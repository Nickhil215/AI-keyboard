/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.ai.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

enum class ModelDownloadError {
    INSUFFICIENT_STORAGE,
    NETWORK,

    /** 401/403: the model is gated and the token is missing, wrong, or the license was not accepted. */
    AUTH_REQUIRED,
    HTTP_ERROR,
    SIZE_MISMATCH,
    INTEGRITY,
    IO,
}

sealed interface ModelInstallState {
    /** @property partialBytes bytes kept from an interrupted download; the next attempt resumes there. */
    data class NotInstalled(val partialBytes: Long = 0) : ModelInstallState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelInstallState
    data object Verifying : ModelInstallState
    data object Installed : ModelInstallState
    data class Failed(val reason: ModelDownloadError, val partialBytes: Long = 0) : ModelInstallState
}

/**
 * Downloads, verifies and removes local models, and publishes their [ModelInstallState].
 *
 * A download never starts by itself: the caller (the user pressing Download after reading the size and
 * license) starts it. It can be cancelled at any time, keeps its partial file for resuming, checks free
 * storage first, and only becomes [ModelInstallState.Installed] after the size and SHA-256 match.
 *
 * @param freeSpace reports usable bytes for a directory; injectable for tests.
 */
class LocalModelManager(
    private val storage: LocalModelStorage,
    private val http: ModelHttp,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val progressStepBytes: Long = 512 * 1024,
) {
    private val states = HashMap<String, MutableStateFlow<ModelInstallState>>()
    private val jobs = HashMap<String, Job>()

    @Synchronized
    fun stateOf(spec: LocalModelSpec): StateFlow<ModelInstallState> = flowOf(spec).asStateFlow()

    @Synchronized
    private fun flowOf(spec: LocalModelSpec): MutableStateFlow<ModelInstallState> {
        return states.getOrPut(spec.id) { MutableStateFlow(stateFromDisk(spec)) }
    }

    private fun stateFromDisk(spec: LocalModelSpec): ModelInstallState {
        return if (storage.isInstalled(spec)) {
            ModelInstallState.Installed
        } else {
            ModelInstallState.NotInstalled(storage.partialBytes(spec))
        }
    }

    /** Re-reads the disk, e.g. after the user deleted files by hand or cleared the app's storage. */
    @Synchronized
    fun refresh(spec: LocalModelSpec) {
        val flow = flowOf(spec)
        if (flow.value !is ModelInstallState.Downloading && flow.value !is ModelInstallState.Verifying) {
            flow.value = stateFromDisk(spec)
        }
    }

    /**
     * Starts a download in the background. Does nothing if one is already running for [spec].
     * Call again after a failure or cancellation to retry; the partial file is resumed.
     */
    @Synchronized
    fun start(spec: LocalModelSpec, authToken: String? = null) {
        if (jobs[spec.id]?.isActive == true) return
        jobs[spec.id] = scope.launch { download(spec, authToken) }
    }

    /** Cancels a running download. The partial file is kept so it can be resumed. */
    @Synchronized
    fun cancel(spec: LocalModelSpec) {
        jobs.remove(spec.id)?.cancel()
    }

    /** Removes the model (and any partial download) and frees the space. */
    @Synchronized
    fun delete(spec: LocalModelSpec) {
        jobs.remove(spec.id)?.cancel()
        storage.delete(spec)
        flowOf(spec).value = ModelInstallState.NotInstalled(0)
    }

    /**
     * Runs the whole download in the calling coroutine. Failures are published as
     * [ModelInstallState.Failed]; cancellation is rethrown after the state is reset.
     */
    suspend fun download(spec: LocalModelSpec, authToken: String? = null) {
        val state = synchronized(this) { flowOf(spec) }
        if (storage.isInstalled(spec)) {
            state.value = ModelInstallState.Installed
            return
        }
        try {
            withContext(ioDispatcher) { downloadBlocking(spec, authToken, state) }
        } catch (e: CancellationException) {
            state.value = ModelInstallState.NotInstalled(storage.partialBytes(spec))
            throw e
        } catch (e: ModelFailure) {
            state.value = ModelInstallState.Failed(e.reason, storage.partialBytes(spec))
        } catch (e: IOException) {
            state.value = ModelInstallState.Failed(ModelDownloadError.NETWORK, storage.partialBytes(spec))
        } catch (e: Exception) {
            state.value = ModelInstallState.Failed(ModelDownloadError.IO, storage.partialBytes(spec))
        }
    }

    private class ModelFailure(val reason: ModelDownloadError) : Exception(reason.name)

    private suspend fun downloadBlocking(
        spec: LocalModelSpec,
        authToken: String?,
        state: MutableStateFlow<ModelInstallState>,
    ) {
        val directory = storage.directory
        if (!directory.isDirectory && !directory.mkdirs()) throw ModelFailure(ModelDownloadError.IO)
        val part = storage.partFileFor(spec)

        var resumeFrom = if (part.isFile) part.length() else 0L
        if (resumeFrom > spec.sizeBytes) {
            part.delete()
            resumeFrom = 0L
        }

        // Fail early with a clear message instead of filling the disk halfway through.
        val needed = spec.sizeBytes - resumeFrom + STORAGE_SAFETY_MARGIN_BYTES
        if (freeSpace(directory) < needed) throw ModelFailure(ModelDownloadError.INSUFFICIENT_STORAGE)

        if (resumeFrom < spec.sizeBytes) {
            state.value = ModelInstallState.Downloading(resumeFrom, spec.sizeBytes)
            resumeFrom = fetchInto(spec, authToken, part, resumeFrom, state)
        }

        if (part.length() != spec.sizeBytes) {
            part.delete()
            throw ModelFailure(ModelDownloadError.SIZE_MISMATCH)
        }

        state.value = ModelInstallState.Verifying
        if (!sha256Of(part).equals(spec.sha256, ignoreCase = true)) {
            // A corrupt or tampered file must never be loaded, and resuming it would not fix it.
            part.delete()
            throw ModelFailure(ModelDownloadError.INTEGRITY)
        }

        Files.move(part.toPath(), storage.fileFor(spec).toPath(), StandardCopyOption.REPLACE_EXISTING)
        state.value = ModelInstallState.Installed
    }

    // Returns the number of bytes now in the part file.
    private suspend fun fetchInto(
        spec: LocalModelSpec,
        authToken: String?,
        part: File,
        startAt: Long,
        state: MutableStateFlow<ModelInstallState>,
    ): Long {
        var resumeFrom = startAt
        var response = http.open(spec.downloadUrl, resumeFrom, authToken)
        if (response.status == 416 && resumeFrom > 0) {
            // The server rejects our offset (the partial file is unusable): start over once.
            response.close()
            part.delete()
            resumeFrom = 0L
            response = http.open(spec.downloadUrl, 0, authToken)
        }
        return response.use { r ->
            when (r.status) {
                200, 206 -> Unit
                401, 403 -> throw ModelFailure(ModelDownloadError.AUTH_REQUIRED)
                else -> throw ModelFailure(ModelDownloadError.HTTP_ERROR)
            }
            // A 200 to a range request means the server ignored the range and is sending the whole file.
            val append = r.status == 206 && resumeFrom > 0
            if (!append) resumeFrom = 0L

            RandomAccessFile(part, "rw").use { file ->
                if (append) file.seek(resumeFrom) else file.setLength(0)
                val buffer = ByteArray(BUFFER_SIZE)
                var written = resumeFrom
                var lastReported = written
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = r.input.read(buffer)
                    if (read < 0) break
                    file.write(buffer, 0, read)
                    written += read
                    if (written > spec.sizeBytes) throw ModelFailure(ModelDownloadError.SIZE_MISMATCH)
                    if (written - lastReported >= progressStepBytes) {
                        lastReported = written
                        state.value = ModelInstallState.Downloading(written, spec.sizeBytes)
                    }
                }
                state.value = ModelInstallState.Downloading(written, spec.sizeBytes)
                written
            }
        }
    }

    private suspend fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val BUFFER_SIZE = 64 * 1024

        /** Extra free space required on top of the model size, for temporary files and the system. */
        const val STORAGE_SAFETY_MARGIN_BYTES = 100L * 1024 * 1024
    }
}

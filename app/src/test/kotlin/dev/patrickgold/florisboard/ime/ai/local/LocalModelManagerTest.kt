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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun sha256Hex(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** A scripted download server. Each call to [open] records its arguments and answers via [handler]. */
private class FakeModelHttp(private val handler: (callIndex: Int, resumeFrom: Long) -> ModelHttpResponse) : ModelHttp {
    val resumeOffsets = mutableListOf<Long>()
    val tokens = mutableListOf<String?>()

    override fun open(url: String, resumeFrom: Long, bearerToken: String?): ModelHttpResponse {
        resumeOffsets += resumeFrom
        tokens += bearerToken
        return handler(resumeOffsets.size - 1, resumeFrom)
    }
}

/** Serves [content] honoring Range requests like a real file server. */
private fun serve(content: ByteArray, resumeFrom: Long): ModelHttpResponse {
    val body = content.copyOfRange(resumeFrom.toInt(), content.size)
    return ModelHttpResponse(if (resumeFrom > 0) 206 else 200, body.size.toLong(), ByteArrayInputStream(body))
}

private fun status(code: Int) = ModelHttpResponse(code, -1, ByteArrayInputStream(ByteArray(0)))

/** A stream that delivers [good] and then fails, like a dropped connection. */
private class FailingAfter(private val good: ByteArray) : InputStream() {
    private var pos = 0
    override fun read(): Int = if (pos < good.size) good[pos++].toInt() and 0xff else throw IOException("connection lost")
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= good.size) throw IOException("connection lost")
        val n = minOf(len, good.size - pos)
        System.arraycopy(good, pos, b, off, n)
        pos += n
        return n
    }
}

class LocalModelManagerTest : FunSpec({
    val content = ByteArray(200_000) { (it * 31 % 251).toByte() }
    val spec = testSpec.copy(sizeBytes = content.size.toLong(), sha256 = sha256Hex(content))

    fun newManager(
        storage: LocalModelStorage,
        http: ModelHttp,
        freeSpace: (java.io.File) -> Long = { Long.MAX_VALUE },
    ) = LocalModelManager(
        storage = storage,
        http = http,
        scope = CoroutineScope(Dispatchers.Unconfined),
        freeSpace = freeSpace,
        progressStepBytes = 1,
    )

    context("successful download") {
        test("downloads, verifies and installs the model") {
            val storage = LocalModelStorage(newTempDir())
            val http = FakeModelHttp { _, from -> serve(content, from) }
            val manager = newManager(storage, http)

            manager.download(spec)

            manager.stateOf(spec).value shouldBe ModelInstallState.Installed
            storage.isInstalled(spec) shouldBe true
            storage.fileFor(spec).readBytes().contentEquals(content) shouldBe true
            storage.partFileFor(spec).exists() shouldBe false
            http.resumeOffsets shouldBe listOf(0L)
        }

        test("the pinned url and no token are used for an ungated model") {
            val storage = LocalModelStorage(newTempDir())
            val http = FakeModelHttp { _, from -> serve(content, from) }
            newManager(storage, http).download(spec)
            http.tokens shouldBe listOf<String?>(null)
        }

        test("a token is handed to the http layer when given") {
            val storage = LocalModelStorage(newTempDir())
            val http = FakeModelHttp { _, from -> serve(content, from) }
            newManager(storage, http).download(spec, authToken = "hf_test")
            http.tokens shouldBe listOf<String?>("hf_test")
        }

        test("an already installed model is not downloaded again") {
            val storage = LocalModelStorage(newTempDir())
            storage.directory.mkdirs()
            storage.fileFor(spec).writeBytes(content)
            val http = FakeModelHttp { _, from -> serve(content, from) }
            val manager = newManager(storage, http)

            manager.download(spec)

            http.resumeOffsets.size shouldBe 0
            manager.stateOf(spec).value shouldBe ModelInstallState.Installed
        }
    }

    context("integrity") {
        test("content that does not match the checksum is rejected and deleted") {
            val storage = LocalModelStorage(newTempDir())
            val tampered = content.copyOf().also { it[100] = (it[100] + 1).toByte() }
            val manager = newManager(storage, FakeModelHttp { _, from -> serve(tampered, from) })

            manager.download(spec)

            manager.stateOf(spec).value.shouldBeInstanceOf<ModelInstallState.Failed>().reason shouldBe ModelDownloadError.INTEGRITY
            storage.isInstalled(spec) shouldBe false
            storage.fileFor(spec).exists() shouldBe false
            storage.partFileFor(spec).exists() shouldBe false
        }

        test("a file that is too short is rejected") {
            val storage = LocalModelStorage(newTempDir())
            val short = content.copyOf(1_000)
            val manager = newManager(storage, FakeModelHttp { _, _ ->
                ModelHttpResponse(200, short.size.toLong(), ByteArrayInputStream(short))
            })

            manager.download(spec)

            manager.stateOf(spec).value.shouldBeInstanceOf<ModelInstallState.Failed>().reason shouldBe ModelDownloadError.SIZE_MISMATCH
            storage.isInstalled(spec) shouldBe false
        }

        test("a server that sends more than the expected size is stopped") {
            val storage = LocalModelStorage(newTempDir())
            val long = content + ByteArray(5_000)
            val manager = newManager(storage, FakeModelHttp { _, _ ->
                ModelHttpResponse(200, long.size.toLong(), ByteArrayInputStream(long))
            })

            manager.download(spec)

            manager.stateOf(spec).value.shouldBeInstanceOf<ModelInstallState.Failed>().reason shouldBe ModelDownloadError.SIZE_MISMATCH
            storage.isInstalled(spec) shouldBe false
        }

        test("a model file with the wrong size on disk is not treated as installed") {
            val storage = LocalModelStorage(newTempDir())
            storage.directory.mkdirs()
            storage.fileFor(spec).writeBytes(content.copyOf(10))
            storage.isInstalled(spec) shouldBe false
            newManager(storage, FakeModelHttp { _, _ -> status(500) }).stateOf(spec).value
                .shouldBeInstanceOf<ModelInstallState.NotInstalled>()
        }
    }

    context("storage") {
        test("not enough free space fails before any request") {
            val storage = LocalModelStorage(newTempDir())
            val http = FakeModelHttp { _, from -> serve(content, from) }
            val manager = newManager(storage, http, freeSpace = { 10L })

            manager.download(spec)

            manager.stateOf(spec).value.shouldBeInstanceOf<ModelInstallState.Failed>().reason shouldBe ModelDownloadError.INSUFFICIENT_STORAGE
            http.resumeOffsets.size shouldBe 0
        }

        test("only the remaining bytes of a partial download need free space") {
            val storage = LocalModelStorage(newTempDir())
            storage.directory.mkdirs()
            storage.partFileFor(spec).writeBytes(content.copyOf(150_000))
            // Enough for the missing 50 KB plus the safety margin, but not for a whole fresh download.
            val free = (spec.sizeBytes - 150_000) + LocalModelManager.STORAGE_SAFETY_MARGIN_BYTES
            val manager = newManager(storage, FakeModelHttp { _, from -> serve(content, from) }, freeSpace = { free })

            manager.download(spec)

            manager.stateOf(spec).value shouldBe ModelInstallState.Installed
        }
    }

    context("failures and retry") {
        test("a dropped connection keeps the partial file and the retry resumes from it") {
            val storage = LocalModelStorage(newTempDir())
            val http = FakeModelHttp { call, from ->
                if (call == 0) {
                    ModelHttpResponse(200, content.size.toLong(), FailingAfter(content.copyOf(80_000)))
                } else {
                    serve(content, from)
                }
            }
            val manager = newManager(storage, http)

            manager.download(spec)
            val failed = manager.stateOf(spec).value.shouldBeInstanceOf<ModelInstallState.Failed>()
            failed.reason shouldBe ModelDownloadError.NETWORK
            failed.partialBytes shouldBe 80_000L

            manager.download(spec) // the user taps "Resume"

            http.resumeOffsets shouldBe listOf(0L, 80_000L)
            manager.stateOf(spec).value shouldBe ModelInstallState.Installed
            storage.fileFor(spec).readBytes().contentEquals(content) shouldBe true
        }

        test("a server that ignores the range restarts cleanly from the beginning") {
            val storage = LocalModelStorage(newTempDir())
            storage.directory.mkdirs()
            storage.partFileFor(spec).writeBytes(content.copyOf(50_000))
            val manager = newManager(storage, FakeModelHttp { _, _ ->
                ModelHttpResponse(200, content.size.toLong(), ByteArrayInputStream(content))
            })

            manager.download(spec)

            manager.stateOf(spec).value shouldBe ModelInstallState.Installed
            storage.fileFor(spec).readBytes().contentEquals(content) shouldBe true
        }

        test("a rejected range (416) discards the partial file and starts over once") {
            val storage = LocalModelStorage(newTempDir())
            storage.directory.mkdirs()
            storage.partFileFor(spec).writeBytes(content.copyOf(50_000))
            val http = FakeModelHttp { call, from -> if (call == 0) status(416) else serve(content, from) }
            val manager = newManager(storage, http)

            manager.download(spec)

            http.resumeOffsets shouldBe listOf(50_000L, 0L)
            manager.stateOf(spec).value shouldBe ModelInstallState.Installed
        }

        test("a partial file larger than the model is discarded") {
            val storage = LocalModelStorage(newTempDir())
            storage.directory.mkdirs()
            storage.partFileFor(spec).writeBytes(ByteArray(content.size + 10))
            val http = FakeModelHttp { _, from -> serve(content, from) }
            val manager = newManager(storage, http)

            manager.download(spec)

            http.resumeOffsets shouldBe listOf(0L)
            manager.stateOf(spec).value shouldBe ModelInstallState.Installed
        }

        test("401 and 403 mean access is required (gated model)") {
            for (code in listOf(401, 403)) {
                val storage = LocalModelStorage(newTempDir())
                val manager = newManager(storage, FakeModelHttp { _, _ -> status(code) })
                manager.download(spec)
                manager.stateOf(spec).value.shouldBeInstanceOf<ModelInstallState.Failed>().reason shouldBe ModelDownloadError.AUTH_REQUIRED
            }
        }

        test("other http errors are reported") {
            val storage = LocalModelStorage(newTempDir())
            val manager = newManager(storage, FakeModelHttp { _, _ -> status(500) })
            manager.download(spec)
            manager.stateOf(spec).value.shouldBeInstanceOf<ModelInstallState.Failed>().reason shouldBe ModelDownloadError.HTTP_ERROR
        }

        test("an exception from the http layer is a network failure, not a crash") {
            val storage = LocalModelStorage(newTempDir())
            val manager = newManager(storage, FakeModelHttp { _, _ -> throw IOException("no route") })
            manager.download(spec)
            manager.stateOf(spec).value.shouldBeInstanceOf<ModelInstallState.Failed>().reason shouldBe ModelDownloadError.NETWORK
        }
    }

    context("cancellation") {
        test("cancelling mid-download stops it, keeps the partial file, and can be resumed") {
            val storage = LocalModelStorage(newTempDir())
            val reached = CountDownLatch(1)
            val release = CountDownLatch(1)
            val http = FakeModelHttp { call, from ->
                if (call == 0) {
                    val stream = object : InputStream() {
                        var sent = false
                        override fun read(): Int = throw UnsupportedOperationException()
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (!sent) {
                                sent = true
                                val n = minOf(len, 30_000)
                                System.arraycopy(content, 0, b, off, n)
                                return n
                            }
                            // Pretend the network stalls here until the test has cancelled.
                            reached.countDown()
                            release.await(10, TimeUnit.SECONDS)
                            val n = minOf(len, 1_000)
                            System.arraycopy(content, 30_000, b, off, n)
                            return n
                        }
                    }
                    ModelHttpResponse(200, content.size.toLong(), stream)
                } else {
                    serve(content, from)
                }
            }
            val manager = newManager(storage, http)

            val job = CoroutineScope(Dispatchers.Default).launch { manager.download(spec) }
            reached.await(10, TimeUnit.SECONDS) shouldBe true
            job.cancel()
            release.countDown()
            job.join()

            job.isCancelled shouldBe true
            val state = manager.stateOf(spec).value.shouldBeInstanceOf<ModelInstallState.NotInstalled>()
            (state.partialBytes > 0) shouldBe true
            storage.isInstalled(spec) shouldBe false

            manager.download(spec)
            manager.stateOf(spec).value shouldBe ModelInstallState.Installed
            http.resumeOffsets.last() shouldBe state.partialBytes
        }
    }

    context("state and deletion") {
        test("the initial state reflects what is on disk") {
            val storage = LocalModelStorage(newTempDir())
            val http = FakeModelHttp { _, _ -> status(500) }
            newManager(storage, http).stateOf(spec).value shouldBe ModelInstallState.NotInstalled(0)

            storage.directory.mkdirs()
            storage.partFileFor(spec).writeBytes(ByteArray(123))
            newManager(storage, http).stateOf(spec).value shouldBe ModelInstallState.NotInstalled(123)

            storage.fileFor(spec).writeBytes(content)
            newManager(storage, http).stateOf(spec).value shouldBe ModelInstallState.Installed
        }

        test("delete removes the model and the partial file") {
            val storage = LocalModelStorage(newTempDir())
            val manager = newManager(storage, FakeModelHttp { _, from -> serve(content, from) })
            manager.download(spec)
            storage.partFileFor(spec).writeBytes(ByteArray(5))

            manager.delete(spec)

            storage.fileFor(spec).exists() shouldBe false
            storage.partFileFor(spec).exists() shouldBe false
            manager.stateOf(spec).value shouldBe ModelInstallState.NotInstalled(0)
        }

        test("refresh notices a model that was removed behind the app's back") {
            val storage = LocalModelStorage(newTempDir())
            val manager = newManager(storage, FakeModelHttp { _, from -> serve(content, from) })
            manager.download(spec)
            storage.fileFor(spec).delete()

            manager.refresh(spec)

            manager.stateOf(spec).value shouldBe ModelInstallState.NotInstalled(0)
        }

        test("models are stored by file name inside the given private directory only") {
            val dir = newTempDir()
            val storage = LocalModelStorage(dir)
            storage.fileFor(spec).parentFile shouldBe dir
            storage.partFileFor(spec).name shouldBe spec.fileName + ".part"
        }
    }
})

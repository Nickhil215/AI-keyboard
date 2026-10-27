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

import java.io.File

/**
 * A loaded on-device model that can answer one prompt at a time.
 *
 * Implementations must never block the caller's thread (run native work on a background dispatcher),
 * must stop promptly when the calling coroutine is cancelled, and must throw
 * [dev.patrickgold.florisboard.ime.ai.AiProcessingException] with a specific error where they can
 * (initialization failure, out of memory, inference failure).
 */
interface LocalInferenceEngine {
    suspend fun generate(systemPrompt: String, userMessage: String, maxOutputTokens: Int): String

    /** Frees the native model memory. The engine must not be used afterwards. */
    fun close()
}

/**
 * Keeps a single loaded engine and reuses it for every request, because loading a model takes seconds
 * and hundreds of megabytes. The engine is replaced if a different model file is requested and released
 * on demand (idle timeout, memory pressure, model deletion).
 *
 * @param factory creates an engine for a model file. Called at most once per file until [release].
 */
class LocalEngineHolder(
    private val clock: () -> Long = System::currentTimeMillis,
    private val factory: (File, LocalModelSpec) -> LocalInferenceEngine,
) {
    private var engine: LocalInferenceEngine? = null
    private var loadedFile: String? = null
    private var lastUsedAt = 0L

    @Synchronized
    fun get(file: File, spec: LocalModelSpec): LocalInferenceEngine {
        val key = file.absolutePath
        val existing = engine
        lastUsedAt = clock()
        if (existing != null && loadedFile == key) return existing
        existing?.close()
        return factory(file, spec).also {
            engine = it
            loadedFile = key
        }
    }

    /** Marks the engine as just used, e.g. when a request finishes, so the idle timer starts from then. */
    @Synchronized
    fun touch() {
        lastUsedAt = clock()
    }

    /** Releases the engine if it has not been used for [idleMs]. Returns whether it was released. */
    @Synchronized
    fun releaseIfIdle(idleMs: Long): Boolean {
        if (engine == null || clock() - lastUsedAt < idleMs) return false
        release()
        return true
    }

    @Synchronized
    fun release() {
        engine?.close()
        engine = null
        loadedFile = null
    }

    @get:Synchronized
    val isLoaded: Boolean get() = engine != null
}

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

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Process-wide access to the on-device model: storage, downloads and the loaded engine.
 *
 * Models live in the app-private `filesDir`, which other apps cannot read, and which is not part of the
 * backed-up paths. The engine is released after [IDLE_RELEASE_MS] without use, because a loaded model
 * holds several hundred megabytes inside the keyboard process.
 */
object LocalModels {
    const val IDLE_RELEASE_MS = 2 * 60 * 1000L
    private const val IDLE_CHECK_MS = 30 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var storage: LocalModelStorage? = null

    @Volatile
    private var manager: LocalModelManager? = null

    @Volatile
    private var engines: LocalEngineHolder? = null

    @Synchronized
    fun storage(context: Context): LocalModelStorage {
        return storage ?: LocalModelStorage(File(context.applicationContext.filesDir, "ai-models")).also { storage = it }
    }

    @Synchronized
    fun manager(context: Context): LocalModelManager {
        return manager ?: LocalModelManager(
            storage = storage(context),
            http = HttpUrlModelHttp(),
        ).also { manager = it }
    }

    @Synchronized
    fun engines(context: Context): LocalEngineHolder {
        engines?.let { return it }
        val appContext = context.applicationContext
        val holder = LocalEngineHolder { file, spec ->
            LiteRtLmInferenceEngine(
                modelFile = file,
                cacheDir = File(appContext.cacheDir, "litertlm"),
                contextTokens = spec.contextTokens,
            )
        }
        engines = holder
        scope.launch {
            while (true) {
                delay(IDLE_CHECK_MS)
                holder.releaseIfIdle(IDLE_RELEASE_MS)
            }
        }
        return holder
    }

    /** Deletes a model and frees its memory if it is the one currently loaded. */
    fun delete(context: Context, spec: LocalModelSpec) {
        engines?.release()
        manager(context).delete(spec)
    }
}

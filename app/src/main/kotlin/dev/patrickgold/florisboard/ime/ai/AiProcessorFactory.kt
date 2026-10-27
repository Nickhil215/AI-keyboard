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

package dev.patrickgold.florisboard.ime.ai

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore

/**
 * Wires the user's settings into an [AiTextProcessor].
 */
object AiProcessorFactory {
    fun create(context: Context): AiTextProcessor {
        val appContext = context.applicationContext
        return LlmAiTextProcessor(
            configProvider = { loadConfig(appContext) },
            transport = HttpUrlConnectionTransport(),
        )
    }

    /**
     * Reads the current settings. Returns null when the feature is switched off, so that a processor
     * can never send anything while the user has AI disabled.
     */
    fun loadConfig(context: Context): AiProviderConfig? {
        val prefs by FlorisPreferenceStore
        if (!prefs.ai.enabled.get()) return null
        return AiProviderConfig(
            endpointUrl = prefs.ai.endpointUrl.get(),
            model = prefs.ai.model.get(),
            apiKey = KeystoreAiCredentialStore(context).getApiKey(),
        )
    }
}

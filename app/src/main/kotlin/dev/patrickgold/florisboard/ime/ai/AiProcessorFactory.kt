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
import dev.patrickgold.florisboard.ime.ai.local.AndroidDeviceCapabilities
import dev.patrickgold.florisboard.ime.ai.local.LocalModelCatalog
import dev.patrickgold.florisboard.ime.ai.local.LocalModelProvider
import dev.patrickgold.florisboard.ime.ai.local.LocalModels

/**
 * Wires the user's settings into an [AiTextProcessor].
 */
object AiProcessorFactory {
    /**
     * Creates the processor for the provider selected in settings. A new processor is cheap to build; the
     * expensive state (the loaded model) lives in [LocalModels] and is shared.
     */
    fun create(context: Context): AiTextProcessor {
        val prefs by FlorisPreferenceStore
        return selector(context).select(prefs.ai.provider.get())
    }

    fun selector(context: Context): AiProviderSelector {
        val appContext = context.applicationContext
        val prefs by FlorisPreferenceStore
        return AiProviderSelector(
            mapOf(
                AiProviderType.LOCAL to {
                    LocalModelProvider(
                        model = { LocalModelCatalog.byId(prefs.ai.localModelId.get()) },
                        storage = LocalModels.storage(appContext),
                        device = AndroidDeviceCapabilities(appContext),
                        engines = LocalModels.engines(appContext),
                    )
                },
                AiProviderType.OPENAI to { remote(appContext, AiProviderType.OPENAI) },
                AiProviderType.GEMINI to { remote(appContext, AiProviderType.GEMINI) },
                AiProviderType.ANTHROPIC to { remote(appContext, AiProviderType.ANTHROPIC) },
                AiProviderType.CUSTOM to { remote(appContext, AiProviderType.CUSTOM) },
            )
        )
    }

    /** Creates the cloud provider for [type], for sending requests or for "Test connection". */
    fun remote(context: Context, type: AiProviderType, requireEnabledSwitch: Boolean = true): RemoteAiProvider {
        require(type.isRemote) { "$type is not a remote provider" }
        val appContext = context.applicationContext
        val configProvider = { loadConfig(appContext, type, requireEnabledSwitch) }
        val transport = HttpUrlConnectionTransport()
        return when (type) {
            AiProviderType.ANTHROPIC -> AnthropicProvider(configProvider, transport)
            else -> OpenAiCompatibleProvider(configProvider, transport)
        }
    }

    /**
     * Reads the current settings for a cloud provider. Returns null when the feature is switched off, so
     * a processor can never send anything while the user has AI disabled. "Test connection" is an explicit
     * user action and passes `requireEnabledSwitch = false`.
     */
    fun loadConfig(context: Context, type: AiProviderType, requireEnabledSwitch: Boolean = true): AiProviderConfig? {
        val prefs by FlorisPreferenceStore
        if (requireEnabledSwitch && !prefs.ai.enabled.get()) return null
        val remote = prefs.ai.remote(type)
        return AiProviderConfig(
            endpointUrl = remote.endpointUrl.get(),
            model = remote.model.get(),
            apiKey = KeystoreAiCredentialStore(context).getSecret(type.credentialSlot),
            connectTimeoutMs = prefs.ai.connectTimeoutSeconds.get() * 1000,
            readTimeoutMs = prefs.ai.requestTimeoutSeconds.get() * 1000,
        )
    }
}

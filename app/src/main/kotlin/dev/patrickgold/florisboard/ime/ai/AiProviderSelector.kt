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

/**
 * Picks the processor for the provider the user selected.
 *
 * Providers are passed as factories and only the selected one is invoked. That keeps the cloud
 * providers uninitialized for on-device use, and the on-device engine untouched for cloud use.
 * There is deliberately no fallback logic here: if the selected provider fails, the user is told and
 * decides, so text is never silently sent somewhere they did not choose.
 */
class AiProviderSelector(private val factories: Map<AiProviderType, () -> AiTextProcessor>) {
    fun select(type: AiProviderType): AiTextProcessor {
        val factory = factories[type] ?: throw AiProcessingException(AiError.NOT_CONFIGURED)
        return factory()
    }
}

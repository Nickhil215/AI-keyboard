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
 * The inference backends the user can choose between in the AI settings.
 *
 * [LOCAL] is the default and needs no credentials or network once its model is installed. The others
 * send the selected text to a third party and are only ever constructed if the user selects them.
 *
 * Gemini is served through Google's OpenAI-compatible endpoint, so it reuses the OpenAI-compatible
 * adapter. Anthropic's Messages API is not OpenAI-compatible and has its own adapter.
 */
enum class AiProviderType(
    val isRemote: Boolean,
    val defaultEndpoint: String,
    val defaultModel: String,
) {
    LOCAL(isRemote = false, defaultEndpoint = "", defaultModel = ""),
    OPENAI(isRemote = true, defaultEndpoint = "https://api.openai.com/v1", defaultModel = "gpt-4o-mini"),
    GEMINI(
        isRemote = true,
        defaultEndpoint = "https://generativelanguage.googleapis.com/v1beta/openai",
        defaultModel = "gemini-2.5-flash",
    ),
    ANTHROPIC(isRemote = true, defaultEndpoint = "https://api.anthropic.com", defaultModel = "claude-haiku-5-5"),
    CUSTOM(isRemote = true, defaultEndpoint = "", defaultModel = ""),
    ;

    /** Name of the encrypted-credential slot holding this provider's API key. */
    val credentialSlot: String get() = name.lowercase()
}

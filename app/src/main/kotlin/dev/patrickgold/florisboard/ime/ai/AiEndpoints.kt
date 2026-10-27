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
 * Turns the base URL (or full endpoint) a user typed into the exact URL for each API call, so both
 * `https://api.openai.com/v1` and `https://api.openai.com/v1/chat/completions` work.
 */
object AiEndpoints {
    private const val CHAT_COMPLETIONS = "/chat/completions"

    fun chatCompletionsUrl(base: String): String {
        val url = base.trim().trimEnd('/')
        return if (url.endsWith(CHAT_COMPLETIONS)) url else url + CHAT_COMPLETIONS
    }

    fun openAiModelsUrl(base: String): String {
        val url = base.trim().trimEnd('/')
        return (if (url.endsWith(CHAT_COMPLETIONS)) url.removeSuffix(CHAT_COMPLETIONS) else url) + "/models"
    }

    fun anthropicMessagesUrl(base: String): String = anthropicRoot(base) + "/messages"

    fun anthropicModelsUrl(base: String): String = anthropicRoot(base) + "/models"

    // Accepts "https://host", "https://host/v1" and "https://host/v1/messages".
    private fun anthropicRoot(base: String): String {
        var url = base.trim().trimEnd('/')
        url = url.removeSuffix("/messages").trimEnd('/')
        return if (url.endsWith("/v1")) url else "$url/v1"
    }
}

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

import java.net.URI

/**
 * Connection settings for an OpenAI-compatible chat completions endpoint.
 *
 * [toString] is overridden so the API key can never end up in logs or crash reports.
 */
class AiProviderConfig(
    val endpointUrl: String,
    val model: String,
    val apiKey: String?,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    val maxInputChars: Int = DEFAULT_MAX_INPUT_CHARS,
    val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
) {
    override fun toString(): String = "AiProviderConfig(host=${hostOrNull(endpointUrl)}, model=$model, apiKey=<redacted>)"

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val DEFAULT_READ_TIMEOUT_MS = 30_000
        const val DEFAULT_MAX_ATTEMPTS = 2
        const val DEFAULT_RETRY_DELAY_MS = 600L
        const val DEFAULT_MAX_INPUT_CHARS = 4_000
        const val DEFAULT_MAX_RESPONSE_BYTES = 256 * 1024

        /**
         * Returns the host of [endpointUrl] if it is a valid https endpoint without embedded
         * credentials, or null otherwise. Cleartext http is rejected on purpose, because the request
         * carries both the user's text and the API key.
         */
        fun hostOrNull(endpointUrl: String): String? {
            val uri = try {
                URI(endpointUrl.trim())
            } catch (_: Exception) {
                return null
            }
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            if (uri.userInfo != null) return null
            return uri.host?.takeIf { it.isNotBlank() }
        }
    }
}

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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * [AiTextProcessor] backed by any OpenAI-compatible `/chat/completions` endpoint
 * (OpenAI, OpenRouter, Groq, Gemini's compatibility endpoint, self-hosted gateways, ...).
 *
 * @param configProvider returns the current settings, or null if the feature is not configured. It is
 * read for every call, so settings changes apply immediately.
 * @param transport performs the actual HTTP call.
 * @param pause suspends between retries; replaced in tests to avoid real delays.
 */
class LlmAiTextProcessor(
    private val configProvider: () -> AiProviderConfig?,
    private val transport: AiHttpTransport,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : AiTextProcessor {
    override suspend fun fixGrammar(text: String): String {
        return process(AiPrompts.fixGrammarSystemPrompt, text)
    }

    override suspend fun rewrite(text: String, tone: RewriteTone?): String {
        return process(AiPrompts.rewriteSystemPrompt(tone), text)
    }

    private suspend fun process(systemPrompt: String, text: String): String {
        if (text.isBlank()) throw AiProcessingException(AiError.EMPTY_INPUT)
        val config = configProvider() ?: throw AiProcessingException(AiError.NOT_CONFIGURED)
        if (config.apiKey.isNullOrBlank() || config.model.isBlank()) {
            throw AiProcessingException(AiError.NOT_CONFIGURED)
        }
        if (AiProviderConfig.hostOrNull(config.endpointUrl) == null) {
            throw AiProcessingException(AiError.INVALID_ENDPOINT)
        }
        if (text.length > config.maxInputChars) throw AiProcessingException(AiError.INPUT_TOO_LONG)

        val request = AiHttpRequest(
            url = config.endpointUrl.trim(),
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "Authorization" to "Bearer ${config.apiKey}",
            ),
            body = buildBody(config.model, systemPrompt, text),
            connectTimeoutMs = config.connectTimeoutMs,
            readTimeoutMs = config.readTimeoutMs,
            maxResponseBytes = config.maxResponseBytes,
        )
        val response = postWithRetry(request, config)
        val content = extractContent(response.body)
        return AiOutputSanitizer.clean(original = text, raw = content)
    }

    private fun buildBody(model: String, systemPrompt: String, text: String): String {
        return buildJsonObject {
            put("model", model)
            put("stream", false)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", AiPrompts.wrapUserText(text))
                })
            })
        }.toString()
    }

    // Only transient failures (network, timeout, 429, 5xx) are retried, and only up to maxAttempts.
    // Auth and request errors fail immediately, as repeating them cannot succeed.
    private suspend fun postWithRetry(request: AiHttpRequest, config: AiProviderConfig): AiHttpResponse {
        val attempts = config.maxAttempts.coerceAtLeast(1)
        var attempt = 0
        while (true) {
            attempt++
            val failure: AiProcessingException = try {
                val response = transport.post(request)
                when (response.status) {
                    in 200..299 -> return response
                    401, 403 -> throw AiProcessingException(AiError.AUTH, response.status)
                    429 -> AiProcessingException(AiError.RATE_LIMITED, response.status)
                    in 500..599 -> AiProcessingException(AiError.SERVER, response.status)
                    in 400..499 -> throw AiProcessingException(AiError.PROVIDER_REJECTED, response.status)
                    else -> throw AiProcessingException(AiError.SERVER, response.status)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiProcessingException) {
                if (e.error == AiError.AUTH || e.error == AiError.PROVIDER_REJECTED) throw e
                e
            } catch (e: AiResponseTooLargeException) {
                throw AiProcessingException(AiError.RESPONSE_TOO_LARGE, cause = e)
            } catch (e: SocketTimeoutException) {
                AiProcessingException(AiError.TIMEOUT, cause = e)
            } catch (e: IOException) {
                AiProcessingException(AiError.NETWORK, cause = e)
            } catch (e: Exception) {
                throw AiProcessingException(AiError.UNKNOWN, cause = e)
            }
            if (attempt >= attempts) throw failure
            pause(config.retryDelayMs * attempt)
        }
    }

    // Reads choices[0].message.content. A finish_reason of "length" means the model was cut off, so
    // the text would be incomplete and applying it could silently drop part of the user's sentence.
    private fun extractContent(body: String): String {
        val root = try {
            Json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw AiProcessingException(AiError.INVALID_RESPONSE)
        }
        val choice = ((root as? JsonObject)?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw AiProcessingException(AiError.INVALID_RESPONSE)
        if ((choice["finish_reason"] as? JsonPrimitive)?.contentOrNull == "length") {
            throw AiProcessingException(AiError.INVALID_RESPONSE)
        }
        val content = ((choice["message"] as? JsonObject)?.get("content") as? JsonPrimitive)
        if (content == null || !content.isString) throw AiProcessingException(AiError.INVALID_RESPONSE)
        return content.content
    }
}

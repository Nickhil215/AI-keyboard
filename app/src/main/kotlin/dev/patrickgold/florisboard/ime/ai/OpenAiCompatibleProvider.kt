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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Provider for any OpenAI-compatible `/chat/completions` endpoint: OpenAI itself, Google Gemini's
 * OpenAI-compatibility endpoint, OpenRouter, Groq, and self-hosted gateways.
 */
class OpenAiCompatibleProvider(
    configProvider: () -> AiProviderConfig?,
    transport: AiHttpTransport,
    pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : RemoteAiProvider(configProvider, transport, pause) {
    override fun buildRequest(config: AiProviderConfig, systemPrompt: String, text: String): AiHttpRequest {
        // No temperature / max_tokens: newer models reject or rename them, and the answer size is
        // already bounded by the response size limit and by validation of the result.
        val body = buildJsonObject {
            put("model", config.model)
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
        return AiHttpRequest(
            url = AiEndpoints.chatCompletionsUrl(config.endpointUrl),
            headers = headers(config),
            body = body,
            connectTimeoutMs = config.connectTimeoutMs,
            readTimeoutMs = config.readTimeoutMs,
            maxResponseBytes = config.maxResponseBytes,
        )
    }

    override fun buildConnectionTestRequest(config: AiProviderConfig): AiHttpRequest {
        return AiHttpRequest(
            url = AiEndpoints.openAiModelsUrl(config.endpointUrl),
            headers = headers(config),
            body = null,
            connectTimeoutMs = config.connectTimeoutMs,
            readTimeoutMs = config.readTimeoutMs,
            maxResponseBytes = config.maxResponseBytes,
            method = "GET",
        )
    }

    private fun headers(config: AiProviderConfig) = mapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "Authorization" to "Bearer ${config.apiKey}",
    )

    // Reads choices[0].message.content. A finish_reason of "length" means the model was cut off, so
    // the text would be incomplete and applying it could silently drop part of the user's sentence.
    override fun extractContent(body: String): String {
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

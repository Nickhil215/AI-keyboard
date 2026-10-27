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
 * Native adapter for Anthropic's Messages API, which differs from the OpenAI format: the key goes in
 * `x-api-key`, an `anthropic-version` header is required, the system prompt is a top-level field,
 * `max_tokens` is mandatory, and the answer is a list of content blocks.
 */
class AnthropicProvider(
    configProvider: () -> AiProviderConfig?,
    transport: AiHttpTransport,
    pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : RemoteAiProvider(configProvider, transport, pause) {
    override fun buildRequest(config: AiProviderConfig, systemPrompt: String, text: String): AiHttpRequest {
        val body = buildJsonObject {
            put("model", config.model)
            put("max_tokens", outputTokenLimit(text))
            put("system", systemPrompt)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", AiPrompts.wrapUserText(text))
                })
            })
        }.toString()
        return AiHttpRequest(
            url = AiEndpoints.anthropicMessagesUrl(config.endpointUrl),
            headers = headers(config),
            body = body,
            connectTimeoutMs = config.connectTimeoutMs,
            readTimeoutMs = config.readTimeoutMs,
            maxResponseBytes = config.maxResponseBytes,
        )
    }

    override fun buildConnectionTestRequest(config: AiProviderConfig): AiHttpRequest {
        return AiHttpRequest(
            url = AiEndpoints.anthropicModelsUrl(config.endpointUrl),
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
        "x-api-key" to (config.apiKey ?: ""),
        "anthropic-version" to API_VERSION,
    )

    // Joins the text blocks of the answer. stop_reason "max_tokens" means the answer was cut off.
    override fun extractContent(body: String): String {
        val parsed = try {
            Json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw AiProcessingException(AiError.INVALID_RESPONSE)
        }
        val root = parsed as? JsonObject ?: throw AiProcessingException(AiError.INVALID_RESPONSE)
        if ((root["stop_reason"] as? JsonPrimitive)?.contentOrNull == "max_tokens") {
            throw AiProcessingException(AiError.INVALID_RESPONSE)
        }
        val blocks = root["content"] as? JsonArray ?: throw AiProcessingException(AiError.INVALID_RESPONSE)
        val text = blocks
            .mapNotNull { it as? JsonObject }
            .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
            .mapNotNull { (it["text"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .joinToString("")
        if (text.isEmpty() && blocks.none { (it as? JsonObject)?.get("type") != null }) {
            throw AiProcessingException(AiError.INVALID_RESPONSE)
        }
        return text
    }

    companion object {
        const val API_VERSION = "2023-06-01"
    }
}

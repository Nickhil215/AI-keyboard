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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json

// Shared fakes for the AI tests. Nothing here touches the network or needs an API key.

const val TEST_API_KEY = "sk-test-secret-key-123"

fun testConfig(
    apiKey: String? = TEST_API_KEY,
    endpoint: String = "https://api.example.com/v1/chat/completions",
    model: String = "test-model",
    maxAttempts: Int = 2,
    maxInputChars: Int = 4_000,
) = AiProviderConfig(
    endpointUrl = endpoint,
    model = model,
    apiKey = apiKey,
    maxAttempts = maxAttempts,
    retryDelayMs = 0,
    maxInputChars = maxInputChars,
)

/** A well-formed chat completions response carrying [content]. */
fun completionBody(content: String, finishReason: String = "stop"): String {
    return """{"choices":[{"index":0,"message":{"role":"assistant","content":${JsonPrimitive(content)}},"finish_reason":"$finishReason"}]}"""
}

fun ok(content: String) = AiHttpResponse(200, completionBody(content))

/** The full user message of a request, including the delimiter tags. */
fun rawUserMessageOf(request: AiHttpRequest): String {
    val messages: JsonArray = Json.parseToJsonElement(request.body!!).jsonObject["messages"]!!.jsonArray
    val user: JsonObject = messages.map { it.jsonObject }.first { it["role"]!!.jsonPrimitive.content == "user" }
    return user["content"]!!.jsonPrimitive.content
}

/** Extracts the text between the user_text tags from a request body. */
fun userTextOf(request: AiHttpRequest): String {
    return rawUserMessageOf(request)
        .removePrefix(AiPrompts.OPEN_TAG + "\n")
        .removeSuffix("\n" + AiPrompts.CLOSE_TAG)
}

fun systemPromptOf(request: AiHttpRequest): String {
    val messages = Json.parseToJsonElement(request.body!!).jsonObject["messages"]!!.jsonArray
    return messages.map { it.jsonObject }.first { it["role"]!!.jsonPrimitive.content == "system" }["content"]!!
        .jsonPrimitive.content
}

class FakeTransport(private val handler: suspend (AiHttpRequest) -> AiHttpResponse) : AiHttpTransport {
    val requests = mutableListOf<AiHttpRequest>()
    val callCount get() = requests.size

    override suspend fun send(request: AiHttpRequest): AiHttpResponse {
        requests += request
        return handler(request)
    }
}

fun anthropicWith(
    config: AiProviderConfig? = testConfig(endpoint = "https://api.anthropic.example.com"),
    handler: suspend (AiHttpRequest) -> AiHttpResponse,
): Pair<AnthropicProvider, FakeTransport> {
    val transport = FakeTransport(handler)
    return AnthropicProvider(configProvider = { config }, transport = transport, pause = {}) to transport
}

fun anthropicOk(text: String, stopReason: String = "end_turn") = AiHttpResponse(
    200,
    """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":${JsonPrimitive(text)}}],"stop_reason":"$stopReason"}""",
)

fun processorWith(
    config: AiProviderConfig? = testConfig(),
    handler: suspend (AiHttpRequest) -> AiHttpResponse,
): Pair<OpenAiCompatibleProvider, FakeTransport> {
    val transport = FakeTransport(handler)
    return OpenAiCompatibleProvider(configProvider = { config }, transport = transport, pause = {}) to transport
}

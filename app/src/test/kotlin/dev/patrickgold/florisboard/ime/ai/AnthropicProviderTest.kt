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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.SocketTimeoutException

class AnthropicProviderTest : FunSpec({
    suspend fun AnthropicProvider.errorOf(block: suspend AnthropicProvider.() -> Unit): AiProcessingException {
        return shouldThrow<AiProcessingException> { block() }
    }

    context("requests") {
        test("uses the native Messages API shape and headers") {
            val (provider, transport) = anthropicWith { anthropicOk("Hello.") }
            provider.fixGrammar("helo")

            val request = transport.requests.single()
            request.method shouldBe "POST"
            request.url shouldBe "https://api.anthropic.example.com/v1/messages"
            request.headers["x-api-key"] shouldBe TEST_API_KEY
            request.headers["anthropic-version"] shouldBe AnthropicProvider.API_VERSION
            // Anthropic does not use the OpenAI bearer scheme.
            request.headers.containsKey("Authorization") shouldBe false

            val body = Json.parseToJsonElement(request.body!!).jsonObject
            body["model"]!!.jsonPrimitive.content shouldBe "test-model"
            body["system"]!!.jsonPrimitive.content shouldBe AiPrompts.fixGrammarSystemPrompt
            (body["max_tokens"]!!.jsonPrimitive.int >= 256) shouldBe true
            val message = body["messages"]!!.jsonArray.single().jsonObject
            message["role"]!!.jsonPrimitive.content shouldBe "user"
            message["content"]!!.jsonPrimitive.content shouldBe AiPrompts.wrapUserText("helo")
        }

        test("rewrite sends the tone in the system prompt") {
            val (provider, transport) = anthropicWith { anthropicOk("Hi.") }
            provider.rewrite("hey", RewriteTone.CONCISE)
            val body = Json.parseToJsonElement(transport.requests.single().body!!).jsonObject
            body["system"]!!.jsonPrimitive.content shouldBe AiPrompts.rewriteSystemPrompt(RewriteTone.CONCISE)
        }

        test("accepts a base URL, a /v1 URL and a full messages URL") {
            for (endpoint in listOf(
                "https://api.anthropic.example.com",
                "https://api.anthropic.example.com/",
                "https://api.anthropic.example.com/v1",
                "https://api.anthropic.example.com/v1/messages",
            )) {
                val (provider, transport) = anthropicWith(testConfig(endpoint = endpoint)) { anthropicOk("Hi.") }
                provider.fixGrammar("hi")
                transport.requests.single().url shouldBe "https://api.anthropic.example.com/v1/messages"
            }
        }

        test("the text is never placed in the system prompt") {
            val (provider, transport) = anthropicWith { anthropicOk("x") }
            provider.fixGrammar("my secret sentence")
            val body = Json.parseToJsonElement(transport.requests.single().body!!).jsonObject
            body["system"]!!.jsonPrimitive.content shouldNotContain "my secret sentence"
        }
    }

    context("responses") {
        test("returns the text of the answer") {
            val (provider, _) = anthropicWith { anthropicOk("I was busy yesterday.") }
            provider.fixGrammar("i was busy yesterday") shouldBe "I was busy yesterday."
        }

        test("joins several text blocks and ignores other block types") {
            val (provider, _) = anthropicWith {
                AiHttpResponse(
                    200,
                    """{"content":[{"type":"text","text":"Hello "},{"type":"tool_use","id":"x"},{"type":"text","text":"world."}],"stop_reason":"end_turn"}""",
                )
            }
            provider.fixGrammar("hello world") shouldBe "Hello world."
        }

        test("an answer cut off by max_tokens is rejected") {
            val (provider, _) = anthropicWith { anthropicOk("Hello wor", stopReason = "max_tokens") }
            provider.errorOf { fixGrammar("hello world") }.error shouldBe AiError.INVALID_RESPONSE
        }

        test("malformed bodies are rejected") {
            for (body in listOf("not json", "[]", "{}", """{"content":"text"}""", """{"content":[]}""")) {
                val (provider, _) = anthropicWith { AiHttpResponse(200, body) }
                provider.errorOf { fixGrammar("hello") }.error shouldBe AiError.INVALID_RESPONSE
            }
        }

        test("an answer without any text is an empty response") {
            val (provider, _) = anthropicWith { anthropicOk("   ") }
            provider.errorOf { fixGrammar("hello") }.error shouldBe AiError.EMPTY_RESPONSE
        }
    }

    context("errors") {
        test("401 is an auth error and is not retried") {
            val (provider, transport) = anthropicWith { AiHttpResponse(401, "") }
            provider.errorOf { fixGrammar("hello") }.error shouldBe AiError.AUTH
            transport.callCount shouldBe 1
        }

        test("400 is a rejected request and is not retried") {
            val (provider, transport) = anthropicWith { AiHttpResponse(400, "") }
            val error = provider.errorOf { fixGrammar("hello") }
            error.error shouldBe AiError.PROVIDER_REJECTED
            error.httpStatus shouldBe 400
            transport.callCount shouldBe 1
        }

        test("overloaded (529) is retried and then reported as a server error") {
            val (provider, transport) = anthropicWith { AiHttpResponse(529, "") }
            provider.errorOf { fixGrammar("hello") }.error shouldBe AiError.SERVER
            transport.callCount shouldBe 2
        }

        test("429 then success works") {
            var calls = 0
            val (provider, transport) = anthropicWith { if (calls++ == 0) AiHttpResponse(429, "") else anthropicOk("Hi.") }
            provider.fixGrammar("hi") shouldBe "Hi."
            transport.callCount shouldBe 2
        }

        test("network failure and timeout are distinguished") {
            val (network, _) = anthropicWith { throw IOException("down") }
            network.errorOf { fixGrammar("hello") }.error shouldBe AiError.NETWORK
            val (timeout, _) = anthropicWith { throw SocketTimeoutException("slow") }
            timeout.errorOf { fixGrammar("hello") }.error shouldBe AiError.TIMEOUT
        }

        test("missing configuration and missing key fail without any request") {
            val (noConfig, t1) = anthropicWith(config = null) { anthropicOk("x") }
            noConfig.errorOf { fixGrammar("hello") }.error shouldBe AiError.NOT_CONFIGURED
            val (noKey, t2) = anthropicWith(testConfig(apiKey = null)) { anthropicOk("x") }
            noKey.errorOf { fixGrammar("hello") }.error shouldBe AiError.NOT_CONFIGURED
            (t1.callCount + t2.callCount) shouldBe 0
        }

        test("cleartext endpoints are refused") {
            val (provider, transport) = anthropicWith(testConfig(endpoint = "http://api.anthropic.example.com")) { anthropicOk("x") }
            provider.errorOf { fixGrammar("hello") }.error shouldBe AiError.INVALID_ENDPOINT
            transport.callCount shouldBe 0
        }

        test("cancelling the caller cancels the in-flight request") {
            val started = CompletableDeferred<Unit>()
            val transportCancelled = CompletableDeferred<Unit>()
            val (provider, _) = anthropicWith {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    transportCancelled.complete(Unit)
                }
            }
            val job = CoroutineScope(Dispatchers.Default).launch { provider.fixGrammar("hello") }
            started.await()
            job.cancelAndJoin()
            transportCancelled.await()
            job.isCancelled shouldBe true
        }
    }

    test("the API key never appears in errors or the config string") {
        val (provider, _) = anthropicWith { AiHttpResponse(401, "") }
        val error = provider.errorOf { fixGrammar("hello") }
        (error.message ?: "") shouldNotContain TEST_API_KEY
        error.message!! shouldContain "AUTH"
    }
})

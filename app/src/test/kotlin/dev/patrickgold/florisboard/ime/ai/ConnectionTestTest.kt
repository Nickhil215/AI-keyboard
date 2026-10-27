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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.net.SocketTimeoutException

class ConnectionTestTest : FunSpec({
    fun noModelConfig(endpoint: String) = testConfig(endpoint = endpoint, model = "")

    context("openai-compatible") {
        test("lists models with a GET and the bearer key, and succeeds on 200") {
            val (provider, transport) = processorWith(testConfig(endpoint = "https://api.example.com/v1")) {
                AiHttpResponse(200, """{"data":[]}""")
            }
            provider.testConnection() shouldBe ConnectionTestResult.Success
            val request = transport.requests.single()
            request.method shouldBe "GET"
            request.url shouldBe "https://api.example.com/v1/models"
            request.headers["Authorization"] shouldBe "Bearer $TEST_API_KEY"
            request.body shouldBe null
        }

        test("works when the endpoint is the full chat completions url") {
            val (provider, transport) = processorWith { AiHttpResponse(200, "{}") }
            provider.testConnection()
            transport.requests.single().url shouldBe "https://api.example.com/v1/models"
        }

        test("does not need a model id, and sends no user text") {
            val (provider, transport) = processorWith(noModelConfig("https://api.example.com/v1")) { AiHttpResponse(200, "{}") }
            provider.testConnection() shouldBe ConnectionTestResult.Success
            transport.requests.single().body shouldBe null
        }

        test("a rejected key is an auth failure") {
            for (status in listOf(401, 403)) {
                val (provider, _) = processorWith { AiHttpResponse(status, "") }
                provider.testConnection() shouldBe ConnectionTestResult.Failed(AiError.AUTH, status)
            }
        }

        test("a missing models endpoint is inconclusive, not a failure") {
            for (status in listOf(404, 405)) {
                val (provider, _) = processorWith { AiHttpResponse(status, "") }
                provider.testConnection() shouldBe ConnectionTestResult.Inconclusive(status)
            }
        }

        test("server errors and rate limits are reported, with a single attempt") {
            val (server, serverTransport) = processorWith { AiHttpResponse(500, "") }
            server.testConnection() shouldBe ConnectionTestResult.Failed(AiError.SERVER, 500)
            serverTransport.callCount shouldBe 1
            val (limited, _) = processorWith { AiHttpResponse(429, "") }
            limited.testConnection() shouldBe ConnectionTestResult.Failed(AiError.RATE_LIMITED, 429)
        }

        test("network problems and timeouts are reported") {
            val (network, _) = processorWith { throw IOException("down") }
            network.testConnection() shouldBe ConnectionTestResult.Failed(AiError.NETWORK)
            val (timeout, _) = processorWith { throw SocketTimeoutException("slow") }
            timeout.testConnection() shouldBe ConnectionTestResult.Failed(AiError.TIMEOUT)
        }

        test("missing key or configuration is reported without any request") {
            val (noConfig, t1) = processorWith(config = null) { AiHttpResponse(200, "{}") }
            noConfig.testConnection() shouldBe ConnectionTestResult.Failed(AiError.NOT_CONFIGURED)
            val (noKey, t2) = processorWith(testConfig(apiKey = "")) { AiHttpResponse(200, "{}") }
            noKey.testConnection() shouldBe ConnectionTestResult.Failed(AiError.NOT_CONFIGURED)
            val (badUrl, t3) = processorWith(testConfig(endpoint = "http://insecure.example.com")) { AiHttpResponse(200, "{}") }
            badUrl.testConnection() shouldBe ConnectionTestResult.Failed(AiError.INVALID_ENDPOINT)
            (t1.callCount + t2.callCount + t3.callCount) shouldBe 0
        }
    }

    context("anthropic") {
        test("lists models with the native headers") {
            val (provider, transport) = anthropicWith { AiHttpResponse(200, """{"data":[]}""") }
            provider.testConnection() shouldBe ConnectionTestResult.Success
            val request = transport.requests.single()
            request.method shouldBe "GET"
            request.url shouldBe "https://api.anthropic.example.com/v1/models"
            request.headers["x-api-key"] shouldBe TEST_API_KEY
            request.headers["anthropic-version"] shouldBe AnthropicProvider.API_VERSION
        }

        test("a rejected key is an auth failure") {
            val (provider, _) = anthropicWith { AiHttpResponse(401, "") }
            provider.testConnection().shouldBeInstanceOf<ConnectionTestResult.Failed>().error shouldBe AiError.AUTH
        }
    }
})

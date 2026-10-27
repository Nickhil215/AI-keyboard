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
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Outcome of a "Test connection" action.
 */
sealed interface ConnectionTestResult {
    /** The endpoint answered and accepted the credentials. */
    data object Success : ConnectionTestResult

    /** The server answered but has no reliable way to confirm (e.g. no model listing endpoint). */
    data class Inconclusive(val httpStatus: Int) : ConnectionTestResult

    data class Failed(val error: AiError, val httpStatus: Int? = null) : ConnectionTestResult
}

interface ConnectionTestable {
    suspend fun testConnection(): ConnectionTestResult
}

/**
 * Shared behavior of all cloud providers: configuration checks, retries, error mapping, response
 * cleaning and the connection test. Subclasses only describe the wire format of their API.
 *
 * Providers are only constructed when the user selected them, so none of this runs for on-device use.
 *
 * @param configProvider returns the current settings, or null if the feature is not configured. It is
 * read for every call, so settings changes apply immediately.
 * @param transport performs the actual HTTP call.
 * @param pause suspends between retries; replaced in tests to avoid real delays.
 */
abstract class RemoteAiProvider(
    private val configProvider: () -> AiProviderConfig?,
    private val transport: AiHttpTransport,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : AiTextProcessor, ConnectionTestable {
    protected abstract fun buildRequest(config: AiProviderConfig, systemPrompt: String, text: String): AiHttpRequest

    protected abstract fun buildConnectionTestRequest(config: AiProviderConfig): AiHttpRequest

    /** Extracts the model's text from a 2xx response body, or throws [AiProcessingException]. */
    protected abstract fun extractContent(body: String): String

    override suspend fun fixGrammar(text: String): String {
        return process(AiPrompts.fixGrammarSystemPrompt, text)
    }

    override suspend fun rewrite(text: String, tone: RewriteTone?): String {
        return process(AiPrompts.rewriteSystemPrompt(tone), text)
    }

    override suspend fun testConnection(): ConnectionTestResult {
        val config = try {
            validatedConfig(requireModel = false)
        } catch (e: AiProcessingException) {
            return ConnectionTestResult.Failed(e.error)
        }
        return try {
            // A single attempt: the user is waiting for an answer and wants to see the first failure.
            val response = transport.send(buildConnectionTestRequest(config))
            when (response.status) {
                in 200..299 -> ConnectionTestResult.Success
                401, 403 -> ConnectionTestResult.Failed(AiError.AUTH, response.status)
                404, 405 -> ConnectionTestResult.Inconclusive(response.status)
                429 -> ConnectionTestResult.Failed(AiError.RATE_LIMITED, response.status)
                in 500..599 -> ConnectionTestResult.Failed(AiError.SERVER, response.status)
                else -> ConnectionTestResult.Failed(AiError.PROVIDER_REJECTED, response.status)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiResponseTooLargeException) {
            ConnectionTestResult.Failed(AiError.RESPONSE_TOO_LARGE)
        } catch (e: SocketTimeoutException) {
            ConnectionTestResult.Failed(AiError.TIMEOUT)
        } catch (e: IOException) {
            ConnectionTestResult.Failed(AiError.NETWORK)
        } catch (e: Exception) {
            ConnectionTestResult.Failed(AiError.UNKNOWN)
        }
    }

    private suspend fun process(systemPrompt: String, text: String): String {
        if (text.isBlank()) throw AiProcessingException(AiError.EMPTY_INPUT)
        val config = validatedConfig(requireModel = true)
        if (text.length > config.maxInputChars) throw AiProcessingException(AiError.INPUT_TOO_LONG)

        val response = postWithRetry(buildRequest(config, systemPrompt, text), config)
        val content = extractContent(response.body)
        return AiOutputSanitizer.clean(original = text, raw = content)
    }

    private fun validatedConfig(requireModel: Boolean): AiProviderConfig {
        val config = configProvider() ?: throw AiProcessingException(AiError.NOT_CONFIGURED)
        if (config.apiKey.isNullOrBlank() || (requireModel && config.model.isBlank())) {
            throw AiProcessingException(AiError.NOT_CONFIGURED)
        }
        if (AiProviderConfig.hostOrNull(config.endpointUrl) == null) {
            throw AiProcessingException(AiError.INVALID_ENDPOINT)
        }
        return config
    }

    // Only transient failures (network, timeout, 429, 5xx) are retried, and only up to maxAttempts.
    // Auth and request errors fail immediately, as repeating them cannot succeed.
    private suspend fun postWithRetry(request: AiHttpRequest, config: AiProviderConfig): AiHttpResponse {
        val attempts = config.maxAttempts.coerceAtLeast(1)
        var attempt = 0
        while (true) {
            attempt++
            val failure: AiProcessingException = try {
                val response = transport.send(request)
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
                if (e.error == AiError.AUTH || e.error == AiError.PROVIDER_REJECTED || e.error == AiError.SERVER &&
                    e.httpStatus !in 500..599
                ) {
                    throw e
                }
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

    /** Caps the length of an answer: a short keyboard edit never needs more than about its own length. */
    protected fun outputTokenLimit(text: String): Int = (text.length / 2 + 128).coerceIn(256, 4096)
}

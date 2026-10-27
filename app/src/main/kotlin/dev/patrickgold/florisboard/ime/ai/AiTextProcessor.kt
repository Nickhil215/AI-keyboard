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
 * Optional tone hint for the rewrite operation.
 */
enum class RewriteTone {
    PROFESSIONAL,
    CASUAL,
    CONCISE,
    SIMPLE,
}

/**
 * Every reason an AI operation can fail, from both the provider layer and the editing session.
 *
 * Values are mapped to localized, user-facing messages in the UI. They deliberately carry no
 * user text, prompt or credential, so they are safe to log and to show.
 */
enum class AiError {
    // Session level
    FEATURE_DISABLED,
    SENSITIVE_FIELD,
    UNSUPPORTED_EDITOR,
    NO_SELECTION,
    SELECTION_CHANGED,
    APPLY_FAILED,

    // Provider level
    NOT_CONFIGURED,
    INVALID_ENDPOINT,
    EMPTY_INPUT,
    INPUT_TOO_LONG,
    NETWORK,
    TIMEOUT,
    AUTH,
    RATE_LIMITED,
    PROVIDER_REJECTED,
    SERVER,
    INVALID_RESPONSE,
    EMPTY_RESPONSE,
    RESPONSE_TOO_LARGE,
    UNKNOWN,
}

/**
 * Thrown by [AiTextProcessor] implementations. The message never contains user text or credentials.
 *
 * @property httpStatus the HTTP status code returned by the provider, if one was received.
 */
class AiProcessingException(
    val error: AiError,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(
    if (httpStatus != null) "${error.name} (HTTP $httpStatus)" else error.name,
    cause,
)

/**
 * Provider-independent abstraction for AI text operations. UI code only depends on this interface,
 * so a local-inference implementation can be added later without touching the keyboard UI.
 *
 * Implementations must be main-safe (never block the calling thread), cancellable and must signal
 * every failure with an [AiProcessingException].
 */
interface AiTextProcessor {
    /**
     * Fixes spelling, grammar, punctuation and capitalization of [text] without changing its meaning.
     */
    suspend fun fixGrammar(text: String): String

    /**
     * Rewrites [text] for clarity and fluency without changing its meaning, optionally in a given [tone].
     */
    suspend fun rewrite(text: String, tone: RewriteTone? = null): String
}

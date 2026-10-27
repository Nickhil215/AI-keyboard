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
 * Validates and normalizes raw model output before it is shown to the user.
 */
object AiOutputSanitizer {
    // A result far longer than its input is almost certainly commentary or an injected answer.
    private const val MAX_GROWTH_FACTOR = 4
    private const val MAX_GROWTH_EXTRA_CHARS = 200

    private val quotePairs = listOf(
        '"' to '"',
        '“' to '”',
        '„' to '“',
        '«' to '»',
    )
    private val tagRegex = Regex("""<\s*/?\s*user_text\s*>""", RegexOption.IGNORE_CASE)
    private val fenceRegex = Regex("""^```[A-Za-z0-9_-]*\n?([\s\S]*?)\n?```$""")

    /**
     * Cleans [raw] model output for the given [original] input.
     *
     * @throws AiProcessingException with [AiError.EMPTY_RESPONSE] or [AiError.INVALID_RESPONSE].
     */
    fun clean(original: String, raw: String): String {
        var out = raw.trim()
        out = tagRegex.replace(out, "").trim()
        fenceRegex.matchEntire(out)?.let { out = it.groupValues[1].trim() }
        if (!isWrappedInQuotes(original.trim())) {
            out = unwrapQuotes(out)
        }
        if (out.isBlank()) {
            throw AiProcessingException(AiError.EMPTY_RESPONSE)
        }
        if (out.length > original.length * MAX_GROWTH_FACTOR + MAX_GROWTH_EXTRA_CHARS) {
            throw AiProcessingException(AiError.INVALID_RESPONSE)
        }
        // Models trim their output, but the selection may include surrounding whitespace that the
        // user wants to keep, so it is restored around the result.
        val leading = original.takeWhile { it.isWhitespace() }
        val trailing = original.takeLastWhile { it.isWhitespace() }
        return leading + out + trailing
    }

    private fun isWrappedInQuotes(text: String): Boolean {
        return text.length >= 2 && quotePairs.any { (open, close) -> text.first() == open && text.last() == close }
    }

    private fun unwrapQuotes(text: String): String {
        if (!isWrappedInQuotes(text)) return text
        val inner = text.substring(1, text.length - 1)
        // Quotes inside mean the text is probably several quoted pieces, not one wrapped value.
        return if (quotePairs.any { (open, close) -> inner.contains(open) || inner.contains(close) }) text else inner.trim()
    }
}

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

import android.text.InputType
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AiEligibilityTest : FunSpec({
    fun attrs(raw: Int) = InputAttributes.wrap(raw)

    val text = InputType.TYPE_CLASS_TEXT

    test("a normal text field is allowed") {
        AiEligibility.check(true, attrs(text)) shouldBe null
        AiEligibility.check(true, attrs(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE)) shouldBe null
        AiEligibility.check(true, attrs(text or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE)) shouldBe null
    }

    test("the feature switch wins over everything") {
        AiEligibility.check(false, attrs(text)) shouldBe AiError.FEATURE_DISABLED
    }

    test("every password style of field is refused as sensitive") {
        val sensitive = listOf(
            text or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        for (raw in sensitive) {
            AiEligibility.check(true, attrs(raw)) shouldBe AiError.SENSITIVE_FIELD
        }
    }

    test("non-text editors are unsupported") {
        AiEligibility.check(true, attrs(InputType.TYPE_CLASS_NUMBER)) shouldBe AiError.UNSUPPORTED_EDITOR
        AiEligibility.check(true, attrs(InputType.TYPE_CLASS_PHONE)) shouldBe AiError.UNSUPPORTED_EDITOR
        AiEligibility.check(true, attrs(InputType.TYPE_CLASS_DATETIME)) shouldBe AiError.UNSUPPORTED_EDITOR
        AiEligibility.check(true, attrs(InputType.TYPE_NULL)) shouldBe AiError.UNSUPPORTED_EDITOR
    }
})

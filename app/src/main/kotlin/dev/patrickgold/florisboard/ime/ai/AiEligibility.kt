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

import dev.patrickgold.florisboard.ime.editor.InputAttributes

/**
 * Decides whether AI actions may run in the currently focused editor.
 */
object AiEligibility {
    /**
     * Returns null if AI actions are allowed, or the [AiError] describing why they are not.
     *
     * Sensitive fields are checked before the editor type, so a numeric PIN field is reported as
     * sensitive rather than merely unsupported.
     */
    fun check(featureEnabled: Boolean, attributes: InputAttributes): AiError? {
        if (!featureEnabled) return AiError.FEATURE_DISABLED
        if (isSensitive(attributes)) return AiError.SENSITIVE_FIELD
        if (attributes.type != InputAttributes.Type.TEXT) return AiError.UNSUPPORTED_EDITOR
        return null
    }

    fun isSensitive(attributes: InputAttributes): Boolean {
        return when (attributes.variation) {
            InputAttributes.Variation.PASSWORD,
            InputAttributes.Variation.VISIBLE_PASSWORD,
            InputAttributes.Variation.WEB_PASSWORD,
                -> true
            else -> false
        }
    }
}

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

import dev.patrickgold.florisboard.ime.keyboard3.ImeActions
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickAction
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionArrangement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Static checks that the two actions are actually reachable from the existing keyboard UI.
 * (Rendering itself needs a device or emulator and is covered by the manual test list.)
 */
class AiQuickActionsTest : FunSpec({
    val fixGrammar = QuickAction.InsertK3Descriptor(ImeActions.AiFixGrammar)
    val rewrite = QuickAction.InsertK3Descriptor(ImeActions.AiRewrite)

    test("both actions are part of the default smartbar actions") {
        (fixGrammar in QuickActionArrangement.Default) shouldBe true
        (rewrite in QuickActionArrangement.Default) shouldBe true
    }

    test("the AI actions are not shown in the main bar by default, so typing looks unchanged") {
        QuickActionArrangement.Default.stickyAction shouldBe QuickAction.InsertK3Descriptor(ImeActions.ExternalVoiceInput)
        QuickActionArrangement.Default.dynamicActions.take(10).contains(fixGrammar) shouldBe false
    }

    test("the action names are unique, so dispatch cannot mix them up") {
        ImeActions.AiFixGrammar.name shouldBe "ai_fix_grammar"
        ImeActions.AiRewrite.name shouldBe "ai_rewrite"
        (ImeActions.AiFixGrammar == ImeActions.AiRewrite) shouldBe false
    }

    test("both actions have an icon mapping in the foundation keyboard") {
        val xml = File("src/main/assets/extensions/org.florisboard.k3.foundation/import/displays-implied.xml").readText()
        xml.contains("""output="@fl:action/ai_fix_grammar" display="@fl:icon/ai_fix_grammar"""") shouldBe true
        xml.contains("""output="@fl:action/ai_rewrite" display="@fl:icon/ai_rewrite"""") shouldBe true
    }

    test("every string the AI UI references exists in strings.xml") {
        val strings = File("src/main/res/values/strings.xml").readText()
        val sources = listOf(
            "src/main/kotlin/dev/patrickgold/florisboard/ime/ai/AiPreviewPanel.kt",
            "src/main/kotlin/dev/patrickgold/florisboard/app/settings/ai/AiScreen.kt",
            "src/main/kotlin/dev/patrickgold/florisboard/ime/smartbar/quickaction/QuickAction.kt",
        ).joinToString("\n") { File(it).readText() }
        val referenced = Regex("""R\.string\.((?:ai__|pref__ai__|settings__ai__|quick_action__ai_)\w+)""")
            .findAll(sources).map { it.groupValues[1] }.toSet()
        (referenced.isNotEmpty()) shouldBe true
        val missing = referenced.filter { !strings.contains("name=\"$it\"") }
        missing shouldBe emptyList()
    }

    test("the API key is not stored in the backed-up preference datastore") {
        val prefs = File("src/main/kotlin/dev/patrickgold/florisboard/app/AppPrefs.kt").readText()
        val aiSection = prefs.substringAfter("inner class Ai {").substringBefore("val clipboard = Clipboard()")
        aiSection.contains("api_key", ignoreCase = true) shouldBe false
        aiSection.contains("apikey", ignoreCase = true) shouldBe false
    }
})

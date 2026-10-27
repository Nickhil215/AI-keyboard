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

import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.ime.ai.local.LocalModelCatalog
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.IOException

private class CountingProcessor(val onCall: suspend () -> String = { "result" }) : AiTextProcessor {
    var calls = 0
    override suspend fun fixGrammar(text: String): String {
        calls++
        return onCall()
    }

    override suspend fun rewrite(text: String, tone: RewriteTone?): String {
        calls++
        return onCall()
    }
}

class AiProviderSelectorTest : FunSpec({
    class Fixture {
        val created = mutableListOf<AiProviderType>()
        val processors = AiProviderType.entries.associateWith { CountingProcessor() }
        val selector = AiProviderSelector(
            AiProviderType.entries.associateWith { type ->
                {
                    created += type
                    processors.getValue(type)
                }
            }
        )
    }

    test("only the selected provider is constructed") {
        for (type in AiProviderType.entries) {
            val f = Fixture()
            f.selector.select(type) shouldBe f.processors.getValue(type)
            f.created shouldBe listOf(type)
        }
    }

    test("the on-device provider never initializes any cloud provider") {
        val f = Fixture()
        f.selector.select(AiProviderType.LOCAL)
        f.created.none { it.isRemote } shouldBe true
    }

    test("a cloud provider never initializes the on-device engine") {
        val f = Fixture()
        f.selector.select(AiProviderType.OPENAI)
        f.created.contains(AiProviderType.LOCAL) shouldBe false
    }

    test("a failing on-device provider is not silently replaced by a cloud provider") {
        val f = Fixture()
        val failing = CountingProcessor { throw AiProcessingException(AiError.MODEL_NOT_INSTALLED) }
        val selector = AiProviderSelector(
            mapOf(
                AiProviderType.LOCAL to { failing },
                AiProviderType.OPENAI to { f.processors.getValue(AiProviderType.OPENAI).also { f.created += AiProviderType.OPENAI } },
            )
        )

        val error = shouldThrow<AiProcessingException> { selector.select(AiProviderType.LOCAL).fixGrammar("hello") }

        error.error shouldBe AiError.MODEL_NOT_INSTALLED
        failing.calls shouldBe 1
        f.created shouldBe emptyList()
        f.processors.getValue(AiProviderType.OPENAI).calls shouldBe 0
    }

    test("an unconfigured provider type fails clearly") {
        val selector = AiProviderSelector(emptyMap())
        shouldThrow<AiProcessingException> { selector.select(AiProviderType.LOCAL) }.error shouldBe AiError.NOT_CONFIGURED
    }

    test("a new request builds a fresh processor, so settings changes apply immediately") {
        val f = Fixture()
        f.selector.select(AiProviderType.ANTHROPIC)
        f.selector.select(AiProviderType.ANTHROPIC)
        f.created shouldBe listOf(AiProviderType.ANTHROPIC, AiProviderType.ANTHROPIC)
    }
})

class AiPreferencesTest : FunSpec({
    test("defaults: off, on-device, no cloud provider active, sensible timeouts") {
        val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
        prefs.ai.enabled.get() shouldBe false
        prefs.ai.provider.get() shouldBe AiProviderType.LOCAL
        prefs.ai.localModelId.get() shouldBe LocalModelCatalog.default.id
        prefs.ai.connectTimeoutSeconds.get() shouldBe 10
        prefs.ai.requestTimeoutSeconds.get() shouldBe 30
    }

    test("each cloud provider starts with its own endpoint and model") {
        val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
        for (type in AiProviderType.entries.filter { it.isRemote }) {
            prefs.ai.remote(type).endpointUrl.get() shouldBe type.defaultEndpoint
            prefs.ai.remote(type).model.get() shouldBe type.defaultModel
        }
        shouldThrow<IllegalStateException> { prefs.ai.remote(AiProviderType.LOCAL) }
    }

    test("choices persist and are independent per provider") {
        val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)
        try {
            prefs.ai.provider.set(AiProviderType.ANTHROPIC)
            prefs.ai.remote(AiProviderType.CUSTOM).endpointUrl.set("https://llm.internal.example/v1")
            prefs.ai.remote(AiProviderType.CUSTOM).model.set("my-model")
            prefs.ai.requestTimeoutSeconds.set(45)

            prefs.ai.provider.get() shouldBe AiProviderType.ANTHROPIC
            prefs.ai.remote(AiProviderType.CUSTOM).endpointUrl.get() shouldBe "https://llm.internal.example/v1"
            prefs.ai.remote(AiProviderType.CUSTOM).model.get() shouldBe "my-model"
            prefs.ai.requestTimeoutSeconds.get() shouldBe 45
            // Changing one provider's settings must not touch another's.
            prefs.ai.remote(AiProviderType.OPENAI).endpointUrl.get() shouldBe AiProviderType.OPENAI.defaultEndpoint
            prefs.ai.remote(AiProviderType.ANTHROPIC).model.get() shouldBe AiProviderType.ANTHROPIC.defaultModel
        } finally {
            prefs.ai.provider.set(AiProviderType.LOCAL)
            prefs.ai.remote(AiProviderType.CUSTOM).endpointUrl.set(AiProviderType.CUSTOM.defaultEndpoint)
            prefs.ai.remote(AiProviderType.CUSTOM).model.set(AiProviderType.CUSTOM.defaultModel)
            prefs.ai.requestTimeoutSeconds.set(30)
        }
    }
})

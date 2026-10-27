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

class AiEndpointsTest : FunSpec({
    test("chat completions url accepts base urls and full urls") {
        val expected = "https://api.openai.com/v1/chat/completions"
        AiEndpoints.chatCompletionsUrl("https://api.openai.com/v1") shouldBe expected
        AiEndpoints.chatCompletionsUrl("https://api.openai.com/v1/") shouldBe expected
        AiEndpoints.chatCompletionsUrl("  https://api.openai.com/v1/chat/completions  ") shouldBe expected
        AiEndpoints.chatCompletionsUrl("https://api.openai.com/v1/chat/completions/") shouldBe expected
    }

    test("gemini's openai-compatible base url works") {
        AiEndpoints.chatCompletionsUrl("https://generativelanguage.googleapis.com/v1beta/openai") shouldBe
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        AiEndpoints.openAiModelsUrl("https://generativelanguage.googleapis.com/v1beta/openai") shouldBe
            "https://generativelanguage.googleapis.com/v1beta/openai/models"
    }

    test("models url is derived from either form") {
        AiEndpoints.openAiModelsUrl("https://host/v1") shouldBe "https://host/v1/models"
        AiEndpoints.openAiModelsUrl("https://host/v1/chat/completions") shouldBe "https://host/v1/models"
    }

    test("anthropic urls accept base, v1 and full urls") {
        for (base in listOf("https://api.anthropic.com", "https://api.anthropic.com/", "https://api.anthropic.com/v1", "https://api.anthropic.com/v1/messages")) {
            AiEndpoints.anthropicMessagesUrl(base) shouldBe "https://api.anthropic.com/v1/messages"
            AiEndpoints.anthropicModelsUrl(base) shouldBe "https://api.anthropic.com/v1/models"
        }
    }

    test("provider defaults are sensible") {
        AiProviderType.LOCAL.isRemote shouldBe false
        AiProviderType.entries.filter { it != AiProviderType.LOCAL }.forEach { it.isRemote shouldBe true }
        AiProviderType.OPENAI.defaultEndpoint shouldBe "https://api.openai.com/v1"
        AiProviderType.ANTHROPIC.defaultEndpoint shouldBe "https://api.anthropic.com"
        AiProviderType.GEMINI.defaultEndpoint shouldBe "https://generativelanguage.googleapis.com/v1beta/openai"
        // Every default endpoint of a cloud provider with a fixed host must pass the https check.
        listOf(AiProviderType.OPENAI, AiProviderType.GEMINI, AiProviderType.ANTHROPIC).forEach {
            (AiProviderConfig.hostOrNull(it.defaultEndpoint) != null) shouldBe true
        }
        // Slots are unique, so one provider's key can never be read as another's.
        AiProviderType.entries.map { it.credentialSlot }.toSet().size shouldBe AiProviderType.entries.size
    }
})

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

package dev.patrickgold.florisboard.ime.ai.local

import dev.patrickgold.florisboard.ime.ai.AiError
import dev.patrickgold.florisboard.ime.ai.AiPrompts
import dev.patrickgold.florisboard.ime.ai.AiProcessingException
import dev.patrickgold.florisboard.ime.ai.RewriteTone
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files

/** A tiny stand-in model, so tests never need the real 475 MB file. */
internal val testSpec = LocalModelCatalog.Qwen3.copy(id = "test-model", fileName = "test-model.litertlm", sizeBytes = 16)

internal class FakeDevice(
    override val is64BitCpu: Boolean = true,
    override val totalMemoryBytes: Long = 8L * 1024 * 1024 * 1024,
) : DeviceCapabilities

internal class FakeEngine(
    val onGenerate: suspend (system: String, user: String, maxTokens: Int) -> String = { _, _, _ -> "ok" },
) : LocalInferenceEngine {
    var closed = false
    val calls = mutableListOf<Triple<String, String, Int>>()

    override suspend fun generate(systemPrompt: String, userMessage: String, maxOutputTokens: Int): String {
        calls += Triple(systemPrompt, userMessage, maxOutputTokens)
        return onGenerate(systemPrompt, userMessage, maxOutputTokens)
    }

    override fun close() {
        closed = true
    }
}

internal fun newTempDir(): File = Files.createTempDirectory("ai-model-test").toFile()

internal fun installFakeModel(storage: LocalModelStorage, spec: LocalModelSpec = testSpec, size: Int = spec.sizeBytes.toInt()) {
    storage.directory.mkdirs()
    storage.fileFor(spec).writeBytes(ByteArray(size) { 1 })
}

class LocalModelProviderTest : FunSpec({
    class Fixture(
        val storage: LocalModelStorage = LocalModelStorage(newTempDir()),
        val device: DeviceCapabilities = FakeDevice(),
        val engine: FakeEngine = FakeEngine(),
        maxInputChars: Int = LocalModelProvider.DEFAULT_MAX_INPUT_CHARS,
    ) {
        var engineCreations = 0
        val holder = LocalEngineHolder { _, _ ->
            engineCreations++
            engine
        }
        val provider = LocalModelProvider({ testSpec }, storage, device, holder, maxInputChars)
    }

    suspend fun LocalModelProvider.errorOf(block: suspend LocalModelProvider.() -> Unit): AiProcessingException {
        return shouldThrow<AiProcessingException> { block() }
    }

    context("without a model") {
        test("a missing model file is reported and the engine is never created") {
            val f = Fixture()
            f.provider.errorOf { fixGrammar("hello") }.error shouldBe AiError.MODEL_NOT_INSTALLED
            f.engineCreations shouldBe 0
        }

        test("a file with the wrong size (e.g. a truncated copy) counts as not installed") {
            val f = Fixture()
            installFakeModel(f.storage, size = 5)
            f.provider.errorOf { rewrite("hello") }.error shouldBe AiError.MODEL_NOT_INSTALLED
            f.engineCreations shouldBe 0
        }

        test("a partial download is not a usable model") {
            val f = Fixture()
            f.storage.directory.mkdirs()
            f.storage.partFileFor(testSpec).writeBytes(ByteArray(16))
            f.provider.errorOf { fixGrammar("hello") }.error shouldBe AiError.MODEL_NOT_INSTALLED
        }
    }

    context("unsupported devices") {
        test("a 32-bit device is refused before anything is loaded") {
            val f = Fixture(device = FakeDevice(is64BitCpu = false))
            installFakeModel(f.storage)
            f.provider.errorOf { fixGrammar("hello") }.error shouldBe AiError.UNSUPPORTED_DEVICE
            f.engineCreations shouldBe 0
        }

        test("a device with too little memory is refused") {
            val f = Fixture(device = FakeDevice(totalMemoryBytes = 1L * 1024 * 1024 * 1024))
            installFakeModel(f.storage)
            f.provider.errorOf { fixGrammar("hello") }.error shouldBe AiError.UNSUPPORTED_DEVICE
        }

        test("an unknown memory size does not block the device") {
            val f = Fixture(device = FakeDevice(totalMemoryBytes = 0))
            installFakeModel(f.storage)
            f.provider.fixGrammar("helo") shouldBe "ok"
        }
    }

    context("inference") {
        test("fix grammar sends the grammar prompt and the delimited text, and returns the answer") {
            val f = Fixture(engine = FakeEngine { _, _, _ -> "I was not able to attend the meeting." })
            installFakeModel(f.storage)

            val result = f.provider.fixGrammar("i was not able to attends the meeting")

            result shouldBe "I was not able to attend the meeting."
            val (system, user, _) = f.engine.calls.single()
            system shouldBe AiPrompts.fixGrammarSystemPrompt
            user shouldBe AiPrompts.wrapUserText("i was not able to attends the meeting")
        }

        test("rewrite uses the rewrite prompt with the tone") {
            val f = Fixture(engine = FakeEngine { _, _, _ -> "We cannot finish today." })
            installFakeModel(f.storage)

            f.provider.rewrite("we cant finish today", RewriteTone.PROFESSIONAL) shouldBe "We cannot finish today."

            f.engine.calls.single().first shouldBe AiPrompts.rewriteSystemPrompt(RewriteTone.PROFESSIONAL)
        }

        test("needs no credentials: nothing but the model and the device is consulted") {
            val f = Fixture()
            installFakeModel(f.storage)
            f.provider.fixGrammar("hello") shouldBe "ok"
        }

        test("the output token budget is bounded") {
            val f = Fixture()
            installFakeModel(f.storage)
            f.provider.fixGrammar("a")
            f.provider.fixGrammar("b".repeat(1_200))
            val budgets = f.engine.calls.map { it.third }
            budgets.all { it in 64..LocalModelProvider.MAX_OUTPUT_TOKENS } shouldBe true
        }

        test("empty, blank and oversized input never reach the model") {
            val f = Fixture(maxInputChars = 10)
            installFakeModel(f.storage)
            f.provider.errorOf { fixGrammar("") }.error shouldBe AiError.EMPTY_INPUT
            f.provider.errorOf { rewrite("  \n") }.error shouldBe AiError.EMPTY_INPUT
            f.provider.errorOf { fixGrammar("x".repeat(11)) }.error shouldBe AiError.INPUT_TOO_LONG
            f.engine.calls.size shouldBe 0
            f.engineCreations shouldBe 0
        }

        test("reasoning blocks are removed from the answer") {
            val f = Fixture(engine = FakeEngine { _, _, _ -> "<think>the user wants a fix</think>\nHello world." })
            installFakeModel(f.storage)
            f.provider.fixGrammar("helo world") shouldBe "Hello world."
        }

        test("an unfinished reasoning block is rejected") {
            val f = Fixture(engine = FakeEngine { _, _, _ -> "<think>let me think about" })
            installFakeModel(f.storage)
            f.provider.errorOf { fixGrammar("helo") }.error shouldBe AiError.INVALID_RESPONSE
        }

        test("empty answers are rejected") {
            val f = Fixture(engine = FakeEngine { _, _, _ -> "  " })
            installFakeModel(f.storage)
            f.provider.errorOf { fixGrammar("helo") }.error shouldBe AiError.EMPTY_RESPONSE
        }

        val special = listOf("Hello 👋 world", "మీరు ఎలా ఉన్నారు?", "नमस्ते दुनिया", "line1\nline2", "Call getUserById(42) for ₹500")
        for (text in special) {
            test("keeps special text intact: ${text.take(14).replace("\n", " ")}") {
                val f = Fixture(engine = FakeEngine { _, user, _ ->
                    user.removePrefix(AiPrompts.OPEN_TAG + "\n").removeSuffix("\n" + AiPrompts.CLOSE_TAG)
                })
                installFakeModel(f.storage)
                f.provider.fixGrammar(text) shouldBe text
            }
        }
    }

    context("failures") {
        test("an initialization failure from the engine is passed on") {
            val f = Fixture(engine = FakeEngine { _, _, _ -> throw AiProcessingException(AiError.MODEL_INIT_FAILED) })
            installFakeModel(f.storage)
            f.provider.errorOf { fixGrammar("hello") }.error shouldBe AiError.MODEL_INIT_FAILED
        }

        test("running out of memory is reported and the model is released") {
            val f = Fixture(engine = FakeEngine { _, _, _ -> throw OutOfMemoryError() })
            installFakeModel(f.storage)
            f.provider.errorOf { fixGrammar("hello") }.error shouldBe AiError.INSUFFICIENT_MEMORY
            f.engine.closed shouldBe true
            f.holder.isLoaded shouldBe false
        }

        test("missing native code is reported as an unsupported device") {
            val f = Fixture(engine = FakeEngine { _, _, _ -> throw UnsatisfiedLinkError("no lib") })
            installFakeModel(f.storage)
            f.provider.errorOf { fixGrammar("hello") }.error shouldBe AiError.UNSUPPORTED_DEVICE
        }

        test("any other engine crash becomes an inference error, never an uncaught exception") {
            val f = Fixture(engine = FakeEngine { _, _, _ -> throw IllegalStateException("native crash") })
            installFakeModel(f.storage)
            val error = f.provider.errorOf { fixGrammar("hello") }
            error.error shouldBe AiError.INFERENCE_FAILED
            (error.message ?: "") shouldContain "INFERENCE_FAILED"
        }

        test("a failure never falls back to anything else") {
            // The provider has no other backend to call; a failed run leaves no result at all.
            val f = Fixture(engine = FakeEngine { _, _, _ -> throw IllegalStateException("x") })
            installFakeModel(f.storage)
            f.provider.errorOf { rewrite("hello") }
            f.engine.calls.size shouldBe 1
        }

        test("cancelling the caller cancels the running inference") {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val f = Fixture(engine = FakeEngine { _, _, _ ->
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            })
            installFakeModel(f.storage)

            val job = CoroutineScope(Dispatchers.Default).launch { f.provider.fixGrammar("hello") }
            started.await()
            job.cancelAndJoin()
            stopped.await()
            job.isCancelled shouldBe true
        }
    }

    context("engine reuse and cleanup") {
        test("the engine is created once and reused for later requests") {
            val f = Fixture()
            installFakeModel(f.storage)
            repeat(3) { f.provider.fixGrammar("hello $it") }
            f.engineCreations shouldBe 1
            f.engine.calls.size shouldBe 3
        }

        test("a different model file replaces and closes the old engine") {
            val engines = mutableListOf<FakeEngine>()
            val holder = LocalEngineHolder { _, _ -> FakeEngine().also { engines += it } }
            val dir = newTempDir()
            holder.get(File(dir, "a"), testSpec)
            holder.get(File(dir, "a"), testSpec)
            holder.get(File(dir, "b"), testSpec)
            engines.size shouldBe 2
            engines[0].closed shouldBe true
            engines[1].closed shouldBe false
        }

        test("an idle engine is released and loaded again on demand") {
            var now = 1_000L
            val engines = mutableListOf<FakeEngine>()
            val holder = LocalEngineHolder(clock = { now }) { _, _ -> FakeEngine().also { engines += it } }
            val file = File(newTempDir(), "m")

            holder.get(file, testSpec)
            now += 500
            holder.releaseIfIdle(1_000) shouldBe false
            holder.isLoaded shouldBe true
            now += 1_000
            holder.releaseIfIdle(1_000) shouldBe true
            holder.isLoaded shouldBe false
            engines[0].closed shouldBe true

            holder.get(file, testSpec)
            engines.size shouldBe 2
        }

        test("using the engine postpones its idle release") {
            var now = 0L
            val holder = LocalEngineHolder(clock = { now }) { _, _ -> FakeEngine() }
            holder.get(File(newTempDir(), "m"), testSpec)
            now = 900
            holder.touch()
            now = 1_500
            holder.releaseIfIdle(1_000) shouldBe false
        }

        test("release closes the engine and is safe to call twice") {
            val engine = FakeEngine()
            val holder = LocalEngineHolder { _, _ -> engine }
            holder.get(File(newTempDir(), "m"), testSpec)
            holder.release()
            holder.release()
            engine.closed shouldBe true
        }
    }

    context("catalog") {
        test("the default model needs no account, the optional one is gated") {
            LocalModelCatalog.default.requiresAuthToken shouldBe false
            LocalModelCatalog.Gemma3.requiresAuthToken shouldBe true
            LocalModelCatalog.byId("does-not-exist") shouldBe LocalModelCatalog.default
            LocalModelCatalog.byId(null) shouldBe LocalModelCatalog.default
        }

        test("every model is pinned to a commit, a size and a sha-256, over https") {
            for (spec in LocalModelCatalog.all) {
                spec.downloadUrl.startsWith("https://huggingface.co/") shouldBe true
                Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(spec.downloadUrl) shouldBe true
                Regex("[0-9a-f]{64}").matches(spec.sha256) shouldBe true
                (spec.sizeBytes > 0) shouldBe true
                spec.downloadUrl.endsWith(spec.fileName) shouldBe true
            }
            LocalModelCatalog.all.map { it.id }.toSet().size shouldBe LocalModelCatalog.all.size
        }
    }
})

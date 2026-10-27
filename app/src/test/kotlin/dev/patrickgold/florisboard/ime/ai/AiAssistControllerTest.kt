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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** An in-memory editor that records every write, so tests can prove when the text was (not) touched. */
private class FakeBridge(
    var eligibility: AiError? = null,
    var selection: SelectionSnapshot? = SelectionSnapshot("i am here", 5, 14, "pkg/1"),
    var replaceSucceeds: Boolean = true,
) : AiEditorBridge {
    var readCalls = 0
    val replacements = mutableListOf<Pair<SelectionSnapshot, String>>()

    override fun checkEligibility() = eligibility

    override fun readSelection(): SelectionSnapshot? {
        readCalls++
        return selection
    }

    override fun replaceSelection(expected: SelectionSnapshot, newText: String): Boolean {
        replacements += expected to newText
        return replaceSucceeds
    }
}

/** A processor whose answers are released by the test, to observe the loading state. */
private class GatedProcessor : AiTextProcessor {
    class Call(val operation: AiOperation, val text: String, val tone: RewriteTone?, val gate: CompletableDeferred<String>)

    val calls = mutableListOf<Call>()

    override suspend fun fixGrammar(text: String): String {
        val call = Call(AiOperation.FIX_GRAMMAR, text, null, CompletableDeferred())
        calls += call
        return call.gate.await()
    }

    override suspend fun rewrite(text: String, tone: RewriteTone?): String {
        val call = Call(AiOperation.REWRITE, text, tone, CompletableDeferred())
        calls += call
        return call.gate.await()
    }
}

class AiAssistControllerTest : FunSpec({
    // Unconfined runs the request inline until it suspends, which keeps these tests deterministic.
    fun newController(processor: AiTextProcessor) = AiAssistController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        processorProvider = { processor },
    )

    context("starting") {
        test("a selection moves through loading to a ready preview") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            val bridge = FakeBridge()

            controller.start(AiOperation.FIX_GRAMMAR, bridge)
            val loading = controller.state.value.shouldBeInstanceOf<AiAssistState.Loading>()
            loading.original.text shouldBe "i am here"
            processor.calls.single().text shouldBe "i am here"

            processor.calls.single().gate.complete("I am here.")
            val ready = controller.state.value.shouldBeInstanceOf<AiAssistState.Ready>()
            ready.result shouldBe "I am here."
            bridge.replacements.size shouldBe 0 // a preview never touches the text
        }

        test("rewrite passes the tone to the processor") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            controller.start(AiOperation.REWRITE, FakeBridge(), RewriteTone.CASUAL)
            processor.calls.single().operation shouldBe AiOperation.REWRITE
            processor.calls.single().tone shouldBe RewriteTone.CASUAL
        }

        test("no selection shows a helpful error and sends nothing") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            controller.start(AiOperation.FIX_GRAMMAR, FakeBridge(selection = null))
            controller.state.value.shouldBeInstanceOf<AiAssistState.Failed>().error shouldBe AiError.NO_SELECTION
            processor.calls.size shouldBe 0
        }

        test("sensitive fields are refused before the selection is even read") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            val bridge = FakeBridge(eligibility = AiError.SENSITIVE_FIELD)
            controller.start(AiOperation.REWRITE, bridge)
            controller.state.value.shouldBeInstanceOf<AiAssistState.Failed>().error shouldBe AiError.SENSITIVE_FIELD
            bridge.readCalls shouldBe 0
            processor.calls.size shouldBe 0
        }

        test("a disabled feature sends nothing") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            controller.start(AiOperation.FIX_GRAMMAR, FakeBridge(eligibility = AiError.FEATURE_DISABLED))
            controller.state.value.shouldBeInstanceOf<AiAssistState.Failed>().error shouldBe AiError.FEATURE_DISABLED
            processor.calls.size shouldBe 0
        }

        test("starting again while a request is running does not submit a duplicate") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            controller.start(AiOperation.FIX_GRAMMAR, FakeBridge())
            controller.start(AiOperation.FIX_GRAMMAR, FakeBridge())
            controller.start(AiOperation.REWRITE, FakeBridge())
            processor.calls.size shouldBe 1
        }
    }

    context("accepting") {
        test("accept replaces exactly the original selection with the result and closes the panel") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            val bridge = FakeBridge()
            controller.start(AiOperation.FIX_GRAMMAR, bridge)
            processor.calls.single().gate.complete("I am here.")

            controller.accept(bridge)

            bridge.replacements.single() shouldBe (SelectionSnapshot("i am here", 5, 14, "pkg/1") to "I am here.")
            controller.state.value shouldBe AiAssistState.Idle
        }

        test("accept is refused when the selection moved, and the text is not touched") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            val bridge = FakeBridge()
            controller.start(AiOperation.FIX_GRAMMAR, bridge)
            processor.calls.single().gate.complete("I am here.")

            bridge.selection = SelectionSnapshot("i am here", 20, 29, "pkg/1")
            controller.accept(bridge)

            bridge.replacements.size shouldBe 0
            val failed = controller.state.value.shouldBeInstanceOf<AiAssistState.Failed>()
            failed.error shouldBe AiError.SELECTION_CHANGED
            failed.original shouldBe null // nothing left to retry on: the user must select again
        }

        test("accept is refused when the selected text changed at the same position") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            val bridge = FakeBridge()
            controller.start(AiOperation.FIX_GRAMMAR, bridge)
            processor.calls.single().gate.complete("I am here.")

            bridge.selection = SelectionSnapshot("i am gone", 5, 14, "pkg/1")
            controller.accept(bridge)

            bridge.replacements.size shouldBe 0
            controller.state.value.shouldBeInstanceOf<AiAssistState.Failed>().error shouldBe AiError.SELECTION_CHANGED
        }

        test("accept is refused when the selection was cleared") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            val bridge = FakeBridge()
            controller.start(AiOperation.FIX_GRAMMAR, bridge)
            processor.calls.single().gate.complete("I am here.")

            bridge.selection = null
            controller.accept(bridge)

            bridge.replacements.size shouldBe 0
            controller.state.value.shouldBeInstanceOf<AiAssistState.Failed>().error shouldBe AiError.SELECTION_CHANGED
        }

        test("accept is refused when the focused field became a password field") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            val bridge = FakeBridge()
            controller.start(AiOperation.FIX_GRAMMAR, bridge)
            processor.calls.single().gate.complete("I am here.")

            bridge.eligibility = AiError.SENSITIVE_FIELD
            controller.accept(bridge)

            bridge.replacements.size shouldBe 0
            controller.state.value.shouldBeInstanceOf<AiAssistState.Failed>().error shouldBe AiError.SENSITIVE_FIELD
        }

        test("accept is refused when the field changed even if the text is identical") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            val bridge = FakeBridge()
            controller.start(AiOperation.FIX_GRAMMAR, bridge)
            processor.calls.single().gate.complete("I am here.")

            bridge.selection = SelectionSnapshot("i am here", 5, 14, "other.app/9")
            controller.accept(bridge)

            bridge.replacements.size shouldBe 0
            controller.state.value.shouldBeInstanceOf<AiAssistState.Failed>().error shouldBe AiError.SELECTION_CHANGED
        }

        test("a host app that rejects the edit is reported and the panel stays open") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            val bridge = FakeBridge(replaceSucceeds = false)
            controller.start(AiOperation.FIX_GRAMMAR, bridge)
            processor.calls.single().gate.complete("I am here.")

            controller.accept(bridge)

            controller.state.value.shouldBeInstanceOf<AiAssistState.Failed>().error shouldBe AiError.APPLY_FAILED
        }

        test("accept does nothing unless a result is ready") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            val bridge = FakeBridge()
            controller.accept(bridge) // idle
            controller.start(AiOperation.FIX_GRAMMAR, bridge)
            controller.accept(bridge) // still loading
            bridge.replacements.size shouldBe 0
        }
    }

    context("cancelling") {
        test("cancel closes the panel and never touches the text") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            val bridge = FakeBridge()
            controller.start(AiOperation.FIX_GRAMMAR, bridge)
            processor.calls.single().gate.complete("I am here.")

            controller.cancel()

            controller.state.value shouldBe AiAssistState.Idle
            bridge.replacements.size shouldBe 0
        }

        test("cancelling a running request aborts it and a late answer is ignored") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            controller.start(AiOperation.FIX_GRAMMAR, FakeBridge())
            val first = processor.calls.single()

            controller.cancel()
            controller.state.value shouldBe AiAssistState.Idle
            first.gate.complete("late answer")

            controller.state.value shouldBe AiAssistState.Idle
        }

        test("a cancelled request cannot overwrite the next request") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            val bridge = FakeBridge()
            controller.start(AiOperation.FIX_GRAMMAR, bridge)
            val first = processor.calls[0]
            controller.cancel()

            controller.start(AiOperation.FIX_GRAMMAR, bridge)
            val second = processor.calls[1]
            first.gate.complete("stale")
            controller.state.value.shouldBeInstanceOf<AiAssistState.Loading>()

            second.gate.complete("fresh")
            controller.state.value.shouldBeInstanceOf<AiAssistState.Ready>().result shouldBe "fresh"
        }
    }

    context("retrying") {
        test("retry submits a new request for the same original text") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            controller.start(AiOperation.FIX_GRAMMAR, FakeBridge())
            processor.calls[0].gate.complete("first")

            controller.retry()
            controller.state.value.shouldBeInstanceOf<AiAssistState.Loading>()
            processor.calls.size shouldBe 2
            processor.calls[1].text shouldBe "i am here"
            processor.calls[1].gate.complete("second")
            controller.state.value.shouldBeInstanceOf<AiAssistState.Ready>().result shouldBe "second"
        }

        test("retry after a provider error submits a new request") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            controller.start(AiOperation.REWRITE, FakeBridge())
            processor.calls[0].gate.completeExceptionally(AiProcessingException(AiError.NETWORK))
            controller.state.value.shouldBeInstanceOf<AiAssistState.Failed>().error shouldBe AiError.NETWORK

            controller.retry()
            processor.calls.size shouldBe 2
        }

        test("retry with a new tone re-runs the rewrite with that tone") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            controller.start(AiOperation.REWRITE, FakeBridge())
            processor.calls[0].gate.complete("first")

            controller.retry(RewriteTone.CONCISE, changeTone = true)
            processor.calls[1].tone shouldBe RewriteTone.CONCISE

            processor.calls[1].gate.complete("second")
            controller.retry() // a plain retry keeps the chosen tone
            processor.calls[2].tone shouldBe RewriteTone.CONCISE
        }

        test("retry is ignored while loading, idle, or when there is nothing to retry on") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            controller.retry()
            controller.start(AiOperation.FIX_GRAMMAR, FakeBridge())
            controller.retry()
            processor.calls.size shouldBe 1

            val noSelection = newController(processor)
            noSelection.start(AiOperation.FIX_GRAMMAR, FakeBridge(selection = null))
            noSelection.retry()
            processor.calls.size shouldBe 1
        }
    }

    context("failures") {
        test("provider errors are shown and carry the http status") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            controller.start(AiOperation.FIX_GRAMMAR, FakeBridge())
            processor.calls[0].gate.completeExceptionally(AiProcessingException(AiError.PROVIDER_REJECTED, httpStatus = 404))
            val failed = controller.state.value.shouldBeInstanceOf<AiAssistState.Failed>()
            failed.error shouldBe AiError.PROVIDER_REJECTED
            failed.httpStatus shouldBe 404
        }

        test("an unexpected crash in a processor becomes an error state, not a keyboard crash") {
            val processor = GatedProcessor()
            val controller = newController(processor)
            controller.start(AiOperation.FIX_GRAMMAR, FakeBridge())
            processor.calls[0].gate.completeExceptionally(IllegalStateException("bug"))
            controller.state.value.shouldBeInstanceOf<AiAssistState.Failed>().error shouldBe AiError.UNKNOWN
        }
    }
})

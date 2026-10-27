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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AiOperation {
    FIX_GRAMMAR,
    REWRITE,
}

/**
 * The exact selection an AI request was started for.
 *
 * It is compared field by field with the live editor before anything is replaced, so a result can
 * only ever be applied to the text it was generated from.
 */
data class SelectionSnapshot(
    val text: String,
    val start: Int,
    val end: Int,
    val fieldKey: String,
)

sealed interface AiAssistState {
    data object Idle : AiAssistState

    data class Loading(
        val operation: AiOperation,
        val tone: RewriteTone?,
        val original: SelectionSnapshot,
    ) : AiAssistState

    data class Ready(
        val operation: AiOperation,
        val tone: RewriteTone?,
        val original: SelectionSnapshot,
        val result: String,
    ) : AiAssistState

    /**
     * @property original the selection the request was for, or null if there is nothing to retry
     * (no selection, sensitive field, or selection changed).
     */
    data class Failed(
        val operation: AiOperation,
        val tone: RewriteTone?,
        val original: SelectionSnapshot?,
        val error: AiError,
        val httpStatus: Int? = null,
        val detail: String? = null,
    ) : AiAssistState
}

/**
 * The part of the editor the session needs. Kept as an interface so the session logic can be tested
 * without an Android `InputConnection`.
 */
interface AiEditorBridge {
    /** Returns null if AI actions are allowed in the current editor, or the reason they are not. */
    fun checkEligibility(): AiError?

    /** Reads the live selection from the editor, or null if no text is selected. */
    fun readSelection(): SelectionSnapshot?

    /**
     * Replaces the selection with [newText] if, and only if, the editor still holds exactly
     * [expected]. Returns whether the replacement was applied.
     */
    fun replaceSelection(expected: SelectionSnapshot, newText: String): Boolean
}

/**
 * Drives one AI operation at a time: start, preview, retry, accept or cancel.
 *
 * Nothing is written to the editor except by [accept], which is only reachable through an explicit
 * user action in the preview panel.
 *
 * @param scope scope the requests run in. Network work happens inside the processor, never on the
 * caller's thread.
 * @param processorProvider returns the processor to use; evaluated per request so settings changes apply.
 */
class AiAssistController(
    private val scope: CoroutineScope,
    private val processorProvider: () -> AiTextProcessor,
) {
    private val _state = MutableStateFlow<AiAssistState>(AiAssistState.Idle)
    val state: StateFlow<AiAssistState> = _state.asStateFlow()

    private var job: Job? = null

    // Incremented whenever a request starts or is cancelled, so a finished-but-stale request can tell
    // that it no longer owns the state and must not publish its outcome.
    private var generation = 0

    /**
     * Starts [operation] for the current selection. Ignored while a request is already running.
     */
    @Synchronized
    fun start(operation: AiOperation, bridge: AiEditorBridge, tone: RewriteTone? = null) {
        if (_state.value is AiAssistState.Loading) return

        bridge.checkEligibility()?.let { error ->
            _state.value = AiAssistState.Failed(operation, tone, original = null, error = error)
            return
        }
        val snapshot = bridge.readSelection()
        if (snapshot == null) {
            _state.value = AiAssistState.Failed(operation, tone, original = null, error = AiError.NO_SELECTION)
            return
        }
        launchRequest(operation, tone, snapshot)
    }

    /**
     * Re-runs the last request on the same original text, optionally with a different [tone].
     * Ignored while a request is running or when there is nothing to retry.
     */
    @Synchronized
    fun retry(tone: RewriteTone? = null, changeTone: Boolean = false) {
        val current = _state.value
        val (operation, previousTone, original) = when (current) {
            is AiAssistState.Ready -> Triple(current.operation, current.tone, current.original)
            is AiAssistState.Failed -> Triple(current.operation, current.tone, current.original ?: return)
            else -> return
        }
        launchRequest(operation, if (changeTone) tone else previousTone, original)
    }

    /**
     * Applies the previewed result if the selection is still exactly what the request was made for.
     */
    @Synchronized
    fun accept(bridge: AiEditorBridge) {
        val current = _state.value as? AiAssistState.Ready ?: return

        fun fail(error: AiError) {
            _state.value = AiAssistState.Failed(current.operation, current.tone, original = null, error = error)
        }

        bridge.checkEligibility()?.let { return fail(it) }
        if (bridge.readSelection() != current.original) return fail(AiError.SELECTION_CHANGED)
        // The bridge re-checks the text atomically while writing, so false means either the app refused
        // the edit or the text changed in the instant since the check above. Nothing was modified.
        if (!bridge.replaceSelection(current.original, current.result)) return fail(AiError.APPLY_FAILED)
        _state.value = AiAssistState.Idle
    }

    /**
     * Aborts a running request and closes the panel. The editor text is never touched.
     */
    @Synchronized
    fun cancel() {
        generation++
        job?.cancel()
        job = null
        _state.value = AiAssistState.Idle
    }

    private fun launchRequest(operation: AiOperation, tone: RewriteTone?, snapshot: SelectionSnapshot) {
        if (_state.value is AiAssistState.Loading) return
        job?.cancel()
        val myGeneration = ++generation
        _state.value = AiAssistState.Loading(operation, tone, snapshot)
        job = scope.launch {
            val outcome: AiAssistState = try {
                val processor = processorProvider()
                val result = when (operation) {
                    AiOperation.FIX_GRAMMAR -> processor.fixGrammar(snapshot.text)
                    AiOperation.REWRITE -> processor.rewrite(snapshot.text, tone)
                }
                AiAssistState.Ready(operation, tone, snapshot, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiProcessingException) {
                AiAssistState.Failed(operation, tone, snapshot, e.error, e.httpStatus, e.diagnostic(snapshot.text))
            } catch (e: Exception) {
                // A provider bug must never crash the keyboard.
                AiAssistState.Failed(operation, tone, snapshot, AiError.UNKNOWN)
            }
            synchronized(this@AiAssistController) {
                if (generation == myGeneration) _state.value = outcome
            }
        }
    }
}

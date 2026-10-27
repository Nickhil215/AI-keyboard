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
import dev.patrickgold.florisboard.ime.ai.AiOutputSanitizer
import dev.patrickgold.florisboard.ime.ai.AiProcessingException
import dev.patrickgold.florisboard.ime.ai.AiPrompts
import dev.patrickgold.florisboard.ime.ai.AiTextProcessor
import dev.patrickgold.florisboard.ime.ai.RewriteTone
import kotlinx.coroutines.CancellationException

/**
 * [AiTextProcessor] that runs a model on the device. It needs no credentials and no network, and it
 * never falls back to a cloud provider: if the model is missing or fails, the caller gets a specific
 * error and the user decides what to do.
 *
 * @param model the model the user selected.
 * @param engines supplies the (cached) engine for the model file.
 */
class LocalModelProvider(
    private val model: () -> LocalModelSpec,
    private val storage: LocalModelStorage,
    private val device: DeviceCapabilities,
    private val engines: LocalEngineHolder,
    private val maxInputChars: Int = DEFAULT_MAX_INPUT_CHARS,
) : AiTextProcessor {
    override suspend fun fixGrammar(text: String): String {
        return process(AiPrompts.fixGrammarSystemPrompt, text)
    }

    override suspend fun rewrite(text: String, tone: RewriteTone?): String {
        return process(AiPrompts.rewriteSystemPrompt(tone), text)
    }

    private suspend fun process(systemPrompt: String, text: String): String {
        if (text.isBlank()) throw AiProcessingException(AiError.EMPTY_INPUT)
        // Small models are slow and degrade on long inputs, so the limit is stricter than for cloud models.
        if (text.length > maxInputChars) throw AiProcessingException(AiError.INPUT_TOO_LONG)

        val spec = model()
        if (!device.is64BitCpu || device.totalMemoryBytes in 1 until spec.minTotalRamBytes) {
            throw AiProcessingException(AiError.UNSUPPORTED_DEVICE)
        }
        if (!storage.isInstalled(spec)) throw AiProcessingException(AiError.MODEL_NOT_INSTALLED)

        val raw = try {
            engines.get(storage.fileFor(spec), spec)
                .generate(systemPrompt, AiPrompts.wrapUserText(text), outputTokenLimit(text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiProcessingException) {
            throw e
        } catch (e: OutOfMemoryError) {
            // Drop the model so the process can recover its memory.
            engines.release()
            throw AiProcessingException(AiError.INSUFFICIENT_MEMORY, cause = e)
        } catch (e: UnsatisfiedLinkError) {
            throw AiProcessingException(AiError.UNSUPPORTED_DEVICE, cause = e)
        } catch (e: LinkageError) {
            // A missing class or method in the native runtime wrapper: report it instead of crashing.
            throw AiProcessingException(AiError.INFERENCE_FAILED, cause = e)
        } catch (e: Exception) {
            throw AiProcessingException(AiError.INFERENCE_FAILED, cause = e)
        }
        engines.touch()
        return AiOutputSanitizer.clean(original = text, raw = raw)
    }

    // About one token per two characters is generous for edits whose output is as long as the input.
    private fun outputTokenLimit(text: String): Int = (text.length / 2 + 64).coerceIn(64, MAX_OUTPUT_TOKENS)

    companion object {
        const val DEFAULT_MAX_INPUT_CHARS = 1_200
        const val MAX_OUTPUT_TOKENS = 768
    }
}

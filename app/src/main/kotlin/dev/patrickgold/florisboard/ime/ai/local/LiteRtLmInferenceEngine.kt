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

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import dev.patrickgold.florisboard.ime.ai.AiError
import dev.patrickgold.florisboard.ime.ai.AiProcessingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [LocalInferenceEngine] backed by Google's LiteRT-LM runtime (`com.google.ai.edge.litertlm`).
 *
 * The model is loaded lazily on the first request, on a background thread, and then kept for later
 * requests. Requests are serialized because the native engine handles one conversation at a time.
 * Only the CPU backend is used: it works on every supported device, whereas GPU/NPU support depends on
 * the chipset and has not been validated for this app.
 */
class LiteRtLmInferenceEngine(
    private val modelFile: File,
    private val cacheDir: File,
    private val contextTokens: Int,
) : LocalInferenceEngine {
    private val mutex = Mutex()
    private var engine: Engine? = null

    override suspend fun generate(systemPrompt: String, userMessage: String, maxOutputTokens: Int): String {
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                val loaded = loadEngine()
                // Different model files accept different options (for example some reject the thinking switch or
                // the output limit). Try the preferred setup first and fall back to simpler ones, remembering what
                // each attempt said so that a total failure can explain itself.
                val failures = mutableListOf<String>()
                for (variant in Variant.entries) {
                    try {
                        return@withContext runOnce(loaded, variant, systemPrompt, userMessage, maxOutputTokens)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures += "${variant.name}: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(MAX_FAILURE_CHARS)}"
                    }
                }
                throw AiProcessingException(
                    AiError.INFERENCE_FAILED,
                    cause = IllegalStateException(failures.joinToString(" | ")),
                )
            }
        }
    }

    private enum class Variant {
        /** System instruction, low-randomness sampling, output limit, reasoning off. */
        FULL,

        /** Only the system instruction and sampling: for models that reject the thinking/limit options. */
        SYSTEM_AND_SAMPLING,

        /** Library defaults, with the instructions placed in the user message: for models without a system role. */
        PLAIN,
    }

    private suspend fun runOnce(
        loaded: Engine,
        variant: Variant,
        systemPrompt: String,
        userMessage: String,
        maxOutputTokens: Int,
    ): String {
        val sampler = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2, seed = 0)
        val config = when (variant) {
            // Rewrites should be stable, not creative. Reasoning would be slow and its text is not wanted.
            Variant.FULL -> ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                samplerConfig = sampler,
                maxOutputToken = maxOutputTokens,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            )
            Variant.SYSTEM_AND_SAMPLING -> ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                samplerConfig = sampler,
            )
            Variant.PLAIN -> ConversationConfig()
        }
        val message = if (variant == Variant.PLAIN) "$systemPrompt\n\n$userMessage" else userMessage

        val conversation = loaded.createConversation(config)
        try {
            return suspendCancellableCoroutine { cont ->
                // The native call blocks. cancelProcess() makes it return early when the user cancels, and the
                // result is then ignored because the continuation is already cancelled.
                cont.invokeOnCancellation { runCatching { conversation.cancelProcess() } }
                try {
                    val reply = conversation.sendMessage(message)
                    cont.resume(
                        reply.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                    )
                } catch (e: Throwable) {
                    cont.resumeWithException(e)
                }
            }
        } finally {
            runCatching { conversation.close() }
        }
    }

    // Called with the mutex held, off the main thread.
    private fun loadEngine(): Engine {
        engine?.let { return it }
        try {
            cacheDir.mkdirs()
            val created = Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    maxNumTokens = contextTokens.coerceAtMost(MAX_CONTEXT_TOKENS),
                    cacheDir = cacheDir.absolutePath,
                )
            )
            created.initialize()
            engine = created
            return created
        } catch (e: LiteRtLmJniException) {
            throw AiProcessingException(AiError.MODEL_INIT_FAILED, cause = e)
        } catch (e: IllegalStateException) {
            throw AiProcessingException(AiError.MODEL_INIT_FAILED, cause = e)
        }
    }

    override fun close() {
        val toClose = engine
        engine = null
        // Closing waits for any request that is still running, so it must not block the caller.
        Thread { runCatching { toClose?.close() } }.start()
    }

    private companion object {
        // Keyboard edits are short; a smaller context also means less memory.
        const val MAX_CONTEXT_TOKENS = 1536
        const val MAX_FAILURE_CHARS = 110
    }
}

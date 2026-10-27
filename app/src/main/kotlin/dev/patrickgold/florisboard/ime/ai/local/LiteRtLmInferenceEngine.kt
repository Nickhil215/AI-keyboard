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
                val conversation = try {
                    loaded.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(systemPrompt),
                            // Rewrites should be stable, not creative.
                            samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2, seed = 0),
                            maxOutputToken = maxOutputTokens,
                            // Reasoning would be slow and its text is not wanted in a keyboard edit.
                            thinkingConfig = ThinkingConfig(enableThinking = false),
                        )
                    )
                } catch (e: LiteRtLmJniException) {
                    throw AiProcessingException(AiError.INFERENCE_FAILED, cause = e)
                }
                try {
                    suspendCancellableCoroutine { cont ->
                        // The native call blocks. cancelProcess() makes it return early when the user
                        // cancels, and the result is then ignored because the continuation is cancelled.
                        cont.invokeOnCancellation { runCatching { conversation.cancelProcess() } }
                        try {
                            val reply = conversation.sendMessage(userMessage)
                            cont.resume(
                                reply.contents.contents
                                    .filterIsInstance<Content.Text>()
                                    .joinToString("") { it.text }
                            )
                        } catch (e: LiteRtLmJniException) {
                            cont.resumeWithException(AiProcessingException(AiError.INFERENCE_FAILED, cause = e))
                        } catch (e: Throwable) {
                            cont.resumeWithException(e)
                        }
                    }
                } finally {
                    runCatching { conversation.close() }
                }
            }
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
    }
}

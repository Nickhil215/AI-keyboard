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

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.ime.ai.AiPrompts
import dev.patrickgold.florisboard.ime.ai.AiOutputSanitizer
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real on-device inference benchmark. It is skipped unless a model file is present, so it never runs by
 * accident and never downloads anything.
 *
 * Run on a physical arm64 phone:
 *
 *     adb push qwen3_0_6b_mixed_int4.litertlm /sdcard/Android/data/dev.patrickgold.florisboard.debug/files/model.litertlm
 *     ./gradlew :app:connectedDebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=dev.patrickgold.florisboard.ime.ai.local.LocalModelBenchmarkTest
 *     adb logcat -d -s LocalModelBenchmark
 *
 * (Use the package name of the build you installed. Optionally pass `-e modelPath <path>` and
 * `-e contextTokens <n>`.) It logs startup time, warm latency per request, native memory, and the model's
 * answers for a fixed set of sentences, so output quality can be judged by eye.
 */
@RunWith(AndroidJUnit4::class)
class LocalModelBenchmarkTest {
    private val tag = "LocalModelBenchmark"

    private val samples = listOf(
        "i am not able to attends meeting yesterday because i was busy",
        "we are not able to finish this task today due to some issues",
        "Please do the needful and revert back by EOD",
        "their going to the market tomorow, and me and him will meet there",
        "హలో, నేను రేపు వస్తాను",
        "plz send the report asap its urgent",
    )

    @Test
    fun benchmarkLocalModel() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val modelFile = File(args.getString("modelPath") ?: File(context.getExternalFilesDir(null), "model.litertlm").path)
        assumeTrue("No model at ${modelFile.path}; skipping benchmark", modelFile.isFile)

        Log.i(tag, "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} " +
            "abi=${android.os.Build.SUPPORTED_64_BIT_ABIS.joinToString()} model=${modelFile.name} size=${modelFile.length()}")

        val engine = LiteRtLmInferenceEngine(
            modelFile = modelFile,
            cacheDir = File(context.cacheDir, "litertlm-bench"),
            contextTokens = args.getString("contextTokens")?.toIntOrNull() ?: 2048,
        )
        try {
            // The first request includes loading the model, so it measures cold start.
            val coldStart = System.nanoTime()
            engine.generate(AiPrompts.fixGrammarSystemPrompt, AiPrompts.wrapUserText("hello wrold"), 64)
            Log.i(tag, "cold_start_ms=${(System.nanoTime() - coldStart) / 1_000_000}")

            val latencies = mutableListOf<Long>()
            for (round in 1..3) {
                for (sample in samples) {
                    val start = System.nanoTime()
                    val raw = engine.generate(
                        AiPrompts.fixGrammarSystemPrompt,
                        AiPrompts.wrapUserText(sample),
                        (sample.length / 2 + 64).coerceIn(64, 768),
                    )
                    val ms = (System.nanoTime() - start) / 1_000_000
                    latencies += ms
                    if (round == 1) {
                        val cleaned = runCatching { AiOutputSanitizer.clean(sample, raw) }.getOrElse { "<rejected: ${it.message}>" }
                        Log.i(tag, "sample in=\"$sample\" out=\"$cleaned\" ms=$ms")
                    }
                }
            }
            latencies.sort()
            Log.i(tag, "warm_latency_ms median=${latencies[latencies.size / 2]} p90=${latencies[(latencies.size * 9) / 10]} max=${latencies.last()} n=${latencies.size}")

            val memory = Debug.MemoryInfo()
            Debug.getMemoryInfo(memory)
            Log.i(tag, "memory_pss_kb total=${memory.totalPss} native=${memory.nativePss} nativeHeapAllocated_kb=${Debug.getNativeHeapAllocatedSize() / 1024}")
        } finally {
            engine.close()
        }
    }
}

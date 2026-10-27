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

/**
 * A downloadable on-device model. Everything needed to fetch and verify it is pinned here: the
 * download URL points at an exact repository commit, and [sha256] and [sizeBytes] are checked before
 * a download is accepted.
 *
 * @property requiresAuthToken true for gated models: the user must accept the license on the model's
 * page and supply their own access token. Such models are never downloaded silently.
 * @property minTotalRamBytes devices with less memory are refused rather than risking the host app.
 */
data class LocalModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val licenseName: String,
    val licenseUrl: String,
    val modelPageUrl: String,
    val requiresAuthToken: Boolean,
    val minTotalRamBytes: Long,
    val contextTokens: Int,
)

object LocalModelCatalog {
    private const val GIB = 1024L * 1024L * 1024L

    /**
     * Default: Qwen3 0.6B, 4-bit. Apache-2.0 and not gated, so it can be offered without any account.
     * File, commit, size and checksum come from the litert-community/Qwen3-0.6B Hugging Face repository.
     */
    val Qwen3 = LocalModelSpec(
        id = "qwen3-0.6b-int4",
        displayName = "Qwen3 0.6B (4-bit)",
        fileName = "qwen3_0_6b_mixed_int4.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/" +
            "fed81d17517e6ea64362e917ecf7e5accff12492/qwen3_0_6b_mixed_int4.litertlm",
        sizeBytes = 497_516_544L,
        sha256 = "7900eb4e7362d88c58782c6f9999bb7a129e03544aa98b8f338ea0cc5d8c22c1",
        licenseName = "Apache License 2.0",
        licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
        modelPageUrl = "https://huggingface.co/litert-community/Qwen3-0.6B",
        requiresAuthToken = false,
        minTotalRamBytes = 3 * GIB,
        contextTokens = 2048,
    )

    /**
     * Optional, higher quality: Gemma 3 1B, 4-bit. Gated under the Gemma Terms of Use, so the user must
     * accept the terms on Hugging Face and provide their own token.
     */
    val Gemma3 = LocalModelSpec(
        id = "gemma3-1b-int4",
        displayName = "Gemma 3 1B (4-bit)",
        fileName = "gemma3-1b-it-int4.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/" +
            "d448835d84411a08f577e25f3afebe528402d73b/gemma3-1b-it-int4.litertlm",
        sizeBytes = 584_417_280L,
        sha256 = "1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be",
        licenseName = "Gemma Terms of Use",
        licenseUrl = "https://ai.google.dev/gemma/terms",
        modelPageUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT",
        requiresAuthToken = true,
        minTotalRamBytes = 4 * GIB,
        contextTokens = 4096,
    )

    val all: List<LocalModelSpec> = listOf(Qwen3, Gemma3)
    val default: LocalModelSpec = Qwen3

    fun byId(id: String?): LocalModelSpec = all.firstOrNull { it.id == id } ?: default
}

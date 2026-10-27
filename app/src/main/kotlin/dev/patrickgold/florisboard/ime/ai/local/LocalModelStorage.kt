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

import java.io.File

/**
 * Where model files live. Pass an app-private directory (e.g. `filesDir/ai-models`), so other apps
 * cannot read or replace the files.
 *
 * Downloads are written to a `.part` file and only renamed to the final name after the size and
 * SHA-256 checks pass, so a final file always means a verified model.
 */
class LocalModelStorage(val directory: File) {
    fun fileFor(spec: LocalModelSpec): File = File(directory, spec.fileName)

    fun partFileFor(spec: LocalModelSpec): File = File(directory, spec.fileName + ".part")

    /** A cheap check, run before every load: the verified file is present and has the pinned size. */
    fun isInstalled(spec: LocalModelSpec): Boolean {
        val file = fileFor(spec)
        return file.isFile && file.length() == spec.sizeBytes
    }

    fun partialBytes(spec: LocalModelSpec): Long {
        val part = partFileFor(spec)
        return if (part.isFile) part.length() else 0L
    }

    fun delete(spec: LocalModelSpec) {
        fileFor(spec).delete()
        partFileFor(spec).delete()
    }
}

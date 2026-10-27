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

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * What the device can do, so unsupported phones get a clear message instead of a native crash.
 */
interface DeviceCapabilities {
    val is64BitCpu: Boolean
    val totalMemoryBytes: Long
}

class AndroidDeviceCapabilities(context: Context) : DeviceCapabilities {
    private val appContext = context.applicationContext

    // The LiteRT-LM library ships native code for arm64-v8a and x86_64 only.
    override val is64BitCpu: Boolean
        get() = Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

    override val totalMemoryBytes: Long
        get() {
            val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            manager?.getMemoryInfo(info)
            return info.totalMem
        }
}

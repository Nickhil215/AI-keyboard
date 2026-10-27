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

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Storage for secrets: one API key per cloud provider, plus the optional Hugging Face token used to
 * download gated local models. Each secret lives in its own named slot.
 *
 * Secrets are intentionally NOT kept in the regular preference datastore, because that datastore is
 * included in Android backups and in FlorisBoard's settings export.
 */
interface AiCredentialStore {
    fun getSecret(slot: String): String?
    fun setSecret(slot: String, value: String?)

    companion object {
        const val SLOT_HUGGING_FACE = "huggingface"
    }
}

/**
 * Keeps each secret encrypted with an AES-GCM key that never leaves the Android Keystore.
 *
 * The backup rules only include explicitly listed paths, and this file is not one of them, so the
 * encrypted key is also excluded from device backups.
 *
 * Note: this protects the key at rest on the device. It does not make a key shipped to a user's
 * device secret from that user; see docs/ai-features.md for the recommended proxy setup.
 */
class KeystoreAiCredentialStore(context: Context) : AiCredentialStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    override fun getSecret(slot: String): String? {
        val stored = prefs.getString(prefsKey(slot), null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_SIZE_BYTES)
            val cipherText = bytes.copyOfRange(IV_SIZE_BYTES, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(TAG_SIZE_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (_: Exception) {
            // The keystore key may be gone (e.g. after a restore). Treat that as "no key configured".
            null
        }
    }

    override fun setSecret(slot: String, value: String?) {
        val trimmed = value?.trim()
        if (trimmed.isNullOrEmpty()) {
            prefs.edit().remove(prefsKey(slot)).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.iv + cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(prefsKey(slot), Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun prefsKey(slot: String) = "secret_$slot"

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_FILE = "florisboard_ai_credentials"
        const val KEY_ALIAS = "florisboard_ai_secrets"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE_BYTES = 12
        const val TAG_SIZE_BITS = 128
    }
}

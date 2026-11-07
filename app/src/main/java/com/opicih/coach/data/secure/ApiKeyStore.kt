package com.opicih.coach.data.secure

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
 * Stores user-entered API keys encrypted with an AES-GCM key that never leaves the Android Keystore.
 * Keys are never bundled in the APK or source. (androidx.security EncryptedSharedPreferences is deprecated,
 * so the same idea is implemented directly with the platform Keystore.)
 */
class ApiKeyStore(context: Context) {
    enum class Provider(val prefKey: String, val label: String) {
        GEMINI("gemini", "Gemini (Google AI Studio) API 키"),
        ANTHROPIC("anthropic", "Claude (Anthropic) API 키"),
        OPENAI_STT("openai", "OpenAI API 키 (음성 전사 전용)"),
    }

    private val prefs = context.getSharedPreferences("secure_keys", Context.MODE_PRIVATE)

    fun get(p: Provider): String? = runCatching {
        val stored = prefs.getString(p.prefKey, null) ?: return null
        val (ivB64, dataB64) = stored.split(":", limit = 2)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(dataB64, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    fun has(p: Provider): Boolean = prefs.contains(p.prefKey)

    fun set(p: Provider, value: String?) {
        if (value.isNullOrBlank()) {
            prefs.edit().remove(p.prefKey).apply(); return
        }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        val enc = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
        prefs.edit().putString(p.prefKey, enc).apply()
    }

    fun clearAll() = prefs.edit().clear().apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "opic_api_keys"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

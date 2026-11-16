package com.claudian.call

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object TokenStore {
    private const val KEY_ALIAS = "claudian_token_aes_key"
    private const val SECURE_PREFS = "claudian_call_secure_prefs"
    private const val KEY_ENC_TOKEN = "encAuthToken"
    private const val KEY_IV = "encAuthIv"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    fun saveToken(ctx: Context, token: String) {
        val trimmed = token.trim()
        val sp = ctx.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
        if (trimmed.isEmpty()) {
            sp.edit().remove(KEY_ENC_TOKEN).remove(KEY_IV).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ct = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        sp.edit()
            .putString(KEY_ENC_TOKEN, Base64.encodeToString(ct, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun loadToken(ctx: Context, legacyPlaintext: String?): String {
        val sp = ctx.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
        val enc = sp.getString(KEY_ENC_TOKEN, null)
        val ivStr = sp.getString(KEY_IV, null)
        if (!enc.isNullOrBlank() && !ivStr.isNullOrBlank()) {
            return runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val iv = Base64.decode(ivStr, Base64.NO_WRAP)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
                String(cipher.doFinal(Base64.decode(enc, Base64.NO_WRAP)), Charsets.UTF_8)
            }.getOrDefault("")
        }
        val legacy = legacyPlaintext?.trim().orEmpty()
        if (legacy.isNotEmpty()) {
            runCatching { saveToken(ctx, legacy) }
            return legacy
        }
        return ""
    }
}

package com.baystudio.droide.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

 
internal class GitHubAccountSecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(slot: String): String {
        requireSlot(slot)
        val encoded = prefs.getString(slot, null) ?: return ""
        return runCatching { decrypt(slot, encoded) }.getOrElse {
            prefs.edit().remove(slot).commit()
            ""
        }
    }

    fun putDurable(slot: String, secret: String): Boolean {
        requireSlot(slot)
        if (secret.isBlank()) return removeDurable(slot)
        require(secret.toByteArray(Charsets.UTF_8).size <= MAX_SECRET_BYTES) { "GitHub account secret is too large" }
        return prefs.edit().putString(slot, encrypt(slot, secret)).commit()
    }

    fun removeDurable(slot: String): Boolean {
        requireSlot(slot)
        return prefs.edit().remove(slot).commit()
    }

    private fun encrypt(slot: String, plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad(slot))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        require(iv.size in 12..32)
        val packed = ByteArray(2 + iv.size + encrypted.size)
        packed[0] = FORMAT
        packed[1] = iv.size.toByte()
        System.arraycopy(iv, 0, packed, 2, iv.size)
        System.arraycopy(encrypted, 0, packed, 2 + iv.size, encrypted.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(slot: String, encoded: String): String {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size >= 3 && packed[0] == FORMAT)
        val ivSize = packed[1].toInt() and 0xff
        require(ivSize in 12..32 && packed.size > 2 + ivSize)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(2, 2 + ivSize)))
        cipher.updateAAD(aad(slot))
        val plaintext = cipher.doFinal(packed.copyOfRange(2 + ivSize, packed.size))
        require(plaintext.size <= MAX_SECRET_BYTES)
        return plaintext.toString(Charsets.UTF_8)
    }

    private fun aad(slot: String): ByteArray = "droide-github-account:$slot".toByteArray(Charsets.UTF_8)

    private fun requireSlot(slot: String) {
        require(slot == SLOT_CREDENTIAL || slot == SLOT_PENDING_OAUTH) { "Invalid GitHub account secret slot" }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val SLOT_CREDENTIAL = "credential"
        const val SLOT_PENDING_OAUTH = "pending_oauth"
        private const val PREFS = "droide_github_account_secrets_v1"
        private const val KEY_ALIAS = "droide_github_account_aes_v1"
        private const val MAX_SECRET_BYTES = 64 * 1024
        private const val FORMAT: Byte = 1
    }
}

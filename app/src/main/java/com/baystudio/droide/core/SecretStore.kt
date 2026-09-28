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

 
class SecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("droide_secrets_v1", Context.MODE_PRIVATE)
    private val alias = "droide_api_keys_v1"

    fun put(providerId: String, secret: String) {
        requireProviderId(providerId)
        if (secret.isBlank()) { remove(providerId); return }
        prefs.edit().putString(providerId, encode(providerId, secret)).apply()
    }

    // durable write used by provider connection transactions.
    fun putDurable(providerId: String, secret: String): Boolean {
        requireProviderId(providerId)
        if (secret.isBlank()) return removeDurable(providerId)
        return prefs.edit().putString(providerId, encode(providerId, secret)).commit()
    }

    fun get(providerId: String): String {
        requireProviderId(providerId)
        val encoded = prefs.getString(providerId, null) ?: return ""
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.isNotEmpty())
            if (packed[0] == FORMAT_AAD_BOUND) {
                decryptAadBound(providerId, packed)
            } else {
                

                val legacy = decryptLegacy(packed)
                put(providerId, legacy)
                legacy
            }
        }.getOrElse {
            prefs.edit().remove(providerId).apply()
            ""
        }
    }

    fun remove(providerId: String) {
        requireProviderId(providerId)
        prefs.edit().remove(providerId).apply()
    }

    // durable delete paired with ProviderConnectionStateStore commit/rollback.
    fun removeDurable(providerId: String): Boolean {
        requireProviderId(providerId)
        return prefs.edit().remove(providerId).commit()
    }

    private fun encode(providerId: String, secret: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad(providerId))
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(2 + cipher.iv.size + encrypted.size)
        packed[0] = FORMAT_AAD_BOUND
        packed[1] = cipher.iv.size.toByte()
        System.arraycopy(cipher.iv, 0, packed, 2, cipher.iv.size)
        System.arraycopy(encrypted, 0, packed, 2 + cipher.iv.size, encrypted.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decryptAadBound(providerId: String, packed: ByteArray): String {
        require(packed.size >= 3)
        val ivSize = packed[1].toInt() and 0xff
        require(ivSize in 12..32 && packed.size > 2 + ivSize)
        val iv = packed.copyOfRange(2, 2 + ivSize)
        val payload = packed.copyOfRange(2 + ivSize, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad(providerId))
        return cipher.doFinal(payload).toString(Charsets.UTF_8)
    }

    private fun decryptLegacy(packed: ByteArray): String {
        val ivSize = packed[0].toInt() and 0xff
        require(ivSize in 12..32 && packed.size > 1 + ivSize)
        val iv = packed.copyOfRange(1, 1 + ivSize)
        val payload = packed.copyOfRange(1 + ivSize, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(payload).toString(Charsets.UTF_8)
    }

    private fun aad(providerId: String): ByteArray = "droide-provider:$providerId".toByteArray(Charsets.UTF_8)

    private fun requireProviderId(providerId: String) {
        require(providerId.matches(Regex("[A-Za-z0-9._-]{1,96}"))) { "Invalid provider id" }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val FORMAT_AAD_BOUND: Byte = 2
    }
}

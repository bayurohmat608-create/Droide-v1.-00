package com.baystudio.droide.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec





class KeystoreBlobCipher(private val alias: String) {
    fun encrypt(plain: ByteArray, aad: ByteArray = byteArrayOf()): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plain)
        val iv = cipher.iv
        require(iv.size in 12..32) { "Unexpected GCM IV length" }

        return ByteArray(MAGIC.size + 2 + iv.size + encrypted.size).also { out ->
            var p = 0
            MAGIC.copyInto(out, destinationOffset = p); p += MAGIC.size
            out[p++] = FORMAT_VERSION
            out[p++] = iv.size.toByte()
            iv.copyInto(out, destinationOffset = p); p += iv.size
            encrypted.copyInto(out, destinationOffset = p)
        }
    }

    fun decrypt(packed: ByteArray, aad: ByteArray = byteArrayOf()): ByteArray {
        require(packed.size >= MAGIC.size + 2 + 12 + 16) { "Encrypted blob is truncated" }
        require(packed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Invalid encrypted blob header" }
        var p = MAGIC.size
        require(packed[p++] == FORMAT_VERSION) { "Unsupported encrypted blob version" }
        val ivSize = packed[p++].toInt() and 0xff
        require(ivSize in 12..32 && packed.size > p + ivSize + 15) { "Invalid encrypted blob IV" }
        val iv = packed.copyOfRange(p, p + ivSize); p += ivSize
        val payload = packed.copyOfRange(p, packed.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(payload)
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

    private companion object {
        val MAGIC = byteArrayOf(0x44, 0x52, 0x42, 0x31) 
        const val FORMAT_VERSION: Byte = 1
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

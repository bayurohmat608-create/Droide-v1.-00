package com.baystudio.droide.core

import android.content.Context
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import java.io.File








class EncryptedKadbIdentityStore(context: Context) : KadbPrivateKeyStore {
    private val dir = File(context.applicationContext.noBackupFilesDir, "device-bridge").apply { mkdirs() }
    private val file = File(dir, "adb-host-identity.drb")
    private val cipher = KeystoreBlobCipher("droide_kadb_host_identity_v1")
    private val aad = "droide:kadb-host-identity:v1".toByteArray(Charsets.UTF_8)

    override fun readPrivateKeyPem(): ByteArray? {
        if (!file.isFile) return null
        val packed = file.readBytes()
        require(packed.size <= MAX_PACKED_BYTES) { "ADB host identity is unexpectedly large" }
        return try {
            cipher.decrypt(packed, aad)
        } catch (t: Throwable) {
            throw IllegalStateException("ADB host identity cannot be decrypted; reset pairing identity and pair again", t)
        }
    }

    override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) {
        require(privateKeyPem.isNotEmpty() && privateKeyPem.size <= MAX_PEM_BYTES) { "Invalid ADB host private key" }
        val packed = cipher.encrypt(privateKeyPem, aad)
        val staged = File(dir, ".${file.name}.tmp")
        try {
            staged.outputStream().buffered().use { out ->
                out.write(packed)
                out.flush()
            }
            if (!staged.renameTo(file)) {
                file.outputStream().buffered().use { out -> out.write(packed); out.flush() }
                staged.delete()
            }
        } finally {
            staged.delete()
        }
    }

    override fun clear() {
        if (file.exists() && !file.delete()) error("Cannot delete ADB host identity")
    }

    private companion object {
        const val MAX_PEM_BYTES = 64 * 1024
        const val MAX_PACKED_BYTES = 128 * 1024
    }
}

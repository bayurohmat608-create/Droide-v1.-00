package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

 
object PackagedWorkstationRootfs {
    private val mutex = Mutex()

    suspend fun obtain(context: Context, spec: TrustedArtifactSpec): File {
        spec.validate()
        return obtainPinned(context, spec.fileName, spec.sha256, requireNotNull(spec.expectedBytes))
    }

    suspend fun obtainPinned(context: Context, fileName: String, sha256: String, pinnedBytes: Long): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(fileName.matches(Regex("[A-Za-z0-9._+-]{1,180}")) &&
                sha256.matches(Regex("[0-9a-f]{64}")) && pinnedBytes in 1L..1_500_000_000L)
            val cache = File(context.applicationContext.cacheDir, "workstation-images").apply { mkdirs() }.canonicalFile
            val target = File(cache, "$sha256-$fileName")
            check(target.parentFile?.canonicalFile == cache) { "Unsafe guest image path" }
            if (verified(target, sha256, pinnedBytes)) return@withLock target
            if (target.exists()) check(target.delete()) { "Could not remove untrusted cached guest image" }

            val pending = File(cache, "${target.name}.partial")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                context.applicationContext.assets.open("workstation/$fileName").use { input ->
                    FileOutputStream(pending).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied += read
                            check(copied <= pinnedBytes) { "Bundled guest image exceeds pinned size" }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }
                check(copied == pinnedBytes && digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == sha256) {
                    "Bundled guest image failed exact size/SHA-256 verification"
                }
                check(pending.renameTo(target)) { "Could not publish verified guest image" }
                target
            } catch (failure: Throwable) {
                pending.delete()
                throw failure
            }
        }
    }

     
    suspend fun evict(context: Context, fileName: String, sha256: String) = mutex.withLock {
        require(fileName.matches(Regex("[A-Za-z0-9._+-]{1,180}")) && sha256.matches(Regex("[0-9a-f]{64}")))
        val cache = File(context.cacheDir, "workstation-images").canonicalFile
        val target = File(cache, "$sha256-$fileName")
        check(target.parentFile?.canonicalFile == cache)
        if (target.exists()) check(target.delete()) { "Cannot remove committed asset cache" }
    }

    private fun verified(file: File, sha256: String, pinnedBytes: Long): Boolean {
        if (!file.isFile || file.length() != pinnedBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == sha256
    }
}

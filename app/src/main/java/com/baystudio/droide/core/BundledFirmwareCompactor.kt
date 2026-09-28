package com.baystudio.droide.core
import android.system.Os
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*

// A VM must use its own writable VARS copy.
object BundledFirmwareCompactor {
    private val templates = mapOf(
        "edk2-arm-vars.fd" to "b3b855c5a80310168051164986855692d1bdb06e67619856177965cd87c6774f",
        "edk2-aarch64-code.fd" to "47765fe344818cbc464b1c14ae658fb4b854f5c2ceffa982411731eb4865594d",
    )
    suspend fun compact(rootfs: File) = withContext(Dispatchers.IO) {
        for ((name, expected) in templates) {
            val file = File(rootfs, "usr/share/qemu/$name")
            LocalExecutionSubstrate.requireSafeLocalPath(file.absolutePath)
            if (!file.isFile || PathSecurity.isSymbolicLink(file) || file.length() != 67_108_864L || sha256(file) != expected) continue
            val old = Os.stat(file.absolutePath)
            val temp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.sparse")
            try {
                file.inputStream().use { input -> RandomAccessFile(temp, "rw").use { output ->
                    val buffer = ByteArray(16384); var position = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break
                        if ((0 until n).any { buffer[it] != 0.toByte() }) output.write(buffer, 0, n) else output.seek(position + n)
                        position += n
                    }
                    output.setLength(position); output.fd.sync()
                } }
                check(sha256(temp) == expected) { "Sparse firmware integrity failure" }
                Os.chmod(temp.absolutePath, old.st_mode and 511); temp.setLastModified(file.lastModified())
                if (Os.stat(temp.absolutePath).st_blocks < old.st_blocks) {
                    check(file.length() == 67_108_864L && sha256(file) == expected) { "Firmware changed during compaction" }
                    check(temp.renameTo(file)) { "Could not commit sparse firmware" }
                }
            } finally { temp.delete() }
        }
    }
    private suspend fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

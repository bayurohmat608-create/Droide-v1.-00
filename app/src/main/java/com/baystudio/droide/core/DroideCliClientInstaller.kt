package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

// Keep executable payloads in APK-native storage to preserve W^X.
object DroideCliClientInstaller {
    const val NATIVE_FILE_NAME = "libdroide_cli.so"
    const val SHA256 = "c8938f2fad4eed323445ca31b57c8659adbcdf4956491720079428749e09f2a9"
    private const val MAX_BYTES = 64 * 1024L
    private const val ELF_HEADER_BYTES = 64
    private const val PT_LOAD = 1L
    private const val PT_INTERP = 3L
    private const val PF_X = 1L
    private const val ET_EXEC = 2
    private const val EM_AARCH64 = 183
    private const val MIN_LOAD_ALIGNMENT = 16L * 1024L

    
    fun ensureInstalled(context: Context): File {
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "aarch64" }) {
            "Droide CLI client requires ARM64"
        }
        val app = context.applicationContext
        val nativeRoot = File(requireNotNull(app.applicationInfo.nativeLibraryDir) { "Native library directory is unavailable" })
            .canonicalFile
        val binary = File(nativeRoot, NATIVE_FILE_NAME)
        require(binary.canonicalFile.parentFile == nativeRoot) { "Droide CLI native path escaped nativeLibraryDir" }
        require(binary.isFile && !PathSecurity.isSymbolicLink(binary)) { "Bundled Droide CLI native payload is missing" }
        require(binary.length() in 1..MAX_BYTES) { "Bundled Droide CLI client exceeds its reviewed size" }
        require(sha256(binary) == SHA256) { "Bundled Droide CLI client failed integrity verification" }
        requireStaticArm64Elf(binary)
        check(binary.canExecute()) { "Bundled Droide CLI payload is not executable" }
        return binary
    }

    internal fun requireStaticArm64Elf(file: File) {
        RandomAccessFile(file, "r").use { raf ->
            require(raf.length() >= ELF_HEADER_BYTES) { "Droide CLI ELF header is truncated" }
            val header = ByteArray(ELF_HEADER_BYTES)
            raf.readFully(header)
            require(header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()) {
                "Droide CLI client is not ELF"
            }
            require(header[4] == 2.toByte() && header[5] == 1.toByte()) { "Droide CLI client must be ELF64 little-endian" }
            require(u16le(header, 16) == ET_EXEC) { "Droide CLI client must be a static executable" }
            require(u16le(header, 18) == EM_AARCH64) { "Droide CLI client must target AArch64" }
            val phoff = u64le(header, 32)
            val phentsize = u16le(header, 54)
            val phnum = u16le(header, 56)
            require(phoff >= ELF_HEADER_BYTES && phentsize >= 56 && phnum in 1..32) { "Droide CLI program headers are invalid" }
            require(phoff + phentsize.toLong() * phnum <= raf.length()) { "Droide CLI program headers are truncated" }
            var hasExecutableLoad = false
            repeat(phnum) { index ->
                val ph = ByteArray(phentsize)
                raf.seek(phoff + index.toLong() * phentsize)
                raf.readFully(ph)
                val type = u32le(ph, 0)
                require(type != PT_INTERP) { "Droide CLI client must not depend on a dynamic loader" }
                if (type == PT_LOAD) {
                    val flags = u32le(ph, 4)
                    val alignment = u64le(ph, 48)
                    require(alignment >= MIN_LOAD_ALIGNMENT && alignment % MIN_LOAD_ALIGNMENT == 0L) {
                        "Droide CLI load segment is not 16 KiB aligned"
                    }
                    if (flags and PF_X != 0L) hasExecutableLoad = true
                }
            }
            require(hasExecutableLoad) { "Droide CLI client has no executable load segment" }
        }
    }

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32le(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun u64le(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(8) { index -> value = value or ((bytes[offset + index].toLong() and 0xff) shl (index * 8)) }
        return value
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

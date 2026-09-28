package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest

// A downloaded executable in filesDir is never a substitute.
object PackagedLinuxEngine {
    const val LIBRARY_NAME = "libdroide_proot.so"
    private const val LIBRARY_SHA256 = "f6b0381ab9a066fa620fef0001737fd3cfaf9d22474f013ac48d7861411374ac"
    private const val LOADER_NAME = "libproot-loader.so"
    private const val LOADER_SHA256 = "44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04"
    private const val LOADER32_NAME = "libproot-loader32.so"
    private const val LOADER32_SHA256 = "25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34"

    fun requireReady(context: Context): File {
        check(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" && android.os.Process.is64Bit()) {
            "Local Linux currently needs an ARM64 Android process"
        }
        val nativeDirPath = context.applicationContext.applicationInfo.nativeLibraryDir
        check(!nativeDirPath.isNullOrBlank()) { "Android did not provide a native-library directory" }
        val nativeDir = File(nativeDirPath).canonicalFile
        val launcher = File(nativeDir, LIBRARY_NAME).canonicalFile
        check(launcher.parentFile == nativeDir && launcher.isFile && launcher.canExecute()) {
            "Droide Linux engine is absent from APK native libraries; install a build with a certified ARM64 engine"
        }
        SystemLinkerExec.requireAndroidArm64DynamicElf(launcher)
        check(sha256(launcher) == LIBRARY_SHA256) { "Packaged PRoot does not match the reviewed release" }
        loader(nativeDir, LOADER_NAME, LOADER_SHA256, 2)
        loader(nativeDir, LOADER32_NAME, LOADER32_SHA256, 1)
        return launcher
    }

    fun loaderEnvironment(context: Context): Map<String, String> {
        val nativeDir = requireReady(context).parentFile!!
        return mapOf(
            "PROOT_LOADER" to File(nativeDir, LOADER_NAME).absolutePath,
            "PROOT_LOADER_32" to File(nativeDir, LOADER32_NAME).absolutePath,
        )
    }

    private fun loader(nativeDir: File, name: String, expectedSha: String, elfClass: Int) {
        val file = File(nativeDir, name).canonicalFile
        check(file.parentFile == nativeDir && file.isFile && file.canExecute()) { "Packaged PRoot loader is absent: $name" }
        DataInputStream(file.inputStream()).use { stream ->
            val header = ByteArray(20)
            stream.readFully(header)
            check(header[0] == 0x7f.toByte() &&
                header[1] == 69.toByte() && header[2] == 76.toByte() && header[3] == 70.toByte() &&
                header[4] == elfClass.toByte() && header[5] == 1.toByte() &&
                header[18] == (if (elfClass == 2) 183.toByte() else 40.toByte()) && header[19] == 0.toByte()) {
                "Packaged PRoot loader has an unsupported ELF ABI: $name"
            }
        }
        check(sha256(file) == expectedSha) { "Packaged PRoot loader does not match the reviewed release: $name" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val block = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(block)
                if (n < 0) break
                digest.update(block, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun unavailableReason(context: Context): String? =
        runCatching { requireReady(context) }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
}

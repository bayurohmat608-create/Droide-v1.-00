package com.baystudio.droide.core
import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.tukaani.xz.XZInputStream

 
object BundledRootfsExtractor {
    data class Image(val file: String, val bytes: Long, val sha256: String, val tarBytes: Long, val tarSha256: String)
    val alpine = Image("alpine-minirootfs-3.24.2-aarch64.tar.xz", 3_203_364,
        "66c582602205a7f90657c5cb01c8d7d1bfd0a2fc8eb03bd9de288f2c9ef2b935", 8_949_760,
        "1886356f5ecb94414310225351b1ea4917218dc323d689c2921f28737bd09a28")
    val ubuntu = Image("ubuntu-base-24.04.5-base-arm64.tar.xz", 17_880_788,
        "33f099c82ff503ddae6ff669bb274165d70a7602de84cfd34cb0e30ce4b766ce", 106_741_760,
        "d0cebb41bb205a8e4e2afc60987dca30619a4fb2c6ec4a1a23a42839254a2ba6")
    suspend fun extract(context: Context, image: Image, destination: File) = withContext(Dispatchers.IO) {
        require(image == alpine || image == ubuntu)
        LocalExecutionSubstrate.requireSafeLocalPath(destination.absolutePath)
        check(destination.isDirectory && destination.list()?.isEmpty() == true && !PathSecurity.isSymbolicLink(destination)) {
            "Rootfs extraction requires an empty private staging directory"
        }
        val task = currentCoroutineContext()
        fun digest(decoded: Boolean): Pair<Long, String> {
            val md = MessageDigest.getInstance("SHA-256"); var count = 0L
            context.assets.open("workstation/${image.file}").use { raw ->
                (if (decoded) XZInputStream(raw, 32768) else raw).use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        task.ensureActive(); val n = input.read(buffer); if (n < 0) break
                        count += n; check(count <= if (decoded) image.tarBytes else image.bytes)
                        md.update(buffer, 0, n)
                    }
                }
            }
            return count to md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        check(digest(false) == (image.bytes to image.sha256)) { "Bundled XZ integrity failure" }
        check(digest(true) == (image.tarBytes to image.tarSha256)) { "Bundled TAR integrity failure" }
        
        val result = LocalProcessSupervisor.capture(
            listOf("/system/bin/toybox", "tar", "-xf", "-", "-C", destination.absolutePath), destination,
            maxOutputBytes = 65536, timeoutMs = 180_000,
            input = { output ->
                context.assets.open("workstation/${image.file}").use { raw -> XZInputStream(raw, 32768).use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        task.ensureActive(); val n = input.read(buffer); if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                } }
            },
        )
        check(result.exitCode == 0 && !result.timedOut) { "Rootfs extraction failed: ${result.output.takeLast(4000)}" }
    }
}

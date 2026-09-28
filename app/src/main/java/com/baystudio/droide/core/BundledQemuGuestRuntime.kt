package com.baystudio.droide.core
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object BundledQemuGuestSpec {
    const val FILE_NAME = "qemu-offline-alpine-v3.24-arm64.tar"
    const val SHA256 = "406fbb16607902b7e39bc8b8c90bca3dda1c36772166b4f0d0777bb9b5d5b0f6"
    const val BYTES = 38_912_000L
    const val VERSION = "11.0.3-r0"
    val commands = listOf("qemu-x86_64", "qemu-system-aarch64", "qemu-system-x86_64", "qemu-img")
}
// The journal supports retry; it is not a package rollback snapshot.
class BundledQemuGuestRuntime(
    context: Context,
    private val alpine: WorkstationGuestEnvironmentManager = WorkstationGuestEnvironmentManager(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val root = appContext.filesDir.absolutePath
    private val guest = File(root, "guest/${WorkstationGuestEnvironmentSpec.ROOT_DIR}")
    private var failedEngine: String? = null
    suspend fun ensureInstalled(): String = withContext(Dispatchers.IO) {
        LocalExecutionSubstrate.requireContextRoot(root); PackagedLinuxEngine.requireReady(appContext)
        AlpineGuestMutationGate.mutex.withLock {
            alpine.ensureInstalledUnlocked()
            val lock = File(guest, "QEMU_ENGINE_LOCK.txt")
            val journal = File(guest, "QEMU_INSTALL_PENDING.txt")
            val packageRoot = File(guest, "rootfs/opt/droide-qemu-pack")
            listOf(lock, journal, packageRoot).forEach { LocalExecutionSubstrate.requireSafeLocalPath(it.absolutePath) }
            if (!journal.exists() && lock.isFile && lock.length() <= 65536 && lock.readLines().contains("schema=2") &&
                lock.readLines().contains("asset_sha256=${BundledQemuGuestSpec.SHA256}") && healthy()) {
                cleanup(packageRoot)
                return@withLock File(guest, "rootfs/usr/bin/qemu-system-aarch64").absolutePath
            }
            DeviceWorkstationStorageGuard.requireHeadroom(512L * 1024 * 1024, "activate bundled QEMU engines")
            val source = PackagedWorkstationRootfs.obtainPinned(appContext, BundledQemuGuestSpec.FILE_NAME,
                BundledQemuGuestSpec.SHA256, BundledQemuGuestSpec.BYTES)
            writeAtomic(journal, "schema=1\nphase=package-mutation\nasset_sha256=${BundledQemuGuestSpec.SHA256}\n")
            check(packageRoot.mkdirs() || packageRoot.isDirectory)
            val unpack = LocalExecutionSubstrate.shellBounded(
                "toybox tar -xf ${quote(source.absolutePath)} -C ${quote(packageRoot.absolutePath)}", 65536, 120_000)
            check(unpack.exitCode == 0) { "QEMU extraction failed: ${unpack.output.takeLast(4000)}" }
            val installed = alpine.executeInstalled(listOf("/bin/sh", "-lc", "apk --no-network add --no-cache /opt/droide-qemu-pack/*.apk"), 256_000)
            check(installed.exitCode == 0) { "QEMU install interrupted; retry required: ${installed.output.takeLast(8000)}" }
            check(healthy()) { "QEMU executable failed: $failedEngine; package journal retained" }
            BundledFirmwareCompactor.compact(File(guest, "rootfs"))
            writeAtomic(lock, "schema=2\nsource=APK_SIGNED_ALPINE_PACKAGES\nversion=${BundledQemuGuestSpec.VERSION}\n" +
                "asset_sha256=${BundledQemuGuestSpec.SHA256}\nengine_executable=1\nvm_booted=0\ndebug_certified=0\n")
            check(journal.delete()) { "Could not commit QEMU activation journal" }
            cleanup(packageRoot)
            File(guest, "rootfs/usr/bin/qemu-system-aarch64").absolutePath
        }
    }
    private suspend fun cleanup(packageRoot: File) {
        check(PathSecurity.deleteTreeNoFollow(packageRoot)) { "Cannot clean committed QEMU package cache" }
        PackagedWorkstationRootfs.evict(appContext, BundledQemuGuestSpec.FILE_NAME, BundledQemuGuestSpec.SHA256)
        File(guest, ".qemu-offline-pack.tar").delete()
    }
    private suspend fun healthy(): Boolean {
        for (command in BundledQemuGuestSpec.commands) {
            val probe = alpine.executeInstalled(listOf("/usr/bin/$command", "--version"), 8192, timeoutMs = 10_000)
            if (probe.exitCode != 0 || probe.timedOut || !Regex("\\b11\\.0\\.3\\b").containsMatchIn(probe.output.lineSequence().firstOrNull().orEmpty())) {
                failedEngine = command; return false
            }
        }
        failedEngine = null; return true
    }
    private fun writeAtomic(file: File, text: String) {
        val pending = File(file.parentFile, ".${file.name}.next")
        LocalExecutionSubstrate.requireSafeLocalPath(pending.absolutePath)
        FileOutputStream(pending).use { it.write(text.toByteArray()); it.fd.sync() }
        check(pending.renameTo(file)) { "Cannot commit ${file.name}" }
    }
    private fun quote(value: String) = LocalExecutionSubstrate.shellQuote(value)
}

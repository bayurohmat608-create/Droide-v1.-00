package com.baystudio.droide.core

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class QemuQmpClient(private val socketPath: File) {
    private val json = Json { ignoreUnknownKeys = true }

    data class Status(val running: Boolean, val status: String)

    fun queryStatus(timeoutMs: Int = 3_000): Status = withConnection(timeoutMs) { reader, writer ->
        negotiate(reader, writer)
        val response = command(reader, writer, "query-status", "status-1")
        val returned = response["return"]?.jsonObject ?: error("QMP query-status did not return an object")
        Status(
            running = returned["running"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            status = returned["status"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    fun systemPowerdown(timeoutMs: Int = 3_000) = withConnection(timeoutMs) { reader, writer ->
        negotiate(reader, writer)
        command(reader, writer, "system_powerdown", "powerdown-1")
        Unit
    }

    fun quit(timeoutMs: Int = 3_000) = withConnection(timeoutMs) { reader, writer ->
        negotiate(reader, writer)
        command(reader, writer, "quit", "quit-1")
        Unit
    }

    private fun <T> withConnection(timeoutMs: Int, block: (BufferedReader, OutputStreamWriter) -> T): T {
        require(timeoutMs in 100..15_000)
        require(socketPath.absolutePath.toByteArray(Charsets.UTF_8).size <= 100) { "QMP filesystem socket path is too long" }
        require(socketPath.absolutePath.none { it == '\u0000' || it == '\n' || it == '\r' })
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(socketPath.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.setSoTimeout(timeoutMs)
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8), 8_192)
            val writer = OutputStreamWriter(socket.outputStream, Charsets.UTF_8)
            return block(reader, writer)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun negotiate(reader: BufferedReader, writer: OutputStreamWriter) {
        val greeting = readObject(reader)
        check(greeting.containsKey("QMP")) { "Invalid QMP greeting" }
        val response = command(reader, writer, "qmp_capabilities", "caps-1")
        check(response.containsKey("return") && !response.containsKey("error")) { "QMP capability negotiation failed" }
    }

    private fun command(reader: BufferedReader, writer: OutputStreamWriter, execute: String, id: String): JsonObject {
        writer.write("{\"execute\":\"$execute\",\"id\":\"$id\"}\r\n")
        writer.flush()
        repeat(64) {
            val objectValue = readObject(reader)
            if (objectValue["id"]?.jsonPrimitive?.content == id) {
                check(!objectValue.containsKey("error")) { "QMP command failed: $execute" }
                return objectValue
            }
        }
        error("QMP response limit exceeded")
    }

    private fun readObject(reader: BufferedReader): JsonObject {
        val line = reader.readLine() ?: error("QMP connection closed")
        require(line.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "QMP frame exceeds 64 KiB" }
        return json.parseToJsonElement(line).jsonObject
    }
}


class QemuVmManager(context: Context, private val workspaceId: String) {
    data class RunningSession internal constructor(
        val profile: QemuVmProfile,
        val image: QemuVmImageSpec,
        val process: Process,
        internal val lease: RemoteProcessLease,
        internal val qmpSocket: File,
        internal val serialLog: File,
        internal val operationLease: LongRunningOperationJournal.Lease,
        internal val foreground: DevelopmentForegroundService.Handle?,
        internal val startedAtEpochMs: Long,
    )

    private val appContext = context.applicationContext
    private val appRoot = appContext.filesDir.canonicalFile
    private val alpine = WorkstationGuestEnvironmentManager(appContext)
    private val imageProvider = AlpineQemuVmImageProvider(appContext)
    private val vmRoot = File(appRoot, "vm").apply { mkdirs() }
    private val journal = LongRunningOperationJournal(
        root = File(appRoot, "operation-journal"),
        workspaceId = "vm-$workspaceId",
        currentPid = android.os.Process.myPid(),
        currentProcessIdentity = LocalExecutionSubstrate.processIdentity(),
    )
    private val lifecycleMutex = Mutex()
    @Volatile private var activeSession: RunningSession? = null
    @Volatile private var pendingLease: RemoteProcessLease? = null
    @Volatile private var pendingProcess: Process? = null
    @Volatile private var pendingOperationLease: LongRunningOperationJournal.Lease? = null
    @Volatile private var pendingForeground: DevelopmentForegroundService.Handle? = null
    @Volatile private var closed = false

    fun imagePath(spec: QemuVmImageSpec): File {
        spec.validate()
        return File(vmRoot, "images/${spec.id}/${spec.fileName}")
    }

    suspend fun installDefaultAlpineImage(
        onProgress: (TrustedArtifactDownloadProgress) -> Unit = {},
    ): QemuVmImageSpec {
        val resolved = imageProvider.resolveAndDownload(onProgress)
        importVerifiedBaseImage(resolved.spec, resolved.verifiedDownload)
        return resolved.spec
    }

    suspend fun importVerifiedBaseImage(spec: QemuVmImageSpec, source: File): File = withContext(Dispatchers.IO) {
        spec.validate()
        check(source.isFile && !PathSecurity.isSymbolicLink(source)) { "VM image source is unavailable or unsafe" }
        DeviceWorkstationStorageGuard.requireHeadroom(
            maxOf(256L * 1024L * 1024L, spec.expectedBytes + 128L * 1024L * 1024L),
            "import the pinned VM base image",
        )
        verifyPinnedImage(source, spec)
        val destination = imagePath(spec)
        val parent = destination.parentFile ?: error("VM image has no parent directory")
        parent.mkdirs()
        check(
            parent.isDirectory &&
                !PathSecurity.isSymbolicLink(parent) &&
                parent.canonicalFile.toPath().startsWith(vmRoot.canonicalFile.toPath())
        ) { "Unsafe VM image directory" }
        val pending = File(parent, ".${destination.name}.${UUID.randomUUID().toString().take(8)}.tmp")
        check(!PathSecurity.isSymbolicLink(destination) && !PathSecurity.isSymbolicLink(pending)) { "Unsafe VM image destination" }
        try {
            source.inputStream().use { input ->
                java.io.FileOutputStream(pending).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            check(pending.length() == spec.expectedBytes) { "VM image copy is incomplete" }
            val moved = runCatching {
                java.nio.file.Files.move(
                    pending.toPath(), destination.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }.recoverCatching {
                java.nio.file.Files.move(pending.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            moved.getOrThrow()
        } finally {
            if (pending.exists()) pending.delete()
        }
        destination
    }

    suspend fun start(profile: QemuVmProfile, image: QemuVmImageSpec): RunningSession = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            check(!closed) { "VM manager is closed" }
            profile.validate(); image.validate()
            activeSession?.let { existing ->
                if (existing.process.isAlive) error("A QEMU VM session is already running for this workspace")
                activeSession = null
            }
            journal.reconcileInterrupted()
            val base = imagePath(image)
            check(base.isFile && !PathSecurity.isSymbolicLink(base)) { "Pinned VM base image is not installed" }
            verifyPinnedImage(base, image)
            DeviceWorkstationStorageGuard.requireHeadroom(768L * 1024L * 1024L, "start the QEMU virtual machine")
            alpine.ensureQemuInstalled()
            // Never reuse a writable overlay across two immutable base-image identities.
            val profileRoot = File(vmRoot, "profiles/${profile.id}/${QemuVmStoragePolicy.imageKey(image)}").apply { mkdirs() }
            // QMP is a filesystem Unix socket, so keep its pathname short even for long profile IDs.
            val runtimeId = MessageDigest.getInstance("SHA-256")
                .digest(profile.id.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)
            val runRoot = File(vmRoot, "run/$runtimeId").apply { mkdirs() }
            listOf(profileRoot, runRoot).forEach { dir ->
                check(dir.canonicalFile.toPath().startsWith(vmRoot.canonicalFile.toPath()) && !PathSecurity.isSymbolicLink(dir)) {
                    "Unsafe VM runtime directory"
                }
            }
            val paths = QemuVmCommandBuilder.Paths(
                appRoot = appRoot,
                baseImage = base,
                overlay = File(profileRoot, "overlay.qcow2"),
                qmpSocket = File(runRoot, "qmp.sock"),
                pidFile = File(runRoot, "qemu.pid"),
                serialLog = File(profileRoot, "serial.log"),
                processLog = File(profileRoot, "qemu.log"),
            )
            listOf(paths.qmpSocket, paths.pidFile).forEach { stale -> if (stale.exists() && !PathSecurity.isSymbolicLink(stale)) stale.delete() }
            if (!paths.overlay.exists()) {
                val create = alpine.executeInstalled(QemuVmCommandBuilder.overlayCreateArgv(paths), maxOutputBytes = 32_768, timeoutMs = 120_000)
                check(create.exitCode == 0 && !create.timedOut) { "Could not create VM overlay: ${create.output.takeLast(4_000)}" }
            }
            check(!PathSecurity.isSymbolicLink(paths.overlay)) { "VM overlay is unsafe" }
            val checkImage = alpine.executeInstalled(QemuVmCommandBuilder.imageCheckArgv(paths), maxOutputBytes = 64_000, timeoutMs = 120_000)
            check(checkImage.exitCode == 0 && !checkImage.timedOut) { "VM overlay failed qemu-img check: ${checkImage.output.takeLast(4_000)}" }

            // Boot-health evidence must come from this launch, never a previous guest session.
            listOf(paths.serialLog, paths.processLog).forEach { log ->
                check(!PathSecurity.isSymbolicLink(log)) { "Unsafe VM log path" }
            }
            java.io.FileOutputStream(paths.serialLog, false).use { output ->
                output.flush()
                output.fd.sync()
            }

            check(!closed) { "VM manager closed before launch" }
            val operationLease = journal.begin(LongRunningOperationJournal.Kind.VM, "QEMU VM ${profile.id}")
            pendingOperationLease = operationLease
            val lease = RemoteProcessLease.createLocal("qemu-vm")
            pendingLease = lease
            var foreground: DevelopmentForegroundService.Handle? = null
            var process: Process? = null
            try {
                foreground = DevelopmentForegroundService.startRequired(appContext, "Virtual machine ${profile.id}") {
                    try {
                        LocalExecutionSubstrate.terminateLease(lease)
                    } finally {
                        operationLease.cancel("Stopped from Droide developer-session notification")
                    }
                }
                pendingForeground = foreground
                check(!closed) { "VM manager closed before process start" }
                val launcher = alpine.launcherPath()
                val argv = listOf("/system/bin/sh", launcher) + QemuVmCommandBuilder.startArgv(profile, paths)
                // Android's Process API has no pid(). The shared lease wrapper records its own
                // PID before exec and starts a process group for owned descendant cleanup.
                val leasedCommand = lease.wrap(argv.joinToString(" ", transform = LocalExecutionSubstrate::shellQuote))
                process = ProcessBuilder("/system/bin/sh", "-c", leasedCommand)
                    .directory(appRoot)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(paths.processLog))
                    .apply { environment().putAll(lease.environment) }
                    .start()
                    .also { pendingProcess = it }
                val startedProcess = checkNotNull(process)
                val status = waitForQmp(paths.qmpSocket, startedProcess)
                check(!closed) { "VM manager closed while QEMU was starting" }
                check(status.running || status.status in setOf("prelaunch", "paused", "inmigrate")) {
                    "QEMU did not enter a valid VM state: ${status.status}"
                }
                check(!closed) { "VM manager closed before VM session activation" }
                RunningSession(
                    profile = profile,
                    image = image,
                    process = startedProcess,
                    lease = lease,
                    qmpSocket = paths.qmpSocket,
                    serialLog = paths.serialLog,
                    operationLease = operationLease,
                    foreground = foreground,
                    startedAtEpochMs = System.currentTimeMillis(),
                ).also {
                    activeSession = it
                    clearPendingStart()
                    check(!closed) { "VM manager closed while VM session was activating" }
                }
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    runCatching { LocalExecutionSubstrate.terminateLease(lease) }
                    process?.let { started -> runCatching { LocalProcessSupervisor.terminate(started) } }
                    DevelopmentForegroundService.finish(appContext, foreground)
                    runCatching { operationLease.fail(failure.message.orEmpty().take(400)) }
                    clearPendingStart()
                }
                throw failure
            }
        }
    }

    
    suspend fun awaitBootHealth(
        session: RunningSession,
        timeoutMs: Long = 180_000L,
    ): QemuVmBootHealth = withContext(Dispatchers.IO) {
        require(timeoutMs in 10_000L..600_000L) { "Invalid VM boot-health timeout" }
        val markers = session.image.serialIdentityMarkers
        require(markers.isNotEmpty()) { "VM image has no guest identity markers" }
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastStatus = QemuQmpClient.Status(running = false, status = "unavailable")
        var lastTail = ""
        while (System.currentTimeMillis() < deadline) {
            check(session.process.isAlive) { "QEMU exited before guest boot health was established: ${lastTail.takeLast(2_000)}" }
            lastStatus = runCatching { QemuQmpClient(session.qmpSocket).queryStatus(timeoutMs = 1_500) }.getOrElse { lastStatus }
            lastTail = readSerialTail(session.serialLog, MAX_SERIAL_HEALTH_BYTES)
            if (QemuVmBootHealthPolicy.healthy(lastStatus.running, lastStatus.status, lastTail, markers)) {
                return@withContext QemuVmBootHealth(
                    qmpStatus = lastStatus.status,
                    matchedIdentityMarkers = markers,
                    observedAtEpochMs = System.currentTimeMillis(),
                )
            }
            delay(250)
        }
        error(
            "VM boot-health probe timed out; qmp=${lastStatus.status}; missing=" +
                markers.filterNot { lastTail.contains(it, ignoreCase = true) }.joinToString(",")
        )
    }

    suspend fun stop(session: RunningSession, gracefulTimeoutMs: Long = 8_000) = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock { stopLocked(session, gracefulTimeoutMs) }
    }

    private suspend fun stopLocked(session: RunningSession, gracefulTimeoutMs: Long) {
        require(gracefulTimeoutMs in 1_000..30_000)
        try {
            runCatching { QemuQmpClient(session.qmpSocket).systemPowerdown() }
            val deadline = System.currentTimeMillis() + gracefulTimeoutMs
            while (session.process.isAlive && System.currentTimeMillis() < deadline) delay(100)
            if (session.process.isAlive) runCatching { QemuQmpClient(session.qmpSocket).quit() }
            if (session.process.isAlive) LocalExecutionSubstrate.terminateLease(session.lease)
            if (session.process.isAlive) LocalProcessSupervisor.terminate(session.process)
            session.operationLease.complete("QEMU process stopped; guest boot certification is tracked separately.")
        } catch (failure: Throwable) {
            session.operationLease.fail(failure.message.orEmpty().take(400))
            throw failure
        } finally {
            if (activeSession === session) activeSession = null
            DevelopmentForegroundService.finish(appContext, session.foreground)
        }
    }

    suspend fun shutdown() {
        closed = true
        withContext(Dispatchers.IO) {
            lifecycleMutex.withLock {
                activeSession?.let { session -> runCatching { stopLocked(session, gracefulTimeoutMs = 3_000) } }
                activeSession = null
                clearPendingStart()
            }
        }
    }

    // Recheck authoritative state at commit boundaries to avoid stale writes.
    fun closeNow() {
        closed = true
        val active = activeSession
        activeSession = null
        val leases = listOfNotNull(active?.lease, pendingLease).distinct()
        leases.forEach { runCatching { LocalExecutionSubstrate.terminateLease(it) } }
        listOfNotNull(active?.process, pendingProcess).distinct().forEach { runCatching { LocalProcessSupervisor.terminate(it) } }
        pendingOperationLease?.let { runCatching { it.cancel("Workspace closed while VM startup was in progress") } }
        active?.operationLease?.let { runCatching { it.cancel("Workspace closed before VM shutdown completed") } }
        DevelopmentForegroundService.finish(appContext, pendingForeground)
        active?.let { DevelopmentForegroundService.finish(appContext, it.foreground) }
        clearPendingStart()
    }

    private fun clearPendingStart() {
        pendingLease = null
        pendingProcess = null
        pendingOperationLease = null
        pendingForeground = null
    }

    internal fun interruptedSessions(): List<LongRunningOperationJournal.Record> = journal.reconcileInterrupted()

    private fun verifyPinnedImage(file: File, spec: QemuVmImageSpec) {
        check(file.isFile && !PathSecurity.isSymbolicLink(file)) { "Pinned VM image is unavailable or unsafe" }
        check(file.length() == spec.expectedBytes) { "VM image byte count does not match pinned metadata" }
        check(fileDigest(file, "SHA-256") == spec.sha256) { "VM image SHA-256 mismatch" }
        spec.sha512?.let { expected ->
            check(fileDigest(file, "SHA-512") == expected) { "VM image SHA-512 mismatch" }
        }
    }

    private fun fileDigest(file: File, algorithm: String): String = file.inputStream().buffered(1024 * 1024).use { input ->
        val digest = MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n > 0) digest.update(buffer, 0, n)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readSerialTail(file: File, maxBytes: Int): String {
        require(maxBytes in 1..1_048_576)
        if (!file.isFile || PathSecurity.isSymbolicLink(file)) return ""
        check(file.canonicalFile.toPath().startsWith(vmRoot.canonicalFile.toPath())) { "Unsafe VM serial log" }
        return RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val count = minOf(length, maxBytes.toLong()).toInt()
            input.seek(length - count)
            val bytes = ByteArray(count)
            input.readFully(bytes)
            bytes.toString(Charsets.UTF_8)
        }
    }

    private suspend fun waitForQmp(socket: File, process: Process): QemuQmpClient.Status {
        val deadline = System.currentTimeMillis() + 10_000
        var lastFailure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            check(process.isAlive) { "QEMU exited before QMP became ready" }
            if (socket.exists() && !PathSecurity.isSymbolicLink(socket)) {
                val status = runCatching { QemuQmpClient(socket).queryStatus(timeoutMs = 1_000) }
                if (status.isSuccess) return status.getOrThrow()
                lastFailure = status.exceptionOrNull()
            }
            delay(100)
        }
        throw IllegalStateException("QEMU QMP handshake did not become ready", lastFailure)
    }

    private companion object {
        const val MAX_SERIAL_HEALTH_BYTES = 256 * 1024
    }
}

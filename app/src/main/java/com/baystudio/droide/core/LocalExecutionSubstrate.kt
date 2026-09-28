package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SubstrateState(
    val ready: Boolean = false,
    val prootAvailable: Boolean = false,
    val ubuntuAvailable: Boolean = false
)

object LocalExecutionSubstrate {
    private val _state = MutableStateFlow(SubstrateState())
    val state: StateFlow<SubstrateState> = _state

    private lateinit var filesDir: File
    private lateinit var appContext: Context

    fun init(context: Context) {
        val requested = context.applicationContext.filesDir.canonicalFile
        if (::filesDir.isInitialized) {
            check(filesDir.canonicalFile == requested) { "Cannot rebind the local execution substrate to another application root" }
        }
        filesDir = requested
        appContext = context.applicationContext
        val guest = ubuntuGuestRoot()
        _state.value = SubstrateState(
            // File presence and ELF admission do not certify the local execution gate.
            ready = false,
            prootAvailable = PackagedLinuxEngine.unavailableReason(appContext) == null,
            ubuntuAvailable = File(guest, "rootfs").isDirectory
        )
    }

    private fun ubuntuGuestRoot(): File = File(filesDir, "guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}")

     
    fun linuxLaunchSpec(workDir: File, alpine: Boolean = false): TerminalLaunchSpec {
        check(::filesDir.isInitialized) { "Local execution substrate has not been initialized" }
        check(android.os.Process.is64Bit() && android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            "Local Linux currently requires an ARM64 Android process"
        }
        val guest = if (alpine) File(filesDir, "guest/${WorkstationGuestEnvironmentSpec.ROOT_DIR}") else ubuntuGuestRoot()
        val rootfs = File(guest, "rootfs")
        check(rootfs.isDirectory && File(rootfs, if (alpine) "bin/busybox" else "usr/bin/bash").isFile) {
            "Local Ubuntu PC Environment is not installed"
        }
        val lock = File(guest, "BOOTSTRAP_LOCK.txt")
        check(lock.isFile && lock.length() <= 64 * 1024 && lock.readLines().contains(
            if (alpine) "environment=${WorkstationGuestEnvironmentSpec.ID}" else "profile_revision=${FoundryUbuntuGuestEnvironmentSpec.PROFILE_REVISION}"
        )) { "Local Ubuntu PC Environment has not completed activation" }
        val workspace = workDir.canonicalPath
        require(':' !in workspace) { "Workspace path cannot be represented by a PRoot bind" }
        val temp = File(filesDir, "runtime-tmp").apply { mkdirs() }
        check(temp.isDirectory) { "Local runtime temporary directory is unavailable" }
        

        val launcher = SystemLinkerExec.wrap(PackagedLinuxEngine.requireReady(appContext).absolutePath, emptyList())
        val prefix = launcher + listOf(
            "-0", "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "$workspace:$workspace", "-b", "${temp.absolutePath}:/tmp", "-w", workspace,
            "/usr/bin/env", "-i", "HOME=/root", "TMPDIR=/tmp", "LANG=C.UTF-8",
            "TERM=xterm-256color", if (alpine) "SHELL=/bin/sh" else "SHELL=/bin/bash",
            "PATH=/root/.local/bin:/root/.cargo/bin:/root/go/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )
        return TerminalLaunchSpec(
            prefix = prefix,
            shell = if (alpine) "/bin/sh" else "/bin/bash",
            interactiveArgs = if (alpine) emptyList() else listOf("--noprofile", "--norc"),
            environment = mapOf("PROOT_TMP_DIR" to temp.absolutePath, "PROOT_NO_SECCOMP" to "1") +
                PackagedLinuxEngine.loaderEnvironment(appContext),
        )
    }

    suspend fun ensureLinuxReady(workDir: File): TerminalLaunchSpec = withContext(Dispatchers.IO) {
        check(::appContext.isInitialized) { "Local substrate is not initialized" }
        FoundryUbuntuGuestEnvironmentManager(appContext).ensureInstalled()
        linuxLaunchSpec(workDir).also { _state.value = SubstrateState(true, true, true) }
    }

    suspend fun ensureQemuReady(workDir: File): TerminalLaunchSpec = withContext(Dispatchers.IO) {
        WorkstationGuestEnvironmentManager(appContext).ensureQemuInstalled()
        linuxLaunchSpec(workDir, alpine = true)
    }

    suspend fun shellBounded(command: String, maxOutputBytes: Int = 1048576, timeoutMs: Long = 30_000): ExecResult {
        val lease = RemoteProcessLease.createLocal("bootstrap")
        return LocalProcessSupervisor.capture(listOf("/system/bin/sh", "-c", lease.wrap(command)), filesDir,
            maxOutputBytes = maxOutputBytes, timeoutMs = timeoutMs, cleanup = { terminateLease(lease) })
    }

    internal fun terminateLease(lease: RemoteProcessLease) {
        val cleanup = ProcessBuilder("/system/bin/sh", "-c", lease.terminateCommand())
            .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
            .redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
        try { cleanup.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }
        finally { LocalProcessSupervisor.terminate(cleanup) }
    }

    suspend fun shell(command: String): ExecResult = shellBounded(command, timeoutMs = 120_000)

    fun requireContextRoot(expectedRoot: String) {
        check(::filesDir.isInitialized) { "Local execution substrate has not been initialized" }
        check(filesDir.canonicalFile == File(expectedRoot).canonicalFile) { "Local guest belongs to another application root" }
    }

    fun localRoot(): String = filesDir.absolutePath

    suspend fun pushStream(input: InputStream, path: String, mode: Int = 0) = withContext(Dispatchers.IO) {
        requireSafeLocalPath(path)
        val file = File(path)
        file.parentFile?.mkdirs()
        file.outputStream().use { out -> input.copyTo(out) }
        if (mode != 0) {
            val executable = (mode and 0b001_000_000) != 0
            if (executable) file.setExecutable(true, false)
        }
    }

    fun requireSafeLocalPath(path: String) {
        check(::filesDir.isInitialized) { "Local execution substrate has not been initialized" }
        require(path.isNotBlank() && '\u0000' !in path) { "Invalid local storage path" }
        val root = filesDir.canonicalFile.toPath()
        require(File(path).canonicalFile.toPath().startsWith(root)) { "Path escapes local sandbox: $path" }
    }

    fun shellQuote(value: String): String {
        if (value.isEmpty()) return "''"
        if (value.all { it.isLetterOrDigit() || it in "-_.,+/" }) return value
        return "'" + value.replace("'", "'\\''") + "'"
    }

    suspend fun push(source: File, path: String, mode: Int = 0) {
        source.inputStream().use { pushStream(it, path, mode) }
    }
}

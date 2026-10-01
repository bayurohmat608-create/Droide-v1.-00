package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
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
    private val processToken = UUID.randomUUID().toString().replace("-", "")

    internal fun processIdentity(): String = processToken

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

    // Reaps only leases left by an older Droide app process.
    suspend fun reconcileStaleLocalProcesses(): Int = withContext(Dispatchers.IO) {
        check(::filesDir.isInitialized) { "Local execution substrate has not been initialized" }
        val marker = File(filesDir, "run/.app-process")
        val previous = runCatching {
            marker.takeIf { it.isFile && !PathSecurity.isSymbolicLink(it) && it.length() in 1..128 }
                ?.readText(Charsets.UTF_8)?.trim()
        }.getOrNull()
        if (previous == processToken) return@withContext 0
        val runDir = File(filesDir, "run").apply { mkdirs() }
        check(runDir.isDirectory && !PathSecurity.isSymbolicLink(runDir)) { "Local process lease directory is unavailable" }
        val staleCount = runDir.listFiles { file -> file.isFile && file.name.endsWith(".pid") }?.size ?: 0
        if (previous != null || staleCount > 0) {
            val result = LocalProcessSupervisor.capture(
                argv = listOf(if (File("/system/bin/sh").canExecute()) "/system/bin/sh" else "/bin/sh", "-c", RemoteProcessLease.reapAllLocalCommand()),
                cwd = filesDir,
                maxOutputBytes = 16_384,
                timeoutMs = 15_000,
            )
            check(result.exitCode == 0 && !result.timedOut) { "Could not reconcile stale local processes" }
        }
        check(!PathSecurity.isSymbolicLink(marker)) { "Local process marker is unsafe" }
        val temp = File(runDir, ".app-process.${UUID.randomUUID().toString().take(8)}.tmp")
        check(!PathSecurity.isSymbolicLink(temp)) { "Local process marker temp path is unsafe" }
        try {
            java.io.FileOutputStream(temp).use { output ->
                output.write((processToken + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                java.nio.file.Files.move(
                    temp.toPath(), marker.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                java.nio.file.Files.move(temp.toPath(), marker.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
        staleCount
    }

    suspend fun inspectLinuxState(): SubstrateState = withContext(Dispatchers.IO) {
        if (!::appContext.isInitialized) return@withContext SubstrateState()
        val engine = PackagedLinuxEngine.unavailableReason(appContext) == null
        val installed = File(ubuntuGuestRoot(), "rootfs").isDirectory
        val healthy = engine && installed && runSuspendCatching {
            FoundryUbuntuGuestEnvironmentManager(appContext).isHealthy()
        }.getOrDefault(false)
        SubstrateState(healthy, engine, installed).also { _state.value = it }
    }

     
    fun linuxLaunchSpec(workDir: File, alpine: Boolean = false, workspaceRoot: File = workDir, toolchainWorkspaceRoot: File = workspaceRoot): TerminalLaunchSpec {
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
        val workspace = workspaceRoot.canonicalPath
        val cwd = workDir.canonicalPath
        require(PathSecurity.contains(workspaceRoot, workDir) && workDir.isDirectory) { "Linux cwd escaped workspace" }
        require(':' !in workspace) { "Workspace path cannot be represented by a PRoot bind" }
        val temp = File(filesDir, "runtime-tmp").apply { mkdirs() }
        check(temp.isDirectory) { "Local runtime temporary directory is unavailable" }
        val packageRoot = File(filesDir, "packages").apply { mkdirs() }
        val managedUbuntuBin = File(filesDir, "managed/ubuntu-bin").apply { mkdirs() }
        val workspaceToolchains = if (alpine) null else WorkspaceToolchainPreferences(appContext, toolchainWorkspaceRoot)
        val cliBinary = if (alpine) null else DroideCliClientInstaller.ensureInstalled(appContext)
        val cliDiscovery = workspaceToolchains?.let { File(filesDir, "cli/${it.workspaceId}").apply { mkdirs() } }
        if (!alpine) {
            val guestManagedPackages = File(rootfs, "opt/droide/packages").apply { mkdirs() }
            val guestManagedBin = File(rootfs, "opt/droide/bin").apply { mkdirs() }
            val guestCoreBin = File(rootfs, "opt/droide/core-bin").apply { mkdirs() }
            val guestCli = File(rootfs, "opt/droide/cli").apply { mkdirs() }
            check(packageRoot.isDirectory && managedUbuntuBin.isDirectory && cliBinary?.isFile == true && cliDiscovery?.isDirectory == true &&
                guestManagedPackages.isDirectory && guestManagedBin.isDirectory && guestCoreBin.isDirectory && guestCli.isDirectory) {
                "Local managed-package/CLI projection is unavailable"
            }
        }
        

        val launcher = SystemLinkerExec.wrap(PackagedLinuxEngine.requireReady(appContext).absolutePath, emptyList())
        val guestPath = "/root/.local/bin:/root/.cargo/bin:/root/go/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        val managedRecords = if (alpine) emptyList() else ManagedPackageRegistry(appContext).list()
        val workspaceSelections = workspaceToolchains?.selections().orEmpty()
        val managedGuestPaths = if (alpine) emptyList() else LocalLinuxPackageEnvironment.guestBinPaths(
            records = managedRecords,
            workspaceSelections = workspaceSelections,
            appRoot = filesDir.absolutePath,
        )
        val managedGuestEnvironment = if (alpine) emptyMap() else LocalLinuxPackageEnvironment.guestEnvironment(
            records = managedRecords,
            workspaceSelections = workspaceSelections,
            appRoot = filesDir.absolutePath,
        )
        val androidSdkBindings = if (alpine) emptyList() else LocalAndroidSdkComponentEnvironment.bindings(
            records = managedRecords,
            workspaceSelections = workspaceSelections,
            appRoot = filesDir.absolutePath,
        )
        if (!alpine && androidSdkBindings.isNotEmpty()) {
            LocalAndroidSdkComponentEnvironment.prepareGuestTargets(rootfs, androidSdkBindings)
        }
        val injectedGuestEnvironment = managedGuestEnvironment +
            LocalAndroidSdkComponentEnvironment.environment(androidSdkBindings)
        val prefix = buildList {
            addAll(launcher)
            addAll(listOf("-0", "-r", rootfs.absolutePath, "-b", "/dev", "-b", "/proc", "-b", "/sys"))
            addAll(listOf("-b", "$workspace:$workspace", "-b", "${temp.absolutePath}:/tmp"))
            if (!alpine) {
                addAll(listOf("-b", "${packageRoot.absolutePath}:/opt/droide/packages"))
                addAll(listOf("-b", "${managedUbuntuBin.absolutePath}:/opt/droide/bin"))
                addAll(listOf("-b", "${requireNotNull(cliBinary).absolutePath}:/opt/droide/core-bin/droide"))
                addAll(listOf("-b", "${requireNotNull(cliDiscovery).absolutePath}:/opt/droide/cli"))
                androidSdkBindings.forEach { binding ->
                    addAll(listOf("-b", "${binding.hostPath}:${binding.guestPath}"))
                }
            }
            addAll(listOf("-w", cwd, "/usr/bin/env", "-i", "HOME=/root", "TMPDIR=/tmp", "LANG=C.UTF-8"))
            add("TERM=xterm-256color")
            add(if (alpine) "SHELL=/bin/sh" else "SHELL=/bin/bash")
            if (!alpine) injectedGuestEnvironment.toSortedMap().forEach { (key, value) -> add("$key=$value") }
            val managedPrefix = (listOf("/opt/droide/core-bin") + managedGuestPaths + "/opt/droide/bin").distinct().joinToString(":")
            add("PATH=" + if (alpine) guestPath else "$managedPrefix:$guestPath")
        }
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

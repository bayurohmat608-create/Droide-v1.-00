package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import com.flyfishxu.kadb.shell.AdbPtyShellSession
import com.flyfishxu.kadb.shell.AdbShellPacket
import com.flyfishxu.kadb.shell.AdbShellStream
import com.flyfishxu.kadb.stream.AdbStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import okio.Buffer
import okio.ForwardingSource
import okio.source


class DeviceBridgeManager(
    context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
) : Closeable {
    data class State(
        val supported: Boolean = Build.VERSION.SDK_INT >= 30,
        val discovering: Boolean = false,
        val pairEndpoints: List<MdnsEndpoint> = emptyList(),
        val connectEndpoints: List<MdnsEndpoint> = emptyList(),
        val connected: MdnsEndpoint? = null,
        val lastError: String? = null,
    )

    private val appContext = context.applicationContext
    private val bridgePrefs = appContext.getSharedPreferences("droide_device_bridge", Context.MODE_PRIVATE)
    private val mdns = if (Build.VERSION.SDK_INT >= 30) KadbMdnsAndroid(appContext) else null
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var client: Kadb? = null
    private val connectionMutex = Mutex()
    private val shellExecutor = Executors.newCachedThreadPool { task ->
        Thread(task, "Droide-AdbShell").apply { isDaemon = true }
    }

    init {
        

        KadbCert.configure(
            store = EncryptedKadbIdentityStore(appContext),
            policy = KadbCertPolicy(autoHealInvalidPrivateKey = false),
        )
        mdns?.let { discovery ->
            scope.launchCatching {
                discovery.state.collectLatest { s ->
                    _state.value = _state.value.copy(
                        discovering = s.loading,
                        pairEndpoints = s.pairDevices,
                        connectEndpoints = s.connectDevices,
                    )
                }
            }
        }
    }

    fun startDiscovery() {
        if (mdns == null) {
            _state.value = _state.value.copy(lastError = "Wireless Debugging requires Android 11 or newer.")
            return
        }
        _state.value = _state.value.copy(lastError = null)
        mdns.start()
    }

    fun stopDiscovery() { mdns?.stop() }

    suspend fun pair(endpoint: MdnsEndpoint, code: String) = withContext(Dispatchers.IO) {
        require(endpoint.serviceType == MdnsServiceType.TLS_PAIRING) { "Not a pairing endpoint" }
        require(code.matches(Regex("[0-9]{6}"))) { "Pairing code must contain 6 digits" }
        Kadb.pair(endpoint.host, endpoint.port, code, "Droide")
        bridgePrefs.edit().putString(KEY_LAST_PAIRED_HOST, endpoint.host).apply()
    }

    suspend fun connect(endpoint: MdnsEndpoint): String = connectionMutex.withLock {
        connectLocked(endpoint)
    }

    private suspend fun connectLocked(endpoint: MdnsEndpoint): String = withContext(Dispatchers.IO) {
        require(endpoint.serviceType == MdnsServiceType.TLS_CONNECT || endpoint.serviceType == MdnsServiceType.ADB) {
            "Not a connect endpoint"
        }
        val currentEndpoint = _state.value.connected
        val currentClient = client
        if (currentEndpoint != null && currentClient != null) {
            require(currentEndpoint.host == endpoint.host) {
                "Disconnect the current Device Workstation before connecting a different device"
            }
            val healthy = runCatching {
                val probe = currentClient.shell("printf DROIDE_BRIDGE_HEALTH")
                probe.exitCode == 0 && probe.output.contains("DROIDE_BRIDGE_HEALTH")
            }.getOrDefault(false)
            if (healthy) {
                // A healthy transport is authoritative even if mDNS advertises another rotating port.

                mdns?.stop()
                _state.value = _state.value.copy(discovering = false, lastError = null)
                return@withContext "Already connected to ${currentEndpoint.host}:${currentEndpoint.port}"
            }
            runCatching { currentClient.close() }
            client = null
            _state.value = _state.value.copy(connected = null)
        }


        val next = Kadb.create(endpoint.host, endpoint.port, connectTimeout = 8_000, socketTimeout = 0)
        try {
            val probe = next.shell("printf DROIDE_BRIDGE_OK")
            check(probe.exitCode == 0 && probe.output.contains("DROIDE_BRIDGE_OK")) { "Device bridge probe failed" }
            

            val reap = next.shell(RemoteProcessLease.reapAllCommand())
            check(reap.exitCode == 0) { "Could not reconcile stale Device Workstation processes" }
            client = next
            bridgePrefs.edit().putString(KEY_LAST_PAIRED_HOST, endpoint.host).apply()
            mdns?.stop()
            _state.value = _state.value.copy(connected = endpoint, discovering = false, lastError = null)
            "Connected to ${endpoint.host}:${endpoint.port}"
        } catch (t: Throwable) {
            next.close()
            _state.value = _state.value.copy(lastError = t.message)
            throw t
        }
    }

    suspend fun disconnect() = withContext(NonCancellable) {
        connectionMutex.withLock {
            withContext(Dispatchers.IO) {
                client?.let { adb ->
                    
                    runCatching { adb.shell(RemoteProcessLease.reapAllCommand()) }
                    runCatching { adb.close() }
                }
                client = null
                _state.value = _state.value.copy(connected = null)
            }
        }
    }

    // Pairing is never performed here: the user must explicitly pair once from the Device Workstation flow.


    suspend fun ensurePackageBackend(): String = connectionMutex.withLock {
        if (Build.VERSION.SDK_INT < 30) error("Wireless Debugging package backend requires Android 11 or newer")
        if (_state.value.connected != null && client != null) {
            return@withLock ensureHealthyConnectionLocked()
        }
        val rememberedHost = bridgePrefs.getString(KEY_LAST_PAIRED_HOST, null)
        val discovery = mdns ?: error("Wireless Debugging discovery is unavailable")
        discovery.start()
        try {
            var matches = emptyList<MdnsEndpoint>()
            for (attempt in 0 until 30) {
                delay(200)
                val advertised = _state.value.connectEndpoints.distinctBy { it.host to it.port }
                matches = if (rememberedHost.isNullOrBlank()) advertised else advertised.filter { it.host == rememberedHost }
                if (matches.size == 1) break
            }
            require(matches.size == 1) {
                when {
                    matches.isEmpty() && rememberedHost.isNullOrBlank() -> "No Wireless Debugging connect endpoint is advertised. Pair or connect Device Workstation once."
                    matches.isEmpty() -> "Paired Device Workstation is not advertising a connect endpoint"
                    else -> "Multiple Wireless Debugging endpoints are available; connect the intended device manually once"
                }
            }
            // It never performs pairing, so an untrusted/unpaired endpoint simply rejects the connection.

            connectLocked(matches.single())
        } finally {
            discovery.stop()
        }
    }


    suspend fun ensureHealthyConnection(): String = connectionMutex.withLock {
        ensureHealthyConnectionLocked()
    }

    private suspend fun ensureHealthyConnectionLocked(): String = withContext(Dispatchers.IO) {
        val previous = _state.value.connected ?: error("Device bridge is not connected")
        val healthy = runCatching {
            val probe = requireNotNull(client).shell("printf DROIDE_BRIDGE_HEALTH")
            probe.exitCode == 0 && probe.output.contains("DROIDE_BRIDGE_HEALTH")
        }.getOrDefault(false)
        if (healthy) return@withContext "healthy:${previous.host}:${previous.port}"

        runCatching { client?.close() }
        client = null
        _state.value = _state.value.copy(connected = null, lastError = "Reconnecting Wireless Debugging…")
        runCatching { connectLocked(previous) }.onSuccess {
            return@withContext "reconnected:${previous.host}:${previous.port}"
        }

        val discovery = mdns ?: error("Wireless Debugging rediscovery is unavailable on this Android version")
        discovery.start()
        try {
            var matches = emptyList<MdnsEndpoint>()
            repeat(25) {
                delay(200)
                val endpoints = _state.value.connectEndpoints.filter { it.host == previous.host }
                    .distinctBy { it.host to it.port }
                if (endpoints.isNotEmpty()) {
                    matches = endpoints
                    if (endpoints.size == 1) return@repeat
                }
            }
            require(matches.size == 1) {
                if (matches.isEmpty()) "Paired device is not advertising a connect endpoint"
                else "Multiple Wireless Debugging endpoints match the previous device; reconnect manually"
            }
            val replacement = matches.single()
            connectLocked(replacement)
            "rediscovered:${replacement.host}:${replacement.port}"
        } finally {
            discovery.stop()
        }
    }

    // Keep untrusted input and output bounded.


    suspend fun shell(command: String): BridgeShellResult {
        val result = shellBounded(command, DEFAULT_CONTROL_SHELL_OUTPUT_BYTES)
        check(!result.truncated) { "Device shell command exceeded the control-plane output limit" }
        return result
    }

    // Opens raw shell-v2 stdio for trusted IDE protocol hosts such as LSP/DAP.
    fun openRawShell(command: String): AdbShellStream {
        require(command.isNotBlank() && command.length <= 128_000 && '\u0000' !in command) { "Invalid raw shell command" }
        return requireClient().openShell(command)
    }

     
    fun openJdwp(pid: Int): AdbStream {
        require(pid > 0) { "Invalid JDWP pid" }
        return requireClient().open("jdwp:$pid")
    }

     
    fun openJdwpTracker(): AdbStream = requireClient().open("track-jdwp")

     
    suspend fun jdwpPids(timeoutMs: Long = 3_000): List<Int> = withTimeout(timeoutMs.coerceIn(500, 15_000)) {
        suspendCancellableCoroutine { continuation ->
            val streamRef = AtomicReference<AdbStream?>(null)
            val future = shellExecutor.submit {
                try {
                    val stream = openJdwpTracker()
                    streamRef.set(stream)
                    stream.use { tracker ->
                        val header = tracker.source.readUtf8(4)
                        val length = header.toIntOrNull(16) ?: error("Invalid track-jdwp frame length")
                        require(length in 0..64_000) { "track-jdwp frame is too large" }
                        val payload = if (length == 0) "" else tracker.source.readUtf8(length.toLong())
                        val pids = payload.lineSequence()
                            .map(String::trim)
                            .filter(String::isNotEmpty)
                            .mapNotNull(String::toIntOrNull)
                            .filter { it > 0 }
                            .distinct()
                            .take(256)
                            .toList()
                        if (continuation.isActive) continuation.resume(pids)
                    }
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    streamRef.getAndSet(null)?.let { runCatching { it.close() } }
                }
            }
            continuation.invokeOnCancellation {
                streamRef.getAndSet(null)?.let { runCatching { it.close() } }
                future.cancel(true)
            }
        }
    }

    // Callers own and must close the returned session.
    fun openPtyShell(command: String = "", term: String = "xterm-256color"): AdbPtyShellSession {
        require(term.matches(Regex("[A-Za-z0-9._+-]{1,64}"))) { "Invalid terminal type" }
        return requireClient().openPtyShellSession(command = command, term = term)
    }

    // The callback is observational only: callback failures are ignored and never corrupt the build.


    suspend fun shellStreaming(
        command: String,
        maxOutputBytes: Int = 1_500_000,
        onChunk: (text: String, stderr: Boolean) -> Unit,
    ): BridgeShellResult {
        require(maxOutputBytes in 8_192..3_000_000) { "Invalid shell output cap" }
        val adb = requireClient()
        return suspendCancellableCoroutine { continuation ->
            val streamRef = AtomicReference<AdbShellStream?>(null)
            val future = shellExecutor.submit {
                try {
                    val stdout = TailByteBuffer(maxOutputBytes / 2)
                    val stderr = TailByteBuffer(maxOutputBytes / 2)
                    val stream = adb.openShell(command)
                    streamRef.set(stream)
                    var exitCode = 1
                    stream.use { shell ->
                        while (true) {
                            when (val packet = shell.read()) {
                                is AdbShellPacket.StdOut -> {
                                    stdout.write(packet.payload)
                                    runCatching { onChunk(packet.payload.toString(Charsets.UTF_8), false) }
                                }
                                is AdbShellPacket.StdError -> {
                                    stderr.write(packet.payload)
                                    runCatching { onChunk(packet.payload.toString(Charsets.UTF_8), true) }
                                }
                                is AdbShellPacket.Exit -> {
                                    exitCode = packet.payload.firstOrNull()?.toUByte()?.toInt() ?: 1
                                    break
                                }
                            }
                        }
                    }
                    streamRef.set(null)
                    if (continuation.isActive) {
                        continuation.resume(
                            BridgeShellResult(
                                exitCode = exitCode,
                                stdout = stdout.utf8(),
                                stderr = stderr.utf8(),
                                truncated = stdout.truncated || stderr.truncated,
                            )
                        )
                    }
                } catch (error: Throwable) {
                    streamRef.getAndSet(null)?.let { runCatching { it.close() } }
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
            continuation.invokeOnCancellation {
                streamRef.getAndSet(null)?.let { runCatching { it.close() } }
                runCatching { adb.resetConnection() }
                future.cancel(true)
            }
        }
    }


    suspend fun shellBounded(command: String, maxOutputBytes: Int = 1_500_000): BridgeShellResult =
        shellStreaming(command, maxOutputBytes) { _, _ -> }

    suspend fun push(local: File, remotePath: String, mode: Int = 420) = withContext(Dispatchers.IO) {
        requireSafeRemotePath(remotePath)
        val path = local.toPath()
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attrs.isRegularFile && !attrs.isSymbolicLink) { "Local source must be a regular non-symlink file: ${local.path}" }
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val source = input.source()
            requireClient().push(source, remotePath, mode = mode, lastModifiedMs = attrs.lastModifiedTime().toMillis())
        }
    }


    suspend fun pushCancellable(
        local: File,
        remotePath: String,
        mode: Int = 420,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        requireSafeRemotePath(remotePath)
        val path = local.toPath()
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attrs.isRegularFile && !attrs.isSymbolicLink) { "Local source must be a regular non-symlink file: ${local.path}" }
        val total = attrs.size()
        val job = currentCoroutineContext()[Job]
        val adb = requireClient()
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            var copied = 0L
            val guarded = object : ForwardingSource(input.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    job?.ensureActive()
                    val read = super.read(sink, byteCount)
                    if (read > 0L) {
                        copied = Math.addExact(copied, read)
                        try {
                            onProgress(copied, total)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            // Progress observers never get to corrupt the transport.
                        }
                    }
                    return read
                }
            }
            try {
                adb.push(guarded, remotePath, mode = mode, lastModifiedMs = attrs.lastModifiedTime().toMillis())
                job?.ensureActive()
                require(copied == total) { "ADB push size changed while reading: $copied != $total" }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                runCatching { adb.resetConnection() }
                throw cancelled
            } finally {
                guarded.close()
            }
        }
    }

    suspend fun pushStream(
        input: InputStream,
        remotePath: String,
        mode: Int = 420,
        lastModifiedMs: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        requireSafeRemotePath(remotePath)
        // Kadb does not close the Source supplied to push(); ownership remains with the caller.
        val source = input.source()
        requireClient().push(source, remotePath, mode = mode, lastModifiedMs = lastModifiedMs)
    }

    suspend fun pull(remotePath: String, local: File) = withContext(Dispatchers.IO) {
        requireSafeRemotePath(remotePath)
        local.parentFile?.mkdirs()
        requireClient().pull(local, remotePath)
    }

    suspend fun install(apk: File, replace: Boolean = true) = withContext(Dispatchers.IO) {
        require(apk.isFile && apk.extension.equals("apk", true)) { "APK not found: ${apk.path}" }
        if (replace) requireClient().install(apk, "-r") else requireClient().install(apk)
    }

    suspend fun launch(packageName: String, activity: String? = null): BridgeShellResult {
        requirePackageName(packageName)
        val command = if (activity.isNullOrBlank()) {
            "monkey -p ${shellQuote(packageName)} -c android.intent.category.LAUNCHER 1"
        } else {
            require(activity.matches(Regex("[A-Za-z0-9_.$/]+"))) { "Invalid activity" }
            "am start -n ${shellQuote(packageName + "/" + activity)}"
        }
        return shell(command)
    }


    suspend fun launchDebuggable(packageName: String, suspendAtStart: Boolean = true): BridgeShellResult {
        requirePackageName(packageName)
        val resolve = shell(
            "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER " + shellQuote(packageName) + " | tail -1"
        )
        if (resolve.exitCode != 0) return resolve
        val component = resolve.stdout.lineSequence().map(String::trim)
            .lastOrNull { it.contains('/') && it.length <= 512 }
            ?: return BridgeShellResult(1, "", "Launcher activity could not be resolved")
        require(component.matches(Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+"))) { "Unsafe launcher component" }
        val suspendArg = if (suspendAtStart) " --suspend" else ""
        return shell("am start -D$suspendArg -n ${shellQuote(component)}")
    }

    suspend fun forceStop(packageName: String): BridgeShellResult {
        requirePackageName(packageName)
        return shell("am force-stop ${shellQuote(packageName)}")
    }

    suspend fun pidOf(packageName: String): Int? {
        requirePackageName(packageName)
        return shell("pidof ${shellQuote(packageName)}").stdout.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
    }

    suspend fun logcat(packageName: String, maxLines: Int = 1200): BridgeShellResult {
        val pid = pidOf(packageName) ?: return BridgeShellResult(1, "", "App is not running")
        val lines = maxLines.coerceIn(50, 5000)
        return shellBounded("logcat -d --pid=$pid -t $lines", maxOutputBytes = 1_000_000)
    }

    fun forward(localPort: Int, remotePort: Int): Closeable {
        require(localPort in 1024..65535 && remotePort in 1..65535)
        val handle = requireClient().tcpForward(localPort, remotePort)
        return Closeable { handle.close() }
    }

    private fun requireClient(): Kadb = client ?: error("Device bridge is not connected")

    suspend fun resetPairingIdentity() = withContext(NonCancellable) {
        connectionMutex.withLock {
            withContext(Dispatchers.IO) {
                client?.let { adb ->
                    runCatching { adb.shell(RemoteProcessLease.reapAllCommand()) }
                    runCatching { adb.close() }
                }
                client = null
                KadbCert.clear()
                bridgePrefs.edit().remove(KEY_LAST_PAIRED_HOST).apply()
                _state.value = _state.value.copy(connected = null, lastError = null)
            }
        }
    }

    override fun close() {
        runCatching { mdns?.close() }
        runCatching { client?.close() }
        client = null
        shellExecutor.shutdownNow()
    }

    companion object {
        private const val REMOTE_ROOT = "/data/local/tmp/droide"
        private const val DEFAULT_CONTROL_SHELL_OUTPUT_BYTES = 512 * 1024
        private const val KEY_LAST_PAIRED_HOST = "last_paired_host"
        fun remoteRoot(): String = REMOTE_ROOT

        fun requireSafeRemotePath(path: String) {
            require(path == REMOTE_ROOT || path.startsWith("$REMOTE_ROOT/")) { "Remote path must stay inside $REMOTE_ROOT" }
            require(!path.contains("/../") && !path.endsWith("/..") && !path.contains('\u0000')) { "Unsafe remote path" }
        }

        fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
        fun requirePackageName(value: String) {
            require(value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "Invalid package name" }
        }
    }
}

data class BridgeShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val truncated: Boolean = false,
) {
    val combined: String get() = buildString {
        if (truncated) append("[Droide: earlier command output was truncated to protect memory]\n")
        append(stdout)
        if (stderr.isNotBlank()) {
            if (isNotEmpty() && !endsWith("\n")) append('\n')
            append(stderr)
        }
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchCatching(block: suspend () -> Unit) =
    launch { runSuspendCatching { block() } }

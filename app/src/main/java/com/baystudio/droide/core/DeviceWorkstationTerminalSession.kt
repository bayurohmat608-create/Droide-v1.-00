package com.baystudio.droide.core

import com.flyfishxu.kadb.shell.AdbPtyShellSession
import com.flyfishxu.kadb.shell.AdbShellPacket
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

 
interface DevicePtySessionHandle : ITerminalSession, TerminalMetadataSession {
    val terminalEmulator: DeviceTerminalEmulator
    val profileLabel: String
    val status: StateFlow<String>
    fun writeRaw(text: String)
    fun sendKeyCode(keyCode: Int, modifiers: Int = 0)
    fun resize(columns: Int, rows: Int)
    suspend fun refreshWorkspace()
}






class DeviceWorkstationTerminalSession(
    private val bridge: DeviceBridgeManager,
    private val androidDevelopment: AndroidDevelopmentManager,
    private val scope: CoroutineScope,
) : DevicePtySessionHandle {
    private val _output = MutableStateFlow("")
    override val output: StateFlow<String> = _output.asStateFlow()
    override val profileLabel: String = "Device Workstation PTY • xterm-256color"
    private val _status = MutableStateFlow("Disconnected")
    override val status: StateFlow<String> = _status.asStateFlow()

    private val sessionRef = AtomicReference<AdbPtyShellSession?>(null)
    private val configRef = AtomicReference<AndroidDevelopmentManager.InteractiveShellConfig?>(null)
    private val resizeAuthority = TerminalResizeAuthority()
    private data class AppliedResize(
        val session: AdbPtyShellSession,
        val geometry: TerminalResizeAuthority.Geometry,
    )
    private val appliedResizeRef = AtomicReference<AppliedResize?>(null)
    private val writeLock = Any()
    private var readerJob: Job? = null
    private var writerJob: Job? = null
    private var resizeJob: Job? = null
    private val writeChannel = Channel<ByteArray>(Channel.UNLIMITED)

    override val terminalEmulator = resizeAuthority.latest().let { initial ->
        DeviceTerminalEmulator(::enqueuePtyBytes, initial.columns, initial.rows)
    }
    override val terminalTitle: StateFlow<String?> = terminalEmulator.title

    override fun start() {
        if (readerJob?.isActive == true) return
        if (writerJob?.isActive != true) {
            writerJob = scope.launch(Dispatchers.IO) {
                for (bytes in writeChannel) {
                    var pty = sessionRef.get()
                    var attempts = 0
                    while (pty == null && attempts++ < 100) {
                        delay(20)
                        pty = sessionRef.get()
                    }
                    if (pty == null) continue
                    runCatching {
                        synchronized(writeLock) { pty.write(bytes) }
                    }.onFailure { error ->
                        if (sessionRef.get() === pty) _status.value = "Write error · ${error.message ?: error.javaClass.simpleName}"
                    }
                }
            }
        }
        if (resizeJob?.isActive != true) {
            resizeJob = scope.launch(Dispatchers.IO) {
                while (true) {
                    val geometry = resizeAuthority.receive()
                    applyRemoteResize(geometry)
                }
            }
        }
        readerJob = scope.launch(Dispatchers.IO) {
            _status.value = "Preparing Device Workstation…"
            try {
                val config = androidDevelopment.prepareInteractiveShell()
                configRef.set(config)
                val command = config.shellPrefix() + "exec /system/bin/sh -i"
                val session = bridge.openPtyShell(command = command, term = "xterm-256color")
                val initialGeometry = resizeAuthority.latest()
                session.resize(rows = initialGeometry.rows, cols = initialGeometry.columns)
                appliedResizeRef.set(AppliedResize(session, initialGeometry))
                sessionRef.set(session)
                

                resizeAuthority.signalLatest()
                _status.value = "Ready · ${config.remoteWorkspace}"
                session.use { pty ->
                    while (true) {
                        when (val packet = pty.read()) {
                            is AdbShellPacket.StdOut -> appendPtyBytes(packet.payload)
                            is AdbShellPacket.StdError -> appendPtyBytes(packet.payload)
                            is AdbShellPacket.Exit -> {
                                val code = packet.payload.firstOrNull()?.toUByte()?.toInt() ?: -1
                                _status.value = "Process exited · $code"
                                break
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _status.value = "Error · ${error.message ?: error.javaClass.simpleName}"
            } finally {
                val closed = sessionRef.getAndSet(null)
                appliedResizeRef.set(null)
                closed?.let { runCatching { it.close() } }
            }
        }
    }

    override fun send(cmd: String) {
        if (cmd.isBlank()) return
        writeRaw(cmd + "\n")
    }

    override fun writeRaw(text: String) {
        if (text.isEmpty()) return
        enqueuePtyBytes(text.encodeToByteArray())
    }

    override fun sendKeyCode(keyCode: Int, modifiers: Int) {
        terminalEmulator.keySequence(keyCode, modifiers)?.let(::writeRaw)
    }

    override fun resize(columns: Int, rows: Int) {
        val geometry = TerminalResizeAuthority.normalize(columns, rows)
        terminalEmulator.resize(geometry.columns, geometry.rows)
        resizeAuthority.offer(geometry.columns, geometry.rows)
    }

    private fun applyRemoteResize(geometry: TerminalResizeAuthority.Geometry) {
        val pty = sessionRef.get() ?: return
        val applied = appliedResizeRef.get()
        if (applied?.session === pty && applied.geometry == geometry) return
        runCatching {
            synchronized(writeLock) { pty.resize(rows = geometry.rows, cols = geometry.columns) }
        }.onSuccess {
            if (sessionRef.get() === pty) appliedResizeRef.set(AppliedResize(pty, geometry))
        }.onFailure { error ->
            if (sessionRef.get() === pty) {
                _status.value = "Resize error · ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    override suspend fun refreshWorkspace() {
        configRef.set(androidDevelopment.prepareInteractiveShell(syncProject = true))
    }

    override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult =
        execStreaming(cmd, timeoutMs) { }

    override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult {
        val config = ensureConfig()
        val lease = RemoteProcessLease.create("terminal-exec")
        val execute: suspend () -> BridgeShellResult = {
            bridge.shellStreaming(lease.wrap(config.shellPrefix() + cmd), maxOutputBytes = 1_500_000) { text, _ ->
                runCatching { onOutput(text) }
            }
        }
        return try {
            val result = withContext(Dispatchers.IO) {
                if (timeoutMs > 0L) kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { execute() } else execute()
            }
            if (result == null) {
                terminateExecLease(lease)
                ExecResult(-1, "[TIMEOUT ${timeoutMs}ms]", true)
            } else {
                withContext(NonCancellable) {
                    runSuspendCatching { bridge.shellBounded(lease.cleanupCommand(), maxOutputBytes = 8_192) }
                }
                ExecResult(result.exitCode, result.combined.takeLast(20_000), false)
            }
        } catch (cancelled: CancellationException) {
            terminateExecLease(lease)
            throw cancelled
        } catch (error: Throwable) {
            terminateExecLease(lease)
            throw error
        }
    }

    private suspend fun terminateExecLease(lease: RemoteProcessLease) = withContext(NonCancellable + Dispatchers.IO) {
        runSuspendCatching { bridge.ensureHealthyConnection() }
        if (bridge.state.value.connected != null) {
            runSuspendCatching { bridge.shellBounded(lease.terminateCommand(), maxOutputBytes = 16_384) }
        }
    }

    override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        val literal = argv.joinToString(" ") { DeviceBridgeManager.shellQuote(it) }
        return execOnce(literal, timeoutMs)
    }

    override fun interrupt() {
        writeRaw("\u0003")
    }

    override fun clear() {
        terminalEmulator.resetAndClear()
        _output.value = ""
    }

    override fun destroy() {
        readerJob?.cancel()
        readerJob = null
        writerJob?.cancel()
        writerJob = null
        resizeJob?.cancel()
        resizeJob = null
        writeChannel.close()
        resizeAuthority.close()
        configRef.set(null)
        terminalEmulator.setUiListener(null)
        sessionRef.getAndSet(null)?.let { runCatching { it.close() } }
    }

    private suspend fun ensureConfig(): AndroidDevelopmentManager.InteractiveShellConfig {
        configRef.get()?.let { return it }
        return androidDevelopment.prepareInteractiveShell().also(configRef::set)
    }

    private fun enqueuePtyBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        writeChannel.trySend(bytes.copyOf())
    }

    private fun appendPtyBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        terminalEmulator.append(bytes)
        _output.value = terminalEmulator.snapshotText()
    }
}

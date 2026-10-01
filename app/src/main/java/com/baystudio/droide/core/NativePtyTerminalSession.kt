package com.baystudio.droide.core

import java.util.ArrayDeque
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.os.Looper
import com.termux.terminal.TerminalSession as TermuxSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

 
interface NativePtySessionHandle : ITerminalSession, TerminalMetadataSession {
    val nativePtySession: TermuxSession
    fun writeRaw(text: String)
    fun setScreenUpdateListener(listener: (() -> Unit)?)
    fun setBellListener(listener: (() -> Unit)?)
}

internal object NativePtyThreadPolicy {
    fun requireMainThread(isMainThread: Boolean) {
        check(isMainThread) {
            "Native PTY sessions must be constructed on Android's main Looper"
        }
    }
}


class NativePtyTerminalSession(
    private val context: Context,
    private val workDir: File,
    private val scope: CoroutineScope,
    private val launchSpec: TerminalLaunchSpec = TerminalLaunchSpec(),
) : NativePtySessionHandle {
    init {
        NativePtyThreadPolicy.requireMainThread(
            isMainThread = Looper.getMainLooper().thread === Thread.currentThread(),
        )
    }

    private val _output = MutableStateFlow("")
    override val output: StateFlow<String> = _output.asStateFlow()
    private val _terminalTitle = MutableStateFlow<String?>(null)
    override val terminalTitle: StateFlow<String?> = _terminalTitle.asStateFlow()
    private val outputCap = 200_000
    private val execDelegate = TerminalSession(workDir, scope, launchSpec)
    // Native PTYs cannot be wrapped in an extra setsid shell without risking loss of the
    
    private val processLease = RemoteProcessLease.createLocal("native-terminal")
    @Volatile private var screenUpdateListener: (() -> Unit)? = null
    @Volatile private var bellListener: (() -> Unit)? = null
    private val pendingCommandLock = Any()
    private val pendingCommands = ArrayDeque<String>()
    private var pendingCommandChars = 0

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TermuxSession) {
            flushPendingCommands()
            refreshTranscript(changedSession)
            screenUpdateListener?.invoke()
        }
        override fun onTitleChanged(changedSession: TermuxSession) {
            _terminalTitle.value = TerminalTitlePolicy.display(changedSession.title)
            screenUpdateListener?.invoke()
        }
        override fun onSessionFinished(finishedSession: TermuxSession) {
            refreshTranscript(finishedSession)
            val status = runCatching { finishedSession.exitStatus }.getOrDefault(-1)
            appendLine("\n[process exited: $status]")
            scope.launch(NonCancellable + Dispatchers.IO) {
                runCatching { LocalExecutionSubstrate.terminateLease(processLease) }
            }
        }

        override fun onCopyTextToClipboard(session: TermuxSession, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text)) }
        }

        override fun onPasteTextFromClipboard(session: TermuxSession) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val text = runCatching {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
                } else ""
            }.getOrDefault("")
            if (text.isNotEmpty()) session.write(text)
        }

        override fun onBell(session: TermuxSession) { bellListener?.invoke() }
        override fun onColorsChanged(session: TermuxSession) { screenUpdateListener?.invoke() }
        override fun onTerminalCursorStateChange(state: Boolean) { screenUpdateListener?.invoke() }
        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message ?: "terminal error", e) }
    }

    override val nativePtySession: TermuxSession = TermuxSession(
        launchSpec.interactiveArgv.first(),
        workDir.absolutePath,
        launchSpec.interactiveArgv.toTypedArray(),
        buildEnvironment(),
        10_000,
        client,
    )

     
    override fun start() {
        workDir.mkdirs()
        // Debugger-created terminals must start even before a TerminalView is attached.
        if (nativePtySession.emulator == null) nativePtySession.updateSize(80, 24)
        nativePtySession.pid.takeIf { it > 0 }?.let(processLease::recordLocalPid)
        flushPendingCommands()
    }

    override fun send(cmd: String) {
        require(cmd.length <= 64_000 && '\u0000' !in cmd) { "Terminal command is too large or invalid" }
        synchronized(pendingCommandLock) {
            if (nativePtySession.isRunning) {
                nativePtySession.write(cmd + "\n")
                return
            }
            

            check(pendingCommands.size < 32 && pendingCommandChars + cmd.length <= 256_000) {
                "Terminal startup command queue is full"
            }
            pendingCommands.addLast(cmd)
            pendingCommandChars += cmd.length
        }
    }

    override fun writeRaw(text: String) {
        if (nativePtySession.isRunning) nativePtySession.write(text)
    }

    override fun setScreenUpdateListener(listener: (() -> Unit)?) {
        screenUpdateListener = listener
        flushPendingCommands()
    }

    override fun setBellListener(listener: (() -> Unit)?) {
        bellListener = listener
    }

    override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = execDelegate.execOnce(cmd, timeoutMs)
    override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult =
        execDelegate.execStreaming(cmd, timeoutMs, onOutput)
    override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = execDelegate.execArgv(argv, timeoutMs)

     
    override fun interrupt() {
        if (nativePtySession.isRunning) nativePtySession.write(byteArrayOf(0x03), 0, 1)
    }

    override fun clear() {
        val emulator = nativePtySession.emulator
        if (emulator != null) nativePtySession.reset()
        _output.value = ""
    }

    override fun destroy() {
        screenUpdateListener = null
        bellListener = null
        synchronized(pendingCommandLock) {
            pendingCommands.clear()
            pendingCommandChars = 0
        }
        execDelegate.destroy()
        nativePtySession.finishIfRunning()
        scope.launch(NonCancellable + Dispatchers.IO) {
            runCatching { LocalExecutionSubstrate.terminateLease(processLease) }
        }
    }

    private fun flushPendingCommands() {
        synchronized(pendingCommandLock) {
            if (!nativePtySession.isRunning) return
            while (pendingCommands.isNotEmpty()) {
                val command = pendingCommands.removeFirst()
                pendingCommandChars -= command.length
                nativePtySession.write(command + "\n")
            }
            pendingCommandChars = 0
        }
    }

    private fun refreshTranscript(session: TermuxSession) {
        val transcript = session.emulator?.screen?.transcriptText.orEmpty()
        _output.value = transcript.takeLast(outputCap)
    }

    private fun appendLine(text: String) {
        _output.value = (_output.value + text + "\n").takeLast(outputCap)
    }

    private fun buildEnvironment(): Array<String> {
        val home = File(context.filesDir, "terminal-home").apply { mkdirs() }
        val path = System.getenv("PATH")?.takeIf { it.isNotBlank() }
            ?: "/product/bin:/apex/com.android.runtime/bin:/system/bin:/system/xbin:/vendor/bin"
        val base = arrayOf(
            "PATH=$path",
            "HOME=${home.absolutePath}",
            "TMPDIR=${context.cacheDir.absolutePath}",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=en_US.UTF-8",
            "SHELL=/system/bin/sh",
        )
        val leaseEnvironment = processLease.environment
        val overridden = launchSpec.environment.keys + leaseEnvironment.keys
        return base.filterNot { it.substringBefore('=') in overridden }.toTypedArray() +
            launchSpec.environment.map { (key, value) -> "$key=$value" } +
            leaseEnvironment.map { (key, value) -> "$key=$value" }
    }
}

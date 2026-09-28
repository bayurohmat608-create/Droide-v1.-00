package com.baystudio.droide

import android.app.Instrumentation
import android.content.Context
import com.baystudio.droide.core.NativePtyTerminalSession
import com.baystudio.droide.core.EditorRecoverySnapshot
import com.baystudio.droide.core.EditorStateStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git

 
internal object PhysicalDeviceIdeChecks {
    fun verifyEditorRecovery(context: Context, nonceSha256: String): String = runBlocking {
        val store = EditorStateStore(context, "editor-state-${nonceSha256.take(12)}")
        try {
            val buffers = (1..32).associate { "src/file$it.kt" to "unsaved-$it-\uD83D\uDD12\n" }
            store.save(EditorRecoverySnapshot(buffers.keys.toList(), buffers.keys.first(), buffers))
            check(store.load()?.dirtyBuffers == buffers) { "One of 32 dirty buffers was lost" }
            val large = (1..3).associate { "src/large$it.txt" to "$it".repeat(14 * 1024 * 1024) }
            store.save(EditorRecoverySnapshot(large.keys.toList(), large.keys.first(), large))
            check(store.load()?.dirtyBuffers == large) { "Recovery failed above the former 40 MiB limit" }
            "32 dirty buffers and >40 MiB encrypted round trip PASS"
        } finally {
            store.clear()
        }
    }

    internal class PtyProbe(
        val session: NativePtyTerminalSession,
        private val scope: CoroutineScope,
        private val workDir: File,
    ) {
        fun close(instrumentation: Instrumentation) {
            runCatching { instrumentation.runOnMainSync { session.destroy() } }
            scope.cancel()
            workDir.deleteRecursively()
        }
    }

    fun verifyJGitRoundTrip(context: Context, nonceSha256: String): String {
        val root = File(context.cacheDir, "device-jgit-${nonceSha256.take(12)}")
        root.deleteRecursively()
        check(root.mkdirs()) { "Could not create JGit certification directory" }
        try {
            Git.init().setDirectory(root).call().use { git ->
                git.repository.config.apply {
                    setString("user", null, "name", "Droide Certification")
                    setString("user", null, "email", "certification@localhost")
                    save()
                }
                val file = File(root, "roundtrip.txt")
                file.writeText("first-${nonceSha256.take(16)}\n", Charsets.UTF_8)
                git.add().addFilepattern("roundtrip.txt").call()
                val commit = git.commit().setMessage("physical certification").call()
                check(commit.name.isNotBlank()) { "JGit commit id is blank" }
                val latest = git.log().setMaxCount(1).call().firstOrNull()
                check(latest?.name == commit.name) { "JGit log did not return committed revision" }
                check(git.status().call().isClean) { "JGit repository is not clean after commit" }
                file.appendText("second\n", Charsets.UTF_8)
                val modified = git.status().call()
                check("roundtrip.txt" in modified.modified) { "JGit did not detect working-tree modification" }
            }
        } finally {
            root.deleteRecursively()
        }
        return "JGit init/add/commit/log/status round-trip PASS"
    }

    fun startAndVerifyNativePty(
        instrumentation: Instrumentation,
        context: Context,
        nonceSha256: String,
    ): PtyProbe {
        val token = "DROIDE_PTY_${nonceSha256.take(16)}"
        val workDir = File(context.cacheDir, "device-pty-${nonceSha256.take(12)}")
        check(workDir.mkdirs() || workDir.isDirectory) { "Could not create PTY work directory" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val session = NativePtyTerminalSession(context, workDir, scope)
        val probe = PtyProbe(session, scope, workDir)
        try {
            instrumentation.runOnMainSync {
                session.start()
                session.nativePtySession.updateSize(80, 24)
                session.send("stty -echo")
            }
            waitForRunning(session)
            instrumentation.runOnMainSync { session.send("printf '$token\\n'") }
            waitForOutput(session, token, "Native PTY did not return certification token")
            check(session.nativePtySession.isRunning) { "Native PTY process did not stay running" }
            return probe
        } catch (t: Throwable) {
            probe.close(instrumentation)
            throw t
        }
    }

    fun verifyNativePtyUtf8AndResize(
        instrumentation: Instrumentation,
        probe: PtyProbe,
        nonceSha256: String,
    ): String {
        val token = "DROIDE_UTF8_${nonceSha256.take(12)}_✓"
        instrumentation.runOnMainSync {
            probe.session.clear()
            probe.session.nativePtySession.updateSize(91, 31)
            probe.session.send("printf '$token\\n'; stty size")
        }
        waitForOutput(probe.session, token, "Native PTY UTF-8 output was not preserved")
        waitForOutput(probe.session, "31 91", "Native PTY resize was not visible to the shell")
        check(probe.session.nativePtySession.isRunning) { "Native PTY stopped during resize/UTF-8 probe" }
        return "Termux PTY UTF-8 + 91x31 resize PASS"
    }

    private fun waitForRunning(session: NativePtyTerminalSession) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline && !session.nativePtySession.isRunning) {
            Thread.sleep(25)
        }
        check(session.nativePtySession.isRunning) { "Native PTY process did not start" }
    }

    private fun waitForOutput(session: NativePtyTerminalSession, needle: String, message: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline && !session.output.value.contains(needle)) {
            Thread.sleep(25)
        }
        check(session.output.value.contains(needle)) { message }
    }
}

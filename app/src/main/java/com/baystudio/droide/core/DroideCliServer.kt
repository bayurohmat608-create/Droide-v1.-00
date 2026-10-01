package com.baystudio.droide.core

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.system.Os
import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select


object DroideCliServer {
    private var active: Session? = null

    @Synchronized
    fun start(context: Context, authority: UnifiedPackageAuthority, workspaceId: String) {
        require(workspaceId.matches(Regex("[A-Za-z0-9._-]{1,160}"))) { "Invalid CLI workspace identity" }
        if (active?.authority === authority && active?.workspaceId == workspaceId) return
        active?.close()
        active = Session(context.applicationContext, authority, workspaceId).also { it.start() }
    }

    @Synchronized
    fun stop(authority: UnifiedPackageAuthority? = null) {
        val session = active ?: return
        if (authority != null && session.authority !== authority) return
        active = null
        session.close()
    }

    private class Session(
        private val appContext: Context,
        val authority: UnifiedPackageAuthority,
        val workspaceId: String,
    ) {
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val closed = AtomicBoolean(false)
        private val clients = ConcurrentHashMap.newKeySet<LocalSocket>()
        private val discoveryDir = File(appContext.filesDir, "cli/$workspaceId")
        private val endpointFile = File(discoveryDir, "endpoint-v1")
        @Volatile private var server: LocalServerSocket? = null

        fun start() {
            scope.launch {
                try {
                    DroideCliClientInstaller.ensureInstalled(appContext)
                    val socketName = "com.baystudio.droide.cli.${Process.myPid()}.${UUID.randomUUID()}"
                    val listener = LocalServerSocket(socketName)
                    server = listener
                    publishEndpoint(socketName)
                    if (closed.get()) return@launch
                    while (isActive && !closed.get()) {
                        val client = listener.accept()
                        val sameUid = runCatching { client.peerCredentials.uid == Process.myUid() }.getOrDefault(false)
                        if (!sameUid || clients.size >= MAX_CLIENTS || closed.get()) {
                            runCatching { client.close() }
                            continue
                        }
                        clients.add(client)
                        launch { serve(client) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (!closed.get()) Log.w("DroideCli", "Internal CLI endpoint could not start", failure)
                } finally {
                    deleteEndpoint()
                    runCatching { server?.close() }
                }
            }
        }

        private suspend fun serve(client: LocalSocket) {
            try {
                client.soTimeout = REQUEST_TIMEOUT_MS
                val args = DroideCliWireProtocol.readRequest(client.inputStream)
                client.soTimeout = 0 // Package transactions may legitimately take longer than request framing.
                coroutineScope {
                    val operation = async {
                        if (args.firstOrNull() in setOf("pkg", "plugin", "runtime")) {
                            DroideCliTool(authority, workspaceId).executeResult(args)
                        } else {
                            DroideCliExecutionResult(DroideCliExitCode.USAGE, stderr = "Unknown CLI category")
                        }
                    }
                    // After one framed request, any read completion means the client wrote extra data or
                    // disappeared. Cancel the package coroutine so Ctrl-C/disconnect cannot orphan a mutation.
                    val peerState = async(Dispatchers.IO) {
                        runCatching { client.inputStream.read() }.getOrDefault(-1)
                    }
                    val completed = select<DroideCliExecutionResult?> {
                        operation.onAwait { it }
                        peerState.onAwait { null }
                    }
                    if (completed == null) {
                        operation.cancelAndJoin()
                    } else {
                        DroideCliWireProtocol.writeResponse(client.outputStream, completed)
                        // Closing after the complete frame releases the peer monitor and this workspace-bound socket.
                        runCatching { client.close() }
                        runCatching { peerState.await() }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Malformed/disconnected clients are rejected without exposing stack traces over IPC.
            } finally {
                clients.remove(client)
                runCatching { client.close() }
            }
        }

        private fun publishEndpoint(socketName: String) {
            check(discoveryDir.mkdirs() || discoveryDir.isDirectory) { "Could not create CLI discovery directory" }
            requireDiscoveryPath(discoveryDir)
            check(!PathSecurity.isSymbolicLink(discoveryDir)) { "CLI discovery directory may not be a symlink" }
            val temp = File(discoveryDir, ".endpoint-v1-${Process.myPid()}")
            check(PathSecurity.deleteTreeNoFollow(temp)) { "Could not clear stale CLI discovery staging file" }
            val payload = "DROIDE_CLI_ENDPOINT_V1\n$socketName\n$workspaceId\n"
            temp.writeText(payload, Charsets.UTF_8)
            Os.chmod(temp.absolutePath, 0b110_000_000) // 0600, same app UID only.
            check(PathSecurity.deleteTreeNoFollow(endpointFile)) { "Could not replace stale CLI endpoint" }
            check(temp.renameTo(endpointFile)) { "Could not publish CLI endpoint" }
        }

        private fun deleteEndpoint() {
            runCatching {
                requireDiscoveryPath(endpointFile)
                PathSecurity.deleteTreeNoFollow(endpointFile)
            }
        }

        private fun requireDiscoveryPath(file: File) {
            val root = appContext.filesDir.canonicalFile.toPath()
            require(file.canonicalFile.toPath().startsWith(root)) { "CLI discovery path escaped app storage" }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            deleteEndpoint()
            runCatching { server?.close() }
            clients.forEach { runCatching { it.close() } }
            clients.clear()
            scope.cancel()
        }
    }

    private const val MAX_CLIENTS = 8
    private const val REQUEST_TIMEOUT_MS = 10_000
}

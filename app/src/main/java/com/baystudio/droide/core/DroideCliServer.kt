package com.baystudio.droide.core

import java.net.ServerSocket
import java.net.Socket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

object DroideCliServer {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cliTool: DroideCliTool? = null

    fun start(authority: UnifiedPackageAuthority) {
        cliTool = DroideCliTool(authority)
        if (serverSocket != null) return
        
        serverJob = scope.launch {
            try {
                
                serverSocket = ServerSocket(9099, 50, java.net.InetAddress.getByName("127.0.0.1"))
                while (true) {
                    val client = serverSocket?.accept() ?: break
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                
            }
        }
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        client.use { socket ->
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                val writer = PrintWriter(socket.outputStream, true)
                val line = reader.readLine() ?: return@withContext
                
                val args = DroideCli.parse("droide " + line.trim())
                if (args.isEmpty()) {
                    writer.println("Empty command")
                    return@withContext
                }
                
                
                if (args[0] in listOf("pkg", "plugin", "runtime")) {
                    val result = cliTool?.execute(args) ?: "CLI Tool not initialized"
                    writer.println(result)
                } else {
                    writer.println("Unknown CLI category: ${args[0]}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        serverSocket?.close()
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
    }
}

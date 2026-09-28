package com.baystudio.droide.core

import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpManagerTest {
    private lateinit var root: java.io.File

    @Before fun setUp() {
        root = Files.createTempDirectory("droide-mcp-").toFile()
        root.resolve(".droide").mkdirs()
    }

    @After fun tearDown() {
        PathSecurity.deleteTreeNoFollow(root)
    }

    @Test fun approvalFingerprintChangesWhenReferencedServerScriptChanges() {
        root.resolve("server.sh").writeText("#!/bin/sh\necho one\n")
        root.resolve(".droide/mcp.json").writeText(
            """{"servers":{"local":{"command":"mcp-test-command","args":["server.sh"],"protocol":"auto"}}}"""
        )
        val manager = McpManager(root)
        val before = manager.permissionResource("local", "tool")

        root.resolve("server.sh").writeText("#!/bin/sh\necho two\n")
        val after = manager.permissionResource("local", "tool")

        assertNotEquals(before, after)
    }

    @Test fun reusesStdioSessionAcrossManagersAndRestartsOnConfigChange() = runBlocking {
        val host = FakeMcpHost()
        try {
            root.resolve(".droide/mcp.json").writeText(
                """{"servers":{"local":{"command":"fake-mcp","protocol":"legacy-2025"}}}"""
            )
            McpManager(root, processHost = host).listTools("local")
            McpManager(root, processHost = host).callTool("local", "counter")
            assertEquals(1, host.starts.size)
            assertTrue(host.starts[0].isAlive)

            root.resolve(".droide/mcp.json").writeText(
                """{"servers":{"local":{"command":"fake-mcp","args":["revised"],"protocol":"legacy-2025"}}}"""
            )
            McpManager(root, processHost = host).listTools("local")
            assertEquals(2, host.starts.size)
            assertFalse(host.starts[0].isAlive)
            McpManager.shutdownSessions(root, host)
            assertTrue(host.starts.all { !it.isAlive })
            assertEquals(McpHealthState.CONFIGURED, McpHealthRegistry.observe(root).value.first { it.serverName == "local" }.state)
        } finally {
            McpManager.shutdownSessions(root, host)
        }
    }

    @Test fun remoteAndEnvConfigurationShowUnsupportedReason() {
        val config = root.resolve(".droide/mcp.json")
        config.writeText("""{"servers":{"remote":{"url":"https://example.test/mcp"}}}""")
        assertTrue(McpManager(root).list().isEmpty())
        assertTrue(McpHealthRegistry.observe(root).value.any {
            it.workspaceIssue && it.message == "Unsupported MCP configuration" && it.detail.orEmpty().contains("remote HTTP")
        })

        config.writeText("""{"servers":{"local":{"command":"fake-mcp","env":{"TOKEN":"secret"}}}}""")
        assertTrue(McpManager(root).list().isEmpty())
        assertTrue(McpHealthRegistry.observe(root).value.any {
            it.workspaceIssue && it.detail.orEmpty().contains("env") && !it.detail.orEmpty().contains("secret")
        })
    }

    private class FakeMcpHost : StdioProcessHost {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        val starts = mutableListOf<HostedStdioProcess>()
        override suspend fun resolveExecutable(command: String): String = command
        override suspend fun start(
            argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?,
        ): HostedStdioProcess {
            val toServer = PipedOutputStream()
            val serverInput = PipedInputStream(toServer, 64 * 1024)
            val toClient = PipedOutputStream()
            val clientInput = PipedInputStream(toClient, 64 * 1024)
            val alive = AtomicBoolean(true)
            val process = object : HostedStdioProcess {
                override val stdin = toServer
                override val stdout = clientInput
                override val stderr = ByteArrayInputStream(byteArrayOf())
                override val isAlive: Boolean get() = alive.get()
                override val exitCode: Int? get() = if (alive.get()) null else 0
                override suspend fun awaitExit(): Int = 0
                override fun close() {
                    if (alive.compareAndSet(true, false)) {
                        runCatching { toServer.close() }
                        runCatching { serverInput.close() }
                        runCatching { toClient.close() }
                        runCatching { clientInput.close() }
                    }
                }
            }
            starts += process
            Thread({
                try {
                    serverInput.bufferedReader().use { input ->
                        toClient.bufferedWriter().use { output ->
                            while (true) {
                                val line = input.readLine() ?: break
                                val request = Json.parseToJsonElement(line).jsonObject
                                val method = request["method"]?.jsonPrimitive?.content.orEmpty()
                                val id = request["id"] ?: continue
                                val result = when (method) {
                                    "initialize" -> """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"1"}}"""
                                    "tools/list" -> """{"tools":[{"name":"counter","inputSchema":{"type":"object"}}]}"""
                                    "tools/call" -> """{"content":[{"type":"text","text":"ok"}]}"""
                                    else -> "{}"
                                }
                                output.write("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
                                output.newLine()
                                output.flush()
                            }
                        }
                    }
                } catch (_: Exception) {   }
            }, "fake-mcp-test").apply { isDaemon = true; start() }
            return process
        }
    }
}

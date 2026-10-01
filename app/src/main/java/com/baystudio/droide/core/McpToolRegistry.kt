package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive


data class McpAgentTool(
    val functionName: String,
    val serverName: String,
    val toolName: String,
    val description: String,
    val inputSchema: JsonObject,
    val runtimeIdentity: String,
)

class McpToolRegistry(
    private val workDir: File,
    private val plugins: AgentPluginSource = AgentPluginSource.EMPTY,
    private val processHost: StdioProcessHost? = null,
) {
    private val manager = McpManager(workDir, plugins, processHost)

    suspend fun discoverAgentTools(permissions: PermissionEngine, permissionScope: String): List<McpAgentTool> {
        val out = ArrayList<McpAgentTool>()
        var catalogChars = 0
        for (server in manager.list()) {
            if (out.size >= MAX_AGENT_TOOLS) break
            val identity = manager.permissionResource(server.name)
            

            if (permissions.decide("mcp", identity, permissionScope) != PermEffect.ALLOW) {
                val permission = permissions.decide("mcp", identity, permissionScope)
                McpHealthRegistry.permissionDeferred(workDir, server, denied = permission == PermEffect.DENY)
                continue
            }
            val cacheKey = workspaceKey() + "\u0000" + server.name
            val now = System.nanoTime()
            val cached = CACHE[cacheKey]?.takeIf {
                it.runtimeIdentity == identity && now - it.createdAtNanos <= CACHE_TTL_NANOS
            }
            val tools = if (cached != null) {
                cached.tools
            } else {
                val discovered = try {
                    manager.discoverTools(server.name).mapNotNull { raw ->
                        val name = raw["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val schema = raw["inputSchema"] as? JsonObject ?: return@mapNotNull null
                        if (schema.toString().length > MAX_SCHEMA_CHARS) return@mapNotNull null
                        val description = raw["description"]?.jsonPrimitive?.contentOrNull.orEmpty().take(MAX_DESCRIPTION_CHARS)
                        McpAgentTool(
                            functionName = McpToolIdentity.functionName(server.name, name, identity, schema.toString()),
                            serverName = server.name,
                            toolName = name,
                            description = description,
                            inputSchema = schema,
                            runtimeIdentity = identity,
                        )
                    }.sortedWith(compareBy(McpAgentTool::serverName, McpAgentTool::toolName))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                if (discovered != null) putCache(cacheKey, CacheEntry(identity, now, discovered), now)
                discovered.orEmpty()
            }
            for (tool in tools) {
                if (out.size >= MAX_AGENT_TOOLS) break
                val catalogCost = tool.functionName.length + tool.description.length + tool.inputSchema.toString().length
                if (catalogChars + catalogCost > MAX_AGENT_CATALOG_CHARS) continue
                catalogChars += catalogCost
                out += tool
            }
        }
        return out
    }

    suspend fun resolve(functionName: String, permissions: PermissionEngine, permissionScope: String): McpAgentTool? {
        if (!isFirstClassName(functionName)) return null
        return discoverAgentTools(permissions, permissionScope).firstOrNull { it.functionName == functionName }
    }

    private fun workspaceKey(): String = runCatching { workDir.canonicalPath }.getOrElse { workDir.absolutePath }

    private data class CacheEntry(
        val runtimeIdentity: String,
        val createdAtNanos: Long,
        val tools: List<McpAgentTool>,
    )

    companion object {
        private val CACHE = ConcurrentHashMap<String, CacheEntry>()

        private fun putCache(key: String, value: CacheEntry, now: Long) = synchronized(CACHE) {
            CACHE.entries.removeIf { now - it.value.createdAtNanos > CACHE_TTL_NANOS }
            if (CACHE.size >= MAX_CACHE_ENTRIES && !CACHE.containsKey(key)) {
                CACHE.entries.minByOrNull { it.value.createdAtNanos }?.let { CACHE.remove(it.key, it.value) }
            }
            CACHE[key] = value
        }

        private const val CACHE_TTL_NANOS = 2L * 60L * 1_000_000_000L
        private const val MAX_CACHE_ENTRIES = 128
        private const val MAX_AGENT_TOOLS = 96
        private const val MAX_AGENT_CATALOG_CHARS = 256_000
        private const val MAX_SCHEMA_CHARS = 32_000
        private const val MAX_DESCRIPTION_CHARS = 1_024

        fun isFirstClassName(name: String): Boolean = McpToolIdentity.isFirstClassName(name)

         
        fun invalidate(workDir: File, serverName: String? = null) = synchronized(CACHE) {
            val workspace = runCatching { workDir.canonicalPath }.getOrElse { workDir.absolutePath } + "\u0000"
            CACHE.keys.filter { key ->
                key.startsWith(workspace) && (serverName == null || key == workspace + serverName)
            }.forEach(CACHE::remove)
        }

    }
}

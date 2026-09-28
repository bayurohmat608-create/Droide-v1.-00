package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

 
data class DebugStopSnapshot(
    val generation: Long,
    val reason: String,
    val description: String?,
    val threadId: Int?,
    val hitBreakpointIds: List<Int>,
)

 
enum class DebugAdapterTransport {
     
    STDIO,

     
    LOOPBACK_TCP_SERVER,

     
    AUTHENTICATED_REVERSE_TCP,
}

 
data class AuthenticatedReverseTcpContract(
    val connectArgument: String,
    val authTokenArgument: String,
    val authHeaderName: String = "Auth-Token",
) {
    fun validate() {
        require(connectArgument.matches(Regex("--[A-Za-z0-9][A-Za-z0-9-]{0,79}"))) { "Invalid reverse-TCP connect argument" }
        require(authTokenArgument.matches(Regex("--[A-Za-z0-9][A-Za-z0-9-]{0,79}"))) { "Invalid reverse-TCP auth-token argument" }
        require(authHeaderName.matches(Regex("[A-Za-z][A-Za-z0-9-]{0,79}"))) { "Invalid reverse-TCP auth header" }
        require(!authHeaderName.equals("Content-Length", ignoreCase = true)) { "Auth header must not shadow Content-Length" }
    }
}

// Built-in adapters are conservative: Droide never downloads a debugger implicitly.
object DebugAdapterRegistry {
    data class Spec(
        val id: String,
        val extensions: Set<String>,
        val commandCandidates: List<List<String>>,
        val defaultArguments: (String, File) -> JsonObject,
        val source: String = "built-in",
        val transport: DebugAdapterTransport = DebugAdapterTransport.STDIO,
        val reverseTcp: AuthenticatedReverseTcpContract? = null,
        val autoConfigure: Boolean = true,
    ) {
        fun validate() {
            reverseTcp?.validate()
            require((transport == DebugAdapterTransport.AUTHENTICATED_REVERSE_TCP) == (reverseTcp != null)) {
                "Authenticated reverse-TCP transport requires exactly one reviewed reverse-TCP contract"
            }
        }
    }

    val builtIns = listOf(
        Spec(
            id = "python-debugpy",
            extensions = setOf("py", "pyi"),
            commandCandidates = listOf(
                listOf("python3", "-m", "debugpy.adapter"),
                listOf("python", "-m", "debugpy.adapter"),
            ),
            defaultArguments = { file, root -> buildJsonObject {
                put("program", File(root, file).absolutePath)
                put("cwd", root.absolutePath)
                put("justMyCode", true)
                put("console", "internalConsole")
            } },
        ),
        Spec(
            id = "go-delve",
            extensions = setOf("go"),
            commandCandidates = listOf(
                listOf("dlv", "dap", "--listen=127.0.0.1:0"),
            ),
            defaultArguments = { file, root -> buildJsonObject {
                put("mode", "debug")
                put("program", File(root, file).absolutePath)
                put("cwd", root.absolutePath)
                put("stopOnEntry", false)
            } },
            source = "built-in:managed-delve",
            transport = DebugAdapterTransport.LOOPBACK_TCP_SERVER,
        ),
    )

    fun forPath(path: String, extra: List<Spec> = emptyList()): List<Spec> {
        val ext = path.substringAfterLast('.', "").lowercase()
        return (extra + builtIns).distinctBy(Spec::id).filter { it.autoConfigure && ext in it.extensions }
    }

    fun byId(id: String, extra: List<Spec> = emptyList()): Spec? =
        (extra + builtIns).distinctBy(Spec::id).singleOrNull { it.id == id }
}

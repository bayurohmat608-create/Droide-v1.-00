package com.baystudio.droide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext





class DroideCliTool(
    private val authority: UnifiedPackageAuthority
) {

    suspend fun execute(args: List<String>): String = withContext(Dispatchers.IO) {
        if (args.isEmpty()) return@withContext usage()
        val command = args[0]
        
        when (command) {
            "pkg" -> handlePkg(args.drop(1))
            "plugin" -> handlePlugin(args.drop(1))
            "runtime" -> handleRuntime(args.drop(1))
            else -> usage()
        }
    }

    private suspend fun handlePkg(args: List<String>): String {
        if (args.isEmpty()) return "Usage: droide pkg [search|info|install|upgrade|downgrade|use|list|health|repair|uninstall]"
        
        return try {
            when (args[0]) {
                "search" -> {
                    val query = args.getOrNull(1) ?: return "Missing query."
                    val res = authority.search(query)
                    res.joinToString("\n") { "${it.familyId} - ${it.name} [${it.installedVersion ?: "Not installed"}]" }
                }
                "info" -> {
                    val target = args.getOrNull(1) ?: return "Missing package (e.g. python@3.13)."
                    val (family, version) = parseTarget(target)
                    val res = authority.info(family, version) ?: return "Not found"
                    "Family: ${res.familyId}\nVersion: ${res.version}\nState: ${res.state}\nDependencies: ${res.dependencies.joinToString(", ")}"
                }
                "install" -> {
                    val target = args.getOrNull(1) ?: return "Missing package."
                    val (family, version) = parseTarget(target)
                    val res = authority.install(family, version)
                    res.message
                }
                "upgrade" -> {
                    val family = args.getOrNull(1) ?: return "Missing package."
                    val res = authority.upgrade(family)
                    res.message
                }
                "downgrade" -> {
                    val target = args.getOrNull(1) ?: return "Missing package@version."
                    val (family, version) = parseTarget(target)
                    val res = authority.downgrade(family, version)
                    res.message
                }
                "use" -> {
                    val target = args.getOrNull(1) ?: return "Missing package."
                    val (family, version) = parseTarget(target)
                    val res = authority.activate(family, version, args.getOrNull(3)) 
                    res.message
                }
                "list" -> {
                    val res = authority.list()
                    res.joinToString("\n") { "${it.familyId}@${it.version} (${it.state})" }
                }
                "health" -> {
                    val family = args.getOrNull(1) ?: return "Missing package."
                    val res = authority.health(family)
                    "Health for ${res.familyId}: ${if(res.healthy) "OK" else "FAILED"} - ${res.messages.joinToString(", ")}"
                }
                "repair" -> {
                    val family = args.getOrNull(1) ?: return "Missing package."
                    val res = authority.repair(family)
                    res.message
                }
                "uninstall" -> {
                    val target = args.getOrNull(1) ?: return "Missing package."
                    val (family, version) = parseTarget(target)
                    val res = authority.uninstall(family, version)
                    res.message
                }
                else -> "Unknown pkg command."
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
    
    private fun handlePlugin(args: List<String>): String = "Plugin management via CLI not yet fully implemented."
    private fun handleRuntime(args: List<String>): String = "Runtime management via CLI not yet fully implemented."

    private fun parseTarget(target: String): Pair<String, String> {
        val parts = target.split("@", limit = 2)
        return parts[0] to (parts.getOrNull(1) ?: "latest")
    }

    private fun usage(): String = """
        Droide CLI
        Usage: 
          droide pkg [command]
          droide plugin [command]
          droide runtime [command]
    """.trimIndent()
}

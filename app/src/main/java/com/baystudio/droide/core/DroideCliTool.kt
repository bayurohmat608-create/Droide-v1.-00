package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DroideCliTool(
    private val authority: UnifiedPackageAuthority,
    private val boundWorkspaceId: String? = null,
) {
    init {
        require(boundWorkspaceId == null || boundWorkspaceId.matches(Regex("[A-Za-z0-9._-]{1,160}"))) {
            "Invalid bound CLI workspace identity"
        }
    }
    
    suspend fun execute(args: List<String>): String {
        val result = executeResult(args)
        return when {
            result.exitCode == DroideCliExitCode.USAGE && result.stderr.contains("Usage:") -> result.stderr
            result.stderr.isNotBlank() -> "Error: ${result.stderr}"
            else -> result.stdout
        }
    }

    suspend fun executeResult(args: List<String>): DroideCliExecutionResult = withContext(Dispatchers.IO) {
        if (args.isEmpty()) return@withContext usageResult()
        try {
            when (args[0]) {
                "pkg" -> handlePkg(args.drop(1))
                "plugin" -> handlePkg(args.drop(1), familyPrefix = "plugin.", namespace = "plugin")
                "runtime" -> handlePkg(args.drop(1), familyPrefix = "runtime.", namespace = "runtime")
                else -> usageResult()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            failure(DroideCliExitCode.USAGE, error.message ?: "Invalid CLI arguments")
        } catch (error: IllegalStateException) {
            failure(DroideCliExitCode.TRANSACTION_FAILED, error.message ?: "Package operation failed")
        } catch (error: Exception) {
            failure(DroideCliExitCode.INTERNAL, error.message ?: "CLI operation failed")
        }
    }

    private suspend fun handlePkg(
        args: List<String>,
        familyPrefix: String? = null,
        namespace: String = "pkg",
    ): DroideCliExecutionResult {
        if (args.isEmpty()) return usagePkg(namespace)
        val command = args[0]
        val expected = when (command) {
            "list" -> 1
            "search", "info", "install", "upgrade", "downgrade", "health", "repair", "uninstall" -> 2
            "use" -> null
            else -> return failure(DroideCliExitCode.USAGE, "Unknown $namespace command: $command")
        }
        require(expected == null || args.size == expected) { "Unexpected or missing arguments for $namespace $command" }
        if (command in setOf("upgrade", "health")) scopedFamily(args[1], familyPrefix, namespace)
        if (command == "downgrade") {
            require('@' in args[1] && parseTarget(args[1], familyPrefix, namespace).second !in setOf("latest", "Latest official")) {
                "Downgrade requires an exact package@version"
            }
        }
        return when (command) {
            "search" -> {
                val result = authority.search(args[1]).filter { visibleInNamespace(it.familyId, familyPrefix) }
                success(result.joinToString("\n") { "${it.familyId} - ${it.name} [${it.installedVersion ?: "Not installed"}]" })
            }
            "info" -> {
                val (family, version) = parseTarget(args[1], familyPrefix, namespace)
                val result = authority.info(family, version)
                    ?: return failure(DroideCliExitCode.NOT_FOUND, "Package not found: ${args[1]}")
                success("Family: ${result.familyId}\nVersion: ${result.version}\nState: ${result.state}\nDependencies: ${result.dependencies.joinToString(", ")}")
            }
            "install" -> {
                val (family, version) = parseTarget(args[1], familyPrefix, namespace)
                transaction(authority.install(family, version))
            }
            "upgrade" -> transaction(authority.upgrade(scopedFamily(args[1], familyPrefix, namespace)))
            "downgrade" -> {
                val (family, version) = parseTarget(args[1], familyPrefix, namespace)
                transaction(authority.downgrade(family, version))
            }
            "use" -> {
                require(args.size == 2 || args.size == 4 && args[2] == "--workspace" && args[3].isNotBlank()) {
                    "Usage: droide $namespace use family@version [--workspace workspace-id]"
                }
                val (family, version) = parseTarget(args[1], familyPrefix, namespace)
                val explicitWorkspace = args.getOrNull(3)
                require(boundWorkspaceId == null || explicitWorkspace == null || explicitWorkspace == boundWorkspaceId) {
                    "This CLI endpoint is bound to workspace $boundWorkspaceId"
                }
                // A socket published inside a workspace-bound terminal defaults to that workspace.
                // The legacy in-process/unbound CLI keeps null = global for backward compatibility.
                val workspace = explicitWorkspace ?: boundWorkspaceId
                transaction(authority.activate(family, version, workspace))
            }
            "list" -> success(authority.list().filter { visibleInNamespace(it.familyId, familyPrefix) }
                .joinToString("\n") { "${it.familyId}@${it.version} (${it.state})" })
            "health" -> {
                val result = authority.health(scopedFamily(args[1], familyPrefix, namespace))
                val text = "Health for ${result.familyId}: ${if (result.healthy) "OK" else "FAILED"} - ${result.messages.joinToString(", ")}"
                if (result.healthy) success(text) else failure(DroideCliExitCode.UNHEALTHY, text)
            }
            "repair" -> {
                val target = args[1]
                val (family, version) = parseTarget(target, familyPrefix, namespace)
                transaction(if ('@' in target) authority.repairVersion(family, version) else authority.repair(family))
            }
            "uninstall" -> {
                val (family, version) = parseTarget(args[1], familyPrefix, namespace)
                transaction(authority.uninstall(family, version))
            }
            else -> error("unreachable")
        }
    }

    private fun parseTarget(
        target: String,
        familyPrefix: String? = null,
        namespace: String = "pkg",
    ): Pair<String, String> {
        val parts = target.split("@", limit = 2)
        val family = scopedFamily(parts[0], familyPrefix, namespace)
        val version = parts.getOrNull(1) ?: "latest"
        require(version.matches(Regex("[A-Za-z0-9._+ -]{1,80}")) && version.isNotBlank()) { "Invalid package version" }
        return family to version
    }

    private fun scopedFamily(family: String, familyPrefix: String?, namespace: String): String {
        requireFamily(family)
        if (familyPrefix == null) return family
        val normalized = when {
            family.startsWith(familyPrefix) -> family
            '.' !in family -> familyPrefix + family
            else -> throw IllegalArgumentException("$namespace accepts only ${familyPrefix.removeSuffix(".")} package families")
        }
        require(visibleInNamespace(normalized, familyPrefix)) { "$namespace package family is internal or unavailable through this namespace" }
        return normalized
    }

    private fun visibleInNamespace(family: String, familyPrefix: String?): Boolean {
        if (familyPrefix == null) return true
        if (!family.startsWith(familyPrefix)) return false
        return familyPrefix != "runtime." || !family.startsWith("runtime.deps.")
    }

    private fun requireFamily(family: String) {
        require(family.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid package family" }
    }

    private fun transaction(result: UnifiedPackageTransactionResult): DroideCliExecutionResult =
        if (result.success) success(result.message) else failure(DroideCliExitCode.TRANSACTION_FAILED, result.message)

    private fun success(text: String) = DroideCliExecutionResult(DroideCliExitCode.OK, stdout = text)
    private fun failure(code: Int, text: String) = DroideCliExecutionResult(code, stderr = text)
    private fun usageResult() = failure(DroideCliExitCode.USAGE, usage())
    private fun usagePkg(namespace: String = "pkg") = failure(
        DroideCliExitCode.USAGE,
        "Usage: droide $namespace [search|info|install|upgrade|downgrade|use|list|health|repair|uninstall]",
    )

    private fun usage(): String = """
        Droide CLI
        Usage:
          droide pkg [command]
          droide plugin [command]
          droide runtime [command]
    """.trimIndent()
}

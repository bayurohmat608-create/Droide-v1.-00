package com.baystudio.droide.core

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal object LocalUbuntuNpmPolicy {
    const val SEAL = "NPM_TREE_SEAL.json"
    private val packages = mapOf(
        "plugin.opencode" to "opencode-ai",
        "plugin.codex" to "@agentclientprotocol/codex-acp",
        "plugin.claude-code" to "@agentclientprotocol/claude-agent-acp",
        "plugin.gemini-cli" to "@google/gemini-cli",
        "plugin.github-copilot-cli" to "@github/copilot",
    )

    fun supports(recipe: WorkstationInstallRecipe): Boolean = packages[recipe.familyId] == recipe.packageName &&
        WorkstationInstallRecipeCatalog.find(recipe.familyId, recipe.version)?.let {
            it.copy(registryIntegrity = recipe.registryIntegrity) == recipe
        } == true

    // Original CLIs and editor adapters are separate entry points in the same dependency closure.
    fun commands(recipe: WorkstationInstallRecipe): Map<String, List<String>> = when (recipe.familyId) {
        "plugin.codex" -> linkedMapOf("codex-acp" to listOf("codex-acp"), "codex" to listOf("codex"))
        "plugin.claude-code" -> linkedMapOf("claude-agent-acp" to listOf("claude-agent-acp"), "claude" to listOf("claude-agent-acp", "--cli"))
        else -> mapOf(recipe.command to listOf(recipe.command))
    }

    fun requireRegistryTarball(value: String) {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.host == "registry.npmjs.org" && uri.rawUserInfo == null &&
            uri.port in setOf(-1, 443) && uri.rawQuery == null && uri.rawFragment == null && uri.path.endsWith(".tgz")) {
            "npm artifact escaped the official registry"
        }
    }

    fun requireIntegrity(value: String) {
        require(value.matches(Regex("sha512-[A-Za-z0-9+/=]{86,90}"))) { "npm package must publish SHA-512 integrity" }
        require(java.util.Base64.getDecoder().decode(value.removePrefix("sha512-")).size == 64) { "Invalid npm SHA-512 integrity" }
    }

    fun validateLock(text: String, recipe: WorkstationInstallRecipe): JsonObject {
        require(text.length <= 8_000_000) { "npm lockfile is too large" }
        val lock = Json.parseToJsonElement(text).jsonObject
        require(lock["lockfileVersion"]?.jsonPrimitive?.intOrNull in 2..3) { "npm 10+ lockfile required" }
        val entries = lock["packages"]?.jsonObject ?: error("npm dependency lock is missing")
        require(entries.size in 2..10_000) { "npm dependency closure exceeds the supported limit" }
        for ((path, raw) in entries) {
            if (path.isEmpty()) continue
            require(path.startsWith("node_modules/")) { "npm lock contains an external dependency" }
            safeRelative(path)
            val pkg = raw.jsonObject
            require(pkg["link"]?.jsonPrimitive?.booleanOrNull != true) { "npm linked dependency is not allowed" }
            requireRegistryTarball(pkg["resolved"]?.jsonPrimitive?.content ?: error("npm artifact URL is missing"))
            requireIntegrity(pkg["integrity"]?.jsonPrimitive?.content ?: error("npm dependency integrity is missing"))
        }
        val root = entries["node_modules/${recipe.packageName}"]?.jsonObject ?: error("npm root package is missing")
        require(root["version"]?.jsonPrimitive?.content == recipe.packageVersion) { "npm root version mismatch" }
        require(root["integrity"]?.jsonPrimitive?.content == recipe.registryIntegrity) { "npm root integrity mismatch" }
        if (recipe.familyId == "plugin.codex") require(entries.containsKey("node_modules/@openai/codex")) { "Original OpenAI Codex CLI dependency is missing" }
        if (recipe.familyId == "plugin.claude-code") require(entries.containsKey("node_modules/@anthropic-ai/claude-agent-sdk")) { "Original Claude Agent SDK dependency is missing" }
        return lock
    }

    suspend fun tree(root: File): JsonObject {
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "npm package tree is missing" }
        val base = root.canonicalFile.toPath()
        val files = sortedMapOf<String, String>(); val links = sortedMapOf<String, String>()
        var bytes = 0L
        Files.walk(base).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                currentCoroutineContext().ensureActive()
                val path = iterator.next()
                if (path == base) continue
                val rel = base.relativize(path).toString().replace(File.separatorChar, '/')
                safeRelative(rel)
                if (rel == SEAL) continue
                when {
                    Files.isSymbolicLink(path) -> {
                        val target = Files.readSymbolicLink(path)
                        require(!target.isAbsolute && path.parent.resolve(target).normalize().startsWith(base) &&
                            path.toFile().canonicalFile.toPath().startsWith(base)) { "npm symlink escaped package storage" }
                        links[rel] = target.toString()
                    }
                    Files.isDirectory(path, NOFOLLOW_LINKS) -> Unit
                    Files.isRegularFile(path, NOFOLLOW_LINKS) -> {
                        bytes += Files.size(path); require(bytes <= 1_500_000_000L) { "npm package exceeds 1.5 GB" }
                        files[rel] = sha256(path.toFile())
                    }
                    else -> error("Unsupported npm package file type")
                }
                require(files.size + links.size <= 40_000) { "npm package contains too many files" }
            }
        }
        return buildJsonObject {
            put("schema", 1); put("files", JsonObject(files.mapValues { JsonPrimitive(it.value) }))
            put("links", JsonObject(links.mapValues { JsonPrimitive(it.value) }))
        }
    }

    suspend fun verifyTree(root: File, expectedSealHash: String): Boolean {
        val seal = File(root, SEAL)
        if (!seal.isFile || Files.isSymbolicLink(seal.toPath()) || seal.length() > 8_000_000L) return false
        if (sha256(seal) != expectedSealHash) return false
        return Json.parseToJsonElement(seal.readText()).jsonObject == tree(root)
    }

    private fun safeRelative(value: String) {
        require(value.length in 1..4096 && !value.startsWith('/') && '\\' !in value &&
            value.none { it.code < 32 || it.code == 127 } && value.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe npm package member" }
    }

    suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer); if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

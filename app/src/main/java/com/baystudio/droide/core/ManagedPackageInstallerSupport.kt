package com.baystudio.droide.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

 
internal object ManagedPackageInstallerSupport {
    fun validateManifestShape(manifest: ManagedPackageManifest) {
        require(manifest.schema in 1..2) { "Unsupported managed package manifest schema" }
        require(manifest.familyId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid managed package family" }
        require(manifest.version.matches(Regex("[A-Za-z0-9._+ -]{1,80}"))) { "Invalid managed package version" }
        require(manifest.abi in AndroidToolchainCatalogEntry.SUPPORTED_ABIS) { "Unsupported managed package ABI" }
        require(manifest.checksumFile == "SHA256SUMS") { "Unsupported managed package checksum manifest" }
        require(manifest.dependencies.size <= 32 && manifest.dependencies.distinct().size == manifest.dependencies.size) { "Invalid managed package dependencies" }
        require(manifest.dependencies.all { it.matches(Regex("[A-Za-z0-9._-]{1,120}@[A-Za-z0-9._+ -]{1,80}")) }) { "Invalid managed package dependencies" }
        if (manifest.schema == 1) require(manifest.dependencies.isEmpty()) { "Schema-1 managed packages cannot bind dependencies" }
        require(manifest.pathEntries.size <= 32 && manifest.executablePaths.size <= 128 && manifest.healthChecks.size <= 32 && manifest.environment.size <= 32) { "Managed package manifest is too large" }
        require("PATH" !in manifest.environment) { "Managed package PATH must use pathEntries so dependency composition remains deterministic" }
        (manifest.pathEntries + manifest.executablePaths + manifest.healthChecks.map { it.executable }).forEach(::validateRelativePayloadPath)
        manifest.environment.forEach { (key, value) ->
            require(key.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) { "Invalid managed package environment key" }
            require(value.startsWith("${'$'}{PACKAGE_ROOT}")) { "Managed package environment values must be rooted at PACKAGE_ROOT" }
            val suffix = value.removePrefix("${'$'}{PACKAGE_ROOT}").removePrefix("/")
            if (suffix.isNotBlank()) validateRelativePayloadPath(suffix)
        }
    }

    fun resolveTemplate(value: String, installRoot: String): String {
        val suffix = value.removePrefix("${'$'}{PACKAGE_ROOT}").removePrefix("/")
        return if (suffix.isBlank()) "$installRoot/payload" else "$installRoot/payload/$suffix"
    }

    fun readBytesBounded(input: InputStream, maxBytes: Int, label: String): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= maxBytes) { "$label is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun readUtf8Bounded(input: InputStream, maxBytes: Int, label: String): String =
        readBytesBounded(input, maxBytes, label).decodeToString()

    fun validateArchivePath(path: String) {
        require(path.isNotBlank() && path.length <= 400) { "Invalid managed package archive path" }
        val normalized = path.replace('\\', '/')
        require(!normalized.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) { "Managed package archive path must be relative" }
        require(normalized.split('/').filter { it.isNotBlank() }.none { it == "." || it == ".." }) { "Unsafe managed package archive path" }
    }

    fun parseChecksums(text: String, checksumFile: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val match = Regex("^([0-9a-f]{64})\\s+\\*?(.+)$").matchEntire(line.trim()) ?: error("Invalid checksum line")
            val path = match.groupValues[2]
            validateArchivePath(path)
            require(path != checksumFile) { "Checksum manifest must not hash itself" }
            require(result.put(path, match.groupValues[1]) == null) { "Duplicate checksum path" }
        }
        require(result.isNotEmpty()) { "Managed package checksum manifest is empty" }
        return result
    }

    fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(128 * 1024).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validateRelativePayloadPath(path: String) {
        require(path.isNotBlank() && path.length <= 300) { "Invalid managed package relative path" }
        val normalized = path.replace('\\', '/')
        require(!normalized.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) { "Managed package path must be relative" }
        require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Unsafe managed package relative path" }
    }
}

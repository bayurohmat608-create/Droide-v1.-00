package com.baystudio.droide.core

import java.io.File
import java.net.URI

 
class WorkspacePathMapper(localRoot: File, remoteRoot: String) {
    private val local = localRoot.canonicalFile
    val localRoot: File get() = local
    val remoteRoot: String = remoteRoot.trimEnd('/').also { value ->
        require(value.startsWith("/") && value.length <= 4_096) { "Remote workspace root must be absolute" }
        require(!value.contains("/../") && !value.endsWith("/..") && '\u0000' !in value) { "Unsafe remote workspace root" }
    }

    fun localToRemote(file: File): String {
        val canonical = file.canonicalFile
        require(PathSecurity.contains(local, canonical)) { "Local path escaped workspace" }
        val rel = canonical.relativeTo(local).invariantSeparatorsPath
        return if (rel == "." || rel.isBlank()) remoteRoot else "$remoteRoot/$rel"
    }

    fun localRelativeToRemote(relativePath: String): String =
        localToRemote(PathSecurity.resolveWithin(local, relativePath))

    fun remoteToLocal(remotePath: String): File? {
        val normalized = remotePath.replace('\\', '/')
        if (normalized != remoteRoot && !normalized.startsWith("$remoteRoot/")) return null
        if (normalized.contains("/../") || normalized.endsWith("/..") || '\u0000' in normalized) return null
        val rel = normalized.removePrefix(remoteRoot).removePrefix("/")
        return runCatching { if (rel.isBlank()) local else PathSecurity.resolveWithin(local, rel).canonicalFile }.getOrNull()
    }

    fun localUriFor(file: File): String = file.canonicalFile.toURI().toString()

    fun remoteUriFor(file: File): String = fileUri(localToRemote(file))

    fun remoteUriForRelative(relativePath: String): String = fileUri(localRelativeToRemote(relativePath))

    fun uriToLocal(uri: String): File? {
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
        if (parsed.scheme != "file") return null
        val path = parsed.path ?: return null
        remoteToLocal(path)?.let { return it }
        val localFile = runCatching { File(parsed).canonicalFile }.getOrNull() ?: return null
        return localFile.takeIf { PathSecurity.contains(local, it) }
    }

    fun normalizeUriToLocal(uri: String): String? = uriToLocal(uri)?.toURI()?.toString()

    fun toRemoteProtocolString(value: String): String {
        if (value.startsWith("file:")) {
            val file = uriToLocal(value) ?: return value
            return remoteUriFor(file)
        }
        val localPrefix = local.absolutePath.trimEnd(File.separatorChar)
        if (value == localPrefix || value.startsWith(localPrefix + File.separator)) {
            return runCatching { localToRemote(File(value)) }.getOrDefault(value)
        }
        return value
    }

    fun toLocalProtocolString(value: String): String {
        if (value.startsWith("file:")) {
            val file = uriToLocal(value) ?: return value
            return file.toURI().toString()
        }
        remoteToLocal(value)?.let { return it.absolutePath }
        return value
    }

    private fun fileUri(path: String): String = URI("file", "", path, null).toString()
}

package com.baystudio.droide.core

import java.security.MessageDigest

 
object McpToolIdentity {
    const val PREFIX = "mcp__"
    private const val MAX_FUNCTION_NAME_CHARS = 64

    fun isFirstClassName(name: String): Boolean = name.startsWith(PREFIX)

    fun functionName(server: String, tool: String, runtimeIdentity: String, schemaIdentity: String): String {
        val serverSlug = slug(server).take(14)
        val toolSlug = slug(tool).take(24)
        val hash = sha256("$server\u0000$tool\u0000$runtimeIdentity\u0000$schemaIdentity").take(12)
        return "${PREFIX}${serverSlug}__${toolSlug}__${hash}".take(MAX_FUNCTION_NAME_CHARS)
    }

    private fun slug(value: String): String {
        val normalized = buildString(value.length) {
            value.forEach { ch -> append(if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_') }
        }.trim('_')
        return normalized.ifBlank { "tool" }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

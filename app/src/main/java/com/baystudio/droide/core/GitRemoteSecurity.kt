package com.baystudio.droide.core

import java.net.URI

 
object GitRemoteSecurity {
    private val scpLike = Regex("^([A-Za-z0-9._-]+)@([A-Za-z0-9.-]+):([^\\s]+)$")

    fun isAllowed(raw: String): Boolean {
        val value = raw.trim()
        if (value.length !in 1..2_000 || value.any { it == '\n' || it == '\r' || it == '\u0000' }) return false
        if (scpLike.matches(value)) return true
        return runCatching {
            val uri = URI(value)
            val host = uri.host ?: return@runCatching false
            if (host.isBlank()) return@runCatching false
            if (uri.query != null || uri.fragment != null) return@runCatching false
            when {
                uri.scheme.equals("https", true) -> uri.userInfo == null
                uri.scheme.equals("ssh", true) -> uri.userInfo?.contains(':') != true
                else -> false
            }
        }.getOrDefault(false)
    }

    // User names may remain for SSH, secrets/passwords never do.
    fun redact(raw: String): String {
        val value = raw.trim().take(2_000)
        scpLike.matchEntire(value)?.let { m ->
            val host = m.groupValues[2]
            val path = m.groupValues[3]
            return "<user>@$host:$path"
        }
        return runCatching {
            val uri = URI(value)
            val scheme = uri.scheme ?: return@runCatching "(invalid remote)"
            val host = uri.host ?: return@runCatching "(invalid remote)"
            val user = if (scheme.equals("ssh", true) && uri.userInfo != null) "<user>" else null
            URI(scheme, user, host, uri.port, uri.path, null, null).toASCIIString()
        }.getOrDefault("(invalid remote)")
    }
}

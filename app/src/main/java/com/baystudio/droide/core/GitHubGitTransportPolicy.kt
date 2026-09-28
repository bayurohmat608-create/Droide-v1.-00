package com.baystudio.droide.core

import java.net.URI








object GitHubGitTransportPolicy {
    fun isGitHubHttps(raw: String): Boolean = runCatching {
        val uri = URI(raw.trim())
        uri.scheme.equals("https", true) &&
            uri.userInfo == null &&
            uri.host.equals("github.com", true) &&
            uri.port in listOf(-1, 443) &&
            uri.query == null &&
            uri.fragment == null
    }.getOrDefault(false)

    fun mayUseConnectedAccount(urls: Collection<String>): Boolean {
        val normalized = urls.map(String::trim).filter(String::isNotEmpty).distinct()
        return normalized.isNotEmpty() && normalized.all(::isGitHubHttps)
    }
}

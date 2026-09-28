package com.baystudio.droide.core

import java.net.URI

 
object GitClonePolicy {
    fun suggestProjectName(rawUrl: String): String {
        val value = rawUrl.trim().trimEnd('/')
        val tail = if (value.contains("://")) {
            runCatching { URI(value).path.orEmpty().trim('/').substringAfterLast('/', "") }.getOrDefault("")
        } else {
            value.substringAfterLast('/').substringAfterLast(':')
        }.removeSuffix(".git").trim()
        val safe = tail
            .replace(Regex("[^A-Za-z0-9._ -]+"), "-")
            .trim(' ', '.', '-')
            .take(120)
        return safe.ifBlank { "Cloned Project" }
    }
}

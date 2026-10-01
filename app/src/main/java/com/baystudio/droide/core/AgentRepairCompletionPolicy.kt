package com.baystudio.droide.core

import java.net.URI

 
object AgentRepairCompletionPolicy {
    enum class BrowserTargetClass { HTTPS_CANDIDATE, LOOPBACK_HTTP, BLOCKED }

    fun lspMutates(operation: String): Boolean = operation in setOf("apply_action", "format")

    fun toolchainMutates(operation: String): Boolean = operation in setOf("install", "repair", "use_workspace")

    fun browserInteracts(operation: String): Boolean = operation in setOf("click", "type")

    fun classifyBrowserTarget(raw: String): BrowserTargetClass = runCatching {
        val uri = URI(raw)
        val host = uri.host ?: return@runCatching BrowserTargetClass.BLOCKED
        if (uri.userInfo != null) return@runCatching BrowserTargetClass.BLOCKED
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (isLiteralLoopback(host)) {
            if (scheme == "http" || scheme == "https") BrowserTargetClass.LOOPBACK_HTTP
            else BrowserTargetClass.BLOCKED
        } else if (scheme == "https") BrowserTargetClass.HTTPS_CANDIDATE
        else BrowserTargetClass.BLOCKED
    }.getOrDefault(BrowserTargetClass.BLOCKED)

    fun isLiteralLoopback(host: String?): Boolean = host != null && (
        host.equals("localhost", true) || host == "127.0.0.1" || host == "::1" || host == "[::1]"
    )
}

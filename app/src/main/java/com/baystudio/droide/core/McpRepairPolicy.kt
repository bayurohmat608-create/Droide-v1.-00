package com.baystudio.droide.core

 
internal object McpRepairPolicy {
    val patchKeys: Set<String> = setOf("command", "args", "protocol")

    fun normalizeProtocol(value: String): String = when (value.trim().lowercase()) {
        "auto" -> "auto"
        "2026-07-28", "modern", "modern-2026" -> "2026-07-28"
        "2025-11-25", "legacy", "legacy-2025" -> "2025-11-25"
        else -> error("Unsupported MCP protocol preference")
    }

    fun safeCommand(command: String): Boolean = command.isNotBlank() && command.length <= 512 &&
        command.none { it == '\u0000' || it == '\n' || it == '\r' || it in " ;&|`$<>" }

    fun validArgument(arg: String): Boolean = arg.length <= 4_096 && arg.none { it == '\u0000' || it == '\n' || it == '\r' }

    fun redactArgument(value: String): String {
        val text = value.trim()
        if (Regex("(?i)(token|secret|password|api[_-]?key|authorization|bearer)").containsMatchIn(text)) return "<sensitive-arg>"
        return if (text.length <= 120) text else text.take(117) + "..."
    }

    fun repairPermissionResource(server: String, beforeSha256: String, afterSha256: String): String =
        "$server@repair:before:${beforeSha256.take(16)}:after:${afterSha256.take(16)}"

    fun rollbackPermissionResource(server: String, repairId: String, currentSha256: String, restoreSha256: String): String =
        "$server@rollback:$repairId:current:${currentSha256.take(16)}:restore:${restoreSha256.take(16)}"
}

package com.baystudio.droide.core

import java.io.File


internal data class GradleProjectPerformanceHints(
    val buildCacheEnabled: Boolean? = null,
    val daemonEnabled: Boolean? = null,
    val maxWorkers: Int? = null,
    val daemonIdleMillis: Long? = null,
    // low keeps editor/input responsive; an explicit normal is still project-authoritative.
    val priority: String? = null,
) {
    companion object {
        fun read(projectRoot: File): GradleProjectPerformanceHints {
            val file = File(projectRoot, "gradle.properties")
            if (!file.isFile || PathSecurity.isSymbolicLink(file) || file.length() !in 1..512_000) return GradleProjectPerformanceHints()
            val values = linkedMapOf<String, String>()
            runCatching {
                file.useLines { lines ->
                    lines.take(4_000).forEach { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith('#') || line.startsWith('!')) return@forEach
                        val split = line.indexOfAny(charArrayOf('=', ':'))
                        if (split <= 0) return@forEach
                        values[line.substring(0, split).trim()] = line.substring(split + 1).trim()
                    }
                }
            }.getOrElse { return GradleProjectPerformanceHints() }
            return GradleProjectPerformanceHints(
                buildCacheEnabled = values["org.gradle.caching"]?.toBooleanStrictOrNull(),
                daemonEnabled = values["org.gradle.daemon"]?.toBooleanStrictOrNull(),
                maxWorkers = values["org.gradle.workers.max"]?.toIntOrNull()?.takeIf { it in 1..64 },
                daemonIdleMillis = values["org.gradle.daemon.idletimeout"]?.toLongOrNull()?.takeIf { it in 30_000L..24 * 60 * 60_000L },
                priority = values["org.gradle.priority"]?.lowercase()?.takeIf { it == "low" || it == "normal" },
            )
        }
    }
}

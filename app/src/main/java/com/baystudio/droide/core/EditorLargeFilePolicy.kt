package com.baystudio.droide.core









enum class EditorFilePerformanceMode { FULL_INTELLIGENCE, LARGE_FILE_OPTIMIZED, PREVIEW_ONLY }

data class EditorFileProfile(
    val mode: EditorFilePerformanceMode,
    val bytes: Long,
    val lineCount: Int,
) {
    val editable: Boolean get() = mode != EditorFilePerformanceMode.PREVIEW_ONLY
    val fullIntelligence: Boolean get() = mode == EditorFilePerformanceMode.FULL_INTELLIGENCE
}

object EditorLargeFilePolicy {
     
    const val FULL_INTELLIGENCE_MAX_BYTES: Long = 2_500L * 1024L

     
    const val MAX_EDITABLE_BYTES: Long = 20L * 1024L * 1024L

     
    const val FULL_INTELLIGENCE_MAX_LINES: Int = 300_000

    const val PREVIEW_LINES: Int = 500

    fun classify(bytes: Long, lineCount: Int): EditorFileProfile {
        require(bytes >= 0L) { "bytes must be non-negative" }
        require(lineCount >= 0) { "lineCount must be non-negative" }
        val mode = when {
            bytes > MAX_EDITABLE_BYTES -> EditorFilePerformanceMode.PREVIEW_ONLY
            bytes > FULL_INTELLIGENCE_MAX_BYTES || lineCount > FULL_INTELLIGENCE_MAX_LINES ->
                EditorFilePerformanceMode.LARGE_FILE_OPTIMIZED
            else -> EditorFilePerformanceMode.FULL_INTELLIGENCE
        }
        return EditorFileProfile(mode, bytes, lineCount)
    }

     
    fun lineCount(text: CharSequence, stopAfter: Int = Int.MAX_VALUE): Int {
        if (text.isEmpty()) return 1
        val cap = stopAfter.coerceAtLeast(1)
        var lines = 1
        for (i in 0 until text.length) {
            if (text[i] == '\n' && ++lines >= cap) return lines
        }
        return lines
    }

     
    fun utf8Bytes(text: CharSequence): Long {
        var bytes = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.code <= 0x7f -> bytes += 1
                c.code <= 0x7ff -> bytes += 2
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    bytes += 4
                    i++
                }
                else -> bytes += 3
            }
            i++
        }
        return bytes
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

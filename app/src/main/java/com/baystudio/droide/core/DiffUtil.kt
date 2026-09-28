package com.baystudio.droide.core

 
object DiffUtil {
    fun unified(old: String, new: String, context: Int = 3, maxChangedLines: Int = 800): String {
        if (old == new) return "(no changes)"
        val a = old.lines()
        val b = new.lines()

        var prefix = 0
        val commonMax = minOf(a.size, b.size)
        while (prefix < commonMax && a[prefix] == b[prefix]) prefix++

        var suffix = 0
        while (
            suffix < a.size - prefix && suffix < b.size - prefix &&
            a[a.lastIndex - suffix] == b[b.lastIndex - suffix]
        ) suffix++

        val oldStart = (prefix - context).coerceAtLeast(0)
        val newStart = oldStart
        val oldChangedEnd = a.size - suffix
        val newChangedEnd = b.size - suffix
        val oldEnd = (oldChangedEnd + context).coerceAtMost(a.size)
        val newEnd = (newChangedEnd + context).coerceAtMost(b.size)

        return buildString {
            appendLine("@@ old ${oldStart + 1}-${oldEnd} | new ${newStart + 1}-${newEnd} @@")
            for (i in oldStart until prefix) appendLine(" ${a[i]}")

            val removed = a.subList(prefix, oldChangedEnd)
            val added = b.subList(prefix, newChangedEnd)
            removed.take(maxChangedLines).forEach { appendLine("-$it") }
            if (removed.size > maxChangedLines) appendLine("-… ${removed.size - maxChangedLines} more removed lines …")
            added.take(maxChangedLines).forEach { appendLine("+$it") }
            if (added.size > maxChangedLines) appendLine("+… ${added.size - maxChangedLines} more added lines …")

            val suffixStart = a.size - suffix
            for (i in suffixStart until minOf(suffixStart + context, a.size)) appendLine(" ${a[i]}")
        }.take(16_000)
    }
}

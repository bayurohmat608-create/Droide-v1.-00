package com.baystudio.droide.core

 
internal object GradleTaskPath {
    data class Parsed(
        val raw: String,
        val modulePath: String?,
        val taskName: String,
    )

    private val segment = Regex("[A-Za-z0-9_.][A-Za-z0-9_.-]{0,79}")

    fun parse(task: String): Parsed {
        val raw = task.trim()
        require(raw.length in 1..120) { "Invalid Gradle task" }
        require(!raw.startsWith('-')) { "Gradle CLI options are not task names" }
        val body = raw.removePrefix(":")
        val parts = body.split(':')
        require(parts.isNotEmpty() && parts.all { it.matches(segment) && it != "." && it != ".." }) {
            "Invalid Gradle task path"
        }
        return Parsed(
            raw = raw,
            modulePath = parts.dropLast(1).takeIf(List<String>::isNotEmpty)?.joinToString("/"),
            taskName = parts.last(),
        )
    }
}

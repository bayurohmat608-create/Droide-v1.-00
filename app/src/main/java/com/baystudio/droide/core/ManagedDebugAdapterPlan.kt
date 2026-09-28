package com.baystudio.droide.core






data class ManagedDebugAdapterPlanEntry(
    val adapterId: String,
    val packageFamilyId: String,
    val pinnedVersion: String,
    val command: String,
    val requiredCommands: Set<String>,
    val notes: String,
)

object ManagedDebugAdapterPlan {
    val priority = listOf(
        ManagedDebugAdapterPlanEntry(
            adapterId = "kotlin-jdwp",
            packageFamilyId = "debug.kotlin-debug-adapter",
            pinnedVersion = "0.4.4",
            command = "kotlin-debug-adapter",
            requiredCommands = setOf("java"),
            notes = "Standalone Kotlin/JVM DAP/JDI adapter. Promotion requires an exact packaged distribution, pinned checksums/provenance, and physical ART/JDWP certification.",
        ),
    )

    fun validate() {
        require(priority.isNotEmpty()) { "Managed debug-adapter plan is empty" }
        require(priority.map { it.adapterId }.distinct().size == priority.size) { "Duplicate managed debug-adapter id" }
        require(priority.map { it.packageFamilyId }.distinct().size == priority.size) { "Duplicate managed debug-adapter package family" }
        priority.forEach { entry ->
            require(entry.adapterId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid managed debug-adapter id" }
            require(entry.packageFamilyId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid managed debug-adapter package family" }
            require(entry.pinnedVersion.matches(Regex("[A-Za-z0-9._+ -]{1,80}"))) { "Invalid managed debug-adapter version" }
            require(entry.command.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid managed debug-adapter command" }
            require(entry.requiredCommands.size <= 16 && entry.requiredCommands.all { it.matches(Regex("[A-Za-z0-9._+-]{1,80}")) }) {
                "Invalid managed debug-adapter runtime dependency"
            }
        }
    }

    fun forAdapter(id: String): ManagedDebugAdapterPlanEntry? = priority.firstOrNull { it.adapterId == id }
}

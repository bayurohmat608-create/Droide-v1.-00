package com.baystudio.droide.core








enum class LanguageServerRuntimeKind {
    ANDROID_NATIVE,
    LINUX_ARM64_PROOT,
    JVM,
    NODE,
}

data class ManagedRuntimeRequirement(
    val command: String,
    val versionArgs: List<String>,
    val minimumMajor: Int,
    val minimumMinor: Int = 0,
    val minimumPatch: Int = 0,
) {
    fun validate() {
        require(command.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid runtime command" }
        require(versionArgs.isNotEmpty() && versionArgs.size <= 4) { "Invalid runtime version arguments" }
        require(versionArgs.all { it.matches(Regex("[-A-Za-z0-9._+=]{1,80}")) }) { "Invalid runtime version argument" }
        require(minimumMajor in 1..999 && minimumMinor in 0..999 && minimumPatch in 0..999) { "Invalid minimum runtime version" }
    }

    val minimumVersion: String get() = "$minimumMajor.$minimumMinor.$minimumPatch"
}

data class ManagedLanguageServerPlanEntry(
    val languageServerId: String,
    val packageFamilyId: String,
    val pinnedVersion: String,
    val command: String,
    val runtime: LanguageServerRuntimeKind,
    val requiredCommands: Set<String> = emptySet(),
    val runtimeRequirements: List<ManagedRuntimeRequirement> = emptyList(),
    val notes: String,
)

object ManagedLanguageServerPlan {
    val priority = listOf(
        ManagedLanguageServerPlanEntry(
            languageServerId = "python-pyright",
            packageFamilyId = "lsp.python-pyright",
            pinnedVersion = "1.1.414",
            command = "pyright-langserver",
            runtime = LanguageServerRuntimeKind.NODE,
            requiredCommands = setOf("node"),
            runtimeRequirements = listOf(ManagedRuntimeRequirement("node", listOf("--version"), 14)),
            notes = "Provision from an exact pinned Pyright distribution plus a certified ARM64 Node runtime (upstream engine minimum Node 14).",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "python-pylsp",
            packageFamilyId = "lsp.python-pylsp",
            pinnedVersion = "1.15.0",
            command = "pylsp",
            runtime = LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            requiredCommands = setOf("python3"),
            runtimeRequirements = listOf(ManagedRuntimeRequirement("python3", listOf("--version"), 3, 9)),
            notes = "Provision from pinned Python wheels in the compatibility userspace; python-lsp-server 1.15.0 requires Python 3.9+ and no unbounded pip install is allowed at runtime.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "typescript",
            packageFamilyId = "lsp.typescript-language-server",
            pinnedVersion = "5.3.0",
            command = "typescript-language-server",
            runtime = LanguageServerRuntimeKind.NODE,
            requiredCommands = setOf("node"),
            runtimeRequirements = listOf(ManagedRuntimeRequirement("node", listOf("--version"), 22, 22, 2)),
            notes = "Provision a pinned TypeScript Language Server bundle plus a certified ARM64 Node runtime; v5.3.0 declares Node >=22.22.2.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "clangd",
            packageFamilyId = "lsp.clangd",
            pinnedVersion = "23.1.1",
            command = "clangd",
            runtime = LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            notes = "Use an exact ARM64 clangd artifact in the compatibility userspace; never execute an x86 host binary on-device.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "gopls",
            packageFamilyId = "lsp.gopls",
            pinnedVersion = "0.23.0",
            command = "gopls",
            runtime = LanguageServerRuntimeKind.ANDROID_NATIVE,
            notes = "Preferred path is a reproducibly built CGO-disabled android/arm64 binary with source/module hashes pinned.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "rust-analyzer",
            packageFamilyId = "lsp.rust-analyzer",
            pinnedVersion = "20260914",
            command = "rust-analyzer",
            runtime = LanguageServerRuntimeKind.ANDROID_NATIVE,
            notes = "Bootstrap from the exact hash-pinned Termux aarch64 package built from upstream rust-analyzer 2026-09-14; Droide extracts only the reviewed executable and never runs Debian maintainer scripts.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "kotlin",
            packageFamilyId = "lsp.kotlin-language-server",
            pinnedVersion = "1.3.13",
            command = "kotlin-language-server",
            runtime = LanguageServerRuntimeKind.JVM,
            requiredCommands = setOf("java"),
            notes = "Use a pinned JVM distribution and launch it with the workstation JDK, keeping the Java process in the certified execution environment.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "jdtls",
            packageFamilyId = "lsp.jdtls",
            pinnedVersion = "1.61.0",
            command = "jdtls",
            runtime = LanguageServerRuntimeKind.JVM,
            requiredCommands = setOf("java"),
            runtimeRequirements = listOf(ManagedRuntimeRequirement("java", listOf("-version"), 21)),
            notes = "Use Eclipse JDT LS 1.61.0 with a dedicated certified Java 21+ runtime. Keep the dedicated language-server runtime separate from the project-selected Android/Gradle JDK; do not replace a build runtime just to satisfy JDT LS.",
        ),
    )

    fun validate(registry: List<LanguageServerSpec> = LanguageServerRegistry.builtIns) {
        require(priority.isNotEmpty()) { "Managed LSP plan is empty" }
        require(priority.map { it.languageServerId }.distinct().size == priority.size) { "Duplicate managed LSP id" }
        require(priority.map { it.packageFamilyId }.distinct().size == priority.size) { "Duplicate managed LSP package family" }
        require(priority.all { it.packageFamilyId.matches(Regex("[A-Za-z0-9._-]{1,120}")) }) { "Invalid managed LSP package family" }
        require(priority.all { it.command.matches(Regex("[A-Za-z0-9._+-]{1,80}")) }) { "Invalid managed LSP command" }
        priority.forEach { entry ->
            require(entry.requiredCommands.all { it.matches(Regex("[A-Za-z0-9._+-]{1,80}")) }) { "Invalid required runtime command" }
            entry.runtimeRequirements.forEach(ManagedRuntimeRequirement::validate)
            require(entry.runtimeRequirements.map { it.command }.toSet().all { it in entry.requiredCommands }) {
                "Runtime version checks must reference declared required commands"
            }
        }
        val known = registry.map { it.id }.toSet()
        val missing = priority.map { it.languageServerId }.filterNot { it in known }
        require(missing.isEmpty()) { "Managed LSP plan references unknown registry ids: $missing" }
    }

    fun forServer(id: String): ManagedLanguageServerPlanEntry? = priority.firstOrNull { it.languageServerId == id }
}

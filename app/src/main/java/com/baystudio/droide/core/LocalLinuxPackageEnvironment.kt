package com.baystudio.droide.core


object LocalLinuxPackageEnvironment {
    private val supportedKinds = setOf(
        LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW,
        LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE,
        LocalManagedPackageMetadata.KIND_UBUNTU_NPM,
        LocalManagedPackageMetadata.KIND_ANDROID_SDK_COMPONENT,
    )

    fun supportsWorkspaceSelection(record: ManagedPackageRecord, appRoot: String): Boolean =
        record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name &&
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] in supportedKinds &&
            guestPath(record.installRoot, "$appRoot/packages/") != null

    fun selectedRecords(
        records: List<ManagedPackageRecord>,
        workspaceSelections: Map<String, String>,
        appRoot: String,
    ): List<ManagedPackageRecord> {
        val eligible = records.filter { supportsWorkspaceSelection(it, appRoot) }
        return eligible.groupBy { it.familyId }
            .toSortedMap()
            .values
            .mapNotNull { familyRecords ->
                val family = familyRecords.first().familyId
                val selected = workspaceSelections[family]
                familyRecords.firstOrNull { selected != null && it.version == selected }
                    ?: familyRecords.filter { it.active }.singleOrNull()
            }
            .take(128)
    }

    fun guestBinPaths(
        records: List<ManagedPackageRecord>,
        workspaceSelections: Map<String, String>,
        appRoot: String,
    ): List<String> = selectedRecords(records, workspaceSelections, appRoot)
        .filter { it.commands.isNotEmpty() }
        .flatMap { record ->
            val wrapper = guestPath(record.installRoot, "$appRoot/packages/")?.let { "$it/payload/bin" }
            val home = record.environment["JAVA_HOME"]?.takeIf { it.startsWith("${record.installRoot}/") }
                ?.let { guestPath(it, "$appRoot/packages/") }
            listOfNotNull(home?.takeIf { record.familyId == LocalUbuntuJdkPolicy.FAMILY }?.let { "$it/bin" }, wrapper)
        }
        .distinct()
        .take(64)

    
    fun guestEnvironment(
        records: List<ManagedPackageRecord>,
        workspaceSelections: Map<String, String>,
        appRoot: String,
    ): Map<String, String> {
        val packageRoot = "$appRoot/packages/"
        val out = linkedMapOf<String, String>()
        selectedRecords(records, workspaceSelections, appRoot).forEach { record ->
            record.environment.toSortedMap().forEach environment@{ (key, value) ->
                if (key == "PATH") return@environment
                if (!value.startsWith("${record.installRoot}/")) return@environment
                val guest = guestPath(value, packageRoot) ?: return@environment
                out[key] = guest
            }
        }
        return out.toMap()
    }

    private fun guestPath(hostPath: String, packageRoot: String): String? {
        if (!hostPath.startsWith(packageRoot)) return null
        val relative = hostPath.removePrefix(packageRoot)
        if (relative.isBlank() || relative.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
        return "/opt/droide/packages/$relative"
    }
}

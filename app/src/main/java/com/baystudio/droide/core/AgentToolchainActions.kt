package com.baystudio.droide.core

import java.security.MessageDigest

// Installation/repair is possible only for entries that the normal Extensions subsystem already marks AVAILABLE/INSTALLED.


class AgentToolchainActions(
    private val extensions: DevelopmentExtensionsManager,
) {
    data class PreparedInstall(
        val item: ExtensionVersionState,
        val plan: ExtensionInstallPlan,
        val preview: String,
        val permissionResource: String,
        val needsLicenseAcceptance: Boolean,
    )

    suspend fun list(query: String? = null): String {
        val result = extensions.unifiedAuthority.list()
        val matching = query?.let { q -> result.filter { it.familyId.contains(q, ignoreCase = true) } } ?: result
        return buildString {
            append("UNIFIED_PACKAGE_AUTHORITY_CATALOG\n")
            matching.forEach { item ->
                append(item.familyId).append('@').append(item.version)
                    .append(" · ").append(item.state)
                    .append('\n')
            }
            if (matching.isEmpty()) append("No matching packages found.\n")
        }
    }

    suspend fun status(familyId: String, version: String? = null): String {
        val info = extensions.unifiedAuthority.info(familyId, version ?: "latest")
        if (info == null) return "PACKAGE_NOT_FOUND family=$familyId version=$version"
        return buildString {
            append("PACKAGE_STATUS family=").append(info.familyId).append('\n')
            append("version=").append(info.version).append('\n')
            append("state=").append(info.state).append('\n')
            append("dependencies=").append(info.dependencies.joinToString(",")).append('\n')
        }
    }

    suspend fun prepareInstall(familyId: String, version: String): PreparedInstall {
        val item = find(familyId, version)
        require(item.state == ExtensionState.AVAILABLE) {
            "${item.family.name} ${item.version.version} is ${item.state.name.lowercase()}, not installable: ${item.detail}"
        }
        val plan = extensions.installer.prepare(item)
        val needsLicense = when (plan) {
            is ExtensionInstallPlan.Android -> !plan.licenseAccepted
            is ExtensionInstallPlan.AndroidLocalComponent -> !plan.licenseAccepted
            else -> false
        }
        val preview = buildString {
            appendLine("Install managed toolchain/runtime")
            appendLine("${plan.familyName} ${plan.requestedVersion}")
            appendLine("Provider: ${item.version.installKind.name.lowercase()}")
            appendLine("Scope: ${item.version.scope.name.lowercase()}")
            appendLine("Components:")
            plan.components.take(32).forEach { appendLine("• ${it.take(300)}") }
            when (plan) {
                is ExtensionInstallPlan.Android -> {
                    appendLine("Android SDK license SHA-256: ${plan.license.sha256}")
                    appendLine("License accepted: ${plan.licenseAccepted}")
                }
                is ExtensionInstallPlan.AndroidLocalComponent -> {
                    appendLine("Artifact: ${plan.entry.fileName}")
                    appendLine("Artifact SHA-256: ${plan.entry.sha256}")
                    appendLine("Upstream SHA-1: ${plan.entry.upstreamSha1}")
                    appendLine("Ubuntu target: ${LocalAndroidSdkComponentEnvironment.GUEST_SDK_ROOT}/${plan.entry.guestTarget}")
                    if (plan.entry.nativeTools.isNotEmpty()) {
                        appendLine("ARM64 native overlays:")
                        plan.entry.nativeTools.sortedBy { it.name }.forEach { tool ->
                            appendLine("  ${tool.name}: sha256:${tool.sha256}")
                        }
                    }
                    appendLine("Android SDK license SHA-256: ${plan.license.sha256}")
                    appendLine("License accepted: ${plan.licenseAccepted}")
                }
                else -> Unit
            }
            appendLine("Droide will install only artifacts/recipes already admitted by its certified/reviewed provider pipeline.")
        }.take(8_000)
        val resource = "install:${plan.familyId}@${plan.requestedVersion}:sha256:${sha256(preview)}"
        return PreparedInstall(item, plan, preview, resource, needsLicense)
    }

    suspend fun install(prepared: PreparedInstall): String {
        if (prepared.needsLicenseAcceptance) {
            return "UNAVAILABLE: Android SDK license acceptance is required. Open Extensions, review and accept the exact license yourself; the Agent cannot accept legal terms on your behalf."
        }
        
        
        val result = extensions.unifiedAuthority.installPrepared(prepared.plan)
        if (!result.success) return "TOOLCHAIN_INSTALL_RESULT\nsuccess=false\nmessage=${result.message.take(6_000)}"
        
        val refreshed = extensions.unifiedAuthority.info(prepared.plan.familyId, prepared.plan.resolvedVersion)
        require(refreshed?.state == "active" || refreshed?.state == "inactive") {
            "Managed install returned but unified authority did not prove INSTALLED: state=${refreshed?.state}"
        }
        
        return buildString {
            append("TOOLCHAIN_INSTALL_RESULT\n")
            append("success=").append(result.success).append('\n')
            append("message=").append(result.message.take(6_000)).append('\n')
            append("verified_state=").append(refreshed.state).append('\n')
        }
    }

    suspend fun repairPreview(familyId: String, version: String): Pair<ExtensionVersionState, String> {
        val item = find(familyId, version)
        require(item.state == ExtensionState.INSTALLED) { "Only Droide-managed installed packages can be repaired" }
        require(item.version.installKind in setOf(
            ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN,
            ExtensionInstallKind.ANDROID_LOCAL_COMPONENT,
            ExtensionInstallKind.MANAGED_PACKAGE,
            ExtensionInstallKind.GUEST_PACKAGE,
            ExtensionInstallKind.REVIEWED_RECIPE,
        )) { "This entry has no managed repair provider" }
        val preview = "Repair managed package\n${item.family.name} ${item.version.version}\n${item.detail}\nDroide will reinstall/health-check through the existing managed provider and will not replace user-managed EXTERNAL tools."
        return item to preview
    }

    suspend fun repair(item: ExtensionVersionState): String {
        
        val result = extensions.unifiedAuthority.repairVersion(item.family.id, item.version.version)
        if (!result.success) return "TOOLCHAIN_REPAIR_RESULT\nsuccess=false\nmessage=${result.message.take(6_000)}"
        
        val refreshed = extensions.unifiedAuthority.info(item.family.id, item.version.version)
        require(refreshed?.state == "active" || refreshed?.state == "inactive") {
            "Managed repair returned but unified authority did not prove INSTALLED: state=${refreshed?.state}"
        }
        
        return "TOOLCHAIN_REPAIR_RESULT\nsuccess=${result.success}\nmessage=${result.message.take(6_000)}\nverified_state=${refreshed.state}"
    }

    suspend fun activateWorkspace(familyId: String, version: String, workspaceId: String? = null): String {
        val item = find(familyId, version)
        require(item.state == ExtensionState.INSTALLED) { "Install the managed package before selecting it for this workspace" }
        
        
        val result = extensions.unifiedAuthority.activate(item.family.id, item.version.version, workspaceId ?: extensions.workspaceId)
        
        return "TOOLCHAIN_WORKSPACE_SELECTION family=${item.family.id} version=${item.version.version} status=${if (result.success) "selected" else "failed"} message=${result.message}"
    }

    private suspend fun find(familyId: String, version: String?): ExtensionVersionState {
        val cleanFamily = familyId.trim()
        require(cleanFamily.isNotBlank()) { "family is required" }
        val snapshot = extensions.refresh()
        val candidates = snapshot.items.filter { it.family.id == cleanFamily }
        require(candidates.isNotEmpty()) { "Unknown extension/toolchain family: $cleanFamily" }
        if (!version.isNullOrBlank()) {
            return candidates.firstOrNull { it.version.version == version.trim() }
                ?: error("Unknown version ${version.trim()} for $cleanFamily")
        }
        return candidates.firstOrNull { it.state == ExtensionState.INSTALLED }
            ?: candidates.firstOrNull { it.version.recommended }
            ?: candidates.first()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

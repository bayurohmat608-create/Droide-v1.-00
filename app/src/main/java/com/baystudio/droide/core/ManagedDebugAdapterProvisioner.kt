package com.baystudio.droide.core

import android.content.Context
import android.os.Build






class ManagedDebugAdapterProvisioner(
    context: Context,
    private val bridge: DeviceBridgeManager,
    private val development: AndroidDevelopmentManager,
    private val registry: ManagedPackageRegistry,
) {
    data class ProvisionPlan(
        val adapter: ManagedDebugAdapterPlanEntry,
        val catalogRevision: String,
        val target: ManagedPackageCatalogEntry,
        val entries: List<ManagedPackageCatalogEntry>,
    )

    data class Status(
        val adapterId: String,
        val plannedVersion: String,
        val catalogAvailable: Boolean,
        val installed: Boolean,
        val verified: Boolean,
        val commandReady: Boolean,
        val message: String,
    )

    private val appContext = context.applicationContext
    private val installer = ManagedPackageInstaller(appContext, bridge, registry)

    fun prepare(
        adapterId: String,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    ): ProvisionPlan {
        ManagedDebugAdapterPlan.validate()
        val adapter = ManagedDebugAdapterPlan.forAdapter(adapterId)
            ?: error("No managed debug-adapter plan exists for $adapterId")
        val document = ManagedPackageCatalog.load(appContext)
        val target = ManagedPackageCatalog.select(document, adapter.packageFamilyId, adapter.pinnedVersion, supportedAbis)
            ?: error("No certified managed package is pinned for ${adapter.packageFamilyId} ${adapter.pinnedVersion}")
        val entries = ManagedPackageDependencyResolver.resolve(document, target, supportedAbis)
        check(entries.lastOrNull()?.id == target.id) { "Managed debug-adapter dependency plan is malformed" }
        return ProvisionPlan(adapter, document.revision, target, entries)
    }

    suspend fun install(plan: ProvisionPlan): ManagedPackageRecord {
        check(bridge.state.value.connected != null) { "Connect Device Workstation first" }
        check(plan.entries.isNotEmpty() && plan.entries.last().id == plan.target.id) { "Invalid debug-adapter install plan" }
        check(plan.adapter.packageFamilyId == plan.target.familyId && plan.adapter.pinnedVersion == plan.target.version) {
            "Debug-adapter plan/catalog mismatch"
        }

        var targetRecord: ManagedPackageRecord? = null
        for (entry in plan.entries) {
            val existing = registry.find(entry.familyId, entry.version)
            val record = if (existing != null && installer.verify(existing)) {
                installer.activate(existing)
                existing.copy(active = true)
            } else {
                installer.install(entry)
            }
            if (entry.id == plan.target.id) targetRecord = record
        }

        val target = targetRecord ?: error("Managed debug-adapter target was not installed")
        check(installer.verify(target)) { "Installed debug-adapter package failed integrity/health verification" }
        check(plan.adapter.command in target.commands) {
            "Installed debug-adapter package does not expose required command ${plan.adapter.command}"
        }
        verifyRuntimeCommands(plan.adapter)
        return registry.find(target.familyId, target.version) ?: target
    }

    suspend fun status(adapterId: String): Status {
        val adapter = ManagedDebugAdapterPlan.forAdapter(adapterId)
            ?: return Status(adapterId, "", false, false, false, false, "No managed debug-adapter plan exists")
        val document = runCatching { ManagedPackageCatalog.load(appContext) }.getOrElse {
            return Status(adapterId, adapter.pinnedVersion, false, false, false, false, it.message ?: "Managed package catalog unavailable")
        }
        val target = ManagedPackageCatalog.select(document, adapter.packageFamilyId, adapter.pinnedVersion, Build.SUPPORTED_ABIS.toList())
            ?: return Status(adapterId, adapter.pinnedVersion, false, false, false, false, "No certified package is pinned yet")
        val record = registry.find(target.familyId, target.version)
            ?: return Status(adapterId, adapter.pinnedVersion, true, false, false, false, "Certified package is available but not installed")
        if (bridge.state.value.connected == null) {
            return Status(adapterId, adapter.pinnedVersion, true, true, false, false, "Connect Device Workstation to verify the installed package")
        }
        val verified = installer.verify(record)
        if (!verified) return Status(adapterId, adapter.pinnedVersion, true, true, false, false, "Installed package failed verification")
        val commandReady = runCatching {
            verifyRuntimeCommands(adapter)
            true
        }.getOrDefault(false)
        return Status(
            adapterId = adapterId,
            plannedVersion = adapter.pinnedVersion,
            catalogAvailable = true,
            installed = true,
            verified = true,
            commandReady = commandReady,
            message = if (commandReady) "Managed debug adapter is verified and discoverable." else "Package is verified but its runtime command is not ready.",
        )
    }

    private suspend fun verifyRuntimeCommands(adapter: ManagedDebugAdapterPlanEntry) {
        val shell = development.prepareInteractiveShell(syncProject = false)
        val commands = linkedSetOf(adapter.command).apply { addAll(adapter.requiredCommands) }
        commands.forEach { command ->
            ProcessSecurityPolicy.requireExecutableName(command)
            val result = bridge.shellBounded(
                shell.shellPrefix() + "command -v ${DeviceBridgeManager.shellQuote(command)}",
                maxOutputBytes = 64 * 1024,
            )
            check(result.exitCode == 0) { "Required managed debug command is unavailable: $command" }
            val resolved = result.stdout.lineSequence().map(String::trim).firstOrNull { it.startsWith('/') }
                ?: error("Managed debug command did not resolve to an absolute path: $command")
            check(ProcessSecurityPolicy.isAllowedRemoteExecutable(resolved, DeviceBridgeManager.remoteRoot())) {
                "Managed debug command resolved outside Droide/system roots: $command"
            }
        }
    }
}

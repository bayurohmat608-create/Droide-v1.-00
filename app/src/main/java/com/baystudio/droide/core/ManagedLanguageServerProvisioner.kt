package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.CoroutineScope

// This does not alter Extensions UI metadata and never turns an unpromoted artifact into an installable package.




class ManagedLanguageServerProvisioner(
    context: Context,
    private val scope: CoroutineScope,
    private val workDir: File,
    private val bridge: DeviceBridgeManager,
    private val development: AndroidDevelopmentManager,
    private val registry: ManagedPackageRegistry,
) {
    data class ProvisionPlan(
        val server: ManagedLanguageServerPlanEntry,
        val catalogRevision: String,
        val target: ManagedPackageCatalogEntry,
        val entries: List<ManagedPackageCatalogEntry>,
    )

    data class Status(
        val serverId: String,
        val plannedVersion: String,
        val catalogAvailable: Boolean,
        val installed: Boolean,
        val verified: Boolean,
        val commandReady: Boolean,
        val message: String,
    )

    private val appContext = context.applicationContext
    private val localProcessHost = LocalLinuxProcessHost(workDir, scope)

    fun prepare(
        serverId: String,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    ): ProvisionPlan {
        ManagedLanguageServerPlan.validate()
        val server = ManagedLanguageServerPlan.forServer(serverId)
            ?: error("No managed language-server plan exists for $serverId")
        val document = ManagedPackageCatalog.load(appContext)
        val target = ManagedPackageCatalog.select(document, server.packageFamilyId, server.pinnedVersion, supportedAbis)
            ?: error("No trusted managed package is pinned for ${server.packageFamilyId} ${server.pinnedVersion}")
        val entries = ManagedPackageDependencyResolver.resolve(document, target, supportedAbis)
        check(entries.lastOrNull()?.id == target.id) { "Managed language-server dependency plan is malformed" }
        return ProvisionPlan(server, document.revision, target, entries)
    }

    // Opening/editing a file never downloads tools.




    suspend fun ensureAvailable(
        serverId: String,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    ): Boolean {
        val server = ManagedLanguageServerPlan.forServer(serverId) ?: return false
        

        runCatching {
            val document = ManagedPackageCatalog.load(appContext)
            ManagedPackageCatalog.select(document, server.packageFamilyId, server.pinnedVersion, supportedAbis)
        }
        return verifyLocalRuntimeCommands(server)
    }

    suspend fun install(plan: ProvisionPlan): ManagedPackageRecord {
        check(plan.entries.isNotEmpty() && plan.entries.last().id == plan.target.id) { "Invalid language-server install plan" }
        error(
            "Managed language-server installation still targets the legacy Device Workstation path. " +
                "Install ${plan.server.command} in local Ubuntu from the Linux terminal until a local transaction is implemented."
        )
    }

    suspend fun status(serverId: String): Status {
        val server = ManagedLanguageServerPlan.forServer(serverId)
            ?: return Status(serverId, "", false, false, false, false, "No managed language-server plan exists")
        val catalogAvailable = runCatching {
            val document = ManagedPackageCatalog.load(appContext)
            ManagedPackageCatalog.select(document, server.packageFamilyId, server.pinnedVersion, Build.SUPPORTED_ABIS.toList()) != null
        }.getOrDefault(false)
        val commandReady = verifyLocalRuntimeCommands(server)
        return Status(
            serverId = serverId,
            plannedVersion = server.pinnedVersion,
            catalogAvailable = catalogAvailable,
            installed = commandReady,
            verified = commandReady,
            commandReady = commandReady,
            message = if (commandReady) {
                "Language server detected and launchable in local Ubuntu"
            } else {
                "Language server is not available in local Ubuntu; install it from the Linux terminal"
            },
        )
    }

    private suspend fun verifyLocalRuntimeCommands(server: ManagedLanguageServerPlanEntry): Boolean {
        val commands = linkedSetOf(server.command).apply { addAll(server.requiredCommands) }
        for (command in commands) {
            ProcessSecurityPolicy.requireExecutableName(command)
            val resolved = runSuspendCatching { localProcessHost.resolveExecutable(command) }.getOrNull()
            if (resolved == null) return false
        }
        return true
    }

}

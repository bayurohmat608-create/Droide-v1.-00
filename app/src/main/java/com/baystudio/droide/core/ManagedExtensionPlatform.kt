package com.baystudio.droide.core

import kotlinx.serialization.json.JsonElement

 
private data class ReviewedMarketplaceBinding(
    val entry: PluginMarketplaceEntry,
    val manifestBytes: ByteArray,
    val grantedPermissions: Set<PluginPermission>,
)

// Package ownership and artifact verification remain delegated to the existing managed-package and marketplace trust layers.






class ManagedExtensionPlatform(
    val contributions: ExtensionContributionRegistry,
    private val pluginRuntime: ManagedPluginRuntime,
    private val packages: ManagedPackageRegistry,
    private val capabilities: UniversalCapabilityRegistry,
) : AutoCloseable {
    private val reviewedBindings = linkedMapOf<String, ReviewedMarketplaceBinding>()
    private val activated = linkedSetOf<String>()

    @Synchronized
    fun registerManifest(manifest: DroideExtensionManifest) {
        contributions.register(manifest)
        capabilities.registerExtension(manifest, enabled = contributions.isEnabled(manifest.id))
    }

    @Synchronized
    fun registerManifestBytes(manifestBytes: ByteArray): DroideExtensionManifest {
        val manifest = DroideExtensionManifestCodec.parse(manifestBytes)
        contributions.register(manifest)
        capabilities.registerExtension(manifest, enabled = contributions.isEnabled(manifest.id))
        return manifest
    }

    @Synchronized
    fun registerReviewedMarketplace(
        entry: PluginMarketplaceEntry,
        manifestBytes: ByteArray,
        grantedPermissions: Set<PluginPermission>,
    ): DroideExtensionManifest {
        PluginMarketplaceResolver.requireExecutableTrust(entry)
        val legacy = PluginMarketplaceResolver.parseAndVerifyManifest(entry, manifestBytes)
        require(legacy.permissions.containsAll(grantedPermissions)) {
            "Cannot grant permissions the plugin did not declare"
        }
        require(grantedPermissions.containsAll(legacy.permissions)) {
            "All declared plugin permissions require explicit grant"
        }
        val manifest = DroideExtensionManifest.fromLegacyPlugin(legacy, publisher = entry.publisher)
        contributions.register(manifest)
        capabilities.registerExtension(manifest, enabled = contributions.isEnabled(manifest.id))
        reviewedBindings[manifest.id] = ReviewedMarketplaceBinding(entry, manifestBytes.copyOf(), grantedPermissions.toSet())
        return manifest
    }

    @Synchronized
    fun unregister(extensionId: String) {
        reviewedBindings.remove(extensionId)
        activated.remove(extensionId)
        pluginRuntime.stop(extensionId)
        contributions.unregister(extensionId)
        capabilities.unregisterExtension(extensionId)
    }

    @Synchronized
    fun setEnabled(extensionId: String, enabled: Boolean) {
        contributions.setEnabled(extensionId, enabled)
        capabilities.setExtensionEnabled(extensionId, enabled)
        if (!enabled) {
            activated.remove(extensionId)
            pluginRuntime.stop(extensionId)
        }
    }

    suspend fun activate(event: ExtensionActivationEvent): ExtensionActivationPlan {
        val plan = contributions.activationPlan(event)
        require(plan.canActivate) {
            plan.blockers.entries.joinToString(prefix = "Extension activation blocked: ") { (id, reason) -> "$id ($reason)" }
        }
        for (extensionId in plan.orderedExtensionIds) {
            activateOne(extensionId)
        }
        return plan
    }

    suspend fun onStartup(): ExtensionActivationPlan = activate(ExtensionActivationEvent.startup())

    suspend fun onLanguage(languageId: String): ExtensionActivationPlan =
        activate(ExtensionActivationEvent.language(languageId))

    suspend fun onDebug(debugType: String): ExtensionActivationPlan =
        activate(ExtensionActivationEvent.debug(debugType))

    suspend fun executeCommand(commandId: String, arguments: List<JsonElement> = emptyList()): JsonElement {
        activate(ExtensionActivationEvent.command(commandId))
        val owner = contributions.ownerOfCommand(commandId) ?: error("No enabled extension contributes command: $commandId")
        val manifest = contributions.manifest(owner) ?: error("Extension disappeared during command dispatch: $owner")
        require(manifest.runtime == ExtensionRuntimeKind.EXECUTABLE) {
            "Command '$commandId' belongs to a non-executable extension and has no runtime handler yet"
        }
        return pluginRuntime.executeCommand(owner, commandId, arguments)
    }

    @Synchronized
    fun activatedExtensionIds(): Set<String> = activated.toSet()

     
    fun contributedTool(toolId: String): ContributedToolCapability? = capabilities.tool(toolId)

    private suspend fun activateOne(extensionId: String) {
        val manifest = contributions.manifest(extensionId) ?: error("Extension is not registered: $extensionId")
        val alreadyHealthy = synchronized(this) {
            extensionId in activated && (manifest.runtime != ExtensionRuntimeKind.EXECUTABLE || pluginRuntime.isRunning(extensionId))
        }
        if (alreadyHealthy) return
        if (manifest.runtime == ExtensionRuntimeKind.EXECUTABLE) {
            synchronized(this) { activated.remove(extensionId) }
        }
        when (manifest.runtime) {
            ExtensionRuntimeKind.DECLARATIVE -> Unit
            ExtensionRuntimeKind.PROTOCOL -> {
                


                check(manifest.contributes.languageServers.isNotEmpty() || manifest.contributes.debuggers.isNotEmpty() || manifest.contributes.tools.isNotEmpty()) {
                    "Protocol extension exposes no protocol/tool capability: $extensionId"
                }
            }
            ExtensionRuntimeKind.EXECUTABLE -> {
                val binding = synchronized(this) { reviewedBindings[extensionId] }
                    ?: error("Executable extension has no reviewed runtime binding: $extensionId")
                pluginRuntime.startReviewedMarketplace(
                    entry = binding.entry,
                    manifestBytes = binding.manifestBytes,
                    records = packages.list(),
                    grantedPermissions = binding.grantedPermissions,
                )
            }
        }
        synchronized(this) { activated += extensionId }
    }

    override fun close() {
        synchronized(this) {
            activated.clear()
            reviewedBindings.clear()
        }
        pluginRuntime.close()
    }
}

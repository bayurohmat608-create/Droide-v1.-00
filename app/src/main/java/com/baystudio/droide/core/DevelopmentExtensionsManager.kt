package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

 
data class DevelopmentExtensionsSnapshot(
    val catalogRevision: String = "source-v1.00",
    val workspace: WorkspaceEnvironmentSnapshot,
    val items: List<ExtensionVersionState>,
    val deviceConnected: Boolean,
    val workstationReady: Boolean,
    val message: String = "",
)


class DevelopmentExtensionsManager(
    private val context: Context,
    private val projectRoot: File,
    private val androidDevelopment: AndroidDevelopmentManager,
    private val bridge: DeviceBridgeManager,
    val capabilities: UniversalCapabilityRegistry,
    val registry: ManagedPackageRegistry = ManagedPackageRegistry(context),
) {
    private val sourceCatalog: PackageSourceCatalog = PackageSourceCatalog.load(context.applicationContext)
    val installer: ManagedExtensionInstaller = ManagedExtensionInstaller(context.applicationContext, androidDevelopment, bridge, registry, sourceCatalog, projectRoot)
    val marketplace: ExtensionMarketplaceManager = ExtensionMarketplaceManager(context.applicationContext)
    private val workspaceToolchains = WorkspaceToolchainPreferences(context.applicationContext, projectRoot)

    val unifiedAuthority = DefaultUnifiedPackageAuthority(
        registry = registry,
        installer = installer.packageInstaller,
        catalog = sourceCatalog,
        extensionInstaller = installer,
        workspaceToolchains = workspaceToolchains,
        itemResolver = { family, version -> 
            val items = refresh().items.filter { it.family.id == family }
            if (version == "latest") {
                items.firstOrNull { it.version.version == PackageSourceCatalog.LATEST_OFFICIAL_VERSION }
                    ?: items.firstOrNull { it.version.recommended && it.state == ExtensionState.AVAILABLE }
                    ?: items.firstOrNull { it.state == ExtensionState.AVAILABLE }
            } else items.firstOrNull { it.version.version == version }
        },
        itemLister = { refresh().items },
    )
    init {
        DroideCliServer.start(context.applicationContext, unifiedAuthority, workspaceToolchains.workspaceId)
    }

    private val initialWorkspace = WorkspaceEnvironmentDetector.detect(projectRoot)
    private val _snapshot = MutableStateFlow(
        DevelopmentExtensionsSnapshot(
            workspace = initialWorkspace,
            items = initialStates(),
            deviceConnected = false,
            workstationReady = false,
            message = "Checking extensions…",
        )
    )
    val snapshot: StateFlow<DevelopmentExtensionsSnapshot> = _snapshot.asStateFlow()

    fun close() = DroideCliServer.stop(unifiedAuthority)

    val workspaceId: String get() = workspaceToolchains.workspaceId

    fun workspaceVersion(familyId: String): String? = workspaceToolchains.selectedVersion(familyId)

    fun useInWorkspace(familyId: String, version: String) {
        val record = registry.find(familyId, version) ?: error("Managed package is not installed")
        require(record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name) { "Workspace overrides require a local Linux package" }
        workspaceToolchains.set(familyId, version)
    }

    fun clearWorkspaceVersion(familyId: String) {
        workspaceToolchains.clear(familyId)
    }

    suspend fun refresh(activeFile: String? = null): DevelopmentExtensionsSnapshot = withContext(Dispatchers.IO) {
        val workspace = WorkspaceEnvironmentDetector.detect(projectRoot, activeFile)
        val connected = bridge.state.value.connected != null
        val devStatus = runSuspendCatching { androidDevelopment.refresh() }.getOrNull()
        val workstation = if (connected) runSuspendCatching { androidDevelopment.workstationInfo() }.getOrNull() else null
        val toolchainCatalog = runCatching { AndroidToolchainCatalog.load(context) }.getOrNull()
        val localAndroidComponentCatalog = runCatching { AndroidLocalComponentCatalog.load(context) }.getOrNull()
        val localAndroidComponents = localAndroidComponentCatalog?.entries.orEmpty()
        val certifiedAndroidEntries = toolchainCatalog?.entries
            ?.filter { it.abi in Build.SUPPORTED_ABIS }
            .orEmpty()
        val packageCatalog = runCatching { ManagedPackageCatalog.load(context) }.getOrNull()
        val managedPackageEntries = packageCatalog?.entries
            ?.filter { it.abi in Build.SUPPORTED_ABIS }
            .orEmpty()
        val managedPackages = managedPackageEntries.map { it.familyId to it.version }.toSet()

        val localRecoveryProblem = runSuspendCatching { installer.reconcileLocalPackages() }
            .exceptionOrNull()?.let { "Local package recovery failed: ${it.message ?: it.javaClass.simpleName}" }
        if (connected) runSuspendCatching { installer.reconcileManagedPackages() }
        

        val externalExecutableMap = detectExternalLocalLinuxExecutables()
        val localRuntime = LocalExecutionSubstrate.inspectLinuxState()
        val externalCommands = externalExecutableMap.keys
        capabilities.updateExternalExecutables(externalExecutableMap) 
        val nodeMajor = if ("node" in externalCommands) detectLocalNodeMajor() else null
        val records = registry.list()
        val validRecords = validateManagedRecords(records, connected)
        val verifiedKeys = validRecords.map { it.familyId to it.version }.toSet()
        val verifiedRecords = records
        val visibleRecords = if (localRecoveryProblem != null) {
            verifiedRecords.filterNot(installer.localPackageAuthority::owns)
        } else verifiedRecords


        val localIdeRecords = visibleRecords.filter { it.scope != ExecutionScope.LOCAL_LINUX_ARM64.name || installer.localPackageAuthority.owns(it) }
        capabilities.updateManagedPackages(localIdeRecords)
        capabilities.updateManagedDebugAdapters(PackageProtocolCapabilityEngine.debugAdapters(sourceCatalog.manifests, localIdeRecords))
        // A disconnected bridge is not evidence that a durable remote package disappeared.

        if (localRecoveryProblem == null) workspaceToolchains.prune(records.map { it.familyId to it.version }.toSet())
        val workspaceSelections = workspaceToolchains.selections()
        val items = DevelopmentExtensionCatalog.families.flatMap { baseFamily ->
            val family = baseFamily.copy(versions = projectedVersions(
                baseFamily, workstation, certifiedAndroidEntries, localAndroidComponents, managedPackageEntries, visibleRecords,
            ))
            family.versions.map { version ->
                resolveState(
                    family = family,
                    version = version,
                    workstation = workstation,
                    androidProviderAvailable = when (family.id) {
                        "sdk.android" -> version.version.toIntOrNull()?.let { api -> certifiedAndroidEntries.any { api in it.compileSdks } } == true
                        "toolchain.jdk" -> version.version.toIntOrNull()?.let { java -> certifiedAndroidEntries.any { it.javaVersion == java } } == true
                        "build.android-tools" -> certifiedAndroidEntries.any { version.version in it.buildToolsVersions }
                        "meta.android-development" -> certifiedAndroidEntries.isNotEmpty()
                        else -> false
                    },
                    managedPackages = managedPackages,
                    externalCommands = externalCommands,
                    nodeMajor = nodeMajor,
                    records = visibleRecords,
                    workspaceSelections = workspaceSelections,
                    connected = connected,
                    localRecoveryProblem = localRecoveryProblem,
                    verifiedKeys = verifiedKeys,
                )
            }
        }
        val next = DevelopmentExtensionsSnapshot(
            workspace = workspace,
            items = items,
            deviceConnected = connected,
            workstationReady = workstation != null,
            message = when {
                localRecoveryProblem != null -> localRecoveryProblem
                localRuntime.ready -> "Local Ubuntu ready · ${externalCommands.size} command(s) detected"
                localRuntime.ubuntuAvailable -> "Local Ubuntu needs attention; open the Linux terminal for details"
                !localRuntime.prootAvailable -> "Local Linux engine is unavailable on this device"
                else -> "Activate local Ubuntu from Terminal → + Linux"
            },
        )
        _snapshot.value = next
        next
    }


    private fun projectedVersions(
        family: ExtensionFamily,
        workstation: AndroidDevelopmentManager.WorkstationInfo?,
        certifiedEntries: List<AndroidToolchainCatalogEntry>,
        localAndroidComponents: List<AndroidLocalComponentCatalogEntry>,
        managedPackageEntries: List<ManagedPackageCatalogEntry>,
        installedRecords: List<ManagedPackageRecord>,
    ): List<ExtensionVersion> {
        val dynamic = when (family.id) {
            "sdk.android" -> {
                val installed = workstation?.sdkPlatforms.orEmpty()
                val certified = certifiedEntries.flatMap { it.compileSdks }.toSet()
                val local = localAndroidComponents
                    .filter { it.familyId == family.id && it.kind == AndroidLocalComponentKind.SDK_PLATFORM }
                    .mapNotNull { it.version.toIntOrNull() }
                    .toSet()
                (installed + certified + local).sortedDescending().map { api ->
                    val localEntry = api in local
                    val certifiedPack = api in certified
                    ExtensionVersion(
                        version = api.toString(),
                        channel = when {
                            localEntry -> "google-local-ubuntu"
                            certifiedPack -> "certified"
                            else -> "installed"
                        },
                        installKind = when {
                            localEntry -> ExtensionInstallKind.ANDROID_LOCAL_COMPONENT
                            certifiedPack -> ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN
                            else -> ExtensionInstallKind.CATALOG_ONLY
                        },
                        provides = setOf("android-sdk:$api"),
                        note = when {
                            localEntry -> "Official Google SDK Platform $api archive, byte-pinned and projected into local Ubuntu."
                            certifiedPack -> "A certified Android toolchain artifact declares Android SDK $api capability."
                            else -> "Android SDK $api was discovered from the active Android toolchain profile."
                        },
                    )
                }
            }
            "toolchain.jdk" -> {
                val installedAndroid = workstation?.javaVersion?.let(::setOf).orEmpty()
                val certifiedAndroid = certifiedEntries.map { it.javaVersion }.toSet()
                val androidVersions = (installedAndroid + certifiedAndroid).sortedDescending().map { java ->
                    ExtensionVersion(
                        version = java.toString(),
                        channel = if (java in certifiedAndroid) "certified" else "installed",
                        installKind = if (java in certifiedAndroid) ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN else ExtensionInstallKind.CATALOG_ONLY,
                        provides = setOf("jdk:$java", "java", "javac", "jar"),
                        note = if (java in certifiedAndroid) {
                            "A certified Android toolchain artifact declares JDK $java capability."
                        } else {
                            "JDK $java was discovered from the active Android toolchain profile."
                        },
                    )
                }
                val reviewedSource = sourceCatalog.installable(family.id)?.takeIf(LocalUbuntuReviewedArtifactPolicy::supports)
                val managedInstalled = installedRecords.filter { it.familyId == family.id && installer.localPackageAuthority.owns(it) }.map { it.version }.distinct().sortedDescending().map { version ->
                    ExtensionVersion(
                        version = version,
                        channel = "managed-ubuntu",
                        installKind = ExtensionInstallKind.REVIEWED_RECIPE,
                        scope = ExecutionScope.LOCAL_LINUX_ARM64,
                        provides = setOf("java", "javac", "jar"),
                        note = "Managed Ubuntu ARM64 JDK installed with workspace-selectable JAVA_HOME.",
                    )
                }
                val latest = if (reviewedSource != null) listOf(
                    ExtensionVersion(
                        version = requireNotNull(reviewedSource.vendorPinnedVersion),
                        channel = "managed-ubuntu",
                        recommended = true,
                        installKind = ExtensionInstallKind.REVIEWED_RECIPE,
                        scope = ExecutionScope.LOCAL_LINUX_ARM64,
                        provides = setOf("java", "javac", "jar"),
                        note = "Pinned Eclipse Temurin ARM64 JDK for local Ubuntu; publisher SHA-256 verified.",
                    )
                ) else emptyList()
                (latest + managedInstalled + androidVersions).distinctBy { it.version }
            }
            "build.android-tools" -> {
                val installed = workstation?.buildToolsVersions.orEmpty()
                val certified = certifiedEntries.flatMap { it.buildToolsVersions }.toSet()
                val local = localAndroidComponents
                    .filter { it.familyId == family.id && it.kind == AndroidLocalComponentKind.BUILD_TOOLS_ARM64 }
                    .map { it.version }
                    .toSet()
                (installed + certified + local).sortedDescending().map { revision ->
                    val localEntry = revision in local
                    val certifiedPack = revision in certified
                    ExtensionVersion(
                        version = revision,
                        channel = when {
                            localEntry -> "google-plus-aosp-arm64"
                            certifiedPack -> "certified"
                            else -> "installed"
                        },
                        installKind = when {
                            localEntry -> ExtensionInstallKind.ANDROID_LOCAL_COMPONENT
                            certifiedPack -> ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN
                            else -> ExtensionInstallKind.CATALOG_ONLY
                        },
                        scope = ExecutionScope.LOCAL_LINUX_ARM64,
                        provides = setOf("aapt2", "aidl", "apksigner", "zipalign", "split-select"),
                        note = when {
                            localEntry -> "Build Tools $revision uses Google Java/metadata plus pinned AOSP-derived linux-glibc-arm64 native tools for local Ubuntu."
                            certifiedPack -> "Build Tools $revision is available from a certified Android toolchain artifact."
                            else -> "Build Tools $revision is installed in the active Android toolchain profile."
                        },
                    )
                }
            }
            else -> {
                val catalogVersions = managedPackageEntries.filter { it.familyId == family.id }.map { it.version }.toSet()
                val installedVersions = installedRecords.filter { it.familyId == family.id }.map { it.version }.toSet()
                val baseRequires = family.versions.flatMap { it.requires }.toSet()
                val verified = catalogVersions.sortedDescending().map { version ->
                    ExtensionVersion(
                        version = version,
                        channel = if (version in installedVersions) "installed" else "verified",
                        installKind = ExtensionInstallKind.MANAGED_PACKAGE,
                        provides = family.versions.flatMap { it.provides }.toSet(),
                        requires = baseRequires,
                        note = "Available for this device ABI.",
                    )
                }
                val guest = WorkstationGuestPackageCatalog.forFamily(family.id).map { recipe ->
                    ExtensionVersion(
                        version = recipe.version,
                        channel = "rootless-alpine",
                        recommended = true,
                        installKind = ExtensionInstallKind.GUEST_PACKAGE,
                        scope = ExecutionScope.LOCAL_LINUX_ARM64,
                        provides = recipe.commands.keys,
                        requires = baseRequires,
                        note = "Rootless Alpine ARM64 package.",
                    )
                }
                val source = sourceCatalog.installable(family.id)
                val followerSource = sourceCatalog.find(family.id)
                val bundle = sourceCatalog.manifest(family.id)?.bundle?.takeIf { it.primaryFamilyId != family.id }
                val bundlePrimarySource = bundle?.let { sourceCatalog.installable(it.primaryFamilyId) }
                val sourceLatest = when {
                    source != null -> listOf(
                        ExtensionVersion(
                            version = PackageSourceCatalog.LATEST_OFFICIAL_VERSION,
                            channel = "${source.source.label.lowercase()}-official",
                            recommended = true,
                            installKind = ExtensionInstallKind.REVIEWED_RECIPE,
                            scope = ExecutionScope.LOCAL_LINUX_ARM64,
                            provides = setOf(source.command),
                            requires = baseRequires,
                            note = "Latest official ${source.source.label} release.",
                        )
                    )
                    bundlePrimarySource != null && followerSource != null -> listOf(
                        ExtensionVersion(
                            version = PackageSourceCatalog.LATEST_OFFICIAL_VERSION,
                            channel = "bundle-${bundle!!.id}",
                            recommended = true,
                            installKind = ExtensionInstallKind.REVIEWED_RECIPE,
                            scope = ExecutionScope.LOCAL_LINUX_ARM64,
                            provides = setOf(followerSource.command),
                            requires = baseRequires,
                            note = "Shared ${bundle.id} capability via ${bundle.primaryFamilyId}.",
                        )
                    )
                    else -> emptyList()
                }
                val known = (catalogVersions + guest.map { it.version }).toSet()
                val discovered = (installedVersions - known).sortedDescending().map { version ->
                    val projectionOwned = source != null || bundlePrimarySource != null
                    ExtensionVersion(
                        version = version,
                        channel = "installed",
                        installKind = if (projectionOwned) ExtensionInstallKind.REVIEWED_RECIPE else ExtensionInstallKind.CATALOG_ONLY,
                        provides = (source ?: followerSource)?.let { setOf(it.command) } ?: family.versions.flatMap { it.provides }.toSet(),
                        requires = baseRequires,
                        note = when {
                            source != null -> "Installed via ${source.source.label}."
                            bundlePrimarySource != null && bundle != null -> "Shared ${bundle.id} install via ${bundle.primaryFamilyId}."
                            else -> "Installed package detected."
                        },
                    )
                }
                verified + guest + sourceLatest + discovered
            }
        }
        return (dynamic + family.versions).distinctBy { it.version }
    }

    private fun initialStates(): List<ExtensionVersionState> = DevelopmentExtensionCatalog.families.flatMap { family ->
        family.versions.map { version ->
            if (version.installKind == ExtensionInstallKind.BUILT_IN) {
                ExtensionVersionState(family, version, ExtensionState.BUILT_IN, "Built-in")
            } else {
                ExtensionVersionState(family, version, ExtensionState.UNAVAILABLE, version.note.ifBlank { "Not checked yet" })
            }
        }
    }

    private fun resolveState(
        family: ExtensionFamily,
        version: ExtensionVersion,
        workstation: AndroidDevelopmentManager.WorkstationInfo?,
        androidProviderAvailable: Boolean,
        managedPackages: Set<Pair<String, String>>,
        externalCommands: Set<String>,
        nodeMajor: Int?,
        records: List<ManagedPackageRecord>,
        workspaceSelections: Map<String, String>,
        connected: Boolean,
        localRecoveryProblem: String?,
        verifiedKeys: Set<Pair<String, String>>,
    ): ExtensionVersionState {
        if (version.installKind == ExtensionInstallKind.BUILT_IN) {
            return ExtensionVersionState(family, version, ExtensionState.BUILT_IN, "Built-in")
        }
        val localReviewedSource = sourceCatalog.installable(family.id)?.takeIf(LocalUbuntuReviewedArtifactPolicy::supports)
        val localNpmRecipe = WorkstationInstallRecipeCatalog.find(family.id, version.version)?.takeIf(LocalUbuntuNpmPolicy::supports)
        if (localRecoveryProblem != null && (family.id == LocalManagedPackageAuthority.PILOT_FAMILY || localReviewedSource != null || localNpmRecipe != null || version.installKind == ExtensionInstallKind.ANDROID_LOCAL_COMPONENT)) {
            return ExtensionVersionState(family, version, ExtensionState.UNAVAILABLE, localRecoveryProblem)
        }
        if (family.id == "sdk.android") {
            if (version.version == "Project-selected" && workstation != null) {
                return ExtensionVersionState(
                    family, version, ExtensionState.INSTALLED,
                    "Installed SDKs: ${workstation.sdkPlatforms.sorted().joinToString(", ")}",
                )
            }
            val api = version.version.toIntOrNull()
            if (api != null && workstation?.sdkPlatforms?.contains(api) == true) {
                return ExtensionVersionState(family, version, ExtensionState.INSTALLED, "Installed")
            }
        }
        if (family.id == "toolchain.jdk") {
            if (version.version == "Project-selected" && workstation != null) {
                return ExtensionVersionState(
                    family, version, ExtensionState.INSTALLED,
                    "Active JDK ${workstation.javaVersion}",
                )
            }
            val java = version.version.toIntOrNull()
            if (java != null && workstation?.javaVersion == java) {
                return ExtensionVersionState(family, version, ExtensionState.INSTALLED, "JDK $java active")
            }
        }
        if (family.id == "meta.android-development" && workstation != null) {
            return ExtensionVersionState(
                family,
                version,
                ExtensionState.INSTALLED,
                "JDK ${workstation.javaVersion} · SDK ${workstation.compileSdk} · ${workstation.abi}",
            )
        }

        if (family.id == "build.android-tools" && workstation != null) {
            if (version.version == "Project-selected") {
                return ExtensionVersionState(
                    family,
                    version,
                    ExtensionState.INSTALLED,
                    "Installed Build Tools: ${workstation.buildToolsVersions.sorted().joinToString(", ")}",
                )
            }
            if (version.version in workstation.buildToolsVersions) {
                return ExtensionVersionState(
                    family,
                    version,
                    ExtensionState.INSTALLED,
                    "Build Tools ${version.version} installed",
                )
            }
        }
        records.firstOrNull { it.familyId == family.id && it.version == version.version }?.let { record ->
            val workspaceSelected = workspaceSelections[family.id] == version.version
            return ExtensionVersionState(
                family,
                version,
                ExtensionState.INSTALLED,
                when {
                    installer.localPackageAuthority.owns(record) -> "Installed app-local · verify or repair"
                    !connected && record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name -> "Installed · verification pending"
                    (record.familyId to record.version) !in verifiedKeys -> "Installed · verification failed or pending; receipt preserved for repair"
                    workspaceSelected -> "Installed · workspace default"
                    record.active -> "Installed · global default"
                    else -> "Installed"
                },
            )
        }

        val executableProvides = version.provides.filter { it.matches(Regex("[A-Za-z0-9._+-]{1,64}")) }
        val canUseExecutableDetection = version.scope == ExecutionScope.LOCAL_LINUX_ARM64 &&
            (family.id != LocalUbuntuJdkPolicy.FAMILY || version.version == "Project-selected")
        val detected = if (family.id == LocalUbuntuJdkPolicy.FAMILY) {
            setOf("java", "javac", "jar").all { it in externalCommands }
        } else if (localNpmRecipe != null) executableProvides.all { it in externalCommands }
        else executableProvides.any { it in externalCommands }
        if (canUseExecutableDetection && executableProvides.isNotEmpty() && detected) {
            return ExtensionVersionState(
                family,
                version,
                ExtensionState.EXTERNAL,
                "Detected in local Ubuntu · user-managed",
            )
        }

        if (version.installKind == ExtensionInstallKind.ANDROID_LOCAL_COMPONENT) {
            return ExtensionVersionState(
                family,
                version,
                ExtensionState.AVAILABLE,
                "Reviewed Android SDK component · verified downloads · local Ubuntu projection",
            )
        }

        if (version.installKind == ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN) {
            return ExtensionVersionState(
                family,
                version,
                ExtensionState.UNAVAILABLE,
                if (androidProviderAvailable) {
                    "Local Ubuntu install transaction is not implemented yet; install this toolchain from the Linux terminal"
                } else {
                    version.note.ifBlank { "No compatible Android toolchain package" }
                },
            )
        }

        if (version.installKind == ExtensionInstallKind.GUEST_PACKAGE) {
            return ExtensionVersionState(
                family,
                version,
                ExtensionState.UNAVAILABLE,
                "This installer targets a different guest; install the tool in local Ubuntu from the Linux terminal",
            )
        }

        if (version.installKind == ExtensionInstallKind.REVIEWED_RECIPE) {
            if (localNpmRecipe != null) {
                return ExtensionVersionState(family, version, ExtensionState.AVAILABLE,
                    "Original CLI + ACP in local Ubuntu · install Node.js ${localNpmRecipe.minimumNodeMajor}+ and npm 10+ in the Linux terminal first")
            }
            if (localReviewedSource != null) {
                return ExtensionVersionState(
                    family,
                    version,
                    ExtensionState.AVAILABLE,
                    "Reviewed Linux/ARM64 artifact · app-local Ubuntu/glibc transaction",
                )
            }
            return ExtensionVersionState(
                family,
                version,
                ExtensionState.UNAVAILABLE,
                "Local Ubuntu install transaction is not implemented yet for this artifact type; install the tool from the Linux terminal",
            )
        }

        if (version.installKind == ExtensionInstallKind.MANAGED_PACKAGE) {
            return ExtensionVersionState(
                family,
                version,
                ExtensionState.UNAVAILABLE,
                "Managed installer currently targets Device Workstation, not the local Ubuntu IDE backend",
            )
        }

        val pendingSource = sourceCatalog.find(family.id)?.takeIf { !it.installerReady }
        return ExtensionVersionState(
            family,
            version,
            ExtensionState.UNAVAILABLE,
            pendingSource?.let {
                "${it.source.label} installer is not available yet"
            } ?: version.note.ifBlank { "No compatible managed package" },
        )
    }


    private suspend fun validateManagedRecords(records: List<ManagedPackageRecord>, connected: Boolean): List<ManagedPackageRecord> {
        if (records.isEmpty()) return emptyList()
        val local = records.filter { it.scope != ExecutionScope.LOCAL_LINUX_ARM64.name || installer.localPackageAuthority.owns(it) }
        if (!connected) return local
        val remote = records.filter { it.scope == ExecutionScope.LOCAL_LINUX_ARM64.name && !installer.localPackageAuthority.owns(it) }.take(64)
        if (remote.isEmpty()) return local
        val root = DeviceBridgeManager.remoteRoot()
        val valid = mutableListOf<ManagedPackageRecord>()
        for (record in remote) {
            if (!record.installRoot.startsWith("$root/")) {
                continue
            }
            val safe = runCatching { DeviceBridgeManager.requireSafeRemotePath(record.installRoot) }.isSuccess
            val healthy = safe && runSuspendCatching { installer.verifyManagedRecord(record) }.getOrDefault(false)
            if (healthy) valid += record
        }
        return local + valid
    }

    private suspend fun detectLocalNodeMajor(): Int? {
        val spec = runCatching { LocalExecutionSubstrate.linuxLaunchSpec(projectRoot) }.getOrNull() ?: return null
        val result = runSuspendCatching {
            LocalProcessSupervisor.capture(
                spec.command(listOf("node", "-p", "process.versions.node.split('.')[0]")),
                projectRoot,
                spec.environment,
                maxOutputBytes = 8_192,
                timeoutMs = 8_000,
            )
        }.getOrNull() ?: return null
        return result.output.trim().lineSequence().lastOrNull()?.toIntOrNull()
    }

    private suspend fun detectExternalLocalLinuxExecutables(): Map<String, String> {
        val spec = runCatching { LocalExecutionSubstrate.linuxLaunchSpec(projectRoot) }.getOrNull()
            ?: return emptyMap()
        val commands = DevelopmentExtensionCatalog.families
            .asSequence()
            .flatMap { it.versions.asSequence() }
            .flatMap { it.provides.asSequence() }
            .filter { it.matches(Regex("[A-Za-z0-9._+-]{1,64}")) }
            .distinct()
            .take(160)
            .toList()
        if (commands.isEmpty()) return emptyMap()

        val script = buildString {
            append("for c in")
            commands.forEach { append(' ').append(LocalExecutionSubstrate.shellQuote(it)) }
            append("; do p=${'$'}(command -v -- \"${'$'}c\" 2>/dev/null) || continue; ")
            append("case \"${'$'}p\" in /*) printf '%s\\t%s\\n' \"${'$'}c\" \"${'$'}p\";; esac; done")
        }
        val result = runSuspendCatching {
            LocalProcessSupervisor.capture(
                spec.shellCommand(script),
                projectRoot,
                spec.environment,
                maxOutputBytes = 64 * 1024,
                timeoutMs = 12_000,
            )
        }.getOrNull() ?: return emptyMap()
        if (result.timedOut || result.exitCode != 0) return emptyMap()
        return result.output.lineSequence().mapNotNull { line ->
            val split = line.split('\t', limit = 2)
            if (split.size != 2 || split[0] !in commands || !split[1].startsWith('/')) null
            else split[0] to split[1]
        }.toMap()
    }

}

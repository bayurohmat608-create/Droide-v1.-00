package com.baystudio.droide.core
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
sealed interface ExtensionInstallPlan {
    val familyId: String
    val familyName: String
    val requestedVersion: String
    val components: List<String>
    data class Android(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val toolchainEntry: AndroidToolchainCatalogEntry,
        val license: AndroidSdkLicense,
        val licenseAccepted: Boolean,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class AndroidLocalComponent(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val entry: AndroidLocalComponentCatalogEntry,
        val license: AndroidSdkLicense,
        val licenseAccepted: Boolean,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class Package(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val entries: List<ManagedPackageCatalogEntry>,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class Recipe(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val recipe: WorkstationInstallRecipe,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class PyPi(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val recipe: PyPiInstallRecipe,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class Go(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val recipe: GoInstallRecipe,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class RubyGems(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val recipe: RubyGemsInstallRecipe,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class GitHubRelease(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val recipe: GitHubReleaseInstallRecipe,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class VendorOfficial(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val recipe: VendorArtifactInstallRecipe,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class BundleProjection(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val bundleId: String,
        val primaryFamilyId: String,
        val primarySource: PackageSourceDefinition,
        val primaryRecipe: ReviewedBundlePrimaryRecipe,
        val command: String,
        val healthArgs: List<String>,
        val provenanceUrl: String,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class GuestPackage(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val recipe: WorkstationGuestPackageRecipe,
        override val components: List<String>,
    ) : ExtensionInstallPlan
}

val ExtensionInstallPlan.resolvedVersion: String
    get() = when (this) {
        is ExtensionInstallPlan.Android -> requestedVersion
        is ExtensionInstallPlan.AndroidLocalComponent -> requestedVersion
        is ExtensionInstallPlan.Package -> entries.first { it.familyId == familyId }.version
        is ExtensionInstallPlan.Recipe -> recipe.version
        is ExtensionInstallPlan.PyPi -> recipe.version
        is ExtensionInstallPlan.Go -> recipe.version
        is ExtensionInstallPlan.RubyGems -> recipe.version
        is ExtensionInstallPlan.GitHubRelease -> recipe.version
        is ExtensionInstallPlan.VendorOfficial -> recipe.version
        is ExtensionInstallPlan.GuestPackage -> recipe.version
        is ExtensionInstallPlan.BundleProjection -> primaryRecipe.version
    }

 
class ManagedExtensionInstaller(
    context: Context,
    private val androidDevelopment: AndroidDevelopmentManager,
    private val bridge: DeviceBridgeManager,
    private val registry: ManagedPackageRegistry,
    private val sourceCatalog: PackageSourceCatalog = PackageSourceCatalog.load(context.applicationContext),
    private val projectRoot: java.io.File,
) {
    enum class Phase { IDLE, RESOLVING, LICENSE, DOWNLOADING, INSTALLING, ACTIVATING, REPAIRING, REMOVING, READY, CANCELED, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val message: String = "",
        val familyId: String? = null,
        val version: String? = null,
    ) {
        val running: Boolean get() = phase in setOf(Phase.RESOLVING, Phase.DOWNLOADING, Phase.INSTALLING, Phase.ACTIVATING, Phase.REPAIRING, Phase.REMOVING)
    }

    private val appContext = context.applicationContext
    private val longOperationJournal = LongRunningOperationJournal(
        java.io.File(appContext.filesDir, "operation-journal"),
        "packages",
        android.os.Process.myPid(),
        currentProcessIdentity = LocalExecutionSubstrate.processIdentity(),
    ).also { it.reconcileInterrupted() }
    private val licenseManager = AndroidSdkLicenseManager(appContext)
    private val androidInstaller = ManagedAndroidToolchainInstaller(appContext, androidDevelopment, licenseManager)
    val packageInstaller = ManagedPackageInstaller(appContext, bridge, registry)
    val localPackageAuthority = LocalManagedPackageAuthority(appContext, registry)
    private val recipeInstaller = ReviewedWorkstationRecipeInstaller(androidDevelopment, bridge, packageInstaller)
    private val pypiRecipeInstaller = ReviewedPyPiRecipeInstaller(appContext, bridge, packageInstaller)
    private val goRecipeInstaller = ReviewedGoRecipeInstaller(appContext, bridge, packageInstaller)
    private val rubyGemsRecipeInstaller = ReviewedRubyGemsRecipeInstaller(appContext, bridge, packageInstaller)
    private val guestEnvironment = WorkstationGuestEnvironmentManager(appContext)
    private val guestPackageInstaller = WorkstationGuestPackageInstaller(appContext, localPackageAuthority, guestEnvironment)
    private val localUbuntuEnvironment = FoundryUbuntuGuestEnvironmentManager(appContext)
    private val localNpmInstaller = LocalUbuntuNpmRecipeInstaller(appContext, projectRoot, localPackageAuthority, localUbuntuEnvironment)
    private val localAndroidSdkComponentInstaller = LocalAndroidSdkComponentInstaller(appContext, localPackageAuthority, localUbuntuEnvironment)
    private val localReviewedRawInstaller = LocalReviewedRawArtifactInstaller(appContext, localPackageAuthority, localUbuntuEnvironment)
    private val localReviewedWholeTreeInstaller = LocalReviewedWholeTreeArtifactInstaller(appContext, localPackageAuthority, localUbuntuEnvironment)
    private val runtimeDependencyEngine = PackageRuntimeDependencyEngine(registry, packageInstaller, guestPackageInstaller, guestEnvironment)
    private val reviewedArtifactBackend = ReviewedArtifactBackendRouter(appContext, bridge, packageInstaller, runtimeDependencyEngine, registry, sourceCatalog, guestEnvironment)
    private val artifactInstallCoordinator = ReviewedArtifactInstallCoordinator(registry, packageInstaller, reviewedArtifactBackend)
    private val bundleProjectionInstaller = ReviewedBundleProjectionInstaller(bridge, packageInstaller)
    private val sourceResolver = PackageSourceResolver(sourceCatalog)
    private val bundleProjectionCoordinator = ReviewedBundleProjectionCoordinator(sourceCatalog, sourceResolver, registry, packageInstaller, reviewedArtifactBackend, bundleProjectionInstaller)
    private val operationMutex = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val androidProviderState: StateFlow<ManagedAndroidToolchainInstaller.State> = androidInstaller.state
    val packageProviderState: StateFlow<ManagedPackageInstaller.State> = packageInstaller.state

    suspend fun prepare(item: ExtensionVersionState): ExtensionInstallPlan {
        require(item.state == ExtensionState.AVAILABLE) { "This extension is not currently installable" }
        _state.value = State(Phase.RESOLVING, "Resolving package and dependencies…", item.family.id, item.version.version)

        return when (item.version.installKind) {
            ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN -> prepareAndroid(item)
            ExtensionInstallKind.ANDROID_LOCAL_COMPONENT -> prepareAndroidLocalComponent(item)
            ExtensionInstallKind.MANAGED_PACKAGE -> preparePackage(item)
            ExtensionInstallKind.GUEST_PACKAGE -> prepareGuestPackage(item)
            ExtensionInstallKind.REVIEWED_RECIPE -> prepareRecipe(item)
            ExtensionInstallKind.BUILT_IN -> error("Built-in extensions do not need installation")
            ExtensionInstallKind.CATALOG_ONLY -> error("No verified managed installer exists for this package")
        }
    }

    private suspend fun prepareAndroid(item: ExtensionVersionState): ExtensionInstallPlan.Android {
        val catalog = AndroidToolchainCatalog.load(appContext)
        val supported = Build.SUPPORTED_ABIS.toList()
        val entry = when (item.family.id) {
            "sdk.android" -> {
                val api = item.version.version.toIntOrNull() ?: error("Invalid Android SDK version")
                AndroidToolchainCatalog.select(catalog, AndroidToolchainRequirements(compileSdk = api), supported)
            }
            "toolchain.jdk" -> {
                val java = item.version.version.toIntOrNull() ?: error("Invalid JDK version")
                catalog.entries.filter { it.abi in supported && it.javaVersion == java }
                    .minWithOrNull(compareBy<AndroidToolchainCatalogEntry> { AndroidToolchainRuntime.runtimePriority(AndroidToolchainRuntimeSpec(it.executionMode)) }
                        .thenByDescending { it.compileSdk })
            }
            "build.android-tools" -> catalog.entries.filter { entry ->
                entry.abi in supported && item.version.version in entry.buildToolsVersions
            }.minWithOrNull(compareBy<AndroidToolchainCatalogEntry> { AndroidToolchainRuntime.runtimePriority(AndroidToolchainRuntimeSpec(it.executionMode)) }
                .thenByDescending { it.compileSdk })
            "meta.android-development" -> AndroidToolchainCatalog.select(catalog, AndroidToolchainRequirements(), supported)
            else -> null
        } ?: error("No certified ${Build.SUPPORTED_ABIS.firstOrNull() ?: "device"} artifact is pinned for ${item.family.name} ${item.version.version}")

        _state.value = State(Phase.LICENSE, "Loading exact Android SDK license…", item.family.id, item.version.version)
        val license = licenseManager.fetchCurrent()
        require(license.sha256 == entry.sdkLicenseSha256) { "Android SDK license hash does not match the pinned package catalog" }
        val accepted = licenseManager.isAccepted(license)
        _state.value = State(Phase.LICENSE, if (accepted) "Package ready to install." else "Review the exact SDK license before installation.", item.family.id, item.version.version)
        return ExtensionInstallPlan.Android(
            familyId = item.family.id,
            familyName = item.family.name,
            requestedVersion = item.version.version,
            toolchainEntry = entry,
            license = license,
            licenseAccepted = accepted,
            components = buildList {
                add("JDK ${entry.javaVersion}")
                add("Android SDK ${entry.compileSdks.sorted().joinToString(",")}")
                if (entry.buildToolsVersions.isNotEmpty()) add("Build Tools ${entry.buildToolsVersions.joinToString(",")}")
                if (entry.ndkVersions.isNotEmpty()) add("NDK ${entry.ndkVersions.joinToString(",")}")
                if (entry.cmakeVersions.isNotEmpty()) add("CMake ${entry.cmakeVersions.joinToString(",")}")
                add("Gradle Wrapper compatible runtime (${entry.gradleVersion} certification baseline)")
            },
        )
    }

    private suspend fun prepareAndroidLocalComponent(item: ExtensionVersionState): ExtensionInstallPlan.AndroidLocalComponent {
        val catalog = AndroidLocalComponentCatalog.load(appContext)
        val entry = AndroidLocalComponentCatalog.find(catalog, item.family.id, item.version.version)
            ?: error("No verified local Android component is pinned for ${item.family.name} ${item.version.version}")
        _state.value = State(Phase.LICENSE, "Loading current Android SDK license…", item.family.id, item.version.version)
        val license = licenseManager.fetchCurrent()
        val accepted = licenseManager.isAccepted(license)
        _state.value = State(
            Phase.LICENSE,
            if (accepted) "Local Android component is ready to install." else "Review the Android SDK license before installation.",
            item.family.id,
            item.version.version,
        )
        val detail = when (entry.kind) {
            AndroidLocalComponentKind.SDK_PLATFORM -> listOf(
                "Google Android SDK Platform ${entry.version} revision 2",
                "Exact Google archive: ${entry.fileName} (${entry.sizeBytes} bytes)",
                "Google SHA-1 ${entry.upstreamSha1}",
            )
            AndroidLocalComponentKind.BUILD_TOOLS_ARM64 -> listOf(
                "Google Android SDK Build Tools ${entry.version} Java/metadata payload",
                "Exact Google archive: ${entry.fileName} (${entry.sizeBytes} bytes)",
                "Google SHA-1 ${entry.upstreamSha1}",
                "Linux ARM64 native overlay: ${entry.nativeTools.sortedBy { it.name }.joinToString { it.name }}",
                "Native overlay provenance: Commit451/android-arm-build-tools platform-tools-${entry.version}",
            )
        }
        return ExtensionInstallPlan.AndroidLocalComponent(
            familyId = item.family.id,
            familyName = item.family.name,
            requestedVersion = item.version.version,
            entry = entry,
            license = license,
            licenseAccepted = accepted,
            components = detail + listOf(
                "Local Ubuntu projection: ${LocalAndroidSdkComponentEnvironment.GUEST_SDK_ROOT}/${entry.guestTarget}",
                "Primary SHA-256 ${entry.sha256}",
            ) + entry.nativeTools.sortedBy { it.name }.map { "${it.name} SHA-256 ${it.sha256}" },
        )
    }

    private suspend fun preparePackage(item: ExtensionVersionState): ExtensionInstallPlan.Package {
        val document = ManagedPackageCatalog.load(appContext)
        val target = ManagedPackageCatalog.select(document, item.family.id, item.version.version, Build.SUPPORTED_ABIS.toList())
            ?: error("No verified ${Build.SUPPORTED_ABIS.firstOrNull() ?: "device"} package is pinned for ${item.family.name} ${item.version.version}")
        val resolved = resolveDependencies(document, target)
        _state.value = State(Phase.IDLE, "Package plan ready.", item.family.id, item.version.version)
        return ExtensionInstallPlan.Package(
            familyId = item.family.id,
            familyName = item.family.name,
            requestedVersion = item.version.version,
            entries = resolved,
            components = resolved.map { "${it.familyId} ${it.version} (${it.abi})" },
        )
    }

    private suspend fun prepareGuestPackage(item: ExtensionVersionState): ExtensionInstallPlan.GuestPackage {
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "aarch64" }) {
            "Rootless Alpine extension packages currently require ARM64"
        }
        val recipe = WorkstationGuestPackageCatalog.find(item.family.id, item.version.version)
            ?: error("No reviewed Alpine package recipe exists for ${item.family.name} ${item.version.version}")
        _state.value = State(Phase.IDLE, "Reviewed Alpine package recipe ready.", item.family.id, item.version.version)
        return ExtensionInstallPlan.GuestPackage(
            familyId = item.family.id,
            familyName = item.family.name,
            requestedVersion = item.version.version,
            recipe = recipe,
            components = buildList {
                add("Rootless ${WorkstationGuestEnvironmentSpec.VERSION} ARM64 environment")
                addAll(recipe.packages.map { "apk: $it" })
                if (recipe.commands.isNotEmpty()) add("Commands: ${recipe.commands.keys.sorted().joinToString(", ")}")
                add("SHA-256 pinned PRoot + Alpine bootstrap")
            },
        )
    }

    private suspend fun prepareRecipe(item: ExtensionVersionState): ExtensionInstallPlan {
        prepareBundleProjection(item)?.let { return it }
        WorkstationInstallRecipeCatalog.find(item.family.id, item.version.version)?.let { recipe ->
            _state.value = State(Phase.IDLE, "Reviewed exact-version package recipe ready. Install Node.js ${recipe.minimumNodeMajor}+ and npm 10+ in local Ubuntu first.", item.family.id, item.version.version)
            return npmPlan(item, recipe)
        }

        val source = sourceCatalog.installable(item.family.id)
            ?: error("No certified transactional installer exists for ${item.family.name}")
        return when (source.source) {
            PackageSourceKind.NPM -> {
                val recipe = sourceResolver.resolveReviewedNpm(item.family.id, item.version.version)
                _state.value = State(Phase.IDLE, "Official npm metadata resolved to an exact integrity-bound recipe.", item.family.id, recipe.version)
                npmPlan(item, recipe)
            }
            PackageSourceKind.PYPI -> {
                val recipe = sourceResolver.resolveReviewedPyPi(item.family.id, item.version.version)
                _state.value = State(Phase.IDLE, "Official PyPI metadata resolved to an exact wheel-hash-bound recipe.", item.family.id, recipe.version)
                ExtensionInstallPlan.PyPi(
                    familyId = item.family.id,
                    familyName = item.family.name,
                    requestedVersion = item.version.version,
                    recipe = recipe,
                    components = buildList {
                        add("PyPI ${recipe.packageName}==${recipe.packageVersion}")
                        if (item.version.version == PackageSourceCatalog.LATEST_OFFICIAL_VERSION) add("Resolved exact version: ${recipe.version}")
                        add("Release wheel SHA-256 candidates: ${recipe.releaseWheelSha256.size}")
                        add("Provenance: ${recipe.provenanceUrl}")
                        recipe.requiresPython?.let { add("Upstream Requires-Python: $it") }
                        add("Python ${recipe.minimumPythonMajor}.${recipe.minimumPythonMinor}+")
                        add("Wheel-only dependency closure")
                        add("Complete SHA-256 hash lock + offline installation")
                        add("Workspace command: ${recipe.command}")
                        add("Post-install integrity manifest + health check")
                    },
                )
            }
            PackageSourceKind.GO -> {
                val recipe = sourceResolver.resolveReviewedGo(item.family.id, item.version.version)
                _state.value = State(Phase.IDLE, "Official Go module metadata resolved to an exact sumdb-authenticated recipe.", item.family.id, recipe.version)
                ExtensionInstallPlan.Go(
                    familyId = item.family.id,
                    familyName = item.family.name,
                    requestedVersion = item.version.version,
                    recipe = recipe,
                    components = buildList {
                        add("Go ${recipe.packagePath}@${recipe.version}")
                        if (item.version.version == PackageSourceCatalog.LATEST_OFFICIAL_VERSION) add("Resolved exact version: ${recipe.version}")
                        add("Module: ${recipe.modulePath}")
                        add("Provenance: ${recipe.provenanceUrl}")
                        add("Go ${recipe.minimumGoMajor}.${recipe.minimumGoMinor}+")
                        add("proxy.golang.org only; VCS fallback disabled")
                        add("sum.golang.org checksum authentication")
                        add("Workspace command: ${recipe.command}")
                        add("Embedded module-closure lock + post-install integrity manifest")
                    },
                )
            }
            PackageSourceKind.RUBYGEMS -> {
                val recipe = sourceResolver.resolveReviewedRubyGems(item.family.id, item.version.version)
                _state.value = State(Phase.IDLE, "Official RubyGems metadata resolved to an exact SHA-256-bound Bundler transaction.", item.family.id, recipe.version)
                ExtensionInstallPlan.RubyGems(
                    familyId = item.family.id,
                    familyName = item.family.name,
                    requestedVersion = item.version.version,
                    recipe = recipe,
                    components = buildList {
                        add("RubyGems ${recipe.gemName}==${recipe.version}")
                        if (item.version.version == PackageSourceCatalog.LATEST_OFFICIAL_VERSION) add("Resolved exact version: ${recipe.version}")
                        add("Official root SHA-256: ${recipe.rootSha256}")
                        recipe.requiredRuby?.let { add("Upstream Ruby requirement: $it") }
                        add("Bundler exact dependency lock + CHECKSUMS")
                        add("Offline final install from verified vendor/cache")
                        add("Workspace command: ${recipe.command}")
                    },
                )
            }
            PackageSourceKind.GITHUB_RELEASE -> {
                val recipe = sourceResolver.resolveReviewedGitHubRelease(item.family.id, item.version.version)
                _state.value = State(Phase.IDLE, "Official GitHub release metadata resolved to one exact SHA-256-bound Linux/ARM64 artifact.", item.family.id, recipe.version)
                ExtensionInstallPlan.GitHubRelease(
                    familyId = item.family.id,
                    familyName = item.family.name,
                    requestedVersion = item.version.version,
                    recipe = recipe,
                    components = buildList {
                        add("GitHub ${recipe.repository}@${recipe.releaseTag}")
                        if (item.version.version == PackageSourceCatalog.LATEST_OFFICIAL_VERSION) add("Resolved exact version: ${recipe.version}")
                        add("Asset: ${recipe.assetName} (${recipe.assetSize} bytes)")
                        add("GitHub asset SHA-256: ${recipe.assetSha256}")
                        add("Artifact format: ${recipe.artifactFormat.name}")
                        if (recipe.artifactFormat == DeclarativeGitHubArtifactFormat.PHAR) {
                            add("Managed interpreter: ${recipe.interpreterFamilyId}/${recipe.interpreterCommand}")
                            add("Authenticated single-file PHAR projection")
                        } else if (recipe.artifactLayout == DeclarativeGitHubArtifactLayout.WHOLE_TREE) {
                            add("Platform: Linux ARM64 / ${recipe.runtimeCompatibility.libc.name}")
                            add("Safe bounded whole-tree projection: ${recipe.treeEntryPoint}")
                            add("Expanded bounds: ${recipe.treeMaxFiles} files / ${recipe.treeMaxUnpackedBytes} bytes")
                        } else {
                            add("Platform: Linux ARM64 / ELF64 AArch64")
                            add("Safe single-executable projection: ${recipe.executableBasename}")
                        }
                        add("Workspace command: ${recipe.command}")
                        add("Post-install integrity manifest + health check")
                    },
                )
            }
            PackageSourceKind.VENDOR_OFFICIAL -> {
                val recipe = sourceResolver.resolveReviewedVendorOfficial(item.family.id, item.version.version)
                _state.value = State(Phase.IDLE, "Official vendor metadata resolved to an exact publisher-checksum-bound Linux/ARM64 artifact.", item.family.id, recipe.version)
                ExtensionInstallPlan.VendorOfficial(
                    familyId = item.family.id,
                    familyName = item.family.name,
                    requestedVersion = item.version.version,
                    recipe = recipe,
                    components = buildList {
                        add("Official vendor ${recipe.releaseTag}")
                        if (item.version.version == PackageSourceCatalog.LATEST_OFFICIAL_VERSION) add("Resolved exact version: ${recipe.version}")
                        add("Asset: ${recipe.assetName}")
                        add("Publisher SHA-256: ${recipe.assetSha256}")
                        add("Artifact format: ${recipe.artifactFormat.name}")
                        if (recipe.artifactFormat == DeclarativeGitHubArtifactFormat.PHAR) {
                            add("Managed interpreter: ${recipe.interpreterFamilyId}/${recipe.interpreterCommand}")
                            add("Authenticated vendor PHAR projection")
                        } else if (recipe.artifactLayout == DeclarativeGitHubArtifactLayout.WHOLE_TREE) {
                            add("Platform: Linux ARM64 / ${recipe.runtimeCompatibility.libc.name}")
                            add("Safe bounded whole-tree projection: ${recipe.treeEntryPoint}")
                            add("Expanded bounds: ${recipe.treeMaxFiles} files / ${recipe.treeMaxUnpackedBytes} bytes")
                        } else {
                            add("Platform: Linux ARM64 / ${recipe.runtimeCompatibility.libc.name}")
                        }
                        add("Workspace command: ${recipe.command}")
                        add("Post-install integrity manifest + guest health check")
                    },
                )
            }
            else -> error("Certified transaction backend is not implemented for ${source.source.label}")
        }
    }

    private suspend fun prepareBundleProjection(item: ExtensionVersionState) = bundleProjectionCoordinator.prepare(item)?.also {
        _state.value = State(Phase.IDLE, "Shared bundle projection ready. The primary package is installed once and reused transactionally.", item.family.id, item.version.version)
    }

    private fun npmPlan(item: ExtensionVersionState, recipe: WorkstationInstallRecipe): ExtensionInstallPlan.Recipe =
        ExtensionInstallPlan.Recipe(
            familyId = item.family.id,
            familyName = item.family.name,
            requestedVersion = item.version.version,
            recipe = recipe,
            components = buildList {
                add("npm ${recipe.packageName}@${recipe.packageVersion}")
                if (item.version.version == PackageSourceCatalog.LATEST_OFFICIAL_VERSION) add("Resolved exact version: ${recipe.version}")
                recipe.registryIntegrity?.let { add("Registry integrity: ${it.substringBefore('-').uppercase()} pinned for this transaction") }
                add(if (LocalUbuntuNpmPolicy.supports(recipe)) "User-installed Node.js ${recipe.minimumNodeMajor}+ and npm 10+ in local Ubuntu" else "Node.js ${recipe.minimumNodeMajor}+")
                add("Workspace command: ${recipe.command}")
                if (recipe.allowLifecycleScripts) {
                    add(if (LocalUbuntuNpmPolicy.supports(recipe)) "Reviewed root binary selector enabled; transitive install scripts blocked" else "Reviewed npm install scripts run in Device Workstation")
                    add("Reviewed reason: ${recipe.lifecycleScriptReview}")
                } else {
                    add("Install scripts: BLOCKED (--ignore-scripts), including transitive dependency lifecycle scripts")
                }
                add("Post-install integrity manifest + health check")
            },
        )

    private fun resolveDependencies(document: ManagedPackageCatalogDocument, target: ManagedPackageCatalogEntry): List<ManagedPackageCatalogEntry> =
        ManagedPackageDependencyResolver.resolve(document, target, Build.SUPPORTED_ABIS.toList())

    suspend fun acceptLicense(plan: ExtensionInstallPlan.Android) {
        require(plan.license.sha256 == plan.toolchainEntry.sdkLicenseSha256) { "License/catalog mismatch" }
        licenseManager.accept(plan.license)
        _state.value = State(Phase.LICENSE, "Android SDK license accepted for this exact revision.", plan.familyId, plan.requestedVersion)
    }

    suspend fun acceptLicense(plan: ExtensionInstallPlan.AndroidLocalComponent) {
        licenseManager.accept(plan.license)
        _state.value = State(Phase.LICENSE, "Android SDK license accepted for this component install.", plan.familyId, plan.requestedVersion)
    }

    suspend fun install(plan: ExtensionInstallPlan): String = operationMutex.withLock {
        val operationLease = longOperationJournal.begin(
            LongRunningOperationJournal.Kind.PACKAGE,
            "${plan.familyId}@${plan.resolvedVersion}",
        )
        try {
            val localReviewed = when (plan) {
                is ExtensionInstallPlan.GitHubRelease ->
                    localReviewedRawInstaller.supports(plan.recipe) || localReviewedWholeTreeInstaller.supports(plan.recipe)
                is ExtensionInstallPlan.VendorOfficial -> localReviewedWholeTreeInstaller.supports(plan.recipe)
                else -> false
            }
            val localAppPlan = localReviewed || plan is ExtensionInstallPlan.AndroidLocalComponent ||
                (plan is ExtensionInstallPlan.Recipe && LocalUbuntuNpmPolicy.supports(plan.recipe))
            when (plan) {
                is ExtensionInstallPlan.GuestPackage -> guestPackageInstaller.requireBackend(plan.familyId)
                is ExtensionInstallPlan.Android, is ExtensionInstallPlan.AndroidLocalComponent, is ExtensionInstallPlan.Package, is ExtensionInstallPlan.Recipe -> Unit
                is ExtensionInstallPlan.GitHubRelease, is ExtensionInstallPlan.VendorOfficial -> if (!localReviewed) guestPackageInstaller.requireMixedBackend()
                else -> guestPackageInstaller.requireMixedBackend()
            }
            if (plan !is ExtensionInstallPlan.GuestPackage && !localAppPlan) ensurePackageBackend(plan.familyId, plan.requestedVersion)
            val result = when (plan) {
                is ExtensionInstallPlan.Android -> ManagedPackageMutationGate.mutex.withLock { installAndroid(plan) }
                is ExtensionInstallPlan.AndroidLocalComponent -> installAndroidLocalComponent(plan)
                is ExtensionInstallPlan.Package -> installPackage(plan)
                is ExtensionInstallPlan.GuestPackage -> installGuestPackage(plan)
                is ExtensionInstallPlan.Recipe -> installRecipe(plan)
                is ExtensionInstallPlan.PyPi -> installPyPiRecipe(plan)
                is ExtensionInstallPlan.Go -> installGoRecipe(plan)
                is ExtensionInstallPlan.RubyGems -> installRubyGemsRecipe(plan)
                is ExtensionInstallPlan.GitHubRelease -> installGitHubRelease(plan)
                is ExtensionInstallPlan.VendorOfficial -> installVendorOfficial(plan)
                is ExtensionInstallPlan.BundleProjection -> installBundleProjection(plan)
            }
            operationLease.complete("Package transaction committed")
            result
        } catch (cancelled: CancellationException) {
            operationLease.cancel("Package installation canceled")
            _state.value = State(Phase.CANCELED, "Installation canceled. No incomplete package was activated.", plan.familyId, plan.requestedVersion)
            throw cancelled
        } catch (error: Throwable) {
            operationLease.fail((error.message ?: "Package installation failed").take(300))
            _state.value = State(Phase.FAILED, error.message ?: "Installation failed", plan.familyId, plan.requestedVersion)
            throw error
        }
    }

    private suspend fun installAndroidLocalComponent(plan: ExtensionInstallPlan.AndroidLocalComponent): String {
        check(licenseManager.isAccepted(plan.license)) { "Accept the Android SDK license first" }
        _state.value = State(Phase.DOWNLOADING, "Downloading verified Android SDK component…", plan.familyId, plan.requestedVersion)
        val record = localAndroidSdkComponentInstaller.install(plan.entry)
        _state.value = State(Phase.ACTIVATING, "Verifying local Ubuntu SDK projection…", plan.familyId, plan.requestedVersion)
        check(localPackageAuthority.verify(record)) { "Installed Android SDK component failed local verification" }
        _state.value = State(Phase.READY, "Installed, integrity-checked and projected into local Ubuntu.", plan.familyId, plan.requestedVersion)
        return when (plan.entry.kind) {
            AndroidLocalComponentKind.SDK_PLATFORM -> "Installed Android SDK Platform ${plan.entry.version} for local Ubuntu."
            AndroidLocalComponentKind.BUILD_TOOLS_ARM64 -> "Installed Android Build Tools ${plan.entry.version} for local Ubuntu with pinned ARM64 native overlays."
        }
    }

    private suspend fun installAndroid(plan: ExtensionInstallPlan.Android): String {
        check(licenseManager.isAccepted(plan.license)) { "Accept the exact Android SDK license first" }
        _state.value = State(Phase.DOWNLOADING, "Downloading verified Android workstation package…", plan.familyId, plan.requestedVersion)
        val result = androidInstaller.install(plan.toolchainEntry, plan.license)
        _state.value = State(Phase.ACTIVATING, "Activating and health-checking package…", plan.familyId, plan.requestedVersion)
        val info = androidDevelopment.workstationInfo() ?: error("Installed package failed workstation health check")
        require(info.compileSdk == plan.toolchainEntry.compileSdk) { "Installed SDK does not match package plan" }
        require(info.javaVersion == plan.toolchainEntry.javaVersion) { "Installed JDK does not match package plan" }

        val root = "${DeviceBridgeManager.remoteRoot()}/toolchains/current"
        val now = System.currentTimeMillis()
        buildList {
            add(ManagedPackageRecord("sdk.android", info.compileSdk.toString(), ExecutionScope.LOCAL_LINUX_ARM64.name, root, now))
            add(ManagedPackageRecord("toolchain.jdk", info.javaVersion.toString(), ExecutionScope.LOCAL_LINUX_ARM64.name, root, now))
            info.buildToolsVersions.sorted().forEach { revision ->
                add(ManagedPackageRecord("build.android-tools", revision, ExecutionScope.LOCAL_LINUX_ARM64.name, root, now))
            }
            add(ManagedPackageRecord("meta.android-development", "SDK ${info.compileSdk}", ExecutionScope.LOCAL_LINUX_ARM64.name, root, now))
        }.forEach(registry::put)
        _state.value = State(Phase.READY, "Installed, activated and health-checked.", plan.familyId, plan.requestedVersion)
        return result
    }

    private suspend fun installPackage(plan: ExtensionInstallPlan.Package): String {
        _state.value = State(Phase.DOWNLOADING, "Installing verified package dependencies…", plan.familyId, plan.requestedVersion)
        val activeBefore = registry.list().filter { it.active }.associateBy { it.familyId }
        var installed = 0
        plan.entries.forEach { entry ->
            val existing = registry.find(entry.familyId, entry.version)
            val validExisting = existing
                ?.takeIf { it.scope == ExecutionScope.LOCAL_LINUX_ARM64.name }
                ?.let { record -> packageInstaller.verify(record) } == true
            if (!validExisting) {
                packageInstaller.install(entry)
                installed++
            } else if (existing != null && !existing.active) {
                packageInstaller.activate(existing)
            }
        }
        // Installing an exact dependency must not silently change an existing user default for that dependency family.


        plan.entries.asSequence().filter { it.familyId != plan.familyId }.map { it.familyId }.distinct().forEach { family ->
            val previous = activeBefore[family] ?: return@forEach
            val current = registry.find(previous.familyId, previous.version) ?: return@forEach
            if (!current.active) packageInstaller.activate(current)
        }
        _state.value = State(Phase.READY, "Installed, activated and available through managed/bin.", plan.familyId, plan.requestedVersion)
        return if (installed == 0) "Package and dependencies were already installed and healthy." else "Installed $installed managed package${if (installed == 1) "" else "s"}. Commands are available through managed/bin in existing and new Device Workstation terminals."
    }

    suspend fun reconcileManagedPackages(): List<ManagedPackageRecord> = packageInstaller.reconcile()
    suspend fun reconcileLocalPackages() {
        localPackageAuthority.reconcile()
        guestPackageInstaller.reconcilePendingGuestCleanup()
    }

    suspend fun verifyManagedRecord(record: ManagedPackageRecord): Boolean =
        if (localPackageAuthority.owns(record)) localPackageAuthority.verify(record) else packageInstaller.verify(record)

    suspend fun activate(item: ExtensionVersionState): String = operationMutex.withLock {
        val record = registry.find(item.family.id, item.version.version)
            ?: error("Managed package is not installed")
        require(item.version.installKind in setOf(ExtensionInstallKind.ANDROID_LOCAL_COMPONENT, ExtensionInstallKind.MANAGED_PACKAGE, ExtensionInstallKind.GUEST_PACKAGE, ExtensionInstallKind.REVIEWED_RECIPE)) { "This provider does not support version switching" }
        _state.value = State(Phase.ACTIVATING, "Activating ${item.family.name} ${item.version.version}…", item.family.id, item.version.version)
        if (localPackageAuthority.owns(record)) localPackageAuthority.activate(record) else packageInstaller.activate(record)
        _state.value = State(Phase.READY, "Version activated.", item.family.id, item.version.version)
        return "${item.family.name} ${item.version.version} is now active."
    }

    private suspend fun installRecipe(plan: ExtensionInstallPlan.Recipe): String {
        if (LocalUbuntuNpmPolicy.supports(plan.recipe)) {
            _state.value = State(Phase.INSTALLING, "Installing and verifying the original CLI and editor adapter in local Ubuntu…", plan.familyId, plan.requestedVersion)
            val record = localNpmInstaller.install(plan.recipe)
            _state.value = State(Phase.READY, "Installed in local Ubuntu. Complete the upstream login in the Linux terminal before using Agent.", plan.familyId, plan.requestedVersion)
            return "Installed ${record.familyId} ${record.version} in local Ubuntu. Commands: ${record.commands.keys.sorted().joinToString()}"
        }
        _state.value = State(Phase.INSTALLING, "Provisioning exact Node.js dependency and installing reviewed package recipe…", plan.familyId, plan.requestedVersion)
        val previousNodeDefault = registry.list().firstOrNull { it.familyId == "runtime.node" && it.active }
        val nodeDependency = ensureManagedNodeDependency(plan.recipe.minimumNodeMajor)
        return try {
            val record = recipeInstaller.install(plan.recipe, nodeDependency)
            _state.value = State(Phase.READY, "Installed, dependency-locked, integrity-checked and activated.", plan.familyId, plan.requestedVersion)
            "Installed ${record.familyId} ${record.version}."
        } finally {
            if (previousNodeDefault != null && previousNodeDefault.version != nodeDependency.version) {
                registry.find(previousNodeDefault.familyId, previousNodeDefault.version)?.let { previous ->
                    if (!previous.active) runSuspendCatching { packageInstaller.activate(previous) }
                }
            }
        }
    }


    private suspend fun installPyPiRecipe(plan: ExtensionInstallPlan.PyPi): String {
        _state.value = State(Phase.INSTALLING, "Provisioning exact Python dependency and installing hash-locked PyPI wheel closure…", plan.familyId, plan.requestedVersion)
        val previousPythonDefault = registry.list().firstOrNull { it.familyId == "runtime.python" && it.active }
        val pythonDependency = ensureManagedPythonDependency(plan.recipe.minimumPythonMajor, plan.recipe.minimumPythonMinor)
        return try {
            val record = pypiRecipeInstaller.install(plan.recipe, pythonDependency)
            _state.value = State(Phase.READY, "Installed, dependency-locked, integrity-checked and activated.", plan.familyId, plan.requestedVersion)
            "Installed ${record.familyId} ${record.version}."
        } finally {
            if (previousPythonDefault != null && previousPythonDefault.version != pythonDependency.version) {
                registry.find(previousPythonDefault.familyId, previousPythonDefault.version)?.let { previous ->
                    if (!previous.active) runSuspendCatching { packageInstaller.activate(previous) }
                }
            }
        }
    }

    private suspend fun installGoRecipe(plan: ExtensionInstallPlan.Go): String {
        _state.value = State(Phase.INSTALLING, "Provisioning exact Go dependency and installing checksum-authenticated Go command…", plan.familyId, plan.requestedVersion)
        val previousGoDefault = registry.list().firstOrNull { it.familyId == "toolchain.go" && it.active }
        val goDependency = ensureManagedGoDependency(plan.recipe.minimumGoMajor, plan.recipe.minimumGoMinor)
        return try {
            val record = goRecipeInstaller.install(plan.recipe, goDependency)
            _state.value = State(Phase.READY, "Installed, sumdb-authenticated, integrity-checked and activated.", plan.familyId, plan.requestedVersion)
            "Installed ${record.familyId} ${record.version}."
        } finally {
            if (previousGoDefault != null && previousGoDefault.version != goDependency.version) {
                registry.find(previousGoDefault.familyId, previousGoDefault.version)?.let { previous ->
                    if (!previous.active) runSuspendCatching { packageInstaller.activate(previous) }
                }
            }
        }
    }

    private suspend fun installRubyGemsRecipe(plan: ExtensionInstallPlan.RubyGems): String {
        _state.value = State(Phase.INSTALLING, "Provisioning Ruby/Bundler and installing checksum-locked RubyGems closure…", plan.familyId, plan.requestedVersion)
        val ruby = ensureGuestCommandDependency("runtime.ruby", "ruby").record
        val bundler = ensureGuestCommandDependency("package.bundler", "bundle").record
        val nativeBuild = ensureGuestCommandDependency("toolchain.ruby-native-build", "gcc").record
        val record = rubyGemsRecipeInstaller.install(plan.recipe, ruby, bundler, nativeBuild)
        _state.value = State(Phase.READY, "Installed, lock-checksummed, offline-installed and activated.", plan.familyId, plan.requestedVersion)
        return "Installed ${record.familyId} ${record.version}."
    }

    private suspend fun installGitHubRelease(plan: ExtensionInstallPlan.GitHubRelease): String {
        _state.value = State(Phase.DOWNLOADING, "Downloading exact GitHub release asset and verifying SHA-256…", plan.familyId, plan.requestedVersion)
        val record = when {
            localReviewedRawInstaller.supports(plan.recipe) -> {
                _state.value = State(Phase.INSTALLING, "Installing verified Linux/ARM64 ELF into local Ubuntu…", plan.familyId, plan.recipe.version)
                localReviewedRawInstaller.install(plan.recipe)
            }
            localReviewedWholeTreeInstaller.supports(plan.recipe) -> {
                _state.value = State(Phase.INSTALLING, "Extracting and atomically activating reviewed Linux/ARM64 tree in local Ubuntu…", plan.familyId, plan.recipe.version)
                localReviewedWholeTreeInstaller.install(plan.recipe)
            }
            else -> artifactInstallCoordinator.install(plan.recipe, ::ensureGuestCommandDependency)
        }
        _state.value = State(Phase.READY, "Installed, SHA-256 verified, health-checked and activated.", plan.familyId, plan.requestedVersion)
        return "Installed ${record.familyId} ${record.version} from ${plan.recipe.repository} ${plan.recipe.releaseTag}."
    }

    private suspend fun installVendorOfficial(plan: ExtensionInstallPlan.VendorOfficial): String {
        _state.value = State(Phase.DOWNLOADING, "Downloading exact official-vendor artifact and verifying publisher SHA-256…", plan.familyId, plan.requestedVersion)
        val record = if (localReviewedWholeTreeInstaller.supports(plan.recipe)) {
            _state.value = State(Phase.INSTALLING, "Extracting and atomically activating reviewed vendor tree in local Ubuntu…", plan.familyId, plan.recipe.version)
            localReviewedWholeTreeInstaller.install(plan.recipe)
        } else {
            artifactInstallCoordinator.install(plan.recipe, ::ensureGuestCommandDependency)
        }
        _state.value = State(Phase.READY, "Installed, publisher-SHA-256 verified, health-checked and activated.", plan.familyId, plan.requestedVersion)
        return "Installed ${record.familyId} ${record.version} from reviewed vendor authority."
    }

    private suspend fun installBundleProjection(plan: ExtensionInstallPlan.BundleProjection): String {
        _state.value = State(Phase.INSTALLING, "Ensuring shared bundle primary and creating lightweight capability projection…", plan.familyId, plan.requestedVersion)
        val record = bundleProjectionCoordinator.install(plan, ::ensureGuestCommandDependency)
        _state.value = State(Phase.READY, "Installed as a dependency-safe projection of ${plan.primaryFamilyId}; shared SDK bytes were not duplicated.", plan.familyId, record.version)
        return "Installed ${record.familyId} ${record.version} as a shared ${plan.bundleId} capability projection."
    }

    private suspend fun ensureGuestCommandDependency(familyId: String, command: String): GitHubArtifactInterpreterDependency {
        val candidates = WorkstationGuestPackageCatalog.forFamily(familyId).filter { command in it.commands }
        require(candidates.isNotEmpty()) { "No reviewed managed interpreter provider is available for $familyId/$command" }
        var lastFailure: Throwable? = null
        for (recipe in candidates) {
            val existing = registry.find(recipe.familyId, recipe.version)
            val record = try {
                if (existing != null && packageInstaller.verify(existing)) existing else guestPackageInstaller.install(recipe)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Error) {
                throw fatal
            } catch (failure: Exception) {
                lastFailure = failure
                continue
            }
            val guestExecutable = requireNotNull(recipe.commands[command])
            return GitHubArtifactInterpreterDependency(record, guestExecutable)
        }
        throw IllegalStateException("Could not provision reviewed managed interpreter $familyId/$command", lastFailure)
    }

    private suspend fun ensureManagedGoDependency(minimumMajor: Int, minimumMinor: Int): ManagedPackageRecord {
        val candidates = WorkstationGuestPackageCatalog.forFamily("toolchain.go")
            .filter { "go" in it.commands }
        require(candidates.isNotEmpty()) { "No reviewed managed Go provider is available" }
        var lastFailure: Throwable? = null
        for (recipe in candidates) {
            val existing = registry.find(recipe.familyId, recipe.version)
            val record = try {
                if (existing != null && packageInstaller.verify(existing)) existing
                else guestPackageInstaller.install(recipe)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Error) {
                throw fatal
            } catch (failure: Exception) {
                lastFailure = failure
                continue
            }
            val goVersion = goRecipeInstaller.probeGoVersion(record)
            if (goVersion != null && (goVersion.first > minimumMajor || goVersion.first == minimumMajor && goVersion.second >= minimumMinor)) return record
            lastFailure = IllegalStateException("Managed Go ${record.version} does not satisfy Go $minimumMajor.$minimumMinor+")
        }
        throw IllegalStateException("Could not provision a reviewed managed Go $minimumMajor.$minimumMinor+ dependency", lastFailure)
    }

    private suspend fun ensureManagedPythonDependency(minimumMajor: Int, minimumMinor: Int): ManagedPackageRecord {
        val candidates = WorkstationGuestPackageCatalog.forFamily("runtime.python")
            .filter { "python3" in it.commands && ("pip3" in it.commands || "pip" in it.commands) }
        require(candidates.isNotEmpty()) { "No reviewed managed Python provider is available" }
        var lastFailure: Throwable? = null
        for (recipe in candidates) {
            val existing = registry.find(recipe.familyId, recipe.version)
            val record = try {
                if (existing != null && packageInstaller.verify(existing)) existing
                else guestPackageInstaller.install(recipe)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Error) {
                throw fatal
            } catch (failure: Exception) {
                lastFailure = failure
                continue
            }
            val python = pypiRecipeInstaller.probePythonVersion(record)
            if (python != null && (python.first > minimumMajor || python.first == minimumMajor && python.second >= minimumMinor)) return record
            lastFailure = IllegalStateException("Managed Python ${record.version} does not satisfy Python $minimumMajor.$minimumMinor+")
        }
        throw IllegalStateException("Could not provision a reviewed managed Python $minimumMajor.$minimumMinor+ dependency", lastFailure)
    }

    private suspend fun ensureManagedNodeDependency(minimumMajor: Int): ManagedPackageRecord {
        val candidates = WorkstationGuestPackageCatalog.forFamily("runtime.node")
            .filter { "node" in it.commands && "npm" in it.commands }
        require(candidates.isNotEmpty()) { "No reviewed managed Node.js provider is available" }
        var lastFailure: Throwable? = null
        for (recipe in candidates) {
            val existing = registry.find(recipe.familyId, recipe.version)
            val record = try {
                if (existing != null && packageInstaller.verify(existing)) existing
                else guestPackageInstaller.install(recipe)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Error) {
                throw fatal
            } catch (failure: Exception) {
                lastFailure = failure
                continue
            }
            val nodeMajor = recipeInstaller.probeNodeMajor(record)
            if (nodeMajor != null && nodeMajor >= minimumMajor) return record
            lastFailure = IllegalStateException("Managed Node.js ${record.version} does not satisfy Node.js $minimumMajor+")
        }
        throw IllegalStateException("Could not provision a reviewed managed Node.js $minimumMajor+ dependency", lastFailure)
    }

    private suspend fun ensurePackageBackend(familyId: String, version: String) {
        if (bridge.state.value.connected != null) return
        _state.value = State(Phase.RESOLVING, "Reconnecting paired Device Workstation…", familyId, version)
        bridge.ensurePackageBackend()
    }

    private suspend fun installGuestPackage(plan: ExtensionInstallPlan.GuestPackage): String {
        _state.value = State(Phase.INSTALLING, "Bootstrapping verified Linux environment and installing Alpine packages…", plan.familyId, plan.requestedVersion)
        val record = guestPackageInstaller.install(plan.recipe)
        _state.value = State(Phase.READY, "Installed, integrity-checked and activated through app-local managed/bin.", plan.familyId, plan.requestedVersion)
        return "Installed ${record.familyId} ${record.version} in the app-local rootless Alpine guest."
    }

    suspend fun repair(item: ExtensionVersionState): String = operationMutex.withLock {
        require(item.state == ExtensionState.INSTALLED) { "Only installed packages can be repaired" }
        _state.value = State(Phase.REPAIRING, "Repairing ${item.family.name} ${item.version.version}…", item.family.id, item.version.version)
        return try {
            val localReviewedSource = sourceCatalog.installable(item.family.id)?.takeIf(LocalUbuntuReviewedArtifactPolicy::supports)
            if (item.version.installKind == ExtensionInstallKind.GUEST_PACKAGE ||
                (item.version.installKind == ExtensionInstallKind.REVIEWED_RECIPE &&
                    WorkstationInstallRecipeCatalog.find(item.family.id, item.version.version) == null &&
                    sourceCatalog.installable(item.family.id)?.source != PackageSourceKind.NPM &&
                    localReviewedSource == null)) {
                if (item.version.installKind == ExtensionInstallKind.GUEST_PACKAGE) guestPackageInstaller.requireBackend(item.family.id)
                else guestPackageInstaller.requireMixedBackend()
            }
            val localAppKind = item.version.installKind == ExtensionInstallKind.ANDROID_LOCAL_COMPONENT ||
                (item.version.installKind == ExtensionInstallKind.REVIEWED_RECIPE &&
                    WorkstationInstallRecipeCatalog.find(item.family.id, item.version.version)?.let(LocalUbuntuNpmPolicy::supports) == true)
            if (item.version.installKind != ExtensionInstallKind.GUEST_PACKAGE && localReviewedSource == null && !localAppKind) {
                ensurePackageBackend(item.family.id, item.version.version)
            }
            val result = when (item.version.installKind) {
                ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN -> {
                    ManagedPackageMutationGate.mutex.withLock { androidDevelopment.repairToolchain() }
                }
                ExtensionInstallKind.ANDROID_LOCAL_COMPONENT -> {
                    val catalog = AndroidLocalComponentCatalog.load(appContext)
                    val entry = AndroidLocalComponentCatalog.find(catalog, item.family.id, item.version.version)
                        ?: error("Pinned local Android SDK component is missing for repair")
                    val activeBefore = registry.list().firstOrNull { it.familyId == item.family.id && it.active }
                    val record = localAndroidSdkComponentInstaller.install(entry)
                    check(localPackageAuthority.verify(record)) { "Repaired Android SDK component failed local verification" }
                    if (activeBefore != null && activeBefore.version != item.version.version) {
                        registry.find(activeBefore.familyId, activeBefore.version)?.let { previous ->
                            check(localPackageAuthority.owns(previous)) { "Previous Android SDK component changed backend during repair" }
                            localPackageAuthority.activate(previous)
                        }
                    }
                    "Reinstalled and integrity-checked ${item.family.name} ${item.version.version} in local Ubuntu."
                }
                ExtensionInstallKind.REVIEWED_RECIPE -> {
                    val bundleProjection = prepareBundleProjection(item)
                    if (bundleProjection != null) {
                        installBundleProjection(bundleProjection)
                        "Reinstalled and health-checked ${item.family.name} through its shared bundle primary."
                    } else {
                    val staticNpm = WorkstationInstallRecipeCatalog.find(item.family.id, item.version.version)
                    val source = sourceCatalog.installable(item.family.id)
                    when {
                        staticNpm != null || source?.source == PackageSourceKind.NPM -> {
                            val recipe = staticNpm ?: sourceResolver.resolveReviewedNpm(item.family.id, item.version.version)
                            if (LocalUbuntuNpmPolicy.supports(recipe)) {
                                localNpmInstaller.install(recipe)
                            } else {
                                val previousNodeDefault = registry.list().firstOrNull { it.familyId == "runtime.node" && it.active }
                                val nodeDependency = ensureManagedNodeDependency(recipe.minimumNodeMajor)
                                try {
                                    recipeInstaller.install(recipe, nodeDependency)
                                } finally {
                                    if (previousNodeDefault != null && previousNodeDefault.version != nodeDependency.version) {
                                        registry.find(previousNodeDefault.familyId, previousNodeDefault.version)?.let { previous ->
                                            if (!previous.active) runSuspendCatching { packageInstaller.activate(previous) }
                                        }
                                    }
                                }
                            }
                        }
                        source?.source == PackageSourceKind.PYPI -> {
                            val recipe = sourceResolver.resolveReviewedPyPi(item.family.id, item.version.version)
                            val previousPythonDefault = registry.list().firstOrNull { it.familyId == "runtime.python" && it.active }
                            val pythonDependency = ensureManagedPythonDependency(recipe.minimumPythonMajor, recipe.minimumPythonMinor)
                            try {
                                pypiRecipeInstaller.install(recipe, pythonDependency)
                            } finally {
                                if (previousPythonDefault != null && previousPythonDefault.version != pythonDependency.version) {
                                    registry.find(previousPythonDefault.familyId, previousPythonDefault.version)?.let { previous ->
                                        if (!previous.active) runSuspendCatching { packageInstaller.activate(previous) }
                                    }
                                }
                            }
                        }
                        source?.source == PackageSourceKind.GO -> {
                            val recipe = sourceResolver.resolveReviewedGo(item.family.id, item.version.version)
                            val previousGoDefault = registry.list().firstOrNull { it.familyId == "toolchain.go" && it.active }
                            val goDependency = ensureManagedGoDependency(recipe.minimumGoMajor, recipe.minimumGoMinor)
                            try {
                                goRecipeInstaller.install(recipe, goDependency)
                            } finally {
                                if (previousGoDefault != null && previousGoDefault.version != goDependency.version) {
                                    registry.find(previousGoDefault.familyId, previousGoDefault.version)?.let { previous ->
                                        if (!previous.active) runSuspendCatching { packageInstaller.activate(previous) }
                                    }
                                }
                            }
                        }
                        source?.source == PackageSourceKind.RUBYGEMS -> {
                            val recipe = sourceResolver.resolveReviewedRubyGems(item.family.id, item.version.version)
                            registry.find(item.family.id, item.version.version)?.artifactSha256?.let { installedDigest ->
                                require(installedDigest == recipe.rootSha256) {
                                    "Upstream RubyGems bytes changed for the installed version; Repair is blocked to preserve reproducibility. Uninstall and explicitly reinstall to accept new bytes."
                                }
                            }
                            val ruby = ensureGuestCommandDependency("runtime.ruby", "ruby").record
                            val bundler = ensureGuestCommandDependency("package.bundler", "bundle").record
                            val nativeBuild = ensureGuestCommandDependency("toolchain.ruby-native-build", "gcc").record
                            rubyGemsRecipeInstaller.install(recipe, ruby, bundler, nativeBuild)
                        }
                        source?.source == PackageSourceKind.GITHUB_RELEASE -> {
                            val recipe = sourceResolver.resolveReviewedGitHubRelease(item.family.id, item.version.version)
                            registry.find(item.family.id, item.version.version)?.artifactSha256?.let { installedDigest ->
                                require(installedDigest == recipe.assetSha256) {
                                    "Upstream GitHub release asset changed for the installed version; Repair is blocked to preserve reproducibility. Uninstall and explicitly reinstall to accept new bytes."
                                }
                            }
                            when {
                                localReviewedRawInstaller.supports(recipe) -> localReviewedRawInstaller.install(recipe)
                                localReviewedWholeTreeInstaller.supports(recipe) -> localReviewedWholeTreeInstaller.install(recipe)
                                else -> artifactInstallCoordinator.install(recipe, ::ensureGuestCommandDependency)
                            }
                        }
                        source?.source == PackageSourceKind.VENDOR_OFFICIAL -> {
                            val recipe = sourceResolver.resolveReviewedVendorOfficial(item.family.id, item.version.version)
                            registry.find(item.family.id, item.version.version)?.artifactSha256?.let { installedDigest ->
                                require(installedDigest == recipe.assetSha256) {
                                    "Upstream vendor artifact changed for the installed version; Repair is blocked to preserve reproducibility. Uninstall and explicitly reinstall to accept new bytes."
                                }
                            }
                            if (localReviewedWholeTreeInstaller.supports(recipe)) localReviewedWholeTreeInstaller.install(recipe)
                            else artifactInstallCoordinator.install(recipe, ::ensureGuestCommandDependency)
                        }
                        else -> error("No certified repair transaction owns ${item.family.name} ${item.version.version}")
                    }
                    "Reinstalled and health-checked ${item.family.name} ${item.version.version}."
                    }
                }
                ExtensionInstallKind.GUEST_PACKAGE -> {
                    val recipe = WorkstationGuestPackageCatalog.find(item.family.id, item.version.version)
                        ?: error("Reviewed Alpine repair recipe is missing")
                    guestPackageInstaller.repair(recipe, registry.find(item.family.id, item.version.version))
                    "Reinstalled and health-checked ${item.family.name} ${item.version.version} inside the rootless guest."
                }
                ExtensionInstallKind.MANAGED_PACKAGE -> {
                    val catalog = ManagedPackageCatalog.load(appContext)
                    val entry = ManagedPackageCatalog.select(catalog, item.family.id, item.version.version, Build.SUPPORTED_ABIS.toList())
                        ?: error("No verified repair artifact is pinned for ${item.family.name} ${item.version.version}")
                    val activeBefore = registry.list().firstOrNull { it.familyId == item.family.id && it.active }
                    val resolved = resolveDependencies(catalog, entry)
                    for (dependency in resolved) {
                        val existing = registry.find(dependency.familyId, dependency.version)
                        val healthy = existing?.let { packageInstaller.verify(it) } == true
                        if (!healthy || dependency.familyId == item.family.id && dependency.version == item.version.version) {
                            packageInstaller.install(dependency)
                        }
                    }
                    if (activeBefore != null && activeBefore.version != item.version.version) {
                        registry.find(activeBefore.familyId, activeBefore.version)?.let { packageInstaller.activate(it) }
                    }
                    "Reinstalled dependencies and health-checked ${item.family.name} ${item.version.version}."
                }
                else -> error("This extension is not managed by an installable provider")
            }
            _state.value = State(Phase.READY, "Repair completed.", item.family.id, item.version.version)
            result
        } catch (cancelled: CancellationException) {
            _state.value = State(Phase.CANCELED, "Repair canceled. Existing healthy package state was preserved where possible.", item.family.id, item.version.version)
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (error: Exception) {
            _state.value = State(Phase.FAILED, error.message ?: "Repair failed", item.family.id, item.version.version)
            throw error
        }
    }

    suspend fun uninstall(item: ExtensionVersionState): String = operationMutex.withLock {
        require(item.state == ExtensionState.INSTALLED) { "Only installed packages can be uninstalled" }
        _state.value = State(Phase.REMOVING,
            if (item.version.installKind == ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN) "Removing entire managed Android JDK/SDK/Build Tools pack…"
            else "Uninstalling ${item.family.name} ${item.version.version}…", item.family.id, item.version.version)
        return try {
            val localReviewedSource = sourceCatalog.installable(item.family.id)?.takeIf(LocalUbuntuReviewedArtifactPolicy::supports)
            if (item.version.installKind == ExtensionInstallKind.GUEST_PACKAGE ||
                (item.version.installKind == ExtensionInstallKind.REVIEWED_RECIPE &&
                    WorkstationInstallRecipeCatalog.find(item.family.id, item.version.version) == null &&
                    sourceCatalog.installable(item.family.id)?.source != PackageSourceKind.NPM &&
                    localReviewedSource == null)) {
                if (item.version.installKind == ExtensionInstallKind.GUEST_PACKAGE) guestPackageInstaller.requireBackend(item.family.id)
                else guestPackageInstaller.requireMixedBackend()
            }
            val localAppKind = item.version.installKind == ExtensionInstallKind.ANDROID_LOCAL_COMPONENT ||
                registry.find(item.family.id, item.version.version)?.let(localPackageAuthority::owns) == true
            if (item.version.installKind != ExtensionInstallKind.GUEST_PACKAGE && localReviewedSource == null && !localAppKind) {
                ensurePackageBackend(item.family.id, item.version.version)
            }
            val result = when (item.version.installKind) {
                ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN -> {
                    ManagedPackageMutationGate.mutex.withLock {
                        
                        val result = androidDevelopment.removeToolchain()
                        registry.replaceAll(
                            registry.list().filterNot {
                                it.familyId in setOf("sdk.android", "toolchain.jdk", "build.android-tools", "meta.android-development")
                            }
                        )
                        result
                    }
                }
                ExtensionInstallKind.ANDROID_LOCAL_COMPONENT -> {
                    val record = registry.find(item.family.id, item.version.version)
                        ?: error("Local Android SDK component receipt is missing")
                    check(localPackageAuthority.owns(record)) { "Local Android SDK component belongs to a different backend" }
                    localPackageAuthority.uninstall(record)
                }
                ExtensionInstallKind.MANAGED_PACKAGE -> {
                    val record = registry.find(item.family.id, item.version.version)
                        ?: error("Managed package record is missing")
                    packageInstaller.uninstall(record)
                }
                ExtensionInstallKind.REVIEWED_RECIPE -> {
                    val record = registry.find(item.family.id, item.version.version)
                        ?: error("Managed package record is missing")
                    val dependenciesBefore = record.dependencies.toList()
                    val source = sourceCatalog.entries.firstOrNull { it.familyId == item.family.id }
                    val localOwned = localPackageAuthority.owns(record)
                    val result = if (localOwned) {
                        localPackageAuthority.uninstall(record)
                    } else {
                        packageInstaller.uninstall(record)
                    }
                    (source?.githubRuntimeCompatibility ?: source?.vendorRuntimeCompatibility)?.let { runtime ->
                        if (localOwned) cleanupOrphanLocalUbuntuDependencies(runtime, dependenciesBefore)
                        else reviewedArtifactBackend.cleanupOrphanDependencies(runtime, dependenciesBefore)
                    }
                    result
                }
                ExtensionInstallKind.GUEST_PACKAGE -> {
                    val recipe = WorkstationGuestPackageCatalog.find(item.family.id, item.version.version)
                        ?: error("Reviewed Alpine uninstall recipe is missing")
                    val record = registry.find(item.family.id, item.version.version)
                        ?: error("Managed package record is missing")
                    guestPackageInstaller.uninstall(recipe, record)
                }
                else -> error("This extension is not managed by an installable provider")
            }
            _state.value = State(Phase.READY, "Uninstall completed.", item.family.id, item.version.version)
            result
        } catch (cancelled: CancellationException) {
            _state.value = State(Phase.CANCELED, "Uninstall canceled. Package state was not reported as successfully removed.", item.family.id, item.version.version)
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (error: Exception) {
            _state.value = State(Phase.FAILED, error.message ?: "Uninstall failed", item.family.id, item.version.version)
            throw error
        }
    }

    private suspend fun cleanupOrphanLocalUbuntuDependencies(
        contract: DeclarativeRuntimeCompatibility,
        dependencyKeys: List<String>,
    ) {
        if (contract.guestProfile != DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64) return
        val expected = localUbuntuEnvironment.layerRecordKey(contract.requiredGuestPackages) ?: return
        if (expected !in dependencyKeys || registry.list().any { expected in it.dependencies }) return
        val familyId = expected.substringBeforeLast('@')
        val version = expected.substringAfterLast('@')
        val layer = registry.find(familyId, version) ?: return
        if (!localPackageAuthority.owns(layer)) return

        val protectedSets = registry.list().filter { candidate ->
            candidate.familyId != familyId &&
                candidate.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND] == FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND
        }.mapNotNull { candidate ->
            candidate.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES]
                ?.split(',')?.filter(String::isNotBlank)
        }

        // The durable ownership anchor is removed first. If optional physical reclaim fails,
        // extra guest packages are preserved rather than risking another package's runtime.
        localPackageAuthority.uninstall(layer)
        runSuspendCatching {
            localUbuntuEnvironment.releaseLayer(
                packages = contract.requiredGuestPackages,
                protectedPackageSets = protectedSets,
                removeWholeEnvironment = false,
            )
        }
    }

    fun reset() { _state.value = State() }
}

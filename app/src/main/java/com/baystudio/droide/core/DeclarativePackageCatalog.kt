package com.baystudio.droide.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json


@Serializable
data class DeclarativePackageCatalogDocument(
    val schemaVersion: Int,
    val revision: String,
    val packagesSha256: String,
    val packages: List<DeclarativePackageManifest>,
)

@Serializable
enum class DeclarativePackageCertification { CERTIFIED, PENDING }

@Serializable
data class DeclarativeRuntimeRequirements(
    val minimumNodeMajor: Int? = null,
    val minimumPythonMajor: Int? = null,
    val minimumPythonMinor: Int? = null,
)

@Serializable
data class DeclarativeGoSource(
    val modulePath: String,
)

@Serializable
enum class DeclarativeGitHubArtifactFormat { TAR_GZ, TGZ, ZIP, VSIX, RAW, PHAR }

@Serializable
enum class DeclarativeGitHubArtifactLayout { SINGLE_COMMAND, WHOLE_TREE }

@Serializable
enum class DeclarativeWholeTreeLinkPolicy { REJECT, SAFE_RELATIVE }

@Serializable
enum class DeclarativeWholeTreeModePolicy { EXPLICIT_ALLOWLIST, PINNED_ARCHIVE }

@Serializable
enum class DeclarativeVendorVersionStrategy { PINNED, TEXT_ENDPOINT, GITHUB_RELEASE, GITHUB_RELEASE_MAX_SEMVER }

@Serializable
data class DeclarativeVendorOfficialSource(
    val versionStrategy: DeclarativeVendorVersionStrategy,
    val pinnedVersion: String? = null,
    val pinnedSha256: String? = null,
    val versionUrl: String? = null,
    val versionGithubRepository: String? = null,
    val tagPrefix: String = "v",
    val artifactUrlTemplate: String,
    val checksumUrlTemplate: String? = null,
    val artifactFormat: DeclarativeGitHubArtifactFormat,
    val artifactLayout: DeclarativeGitHubArtifactLayout = DeclarativeGitHubArtifactLayout.SINGLE_COMMAND,
    val executableBasename: String,
    val interpreterFamilyId: String? = null,
    val interpreterCommand: String? = null,
    val archiveMemberPath: String? = null,
    val archiveMaxFiles: Int? = null,
    val archiveMaxUnpackedBytes: Long? = null,
    val treeEntryPoint: String? = null,
    val treeExecutablePaths: List<String> = emptyList(),
    val treeAuxCommands: Map<String, String> = emptyMap(),
    val treeMaxFiles: Int? = null,
    val treeMaxUnpackedBytes: Long? = null,
    val treeLinkPolicy: DeclarativeWholeTreeLinkPolicy = DeclarativeWholeTreeLinkPolicy.REJECT,
    val treeModePolicy: DeclarativeWholeTreeModePolicy = DeclarativeWholeTreeModePolicy.EXPLICIT_ALLOWLIST,
    val assetMaxBytes: Long,
    val runtimeCompatibility: DeclarativeRuntimeCompatibility,
)

@Serializable
data class DeclarativeGitHubReleaseSource(
    val tagPrefix: String = "v",
    val assetTokens: List<String>,
    val exactAssetName: String? = null,
    val assetNameTemplate: String? = null,
    val artifactFormat: DeclarativeGitHubArtifactFormat = DeclarativeGitHubArtifactFormat.TAR_GZ,
    val artifactLayout: DeclarativeGitHubArtifactLayout = DeclarativeGitHubArtifactLayout.SINGLE_COMMAND,
    val executableBasename: String? = null,
    val treeEntryPoint: String? = null,
    val treeExecutablePaths: List<String> = emptyList(),
    val treeMaxFiles: Int? = null,
    val treeMaxUnpackedBytes: Long? = null,
    val interpreterFamilyId: String? = null,
    val interpreterCommand: String? = null,
    val interpreterArgs: List<String> = emptyList(),
    val assetMaxBytes: Long,
    val runtimeCompatibility: DeclarativeRuntimeCompatibility? = null,
)

@Serializable
data class DeclarativeVersionPolicy(
    val allowLatest: Boolean = true,
    val sideBySide: Boolean = true,
    val workspaceSelectable: Boolean = true,
    val versionScopedUninstall: Boolean = true,
)

@Serializable
data class DeclarativePackageBundle(
    val id: String,
    val primaryFamilyId: String,
)

@Serializable
data class DeclarativePackageManifest(
    val familyId: String,
    val provider: PackageSourceKind,
    val packageName: String,
    val command: String,
    val provenanceUrl: String,
    val certification: DeclarativePackageCertification,
    val requirements: DeclarativeRuntimeRequirements = DeclarativeRuntimeRequirements(),
    val go: DeclarativeGoSource? = null,
    val githubRelease: DeclarativeGitHubReleaseSource? = null,
    val vendorOfficial: DeclarativeVendorOfficialSource? = null,
    val healthArgs: List<String> = listOf("--version"),
    val supportedEnvironments: List<String> = listOf("linux/arm64"),
    val versionPolicy: DeclarativeVersionPolicy = DeclarativeVersionPolicy(),
    val bundle: DeclarativePackageBundle? = null,
    val debugAdapter: DeclarativeDebugAdapterCapability? = null,
)

// Package manifests never get to enable a provider engine by themselves.
data class UniversalPackageProviderContract(
    val kind: PackageSourceKind,
    val runtimeCertified: Boolean,
    val exactVersionResolution: Boolean,
    val integrityBound: Boolean,
    val transactionalActivation: Boolean,
    val sideBySideCapable: Boolean,
)

object UniversalPackageProviderRegistry {
    private val contracts = listOf(
        UniversalPackageProviderContract(PackageSourceKind.NPM, true, true, true, true, true),
        UniversalPackageProviderContract(PackageSourceKind.PYPI, true, true, true, true, true),
        UniversalPackageProviderContract(PackageSourceKind.GO, true, true, true, true, true),
        UniversalPackageProviderContract(PackageSourceKind.GITHUB_RELEASE, true, true, true, true, true),
        UniversalPackageProviderContract(PackageSourceKind.CARGO, false, true, true, true, true),
        UniversalPackageProviderContract(PackageSourceKind.RUBYGEMS, true, true, true, true, true),
        UniversalPackageProviderContract(PackageSourceKind.COMPOSER, false, true, true, true, true),
        UniversalPackageProviderContract(PackageSourceKind.ANDROID_SDK, false, true, true, true, true),
        UniversalPackageProviderContract(PackageSourceKind.VENDOR_OFFICIAL, true, true, true, true, true),
    ).associateBy { it.kind }

    fun contract(kind: PackageSourceKind): UniversalPackageProviderContract =
        contracts[kind] ?: error("Missing provider contract for ${kind.name}")

    fun all(): List<UniversalPackageProviderContract> = contracts.values.sortedBy { it.kind.name }
}

class PackageSourceCatalog private constructor(
    val revision: String,
    val packagesSha256: String,
    val manifests: List<DeclarativePackageManifest>,
    val entries: List<PackageSourceDefinition>,
) {
    companion object {
        const val LATEST_OFFICIAL_VERSION = "Latest official"
        private const val ASSET_NAME = "droide-package-catalog-v1.json"
        private val json = Json { ignoreUnknownKeys = false }

        fun load(context: Context): PackageSourceCatalog {
            val raw = context.applicationContext.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            val document = json.decodeFromString<DeclarativePackageCatalogDocument>(raw)
            return fromDocument(document)
        }

        internal fun fromDocument(document: DeclarativePackageCatalogDocument): PackageSourceCatalog {
            require(document.schemaVersion == 1) { "Unsupported package catalog schema" }
            require(document.revision.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "Invalid package catalog revision" }
            require(document.packagesSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid package catalog digest" }
            require(document.packages.isNotEmpty()) { "Package catalog is empty" }
            require(document.packages.map { it.familyId }.distinct().size == document.packages.size) { "Duplicate package source family" }

            val entries = document.packages.map(::project)
            validateBundles(document.packages)
            return PackageSourceCatalog(document.revision, document.packagesSha256, document.packages, entries)
        }

        private fun project(manifest: DeclarativePackageManifest): PackageSourceDefinition {
            require(manifest.supportedEnvironments == listOf("linux/arm64")) {
                "${manifest.familyId}: unsupported catalog environment"
            }
            val provider = UniversalPackageProviderRegistry.contract(manifest.provider)
            val githubRuntime = manifest.githubRelease?.runtimeCompatibility
            val vendorRuntime = manifest.vendorOfficial?.runtimeCompatibility
            githubRuntime?.validate()
            vendorRuntime?.validate()
            manifest.debugAdapter?.validate()
            val runtimeContract = when (manifest.provider) {
                PackageSourceKind.GITHUB_RELEASE -> githubRuntime
                PackageSourceKind.VENDOR_OFFICIAL -> vendorRuntime
                else -> null
            }
            val runtimeReady = manifest.provider !in setOf(PackageSourceKind.GITHUB_RELEASE, PackageSourceKind.VENDOR_OFFICIAL) ||
                (runtimeContract?.genericInstallerReady() == true && PackageRuntimeDependencyPolicy.isResolvable(runtimeContract))
            val protocolReady = manifest.provider !in setOf(PackageSourceKind.GITHUB_RELEASE, PackageSourceKind.VENDOR_OFFICIAL) ||
                PackageProtocolCapabilityPolicy.installerReady(manifest)
            val ready = manifest.certification == DeclarativePackageCertification.CERTIFIED && provider.runtimeCertified && runtimeReady && protocolReady
            require(!ready || provider.exactVersionResolution && provider.integrityBound && provider.transactionalActivation) {
                "${manifest.familyId}: certified provider contract is incomplete"
            }
            val github = manifest.githubRelease
            val vendor = manifest.vendorOfficial
            return PackageSourceDefinition(
                familyId = manifest.familyId,
                source = manifest.provider,
                packageName = manifest.packageName,
                command = manifest.command,
                provenanceUrl = manifest.provenanceUrl,
                installerReady = ready,
                minimumNodeMajor = manifest.requirements.minimumNodeMajor,
                minimumPythonMajor = manifest.requirements.minimumPythonMajor,
                minimumPythonMinor = manifest.requirements.minimumPythonMinor,
                goModulePath = manifest.go?.modulePath,
                githubTagPrefix = github?.tagPrefix,
                githubAssetTokens = github?.assetTokens.orEmpty(),
                githubExactAssetName = github?.exactAssetName,
                githubAssetNameTemplate = github?.assetNameTemplate,
                githubArtifactFormat = github?.artifactFormat,
                githubArtifactLayout = github?.artifactLayout,
                githubExecutableBasename = github?.executableBasename,
                githubTreeEntryPoint = github?.treeEntryPoint,
                githubTreeExecutablePaths = github?.treeExecutablePaths.orEmpty(),
                githubTreeMaxFiles = github?.treeMaxFiles,
                githubTreeMaxUnpackedBytes = github?.treeMaxUnpackedBytes,
                githubInterpreterFamilyId = github?.interpreterFamilyId,
                githubInterpreterCommand = github?.interpreterCommand,
                githubInterpreterArgs = github?.interpreterArgs.orEmpty(),
                githubAssetMaxBytes = github?.assetMaxBytes,
                githubRuntimeCompatibility = github?.runtimeCompatibility,
                vendorVersionStrategy = vendor?.versionStrategy,
                vendorPinnedVersion = vendor?.pinnedVersion,
                vendorPinnedSha256 = vendor?.pinnedSha256,
                vendorVersionUrl = vendor?.versionUrl,
                vendorVersionGithubRepository = vendor?.versionGithubRepository,
                vendorTagPrefix = vendor?.tagPrefix,
                vendorArtifactUrlTemplate = vendor?.artifactUrlTemplate,
                vendorChecksumUrlTemplate = vendor?.checksumUrlTemplate,
                vendorArtifactFormat = vendor?.artifactFormat,
                vendorArtifactLayout = vendor?.artifactLayout,
                vendorExecutableBasename = vendor?.executableBasename,
                vendorInterpreterFamilyId = vendor?.interpreterFamilyId,
                vendorInterpreterCommand = vendor?.interpreterCommand,
                vendorArchiveMemberPath = vendor?.archiveMemberPath,
                vendorArchiveMaxFiles = vendor?.archiveMaxFiles,
                vendorArchiveMaxUnpackedBytes = vendor?.archiveMaxUnpackedBytes,
                vendorTreeEntryPoint = vendor?.treeEntryPoint,
                vendorTreeExecutablePaths = vendor?.treeExecutablePaths.orEmpty(),
                vendorTreeAuxCommands = vendor?.treeAuxCommands.orEmpty(),
                vendorTreeMaxFiles = vendor?.treeMaxFiles,
                vendorTreeMaxUnpackedBytes = vendor?.treeMaxUnpackedBytes,
                vendorTreeLinkPolicy = vendor?.treeLinkPolicy ?: DeclarativeWholeTreeLinkPolicy.REJECT,
                vendorTreeModePolicy = vendor?.treeModePolicy ?: DeclarativeWholeTreeModePolicy.EXPLICIT_ALLOWLIST,
                vendorAssetMaxBytes = vendor?.assetMaxBytes,
                vendorRuntimeCompatibility = vendor?.runtimeCompatibility,
                healthArgs = manifest.healthArgs,
                bundleId = manifest.bundle?.id,
                bundlePrimaryFamilyId = manifest.bundle?.primaryFamilyId,
                allowLatest = manifest.versionPolicy.allowLatest,
                sideBySide = manifest.versionPolicy.sideBySide,
                workspaceSelectable = manifest.versionPolicy.workspaceSelectable,
                versionScopedUninstall = manifest.versionPolicy.versionScopedUninstall,
            ).also(PackageSourceDefinition::validate)
        }

        private fun validateBundles(packages: List<DeclarativePackageManifest>) {
            packages.mapNotNull { packageManifest -> packageManifest.bundle?.id?.let { it to packageManifest } }
                .groupBy({ it.first }, { it.second })
                .forEach { (bundleId, members) ->
                    require(bundleId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid package bundle id" }
                    val primaries = members.map { requireNotNull(it.bundle).primaryFamilyId }.distinct()
                    require(primaries.size == 1) { "$bundleId has inconsistent primary family ownership" }
                    val primary = primaries.single()
                    // Current multi-capability bundles must be local.

                    if (bundleId == "android.platform-tools" || bundleId == "dart-sdk") {
                        require(members.any { it.familyId == primary }) { "$bundleId primary family is missing" }
                        require(members.map { it.provider to it.packageName }.distinct().size == 1) {
                            "$bundleId members do not share one package authority"
                        }
                    }
                }
        }
    }

    fun find(familyId: String): PackageSourceDefinition? = entries.firstOrNull { it.familyId == familyId }
    fun manifest(familyId: String): DeclarativePackageManifest? = manifests.firstOrNull { it.familyId == familyId }
    fun installable(familyId: String): PackageSourceDefinition? = find(familyId)?.takeIf { it.installerReady }
    fun bundleMembers(bundleId: String): List<DeclarativePackageManifest> = manifests.filter { it.bundle?.id == bundleId }
}

package com.baystudio.droide.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

// Commit durable state only after verification succeeds.


@Serializable
enum class PackageSourceKind(val label: String) {
    NPM("npm"),
    PYPI("PyPI"),
    CARGO("Cargo / crates.io"),
    GO("Go modules"),
    RUBYGEMS("RubyGems"),
    COMPOSER("Composer / Packagist"),
    ANDROID_SDK("Android SDK repository"),
    GITHUB_RELEASE("GitHub Releases"),
    VENDOR_OFFICIAL("Vendor official"),
}

data class PackageSourceDefinition(
    val familyId: String,
    val source: PackageSourceKind,
    val packageName: String,
    val command: String,
    val provenanceUrl: String,
    val installerReady: Boolean = false,
    val minimumNodeMajor: Int? = null,
    val minimumPythonMajor: Int? = null,
    val minimumPythonMinor: Int? = null,
    val goModulePath: String? = null,
    val githubTagPrefix: String? = null,
    val githubAssetTokens: List<String> = emptyList(),
    val githubExactAssetName: String? = null,
    val githubAssetNameTemplate: String? = null,
    val githubArtifactFormat: DeclarativeGitHubArtifactFormat? = null,
    val githubArtifactLayout: DeclarativeGitHubArtifactLayout? = null,
    val githubExecutableBasename: String? = null,
    val githubTreeEntryPoint: String? = null,
    val githubTreeExecutablePaths: List<String> = emptyList(),
    val githubTreeMaxFiles: Int? = null,
    val githubTreeMaxUnpackedBytes: Long? = null,
    val githubInterpreterFamilyId: String? = null,
    val githubInterpreterCommand: String? = null,
    val githubInterpreterArgs: List<String> = emptyList(),
    val githubAssetMaxBytes: Long? = null,
    val githubRuntimeCompatibility: DeclarativeRuntimeCompatibility? = null,
    val vendorVersionStrategy: DeclarativeVendorVersionStrategy? = null,
    val vendorPinnedVersion: String? = null,
    val vendorPinnedSha256: String? = null,
    val vendorVersionUrl: String? = null,
    val vendorVersionGithubRepository: String? = null,
    val vendorTagPrefix: String? = null,
    val vendorArtifactUrlTemplate: String? = null,
    val vendorChecksumUrlTemplate: String? = null,
    val vendorArtifactFormat: DeclarativeGitHubArtifactFormat? = null,
    val vendorArtifactLayout: DeclarativeGitHubArtifactLayout? = null,
    val vendorExecutableBasename: String? = null,
    val vendorInterpreterFamilyId: String? = null,
    val vendorInterpreterCommand: String? = null,
    val vendorArchiveMemberPath: String? = null,
    val vendorArchiveMaxFiles: Int? = null,
    val vendorArchiveMaxUnpackedBytes: Long? = null,
    val vendorTreeEntryPoint: String? = null,
    val vendorTreeExecutablePaths: List<String> = emptyList(),
    val vendorTreeAuxCommands: Map<String, String> = emptyMap(),
    val vendorTreeMaxFiles: Int? = null,
    val vendorTreeMaxUnpackedBytes: Long? = null,
    val vendorTreeLinkPolicy: DeclarativeWholeTreeLinkPolicy = DeclarativeWholeTreeLinkPolicy.REJECT,
    val vendorTreeModePolicy: DeclarativeWholeTreeModePolicy = DeclarativeWholeTreeModePolicy.EXPLICIT_ALLOWLIST,
    val vendorAssetMaxBytes: Long? = null,
    val vendorRuntimeCompatibility: DeclarativeRuntimeCompatibility? = null,
    val healthArgs: List<String> = listOf("--version"),
    val bundleId: String? = null,
    val bundlePrimaryFamilyId: String? = null,
    val allowLatest: Boolean = true,
    val sideBySide: Boolean = true,
    val workspaceSelectable: Boolean = true,
    val versionScopedUninstall: Boolean = true,
) {
    fun validate() {
        require(familyId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid source family" }
        require(packageName.length in 1..220 && '\u0000' !in packageName && '\n' !in packageName && '\r' !in packageName) {
            "Invalid source package name"
        }
        require(command.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid source command" }
        require(provenanceUrl.startsWith("https://")) { "Source provenance must use HTTPS" }
        require(!installerReady || source in setOf(PackageSourceKind.NPM, PackageSourceKind.PYPI, PackageSourceKind.GO, PackageSourceKind.RUBYGEMS, PackageSourceKind.GITHUB_RELEASE, PackageSourceKind.VENDOR_OFFICIAL)) {
            "No supported installer is available for this package source"
        }
        when {
            source == PackageSourceKind.NPM && installerReady -> {
                require(minimumNodeMajor != null && minimumNodeMajor in 18..40) { "Installable npm source requires a reviewed Node.js minimum" }
                require(minimumPythonMajor == null && minimumPythonMinor == null) { "npm source must not declare a Python minimum" }
                require(goModulePath == null && githubTagPrefix == null && githubAssetTokens.isEmpty() && githubExactAssetName == null && githubAssetNameTemplate == null && githubArtifactFormat == null && githubArtifactLayout == null && githubExecutableBasename == null && githubTreeEntryPoint == null && githubTreeExecutablePaths.isEmpty() && githubTreeMaxFiles == null && githubTreeMaxUnpackedBytes == null && githubInterpreterFamilyId == null && githubInterpreterCommand == null && githubInterpreterArgs.isEmpty() && githubAssetMaxBytes == null && githubRuntimeCompatibility == null) { "npm source must not declare unrelated provider selectors" }
            }
            source == PackageSourceKind.PYPI && installerReady -> {
                require(minimumNodeMajor == null) { "PyPI source must not declare a Node.js minimum" }
                require(minimumPythonMajor == 3 && minimumPythonMinor != null && minimumPythonMinor in 8..20) { "Installable PyPI source requires a reviewed Python minimum" }
                require(goModulePath == null) { "PyPI source must not declare a Go module" }
                require(githubTagPrefix == null && githubAssetTokens.isEmpty() && githubExactAssetName == null && githubAssetNameTemplate == null && githubArtifactFormat == null && githubArtifactLayout == null && githubExecutableBasename == null && githubTreeEntryPoint == null && githubTreeExecutablePaths.isEmpty() && githubTreeMaxFiles == null && githubTreeMaxUnpackedBytes == null && githubInterpreterFamilyId == null && githubInterpreterCommand == null && githubInterpreterArgs.isEmpty() && githubAssetMaxBytes == null && githubRuntimeCompatibility == null) { "PyPI source must not declare GitHub release selectors" }
            }
            source == PackageSourceKind.RUBYGEMS && installerReady -> {
                require(minimumNodeMajor == null && minimumPythonMajor == null && minimumPythonMinor == null && goModulePath == null) { "RubyGems source must not declare unrelated language-runtime minimums" }
                require(packageName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}"))) { "Installable RubyGems source requires a safe gem name" }
                require(githubTagPrefix == null && githubAssetTokens.isEmpty() && githubExactAssetName == null && githubAssetNameTemplate == null && githubArtifactFormat == null && githubArtifactLayout == null && githubExecutableBasename == null && githubTreeEntryPoint == null && githubTreeExecutablePaths.isEmpty() && githubTreeMaxFiles == null && githubTreeMaxUnpackedBytes == null && githubInterpreterFamilyId == null && githubInterpreterCommand == null && githubInterpreterArgs.isEmpty() && githubAssetMaxBytes == null && githubRuntimeCompatibility == null) { "RubyGems source must not declare GitHub release selectors" }
            }
            source == PackageSourceKind.GO && installerReady -> {
                require(minimumNodeMajor == null && minimumPythonMajor == null && minimumPythonMinor == null) { "Go source must not declare Node.js/Python minimums" }
                require(goModulePath != null && goModulePath.matches(Regex("[a-z0-9][a-z0-9._~/-]{2,219}"))) { "Installable Go source requires a reviewed lowercase public module path" }
                require(packageName == goModulePath || packageName.startsWith("$goModulePath/")) { "Go command package must belong to the reviewed module" }
                require(githubTagPrefix == null && githubAssetTokens.isEmpty() && githubExactAssetName == null && githubAssetNameTemplate == null && githubArtifactFormat == null && githubArtifactLayout == null && githubExecutableBasename == null && githubTreeEntryPoint == null && githubTreeExecutablePaths.isEmpty() && githubTreeMaxFiles == null && githubTreeMaxUnpackedBytes == null && githubInterpreterFamilyId == null && githubInterpreterCommand == null && githubInterpreterArgs.isEmpty() && githubAssetMaxBytes == null && githubRuntimeCompatibility == null) { "Go source must not declare GitHub release selectors" }
            }
            source == PackageSourceKind.GITHUB_RELEASE && (installerReady || githubTagPrefix != null || githubExactAssetName != null || githubAssetNameTemplate != null || githubArtifactFormat != null || githubArtifactLayout != null || githubTreeEntryPoint != null || githubTreeExecutablePaths.isNotEmpty()) -> {
                require(minimumNodeMajor == null && minimumPythonMajor == null && minimumPythonMinor == null && goModulePath == null) { "GitHub Release source must not declare language-runtime minimums" }
                require(packageName.matches(Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))) { "GitHub source requires an owner/repository slug" }
                require(githubTagPrefix != null && githubTagPrefix.matches(Regex("[A-Za-z0-9._+-]{0,16}"))) { "GitHub source requires a safe tag prefix" }
                require(githubAssetTokens.size in 1..8 && githubAssetTokens.all { it.matches(Regex("[A-Za-z0-9._+-]{1,80}")) }) { "GitHub source requires bounded asset-selection tokens" }
                githubExactAssetName?.let { require(it.matches(Regex("[A-Za-z0-9._+-]{1,180}"))) { "GitHub source exact asset name is invalid" } }
                githubAssetNameTemplate?.let { template ->
                    require(githubExactAssetName == null) { "GitHub source cannot declare exactAssetName and assetNameTemplate together" }
                    require(template.length in 1..180 && template.count { it == '{' } == 1 && template.count { it == '}' } == 1 && template.contains("{version}")) { "GitHub source asset-name template must contain exactly one {version}" }
                    require(template.replace("{version}", "1.2.3").matches(Regex("[A-Za-z0-9._+-]{1,180}"))) { "GitHub source asset-name template is invalid" }
                }
                val format = requireNotNull(githubArtifactFormat) { "GitHub source requires a reviewed artifact format" }
                val layout = githubArtifactLayout ?: DeclarativeGitHubArtifactLayout.SINGLE_COMMAND
                githubExecutableBasename?.let { require(it.matches(Regex("[A-Za-z0-9._+-]{1,120}"))) { "Invalid GitHub executable basename" } }
                when (layout) {
                    DeclarativeGitHubArtifactLayout.SINGLE_COMMAND -> {
                        require(githubTreeEntryPoint == null && githubTreeExecutablePaths.isEmpty() && githubTreeMaxFiles == null && githubTreeMaxUnpackedBytes == null) {
                            "Single-command GitHub source must not declare whole-tree selectors"
                        }
                        val interpreted = format == DeclarativeGitHubArtifactFormat.PHAR || githubInterpreterFamilyId != null || githubInterpreterCommand != null || githubInterpreterArgs.isNotEmpty()
                        if (interpreted) {
                            require(format in setOf(DeclarativeGitHubArtifactFormat.PHAR, DeclarativeGitHubArtifactFormat.RAW)) { "Only PHAR/RAW GitHub artifacts may declare an interpreter" }
                            if (format == DeclarativeGitHubArtifactFormat.RAW) require(githubExactAssetName != null) { "Interpreted RAW GitHub source requires exactAssetName" }
                            require(githubInterpreterFamilyId?.matches(Regex("[A-Za-z0-9._-]{1,120}")) == true) { "Interpreted GitHub source requires a reviewed interpreter family" }
                            require(githubInterpreterCommand?.matches(Regex("[A-Za-z0-9._+-]{1,80}")) == true) { "Interpreted GitHub source requires a reviewed interpreter command" }
                            require(githubInterpreterArgs.size <= 12 && githubInterpreterArgs.all { isSafeGitHubInterpreterArg(it, allowTreePlaceholders = false) }) {
                                "Interpreted GitHub source has invalid interpreter arguments"
                            }
                        } else {
                            require(githubInterpreterFamilyId == null && githubInterpreterCommand == null && githubInterpreterArgs.isEmpty()) { "Native GitHub artifact must not declare an interpreter" }
                        }
                    }
                    DeclarativeGitHubArtifactLayout.WHOLE_TREE -> {
                        require(format in setOf(DeclarativeGitHubArtifactFormat.TAR_GZ, DeclarativeGitHubArtifactFormat.TGZ, DeclarativeGitHubArtifactFormat.ZIP, DeclarativeGitHubArtifactFormat.VSIX)) {
                            "Whole-tree GitHub source requires an archive container"
                        }
                        require(githubExecutableBasename == null) { "Whole-tree GitHub source uses an explicit tree entry point" }
                        val interpreted = githubInterpreterFamilyId != null || githubInterpreterCommand != null || githubInterpreterArgs.isNotEmpty()
                        if (interpreted) {
                            require(githubInterpreterFamilyId?.matches(Regex("[A-Za-z0-9._-]{1,120}")) == true) { "Interpreted whole-tree GitHub source requires a reviewed interpreter family" }
                            require(githubInterpreterCommand?.matches(Regex("[A-Za-z0-9._+-]{1,80}")) == true) { "Interpreted whole-tree GitHub source requires a reviewed interpreter command" }
                            require(githubInterpreterArgs.size <= 12 && githubInterpreterArgs.all { isSafeGitHubInterpreterArg(it, allowTreePlaceholders = true) }) {
                                "Interpreted whole-tree GitHub source has invalid interpreter arguments"
                            }
                            require(githubInterpreterArgs.any { "{entry}" in it }) { "Interpreted whole-tree GitHub source must reference {entry}" }
                        } else {
                            require(githubInterpreterFamilyId == null && githubInterpreterCommand == null && githubInterpreterArgs.isEmpty()) { "Native whole-tree GitHub source must not declare an interpreter" }
                        }
                        val entryPoint = requireNotNull(githubTreeEntryPoint) { "Whole-tree GitHub source requires an entry point" }
                        require(isSafeGitHubTreePath(entryPoint)) { "Unsafe GitHub whole-tree entry point" }
                        require(githubTreeExecutablePaths.size in 1..64 && githubTreeExecutablePaths.distinct().size == githubTreeExecutablePaths.size && githubTreeExecutablePaths.all(::isSafeGitHubTreePath)) {
                            "Whole-tree GitHub source requires bounded reviewed executable paths"
                        }
                        if (!interpreted) require(entryPoint in githubTreeExecutablePaths) { "Native whole-tree entry point must be declared executable" }
                        require(githubTreeMaxFiles != null && githubTreeMaxFiles in 1..4_096) { "Whole-tree file-count bound is invalid" }
                        require(githubTreeMaxUnpackedBytes != null && githubTreeMaxUnpackedBytes in 1L..1_073_741_824L) { "Whole-tree unpacked-byte bound is invalid" }
                    }
                }
                require(githubAssetMaxBytes != null && githubAssetMaxBytes in 1L..512L * 1024L * 1024L) { "GitHub release asset size bound is invalid" }
                githubRuntimeCompatibility?.validate()
                if (installerReady) {
                    val runtime = requireNotNull(githubRuntimeCompatibility) { "Installable GitHub source requires runtime compatibility evidence" }
                    PackageRuntimeCompatibilityPolicy.requireGenericInstallerCompatible(runtime)
                    val interpreted = githubInterpreterFamilyId != null
                    val nativeLibc = when (GuestRuntimeProfiles.get(runtime.guestProfile).libcFamily) {
                        "musl" -> DeclarativeLibcCompatibility.STATIC_OR_MUSL
                        "glibc" -> DeclarativeLibcCompatibility.GLIBC
                        else -> error("Unsupported reviewed guest libc")
                    }
                    require(runtime.libc == if (interpreted) DeclarativeLibcCompatibility.INTERPRETED else nativeLibc) {
                        "GitHub runtime compatibility must match interpreted/native execution semantics for the selected guest"
                    }
                }
            }
            source == PackageSourceKind.VENDOR_OFFICIAL && (installerReady || vendorVersionStrategy != null || vendorArtifactUrlTemplate != null) -> {
                require(minimumNodeMajor == null && minimumPythonMajor == null && minimumPythonMinor == null && goModulePath == null) { "Vendor source must not declare language-runtime minimums" }
                require(githubTagPrefix == null && githubAssetTokens.isEmpty() && githubExactAssetName == null && githubAssetNameTemplate == null && githubArtifactFormat == null && githubArtifactLayout == null && githubExecutableBasename == null && githubTreeEntryPoint == null && githubTreeExecutablePaths.isEmpty() && githubTreeMaxFiles == null && githubTreeMaxUnpackedBytes == null && githubInterpreterFamilyId == null && githubInterpreterCommand == null && githubInterpreterArgs.isEmpty() && githubAssetMaxBytes == null && githubRuntimeCompatibility == null) { "Vendor source must not declare GitHub artifact selectors" }
                val strategy = requireNotNull(vendorVersionStrategy) { "Vendor source requires a reviewed version strategy" }
                require(vendorTagPrefix != null && vendorTagPrefix.matches(Regex("[A-Za-z0-9._+-]{0,16}"))) { "Vendor source requires a safe tag prefix" }
                when (strategy) {
                    DeclarativeVendorVersionStrategy.PINNED -> {
                        require(vendorPinnedVersion?.matches(Regex("[0-9][0-9A-Za-z._+-]{0,79}")) == true) { "Vendor PINNED strategy requires a safe pinnedVersion" }
                        require(vendorPinnedSha256?.matches(Regex("[0-9a-f]{64}")) == true) { "Vendor PINNED strategy requires an exact pinnedSha256" }
                        require(vendorVersionUrl == null && vendorVersionGithubRepository == null) { "Vendor PINNED strategy must not declare live version authority" }
                        require(vendorChecksumUrlTemplate == null) { "Vendor PINNED strategy must use the catalog-bound SHA-256 rather than a mutable checksum endpoint" }
                    }
                    DeclarativeVendorVersionStrategy.TEXT_ENDPOINT -> {
                        require(vendorPinnedVersion == null && vendorPinnedSha256 == null) { "Vendor live strategy must not declare pinned metadata" }
                        require(vendorVersionUrl?.startsWith("https://") == true) { "Vendor TEXT_ENDPOINT requires HTTPS versionUrl" }
                        require(vendorVersionGithubRepository == null) { "Vendor TEXT_ENDPOINT must not declare a GitHub version repository" }
                        require(isSafeVendorChecksumTemplate(vendorChecksumUrlTemplate)) { "Vendor TEXT_ENDPOINT requires a reviewed checksum URL template" }
                    }
                    DeclarativeVendorVersionStrategy.GITHUB_RELEASE,
                    DeclarativeVendorVersionStrategy.GITHUB_RELEASE_MAX_SEMVER -> {
                        require(vendorPinnedVersion == null && vendorPinnedSha256 == null) { "Vendor live strategy must not declare pinned metadata" }
                        require(vendorVersionUrl == null) { "Vendor GitHub release strategy must not declare versionUrl" }
                        require(vendorVersionGithubRepository?.matches(Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")) == true) { "Vendor GitHub release strategy requires owner/repository" }
                        require(isSafeVendorChecksumTemplate(vendorChecksumUrlTemplate)) { "Vendor GitHub strategy requires a reviewed checksum URL template" }
                    }
                }
                require(isSafeVendorArtifactTemplate(vendorArtifactUrlTemplate)) { "Vendor artifact URL template is not a reviewed HTTPS template" }
                val format = requireNotNull(vendorArtifactFormat) { "Vendor source requires artifactFormat" }
                val layout = requireNotNull(vendorArtifactLayout) { "Vendor source requires artifactLayout" }
                require(vendorExecutableBasename?.matches(Regex("[A-Za-z0-9._+-]{1,120}")) == true) { "Vendor source requires a reviewed executable basename" }
                when (layout) {
                    DeclarativeGitHubArtifactLayout.SINGLE_COMMAND -> {
                        require(vendorTreeEntryPoint == null && vendorTreeExecutablePaths.isEmpty() && vendorTreeAuxCommands.isEmpty() && vendorTreeMaxFiles == null && vendorTreeMaxUnpackedBytes == null && vendorTreeLinkPolicy == DeclarativeWholeTreeLinkPolicy.REJECT && vendorTreeModePolicy == DeclarativeWholeTreeModePolicy.EXPLICIT_ALLOWLIST) {
                            "Single-command vendor source must not declare whole-tree metadata"
                        }
                        require(format in setOf(DeclarativeGitHubArtifactFormat.RAW, DeclarativeGitHubArtifactFormat.PHAR, DeclarativeGitHubArtifactFormat.TAR_GZ, DeclarativeGitHubArtifactFormat.TGZ)) { "Vendor single-command backend supports RAW/PHAR/TAR_GZ/TGZ" }
                        when (format) {
                            DeclarativeGitHubArtifactFormat.PHAR -> {
                                require(vendorArchiveMemberPath == null && vendorArchiveMaxFiles == null && vendorArchiveMaxUnpackedBytes == null) { "PHAR vendor source must not declare archive projection metadata" }
                                require(vendorInterpreterFamilyId?.matches(Regex("[A-Za-z0-9._-]{1,120}")) == true) { "Vendor PHAR source requires a reviewed interpreter family" }
                                require(vendorInterpreterCommand?.matches(Regex("[A-Za-z0-9._+-]{1,80}")) == true) { "Vendor PHAR source requires a reviewed interpreter command" }
                            }
                            DeclarativeGitHubArtifactFormat.RAW -> {
                                require(vendorArchiveMemberPath == null && vendorArchiveMaxFiles == null && vendorArchiveMaxUnpackedBytes == null) { "Raw vendor source must not declare archive projection metadata" }
                                require(vendorInterpreterFamilyId == null && vendorInterpreterCommand == null) { "Raw vendor source must not declare an interpreter" }
                            }
                            DeclarativeGitHubArtifactFormat.TAR_GZ, DeclarativeGitHubArtifactFormat.TGZ -> {
                                require(vendorInterpreterFamilyId == null && vendorInterpreterCommand == null) { "Native vendor archive source must not declare an interpreter" }
                                require(vendorArchiveMemberPath?.let(::isSafeGitHubTreePath) == true) { "Vendor archive source requires a reviewed member path" }
                                require(vendorArchiveMemberPath.substringAfterLast('/') == vendorExecutableBasename) { "Vendor archive member must end in the reviewed executable basename" }
                                require(vendorArchiveMaxFiles != null && vendorArchiveMaxFiles in 1..4_096) { "Vendor archive file-count bound is invalid" }
                                require(vendorArchiveMaxUnpackedBytes != null && vendorArchiveMaxUnpackedBytes in 1L..1_073_741_824L) { "Vendor archive unpacked-byte bound is invalid" }
                            }
                            else -> error("Unsupported single-command vendor artifact format")
                        }
                    }
                    DeclarativeGitHubArtifactLayout.WHOLE_TREE -> {
                        require(format in setOf(DeclarativeGitHubArtifactFormat.ZIP, DeclarativeGitHubArtifactFormat.TAR_GZ, DeclarativeGitHubArtifactFormat.TGZ)) { "Vendor whole-tree backend supports ZIP/TAR_GZ/TGZ" }
                        require(vendorInterpreterFamilyId == null && vendorInterpreterCommand == null) { "Vendor native whole-tree source must not declare an interpreter" }
                        require(vendorArchiveMemberPath == null && vendorArchiveMaxFiles == null && vendorArchiveMaxUnpackedBytes == null) { "Vendor whole-tree source must not declare single-member projection metadata" }
                        val entry = requireNotNull(vendorTreeEntryPoint) { "Vendor whole-tree source requires an entry point" }
                        require(isSafeGitHubTreePath(entry)) { "Unsafe vendor whole-tree entry point" }
                        require(vendorTreeExecutablePaths.distinct().size == vendorTreeExecutablePaths.size && vendorTreeExecutablePaths.all(::isSafeGitHubTreePath)) { "Vendor whole-tree executable paths are invalid" }
                        when (vendorTreeModePolicy) {
                            DeclarativeWholeTreeModePolicy.EXPLICIT_ALLOWLIST -> {
                                require(vendorTreeExecutablePaths.size in 1..64) { "Vendor whole-tree source requires bounded executable paths" }
                                require(entry in vendorTreeExecutablePaths) { "Vendor native whole-tree entry point must be executable" }
                                require(vendorTreeAuxCommands.values.all { it in vendorTreeExecutablePaths }) { "Vendor whole-tree auxiliary commands must point at reviewed executable paths" }
                            }
                            DeclarativeWholeTreeModePolicy.PINNED_ARCHIVE -> {
                                require(vendorTreeExecutablePaths.isEmpty()) { "Pinned archive mode policy must not duplicate executable metadata" }
                                require(format in setOf(DeclarativeGitHubArtifactFormat.TAR_GZ, DeclarativeGitHubArtifactFormat.TGZ)) { "Pinned archive mode policy currently requires TAR_GZ/TGZ" }
                            }
                        }
                        require(vendorTreeAuxCommands.size <= 16 && vendorTreeAuxCommands.keys.all { it.matches(Regex("[A-Za-z0-9._+-]{1,80}")) } && vendorTreeAuxCommands.values.all(::isSafeGitHubTreePath)) { "Vendor whole-tree auxiliary command map is invalid" }
                        if (vendorTreeLinkPolicy == DeclarativeWholeTreeLinkPolicy.SAFE_RELATIVE) {
                            require(format in setOf(DeclarativeGitHubArtifactFormat.TAR_GZ, DeclarativeGitHubArtifactFormat.TGZ)) { "Safe relative-link policy currently requires TAR_GZ/TGZ" }
                        }
                        require(vendorTreeMaxFiles != null && vendorTreeMaxFiles in 1..8_192) { "Vendor whole-tree file-count bound is invalid" }
                        require(vendorTreeMaxUnpackedBytes != null && vendorTreeMaxUnpackedBytes in 1L..6_442_450_944L) { "Vendor whole-tree unpacked-byte bound is invalid" }
                    }
                }
                require(vendorAssetMaxBytes != null && vendorAssetMaxBytes in 1L..2L * 1024L * 1024L * 1024L) { "Vendor asset size bound is invalid" }
                requireNotNull(vendorRuntimeCompatibility) { "Vendor source requires runtime compatibility evidence" }.let { runtime ->
                    runtime.validate()
                    require(runtime.integration == DeclarativeIntegrationKind.CLI) { "Vendor backend currently certifies CLI integration only" }
                    if (installerReady) PackageRuntimeCompatibilityPolicy.requireGenericInstallerCompatible(runtime)
                }
            }
            else -> {
                require(minimumNodeMajor == null) { "Node.js minimum only applies to installable npm source recipes" }
                require(minimumPythonMajor == null && minimumPythonMinor == null) { "Python minimum only applies to installable PyPI source recipes" }
                require(goModulePath == null) { "Go module ownership only applies to installable Go source recipes" }
                require(githubTagPrefix == null && githubAssetTokens.isEmpty() && githubExactAssetName == null && githubAssetNameTemplate == null && githubArtifactFormat == null && githubArtifactLayout == null && githubExecutableBasename == null && githubTreeEntryPoint == null && githubTreeExecutablePaths.isEmpty() && githubTreeMaxFiles == null && githubTreeMaxUnpackedBytes == null && githubInterpreterFamilyId == null && githubInterpreterCommand == null && githubInterpreterArgs.isEmpty() && githubAssetMaxBytes == null && githubRuntimeCompatibility == null) { "GitHub release selectors only apply to installable GitHub sources" }
            }
        }
        if (source != PackageSourceKind.VENDOR_OFFICIAL) {
            require(vendorVersionStrategy == null && vendorPinnedVersion == null && vendorPinnedSha256 == null && vendorVersionUrl == null && vendorVersionGithubRepository == null && vendorTagPrefix == null && vendorArtifactUrlTemplate == null && vendorChecksumUrlTemplate == null && vendorArtifactFormat == null && vendorArtifactLayout == null && vendorExecutableBasename == null && vendorInterpreterFamilyId == null && vendorInterpreterCommand == null && vendorArchiveMemberPath == null && vendorArchiveMaxFiles == null && vendorArchiveMaxUnpackedBytes == null && vendorTreeEntryPoint == null && vendorTreeExecutablePaths.isEmpty() && vendorTreeAuxCommands.isEmpty() && vendorTreeMaxFiles == null && vendorTreeMaxUnpackedBytes == null && vendorTreeLinkPolicy == DeclarativeWholeTreeLinkPolicy.REJECT && vendorTreeModePolicy == DeclarativeWholeTreeModePolicy.EXPLICIT_ALLOWLIST && vendorAssetMaxBytes == null && vendorRuntimeCompatibility == null) {
                "Vendor selectors only apply to vendor-official sources"
            }
        }
        bundleId?.let { require(it.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid source bundle id" } }
        bundlePrimaryFamilyId?.let { require(it.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid source bundle primary family" } }
        require(bundleId != null || bundlePrimaryFamilyId == null) { "Bundle primary family requires a bundle id" }
        require(!installerReady || allowLatest) { "Certified live providers must expose an explicit Latest convenience selector" }
        require(!installerReady || sideBySide && versionScopedUninstall) { "Certified packages must preserve side-by-side versions and version-scoped uninstall" }
        require(healthArgs.size <= 8 && healthArgs.all { it.length <= 120 && '\u0000' !in it && '\n' !in it && '\r' !in it }) {
            "Invalid source health arguments"
        }
    }
}


private fun isSafeVendorArtifactTemplate(value: String?): Boolean {
    if (value == null || value.length !in 12..500 || !value.startsWith("https://")) return false
    val tagCount = Regex(Regex.escape("{tag}")).findAll(value).count()
    if (tagCount !in 1..4) return false
    val stripped = value.replace("{tag}", "")
    if ('{' in stripped || '}' in stripped) return false
    if ('\u0000' in value || '\n' in value || '\r' in value || ' ' in value || '\\' in value) return false
    return runCatching { value.replace("{tag}", "v1.2.3").toHttpUrl() }.isSuccess
}

private fun isSafeVendorChecksumTemplate(value: String?): Boolean {
    if (value == null || value.length !in 12..500 || !value.startsWith("https://")) return false
    if ('\u0000' in value || '\n' in value || '\r' in value || ' ' in value || '\\' in value) return false
    val stripped = value.replace("{tag}", "")
    if ('{' in stripped || '}' in stripped || value.count { it == '{' } > 1 || value.count { it == '}' } > 1) return false
    return runCatching { value.replace("{tag}", "v1.2.3").toHttpUrl() }.isSuccess
}

private fun isSafeGitHubInterpreterArg(value: String, allowTreePlaceholders: Boolean): Boolean {
    if (value.length > 120 || '\u0000' in value || '\n' in value || '\r' in value) return false
    if (!allowTreePlaceholders) return '{' !in value && '}' !in value
    val stripped = value.replace("{tree}", "").replace("{entry}", "").replace("{version}", "")
    return '{' !in stripped && '}' !in stripped
}

private fun isSafeGitHubTreePath(value: String): Boolean {
    if (value.length !in 1..500 || value.startsWith('/') || '\\' in value || '\u0000' in value || '\n' in value || '\r' in value) return false
    val stripped = value.replace("{version}", "1.2.3")
    if ('{' in stripped || '}' in stripped) return false
    val segments = stripped.removePrefix("./").split('/')
    return segments.size in 1..16 && segments.all { segment ->
        segment.isNotBlank() && segment != "." && segment != ".." && segment.length <= 180 &&
            segment.matches(Regex("[A-Za-z0-9._+@=~%\\[\\]-]{1,180}"))
    }
}

@Serializable
private data class NpmDistDto(
    val integrity: String? = null,
    val shasum: String? = null,
    val tarball: String? = null,
)

@Serializable
private data class NpmPackageDto(
    val name: String,
    val version: String,
    val dist: NpmDistDto = NpmDistDto(),
)

@Serializable
private data class PyPiInfoDto(
    val name: String,
    val version: String,
    @SerialName("requires_python") val requiresPython: String? = null,
)

@Serializable
private data class PyPiDigestsDto(
    val sha256: String? = null,
)

@Serializable
private data class PyPiFileDto(
    val filename: String,
    val packagetype: String,
    val url: String,
    val digests: PyPiDigestsDto = PyPiDigestsDto(),
    val yanked: Boolean = false,
)

@Serializable
private data class PyPiReleaseDto(
    val info: PyPiInfoDto,
    val urls: List<PyPiFileDto> = emptyList(),
)

@Serializable
private data class GitHubReleaseAssetDto(
    val name: String,
    val state: String,
    val size: Long,
    val digest: String? = null,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
)

@Serializable
private data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val immutable: Boolean = false,
    val assets: List<GitHubReleaseAssetDto> = emptyList(),
)

@Serializable
private data class GoProxyInfoDto(
    @SerialName("Version") val version: String,
    @SerialName("Time") val time: String? = null,
)

@Serializable
private data class RubyGemsVersionDto(
    val number: String,
    val platform: String = "ruby",
    val prerelease: Boolean = false,
    @SerialName("ruby_version") val rubyVersion: String? = null,
    val sha: String? = null,
)

 
class PackageSourceResolver(private val sourceCatalog: PackageSourceCatalog) {
    private val json = Json { ignoreUnknownKeys = true }
    private val npmRegistry = "https://registry.npmjs.org".toHttpUrl()
    private val pypiRegistry = "https://pypi.org".toHttpUrl()
    private val goProxy = "https://proxy.golang.org".toHttpUrl()
    private val rubyGemsRegistry = "https://rubygems.org".toHttpUrl()
    private val githubApi = "https://api.github.com".toHttpUrl()

    suspend fun resolveReviewedNpm(familyId: String, requestedVersion: String): WorkstationInstallRecipe {
        val source = sourceCatalog.installable(familyId)
            ?: error("No certified live-source installer exists for $familyId")
        require(source.source == PackageSourceKind.NPM) { "Unsupported live-source installer: ${source.source.label}" }

        val selector = if (requestedVersion == PackageSourceCatalog.LATEST_OFFICIAL_VERSION) "latest" else requestedVersion
        require(selector.matches(Regex("[A-Za-z0-9._+-]{1,100}"))) { "Unsafe npm version selector" }
        val url = npmRegistry.newBuilder()
            .addPathSegment(source.packageName)
            .addPathSegment(selector)
            .build()
            .toString()
        val response = SafeHttp.get(url, accept = "application/json", maxBytes = 500_000)
        check(response.code == 200) { "npm registry lookup failed with HTTP ${response.code}" }
        require(response.finalUrl.toHttpUrl().host == "registry.npmjs.org") { "npm metadata escaped the official registry" }
        val metadata = json.decodeFromString<NpmPackageDto>(response.body)
        require(metadata.name == source.packageName) { "npm registry package identity mismatch" }
        require(metadata.version.matches(Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,79}"))) { "Invalid npm package version" }
        if (requestedVersion != PackageSourceCatalog.LATEST_OFFICIAL_VERSION) {
            require(metadata.version == requestedVersion) { "npm registry resolved a different version" }
        }
        val integrity = metadata.dist.integrity?.trim()
            ?: error("npm registry metadata does not publish dist.integrity for ${source.packageName}@${metadata.version}")
        require(integrity.matches(Regex("sha512-[A-Za-z0-9+/=]{32,180}"))) { "Invalid npm dist.integrity metadata" }
        metadata.dist.tarball?.let { tarball ->
            val validated = NetworkSecurity.validatePublicHttpsTarget(tarball)
            require(validated.url.toHttpUrl().host == "registry.npmjs.org") { "npm tarball is not hosted by the official registry" }
        }

        return WorkstationInstallRecipe(
            familyId = familyId,
            version = metadata.version,
            packageName = source.packageName,
            packageVersion = metadata.version,
            command = source.command,
            minimumNodeMajor = requireNotNull(source.minimumNodeMajor),
            provenanceUrl = source.provenanceUrl,
            registryIntegrity = integrity,
        ).also(WorkstationInstallRecipe::validate)
    }

    suspend fun resolveReviewedPyPi(familyId: String, requestedVersion: String): PyPiInstallRecipe {
        val source = sourceCatalog.installable(familyId)
            ?: error("No certified live-source installer exists for $familyId")
        require(source.source == PackageSourceKind.PYPI) { "Unsupported live-source installer: ${source.source.label}" }
        if (requestedVersion != PackageSourceCatalog.LATEST_OFFICIAL_VERSION) {
            require(requestedVersion.matches(Regex("[0-9A-Za-z][0-9A-Za-z._+!-]{0,79}"))) { "Unsafe PyPI version selector" }
        }
        val url = pypiRegistry.newBuilder()
            .addPathSegment("pypi")
            .addPathSegment(source.packageName)
            .apply {
                if (requestedVersion != PackageSourceCatalog.LATEST_OFFICIAL_VERSION) addPathSegment(requestedVersion)
                addPathSegment("json")
            }
            .build()
            .toString()
        val response = SafeHttp.get(url, accept = "application/json", maxBytes = 1_000_000)
        check(response.code == 200) { "PyPI registry lookup failed with HTTP ${response.code}" }
        require(response.finalUrl.toHttpUrl().host == "pypi.org") { "PyPI metadata escaped pypi.org" }
        val metadata = json.decodeFromString<PyPiReleaseDto>(response.body)
        require(normalizePyPiName(metadata.info.name) == normalizePyPiName(source.packageName)) { "PyPI project identity mismatch" }
        require(metadata.info.version.matches(Regex("[0-9A-Za-z][0-9A-Za-z._+!-]{0,79}"))) { "Invalid PyPI release version" }
        if (requestedVersion != PackageSourceCatalog.LATEST_OFFICIAL_VERSION) {
            require(metadata.info.version == requestedVersion) { "PyPI resolved a different version" }
        }
        val wheelHashes = metadata.urls.asSequence()
            .filter { !it.yanked && it.packagetype == "bdist_wheel" && it.filename.endsWith(".whl") }
            .map { file ->
                val target = NetworkSecurity.validatePublicHttpsTarget(file.url)
                require(target.url.toHttpUrl().host == "files.pythonhosted.org") { "PyPI wheel escaped files.pythonhosted.org" }
                val hash = file.digests.sha256?.lowercase() ?: error("PyPI wheel omitted SHA-256")
                require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid PyPI wheel SHA-256" }
                hash
            }
            .toSet()
        require(wheelHashes.isNotEmpty()) { "PyPI release has no non-yanked wheel artifacts" }
        return PyPiInstallRecipe(
            familyId = familyId,
            version = metadata.info.version,
            packageName = source.packageName,
            packageVersion = metadata.info.version,
            command = source.command,
            minimumPythonMajor = requireNotNull(source.minimumPythonMajor),
            minimumPythonMinor = requireNotNull(source.minimumPythonMinor),
            provenanceUrl = source.provenanceUrl,
            requiresPython = metadata.info.requiresPython,
            releaseWheelSha256 = wheelHashes,
            healthArgs = source.healthArgs,
        ).also(PyPiInstallRecipe::validate)
    }

    suspend fun resolveReviewedGo(familyId: String, requestedVersion: String): GoInstallRecipe {
        val source = sourceCatalog.installable(familyId)
            ?: error("No certified live-source installer exists for $familyId")
        require(source.source == PackageSourceKind.GO) { "Unsupported live-source installer: ${source.source.label}" }
        val modulePath = requireNotNull(source.goModulePath)
        val selector = if (requestedVersion == PackageSourceCatalog.LATEST_OFFICIAL_VERSION) null else requestedVersion
        selector?.let {
            require(it.matches(Regex("v[0-9][0-9A-Za-z.+_-]{0,119}"))) { "Unsafe Go module version selector" }
        }
        val infoUrl = goProxy.newBuilder()
            .addPathSegments(modulePath)
            .apply {
                if (selector == null) addPathSegment("@latest")
                else {
                    addPathSegment("@v")
                    addPathSegment("$selector.info")
                }
            }
            .build()
            .toString()
        val infoResponse = SafeHttp.get(infoUrl, accept = "application/json", maxBytes = 64_000)
        check(infoResponse.code == 200) { "Go module proxy lookup failed with HTTP ${infoResponse.code}" }
        require(infoResponse.finalUrl.toHttpUrl().host == "proxy.golang.org") { "Go metadata escaped proxy.golang.org" }
        val info = json.decodeFromString<GoProxyInfoDto>(infoResponse.body)
        require(info.version.matches(Regex("v[0-9][0-9A-Za-z.+_-]{0,119}"))) { "Invalid Go module version" }
        if (selector != null) require(info.version == selector) { "Go module proxy resolved a different version" }

        val modUrl = goProxy.newBuilder()
            .addPathSegments(modulePath)
            .addPathSegment("@v")
            .addPathSegment("${info.version}.mod")
            .build()
            .toString()
        val modResponse = SafeHttp.get(modUrl, accept = "text/plain", maxBytes = 512_000)
        check(modResponse.code == 200) { "Go module metadata lookup failed with HTTP ${modResponse.code}" }
        require(modResponse.finalUrl.toHttpUrl().host == "proxy.golang.org") { "Go module metadata escaped proxy.golang.org" }
        val declaredModule = Regex("(?m)^module\\s+([^\\s]+)\\s*$").find(modResponse.body)?.groupValues?.getOrNull(1)
            ?: error("Go module metadata omitted module directive")
        require(declaredModule == modulePath) { "Go module identity mismatch" }
        require(Regex("(?m)^\\s*(replace|exclude)\\s+").find(modResponse.body) == null) {
            "Reviewed go install source cannot use replace/exclude directives"
        }
        val goDirective = Regex("(?m)^go\\s+([0-9]+)\\.([0-9]+)(?:\\.[0-9]+)?\\s*$").find(modResponse.body)
            ?: error("Go module metadata omitted go directive")
        val languageMajor = goDirective.groupValues[1].toInt()
        val languageMinor = goDirective.groupValues[2].toInt()
        val toolchainDirective = Regex("(?m)^toolchain\\s+go([0-9]+)\\.([0-9]+)(?:\\.[0-9]+)?\\s*$").find(modResponse.body)
        val toolchainMajor = toolchainDirective?.groupValues?.get(1)?.toIntOrNull() ?: languageMajor
        val toolchainMinor = toolchainDirective?.groupValues?.get(2)?.toIntOrNull() ?: languageMinor
        val (minimumGoMajor, minimumGoMinor) = if (toolchainMajor > languageMajor || toolchainMajor == languageMajor && toolchainMinor > languageMinor) {
            toolchainMajor to toolchainMinor
        } else {
            languageMajor to languageMinor
        }
        require(minimumGoMajor == 1 && minimumGoMinor in 16..99) { "Unsupported Go language/toolchain minimum in module metadata" }

        return GoInstallRecipe(
            familyId = familyId,
            version = info.version,
            modulePath = modulePath,
            packagePath = source.packageName,
            command = source.command,
            minimumGoMajor = minimumGoMajor,
            minimumGoMinor = minimumGoMinor,
            provenanceUrl = source.provenanceUrl,
            healthArgs = source.healthArgs,
        ).also(GoInstallRecipe::validate)
    }

    suspend fun resolveReviewedRubyGems(familyId: String, requestedVersion: String): RubyGemsInstallRecipe {
        val source = sourceCatalog.installable(familyId)
            ?: error("No certified live-source installer exists for $familyId")
        require(source.source == PackageSourceKind.RUBYGEMS) { "Unsupported live-source installer: ${source.source.label}" }
        if (requestedVersion != PackageSourceCatalog.LATEST_OFFICIAL_VERSION) {
            require(requestedVersion.matches(Regex("[0-9][0-9A-Za-z._+-]{0,79}"))) { "Unsafe RubyGems version selector" }
        }
        val url = rubyGemsRegistry.newBuilder()
            .addPathSegments("api/v1/versions")
            .addPathSegment("${source.packageName}.json")
            .build()
            .toString()
        val response = SafeHttp.get(url, accept = "application/json", maxBytes = 2_000_000)
        check(response.code == 200) { "RubyGems registry lookup failed with HTTP ${response.code}" }
        require(response.finalUrl.toHttpUrl().host == "rubygems.org") { "RubyGems metadata escaped rubygems.org" }
        val releases = json.decodeFromString<List<RubyGemsVersionDto>>(response.body)
        require(releases.size in 1..5_000) { "RubyGems returned an invalid version set" }
        val selected = if (requestedVersion == PackageSourceCatalog.LATEST_OFFICIAL_VERSION) {
            releases.firstOrNull { !it.prerelease && it.platform == "ruby" }
                ?: error("RubyGems publishes no stable ruby-platform release for ${source.packageName}")
        } else {
            releases.singleOrNull { it.number == requestedVersion && !it.prerelease && it.platform == "ruby" }
                ?: error("RubyGems exact version is unavailable or not a stable ruby-platform release")
        }
        require(selected.number.matches(Regex("[0-9][0-9A-Za-z._+-]{0,79}"))) { "Invalid RubyGems release version" }
        val sha256 = selected.sha?.lowercase() ?: error("RubyGems release metadata omitted SHA-256")
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid RubyGems release SHA-256" }
        return RubyGemsInstallRecipe(
            familyId = familyId,
            version = selected.number,
            gemName = source.packageName,
            command = source.command,
            rootSha256 = sha256,
            requiredRuby = selected.rubyVersion,
            provenanceUrl = source.provenanceUrl,
            healthArgs = source.healthArgs,
        ).also(RubyGemsInstallRecipe::validate)
    }

    suspend fun resolveReviewedGitHubRelease(familyId: String, requestedVersion: String): GitHubReleaseInstallRecipe {
        val source = sourceCatalog.installable(familyId)
            ?: error("No certified live-source installer exists for $familyId")
        require(source.source == PackageSourceKind.GITHUB_RELEASE) { "Unsupported live-source installer: ${source.source.label}" }
        val (owner, repo) = source.packageName.split('/', limit = 2).let { parts ->
            require(parts.size == 2) { "Invalid GitHub repository authority" }
            parts[0] to parts[1]
        }
        val tagPrefix = requireNotNull(source.githubTagPrefix)
        val exactRequested = requestedVersion.takeUnless { it == PackageSourceCatalog.LATEST_OFFICIAL_VERSION }?.let { raw ->
            require(raw.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}"))) { "Unsafe GitHub release version selector" }
            if (tagPrefix.isNotEmpty() && !raw.startsWith(tagPrefix)) "$tagPrefix$raw" else raw
        }
        val url = githubApi.newBuilder()
            .addPathSegment("repos")
            .addPathSegment(owner)
            .addPathSegment(repo)
            .addPathSegment("releases")
            .apply {
                if (exactRequested == null) addPathSegment("latest")
                else {
                    addPathSegment("tags")
                    addPathSegment(exactRequested)
                }
            }
            .build()
            .toString()
        val response = SafeHttp.get(url, accept = "application/vnd.github+json", maxBytes = 1_000_000)
        check(response.code == 200) { "GitHub release lookup failed with HTTP ${response.code}" }
        require(response.finalUrl.toHttpUrl().host == "api.github.com") { "GitHub release metadata escaped api.github.com" }
        val release = json.decodeFromString<GitHubReleaseDto>(response.body)
        require(!release.draft && !release.prerelease) { "Only stable published GitHub releases are supported" }
        require(release.tagName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,95}"))) { "Unsafe GitHub release tag" }
        if (exactRequested != null) require(release.tagName == exactRequested) { "GitHub resolved a different release tag" }
        if (tagPrefix.isNotEmpty()) require(release.tagName.startsWith(tagPrefix)) { "GitHub release tag does not match the reviewed tag prefix" }
        val version = if (tagPrefix.isNotEmpty()) release.tagName.removePrefix(tagPrefix) else release.tagName
        require(version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}"))) { "Unsafe normalized GitHub release version" }

        val artifactFormat = requireNotNull(source.githubArtifactFormat)
        val requiredTokens = source.githubAssetTokens.map { it.lowercase() }
        val expectedAssetName = source.githubExactAssetName ?: source.githubAssetNameTemplate?.replace("{version}", version)?.also { expanded ->
            require(expanded.matches(Regex("[A-Za-z0-9._+-]{1,180}"))) { "Expanded GitHub asset-name template is unsafe" }
        }
        val candidates = release.assets.filter { asset ->
            val lower = asset.name.lowercase()
            asset.state == "uploaded" && githubArtifactFormat(asset.name) == artifactFormat &&
                (expectedAssetName == null || asset.name == expectedAssetName) && requiredTokens.all(lower::contains) &&
                listOf("sbom", "checksum", "checksums", "sha256", ".sig", ".asc", "attestation").none { token -> lower.contains(token) }
        }
        require(candidates.size == 1) {
            "GitHub release must expose exactly one reviewed Linux/ARM64 asset; found ${candidates.map { it.name }}"
        }
        val asset = candidates.single()
        require(asset.name.matches(Regex("[A-Za-z0-9._+-]{1,180}"))) { "Unsafe GitHub release asset filename" }
        val maxBytes = requireNotNull(source.githubAssetMaxBytes)
        require(asset.size in 1L..maxBytes) { "GitHub release asset exceeds the reviewed size bound" }
        val digest = asset.digest?.lowercase() ?: error("GitHub release asset does not publish a digest")
        require(digest.matches(Regex("sha256:[0-9a-f]{64}"))) { "GitHub release asset lacks a valid SHA-256 digest" }
        val sha256 = digest.removePrefix("sha256:")

        val target = NetworkSecurity.validatePublicHttpsTarget(asset.browserDownloadUrl)
        val download = target.url.toHttpUrl()
        require(download.host == "github.com") { "GitHub release asset is not hosted by github.com" }
        require(download.pathSegments == listOf(owner, repo, "releases", "download", release.tagName, asset.name)) {
            "GitHub release asset URL escaped the reviewed repository/tag"
        }

        return GitHubReleaseInstallRecipe(
            familyId = familyId,
            version = version,
            repository = source.packageName,
            releaseTag = release.tagName,
            releaseImmutable = release.immutable,
            command = source.command,
            artifactFormat = artifactFormat,
            artifactLayout = source.githubArtifactLayout ?: DeclarativeGitHubArtifactLayout.SINGLE_COMMAND,
            executableBasename = source.githubExecutableBasename ?: source.command,
            treeEntryPoint = source.githubTreeEntryPoint,
            treeExecutablePaths = source.githubTreeExecutablePaths,
            treeMaxFiles = source.githubTreeMaxFiles,
            treeMaxUnpackedBytes = source.githubTreeMaxUnpackedBytes,
            interpreterFamilyId = source.githubInterpreterFamilyId,
            interpreterCommand = source.githubInterpreterCommand,
            interpreterArgs = source.githubInterpreterArgs,
            assetName = asset.name,
            assetUrl = asset.browserDownloadUrl,
            assetSha256 = sha256,
            assetSize = asset.size,
            provenanceUrl = source.provenanceUrl,
            healthArgs = source.healthArgs,
            runtimeCompatibility = requireNotNull(source.githubRuntimeCompatibility),
        ).also(GitHubReleaseInstallRecipe::validate)
    }

    private fun githubArtifactFormat(name: String): DeclarativeGitHubArtifactFormat {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".tar.gz") -> DeclarativeGitHubArtifactFormat.TAR_GZ
            lower.endsWith(".tgz") -> DeclarativeGitHubArtifactFormat.TGZ
            lower.endsWith(".zip") -> DeclarativeGitHubArtifactFormat.ZIP
            lower.endsWith(".vsix") -> DeclarativeGitHubArtifactFormat.VSIX
            lower.endsWith(".phar") -> DeclarativeGitHubArtifactFormat.PHAR
            else -> DeclarativeGitHubArtifactFormat.RAW
        }
    }

    suspend fun resolveReviewedVendorOfficial(familyId: String, requestedVersion: String): VendorArtifactInstallRecipe {
        val source = sourceCatalog.installable(familyId)
            ?: error("No certified live-source installer exists for $familyId")
        require(source.source == PackageSourceKind.VENDOR_OFFICIAL) { "Unsupported live-source installer: ${source.source.label}" }
        val strategy = requireNotNull(source.vendorVersionStrategy)
        val prefix = requireNotNull(source.vendorTagPrefix)
        val version = if (requestedVersion == PackageSourceCatalog.LATEST_OFFICIAL_VERSION) {
            when (strategy) {
                DeclarativeVendorVersionStrategy.PINNED -> requireNotNull(source.vendorPinnedVersion)
                DeclarativeVendorVersionStrategy.TEXT_ENDPOINT -> {
                    val endpoint = requireNotNull(source.vendorVersionUrl)
                    val authority = endpoint.toHttpUrl().host
                    val response = SafeHttp.get(endpoint, accept = "text/plain", maxBytes = 8_192)
                    check(response.code == 200) { "Vendor version lookup failed with HTTP ${response.code}" }
                    require(endpoint.toHttpUrl().host == authority) { "Vendor version authority changed unexpectedly" }
                    normalizeVendorVersion(response.body.trim(), prefix)
                }
                DeclarativeVendorVersionStrategy.GITHUB_RELEASE -> {
                    val repository = requireNotNull(source.vendorVersionGithubRepository)
                    val url = githubApi.newBuilder().addPathSegment("repos").apply { repository.split('/').forEach(::addPathSegment) }.addPathSegment("releases").addPathSegment("latest").build().toString()
                    val response = SafeHttp.get(url, accept = "application/vnd.github+json", maxBytes = 1_000_000)
                    check(response.code == 200) { "GitHub version lookup failed with HTTP ${response.code}" }
                    require(response.finalUrl.toHttpUrl().host == "api.github.com") { "GitHub version lookup escaped api.github.com" }
                    val release = json.decodeFromString<GitHubReleaseDto>(response.body)
                    require(!release.draft && !release.prerelease) { "Vendor version authority returned a non-stable GitHub release" }
                    normalizeVendorVersion(release.tagName, prefix)
                }
                DeclarativeVendorVersionStrategy.GITHUB_RELEASE_MAX_SEMVER -> {
                    val repository = requireNotNull(source.vendorVersionGithubRepository)
                    val url = githubApi.newBuilder().addPathSegment("repos").apply { repository.split('/').forEach(::addPathSegment) }
                        .addPathSegment("releases").addQueryParameter("per_page", "100").build().toString()
                    val response = SafeHttp.get(url, accept = "application/vnd.github+json", maxBytes = 4_000_000)
                    check(response.code == 200) { "GitHub release-list lookup failed with HTTP ${response.code}" }
                    require(response.finalUrl.toHttpUrl().host == "api.github.com") { "GitHub release-list lookup escaped api.github.com" }
                    json.decodeFromString<List<GitHubReleaseDto>>(response.body)
                        .asSequence()
                        .filter { !it.draft && !it.prerelease }
                        .mapNotNull { release ->
                            runCatching { normalizeVendorVersion(release.tagName, prefix) }.getOrNull()?.let { candidate ->
                                strictStableSemver(candidate)?.let { semver -> semver to candidate }
                            }
                        }
                        .maxWithOrNull(compareBy<Pair<List<Int>, String>>({ it.first[0] }, { it.first[1] }, { it.first[2] }))
                        ?.second
                        ?: error("Vendor GitHub release list contains no stable strict-semver release")
                }
            }
        } else {
            require(requestedVersion.matches(Regex("[0-9][0-9A-Za-z._+-]{0,79}"))) { "Unsafe vendor version selector" }
            if (strategy == DeclarativeVendorVersionStrategy.PINNED) {
                require(requestedVersion == requireNotNull(source.vendorPinnedVersion)) {
                    "Requested vendor version is not the exact Foundry-certified pin"
                }
            }
            requestedVersion
        }
        val tag = prefix + version
        require(tag.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,95}"))) { "Invalid vendor artifact tag" }
        val assetTemplate = requireNotNull(source.vendorArtifactUrlTemplate)
        val assetUrl = assetTemplate.replace("{tag}", tag)
        val assetTarget = NetworkSecurity.validatePublicHttpsTarget(assetUrl)
        require(assetTarget.url.toHttpUrl().host == assetTemplate.replace("{tag}", "v1.2.3").toHttpUrl().host) { "Vendor artifact escaped reviewed authority" }
        val assetName = assetTarget.url.toHttpUrl().encodedPath.substringAfterLast('/').takeIf(String::isNotBlank)
            ?: error("Vendor artifact URL has no filename")
        require(assetName.matches(Regex("[A-Za-z0-9._+-]{1,180}"))) { "Vendor artifact filename is unsafe" }
        val checksum = when (strategy) {
            DeclarativeVendorVersionStrategy.PINNED -> requireNotNull(source.vendorPinnedSha256)
            else -> {
                val checksumTemplate = requireNotNull(source.vendorChecksumUrlTemplate)
                val checksumUrl = checksumTemplate.replace("{tag}", tag)
                val checksumTarget = NetworkSecurity.validatePublicHttpsTarget(checksumUrl)
                require(checksumTarget.url.toHttpUrl().host == checksumTemplate.replace("{tag}", "v1.2.3").toHttpUrl().host) { "Vendor checksum escaped reviewed authority" }
                val checksumResponse = SafeHttp.get(checksumUrl, accept = "text/plain, text/html;q=0.9", maxBytes = 512_000)
                check(checksumResponse.code == 200) { "Vendor checksum lookup failed with HTTP ${checksumResponse.code}" }
                parseVendorSha256(checksumResponse.body, assetName)
            }
        }
        val format = requireNotNull(source.vendorArtifactFormat)
        require(GitHubReleaseInstallRecipe.assetMatchesFormat(assetName, format)) { "Vendor artifact does not match reviewed format" }
        return VendorArtifactInstallRecipe(
            familyId = familyId,
            version = version,
            command = source.command,
            versionAuthority = when (strategy) {
                DeclarativeVendorVersionStrategy.PINNED -> source.provenanceUrl
                DeclarativeVendorVersionStrategy.TEXT_ENDPOINT -> requireNotNull(source.vendorVersionUrl)
                DeclarativeVendorVersionStrategy.GITHUB_RELEASE,
                DeclarativeVendorVersionStrategy.GITHUB_RELEASE_MAX_SEMVER -> "https://github.com/${requireNotNull(source.vendorVersionGithubRepository)}/releases"
            },
            releaseTag = tag,
            artifactFormat = format,
            artifactLayout = requireNotNull(source.vendorArtifactLayout),
            executableBasename = requireNotNull(source.vendorExecutableBasename),
            interpreterFamilyId = source.vendorInterpreterFamilyId,
            interpreterCommand = source.vendorInterpreterCommand,
            archiveMemberPath = source.vendorArchiveMemberPath,
            archiveMaxFiles = source.vendorArchiveMaxFiles,
            archiveMaxUnpackedBytes = source.vendorArchiveMaxUnpackedBytes,
            treeEntryPoint = source.vendorTreeEntryPoint,
            treeExecutablePaths = source.vendorTreeExecutablePaths,
            treeAuxCommands = source.vendorTreeAuxCommands,
            treeMaxFiles = source.vendorTreeMaxFiles,
            treeMaxUnpackedBytes = source.vendorTreeMaxUnpackedBytes,
            treeLinkPolicy = source.vendorTreeLinkPolicy,
            treeModePolicy = source.vendorTreeModePolicy,
            assetName = assetName,
            assetUrl = assetUrl,
            assetSha256 = checksum,
            assetMaxBytes = requireNotNull(source.vendorAssetMaxBytes),
            provenanceUrl = source.provenanceUrl,
            healthArgs = source.healthArgs,
            runtimeCompatibility = requireNotNull(source.vendorRuntimeCompatibility),
        ).also(VendorArtifactInstallRecipe::validate)
    }

    private fun normalizeVendorVersion(value: String, prefix: String): String {
        val trimmed = value.trim()
        val version = if (prefix.isNotEmpty() && trimmed.startsWith(prefix)) trimmed.removePrefix(prefix) else trimmed
        require(version.matches(Regex("[0-9][0-9A-Za-z._+-]{0,79}"))) { "Vendor version authority returned an unsafe version" }
        return version
    }

    private fun strictStableSemver(version: String): List<Int>? {
        val match = Regex("^([0-9]{1,6})\\.([0-9]{1,6})\\.([0-9]{1,6})$").matchEntire(version) ?: return null
        return match.groupValues.drop(1).map { it.toInt() }
    }

    private fun parseVendorSha256(body: String, assetName: String): String {
        require(body.length <= 512_000) { "Vendor checksum response exceeded safety bound" }
        require(assetName.matches(Regex("[A-Za-z0-9._+-]{1,180}"))) { "Vendor checksum asset filename is unsafe" }
        val digestRegex = Regex("(?i)(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])")
        val all = digestRegex.findAll(body).map { it.value.lowercase() }.distinct().toList()
        if (all.size == 1) return all.single()
        require(all.isNotEmpty()) { "Vendor checksum response contains no SHA-256" }
        val assetOffsets = Regex(Regex.escape(assetName)).findAll(body).map { it.range.first }.take(16).toList()
        require(assetOffsets.isNotEmpty()) { "Vendor checksum index does not mention the exact artifact" }
        val bound = 768
        val nearby = assetOffsets.flatMap { offset ->
            val start = (offset - bound).coerceAtLeast(0)
            val end = (offset + assetName.length + bound).coerceAtMost(body.length)
            digestRegex.findAll(body.substring(start, end)).map { it.value.lowercase() }.toList()
        }.distinct()
        require(nearby.size == 1) { "Vendor checksum index must bind the exact artifact to exactly one SHA-256" }
        return nearby.single()
    }

    private fun normalizePyPiName(name: String): String = name.lowercase().replace(Regex("[-_.]+"), "-")

}

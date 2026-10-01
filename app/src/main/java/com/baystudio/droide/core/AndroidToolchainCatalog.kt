package com.baystudio.droide.core

import android.content.Context
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class AndroidToolchainCatalogDocument(
    val schema: Int = 2,
    val revision: String,
    val entries: List<AndroidToolchainCatalogEntry>,
)

@Serializable
enum class AndroidToolchainArtifactHost {
    ANDROID_ARM64,
    LINUX_ARM64,
    LINUX_X86_64,
}

@Serializable
data class AndroidToolchainCatalogEntry(
    val id: String,
    val version: String,
    val abi: String,
    val compileSdk: Int,
    val javaVersion: Int,
    val gradleVersion: String,
    val downloadUrl: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val sdkLicenseSha256: String,
    val provenance: String,
    val provenanceUrl: String,
    val executionMode: AndroidToolchainExecutionMode = AndroidToolchainExecutionMode.ANDROID_NATIVE,
    val artifactHost: AndroidToolchainArtifactHost = AndroidToolchainArtifactHost.ANDROID_ARM64,
    val supportedCompileSdks: List<Int> = emptyList(),
    val buildToolsVersions: List<String> = emptyList(),
    val ndkVersions: List<String> = emptyList(),
    val cmakeVersions: List<String> = emptyList(),
) {
    val compileSdks: Set<Int> get() = (supportedCompileSdks + compileSdk).toSet()

    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "Invalid toolchain catalog id" }
        require(version.matches(Regex("[A-Za-z0-9._+-]{1,100}"))) { "Invalid toolchain version" }
        require(abi in SUPPORTED_ABIS) { "Unsupported toolchain ABI: $abi" }
        require(compileSdk in 1..999) { "Invalid toolchain compileSdk" }
        require(javaVersion in 8..99) { "Invalid toolchain Java version" }
        require(gradleVersion.matches(VERSION)) { "Invalid Gradle version" }
        require(fileName.matches(Regex("[A-Za-z0-9._+-]{1,180}"))) { "Invalid toolchain filename" }
        require(sizeBytes in 1L..TrustedArtifactSpec.MAX_TRUSTED_ARTIFACT_BYTES) { "Invalid toolchain size" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid toolchain SHA-256" }
        require(sdkLicenseSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid SDK license SHA-256" }
        require(provenance.isNotBlank() && provenance.length <= 300) { "Invalid provenance" }
        require(compileSdks.all { it in 1..999 } && compileSdks.size <= 128) { "Invalid compile SDK capability set" }
        listOf(buildToolsVersions, ndkVersions, cmakeVersions).forEach { versions ->
            require(versions.size <= 128 && versions.distinct().size == versions.size) { "Invalid duplicate/oversized version capability list" }
            versions.forEach { require(it.matches(VERSION)) { "Invalid component version: $it" } }
        }
        if (executionMode != AndroidToolchainExecutionMode.ANDROID_NATIVE) {
            require(abi == "arm64-v8a") { "Linux compatibility toolchains require an arm64-v8a device" }
        }
        AndroidToolchainArtifactAdmission.validate(executionMode, artifactHost, abi)
        validateHttpsUrl(downloadUrl, "download URL")
        validateHttpsUrl(provenanceUrl, "provenance URL")
    }

    fun supports(requirements: AndroidToolchainRequirements): Boolean {
        if (!compileSdks.containsAll(requirements.effectiveCompileSdks)) return false
        if (!buildToolsVersions.containsAll(requirements.effectiveBuildToolsVersions)) return false
        if (!ndkVersions.containsAll(requirements.effectiveNdkVersions)) return false
        if (!cmakeVersions.containsAll(requirements.effectiveCmakeVersions)) return false
        if (requirements.requiresNativeToolchain && ndkVersions.isEmpty()) return false
        if (requirements.requiresCmake && cmakeVersions.isEmpty()) return false
        if (requirements.requiresDesktopHostTools && executionMode == AndroidToolchainExecutionMode.ANDROID_NATIVE &&
            ndkVersions.isEmpty() && cmakeVersions.isEmpty()) return false
        return true
    }

    fun toArtifactSpec(): TrustedArtifactSpec = TrustedArtifactSpec(
        id = id,
        url = downloadUrl,
        sha256 = sha256,
        fileName = fileName,
        maxBytes = sizeBytes,
        expectedBytes = sizeBytes,
    )

    private fun validateHttpsUrl(value: String, label: String) {
        val parsed = value.toHttpUrlOrNull()
        require(parsed != null && parsed.isHttps && parsed.host.isNotBlank()) { "Invalid $label" }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "$label must not embed credentials" }
    }

    companion object {
        val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        private val VERSION = Regex("[0-9][A-Za-z0-9+_.-]{0,79}")
    }
}

object AndroidToolchainArtifactAdmission {
    fun validate(
        executionMode: AndroidToolchainExecutionMode,
        artifactHost: AndroidToolchainArtifactHost,
        deviceAbi: String,
    ) {
        when (executionMode) {
            AndroidToolchainExecutionMode.ANDROID_NATIVE -> {
                require(artifactHost == AndroidToolchainArtifactHost.ANDROID_ARM64) {
                    "Android-native toolchain artifact must target Android ARM64"
                }
                require(deviceAbi == "arm64-v8a") {
                    "Android-native managed toolchains are currently admitted only on arm64-v8a"
                }
            }
            AndroidToolchainExecutionMode.LINUX_ARM64_PROOT -> {
                require(artifactHost == AndroidToolchainArtifactHost.LINUX_ARM64) {
                    "Linux ARM64 PRoot toolchain artifact must target Linux ARM64"
                }
                require(deviceAbi == "arm64-v8a") {
                    "Linux ARM64 PRoot toolchains require an arm64-v8a device"
                }
            }
            AndroidToolchainExecutionMode.LINUX_X86_64_PROOT_QEMU -> {
                require(artifactHost == AndroidToolchainArtifactHost.LINUX_X86_64) {
                    "x86_64/QEMU toolchain artifact must target Linux x86_64"
                }
                require(deviceAbi == "arm64-v8a") {
                    "Linux x86_64/QEMU toolchains require an arm64-v8a device"
                }
            }
        }
    }

    fun description(artifactHost: AndroidToolchainArtifactHost): String = when (artifactHost) {
        AndroidToolchainArtifactHost.ANDROID_ARM64 -> "Android/ARM64"
        AndroidToolchainArtifactHost.LINUX_ARM64 -> "Linux/ARM64"
        AndroidToolchainArtifactHost.LINUX_X86_64 -> "Linux/x86_64 via QEMU"
    }
}

object AndroidToolchainCatalog {
    private const val ASSET_NAME = "android-toolchain-catalog.json"
    const val PINNED_ASSET_SHA256 = "8de4e22fe1a58b5a6a01f74ef849d31683d63def5ab9a348c683c9a79da16568"
    private val json = Json { ignoreUnknownKeys = false }

    fun load(context: Context): AndroidToolchainCatalogDocument {
        val bytes = context.applicationContext.assets.open(ASSET_NAME).use { input -> input.readBytes() }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual == PINNED_ASSET_SHA256) { "Pinned Android toolchain catalog integrity check failed" }
        val document = json.decodeFromString<AndroidToolchainCatalogDocument>(bytes.decodeToString())
        validate(document)
        return document
    }

    fun validate(document: AndroidToolchainCatalogDocument) {
        require(document.schema == 2) { "Unsupported toolchain catalog schema ${document.schema}" }
        require(document.revision.matches(Regex("[A-Za-z0-9._+-]{1,100}"))) { "Invalid catalog revision" }
        require(document.entries.size <= 128) { "Toolchain catalog is unexpectedly large" }
        document.entries.forEach(AndroidToolchainCatalogEntry::validate)
        require(document.entries.map { it.id }.distinct().size == document.entries.size) { "Duplicate toolchain catalog id" }
        require(document.entries.map { it.sha256 }.distinct().size == document.entries.size) { "Duplicate toolchain artifact bytes" }
    }

    fun select(
        document: AndroidToolchainCatalogDocument,
        requiredCompileSdk: Int,
        supportedAbis: List<String>,
    ): AndroidToolchainCatalogEntry? = select(
        document,
        AndroidToolchainRequirements(compileSdk = requiredCompileSdk),
        supportedAbis,
    )

    fun select(
        document: AndroidToolchainCatalogDocument,
        requirements: AndroidToolchainRequirements,
        supportedAbis: List<String>,
    ): AndroidToolchainCatalogEntry? {
        val rankedWithinRuntime = document.entries
            .asSequence()
            .filter { it.abi in supportedAbis }
            .filter { it.supports(requirements) }
            .sortedWith(
                compareBy<AndroidToolchainCatalogEntry> {
                    supportedAbis.indexOf(it.abi).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
                }
                    .thenBy { compileDistance(it, requirements.effectiveCompileSdks.maxOrNull()) }
                    .thenByDescending { it.javaVersion }
            )
            .toList()
        return AndroidToolchainFallbackPolicy.select(
            candidates = rankedWithinRuntime,
            mode = AndroidToolchainCatalogEntry::executionMode,
            supported = { true },
        )
    }

    private fun compileDistance(entry: AndroidToolchainCatalogEntry, required: Int?): Int {
        if (required == null) return 0
        return entry.compileSdks.minOfOrNull { kotlin.math.abs(it - required) } ?: Int.MAX_VALUE
    }
}

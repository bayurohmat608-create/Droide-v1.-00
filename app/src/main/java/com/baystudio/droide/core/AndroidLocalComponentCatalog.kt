package com.baystudio.droide.core

import android.content.Context
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
enum class AndroidLocalComponentKind {
    SDK_PLATFORM,
    BUILD_TOOLS_ARM64,
}

@Serializable
data class AndroidLocalNativeTool(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    fun validate(componentVersion: String) {
        require(name in REQUIRED_BUILD_TOOLS_NATIVE_NAMES) { "Unsupported Android Build Tools native overlay '$name'" }
        require(sizeBytes in 1L..TrustedArtifactSpec.MAX_TRUSTED_ARTIFACT_BYTES) { "Invalid Android Build Tools native tool size" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid Android Build Tools native tool SHA-256" }
        val parsed = downloadUrl.toHttpUrlOrNull()
        require(parsed != null && parsed.isHttps && parsed.host == "github.com") { "Android Build Tools native overlay must use github.com HTTPS" }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "Android Build Tools native overlay URL must not embed credentials" }
        require(parsed.query == null && parsed.fragment == null) { "Android Build Tools native overlay URL must not contain query/fragment" }
        require(parsed.encodedPath == "/Commit451/android-arm-build-tools/releases/download/platform-tools-$componentVersion/$name") {
            "Android Build Tools native overlay URL/version mismatch"
        }
    }

    fun toArtifactSpec(componentId: String): TrustedArtifactSpec = TrustedArtifactSpec(
        id = "$componentId-native-$name",
        url = downloadUrl,
        sha256 = sha256,
        fileName = "$componentId-$name",
        maxBytes = sizeBytes,
        expectedBytes = sizeBytes,
    )

    companion object {
        val REQUIRED_BUILD_TOOLS_NATIVE_NAMES = setOf("aapt2", "aidl", "zipalign", "split-select")
    }
}

@Serializable
data class AndroidLocalComponentCatalogDocument(
    val schema: Int = 2,
    val revision: String,
    val entries: List<AndroidLocalComponentCatalogEntry>,
)

@Serializable
data class AndroidLocalComponentCatalogEntry(
    val id: String,
    val familyId: String,
    val version: String,
    val kind: AndroidLocalComponentKind,
    val downloadUrl: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val upstreamSha1: String,
    val archiveRoot: String,
    val guestTarget: String,
    val maxFiles: Int,
    val maxUnpackedBytes: Long,
    val provenance: String,
    val provenanceUrl: String,
    val nativeTools: List<AndroidLocalNativeTool> = emptyList(),
) {
    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "Invalid local Android component id" }
        require(fileName.matches(Regex("[A-Za-z0-9._+-]{1,180}"))) { "Invalid local Android component filename" }
        require(sizeBytes in 1L..TrustedArtifactSpec.MAX_TRUSTED_ARTIFACT_BYTES) { "Invalid local Android component size" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid local Android component SHA-256" }
        require(upstreamSha1.matches(Regex("[0-9a-f]{40}"))) { "Invalid upstream Android component SHA-1" }
        require(maxFiles in 1..8_192) { "Invalid local Android component file bound" }
        require(maxUnpackedBytes in 1L..1_073_741_824L) { "Invalid local Android component expanded-byte bound" }
        require(provenance.isNotBlank() && provenance.length <= 500) { "Invalid local Android component provenance" }
        validateHttps(downloadUrl, "download URL")
        validateHttps(provenanceUrl, "provenance URL")

        when (kind) {
            AndroidLocalComponentKind.SDK_PLATFORM -> {
                require(familyId == "sdk.android") { "SDK Platform component has the wrong family" }
                require(version.matches(Regex("[0-9]{1,3}"))) { "Invalid Android SDK platform version" }
                val api = version.toInt()
                require(api in 1..999) { "Invalid Android SDK platform API" }
                require(fileName.matches(Regex("platform-[0-9]+_r[0-9]+\\.zip"))) { "Unexpected Google SDK Platform archive name" }
                require(archiveRoot == "android-$api") { "SDK platform archive root/version mismatch" }
                require(guestTarget == "platforms/android-$api") { "SDK platform guest target/version mismatch" }
                require(nativeTools.isEmpty()) { "SDK Platform component must not declare native overlays" }
            }
            AndroidLocalComponentKind.BUILD_TOOLS_ARM64 -> {
                require(familyId == "build.android-tools") { "Build Tools component has the wrong family" }
                require(version.matches(Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}"))) { "Invalid Android Build Tools version" }
                require(fileName == "build-tools_r${version.substringBefore('.')}_linux.zip") { "Unexpected Google Build Tools archive name" }
                require(archiveRoot.matches(Regex("android-[0-9]{1,3}"))) { "Invalid Google Build Tools archive root" }
                require(guestTarget == "build-tools/$version") { "Build Tools guest target/version mismatch" }
                require(nativeTools.size == AndroidLocalNativeTool.REQUIRED_BUILD_TOOLS_NATIVE_NAMES.size) { "Incomplete Android Build Tools native overlay" }
                require(nativeTools.map { it.name }.toSet() == AndroidLocalNativeTool.REQUIRED_BUILD_TOOLS_NATIVE_NAMES) {
                    "Android Build Tools native overlay set is incomplete"
                }
                require(nativeTools.map { it.sha256 }.distinct().size == nativeTools.size) { "Duplicate Android Build Tools native overlay digest" }
                nativeTools.forEach { it.validate(version) }
            }
        }
    }

    fun toArtifactSpec(): TrustedArtifactSpec = TrustedArtifactSpec(
        id = id,
        url = downloadUrl,
        sha256 = sha256,
        fileName = fileName,
        maxBytes = sizeBytes,
        expectedBytes = sizeBytes,
    )

    private fun validateHttps(value: String, label: String) {
        val parsed = value.toHttpUrlOrNull()
        require(parsed != null && parsed.isHttps && parsed.host.isNotBlank()) { "Invalid $label" }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "$label must not embed credentials" }
    }
}

object AndroidLocalComponentCatalog {
    private const val ASSET_NAME = "android-local-component-catalog.json"
    const val PINNED_ASSET_SHA256 = "a81cbfb37060335d4dd31c58af9290b61d04c2587b8925ed63dc416a3b53c55e"
    private val json = Json { ignoreUnknownKeys = false }

    fun load(context: Context): AndroidLocalComponentCatalogDocument {
        val bytes = context.applicationContext.assets.open(ASSET_NAME).use { it.readBytes() }
        val actual = sha256(bytes)
        check(actual == PINNED_ASSET_SHA256) { "Pinned local Android component catalog integrity check failed" }
        return json.decodeFromString<AndroidLocalComponentCatalogDocument>(bytes.decodeToString()).also(::validate)
    }

    fun validate(document: AndroidLocalComponentCatalogDocument) {
        require(document.schema == 2) { "Unsupported local Android component catalog schema ${document.schema}" }
        require(document.revision.matches(Regex("[A-Za-z0-9._+-]{1,100}"))) { "Invalid local Android component revision" }
        require(document.entries.size in 1..128) { "Local Android component catalog is unexpectedly sized" }
        document.entries.forEach(AndroidLocalComponentCatalogEntry::validate)
        require(document.entries.map { it.id }.distinct().size == document.entries.size) { "Duplicate local Android component id" }
        require(document.entries.map { it.familyId to it.version }.distinct().size == document.entries.size) { "Duplicate local Android component family/version" }
        require(document.entries.map { it.sha256 }.distinct().size == document.entries.size) { "Duplicate local Android component bytes" }
    }

    fun find(document: AndroidLocalComponentCatalogDocument, familyId: String, version: String): AndroidLocalComponentCatalogEntry? =
        document.entries.firstOrNull { it.familyId == familyId && it.version == version }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

package com.baystudio.droide.core








object LocalLlamaRuntimeContract {
    const val RUNTIME_ID = "llama.cpp-android-arm64"
    const val VERSION = "b10964"
    const val STABLE_RELEASE = "v0.4.1"
    const val UPSTREAM_COMMIT = "b29c606e28a01b1bc8c1351026a0fa6e616bf6c4"
    const val ABI = "arm64-v8a"
    const val MIN_ANDROID_API = 28

    const val ASSET_NAME = "llama-b10964-bin-android-arm64.tar.gz"
    const val ASSET_URL = "https://github.com/ggml-org/llama.cpp/releases/download/b10964/llama-b10964-bin-android-arm64.tar.gz"
    const val ASSET_SHA256 = "f1e8466297dd3c9fbdc6dc5a92c10ebfffd3757981cb1db0d0a2099ef6c0ff99"
    const val ASSET_BYTES = 71_151_166L
    const val ASSET_MAX_BYTES = 96L * 1024L * 1024L

    const val PROVENANCE_URL = "https://github.com/ggml-org/llama.cpp/releases/tag/b10964"
    const val ATTESTATION_URL = "https://github.com/ggml-org/llama.cpp/attestations/47376843"
    const val ARCHIVE_ROOT = "llama-b10964"
    const val CLI_RELATIVE_PATH = "$ARCHIVE_ROOT/llama-cli"
    const val SERVER_RELATIVE_PATH = "$ARCHIVE_ROOT/llama-server"
    const val LICENSE_RELATIVE_PATH = "$ARCHIVE_ROOT/LICENSE"

    const val MAX_TREE_FILES = 512
    const val MAX_TREE_BYTES = 384L * 1024L * 1024L
    const val INSTALL_OVERHEAD_BYTES = 128L * 1024L * 1024L

    val requiredRegularFiles: Set<String> = setOf(
        CLI_RELATIVE_PATH,
        SERVER_RELATIVE_PATH,
        LICENSE_RELATIVE_PATH,
    )

    fun validate() {
        require(VERSION.matches(Regex("b[0-9]{4,8}"))) { "Invalid llama.cpp build version" }
        require(STABLE_RELEASE.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Invalid stable release" }
        require(UPSTREAM_COMMIT.matches(Regex("[0-9a-f]{40}"))) { "Invalid upstream commit" }
        require(ASSET_SHA256.matches(Regex("[0-9a-f]{64}"))) { "Invalid runtime asset digest" }
        require(ASSET_BYTES in 1L..ASSET_MAX_BYTES) { "Invalid runtime asset size" }
        require(MIN_ANDROID_API >= 28) { "Android runtime floor regressed" }
        require(requiredRegularFiles.all(::safeArchivePath)) { "Unsafe required runtime archive path" }
        require(ASSET_URL.startsWith("https://github.com/ggml-org/llama.cpp/")) { "Unexpected runtime artifact origin" }
        require(PROVENANCE_URL.startsWith("https://github.com/ggml-org/llama.cpp/")) { "Unexpected runtime provenance origin" }
        require(ATTESTATION_URL.startsWith("https://github.com/ggml-org/llama.cpp/attestations/")) { "Unexpected runtime attestation origin" }
    }

    fun requireCompatibleTarget(abi: String, api: Int) {
        require(abi == ABI || abi == "aarch64") { "Local llama.cpp runtime requires Android ARM64" }
        require(api >= MIN_ANDROID_API) { "Local llama.cpp runtime requires Android API $MIN_ANDROID_API or newer" }
    }

    fun requireArchivePath(relativePath: String) {
        require(safeArchivePath(relativePath)) { "Unsafe llama.cpp archive path" }
        require(relativePath == ARCHIVE_ROOT || relativePath.startsWith("$ARCHIVE_ROOT/")) {
            "llama.cpp archive escaped its pinned top-level directory"
        }
    }

    private fun safeArchivePath(value: String): Boolean {
        if (value.length !in 1..500 || value.startsWith('/') || '\\' in value || '\u0000' in value || '\n' in value || '\r' in value) return false
        val parts = value.split('/')
        return parts.size in 1..16 && parts.all { part ->
            part.isNotBlank() && part != "." && part != ".." && part.length <= 180 &&
                part.matches(Regex("[A-Za-z0-9._+@=~%\\[\\]-]{1,180}"))
        }
    }
}

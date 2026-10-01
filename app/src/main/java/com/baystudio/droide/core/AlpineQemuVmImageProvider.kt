package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class AlpineQemuVmImageProvider(context: Context) {
    data class ResolvedImage(val spec: QemuVmImageSpec, val verifiedDownload: File)

    private val appContext = context.applicationContext
    private val transfer = UserInitiatedArtifactTransfer(appContext)

    suspend fun resolveAndDownload(
        onProgress: (TrustedArtifactDownloadProgress) -> Unit = {},
    ): ResolvedImage = withContext(Dispatchers.IO) {
        val imageSha512 = fetchSha512(IMAGE_SHA512_URL, IMAGE_FILE)

        val metadata = SafeHttp.get(METADATA_URL, accept = "text/yaml,text/plain", maxBytes = 16_384)
        requireOfficial(metadata, METADATA_URL)
        val metadataSha512 = fetchSha512(METADATA_SHA512_URL, METADATA_FILE)
        check(sha512(metadata.body.toByteArray(Charsets.UTF_8)) == metadataSha512) {
            "Alpine VM metadata SHA-512 does not match the official sidecar"
        }

        val downloadSpec = TrustedSha512ArtifactSpec(
            id = "alpine-$VERSION-x86_64-tiny-vm",
            url = IMAGE_URL,
            sha512 = imageSha512,
            fileName = IMAGE_FILE,
            maxBytes = EXPECTED_BYTES,
            expectedBytes = EXPECTED_BYTES,
        )
        val image = transfer.download(downloadSpec, onProgress)
        val localSha256 = digest(image, "SHA-256")
        val spec = QemuVmImageSpec(
            id = "alpine-tiny-vm",
            version = VERSION,
            architecture = "x86_64",
            downloadUrl = IMAGE_URL,
            fileName = IMAGE_FILE,
            expectedBytes = EXPECTED_BYTES,
            sha256 = localSha256,
            sha512 = imageSha512,
            provenance = "Alpine Linux official cloud release $VERSION; image=$IMAGE_URL; checksum=$IMAGE_SHA512_URL; metadata=$METADATA_URL",
            serialIdentityMarkers = listOf("Alpine Linux", VERSION.substringBeforeLast('.'), "login:"),
        )
        spec.validate()
        ResolvedImage(spec, image)
    }

    private suspend fun fetchSha512(url: String, expectedFile: String): String {
        val response = SafeHttp.get(url, accept = "text/plain", maxBytes = 2_048)
        requireOfficial(response, url)
        return AlpineSha512SidecarPolicy.parse(response.body, expectedFile)
    }

    private fun requireOfficial(response: SafeHttpResponse, requested: String) {
        check(response.code == 200) { "Alpine metadata request failed with HTTP ${response.code}" }
        val requestedUri = URI(requested)
        val finalUri = URI(response.finalUrl)
        check(requestedUri.host == OFFICIAL_HOST && finalUri.host == OFFICIAL_HOST) {
            "Alpine metadata request escaped the official release host"
        }
    }

    private fun digest(file: File, algorithm: String): String = file.inputStream().buffered(128 * 1024).use { input ->
        val digest = MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha512(bytes: ByteArray): String = MessageDigest.getInstance("SHA-512")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val VERSION = "3.24.2"
        const val EXPECTED_BYTES = 110_886_912L
        private const val OFFICIAL_HOST = "dl-cdn.alpinelinux.org"
        private const val BASE_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/cloud"
        private const val IMAGE_FILE = "alpine-3.24.2-x86_64-tiny-r0.qcow2"
        private const val METADATA_FILE = "alpine-3.24.2-x86_64-tiny-r0.yaml"
        const val IMAGE_URL = "$BASE_URL/$IMAGE_FILE"
        const val IMAGE_SHA512_URL = "$IMAGE_URL.sha512"
        const val METADATA_URL = "$BASE_URL/$METADATA_FILE"
        const val METADATA_SHA512_URL = "$METADATA_URL.sha512"

    }
}

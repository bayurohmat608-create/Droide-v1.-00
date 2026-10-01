package com.baystudio.droide.core

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ManagedAndroidToolchainInstaller(
    context: Context,
    private val manager: AndroidDevelopmentManager,
    private val licenseManager: AndroidSdkLicenseManager,
    private val downloader: TrustedArtifactDownloader = TrustedArtifactDownloader(context.applicationContext),
) {
    enum class Phase { IDLE, DOWNLOADING, PROVISIONING, READY, CANCELED, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val message: String = "",
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long? = null,
        val fromCache: Boolean = false,
    ) {
        val running: Boolean get() = phase == Phase.DOWNLOADING || phase == Phase.PROVISIONING
        val fraction: Float?
            get() = totalBytes?.takeIf { it > 0L }?.let {
                (bytesDownloaded.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat()
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun install(entry: AndroidToolchainCatalogEntry, license: AndroidSdkLicense): String {
        entry.validate()
        require(license.sha256 == entry.sdkLicenseSha256) {
            "The reviewed Android SDK license does not match the license pinned by this toolchain entry"
        }
        check(licenseManager.isAccepted(license)) { "Review and accept the pinned Android SDK license first" }
        return try {
            _state.value = State(Phase.DOWNLOADING, "Downloading verified Android toolchain…", totalBytes = entry.sizeBytes)
            val pack = downloader.download(entry.toArtifactSpec()) { progress ->
                _state.value = State(
                    phase = Phase.DOWNLOADING,
                    message = if (progress.fromCache) "Using verified cached Android toolchain…" else "Downloading verified Android toolchain…",
                    bytesDownloaded = progress.bytesDownloaded,
                    totalBytes = progress.totalBytes ?: entry.sizeBytes,
                    fromCache = progress.fromCache,
                )
            }
            _state.value = State(Phase.PROVISIONING, "Installing and verifying Android toolchain on this device…")
            val result = manager.provision(pack, entry.sha256)
            manager.refresh()
            _state.value = State(Phase.READY, result)
            result
        } catch (cancelled: CancellationException) {
            _state.value = State(Phase.CANCELED, "Managed Android toolchain installation canceled.")
            throw cancelled
        } catch (error: Throwable) {
            _state.value = State(Phase.FAILED, error.message ?: "Managed Android toolchain installation failed")
            throw error
        }
    }

    suspend fun clearCachedDownload(entry: AndroidToolchainCatalogEntry) {
        downloader.discard(entry.toArtifactSpec())
        _state.value = State(Phase.IDLE, "Verified toolchain download cache cleared.")
    }

    suspend fun isDownloadCached(entry: AndroidToolchainCatalogEntry): Boolean = downloader.cached(entry.toArtifactSpec()) != null
}

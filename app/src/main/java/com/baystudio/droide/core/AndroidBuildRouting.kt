package com.baystudio.droide.core

import com.baystudio.droide.core.AndroidDevelopmentManager.BuildBackend

internal object AndroidBuildRouting {
    suspend fun <L : Any, R> execute(
        requiredBackend: BuildBackend?,
        resolveLocal: suspend () -> L?,
        runLocal: suspend (L) -> R,
        runDevice: suspend () -> R,
    ): R {
        if (requiredBackend == BuildBackend.DEVICE_WORKSTATION) return runDevice()
        val local = resolveLocal()
        if (local != null) return runLocal(local)
        check(requiredBackend != BuildBackend.LOCAL_UBUNTU) { "The required Local Ubuntu build backend is unavailable" }
        return runDevice()
    }
}

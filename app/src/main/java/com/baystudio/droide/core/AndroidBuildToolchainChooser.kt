package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException





internal object AndroidBuildToolchainChooser {
    data class Selection(
        val resolved: AndroidToolchainSelector.Resolved,
        val runtimeGradleVersion: String,
    ) {
        val manifest: AndroidDevelopmentManager.ToolchainManifest get() = resolved.manifest
        val source: AndroidToolchainSelector.Source get() = resolved.source
    }

    suspend fun select(
        resolution: AndroidToolchainSelector.CandidateResolution,
        onAttempt: (AndroidToolchainSelector.Resolved) -> Unit = {},
        attempt: suspend (AndroidToolchainSelector.Resolved) -> String,
    ): Selection {
        check(resolution.candidates.isNotEmpty()) {
            resolution.failure ?: "No Android toolchain satisfies the project requirements"
        }
        val failures = mutableListOf<String>()
        for (candidate in resolution.candidates) {
            onAttempt(candidate)
            val runtimeVersion = try {
                attempt(candidate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val manifest = candidate.manifest
                failures += "${candidate.source.label} ${manifest.version} (JDK ${manifest.javaVersion}, ${manifest.runtime.mode.name.lowercase().replace('_', '-')}): ${error.message ?: "Gradle preflight failed"}"
                null
            }
            if (runtimeVersion != null) return Selection(candidate, runtimeVersion)
        }
        error(
            "No installed toolchain could start this project's Gradle Wrapper. " +
                failures.take(MAX_FAILURES).joinToString(" · ")
        )
    }

    private const val MAX_FAILURES = 12
}

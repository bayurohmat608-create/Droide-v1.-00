package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.atomic.AtomicReference








internal class AndroidProjectToolchainAuthority(
    private val projectRoot: File,
    private val selector: AndroidToolchainSelector,
    private val verifier: suspend (AndroidDevelopmentManager.ToolchainManifest, AndroidToolchainRequirements) -> String?,
) {
    private data class Binding(
        val resolved: AndroidToolchainSelector.Resolved,
        val sourceResolved: AndroidToolchainSelector.Resolved,
        val wrapperFingerprintSha256: String,
        val projectConfigurationFingerprintSha256: String,
    )

    private val binding = AtomicReference<Binding?>(null)

    suspend fun validBinding(requirements: AndroidToolchainRequirements): AndroidToolchainSelector.Resolved? {
        val current = binding.get() ?: return null
        val wrapperFingerprint = runCatching { GradleWrapperInspector.inspect(projectRoot).fingerprintSha256 }.getOrNull()
        val configurationFingerprint = runCatching { GradleProjectConfigurationFingerprint.compute(projectRoot) }.getOrNull()
        val wrapperStillMatches = wrapperFingerprint != null && wrapperFingerprint == current.wrapperFingerprintSha256
        val configurationStillMatches = configurationFingerprint != null && configurationFingerprint == current.projectConfigurationFingerprintSha256
        val sourceStillMatches = runSuspendCatching { selector.sourceCandidateStillPresent(current.sourceResolved) }.getOrDefault(false)
        val stillHealthy = wrapperStillMatches && configurationStillMatches && sourceStillMatches && verifier(current.resolved.manifest, requirements) == null
        if (stillHealthy) return current.resolved
        binding.compareAndSet(current, null)
        return null
    }

    suspend fun resolve(requirements: AndroidToolchainRequirements): AndroidToolchainSelector.Resolution =
        validBinding(requirements)?.let { AndroidToolchainSelector.Resolution(it) } ?: selector.resolve(requirements)

    suspend fun resolve(): AndroidToolchainSelector.Resolution {
        val requirements = AndroidProjectDetector.detect(projectRoot)?.toolchainRequirements() ?: AndroidToolchainRequirements()
        return resolve(requirements)
    }

    fun bind(
        manifest: AndroidDevelopmentManager.ToolchainManifest,
        sourceManifest: AndroidDevelopmentManager.ToolchainManifest,
        source: AndroidToolchainSelector.Source,
        wrapperFingerprintSha256: String,
        projectConfigurationFingerprintSha256: String,
    ) {
        require(wrapperFingerprintSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid Gradle Wrapper fingerprint" }
        require(projectConfigurationFingerprintSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid Gradle configuration fingerprint" }
        binding.set(
            Binding(
                resolved = AndroidToolchainSelector.Resolved(manifest, source),
                sourceResolved = AndroidToolchainSelector.Resolved(sourceManifest, source),
                wrapperFingerprintSha256 = wrapperFingerprintSha256,
                projectConfigurationFingerprintSha256 = projectConfigurationFingerprintSha256,
            )
        )
    }
}

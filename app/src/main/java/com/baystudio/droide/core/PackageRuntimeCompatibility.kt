package com.baystudio.droide.core

import kotlinx.serialization.Serializable






@Serializable
enum class DeclarativeGuestProfile { ALPINE_3_24_MUSL_ARM64, UBUNTU_24_04_GLIBC_ARM64 }

@Serializable
enum class DeclarativeLibcCompatibility { STATIC_OR_MUSL, INTERPRETED, GLIBC, UNKNOWN }

@Serializable
enum class DeclarativeIntegrationKind { CLI, DAP_STDIO, DAP_REVERSE_TCP }

@Serializable
enum class DeclarativeGuestCapability { LOOPBACK_TCP, PROCFS, PTRACE }

enum class GuestCapabilitySupport { CERTIFIED, DEVICE_CERTIFICATION_REQUIRED, UNSUPPORTED }

 
data class GuestRuntimeProfileDescriptor(
    val id: DeclarativeGuestProfile,
    val environmentId: String,
    val operatingSystem: String,
    val distribution: String,
    val distributionVersion: String,
    val architecture: String,
    val libcFamily: String,
    val certifiedCapabilities: Set<DeclarativeGuestCapability>,
    val deviceCertificationCapabilities: Set<DeclarativeGuestCapability>,
) {
    fun validate() {
        require(environmentId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid guest environment id" }
        require(operatingSystem == "linux") { "Only Linux guest profiles are currently supported" }
        require(distribution.matches(Regex("[a-z0-9._-]{1,40}"))) { "Invalid guest distribution" }
        require(distributionVersion.matches(Regex("[0-9][A-Za-z0-9._-]{0,39}"))) { "Invalid guest distribution version" }
        require(architecture in setOf("arm64")) { "Unsupported guest architecture" }
        require(libcFamily in setOf("musl", "glibc")) { "Unsupported guest libc family" }
        require(certifiedCapabilities.intersect(deviceCertificationCapabilities).isEmpty()) { "Guest capability evidence overlaps" }
    }
}

 
object GuestRuntimeProfiles {
    val ALPINE_3_24_MUSL_ARM64 = GuestRuntimeProfileDescriptor(
        id = DeclarativeGuestProfile.ALPINE_3_24_MUSL_ARM64,
        environmentId = WorkstationGuestEnvironmentSpec.ID,
        operatingSystem = "linux",
        distribution = "alpine",
        distributionVersion = "3.24",
        architecture = "arm64",
        libcFamily = "musl",
        certifiedCapabilities = setOf(DeclarativeGuestCapability.LOOPBACK_TCP, DeclarativeGuestCapability.PROCFS),
        deviceCertificationCapabilities = setOf(DeclarativeGuestCapability.PTRACE),
    ).also(GuestRuntimeProfileDescriptor::validate)

    val UBUNTU_24_04_GLIBC_ARM64 = GuestRuntimeProfileDescriptor(
        id = DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64,
        environmentId = FoundryUbuntuGuestEnvironmentSpec.ID,
        operatingSystem = "linux",
        distribution = "ubuntu",
        distributionVersion = "24.04",
        architecture = "arm64",
        libcFamily = "glibc",
        certifiedCapabilities = setOf(DeclarativeGuestCapability.LOOPBACK_TCP, DeclarativeGuestCapability.PROCFS),
        deviceCertificationCapabilities = setOf(DeclarativeGuestCapability.PTRACE),
    ).also(GuestRuntimeProfileDescriptor::validate)

     
    val current: GuestRuntimeProfileDescriptor get() = ALPINE_3_24_MUSL_ARM64

    fun get(profile: DeclarativeGuestProfile): GuestRuntimeProfileDescriptor = when (profile) {
        DeclarativeGuestProfile.ALPINE_3_24_MUSL_ARM64 -> ALPINE_3_24_MUSL_ARM64
        DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64 -> UBUNTU_24_04_GLIBC_ARM64
    }
}

@Serializable
data class DeclarativeRuntimeCompatibility(
    val guestProfile: DeclarativeGuestProfile,
    val libc: DeclarativeLibcCompatibility,
    val integration: DeclarativeIntegrationKind = DeclarativeIntegrationKind.CLI,
    val requiredCommands: List<String> = emptyList(),
    val requiredFamilyIds: List<String> = emptyList(),
    val requiredGuestPackages: List<String> = emptyList(),
    val requiredGuestCapabilities: List<DeclarativeGuestCapability> = emptyList(),
    val evidenceUrl: String,
) {
    fun validate() {
        require(evidenceUrl.startsWith("https://") && evidenceUrl.length <= 500) { "Runtime compatibility evidence must use bounded HTTPS" }
        require(requiredCommands.size <= 32 && requiredCommands.distinct().size == requiredCommands.size && requiredCommands.all { it.matches(COMMAND_RE) }) {
            "Runtime compatibility declares invalid guest commands"
        }
        require(requiredFamilyIds.size <= 32 && requiredFamilyIds.distinct().size == requiredFamilyIds.size && requiredFamilyIds.all { it.matches(FAMILY_RE) }) {
            "Runtime compatibility declares invalid package families"
        }
        require(requiredGuestPackages.size <= 64 && requiredGuestPackages.distinct().size == requiredGuestPackages.size && requiredGuestPackages.all { it.matches(GUEST_PACKAGE_RE) }) {
            "Runtime compatibility declares invalid guest packages"
        }
        require(requiredGuestCapabilities.size <= 16 && requiredGuestCapabilities.distinct().size == requiredGuestCapabilities.size) {
            "Runtime compatibility declares duplicate/excess guest capabilities"
        }
        if (integration == DeclarativeIntegrationKind.DAP_REVERSE_TCP) {
            require(DeclarativeGuestCapability.LOOPBACK_TCP in requiredGuestCapabilities) {
                "Reverse-TCP integration must explicitly require loopback TCP capability"
            }
        }
    }

     
    fun genericInstallerReady(): Boolean {
        validate()
        val descriptor = GuestRuntimeProfiles.get(guestProfile)
        val libcReady = when (descriptor.libcFamily) {
            "musl" -> libc in setOf(DeclarativeLibcCompatibility.STATIC_OR_MUSL, DeclarativeLibcCompatibility.INTERPRETED)
            "glibc" -> libc in setOf(DeclarativeLibcCompatibility.GLIBC, DeclarativeLibcCompatibility.INTERPRETED)
            else -> false
        }
        return libcReady &&
            integration in setOf(DeclarativeIntegrationKind.CLI, DeclarativeIntegrationKind.DAP_STDIO, DeclarativeIntegrationKind.DAP_REVERSE_TCP) &&
            requiredGuestCapabilities.all { PackageRuntimeCompatibilityPolicy.capabilitySupport(guestProfile, it) == GuestCapabilitySupport.CERTIFIED }
    }

    companion object {
        private val COMMAND_RE = Regex("[A-Za-z0-9._+-]{1,80}")
        private val FAMILY_RE = Regex("[A-Za-z0-9._-]{1,120}")
        private val GUEST_PACKAGE_RE = Regex("[A-Za-z0-9][A-Za-z0-9+_.@-]{0,119}")
    }
}

object PackageRuntimeCompatibilityPolicy {
    val CURRENT_GUEST_PROFILE: String get() = GuestRuntimeProfiles.current.id.name

    fun capabilitySupport(
        profile: DeclarativeGuestProfile,
        capability: DeclarativeGuestCapability,
    ): GuestCapabilitySupport {
        val descriptor = GuestRuntimeProfiles.get(profile)
        return when (capability) {
            in descriptor.certifiedCapabilities -> GuestCapabilitySupport.CERTIFIED
            in descriptor.deviceCertificationCapabilities -> GuestCapabilitySupport.DEVICE_CERTIFICATION_REQUIRED
            else -> GuestCapabilitySupport.UNSUPPORTED
        }
    }

    fun requireArtifactRuntimeCompatible(contract: DeclarativeRuntimeCompatibility) {
        contract.validate()
        val descriptor = GuestRuntimeProfiles.get(contract.guestProfile)
        val libcReady = when (descriptor.libcFamily) {
            "musl" -> contract.libc in setOf(DeclarativeLibcCompatibility.STATIC_OR_MUSL, DeclarativeLibcCompatibility.INTERPRETED)
            "glibc" -> contract.libc in setOf(DeclarativeLibcCompatibility.GLIBC, DeclarativeLibcCompatibility.INTERPRETED)
            else -> false
        }
        require(libcReady) {
            "Package libc/runtime compatibility is not certified for ${descriptor.distribution} ${descriptor.distributionVersion}/${descriptor.libcFamily}"
        }
        contract.requiredGuestCapabilities.forEach { capability ->
            require(capabilitySupport(contract.guestProfile, capability) == GuestCapabilitySupport.CERTIFIED) {
                "Guest capability ${capability.name} still requires device certification"
            }
        }
    }

    fun requireGenericInstallerCompatible(contract: DeclarativeRuntimeCompatibility) = requireArtifactRuntimeCompatible(contract)

    suspend fun verifyGuestCommands(environment: ReviewedGuestExecutionEnvironment, contract: DeclarativeRuntimeCompatibility) {
        requireArtifactRuntimeCompatible(contract)
        val descriptor = GuestRuntimeProfiles.get(contract.guestProfile)
        require(environment.environmentId == descriptor.environmentId) {
            "Installer guest ${environment.environmentId} does not match reviewed profile ${descriptor.environmentId}"
        }
        contract.requiredCommands.forEach { command ->
            val result = environment.execute(listOf("sh", "-c", "command -v '$command' >/dev/null 2>&1"), maxOutputBytes = 8_192)
            check(result.exitCode == 0) { "Required guest command '$command' is unavailable" }
        }
    }
}

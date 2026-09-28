package com.baystudio.droide.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class DeclarativeReverseTcpCapability(
    val connectArgument: String,
    val authTokenArgument: String,
    val authHeaderName: String = "Auth-Token",
) {
    fun validate() = toRuntimeContract().validate()

    fun toRuntimeContract(): AuthenticatedReverseTcpContract = AuthenticatedReverseTcpContract(
        connectArgument = connectArgument,
        authTokenArgument = authTokenArgument,
        authHeaderName = authHeaderName,
    )
}

 
@Serializable
data class DeclarativeDebugAdapterCapability(
    val id: String,
    val extensions: Set<String>,
    val args: List<String>,
    val reverseTcp: DeclarativeReverseTcpCapability? = null,
    val requiredGuestCapabilities: List<DeclarativeGuestCapability> = emptyList(),
    val autoConfigure: Boolean = false,
) {
    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "Invalid managed debug-adapter id" }
        require(extensions.size in 1..32 && extensions.all { it.matches(Regex("[A-Za-z0-9_+.-]{1,24}")) }) { "Invalid managed debug-adapter extensions" }
        require(args.size <= 32 && args.all { it.length <= 240 && '\u0000' !in it && '\n' !in it && '\r' !in it }) { "Invalid managed debug-adapter arguments" }
        require(requiredGuestCapabilities.size <= 16 && requiredGuestCapabilities.distinct().size == requiredGuestCapabilities.size) {
            "Invalid managed debug-adapter capability requirements"
        }
        reverseTcp?.validate()
    }
}

object PackageProtocolCapabilityPolicy {
    fun installerReady(manifest: DeclarativePackageManifest): Boolean {
        val runtime = manifest.githubRelease?.runtimeCompatibility ?: return manifest.debugAdapter == null
        val adapter = manifest.debugAdapter
        adapter?.validate()
        return when (runtime.integration) {
            DeclarativeIntegrationKind.CLI -> adapter == null
            DeclarativeIntegrationKind.DAP_STDIO -> adapter != null && adapter.reverseTcp == null
            DeclarativeIntegrationKind.DAP_REVERSE_TCP -> adapter?.reverseTcp != null
        }
    }

     
    fun runtimeReady(manifest: DeclarativePackageManifest): Boolean {
        val adapter = manifest.debugAdapter ?: return true
        val runtime = manifest.githubRelease?.runtimeCompatibility ?: return false
        adapter.validate()
        return adapter.requiredGuestCapabilities.all { capability ->
            PackageRuntimeCompatibilityPolicy.capabilitySupport(runtime.guestProfile, capability) == GuestCapabilitySupport.CERTIFIED
        }
    }
}

object PackageProtocolCapabilityEngine {
    fun debugAdapters(
        manifests: List<DeclarativePackageManifest>,
        records: List<ManagedPackageRecord>,
    ): List<DebugAdapterRegistry.Spec> {
        val activeFamilies = records.asSequence().filter { it.active }.map { it.familyId }.toSet()
        return manifests.asSequence()
            .filter { it.certification == DeclarativePackageCertification.CERTIFIED && it.familyId in activeFamilies }
            .mapNotNull { manifest ->
                val capability = manifest.debugAdapter ?: return@mapNotNull null
                val runtime = manifest.githubRelease?.runtimeCompatibility ?: return@mapNotNull null
                if (!PackageProtocolCapabilityPolicy.installerReady(manifest) || !PackageProtocolCapabilityPolicy.runtimeReady(manifest)) return@mapNotNull null
                val reverse = capability.reverseTcp?.toRuntimeContract()
                val transport = when (runtime.integration) {
                    DeclarativeIntegrationKind.DAP_STDIO -> DebugAdapterTransport.STDIO
                    DeclarativeIntegrationKind.DAP_REVERSE_TCP -> DebugAdapterTransport.AUTHENTICATED_REVERSE_TCP
                    DeclarativeIntegrationKind.CLI -> return@mapNotNull null
                }
                DebugAdapterRegistry.Spec(
                    id = capability.id,
                    extensions = capability.extensions.map { it.removePrefix(".").lowercase() }.toSet(),
                    commandCandidates = listOf(listOf(manifest.command) + capability.args),
                    defaultArguments = { _, _ -> buildJsonObject { } },
                    source = "managed-package:${manifest.familyId}",
                    transport = transport,
                    reverseTcp = reverse,
                    autoConfigure = capability.autoConfigure,
                ).also(DebugAdapterRegistry.Spec::validate)
            }
            .distinctBy { it.id }
            .toList()
    }
}

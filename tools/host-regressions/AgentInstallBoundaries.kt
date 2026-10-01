package com.baystudio.droide.core

import java.io.File

// The verifier extracts package/bridge data contracts from production source separately.
class DeviceBridgeManager {
    suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult = error("ADB is outside the local fixture")
    suspend fun shell(command: String): BridgeShellResult = error("ADB is outside the local fixture")
    suspend fun pull(path: String, output: File): Unit = error("ADB is outside the local fixture")
    suspend fun push(input: File, path: String, mode: Int): Unit = error("ADB is outside the local fixture")
    companion object {
        fun requireSafeRemotePath(path: String) { require(path.startsWith('/')) }
        fun shellQuote(value: String) = LocalExecutionSubstrate.shellQuote(value)
    }
}

internal class LocalManagedPackageAuthority {
    val backendId = PackageBackendId.LOCAL_APP
    var adopted: ManagedPackageRecord? = null
    var failAdoption = false
    fun ownedReceipt(family: String, version: String) = adopted?.takeIf { it.familyId == family && it.version == version }
    suspend fun adopt(record: ManagedPackageRecord) { check(!failAdoption) { "Fixture receipt write failure" }; adopted = record }
}

internal class FoundryUbuntuGuestEnvironmentManager {
    val backendId = PackageBackendId.LOCAL_APP
    var ensureCount = 0
    suspend fun ensureInstalled(): String { ensureCount++; return "Fixture guest" }
    fun launcherPath() = "/fixture/ubuntu-launcher"
}

internal data class SafeHttpResponse(val code: Int, val body: String, val finalUrl: String)
internal object SafeHttp {
    var response: SafeHttpResponse? = null
    suspend fun get(rawUrl: String, accept: String, maxBytes: Int): SafeHttpResponse = response ?: error("Fixture metadata not configured")
}
internal object LocalUbuntuJdkPolicy { const val FAMILY = "toolchain.jdk" }

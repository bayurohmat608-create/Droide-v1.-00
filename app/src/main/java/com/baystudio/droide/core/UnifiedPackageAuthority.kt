package com.baystudio.droide.core

import kotlinx.coroutines.flow.StateFlow

data class UnifiedPackageSearchResult(
    val familyId: String,
    val name: String,
    val description: String,
    val versions: List<String>,
    val installedVersion: String?
)

data class UnifiedPackageInfo(
    val familyId: String,
    val version: String,
    val state: String,
    val sizeBytes: Long,
    val dependencies: List<String>
)

data class UnifiedPackageHealthStatus(
    val familyId: String,
    val healthy: Boolean,
    val messages: List<String>
)

data class UnifiedPackageTransactionResult(
    val success: Boolean,
    val message: String
)

data class UnifiedPackageTransactionState(
    val active: Boolean,
    val operationId: String?,
    val progress: Float?,
    val logs: List<String> = emptyList(),
    val initiator: String? = null,
    val cancelable: Boolean = false
)

interface UnifiedPackageAuthority {
    suspend fun search(query: String): List<UnifiedPackageSearchResult>
    suspend fun info(familyId: String, version: String): UnifiedPackageInfo?
    suspend fun install(familyId: String, version: String): UnifiedPackageTransactionResult
    suspend fun upgrade(familyId: String): UnifiedPackageTransactionResult
    suspend fun downgrade(familyId: String, version: String): UnifiedPackageTransactionResult
    suspend fun activate(familyId: String, version: String, workspaceId: String? = null): UnifiedPackageTransactionResult
    suspend fun health(familyId: String): UnifiedPackageHealthStatus
    suspend fun repair(familyId: String): UnifiedPackageTransactionResult
    suspend fun repairVersion(familyId: String, version: String): UnifiedPackageTransactionResult =
        UnifiedPackageTransactionResult(false, "This provider does not support exact-version repair")
    suspend fun uninstall(familyId: String, version: String): UnifiedPackageTransactionResult
    suspend fun list(): List<UnifiedPackageInfo>
    
    val transactionState: StateFlow<UnifiedPackageTransactionState>
}

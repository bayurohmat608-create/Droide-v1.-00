package com.baystudio.droide.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class DefaultUnifiedPackageAuthority(
    private val registry: ManagedPackageRegistry,
    private val installer: ManagedPackageInstaller,
    private val catalog: PackageSourceCatalog,
    private val extensionInstaller: ManagedExtensionInstaller? = null,
    private val itemResolver: (suspend (String, String) -> ExtensionVersionState?)? = null,
    private val workspaceToolchains: WorkspaceToolchainPreferences? = null
) : UnifiedPackageAuthority {

    private val transactionMutex = Mutex()
    private val _transactionState = MutableStateFlow(UnifiedPackageTransactionState(false, null, null, emptyList(), null, false))
    override val transactionState: StateFlow<UnifiedPackageTransactionState> = _transactionState.asStateFlow()

    override suspend fun search(query: String): List<UnifiedPackageSearchResult> {
        val all = catalog.entries
        val matching = all.filter { it.familyId.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
        val installed = registry.list()
        
        return matching.map { entry ->
            val installedRec = installed.find { it.familyId == entry.familyId && it.active }
            UnifiedPackageSearchResult(
                familyId = entry.familyId,
                name = entry.packageName,
                description = "Source: ${entry.source}",
                
                versions = installed.filter { it.familyId == entry.familyId }.map { it.version }.distinct().ifEmpty { listOf("latest") },
                installedVersion = installedRec?.version
            )
        }
    }

    override suspend fun info(familyId: String, version: String): UnifiedPackageInfo? {
        val record = registry.list().find { it.familyId == familyId && (version == "latest" || it.version == version) }
            ?: return null
            
        return UnifiedPackageInfo(
            familyId = record.familyId,
            version = record.version,
            state = if (record.active) "active" else "inactive",
            sizeBytes = 0L, 
            dependencies = record.dependencies
        )
    }

    override suspend fun install(familyId: String, version: String): UnifiedPackageTransactionResult {
        return transactionMutex.withLock {
            val opId = "install-${familyId}-${UUID.randomUUID().toString().take(8)}"
            try {
                _transactionState.value = UnifiedPackageTransactionState(true, opId, 0f, emptyList(), "SYSTEM", false)
                
                if (extensionInstaller == null || itemResolver == null) {
                    return@withLock UnifiedPackageTransactionResult(false, "Installer dependencies not wired.").also {
                        _transactionState.value = UnifiedPackageTransactionState(false, null, null, emptyList(), null, false)
                    }
                }
                val item = itemResolver.invoke(familyId, version) 
                    ?: return@withLock UnifiedPackageTransactionResult(false, "Item $familyId@$version not found in catalog.").also {
                        _transactionState.value = UnifiedPackageTransactionState(false, null, null, emptyList(), null, false)
                    }
                val plan = extensionInstaller.prepare(item)
                val msg = extensionInstaller.install(plan)
                _transactionState.value = UnifiedPackageTransactionState(false, null, null, emptyList(), null, false)
                UnifiedPackageTransactionResult(true, msg)
            } catch (e: Exception) {
                _transactionState.value = UnifiedPackageTransactionState(false, null, null, emptyList(), null, false)
                UnifiedPackageTransactionResult(false, e.message ?: "Installation failed.")
            }
        }
    }

    override suspend fun upgrade(familyId: String): UnifiedPackageTransactionResult = install(familyId, "latest")

    override suspend fun downgrade(familyId: String, version: String): UnifiedPackageTransactionResult = install(familyId, version)

    override suspend fun activate(familyId: String, version: String, workspaceId: String?): UnifiedPackageTransactionResult {
        return transactionMutex.withLock {
            val record = registry.list().find { it.familyId == familyId && it.version == version }
                ?: return@withLock UnifiedPackageTransactionResult(false, "Package not found locally.")
            try {
                if (workspaceId != null && workspaceToolchains != null) {
                    workspaceToolchains.set(familyId, version)
                    UnifiedPackageTransactionResult(true, "Activated $familyId@$version for workspace $workspaceId.")
                } else {
                    val local = extensionInstaller?.localPackageAuthority
                    if (local?.owns(record) == true) local.activate(record) else installer.activate(record)
                    UnifiedPackageTransactionResult(true, "Activated $familyId@$version globally.")
                }
            } catch (e: Exception) {
                UnifiedPackageTransactionResult(false, e.message ?: "Activation failed.")
            }
        }
    }

    override suspend fun health(familyId: String): UnifiedPackageHealthStatus {
        val record = registry.list().find { it.familyId == familyId && it.active }
            ?: return UnifiedPackageHealthStatus(familyId, false, listOf("Not installed or active"))
            
        val local = extensionInstaller?.localPackageAuthority
        val isHealthy = if (local?.owns(record) == true) local.verify(record) else installer.verify(record)
        return UnifiedPackageHealthStatus(familyId, isHealthy, if (isHealthy) emptyList() else listOf("Verification failed"))
    }

    override suspend fun repair(familyId: String): UnifiedPackageTransactionResult {
        return transactionMutex.withLock {
            val opId = "repair-${familyId}-${UUID.randomUUID().toString().take(8)}"
            try {
                _transactionState.value = UnifiedPackageTransactionState(true, opId, 0f, emptyList(), "SYSTEM", false)
                if (extensionInstaller == null || itemResolver == null) {
                    return@withLock UnifiedPackageTransactionResult(false, "Installer dependencies not wired.").also {
                        _transactionState.value = UnifiedPackageTransactionState(false, null, null, emptyList(), null, false)
                    }
                }
                val item = itemResolver.invoke(familyId, "latest") ?: return@withLock UnifiedPackageTransactionResult(false, "Item not found.").also {
                        _transactionState.value = UnifiedPackageTransactionState(false, null, null, emptyList(), null, false)
                    }
                val msg = extensionInstaller.repair(item)
                _transactionState.value = UnifiedPackageTransactionState(false, null, null, emptyList(), null, false)
                UnifiedPackageTransactionResult(true, msg)
            } catch(e: Exception) {
                _transactionState.value = UnifiedPackageTransactionState(false, null, null, emptyList(), null, false)
                UnifiedPackageTransactionResult(false, e.message ?: "Repair failed.")
            }
        }
    }

    override suspend fun uninstall(familyId: String, version: String): UnifiedPackageTransactionResult {
        return transactionMutex.withLock {
            val record = registry.list().find { it.familyId == familyId && it.version == version }
                ?: return@withLock UnifiedPackageTransactionResult(false, "Package not found.")
            val opId = "uninstall-${familyId}-${UUID.randomUUID().toString().take(8)}"
            try {
                _transactionState.value = UnifiedPackageTransactionState(true, opId, 0f, emptyList(), "SYSTEM", false)
                val local = extensionInstaller?.localPackageAuthority
                val msg = if (local?.owns(record) == true) {
                    val item = itemResolver?.invoke(familyId, version) ?: error("Local package catalog item is missing")
                    requireNotNull(extensionInstaller).uninstall(item)
                } else installer.uninstall(record)
                _transactionState.value = UnifiedPackageTransactionState(false, null, null, emptyList(), null, false)
                UnifiedPackageTransactionResult(true, msg)
            } catch (e: Exception) {
                _transactionState.value = UnifiedPackageTransactionState(false, null, null, emptyList(), null, false)
                UnifiedPackageTransactionResult(false, e.message ?: "Uninstall failed.")
            }
        }
    }

    override suspend fun list(): List<UnifiedPackageInfo> {
        return registry.list().map { record ->
            UnifiedPackageInfo(
                familyId = record.familyId,
                version = record.version,
                state = if (record.active) "active" else "inactive",
                sizeBytes = 0L,
                dependencies = record.dependencies
            )
        }
    }
}

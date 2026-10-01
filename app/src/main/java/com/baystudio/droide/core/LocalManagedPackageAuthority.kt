package com.baystudio.droide.core

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Never uses an ADB transport.
class LocalManagedPackageAuthority(
    context: Context,
    private val registry: ManagedPackageRegistry,
) {
    val backendId = PackageBackendId.LOCAL_APP
    private val root = context.applicationContext.filesDir.canonicalPath
    private val managed = File(root, "managed")
    private val bin = File(managed, "bin")

    private fun removalJournal(record: ManagedPackageRecord) = File(
        root, "packages/.trash/${record.familyId}-${record.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")}",
    )

    fun requirePilot(familyId: String) {
        check(familyId == PILOT_FAMILY) {
            "Local package authority is currently certified only for $PILOT_FAMILY; $familyId remains gated"
        }
    }

    fun owns(record: ManagedPackageRecord): Boolean =
        PackageBackendContract.recordOwner(record.installRoot, record.metadata, root) == backendId

    fun hasReceipt(familyId: String, version: String): Boolean =
        registry.find(familyId, version)?.let(::owns) == true

    private fun requireOwner(record: ManagedPackageRecord) {
        requirePilot(record.familyId)
        PackageBackendContract.requireOwner(backendId, record.installRoot, record.metadata, root)
        check(record.installRoot.startsWith("$root/packages/") &&
            record.installRoot.removePrefix("$root/packages/").split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Local package receipt escaped app-owned package storage"
        }
        LocalExecutionSubstrate.requireSafeLocalPath(record.installRoot)
    }

    // Guest files must already be staged and integrity/health checked before the receipt commits.
    suspend fun adopt(record: ManagedPackageRecord) = ManagedPackageMutationGate.mutex.withLock {
        requireOwner(record)
        require(record.dependencies.isEmpty()) { "Pilot family cannot acquire cross-backend dependencies" }
        val before = registry.list()
        check(before.filter { it.familyId == record.familyId }.all(::owns)) {
            "Package family already belongs to another backend"
        }
        check(verifyPayload(record)) { "Local package failed integrity or health verification" }
        val next = before.filterNot { it.familyId == record.familyId && it.version == record.version }
            .map { if (it.familyId == record.familyId) it.copy(active = false) else it } +
            record.copy(active = true, metadata = record.metadata + (PackageBackendContract.METADATA_KEY to backendId.name))
        PackageProjectionCommit.commit(before, next, ::project, registry::replaceAll)
    }

    suspend fun verify(record: ManagedPackageRecord): Boolean = ManagedPackageMutationGate.mutex.withLock {
        if (!owns(record) || record.familyId != PILOT_FAMILY) return@withLock false
        verifyPayload(record)
    }

    suspend fun activate(record: ManagedPackageRecord) = ManagedPackageMutationGate.mutex.withLock {
        requireOwner(record)
        val before = registry.list()
        check(before.filter { it.familyId == record.familyId }.all(::owns)) { "Package family belongs to another backend" }
        val current = before.firstOrNull { it.familyId == record.familyId && it.version == record.version }
            ?: error("Local package receipt is missing")
        check(verifyPayload(current)) { "Local package failed verification before activation" }
        val next = before.map { if (it.familyId == record.familyId) it.copy(active = it.version == record.version) else it }
        PackageProjectionCommit.commit(before, next, ::project, registry::replaceAll)
    }

    suspend fun uninstall(record: ManagedPackageRecord): String = ManagedPackageMutationGate.mutex.withLock {
        requireOwner(record)
        val before = registry.list()
        check(before.any { it.familyId == record.familyId && it.version == record.version }) { "Local receipt is missing" }
        val key = "${record.familyId}@${record.version}"
        check(before.none { it.familyId != record.familyId && key in it.dependencies }) { "Local package has dependents" }
        val source = File(record.installRoot)
        val trash = removalJournal(record)
        LocalExecutionSubstrate.requireSafeLocalPath(trash.absolutePath)
        check(!trash.exists()) { "Interrupted local package removal needs recovery before retry" }
        check(trash.parentFile?.mkdirs() == true || trash.parentFile?.isDirectory == true) { "Cannot prepare local removal journal" }
        check(source.isDirectory && !PathSecurity.isSymbolicLink(source) && source.renameTo(trash)) {
            "Could not stage local package removal"
        }
        val next = before.filterNot { it.familyId == record.familyId && it.version == record.version }.toMutableList()
        if (record.active) {
            val fallback = next.filter { it.familyId == record.familyId }.maxByOrNull { it.installedAtEpochMs }
            if (fallback != null) {
                val index = next.indexOfFirst { it.familyId == fallback.familyId && it.version == fallback.version }
                next[index] = fallback.copy(active = true)
            }
        }
        try {
            PackageProjectionCommit.commit(before, next, ::project, registry::replaceAll)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                if (!trash.renameTo(source)) failure.addSuppressed(IllegalStateException("Could not restore local package removal journal"))
                try { project(before) } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            }
            throw failure
        }
        


        PathSecurity.deleteTreeNoFollow(trash)
        "Uninstalled ${record.familyId} ${record.version}."
    }

     
    suspend fun reconcile() = ManagedPackageMutationGate.mutex.withLock {
        val records = registry.list().filter { it.familyId == PILOT_FAMILY && owns(it) }
        for (record in records) {
            requireOwner(record)
            val trash = removalJournal(record)
            LocalExecutionSubstrate.requireSafeLocalPath(trash.absolutePath)
            if (trash.exists() && !File(record.installRoot).exists()) {
                check(!PathSecurity.isSymbolicLink(trash) && trash.renameTo(File(record.installRoot))) {
                    "Cannot restore interrupted local package removal"
                }
            }
        }
        val trashRoot = File(root, "packages/.trash")
        trashRoot.listFiles().orEmpty().forEach { trash ->
            if (!trash.name.startsWith("$PILOT_FAMILY-")) return@forEach
            LocalExecutionSubstrate.requireSafeLocalPath(trash.absolutePath)
            val owner = records.firstOrNull { removalJournal(it).name == trash.name }
            if (owner == null || File(owner.installRoot).exists()) {
                check(PathSecurity.deleteTreeNoFollow(trash)) { "Could not clean committed local removal journal" }
            }
        }
        project(registry.list())
    }

    private suspend fun verifyPayload(record: ManagedPackageRecord): Boolean = withContext(Dispatchers.IO) {
        if (!owns(record) || record.familyId != PILOT_FAMILY) return@withContext false
        if (runCatching { requireOwner(record) }.isFailure) return@withContext false
        val recipe = WorkstationGuestPackageCatalog.find(record.familyId, record.version) ?: return@withContext false
        if (record.commands.keys != recipe.commands.keys) return@withContext false
        if (record.metadata[WORKSTATION_GUEST_ADMISSION_CONTRACT_KEY] != recipe.admissionContractSha256()) return@withContext false
        if (record.dependencies.isNotEmpty()) return@withContext false
        val path = LocalExecutionSubstrate.shellBounded(
            "cd ${LocalExecutionSubstrate.shellQuote(record.installRoot)} && test -f SHA256SUMS && toybox sha256sum -c SHA256SUMS",
            maxOutputBytes = 128_000,
        )
        if (path.exitCode != 0) return@withContext false
        for ((name, target) in record.commands) {
            if (target != "${record.installRoot}/payload/bin/$name") return@withContext false
            LocalExecutionSubstrate.requireSafeLocalPath(target)
            if (LocalExecutionSubstrate.shell("test -x ${LocalExecutionSubstrate.shellQuote(target)}").exitCode != 0) return@withContext false
        }
        for (health in recipe.requiredManagedHealthChecks()) {
            val executable = "${record.installRoot}/payload/${health.executable}"
            LocalExecutionSubstrate.requireSafeLocalPath(executable)
            

            val command = (listOf("/system/bin/sh", executable) + health.args)
                .joinToString(" ") { LocalExecutionSubstrate.shellQuote(it) }
            if (LocalExecutionSubstrate.shellBounded(command, maxOutputBytes = 64_000).exitCode != 0) return@withContext false
        }
        true
    }

    private suspend fun project(records: List<ManagedPackageRecord>) = withContext(Dispatchers.IO) {
        LocalExecutionSubstrate.requireContextRoot(root)
        val active = records.filter { it.active && owns(it) }
        active.forEach(::requireOwner)
        val owners = linkedMapOf<String, String>()
        active.forEach { record -> record.commands.forEach { (name, _) ->
            check(owners.putIfAbsent(name, "${record.familyId}@${record.version}") == null) {
                "Local command collision for $name"
            }
        } }
        check(managed.mkdirs() || managed.isDirectory) { "Cannot prepare local command projection" }
        val stage = File(managed, ".staging")
        val previous = File(managed, ".previous")
        if (!bin.exists() && previous.exists()) check(previous.renameTo(bin)) { "Cannot recover local projection journal" }
        check(PathSecurity.deleteTreeNoFollow(stage)) { "Cannot clean local command staging" }
        check(stage.mkdirs()) { "Cannot create local command staging" }
        try {
            active.forEach { record -> record.commands.forEach { (name, target) ->
                require(name.matches(Regex("[A-Za-z0-9._+-]{1,80}")))
                check(target.startsWith("${record.installRoot}/")) { "Local command target escaped package" }
                val script = "#!/system/bin/sh\nexec /system/bin/sh ${LocalExecutionSubstrate.shellQuote(target)} \"${'$'}@\"\n"
                val output = File(stage, name)
                LocalExecutionSubstrate.requireSafeLocalPath(output.absolutePath)
                ByteArrayInputStream(script.toByteArray()).use { LocalExecutionSubstrate.pushStream(it, output.absolutePath, 493) }
            } }
            check(PathSecurity.deleteTreeNoFollow(previous)) { "Cannot clear prior local projection" }
            if (bin.exists()) check(bin.renameTo(previous)) { "Cannot journal prior local projection" }
            if (!stage.renameTo(bin)) {
                if (previous.exists()) check(previous.renameTo(bin)) { "Cannot restore prior local projection" }
                error("Cannot activate local command projection")
            }
            check(PathSecurity.deleteTreeNoFollow(previous)) { "Cannot clean prior local projection" }
        } finally {
            PathSecurity.deleteTreeNoFollow(stage)
        }
    }

    companion object { const val PILOT_FAMILY = "cli.jq" }
}

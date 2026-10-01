package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext







object FoundryUbuntuGuestEnvironmentSpec {
    const val ID = "ubuntu-base-24.04.5-arm64"
    const val VERSION = "Ubuntu 24.04"
    const val ROOT_DIR = "ubuntu-base-24.04.5"
    const val PROFILE_REVISION = "foundry-glibc-r2-layered"
    const val LEGACY_PROFILE_REVISION = "foundry-glibc-r1"
    const val LEGACY_BROAD_BASE_MARKER = "legacy_broad_base_preserved=1"
    const val LEGACY_LAYER_OWNERSHIP_MARKER = "legacy_layer_ownership_complete=1"
    const val LEGACY_PHYSICAL_COMPACTION_MARKER = "legacy_physical_compaction_complete=1"
    const val LAYER_RECORD_VERSION = "24.04.5.r2"
    const val LAYER_METADATA_KIND = "droide.runtime.kind"
    const val LAYER_METADATA_PACKAGES = "droide.runtime.packages"
    const val LAYER_KIND = "ubuntu-apt-layer"

    



    val baselinePackages = listOf(
        "ca-certificates",
    )

     
    val canonicalBasePackages = FoundryUbuntuBaseManifest.packages

    

    val legacyBroadRootPackages = listOf(
        "ca-certificates", "binutils", "curl", "git", "gnupg2", "libc6-dev",
        "libcurl4-openssl-dev", "libedit2", "libgcc-13-dev", "libncurses-dev",
        "libpython3-dev", "libsqlite3-0", "libstdc++-13-dev", "libxml2-dev",
        "libz3-dev", "pkg-config", "python3", "tzdata", "unzip", "zip", "zlib1g-dev",
        "libicu74", "libssl3", "libssl3t64", "libunwind8", "libkrb5-3",
    )

    

    const val MAX_LAYER_PACKAGES = 48
    const val LAYER_TRANSACTION_OVERHEAD_BYTES = 64L * 1024L * 1024L
    private val PACKAGE_RE = Regex("[A-Za-z0-9][A-Za-z0-9+_.@-]{0,119}")

    fun normalizeLayerPackages(packages: List<String>): List<String> {
        require(packages.size <= MAX_LAYER_PACKAGES) { "Ubuntu capability layer is too large" }
        require(packages.all { PACKAGE_RE.matches(it) }) { "Ubuntu capability layer contains an invalid package name" }
        return packages.distinct().sorted()
    }

    val rootfsArtifact = TrustedArtifactSpec(
        id = "ubuntu-base-24.04.5-arm64",
        url = FoundryUbuntuBaseManifest.ROOTFS_URL,
        sha256 = FoundryUbuntuBaseManifest.ROOTFS_SHA256,
        fileName = "ubuntu-base-24.04.5-base-arm64.tar.gz",
        maxBytes = FoundryUbuntuBaseManifest.ROOTFS_BYTES,
        expectedBytes = FoundryUbuntuBaseManifest.ROOTFS_BYTES,
    )
}

 
interface ReviewedGuestExecutionEnvironment {
    val backendId: PackageBackendId
    val environmentId: String
    val environmentVersion: String
    fun launcherPath(): String
    suspend fun isHealthy(): Boolean
    suspend fun ensureInstalled(): String
    suspend fun execute(argv: List<String>, maxOutputBytes: Int = 1_500_000): ExecResult
}

 
class AlpineReviewedGuestEnvironment(
    context: Context,
    
    private val delegate: WorkstationGuestEnvironmentManager = WorkstationGuestEnvironmentManager(context.applicationContext),
) : ReviewedGuestExecutionEnvironment {
    override val backendId: PackageBackendId get() = delegate.backendId
    override val environmentId: String = WorkstationGuestEnvironmentSpec.ID
    override val environmentVersion: String = WorkstationGuestEnvironmentSpec.VERSION
    override fun launcherPath(): String = delegate.launcherPath()
    override suspend fun isHealthy(): Boolean = delegate.isHealthy()
    override suspend fun ensureInstalled(): String = delegate.ensureInstalled()
    override suspend fun execute(argv: List<String>, maxOutputBytes: Int): ExecResult = delegate.execute(argv, maxOutputBytes)
}

private object FoundryUbuntuGuestMutationGate {
    val mutex = Mutex()
}






class FoundryUbuntuGuestEnvironmentManager(
    context: Context,
) : ReviewedGuestExecutionEnvironment {
    private val appContext = context.applicationContext
    private val root = context.applicationContext.filesDir.absolutePath
    override val backendId: PackageBackendId = PackageBackendId.LOCAL_APP
    private val guestBase = "$root/guest"
    private val finalRoot = "$guestBase/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}"
    private val launcher = "$finalRoot/launch"
    private val legacyCompactor = FoundryUbuntuLegacyCompactor( finalRoot, launcher)

    override val environmentId: String = FoundryUbuntuGuestEnvironmentSpec.ID
    override val environmentVersion: String = FoundryUbuntuGuestEnvironmentSpec.VERSION
    override fun launcherPath(): String = launcher

    override suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        LocalExecutionSubstrate.requireContextRoot(root)
        
        val profileLock = "$finalRoot/BOOTSTRAP_LOCK.txt"
        val probe = LocalExecutionSubstrate.shellBounded(
            "test -f ${q(launcher)} && test -f ${q(profileLock)} && " +
                "grep -Fxq ${q("profile_revision=${FoundryUbuntuGuestEnvironmentSpec.PROFILE_REVISION}")} ${q(profileLock)} && " +
                "/system/bin/sh ${q(launcher)} /bin/sh -lc ${q("test -x /usr/bin/apt-get && test \"\$(/usr/bin/dpkg --print-architecture)\" = arm64")}",
            maxOutputBytes = 16_384,
        )
        probe.exitCode == 0
    }

    override suspend fun ensureInstalled(): String = withContext(Dispatchers.IO) {
        FoundryUbuntuGuestMutationGate.mutex.withLock {
            val installed = ensureInstalledUnlocked()
            PackagedWorkstationRootfs.evict(appContext, FoundryUbuntuGuestEnvironmentSpec.rootfsArtifact.fileName, FoundryUbuntuGuestEnvironmentSpec.rootfsArtifact.sha256)
            installed
        }
    }

    private suspend fun ensureInstalledUnlocked(): String {
        LocalExecutionSubstrate.requireContextRoot(root)
        requireArm64()
        PackagedLinuxEngine.requireReady(appContext)
        reconcileInterruptedBootstrap()
        reconcileInterruptedLegacyCompactionUnlocked()
        repairExistingLauncher()
        if (isHealthy()) return finalRoot
        if (isLegacyHealthyUnlocked()) {
            migrateLegacyProfileInPlaceUnlocked()
            check(isHealthy()) { "Migrated Ubuntu guest failed layered-profile health verification" }
            return finalRoot
        }
        if (existingGuestPresent()) {
            error(
                "Existing Ubuntu guest failed its health check and was preserved. " +
                    "Droide will not replace user packages or /root automatically; use an explicit recovery/reset action."
            )
        }

        DeviceWorkstationStorageGuard.requireHeadroom(
            
            additionalBytes = maxOf(
                384L * 1024L * 1024L,
                requireNotNull(FoundryUbuntuGuestEnvironmentSpec.rootfsArtifact.expectedBytes) * 10L,
            ),
            purpose = "bootstrap the Foundry Ubuntu ARM64 runtime",
        )
        val stage = "$guestBase/.staging/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}"
        val previous = "$guestBase/.previous/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}"
        listOf(guestBase, stage, previous, finalRoot).forEach(LocalExecutionSubstrate::requireSafeLocalPath)

        val prep = LocalExecutionSubstrate.shell(
            "set -eu; rm -rf ${q(stage)}; mkdir -p ${q("$stage/rootfs")} ${q("$guestBase/.previous")}",
        )
        check(prep.exitCode == 0) { "Could not prepare Ubuntu guest environment: ${prep.output}" }

        try {
            BundledRootfsExtractor.extract(appContext, BundledRootfsExtractor.ubuntu, File("$stage/rootfs"))

            val dns = LocalExecutionSubstrate.shell(
                "set -eu; mkdir -p ${q("$stage/rootfs/etc")}; " +
                    "printf '%s\\n' 'nameserver 1.1.1.1' 'nameserver 8.8.8.8' > ${q("$stage/rootfs/etc/resolv.conf")}",
            )
            check(dns.exitCode == 0) { "Could not prepare Ubuntu guest DNS configuration: ${dns.output}" }

            pushText("$stage/launch", launcherScript(), 493)
            LocalGuestCertificates.seed(File("$stage/rootfs"))
            pushText("$stage/BOOTSTRAP_LOCK.txt", bootstrapLock() + "bootstrap_tls=android-system-ca\n", 420)
            val stageHealth = LocalExecutionSubstrate.shellBounded(
                "/system/bin/sh ${q("$stage/launch")} /bin/sh -lc ${q("test -x /usr/bin/apt-get && test \"\$(/usr/bin/dpkg --print-architecture)\" = arm64 && test -s /etc/ssl/certs/ca-certificates.crt")}",
                maxOutputBytes = 16_384,
            )
            check(stageHealth.exitCode == 0) { "Rootless Ubuntu bootstrap health check failed: ${stageHealth.output}" }

            val move = LocalExecutionSubstrate.shell(
                // Bootstrap is creation-only. An existing guest, healthy or not, is never replaced
                // by an automatic health path. Re-check at commit time to close the race window.
                "set -eu; if [ -e ${q(finalRoot)} ] || [ -L ${q(finalRoot)} ]; then exit 73; fi; mv ${q(stage)} ${q(finalRoot)}",
            )
            check(move.exitCode == 0) { "Could not activate Ubuntu guest environment: ${move.output}" }
            check(isHealthy()) { "Activated Ubuntu guest environment failed health verification" }
            // A legacy `.previous` recovery anchor is intentionally retained. Fresh creation does
            // not create one, and automatic cleanup never deletes a user-recoverable guest tree.
            withContext(NonCancellable) { LocalExecutionSubstrate.shell("rm -rf ${q(stage)}") }
            return finalRoot
        } catch (failure: Throwable) {
            // Cleanup only the staging tree. Current/previous guests are recovery state and are never
            // deleted or swapped merely because activation/health verification failed.
            withContext(NonCancellable) { LocalExecutionSubstrate.shell("rm -rf ${q(stage)}") }
            throw failure
        }
    }

    /** Refresh the APK-owned launcher after an app update without replacing the user's guest. */
    private suspend fun repairExistingLauncher() {
        val lock = File(finalRoot, "BOOTSTRAP_LOCK.txt")
        val existing = File(launcher)
        if (!lock.isFile || !existing.isFile || !File(finalRoot, "rootfs").isDirectory) return
        check(!PathSecurity.isSymbolicLink(lock) && !PathSecurity.isSymbolicLink(existing) &&
            lock.length() in 1..65536 && existing.length() in 1..65536) {
            "Cannot migrate an unsafe Ubuntu launcher"
        }
        check(lock.readLines().contains("environment=${FoundryUbuntuGuestEnvironmentSpec.ID}")) {
            "Cannot migrate an unowned Ubuntu guest"
        }
        val next = launcherScript()
        val old = existing.readText()
        if (next == old) return
        fun replace(text: String) {
            val pending = File(finalRoot, ".launch-next")
            LocalExecutionSubstrate.requireSafeLocalPath(pending.absolutePath)
            FileOutputStream(pending).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            check(pending.renameTo(existing)) { "Cannot atomically update Ubuntu launcher" }
        }
        replace(next)
        if (!isHealthy() && !isLegacyHealthyUnlocked()) {
            withContext(NonCancellable) { replace(old) }
            error("Packaged PRoot could not start the existing Ubuntu guest; original launcher restored")
        }
    }

    /**
     * True only for an r1 guest that has been migrated in place but whose historical package
     * consumers have not yet been rebound to explicit r2 capability-layer ownership. Calling this
     * also performs the non-destructive r1 -> r2 profile migration when needed.
     */
    suspend fun needsLegacyOwnershipAdoption(): Boolean = withContext(Dispatchers.IO) {
        FoundryUbuntuGuestMutationGate.mutex.withLock {
            ensureInstalledUnlocked()
            isLegacyBroadBasePreservedUnlocked() && !isLegacyLayerOwnershipCompleteUnlocked()
        }
    }

    /**
     * Persist the point at which every registry-visible legacy glibc consumer has an explicit
     * capability-layer dependency. This does not delete one byte of the r1 broad base; destructive
     * compaction is a separate transaction so a failed ownership pass can never strand a tool.
     */
    suspend fun markLegacyOwnershipComplete() = withContext(Dispatchers.IO) {
        FoundryUbuntuGuestMutationGate.mutex.withLock {
            ensureInstalledUnlocked()
            check(isLegacyBroadBasePreservedUnlocked()) { "Legacy ownership completion is only valid for an in-place r1 migration" }
            check(isHealthy()) { "Ubuntu guest must be healthy before sealing legacy layer ownership" }
            val profileLock = "$finalRoot/BOOTSTRAP_LOCK.txt"
            val marker = FoundryUbuntuGuestEnvironmentSpec.LEGACY_LAYER_OWNERSHIP_MARKER
            val seal = LocalExecutionSubstrate.shellBounded(
                "set -eu; grep -Fxq ${q(marker)} ${q(profileLock)} || printf '%s\n' ${q(marker)} >> ${q(profileLock)}",
                maxOutputBytes = 16_384,
            )
            check(seal.exitCode == 0) { "Could not seal legacy Ubuntu capability-layer ownership" }
        }
    }

    suspend fun needsLegacyPhysicalCompaction(): Boolean = withContext(Dispatchers.IO) {
        FoundryUbuntuGuestMutationGate.mutex.withLock {
            ensureInstalledUnlocked()
            isLegacyBroadBasePreservedUnlocked() &&
                isLegacyLayerOwnershipCompleteUnlocked() &&
                !isLegacyPhysicalCompactionCompleteUnlocked()
        }
    }

    internal suspend fun beginLegacyPhysicalCompaction(
        protectedPackageSets: List<List<String>>,
    ): LegacyUbuntuCompactionTransaction = withContext(Dispatchers.IO) {
        FoundryUbuntuGuestMutationGate.mutex.withLock {
            ensureInstalledUnlocked()
            check(isLegacyBroadBasePreservedUnlocked()) { "Legacy Ubuntu broad base is not present" }
            check(isLegacyLayerOwnershipCompleteUnlocked()) { "Legacy Ubuntu ownership is not complete" }
            check(!isLegacyPhysicalCompactionCompleteUnlocked()) { "Legacy Ubuntu guest is already compacted" }
            ensureAptHealthyUnlocked()
            legacyCompactor.begin(
                legacyBroadRoots = FoundryUbuntuGuestEnvironmentSpec.legacyBroadRootPackages,
                canonicalBaseRoots = FoundryUbuntuGuestEnvironmentSpec.canonicalBasePackages,
                baselineRoots = FoundryUbuntuGuestEnvironmentSpec.baselinePackages,
                protectedPackageSets = protectedPackageSets,
            )
        }
    }

    internal suspend fun rollbackLegacyPhysicalCompaction(transaction: LegacyUbuntuCompactionTransaction) = withContext(Dispatchers.IO) {
        FoundryUbuntuGuestMutationGate.mutex.withLock {
            legacyCompactor.rollback(transaction)
            check(isHealthy()) { "Ubuntu guest failed health verification after compaction rollback" }
        }
    }

    internal suspend fun finalizeLegacyPhysicalCompaction(transaction: LegacyUbuntuCompactionTransaction) = withContext(Dispatchers.IO) {
        FoundryUbuntuGuestMutationGate.mutex.withLock {
            check(legacyCompactor.exists()) { "Legacy Ubuntu compaction transaction disappeared before commit" }
            check(isLegacyBroadBasePreservedUnlocked()) { "Legacy Ubuntu broad-base marker changed before commit" }
            check(isLegacyLayerOwnershipCompleteUnlocked()) { "Legacy Ubuntu ownership marker changed before commit" }
            check(isHealthy()) { "Ubuntu guest is unhealthy before compaction commit" }
            val lockText = bootstrapLock() +
                FoundryUbuntuGuestEnvironmentSpec.LEGACY_LAYER_OWNERSHIP_MARKER + "\n" +
                FoundryUbuntuGuestEnvironmentSpec.LEGACY_PHYSICAL_COMPACTION_MARKER + "\n"
            val lockPath = "$finalRoot/BOOTSTRAP_LOCK.txt"
            val lockStage = "$finalRoot/.BOOTSTRAP_LOCK.compaction.tmp"
            var committed = false
            try {
                pushText(lockStage, lockText, 420)
                val commitLock = LocalExecutionSubstrate.shellBounded(
                    "set -eu; chmod 0644 ${q(lockStage)}; mv -f ${q(lockStage)} ${q(lockPath)}",
                    maxOutputBytes = 16_384,
                )
                check(commitLock.exitCode == 0) { "Could not atomically commit legacy Ubuntu compaction marker" }
                committed = true
                legacyCompactor.discard(transaction)
                check(isHealthy()) { "Ubuntu guest failed health verification after compaction commit" }
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    if (!committed) {
                        runCatching { LocalExecutionSubstrate.shellBounded("rm -f ${q(lockStage)}", maxOutputBytes = 8_192) }
                        try {
                            legacyCompactor.rollback(transaction)
                            check(isHealthy()) { "Ubuntu guest failed health verification after failed compaction commit rollback" }
                        } catch (rollbackFailure: Throwable) {
                            failure.addSuppressed(rollbackFailure)
                        }
                    }
                }
                throw failure
            }
        }
    }

    /**
     * Ensure a reviewed package capability set exists without bloating the base guest. The request
     * is content-addressed and idempotent. APT first simulates the exact closure, then Droide checks
     * peak storage, downloads into a transaction-local cache, rechecks using real .deb metadata, and
     * finally installs from that cache with --no-download.
     */
    suspend fun ensurePackages(packages: List<String>): ManagedPackageRecord? = withContext(Dispatchers.IO) {
        val normalized = FoundryUbuntuGuestEnvironmentSpec.normalizeLayerPackages(packages)
        if (normalized.isEmpty()) return@withContext null
        FoundryUbuntuGuestMutationGate.mutex.withLock {
            ensureInstalledUnlocked()
            ensureAptHealthyUnlocked()
            val layerId = layerId(normalized)
            if (isLayerHealthyUnlocked(layerId, normalized)) {
                return@withLock createLayerAnchorUnlocked(layerId, normalized)
            }

            val packageArgs = normalized.joinToString(" ")
            val txRoot = "/var/cache/droide-foundry/$layerId"
            val cacheDir = "$txRoot/archives"
            val remoteTxRoot = "$finalRoot/rootfs$txRoot"
            LocalExecutionSubstrate.requireSafeLocalPath(remoteTxRoot)

            val update = executeUnlocked(listOf("sh", "-lc", "apt-get update -qq"), maxOutputBytes = 256_000)
            check(update.exitCode == 0) { "Could not refresh Ubuntu capability metadata: ${update.output.takeLast(8_000)}" }

            val plan = executeUnlocked(
                listOf(
                    "sh", "-lc",
                    "set -eu; " +
                        "apt-get -s --no-install-recommends install $packageArgs | grep '^Inst ' | cut -d' ' -f2 > /tmp/droide-layer-packages; " +
                        "apt-get --print-uris -y --no-install-recommends install $packageArgs 2>/dev/null | " +
                        "sed -nE 's/^[^ ]+ [^ ]+ ([0-9]+).*/\\1/p' > /tmp/droide-layer-sizes; " +
                        "dl=0; while read -r n; do [ -n \"\$n\" ] && dl=\$((dl+n)); done < /tmp/droide-layer-sizes; " +
                        "ik=0; while read -r p; do [ -n \"\$p\" ] || continue; " +
                        "n=\$(apt-cache show --no-all-versions \"\$p\" 2>/dev/null | sed -n 's/^Installed-Size: //p' | head -1); " +
                        "n=\${n:-0}; ik=\$((ik+n)); done < /tmp/droide-layer-packages; " +
                        "rm -f /tmp/droide-layer-packages /tmp/droide-layer-sizes; " +
                        "printf 'download=%s\\ninstalled_kib=%s\\n' \"\$dl\" \"\$ik\"",
                ),
                maxOutputBytes = 256_000,
            )
            check(plan.exitCode == 0) { "Could not plan Ubuntu capability layer: ${plan.output.takeLast(8_000)}" }
            val plannedDownload = parsePlanLong(plan.output, "download")
            val plannedInstalled = Math.multiplyExact(parsePlanLong(plan.output, "installed_kib"), 1024L)
            DeviceWorkstationStorageGuard.requireHeadroom(
                
                additionalBytes = Math.addExact(
                    Math.addExact(plannedDownload, plannedInstalled),
                    FoundryUbuntuGuestEnvironmentSpec.LAYER_TRANSACTION_OVERHEAD_BYTES,
                ),
                purpose = "install the Ubuntu capability layer for ${normalized.take(4).joinToString(",")}",
            )

            try {
                val download = executeUnlocked(
                    listOf(
                        "sh", "-lc",
                        "set -eu; rm -rf $txRoot; mkdir -p $cacheDir/partial; " +
                            "apt-get -y --no-install-recommends --download-only -o Dir::Cache::archives=$cacheDir install $packageArgs >/tmp/droide-layer-download.log",
                    ),
                    maxOutputBytes = 256_000,
                )
                check(download.exitCode == 0) { "Could not download Ubuntu capability layer: ${download.output.takeLast(8_000)}" }

                val exact = executeUnlocked(
                    listOf(
                        "sh", "-lc",
                        "set -eu; db=0; ik=0; for f in $cacheDir/*.deb; do [ -f \"\$f\" ] || continue; " +
                            "n=\$(stat -c %s \"\$f\"); db=\$((db+n)); " +
                            "n=\$(dpkg-deb -f \"\$f\" Installed-Size 2>/dev/null || printf 0); ik=\$((ik+n)); done; " +
                            "printf 'download=%s\ninstalled_kib=%s\n' \"\$db\" \"\$ik\"",
                    ),
                    maxOutputBytes = 128_000,
                )
                check(exact.exitCode == 0) { "Could not measure Ubuntu capability layer" }
                val exactInstalled = Math.multiplyExact(parsePlanLong(exact.output, "installed_kib"), 1024L)
                DeviceWorkstationStorageGuard.requireHeadroom(
                    
                    additionalBytes = Math.addExact(exactInstalled, FoundryUbuntuGuestEnvironmentSpec.LAYER_TRANSACTION_OVERHEAD_BYTES),
                    purpose = "activate the downloaded Ubuntu capability layer",
                )

                val install = executeUnlocked(
                    listOf(
                        "sh", "-lc",
                        "set -eu; apt-get -y --no-install-recommends --no-download -o Dir::Cache::archives=$cacheDir install $packageArgs >/tmp/droide-layer-install.log; " +
                            "apt-mark manual $packageArgs >/dev/null; " +
                            "dpkg --audit; for p in $packageArgs; do dpkg-query -W -f='\${Status}\\n' \"\$p\" | grep -Fxq 'install ok installed'; done",
                    ),
                    maxOutputBytes = 512_000,
                )
                check(install.exitCode == 0) { "Could not activate Ubuntu capability layer: ${install.output.takeLast(12_000)}" }
                writeLayerLockUnlocked(layerId, normalized)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    executeUnlocked(
                        listOf("sh", "-lc", "dpkg --configure -a >/tmp/droide-layer-repair.log 2>&1 || true; rm -rf $txRoot"),
                        maxOutputBytes = 128_000,
                    )
                }
                throw failure
            } finally {
                withContext(NonCancellable) {
                    executeUnlocked(
                        listOf("sh", "-lc", "rm -rf $txRoot /var/lib/apt/lists/*"),
                        maxOutputBytes = 64_000,
                    )
                }
            }
            createLayerAnchorUnlocked(layerId, normalized)
        }
    }

    fun layerRecordKey(packages: List<String>): String? {
        val normalized = FoundryUbuntuGuestEnvironmentSpec.normalizeLayerPackages(packages)
        if (normalized.isEmpty()) return null
        return "${layerFamilyId(normalized)}@${FoundryUbuntuGuestEnvironmentSpec.LAYER_RECORD_VERSION}"
    }

    suspend fun releaseLayer(
        packages: List<String>,
        protectedPackageSets: List<List<String>>,
        removeWholeEnvironment: Boolean,
    ) = withContext(Dispatchers.IO) {
        val normalized = FoundryUbuntuGuestEnvironmentSpec.normalizeLayerPackages(packages)
        if (normalized.isEmpty()) return@withContext
        FoundryUbuntuGuestMutationGate.mutex.withLock {
            val exists = LocalExecutionSubstrate.shell("test -d ${q(finalRoot)}").exitCode == 0
            if (!exists) return@withLock
            val legacyBroadBasePreserved = isLegacyBroadBasePreservedUnlocked()
            if (removeWholeEnvironment && !legacyBroadBasePreserved) {
                val previous = "$guestBase/.previous/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}"
                val stage = "$guestBase/.staging/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}"
                val remove = LocalExecutionSubstrate.shell("rm -rf ${q(finalRoot)} ${q(previous)} ${q(stage)}")
                check(remove.exitCode == 0) { "Could not reclaim unused Ubuntu guest: ${remove.output.takeLast(8_000)}" }
                return@withLock
            }
            check(isHealthy()) { "Ubuntu guest is unhealthy; preserving capability-layer ownership for repair" }
            ensureAptHealthyUnlocked()

            val protected = buildSet {
                addAll(FoundryUbuntuGuestEnvironmentSpec.baselinePackages)
                protectedPackageSets.forEach { addAll(FoundryUbuntuGuestEnvironmentSpec.normalizeLayerPackages(it)) }
            }
            val removable = normalized.filterNot { it in protected }
            



            if (removable.isNotEmpty() && !legacyBroadBasePreserved) {
                val protectArgs = protected.sorted().joinToString(" ")
                val removableArgs = removable.joinToString(" ")
                val mark = executeUnlocked(
                    listOf(
                        "sh", "-lc",
                        "set -eu; apt-get update -qq; for p in $protectArgs; do " +
                            "dpkg-query -W -f='\${Status}\\n' \"\$p\" 2>/dev/null | grep -Fxq 'install ok installed' && apt-mark manual \"\$p\" >/dev/null || true; done",
                    ),
                    maxOutputBytes = 256_000,
                )
                check(mark.exitCode == 0) { "Could not protect shared Ubuntu capability packages" }

                val purgePlan = executeUnlocked(listOf("sh", "-lc", "apt-get -s purge $removableArgs"), maxOutputBytes = 256_000)
                check(purgePlan.exitCode == 0) { "Could not plan Ubuntu capability-layer removal" }
                val purgeRemovals = simulatedRemovals(purgePlan.output)
                check(purgeRemovals.none { it in protected }) { "Ubuntu layer purge would remove a shared capability package" }
                val purge = executeUnlocked(
                    listOf("sh", "-lc", "apt-get purge -y $removableArgs >/tmp/droide-layer-purge.log"),
                    maxOutputBytes = 384_000,
                )
                check(purge.exitCode == 0) { "Could not remove unused Ubuntu capability packages: ${purge.output.takeLast(8_000)}" }

                val autoPlan = executeUnlocked(listOf("sh", "-lc", "apt-get -s autoremove --purge"), maxOutputBytes = 256_000)
                check(autoPlan.exitCode == 0) { "Could not plan Ubuntu dependency autoremove" }
                val autoRemovals = simulatedRemovals(autoPlan.output)
                check(autoRemovals.none { it in protected }) { "Ubuntu autoremove would remove a shared capability package" }
                if (autoRemovals.isNotEmpty()) {
                    val autoremove = executeUnlocked(
                        listOf("sh", "-lc", "apt-get autoremove -y --purge >/tmp/droide-layer-autoremove.log"),
                        maxOutputBytes = 384_000,
                    )
                    check(autoremove.exitCode == 0) { "Could not reclaim orphan Ubuntu dependencies: ${autoremove.output.takeLast(8_000)}" }
                }
            }
            val layerId = layerId(normalized)
            val cleanup = executeUnlocked(
                listOf("sh", "-lc", "rm -f /var/lib/droide-foundry/layers/$layerId.lock; apt-get clean; rm -rf /var/lib/apt/lists/*"),
                maxOutputBytes = 64_000,
            )
            check(cleanup.exitCode == 0) { "Could not finalize Ubuntu capability-layer cleanup" }
        }
    }

    override suspend fun execute(argv: List<String>, maxOutputBytes: Int): ExecResult = withContext(Dispatchers.IO) {
        ensureInstalled()
        executeUnlocked(argv, maxOutputBytes)
    }

    private suspend fun executeUnlocked(argv: List<String>, maxOutputBytes: Int): ExecResult {
        require(argv.isNotEmpty()) { "Guest command is empty" }
        val command = buildString {
            append("/system/bin/sh ").append(q(launcher))
            argv.forEach { arg ->
                require('\u0000' !in arg && '\n' !in arg && '\r' !in arg && arg.length <= 4_000) { "Unsafe Ubuntu guest argument" }
                append(' ').append(q(arg))
            }
        }
        return LocalExecutionSubstrate.shellBounded(command, maxOutputBytes, timeoutMs = 900_000)
    }


    private suspend fun isLegacyHealthyUnlocked(): Boolean {
        val profileLock = "$finalRoot/BOOTSTRAP_LOCK.txt"
        val probe = LocalExecutionSubstrate.shellBounded(
            "test -f ${q(launcher)} && test -f ${q(profileLock)} && " +
                "grep -Fxq ${q("profile_revision=${FoundryUbuntuGuestEnvironmentSpec.LEGACY_PROFILE_REVISION}")} ${q(profileLock)} && " +
                "/system/bin/sh ${q(launcher)} /bin/sh -lc ${q("test -x /usr/bin/apt-get && test \"\$(/usr/bin/dpkg --print-architecture)\" = arm64")}",
            maxOutputBytes = 16_384,
        )
        return probe.exitCode == 0
    }

    private suspend fun migrateLegacyProfileInPlaceUnlocked() {
        // Safety-first migration: keep the already-working broad r1 package set intact. New and
        // repaired artifacts gain explicit r2 layer ownership, while legacy artifacts continue to
        // run until a later ownership-complete compaction can prove every dependency is covered.
        ensureAptHealthyUnlocked()
        val migratedLock = bootstrapLock() + FoundryUbuntuGuestEnvironmentSpec.LEGACY_BROAD_BASE_MARKER + "\n"
        pushText("$finalRoot/BOOTSTRAP_LOCK.txt", migratedLock, 420)
    }

    private suspend fun isLegacyBroadBasePreservedUnlocked(): Boolean {
        val profileLock = "$finalRoot/BOOTSTRAP_LOCK.txt"
        val probe = LocalExecutionSubstrate.shellBounded(
            "test -f ${q(profileLock)} && grep -Fxq ${q(FoundryUbuntuGuestEnvironmentSpec.LEGACY_BROAD_BASE_MARKER)} ${q(profileLock)}",
            maxOutputBytes = 8_192,
        )
        return probe.exitCode == 0
    }

    private suspend fun isLegacyLayerOwnershipCompleteUnlocked(): Boolean {
        val profileLock = "$finalRoot/BOOTSTRAP_LOCK.txt"
        val probe = LocalExecutionSubstrate.shellBounded(
            "test -f ${q(profileLock)} && grep -Fxq ${q(FoundryUbuntuGuestEnvironmentSpec.LEGACY_LAYER_OWNERSHIP_MARKER)} ${q(profileLock)}",
            maxOutputBytes = 8_192,
        )
        return probe.exitCode == 0
    }

    private suspend fun isLegacyPhysicalCompactionCompleteUnlocked(): Boolean {
        val profileLock = "$finalRoot/BOOTSTRAP_LOCK.txt"
        val probe = LocalExecutionSubstrate.shellBounded(
            "test -f ${q(profileLock)} && grep -Fxq ${q(FoundryUbuntuGuestEnvironmentSpec.LEGACY_PHYSICAL_COMPACTION_MARKER)} ${q(profileLock)}",
            maxOutputBytes = 8_192,
        )
        return probe.exitCode == 0
    }

    private suspend fun reconcileInterruptedLegacyCompactionUnlocked() {
        val exists = LocalExecutionSubstrate.shell("test -f ${q(launcher)}").exitCode == 0
        if (!exists || !legacyCompactor.exists()) return
        legacyCompactor.recoverInterrupted(isLegacyPhysicalCompactionCompleteUnlocked())
    }

    private suspend fun ensureAptHealthyUnlocked() {
        val configure = executeUnlocked(
            listOf("sh", "-lc", "dpkg --configure -a"),
            maxOutputBytes = 256_000,
        )
        if (configure.exitCode == 0) return

        val repair = executeUnlocked(
            listOf("sh", "-lc", "set -eu; apt-get update -qq; apt-get -f install -y --no-install-recommends; dpkg --configure -a"),
            maxOutputBytes = 512_000,
        )
        check(repair.exitCode == 0) { "Ubuntu guest package state requires repair: ${repair.output.takeLast(12_000)}" }
    }

    private suspend fun isLayerHealthyUnlocked(layerId: String, packages: List<String>): Boolean {
        val marker = "/var/lib/droide-foundry/layers/$layerId.lock"
        val command = buildString {
            append("set -eu; test -f ").append(q(marker)).append("; ")
            packages.forEach { packageName ->
                append("dpkg-query -W -f='\${Status}\\n' ").append(q(packageName)).append(" | grep -Fxq 'install ok installed'; ")
            }
        }
        return executeUnlocked(listOf("sh", "-lc", command), maxOutputBytes = 32_000).exitCode == 0
    }

    private suspend fun writeLayerLockUnlocked(layerId: String, packages: List<String>) {
        val guestDir = "/var/lib/droide-foundry/layers"
        val prep = executeUnlocked(listOf("sh", "-lc", "mkdir -p $guestDir"), maxOutputBytes = 16_384)
        check(prep.exitCode == 0) { "Could not prepare Ubuntu capability-layer metadata" }
        val hostPath = "$finalRoot/rootfs$guestDir/$layerId.lock"
        pushText(
            hostPath,
            buildString {
                appendLine("schema=1")
                appendLine("profile_revision=${FoundryUbuntuGuestEnvironmentSpec.PROFILE_REVISION}")
                appendLine("layer_id=$layerId")
                appendLine("packages=${packages.joinToString(",")}")
            },
            420,
        )
    }

    private fun layerId(packages: List<String>): String {
        val identity = FoundryUbuntuGuestEnvironmentSpec.PROFILE_REVISION + "\n" + packages.joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun layerFamilyId(packages: List<String>): String = "runtime.ubuntu-deps.${layerId(packages)}"

    private suspend fun createLayerAnchorUnlocked(layerId: String, packages: List<String>): ManagedPackageRecord {
        val familyId = layerFamilyId(packages)
        val version = FoundryUbuntuGuestEnvironmentSpec.LAYER_RECORD_VERSION
        val installRoot = "$root/packages/$familyId/$version"
        val stage = "$root/packages/.staging/$familyId.$version"
        listOf(installRoot, stage).forEach(LocalExecutionSubstrate::requireSafeLocalPath)
        val layerText = buildString {
            appendLine("schema=1")
            appendLine("kind=${FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND}")
            appendLine("profile_revision=${FoundryUbuntuGuestEnvironmentSpec.PROFILE_REVISION}")
            appendLine("layer_id=$layerId")
            appendLine("packages=${packages.joinToString(",")}")
        }
        val expectedMarker = "layer_id=$layerId"
        val existing = LocalExecutionSubstrate.shellBounded(
            "cd ${q(installRoot)} 2>/dev/null && toybox sha256sum -c SHA256SUMS >/dev/null 2>&1 && grep -Fxq ${q(expectedMarker)} LAYER.txt",
            maxOutputBytes = 16_384,
        )
        if (existing.exitCode != 0) {
            val prep = LocalExecutionSubstrate.shell("rm -rf ${q(stage)}; mkdir -p ${q("$stage/payload")}")
            check(prep.exitCode == 0) { "Could not prepare Ubuntu layer ownership anchor" }
            pushText("$stage/LAYER.txt", layerText, 420)
            val guestHealth = buildString {
                append("set -eu; test \"\$(dpkg --print-architecture)\" = arm64; test -f /var/lib/droide-foundry/layers/$layerId.lock; ")
                packages.forEach { packageName ->
                    append("dpkg-query -W -f='\${Status}\\n' ").append(q(packageName))
                        .append(" | grep -Fxq 'install ok installed'; ")
                }
            }
            val healthScript = "#!/system/bin/sh\nset -eu\nexec /system/bin/sh ${q(launcher)} /bin/sh -lc ${q(guestHealth)}\n"
            pushText("$stage/payload/verify-layer", healthScript, 493)
            val seal = LocalExecutionSubstrate.shell(
                "set -eu; cd ${q(stage)}; toybox sha256sum LAYER.txt payload/verify-layer > SHA256SUMS; " +
                    "mkdir -p ${q(installRoot.substringBeforeLast('/'))}; rm -rf ${q(installRoot)}; mv ${q(stage)} ${q(installRoot)}",
            )
            check(seal.exitCode == 0) { "Could not seal Ubuntu layer ownership anchor: ${seal.output.takeLast(4_000)}" }
        }
        return ManagedPackageRecord(
            familyId = familyId,
            version = version,
            scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
            installRoot = installRoot,
            installedAtEpochMs = System.currentTimeMillis(),
            active = true,
            healthChecks = listOf(ManagedPackageHealthCheck("verify-layer", emptyList())),
            metadata = mapOf(
                FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND to FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND,
                FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES to packages.joinToString(","),
            ),
        )
    }

    private fun simulatedRemovals(output: String): Set<String> = output.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("Remv ") }
        .mapNotNull { it.split(Regex("\\s+")).getOrNull(1) }
        .toSet()

    private fun parsePlanLong(output: String, key: String): Long = output.lineSequence()
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.trim()
        ?.toLongOrNull()
        ?: error("Ubuntu capability planner did not report $key")

    private suspend fun reconcileInterruptedBootstrap() {
        val previous = "$guestBase/.previous/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}"
        val stage = "$guestBase/.staging/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}"
        listOf(previous, stage, finalRoot).forEach(LocalExecutionSubstrate::requireSafeLocalPath)
        val state = LocalExecutionSubstrate.shellBounded(
            """for p in ${q(previous)} ${q(stage)} ${q(finalRoot)}; do if [ -e "${'$'}p" ] || [ -L "${'$'}p" ]; then printf '1 '; else printf '0 '; fi; done""",
            maxOutputBytes = 8_192,
        )
        check(state.exitCode == 0) { "Could not reconcile Ubuntu runtime transaction: ${state.output.takeLast(4_000)}" }
        val flags = state.output.trim().split(Regex("\\s+")).mapNotNull(String::toIntOrNull)
        if (flags.size < 3) error("Invalid Ubuntu runtime transaction state")
        val previousExists = flags[0] == 1
        val stageExists = flags[1] == 1
        val finalExists = flags[2] == 1

        // Its presence must never authorize deletion/rollback of a current guest that may contain newer user data.

        if (finalExists) {
            if (stageExists) withContext(NonCancellable) { LocalExecutionSubstrate.shell("rm -rf ${q(stage)}") }
            return
        }
        if (previousExists) {
            withContext(NonCancellable) {
                val restore = LocalExecutionSubstrate.shell(
                    "set -eu; mv ${q(previous)} ${q(finalRoot)}; rm -rf ${q(stage)}",
                )
                check(restore.exitCode == 0) { "Could not restore interrupted Ubuntu runtime: ${restore.output.takeLast(4_000)}" }
            }
            return
        }
        if (stageExists) withContext(NonCancellable) { LocalExecutionSubstrate.shell("rm -rf ${q(stage)}") }
    }

    private fun existingGuestPresent(): Boolean {
        val guest = File(finalRoot)
        // Never infer permission to replace a guest from its health.

        return guest.exists() || PathSecurity.isSymbolicLink(guest)
    }


    private suspend fun verifyRemoteArtifact(path: String, spec: TrustedArtifactSpec) {
        LocalExecutionSubstrate.requireSafeLocalPath(path)
        val size = LocalExecutionSubstrate.shellBounded("toybox stat -c %s ${q(path)}", maxOutputBytes = 8_192)
        check(size.exitCode == 0 && size.output.trim().lineSequence().lastOrNull()?.toLongOrNull() == spec.expectedBytes) {
            "Remote ${spec.id} size does not match pinned metadata"
        }
        val digest = LocalExecutionSubstrate.shellBounded("toybox sha256sum ${q(path)}", maxOutputBytes = 8_192)
        val actual = digest.output.trim().substringBefore(' ').lowercase()
        check(digest.exitCode == 0 && actual == spec.sha256.lowercase()) { "Remote ${spec.id} SHA-256 mismatch" }
    }

    private fun launcherScript(): String = """#!/system/bin/sh
set -eu
SELF="${'$'}0"
BASE="${'$'}{SELF%/*}"
ROOTFS="${'$'}BASE/rootfs"
DROIDE_ROOT="${LocalExecutionSubstrate.localRoot()}"
WORK="${'$'}{PWD:-${LocalExecutionSubstrate.localRoot()}}"
case "${'$'}WORK" in
  "${LocalExecutionSubstrate.localRoot()}"|"${LocalExecutionSubstrate.localRoot()}"/*) ;;
  *) WORK="${LocalExecutionSubstrate.localRoot()}" ;;
esac
PROOT_NO_SECCOMP=1
PROOT_LOADER=${q(PackagedLinuxEngine.loaderEnvironment(appContext).getValue("PROOT_LOADER"))}
PROOT_LOADER_32=${q(PackagedLinuxEngine.loaderEnvironment(appContext).getValue("PROOT_LOADER_32"))}
PROOT_TMP_DIR=${q(File(root, "runtime-tmp").apply { mkdirs() }.absolutePath)}
export PROOT_NO_SECCOMP PROOT_LOADER PROOT_LOADER_32 PROOT_TMP_DIR
exec /system/bin/linker64 ${q(PackagedLinuxEngine.requireReady(appContext).absolutePath)} -0 -r "${'$'}ROOTFS" \
  -b /dev -b /proc -b /sys -b "${'$'}DROIDE_ROOT:${'$'}DROIDE_ROOT" -w "${'$'}WORK" \
  /usr/bin/env -i HOME=/root USER=root LOGNAME=root \
  DROIDE_PROCESS_LEASE="${'$'}{DROIDE_PROCESS_LEASE:-}" \
  PATH=/root/.local/bin:/root/.cargo/bin:/root/go/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  TERM="${'$'}{TERM:-xterm-256color}" LANG=C.UTF-8 LC_ALL=C.UTF-8 DEBIAN_FRONTEND=noninteractive "${'$'}@"
"""

    private fun bootstrapLock(): String = buildString {
        appendLine("schema=1")
        appendLine("environment=${FoundryUbuntuGuestEnvironmentSpec.ID}")
        appendLine("profile=UBUNTU_24_04_GLIBC_ARM64")
        appendLine("profile_revision=${FoundryUbuntuGuestEnvironmentSpec.PROFILE_REVISION}")
        appendLine("baseline_packages=on-demand")
        appendLine("engine_source=APK_NATIVE_LIBRARY")
        appendLine("engine_name=${PackagedLinuxEngine.LIBRARY_NAME}")
        appendLine("rootfs_sha256=${FoundryUbuntuGuestEnvironmentSpec.rootfsArtifact.sha256}")
        appendLine("rootfs_bytes=${FoundryUbuntuGuestEnvironmentSpec.rootfsArtifact.expectedBytes}")
        appendLine("rootfs_source=https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS")
    }

    private suspend fun pushText(path: String, text: String, mode: Int) {
        LocalExecutionSubstrate.requireSafeLocalPath(path)
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).use { LocalExecutionSubstrate.pushStream(it, path, mode = mode) }
    }

    private fun requireArm64() {
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "aarch64" }) {
            "Foundry Ubuntu developer environment currently requires ARM64"
        }
    }

    private fun q(value: String): String = LocalExecutionSubstrate.shellQuote(value)
}

package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal data class LegacyUbuntuCompactionTransaction(
    val planDigest: String,
    val removals: List<String>,
    val estimatedReclaimBytes: Long,
)







internal class FoundryUbuntuLegacyCompactor(
    
    private val finalRoot: String,
    private val launcher: String,
) {
    companion object {
        const val TX_ROOT = "/var/cache/droide-foundry/legacy-r1-compaction"
        private const val MAX_REMOVALS = 512
        private const val ROLLBACK_OVERHEAD_BYTES = 64L * 1024L * 1024L
        private val PACKAGE_RE = Regex("[A-Za-z0-9][A-Za-z0-9+_.@:-]{0,119}")
        private val VERSION_SPEC_RE = Regex("[A-Za-z0-9][A-Za-z0-9+_.@:-]{0,119}=[^\\s\\u0000]{1,240}")
    }
    private val hostTxRoot = "$finalRoot/rootfs$TX_ROOT"
    suspend fun exists(): Boolean = execute("test -d ${q(TX_ROOT)}", 8_192).exitCode == 0
    suspend fun begin(
        legacyBroadRoots: List<String>,
        canonicalBaseRoots: List<String>,
        baselineRoots: List<String>,
        protectedPackageSets: List<List<String>>,
    ): LegacyUbuntuCompactionTransaction = withContext(Dispatchers.IO) {
        check(!exists()) { "A legacy Ubuntu compaction transaction is already active" }
        val installed = packageLines("dpkg-query -W -f='\${Package}\\n'")
        val manualBefore = packageLines("apt-mark showmanual")
        val autoBefore = packageLines("apt-mark showauto")
        val legacy = legacyBroadRoots.map(::normalizePackage).filter { it in installed }.distinct().sorted()
        val protected = buildSet {
            addAll(canonicalBaseRoots.map(::normalizePackage))
            addAll(baselineRoots.map(::normalizePackage))
            protectedPackageSets.flatten().mapTo(this, ::normalizePackage)
            // Preserve every other pre-existing manual package except historical r1 broad roots too.


            addAll(manualBefore.filterNot { it in legacy })
        }.filter { it in installed }.sorted()
        val candidates = legacy.filterNot { it in protected }
        val prep = execute("set -eu; rm -rf ${q(TX_ROOT)}; mkdir -p ${q("$TX_ROOT/archives/partial")}", 32_768)
        check(prep.exitCode == 0) { "Could not prepare legacy Ubuntu compaction transaction" }
        try {
            pushText("$hostTxRoot/manual.before", manualBefore.joinToString("\n", postfix = "\n"))
            pushText("$hostTxRoot/auto.before", autoBefore.joinToString("\n", postfix = "\n"))
            pushText("$hostTxRoot/protected.txt", protected.joinToString("\n", postfix = "\n"))
            pushText("$hostTxRoot/candidates.txt", candidates.joinToString("\n", postfix = "\n"))
            writeState("preparing", "pending")
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                val cleanup = execute("rm -rf ${q(TX_ROOT)}", 32_768)
                if (cleanup.exitCode != 0) failure.addSuppressed(IllegalStateException("Could not clear incomplete Ubuntu compaction setup"))
            }
            throw failure
        }
        try {
            markInstalled("manual", protected)
            // Original ownership marks are persisted for rollback.



            markInstalled("auto", candidates)
            // Any unrelated pre-existing auto-orphan must abort the transaction before apply.

            val candidateClosure = dependencyClosure(candidates, installed)
            pushText("$hostTxRoot/legacy-closure.txt", candidateClosure.joinToString("\n", postfix = "\n"))
            val planCommand = if (candidates.isEmpty()) "true" else "apt-get -s autoremove"
            val plan = execute(planCommand, 512_000)
            check(plan.exitCode == 0) { "Could not plan legacy Ubuntu compaction: ${plan.output.takeLast(8_000)}" }
            val removals = simulatedRemovals(plan.output).map(::normalizePackage).distinct().sorted()
            check(removals.size <= MAX_REMOVALS) { "Legacy Ubuntu compaction removal set is unexpectedly large" }
            check(removals.none { it in protected }) { "Legacy Ubuntu compaction would remove a protected package" }
            val foreignRemovals = removals.filterNot { it in candidateClosure }
            check(foreignRemovals.isEmpty()) {
                "Legacy Ubuntu compaction would remove unrelated auto-orphan packages: ${foreignRemovals.take(12).joinToString(",")}"
            }
            val digest = digest(protected, candidates, candidateClosure, removals)
            pushText("$hostTxRoot/removals.txt", removals.joinToString("\n", postfix = "\n"))
            var estimatedReclaimBytes = 0L
            if (removals.isNotEmpty()) {
                val removalArgs = removals.joinToString(" ") { q(it) }
                val specs = execute(
                    "set -eu; for p in $removalArgs; do dpkg-query -W -f='\${Package}=\${Version}\\n' \"\$p\"; done",
                    256_000,
                )
                check(specs.exitCode == 0) { "Could not snapshot exact package versions for Ubuntu rollback" }
                val restoreSpecs = specs.output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
                check(restoreSpecs.size == removals.size && restoreSpecs.all(VERSION_SPEC_RE::matches)) {
                    "Invalid exact package restore manifest"
                }
                pushText("$hostTxRoot/restore-specs.txt", restoreSpecs.joinToString("\n", postfix = "\n"))

                val installedSize = execute(
                    "set -eu; total=0; for p in $removalArgs; do n=\$(dpkg-query -W -f='\${Installed-Size}' \"\$p\" 2>/dev/null || printf 0); total=\$((total+n)); done; printf 'installed_kib=%s\\n' \"\$total\"",
                    64_000,
                )
                check(installedSize.exitCode == 0) { "Could not measure legacy Ubuntu reclaim size" }
                estimatedReclaimBytes = Math.multiplyExact(parsePlanLong(installedSize.output, "installed_kib"), 1024L)

                val specArgs = restoreSpecs.joinToString(" ") { q(it) }
                val printUris = execute(
                    "apt-get --print-uris download $specArgs 2>/dev/null",
                    512_000,
                )
                check(printUris.exitCode == 0) { "Could not plan offline rollback cache for legacy Ubuntu compaction" }
                val rollbackDownloadBytes = uriDownloadBytes(printUris.output)
                DeviceWorkstationStorageGuard.requireHeadroom(
                    
                    additionalBytes = Math.addExact(rollbackDownloadBytes, ROLLBACK_OVERHEAD_BYTES),
                    purpose = "stage an offline rollback cache before compacting the legacy Ubuntu runtime",
                )
                val download = execute(
                    "set -eu; cd $TX_ROOT/archives; apt-get download $specArgs >/tmp/droide-legacy-compaction-download.log 2>&1; " +
                        "test -n \"\$(find . -maxdepth 1 -type f -name '*.deb' -print -quit)\"",
                    256_000,
                )
                check(download.exitCode == 0) { "Could not stage offline rollback packages: ${download.output.takeLast(8_000)}" }
            } else {
                pushText("$hostTxRoot/restore-specs.txt", "")
            }

            writeState("prepared", digest)
            val replan = execute(planCommand, 512_000)
            check(replan.exitCode == 0 && simulatedRemovals(replan.output).map(::normalizePackage).distinct().sorted() == removals) {
                "Legacy Ubuntu compaction plan changed after rollback staging; no packages were removed"
            }
            writeState("applying", digest)
            val applyCommand = if (candidates.isEmpty()) {
                "true"
            } else {
                "apt-get autoremove -y >/tmp/droide-legacy-compaction-apply.log"
            }
            val apply = execute(applyCommand, 512_000)
            check(apply.exitCode == 0) {
                "Could not compact legacy Ubuntu package closure: ${apply.output.takeLast(12_000)}"
            }
            val audit = execute("set -eu; test -z \"\$(dpkg --audit)\"; apt-get check >/dev/null", 256_000)
            check(audit.exitCode == 0) { "Legacy Ubuntu compaction left an unhealthy package database" }
            check(checkInstalled(protected)) { "Legacy Ubuntu compaction removed a protected runtime package" }
            writeState("applied", digest)
            LegacyUbuntuCompactionTransaction(digest, removals, estimatedReclaimBytes)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    rollbackInternal()
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        }
    }

    suspend fun rollback(transaction: LegacyUbuntuCompactionTransaction? = null) = withContext(Dispatchers.IO) {
        if (!exists()) return@withContext
        if (transaction != null) check(readDigest() == transaction.planDigest) { "Legacy Ubuntu compaction transaction changed before rollback" }
        rollbackInternal()
    }

    suspend fun discard(transaction: LegacyUbuntuCompactionTransaction? = null) = withContext(Dispatchers.IO) {
        if (!exists()) return@withContext
        if (transaction != null) check(readDigest() == transaction.planDigest) { "Legacy Ubuntu compaction transaction changed before finalize" }
        val cleanup = execute("rm -rf ${q(TX_ROOT)}; apt-get clean; rm -rf /var/lib/apt/lists/*", 64_000)
        check(cleanup.exitCode == 0) { "Could not finalize legacy Ubuntu compaction transaction" }
    }

    suspend fun recoverInterrupted(compactionAlreadyCommitted: Boolean) = withContext(Dispatchers.IO) {
        if (!exists()) return@withContext
        if (compactionAlreadyCommitted) {
            discard()
        } else {
            rollbackInternal()
        }
    }

    private suspend fun rollbackInternal() {
        val phase = readPhase()
        if (phase == null) {
            val cleanup = execute("rm -rf ${q(TX_ROOT)}", 32_768)
            check(cleanup.exitCode == 0) { "Could not clear incomplete legacy Ubuntu compaction setup" }
            return
        }
        if (phase == "applying" || phase == "applied") {
            val specs = readLines("$TX_ROOT/restore-specs.txt")
            if (specs.isNotEmpty()) {
                check(specs.all(VERSION_SPEC_RE::matches)) { "Legacy Ubuntu rollback manifest is invalid" }
                val args = specs.joinToString(" ") { q(it) }
                val reinstall = execute(
                    "set -eu; apt-get -y --no-download --reinstall --no-install-recommends " +
                        "-o Dir::Cache::archives=$TX_ROOT/archives install $args >/tmp/droide-legacy-compaction-rollback.log; dpkg --configure -a",
                    512_000,
                )
                check(reinstall.exitCode == 0) { "Could not restore legacy Ubuntu packages from offline rollback cache: ${reinstall.output.takeLast(12_000)}" }
            }
        }
        restoreMarks()
        val audit = execute("set -eu; dpkg --configure -a; test -z \"\$(dpkg --audit)\"; apt-get check >/dev/null", 256_000)
        check(audit.exitCode == 0) { "Legacy Ubuntu rollback did not restore a healthy package database" }
        val cleanup = execute("rm -rf ${q(TX_ROOT)}; apt-get clean; rm -rf /var/lib/apt/lists/*", 64_000)
        check(cleanup.exitCode == 0) { "Could not clear rolled-back Ubuntu compaction state" }
    }

    private suspend fun restoreMarks() {
        val auto = readLines("$TX_ROOT/auto.before").map(::normalizePackage)
        val manual = readLines("$TX_ROOT/manual.before").map(::normalizePackage)
        markInstalled("auto", auto)
        markInstalled("manual", manual)
    }

    private suspend fun markInstalled(mode: String, packages: List<String>) {
        require(mode == "auto" || mode == "manual")
        packages.chunked(48).forEach { chunk ->
            if (chunk.isEmpty()) return@forEach
            val args = chunk.joinToString(" ") { q(it) }
            val mark = execute(
                "set -eu; keep=''; for p in $args; do if dpkg-query -W -f='\${Status}' \"\$p\" 2>/dev/null | grep -Fxq 'install ok installed'; then keep=\"\$keep \$p\"; fi; done; [ -z \"\$keep\" ] || apt-mark $mode \$keep >/dev/null",
                128_000,
            )
            check(mark.exitCode == 0) { "Could not restore Ubuntu APT ownership marks" }
        }
    }

    private suspend fun dependencyClosure(candidates: List<String>, installed: Set<String>): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val args = candidates.joinToString(" ") { q(it) }
        val result = execute(
            "apt-cache depends --recurse --installed --important $args",
            1_048_576,
        )
        check(result.exitCode == 0) { "Could not resolve the historical Ubuntu dependency closure" }
        return buildSet {
            addAll(candidates)
            result.output.lineSequence().forEach { raw ->
                val trimmed = raw.trim()
                val value = when {
                    raw.isNotEmpty() && !raw.first().isWhitespace() && !trimmed.startsWith("|") -> trimmed.substringBefore(' ')
                    trimmed.startsWith("Depends:") -> trimmed.substringAfter(':').trim()
                    trimmed.startsWith("PreDepends:") -> trimmed.substringAfter(':').trim()
                    trimmed.startsWith("|Depends:") -> trimmed.substringAfter(':').trim()
                    trimmed.startsWith("|PreDepends:") -> trimmed.substringAfter(':').trim()
                    else -> null
                }?.removePrefix("<")?.removeSuffix(">")?.substringBefore(':')
                if (value != null && PACKAGE_RE.matches(value) && value in installed) add(value)
            }
        }.sorted()
    }

    private suspend fun checkInstalled(packages: List<String>): Boolean {
        if (packages.isEmpty()) return true
        return packages.chunked(48).all { chunk ->
            val args = chunk.joinToString(" ") { q(it) }
            execute(
                "set -eu; for p in $args; do dpkg-query -W -f='\${Status}\\n' \"\$p\" | grep -Fxq 'install ok installed'; done",
                128_000,
            ).exitCode == 0
        }
    }

    private suspend fun packageLines(command: String): Set<String> {
        val result = execute(command, 512_000)
        check(result.exitCode == 0) { "Could not inspect Ubuntu package ownership state" }
        return result.output.lineSequence().map(String::trim).filter(String::isNotEmpty).map(::normalizePackage).toSet()
    }

    private suspend fun readLines(path: String): List<String> {
        val result = execute("test -f ${q(path)} && cat ${q(path)} || true", 512_000)
        check(result.exitCode == 0)
        return result.output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    }

    private suspend fun readPhase(): String? {
        val result = execute("test ! -f ${q("$TX_ROOT/state")} || sed -n 's/^phase=//p' ${q("$TX_ROOT/state")} | head -1", 8_192)
        check(result.exitCode == 0)
        return result.output.trim().takeIf { it in setOf("preparing", "prepared", "applying", "applied") }
    }

    private suspend fun readDigest(): String {
        val result = execute("sed -n 's/^digest=//p' ${q("$TX_ROOT/state")} | head -1", 8_192)
        check(result.exitCode == 0)
        return result.output.trim()
    }

    private suspend fun writeState(phase: String, digest: String) {
        require(phase in setOf("preparing", "prepared", "applying", "applied"))
        require(digest == "pending" || digest.matches(Regex("[0-9a-f]{64}")))
        val temp = "$TX_ROOT/state.tmp"
        pushText("$hostTxRoot/state.tmp", "phase=$phase\ndigest=$digest\n")
        val commit = execute("mv -f ${q(temp)} ${q("$TX_ROOT/state")}", 8_192)
        check(commit.exitCode == 0) { "Could not atomically commit legacy Ubuntu compaction state" }
    }

    private suspend fun pushText(path: String, text: String) {
        LocalExecutionSubstrate.requireSafeLocalPath(path)
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).use { LocalExecutionSubstrate.pushStream(it, path, mode = 420) }
    }

    private suspend fun execute(command: String, maxOutputBytes: Int): ExecResult {
        val full = "/system/bin/sh ${q(launcher)} /bin/sh -lc ${q(command)}"
        return LocalExecutionSubstrate.shellBounded(full, maxOutputBytes)
    }

    private fun normalizePackage(raw: String): String {
        val value = raw.trim().substringBefore(':')
        require(PACKAGE_RE.matches(value)) { "Invalid Ubuntu package name in compaction transaction" }
        return value
    }

    private fun simulatedRemovals(output: String): List<String> = output.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("Remv ") }
        .mapNotNull { it.split(Regex("\\s+")).getOrNull(1) }
        .toList()

    private fun uriDownloadBytes(output: String): Long = output.lineSequence().sumOf { line ->
        val fields = line.trim().split(Regex("\\s+"))
        fields.firstNotNullOfOrNull { it.toLongOrNull() } ?: 0L
    }

    private fun parsePlanLong(output: String, key: String): Long = output.lineSequence()
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.trim()
        ?.toLongOrNull()
        ?: error("Legacy Ubuntu compaction planner did not report $key")

    private fun digest(
        protected: List<String>,
        candidates: List<String>,
        candidateClosure: List<String>,
        removals: List<String>,
    ): String {
        val input = buildString {
            appendLine("schema=2")
            appendLine("protected=${protected.joinToString(",")}")
            appendLine("candidates=${candidates.joinToString(",")}")
            appendLine("candidate_closure=${candidateClosure.joinToString(",")}")
            appendLine("removals=${removals.joinToString(",")}")
        }
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun q(value: String): String = LocalExecutionSubstrate.shellQuote(value)
}

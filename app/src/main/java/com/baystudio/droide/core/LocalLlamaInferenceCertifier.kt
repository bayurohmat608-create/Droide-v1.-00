package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


class LocalLlamaInferenceCertifier(
    context: Context,
    private val bridge: DeviceBridgeManager,
    private val runtimeManager: LocalLlamaRuntimeManager = LocalLlamaRuntimeManager(context.applicationContext, bridge),
    private val modelStore: LocalGgufModelStore = LocalGgufModelStore(LocalGgufAndroidStorage.root(context.applicationContext)),
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }
    private val certificateRoot = File(context.noBackupFilesDir, "local-llama-certifications").apply { mkdirs() }.canonicalFile
    private val remoteModels = "${DeviceBridgeManager.remoteRoot()}/local-models/llama.cpp"
    private val operationMutex = Mutex()

    enum class Phase { VERIFYING_MODEL, PREPARING_RUNTIME, CHECKING_MEMORY, SYNCING_MODEL, DECODING, CERTIFIED }

    @Serializable
    data class Certification(
        val schema: Int = SCHEMA,
        val policyVersion: Int,
        val runtimeVersion: String,
        val runtimeAssetSha256: String,
        val modelId: String,
        val modelBytes: Long,
        val ggufVersion: Int,
        val targetAbi: String,
        val targetApi: Int,
        val targetEnvironmentSha256: String,
        val totalMemoryBytes: Long,
        val availableMemoryBytesAtStart: Long,
        val requiredAvailableBytes: Long,
        val systemReserveBytes: Long,
        val workingOverheadBytes: Long,
        val contextSize: Int,
        val predictTokens: Int,
        val batchSize: Int,
        val ubatchSize: Int,
        val threads: Int,
        val commandOutputSha256: String,
        val durationMs: Long,
        val certifiedAtEpochMs: Long,
    ) {
        fun matches(
            runtime: LocalLlamaRuntimeManager.Status,
            model: LocalGgufModelStore.StoredModel,
            currentTargetEnvironmentSha256: String,
        ): Boolean =
            schema == SCHEMA &&
                policyVersion == LocalLlamaInferencePolicy.POLICY_VERSION &&
                runtimeVersion == LocalLlamaRuntimeContract.VERSION &&
                runtimeAssetSha256 == LocalLlamaRuntimeContract.ASSET_SHA256 &&
                runtime.version == runtimeVersion && runtime.runtimeSmokeCertified &&
                modelId == model.id && modelBytes == model.sizeBytes && ggufVersion == model.header.version &&
                targetAbi == runtime.target.abi && targetApi == runtime.target.api &&
                targetEnvironmentSha256 == currentTargetEnvironmentSha256 &&
                targetEnvironmentSha256.matches(Regex("[0-9a-f]{64}")) &&
                contextSize == LocalLlamaInferencePolicy.CONTEXT_SIZE &&
                predictTokens == LocalLlamaInferencePolicy.PREDICT_TOKENS &&
                batchSize == LocalLlamaInferencePolicy.BATCH_SIZE &&
                ubatchSize == LocalLlamaInferencePolicy.UBATCH_SIZE &&
                threads in 1..LocalLlamaInferencePolicy.MAX_THREADS &&
                totalMemoryBytes > 0L && availableMemoryBytesAtStart in 1L..totalMemoryBytes &&
                requiredAvailableBytes > modelBytes && systemReserveBytes > 0L && workingOverheadBytes > 0L &&
                commandOutputSha256.matches(Regex("[0-9a-f]{64}")) && durationMs > 0L && certifiedAtEpochMs > 0L
    }

    suspend fun certificationFor(modelId: String): Certification? =
        certificationsFor(listOf(modelId))[modelId]

    data class ServingPreparation(
        val runtime: LocalLlamaRuntimeManager.Status,
        val model: LocalGgufModelStore.StoredModel,
        val certificate: Certification,
        val memory: LocalLlamaInferencePolicy.MemorySnapshot,
        val plan: LocalLlamaInferencePolicy.Plan,
        val targetEnvironmentSha256: String,
        val remoteModelPath: String,
    )

    // Re-proves every durable 08aw identity before a long-lived server is allowed to consume it.


    suspend fun prepareCertifiedForServing(
        modelId: String,
        onTransferProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): ServingPreparation = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            require(modelId.matches(Regex("[0-9a-f]{64}"))) { "Invalid local GGUF model id" }
            val job = currentCoroutineContext()[Job]
            fun checkCancelled() { job?.ensureActive() }

            val model = modelStore.findStored(modelId) ?: error("Imported GGUF model is missing")
            check(modelStore.verifyIntegrity(modelId, ::checkCancelled)) { "Imported GGUF integrity verification failed" }
            checkCancelled()

            val runtime = runtimeManager.ensureInstalled()
            check(runtime.runtimeSmokeCertified) { "Local llama.cpp runtime is not smoke-certified" }
            val targetEnvironment = probeTargetEnvironmentSha256()
            val certificate = readCertificate(modelId) ?: error("Local GGUF must be inference-certified before serving")
            check(certificate.matches(runtime, model, targetEnvironment)) {
                "Local GGUF certificate is stale for the current runtime/device; certify it again"
            }

            val memory = probeMemory()
            val plan = LocalLlamaInferencePolicy.plan(model.sizeBytes, memory)
            checkCancelled()
            val remoteModel = ensureRemoteModel(model, ::checkCancelled, onTransferProgress)
            check(remoteModelMatches(remoteModel, model)) { "Serving GGUF failed final remote integrity verification" }
            checkCancelled()
            ServingPreparation(runtime, model, certificate, memory, plan, targetEnvironment, remoteModel)
        }
    }


    suspend fun certificationsFor(modelIds: Collection<String>): Map<String, Certification> = withContext(Dispatchers.IO) {
        val ids = modelIds.asSequence().map(String::trim).filter { it.matches(Regex("[0-9a-f]{64}")) }
            .distinct().take(MAX_INVENTORY_MODELS).toList()
        if (ids.isEmpty()) return@withContext emptyMap()
        val runtime = runtimeManager.status() ?: return@withContext emptyMap()
        val targetEnvironment = runSuspendCatching { probeTargetEnvironmentSha256() }.getOrNull()
            ?: return@withContext emptyMap()
        ids.mapNotNull { modelId ->
            val model = runCatching { modelStore.findStored(modelId) }.getOrNull() ?: return@mapNotNull null
            val certificate = readCertificate(modelId) ?: return@mapNotNull null
            (modelId to certificate).takeIf { certificate.matches(runtime, model, targetEnvironment) }
        }.toMap()
    }

    suspend fun certify(
        modelId: String,
        onPhase: (Phase) -> Unit = {},
        onTransferProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Certification = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            require(modelId.matches(Regex("[0-9a-f]{64}"))) { "Invalid local GGUF model id" }
            val job = currentCoroutineContext()[Job]
            fun checkCancelled() { job?.ensureActive() }

            onPhase(Phase.VERIFYING_MODEL)
            val model = modelStore.findStored(modelId) ?: error("Imported GGUF model is missing")
            check(modelStore.verifyIntegrity(modelId, ::checkCancelled)) { "Imported GGUF integrity verification failed" }
            checkCancelled()

            onPhase(Phase.PREPARING_RUNTIME)
            val runtime = runtimeManager.ensureInstalled()
            check(runtime.runtimeSmokeCertified) { "Local llama.cpp runtime is not smoke-certified" }

            onPhase(Phase.CHECKING_MEMORY)
            LocalLlamaInferencePolicy.plan(model.sizeBytes, probeMemory())
            checkCancelled()

            onPhase(Phase.SYNCING_MODEL)
            val remoteModel = ensureRemoteModel(model, ::checkCancelled, onTransferProgress)
            checkCancelled()


            onPhase(Phase.CHECKING_MEMORY)
            val memory = probeMemory()
            val plan = LocalLlamaInferencePolicy.plan(model.sizeBytes, memory)
            val targetEnvironment = probeTargetEnvironmentSha256()
            checkCancelled()

            onPhase(Phase.DECODING)
            val started = System.currentTimeMillis()
            val output = runDecode(runtime, remoteModel, plan)
            val duration = (System.currentTimeMillis() - started).coerceAtLeast(1L)
            val certificate = Certification(
                policyVersion = LocalLlamaInferencePolicy.POLICY_VERSION,
                runtimeVersion = runtime.version,
                runtimeAssetSha256 = LocalLlamaRuntimeContract.ASSET_SHA256,
                modelId = model.id,
                modelBytes = model.sizeBytes,
                ggufVersion = model.header.version,
                targetAbi = runtime.target.abi,
                targetApi = runtime.target.api,
                targetEnvironmentSha256 = targetEnvironment,
                totalMemoryBytes = memory.totalBytes,
                availableMemoryBytesAtStart = memory.availableBytes,
                requiredAvailableBytes = plan.requiredAvailableBytes,
                systemReserveBytes = plan.systemReserveBytes,
                workingOverheadBytes = plan.workingOverheadBytes,
                contextSize = plan.contextSize,
                predictTokens = plan.predictTokens,
                batchSize = plan.batchSize,
                ubatchSize = plan.ubatchSize,
                threads = plan.threads,
                commandOutputSha256 = sha256(output.toByteArray(Charsets.UTF_8)),
                durationMs = duration,
                certifiedAtEpochMs = System.currentTimeMillis(),
            )
            writeCertificate(certificate)
            val durable = readCertificate(modelId)
            check(durable == certificate && durable.matches(runtime, model, targetEnvironment)) {
                "Local GGUF inference certification was not durably committed"
            }
            onPhase(Phase.CERTIFIED)
            durable
        }
    }

    private suspend fun probeMemory(): LocalLlamaInferencePolicy.MemorySnapshot {
        val command = """
            set -eu
            /system/bin/toybox awk '
              /^MemTotal:/ { total=${'$'}2 }
              /^MemAvailable:/ { avail=${'$'}2 }
              /^MemFree:/ { free=${'$'}2 }
              /^Buffers:/ { buffers=${'$'}2 }
              /^Cached:/ { cached=${'$'}2 }
              /^SReclaimable:/ { reclaim=${'$'}2 }
              /^Shmem:/ { shmem=${'$'}2 }
              END {
                if (avail <= 0) avail = free + buffers + cached + reclaim - shmem
                if (total <= 0 || avail <= 0 || avail > total) exit 2
                printf "%.0f %.0f\\n", total, avail
              }
            ' /proc/meminfo
            cpus=${'$'}(/system/bin/toybox grep -c '^processor' /proc/cpuinfo 2>/dev/null || true)
            case "${'$'}cpus" in ''|*[!0-9]*) cpus=1;; esac
            [ "${'$'}cpus" -ge 1 ] 2>/dev/null || cpus=1
            printf '%s\\n' "${'$'}cpus"
        """.trimIndent()
        val result = bridge.shellBounded(command, maxOutputBytes = 8_192)
        check(result.exitCode == 0 && !result.truncated) { "Could not inspect Device Workstation memory" }
        val lines = result.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.size >= 2) { "Incomplete Device Workstation memory report" }
        val kib = lines[0].split(Regex("\\s+"))
        require(kib.size == 2) { "Invalid Device Workstation memory report" }
        val total = Math.multiplyExact(kib[0].toLongOrNull() ?: error("Invalid total memory"), 1024L)
        val available = Math.multiplyExact(kib[1].toLongOrNull() ?: error("Invalid available memory"), 1024L)
        val cpus = lines[1].toIntOrNull() ?: 1
        return LocalLlamaInferencePolicy.MemorySnapshot(total, available, cpus)
    }


    private suspend fun probeTargetEnvironmentSha256(): String {
        val command = """
            printf '%s\n' "${'$'}(getprop ro.product.device)" "${'$'}(getprop ro.build.fingerprint)" "${'$'}(getprop ro.soc.model)"
        """.trimIndent()
        val result = bridge.shellBounded(command, maxOutputBytes = 16_384)
        check(result.exitCode == 0 && !result.truncated) { "Could not inspect Device Workstation target environment" }
        val values = result.stdout.lineSequence().take(3).map(String::trim).toList()
        require(values.size >= 2 && values[0].isNotBlank() && values[1].isNotBlank()) {
            "Incomplete Device Workstation target environment"
        }
        require(values.all { value -> value.length <= 512 && value.none { it == '\u0000' || it == '\r' || it == '\n' } }) {
            "Invalid Device Workstation target environment"
        }
        val canonical = "device=${values[0]}\nfingerprint=${values[1]}\nsoc=${values.getOrElse(2) { "" }}\n"
        return sha256(canonical.toByteArray(Charsets.UTF_8))
    }

    private suspend fun ensureRemoteModel(
        model: LocalGgufModelStore.StoredModel,
        checkCancelled: () -> Unit,
        onTransferProgress: (copiedBytes: Long, totalBytes: Long) -> Unit,
    ): String {
        val final = "$remoteModels/${model.id}.gguf"
        DeviceBridgeManager.requireSafeRemotePath(final)
        if (remoteModelMatches(final, model)) return final

        DeviceWorkstationStorageGuard.requireHeadroom(
            bridge = bridge,
            additionalBytes = model.sizeBytes,
            purpose = "stage the local GGUF model for certification",
        )
        val stageDir = "$remoteModels/.staging"
        val stage = "$stageDir/${model.id}-${UUID.randomUUID().toString().replace("-", "").take(12)}.part"
        listOf(remoteModels, stageDir, stage).forEach(DeviceBridgeManager::requireSafeRemotePath)
        val prepare = bridge.shellBounded(
            "set -eu; mkdir -p ${q(stageDir)}; rm -f ${q(stage)}",
            maxOutputBytes = 8_192,
        )
        check(prepare.exitCode == 0) { "Could not prepare remote GGUF staging" }
        try {
            checkCancelled()
            bridge.pushCancellable(model.file, stage, mode = 420, onProgress = onTransferProgress)
            checkCancelled()
            check(remoteModelMatches(stage, model)) { "Transferred GGUF failed remote integrity verification" }
            val activate = bridge.shellBounded(
                "set -eu; mkdir -p ${q(remoteModels)}; rm -f ${q(final)}; mv ${q(stage)} ${q(final)}",
                maxOutputBytes = 8_192,
            )
            check(activate.exitCode == 0) { "Could not activate remote GGUF model" }
            check(remoteModelMatches(final, model)) { "Activated GGUF failed remote integrity verification" }
            return final
        } catch (cancelled: CancellationException) {
            cleanupRemoteStage(stage)
            throw cancelled
        } catch (failure: Throwable) {
            cleanupRemoteStage(stage)
            throw failure
        }
    }

    private suspend fun cleanupRemoteStage(stage: String) = withContext(NonCancellable + Dispatchers.IO) {
        runSuspendCatching { bridge.ensureHealthyConnection() }
        if (bridge.state.value.connected != null) {
            runSuspendCatching { bridge.shellBounded("rm -f ${q(stage)}", maxOutputBytes = 8_192) }
        }
    }

    private suspend fun remoteModelMatches(path: String, model: LocalGgufModelStore.StoredModel): Boolean {
        DeviceBridgeManager.requireSafeRemotePath(path)
        val result = bridge.shellBounded(
            "set -eu; test -f ${q(path)}; test ! -L ${q(path)}; " +
                "test \"${'$'}(/system/bin/toybox wc -c < ${q(path)} | tr -d ' ')\" = ${q(model.sizeBytes.toString())}; " +
                "/system/bin/toybox sha256sum ${q(path)}",
            maxOutputBytes = 8_192,
        )
        if (result.exitCode != 0 || result.truncated) return false
        return result.stdout.trim().substringBefore(' ').equals(model.id, ignoreCase = false)
    }

    private suspend fun runDecode(
        runtime: LocalLlamaRuntimeManager.Status,
        remoteModel: String,
        plan: LocalLlamaInferencePolicy.Plan,
    ): String {
        val cli = runtime.cliPath
        DeviceBridgeManager.requireSafeRemotePath(cli)
        DeviceBridgeManager.requireSafeRemotePath(remoteModel)
        require(ProcessSecurityPolicy.isAllowedRemoteExecutable(cli, DeviceBridgeManager.remoteRoot())) {
            "Local llama.cpp executable escaped Device Workstation"
        }
        val command = listOf(
            cli,
            "-m", remoteModel,
            "-c", plan.contextSize.toString(),
            "-n", plan.predictTokens.toString(),
            "-b", plan.batchSize.toString(),
            "-ub", plan.ubatchSize.toString(),
            "-t", plan.threads.toString(),
            "--cache-ram", "0",
            "--no-warmup",
            "--no-display-prompt",
            "--show-timings",
            "-st",
            "-p", CERTIFICATION_PROMPT,
        ).joinToString(" ") { q(it) }
        val lease = RemoteProcessLease.create("llama-certify")
        return try {
            val result = withTimeout(LocalLlamaInferencePolicy.CERTIFICATION_TIMEOUT_MS) {
                bridge.shellStreaming(
                    lease.wrap("exec /system/bin/toybox nice -n 10 $command"),
                    maxOutputBytes = LocalLlamaInferencePolicy.MAX_RETAINED_OUTPUT_BYTES,
                ) { _, _ -> }
            }
            withContext(NonCancellable) { runSuspendCatching { bridge.shellBounded(lease.cleanupCommand(), 8_192) } }
            check(result.exitCode == 0 && !result.truncated) {
                "Local GGUF certification decode failed (exit ${result.exitCode}): ${result.combined.takeLast(8_000)}"
            }
            check(result.combined.isNotBlank()) { "Local GGUF certification produced no runtime evidence" }
            result.combined
        } catch (timeout: TimeoutCancellationException) {
            terminateLease(lease)
            error("Local GGUF certification timed out after ${LocalLlamaInferencePolicy.CERTIFICATION_TIMEOUT_MS / 1000}s")
        } catch (cancelled: CancellationException) {
            terminateLease(lease)
            throw cancelled
        } catch (failure: Throwable) {
            terminateLease(lease)
            throw failure
        }
    }

    private suspend fun terminateLease(lease: RemoteProcessLease) = withContext(NonCancellable + Dispatchers.IO) {
        runSuspendCatching { bridge.ensureHealthyConnection() }
        if (bridge.state.value.connected != null) {
            runSuspendCatching { bridge.shellBounded(lease.terminateCommand(), maxOutputBytes = 16_384) }
            runSuspendCatching { bridge.shellBounded(lease.cleanupCommand(), maxOutputBytes = 8_192) }
        }
    }

    private fun certificateFile(modelId: String): File {
        require(modelId.matches(Regex("[0-9a-f]{64}"))) { "Invalid local GGUF model id" }
        val file = PathSecurity.resolveWithin(certificateRoot, "$modelId.json")
        require(!PathSecurity.isSymbolicLink(file)) { "Unsafe local GGUF certification path" }
        return file
    }

    private fun readCertificate(modelId: String): Certification? {
        val file = certificateFile(modelId)
        if (!file.isFile || file.length() !in 2L..64_000L) return null
        return runCatching { json.decodeFromString<Certification>(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun writeCertificate(certificate: Certification) {
        val destination = certificateFile(certificate.modelId)
        val stage = File(certificateRoot, ".${certificate.modelId}-${UUID.randomUUID().toString().take(8)}.tmp")
        require(PathSecurity.contains(certificateRoot, stage) && !PathSecurity.isSymbolicLink(stage)) {
            "Unsafe local GGUF certification staging path"
        }
        try {
            FileOutputStream(stage, false).use { output ->
                output.write(json.encodeToString(certificate).toByteArray(Charsets.UTF_8))
                output.write('\n'.code)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(stage.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(stage.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { stage.delete() }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun q(value: String): String = DeviceBridgeManager.shellQuote(value)

    private companion object {
        const val SCHEMA = 1
        const val MAX_INVENTORY_MODELS = 128
        const val CERTIFICATION_PROMPT = "Droide local model certification probe."
    }
}

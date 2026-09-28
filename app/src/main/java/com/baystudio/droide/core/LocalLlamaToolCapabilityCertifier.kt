package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Durable, model/template/runtime-specific evidence for local tool calling.
class LocalLlamaToolCapabilityCertifier(
    context: Context,
    private val llm: LlmClient = LlmClient(),
) {
    @Serializable
    data class Certificate(
        val schema: Int = SCHEMA,
        val policyVersion: Int = LocalLlamaToolCapabilityPolicy.POLICY_VERSION,
        val providerId: String = LocalLlamaServerPolicy.PROVIDER_ID,
        val modelId: String,
        val runtimeVersion: String = LocalLlamaRuntimeContract.VERSION,
        val runtimeAssetSha256: String = LocalLlamaRuntimeContract.ASSET_SHA256,
        val serverProfileVersion: Int = LocalLlamaServerPolicy.PROFILE_VERSION,
        val serverBuildInfo: String,
        val chatTemplateSha256: String,
        val chatTemplateCapsSha256: String,
        val targetAbi: String,
        val targetApi: Int,
        val targetEnvironmentSha256: String,
        val requiredProbeResponseSha256: String,
        val autonomousProbeResponseSha256: String,
        val continuationProbeResponseSha256: String,
        val parallelToolCalls: Boolean = false,
        val certifiedAtEpochMs: Long,
    ) {
        fun matches(modelId: String, facts: LocalLlamaToolCapabilityPolicy.ServerFacts): Boolean =
            schema == SCHEMA &&
                policyVersion == LocalLlamaToolCapabilityPolicy.POLICY_VERSION &&
                providerId == LocalLlamaServerPolicy.PROVIDER_ID &&
                this.modelId == modelId && modelId.matches(SHA256) &&
                runtimeVersion == LocalLlamaRuntimeContract.VERSION &&
                runtimeAssetSha256 == LocalLlamaRuntimeContract.ASSET_SHA256 &&
                serverProfileVersion == LocalLlamaServerPolicy.PROFILE_VERSION &&
                serverBuildInfo == facts.buildInfo &&
                chatTemplateSha256 == facts.chatTemplateSha256 &&
                chatTemplateCapsSha256 == facts.chatTemplateCapsSha256 &&
                targetAbi == facts.targetAbi && targetApi == facts.targetApi &&
                targetEnvironmentSha256 == facts.targetEnvironmentSha256 &&
                targetEnvironmentSha256.matches(SHA256) &&
                requiredProbeResponseSha256.matches(SHA256) &&
                autonomousProbeResponseSha256.matches(SHA256) &&
                continuationProbeResponseSha256.matches(SHA256) &&
                !parallelToolCalls && certifiedAtEpochMs > 0L
    }

    class ProbeRejectedException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    private val root = File(context.noBackupFilesDir, "local-llama-tool-capabilities").apply { mkdirs() }.canonicalFile
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }
    private val random = SecureRandom()

    fun certificateFor(modelId: String, facts: LocalLlamaToolCapabilityPolicy.ServerFacts): Certificate? {
        requireModelId(modelId)
        val file = certificateFile(modelId)
        val certificate = runCatching { json.decodeFromString<Certificate>(file.readText()) }.getOrNull() ?: return null
        return certificate.takeIf { it.matches(modelId, facts) }
    }

    suspend fun certify(
        provider: AiProvider,
        apiKey: String,
        modelId: String,
        facts: LocalLlamaToolCapabilityPolicy.ServerFacts,
    ): Certificate = withContext(Dispatchers.IO) {
        require(provider.id == LocalLlamaServerPolicy.PROVIDER_ID && provider.networkScope == ProviderNetworkScope.LOOPBACK_ONLY) {
            "Tool capability certification requires the managed local llama.cpp provider"
        }
        require(provider.model == modelId) { "Tool capability provider/model mismatch" }
        require(apiKey.isNotBlank() && apiKey.length <= 512) { "Local llama.cpp session credential is unavailable" }
        requireModelId(modelId)

        val requiredNonce = nonce("required")
        val autonomousNonce = nonce("auto")
        val resultCanary = nonce("result")
        val requiredResponse = withTimeout(LocalLlamaToolCapabilityPolicy.PROBE_TIMEOUT_MS) {
                llm.chatCompletions(
                    provider.id,
                    provider.baseUrl,
                    apiKey,
                    modelId,
                    LocalLlamaToolCapabilityPolicy.requiredProbeBody(modelId, requiredNonce),
                )
            }
            val requiredCall = validateOrReject(modelId) {
                LocalLlamaToolCapabilityPolicy.requireSingleToolCall(requiredResponse, requiredNonce)
            }

            val autonomousResponse = withTimeout(LocalLlamaToolCapabilityPolicy.PROBE_TIMEOUT_MS) {
                llm.chatCompletions(
                    provider.id,
                    provider.baseUrl,
                    apiKey,
                    modelId,
                    LocalLlamaToolCapabilityPolicy.autonomousProbeBody(modelId, autonomousNonce),
                )
            }
            validateOrReject(modelId) {
                LocalLlamaToolCapabilityPolicy.requireSingleToolCall(autonomousResponse, autonomousNonce)
            }

            val continuationResponse = withTimeout(LocalLlamaToolCapabilityPolicy.PROBE_TIMEOUT_MS) {
                llm.chatCompletions(
                    provider.id,
                    provider.baseUrl,
                    apiKey,
                    modelId,
                    LocalLlamaToolCapabilityPolicy.continuationProbeBody(modelId, requiredNonce, requiredCall, resultCanary),
                )
            }
            validateOrReject(modelId) {
                LocalLlamaToolCapabilityPolicy.requireContinuation(continuationResponse, resultCanary)
            }

            val certificate = Certificate(
                modelId = modelId,
                serverBuildInfo = facts.buildInfo,
                chatTemplateSha256 = facts.chatTemplateSha256,
                chatTemplateCapsSha256 = facts.chatTemplateCapsSha256,
                targetAbi = facts.targetAbi,
                targetApi = facts.targetApi,
                targetEnvironmentSha256 = facts.targetEnvironmentSha256,
                requiredProbeResponseSha256 = hashResponse(requiredResponse),
                autonomousProbeResponseSha256 = hashResponse(autonomousResponse),
                continuationProbeResponseSha256 = hashResponse(continuationResponse),
                certifiedAtEpochMs = System.currentTimeMillis(),
            )
        writeCertificate(certificate)
        certificate
    }

    private inline fun <T> validateOrReject(modelId: String, block: () -> T): T = try {
        block()
    } catch (failure: Throwable) {
        revoke(modelId)
        throw ProbeRejectedException(
            "Local GGUF failed tool-capability validation: ${failure.message ?: failure::class.java.simpleName}",
            failure,
        )
    }

    fun revoke(modelId: String): Boolean {
        requireModelId(modelId)
        return runCatching { Files.deleteIfExists(certificateFile(modelId).toPath()) }.getOrDefault(false)
    }

    private fun writeCertificate(certificate: Certificate) {
        val final = certificateFile(certificate.modelId)
        val stage = File(root, ".${certificate.modelId}-${System.nanoTime()}.part")
        require(stage.parentFile?.canonicalFile == root) { "Tool certificate staging escaped its root" }
        try {
            FileOutputStream(stage).use { out ->
                out.write(json.encodeToString(certificate).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            try {
                Files.move(stage.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(stage.toPath(), final.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            require(certificateFor(certificate.modelId, factsFrom(certificate)) != null) {
                "Tool certificate failed round-trip validation"
            }
        } finally {
            runCatching { Files.deleteIfExists(stage.toPath()) }
        }
    }

    private fun factsFrom(certificate: Certificate) = LocalLlamaToolCapabilityPolicy.ServerFacts(
        buildInfo = certificate.serverBuildInfo,
        chatTemplateSha256 = certificate.chatTemplateSha256,
        chatTemplateCapsSha256 = certificate.chatTemplateCapsSha256,
        targetAbi = certificate.targetAbi,
        targetApi = certificate.targetApi,
        targetEnvironmentSha256 = certificate.targetEnvironmentSha256,
    )

    private fun certificateFile(modelId: String): File {
        val file = File(root, "$modelId.json").canonicalFile
        require(file.parentFile == root) { "Tool certificate path escaped its root" }
        return file
    }

    private fun hashResponse(response: String): String {
        require(response.length <= LocalLlamaToolCapabilityPolicy.MAX_RESPONSE_CHARS) { "Tool probe response is too large" }
        return LocalLlamaToolCapabilityPolicy.sha256(response.toByteArray(Charsets.UTF_8))
    }

    private fun nonce(label: String): String {
        val bytes = ByteArray(12).also(random::nextBytes)
        return "droide_${label}_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun requireModelId(modelId: String) {
        require(modelId.matches(SHA256)) { "Invalid local GGUF model id" }
    }

    companion object {
        private const val SCHEMA = 1
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

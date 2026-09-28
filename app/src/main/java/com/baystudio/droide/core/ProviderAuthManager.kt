package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

// Errors are never treated as successful validation.
object ProviderAuthManager {
    fun authMethods(provider: AiProvider): List<ProviderAuthMethodDescriptor> = ProviderAuthMethods.forProvider(provider)

    private const val MAX_MODEL_RESPONSE_BYTES = 2_000_000L
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun validate(provider: AiProvider, apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        if (provider.needsKey && apiKey.isBlank()) {
            if (!ProviderAuthMethods.canResolveWithoutStoredSecret(provider))
                return@withContext Result.failure(Exception("API key is empty"))
        }

        try {
            if (provider.authScheme == ProviderAuthScheme.AWS_SIGV4_BEDROCK) {
                return@withContext validateBedrock(provider, apiKey)
            }
            if (provider.configuredModels.isNotEmpty() && provider.authScheme in TOKEN_RESOLVED_SCHEMES) {
                ProviderRequestAuthorizer.resolveOnly(provider, apiKey)
                return@withContext Result.success(provider.configuredModels)
            }
            val modelsUrl = provider.baseUrl.trimEnd('/') + if (provider.id == "openrouter") {
                "/models?supported_parameters=tools&output_modalities=text"
            } else {
                "/models"
            }
            val target = runCatching { NetworkSecurity.validateProviderTarget(provider, modelsUrl) }
                .getOrElse { return@withContext Result.failure(Exception("Unsafe/invalid provider URL: ${it.message}")) }
            val builder = Request.Builder().url(target.url).addHeader("Accept", "application/json")
            ProviderTransportV2.applyAuth(builder, provider, apiKey)
            val req = ProviderRequestAuthorizer.authorize(builder.get().build(), provider, apiKey)
            client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(req).awaitResponse().use { r ->
                val body = boundedBody(r)
                if (!r.isSuccessful) return@withContext Result.failure(Exception("HTTP ${r.code}: ${body.take(300)}"))
                val liveModels = parseModels(body, provider.id).map(String::trim).filter { it.isNotEmpty() }
                val catalogModels = ModelCapabilityRegistry.catalogModels(provider.id)
                val liveAndCatalog = (liveModels.asSequence() + catalogModels.asSequence()).distinct()
                val models = (liveAndCatalog + provider.configuredModels.asSequence())
                    .distinct().take(1_000).toList()
                if (models.isEmpty()) Result.failure(Exception("Provider returned no usable model IDs and models.dev has no catalog models"))
                else Result.success(models)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(Exception("Connection failed: ${e.message}", e))
        }
    }


    private suspend fun validateBedrock(provider: AiProvider, apiKey: String): Result<List<String>> {
        val region = provider.authRegion ?: runCatching {
            java.net.URI(provider.baseUrl).host.split('.').dropWhile { it != "bedrock-runtime" && it != "bedrock-mantle" }.getOrNull(1)
        }.getOrNull() ?: return Result.failure(Exception("Bedrock region is required"))
        val url = "https://bedrock.$region.amazonaws.com/foundation-models"
        val target = NetworkSecurity.validatePublicHttpsTarget(url)
        val unsigned = Request.Builder().url(target.url).header("Accept", "application/json").get().build()
        val controlProvider = provider.copy(baseUrl = "https://bedrock.$region.amazonaws.com", authRegion = region)
        val req = ProviderRequestAuthorizer.authorize(unsigned, controlProvider, apiKey)
        return client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(req).awaitResponse().use { r ->
            val body = boundedBody(r)
            if (!r.isSuccessful) return@use Result.failure(Exception("HTTP ${r.code}: ${body.take(300)}"))
            val root = runCatching { json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject }.getOrNull()
            val summaries = root?.get("modelSummaries") as? kotlinx.serialization.json.JsonArray
            val live = summaries.orEmpty().mapNotNull { item ->
                ((item as? kotlinx.serialization.json.JsonObject)?.get("modelId") as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
            val models = (live.asSequence() + provider.configuredModels.asSequence()).distinct().take(1_000).toList()
            if (models.isEmpty()) Result.failure(Exception("Bedrock returned no models; configure explicit models/inference profiles if required"))
            else Result.success(models)
        }
    }

    private fun boundedBody(response: okhttp3.Response): String = response.body?.source()?.let { source ->
        source.request(MAX_MODEL_RESPONSE_BYTES + 1L)
        if (source.buffer.size > MAX_MODEL_RESPONSE_BYTES) throw IllegalStateException("Provider model catalog is too large")
        source.buffer.readUtf8()
    } ?: ""

    private fun parseModels(body: String, providerId: String): List<String> = runCatching {
        val jo = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        val entries = (jo["data"] as? kotlinx.serialization.json.JsonArray)
            ?: (jo["models"] as? kotlinx.serialization.json.JsonArray)
            ?: return emptyList()
        entries.mapNotNull { entry ->
            when (entry) {
                is kotlinx.serialization.json.JsonObject -> {
                    val rawId = (entry["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?: (entry["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?: return@mapNotNull null
                    val id = ProviderModelIdentity.normalizeOrNull(rawId.removePrefix("models/")) ?: return@mapNotNull null
                    ModelContextRegistry.remember(providerId, id, ModelContextRegistry.contextLimitFromModel(entry))
                    id
                }
                is kotlinx.serialization.json.JsonPrimitive -> ProviderModelIdentity.normalizeOrNull(entry.content)
                else -> null
            }
        }
    }.getOrDefault(emptyList())

    private val TOKEN_RESOLVED_SCHEMES = setOf(
        ProviderAuthScheme.GOOGLE_ADC,
        ProviderAuthScheme.AZURE_ENTRA,
        ProviderAuthScheme.GITHUB_COPILOT,
    )

     
    fun cleanupLegacyWorkspaceSecrets(workDir: File) {
        runCatching { PathSecurity.resolveWithin(workDir, ".droide/auth.json").delete() }
    }
}

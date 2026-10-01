package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class ModelsDevCatalogSnapshot(
    val providers: List<AiProvider>,
    val capabilities: List<ModelCapabilities>,
)

// models.dev catalog bridge with bounded parsing, atomic cache replacement and conditional refresh.


object ModelsDevCatalog {
    private val _revision = MutableStateFlow(0L)
     
    val revision: StateFlow<Long> = _revision.asStateFlow()

    const val ENDPOINT = "https://models.dev/api.json"
    private const val CACHE_DIR = "models-dev"
    private const val CACHE_FILE = "api.json"
    private const val META_FILE = "cache.meta"
    private const val MAX_CATALOG_BYTES = 12L * 1024L * 1024L
    private const val MAX_PROVIDERS = 1_000
    private const val MAX_MODELS = 50_000
    private const val REFRESH_AFTER_MS = 60L * 60L * 1_000L

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

     
    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        val cacheDir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
        val cache = File(cacheDir, CACHE_FILE)
        val meta = File(cacheDir, META_FILE)
        val cachedSnapshot = if (cache.isFile && cache.length() in 1..MAX_CATALOG_BYTES) {
            runCatching { parse(cache.readText()) }.getOrNull()
        } else null
        if (cachedSnapshot != null) {
            install(cachedSnapshot)
        } else if (cache.exists()) {
            cache.delete()
            meta.delete()
        }
        val cachedAt = if (cachedSnapshot != null) readMeta(meta)["fetchedAtMs"]?.toLongOrNull() ?: 0L else 0L
        if (cachedSnapshot != null && System.currentTimeMillis() - cachedAt < REFRESH_AFTER_MS) return@withContext
        refresh(cache, meta)
    }

    private suspend fun refresh(cache: File, meta: File) {
        val previousMeta = readMeta(meta)
        repeat(2) { attempt ->
            try {
                val target = NetworkSecurity.validatePublicHttpsTarget(ENDPOINT)
                val builder = Request.Builder().url(target.url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "Droide/1.00 models.dev catalog")
                previousMeta["etag"]?.takeIf(String::isNotBlank)?.let { builder.header("If-None-Match", it) }
                previousMeta["lastModified"]?.takeIf(String::isNotBlank)?.let { builder.header("If-Modified-Since", it) }
                val network = client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build()
                network.newCall(builder.get().build()).awaitResponse().use { response ->
                    if (response.code == 304 && cache.isFile) {
                        writeMeta(meta, previousMeta + ("fetchedAtMs" to System.currentTimeMillis().toString()))
                        return
                    }
                    if (!response.isSuccessful) error("models.dev HTTP ${response.code}")
                    val source = response.body?.source() ?: error("models.dev returned an empty body")
                    source.request(MAX_CATALOG_BYTES + 1L)
                    require(source.buffer.size <= MAX_CATALOG_BYTES) { "models.dev catalog exceeds size limit" }
                    val raw = source.buffer.readUtf8()
                    val parsed = parse(raw)
                    install(parsed)
                    atomicWrite(cache, raw)
                    writeMeta(meta, mapOf(
                        "fetchedAtMs" to System.currentTimeMillis().toString(),
                        "etag" to response.header("ETag").orEmpty(),
                        "lastModified" to response.header("Last-Modified").orEmpty(),
                    ))
                    return
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (attempt == 0) delay(250)
            }
        }
        // Stale cache/bootstrap remains authoritative when refresh fails.

        return
    }

    internal fun parse(raw: String): ModelsDevCatalogSnapshot {
        require(raw.length <= MAX_CATALOG_BYTES.toInt()) { "models.dev catalog exceeds character limit" }
        val root = json.parseToJsonElement(raw) as? JsonObject ?: error("models.dev catalog root must be an object")
        require(root.size in 1..MAX_PROVIDERS) { "models.dev provider count is invalid" }
        val providers = ArrayList<AiProvider>()
        val capabilities = ArrayList<ModelCapabilities>()
        var modelCount = 0
        root.forEach { (providerKey, providerElement) ->
            val provider = providerElement as? JsonObject ?: return@forEach
            val providerId = provider["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                .ifBlank { providerKey.trim() }
                .lowercase()
            if (!safeProviderId(providerId)) return@forEach
            val name = provider.string("name")?.take(160)?.ifBlank { providerId } ?: providerId
            val npm = provider.string("npm")?.take(160).orEmpty()
            val api = provider.string("api")?.take(500)
            val doc = provider.string("doc")?.take(500).orEmpty()
            val env = (provider["env"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { value -> value.matches(Regex("[A-Z0-9_]{1,100}")) }
            }.distinct().take(12)
            projectCallableProvider(providerId, name, npm, api, doc, env)?.let(providers::add)

            val models = provider["models"] as? JsonObject ?: return@forEach
            models.forEach modelLoop@{ (modelKey, modelElement) ->
                if (++modelCount > MAX_MODELS) error("models.dev model count exceeds safety limit")
                val model = modelElement as? JsonObject ?: return@modelLoop
                val modelId = model["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { modelKey.trim() }
                if (!safeModelId(modelId)) return@modelLoop
                val modalities = model["modalities"] as? JsonObject
                val limits = model["limit"] as? JsonObject
                val capability = ModelCapabilities(
                    providerId = providerId,
                    modelId = modelId,
                    displayName = model.string("name")?.take(200)?.ifBlank { modelId } ?: modelId,
                    attachment = model.bool("attachment"),
                    reasoning = model.bool("reasoning"),
                    toolCall = model.bool("tool_call"),
                    structuredOutput = model.bool("structured_output"),
                    temperature = model.bool("temperature"),
                    inputModalities = modalities.stringSet("input"),
                    outputModalities = modalities.stringSet("output"),
                    reasoningEfforts = reasoningEfforts(model["reasoning_options"]),
                    contextTokens = limits.safeTokenCount("context"),
                    outputTokens = limits.safeTokenCount("output"),
                    source = ModelCapabilitySource.MODELS_DEV,
                )
                capabilities.add(capability)
            }
        }
        return ModelsDevCatalogSnapshot(providers.distinctBy { it.id }, capabilities)
    }

    private fun install(snapshot: ModelsDevCatalogSnapshot) {
        ProviderRegistry.installDiscovered(snapshot.providers)
        ModelCapabilityRegistry.installCatalog(snapshot.capabilities)
        _revision.update { current -> if (current == Long.MAX_VALUE) 0L else current + 1L }
    }

    private fun projectCallableProvider(
        id: String,
        name: String,
        npm: String,
        api: String?,
        doc: String,
        env: List<String>,
    ): AiProvider? {
        if (ProviderRegistry.isBootstrapProvider(id)) return null
        if (npm == "@ai-sdk/openai-compatible") {
            val endpoint = api?.trim().orEmpty()
            if (!safePublicHttpsSyntax(endpoint)) return null
            return AiProvider(
                id = id,
                name = name,
                baseUrl = endpoint.trimEnd('/'),
                model = "",
                free = false,
                needsKey = env.isNotEmpty(),
                helpUrl = doc.takeIf(::safePublicHttpsSyntax).orEmpty(),
                note = "Discovered from models.dev · verified OpenAI-compatible transport.",
                wireProtocol = ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,
                authScheme = ProviderAuthScheme.BEARER,
                catalogPackage = npm,
                catalogEnv = env,
                catalogDiscovered = true,
            )
        }
        val route = catalogRoute(id, npm) ?: return null
        if (!safePublicHttpsSyntax(route.baseUrl)) return null
        return AiProvider(
            id = id,
            name = name,
            baseUrl = route.baseUrl.trimEnd('/'),
            model = "",
            free = false,
            needsKey = env.isNotEmpty(),
            helpUrl = doc.takeIf(::safePublicHttpsSyntax).orEmpty(),
            note = "Discovered from models.dev · ${route.note}",
            wireProtocol = route.protocol,
            authScheme = route.authScheme,
            catalogPackage = npm,
            catalogEnv = env,
            catalogDiscovered = true,
        )
    }

    private data class CatalogRoute(
        val baseUrl: String,
        val protocol: ProviderWireProtocol,
        val authScheme: ProviderAuthScheme,
        val note: String,
    )


    private fun catalogRoute(id: String, npm: String): CatalogRoute? {
        if (npm != "@ai-sdk/openai-compatible") {
            if (npm == "@ai-sdk/google" && id == "google") {
                return CatalogRoute("https://generativelanguage.googleapis.com/v1beta", ProviderWireProtocol.GOOGLE_GENERATE_CONTENT, ProviderAuthScheme.GOOGLE_API_KEY, "native Google GenerateContent transport.")
            }
            val verifiedOpenAiCompatibility = mapOf(
                "xai" to ("@ai-sdk/xai" to "https://api.x.ai/v1"),
                "mistral" to ("@ai-sdk/mistral" to "https://api.mistral.ai/v1"),
                "cerebras" to ("@ai-sdk/cerebras" to "https://api.cerebras.ai/v1"),
                "cohere" to ("@ai-sdk/cohere" to "https://api.cohere.ai/compatibility/v1"),
            )
            val verified = verifiedOpenAiCompatibility[id] ?: return null
            if (verified.first != npm) return null
            return CatalogRoute(verified.second, ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, ProviderAuthScheme.BEARER, "provider-native package via verified OpenAI-compatible REST transport.")
        }
        return null
    }

    private fun reasoningEfforts(element: JsonElement?): Set<String> {
        val result = linkedSetOf<String>()
        (element as? JsonArray)?.forEach { raw ->
            val option = raw as? JsonObject ?: return@forEach
            if (option.string("type") != "effort") return@forEach
            (option["values"] as? JsonArray)?.forEach { value ->
                (value as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
                    ?.takeIf { it.matches(Regex("[a-z0-9_-]{1,32}")) }
                    ?.let(result::add)
            }
        }
        return result
    }

    private fun safePublicHttpsSyntax(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null && uri.fragment == null
    }.getOrDefault(false)

    private fun safeProviderId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9._-]{1,100}"))
    private fun safeModelId(value: String): Boolean = value.length in 1..240 && value.none { it.code < 0x20 }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject?.safeTokenCount(name: String): Int? = this?.get(name)?.jsonPrimitive?.intOrNull?.takeIf { it in 1..16_000_000 }
    private fun JsonObject?.stringSet(name: String): Set<String> = ((this?.get(name) as? JsonArray).orEmpty())
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf { v -> v.matches(Regex("[a-z0-9_-]{1,32}")) } }
        .toSet()

    private fun readMeta(file: File): Map<String, String> = runCatching {
        if (!file.isFile || file.length() !in 1..16_384) return@runCatching emptyMap()
        file.readLines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun writeMeta(file: File, values: Map<String, String>) {
        atomicWrite(file, values.entries.joinToString("\n") { "${it.key}=${it.value.replace("\n", "")}" })
    }

    private fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        temp.writeText(text)
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

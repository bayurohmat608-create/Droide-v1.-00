package com.baystudio.droide.core

import java.io.File
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive







object ProviderEndpointCatalog {
    private const val MAX_CONFIG_BYTES = 256 * 1024L
    private const val MAX_CONFIGURED_PROVIDERS = 32
    private val json = Json { ignoreUnknownKeys = false }

    private val localBuiltins = listOf(
        AiProvider(
            id = "ollama",
            name = "Ollama (local)",
            baseUrl = loopbackBaseUrl(11434),
            model = "",
            free = true,
            needsKey = false,
            helpUrl = "https://ollama.com/blog/openai-compatibility",
            note = "Local OpenAI-compatible runtime. Loopback only; no credential required by default.",
            authScheme = ProviderAuthScheme.NONE,
            networkScope = ProviderNetworkScope.LOOPBACK_ONLY,
        ),
        AiProvider(
            id = "lmstudio",
            name = "LM Studio (local)",
            baseUrl = loopbackBaseUrl(1234),
            model = "",
            free = true,
            needsKey = false,
            helpUrl = "https://lmstudio.ai/docs/developer/openai-compat",
            note = "Local OpenAI-compatible runtime. Loopback only; no credential required by default.",
            authScheme = ProviderAuthScheme.NONE,
            networkScope = ProviderNetworkScope.LOOPBACK_ONLY,
        ),
        AiProvider(
            id = "vllm",
            name = "vLLM (local)",
            baseUrl = loopbackBaseUrl(8000),
            model = "",
            free = true,
            needsKey = false,
            helpUrl = "https://docs.vllm.ai/en/latest/serving/openai_compatible_server/",
            note = "Local OpenAI-compatible runtime. Loopback only; no credential required by default.",
            authScheme = ProviderAuthScheme.NONE,
            networkScope = ProviderNetworkScope.LOOPBACK_ONLY,
        ),
    )

    fun load(workDir: File) {
        val configured = parseWorkspaceConfig(runCatching {
            val file = PathSecurity.resolveWithin(workDir, ".droide/providers.json")
            if (!file.isFile) null else {
                require(file.length() <= MAX_CONFIG_BYTES) { "Provider config is too large" }
                file.readText()
            }
        }.getOrNull())
        ProviderRegistry.installConfigured(localBuiltins + configured)
    }

    internal fun parseWorkspaceConfig(raw: String?): List<AiProvider> {
        if (raw.isNullOrBlank()) return emptyList()
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BYTES) { "Provider config is too large" }
        val root = json.parseToJsonElement(raw) as? JsonObject ?: error(".droide/providers.json must be an object")
        val allowedRoot = setOf("providers")
        require(root.keys.all { it in allowedRoot }) { "Unknown provider config root field" }
        val entries = root["providers"] as? JsonArray ?: error("providers must be an array")
        require(entries.size <= MAX_CONFIGURED_PROVIDERS) { "Too many configured providers" }
        val seen = hashSetOf<String>()
        return entries.mapIndexed { index, element ->
            val obj = element as? JsonObject ?: error("providers[$index] must be an object")
            val allowed = setOf("id", "name", "baseUrl", "auth", "network", "helpUrl", "note", "models", "region")
            require(obj.keys.all { it in allowed }) { "Unknown providers[$index] field" }
            require(obj.keys.none { it.equals("apiKey", true) || it.equals("token", true) || it.equals("secret", true) }) {
                "Provider secrets must be stored in SecretStore, not .droide/providers.json"
            }
            val id = obj.string("id").trim().lowercase()
            require(id.matches(Regex("custom\\.[a-z0-9._-]{1,89}"))) { "Workspace provider id must use the custom.* namespace" }
            require(!ProviderRegistry.isBootstrapProvider(id)) { "Configured provider cannot shadow a built-in provider" }
            require(seen.add(id)) { "Duplicate configured provider id: $id" }
            val name = obj.string("name").trim().take(160)
            require(name.isNotBlank()) { "Provider name is required" }
            val baseUrl = obj.string("baseUrl").trim().trimEnd('/')
            val networkScope = when (obj.optionalString("network")?.lowercase() ?: "public-https") {
                "public-https" -> ProviderNetworkScope.PUBLIC_HTTPS
                "loopback" -> ProviderNetworkScope.LOOPBACK_ONLY
                else -> error("Unsupported provider network scope")
            }
            validateConfiguredBaseUrl(baseUrl, networkScope)
            val authScheme = when (obj.optionalString("auth")?.lowercase() ?: "bearer") {
                "none" -> ProviderAuthScheme.NONE
                "bearer" -> ProviderAuthScheme.BEARER
                "azure-api-key" -> ProviderAuthScheme.AZURE_API_KEY
                "aws-sigv4-bedrock" -> ProviderAuthScheme.AWS_SIGV4_BEDROCK
                "google-adc" -> ProviderAuthScheme.GOOGLE_ADC
                "azure-entra" -> ProviderAuthScheme.AZURE_ENTRA
                "github-copilot" -> ProviderAuthScheme.GITHUB_COPILOT
                else -> error("Unsupported provider auth scheme")
            }
            validateAuthEndpoint(baseUrl, authScheme)
            val configuredModels = (obj["models"] as? JsonArray)?.mapIndexed { modelIndex, model ->
                val modelId = (model as? JsonPrimitive)?.contentOrNull
                    ?: error("providers[$index].models[$modelIndex] must be a string")
                ProviderModelIdentity.normalize(modelId)
            }?.distinct()?.take(256) ?: emptyList()
            val region = obj.optionalString("region")?.trim()?.takeIf { it.isNotBlank() }
            require(region == null || region.matches(Regex("[a-z]{2}(?:-gov)?-[a-z]+-\\d"))) { "Invalid provider region" }
            AiProvider(
                id = id,
                name = name,
                baseUrl = baseUrl,
                model = "",
                free = networkScope == ProviderNetworkScope.LOOPBACK_ONLY,
                needsKey = authScheme != ProviderAuthScheme.NONE,
                helpUrl = obj.optionalString("helpUrl")?.takeIf(::safePublicHelpUrl).orEmpty(),
                note = obj.optionalString("note")?.trim()?.take(300)
                    ?: "Workspace-configured OpenAI-compatible provider.",
                wireProtocol = ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,
                authScheme = authScheme,
                catalogDiscovered = false,
                networkScope = networkScope,
                configuredModels = configuredModels,
                authRegion = region,
            )
        }
    }

    private fun validateConfiguredBaseUrl(raw: String, scope: ProviderNetworkScope) {
        val uri = URI(raw)
        require(uri.userInfo == null && uri.fragment == null && uri.query == null) { "Provider baseUrl cannot contain user-info, query, or fragment" }
        val host = uri.host ?: error("Provider baseUrl host is missing")
        when (scope) {
            ProviderNetworkScope.PUBLIC_HTTPS -> require(uri.scheme.equals("https", true)) { "Public provider baseUrl must use HTTPS" }
            ProviderNetworkScope.LOOPBACK_ONLY -> {
                require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "Loopback provider must use HTTP(S)" }
                require(isLoopbackHost(host)) { "Loopback provider must target localhost/127.0.0.1/::1" }
            }
        }
    }

    private fun validateAuthEndpoint(baseUrl: String, authScheme: ProviderAuthScheme) {
        if (authScheme !in ADVANCED_AUTH_SCHEMES) return
        val uri = URI(baseUrl)
        require(uri.scheme.equals("https", true)) { "Advanced cloud auth requires HTTPS" }
        val host = uri.host?.lowercase() ?: error("Advanced cloud auth host is missing")
        val valid = when (authScheme) {
            ProviderAuthScheme.AWS_SIGV4_BEDROCK ->
                Regex("""(?:bedrock|bedrock-runtime)\.[a-z]{2}(?:-gov)?-[a-z]+-\d\.amazonaws\.com""").matches(host) ||
                    Regex("""bedrock-mantle\.[a-z]{2}(?:-gov)?-[a-z]+-\d\.api\.aws""").matches(host)
            ProviderAuthScheme.GOOGLE_ADC -> host.endsWith("-aiplatform.googleapis.com")
            ProviderAuthScheme.AZURE_ENTRA -> host.endsWith(".openai.azure.com") || host.endsWith(".services.ai.azure.com") || host.endsWith(".cognitiveservices.azure.com")
            ProviderAuthScheme.GITHUB_COPILOT -> host == "api.githubcopilot.com"
            else -> true
        }
        require(valid) { "Provider endpoint is not valid for selected advanced auth family" }
    }

    private fun loopbackBaseUrl(port: Int): String =
        URI("http", null, "127.0.0.1", port, "/v1", null, null).toASCIIString()

    private fun isLoopbackHost(host: String): Boolean =
        host.equals("localhost", true) || host == "127.0.0.1" || host == "::1" || host == "[::1]" || host == "0:0:0:0:0:0:0:1"

    private fun safePublicHelpUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null && uri.fragment == null
    }.getOrDefault(false)

    private fun JsonObject.string(name: String): String =
        (this[name] as? JsonPrimitive)?.contentOrNull ?: error("$name is required")

    private fun JsonObject.optionalString(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull
    private val ADVANCED_AUTH_SCHEMES = setOf(
        ProviderAuthScheme.AWS_SIGV4_BEDROCK,
        ProviderAuthScheme.GOOGLE_ADC,
        ProviderAuthScheme.AZURE_ENTRA,
        ProviderAuthScheme.GITHUB_COPILOT,
    )

}

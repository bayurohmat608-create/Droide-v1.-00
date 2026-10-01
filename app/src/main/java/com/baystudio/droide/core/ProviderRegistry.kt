package com.baystudio.droide.core

import java.net.URI
import java.util.concurrent.atomic.AtomicReference





enum class ProviderWireProtocol { OPENAI_CHAT_COMPLETIONS, ANTHROPIC_MESSAGES, GOOGLE_GENERATE_CONTENT }

// compatibility marker: ProviderAuthScheme { NONE, BEARER, ANTHROPICXAPIKEY, GOOGLEAPIKEY, AZUREAPIKEY }
enum class ProviderAuthScheme {
    NONE,
    BEARER,
    ANTHROPIC_X_API_KEY,
    GOOGLE_API_KEY,
    AZURE_API_KEY,
    AWS_SIGV4_BEDROCK,
    GOOGLE_ADC,
    AZURE_ENTRA,
    GITHUB_COPILOT,
}

enum class ProviderNetworkScope { PUBLIC_HTTPS, LOOPBACK_ONLY }

data class AiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val free: Boolean,
    val needsKey: Boolean,
    val helpUrl: String,
    val note: String,
    val wireProtocol: ProviderWireProtocol = ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,
    val authScheme: ProviderAuthScheme = ProviderAuthScheme.BEARER,
    val catalogPackage: String? = null,
    val catalogEnv: List<String> = emptyList(),
    val catalogDiscovered: Boolean = false,
    val networkScope: ProviderNetworkScope = ProviderNetworkScope.PUBLIC_HTTPS,
    val configuredModels: List<String> = emptyList(),
    val authRegion: String? = null,
)

object ProviderRegistry {
    private val discovered = AtomicReference<List<AiProvider>>(emptyList())
    private val configured = AtomicReference<List<AiProvider>>(emptyList())
    private val managedRuntime = AtomicReference<List<AiProvider>>(emptyList())
    private val bootstrap = listOf(
        AiProvider("pollinations", "Pollinations", "https://gen.pollinations.ai/v1", "", true, true,
            "https://enter.pollinations.ai", "OpenAI-compatible API. Generation requests require a Pollinations API key."),
        AiProvider("openai", "OpenAI", "https://api.openai.com/v1", "", false, true,
            "https://platform.openai.com/api-keys", "OpenAI API."),
        AiProvider("anthropic", "Anthropic", "https://api.anthropic.com/v1", "", false, true,
            "https://console.anthropic.com/settings/keys", "Native Anthropic Messages API.",
            wireProtocol = ProviderWireProtocol.ANTHROPIC_MESSAGES,
            authScheme = ProviderAuthScheme.ANTHROPIC_X_API_KEY),
        AiProvider("groq", "Groq", "https://api.groq.com/openai/v1", "", true, true,
            "https://console.groq.com/keys", "OpenAI-compatible API."),
        AiProvider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "", true, true,
            "https://aistudio.google.com/app/apikey", "Google's OpenAI-compatible endpoint."),
        AiProvider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "", true, true,
            "https://openrouter.ai/keys", "OpenAI-compatible multi-model routing."),
        AiProvider("deepseek", "DeepSeek", "https://api.deepseek.com", "", false, true,
            "https://platform.deepseek.com/api_keys", "OpenAI-compatible chat API."),
        AiProvider("together", "Together AI", "https://api.together.xyz/v1", "", false, true,
            "https://api.together.ai/settings/api-keys", "OpenAI-compatible endpoint."),
        AiProvider("fireworks", "Fireworks AI", "https://api.fireworks.ai/inference/v1", "", false, true,
            "https://app.fireworks.ai", "OpenAI-compatible endpoint."),
        AiProvider("deepinfra", "DeepInfra", "https://api.deepinfra.com/v1/openai", "", false, true,
            "https://deepinfra.com/dash/api_keys", "OpenAI-compatible endpoint."),
        AiProvider("huggingface", "Hugging Face Inference Providers", "https://router.huggingface.co/v1", "", false, true,
            "https://huggingface.co/settings/tokens", "OpenAI-compatible chat endpoint via HF router."),
        AiProvider("nvidia", "NVIDIA API Catalog", "https://integrate.api.nvidia.com/v1", "", false, true,
            "https://build.nvidia.com", "OpenAI-compatible NVIDIA endpoint."),
    )

    val all: List<AiProvider>
        get() {
            val runtime = configured.get()
            val managed = managedRuntime.get()
            val dynamic = discovered.get()
            if (runtime.isEmpty() && managed.isEmpty() && dynamic.isEmpty()) return bootstrap
            val reserved = bootstrap.asSequence().map { it.id.lowercase() }.toMutableSet()
            val safeRuntime = runtime.asSequence().filter { reserved.add(it.id.lowercase()) }.toList()
            val safeManaged = managed.asSequence().filter { reserved.add(it.id.lowercase()) }.toList()
            val safeDynamic = dynamic.asSequence().filter { reserved.add(it.id.lowercase()) }.toList()
            return bootstrap + safeRuntime + safeManaged + safeDynamic.sortedBy { it.name.lowercase() }
        }

    internal fun installDiscovered(providers: Collection<AiProvider>) {
        val bootstrapIds = bootstrap.asSequence().map { it.id.lowercase() }.toHashSet()
        val safe = providers.asSequence()
            .map { it.copy(id = it.id.trim().lowercase()) }
            .filter {
                it.catalogDiscovered && it.id.isNotBlank() && it.id !in bootstrapIds &&
                    it.baseUrl.startsWith("https://")
            }
            .distinctBy { it.id }
            .toList()
        discovered.set(safe)
    }


    internal fun installConfigured(providers: Collection<AiProvider>) {
        val bootstrapIds = bootstrap.asSequence().map { it.id.lowercase() }.toHashSet()
        val safe = providers.asSequence()
            .map { it.copy(id = it.id.trim().lowercase()) }
            .filter { provider ->
                provider.id.matches(Regex("[a-z0-9._-]{1,96}")) && provider.id !in bootstrapIds &&
                    runCatching {
                        val uri = URI(provider.baseUrl)
                        when (provider.networkScope) {
                            ProviderNetworkScope.PUBLIC_HTTPS -> uri.scheme.equals("https", true)
                            ProviderNetworkScope.LOOPBACK_ONLY -> uri.scheme.equals("http", true) || uri.scheme.equals("https", true)
                        }
                    }.getOrDefault(false)
            }
            .distinctBy { it.id }
            .toList()
        configured.set(safe)
    }


    // They are intentionally separate from workspace configuration so a provider cannot survive after its backing process/forward has been torn down.




    internal fun installManagedRuntime(providers: Collection<AiProvider>) {
        val reserved = bootstrap.asSequence().map { it.id.lowercase() }.toHashSet()
        reserved += configured.get().map { it.id.lowercase() }
        val safe = providers.asSequence()
            .map { it.copy(id = it.id.trim().lowercase()) }
            .filter { provider ->
                provider.id.matches(Regex("local\\.[a-z0-9._-]{1,89}")) && provider.id !in reserved &&
                    provider.networkScope == ProviderNetworkScope.LOOPBACK_ONLY &&
                    provider.wireProtocol == ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS &&
                    runCatching {
                        val uri = URI(provider.baseUrl)
                        val host = uri.host.orEmpty()
                        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                            (host == "127.0.0.1" || host.equals("localhost", true) || host == "::1" || host == "[::1]") &&
                            uri.userInfo == null && uri.query == null && uri.fragment == null
                    }.getOrDefault(false)
            }
            .distinctBy { it.id }
            .toList()
        val replacing = safe.map { it.id }.toSet()
        while (true) {
            val current = managedRuntime.get()
            val next = (current.filterNot { it.id in replacing } + safe).distinctBy { it.id }
            if (managedRuntime.compareAndSet(current, next)) break
        }
    }

    internal fun removeManagedRuntime(providerId: String): Boolean {
        val id = providerId.trim().lowercase()
        while (true) {
            val current = managedRuntime.get()
            if (current.none { it.id == id }) return false
            val next = current.filterNot { it.id == id }
            if (managedRuntime.compareAndSet(current, next)) return true
        }
    }

    internal fun isBootstrapProvider(id: String): Boolean = bootstrap.any { it.id.equals(id, ignoreCase = true) }

    fun findById(id: String): AiProvider? = all.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }

    fun byId(id: String): AiProvider = findById(id) ?: bootstrap.first()
}

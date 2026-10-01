package com.baystudio.droide.core

import okhttp3.Request

// A provider catalog entry chooses a protocol/auth family; the agent loop never branches on a provider name.






data class ProviderTransportSpec(
    val protocol: ProviderWireProtocol,
    val authScheme: ProviderAuthScheme,
    val chatPath: String,
)

internal object ProviderTransportV2 {
    fun spec(provider: AiProvider): ProviderTransportSpec = when (provider.wireProtocol) {
        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> ProviderTransportSpec(
            protocol = provider.wireProtocol,
            authScheme = provider.authScheme,
            chatPath = "/chat/completions",
        )
        ProviderWireProtocol.ANTHROPIC_MESSAGES -> ProviderTransportSpec(
            protocol = provider.wireProtocol,
            authScheme = provider.authScheme,
            chatPath = "/messages",
        )
        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> ProviderTransportSpec(
            protocol = provider.wireProtocol,
            authScheme = provider.authScheme,
            chatPath = "", 
        )
    }

    fun applyAuth(builder: Request.Builder, provider: AiProvider, apiKey: String): Request.Builder {
        if (apiKey.isBlank()) return builder
        return when (provider.authScheme) {
            ProviderAuthScheme.NONE -> builder
            ProviderAuthScheme.BEARER -> builder.addHeader("Authorization", "Bearer $apiKey")
            ProviderAuthScheme.ANTHROPIC_X_API_KEY -> builder
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
            ProviderAuthScheme.GOOGLE_API_KEY -> builder
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("x-goog-api-client", "baystudio-droide/1.00")
            ProviderAuthScheme.AZURE_API_KEY -> builder.addHeader("api-key", apiKey)
            ProviderAuthScheme.AWS_SIGV4_BEDROCK,
            ProviderAuthScheme.GOOGLE_ADC,
            ProviderAuthScheme.AZURE_ENTRA,
            ProviderAuthScheme.GITHUB_COPILOT -> builder 
        }
    }
}

package com.baystudio.droide.core

 
enum class ProviderAuthMethodKind { NONE, API_KEY, CREDENTIAL_BUNDLE, DEFAULT_CHAIN, OAUTH }

data class ProviderAuthMethodDescriptor(
    val id: String,
    val label: String,
    val kind: ProviderAuthMethodKind,
    val storesSecret: Boolean,
)

object ProviderAuthMethods {
    fun forProvider(provider: AiProvider): List<ProviderAuthMethodDescriptor> = when (provider.authScheme) {
        ProviderAuthScheme.NONE -> listOf(method("none", "No authentication", ProviderAuthMethodKind.NONE, false))
        ProviderAuthScheme.AWS_SIGV4_BEDROCK -> listOf(
            method("credential-bundle", "AWS access credentials", ProviderAuthMethodKind.CREDENTIAL_BUNDLE, true),
            method("default-chain", "AWS environment / shared profile", ProviderAuthMethodKind.DEFAULT_CHAIN, false),
        )
        ProviderAuthScheme.GOOGLE_ADC -> listOf(
            method("adc-json", "Google ADC credential JSON", ProviderAuthMethodKind.CREDENTIAL_BUNDLE, true),
            method("application-default", "GOOGLE_APPLICATION_CREDENTIALS", ProviderAuthMethodKind.DEFAULT_CHAIN, false),
        )
        ProviderAuthScheme.AZURE_ENTRA -> listOf(
            method("entra-credential", "Azure Entra credential", ProviderAuthMethodKind.CREDENTIAL_BUNDLE, true),
            method("environment", "Azure environment credential", ProviderAuthMethodKind.DEFAULT_CHAIN, false),
        )
        ProviderAuthScheme.GITHUB_COPILOT -> listOf(
            method("github-token", "GitHub OAuth token", ProviderAuthMethodKind.OAUTH, true),
            method("device-oauth", "GitHub device login", ProviderAuthMethodKind.OAUTH, true),
        )
        else -> listOf(method("api-key", "API key", ProviderAuthMethodKind.API_KEY, true))
    }

    fun canResolveWithoutStoredSecret(provider: AiProvider): Boolean = when (provider.authScheme) {
        ProviderAuthScheme.NONE,
        ProviderAuthScheme.AWS_SIGV4_BEDROCK,
        ProviderAuthScheme.GOOGLE_ADC,
        ProviderAuthScheme.AZURE_ENTRA -> true
        else -> false
    }

    private fun method(id: String, label: String, kind: ProviderAuthMethodKind, storesSecret: Boolean) =
        ProviderAuthMethodDescriptor(id, label, kind, storesSecret)
}

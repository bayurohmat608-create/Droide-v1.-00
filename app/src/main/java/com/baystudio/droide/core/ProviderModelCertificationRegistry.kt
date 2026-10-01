package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicReference

// Durable connection metadata intentionally stores only non-secret summary state.








internal object ProviderModelCertificationRegistry {
    private const val MAX_MODELS = 1_000

    private data class Certification(
        val providerFingerprint: String,
        val models: Set<String>,
    )

    private val certifications = AtomicReference<Map<String, Certification>>(emptyMap())

    fun validatedModels(models: Collection<String>): List<String> {
        require(models.size <= MAX_MODELS) { "Provider returned too many model IDs" }
        val safe = LinkedHashSet<String>(models.size)
        models.forEach { raw ->
            safe += ProviderModelIdentity.normalize(raw)
        }
        require(safe.isNotEmpty()) { "Provider returned no certifiable model IDs" }
        return safe.toList()
    }

     
    fun certify(provider: AiProvider, models: Collection<String>): List<String> {
        val safe = validatedModels(models)
        val id = provider.id.trim().lowercase()
        val certification = Certification(providerFingerprint(provider), safe.toSet())
        while (true) {
            val current = certifications.get()
            val next = current.toMutableMap().apply { put(id, certification) }.toMap()
            if (certifications.compareAndSet(current, next)) return safe
        }
    }

    fun revoke(providerId: String): Boolean {
        val id = providerId.trim().lowercase()
        while (true) {
            val current = certifications.get()
            if (id !in current) return false
            val next = current.toMutableMap().apply { remove(id) }.toMap()
            if (certifications.compareAndSet(current, next)) return true
        }
    }

    fun isCertified(provider: AiProvider, modelId: String): Boolean {
        val certification = certifications.get()[provider.id.trim().lowercase()] ?: return false
        if (certification.providerFingerprint != providerFingerprint(provider)) return false
        val model = ProviderModelIdentity.normalizeOrNull(modelId) ?: return false
        if (model != modelId) return false
        return model in certification.models
    }

    fun isCertified(providerId: String, modelId: String): Boolean {
        val provider = ProviderRegistry.findById(providerId) ?: return false
        return isCertified(provider, modelId)
    }

    fun requireCertified(provider: AiProvider, modelId: String) {
        require(isCertified(provider, modelId)) {
            "Provider/model is not live-certified in this process; refresh the provider and select a validated model"
        }
    }

    fun requireCertified(providerId: String, modelId: String): AiProvider {
        val provider = ProviderRegistry.findById(providerId)
            ?: throw IllegalArgumentException("Unknown provider: $providerId")
        requireCertified(provider, modelId)
        return provider
    }

    private fun providerFingerprint(provider: AiProvider): String = listOf(
        provider.id.trim().lowercase(),
        provider.baseUrl.trim(),
        provider.wireProtocol.name,
        provider.authScheme.name,
        provider.networkScope.name,
        provider.authRegion.orEmpty(),
    ).joinToString("\u0000")
}

// compatibility marker: MAXMODELIDCHARS = 240

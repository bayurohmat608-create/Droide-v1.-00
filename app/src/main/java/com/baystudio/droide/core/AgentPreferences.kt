package com.baystudio.droide.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

 
val Context.agentPreferences by preferencesDataStore(name = "agent_cfg")

object AgentPreferences {
    private val PROVIDER = stringPreferencesKey("provider_id")
    private val BASE_URL = stringPreferencesKey("base_url") 
    private val MODEL = stringPreferencesKey("model")
    private val REASONING = stringPreferencesKey("reasoning_effort")

    data class Snapshot(val providerId: String, val baseUrl: String, val model: String, val reasoningEffort: ReasoningEffort?)

    fun observe(context: Context): Flow<Snapshot> = context.agentPreferences.data.map(::snapshot)

    suspend fun load(context: Context): Snapshot = observe(context).first()

    suspend fun saveProvider(context: Context, providerId: String, model: String) {
        val preset = ProviderRegistry.findById(providerId) ?: error("Unknown provider: $providerId")
        val selectedModel = ProviderModelIdentity.normalize(model)
        ProviderModelCertificationRegistry.requireCertified(preset, selectedModel)
        context.agentPreferences.edit { p ->
            p[PROVIDER] = preset.id
            // Endpoint is always derived from the curated registry, never persisted from UI/input.
            p.remove(BASE_URL)
            p[MODEL] = selectedModel
            val stored = p[REASONING]?.let { runCatching { ReasoningEffort.valueOf(it) }.getOrNull() }
            if (stored != null && ReasoningSupport.wire(preset.id, selectedModel, stored) == null) p.remove(REASONING)
        }
    }

    suspend fun saveModel(context: Context, model: String) {
        val selectedModel = ProviderModelIdentity.normalize(model)
        context.agentPreferences.edit { p ->
            val provider = ProviderRegistry.findById(p[PROVIDER] ?: "pollinations")
                ?: error("Stored provider is no longer available")
            ProviderModelCertificationRegistry.requireCertified(provider, selectedModel)
            p[MODEL] = selectedModel
            val stored = p[REASONING]?.let { runCatching { ReasoningEffort.valueOf(it) }.getOrNull() }
            if (stored != null && ReasoningSupport.wire(provider.id, selectedModel, stored) == null) p.remove(REASONING)
        }
    }

    suspend fun saveReasoningEffort(context: Context, effort: ReasoningEffort?) {
        context.agentPreferences.edit { p ->
            if (effort == null) {
                p.remove(REASONING)
            } else {
                val provider = ProviderRegistry.byId(p[PROVIDER] ?: "pollinations")
                val selectedModel = p[MODEL]?.takeIf { it.isNotBlank() } ?: provider.model
                require(ReasoningSupport.wire(provider.id, selectedModel, effort) != null) {
                    "${effort.label} reasoning is not supported by ${provider.name} / $selectedModel"
                }
                p[REASONING] = effort.name
            }
        }
    }

    private fun snapshot(p: androidx.datastore.preferences.core.Preferences): Snapshot {
        val requested = p[PROVIDER] ?: "pollinations"
        val preset = ProviderRegistry.findById(requested) ?: ProviderRegistry.byId("pollinations")
        val useStored = preset.id == requested
        val selectedModel = if (useStored) ProviderModelIdentity.normalizeOrNull(p[MODEL]) ?: ProviderModelIdentity.normalizeOrNull(preset.model).orEmpty() else ProviderModelIdentity.normalizeOrNull(preset.model).orEmpty()
        val storedReasoning = p[REASONING]?.let { runCatching { ReasoningEffort.valueOf(it) }.getOrNull() }
        val reasoning = storedReasoning?.takeIf { ReasoningSupport.wire(preset.id, selectedModel, it) != null }
            ?: ReasoningSupport.defaultFor(preset.id, selectedModel)
        return Snapshot(
            providerId = preset.id,
            baseUrl = preset.baseUrl,
            model = selectedModel,
            reasoningEffort = reasoning,
        )
    }
}

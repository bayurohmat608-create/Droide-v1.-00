package com.baystudio.droide.core

import android.content.Context

// Credentials never live here; SecretStore owns secret material.


internal class ProviderConnectionStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(providerId: String): ProviderConnectionRecord? {
        val id = normalizeProviderId(providerId)
        val method = prefs.getString(methodKey(id), null)?.takeIf { METHOD_ID.matches(it) } ?: return null
        val validated = prefs.getLong(validatedKey(id), 0L).takeIf { it > 0L } ?: return null
        val models = prefs.getInt(modelCountKey(id), 0).coerceIn(0, MAX_MODELS)
        return ProviderConnectionRecord(id, method, validated, models)
    }

    fun put(record: ProviderConnectionRecord): Boolean {
        val id = normalizeProviderId(record.providerId)
        require(METHOD_ID.matches(record.authMethodId)) { "Invalid provider auth method id" }
        require(record.validatedAtEpochSeconds > 0L) { "Invalid provider validation timestamp" }
        require(record.modelCount in 0..MAX_MODELS) { "Invalid provider model count" }
        return prefs.edit()
            .putString(methodKey(id), record.authMethodId)
            .putLong(validatedKey(id), record.validatedAtEpochSeconds)
            .putInt(modelCountKey(id), record.modelCount)
            .commit()
    }

    fun remove(providerId: String): Boolean {
        val id = normalizeProviderId(providerId)
        return prefs.edit()
            .remove(methodKey(id))
            .remove(validatedKey(id))
            .remove(modelCountKey(id))
            .commit()
    }

    fun providerIds(): Set<String> = prefs.all.keys.asSequence()
        .filter { it.startsWith(METHOD_PREFIX) }
        .map { it.removePrefix(METHOD_PREFIX) }
        .filter { PROVIDER_ID.matches(it) }
        .toSet()

    private fun normalizeProviderId(raw: String): String = raw.trim().lowercase().also {
        require(PROVIDER_ID.matches(it)) { "Invalid provider id" }
    }

    private fun methodKey(id: String) = METHOD_PREFIX + id
    private fun validatedKey(id: String) = VALIDATED_PREFIX + id
    private fun modelCountKey(id: String) = MODEL_COUNT_PREFIX + id

    companion object {
        private const val PREFS = "droide_provider_connections_v1"
        private const val METHOD_PREFIX = "method."
        private const val VALIDATED_PREFIX = "validated."
        private const val MODEL_COUNT_PREFIX = "models."
        private const val MAX_MODELS = 1_000
        private val PROVIDER_ID = Regex("[a-z0-9._-]{1,96}")
        private val METHOD_ID = Regex("[a-z0-9._-]{1,64}")
    }
}

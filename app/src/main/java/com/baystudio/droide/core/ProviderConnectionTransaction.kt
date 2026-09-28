package com.baystudio.droide.core

 
internal data class ProviderConnectionRecord(
    val providerId: String,
    val authMethodId: String,
    val validatedAtEpochSeconds: Long,
    val modelCount: Int,
)

internal class ProviderConnectionTransaction(
    private val readSecret: (String) -> String,
    private val writeSecret: (String, String) -> Boolean,
    private val removeSecret: (String) -> Boolean,
    private val readState: (String) -> ProviderConnectionRecord?,
    private val writeState: (ProviderConnectionRecord) -> Boolean,
    private val removeState: (String) -> Boolean,
) {
    fun commit(record: ProviderConnectionRecord, persistSecret: Boolean, candidateSecret: String) {
        val oldSecret = readSecret(record.providerId)
        val oldState = readState(record.providerId)
        val secretOk = if (persistSecret) writeSecret(record.providerId, candidateSecret)
            else removeSecret(record.providerId)
        check(secretOk) { "Could not durably persist provider credential" }
        if (writeState(record)) return
        restoreSecret(record.providerId, oldSecret)
        val restored = if (oldState != null) writeState(oldState) else removeState(record.providerId)
        check(restored) { "Provider connection metadata rollback failed" }
        error("Could not durably persist provider connection metadata")
    }

    fun disconnect(providerId: String) {
        val oldSecret = readSecret(providerId)
        val oldState = readState(providerId)
        check(removeSecret(providerId)) { "Could not durably remove provider credential" }
        if (removeState(providerId)) return
        restoreSecret(providerId, oldSecret)
        oldState?.let { check(writeState(it)) { "Provider connection metadata rollback failed" } }
        error("Could not durably remove provider connection metadata")
    }

    private fun restoreSecret(providerId: String, oldSecret: String) {
        val restored = if (oldSecret.isBlank()) removeSecret(providerId) else writeSecret(providerId, oldSecret)
        check(restored) { "Provider connection credential rollback failed" }
    }
}

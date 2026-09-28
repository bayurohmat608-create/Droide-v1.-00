package com.baystudio.droide.core

 
internal class GitHubAccountTransaction(
    private val readCredential: () -> String,
    private val writeCredential: (String) -> Boolean,
    private val removeCredential: () -> Boolean,
    private val readState: () -> GitHubAccountRecord?,
    private val writeState: (GitHubAccountRecord) -> Boolean,
    private val removeState: () -> Boolean,
) {
    fun commit(record: GitHubAccountRecord, credential: String) {
        require(credential.isNotBlank())
        val oldCredential = readCredential()
        val oldState = readState()
        check(writeCredential(credential)) { "Could not durably persist GitHub credential" }
        if (writeState(record)) return
        restoreCredential(oldCredential)
        val stateRestored = if (oldState != null) writeState(oldState) else removeState()
        check(stateRestored) { "GitHub account metadata rollback failed" }
        error("Could not durably persist GitHub account metadata")
    }

    data class RotationResult(val metadataCommitted: Boolean)

    






    fun rotate(record: GitHubAccountRecord, credential: String): RotationResult {
        require(credential.isNotBlank())
        check(writeCredential(credential)) { "Could not durably persist rotated GitHub credential" }
        return RotationResult(metadataCommitted = writeState(record))
    }

    data class RevokedCleanupResult(
        val credentialRemoved: Boolean,
        val metadataRemoved: Boolean,
    ) {
        val complete: Boolean get() = credentialRemoved && metadataRemoved
    }

    




    fun disconnectRevoked(): RevokedCleanupResult {
        val metadataRemoved = removeState()
        val credentialRemoved = removeCredential()
        return RevokedCleanupResult(
            credentialRemoved = credentialRemoved,
            metadataRemoved = metadataRemoved,
        )
    }

    fun disconnect() {
        val oldCredential = readCredential()
        val oldState = readState()
        check(removeCredential()) { "Could not durably remove GitHub credential" }
        if (removeState()) return
        restoreCredential(oldCredential)
        oldState?.let { check(writeState(it)) { "GitHub account metadata rollback failed" } }
        error("Could not durably remove GitHub account metadata")
    }

    private fun restoreCredential(old: String) {
        val restored = if (old.isBlank()) removeCredential() else writeCredential(old)
        check(restored) { "GitHub account credential rollback failed" }
    }
}

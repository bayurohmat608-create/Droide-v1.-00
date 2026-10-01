package com.baystudio.droide.core


internal class GitHubAccountCredentialLifecycleAuthority(
    private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    enum class Freshness {
        USABLE,
        REFRESHABLE,
        REAUTH_REQUIRED,
    }

    data class RefreshTicket internal constructor(
        val generation: Long,
        val credentialFingerprint: String,
        internal val fence: OAuthAttemptCommitFence,
    )

    private val lock = Any()
    private var generation = 1L
    private var activeRefresh: RefreshTicket? = null

    fun freshness(
        accessExpiresAtEpochSeconds: Long?,
        refreshTokenPresent: Boolean,
        refreshExpiresAtEpochSeconds: Long?,
    ): Freshness {
        val now = epochSeconds()
        if (accessExpiresAtEpochSeconds == null) return Freshness.USABLE
        if (accessExpiresAtEpochSeconds > now + REFRESH_EARLY_SECONDS) return Freshness.USABLE

        val refreshUsable = refreshTokenPresent &&
            (refreshExpiresAtEpochSeconds == null || refreshExpiresAtEpochSeconds > now)
        if (refreshUsable) return Freshness.REFRESHABLE

        // Do not force re-authorization early just because a refresh token is unavailable.

        return if (accessExpiresAtEpochSeconds > now) Freshness.USABLE else Freshness.REAUTH_REQUIRED
    }

    fun beginRefresh(credentialFingerprint: String): RefreshTicket = synchronized(lock) {
        require(credentialFingerprint.matches(FINGERPRINT)) { "Invalid GitHub credential fingerprint" }
        activeRefresh?.fence?.cancel()
        RefreshTicket(generation, credentialFingerprint, OAuthAttemptCommitFence()).also { activeRefresh = it }
    }

    fun canContinue(ticket: RefreshTicket): Boolean = synchronized(lock) {
        activeRefresh === ticket && ticket.generation == generation && ticket.fence.isActive()
    }

    // Claim ownership exactly once immediately before persisting a rotated credential pair.
    fun tryClaimRefreshCommit(ticket: RefreshTicket): Boolean = synchronized(lock) {
        if (activeRefresh !== ticket || ticket.generation != generation) return@synchronized false
        val claimed = ticket.fence.tryClaimCommit()
        if (claimed) {
            activeRefresh = null
            generation++
        }
        claimed
    }

     
    fun invalidate(): Long = synchronized(lock) {
        activeRefresh?.fence?.cancel()
        activeRefresh = null
        generation++
        generation
    }

    internal fun generationForTest(): Long = synchronized(lock) { generation }

    companion object {
         
        const val REFRESH_EARLY_SECONDS = 5L * 60L
        private val FINGERPRINT = Regex("[a-f0-9]{64}")
    }
}

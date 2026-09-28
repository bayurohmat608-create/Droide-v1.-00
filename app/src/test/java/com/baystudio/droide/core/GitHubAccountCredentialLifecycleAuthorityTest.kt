package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubAccountCredentialLifecycleAuthorityTest {
    @Test fun cachesHealthyAccessTokenUntilRefreshWindow() {
        val authority = GitHubAccountCredentialLifecycleAuthority(epochSeconds = { 1_000L })
        assertEquals(
            GitHubAccountCredentialLifecycleAuthority.Freshness.USABLE,
            authority.freshness(1_301L, refreshTokenPresent = true, refreshExpiresAtEpochSeconds = 10_000L),
        )
        assertEquals(
            GitHubAccountCredentialLifecycleAuthority.Freshness.REFRESHABLE,
            authority.freshness(1_300L, refreshTokenPresent = true, refreshExpiresAtEpochSeconds = 10_000L),
        )
    }

    @Test fun accessTokenWithoutRefreshRemainsUsableUntilActualExpiry() {
        var now = 1_000L
        val authority = GitHubAccountCredentialLifecycleAuthority(epochSeconds = { now })
        assertEquals(
            GitHubAccountCredentialLifecycleAuthority.Freshness.USABLE,
            authority.freshness(1_050L, refreshTokenPresent = false, refreshExpiresAtEpochSeconds = null),
        )
        now = 1_050L
        assertEquals(
            GitHubAccountCredentialLifecycleAuthority.Freshness.REAUTH_REQUIRED,
            authority.freshness(1_050L, refreshTokenPresent = false, refreshExpiresAtEpochSeconds = null),
        )
    }

    @Test fun refreshTicketIsOneShotAndInvalidatedByLifecycleChange() {
        val authority = GitHubAccountCredentialLifecycleAuthority(epochSeconds = { 1_000L })
        val first = authority.beginRefresh("a".repeat(64))
        assertTrue(authority.canContinue(first))
        authority.invalidate()
        assertFalse(authority.canContinue(first))
        assertFalse(authority.tryClaimRefreshCommit(first))

        val second = authority.beginRefresh("b".repeat(64))
        assertTrue(authority.tryClaimRefreshCommit(second))
        assertFalse(authority.tryClaimRefreshCommit(second))
    }

    @Test fun expiredRefreshCannotRescueExpiredAccess() {
        val authority = GitHubAccountCredentialLifecycleAuthority(epochSeconds = { 1_000L })
        assertEquals(
            GitHubAccountCredentialLifecycleAuthority.Freshness.REAUTH_REQUIRED,
            authority.freshness(999L, refreshTokenPresent = true, refreshExpiresAtEpochSeconds = 1_000L),
        )
    }
}

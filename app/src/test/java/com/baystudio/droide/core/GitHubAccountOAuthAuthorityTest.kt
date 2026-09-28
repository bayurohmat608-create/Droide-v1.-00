package com.baystudio.droide.core

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubAccountOAuthAuthorityTest {
    @Test fun authorizationUsesS256AndNoVerifierLeak() {
        val authority = GitHubAccountOAuthAuthority(epochSeconds = { 1_000L })
        val auth = authority.begin("Iv1.droideclient123", GitHubAccountOAuthAuthority.DEFAULT_REDIRECT_URI)
        val params = query(auth.authorizationUri)
        assertEquals("S256", params["code_challenge_method"])
        assertEquals("select_account", params["prompt"])
        assertEquals(auth.pending.state, params["state"])
        assertEquals(GitHubAccountOAuthAuthority.DEFAULT_REDIRECT_URI, params["redirect_uri"])
        assertFalse(auth.authorizationUri.contains(auth.pending.codeVerifier))
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(auth.pending.codeVerifier.toByteArray(StandardCharsets.US_ASCII))
        )
        assertEquals(expectedChallenge, params["code_challenge"])
    }

    @Test fun callbackIsOneShotAndExactAttemptOwnsCommit() {
        val authority = GitHubAccountOAuthAuthority(epochSeconds = { 1_000L })
        val auth = authority.begin("Iv1.droideclient123", GitHubAccountOAuthAuthority.DEFAULT_REDIRECT_URI)
        val callback = "${GitHubAccountOAuthAuthority.DEFAULT_REDIRECT_URI}?code=abc123&state=${auth.pending.state}"
        val accepted = authority.claimCallback(callback) as GitHubAccountOAuthAuthority.CallbackResult.Accepted
        assertEquals(auth.attemptId, accepted.ticket.attemptId)
        assertTrue(authority.claimCallback(callback) is GitHubAccountOAuthAuthority.CallbackResult.Rejected)
        assertTrue(authority.tryClaimCommit(auth.attemptId))
        assertFalse(authority.tryClaimCommit(auth.attemptId))
    }

    @Test fun replacementTombstonesLateCommit() {
        val authority = GitHubAccountOAuthAuthority(epochSeconds = { 1_000L })
        val first = authority.begin("Iv1.droideclient123", GitHubAccountOAuthAuthority.DEFAULT_REDIRECT_URI)
        val accepted = authority.claimCallback(
            "${GitHubAccountOAuthAuthority.DEFAULT_REDIRECT_URI}?code=first&state=${first.pending.state}"
        ) as GitHubAccountOAuthAuthority.CallbackResult.Accepted
        val second = authority.begin("Iv1.droideclient123", GitHubAccountOAuthAuthority.DEFAULT_REDIRECT_URI)
        assertFalse(authority.tryClaimCommit(accepted.ticket.attemptId))
        assertEquals(second.attemptId, authority.pendingRecord()?.attemptId)
    }

    @Test fun stateMismatchTombstonesAttempt() {
        val authority = GitHubAccountOAuthAuthority(epochSeconds = { 1_000L })
        authority.begin("Iv1.droideclient123", GitHubAccountOAuthAuthority.DEFAULT_REDIRECT_URI)
        val result = authority.claimCallback("${GitHubAccountOAuthAuthority.DEFAULT_REDIRECT_URI}?code=abc&state=wrong")
        assertTrue(result is GitHubAccountOAuthAuthority.CallbackResult.Rejected)
        assertEquals(null, authority.pendingRecord())
    }

    @Test fun durablePendingCanRestoreAfterProcessRecreation() {
        var now = 1_000L
        val first = GitHubAccountOAuthAuthority(epochSeconds = { now })
        val auth = first.begin("Iv1.droideclient123", GitHubAccountOAuthAuthority.DEFAULT_REDIRECT_URI)
        val restored = GitHubAccountOAuthAuthority(epochSeconds = { now })
        assertTrue(restored.restore(auth.pending))
        val result = restored.claimCallback(
            "${GitHubAccountOAuthAuthority.DEFAULT_REDIRECT_URI}?code=restored&state=${auth.pending.state}"
        )
        assertTrue(result is GitHubAccountOAuthAuthority.CallbackResult.Accepted)
        now += 601L
        val expired = GitHubAccountOAuthAuthority(epochSeconds = { now })
        assertFalse(expired.restore(auth.pending))
    }

    @Test fun callbackRouteMustMatchExactly() {
        val authority = GitHubAccountOAuthAuthority(epochSeconds = { 1_000L })
        val auth = authority.begin("Iv1.droideclient123", GitHubAccountOAuthAuthority.DEFAULT_REDIRECT_URI)
        val wrong = "${GitHubAccountOAuthAuthority.CALLBACK_SCHEME}://evil/callback?code=abc&state=${auth.pending.state}"
        assertTrue(authority.claimCallback(wrong) is GitHubAccountOAuthAuthority.CallbackResult.NotForDroide)
        assertEquals(auth.attemptId, authority.pendingRecord()?.attemptId)
    }

    @Test fun verifiedHttpsCallbackCanBePinnedExactly() {
        val authority = GitHubAccountOAuthAuthority(epochSeconds = { 1_000L })
        val redirect = "https://auth.baystudio.example/droide/github/callback"
        val auth = authority.begin("Iv1.droideclient123", redirect)
        assertEquals(redirect, query(auth.authorizationUri)["redirect_uri"])
        assertTrue(authority.isCallbackRoute("$redirect?code=abc&state=${auth.pending.state}", redirect))
        assertFalse(authority.isCallbackRoute("https://evil.example/droide/github/callback?code=abc&state=${auth.pending.state}", redirect))
        val accepted = authority.claimCallback("$redirect?code=abc&state=${auth.pending.state}")
        assertTrue(accepted is GitHubAccountOAuthAuthority.CallbackResult.Accepted)
    }

    @Test fun redirectRejectsHttpAndCredentialBearingUrls() {
        val authority = GitHubAccountOAuthAuthority(epochSeconds = { 1_000L })
        assertFalse(runCatching { authority.begin("Iv1.droideclient123", "http://auth.example/callback") }.isSuccess)
        assertFalse(runCatching { authority.begin("Iv1.droideclient123", "https://user@auth.example/callback") }.isSuccess)
        assertFalse(runCatching { authority.begin("Iv1.droideclient123", "https://auth.example/callback?next=evil") }.isSuccess)
    }

    private fun query(raw: String): Map<String, String> {
        val uri = URI(raw)
        return uri.rawQuery.split('&').associate { part ->
            val (k, v) = part.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }
}

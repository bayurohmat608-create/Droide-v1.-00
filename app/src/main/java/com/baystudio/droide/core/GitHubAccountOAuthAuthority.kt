package com.baystudio.droide.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean


internal class GitHubAccountOAuthAuthority(
    private val random: SecureRandom = SecureRandom(),
    private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    data class PendingRecord(
        val attemptId: String,
        val state: String,
        val codeVerifier: String,
        val redirectUri: String,
        val createdAtEpochSeconds: Long,
        val expiresAtEpochSeconds: Long,
    )

    data class Authorization(
        val attemptId: String,
        val authorizationUri: String,
        val pending: PendingRecord,
    )

    data class ExchangeTicket(
        val attemptId: String,
        val code: String,
        val codeVerifier: String,
        val redirectUri: String,
    )

    sealed interface CallbackResult {
        data object NotForDroide : CallbackResult
        data class Accepted(val ticket: ExchangeTicket) : CallbackResult
        data class Rejected(val reason: String) : CallbackResult
    }

    private data class Pending(
        val record: PendingRecord,
        val callbackClaimed: AtomicBoolean = AtomicBoolean(false),
        val commitFence: OAuthAttemptCommitFence = OAuthAttemptCommitFence(),
    )

    private val lock = Any()
    @Volatile private var current: Pending? = null

    fun begin(clientId: String, redirectUri: String): Authorization {
        require(CLIENT_ID.matches(clientId)) { "Invalid GitHub account client id" }
        val redirect = validateRedirectUri(redirectUri)
        val now = epochSeconds()
        val state = randomBase64Url(32)
        val verifier = randomBase64Url(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
        check(challenge.length == 43) { "Invalid PKCE S256 challenge length" }
        val attemptId = randomBase64Url(24)
        val record = PendingRecord(
            attemptId = attemptId,
            state = state,
            codeVerifier = verifier,
            redirectUri = redirect.toASCIIString(),
            createdAtEpochSeconds = now,
            expiresAtEpochSeconds = now + AUTHORIZATION_TTL_SECONDS,
        )
        synchronized(lock) {
            current?.commitFence?.cancel()
            current = Pending(record)
        }
        val params = linkedMapOf(
            "client_id" to clientId,
            "redirect_uri" to record.redirectUri,
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "prompt" to "select_account",
        )
        val query = params.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return Authorization(attemptId, "$AUTHORIZATION_ENDPOINT?$query", record)
    }

     
    fun restore(record: PendingRecord): Boolean {
        return runCatching {
            validateRecord(record)
            val now = epochSeconds()
            require(record.expiresAtEpochSeconds > now) { "OAuth attempt expired" }
            require(record.createdAtEpochSeconds <= now + CLOCK_SKEW_SECONDS) { "OAuth attempt timestamp is in the future" }
            require(record.expiresAtEpochSeconds - record.createdAtEpochSeconds in 1..AUTHORIZATION_TTL_SECONDS) {
                "OAuth attempt lifetime is invalid"
            }
            synchronized(lock) {
                current?.commitFence?.cancel()
                current = Pending(record)
            }
            true
        }.getOrDefault(false)
    }

    fun pendingRecord(): PendingRecord? = synchronized(lock) {
        val pending = current ?: return@synchronized null
        if (isExpired(pending.record)) {
            pending.commitFence.cancel()
            current = null
            null
        } else pending.record
    }

    fun claimCallback(rawUri: String): CallbackResult {
        if (rawUri.length !in 1..MAX_CALLBACK_URI_CHARS) return CallbackResult.Rejected("oauth_callback_invalid")
        val callback = runCatching { URI(rawUri) }.getOrNull() ?: return CallbackResult.Rejected("oauth_callback_invalid")
        val pending = synchronized(lock) { current } ?: return if (isDroideCallbackRoute(callback)) {
            CallbackResult.Rejected("oauth_attempt_not_found")
        } else CallbackResult.NotForDroide
        val expected = runCatching { URI(pending.record.redirectUri) }.getOrNull()
            ?: return rejectCurrent(pending, "oauth_redirect_configuration_invalid")
        if (!sameCallbackRoute(expected, callback)) {
            return if (isDroideCallbackRoute(callback)) rejectCurrent(pending, "oauth_callback_route_mismatch")
            else CallbackResult.NotForDroide
        }
        if (callback.rawFragment != null || callback.rawUserInfo != null) {
            return rejectCurrent(pending, "oauth_callback_invalid")
        }
        if (isExpired(pending.record)) return rejectCurrent(pending, "oauth_attempt_expired")
        val params = runCatching { parseQuery(callback.rawQuery.orEmpty()) }
            .getOrElse { return rejectCurrent(pending, "oauth_callback_invalid") }
        val state = params["state"] ?: return rejectCurrent(pending, "oauth_state_missing")
        if (!constantTimeEquals(pending.record.state, state)) return rejectCurrent(pending, "oauth_state_mismatch")
        val error = params["error"]?.takeIf(String::isNotBlank)
        if (error != null) {
            val detail = params["error_description"]?.take(160)?.replace(Regex("[\\r\\n\\u0000]"), " ")
            return rejectCurrent(pending, if (detail.isNullOrBlank()) "github_$error" else "github_$error: $detail")
        }
        val code = params["code"]?.trim().orEmpty()
        if (code.length !in 1..MAX_AUTHORIZATION_CODE_CHARS || code.any { it == '\r' || it == '\n' || it == '\u0000' }) {
            return rejectCurrent(pending, "oauth_code_invalid")
        }
        if (!pending.callbackClaimed.compareAndSet(false, true)) return CallbackResult.Rejected("oauth_callback_replayed")
        return CallbackResult.Accepted(
            ExchangeTicket(
                attemptId = pending.record.attemptId,
                code = code,
                codeVerifier = pending.record.codeVerifier,
                redirectUri = pending.record.redirectUri,
            )
        )
    }

    // Re-check exact attempt ownership after waiting behind another account network operation.
    fun isExchangeActive(attemptId: String): Boolean = synchronized(lock) {
        val pending = current ?: return@synchronized false
        pending.record.attemptId == attemptId &&
            pending.callbackClaimed.get() &&
            pending.commitFence.isActive() &&
            !isExpired(pending.record)
    }

    // Claim exact attempt ownership immediately before durable credential persistence.
    fun tryClaimCommit(attemptId: String): Boolean = synchronized(lock) {
        val pending = current ?: return@synchronized false
        if (pending.record.attemptId != attemptId || !pending.callbackClaimed.get() || isExpired(pending.record)) return@synchronized false
        val claimed = pending.commitFence.tryClaimCommit()
        if (claimed) current = null
        claimed
    }

    // A replacement attempt is never cancelled by stale I/O.
    fun fail(attemptId: String): Boolean = synchronized(lock) {
        val pending = current ?: return@synchronized false
        if (pending.record.attemptId != attemptId) return@synchronized false
        val cancelled = pending.commitFence.cancel()
        current = null
        cancelled
    }

    fun cancelCurrent(): Boolean = synchronized(lock) {
        val pending = current ?: return@synchronized false
        val cancelled = pending.commitFence.cancel()
        current = null
        cancelled
    }

    fun isCallbackRoute(rawUri: String, configuredRedirectUri: String): Boolean = runCatching {
        sameCallbackRoute(validateRedirectUri(configuredRedirectUri), URI(rawUri))
    }.getOrDefault(false)

    private fun rejectCurrent(pending: Pending, reason: String): CallbackResult.Rejected {
        synchronized(lock) {
            if (current === pending) {
                pending.commitFence.cancel()
                current = null
            }
        }
        return CallbackResult.Rejected(reason)
    }

    private fun validateRecord(record: PendingRecord) {
        require(ATTEMPT_ID.matches(record.attemptId)) { "Invalid OAuth attempt id" }
        require(STATE.matches(record.state)) { "Invalid OAuth state" }
        require(PKCE_VERIFIER.matches(record.codeVerifier)) { "Invalid PKCE verifier" }
        validateRedirectUri(record.redirectUri)
        require(record.createdAtEpochSeconds > 0L && record.expiresAtEpochSeconds > record.createdAtEpochSeconds)
    }

    private fun validateRedirectUri(raw: String): URI {
        require(raw.length in 1..MAX_REDIRECT_URI_CHARS) { "Invalid GitHub redirect URI" }
        val uri = URI(raw)
        require(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null) {
            "GitHub redirect URI must not contain query, fragment, or user info"
        }
        val nativeCallback = raw == DEFAULT_REDIRECT_URI
        val verifiedWebCandidate = uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.port in listOf(-1, 443) &&
            !uri.rawPath.isNullOrBlank() && uri.rawPath.startsWith("/")
        require(nativeCallback || verifiedWebCandidate) {
            "GitHub redirect URI must be the Droide native callback or a clean HTTPS App Link"
        }
        return uri
    }

    private fun isDroideCallbackRoute(uri: URI): Boolean =
        uri.scheme.equals(CALLBACK_SCHEME, true) && uri.host.equals(CALLBACK_HOST, true) && uri.rawPath == CALLBACK_PATH && uri.port == -1

    private fun sameCallbackRoute(expected: URI, actual: URI): Boolean =
        expected.scheme.equals(actual.scheme, true) &&
            expected.host.equals(actual.host, true) &&
            expected.port == actual.port &&
            expected.rawPath == actual.rawPath

    private fun parseQuery(raw: String): Map<String, String> {
        require(raw.length <= MAX_CALLBACK_URI_CHARS)
        if (raw.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        raw.split('&').forEach { pair ->
            require(pair.isNotEmpty())
            val split = pair.indexOf('=')
            val key = decode(if (split >= 0) pair.substring(0, split) else pair)
            val value = decode(if (split >= 0) pair.substring(split + 1) else "")
            require(key.length in 1..128 && value.length <= 2_048)
            require(result.putIfAbsent(key, value) == null) { "Duplicate OAuth callback parameter" }
        }
        return result
    }

    private fun isExpired(record: PendingRecord): Boolean = epochSeconds() >= record.expiresAtEpochSeconds

    private fun randomBase64Url(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(::base64Url)

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun constantTimeEquals(expected: String, actual: String): Boolean = MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.UTF_8), actual.toByteArray(StandardCharsets.UTF_8),
    )

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    companion object {
        const val CALLBACK_SCHEME = "com.baystudio.droide.oauth"
        const val CALLBACK_HOST = "github"
        const val CALLBACK_PATH = "/callback"
        const val DEFAULT_REDIRECT_URI = "$CALLBACK_SCHEME://$CALLBACK_HOST$CALLBACK_PATH"
        private const val AUTHORIZATION_ENDPOINT = "https://github.com/login/oauth/authorize"
        private const val AUTHORIZATION_TTL_SECONDS = 10L * 60L
        private const val CLOCK_SKEW_SECONDS = 60L
        private const val MAX_REDIRECT_URI_CHARS = 512
        private const val MAX_CALLBACK_URI_CHARS = 4_096
        private const val MAX_AUTHORIZATION_CODE_CHARS = 1_024
        private val CLIENT_ID = Regex("[A-Za-z0-9._-]{8,128}")
        private val ATTEMPT_ID = Regex("[A-Za-z0-9_-]{24,64}")
        private val STATE = Regex("[A-Za-z0-9_-]{32,128}")
        private val PKCE_VERIFIER = Regex("[A-Za-z0-9._~-]{43,128}")
    }
}

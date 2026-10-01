package com.baystudio.droide.core

import android.content.Context
import com.baystudio.droide.BuildConfig
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

 
class GitHubAccountManager(context: Context) {
    enum class Status {
        UNAVAILABLE,
        DISCONNECTED,
        WAITING_BROWSER,
        COMPLETING,
        REFRESHING,
        DISCONNECTING,
        CONNECTED,
        REAUTH_REQUIRED,
    }

    data class Snapshot(
        val status: Status,
        val login: String? = null,
        val userId: Long? = null,
        val avatarUrl: String? = null,
        val detail: String? = null,
    )

    data class DisconnectOutcome(
        val remoteRevoked: Boolean,
    )

    private data class RefreshWork(
        val rawCredential: String,
        val credential: GitHubAccountCredential,
        val record: GitHubAccountRecord,
        val ticket: GitHubAccountCredentialLifecycleAuthority.RefreshTicket,
    )

    private sealed interface TokenResolution {
        data class Ready(val token: String) : TokenResolution
        data object ReauthRequired : TokenResolution
        data object Missing : TokenResolution
        data class Refresh(val work: RefreshWork) : TokenResolution
    }

    private val appContext = context.applicationContext
    private val lifecycleLock = Any()
    // Serializes exchange, refresh and revoke so remote credential mutations cannot cross.
    private val credentialNetworkMutex = Mutex()
    private val authority = GitHubAccountOAuthAuthority()
    private val credentialLifecycle = GitHubAccountCredentialLifecycleAuthority()
    private val secrets = GitHubAccountSecretStore(appContext)
    private val states = GitHubAccountStateStore(appContext)
    private val oauthClient = GitHubAccountOAuthClient()
    private val transaction = GitHubAccountTransaction(
        readCredential = { secrets.get(GitHubAccountSecretStore.SLOT_CREDENTIAL) },
        writeCredential = { secrets.putDurable(GitHubAccountSecretStore.SLOT_CREDENTIAL, it) },
        removeCredential = { secrets.removeDurable(GitHubAccountSecretStore.SLOT_CREDENTIAL) },
        readState = states::get,
        writeState = states::put,
        removeState = states::remove,
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val clientId = BuildConfig.GITHUB_ACCOUNT_CLIENT_ID.trim()
    private val exchangeUrl = BuildConfig.GITHUB_ACCOUNT_EXCHANGE_URL.trim()
    private val redirectUri = BuildConfig.GITHUB_ACCOUNT_REDIRECT_URI.trim()
    private val configured = CLIENT_ID.matches(clientId) && isSecureExchangeUrl(exchangeUrl) &&
        authority.isCallbackRoute(redirectUri, redirectUri)
    private var disconnecting = false
     
    private var knownRemoteRevoked = false
    private val _snapshot = MutableStateFlow(initialSnapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    




    fun beginAuthorization(): Result<String> = synchronized(lifecycleLock) {
        if (!configured) {
            _snapshot.value = unavailableSnapshot()
            return@synchronized Result.failure(IllegalStateException("GitHub account login is not configured in this build"))
        }
        if (disconnecting) {
            return@synchronized Result.failure(IllegalStateException("GitHub disconnect is still revoking the previous authorization"))
        }
        runCatching {
            val authorization = authority.begin(clientId, redirectUri)
            val encoded = encodePending(authorization.pending)
            if (!secrets.putDurable(GitHubAccountSecretStore.SLOT_PENDING_OAUTH, encoded)) {
                authority.cancelCurrent()
                error("Could not safely save GitHub sign-in state")
            }
            val previous = durableSnapshot()
            _snapshot.value = Snapshot(
                status = Status.WAITING_BROWSER,
                login = previous.login,
                userId = previous.userId,
                avatarUrl = previous.avatarUrl,
                detail = "Finish signing in with GitHub in your browser",
            )
            authorization.authorizationUri
        }.onFailure { failure ->
            authority.cancelCurrent()
            secrets.removeDurable(GitHubAccountSecretStore.SLOT_PENDING_OAUTH)
            _snapshot.value = durableSnapshot("GitHub sign-in could not start: ${safeMessage(failure)}")
        }
    }

     
    fun browserLaunchFailed(reason: String) = synchronized(lifecycleLock) {
        authority.cancelCurrent()
        secrets.removeDurable(GitHubAccountSecretStore.SLOT_PENDING_OAUTH)
        _snapshot.value = durableSnapshot(reason.take(200))
    }

    // Callback exchange is serialized with refresh/revoke; after waiting for that lane, exact attempt ownership is checked again before any remote issue.



    suspend fun handleCallback(rawUri: String): Boolean {
        if (!authority.isCallbackRoute(rawUri, redirectUri)) return false
        val callback = synchronized(lifecycleLock) {
            if (!configured) {
                secrets.removeDurable(GitHubAccountSecretStore.SLOT_PENDING_OAUTH)
                _snapshot.value = unavailableSnapshot()
                return@synchronized GitHubAccountOAuthAuthority.CallbackResult.Rejected("oauth_not_configured")
            }
            if (disconnecting) {
                authority.cancelCurrent()
                secrets.removeDurable(GitHubAccountSecretStore.SLOT_PENDING_OAUTH)
                return@synchronized GitHubAccountOAuthAuthority.CallbackResult.Rejected("oauth_disconnect_in_progress")
            }
            restorePendingIfNeeded()
            when (val result = authority.claimCallback(rawUri)) {
                is GitHubAccountOAuthAuthority.CallbackResult.Accepted -> {
                    // A process crash during exchange must require a fresh user authorization rather than resurrecting and replaying a callback that has already been claimed.

                    secrets.removeDurable(GitHubAccountSecretStore.SLOT_PENDING_OAUTH)
                    val previous = durableSnapshot()
                    _snapshot.value = Snapshot(
                        status = Status.COMPLETING,
                        login = previous.login,
                        userId = previous.userId,
                        avatarUrl = previous.avatarUrl,
                        detail = "Validating GitHub account",
                    )
                    result
                }
                is GitHubAccountOAuthAuthority.CallbackResult.Rejected -> {
                    secrets.removeDurable(GitHubAccountSecretStore.SLOT_PENDING_OAUTH)
                    _snapshot.value = durableSnapshot(humanizeOAuthError(result.reason))
                    result
                }
                GitHubAccountOAuthAuthority.CallbackResult.NotForDroide -> result
            }
        }
        val accepted = callback as? GitHubAccountOAuthAuthority.CallbackResult.Accepted ?: return true
        val ticket = accepted.ticket
        try {
            credentialNetworkMutex.withLock {
                val mayExchange = synchronized(lifecycleLock) {
                    !disconnecting && authority.isExchangeActive(ticket.attemptId)
                }
                if (!mayExchange) return@withLock

                val credential = oauthClient.exchange(
                    exchangeUrl = exchangeUrl,
                    clientId = clientId,
                    code = ticket.code,
                    codeVerifier = ticket.codeVerifier,
                    redirectUri = ticket.redirectUri,
                )
                val identity = oauthClient.authenticatedUser(credential.accessToken)
                val record = GitHubAccountRecord(
                    login = identity.login,
                    userId = identity.userId,
                    avatarUrl = identity.avatarUrl,
                    connectedAtEpochSeconds = Instant.now().epochSecond,
                    tokenExpiresAtEpochSeconds = credential.expiresAtEpochSeconds,
                )
                synchronized(lifecycleLock) {
                    // Keep this exact-attempt fence immediately before durable persistence.
                    if (!authority.tryClaimCommit(ticket.attemptId)) return@withLock
                    transaction.commit(record, encodeCredential(credential))
                    knownRemoteRevoked = false
                    credentialLifecycle.invalidate()
                    _snapshot.value = connectedSnapshot(record)
                }
            }
        } catch (cancelled: CancellationException) {
            synchronized(lifecycleLock) {
                if (authority.fail(ticket.attemptId)) {
                    _snapshot.value = durableSnapshot("GitHub sign-in was cancelled")
                }
            }
            throw cancelled
        } catch (failure: Throwable) {
            synchronized(lifecycleLock) {
                if (authority.fail(ticket.attemptId)) {
                    _snapshot.value = durableSnapshot("GitHub sign-in failed: ${safeMessage(failure)}")
                }
            }
        }
        return true
    }

    





    suspend fun disconnect(): Result<DisconnectOutcome> = credentialNetworkMutex.withLock {
        if (!configured) {
            return@withLock runCatching {
                synchronized(lifecycleLock) {
                    transaction.disconnect()
                    knownRemoteRevoked = false
                    _snapshot.value = disconnectedSnapshot("Local GitHub credential removed; remote revoke is unavailable in this build")
                }
                DisconnectOutcome(remoteRevoked = false)
            }.onFailure { failure ->
                synchronized(lifecycleLock) {
                    _snapshot.value = durableSnapshot("Could not remove local GitHub credential: ${safeMessage(failure)}")
                }
            }
        }

        val token = try {
            resolveAccessTokenUnderNetworkMutex()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            synchronized(lifecycleLock) {
                _snapshot.value = durableSnapshot("Could not prepare GitHub authorization for revocation: ${safeMessage(failure)}")
            }
            return@withLock Result.failure(failure)
        }

        synchronized(lifecycleLock) {
            disconnecting = true
            authority.cancelCurrent()
            credentialLifecycle.invalidate()
            secrets.removeDurable(GitHubAccountSecretStore.SLOT_PENDING_OAUTH)
            val previous = durableSnapshot()
            _snapshot.value = Snapshot(
                Status.DISCONNECTING,
                previous.login,
                previous.userId,
                previous.avatarUrl,
                if (token != null) "Revoking Droide's GitHub authorization" else "Removing expired GitHub credential",
            )
        }

        var remoteRevoked = false
        try {
            

            if (token != null) {
                oauthClient.revokeAuthorization(exchangeUrl, clientId, token)
                remoteRevoked = true
                synchronized(lifecycleLock) { knownRemoteRevoked = true }
            }

            val cleanup = synchronized(lifecycleLock) {
                transaction.disconnectRevoked().also {
                    disconnecting = false
                    _snapshot.value = disconnectedSnapshot(
                        when {
                            it.complete -> null
                            remoteRevoked -> "GitHub authorization was revoked; local cleanup is incomplete and will be retried"
                            else -> "Expired GitHub credential is unusable; local cleanup is incomplete and will be retried"
                        }
                    )
                }
            }
            if (!cleanup.complete) {
                return@withLock Result.failure(IllegalStateException(
                    if (remoteRevoked) "GitHub was revoked remotely, but local credential cleanup is incomplete"
                    else "Expired GitHub credential cleanup is incomplete"
                ))
            }
            synchronized(lifecycleLock) { knownRemoteRevoked = false }
            Result.success(DisconnectOutcome(remoteRevoked = remoteRevoked))
        } catch (cancelled: CancellationException) {
            synchronized(lifecycleLock) {
                disconnecting = false
                _snapshot.value = if (remoteRevoked) {
                    disconnectedSnapshot("GitHub was revoked remotely; local cleanup was interrupted")
                } else {
                    durableSnapshot("GitHub disconnect was cancelled before remote revocation completed")
                }
            }
            throw cancelled
        } catch (failure: Throwable) {
            synchronized(lifecycleLock) {
                disconnecting = false
                _snapshot.value = if (remoteRevoked) {
                    disconnectedSnapshot("GitHub was revoked remotely; local cleanup needs retry: ${safeMessage(failure)}")
                } else {
                    durableSnapshot("Remote GitHub revocation failed: ${safeMessage(failure)}")
                }
            }
            Result.failure(failure)
        }
    }

    fun isConfigured(): Boolean = configured

     
    fun repositoryAccessUrl(): String? {
        val slug = BuildConfig.GITHUB_APP_SLUG.trim()
        return slug.takeIf { GITHUB_APP_SLUG.matches(it) }
            ?.let { "https://github.com/apps/$it/installations/new" }
    }

    




    internal suspend fun currentGitTransportCredential(): GitHubTransportCredential? = credentialNetworkMutex.withLock {
        try {
            val token = resolveAccessTokenUnderNetworkMutex() ?: return@withLock null
            val record = synchronized(lifecycleLock) { states.get() } ?: return@withLock null
            GitHubTransportCredential(GITHUB_APP_GIT_USERNAME, token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            synchronized(lifecycleLock) {
                _snapshot.value = durableSnapshot("GitHub session refresh failed: ${safeMessage(failure)}")
            }
            null
        }
    }

    // API-only callers that do not need the Git username still share the same lifecycle authority.
    internal suspend fun currentAccessToken(): String? = currentGitTransportCredential()?.accessToken

    // Must be called only while the referenced API is held.
    private suspend fun resolveAccessTokenUnderNetworkMutex(): String? {
        val resolution = synchronized(lifecycleLock) {
            if (knownRemoteRevoked) return@synchronized TokenResolution.Missing
            val record = states.get() ?: return@synchronized TokenResolution.Missing
            val raw = secrets.get(GitHubAccountSecretStore.SLOT_CREDENTIAL)
            val credential = decodeCredential(raw) ?: return@synchronized TokenResolution.Missing
            when (credentialLifecycle.freshness(
                credential.expiresAtEpochSeconds,
                !credential.refreshToken.isNullOrBlank(),
                credential.refreshTokenExpiresAtEpochSeconds,
            )) {
                GitHubAccountCredentialLifecycleAuthority.Freshness.USABLE -> TokenResolution.Ready(credential.accessToken)
                GitHubAccountCredentialLifecycleAuthority.Freshness.REAUTH_REQUIRED -> {
                    _snapshot.value = Snapshot(
                        Status.REAUTH_REQUIRED,
                        record?.login,
                        record?.userId,
                        record?.avatarUrl,
                        "GitHub session expired; connect again",
                    )
                    TokenResolution.ReauthRequired
                }
                GitHubAccountCredentialLifecycleAuthority.Freshness.REFRESHABLE -> {
                    val ticket = credentialLifecycle.beginRefresh(credentialFingerprint(raw))
                    _snapshot.value = Snapshot(
                        Status.REFRESHING,
                        record?.login,
                        record?.userId,
                        record?.avatarUrl,
                        "Refreshing GitHub session",
                    )
                    TokenResolution.Refresh(RefreshWork(raw, credential, record, ticket))
                }
            }
        }

        when (resolution) {
            is TokenResolution.Ready -> return resolution.token
            TokenResolution.ReauthRequired, TokenResolution.Missing -> return null
            is TokenResolution.Refresh -> Unit
        }
        val work = (resolution as TokenResolution.Refresh).work
        val refreshToken = work.credential.refreshToken ?: return null

        val refreshed = try {
            oauthClient.refresh(exchangeUrl, clientId, refreshToken)
        } catch (failure: GitHubAccountRemoteException) {
            if (failure.code == "bad_refresh_token") {
                synchronized(lifecycleLock) {
                    credentialLifecycle.invalidate()
                    _snapshot.value = Snapshot(
                        Status.REAUTH_REQUIRED,
                        work.record.login,
                        work.record.userId,
                        work.record.avatarUrl,
                        "GitHub refresh token is no longer valid; connect again",
                    )
                }
                return null
            }
            throw failure
        }
        val identity = oauthClient.authenticatedUser(refreshed.accessToken)

        if (identity.userId != work.record.userId) {
            credentialLifecycle.invalidate()
            runCatching { oauthClient.revokeAuthorization(exchangeUrl, clientId, refreshed.accessToken) }
            synchronized(lifecycleLock) {
                transaction.disconnectRevoked()
                _snapshot.value = disconnectedSnapshot("GitHub identity changed during token refresh; connect again")
            }
            return null
        }

        val record = GitHubAccountRecord(
            login = identity.login,
            userId = identity.userId,
            avatarUrl = identity.avatarUrl,
            connectedAtEpochSeconds = work.record.connectedAtEpochSeconds,
            tokenExpiresAtEpochSeconds = refreshed.expiresAtEpochSeconds,
        )

        var persistenceFailure: Throwable? = null
        val committed = synchronized(lifecycleLock) {
            val currentRaw = secrets.get(GitHubAccountSecretStore.SLOT_CREDENTIAL)
            if (!credentialLifecycle.canContinue(work.ticket) ||
                credentialFingerprint(currentRaw) != work.ticket.credentialFingerprint
            ) return@synchronized false
            if (!credentialLifecycle.tryClaimRefreshCommit(work.ticket)) return@synchronized false
            try {
                val rotation = transaction.rotate(record, encodeCredential(refreshed))
                knownRemoteRevoked = false
                _snapshot.value = connectedSnapshot(
                    record,
                    if (rotation.metadataCommitted) "GitHub session refreshed" else "GitHub session refreshed; account metadata will be repaired on next validation",
                )
                true
            } catch (failure: Throwable) {
                persistenceFailure = failure
                _snapshot.value = Snapshot(
                    Status.REAUTH_REQUIRED,
                    work.record.login,
                    work.record.userId,
                    work.record.avatarUrl,
                    "Rotated GitHub credential could not be saved; connect again",
                )
                false
            }
        }

        if (persistenceFailure != null) {
            

            runCatching { oauthClient.revokeAuthorization(exchangeUrl, clientId, refreshed.accessToken) }
            synchronized(lifecycleLock) { runCatching { transaction.disconnectRevoked() } }
            val failure = checkNotNull(persistenceFailure)
            throw failure
        }
        if (!committed) {
            // Never persist or expose this stale rotated token.

            runCatching { oauthClient.revokeAuthorization(exchangeUrl, clientId, refreshed.accessToken) }
            return synchronized(lifecycleLock) {
                val latest = decodeCredential(secrets.get(GitHubAccountSecretStore.SLOT_CREDENTIAL))
                latest?.takeIf {
                    credentialLifecycle.freshness(it.expiresAtEpochSeconds, !it.refreshToken.isNullOrBlank(), it.refreshTokenExpiresAtEpochSeconds) ==
                        GitHubAccountCredentialLifecycleAuthority.Freshness.USABLE
                }?.accessToken
            }
        }
        return refreshed.accessToken
    }

    private fun initialSnapshot(): Snapshot = synchronized(lifecycleLock) {
        if (!configured) return@synchronized unavailableSnapshot()
        val restored = decodePending(secrets.get(GitHubAccountSecretStore.SLOT_PENDING_OAUTH))
        if (restored != null && authority.restore(restored)) {
            val previous = durableSnapshot()
            return@synchronized Snapshot(
                Status.WAITING_BROWSER,
                previous.login,
                previous.userId,
                previous.avatarUrl,
                "Finish signing in with GitHub in your browser",
            )
        }
        secrets.removeDurable(GitHubAccountSecretStore.SLOT_PENDING_OAUTH)
        durableSnapshot()
    }

    private fun restorePendingIfNeeded() {
        if (authority.pendingRecord() != null) return
        val restored = decodePending(secrets.get(GitHubAccountSecretStore.SLOT_PENDING_OAUTH))
        if (restored == null || !authority.restore(restored)) {
            secrets.removeDurable(GitHubAccountSecretStore.SLOT_PENDING_OAUTH)
        }
    }

    private fun durableSnapshot(detail: String? = null): Snapshot {
        val record = states.get() ?: return disconnectedSnapshot(detail)
        val credential = decodeCredential(secrets.get(GitHubAccountSecretStore.SLOT_CREDENTIAL))
            ?: return disconnectedSnapshot(detail ?: "Stored GitHub credential is unavailable")
        return when (credentialLifecycle.freshness(
            credential.expiresAtEpochSeconds,
            !credential.refreshToken.isNullOrBlank(),
            credential.refreshTokenExpiresAtEpochSeconds,
        )) {
            GitHubAccountCredentialLifecycleAuthority.Freshness.REAUTH_REQUIRED -> Snapshot(
                Status.REAUTH_REQUIRED,
                record.login,
                record.userId,
                record.avatarUrl,
                detail ?: "GitHub session expired; connect again",
            )
            GitHubAccountCredentialLifecycleAuthority.Freshness.REFRESHABLE -> connectedSnapshot(
                record,
                detail ?: "GitHub session will refresh automatically when needed",
            )
            GitHubAccountCredentialLifecycleAuthority.Freshness.USABLE -> connectedSnapshot(record, detail)
        }
    }

    private fun connectedSnapshot(record: GitHubAccountRecord, detail: String? = null): Snapshot = Snapshot(
        status = Status.CONNECTED,
        login = record.login,
        userId = record.userId,
        avatarUrl = record.avatarUrl,
        detail = detail,
    )

    private fun disconnectedSnapshot(detail: String? = null): Snapshot = Snapshot(Status.DISCONNECTED, detail = detail)

    private fun unavailableSnapshot(): Snapshot = Snapshot(
        Status.UNAVAILABLE,
        detail = "GitHub sign-in needs Droide's first-party client ID and HTTPS token-exchange relay",
    )

    private fun encodeCredential(value: GitHubAccountCredential): String = buildJsonObject {
        put("access_token", value.accessToken)
        put("token_type", value.tokenType)
        value.scope?.let { put("scope", it) }
        value.expiresAtEpochSeconds?.let { put("expires_at", it) }
        value.refreshToken?.let { put("refresh_token", it) }
        value.refreshTokenExpiresAtEpochSeconds?.let { put("refresh_token_expires_at", it) }
    }.toString()

    private fun decodeCredential(raw: String): GitHubAccountCredential? = runCatching {
        if (raw.isBlank() || raw.length > 64 * 1024) return@runCatching null
        val obj = json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching null
        val token = obj.string("access_token")
        if (token.length !in 20..4_096 || token.any(Char::isWhitespace)) return@runCatching null
        val refresh = obj.optionalString("refresh_token")
        if (refresh != null && (refresh.length !in 20..4_096 || refresh.any(Char::isWhitespace))) return@runCatching null
        GitHubAccountCredential(
            accessToken = token,
            tokenType = obj.optionalString("token_type") ?: "bearer",
            scope = obj.optionalString("scope"),
            expiresAtEpochSeconds = obj.optionalLong("expires_at"),
            refreshToken = refresh,
            refreshTokenExpiresAtEpochSeconds = obj.optionalLong("refresh_token_expires_at"),
        )
    }.getOrNull()

    private fun encodePending(value: GitHubAccountOAuthAuthority.PendingRecord): String = buildJsonObject {
        put("attempt_id", value.attemptId)
        put("state", value.state)
        put("code_verifier", value.codeVerifier)
        put("redirect_uri", value.redirectUri)
        put("created_at", value.createdAtEpochSeconds)
        put("expires_at", value.expiresAtEpochSeconds)
    }.toString()

    private fun decodePending(raw: String): GitHubAccountOAuthAuthority.PendingRecord? = runCatching {
        if (raw.isBlank() || raw.length > 16 * 1024) return@runCatching null
        val obj = json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching null
        GitHubAccountOAuthAuthority.PendingRecord(
            attemptId = obj.string("attempt_id"),
            state = obj.string("state"),
            codeVerifier = obj.string("code_verifier"),
            redirectUri = obj.string("redirect_uri"),
            createdAtEpochSeconds = obj.long("created_at"),
            expiresAtEpochSeconds = obj.long("expires_at"),
        )
    }.getOrNull()

    private fun credentialFingerprint(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun humanizeOAuthError(reason: String): String = when {
        reason.startsWith("github_access_denied") -> "GitHub sign-in was cancelled"
        reason == "oauth_attempt_expired" -> "GitHub sign-in expired; try again"
        reason == "oauth_state_mismatch" -> "GitHub sign-in was rejected because the security state did not match"
        reason == "oauth_callback_replayed" -> "This GitHub callback was already used"
        reason == "oauth_attempt_not_found" -> "GitHub sign-in session is no longer active"
        reason == "oauth_disconnect_in_progress" -> "GitHub disconnect is still in progress"
        reason == "oauth_not_configured" -> "GitHub sign-in is not configured in this build"
        else -> "GitHub sign-in failed"
    }

    private fun safeMessage(failure: Throwable): String = (failure.message ?: failure::class.java.simpleName)
        .take(180).replace(Regex("[\\r\\n\\u0000]"), " ")

    private fun isSecureExchangeUrl(raw: String): Boolean = runCatching {
        val uri = java.net.URI(raw)
        uri.scheme.equals("https", true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawPath == GitHubAccountOAuthClient.RELAY_EXCHANGE_PATH
    }.getOrDefault(false)

    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull
        ?: error("Missing GitHub account field: $key")
    private fun JsonObject.optionalString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long = optionalLong(key) ?: error("Missing GitHub account field: $key")
    private fun JsonObject.optionalLong(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    companion object {
        private val GITHUB_APP_SLUG = Regex("[a-z0-9](?:[a-z0-9-]{0,98}[a-z0-9])?")
        private const val GITHUB_APP_GIT_USERNAME = "x-access-token"
        private val CLIENT_ID = Regex("[A-Za-z0-9._-]{8,128}")
    }
}

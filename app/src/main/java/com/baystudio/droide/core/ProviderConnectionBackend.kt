package com.baystudio.droide.core

import android.content.Context
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


class ProviderConnectionBackend(context: Context) {
    enum class Status { DISCONNECTED, CONNECTED }

    data class Snapshot(
        val providerId: String,
        val status: Status,
        val authMethodId: String? = null,
        val validatedAtEpochSeconds: Long? = null,
        val modelCount: Int = 0,
    )

    data class Connected(
        val snapshot: Snapshot,
        val models: List<String>,
    )

    data class OAuthChallenge(
        val sessionId: String,
        val providerId: String,
        val verificationUri: String,
        val userCode: String,
        val expiresInSeconds: Long,
        val pollIntervalSeconds: Long,
    )

    sealed interface OAuthPoll {
        data class Pending(val retryAfterSeconds: Long) : OAuthPoll
        data class Success(val connected: Connected) : OAuthPoll
        data class Failed(val reason: String) : OAuthPoll
    }

    private data class PendingOAuth(
        val sessionId: String,
        val providerId: String,
        val clientId: String,
        val device: GitHubDeviceOAuth.DeviceCode,
        val createdAtNanos: Long,
        val expiresAtNanos: Long,
        var intervalSeconds: Long,
        var nextPollAtNanos: Long,
        val lock: Mutex = Mutex(),
        val commitFence: OAuthAttemptCommitFence = OAuthAttemptCommitFence(),
    )

    private val appContext = context.applicationContext
    private val secrets = SecretStore(appContext)
    private val states = ProviderConnectionStateStore(appContext)
    private val providerLocks = ConcurrentHashMap<String, Mutex>()
    private val oauthSessions = ConcurrentHashMap<String, PendingOAuth>()
    private val random = SecureRandom()
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()
    private val transaction = ProviderConnectionTransaction(
        readSecret = secrets::get,
        writeSecret = secrets::putDurable,
        removeSecret = secrets::removeDurable,
        readState = states::get,
        writeState = states::put,
        removeState = states::remove,
    )

    init {
        ProviderRequestAuthorizer.installCredentialUpdater { providerId, secret ->
            secrets.putDurable(providerId, secret)
        }
    }

    fun authMethods(providerId: String): List<ProviderAuthMethodDescriptor> =
        ProviderAuthManager.authMethods(provider(providerId))

    fun snapshot(providerId: String): Snapshot {
        val id = normalizeProviderId(providerId)
        val record = states.get(id) ?: return Snapshot(id, Status.DISCONNECTED)
        val provider = ProviderRegistry.findById(id) ?: return Snapshot(id, Status.DISCONNECTED)
        val selected = ProviderAuthManager.authMethods(provider).firstOrNull { it.id == record.authMethodId }
            ?: return Snapshot(id, Status.DISCONNECTED)
        if (selected.storesSecret && secrets.get(id).isBlank()) return Snapshot(id, Status.DISCONNECTED)
        return Snapshot(id, Status.CONNECTED, record.authMethodId, record.validatedAtEpochSeconds, record.modelCount)
    }

    fun snapshots(): List<Snapshot> = states.providerIds().sorted().map(::snapshot)

    suspend fun connect(providerId: String, authMethodId: String, credentialMaterial: String = ""): Result<Connected> {
        val provider = runCatching { provider(providerId) }.getOrElse { return Result.failure(it) }
        // This tombstone must win even if an old poll is in flight.

        invalidateOAuthSessionsForProvider(provider.id)
        return lockFor(provider.id).withLock {
            connectLocked(provider, authMethodId, credentialMaterial)
        }
    }

    // Revalidates the durable selected method without changing credential identity on failure.
    suspend fun revalidate(providerId: String): Result<Connected> {
        val provider = runCatching { provider(providerId) }.getOrElse { return Result.failure(it) }
        return lockFor(provider.id).withLock {
            val current = states.get(provider.id)
                ?: return@withLock Result.failure(IllegalStateException("Provider is not connected"))
            val method = method(provider, current.authMethodId)
            val secret = if (method.storesSecret) secrets.get(provider.id) else ""
            if (method.storesSecret && secret.isBlank()) {
                return@withLock Result.failure(IllegalStateException("Stored provider credential is missing"))
            }
            val models = validateOnly(provider, method, secret).getOrElse { failure ->
                if (ProviderModelCertificationRegistry.revoke(provider.id)) bumpRevision()
                return@withLock Result.failure(failure)
            }
            val certifiedModels = runCatching { ProviderModelCertificationRegistry.validatedModels(models) }.getOrElse { failure ->
                if (ProviderModelCertificationRegistry.revoke(provider.id)) bumpRevision()
                return@withLock Result.failure(failure)
            }
            runCatching {
                val refreshed = current.copy(
                    validatedAtEpochSeconds = Instant.now().epochSecond,
                    modelCount = certifiedModels.size,
                )
                if (!states.put(refreshed)) error("Could not persist provider validation state")
                ProviderModelCertificationRegistry.certify(provider, certifiedModels)
                bumpRevision()
                Connected(snapshot(provider.id), certifiedModels)
            }
        }
    }

    suspend fun disconnect(providerId: String): Result<Unit> {
        val id = normalizeProviderId(providerId)
        

        invalidateOAuthSessionsForProvider(id)
        return lockFor(id).withLock {
            runCatching {
                invalidateOAuthSessionsForProvider(id)
                transaction.disconnect(id)
                ProviderModelCertificationRegistry.revoke(id)
                ProviderRequestAuthorizer.invalidateProvider(id)
                bumpRevision()
            }
        }
    }

     
    suspend fun beginGitHubDeviceOAuth(
        providerId: String,
        clientId: String,
        scope: String = "read:user",
    ): Result<OAuthChallenge> {
        val provider = runCatching { provider(providerId) }.getOrElse { return Result.failure(it) }
        if (provider.authScheme != ProviderAuthScheme.GITHUB_COPILOT) {
            return Result.failure(IllegalArgumentException("Provider does not use GitHub OAuth"))
        }
        method(provider, "device-oauth")
        // Tombstone them before the mutex so an in-flight poll cannot win merely by holding it first.

        invalidateOAuthSessionsForProvider(provider.id)
        return lockFor(provider.id).withLock {
            runCatching {
                invalidateOAuthSessionsForProvider(provider.id)
                if (oauthSessions.size >= MAX_OAUTH_SESSIONS) pruneExpiredOAuthSessions()
                require(oauthSessions.size < MAX_OAUTH_SESSIONS) { "Too many pending OAuth sessions" }
                val device = GitHubDeviceOAuth.start(clientId, scope)
                val now = System.nanoTime()
                val interval = device.intervalSeconds.coerceIn(5, 60)
                val sessionId = randomId()
                val pending = PendingOAuth(
                    sessionId = sessionId,
                    providerId = provider.id,
                    clientId = clientId,
                    device = device,
                    createdAtNanos = now,
                    expiresAtNanos = now + device.expiresInSeconds.coerceIn(60, 1800) * NANOS_PER_SECOND,
                    intervalSeconds = interval,
                    nextPollAtNanos = now + interval * NANOS_PER_SECOND,
                )
                oauthSessions[sessionId] = pending
                OAuthChallenge(
                    sessionId, provider.id, device.verificationUri, device.userCode,
                    device.expiresInSeconds, interval,
                )
            }
        }
    }

    suspend fun pollGitHubDeviceOAuth(sessionId: String): OAuthPoll {
        val normalized = requireSessionId(sessionId)
        val pending = oauthSessions[normalized] ?: return OAuthPoll.Failed("oauth_session_not_found")
        return pending.lock.withLock {
            val current = oauthSessions[normalized] ?: return@withLock OAuthPoll.Failed("oauth_session_not_found")
            val now = System.nanoTime()
            if (now >= current.expiresAtNanos) {
                invalidateOAuthSession(normalized, current)
                return@withLock OAuthPoll.Failed("oauth_session_expired")
            }
            if (!current.commitFence.isActive() || oauthSessions[normalized] !== current) {
                return@withLock OAuthPoll.Failed("oauth_session_cancelled")
            }
            if (now < current.nextPollAtNanos) {
                return@withLock OAuthPoll.Pending(secondsUntil(current.nextPollAtNanos, now))
            }
            
            current.nextPollAtNanos = now + current.intervalSeconds * NANOS_PER_SECOND
            when (val result = try {
                GitHubDeviceOAuth.pollOnce(current.clientId, current.device.deviceCode, current.intervalSeconds)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                invalidateOAuthSession(normalized, current)
                return@withLock OAuthPoll.Failed(t.message ?: "oauth_poll_failed")
            }) {
                is GitHubDeviceOAuth.PollResult.Pending -> {
                    if (!current.commitFence.isActive() || oauthSessions[normalized] !== current) {
                        return@withLock OAuthPoll.Failed("oauth_session_cancelled")
                    }
                    current.intervalSeconds = result.nextIntervalSeconds.coerceIn(5, 60)
                    current.nextPollAtNanos = System.nanoTime() + current.intervalSeconds * NANOS_PER_SECOND
                    OAuthPoll.Pending(current.intervalSeconds)
                }
                is GitHubDeviceOAuth.PollResult.Failed -> {
                    invalidateOAuthSession(normalized, current)
                    OAuthPoll.Failed(result.reason.take(160))
                }
                is GitHubDeviceOAuth.PollResult.Success -> {
                    val credential = githubCredentialBundle(current.clientId, result)
                    val connected = lockFor(current.providerId).withLock {
                        connectLocked(provider(current.providerId), "device-oauth", credential) {
                            current.commitFence.tryClaimCommit() && oauthSessions.remove(normalized, current)
                        }
                    }
                    oauthSessions.remove(normalized, current)
                    connected.fold(
                        onSuccess = { OAuthPoll.Success(it) },
                        onFailure = { OAuthPoll.Failed(it.message ?: "provider_validation_failed") },
                    )
                }
            }
        }
    }

    fun cancelOAuth(sessionId: String): Boolean {
        val normalized = requireSessionId(sessionId)
        val current = oauthSessions[normalized] ?: return false
        val cancelled = current.commitFence.cancel()
        oauthSessions.remove(normalized, current)
        return cancelled
    }

    private suspend fun connectLocked(
        provider: AiProvider,
        authMethodId: String,
        credentialMaterial: String,
        commitGuard: (() -> Boolean)? = null,
    ): Result<Connected> {
        val selected = runCatching { method(provider, authMethodId) }.getOrElse { return Result.failure(it) }
        if (selected.id == "device-oauth" && credentialMaterial.isBlank()) {
            return Result.failure(IllegalArgumentException("Use beginGitHubDeviceOAuth for device login"))
        }
        val candidate = try {
            candidateCredential(selected, credentialMaterial)
        } catch (t: Throwable) {
            return Result.failure(t)
        }
        val models = validateOnly(provider, selected, candidate).getOrElse { return Result.failure(it) }
        val certifiedModels = runCatching { ProviderModelCertificationRegistry.validatedModels(models) }
            .getOrElse { return Result.failure(it) }

        // OAuth callers additionally claim their exact still-current attempt immediately before durable writes.


        if (commitGuard != null && !commitGuard()) {
            return Result.failure(IllegalStateException("oauth_session_cancelled"))
        }
        return runCatching {
            transaction.commit(
                record = ProviderConnectionRecord(
                    providerId = provider.id,
                    authMethodId = selected.id,
                    validatedAtEpochSeconds = Instant.now().epochSecond,
                    modelCount = certifiedModels.size,
                ),
                persistSecret = selected.storesSecret,
                candidateSecret = candidate,
            )
            ProviderModelCertificationRegistry.certify(provider, certifiedModels)
            ProviderRequestAuthorizer.invalidateProvider(provider.id)
            bumpRevision()
            Connected(snapshot(provider.id), certifiedModels)
        }
    }

    private suspend fun validateOnly(
        provider: AiProvider,
        method: ProviderAuthMethodDescriptor,
        candidate: String,
    ): Result<List<String>> {
        if (method.kind == ProviderAuthMethodKind.DEFAULT_CHAIN || method.kind == ProviderAuthMethodKind.NONE) {
            require(candidate.isEmpty()) { "Default/no-auth methods cannot persist credential material" }
        }
        return ProviderAuthManager.validate(provider, candidate)
    }

    private fun candidateCredential(method: ProviderAuthMethodDescriptor, raw: String): String {
        if (!method.storesSecret) {
            require(raw.isBlank()) { "Selected auth method does not accept credential material" }
            return ""
        }
        val value = raw.trim()
        require(value.isNotEmpty()) { "Credential material is required" }
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_CREDENTIAL_BYTES) { "Credential material is too large" }
        return value
    }


    private fun provider(providerId: String): AiProvider {
        val id = normalizeProviderId(providerId)
        return ProviderRegistry.findById(id) ?: error("Unknown provider: $id")
    }

    private fun method(provider: AiProvider, methodId: String): ProviderAuthMethodDescriptor {
        val id = methodId.trim().lowercase()
        require(METHOD_ID.matches(id)) { "Invalid provider auth method id" }
        return ProviderAuthManager.authMethods(provider).firstOrNull { it.id == id }
            ?: error("Auth method '$id' is not supported by ${provider.name}")
    }

    private fun normalizeProviderId(raw: String): String = raw.trim().lowercase().also {
        require(PROVIDER_ID.matches(it)) { "Invalid provider id" }
    }

    private fun lockFor(providerId: String): Mutex = providerLocks.computeIfAbsent(normalizeProviderId(providerId)) { Mutex() }

    private fun githubCredentialBundle(clientId: String, result: GitHubDeviceOAuth.PollResult.Success): String =
        buildJsonObject {
            put("github_token", result.accessToken)
            put("oauth_client_id", clientId)
            put("oauth_source", "device")
            result.expiresInSeconds?.takeIf { it > 0 }?.let { put("github_token_expires_at", Instant.now().epochSecond + it) }
            result.refreshToken?.let { put("refresh_token", it) }
            result.refreshTokenExpiresInSeconds?.takeIf { it > 0 }?.let {
                put("refresh_token_expires_at", Instant.now().epochSecond + it)
            }
        }.toString()

    private fun pruneExpiredOAuthSessions() {
        val now = System.nanoTime()
        oauthSessions.entries.toList().forEach { (sessionId, pending) ->
            if (pending.expiresAtNanos <= now) invalidateOAuthSession(sessionId, pending)
        }
    }

    private fun invalidateOAuthSessionsForProvider(providerId: String) {
        oauthSessions.entries.toList().forEach { (sessionId, pending) ->
            if (pending.providerId == providerId) invalidateOAuthSession(sessionId, pending)
        }
    }

    private fun invalidateOAuthSession(sessionId: String, pending: PendingOAuth) {
        pending.commitFence.cancel()
        oauthSessions.remove(sessionId, pending)
    }

    private fun bumpRevision() {
        _revision.update { current -> if (current == Long.MAX_VALUE) 0L else current + 1L }
    }

    private fun randomId(): String {
        val bytes = ByteArray(24).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun requireSessionId(raw: String): String = raw.trim().also {
        require(SESSION_ID.matches(it)) { "Invalid OAuth session id" }
    }

    private fun secondsUntil(deadline: Long, now: Long): Long =
        ((deadline - now + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND).coerceAtLeast(1)

    companion object {
        private const val MAX_CREDENTIAL_BYTES = 256 * 1024
        private const val MAX_OAUTH_SESSIONS = 16
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private val PROVIDER_ID = Regex("[a-z0-9._-]{1,96}")
        private val METHOD_ID = Regex("[a-z0-9._-]{1,64}")
        private val SESSION_ID = Regex("[A-Za-z0-9_-]{24,64}")
    }
}

package com.baystudio.droide.core

import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

 
internal object ProviderRequestAuthorizer {
    private const val MAX_SECRET_BYTES = 96 * 1024
    private const val MAX_TOKEN_RESPONSE_BYTES = 512 * 1024L
    private const val EXPIRY_SKEW_SECONDS = 90L
    private const val MAX_TOKEN_CACHE_ENTRIES = 128
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenClient = OkHttpClient.Builder()
        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val tokenCache = ConcurrentHashMap<String, CachedToken>()
    private val awsCredentialCache = ConcurrentHashMap<String, ProviderCredentialChain.AwsCredentials>()
    private val githubOauthCache = ConcurrentHashMap<String, GitHubOauthState>()
    @Volatile private var credentialUpdater: ((String, String) -> Boolean)? = null

    private data class CachedToken(val value: String, val expiresAtEpochSeconds: Long)
    private data class GitHubOauthState(
        val accessToken: String,
        val accessTokenExpiresAt: Long?,
        val refreshToken: String?,
        val refreshTokenExpiresAt: Long?,
        val clientId: String?,
        val source: String?,
    )

    fun installCredentialUpdater(updater: ((String, String) -> Boolean)?) {
        credentialUpdater = updater
    }

    fun invalidateProvider(providerId: String) {
        val id = providerId.trim().lowercase()
        tokenCache.keys.removeIf { it.startsWith("$id:") }
        when (ProviderRegistry.findById(id)?.authScheme) {
            ProviderAuthScheme.GITHUB_COPILOT -> githubOauthCache.clear()
            ProviderAuthScheme.AWS_SIGV4_BEDROCK -> awsCredentialCache.clear()
            else -> Unit
        }
    }

    suspend fun authorize(request: Request, provider: AiProvider, secret: String): Request {
        if (provider.authScheme in LEGACY_SCHEMES) return request
        require(secret.toByteArray(Charsets.UTF_8).size <= MAX_SECRET_BYTES) { "Provider credential bundle is too large" }
        return when (provider.authScheme) {
            ProviderAuthScheme.AWS_SIGV4_BEDROCK -> signBedrock(request, provider, secret)
            ProviderAuthScheme.GOOGLE_ADC -> request.also { requireAdvancedAuthTarget(provider, it) }.newBuilder()
                .header("Authorization", "Bearer ${googleAccessToken(provider, secret)}").build()
            ProviderAuthScheme.AZURE_ENTRA -> request.also { requireAdvancedAuthTarget(provider, it) }.newBuilder()
                .header("Authorization", "Bearer ${azureAccessToken(provider, secret)}").build()
            ProviderAuthScheme.GITHUB_COPILOT -> request.also { requireAdvancedAuthTarget(provider, it) }.let {
                copilotAuthorize(it, provider, secret, allowCredentialRotation = true)
            }
            else -> request
        }
    }

    suspend fun resolveOnly(provider: AiProvider, secret: String) {
        when (provider.authScheme) {
            ProviderAuthScheme.GOOGLE_ADC -> googleAccessToken(provider, secret)
            ProviderAuthScheme.AZURE_ENTRA -> azureAccessToken(provider, secret)
            ProviderAuthScheme.GITHUB_COPILOT -> {
                val probe = Request.Builder().url("https://api.githubcopilot.com").get().build()
                copilotAuthorize(probe, provider, secret, allowCredentialRotation = false)
            }
            ProviderAuthScheme.AWS_SIGV4_BEDROCK -> resolveAwsCredentials(secret)
            else -> Unit
        }
    }

    private suspend fun signBedrock(request: Request, provider: AiProvider, secret: String): Request {
        // compatibility marker: credentialObject(secret) was the explicit-bundle resolver before.
        requireAdvancedAuthTarget(provider, request)
        val credentials = resolveAwsCredentials(secret)
        val region = provider.authRegion?.takeIf { REGION.matches(it) }
            ?: credentials.region?.takeIf { REGION.matches(it) }
            ?: regionFromBedrockHost(request.url.host)
            ?: error("AWS region is required for Bedrock SigV4")
        val bodyBytes = request.body?.let { body -> Buffer().also(body::writeTo).readByteArray() } ?: ByteArray(0)
        val signed = AwsSigV4Signer.sign(
            method = request.method,
            uri = URI(request.url.toString()),
            contentType = request.header("Content-Type"),
            body = bodyBytes,
            accessKeyId = credentials.accessKeyId,
            secretAccessKey = credentials.secretAccessKey,
            sessionToken = credentials.sessionToken,
            region = region,
            service = "bedrock",
        )
        return request.newBuilder()
            .header("x-amz-date", signed.amzDate)
            .header("x-amz-content-sha256", signed.payloadHash)
            .apply { signed.securityToken?.let { header("x-amz-security-token", it) } }
            .header("Authorization", signed.authorization)
            .build()
    }

    private suspend fun resolveAwsCredentials(secret: String): ProviderCredentialChain.AwsCredentials {
        if (secret.isNotBlank()) return ProviderCredentialChain.aws(secret)
        val env = System.getenv()
        val fingerprint = ProviderCredentialChain.environmentFingerprint(ProviderAuthScheme.AWS_SIGV4_BEDROCK, env)
        awsCredentialCache[fingerprint]?.let { cached ->
            val expiry = cached.expiresAtEpochSeconds
            if (expiry != null && expiry > Instant.now().epochSecond + EXPIRY_SKEW_SECONDS) return cached
            awsCredentialCache.remove(fingerprint, cached)
        }
        val hasStaticHint = listOf(
            "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_PROFILE",
            "AWS_SHARED_CREDENTIALS_FILE", "AWS_CONFIG_FILE",
        ).any { !env[it].isNullOrBlank() }
        if (hasStaticHint) return ProviderCredentialChain.aws(secret, env)
        ProviderDefaultCredentialSources.awsWebIdentity(env)?.let { return cacheAws(fingerprint, it) }
        runCatching { ProviderCredentialChain.aws(secret, env) }.getOrNull()?.let { return it }
        ProviderDefaultCredentialSources.awsContainer(env)?.let { return cacheAws(fingerprint, it) }
        ProviderDefaultCredentialSources.awsImdsV2(env)?.let { return cacheAws(fingerprint, it) }
        error("AWS credential chain found no usable credential source")
    }

    private fun cacheAws(key: String, value: ProviderCredentialChain.AwsCredentials): ProviderCredentialChain.AwsCredentials {
        val expiry = value.expiresAtEpochSeconds ?: return value
        if (expiry > Instant.now().epochSecond + EXPIRY_SKEW_SECONDS) {
            if (awsCredentialCache.size >= MAX_TOKEN_CACHE_ENTRIES && !awsCredentialCache.containsKey(key)) awsCredentialCache.clear()
            awsCredentialCache[key] = value
        }
        return value
    }

    private suspend fun googleAccessToken(provider: AiProvider, secret: String): String {
        val key = cacheKey(provider, secret)
        freshCached(key)?.let { return it }
        val credentialJson = when {
            secret.isNotBlank() -> ProviderCredentialChain.googleCredentialJson(secret)
            ProviderCredentialChain.hasGoogleFileSource() -> ProviderCredentialChain.googleCredentialJson(secret)
            else -> ProviderDefaultCredentialSources.googleMetadataAccessToken()?.let { (token, expires) ->
                cache(key, token, expires)
                return token
            } ?: error("Google ADC chain found no file or metadata credentials")
        }
        val obj = credentialObject(credentialJson)
        obj.optional("access_token")?.takeIf { it.isNotBlank() }?.let { token ->
            val expires = obj.optionalLong("expires_at") ?: (Instant.now().epochSecond + 300)
            cache(key, token, expires)
            return token
        }
        val type = obj.required("type")
        val token = when (type) {
            "authorized_user" -> refreshGoogleAuthorizedUser(obj)
            "service_account" -> refreshGoogleServiceAccount(obj)
            else -> error("Unsupported Google ADC credential type: $type")
        }
        cache(key, token.first, token.second)
        return token.first
    }

    private suspend fun refreshGoogleAuthorizedUser(obj: JsonObject): Pair<String, Long> {
        val tokenUri = googleTokenUri(obj.optional("token_uri"))
        return postTokenForm(
            tokenUri,
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to obj.required("client_id"),
                "client_secret" to obj.required("client_secret"),
                "refresh_token" to obj.required("refresh_token"),
            ),
        )
    }

    private suspend fun refreshGoogleServiceAccount(obj: JsonObject): Pair<String, Long> {
        val tokenUri = googleTokenUri(obj.optional("token_uri"))
        val now = Instant.now().epochSecond
        val header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".toByteArray())
        val payload = buildString {
            append('{')
            append("\"iss\":").append(JsonPrimitive(obj.required("client_email"))).append(',')
            append("\"scope\":\"https://www.googleapis.com/auth/cloud-platform\",")
            append("\"aud\":").append(JsonPrimitive(tokenUri)).append(',')
            append("\"iat\":").append(now).append(',')
            append("\"exp\":").append(now + 3600)
            append('}')
        }
        val signingInput = "$header.${base64Url(payload.toByteArray())}"
        val privateKey = parsePkcs8Rsa(obj.required("private_key"))
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(signingInput.toByteArray(Charsets.UTF_8))
        }.sign()
        val assertion = "$signingInput.${base64Url(signature)}"
        return postTokenForm(
            tokenUri,
            mapOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:jwt-bearer",
                "assertion" to assertion,
            ),
        )
    }

    private suspend fun azureAccessToken(provider: AiProvider, secret: String): String {
        val key = cacheKey(provider, secret)
        freshCached(key)?.let { return it }
        val credentialJson = when {
            secret.isNotBlank() -> ProviderCredentialChain.azureCredentialJson(secret)
            ProviderCredentialChain.hasAzureEnvironmentCredential() -> ProviderCredentialChain.azureCredentialJson(secret)
            else -> ProviderDefaultCredentialSources.azureManagedIdentityAccessToken(System.getenv())?.let { (token, expires) ->
                cache(key, token, expires)
                return token
            } ?: error("Azure credential chain found no environment/workload or managed identity credential")
        }
        val obj = credentialObject(credentialJson)
        obj.optional("access_token")?.takeIf { it.isNotBlank() }?.let { token ->
            val expires = obj.optionalLong("expires_at") ?: (Instant.now().epochSecond + 300)
            cache(key, token, expires)
            return token
        }
        val tenant = obj.required("tenant_id")
        require(TENANT.matches(tenant)) { "Invalid Azure tenant id" }
        val tokenUrl = URI("https", "login.microsoftonline.com", "/$tenant/oauth2/v2.0/token", null).toASCIIString()
        val fields = linkedMapOf(
            "client_id" to obj.required("client_id"),
            "scope" to "https://cognitiveservices.azure.com/.default",
        )
        if (obj.optional("refresh_token") != null) {
            fields["grant_type"] = "refresh_token"
            fields["refresh_token"] = obj.required("refresh_token")
            obj.optional("client_secret")?.let { fields["client_secret"] = it }
        } else if (obj.optional("federated_token") != null) {
            fields["grant_type"] = "client_credentials"
            fields["client_assertion_type"] = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
            fields["client_assertion"] = obj.required("federated_token")
        } else {
            fields["grant_type"] = "client_credentials"
            fields["client_secret"] = obj.required("client_secret")
        }
        val token = postTokenForm(tokenUrl, fields)
        cache(key, token.first, token.second)
        return token.first
    }

    private suspend fun copilotAuthorize(
        request: Request,
        provider: AiProvider,
        secret: String,
        allowCredentialRotation: Boolean,
    ): Request {
        val key = cacheKey(provider, secret)
        val token = freshCached(key) ?: run {
            val githubToken = githubOAuthAccessToken(provider, secret, allowCredentialRotation)
            val target = NetworkSecurity.validatePublicHttpsTarget("https://api.github.com/copilot_internal/v2/token")
            val exchange = Request.Builder().url(target.url)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $githubToken")
                .header("User-Agent", "Droide/1.00")
                .get().build()
            val responseText = executeTokenRequest(target, exchange)
            val response = json.parseToJsonElement(responseText) as? JsonObject ?: error("Invalid Copilot token response")
            val value = response.required("token")
            val expires = response.optionalLong("expires_at") ?: (Instant.now().epochSecond + 300)
            cache(key, value, expires)
            value
        }
        return request.newBuilder()
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "Droide/1.00")
            .header("Editor-Version", "Droide/1.00")
            .header("Editor-Plugin-Version", "Droide/1.00")
            .header("Copilot-Integration-Id", "vscode-chat")
            .header("Openai-Intent", "conversation-edits")
            .header("X-Interaction-Type", "conversation")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
    }

    private suspend fun githubOAuthAccessToken(
        provider: AiProvider,
        secret: String,
        allowCredentialRotation: Boolean,
    ): String {
        val rawKey = sha256Hex(secret.toByteArray())
        val parsed = parseMaybeObject(secret)
        if (parsed == null) {
            return secret.trim().also {
                require(it.isNotBlank() && it.length <= 4096) { "GitHub OAuth token is missing" }
            }
        }
        var state = githubOauthCache[rawKey] ?: GitHubOauthState(
            accessToken = parsed.required("github_token"),
            accessTokenExpiresAt = parsed.optionalLong("github_token_expires_at"),
            refreshToken = parsed.optional("refresh_token"),
            refreshTokenExpiresAt = parsed.optionalLong("refresh_token_expires_at"),
            clientId = parsed.optional("oauth_client_id"),
            source = parsed.optional("oauth_source"),
        ).also { githubOauthCache[rawKey] = it }
        require(state.accessToken.length <= 4096) { "GitHub OAuth token is too large" }
        val expiry = state.accessTokenExpiresAt
        if (expiry == null || expiry > Instant.now().epochSecond + EXPIRY_SKEW_SECONDS) return state.accessToken

        if (!allowCredentialRotation) {
            error("GitHub OAuth token requires rotation; reconnect before validating this credential")
        }
        val refresh = state.refreshToken?.takeIf { it.isNotBlank() }
            ?: error("GitHub OAuth access token expired and no refresh token is available")
        val refreshExpiry = state.refreshTokenExpiresAt
        if (refreshExpiry != null && refreshExpiry <= Instant.now().epochSecond + EXPIRY_SKEW_SECONDS) {
            error("GitHub OAuth refresh token has expired; reconnect is required")
        }
        val clientId = state.clientId?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{8,128}")) }
            ?: error("GitHub OAuth refresh requires the original first-party client id")
        val refreshed = refreshGitHubOAuth(clientId, refresh)
        state = state.copy(
            accessToken = refreshed.accessToken,
            accessTokenExpiresAt = refreshed.accessTokenExpiresAt,
            refreshToken = refreshed.refreshToken,
            refreshTokenExpiresAt = refreshed.refreshTokenExpiresAt,
        )
        val rotatedBundle = buildJsonObject {
            put("github_token", state.accessToken)
            put("oauth_client_id", clientId)
            put("oauth_source", state.source ?: "device")
            state.accessTokenExpiresAt?.let { put("github_token_expires_at", it) }
            state.refreshToken?.let { put("refresh_token", it) }
            state.refreshTokenExpiresAt?.let { put("refresh_token_expires_at", it) }
        }.toString()
        val updater = credentialUpdater ?: error("GitHub OAuth token rotation has no durable credential updater")
        require(updater(provider.id, rotatedBundle)) { "Could not durably persist rotated GitHub OAuth credentials" }
        githubOauthCache[rawKey] = state
        return state.accessToken
    }

    private suspend fun refreshGitHubOAuth(clientId: String, refreshToken: String): GitHubOauthState {
        require(refreshToken.length <= 4096) { "GitHub OAuth refresh token is too large" }
        val target = NetworkSecurity.validatePublicHttpsTarget("https://github.com/login/oauth/access_token")
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        val request = Request.Builder().url(target.url)
            .header("Accept", "application/json")
            .header("User-Agent", "Droide/1.00")
            .post(form).build()
        val body = executeTokenRequest(target, request)
        val obj = json.parseToJsonElement(body) as? JsonObject ?: error("Invalid GitHub OAuth refresh response")
        obj.optional("error")?.let { error("GitHub OAuth refresh rejected credentials: $it") }
        val access = obj.required("access_token")
        val now = Instant.now().epochSecond
        val accessExpiry = obj.optionalLong("expires_in")?.coerceIn(60, 86_400)?.let { now + it }
        val nextRefresh = obj.optional("refresh_token") ?: error("GitHub OAuth refresh did not rotate the refresh token")
        val refreshExpiry = obj.optionalLong("refresh_token_expires_in")?.coerceIn(60, 31_536_000)?.let { now + it }
        return GitHubOauthState(access, accessExpiry, nextRefresh, refreshExpiry, clientId, "device")
    }

    private suspend fun postTokenForm(url: String, fields: Map<String, String>): Pair<String, Long> {
        val target = NetworkSecurity.validatePublicHttpsTarget(url)
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url(target.url)
            .header("Accept", "application/json")
            .post(form).build()
        val body = executeTokenRequest(target, req)
        val obj = json.parseToJsonElement(body) as? JsonObject ?: error("Invalid OAuth token response")
        obj.optional("error")?.let { error("OAuth token endpoint rejected credentials: $it") }
        val token = obj.required("access_token")
        val expiresIn = obj.optionalLong("expires_in")?.coerceIn(60, 86_400) ?: 3600L
        return token to (Instant.now().epochSecond + expiresIn)
    }

    private suspend fun executeTokenRequest(target: ValidatedNetworkTarget, request: Request): String {
        try {
            tokenClient.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(request).awaitResponse().use { response ->
                val source = response.body?.source() ?: error("Empty credential endpoint response")
                source.request(MAX_TOKEN_RESPONSE_BYTES + 1)
                require(source.buffer.size <= MAX_TOKEN_RESPONSE_BYTES) { "Credential endpoint response is too large" }
                val body = source.buffer.readUtf8()
                require(response.isSuccessful) { "Credential endpoint HTTP ${response.code}: ${body.take(240)}" }
                return body
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private fun freshCached(key: String): String? {
        val token = tokenCache[key] ?: return null
        return if (token.expiresAtEpochSeconds > Instant.now().epochSecond + EXPIRY_SKEW_SECONDS) token.value
        else null.also { tokenCache.remove(key, token) }
    }

    private fun cache(key: String, token: String, expires: Long) {
        require(token.isNotBlank() && token.length <= 32_768) { "Resolved bearer token is invalid" }
        val now = Instant.now().epochSecond
        tokenCache.entries.removeIf { it.value.expiresAtEpochSeconds <= now + EXPIRY_SKEW_SECONDS }
        if (tokenCache.size >= MAX_TOKEN_CACHE_ENTRIES && !tokenCache.containsKey(key)) tokenCache.clear()
        tokenCache[key] = CachedToken(token, expires)
    }

    private fun cacheKey(provider: AiProvider, secret: String): String {
        val host = runCatching { URI(provider.baseUrl).host?.lowercase() }.getOrNull().orEmpty()
        val sourceFingerprint = if (secret.isNotBlank()) sha256Hex(secret.toByteArray())
            else ProviderCredentialChain.environmentFingerprint(provider.authScheme)
        return provider.id.lowercase() + ':' + provider.authScheme.name + ':' + host + ':' + sourceFingerprint
    }

    private fun credentialObject(raw: String): JsonObject = parseMaybeObject(raw)
        ?: error("This provider auth scheme requires a JSON credential bundle")

    private fun parseMaybeObject(raw: String): JsonObject? = runCatching {
        json.parseToJsonElement(raw) as? JsonObject
    }.getOrNull()

    private fun JsonObject.required(name: String): String = optional(name)?.takeIf { it.isNotBlank() }
        ?: error("Credential field '$name' is required")
    private fun JsonObject.optional(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.optionalLong(name: String): Long? = optional(name)?.toLongOrNull()

    private fun googleTokenUri(raw: String?): String {
        val value = raw ?: "https://oauth2.googleapis.com/token"
        require(value == "https://oauth2.googleapis.com/token") { "Untrusted Google token_uri" }
        return value
    }

    private fun parsePkcs8Rsa(pem: String) = run {
        val cleaned = pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "").replace(Regex("\\s+"), "")
        val bytes = Base64.getDecoder().decode(cleaned)
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    private fun requireAdvancedAuthTarget(provider: AiProvider, request: Request) {
        require(request.url.isHttps) { "Advanced provider credentials require HTTPS" }
        val host = request.url.host.lowercase()
        when (provider.authScheme) {
            ProviderAuthScheme.AWS_SIGV4_BEDROCK -> require(isBedrockHost(host)) { "AWS credentials may only be used with Amazon Bedrock endpoints" }
            ProviderAuthScheme.GOOGLE_ADC -> require(host.endsWith("-aiplatform.googleapis.com")) { "Google ADC may only be used with Vertex AI endpoints" }
            ProviderAuthScheme.AZURE_ENTRA -> require(isAzureAiHost(host)) { "Azure Entra tokens may only be used with Azure AI endpoints" }
            ProviderAuthScheme.GITHUB_COPILOT -> require(host == "api.githubcopilot.com") { "Copilot tokens may only be used with the GitHub Copilot endpoint" }
            else -> Unit
        }
    }

    private fun isBedrockHost(host: String): Boolean {
        val standard = Regex("""(?:bedrock|bedrock-runtime)\.[a-z]{2}(?:-gov)?-[a-z]+-\d\.amazonaws\.com""")
        val mantle = Regex("""bedrock-mantle\.[a-z]{2}(?:-gov)?-[a-z]+-\d\.api\.aws""")
        return standard.matches(host) || mantle.matches(host)
    }

    private fun isAzureAiHost(host: String): Boolean =
        host.endsWith(".openai.azure.com") || host.endsWith(".services.ai.azure.com") || host.endsWith(".cognitiveservices.azure.com")

    private fun regionFromBedrockHost(host: String): String? {
        val parts = host.lowercase().split('.')
        val index = parts.indexOfFirst { it == "bedrock-runtime" || it == "bedrock-mantle" }
        return parts.getOrNull(index + 1)?.takeIf { REGION.matches(it) }
    }

    private fun sha256Hex(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private val LEGACY_SCHEMES = setOf(
        ProviderAuthScheme.NONE,
        ProviderAuthScheme.BEARER,
        ProviderAuthScheme.ANTHROPIC_X_API_KEY,
        ProviderAuthScheme.GOOGLE_API_KEY,
        ProviderAuthScheme.AZURE_API_KEY,
    )
    private val REGION = Regex("[a-z]{2}(?:-gov)?-[a-z]+-\\d")
    private val TENANT = Regex("[A-Za-z0-9._-]{2,128}")
}

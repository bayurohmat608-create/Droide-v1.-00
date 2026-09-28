package com.baystudio.droide.core

import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class GitHubAccountCredential(
    val accessToken: String,
    val tokenType: String,
    val scope: String?,
    val expiresAtEpochSeconds: Long?,
    val refreshToken: String?,
    val refreshTokenExpiresAtEpochSeconds: Long?,
)

internal data class GitHubAccountIdentity(
    val login: String,
    val userId: Long,
    val avatarUrl: String?,
)

internal class GitHubAccountRemoteException(
    val code: String,
    val httpStatus: Int,
) : IllegalStateException(code)

 
internal class GitHubAccountOAuthClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun exchange(
        exchangeUrl: String,
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): GitHubAccountCredential = withContext(Dispatchers.IO) {
        val endpoint = relayEndpoint(exchangeUrl, RELAY_EXCHANGE_PATH)
        requireClientId(clientId)
        require(code.length in 1..1_024 && code.none(::isForbiddenControl)) { "Invalid GitHub authorization code" }
        require(codeVerifier.matches(Regex("[A-Za-z0-9._~-]{43,128}"))) { "Invalid PKCE verifier" }
        require(GitHubAccountOAuthAuthority().isCallbackRoute(redirectUri, redirectUri)) { "GitHub redirect URI mismatch" }

        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("code", code)
            .add("code_verifier", codeVerifier)
            .add("redirect_uri", redirectUri)
            .build()
        val body = postForm(endpoint, form)
        parseCredential(body, requireRefreshRotation = false)
    }

     
    suspend fun refresh(
        exchangeUrl: String,
        clientId: String,
        refreshToken: String,
    ): GitHubAccountCredential = withContext(Dispatchers.IO) {
        val endpoint = relayEndpoint(exchangeUrl, RELAY_REFRESH_PATH)
        requireClientId(clientId)
        requireToken(refreshToken)
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        val body = postForm(endpoint, form)
        
        parseCredential(body, requireRefreshRotation = true)
    }

    



    suspend fun revokeAuthorization(
        exchangeUrl: String,
        clientId: String,
        accessToken: String,
    ) = withContext(Dispatchers.IO) {
        val endpoint = relayEndpoint(exchangeUrl, RELAY_REVOKE_PATH)
        requireClientId(clientId)
        requireToken(accessToken)
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("access_token", accessToken)
            .build()
        val request = Request.Builder()
            .url(endpoint.toASCIIString())
            .post(form)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("Cache-Control", "no-store")
            .build()
        client.newCall(request).execute().use { response ->
            val body = readBoundedBody(response)
            if (!response.isSuccessful) throw remoteFailure(response.code, body)
        }
    }

    suspend fun authenticatedUser(accessToken: String): GitHubAccountIdentity = withContext(Dispatchers.IO) {
        requireToken(accessToken)
        val request = Request.Builder()
            .url("https://api.github.com/user")
            .get()
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $accessToken")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            val body = readBoundedBody(response)
            if (!response.isSuccessful) throw GitHubAccountRemoteException("github_identity_${response.code}", response.code)
            val obj = json.parseToJsonElement(body) as? JsonObject ?: error("Invalid GitHub identity response")
            val login = obj.string("login").trim()
            val id = obj.long("id")
            val avatar = obj.optionalString("avatar_url")?.takeIf { it.startsWith("https://") && it.length <= 2_048 }
            require(login.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})"))) { "Invalid GitHub login identity" }
            require(id > 0L) { "Invalid GitHub account id" }
            GitHubAccountIdentity(login, id, avatar)
        }
    }

    private fun postForm(endpoint: URI, form: FormBody): String {
        val request = Request.Builder()
            .url(endpoint.toASCIIString())
            .post(form)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("Cache-Control", "no-store")
            .build()
        client.newCall(request).execute().use { response ->
            val body = readBoundedBody(response)
            if (!response.isSuccessful) throw remoteFailure(response.code, body)
            return body
        }
    }

    private fun parseCredential(body: String, requireRefreshRotation: Boolean): GitHubAccountCredential {
        val obj = json.parseToJsonElement(body) as? JsonObject ?: error("Invalid GitHub token response")
        val token = obj.string("access_token").trim()
        requireToken(token)
        val tokenType = obj.optionalString("token_type")?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9._-]{1,32}")) } ?: "bearer"
        val scope = obj.optionalString("scope")?.take(1_024)
        val now = Instant.now().epochSecond
        val expiresIn = obj.optionalLong("expires_in")?.takeIf { it in 1..31_536_000L }
        val refresh = obj.optionalString("refresh_token")?.trim()?.takeIf(String::isNotEmpty)
        refresh?.let(::requireToken)
        val refreshExpiresIn = obj.optionalLong("refresh_token_expires_in")?.takeIf { it in 1..63_072_000L }
        if (requireRefreshRotation) {
            require(expiresIn != null && refresh != null && refreshExpiresIn != null) {
                "GitHub refresh response did not rotate the complete expiring credential pair"
            }
        }
        return GitHubAccountCredential(
            accessToken = token,
            tokenType = tokenType,
            scope = scope,
            expiresAtEpochSeconds = expiresIn?.let { now + it },
            refreshToken = refresh,
            refreshTokenExpiresAtEpochSeconds = refreshExpiresIn?.let { now + it },
        )
    }

    private fun relayEndpoint(rawExchangeUrl: String, expectedPath: String): URI {
        require(rawExchangeUrl.length in 1..2_048) { "GitHub token exchange relay is not configured" }
        val exchange = URI(rawExchangeUrl)
        require(exchange.scheme.equals("https", true) && !exchange.host.isNullOrBlank()) { "GitHub token exchange relay must use HTTPS" }
        require(exchange.rawUserInfo == null && exchange.rawFragment == null && exchange.rawQuery == null) { "GitHub token exchange relay URL is invalid" }
        require(exchange.port == -1 || exchange.port == 443) { "GitHub token exchange relay must use the HTTPS default port" }
        require(exchange.rawPath == RELAY_EXCHANGE_PATH) { "GitHub relay must expose the fixed exchange path" }
        return if (expectedPath == RELAY_EXCHANGE_PATH) exchange else URI(
            exchange.scheme,
            null,
            exchange.host,
            exchange.port,
            expectedPath,
            null,
            null,
        )
    }

    private fun readBoundedBody(response: okhttp3.Response): String {
        val responseBody = response.body ?: return ""
        val source = responseBody.source()
        val bytes = source.readByteArray(MAX_RESPONSE_BYTES + 1L)
        require(bytes.size <= MAX_RESPONSE_BYTES) { "GitHub response exceeded ${MAX_RESPONSE_BYTES} bytes" }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun remoteFailure(status: Int, body: String): GitHubAccountRemoteException {
        val code = runCatching {
            val obj = json.parseToJsonElement(body) as? JsonObject
            obj?.optionalString("error") ?: obj?.optionalString("message")
        }.getOrNull().orEmpty()
            .take(96)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "relay_error" }
        return GitHubAccountRemoteException(code, status)
    }

    private fun requireClientId(clientId: String) {
        require(clientId.matches(Regex("[A-Za-z0-9._-]{8,128}"))) { "Invalid GitHub account client id" }
    }

    private fun requireToken(token: String) {
        

        require(token.length in 20..4_096 && token.none(::isForbiddenControl) && token.none(Char::isWhitespace)) { "Invalid GitHub token" }
    }

    private fun isForbiddenControl(c: Char): Boolean = c == '\r' || c == '\n' || c == '\u0000'

    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull
        ?: error("Missing GitHub response field: $key")
    private fun JsonObject.optionalString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long = optionalLong(key) ?: error("Missing GitHub response field: $key")
    private fun JsonObject.optionalLong(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    companion object {
        const val RELAY_EXCHANGE_PATH = "/oauth/github/exchange"
        const val RELAY_REFRESH_PATH = "/oauth/github/refresh"
        const val RELAY_REVOKE_PATH = "/oauth/github/revoke"
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val GITHUB_API_VERSION = "2026-03-10"
        private const val USER_AGENT = "Droide-Mobile-IDE"
    }
}

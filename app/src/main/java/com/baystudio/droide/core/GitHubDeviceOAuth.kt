package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit








internal object GitHubDeviceOAuth {
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSeconds: Long,
        val intervalSeconds: Long,
    )

    sealed interface PollResult {
        data class Success(
            val accessToken: String,
            val refreshToken: String?,
            val expiresInSeconds: Long?,
            val refreshTokenExpiresInSeconds: Long? = null,
        ) : PollResult
        data class Pending(val nextIntervalSeconds: Long) : PollResult
        data class Failed(val reason: String) : PollResult
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun start(clientId: String, scope: String = "read:user"): DeviceCode {
        require(CLIENT_ID.matches(clientId)) { "Invalid GitHub OAuth client id" }
        require(scope.length <= 256 && scope.matches(Regex("[A-Za-z0-9:_ -]*"))) { "Invalid GitHub OAuth scope" }
        val form = FormBody.Builder().add("client_id", clientId).add("scope", scope).build()
        val body = post("https://github.com/login/device/code", form)
        val obj = json.parseToJsonElement(body) as? JsonObject ?: error("Invalid GitHub device-code response")
        val verification = obj.string("verification_uri")
        require(verification == "https://github.com/login/device") { "Unexpected GitHub verification URI" }
        return DeviceCode(
            deviceCode = obj.string("device_code"),
            userCode = obj.string("user_code"),
            verificationUri = verification,
            expiresInSeconds = obj.long("expires_in")?.coerceIn(60, 1800) ?: 900,
            intervalSeconds = obj.long("interval")?.coerceIn(5, 60) ?: 5,
        )
    }

    suspend fun awaitAuthorization(clientId: String, device: DeviceCode): PollResult.Success {
        val deadline = System.nanoTime() + device.expiresInSeconds.coerceIn(60, 1800) * 1_000_000_000L
        var interval = device.intervalSeconds.coerceIn(5, 60)
        while (System.nanoTime() < deadline) {
            delay(interval * 1000L)
            when (val result = pollOnce(clientId, device.deviceCode, interval)) {
                is PollResult.Success -> return result
                is PollResult.Pending -> interval = result.nextIntervalSeconds.coerceIn(5, 60)
                is PollResult.Failed -> error("GitHub device authorization failed: ${result.reason}")
            }
        }
        error("GitHub device authorization expired")
    }

    suspend fun pollOnce(clientId: String, deviceCode: String, previousIntervalSeconds: Long): PollResult {
        require(CLIENT_ID.matches(clientId)) { "Invalid GitHub OAuth client id" }
        require(deviceCode.length in 16..128) { "Invalid GitHub device code" }
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val obj = json.parseToJsonElement(post("https://github.com/login/oauth/access_token", form)) as? JsonObject
            ?: return PollResult.Failed("invalid_response")
        obj.stringOrNull("access_token")?.let { token ->
            require(token.length <= 4096) { "GitHub OAuth token is too large" }
            return PollResult.Success(
                accessToken = token,
                refreshToken = obj.stringOrNull("refresh_token")?.takeIf { it.length <= 4096 },
                expiresInSeconds = obj.long("expires_in"),
                refreshTokenExpiresInSeconds = obj.long("refresh_token_expires_in"),
            )
        }
        return when (val error = obj.stringOrNull("error") ?: "invalid_response") {
            "authorization_pending" -> PollResult.Pending(previousIntervalSeconds.coerceIn(5, 60))
            "slow_down" -> PollResult.Pending((previousIntervalSeconds + 5).coerceAtMost(60))
            else -> PollResult.Failed(error)
        }
    }

    private suspend fun post(url: String, body: FormBody): String {
        val target = NetworkSecurity.validatePublicHttpsTarget(url)
        val req = Request.Builder().url(target.url)
            .header("Accept", "application/json")
            .header("User-Agent", "Droide/1.00")
            .post(body).build()
        try {
            client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(req).awaitResponse().use { response ->
                val source = response.body?.source() ?: error("Empty GitHub OAuth response")
                source.request(MAX_RESPONSE_BYTES + 1L)
                require(source.buffer.size <= MAX_RESPONSE_BYTES) { "GitHub OAuth response is too large" }
                val text = source.buffer.readUtf8()
                require(response.isSuccessful) { "GitHub OAuth HTTP ${response.code}: ${text.take(200)}" }
                return text
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private fun JsonObject.string(name: String): String = stringOrNull(name)?.takeIf { it.isNotBlank() }
        ?: error("GitHub OAuth field '$name' is missing")
    private fun JsonObject.stringOrNull(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(name: String): Long? = stringOrNull(name)?.toLongOrNull()

    private const val MAX_RESPONSE_BYTES = 256 * 1024L
    private val CLIENT_ID = Regex("[A-Za-z0-9._-]{8,128}")
}

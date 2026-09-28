package com.baystudio.droide.core

import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Metadata hosts are fixed or narrowly allowlisted, redirects are disabled, responses are bounded and credentials are never persisted here.






internal object ProviderDefaultCredentialSources {
    private const val MAX_RESPONSE_BYTES = 256 * 1024L
    private const val METADATA_TIMEOUT_SECONDS = 3L
    private val json = Json { ignoreUnknownKeys = true }
    private val metadataClient = OkHttpClient.Builder()
        .connectTimeout(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val publicClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun awsWebIdentity(env: Map<String, String>): ProviderCredentialChain.AwsCredentials? {
        val roleArn = env["AWS_ROLE_ARN"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val tokenPath = env["AWS_WEB_IDENTITY_TOKEN_FILE"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        require(roleArn.length <= 2048 && roleArn.startsWith("arn:aws:iam::") && roleArn.contains(":role/")) {
            "Invalid AWS web-identity role ARN"
        }
        val token = ProviderCredentialChain.readCredentialFile(tokenPath, "AWS web identity token", 64 * 1024L).trim()
        require(token.length in 16..64_000) { "AWS web identity token is invalid" }
        val region = ProviderCredentialChain.awsRegion(env)
        val stsHost = if (region != null) "sts.$region.amazonaws.com" else "sts.amazonaws.com"
        val target = NetworkSecurity.validatePublicHttpsTarget(URI("https", stsHost, "/", null).toASCIIString())
        val sessionName = env["AWS_ROLE_SESSION_NAME"]?.trim()?.takeIf { SESSION_NAME.matches(it) }
            ?: "droide-${Instant.now().epochSecond}"
        val form = FormBody.Builder()
            .add("Action", "AssumeRoleWithWebIdentity")
            .add("Version", "2011-06-15")
            .add("RoleArn", roleArn)
            .add("RoleSessionName", sessionName)
            .add("WebIdentityToken", token)
            .build()
        val request = Request.Builder().url(target.url).post(form).header("Accept", "application/xml").build()
        val xml = execute(publicClient.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build(), request)
        return ProviderCredentialChain.AwsCredentials(
            accessKeyId = xmlTag(xml, "AccessKeyId"),
            secretAccessKey = xmlTag(xml, "SecretAccessKey"),
            sessionToken = xmlTag(xml, "SessionToken"),
            region = region,
            source = "web-identity-sts",
            expiresAtEpochSeconds = parseExpiry(xmlTag(xml, "Expiration")),
        )
    }

    suspend fun awsContainer(env: Map<String, String>): ProviderCredentialChain.AwsCredentials? {
        val relative = env["AWS_CONTAINER_CREDENTIALS_RELATIVE_URI"]?.trim()?.takeIf { it.isNotEmpty() }
        val full = env["AWS_CONTAINER_CREDENTIALS_FULL_URI"]?.trim()?.takeIf { it.isNotEmpty() }
        if (relative == null && full == null) return null
        require(!(relative != null && full != null)) { "Conflicting AWS container credential endpoints" }
        val uri = if (relative != null) {
            require(relative.startsWith('/') && !relative.contains("..") && relative.length <= 2048) {
                "Invalid AWS container relative credential URI"
            }
            URI("http", null, "169.254.170.2", -1, relative, null, null)
        } else {
            val candidate = URI(full!!)
            require(candidate.scheme.equals("http", true)) { "AWS container credential endpoint must use HTTP" }
            require(candidate.userInfo == null && candidate.fragment == null) { "Unsafe AWS container credential endpoint" }
            require(candidate.host in CONTAINER_HOSTS) { "AWS container credential host is not allowlisted" }
            candidate
        }
        val token = env["AWS_CONTAINER_AUTHORIZATION_TOKEN"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: env["AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                ProviderCredentialChain.readCredentialFile(it, "AWS container authorization token", 32 * 1024L).trim()
            }
        val builder = Request.Builder().url(uri.toASCIIString()).get().header("Accept", "application/json")
        token?.let { builder.header("Authorization", it) }
        val obj = jsonObject(execute(metadataClient, builder.build()), "AWS container credentials")
        return awsJsonCredentials(obj, ProviderCredentialChain.awsRegion(env), "ecs-container")
    }

    suspend fun awsImdsV2(env: Map<String, String>): ProviderCredentialChain.AwsCredentials? {
        if (env["AWS_EC2_METADATA_DISABLED"]?.trim()?.equals("true", true) == true) return null
        val base = URI("http", null, "169.254.169.254", -1, null, null, null)
        val tokenRequest = Request.Builder()
            .url(base.resolve("/latest/api/token").toASCIIString())
            .put(ByteArray(0).toRequestBody(null))
            .header("X-aws-ec2-metadata-token-ttl-seconds", "21600")
            .build()
        val token = runCatching { execute(metadataClient, tokenRequest).trim() }.getOrNull()?.takeIf { it.length in 8..4096 }
            ?: return null
        val roleRequest = Request.Builder()
            .url(base.resolve("/latest/meta-data/iam/security-credentials/").toASCIIString())
            .get().header("X-aws-ec2-metadata-token", token).build()
        val role = execute(metadataClient, roleRequest).lineSequence().firstOrNull()?.trim().orEmpty()
        require(ROLE_NAME.matches(role)) { "Invalid EC2 instance profile role name" }
        val credentialRequest = Request.Builder()
            .url(base.resolve("/latest/meta-data/iam/security-credentials/${encodePathSegment(role)}").toASCIIString())
            .get().header("X-aws-ec2-metadata-token", token).build()
        val obj = jsonObject(execute(metadataClient, credentialRequest), "EC2 instance credentials")
        return awsJsonCredentials(obj, ProviderCredentialChain.awsRegion(env), "ec2-imdsv2")
    }

    suspend fun googleMetadataAccessToken(): Pair<String, Long>? {
        val uri = URI("http", null, "169.254.169.254", -1,
            "/computeMetadata/v1/instance/service-accounts/default/token", null, null)
        val request = Request.Builder().url(uri.toASCIIString()).get()
            .header("Metadata-Flavor", "Google").header("Accept", "application/json").build()
        val text = runCatching { execute(metadataClient, request) }.getOrNull() ?: return null
        val obj = jsonObject(text, "Google metadata token")
        val token = obj.string("access_token")
        val expires = obj.long("expires_in")?.coerceIn(60, 86_400) ?: 300
        return token to (Instant.now().epochSecond + expires)
    }

    suspend fun azureManagedIdentityAccessToken(env: Map<String, String>): Pair<String, Long>? {
        val clientId = env["AZURE_CLIENT_ID"]?.trim()?.takeIf { it.isNotEmpty() }
        val query = buildString {
            append("api-version=2018-02-01&resource=https://cognitiveservices.azure.com/")
            if (clientId != null) append("&client_id=").append(clientId)
        }
        val uri = URI("http", null, "169.254.169.254", -1, "/metadata/identity/oauth2/token", query, null)
        val request = Request.Builder().url(uri.toASCIIString()).get()
            .header("Metadata", "true").header("Accept", "application/json").build()
        val text = runCatching { execute(metadataClient, request) }.getOrNull() ?: return null
        val obj = jsonObject(text, "Azure managed identity token")
        val token = obj.string("access_token")
        val expires = obj.stringOrNull("expires_on")?.toLongOrNull()
            ?: (Instant.now().epochSecond + (obj.long("expires_in")?.coerceIn(60, 86_400) ?: 300))
        return token to expires
    }

    private suspend fun execute(client: OkHttpClient, request: Request): String {
        try {
            client.newCall(request).awaitResponse().use { response ->
                val source = response.body?.source() ?: error("Empty credential-source response")
                source.request(MAX_RESPONSE_BYTES + 1)
                require(source.buffer.size <= MAX_RESPONSE_BYTES) { "Credential-source response is too large" }
                val text = source.buffer.readUtf8()
                require(response.isSuccessful) { "Credential source HTTP ${response.code}: ${text.take(160)}" }
                return text
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private fun awsJsonCredentials(obj: JsonObject, region: String?, source: String): ProviderCredentialChain.AwsCredentials {
        val access = obj.stringOrNull("AccessKeyId") ?: obj.string("AccessKeyID")
        val secret = obj.string("SecretAccessKey")
        val token = obj.stringOrNull("Token") ?: obj.stringOrNull("SessionToken")
        require(access.length <= 512 && secret.length <= 4096 && (token?.length ?: 0) <= 16_384) {
            "AWS metadata credentials exceed limits"
        }
        return ProviderCredentialChain.AwsCredentials(
            access, secret, token, region, source, obj.stringOrNull("Expiration")?.let(::parseExpiry),
        )
    }

    private fun jsonObject(raw: String, label: String): JsonObject =
        json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid $label response")

    private fun JsonObject.string(name: String): String = stringOrNull(name)?.takeIf { it.isNotBlank() }
        ?: error("Credential source field '$name' is missing")
    private fun JsonObject.stringOrNull(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(name: String): Long? = stringOrNull(name)?.toLongOrNull()

    private fun xmlTag(raw: String, tag: String): String {
        val match = Regex("<$tag>([^<]{1,65536})</$tag>").find(raw) ?: error("AWS STS response missing $tag")
        return decodeXml(match.groupValues[1]).trim().also { require(it.isNotEmpty()) }
    }

    private fun parseExpiry(raw: String): Long = runCatching { Instant.parse(raw.trim()).epochSecond }
        .getOrElse { error("Invalid temporary AWS credential expiration") }
        .also { require(it > Instant.now().epochSecond) { "Temporary AWS credentials are already expired" } }

    private fun decodeXml(value: String): String = value
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    private fun encodePathSegment(value: String): String = value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
        val b = byte.toInt() and 0xff
        if (b in 'A'.code..'Z'.code || b in 'a'.code..'z'.code || b in '0'.code..'9'.code || b in "-_.~".map(Char::code)) {
            b.toChar().toString()
        } else "%%%02X".format(b)
    }

    private val SESSION_NAME = Regex("[A-Za-z0-9+=,.@_-]{2,64}")
    private val ROLE_NAME = Regex("[A-Za-z0-9+=,.@_-]{1,128}")
    private val CONTAINER_HOSTS = setOf("169.254.170.2", "169.254.170.23", "127.0.0.1", "localhost", "::1", "[::1]")
}

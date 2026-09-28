package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put








internal object ProviderCredentialChain {
    private const val MAX_CREDENTIAL_FILE_BYTES = 256 * 1024L
    private val json = Json { ignoreUnknownKeys = true }

    data class AwsCredentials(
        val accessKeyId: String,
        val secretAccessKey: String,
        val sessionToken: String?,
        val region: String?,
        val source: String,
        val expiresAtEpochSeconds: Long? = null,
    )

    fun aws(
        rawSecret: String,
        env: Map<String, String> = System.getenv(),
        homeDir: String? = env["HOME"] ?: System.getProperty("user.home"),
    ): AwsCredentials {
        val explicit = parseObject(rawSecret)
        val explicitAccess = explicit?.string("accessKeyId") ?: explicit?.string("aws_access_key_id")
        if (!explicitAccess.isNullOrBlank()) {
            return AwsCredentials(
                accessKeyId = bounded(explicitAccess, "AWS access key id", 512),
                secretAccessKey = bounded(
                    explicit?.string("secretAccessKey") ?: explicit?.string("aws_secret_access_key")
                        ?: error("Credential field 'secretAccessKey' is required"),
                    "AWS secret access key", 4096,
                ),
                sessionToken = (explicit?.string("sessionToken") ?: explicit?.string("aws_session_token"))
                    ?.let { bounded(it, "AWS session token", 16_384) },
                region = normalizeRegion(explicit?.string("region")),
                source = "secret-store-explicit",
            )
        }

        val envAccess = env["AWS_ACCESS_KEY_ID"]?.trim().orEmpty()
        val envSecret = env["AWS_SECRET_ACCESS_KEY"]?.trim().orEmpty()
        if (envAccess.isNotEmpty() || envSecret.isNotEmpty()) {
            require(envAccess.isNotEmpty() && envSecret.isNotEmpty()) { "Incomplete AWS environment credentials" }
            return AwsCredentials(
                accessKeyId = bounded(envAccess, "AWS access key id", 512),
                secretAccessKey = bounded(envSecret, "AWS secret access key", 4096),
                sessionToken = env["AWS_SESSION_TOKEN"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { bounded(it, "AWS session token", 16_384) },
                region = normalizeRegion(env["AWS_REGION"] ?: env["AWS_DEFAULT_REGION"]),
                source = "environment",
            )
        }

        val profile = (explicit?.string("profile") ?: env["AWS_PROFILE"] ?: "default").trim()
        require(PROFILE.matches(profile)) { "Invalid AWS profile name" }
        val credentialsPath = env["AWS_SHARED_CREDENTIALS_FILE"]?.takeIf { it.isNotBlank() }
            ?: homeDir?.let { File(it, ".aws/credentials").path }
            ?: error("AWS credential chain has no home directory")
        val resolved = AwsSharedProfileParser.credentials(readBoundedFile(credentialsPath, "AWS shared credentials"), profile)
        val region = normalizeRegion(explicit?.string("region") ?: env["AWS_REGION"] ?: env["AWS_DEFAULT_REGION"])
            ?: awsConfigRegion(profile, env, homeDir)
        return AwsCredentials(
            accessKeyId = bounded(resolved.accessKeyId, "AWS access key id", 512),
            secretAccessKey = bounded(resolved.secretAccessKey, "AWS secret access key", 4096),
            sessionToken = resolved.sessionToken?.let { bounded(it, "AWS session token", 16_384) },
            region = region,
            source = "shared-profile:$profile",
        )
    }

    fun googleCredentialJson(
        rawSecret: String,
        env: Map<String, String> = System.getenv(),
        homeDir: String? = env["HOME"] ?: System.getProperty("user.home"),
    ): String {
        if (rawSecret.isNotBlank()) return boundedRaw(rawSecret, "Google credential bundle")
        val explicitPath = env["GOOGLE_APPLICATION_CREDENTIALS"]?.trim().orEmpty()
        if (explicitPath.isNotEmpty()) return readBoundedFile(explicitPath, "Google application credentials")
        val wellKnown = googleWellKnownAdcPath(env, homeDir)
        if (wellKnown != null && Files.isRegularFile(Paths.get(wellKnown)) && !Files.isSymbolicLink(Paths.get(wellKnown))) {
            return readBoundedFile(wellKnown, "Google well-known ADC")
        }
        error("Google file-based ADC source is unavailable")
    }

    fun azureCredentialJson(
        rawSecret: String,
        env: Map<String, String> = System.getenv(),
    ): String {
        if (rawSecret.isNotBlank()) return boundedRaw(rawSecret, "Azure credential bundle")
        val tenant = env["AZURE_TENANT_ID"]?.trim().orEmpty()
        val client = env["AZURE_CLIENT_ID"]?.trim().orEmpty()
        require(tenant.isNotEmpty() && client.isNotEmpty()) {
            "Azure environment credential requires AZURE_TENANT_ID and AZURE_CLIENT_ID"
        }
        val secret = env["AZURE_CLIENT_SECRET"]?.trim()?.takeIf { it.isNotEmpty() }
        val federatedPath = env["AZURE_FEDERATED_TOKEN_FILE"]?.trim()?.takeIf { it.isNotEmpty() }
        require((secret != null) xor (federatedPath != null)) {
            "Azure environment credential requires exactly one of AZURE_CLIENT_SECRET or AZURE_FEDERATED_TOKEN_FILE"
        }
        return buildJsonObject {
            put("tenant_id", tenant)
            put("client_id", client)
            if (secret != null) put("client_secret", bounded(secret, "Azure client secret", 16_384))
            if (federatedPath != null) {
                val token = readBoundedFile(federatedPath, "Azure federated token").trim()
                require(token.isNotEmpty() && token.length <= 32_768) { "Azure federated token is invalid" }
                put("federated_token", token)
            }
        }.toString()
    }

    internal fun hasAzureEnvironmentCredential(env: Map<String, String> = System.getenv()): Boolean =
        listOf("AZURE_TENANT_ID", "AZURE_CLIENT_SECRET", "AZURE_FEDERATED_TOKEN_FILE").any { !env[it].isNullOrBlank() }

    internal fun hasGoogleFileSource(
        env: Map<String, String> = System.getenv(),
        homeDir: String? = env["HOME"] ?: System.getProperty("user.home"),
    ): Boolean {
        val explicit = env["GOOGLE_APPLICATION_CREDENTIALS"]?.takeIf { it.isNotBlank() }
        if (explicit != null) return true
        val wellKnown = googleWellKnownAdcPath(env, homeDir) ?: return false
        val path = runCatching { Paths.get(wellKnown) }.getOrNull() ?: return false
        return Files.isRegularFile(path) && !Files.isSymbolicLink(path)
    }

    fun environmentFingerprint(scheme: ProviderAuthScheme, env: Map<String, String> = System.getenv()): String {
        val names = when (scheme) {
            ProviderAuthScheme.AWS_SIGV4_BEDROCK -> listOf(
                "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_PROFILE",
                "AWS_REGION", "AWS_DEFAULT_REGION", "AWS_SHARED_CREDENTIALS_FILE", "AWS_CONFIG_FILE",
                "AWS_ROLE_ARN", "AWS_WEB_IDENTITY_TOKEN_FILE", "AWS_ROLE_SESSION_NAME",
                "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI", "AWS_CONTAINER_CREDENTIALS_FULL_URI",
                "AWS_CONTAINER_AUTHORIZATION_TOKEN", "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE",
                "AWS_EC2_METADATA_DISABLED",
            )
            ProviderAuthScheme.GOOGLE_ADC -> listOf("GOOGLE_APPLICATION_CREDENTIALS", "HOME", "APPDATA")
            ProviderAuthScheme.AZURE_ENTRA -> listOf(
                "AZURE_TENANT_ID", "AZURE_CLIENT_ID", "AZURE_CLIENT_SECRET", "AZURE_FEDERATED_TOKEN_FILE",
            )
            else -> emptyList()
        }
        val material = buildString {
            for (name in names) append(name).append('=').append(env[name].orEmpty()).append('\n')
            val fileBacked = names.filter { it.endsWith("FILE") }.toMutableSet().apply {
                if (scheme == ProviderAuthScheme.GOOGLE_ADC) add("GOOGLE_APPLICATION_CREDENTIALS")
            }
            for (name in fileBacked) {
                val path = env[name]?.takeIf { it.isNotBlank() } ?: continue
                val file = File(path)
                append(name).append("#meta=").append(file.length()).append(':').append(file.lastModified())
                runCatching { readBoundedFile(path, "$name credential source") }.getOrNull()?.let {
                    append(':').append(sha256(it))
                }
                append('\n')
            }
            val home = env["HOME"] ?: System.getProperty("user.home")
            if (scheme == ProviderAuthScheme.AWS_SIGV4_BEDROCK) {
                if (env["AWS_SHARED_CREDENTIALS_FILE"].isNullOrBlank()) home?.let { appendFileFingerprint("AWS_DEFAULT_SHARED_CREDENTIALS", File(it, ".aws/credentials").path) }
                if (env["AWS_CONFIG_FILE"].isNullOrBlank()) home?.let { appendFileFingerprint("AWS_DEFAULT_CONFIG", File(it, ".aws/config").path) }
            }
            if (scheme == ProviderAuthScheme.GOOGLE_ADC && env["GOOGLE_APPLICATION_CREDENTIALS"].isNullOrBlank()) {
                googleWellKnownAdcPath(env, home)?.let { appendFileFingerprint("GOOGLE_WELL_KNOWN_ADC", it) }
            }
        }
        return sha256(material)
    }

    internal fun awsRegion(env: Map<String, String> = System.getenv()): String? =
        normalizeRegion(env["AWS_REGION"] ?: env["AWS_DEFAULT_REGION"])

    internal fun googleWellKnownAdcPath(env: Map<String, String>, homeDir: String?): String? {
        val appData = env["APPDATA"]?.takeIf { it.isNotBlank() }
        if (appData != null) return File(File(appData, "gcloud"), "application_default_credentials.json").path
        return homeDir?.let { File(File(File(it, ".config"), "gcloud"), "application_default_credentials.json").path }
    }

    private fun awsConfigRegion(profile: String, env: Map<String, String>, homeDir: String?): String? {
        val path = env["AWS_CONFIG_FILE"]?.takeIf { it.isNotBlank() }
            ?: homeDir?.let { File(it, ".aws/config").path }
            ?: return null
        val raw = runCatching { readBoundedFile(path, "AWS config") }.getOrNull() ?: return null
        return AwsSharedProfileParser.region(raw, profile)
    }

    internal fun readCredentialFile(pathText: String, label: String, maxBytes: Long = MAX_CREDENTIAL_FILE_BYTES): String {
        require(pathText.length in 1..4096) { "$label path is invalid" }
        require(maxBytes in 1..MAX_CREDENTIAL_FILE_BYTES) { "$label size limit is invalid" }
        val path = Paths.get(pathText).toAbsolutePath().normalize()
        require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) { "$label file is missing or unsafe" }
        val size = Files.size(path)
        require(size in 1..maxBytes) { "$label file is too large" }
        return Files.newBufferedReader(path, Charsets.UTF_8).use { it.readText() }
    }

    private fun readBoundedFile(pathText: String, label: String): String = readCredentialFile(pathText, label)

    private fun StringBuilder.appendFileFingerprint(label: String, pathText: String) {
        val file = runCatching { File(pathText).absoluteFile }.getOrNull() ?: return
        append(label).append("#meta=").append(file.length()).append(':').append(file.lastModified())
        runCatching { readBoundedFile(file.path, "$label credential source") }.getOrNull()?.let {
            append(':').append(sha256(it))
        }
        append('\n')
    }

    private fun boundedRaw(raw: String, label: String): String {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_CREDENTIAL_FILE_BYTES) { "$label is too large" }
        return raw
    }

    private fun parseObject(raw: String): JsonObject? = raw.takeIf { it.isNotBlank() }?.let {
        runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull()
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun normalizeRegion(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }?.also {
        require(REGION.matches(it)) { "Invalid cloud region" }
    }
    private fun bounded(value: String, label: String, max: Int): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= max) { "$label is invalid" }
    }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private val PROFILE = Regex("[A-Za-z0-9+=,.@_-]{1,128}")
    private val REGION = Regex("[a-z]{2}(?:-gov)?-[a-z]+-\\d")
}

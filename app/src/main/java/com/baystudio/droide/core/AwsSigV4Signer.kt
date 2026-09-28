package com.baystudio.droide.core

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

 
internal object AwsSigV4Signer {
    data class SignedHeaders(
        val authorization: String,
        val amzDate: String,
        val payloadHash: String,
        val securityToken: String?,
    )

    fun sign(
        method: String,
        uri: URI,
        contentType: String?,
        body: ByteArray,
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String?,
        region: String,
        service: String,
        now: Instant = Instant.now(),
    ): SignedHeaders {
        require(method.matches(Regex("[A-Z]+"))) { "Invalid SigV4 HTTP method" }
        require(uri.scheme.equals("https", true)) { "SigV4 target must use HTTPS" }
        val hostName = uri.host ?: error("SigV4 target host is missing")
        require(accessKeyId.isNotBlank() && accessKeyId.length <= 256) { "Invalid AWS access key id" }
        require(secretAccessKey.isNotBlank() && secretAccessKey.length <= 4096) { "Invalid AWS secret access key" }
        require(region.matches(REGION)) { "Invalid AWS region" }
        require(service.matches(SERVICE)) { "Invalid AWS signing service" }

        val amzDate = ISO8601.format(now)
        val dateStamp = DATE_ONLY.format(now)
        val payloadHash = sha256Hex(body)
        val canonicalHost = hostName.lowercase()
        val host = if (isDefaultPort(uri.scheme, uri.port)) canonicalHost else "$canonicalHost:${uri.port}"
        val signed = linkedMapOf(
            "host" to host,
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to amzDate,
        )
        contentType?.takeIf { it.isNotBlank() }?.let { signed["content-type"] = canonicalHeaderValue(it) }
        sessionToken?.takeIf { it.isNotBlank() }?.let { signed["x-amz-security-token"] = canonicalHeaderValue(it) }
        val sortedHeaders = signed.toSortedMap()
        val canonicalHeaders = sortedHeaders.entries.joinToString("") { "${it.key}:${canonicalHeaderValue(it.value)}\n" }
        val signedHeaderNames = sortedHeaders.keys.joinToString(";")
        val canonicalRequest = listOf(
            method,
            canonicalPath(uri.rawPath),
            canonicalQuery(uri.rawQuery.orEmpty()),
            canonicalHeaders,
            signedHeaderNames,
            payloadHash,
        ).joinToString("\n")
        val credentialScope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$credentialScope\n${sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))}"
        val kDate = hmac(("AWS4$secretAccessKey").toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, service)
        val kSigning = hmac(kService, "aws4_request")
        val signature = hmacHex(kSigning, stringToSign)
        val authorization = "AWS4-HMAC-SHA256 Credential=$accessKeyId/$credentialScope, SignedHeaders=$signedHeaderNames, Signature=$signature"
        return SignedHeaders(authorization, amzDate, payloadHash, sessionToken?.takeIf { it.isNotBlank() })
    }

    private fun canonicalPath(rawPath: String?): String {
        val path = rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val output = mutableListOf<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (output.isNotEmpty()) output.removeAt(output.lastIndex)
                else -> output += segment
            }
        }
        val leading = if (path.startsWith('/')) "/" else ""
        val trailing = if (path.endsWith('/') && output.isNotEmpty()) "/" else ""
        return uriEncodePath(leading + output.joinToString("/") + trailing)
    }

    private fun uriEncodePath(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { raw ->
            val byte = raw.toInt() and 0xff
            val safe = byte in 'A'.code..'Z'.code || byte in 'a'.code..'z'.code ||
                byte in '0'.code..'9'.code || byte == '-'.code || byte == '_'.code ||
                byte == '.'.code || byte == '~'.code || byte == '/'.code
            if (safe) append(byte.toChar()) else append("%%%02X".format(byte))
        }
    }

    private fun canonicalQuery(rawQuery: String): String = rawQuery.split('&')
        .filter { it.isNotEmpty() }
        .map { part ->
            val separator = part.indexOf('=')
            if (separator < 0) part to "" else part.substring(0, separator) to part.substring(separator + 1)
        }
        .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        .joinToString("&") { (name, value) -> "$name=$value" }

    private fun canonicalHeaderValue(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    private fun isDefaultPort(scheme: String?, port: Int): Boolean =
        port == -1 || (scheme.equals("https", true) && port == 443) || (scheme.equals("http", true) && port == 80)

    private fun hmac(key: ByteArray, data: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacHex(key: ByteArray, data: String): String = hmac(key, data).toHex()
    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private val REGION = Regex("[a-z]{2}(?:-gov)?-[a-z]+-\\d")
    private val SERVICE = Regex("[a-z0-9-]{1,64}")
    private val ISO8601 = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DATE_ONLY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
}

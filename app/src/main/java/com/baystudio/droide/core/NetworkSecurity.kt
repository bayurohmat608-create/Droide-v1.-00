package com.baystudio.droide.core

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ValidatedNetworkTarget(
    val url: String,
    val host: String,
    val addresses: List<InetAddress>,
    val allowLoopback: Boolean = false,
)

 
object NetworkSecurity {
    
    fun validatePublicHttpsUrl(raw: String): URI {
        val uri = URI(raw.trim())
        require(uri.scheme.equals("https", ignoreCase = true)) { "Only HTTPS URLs are allowed" }
        require(uri.userInfo == null) { "User-info in URLs is not allowed" }
        require(uri.fragment == null) { "URL fragments are not allowed for network requests" }
        val host = uri.host ?: throw IllegalArgumentException("URL host is missing")
        require(!host.equals("localhost", true) && !host.endsWith(".localhost", true)) { "Localhost is blocked" }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid HTTPS port" }
        val canonicalHost = uri.toASCIIString().toHttpUrlOrNull()?.host
            ?: throw IllegalArgumentException("Invalid HTTPS URL")
        require(!canonicalHost.equals("localhost", true) && !canonicalHost.trimEnd('.').endsWith(".localhost", true) &&
            !canonicalHost.trimEnd('.').equals("localhost", true)) { "Localhost is blocked" }
        // OkHttp canonicalizes numeric/IPv6 literals without DNS. Resolve only those literals here;
        // hostname DNS and every resolved address remain checked when a real request is made.
        if (canonicalHost.contains(':') || canonicalHost.all { it in '0'..'9' || it == '.' }) {
            require(!isPrivateOrLocal(InetAddress.getByName(canonicalHost))) { "Private/local network targets are blocked" }
        }
        return uri
    }

    fun validatePublicHttpsTarget(raw: String): ValidatedNetworkTarget {
        val uri = validatePublicHttpsUrl(raw)
        val host = checkNotNull(uri.host)
        val addresses = InetAddress.getAllByName(host).toList()
        require(addresses.isNotEmpty()) { "Host did not resolve" }
        require(addresses.none(::isPrivateOrLocal)) { "Private/local network targets are blocked" }
        return ValidatedNetworkTarget(uri.toASCIIString(), host, addresses)
    }


    fun validateProviderTarget(provider: AiProvider, raw: String): ValidatedNetworkTarget = when (provider.networkScope) {
        ProviderNetworkScope.PUBLIC_HTTPS -> validatePublicHttpsTarget(raw)
        ProviderNetworkScope.LOOPBACK_ONLY -> validateLoopbackTarget(raw)
    }

    private fun validateLoopbackTarget(raw: String): ValidatedNetworkTarget {
        val uri = URI(raw.trim())
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "Loopback provider must use HTTP(S)" }
        require(uri.userInfo == null) { "User-info in URLs is not allowed" }
        require(uri.fragment == null) { "URL fragments are not allowed for network requests" }
        val host = uri.host ?: throw IllegalArgumentException("URL host is missing")
        require(host.equals("localhost", true) || host == "127.0.0.1" || host == "::1" || host == "[::1]" || host == "0:0:0:0:0:0:0:1") {
            "Loopback provider target must stay on localhost"
        }
        val addresses = InetAddress.getAllByName(host).toList()
        require(addresses.isNotEmpty() && addresses.all { it.isLoopbackAddress }) { "Loopback provider resolved outside loopback" }
        return ValidatedNetworkTarget(uri.toASCIIString(), host, addresses, allowLoopback = true)
    }

     
    fun pinnedDns(target: ValidatedNetworkTarget): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname.equals(target.host, ignoreCase = true)) return target.addresses
            val resolved = Dns.SYSTEM.lookup(hostname)
            if (target.allowLoopback) {
                require(resolved.all { it.isLoopbackAddress }) { "Loopback provider DNS escaped localhost" }
            } else {
                require(resolved.none(::isPrivateOrLocal)) { "Private/local network targets are blocked" }
            }
            return resolved
        }
    }

    private fun isPrivateOrLocal(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        if (a is Inet4Address && b.size == 4) {
            val x = b[0].toInt() and 0xff
            val y = b[1].toInt() and 0xff
            val z = b[2].toInt() and 0xff
            if (x == 0 || x == 10 || x == 127) return true
            if (x == 100 && y in 64..127) return true 
            if (x == 169 && y == 254) return true
            if (x == 172 && y in 16..31) return true
            if (x == 192 && y == 168) return true
            if (x == 192 && y == 0 && z == 0) return true   
            if (x == 192 && y == 0 && z == 2) return true   
            if (x == 192 && y == 88 && z == 99) return true 
            if (x == 198 && y in 18..19) return true        
            if (x == 198 && y == 51 && z == 100) return true 
            if (x == 203 && y == 0 && z == 113) return true  
            if (x >= 224) return true
        }
        if (a is Inet6Address && b.isNotEmpty()) {
            val first = b[0].toInt() and 0xff
            val second = if (b.size > 1) b[1].toInt() and 0xff else 0
            if ((first and 0xfe) == 0xfc) return true 
            if (first == 0xff) return true            
            if (first == 0x20 && second == 0x01 && b.size >= 4 && (b[2].toInt() and 0xff) == 0x0d && (b[3].toInt() and 0xff) == 0xb8) return true 
        }
        return false
    }
}

data class SafeHttpResponse(val code: Int, val body: String, val finalUrl: String)

object SafeHttp {
    private val baseClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun get(rawUrl: String, accept: String = "text/html,application/xhtml+xml", maxBytes: Int = 50_000): SafeHttpResponse {
        require(maxBytes in 1..1_000_000) { "Invalid response size limit" }
        var target = NetworkSecurity.validatePublicHttpsTarget(rawUrl)
        repeat(6) { hop ->
            val client = baseClient.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build()
            val req = Request.Builder().url(target.url)
                .header("User-Agent", "Droide/1.00")
                .header("Accept", accept)
                .build()
            client.newCall(req).awaitResponse().use { response ->
                if (response.code in 300..399) {
                    val location = response.header("Location") ?: return SafeHttpResponse(response.code, "", target.url)
                    val base = target.url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid redirect base")
                    val next = base.resolve(location)?.toString() ?: throw IllegalArgumentException("Invalid redirect")
                    target = NetworkSecurity.validatePublicHttpsTarget(next)
                    if (hop == 5) throw IllegalStateException("Too many redirects")
                } else {
                    val source = response.body?.source()
                    val body = if (source == null) "" else {
                        val probe = maxBytes.toLong() + 1L
                        source.request(probe)
                        require(source.buffer.size <= maxBytes.toLong()) { "HTTP response exceeds $maxBytes bytes" }
                        source.buffer.clone().readUtf8(source.buffer.size)
                    }
                    return SafeHttpResponse(response.code, body, target.url)
                }
            }
        }
        throw IllegalStateException("Too many redirects")
    }
}

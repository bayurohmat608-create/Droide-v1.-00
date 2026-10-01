package com.baystudio.droide.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.StringReader
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

// It stores only the hash of the exact license text accepted by the user.
private val Context.androidSdkLicensePreferences by preferencesDataStore(name = "android_sdk_license")

data class AndroidSdkLicense(
    val text: String,
    val sha256: String,
)








class AndroidSdkLicenseManager(context: Context) {
    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun fetchCurrent(): AndroidSdkLicense = withContext(Dispatchers.IO) {
        var target = NetworkSecurity.validatePublicHttpsTarget(REPOSITORY_URL)
        repeat(MAX_REDIRECTS + 1) { hop ->
            val request = Request.Builder()
                .url(target.url)
                .header("User-Agent", "Droide/1.00")
                .header("Accept", "application/xml,text/xml")
                .build()
            val hopClient = client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build()
            hopClient.newCall(request).awaitResponse().use { response ->
                if (response.code in 300..399) {
                    require(hop < MAX_REDIRECTS) { "Too many Android repository redirects" }
                    val location = response.header("Location") ?: error("Android repository redirect has no Location header")
                    val base = target.url.toHttpUrlOrNull() ?: error("Invalid Android repository redirect base")
                    val next = base.resolve(location)?.toString() ?: error("Invalid Android repository redirect")
                    target = NetworkSecurity.validatePublicHttpsTarget(next)
                    return@use
                }
                check(response.isSuccessful) { "Android repository metadata failed: HTTP ${response.code}" }
                val body = response.body ?: error("Android repository metadata is empty")
                val declared = body.contentLength()
                require(declared < 0L || declared <= MAX_REPOSITORY_BYTES) { "Android repository metadata is too large" }
                val source = body.source()
                source.request(MAX_REPOSITORY_BYTES + 1L)
                require(source.buffer.size <= MAX_REPOSITORY_BYTES) { "Android repository metadata is too large" }
                val xml = source.buffer.clone().readUtf8(source.buffer.size)
                val text = parseLicense(xml).trim()
                require(text.length in MIN_LICENSE_CHARS..MAX_LICENSE_CHARS) { "Android SDK license text is invalid" }
                return@withContext AndroidSdkLicense(text, sha256(text))
            }
        }
        error("Android repository redirect limit exceeded")
    }

    suspend fun isAccepted(license: AndroidSdkLicense): Boolean {
        require(license.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid Android SDK license hash" }
        return appContext.androidSdkLicensePreferences.data.first()[ACCEPTED_HASH] == license.sha256
    }

    suspend fun accept(license: AndroidSdkLicense) {
        require(license.text.isNotBlank()) { "Android SDK license is empty" }
        require(sha256(license.text) == license.sha256) { "Android SDK license hash mismatch" }
        appContext.androidSdkLicensePreferences.edit { prefs -> prefs[ACCEPTED_HASH] = license.sha256 }
    }

    suspend fun revoke() {
        appContext.androidSdkLicensePreferences.edit { prefs -> prefs.remove(ACCEPTED_HASH) }
    }

    private fun parseLicense(xml: String): String {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(StringReader(xml))
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "license" && parser.getAttributeValue(null, "id") == LICENSE_ID) {
                return parser.nextText()
            }
            event = parser.next()
        }
        error("Android SDK license was not found in repository metadata")
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val REPOSITORY_URL = "https://dl.google.com/android/repository/repository2-3.xml"
        private const val LICENSE_ID = "android-sdk-license"
        private const val MAX_REDIRECTS = 3
        private const val MAX_REPOSITORY_BYTES = 8L * 1024L * 1024L
        private const val MIN_LICENSE_CHARS = 2_000
        private const val MAX_LICENSE_CHARS = 100_000
        private val ACCEPTED_HASH = stringPreferencesKey("accepted_android_sdk_license_sha256")
    }
}

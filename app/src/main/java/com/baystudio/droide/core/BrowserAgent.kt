package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

 
class BrowserAgent {
    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        try {
            val r = SafeHttp.get(url, maxBytes = 50_000)
            if (r.code !in 200..299) return@withContext "HTTP ${r.code}: ${r.body.take(500)}"
            val doc = org.jsoup.Jsoup.parse(r.body)
            doc.select("script, style, noscript").remove()
            val text = doc.text().replace(Regex("\\s+"), " ").trim().take(8_000)
            "Browser fetched ${r.finalUrl}\nTitle: ${doc.title()}\nText: $text"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            "Browser error: ${e.message}"
        }
    }
}

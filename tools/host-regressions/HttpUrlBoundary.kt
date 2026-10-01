package okhttp3


class HttpUrl private constructor(private val url: String) {
    val host: String get() = java.net.URI(url).host
    fun newBuilder() = Builder(url)
    override fun toString() = url
    class Builder(private val base: String) {
        private val parts = mutableListOf<String>()
        fun addPathSegment(part: String): Builder {
            parts += java.net.URLEncoder.encode(part, "UTF-8").replace("+", "%20")
            return this
        }
        fun build() = HttpUrl(base.trimEnd('/') + "/" + parts.joinToString("/"))
    }
    companion object { fun String.toHttpUrl() = HttpUrl(this) }
}

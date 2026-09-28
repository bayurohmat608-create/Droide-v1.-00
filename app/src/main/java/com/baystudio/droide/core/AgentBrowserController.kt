package com.baystudio.droide.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume









class AgentBrowserController(
    private val projectRoot: File,
) {
    data class Status(
        val attached: Boolean = false,
        val loading: Boolean = false,
        val url: String = "",
        val title: String = "",
        val lastError: String? = null,
        val console: List<String> = emptyList(),
    )

    data class Binding internal constructor(val generation: Long)

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var openGeneration = 0L
    private var viewGeneration = 0L
    private var webView: WebView? = null
    private var navigationState: Bundle? = null
    private var requestOpen: (() -> Unit)? = null
    private var status = Status()
    private val consoleLines = ArrayDeque<String>()
    @Volatile private var allowLoopbackRequests = false

    fun bindOpenRequest(open: () -> Unit): Binding = synchronized(lock) {
        openGeneration += 1L
        requestOpen = open
        Binding(openGeneration)
    }

    fun unbindOpenRequest(binding: Binding) = synchronized(lock) {
        if (binding.generation == openGeneration) requestOpen = null
    }

    fun attach(view: WebView): Binding = synchronized(lock) {
        navigationState?.let { saved ->
            if (view.restoreState(saved) != null) navigationState = null
        }
        viewGeneration += 1L
        webView = view
        status = status.copy(attached = true, loading = false)
        Binding(viewGeneration)
    }

    fun detach(binding: Binding, view: WebView) = synchronized(lock) {
        if (binding.generation == viewGeneration && webView === view) {
            val saved = Bundle()
            if (view.saveState(saved)?.size?.let { it > 0 } == true) navigationState = saved
            webView = null
            status = status.copy(attached = false, loading = false)
        }
    }

    fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            blockNetworkLoads = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        if (android.os.Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Agent browsing never gains camera/mic/device capabilities implicitly.
                request.deny()
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, false, false)
            }
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                recordConsole("${message.messageLevel()}: ${message.message()} @${message.lineNumber()}")
                return true
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val raw = request.url.toString()
                if (!quickTopLevelAllowed(raw, allowLoopbackRequests)) {
                    recordError("Blocked navigation outside public HTTPS/approved loopback: ${request.url.host.orEmpty()}")
                    return true
                }
                return false
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                !quickTopLevelAllowed(url, allowLoopbackRequests)

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                // POST/XHR/fetch must not become a private-network escape hatch just because only GET was preflighted.

                return if (networkRequestAllowed(url, allowLoopbackRequests)) {
                    null
                } else {
                    WebResourceResponse("text/plain", "utf-8", 403, "Blocked by Droide", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                synchronized(lock) {
                    status = status.copy(loading = true, url = url.orEmpty(), lastError = null)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                synchronized(lock) {
                    status = status.copy(loading = false, url = url.orEmpty(), title = view.title.orEmpty())
                }
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                recordError("${description ?: "WebView error"} ($errorCode) ${failingUrl.orEmpty()}")
            }
        }
    }

    suspend fun execute(
        operation: String,
        url: String? = null,
        target: String? = null,
        text: String? = null,
        submit: Boolean = false,
        waitMs: Long = 500,
        allowLoopback: Boolean = false,
    ): String {
        requestSurface()
        val view = awaitView()
        allowLoopbackRequests = allowLoopback
        return when (operation) {
            "status" -> renderStatus()
            "open", "navigate" -> {
                val raw = requireNotNull(url?.trim()?.takeIf { it.isNotEmpty() }) { "browser open requires url" }
                validateTopLevel(raw, allowLoopback)
                onMain { view.loadUrl(raw) }
                awaitSettled(waitMs.coerceAtLeast(250))
                readPage(view)
            }
            "read" -> readPage(view)
            "click" -> {
                val selector = requireNotNull(target?.takeIf { it.isNotBlank() }) { "browser click requires target" }
                val result = eval(view, interactionScript("click", selector, null, false))
                require(!result.contains("\"ok\":false")) { "Browser click failed: $result" }
                delay(waitMs.coerceIn(100, 5_000))
                "$result\n${readPage(view)}"
            }
            "type" -> {
                val selector = requireNotNull(target?.takeIf { it.isNotBlank() }) { "browser type requires target" }
                val value = requireNotNull(text) { "browser type requires text" }
                require(value.length <= 20_000) { "browser input is too large" }
                val result = eval(view, interactionScript("type", selector, value, submit))
                require(!result.contains("\"ok\":false")) { "Browser type failed: $result" }
                delay(waitMs.coerceIn(100, 5_000))
                "$result\n${readPage(view)}"
            }
            "back" -> {
                onMain { if (view.canGoBack()) view.goBack() }
                awaitSettled(waitMs.coerceAtLeast(250)); readPage(view)
            }
            "reload" -> {
                onMain { view.reload() }
                awaitSettled(waitMs.coerceAtLeast(250)); readPage(view)
            }
            "wait" -> {
                delay(waitMs.coerceIn(50, 10_000)); readPage(view)
            }
            "screenshot" -> screenshot(view)
            else -> "ERROR: unsupported browser operation: $operation"
        }
    }

    private suspend fun requestSurface() {
        val open = synchronized(lock) { requestOpen }
            ?: error("Integrated browser UI is not bound. Open the active workspace before using browser automation.")
        onMain { open() }
    }

    private suspend fun awaitView(): WebView {
        repeat(30) {
            synchronized(lock) { webView }?.let { return it }
            delay(100)
        }
        error("Integrated browser surface did not become available")
    }

    private suspend fun awaitSettled(minWaitMs: Long) {
        delay(minWaitMs.coerceAtMost(2_000))
        repeat(80) {
            if (!synchronized(lock) { status.loading }) return
            delay(100)
        }
    }

    private suspend fun readPage(view: WebView): String {
        val payload = eval(view, DOM_SNAPSHOT_JS)
        val stat = synchronized(lock) { status }
        return buildString {
            append("BROWSER_PAGE\n")
            append("url=").append(stat.url.ifBlank { view.url.orEmpty() }).append('\n')
            append("title=").append(stat.title.ifBlank { view.title.orEmpty() }).append('\n')
            append("loading=").append(stat.loading).append('\n')
            stat.lastError?.let { append("last_error=").append(it.take(600)).append('\n') }
            append("snapshot=").append(payload.take(20_000))
            if (stat.console.isNotEmpty()) {
                append("\nconsole:\n")
                stat.console.takeLast(20).forEach { append("- ").append(it.take(500)).append('\n') }
            }
        }.take(24_000)
    }

    private suspend fun screenshot(view: WebView): String {
        val bitmap = onMainResult {
            val width = view.width.coerceAtLeast(1)
            val height = view.height.coerceAtLeast(1)
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(out))
            out
        }
        val dir = PathSecurity.resolveWithin(projectRoot, ".droide/browser")
        withContext(Dispatchers.IO) { if (!dir.isDirectory) check(dir.mkdirs() || dir.isDirectory) }
        val file = File(dir, "agent-browser-${System.currentTimeMillis()}.png")
        withContext(Dispatchers.IO) {
            FileOutputStream(file).use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 92, out)) }
            bitmap.recycle()
        }
        val rel = projectRoot.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
        return "BROWSER_SCREENSHOT path=$rel width=${view.width} height=${view.height} note=Screenshot captures the visible Android WebView viewport. DOM/accessibility text remains the model-readable verification channel unless the selected provider supports image input."
    }

    fun currentUrl(): String = synchronized(lock) { status.url.ifBlank { webView?.url.orEmpty() } }

    private fun renderStatus(): String {
        val s = synchronized(lock) { status }
        return "BROWSER_STATUS attached=${s.attached} loading=${s.loading} url=${s.url} title=${s.title} error=${s.lastError.orEmpty()}"
    }

    private suspend fun validateTopLevel(raw: String, allowLoopback: Boolean) {
        val uri = URI(raw)
        if (isLoopback(uri.host)) {
            require(allowLoopback) { "Loopback browser navigation requires explicit browser_local permission" }
            require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "Loopback browser requires HTTP(S)" }
            return
        }
        
        val preflight = URI(uri.scheme, uri.userInfo, uri.host, uri.port, uri.path, uri.query, null).toString()
        withContext(Dispatchers.IO) { NetworkSecurity.validatePublicHttpsTarget(preflight) }
    }

    private fun quickTopLevelAllowed(raw: String, allowLoopback: Boolean): Boolean = runCatching {
        val uri = URI(raw)
        if (isLoopback(uri.host)) {
            allowLoopback && (uri.scheme.equals("http", true) || uri.scheme.equals("https", true))
        } else uri.scheme.equals("https", true) && uri.userInfo == null && uri.host != null
    }.getOrDefault(false)

    private fun networkRequestAllowed(raw: String, allowLoopback: Boolean): Boolean = runCatching {
        val uri = URI(raw)
        val host = uri.host ?: return@runCatching false
        if (isLoopback(host)) return@runCatching allowLoopback && (uri.scheme.equals("http", true) || uri.scheme.equals("https", true))
        if (!uri.scheme.equals("https", true)) return@runCatching false
        NetworkSecurity.validatePublicHttpsTarget(raw)
        true
    }.getOrDefault(false)

    private fun isLoopback(host: String?): Boolean = host != null && (
        host.equals("localhost", true) || host == "127.0.0.1" || host == "::1" || host == "[::1]" ||
            runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
        )

    private fun recordConsole(line: String) = synchronized(lock) {
        consoleLines.addLast(line.take(1_000))
        while (consoleLines.size > 40) consoleLines.removeFirst()
        status = status.copy(console = consoleLines.toList())
    }

    private fun recordError(message: String) = synchronized(lock) {
        status = status.copy(lastError = message.take(1_000), loading = false)
    }

    private suspend fun eval(view: WebView, script: String): String = suspendCancellableCoroutine { cont ->
        main.post {
            runCatching {
                view.evaluateJavascript(script) { raw ->
                    if (!cont.isActive) return@evaluateJavascript
                    val unwrapped = runCatching {
                        val element = Json.parseToJsonElement(raw ?: "null")
                        if (element is JsonPrimitive && element.isString) element.contentOrNull ?: raw else raw
                    }.getOrDefault(raw ?: "null")
                    cont.resume(unwrapped)
                }
            }.onFailure { if (cont.isActive) cont.resume("ERROR: ${it.message}") }
        }
    }

    private suspend fun onMain(block: () -> Unit) = suspendCancellableCoroutine { cont ->
        main.post {
            runCatching(block).fold(
                onSuccess = { if (cont.isActive) cont.resume(Unit) },
                onFailure = { if (cont.isActive) cont.resumeWith(Result.failure(it)) },
            )
        }
    }

    private suspend fun <T> onMainResult(block: () -> T): T = suspendCancellableCoroutine { cont ->
        main.post {
            runCatching(block).fold(
                onSuccess = { if (cont.isActive) cont.resume(it) },
                onFailure = { if (cont.isActive) cont.resumeWith(Result.failure(it)) },
            )
        }
    }

    private fun interactionScript(operation: String, target: String, text: String?, submit: Boolean): String {
        val targetJson = JsonPrimitive(target).toString()
        val textJson = JsonPrimitive(text ?: "").toString()
        return """
            (() => {
              const t = $targetJson;
              let el = [...document.querySelectorAll('[data-droide-agent-id]')]
                .find(e => e.getAttribute('data-droide-agent-id') === t) || null;
              if (!el) { try { el = document.querySelector(t); } catch (_) {} }
              if (!el) return JSON.stringify({ok:false,error:'target not found',target:t});
              if ('$operation' === 'click') {
                el.scrollIntoView({block:'center',inline:'center'}); el.click();
                return JSON.stringify({ok:true,action:'click',target:t});
              }
              const v = $textJson;
              const inputType = (el.getAttribute?.('type') || '').toLowerCase();
              if (inputType === 'password' || inputType === 'file') {
                return JSON.stringify({ok:false,error:inputType + ' fields require direct human interaction',target:t});
              }
              el.focus();
              if ('value' in el) {
                const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                if (setter) setter.call(el, v); else el.value = v;
              } else if (el.isContentEditable) {
                el.textContent = v;
              } else return JSON.stringify({ok:false,error:'target is not typable',target:t});
              el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:v}));
              el.dispatchEvent(new Event('change',{bubbles:true}));
              if (${if (submit) "true" else "false"}) {
                if (el.form?.requestSubmit) el.form.requestSubmit();
                else el.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',bubbles:true}));
              }
              return JSON.stringify({ok:true,action:'type',target:t,length:v.length,submit:${if (submit) "true" else "false"}});
            })()
        """.trimIndent()
    }

    companion object {
        private val DOM_SNAPSHOT_JS = """
            (() => {
              window.__droideAgentElementSeq = Number(window.__droideAgentElementSeq || 0);
              const visible = e => {
                const r = e.getBoundingClientRect(), s = getComputedStyle(e);
                return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
              };
              const els = [...document.querySelectorAll('a,button,input,textarea,select,[role="button"],[role="link"],[contenteditable="true"]')]
                .filter(visible).slice(0,120).map(e => {
                  let id = e.getAttribute('data-droide-agent-id');
                  if (!id) { id = `d${'$'}{++window.__droideAgentElementSeq}`; e.setAttribute('data-droide-agent-id', id); }
                  return {id,tag:e.tagName.toLowerCase(),role:e.getAttribute('role')||'',type:e.getAttribute('type')||'',name:e.getAttribute('aria-label')||e.getAttribute('name')||'',text:(e.innerText||e.value||e.textContent||'').trim().slice(0,240),disabled:!!e.disabled};
                });
              return JSON.stringify({url:location.href,title:document.title,text:(document.body?.innerText||'').replace(/\\s+/g,' ').trim().slice(0,12000),elements:els});
            })()
        """.trimIndent()
    }
}

package com.baystudio.droide.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.baystudio.droide.core.AgentBrowserController
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction








@Composable
fun BrowserPane(
    url: String = "https://github.com",
    onUrlChange: (String) -> Unit = {},
    agentBrowser: AgentBrowserController,
) {
    val scope = rememberCoroutineScope()
    var inputUrl by rememberSaveable(url) { mutableStateOf(url) }
    var status by rememberSaveable { mutableStateOf("Ready") }
    var loading by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val owner = remember { Any() }
    var browserBinding by remember { mutableStateOf<AgentBrowserController.Binding?>(null) }

    fun navigate(raw: String, operation: String = "open") {
        if (loading) return
        loading = true
        scope.launch {
            status = "Loading…"
            try {
                val result = agentBrowser.execute(operation, url = raw.trim(), allowLoopback = true, waitMs = 350)
                val current = agentBrowser.currentUrl().ifBlank { raw.trim() }
                inputUrl = current
                onUrlChange(current)
                status = result.lineSequence().firstOrNull { it.startsWith("title=") }
                    ?.removePrefix("title=")?.takeIf { it.isNotBlank() } ?: current
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                status = "Blocked: browser operation timed out"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                status = "Blocked: ${failure.message ?: "navigation failed"}"
            } finally { loading = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = { navigate(inputUrl, "back") }, enabled = !loading) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            OutlinedTextField(
                inputUrl,
                { inputUrl = it.take(2_000) },
                Modifier.weight(1f),
                placeholder = { Text("https://… or http://localhost:…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { navigate(inputUrl) }),
            )
            IconButton(onClick = { navigate(inputUrl, "reload") }, enabled = !loading) {
                Icon(Icons.Default.Refresh, contentDescription = "Reload")
            }
            Button(onClick = { navigate(inputUrl) }, enabled = !loading) { Text(if (loading) "…" else "Go") }
        }
        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            color = if (status.startsWith("Blocked:")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            maxLines = 1,
        )
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webView = this
                    agentBrowser.configure(this)
                    browserBinding = agentBrowser.attach(this)
                }
            },
            update = { webView = it },
            modifier = Modifier.fillMaxSize(),
        )
    }

    LaunchedEffect(webView) {
        if (webView != null && webView?.copyBackForwardList()?.size == 0 && url.isNotBlank()) navigate(url)
    }

    DisposableEffect(owner) {
        onDispose {
            val view = webView
            val binding = browserBinding
            if (view != null && binding != null) agentBrowser.detach(binding, view)
            view?.apply {
                stopLoading()
                removeAllViews()
                destroy()
            }
            webView = null
            browserBinding = null
        }
    }
}

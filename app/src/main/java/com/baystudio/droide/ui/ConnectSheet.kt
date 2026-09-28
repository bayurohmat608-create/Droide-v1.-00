package com.baystudio.droide.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.BuildConfig
import com.baystudio.droide.core.ProviderAuthMethodDescriptor
import com.baystudio.droide.core.ProviderAuthMethodKind
import com.baystudio.droide.core.ProviderConnectionBackend
import com.baystudio.droide.core.ModelsDevCatalog
import com.baystudio.droide.core.ProviderRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch






@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectSheet(
    connectionBackend: ProviderConnectionBackend,
    initialProviderId: String? = null,
    githubOAuthClientId: String = BuildConfig.GITHUB_OAUTH_CLIENT_ID,
    onConnected: (providerId: String, validatedModel: String) -> Unit,
    onDisconnected: (providerId: String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val backendRevision by connectionBackend.revision.collectAsState()
    val catalogRevision by ModelsDevCatalog.revision.collectAsState()
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val twoPane = landscape && configuration.screenWidthDp >= 600
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var query by rememberSaveable { mutableStateOf("") }
    var selectedProviderId by rememberSaveable(initialProviderId) {
        mutableStateOf(initialProviderId?.takeIf { ProviderRegistry.findById(it) != null })
    }
    var selectedMethodId by rememberSaveable { mutableStateOf<String?>(null) }
    var credential by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var validatedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelQuery by rememberSaveable { mutableStateOf("") }
    var selectedModel by rememberSaveable { mutableStateOf<String?>(null) }
    var oauthChallenge by remember { mutableStateOf<ProviderConnectionBackend.OAuthChallenge?>(null) }
    var oauthJob by remember { mutableStateOf<Job?>(null) }
    var oauthRemainingSeconds by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) { onDispose { oauthJob?.cancel() } }
    DisposableEffect(oauthChallenge?.sessionId) {
        val sessionId = oauthChallenge?.sessionId
        onDispose { sessionId?.let(connectionBackend::cancelOAuth) }
    }
    LaunchedEffect(oauthChallenge?.sessionId) {
        val challenge = oauthChallenge ?: run { oauthRemainingSeconds = 0L; return@LaunchedEffect }
        val deadline = SystemClock.elapsedRealtime() + challenge.expiresInSeconds.coerceIn(1, 1800) * 1_000L
        while (oauthChallenge?.sessionId == challenge.sessionId) {
            val remaining = ((deadline - SystemClock.elapsedRealtime() + 999L) / 1_000L).coerceAtLeast(0L)
            oauthRemainingSeconds = remaining
            if (remaining <= 0L) {
                connectionBackend.cancelOAuth(challenge.sessionId)
                oauthJob?.cancel()
                oauthJob = null
                oauthChallenge = null
                busy = false
                error = "GitHub login code expired. Start a new login."
                break
            }
            delay(1_000L)
        }
    }

    LaunchedEffect(catalogRevision, initialProviderId) {
        val requested = initialProviderId?.takeIf { ProviderRegistry.findById(it) != null }
        when {
            selectedProviderId == null && requested != null -> selectedProviderId = requested
            selectedProviderId != null && ProviderRegistry.findById(selectedProviderId!!) == null -> selectedProviderId = requested
        }
    }

    val providers = remember(query, backendRevision, catalogRevision) {
        ProviderRegistry.all
            .filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) }
            .sortedWith(compareByDescending<com.baystudio.droide.core.AiProvider> {
                connectionBackend.snapshot(it.id).status == ProviderConnectionBackend.Status.CONNECTED
            }.thenBy { it.name.lowercase() })
    }
    val selectedProvider = selectedProviderId?.let(ProviderRegistry::findById)
    val authMethods = remember(selectedProviderId, backendRevision, catalogRevision) {
        selectedProviderId?.let { runCatching { connectionBackend.authMethods(it) }.getOrDefault(emptyList()) }.orEmpty()
    }
    val selectedMethod = authMethods.firstOrNull { it.id == selectedMethodId }
    val snapshot = remember(selectedProviderId, backendRevision) {
        selectedProviderId?.let(connectionBackend::snapshot)
    }
    val visibleModels = remember(validatedModels, modelQuery) {
        validatedModels.asSequence()
            .filter { modelQuery.isBlank() || it.contains(modelQuery, ignoreCase = true) }
            .take(500)
            .toList()
    }

    fun preferredMethodId(methods: List<ProviderAuthMethodDescriptor>, currentMethodId: String? = null): String? =
        currentMethodId?.takeIf { current -> methods.any { it.id == current } }
            ?: methods.firstOrNull { it.id == "device-oauth" && githubOAuthClientId.isNotBlank() }?.id
            ?: methods.firstOrNull()?.id

    fun openGitHubVerification(challenge: ProviderConnectionBackend.OAuthChallenge): Boolean =
        runCatching {
            uriHandler.openUri(challenge.verificationUri)
            true
        }.getOrDefault(false)

    fun oauthFailureMessage(reason: String): String = when (reason) {
        "oauth_session_expired" -> "GitHub login expired. Start a new login."
        "oauth_session_not_found" -> "GitHub login session ended. Start a new login."
        "access_denied" -> "GitHub login was cancelled or denied."
        "device_flow_disabled" -> "GitHub device login is not enabled for Droide's registered OAuth application."
        "incorrect_client_credentials" -> "Droide's configured GitHub OAuth client ID was rejected."
        else -> "GitHub login failed: ${reason.take(160)}"
    }

    fun clearTransient(keepModels: Boolean = false) {
        oauthJob?.cancel()
        oauthJob = null
        oauthChallenge = null
        credential = ""
        error = null
        info = null
        if (!keepModels) {
            validatedModels = emptyList()
            selectedModel = null
            modelQuery = ""
        }
    }

    fun selectProvider(id: String) {
        selectedProviderId = id
        val current = connectionBackend.snapshot(id)
        val methods = runCatching { connectionBackend.authMethods(id) }.getOrDefault(emptyList())
        selectedMethodId = preferredMethodId(methods, current.authMethodId)
        clearTransient()
    }

    LaunchedEffect(selectedProviderId, authMethods, snapshot?.authMethodId) {
        if (selectedProviderId != null && (selectedMethodId == null || authMethods.none { it.id == selectedMethodId })) {
            selectedMethodId = preferredMethodId(authMethods, snapshot?.authMethodId)
        }
    }

    fun acceptConnected(providerId: String, connected: ProviderConnectionBackend.Connected) {
        validatedModels = connected.models.distinct().sorted()
        selectedModel = connected.models.firstOrNull { it == ProviderRegistry.byId(providerId).model }
            ?: connected.models.firstOrNull()
        credential = ""
        error = null
        info = "Connected with ${connected.snapshot.authMethodId ?: "provider authentication"}. Choose a model to use."
    }

    fun runConnect(method: ProviderAuthMethodDescriptor) {
        val id = selectedProviderId ?: return
        busy = true
        error = null
        info = null
        scope.launch {
            val result = connectionBackend.connect(id, method.id, credential)
            busy = false
            result.onSuccess { acceptConnected(id, it) }
                .onFailure { error = it.message ?: "Could not connect provider." }
        }
    }

    fun beginDeviceOAuth() {
        val id = selectedProviderId ?: return
        if (githubOAuthClientId.isBlank()) {
            error = "GitHub device login is not configured. Use a GitHub OAuth token for now."
            return
        }
        busy = true
        error = null
        info = null
        oauthJob?.cancel()
        oauthJob = scope.launch {
            val started = connectionBackend.beginGitHubDeviceOAuth(id, githubOAuthClientId)
            val challenge = started.getOrElse {
                busy = false
                error = it.message ?: "Could not start GitHub login."
                return@launch
            }
            oauthChallenge = challenge
            busy = false
            info = if (openGitHubVerification(challenge)) {
                "GitHub opened in your browser. Approve access, then return to Droide."
            } else {
                "Login started. Tap Open GitHub to continue in your browser."
            }
            while (true) {
                when (val polled = connectionBackend.pollGitHubDeviceOAuth(challenge.sessionId)) {
                    is ProviderConnectionBackend.OAuthPoll.Pending -> delay(polled.retryAfterSeconds.coerceIn(1, 60) * 1_000L)
                    is ProviderConnectionBackend.OAuthPoll.Success -> {
                        oauthChallenge = null
                        acceptConnected(id, polled.connected)
                        return@launch
                    }
                    is ProviderConnectionBackend.OAuthPoll.Failed -> {
                        oauthChallenge = null
                        error = oauthFailureMessage(polled.reason)
                        return@launch
                    }
                }
            }
        }
    }

    val providerPane: @Composable ColumnScope.() -> Unit = {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(160) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search provider…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
        )
        LazyColumn(
            modifier = if (twoPane) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().heightIn(max = 220.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(providers, key = { it.id }) { provider ->
                val connected = connectionBackend.snapshot(provider.id).status == ProviderConnectionBackend.Status.CONNECTED
                ListItem(
                    headlineContent = { ProviderIdentityLabel(provider.id, provider.name, iconSize = 20.dp) },
                    supportingContent = {
                        Text(
                            if (connected) "Connected" else provider.note,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (connected) DroideColors.Success else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (connected) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        when {
                            selectedProviderId == provider.id -> Icon(Icons.Default.Check, "Selected provider", tint = MaterialTheme.colorScheme.primary)
                            connected -> Icon(Icons.Default.CheckCircle, "Connected", tint = DroideColors.Success, modifier = Modifier.size(18.dp))
                            else -> Unit
                        }
                    },
                    modifier = Modifier.clickable { selectProvider(provider.id) },
                )
                HorizontalDivider(color = DroideColors.Border.copy(alpha = .65f))
            }
        }
    }

    val detailPane: @Composable ColumnScope.() -> Unit = detail@{
        val provider = selectedProvider
        if (provider == null) {
            Box(if (twoPane) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                Text("Choose a provider to continue", style = MaterialTheme.typography.bodyMedium, color = DroideColors.Muted)
            }
            return@detail
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ProviderIdentityLabel(provider.id, provider.name, iconSize = 22.dp)
                Text(
                    if (snapshot?.status == ProviderConnectionBackend.Status.CONNECTED) "Connected · ${snapshot.authMethodId}" else "Choose how Droide should authenticate",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (snapshot?.status == ProviderConnectionBackend.Status.CONNECTED) DroideColors.Success else DroideColors.Muted,
                )
            }
            if (snapshot?.status == ProviderConnectionBackend.Status.CONNECTED) {
                Icon(Icons.Default.CheckCircle, "Connected", tint = DroideColors.Success)
            }
        }

        if (authMethods.size > 1) {
            Text("Authentication", style = MaterialTheme.typography.labelMedium, color = DroideColors.Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                authMethods.forEach { method ->
                    FilterChip(
                        selected = selectedMethodId == method.id,
                        onClick = {
                            selectedMethodId = method.id
                            clearTransient()
                        },
                        label = { Text(method.label) },
                    )
                }
            }
        }

        selectedMethod?.let { method ->
            when {
                method.id == "device-oauth" -> {
                    Surface(color = DroideColors.Surface2, shape = MaterialTheme.shapes.small) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("GitHub device login", style = MaterialTheme.typography.labelLarge)
                            oauthChallenge?.let { challenge ->
                                Text(challenge.verificationUri, style = MaterialTheme.typography.bodySmall, color = DroideColors.Primary)
                                Text("Code: ${challenge.userCode}", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Waiting for authorization · expires in ${formatDuration(oauthRemainingSeconds)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DroideColors.Muted,
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = {
                                        if (openGitHubVerification(challenge)) {
                                            info = "GitHub opened. Approve access, then return to Droide."
                                            error = null
                                        } else {
                                            error = "Could not open the GitHub verification page. You can copy the URL and code manually."
                                        }
                                    }) { Text("Open GitHub") }
                                    OutlinedButton(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        clipboard?.setPrimaryClip(ClipData.newPlainText("GitHub device code", challenge.userCode))
                                        info = "Verification code copied."
                                    }) { Text("Copy code") }
                                    TextButton(onClick = {
                                        oauthJob?.cancel()
                                        oauthJob = null
                                        connectionBackend.cancelOAuth(challenge.sessionId)
                                        oauthChallenge = null
                                        busy = false
                                        info = "Login cancelled."
                                    }) { Text("Cancel login") }
                                }
                            } ?: run {
                                Text(
                                    if (githubOAuthClientId.isBlank()) "GitHub device login is not configured in this build."
                                    else "GitHub sign-in opens in your browser. Droide never asks for your password.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DroideColors.Muted,
                                )
                                Button(onClick = ::beginDeviceOAuth, enabled = !busy && githubOAuthClientId.isNotBlank()) {
                                    Icon(Icons.Default.Login, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Continue with GitHub")
                                }
                            }
                        }
                    }
                }
                method.kind == ProviderAuthMethodKind.DEFAULT_CHAIN -> {
                    Text(
                        "Uses the provider's default credential chain. Nothing is copied into the project.",
                        style = MaterialTheme.typography.labelSmall,
                        color = DroideColors.Muted,
                    )
                }
                method.kind == ProviderAuthMethodKind.NONE -> {
                    Text("No credential required. The endpoint is checked before saving.", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                }
                method.storesSecret -> {
                    OutlinedTextField(
                        value = credential,
                        onValueChange = { credential = it.take(262_144); error = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(credentialLabel(method)) },
                        placeholder = { Text(if (method.kind == ProviderAuthMethodKind.CREDENTIAL_BUNDLE) "Paste credential JSON / bundle" else "••••••••") },
                        singleLine = method.kind != ProviderAuthMethodKind.CREDENTIAL_BUNDLE,
                        minLines = if (method.kind == ProviderAuthMethodKind.CREDENTIAL_BUNDLE) 3 else 1,
                        maxLines = if (method.kind == ProviderAuthMethodKind.CREDENTIAL_BUNDLE) 6 else 1,
                        visualTransformation = if (method.kind == ProviderAuthMethodKind.API_KEY || method.id == "github-token") PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    )
                }
            }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        info?.let { Text(it, color = DroideColors.Success, style = MaterialTheme.typography.labelSmall) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val method = selectedMethod
            if (snapshot?.status == ProviderConnectionBackend.Status.CONNECTED) {
                OutlinedButton(
                    onClick = {
                        busy = true; error = null; info = null
                        scope.launch {
                            val result = connectionBackend.revalidate(provider.id)
                            busy = false
                            result.onSuccess { acceptConnected(provider.id, it) }
                                .onFailure { error = it.message ?: "Could not revalidate provider." }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Icon(Icons.Default.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Refresh") }
                OutlinedButton(
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            val result = connectionBackend.disconnect(provider.id)
                            busy = false
                            result.onSuccess {
                                                        clearTransient()
                                selectedMethodId = authMethods.firstOrNull()?.id
                                onDisconnected(provider.id)
                                info = "Disconnected from ${provider.name}."
                            }.onFailure { error = it.message ?: "Could not disconnect provider." }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Icon(Icons.Default.LinkOff, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Disconnect") }
            } else if (method != null && method.id != "device-oauth") {
                Button(
                    onClick = { runConnect(method) },
                    enabled = !busy && (!method.storesSecret || credential.isNotBlank()),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect & validate") }
            }
        }
        if (snapshot?.status == ProviderConnectionBackend.Status.CONNECTED && selectedMethod != null && selectedMethod.id != "device-oauth" &&
            (selectedMethod.id != snapshot.authMethodId || (selectedMethod.storesSecret && credential.isNotBlank()))) {
            Button(
                onClick = { runConnect(selectedMethod) },
                enabled = !busy && (!selectedMethod.storesSecret || credential.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Replace connection") }
        }

        if (validatedModels.isNotEmpty()) {
            HorizontalDivider(color = DroideColors.Border)
            OutlinedTextField(
                value = modelQuery,
                onValueChange = { modelQuery = it.take(200) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search models") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
            )
            LazyColumn(
                modifier = if (twoPane) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().heightIn(max = 220.dp),
            ) {
                items(visibleModels, key = { it }) { model ->
                    ListItem(
                        headlineContent = { ModelIdentityLabel(provider.id, model, iconSize = 19.dp) },
                        trailingContent = { if (selectedModel == model) Icon(Icons.Default.Check, "Selected model") },
                        modifier = Modifier.clickable { selectedModel = model },
                    )
                }
            }
            Button(
                onClick = { selectedModel?.let { onConnected(provider.id, it); onDismiss() } },
                enabled = selectedModel != null && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Use selected model") }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .heightIn(max = (configuration.screenHeightDp.dp * if (landscape) .88f else .90f))
            .padding(horizontal = 16.dp, vertical = if (landscape) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Connect provider", style = MaterialTheme.typography.titleMedium)
                Text("BYOK · credentials are validated before Droide commits the connection", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            }
            IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Default.Close, "Close connect provider") }
        }

        if (twoPane) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(.42f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp), content = providerPane)
                VerticalDivider(color = DroideColors.Border)
                Column(Modifier.weight(.58f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp), content = detailPane)
            }
        } else {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = providerPane)
                HorizontalDivider(color = DroideColors.Border)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = detailPane)
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val minutes = safe / 60L
    val remainder = safe % 60L
    return if (minutes > 0L) "${minutes}m ${remainder}s" else "${remainder}s"
}

private fun credentialLabel(method: ProviderAuthMethodDescriptor): String = when (method.kind) {
    ProviderAuthMethodKind.API_KEY -> "API key"
    ProviderAuthMethodKind.CREDENTIAL_BUNDLE -> method.label
    ProviderAuthMethodKind.OAUTH -> if (method.id == "github-token") "GitHub OAuth token" else method.label
    ProviderAuthMethodKind.DEFAULT_CHAIN -> "Default credentials"
    ProviderAuthMethodKind.NONE -> "No authentication"
}

package com.baystudio.droide.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.baystudio.droide.BuildConfig
import com.baystudio.droide.core.*
import kotlinx.coroutines.launch

private enum class SettingsScope { USER, WORKSPACE }

private enum class SettingsCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val keywords: String,
) {
    GENERAL("General", "Startup and workbench behavior", Icons.Default.Settings, "startup restore autosave behavior"),
    APPEARANCE("Appearance", "Theme and interface presentation", Icons.Default.Palette, "theme appearance density ui"),
    EDITOR("Editor", "Editing, formatting and text behavior", Icons.Default.Edit, "editor word wrap formatter autocomplete smart typing"),
    LANGUAGES("Languages & LSP", "Language intelligence and diagnostics", Icons.Default.Code, "language lsp diagnostics formatter tree sitter textmate"),
    FILES("Files & Workspace", "Workspace scope and file behavior", Icons.Default.Folder, "files workspace encoding exclude watcher"),
    TERMINAL("Terminal", "Terminal interaction and input", Icons.Default.Terminal, "terminal shell accessory keys"),
    GIT("Git & Source Control", "Repository behavior and source control", Icons.Default.AccountTree, "git source control diff commit fetch"),
    RUN("Run, Build & Debug", "Android toolchain and device execution", Icons.Default.PlayArrow, "run build debug gradle sdk ndk adb device"),
    AI("AI & Agents", "Agent context and runtime behavior", Icons.Default.AutoAwesome, "ai agent context reasoning subagent checkpoint"),
    PROVIDERS("Providers & Models", "Provider authentication and model selection", Icons.Default.Cloud, "provider model openai gemini claude local auth"),
    PERMISSIONS("Agent Permissions", "Allow, ask or deny agent capabilities", Icons.Default.AdminPanelSettings, "permission allow ask deny shell files web mcp"),
    TOOLS("Tools & MCP", "Tool and MCP integration", Icons.Default.Build, "tools mcp server integration"),
    PLUGINS("Plugins", "Installed extensions and capabilities", Icons.Default.Extension, "plugin extension marketplace"),
    KEYBOARD("Keyboard & Input", "Mobile accessory keys and hardware keyboard", Icons.Default.Keyboard, "keyboard input shortcuts keys"),
    SECURITY("Security & Privacy", "Trust, credentials and privacy boundaries", Icons.Default.Security, "security privacy credentials secret trust telemetry"),
    PERFORMANCE("Performance & Storage", "Runtime resource and local storage behavior", Icons.Default.Speed, "performance storage cache indexing memory"),
    NOTIFICATIONS("Notifications", "Build and agent notification behavior", Icons.Default.Notifications, "notifications alerts build agent"),
    ACCOUNTS("Accounts & Sync", "Connected services and configuration scope", Icons.Default.Person, "account sync connected service github login oauth source control"),
    ABOUT("About & Legal", "Version, licenses and legal notices", Icons.Default.Info, "about legal license notices privacy terms version"),
}

private enum class LegalRoute { OPEN_SOURCE, THIRD_PARTY, BRAND_ASSETS, DROIDE_LICENSE, PRIVACY, TERMS, UPDATES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themes: ThemeManager,
    formatters: FormatterManager,
    wordwrap: Boolean,
    hardwareShortcuts: Boolean,
    accessoryKeysComfortable: Boolean,
    codeStyleDefaults: CodeStyleDefaults,
    androidDevelopment: AndroidDevelopmentManager,
    deviceBridge: DeviceBridgeManager,
    githubAccount: GitHubAccountManager,
    projectName: String,
    initialPermissionPolicy: PermissionPolicyDocument,
    onWordwrap: (Boolean) -> Unit,
    onHardwareShortcuts: (Boolean) -> Unit,
    onAccessoryKeysComfortable: (Boolean) -> Unit,
    onDetectIndentation: (Boolean) -> Unit,
    onIndentStyle: (IndentStyle) -> Unit,
    onTabWidth: (Int) -> Unit,
    onIndentSize: (Int) -> Unit,
    onContinuationIndent: (Int) -> Unit,
    onTheme: (String) -> Unit,
    onPermissionPolicyChange: suspend (PermissionPolicyDocument) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenGit: () -> Unit,
    onOpenRunDebug: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenAgentSessions: () -> Unit,
    onOpenCapabilityHealth: () -> Unit,
    mcpHealth: List<McpHealthSnapshot>,
    onRetryMcp: (String) -> Unit,
    onOpenMcpConfig: () -> Unit,
    initialCategoryName: String? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var selected by rememberSaveable(initialCategoryName) {
        mutableStateOf(initialCategoryName?.let { name -> SettingsCategory.entries.firstOrNull { it.name == name } })
    }
    var settingsScope by rememberSaveable { mutableStateOf(SettingsScope.USER) }
    var query by rememberSaveable { mutableStateOf("") }
    var legalRoute by rememberSaveable { mutableStateOf<LegalRoute?>(null) }
    var selectedLicenseId by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionPolicy by remember(projectName) { mutableStateOf(initialPermissionPolicy) }
    var fmt by remember(formatters) { mutableStateOf(formatters.isEnabled()) }
    val agentPrefs by AgentPreferences.observe(context).collectAsState(
        initial = AgentPreferences.Snapshot("pollinations", "", ProviderRegistry.byId("pollinations").model, null),
    )
    val githubAccountState by githubAccount.snapshot.collectAsState()
    val connectGitHub: () -> Unit = {
        githubAccount.beginAuthorization().fold(
            onSuccess = { authorizationUrl ->
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl))
                            .addCategory(Intent.CATEGORY_BROWSABLE)
                    )
                }.onFailure { failure ->
                    githubAccount.browserLaunchFailed("Could not open a browser for GitHub sign-in")
                    scope.launch { snackbar.showSnackbar("Could not open GitHub sign-in: ${failure.message ?: "no browser available"}") }
                }
            },
            onFailure = { failure ->
                scope.launch { snackbar.showSnackbar(failure.message ?: "GitHub sign-in is unavailable") }
            },
        )
    }
    val manageGitHubRepositoryAccess: () -> Unit = {
        val accessUrl = githubAccount.repositoryAccessUrl()
        if (accessUrl == null) {
            scope.launch { snackbar.showSnackbar("This build has no GitHub App installation URL configured") }
        } else {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(accessUrl))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                )
            }.onFailure { failure ->
                scope.launch { snackbar.showSnackbar("Could not open GitHub repository access: ${failure.message ?: "no browser available"}") }
            }
        }
    }
    val disconnectGitHub: () -> Unit = {
        scope.launch {
            githubAccount.disconnect().fold(
                onSuccess = { outcome ->
                    snackbar.showSnackbar(
                        if (outcome.remoteRevoked) "Droide's GitHub authorization was revoked"
                        else "Local GitHub connection was removed"
                    )
                },
                onFailure = { failure -> snackbar.showSnackbar("GitHub disconnect needs attention: ${failure.message ?: "unknown error"}") },
            )
        }
    }
    val licenseManifest = remember(context) { runCatching { OpenSourceLicenseCatalog.load(context) } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        

        val compactHeight = maxHeight < 480.dp
        val twoPane = maxWidth >= 720.dp && !compactHeight
        val showSearchField = twoPane || selected == null || query.isNotBlank()
        fun navigateBack() {
            when {
                selectedLicenseId != null -> selectedLicenseId = null
                legalRoute != null -> legalRoute = null
                !twoPane && selected != null -> selected = null
                else -> onDismiss()
            }
        }
        BackHandler(onBack = ::navigateBack)

        Scaffold(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Surface(tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = if (compactHeight) 48.dp else 56.dp).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!twoPane && selected != null) {
                                IconButton(onClick = ::navigateBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                            } else {
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                if (!twoPane && selected != null) selected!!.title else "Settings",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close settings") }
                        }
                        if (showSearchField) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compactHeight) 2.dp else 4.dp),
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") } }) else null,
                                placeholder = { Text("Search settings") },
                            )
                        }
                        ScopeSelector(settingsScope, compact = compactHeight, onSelected = { settingsScope = it })
                    }
                }
            },
        ) { padding ->
            val filtered = remember(query) {
                val q = query.trim().lowercase()
                if (q.isBlank()) SettingsCategory.entries else SettingsCategory.entries.filter {
                    it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q) || it.keywords.contains(q)
                }
            }
            if (twoPane) {
                Row(Modifier.fillMaxSize().padding(padding)) {
                    SettingsCategoryList(
                        categories = filtered,
                        selected = selected,
                        onSelect = { selected = it; legalRoute = null; selectedLicenseId = null },
                        modifier = Modifier.widthIn(min = 220.dp, max = 310.dp).fillMaxHeight(),
                    )
                    VerticalDivider()
                    SettingsDetail(
                        category = selected ?: SettingsCategory.GENERAL,
                        settingsScope = settingsScope,
                        themes = themes,
                        formattersEnabled = fmt,
                        wordwrap = wordwrap,
                        hardwareShortcuts = hardwareShortcuts,
                        accessoryKeysComfortable = accessoryKeysComfortable,
                        codeStyleDefaults = codeStyleDefaults,
                        androidDevelopment = androidDevelopment,
                        deviceBridge = deviceBridge,
                        projectName = projectName,
                        agentPrefs = agentPrefs,
                        githubAccountState = githubAccountState,
                        permissionPolicy = permissionPolicy,
                        legalRoute = legalRoute,
                        selectedLicenseId = selectedLicenseId,
                        licenseManifest = licenseManifest,
                        showNestedBack = twoPane,
                        onFormatter = { enabled -> fmt = enabled; formatters.setEnabled(enabled) },
                        onWordwrap = onWordwrap,
                        onHardwareShortcuts = onHardwareShortcuts,
                        onAccessoryKeysComfortable = onAccessoryKeysComfortable,
                        onDetectIndentation = onDetectIndentation,
                        onIndentStyle = onIndentStyle,
                        onTabWidth = onTabWidth,
                        onIndentSize = onIndentSize,
                        onContinuationIndent = onContinuationIndent,
                        onTheme = onTheme,
                        onPermission = { action, effect ->
                            val updated = permissionPolicy.withBroadRule(action, effect)
                            scope.launch {
                                runCatching { onPermissionPolicyChange(updated) }
                                    .onSuccess { permissionPolicy = updated; snackbar.showSnackbar("Permission policy saved") }
                                    .onFailure { snackbar.showSnackbar("Permission update failed: ${it.message ?: "unknown error"}") }
                            }
                        },
                        onLegalRoute = { legalRoute = it; selectedLicenseId = null },
                        onLicense = { selectedLicenseId = it },
                        onLegalBack = ::navigateBack,
                        onOpenProviders = onOpenProviders,
                        onOpenModels = onOpenModels,
                        onConnectGitHub = connectGitHub,
                        onManageGitHubRepositoryAccess = manageGitHubRepositoryAccess,
                        onDisconnectGitHub = disconnectGitHub,
                        onOpenExtensions = onOpenExtensions,
                        onOpenFiles = onOpenFiles,
                        onOpenTerminal = onOpenTerminal,
                        onOpenGit = onOpenGit,
                        onOpenRunDebug = onOpenRunDebug,
                        onOpenAgent = onOpenAgent,
                        onOpenAgentSessions = onOpenAgentSessions,
                        onOpenCapabilityHealth = onOpenCapabilityHealth,
                        onSettingsScopeChange = { settingsScope = it },
                        mcpHealth = mcpHealth,
                        onRetryMcp = onRetryMcp,
                        onOpenMcpConfig = onOpenMcpConfig,
                        onOpenMcpPermissions = { settingsScope = SettingsScope.WORKSPACE; selected = SettingsCategory.PERMISSIONS },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            } else if (selected == null || query.isNotBlank()) {
                SettingsCategoryList(
                    categories = filtered,
                    selected = null,
                    onSelect = { selected = it; query = ""; legalRoute = null; selectedLicenseId = null },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            } else {
                SettingsDetail(
                    category = selected!!,
                    settingsScope = settingsScope,
                    themes = themes,
                    formattersEnabled = fmt,
                    wordwrap = wordwrap,
                    hardwareShortcuts = hardwareShortcuts,
                    accessoryKeysComfortable = accessoryKeysComfortable,
                    codeStyleDefaults = codeStyleDefaults,
                    androidDevelopment = androidDevelopment,
                    deviceBridge = deviceBridge,
                    projectName = projectName,
                    agentPrefs = agentPrefs,
                    githubAccountState = githubAccountState,
                    permissionPolicy = permissionPolicy,
                    legalRoute = legalRoute,
                    selectedLicenseId = selectedLicenseId,
                    licenseManifest = licenseManifest,
                    showNestedBack = twoPane,
                    onFormatter = { enabled -> fmt = enabled; formatters.setEnabled(enabled) },
                    onWordwrap = onWordwrap,
                    onHardwareShortcuts = onHardwareShortcuts,
                    onAccessoryKeysComfortable = onAccessoryKeysComfortable,
                    onDetectIndentation = onDetectIndentation,
                    onIndentStyle = onIndentStyle,
                    onTabWidth = onTabWidth,
                    onIndentSize = onIndentSize,
                    onContinuationIndent = onContinuationIndent,
                    onTheme = onTheme,
                    onPermission = { action, effect ->
                        val updated = permissionPolicy.withBroadRule(action, effect)
                        scope.launch {
                            runCatching { onPermissionPolicyChange(updated) }
                                .onSuccess { permissionPolicy = updated; snackbar.showSnackbar("Permission policy saved") }
                                .onFailure { snackbar.showSnackbar("Permission update failed: ${it.message ?: "unknown error"}") }
                        }
                    },
                    onLegalRoute = { legalRoute = it; selectedLicenseId = null },
                    onLicense = { selectedLicenseId = it },
                    onLegalBack = ::navigateBack,
                    onOpenProviders = onOpenProviders,
                    onOpenModels = onOpenModels,
                    onConnectGitHub = connectGitHub,
                    onManageGitHubRepositoryAccess = manageGitHubRepositoryAccess,
                    onDisconnectGitHub = disconnectGitHub,
                    onOpenExtensions = onOpenExtensions,
                    onOpenFiles = onOpenFiles,
                    onOpenTerminal = onOpenTerminal,
                    onOpenGit = onOpenGit,
                    onOpenRunDebug = onOpenRunDebug,
                    onOpenAgent = onOpenAgent,
                    onOpenAgentSessions = onOpenAgentSessions,
                    onOpenCapabilityHealth = onOpenCapabilityHealth,
                    onSettingsScopeChange = { settingsScope = it },
                    mcpHealth = mcpHealth,
                    onRetryMcp = onRetryMcp,
                    onOpenMcpConfig = onOpenMcpConfig,
                    onOpenMcpPermissions = { settingsScope = SettingsScope.WORKSPACE; selected = SettingsCategory.PERMISSIONS },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
        }
    }
}

@Composable
private fun ScopeSelector(scope: SettingsScope, compact: Boolean = false, onSelected: (SettingsScope) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = scope == SettingsScope.USER, onClick = { onSelected(SettingsScope.USER) }, label = { Text("User") })
        FilterChip(selected = scope == SettingsScope.WORKSPACE, onClick = { onSelected(SettingsScope.WORKSPACE) }, label = { Text("Workspace") })
    }
}

@Composable
private fun SettingsCategoryList(
    categories: List<SettingsCategory>,
    selected: SettingsCategory?,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        items(categories, key = { it.name }) { category ->
            ListItem(
                headlineContent = { Text(category.title) },
                supportingContent = { Text(category.subtitle, maxLines = 1) },
                leadingContent = { Icon(category.icon, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = if (category == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                modifier = Modifier.clickable { onSelect(category) },
            )
        }
    }
}

@Composable
private fun SettingsDetail(
    category: SettingsCategory,
    settingsScope: SettingsScope,
    themes: ThemeManager,
    formattersEnabled: Boolean,
    wordwrap: Boolean,
    hardwareShortcuts: Boolean,
    accessoryKeysComfortable: Boolean,
    codeStyleDefaults: CodeStyleDefaults,
    androidDevelopment: AndroidDevelopmentManager,
    deviceBridge: DeviceBridgeManager,
    projectName: String,
    agentPrefs: AgentPreferences.Snapshot,
    githubAccountState: GitHubAccountManager.Snapshot,
    permissionPolicy: PermissionPolicyDocument,
    legalRoute: LegalRoute?,
    selectedLicenseId: String?,
    licenseManifest: Result<OpenSourceLicenseManifest>,
    showNestedBack: Boolean,
    onFormatter: (Boolean) -> Unit,
    onWordwrap: (Boolean) -> Unit,
    onHardwareShortcuts: (Boolean) -> Unit,
    onAccessoryKeysComfortable: (Boolean) -> Unit,
    onDetectIndentation: (Boolean) -> Unit,
    onIndentStyle: (IndentStyle) -> Unit,
    onTabWidth: (Int) -> Unit,
    onIndentSize: (Int) -> Unit,
    onContinuationIndent: (Int) -> Unit,
    onTheme: (String) -> Unit,
    onPermission: (String, PermEffect) -> Unit,
    onLegalRoute: (LegalRoute) -> Unit,
    onLicense: (String) -> Unit,
    onLegalBack: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenModels: () -> Unit,
    onConnectGitHub: () -> Unit,
    onManageGitHubRepositoryAccess: () -> Unit,
    onDisconnectGitHub: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenGit: () -> Unit,
    onOpenRunDebug: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenAgentSessions: () -> Unit,
    onOpenCapabilityHealth: () -> Unit,
    onSettingsScopeChange: (SettingsScope) -> Unit,
    mcpHealth: List<McpHealthSnapshot>,
    onRetryMcp: (String) -> Unit,
    onOpenMcpConfig: () -> Unit,
    onOpenMcpPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (category == SettingsCategory.ABOUT && legalRoute != null) {
        LegalSettingsContent(legalRoute, selectedLicenseId, licenseManifest, onLicense, onLegalBack, showNestedBack, modifier)
        return
    }
    val context = LocalContext.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(category.title, style = MaterialTheme.typography.headlineSmall)
        Text(category.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        when (category) {
            SettingsCategory.GENERAL -> {
                SectionTitle("Workbench")
                InfoRow("Project", projectName)
                InfoRow("Settings scope", if (settingsScope == SettingsScope.USER) "Applies across projects" else "Applies to this project")
                InfoRow("Session restoration", "Preserves editor, terminal and Agent state")
            }
            SettingsCategory.APPEARANCE -> {
                SectionTitle("Theme")
                if (settingsScope == SettingsScope.WORKSPACE) {
                    themes.list().forEach { theme -> ChoiceRow(theme.name, theme.name == themes.current().name) { onTheme(theme.name) } }
                    InfoRow("Storage", ".droide/theme.json")
                } else ActionRow("Switch to Workspace settings", "Store theme for this workspace", { onSettingsScopeChange(SettingsScope.WORKSPACE) })
            }
            SettingsCategory.EDITOR -> {
                if (settingsScope == SettingsScope.USER) {
                    SectionTitle("Text editor")
                    SwitchRow("Word wrap", "Wrap long editor lines to the viewport", wordwrap, onWordwrap)
                    SwitchRow("Detect indentation", "Detect tabs, spaces and width from the file", codeStyleDefaults.detectIndentation, onDetectIndentation)
                    SectionTitle("Default indentation")
                    ChoiceRow("Spaces", codeStyleDefaults.indentStyle == IndentStyle.SPACES) { onIndentStyle(IndentStyle.SPACES) }
                    ChoiceRow("Tabs", codeStyleDefaults.indentStyle == IndentStyle.TABS) { onIndentStyle(IndentStyle.TABS) }
                    Text("Tab width", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(2, 4, 8).forEach { n -> FilterChip(selected = codeStyleDefaults.tabWidth == n, onClick = { onTabWidth(n) }, label = { Text(n.toString()) }) } }
                    Text("Indent size", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(2, 4, 8).forEach { n -> FilterChip(selected = codeStyleDefaults.indentSize == n, onClick = { onIndentSize(n) }, label = { Text(n.toString()) }) } }
                    Text("Continuation indent", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(4, 8, 12).forEach { n -> FilterChip(selected = codeStyleDefaults.continuationIndent == n, onClick = { onContinuationIndent(n) }, label = { Text(n.toString()) }) } }
                    InfoRow("Authority", ".editorconfig > detected style > user defaults")
                    InfoRow("Smart typing & completion", "Follows the active language and LSP")
                } else {
                    SectionTitle("Workspace formatting")
                    SwitchRow("Formatter", "Use configured ruff / prettier / ktlint / gofmt / shfmt integrations", formattersEnabled, onFormatter)
                    InfoRow("Storage", ".droide/formatter.json")
                    InfoRow("Indentation authority", "Nearest matching .editorconfig overrides user defaults for each file")
                }
            }
            SettingsCategory.LANGUAGES -> {
                SectionTitle("Language intelligence")
                InfoRow("Detection", "LanguageRegistry selects language support from the active file")
                InfoRow("LSP", "Diagnostics, completion, symbols and navigation")
                InfoRow("Grammar", "TextMate grammars are built in")
                ActionRow("Manage language extensions", "Open the Extensions workbench", onOpenExtensions)
            }
            SettingsCategory.FILES -> {
                SectionTitle("Scope")
                InfoRow("Active workspace", projectName)
                InfoRow("User vs Workspace", "Workspace values override supported user defaults")
                InfoRow("File access", "Workspace paths are sandboxed and validated")
                ActionRow("Open Files workbench", "Browse and manage the active workspace", onOpenFiles)
            }
            SettingsCategory.TERMINAL -> {
                SectionTitle("Input")
                if (settingsScope == SettingsScope.USER) {
                    ComfortableAccessoryKeysRow(accessoryKeysComfortable, onAccessoryKeysComfortable)
                    SwitchRow("Hardware keyboard shortcuts", "Enable Droide's IDE keyboard shortcut routing", hardwareShortcuts, onHardwareShortcuts)
                } else ActionRow("Switch to User settings", "Applies across workspaces", { onSettingsScopeChange(SettingsScope.USER) })
                InfoRow("Terminal engine", "Termux terminal components")
                ActionRow("Open Terminal", "Return to the live terminal workbench", onOpenTerminal)
            }
            SettingsCategory.GIT -> {
                SectionTitle("Source control")
                InfoRow("Git engine", "Eclipse JGit, workspace-scoped")
                InfoRow("Safety", "Agent permissions apply to mutating Git and shell actions")
                InfoRow("Repository state", "Uses the active workspace repository")
                ActionRow("Open Source Control", "Inspect status, changes, branches and repository actions", onOpenGit)
            }
            SettingsCategory.RUN -> {
                SectionTitle("Android & Device")
                if (settingsScope == SettingsScope.WORKSPACE) AndroidDevelopmentSettingsSection(androidDevelopment, deviceBridge)
                else ActionRow("Switch to Workspace settings", "Build and toolchain settings are workspace-scoped", { onSettingsScopeChange(SettingsScope.WORKSPACE) })
                ActionRow("Open Run & Debug", "Return to the workspace execution tools", onOpenRunDebug)
            }
            SettingsCategory.AI -> {
                SectionTitle("Current agent configuration")
                InfoRow("Provider", ProviderRegistry.byId(agentPrefs.providerId).name)
                InfoRow("Model", agentPrefs.model)
                InfoRow("Reasoning", agentPrefs.reasoningEffort?.label ?: "Provider default")
                InfoRow("Context safety", "Project-scoped permission gates")
                ActionRow("Open Agent", "Return to the active Agent conversation", onOpenAgent)
                ActionRow("Agent sessions", "Browse and resume previous Agent sessions", onOpenAgentSessions)
                ActionRow("Choose model", "Change the active provider model", onOpenModels)
            }
            SettingsCategory.PROVIDERS -> {
                SectionTitle("Active")
                InfoRow("Provider", ProviderRegistry.byId(agentPrefs.providerId).name)
                InfoRow("Model", agentPrefs.model)
                if (settingsScope == SettingsScope.USER) {
                    ActionRow("Connect or manage provider", "Connect provider accounts and credentials", onOpenProviders)
                    ActionRow("Choose model", "Select a model for the active provider", onOpenModels)
                    Spacer(Modifier.height(12.dp))
                    LocalModelsSettingsSection(deviceBridge)
                } else ActionRow("Switch to User settings", "Provider accounts and model defaults are user settings", { onSettingsScopeChange(SettingsScope.USER) })
            }
            SettingsCategory.PERMISSIONS -> {
                if (settingsScope == SettingsScope.WORKSPACE) PermissionSettings(permissionPolicy, onPermission)
                else { SectionTitle("Project policy"); ActionRow("Switch to Workspace settings", "Agent permissions are project-scoped", { onSettingsScopeChange(SettingsScope.WORKSPACE) }) }
            }
            SettingsCategory.TOOLS -> {
                SectionTitle("Tool authority")
                McpSettingsSection(
                    health = mcpHealth,
                    onRetry = onRetryMcp,
                    onOpenConfig = onOpenMcpConfig,
                    onOpenPermissions = onOpenMcpPermissions,
                )
                SectionTitle("Other tools")
                InfoRow("Custom/plugin tools", "Dynamic tools use Agent permission checks")
                ActionRow("Agent permission controls", "Review workspace tool permissions", onOpenMcpPermissions)
            }
            SettingsCategory.PLUGINS -> {
                SectionTitle("Extensions")
                InfoRow("Runtime", "Extensions follow Droide permission boundaries")
                ActionRow("Open Extensions workbench", "Browse and manage extensions", onOpenExtensions)
            }
            SettingsCategory.KEYBOARD -> {
                SectionTitle("Keyboard")
                if (settingsScope == SettingsScope.USER) {
                    SwitchRow("Hardware keyboard shortcuts", "Enable IDE shortcuts for hardware keyboards", hardwareShortcuts, onHardwareShortcuts)
                    ComfortableAccessoryKeysRow(accessoryKeysComfortable, onAccessoryKeysComfortable)
                } else ActionRow("Switch to User settings", "Keyboard and input preferences apply across workspaces", { onSettingsScopeChange(SettingsScope.USER) })
                InfoRow("Context-aware keys", "Accessory keys follow editor or terminal focus")
                InfoRow("IDE shortcuts", "Ctrl+Space Completion · Ctrl+P Quick Open · Ctrl+Shift+P Commands · Ctrl+S Save · Ctrl+` Terminal · F2 Rename · F5 Debug")
            }
            SettingsCategory.SECURITY -> {
                SectionTitle("Security boundaries")
                InfoRow("Secrets", "Stored outside workspace files")
                InfoRow("Agent policy", "Stored per project")
                InfoRow("Sensitive files", "Sensitive paths default to Ask")
                InfoRow("Distribution", "Public release is blocked until compliance is complete")
                ActionRow("Agent Permissions", "Review workspace permissions", onOpenMcpPermissions)
                ActionRow("Provider credentials", "Manage provider accounts", onOpenProviders)
            }
            SettingsCategory.PERFORMANCE -> {
                SectionTitle("Runtime")
                InfoRow("Workspace lifetime", "Editor, terminal and Agent stay active")
                InfoRow("Indexing", "Workspace language services stay active")
                InfoRow("Storage", "Projects are local-first; build caches are managed separately")
                ActionRow("Capability Health", "Inspect runtime and toolchain status", onOpenCapabilityHealth)
            }
            SettingsCategory.NOTIFICATIONS -> {
                SectionTitle("Current support")
                InfoRow("IDE feedback", "Uses in-app status and notifications")
                ActionRow("Android notification settings", "Open Android notification settings", {
                    runCatching {
                        context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                })
            }
            SettingsCategory.ACCOUNTS -> {
                if (settingsScope == SettingsScope.USER) {
                    SectionTitle("GitHub")
                    when (githubAccountState.status) {
                        GitHubAccountManager.Status.UNAVAILABLE -> {
                            ActionRow(
                                "Connect GitHub",
                                "GitHub sign-in is not configured in this build",
                                onConnectGitHub,
                            )
                        }
                        GitHubAccountManager.Status.DISCONNECTED -> {
                            ActionRow("Connect GitHub", "Sign in with GitHub", onConnectGitHub)
                        }
                        GitHubAccountManager.Status.WAITING_BROWSER -> {
                            InfoRow("GitHub", githubAccountState.login?.let { "@$it connected · waiting for browser sign-in" } ?: "Waiting for browser authorization")
                            ActionRow("Restart GitHub sign-in", "Start a new GitHub sign-in", onConnectGitHub)
                        }
                        GitHubAccountManager.Status.COMPLETING -> {
                            InfoRow("GitHub", githubAccountState.login?.let { "Validating a replacement for @$it" } ?: "Validating the authorized GitHub account")
                        }
                        GitHubAccountManager.Status.REFRESHING -> {
                            InfoRow("GitHub", githubAccountState.login?.let { "Refreshing the session for @$it" } ?: "Refreshing the GitHub session")
                        }
                        GitHubAccountManager.Status.DISCONNECTING -> {
                            InfoRow("GitHub", githubAccountState.login?.let { "Revoking Droide authorization for @$it" } ?: "Revoking Droide's GitHub authorization")
                        }
                        GitHubAccountManager.Status.CONNECTED -> {
                            InfoRow("GitHub", "Connected as @${githubAccountState.login ?: "unknown"}")
                            InfoRow("Repository access", "Private access follows GitHub App permissions")
                            githubAccountState.detail?.let { InfoRow("Last sign-in", it) }
                            ActionRow("Grant repository access", "Choose repositories on GitHub", onManageGitHubRepositoryAccess)
                            ActionRow("Reconnect GitHub", "Sign in again", onConnectGitHub)
                            ActionRow("Disconnect GitHub", "Revoke GitHub access", onDisconnectGitHub)
                        }
                        GitHubAccountManager.Status.REAUTH_REQUIRED -> {
                            InfoRow("GitHub", "@${githubAccountState.login ?: "unknown"} needs sign-in again")
                            ActionRow("Reconnect GitHub", "Sign in again with GitHub", onConnectGitHub)
                            ActionRow("Disconnect GitHub", "Remove the expired credential", onDisconnectGitHub)
                        }
                    }
                    githubAccountState.detail?.takeIf { githubAccountState.status != GitHubAccountManager.Status.CONNECTED }?.let {
                        InfoRow("Status", it)
                    }
                    SectionTitle("Other connected services")
                    InfoRow("AI providers", "AI provider accounts are separate from GitHub")
                    ActionRow("Manage providers", "Open provider accounts", onOpenProviders)
                    InfoRow("Settings sync", "Not available yet")
                } else {
                    SectionTitle("Connected services")
                    ActionRow("Switch to User settings", "Accounts are user settings", { onSettingsScopeChange(SettingsScope.USER) })
                }
            }
            SettingsCategory.ABOUT -> {
                SectionTitle("Droide")
                InfoRow("Version", "Droide v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                InfoRow("Developer", "BayStudio")
                InfoRow("Engineering", "BayZov")
                ActionRow("Updates", "Release status", { onLegalRoute(LegalRoute.UPDATES) })
                ActionRow("Privacy", "Privacy details", { onLegalRoute(LegalRoute.PRIVACY) })
                ActionRow("Terms of Service", "Terms are not configured for this build", { onLegalRoute(LegalRoute.TERMS) })
                ActionRow("Droide License", "Distribution license status", { onLegalRoute(LegalRoute.DROIDE_LICENSE) })
                SectionTitle("Legal")
                ActionRow("Open Source Licenses", "Dependency licenses", { onLegalRoute(LegalRoute.OPEN_SOURCE) })
                ActionRow("Third-Party Notices", "Brand marks, attributions and license scope", { onLegalRoute(LegalRoute.THIRD_PARTY) })
                ActionRow("Brand Asset Terms", "Provider and project logo usage", { onLegalRoute(LegalRoute.BRAND_ASSETS) })
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}
@Composable
private fun PermissionSettings(policy: PermissionPolicyDocument, onPermission: (String, PermEffect) -> Unit) {
    val rows = listOf(
        Triple("read", "Read workspace files", PermEffect.ALLOW),
        Triple("edit", "Edit workspace files", PermEffect.ASK),
        Triple("shell", "Run shell / mutating Git commands", PermEffect.ASK),
        Triple("webfetch", "Fetch web resources", PermEffect.ASK),
        Triple("websearch", "Search the web", PermEffect.ASK),
        Triple("mcp", "Use MCP tools", PermEffect.ASK),
        Triple("mcp_repair", "Repair MCP configuration", PermEffect.ASK),
        Triple("ide_execute", "Run IDE build, tests, lint or file execution", PermEffect.ASK),
        Triple("lsp_edit", "Apply LSP quick fixes / formatting", PermEffect.ASK),
        Triple("toolchain_install", "Install or repair managed runtimes/toolchains", PermEffect.ASK),
        Triple("browser_interact", "Click/type in the integrated browser", PermEffect.ASK),
        Triple("browser_local", "Let Agent browse localhost development servers", PermEffect.ASK),
        Triple("subagent", "Launch subagents", PermEffect.ASK),
        Triple("external_directory", "Access paths outside the workspace", PermEffect.ASK),
    )
    SectionTitle("Project policy")
    Text(
        "Project-specific policy. Deny overrides Ask and Allow.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    rows.forEach { (action, label, default) ->
        val current = policy.rules.lastOrNull { it.action == action && it.resource == "*" }?.effect ?: default
        PermissionRow(label, current) { onPermission(action, it) }
    }
    if (policy.rules.any { it.resource != "*" }) {
        InfoRow("Fine-grained rules", "${policy.rules.count { it.resource != "*" }} existing rule(s) are preserved when broad controls change")
    }
}

@Composable
private fun PermissionRow(label: String, selected: PermEffect, onSelected: (PermEffect) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PermEffect.entries.forEach { effect ->
                FilterChip(selected = effect == selected, onClick = { onSelected(effect) }, label = { Text(effect.name.lowercase().replaceFirstChar { it.uppercase() }) })
            }
        }
    }
    HorizontalDivider()
}

private fun PermissionPolicyDocument.withBroadRule(action: String, effect: PermEffect): PermissionPolicyDocument {
    val kept = rules.filterNot { it.action == action && it.resource == "*" }
    return copy(rules = kept + PermRule(action, "*", effect)).validated()
}

@Composable
private fun LegalSettingsContent(
    route: LegalRoute,
    selectedLicenseId: String?,
    manifestResult: Result<OpenSourceLicenseManifest>,
    onLicense: (String) -> Unit,
    onBack: () -> Unit,
    showBackHeader: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    when (route) {
        LegalRoute.OPEN_SOURCE -> {
            val manifest = manifestResult.getOrNull()
            if (manifest == null) {
                LegalTextPage("Open Source Licenses", "License catalog unavailable.\n\n${manifestResult.exceptionOrNull()?.message.orEmpty()}", onBack, showBackHeader, modifier)
            } else if (selectedLicenseId != null) {
                val entry = manifest.entries.firstOrNull { it.id == selectedLicenseId }
                if (entry == null) LegalTextPage("License not found", "The selected catalog entry no longer exists.", onBack, showBackHeader, modifier)
                else {
                    val body = remember(entry.id) { runCatching { OpenSourceLicenseCatalog.readAsset(context, entry.licenseTextAsset) }.getOrElse { "License text could not be loaded: ${it.message}" } }
                    val text = buildString {
                        append(entry.name).append("\n")
                        append("Version: ").append(entry.version).append("\n")
                        append("License: ").append(entry.licenseName).append(" (").append(entry.spdx).append(")\n")
                        append(entry.copyright).append("\n")
                        append("Source: ").append(entry.source).append("\n\n")
                        append(entry.notice).append("\n\n").append(body)
                    }
                    LegalTextPage(entry.name, text, onBack, showBackHeader, modifier)
                }
            } else {
                OpenSourceLicenseList(manifest, onLicense, onBack, showBackHeader, modifier)
            }
        }
        LegalRoute.THIRD_PARTY -> LegalAssetPage("Third-Party Notices", OpenSourceLicenseCatalog.THIRD_PARTY_NOTICES_ASSET, onBack, showBackHeader, modifier)
        LegalRoute.BRAND_ASSETS -> LegalAssetPage("Brand Asset Terms", OpenSourceLicenseCatalog.BRAND_ASSET_TERMS_ASSET, onBack, showBackHeader, modifier)
        LegalRoute.UPDATES -> LegalAssetPage("Release & Distribution Status", OpenSourceLicenseCatalog.DISTRIBUTION_COMPLIANCE_ASSET, onBack, showBackHeader, modifier)
        LegalRoute.DROIDE_LICENSE -> LegalTextPage(
            "Droide License",
            "Distribution license not configured. Public release stays disabled until one is selected.",
            onBack,
            showBackHeader,
            modifier,
        )
        LegalRoute.PRIVACY -> LegalTextPage(
            "Privacy",
            "Secrets and project permissions stay in app-private storage. A public Privacy Policy is not bundled yet.",
            onBack,
            showBackHeader,
            modifier,
        )
        LegalRoute.TERMS -> LegalTextPage(
            "Terms of Service",
            "No public Terms of Service are bundled in this build.",
            onBack,
            showBackHeader,
            modifier,
        )
    }
}

@Composable
private fun OpenSourceLicenseList(manifest: OpenSourceLicenseManifest, onLicense: (String) -> Unit, onBack: () -> Unit, showBackHeader: Boolean, modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(query, manifest) {
        val q = query.trim().lowercase()
        if (q.isBlank()) manifest.entries else manifest.entries.filter {
            it.name.lowercase().contains(q) || it.licenseName.lowercase().contains(q) || it.spdx.lowercase().contains(q)
        }
    }
    Column(modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        LegalPageTitle("Open Source Licenses", onBack, showBackHeader)
        Text(manifest.closureNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            query,
            { query = it },
            Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("Search libraries") },
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(visible, key = { it.id }) { entry ->
                ListItem(
                    headlineContent = { Text(entry.name) },
                    supportingContent = { Text("${entry.version} · ${entry.licenseName}") },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    modifier = Modifier.clickable { onLicense(entry.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LegalAssetPage(title: String, asset: String, onBack: () -> Unit, showBackHeader: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val text = remember(asset) { runCatching { OpenSourceLicenseCatalog.readAsset(context, asset) }.getOrElse { "Unable to load packaged legal document: ${it.message}" } }
    LegalTextPage(title, text, onBack, showBackHeader, modifier)
}

@Composable
private fun LegalTextPage(title: String, text: String, onBack: () -> Unit, showBackHeader: Boolean, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        LegalPageTitle(title, onBack, showBackHeader)
        SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun LegalPageTitle(title: String, onBack: () -> Unit, showBackHeader: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (showBackHeader) IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
}

@Composable private fun SectionTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable private fun ScopeNotice(actual: SettingsScope, required: SettingsScope, text: String) {
    if (actual != required) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable private fun InfoRow(title: String, value: String) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(value) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface))
    HorizontalDivider()
}

@Composable private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable { onChecked(!checked) },
    )
    HorizontalDivider()
}

@Composable private fun ComfortableAccessoryKeysRow(checked: Boolean, onChecked: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text("Comfortable accessory keys") },
        supportingContent = { Text("48dp mobile accessory-key row instead of compact 44dp") },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable { onChecked(!checked) },
    )
    HorizontalDivider()
}

@Composable private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}

@Composable private fun ChoiceRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(title, Modifier.padding(start = 8.dp))
    }
}

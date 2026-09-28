package com.baystudio.droide.ui
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import com.baystudio.droide.core.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.content.Context
import android.view.KeyEvent as AndroidKeyEvent
import java.io.File
import java.net.URI
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class) @Composable
fun IdeScreen(
    files: FileRepository,
    documents: WorkspaceDocumentAuthority,
    terminalManager: TerminalManager,
    git: GitManager,
    lsp: LspManager,
    debugger: DebugManager,
    agent: AgentService,
    agentBrowser: AgentBrowserController,
    agentPlugins: WorkspaceAgentPluginHost,
    githubAccount: GitHubAccountManager, providerConnections: ProviderConnectionBackend,
    localLlamaModels: LocalLlamaAgentModelController,
    androidDevelopment: AndroidDevelopmentManager,
    extensions: DevelopmentExtensionsManager,
    execution: BuildRunDebugCoordinator,
    deviceBridge: DeviceBridgeManager,
    approvals: ApprovalManager,
    projectManager: ProjectManager,
    recoveryBridge: EditorRecoveryBridge,
    editorState: EditorWorkspaceState,
    explorerState: ExplorerWorkspaceState,
    onPortraitEditorStatusBarHiddenChanged: (Boolean) -> Unit,
    onPickSaf: () -> Unit, safLinked: Boolean, onReconcileSaf: suspend () -> String?,
    onProjectSwitch: (String) -> Unit, onProjectCreate: suspend (String, String) -> Pair<DroideProject, String?>,
    onPermissionPolicyChange: suspend (PermissionPolicyDocument) -> Unit,
) {
    val workspaceKey = files.root.absolutePath
    val initialFile = remember(workspaceKey) {
        if (runCatching { files.resolveChecked("README.md").isFile }.getOrDefault(false)) "README.md" else ""
    }
    var workspaceTab by rememberSaveable(workspaceKey) { mutableIntStateOf(if (initialFile.isNotBlank()) 1 else 0) } 
    var openFiles by rememberSaveable(workspaceKey) { mutableStateOf(if (initialFile.isBlank()) emptyList() else listOf(initialFile)) }
    var activeFile by rememberSaveable(workspaceKey) { mutableStateOf(initialFile) }
    val activeProject by projectManager.activeId.collectAsState(); val projects by projectManager.projects.collectAsState()
    val projectName = projects.firstOrNull { it.id == activeProject }?.name ?: "Droide"
    var drawer by rememberSaveable { mutableStateOf(false) }
    var showPalette by rememberSaveable { mutableStateOf(false) }
    var showQuickOpen by rememberSaveable { mutableStateOf(false) }
    var showWorkspaceSymbols by rememberSaveable { mutableStateOf(false) }
    var showGoToLine by rememberSaveable(workspaceKey) { mutableStateOf(false) }
    var goToLineInput by rememberSaveable(workspaceKey) { mutableStateOf("") }
    var showSessions by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }; var settingsInitialCategoryName by rememberSaveable { mutableStateOf<String?>(null) }
    var showAndroidDevelopment by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showConnect by rememberSaveable { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showBilling by rememberSaveable { mutableStateOf(false) }
    var showProblems by rememberSaveable { mutableStateOf(false) }; var professionalSheet by remember { mutableStateOf<ProfessionalSheet?>(null) }
    var isChatVisible by rememberSaveable { mutableStateOf(false) }; var agentComposerFocused by remember { mutableStateOf(false) }
    var browserUrl by rememberSaveable { mutableStateOf("https://github.com") }
    var runnerMsg by remember(workspaceKey) { mutableStateOf<String?>(null) }
    var androidBuildDiagnostics by remember(workspaceKey) { mutableStateOf<List<BuildDiagnostic>>(emptyList()) }
    var lastAndroidApk by remember(workspaceKey) { mutableStateOf<File?>(null) }
    var androidOutputTitle by remember(workspaceKey) { mutableStateOf("Android Output") }
    var androidOutputText by remember(workspaceKey) { mutableStateOf("") }
    var showAndroidOutput by remember(workspaceKey) { mutableStateOf(false) }
    var statusBranch by remember(workspaceKey) { mutableStateOf("(no repo)") }
    var sessionsCount by remember(workspaceKey) { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val workbenchPrefs by remember(ctx) { WorkbenchPreferences.flow(ctx) }.collectAsState(initial = WorkbenchPreferences.Snapshot())
    val wordwrap = workbenchPrefs.wordWrap
    val hardwareShortcuts = workbenchPrefs.hardwareShortcuts
    var currentProvider by rememberSaveable { mutableStateOf("pollinations") }
    var sidebarToolName by rememberSaveable { mutableStateOf(DroideRailTool.Files.name) }
    var sidebarVisible by rememberSaveable { mutableStateOf(true) }
    var bottomToolName by rememberSaveable { mutableStateOf(DroideBottomTool.Terminal.name) }
    var bottomPanelVisible by rememberSaveable { mutableStateOf(true) }
    var bottomPanelHeightDp by rememberSaveable { mutableFloatStateOf(210f) }
    var agentPanelWidthDp by rememberSaveable { mutableFloatStateOf(336f) }
    var expandedWorkbench by remember { mutableStateOf(false) }
    val editorBridge = remember(workspaceKey) { ActiveEditorBridge() }; val accessoryInputFocus = remember(workspaceKey) { AccessoryInputFocusController() }; var editorAccessoryKeysExpanded by rememberSaveable(workspaceKey) { mutableStateOf(true) }; var terminalAccessoryKeysExpanded by rememberSaveable(workspaceKey) { mutableStateOf(true) }
    val markdownModes = remember(workspaceKey) { mutableStateMapOf<String, MarkdownPreviewMode>() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var pendingCloseFile by rememberSaveable(workspaceKey) { mutableStateOf<String?>(null) }
    var pendingProjectSwitch by rememberSaveable(workspaceKey) { mutableStateOf<String?>(null) }
    var pendingSafPick by rememberSaveable(workspaceKey) { mutableStateOf(false) }; var consumedPathMutationSequence by remember(workspaceKey) { mutableLongStateOf(0L) }
    var navigationTarget by remember(workspaceKey) { mutableStateOf<Triple<String, Int, Int>?>(null) }
    var navigationToken by remember(workspaceKey) { mutableIntStateOf(0) }
    var navigationBack by remember(workspaceKey) { mutableStateOf<List<Triple<String, Int, Int>>>(emptyList()) }
    var navigationForward by remember(workspaceKey) { mutableStateOf<List<Triple<String, Int, Int>>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val activeSession = terminalManager.activeSession()
    val tabs by terminalManager.tabs.collectAsState()
    val activeTab by terminalManager.active.collectAsState()
    val mode by agent.mode.collectAsState(); val mcpHealth by agent.mcpHealth.collectAsState()
    val tokens by agent.tokens.usage.collectAsState()
    val tokenWarning by agent.tokens.warning.collectAsState()
    val diagnostics by lsp.diagnostics.collectAsState()
    val deviceBridgeState by deviceBridge.state.collectAsState()
    val androidDevState by androidDevelopment.status.collectAsState()
    val androidOperation by androidDevelopment.operation.collectAsState()
    val executionState by execution.state.collectAsState()
    val workspaceEnvironment = remember(workspaceKey, activeFile) { WorkspaceEnvironmentDetector.detect(files.root, activeFile) }
    val activeLanguageName = remember(activeFile) { LanguageRegistry.forFile(activeFile)?.name ?: if (activeFile.isBlank()) "No file" else "Plain Text" }
    val themes = remember(workspaceKey) { ThemeManager(files.root) }
    var themeTick by remember(workspaceKey) { mutableIntStateOf(0) }; val formatters = remember(workspaceKey, terminalManager, deviceBridge, androidDevelopment, extensions) { FormatterManager(files.root, terminalManager, deviceBridge, androidDevelopment, extensions.capabilities) }; val commands = remember(workspaceKey) { CommandManager(files.root) }
    val taskManager = remember(workspaceKey) { TaskManager(files.root, terminalManager) }; val worktreeManager = remember(workspaceKey) { WorktreeManager(files.root) }
    val recoveryStore = remember(ctx, workspaceKey, activeProject) { EditorStateStore(ctx, activeProject ?: "default") }
    val themeSnapshot = remember(workspaceKey, themeTick) { themes.snapshot() }
    SideEffect {
        DroideColors.install(themeSnapshot.ui)
        TerminalThemeAuthority.installDefaults(themeSnapshot.terminal)
    }
    val scheme = themeSnapshot.colorScheme
    LaunchedEffect(workspaceKey) { while (true) { agent.syncMcpHealth(); kotlinx.coroutines.delay(15_000) } }
    val hasTransientUi = drawer || showPalette || showQuickOpen || showWorkspaceSymbols || showGoToLine || showSessions || showSettings || showAndroidDevelopment || showSearch || showConnect || showModels || showBilling || showProblems || professionalSheet != null
    val portraitEditorOwnsStatusBar = workspaceTab == 1 && !isChatVisible && !hasTransientUi
    SideEffect { onPortraitEditorStatusBarHiddenChanged(portraitEditorOwnsStatusBar) }
    DisposableEffect(Unit) { onDispose { onPortraitEditorStatusBarHiddenChanged(false) } }
    DisposableEffect(agentBrowser, workspaceKey) {
        val binding = agentBrowser.bindOpenRequest { if (expandedWorkbench || isChatVisible) { sidebarToolName = DroideRailTool.Web.name; sidebarVisible = true } else workspaceTab = 4 }
        onDispose { agentBrowser.unbindOpenRequest(binding) }
    }
    fun captureActiveForLsp() {
        editorBridge.capture()
        val path = activeFile
        val doc = editorState.peek(path)
        if (path.isNotBlank() && doc?.loaded == true && doc.fullIntelligence) {
            scope.launch { runSuspendCatching { lsp.didChange(path, doc.content) } }
        }
    }
    fun open(path: String): Boolean {
        captureActiveForLsp()
        val nextTabs = EditorTabRetention.open(openFiles, path, activeFile, allowExistingDirtyOverflow = true) { editorState.peek(it)?.dirty == true }
        if (nextTabs == null) {
            scope.launch { snackbarHostState.showSnackbar("All editor tabs contain unsaved changes. Save or close a tab before opening another file.") }
            return false
        }
        EditorTabRetention.closeEvicted(openFiles, nextTabs, editorState) { evicted -> scope.launch { runSuspendCatching { lsp.didClose(evicted) } } }
        openFiles = nextTabs
        activeFile = path
        workspaceTab = 1
        return true
    }
    fun currentEditorLocation(): Triple<String, Int, Int>? {
        if (activeFile.isBlank()) return null
        val (line, column) = editorBridge.currentCursorLocation() ?: (1 to 1)
        return Triple(activeFile, line.coerceAtLeast(1), column.coerceAtLeast(1))
    }
    fun openLocation(path: String, line: Int, column: Int, recordHistory: Boolean = true): Boolean {
        val target = Triple(path, line.coerceAtLeast(1), column.coerceAtLeast(1))
        val origin = if (recordHistory) currentEditorLocation()?.takeIf { it != target } else null
        if (!open(path)) return false
        origin?.let { previous ->
            navigationBack = (navigationBack + previous).takeLast(100)
            navigationForward = emptyList()
        }
        navigationTarget = target
        navigationToken++
        return true
    }
    fun navigateBack() {
        val target = navigationBack.lastOrNull() ?: return
        val current = currentEditorLocation()
        if (!openLocation(target.first, target.second, target.third, recordHistory = false)) return
        navigationBack = navigationBack.dropLast(1)
        if (current != null) navigationForward = (navigationForward + current).takeLast(100)
    }
    fun navigateForward() {
        val target = navigationForward.lastOrNull() ?: return
        val current = currentEditorLocation()
        if (!openLocation(target.first, target.second, target.third, recordHistory = false)) return
        navigationForward = navigationForward.dropLast(1)
        if (current != null) navigationBack = (navigationBack + current).takeLast(100)
    }
    BackHandler(enabled = hasTransientUi || isChatVisible || navigationBack.isNotEmpty()) {
        when {
            drawer -> drawer = false
            showPalette -> showPalette = false
            showQuickOpen -> showQuickOpen = false
            showWorkspaceSymbols -> showWorkspaceSymbols = false
            showGoToLine -> showGoToLine = false
            showSessions -> showSessions = false
            showSettings -> showSettings = false
            showAndroidDevelopment -> showAndroidDevelopment = false
            showSearch -> showSearch = false
            showConnect -> showConnect = false
            showModels -> showModels = false
            showBilling -> showBilling = false
            showProblems -> showProblems = false
            professionalSheet != null -> professionalSheet = null
            isChatVisible -> isChatVisible = false
            navigationBack.isNotEmpty() -> navigateBack()
        }
    }
    fun problemLocations(): List<Triple<String, Int, Int>> = diagnostics.entries.flatMap { (uri, list) ->
        val rel = runCatching {
            val file = File(URI(uri)).canonicalFile
            if (!PathSecurity.contains(files.root, file)) return@runCatching null
            file.relativeTo(files.root.canonicalFile).invariantSeparatorsPath
        }.getOrNull() ?: return@flatMap emptyList()
        list.map { Triple(rel, it.line.coerceAtLeast(1), it.column.coerceAtLeast(1)) }
    }.distinct().sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
    fun navigateProblem(forward: Boolean) {
        val items = problemLocations()
        if (items.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar("No LSP problems") }
            return
        }
        val current = currentEditorLocation() ?: Triple("", 1, 1)
        val index = if (forward) {
            items.indexOfFirst { it.first > current.first || (it.first == current.first && (it.second > current.second || (it.second == current.second && it.third > current.third))) }
                .let { if (it >= 0) it else 0 }
        } else {
            items.indexOfLast { it.first < current.first || (it.first == current.first && (it.second < current.second || (it.second == current.second && it.third < current.third))) }
                .let { if (it >= 0) it else items.lastIndex }
        }
        val target = items[index]
        openLocation(target.first, target.second, target.third)
    }
    fun closeFileNow(path: String) {
        if (path == activeFile) captureActiveForLsp()
        val index = openFiles.indexOf(path)
        openFiles = openFiles.filterNot { it == path }
        scope.launch { runSuspendCatching { lsp.didClose(path) } }
        editorState.forgetAtOrUnder(path)
        if (activeFile == path) {
            activeFile = when {
                openFiles.isEmpty() -> ""
                index in openFiles.indices -> openFiles[index]
                else -> openFiles.last()
            }
        }
    }
    fun requestClose(path: String) {
        if (path == activeFile) captureActiveForLsp()
        if (editorState.peek(path)?.dirty == true) pendingCloseFile = path else closeFileNow(path)
    }
    fun requestProjectSwitch(id: String) {
        if (id == activeProject) return
        captureActiveForLsp()
        if (editorState.dirtyDocuments().isNotEmpty()) pendingProjectSwitch = id else onProjectSwitch(id)
    }
    fun onPathRenamed(oldPath: String, newPath: String) {
        val prefix = oldPath.trimEnd('/') + "/"
        fun remap(path: String): String = when {
            path == oldPath -> newPath
            path.startsWith(prefix) -> newPath.trimEnd('/') + "/" + path.removePrefix(prefix)
            else -> path
        }
        openFiles = openFiles.map(::remap).distinct()
        activeFile = remap(activeFile)
        navigationTarget = navigationTarget?.let { Triple(remap(it.first), it.second, it.third) }; navigationBack = navigationBack.map { Triple(remap(it.first), it.second, it.third) }; navigationForward = navigationForward.map { Triple(remap(it.first), it.second, it.third) }
    }
    fun onPathDeleted(path: String) {
        val prefix = path.trimEnd('/') + "/"
        fun removed(candidate: String): Boolean = candidate == path || candidate.startsWith(prefix)
        val removedActive = removed(activeFile)
        openFiles = openFiles.filterNot(::removed)
        navigationTarget = navigationTarget?.takeUnless { removed(it.first) }; navigationBack = navigationBack.filterNot { removed(it.first) }; navigationForward = navigationForward.filterNot { removed(it.first) }
        if (removedActive) activeFile = openFiles.lastOrNull().orEmpty()
    }
    fun applyPathMutation(mutation: EditorWorkspacePathMutation) { if (mutation.to == null) onPathDeleted(mutation.from) else onPathRenamed(mutation.from, mutation.to); consumedPathMutationSequence = mutation.sequence }
    DisposableEffect(editorState, workspaceKey) {
        editorState.pathMutationsAfter(consumedPathMutationSequence).forEach(::applyPathMutation); val binding = editorState.bindPathMutationListener(::applyPathMutation)
        onDispose { binding.close() }
    }
    suspend fun persistEditorState() {
        editorBridge.capture()
        if (openFiles.isEmpty() && editorState.dirtyDocuments().isEmpty()) {
            recoveryStore.clear()
            return
        }
        val dirty = editorState.dirtyDocuments().associate { it.path to it.content }
        recoveryStore.save(EditorRecoverySnapshot(openFiles, activeFile, dirty, editorState.selectionSnapshots(), editorState.reviewModePaths()))
    }
    suspend fun saveAllDirty(): Pair<String, Throwable>? {
        editorBridge.capture()
        for (doc in editorState.dirtyDocuments().sortedBy { it.path }) {
            val failure = doc.save(files).exceptionOrNull()
            if (failure != null) return doc.path to failure
            if (doc.fullIntelligence) runSuspendCatching { lsp.didSave(doc.path, doc.content) }
        }
        return null
    }
    suspend fun workstationSupported(): Boolean { if (deviceBridgeState.supported) return true; snackbarHostState.showSnackbar("Device Workstation and on-device Run/Build require Android 11 or newer; editor, local terminal and Git remain available"); return false }
    suspend fun runAndroidTask(
        title: String,
        action: suspend () -> AndroidDevelopmentManager.BuildResult,
    ): AndroidDevelopmentManager.BuildResult? { if (!workstationSupported()) return null
        val saveFailure = saveAllDirty()
        if (saveFailure != null) {
            snackbarHostState.showSnackbar("Build cancelled: save failed for ${saveFailure.first}: ${saveFailure.second.message}")
            return null
        }
        persistEditorState()
        snackbarHostState.showSnackbar("$title started")
        val result = runSuspendCatching { action() }.getOrElse { error ->
            androidOutputTitle = title
            androidOutputText = error.stackTraceToString().takeLast(300_000)
            showAndroidOutput = true
            snackbarHostState.showSnackbar("$title failed: ${error.message ?: error::class.java.simpleName}")
            return null
        }
        androidBuildDiagnostics = result.diagnostics
        androidOutputTitle = title
        androidOutputText = result.output
        result.localArtifacts.firstOrNull { it.extension.equals("apk", ignoreCase = true) }?.let { lastAndroidApk = it }
        val seconds = result.durationMs / 1000.0
        if (result.success) {
            val artifactText = if (result.localArtifacts.isEmpty()) "" else " · ${result.localArtifacts.size} artifact(s)"
            snackbarHostState.showSnackbar("$title succeeded in ${"%.1f".format(seconds)}s$artifactText")
        } else {
            showProblems = result.diagnostics.isNotEmpty()
            showAndroidOutput = result.diagnostics.isEmpty()
            snackbarHostState.showSnackbar("$title failed (exit ${result.exitCode}) · ${result.diagnostics.size} problem(s)")
        }
        return result
    }
    suspend fun runCurrentFile(presentAsSheet: Boolean = true) { if (!workstationSupported()) return
        if (activeFile.isBlank()) { snackbarHostState.showSnackbar("Open a file first"); return }
        val failure = saveAllDirty()
        if (failure != null) { snackbarHostState.showSnackbar("Cannot run: save failed for ${failure.first}"); return }
        persistEditorState()
        runSuspendCatching { execution.runFile(activeFile) }
            .onSuccess { output ->
                runnerMsg = output
                androidOutputTitle = "Run: ${activeFile.substringAfterLast('/')}"
                androidOutputText = output
                if (presentAsSheet) showAndroidOutput = true else { bottomToolName = DroideBottomTool.Output.name; bottomPanelVisible = true }
            }
            .onFailure { snackbarHostState.showSnackbar("Run failed: ${it.message}") }
    }
    suspend fun debugAndroidApp() { if (!workstationSupported()) return
        val failure = saveAllDirty()
        if (failure != null) {
            snackbarHostState.showSnackbar("Debug cancelled: save failed for ${failure.first}: ${failure.second.message}")
            return
        }
        persistEditorState()
        runSuspendCatching { execution.debugAndroid(activeFile) }
            .onSuccess { result ->
                androidBuildDiagnostics = result.build.diagnostics
                androidOutputTitle = "Android: Debug App"
                androidOutputText = result.build.output
                lastAndroidApk = result.apk
                workspaceTab = 5
                snackbarHostState.showSnackbar("Attached ${result.configuration.name} to PID ${result.launch.pid}")
            }
            .onFailure { if (it.message?.contains("DAP adapter", ignoreCase = true) == true) workspaceTab = 5; snackbarHostState.showSnackbar("Android debug failed: ${it.message}") }
    }
    suspend fun installAndRunAndroid() { if (!workstationSupported()) return
        val failure = saveAllDirty()
        if (failure != null) {
            snackbarHostState.showSnackbar("Install/Run cancelled: save failed for ${failure.first}: ${failure.second.message}")
            return
        }
        persistEditorState()
        val reusableDebugApk = lastAndroidApk?.takeIf { it.isFile && it.name.contains("debug", ignoreCase = true) }
        runSuspendCatching { execution.installAndRun(reusableDebugApk) }
            .onSuccess { result ->
                result.build?.let { build ->
                    androidBuildDiagnostics = build.diagnostics
                    androidOutputTitle = "Android: Install & Run"
                    androidOutputText = build.output
                }
                result.apk?.let { lastAndroidApk = it }
                when {
                    result.launchMessage != null -> snackbarHostState.showSnackbar(result.launchMessage)
                    result.build?.success == false -> {
                        showProblems = result.build.diagnostics.isNotEmpty()
                        showAndroidOutput = result.build.diagnostics.isEmpty()
                        snackbarHostState.showSnackbar("Install/Run stopped because the debug build failed")
                    }
                    else -> snackbarHostState.showSnackbar("No installable debug APK was produced")
                }
            }
            .onFailure { snackbarHostState.showSnackbar("Install/Run failed: ${it.message}") }
    }
    suspend fun loadAndroidLogcat(presentAsSheet: Boolean = true) {
        runSuspendCatching { androidDevelopment.logs() }
            .onSuccess { logs ->
                androidOutputTitle = "Android: Logcat"
                androidOutputText = logs
                if (presentAsSheet) showAndroidOutput = true else { bottomToolName = DroideBottomTool.Output.name; bottomPanelVisible = true }
            }
            .onFailure { snackbarHostState.showSnackbar("Logcat failed: ${it.message}") }
    }
    suspend fun saveActive(): Result<Unit> {
        editorBridge.capture()
        val path = activeFile
        if (path.isBlank()) return Result.success(Unit)
        val doc = editorState.peek(path) ?: return Result.success(Unit)
        if (!doc.dirty) return Result.success(Unit)
        return doc.save(files).onSuccess { if (doc.fullIntelligence) runSuspendCatching { lsp.didSave(path, doc.content) } }
    }
    suspend fun applyWorkspaceEdit(edit: LspWorkspaceEdit): Result<Int> = runSuspendCatching {
        editorBridge.capture()
        val updates = LspWorkspaceEditPreflight.prepare(edit, editorState, files)
        val plannedTabs = EditorTabRetention.planWorkspaceEdit(openFiles, updates.keys, activeFile) { editorState.peek(it)?.dirty == true }
        updates.forEach { (path, updated) ->
            editorState.document(path).applyWorkspaceText(updated, "Language-server rename — review before saving")
        }
        EditorTabRetention.closeEvicted(openFiles, plannedTabs, editorState) { evicted -> scope.launch { runSuspendCatching { lsp.didClose(evicted) } } }
        openFiles = plannedTabs
        persistEditorState()
        updates.forEach { (path, updated) -> if (editorState.peek(path)?.fullIntelligence == true) runSuspendCatching { lsp.didChange(path, updated) } }
        updates.size
    }
    fun showActionFailure(prefix: String, error: Throwable) {
        scope.launch { snackbarHostState.showSnackbar("$prefix: ${error.message ?: error::class.java.simpleName}") }
    }
    DisposableEffect(recoveryBridge, recoveryStore, workspaceKey) {
        val owner = Any()
        recoveryBridge.install(owner) {
            editorBridge.capture()
            val snapshot = if (openFiles.isEmpty() && editorState.dirtyDocuments().isEmpty()) null else EditorRecoverySnapshot(
                openFiles = openFiles,
                activeFile = activeFile,
                dirtyBuffers = editorState.dirtyDocuments().associate { it.path to it.content }, selections = editorState.selectionSnapshots(),
                reviewFiles = editorState.reviewModePaths(),
            )
            EditorRecoveryRequest(recoveryStore, snapshot)
        }
        onDispose { recoveryBridge.uninstall(owner) }
    }
    LaunchedEffect(recoveryStore, workspaceKey) {
        val recovered = runSuspendCatching { recoveryStore.load() }.getOrElse { error ->
            snackbarHostState.showSnackbar("Editor recovery unavailable; saved recovery data was kept: ${error.message}")
            return@LaunchedEffect
        } ?: return@LaunchedEffect
        val visible = EditorTabRetention.restoreDocuments(recovered, files, editorState)
        if (visible.isNotEmpty()) {
            openFiles = visible
            activeFile = recovered.activeFile.takeIf { it in visible } ?: visible.first()
            workspaceTab = 1
            val restoredDirty = editorState.dirtyDocuments().count { it.path in recovered.dirtyBuffers }
            if (restoredDirty > 0) snackbarHostState.showSnackbar("Recovered $restoredDirty unsaved file(s)")
        }
    }
    LaunchedEffect(recoveryStore, workspaceKey) {
        
        var persistedSignature = ""; var observedSignature = ""; var quietTicks = 0
        while (true) {
            kotlinx.coroutines.delay(2_000)
            val dirty = editorState.dirtyDocuments()
            val signature = buildString {
                append(activeFile).append('|').append(openFiles.joinToString(";"))
                dirty.sortedBy { it.path }.forEach { append('|').append(it.path).append(':').append(it.changeVersion) }; editorState.selectionSnapshots().toSortedMap().forEach { (path, sel) -> append('|').append(path).append('@').append(sel.start).append(':').append(sel.end) }; editorState.reviewModePaths().sorted().forEach { append('|').append(it).append("#review") }
            }
            quietTicks = if (signature == observedSignature) quietTicks + 1 else 0; observedSignature = signature
            val hasDirtyLargeFile = dirty.any { it.largeFileOptimized }
            val idleEnoughForLargeSnapshot = !hasDirtyLargeFile || quietTicks >= 2
            if (signature != persistedSignature && idleEnoughForLargeSnapshot) {
                runSuspendCatching { persistEditorState() }.onSuccess { persistedSignature = signature }.onFailure {
                    snackbarHostState.showSnackbar("Editor recovery save failed: ${it.message ?: "unknown error"}")
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        ctx.agentPreferences.data.collect { prefs ->
            val requested = prefs[stringPreferencesKey("provider_id")] ?: "pollinations"
            currentProvider = ProviderRegistry.findById(requested)?.id ?: "pollinations"
        }
    }
    LaunchedEffect(tokenWarning) {
        tokenWarning?.let { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(git) {
        while (true) {
            statusBranch = runSuspendCatching { git.branches().lines().firstOrNull { it.startsWith("*") }?.removePrefix("* ")?.trim() ?: statusBranch }.getOrDefault(statusBranch)
            sessionsCount = runSuspendCatching { agent.sessions.count() }.getOrDefault(sessionsCount)
            kotlinx.coroutines.delay(15_000)
        }
    }
    fun openBottomTool(tool: DroideBottomTool) {
        bottomToolName = tool.name
        if (expandedWorkbench) bottomPanelVisible = true
        else if (tool == DroideBottomTool.Terminal) workspaceTab = 2
    }
    MaterialTheme(colorScheme = scheme) {
        BoxWithConstraints(
            Modifier.fillMaxSize().onPreviewKeyEvent { composeEvent ->
                val event = composeEvent.nativeKeyEvent
                if (!hardwareShortcuts || event.action != AndroidKeyEvent.ACTION_DOWN || event.repeatCount > 0) return@onPreviewKeyEvent false
                val ctrl = event.isCtrlPressed
                val shift = event.isShiftPressed
                when {
                    ctrl && event.keyCode == AndroidKeyEvent.KEYCODE_SPACE -> { val ok = if (shift) editorBridge.requestSignatureHelp() else editorBridge.requestCompletion(); if (!ok) scope.launch { snackbarHostState.showSnackbar(if (shift) "Open a text editor for parameter info" else "Open a text editor for completion") }; true }
                    ctrl && shift && event.keyCode == AndroidKeyEvent.KEYCODE_P -> { showPalette = true; true }
                    ctrl && event.keyCode == AndroidKeyEvent.KEYCODE_P -> { showQuickOpen = true; true }
                    ctrl && event.keyCode == AndroidKeyEvent.KEYCODE_T -> { showWorkspaceSymbols = true; true }
                    ctrl && !shift && event.keyCode == AndroidKeyEvent.KEYCODE_F -> { if (!editorBridge.requestFind()) scope.launch { snackbarHostState.showSnackbar("Open a text editor to find") }; true }
                    ctrl && event.keyCode == AndroidKeyEvent.KEYCODE_W -> { if (activeFile.isNotBlank()) requestClose(activeFile); true }
                    ctrl && event.keyCode == AndroidKeyEvent.KEYCODE_PERIOD -> {
                        if (!editorBridge.requestQuickFix()) scope.launch { snackbarHostState.showSnackbar("Open a text editor for Quick Fix") }
                        true
                    }
                    ctrl && event.keyCode == AndroidKeyEvent.KEYCODE_G -> {
                        if (activeFile.isNotBlank()) { goToLineInput = ""; showGoToLine = true }
                        else scope.launch { snackbarHostState.showSnackbar("Open a file first") }
                        true
                    }
                    ctrl && shift && event.keyCode == AndroidKeyEvent.KEYCODE_O -> {
                        if (!editorBridge.requestDocumentSymbols()) scope.launch { snackbarHostState.showSnackbar("Open a text editor for symbols") }
                        true
                    }
                    ctrl && shift && event.keyCode == AndroidKeyEvent.KEYCODE_M -> { showProblems = true; true }
                    shift && event.keyCode == AndroidKeyEvent.KEYCODE_F12 -> {
                        if (!editorBridge.requestFindReferences()) scope.launch { snackbarHostState.showSnackbar("Open a text editor for references") }
                        true
                    }
                    event.keyCode == AndroidKeyEvent.KEYCODE_F12 -> {
                        if (!editorBridge.requestGoToDefinition()) scope.launch { snackbarHostState.showSnackbar("Open a text editor for definition") }
                        true
                    }
                    event.keyCode == AndroidKeyEvent.KEYCODE_F2 -> {
                        if (!editorBridge.requestRenameSymbol()) scope.launch { snackbarHostState.showSnackbar("Open a text editor to rename a symbol") }
                        true
                    }
                    event.keyCode == AndroidKeyEvent.KEYCODE_F8 -> { navigateProblem(forward = !shift); true }
                    shift && event.isAltPressed && event.keyCode == AndroidKeyEvent.KEYCODE_F -> {
                        if (!editorBridge.requestFormatDocument()) scope.launch { snackbarHostState.showSnackbar("Open a text editor to format") }
                        true
                    }
                    event.isAltPressed && event.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { navigateBack(); true }
                    event.isAltPressed && event.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { navigateForward(); true }
                    ctrl && shift && event.keyCode == AndroidKeyEvent.KEYCODE_F -> {
                        if (expandedWorkbench || isChatVisible) {
                            sidebarToolName = DroideRailTool.Search.name; sidebarVisible = true
                        } else { showSearch = true; workspaceTab = 0 }
                        true
                    }
                    ctrl && shift && event.keyCode == AndroidKeyEvent.KEYCODE_S -> {
                        scope.launch {
                            val failure = saveAllDirty()
                            if (failure == null) { persistEditorState(); snackbarHostState.showSnackbar("All files saved") }
                            else snackbarHostState.showSnackbar("Save failed for ${failure.first}: ${failure.second.message}")
                        }
                        true
                    }
                    ctrl && event.keyCode == AndroidKeyEvent.KEYCODE_S -> {
                        scope.launch { saveActive().onFailure { snackbarHostState.showSnackbar("Save failed: ${it.message}") } }
                        true
                    }
                    ctrl && event.keyCode == AndroidKeyEvent.KEYCODE_GRAVE -> {
                        if (expandedWorkbench) { bottomToolName = DroideBottomTool.Terminal.name; bottomPanelVisible = true } else workspaceTab = 2
                        true
                    }
                    shift && event.keyCode == AndroidKeyEvent.KEYCODE_F5 -> {
                        scope.launch { runSuspendCatching { debugger.stop() }.onFailure { snackbarHostState.showSnackbar("Stop failed: ${it.message}") } }
                        true
                    }
                    event.keyCode == AndroidKeyEvent.KEYCODE_F5 -> {
                        scope.launch {
                            runSuspendCatching {
                                when (debugger.state.value) {
                                    DebugState.STOPPED -> debugger.continueExecution()
                                    DebugState.RUNNING, DebugState.STARTING -> Unit
                                    else -> {
                                        check(activeFile.isNotBlank()) { "Open a file to debug" }
                                        val failure = saveAllDirty()
                                        check(failure == null) { "Save failed for ${failure?.first}" }
                                        execution.debugFile(activeFile)
                                    }
                                }
                            }.onFailure { snackbarHostState.showSnackbar("Debug: ${it.message}") }
                        }
                        true
                    }
                    event.keyCode == AndroidKeyEvent.KEYCODE_F10 -> {
                        if (debugger.state.value == DebugState.STOPPED) scope.launch { runSuspendCatching { debugger.next() }.onFailure { snackbarHostState.showSnackbar("Step over failed: ${it.message}") } }
                        true
                    }
                    shift && event.keyCode == AndroidKeyEvent.KEYCODE_F11 -> {
                        if (debugger.state.value == DebugState.STOPPED) scope.launch { runSuspendCatching { debugger.stepOut() }.onFailure { snackbarHostState.showSnackbar("Step out failed: ${it.message}") } }
                        true
                    }
                    event.keyCode == AndroidKeyEvent.KEYCODE_F11 -> {
                        if (debugger.state.value == DebugState.STOPPED) scope.launch { runSuspendCatching { debugger.stepIn() }.onFailure { snackbarHostState.showSnackbar("Step into failed: ${it.message}") } }
                        true
                    }
                    else -> false
                }
            }
        ) {
            val physicalLandscape = droidePhysicalLandscape(); val imeVisible = rememberDroideImeVisible(); val typingImeActive = droideTypingImeActive(imeVisible, accessoryInputFocus.activeTarget); val editorTypingImeActive = imeVisible && accessoryInputFocus.activeTarget == AccessoryInputTarget.EDITOR
            val terminalInputFocused = accessoryInputFocus.activeTarget.keyProfile() == AccessoryKeyProfile.TERMINAL; val terminalImeFocus = rememberLandscapeTerminalImeFocus(workspaceKey, physicalLandscape, imeVisible, terminalInputFocused)
            var agentLandscapeImeSession by remember(workspaceKey) { mutableStateOf(false) }
            LaunchedEffect(physicalLandscape, imeVisible, agentComposerFocused, isChatVisible) {
                if (!physicalLandscape || !imeVisible || !isChatVisible) agentLandscapeImeSession = false
                else if (agentComposerFocused) agentLandscapeImeSession = true
            }
            val effectiveLandscapeFocusMode = workbenchPrefs.landscapeFocusMode && !agentLandscapeImeSession
            val imePresentation = droideImePresentation(physicalLandscape, imeVisible, effectiveLandscapeFocusMode, sidebarVisible, isChatVisible); val portraitAgentFullscreen = droidePortraitAgentFullscreen(imePresentation.landscape, isChatVisible)
            val adaptiveFromConstraints = droideWorkbenchLayout(maxWidth, maxHeight, bottomPanelHeightDp.dp, physicalLandscape = physicalLandscape); val adaptive = droideImeStableWorkbenchLayout(adaptiveFromConstraints, maxWidth, bottomPanelHeightDp.dp, imePresentation)
            val compact = adaptive.compact; val medium = adaptive.medium; val expanded = adaptive.expanded
            SideEffect { expandedWorkbench = expanded }
            val sidebarTool = runCatching { DroideRailTool.valueOf(sidebarToolName) }.getOrDefault(DroideRailTool.Files)
            val bottomTool = runCatching { DroideBottomTool.valueOf(bottomToolName) }.getOrDefault(DroideBottomTool.Terminal)
            val focusLayout = rememberDroideLandscapeImeFocusLayout(adaptive, maxWidth, sidebarTool, imePresentation, effectiveLandscapeFocusMode, sidebarVisible, isChatVisible, agentPanelWidthDp.dp); val paneFit = focusLayout.paneFit; val userPaneFit = focusLayout.userPaneFit; val horizontalWorkbenchActive = expanded || (isChatVisible && imePresentation.landscape)
            val workbenchNavigation = rememberDroideWorkbenchNavigation(horizontalWorkbenchActive && (sidebarVisible || isChatVisible), paneFit.workbenchOverflowWidth, (maxWidth - DroideDimensions.ActivityRail).coerceAtLeast(0.dp))
            DroideAdaptiveWorkbenchEffects(adaptive, bottomPanelHeightDp.dp) { bottomPanelHeightDp = it.value }
            @Composable
            fun EditorTabs() = DroideEditorTabs(
                openFiles = openFiles, activeFile = activeFile,
                isDirty = { editorState.peek(it)?.dirty == true },
                onSelect = { captureActiveForLsp(); activeFile = it },
                onClose = ::requestClose,
            )
            @Composable
            fun EditorSurface() {
                Column(Modifier.fillMaxSize().background(DroideColors.Background)) {
                    EditorTabs()
                    val markdownPath = activeFile.takeIf(::isMarkdownPreviewPath)
                    val markdownMode = markdownPath?.let { markdownModes[it] ?: MarkdownPreviewMode.EDITOR }
                    DroideEditorToolbar(
                        path = activeFile,
                        canBack = navigationBack.isNotEmpty(),
                        canForward = navigationForward.isNotEmpty(),
                        onBack = ::navigateBack,
                        onForward = ::navigateForward,
                        onSave = { scope.launch { saveActive().onFailure { snackbarHostState.showSnackbar("Save failed: ${it.message}") } } },
                        onSymbols = { showWorkspaceSymbols = true },
                        onGoToLine = { if (activeFile.isNotBlank()) { goToLineInput = ""; showGoToLine = true } },
                        markdownPreviewMode = markdownMode,
                        onMarkdownPreviewModeChange = markdownPath?.let { path ->
                            { mode ->
                                editorBridge.capture()
                                markdownModes[path] = mode
                                if (mode == MarkdownPreviewMode.PREVIEW) {
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                }
                            }
                        },
                    )
                    runnerMsg?.let {
                        Row(Modifier.fillMaxWidth().background(DroideColors.Surface2).padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(it.take(2_000), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 2)
                            IconButton(onClick = { runnerMsg = null }, Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Close run output") }
                        }
                    }
                    if (activeFile.isNotBlank()) {
                        val document = editorState.document(activeFile)
                        val markdownMode = if (isMarkdownPreviewPath(activeFile)) markdownModes[activeFile] ?: MarkdownPreviewMode.EDITOR else MarkdownPreviewMode.EDITOR
                        @Composable
                        fun ActiveEditorPane(modifier: Modifier = Modifier) {
                            Box(modifier) {
                                EditorPane(
                                    files = files,
                                    path = activeFile,
                                    document = document,
                                    bridge = editorBridge,
                                    terminal = terminalManager.activeSession(),
                                    lsp = lsp,
                                    formatter = formatters,
                                    theme = themeSnapshot,
                                    codeStyleDefaults = workbenchPrefs.codeStyleDefaults,
                                    wordwrap = wordwrap,
                                    accessoryKeysComfortable = workbenchPrefs.accessoryKeysComfortable, accessoryInputFocus = accessoryInputFocus, accessoryKeysExpanded = editorAccessoryKeysExpanded, onAccessoryKeysExpandedChange = { editorAccessoryKeysExpanded = it },
                                    onToggleBreakpoint = { line -> debugger.toggleBreakpoint(activeFile, line) },
                                    onOpenLocation = ::openLocation,
                                    onApplyWorkspaceEdit = { edit ->
                                        scope.launch {
                                            applyWorkspaceEdit(edit)
                                                .onSuccess { count -> snackbarHostState.showSnackbar("Rename applied to $count file(s). Review and Save All when ready.") }
                                                .onFailure { snackbarHostState.showSnackbar("Rename failed: ${it.message}") }
                                        }
                                    },
                                    navigateLine = navigationTarget?.takeIf { it.first == activeFile }?.second,
                                    navigateColumn = navigationTarget?.takeIf { it.first == activeFile }?.third ?: 1,
                                    navigationToken = navigationToken,
                                    onNavigationConsumed = { if (navigationTarget?.first == activeFile) navigationTarget = null },
                                )
                            }
                        }

                        when (markdownMode) {
                            MarkdownPreviewMode.EDITOR -> ActiveEditorPane(Modifier.fillMaxSize())
                            MarkdownPreviewMode.PREVIEW -> {
                                LaunchedEffect(activeFile) { document.ensureLoaded(files) }
                                MarkdownFilePreview(document, Modifier.fillMaxSize())
                            }
                            MarkdownPreviewMode.SPLIT -> BoxWithConstraints(Modifier.fillMaxSize()) {
                                if (maxWidth >= 720.dp) {
                                    Row(Modifier.fillMaxSize()) {
                                        ActiveEditorPane(Modifier.weight(1f).fillMaxHeight())
                                        VerticalDivider(color = DroideColors.Border)
                                        MarkdownFilePreview(document, Modifier.weight(1f).fillMaxHeight())
                                    }
                                } else {
                                    Column(Modifier.fillMaxSize()) {
                                        ActiveEditorPane(Modifier.weight(1f).fillMaxWidth())
                                        HorizontalDivider(color = DroideColors.Border)
                                        MarkdownFilePreview(document, Modifier.weight(1f).fillMaxWidth())
                                    }
                                }
                            }
                        }
                    } else Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Code, null, Modifier.size(34.dp), tint = DroideColors.Muted)
                            Spacer(Modifier.height(8.dp))
                            Text("Open a file to start editing", color = DroideColors.Muted)
                        }
                    }
                }
            }
            @Composable
            fun TerminalSurface(showCreate: Boolean = true, typingFocus: Boolean = false) {
                Column(Modifier.fillMaxSize().background(DroideColors.Background)) {
                    if (tabs.size > 1 && !typingFocus) {
                        ScrollableTabRow(
                            selectedTabIndex = tabs.indexOfFirst { it.id == activeTab }.coerceAtLeast(0),
                            containerColor = DroideColors.Surface,
                            divider = { HorizontalDivider(color = DroideColors.Border) },
                        ) {
                            tabs.forEach { t -> Tab(selected = t.id == activeTab, onClick = { terminalManager.switch(t.id) }, text = { Text(t.title) }) }
                        }
                    }
                    if (showCreate) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { runCatching { terminalManager.createLocal("Local", files.root) }.onFailure { failure -> scope.launch { snackbarHostState.showSnackbar(failure.message ?: "Cannot open local terminal") } } }, enabled = terminalManager.canCreateTab) { Text("+ Local") }
                            OutlinedButton(onClick = {
                                if (deviceBridgeState.connected != null) runCatching { terminalManager.createDeviceWorkstation() }
                                    .onFailure { scope.launch { snackbarHostState.showSnackbar(it.message ?: "Cannot open Device Workstation") } }
                                else scope.launch { snackbarHostState.showSnackbar("Connect Device Workstation with Wireless Debugging first") }
                            }, enabled = terminalManager.canCreateTab) { Text("+ Workstation") }
                            var linuxSetupJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                            var linuxSetupBusy by remember { mutableStateOf(false) }
                            listOf(false to "+ Linux", true to "+ QEMU").forEach { (qemu, label) ->
                                OutlinedButton(onClick = {
                                    linuxSetupBusy = true
                                    linuxSetupJob = scope.launch {
                                        try { terminalManager.openLinuxTerminal(qemu) }
                                        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                                        catch (failure: Exception) { snackbarHostState.showSnackbar(failure.message ?: "Linux setup failed") }
                                        finally { linuxSetupBusy = false; linuxSetupJob = null }
                                    }
                                }, enabled = terminalManager.canCreateTab && !linuxSetupBusy) { Text(label) }
                            }
                            if (linuxSetupBusy) OutlinedButton(onClick = { linuxSetupJob?.cancel() }) { Text("Cancel setup") }
                            if (tabs.size > 1) OutlinedButton(onClick = { activeTab?.let { terminalManager.close(it) } }) { Text("Close") }
                        }
                    }
                    terminalManager.activeSession()?.let { TerminalPane(it, themeSnapshot, workbenchPrefs.accessoryKeysComfortable, accessoryInputFocus, terminalAccessoryKeysExpanded, typingFocus) { terminalAccessoryKeysExpanded = it } }
                        ?: Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Text("No terminal session", color = DroideColors.Muted) }
                }
            }
            @Composable
            fun DebugSurface() {
                DebugPanel(
                    debugger = debugger,
                    activeFile = activeFile,
                    onEnsureSaved = {
                        val failure = saveAllDirty()
                        if (failure == null) { persistEditorState(); true }
                        else { snackbarHostState.showSnackbar("Save failed for ${failure.first}: ${failure.second.message}"); false }
                    },
                    onOpenLocation = ::openLocation,
                    onOpenExtensions = { if (expanded || isChatVisible) { sidebarToolName = DroideRailTool.Extensions.name; sidebarVisible = true } else workspaceTab = 6 },
                )
            }
            @Composable
            fun FocusedWorkspace() {
                when (workspaceTab) {
                    0 -> if (showSearch) SearchPanel(files, documents, { editorBridge.capture() }, onOpen = ::openLocation) else FileExplorer(
                        files = files,
                        state = explorerState,
                        onOpen = { open(it) },
                        canMutatePath = { path -> editorBridge.capture(); !editorState.hasDirtyAtOrUnder(path) },
                        onRenamed = ::onPathRenamed,
                        onDeleted = ::onPathDeleted, safLinked = safLinked, onReconcileSaf = onReconcileSaf,
                    )
                    1 -> EditorSurface()
                    2 -> TerminalSurface()
                    3 -> GitPanel(git, onOpen = { open(it) }, onWorkspaceChanged = explorerState::requestRefresh)
                    4 -> BrowserPane(browserUrl, onUrlChange = { browserUrl = it }, agentBrowser = agentBrowser)
                    5 -> DebugSurface()
                    6 -> ExtensionsPane(extensions, androidDevelopment, deviceBridge, activeFile)
                    else -> EditorSurface()
                }
            }
            @Composable
            fun SidebarSurface(widthOverride: androidx.compose.ui.unit.Dp? = null) {
                val title = when (sidebarTool) {
                    DroideRailTool.Files -> projectName
                    DroideRailTool.Search -> "Search"
                    DroideRailTool.Git -> "Source Control"
                    DroideRailTool.Run -> "Run & Debug"
                    DroideRailTool.Web -> "Web"
                    DroideRailTool.Extensions -> "Extensions"
                }
                val sideWidth = widthOverride ?: if (sidebarTool == DroideRailTool.Files || sidebarTool == DroideRailTool.Search) adaptive.sidebarWidth else adaptive.wideSidebarWidth
                DroideSidebarFrame(title, sideWidth, onClose = { sidebarVisible = false }) {
                    when (sidebarTool) {
                        DroideRailTool.Files -> FileExplorer(
                            files = files,
                            state = explorerState,
                            onOpen = { open(it) },
                            canMutatePath = { path -> editorBridge.capture(); !editorState.hasDirtyAtOrUnder(path) },
                            onRenamed = ::onPathRenamed,
                            onDeleted = ::onPathDeleted, safLinked = safLinked, onReconcileSaf = onReconcileSaf,
                        )
                        DroideRailTool.Search -> SearchPanel(files, documents, { editorBridge.capture() }, onOpen = ::openLocation)
                        DroideRailTool.Git -> GitPanel(git, onOpen = { open(it) }, onWorkspaceChanged = explorerState::requestRefresh)
                        DroideRailTool.Run -> DebugSurface()
                        DroideRailTool.Web -> BrowserPane(browserUrl, onUrlChange = { browserUrl = it }, agentBrowser = agentBrowser)
                        DroideRailTool.Extensions -> ExtensionsPane(extensions, androidDevelopment, deviceBridge, activeFile)
                    }
                }
            }
            @Composable
            fun BottomPanelContent() {
                when (bottomTool) {
                    DroideBottomTool.Terminal -> TerminalSurface(showCreate = false)
                    DroideBottomTool.Problems -> ProblemsSheet(
                        files = files,
                        lsp = lsp,
                        buildDiagnostics = androidBuildDiagnostics,
                        onOpen = { path, line, col -> openLocation(path, line, col) },
                        onApplyWorkspaceEdit = { edit -> scope.launch { applyWorkspaceEdit(edit) } },
                    )
                    DroideBottomTool.Output -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
                        Text((if (androidOutputText.isNotBlank()) androidOutputText else runnerMsg ?: "No output yet").take(300_000), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                    }
                    DroideBottomTool.Build -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (androidOperation.running) androidOperation.message else androidOutputTitle, style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text("${androidBuildDiagnostics.size} problem(s)", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted); lastAndroidApk?.let { apk -> FileIdentityLabel(apk.absolutePath, apk.name, iconSize = 14.dp, color = DroideColors.Muted) } }
                        if (androidOperation.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (androidOutputText.isNotBlank()) Text(androidOutputText.takeLast(60_000), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            @Composable
            fun WorkspaceStatus() {
                Box(Modifier.fillMaxWidth().background(DroideColors.Status)) {
                    StatusBar(
                        branch = statusBranch,
                        language = activeLanguageName,
                        environment = workspaceEnvironment.kind.label + (androidDevState.compileSdk?.let { " · SDK $it" } ?: ""),
                        mode = mode.name,
                        providerId = currentProvider,
                        provider = ProviderRegistry.byId(currentProvider).name,
                        sessions = sessionsCount,
                        tokens = tokens.total,
                        onCompact = {
                            scope.launch {
                                val cfg = AgentPreferences.load(ctx)
                                when {
                                    cfg.model.isBlank() -> snackbarHostState.showSnackbar("Select a validated model first")
                                    else -> {
                                        val resolved = localLlamaModels.resolveAgentConfig(cfg.providerId, cfg.model, cfg.reasoningEffort)
                                        snackbarHostState.showSnackbar(agent.compactCurrent(resolved))
                                    }
                                }
                            }
                        },
                    )
                }
            }
            @Composable
            fun AgentSurface() {
                AgentPane(agent = agent, providerConnections = providerConnections, localLlamaModels = localLlamaModels, agentPlugins = agentPlugins, approvals = approvals, captureIdeContext = { editorBridge.capture(); captureAgentIdeContext(files.root, activeFile, activeLanguageName, openFiles, editorState, editorBridge.currentCursorLocation(), diagnostics, androidBuildDiagnostics) }, onSessions = { showSessions = true }, onUiError = { message -> scope.launchUiCatching { snackbarHostState.showSnackbar(message) } }, onComposerFocusChanged = { agentComposerFocused = it }, onClose = { isChatVisible = false })
            }
            fun selectRail(tool: DroideRailTool) {
                if (sidebarTool == tool) sidebarVisible = !sidebarVisible
                else { sidebarToolName = tool.name; sidebarVisible = true }
            }
            fun selectMediumTool(tool: DroideRailTool) {
                when (tool) {
                    DroideRailTool.Files -> { showSearch = false; workspaceTab = 0 }
                    DroideRailTool.Search -> { showSearch = true; workspaceTab = 0 }
                    DroideRailTool.Git -> workspaceTab = 3
                    DroideRailTool.Run -> workspaceTab = 5
                    DroideRailTool.Web -> workspaceTab = 4
                    DroideRailTool.Extensions -> workspaceTab = 6
                }
            }
            @Composable
            fun TopChrome(compactTop: Boolean) {
                val hasOutput = androidOutputText.isNotBlank() || !runnerMsg.isNullOrBlank(); val busy = executionState.busy || androidOperation.running
                DroideIdeHeader(
                    state = DroideIdeHeaderState(projectName, activeFile, compactTop, adaptive.wideHeader, isChatVisible, imePresentation.landscape, workbenchPrefs.landscapeFocusMode, busy, workspaceEnvironment.kind == WorkspaceKind.ANDROID_GRADLE, deviceBridgeState.connected != null, hasOutput, lastAndroidApk?.isFile == true, executionState.busy, workbenchNavigation.navigatorState.visible, workbenchNavigation.navigatorState.positionFraction, workbenchNavigation.navigatorState.viewportFraction, workbenchNavigation.navigatorState.canScrollLeft, workbenchNavigation.navigatorState.canScrollRight, mcpHealth),
                    actions = DroideIdeHeaderActions(
                        onProjects = { drawer = true },
                        onRun = {
                            if (workspaceEnvironment.kind == WorkspaceKind.ANDROID_GRADLE) {
                                if (deviceBridgeState.connected != null) scope.launch { installAndRunAndroid() } else showAndroidDevelopment = true
                            } else scope.launch { runCurrentFile(presentAsSheet = !expanded) }
                        },
                        onBuildDebug = { openBottomTool(DroideBottomTool.Build); scope.launch { runAndroidTask("Android: Build Debug APK") { execution.buildDebug() } } },
                        onTest = { openBottomTool(DroideBottomTool.Build); scope.launch { runAndroidTask("Android: Test") { execution.test() } } }, onTestExplorer = { professionalSheet = ProfessionalSheet.TEST_EXPLORER },
                        onLint = { openBottomTool(DroideBottomTool.Build); scope.launch { runAndroidTask("Android: Lint") { execution.lint() } } },
                        onReleaseApk = { openBottomTool(DroideBottomTool.Build); scope.launch { runAndroidTask("Android: Build Release APK") { execution.buildReleaseApk() } } },
                        onReleaseBundle = { openBottomTool(DroideBottomTool.Build); scope.launch { runAndroidTask("Android: Build Release Bundle") { execution.buildReleaseBundle() } } },
                        onBuildOutput = { if (expanded) openBottomTool(DroideBottomTool.Output) else { if (androidOutputText.isBlank() && !runnerMsg.isNullOrBlank()) { androidOutputTitle = "Run Output"; androidOutputText = runnerMsg.orEmpty() }; showAndroidOutput = true } },
                        onApkAnalyzer = { professionalSheet = ProfessionalSheet.APK_ANALYZER },
                        onCancel = { val canceled = execution.cancelActive(); scope.launch { snackbarHostState.showSnackbar(if (canceled) "Cancel requested" else "No cancellable operation is active") } },
                        onDebugAndroid = { scope.launch { debugAndroidApp() } },
                        onInstallRun = { scope.launch { installAndRunAndroid() } },
                        onLogcat = { scope.launch { loadAndroidLogcat(presentAsSheet = !expanded) } },
                        onDeviceWorkstation = { runCatching { terminalManager.createDeviceWorkstation() }.onSuccess { openBottomTool(DroideBottomTool.Terminal) }.onFailure { scope.launch { snackbarHostState.showSnackbar(it.message ?: "Cannot open Device Workstation") } } },
                        onAndroidDevelopment = { showAndroidDevelopment = true },
                        onQuickOpen = { showQuickOpen = true },
                        onSearchFiles = { if (expanded || isChatVisible) { sidebarToolName = DroideRailTool.Search.name; sidebarVisible = true } else { showSearch = true; workspaceTab = 0 } },
                        onWorkspaceSymbols = { showWorkspaceSymbols = true },
                        onProblems = { if (expanded) openBottomTool(DroideBottomTool.Problems) else showProblems = true },
                        onTerminal = { openBottomTool(DroideBottomTool.Terminal) },
                        onGit = { showSearch = false; if (expanded || isChatVisible) { sidebarToolName = DroideRailTool.Git.name; sidebarVisible = true } else workspaceTab = 3 }, onReviewChanges = { professionalSheet = ProfessionalSheet.CHANGE_REVIEW }, onIsolatedWorkspaces = { professionalSheet = ProfessionalSheet.ISOLATED_WORKSPACES }, onCapabilityHealth = { professionalSheet = ProfessionalSheet.CAPABILITY_HEALTH },
                        onTasks = { professionalSheet = ProfessionalSheet.TASKS },
                        onAgent = { isChatVisible = !isChatVisible }, onFocusMode = { scope.launch { WorkbenchPreferences.setLandscapeFocusMode(ctx, !workbenchPrefs.landscapeFocusMode) } },
                        onCommandPalette = { showPalette = true },
                        onRunDebug = { if (expanded || isChatVisible) { sidebarToolName = DroideRailTool.Run.name; sidebarVisible = true } else workspaceTab = 5 },
                        onSessions = { showSessions = true },
                        onExtensions = { if (expanded || isChatVisible) { sidebarToolName = DroideRailTool.Extensions.name; sidebarVisible = true } else workspaceTab = 6 },
                        onConnectProvider = { showConnect = true },
                        onModels = { showModels = true },
                        onBilling = { showBilling = true },
                        onSettings = { settingsInitialCategoryName = null; showSettings = true },
                        onMcpRetry = { server -> scope.launch { snackbarHostState.showSnackbar(retryMcpForUi(agent, server)) } },
                        onManageMcp = { settingsInitialCategoryName = "TOOLS"; showSettings = true },
                        onWorkbenchScrollLeft = workbenchNavigation.scrollLeft, onWorkbenchScrollRight = workbenchNavigation.scrollRight, onWorkbenchSeekFraction = workbenchNavigation.seekFraction,
                    ),
                )
            }
            Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }, containerColor = DroideColors.Background) { pad ->
                Column(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()) {
                    AnimatedVisibility(visible = !imePresentation.landscapeImeActive && !portraitAgentFullscreen && !terminalImeFocus.active, enter = expandVertically(animationSpec = androidx.compose.animation.core.tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing)) + fadeIn(animationSpec = androidx.compose.animation.core.tween(140)), exit = shrinkVertically(animationSpec = androidx.compose.animation.core.tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing)) + fadeOut(animationSpec = androidx.compose.animation.core.tween(120))) { TopChrome(compactTop = compact || adaptive.shortHeight) }
                    when {
                        terminalImeFocus.active -> Box(Modifier.weight(1f).fillMaxWidth()) {
                            LandscapeTerminalTypingSurface(terminalImeFocus.exit) { TerminalSurface(showCreate = false, typingFocus = true) }
                        }
                        portraitAgentFullscreen -> {
                            Box(Modifier.weight(1f).fillMaxWidth()) { AgentSurface() }
                        }
                        expanded -> {
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                DroideActivityRail(
                                    selected = sidebarTool,
                                    sidebarVisible = imePresentation.sidebarVisible,
                                    bottomPanelVisible = bottomPanelVisible,
                                    onTool = ::selectRail,
                                    onTerminal = { bottomToolName = DroideBottomTool.Terminal.name; bottomPanelVisible = !bottomPanelVisible },
                                    onProjects = { drawer = true },
                                    onSettings = { settingsInitialCategoryName = null; showSettings = true },
                                )
                                DroideHorizontalWorkbench(
                                    overflowWidth = paneFit.workbenchOverflowWidth, scrollState = workbenchNavigation.scrollState,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                ) {
                                    if (sidebarVisible) { DroideClippedPaneRegion(focusLayout.sidebarRegionWidth, userPaneFit.sidebarWidth + 1.dp) { Row { SidebarSurface(userPaneFit.sidebarWidth); VerticalDivider(color = DroideColors.Border) } } }
                                    Column(Modifier.width(focusLayout.editorViewportWidth).fillMaxHeight()) {
                                        Box(Modifier.weight(1f).fillMaxWidth()) { EditorSurface() }
                                        if (bottomPanelVisible && !editorTypingImeActive) {
                                            HorizontalDivider(color = DroideColors.Border)
                                            DroideBottomPanelFrame(
                                                active = bottomTool,
                                                height = adaptive.effectiveBottomPanelHeight,
                                                maxHeight = adaptive.bottomPanelMaxHeight,
                                                onHeightChange = { bottomPanelHeightDp = it.coerceAtMost(adaptive.bottomPanelMaxHeight).value },
                                                onSelect = { bottomToolName = it.name },
                                                onCollapse = { bottomPanelVisible = false },
                                            ) { BottomPanelContent() }
                                        } else if (!typingImeActive) {
                                            Surface(color = DroideColors.Surface) {
                                                Row(Modifier.fillMaxWidth().height(35.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                                    DroideBottomTool.entries.forEach { tool -> TextButton(onClick = { bottomToolName = tool.name; bottomPanelVisible = true }) { Text(tool.label, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted) } }
                                                    Spacer(Modifier.weight(1f))
                                                    IconButton(onClick = { bottomPanelVisible = true }, Modifier.size(34.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Open bottom panel") }
                                                }
                                            }
                                        }
                                        if (!typingImeActive) WorkspaceStatus()
                                    }
                                    if (isChatVisible) {
                                        val maxAgent = adaptive.agentPanelMaxWidth
                                        DroideClippedPaneRegion(focusLayout.agentRegionWidth, userPaneFit.agentWidth + 10.dp, androidx.compose.ui.Alignment.CenterEnd) { DroideResizableAgentPanel(userPaneFit.agentWidth, userPaneFit.agentMinWidth, minOf(maxAgent, userPaneFit.agentMaxWidth), { agentPanelWidthDp = it.value }) { AgentSurface() } }
                                    }
                                }
                            }
                        }
                        isChatVisible -> {
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                DroideActivityRail(sidebarTool, imePresentation.sidebarVisible, false, ::selectRail, { workspaceTab = 2 }, { drawer = true }, { settingsInitialCategoryName = null; showSettings = true })
                                DroideHorizontalWorkbench(
                                    overflowWidth = paneFit.workbenchOverflowWidth, scrollState = workbenchNavigation.scrollState,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                ) {
                                    if (sidebarVisible) { DroideClippedPaneRegion(focusLayout.sidebarRegionWidth, userPaneFit.sidebarWidth + 1.dp) { Row { SidebarSurface(userPaneFit.sidebarWidth); VerticalDivider(color = DroideColors.Border) } } }
                                    Column(Modifier.width(focusLayout.editorViewportWidth).fillMaxHeight()) { Box(Modifier.weight(1f).fillMaxWidth()) { FocusedWorkspace() }; if (!typingImeActive) WorkspaceStatus() }
                                    DroideClippedPaneRegion(focusLayout.agentRegionWidth, userPaneFit.agentWidth + 10.dp, androidx.compose.ui.Alignment.CenterEnd) { DroideResizableAgentPanel(userPaneFit.agentWidth, userPaneFit.agentMinWidth, userPaneFit.agentMaxWidth, { agentPanelWidthDp = it.value }) { AgentSurface() } }
                                }
                            }
                        }
                        medium -> Row(Modifier.weight(1f).fillMaxWidth()) {
                            DroideActivityRail(when (workspaceTab) { 3 -> DroideRailTool.Git; 4 -> DroideRailTool.Web; 5 -> DroideRailTool.Run; 6 -> DroideRailTool.Extensions; else -> if (showSearch) DroideRailTool.Search else DroideRailTool.Files }, true, workspaceTab == 2, ::selectMediumTool, { workspaceTab = 2 }, { drawer = true }, { settingsInitialCategoryName = null; showSettings = true })
                            Column(Modifier.weight(1f).fillMaxHeight()) { Box(Modifier.weight(1f)) { FocusedWorkspace() }; if (!typingImeActive) WorkspaceStatus() }
                        }
                        else -> {
                            Box(Modifier.weight(1f).fillMaxWidth()) { FocusedWorkspace() }
                            if (!typingImeActive) {
                                WorkspaceStatus()
                                DroideCompactNavigation(workspaceTab) { id -> showSearch = false; workspaceTab = id }
                            }
                        }
                    }
                }
            }
        }
        if (drawer) ModalBottomSheet(onDismissRequest = { drawer = false }) {
            ProjectDrawer(
                manager = projectManager,
                onPickSaf = {
                    drawer = false
                    captureActiveForLsp()
                    if (editorState.dirtyDocuments().isNotEmpty()) pendingSafPick = true else onPickSaf()
                },
                onSwitch = { id -> drawer = false; requestProjectSwitch(id) }, onCreateProject = { name, template -> captureActiveForLsp(); check(editorState.dirtyDocuments().isEmpty()) { "Save or discard unsaved editor changes before creating a project" }; onProjectCreate(name, template).also { drawer = false } },
                onDismiss = { drawer = false },
            )
        }
        if (showGoToLine) {
            AlertDialog(
                onDismissRequest = { showGoToLine = false },
                title = { Text("Go to Line") },
                text = {
                    OutlinedTextField(
                        value = goToLineInput,
                        onValueChange = { goToLineInput = it.filter(Char::isDigit).take(9) },
                        label = { Text("Line number") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val line = goToLineInput.toIntOrNull()?.coerceAtLeast(1) ?: return@Button
                            showGoToLine = false
                            openLocation(activeFile, line, 1)
                        },
                        enabled = goToLineInput.toIntOrNull()?.let { it > 0 } == true,
                    ) { Text("Go") }
                },
                dismissButton = { TextButton(onClick = { showGoToLine = false }) { Text("Cancel") } },
            )
        }
        if (showProblems) ModalBottomSheet(onDismissRequest = { showProblems = false }) {
            ProblemsSheet(
                files = files,
                lsp = lsp,
                buildDiagnostics = androidBuildDiagnostics,
                onOpen = { path, line, col ->
                    showProblems = false
                    openLocation(path, line, col)
                },
                onApplyWorkspaceEdit = { edit ->
                    scope.launch {
                        val result = applyWorkspaceEdit(edit)
                        result.onSuccess { count -> snackbarHostState.showSnackbar("Applied $count code-action edit(s) to buffers") }
                            .onFailure { snackbarHostState.showSnackbar("Quick Fix failed: ${it.message}") }
                    }
                },
                onClose = { showProblems = false },
            )
        }
        ProfessionalSheetHost(professionalSheet, taskManager, git, worktreeManager, androidDevelopment, deviceBridge, debugger, extensions, activeFile, lastAndroidApk, onOpenSourceControl = { professionalSheet = null; workspaceTab = 3; if (expandedWorkbench || isChatVisible) { sidebarToolName = DroideRailTool.Git.name; sidebarVisible = true } },
            beforeTaskRun = { saveAllDirty()?.let { throw IllegalStateException("Save failed for ${it.first}: ${it.second.message}", it.second) }; persistEditorState() }, onDismiss = { professionalSheet = null })
        if (showQuickOpen) QuickOpenDialog(
            files = files,
            onOpen = { path -> open(path) },
            onDismiss = { showQuickOpen = false },
        )
        if (showWorkspaceSymbols) WorkspaceSymbolsDialog(
            lsp = lsp,
            pathHint = activeFile,
            onOpen = { path, line, col -> openLocation(path, line, col) },
            onDismiss = { showWorkspaceSymbols = false },
        )
        if (showPalette) CommandPalette(files, commands, onPick = { cmd ->
            val clean = cmd.removePrefix("/").trim().substringBefore(" ")
            when (clean) {
                "connect" -> { showConnect = true }
                "models" -> { showModels = true }
                "open-file" -> { showQuickOpen = true }
                "workspace-symbols" -> { showWorkspaceSymbols = true }
                "search" -> { if (expandedWorkbench || isChatVisible) { sidebarToolName = DroideRailTool.Search.name; sidebarVisible = true } else { showSearch = true; workspaceTab = 0 } }
                "problems" -> { if (expandedWorkbench) openBottomTool(DroideBottomTool.Problems) else showProblems = true }
                "settings" -> { showSettings = true }
                "explorer" -> { showSearch = false; if (expandedWorkbench || isChatVisible) { sidebarToolName = DroideRailTool.Files.name; sidebarVisible = true } else workspaceTab = 0 }
                "source-control" -> { if (expandedWorkbench || isChatVisible) { sidebarToolName = DroideRailTool.Git.name; sidebarVisible = true } else workspaceTab = 3 }
                "terminal" -> { openBottomTool(DroideBottomTool.Terminal) }
                "debug" -> { if (expandedWorkbench || isChatVisible) { sidebarToolName = DroideRailTool.Run.name; sidebarVisible = true } else workspaceTab = 5 }
                "browser" -> { if (expandedWorkbench || isChatVisible) { sidebarToolName = DroideRailTool.Web.name; sidebarVisible = true } else workspaceTab = 4 }
                "tasks" -> { professionalSheet = ProfessionalSheet.TASKS }
                "extensions" -> { if (expandedWorkbench || isChatVisible) { sidebarToolName = DroideRailTool.Extensions.name; sidebarVisible = true } else workspaceTab = 6 }
                "android-development" -> { showAndroidDevelopment = true }
                "android-build-debug" -> scope.launch { runAndroidTask("Android: Build Debug APK") { execution.buildDebug() } }
                "android-debug-app" -> scope.launch { debugAndroidApp() }
                "android-build-release-apk" -> scope.launch { runAndroidTask("Android: Build Release APK") { execution.buildReleaseApk() } }
                "android-build-release-bundle" -> scope.launch { runAndroidTask("Android: Build Release Bundle") { execution.buildReleaseBundle() } }
                "android-test" -> scope.launch { runAndroidTask("Android: Test") { execution.test() } }
                "android-lint" -> scope.launch { runAndroidTask("Android: Lint") { execution.lint() } }
                "android-install-run" -> scope.launch { installAndRunAndroid() }
                "android-logcat" -> scope.launch { loadAndroidLogcat() }
                "android-build-output" -> {
                    if (androidOutputText.isBlank()) scope.launch { snackbarHostState.showSnackbar("No Android build output yet") }
                    else showAndroidOutput = true
                }
                "build-cancel" -> {
                    val canceled = execution.cancelActive()
                    scope.launch { snackbarHostState.showSnackbar(if (canceled) "Cancel requested" else "No active Build/Run/Test operation") }
                }
                "save-all" -> scope.launch {
                    val failure = saveAllDirty()
                    if (failure == null) {
                        persistEditorState()
                        snackbarHostState.showSnackbar("All files saved")
                    } else {
                        snackbarHostState.showSnackbar("Save failed for ${failure.first}: ${failure.second.message}")
                    }
                }
                "undo" -> scope.launch { snackbarHostState.showSnackbar(agent.undoLast()) }
                "compact" -> scope.launch {
                    val cfg = AgentPreferences.load(ctx)
                    if (cfg.model.isBlank()) {
                        snackbarHostState.showSnackbar("Select a validated model first")
                    } else {
                        val resolved = localLlamaModels.resolveAgentConfig(cfg.providerId, cfg.model, cfg.reasoningEffort)
                        snackbarHostState.showSnackbar(agent.compactCurrent(resolved))
                    }
                }
                else -> scope.launch {
                    val resolved = commands.resolve(cmd.removePrefix("/"), "") ?: cmd
                    val cfg = AgentPreferences.load(ctx)
                    if (cfg.model.isBlank()) {
                        snackbarHostState.showSnackbar("Select a validated model first")
                    } else {
                        val config = localLlamaModels.resolveAgentConfig(cfg.providerId, cfg.model, cfg.reasoningEffort)
                        agent.chatAsync(scope, resolved, config)
                    }
                }
            }
        }, onDismiss = { showPalette = false })
        if (showConnect) ModalBottomSheet(onDismissRequest = { showConnect = false }) {
            ConnectSheet(connectionBackend = providerConnections, initialProviderId = currentProvider, onConnected = { id, validatedModel ->
                scope.launchUiCatching(onError = { snackbarHostState.showSnackbar("Provider connected but preferences could not be saved: ${it.message ?: it::class.java.simpleName}") }) { localLlamaModels.selectRemote(id, validatedModel); ProviderAuthManager.cleanupLegacyWorkspaceSecrets(files.root); currentProvider = id; snackbarHostState.showSnackbar("Connected to ${ProviderRegistry.byId(id).name} · $validatedModel") }
            }, onDisconnected = { id -> scope.launch { snackbarHostState.showSnackbar("Disconnected ${ProviderRegistry.byId(id).name}") } }, onDismiss = { showConnect = false })
        }
        if (showModels) ModalBottomSheet(onDismissRequest = { showModels = false }) {
            ModelPickerSheet(providerId = currentProvider, connectionBackend = providerConnections,
                onPick = { m -> scope.launchUiCatching(onError = { snackbarHostState.showSnackbar("Could not select model: ${it.message ?: it::class.java.simpleName}") }) { localLlamaModels.selectRemote(currentProvider, m) } },
                onConnectProvider = { showModels = false; showConnect = true }, onDismiss = { showModels = false })
        }
        if (showSessions) ModalBottomSheet(onDismissRequest = { showSessions = false }) {
            SessionDrawer(manager = agent.sessions, onPick = { s -> agent.loadSession(s); showSessions = false; isChatVisible = true }, onDismiss = { showSessions = false })
        }
        if (showSettings) SettingsScreen(
            themes = themes, formatters = formatters, wordwrap = wordwrap, hardwareShortcuts = hardwareShortcuts,
            accessoryKeysComfortable = workbenchPrefs.accessoryKeysComfortable, codeStyleDefaults = workbenchPrefs.codeStyleDefaults, androidDevelopment = androidDevelopment,
            deviceBridge = deviceBridge, githubAccount = githubAccount, projectName = projectName, initialPermissionPolicy = agent.perms.policySnapshot(),
            onWordwrap = { enabled -> scope.launch { WorkbenchPreferences.setWordWrap(ctx, enabled) } },
            onHardwareShortcuts = { enabled -> scope.launch { WorkbenchPreferences.setHardwareShortcuts(ctx, enabled) } },
            onAccessoryKeysComfortable = { enabled -> scope.launch { WorkbenchPreferences.setAccessoryKeysComfortable(ctx, enabled) } },
            onDetectIndentation = { enabled -> scope.launch { WorkbenchPreferences.setDetectIndentation(ctx, enabled) } },
            onIndentStyle = { style -> scope.launch { WorkbenchPreferences.setIndentStyle(ctx, style) } },
            onTabWidth = { value -> scope.launch { WorkbenchPreferences.setTabWidth(ctx, value) } },
            onIndentSize = { value -> scope.launch { WorkbenchPreferences.setIndentSize(ctx, value) } },
            onContinuationIndent = { value -> scope.launch { WorkbenchPreferences.setContinuationIndent(ctx, value) } },
            onTheme = { themes.select(it); themeTick++ }, onPermissionPolicyChange = onPermissionPolicyChange,
            onOpenProviders = { showSettings = false; showConnect = true }, onOpenModels = { showSettings = false; showModels = true },
            onOpenExtensions = { showSettings = false; if (expandedWorkbench || isChatVisible) { sidebarToolName = DroideRailTool.Extensions.name; sidebarVisible = true } else workspaceTab = 6 },
            onOpenFiles = { showSettings = false; showSearch = false; if (expandedWorkbench || isChatVisible) { sidebarToolName = DroideRailTool.Files.name; sidebarVisible = true } else workspaceTab = 0 },
            onOpenTerminal = { showSettings = false; openBottomTool(DroideBottomTool.Terminal) },
            onOpenGit = { showSettings = false; showSearch = false; if (expandedWorkbench || isChatVisible) { sidebarToolName = DroideRailTool.Git.name; sidebarVisible = true } else workspaceTab = 3 },
            onOpenRunDebug = { showSettings = false; if (expandedWorkbench || isChatVisible) { sidebarToolName = DroideRailTool.Run.name; sidebarVisible = true } else workspaceTab = 5 },
            onOpenAgent = { showSettings = false; isChatVisible = true }, onOpenAgentSessions = { showSettings = false; showSessions = true },
            onOpenCapabilityHealth = { showSettings = false; professionalSheet = ProfessionalSheet.CAPABILITY_HEALTH },
            mcpHealth = mcpHealth,
            onRetryMcp = { server -> scope.launch { snackbarHostState.showSnackbar(retryMcpForUi(agent, server)) } },
            onOpenMcpConfig = { scope.launch { ensureMcpWorkspaceConfig(files); showSettings = false; open(MCP_WORKSPACE_CONFIG) } },
            initialCategoryName = settingsInitialCategoryName,
            onDismiss = { showSettings = false; settingsInitialCategoryName = null },
        )
        if (showAndroidDevelopment) ModalBottomSheet(onDismissRequest = { showAndroidDevelopment = false }) {
            AndroidDevelopmentSheet(androidDevelopment, deviceBridge) { showAndroidDevelopment = false }
        }
        if (showBilling) ModalBottomSheet(onDismissRequest = { showBilling = false }) {
            BillingSheet(tracker = agent.tokens, onDismiss = { showBilling = false })
        }
        if (showAndroidOutput) ModalBottomSheet(onDismissRequest = { showAndroidOutput = false }) {
            AndroidOutputSheet(androidOutputTitle, androidOutputText, onDismiss = { showAndroidOutput = false })
        }
        pendingCloseFile?.let { path ->
            val doc = editorState.peek(path)
            AlertDialog(
                onDismissRequest = { pendingCloseFile = null },
                title = { Text("Unsaved changes") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { FileIdentityLabel(path, path.substringAfterLast('/'), iconSize = 18.dp); Text("This file has unsaved changes.") } },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            editorBridge.capture()
                            val result = doc?.save(files) ?: Result.success(Unit)
                            if (result.isSuccess) {
                                pendingCloseFile = null
                                closeFileNow(path)
                            } else snackbarHostState.showSnackbar("Save failed: ${result.exceptionOrNull()?.message}")
                        }
                    }) { Text("Save & close") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            doc?.discardUnsaved()
                            pendingCloseFile = null
                            closeFileNow(path)
                        }) { Text("Discard") }
                        OutlinedButton(onClick = { pendingCloseFile = null }) { Text("Cancel") }
                    }
                },
            )
        }
        pendingProjectSwitch?.let { targetId ->
            val dirty = editorState.dirtyDocuments()
            val targetName = projects.firstOrNull { it.id == targetId }?.name ?: "project"
            AlertDialog(
                onDismissRequest = { pendingProjectSwitch = null },
                title = { Text("Unsaved changes") },
                text = { Text("${dirty.size} file(s) have unsaved changes. Save them before switching to $targetName?") },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            val failure = saveAllDirty()
                            if (failure == null) {
                                persistEditorState()
                                pendingProjectSwitch = null
                                onProjectSwitch(targetId)
                            } else snackbarHostState.showSnackbar("Save failed for ${failure.first}: ${failure.second.message}")
                        }
                    }) { Text("Save & switch") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            scope.launch {
                                editorState.dirtyDocuments().forEach { it.discardUnsaved() }
                                persistEditorState()
                                pendingProjectSwitch = null
                                onProjectSwitch(targetId)
                            }
                        }) { Text("Discard & switch") }
                        OutlinedButton(onClick = { pendingProjectSwitch = null }) { Text("Cancel") }
                    }
                },
            )
        }
        if (pendingSafPick) {
            val dirty = editorState.dirtyDocuments()
            AlertDialog(
                onDismissRequest = { pendingSafPick = false },
                title = { Text("Unsaved changes") },
                text = { Text("${dirty.size} file(s) have unsaved changes. Save them before opening another folder?") },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            val failure = saveAllDirty()
                            if (failure == null) {
                                persistEditorState()
                                pendingSafPick = false
                                onPickSaf()
                            } else snackbarHostState.showSnackbar("Save failed for ${failure.first}: ${failure.second.message}")
                        }
                    }) { Text("Save & open") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            scope.launch {
                                editorState.dirtyDocuments().forEach { it.discardUnsaved() }
                                persistEditorState()
                                pendingSafPick = false
                                onPickSaf()
                            }
                        }) { Text("Discard & open") }
                        OutlinedButton(onClick = { pendingSafPick = false }) { Text("Cancel") }
                    }
                },
            )
        }
    }
}

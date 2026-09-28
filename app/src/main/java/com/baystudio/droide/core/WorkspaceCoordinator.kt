package com.baystudio.droide.core

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class WorkspaceRuntime(
    val project: DroideProject,
    val files: FileRepository,
    val documentAuthority: WorkspaceDocumentAuthorityBridge,
    val terminals: TerminalManager,
    val git: GitManager,
    val lsp: LspManager,
    val debugger: DebugManager,
    val agent: AgentService,
    val agentBrowser: AgentBrowserController,
    val agentPlugins: WorkspaceAgentPluginHost,
    val androidDevelopment: AndroidDevelopmentManager,
    val extensions: DevelopmentExtensionsManager,
    val capabilities: UniversalCapabilityRegistry,
    val managedPackageCandidates: ManagedPackageCandidateManager,
    val managedLanguageServers: ManagedLanguageServerProvisioner,
    val managedLanguageServerCertification: ManagedLanguageServerCertificationRunner,
    val managedLanguageServerCertificationManager: ManagedLanguageServerCertificationManager,
    val managedDebugAdapters: ManagedDebugAdapterProvisioner,
    val debugCertification: DebugCertificationManager,
    val physicalDeviceCertification: PhysicalDeviceCertificationManager,
    val universalToolDiscoveryCertification: UniversalToolDiscoveryCertificationManager,
    val androidInspection: AndroidInspectionManager,
    val androidStability: AndroidStabilityRunner,
    val pluginRuntime: ManagedPluginRuntime,
    val extensionPlatform: ManagedExtensionPlatform,
    val execution: BuildRunDebugCoordinator,
    val safMirror: SafMirror? = null,
) {
    suspend fun reconcileSaf(): String? {
        val mirror = safMirror ?: return null
        val dirty = documentAuthority.dirtySnapshots()
        check(dirty.isEmpty()) {
            "Save or discard unsaved editor changes before refreshing the external project mirror"
        }
        val message = mirror.refreshFromTree()
        val reconciled = documentAuthority.reconcilePersistedWorkspace()
        check(reconciled.conflicts.isEmpty()) { "Linked folder synchronized, but editor conflicts need review: ${reconciled.conflicts.take(8).joinToString()}" }
        return message
    }

    suspend fun shutdownGracefully() = withContext(NonCancellable) {
        


        try { agent.shutdown() } catch (_: Exception) {}
        try { agentPlugins.close() } catch (_: Exception) {}
        try { extensionPlatform.close() } catch (_: Exception) {}
        runSuspendCatching { debugger.shutdown() }
        runSuspendCatching { lsp.shutdown() }
        try { capabilities.close() } catch (_: Exception) {}
        withContext(Dispatchers.Main.immediate) {
            try { terminals.dispose(preserveLocalSessions = false) } catch (_: Exception) {}
        }
        try { AgentPluginRuntime.cleanupWorkspace(File(project.rootPath)) } catch (_: Exception) {}
    }

     
    fun close(preserveLocalTerminals: Boolean = false) {
        agent.shutdown()
        agentPlugins.close()
        extensionPlatform.close()
        debugger.close()
        lsp.close()
        capabilities.close()
        terminals.dispose(preserveLocalSessions = preserveLocalTerminals)
        AgentPluginRuntime.cleanupWorkspace(File(project.rootPath))
    }
}

// One authoritative, transactional owner for active project file/Git/terminal/agent services.
class WorkspaceCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    val projects: ProjectManager = ProjectManager(context.filesDir),
    private val approvals: ApprovalManager = ApprovalManager(),
) {
    private val permissionPolicies = PermissionPolicyStore(context.filesDir)
    private val _runtime = MutableStateFlow<WorkspaceRuntime?>(null)
    private val _startup = MutableStateFlow(WorkspaceStartupStatus())
    private val transitionMutex = Mutex()
    private var startupAttempt = 0L
    val deviceBridge = DeviceBridgeManager(context.applicationContext, scope)
     
    val githubAccount = GitHubAccountManager(context.applicationContext)

    init {
        projects.bindGitHubCredentialResolver(githubAccount::currentGitTransportCredential)
    }
     
    val providerConnections = ProviderConnectionBackend(context.applicationContext)
    val localLlamaServer = LocalLlamaServerManager(
        context = context.applicationContext,
        bridge = deviceBridge,
        providerBackend = providerConnections,
    )
    val localLlamaAgentModels = LocalLlamaAgentModelController(
        context = context.applicationContext,
        bridge = deviceBridge,
        providerBackend = providerConnections,
        server = localLlamaServer,
    )
    val runtime: StateFlow<WorkspaceRuntime?> = _runtime
    val startup: StateFlow<WorkspaceStartupStatus> = _startup

    suspend fun reconcileActiveSaf(): String? = transitionMutex.withLock { _runtime.value?.reconcileSaf() }

    suspend fun init() = transitionMutex.withLock {
        // Activity recreation/retry must not construct a second live runtime over the same workspace.
        if (_runtime.value != null) return@withLock
        val attempt = ++startupAttempt

        suspend fun <T> stage(
            phase: WorkspaceStartupPhase,
            timeoutMs: Long,
            projectId: String? = null,
            block: suspend () -> T,
        ): T {
            _startup.value = WorkspaceStartupStatus(attempt, phase, projectId)
            return WorkspaceStartupPolicy.bounded(phase, timeoutMs, projectId, block)
        }

        try {
            LocalExecutionSubstrate.init(context.applicationContext)
            stage(WorkspaceStartupPhase.RUNTIME_REGISTRIES, WorkspaceStartupPolicy.RUNTIME_REGISTRIES_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    DeclarativeExtensionRuntime.initialize(context.applicationContext)
                    AgentPluginRuntime.initialize(context.applicationContext)
                }
            }
            stage(WorkspaceStartupPhase.PROJECT_CATALOG, WorkspaceStartupPolicy.PROJECT_CATALOG_TIMEOUT_MS) {
                projects.init()
            }
            val active = projects.active() ?: error("No active project")
            var recoveredFrom: Throwable? = null
            val prepared = try {
                stage(
                    WorkspaceStartupPhase.ACTIVE_RUNTIME,
                    WorkspaceStartupPolicy.runtimeTimeoutMs(active),
                    active.id,
                ) { buildRuntime(active, refreshSaf = false) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (firstError: Throwable) {
                if (active.id == "default") throw firstError
                recoveredFrom = firstError
                val fallback = projects.projects.value.firstOrNull { it.id == "default" }
                    ?: throw IllegalStateException("Default recovery project is unavailable", firstError)
                try {
                    stage(
                        WorkspaceStartupPhase.FALLBACK_RUNTIME,
                        WorkspaceStartupPolicy.FALLBACK_RUNTIME_TIMEOUT_MS,
                        fallback.id,
                    ) {
                        projects.switch(fallback.id)
                        buildRuntime(fallback, refreshSaf = false)
                    }
                } catch (fallbackError: Throwable) {
                    fallbackError.addSuppressed(firstError)
                    throw fallbackError
                }
            }
            stage(
                WorkspaceStartupPhase.PUBLISH_RUNTIME,
                WorkspaceStartupPolicy.PUBLISH_RUNTIME_TIMEOUT_MS,
                prepared.project.id,
            ) { swap(prepared) }
            _startup.value = WorkspaceStartupStatus(
                attempt = attempt,
                phase = WorkspaceStartupPhase.READY,
                projectId = prepared.project.id,
                detail = recoveredFrom?.let { "Recovered from ${it::class.java.simpleName}: ${it.message.orEmpty().take(240)}" },
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            _startup.value = WorkspaceStartupStatus(
                attempt = attempt,
                phase = WorkspaceStartupPhase.FAILED,
                projectId = _startup.value.projectId,
                detail = failure.message?.take(500) ?: "Workspace initialization failed",
            )
            throw failure
        }
    }

    suspend fun switchProject(id: String) = transitionMutex.withLock {
        val project = projects.projects.value.firstOrNull { it.id == id } ?: error("Project not found: $id")
        val current = _runtime.value
        if (current?.project?.id == id) return@withLock
        
        current?.reconcileSaf()
        val prepared = buildRuntime(project, refreshSaf = true)
        try {
            projects.switch(id)
        } catch (t: Throwable) {
            prepared.shutdownGracefully()
            throw t
        }
        swap(prepared)
    }

    fun switchProjectAsync(id: String) { scope.launch { switchProject(id) } }

    suspend fun addSafProject(
        uri: Uri,
        name: String = "SAF project",
        onProgress: ((SafMirrorProgress) -> Unit)? = null,
    ): DroideProject = transitionMutex.withLock {
        _runtime.value?.reconcileSaf()
        val project = projects.add(name = name, treeUri = uri.toString())
        val prepared = try {
            buildRuntime(project, refreshSaf = true, safProgress = onProgress)
        } catch (t: Throwable) {
            withContext(NonCancellable) { runSuspendCatching { projects.remove(project.id, deleteWorkspace = true) } }
            throw t
        }
        try {
            projects.switch(project.id)
        } catch (t: Throwable) {
            prepared.shutdownGracefully()
            withContext(NonCancellable) { runSuspendCatching { projects.remove(project.id, deleteWorkspace = true) } }
            throw t
        }
        swap(prepared)
        project
    }

    // A template is never left registered if its runtime cannot be constructed, and old-runtime cleanup cannot invalidate a successfully committed new project.




    suspend fun createProjectFromTemplateAndSwitch(name: String, templateId: String): Pair<DroideProject, String?> =
        transitionMutex.withLock {
            _runtime.value?.reconcileSaf()
            val created = projects.createFromTemplate(name, templateId)
            val project = created.first
            val prepared = try {
                buildRuntime(project, refreshSaf = true)
            } catch (t: Throwable) {
                withContext(NonCancellable) { runSuspendCatching { projects.remove(project.id, deleteWorkspace = true) } }
                throw t
            }
            try {
                projects.switch(project.id)
            } catch (t: Throwable) {
                prepared.shutdownGracefully()
                withContext(NonCancellable) { runSuspendCatching { projects.remove(project.id, deleteWorkspace = true) } }
                throw t
            }
            swap(prepared)
            created
        }

    private suspend fun buildRuntime(
        project: DroideProject,
        refreshSaf: Boolean,
        safProgress: ((SafMirrorProgress) -> Unit)? = null,
    ): WorkspaceRuntime = withContext(Dispatchers.IO) {
        val root = File(project.rootPath)
        check(root.isDirectory || root.mkdirs()) { "Cannot create workspace: ${root.path}" }
        val mirror = project.treeUri?.let { SafMirror(context, it, root) }
        if (refreshSaf && mirror != null) mirror.refreshFromTree(onProgress = safProgress)
        seedDefaultFiles(project, root)
        ProviderEndpointCatalog.load(root)

        var terminals: TerminalManager? = null
        var capabilities: UniversalCapabilityRegistry? = null
        try {
            val documentAuthority = WorkspaceDocumentAuthorityBridge()
            val files = FileRepository(
                root,
                beforeMutation = documentAuthority::validatePersistedMutation,
                onMutation = { mutation ->
                    mirror?.apply(mutation)
                    documentAuthority.persistedMutation(mutation)
                },
            )
            val capabilityRegistry = UniversalCapabilityRegistry(project.id, root)
            capabilities = capabilityRegistry
            val androidDevelopment = AndroidDevelopmentManager(context.applicationContext, root, deviceBridge)
            val extensions = DevelopmentExtensionsManager(context.applicationContext, root, androidDevelopment, deviceBridge, capabilityRegistry)
            terminals = TerminalManager(
                scope = scope,
                defaultDir = root,
                context = context.applicationContext,
                deviceBridge = deviceBridge,
                androidDevelopment = androidDevelopment,
                projectKey = project.id,
            )
            terminals.revivePersistedLocalSessions()
            if (terminals.tabs.value.none { it.profile == TerminalManager.Profile.LOCAL }) {
                terminals.createLocalOnMain("Local", root)
            }
            val syncLinkedFolder: (suspend () -> String?)? = if (mirror == null) null else ({
                check(documentAuthority.dirtySnapshots().isEmpty()) { "Save or discard unsaved editor changes before linked folder sync" }
                mirror.refreshFromTree()
            })
            val git = GitManager(
                root,
                documentAuthority,
                githubCredentialResolver = githubAccount::currentGitTransportCredential,
                syncLinkedFolder = syncLinkedFolder,
            )
            val terminal = terminals.activeSession() ?: error("Terminal failed")
            val automatedExecutionTerminal = ExecutionTerminalRouter(terminals, terminal)
            val remoteProcessHost: StdioProcessHost = LocalLinuxProcessHost(root, scope)
            val managedLanguageServers = ManagedLanguageServerProvisioner(
                context = context.applicationContext,
                scope = scope,
                workDir = root,
                bridge = deviceBridge,
                development = androidDevelopment,
                registry = extensions.registry,
            )
            val professionalLsp = ProfessionalLspManager(
                scope = scope,
                files = files,
                

                fallback = CliLspManager(automatedExecutionTerminal, files),
                remoteProcessHost = remoteProcessHost,
                extraLanguageServers = capabilityRegistry::languageServers,
            )
            val lsp: LspManager = ProvisioningLspManager(
                delegate = professionalLsp,
                ensureLanguageServer = { serverId -> managedLanguageServers.ensureAvailable(serverId) },
            )
            val debugger = DebugManager(
                scope = scope,
                files = files,
                terminalManager = terminals,
                stateStore = DebugStateStore(context.applicationContext, project.id),
                remoteProcessHost = remoteProcessHost,
                deviceBridge = deviceBridge,
                extraDebugAdapters = capabilityRegistry::debugAdapters,
            )
            debugger.restoreState()
            val managedPackageCandidates = ManagedPackageCandidateManager(
                context = context.applicationContext,
                installer = ManagedPackageInstaller(
                    context = context.applicationContext,
                    bridge = deviceBridge,
                    registry = extensions.registry,
                ),
            )
            val managedLanguageServerCertification = ManagedLanguageServerCertificationRunner(
                scope = scope,
                workDir = root,
                bridge = deviceBridge,
                development = androidDevelopment,
                registry = extensions.registry,
            )
            val managedLanguageServerCertificationManager = ManagedLanguageServerCertificationManager(
                context = context.applicationContext,
                projectId = project.id,
                runner = managedLanguageServerCertification,
            )
            val managedDebugAdapters = ManagedDebugAdapterProvisioner(
                context = context.applicationContext,
                bridge = deviceBridge,
                development = androidDevelopment,
                registry = extensions.registry,
            )
            val debugCertification = DebugCertificationManager(
                context = context.applicationContext,
                projectId = project.id,
                androidDevelopment = androidDevelopment,
                debugger = debugger,
                bridge = deviceBridge,
            )
            val physicalDeviceCertification = PhysicalDeviceCertificationManager(
                context = context.applicationContext,
                projectId = project.id,
                projectRoot = root,
                development = androidDevelopment,
                debugger = debugger,
                bridge = deviceBridge,
            )
            val androidInspection = AndroidInspectionManager(deviceBridge)
            val androidStability = AndroidStabilityRunner(androidInspection, AndroidDeviceIdentityCollector(deviceBridge))
            val pluginRuntime = ManagedPluginRuntime(
                scope = scope,
                workDir = root,
                files = files,
                processHost = remoteProcessHost,
            )
            val extensionPlatform = ManagedExtensionPlatform(
                contributions = ExtensionContributionRegistry(
                    ExtensionLifecycleStore(context.filesDir, project.id),
                ),
                pluginRuntime = pluginRuntime,
                packages = extensions.registry,
                capabilities = capabilityRegistry,
            )
            DeclarativeExtensionRuntime.manifests().forEach(extensionPlatform::registerManifest)
            extensions.marketplace.bindExtensionPlatform(extensionPlatform)
            val execution = BuildRunDebugCoordinator(root, terminals, androidDevelopment, debugger, capabilityRegistry)
            val universalToolDiscoveryCertification = UniversalToolDiscoveryCertificationManager(
                context = context.applicationContext,
                projectId = project.id,
                development = androidDevelopment,
                bridge = deviceBridge,
                capabilities = capabilityRegistry,
                execution = execution,
            )
            val agentCapabilities = AgentCapabilityActions(capabilityRegistry, execution)
            val agentTerminal = automatedExecutionTerminal
            val permissionEngine = PermissionEngine(policy = permissionPolicies.load(project.id))
            val agentBrowser = AgentBrowserController(root)
            val agentToolchains = AgentToolchainActions(extensions)
            val agent = AgentService(
                files, agentTerminal, git, approvals, lsp = lsp, perms = permissionEngine,
                agentPluginSource = AgentPluginRuntime, documentAuthority = documentAuthority,
                ideActions = AgentIdeActions(execution),
                toolchainActions = agentToolchains,
                capabilityActions = agentCapabilities,
                browserController = agentBrowser,
                mcpProcessHost = remoteProcessHost,
            )
            val agentPlugins = WorkspaceAgentPluginHost(
                projectRoot = root,
                cacheDir = context.cacheDir,
                files = files,
                
                processHost = DeviceWorkstationProcessHost(deviceBridge, androidDevelopment, root, scope),
                androidDevelopment = androidDevelopment,
                bridge = deviceBridge,
                approvals = approvals,
                permissions = permissionEngine,
                parentScope = scope,
            )
            WorkspaceRuntime(
                project = project,
                files = files,
                documentAuthority = documentAuthority,
                terminals = terminals,
                git = git,
                lsp = lsp,
                debugger = debugger,
                agent = agent,
                agentBrowser = agentBrowser,
                agentPlugins = agentPlugins,
                androidDevelopment = androidDevelopment,
                extensions = extensions,
                capabilities = capabilityRegistry,
                managedPackageCandidates = managedPackageCandidates,
                managedLanguageServers = managedLanguageServers,
                managedLanguageServerCertification = managedLanguageServerCertification,
                managedLanguageServerCertificationManager = managedLanguageServerCertificationManager,
                managedDebugAdapters = managedDebugAdapters,
                debugCertification = debugCertification,
                physicalDeviceCertification = physicalDeviceCertification,
                universalToolDiscoveryCertification = universalToolDiscoveryCertification,
                androidInspection = androidInspection,
                androidStability = androidStability,
                pluginRuntime = pluginRuntime,
                extensionPlatform = extensionPlatform,
                execution = execution,
                safMirror = mirror,
            )
        } catch (t: Throwable) {
            terminals?.let { manager ->
                // TerminalSession/Handler/native PTY ownership is main-thread-bound even when the surrounding runtime construction happens on Dispatchers.IO.

                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    try { manager.dispose(preserveLocalSessions = false) } catch (_: Exception) {}
                }
            }
            try { capabilities?.close() } catch (_: Exception) {}
            throw t
        }
    }

    private suspend fun swap(next: WorkspaceRuntime) {
        val previous = _runtime.value
        _runtime.value = next
        if (previous != null) {
            runSuspendCatching { previous.shutdownGracefully() }
                .onFailure { Log.w("DroideWorkspace", "Previous workspace cleanup failed after committed swap", it) }
        }
    }

    private fun seedDefaultFiles(project: DroideProject, root: File) {
        if (project.id != "default") return
        PathSecurity.resolveWithin(root, "README.md").apply { if (!exists()) writeText("# Default Project\n\nWelcome to Droide.\n") }
        PathSecurity.resolveWithin(root, "hello.py").apply { if (!exists()) writeText("print('Hello from Droide')\n") }
    }


    // Install a data-only VSIX into the integrity-checked app-private extension store.
    suspend fun installDeclarativeVsix(uri: Uri): InstalledDeclarativeExtensionRecord = transitionMutex.withLock {
        withContext(Dispatchers.IO) { DeclarativeExtensionRuntime.installFromUri(uri) }.also { record ->
            _runtime.value?.extensionPlatform?.registerManifest(record.manifest)
        }
    }

     
    suspend fun uninstallDeclarativeExtension(extensionId: String) = transitionMutex.withLock {
        _runtime.value?.extensionPlatform?.unregister(extensionId)
        withContext(Dispatchers.IO) { DeclarativeExtensionRuntime.uninstall(extensionId) }
    }

     
    suspend fun installAgentPlugin(uri: Uri): InstalledAgentPluginRecord = transitionMutex.withLock {
        val record = withContext(Dispatchers.IO) { AgentPluginRuntime.installFromUri(uri) }
        _runtime.value?.let { AgentPluginRuntime.cleanupWorkspace(File(it.project.rootPath)) }
        record
    }

    suspend fun uninstallAgentPlugin(id: String) = transitionMutex.withLock {
        withContext(Dispatchers.IO) { AgentPluginRuntime.uninstall(id) }
        _runtime.value?.let { AgentPluginRuntime.cleanupWorkspace(File(it.project.rootPath)) }
    }

    suspend fun setAgentPluginEnabled(id: String, enabled: Boolean) = transitionMutex.withLock {
        withContext(Dispatchers.IO) { AgentPluginRuntime.setEnabled(id, enabled) }
        _runtime.value?.let { AgentPluginRuntime.cleanupWorkspace(File(it.project.rootPath)) }
    }

    fun installedAgentPlugins(): List<InstalledAgentPluginRecord> = AgentPluginRuntime.records()

    // Persist and activate a trusted, app-private policy for the current project.
    suspend fun replaceActivePermissionPolicy(policy: PermissionPolicyDocument) = transitionMutex.withLock {
        val active = _runtime.value ?: error("No active workspace")
        val validated = policy.validated()
        permissionPolicies.save(active.project.id, validated)
        active.agent.perms.replacePolicy(validated)
    }

    fun activePermissionPolicy(): PermissionPolicyDocument =
        _runtime.value?.agent?.perms?.policySnapshot() ?: PermissionPolicyDocument.EMPTY

    fun approvals(): ApprovalManager = approvals
    fun close(preserveLocalTerminals: Boolean = false) {
        _runtime.value?.close(preserveLocalTerminals = preserveLocalTerminals)
        _runtime.value = null
        deviceBridge.close()
    }
}

package com.baystudio.droide.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import java.io.File
import java.util.concurrent.ConcurrentHashMap

 
class TerminalManager(
    private val scope: CoroutineScope,
    private val defaultDir: File,
    private val context: Context? = null,
    private val deviceBridge: DeviceBridgeManager? = null,
    private val androidDevelopment: AndroidDevelopmentManager? = null,
    private val projectKey: String = defaultDir.absolutePath,
) {
    enum class Profile { LOCAL, LOCAL_LINUX_ARM64, LOCAL_QEMU, DEVICE_ADB }

    data class Tab(
        val id: String,
        val session: ITerminalSession,
        val title: String,
        val profile: Profile,
    )

    private val persistentLocalHost = context?.let(PersistentLocalTerminalHost::get)
    private val restoredLocalTabs = persistentLocalHost?.snapshot(projectKey).orEmpty().map { hosted ->
        Tab(hosted.id, hosted.session, hosted.title, Profile.LOCAL)
    }
    private val _tabs = MutableStateFlow<List<Tab>>(restoredLocalTabs)
    val tabs: StateFlow<List<Tab>> = _tabs
    val canCreateTab: Boolean get() = _tabs.value.size < MAX_TABS
    private val _active = MutableStateFlow<String?>(
        persistentLocalHost?.activeId(projectKey)?.takeIf { id -> restoredLocalTabs.any { it.id == id } }
            ?: restoredLocalTabs.firstOrNull()?.id
    )
    val active: StateFlow<String?> = _active
    private val titleObservationJobs = ConcurrentHashMap<String, Job>()
    private val linuxCreationMutex = Mutex()
    @Volatile private var linuxUnavailableReason: String = "Local Linux PC Environment is not ready"

    fun automatedExecutionUnavailableReason(): String = linuxUnavailableReason

    init {
        restoredLocalTabs.forEach { tab -> observeDynamicTitle(tab.id, tab.title, tab.session) }
    }

    fun create(title: String = "Local", workDir: File = defaultDir): Tab = createLocal(title, workDir)

    @Synchronized
    fun createLocal(title: String = "Local", workDir: File = defaultDir, activate: Boolean = true, launchSpec: TerminalLaunchSpec = TerminalLaunchSpec(), profile: Profile = Profile.LOCAL): Tab {
        require(_tabs.value.size < MAX_TABS) { "Too many terminal tabs (max $MAX_TABS)" }
        require(profile != Profile.DEVICE_ADB) { "Device sessions require the device backend" }
        require(profile != Profile.LOCAL || launchSpec.canReviveAsAndroidShell) {
            "Custom launchers require a distinct backend profile"
        }
        require(profile != Profile.LOCAL_LINUX_ARM64 || launchSpec.prefix.isNotEmpty()) {
            "Linux execution requires an explicit backend launcher"
        }
        

        val nativeReady = context != null && NativePtyCompatibility.prepare()
        
        val hosted = if (nativeReady && profile == Profile.LOCAL) persistentLocalHost?.create(projectKey, defaultDir, title, workDir, activate) else null
        val id = hosted?.id ?: "t${System.nanoTime()}"
        val session: ITerminalSession = hosted?.session ?: if (nativeReady) {
            NativePtyTerminalSession(requireNotNull(context), workDir, scope, launchSpec).also { it.start() }
        } else TerminalSession(workDir, scope, launchSpec).also { it.start() }
        val tab = Tab(id, session, title, profile)
        _tabs.value = _tabs.value + tab
        observeDynamicTitle(id, title, session)
        if (activate) _active.value = id
        return tab
    }


    suspend fun createLocalOnMain(
        title: String = "Local",
        workDir: File = defaultDir,
        activate: Boolean = true,
        launchSpec: TerminalLaunchSpec = TerminalLaunchSpec(),
        profile: Profile = Profile.LOCAL
    ): Tab = if (context == null) {
        createLocal(title, workDir, activate, launchSpec, profile)
    } else {
        withContext(Dispatchers.Main.immediate) { createLocal(title, workDir, activate, launchSpec, profile) }
    }

    @Synchronized
    fun createDeviceWorkstation(title: String = "Workstation", activate: Boolean = true): Tab {
        require(_tabs.value.size < MAX_TABS) { "Too many terminal tabs (max $MAX_TABS)" }
        val bridge = requireNotNull(deviceBridge) { "Device Bridge is not available" }
        val development = requireNotNull(androidDevelopment) { "Android Development environment is not available" }
        check(bridge.state.value.supported) { "Device Workstation requires Android 11 or newer; local editor, Git and terminal remain available on Android 10" }
        check(bridge.state.value.connected != null) { "Connect Device Workstation first" }
        val id = "d${System.nanoTime()}"
        val session = DeviceWorkstationTerminalSession(bridge, development, scope)
        session.start()
        val tab = Tab(id, session, title, Profile.DEVICE_ADB)
        _tabs.value = _tabs.value + tab
        observeDynamicTitle(id, title, session)
        if (activate) _active.value = id
        return tab
    }


    suspend fun localExecutionSession(createIfMissing: Boolean = true): ITerminalSession? {
        _tabs.value.firstOrNull { it.profile == Profile.LOCAL }?.session?.let { return it }
        return if (createIfMissing) createLocalOnMain(title = "Local", workDir = defaultDir, activate = false).session else null
    }

    suspend fun linuxExecutionSession(createIfMissing: Boolean = true): ITerminalSession? = linuxCreationMutex.withLock {
        _tabs.value.firstOrNull { it.profile == Profile.LOCAL_LINUX_ARM64 }?.session?.let { return@withLock it }
        if (!createIfMissing) return@withLock null
        try {
            val launchSpec = LocalExecutionSubstrate.ensureLinuxReady(defaultDir)
            createLocalOnMain(
                title = "Linux (Local)", workDir = defaultDir, activate = false,
                launchSpec = launchSpec, profile = Profile.LOCAL_LINUX_ARM64,
            ).session
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            linuxUnavailableReason = error.message ?: "Local Linux launcher is unavailable"
            null
        }
    }

    suspend fun openLinuxTerminal(qemu: Boolean = false): Tab = linuxCreationMutex.withLock {
        val spec = if (qemu) LocalExecutionSubstrate.ensureQemuReady(defaultDir) else LocalExecutionSubstrate.ensureLinuxReady(defaultDir)
        createLocalOnMain(title = if (qemu) "QEMU (Alpine)" else "Linux (Ubuntu)", workDir = defaultDir,
            launchSpec = spec, profile = if (qemu) Profile.LOCAL_QEMU else Profile.LOCAL_LINUX_ARM64)
    }

     
    suspend fun createLinuxTerminal(title: String = "Linux (Ubuntu)", activate: Boolean = true): Tab =
        linuxCreationMutex.withLock {
            val spec = LocalExecutionSubstrate.ensureLinuxReady(defaultDir)
            createLocalOnMain(
                title = title,
                workDir = defaultDir,
                activate = activate,
                launchSpec = spec,
                profile = Profile.LOCAL_LINUX_ARM64,
            )
        }

    fun workstationExecutionSession(createIfMissing: Boolean = true): ITerminalSession? {
        if (deviceBridge?.state?.value?.connected == null || androidDevelopment == null) return null
        _tabs.value.firstOrNull { it.profile == Profile.DEVICE_ADB }?.session?.let { return it }
        return if (createIfMissing) createDeviceWorkstation(title = "Workstation", activate = false).session else null
    }


    suspend fun automatedExecutionSession(createIfMissing: Boolean = true): ITerminalSession? =
        linuxExecutionSession(createIfMissing)

    // Shell path must be derived from the actual backend tab, never from caller intent.
    fun commandShellFor(session: ITerminalSession): String = when (
        _tabs.value.firstOrNull { it.session === session }?.profile
            ?: error("Terminal session is not owned by this workspace")
    ) {
        Profile.LOCAL -> "/system/bin/sh"
        Profile.LOCAL_LINUX_ARM64, Profile.LOCAL_QEMU -> "/bin/sh"
        Profile.DEVICE_ADB -> "/system/bin/sh"
    }

    fun existingAutomatedExecutionSession(): ITerminalSession? =
        _tabs.value.firstOrNull { it.profile == Profile.LOCAL_LINUX_ARM64 }?.session

    fun isDeviceWorkstationConnected(): Boolean =
        deviceBridge?.state?.value?.connected != null && androidDevelopment != null

    suspend fun executionSession(preferWorkstation: Boolean = true): ITerminalSession? =
        if (preferWorkstation) workstationExecutionSession(createIfMissing = true) ?: localExecutionSession(createIfMissing = true)
        else localExecutionSession(createIfMissing = true)

     
    fun existingExecutionSession(preferWorkstation: Boolean = true): ITerminalSession? {
        val workstation = if (deviceBridge?.state?.value?.connected != null) {
            _tabs.value.firstOrNull { it.profile == Profile.DEVICE_ADB }?.session
        } else null
        val local = _tabs.value.firstOrNull { it.profile == Profile.LOCAL }?.session
        return if (preferWorkstation) workstation ?: local else local ?: workstation
    }


    suspend fun revivePersistedLocalSessions(): Int {
        val host = persistentLocalHost ?: return 0
        val nativeReady = if (context == null) false else withContext(Dispatchers.Main.immediate) { NativePtyCompatibility.prepare() }
        if (!nativeReady) return 0
        val revived = host.reviveProject(projectKey, defaultDir)
        if (revived.isEmpty()) return 0
        val currentIds = _tabs.value.mapTo(mutableSetOf()) { it.id }
        val additions = revived.filterNot { it.id in currentIds }.map { hosted ->
            Tab(hosted.id, hosted.session, hosted.title, Profile.LOCAL)
        }
        if (additions.isNotEmpty()) _tabs.value = _tabs.value + additions
        additions.forEach { tab -> observeDynamicTitle(tab.id, tab.title, tab.session) }
        val wantedActive = host.activeId(projectKey)
        if (wantedActive != null && _tabs.value.any { it.id == wantedActive }) _active.value = wantedActive
        else if (_active.value == null) _active.value = _tabs.value.firstOrNull()?.id
        return additions.size
    }

    suspend fun flushReviveState() {
        persistentLocalHost?.flush(projectKey, defaultDir)
    }

    fun activeSession(): ITerminalSession? = _tabs.value.firstOrNull { it.id == _active.value }?.session
    fun activeTab(): Tab? = _tabs.value.firstOrNull { it.id == _active.value }
    fun switch(id: String) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        _active.value = id
        if (tab.profile == Profile.LOCAL) persistentLocalHost?.markActive(projectKey, defaultDir, id)
    }

    @Synchronized
    fun close(id: String) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        titleObservationJobs.remove(id)?.cancel()
        if (tab.profile == Profile.LOCAL && persistentLocalHost != null) {
            persistentLocalHost.remove(projectKey, defaultDir, id)
        } else {
            tab.session.destroy()
        }
        _tabs.value = _tabs.value.filterNot { it.id == id }
        if (_active.value == id) {
            _active.value = _tabs.value.firstOrNull()?.id
            _tabs.value.firstOrNull { it.id == _active.value && it.profile == Profile.LOCAL }
                ?.let { persistentLocalHost?.markActive(projectKey, defaultDir, it.id) }
        }
    }


    @Synchronized
    fun dispose(preserveLocalSessions: Boolean) {
        titleObservationJobs.values.forEach { it.cancel() }
        titleObservationJobs.clear()
        val current = _tabs.value
        current.forEach { tab ->
            when {
                tab.profile == Profile.LOCAL && preserveLocalSessions && persistentLocalHost != null -> Unit
                tab.profile == Profile.LOCAL && persistentLocalHost != null -> persistentLocalHost.remove(projectKey, defaultDir, tab.id)
                else -> tab.session.destroy()
            }
        }
        _tabs.value = emptyList()
        _active.value = null
    }

    private fun observeDynamicTitle(id: String, fallback: String, session: ITerminalSession) {
        val metadata = session as? TerminalMetadataSession ?: return
        titleObservationJobs.remove(id)?.cancel()
        val job = scope.launch {
            metadata.terminalTitle.collect { reported ->
                val display = TerminalTitlePolicy.display(reported)
                    ?: TerminalTitlePolicy.display(fallback)
                    ?: "Terminal"
                val current = _tabs.value
                val index = current.indexOfFirst { it.id == id }
                if (index < 0 || current[index].title == display) return@collect
                _tabs.value = current.toMutableList().apply {
                    this[index] = current[index].copy(title = display)
                }
            }
        }
        titleObservationJobs[id] = job
        job.invokeOnCompletion { titleObservationJobs.remove(id, job) }
    }

    private companion object {
        const val MAX_TABS = 16
    }

}

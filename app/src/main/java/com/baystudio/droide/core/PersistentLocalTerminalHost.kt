package com.baystudio.droide.core

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File













class PersistentLocalTerminalHost private constructor(
    private val appContext: Context,
) {
    data class HostedTab(
        val id: String,
        val title: String,
        val workDir: File,
        val session: NativePtySessionHandle,
    )

    // Cancelling a WorkspaceViewModel therefore cannot silently break execOnce/execArgv on a retained PTY.

    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceMutex = Mutex()
    private val reviveStore = TerminalReviveStore(appContext.filesDir)
    private val hydratedProjects = mutableSetOf<String>()
    private val reviveAllowedForProcess = TerminalProcessExitPolicy.allowRevive(appContext).also { allowed ->
        if (!allowed) reviveStore.clearAll()
    }
    private val tabsByProject = linkedMapOf<String, LinkedHashMap<String, HostedTab>>()
    private val activeLocalByProject = mutableMapOf<String, String>()

    @Synchronized
    fun snapshot(projectKey: String): List<HostedTab> =
        tabsByProject[projectKey]?.values?.toList().orEmpty()

    @Synchronized
    fun activeId(projectKey: String): String? =
        activeLocalByProject[projectKey]?.takeIf { id -> tabsByProject[projectKey]?.containsKey(id) == true }

    



    suspend fun reviveProject(projectKey: String, projectRoot: File): List<HostedTab> {
        synchronized(this) {
            tabsByProject[projectKey]?.values?.toList()?.takeIf { it.isNotEmpty() }?.let { return it }
            if (projectKey in hydratedProjects) return emptyList()
        }
        if (!reviveAllowedForProcess) {
            synchronized(this) { hydratedProjects += projectKey }
            return emptyList()
        }
        val snapshot = withContext(Dispatchers.IO) { reviveStore.load(projectKey, projectRoot) }
        if (snapshot == null) {
            synchronized(this) { hydratedProjects += projectKey }
            return emptyList()
        }
        return withContext(Dispatchers.Main.immediate) {
            NativePtyThreadPolicy.requireMainThread(
                isMainThread = Looper.getMainLooper().thread === Thread.currentThread(),
            )
            synchronized(this@PersistentLocalTerminalHost) {
                tabsByProject[projectKey]?.values?.toList()?.takeIf { it.isNotEmpty() }?.let { existing ->
                    hydratedProjects += projectKey
                    return@withContext existing
                }
            }
            val created = mutableListOf<HostedTab>()
            try {
                snapshot.tabs.take(MAX_LOCAL_TABS_PER_PROJECT).forEach { descriptor ->
                    val hosted = HostedTab(
                        id = descriptor.id,
                        title = descriptor.title,
                        workDir = descriptor.workDir,
                        session = NativePtyTerminalSession(appContext, descriptor.workDir, processScope),
                    )
                    hosted.session.start()
                    created += hosted
                }
                synchronized(this@PersistentLocalTerminalHost) {
                    val projectTabs = linkedMapOf<String, HostedTab>()
                    created.forEach { projectTabs[it.id] = it }
                    if (projectTabs.isNotEmpty()) tabsByProject[projectKey] = projectTabs
                    val active = snapshot.activeId?.takeIf(projectTabs::containsKey) ?: projectTabs.keys.firstOrNull()
                    if (active != null) activeLocalByProject[projectKey] = active
                    hydratedProjects += projectKey
                    projectTabs.values.toList()
                }
            } catch (t: Throwable) {
                created.forEach { runCatching { it.session.destroy() } }
                withContext(Dispatchers.IO) { reviveStore.delete(projectKey) }
                synchronized(this@PersistentLocalTerminalHost) { hydratedProjects += projectKey }
                throw t
            }
        }
    }

    
    fun create(projectKey: String, projectRoot: File, title: String, workDir: File, activate: Boolean): HostedTab {
        NativePtyThreadPolicy.requireMainThread(
            isMainThread = Looper.getMainLooper().thread === Thread.currentThread(),
        )
        synchronized(this) {
            check((tabsByProject[projectKey]?.size ?: 0) < MAX_LOCAL_TABS_PER_PROJECT) {
                "Too many retained local terminal tabs (max $MAX_LOCAL_TABS_PER_PROJECT)"
            }
        }
        val hosted = HostedTab(
            id = "t${System.nanoTime()}",
            title = title,
            workDir = workDir,
            session = NativePtyTerminalSession(appContext, workDir, processScope),
        )
        hosted.session.start()
        synchronized(this) {
            val projectTabs = tabsByProject.getOrPut(projectKey) { linkedMapOf() }
            

            if (projectTabs.size >= MAX_LOCAL_TABS_PER_PROJECT) {
                hosted.session.destroy()
                error("Too many retained local terminal tabs (max $MAX_LOCAL_TABS_PER_PROJECT)")
            }
            projectTabs[hosted.id] = hosted
            if (activate || activeLocalByProject[projectKey] == null) {
                activeLocalByProject[projectKey] = hosted.id
            }
            hydratedProjects += projectKey
        }
        schedulePersist(projectKey, projectRoot)
        return hosted
    }

    fun markActive(projectKey: String, projectRoot: File, id: String) {
        val changed = synchronized(this) {
            if (tabsByProject[projectKey]?.containsKey(id) == true) {
                activeLocalByProject[projectKey] = id
                true
            } else false
        }
        if (changed) schedulePersist(projectKey, projectRoot)
    }

    fun remove(projectKey: String, projectRoot: File, id: String): Boolean {
        val removed = synchronized(this) {
            val projectTabs = tabsByProject[projectKey] ?: return@synchronized null
            val tab = projectTabs.remove(id) ?: return@synchronized null
            if (activeLocalByProject[projectKey] == id) {
                projectTabs.keys.firstOrNull()?.let { activeLocalByProject[projectKey] = it }
                    ?: activeLocalByProject.remove(projectKey)
            }
            if (projectTabs.isEmpty()) tabsByProject.remove(projectKey)
            tab
        } ?: return false
        removed.session.destroy()
        schedulePersist(projectKey, projectRoot)
        return true
    }

    fun destroyProject(projectKey: String, projectRoot: File) {
        val removed = synchronized(this) {
            activeLocalByProject.remove(projectKey)
            tabsByProject.remove(projectKey)?.values?.toList().orEmpty()
        }
        removed.forEach { it.session.destroy() }
        schedulePersist(projectKey, projectRoot)
    }

     
    suspend fun flush(projectKey: String, projectRoot: File) {
        persistenceMutex.withLock { persistLatest(projectKey, projectRoot) }
    }

    private fun schedulePersist(projectKey: String, projectRoot: File) {
        persistenceScope.launch {
            runCatching { persistenceMutex.withLock { persistLatest(projectKey, projectRoot) } }
        }
    }

    private fun persistLatest(projectKey: String, projectRoot: File) {
        val state = synchronized(this) {
            val tabs = tabsByProject[projectKey]?.values.orEmpty().map { hosted ->
                TerminalReviveDescriptor(hosted.id, hosted.title, hosted.workDir)
            }
            tabs to activeLocalByProject[projectKey]
        }
        if (state.first.isEmpty()) reviveStore.delete(projectKey)
        else reviveStore.save(projectKey, projectRoot, state.first, state.second)
    }

    companion object {
        private const val MAX_LOCAL_TABS_PER_PROJECT = 16

        @Volatile
        private var instance: PersistentLocalTerminalHost? = null

        fun get(context: Context): PersistentLocalTerminalHost =
            instance ?: synchronized(this) {
                instance ?: PersistentLocalTerminalHost(context.applicationContext).also { instance = it }
            }
    }
}

package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull

 
data class DroideProject(
    val id: String,
    val name: String,
    val rootPath: String,
    val treeUri: String? = null,
)

interface ProjectStore {
    suspend fun load(): List<DroideProject>
    suspend fun save(all: List<DroideProject>)
    suspend fun loadActiveId(): String?
    suspend fun saveActiveId(id: String)
}

class FileProjectStore(private val dir: File) : ProjectStore {
    private val f = File(dir, ".droide/projects.json")
    private val active = File(dir, ".droide/active_project.txt")
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override suspend fun load(): List<DroideProject> = withContext(Dispatchers.IO) {
        if (!f.isFile || f.length() > 2_000_000) return@withContext emptyList()
        runCatching {
            val arr = json.parseToJsonElement(f.readText()) as? kotlinx.serialization.json.JsonArray ?: return@runCatching emptyList()
            arr.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
                val name = (o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "Project"
                val tree = (o["treeUri"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                val storedRoot = (o["rootPath"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                val safeId = runCatching { PathSecurity.safeLeafName(id) }.getOrNull() ?: return@mapNotNull null
                val projectsRoot = ProjectWorkspacePolicy.projectsRoot(dir)
                val root = ProjectWorkspacePolicy.restoreRoot(projectsRoot, safeId, storedRoot).canonicalPath
                // Older builds persisted a trusted flag.


                DroideProject(safeId, name.take(120), root, tree)
            }.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    override suspend fun save(all: List<DroideProject>) = withContext(Dispatchers.IO) {
        val arr = kotlinx.serialization.json.JsonArray(all.map { p ->
            kotlinx.serialization.json.buildJsonObject {
                put("id", kotlinx.serialization.json.JsonPrimitive(p.id))
                put("name", kotlinx.serialization.json.JsonPrimitive(p.name))
                put("rootPath", kotlinx.serialization.json.JsonPrimitive(p.rootPath))
                p.treeUri?.let { put("treeUri", kotlinx.serialization.json.JsonPrimitive(it)) }
            }
        })
        atomicText(f, arr.toString())
    }

    override suspend fun loadActiveId(): String? = withContext(Dispatchers.IO) {
        active.takeIf { it.isFile && it.length() <= 512 }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }

    override suspend fun saveActiveId(id: String) = withContext(Dispatchers.IO) { atomicText(active, id + "\n") }

    private fun atomicText(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
        try {
            tmp.writeText(text)
            runCatching {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}


interface ProjectRecentsStore {
    // Null means this installation has never persisted recent-project presentation state.
    suspend fun load(): List<String>?
    suspend fun save(ids: List<String>)
}

class FileProjectRecentsStore(private val dir: File) : ProjectRecentsStore {
    private val f = File(dir, ".droide/recent_projects.json")
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override suspend fun load(): List<String>? = withContext(Dispatchers.IO) {
        if (!f.exists()) return@withContext null
        if (!f.isFile || f.length() > 256_000) return@withContext emptyList()
        runCatching {
            val arr = json.parseToJsonElement(f.readText()) as? kotlinx.serialization.json.JsonArray
                ?: return@runCatching emptyList()
            arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                .mapNotNull { runCatching { PathSecurity.safeLeafName(it) }.getOrNull() }
                .distinct()
                .take(128)
        }.getOrDefault(emptyList())
    }

    override suspend fun save(ids: List<String>) = withContext(Dispatchers.IO) {
        val safe = ids.asSequence()
            .mapNotNull { runCatching { PathSecurity.safeLeafName(it) }.getOrNull() }
            .distinct()
            .take(128)
            .map { kotlinx.serialization.json.JsonPrimitive(it) }
            .toList()
        atomicText(f, kotlinx.serialization.json.JsonArray(safe).toString())
    }

    private fun atomicText(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
        try {
            tmp.writeText(text)
            runCatching {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}

class ProjectManager(
    private val appFilesDir: File,
    private val store: ProjectStore = FileProjectStore(appFilesDir),
    private val recentsStore: ProjectRecentsStore = FileProjectRecentsStore(appFilesDir),
) {
    private val _projects = MutableStateFlow<List<DroideProject>>(emptyList())
    val projects: StateFlow<List<DroideProject>> = _projects
    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId
    private val recentMutex = Mutex()
    private val _recentIds = MutableStateFlow<List<String>>(emptyList())
    private val _recentProjects = MutableStateFlow<List<DroideProject>>(emptyList())
    val recentProjects: StateFlow<List<DroideProject>> = _recentProjects
    private val _otherProjects = MutableStateFlow<List<DroideProject>>(emptyList())
    val otherProjects: StateFlow<List<DroideProject>> = _otherProjects

    @Volatile
    private var githubCredentialResolver: (suspend () -> GitHubTransportCredential?)? = null

     
    internal fun bindGitHubCredentialResolver(resolver: suspend () -> GitHubTransportCredential?) {
        githubCredentialResolver = resolver
    }

    private fun defaultProject(): DroideProject =
        DroideProject("default", "Default", File(appFilesDir, "projects/default").absolutePath)

    private var startupCleanup: List<File> = emptyList()

    suspend fun init() = withContext(Dispatchers.IO) {
        val projectsRoot = ProjectWorkspacePolicy.projectsRoot(appFilesDir).apply { mkdirs() }
        val loaded = store.load().mapNotNull { project ->
            val safeId = runCatching { PathSecurity.safeLeafName(project.id) }.getOrNull() ?: return@mapNotNull null
            val root = ProjectWorkspacePolicy.restoreRoot(projectsRoot, safeId, project.rootPath)
            project.copy(id = safeId, rootPath = root.canonicalPath)
        }.distinctBy { it.id }.toMutableList()
        val def = defaultProject()
        if (loaded.none { it.id == def.id }) loaded.add(0, def)
        startupCleanup = ProjectWorkspaceMaintenance.prepare(projectsRoot, loaded.associate { it.id to File(it.rootPath) })
        loaded.forEach { File(it.rootPath).mkdirs() }
        _projects.value = loaded
        store.save(loaded)

        val wanted = store.loadActiveId()
        val chosen = loaded.firstOrNull { it.id == wanted }?.id ?: def.id
        store.saveActiveId(chosen)
        _activeId.value = chosen

        val registeredIds = loaded.map { it.id }
        _recentIds.value = ProjectHistoryPolicy.initial(
            persisted = recentsStore.load(),
            registeredIds = registeredIds,
            activeId = chosen,
        )
        recentsStore.save(_recentIds.value)
        refreshProjectPresentation()
    }

    internal suspend fun collectStartupGarbage(): List<String> {
        val prepared = startupCleanup
        startupCleanup = emptyList()
        return ProjectWorkspaceMaintenance.collect(ProjectWorkspacePolicy.projectsRoot(appFilesDir), prepared)
    }

    suspend fun add(name: String, rootPath: String? = null, treeUri: String? = null): DroideProject {
        val id = "p-${UUID.randomUUID()}"
        val projectsRoot = ProjectWorkspacePolicy.projectsRoot(appFilesDir).apply { mkdirs() }
        val requested = rootPath?.let(::File) ?: ProjectWorkspacePolicy.defaultRoot(projectsRoot, id)
        val root = ProjectWorkspacePolicy.requireDirectChild(projectsRoot, requested)
        require(_projects.value.none { existing -> runCatching { File(existing.rootPath).canonicalFile == root }.getOrDefault(false) }) {
            "Project workspace is already registered"
        }
        check(root.isDirectory || root.mkdirs()) { "Cannot create project workspace" }
        val p = DroideProject(
            id = id,
            name = name.trim().ifBlank { "Project" }.take(120),
            rootPath = root.canonicalPath,
            treeUri = treeUri,
        )
        val updated = _projects.value + p
        store.save(updated)
        _projects.value = updated
        return p
    }

    suspend fun createFromTemplate(name: String, templateId: String): Pair<DroideProject, String?> {
        val template = ProjectTemplateRegistry.byId(templateId) ?: error("Unknown project template: $templateId")
        val project = add(name.ifBlank { template.name })
        try {
            withContext(Dispatchers.IO) { ProjectTemplateWriter.write(File(project.rootPath), template) }
            return project to template.primaryFile
        } catch (error: Throwable) {
            runCatching { remove(project.id, deleteWorkspace = true) }
            throw error
        }
    }

    // Authentication is transport-only and never persisted here.


    suspend fun cloneFromGit(
        name: String,
        url: String,
        username: String? = null,
        token: String? = null,
    ): DroideProject = withContext(Dispatchers.IO) {
        val safeUrl = url.trim()
        require(GitRemoteSecurity.isAllowed(safeUrl)) {
            "Only HTTPS or SSH Git remotes without embedded HTTPS credentials are allowed"
        }
        val id = "p-${UUID.randomUUID()}"
        val projectsRoot = ProjectWorkspacePolicy.projectsRoot(appFilesDir).apply { mkdirs() }
        val root = ProjectWorkspacePolicy.defaultRoot(projectsRoot, id)
        require(!root.exists()) { "Clone workspace collision" }
        check(root.mkdirs()) { "Cannot create clone workspace" }
        try {
            val result = GitManager(root, githubCredentialResolver = githubCredentialResolver)
                .clone(safeUrl, username = username, token = token)
            require(result == "Clone completed") { result }
            val project = DroideProject(
                id = id,
                name = name.trim().ifBlank { GitClonePolicy.suggestProjectName(safeUrl) }.take(120),
                rootPath = root.canonicalPath,
            )
            val updated = _projects.value + project
            store.save(updated)
            _projects.value = updated
            project
        } catch (error: Throwable) {
            runCatching { PathSecurity.deleteTreeNoFollow(root) }
            throw error
        }
    }


    // Persist first, then publish state so observers never see a switch that failed to save.
    suspend fun switch(id: String) {
        require(_projects.value.any { it.id == id }) { "Project not found: $id" }
        // Active-project persistence is authoritative; Recent is presentation-only.


        store.saveActiveId(id)
        _activeId.value = id
        if (id != "default") {
            runCatching { markRecent(id) }.onFailure { refreshProjectPresentation() }
        } else {
            refreshProjectPresentation()
        }
    }


    suspend fun removeFromRecent(id: String) {
        require(id != "default") { "Default project is pinned" }
        require(_activeId.value != id) { "Switch away from a project before removing it from Recent" }
        recentMutex.withLock {
            val next = ProjectHistoryPolicy.remove(_recentIds.value, id)
            recentsStore.save(next)
            _recentIds.value = next
            refreshProjectPresentation()
        }
    }

    suspend fun restoreToRecent(id: String) {
        require(_projects.value.any { it.id == id }) { "Project not found: $id" }
        if (id != "default") markRecent(id)
    }

     
    suspend fun clearRecent() {
        recentMutex.withLock {
            val next = ProjectHistoryPolicy.clear(_activeId.value)
            recentsStore.save(next)
            _recentIds.value = next
            refreshProjectPresentation()
        }
    }

    // For SAF projects this deletes only Droide's app-private mirror; the external user tree is never deleted.


    suspend fun deleteWorkspace(id: String) = withContext(Dispatchers.IO) {
        require(id != "default") { "Default project cannot be deleted" }
        require(_activeId.value != id) { "Switch away from a project before deleting its workspace" }
        val target = _projects.value.firstOrNull { it.id == id } ?: return@withContext
        val projectsRoot = ProjectWorkspacePolicy.projectsRoot(appFilesDir).apply { mkdirs() }
        val root = ProjectWorkspacePolicy.requireDirectChild(projectsRoot, File(target.rootPath))
        val quarantine = File(projectsRoot, ".delete-${PathSecurity.safeLeafName(id)}").canonicalFile
        require(!quarantine.exists()) { "A previous delete transaction still needs recovery" }
        var moved = false
        if (root.exists()) {
            runCatching {
                Files.move(root.toPath(), quarantine.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(root.toPath(), quarantine.toPath())
            }
            moved = true
        }

        val updatedProjects = _projects.value.filterNot { it.id == id }
        val updatedRecent = ProjectHistoryPolicy.remove(_recentIds.value, id)
        try {
            // projects.json is the durable commit point.
            store.save(updatedProjects)
        } catch (failure: Throwable) {
            if (moved && quarantine.exists() && !root.exists()) {
                runCatching {
                    Files.move(quarantine.toPath(), root.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }.getOrElse { Files.move(quarantine.toPath(), root.toPath()) }
            }
            throw failure
        }

        
        runCatching { recentsStore.save(updatedRecent) }
        _projects.value = updatedProjects
        _recentIds.value = updatedRecent
        refreshProjectPresentation()
        if (moved) runCatching { PathSecurity.deleteTreeNoFollow(quarantine) }
    }

    // Presentation-only removal must use the referenced API.


    suspend fun remove(id: String, deleteWorkspace: Boolean) {
        require(deleteWorkspace) { "Use removeFromRecent() for presentation-only removal" }
        require(id != "default") { "Default project cannot be removed" }
        require(_activeId.value != id) { "Switch away from a project before removing it" }
        val target = _projects.value.firstOrNull { it.id == id } ?: return
        val updated = _projects.value.filterNot { it.id == id }
        store.save(updated)
        _projects.value = updated
        recentMutex.withLock {
            val next = ProjectHistoryPolicy.remove(_recentIds.value, id)
            recentsStore.save(next)
            _recentIds.value = next
            refreshProjectPresentation()
        }
        if (deleteWorkspace) {
            val projectsRoot = ProjectWorkspacePolicy.projectsRoot(appFilesDir)
            val root = ProjectWorkspacePolicy.requireDirectChild(projectsRoot, File(target.rootPath))
            runCatching { PathSecurity.deleteTreeNoFollow(root) }.getOrThrow()
        }
    }

    private suspend fun markRecent(id: String) {
        recentMutex.withLock {
            val registered = _projects.value.any { it.id == id }
            if (!registered || id == "default") {
                refreshProjectPresentation()
                return@withLock
            }
            val next = ProjectHistoryPolicy.markRecent(
                current = _recentIds.value,
                id = id,
                registeredIds = _projects.value.map { it.id }.toSet(),
            )
            recentsStore.save(next)
            _recentIds.value = next
            refreshProjectPresentation()
        }
    }

    private fun refreshProjectPresentation() {
        val all = _projects.value
        val byId = all.associateBy { it.id }
        val recent = _recentIds.value.mapNotNull(byId::get)
        val default = byId["default"]
        _recentProjects.value = listOfNotNull(default) + recent.filterNot { it.id == "default" }
        val recentSet = _recentIds.value.toSet()
        _otherProjects.value = all.filter { it.id != "default" && it.id !in recentSet }
    }

    fun active(): DroideProject? = _projects.value.firstOrNull { it.id == _activeId.value }
    fun activeRoot(): File {
        val projectsRoot = ProjectWorkspacePolicy.projectsRoot(appFilesDir)
        val project = active() ?: return ProjectWorkspacePolicy.defaultRoot(projectsRoot, "default")
        return ProjectWorkspacePolicy.requireDirectChild(projectsRoot, File(project.rootPath))
    }

}

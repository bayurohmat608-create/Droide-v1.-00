package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class SubagentTaskState { QUEUED, STARTING, RUNNING, IDLE, ERROR, KILLED, INTERRUPTED }

@Serializable
enum class SubagentDeliveryState { NONE, PENDING, DELIVERED }

@Serializable
enum class SubagentWorkspaceMode { SHARED, ISOLATED }

@Serializable
enum class SubagentIntegrationState { NONE, PENDING, MERGED, CONFLICT, DISCARDED }

@Serializable
data class SubagentTaskRecord(
    val id: String,
    val parentSessionId: String,
    val childSessionId: String,
    val profile: String,
    val profileRevision: String = "",
    val description: String,
    val background: Boolean,
    val state: SubagentTaskState,
    val delivery: SubagentDeliveryState,
    val workspaceMode: SubagentWorkspaceMode = SubagentWorkspaceMode.SHARED,
    val workspaceName: String = "",
    val workspacePath: String = "",
    val workspaceBranch: String = "",
    val workspaceBaseCommit: String = "",
    val workspaceParentBranch: String = "",
    val workspaceParentRepositoryIdentity: String = "",
    val integration: SubagentIntegrationState = SubagentIntegrationState.NONE,
    val attempt: Int = 1,
    val providerId: String = "",
    val modelId: String = "",
    val startedAtMs: Long = 0L,
    val endedAtMs: Long = 0L,
    val lastPrompt: String = "",
    val result: String = "",
    val error: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
)

data class SubagentCompletion(val record: SubagentTaskRecord, val config: AgentConfig, val activeSiblings: Int = 0)

 
data class SubagentTaskView(
    val id: String,
    val childSessionId: String,
    val profile: String,
    val description: String,
    val state: SubagentTaskState,
    val background: Boolean,
    val workspaceMode: SubagentWorkspaceMode,
    val workspaceName: String,
    val integration: SubagentIntegrationState,
    val attempt: Int,
    val providerId: String,
    val modelId: String,
    val createdAtMs: Long,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val updatedAtMs: Long,
    val activity: String = "",
    val activityTool: String = "",
    val activityStep: Int = 0,
    val resultPreview: String = "",
    val errorPreview: String = "",
    val queuePosition: Int = 0,
) {
    val active: Boolean get() = state in setOf(SubagentTaskState.QUEUED, SubagentTaskState.STARTING, SubagentTaskState.RUNNING)
    val terminal: Boolean get() = !active
}

data class SubagentDashboardState(
    val parentSessionId: String = "",
    val tasks: List<SubagentTaskView> = emptyList(),
) {
    val running: Int get() = tasks.count { it.state == SubagentTaskState.RUNNING || it.state == SubagentTaskState.STARTING }
    val queued: Int get() = tasks.count { it.state == SubagentTaskState.QUEUED }
    val completed: Int get() = tasks.count { it.state == SubagentTaskState.IDLE }
    val failed: Int get() = tasks.count { it.state in setOf(SubagentTaskState.ERROR, SubagentTaskState.INTERRUPTED) }
}

private data class SubagentLiveActivity(
    val detail: String = "",
    val tool: String = "",
    val step: Int = 0,
)

// Atomic, bounded on-disk task records so child/parent linkage survives process death.
private class SubagentTaskStore(workDir: File) {
    private val dir = PathSecurity.resolveWithin(workDir, ".droide/subagents")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var recovered = false

    private fun ensureDir(): File = dir.apply { check(isDirectory || mkdirs()) { "Cannot create subagent task directory" } }

    suspend fun create(record: SubagentTaskRecord): SubagentTaskRecord = mutex.withLock {
        ensureRecoveredLocked()
        saveLocked(record)
        record
    }

    suspend fun get(id: String): SubagentTaskRecord? = mutex.withLock {
        ensureRecoveredLocked()
        readLocked(id)
    }

    suspend fun update(id: String, transform: (SubagentTaskRecord) -> SubagentTaskRecord): SubagentTaskRecord? = mutex.withLock {
        ensureRecoveredLocked()
        val current = readLocked(id) ?: return@withLock null
        val next = transform(current).copy(updatedAtMs = System.currentTimeMillis())
        saveLocked(next)
        next
    }

    suspend fun list(parentSessionId: String): List<SubagentTaskRecord> = mutex.withLock {
        ensureRecoveredLocked()
        filesLocked().mapNotNull(::readFileLocked)
            .filter { it.parentSessionId == parentSessionId }
            .sortedByDescending { it.updatedAtMs }
            .take(MAX_TASKS_PER_PARENT)
    }

    suspend fun pendingDeliveries(parentSessionId: String, excludeTaskId: String? = null): List<SubagentTaskRecord> =
        list(parentSessionId).filter {
            it.id != excludeTaskId && it.background && it.delivery == SubagentDeliveryState.PENDING &&
                it.state in setOf(SubagentTaskState.IDLE, SubagentTaskState.ERROR, SubagentTaskState.INTERRUPTED, SubagentTaskState.KILLED)
        }.sortedBy { it.updatedAtMs }

    private fun ensureRecoveredLocked() {
        if (recovered) return
        recovered = true
        filesLocked().forEach { file ->
            val record = readFileLocked(file) ?: return@forEach
            if (record.state in setOf(SubagentTaskState.QUEUED, SubagentTaskState.STARTING, SubagentTaskState.RUNNING)) {
                saveLocked(record.copy(
                    state = SubagentTaskState.INTERRUPTED,
                    error = "Subagent execution was interrupted by process/workspace restart.",
                    delivery = if (record.background) SubagentDeliveryState.PENDING else SubagentDeliveryState.NONE,
                    endedAtMs = System.currentTimeMillis(),
                    updatedAtMs = System.currentTimeMillis(),
                ))
            }
        }
    }

    private fun readLocked(id: String): SubagentTaskRecord? {
        val safe = PathSecurity.safeLeafName(id)
        return readFileLocked(File(dir, "$safe.json"))
    }

    private fun readFileLocked(file: File): SubagentTaskRecord? {
        if (!file.isFile || file.length() > MAX_RECORD_BYTES) return null
        return runCatching { json.decodeFromString<SubagentTaskRecord>(file.readText()) }.getOrNull()
    }

    private fun filesLocked(): List<File> = ensureDir().listFiles().orEmpty().asSequence()
        .filter { it.isFile && it.name.startsWith("task-") && it.extension == "json" && it.length() <= MAX_RECORD_BYTES }
        .sortedByDescending { it.lastModified() }
        .take(MAX_TOTAL_TASK_RECORDS)
        .toList()

    private fun saveLocked(record: SubagentTaskRecord) {
        val target = File(ensureDir(), "${PathSecurity.safeLeafName(record.id)}.json")
        val tmp = File(dir, ".${target.name}.tmp-${System.nanoTime()}")
        try {
            val encoded = json.encodeToString(SubagentTaskRecord.serializer(), record)
            require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES) { "Subagent task record too large" }
            tmp.writeText(encoded)
            runCatching {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    companion object {
        private const val MAX_RECORD_BYTES = 64 * 1024L
        private const val MAX_TOTAL_TASK_RECORDS = 256
        private const val MAX_TASKS_PER_PARENT = 64
    }
}

// Fresh tasks create durable child sessions.







class SubagentManager(
    private val files: FileRepository,
    private val terminal: ITerminalSession,
    private val git: GitManager,
    private val approvals: ApprovalManager,
    private val lsp: LspManager,
    private val questions: QuestionManager,
    private val parentPermissions: PermissionEngine,
    private val workDir: File,
    private val depth: Int,
    private val maxDepth: Int,
    private val pluginSource: AgentPluginSource = AgentPluginSource.EMPTY,
    private val documentAuthority: WorkspaceDocumentAuthority? = null,
    private val onBackgroundCompletion: suspend (SubagentCompletion) -> Unit,
) {
    private val store = SubagentTaskStore(workDir)
    private val workspaceManager = WorktreeManager(workDir)
    private val profiles = AgentProfileManager(workDir, pluginSource)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val slots = Semaphore(MAX_CONCURRENT_SUBAGENTS)
    
    private val sharedMutationSlot = Semaphore(1)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val liveChildren = ConcurrentHashMap<String, AgentService>()
    private val liveConfigs = ConcurrentHashMap<String, AgentConfig>()
    private val liveActivity = ConcurrentHashMap<String, SubagentLiveActivity>()
    @Volatile private var boundParentSessionId: String? = null
    private val _dashboard = MutableStateFlow(SubagentDashboardState())
    val dashboard: StateFlow<SubagentDashboardState> = _dashboard.asStateFlow()

    // Switches the monitoring surface to one parent session and restores durable task rows after restart.
    fun bindParent(parentSessionId: String?) {
        boundParentSessionId = parentSessionId
        if (parentSessionId == null) {
            _dashboard.value = SubagentDashboardState()
            return
        }
        scope.launch { refreshDashboard(parentSessionId) }
    }

    fun profileSummaries(): List<AgentProfileSummary> = profiles.subagentSummaries()
    fun profileMode(name: String): AgentMode? = profiles.get(name)?.takeIf { it.subagent }?.mode

    suspend fun run(
        parentSessionId: String,
        description: String,
        prompt: String,
        profileName: String,
        taskId: String?,
        background: Boolean,
        workspace: String? = null,
        config: AgentConfig,
        onProgress: (AgentToolProgress) -> Unit = {},
    ): String {
        require(depth < maxDepth) { "Subagent depth limit reached ($maxDepth)" }
        require(description.isNotBlank() && description.length <= 160) { "Subagent description must be 1..160 chars" }
        require(prompt.isNotBlank() && prompt.length <= MAX_PROMPT_CHARS) { "Subagent prompt must be 1..$MAX_PROMPT_CHARS chars" }
        val profile = profiles.get(profileName)?.takeIf { it.subagent }
            ?: return "ERROR: unknown subagent_type '$profileName'. Available: ${profiles.subagents().joinToString(", ") { it.name }}"
        val effectiveConfig = profile.applyModel(config)
        runCatching { ProviderRuntimeGuard.requireCertifiedSelection(effectiveConfig.providerId, effectiveConfig.baseUrl, effectiveConfig.model) }
            .getOrElse { return "ERROR: subagent model selection is not live-certified: ${it.message}" }

        val record = if (taskId.isNullOrBlank()) {
            val id = "task-${UUID.randomUUID()}"
            val requestedMode = resolveWorkspaceMode(profile, workspace)
            val workspaceInfo = if (requestedMode == SubagentWorkspaceMode.ISOLATED) {
                runCatching {
                    workspaceManager.provision(
                        name = id,
                        branch = "droide-agent/${id.removePrefix("task-").take(12)}",
                    )
                }.getOrElse { e ->
                    return "ERROR: isolated subagent workspace could not be created: ${e.message}"
                }
            } else null
            val childRoot = workspaceInfo?.directory?.let(::File) ?: workDir
            val sessionManager = SessionManager(childRoot)
            val child = try {
                sessionManager.create(title = "${profile.name}: ${description.take(80)}", parentId = parentSessionId)
            } catch (t: Throwable) {
                if (workspaceInfo != null) runCatching { workspaceManager.remove(workspaceInfo.name, force = true) }
                throw t
            }
            try {
                store.create(SubagentTaskRecord(
                    id = id,
                    parentSessionId = parentSessionId,
                    childSessionId = child.id,
                    profile = profile.name,
                    profileRevision = profile.revision,
                    description = description,
                    background = background,
                    state = SubagentTaskState.QUEUED,
                    delivery = if (background) SubagentDeliveryState.PENDING else SubagentDeliveryState.NONE,
                    workspaceMode = requestedMode,
                    workspaceName = workspaceInfo?.name.orEmpty(),
                    workspacePath = workspaceInfo?.directory.orEmpty(),
                    workspaceBranch = workspaceInfo?.branch.orEmpty(),
                    workspaceBaseCommit = workspaceInfo?.baseCommit.orEmpty(),
                    workspaceParentBranch = workspaceInfo?.parentBranch.orEmpty(),
                    workspaceParentRepositoryIdentity = workspaceInfo?.parentRepositoryIdentity.orEmpty(),
                    integration = if (workspaceInfo != null) SubagentIntegrationState.PENDING else SubagentIntegrationState.NONE,
                    providerId = effectiveConfig.providerId,
                    modelId = effectiveConfig.model,
                    lastPrompt = prompt.take(MAX_PERSISTED_PROMPT_CHARS),
                ))
            } catch (t: Throwable) {
                try { sessionManager.delete(child.id) } catch (_: Throwable) { }
                if (workspaceInfo != null) runCatching { workspaceManager.remove(workspaceInfo.name, force = true) }
                throw t
            }
        } else {
            val existing = store.get(taskId) ?: return "ERROR: subagent task not found: $taskId"
            if (existing.parentSessionId != parentSessionId) return "ERROR: task $taskId does not belong to this parent session"
            if (existing.profile != profile.name) return "ERROR: task $taskId uses subagent_type '${existing.profile}', not '${profile.name}'"
            if (!profile.acceptsStoredRevision(existing.profileRevision)) {
                return if (existing.profileRevision.isBlank())
                    "ERROR: task $taskId predates verifiable custom Agent profile identity; start a new task."
                else "ERROR: Agent profile '${profile.name}' changed since task $taskId was created; start a new task instead of resuming under different instructions/capabilities."
            }
            if (existing.state == SubagentTaskState.KILLED) return "ERROR: task $taskId was killed and cannot be resumed; start a new task."
            if (existing.integration == SubagentIntegrationState.MERGED || existing.integration == SubagentIntegrationState.DISCARDED) {
                return "ERROR: task $taskId workspace is ${existing.integration.name.lowercase()} and cannot be resumed; start a new task."
            }
            if (existing.state in setOf(SubagentTaskState.QUEUED, SubagentTaskState.RUNNING, SubagentTaskState.STARTING)) {
                return "ERROR: task $taskId is already running; do not duplicate work."
            }
            if (workspace != null && workspace.isNotBlank() && resolveWorkspaceMode(profile, workspace) != existing.workspaceMode) {
                return "ERROR: task $taskId workspace mode is fixed to ${existing.workspaceMode.name.lowercase()} for its lifetime"
            }
            val childRoot = workspaceRoot(existing)
                ?: return "ERROR: isolated workspace missing for task $taskId: ${existing.workspaceName}"
            val childSession = SessionManager(childRoot).get(existing.childSessionId)
                ?: return "ERROR: child session missing for task $taskId: ${existing.childSessionId}"
            if (childSession.parentId != parentSessionId) {
                return "ERROR: child session ownership mismatch for task $taskId"
            }
            store.update(taskId) { it.copy(
                background = background,
                state = SubagentTaskState.QUEUED,
                delivery = if (background) SubagentDeliveryState.PENDING else SubagentDeliveryState.NONE,
                attempt = it.attempt + 1,
                profileRevision = profile.revision,
                providerId = effectiveConfig.providerId,
                modelId = effectiveConfig.model,
                startedAtMs = 0L,
                endedAtMs = 0L,
                lastPrompt = prompt.take(MAX_PERSISTED_PROMPT_CHARS),
                result = "",
                error = "",
            ) } ?: return "ERROR: subagent task disappeared: $taskId"
        }

        refreshDashboard(record.parentSessionId)

        if (background) {
            val job = scope.launch {
                val terminalRecord = executeScheduled(record.id, prompt, profile, effectiveConfig, onProgress)
                val activeSiblings = store.list(terminalRecord.parentSessionId).count {
                    it.id != terminalRecord.id && it.state in setOf(SubagentTaskState.QUEUED, SubagentTaskState.STARTING, SubagentTaskState.RUNNING)
                }
                runCatching { onBackgroundCompletion(SubagentCompletion(terminalRecord, effectiveConfig, activeSiblings)) }
            }
            jobs[record.id] = job
            job.invokeOnCompletion { jobs.remove(record.id, job) }
            return "SUBAGENT_STARTED task_id=${record.id} child_session_id=${record.childSessionId} profile=${profile.name} state=queued " +
                "workspace=${record.workspaceMode.name.lowercase()}${if (record.workspaceName.isNotBlank()) " workspace_id=${record.workspaceName}" else ""}\n" +
                "The task runs independently. Do not poll or duplicate its work; continue non-overlapping work. Its result will be delivered automatically."
        }

        val terminalRecord = executeScheduled(record.id, prompt, profile, effectiveConfig, onProgress)
        return renderResult(terminalRecord)
    }

    private suspend fun executeScheduled(
        taskId: String, prompt: String, profile: AgentProfile, config: AgentConfig, onProgress: (AgentToolProgress) -> Unit,
    ): SubagentTaskRecord = slots.withPermit {
        val record = store.update(taskId) { it.copy(state = SubagentTaskState.STARTING) }
            ?: error("Subagent task disappeared: $taskId")
        refreshDashboard(record.parentSessionId)
        if (profile.mode == AgentMode.BUILD && record.workspaceMode == SubagentWorkspaceMode.SHARED) {
            sharedMutationSlot.withPermit { execute(taskId, prompt, profile, config, onProgress) }
        } else {
            execute(taskId, prompt, profile, config, onProgress)
        }
    }

    suspend fun control(parentSessionId: String, action: String, taskId: String?, message: String? = null): String = when (action) {
        "list" -> store.list(parentSessionId).joinToString("\n") { summary(it) }.ifBlank { "No subagent tasks." }
        "status" -> {
            val id = taskId ?: return "ERROR: task_control status requires task_id"
            val record = ownedTask(parentSessionId, id) ?: return "ERROR: subagent task not found or not owned by this parent: $id"
            val workspaceStatus = if (record.workspaceMode == SubagentWorkspaceMode.ISOLATED && record.workspaceName.isNotBlank() &&
                record.integration !in setOf(SubagentIntegrationState.MERGED, SubagentIntegrationState.DISCARDED)
            ) "\n${workspaceManager.status(record.workspaceName)}" else ""
            summary(record) + workspaceStatus +
                if (record.result.isNotBlank()) "\nresult=${record.result.take(4000)}"
                else if (record.error.isNotBlank()) "\nerror=${record.error.take(2000)}" else ""
        }
        "review" -> {
            val id = taskId ?: return "ERROR: task_control review requires task_id"
            val record = ownedTask(parentSessionId, id) ?: return "ERROR: subagent task not found or not owned by this parent: $id"
            if (record.workspaceMode != SubagentWorkspaceMode.ISOLATED || record.workspaceName.isBlank()) {
                return "Task $id uses shared workspace; review the parent Git diff directly."
            }
            "SUBAGENT_REVIEW task_id=$id\n${workspaceManager.reviewChanges(record.workspaceName)}"
        }
        "transcript" -> {
            val id = taskId ?: return "ERROR: task_control transcript requires task_id"
            val record = ownedTask(parentSessionId, id) ?: return "ERROR: subagent task not found or not owned by this parent: $id"
            renderTranscript(record)
        }
        "message" -> {
            val id = taskId ?: return "ERROR: task_control message requires task_id"
            val text = message?.trim().orEmpty()
            if (text.isBlank()) return "ERROR: task_control message requires non-empty message"
            if (text.length > MAX_STEER_MESSAGE_CHARS) return "ERROR: subagent message exceeds $MAX_STEER_MESSAGE_CHARS characters"
            val record = ownedTask(parentSessionId, id) ?: return "ERROR: subagent task not found or not owned by this parent: $id"
            if (!record.background || record.state != SubagentTaskState.RUNNING) {
                return "ERROR: direct steer is available only for an actively running background child. ${summary(record)}"
            }
            val child = liveChildren[id] ?: return "ERROR: task $id is transitioning; steer again when it reports Running"
            val config = liveConfigs[id] ?: return "ERROR: task $id runtime configuration is unavailable"
            if (!child.steerActive(text, config, record.childSessionId)) {
                return "ERROR: task $id reached a provider-turn boundary and is no longer accepting live steer; resume it with task(...) instead"
            }
            liveActivity[id] = SubagentLiveActivity("Steer admitted · ${text.take(96)}")
            publishLive(id, parentSessionId, liveActivity.getValue(id))
            "SUBAGENT_STEER_ADMITTED task_id=$id child_session_id=${record.childSessionId}"
        }
        "cancel" -> {
            val id = taskId ?: return "ERROR: task_control cancel requires task_id"
            val record = ownedTask(parentSessionId, id) ?: return "ERROR: subagent task not found or not owned by this parent: $id"
            val job = jobs[id]
            if (job == null || !job.isActive) return "Task $id is not running. ${summary(record)}"
            job.cancel(CancellationException("Cancelled by parent agent"))
            val killed = store.update(id) { it.copy(
                state = SubagentTaskState.KILLED,
                error = "Cancelled by parent agent",
                delivery = SubagentDeliveryState.DELIVERED,
                endedAtMs = System.currentTimeMillis(),
            ) }
            liveActivity.remove(id)
            if (killed != null) refreshDashboard(killed.parentSessionId)
            "SUBAGENT_CANCELLED task_id=$id"
        }
        "merge" -> {
            val id = taskId ?: return "ERROR: task_control merge requires task_id"
            val record = ownedTask(parentSessionId, id) ?: return "ERROR: subagent task not found or not owned by this parent: $id"
            if (record.workspaceMode != SubagentWorkspaceMode.ISOLATED) return "ERROR: task $id uses shared workspace; there is nothing isolated to merge"
            if (record.state != SubagentTaskState.IDLE) return "ERROR: task $id must complete successfully before merge (state=${record.state.name.lowercase()})"
            if (record.integration == SubagentIntegrationState.MERGED) return "Task $id is already merged."
            if (record.integration == SubagentIntegrationState.DISCARDED) return "ERROR: task $id workspace was discarded"
            val merged = workspaceManager.merge(
                name = record.workspaceName,
                expectedBaseCommit = record.workspaceBaseCommit,
                expectedParentBranch = record.workspaceParentBranch,
                expectedParentRepositoryIdentity = record.workspaceParentRepositoryIdentity,
                expectedChildBranch = record.workspaceBranch,
                commitMessage = "Droide subagent ${record.description.take(120)}",
            )
            if (merged.merged) {
                val cleanup = runCatching { workspaceManager.remove(record.workspaceName, force = true) }.getOrNull()
                val mergedRecord = store.update(id) { it.copy(integration = SubagentIntegrationState.MERGED) }
                if (mergedRecord != null) refreshDashboard(mergedRecord.parentSessionId)
                "SUBAGENT_MERGED task_id=$id no_changes=${merged.noChanges}\n${merged.message}${cleanup?.let { "\n$it" }.orEmpty()}"
            } else {
                val conflicted = store.update(id) { it.copy(integration = SubagentIntegrationState.CONFLICT) }
                if (conflicted != null) refreshDashboard(conflicted.parentSessionId)
                "SUBAGENT_MERGE_BLOCKED task_id=$id\n${merged.message}"
            }
        }
        "discard" -> {
            val id = taskId ?: return "ERROR: task_control discard requires task_id"
            val record = ownedTask(parentSessionId, id) ?: return "ERROR: subagent task not found or not owned by this parent: $id"
            if (record.workspaceMode != SubagentWorkspaceMode.ISOLATED) return "ERROR: task $id uses shared workspace; nothing to discard"
            if (jobs[id]?.isActive == true) return "ERROR: cancel task $id before discarding its workspace"
            if (record.integration == SubagentIntegrationState.MERGED) return "ERROR: task $id was already merged"
            val removed = workspaceManager.remove(record.workspaceName, force = true)
            val discarded = store.update(id) { it.copy(integration = SubagentIntegrationState.DISCARDED) }
            if (discarded != null) refreshDashboard(discarded.parentSessionId)
            "SUBAGENT_DISCARDED task_id=$id\n$removed"
        }
        else -> "ERROR: unsupported task_control action: $action"
    }

    suspend fun pendingDeliveries(parentSessionId: String, excludeTaskId: String? = null): List<SubagentTaskRecord> =
        store.pendingDeliveries(parentSessionId, excludeTaskId)

    suspend fun markDelivered(taskId: String) {
        store.update(taskId) { it.copy(delivery = SubagentDeliveryState.DELIVERED) }
    }

    fun close() {
        jobs.values.forEach { it.cancel(CancellationException("Workspace closed")) }
        jobs.clear()
        liveChildren.clear()
        liveConfigs.clear()
        liveActivity.clear()
        _dashboard.value = SubagentDashboardState()
        scope.cancel("Workspace closed")
    }

    private suspend fun execute(
        taskId: String,
        prompt: String,
        profile: AgentProfile,
        config: AgentConfig,
        onProgress: (AgentToolProgress) -> Unit,
    ): SubagentTaskRecord {
        val starting = store.get(taskId) ?: error("Subagent task disappeared: $taskId")
        val running = store.update(taskId) { it.copy(
            state = SubagentTaskState.RUNNING,
            startedAtMs = if (it.startedAtMs > 0L) it.startedAtMs else System.currentTimeMillis(),
            endedAtMs = 0L,
        ) } ?: starting.copy(state = SubagentTaskState.RUNNING)
        refreshDashboard(running.parentSessionId)
        onProgress(AgentToolProgress(
            title = "${profile.name} subagent",
            detail = "Running · ${starting.childSessionId} · ${starting.workspaceMode.name.lowercase()}",
        ))
        currentCoroutineContext().ensureActive()

        val childHardRules = if (depth + 1 >= maxDepth)
            listOf(PermRule("subagent", "*", PermEffect.DENY)) else emptyList()
        val childRoot = workspaceRoot(starting)
            ?: throw IllegalStateException("Isolated workspace missing for task $taskId: ${starting.workspaceName}")
        val childPermissions = parentPermissions.fork(childHardRules, profile.tools)
        val isolated = starting.workspaceMode == SubagentWorkspaceMode.ISOLATED
        val childFiles = if (isolated) FileRepository(childRoot) else files
        val childTerminal: ITerminalSession = if (isolated) TerminalSession(childRoot, scope) else terminal
        val childGit = if (isolated) git.forWorkDir(childRoot) else git
        val childLsp: LspManager = if (isolated) {
            ProfessionalLspManager(scope, childFiles, CliLspManager(childTerminal, childFiles))
        } else lsp
        val workspaceOverlay = if (isolated) """
            You are running inside an isolated Droide child workspace.
            Workspace root: ${starting.workspacePath}
            Branch: ${starting.workspaceBranch}
            Base commit: ${starting.workspaceBaseCommit}
            Make and verify changes only in this isolated workspace. Do not attempt to merge into or edit the parent workspace yourself.
            The parent agent will inspect and integrate your branch through task_control after you finish.
        """.trimIndent() else ""
        val child = AgentService(
            files = childFiles,
            terminal = childTerminal,
            git = childGit,
            approvals = approvals,
            lsp = childLsp,
            questions = questions,
            perms = childPermissions,
            workDir = childRoot,
            subagentDepth = depth + 1,
            maxSubagentDepth = maxDepth,
            systemOverlay = listOf(profile.systemInstructions, workspaceOverlay).filter { it.isNotBlank() }.joinToString("\n\n"),
            executionIdentity = AgentExecutionIdentity.subagent(
                agentType = profile.name,
                taskId = taskId,
                parentSessionId = starting.parentSessionId,
                depth = depth + 1,
            ),
            agentPluginSource = pluginSource,
            documentAuthority = if (isolated) null else documentAuthority,
        )
        liveChildren[taskId] = child
        liveConfigs[taskId] = config
        return try {
            val session = child.sessions.get(starting.childSessionId)
                ?: throw IllegalStateException("Child session missing: ${starting.childSessionId}")
            child.loadSession(session)
            child.setMode(profile.mode)
            coroutineScope {
                val monitor = launch {
                    child.streamState.collect { state ->
                        val part = state.parts.lastOrNull { it.state == AgentStreamPartState.RUNNING || it.state == AgentStreamPartState.PENDING }
                            ?: state.parts.lastOrNull()
                        val activity = when (part?.kind) {
                            AgentStreamPartKind.TOOL -> SubagentLiveActivity(
                                detail = part.detail.ifBlank { "Using ${part.title.ifBlank { part.toolName }}" },
                                tool = part.toolName.ifBlank { part.title },
                                step = part.step,
                            )
                            AgentStreamPartKind.TEXT -> SubagentLiveActivity("Responding", step = part.step)
                            AgentStreamPartKind.STEP -> SubagentLiveActivity(part.detail.ifBlank { part.title.ifBlank { "Model step ${part.step}" } }, step = part.step)
                            AgentStreamPartKind.RETRY -> SubagentLiveActivity(part.detail.ifBlank { "Retrying" }, step = part.step)
                            AgentStreamPartKind.COMPACTION -> SubagentLiveActivity(part.detail.ifBlank { "Compacting context" }, step = part.step)
                            else -> SubagentLiveActivity(if (state.status == AgentStreamState.Status.BUSY) "Working" else "")
                        }
                        liveActivity[taskId] = activity
                        publishLive(taskId, running.parentSessionId, activity)
                    }
                }
                try { child.chat(prompt, config, maxTurns = SUBAGENT_MAX_TURNS) } finally { monitor.cancel() }
            }
            val lastPlain = child.messages.value.asReversed().firstOrNull {
                it.role == "assistant" && it.streamPart == null && it.content.isNotBlank()
            }
            if (lastPlain?.content == "Stopped by user.") throw CancellationException("Subagent stopped")
            if (lastPlain?.content?.startsWith("Error:") == true) {
                throw IllegalStateException(lastPlain.content.removePrefix("Error:").trim().ifBlank { "Subagent failed" })
            }
            val result = lastPlain?.content?.take(MAX_RESULT_CHARS).orEmpty()
                .ifBlank { "Subagent completed without a textual final report." }
            val completed = store.update(taskId) { it.copy(
                state = SubagentTaskState.IDLE,
                result = result,
                endedAtMs = System.currentTimeMillis(),
                error = "",
                delivery = if (it.background) SubagentDeliveryState.PENDING else SubagentDeliveryState.DELIVERED,
            ) } ?: error("Subagent task disappeared after completion")
            liveActivity.remove(taskId)
            refreshDashboard(completed.parentSessionId)
            onProgress(AgentToolProgress(
                title = "${profile.name} subagent",
                detail = "Completed · ${starting.childSessionId}${if (starting.workspaceMode == SubagentWorkspaceMode.ISOLATED) " · awaiting merge" else ""}",
            ))
            completed
        } catch (cancel: CancellationException) {
            val killed = store.update(taskId) { it.copy(
                state = SubagentTaskState.KILLED,
                error = cancel.message ?: "Subagent cancelled",
                endedAtMs = System.currentTimeMillis(),
                delivery = if (it.background) SubagentDeliveryState.PENDING else SubagentDeliveryState.NONE,
            ) } ?: starting.copy(state = SubagentTaskState.KILLED, error = "Subagent cancelled")
            liveActivity.remove(taskId)
            refreshDashboard(killed.parentSessionId)
            onProgress(AgentToolProgress(title = "${profile.name} subagent", detail = "Cancelled"))
            if (!starting.background) throw cancel
            killed
        } catch (t: Throwable) {
            val failed = store.update(taskId) { it.copy(
                state = SubagentTaskState.ERROR,
                error = (t.message ?: t::class.java.simpleName).take(MAX_ERROR_CHARS),
                endedAtMs = System.currentTimeMillis(),
                delivery = if (it.background) SubagentDeliveryState.PENDING else SubagentDeliveryState.NONE,
            ) } ?: starting.copy(state = SubagentTaskState.ERROR, error = t.message.orEmpty())
            liveActivity.remove(taskId)
            refreshDashboard(failed.parentSessionId)
            onProgress(AgentToolProgress(title = "${profile.name} subagent", detail = "Failed: ${failed.error.take(160)}"))
            failed
        } finally {
            liveChildren.remove(taskId, child)
            liveConfigs.remove(taskId)
            child.shutdown()
            if (isolated) {
                if (pluginSource === AgentPluginRuntime) runCatching { AgentPluginRuntime.cleanupWorkspace(childRoot) }
                runCatching { childLsp.close() }
                runCatching { childTerminal.destroy() }
            }
        }
    }

    private suspend fun refreshDashboard(parentSessionId: String) {
        if (boundParentSessionId != parentSessionId) return
        val records = store.list(parentSessionId)
        val queued = records.filter { it.state == SubagentTaskState.QUEUED }.sortedBy { it.createdAtMs }
        val queueIndex = queued.mapIndexed { index, record -> record.id to index + 1 }.toMap()
        _dashboard.value = SubagentDashboardState(
            parentSessionId = parentSessionId,
            tasks = records.map { toView(it, liveActivity[it.id], queueIndex[it.id] ?: 0) }
                .sortedWith(compareBy<SubagentTaskView>({ if (it.active) 0 else 1 }, { it.createdAtMs })),
        )
    }

    private fun publishLive(taskId: String, parentSessionId: String, activity: SubagentLiveActivity) {
        if (boundParentSessionId != parentSessionId) return
        _dashboard.update { current ->
            current.copy(tasks = current.tasks.map { task ->
                if (task.id == taskId) task.copy(
                    activity = activity.detail, activityTool = activity.tool, activityStep = activity.step,
                ) else task
            })
        }
    }

    private fun toView(record: SubagentTaskRecord, live: SubagentLiveActivity?, queuePosition: Int): SubagentTaskView = SubagentTaskView(
        id = record.id,
        childSessionId = record.childSessionId,
        profile = record.profile,
        description = record.description,
        state = record.state,
        background = record.background,
        workspaceMode = record.workspaceMode,
        workspaceName = record.workspaceName,
        integration = record.integration,
        attempt = record.attempt,
        providerId = record.providerId,
        modelId = record.modelId,
        createdAtMs = record.createdAtMs,
        startedAtMs = record.startedAtMs,
        endedAtMs = record.endedAtMs,
        updatedAtMs = record.updatedAtMs,
        activity = live?.detail.orEmpty(),
        activityTool = live?.tool.orEmpty(),
        activityStep = live?.step ?: 0,
        resultPreview = record.result.take(1200),
        errorPreview = record.error.take(800),
        queuePosition = queuePosition,
    )

    private suspend fun ownedTask(parentSessionId: String, taskId: String): SubagentTaskRecord? =
        store.get(taskId)?.takeIf { it.parentSessionId == parentSessionId }

    private suspend fun renderTranscript(record: SubagentTaskRecord): String {
        val root = workspaceRoot(record) ?: return "ERROR: child workspace missing for task ${record.id}"
        val session = SessionManager(root).get(record.childSessionId) ?: return "ERROR: child session missing: ${record.childSessionId}"
        val body = session.messages.takeLast(MAX_TRANSCRIPT_MESSAGES).joinToString("\n") { message ->
            val part = message.streamPart
            if (part != null) {
                val detail = listOf(part.title, part.detail, part.text).filter { it.isNotBlank() }.joinToString(" · ")
                "[${part.kind.name.lowercase()}:${part.state.name.lowercase()}] ${detail.take(1200)}"
            } else "${message.role.uppercase()}: ${message.content.take(2400)}"
        }.trim()
        return "SUBAGENT_TRANSCRIPT task_id=${record.id} child_session_id=${record.childSessionId} messages=${session.messages.size}\n" +
            body.ifBlank { "(no persisted transcript yet)" }.take(MAX_TRANSCRIPT_CHARS)
    }

    private fun resolveWorkspaceMode(profile: AgentProfile, requested: String?): SubagentWorkspaceMode = when (requested?.trim()?.lowercase()) {
        null, "", "auto" -> if (profile.mode == AgentMode.BUILD) SubagentWorkspaceMode.ISOLATED else SubagentWorkspaceMode.SHARED
        "isolated", "branch", "worktree" -> SubagentWorkspaceMode.ISOLATED
        "shared", "inherit" -> SubagentWorkspaceMode.SHARED
        else -> throw IllegalArgumentException("workspace must be auto|isolated|shared")
    }

    private fun workspaceRoot(record: SubagentTaskRecord): File? {
        if (record.workspaceMode == SubagentWorkspaceMode.SHARED) return workDir
        if (record.workspaceName.isBlank() || record.workspacePath.isBlank()) return null
        val managed = workspaceManager.directory(record.workspaceName) ?: return null
        val expected = runCatching { File(record.workspacePath).canonicalFile }.getOrNull() ?: return null
        return managed.canonicalFile.takeIf { it == expected }
    }

    private fun renderResult(record: SubagentTaskRecord): String = when (record.state) {
        SubagentTaskState.IDLE -> buildString {
            append("SUBAGENT_COMPLETED task_id=${record.id} child_session_id=${record.childSessionId} workspace=${record.workspaceMode.name.lowercase()}")
            if (record.workspaceName.isNotBlank()) append(" workspace_id=${record.workspaceName} integration=${record.integration.name.lowercase()}")
            append("\n${record.result}")
            if (record.workspaceMode == SubagentWorkspaceMode.ISOLATED) {
                append("\nChanges remain isolated. Inspect status, then use task_control(action=\"merge\", task_id=\"${record.id}\") or discard explicitly.")
            }
        }
        else -> "SUBAGENT_${record.state.name} task_id=${record.id} child_session_id=${record.childSessionId} workspace=${record.workspaceMode.name.lowercase()}\n${record.error.ifBlank { record.result }}"
    }

    private fun summary(record: SubagentTaskRecord): String =
        "task_id=${record.id} child_session_id=${record.childSessionId} profile=${record.profile} state=${record.state.name.lowercase()} " +
            "background=${record.background} delivery=${record.delivery.name.lowercase()} workspace=${record.workspaceMode.name.lowercase()} " +
            "workspace_id=${record.workspaceName.ifBlank { "-" }} integration=${record.integration.name.lowercase()} attempt=${record.attempt} " +
            "description=${record.description.take(120)}"

    companion object {
        const val DEFAULT_MAX_DEPTH = 1
        const val MAX_CONCURRENT_SUBAGENTS = 4
        const val SUBAGENT_MAX_TURNS = 24
        const val MAX_PROMPT_CHARS = 24_000
        const val MAX_STEER_MESSAGE_CHARS = 8_000
        const val MAX_TRANSCRIPT_MESSAGES = 60
        const val MAX_TRANSCRIPT_CHARS = 16_000
        private const val MAX_PERSISTED_PROMPT_CHARS = 12_000
        private const val MAX_RESULT_CHARS = 16_000
        private const val MAX_ERROR_CHARS = 2_000
    }
}

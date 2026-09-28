package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

 
data class SubagentInputProjection(
    val messages: List<AgentMessage>,
    val apiEntries: List<JsonObject>,
    val deliveryTaskIds: List<String> = emptyList(),
)

 
class AgentSubagentBridge(
    files: FileRepository,
    terminal: ITerminalSession,
    git: GitManager,
    approvals: ApprovalManager,
    lsp: LspManager,
    questions: QuestionManager,
    permissions: PermissionEngine,
    workDir: File,
    depth: Int,
    maxDepth: Int,
    pluginSource: AgentPluginSource = AgentPluginSource.EMPTY,
    documentAuthority: WorkspaceDocumentAuthority? = null,
    private val maxAssistantChars: Int,
    private val maxApiMessageChars: Int,
    onBackgroundCompletion: suspend (SubagentCompletion) -> Unit,
) {
    private val emptyDashboard = MutableStateFlow(SubagentDashboardState())

    val manager: SubagentManager? = if (depth < maxDepth) SubagentManager(
        files = files,
        terminal = terminal,
        git = git,
        approvals = approvals,
        lsp = lsp,
        questions = questions,
        parentPermissions = permissions,
        workDir = workDir,
        depth = depth,
        maxDepth = maxDepth,
        pluginSource = pluginSource,
        documentAuthority = documentAuthority,
        onBackgroundCompletion = onBackgroundCompletion,
        ) else null

    val dashboard: StateFlow<SubagentDashboardState> get() = manager?.dashboard ?: emptyDashboard

    fun bindParent(parentSessionId: String?) {
        manager?.bindParent(parentSessionId) ?: run { emptyDashboard.value = SubagentDashboardState() }
    }

    suspend fun controlFromUi(parentSessionId: String, action: String, taskId: String, message: String? = null): String =
        manager?.control(parentSessionId, action, taskId, message) ?: "Subagents unavailable in this context."

    suspend fun projectInitial(
        origin: AgentInputOrigin,
        sourceId: String?,
        text: String,
        runId: Long,
        images: List<AgentImageRef> = emptyList(),
        ideContext: AgentIdeContextSnapshot? = null,
    ): SubagentInputProjection {
        if (origin == AgentInputOrigin.USER) {
            return SubagentInputProjection(
                messages = listOf(AgentMessage("user", text)),
                apiEntries = listOf(VisionSupport.userMessage(AgentIdeContextProjection.forModel(text, ideContext, maxApiMessageChars), images, maxApiMessageChars)),
            )
        }
        val part = AgentStreamPart(
            id = "subagent-input:${sourceId ?: runId}",
            step = 0,
            kind = AgentStreamPartKind.STATUS,
            state = AgentStreamPartState.COMPLETED,
            title = "Subagent result",
            detail = "Background child completion delivered to parent",
            text = text.take(maxAssistantChars),
            endedAtMs = System.currentTimeMillis(),
        )
        return SubagentInputProjection(
            messages = listOf(AgentMessage("assistant", "", streamPart = part)),
            apiEntries = listOf(buildJsonObject {
                put("role", "user")
                put("content", "[Background subagent completion; synthetic system event, not user-authored]\n${text.take(maxApiMessageChars)}")
            }),
            deliveryTaskIds = listOfNotNull(sourceId),
        )
    }

    suspend fun recover(
        parentSessionId: String,
        excludeTaskId: String?,
        existingApiHistory: List<JsonObject>,
    ): SubagentInputProjection {
        val active = manager ?: return SubagentInputProjection(emptyList(), emptyList())
        val pending = active.pendingDeliveries(parentSessionId, excludeTaskId)
        if (pending.isEmpty()) return SubagentInputProjection(emptyList(), emptyList())
        val messages = mutableListOf<AgentMessage>()
        val api = mutableListOf<JsonObject>()
        val deliveryIds = mutableListOf<String>()
        pending.forEach { record ->
            // The persisted parent transcript is authoritative evidence of delivery.


            if (historyContainsTask(existingApiHistory, record.id)) {
                active.markDelivered(record.id)
                return@forEach
            }
            val text = render(record)
            messages += AgentMessage("assistant", "", streamPart = AgentStreamPart(
                id = "subagent-recovery:${record.id}",
                step = 0,
                kind = AgentStreamPartKind.STATUS,
                state = if (record.state == SubagentTaskState.IDLE) AgentStreamPartState.COMPLETED else AgentStreamPartState.ERROR,
                title = if (record.state == SubagentTaskState.IDLE) "Subagent completed" else "Subagent ${record.state.name.lowercase()}",
                detail = "Recovered durable result · ${record.profile} · ${record.childSessionId}",
                text = text.take(maxAssistantChars),
                endedAtMs = System.currentTimeMillis(),
            ))
            api += buildJsonObject {
                put("role", "user")
                put("content", "[Background subagent completion; recovered durable system event, not user-authored]\n${text.take(maxApiMessageChars)}")
            }
            deliveryIds += record.id
        }
        return SubagentInputProjection(messages, api, deliveryIds)
    }

    private fun historyContainsTask(history: List<JsonObject>, taskId: String): Boolean =
        history.any { entry ->
            entry["content"]?.toString()?.contains("task_id=$taskId") == true
        }

    suspend fun markDelivered(ids: List<String>) {
        val active = manager ?: return
        ids.forEach { active.markDelivered(it) }
    }

    fun render(record: SubagentTaskRecord): String = when (record.state) {
        SubagentTaskState.IDLE ->
            "SUBAGENT_COMPLETED task_id=${record.id} child_session_id=${record.childSessionId} profile=${record.profile} " +
                "workspace=${record.workspaceMode.name.lowercase()} integration=${record.integration.name.lowercase()}\n${record.result}" +
                if (record.workspaceMode == SubagentWorkspaceMode.ISOLATED) "\nChanges remain isolated until task_control merge/discard." else ""
        else ->
            "SUBAGENT_${record.state.name} task_id=${record.id} child_session_id=${record.childSessionId} profile=${record.profile} " +
                "workspace=${record.workspaceMode.name.lowercase()} integration=${record.integration.name.lowercase()}\n${record.error.ifBlank { record.result }}"
    }.take(maxApiMessageChars)

    fun close() = manager?.close()
}

internal fun renderSubagentCompletionText(record: SubagentTaskRecord, maxChars: Int, activeSiblings: Int = 0): String {
    val coordination = if (activeSiblings > 0)
        "\nremaining_parallel_agents=$activeSiblings; absorb this result but do not finalize the parallel work yet."
    else "\nremaining_parallel_agents=0; all currently launched sibling agents have settled, so synthesize their relevant results before finalizing."
    return (when (record.state) {
        SubagentTaskState.IDLE -> "SUBAGENT_COMPLETED task_id=${record.id} child_session_id=${record.childSessionId} profile=${record.profile} " +
            "workspace=${record.workspaceMode.name.lowercase()} integration=${record.integration.name.lowercase()}\n${record.result}" +
            if (record.workspaceMode == SubagentWorkspaceMode.ISOLATED) "\nChanges remain isolated until task_control review/merge/discard." else ""
        else -> "SUBAGENT_${record.state.name} task_id=${record.id} child_session_id=${record.childSessionId} profile=${record.profile} " +
            "workspace=${record.workspaceMode.name.lowercase()} integration=${record.integration.name.lowercase()}\n${record.error.ifBlank { record.result }}"
    } + coordination).take(maxChars)
}

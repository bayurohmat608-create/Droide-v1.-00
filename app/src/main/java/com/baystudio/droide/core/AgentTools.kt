package com.baystudio.droide.core
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException


 
data class AgentToolProgress(
    val outputDelta: String = "",
    val detail: String = "",
    val title: String = "",
)
object AgentTools {
    val definitions: JsonArray = AgentToolCatalog.definitions

     
    fun definitionsWithCustom(
        skills: SkillManager,
        customs: CustomToolManager,
        allowSubagents: Boolean = true,
        mode: AgentMode = AgentMode.BUILD,
        permissions: PermissionEngine? = null,
        permissionScope: String = mode.name.lowercase(),
        subagentProfiles: List<AgentProfileSummary> = AgentProfileManager.builtinSubagentSummaries(),
        mcpTools: List<McpAgentTool> = emptyList(),
        rules: AgentRuleManager? = null,
        pluginTools: List<AgentPluginToolDef> = emptyList(),
        ideAvailable: Boolean = false,
        lspEditAvailable: Boolean = false,
        toolchainAvailable: Boolean = false,
        capabilityAvailable: Boolean = false,
        browserAvailable: Boolean = false,
    ): JsonArray {
        val skillCatalog = skills.discover().take(80)
        val modelRules = rules?.modelVisible().orEmpty()
        val projectedDefinitions = definitions.map { element ->
            val fn = (element as? JsonObject)?.get("function") as? JsonObject
            when ((fn?.get("name") as? JsonPrimitive)?.contentOrNull) {
                "task" -> taskTool(subagentProfiles)
                "skill" -> contextLoaderTool("skill", "Load a skill on demand", skillCatalog.map { it.name to it.description })
                "rule" -> contextLoaderTool("rule", "Load an Agent rule on demand", modelRules.map { it.name to "[${it.activation.name.lowercase()}] ${it.description}" })
                else -> element
            }
        }
        val base = JsonArray(projectedDefinitions.filter { element ->
            val tool = element as? JsonObject ?: return@filter true
            val fn = tool["function"] as? JsonObject ?: return@filter true
            val name = (fn["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val action = actionOf(name)
            val permissionVisible = permissions == null || permissions.actionVisible(action, permissionScope) ||
                (name == "mcp" && permissions.actionVisible("mcp_repair", permissionScope))
            AgentModePolicy.isToolVisible(mode, name) &&
                (name != "ide" || ideAvailable) &&
                (name != "lsp_edit" || lspEditAvailable) &&
                (name != "toolchain" || toolchainAvailable) &&
                (name != "capability" || capabilityAvailable) &&
                (name != "browser" || browserAvailable) &&
                permissionVisible &&
                (permissions == null || permissions.toolAllowed(name)) &&
                (allowSubagents || name !in setOf("task", "task_control", "explore_codebase"))
        })
        val extra = buildJsonArray {
            

            customs.discover().forEach { ct ->
                val name = "custom_${ct.name}"
                if (AgentModePolicy.isToolVisible(mode, name) &&
                    (permissions == null || permissions.actionVisible("shell", permissionScope)) &&
                    (permissions == null || permissions.toolAllowed(name))) {
                    add(tool(name, ct.description,
                        buildJsonObject { put("input", buildJsonObject { put("type", "string") }) }))
                }
            }
            pluginTools.forEach { pt ->
                if (mode == AgentMode.BUILD &&
                    (permissions == null || permissions.actionVisible("shell", permissionScope)) &&
                    (permissions == null || permissions.toolAllowed(pt.functionName))) {
                    add(tool(pt.functionName, pt.description, pt.inputSchema))
                }
            }
            // Runtime permission and identity checks remain authoritative at execution time.

            mcpTools.forEach { mt ->
                if (AgentModePolicy.isToolVisible(mode, mt.functionName) &&
                    (permissions == null || permissions.actionVisible("mcp", permissionScope)) &&
                    (permissions == null || permissions.toolAllowed(mt.functionName))) {
                    add(mcpTool(mt))
                }
            }
        }
        return JsonArray(base + extra)
    }
    private fun contextLoaderTool(name: String, base: String, items: List<Pair<String, String>>): JsonObject {
        val available = items.distinctBy { it.first }.sortedBy { it.first }
        val hint = available.joinToString("; ") { "${it.first}: ${it.second.take(120)}" }.take(10_000)
        return tool(name, base + if (hint.isBlank()) "" else ". Available: $hint",
            buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    if (available.isNotEmpty()) put("enum", JsonArray(available.map { JsonPrimitive(it.first) }))
                })
            }, listOf("name"))
    }

    private fun mcpTool(tool: McpAgentTool): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", tool.functionName)
            put("description", ("MCP ${tool.serverName}/${tool.toolName}: " + tool.description).take(1_200))
            put("parameters", tool.inputSchema)
        })
    }

    private fun taskTool(profiles: List<AgentProfileSummary>): JsonObject {
        val available = profiles.filter { it.subagent }.distinctBy { it.name }.sortedBy { it.name }
        val detail = available.joinToString("; ") { "${it.name}: ${it.description.take(180)}" }
        return tool("task", "Delegate work to a fresh or resumable child-agent session" +
            if (detail.isBlank()) "" else ". Available profiles: $detail",
            buildJsonObject {
                put("description", buildJsonObject { put("type", "string"); put("description", "Short 3-8 word task label") })
                put("prompt", buildJsonObject { put("type", "string"); put("description", "Detailed autonomous instructions and expected result") })
                put("subagent_type", buildJsonObject {
                    put("type", "string")
                    put("enum", JsonArray(available.map { JsonPrimitive(it.name) }))
                    put("description", "Choose a discovered Agent profile; profile capabilities can only narrow runtime authority.")
                })
                put("task_id", buildJsonObject { put("type", "string"); put("description", "Resume a previous child session") })
                put("background", buildJsonObject { put("type", "boolean"); put("description", "Run concurrently and notify parent when done") })
                put("workspace", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("auto"); add("isolated"); add("shared") })
                    put("description", "Workspace policy. BUILD profiles default to isolated; read-only profiles default to shared.")
                })
            }, listOf("description", "prompt", "subagent_type"))
    }

    private fun tool(name: String, desc: String, props: JsonObject, required: List<String> = emptyList()) =
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name); put("description", desc)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", props)
                    put("required", JsonArray(required.map { JsonPrimitive(it) }))
                })
            })
        }

     
    internal fun parallelReadAccess(tool: String, args: JsonObject): AgentToolReadAccess? {
        val realName = when (tool) {
            "read" -> "read_file"
            "grep" -> "search_files"
            else -> tool
        }
        return when (realName) {
            "list_files" -> AgentToolReadAccess("read", args["path"]?.jsonPrimitive?.contentOrNull ?: "")
            "read_file" -> AgentToolReadAccess("read", args["path"]?.jsonPrimitive?.contentOrNull ?: return null)
            "glob" -> AgentToolReadAccess("glob", args["pattern"]?.jsonPrimitive?.contentOrNull ?: return null)
            "search_files" -> AgentToolReadAccess("grep", args["query"]?.jsonPrimitive?.contentOrNull ?: return null)
            else -> null
        }
    }

     
    fun actionOf(tool: String): String = AgentToolPermissionPolicy.actionOf(tool)

    class Ctx(
        val files: FileRepository,
        val terminal: ITerminalSession,
        val git: GitManager,
        val approvals: ApprovalManager,
        val questions: QuestionManager,
        val history: EditHistory,
        val perms: PermissionEngine,
        val mode: AgentMode,
        val llm: LlmClient,
        val llmConfig: AgentConfig,
        val workDir: java.io.File,
        val todos: TodoManager,
        val sessionId: String?,
        val lsp: LspManager,
        val backgroundCommands: BackgroundCommandManager? = null,
        val subagents: SubagentManager? = null,
        val provenance: AgentRequestProvenance = AgentRequestProvenance.primary(),
        val pluginSource: AgentPluginSource = AgentPluginSource.EMPTY,
        val documentAuthority: WorkspaceDocumentAuthority? = null,
        val activeToolNames: Set<String> = emptySet(),
        val ideActions: AgentIdeActions? = null,
        val lspEditActions: AgentLspEditActions? = null,
        val toolchainActions: AgentToolchainActions? = null,
        val capabilityActions: AgentCapabilityActions? = null,
        val browserController: AgentBrowserController? = null,
        val mcpProcessHost: StdioProcessHost? = null,
        val onProgress: (AgentToolProgress) -> Unit = {},
    ) {
        val permissionScope: String get() = AgentPermissionScope.id(mode, provenance)
    }

    suspend fun execute(name: String, args: JsonObject, c: Ctx): String = try {
        
        val realName = when (name) {
            "bash" -> "run_command"
            "edit" -> "edit_file"
            "write" -> "write_file"
            "read" -> "read_file"
            "grep" -> "search_files"
            else -> name
        }
        
        if (!c.perms.toolAllowed(realName)) return "DENIED (agent profile): tool '$realName' is outside this Agent profile capability set."
        if (McpToolIdentity.isFirstClassName(realName)) {
            AgentModePolicy.check(c.mode, realName)?.let { return it }
            val registry = McpToolRegistry(c.workDir, c.pluginSource, c.mcpProcessHost)
            val descriptor = registry.resolve(realName, c.perms, c.permissionScope)
                ?: return "ERROR: MCP tool definition is stale or unavailable; refresh the Agent tool catalog."
            val manager = McpManager(c.workDir, c.pluginSource, c.mcpProcessHost)
            val resource = manager.permissionResource(descriptor.serverName, descriptor.toolName)
            require(resource.startsWith("${descriptor.serverName}/${descriptor.toolName}@cfg:")) {
                "MCP permission identity is invalid for advertised tool."
            }
            require(descriptor.runtimeIdentity == manager.permissionResource(descriptor.serverName)) {
                "MCP server configuration changed after tool advertisement. Refresh the Agent tool catalog."
            }
            gate("mcp", resource, c)?.let { return it }
            require(descriptor.runtimeIdentity == manager.permissionResource(descriptor.serverName)) {
                "MCP server configuration changed after approval. Review the current configuration before running it."
            }
            return manager.callTool(descriptor.serverName, descriptor.toolName, args).take(8_000)
        }
        AgentModePolicy.check(c.mode, realName)?.let { return it }
        if (realName.startsWith("plugin_")) {
            val manager = AgentPluginToolManager(c.workDir, c.pluginSource)
            val def = manager.discover().firstOrNull { it.functionName == realName } ?: return "ERROR: Agent plugin tool not found: $realName"
            val resource = manager.permissionResource(def, args)
            gate("shell_escape", resource, c)?.let { return it }
            gate("shell", resource, c)?.let { return it }
            return manager.execute(def, args, c.terminal)
        }
        if (realName.startsWith("custom_")) {
            val real = realName.removePrefix("custom_")
            val custom = CustomToolManager(c.workDir)
            val fingerprint = custom.permissionFingerprint(real) ?: return "ERROR: custom tool not found: $real"
            

            gate("shell_escape", ShellContainmentPreflight.customToolEscapeResource(real, fingerprint), c)?.let { return it }
            gate("shell", "custom:$real@sha:${fingerprint.take(20)} ${args.toString().take(120)}", c)?.let { return it }
            return custom.execute(
                real,
                args["input"]?.jsonPrimitive?.contentOrNull ?: args.toString(),
                terminal = c.terminal,
                expectedFingerprint = fingerprint,
            )
        }

        AgentToolPermissionPolicy.runtimeRequirements(realName, args, c.sessionId).forEach { requirement ->
            gate(requirement.action, requirement.resource, c)?.let { return it }
        }
        when (realName) {
            "list_files" -> {
                gate("read", args["path"]?.jsonPrimitive?.contentOrNull ?: "", c)
                    ?: c.files.listFiles(args["path"]?.jsonPrimitive?.contentOrNull ?: "")
                        .joinToString("\n") { (if (it.isDir) "DIR  " else "FILE ") + it.path }
                        .ifEmpty { "(kosong)" }
            }
            "read_file" -> {
                val p = req(args, "path")
                gate("read", p, c) ?: run {
                    val s = args["start"]?.jsonPrimitive?.intOrNull
                    val e = args["end"]?.jsonPrimitive?.intOrNull
                    AgentWorkspaceReader(c.files, c.documentAuthority).read(p, s, e)
                }
            }
            "glob" -> {
                val pat = req(args, "pattern")
                gate("glob", pat, c) ?: c.files.glob(pat)
            }
            "search_files" -> {
                val q = req(args, "query")
                gate("grep", q, c) ?: AgentWorkspaceSearch(c.files, c.documentAuthority)
                    .search(q).take(8_000).ifBlank { "(tidak ada hasil)" }
            }
            "write_file" -> {
                val requestedPath = req(args, "path")
                val content = args["content"]?.jsonPrimitive?.contentOrNull ?: ""
                require(content.length <= 2_000_000) { "write_file content exceeds 2,000,000 characters" }
                val mutation = AgentWorkspaceMutation(c.files, c.documentAuthority)
                when (val target = mutation.inspect(requestedPath)) {
                    is AgentWorkspaceMutation.Target.Live -> {
                        val expected = mutation.expectedVersion(args) ?: return mutation.versionRequired(target)
                        if (target.snapshot.version != expected) return mutation.versionConflict(target.path, target.snapshot)
                        gateWithPreview("edit", target.path, "write_file", "Write ${target.path}", DiffUtil.unified(target.content, content), c)?.let { return it }
                        val undo = c.history.prepareLive(target.path, target.content)
                        mutation.finalizeLive(
                            c.history, c.lsp, undo,
                            { mutation.replaceLive(target, expected, content, "Agent write — review before saving") },
                            target.path, "write",
                        ) { result ->
                            "OK: updated live editor buffer ${target.path}; disk unchanged; revision=${result.after.revision} change_version=${result.after.changeVersion}"
                        }
                    }
                    is AgentWorkspaceMutation.Target.Disk -> {
                        gateWithPreview("edit", target.path, "write_file", "Write ${target.path}", DiffUtil.unified(target.content, content), c)?.let { return it }
                        val afterReview = if (c.files.exists(target.path)) c.files.readTextForEdit(target.path) else null
                        val expectedDisk = if (target.existed) target.content else null
                        require(afterReview == expectedDisk) { "File changed after review: ${target.path}. Re-run write_file so the current state can be reviewed." }
                        c.history.snapshot(target.path, expectedDisk)
                        try {
                            c.files.writeText(target.path, content)
                        } catch (t: Throwable) {
                            c.history.discardLast()
                            throw t
                        }
                        "OK: wrote ${target.path}"
                    }
                }
            }
            "edit_file" -> {
                val requestedPath = req(args, "path")
                val search = req(args, "search")
                val replace = req(args, "replace")
                require(search.isNotEmpty()) { "search must not be empty" }
                val mutation = AgentWorkspaceMutation(c.files, c.documentAuthority)
                when (val target = mutation.inspect(requestedPath)) {
                    is AgentWorkspaceMutation.Target.Live -> {
                        val expected = mutation.expectedVersion(args) ?: return mutation.versionRequired(target)
                        if (target.snapshot.version != expected) return mutation.versionConflict(target.path, target.snapshot)
                        val occurrences = countOccurrences(target.content, search)
                        if (occurrences != 1) return "ERROR: search must match exactly once in ${target.path} (found $occurrences)"
                        val updated = target.content.replaceFirst(search, replace)
                        gateWithPreview("edit", target.path, "edit_file", "Edit ${target.path}", DiffUtil.unified(target.content, updated), c)?.let { return it }
                        val undo = c.history.prepareLive(target.path, target.content)
                        mutation.finalizeLive(
                            c.history, c.lsp, undo,
                            { mutation.replaceLive(target, expected, updated, "Agent edit — review before saving") },
                            target.path, "edit",
                        ) { result ->
                            "OK: 1 replacement(s) in live editor buffer ${target.path}; disk unchanged; revision=${result.after.revision} change_version=${result.after.changeVersion}"
                        }
                    }
                    is AgentWorkspaceMutation.Target.Disk -> {
                        val occurrences = countOccurrences(target.content, search)
                        if (occurrences != 1) return "ERROR: search must match exactly once in ${target.path} (found $occurrences)"
                        val updated = target.content.replaceFirst(search, replace)
                        gateWithPreview("edit", target.path, "edit_file", "Edit ${target.path}", DiffUtil.unified(target.content, updated), c)?.let { return it }
                        val afterReview = c.files.readTextForEdit(target.path)
                        require(afterReview == target.content) { "File changed after review: ${target.path}. Re-run edit_file so the current state can be reviewed." }
                        c.history.snapshot(target.path, target.content)
                        val n = try {
                            c.files.applySearchReplace(target.path, search, replace)
                        } catch (t: Throwable) {
                            c.history.discardLast()
                            throw t
                        }
                        "OK: $n replacement(s) in ${target.path}"
                    }
                }
            }
            "apply_patch" -> {
                val patch = req(args, "patchText")
                val ops = PatchTool.parse(patch)
                val reviewed = PatchTool.reviewWorkspaceSnapshot(ops, c.files, c.documentAuthority)
                val preview = PatchTool.previewWorkspaceReviewed(ops, reviewed)
                val resource = ops.joinToString(",") { op -> when (op) {
                    is PatchTool.Op.Add -> op.path
                    is PatchTool.Op.Update -> op.path
                    is PatchTool.Op.Delete -> op.path
                } }.take(500)
                gateWithPreview("edit", resource, "write_file", "Terapkan patch (${ops.size} operasi)", preview, c)?.let { return it }
                PatchTool.applyWorkspaceReviewed(ops, c.files, c.history, c.documentAuthority, c.lsp, reviewed)
            }
            "run_command" -> {
                val cmd = req(args, "command")
                

                shellContainmentGate(cmd, c)?.let { return it }
                gate("shell", cmd, c)?.let { return it }
                val background = args["background"]?.jsonPrimitive?.booleanOrNull == true
                val requestedTimeout = args["timeout_ms"]?.jsonPrimitive?.longOrNull
                if (requestedTimeout != null) {
                    require(requestedTimeout in BackgroundCommandManager.MIN_TIMEOUT_MS..BackgroundCommandManager.MAX_TIMEOUT_MS) {
                        "timeout_ms must be between ${BackgroundCommandManager.MIN_TIMEOUT_MS} and ${BackgroundCommandManager.MAX_TIMEOUT_MS}"
                    }
                }
                if (background) {
                    val diskState = AgentDiskExecutionState.capture(c.documentAuthority)
                    val manager = c.backgroundCommands ?: return "ERROR: background command manager unavailable"
                    val job = manager.start(cmd, requestedTimeout, diskState)
                    diskState.annotate(
                        "run_command(background)",
                        "BACKGROUND_JOB_STARTED id=${job.id} state=${job.state.name.lowercase()}" +
                            (job.timeoutMs?.let { " timeout_ms=$it" } ?: " timeout=none") +
                            "\nContinue independent work; use background_job(action=\"status\"|\"output\"|\"kill\", job_id=\"${job.id}\") when needed.",
                    )
                } else {
                    c.onProgress(AgentToolProgress(detail = "Running command"))
                    c.diskBacked("run_command", reconcileAfter = true) {
                        val r = c.terminal.execStreaming(cmd, requestedTimeout ?: 30_000L) { chunk ->
                            c.onProgress(AgentToolProgress(outputDelta = chunk, detail = "Streaming command output"))
                        }
                        "exit=${r.exitCode}\n${r.output}"
                    }
                }
            }
            "environment_probe" -> AgentEnvironmentProbe.run(args, c)
            "ide" -> {
                val request = AgentIdeRequest.parse(args)
                val actions = c.ideActions ?: return "ERROR: IDE action manager unavailable in this Agent context"
                gate("ide_execute", request.permissionResource, c)?.let { return it }
                c.onProgress(AgentToolProgress(detail = "IDE ${request.operation.replace('_', ' ')}"))
                if (request.usesDisk) c.diskBacked("ide:${request.operation}", reconcileAfter = request.reconcileAfter) {
                    actions.execute(request)
                } else actions.execute(request)
            }
            "undo_agent_edit" -> {
                gate("edit", "agent-history:last", c)?.let { return it }
                if (c.history.depth() <= 0) return "ERROR: no tracked Agent edit is available to undo"
                val result = c.history.undo()
                if (result.startsWith("Undo failed", ignoreCase = true)) "ERROR: $result" else result
            }
            "background_job" -> {
                val manager = c.backgroundCommands ?: return "ERROR: background command manager unavailable"
                val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "list"
                when (action) {
                    "list" -> manager.list().joinToString("\n") { it.agentSummary() }.ifEmpty { "No background jobs." } + c.documentAuthority?.reconcilePersistedWorkspace().agentReconciliationNotice("background_job:list")
                    "status" -> {
                        val id = req(args, "job_id")
                        val job = manager.snapshot(id) ?: return "ERROR: background job not found: $id"
                        job.agentSummary(includeDiskNotice = true) + c.documentAuthority?.reconcilePersistedWorkspace().agentReconciliationNotice("background_job:$id:status")
                    }
                    "output" -> {
                        val id = req(args, "job_id")
                        val job = manager.snapshot(id) ?: return "ERROR: background job not found: $id"
                        val reconciliation = c.documentAuthority?.reconcilePersistedWorkspace()
                        job.agentSummary(includeDiskNotice = true) + "\n" + job.output.ifBlank { "(no output yet)" } +
                            reconciliation.agentReconciliationNotice("background_job:$id:output")
                    }
                    "kill" -> {
                        if (c.mode != AgentMode.BUILD) return "DENIED (${c.mode} mode): background process termination requires BUILD mode."
                        val id = req(args, "job_id")
                        val existing = manager.snapshot(id) ?: return "ERROR: background job not found: $id"
                        gate("shell", "background-job:$id:kill:${existing.command.take(200)}", c)?.let { return it }
                        val killed = manager.kill(id) ?: return "ERROR: background job not found: $id"
                        killed.agentSummary() + c.documentAuthority?.reconcilePersistedWorkspace().agentReconciliationNotice("background_job:$id:kill")
                    }
                    else -> "ERROR: invalid background_job action: $action"
                }
            }
            "git_status" -> c.diskBacked("git_status") { c.git.status() }
            "git_branch" -> c.diskBacked("git_branch", reconcileAfter = true) {
                val a = args["action"]?.jsonPrimitive?.contentOrNull ?: "list"
                val name = args["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val permissionResource = when (a) { "checkout" -> "git checkout $name"; "create" -> "git checkout -b $name"; else -> "git branch" }
                gate("shell", permissionResource, c)?.let { return@diskBacked it }
                when (a) { "checkout" -> c.git.checkout(name); "create" -> c.git.checkout(name, create = true); else -> c.git.branches() }
            }
            "git_log" -> c.diskBacked("git_log") { c.git.log(args["max"]?.jsonPrimitive?.intOrNull ?: 20) }
            "git_diff" -> c.diskBacked("git_diff") { c.git.diff().take(12_000) }
            "git_commit" -> c.diskBacked("git_commit", reconcileAfter = true) {
                val message = req(args, "message").take(500)
                gate("shell", "git commit -m <message>", c)?.let { return@diskBacked it }
                c.git.commitAll(message)
            }
            "git_pull" -> c.diskBacked("git_pull", reconcileAfter = true) {
                gate("shell", "git pull", c)?.let { return@diskBacked it }
                c.git.pull()
            }
            "git_push" -> c.diskBacked("git_push", reconcileAfter = true) {
                gate("shell", "git push", c)?.let { return@diskBacked it }
                c.git.push()
            }
            "skill" -> {
                val n = req(args, "name")
                gate("skill", n, c)?.let { return it }
                val sk = SkillManager(c.workDir, c.pluginSource).load(n) ?: return "ERROR: skill tidak ada: $n"
                "# ${sk.name}: ${sk.description}\nSource: ${sk.sourceLabel}\n\n${sk.body.take(10_000)}"
            }
            "rule" -> {
                val n = req(args, "name")
                gate("skill", "rule:$n", c)?.let { return it }
                val rule = AgentRuleManager(c.workDir, c.pluginSource).load(n) ?: return "ERROR: rule tidak ada: $n"
                "# Rule ${rule.name}: ${rule.description}\nSource: ${rule.sourceLabel}\nActivation: ${rule.activation.name.lowercase()}\n\n${rule.body.take(10_000)}"
            }
            "worktree" -> {
                val a = args["action"]?.jsonPrimitive?.contentOrNull ?: "list"
                val n = args["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val wm = WorktreeManager(c.workDir)
                gate("shell", if (a == "create") "worktree create $n" else "worktree list", c)?.let { return it }
                if (a == "create") wm.create(n) else wm.list().joinToString("\n").ifEmpty { "(belum ada worktree)" }
            }
            "task" -> {
                val manager = c.subagents ?: return "DENIED: subagent depth limit reached or subagents unavailable in this context."
                val parentSessionId = c.sessionId ?: return "ERROR: task requires an active parent session"
                val description = req(args, "description").take(160)
                val prompt = req(args, "prompt")
                val type = args["subagent_type"]?.jsonPrimitive?.contentOrNull ?: "general"
                if (c.mode != AgentMode.BUILD && manager.profileMode(type) == AgentMode.BUILD) {
                    return "DENIED (${c.mode} mode): mutating BUILD subagents require BUILD mode; choose a read-only profile."
                }
                gate("subagent", "$type:${description.take(120)}", c)?.let { return it }
                manager.run(
                    parentSessionId = parentSessionId,
                    description = description,
                    prompt = prompt,
                    profileName = type,
                    taskId = args["task_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    background = args["background"]?.jsonPrimitive?.booleanOrNull ?: false,
                    workspace = args["workspace"]?.jsonPrimitive?.contentOrNull,
                    config = c.llmConfig,
                    onProgress = c.onProgress,
                ).take(20_000)
            }
            "task_control" -> {
                val manager = c.subagents ?: return "DENIED: subagents unavailable in this context."
                val parentSessionId = c.sessionId ?: return "ERROR: task_control requires an active parent session"
                val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "list"
                val taskId = args["task_id"]?.jsonPrimitive?.contentOrNull
                val message = args["message"]?.jsonPrimitive?.contentOrNull
                gate("subagent", "$action:${taskId ?: "*"}", c)?.let { return it }
                manager.control(parentSessionId, action, taskId, message).take(20_000)
            }
            "explore_codebase" -> {
                val q = req(args, "query")
                val manager = c.subagents ?: return "DENIED: subagent depth limit reached or subagents unavailable in this context."
                val parentSessionId = c.sessionId ?: return "ERROR: explore_codebase requires an active parent session"
                gate("subagent", "explore:${q.take(100)}", c)?.let { return it }
                manager.run(
                    parentSessionId = parentSessionId,
                    description = q.take(80).ifBlank { "Explore codebase" },
                    prompt = q,
                    profileName = "explore",
                    taskId = null,
                    background = false,
                    config = c.llmConfig,
                    onProgress = c.onProgress,
                ).take(20_000)
            }
            "diagnose" -> {
                val p = req(args, "path")
                gate("diagnose", p, c)?.let { return it }
                c.lsp.diagnose(p).take(6_000)
            }
            "lsp" -> {
                gate("lsp", args["path"]?.jsonPrimitive?.contentOrNull ?: "*", c)?.let { return it }
                val op = args["operation"]?.jsonPrimitive?.contentOrNull ?: "hover"
                val p = req(args, "path")
                c.lsp.lsp(op, p,
                    args["line"]?.jsonPrimitive?.intOrNull ?: 1,
                    args["col"]?.jsonPrimitive?.intOrNull ?: 1).take(6_000)
            }
            "lsp_edit" -> {
                val actions = c.lspEditActions ?: return "ERROR: LSP edit authority unavailable"
                val op = args["operation"]?.jsonPrimitive?.contentOrNull ?: "list_actions"
                val path = req(args, "path")
                val line = args["line"]?.jsonPrimitive?.intOrNull ?: 1
                val col = args["col"]?.jsonPrimitive?.intOrNull ?: 1
                when (op) {
                    "list_actions" -> {
                        gate("lsp", path, c)?.let { return it }
                        actions.listActions(path, line, col)
                    }
                    "apply_action" -> {
                        if (c.mode != AgentMode.BUILD) return "DENIED (${c.mode} mode): applying LSP edits requires BUILD mode."
                        gate("lsp", path, c)?.let { return it }
                        val prepared = actions.prepareCodeAction(path, line, col, req(args, "title"))
                        gateWithPreview("lsp_edit", prepared.permissionResource, "lsp_edit", prepared.title, prepared.preview, c)?.let { return it }
                        actions.apply(prepared)
                    }
                    "format" -> {
                        if (c.mode != AgentMode.BUILD) return "DENIED (${c.mode} mode): LSP formatting mutation requires BUILD mode."
                        gate("lsp", path, c)?.let { return it }
                        val prepared = actions.prepareFormat(path)
                        gateWithPreview("lsp_edit", prepared.permissionResource, "lsp_edit", prepared.title, prepared.preview, c)?.let { return it }
                        actions.apply(prepared)
                    }
                    else -> "ERROR: unsupported lsp_edit operation: $op"
                }
            }
            "toolchain" -> {
                val actions = c.toolchainActions ?: return "ERROR: managed toolchain authority unavailable"
                val op = args["operation"]?.jsonPrimitive?.contentOrNull ?: "list"
                val family = args["family"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val version = args["version"]?.jsonPrimitive?.contentOrNull.orEmpty()
                when (op) {
                    "list" -> actions.list(args["query"]?.jsonPrimitive?.contentOrNull)
                    "status" -> actions.status(family, version.takeIf { it.isNotBlank() })
                    "plan_install" -> {
                        val prepared = actions.prepareInstall(family, version)
                        if (prepared.needsLicenseAcceptance) prepared.preview + "\nNEEDS_USER_ACTION: exact Android SDK license must be reviewed/accepted by the user in Extensions."
                        else prepared.preview
                    }
                    "install" -> {
                        if (c.mode != AgentMode.BUILD) return "DENIED (${c.mode} mode): managed installation requires BUILD mode."
                        val prepared = actions.prepareInstall(family, version)
                        if (prepared.needsLicenseAcceptance) return actions.install(prepared)
                        gateWithPreview("toolchain_install", prepared.permissionResource, "toolchain_install", "Install ${prepared.item.family.name} ${prepared.item.version.version}", prepared.preview, c)?.let { return it }
                        actions.install(prepared)
                    }
                    "repair" -> {
                        if (c.mode != AgentMode.BUILD) return "DENIED (${c.mode} mode): managed repair requires BUILD mode."
                        val (item, preview) = actions.repairPreview(family, version)
                        val resource = "repair:${item.family.id}@${item.version.version}"
                        gateWithPreview("toolchain_install", resource, "toolchain_install", "Repair ${item.family.name} ${item.version.version}", preview, c)?.let { return it }
                        actions.repair(item)
                    }
                    "use_workspace" -> {
                        if (c.mode != AgentMode.BUILD) return "DENIED (${c.mode} mode): workspace toolchain selection requires BUILD mode."
                        val resource = "workspace-select:${family}@${version}"
                        gate("toolchain_install", resource, c)?.let { return it }
                        actions.activateWorkspace(family, version)
                    }
                    else -> "ERROR: unsupported toolchain operation: $op"
                }
            }
            "capability" -> {
                val actions = c.capabilityActions ?: return "ERROR: universal capability authority unavailable"
                when (val op = args["operation"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: "list") {
                    "list" -> actions.list(args["kind"]?.jsonPrimitive?.contentOrNull)
                    "status" -> actions.status(req(args, "tool_id"))
                    "run" -> {
                        if (c.mode != AgentMode.BUILD) return "DENIED (${c.mode} mode): contributed capability execution requires BUILD mode."
                        val prepared = actions.prepareRun(
                            req(args, "tool_id"),
                            args["path"]?.jsonPrimitive?.contentOrNull,
                        )
                        val resource = actions.permissionResource(prepared)
                        gateWithPreview(
                            "shell", resource, "capability_tool",
                            "Run ${prepared.title}", prepared.preview, c,
                        )?.let { return it }
                        actions.run(prepared)
                    }
                    else -> "ERROR: unsupported capability operation: $op"
                }
            }
            "todowrite" -> {
                
                val arr = args["todos"]?.jsonArray
                if (arr == null) return c.todos.snapshot(c.sessionId)
                val list = arr.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    Todo(
                        content = o["content"]?.jsonPrimitive?.contentOrNull ?: "",
                        status = o["status"]?.jsonPrimitive?.contentOrNull ?: "pending",
                        priority = o["priority"]?.jsonPrimitive?.contentOrNull ?: "medium",
                    )
                }
                c.todos.set(list, c.sessionId)
                "OK: ${list.size} todos"
            }
            "webfetch" -> {
                val url = req(args, "url")
                gate("webfetch", url, c)?.let { return it }
                fetchUrl(url).take(8_000)
            }
            "websearch" -> {
                val q = req(args, "query")
                gate("websearch", q, c)?.let { return it }
                
                fetchUrl("https://html.duckduckgo.com/html/?q=${java.net.URLEncoder.encode(q, "UTF-8")}").take(8_000)
            }
            "mcp" -> McpAgentOperations.execute(
                args, c,
                gate = { action, resource -> gate(action, resource, c) },
                gatePreview = { action, resource, kind, summary, detail -> gateWithPreview(action, resource, kind, summary, detail, c) },
            )
            "question" -> {
                gate("question", "*", c)?.let { return it }
                val header = args["header"]?.jsonPrimitive?.contentOrNull ?: "Choose"
                val q = args["question"]?.jsonPrimitive?.contentOrNull ?: req(args, "question")
                val opts = args["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                c.onProgress(AgentToolProgress(detail = "Waiting for user answer"))
                val ans = c.questions.ask(
                    header,
                    q,
                    opts.ifEmpty { listOf("Yes", "No") },
                    provenance = c.provenance,
                )
                c.onProgress(AgentToolProgress(detail = "User answered"))
                "User answered: $ans"
            }
            "browser" -> {
                val controller = c.browserController ?: return "ERROR: integrated Android browser is unavailable"
                val op = args["operation"]?.jsonPrimitive?.contentOrNull ?: "read"
                val url = args["url"]?.jsonPrimitive?.contentOrNull
                val currentUrl = controller.currentUrl()
                val effectiveUrl = AgentRepairCompletionPolicy.browserPermissionTarget(op, url, currentUrl)
                if (op in setOf("open", "navigate", "read", "status", "back", "reload", "wait", "screenshot")) {
                    gate("webfetch", effectiveUrl.ifBlank { "browser:$op" }, c)?.let { return it }
                }
                if (AgentRepairCompletionPolicy.browserInteracts(op)) {
                    if (c.mode != AgentMode.BUILD) return "DENIED (${c.mode} mode): interactive browser mutation requires BUILD mode."
                    gate("browser_interact", "${effectiveUrl.take(500)}#$op:${args["target"]?.jsonPrimitive?.contentOrNull.orEmpty().take(200)}", c)?.let { return it }
                }
                val loopback = AgentRepairCompletionPolicy.classifyBrowserTarget(effectiveUrl) == AgentRepairCompletionPolicy.BrowserTargetClass.LOOPBACK_HTTP
                if (loopback) gate("browser_local", effectiveUrl.take(800), c)?.let { return it }
                controller.execute(
                    operation = op,
                    url = url,
                    expectedCurrentUrl = effectiveUrl.takeIf { op !in setOf("open", "navigate") },
                    target = args["target"]?.jsonPrimitive?.contentOrNull,
                    text = args["text"]?.jsonPrimitive?.contentOrNull,
                    submit = args["submit"]?.jsonPrimitive?.booleanOrNull ?: false,
                    waitMs = (args["wait_ms"]?.jsonPrimitive?.longOrNull ?: 500L).coerceIn(50L, 10_000L),
                    allowLoopback = loopback,
                ).take(24_000)
            }
            "artifact" -> {
                gate("edit", req(args, "type"), c)?.let { return it }
                val type = req(args, "type")
                val content = req(args, "content")
                val art = ArtifactManager(c.workDir).create(type, content)
                "Created artifact ${art.id} ($type) at ${art.file.absolutePath}"
            }
            else -> "Unknown tool: $name"
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) { "ERROR: ${e.message}" }


    // Permission gate that uses the mutation preview as the single user approval prompt.
    private suspend fun gateWithPreview(
        action: String, resource: String, kind: String, summary: String, detail: String, c: Ctx
    ): String? = when (c.perms.decide(action, resource, c.permissionScope)) {
        PermEffect.ALLOW -> null
        PermEffect.DENY -> "DENIED (policy): $action $resource"
        PermEffect.ASK -> {
            c.onProgress(AgentToolProgress(detail = "Waiting for approval"))
            val dec = c.approvals.requestApproval(
                kind, summary, detail, action = action, resource = resource,
                permissionScope = c.permissionScope, provenance = c.provenance,
            )
            if (dec is ApprovalDecision.Denied) {
                c.onProgress(AgentToolProgress(detail = "Approval denied"))
                "DENIED oleh user — batalkan $action $resource"
            } else if (!ApprovalPolicyGuard.remainsPermitted(c.perms, action, resource, c.permissionScope)) {
                c.onProgress(AgentToolProgress(detail = "Approval revoked by updated policy"))
                "DENIED (policy changed after approval): $action $resource"
            } else {
                c.onProgress(AgentToolProgress(detail = "Approved · continuing"))
                null
            }
        }
    }

    // pre-execution containment permission order.


    private suspend fun shellContainmentGate(command: String, c: Ctx): String? {
        val result = ShellContainmentPreflight.analyze(command, c.workDir, c.workDir)
        result.externalDirectories.forEach { boundary ->
            gate("external_directory", boundary, c)?.let { return it }
        }
        if (result.requiresShellEscape) {
            gate("shell_escape", ShellContainmentPreflight.shellEscapeResource(command), c)?.let { return it }
        }
        return null
    }

    internal suspend fun permissionGateForProbe(action: String, resource: String, c: Ctx): String? = gate(action, resource, c)

    // Permission gate → return String bila diblokir/ditolak (untuk langsung return), else null.
    private suspend fun gate(action: String, resource: String, c: Ctx): String? {
        return when (c.perms.decide(action, resource, c.permissionScope)) {
            PermEffect.ALLOW -> null
            PermEffect.DENY -> "DENIED (policy): $action $resource"
            PermEffect.ASK -> {
                c.onProgress(AgentToolProgress(detail = "Waiting for approval"))
                val dec = c.approvals.requestApproval(
                    if (action == "shell") "run_command" else action,
                    "$action: ${resource.take(120)}", resource.take(2_000),
                    action = action, resource = resource, permissionScope = c.permissionScope,
                    provenance = c.provenance)
                if (dec is ApprovalDecision.Denied) {
                    c.onProgress(AgentToolProgress(detail = "Approval denied"))
                    "DENIED oleh user — batalkan $action $resource"
                } else if (!ApprovalPolicyGuard.remainsPermitted(c.perms, action, resource, c.permissionScope)) {
                    c.onProgress(AgentToolProgress(detail = "Approval revoked by updated policy"))
                    "DENIED (policy changed after approval): $action $resource"
                } else {
                    c.onProgress(AgentToolProgress(detail = "Approved · continuing"))
                    null
                }
            }
        }
    }

    private suspend fun fetchUrl(url: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val r = SafeHttp.get(url, maxBytes = 50_000)
            if (r.code !in 200..299) return@withContext "HTTP ${r.code}: ${r.body.take(500)}"
            val doc = org.jsoup.Jsoup.parse(r.body)
            doc.select("script, style, noscript").remove()
            doc.text().replace(Regex("\\s+"), " ").trim().take(8_000).ifBlank { "(empty page or JavaScript required)" }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) { "ERROR webfetch: ${e.message}" }
    }

    private fun countOccurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val i = text.indexOf(needle, from)
            if (i < 0) return count
            count++
            from = i + needle.length
        }
    }

    private fun req(args: JsonObject, k: String) =
        args[k]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("Missing arg: $k")
}

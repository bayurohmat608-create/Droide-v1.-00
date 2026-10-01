package com.baystudio.droide.core

import kotlinx.serialization.json.*

 
internal object AgentToolCatalog {
    val definitions: JsonArray = buildJsonArray {
        add(tool("list_files", "List files",
            buildJsonObject { put("path", buildJsonObject { put("type", "string") }) }))
        add(tool("read_file", "Read file",
            buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
                put("start", buildJsonObject { put("type", "integer") })
                put("end", buildJsonObject { put("type", "integer") })
            }, listOf("path")))
        add(tool("glob", "Find files",
            buildJsonObject { put("pattern", buildJsonObject { put("type", "string") }) }, listOf("pattern")))
        add(tool("search_files", "Search text",
            buildJsonObject { put("query", buildJsonObject { put("type", "string") }) }, listOf("query")))
        add(tool("write_file", "Write/overwrite file",
            buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
                put("content", buildJsonObject { put("type", "string") })
                put("expected_revision", buildJsonObject { put("type", "integer"); put("description", "Required for a live editor buffer; copy revision from read_file DROIDE_DOCUMENT_STATE.") })
                put("expected_change_version", buildJsonObject { put("type", "integer"); put("description", "Required for a live editor buffer; copy change_version from read_file DROIDE_DOCUMENT_STATE.") })
            }, listOf("path", "content")))
        add(tool("edit_file", "Search-replace in file",
            buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
                put("search", buildJsonObject { put("type", "string") })
                put("replace", buildJsonObject { put("type", "string") })
                put("expected_revision", buildJsonObject { put("type", "integer"); put("description", "Required for a live editor buffer; copy revision from read_file DROIDE_DOCUMENT_STATE.") })
                put("expected_change_version", buildJsonObject { put("type", "integer"); put("description", "Required for a live editor buffer; copy change_version from read_file DROIDE_DOCUMENT_STATE.") })
            }, listOf("path", "search", "replace")))
        add(tool("apply_patch", "Apply patch",
            buildJsonObject { put("patchText", buildJsonObject { put("type", "string") }) }, listOf("patchText")))
        add(tool("run_command", "Run shell command in foreground or as a non-blocking background job",
            buildJsonObject {
                put("command", buildJsonObject { put("type", "string") })
                put("background", buildJsonObject { put("type", "boolean") })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Foreground defaults to 30000ms. Background has no timeout unless supplied.")
                })
            }, listOf("command")))
        add(tool("background_job", "Inspect or stop background shell jobs",
            buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("list"); add("status"); add("output"); add("kill") })
                })
                put("job_id", buildJsonObject { put("type", "string") })
            }, listOf("action")))
        add(tool("environment_probe", "Prove which Agent tools are advertised and which executable names are present in the current execution environment. Presence is not execution success.",
            buildJsonObject {
                put("agent_tools", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("commands", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("include_path", buildJsonObject { put("type", "boolean") })
            }))
        add(tool("ide", "Run IDE-owned project verification through Droide's Build/Run/Test coordinator instead of guessing shell commands",
            buildJsonObject {
                put("operation", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("status"); add("build_debug"); add("test"); add("lint"); add("run_file"); add("cancel") })
                })
                put("path", buildJsonObject { put("type", "string"); put("description", "Required only for run_file") })
            }, listOf("operation")))
        add(tool("undo_agent_edit", "Undo the most recent tracked Agent edit/patch transaction", buildJsonObject {}))
        add(tool("git_status", "Show git status", buildJsonObject {}))
        add(tool("git_branch", "Manage branches",
            buildJsonObject {
                put("action", buildJsonObject { put("type", "string") })
                put("name", buildJsonObject { put("type", "string") })
            }, listOf("action")))
        add(tool("git_log", "Show commit log",
            buildJsonObject { put("max", buildJsonObject { put("type", "integer") }) }))
        add(tool("git_diff", "Show diff", buildJsonObject {}))
        add(tool("git_commit", "Commit current project changes",
            buildJsonObject { put("message", buildJsonObject { put("type", "string") }) }, listOf("message")))
        add(tool("git_pull", "Pull from remote", buildJsonObject {}))
        add(tool("git_push", "Push to remote", buildJsonObject {}))
        add(tool("skill", "Load skill",
            buildJsonObject { put("name", buildJsonObject { put("type", "string") }) }, listOf("name")))
        add(tool("rule", "Load an on-demand Agent rule",
            buildJsonObject { put("name", buildJsonObject { put("type", "string") }) }, listOf("name")))
        add(tool("worktree", "Manage worktrees",
            buildJsonObject {
                put("action", buildJsonObject { put("type", "string") })
                put("name", buildJsonObject { put("type", "string") })
            }, listOf("action")))
        add(tool("task", "Delegate work to a fresh or resumable child-agent session",
            buildJsonObject {
                put("description", buildJsonObject { put("type", "string"); put("description", "Short 3-8 word task label") })
                put("prompt", buildJsonObject { put("type", "string"); put("description", "Detailed autonomous instructions and expected result") })
                put("subagent_type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("general"); add("explore"); add("reviewer") })
                })
                put("task_id", buildJsonObject { put("type", "string"); put("description", "Resume a previous child session") })
                put("background", buildJsonObject { put("type", "boolean"); put("description", "Run concurrently and notify parent when done") })
                put("workspace", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("auto"); add("isolated"); add("shared") })
                    put("description", "Workspace policy. general defaults to isolated; read-only children default to shared.")
                })
            }, listOf("description", "prompt", "subagent_type")))
        add(tool("task_control", "Inspect, steer, merge, discard, or cancel child-agent tasks",
            buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("list"); add("status"); add("review"); add("transcript"); add("message"); add("cancel"); add("merge"); add("discard") })
                })
                put("task_id", buildJsonObject { put("type", "string") })
                put("message", buildJsonObject { put("type", "string"); put("description", "Correction or additional context for an actively running background child") })
            }, listOf("action")))
        add(tool("explore_codebase", "Explore codebase using the read-only child-agent",
            buildJsonObject { put("query", buildJsonObject { put("type", "string") }) }, listOf("query")))
        add(tool("diagnose", "Check file errors",
            buildJsonObject { put("path", buildJsonObject { put("type", "string") }) }, listOf("path")))
        add(tool("lsp", "LSP operations",
            buildJsonObject {
                put("operation", buildJsonObject { put("type", "string") })
                put("path", buildJsonObject { put("type", "string") })
                put("line", buildJsonObject { put("type", "integer") })
                put("col", buildJsonObject { put("type", "integer") })
            }, listOf("operation", "path")))
        add(tool("lsp_edit", "Use transparent LSP code actions or formatting through preview, approval, CAS and rollback",
            buildJsonObject {
                put("operation", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("list_actions"); add("apply_action"); add("format") })
                })
                put("path", buildJsonObject { put("type", "string") })
                put("line", buildJsonObject { put("type", "integer") })
                put("col", buildJsonObject { put("type", "integer") })
                put("title", buildJsonObject { put("type", "string"); put("description", "Exact code-action title returned by list_actions") })
            }, listOf("operation", "path")))
        add(tool("toolchain", "Inspect, install, repair or select Droide-managed runtimes/toolchains without bypassing catalog certification",
            buildJsonObject {
                put("operation", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("list"); add("status"); add("plan_install"); add("install"); add("repair"); add("use_workspace") })
                })
                put("family", buildJsonObject { put("type", "string") })
                put("version", buildJsonObject { put("type", "string") })
                put("query", buildJsonObject { put("type", "string") })
            }, listOf("operation")))
        add(tool("capability", "Inspect or execute enabled extension-contributed RUN/FORMATTER/LINTER/BUILD/TEST/CLI capabilities through Droide's universal capability registry",
            buildJsonObject {
                put("operation", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("list"); add("status"); add("run") })
                })
                put("tool_id", buildJsonObject { put("type", "string"); put("description", "Qualified capability id preferred (extension:<id>:<tool>)") })
                put("path", buildJsonObject { put("type", "string"); put("description", "Optional workspace-relative file for language-scoped capabilities") })
                put("kind", buildJsonObject { put("type", "string"); put("description", "Optional list filter: run, formatter, linter, build, test, cli") })
            }, listOf("operation")))
        add(tool("todowrite", "Manage todos",
            buildJsonObject { put("todos", buildJsonObject { put("type", "array") }) }))
        add(tool("webfetch", "Fetch URL",
            buildJsonObject { put("url", buildJsonObject { put("type", "string") }) }, listOf("url")))
        add(tool("websearch", "Search web",
            buildJsonObject { put("query", buildJsonObject { put("type", "string") }) }, listOf("query")))
        add(tool("mcp", "Discover or call tools on a configured MCP server",
            buildJsonObject {
                put("server", buildJsonObject { put("type", "string") })
                put("operation", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("status"); add("diagnose"); add("list_tools"); add("call_tool")
                        add("retry"); add("refresh_catalog"); add("verify"); add("repair_config"); add("rollback")
                    })
                })
                put("tool", buildJsonObject { put("type", "string") })
                put("arguments", buildJsonObject { put("type", "object") })
                put("patch", buildJsonObject {
                    put("type", "object")
                    put("description", "Workspace MCP config repair patch. Only command, args and protocol are accepted.")
                })
                put("repair_id", buildJsonObject { put("type", "string"); put("description", "Rollback id returned by a prior repair_config operation") })
                put("input", buildJsonObject { put("type", "string"); put("description", "Legacy JSON bridge: {\"tool\":\"name\",\"arguments\":{...}}") })
            }, listOf("server")))
        add(tool("question", "Ask user",
            buildJsonObject {
                put("header", buildJsonObject { put("type", "string") })
                put("question", buildJsonObject { put("type", "string") })
                put("options", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
            }, listOf("header", "question")))
        add(tool("browser", "Use Droide's integrated Android browser to open/read/interact with/verify a web page on-device",
            buildJsonObject {
                put("operation", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("status"); add("open"); add("navigate"); add("read"); add("click"); add("type"); add("back"); add("reload"); add("wait"); add("screenshot") })
                })
                put("url", buildJsonObject { put("type", "string") })
                put("target", buildJsonObject { put("type", "string"); put("description", "data-droide-agent-id from read output, or a CSS selector") })
                put("text", buildJsonObject { put("type", "string") })
                put("submit", buildJsonObject { put("type", "boolean") })
                put("wait_ms", buildJsonObject { put("type", "integer") })
            }, listOf("operation")))
        add(tool("artifact", "Create artifact",
            buildJsonObject {
                put("type", buildJsonObject { put("type", "string") })
                put("content", buildJsonObject { put("type", "string") })
            }, listOf("type", "content")))
        
        add(tool("bash", "Run shell command in foreground or as a non-blocking background job",
            buildJsonObject {
                put("command", buildJsonObject { put("type", "string") })
                put("background", buildJsonObject { put("type", "boolean") })
                put("timeout_ms", buildJsonObject { put("type", "integer") })
            }, listOf("command")))
        add(tool("edit", "Edit file",
            buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
                put("search", buildJsonObject { put("type", "string") })
                put("replace", buildJsonObject { put("type", "string") })
                put("expected_revision", buildJsonObject { put("type", "integer") })
                put("expected_change_version", buildJsonObject { put("type", "integer") })
            }, listOf("path", "search", "replace")))
        add(tool("write", "Write file",
            buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
                put("content", buildJsonObject { put("type", "string") })
                put("expected_revision", buildJsonObject { put("type", "integer") })
                put("expected_change_version", buildJsonObject { put("type", "integer") })
            }, listOf("path", "content")))
        add(tool("read", "Read file",
            buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
                put("start", buildJsonObject { put("type", "integer") })
                put("end", buildJsonObject { put("type", "integer") })
            }, listOf("path")))
        add(tool("grep", "Search text",
            buildJsonObject { put("query", buildJsonObject { put("type", "string") }) }, listOf("query")))
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
}

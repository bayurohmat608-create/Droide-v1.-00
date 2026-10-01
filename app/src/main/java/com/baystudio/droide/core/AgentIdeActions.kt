package com.baystudio.droide.core

import kotlinx.serialization.json.*

class AgentIdeActions(
    private val coordinator: BuildRunDebugCoordinator,
    private val debugger: DebugManager,
) {
    suspend fun execute(operation: String, path: String? = null): String = execute(AgentIdeRequest.parse(buildJsonObject {
        put("operation", operation)
        path?.let { put("path", it) }
    }))

    internal suspend fun execute(request: AgentIdeRequest): String {
        val op = request.operation
        return when (op) {
            "status" -> AgentIdeResult.succeeded(op, status())
            "build_debug" -> renderBuild(op, coordinator.buildDebug())
            "build_release_apk" -> renderBuild(op, coordinator.buildReleaseApk())
            "build_release_bundle" -> renderBuild(op, coordinator.buildReleaseBundle())
            "gradle_task" -> renderBuild(op, coordinator.gradleTask(requireNotNull(request.task)))
            "test" -> renderBuild(op, coordinator.test())
            "lint" -> renderBuild(op, coordinator.lint())
            "run_file" -> AgentIdeResult.runFile(coordinator.runFileResult(requireNotNull(request.path)))
            "install_run" -> {
                val result = coordinator.installAndRun(buildTask = request.task ?: "assembleDebug")
                if (result.build?.success != true) renderBuild(op, requireNotNull(result.build))
                else {
                    check(result.apk != null && !result.launchMessage.isNullOrBlank()) { "Build succeeded without a verified Android launch" }
                    AgentIdeResult.started(op, "launch=${quoted(requireNotNull(result.launchMessage))}\n" +
                        "Android app was installed and launched; app completion is unverified.")
                }
            }
            "cancel" -> if (coordinator.cancelActive("Canceled by Agent after user-approved IDE action")) {
                AgentIdeResult.started(op, "status=cancellation_requested")
            } else "ERROR: no active IDE operation to cancel"
            "debug_file" -> {
                val config = coordinator.debugFile(requireNotNull(request.path), request.configuration)
                debugStarted(op, config)
            }
            "debug_android" -> {
                val result = coordinator.debugAndroid(requireNotNull(request.path), request.task ?: "assembleDebug", request.configuration)
                debugStarted(op, result.configuration) + "\npackage=${quoted(result.launch.packageName)}\npid=${result.launch.pid}"
            }
            "debug_status" -> AgentIdeResult.succeeded(op, debugStatus())
            "debug_configurations" -> {
                val path = coordinator.checkedSourcePath(requireNotNull(request.path))
                val configs = debugger.configurations(path) + debugger.androidAttachConfigurations(path, 1)
                val items = configs.distinctBy { it.name to it.androidJdwp }.map { config -> buildJsonObject {
                    put("name", config.name); put("request", config.request)
                    put("android_jdwp", config.androidJdwp); put("source", config.source)
                } }
                AgentIdeResult.succeeded(op, AgentIdeResult.jsonPage("configurations", items, request.start ?: 0, request.count ?: 80))
            }
            "debug_stop" -> {
                coordinator.stopDebug()
                AgentIdeResult.succeeded(op, "request_succeeded=true\n" + debugStatus())
            }
            "debug_breakpoint_add", "debug_breakpoint_remove" -> {
                val path = coordinator.checkedSourcePath(requireNotNull(request.path))
                val line = requireNotNull(request.line)
                if (op == "debug_breakpoint_add") debugger.addBreakpoint(path, line) else debugger.clearBreakpoint(path, line)
                val breakpoint = debugger.breakpointForRequestedLine(path, line)
                AgentIdeResult.succeeded(op, buildJsonObject {
                    put("path", path); put("line", line)
                    put("registered", breakpoint != null); put("verified_by_adapter", breakpoint?.verified == true)
                }.toString())
            }
            "debug_continue", "debug_next", "debug_step_in", "debug_step_out", "debug_pause" -> {
                requireState(if (op == "debug_pause") DebugState.RUNNING else DebugState.STOPPED)
                val thread = request.threadId ?: debugger.selectedThread.value
                when (op) {
                    "debug_continue" -> debugger.continueExecution(thread)
                    "debug_pause" -> debugger.pause(thread)
                    "debug_next" -> debugger.next(thread)
                    "debug_step_in" -> debugger.stepIn(thread)
                    "debug_step_out" -> debugger.stepOut(thread)
                }
                AgentIdeResult.started(op, "request_accepted=true\n" + debugStatus())
            }
            "debug_threads" -> {
                requireSession()
                val items = debugger.refreshThreads().map { thread -> buildJsonObject {
                    put("id", thread.id); put("name", thread.name)
                } }
                AgentIdeResult.succeeded(op, AgentIdeResult.jsonPage("threads", items, request.start ?: 0, request.count ?: 128))
            }
            "debug_stack" -> {
                requireState(DebugState.STOPPED)
                val thread = request.threadId ?: debugger.selectedThread.value ?: error("No debug thread selected")
                val count = request.count ?: 100
                val frames = debugger.refreshStack(thread, request.start ?: 0, count)
                val items = frames.map { frame -> buildJsonObject {
                    put("id", frame.id); put("name", frame.name)
                    frame.path?.let { put("path", it) }; put("line", frame.line); put("column", frame.column)
                } }
                AgentIdeResult.succeeded(op, AgentIdeResult.jsonPage("frames", items, count = count,
                    sourceOffset = request.start ?: 0, mayHaveMore = frames.size >= count))
            }
            "debug_variables" -> {
                requireState(DebugState.STOPPED)
                val variables = request.reference?.let { debugger.variables(it, request.start ?: 0, request.count ?: 100) }
                    ?: debugger.refreshFrameVariables(request.frameId ?: debugger.selectedFrame.value ?: error("No frame selected"))
                val items = variables.map { variable -> buildJsonObject {
                    put("name", variable.name); put("value", variable.value); variable.type?.let { put("type", it) }
                    put("reference", variable.variablesReference)
                } }
                AgentIdeResult.succeeded(op, AgentIdeResult.jsonPage("variables", items,
                    start = if (request.reference == null) request.start ?: 0 else 0, count = request.count ?: 100,
                    sourceOffset = if (request.reference == null) 0 else request.start ?: 0,
                    mayHaveMore = request.reference != null && variables.size >= (request.count ?: 100)))
            }
            "debug_evaluate" -> {
                requireState(DebugState.STOPPED)
                val result = debugger.evaluate(requireNotNull(request.expression), request.frameId ?: debugger.selectedFrame.value)
                AgentIdeResult.succeeded(op, AgentIdeResult.jsonText("result", result))
            }
            else -> error("Unsupported ide operation: $op")
        }
    }

    private fun requireState(expected: DebugState) = check(debugger.state.value == expected) {
        "Debug operation requires ${expected.name.lowercase()}; current state is ${debugger.state.value.name.lowercase()}"
    }

    private fun requireSession() = check(debugger.state.value in setOf(DebugState.RUNNING, DebugState.STOPPED)) { "No active debug session" }

    private fun debugStarted(operation: String, config: DebugConfiguration): String {
        return AgentIdeResult.started(operation, "configuration=${quoted(config.name)}\n" +
            debugStatus() + "\nDebug session started; target execution completion is unverified.")
    }

    private fun debugStatus(): String = "IDE_DEBUG_STATUS\n" + buildJsonObject {
        put("state", debugger.state.value.name.lowercase()); put("message", debugger.status.value.take(1000))
        debugger.selectedThread.value?.let { put("thread_id", it) }
        debugger.selectedFrame.value?.let { put("frame_id", it) }
        debugger.lastStop.value?.let { stop ->
            put("stop_generation", stop.generation); put("stop_reason", stop.reason)
        }
        put("output_tail", debugger.output.value.takeLast(4000))
    }

    private fun status(): String = buildJsonObject {
        val state = coordinator.state.value
        put("state", state.operation.name.lowercase()); put("busy", state.busy); put("message", state.message.take(500))
        state.startedAtMs?.let { put("started_at_ms", it) }
        put("debug_state", debugger.state.value.name.lowercase())
    }.toString()

    private fun renderBuild(operation: String, result: AndroidDevelopmentManager.BuildResult): String {
      val payload = buildString {
        val effectiveExit = if (result.success) 0 else result.exitCode.takeIf { it != 0 } ?: 1
        append("exit=$effectiveExit\nsuccess=${result.success}\nduration_ms=${result.durationMs}\ndiagnostics=${result.diagnostics.size}\n")
        if (result.localArtifacts.isNotEmpty()) append("artifacts=" + buildJsonArray {
            result.localArtifacts.take(40).forEach { add(it.absolutePath) }
        } + "\n")
        if (result.diagnostics.isNotEmpty()) {
            append("diagnostic_summary:\n")
            result.diagnostics.take(20).forEach { append("- ${it.toString().replace('\n', ' ').take(600)}\n") }
        }
        append("output:\n").append(result.output.takeLast(12_000))
      }
      return if (result.success) AgentIdeResult.succeeded(operation, payload) else AgentIdeResult.failed(operation, payload)
    }

    private fun quoted(value: String) = JsonPrimitive(value).toString()
}

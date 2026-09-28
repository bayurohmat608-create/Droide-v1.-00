package com.baystudio.droide.core









class AgentIdeActions(
    private val coordinator: BuildRunDebugCoordinator,
) {
    suspend fun execute(operation: String, path: String? = null): String = when (operation) {
        "status" -> status()
        "build_debug" -> renderBuild("build_debug", coordinator.buildDebug())
        "test" -> renderBuild("test", coordinator.test())
        "lint" -> renderBuild("lint", coordinator.lint())
        "run_file" -> {
            val target = requireNotNull(path?.trim()?.takeIf { it.isNotEmpty() }) { "ide run_file requires path" }
            val result = coordinator.runFile(target)
            if ("exit=" !in result && result.startsWith("Runner:")) "ERROR: $result"
            else "IDE_ACTION operation=run_file\n$result"
        }
        "cancel" -> if (coordinator.cancelActive("Canceled by Agent after user-approved IDE action")) {
            "IDE_ACTION operation=cancel\nstatus=cancellation_requested"
        } else {
            "ERROR: no active IDE Build/Run/Test/Lint operation to cancel"
        }
        else -> "ERROR: unsupported ide operation: $operation"
    }

    private fun status(): String {
        val state = coordinator.state.value
        return buildString {
            append("IDE_ACTION operation=status\n")
            append("state=").append(state.operation.name.lowercase()).append('\n')
            append("busy=").append(state.busy).append('\n')
            append("message=").append(state.message.take(500))
            state.startedAtMs?.let { append("\nstarted_at_ms=").append(it) }
        }
    }

    private fun renderBuild(operation: String, result: AndroidDevelopmentManager.BuildResult): String {
        val effectiveExit = if (result.success) 0 else result.exitCode.takeIf { it != 0 } ?: 1
        return buildString {
            append("IDE_ACTION operation=").append(operation).append('\n')
            append("exit=").append(effectiveExit).append('\n')
            append("success=").append(result.success).append('\n')
            append("duration_ms=").append(result.durationMs).append('\n')
            append("diagnostics=").append(result.diagnostics.size).append('\n')
            if (result.localArtifacts.isNotEmpty()) {
                append("artifacts=")
                append(result.localArtifacts.take(12).joinToString(",") { it.name.take(160) })
                append('\n')
            }
            if (result.diagnostics.isNotEmpty()) {
                append("diagnostic_summary:\n")
                result.diagnostics.take(20).forEach { diagnostic ->
                    append("- ").append(diagnostic.toString().replace('\n', ' ').take(600)).append('\n')
                }
            }
            append("output:\n").append(result.output.takeLast(12_000))
        }
    }
}

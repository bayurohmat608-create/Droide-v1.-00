package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex








class BuildRunDebugCoordinator(
    private val root: File,
    private val terminals: TerminalManager,
    private val android: AndroidDevelopmentManager,
    private val debugger: DebugManager,
    private val capabilities: UniversalCapabilityRegistry? = null,
) {
    enum class Operation {
        IDLE,
        RUN_FILE,
        CONTRIBUTED_TOOL,
        BUILD_DEBUG,
        BUILD_RELEASE_APK,
        BUILD_RELEASE_BUNDLE,
        TEST,
        LINT,
        INSTALL_RUN,
        DEBUG,
        STOPPING,
    }

    data class State(
        val operation: Operation = Operation.IDLE,
        val message: String = "Ready",
        val startedAtMs: Long? = null,
    ) {
        val busy: Boolean get() = operation != Operation.IDLE
    }

    data class AndroidRunResult(
        val build: AndroidDevelopmentManager.BuildResult?,
        val apk: File?,
        val launchMessage: String?,
    )

    data class AndroidDebugResult(
        val build: AndroidDevelopmentManager.BuildResult,
        val apk: File,
        val launch: AndroidDevelopmentManager.DebugLaunchResult,
        val configuration: DebugConfiguration,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val operationMutex = Mutex()
    @Volatile private var activeOperationJob: Job? = null

    private suspend fun <T> operation(kind: Operation, message: String, block: suspend () -> T): T {
        val job = currentCoroutineContext()[Job]
            ?: error("Build/Run/Debug operation has no coroutine Job")
        check(operationMutex.tryLock()) { "Another Build/Run/Debug operation is already active" }
        activeOperationJob = job
        _state.value = State(kind, message, System.currentTimeMillis())
        return try {
            block()
        } finally {
            if (activeOperationJob === job) activeOperationJob = null
            _state.value = State()
            operationMutex.unlock()
        }
    }

     
    fun cancelActive(reason: String = "Canceled by user"): Boolean {
        val job = activeOperationJob ?: return false
        job.cancel(CancellationException(reason.take(200)))
        return true
    }

    suspend fun runFile(relativePath: String): String = operation(Operation.RUN_FILE, "Running $relativePath…") {
        val contributed = capabilities?.let { registry -> registry::runPlanFor } ?: { _: String -> null }
        
        when (val resolved = Runner.resolveFilePlan(relativePath, root, contributed)) {
            is Runner.FilePlanResolution.Error -> "Runner: ${resolved.message}"
            is Runner.FilePlanResolution.Ready -> {
                val session = terminals.automatedExecutionSession(createIfMissing = true)
                val decision = RunExecutionPolicy.decide(
                    plan = resolved.plan,
                    localRuntimeAvailable = session != null,
                )
                when (decision.target) {
                    RunExecutionPolicy.Target.LOCAL_LINUX_ARM64 -> {
                        Runner(requireNotNull(session), root).runPlan(decision.plan, 30_000)
                    }
                    RunExecutionPolicy.Target.UNAVAILABLE ->
                        RunExecutionPolicy.unavailableMessage(decision.unavailableCommand ?: "runtime") +
                            "\n" + terminals.automatedExecutionUnavailableReason()
                }
            }
        }
    }

     
    suspend fun executeContributedTool(toolId: String, relativePath: String? = null): String =
        operation(Operation.CONTRIBUTED_TOOL, "Running contributed tool…") {
            val registry = capabilities ?: error("Universal capability registry is unavailable")
            val tool = registry.tool(toolId) ?: error("Unknown or ambiguous contributed tool: $toolId")
            val plan = registry.toolPlan(tool.qualifiedId, relativePath)
                ?: error("Contributed tool is unresolved or not applicable to ${relativePath ?: "this workspace"}: ${tool.qualifiedId}")
            runContributedTool(tool, plan)
        }

    



    suspend fun executePreparedContributedTool(
        toolId: String,
        expectedExecutable: String,
        relativePath: String? = null,
    ): String = operation(Operation.CONTRIBUTED_TOOL, "Running approved contributed tool…") {
        val registry = capabilities ?: error("Universal capability registry is unavailable")
        val tool = registry.tool(toolId) ?: error("Unknown or ambiguous contributed tool: $toolId")
        val currentExecutable = registry.resolveExecutable(tool.command)?.resolvedPath
            ?: error("Approved capability executable is no longer available: ${tool.command}")
        require(currentExecutable == expectedExecutable) {
            "Capability executable changed after approval; review the current tool binding before running it"
        }
        val plan = registry.toolPlan(tool.qualifiedId, relativePath)
            ?: error("Approved capability is no longer applicable: ${tool.qualifiedId}")
        require(plan.steps.singleOrNull()?.firstOrNull() == expectedExecutable) {
            "Capability execution plan changed after approval"
        }
        runContributedTool(tool, plan)
    }

    private suspend fun runContributedTool(tool: ContributedToolCapability, plan: RunPlan): String {
        val session = terminals.automatedExecutionSession(createIfMissing = true)
            ?: return RunExecutionPolicy.unavailableMessage(tool.command)
        val timeoutMs = when (tool.kind) {
            DroideToolKind.RUN, DroideToolKind.FORMATTER -> 60_000L
            DroideToolKind.LINTER, DroideToolKind.CLI -> 120_000L
            DroideToolKind.BUILD, DroideToolKind.TEST -> 10L * 60_000L
        }
        return Runner(session, root).runPlan(plan, timeoutMs)
    }

    suspend fun buildDebug(): AndroidDevelopmentManager.BuildResult =
        operation(Operation.BUILD_DEBUG, "Building debug APK…") { android.buildDebug() }

    suspend fun buildReleaseApk(): AndroidDevelopmentManager.BuildResult =
        operation(Operation.BUILD_RELEASE_APK, "Building release APK…") { android.buildReleaseApk() }

    suspend fun buildReleaseBundle(): AndroidDevelopmentManager.BuildResult =
        operation(Operation.BUILD_RELEASE_BUNDLE, "Building release App Bundle…") { android.buildReleaseBundle() }

    suspend fun test(): AndroidDevelopmentManager.BuildResult =
        operation(Operation.TEST, "Running tests…") { android.test() }

    suspend fun lint(): AndroidDevelopmentManager.BuildResult =
        operation(Operation.LINT, "Running lint…") { android.lint() }

    suspend fun installAndRun(existingApk: File? = null): AndroidRunResult =
        operation(Operation.INSTALL_RUN, "Building, installing and running Android app…") {
            


            existingApk?.takeIf { it.isFile } 
            val build = android.buildDebug()
            if (!build.success) return@operation AndroidRunResult(build, null, null)
            val apks = build.localArtifacts.filter {
                it.isFile && it.extension.equals("apk", ignoreCase = true)
            }
            require(apks.size <= 1) {
                "Debug build produced ${apks.size} APKs. Choose an explicit application module/APK instead of guessing."
            }
            val apk = apks.singleOrNull() ?: return@operation AndroidRunResult(build, null, null)
            val launch = android.installAndRun(apk)
            AndroidRunResult(build, apk, launch)
        }

    



    suspend fun debugAndroid(activeFile: String): AndroidDebugResult =
        operation(Operation.DEBUG, "Building and starting Android debugger…") {
            require(activeFile.isNotBlank()) { "Open a Kotlin/Java source file before debugging" }
            check(debugger.hasAndroidAttachProvider(activeFile)) { "No Android JDWP-capable DAP adapter is installed. Open Run & Debug → Extensions, or configure androidJdwp=true in .droide/launch.json." }
            val build = android.buildDebug()
            check(build.success) { "Debug APK build failed" }
            val apks = build.localArtifacts.filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            require(apks.size <= 1) {
                "Debug build produced ${apks.size} APKs. Select an explicit application module before debugging."
            }
            val apk = apks.singleOrNull() ?: error("Debug build completed but no APK artifact was produced")
            val launch = android.installAndLaunchForDebug(apk)
            try {
                val configs = debugger.androidAttachConfigurations(activeFile, launch.pid)
                val config = configs.firstOrNull()
                    ?: error("No Android JDWP-capable DAP adapter is installed. Install a compatible adapter or add androidJdwp=true in .droide/launch.json.")
                debugger.start(config)
                AndroidDebugResult(build, apk, launch, config)
            } catch (error: Throwable) {
                runSuspendCatching { android.stopApp(launch.packageName) }
                throw error
            }
        }

     
    suspend fun debugFile(relativePath: String, configurationName: String? = null): DebugConfiguration =
        operation(Operation.DEBUG, "Starting debugger…") {
            val configs = debugger.configurations(relativePath)
            val selected = configurationName?.let { name -> configs.firstOrNull { it.name == name } }
                ?: configs.firstOrNull()
                ?: error("No compatible debug adapter is available for $relativePath")
            debugger.start(selected)
            selected
        }

    suspend fun stopDebug() = operation(Operation.STOPPING, "Stopping debugger…") {
        debugger.stop()
    }
}

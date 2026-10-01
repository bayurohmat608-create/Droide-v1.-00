package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

enum class TaskExecutionScope { AUTO, LOCAL, LOCAL_LINUX_ARM64 }

data class IdeTask(
    val label: String,
    val argv: List<String>,
    val cwd: File,
    val group: String = "task",
    val source: String = "auto",
    val executionScope: TaskExecutionScope = TaskExecutionScope.AUTO,
)

data class TaskResult(
    val task: IdeTask,
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
    val durationMs: Long,
)

// Task definitions are literal argv arrays, never shell snippets.


class TaskManager(private val root: File, private val terminals: TerminalManager? = null) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    suspend fun list(): List<IdeTask> = withContext(Dispatchers.IO) {
        (loadCustom() + detect()).distinctBy { it.label }.take(100)
    }

    suspend fun run(task: IdeTask, timeoutMs: Long = 10 * 60_000L): TaskResult = withContext(Dispatchers.IO) {
        require(timeoutMs in 1_000..30 * 60_000L) { "Invalid task timeout" }
        require(task.argv.isNotEmpty() && task.argv.size <= 128) { "Invalid task argv" }
        require(PathSecurity.contains(root, task.cwd) && task.cwd.isDirectory) { "Task cwd must stay inside workspace" }
        val relCwd = task.cwd.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath.let { if (it == ".") "." else it }
        val started = System.nanoTime()
        val session = when (task.executionScope) {
            // Ubuntu is a compatibility backend and shares the application UID; it is not a hostile-code sandbox.

            TaskExecutionScope.AUTO -> terminals?.automatedExecutionSession(createIfMissing = true)
                ?: error("Automatic project tasks require the local Linux backend; use explicit LOCAL only for code you trust")
            TaskExecutionScope.LOCAL -> terminals?.localExecutionSession(createIfMissing = true)
            TaskExecutionScope.LOCAL_LINUX_ARM64 -> terminals?.automatedExecutionSession(createIfMissing = true)
                ?: error(terminals?.automatedExecutionUnavailableReason() ?: "Local Linux backend is unavailable")
        }
        val exec = if (session != null) {
            if (session is DevicePtySessionHandle) session.refreshWorkspace()
            // Shell selection follows the actual session backend; caller scope is not trusted as a proxy.

            val backendShell = requireNotNull(terminals).commandShellFor(session)
            session.execArgv(
                listOf(backendShell, "-c", "cd -- \"${'$'}1\" && shift && exec \"${'$'}@\"", "droide-task", relCwd) + task.argv,
                timeoutMs = timeoutMs,
            )
        } else {
            val resolvedArgv = resolveArgv(task.argv)
            ProcessRunner.run(
                argv = resolvedArgv,
                cwd = task.cwd,
                timeoutMs = timeoutMs,
                outputCap = 96_000,
            ).let { ExecResult(it.exitCode, it.output, it.timedOut) }
        }
        val durationMs = (System.nanoTime() - started) / 1_000_000L
        TaskResult(
            task = task,
            exitCode = exec.exitCode,
            output = if (exec.timedOut) (exec.output + "\n[TASK TIMEOUT ${timeoutMs}ms]").takeLast(80_000) else exec.output.takeLast(80_000),
            timedOut = exec.timedOut,
            durationMs = durationMs,
        )
    }

    private fun loadCustom(): List<IdeTask> {
        val config = runCatching { PathSecurity.resolveWithin(root, ".droide/tasks.json") }.getOrNull() ?: return emptyList()
        if (!config.isFile || config.length() !in 1..256_000) return emptyList()
        val parsed = runCatching { json.parseToJsonElement(config.readText()) as? JsonObject }.getOrNull() ?: return emptyList()
        val array = parsed["tasks"] as? JsonArray ?: return emptyList()
        return array.take(100).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun string(key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val label = string("label")?.trim()?.takeIf { it.isNotEmpty() }?.take(160) ?: return@mapNotNull null
            val rawArgv = o["command"] as? JsonArray ?: return@mapNotNull null
            if (rawArgv.isEmpty() || rawArgv.size > 128) return@mapNotNull null
            val argv = rawArgv.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull }
            if (argv.any { it == null || it.length > 4_000 || '\u0000' in it } || argv.first().isNullOrBlank()) return@mapNotNull null
            val safeArgv = argv.filterNotNull()
            if (o["cwd"] != null && string("cwd") == null) return@mapNotNull null
            val cwdRaw = string("cwd") ?: "."
            val cwd = runCatching { if (cwdRaw == ".") root.canonicalFile else PathSecurity.resolveWithin(root, cwdRaw) }.getOrNull() ?: return@mapNotNull null
            if (!cwd.isDirectory) return@mapNotNull null
            if (o["group"] != null && string("group") == null) return@mapNotNull null
            val group = string("group")?.take(80) ?: "task"
            if (o["executionScope"] != null && string("executionScope") == null) return@mapNotNull null
            val executionScope = when (string("executionScope")?.trim()?.lowercase()) {
                null, "", "auto" -> TaskExecutionScope.AUTO
                "local" -> return@mapNotNull null
                "local-linux-arm64", "device-workstation", "workstation", "remote" -> TaskExecutionScope.LOCAL_LINUX_ARM64
                else -> return@mapNotNull null
            }
            IdeTask(label, safeArgv, cwd, group, ".droide/tasks.json", executionScope)
        }
    }

    private fun metadataPath(path: String): File? = runCatching { PathSecurity.resolveWithin(root, path) }.getOrNull()

    private fun detect(): List<IdeTask> {
        val out = mutableListOf<IdeTask>()
        val gradlew = metadataPath("gradlew")
        if (gradlew?.isFile == true) {
            
            out += IdeTask("Gradle: test", listOf("sh", "./gradlew", "test"), root, "test")
            out += IdeTask("Gradle: assembleDebug", listOf("sh", "./gradlew", "assembleDebug"), root, "build")
        }

        val packageJson = metadataPath("package.json")
        if (packageJson?.isFile == true && packageJson.length() <= 2_000_000) {
            val scripts = runCatching {
                val obj = json.parseToJsonElement(packageJson.readText()) as? JsonObject
                (obj?.get("scripts") as? JsonObject)?.keys.orEmpty().take(40)
            }.getOrDefault(emptyList())
            scripts.forEach { script ->
                if (script.matches(Regex("[A-Za-z0-9:_-]{1,100}"))) {
                    out += IdeTask("npm: $script", listOf("npm", "run", script), root, if (script.contains("test", true)) "test" else "task")
                }
            }
        }

        if ((metadataPath("Cargo.toml")?.isFile == true)) {
            out += IdeTask("Cargo: check", listOf("cargo", "check"), root, "build")
            out += IdeTask("Cargo: test", listOf("cargo", "test"), root, "test")
        }
        if ((metadataPath("go.mod")?.isFile == true)) {
            out += IdeTask("Go: test ./...", listOf("go", "test", "./..."), root, "test")
            out += IdeTask("Go: build ./...", listOf("go", "build", "./..."), root, "build")
        }
        if ((metadataPath("pyproject.toml")?.isFile == true) || (metadataPath("pytest.ini")?.isFile == true) || (metadataPath("tests")?.isDirectory == true)) {
            out += IdeTask("Pytest", listOf("pytest"), root, "test")
        }
        if ((metadataPath("Makefile")?.isFile == true) || (metadataPath("makefile")?.isFile == true)) {
            out += IdeTask("Make", listOf("make"), root, "build")
        }
        return out
    }

    private fun resolveArgv(argv: List<String>): List<String> {
        val first = argv.first()
        val executable = when {
            File(first).isAbsolute -> File(first).takeIf { it.isFile && it.canExecute() }?.canonicalPath
            first.contains('/') || first.contains('\\') -> {
                val local = PathSecurity.resolveWithin(root, first.removePrefix("./"))
                local.takeIf { it.isFile && it.canExecute() }?.canonicalPath
            }
            else -> ExecutableResolver.resolve(first)
        } ?: throw IllegalStateException("Task executable not found: $first")
        return listOf(executable) + argv.drop(1)
    }
}

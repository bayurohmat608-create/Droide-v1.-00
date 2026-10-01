package com.baystudio.droide.core

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException


internal class LocalAndroidBuildRunner(
    context: Context,
    private val projectRoot: File,
) {
    data class Result(
        val success: Boolean,
        val exitCode: Int,
        val output: String,
        val artifacts: List<File>,
        val durationMs: Long,
        val diagnostics: List<BuildDiagnostic>,
    )

    private val governor = MobileBuildResourceGovernor(context.applicationContext)

    suspend fun build(
        task: String,
        snapshot: LocalAndroidToolchainDiscovery.Snapshot,
        onStatus: (String) -> Unit,
    ): Result {
        GradleTaskPath.parse(task)
        val root = projectRoot.canonicalFile
        val wrapper = GradleWrapperInspector.inspect(root)
        GradleRuntimeCompatibility.problem(snapshot.javaVersion, wrapper.declaredVersion)?.let(::error)
        val resource = governor.plan()
        val hints = GradleProjectPerformanceHints.read(root)
        val workers = minOf(resource.maxWorkers, hints.maxWorkers ?: resource.maxWorkers).coerceAtLeast(1)
        val useBuildCache = resource.useBuildCache && hints.buildCacheEnabled != false
        val start = System.currentTimeMillis()
        val launch = LocalExecutionSubstrate.linuxLaunchSpec(root)
        val lease = RemoteProcessLease.createLocal("gradle-build")
        val evidence = LocalAndroidBuildEvidence.prepare(root)
        val lineBuffer = StringBuilder()
        return try {
            val command = LocalAndroidBuildCommand.create(task, snapshot, workers, useBuildCache, evidence.initScript.absolutePath)
            onStatus("Local Ubuntu · Gradle $task · $workers worker${if (workers == 1) "" else "s"}")
            val guestArgv = launch.shellCommand(command, lease.environment)
            val targetCommand = guestArgv.joinToString(" ", transform = LocalExecutionSubstrate::shellQuote)
            val exec = LocalProcessSupervisor.capture(
                argv = listOf(if (File("/system/bin/sh").canExecute()) "/system/bin/sh" else "/bin/sh", "-c", lease.wrap(targetCommand)),
                cwd = root,
                environment = launch.environment + lease.environment,
                maxOutputBytes = 1_500_000,
                timeoutMs = BUILD_TIMEOUT_MS,
                cleanup = { LocalExecutionSubstrate.terminateLease(lease) },
                onOutput = { text ->
                    synchronized(lineBuffer) {
                        lineBuffer.append(text)
                        if (lineBuffer.length > 32_000) lineBuffer.delete(0, lineBuffer.length - 16_000)
                        lineBuffer.lineSequence().lastOrNull { it.isNotBlank() }?.trim()
                            ?.takeIf { it.startsWith("> Task ") }
                            ?.let { onStatus("Local Ubuntu · ${it.removePrefix("> ").take(220)}") }
                    }
                },
                keepTail = true,
            )
            val output = exec.output
            val artifacts = if (exec.exitCode == 0 && !exec.timedOut) LocalAndroidBuildArtifactCollector.collect(
                root, task, LocalAndroidBuildEvidence.readOutputRoots(root, evidence.manifest),
            ) else emptyList()
            Result(
                success = exec.exitCode == 0 && !exec.timedOut,
                exitCode = exec.exitCode,
                output = output,
                artifacts = artifacts,
                durationMs = System.currentTimeMillis() - start,
                diagnostics = GradleProblemParser.parse(output, root.canonicalPath),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            PathSecurity.deleteTreeNoFollow(evidence.directory)
        }
    }

    private companion object { const val BUILD_TIMEOUT_MS = 3_600_000L }
}

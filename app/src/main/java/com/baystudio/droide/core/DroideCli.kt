package com.baystudio.droide.core

import java.io.File

object DroideCli {
    sealed interface Result {
        data class Open(val relativePath: String) : Result
        data class Run(val plan: RunPlan) : Result
        data class Pkg(val args: List<String>) : Result
        data class Err(val msg: String) : Result
    }

    fun handle(argv: List<String>, workDir: File): Result {
        if (argv.isEmpty()) return Result.Err("usage: droide open <path> | droide run <path> | droide pkg <cmd>")
        return when (argv[0]) {
            "open" -> {
                val rel = argv.getOrNull(1) ?: return Result.Err("droide open: path required")
                runCatching { PathSecurity.resolveWithin(workDir, rel) }.getOrElse { return Result.Err(it.message ?: "invalid path") }
                Result.Open(rel)
            }
            "run" -> {
                val rel = argv.getOrNull(1) ?: return Result.Err("droide run: path required")
                val f = runCatching { PathSecurity.resolveWithin(workDir, rel) }.getOrElse { return Result.Err(it.message ?: "invalid path") }
                if (!f.isFile) return Result.Err("droide run: file not found: $rel")
                val plan = LanguageRegistry.runPlanFor(rel)
                    ?: return Result.Err("No safe runner for .${rel.substringAfterLast('.', "")} on Android")
                Result.Run(plan)
            }
            "pkg", "plugin", "runtime" -> {
                Result.Pkg(argv)
            }
            else -> Result.Err("unknown command: ${argv[0]} (open|run|pkg|plugin|runtime)")
        }
    }

    fun parse(cmdline: String): List<String> =
        cmdline.trim().removePrefix("droide").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
}

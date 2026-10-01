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

    fun parse(cmdline: String): List<String> {
        require(cmdline.length <= 16_384 && cmdline.none { it == '\u0000' || it == '\n' || it == '\r' }) { "Invalid CLI command" }
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var started = false
        fun finishWord() {
            if (!started) return
            require(words.size < 128 && word.length <= 4_096) { "CLI argument limit exceeded" }
            words += word.toString()
            word.setLength(0)
            started = false
        }
        for (character in cmdline) {
            when {
                escaped -> { word.append(character); escaped = false; started = true }
                character == '\\' && quote != '\'' -> { escaped = true; started = true }
                quote != null -> if (character == quote) quote = null else word.append(character)
                character == '\'' || character == '"' -> { quote = character; started = true }
                character.isWhitespace() -> finishWord()
                else -> { word.append(character); started = true }
            }
        }
        require(!escaped && quote == null) { "Unclosed CLI quote or escape" }
        finishWord()
        return if (words.firstOrNull() == "droide") words.drop(1) else words
    }
}

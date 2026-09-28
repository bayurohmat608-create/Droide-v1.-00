package com.baystudio.droide.core

import java.io.File

 
object ExecutableResolver {
    fun resolve(command: String, additionalDirs: List<File> = emptyList(), path: String? = System.getenv("PATH")): String? {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || trimmed.indexOf('\u0000') >= 0) return null
        val direct = File(trimmed)
        if (direct.isAbsolute) return direct.takeIf(::usable)?.canonicalPath
        if (trimmed.contains('/') || trimmed.contains('\\')) return null

        val dirs = buildList {
            addAll(additionalDirs)
            path.orEmpty().split(File.pathSeparatorChar).forEach { raw ->
                if (raw.isBlank() || raw == ".") return@forEach
                val f = File(raw)
                if (f.isAbsolute) add(f)
            }
        }.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }

        for (dir in dirs) {
            val candidate = File(dir, trimmed)
            if (usable(candidate)) return runCatching { candidate.canonicalPath }.getOrElse { candidate.absolutePath }
        }
        return null
    }

    private fun usable(file: File): Boolean = runCatching { file.isFile && file.canExecute() }.getOrDefault(false)
}

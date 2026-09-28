package com.baystudio.droide.core








internal object AndroidBuildArtifactPolicy {
    enum class Kind(val extension: String, val outputDirectory: String) {
        APK("apk", "apk"),
        BUNDLE("aab", "bundle"),
    }

    data class Query(
        val modulePath: String?,
        val kind: Kind,
        val variantHint: String?,
    )

    fun queryFor(task: String): Query? {
        val parsed = GradleTaskPath.parse(task)
        val taskName = parsed.taskName
        val modulePath = parsed.modulePath
        val (kind, prefix) = when {
            taskName.startsWith("assemble") -> Kind.APK to "assemble"
            taskName.startsWith("bundle") -> Kind.BUNDLE to "bundle"
            else -> return null
        }
        val suffix = taskName.removePrefix(prefix)
        return Query(modulePath, kind, suffix.takeIf(String::isNotBlank)?.replaceFirstChar { it.lowercaseChar() })
    }

    fun matchesRelativePath(query: Query, relativePath: String): Boolean {
        val normalized = relativePath.replace('\\', '/')
        if (normalized.startsWith('/') || normalized.split('/').any { it.isBlank() || it == "." || it == ".." }) return false
        if (!normalized.endsWith(".${query.kind.extension}", ignoreCase = true)) return false

        val modulePrefix = query.modulePath?.let { "$it/" }.orEmpty()
        if (modulePrefix.isNotEmpty() && !normalized.startsWith(modulePrefix)) return false

        val marker = "build/outputs/${query.kind.outputDirectory}/"
        val markerIndex = normalized.indexOf(marker)
        if (markerIndex < 0) return false
        if (markerIndex > 0 && normalized[markerIndex - 1] != '/') return false
        if (modulePrefix.isNotEmpty() && markerIndex < modulePrefix.length) return false

        val variant = query.variantHint ?: return true
        val outputTail = normalized.substring(markerIndex + marker.length)
        val variantDirectory = outputTail.substringBeforeLast('/', missingDelimiterValue = "")
        if (variantDirectory.isBlank()) return false
        return compact(variantDirectory) == compact(variant)
    }

    private fun compact(value: String): String = value
        .lowercase()
        .filter(Char::isLetterOrDigit)
}

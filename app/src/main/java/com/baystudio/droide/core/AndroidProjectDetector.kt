package com.baystudio.droide.core

import java.io.File

data class AndroidProjectModel(
    val packageName: String?,
    val compileSdk: Int?,
    val targetSdk: Int?,
    val minSdk: Int?,
    val buildToolsVersion: String? = null,
    val ndkVersion: String? = null,
    val cmakeVersion: String? = null,
    val requiresNativeToolchain: Boolean = false,
    val requiresCmake: Boolean = false,
    val compileSdks: Set<Int> = compileSdk?.let(::setOf).orEmpty(),
    val buildToolsVersions: Set<String> = buildToolsVersion?.let(::setOf).orEmpty(),
    val ndkVersions: Set<String> = ndkVersion?.let(::setOf).orEmpty(),
    val cmakeVersions: Set<String> = cmakeVersion?.let(::setOf).orEmpty(),
    val androidGradlePluginVersions: Set<String> = emptySet(),
) {
    fun toolchainRequirements(): AndroidToolchainRequirements = AndroidToolchainRequirements(
        compileSdk = compileSdk,
        buildToolsVersion = buildToolsVersion,
        ndkVersion = ndkVersion,
        cmakeVersion = cmakeVersion,
        requiresNativeToolchain = requiresNativeToolchain,
        requiresCmake = requiresCmake,
        compileSdks = compileSdks,
        buildToolsVersions = buildToolsVersions,
        ndkVersions = ndkVersions,
        cmakeVersions = cmakeVersions,
    )
}

data class AndroidToolchainRequirements(
    val compileSdk: Int? = null,
    val buildToolsVersion: String? = null,
    val ndkVersion: String? = null,
    val cmakeVersion: String? = null,
    val requiresNativeToolchain: Boolean = false,
    val requiresCmake: Boolean = false,
    val compileSdks: Set<Int> = compileSdk?.let(::setOf).orEmpty(),
    val buildToolsVersions: Set<String> = buildToolsVersion?.let(::setOf).orEmpty(),
    val ndkVersions: Set<String> = ndkVersion?.let(::setOf).orEmpty(),
    val cmakeVersions: Set<String> = cmakeVersion?.let(::setOf).orEmpty(),
    val androidGradlePluginVersions: Set<String> = emptySet(),
) {
    val effectiveCompileSdks: Set<Int> get() = compileSdks + listOfNotNull(compileSdk)
    val effectiveBuildToolsVersions: Set<String> get() = buildToolsVersions + listOfNotNull(buildToolsVersion)
    val effectiveNdkVersions: Set<String> get() = ndkVersions + listOfNotNull(ndkVersion)
    val effectiveCmakeVersions: Set<String> get() = cmakeVersions + listOfNotNull(cmakeVersion)

    init {
        compileSdk?.let { require(it in 1..999) { "Invalid compileSdk requirement" } }
        require(effectiveCompileSdks.size <= 128 && effectiveCompileSdks.all { it in 1..999 }) {
            "Invalid compileSdk requirement set"
        }
        listOf(
            "Build Tools" to effectiveBuildToolsVersions,
            "NDK" to effectiveNdkVersions,
            "CMake" to effectiveCmakeVersions,
        ).forEach { (label, versions) ->
            require(versions.size <= 128) { "Too many $label requirements" }
            versions.forEach { version ->
                require(version.matches(Regex("[0-9][A-Za-z0-9+_.-]{0,79}"))) {
                    "Invalid toolchain component version: $version"
                }
            }
        }
    }

    val requiresDesktopHostTools: Boolean
        get() = requiresNativeToolchain || effectiveNdkVersions.isNotEmpty() || effectiveCmakeVersions.isNotEmpty()
}

 
object AndroidProjectDetector {
    fun detect(root: File): AndroidProjectModel? {
        val safeRoot = root.canonicalFile
        if (!safeRoot.isDirectory || PathSecurity.isSymbolicLink(root)) return null
        val settings = listOf("settings.gradle.kts", "settings.gradle")
            .map { File(safeRoot, it) }
            .firstOrNull { it.isFile && !PathSecurity.isSymbolicLink(it) && PathSecurity.contains(safeRoot, it) }
        val gradlew = File(safeRoot, "gradlew")
        if (settings == null || !gradlew.isFile || PathSecurity.isSymbolicLink(gradlew) || !PathSecurity.contains(safeRoot, gradlew)) return null

        val buildFiles = safeRoot.walkTopDown().onEnter { directory ->
            directory == safeRoot || (
                directory.name !in SKIP_DIRS &&
                    directory.depthFrom(safeRoot) <= MAX_DEPTH &&
                    PathSecurity.canDescend(safeRoot, directory)
            )
        }
            .filter {
                !PathSecurity.isSymbolicLink(it) && PathSecurity.contains(safeRoot, it) && it.isFile &&
                    (it.name == "build.gradle.kts" || it.name == "build.gradle")
            }
            .take(MAX_BUILD_FILES + 1)
            .toList()
        if (buildFiles.isEmpty() || buildFiles.size > MAX_BUILD_FILES || buildFiles.any { it.length() > MAX_BUILD_FILE_BYTES }) return null
        val orderedBuildFiles = buildFiles.sortedBy { it.relativeTo(safeRoot).invariantSeparatorsPath }
        val buildTexts = buildList {
            for (file in orderedBuildFiles) {
                val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
                add(stripGradleComments(text))
            }
        }
        val codeTexts = buildTexts.map(::maskGradleStrings)
        if (orderedBuildFiles.indices.none { index -> looksLikeAndroidBuild(safeRoot, orderedBuildFiles[index], buildTexts[index], codeTexts[index]) }) return null
        fun intValues(name: String): Set<Int>? {
            val regex = Regex("\\b$name\\s*(?:=|\\s)\\s*(\\d{1,3})")
            val values = codeTexts.asSequence()
                .flatMap { regex.findAll(it).map { match -> match.groupValues[1] } }
                .mapNotNull(String::toIntOrNull)
                .filter { it in 1..999 }
                .distinct()
                .take(MAX_COMPONENT_REQUIREMENTS + 1)
                .toSet()
            return values.takeIf { it.size <= MAX_COMPONENT_REQUIREMENTS }
        }
        val namespaceRegex = Regex("""\b(?:namespace|applicationId)\s*(?:=|\s)\s*["']([A-Za-z][A-Za-z0-9_.]+)["']""")
        val namespace = buildTexts.indices.asSequence()
            .flatMap { index -> namespaceRegex.findAll(buildTexts[index]).filter { codeIdentifierAt(codeTexts[index], it.range.first) } }
            .map { it.groupValues[1] }
            .firstOrNull()
        fun versionValues(name: String): Set<String>? {
            val regex = Regex("""\b$name\s*(?:=|\s)\s*["']([0-9][A-Za-z0-9+_.-]{0,79})["']""")
            val values = buildTexts.indices.asSequence()
                .flatMap { index -> regex.findAll(buildTexts[index]).filter { codeTexts[index].startsWith(name, it.range.first) } }
                .map { match -> match.groupValues[1] }
                .distinct()
                .take(MAX_COMPONENT_REQUIREMENTS + 1)
                .toSet()
            return values.takeIf { it.size <= MAX_COMPONENT_REQUIREMENTS }
        }
        val compileSdks = intValues("compileSdk") ?: return null
        val targetSdks = intValues("targetSdk") ?: return null
        val minSdks = intValues("minSdk") ?: return null
        val buildToolsVersions = versionValues("buildToolsVersion") ?: return null
        val ndkVersions = versionValues("ndkVersion") ?: return null
        val androidGradlePluginVersions = buildTexts.indices.asSequence()
            .flatMap { index ->
                ANDROID_PLUGIN_VERSION.findAll(buildTexts[index])
                    .filter { codeTexts[index].startsWith("id", it.range.first) }
                    .map { it.groupValues[1] }
            }
            .distinct()
            .take(MAX_COMPONENT_REQUIREMENTS + 1)
            .toSet()
        if (androidGradlePluginVersions.size > MAX_COMPONENT_REQUIREMENTS) return null
        val cmakeMarker = Regex("""\b(?:externalNativeBuild\s*\{[\s\S]*?cmake\s*\{|cmake\s*\{)""")
        val nativeMarker = Regex("""\b(?:externalNativeBuild|ndkVersion)""")
        val cmakeBuild = codeTexts.any(cmakeMarker::containsMatchIn) || safeRootFileExists(safeRoot, "CMakeLists.txt")
        val nativeBuild = cmakeBuild || codeTexts.any(nativeMarker::containsMatchIn) || safeRootFileExists(safeRoot, "Android.mk")
        val cmakeVersionRegex = Regex("""(?s)externalNativeBuild\s*\{.*?cmake\s*\{.*?version\s*(?:=|\s)\s*["']([0-9][A-Za-z0-9+_.-]{0,79})["']""")
        val cmakeVersions = buildTexts.indices.asSequence()
            .flatMap { index -> cmakeVersionRegex.findAll(buildTexts[index]).filter { codeTexts[index].startsWith("externalNativeBuild", it.range.first) } }
            .map { match -> match.groupValues[1] }
            .distinct()
            .take(MAX_COMPONENT_REQUIREMENTS + 1)
            .toSet()
        if (cmakeVersions.size > MAX_COMPONENT_REQUIREMENTS) return null
        return AndroidProjectModel(
            packageName = namespace,
            compileSdk = compileSdks.maxOrNull(),
            targetSdk = targetSdks.maxOrNull(),
            minSdk = minSdks.minOrNull(),
            buildToolsVersion = buildToolsVersions.singleOrNull(),
            ndkVersion = ndkVersions.singleOrNull(),
            cmakeVersion = cmakeVersions.singleOrNull(),
            requiresNativeToolchain = nativeBuild,
            requiresCmake = cmakeBuild,
            compileSdks = compileSdks,
            buildToolsVersions = buildToolsVersions,
            ndkVersions = ndkVersions,
            cmakeVersions = cmakeVersions,
            androidGradlePluginVersions = androidGradlePluginVersions,
        )
    }

    private fun looksLikeAndroidBuild(root: File, buildFile: File, text: String, codeText: String): Boolean {
        if (ANDROID_PLUGIN_CALL.findAll(text).any { codeText.startsWith("id", it.range.first) } || ANDROID_BLOCK.containsMatchIn(codeText)) return true
        val manifest = File(buildFile.parentFile, "src/main/AndroidManifest.xml")
        return manifest.isFile && !PathSecurity.isSymbolicLink(manifest) && PathSecurity.contains(root, manifest)
    }

    private fun codeIdentifierAt(codeText: String, offset: Int): Boolean =
        offset in codeText.indices && (codeText.startsWith("namespace", offset) || codeText.startsWith("applicationId", offset))

    private fun safeRootFileExists(root: File, name: String): Boolean {
        val file = File(root, name)
        return file.isFile && !PathSecurity.isSymbolicLink(file) && PathSecurity.contains(root, file)
    }

     
    private fun stripGradleComments(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        var blockDepth = 0
        var quote: Char? = null
        var tripleDouble = false
        while (index < text.length) {
            val c = text[index]
            val next = text.getOrNull(index + 1)
            if (blockDepth > 0) {
                when {
                    c == '/' && next == '*' -> { blockDepth += 1; out.append("  "); index += 2 }
                    c == '*' && next == '/' -> { blockDepth -= 1; out.append("  "); index += 2 }
                    c == '\n' -> { out.append('\n'); index += 1 }
                    else -> { out.append(' '); index += 1 }
                }
                continue
            }
            if (tripleDouble) {
                if (text.startsWith("\"\"\"", index)) {
                    out.append("\"\"\""); index += 3; tripleDouble = false
                } else { out.append(c); index += 1 }
                continue
            }
            if (quote != null) {
                out.append(c)
                if (c == '\\' && index + 1 < text.length) { out.append(text[index + 1]); index += 2; continue }
                if (c == quote) quote = null
                index += 1
                continue
            }
            when {
                text.startsWith("\"\"\"", index) -> { out.append("\"\"\""); index += 3; tripleDouble = true }
                c == '\"' || c == '\'' -> { quote = c; out.append(c); index += 1 }
                c == '/' && next == '/' -> {
                    out.append("  "); index += 2
                    while (index < text.length && text[index] != '\n') { out.append(' '); index += 1 }
                }
                c == '/' && next == '*' -> { blockDepth = 1; out.append("  "); index += 2 }
                else -> { out.append(c); index += 1 }
            }
        }
        return out.toString()
    }

     
    private fun maskGradleStrings(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        var quote: Char? = null
        var tripleDouble = false
        while (index < text.length) {
            val c = text[index]
            if (tripleDouble) {
                if (text.startsWith("\"\"\"", index)) {
                    out.append("\"\"\""); index += 3; tripleDouble = false
                } else { out.append(if (c == '\n') '\n' else ' '); index += 1 }
                continue
            }
            if (quote != null) {
                if (c == '\\' && index + 1 < text.length) { out.append("  "); index += 2; continue }
                if (c == quote) { quote = null; out.append(c) } else out.append(if (c == '\n') '\n' else ' ')
                index += 1
                continue
            }
            when {
                text.startsWith("\"\"\"", index) -> { out.append("\"\"\""); index += 3; tripleDouble = true }
                c == '\"' || c == '\'' -> { quote = c; out.append(c); index += 1 }
                else -> { out.append(c); index += 1 }
            }
        }
        return out.toString()
    }

    private fun File.depthFrom(root: File): Int = relativeTo(root).toPath().nameCount

    private const val MAX_DEPTH = 8
    private const val MAX_BUILD_FILES = 256
    private const val MAX_BUILD_FILE_BYTES = 2_000_000L
    private const val MAX_COMPONENT_REQUIREMENTS = 128
    private val SKIP_DIRS = setOf(".git", ".gradle", "build", ".droide")
    private val ANDROID_PLUGIN_CALL = Regex("""\bid\s*(?:\(\s*)?["'](?:com\.android\.application|com\.android\.library|com\.android\.dynamic-feature)["']""")
    private val ANDROID_PLUGIN_VERSION = Regex(
        """\bid\s*(?:\(\s*)?["'](?:com\.android\.application|com\.android\.library|com\.android\.dynamic-feature)["']\s*\)?\s*version\s*(?:\(\s*)?["']([0-9][A-Za-z0-9+_.-]{0,79})["']"""
    )
    private val ANDROID_BLOCK = Regex("""\bandroid\s*\{""")
}

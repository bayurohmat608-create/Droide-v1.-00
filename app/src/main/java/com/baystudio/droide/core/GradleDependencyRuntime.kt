package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

 
internal data class GradleReadOnlyDependencyCache(
    val root: String,
    val gradleVersion: String,
    val metadataFormat: String,
) {
    init {
        DeviceBridgeManager.requireSafeRemotePath(root)
        require(root.startsWith(GradleReadOnlyDependencyCacheResolver.userCacheRoot() + "/")) {
            "Gradle read-only cache path must stay inside Device Workstation user cache root"
        }
    }
    val identity: String get() = "$gradleVersion\u0000$metadataFormat\u0000$root"
}

@Serializable
private data class GradleReadOnlyDependencyCacheMarker(
    val schema: Int,
    val gradleVersion: String,
    val metadataFormat: String,
    val root: String,
)

// Project files can never nominate an executable or dependency-cache path.




internal class GradleReadOnlyDependencyCacheResolver(
    private val shell: suspend (String, Int) -> BridgeShellResult,
) {
    constructor(bridge: DeviceBridgeManager) : this({ command, cap -> bridge.shellBounded(command, cap) })

    private val json = Json { ignoreUnknownKeys = false }

    suspend fun resolve(expectedGradleVersion: String): GradleReadOnlyDependencyCache? {
        require(expectedGradleVersion.matches(Regex("[0-9][A-Za-z0-9+_.-]{0,63}"))) { "Invalid Gradle runtime version" }
        val marker = markerPath()
        val markerResult = shell(
            "if [ -e ${DeviceBridgeManager.shellQuote(marker)} ] || [ -L ${DeviceBridgeManager.shellQuote(marker)} ]; then " +
                "test -f ${DeviceBridgeManager.shellQuote(marker)}; test ! -L ${DeviceBridgeManager.shellQuote(marker)}; " +
                "cat ${DeviceBridgeManager.shellQuote(marker)}; fi",
            MAX_MARKER_BYTES,
        )
        check(markerResult.exitCode == 0) { "Gradle read-only cache marker is unsafe or unreadable" }
        if (markerResult.stdout.isBlank()) return null
        require(!markerResult.truncated) { "Gradle read-only cache marker output was truncated" }
        require(markerResult.stdout.toByteArray(Charsets.UTF_8).size <= MAX_MARKER_BYTES) {
            "Gradle read-only cache marker is too large"
        }
        val parsed = runCatching { json.decodeFromString<GradleReadOnlyDependencyCacheMarker>(markerResult.stdout) }
            .getOrElse { error -> throw IllegalArgumentException("Invalid Gradle read-only cache marker: ${error.message}", error) }
        require(parsed.schema == 1) { "Gradle read-only cache marker schema must be 1" }
        require(parsed.gradleVersion == expectedGradleVersion) {
            "Gradle read-only cache targets ${parsed.gradleVersion}, but the project Wrapper runs $expectedGradleVersion"
        }
        require(parsed.metadataFormat.matches(Regex("metadata-[0-9]{1,6}(?:\\.[0-9]{1,6})?"))) {
            "Invalid Gradle dependency-cache metadata format"
        }
        requireInsideUserRoot(parsed.root)

        val modules = parsed.root.trimEnd('/') + "/modules-2"
        val health = shell(
            "set -eu; " +
                "test -d ${DeviceBridgeManager.shellQuote(parsed.root)}; " +
                "test ! -L ${DeviceBridgeManager.shellQuote(parsed.root)}; " +
                "test -d ${DeviceBridgeManager.shellQuote(modules)}; " +
                "test ! -L ${DeviceBridgeManager.shellQuote(modules)}; " +
                "test ! -e ${DeviceBridgeManager.shellQuote(modules + "/modules-2.lock")}; " +
                "test ! -e ${DeviceBridgeManager.shellQuote(modules + "/gc.properties")}",
            16_000,
        )
        check(health.exitCode == 0) {
            "Gradle read-only dependency cache is missing, unsafe, or contains mutable cache-control files"
        }
        return GradleReadOnlyDependencyCache(parsed.root, parsed.gradleVersion, parsed.metadataFormat)
    }

    private fun requireInsideUserRoot(path: String) {
        DeviceBridgeManager.requireSafeRemotePath(path)
        val root = userCacheRoot()
        require(path.startsWith("$root/") && path != root) {
            "Gradle read-only cache must stay inside $root"
        }
    }

    companion object {
        private const val MAX_MARKER_BYTES = 16_384
        fun userCacheRoot(): String = DeviceBridgeManager.remoteRoot() + "/user/gradle-cache"
        fun markerPath(): String = userCacheRoot() + "/gradle-ro-cache.json"
    }
}

 
object GradleProjectConfigurationFingerprint {
    private const val MAX_FILES = 2_048
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 16L * 1024L * 1024L
    private val excludedDirectories = setOf(".git", ".gradle", ".idea", ".droide", "build", "out")

    fun compute(projectRoot: File): String {
        val root = projectRoot.canonicalFile
        require(root.isDirectory && !PathSecurity.isSymbolicLink(projectRoot)) { "Invalid project root" }
        val includedBuildRoots = discoverIncludedBuildRoots(root)
        val files = root.walkTopDown()
            .onEnter { dir ->
                dir == root || (dir.name !in excludedDirectories && !PathSecurity.isSymbolicLink(dir) && PathSecurity.canDescend(root, dir))
            }
            .filter { file -> file.isFile && !PathSecurity.isSymbolicLink(file) && isBuildConfigurationFile(root, file, includedBuildRoots) }
            .take(MAX_FILES + 1)
            .toList()
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        require(files.size <= MAX_FILES) { "Gradle build configuration exceeds bounded fingerprint file count" }
        var totalBytes = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("droide-gradle-config-v1\u0000".toByteArray())
        for (file in files) {
            require(PathSecurity.contains(root, file)) { "Gradle build configuration escapes project root" }
            val size = file.length()
            require(size in 0..MAX_FILE_BYTES) { "Gradle build configuration file is too large: ${file.name}" }
            totalBytes += size
            require(totalBytes <= MAX_TOTAL_BYTES) { "Gradle build configuration exceeds bounded fingerprint size" }
            digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray())
            digest.update(0.toByte())
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun discoverIncludedBuildRoots(root: File): Set<String> {
        val regex = Regex("""\bincludeBuild\s*(?:\(\s*(?:file\s*\(\s*)?)?["']([^"']{1,300})["']""")
        return listOf("settings.gradle.kts", "settings.gradle").asSequence()
            .map { File(root, it) }
            .filter { it.isFile && !PathSecurity.isSymbolicLink(it) && PathSecurity.contains(root, it) && it.length() <= MAX_FILE_BYTES }
            .flatMap { file -> regex.findAll(file.readText(Charsets.UTF_8)).map { it.groupValues[1].replace('\\', '/') } }
            .filter { path -> path.isNotBlank() && !path.startsWith('/') && path.split('/').none { it.isBlank() || it == "." || it == ".." } }
            .mapNotNull { path ->
                val dir = File(root, path)
                if (dir.isDirectory && !PathSecurity.isSymbolicLink(dir) && PathSecurity.contains(root, dir)) {
                    dir.relativeTo(root).invariantSeparatorsPath
                } else null
            }
            .toSet()
    }

    private fun isBuildConfigurationFile(root: File, file: File, includedBuildRoots: Set<String>): Boolean {
        val rel = file.relativeTo(root).invariantSeparatorsPath
        val name = file.name
        if (name in setOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts", "gradle.properties", "gradle.lockfile")) return true
        if (name.endsWith(".gradle") || name.endsWith(".gradle.kts") || name.endsWith(".lockfile")) return true
        if (rel.startsWith("gradle/") && name.substringAfterLast('.', "") in GRADLE_METADATA_EXTENSIONS) return true
        val buildLogicRoot = when {
            rel.startsWith("buildSrc/") -> "buildSrc"
            rel.startsWith("build-logic/") -> "build-logic"
            else -> includedBuildRoots.firstOrNull { rel.startsWith("$it/") }
        }
        if (buildLogicRoot != null && rel.startsWith("$buildLogicRoot/src/")) {
            return name.substringAfterLast('.', "") in BUILD_LOGIC_SOURCE_EXTENSIONS
        }
        return false
    }

    private val GRADLE_METADATA_EXTENSIONS = setOf("toml", "xml", "properties", "lockfile", "keys")
    private val BUILD_LOGIC_SOURCE_EXTENSIONS = setOf("kt", "java", "groovy", "properties", "toml", "xml", "json", "yaml", "yml")
}





internal class GradleDependencyRuntimeProbe internal constructor(
    private val shell: suspend (String, Int) -> BridgeShellResult,
) {
    constructor(bridge: DeviceBridgeManager) : this({ command, cap -> bridge.shellBounded(command, cap) })

    private val readyFingerprints = ConcurrentHashMap.newKeySet<String>()

    suspend fun ensureReady(
        remoteWorkspace: String,
        manifest: AndroidDevelopmentManager.ToolchainManifest,
        wrapper: GradleWrapperDescriptor,
        projectConfigurationFingerprint: String,
        readOnlyCache: GradleReadOnlyDependencyCache?,
    ) {
        DeviceBridgeManager.requireSafeRemotePath(remoteWorkspace)
        require(projectConfigurationFingerprint.matches(Regex("[0-9a-f]{64}"))) { "Invalid Gradle configuration fingerprint" }
        val cacheKey = listOf(
            wrapper.fingerprintSha256,
            projectConfigurationFingerprint,
            manifest.version,
            manifest.javaHome,
            manifest.sdkRoot,
            manifest.aapt2Path,
            manifest.runtime.mode.name,
            manifest.runtime.launcherPath.orEmpty(),
            manifest.runtime.rootfsPath.orEmpty(),
            manifest.runtime.hostBinPath.orEmpty(),
            manifest.runtime.guestJavaHome.orEmpty(),
            manifest.runtime.qemuPath.orEmpty(),
            readOnlyCache?.identity.orEmpty(),
        ).joinToString("\u0000")
        if (cacheKey in readyFingerprints) return

        val root = DeviceBridgeManager.remoteRoot()
        val home = "$root/home"
        val gradleHome = "$root/gradle-cache"
        val tempDir = "$root/tmp"
        val gradleArgs = buildString {
            // Configuration probes must never leave a persistent daemon behind.

            append("/bin/sh ./.droide-gradlew --console=plain --max-workers=1 -Dorg.gradle.vfs.watch=false --no-daemon help")
        }
        val command = buildString {
            append("set -eu; ")
            append("export HOME=").append(DeviceBridgeManager.shellQuote(home)).append("; ")
            append("export JAVA_HOME=").append(DeviceBridgeManager.shellQuote(manifest.javaHome)).append("; ")
            append("export ANDROID_HOME=").append(DeviceBridgeManager.shellQuote(manifest.sdkRoot)).append("; ")
            append("export ANDROID_SDK_ROOT=").append(DeviceBridgeManager.shellQuote(manifest.sdkRoot)).append("; ")
            append("export GRADLE_USER_HOME=").append(DeviceBridgeManager.shellQuote(gradleHome)).append("; ")
            readOnlyCache?.let {
                append("export GRADLE_RO_DEP_CACHE=").append(DeviceBridgeManager.shellQuote(it.root)).append("; ")
            }
            append("export TMPDIR=").append(DeviceBridgeManager.shellQuote(tempDir)).append("; ")
            append("export PATH=").append(DeviceBridgeManager.shellQuote("${manifest.javaHome}/bin:/system/bin:/system/xbin")).append("; ")
            append("mkdir -p ").append(DeviceBridgeManager.shellQuote(home)).append(' ')
                .append(DeviceBridgeManager.shellQuote(gradleHome)).append(' ')
                .append(DeviceBridgeManager.shellQuote(tempDir)).append("; ")
            append("cd ").append(DeviceBridgeManager.shellQuote(remoteWorkspace)).append("; ")
            append("toybox tr -d '\\015' < ./gradlew > ./.droide-gradlew; chmod 700 ./.droide-gradlew; ")
            if (AndroidToolchainRuntime.isCompatibility(manifest.runtime)) {
                append(AndroidToolchainRuntime.guestCommand(manifest.runtime, gradleArgs, remoteWorkspace))
            } else {
                append(gradleArgs.replace("/bin/sh", "/system/bin/sh"))
            }
        }
        val result = shell(command, 500_000)
        check(result.exitCode == 0) { failureMessage(result.combined) }
        readyFingerprints += cacheKey
    }

    private fun failureMessage(output: String): String {
        val lower = output.lowercase()
        val reason = when {
            "unknownhostexception" in lower || "could not resolve host" in lower || "network is unreachable" in lower ->
                "Gradle repositories are unreachable"
            "no cached version" in lower || "available for offline mode" in lower ->
                "required Gradle dependencies are not present in cache"
            "could not resolve all" in lower || "could not find" in lower || "plugin" in lower && "was not found" in lower ->
                "Gradle plugin/dependency resolution failed"
            else -> "Gradle project configuration failed"
        }
        return "$reason during build preflight: ${output.takeLast(12_000)}"
    }
}

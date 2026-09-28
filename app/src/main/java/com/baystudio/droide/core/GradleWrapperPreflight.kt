package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap







data class GradleWrapperDescriptor(
    val distributionUrl: String,
    val declaredVersion: String?,
    val distributionSha256: String?,
    val fingerprintSha256: String,
)

object GradleWrapperInspector {
    private const val MAX_SCRIPT_BYTES = 256_000L
    private const val MAX_PROPERTIES_BYTES = 64_000L
    private const val MAX_WRAPPER_JAR_BYTES = 4_000_000L
    private const val MAX_DISTRIBUTION_URL_CHARS = 4_096
    private val SHA256 = Regex("[0-9a-fA-F]{64}")
    private val STANDARD_DISTRIBUTION = Regex("(?:^|/)gradle-([0-9][A-Za-z0-9+_.-]*)-(?:bin|all)\\.zip(?:[?#].*)?$")

    fun inspect(projectRoot: File): GradleWrapperDescriptor {
        val root = projectRoot.canonicalFile
        require(root.isDirectory && !PathSecurity.isSymbolicLink(projectRoot)) { "Invalid project root" }
        val script = requiredRegularFile(root, "gradlew", MAX_SCRIPT_BYTES)
        val propertiesFile = requiredRegularFile(root, "gradle/wrapper/gradle-wrapper.properties", MAX_PROPERTIES_BYTES)
        val wrapperJar = requiredRegularFile(root, "gradle/wrapper/gradle-wrapper.jar", MAX_WRAPPER_JAR_BYTES)

        val properties = Properties().apply { propertiesFile.inputStream().buffered().use { load(it) } }
        val distributionUrl = properties.getProperty("distributionUrl")?.trim().orEmpty()
        require(distributionUrl.isNotEmpty() && distributionUrl.length <= MAX_DISTRIBUTION_URL_CHARS) {
            "Gradle wrapper distributionUrl is missing or too long"
        }
        require(distributionUrl.none { it == '\u0000' || it == '\n' || it == '\r' }) {
            "Gradle wrapper distributionUrl contains control characters"
        }
        val distributionSha256 = properties.getProperty("distributionSha256Sum")?.trim()?.takeIf(String::isNotEmpty)
        require(distributionSha256 == null || SHA256.matches(distributionSha256)) {
            "Gradle wrapper distributionSha256Sum must be a 64-character SHA-256"
        }

        val declaredVersion = STANDARD_DISTRIBUTION.find(distributionUrl)?.groupValues?.getOrNull(1)
        val fingerprint = MessageDigest.getInstance("SHA-256").apply {
            update("droide-gradle-wrapper-v1\u0000".toByteArray())
            listOf(script, propertiesFile, wrapperJar).forEach { file ->
                update(file.relativeTo(root).invariantSeparatorsPath.toByteArray())
                update(0.toByte())
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        update(buffer, 0, read)
                    }
                }
                update(0.toByte())
            }
        }.digest().joinToString("") { "%02x".format(it) }

        return GradleWrapperDescriptor(
            distributionUrl = distributionUrl,
            declaredVersion = declaredVersion,
            distributionSha256 = distributionSha256?.lowercase(),
            fingerprintSha256 = fingerprint,
        )
    }

    private fun requiredRegularFile(root: File, relativePath: String, maxBytes: Long): File {
        val file = File(root, relativePath)
        require(PathSecurity.contains(root, file) && !PathSecurity.isSymbolicLink(file) && file.isFile) {
            "Gradle wrapper file is missing or unsafe: $relativePath"
        }
        require(file.length() in 1..maxBytes) { "Gradle wrapper file has invalid size: $relativePath" }
        return file
    }
}

 
class GradleWrapperRuntimeProbe internal constructor(
    private val shell: suspend (String, Int) -> BridgeShellResult,
) {
    constructor(bridge: DeviceBridgeManager) : this({ command, cap -> bridge.shellBounded(command, cap) })

    private val readyFingerprints = ConcurrentHashMap.newKeySet<String>()

    suspend fun ensureReady(
        remoteWorkspace: String,
        manifest: AndroidDevelopmentManager.ToolchainManifest,
        descriptor: GradleWrapperDescriptor,
    ): String {
        DeviceBridgeManager.requireSafeRemotePath(remoteWorkspace)
        val cacheKey = listOf(
            descriptor.fingerprintSha256,
            manifest.version,
            manifest.javaVersion.toString(),
            manifest.javaHome,
            manifest.sdkRoot,
            manifest.aapt2Path,
            manifest.runtime.mode.name,
            manifest.runtime.launcherPath.orEmpty(),
            manifest.runtime.rootfsPath.orEmpty(),
            manifest.runtime.hostBinPath.orEmpty(),
            manifest.runtime.guestJavaHome.orEmpty(),
            manifest.runtime.qemuPath.orEmpty(),
        ).joinToString("\u0000")
        if (cacheKey in readyFingerprints) return descriptor.declaredVersion ?: "verified"

        val root = DeviceBridgeManager.remoteRoot()
        val home = "$root/home"
        val gradleHome = "$root/gradle-cache"
        val tempDir = "$root/tmp"
        val path = "${manifest.javaHome}/bin:/system/bin:/system/xbin"
        val wrapperInvocation = "/bin/sh ./.droide-gradlew --console=plain --version --no-daemon"
        val command = buildString {
            append("set -eu; ")
            append("export HOME=").append(DeviceBridgeManager.shellQuote(home)).append("; ")
            append("export JAVA_HOME=").append(DeviceBridgeManager.shellQuote(manifest.javaHome)).append("; ")
            append("export ANDROID_HOME=").append(DeviceBridgeManager.shellQuote(manifest.sdkRoot)).append("; ")
            append("export ANDROID_SDK_ROOT=").append(DeviceBridgeManager.shellQuote(manifest.sdkRoot)).append("; ")
            append("export GRADLE_USER_HOME=").append(DeviceBridgeManager.shellQuote(gradleHome)).append("; ")
            append("export TMPDIR=").append(DeviceBridgeManager.shellQuote(tempDir)).append("; ")
            append("export PATH=").append(DeviceBridgeManager.shellQuote(path)).append("; ")
            append("mkdir -p ").append(DeviceBridgeManager.shellQuote(home)).append(' ')
                .append(DeviceBridgeManager.shellQuote(gradleHome)).append(' ')
                .append(DeviceBridgeManager.shellQuote(tempDir)).append("; ")
            append("cd ").append(DeviceBridgeManager.shellQuote(remoteWorkspace)).append("; ")
            append("test -f ./gradlew -a -f ./gradle/wrapper/gradle-wrapper.jar -a -f ./gradle/wrapper/gradle-wrapper.properties; ")
            append("toybox tr -d '\\015' < ./gradlew > ./.droide-gradlew; chmod 700 ./.droide-gradlew; ")
            if (AndroidToolchainRuntime.isCompatibility(manifest.runtime)) {
                append(AndroidToolchainRuntime.guestCommand(manifest.runtime, wrapperInvocation, remoteWorkspace))
            } else {
                append("/system/bin/sh ./.droide-gradlew --console=plain --version --no-daemon")
            }
        }
        val result = shell(command, 160_000)
        check(result.exitCode == 0) {
            "Gradle wrapper could not start with the selected Android toolchain: ${result.combined.takeLast(12_000)}"
        }
        val runtimeVersion = Regex("(?m)^Gradle\\s+([0-9][^\\s]*)\\s*$")
            .find(result.combined)?.groupValues?.getOrNull(1)
            ?: error("Gradle wrapper started but did not report a Gradle version")
        descriptor.declaredVersion?.let { declared ->
            check(runtimeVersion == declared) {
                "Gradle wrapper version mismatch: declared $declared but runtime reported $runtimeVersion"
            }
        }
        readyFingerprints += cacheKey
        return runtimeVersion
    }
}

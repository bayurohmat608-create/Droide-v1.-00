package com.baystudio.droide.core

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext


internal class LocalAndroidToolchainDiscovery private constructor(
    private val context: Context?,
    private val projectRoot: File,
    private val commandRunner: (suspend (String, Long) -> ExecResult)?,
) {
    constructor(context: Context, projectRoot: File) : this(context, projectRoot, null)

    internal constructor(projectRoot: File, commandRunner: suspend (String, Long) -> ExecResult) :
        this(null, projectRoot, commandRunner)

    enum class Source(val label: String) {
        MANAGED_RECEIPTS("Droide-managed Ubuntu packages"),
        GUEST_ENVIRONMENT("Ubuntu environment"),
    }

    data class Snapshot(
        val source: Source,
        val javaHome: String,
        val javaVersion: Int,
        val sdkRoot: String,
        val aapt2Path: String,
        val zipalignPath: String,
        val apksignerPath: String,
        val compileSdks: Set<Int>,
        val buildToolsVersions: Set<String>,
        val ndkVersions: Set<String>,
        val cmakeVersions: Set<String>,
        val managedFamilies: Set<String>,
    ) {
        val versionLabel: String
            get() = "local-jdk$javaVersion-sdk${compileSdks.maxOrNull() ?: "?"}"
    }

    data class Resolution(val snapshot: Snapshot?, val failure: String? = null)

    suspend fun resolve(
        requirements: AndroidToolchainRequirements,
        androidGradlePluginVersions: Set<String> = emptySet(),
    ): Resolution {
        coroutineContext.ensureActive()
        if (commandRunner == null) {
            val linux = LocalExecutionSubstrate.inspectLinuxState()
            if (!linux.ready) {
                val reason = when {
                    !linux.prootAvailable -> "Local Linux engine is unavailable"
                    !linux.ubuntuAvailable -> "Local Ubuntu PC Environment is not installed"
                    else -> "Local Ubuntu PC Environment failed its health check"
                }
                return Resolution(null, reason)
            }
        }

        val selected = selectedManagedRecords()
        val result = try {
            runProbe(probeScript(requirements))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return Resolution(null, failure.message ?: "Local Ubuntu toolchain probe failed")
        }
        coroutineContext.ensureActive()
        if (result.timedOut) return Resolution(null, "Local Ubuntu Android toolchain probe timed out")
        if (result.exitCode != 0) {
            val detail = result.output.lineSequence().lastOrNull { it.isNotBlank() }?.take(300)
            return Resolution(null, detail ?: "Local Ubuntu Android toolchain is incomplete")
        }
        val parsed = runCatching { parseProbe(result.output, selected.map { it.familyId }.toSet()) }
            .getOrElse { return Resolution(null, it.message ?: "Local Ubuntu Android toolchain probe was invalid") }
        requirementsProblem(parsed, requirements)?.let { return Resolution(null, it) }
        if (commandRunner == null) {
            val wrapper = runCatching { GradleWrapperInspector.inspect(projectRoot) }
                .getOrElse { return Resolution(null, it.message ?: "Gradle Wrapper is missing or unsafe") }
            GradleRuntimeCompatibility.problem(parsed.javaVersion, wrapper.declaredVersion)
                ?.let { return Resolution(null, it) }
            AndroidGradlePluginJdkCompatibility.problem(parsed.javaVersion, androidGradlePluginVersions)
                ?.let { return Resolution(null, it) }
        }
        return Resolution(parsed)
    }

    private fun selectedManagedRecords(): List<ManagedPackageRecord> {
        if (commandRunner != null) return emptyList()
        val appContext = checkNotNull(context).applicationContext
        val records = ManagedPackageRegistry(appContext).list()
        val preferences = WorkspaceToolchainPreferences(appContext, projectRoot)
        return LocalLinuxPackageEnvironment.selectedRecords(
            records = records,
            workspaceSelections = preferences.selections(),
            appRoot = appContext.filesDir.canonicalPath,
        )
    }

    private suspend fun runProbe(script: String): ExecResult {
        commandRunner?.let { return it(script, PROBE_TIMEOUT_MS) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return try {
            val launch = LocalExecutionSubstrate.linuxLaunchSpec(projectRoot)
            TerminalSession(projectRoot, scope, launch).execArgv(listOf("/bin/sh", "-lc", script), PROBE_TIMEOUT_MS)
        } finally {
            scope.cancel()
        }
    }

    internal fun probeScript(requirements: AndroidToolchainRequirements): String {
        val requestedBuildTools = requirements.effectiveBuildToolsVersions.sorted()
        val requestedNdks = requirements.effectiveNdkVersions.sorted()
        val requestedCmakes = requirements.effectiveCmakeVersions.sorted()
        return buildString {
            append("set -eu; ")
            append("if [ -n \"\${JAVA_HOME:-}\" ]; then ")
            append("JAVA_HOME_DETECTED=\${JAVA_HOME%/}; JAVA_BIN=\"\$JAVA_HOME_DETECTED/bin/java\"; ")
            append("[ -x \"\$JAVA_BIN\" ] || { echo 'ERROR=Selected JAVA_HOME has no executable Java runtime'; exit 66; }; ")
            append("else JAVA_BIN=\$(command -v java 2>/dev/null || true); [ -n \"\$JAVA_BIN\" ] || { echo 'ERROR=JDK not found in local Ubuntu'; exit 41; }; ")
            append("JAVA_REAL=\$(readlink -f \"\$JAVA_BIN\" 2>/dev/null || printf '%s' \"\$JAVA_BIN\"); ")
            append("JAVA_HOME_DETECTED=\$(dirname \"\$(dirname \"\$JAVA_REAL\")\"); ")
            // Java 8 can resolve java through <jdk>/jre/bin. Gradle needs the enclosing JDK.
            append("if [ ! -x \"\$JAVA_HOME_DETECTED/bin/javac\" ] && [ \"\${JAVA_HOME_DETECTED##*/}\" = jre ]; then JAVA_HOME_DETECTED=\$(dirname \"\$JAVA_HOME_DETECTED\"); fi; fi; ")
            append("JAVAC_BIN=\"\$JAVA_HOME_DETECTED/bin/javac\"; [ -x \"\$JAVAC_BIN\" ] || { echo 'ERROR=Java runtime found but the matching JDK compiler is missing'; exit 63; }; ")
            append("JAVA_SPEC=\$(\"\$JAVA_BIN\" -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.specification.version = //p' | head -1); ")
            append("[ -n \"\$JAVA_SPEC\" ] || { echo 'ERROR=Could not determine JDK version'; exit 42; }; ")
            append("case \"\$JAVA_SPEC\" in 1.*) JAVA_MAJOR=\${JAVA_SPEC#1.}; JAVA_MAJOR=\${JAVA_MAJOR%%.*};; *) JAVA_MAJOR=\${JAVA_SPEC%%.*};; esac; ")
            append("JAVAC_VERSION=\$(\"\$JAVAC_BIN\" -version 2>&1) || { echo 'ERROR=JDK compiler cannot execute'; exit 64; }; ")
            append("JAVAC_SPEC=\$(printf '%s\\n' \"\$JAVAC_VERSION\" | sed -n 's/^javac //p' | head -1); ")
            append("case \"\$JAVAC_SPEC\" in 1.*) JAVAC_MAJOR=\${JAVAC_SPEC#1.}; JAVAC_MAJOR=\${JAVAC_MAJOR%%.*};; *) JAVAC_MAJOR=\${JAVAC_SPEC%%.*};; esac; ")
            append("[ \"\$JAVA_MAJOR\" = \"\$JAVAC_MAJOR\" ] || { echo 'ERROR=Java runtime and JDK compiler versions do not match'; exit 65; }; ")
            append("SDK_ROOT=''; for p in \"\${ANDROID_HOME:-}\" \"\${ANDROID_SDK_ROOT:-}\" /opt/droide/android-sdk /opt/android-sdk /opt/android-sdk-linux /usr/lib/android-sdk /root/Android/Sdk /root/android-sdk; do ")
            append("[ -n \"\$p\" ] || continue; if [ -d \"\$p/platforms\" ] && [ -d \"\$p/build-tools\" ]; then SDK_ROOT=\$(readlink -f \"\$p\" 2>/dev/null || printf '%s' \"\$p\"); break; fi; done; ")
            append("[ -n \"\$SDK_ROOT\" ] || { echo 'ERROR=Android SDK not found in local Ubuntu'; exit 43; }; ")
            append("SDKS=\$(find \"\$SDK_ROOT/platforms\" -mindepth 1 -maxdepth 1 -type d -name 'android-*' -printf '%f\\n' 2>/dev/null | sed 's/^android-//' | grep -E '^[0-9]{1,3}$' | sort -n | paste -sd, -); ")
            append("BUILD_TOOLS=\$(find \"\$SDK_ROOT/build-tools\" -mindepth 1 -maxdepth 1 -type d -printf '%f\\n' 2>/dev/null | grep -E '^[0-9][A-Za-z0-9+_.-]{0,79}$' | sort -V | paste -sd, -); ")
            append("NDKS=\$(find \"\$SDK_ROOT/ndk\" -mindepth 1 -maxdepth 1 -type d -printf '%f\\n' 2>/dev/null | grep -E '^[0-9][A-Za-z0-9+_.-]{0,79}$' | sort -V | paste -sd, - || true); ")
            append("CMAKES=\$(find \"\$SDK_ROOT/cmake\" -mindepth 1 -maxdepth 1 -type d -printf '%f\\n' 2>/dev/null | grep -E '^[0-9][A-Za-z0-9+_.-]{0,79}$' | sort -V | paste -sd, - || true); ")
            if (requestedBuildTools.size == 1) {
                append("AAPT2=\"\$SDK_ROOT/build-tools/${escapeLiteral(requestedBuildTools.single())}/aapt2\"; ")
            } else {
                append("LATEST_BT=\$(printf '%s\\n' \"\$BUILD_TOOLS\" | tr ',' '\\n' | tail -1); AAPT2=\"\$SDK_ROOT/build-tools/\$LATEST_BT/aapt2\"; ")
            }
            append("[ -n \"\$SDKS\" ] || { echo 'ERROR=No Android SDK platform is installed'; exit 44; }; ")
            append("[ -n \"\$BUILD_TOOLS\" ] || { echo 'ERROR=No Android Build Tools are installed'; exit 45; }; ")
            append("[ -x \"\$AAPT2\" ] || { echo 'ERROR=AAPT2 is missing or not executable'; exit 46; }; ")
            append("BT_DIR=\$(dirname \"\$AAPT2\"); ZIPALIGN=\"\$BT_DIR/zipalign\"; APKSIGNER=\"\$BT_DIR/apksigner\"; ")
            append("[ -x \"\$ZIPALIGN\" ] || { echo 'ERROR=zipalign is missing or not executable'; exit 47; }; ")
            append("[ -x \"\$APKSIGNER\" ] || { echo 'ERROR=apksigner is missing or not executable'; exit 48; }; ")
            append("\"\$JAVA_BIN\" -version >/dev/null 2>&1 || { echo 'ERROR=JDK executable failed'; exit 49; }; ")
            append("\"\$AAPT2\" version >/dev/null 2>&1 || { echo 'ERROR=AAPT2 cannot execute in the ARM64 Ubuntu guest'; exit 50; }; ")
            append("\"\$ZIPALIGN\" -h >/dev/null 2>&1 || { code=\$?; [ \"\$code\" -eq 0 ] || [ \"\$code\" -eq 1 ] || { echo 'ERROR=zipalign cannot execute in the ARM64 Ubuntu guest'; exit 51; }; }; ")
            append("\"\$APKSIGNER\" version >/dev/null 2>&1 || { echo 'ERROR=apksigner cannot execute in the ARM64 Ubuntu guest'; exit 52; }; ")
            if (requestedNdks.isNotEmpty()) {
                requestedNdks.forEachIndexed { index, version ->
                    val safe = escapeLiteral(version)
                    append("NDK_CLANG_$index=\$(find \"\$SDK_ROOT/ndk/$safe/toolchains/llvm/prebuilt\" -mindepth 3 -maxdepth 3 -type f -name clang -print -quit 2>/dev/null || true); ")
                    append("[ -n \"\$NDK_CLANG_$index\" ] && [ -x \"\$NDK_CLANG_$index\" ] || { echo 'ERROR=NDK $safe host clang is missing'; exit 53; }; ")
                    append("\"\$NDK_CLANG_$index\" --version >/dev/null 2>&1 || { echo 'ERROR=NDK $safe host clang cannot execute in the ARM64 Ubuntu guest'; exit 54; }; ")
                }
            } else if (requirements.requiresNativeToolchain) {
                append("LATEST_NDK=\$(printf '%s\\n' \"\$NDKS\" | tr ',' '\\n' | tail -1); [ -n \"\$LATEST_NDK\" ] || { echo 'ERROR=No Android NDK is installed'; exit 55; }; ")
                append("NDK_CLANG=\$(find \"\$SDK_ROOT/ndk/\$LATEST_NDK/toolchains/llvm/prebuilt\" -mindepth 3 -maxdepth 3 -type f -name clang -print -quit 2>/dev/null || true); ")
                append("[ -n \"\$NDK_CLANG\" ] && [ -x \"\$NDK_CLANG\" ] || { echo 'ERROR=Android NDK host clang is missing'; exit 56; }; ")
                append("\"\$NDK_CLANG\" --version >/dev/null 2>&1 || { echo 'ERROR=Android NDK host clang cannot execute in the ARM64 Ubuntu guest'; exit 57; }; ")
            }
            if (requestedCmakes.isNotEmpty()) {
                requestedCmakes.forEach { version ->
                    val safe = escapeLiteral(version)
                    append("CMAKE_BIN=\"\$SDK_ROOT/cmake/$safe/bin/cmake\"; [ -x \"\$CMAKE_BIN\" ] || { echo 'ERROR=CMake $safe host executable is missing'; exit 58; }; ")
                    append("\"\$CMAKE_BIN\" --version >/dev/null 2>&1 || { echo 'ERROR=CMake $safe cannot execute in the ARM64 Ubuntu guest'; exit 59; }; ")
                }
            } else if (requirements.requiresCmake) {
                append("LATEST_CMAKE=\$(printf '%s\\n' \"\$CMAKES\" | tr ',' '\\n' | tail -1); [ -n \"\$LATEST_CMAKE\" ] || { echo 'ERROR=No Android CMake package is installed'; exit 60; }; ")
                append("CMAKE_BIN=\"\$SDK_ROOT/cmake/\$LATEST_CMAKE/bin/cmake\"; [ -x \"\$CMAKE_BIN\" ] || { echo 'ERROR=Android CMake host executable is missing'; exit 61; }; ")
                append("\"\$CMAKE_BIN\" --version >/dev/null 2>&1 || { echo 'ERROR=Android CMake cannot execute in the ARM64 Ubuntu guest'; exit 62; }; ")
            }
            append("printf 'JAVA_HOME=%s\\nJAVA_VERSION=%s\\nSDK_ROOT=%s\\nAAPT2=%s\\nZIPALIGN=%s\\nAPKSIGNER=%s\\nSDKS=%s\\nBUILD_TOOLS=%s\\nNDKS=%s\\nCMAKES=%s\\n' \"\$JAVA_HOME_DETECTED\" \"\$JAVA_MAJOR\" \"\$SDK_ROOT\" \"\$AAPT2\" \"\$ZIPALIGN\" \"\$APKSIGNER\" \"\$SDKS\" \"\$BUILD_TOOLS\" \"\$NDKS\" \"\$CMAKES\"; ")
        }
    }

    internal fun parseProbe(output: String, managedFamilies: Set<String> = emptySet()): Snapshot {
        val values = linkedMapOf<String, String>()
        output.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            val split = line.indexOf('=')
            if (split <= 0) return@forEach
            val key = line.substring(0, split)
            if (key in PROBE_KEYS) values[key] = line.substring(split + 1)
        }
        val javaHome = requireGuestPath(values.getValue("JAVA_HOME"), "JAVA_HOME")
        val sdkRoot = requireGuestPath(values.getValue("SDK_ROOT"), "SDK root")
        val aapt2 = requireGuestPath(values.getValue("AAPT2"), "AAPT2")
        val zipalign = requireGuestPath(values.getValue("ZIPALIGN"), "zipalign")
        val apksigner = requireGuestPath(values.getValue("APKSIGNER"), "apksigner")
        val buildToolsRoot = sdkRoot.trimEnd('/') + "/build-tools/"
        require(aapt2.startsWith(buildToolsRoot)) { "AAPT2 escaped the discovered SDK root" }
        require(zipalign.startsWith(buildToolsRoot)) { "zipalign escaped the discovered SDK root" }
        require(apksigner.startsWith(buildToolsRoot)) { "apksigner escaped the discovered SDK root" }
        val javaVersion = values.getValue("JAVA_VERSION").toIntOrNull()
            ?.takeIf { it in 8..99 } ?: error("Invalid local JDK version")
        val sdks = csv(values.getValue("SDKS")).mapNotNull(String::toIntOrNull).filter { it in 1..999 }.toSet()
        require(sdks.isNotEmpty()) { "Local Android SDK platform list is empty" }
        val buildTools = versionCsv(values.getValue("BUILD_TOOLS"), "Build Tools")
        return Snapshot(
            source = if (managedFamilies.any { it in MANAGED_ANDROID_FAMILIES }) Source.MANAGED_RECEIPTS else Source.GUEST_ENVIRONMENT,
            javaHome = javaHome,
            javaVersion = javaVersion,
            sdkRoot = sdkRoot,
            aapt2Path = aapt2,
            zipalignPath = zipalign,
            apksignerPath = apksigner,
            compileSdks = sdks,
            buildToolsVersions = buildTools,
            ndkVersions = versionCsv(values["NDKS"].orEmpty(), "NDK"),
            cmakeVersions = versionCsv(values["CMAKES"].orEmpty(), "CMake"),
            managedFamilies = managedFamilies.filterTo(linkedSetOf()) { it in MANAGED_ANDROID_FAMILIES },
        )
    }

    internal fun requirementsProblem(snapshot: Snapshot, requirements: AndroidToolchainRequirements): String? {
        val missingSdks = requirements.effectiveCompileSdks - snapshot.compileSdks
        if (missingSdks.isNotEmpty()) return "Local Ubuntu is missing Android SDK platform(s) ${missingSdks.sorted().joinToString()}"
        val missingBuildTools = requirements.effectiveBuildToolsVersions - snapshot.buildToolsVersions
        if (missingBuildTools.isNotEmpty()) return "Local Ubuntu is missing Build Tools ${missingBuildTools.sorted().joinToString()}"
        val missingNdks = requirements.effectiveNdkVersions - snapshot.ndkVersions
        if (missingNdks.isNotEmpty()) return "Local Ubuntu is missing NDK ${missingNdks.sorted().joinToString()}"
        val missingCmakes = requirements.effectiveCmakeVersions - snapshot.cmakeVersions
        if (missingCmakes.isNotEmpty()) return "Local Ubuntu is missing CMake ${missingCmakes.sorted().joinToString()}"
        if (requirements.requiresNativeToolchain && snapshot.ndkVersions.isEmpty()) return "Local Ubuntu has no Android NDK installed"
        if (requirements.requiresCmake && snapshot.cmakeVersions.isEmpty()) return "Local Ubuntu has no Android CMake package installed"
        return null
    }

    private fun csv(value: String): List<String> = value.split(',').map(String::trim).filter(String::isNotBlank).take(128)

    private fun versionCsv(value: String, label: String): Set<String> = csv(value).map { version ->
        require(version.matches(Regex("[0-9][A-Za-z0-9+_.-]{0,79}"))) { "Invalid local $label version: $version" }
        version
    }.toSet()

    private fun requireGuestPath(value: String, label: String): String {
        require(value.startsWith('/') && value.length in 2..500) { "Invalid local $label path" }
        require(value.none { it == '\u0000' || it == '\n' || it == '\r' }) { "Invalid local $label path" }
        require(value.split('/').none { it == "." || it == ".." }) { "Unsafe local $label path" }
        return value.trimEnd('/').ifBlank { "/" }
    }

    private fun escapeLiteral(value: String): String {
        require(value.matches(Regex("[0-9][A-Za-z0-9+_.-]{0,79}")))
        return value
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 20_000L
        val PROBE_KEYS = setOf("JAVA_HOME", "JAVA_VERSION", "SDK_ROOT", "AAPT2", "ZIPALIGN", "APKSIGNER", "SDKS", "BUILD_TOOLS", "NDKS", "CMAKES")
        val MANAGED_ANDROID_FAMILIES = setOf("toolchain.jdk", "sdk.android", "build.android-tools", "toolchain.android-ndk", "build.cmake")
    }
}

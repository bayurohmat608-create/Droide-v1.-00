package com.baystudio.droide.core

import java.io.File

internal object AndroidApkInspectionPolicy {
    fun packageName(output: String): String {
        require(output.length <= 4096) { "APK package-name response is too large" }
        val lines = output.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        require(lines.size == 1) { "AAPT2 returned an ambiguous package-name response" }
        return lines.single().also { name ->
            require(name.length <= 255 && name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "Invalid APK package name" }
        }
    }

    suspend fun inspect(apk: File, local: suspend (File) -> String?, workstation: suspend (File) -> String): String =
        local(apk)?.let(::packageName) ?: packageName(workstation(apk))

    fun guestSnapshotPath(runtimeTemporaryRoot: File, snapshot: File): String {
        require(snapshot.isFile && !PathSecurity.isSymbolicLink(snapshot) && PathSecurity.contains(runtimeTemporaryRoot, snapshot)) { "APK snapshot escaped runtime temporary storage" }
        val relative = snapshot.canonicalFile.relativeTo(runtimeTemporaryRoot.canonicalFile).invariantSeparatorsPath
        require(relative.startsWith("apk-runtime/") && relative.none { it.code < 0x20 || it.code == 0x7f }) { "Invalid runtime APK snapshot path" }
        return requireGuestSnapshotPath("/tmp/$relative")
    }

    fun requireGuestSnapshotPath(path: String): String = path.also {
        require(it.startsWith("/tmp/apk-runtime/") && it.endsWith(".apk") && it.length <= 4096 &&
            it.none { c -> c.code < 0x20 || c.code == 0x7f } && '\\' !in it &&
            it.removePrefix("/").split('/').none { part -> part in setOf("", ".", "..") }) { "Invalid guest APK snapshot path" }
    }
}

internal object LocalAndroidApkInspectionCommand {
    const val UNAVAILABLE_EXIT = 77

    fun create(guestApk: String): String {
        AndroidApkInspectionPolicy.requireGuestSnapshotPath(guestApk)
        return """
            set -eu
            AAPT2=''
            for sdk in "${'$'}{ANDROID_HOME:-}" "${'$'}{ANDROID_SDK_ROOT:-}" /opt/droide/android-sdk /opt/android-sdk /opt/android-sdk-linux /usr/lib/android-sdk /root/Android/Sdk /root/android-sdk; do
                [ -n "${'$'}sdk" ] && [ -d "${'$'}sdk/build-tools" ] || continue
                versions=${'$'}(find "${'$'}sdk/build-tools" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | grep -E '^[0-9][A-Za-z0-9+_.-]{0,79}${'$'}' | sort -Vr)
                for version in ${'$'}versions; do
                    candidate="${'$'}sdk/build-tools/${'$'}version/aapt2"
                    if [ -x "${'$'}candidate" ] && "${'$'}candidate" version >/dev/null 2>&1; then AAPT2="${'$'}candidate"; break; fi
                done
                [ -z "${'$'}AAPT2" ] || break
            done
            if [ -z "${'$'}AAPT2" ]; then
                candidate=${'$'}(command -v aapt2 2>/dev/null || true)
                if [ -n "${'$'}candidate" ] && "${'$'}candidate" version >/dev/null 2>&1; then AAPT2="${'$'}candidate"; fi
            fi
            [ -n "${'$'}AAPT2" ] || { echo 'Local executable AAPT2 is unavailable'; exit $UNAVAILABLE_EXIT; }
            "${'$'}AAPT2" dump packagename ${LocalExecutionSubstrate.shellQuote(guestApk)}
        """.trimIndent()
    }
}

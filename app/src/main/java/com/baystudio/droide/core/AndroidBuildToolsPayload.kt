package com.baystudio.droide.core

import java.io.File


internal object AndroidBuildToolsPayload {
    val architectureNeutralFiles = setOf(
        "apksigner", "d8", "lib/apksigner.jar", "lib/d8.jar", "core-lambda-stubs.jar",
        "source.properties", "runtime.properties", "NOTICE.txt",
    )
    // sdklib validates these paths even for the AAPT2 build path. Preserve the original Google
    // bytes as non-executable compatibility files; legacy AAPT/dexdump execution is unsupported.
    val compatibilityFiles = setOf("aapt", "dexdump")
    val requiredFiles = architectureNeutralFiles + compatibilityFiles
    val archiveFiles = requiredFiles + "package.xml" // sdkmanager may generate this; ZIP need not contain it.

    fun requireGoogleFiles(root: File) {
        val missing = requiredFiles.filterNot { File(root, it).isFile && !PathSecurity.isSymbolicLink(File(root, it)) }
        require(missing.isEmpty()) { "Google Build Tools archive is missing required payload files: ${missing.joinToString()}" }
    }

    fun activateLaunchers(root: File) {
        for (name in listOf("apksigner", "d8")) {
            val file = requireLauncher(root, name)
            check(file.setExecutable(true, false) || file.canExecute()) { "Could not mark Google Build Tools $name launcher executable" }
        }
        for (name in compatibilityFiles) {
            val file = File(root, name)
            require(file.isFile && !PathSecurity.isSymbolicLink(file)) { "Google Build Tools compatibility file $name is missing" }
            check(file.setExecutable(false, false) && !file.canExecute()) { "Could not disable unsupported Google host tool $name" }
        }
    }

    fun verifyLaunchers(root: File): Boolean = runCatching {
        listOf("apksigner", "d8").all { requireLauncher(root, it).canExecute() } &&
            compatibilityFiles.all { name ->
                val file = File(root, name)
                file.isFile && !PathSecurity.isSymbolicLink(file) && !file.canExecute()
            }
    }.getOrDefault(false)

    private fun requireLauncher(root: File, name: String): File {
        val file = File(root, name)
        require(file.isFile && !PathSecurity.isSymbolicLink(file) && file.length() in 20L..8_192L) {
            "Google Build Tools $name launcher is unavailable"
        }
        // The pinned scripts begin with an Apache license block. The jar identity is below that
        // block, outside the first 256 bytes. Read the complete bounded script, not a partial read.
        val script = file.readText(Charsets.UTF_8)
        require(script.startsWith("#!/bin/bash\n") && script.lineSequence().any { it == "jarfile=$name.jar" }) {
            "Google Build Tools $name launcher identity check failed"
        }
        return file
    }
}

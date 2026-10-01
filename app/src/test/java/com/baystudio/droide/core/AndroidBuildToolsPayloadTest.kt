package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AndroidBuildToolsPayloadTest {
    private fun root(): File = Files.createTempDirectory("build-tools-payload").toFile()
    private fun launchers(root: File) {
        for (name in listOf("apksigner", "d8")) {
            File(root, name).writeText("#!/bin/bash\n# " + "license text ".repeat(40) + "\njarfile=$name.jar\nexec java -jar \"\$jarfile\" \"\$@\"\n")
        }
        for (name in AndroidBuildToolsPayload.compatibilityFiles) File(root, name).writeBytes(byteArrayOf(0x7f,69,76,70))
    }
    @Test fun packageXmlIsOptionalWhileJarPayloadIsRequired() {
        val root = root()
        try {
            for (name in AndroidBuildToolsPayload.requiredFiles) File(root, name).apply { parentFile!!.mkdirs(); writeText("payload") }
            assertFalse(File(root, "package.xml").exists())
            AndroidBuildToolsPayload.requireGoogleFiles(root)
            File(root, "lib/d8.jar").delete()
            assertThrows(IllegalArgumentException::class.java) { AndroidBuildToolsPayload.requireGoogleFiles(root) }
        } finally { root.deleteRecursively() }
    }
    @Test fun validLongLicenseHeaderDoesNotHideJarIdentity() {
        val root = root()
        try {
            launchers(root)
            assertFalse(File(root, "apksigner").readBytes().take(256).toByteArray().toString(Charsets.UTF_8).contains("apksigner"))
            val original = File(root, "apksigner").readBytes()
            AndroidBuildToolsPayload.activateLaunchers(root)
            assertTrue(AndroidBuildToolsPayload.verifyLaunchers(root))
            assertArrayEquals(original, File(root, "apksigner").readBytes())
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectsWrongJarAndSymlinkLaunchers() {
        val root = root()
        try {
            launchers(root)
            File(root, "apksigner").writeText("#!/bin/bash\njarfile=wrong.jar\n")
            assertThrows(IllegalArgumentException::class.java) { AndroidBuildToolsPayload.activateLaunchers(root) }
            File(root, "apksigner").delete()
            Files.createSymbolicLink(File(root, "apksigner").toPath(), File(root, "d8").toPath())
            assertThrows(IllegalArgumentException::class.java) { AndroidBuildToolsPayload.activateLaunchers(root) }
        } finally { root.deleteRecursively() }
    }
    @Test fun unsupportedHostToolsRemainByteIdenticalAndNonExecutable() {
        val root = root()
        try {
            launchers(root)
            val before = AndroidBuildToolsPayload.compatibilityFiles.associateWith { File(root, it).readBytes() }
            for (name in before.keys) File(root, name).setExecutable(true, false)
            AndroidBuildToolsPayload.activateLaunchers(root)
            for ((name, bytes) in before) {
                assertFalse(File(root, name).canExecute())
                assertArrayEquals(bytes, File(root, name).readBytes())
            }
            assertTrue(AndroidBuildToolsPayload.verifyLaunchers(root))
            File(root, "dexdump").delete()
            assertFalse(AndroidBuildToolsPayload.verifyLaunchers(root))
        } finally { root.deleteRecursively() }
    }
}

package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidApkInspectionPolicyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun packageResponseMustIdentifyExactlyOneTarget() {
        assertEquals("com.example.app", AndroidApkInspectionPolicy.packageName("\n com.example.app\r\n\n"))
        listOf("", "com.example.app\ncom.other.app", "warning\ncom.example.app", "com.app;id", "com.app\u0000", "x".repeat(4_097)).forEach {
            assertThrows(IllegalArgumentException::class.java) { AndroidApkInspectionPolicy.packageName(it) }
        }
    }

    @Test fun localInspectionAvoidsASecondWorkstationDependency() { runBlocking {
        val apk = temp.newFile("local.apk")
        var remoteCalls = 0
        val name = AndroidApkInspectionPolicy.inspect(apk, { "com.example.local" }, { remoteCalls++; "com.example.remote" })
        assertEquals("com.example.local", name)
        assertEquals(0, remoteCalls)
    } }

    @Test fun unavailableLocalInspectionFallsBackOnce() { runBlocking {
        var remoteCalls = 0
        val name = AndroidApkInspectionPolicy.inspect(temp.newFile("fallback.apk"), { null }, { remoteCalls++; "com.example.remote" })
        assertEquals("com.example.remote", name)
        assertEquals(1, remoteCalls)
    } }

    @Test fun invalidLocalResponseDoesNotSilentlyChooseAnotherTarget() {
        var remoteCalls = 0
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            AndroidApkInspectionPolicy.inspect(temp.newFile("invalid.apk"), { "com.one.app\ncom.other.app" }, { remoteCalls++; "com.example.remote" })
        } }
        assertEquals(0, remoteCalls)
    }

    @Test fun mappingRejectsFilesOutsideTheRuntimeAndTraversal() {
        val runtime = temp.newFolder("runtime")
        val staged = File(runtime, "apk-runtime/session-one/artifact.apk")
        staged.parentFile.mkdirs(); staged.writeText("snapshot")
        assertEquals("/tmp/apk-runtime/session-one/artifact.apk", AndroidApkInspectionPolicy.guestSnapshotPath(runtime, staged))
        assertThrows(IllegalArgumentException::class.java) { AndroidApkInspectionPolicy.guestSnapshotPath(runtime, temp.newFile("outside.apk")) }
        val link = File(staged.parentFile, "link.apk")
        Files.createSymbolicLink(link.toPath(), staged.toPath())
        assertThrows(IllegalArgumentException::class.java) { AndroidApkInspectionPolicy.guestSnapshotPath(runtime, link) }
        listOf("/tmp/apk-runtime/../outside.apk", "/tmp/apk-runtime/session//artifact.apk", "/tmp/apk-runtime/session/./artifact.apk", "/tmp/apk-runtime/session/file.txt", "/tmp/apk-runtime/session/file.apk\n").forEach {
            assertThrows(IllegalArgumentException::class.java) { LocalAndroidApkInspectionCommand.create(it) }
        }
    }

    private fun aapt2(sdk: File, version: String, versionExit: Int, log: File): File {
        val binary = File(sdk, "build-tools/$version/aapt2")
        binary.parentFile.mkdirs()
        binary.writeText("#!/bin/sh\nif [ \"\$1\" = version ]; then exit $versionExit; fi\n" +
            "[ \"\$1\" = dump ] && [ \"\$2\" = packagename ] || exit 23\n" +
            "printf '%s' \"\$3\" > ${LocalExecutionSubstrate.shellQuote(log.path)}\nprintf '%s\\n' 'com.example.app'\n")
        check(binary.setExecutable(true))
        return binary
    }

    private fun inspect(sdk: File, path: String, secondary: File? = null): String {
        assumeTrue(File("/bin/sh").canExecute())
        val process = ProcessBuilder("/bin/sh", "-c", LocalAndroidApkInspectionCommand.create(path)).redirectErrorStream(true)
            .apply { environment()["ANDROID_HOME"] = sdk.path; environment()["ANDROID_SDK_ROOT"] = secondary?.path ?: "" }.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
        return AndroidApkInspectionPolicy.packageName(output)
    }

    @Test fun aBrokenNewestVersionDoesNotHideExecutableOlderBuildTools() {
        val sdk = temp.newFolder("SDK with spaces")
        val chosen = temp.newFile("chosen")
        aapt2(sdk, "36.0.0", 126, temp.newFile("broken"))
        aapt2(sdk, "35.0.0", 0, chosen)
        val path = "/tmp/apk-runtime/session-odd/artifact ' \$(id).apk"
        assertEquals("com.example.app", inspect(sdk, path))
        assertEquals(path, chosen.readText())
    }

    @Test fun newestExecutableVersionIsPreferred() {
        val sdk = temp.newFolder("sdk")
        val newer = temp.newFile("newer")
        val older = temp.newFile("older")
        aapt2(sdk, "35.0.0", 0, older)
        aapt2(sdk, "36.0.0", 0, newer)
        inspect(sdk, "/tmp/apk-runtime/session-one/artifact.apk")
        assertTrue(newer.readText().isNotEmpty())
        assertEquals("", older.readText())
    }

    @Test fun aSecondSelectedSdkIsTriedWhenTheFirstCannotExecute() {
        val sdk = temp.newFolder("first")
        val secondary = temp.newFolder("second")
        aapt2(sdk, "36.0.0", 126, temp.newFile("first-log"))
        val chosen = temp.newFile("second-log")
        aapt2(secondary, "36.0.0", 0, chosen)
        inspect(sdk, "/tmp/apk-runtime/session-one/artifact.apk", secondary)
        assertTrue(chosen.readText().isNotEmpty())
    }
}

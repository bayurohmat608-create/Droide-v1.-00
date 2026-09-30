package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBuildArtifactCollectorTest {
    private val workspace = "/data/local/tmp/droide/workspaces/demo"

    @Test fun nonArtifactTaskNeverEnumeratesOldOutputs() = runBlocking<Unit> {
        val cache = Files.createTempDirectory("droide-artifact-test").toFile()
        try {
            var shellCalls = 0
            val collector = AndroidBuildArtifactCollector(cache, { _, _ ->
                shellCalls++
                BridgeShellResult(0, "", "")
            }, { _, _ -> error("pull must not run") })
            assertTrue(collector.collect(workspace, "test", 1L).isEmpty())
            assertEquals(0, shellCalls)
        } finally { cache.deleteRecursively() }
    }

    @Test fun successfulCollectionPreservesModulePath() = runBlocking<Unit> {
        val cache = Files.createTempDirectory("droide-artifact-test").toFile()
        try {
            val remote = "$workspace/app/build/outputs/apk/debug/app-debug.apk"
            val collector = AndroidBuildArtifactCollector(cache, { _, _ -> BridgeShellResult(0, "$remote\n", "") }, { _, local ->
                local.parentFile?.mkdirs(); local.writeText("APK")
            })
            val result = collector.collect(workspace, ":app:assembleDebug", 2L)
            assertEquals(1, result.size)
            assertTrue(result.single().invariantSeparatorsPath.contains("app/build/outputs/apk/debug/app-debug.apk"))
        } finally { cache.deleteRecursively() }
    }

    @Test fun listingAndPullFailuresAreFailClosedAndPartialCacheIsRemoved() = runBlocking<Unit> {
        val cache = Files.createTempDirectory("droide-artifact-test").toFile()
        try {
            val remote = "$workspace/app/build/outputs/apk/debug/app-debug.apk"
            val truncated = AndroidBuildArtifactCollector(cache, { _, _ -> BridgeShellResult(0, remote, "", truncated = true) }, { _, _ -> })
            assertThrows(IllegalStateException::class.java) { runBlocking { truncated.collect(workspace, ":app:assembleDebug", 3L) } }

            val failed = AndroidBuildArtifactCollector(cache, { _, _ -> BridgeShellResult(0, remote, "") }, { _, local ->
                local.parentFile?.mkdirs(); local.writeText("partial"); throw IOException("pull failed")
            })
            assertThrows(IOException::class.java) { runBlocking { failed.collect(workspace, ":app:assembleDebug", 4L) } }
            val buildRoot = File(cache, "android-build-artifacts")
            assertFalse(buildRoot.walkTopDown().any { it.isFile && it.readText() == "partial" })
        } finally { cache.deleteRecursively() }
    }

    @Test fun artifactCountOverflowIsRejected() = runBlocking<Unit> {
        val cache = Files.createTempDirectory("droide-artifact-test").toFile()
        try {
            val listing = (0..40).joinToString("\n") { i -> "$workspace/module$i/build/outputs/apk/debug/module$i-debug.apk" }
            val collector = AndroidBuildArtifactCollector(cache, { _, _ -> BridgeShellResult(0, listing, "") }, { _, _ -> error("pull must not run") })
            assertThrows(IllegalArgumentException::class.java) { runBlocking { collector.collect(workspace, "assembleDebug", 5L) } }
        } finally { cache.deleteRecursively() }
    }
}

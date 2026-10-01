package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalAndroidBuildArtifactCollectorTest {
    @Test fun returnsOnlyRequestedModuleAndVariant() {
        val root = Files.createTempDirectory("droide-local-artifact").toFile()
        try {
            val good = root.resolve("app/build/outputs/apk/debug/app-debug.apk").apply { parentFile.mkdirs(); writeText("apk") }
            root.resolve("app/build/outputs/apk/release/app-release.apk").apply { parentFile.mkdirs(); writeText("release") }
            root.resolve("other/build/outputs/apk/debug/other-debug.apk").apply { parentFile.mkdirs(); writeText("other") }
            assertEquals(listOf(good.canonicalFile), LocalAndroidBuildArtifactCollector.collect(root, ":app:assembleDebug", listOf(good.parentFile)))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun emptyArtifactsAreRejected() {
        val root = Files.createTempDirectory("droide-local-artifact").toFile()
        try {
            root.resolve("app/build/outputs/apk/debug/app-debug.apk").apply { parentFile.mkdirs(); createNewFile() }
            assertThrows(IllegalArgumentException::class.java) {
                LocalAndroidBuildArtifactCollector.collect(root, ":app:assembleDebug", listOf(root.resolve("app/build/outputs/apk/debug")))
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun staleArtifactsFromEarlierBuildAreIgnored() {
        val root = Files.createTempDirectory("droide-local-artifact-stale").toFile()
        try {
            val stale = root.resolve("app/build/outputs/apk/debug/app-debug.apk").apply {
                parentFile.mkdirs(); writeText("old"); setLastModified(1_000L)
            }
            assertEquals(emptyList<java.io.File>(), LocalAndroidBuildArtifactCollector.collect(root, ":app:assembleDebug", emptyList()))
            assertEquals("old", stale.readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

}

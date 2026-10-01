package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LocalAndroidBuildEvidenceTest {
    @Test fun upToDateAndCacheHitArtifactsRemainDiscoverableWithOldTimestamps() = withFixture { root ->
        val session = LocalAndroidBuildEvidence.prepare(root)
        val apk = root.resolve("app/build/outputs/apk/debug/app-debug.apk").apply {
            parentFile.mkdirs(); writeText("fixture"); setLastModified(1000)
        }
        for (outcome in listOf("UP-TO-DATE", "FROM-CACHE", "EXECUTED")) {
            session.manifest.writeText("$outcome\t${apk.parentFile.canonicalPath}\n")
            val roots = LocalAndroidBuildEvidence.readOutputRoots(root, session.manifest)
            assertEquals(listOf(apk.canonicalFile), LocalAndroidBuildArtifactCollector.collect(root, ":app:assembleDebug", roots))
        }
    }

    @Test fun skippedTaskCannotAdmitOldArtifacts() = withFixture { root ->
        val session = LocalAndroidBuildEvidence.prepare(root)
        session.manifest.writeText("SKIPPED\t${root.canonicalPath}\n")
        assertThrows(IllegalArgumentException::class.java) { LocalAndroidBuildEvidence.readOutputRoots(root, session.manifest) }
    }

    @Test fun evidenceCannotAdmitOutsideFiles() = withFixture { root ->
        val session = LocalAndroidBuildEvidence.prepare(root)
        session.manifest.writeText("EXECUTED\t${root.parentFile.canonicalPath}\n")
        assertThrows(IllegalArgumentException::class.java) { LocalAndroidBuildEvidence.readOutputRoots(root, session.manifest) }
    }

    @Test fun eachInvocationHasAFreshManifestAndIndependentDirectory() = withFixture { root ->
        val first = LocalAndroidBuildEvidence.prepare(root)
        first.manifest.writeText("old evidence")
        val second = LocalAndroidBuildEvidence.prepare(root)
        assertNotEquals(first.directory, second.directory)
        assertEquals("", second.manifest.readText())
    }

    private inline fun withFixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-build-evidence-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

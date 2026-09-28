package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugCertificationEvidenceStoreTest {
    @Test fun persistsReadsAndExportsValidatedEvidence() {
        val root = Files.createTempDirectory("droide-debug-evidence").toFile()
        try {
            val store = DebugCertificationEvidenceStore.forTesting(root)
            val report = passReport()
            store.persist(report)

            assertEquals(report, store.latest())
            val output = ByteArrayOutputStream()
            assertEquals(report, store.exportLatest(output))
            assertTrue(output.toString(Charsets.UTF_8.name()).contains(report.evidenceDigestSha256))
            assertTrue(root.listFiles().orEmpty().count { it.extension == "json" } >= 2)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsTamperedLatestEvidence() {
        val root = Files.createTempDirectory("droide-debug-evidence-tamper").toFile()
        try {
            val store = DebugCertificationEvidenceStore.forTesting(root)
            val report = passReport()
            store.persist(report)
            val latest = root.resolve("latest.json")
            latest.writeText(latest.readText().replace("Phone X", "Phone Y"))
            assertNull(store.latest())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun passReport(): DebugCertificationReport {
        val properties = linkedMapOf(
            "ro.product.manufacturer" to "Example",
            "ro.product.model" to "Phone X",
            "ro.product.device" to "phonex",
            "ro.build.version.sdk" to "36",
            "ro.product.cpu.abilist" to "arm64-v8a",
            "ro.build.fingerprint" to "example/phonex/phonex:16/BP2A.123/42:user/release-keys",
            "ro.kernel.qemu" to "0",
            "ro.boot.qemu" to "0",
            "ro.hardware" to "qcom",
            "ro.boot.hardware" to "qcom",
            "ro.product.name" to "phonex_global",
        )
        val identity = AndroidDeviceIdentityEvidence.fromProperties(properties, "127.0.0.1:37123/TLS_CONNECT")
        val unsigned = DebugCertificationReport(
            generatedAtEpochMs = 123456789L,
            packageName = "com.example.debuggable",
            apkSha256 = "a".repeat(64),
            pid = 1234,
            activeFile = "app/src/main/java/com/example/MainActivity.kt",
            breakpointLine = 42,
            adapterName = "test-adapter",
            device = identity,
            steps = DebugCertificationReport.REQUIRED_STEPS.map { DebugCertificationStep(it, "PASS", 1, "ok") },
        )
        return unsigned.copy(evidenceDigestSha256 = DebugCertificationReport.computeEvidenceDigest(unsigned))
    }
}

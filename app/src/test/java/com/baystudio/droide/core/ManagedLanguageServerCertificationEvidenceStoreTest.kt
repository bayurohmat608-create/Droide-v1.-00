package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedLanguageServerCertificationEvidenceStoreTest {
    @Test fun persistsReadsAndExportsOnlyValidatedPassEvidence() {
        val root = Files.createTempDirectory("droide-lsp-evidence").toFile()
        try {
            val store = ManagedLanguageServerCertificationEvidenceStore.forTesting(root)
            val report = passReport("rust-analyzer")
            store.persist(report)

            assertEquals(report, store.latest("rust-analyzer"))
            val output = ByteArrayOutputStream()
            assertEquals(report, store.exportLatest("rust-analyzer", output))
            assertTrue(output.toString(Charsets.UTF_8.name()).contains(report.evidenceDigestSha256))
            val serverRoot = root.resolve("rust-analyzer")
            assertTrue(serverRoot.listFiles().orEmpty().count { it.extension == "json" } >= 2)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun failedReportCannotBecomeReleaseEvidence() {
        val root = Files.createTempDirectory("droide-lsp-evidence-fail").toFile()
        try {
            val store = ManagedLanguageServerCertificationEvidenceStore.forTesting(root)
            val failedChecks = ManagedLanguageServerCertificationReport.REQUIRED_CHECKS.map { id ->
                ManagedPackageCertificationCheck(id, if (id == "protocol") "FAIL" else "PASS", 1, "fixture")
            }
            val failed = passReport("rust-analyzer")
                .copy(checks = failedChecks, evidenceDigestSha256 = "")
                .withEvidenceDigest()
            assertThrows(IllegalArgumentException::class.java) { store.persist(failed) }
            assertNull(store.latest("rust-analyzer"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsTamperedOrCrossServerEvidence() {
        val root = Files.createTempDirectory("droide-lsp-evidence-tamper").toFile()
        try {
            val store = ManagedLanguageServerCertificationEvidenceStore.forTesting(root)
            val report = passReport("rust-analyzer")
            store.persist(report)
            val latest = root.resolve("rust-analyzer/latest.json")
            latest.writeText(latest.readText().replace("arm64-v8a", "x86_64"))
            assertNull(store.latest("rust-analyzer"))
            assertNull(store.latest("gopls"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun passReport(languageServerId: String): ManagedLanguageServerCertificationReport {
        val unsigned = ManagedLanguageServerCertificationReport(
            generatedAtEpochMs = 123456789L,
            artifactSha256 = "a".repeat(64),
            familyId = "lsp.$languageServerId",
            version = "1.0.0",
            abi = "arm64-v8a",
            androidApi = 36,
            physicalDevice = true,
            deviceFingerprint = "example/device/device:16/BP2A.123/42:user/release-keys",
            languageServerId = languageServerId,
            deviceIdentityPropertiesSha256 = "b".repeat(64),
            bridgeEndpoint = "127.0.0.1:37123/TLS_CONNECT",
            checks = ManagedLanguageServerCertificationReport.REQUIRED_CHECKS.map {
                ManagedPackageCertificationCheck(it, "PASS", 1, "fixture")
            },
        )
        return unsigned.withEvidenceDigest()
    }
}

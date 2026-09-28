package com.baystudio.droide.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedLanguageServerCertificationReportTest {
    private fun report(outcome: String = "PASS") = ManagedLanguageServerCertificationReport(
        generatedAtEpochMs = 1,
        artifactSha256 = "a".repeat(64),
        familyId = "lsp.gopls",
        version = "0.23.0",
        abi = "arm64-v8a",
        androidApi = 36,
        physicalDevice = true,
        deviceFingerprint = "example/device/release-keys",
        languageServerId = "gopls",
        deviceIdentityPropertiesSha256 = "b".repeat(64),
        bridgeEndpoint = "127.0.0.1:5555/TLS_PAIRING",
        checks = ManagedLanguageServerCertificationReport.REQUIRED_CHECKS.map {
            ManagedPackageCertificationCheck(it, outcome, 1, "ok")
        },
    ).withEvidenceDigest()

    @Test fun completePassEvidenceIsPromotionCompatible() {
        val report = report()
        report.validate()
        assertTrue(report.passed)
    }

    @Test fun failedCheckRemainsValidEvidenceButDoesNotPass() {
        val checks = report().checks.toMutableList().apply { this[4] = this[4].copy(outcome = "FAIL") }
        val failed = report().copy(checks = checks, evidenceDigestSha256 = "").withEvidenceDigest()
        failed.validate()
        assertFalse(failed.passed)
    }

    @Test fun tamperingAndNonPhysicalReportsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            report().copy(version = "tampered").validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            report().copy(physicalDevice = false, evidenceDigestSha256 = "").withEvidenceDigest()
        }
    }
}

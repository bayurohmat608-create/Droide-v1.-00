package com.baystudio.droide.core

import org.junit.Assert.assertThrows
import org.junit.Test

class DebugCertificationReportTest {
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
            generatedAtEpochMs = 1,
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

    @Test fun acceptsCompletePassEvidence() {
        passReport().validate()
    }

    @Test fun rejectsMissingOrFailedEvidence() {
        assertThrows(IllegalArgumentException::class.java) {
            val changed = passReport().copy(steps = passReport().steps.dropLast(1))
            changed.copy(evidenceDigestSha256 = DebugCertificationReport.computeEvidenceDigest(changed)).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            val original = passReport()
            val broken = original.steps.toMutableList()
            broken[3] = broken[3].copy(outcome = "FAIL")
            val changed = original.copy(steps = broken)
            changed.copy(evidenceDigestSha256 = DebugCertificationReport.computeEvidenceDigest(changed)).validate()
        }
    }

    @Test fun rejectsEmulatorOrTamperedEvidence() {
        assertThrows(IllegalArgumentException::class.java) {
            val original = passReport()
            val changed = original.copy(device = original.device.copy(physical = false))
            changed.copy(evidenceDigestSha256 = DebugCertificationReport.computeEvidenceDigest(changed)).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            val original = passReport()
            original.copy(device = original.device.copy(model = "Modified after signing")).validate()
        }
    }
}

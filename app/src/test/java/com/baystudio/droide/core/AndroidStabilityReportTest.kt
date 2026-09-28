package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidStabilityReportTest {
    private fun identity(physical: Boolean = true): AndroidDeviceIdentitySnapshot =
        AndroidDeviceIdentityRules.fromProperties(
            AndroidDeviceIdentityTestFixtures.physicalProperties(),
            "127.0.0.1:5555/TLS_PAIRING",
        ).copy(physical = physical)

    private fun report(pss: List<Long>, threads: List<Int>): AndroidStabilityReport = AndroidStabilityReport(
        generatedAtEpochMs = 1,
        packageName = "com.example.app",
        sampleIntervalMs = 1_000,
        device = identity(),
        samples = pss.indices.map { index ->
            AndroidStabilitySample(index * 1_000L, 1234, pss[index], null, null, 1.0, threads[index])
        },
    ).withEvidenceDigest()

    @Test fun evaluatesReviewedGrowthPolicy() {
        val policy = AndroidStabilityPolicy(minimumSamples = 3, maximumPssGrowthKb = 2_000, maximumThreadGrowth = 2)
        val good = report(listOf(10_000, 10_500, 11_000), listOf(20, 20, 21)).evaluate(policy)
        assertTrue(good.passed)
        assertEquals(1_000, good.pssGrowthKb)
        val bad = report(listOf(10_000, 11_000, 14_000), listOf(20, 21, 25)).evaluate(policy)
        assertFalse(bad.passed)
    }

    @Test fun rejectsTamperedOrNonPhysicalEvidence() {
        assertThrows(IllegalArgumentException::class.java) {
            report(listOf(10_000, 10_500, 11_000), listOf(20, 20, 21)).copy(packageName = "com.tampered.app").validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            report(listOf(10_000, 10_500, 11_000), listOf(20, 20, 21)).copy(device = identity(physical = false)).validate()
        }
    }

    @Test fun rejectsPidChangeInsideOneRun() {
        val base = report(listOf(10_000, 10_500, 11_000), listOf(20, 20, 21))
        val changed = base.samples.toMutableList().also { it[2] = it[2].copy(pid = 9999) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(samples = changed).validate(requireDigest = false) }
    }
}

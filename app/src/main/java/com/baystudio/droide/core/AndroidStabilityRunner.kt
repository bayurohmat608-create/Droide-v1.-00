package com.baystudio.droide.core

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable
data class AndroidStabilitySample(
    val elapsedMs: Long,
    val pid: Int,
    val totalPssKb: Long?,
    val nativeHeapKb: Long?,
    val javaHeapKb: Long?,
    val cpuPercent: Double?,
    val threadCount: Int?,
)

@Serializable
data class AndroidStabilityPolicy(
    val minimumSamples: Int = 20,
    val maximumPssGrowthKb: Long,
    val maximumThreadGrowth: Int,
) {
    fun validate() {
        require(minimumSamples in 3..10_000) { "Invalid minimum stability sample count" }
        require(maximumPssGrowthKb in 0..16L * 1024 * 1024) { "Invalid PSS growth limit" }
        require(maximumThreadGrowth in 0..100_000) { "Invalid thread growth limit" }
    }
}

@Serializable
data class AndroidStabilityReport(
    val schema: Int = 2,
    val generatedAtEpochMs: Long,
    val packageName: String,
    val sampleIntervalMs: Long,
    val device: AndroidDeviceIdentitySnapshot,
    val samples: List<AndroidStabilitySample>,
    val evidenceDigestSha256: String = "",
) {
    fun validate(requireDigest: Boolean = true) {
        require(schema == 2) { "Unsupported stability report schema" }
        require(generatedAtEpochMs > 0) { "Invalid stability report timestamp" }
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"))) { "Invalid package name" }
        require(sampleIntervalMs in 250..3_600_000) { "Invalid stability sample interval" }
        device.validate()
        require(device.physical) { "Stability evidence requires a physical Android device" }
        require(samples.size in 1..10_000) { "Invalid stability sample count" }
        require(samples.zipWithNext().all { (a, b) -> a.elapsedMs <= b.elapsedMs }) { "Stability samples are out of order" }
        require(samples.all { it.pid > 0 && it.elapsedMs >= 0 }) { "Invalid stability sample" }
        require(samples.map { it.pid }.distinct().size == 1) { "Stability samples changed process pid during one evidence run" }
        require(samples.all { sample ->
            (sample.totalPssKb == null || sample.totalPssKb >= 0) &&
                (sample.nativeHeapKb == null || sample.nativeHeapKb >= 0) &&
                (sample.javaHeapKb == null || sample.javaHeapKb >= 0) &&
                (sample.cpuPercent == null || sample.cpuPercent in 0.0..10_000.0) &&
                (sample.threadCount == null || sample.threadCount >= 0)
        }) { "Invalid stability sample metric" }
        if (requireDigest) {
            require(evidenceDigestSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid stability evidence digest" }
            require(evidenceDigestSha256 == evidenceDigest(this)) { "Stability evidence digest mismatch" }
        }
    }

    fun withEvidenceDigest(): AndroidStabilityReport {
        validate(requireDigest = false)
        return copy(evidenceDigestSha256 = evidenceDigest(this)).also { it.validate() }
    }

    fun evaluate(policy: AndroidStabilityPolicy): AndroidStabilityEvaluation {
        validate()
        policy.validate()
        require(samples.size >= policy.minimumSamples) { "Not enough stability samples for this policy" }
        val pss = samples.mapNotNull { it.totalPssKb }
        val threads = samples.mapNotNull { it.threadCount }
        require(pss.size >= policy.minimumSamples) { "Not enough PSS samples" }
        require(threads.size >= policy.minimumSamples) { "Not enough thread-count samples" }
        val pssGrowth = (pss.last() - pss.first()).coerceAtLeast(0)
        val threadGrowth = (threads.last() - threads.first()).coerceAtLeast(0)
        return AndroidStabilityEvaluation(
            passed = pssGrowth <= policy.maximumPssGrowthKb && threadGrowth <= policy.maximumThreadGrowth,
            pssGrowthKb = pssGrowth,
            threadGrowth = threadGrowth,
            peakPssKb = pss.maxOrNull(),
            peakThreadCount = threads.maxOrNull(),
            sampleCount = samples.size,
        )
    }

    companion object {
        fun evidenceDigest(report: AndroidStabilityReport): String {
            val canonical = buildString {
                append("schema=").append(report.schema).append('\n')
                append("generatedAtEpochMs=").append(report.generatedAtEpochMs).append('\n')
                append("packageName=").append(report.packageName).append('\n')
                append("sampleIntervalMs=").append(report.sampleIntervalMs).append('\n')
                append("device.propertiesSha256=").append(report.device.propertiesSha256).append('\n')
                append("device.bridgeEndpoint=").append(report.device.bridgeEndpoint).append('\n')
                append("device.physical=").append(report.device.physical).append('\n')
                report.samples.forEachIndexed { index, sample ->
                    append("sample[").append(index).append("]=")
                        .append(sample.elapsedMs).append('|')
                        .append(sample.pid).append('|')
                        .append(sample.totalPssKb ?: "null").append('|')
                        .append(sample.nativeHeapKb ?: "null").append('|')
                        .append(sample.javaHeapKb ?: "null").append('|')
                        .append(sample.cpuPercent ?: "null").append('|')
                        .append(sample.threadCount ?: "null").append('\n')
                }
            }
            return AndroidDeviceIdentityRules.sha256(canonical)
        }
    }
}

@Serializable
data class AndroidStabilityEvaluation(
    val passed: Boolean,
    val pssGrowthKb: Long,
    val threadGrowth: Int,
    val peakPssKb: Long?,
    val peakThreadCount: Int?,
    val sampleCount: Int,
)

// Callers must supply a reviewed StabilityPolicy.





class AndroidStabilityRunner(
    private val inspection: AndroidInspectionManager,
    private val deviceIdentity: AndroidDeviceIdentityCollector,
) {
    suspend fun sample(
        packageName: String,
        sampleCount: Int,
        intervalMs: Long,
        onSample: (suspend (AndroidStabilitySample) -> Unit)? = null,
    ): AndroidStabilityReport {
        require(sampleCount in 1..10_000) { "Invalid sample count" }
        require(intervalMs in 250..3_600_000) { "Invalid sample interval" }
        val identity = deviceIdentity.collect()
        check(identity.physical) { "Stability evidence requires a physical Android device" }
        val started = System.currentTimeMillis()
        val out = ArrayList<AndroidStabilitySample>(sampleCount)
        var expectedPid: Int? = null
        repeat(sampleCount) { index ->
            val profile = inspection.profilePackage(packageName)
            if (expectedPid == null) expectedPid = profile.pid
            check(profile.pid == expectedPid) { "Target process restarted during stability evidence collection" }
            val sample = AndroidStabilitySample(
                elapsedMs = System.currentTimeMillis() - started,
                pid = profile.pid,
                totalPssKb = profile.totalPssKb,
                nativeHeapKb = profile.nativeHeapKb,
                javaHeapKb = profile.javaHeapKb,
                cpuPercent = profile.cpuPercent,
                threadCount = profile.threadCount,
            )
            out += sample
            onSample?.invoke(sample)
            if (index != sampleCount - 1) delay(intervalMs)
        }
        return AndroidStabilityReport(
            generatedAtEpochMs = System.currentTimeMillis(),
            packageName = packageName,
            sampleIntervalMs = intervalMs,
            device = identity,
            samples = out,
        ).withEvidenceDigest()
    }
}

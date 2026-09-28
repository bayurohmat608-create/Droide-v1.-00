package com.baystudio.droide

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.util.Base64
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject








class PhysicalDeviceSmokeInstrumentation : Instrumentation() {
    private data class Step(
        val id: String,
        val outcome: String,
        val durationMs: Long,
        val detail: String,
    )

    private var inputArguments: Bundle = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        inputArguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val generatedAt = System.currentTimeMillis()
        val nonce = inputArguments.getString(ARG_NONCE).orEmpty()
        val nonceSha256 = sha256(nonce)
        val steps = mutableListOf<Step>()
        var failure: Throwable? = null
        var activity: MainActivity? = null
        var ptyProbe: PhysicalDeviceIdeChecks.PtyProbe? = null

        fun step(id: String, block: () -> String) {
            val started = System.currentTimeMillis()
            try {
                val detail = block().take(MAX_DETAIL_CHARS)
                steps += Step(id, "PASS", System.currentTimeMillis() - started, detail)
            } catch (t: Throwable) {
                steps += Step(
                    id = id,
                    outcome = "FAIL",
                    durationMs = System.currentTimeMillis() - started,
                    detail = (t.message ?: t::class.java.simpleName).take(MAX_DETAIL_CHARS),
                )
                throw t
            }
        }

        try {
            require(nonce.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "Missing or invalid certification nonce" }

            step("target-context") {
                PhysicalDevicePlatformChecks.verifyTargetContext(targetContext)
            }

            step("private-storage") {
                PhysicalDevicePlatformChecks.verifyPrivateStorage(targetContext, nonceSha256)
            }

            step("process-shell") {
                PhysicalDevicePlatformChecks.verifyProcessShell(nonceSha256)
            }

            step("main-activity-launch") {
                activity = PhysicalDevicePlatformChecks.launchMainActivity(this, targetContext)
                "MainActivity attached"
            }

            step("main-activity-recreation") {
                activity = PhysicalDevicePlatformChecks.recreateMainActivity(this, requireNotNull(activity))
                "MainActivity recreated and attached"
            }

            step("jgit-roundtrip") {
                PhysicalDeviceIdeChecks.verifyJGitRoundTrip(targetContext, nonceSha256)
            }

            if (inputArguments.getString("droide_recovery_mode") == "true") {
                step("editor-recovery") {
                    PhysicalDeviceIdeChecks.verifyEditorRecovery(targetContext, nonceSha256)
                }
            }

            step("native-pty") {
                ptyProbe = PhysicalDeviceIdeChecks.startAndVerifyNativePty(this, targetContext, nonceSha256)
                "Termux native PTY execution PASS"
            }

            step("native-pty-utf8-resize") {
                PhysicalDeviceIdeChecks.verifyNativePtyUtf8AndResize(this, requireNotNull(ptyProbe), nonceSha256)
            }
        } catch (t: Throwable) {
            failure = t
        } finally {
            ptyProbe?.close(this)
            runCatching { runOnMainSync { activity?.finish() } }
        }

        val report = buildReport(
            generatedAtEpochMs = generatedAt,
            nonceSha256 = nonceSha256,
            passed = failure == null && steps.map { it.id } == (
                if (inputArguments.getString("droide_recovery_mode") == "true")
                    REQUIRED_STEPS.take(6) + "editor-recovery" + REQUIRED_STEPS.drop(6)
                else REQUIRED_STEPS
            ) && steps.all { it.outcome == "PASS" },
            steps = steps,
        )
        val compact = report.toString()
        val result = Bundle().apply {
            putString(RESULT_REPORT_B64, Base64.encodeToString(compact.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            failure?.let { putString(RESULT_FAILURE, (it.message ?: it::class.java.simpleName).take(MAX_DETAIL_CHARS)) }
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private fun buildReport(
        generatedAtEpochMs: Long,
        nonceSha256: String,
        passed: Boolean,
        steps: List<Step>,
    ): JSONObject {
        val abis = Build.SUPPORTED_ABIS.toList().take(8)
        val report = JSONObject()
            .put("schema", SCHEMA)
            .put("generatedAtEpochMs", generatedAtEpochMs)
            .put("passed", passed)
            .put("nonceSha256", nonceSha256)
            .put("packageName", targetContext.packageName)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("abis", JSONArray(abis))
            .put("steps", JSONArray().apply {
                steps.forEach { step ->
                    put(JSONObject()
                        .put("id", step.id)
                        .put("outcome", step.outcome)
                        .put("durationMs", step.durationMs)
                        .put("detail", step.detail))
                }
            })
        report.put("evidenceDigestSha256", instrumentationEvidenceDigest(report))
        return report
    }

    private fun instrumentationEvidenceDigest(report: JSONObject): String {
        val steps = report.getJSONArray("steps")
        val canonical = buildString {
            append("schema=").append(report.getInt("schema")).append('\n')
            append("generatedAtEpochMs=").append(report.getLong("generatedAtEpochMs")).append('\n')
            append("passed=").append(report.getBoolean("passed")).append('\n')
            append("nonceSha256=").append(report.getString("nonceSha256")).append('\n')
            append("packageName=").append(report.getString("packageName")).append('\n')
            append("sdkInt=").append(report.getInt("sdkInt")).append('\n')
            append("abis=")
            val abis = report.getJSONArray("abis")
            for (index in 0 until abis.length()) {
                if (index > 0) append(',')
                append(abis.getString(index))
            }
            append('\n')
            for (index in 0 until steps.length()) {
                val step = steps.getJSONObject(index)
                append("step[").append(index).append("]=")
                    .append(step.getString("id")).append('|')
                    .append(step.getString("outcome")).append('|')
                    .append(step.getLong("durationMs")).append('|')
                    .append(sha256(step.getString("detail"))).append('\n')
            }
        }
        return sha256(canonical)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val SCHEMA = 1
        private const val ARG_NONCE = "droide_nonce"
        private const val RESULT_REPORT_B64 = "droidePhysicalSmokeB64"
        private const val RESULT_FAILURE = "droidePhysicalSmokeFailure"
        private const val MAX_DETAIL_CHARS = 500
        private val REQUIRED_STEPS = listOf(
            "target-context",
            "private-storage",
            "process-shell",
            "main-activity-launch",
            "main-activity-recreation",
            "jgit-roundtrip",
            "native-pty",
            "native-pty-utf8-resize",
        )
    }
}

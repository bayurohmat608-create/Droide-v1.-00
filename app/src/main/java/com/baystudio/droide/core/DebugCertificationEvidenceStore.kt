package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json







class DebugCertificationEvidenceStore private constructor(
    private val root: File,
    @Suppress("UNUSED_PARAMETER") directRoot: Boolean,
) {
    constructor(context: Context, projectId: String) : this(
        root = File(
            File(context.applicationContext.filesDir, "debug-certification"),
            PathSecurity.safeLeafName(projectId),
        ),
        directRoot = true,
    )

    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }
    init {
        check(root.isDirectory || root.mkdirs()) { "Could not create debugger certification evidence directory" }
    }

    fun persist(report: DebugCertificationReport) {
        report.validate()
        val text = json.encodeToString(report) + "\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_REPORT_BYTES) { "Debug certification report is too large" }

        atomicWrite(File(root, LATEST_FILE), bytes)
        val historyName = "${report.generatedAtEpochMs}-${report.apkSha256.take(12)}-${report.evidenceDigestSha256.take(12)}.json"
        atomicWrite(File(root, historyName), bytes)
        pruneHistory()
    }

    fun latest(): DebugCertificationReport? = runCatching {
        val file = File(root, LATEST_FILE)
        if (!file.isFile || file.length() !in 1..MAX_REPORT_BYTES.toLong()) return@runCatching null
        json.decodeFromString<DebugCertificationReport>(file.readText(Charsets.UTF_8)).also(DebugCertificationReport::validate)
    }.getOrNull()

    fun exportLatest(output: OutputStream): DebugCertificationReport {
        val report = latest() ?: error("No validated debugger certification evidence is available")
        val bytes = (json.encodeToString(report) + "\n").toByteArray(Charsets.UTF_8)
        output.write(bytes)
        output.flush()
        return report
    }

    private fun pruneHistory() {
        root.listFiles { file -> file.isFile && file.extension == "json" && file.name != LATEST_FILE }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            ?.drop(MAX_HISTORY_REPORTS)
            ?.forEach { runCatching { it.delete() } }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temp = File(root, ".${target.name}.tmp-${System.nanoTime()}")
        try {
            temp.outputStream().buffered().use { output ->
                output.write(bytes)
                output.flush()
            }
            runCatching {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    companion object {
        internal fun forTesting(root: File): DebugCertificationEvidenceStore =
            DebugCertificationEvidenceStore(root, directRoot = true)

        private const val LATEST_FILE = "latest.json"
        private const val MAX_REPORT_BYTES = 2 * 1024 * 1024
        private const val MAX_HISTORY_REPORTS = 20
    }
}

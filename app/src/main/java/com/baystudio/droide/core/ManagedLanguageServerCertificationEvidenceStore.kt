package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Failed certification reports are intentionally never promoted into this store.






class ManagedLanguageServerCertificationEvidenceStore private constructor(
    private val root: File,
    @Suppress("UNUSED_PARAMETER") directRoot: Boolean,
) {
    constructor(context: Context, projectId: String) : this(
        root = File(
            File(context.applicationContext.filesDir, "lsp-certification"),
            PathSecurity.safeLeafName(projectId),
        ),
        directRoot = true,
    )

    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }

    init {
        check(root.isDirectory || root.mkdirs()) { "Could not create LSP certification evidence directory" }
    }

    fun persist(report: ManagedLanguageServerCertificationReport) {
        report.validate()
        require(report.passed) { "Only fully passing managed LSP certification evidence can be persisted" }
        val serverRoot = serverRoot(report.languageServerId)
        val bytes = (json.encodeToString(report) + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_REPORT_BYTES) { "Managed LSP certification report is too large" }

        atomicWrite(serverRoot, File(serverRoot, LATEST_FILE), bytes)
        val historyName = buildString {
            append(report.generatedAtEpochMs).append('-')
            append(report.artifactSha256.take(12)).append('-')
            append(report.evidenceDigestSha256.take(12)).append(".json")
        }
        atomicWrite(serverRoot, File(serverRoot, historyName), bytes)
        pruneHistory(serverRoot)
    }

    fun latest(languageServerId: String): ManagedLanguageServerCertificationReport? = runCatching {
        val file = File(serverRoot(languageServerId), LATEST_FILE)
        if (!file.isFile || file.length() !in 1..MAX_REPORT_BYTES.toLong()) return@runCatching null
        json.decodeFromString<ManagedLanguageServerCertificationReport>(file.readText(Charsets.UTF_8)).also {
            it.validate()
            require(it.passed) { "Persisted managed LSP evidence is not a PASS report" }
            require(it.languageServerId == languageServerId) { "Managed LSP evidence server id mismatch" }
        }
    }.getOrNull()

    fun exportLatest(languageServerId: String, output: OutputStream): ManagedLanguageServerCertificationReport {
        val report = latest(languageServerId)
            ?: error("No validated managed LSP certification evidence is available for $languageServerId")
        val bytes = (json.encodeToString(report) + "\n").toByteArray(Charsets.UTF_8)
        output.write(bytes)
        output.flush()
        return report
    }

    private fun serverRoot(languageServerId: String): File {
        val safeId = PathSecurity.safeLeafName(languageServerId)
        val directory = File(root, safeId)
        check(directory.isDirectory || directory.mkdirs()) { "Could not create LSP certification server directory" }
        return directory
    }

    private fun pruneHistory(serverRoot: File) {
        serverRoot.listFiles { file -> file.isFile && file.extension == "json" && file.name != LATEST_FILE }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            ?.drop(MAX_HISTORY_REPORTS)
            ?.forEach { runCatching { it.delete() } }
    }

    private fun atomicWrite(directory: File, target: File, bytes: ByteArray) {
        val temp = File(directory, ".${target.name}.tmp-${System.nanoTime()}")
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
        internal fun forTesting(root: File): ManagedLanguageServerCertificationEvidenceStore =
            ManagedLanguageServerCertificationEvidenceStore(root, directRoot = true)

        private const val LATEST_FILE = "latest.json"
        private const val MAX_REPORT_BYTES = 2 * 1024 * 1024
        private const val MAX_HISTORY_REPORTS = 20
    }
}

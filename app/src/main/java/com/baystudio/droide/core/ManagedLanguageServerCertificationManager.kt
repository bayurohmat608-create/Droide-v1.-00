package com.baystudio.droide.core

import android.content.Context
import java.io.OutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock







class ManagedLanguageServerCertificationManager(
    context: Context,
    projectId: String,
    private val runner: ManagedLanguageServerCertificationRunner,
) {
    private val evidence = ManagedLanguageServerCertificationEvidenceStore(context.applicationContext, projectId)
    private val certificationMutex = Mutex()

    suspend fun certify(languageServerId: String): ManagedLanguageServerCertificationReport =
        certificationMutex.withLock {
            val report = runner.run(languageServerId)
            report.validate()
            if (report.passed) evidence.persist(report)
            report
        }

    fun latestReport(languageServerId: String): ManagedLanguageServerCertificationReport? =
        evidence.latest(languageServerId)

    fun exportLatest(languageServerId: String, output: OutputStream): ManagedLanguageServerCertificationReport =
        evidence.exportLatest(languageServerId, output)
}

package com.baystudio.droide.core

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class UserInitiatedArtifactTransfer(context: Context) {
    private val appContext = context.applicationContext
    private val store = ArtifactTransferStore(appContext)
    private val downloader = TrustedArtifactDownloader(appContext)

    suspend fun download(
        spec: TrustedArtifactSpec,
        onProgress: (TrustedArtifactDownloadProgress) -> Unit = {},
    ): File = downloadDescriptor(spec, onProgress)

    suspend fun download(
        spec: TrustedSha512ArtifactSpec,
        onProgress: (TrustedArtifactDownloadProgress) -> Unit = {},
    ): File = downloadDescriptor(spec, onProgress)

    suspend fun discard(spec: TrustedArtifactSpec) = downloader.discard(spec)
    suspend fun discard(spec: TrustedSha512ArtifactSpec) = downloader.discard(spec)
    suspend fun cached(spec: TrustedArtifactSpec): File? = downloader.cached(spec)
    suspend fun cached(spec: TrustedSha512ArtifactSpec): File? = downloader.cached(spec)

    private suspend fun downloadDescriptor(
        spec: TrustedArtifactDescriptor,
        onProgress: (TrustedArtifactDownloadProgress) -> Unit,
    ): File {
        val cached = cached(spec)
        if (cached != null) {
            onProgress(TrustedArtifactDownloadProgress(cached.length(), cached.length(), fromCache = true))
            return cached
        }

        val request = store.ensure(spec)
        store.recoverStaleRunning(request.id)
        if (store.read(request.id)?.state != ArtifactTransferState.RUNNING) schedule(request.id)
        var lastBytes = -1L
        try {
            while (true) {
                val current = store.read(request.id) ?: error("Artifact transfer state disappeared")
                if (current.bytesDownloaded != lastBytes) {
                    lastBytes = current.bytesDownloaded
                    onProgress(
                        TrustedArtifactDownloadProgress(
                            bytesDownloaded = current.bytesDownloaded,
                            totalBytes = current.totalBytes,
                            fromCache = false,
                        )
                    )
                }
                when (current.state) {
                    ArtifactTransferState.SUCCEEDED -> return cached(spec)
                        ?: error("Verified transfer completed but trusted cache is unavailable")
                    ArtifactTransferState.FAILED -> error(current.detail.ifBlank { "Artifact transfer failed" })
                    ArtifactTransferState.CANCELED -> throw CancellationException(
                        current.detail.ifBlank { "Artifact transfer canceled" }
                    )
                    ArtifactTransferState.QUEUED,
                    ArtifactTransferState.RUNNING,
                    -> delay(POLL_MS)
                }
            }
        } catch (cancelled: CancellationException) {
            cancel(appContext, request.id, "Caller canceled the package transfer")
            throw cancelled
        }
    }

    private suspend fun cached(spec: TrustedArtifactDescriptor): File? = when (spec) {
        is TrustedArtifactSpec -> downloader.cached(spec)
        is TrustedSha512ArtifactSpec -> downloader.cached(spec)
        else -> error("Unsupported trusted artifact descriptor")
    }

    private fun schedule(transferId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            scheduleUidt(appContext, transferId)
        } else {
            PackageTransferForegroundService.start(appContext, transferId)
        }
    }

    companion object {
        private const val POLL_MS = 200L

        fun cancel(context: Context, transferId: String, reason: String = "Transfer canceled by user") {
            val app = context.applicationContext
            ArtifactTransferStore(app).markCanceled(transferId, reason)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val scheduler = app.getSystemService(JobScheduler::class.java)
                scheduler.cancel(ArtifactTransferStore.jobId(transferId))
            } else {
                PackageTransferForegroundService.cancel(app, transferId)
            }
        }

        @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private fun scheduleUidt(context: Context, transferId: String) {
            val store = ArtifactTransferStore(context)
            val request = store.read(transferId) ?: error("Artifact transfer request is unavailable")
            val extras = PersistableBundle().apply { putString(ArtifactTransferStore.EXTRA_TRANSFER_ID, transferId) }
            val network = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val estimated = request.totalBytes ?: request.maxBytes
            val info = JobInfo.Builder(
                ArtifactTransferStore.jobId(transferId),
                ComponentName(context, UserInitiatedArtifactTransferJobService::class.java),
            )
                .setExtras(extras)
                .setUserInitiated(true)
                .setRequiredNetwork(network)
                .setEstimatedNetworkBytes(estimated, 0L)
                .setMinimumNetworkChunkBytes(minOf(estimated, 1024L * 1024L).coerceAtLeast(1L))
                .setBackoffCriteria(10_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            val result = context.getSystemService(JobScheduler::class.java).schedule(info)
            check(result == JobScheduler.RESULT_SUCCESS) {
                "Android rejected the user-initiated transfer schedule. Start package downloads while Droide is visible."
            }
        }
    }
}

internal enum class ArtifactTransferState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELED }

internal data class ArtifactTransferRecord(
    val id: String,
    val artifactId: String,
    val url: String,
    val fileName: String,
    val maxBytes: Long,
    val totalBytes: Long?,
    val digestAlgorithm: String,
    val digestHex: String,
    val state: ArtifactTransferState,
    val bytesDownloaded: Long,
    val attempts: Int,
    val ownerPid: Int?,
    val ownerProcessIdentity: String?,
    val updatedAtEpochMs: Long,
    val detail: String,
) {
    fun descriptor(): TrustedArtifactDescriptor = when (digestAlgorithm) {
        "SHA-256" -> TrustedArtifactSpec(artifactId, url, digestHex, fileName, maxBytes, totalBytes)
        "SHA-512" -> TrustedSha512ArtifactSpec(artifactId, url, digestHex, fileName, maxBytes, totalBytes)
        else -> error("Unsupported persisted artifact digest")
    }
}

internal class ArtifactTransferStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "artifact-transfers").apply { mkdirs() }

    fun ensure(spec: TrustedArtifactDescriptor): ArtifactTransferRecord = synchronized(FILE_LOCK) {
        spec.validateDescriptor()
        val id = transferId(spec)
        val existing = readLocked(id)
        if (existing != null) {
            check(existing.artifactId == spec.id && existing.url == spec.url && existing.fileName == spec.fileName)
            check(existing.digestAlgorithm == spec.digestAlgorithm && existing.digestHex.equals(spec.digestHex, true))
            check(existing.maxBytes == spec.maxBytes && existing.totalBytes == spec.expectedBytes)
            if (existing.state in setOf(ArtifactTransferState.SUCCEEDED, ArtifactTransferState.FAILED, ArtifactTransferState.CANCELED)) {
                return@synchronized existing.copy(
                    state = ArtifactTransferState.QUEUED,
                    bytesDownloaded = 0L,
                    attempts = 0,
                    ownerPid = null,
                    ownerProcessIdentity = null,
                    updatedAtEpochMs = System.currentTimeMillis(),
                    detail = "",
                ).also(::writeLocked)
            }
            return@synchronized existing
        }
        ArtifactTransferRecord(
            id = id,
            artifactId = spec.id,
            url = spec.url,
            fileName = spec.fileName,
            maxBytes = spec.maxBytes,
            totalBytes = spec.expectedBytes,
            digestAlgorithm = spec.digestAlgorithm,
            digestHex = spec.digestHex.lowercase(),
            state = ArtifactTransferState.QUEUED,
            bytesDownloaded = 0L,
            attempts = 0,
            ownerPid = null,
            ownerProcessIdentity = null,
            updatedAtEpochMs = System.currentTimeMillis(),
            detail = "",
        ).also(::writeLocked)
    }

    fun read(id: String): ArtifactTransferRecord? = synchronized(FILE_LOCK) { readLocked(id) }

    fun markRunning(id: String): ArtifactTransferRecord? = synchronized(FILE_LOCK) {
        val current = readLocked(id) ?: return@synchronized null
        if (current.state in setOf(ArtifactTransferState.CANCELED, ArtifactTransferState.SUCCEEDED, ArtifactTransferState.FAILED)) {
            return@synchronized null
        }
        current.copy(
            state = ArtifactTransferState.RUNNING,
            attempts = current.attempts + 1,
            ownerPid = android.os.Process.myPid(),
            ownerProcessIdentity = LocalExecutionSubstrate.processIdentity(),
            updatedAtEpochMs = System.currentTimeMillis(),
            detail = "",
        ).also(::writeLocked)
    }

    fun markProgress(id: String, progress: TrustedArtifactDownloadProgress) = update(id) {
        if (it.state != ArtifactTransferState.RUNNING) it else it.copy(
            bytesDownloaded = progress.bytesDownloaded,
            totalBytes = progress.totalBytes ?: it.totalBytes,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    fun markSucceeded(id: String, bytes: Long) = update(id) {
        if (it.state == ArtifactTransferState.CANCELED) it else it.copy(
            state = ArtifactTransferState.SUCCEEDED,
            bytesDownloaded = bytes,
            totalBytes = it.totalBytes ?: bytes,
            ownerPid = null,
            ownerProcessIdentity = null,
            updatedAtEpochMs = System.currentTimeMillis(),
            detail = "Verified digest and published to trusted artifact cache",
        )
    }

    fun markRetry(id: String, detail: String) = update(id) {
        if (it.state in setOf(ArtifactTransferState.CANCELED, ArtifactTransferState.SUCCEEDED)) it else it.copy(
            state = ArtifactTransferState.QUEUED,
            ownerPid = null,
            ownerProcessIdentity = null,
            updatedAtEpochMs = System.currentTimeMillis(),
            detail = detail.take(MAX_DETAIL),
        )
    }

    fun markFailed(id: String, detail: String) = update(id) {
        if (it.state in setOf(ArtifactTransferState.CANCELED, ArtifactTransferState.SUCCEEDED)) it else it.copy(
            state = ArtifactTransferState.FAILED,
            ownerPid = null,
            ownerProcessIdentity = null,
            updatedAtEpochMs = System.currentTimeMillis(),
            detail = detail.take(MAX_DETAIL),
        )
    }

    fun markCanceled(id: String, detail: String) = updateIfPresent(id) {
        if (it.state == ArtifactTransferState.SUCCEEDED) it else it.copy(
            state = ArtifactTransferState.CANCELED,
            ownerPid = null,
            ownerProcessIdentity = null,
            updatedAtEpochMs = System.currentTimeMillis(),
            detail = detail.take(MAX_DETAIL),
        )
    }

    fun recoverStaleRunning(id: String) = synchronized(FILE_LOCK) {
        val record = readLocked(id) ?: return@synchronized
        if (record.state == ArtifactTransferState.RUNNING && (
            record.ownerPid != android.os.Process.myPid() ||
                record.ownerProcessIdentity != LocalExecutionSubstrate.processIdentity()
        )) {
            writeLocked(record.copy(
                state = ArtifactTransferState.QUEUED,
                ownerPid = null,
                ownerProcessIdentity = null,
                updatedAtEpochMs = System.currentTimeMillis(),
                detail = "Recovered interrupted transfer; resumable cache will be revalidated",
            ))
        }
    }

    private fun update(id: String, transform: (ArtifactTransferRecord) -> ArtifactTransferRecord): ArtifactTransferRecord =
        synchronized(FILE_LOCK) {
            val current = readLocked(id) ?: error("Artifact transfer state is unavailable")
            transform(current).also(::writeLocked)
        }

    private fun updateIfPresent(id: String, transform: (ArtifactTransferRecord) -> ArtifactTransferRecord) =
        synchronized(FILE_LOCK) {
            val current = readLocked(id) ?: return@synchronized
            writeLocked(transform(current))
        }

    private fun file(id: String): File {
        require(id.matches(Regex("[0-9a-f]{32}"))) { "Invalid artifact transfer id" }
        return File(root, "$id.properties")
    }

    private fun readLocked(id: String): ArtifactTransferRecord? {
        val file = file(id)
        if (!file.isFile || file.length() !in 1L..MAX_RECORD_BYTES) return null
        return runCatching {
            val p = Properties().apply { file.inputStream().buffered().use { input -> load(input) } }
            check(p.getProperty("schema") == "2")
            ArtifactTransferRecord(
                id = id,
                artifactId = p.required("artifactId"),
                url = p.required("url"),
                fileName = p.required("fileName"),
                maxBytes = p.required("maxBytes").toLong(),
                totalBytes = p.getProperty("totalBytes")?.takeIf(String::isNotBlank)?.toLong(),
                digestAlgorithm = p.required("digestAlgorithm"),
                digestHex = p.required("digestHex"),
                state = ArtifactTransferState.valueOf(p.required("state")),
                bytesDownloaded = p.required("bytesDownloaded").toLong(),
                attempts = p.required("attempts").toInt(),
                ownerPid = p.getProperty("ownerPid")?.takeIf(String::isNotBlank)?.toInt(),
                ownerProcessIdentity = p.getProperty("ownerProcessIdentity")?.takeIf(String::isNotBlank),
                updatedAtEpochMs = p.required("updatedAtEpochMs").toLong(),
                detail = p.getProperty("detail").orEmpty(),
            ).also { record ->
                record.descriptor().validateStructure()
                check(record.bytesDownloaded in 0L..record.maxBytes)
                record.totalBytes?.let { total ->
                    check(total in 1L..record.maxBytes)
                    check(record.bytesDownloaded <= total)
                }
                check(record.attempts in 0..100)
                check(record.detail.length <= MAX_DETAIL)
                record.ownerPid?.let { check(it > 0) }
                record.ownerProcessIdentity?.let { check(it.matches(Regex("[0-9a-f]{32}"))) }
                check((record.ownerPid == null) == (record.ownerProcessIdentity == null))
            }
        }.getOrNull()
    }

    private fun writeLocked(record: ArtifactTransferRecord) {
        val target = file(record.id)
        val temp = File(root, ".${record.id}.${android.os.Process.myPid()}.tmp")
        val props = Properties().apply {
            setProperty("schema", "2")
            setProperty("artifactId", record.artifactId)
            setProperty("url", record.url)
            setProperty("fileName", record.fileName)
            setProperty("maxBytes", record.maxBytes.toString())
            setProperty("totalBytes", record.totalBytes?.toString().orEmpty())
            setProperty("digestAlgorithm", record.digestAlgorithm)
            setProperty("digestHex", record.digestHex)
            setProperty("state", record.state.name)
            setProperty("bytesDownloaded", record.bytesDownloaded.toString())
            setProperty("attempts", record.attempts.toString())
            setProperty("ownerPid", record.ownerPid?.toString().orEmpty())
            setProperty("ownerProcessIdentity", record.ownerProcessIdentity.orEmpty())
            setProperty("updatedAtEpochMs", record.updatedAtEpochMs.toString())
            setProperty("detail", record.detail.replace('\n', ' ').replace('\r', ' ').take(MAX_DETAIL))
        }
        FileOutputStream(temp).use { out ->
            props.store(out, null)
            out.fd.sync()
        }
        val moved = runCatching {
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching {
            java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        moved.getOrElse { failure ->
            temp.delete()
            throw IllegalStateException("Could not persist artifact transfer state", failure)
        }
    }

    private fun Properties.required(key: String): String = getProperty(key)?.takeIf(String::isNotBlank)
        ?: error("Missing artifact transfer field $key")

    companion object {
        const val EXTRA_TRANSFER_ID = "transfer_id"
        private val FILE_LOCK = Any()
        private const val MAX_RECORD_BYTES = 32L * 1024L
        private const val MAX_DETAIL = 500

        fun transferId(spec: TrustedArtifactDescriptor): String {
            val canonical = listOf(
                spec.id, spec.url, spec.fileName, spec.digestAlgorithm, spec.digestHex.lowercase(),
                spec.maxBytes.toString(), spec.expectedBytes?.toString().orEmpty(),
            )
                .joinToString("\u0000")
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(32)
        }

        fun jobId(transferId: String): Int {
            require(transferId.matches(Regex("[0-9a-f]{32}")))
            return (transferId.take(8).toLong(16).and(0x7fffffffL).toInt()).coerceAtLeast(1)
        }
    }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UserInitiatedArtifactTransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Int, Job>()
    private val store by lazy { ArtifactTransferStore(applicationContext) }

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString(ArtifactTransferStore.EXTRA_TRANSFER_ID) ?: return false
        val record = store.read(id) ?: return false
        if (record.state == ArtifactTransferState.CANCELED) return false
        ArtifactTransferNotifications.ensureChannel(this)
        setNotification(
            params,
            ArtifactTransferStore.jobId(id),
            ArtifactTransferNotifications.build(this, record, indeterminate = true),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var lastNotificationAt = 0L
            try {
                val running = store.markRunning(id)
                if (running == null) {
                    jobFinished(params, false)
                    return@launch
                }
                val downloader = TrustedArtifactDownloader(applicationContext)
                val file = when (val spec = running.descriptor()) {
                    is TrustedArtifactSpec -> downloader.download(spec) { progress ->
                        store.markProgress(id, progress)
                        updateTransferredNetworkBytes(params, progress.bytesDownloaded.coerceAtLeast(0L), 0L)
                        val now = System.currentTimeMillis()
                        if (now - lastNotificationAt >= 750L) {
                            lastNotificationAt = now
                            store.read(id)?.let { current ->
                                setNotification(params, ArtifactTransferStore.jobId(id), ArtifactTransferNotifications.build(this@UserInitiatedArtifactTransferJobService, current, false), JOB_END_NOTIFICATION_POLICY_REMOVE)
                            }
                        }
                    }
                    is TrustedSha512ArtifactSpec -> downloader.download(spec) { progress ->
                        store.markProgress(id, progress)
                        updateTransferredNetworkBytes(params, progress.bytesDownloaded.coerceAtLeast(0L), 0L)
                        val now = System.currentTimeMillis()
                        if (now - lastNotificationAt >= 750L) {
                            lastNotificationAt = now
                            store.read(id)?.let { current ->
                                setNotification(params, ArtifactTransferStore.jobId(id), ArtifactTransferNotifications.build(this@UserInitiatedArtifactTransferJobService, current, false), JOB_END_NOTIFICATION_POLICY_REMOVE)
                            }
                        }
                    }
                    else -> error("Unsupported artifact transfer descriptor")
                }
                store.markSucceeded(id, file.length())
                jobFinished(params, false)
            } catch (cancelled: CancellationException) {
                if (store.read(id)?.state != ArtifactTransferState.CANCELED) {
                    store.markRetry(id, "Transfer interrupted before completion")
                }
                throw cancelled
            } catch (io: IOException) {
                val attempts = store.read(id)?.attempts ?: 1
                if (attempts < MAX_RETRIES) {
                    store.markRetry(id, io.message ?: "Temporary network failure")
                    jobFinished(params, true)
                } else {
                    store.markFailed(id, io.message ?: "Network transfer failed after retries")
                    jobFinished(params, false)
                }
            } catch (failure: Throwable) {
                store.markFailed(id, failure.message ?: "Artifact verification failed")
                jobFinished(params, false)
            } finally {
                jobs.remove(params.jobId)
            }
        }
        jobs[params.jobId] = job
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        val id = params.extras.getString(ArtifactTransferStore.EXTRA_TRANSFER_ID) ?: return false
        if (store.read(id)?.state == ArtifactTransferState.CANCELED) return false

        // Some user-originated stop paths that do invoke onStopJob report STOP_REASON_USER. Android's
        // Task Manager Stop may instead kill the app process without this callback; the durable RUNNING
        // record is then recovered as interrupted on the next explicit transfer request.
        if (params.stopReason == JobParameters.STOP_REASON_USER) {
            store.markCanceled(id, "Transfer stopped by the user through Android")
            return false
        }

        store.markRetry(id, "Android stopped transfer (reason=${params.stopReason}); verified partial data retained for resume")
        return true
    }

    override fun onDestroy() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        scope.cancel()
        super.onDestroy()
    }

    companion object { private const val MAX_RETRIES = 4 }
}


class PackageTransferForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val store by lazy { ArtifactTransferStore(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        ArtifactTransferNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(ArtifactTransferStore.EXTRA_TRANSFER_ID) ?: return START_NOT_STICKY
        when (intent.action) {
            ACTION_CANCEL -> {
                store.markCanceled(id, "Transfer canceled from notification")
                jobs.remove(id)?.cancel()
                refreshForeground()
                return START_NOT_STICKY
            }
            ACTION_START -> startTransfer(id)
        }
        return START_NOT_STICKY
    }

    private fun startTransfer(id: String) {
        val record = store.read(id) ?: return
        startVisible(record)
        if (jobs.containsKey(id)) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val running = store.markRunning(id) ?: return@launch
                val downloader = TrustedArtifactDownloader(applicationContext)
                val file = when (val spec = running.descriptor()) {
                    is TrustedArtifactSpec -> downloader.download(spec) { progress -> store.markProgress(id, progress); refreshForeground() }
                    is TrustedSha512ArtifactSpec -> downloader.download(spec) { progress -> store.markProgress(id, progress); refreshForeground() }
                    else -> error("Unsupported artifact transfer descriptor")
                }
                store.markSucceeded(id, file.length())
            } catch (cancelled: CancellationException) {
                if (store.read(id)?.state != ArtifactTransferState.CANCELED) store.markRetry(id, "Visible transfer interrupted")
            } catch (failure: Throwable) {
                store.markFailed(id, failure.message ?: "Visible transfer failed")
            } finally {
                jobs.remove(id)
                refreshForeground()
            }
        }
        jobs[id] = job
        job.start()
    }

    private fun startVisible(record: ArtifactTransferRecord) {
        val notification = ArtifactTransferNotifications.build(this, record, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun refreshForeground() {
        val first = jobs.keys.firstOrNull()?.let(store::read)
        if (first == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            val count = jobs.size
            val base = ArtifactTransferNotifications.build(this, first, false)
            val notification = if (count <= 1) base else ArtifactTransferNotifications.buildAggregate(this, count)
            getSystemService(NotificationManager::class.java).notify(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_START = "com.baystudio.droide.action.PACKAGE_TRANSFER_START"
        private const val ACTION_CANCEL = "com.baystudio.droide.action.PACKAGE_TRANSFER_CANCEL"
        private const val FOREGROUND_NOTIFICATION_ID = 7211

        fun start(context: Context, id: String) {
            val intent = Intent(context, PackageTransferForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(ArtifactTransferStore.EXTRA_TRANSFER_ID, id)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context, id: String) {
            context.startService(
                Intent(context, PackageTransferForegroundService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(ArtifactTransferStore.EXTRA_TRANSFER_ID, id)
            )
        }
    }
}

class ArtifactTransferCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra(ArtifactTransferStore.EXTRA_TRANSFER_ID) ?: return
        UserInitiatedArtifactTransfer.cancel(context, id, "Transfer canceled from notification")
    }
}

internal object ArtifactTransferNotifications {
    private const val CHANNEL_ID = "droide_package_transfers"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Package transfers", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Downloads explicitly started for Droide packages, toolchains, and VM images"
                    setShowBadge(false)
                }
            )
        }
    }

    fun build(context: Context, record: ArtifactTransferRecord, indeterminate: Boolean): Notification {
        val total = record.totalBytes
        val percent = if (total != null && total > 0L) ((record.bytesDownloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Droide package transfer")
            .setContentText(record.fileName.take(80))
            .setOnlyAlertOnce(true)
            .setOngoing(record.state in setOf(ArtifactTransferState.QUEUED, ArtifactTransferState.RUNNING))
            .setProgress(100, percent, indeterminate || total == null)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent(context, record.id)).build())
            .build()
    }

    fun buildAggregate(context: Context, count: Int): Notification = Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Droide package transfers")
        .setContentText("$count verified transfers running")
        .setOngoing(true)
        .setProgress(0, 0, true)
        .build()

    private fun cancelIntent(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        ArtifactTransferStore.jobId(id),
        Intent(context, ArtifactTransferCancelReceiver::class.java)
            .putExtra(ArtifactTransferStore.EXTRA_TRANSFER_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

package com.baystudio.droide.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object DevelopmentForegroundSessionRegistry {
    private val cancelCallbacks = ConcurrentHashMap<String, () -> Unit>()

    fun register(token: String, cancel: () -> Unit) {
        require(token.matches(Regex("[A-Za-z0-9_-]{12,100}"))) { "Invalid foreground-session token" }
        check(cancelCallbacks.putIfAbsent(token, cancel) == null) { "Duplicate foreground-session token" }
    }

    fun remove(token: String) {
        cancelCallbacks.remove(token)
    }

    fun cancel(token: String): Boolean {
        val callback = cancelCallbacks.remove(token) ?: return false
        // A cancellation callback belongs to application work and must never crash the service
        // main thread or prevent other active sessions from being cancelled during teardown.
        runCatching { callback() }
        return true
    }

    fun cancelAll(tokens: Collection<String>) {
        tokens.distinct().forEach { token -> cancel(token) }
    }
}

class DevelopmentForegroundService : Service() {
    internal data class Handle(val token: String)

    private val activeSessions = LinkedHashMap<String, String>()

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
                val label = intent.getStringExtra(EXTRA_LABEL).orEmpty().take(120)
                if (!token.matches(Regex("[A-Za-z0-9_-]{12,100}")) || label.isBlank()) {
                    DevelopmentForegroundSessionRegistry.remove(token)
                    if (activeSessions.isEmpty()) stopSelf(startId)
                    return START_NOT_STICKY
                }
                activeSessions[token] = label
                ensureChannel()
                try {
                    promoteOrRefreshForeground()
                } catch (error: RuntimeException) {
                    activeSessions.remove(token)
                    DevelopmentForegroundSessionRegistry.cancel(token)
                    if (activeSessions.isEmpty()) stopSelf(startId)
                    else runCatching { promoteOrRefreshForeground() }
                    return START_NOT_STICKY
                }
            }

            ACTION_STOP -> {
                val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
                if (token.isNotBlank()) {
                    activeSessions.remove(token)
                    DevelopmentForegroundSessionRegistry.cancel(token)
                    refreshOrStop()
                }
            }

            ACTION_FINISH -> {
                val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
                if (token.isNotBlank()) finishToken(token)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        DevelopmentForegroundSessionRegistry.cancelAll(activeSessions.keys.toList())
        activeSessions.clear()
        super.onDestroy()
    }

    private fun finishToken(token: String) {
        DevelopmentForegroundSessionRegistry.remove(token)
        activeSessions.remove(token)
        refreshOrStop()
    }

    private fun refreshOrStop() {
        if (activeSessions.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            runCatching { promoteOrRefreshForeground() }.onFailure {
                DevelopmentForegroundSessionRegistry.cancelAll(activeSessions.keys.toList())
                activeSessions.clear()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun promoteOrRefreshForeground() {
        val notification = buildNotification(activeSessions)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Developer operations",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "User-started builds, tests, debugging, and virtual machine sessions"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(sessions: Map<String, String>): Notification {
        check(sessions.isNotEmpty()) { "Foreground notification requires at least one active session" }
        val token = sessions.keys.last()
        val label = sessions.values.last()
        val text = if (sessions.size == 1) label else "${sessions.size} developer operations active · $label"
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = openIntent?.let {
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val stopIntent = Intent(this, DevelopmentForegroundService::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_TOKEN, token)
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Droide developer session")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "droide.developer.operations"
        private const val NOTIFICATION_ID = 1701
        private const val REQUEST_OPEN = 1702
        private const val REQUEST_STOP = 1703
        private const val ACTION_START = "com.baystudio.droide.DEV_FGS_START"
        private const val ACTION_STOP = "com.baystudio.droide.DEV_FGS_STOP"
        private const val ACTION_FINISH = "com.baystudio.droide.DEV_FGS_FINISH"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_LABEL = "label"
        @Volatile private var activeInstance: DevelopmentForegroundService? = null

        internal fun start(context: Context, label: String, cancel: () -> Unit): Handle? {
            val app = context.applicationContext
            val token = UUID.randomUUID().toString().replace("-", "")
            DevelopmentForegroundSessionRegistry.register(token, cancel)
            val intent = Intent(app, DevelopmentForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TOKEN, token)
                putExtra(EXTRA_LABEL, label.take(120))
            }
            return try {
                ContextCompat.startForegroundService(app, intent)
                Handle(token)
            } catch (error: RuntimeException) {
                // ForegroundServiceStartNotAllowedException is a RuntimeException on API 31+.
                // A failed promotion must never be presented as a persistence guarantee.
                DevelopmentForegroundSessionRegistry.remove(token)
                null
            }
        }

        internal fun startRequired(context: Context, label: String, cancel: () -> Unit): Handle =
            checkNotNull(start(context, label, cancel)) {
                "Android did not allow the required foreground developer session to start"
            }

        internal fun finish(context: Context, handle: Handle?) {
            val token = handle?.token ?: return
            DevelopmentForegroundSessionRegistry.remove(token)
            val service = activeInstance
            if (service != null) {
                Handler(Looper.getMainLooper()).post {
                    if (activeInstance === service) service.finishToken(token)
                }
                return
            }
            // The operation may finish during service startup. Deliver one best-effort cleanup intent;
            // failure here cannot resurrect the callback because the registry entry is already gone.
            runCatching {
                context.applicationContext.startService(
                    Intent(context.applicationContext, DevelopmentForegroundService::class.java).apply {
                        action = ACTION_FINISH
                        putExtra(EXTRA_TOKEN, token)
                    },
                )
            }
        }
    }
}

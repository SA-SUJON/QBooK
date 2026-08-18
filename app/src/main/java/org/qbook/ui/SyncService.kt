package org.qbook.ui

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.qbook.R
import org.qbook.utils.BackgroundSyncManager

/**
 * Keeps the offline download running after the app is closed.
 *
 * Without this the whole pipeline lived inside the activity's process with
 * nothing holding it up. Swiping the app away killed the process, and every
 * partly-finished download went with it — so a user who opened the app and
 * left immediately never accumulated any offline content at all.
 *
 * A foreground service is the only way Android allows sustained background
 * work of this kind. The notification is required and cannot be hidden, but it
 * is low priority, silent, and disappears as soon as the pass finishes.
 *
 * The service does not do the downloading itself: [BackgroundSyncManager]
 * already owns that, on its own threads, holding only the application context.
 * This exists purely to tell Android the process is doing something the user
 * asked for, and to stop as soon as it is no longer true.
 */
class SyncService : Service() {

    companion object {
        const val CHANNEL_ID = "qbook_sync"
        const val NOTIFICATION_ID = 9002
        const val ACTION_STOP = "org.qbook.STOP_SYNC"

        @Volatile
        var running = false
            private set

        /** Start the service if a pass is running and it is not up already. */
        fun startIfNeeded(context: Context) {
            if (running) return
            if (!BackgroundSyncManager.isRunning) return
            val i = Intent(context, SyncService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                // Android can refuse a background start on newer versions.
                // Saving then simply stays tied to the app being open, which
                // is the behaviour we had before.
            }
        }

        fun stop(context: Context) {
            if (!running) return
            try {
                context.startService(
                    Intent(context, SyncService::class.java).apply { action = ACTION_STOP }
                )
            } catch (e: Exception) {}
        }
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * Stops the service once the pipeline reports it has finished.
     *
     * Polled rather than pushed: BackgroundSyncManager is a plain object with
     * no listener support, and adding one would mean touching the download
     * pipeline itself, which is the part that must not be disturbed.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!BackgroundSyncManager.isRunning) {
                stopSelf()
                return
            }
            main.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID,
                    "Offline downloading",
                    android.app.NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Shown while content is being saved for offline reading"
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                    setSound(null, null)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val tapIntent = Intent(this, MainActivity::class.java).also {
            it.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingTap = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SyncService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QBooK")
            .setContentText("Saving content for offline")
            .setSmallIcon(R.drawable.ic_offline)
            .setContentIntent(pendingTap)
            .addAction(0, "Stop", pendingStop)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        try {
            startForeground(NOTIFICATION_ID, notification)
            running = true
        } catch (e: Exception) {
            // Refused (background start restrictions, or the permission was
            // denied). Give up quietly rather than crashing the process.
            stopSelf()
            return START_NOT_STICKY
        }

        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 5_000)

        // Not sticky: if Android kills the process there is nothing worth
        // resurrecting, and the next app open starts a fresh pass anyway.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The task being swiped away must not take the download with it.
     *
     * A foreground service survives that on its own, but the default
     * behaviour for a service started from an activity is ambiguous across
     * OEM builds -- several aggressively kill the process with the task
     * unless the service says otherwise. Returning here without stopping,
     * and keeping the notification up, is what marks the work as still
     * wanted.
     *
     * The pipeline itself holds only the application context, so it has
     * nothing left pointing at the dead activity.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!BackgroundSyncManager.isRunning) {
            stopSelf()
            return
        }
        // Deliberately not calling super and not stopping: the pass continues.
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(watchdog)
        super.onDestroy()
    }
}

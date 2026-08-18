package org.qbook.ui

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.qbook.R

class AudioService : Service() {

    companion object {
        const val CHANNEL_ID = "dustbook_audio"
        const val NOTIFICATION_ID = 9001
        const val ACTION_STOP = "org.qbook.STOP_AUDIO"
        var running = false
    }

    /*
     * No audio focus request here, deliberately.
     *
     * Asking for AUDIOFOCUS_GAIN looked like the fix for "audio stops after a
     * few seconds", and it made it stop instantly instead. AudioManager does
     * not special-case two requesters in the same process: granting focus to
     * this service sends AUDIOFOCUS_LOSS to the previous holder, and the
     * previous holder is the WebView's own media player. It paused itself the
     * moment the service started.
     *
     * The WebView already owns the focus for what it is playing. Nothing here
     * should take it away.
     */

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(
                // IMPORTANCE_MIN is as close to hidden as Android permits.
                //
                // A foreground service must post a notification — the system
                // refuses to keep the service alive otherwise, and since API
                // 26 there is no way to suppress it. MIN keeps it out of the
                // status bar entirely: no icon, no sound, no badge. It sits
                // silently at the bottom of the shade, which is the quietest
                // the platform allows.
                android.app.NotificationChannel(
                    CHANNEL_ID, "Background audio",
                    android.app.NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Shown while reel audio plays in the background"
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
            AudioService.running = false
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

        val stopIntent = Intent(this, AudioService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dustbook")
            .setContentText("Audio playing")
            .setSmallIcon(R.drawable.ic_reels)
            .setContentIntent(pendingTap)
            .addAction(android.R.drawable.ic_media_pause, "Stop", pendingStop)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        // Foreground first, then focus: the system refuses a focus request
        // made from the background, and the promotion is what lifts that.
        startForeground(NOTIFICATION_ID, notification)
        AudioService.running = true
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AudioService.running = false
        super.onDestroy()
    }
}

package com.dustbook.app.ui

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dustbook.app.R

class AudioService : Service() {

    companion object {
        const val CHANNEL_ID = "dustbook_audio"
        const val NOTIFICATION_ID = 9001
        const val ACTION_STOP = "com.dustbook.app.STOP_AUDIO"
        var running = false
    }

    /**
     * Held for as long as the service runs.
     *
     * The foreground service keeps the process alive, but it says nothing
     * about who owns the audio output. Without a focus request the system
     * treats the playback as unowned and reclaims the stream within a few
     * seconds of the app leaving the foreground -- which is exactly the
     * reported symptom: sound for about five seconds, then silence, with the
     * notification still showing.
     */
    private var focusRequest: AudioFocusRequest? = null

    private fun acquireAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (focusRequest != null) return
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    // Never duck or pause ourselves on a transient loss; the
                    // WebView owns the element and cannot be resumed from here.
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                focusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (e: Exception) {
            focusRequest = null
        }
    }

    private fun releaseAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (e: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID, "Background audio",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shown while reel audio plays in the background"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AudioService.running = false
            releaseAudioFocus()
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
            .setContentText("Reel audio playing in background")
            .setSmallIcon(R.drawable.ic_reels)
            .setContentIntent(pendingTap)
            .addAction(android.R.drawable.ic_media_pause, "Stop", pendingStop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // Foreground first, then focus: the system refuses a focus request
        // made from the background, and the promotion is what lifts that.
        startForeground(NOTIFICATION_ID, notification)
        acquireAudioFocus()
        AudioService.running = true
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AudioService.running = false
        releaseAudioFocus()
        super.onDestroy()
    }
}

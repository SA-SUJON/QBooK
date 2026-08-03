package com.dustbook.app.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dustbook.app.R
import com.dustbook.app.ui.MainActivity

/**
 * Posts the notifications the scraper found.
 *
 * Two channels, not one, so the user can silence friend requests without
 * losing likes and comments - they are answered on very different timescales.
 * Both are ordinary channels: unlike the service notifications these are the
 * point of the feature, so they are allowed to make a sound and show a badge.
 */
object NotificationPresenter {

    const val CHANNEL_ACTIVITY = "dustbook_activity"
    const val CHANNEL_REQUESTS = "dustbook_requests"

    /** Well clear of the service ids (9001, 9002). */
    private const val ID_BASE = 4000

    /** Never post more than this in one pass, however many are new. */
    private const val MAX_PER_PASS = 5

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ACTIVITY,
                context.getString(R.string.notif_channel_activity),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_activity_sum)
                setShowBadge(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REQUESTS,
                context.getString(R.string.notif_channel_requests),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_requests_sum)
                setShowBadge(true)
            }
        )
    }

    /** True when the row reads like a friend request rather than activity. */
    fun isFriendRequest(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("friend request") ||
            t.contains("sent you a friend") ||
            t.contains("wants to be your friend") ||
            t.contains("accepted your friend") ||
            // Bangla, since that is the interface language here
            t.contains("বন্ধুত্বের অনুরোধ") ||
            t.contains("বন্ধু হতে চান")
    }

    fun post(context: Context, items: List<NotificationScraper.Item>) {
        if (items.isEmpty()) return
        ensureChannels(context)

        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return

        items.take(MAX_PER_PASS).forEachIndexed { index, item ->
            val request = isFriendRequest(item.text)
            val channel = if (request) CHANNEL_REQUESTS else CHANNEL_ACTIVITY

            // Tapping opens the item itself, not a generic screen. The URL is
            // handed to MainActivity as a VIEW intent, which is the same path
            // an external link takes, so nothing new has to interpret it.
            val target = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                data = android.net.Uri.parse(resolveUrl(item.url))
            }
            val pending = PendingIntent.getActivity(
                context,
                ID_BASE + index,
                target,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val n = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_bell)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(item.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(item.text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            try {
                nm.notify(ID_BASE + index, n)
            } catch (e: SecurityException) {
                // Permission revoked between the check and here.
            }
        }
    }

    /**
     * Make a row's target absolute.
     *
     * The page gives relative hrefs, and the lite renderer often gives nothing
     * usable at all - in which case the notifications screen itself is the
     * honest destination, rather than guessing at a story id.
     */
    fun resolveUrl(raw: String): String {
        val u = raw.trim()
        if (u.isEmpty()) return "https://m.facebook.com/notifications"
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("/")) return "https://m.facebook.com$u"
        // litelink://screen/... and friends cannot be opened directly.
        return "https://m.facebook.com/notifications"
    }
}

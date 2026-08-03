package com.dustbook.app.utils

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Checks for new Facebook notifications on a schedule.
 *
 * WorkManager rather than a service or an alarm: this has to keep working
 * after the app is closed and after a reboot, and it must not hold a permanent
 * notification the way [com.dustbook.app.ui.SyncService] has to. Fifteen
 * minutes is the shortest period WorkManager allows for periodic work; asking
 * for less would silently be rounded up to it anyway.
 */
class NotificationWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)

        if (!prefs.pushNotifications) return Result.success()
        if (!UrlHelper.isLoggedIn()) return Result.success()

        // The scraper is callback-based and hops to the main thread to drive
        // its WebView, so block this worker thread until it reports back.
        val latch = CountDownLatch(1)
        var items: List<NotificationScraper.Item> = emptyList()

        NotificationScraper.fetch(ctx) {
            items = it
            latch.countDown()
        }

        val answered = try {
            latch.await(90, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!answered) return Result.success()
        if (items.isEmpty()) return Result.success()

        val seen = NotificationStore.seen(ctx)
        val fresh = items.filter { it.id.isNotEmpty() && !seen.contains(it.id) }

        // Record everything first, so a crash between here and posting cannot
        // produce the same notification twice.
        NotificationStore.remember(ctx, items.map { it.id })

        if (NotificationStore.isFirstRun(ctx)) {
            // Seed only. Everything on the page right now is already known to
            // the user; announcing it would be a burst of noise.
            NotificationStore.markFirstRunDone(ctx)
            return Result.success()
        }

        if (fresh.isNotEmpty()) {
            NotificationPresenter.post(ctx, fresh)
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "dustbook_notifications"
        private const val INTERVAL_MINUTES = 15L

        /** Schedule, or cancel, according to the user's setting. */
        fun sync(context: Context, prefs: Prefs) {
            if (prefs.pushNotifications) schedule(context) else cancel(context)
        }

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<NotificationWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            ).setConstraints(constraints).build()

            try {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    // KEEP, not UPDATE: replacing the request on every launch
                    // restarts the period, and an app opened often would then
                    // never reach the end of one.
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            } catch (e: Exception) {
                // WorkManager unavailable: the feature is simply off.
            }
        }

        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            } catch (e: Exception) {
            }
        }
    }
}

package com.dustbook.app

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import com.dustbook.app.utils.AdBlocker
import com.dustbook.app.utils.AppExecutors
import com.dustbook.app.utils.NotificationPresenter
import com.dustbook.app.utils.NotificationWorker
import com.dustbook.app.utils.OfflineCache
import com.dustbook.app.utils.OfflineDocs
import com.dustbook.app.utils.OfflineFeed
import com.dustbook.app.utils.Prefs
import com.dustbook.app.utils.UpdateWatcher

class DustbookApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val prefs = Prefs(this)

        // Apply the user's theme choice before any Activity is created.
        AppCompatDelegate.setDefaultNightMode(prefs.nightMode())

        // Seed the blocker state so the very first request is filtered.
        AdBlocker.enabled = prefs.adBlock
        AdBlocker.cosmeticEnabled = prefs.cosmeticFilter

        // The offline stores are read from the settings screen as well as the
        // main activity, so initialise them once here rather than depending on
        // which screen the user happens to open first.
        OfflineCache.init(this)
        OfflineFeed.init(this)
        OfflineDocs.init(this)
        // Reading is always permitted; only saving follows the switches.
        OfflineCache.enabled = prefs.offlineRead
        OfflineFeed.enabled = prefs.offlineRead
        OfflineDocs.enabled = prefs.offlineRead
        OfflineCache.writeEnabled = prefs.offlineMode
        OfflineFeed.writeEnabled = prefs.offlineMode
        OfflineDocs.writeEnabled = prefs.offlineMode

        // Watch for releases process-wide. Checking only in MainActivity's
        // onCreate meant an update published while the app was open went
        // unnoticed until the next cold start.
        UpdateWatcher.start(this)

        // Count launches, so the support prompt can wait until the app has
        // been used enough to have been worth something. Incremented here
        // rather than in an Activity: this runs exactly once per process.
        prefs.launchCount = prefs.launchCount + 1

        // Stamp the very first launch, so the support prompt can wait a
        // fixed number of days from install rather than a launch count.
        if (prefs.firstLaunchAt == 0L) prefs.firstLaunchAt = System.currentTimeMillis()

        // Re-assert the notification schedule on every process start. The
        // work itself survives a reboot, but the channels do not exist until
        // something creates them, and a cancelled schedule has to stay
        // cancelled if the user turned the switch off while we were not
        // running.
        if (prefs.pushNotifications) NotificationPresenter.ensureChannels(this)
        NotificationWorker.sync(this, prefs)

        // Start Chromium during app startup rather than on the first frame of
        // MainActivity. Without this the very first load pays for the whole
        // WebView provider init, which is the long delay after a cold start.
        try {
            android.webkit.CookieManager.getInstance()
        } catch (e: Exception) { /* provider unavailable, not fatal */ }

        // Ensure the launcher icon matches the user's saved preference.
        syncAppIcon(prefs)

        // Required when more than one process uses a WebView.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val process = getProcessName()
            if (packageName != process) WebView.setDataDirectorySuffix(process)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        // Best-effort cleanup of background executors.
        // Note: onTerminate() is not guaranteed to be called on modern Android.
        try {
            AppExecutors.shutdown()
        } catch (e: Exception) {
            // Swallow — process is dying anyway
        }
    }

    /** On every cold start, re-apply the user's icon choice so the launcher
     *  always reflects the saved preference even after system updates. */
    private fun syncAppIcon(prefs: Prefs) {
        val idx = prefs.appIcon
        val pm = packageManager
        for (i in 0..12) {
            val cn = android.content.ComponentName(
                packageName, "$packageName.ui.SplashActivityIcon$i"
            )
            val state = if (i == idx)
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            try {
                pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP)
            } catch (e: Exception) { /* best effort */ }
        }
    }
}

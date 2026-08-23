package org.qbook.utils

import org.qbook.utils.AppExecutors
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * Watches for new releases from anywhere in the app.
 *
 * The old behaviour only checked in `MainActivity.onCreate`, behind a twelve
 * hour throttle. In practice that meant the user had to go into the hidden
 * settings and check by hand, because the app is rarely cold-started and the
 * throttle swallowed the rest. A published update could sit unnoticed for a
 * day.
 *
 * This runs for the whole process instead:
 *  - a check on every foreground, and periodically while the app is open
 *  - the result is held here, not in one Activity, so once a release is known
 *    the prompt appears on whichever screen the user is on
 *  - a prompt that has not been acted on is re-shown when the user moves to
 *    another screen, because updates are mandatory
 *
 * Nothing here touches the network on the main thread, and every failure is
 * silent - a missed check is not worth an error message.
 */
object UpdateWatcher {

    /** How often to poll while the app is in the foreground. */
    private const val POLL_INTERVAL_MS = 30L * 60 * 1000

    /** Grace period after a foreground event before checking again. */
    private const val FOREGROUND_MIN_GAP_MS = 5L * 60 * 1000

    private val main = Handler(Looper.getMainLooper())

    private var app: Application? = null
    private var current: WeakReference<Activity>? = null

    @Volatile private var started = false
    @Volatile private var checking = false
    @Volatile private var lastCheck = 0L

    /** Set once a newer release is found, and never cleared while it applies. */
    @Volatile var pending: UpdateChecker.Release? = null
        private set

    /**
     * Shows the mandatory update prompt. Set by whichever Activity can display
     * a dialog, so this object does not need to know about the UI.
     */
    @Volatile var presenter: ((Activity, UpdateChecker.Release, String) -> Unit)? = null

    private val poller = object : Runnable {
        override fun run() {
            checkNow(force = false)
            main.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start(application: Application) {
        if (started) return
        started = true
        app = application

        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    current = WeakReference(activity)
                    // A release found while the user was on another screen
                    // must not wait for the next poll to be offered.
                    if (pending != null) {
                        main.post { present(false) }
                    } else {
                        checkNow(force = false)
                    }
                }

                override fun onActivityPaused(activity: Activity) {
                    if (current?.get() === activity) current = null
                }

                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            }
        )

        main.postDelayed(poller, 20_000)
    }

    /**
     * @param force ignore the throttle. Used when the user asks explicitly.
     */
    fun checkNow(force: Boolean) {
        val application = app ?: return
        if (checking) return

        val prefs = Prefs(application)
        if (!force && (!prefs.autoUpdateCheck || prefs.updatePromptsSuppressed)) return

        // Already know about one: show it rather than asking GitHub again.
        if (pending != null) {
            main.post { present(force) }
            return
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < FOREGROUND_MIN_GAP_MS) return

        checking = true
        AppExecutors.background.execute {
            try {
                lastCheck = System.currentTimeMillis()
                val res = UpdateChecker.check(application, prefs)
                val rel = (res as? UpdateChecker.Result.Update)?.release
                if (rel != null) {
                    pending = rel
                    main.post { present(force) }
                }
            } catch (e: Exception) {
                // A failed check is not worth reporting; we try again later.
            } finally {
                checking = false
            }
        }
    }

    /** Show the prompt on whichever Activity is in front, if any. */
    private fun present(force: Boolean) {
        val rel = pending ?: return
        val activity = current?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val application = app ?: return
        val prefs = Prefs(application)
        if (!force && (prefs.updatePromptsSuppressed || prefs.updateSkippedVersion == rel.version)) return
        val local = UpdateChecker.currentVersion(application)
        // The version may have been installed since the check.
        if (!UpdateChecker.isNewer(rel.version, local)) {
            pending = null
            return
        }
        try {
            presenter?.invoke(activity, rel, local)
        } catch (e: Exception) {
            // Activity went away between the check and the dialog.
        }
    }

    /** Skip one release and optionally silence automatic prompts. */
    fun skip(version: String, suppressFuturePrompts: Boolean) {
        app?.let { application ->
            val prefs = Prefs(application)
            prefs.updateSkippedVersion = version
            if (suppressFuturePrompts) prefs.updatePromptsSuppressed = true
        }
        pending = null
    }

    /** Re-enable automatic prompts and explicitly check for the latest release. */
    fun showAgain() {
        app?.let { application ->
            val prefs = Prefs(application)
            prefs.updatePromptsSuppressed = false
            prefs.updateSkippedVersion = null
        }
        pending = null
        checkNow(force = true)
    }

    /** Called once the update has been installed, or no longer applies. */
    fun clear() {
        pending = null
    }
}

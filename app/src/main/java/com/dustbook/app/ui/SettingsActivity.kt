package com.dustbook.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import java.io.File
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.dustbook.app.R
import com.dustbook.app.utils.AdBlocker
import com.dustbook.app.utils.AppExecutors
import com.dustbook.app.utils.BlockList
import com.dustbook.app.utils.BackgroundSyncManager
import com.dustbook.app.utils.NetworkPolicy
import com.dustbook.app.utils.NotificationPresenter
import com.dustbook.app.utils.NotificationStore
import com.dustbook.app.utils.NotificationWorker
import com.dustbook.app.utils.OfflineCache
import com.dustbook.app.utils.OfflineDocs
import com.dustbook.app.utils.OfflineFeed
import com.dustbook.app.utils.OfflineManager
import com.dustbook.app.utils.OfflineSync
import com.dustbook.app.utils.Prefs
import com.dustbook.app.utils.SessionState
import com.dustbook.app.utils.UpdateChecker
import com.dustbook.app.utils.UpdateWatcher
import com.dustbook.app.viewmodel.MainViewModel

/**
 * Hidden settings. Not in the launcher, not reachable by long press.
 * Only entry point: three finger double tap on the main screen.
 *
 * Structure is a root menu of categories; each opens its own sub-screen so
 * related options live together instead of one endless list.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: androidx.appcompat.widget.Toolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Prefs(this).amoled) theme.applyStyle(R.style.ThemeOverlay_Amoled, true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onSupportNavigateUp() }

        val root = findViewById<android.view.View>(R.id.settings_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, RootFragment())
                .commit()
        }

        supportFragmentManager.addOnBackStackChangedListener { updateTitle() }
        updateTitle()
    }

    private fun updateTitle() {
        val f = supportFragmentManager.findFragmentById(R.id.settings_container)
        supportActionBar?.title = when (f) {
            is SubFragment -> getString(f.titleRes())
            else -> getString(R.string.hidden_settings)
        }
    }

    fun openSub(which: String) {
        val frag = SubFragment.create(which)
        // No transition animation: sub-screens appear instantly.
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, frag)
            .addToBackStack(which)
            .commit()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            finish()
        }
        return true
    }

    // ---------------------------------------------------------------- root

    class RootFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.hidden_settings, rootKey)
            listOf(
                "nav_blocking" to "blocking",
                "nav_home" to "home",
                "nav_appearance" to "appearance",
                "nav_browsing" to "browsing",
                "nav_offline" to "offline",
                "nav_privacy" to "privacy",
                "nav_about" to "about"
            ).forEach { (key, dest) ->
                findPreference<Preference>(key)?.setOnPreferenceClickListener {
                    (activity as? SettingsActivity)?.openSub(dest)
                    true
                }
            }
        }
    }

    // ---------------------------------------------------------------- sub

    class SubFragment : PreferenceFragmentCompat() {

        private lateinit var prefs: Prefs

        companion object {
            private const val ARG = "which"
            // Five minutes is the standard Android "About screen seven-tap"
            // window: long enough for a deliberate gesture, short enough
            // that a stray tap a week ago does not count toward today.
            private const val DEV_TAP_WINDOW_MS = 5L * 60L * 1000L

            fun create(which: String) = SubFragment().apply {
                arguments = Bundle().apply { putString(ARG, which) }
            }
        }

        private fun which(): String = arguments?.getString(ARG) ?: "blocking"

        fun titleRes(): Int = when (which()) {
            "blocking" -> R.string.cat_blocking
            "home" -> R.string.cat_home
            "appearance" -> R.string.cat_appearance
            "browsing" -> R.string.cat_browsing
            "offline" -> R.string.cat_offline
            "privacy" -> R.string.cat_data
            "developer" -> R.string.dev_options_title
            else -> R.string.cat_about
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val res = when (which()) {
                "blocking" -> R.xml.settings_blocking
                "home" -> R.xml.settings_home
                "appearance" -> R.xml.settings_appearance
                "browsing" -> R.xml.settings_browsing
                "offline" -> R.xml.settings_offline
                "privacy" -> R.xml.settings_privacy
                "developer" -> R.xml.settings_developer
                else -> R.xml.settings_about
            }
            setPreferencesFromResource(res, rootKey)
            prefs = Prefs(requireContext())
            // The Developer options screen is the only sub-screen that
            // has a toolbar action (the master developer-enabled
            // toggle). Tell the framework to call our menu callbacks;
            // other sub-screens stay menu-free.
            if (which() == "developer") setHasOptionsMenu(true)
            wire()
            // Fill it in now. It used to be written only from a change
            // listener, so opening the screen showed nothing and the count
            // appeared only after toggling something at random.
            refreshOfflineSize()
        }

        override fun onResume() {
            super.onResume()
            refreshOfflineSize()
            // Downloading carries on in the background, so keep the number
            // moving while the screen is open rather than freezing it at
            // whatever it was when the screen opened.
            tick = object : Runnable {
                override fun run() {
                    if (!isAdded) return
                    refreshOfflineSize()
                    view?.postDelayed(this, 2000)
                }
            }
            view?.postDelayed(tick!!, 2000)
        }

        override fun onPause() {
            super.onPause()
            tick?.let { view?.removeCallbacks(it) }
            tick = null
        }

        private var tick: Runnable? = null

        /**
         * One refresh at a time, process-wide cheap. The 2 s tick must
         * never queue a second count while the first is still walking the
         * vaults - see refreshOfflineSize().
         */
        private val refreshInFlight =
            java.util.concurrent.atomic.AtomicBoolean(false)

        private fun wire() {
            // ---- 7-tap on About → reveal Developer options ----
            // The Developer options entry is invisible by default in the
            // layout (android:visibility="gone"). Tapping the About this
            // app entry seven times within five minutes sets
            // developerEnabled to true, which makes the entry visible
            // (in About) and switches the toolbar toggle on (in
            // Developer options). The gesture is idempotent: tapping
            // seven times when the entry is already shown is a no-op
            // (no recreate, no toast), and tapping seven times when the
            // entry is hidden re-enables it. There is no "unlock" state
            // any more - the seven taps are a verb, not a flag.
            // The tap counter is a small integer in SharedPreferences and
            // resets when the first tap is older than five minutes - so
            // an accidental tap a month ago does not get half-credit
            // today.
            val sevenTap = findPreference<Preference>("app_version")
            sevenTap?.setOnPreferenceClickListener {
                if (prefs.developerEnabled) {
                    // Already enabled - still count taps so a tap that
                    // started before the unlock and crossed the
                    // threshold is handled, but do not show a toast or
                    // recreate. The user is looking at the entry; they
                    // do not need to be told it is there.
                    val now = System.currentTimeMillis()
                    val firstTap = prefs.devTapFirstAt
                    if (firstTap == 0L || now - firstTap > DEV_TAP_WINDOW_MS) {
                        prefs.devTapCount = 0
                        prefs.devTapFirstAt = 0L
                    } else if (prefs.devTapCount >= 4) {
                        prefs.devTapCount = prefs.devTapCount + 1
                    }
                    return@setOnPreferenceClickListener true
                }
                val now = System.currentTimeMillis()
                val firstTap = prefs.devTapFirstAt
                val taps = if (firstTap == 0L || now - firstTap > DEV_TAP_WINDOW_MS) {
                    prefs.devTapCount = 1
                    prefs.devTapFirstAt = now
                    1
                } else {
                    val next = prefs.devTapCount + 1
                    prefs.devTapCount = next
                    next
                }
                if (taps >= 7) {
                    prefs.developerEnabled = true
                    prefs.devTapCount = 0
                    prefs.devTapFirstAt = 0L
                    sevenTap.summary = getString(R.string.dev_options_sum)
                    // The dev entry was inflated visibility="gone",
                    // so it is in the tree but not drawn. setVisible
                    // inside a click handler does not redraw the
                    // PreferenceFragmentCompat reliably, and removing
                    // and re-adding the same instance to its parent
                    // screen has crashed the app on tap (the framework
                    // re-enters its own state machine during the same
                    // click). The reliable path is to recreate the
                    // enclosing activity: the About screen redraws
                    // with the entry visible, and a quick black flash
                    // is the only cost.
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            requireActivity().recreate()
                        } catch (e: Exception) {
                            // Worst case the recreate is rejected; the
                            // user still sees the toast and can navigate
                            // to About, where the entry will be visible
                            // on the next inflation.
                        }
                    }
                    toast(getString(R.string.dev_unlocked_toast))
                } else if (taps >= 4) {
                    toast("$taps / 7")
                }
                true
            }

            // Re-show or re-hide the Developer options entry on every
            // wire(). The page-top switch on the Developer options
            // screen can change developerEnabled after this fragment
            // is inflated (the user has to leave the About screen to
            // see the switch), so we cannot rely on layout time alone -
            // we re-read it on every entry into About.
            findPreference<Preference>("nav_developer")?.isVisible =
                prefs.developerEnabled

            // ---- about → developer nav ----
            findPreference<Preference>("nav_developer")?.setOnPreferenceClickListener {
                (activity as? SettingsActivity)?.openSub("developer")
                true
            }

            // ---- developer: logcat switch + view / share / clear buttons ----
            // The switch drives the in-process logger; the three buttons
            // open the file in a reader, share it, or clear it. The reader
            // is its own Activity so the Settings screen stays single-page
            // and the file can be much larger than any dialog body.
            //
            // The page-top "developer_enabled" switch is no longer a
            // row in this preference screen - the master toggle now
            // lives in the toolbar at the top of the Developer
            // options screen (set up in onCreateOptionsMenu). Below
            // is the in-page content: the logcat switch and the
            // view / share / clear buttons.
            findPreference<SwitchPreferenceCompat>(Prefs.KEY_DIAGNOSTIC_LOG)
                ?.setOnPreferenceChangeListener { _, v ->
                    prefs.diagnosticLog = v as Boolean
                    true
                }
            findPreference<Preference>("view_diagnostic_log")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), DiagnosticLogActivity::class.java))
                true
            }
            findPreference<Preference>("share_diagnostic_log")?.setOnPreferenceClickListener {
                val path = prefs.diagLog.path()
                val file = File(path)
                if (!file.exists()) {
                    toast(getString(R.string.diagnostic_log_empty))
                    return@setOnPreferenceClickListener true
                }
                // FileProvider is keyed off the application package; on a
                // SubFragment the packageName lives on the activity, not
                // on `this` (which is the fragment).
                val pkg = requireActivity().packageName
                val uri = FileProvider.getUriForFile(
                    requireContext(), "$pkg.fileprovider", file)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, getString(R.string.diagnostic_share_chooser)))
                true
            }
            findPreference<Preference>("clear_diagnostic_log")?.setOnPreferenceClickListener {
                prefs.diagLog.clear()
                toast(getString(R.string.diagnostic_log_cleared))
                true
            }

            // ---- blocking ----
            findPreference<SwitchPreferenceCompat>(Prefs.KEY_AD_BLOCK)
                ?.setOnPreferenceChangeListener { _, v ->
                    AdBlocker.enabled = v as Boolean; markDirty(true); true
                }
            findPreference<SwitchPreferenceCompat>(Prefs.KEY_COSMETIC)
                ?.setOnPreferenceChangeListener { _, v ->
                    AdBlocker.cosmeticEnabled = v as Boolean; markDirty(true); true
                }

            // Cosmetic-only: applied live, scroll position preserved.
            Prefs.SECTION_KEYS.keys.forEach { key ->
                findPreference<SwitchPreferenceCompat>(key)
                    ?.setOnPreferenceChangeListener { _, _ -> markDirty(false); true }
            }

            // Need a reload to take effect.
            listOf(
                Prefs.KEY_DESKTOP_MODE,
                Prefs.KEY_ZOOM, Prefs.KEY_AUTOPLAY_VIDEO, Prefs.KEY_INAPP_MESSAGING
            ).forEach { key ->
                findPreference<SwitchPreferenceCompat>(key)
                    ?.setOnPreferenceChangeListener { _, _ -> markDirty(true); true }
            }

            findPreference<Preference>("block_stats")?.summary =
                getString(R.string.blocked_count_fmt, prefs.blockCount)
            findPreference<Preference>("filter_info")?.summary =
                if (BlockList.isLoaded) {
                    getString(R.string.filters_fmt, "%,d".format(BlockList.size()))
                } else {
                    getString(R.string.filters_loading)
                }
            findPreference<Preference>("reset_stats")?.setOnPreferenceClickListener {
                prefs.blockCount = 0
                findPreference<Preference>("block_stats")?.summary =
                    getString(R.string.blocked_count_fmt, 0)
                true
            }

            // ---- appearance ----
            findPreference<ListPreference>(Prefs.KEY_DARK_MODE)
                ?.setOnPreferenceChangeListener { _, v ->
                    AppCompatDelegate.setDefaultNightMode(
                        when (v as String) {
                            Prefs.DARK_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                            Prefs.DARK_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        }
                    )
                    true
                }
            findPreference<SwitchPreferenceCompat>(Prefs.KEY_AMOLED)
                ?.setOnPreferenceChangeListener { _, _ ->
                    markDirty(false); activity?.recreate(); true
                }
            findPreference<SwitchPreferenceCompat>(Prefs.KEY_SHOW_PROGRESS)
                ?.setOnPreferenceChangeListener { _, _ -> markDirty(false); true }

            // Notifications: schedule or cancel the background check as soon
            // as the switch moves, rather than waiting for the next launch.
            findPreference<SwitchPreferenceCompat>(Prefs.KEY_PUSH_NOTIFICATIONS)
                ?.setOnPreferenceChangeListener { _, v ->
                    val on = v as Boolean
                    val ctx = requireContext().applicationContext
                    if (on) {
                        NotificationPresenter.ensureChannels(ctx)
                        NotificationWorker.schedule(ctx)
                    } else {
                        NotificationWorker.cancel(ctx)
                        // Forget what was seen, so switching back on seeds
                        // again instead of announcing a backlog.
                        NotificationStore.clear(ctx)
                    }
                    true
                }

            // ---- icon switching ----
            val iconPref = findPreference<Preference>(Prefs.KEY_APP_ICON)
            iconPref?.summary = getString(R.string.pref_icon_sum)
            iconPref?.setOnPreferenceClickListener {
                showIconPickerDialog(prefs.appIcon)
                true
            }

            // ---- offline ----
            // The master switch was removed; the three section switches are
            // the control now, so any of them changing re-evaluates whether
            // offline saving is on at all.
            listOf(
                Prefs.KEY_OFFLINE_REELS,
                Prefs.KEY_OFFLINE_FEED,
                Prefs.KEY_OFFLINE_STORIES
            ).forEach { key ->
                findPreference<SwitchPreferenceCompat>(key)
                    ?.setOnPreferenceChangeListener { _, _ ->
                        // Read after the value is committed, on the next pass.
                        view?.post {
                            // Only saving is switched. Content already on
                            // disk stays readable either way.
                            val write = Prefs(requireContext()).offlineMode
                            OfflineCache.writeEnabled = write
                            OfflineFeed.writeEnabled = write
                            OfflineDocs.writeEnabled = write
                            refreshOfflineSize()
                        }
                        markDirty(false); true
                    }
            }

            // Choosing "Wi-Fi and mobile data" should take effect now, not at
            // the next resume: the user has just said they want it running.
            findPreference<ListPreference>(Prefs.KEY_OFFLINE_WIFI_ONLY)
                ?.setOnPreferenceChangeListener { _, _ ->
                    view?.post {
                        val ctx = requireContext().applicationContext
                        if (NetworkPolicy.canDownload(ctx, Prefs(ctx))) {
                            BackgroundSyncManager.start()
                        }
                        refreshOfflineSize()
                    }
                    markDirty(false); true
                }

            findPreference<Preference>("clear_offline")?.setOnPreferenceClickListener {
                confirm(R.string.pref_clear_offline, R.string.confirm_clear_offline) {
                    OfflineCache.clear()
                    OfflineFeed.clear()
                    OfflineDocs.clear()
                    refreshOfflineSize()
                    toast(getString(R.string.offline_cleared))
                }
                true
            }

            // ---- privacy ----
            findPreference<Preference>("clear_cache")?.setOnPreferenceClickListener {
                clearCache(); true
            }
            findPreference<Preference>("clear_cookies")?.setOnPreferenceClickListener {
                confirm(R.string.clear_cookies, R.string.confirm_logout) { clearCookies() }
                true
            }
            findPreference<Preference>("clear_all_data")?.setOnPreferenceClickListener {
                confirm(R.string.clear_all_data, R.string.confirm_reset) { clearAllData() }
                true
            }

            // ---- updates ----
            findPreference<Preference>("check_update")?.setOnPreferenceClickListener { pref ->
                pref.summary = getString(R.string.update_checking)
                val ctx = requireContext().applicationContext
                AppExecutors.background.execute {
                    val res = UpdateChecker.check(ctx, prefs)
                    val local = UpdateChecker.currentVersion(ctx)
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        pref.summary = when (res) {
                            is UpdateChecker.Result.Update -> {
                                showUpdateDialog(res.release, local)
                                getString(R.string.update_available)
                            }
                            is UpdateChecker.Result.UpToDate ->
                                getString(R.string.update_none_fmt, local)
                            is UpdateChecker.Result.NoReleases ->
                                getString(R.string.update_no_releases)
                            is UpdateChecker.Result.Offline ->
                                getString(R.string.update_offline)
                            is UpdateChecker.Result.Failed ->
                                getString(R.string.update_failed_fmt, res.reason)
                        }
                    }
                }
                true
            }

            // ---- about ----
            // (Round 28: KEY_INSPECT_ADS listener removed - the
            // "Inspect ads" preference is no longer surfaced in
            // settings_developer.xml, so the findPreference lookup was a
            // permanent null. Prefs.KEY_INSPECT_ADS is kept for the
            // persisted state, in case a future build wires the toggle
            // to a long-press gesture again.)

            findPreference<Preference>("app_version")?.summary = try {
                requireContext().packageManager
                    .getPackageInfo(requireContext().packageName, 0).versionName
            } catch (e: Exception) { "2.0.0" }

            // ---- developer info ----
            findPreference<Preference>("dev_info")?.setOnPreferenceClickListener {
                showDeveloperDialog()
                true
            }

            // Asked for explicitly, so it always opens - the "Don't show
            // again" box silences the automatic prompt, not this.
            findPreference<Preference>("support_dev")?.setOnPreferenceClickListener {
                activity?.let { SupportPrompt.showNow(it) }
                true
            }
        }

        /**
         * Updates are mandatory, so there is no skip.
         *
         * This used to close the settings screen and ask MainActivity to run
         * the flow on resume, which meant the user tapped Download and watched
         * the screen simply disappear. The prompt handles the download and the
         * install itself now, on whichever screen it is shown from.
         */
        private fun showUpdateDialog(rel: UpdateChecker.Release, local: String) {
            val a = activity ?: return
            UpdatePrompt.show(a, rel, local)
        }

        /**
         * When the offline library was last actually updated.
         *
         * Written only after a pass that stored something, so this reports
         * the state of the library rather than the last attempt.
         */
        private fun lastSyncText(p: Prefs): String {
            val t = p.offlineLastSync
            if (t <= 0L) return getString(R.string.offline_last_never)
            val ago = System.currentTimeMillis() - t
            val mins = ago / 60_000
            return when {
                mins < 1 -> "just now"
                mins < 60 -> "$mins min ago"
                mins < 1440 -> "${mins / 60} h ago"
                else -> "${mins / 1440} d ago"
            }
        }

        /**
         * No-op on every screen except Offline, which is where the row is.
         *
         * Nothing below the guard may touch the disk on the calling thread.
         * The count is realPlayableCount(), which loads and parses every
         * vault's full store and stats every referenced media file; the size
         * is three recursive directory walks. Until this build all of it ran
         * on the MAIN thread - once when the screen opened, once on resume,
         * and then EVERY TWO SECONDS from the tick above. With a grown vault
         * (hundreds of entries, a thousand-plus media files, worse while a
         * download phase is writing into those same directories) each pass
         * costs long enough that the screen visibly hitches, then freezes -
         * "settings e dhukle thamia jai". The earlier read of this code
         * mistook the update-check's executor (further up, on a click) for
         * this path; verified line by line: these calls sat on the caller.
         */
        override fun onCreateOptionsMenu(menu: android.view.Menu, inflater: android.view.MenuInflater) {
            super.onCreateOptionsMenu(menu, inflater)
            if (which() != "developer") return
            inflater.inflate(R.menu.menu_developer, menu)
            val item = menu.findItem(R.id.action_developer_toggle)
            val sw = item?.actionView?.findViewById<androidx.appcompat.widget.SwitchCompat>(
                R.id.developer_toggle_switch)
            if (sw != null) {
                // Reflect the persisted state on every inflation. The
                // switch is the only thing the user can flip from this
                // screen to re-hide the Developer options entry, so
                // its visible state must match the persisted value.
                sw.isChecked = prefs.developerEnabled
                sw.setOnCheckedChangeListener { _, isChecked ->
                    prefs.developerEnabled = isChecked
                    if (!isChecked) {
                        toast(getString(R.string.dev_disabled_toast))
                    } else {
                        toast(getString(R.string.dev_enabled_toast))
                    }
                    // The About fragment reads developerEnabled in
                    // wire() - it will see the new value the next time
                    // the user navigates to About. We do not call
                    // activity.recreate() here because the user is on
                    // this screen, not on About, and a recreate would
                    // lose the menu state.
                }
            }
        }

        override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
            // The switch's own change listener handles the toggle, so
            // the menu item only needs to return true to consume.
            return true
        }

        private fun refreshOfflineSize() {
            if (findPreference<Preference>("offline_status") == null) return
            // Skip rather than stack: one refresh may still be counting a
            // large vault when the next 2 s tick fires, and the pool's
            // caller-runs rejection policy would otherwise pull the disk
            // work back onto the main thread it was moved off.
            if (!refreshInFlight.compareAndSet(false, true)) return
            val app = activity
            val ctx = context?.applicationContext
            if (app == null || ctx == null) {
                refreshInFlight.set(false)
                return
            }
            // Everything fragment- or resource-touching is resolved HERE, on
            // the caller: Fragment.getString() and lastSyncText() throw on a
            // detached fragment, and a throw inside the pool task would also
            // strand the in-flight flag. System-service and volatile reads
            // (network caps, isRunning flags) are cheap and safe here.
            val p = Prefs(ctx)
            val pausedText = getString(R.string.offline_paused_metered) + " • "
            val agoText = lastSyncText(p)
            val working = OfflineSync.isRunning() || OfflineFeed.isPrefetching() || OfflineManager.isPreparingOffline()

            // Say why nothing is happening. Without this, "Wi-Fi only" on a
            // mobile connection looks identical to saving being broken.
            val paused = NetworkPolicy.blockedByMetered(ctx, p)

            val prefix = when {
                paused -> pausedText
                OfflineManager.isPreparingOffline() -> "Preparing fresh content • "
                working -> "Syncing • "
                else -> ""
            }
            val postTarget = p.offlinePostTarget
            val reelTarget = p.offlineReelTarget

            AppExecutors.background.execute {
                // One line: what is on disk, against the target, and how big
                // it is. Downloading is shown as it happens, so the user can
                // see it working rather than having to trust that it is.
                val mb = (OfflineCache.sizeBytes() + OfflineFeed.sizeBytes() +
                    OfflineDocs.sizeBytes()) / (1024.0 * 1024.0)

                // Count only items that will actually play offline (media is
                // really on disk), not just items whose markup was captured.
                // totalStored() used to be shown here, so "Reels: 50 of 50"
                // could be true while most of those 50 still had videos
                // sitting in the download queue - the number looked complete
                // when it was not.
                val rc = OfflineFeed.realPlayableCount(OfflineFeed.SECTION_REELS)
                val fc = OfflineFeed.realPlayableCount(OfflineFeed.SECTION_FEED)
                val sc = OfflineFeed.realPlayableCount(OfflineFeed.SECTION_STORIES)
                val statusLine = "Posts: " + fc + " of " + postTarget +
                    "  •  Reels: " + rc + " of " + reelTarget +
                    "  •  Stories: " + sc +
                    "  •  " + "%.0f".format(mb) + " MB  •  " + agoText
                val finalText =
                    (if (working || OfflineManager.isPreparingOffline()) prefix else "") + statusLine
                // Only assembling the text happens off the main thread; a
                // Preference is a View-tree object, so the write goes back.
                app.runOnUiThread {
                    refreshInFlight.set(false)
                    if (isAdded) {
                        findPreference<Preference>("offline_status")?.summary =
                            finalText
                    }
                }
            }
        }

        /**
         * @param reload true when the change needs a full page reload.
         *               Cosmetic changes re-run the filter in place instead,
         *               so the user keeps their position in the feed.
         */
        private fun markDirty(reload: Boolean) {
            MainViewModel.pendingSettingsChange = true
            if (reload) MainViewModel.pendingReload = true
        }

        private fun confirm(titleRes: Int, msgRes: Int, action: () -> Unit) {
            AlertDialog.Builder(requireContext())
                .setTitle(titleRes)
                .setMessage(msgRes)
                .setPositiveButton(android.R.string.ok) { _, _ -> action() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /** Application context: never leaks the Activity. */
        private fun webView(): WebView = WebView(requireContext().applicationContext)

        private fun clearCache() {
            webView().apply {
                clearCache(true); clearFormData(); clearHistory(); destroy()
            }
            WebStorage.getInstance().deleteAllData()
            toast(getString(R.string.cache_cleared))
        }

        private fun clearCookies() {
            CookieManager.getInstance().apply {
                removeAllCookies { flush() }   // flush AFTER removal completes
                removeSessionCookies(null)
            }
            toast(getString(R.string.cookies_cleared))
        }

        private fun clearAllData() {
            val ctx = requireContext().applicationContext
            clearCookies()
            webView().apply { clearCache(true); clearFormData(); clearHistory(); destroy() }
            WebStorage.getInstance().deleteAllData()
            OfflineCache.clear()
            // The saved WebView history lives in filesDir, not cacheDir, so
            // deleting the cache left it behind. A reset that leaves the old
            // back-forward list on disk is not a reset: the next cold start
            // restores whatever page was showing when the app last closed.
            SessionState.clear(ctx)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().clear().apply()
            ctx.cacheDir.deleteRecursively()

            toast(getString(R.string.all_data_cleared))
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(launch)
            }
            activity?.finishAffinity()
        }

        private fun toast(m: String) =
            Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()

        /**
         * TEMPORARY diagnostic for the offline-download-never-starts bug.
         * Shows why the last BackgroundSyncManager.start() / OfflineSync.run()
         * call did or did not proceed, with a Copy button so the text can be
         * pasted elsewhere without adb. Remove together with the
         * "sync_diagnostics" preference and its click listener once the bug
         * is confirmed fixed.
         */
        private fun showSyncDiagnosticsDialog() {
            val ctx = requireContext()
            val msg = "Pipeline (BackgroundSyncManager.start):\n" +
                com.dustbook.app.utils.BackgroundSyncManager.lastBlockReason +
                "\n\nWorker (OfflineSync.run):\n" +
                com.dustbook.app.utils.OfflineSync.lastRunBlockReason
            AlertDialog.Builder(ctx)
                .setTitle("Sync diagnostics")
                .setMessage(msg)
                .setPositiveButton("Copy") { _, _ ->
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("Sync diagnostics", msg))
                    toast("Copied")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun showDeveloperDialog() {
            val ctx = requireContext()
            val msg = getString(R.string.dev_bio) + "\n\n" +
                getString(R.string.dev_whatsapp) + "\n" +
                getString(R.string.dev_email) + "\n" +
                getString(R.string.dev_website)
            AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.dev_name) + " — " + getString(R.string.dev_title))
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        /** Enable one launcher alias and disable the other 12. */
        private fun applyAppIcon(index: Int) {
            val ctx = requireContext().applicationContext
            val pm = ctx.packageManager
            val pkg = ctx.packageName

            for (i in 0..12) {
                val cn = android.content.ComponentName(pkg, "$pkg.ui.SplashActivityIcon$i")
                val state = if (i == index) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                try {
                    pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP)
                } catch (e: Exception) { /* best effort */ }
            }

            // Persist the choice so it sticks across restarts.
            prefs.sp.edit().putString(Prefs.KEY_APP_ICON, index.toString()).apply()

            // Update the preference summary to reflect the new choice.
            val labels = resources.getStringArray(R.array.icon_entries)
            val idxLabel = findLabelFor(index, labels)
            findPreference<Preference>(Prefs.KEY_APP_ICON)?.summary =
                getString(R.string.pref_icon_sum) + " — " + idxLabel
        }

        /** Find the display label for a numeric icon index. */
        private fun findLabelFor(idx: Int, labels: Array<String>): String {
            val values = resources.getStringArray(R.array.icon_values)
            for (i in values.indices) {
                if (values[i].toIntOrNull() == idx) return labels[i]
            }
            return labels[0]
        }

        private fun iconRes(index: Int): Int = when (index) {
            1 -> R.mipmap.ic_launcher_alt1; 2 -> R.mipmap.ic_launcher_alt2
            3 -> R.mipmap.ic_launcher_alt3; 4 -> R.mipmap.ic_launcher_alt4
            5 -> R.mipmap.ic_launcher_alt5; 6 -> R.mipmap.ic_launcher_alt6
            7 -> R.mipmap.ic_launcher_alt7; 8 -> R.mipmap.ic_launcher_alt8
            9 -> R.mipmap.ic_launcher_alt9; 10 -> R.mipmap.ic_launcher_alt10
            11 -> R.mipmap.ic_launcher_alt11; 12 -> R.mipmap.ic_launcher_alt12
            else -> R.mipmap.ic_launcher
        }



        private fun showIconPickerDialog(currentIdx: Int) {
            val ctx = requireContext()
            val values = resources.getStringArray(R.array.icon_values)
            val d = resources.displayMetrics.density

            // 3 per row so 9 of the 13 icons are visible without scrolling;
            // the rest reachable by scrolling the grid itself. Selection is
            // shown as a ring rather than a name, so it reads at a glance
            // instead of being read. Tapping an icon moves the ring there
            // immediately and zooms the icon slightly - a live preview of
            // the pending choice - but nothing is applied until OK; Cancel
            // leaves the real selection untouched.
            // Round 28: bigger preview. The old sizes (52dp icon, 84dp
            // cell) read as a grid of thumbnails in the dialog, not as
            // previews; a user picking a launcher icon wants to see
            // what the icon will actually look like in their launcher
            // drawer. Roughly 1.5x on the icon and the cell, the ring
            // and the dialog height scaled to match, two cells per row
            // (so the user can see fewer rows in better detail than
            // three cramped ones). The ring scales with the icon so the
            // selected-vs-not distinction still reads at a glance.
            val cellSize = (80 * d).toInt()
            val cellBox = (120 * d).toInt()
            val cellMargin = (4 * d).toInt()
            val ringSize = (96 * d).toInt()

            val grid = android.widget.GridLayout(ctx).apply {
                columnCount = 2
                setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            }

            val scroll = android.widget.ScrollView(ctx).apply {
                addView(grid)
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (440 * d).toInt()
                )
            }

            var pendingIdx = currentIdx
            val rings = HashMap<Int, android.view.View>()
            val icons = HashMap<Int, android.widget.ImageView>()

            fun selectPending(i: Int) {
                pendingIdx = i
                for ((idx, ring) in rings) {
                    ring.visibility = if (idx == i)
                        android.view.View.VISIBLE else android.view.View.INVISIBLE
                }
                for ((idx, iv) in icons) {
                    iv.scaleX = if (idx == i) 1.12f else 1f
                    iv.scaleY = if (idx == i) 1.12f else 1f
                }
            }

            val dialog = AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.pref_icon))
                .setView(scroll)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    applyAppIcon(pendingIdx)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()

            for (vi in values.indices) {
                val i = values[vi].toIntOrNull() ?: continue
                if (i !in 0..12) continue

                val cell = android.widget.FrameLayout(ctx).apply {
                    layoutParams = android.widget.GridLayout.LayoutParams(
                        android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                        android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    ).apply {
                        width = 0
                        height = cellBox
                        setMargins(cellMargin, cellMargin, cellMargin, cellMargin)
                    }
                    foreground = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                        null,
                        android.graphics.drawable.ShapeDrawable(
                            android.graphics.drawable.shapes.OvalShape()
                        )
                    )
                    isClickable = true
                    isFocusable = true
                }

                val ring = android.view.View(ctx).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        ringSize, ringSize, android.view.Gravity.CENTER
                    )
                    setBackgroundResource(R.drawable.bg_icon_selected_ring)
                    visibility = if (i == currentIdx)
                        android.view.View.VISIBLE else android.view.View.INVISIBLE
                }
                rings[i] = ring

                val img = android.widget.ImageView(ctx).apply {
                    setImageResource(iconRes(i))
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        cellSize, cellSize, android.view.Gravity.CENTER
                    )
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    if (i == currentIdx) { scaleX = 1.12f; scaleY = 1.12f }
                }
                icons[i] = img

                cell.addView(ring)
                cell.addView(img)
                cell.setOnClickListener { selectPending(i) }
                grid.addView(cell)
            }

            dialog.show()
        }
    }
}

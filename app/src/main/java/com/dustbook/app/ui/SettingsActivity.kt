package com.dustbook.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
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
                else -> R.xml.settings_about
            }
            setPreferencesFromResource(res, rootKey)
            prefs = Prefs(requireContext())
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

        private fun wire() {
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
            findPreference<SwitchPreferenceCompat>(Prefs.KEY_INSPECT_ADS)
                ?.setOnPreferenceChangeListener { _, _ -> markDirty(true); true }

            findPreference<Preference>("app_version")?.summary = try {
                requireContext().packageManager
                    .getPackageInfo(requireContext().packageName, 0).versionName
            } catch (e: Exception) { "2.0.0" }

            // ---- developer info ----
            findPreference<Preference>("dev_info")?.setOnPreferenceClickListener {
                showDeveloperDialog()
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

        /** No-op on every screen except Offline, which is where the row is. */
        private fun refreshOfflineSize() {
            if (findPreference<Preference>("offline_status") == null) return
            // One line: what is on disk, against the target, and how big it
            // is. Downloading is shown as it happens, so the user can see it
            // working rather than having to trust that it is.
            val p = Prefs(requireContext())
            val mb = (OfflineCache.sizeBytes() + OfflineFeed.sizeBytes() +
                OfflineDocs.sizeBytes()) / (1024.0 * 1024.0)

            val working = OfflineSync.isRunning() || OfflineFeed.isPrefetching() || OfflineManager.isPreparingOffline()

            // V4 Step 2: Show preparation status so user knows we are fetching fresh content
            // Say why nothing is happening. Without this, "Wi-Fi only" on a
            // mobile connection looks identical to saving being broken.
            val paused = NetworkPolicy.blockedByMetered(requireContext(), p)

            val prefix = when {
                paused -> getString(R.string.offline_paused_metered) + " • "
                OfflineManager.isPreparingOffline() -> "Preparing fresh content • "
                working -> "Syncing • "
                else -> ""
            }

            // Count only items that will actually play offline (media is
            // really on disk), not just items whose markup was captured.
            // totalStored() used to be shown here, so "Reels: 50 of 50"
            // could be true while most of those 50 still had videos
            // sitting in the download queue - the number looked complete
            // when it was not.
            val rc = OfflineFeed.realPlayableCount(OfflineFeed.SECTION_REELS)
            val fc = OfflineFeed.realPlayableCount(OfflineFeed.SECTION_FEED)
            val sc = OfflineFeed.realPlayableCount(OfflineFeed.SECTION_STORIES)
            val statusLine = "Posts: " + fc + "  •  Reels: " + rc +
                " of " + p.offlineReelTarget + "  •  Stories: " + sc +
                "  •  " + "%.0f".format(mb) + " MB  •  " + lastSyncText(p)
            findPreference<Preference>("offline_status")?.summary =
                (if (working || OfflineManager.isPreparingOffline()) prefix else "") + statusLine
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

            // Compact grid, 4 per row, no labels - the icon itself is the
            // only thing being chosen between, so nothing else needs to
            // compete for attention. Selection is shown as a ring rather
            // than a name, so it reads at a glance instead of being read.
            val cellSize = (64 * d).toInt()
            val cellMargin = (6 * d).toInt()
            val ringPadding = (6 * d).toInt()

            val grid = android.widget.GridLayout(ctx).apply {
                columnCount = 4
                setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
            }

            val scroll = android.widget.ScrollView(ctx).apply {
                addView(grid)
            }

            val dialog = AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.pref_icon))
                .setView(scroll)
                .setPositiveButton(android.R.string.cancel, null)
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
                        height = cellSize + ringPadding * 2
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

                val img = android.widget.ImageView(ctx).apply {
                    setImageResource(iconRes(i))
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        cellSize, cellSize, android.view.Gravity.CENTER
                    )
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }

                val ring = android.view.View(ctx).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        cellSize + ringPadding, cellSize + ringPadding, android.view.Gravity.CENTER
                    )
                    setBackgroundResource(R.drawable.bg_icon_selected_ring)
                    visibility = if (i == currentIdx) android.view.View.VISIBLE else android.view.View.INVISIBLE
                }

                cell.addView(ring)
                cell.addView(img)
                cell.setOnClickListener {
                    applyAppIcon(i)
                    dialog.dismiss()
                }
                grid.addView(cell)
            }

            dialog.show()
        }
    }
}

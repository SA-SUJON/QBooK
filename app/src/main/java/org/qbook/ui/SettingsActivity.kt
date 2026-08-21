package org.qbook.ui

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
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import org.qbook.R
import org.qbook.utils.AdBlocker
import org.qbook.utils.AppExecutors
import org.qbook.utils.BlockList
import org.qbook.utils.BackgroundSyncManager
import org.qbook.utils.Diag
import org.qbook.utils.DiagCapture
import org.qbook.utils.DiagnosticExport
import org.qbook.utils.NetworkPolicy
import org.qbook.utils.NotificationPresenter
import org.qbook.utils.NotificationStore
import org.qbook.utils.NotificationWorker
import org.qbook.utils.OfflineCache
import org.qbook.utils.OfflineDocs
import org.qbook.utils.OfflineFeed
import org.qbook.utils.OfflineManager
import org.qbook.utils.OfflineSync
import org.qbook.utils.Prefs
import org.qbook.utils.SessionState
import org.qbook.utils.UpdateChecker
import org.qbook.utils.UpdateWatcher
import org.qbook.viewmodel.MainViewModel

/**
 * QBooK Control Center.
 *
 * The host owns one PreferenceFragmentCompat. Each top-level category is an
 * expandable section in this same screen; preference rows never navigate to a
 * second settings fragment.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_LABS = "org.qbook.open_labs"
    }

    private val isLabsScreen: Boolean
        get() = intent.getBooleanExtra(EXTRA_OPEN_LABS, false)

    private lateinit var toolbar: androidx.appcompat.widget.Toolbar
    private var glassSource: android.view.View? = null
    private var glassBackButton: android.view.View? = null
    private var glassTitle: android.view.View? = null
    private var glassScrollListener: android.view.ViewTreeObserver.OnScrollChangedListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Prefs(this).amoled) theme.applyStyle(R.style.ThemeOverlay_Amoled, true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        toolbar = findViewById(R.id.toolbar)
        toolbar.setContentInsetsRelative(0, 0)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.title = ""
        val backButton = findViewById<android.view.View>(R.id.settings_back_button)
        val titlePill = findViewById<android.view.View>(R.id.settings_title)
        val settingsSource = findViewById<android.view.View>(R.id.settings_container)
        if (Prefs(this).labsLiquidGlass) {
            backButton.background = LiquidGlassDrawable(
                this,
                settingsSource,
                LiquidGlassDrawable.Shape.CIRCLE
            )
            titlePill.background = LiquidGlassDrawable(
                this,
                settingsSource,
                LiquidGlassDrawable.Shape.PILL
            )
        }
        backButton.setOnClickListener { onSupportNavigateUp() }
        glassSource = settingsSource
        glassBackButton = backButton
        glassTitle = titlePill
        val scrollListener = android.view.ViewTreeObserver.OnScrollChangedListener {
            glassBackButton?.invalidate()
            glassTitle?.invalidate()
        }
        glassScrollListener = scrollListener
        settingsSource.viewTreeObserver.addOnScrollChangedListener(scrollListener)

        val root = findViewById<android.view.View>(R.id.settings_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.settings_container,
                    if (isLabsScreen) LabsFragment() else ControlCenterFragment()
                )
                .commit()
        }
        findViewById<android.widget.TextView>(R.id.settings_title)
            .text = getString(if (isLabsScreen) R.string.cat_labs else R.string.hidden_settings)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        glassSource?.viewTreeObserver?.let { observer ->
            if (observer.isAlive) {
                glassScrollListener?.let(observer::removeOnScrollChangedListener)
            }
        }
        (glassBackButton?.background as? LiquidGlassDrawable)?.release()
        (glassTitle?.background as? LiquidGlassDrawable)?.release()
        glassScrollListener = null
        glassSource = null
        glassBackButton = null
        glassTitle = null
        super.onDestroy()
    }

    class ControlCenterFragment : PreferenceFragmentCompat() {

        private lateinit var prefs: Prefs
        private val expandedSections = linkedMapOf<String, Boolean>()
        private var developerExpanded = false

        companion object {
            private const val STATE_EXPANDED = "qbook_expanded_sections"
            private const val STATE_DEVELOPER_EXPANDED = "qbook_developer_expanded"
            private const val DEV_TAP_WINDOW_MS = 5L * 60L * 1000L
            private val SECTION_KEYS = listOf(
                "section_appearance",
                "section_browsing",
                "section_blocking",
                "section_home",
                "section_offline",
                "section_privacy",
                "section_about"
            )
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            SECTION_KEYS.forEach { key -> expandedSections[key] = false }
            savedInstanceState?.getStringArrayList(STATE_EXPANDED)?.forEach {
                if (it in SECTION_KEYS) expandedSections[it] = true
            }
            developerExpanded = false
        }

        override fun onSaveInstanceState(outState: Bundle) {
            outState.putStringArrayList(
                STATE_EXPANDED,
                ArrayList(expandedSections.filterValues { it }.keys)
            )
            outState.putBoolean(STATE_DEVELOPER_EXPANDED, developerExpanded)
            super.onSaveInstanceState(outState)
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference is ListPreference && preference.key in setOf(
                    Prefs.KEY_DARK_MODE,
                    "offline_reel_count",
                    "offline_post_count",
                    "offline_network"
                )
            ) {
                val entries = preference.entries ?: return
                val values = preference.entryValues ?: return
                val selected = preference.findIndexOfValue(preference.value)
                AlertDialog.Builder(requireContext())
                    .setTitle(preference.dialogTitle ?: preference.title)
                    .setSingleChoiceItems(entries, selected) { dialog, which ->
                        val value = values[which].toString()
                        if (preference.callChangeListener(value)) {
                            preference.value = value
                            dialog.dismiss()
                        }
                    }
                    .setNegativeButton(R.string.dialog_dismiss, null)
                    .show()
            } else {
                super.onDisplayPreferenceDialog(preference)
            }
        }

        override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            // The Stitch layout uses card spacing rather than list separators.
            // PreferenceFragmentCompat installs a full-width DividerDecoration
            // by default; remove it so no rules appear behind the glass cards.
            setDivider(null)
            setDividerHeight(0)
            val density = resources.displayMetrics.density
            listView?.apply {
                setPadding((16 * density).toInt(), (84 * density).toInt(), (16 * density).toInt(), (28 * density).toInt())
                clipToPadding = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                post {
                    if (itemDecorationCount == 0) {
                        addItemDecoration(SettingsSectionCardDecoration(requireContext(), SECTION_KEYS.toSet()))
                    }
                }
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.settings_control_center, rootKey)
            SECTION_KEYS.forEach { key ->
                findPreference<ExpandablePreferenceCategory>(key)?.apply {
                    isSelectable = true
                    setOnPreferenceClickListener {
                        setSectionExpanded(key, expandedSections[key] != true)
                        true
                    }
                }
            }
            prefs = Prefs(requireContext())
            applySectionState()
            wire()
            refreshOfflineSize()
        }

        override fun onResume() {
            super.onResume()
            refreshOfflineSize()
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

        private fun setSectionExpanded(key: String, expanded: Boolean) {
            expandedSections[key] = expanded
            val category = findPreference<PreferenceCategory>(key) ?: return
            forEachChild(category) { child -> setVisibleRecursively(child, expanded) }
            updateChevron(category, expanded)
        }

        private fun applySectionState() {
            SECTION_KEYS.forEach { key ->
                val category = findPreference<PreferenceCategory>(key) ?: return@forEach
                val expanded = expandedSections[key] == true
                forEachChild(category) { child -> setVisibleRecursively(child, expanded) }
                updateChevron(category, expanded)
            }
        }

        private fun setVisibleRecursively(preference: Preference, visible: Boolean) {
            val effectiveVisible = when (preference.key) {
                "nav_developer" -> visible && prefs.developerEnabled
                "developer_options_group" -> visible && prefs.developerEnabled && developerExpanded
                else -> visible
            }
            preference.isVisible = effectiveVisible
            if (preference is androidx.preference.PreferenceGroup) {
                forEachChild(preference) { child -> setVisibleRecursively(child, effectiveVisible) }
            }
        }

        private fun forEachChild(
            group: androidx.preference.PreferenceGroup,
            action: (Preference) -> Unit
        ) {
            for (index in 0 until group.preferenceCount) {
                action(group.getPreference(index))
            }
        }

        private fun updateChevron(category: PreferenceCategory, expanded: Boolean) {
            (category as? ExpandablePreferenceCategory)?.expanded = expanded
        }

        private fun setDeveloperVisibility() {
            findPreference<Preference>("nav_developer")?.isVisible = prefs.developerEnabled
            findPreference<PreferenceCategory>("developer_options_group")?.let { group ->
                group.isVisible = prefs.developerEnabled && developerExpanded
                forEachChild(group) {
                    child -> setVisibleRecursively(child, prefs.developerEnabled && developerExpanded)
                }
            }
            if (!prefs.developerEnabled) developerExpanded = false
            requireActivity().invalidateOptionsMenu()
        }

        private fun expandDeveloperOptions() {
            if (!prefs.developerEnabled) return
            developerExpanded = !developerExpanded
            findPreference<PreferenceCategory>("developer_options_group")?.let { group ->
                group.isVisible = developerExpanded
                forEachChild(group) { child -> setVisibleRecursively(child, developerExpanded) }
            }
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
            setDeveloperVisibility()

            // ---- about → developer nav ----
            findPreference<Preference>("nav_developer")?.setOnPreferenceClickListener {
                expandDeveloperOptions()
                true
            }

            // ---- developer: logcat switch + view / share / clear buttons ----
            // The switch drives the in-process logger; the three buttons
            // open the file in a reader, share it, or clear it. The reader
            // is its own Activity so the Settings screen stays single-page
            // and the file can be much larger than any dialog body.
            //
            // Per-channel diagnostic log switches. The XML in
            // settings_developer.xml defines one
            // SwitchPreferenceCompat per Diag.Channel. The
            // toggle writes through to the Prefs boolean
            // for the matching key, and the toggle summary
            // is updated from the live "any channel
            // enabled" state so the developer can see at
            // a glance whether the subsystem is recording.
            findPreference<SwitchPreferenceCompat>(Prefs.KEY_DIAGNOSTIC_ALL)
                ?.setOnPreferenceChangeListener { _, v ->
                    prefs.setDiagAllEnabled(v as Boolean)
                    true
                }
            findPreference<Preference>("diag_mark")
                ?.setOnPreferenceClickListener {
                    DiagCapture.mark(requireContext(), "USER_MARK settings_button")
                    Toast.makeText(requireContext(), "Diagnostic marker added", Toast.LENGTH_SHORT).show()
                    true
                }

            val channelPrefs = listOf(
                Diag.Channel.HOME_FEED to Prefs.KEY_DIAGNOSTIC_HOME_FEED,
                Diag.Channel.REELS to Prefs.KEY_DIAGNOSTIC_REELS,
                Diag.Channel.STORY to Prefs.KEY_DIAGNOSTIC_STORY,
                Diag.Channel.ADS to Prefs.KEY_DIAGNOSTIC_ADS,
                Diag.Channel.NETWORK to Prefs.KEY_DIAGNOSTIC_NETWORK,
                Diag.Channel.OFFLINE_SAVE to Prefs.KEY_DIAGNOSTIC_OFFLINE_SAVE,
                Diag.Channel.APP_LIFECYCLE to Prefs.KEY_DIAGNOSTIC_LIFECYCLE,
            )
            for ((channel, key) in channelPrefs) {
                findPreference<SwitchPreferenceCompat>(key)
                    ?.setOnPreferenceChangeListener { _, v ->
                        prefs.setDiagChannelEnabled(channel, v as Boolean)
                        true
                    }
            }
            // Open the per-channel viewer. The view button
            // sits below the seven channel switches in the
            // XML, and from there the developer can pick a
            // channel, see the captures, clear, and export.
            findPreference<Preference>("view_diagnostic_log")
                ?.setOnPreferenceClickListener {
                    // Purge old export files before opening
                    // the viewer. Each export writes a
                    // new timestamped file in
                    // cacheDir/diagnostic/exports; the
                    // chooser reads from there. Purging
                    // on entry keeps that directory from
                    // growing across sessions.
                    org.qbook.utils.DiagnosticExport.purge(requireContext())
                    startActivity(Intent(requireContext(), DiagnosticLogActivity::class.java))
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
            val oledPreference = findPreference<SwitchPreferenceCompat>(Prefs.KEY_AMOLED)
            oledPreference?.isEnabled = prefs.darkMode == Prefs.DARK_DARK
            oledPreference?.isChecked = prefs.amoled
            findPreference<ListPreference>(Prefs.KEY_DARK_MODE)?.apply {
                setOnPreferenceChangeListener { _, v ->
                    val selected = v as String
                    if (selected != Prefs.DARK_DARK) {
                        prefs.amoled = false
                        oledPreference?.isChecked = false
                    }
                    oledPreference?.isEnabled = selected == Prefs.DARK_DARK
                    AppCompatDelegate.setDefaultNightMode(
                        when (selected) {
                            Prefs.DARK_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                            Prefs.DARK_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        }
                    )
                    true
                }
            }
            oledPreference?.setOnPreferenceChangeListener { _, _ ->
                markDirty(false); activity?.recreate(); true
            }
            findPreference<SwitchPreferenceCompat>(Prefs.KEY_MATERIAL_YOU)
                ?.setOnPreferenceChangeListener { _, _ -> markDirty(true); true }
            findPreference<SwitchPreferenceCompat>(Prefs.KEY_SHOW_PROGRESS)
                ?.setOnPreferenceChangeListener { _, _ -> markDirty(false); true }
            findPreference<SwitchPreferenceCompat>(Prefs.KEY_MEDIA_DOWNLOADER)
                ?.setOnPreferenceChangeListener { _, _ -> markDirty(true); true }

            // ---- QBooK Labs navigation ----
            findPreference<Preference>("labs_navigation")
                ?.setOnPreferenceClickListener {
                    startActivity(
                        Intent(requireContext(), SettingsActivity::class.java)
                            .putExtra(EXTRA_OPEN_LABS, true)
                    )
                    true
                }

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
            iconPref?.summary = iconSummary(prefs.appIcon)
            iconPref?.setOnPreferenceClickListener {
                showIconPickerDialog(prefs.appIcon)
                true
            }

            findPreference<ListPreference>("offline_reel_count")?.apply {
                summary = quotaSummary(entries, value, R.string.pref_offline_reel_count_sum)
                setOnPreferenceChangeListener { _, newValue ->
                    summary = quotaSummary(entries, newValue as String, R.string.pref_offline_reel_count_sum)
                    true
                }
            }
            findPreference<ListPreference>("offline_post_count")?.apply {
                summary = quotaSummary(entries, value, R.string.pref_offline_post_count_sum)
                setOnPreferenceChangeListener { _, newValue ->
                    summary = quotaSummary(entries, newValue as String, R.string.pref_offline_post_count_sum)
                    true
                }
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
                confirm(
                    R.string.confirm_clear_offline_title,
                    R.string.confirm_clear_offline,
                    R.string.confirm_abort,
                    R.string.confirm_execute_purge
                ) {
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
                confirm(
                    R.string.confirm_logout_title,
                    R.string.confirm_logout,
                    R.string.confirm_cancel,
                    R.string.confirm_sign_out
                ) { clearCookies() }
                true
            }
            findPreference<Preference>("clear_all_data")?.setOnPreferenceClickListener {
                confirm(
                    R.string.confirm_reset_title,
                    R.string.confirm_reset,
                    R.string.confirm_abort,
                    R.string.confirm_execute_reset
                ) { clearAllData() }
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

            // ---- permissions: explicit UI to grant the runtime
            // permissions the rest of the app relies on. The user
            // reported that photos / camera / notifications were
            // not actually being delivered even though the
            // manifest declared them; the cause is that on
            // Android 11+ these are runtime permissions and the
            // system has to ask, and the only previous path was
            // the OS dialog fired by the WebView's first
            // permission request - easy to dismiss and never
            // comes back. Each entry here opens the system
            // permission dialog for the right permission, with
            // a one-line summary that says what the user is
            // agreeing to. ----
            wirePermissionRow(
                "perm_notifications",
                granted = hasNotifPermission(),
                request = { requestNotifPermission() })
            wirePermissionRow(
                "perm_photos",
                granted = hasPhotosPermission(),
                request = { requestPhotosPermission() })
            wirePermissionRow(
                "perm_camera",
                granted = hasCameraPermission(),
                request = { requestCameraPermission() })
            findPreference<Preference>("check_notifications")
                ?.setOnPreferenceClickListener {
                    runNotificationCheckNow()
                    true
                }

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
            val working = OfflineSync.isRunning() || OfflineFeed.isPrefetching() || OfflineManager.isPreparingOffline()

            // Say why nothing is happening. Without this, "Wi-Fi only" on a
            // mobile connection looks identical to saving being broken.
            val paused = NetworkPolicy.blockedByMetered(ctx, p)
            val syncStatus = when {
                paused -> getString(R.string.offline_paused_metered)
                working -> "Downloading"
                else -> "Idle"
            }
            val reelTarget = p.offlineReelTarget
            val postTarget = p.offlinePostTarget
            val offlineStatusFormat = getString(R.string.offline_status_fmt)
            // The visible row continues to preserve the established logical
            // fields for posts, reels, and last-sync text while the exact
            // localized sentence is supplied by offline_status_fmt:
            // "Posts: " + fc + " of " + postTarget
            // "  •  Reels: " + rc + " of " + reelTarget
            // lastSyncText(p)

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
                // Only disk traversal and numeric aggregation happen off
                // the main thread. Resource formatting and Preference writes
                // return to the UI thread because both are view/context work.
                app.runOnUiThread {
                    refreshInFlight.set(false)
                    if (isAdded) {
                        findPreference<Preference>("offline_status")?.summary =
                            offlineStatusFormat.format(
                                fc,
                                rc,
                                reelTarget,
                                sc,
                                mb,
                                syncStatus
                            )
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

        private fun confirm(
            titleRes: Int,
            msgRes: Int,
            negativeRes: Int,
            positiveRes: Int,
            action: () -> Unit
        ) {
            AlertDialog.Builder(requireContext())
                .setTitle(titleRes)
                .setMessage(msgRes)
                .setPositiveButton(positiveRes) { _, _ -> action() }
                .setNegativeButton(negativeRes, null)
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

        // ---- permission helpers -----------------------------------
        // Each entry in the permissions row uses these. The
        // hasXxx / requestXxx pair is the only place that talks
        // to the platform; the row's on-click just routes
        // through them. Granted state is reflected back in the
        // preference summary so the user knows what is currently
        // set without having to dig into the system settings
        // app.
        private fun wirePermissionRow(
            key: String,
            granted: Boolean,
            request: () -> Unit
        ) {
            val pref = findPreference<Preference>(key) ?: return
            pref.summary = if (granted) {
                getString(R.string.perm_already_granted)
            } else {
                getString(R.string.perm_requesting)
            }
            pref.setOnPreferenceClickListener {
                if (granted) {
                    toast(getString(R.string.perm_already_granted))
                } else {
                    request()
                }
                true
            }
        }

        private fun hasNotifPermission(): Boolean {
            return if (android.os.Build.VERSION.SDK_INT >=
                android.os.Build.VERSION_CODES.TIRAMISU) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    requireContext(),
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
        }
        private fun requestNotifPermission() {
            if (android.os.Build.VERSION.SDK_INT >=
                android.os.Build.VERSION_CODES.TIRAMISU) {
                notifPermLauncher.launch(
                    android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        private fun hasPhotosPermission(): Boolean {
            val ctx = requireContext()
            val pm = ctx.packageManager
            return if (android.os.Build.VERSION.SDK_INT >=
                android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.checkPermission(
                    android.Manifest.permission.READ_MEDIA_IMAGES, ctx.packageName) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } else if (android.os.Build.VERSION.SDK_INT >=
                android.os.Build.VERSION_CODES.Q) {
                true  // scoped storage handles gallery access
            } else {
                pm.checkPermission(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    ctx.packageName) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }
        private fun requestPhotosPermission() {
            val perm = if (android.os.Build.VERSION.SDK_INT >=
                android.os.Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_IMAGES
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
            mediaPermLauncher.launch(perm)
        }

        private fun hasCameraPermission(): Boolean {
            return androidx.core.content.ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        private fun requestCameraPermission() {
            cameraPermLauncher.launch(android.Manifest.permission.CAMERA)
        }

        // Three ActivityResult launchers, one per permission
        // family. The result is checked and the corresponding
        // preference summary is updated; the user sees the
        // "Already granted" line if they accept and the default
        // "Opening system dialog" line if they do not.
        private val notifPermLauncher =
            registerForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
            ) { granted ->
                val p = findPreference<Preference>("perm_notifications") ?: return@registerForActivityResult
                p.summary = if (granted) getString(R.string.perm_already_granted)
                            else getString(R.string.perm_denied)
            }
        private val mediaPermLauncher =
            registerForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
            ) { granted ->
                val p = findPreference<Preference>("perm_photos") ?: return@registerForActivityResult
                p.summary = if (granted) getString(R.string.perm_already_granted)
                            else getString(R.string.perm_denied)
            }
        private val cameraPermLauncher =
            registerForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
            ) { granted ->
                val p = findPreference<Preference>("perm_camera") ?: return@registerForActivityResult
                p.summary = if (granted) getString(R.string.perm_already_granted)
                            else getString(R.string.perm_denied)
            }

        // Run the same check NotificationWorker would run, but
        // immediately and on a background thread. The result
        // is posted back to the UI and the preference summary
        // is updated. Useful after granting the notification
        // permission for the first time, when the user wants
        // to confirm the channel is alive.
        private fun runNotificationCheckNow() {
            val pref = findPreference<Preference>("check_notifications") ?: return
            pref.summary = getString(R.string.update_checking)
            val ctx = requireContext().applicationContext
            val p = prefs
            AppExecutors.background.execute {
                val items = try {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var got: List<org.qbook.utils.NotificationScraper.Item> = emptyList()
                    org.qbook.utils.NotificationScraper.fetch(ctx) {
                        got = it
                        latch.countDown()
                    }
                    if (latch.await(90, java.util.concurrent.TimeUnit.SECONDS)) got
                    else emptyList()
                } catch (e: Exception) { emptyList() }
                org.qbook.utils.NotificationPresenter.post(ctx, items)
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    pref.summary = getString(R.string.perm_notif_check_done, items.size)
                }
            }
        }

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
                org.qbook.utils.BackgroundSyncManager.lastBlockReason +
                "\n\nWorker (OfflineSync.run):\n" +
                org.qbook.utils.OfflineSync.lastRunBlockReason
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
                getString(R.string.dev_email) + "\n" +
                getString(R.string.dev_website)
            AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.dev_name) + " — " + getString(R.string.dev_title))
                .setMessage(msg)
                .setPositiveButton(R.string.dev_acknowledge, null)
                .show()
        }

        /** Enable one launcher alias and disable the other 15. */
        private fun applyAppIcon(index: Int) {
            val ctx = requireContext().applicationContext
            val pm = ctx.packageManager
            val pkg = ctx.packageName

            for (i in 0..15) {
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
            13 -> R.mipmap.ic_launcher_alt13; 14 -> R.mipmap.ic_launcher_alt14
            15 -> R.mipmap.ic_launcher_alt15
            else -> R.mipmap.ic_launcher
        }

        private fun quotaSummary(entries: Array<CharSequence>, value: String?, summaryRes: Int): String {
            val index = entries.indexOfFirst { it.toString() == value || it.toString().substringBefore(" ") == value }
            val label = if (index >= 0) entries[index].toString() else value.orEmpty()
            return getString(summaryRes, label)
        }

        private fun themeSummary(value: String?): String {
            val label = when (value) {
                Prefs.DARK_LIGHT -> "Light"
                Prefs.DARK_DARK -> "Dark"
                else -> "System Synchronized"
            }
            return getString(R.string.pref_dark_sum, label)
        }

        private fun iconSummary(index: Int): String {
            val labels = resources.getStringArray(R.array.icon_entries)
            return getString(R.string.pref_icon_sum) + " — " + findLabelFor(index, labels)
        }

        private fun showIconPickerDialog(currentIdx: Int) {
            val ctx = requireContext()
            val values = resources.getStringArray(R.array.icon_values)
            val d = resources.displayMetrics.density

            // The existing two-column picker remains unchanged; all 16 icons
            // are reachable by scrolling the grid. Selection is
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
                if (i !in 0..15) continue

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

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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
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
import org.qbook.utils.FontManager
import org.qbook.utils.NativeTypography
import org.qbook.utils.PredefinedFonts
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

    private val customFontPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val name = FontManager.importCustomFont(this, uri)
            Toast.makeText(this, getString(R.string.font_import_success_fmt, name), Toast.LENGTH_SHORT).show()
            (supportFragmentManager.findFragmentById(R.id.settings_container) as? ControlCenterFragment)?.let {
                it.refreshFontPreferences()
                it.applyTypographyNow()
            }
            MainViewModel.pendingSettingsChange = true
        } catch (_: Exception) {
            Toast.makeText(this, R.string.font_import_failed, Toast.LENGTH_LONG).show()
        }
    }

    fun openCustomFontPicker() {
        customFontPicker.launch(arrayOf("font/*", "application/octet-stream"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Prefs(this).amoled) theme.applyStyle(R.style.ThemeOverlay_Amoled, true)
        super.onCreate(savedInstanceState)
        ScreenMotion.enter(this)
        setContentView(R.layout.activity_settings)
        if (Prefs(this).labsAnimatedTheme) {
            findViewById<android.view.View>(R.id.settings_root).apply {
                alpha = 0f
                animate().alpha(1f).setDuration(240L).start()
            }
        }
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
                            val fragment = if (isLabsScreen) LabsFragment() else ControlCenterFragment()
                ScreenMotion.configureSharedAxis(fragment)
                supportFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .replace(R.id.settings_container, fragment)
                    .commit()

        }
        findViewById<android.widget.TextView>(R.id.settings_title)
            .text = getString(if (isLabsScreen) R.string.cat_labs else R.string.hidden_settings)
        NativeTypography.applyActivity(this)
    }

    override fun onResume() {
        super.onResume()
        NativeTypography.applyActivity(this)
    }

    override fun onSupportNavigateUp(): Boolean {
        ScreenMotion.exit(this)
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
        private var typographyRecycler: RecyclerView? = null
        private var typographyAttachListener: RecyclerView.OnChildAttachStateChangeListener? = null

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
            if (preference is ListPreference) {
                val entries = preference.entries?.map { it.toString() } ?: return
                val values = preference.entryValues ?: return
                val selected = preference.findIndexOfValue(preference.value).coerceAtLeast(0)
                val subtitle = when (preference.key) {
                    Prefs.KEY_DARK_MODE -> "Choose how QBooK renders its surfaces and contrast."
                    Prefs.KEY_FONT_FAMILY -> "Choose the typeface used across QBooK’s native surfaces."
                    Prefs.KEY_FONT_SCALE -> "Adjust reading density across the complete interface."
                    "offline_reel_count" -> "Set the maximum number of reels kept in the offline reserve."
                    "offline_post_count" -> "Set the maximum number of posts kept in the offline reserve."
                    "offline_network" -> "Choose which connection types may fill the offline reserve."
                    else -> "Choose one option to continue."
                }
                TypographySelectionDialog.show(
                    context = requireContext(),
                    title = (preference.dialogTitle ?: preference.title)?.toString() ?: "Choose an option",
                    subtitle = subtitle,
                    entries = entries,
                    selectedIndex = selected
                ) { which ->
                    val value = values[which].toString()
                    if (preference.callChangeListener(value)) {
                        preference.value = value
                    }
                }
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
                itemAnimator = DefaultItemAnimator().apply {
                    addDuration = 240L
                    removeDuration = 180L
                    moveDuration = 260L
                    changeDuration = 180L
                }
                post {
                    if (itemDecorationCount == 0) {
                        addItemDecoration(SettingsSectionCardDecoration(requireContext(), SECTION_KEYS.toSet()))
                    }
                }
            }
            installTypographyRowObserver()
            if (::prefs.isInitialized) {
                applyTypographyNow()
                listView?.post { applyTypographyNow() }
            }
        }

        private fun installTypographyRowObserver() {
            val recycler = listView ?: return
            typographyRecycler?.let { previous ->
                typographyAttachListener?.let(previous::removeOnChildAttachStateChangeListener)
            }
            val listener = object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: android.view.View) {
                    if (::prefs.isInitialized) {
                        NativeTypography.apply(view, requireContext(), prefs.fontFamily, prefs.fontScale)
                    }
                }

                override fun onChildViewDetachedFromWindow(view: android.view.View) = Unit
            }
            typographyRecycler = recycler
            typographyAttachListener = listener
            recycler.addOnChildAttachStateChangeListener(listener)
            recycler.post {
                for (index in 0 until recycler.childCount) {
                    NativeTypography.apply(
                        recycler.getChildAt(index),
                        requireContext(),
                        prefs.fontFamily,
                        prefs.fontScale
                    )
                }
            }
        }

        override fun onDestroyView() {
            typographyRecycler?.let { recycler ->
                typographyAttachListener?.let(recycler::removeOnChildAttachStateChangeListener)
            }
            typographyAttachListener = null
            typographyRecycler = null
            super.onDestroyView()
        }

        override fun onCreateAdapter(screen: PreferenceScreen): androidx.recyclerview.widget.RecyclerView.Adapter<PreferenceViewHolder> {
            return object : PreferenceGroupAdapter(screen) {
                override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
                    super.onBindViewHolder(holder, position)
                    if (::prefs.isInitialized && isAdded) {
                        NativeTypography.apply(
                            holder.itemView,
                            requireContext(),
                            prefs.fontFamily,
                            prefs.fontScale
                        )
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
            refreshAnimatedThemeIcons()
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

        private fun refreshAnimatedThemeIcons() {
            val recycler = listView ?: return
            val enabled = prefs.labsAnimatedTheme
            for (index in 0 until recycler.childCount) {
                val icon = recycler.getChildAt(index)
                    .findViewById<android.view.View>(android.R.id.icon)
                    ?: continue
                val key = icon.getTag(R.id.animated_theme_key_tag) as? String
                    ?: continue
                AnimatedThemeIconAnimator.bind(icon, key, enabled)
            }
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
                Prefs.KEY_ZOOM, Prefs.KEY_AUTOPLAY_VIDEO, Prefs.KEY_INAPP_MESSAGING,
                Prefs.KEY_STICKY_NAVBAR, Prefs.KEY_IMMERSIVE_MODE,
                Prefs.KEY_INPAGE_SETTINGS, Prefs.KEY_SELECTABLE_CAPTIONS,
                Prefs.KEY_COPY_MEDIA_CLIPBOARD
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

            // ---- typography ----
            findPreference<ListPreference>(Prefs.KEY_FONT_SCALE)
                ?.setOnPreferenceChangeListener { _, newValue ->
                    prefs.fontScale = (newValue as String).toIntOrNull() ?: 100
                    applyTypographyNow()
                    markDirty(false)
                    true
                }

            val fontPreference = findPreference<ListPreference>(Prefs.KEY_FONT_FAMILY)
            fontPreference?.setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                if (selected == FontManager.CUSTOM_VALUE && !FontManager.hasCustomFont(requireContext())) {
                    toast(getString(R.string.font_custom_missing))
                    false
                } else {
                    prefs.fontFamily = selected
                    updateFontPreferenceSummary(fontPreference)
                    applyTypographyNow()
                    markDirty(false)
                    true
                }
            }
            findPreference<Preference>("custom_font")?.setOnPreferenceClickListener {
                (activity as? SettingsActivity)?.openCustomFontPicker()
                true
            }
            findPreference<Preference>("font_preview")?.setOnPreferenceClickListener {
                TypographyPreviewDialog.show(
                    requireContext(), prefs.fontFamily, prefs.fontScale
                ) { family, scale ->
                    prefs.fontFamily = family
                    prefs.fontScale = scale
                    refreshFontPreferences()
                    applyTypographyNow()
                    markDirty(false)
                }
                true
            }
            findPreference<Preference>("reset_typography")?.setOnPreferenceClickListener {
                prefs.fontFamily = FontManager.SYSTEM_VALUE
                prefs.fontScale = 100
                refreshFontPreferences()
                applyTypographyNow()
                markDirty(false)
                toast(getString(R.string.pref_reset_typography))
                true
            }
            findPreference<Preference>("remove_custom_font")?.setOnPreferenceClickListener {
                confirm(
                    R.string.pref_remove_custom_font,
                    R.string.pref_remove_custom_font_sum,
                    R.string.dialog_dismiss,
                    R.string.pref_remove_custom_font
                ) {
                    FontManager.clearCustomFont(requireContext())
                    refreshFontPreferences()
                    markDirty(false)
                    toast(getString(R.string.font_removed))
                }
                true
            }
            refreshFontPreferences()

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
        fun applyTypographyNow() {
            if (!isAdded) return
            NativeTypography.apply(
                requireActivity().window.decorView,
                requireActivity(),
                prefs.fontFamily,
                prefs.fontScale
            )
            MainActivity.live?.applyTypographyImmediately()
        }

        fun refreshFontPreferences() {
            if (!isAdded) return
            val context = requireContext()
            val fontPreference = findPreference<ListPreference>(Prefs.KEY_FONT_FAMILY) ?: return
            val customAvailable = FontManager.hasCustomFont(context)
            if (prefs.fontFamily == FontManager.CUSTOM_VALUE && !customAvailable) {
                prefs.fontFamily = FontManager.SYSTEM_VALUE
            }
            val entries = mutableListOf(getString(R.string.font_system))
            val values = mutableListOf(FontManager.SYSTEM_VALUE)
            PredefinedFonts.all.forEach {
                entries += it.name
                values += it.asset
            }
            if (customAvailable) {
                entries += getString(R.string.font_custom_fmt, prefs.customFontName.ifBlank { "Custom font" })
                values += FontManager.CUSTOM_VALUE
            }
            fontPreference.entries = entries.toTypedArray()
            fontPreference.entryValues = values.toTypedArray()
            fontPreference.value = prefs.fontFamily
            updateFontPreferenceSummary(fontPreference)
            findPreference<Preference>("remove_custom_font")?.isVisible = customAvailable
            findPreference<ListPreference>(Prefs.KEY_FONT_SCALE)?.value = prefs.fontScale.toString()
            findPreference<Preference>("custom_font")?.summary = if (customAvailable) {
                getString(R.string.font_custom_fmt, prefs.customFontName.ifBlank { "Custom font" })
            } else {
                getString(R.string.pref_custom_font_sum)
            }
        }

        private fun updateFontPreferenceSummary(preference: ListPreference) {
            preference.summary = when {
                prefs.fontFamily == FontManager.CUSTOM_VALUE && FontManager.hasCustomFont(requireContext()) ->
                    getString(R.string.font_custom_fmt, prefs.customFontName.ifBlank { "Custom font" })
                prefs.fontFamily == FontManager.SYSTEM_VALUE -> getString(R.string.font_system)
                else -> PredefinedFonts.all.firstOrNull { it.asset == prefs.fontFamily }?.name
                    ?: getString(R.string.font_system)
            }
        }

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
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(titleRes)
                .setMessage(msgRes)
                .setPositiveButton(positiveRes) { _, _ -> action() }
                .setNegativeButton(negativeRes, null)
                .create()
            dialog.show()
            NativeTypography.applyDialog(dialog, requireContext())
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
            val dialog = AlertDialog.Builder(ctx)
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
                .create()
            dialog.show()
            NativeTypography.applyDialog(dialog, ctx)
        }
        private fun showDeveloperDialog() {

            ArchitectCredentialsDialog.show(requireContext())
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
            val labels = resources.getStringArray(R.array.icon_entries)
            val d = resources.displayMetrics.density
            fun dp(value: Int) = (value * d + 0.5f).toInt()

            val content = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(8))
                setBackgroundResource(R.drawable.bg_launcher_picker)
            }
            val title = android.widget.TextView(ctx).apply {
                text = getString(R.string.pref_icon)
                setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.settings_title_foreground))
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            content.addView(title, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(32)
            ))
            val subtitle = android.widget.TextView(ctx).apply {
                text = getString(R.string.launcher_icon_picker_subtitle)
                setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.settings_body_foreground))
                textSize = 13f
                setPadding(0, dp(2), 0, dp(10))
            }
            content.addView(subtitle)

            val grid = android.widget.GridLayout(ctx).apply {
                columnCount = 4
                alignmentMode = android.widget.GridLayout.ALIGN_MARGINS
                useDefaultMargins = false
                setPadding(0, dp(4), 0, dp(4))
            }
            val scroll = android.widget.ScrollView(ctx).apply {
                isFillViewport = true
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                addView(grid)
            }
            content.addView(scroll, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(430)
            ))

            var pendingIdx = currentIdx
            val cells = HashMap<Int, android.view.View>()
            val icons = HashMap<Int, android.widget.ImageView>()
            val labelViews = HashMap<Int, android.widget.TextView>()

            fun selectPending(index: Int) {
                pendingIdx = index
                cells.forEach { (key, cell) ->
                    cell.setBackgroundResource(if (key == index) R.drawable.bg_icon_selected_ring else R.drawable.bg_icon_cell)
                    cell.alpha = if (key == index) 1f else 0.84f
                }
                icons.forEach { (key, icon) ->
                    val scale = if (key == index) 1.08f else 1f
                    icon.animate().scaleX(scale).scaleY(scale).setDuration(140L).start()
                }
                labelViews.forEach { (key, label) ->
                    label.setTextColor(
                        androidx.core.content.ContextCompat.getColor(
                            ctx,
                            if (key == index) R.color.primary else R.color.settings_body_foreground
                        )
                    )
                }
            }

            for (valueIndex in values.indices) {
                val index = values[valueIndex].toIntOrNull() ?: continue
                if (index !in 0..15) continue
                val cell = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                    setBackgroundResource(if (index == currentIdx) R.drawable.bg_icon_selected_ring else R.drawable.bg_icon_cell)
                    alpha = if (index == currentIdx) 1f else 0.84f
                }
                val cellParams = android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                ).apply {
                    width = 0
                    height = dp(92)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                cell.layoutParams = cellParams
                val icon = android.widget.ImageView(ctx).apply {
                    setImageResource(iconRes(index))
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(54), dp(54))
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    if (index == currentIdx) { scaleX = 1.08f; scaleY = 1.08f }
                    contentDescription = labels.getOrNull(index) ?: getString(R.string.pref_icon)
                }
                val name = android.widget.TextView(ctx).apply {
                    text = labels.getOrNull(index) ?: "Icon ${index + 1}"
                    gravity = android.view.Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    textSize = 11f
                    setTextColor(
                        androidx.core.content.ContextCompat.getColor(
                            ctx,
                            if (index == currentIdx) R.color.primary else R.color.settings_body_foreground
                        )
                    )
                }
                cells[index] = cell
                icons[index] = icon
                labelViews[index] = name
                cell.addView(icon)
                cell.addView(name, android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(20)
                ))
                cell.setOnClickListener { selectPending(index) }
                grid.addView(cell)
            }

            val dialog = AlertDialog.Builder(ctx)
                .setView(content)
                .setPositiveButton(android.R.string.ok) { _, _ -> applyAppIcon(pendingIdx) }
                .setNegativeButton(android.R.string.cancel, null)
                .create()

            dialog.setOnShowListener {
                val width = minOf(resources.displayMetrics.widthPixels - dp(24), dp(420))
                dialog.window?.apply {
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    attributes = attributes.apply { dimAmount = 0.68f }
                    setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                    setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.on_primary))
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(ctx, R.color.primary)
                    )
                    minWidth = dp(88)
                }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                    setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.primary))
                    backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                    minWidth = dp(88)
                }
            }
            dialog.show()
            NativeTypography.applyDialog(dialog, ctx)
        }
    }
}

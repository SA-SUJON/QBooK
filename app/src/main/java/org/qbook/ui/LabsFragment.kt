package org.qbook.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import org.qbook.R
import org.qbook.utils.AdBlocker
import org.qbook.utils.DiagnosticExport
import org.qbook.utils.Diag
import org.qbook.utils.DiagCapture
import org.qbook.utils.Prefs
import org.qbook.viewmodel.MainViewModel

/** Dedicated QBooK Labs screen. Feature behavior is intentionally unchanged. */
class LabsFragment : PreferenceFragmentCompat() {

    private lateinit var prefs: Prefs

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_labs, rootKey)
        prefs = Prefs(requireContext())
        wireLabsFeatures()
        wireAdvancedDebugMatrix()
        updateAdvancedDebugVisibility()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setDivider(null)
        setDividerHeight(0)
        val density = resources.displayMetrics.density
        listView?.apply {
            setPadding(
                (16 * density).toInt(),
                (84 * density).toInt(),
                (16 * density).toInt(),
                (28 * density).toInt()
            )
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) updateAdvancedDebugVisibility()
    }

    private fun wireLabsFeatures() {
        findPreference<Preference>("labs_download_center_open")
            ?.setOnPreferenceClickListener {
                if (prefs.labsDownloadCenter && prefs.labsGallery) {
                    startActivity(Intent(requireContext(), DownloadCenterActivity::class.java))
                } else {
                    toast(getString(R.string.labs_download_center_toggle_sum))
                }
                true
            }

        findPreference<SwitchPreferenceCompat>(Prefs.KEY_LABS_REEL_OPTIONS)
            ?.setOnPreferenceChangeListener { _, _ -> markDirty(true); true }
        findPreference<SwitchPreferenceCompat>(Prefs.KEY_LABS_EXTENDED_MATERIAL)
            ?.setOnPreferenceChangeListener { _, _ -> markDirty(true); true }
        findPreference<SwitchPreferenceCompat>(Prefs.KEY_LABS_ANIMATED_THEME)
            ?.setOnPreferenceChangeListener { _, _ -> markDirty(false); true }
        findPreference<SwitchPreferenceCompat>(Prefs.KEY_LABS_LIQUID_GLASS)
            ?.setOnPreferenceChangeListener { _, _ -> activity?.recreate(); true }
    }

    private fun wireAdvancedDebugMatrix() {
        findPreference<SwitchPreferenceCompat>(Prefs.KEY_DIAGNOSTIC_ALL)
            ?.setOnPreferenceChangeListener { _, value ->
                prefs.setDiagAllEnabled(value as Boolean)
                true
            }

        findPreference<Preference>("diag_mark")?.setOnPreferenceClickListener {
            DiagCapture.mark(requireContext(), "USER_MARK labs_button")
            toast("Diagnostic marker added")
            true
        }

        val channelPrefs = listOf(
            Diag.Channel.HOME_FEED to Prefs.KEY_DIAGNOSTIC_HOME_FEED,
            Diag.Channel.REELS to Prefs.KEY_DIAGNOSTIC_REELS,
            Diag.Channel.STORY to Prefs.KEY_DIAGNOSTIC_STORY,
            Diag.Channel.ADS to Prefs.KEY_DIAGNOSTIC_ADS,
            Diag.Channel.NETWORK to Prefs.KEY_DIAGNOSTIC_NETWORK,
            Diag.Channel.OFFLINE_SAVE to Prefs.KEY_DIAGNOSTIC_OFFLINE_SAVE,
            Diag.Channel.APP_LIFECYCLE to Prefs.KEY_DIAGNOSTIC_LIFECYCLE
        )
        channelPrefs.forEach { (channel, key) ->
            findPreference<SwitchPreferenceCompat>(key)
                ?.setOnPreferenceChangeListener { _, value ->
                    prefs.setDiagChannelEnabled(channel, value as Boolean)
                    true
                }
        }

        findPreference<Preference>("view_diagnostic_log")?.setOnPreferenceClickListener {
            DiagnosticExport.purge(requireContext())
            startActivity(Intent(requireContext(), DiagnosticLogActivity::class.java))
            true
        }

        findPreference<Preference>("turn_off_advanced_debug")?.setOnPreferenceClickListener {
            prefs.developerEnabled = false
            updateAdvancedDebugVisibility()
            toast(getString(R.string.dev_disabled_toast))
            true
        }
    }

    private fun updateAdvancedDebugVisibility() {
        val enabled = prefs.developerEnabled
        findPreference<Preference>("advanced_debug_divider")?.isVisible = enabled
        findPreference<PreferenceCategory>("advanced_debug_options")?.isVisible = enabled
    }

    private fun markDirty(reload: Boolean) {
        MainViewModel.pendingSettingsChange = true
        if (reload) MainViewModel.pendingReload = true
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}

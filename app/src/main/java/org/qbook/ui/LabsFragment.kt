package org.qbook.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.qbook.R
import org.qbook.utils.AdBlocker
import org.qbook.utils.DiagnosticExport
import org.qbook.utils.Diag
import org.qbook.utils.DiagCapture
import org.qbook.utils.Prefs
import org.qbook.utils.ProfileStore
import org.qbook.utils.NativeTypography
import org.qbook.utils.UpdateWatcher
import org.qbook.utils.PrivacySecurityManager
import org.qbook.viewmodel.MainViewModel

/** Dedicated QBooK Labs screen. Feature behavior is intentionally unchanged. */
class LabsFragment : PreferenceFragmentCompat() {

    private lateinit var prefs: Prefs
    private var disclaimerShownForVisit = false
    private var disclaimerDialog: Dialog? = null
    private var disclaimerTimer: CountDownTimer? = null

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
        NativeTypography.applyActivity(requireActivity())
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) {
            updateAdvancedDebugVisibility()
            updateDefaultProfileSummary()
            NativeTypography.applyActivity(requireActivity())
            if (!disclaimerShownForVisit) {
                disclaimerShownForVisit = true
                view?.post { showLabsDisclaimerIfEnabled() }
            }
        }
    }

    override fun onDestroyView() {
        disclaimerTimer?.cancel()
        disclaimerTimer = null
        disclaimerDialog?.dismiss()
        disclaimerDialog = null
        super.onDestroyView()
    }

    private fun showLabsDisclaimerIfEnabled() {
        val host = activity ?: return
        if (!prefs.labsShowDisclaimer || host.isFinishing || host.isDestroyed) return

        val content = layoutInflater.inflate(R.layout.dialog_labs_disclaimer, null)
        val progress = content.findViewById<LinearProgressIndicator>(R.id.labs_disclaimer_progress)
        val timerLabel = content.findViewById<android.widget.TextView>(R.id.labs_disclaimer_timer)
        val abort = content.findViewById<MaterialButton>(R.id.labs_disclaimer_abort)
        val accept = content.findViewById<MaterialButton>(R.id.labs_disclaimer_accept)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(content)
            .setCancelable(false)
            .create()
        disclaimerDialog = dialog

        abort.setOnClickListener {
            disclaimerTimer?.cancel()
            dialog.dismiss()
            host.finish()
        }
        accept.setOnClickListener {
            if (accept.isEnabled) {
                disclaimerTimer?.cancel()
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            disclaimerTimer?.cancel()
            disclaimerTimer = null
            disclaimerDialog = null
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.72f)
                decorView.setPadding(0, 0, 0, 0)
                setLayout(
                    (resources.displayMetrics.widthPixels - (32 * resources.displayMetrics.density)).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
            }
        }
        dialog.show()
        NativeTypography.applyDialog(dialog, requireContext())

        val duration = 10_000L
        disclaimerTimer = object : CountDownTimer(duration, 250L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = ((millisUntilFinished + 999L) / 1000L).toInt()
                timerLabel.text = getString(R.string.labs_disclaimer_waiting, seconds)
                progress.setProgressCompat(
                    ((duration - millisUntilFinished) * 100L / duration).toInt(),
                    false
                )
            }

            override fun onFinish() {
                progress.setProgressCompat(100, false)
                timerLabel.setText(R.string.labs_disclaimer_ready)
                accept.isEnabled = true
            }
        }.start()
    }

    private fun wireLabsFeatures() {
        findPreference<Preference>("labs_show_update_dialog")?.setOnPreferenceClickListener {
            UpdateWatcher.showAgain()
            toast(getString(R.string.update_checking))
            true
        }
        findPreference<Preference>("labs_download_center_open")
            ?.setOnPreferenceClickListener {
                if (prefs.labsDownloadCenter) {
                    startActivity(Intent(requireContext(), DownloadCenterActivity::class.java))
                } else {
                    toast(getString(R.string.labs_download_center_toggle_sum))
                }
                true
            }
        findPreference<Preference>("labs_gallery_open")?.apply {
            isEnabled = prefs.labsGallery
            setOnPreferenceClickListener {
                if (prefs.labsGallery) {
                    startActivity(Intent(requireContext(), MediaGalleryActivity::class.java))
                } else {
                    toast(getString(R.string.labs_gallery_sum))
                }
                true
            }
        }
        findPreference<SwitchPreferenceCompat>(Prefs.KEY_LABS_GALLERY)
            ?.setOnPreferenceChangeListener { _, newValue ->
                view?.post {
                    findPreference<Preference>("labs_gallery_open")?.isEnabled = newValue as Boolean
                }
                true
            }

        findPreference<SwitchPreferenceCompat>(Prefs.KEY_LABS_REEL_OPTIONS)
            ?.setOnPreferenceChangeListener { _, _ -> markDirty(true); true }
        findPreference<SwitchPreferenceCompat>(Prefs.KEY_LABS_SAVE_PATHS)
            ?.setOnPreferenceChangeListener { _, _ -> markDirty(true); true }
        findPreference<SwitchPreferenceCompat>(Prefs.KEY_LABS_AUDIO_EXTRACTION)
            ?.setOnPreferenceChangeListener { _, _ -> markDirty(true); true }
        findPreference<SwitchPreferenceCompat>(Prefs.KEY_LABS_BATCH_SAVE)
            ?.setOnPreferenceChangeListener { _, _ -> markDirty(true); true }
        findPreference<SwitchPreferenceCompat>(Prefs.KEY_LABS_LIQUID_GLASS)
            ?.setOnPreferenceChangeListener { _, _ -> activity?.recreate(); true }
        val animatedThemeInfo = findPreference<Preference>("labs_animated_theme_info")
        findPreference<SwitchPreferenceCompat>(Prefs.KEY_LABS_ANIMATED_THEME)?.apply {
            animatedThemeInfo?.summary = getString(
                if (isChecked) R.string.labs_animated_theme_info_enabled
                else R.string.labs_animated_theme_info_disabled
            )
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                animatedThemeInfo?.summary = getString(
                    if (enabled) R.string.labs_animated_theme_info_enabled
                    else R.string.labs_animated_theme_info_disabled
                )
                activity?.recreate()
                true
            }
        }
        findPreference<SwitchPreferenceCompat>(Prefs.KEY_LABS_EXTENDED_MATERIAL)
            ?.setOnPreferenceChangeListener { _, _ -> markDirty(true); true }
        findPreference<SwitchPreferenceCompat>(Prefs.KEY_LABS_MATERIALBOOK_DESKTOP_CLEANUP)
            ?.setOnPreferenceChangeListener { _, _ -> markDirty(true); true }
        findPreference<SwitchPreferenceCompat>(Prefs.KEY_LABS_MATERIALBOOK_TRANSPARENT_PROGRESS)
            ?.setOnPreferenceChangeListener { _, _ -> markDirty(true); true }
        findPreference<SwitchPreferenceCompat>(Prefs.KEY_LABS_MATERIALBOOK_GREY_TAP)
            ?.setOnPreferenceChangeListener { _, _ -> markDirty(true); true }
        findPreference<SwitchPreferenceCompat>(Prefs.KEY_LABS_APPEAR_OFFLINE)
            ?.setOnPreferenceChangeListener { _, _ -> markDirty(true); true }
        findPreference<SwitchPreferenceCompat>(Prefs.KEY_LABS_STRIP_TRACKING)
            ?.setOnPreferenceChangeListener { _, _ -> markDirty(true); true }
        findPreference<SwitchPreferenceCompat>(Prefs.KEY_LABS_FLAG_SECURE)
            ?.setOnPreferenceChangeListener { _, value ->
                val enabled = value as Boolean
                PrivacySecurityManager.onPreferenceChanged(requireActivity(), enabled)
                true
            }
        findPreference<SwitchPreferenceCompat>(Prefs.KEY_LABS_APP_LOCK)
            ?.setOnPreferenceChangeListener { _, value ->
                val enabled = value as Boolean
                if (enabled && !PrivacySecurityManager.canAuthenticate(requireActivity())) {
                    toast(getString(R.string.app_lock_unavailable))
                    false
                } else {
                    PrivacySecurityManager.onPreferenceChanged(requireActivity(), enabled)
                    true
                }
            }
        findPreference<Preference>("accounts_default_profile")
            ?.setOnPreferenceClickListener {
                startActivity(
                    Intent(requireContext(), AccountsActivity::class.java)
                        .putExtra(AccountsActivity.EXTRA_MANAGE_ONLY, true)
                )
                true
            }
        updateDefaultProfileSummary()
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

    private fun updateDefaultProfileSummary() {
        findPreference<Preference>("accounts_default_profile")?.summary =
            getString(
                R.string.accounts_default_profile_summary,
                ProfileStore.load(requireContext()).defaultProfile.name
            )
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

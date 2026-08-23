package org.qbook.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.qbook.R
import org.qbook.utils.NativeTypography
import org.qbook.utils.Prefs
import org.qbook.utils.ProfileStore

/**
 * Profile switcher and session-management surface. Cookies remain inside the
 * app's private storage and are never exposed in the UI.
 */
class AccountsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TARGET_URL = "accounts_target_url"
        const val EXTRA_MANAGE_ONLY = "accounts_manage_only"
        const val EXTRA_INCOGNITO = "accounts_incognito"
    }

    private lateinit var prefs: Prefs
    private lateinit var profileList: LinearLayout
    private var state: ProfileStore.State? = null
    private val manageOnly: Boolean get() = intent.getBooleanExtra(EXTRA_MANAGE_ONLY, false)

    private val importCookiesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importCookies(it) } }

    private val restoreBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { restoreBackup(it) } }

    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { exportBackup(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startupPrefs = Prefs(this)
        AppCompatDelegate.setDefaultNightMode(startupPrefs.nightMode())
        if (startupPrefs.amoled) theme.applyStyle(R.style.ThemeOverlay_Amoled, true)
        super.onCreate(savedInstanceState)
        ScreenMotion.enter(this)
        prefs = startupPrefs
        WindowCompatHelper.prepare(window)
        setContentView(R.layout.activity_accounts_sessions)
        val accountsScroll = findViewById<android.widget.ScrollView>(R.id.accounts_scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(accountsScroll) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(accountsScroll)
        if (prefs.labsAnimatedTheme) {
            findViewById<android.view.View>(R.id.accounts_root).apply {
                alpha = 0f
                animate().alpha(1f).setDuration(240L).start()
            }
        }

        profileList = findViewById(R.id.accounts_profile_list)
        state = ProfileStore.load(this)
        bindActions()
        renderProfiles()
        NativeTypography.applyActivity(this)
    }

    override fun onResume() {
        super.onResume()
        if (::profileList.isInitialized) {
            state = ProfileStore.load(this)
            renderProfiles()
            NativeTypography.applyActivity(this)
        }
    }

    override fun onBackPressed() {
        if (manageOnly) finish() else openDefaultProfile()
    }

    private fun bindActions() {
        findViewById<ImageButton>(R.id.accounts_close).setOnClickListener {
            if (manageOnly) finish() else openDefaultProfile()
        }
        findViewById<MaterialButton>(R.id.accounts_add_profile).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.ic_plus)
            iconTint = ColorStateList.valueOf(primaryColor())
            setOnClickListener { showCreateProfileDialog() }
        }
        findViewById<MaterialButton>(R.id.accounts_import_cookies).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.ic_lock)
            iconTint = ColorStateList.valueOf(primaryColor())
            setOnClickListener {
                importCookiesLauncher.launch(arrayOf("text/*", "application/json", "application/octet-stream"))
            }
        }
        findViewById<MaterialButton>(R.id.accounts_backup).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.ic_lock)
            iconTint = ColorStateList.valueOf(primaryColor())
            setOnClickListener { backupLauncher.launch("qbook-sessions.json") }
        }
        findViewById<MaterialButton>(R.id.accounts_restore).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.ic_lock)
            iconTint = ColorStateList.valueOf(primaryColor())
            setOnClickListener {
                restoreBackupLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
            }
        }
        findViewById<MaterialButton>(R.id.accounts_incognito_open).setOnClickListener {
            openIncognito()
        }
    }

    private fun renderProfiles() {
        val current = state ?: ProfileStore.load(this).also { state = it }
        profileList.removeAllViews()
        current.profiles.forEach { profile -> addProfileCard(profile, current) }
    }

    private fun addProfileCard(profile: ProfileStore.Profile, current: ProfileStore.State) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_accounts_glass)
            elevation = dp(3).toFloat()
            setPadding(dp(20), dp(20), dp(20), dp(16))
            contentDescription = profile.name
        }
        profileList.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        card.addView(heading)

        val avatar = TextView(this).apply {
            gravity = Gravity.CENTER
            text = profile.initial
            textSize = 27f
            setTextColor(primaryColor())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(resolveColor(com.google.android.material.R.attr.colorSurface))
                setStroke(dp(1), withAlpha(primaryColor(), 180))
            }
        }
        heading.addView(avatar, LinearLayout.LayoutParams(dp(68), dp(68)))

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(8), 0)
        }
        heading.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        details.addView(TextView(this).apply {
            text = profile.name
            textSize = 22f
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnBackground))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        details.addView(TextView(this).apply {
            text = getString(
                if (profile.isActive) R.string.accounts_session_ready else R.string.accounts_session_empty
            )
            setPadding(0, dp(4), 0, 0)
            textSize = 14f
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        details.addView(TextView(this).apply {
            text = getString(R.string.accounts_profile_kind_summary, profileKindLabel(profile.kind))
            setPadding(0, dp(4), 0, 0)
            textSize = 12f
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        details.addView(TextView(this).apply {
            text = getString(R.string.accounts_profile_id, profile.id)
            setPadding(0, dp(4), 0, 0)
            textSize = 11f
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        if (profile.id == current.activeProfileId) {
            details.addView(TextView(this).apply {
                text = getString(R.string.accounts_selected)
                setPadding(0, dp(4), 0, 0)
                textSize = 11f
                setTextColor(primaryColor())
            })
        }

        if (profile.id == current.defaultProfileId) {
            heading.addView(TextView(this).apply {
                text = getString(R.string.accounts_default)
                textSize = 12f
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                background = ContextCompat.getDrawable(context, R.drawable.bg_accounts_glass)
                setPadding(dp(12), dp(6), dp(12), dp(6))
            })
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, 0)
        }
        card.addView(actions)
        val open = filledButton(R.string.accounts_open).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.ic_person)
            setOnClickListener { openProfile(profile.id) }
        }
        actions.addView(open, LinearLayout.LayoutParams(0, dp(50), 1f))
        val more = outlinedButton(R.string.accounts_more_actions).apply {
            setOnClickListener { showProfileActions(profile) }
        }
        actions.addView(more, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginStart = dp(12)
        })
    }

    private fun showProfileActions(profile: ProfileStore.Profile) {
        val current = state ?: return
        val actions = buildList {
            if (profile.id != current.defaultProfileId) add(getString(R.string.accounts_select_default))
            add(getString(R.string.accounts_rename))
            if (current.profiles.size > 1) add(getString(R.string.accounts_delete))
        }.toTypedArray()
        TypographySelectionDialog.show(
            context = this,
            title = getString(R.string.accounts_more_actions_title),
            subtitle = "Manage this profile without leaving Accounts & Sessions.",
            entries = actions.toList(),
            selectedIndex = -1
        ) { which ->
            val firstAction = if (profile.id != current.defaultProfileId) 0 else -1
            when {
                firstAction == 0 && which == 0 -> selectDefault(profile.id)
                which == if (firstAction == 0) 1 else 0 -> showRenameDialog(profile)
                else -> showDeleteDialog(profile)
            }
        }
    }

    private fun showCreateProfileDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        val input = EditText(this).apply {
            hint = getString(R.string.accounts_profile_name)
            setSingleLine(true)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        form.addView(input, LinearLayout.LayoutParams(-1, dp(56)))
        form.addView(TextView(this).apply {
            text = getString(R.string.accounts_profile_kind)
            textSize = 13f
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(4), dp(12), dp(4), dp(2))
        })
        val kinds = listOf(
            ProfileStore.KIND_PERSONAL to R.string.accounts_profile_kind_personal,
            ProfileStore.KIND_BUSINESS to R.string.accounts_profile_kind_business,
            ProfileStore.KIND_PAGE to R.string.accounts_profile_kind_page
        )
        var selectedKind = ProfileStore.KIND_PERSONAL
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        kinds.forEachIndexed { index, (kind, label) ->
            group.addView(RadioButton(this).apply {
                text = getString(label)
                tag = kind
                isChecked = index == 0
                setPadding(dp(4), dp(4), 0, dp(4))
            })
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            selectedKind = group.findViewById<RadioButton>(checkedId)?.tag as? String ?: selectedKind
        }
        form.addView(group)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.accounts_add_profile)
            .setMessage(R.string.accounts_import_hint)
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.accounts_add_profile) { _, _ ->
                val created = ProfileStore.create(this, input.text.toString(), kind = selectedKind)
                state = created
                renderProfiles()
                openProfile(created.profiles.last().id)
            }
            .show()
    }

    private fun profileKindLabel(kind: String): String = when (kind) {
        ProfileStore.KIND_BUSINESS -> getString(R.string.accounts_profile_kind_business)
        ProfileStore.KIND_PAGE -> getString(R.string.accounts_profile_kind_page)
        else -> getString(R.string.accounts_profile_kind_personal)
    }

    private fun showRenameDialog(profile: ProfileStore.Profile) {
        val input = EditText(this).apply {
            setText(profile.name)
            setSingleLine(true)
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.accounts_rename)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.accounts_rename) { _, _ ->
                state = ProfileStore.rename(this, profile.id, input.text.toString())
                renderProfiles()
                toast(R.string.accounts_profile_renamed)
            }
            .show()
    }

    private fun showDeleteDialog(profile: ProfileStore.Profile) {
        val current = state ?: return
        if (current.profiles.size <= 1) {
            toast(R.string.accounts_delete_last)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.accounts_delete)
            .setMessage(getString(R.string.accounts_delete_confirm, profile.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.accounts_delete) { _, _ ->
                state = ProfileStore.delete(this, profile.id)
                renderProfiles()
                toast(R.string.accounts_profile_deleted)
            }
            .show()
    }

    private fun selectDefault(id: String) {
        state = ProfileStore.setDefault(this, id)
        renderProfiles()
        toast(R.string.accounts_default_updated)
        if (manageOnly) finish()
    }

    private fun openProfile(id: String) {
        val profile = state?.profiles?.firstOrNull { it.id == id } ?: return
        toast(getString(R.string.accounts_session_opened, profile.name))
        ProfileStore.activate(this, id) {
            launchMain()
        }
    }

    private fun openDefaultProfile() {
        val id = state?.defaultProfileId ?: ProfileStore.load(this).defaultProfileId
        ProfileStore.activate(this, id) { launchMain() }
    }

    private fun openIncognito() {
        toast(R.string.accounts_incognito_opened)
        ProfileStore.clearCookies { launchMain(ProfileStoreClear.INCOGNITO) }
    }

    private fun launchMain(mode: Int = ProfileStoreClear.NORMAL) {
        val main = Intent(this, MainActivity::class.java).apply {
            intent.getStringExtra(EXTRA_TARGET_URL)?.let { raw ->
                data = Uri.parse(raw)
                action = Intent.ACTION_VIEW
            }
            if (mode == ProfileStoreClear.INCOGNITO) putExtra(EXTRA_INCOGNITO, true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(main)
        finish()
    }

    private fun importCookies(uri: Uri) {
        val text = runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }
            .getOrNull()
        val cookies = text?.let(ProfileStore::importCookies).orEmpty()
        if (cookies.isBlank()) {
            toast(R.string.accounts_import_failed)
            return
        }
        val imported = ProfileStore.create(this, getString(R.string.accounts_imported_profile), cookies)
        state = imported
        renderProfiles()
        toast(R.string.accounts_import_success)
        openProfile(imported.profiles.last().id)
    }

    private fun exportBackup(uri: Uri) {
        val success = runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(ProfileStore.backupJson(this)) }
        }.isSuccess
        toast(if (success) R.string.accounts_backup_success else R.string.accounts_import_failed)
    }

    private fun restoreBackup(uri: Uri) {
        val json = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        val restored = runCatching { json?.let { ProfileStore.restoreJson(this, it) } }.getOrNull()
        if (restored == null) {
            toast(R.string.accounts_restore_failed)
            return
        }
        state = restored
        renderProfiles()
        toast(R.string.accounts_restore_success)
    }

    private fun filledButton(textRes: Int): MaterialButton = MaterialButton(this).apply {
        text = getString(textRes)
        cornerRadius = dp(28)
        setTextColor(resolveColor(com.google.android.material.R.attr.colorOnPrimary))
        backgroundTintList = ColorStateList.valueOf(primaryColor())
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        iconPadding = dp(8)
    }

    private fun outlinedButton(textRes: Int): MaterialButton = MaterialButton(this).apply {
        text = getString(textRes)
        cornerRadius = dp(28)
        setTextColor(resolveColor(com.google.android.material.R.attr.colorOnBackground))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(resolveColor(R.attr.qbookSettingsGlassStroke))
        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
    }

    private fun primaryColor(): Int = resolveColor(com.google.android.material.R.attr.colorPrimary)

    private fun resolveColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(messageRes: Int) = Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private object ProfileStoreClear {
        const val NORMAL = 0
        const val INCOGNITO = 1
    }

    private object WindowCompatHelper {
        fun prepare(window: Window) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
    }
}

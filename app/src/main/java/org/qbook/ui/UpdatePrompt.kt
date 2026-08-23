package org.qbook.ui

import android.app.Activity
import android.app.DownloadManager
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.qbook.R
import org.qbook.utils.ApkInstaller
import org.qbook.utils.NativeTypography
import org.qbook.utils.Prefs
import org.qbook.utils.UpdateChecker
import org.qbook.utils.UpdateWatcher

/**
 * The mandatory update prompt.
 *
 * This used to live inside MainActivity, which meant a release published while
 * the user was in the hidden settings could not be offered until they went
 * back to the feed. It takes a plain Activity now, so [UpdateWatcher] can show
 * it on whichever screen is in front.
 *
 * There is no skip and no dismiss: the dialog is not cancellable and its only
 * action downloads and installs in place. The APK is fetched by DownloadManager
 * and handed to the system package installer, so the browser is never involved.
 */
object UpdatePrompt {

    /** The dialog currently on screen, so it is never stacked twice. */
    private var showing: AlertDialog? = null

    fun isShowing(): Boolean = showing?.isShowing == true

    fun dismiss() {
        try { showing?.dismiss() } catch (e: Exception) {}
        showing = null
    }

    fun show(activity: Activity, rel: UpdateChecker.Release, local: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        // Already up, possibly on a screen the user just left. Rebuild it on
        // the current Activity rather than leaking a window.
        if (isShowing()) {
            val owner = showing?.context
            if (owner === activity) return
            dismiss()
        }

        val view = activity.layoutInflater.inflate(R.layout.dialog_update, null)
        val title = view.findViewById<TextView>(R.id.updTitle)
        val body = view.findViewById<TextView>(R.id.updBody)
        val bar = view.findViewById<ProgressBar>(R.id.updProgress)
        val status = view.findViewById<TextView>(R.id.updStatus)
        val button = view.findViewById<Button>(R.id.updButton)
        val skip = view.findViewById<Button>(R.id.updSkip)
        val dontShowAgain = view.findViewById<CheckBox>(R.id.updDontShowAgain)

        title.text = activity.getString(R.string.update_available)
        dontShowAgain.isChecked = Prefs(activity).updatePromptsSuppressed
        body.text = activity.getString(R.string.update_msg_fmt, rel.version, local) +
            if (rel.notes.isNotBlank()) "\n\n" + rel.notes.take(300) else ""

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        showing = dialog
        dialog.setOnDismissListener { if (showing === dialog) showing = null }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.68f }
            setLayout(
                minOf(activity.resources.displayMetrics.widthPixels - (24 * activity.resources.displayMetrics.density).toInt(),
                    (420 * activity.resources.displayMetrics.density).toInt()),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        NativeTypography.applyDialog(dialog, activity)

        skip.setOnClickListener {
            UpdateWatcher.skip(rel.version, dontShowAgain.isChecked)
            dialog.dismiss()
        }
        dontShowAgain.setOnCheckedChangeListener { _, checked ->
            if (checked) Prefs(activity).updatePromptsSuppressed = true
        }
        button.setOnClickListener {
            val apk = rel.apkUrl
            if (apk.isNullOrBlank()) {
                status.visibility = View.VISIBLE
                status.text = activity.getString(R.string.update_failed)
                return@setOnClickListener
            }
            if (!ApkInstaller.canInstall(activity)) {
                status.visibility = View.VISIBLE
                status.text = activity.getString(R.string.update_need_permission)
                ApkInstaller.requestInstallPermission(activity)
                return@setOnClickListener
            }
            if (dontShowAgain.isChecked) Prefs(activity).updatePromptsSuppressed = true
            button.isEnabled = false
            skip.isEnabled = false
            bar.visibility = View.VISIBLE
            status.visibility = View.VISIBLE
            status.text = activity.getString(R.string.update_downloading)

            val id = ApkInstaller.startDownload(activity, apk, rel.version)
            if (id <= 0) {
                button.isEnabled = true
                bar.visibility = View.GONE
                // Show why, instead of a bare "failed" with no clue.
                status.text = ApkInstaller.lastError?.let {
                    activity.getString(R.string.update_failed) + "\n" + it
                } ?: activity.getString(R.string.update_failed)
                return@setOnClickListener
            }
            poll(activity, id, rel.version, bar, status, button, skip, view)
        }
    }

    private fun poll(
        activity: Activity,
        id: Long,
        version: String,
        bar: ProgressBar,
        status: TextView,
        button: Button,
        skip: Button,
        host: View
    ) {
        val runnable = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) return
                when (ApkInstaller.status(activity, id)) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        bar.isIndeterminate = false
                        bar.progress = 100
                        status.text = activity.getString(R.string.update_installing)
                        if (ApkInstaller.install(activity, version)) {
                            // Handed to the package installer. Stop offering
                            // the same release on every screen change.
                            UpdateWatcher.clear()
                        } else {
                            status.text = activity.getString(R.string.update_failed)
                            button.isEnabled = true
                            skip.isEnabled = true
                        }
                    }
                    DownloadManager.STATUS_FAILED -> {
                        status.text = activity.getString(R.string.update_failed)
                        button.isEnabled = true
                        skip.isEnabled = true
                        bar.visibility = View.GONE
                    }
                    else -> {
                        val p = ApkInstaller.progress(activity, id)
                        if (p >= 0) {
                            bar.isIndeterminate = false
                            bar.progress = p
                            status.text =
                                activity.getString(R.string.update_progress_fmt, p)
                        }
                        host.postDelayed(this, 600)
                    }
                }
            }
        }
        host.post(runnable)
    }
}

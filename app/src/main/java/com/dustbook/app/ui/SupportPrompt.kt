package com.dustbook.app.ui

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ViewGroup
import android.view.Window
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import com.dustbook.app.utils.Prefs

/**
 * "Buy me a coffee".
 *
 * Rendered as the supplied HTML in a WebView rather than rebuilt out of
 * Android views. The design is the reference: rounded card, the paper and moss
 * palette, the tabbed payment panes. Rebuilding it in XML would have meant
 * approximating every one of those, and the result would have drifted from
 * what was asked for.
 *
 * Nothing here takes a payment. The app shows an address and a Binance Pay ID
 * and gets out of the way - no gateway, no API key, and therefore no secret to
 * leak from a public repo. Paying is entirely optional: the prompt can be
 * dismissed, skipped, or silenced for good.
 */
object SupportPrompt {

    /**
     * Held back until the app has actually been in use for a few days.
     *
     * A donation box on day one is begging rather than asking. Gating on
     * elapsed time since the first launch, rather than a launch count, means
     * someone who opens the app once a day still gets asked on schedule
     * instead of needing three separate cold starts first.
     */
    private const val DAYS_BEFORE_ASKING = 3L

    /**
     * At most once a day.
     *
     * Not a fortnight. The checkbox is the real control here - anyone who
     * does not want it says so once and is never asked again - but without
     * some limit the prompt would appear on every single cold start, which
     * would make the app feel like adware.
     */
    private const val DAYS_BETWEEN_ASKS = 1L

    private const val PAGE = "file:///android_asset/support.html"

    /**
     * Show it because the user asked, from About > Support the developer.
     *
     * Always appears, even when "Don't show again" was ticked: that box
     * silences the automatic prompt, not the menu entry. A user who has just
     * tapped "Support the developer" plainly wants to see it.
     */
    fun showNow(activity: Activity) {
        present(activity, Prefs(activity))
    }

    /**
     * Show it on its own, if the moment is right.
     *
     * Returns true when the dialog was shown. Silent by default - a donation
     * prompt on first launch is begging, so it waits until the app has been
     * opened enough times to have been useful.
     */
    fun maybeShow(activity: Activity): Boolean {
        val prefs = Prefs(activity)
        if (prefs.supportHidden) return false

        val now = System.currentTimeMillis()
        val dayMs = 24 * 60 * 60 * 1000L
        val sinceFirstLaunch = now - prefs.firstLaunchAt
        if (prefs.firstLaunchAt == 0L || sinceFirstLaunch < DAYS_BEFORE_ASKING * dayMs) return false

        val gap = DAYS_BETWEEN_ASKS * dayMs
        if (now - prefs.supportLastShown < gap) return false

        prefs.supportLastShown = now
        present(activity, prefs)
        return true
    }

    private fun present(activity: Activity, prefs: Prefs) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val web = WebView(activity)
        web.settings.javaScriptEnabled = true
        // The page is ours, in assets, and loads nothing from the network.
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.setBackgroundColor(Color.TRANSPARENT)
        web.isVerticalScrollBarEnabled = false
        web.overScrollMode = WebView.OVER_SCROLL_NEVER

        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun copy(text: String) {
                activity.runOnUiThread {
                    val cm = activity.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("Dustbook", text))
                    // Android 13 and up shows its own copy confirmation, so a
                    // toast there would be a second one saying the same thing.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            /**
             * The user pressed Donate.
             *
             * This is not a receipt. The app cannot take a payment and cannot
             * verify one - it shows an address and nothing more - so nothing
             * here claims money has moved. It records that the person has
             * decided, which is the only fact available, and that is enough
             * to stop asking them.
             */
            @JavascriptInterface
            fun donated() {
                activity.runOnUiThread {
                    prefs.supportHidden = true
                    prefs.supportDonatedAt = System.currentTimeMillis()
                }
            }

            @JavascriptInterface
            fun close(dontShowAgain: Boolean) {
                activity.runOnUiThread {
                    if (dontShowAgain) prefs.supportHidden = true
                    try {
                        dialog.dismiss()
                    } catch (e: Exception) {
                    }
                }
            }
        }, "DBSupport")

        dialog.setContentView(web)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0xB3141612.toInt()))
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        // Tapping outside closes it, which is what the backdrop implies, but
        // that route must not count as "don't show again".
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            try {
                web.destroy()
            } catch (e: Exception) {
            }
        }

        web.loadUrl(PAGE)
        try {
            dialog.show()
        } catch (e: Exception) {
            // Activity went away between the check and here.
        }
    }
}

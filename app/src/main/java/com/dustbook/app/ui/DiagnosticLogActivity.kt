package com.dustbook.app.ui

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dustbook.app.R
import com.dustbook.app.utils.Prefs

/**
 * Read-only viewer for the in-app diagnostic log. Reachable from
 * Settings -> Developer options -> View log. Newest entry last.
 *
 * The viewer is a separate Activity so the Settings screen stays
 * single-page; the file can be many MB and the user may want to
 * scroll freely without losing the rest of the Settings state.
 *
 * No edit, no share, no clear - the parent Settings page handles
 * those so the surface here is genuinely read-only.
 */
class DiagnosticLogActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostic_log)

        val text = findViewById<TextView>(R.id.diagnostic_log_text)
        text.movementMethod = ScrollingMovementMethod()

        val prefs = Prefs(this)
        val body = prefs.diagLog.readAll()
        if (body.isBlank()) {
            text.text = getString(R.string.diagnostic_log_empty)
            return
        }
        text.text = body
        // Scroll to the bottom on first show - newer entries are at the
        // end, and the user's first action is usually "is the most
        // recent thing I care about there".
        text.post { text.scrollTo(0, text.height) }
    }
}

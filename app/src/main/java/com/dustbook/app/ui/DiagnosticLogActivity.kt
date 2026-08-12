package com.dustbook.app.ui

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dustbook.app.R
import com.dustbook.app.utils.Diag
import com.dustbook.app.utils.DiagCapture
import com.dustbook.app.utils.DiagnosticExport
import com.dustbook.app.utils.Prefs

/**
 * Read-only viewer for the per-channel diagnostic log files.
 *
 * Layout: a Spinner at the top that picks a channel, a body
 * TextView that shows the entries of that channel, and a row
 * of actions at the bottom (clear this channel, clear all,
 * export as JSON, export as text). The body auto-scrolls to
 * the bottom so the most recent entry is on screen.
 *
 * The activity is its own screen rather than a dialog so the
 * file can be much larger than any dialog body. The export
 * happens in the same activity, not via a chooser launched
 * from the developer-options page, so the chooser is the
 * last thing the user sees - they can pick an email, a chat,
 * a cloud drive, or copy to clipboard, all in one go.
 */
class DiagnosticLogActivity : AppCompatActivity() {

    private var spinner: Spinner? = null
    private var body: TextView? = null
    private var currentChannel: Diag.Channel = Diag.Channel.HOME_FEED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostic_log)
        title = getString(R.string.diagnostic_log_title)

        spinner = findViewById(R.id.diag_channel_spinner)
        body = findViewById(R.id.diagnostic_log_text)
        body?.movementMethod = ScrollingMovementMethod()

        val labels = Diag.Channel.values().map { ch ->
            getString(channelLabelRes(ch))
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner?.adapter = adapter
        spinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentChannel = Diag.Channel.values()[pos]
                render()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        findViewById<View>(R.id.diag_clear_channel).setOnClickListener {
            DiagCapture.clear(this, currentChannel)
            Toast.makeText(this, R.string.diag_cleared_channel, Toast.LENGTH_SHORT).show()
            render()
        }
        findViewById<View>(R.id.diag_clear_all).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.diag_clear_all_title)
                .setMessage(R.string.diag_clear_all_msg)
                .setPositiveButton(R.string.diag_clear_all_yes) { _, _ ->
                    DiagCapture.clearAll(this)
                    Toast.makeText(this, R.string.diag_cleared_all, Toast.LENGTH_SHORT).show()
                    render()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        findViewById<View>(R.id.diag_export_json).setOnClickListener {
            exportChannels(DiagnosticExport.FORMAT_JSON)
        }
        findViewById<View>(R.id.diag_export_text).setOnClickListener {
            exportChannels(DiagnosticExport.FORMAT_TEXT)
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        // Pick up entries written while the activity was
        // backgrounded - the diagnostic flags are
        // persistent, so a developer may have flipped
        // one in the developer-options page and come
        // back here to see the captures.
        render()
    }

    /** Render the current channel's entries into the
     *  body TextView, oldest first, and scroll to the
     *  bottom so the newest entry is on screen. */
    private fun render() {
        val entries = DiagCapture.read(this, currentChannel)
        val b = body ?: return
        if (entries.isEmpty()) {
            b.text = getString(R.string.diag_empty_channel)
        } else {
            val sb = StringBuilder()
            for (e in entries) {
                sb.append('[').append(Diag.fmtTs(e.ts)).append("] ")
                  .append('[').append(e.mode.name).append("] ")
                  .append('[').append(e.level.name).append("] ")
                  .append(if (e.thread.isBlank()) "" else "[${e.thread}] ")
                  .appendLine(e.message)
            }
            b.text = sb.toString()
        }
        b.post { b.scrollTo(0, b.height) }
    }

    /** Export every channel that is currently enabled
     *  on the developer-options page. The export is the
     *  union of all enabled channels; the developer can
     *  pick which ones to enable from the developer
     *  options page before opening the viewer. */
    private fun exportChannels(format: String) {
        val prefs = com.dustbook.app.utils.Prefs(this)
        val enabled = if (prefs.sp.getBoolean(Prefs.KEY_DIAGNOSTIC_ALL, false)) {
            Diag.Channel.values().toList()
        } else Diag.Channel.values().filter {
            // Prefs is the single source of truth for what
            // is on. We read it here rather than the
            // store to match the developer-options
            // screen's view.
            val p = com.dustbook.app.utils.Prefs(this)
            p.diagChannelEnabled(it)
        }
        if (enabled.isEmpty()) {
            Toast.makeText(this, R.string.diag_export_none, Toast.LENGTH_SHORT).show()
            return
        }
        val path = DiagnosticExport.share(this, enabled, format)
        if (path == null) {
            Toast.makeText(this, R.string.diag_export_failed, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.diag_export_ok, path), Toast.LENGTH_LONG).show()
        }
    }

    private fun channelLabelRes(ch: Diag.Channel): Int = when (ch) {
        Diag.Channel.HOME_FEED -> R.string.diag_ch_home_feed
        Diag.Channel.REELS -> R.string.diag_ch_reels
        Diag.Channel.STORY -> R.string.diag_ch_story
        Diag.Channel.ADS -> R.string.diag_ch_ads
        Diag.Channel.NETWORK -> R.string.diag_ch_network
        Diag.Channel.OFFLINE_SAVE -> R.string.diag_ch_offline_save
        Diag.Channel.APP_LIFECYCLE -> R.string.diag_ch_lifecycle
    }
}

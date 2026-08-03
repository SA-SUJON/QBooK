package com.dustbook.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView

/**
 * A WebView that can be told to keep playing when the window goes away.
 *
 * Android pauses media in a WebView on its own, before any of our code runs.
 * When the activity stops, the framework delivers onWindowVisibilityChanged
 * with GONE, and WebView's implementation of that treats it as "nobody can see
 * this" and suspends the media pipeline. onPause() was never the thing that
 * stopped the audio: removing that call changed nothing, because the pause had
 * already happened one layer down.
 *
 * That is why the foreground service appeared to work — the notification
 * showed, the service was alive, and the audio stopped anyway.
 *
 * Swallowing the GONE notification keeps the pipeline running. It is only
 * swallowed while [keepMediaAlive] is set, which MainActivity sets exactly
 * when the user has background audio switched on *and* the page has reported
 * that something is genuinely playing. Every other time the default behaviour
 * runs, so a WebView the user has finished with is still suspended properly
 * and does not hold the radio or the CPU awake.
 */
class MediaWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    /**
     * When true, a window-hidden notification is not passed down, so playback
     * survives the activity going to the background.
     */
    var keepMediaAlive: Boolean = false

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (keepMediaAlive && visibility != View.VISIBLE) {
            // Deliberately not calling super: that is the call that suspends
            // playback. Reporting VISIBLE instead is not enough, because the
            // framework compares against its own last known state.
            return
        }
        super.onWindowVisibilityChanged(visibility)
    }
}

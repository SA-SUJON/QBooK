package com.dustbook.app.utils

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Reports the real numbers behind a layout, on the device, for one bug.
 *
 * The "content sits too high with a dead strip at the bottom" report has now
 * survived four fixes, every one of them reasoned from the source rather than
 * measured. Each was plausible and each was wrong, which means the assumption
 * underneath them is wrong: the fault is not where it is being looked for.
 *
 * So stop guessing. This prints what every layer actually believes at the
 * moment the screen is wrong:
 *
 *  - the window and the real display size
 *  - what the system says the bars occupy, both ways round
 *  - the padding each container is carrying
 *  - the measured height of the feed and of the WebView
 *  - what the page itself thinks its viewport is
 *
 * Whichever number does not match the others is the fault. It is only built
 * when the user turns it on, and it draws nothing on its own.
 */
object LayoutProbe {

    /** Everything, as one block of text ready to be shown or copied. */
    fun snapshot(
        activity: Activity,
        root: View,
        contentRoot: View,
        webView: WebView,
        customViewShowing: Boolean,
        onReady: (String) -> Unit
    ) {
        val sb = StringBuilder()

        fun pad(v: View, name: String) {
            sb.append(name).append(": pad t=").append(v.paddingTop)
                .append(" b=").append(v.paddingBottom)
                .append("  size ").append(v.width).append('x').append(v.height)
                .append("  vis=").append(
                    when (v.visibility) {
                        View.VISIBLE -> "VISIBLE"
                        View.INVISIBLE -> "INVISIBLE"
                        else -> "GONE"
                    }
                )
                .append('\n')
        }

        val dm = activity.resources.displayMetrics
        sb.append("display   : ").append(dm.widthPixels).append('x')
            .append(dm.heightPixels).append("  density ").append(dm.density).append('\n')

        val vis = Rect()
        root.getWindowVisibleDisplayFrame(vis)
        sb.append("visible   : ").append(vis.width()).append('x').append(vis.height())
            .append("  top=").append(vis.top).append(" bottom=").append(vis.bottom).append('\n')

        val insets = ViewCompat.getRootWindowInsets(root)
        if (insets != null) {
            val nowBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val alwaysBars =
                insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            sb.append("bars now  : t=").append(nowBars.top)
                .append(" b=").append(nowBars.bottom).append('\n')
            sb.append("bars fixed: t=").append(alwaysBars.top)
                .append(" b=").append(alwaysBars.bottom).append('\n')
            sb.append("ime       : b=").append(ime.bottom).append('\n')
            sb.append("statusVis : ")
                .append(insets.isVisible(WindowInsetsCompat.Type.statusBars()))
                .append("  navVis: ")
                .append(insets.isVisible(WindowInsetsCompat.Type.navigationBars()))
                .append('\n')
        } else {
            sb.append("insets    : none\n")
        }

        sb.append("fullscreen: ").append(customViewShowing).append('\n')
        sb.append("orient    : ")
            .append(activity.resources.configuration.orientation)
            .append("  requested=").append(activity.requestedOrientation).append('\n')

        pad(root, "root      ")
        pad(contentRoot, "contentRt ")
        pad(webView, "webView   ")

        // The parent chain can be padded or offset by something nobody
        // remembers writing, so walk it rather than assuming.
        var p = webView.parent
        var depth = 0
        while (p is ViewGroup && depth < 6) {
            sb.append("  parent").append(depth).append(": ")
                .append(p.javaClass.simpleName)
                .append(" pad t=").append(p.paddingTop)
                .append(" b=").append(p.paddingBottom)
                .append(" h=").append(p.height)
                .append(" scrollY=").append(p.scrollY)
                .append('\n')
            p = p.parent
            depth++
        }

        sb.append("webScrollY: ").append(webView.scrollY)
            .append("  contentH=").append(
                (webView.contentHeight * webView.scale).toInt()
            ).append('\n')

        // Finally ask the page. This is the number that decides where content
        // is actually drawn, and nothing above guarantees it agrees.
        webView.evaluateJavascript(
            """
            (function(){
              try {
                var v = window.visualViewport;
                return JSON.stringify({
                  innerH: window.innerHeight,
                  innerW: window.innerWidth,
                  docH: document.documentElement.clientHeight,
                  bodyH: document.body ? document.body.clientHeight : -1,
                  scrollH: document.documentElement.scrollHeight,
                  visualH: v ? Math.round(v.height) : -1,
                  visualTop: v ? Math.round(v.offsetTop) : -1,
                  dpr: window.devicePixelRatio,
                  scrollY: window.scrollY
                });
              } catch (e) { return '{"err":"' + e + '"}'; }
            })();
            """.trimIndent()
        ) { raw ->
            val page = raw?.trim('"')?.replace("\\\"", "\"") ?: "null"
            sb.append("page      : ").append(page).append('\n')
            onReady(sb.toString())
        }
    }
}

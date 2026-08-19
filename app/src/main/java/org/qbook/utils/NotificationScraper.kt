package org.qbook.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the notifications Facebook already shows on its own site.
 *
 * There is no push channel available here. Push would mean a Facebook app id
 * and their servers delivering to ours, which a WebView wrapper cannot have.
 * The only source of truth is the same page the user would open themselves, so
 * this loads it in an offscreen WebView signed in with the same cookies -
 * exactly the arrangement [OfflineSync] already uses to fill the offline
 * store - and reads the rows out of the rendered DOM.
 *
 * It is deliberately narrow:
 *  - one pass at a time, and never while a pass is already running
 *  - the WebView is destroyed as soon as the pass ends or times out
 *  - it never touches the visible WebView, so it cannot disturb the feed
 *  - nothing is parsed from the network by hand: the page renders, then the
 *    rows are read, so a markup change degrades to "no rows" rather than to
 *    wrong rows
 */
object NotificationScraper {

    /** A single row from the notifications screen. */
    data class Item(
        val id: String,
        val text: String,
        val url: String,
        val unread: Boolean
    )

    private const val TIMEOUT_MS = 45_000L
    private const val SETTLE_MS = 3_500L

    /** m.facebook.com is what the app itself uses; keep the same host. */
    private const val PAGE = "https://m.facebook.com/notifications"

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    fun isRunning(): Boolean = running

    /**
     * Load the notifications page and hand back whatever rows it renders.
     *
     * Always calls [onDone], on the main thread, even on failure - the caller
     * is a worker that has to report a result either way.
     */
    fun fetch(context: Context, onDone: (List<Item>) -> Unit) {
        if (running) {
            onDone(emptyList())
            return
        }
        if (!UrlHelper.isLoggedIn()) {
            onDone(emptyList())
            return
        }

        running = true
        main.post { start(context, onDone) }
    }

    private fun start(context: Context, onDone: (List<Item>) -> Unit) {
        var web: WebView? = null
        var finished = false

        fun finish(items: List<Item>) {
            if (finished) return
            finished = true
            running = false
            try {
                web?.stopLoading()
                web?.destroy()
            } catch (e: Exception) {
            }
            web = null
            onDone(items)
        }

        try {
            val w = WebView(context.applicationContext)
            web = w
            // Offscreen but laid out. A zero viewport makes Facebook render
            // nothing at all, which is the same trap OfflineSync hit.
            w.layout(0, 0, 1080, 1920)

            w.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                // Notification rows are text; the avatars are not needed and
                // skipping them keeps the pass cheap. Unlike the offline
                // capture, nothing here depends on an <img> carrying a real
                // URL, so this is safe.
                loadsImagesAutomatically = false
                blockNetworkImage = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = true
                userAgentString = userAgentString.replace(" wv", "")
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

            w.addJavascriptInterface(object {
                @JavascriptInterface
                fun onRows(json: String) {
                    val items = parse(json)
                    main.post { finish(items) }
                }
            }, "DBNotify")

            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // The lite renderer fills the screen after load, so read
                    // once it has had a moment to settle rather than racing it.
                    main.postDelayed({
                        if (!finished) view?.evaluateJavascript(readScript(), null)
                    }, SETTLE_MS)
                }
            }

            main.postDelayed({ finish(emptyList()) }, TIMEOUT_MS)
            w.loadUrl(PAGE)
        } catch (e: Exception) {
            finish(emptyList())
        }
    }

    private fun parse(json: String): List<Item> {
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<Item>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("text").trim()
                if (text.isEmpty()) continue
                out.add(
                    Item(
                        id = o.optString("id").ifEmpty { text.hashCode().toString() },
                        text = text,
                        url = o.optString("url"),
                        unread = o.optBoolean("unread", false)
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Reads the rendered rows.
     *
     * Written against what the site actually emits rather than a remembered
     * shape. Two renderers have to be handled: the classic one, where a row is
     * an <a> pointing at the story, and the lite one, which draws rows as
     * MContainer nodes whose text lives in .native-text and whose target is an
     * action id rather than an href.
     */
    private fun readScript(): String = """
        (function() {
          function txt(el) {
            var t = (el.innerText || el.textContent || '');
            return t.replace(/\s+/g, ' ').trim();
          }

          var out = [];
          var seen = {};

          function push(id, text, url, unread) {
            if (!text || text.length < 8 || text.length > 300) return;
            var key = id || text;
            if (seen[key]) return;
            seen[key] = 1;
            out.push({ id: String(id || ''), text: text, url: String(url || ''),
                       unread: !!unread });
          }

          // Classic markup: every row is a link to the story itself.
          var links = document.querySelectorAll(
            'a[href*="/story.php"],a[href*="story_fbid"],' +
            'a[href*="notif_t="],a[href*="notif_id="]'
          );
          for (var i = 0; i < links.length && i < 60; i++) {
            var a = links[i];
            // Climb only to a node that still describes this one row. A bare
            // closest('div') walks to whatever wrapper happens to hold the
            // list, and then every row reports the text of all of them.
            var row = a.closest('[data-sigil],li,article');
            if (row && row.querySelectorAll('a[href]').length > 1) row = null;
            var t = txt(row || a);
            if (!t) continue;
            var idm = (a.getAttribute('href') || '').match(/notif_id=(\d+)/);
            // Facebook marks a seen row by styling it, not by attribute, so
            // treat anything it has not marked as read as unread.
            var unread = !!(row && (row.getAttribute('data-sigil') || '')
                              .indexOf('unread') !== -1);
            push(idm ? idm[1] : a.getAttribute('href'), t,
                 a.getAttribute('href'), unread);
          }

          // Lite renderer: rows are containers with an action id.
          if (!out.length) {
            var rows = document.querySelectorAll(
              '[data-mcomponent="MContainer"][data-action-id]'
            );
            for (var j = 0; j < rows.length && j < 200; j++) {
              var r = rows[j];
              // A row holds its own text and nothing else of substance.
              var nt = r.querySelectorAll('.native-text');
              if (!nt.length) continue;
              var t2 = txt(r);
              if (!t2) continue;
              // Skip the chrome: tab bar entries and headings.
              var al = r.getAttribute('aria-label') || '';
              if (/\s+\d+\s+of\s+\d+\s*$/.test(al)) continue;
              if (r.querySelector('[data-action-id]')) continue;
              push(r.getAttribute('data-action-id'), t2,
                   r.getAttribute('data-lite-link') || '', false);
            }
          }

          try { window.DBNotify.onRows(JSON.stringify(out)); } catch (e) {}
        })();
    """.trimIndent()
}

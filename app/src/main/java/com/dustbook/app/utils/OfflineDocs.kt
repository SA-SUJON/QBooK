package com.dustbook.app.utils

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import com.dustbook.app.offline.PageAssembly
import org.json.JSONArray

/**
 * Keeps the real Facebook pages, so being offline looks like being online.
 *
 * The previous approach rendered a screen of our own from a list of saved
 * items. It worked, but it was obviously not Facebook: no header, no tab bar,
 * no like or comment controls, and reels laid out by our CSS rather than by
 * the site.
 *
 * m.facebook.com is server-rendered, so the document it returns already
 * contains the whole shell and the content. Storing that document, and serving
 * it back for the main frame when there is no connection, gives the real UI
 * for free - every button in its right place, because it *is* the site. The
 * images, video, CSS and fonts it references come from [OfflineCache].
 *
 * Writes are always on a background thread. Nothing here goes near the bridge:
 * an earlier attempt passed the rendered DOM through JavaScript and the
 * documents were far too large to survive the trip.
 */
object OfflineDocs {

    private const val DIR = "offline_docs_v1"

    /** A document older than this is refetched as soon as we are online. */
    private const val STALE_AFTER_MS = 20L * 60 * 1000

    /** Documents are HTML; anything much larger than this is not a page. */
    private const val MAX_DOC_BYTES = 6 * 1024 * 1024
    private const val MIN_DOC_BYTES = 20 * 1024

    /** Per-screen ceiling on how many asset URLs are queued for download. */
    private const val MAX_PREFETCH_URLS = 400

    /** `url(...)` inside a stylesheet - fonts, sprites and masks. */
    private val CSS_URL = Regex("""url\(\s*["']?(https://[^"'")\s]+)""")

    /**
     * Screens worth keeping, by first path segment. These are the tabs the
     * user can reach from the bar at the top, which is what has to work for
     * offline to feel normal.
     */
    private val SCREENS = linkedMapOf(
        "home" to "https://m.facebook.com/",
        "reels" to "https://m.facebook.com/reel/",
        "stories" to "https://m.facebook.com/stories/",
        "watch" to "https://m.facebook.com/watch/",
        "notifications" to "https://m.facebook.com/notifications/",
        "friends" to "https://m.facebook.com/friends/",
        "marketplace" to "https://m.facebook.com/marketplace/",
        "menu" to "https://m.facebook.com/menu/"
    )

    private val pool = Executors.newFixedThreadPool(2)
    private val inFlight = AtomicInteger(0)

    /** Last outcome per screen, so a missing tab can be diagnosed. */
    private val outcomes = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile private var root: File? = null
    @Volatile private var appContext: Context? = null
    @Volatile var enabled: Boolean = true

    /**
     * Whether new content may be *written*.
     *
     * [enabled] used to gate reading and writing together, so switching
     * saving off also hid content already on disk. Reading is now always
     * allowed; only collecting new content follows the user's switches.
     */
    @Volatile var writeEnabled: Boolean = true


    /**
     * The WebView's user agent, set by the app at startup.
     *
     * This is not optional. Facebook chooses an entirely different renderer
     * per user agent, so fetching without one stored a page the WebView would
     * never have been served - which is why most screens came back empty
     * offline and only reels had anything.
     */
    @Volatile var userAgent: String? = null

    fun init(context: Context) {
        if (root != null) return
        synchronized(this) {
            if (root != null) return
            appContext = context.applicationContext
            val d = File(context.filesDir, DIR)
            if (!d.exists()) d.mkdirs()
            root = d
        }
    }

    /** Which stored screen, if any, answers a request for [url]. */
    fun screenFor(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = UrlHelper.hostOf(url) ?: return null
        if (!host.endsWith("facebook.com")) return null
        val path = try {
            Uri.parse(url).path?.lowercase(Locale.ROOT) ?: ""
        } catch (e: Exception) {
            return null
        }
        val first = path.trim('/').substringBefore('/')
        return when (first) {
            "", "home.php" -> "home"
            "reel", "reels" -> "reels"
            "stories", "story" -> "stories"
            "watch", "videos" -> "watch"
            "notifications" -> "notifications"
            "friends" -> "friends"
            "marketplace" -> "marketplace"
            "menu", "bookmarks" -> "menu"
            else -> "home"
        }
    }

    private fun fileFor(screen: String): File? {
        val dir = root ?: return null
        if (!SCREENS.containsKey(screen)) return null
        return File(dir, "$screen.html")
    }

    fun has(screen: String): Boolean {
        val f = fileFor(screen) ?: return false
        return f.exists() && f.length() > 0
    }

    fun savedScreens(): List<String> = SCREENS.keys.filter { has(it) }

    /**
     * Screens the offline navigation may route to.
     *
     * Not [savedScreens]: that lists screens with a stored *document*, and a
     * screen can be perfectly usable without one. Stories are captured as
     * cards and rendered by the story viewer, so stories.html frequently does
     * not exist — the tab was therefore treated as unavailable and tapping it
     * did nothing at all. shellFor() builds a page from the cards in exactly
     * that case, so the route is valid whenever either exists.
     */
    fun navigableScreens(): List<String> = SCREENS.keys.filter { screen ->
        if (has(screen)) return@filter true
        val section = when (screen) {
            "reels", "watch" -> OfflineFeed.SECTION_REELS
            "stories" -> OfflineFeed.SECTION_STORIES
            "home" -> OfflineFeed.SECTION_FEED
            else -> return@filter false
        }
        // storedCount, not realPlayableCount. This runs on the WebView's
        // resource thread while a page is being assembled, and
        // realPlayableCount re-reads and re-parses the section's JSON and
        // then stats every media file it names — for four screens, on every
        // served page. The question here is only "is this tab worth
        // offering", which the cheap count answers.
        OfflineFeed.storedCount(section) > 0
    }

    /** The URL a stored screen answers on. */
    fun urlFor(screen: String): String? = SCREENS[screen]

    fun isStale(screen: String): Boolean {
        val f = fileFor(screen) ?: return true
        if (!f.exists()) return true
        return System.currentTimeMillis() - f.lastModified() > STALE_AFTER_MS
    }

    /**
     * A cached, ready-to-serve page, keyed by screen.
     *
     * Serving used to rebuild the whole document on every navigation, on the
     * WebView's resource thread: read the stored page, parse the item store,
     * hash and stat every media URL to decide what is playable, then
     * concatenate every card's markup. With a couple of hundred reels saved
     * that is several hundred SHA-256 hashes and filesystem stats per back
     * press, which is why going back from Reels crawled — and why it got
     * worse the more was downloaded.
     *
     * The result only changes when the stored page or the item store changes,
     * so it is pre-encoded as bytes and held until someone calls
     * [invalidate]. This means navigation from the built cache is a single
     * ConcurrentHashMap lookup and a ByteArrayInputStream — no file I/O,
     * no string copying, and no hashing on the resource thread.
     */
    private class Built(val bytes: ByteArray)

    private val built = java.util.concurrent.ConcurrentHashMap<String, Built>()

    /** Called whenever stored content changes, so the next serve rebuilds. */
    fun invalidate() {
        built.clear()
    }

    /**
     * Same, but scoped to the screens fed by one section. A view-mark on a
     * single reel used to clear every screen's cache — home included — so
     * scrolling reels alone made the next back-to-home rebuild from
     * scratch every time. Only the screens that actually draw from
     * [section] need to rebuild.
     */
    fun invalidate(section: String) {
        val screens = when (section) {
            OfflineFeed.SECTION_REELS -> listOf("reels", "watch")
            OfflineFeed.SECTION_STORIES -> listOf("stories")
            OfflineFeed.SECTION_FEED -> listOf("home")
            else -> null
        }
        if (screens == null) { built.clear(); return }
        screens.forEach { built.remove(it) }
    }

    fun serve(request: WebResourceRequest): WebResourceResponse? {
        if (!enabled) return null
        if (!request.isForMainFrame) return null
        if (!request.method.equals("GET", true)) return null
        val screen = screenFor(request.url.toString()) ?: return null
        val f = fileFor(screen) ?: return null

        if (!f.exists() || f.length() == 0L) {
            return shellFor(screen)
        }

        // The built cache is invalidated only when the store changes, so a
        // hit here is always fresh. No stamp check, no file stat, no copy.
        built[screen]?.let { b ->
            return WebResourceResponse(
                "text/html", "utf-8", 200, "OK",
                mapOf("Cache-Control" to "no-store"),
                b.bytes.inputStream()
            )
        }

        return try {
            val section = when (screen) {
                "reels", "watch" -> OfflineFeed.SECTION_REELS
                "stories" -> OfflineFeed.SECTION_STORIES
                "home" -> OfflineFeed.SECTION_FEED
                else -> null
            }
            // The entries themselves (not bare markup) so each served card
            // carries its vault id for the seen tracker. The same complete
            // list feeds the count, so number, screen and tracker can never
            // disagree about which card an id belongs to.
            val items = section?.let { OfflineFeed.realPlayableItems(it) }
                ?: emptyList()
            val cards = items.map { stampOfflineId(it.html, it.id) }
            val cardIds = items.map { it.id }

            // Resume position, so the user picks up where they left off
            // instead of scrolling from the top every time - EXCEPT on
            // home. Replaying a stored feed position yanked the freshly
            // loaded page below the top five times over 2.5 seconds,
            // fought the user scrolling back up, and repeated forever
            // because positions are only ever reported while scrolled
            // down: the fake "home feed top" of the round-11 report.
            // Home simply starts at the top; reels and stories keep
            // their resume untouched.
            val resumeId = when (screen) {
                "reels", "watch" -> appContext?.let { Prefs(it).offlineResumeReel }
                "stories" -> appContext?.let { Prefs(it).offlineResumeStories }
                "home" -> null
                else -> null
            }

            val docText = f.readText()
            // The saved cards are composed into the stored document here,
            // in Kotlin, before serving. No runtime script stands between
            // the user and their saved content any more: if every script
            // on the page failed, the posts would still be on screen,
            // because they are the document.
            val withCards =
                if (cards.isNotEmpty() && screen != "stories") {
                    // Only reels snaps one card per gesture; a snapping
                    // home feed would be a bug of its own.
                    PageAssembly.compose(docText, cards, snap = screen == "reels")
                } else docText

            val html = promoHideCss() +
                withCards +
                unmuteStripScript() +
                OfflineBanner.html() +
                "<script>" + OfflineNav.script(navigableScreens()) + "</script>" +
                (if (cards.isNotEmpty() && screen == "stories") {
                    "<script>" + storyViewer(cards, cardIds,
                        OfflineFeed.SECTION_STORIES, resumeId) + "</script>"
                } else "") +
                (if (cards.isNotEmpty() && screen != "stories") {
                    "<script>" + PageAssembly.resumeScript(resumeId, screen) + "</script>" +
                    "<script>" + PageAssembly.viewTrackScript(
                        section ?: OfflineFeed.SECTION_FEED) + "</script>"
                } else "") +
                (if (screen == "home") unmuteByDefaultScript() else "") +
                (if (screen == "reels" || screen == "watch" || screen == "stories") {
                    "<script>" + VideoHelper.getOfflineVideoAssistScript() + "</script>"
                } else "")

            // Encode once, serve many times.
            val b = html.toByteArray()
            built[screen] = Built(b)

            WebResourceResponse(
                "text/html", "utf-8", 200, "OK",
                mapOf("Cache-Control" to "no-store"),
                b.inputStream()
            )
        } catch (e: Exception) {
            null
        }
    }

    /** @see shellFor */

    /** A card's own opening tag - the only place its vault id is stamped. */
    private val CARD_FIRST_TAG = Regex("<[a-zA-Z][^>]*>")

    /**
     * Stamps a served card with its vault id, so the page's own seen
     * tracker can name exactly the entry on screen. Set only at serve
     * time - stored markup stays untouched - and carried as an attribute
     * because the resume script has always looked for `data-offline-id`
     * as one of its anchors. The id is entity-escaped (&, ") and inserted
     * by string surgery rather than a regex replacement, so an id that
     * happens to contain a '$' can never be read as a group reference.
     */
    private fun stampOfflineId(html: String, id: String): String {
        if (id.isBlank()) return html
        val safe = id.replace("&", "&amp;").replace("\"", "&quot;")
        val m = CARD_FIRST_TAG.find(html) ?: return html
        val tag = m.value
        if (tag.contains("data-offline-id=")) return html
        val insertAt = if (tag.endsWith("/>")) tag.length - 2
            else tag.length - 1
        val newTag = tag.substring(0, insertAt) +
            " data-offline-id=\"" + safe + "\"" + tag.substring(insertAt)
        return html.substring(0, m.range.first) + newTag +
            html.substring(m.range.last + 1)
    }

    /**
     * Full-screen story viewer. Stories are MScreen captures, not inline
     * cards, so they are shown one at a time. Left-half tap = previous,
     * right-half tap = next. An overlay hides the page chrome so the
     * story fills the viewport.
     *
     * A story on screen IS a story seen - the tracker is the show()
     * toast itself, reported through the ids riding alongside the cards
     * ([ids] is index-aligned with [cards], both from the same complete
     * list).
     */
    private fun storyViewer(cards: List<String>, ids: List<String>,
                            section: String, resumeId: String?): String {
        if (cards.isEmpty()) return ""
        // One JSON array, never a template literal: a "</script" inside a
        // stored card would otherwise end this host block early, and the
        // "<\\/" spelling is the JSON-blessed way to carry it safely.
        // Per-card strings additionally isolate one malformed card from
        // the rest.
        val json = JSONArray()
        for (c in cards) json.put(c)
        val safe = json.toString().replace("</", "<\\/")
        val jsonIds = JSONArray()
        for (i in ids) jsonIds.put(i)
        val safeIds = jsonIds.toString().replace("</", "<\\/")
        val resumeJs = if (resumeId != null) {
            "\nvar START=0;var all=STORIES;for(var i=0;i<all.length;i++){" +
            "if(all[i].indexOf('" + resumeId + "')>=0){START=i;break;}}\n"
        } else "\nvar START=0;\n"
        return """
        (function(){
          if(window.__dbStoryViewer)return;
          window.__dbStoryViewer=true;

          var STORIES = $safe;
          var IDS = $safeIds;
          var SEC = "$section";
          if(!STORIES || !STORIES.length)return;
          $resumeJs
          var idx=START;

          var overlay=document.createElement('div');
          overlay.id='__db_story_overlay';
          // position:fixed is measured against the viewport, not against the
          // padding the activity applies to its root view. Online that padding
          // is what keeps content clear of the status bar; a fixed overlay
          // inside the WebView never sees it, so the story sat too high with
          // its top edge under the status bar.
          //
          // env(safe-area-inset-*) is the viewport-level equivalent, but it
          // only resolves when the document asks for viewport-fit=cover, and
          // this overlay is injected into Facebook's own stored markup whose
          // meta tag we do not control. Ensure the meta tag says so first,
          // then the insets resolve; where they still do not, the 0px
          // fallback leaves the previous behaviour untouched.
          try {
            var vp = document.querySelector('meta[name=viewport]');
            if (!vp) {
              vp = document.createElement('meta');
              vp.setAttribute('name', 'viewport');
              vp.setAttribute('content', 'width=device-width,initial-scale=1');
              document.head.appendChild(vp);
            }
            var c = vp.getAttribute('content') || '';
            if (c.indexOf('viewport-fit') === -1) {
              vp.setAttribute('content', c + ',viewport-fit=cover');
            }
          } catch (e) {}

          // Set through a stylesheet rather than the style attribute. An
          // inline declaration is parsed property by property and a value the
          // parser does not recognise is dropped on the spot, which would
          // leave top unset. In a stylesheet the whole rule is handed to the
          // engine, so top falls back cleanly to 0px where env() is unknown
          // and resolves where it is not.
          try {
            var st = document.createElement('style');
            st.textContent =
              '#__db_story_overlay{position:fixed;left:0;right:0;' +
              'z-index:99999;background:#000;overflow:hidden;' +
              'top:0;bottom:0;' +
              'top:env(safe-area-inset-top,0px);' +
              'bottom:env(safe-area-inset-bottom,0px);}';
            document.head.appendChild(st);
          } catch (e) {}
          overlay.style.cssText='position:fixed;left:0;right:0;top:0;bottom:0;'+
            'z-index:99999;background:#000;overflow:hidden;';
          document.body.appendChild(overlay);

          var prevZone=document.createElement('div');
          prevZone.style.cssText='position:absolute;top:0;left:0;width:33%;bottom:0;z-index:1;';
          overlay.appendChild(prevZone);

          var nextZone=document.createElement('div');
          nextZone.style.cssText='position:absolute;top:0;right:0;width:33%;bottom:0;z-index:1;';
          overlay.appendChild(nextZone);

          var content=document.createElement('div');
          content.id='__db_story_content';
          content.style.cssText='position:absolute;top:0;left:0;right:0;bottom:0;display:flex;align-items:center;justify-content:center;';
          overlay.appendChild(content);

          var dots=document.createElement('div');
          dots.style.cssText='position:absolute;top:12px;left:0;right:0;text-align:center;z-index:2;';
          overlay.appendChild(dots);

          function updateDots(){
            var h='';
            for(var i=0;i<STORIES.length;i++){
              h+='<span style="display:inline-block;width:8px;height:8px;border-radius:4px;margin:3px;background:'+(i===idx?'#fff':'rgba(255,255,255,0.4)')+'"></span>';
            }
            dots.innerHTML=h;
          }

          function show(n){
            if(n<0||n>=STORIES.length)return;
            idx=n;
            content.innerHTML=STORIES[idx];
            updateDots();
            // One story on screen is one story seen: the only honest
            // signal a tap-through viewer has. The section travels with
            // the cards, so a fallback shell reports to the vault the
            // cards actually came from.
            var sid = IDS[idx];
            if (sid && window.FBPro && FBPro.markViewed) {
              try { FBPro.markViewed(SEC, sid); } catch (e) {}
            }
          }

          prevZone.addEventListener('click',function(ev){
            ev.stopPropagation();if(idx>0)show(idx-1);
          });
          nextZone.addEventListener('click',function(ev){
            ev.stopPropagation();if(idx<STORIES.length-1)show(idx+1);
          });

          var close=document.createElement('div');
          close.style.cssText='position:absolute;top:12px;right:16px;z-index:3;color:#fff;font-size:14px;padding:8px;cursor:pointer;';
          close.textContent='\u2715';
          close.addEventListener('click',function(ev){
            ev.stopPropagation();
            overlay.remove();
            window.__dbStoryViewer=false;
          });
          overlay.appendChild(close);

          updateDots();
          show(START);
        })();
        """.trimIndent()
    }


    /**
     * When no stored Facebook document exists but we have saved cards,
     * build the lightest possible page that shows them instead of
     * returning null (which would hand the request to a WebView with no
     * connection — the raw ERR_INTERNET_DISCONNECTED page).
     */
    private fun shellFor(screen: String): WebResourceResponse? {
        val section = when (screen) {
            "reels", "watch" -> OfflineFeed.SECTION_REELS
            "stories" -> OfflineFeed.SECTION_STORIES
            else -> OfflineFeed.SECTION_FEED
        }
        val firstItems = OfflineFeed.realPlayableItems(section)
        val (winSection, winItems) =
            if (firstItems.isNotEmpty()) section to firstItems else
                listOf(OfflineFeed.SECTION_REELS, OfflineFeed.SECTION_FEED,
                       OfflineFeed.SECTION_STORIES)
                    .map { s -> s to OfflineFeed.realPlayableItems(s) }
                    .firstOrNull { it.second.isNotEmpty() }
                ?: return offlineFallbackPage()
        val use = winItems.map { stampOfflineId(it.html, it.id) }
        val useIds = winItems.map { it.id }

        val html = "<!DOCTYPE html><html lang=\"en\"><head>" +
            "<meta charset=\"utf-8\"><meta name=\"viewport\" " +
            "content=\"width=device-width,initial-scale=1,user-scalable=no\">" +
            "<style>body{margin:0;background:#18191a;color:#e4e6eb;" +
            "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto," +
            "sans-serif}</style>" + promoHideCss() +
            // The shell is ours, so reels paging is stated directly and
            // must match REELS_SNAP_CSS: mandatory since round 15, the
            // isolated retrial after proximity was proven unable to cap
            // a fast fling and stop:always proven inert on this WebView
            // (v5.2.14 device report: scrolls fine, still skips 2/3).
            (if (screen == "reels") {
                "<style id=\"__db_reels_snap\">" +
                "html,body{scroll-snap-type:y mandatory}" +
                "#__db_cards>*{scroll-snap-align:start}</style>"
            } else "") +
            "</head><body>" + PageAssembly.holderHtml(use) +
            unmuteStripScript() +
            "<script>" + OfflineNav.script(navigableScreens()) + "</script>" +
            (if (screen != "stories") {
                "<script>" + PageAssembly.viewTrackScript(winSection) +
                    "</script>"
            } else "") +
            (if (screen == "reels" || screen == "watch" || screen == "stories") {
                "<script>" + VideoHelper.getOfflineVideoAssistScript() +
                    "</script>"
            } else "") +
            (if (screen == "home") unmuteByDefaultScript() else "") +
            (if (screen == "stories") {
                "<script>" + storyViewer(use, useIds, winSection, null) + "</script>"
            } else "") +
            "</body></html>"

        return WebResourceResponse("text/html", "utf-8", 200, "OK",
            mapOf("Cache-Control" to "no-store"), html.byteInputStream())
    }

    /**
     * Store a page captured from a live WebView.
     *
     * This is the only way these documents can be obtained. Facebook answers
     * m.facebook.com with HTTP 400 to any plain HTTP client - verified against
     * the live site with five different header combinations, logged out and
     * with a cookie, and every one was refused. [fetchScreen] therefore never
     * stored anything, savedScreens() stayed empty, and going offline showed
     * the bare "Can't load the page" screen with no saved content at all.
     *
     * A real WebView is not refused, and [OfflineSync] already runs one that
     * is signed in and has these very screens open, so the document is taken
     * from there instead of re-requesting a URL that will not be answered.
     */
    fun storeFromPage(screen: String, html: String): Boolean {
        if (!writeEnabled) return false
        val f = fileFor(screen) ?: return false
        if (html.length < MIN_DOC_BYTES) {
            outcomes[screen] = "tiny${html.length / 1024}k"
            return false
        }
        if (html.length > MAX_DOC_BYTES) {
            outcomes[screen] = "size${html.length / 1024}k"
            return false
        }
        // A logged-out page must never be stored: serving it offline would
        // show the login screen to a signed-in user.
        if (html.contains("name=\"login\"", true) &&
            html.contains("type=\"password\"", true)
        ) {
            outcomes[screen] = "loggedout"
            return false
        }
        // Reject Facebook error pages. Bare paths like /reel/ or /stories/
        // without an ID return this page, and storing it makes offline show
        // "The link you followed may be broken" for every request.
        if (html.contains("The link you followed may be broken", true)) {
            outcomes[screen] = "brokenlink"
            return false
        }

        return try {
            val tmp = File(f.parentFile, f.name + ".part")
            tmp.writeText(rewriteForOffline(html))
            if (tmp.renameTo(f)) {
                outcomes[screen] = "ok${html.length / 1024}k"
                true
            } else {
                tmp.delete()
                outcomes[screen] = "writefail"
                false
            }
        } catch (e: Exception) {
            outcomes[screen] = e.javaClass.simpleName
            false
        }
    }

    /**
     * Fetch and store the screens the user has enabled.
     *
     * Runs entirely on [pool]. The request carries the session cookies, so
     * what we store is the signed-in page, not a logged-out one.
     */
    /**
     * Kept for the screens a WebView never visits.
     *
     * Note that m.facebook.com answers HTTP 400 to a plain client, so this
     * will not succeed there - the pages that matter are captured from a live
     * WebView by [storeFromPage] instead. This is left in place because it
     * costs nothing when it fails and still records an outcome, which is what
     * the diagnostics line reports.
     */
    fun refresh(screens: List<String> = SCREENS.keys.toList(), force: Boolean = false) {
        if (!writeEnabled) return
        if (inFlight.get() > 0) return
        for (screen in screens) {
            val url = SCREENS[screen] ?: continue
            if (!force && !isStale(screen)) continue
            inFlight.incrementAndGet()
            pool.execute {
                try {
                    fetchScreen(screen, url)
                } catch (e: Exception) {
                    outcomes[screen] = e.javaClass.simpleName
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
    }

    fun isRefreshing(): Boolean = inFlight.get() > 0

    fun statusLine(): String {
        if (SCREENS.keys.none { outcomes.containsKey(it) }) return "not run yet"
        return SCREENS.keys.joinToString(", ") { s ->
            s + "=" + (outcomes[s] ?: "-")
        }
    }

    private fun fetchScreen(screen: String, url: String) {
        val f = fileFor(screen) ?: return
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 25_000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                userAgent?.let { setRequestProperty("User-Agent", it) }
                setRequestProperty("Accept-Language", "en-GB,en;q=0.9")
                CookieManager.getInstance().getCookie(url)?.let {
                    setRequestProperty("Cookie", it)
                }
            }
            if (conn.responseCode != 200) {
                outcomes[screen] = "http${conn.responseCode}"
                return
            }
            val enc = conn.getHeaderField("Content-Encoding")
            if (enc != null && !enc.equals("identity", true)) {
                outcomes[screen] = "encoded"
                return
            }
            val type = conn.contentType?.lowercase(Locale.ROOT) ?: ""
            if (!type.contains("html")) {
                outcomes[screen] = "nothtml"
                return
            }

            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > MAX_DOC_BYTES) {
                outcomes[screen] = "size${bytes.size / 1024}k"
                return
            }
            if (bytes.size < MIN_DOC_BYTES) {
                outcomes[screen] = "tiny${bytes.size / 1024}k"
                return
            }

            var html = String(bytes, Charsets.UTF_8)
            if (html.contains("name=\"login\"", true) &&
                html.contains("type=\"password\"", true)
            ) {
                outcomes[screen] = "loggedout"
                return
            }

            html = rewriteForOffline(html)

            val tmp = File(f.parentFile, f.name + ".part")
            tmp.writeText(html)
            if (tmp.renameTo(f)) {
                outcomes[screen] = "ok${bytes.size / 1024}k"
            } else {
                tmp.delete()
                outcomes[screen] = "writefail"
            }
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
        }
    }

    /**
     * Make the stored copy absolute.
     *
     * A stored document is replayed at whatever URL the user navigated to, so
     * root-relative asset paths would resolve differently and miss the cache.
     */
    private fun rewriteForOffline(html: String): String = html
        .replace("src=\"/", "src=\"https://m.facebook.com/")
        .replace("href=\"/", "href=\"https://m.facebook.com/")
        .replace("src='\\/", "src='https://m.facebook.com/")
        .replace("href='\\/", "href='https://m.facebook.com/")

    /** Every media URL a stored page references, for the asset prefetch. */
    fun mediaUrls(screen: String): List<String> {
        val f = fileFor(screen) ?: return emptyList()
        if (!f.exists()) return emptyList()
        return try {
            val html = f.readText()
            val re = Regex("""https://[^"'\s\\\)]+?(?:fbcdn\.net|fbsbx\.com)[^"'\s\\\)]*""")
            val all = re.findAll(html)
                .map { it.value.replace("&amp;", "&") }
                .distinct()
                .toList()

            val (chrome, media) = all.partition { it.contains("/rsrc.php/") }

            val fonts = chrome.filter { it.contains(".css") || !it.contains(".") }
                .asSequence()
                .mapNotNull { OfflineCache.textOf(it) }
                .flatMap { css -> CSS_URL.findAll(css) }
                .map { it.groupValues[1].replace("&amp;", "&") }
                .filter { it.startsWith("https://") }
                .distinct()
                .take(80)
                .toList()

            val (avatars, photos) = media.partition {
                it.contains("/t39.30808-1/") || it.contains("_n.jpg?stp=c")
            }
            (fonts + chrome + avatars + photos).distinct().take(MAX_PREFETCH_URLS)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun sizeBytes(): Long {
        val dir = root ?: return 0
        return try { dir.listFiles()?.sumOf { it.length() } ?: 0 } catch (e: Exception) { 0 }
    }

    fun clear() {
        invalidate()
        val dir = root ?: return
        try { dir.listFiles()?.forEach { it.delete() } } catch (e: Exception) {}
    }

    /**
     * Story pager: shows stored story cards one at a time with tap-based
     * navigation (left half = previous, right half = next). Stories are
     * full MScreen captures, not inline cards, so they cannot be injected
     * alongside feed content.
     */
    


    /**
     * Removes Facebook's "Tap to unmute" overlay from a served page.
     *
     * Stripping it at capture time was necessary but not sufficient. It only
     * cleans markup captured *after* the fix, so every page already on disk
     * still carried the overlay, and the stored full document -- which is a
     * verbatim copy of Facebook's page, not something we assemble -- was never
     * passed through the capture path at all.
     *
     * Doing it in the page covers both: the element is removed from the live
     * DOM whatever produced it, and again whenever Facebook's own markup
     * re-inserts one. A MutationObserver is used rather than a one-shot sweep
     * because the offline video assist swaps elements in after load.
     */
    private fun unmuteStripScript(): String = """
        <script id="__db_unmute_strip">
        (function(){
          if (window.__dbUnmuteStrip) return;
          window.__dbUnmuteStrip = true;

          var SEL = '[data-sigil~="m-video-overlay"],[data-sigil*="m-video-overlay"]';

          function textLooksLikeUnmute(el) {
            var t = (el.textContent || '').trim().toLowerCase();
            if (!t || t.length > 40) return false;
            return t.indexOf('unmute') !== -1 || t.indexOf('tap to') === 0;
          }

          function sweep() {
            var n;
            try { n = document.querySelectorAll(SEL); } catch (e) { return; }
            for (var i = 0; i < n.length; i++) {
              var el = n[i];
              if (el.parentNode) el.parentNode.removeChild(el);
            }
            // Facebook does not always tag it. Catch the label by its own
            // text, but only on small leaf-ish nodes so a real caption
            // mentioning the word is never removed.
            var spans = document.querySelectorAll('div,span');
            for (var j = 0; j < spans.length && j < 3000; j++) {
              var s = spans[j];
              if (s.children.length > 2) continue;
              if (!textLooksLikeUnmute(s)) continue;
              var box = s.closest ? s.closest('[data-sigil]') : null;
              var target = box && textLooksLikeUnmute(box) ? box : s;
              if (target.parentNode) target.parentNode.removeChild(target);
            }
          }

          sweep();
          try {
            // Debounced, and only when nodes were actually added.
            //
            // Running the sweep on every mutation meant walking up to 3000
            // elements each time, and scrolling produces mutations
            // continuously — so the scan was competing with the scroll for
            // the whole gesture. Coalescing to one pass per idle moment does
            // the same job without being in the way.
            var pending = 0;
            function schedule() {
              if (pending) return;
              pending = setTimeout(function(){ pending = 0; sweep(); }, 400);
            }
            new MutationObserver(function(muts){
              for (var i = 0; i < muts.length; i++) {
                if (muts[i].addedNodes && muts[i].addedNodes.length) {
                  schedule();
                  return;
                }
              }
            }).observe(document.documentElement, {childList:true, subtree:true});
          } catch (e) {}
          document.addEventListener('DOMContentLoaded', sweep);
        })();
        </script>
    """.trimIndent()

    /**
     * Hides app-promotion banners before the stored page paints.
     *
     * Online this is done from onPageStarted, before first paint. An offline
     * page is answered by shouldInterceptRequest, so that hook has already
     * run against the previous document and everything we append lands
     * *after* the stored markup — by which time the "Open in app" bar has
     * been on screen for a frame. That is the flash: it appeared, then the
     * scripted sweep removed it.
     *
     * Prepending a plain stylesheet fixes it. The rule is parsed before the
     * body it applies to, so the bar never gets a frame to paint in, and no
     * script has to run first.
     */
    private fun promoHideCss(): String = """
        <style id="__db_promo_hide">
        #header-notices,div[id^="header-notice"],
        [data-testid*="app_download"],[data-testid*="install_app"],
        [data-testid*="open_in_app"],[data-testid*="app_upsell"],
        [data-nt="FB:APP_INSTALL"],[data-sigil*="app_install"],
        [data-sigil*="appinstall"],[data-sigil*="mUpsellBanner"],
        #mobile_app_install_banner,#appManifestBanner,#MComposerAppInstallBanner,
        a[href*="play.google.com/store"],a[href*="apps.apple.com"],
        a[href^="market://"],a[href^="fb://"],a[href^="intent://"],
        a[href*="/mobile/download"],a[href*="messenger.com/download"],
        .mobile-app-banner,.app-install-banner,.app-download-banner,
        .smartbanner,.smart-banner,.get-app-banner
        {display:none !important;}
        </style>
    """.trimIndent()

    /**
     * Offline home-feed videos default to sound on.
     *
     * Facebook ships its feed players muted because its own JS decides when
     * sound may start; offline that JS never runs, so the muted state stored
     * in the markup would otherwise be permanent. Scoped to the home screen
     * only - reels and stories keep their players exactly as captured - and
     * applied to each video once, before the user presses play. A running
     * clip is never un-muted underneath the user: a video that was only
     * allowed to start while muted gets stopped again the moment sound comes
     * on, which is why this must happen at rest, at load time.
     */
    private fun unmuteByDefaultScript(): String = """
        <script id="__db_unmute_default">
        (function(){
          if (window.__dbUnmuteDefault) return;
          window.__dbUnmuteDefault = true;

          function open(v) {
            try {
              v.defaultMuted = false;
              v.muted = false;
              v.removeAttribute('muted');
              if (v.volume === 0) v.volume = 1;
            } catch (e) {}
          }
          function sweep() {
            var vs = document.querySelectorAll('video');
            for (var i = 0; i < vs.length; i++) {
              if (vs[i].__dbSound) continue;
              vs[i].__dbSound = true;
              open(vs[i]);
            }
          }
          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', sweep);
          } else {
            sweep();
          }
          // Injected cards arrive after this script; catch their videos too.
          try {
            new MutationObserver(function(muts) {
              for (var i = 0; i < muts.length; i++) {
                if (muts[i].addedNodes && muts[i].addedNodes.length) {
                  sweep();
                  return;
                }
              }
            }).observe(document.documentElement, {childList:true, subtree:true});
          } catch (e) {}
        })();
        </script>
    """.trimIndent()

    /** Basic page served when there is literally nothing saved. */
    private fun offlineFallbackPage(): WebResourceResponse {
        val html = "<!DOCTYPE html><html lang=\"en\"><head>" +
            "<meta charset=\"utf-8\"><meta name=\"viewport\" " +
            "content=\"width=device-width,initial-scale=1\">" +
            "<style>body{margin:0;background:#18191a;color:#e4e6eb;" +
            "font-family:sans-serif;display:flex;align-items:center;" +
            "justify-content:center;height:100vh;text-align:center}" +
            "</style></head><body><div><h2>No saved content</h2>" +
            "<p>Nothing has been downloaded for offline yet.</p>" +
            "<p>Open Facebook with a connection first.</p></div>" +
            "</body></html>"
        return WebResourceResponse("text/html", "utf-8", 200, "OK",
            mapOf("Cache-Control" to "no-store"), html.byteInputStream())
    }
}

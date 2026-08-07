package com.dustbook.app.offline

/**
 * Puts the saved cards into the stored Facebook document at BUILD time.
 *
 * Until now, delivery worked like this: the stored page was served with a
 * runtime script appended, and that script moved the saved cards into the
 * feed container when the page ran. Every step of that chain could fail
 * silently on a real phone - one bad character inside one card used to
 * kill the whole script, and the container lookup depended on whatever
 * markup happened to be stored - and every failure looked identical to
 * the user: only the handful of server-rendered posts in the stored
 * document appear, while the settings count (read from the store) climbs.
 *
 * The cards are now composed into the document in Kotlin, before it is
 * ever served. Nothing at all has to run on the page for content to be
 * there: if every script on the device died, the saved posts still
 * render, because they ARE the document. The small script at the bottom
 * only restores the scroll position and reports it back - a nicety whose
 * failure costs nothing.
 *
 * Placement, against the captured structure
 *
 *     MScreen (screen root)
 *       +-- header  (fixed-container top: logo bar + tab row)
 *       +-- vscroller (Facebook's JS-driven virtual list:
 *             composer, stories tray, then stale server-rendered posts)
 *
 *  1. the composer and the stories tray are MOVED OUT of the old scroller
 *     into the normal document flow, right after the header, so the
 *     offline home screen has the same pieces the online one has - proven
 *     against the user's own side-by-side screenshots, where both were
 *     missing because the whole scroller had to hide;
 *  2. the saved cards go in right after them, as the scroller's previous
 *     sibling - the same parent BY CONSTRUCTION, so the sibling rule that
 *     hides the stale scroller can never miss it;
 *  3. the old scroller - now just stale posts plus any junk that must not
 *     resurface (a floating tab row, a condemned ad) - hides whole, by
 *     name;
 *  4. an offline layout reset un-clips the screen root: on the live site
 *     scrolling belongs to the scroller, whose CSS keeps the screen root
 *     at viewport height with overflow hidden, so a static block inside
 *     that container scrolls jerkily or not at all (the user's "scroll
 *     down smoothly hoi na, scrolling up hotei chai na"). With the
 *     scroller hidden and the content in normal flow, html/body/MScreen
 *     must behave like an ordinary page - and they do, by !important, no
 *     matter what rules the stored stylesheet carries;
 *  5. the fixed header stays fixed, exactly as online. Facebook offsets
 *     its first scroller child by the header's own height (the margin
 *     every capture shows); that same number pads our first in-flow
 *     block, so nothing slips underneath it.
 *
 * The junk decisions in step 3 reuse SectionVault's own signatures
 * verbatim (ad mark, tab-row labels, story links) - one definition, no
 * drift.
 *
 * Two placements were tried in front of users and are recorded here so
 * they are never retried:
 *
 *  - an ALTERNATING regex over both container markers matched the screen
 *    root and then hid EVERY following sibling - Facebook's own header
 *    was simply gone offline;
 *  - inserting the cards as the screen root's FIRST child (v5.2.6) kept
 *    the header alive but parked it after every saved card, so the tab
 *    row effectively did not exist, and the cards still sat inside the
 *    container whose CSS owned scrolling. The user report: "nav buttons
 *    gulai nai, story's o nai, scroll up hotei chai na".
 */
object PageAssembly {

    /**
     * Active markup that must not be replayed from a stored card.
     *
     * The old runtime injected cards via `innerHTML`, and scripts assigned
     * through innerHTML never execute. Composing cards into a document
     * WOULD execute them, changing behaviour and inviting failure - so
     * they are cut. Nothing a post needs to be readable is inside a
     * script tag. <base> would rewrite every relative URL on the page.
     */
    private val SCRIPT_BLOCK =
        Regex("<script\\b[^>]*>[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE)
    private val SCRIPT_SELF =
        Regex("<script\\b[^>]*/>", RegexOption.IGNORE_CASE)
    private val BASE_TAG =
        Regex("<base\\b[^>]*>", RegexOption.IGNORE_CASE)

    fun sanitize(card: String): String =
        card.replace(SCRIPT_BLOCK, "")
            .replace(SCRIPT_SELF, "")
            .replace(BASE_TAG, "")

    /**
     * The old scroller, hidden whole. Its only remaining contents - stale
     * posts and what must never resurface - vanish with it while the
     * header, the moved chrome and the saved cards keep their places.
     */
    private const val HIDE_SCROLLER =
        "<style id=\"__db_hide_old\">#__db_cards~[data-type=\"vscroller\"]" +
            "{display:none!important}</style>"

    /**
     * Everything past the cards inside the same scroller. Kept for the
     * documents whose only container IS the scroller: there the stale
     * children are its siblings, exactly as before.
     */
    private const val HIDE_OLD =
        "<style id=\"__db_hide_old\">#__db_cards~*{display:none!important}</style>"

    /**
     * The offline layout reset, screen-root branch.
     *
     * On the live m.facebook.com the screen root is viewport-sized with
     * clipped overflow and the scroller inside it owns scrolling. A static
     * block inside that container cannot scroll natively - which offline
     * read as jerky half-scrolls that would not go back up. Content now
     * sits in normal document flow, so the ancestors on its path must be
     * ordinary: heights auto, overflow visible, positioning static. Every
     * declaration is !important so it wins against whatever the stored
     * stylesheet says about these elements; failing open would mean a page
     * that shows only its first screenful of saved posts.
     */
    private const val RESET_SCREEN_ROOT =
        "<style id=\"__db_layout_reset\">" +
            "html,body{height:auto!important;min-height:0!important;" +
            "overflow:visible!important}" +
            "[data-mcomponent=\"MScreen\"]{position:static!important;" +
            "top:auto!important;right:auto!important;bottom:auto!important;" +
            "left:auto!important;width:auto!important;height:auto!important;" +
            "min-height:0!important;max-height:none!important;" +
            "overflow:visible!important;transform:none!important}</style>"

    /**
     * The tame half of the reset, for the fallback branches: letting the
     * document itself scroll never hurts, and it is all those branches
     * can safely assume.
     */
    private const val RESET_GENERAL =
        "<style id=\"__db_layout_reset\">" +
            "html,body{height:auto!important;min-height:0!important;" +
            "overflow:visible!important}</style>"

    /** Screen root / feed container in m.facebook.com markup, by earliest match. */
    private val CONTAINER = Regex(
        "<div\\b[^>]*(?:data-type=[\"']vscroller[\"']" +
            "|data-mcomponent=[\"']MScreen[\"'])[^>]*>",
        RegexOption.IGNORE_CASE
    )

    /** Just the scroller test, for choosing which branch applies. */
    private val VSCROLLER = Regex(
        "data-type=[\"']vscroller[\"']",
        RegexOption.IGNORE_CASE
    )

    /** The scroller's own opening tag, for locating it exactly. */
    private val VSCROLLER_TAG = Regex(
        "<div\\b[^>]*data-type=[\"']vscroller[\"'][^>]*>",
        RegexOption.IGNORE_CASE
    )

    private val SCREEN_ROOT = Regex(
        "data-mcomponent=[\"']MScreen[\"']",
        RegexOption.IGNORE_CASE
    )

    private val BODY = Regex("<body\\b[^>]*>", RegexOption.IGNORE_CASE)

    /**
     * Top-level scanner for the scroller's children: real <div> open and
     * close tags, with comments and whole <script> blocks consumed first so
     * a stray tag inside one of them can never bend the depth count either
     * way. This is the same counting idea OfflineCapture.removeTag relies
     * on: a lazy match would stop at the first nested </div> and split a
     * unit in half.
     */
    private val TOKEN = Regex(
        "<!--[\\s\\S]*?-->|<script\\b[^>]*>[\\s\\S]*?</script\\s*>" +
            "|<div\\b[^>]*>|</div\\s*>",
        RegexOption.IGNORE_CASE
    )
    private val DIV_OPEN = Regex("^<div\\b", RegexOption.IGNORE_CASE)
    private val DIV_CLOSE = Regex("^</div\\b", RegexOption.IGNORE_CASE)

    /** A scroller child's own opening tag, anchored at the child's start. */
    private val FIRST_TAG = Regex("^<div\\b[^>]*>", RegexOption.IGNORE_CASE)

    /**
     * The virtual-list offset Facebook stamps on each scroller child. The
     * first child's value equals the pinned header's height: content below
     * a fixed element has to start that far down.
     */
    private val MARGIN = Regex("margin-top\\s*:\\s*(\\d+)px", RegexOption.IGNORE_CASE)
    private val MARGIN_STRIP =
        Regex("margin-top\\s*:\\s*\\d+px;?\\s*", RegexOption.IGNORE_CASE)

    /**
     * What makes a scroller child a post, not chrome: a permalink or one of
     * the identity attributes OfflineCapture.idOf() reads. Anything without
     * one - the composer, the stories tray - is chrome worth moving out.
     */
    private val POST_SIG = Regex(
        "story_fbid|/posts/|/videos/|/reel/|data-tracking-duration-id" +
            "|data-video-id|data-successful-render-id|data-comp-id",
        RegexOption.IGNORE_CASE
    )

    /** Sanity bounds: chrome moves are the first few units, never the feed. */
    private const val MAX_CHROME_UNITS = 6
    private const val MAX_CHROME_BYTES = 300 * 1024

    /** The saved cards as one block, ready to sit inside the document. */
    fun holderHtml(cards: List<String>): String {
        val body = cards.joinToString("\n") { sanitize(it) }
        return "<div id=\"__db_cards\" data-db-cards=\"1\">\n$body\n</div>"
    }

    /** One scroller child as (start, end) offsets of its outer element. */
    private data class Child(val start: Int, val end: Int)

    /**
     * The scroller's direct <div> children, located from just past its
     * opening tag. Depth counting, so nested divs inside a unit are never
     * mistaken for siblings; script and comment regions are consumed whole
     * by the tokenizer first.
     */
    private fun topLevelChildren(doc: String, from: Int): List<Child> {
        val out = ArrayList<Child>()
        var depth = 0
        var childStart = -1
        var m = TOKEN.find(doc, from)
        while (m != null) {
            val t = m.value
            when {
                DIV_CLOSE.containsMatchIn(t) -> {
                    if (depth == 0) return out      // the scroller's own close
                    depth--
                    if (depth == 0 && childStart >= 0) {
                        out.add(Child(childStart, m.range.last + 1))
                        childStart = -1
                    }
                }
                DIV_OPEN.containsMatchIn(t) -> {
                    if (depth == 0) childStart = m.range.first
                    depth++
                }
                // comments and scripts match TOKEN first and are skipped
            }
            m = m.next()
        }
        return out
    }

    /** Whether one scroller child moves out (chrome), stays hidden (junk), */
    /** or ends the move (the first real post). */
    private const val MOVE = 0
    private const val LEAVE = 1
    private const val STOP = 2

    private fun classify(slice: String): Int {
        // A card an ad blocker had already condemned when the capture read
        // it: the mark is part of the saved outerHTML, so it stays hidden
        // with the scroller rather than resurfacing between real posts.
        if (slice.contains(SectionVault.JUNK_AD_TAG, ignoreCase = true)) {
            return LEAVE
        }
        // Facebook's floating tab row, caught mid-scroller offline before.
        // Several tab-labelled buttons and no story link - SectionVault's
        // exact signature, so the page and the store agree on what it is.
        val hasStoryLink = SectionVault.JUNK_STORY_LINK.containsMatchIn(slice)
        var labels = 0
        val it = SectionVault.JUNK_TAB_LABEL.findAll(slice).iterator()
        while (it.hasNext()) {
            it.next()
            if (++labels >= 2) break
        }
        if (labels >= 2 && !hasStoryLink) return LEAVE
        if (POST_SIG.containsMatchIn(slice)) return STOP
        return MOVE
    }

    /**
     * The virtual-list margin lives only on a child's own opening tag, so
     * that is the only place it is stripped: a moved unit rejoins normal
     * flow without its old absolute offset, while everything it contains
     * keeps Facebook's styling untouched.
     */
    private fun stripVirtualMargin(slice: String): String {
        val tag = FIRST_TAG.find(slice) ?: return slice
        val stripped = tag.value.replace(MARGIN_STRIP, "")
        return stripped + slice.substring(tag.value.length)
    }

    /** The header offset Facebook itself used on its first scroller child. */
    private fun stolenOffset(slice: String): Int? {
        val tag = FIRST_TAG.find(slice) ?: return null
        val m = MARGIN.find(tag.value) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /**
     * The stored Facebook document with the saved cards composed in, per
     * the placement rules above. [cards] must be exactly the items the
     * count reports, so the screen and the number agree by construction.
     */
    fun compose(doc: String, cards: List<String>): String {
        if (cards.isEmpty()) return doc
        val holder = holderHtml(cards)

        CONTAINER.find(doc)?.let { m ->
            val at = m.range.last + 1
            val joinedScreenRoot =
                SCREEN_ROOT.containsMatchIn(m.value) &&
                    VSCROLLER.containsMatchIn(doc.substring(at))

            if (joinedScreenRoot) {
                // Home shape: header, then the scroller with chrome on top
                // and stale posts below. Reels documents reach the same
                // branch and simply have nothing to move, because their
                // first scroller child is already a post.
                val rest = doc.substring(at)
                val vs = VSCROLLER_TAG.find(rest) ?: return@let
                val vsStart = at + vs.range.first
                val vsOpenEnd = at + vs.range.last + 1

                val children = topLevelChildren(doc, vsOpenEnd)
                val moved = ArrayList<String>()
                var offset: Int? = null
                var bytes = 0
                if (children.isNotEmpty()) {
                    offset = stolenOffset(
                        doc.substring(children[0].start, children[0].end))
                }

                // Rebuild the scroller with the moved units excised. Junk
                // (a floating tab row, a condemned ad) and everything from
                // the first real post onward stay exactly where they were,
                // hidden with the scroller.
                var cursor = vsOpenEnd
                var inner = ""
                for (c in children) {
                    if (moved.size >= MAX_CHROME_UNITS ||
                        bytes >= MAX_CHROME_BYTES) break
                    val slice = doc.substring(c.start, c.end)
                    val kind = classify(slice)
                    if (kind == STOP) break
                    if (kind == MOVE) {
                        inner += doc.substring(cursor, c.start)
                        cursor = c.end
                        moved.add(stripVirtualMargin(slice))
                        bytes += slice.length
                    }
                    // LEAVE: junk stays exactly where it was, hidden with
                    // the scroller it belongs to.
                }

                val pad = if (offset != null && offset > 0) "${offset}px" else null
                val padCss = "<style id=\"__db_top_pad\">" +
                    (if (moved.isNotEmpty()) "#__db_chrome" else "#__db_cards") +
                    "{padding-top:" + (pad ?: "0") + "!important}</style>"
                val chrome =
                    if (moved.isEmpty()) ""
                    else "<div id=\"__db_chrome\"" +
                        (pad?.let { " style=\"padding-top:$it\"" } ?: "") + ">" +
                        moved.joinToString("\n") +
                        "</div>"

                return doc.substring(0, vsStart) +
                    RESET_SCREEN_ROOT + HIDE_SCROLLER +
                    (if (pad != null) padCss else "") +
                    chrome + holder +
                    doc.substring(vsStart, vsOpenEnd) + inner +
                    doc.substring(cursor)
            }

            // Bare scroller: the historical sibling rule, plus the general
            // reset - the only safe assumption without a screen root.
            return doc.substring(0, at) + RESET_GENERAL + HIDE_OLD + holder +
                doc.substring(at)
        }
        BODY.find(doc)?.let { m ->
            val at = m.range.last + 1
            return doc.substring(0, at) + RESET_GENERAL + holder +
                doc.substring(at)
        }
        return doc + RESET_GENERAL + holder
    }

    /**
     * Restores where the user was, and reports position changes back so
     * the next session resumes there. Content never depends on this: it
     * is appended after a document that already shows everything.
     *
     * @param resumeId "SCROLL:<px>" for the feed scroller, otherwise a
     *                 reel or story id to bring into view.
     */
    fun resumeScript(resumeId: String?): String {
        val main = """
        (function(){
          if (window.__dbOfflineResume) return;
          window.__dbOfflineResume = true;

          function holder() {
            return document.querySelector('[data-db-cards]');
          }
          function scroller() {
            var h = holder();
            if (!h) return document.scrollingElement;
            var c = h.closest ? h.closest('[data-type="vscroller"]') : null;
            return c || document.scrollingElement;
          }

          function doResume() {
            var id = window.__dbResumeId;
            if (!id) return;
            if (id.indexOf('SCROLL:') === 0) {
              var px = parseInt(id.slice(7), 10);
              if (px > 0) {
                tryAt([300, 800, 1500, 2500], function(){
                  var c = scroller();
                  if (c) c.scrollTop = px;
                });
              }
              return;
            }
            // Reel/story: bring the matching card into view. Layout shifts
            // as posters and video frames decode, so try several times.
            tryAt([300, 800, 1500, 2500], function(){
              var h = holder();
              if (!h) return;
              var cards = h.querySelectorAll(
                '[data-video-id],[data-story-id],[data-offline-id]');
              for (var i = 0; i < cards.length; i++) {
                var c = cards[i];
                var cid = c.getAttribute('data-video-id') ||
                          c.getAttribute('data-story-id') ||
                          c.getAttribute('data-offline-id');
                if (cid === id) {
                  c.scrollIntoView({block: 'center', behavior: 'instant'});
                  return;
                }
              }
            });
          }

          function tryAt(delays, fn) {
            fn();
            for (var i = 0; i < delays.length; i++) {
              (function(d){
                setTimeout(function(){
                  if (holder()) fn();
                }, d);
              })(delays[i]);
            }
          }

          doResume();

          // Track position so the next session resumes here.
          var __lastReport = 0;
          function reportCurrent() {
            var now = Date.now();
            if (now - __lastReport < 2000) return;
            __lastReport = now;
            var box = scroller();
            var h = holder();
            if (!box || !h) return;
            var mid = (box.clientHeight || window.innerHeight || 0) / 2;
            var cards = h.querySelectorAll('[data-video-id],[data-story-id]');
            for (var i = 0; i < cards.length; i++) {
              var r = cards[i].getBoundingClientRect();
              if (r.top < mid && r.bottom > mid) {
                var vid = cards[i].getAttribute('data-video-id') ||
                          cards[i].getAttribute('data-story-id');
                if (vid && window.FBPro && FBPro.reportPosition) {
                  try { FBPro.reportPosition('reel', vid); } catch (e) {}
                }
                return;
              }
            }
            if (box.scrollTop > 0 && window.FBPro && FBPro.reportPosition) {
              try { FBPro.reportPosition('feed', String(box.scrollTop)); } catch(e){}
            }
          }
          var s = scroller();
          if (s && s.addEventListener) {
            s.addEventListener('scroll', function(){
              if (window.__dbResumeTimer) clearTimeout(window.__dbResumeTimer);
              window.__dbResumeTimer = setTimeout(reportCurrent, 600);
            });
          }
        })();
        """.trimIndent()

        // Set the resume target outside the template so tests can eval the
        // raw source without an unresolved Kotlin template variable.
        if (resumeId != null) {
            return "window.__dbResumeId=" + "\"$resumeId\";\n" + main
        }
        return main
    }
}

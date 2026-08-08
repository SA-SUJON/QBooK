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
 *  5. the fixed header stays fixed, exactly as online. Facebook stamps
 *     each virtual child an ABSOLUTE y as its margin (composer at 104,
 *     tray at 160 = 104 + 56). Stripped and rebuilt as relative gaps
 *     (own offset - previous offset - previous height), those same
 *     numbers place the moved chrome EXACTLY where the online layout
 *     has it, down to the pixel - no estimated padding, no dead band,
 *     no overlap with the header.
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
 *
 * v5.2.7's reset then taught us the rest of the lesson, from the user's
 * own screenshot: captured documents are STACKS of screens - Facebook
 * keeps previously visited screens in the same DOM (the capture carries
 * `data-screen-keys="57,56,55"`), and un-clipping them all floated a
 * stale post screen above the home header. And the tab row is not part
 * of the header on this device at all: it floats INSIDE the scroller as
 * a badge-carrying unit - hiding the scroller therefore hid navigation.
 * So now: the screen that owns the scroller is marked and kept, every
 * OTHER screen and every stale scroller hides whole, and a tab-row unit
 * found in the scroller is moved out with the composer and stories tray -
 * before them, because navigation goes first.
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
     * Every stale scroller anywhere in the document, and every stacked
     * screen except the one we composed into - hidden whole. Captured
     * home documents can carry MORE than one screen (Facebook keeps prior
     * screens in one DOM for back-navigation); before they were excluded
     * by name, a stale post screen simply rendered above the home header,
     * which is exactly what the user screenshotted. The kept screen is
     * marked `data-db-active` at compose time, so the rule needs no
     * modern selector support.
     */
    private const val HIDE_SCREEN_ROOT =
        "<style id=\"__db_hide_old\">[data-type=\"vscroller\"]" +
            "{display:none!important}" +
            "[data-mcomponent=\"MScreen\"]:not([data-db-active])" +
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
     *
     * The last rules keep the document itself scrollable. A moved unit
     * rejoins normal flow COMPLETELY: the floating tab row can carry the
     * same fixed-container class the header pins itself with, and dropped
     * between saved cards it would float above the logo bar offline. And
     * once Facebook's snap pager is hidden, the body's own scroller must
     * NOT inherit anything scroll-shaped from the site stylesheet: a
     * mandatory scroll-snap without snap areas snaps back to the start
     * on every drag - the reels screen felt exactly like "scrolling
     * hoina", the next reel visible for a moment and gone. Touch itself
     * must also always reach the scroller: a card surface that captured
     * gestures for the dead pager eats the drag before it scrolls.
     * The gesture rules stop at CARDS: the chrome row (tab bar, composer,
     * stories tray) is a live widget, and blanking snap-align under it
     * would take the tray's own horizontal snap away. Both properties
     * are gesture-only - they never change how anything looks.
     */
    private const val RESET_SCREEN_ROOT =
        "<style id=\"__db_layout_reset\">" +
            "html,body{height:auto!important;min-height:0!important;" +
            "overflow:visible!important;scroll-snap-type:none!important;" +
            "touch-action:manipulation!important}" +
            "#screen-root{position:static!important;height:auto!important;" +
            "min-height:0!important;max-height:none!important;" +
            "overflow:visible!important}" +
            "[data-mcomponent=\"MScreen\"]{position:static!important;" +
            "top:auto!important;right:auto!important;bottom:auto!important;" +
            "left:auto!important;width:auto!important;height:auto!important;" +
            "min-height:0!important;max-height:none!important;" +
            "overflow:visible!important;transform:none!important}" +
            "#__db_chrome>*{position:static!important;top:auto!important;" +
            "right:auto!important;bottom:auto!important;left:auto!important;" +
            "z-index:auto!important;transform:none!important}" +
            "#__db_cards *{scroll-snap-align:none!important;" +
            "touch-action:manipulation!important}</style>"

    /**
     * One reel per gesture, rebuilt on the body scroller.
     *
     * Snap mode history, all proven on the user's own device:
     *
     *  - v5.2.10 killed scroll-snap document-wide to heal a bounce-back:
     *    with the snap pager hidden, a fling then ran through three or
     *    four reels at once ("ekbare 3/4 ta kora jacche, ekta ekta kore
     *    ashe na").
     *  - v5.2.11 re-armed it as MANDATORY - and the user could not
     *    scroll at all. Mandatory assigns the scroller to the nearest
     *    snap area at every moment, and Chrome re-snaps after every
     *    layout change; with twenty-nine reel videos settling their
     *    sizes one after another, every drag was yanked straight back.
     *    It was the second time a mandatory snap trapped this app
     *    (v5.2.9: Facebook's own, felt identical).
     *
     *  So proximity instead: it can never hold the scroller hostage -
     *    an in-between rest position is perfectly legal - while slowing
     *    down near a reel boundary still settles on it. One property
     *    from that attempt is deliberately gone again: scroll-snap-stop
     *    survived the mandatory->proximity change untouched, the user
     *    still could not scroll, and it is the remaining member of the
     *    trap family - on mobile Chrome it routinely makes even gentle
     *    drags return to the current area. It is out, honestly recorded
     *    as a suspected - not proven - cause, because gesture physics
     *    cannot be run in jsdom and this device has falsified two
     *    armchair snap theories already.
     *
     *  Round 14 - the confession that rewrites the paragraph above:
     *    EVERY "can't scroll" failure on this list (v5.2.11 mandatory,
     *    v5.2.12 proximity + stop:always) was measured while #screen-root
     *    still clipped the whole page to one viewport of overflow:hidden.
     *    A page that cannot scroll cannot demonstrate a trap either:
     *    scroll-snap-stop:always was NEVER tested in isolation. The
     *    clip fix (found on-device with a diagnostic overlay) is what
     *    made reels scrollable at all - proximity alone then showed its
     *    one honest weakness: a fast fling sails through two or three
     *    snap areas ("scroll korle 2/3 ta reels scroll hoye jai").
     *
     *    The round-13/16 answer was a JS gesture corrector
     *    (touchstart/touchend, settle, then scrollIntoView to start+or-1).
     *    The user rejected it as artificial, twice - verbatim: "halka
     *    scroll korle auto scroll hoye jacche, eita to real hoilo na",
     *    and they are right: any correction that fires AFTER the finger
     *    lifts is visibly fake. It is fully removed; no JS touches the
     *    gesture path anywhere now.
     *
     *    What remains is the one property CSS built exactly for this:
     *    scroll-snap-stop:always refuses a fling passage THROUGH a reel
     *    - native, instant, no animation after the fact. Re-trialed in
     *    v5.2.14, in isolation, with the clip long gone.
     *
     *    Device verdict on v5.2.14 (user, verbatim): scroll works - the
     *    trap is gone for good - but "one swipe fast - 2/3 ta reels
     *    eksathe scroll hoi". So stop:always is PROVEN INERT on this
     *    WebView: it neither traps nor caps. With it answerless, CSS
     *    has exactly one lever left, and it is this one.
     *
     *  Round 15: mandatory, retrialed in isolation (REELS-SCROLL-NOTES
     *    item 2 - the last CSS mode). The v5.2.11 "user could not scroll
     *    at all" verdict on mandatory was itself measured WITH the
     *    #screen-root clip active - never a clean test either. Now the
     *    clip is gone and the offline reels page is fully static (every
     *    reel's box is settled at serve time; nothing re-flows), which
     *    removes mandatory's one pathology - re-snapping at every
     *    layout shift mid-drag. Outcome branches, in advance:
     *      - One swipe = one reel at every speed -> closed, for good.
     *      - "Scroll e cholena" returns -> mandatory is PROVEN dead on
     *        this WebView; revert to proximity+stop:always (scrolling
     *        but skipping) and the only road left is a custom pager -
     *        which the user has rejected as artificial, so that road
     *        requires their explicit consent first.
     *
     * As before: every saved card is one snap area aligned to its start,
     * card descendants keep snap-align:none from the reset, and
     * snap-margin-top carries the offset Facebook stamped for its own
     * pinned bar (__PAD__), so a reel locks just below the bar instead
     * of sliding underneath it. All properties we add are gesture-only.
     */
    private const val REELS_SNAP_CSS =
        "<style id=\"__db_reels_snap\">" +
            "html,body{scroll-snap-type:y mandatory!important}" +
            "#__db_cards>*{scroll-snap-align:start!important;" +
            "scroll-snap-stop:always!important;" +
            "scroll-snap-margin-top:__PAD__px!important}</style>"

    /** The snap opt-in with [padTop] - Facebook's own stamped bar offset. */
    private fun reelsSnapCss(padTop: Int): String =
        REELS_SNAP_CSS.replace("__PAD__", padTop.toString())

    /**
     * The tame half of the reset, for the fallback branches: letting the
     * document itself scroll never hurts, and it is all those branches
     * can safely assume.
     */
    private const val RESET_GENERAL =
        "<style id=\"__db_layout_reset\">" +
            "html,body{height:auto!important;min-height:0!important;" +
            "overflow:visible!important;touch-action:manipulation!important}" +
            "#screen-root{position:static!important;height:auto!important;" +
            "min-height:0!important;max-height:none!important;" +
            "overflow:visible!important}" +
            "#__db_cards *{touch-action:manipulation!important}</style>"

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

    /** A screen's own opening tag - the last one before the scroller is ITS screen. */
    private val MSCREEN_TAG = Regex(
        "<div\\b[^>]*data-mcomponent=[\"']MScreen[\"'][^>]*>",
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
    /**
     * "Content begins here", proven by URLs ALONE.
     *
     * A stale post below the chrome always carries its own permalink
     * (story_fbid, /posts/, /videos/, /reel/). Nothing else may stop the
     * chrome walk: the generic tracking attributes Facebook stamps on
     * every rendered container - data-tracking-duration-id,
     * data-successful-render-id, data-comp-id, data-video-id - used to be
     * in this signature, so a composer or stories tray carrying them read
     * as a "post". One such attribute on the FIRST chrome child stopped
     * the walk before it began, and the offline home lost its tab row,
     * its composer and its stories tray in one stroke - the round-9
     * report, proven in jsdom against the verbatim shipped code.
     */
    private val POST_LINK = Regex(
        "story_fbid|/posts/|/videos/|/reel/",
        RegexOption.IGNORE_CASE
    )

    /** A unit's own declared height, stamped next to its virtual margin. */
    private val HEIGHT = Regex(
        "height\\s*:\\s*(\\d+)px",
        RegexOption.IGNORE_CASE
    )

    /** Sanity bounds: chrome moves are the first few units, never the feed. */
    private const val MAX_CHROME_UNITS = 6
    private const val MAX_CHROME_BYTES = 300 * 1024

    /** The saved cards as one block, ready to sit inside the document. */
    fun holderHtml(cards: List<String>, snap: Boolean = false): String {
        // Every stored reel is stamped preload="auto" at capture so it
        // can start instantly. On a feed of a few clips that is free -
        // but a full screen of thirty reels all buffering at once keeps
        // the decoder and the main thread permanently busy, and a busy
        // page feels exactly like "scroll hoina". Online, only the
        // current reel and its neighbour hold data; the player fetches
        // the rest when the user arrives. Match that: past the first
        // few, drop to metadata (headers only) - a tap still starts
        // playback at once, the page just breathes. Read-time rewrite,
        // so libraries saved by older builds benefit too.
        val body = cards.mapIndexed { i, c ->
            val clean = sanitize(c)
            if (snap && i >= SNAP_PRELOAD_FIRST) clean.replace(
                "preload=\"auto\"", "preload=\"metadata\"") else clean
        }.joinToString("\n")
        // With snap on (the reels page), this zero-height sentinel is the
        // snap area for scroll position 0: after any relayout the snapper
        // knows the very top is a legal rest, so the page can never be
        // dragged down to the first card on its own. Invisible by size,
        // and without a snap container its align property is inert.
        val sentinel = if (snap)
            "<div id=\"__db_snap_t\" style=\"height:0;" +
                "scroll-snap-align:start\"></div>\n" else ""
        return "<div id=\"__db_cards\" data-db-cards=\"1\">\n" +
            sentinel + body + "\n</div>"
    }

    /** How many leading reels keep preload="auto" (current + next + slack). */
    private const val SNAP_PRELOAD_FIRST = 3

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

    /** What one scroller child is, for the move. */
    private const val MOVE = 0
    private const val LEAVE = 1
    private const val STOP = 2
    private const val MOVE_TAB = 3

    private fun classify(slice: String): Int {
        // A card an ad blocker had already condemned when the capture read
        // it: the mark is part of the saved outerHTML, so it stays hidden
        // with the scroller rather than resurfacing between real posts.
        if (slice.contains(SectionVault.JUNK_AD_TAG, ignoreCase = true)) {
            return LEAVE
        }
        // Facebook's floating tab row. On this device the tab row is not
        // part of the header at all - it floats INSIDE the scroller as a
        // badge-carrying unit, which is why hiding the scroller removed
        // navigation itself.
        //
        // The row is decided by two or more EXACT navigation labels
        // (Home, Friends, Chats, Reels, Notifications, Marketplace) and
        // by NOTHING else. Round 8 also demanded "no story link" - but
        // the real row is anchors, and /reel/ sits right there in it, so
        // that excuse refused the row and the post signature below then
        // stopped the whole walk: row, composer and tray all stayed
        // hidden (the round-9 screenshot report). The story-link guard
        // stays exactly one place: INSIDE the vault, where mistaking a
        // real post for junk would trash saved content. Here the greater
        // evil is losing navigation, and no post carries two exact nav
        // labels. (The row must still never be saved as a CARD - that
        // vault filter is unchanged.)
        var labels = 0
        val it = SectionVault.JUNK_TAB_LABEL.findAll(slice).iterator()
        while (it.hasNext()) {
            it.next()
            if (++labels >= 2) return LEAVE
        }
        if (POST_LINK.containsMatchIn(slice)) return STOP
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

    /** The absolute y Facebook stamped on THIS virtual child, if any. */
    private fun ownMargin(slice: String): Int? {
        val tag = FIRST_TAG.find(slice) ?: return null
        return MARGIN.find(tag.value)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** The child's own declared height, stamped next to the margin. */
    private fun ownHeight(slice: String): Int? {
        val tag = FIRST_TAG.find(slice) ?: return null
        return HEIGHT.find(tag.value)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Give a moved unit back one exact gap, on its own opening tag. */
    private fun withMargin(unit: String, gapPx: Int): String {
        val tag = FIRST_TAG.find(unit) ?: return unit
        val open = tag.value
        val newOpen = if (open.contains("style=\"")) {
            open.replaceFirst("style=\"", "style=\"margin-top:${gapPx}px;")
        } else {
            open.dropLast(1) + " style=\"margin-top:${gapPx}px\">"
        }
        return newOpen + unit.substring(open.length)
    }

    /**
     * The header offset Facebook itself stamped on a scroller child. The
     * fixed tab row can lead the scroller without one (it is out of flow),
     * so the offset comes from the first child that actually carries the
     * virtual-list margin.
     */
    private fun stolenOffset(doc: String, children: List<Child>): Int? {
        for (i in 0 until minOf(children.size, 3)) {
            val slice = doc.substring(children[i].start, children[i].end)
            val tag = FIRST_TAG.find(slice) ?: continue
            val m = MARGIN.find(tag.value) ?: continue
            return m.groupValues[1].toIntOrNull()
        }
        return null
    }

    /** Duplicate-spotter for recycled chrome: link set, else a text key. */
    private val HREF = Regex("href=[\"']([^\"'#]+)", RegexOption.IGNORE_CASE)
    private val TAG_STRIP = Regex("<[^>]+>", RegexOption.IGNORE_CASE)
    private val SPACE = Regex("\\s+", RegexOption.IGNORE_CASE)

    private fun chromeKey(slice: String): String {
        val hrefs = HREF.findAll(slice).map { it.groupValues[1] }
            .take(4).sorted().joinToString("|")
        if (hrefs.isNotEmpty()) return "L:" + hrefs
        val text = slice.replace(TAG_STRIP, " ").replace(SPACE, " ").trim()
        return "T:" + text.take(120)
    }

    /** The last screen opening tag before [pos] - the screen [pos] sits in. */
    private fun lastScreenTagBefore(doc: String, pos: Int): MatchResult? {
        var last: MatchResult? = null
        var m = MSCREEN_TAG.find(doc)
        while (m != null && m.range.first < pos) {
            last = m
            m = m.next()
        }
        return last
    }

    /**
     * The stored Facebook document with the saved cards composed in, per
     * the placement rules above. [cards] must be exactly the items the
     * count reports, so the screen and the number agree by construction.
     */
    /**
     * @param snap true only for the reels page: the body scroller keeps
     *             scroll-snap and every card is one snap area, so a fling
     *             lands exactly one reel further - never three or four.
     *             The feed must never snap, which is why it stays false
     *             everywhere else.
     */
    fun compose(doc: String, cards: List<String>, snap: Boolean = false): String {
        if (cards.isEmpty()) return doc
        val holder = holderHtml(cards, snap)

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

                // Mark the screen this scroller belongs to as the one to
                // keep; every other stacked screen (a stale post detail
                // view, say) hides whole. String surgery on the tag we
                // already matched, so nothing has to run on the page.
                var base = doc
                val tagM = lastScreenTagBefore(doc, vsStart)
                if (tagM != null) {
                    val tag = tagM.value
                    val pos = tagM.range.first
                    base = doc.substring(0, pos) +
                        tag.dropLast(1) + " data-db-active=\"1\">" +
                        doc.substring(pos + tag.length)
                }
                val shift = base.length - doc.length
                val vsStartB = vsStart + shift
                val vsOpenEndB = vsOpenEnd + shift

                val children = topLevelChildren(base, vsOpenEndB)
                val moved = ArrayList<Triple<String, Int?, Int?>>()
                val tabs = ArrayList<Triple<String, Int?, Int?>>()
                var bytes = 0

                // Rebuild the scroller with the moved units excised. A
                // condemned ad, and everything from the first real post
                // onward, stays exactly where it was, hidden with the
                // scroller.
                var cursor = vsOpenEndB
                var inner = ""
                val chromeSeen = HashSet<String>()
                for (c in children) {
                    if (moved.size + tabs.size >= MAX_CHROME_UNITS ||
                        bytes >= MAX_CHROME_BYTES) break
                    val slice = base.substring(c.start, c.end)
                    val kind = classify(slice)
                    if (kind == STOP) break
                    // Facebook's virtual list can hold a recycled copy of
                    // the same chrome unit; two identical trays moved out
                    // are exactly the user's "story er ta double asche".
                    // The first copy moves, later twins stay hidden here.
                    if ((kind == MOVE || kind == MOVE_TAB) &&
                        !chromeSeen.add(chromeKey(slice))) continue
                    if (kind == MOVE || kind == MOVE_TAB) {
                        inner += base.substring(cursor, c.start)
                        cursor = c.end
                        // Triple: unit without its virtual margin, the
                        // absolute offset it carried, its declared height.
                        val entry = Triple(stripVirtualMargin(slice),
                            ownMargin(slice), ownHeight(slice))
                        if (kind == MOVE_TAB) tabs.add(entry)
                        else moved.add(entry)
                        bytes += slice.length
                    }
                    // LEAVE: junk stays hidden with the scroller it belongs to.
                }

                // Facebook's margins are ABSOLUTE y offsets in the virtual
                // list (composer at 104, tray at 160 = 104 + 56), useless
                // as-is in normal flow. Turned into gaps they rebuild the
                // online layout exactly, with Facebook's own numbers:
                // each unit keeps (own offset - previous offset -
                // previous height) above itself, and the row lands under
                // the pinned header at Facebook's own first offset.
                val seq = tabs + moved
                val padTop = seq.firstOrNull()?.second ?: stolenOffset(base, children)
                val pad = if (padTop != null && padTop > 0) "${padTop}px" else null
                val parts = ArrayList<String>(seq.size)
                for (i in seq.indices) {
                    val own = seq[i].second
                    val gap = if (i == 0 || own == null) 0
                        else {
                            val prev = seq[i - 1]
                            if (prev.second == null) 0
                            else maxOf(0, own - prev.second!! - (prev.third ?: 0))
                        }
                    parts.add(if (gap > 0) withMargin(seq[i].first, gap)
                        else seq[i].first)
                }
                val padCss = "<style id=\"__db_top_pad\">" +
                    (if (seq.isNotEmpty()) "#__db_chrome" else "#__db_cards") +
                    "{padding-top:" + (pad ?: "0") + "!important}</style>"
                // The tab row is navigation: before the composer and tray.
                val chromeBody = parts.joinToString("\n")
                val chrome =
                    if (chromeBody.isEmpty()) ""
                    else "<div id=\"__db_chrome\"" +
                        (pad?.let { " style=\"padding-top:$it\"" } ?: "") + ">" +
                        chromeBody +
                        "</div>"

                return base.substring(0, vsStartB) +
                    RESET_SCREEN_ROOT + HIDE_SCREEN_ROOT +
                    (if (snap) reelsSnapCss(padTop ?: 0) else "") +
                    (if (pad != null) padCss else "") +
                    chrome + holder +
                    base.substring(vsStartB, vsOpenEndB) + inner +
                    base.substring(cursor)
            }

            // Bare scroller: the historical sibling rule, plus the general
            // reset - the only safe assumption without a screen root. Snap
            // must still be wired here: a reels document that falls into
            // this branch (no joined screen root) previously never got
            // reelsSnapCss at all, leaving whatever scroll-snap the stored
            // stylesheet carried untouched - the "scroll kora jai na" bug.
            return doc.substring(0, at) + RESET_GENERAL + HIDE_OLD +
                (if (snap) reelsSnapCss(0) else "") + holder +
                doc.substring(at)
        }
        BODY.find(doc)?.let { m ->
            val at = m.range.last + 1
            return doc.substring(0, at) + RESET_GENERAL +
                (if (snap) reelsSnapCss(0) else "") + holder +
                doc.substring(at)
        }
        return doc + RESET_GENERAL + (if (snap) reelsSnapCss(0) else "") + holder
    }

    /**
     * Restores where the user was, and reports position changes back so
     * the next session resumes there. Content never depends on this: it
     * is appended after a document that already shows everything.
     *
     * @param resumeId "SCROLL:<px>" for the feed scroller, otherwise a
     *                 reel or story id to bring into view.
     */
    fun resumeScript(resumeId: String?, section: String): String {
        val main = """
        (function(){
          if (window.__dbOfflineResume) return;
          window.__dbOfflineResume = true;

          // Which offline screen this copy of the script serves. The feed
          // reports nothing at all: home never resumes (round 11), and a
          // video post merely SCROLLED PAST on the home page used to be
          // written into the reels slot - hijacking the next real reels
          // resume (round-13 assist for "reels scroll hoina").
          var SEC = "__SEC__";

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
            if (SEC === 'feed') return;
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
                  // The write goes to THIS screen's slot. Hardcoding
                  // 'reel' here used to write story ids into the reels
                  // resume whenever the stories screen was scrolled.
                  var key = (SEC === 'stories') ? 'story' : 'reel';
                  try { FBPro.reportPosition(key, vid); } catch (e) {}
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

          // ---------------------------------------------------------------
          // The reels pager (round 15, user-consented custom pager).
          //
          // What CSS could not do on this WebView: cap a fling to one
          // reel. scroll-snap-stop is inert (v5.2.14 device verdict,
          // scrolled fine, still skipped 2/3), and mandatory only aligns
          // the rest position - it never limits how many areas a single
          // gesture crosses. So the gesture itself is handled here, the
          // same way the real reels apps do it:
          //
          //   - while the finger moves, the page follows it 1:1 (real
          //     direct-manipulation drag, native momentum OFF for reels)
          //   - on release there is exactly ONE commit: the nearest card,
          //     or one card in the flick direction, but NEVER more than
          //     one away from where the gesture began
          //
          // A tap moves less than the lock distance and is never eaten:
          // the play/pause tap bridge keeps its clicks. A horizontal-
          // dominant gesture is handed straight back to the native path.
          // snap CSS is removed at init so the browser cannot fight the
          // animation - but only AFTER its margin offset is read, and if
          // this script ever fails to run, the snap CSS stays as the
          // no-JS fallback. No post-hoc correction exists anywhere: the
          // commit animation is the gesture's own last half.
          if (SEC === 'reels') {
            var PAD = 0;
            var snapStyle = document.getElementById('__db_reels_snap');
            if (snapStyle) {
              var mp = /scroll-snap-margin-top:(\d+)px/.exec(
                snapStyle.textContent || '');
              if (mp) PAD = parseInt(mp[1], 10) || 0;
              try { snapStyle.parentNode.removeChild(snapStyle); } catch (e) {}
            }

            var ANIM_MS = 260;    // commit animation; one per gesture
            var DRAG_LOCK = 7;    // px before a touch counts as a drag
            var DIST_FRAC = 0.15; // of card height commits on release
            var FLING_V = 0.45;   // px/ms finger speed that always commits
            var TAP_SLOP = 16;    // a tapped finger still wobbles this much
                                // (the round-20 "pause hoina" lesson)

            var active = false, locked = false;
            var tapX0 = 0, tapY0 = 0; // anchored at touchstart, never moved
            var peak = 0;             // furthest the finger ever wandered
            var startY = 0, startX = 0, startTop = 0, startIdx = 0;
            var loTop = 0, hiTop = -1;  // drag boundaries, -1 = no cap yet
            var lastDy = 0;             // finger travel since the lock
            var trail = [];       // recent [time, y] for velocity
            var raf = 0;

            function reelCards() {
              var h = holder(); if (!h) return [];
              var out = [], kids = h.children;
              for (var i = 0; i < kids.length; i++) {
                if (kids[i].id !== '__db_snap_t') out.push(kids[i]);
              }
              return out;
            }
            function nearestIndex() {
              var list = reelCards(), box = scroller();
              if (!box) return 0;
              var st = box.scrollTop, best = 0, bestD = Infinity;
              for (var i = 0; i < list.length; i++) {
                var top = st + list[i].getBoundingClientRect().top - PAD;
                var d = Math.abs(top - st);
                if (d < bestD) { bestD = d; best = i; }
              }
              return best;
            }
            function targetTop(i) {
              var list = reelCards(), box = scroller();
              if (!list[i] || !box) return null;
              var t = box.scrollTop +
                      list[i].getBoundingClientRect().top - PAD;
              return Math.max(0, Math.min(t,
                Math.max(0, box.scrollHeight - (box.clientHeight || 0)) - 1));
            }
            function cancelAnim() {
              if (raf) { cancelAnimationFrame(raf); raf = 0; }
            }
            function commitTo(i) {
              var t = targetTop(i); if (t == null) return;
              var box = scroller(); if (!box) return;
              cancelAnim();
              var from = box.scrollTop, d = t - from;
              if (Math.abs(d) < 2) { box.scrollTop = t; return; }
              var t0 = Date.now();
              var step = function() {
                var k = Math.min(1, (Date.now() - t0) / ANIM_MS);
                var e = 1 - Math.pow(1 - k, 3);  // ease-out cubic
                box.scrollTop = Math.round(from + d * e);
                if (k < 1) raf = requestAnimationFrame(step);
                else raf = 0;
              };
              raf = requestAnimationFrame(step);
            }

            document.addEventListener('touchstart', function(ev) {
              if (!ev.touches || ev.touches.length !== 1) return;
              if (!(ev.target && ev.target.closest &&
                    ev.target.closest('[data-db-cards]'))) return;
              cancelAnim();
              active = true; locked = false;
              var box = scroller();
              startTop = box ? box.scrollTop : 0;
              startIdx = nearestIndex();
              startY = ev.touches[0].clientY;
              startX = ev.touches[0].clientX;
              tapX0 = startX; tapY0 = startY; peak = 0;
              trail = [[Date.now(), startY]];
            }, {passive: true});

            document.addEventListener('touchmove', function(ev) {
              if (!active || !ev.touches || ev.touches.length !== 1) return;
              var y = ev.touches[0].clientY, x = ev.touches[0].clientX;
              var tdy = Math.abs(y - tapY0), tdx = Math.abs(x - tapX0);
              if (tdy > peak) peak = tdy;
              if (tdx > peak) peak = tdx;
              var dy = y - startY, dx = x - startX;
              var box = scroller(); if (!box) { active = false; return; }
              if (!locked) {
                if (Math.abs(dy) < DRAG_LOCK && Math.abs(dx) < DRAG_LOCK) {
                  return;
                }
                if (Math.abs(dx) > Math.abs(dy)) { active = false; return; }
                locked = true;
                startTop = box.scrollTop;
                startY = y;
                dy = 0;
                // Cap the drag's reach to ONE card in either direction,
                // with light rubber-band at the edge. This is the actual
                // anti-skip: the page can never be dragged a second reel
                // away, so release can never need a long slide home - the
                // "2/3 scroll hoye jacche abar auto ferot" feeling is
                // structurally impossible, not merely corrected after.
                var list0 = reelCards();
                loTop = startIdx > 0 ? startTop +
                  list0[startIdx - 1].getBoundingClientRect().top - PAD : 0;
                hiTop = startIdx < list0.length - 1 ? startTop +
                  list0[startIdx + 1].getBoundingClientRect().top - PAD : -1;
              }
              ev.preventDefault();
              lastDy = y - startY;
              var nat = startTop - lastDy, capped = nat;
              if (hiTop >= 0 && nat > hiTop) {
                capped = hiTop + (nat - hiTop) * 0.25;
              } else if (nat < loTop) {
                capped = loTop + (nat - loTop) * 0.25;
              }
              box.scrollTop = Math.max(0, capped);
              var now = Date.now();
              trail.push([now, y]);
              while (trail.length > 1 && now - trail[0][0] > 140) {
                trail.shift();
              }
            }, {passive: false});

            document.addEventListener('touchend', function(ev) {
              if (!active) return;
              var wasDrag = locked;
              active = false; locked = false;
              if (!wasDrag) return;   // a tap: nothing here is ours
              var list = reelCards(); if (!list.length) return;
              var box = scroller(); if (!box) return;
              // The decision, exactly the real apps' rule: a flick (fast
              // finger, any distance) commits one card in its direction;
              // a drag past DIST_FRAC of the card commits one card; a
              // smaller drag lands back where it began. Clamped, so no
              // gesture can ever travel more than one card - ever.
              var v = 0;
              if (trail.length > 1) {
                var a = trail[0], b = trail[trail.length - 1];
                if (b[0] > a[0]) v = (a[1] - b[1]) / (b[0] - a[0]);
              }
              trail = [];
              var cardH = list[startIdx] ?
                list[startIdx].getBoundingClientRect().height : 0;
              var travel = -lastDy;   // finger up (content advance) is +
              var intent = startIdx;
              if (v > FLING_V) intent++;
              else if (v < -FLING_V) intent--;
              else if (cardH > 0 && travel > cardH * DIST_FRAC) intent++;
              else if (cardH > 0 && travel < -cardH * DIST_FRAC) intent--;
              if (intent < 0) intent = 0;
              if (intent > list.length - 1) intent = list.length - 1;

              if (intent === startIdx && peak <= TAP_SLOP) {
                // The gesture stayed inside tap slop: it was never a
                // scroll, it was a tap with a shaking finger. But the
                // moment the lock engaged, the move's preventDefault
                // silently cancelled the browser's synthetic click - so
                // a tap on a playing reel paused NOTHING on device
                // (round 20: "offline reels pause kora jai na"), while
                // a 3px lab tap kept passing the test. Every real pager
                // treats a sub-slop touch as a tap, so we do it here by
                // hand: the card drifts home through the normal commit,
                // and the click the browser swallowed is delivered to
                // whatever was under the finger (the tap bridge then
                // pauses the video; links and buttons keep their jobs).
                commitTo(startIdx);
                try {
                  var ct = ev.changedTouches && ev.changedTouches[0];
                  if (ct) {
                    var hit = (document.elementFromPoint &&
                               document.elementFromPoint(
                                 ct.clientX, ct.clientY)) || null;
                    if (!hit) hit = list[startIdx] || null;
                    if (hit && hit.dispatchEvent) {
                      hit.dispatchEvent(new MouseEvent('click', {
                        bubbles: true, cancelable: true, view: window,
                        clientX: ct.clientX, clientY: ct.clientY }));
                    }
                  }
                } catch (e) {}
                return;
              }
              commitTo(intent);
            }, {passive: true});
          }

        })();
        """.trimIndent()

        // Set the section and the resume target outside the template so
        // tests can eval the raw source without an unresolved Kotlin
        // template variable.
        val bound = main.replace("__SEC__", section)
        if (resumeId != null) {
            return "window.__dbResumeId=" + "\"$resumeId\";\n" + bound
        }
        return bound
    }

    /**
     * The seen tracker every served feed/reels page carries.
     *
     * A card counts as SEEN only after it has really been in front of the
     * user: at least 60% of it visible, for at least 1.5 seconds, in one
     * unbroken stretch. A fast scroll-past resets the clock, so flicking
     * through the library never marks anything. The report goes straight
     * to the vault through the same bridge the resume script uses - it
     * is local file state, so it works with the network completely dead,
     * and Unknown-browser builds without IntersectionObserver simply
     * track nothing instead of breaking the page.
     */
    fun viewTrackScript(section: String): String {
        val track = """
        (function(){
          if (window.__dbViewTrack) return;
          window.__dbViewTrack = true;

          var SEC = "__SEC__";
          var RATIO = 0.6;      // of the card that must be on screen
          var DWELL_MS = 1500;  // and for this long, uninterrupted

          function holder() {
            return document.querySelector('[data-db-cards]');
          }

          function visibleFrac(el) {
            var r = el.getBoundingClientRect();
            if (r.height <= 0) return 0;
            var vh = window.innerHeight ||
                     (document.documentElement &&
                      document.documentElement.clientHeight) || 0;
            var vis = Math.min(r.bottom, vh) - Math.max(r.top, 0);
            return vis > 0 ? vis / r.height : 0;
          }

          var done = {};

          function mark(el) {
            var id = el.getAttribute('data-offline-id');
            if (!id || done[id]) return;
            done[id] = true;
            try { io.unobserve(el); } catch (e) {}
            if (window.FBPro && FBPro.markViewed) {
              try { FBPro.markViewed(SEC, id); } catch (e) {}
            }
          }

          if (!('IntersectionObserver' in window)) return;
          var io = new IntersectionObserver(function(entries){
            for (var i = 0; i < entries.length; i++) {
              var el = entries[i].target;
              if (entries[i].intersectionRatio >= RATIO) {
                if (!el.__dbViewT) {
                  el.__dbViewT = setTimeout(function(){
                    el.__dbViewT = null;
                    if (visibleFrac(el) >= RATIO) mark(el);
                  }, DWELL_MS);
                }
              } else if (el.__dbViewT) {
                clearTimeout(el.__dbViewT);
                el.__dbViewT = null;
              }
            }
          }, {threshold: [RATIO]});

          var h = holder();
          if (!h) return;
          var cards = h.querySelectorAll('[data-offline-id]');
          for (var i = 0; i < cards.length; i++) io.observe(cards[i]);
        })();
        """.trimIndent()
        return track.replace("__SEC__", section)
    }
}

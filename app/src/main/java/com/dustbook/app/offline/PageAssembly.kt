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
 * Placement is deterministic:
 *
 *  1. the real feed container (`data-type="vscroller"` - the element the
 *     m.facebook.com captures show) gets the cards as its FIRST child,
 *     and a stylesheet hides the block's old children, which are only
 *     grey server placeholders and stale posts nobody saved;
 *  2. if some stored document has no such container, the cards go right
 *     after <body> and nothing is hidden - content is guaranteed either
 *     way;
 *  3. a document with neither receives the cards at its end.
 *
 * Facebook's own header and tab bar are never touched: they live outside
 * the feed container, which is exactly why the offline page still looks
 * like the online one.
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
     * Everything past the cards inside the feed container. Those are grey
     * server placeholders and the stale posts the stored document shipped
     * with; the saved set replaces them wholesale, or the two would mix
     * on screen and look broken. Scoped to the container, so the header
     * and tab bar above it are untouched.
     */
    private const val HIDE_OLD =
        "<style id=\"__db_hide_old\">#__db_cards~*{display:none!important}</style>"

    /** The feed container in m.facebook.com markup, by its stable markers. */
    private val CONTAINER = Regex(
        "<div\\b[^>]*(?:data-type=[\"']vscroller[\"']" +
            "|data-mcomponent=[\"']MScreen[\"'])[^>]*>",
        RegexOption.IGNORE_CASE
    )

    private val BODY = Regex("<body\\b[^>]*>", RegexOption.IGNORE_CASE)

    /** The saved cards as one block, ready to sit inside the document. */
    fun holderHtml(cards: List<String>): String {
        val body = cards.joinToString("\n") { sanitize(it) }
        return "<div id=\"__db_cards\" data-db-cards=\"1\">\n$body\n</div>"
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
            return doc.substring(0, at) + HIDE_OLD + holder + doc.substring(at)
        }
        BODY.find(doc)?.let { m ->
            val at = m.range.last + 1
            return doc.substring(0, at) + holder + doc.substring(at)
        }
        return doc + holder
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

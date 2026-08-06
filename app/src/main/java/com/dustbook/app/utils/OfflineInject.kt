package com.dustbook.app.utils

import org.json.JSONArray

/**
 * Puts the content we hold into the stored Facebook page.
 *
 * What `m.facebook.com` server-renders is the shell plus grey placeholder
 * blocks; the stories themselves are filled in afterwards by Facebook's own JS
 * over the network. Storing the document therefore stores the skeleton, which
 * is exactly what the user sees offline.
 *
 * The posts and reels are already on disk - [OfflineFeed] holds them with
 * captions and media - they were simply never shown, because the stored
 * document won. This injects them into the feed container of that same
 * document, so the result is Facebook's own page with real content in it
 * rather than a screen of our own design.
 *
 * Safeguards, because a duplicated or mismatched feed would be worse than an
 * empty one:
 *
 *  - injected only into a document served from the offline store, never a live
 *    page, so it cannot appear online
 *  - the container is marked `data-db-offline`; a second pass sees the marker
 *    and stops, so nothing is ever added twice
 *  - the placeholders it replaces are removed in the same operation, so the
 *    grey blocks and the real posts cannot both be on screen
 */
object OfflineInject {

    /**
     * @param cards    Facebook's own markup for the saved stories, one string
     *                 per card, exactly as it was served.
     * @param resumeId when non-null and starts with "SCROLL:", it is a pixel
     *                 offset to restore on the feed scroller; otherwise it
     *                 is a reel/story id to scroll to.
     */
    fun script(cards: List<String>, resumeId: String? = null): String {
        if (cards.isEmpty()) return ""
        // The cards travel as one JSON array, never as a template literal.
        //
        // A template literal dies from a single bad card: "${" in an inline
        // script is read as an interpolation, and any "</script" - in ANY
        // letter case, because the HTML parser is case-insensitive while our
        // old lowercase-only breakup was not - ends the host block and drops
        // every card after it. One code snippet shared in a post was enough
        // to blank the entire offline library: the settings count, computed
        // from the store, kept climbing while the page showed only the
        // handful of posts the stored document itself carried.
        //
        // JSON has no interpolation and no backtick, so no card content can
        // be mistaken for code; and replacing "</" with the JSON-blessed
        // spelling "<\/" means the host script contains no closing sequence
        // at all, in any case. Per-card strings additionally isolate one
        // malformed card from the rest.
        val json = JSONArray()
        for (c in cards) json.put(c)
        val safe = json.toString().replace("</", "<\\/")

        val main = """
        (function(){
          if (window.__dbOfflineInject) return;
          window.__dbOfflineInject = true;

          var CARDS = $safe;
          if (!CARDS || !CARDS.length) return;

          function feedContainer() {
            var sc = document.querySelector('[data-type="vscroller"]');
            if (sc) return sc;
            var alt = document.querySelector('[data-mcomponent="MScreen"]');
            return alt || document.body;
          }

          function isPlaceholder(el) {
            if (!el || el.nodeType !== 1) return false;
            if (el.querySelector('img,video')) return false;
            var t = (el.innerText || el.textContent || '').trim();
            return t.length === 0;
          }

          function inject() {
            var box = feedContainer();
            if (!box || box.getAttribute('data-db-offline')) return;
            box.setAttribute('data-db-offline', '1');

            var kids = Array.prototype.slice.call(box.children);
            for (var i = 0; i < kids.length; i++) {
              if (isPlaceholder(kids[i])) {
                try { kids[i].parentNode.removeChild(kids[i]); } catch (e) {}
              }
            }

            var holder = document.createElement('div');
            holder.setAttribute('data-db-cards', '1');
            holder.innerHTML = CARDS.join('\n');
            box.appendChild(holder);

            doResume(box);
          }

          function doResume(box) {
            if (!window.__dbResumeId || !window.__dbResumeId) return;
            var id = window.__dbResumeId;
            if (id.indexOf('SCROLL:') === 0) {
              var px = parseInt(id.slice(7), 10);
              if (px > 0) {
                // Feed scroll offset. The scroller height may change as
                // images load, so try several times.
                tryAt([300, 800, 1500, 2500], function(){
                  var c = feedContainer();
                  if (c) c.scrollTop = px;
                });
              }
              return;
            }
            // Reel/story: scroll to the card with matching id. The card's
            // position changes as its poster loads, then again as the video
            // frame decodes, so try multiple times to catch the settled layout.
            scrollToCard(id, [300, 800, 1500, 2500]);
          }

          function scrollToCard(id, delays) {
            tryAt(delays, function(){
              var box = feedContainer();
              if (!box) return;
              var cards = box.querySelectorAll(
                '[data-video-id],[data-story-id],[data-offline-id]');
              for (var i = 0; i < cards.length; i++) {
                var c = cards[i];
                var cid = c.getAttribute('data-video-id') ||
                          c.getAttribute('data-story-id') ||
                          c.getAttribute('data-offline-id');
                if (cid === id) {
                  var container = c.closest('[data-type="vscroller"]');
                  if (container) {
                    var off = c.offsetTop - container.offsetTop;
                    container.scrollTop = Math.max(0,
                      off - (container.clientHeight / 3));
                  } else {
                    c.scrollIntoView({block: 'center', behavior: 'instant'});
                  }
                  return;
                }
              }
            });
          }

          function tryAt(delays, fn) {
            // Call fn now, then at each delay, but only if the page is
            // still showing offline cards (has not navigated away).
            fn();
            for (var i = 0; i < delays.length; i++) {
              (function(d){
                setTimeout(function(){
                  if (document.querySelector('[data-db-cards]')) fn();
                }, d);
              })(delays[i]);
            }
          }

          inject();
          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', inject);
          }
          setTimeout(inject, 600);
          setTimeout(inject, 1800);

          // Track position changes so the next session resumes here.
          var __lastReport = 0;
          function reportCurrent() {
            var now = Date.now();
            if (now - __lastReport < 2000) return;
            __lastReport = now;
            var box = feedContainer();
            if (!box) return;
            var mid = box.clientHeight / 2;
            var cards = box.querySelectorAll('[data-video-id],[data-story-id]');
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
          var scroller = feedContainer();
          if (scroller) {
            scroller.addEventListener('scroll', function(){
              if (window.__dbResumeTimer) clearTimeout(window.__dbResumeTimer);
              window.__dbResumeTimer = setTimeout(reportCurrent, 600);
            });
          }
        })();
        """.trimIndent()

        // Set the resume target outside the template so tests can still eval
        // the raw Kotlin source without encountering an unresolved Kotlin
        // template variable.
        if (resumeId != null) {
            return "window.__dbResumeId=" + "\"$resumeId\";\n" + main
        }
        return main
    }
}

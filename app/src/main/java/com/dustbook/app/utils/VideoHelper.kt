package com.dustbook.app.utils

import android.webkit.WebView

/**
 * V4 Step 3: Targeted, root-cause focused video improvements.
 *
 * Philosophy (respecting historical warnings):
 * - Never use CSS hacks or overlays to hide placeholders.
 * - Never use fake delays or timers.
 * - Focus on proper headers, lifecycle, and WebView configuration.
 * - Offline video must behave as close as possible to online.
 */
object VideoHelper {

    /**
     * Small, safe JS to help video elements start playback faster.
     * Only runs on offline documents.
     * Does NOT mutate Facebook's player — only nudges the <video> element.
     */
    fun getOfflineVideoAssistScript(): String = """
        (function(){
          if (window.__dbVideoAssist) return;
          window.__dbVideoAssist = true;

          function assistVideo(v) {
            if (!v || v.__dbAssisted) return;
            v.__dbAssisted = true;

            // playsinline so it does not go fullscreen on its own.
            //
            // Deliberately does NOT set v.muted. This used to force every
            // video silent so autoplay would start, and nothing ever undid
            // it, so sound only appeared after the user paused and pressed
            // play. Facebook already decides muting for its own player; we
            // leave that alone and only help playback begin.
            //
            // It also must not un-mute a running video: a clip that was only
            // allowed to start *because* it is muted gets stopped again the
            // moment the sound comes on, which left nothing playing at all.
            try {
              v.setAttribute('playsinline', '');
              v.setAttribute('webkit-playsinline', '');
              v.setAttribute('preload', 'auto');
            } catch (e) {}

            // The native control bar has to go. While it exists, a tap on
            // a playing video is spent showing and hiding the bar (that
            // timestamp/seek strip at the bottom the user described) and
            // never reaches the tap bridge below - so online the tap
            // paused, offline it did not ("play hobar somoy tap korle
            // pause hoi na"). The bar survives only on Facebook's own
            // live pages; offline its job is done by the bridge instead.
            // Scoped to videos inside saved cards: a video sitting in the
            // story viewer's overlay keeps whatever controls it came
            // with.
            try {
              if (v.closest && v.closest('#__db_cards')) {
                v.removeAttribute('controls');
              }
            } catch (e) {}

            // The stored markup already carries the cached MP4 URL on
            // the video tag's src. Only help the element discover the
            // data; let the user or Facebook's own player start it.
            // Auto-playing every video on injection is what broke
            // sound on the second reel — the browser paused a
            // user-gesture-less play() and the promise rejection was
            // silently eaten, leaving a still frame with no audio.
          }

          // Observe for new video elements (reels, feed videos)
          var mo = new MutationObserver(function(muts) {
            for (var i=0; i<muts.length; i++) {
              var nodes = muts[i].addedNodes;
              for (var j=0; j<nodes.length; j++) {
                var n = nodes[j];
                if (n.tagName === 'VIDEO') assistVideo(n);
                else if (n.querySelectorAll) {
                  var vids = n.querySelectorAll('video');
                  for (var k=0; k<vids.length; k++) assistVideo(vids[k]);
                }
              }
            }
          });

          if (document.body) {
            mo.observe(document.body, { childList: true, subtree: true });
          } else {
            document.addEventListener('DOMContentLoaded', function(){
              mo.observe(document.body, { childList: true, subtree: true });
            }, {once:true});
          }

          // Assist any videos already on the page
          var existing = document.querySelectorAll('video');
          for (var i=0; i<existing.length; i++) assistVideo(existing[i]);

          // Tap-to-play. A stored reel's own play control ran on the
          // site's JS, which never ships with the saved page, so a tap
          // met a dead handler and NOTHING ever started (the round-11
          // report: "reels Play hoi na"). Tapping a saved card now
          // toggles its video - exactly what a tap does online. Real
          // links, buttons and controls (comments, share, the poster's
          // name) keep their own jobs, and the story pager's zones are
          // left alone: a story advances left/right instead of pausing
          // mid-way. One judgment call below: on the captured player
          // species the PLAYER ITSELF carries role="button" ("Video
          // player", the stored fixture data-video-id=1452526892980986),
          // so a blanket button-guard spent every tap on the picture as
          // a control press and offline video could never be paused by
          // touch on those cards at all (17:12: "ekhon to pause o hoi
          // na offline er video te"; overnight: "just 1st ta pause
          // hoto" - the first reel was the species WITHOUT this role).
          // A button is only a control when it holds no player. A third
          // species puts role="button" directly ON the <video> tag
          // itself instead of a wrapper: querySelector('video') never
          // matches self, so rb looked like it held no player and every
          // tap on that species was swallowed too - silently on reels,
          // intermittently on home (posts mix both species).
          document.addEventListener('click', function(e) {
            try {
              if (document.getElementById('__db_story_overlay')) return;
              var t = e.target;
              if (!t || !t.closest) return;
              if (t.closest('a,button,input,textarea,select')) return;
              var rb = t.closest('[role="button"]');
              if (rb && rb.tagName !== 'VIDEO' &&
                  !(rb.querySelector && rb.querySelector('video'))) {
                return;
              }
              var card = t.closest ? t.closest('#__db_cards>*') : null;
              var vs = card && card.querySelectorAll ?
                card.querySelectorAll('video') : null;
              if ((!vs || !vs.length) && t.closest) {
                var vv = t.closest('video');
                if (vv) vs = [vv];
              }
              if (!vs || !vs.length) return;
              e.preventDefault();
              // A pause must reach EVERY player the card carries, not
              // just the first. Every captured species so far holds
              // exactly one video per card, where this is byte-for-byte
              // the old behavior; should a card ever hold two, pausing
              // the first alone would leave the second one's audio
              // running - audible forever, picture frozen.
              var anyPlaying = false;
              for (var i = 0; i < vs.length; i++) {
                if (!vs[i].paused) { anyPlaying = true; break; }
              }
              if (anyPlaying) {
                for (var j = 0; j < vs.length; j++) {
                  try { vs[j].pause(); } catch (e2) {}
                }
              } else {
                var p = vs[0].play();
                if (p && p.catch) p.catch(function() {});
              }
            } catch (err) {}
          }, true);

          // One sound at a time, the way the online pager behaves: a
          // video that starts playing pauses every other player.
          document.addEventListener('play', function(e) {
            try {
              var vs = document.querySelectorAll('video');
              for (var i = 0; i < vs.length; i++) {
                if (vs[i] !== e.target && !vs[i].paused) {
                  try { vs[i].pause(); } catch (err) {}
                }
              }
            } catch (err) {}
          }, true);
        })();
    """.trimIndent()

    /**
     * Improves WebView settings specifically for better video behavior.
     * Called from MainActivity.
     */
    fun applyVideoOptimizations(webView: WebView) {
        webView.settings.apply {
            // Deliberately does NOT touch mediaPlaybackRequiresUserGesture.
            //
            // This used to set it to `!true`, i.e. false, and because this
            // runs after applyWebSettings() it silently overwrote the user's
            // autoplay preference on every launch. That flag belongs to
            // applyWebSettings, which reads prefs.autoplayVideo; the comment
            // here already said "let caller decide", so let it.

            // Better for inline video playback
            setSupportMultipleWindows(true)
        }
    }

    /**
     * Returns extra headers that help video playback when serving from cache.
     */
    fun getVideoExtraHeaders(): Map<String, String> = mapOf(
        "Accept-Ranges" to "bytes",
        "Cache-Control" to "max-age=604800",
        "Access-Control-Allow-Origin" to "*"
    )
}
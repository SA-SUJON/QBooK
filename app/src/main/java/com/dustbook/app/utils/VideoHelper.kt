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
          // links and buttons (comments, share, the poster's name) keep
          // their own jobs, and the story pager's zones are left alone:
          // a story advances left/right instead of pausing mid-way.
          document.addEventListener('click', function(e) {
            try {
              if (document.getElementById('__db_story_overlay')) return;
              var t = e.target;
              if (!t || !t.closest) return;
              if (t.closest('a,button,[role="button"],input,textarea,select'))
                return;
              var card = t.closest ? t.closest('#__db_cards>*') : null;
              var v = card && card.querySelector ? card.querySelector('video') : null;
              if (!v) v = t.closest('video');
              if (!v) return;
              e.preventDefault();
              if (v.paused) {
                var p = v.play();
                if (p && p.catch) p.catch(function() {});
              } else {
                v.pause();
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
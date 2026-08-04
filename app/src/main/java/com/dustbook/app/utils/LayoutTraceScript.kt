package com.dustbook.app.utils

/**
 * Page-side half of the layout investigation.
 *
 * Reports, with timings, every stage that could plausibly move a reel:
 * document readiness, the first and subsequent layouts, resize and
 * visualViewport events, DOM mutations that add or replace a player,
 * ResizeObserver firings, and the measured position of the player and of the
 * metadata row underneath it.
 *
 * Read-only. It observes and reports; it sets no style, adds no class and
 * moves nothing, so it cannot itself become the cause of what it is measuring.
 * Injected only while the diagnostic setting is on.
 */
object LayoutTraceScript {

    fun script(): String = """
        (function() {
          if (window.__dbTrace) return;
          window.__dbTrace = true;

          function say(s) {
            /* FBPro is the bridge the app already registers; log() was added
               to it for this investigation. */
            try { window.FBPro.log(s); } catch (e) {}
          }

          function num(v) { return Math.round(v); }

          function viewport() {
            var v = window.visualViewport;
            return 'innerH=' + num(window.innerHeight) +
                   ' docH=' + num(document.documentElement.clientHeight) +
                   ' visualH=' + (v ? num(v.height) : -1) +
                   ' visualTop=' + (v ? num(v.offsetTop) : -1) +
                   ' scale=' + (v ? (Math.round(v.scale * 100) / 100) : -1) +
                   ' scrollY=' + num(window.scrollY);
          }

          /* The safe-area values, which are the only thing that can move
             content down without any element changing size. */
          function safeArea() {
            try {
              var p = document.createElement('div');
              p.style.cssText =
                'position:fixed;top:0;left:0;width:0;height:0;' +
                'padding-top:env(safe-area-inset-top);' +
                'padding-bottom:env(safe-area-inset-bottom);' +
                'visibility:hidden;pointer-events:none';
              document.documentElement.appendChild(p);
              var cs = getComputedStyle(p);
              var t = cs.paddingTop, b = cs.paddingBottom;
              p.remove();
              return 'safeTop=' + t + ' safeBottom=' + b;
            } catch (e) { return 'safe=?'; }
          }

          /* The player: the largest <video> currently on screen. Reported by
             its bounding box, which is what the eye actually judges. */
          function player() {
            var vids = document.getElementsByTagName('video');
            var best = null, bestArea = 0;
            for (var i = 0; i < vids.length; i++) {
              var r = vids[i].getBoundingClientRect();
              var a = r.width * r.height;
              if (a > bestArea) { bestArea = a; best = vids[i]; }
            }
            if (!best) return 'player=none videos=' + vids.length;
            var r = best.getBoundingClientRect();
            var cs = null;
            try { cs = getComputedStyle(best); } catch (e) {}
            return 'player top=' + num(r.top) + ' bottom=' + num(r.bottom) +
                   ' h=' + num(r.height) + ' w=' + num(r.width) +
                   ' vw=' + (best.videoWidth || 0) + 'x' + (best.videoHeight || 0) +
                   ' objectFit=' + (cs ? cs.objectFit : '?') +
                   ' transform=' + (cs ? cs.transform : '?') +
                   ' videos=' + vids.length;
          }

          /* The metadata row - author, caption, buttons. If this moves while
             the player does not, the two are being positioned independently. */
          function meta() {
            var el = null;
            try {
              el = document.querySelector('[data-mcomponent="MContainer"].fixed-container.bottom') ||
                   document.querySelector('.fixed-container.bottom');
            } catch (e) {}
            if (!el) return 'meta=none';
            var r = el.getBoundingClientRect();
            return 'meta top=' + num(r.top) + ' bottom=' + num(r.bottom) +
                   ' h=' + num(r.height);
          }

          /* The scroller a reel screen lives in. A stale height here, or a
             leftover translate, would move everything inside it. */
          function scroller() {
            var el = null;
            try { el = document.querySelector('[data-type="vscroller"]'); } catch (e) {}
            if (!el) return 'scroller=none';
            var r = el.getBoundingClientRect();
            var cs = null;
            try { cs = getComputedStyle(el); } catch (e) {}
            return 'scroller top=' + num(r.top) + ' h=' + num(r.height) +
                   ' scrollTop=' + num(el.scrollTop) +
                   ' transform=' + (cs ? cs.transform : '?');
          }

          function full(tag) {
            say(tag + ' | ' + viewport());
            say(tag + ' | ' + safeArea());
            say(tag + ' | ' + player());
            say(tag + ' | ' + meta());
            say(tag + ' | ' + scroller());
          }

          say('script injected readyState=' + document.readyState);
          full('inject');

          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function() {
              full('DOMContentLoaded');
            }, {once: true});
          }
          window.addEventListener('load', function() { full('load'); }, {once: true});

          /* First paint, and the frame after it: a value that is right at
             paint and wrong one frame later is the signature of something
             re-laying out after the fact. */
          requestAnimationFrame(function() {
            full('frame1');
            requestAnimationFrame(function() { full('frame2'); });
          });

          window.addEventListener('resize', function() {
            say('resize | ' + viewport());
            say('resize | ' + player());
          });

          if (window.visualViewport) {
            window.visualViewport.addEventListener('resize', function() {
              say('vv.resize | ' + viewport());
              say('vv.resize | ' + player());
            });
            window.visualViewport.addEventListener('scroll', function() {
              say('vv.scroll | ' + viewport());
            });
          }

          window.addEventListener('orientationchange', function() {
            say('orientationchange | ' + viewport());
          });

          document.addEventListener('visibilitychange', function() {
            say('visibility=' + document.visibilityState + ' | ' + viewport());
          });

          /* A new player appearing is the moment a reel is swapped. */
          try {
            var seen = 0;
            var mo = new MutationObserver(function(muts) {
              var added = 0;
              for (var i = 0; i < muts.length; i++) {
                var n = muts[i].addedNodes;
                for (var j = 0; j < n.length; j++) {
                  var el = n[j];
                  if (!el || el.nodeType !== 1) continue;
                  if (el.tagName === 'VIDEO' ||
                      (el.querySelector && el.querySelector('video'))) added++;
                }
              }
              if (!added) return;
              seen++;
              say('mutation #' + seen + ' newPlayers=' + added);
              full('mutation#' + seen);
              /* And again a moment later: if the box is right now and wrong
                 shortly after, the culprit is whatever ran in between. */
              setTimeout(function() { full('mutation#' + seen + '+250ms'); }, 250);
            });
            mo.observe(document.documentElement, {childList: true, subtree: true});
          } catch (e) { say('MutationObserver failed: ' + e); }

          /* ResizeObserver on the player itself: reports the exact instant
             the box changes, which a poll can only approximate. */
          try {
            var ro = new ResizeObserver(function(entries) {
              for (var i = 0; i < entries.length; i++) {
                var r = entries[i].contentRect;
                say('resizeObs target=' + entries[i].target.tagName +
                    ' w=' + num(r.width) + ' h=' + num(r.height));
              }
              say('resizeObs | ' + viewport());
            });
            var watch = function() {
              var v = document.getElementsByTagName('video');
              for (var i = 0; i < v.length; i++) {
                if (!v[i].__dbRo) { v[i].__dbRo = true; ro.observe(v[i]); }
              }
            };
            watch();
            setInterval(watch, 1000);
          } catch (e) { say('ResizeObserver failed: ' + e); }

          /* Backstop: report whenever the player's top edge actually moves,
             whatever caused it. This is the line that will differ between a
             good run and a bad one. */
          (function() {
            var lastTop = null, lastH = null;
            setInterval(function() {
              var v = document.getElementsByTagName('video');
              var best = null, area = 0;
              for (var i = 0; i < v.length; i++) {
                var r = v[i].getBoundingClientRect();
                if (r.width * r.height > area) { area = r.width * r.height; best = v[i]; }
              }
              if (!best) return;
              var r = best.getBoundingClientRect();
              var t = num(r.top), h = num(r.height);
              if (t !== lastTop || h !== lastH) {
                say('MOVED top ' + lastTop + '->' + t + '  h ' + lastH + '->' + h);
                full('moved');
                lastTop = t; lastH = h;
              }
            }, 400);
          })();
        })();
    """.trimIndent()
}

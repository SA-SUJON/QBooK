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
                   ' innerW=' + num(window.innerWidth) +
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

          /* The screen root. It carries Facebook's own sizing - the first
             trace showed style="min-height:100vh; width:360px" - so its box
             is what everything inside is measured against. */
          function screenRoot() {
            var el = null;
            try { el = document.querySelector('[data-mcomponent="MScreen"]'); } catch (e) {}
            if (!el) return 'screen=none';
            var r = el.getBoundingClientRect();
            var cs = null;
            try { cs = getComputedStyle(el); } catch (e) {}
            return 'screen top=' + num(r.top) + ' h=' + num(r.height) +
                   ' w=' + num(r.width) +
                   ' minH=' + (cs ? cs.minHeight : '?') +
                   ' cssW=' + (cs ? cs.width : '?');
          }

          function full(tag) {
            say(tag + ' | ' + viewport());
            say(tag + ' | ' + safeArea());
            say(tag + ' | ' + player());
            say(tag + ' | ' + meta());
            say(tag + ' | ' + scroller());
            say(tag + ' | ' + screenRoot());
          }

          /* ---------------------------------------------------------------
             Round two.

             The first trace settled the geometry question: viewport,
             visualViewport, scale, WebView size, player height, video
             dimensions and object-fit were identical in every sample. The
             only things that moved were the scroller's scrollTop and, in
             lockstep with it, the player's top edge:

                 player.top == 50 - scrollTop     (exact, five samples)

             So nothing is being re-laid out. Something is scrolling the reel
             container. This half finds out what.
             --------------------------------------------------------------- */

          function el2s(el) {
            if (!el) return 'null';
            if (el === document.body) return 'BODY';
            if (el === document.documentElement) return 'HTML';
            var s = el.tagName;
            if (el.id) s += '#' + el.id;
            var mc = el.getAttribute && el.getAttribute('data-mcomponent');
            if (mc) s += '[' + mc + ']';
            var ai = el.getAttribute && el.getAttribute('data-action-id');
            if (ai) s += '{a=' + ai + '}';
            return s;
          }

          /* Who called. The bundle is minified, so the names are short, but
             they are stable and greppable against the shipped file. */
          function who(skip) {
            try {
              var st = new Error().stack || '';
              var lines = st.split('\n').slice(skip || 3, (skip || 3) + 3);
              return lines.map(function(l) {
                return l.trim().replace(/^at\s+/, '').slice(0, 70);
              }).join(' <- ');
            } catch (e) { return '?'; }
          }

          function vscroller() {
            try { return document.querySelector('[data-type="vscroller"]'); }
            catch (e) { return null; }
          }

          function playerTop() {
            var v = document.getElementsByTagName('video');
            var best = null, area = 0;
            for (var i = 0; i < v.length; i++) {
              var r = v[i].getBoundingClientRect();
              if (r.width * r.height > area) { area = r.width * r.height; best = v[i]; }
            }
            return best ? Math.round(best.getBoundingClientRect().top) : null;
          }

          /* Trap writes to scrollTop on the element prototype. This is the
             single most useful line in the whole investigation: it names the
             code that moves the reel, at the moment it happens. */
          try {
            var proto = Element.prototype;
            var desc = Object.getOwnPropertyDescriptor(proto, 'scrollTop') ||
                       Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollTop');
            if (desc && desc.set) {
              Object.defineProperty(proto, 'scrollTop', {
                configurable: true,
                enumerable: desc.enumerable,
                get: function() { return desc.get.call(this); },
                set: function(v) {
                  var before = desc.get.call(this);
                  desc.set.call(this, v);
                  if (Math.round(before) !== Math.round(v)) {
                    say('SET scrollTop ' + el2s(this) + ' ' +
                        Math.round(before) + '->' + Math.round(v) +
                        ' playerTop=' + playerTop() + ' | ' + who(2));
                  }
                }
              });
              say('scrollTop setter trapped');
            } else {
              say('scrollTop setter NOT trappable');
            }
          } catch (e) { say('scrollTop trap failed: ' + e); }

          /* scrollIntoView is the other way a container gets moved, and the
             bundle contains a wrapper that calls it (Tyb -> a.scrollIntoView). */
          try {
            var sivProto = Element.prototype;
            var origSiv = sivProto.scrollIntoView;
            if (typeof origSiv !== 'function') {
              say('scrollIntoView absent');
            } else {
            sivProto.scrollIntoView = function() {
              var sc = vscroller();
              var beforeTop = sc ? Math.round(sc.scrollTop) : -1;
              var arg = '';
              try { arg = JSON.stringify(arguments[0]); } catch (e) {}
              var r = origSiv.apply(this, arguments);
              var afterTop = sc ? Math.round(sc.scrollTop) : -1;
              say('scrollIntoView ' + el2s(this) + ' opts=' + arg +
                  ' scrollTop ' + beforeTop + '->' + afterTop +
                  ' playerTop=' + playerTop() + ' | ' + who(2));
              return r;
            };
            say('scrollIntoView trapped');
            }
          } catch (e) { say('scrollIntoView trap failed: ' + e); }

          /* focus() scrolls the nearest scrollable ancestor unless
             preventScroll is passed. The bundle passes it in three places and
             does NOT pass it on the video element, which is the leading
             suspect for the 16px moves. */
          try {
            var focusProto = HTMLElement.prototype;
            var origFocus = focusProto.focus;
            focusProto.focus = function() {
              var sc = vscroller();
              var beforeTop = sc ? Math.round(sc.scrollTop) : -1;
              var opts = arguments[0];
              var prevented = !!(opts && opts.preventScroll);
              var r = origFocus.apply(this, arguments);
              var afterTop = sc ? Math.round(sc.scrollTop) : -1;
              if (beforeTop !== afterTop || this.tagName === 'VIDEO') {
                say('focus ' + el2s(this) + ' preventScroll=' + prevented +
                    ' scrollTop ' + beforeTop + '->' + afterTop +
                    ' playerTop=' + playerTop() + ' | ' + who(2));
              }
              return r;
            };
            say('focus trapped');
          } catch (e) { say('focus trap failed: ' + e); }

          /* Every scroll event on the reel container, with what is under the
             middle of the screen and what currently has focus. */
          (function() {
            var last = null;
            document.addEventListener('scroll', function(ev) {
              var t = ev.target;
              if (!t || !t.getAttribute) return;
              if (t.getAttribute('data-type') !== 'vscroller') return;
              var now = Math.round(t.scrollTop);
              if (now === last) return;
              last = now;
              var mid = null;
              try {
                mid = document.elementFromPoint(
                  Math.round(window.innerWidth / 2),
                  Math.round(window.innerHeight / 2));
              } catch (e) {}
              say('scroll event scrollTop=' + now +
                  ' playerTop=' + playerTop() +
                  ' active=' + el2s(document.activeElement) +
                  ' mid=' + el2s(mid));
            }, {passive: true, capture: true});
          })();

          /* IntersectionObserver is how a pager decides which reel is current,
             and a snap driven by it would move the container. */
          try {
            var OrigIO = window.IntersectionObserver;
            if (OrigIO) {
              var ioCount = 0;
              window.IntersectionObserver = function(cb, opts) {
                var id = ++ioCount;
                say('IntersectionObserver #' + id + ' created thresholds=' +
                    JSON.stringify(opts && opts.threshold) + ' | ' + who(2));
                return new OrigIO(function(entries, obs) {
                  for (var i = 0; i < entries.length; i++) {
                    var e = entries[i];
                    if (e.target && e.target.tagName === 'VIDEO') {
                      say('IO #' + id + ' ' + el2s(e.target) +
                          ' intersecting=' + e.isIntersecting +
                          ' ratio=' + (Math.round(e.intersectionRatio * 100) / 100));
                    }
                  }
                  return cb.apply(this, arguments);
                }, opts);
              };
              window.IntersectionObserver.prototype = OrigIO.prototype;
              say('IntersectionObserver trapped');
            } else {
              say('IntersectionObserver absent');
            }
          } catch (e) { say('IO trap failed: ' + e); }

          /* Player identity across a recycle. mutation#2 reported three new
             players while only one remained, which is the signature of a
             recycled pager - and whatever re-attaches a player can re-run
             focus() or scrollIntoView(). Tagging each element shows whether
             the visible player is a new node or the same one moved. */
          (function() {
            var nextId = 1;
            setInterval(function() {
              var v = document.getElementsByTagName('video');
              var ids = [];
              for (var i = 0; i < v.length; i++) {
                if (!v[i].__dbId) v[i].__dbId = nextId++;
                var r = v[i].getBoundingClientRect();
                ids.push('#' + v[i].__dbId + '@' + Math.round(r.top) +
                         (v[i].paused ? 'p' : 'P'));
              }
              var sc = vscroller();
              say('players ' + ids.join(' ') +
                  ' scrollTop=' + (sc ? Math.round(sc.scrollTop) : -1) +
                  ' active=' + el2s(document.activeElement));
            }, 1000);
          })();

          /* ---------------------------------------------------------------
             Round three.

             The second trace ruled the scroll theory out: scrollTop stayed 0
             throughout, no SET, no focus, no scrollIntoView, no scroll event
             - and the screen was still wrong. So nothing is scrolling; some
             element is simply the wrong height.

             The decisive new fact is that Stories are affected identically.
             Measured from the two screenshots:

                 reel  bottom gap = 376 px
                 story bottom gap = 375 px

             A story is a full-screen canvas, not a 9:16 video, and it is a
             different screen from Reels. Both stop at the same place, so the
             constraint belongs to the page area they share, not to either
             player. In css:

                 viewport      808
                 content ends  676   ( = 380 * 16/9, and 1918 px )
                 unused        132

             676 is the height a 16:9 device would have at this width. So
             something is sizing the screen for a 16:9 phone on a 20:9 one.

             This pass finds the element that carries that height, by walking
             the chain from the player up to <html> and printing what each
             ancestor measures and which CSS declaration produced it.
             --------------------------------------------------------------- */

          function cssOf(el) {
            var cs = null;
            try { cs = getComputedStyle(el); } catch (e) { return '?'; }
            var inline = (el.getAttribute && el.getAttribute('style')) || '';
            return 'h=' + cs.height + ' minH=' + cs.minHeight +
                   ' maxH=' + cs.maxHeight +
                   ' pos=' + cs.position +
                   ' flex=' + cs.flex +
                   ' aspect=' + (cs.aspectRatio || 'n/a') +
                   ' overflow=' + cs.overflow +
                   (inline ? ' inline="' + inline.slice(0, 90) + '"' : '');
          }

          /* Walk from the biggest video (or the screen root) up to <html>,
             printing the measured box and the CSS behind it at every level.
             Whichever ancestor is 676 css tall while its parent is 808 is
             the one imposing the limit, and its declaration names the cause. */
          function chain(tag) {
            var start = null, area = 0;
            var v = document.getElementsByTagName('video');
            for (var i = 0; i < v.length; i++) {
              var r = v[i].getBoundingClientRect();
              if (r.width * r.height > area) { area = r.width * r.height; start = v[i]; }
            }
            if (!start) {
              try {
                start = document.querySelector('[data-mcomponent="MScreen"]') ||
                        document.querySelector('[data-type="vscroller"]') ||
                        document.body;
              } catch (e) { start = document.body; }
            }
            if (!start) { say(tag + ' chain: nothing to walk'); return; }

            var el = start, depth = 0;
            while (el && depth < 14) {
              var r = el.getBoundingClientRect();
              say(tag + ' chain[' + depth + '] ' + el2s(el) +
                  ' rect top=' + Math.round(r.top) +
                  ' h=' + Math.round(r.height) +
                  ' w=' + Math.round(r.width) +
                  ' | ' + cssOf(el));
              el = el.parentElement;
              depth++;
            }
            /* documentElement and body separately: a height on either would
               cap everything inside without appearing in the walk above. */
            var de = document.documentElement, bd = document.body;
            if (de) say(tag + ' HTML rect h=' +
                        Math.round(de.getBoundingClientRect().height) +
                        ' scrollH=' + de.scrollHeight + ' | ' + cssOf(de));
            if (bd) say(tag + ' BODY rect h=' +
                        Math.round(bd.getBoundingClientRect().height) +
                        ' scrollH=' + bd.scrollHeight + ' | ' + cssOf(bd));
          }

          /* The lite renderer swaps screens without navigating, so the
             injection-time events never fire again. Everything interesting
             therefore has to be sampled on a timer as well - which is why the
             last trace held only the census. */
          (function() {
            var lastSig = null;
            setInterval(function() {
              var v = document.getElementsByTagName('video');
              var best = null, area = 0;
              for (var i = 0; i < v.length; i++) {
                var r = v[i].getBoundingClientRect();
                if (r.width * r.height > area) { area = r.width * r.height; best = v[i]; }
              }
              var probe = best ||
                (function() {
                  try { return document.querySelector('[data-mcomponent="MScreen"]'); }
                  catch (e) { return null; }
                })();
              if (!probe) return;
              var r = probe.getBoundingClientRect();
              var sig = Math.round(r.top) + 'x' + Math.round(r.height) +
                        '@' + Math.round(window.innerHeight);
              if (sig === lastSig) return;
              lastSig = sig;
              say('CHANGED ' + el2s(probe) + ' top=' + Math.round(r.top) +
                  ' h=' + Math.round(r.height) + ' innerH=' +
                  Math.round(window.innerHeight));
              chain('changed');
            }, 500);
          })();

          /* And once early, so a correct run has something to compare. */
          setTimeout(function() { chain('t+1500'); }, 1500);
          setTimeout(function() { chain('t+4000'); }, 4000);

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

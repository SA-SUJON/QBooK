package org.qbook.utils

/**
 * Debug tool for capturing the markup of an ad that slipped through.
 *
 * Filter rules have to name the exact attributes Facebook uses, and those
 * differ per account, locale and A/B bucket. Guessing at them from screenshots
 * has repeatedly produced rules that either miss the ad or remove the feed.
 * This captures the real markup instead.
 *
 * When inspect mode is on, long-pressing an element reports a compact summary
 * of it and its ancestors: tag, id, class, and every data-* / aria-* attribute,
 * which is where Facebook's ad markers live. The post's visible text is
 * truncated hard so nothing private is collected beyond what identifies the
 * card.
 */
object AdInspector {

    /**
     * Long-press capture. Reports through the FBPro.onAdHtml bridge.
     *
     * Deliberately does not send the whole outerHTML: a feed card can be
     * hundreds of kilobytes of nested divs, and none of that helps write a
     * selector. The attributes and the ancestor chain are what matter.
     */
    fun script(): String {
        return """
            (function() {
              'use strict';
              if (window.__dbInspect) return;
              window.__dbInspect = true;

              function attrs(el) {
                var out = [];
                if (!el.attributes) return out;
                for (var i = 0; i < el.attributes.length; i++) {
                  var a = el.attributes[i];
                  var n = a.name;
                  // Keep the attributes rules are written against. Skip style
                  // and the obfuscated class soup unless it is short.
                  if (n === 'style') continue;
                  if (n === 'class') {
                    if (a.value.length <= 120) out.push('class="' + a.value + '"');
                    continue;
                  }
                  var v = a.value;
                  if (v.length > 160) v = v.slice(0, 160) + '...';
                  out.push(n + '="' + v + '"');
                }
                return out;
              }

              function describe(el, depth) {
                var s = '  '.repeat(depth) + '<' + el.tagName.toLowerCase();
                var a = attrs(el);
                if (a.length) s += ' ' + a.join(' ');
                s += '>';
                var t = (el.innerText || el.textContent || '').trim().replace(/\s+/g, ' ');
                if (t) s += '  // text: "' + t.slice(0, 60) + (t.length > 60 ? '...' : '') + '"';
                return s;
              }

              function report(el) {
                var lines = [];
                lines.push('URL: ' + location.href);
                lines.push('UA-mobile: ' + /Mobile/.test(navigator.userAgent));
                lines.push('');

                // Ancestor chain, outermost first, so the card is visible.
                var chain = [];
                var n = el, guard = 0;
                while (n && n.tagName && guard < 12) {
                  chain.unshift(n);
                  if (n.tagName === 'BODY') break;
                  n = n.parentElement;
                  guard++;
                }
                lines.push('--- ancestor chain (outermost first) ---');
                for (var i = 0; i < chain.length; i++) {
                  lines.push(describe(chain[i], i));
                }

                // Anything in the subtree that looks like an ad marker.
                lines.push('');
                lines.push('--- candidate markers in this subtree ---');
                // Pick the outermost ancestor that is still a single card
                // rather than the feed. Scanning from the pressed element
                // upward missed the card's own attributes, which is exactly
                // where the ad markers live.
                var card = el;
                for (var c = 0; c < chain.length; c++) {
                  var cand = chain[c];
                  var tag = cand.tagName;
                  if (tag === 'BODY' || tag === 'HTML') continue;
                  var id = cand.id || '';
                  // Stop before anything that looks like the feed container.
                  if (/feed|stream|root|container|viewport/i.test(id)) continue;
                  card = cand;
                  break;
                }
                var found = [];
                try {
                  var all = [card].concat(
                    Array.prototype.slice.call(card.querySelectorAll('*'))
                  );
                  for (var j = 0; j < all.length && j < 400; j++) {
                    var e = all[j];
                    if (!e.attributes) continue;
                    for (var k = 0; k < e.attributes.length; k++) {
                      var an = e.attributes[k].name, av = e.attributes[k].value;
                      if (an === 'style' || an === 'class') continue;
                      if (/sigil|data-ft|pagelet|testid|posinset|store|tracking|sponsor|promo/i
                            .test(an) ||
                          /ads\/about|sponsored|AdStory|quick_promotion|ad_id/i.test(av)) {
                        var line = e.tagName.toLowerCase() + '[' + an + '="' +
                                   av.slice(0, 200) + '"]';
                        if (found.indexOf(line) === -1) found.push(line);
                      }
                    }
                  }
                } catch (err) {}
                if (found.length) {
                  for (var f = 0; f < found.length && f < 25; f++) lines.push('  ' + found[f]);
                } else {
                  lines.push('  (none found - this ad carries no obvious marker)');
                }

                // A suggested rule, if a marker is obvious.
                lines.push('');
                lines.push('--- suggested selector ---');
                var suggestion = null;
                for (var s2 = 0; s2 < found.length; s2++) {
                  if (/sigil|data-ft|posinset|pagelet/i.test(found[s2])) {
                    suggestion = found[s2];
                    break;
                  }
                }
                lines.push('  ' + (suggestion || '(no reliable marker - text or link based rule needed)'));

                // Direct media URL of the card under the finger. This is the
                // whole point of "Log reel video URLs": the real fbcdn mp4 the
                // pressed reel is playing, so it can be blocked at the network
                // layer. Read-only walk from the pressed element up to the
                // card, grabbing the first <video> source (currentSrc preferred).
                // Nothing here mutates the DOM, so the snap scroller is untouched.
                lines.push('');
                lines.push('--- pressed card video URL ---');
                var vurl = null, vp = el, vg = 0;
                while (vp && vp.tagName && vg < 14) {
                  if (vp.tagName === 'VIDEO') {
                    vurl = vp.currentSrc || vp.src || null;
                    if (vurl) break;
                  }
                  var vEl = vp.querySelector ? vp.querySelector('video') : null;
                  if (vEl) {
                    vurl = vEl.currentSrc || vEl.src || null;
                    if (vurl) break;
                  }
                  if (vp.tagName === 'BODY') break;
                  vp = vp.parentElement;
                  vg++;
                }
                lines.push(vurl ? '  ' + vurl : '  (no <video> source found on this card)');

                var text = lines.join('\n');
                try {
                  if (window.FBPro && window.FBPro.onAdHtml) window.FBPro.onAdHtml(text);
                } catch (e2) {}
              }

              // Long press anywhere while inspect mode is on.
              var timer = null, startX = 0, startY = 0, target = null;

              document.addEventListener('touchstart', function(ev) {
                if (!ev.touches || ev.touches.length !== 1) return;
                target = ev.target;
                startX = ev.touches[0].clientX;
                startY = ev.touches[0].clientY;
                clearTimeout(timer);
                timer = setTimeout(function() {
                  if (target) report(target);
                }, 650);
              }, true);

              document.addEventListener('touchmove', function(ev) {
                if (!ev.touches || !ev.touches.length) return;
                var dx = Math.abs(ev.touches[0].clientX - startX);
                var dy = Math.abs(ev.touches[0].clientY - startY);
                if (dx > 12 || dy > 12) clearTimeout(timer);
              }, true);

              document.addEventListener('touchend', function() {
                clearTimeout(timer);
              }, true);
            })();
        """.trimIndent()
    }
}

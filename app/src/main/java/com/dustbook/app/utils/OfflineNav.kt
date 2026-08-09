package com.dustbook.app.utils

/**
 * Makes Facebook's own tab bar work with no connection.
 *
 * Every control in the bar looks like this, from the device captures:
 *
 * ```html
 * <div role="button" tabindex="0" aria-label="Reels" data-action-id="32761"
 *      data-mcomponent="MContainer">
 * ```
 *
 * There is no `href` anywhere. The action id is resolved by Facebook's runtime,
 * which fetches JSON and swaps the screen client-side. With no network that
 * fetch fails silently, which is why tapping a tab offline does nothing at all.
 *
 * Rather than draw a second bar - which would duplicate the real one and drift
 * out of step the moment Facebook changes anything - this attaches a handler to
 * the buttons that are **already there** and sends them to the stored copy of
 * that screen. Nothing is added to the page.
 *
 * Three rules keep it from ever interfering with normal use:
 *
 *  - it is installed **only when the document was served from the offline
 *    store**. Online, Facebook's action ids run untouched.
 *  - it attaches once per document, guarded by a flag, so a repeated injection
 *    cannot double-handle a tap.
 *  - it matches on `aria-label`, which the captures show is stable and which
 *    Facebook cannot change without breaking their own accessibility.
 */
object OfflineNav {

    /**
     * Maps the label on a control to the screen key used by [OfflineDocs].
     * Kept here rather than in the script so both sides cannot disagree.
     */
    private val ROUTES = listOf(
        "home" to listOf("home", "news feed", "facebook home"),
        "reels" to listOf("reels", "video", "watch", "reels and short videos"),
        "stories" to listOf("stories", "story", "create story"),
        "watch" to listOf("watch", "video"),
        "notifications" to listOf("notifications"),
        "friends" to listOf("friends", "friend requests"),
        "marketplace" to listOf("marketplace"),
        "menu" to listOf("menu", "see more")
    )

    /**
     * @param savedScreens the screens actually held on disk. A tab with
     *                     nothing stored is dimmed rather than left silently
     *                     dead, so the user can see what is available.
     */
    fun script(savedScreens: List<String>): String {
        val saved = savedScreens.joinToString(",") { "\"$it\"" }
        val routes = ROUTES.joinToString(",") { (screen, labels) ->
            "{s:\"$screen\",l:[" + labels.joinToString(",") { "\"$it\"" } + "]}"
        }
        return """
        (function(){
          if (window.__dbOfflineNav) return;
          window.__dbOfflineNav = true;

          var SAVED = [$saved];
          var ROUTES = [$routes];

          var URLS = {
            home: 'https://m.facebook.com/',
            reels: 'https://m.facebook.com/reel/',
            stories: 'https://m.facebook.com/stories/',
            watch: 'https://m.facebook.com/watch/',
            notifications: 'https://m.facebook.com/notifications/',
            friends: 'https://m.facebook.com/friends/',
            marketplace: 'https://m.facebook.com/marketplace/',
            menu: 'https://m.facebook.com/menu/'
          };

          function has(s) {
            for (var i = 0; i < SAVED.length; i++) if (SAVED[i] === s) return true;
            return false;
          }

          /** Which stored screen a control leads to, or null. */
          function routeFor(el) {
            var label = (el.getAttribute('aria-label') || '').toLowerCase().trim();
            if (!label) return null;
            for (var i = 0; i < ROUTES.length; i++) {
              var r = ROUTES[i];
              for (var j = 0; j < r.l.length; j++) {
                if (label === r.l[j] || label.indexOf(r.l[j]) === 0) return r.s;
              }
            }
            return null;
          }

          /**
           * Deliberately does nothing to how the bar looks.
           *
           * This used to fade a tab we hold nothing for. That fade was ours,
           * not Facebook's, so the bar looked different offline than online -
           * the one thing this must never do. A tab with nothing stored is
           * simply left alone; tapping it is handled below.
           */
          function mark() {}

          // One listener, in the capture phase, so the tap is taken before
          // Facebook's own handler tries to resolve a dead action id.
          document.addEventListener('click', function(ev){
            var el = ev.target;
            // Navigation lives in the moved chrome - the tab bar, the
            // tray teasers - never inside saved content. Every reel's
            // player wrapper carries aria-label="Video player", and
            // "video" is a reels/watch tab label matched by prefix, so
            // from inside #__db_cards this climbup claimed the player's
            // tap as a reels route and onOfflineNav loadUrl()'ed the
            // SAME screen: every tap on the picture was a silent full
            // rebuild instead of a pause ("reels play te ektai problem
            // just pause hoi na tap korleo", round 27 - proven with the
            // served script stack in served order). Content taps are
            // never navigation.
            if (el && el.closest && el.closest('#__db_cards')) return;
            for (var depth = 0; el && depth < 8; depth++) {
              if (el.getAttribute && el.getAttribute('aria-label')) {
                var route = routeFor(el);
                if (route) {
                  ev.preventDefault();
                  ev.stopPropagation();
                  if (has(route)) {
                    // Hand the navigation to the app rather than driving
                    // location from the page. The app already owns loading -
                    // it can keep the WebView's history sensible, and a
                    // page-driven navigation inside a document we synthesised
                    // is easy to get subtly wrong.
                    if (window.FBPro && FBPro.onOfflineNav) {
                      try { FBPro.onOfflineNav(route, URLS[route]); return; }
                      catch (e) {}
                    }
                    location.assign(URLS[route]);
                  } else if (window.FBPro && FBPro.onOfflineNavMissing) {
                    try { FBPro.onOfflineNavMissing(route); } catch (e) {}
                  }
                  return;
                }
              }
              el = el.parentElement;
            }
          }, true);

          try {
            new MutationObserver(mark)
              .observe(document.documentElement, { childList:true, subtree:true });
          } catch (e) {}

          mark();
        })();
        """.trimIndent()
    }
}

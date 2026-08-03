package com.dustbook.app.utils

/**
 * Pull-to-refresh without a page load.
 *
 * `WebView.reload()` throws the whole document away, so Facebook re-runs its
 * bootstrap and paints its own blue splash - the app looks like a browser tab
 * every time the user pulls down. The native app just refetches the feed and
 * keeps its shell on screen.
 *
 * Facebook already knows how to do that: the mobile renderer puts a
 * pull-to-refresh action id on its scroller, and the tab bar performs
 * client-side screen swaps. We ask the page to refresh itself, and only fall
 * back to a real reload when the page offers us no way to.
 *
 * The result is reported back through `FBPro.onSoftRefresh(handled)` so the
 * spinner is dismissed at the right moment either way.
 */
object SoftRefresh {

    fun script(): String = """
        (function(){
          var bridge = window.FBPro;
          function done(ok) {
            try { if (bridge && bridge.onSoftRefresh) bridge.onSoftRefresh(!!ok); } catch (e) {}
          }

          function visible(el) {
            if (!el) return false;
            var r = el.getBoundingClientRect();
            return r.width > 0 && r.height > 0;
          }

          // 1. The renderer's own pull-to-refresh. This is exactly what the
          //    site does when you drag its feed down, so the result is
          //    identical to a native refresh - no bootstrap, no splash.
          try {
            var sc = document.querySelector('[data-pull-to-refresh-action-id]');
            if (sc) {
              var id = sc.getAttribute('data-pull-to-refresh-action-id');
              if (id && window.require) {
                // The lite renderer dispatches actions through a tap on the
                // owning node; synthesising that is more reliable than
                // reaching into its internals, which are obfuscated.
                sc.dispatchEvent(new CustomEvent('pulltorefresh',
                                                 { bubbles: true }));
              }
            }
          } catch (e) {}

          // 2. Re-tap the tab the user is already on. Facebook treats that as
          //    "refresh this section" and swaps the screen client-side.
          try {
            var sel = [
              '[role="tab"][aria-selected="true"]',
              '[role="link"][aria-current="page"]',
              '[aria-current="page"]'
            ];
            for (var i = 0; i < sel.length; i++) {
              var tab = document.querySelector(sel[i]);
              if (visible(tab)) {
                tab.click();
                // Take the user back to the top, the way the native app does.
                try { window.scrollTo(0, 0); } catch (e) {}
                done(true);
                return;
              }
            }
          } catch (e) {}

          // 3. Scroll to top and let Facebook's own infinite-scroll logic
          //    refill. Still no document load, so still no splash.
          try {
            var scroller = document.querySelector('[data-type="vscroller"]');
            if (scroller && scroller.scrollTop > 0) {
              scroller.scrollTop = 0;
              window.scrollTo(0, 0);
              done(true);
              return;
            }
            if (window.scrollY > 0) {
              window.scrollTo(0, 0);
              done(true);
              return;
            }
          } catch (e) {}

          // Nothing to work with. Tell the app to do a real reload.
          done(false);
        })();
    """.trimIndent()
}

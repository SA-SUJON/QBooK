package org.qbook.utils

/**
 * Appended to a stored page when it is served with no connection.
 *
 * Deliberately almost empty.
 *
 * An earlier version dimmed every control that needed the network and popped
 * a toast when one was tapped. Both were mine, not Facebook's, so the offline
 * page did not look like the online one - dimmed buttons, a floating message,
 * a layout that had been interfered with. Offline has to be indistinguishable
 * from online except that new content does not arrive.
 *
 * So nothing is dimmed, nothing is added on top, and nothing is restyled. The
 * only thing left is swallowing the taps that would fail: a like that silently
 * does nothing is better than one that spins forever, and either way the page
 * looks exactly as Facebook drew it. The offline state is already reported by
 * the app's own banner outside the WebView.
 */
object OfflineBanner {

    fun html(): String = """
        <script id="__db_off_js">
        (function(){
          if (window.__dbOffline) return;
          window.__dbOffline = true;

          // Anything that writes to Facebook cannot work without a
          // connection. Swallow the gesture so the page does not sit waiting
          // on a request that will never return. Nothing is hidden, dimmed or
          // moved - the markup stays exactly as it was served.
          var WRITE = [
            '[aria-label*="Like" i]', '[aria-label*="Comment" i]',
            '[aria-label*="Share" i]', '[aria-label*="Send" i]',
            '[aria-label*="React" i]', '[aria-label*="Follow" i]',
            '[data-sigil*="like"]', '[data-sigil*="comment"]',
            'form', 'textarea', 'input[type="submit"]'
          ].join(',');

          document.addEventListener('click', function(ev){
            var t = ev.target;
            var el = t && t.closest ? t.closest(WRITE) : null;
            if (!el) return;
            ev.preventDefault();
            ev.stopPropagation();
          }, true);

          document.addEventListener('submit', function(ev){
            ev.preventDefault();
          }, true);
        })();
        </script>
    """.trimIndent()
}

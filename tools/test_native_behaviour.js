#!/usr/bin/env node
/**
 * Guards for the three "act like an app, not a browser tab" behaviours:
 *
 *  1. pull-to-refresh must not reload the document (that is what makes
 *     Facebook paint its own splash screen)
 *  2. the offline capture script must only ever read the DOM, never mutate it,
 *     so it cannot fight the ad remover
 *  3. session state must be restored from disk, not only from the bundle
 *
 * Each of these regressed once already, so they are asserted here rather than
 * left to a manual check on the device.
 */
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const ROOT = path.join(__dirname, '..');
const KT = (f) => path.join(ROOT, 'app/src/main/java/com/dustbook/app', f);

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  ok   ' + name); }
  else { fail++; console.log('  FAIL ' + name + (extra ? ' :: ' + extra : '')); }
}

/** Pull the Kotlin raw string out of a `fun x(...): String = """ ... """`. */
function rawString(file, marker) {
  const src = fs.readFileSync(file, 'utf8');
  const i = src.indexOf(marker);
  if (i < 0) throw new Error('marker not found: ' + marker);
  const start = src.indexOf('"""', i) + 3;
  const end = src.indexOf('"""', start);
  return src.slice(start, end)
    .replace(/\$\{'"'\}/g, '"')
    .replace(/\$\{'\$'\}/g, '$')
    .replace(/\$\{'\\\$'\}/g, '$');
}

// ---------------------------------------------------------------- soft refresh
console.log('\nSoftRefresh');
{
  const src = fs.readFileSync(KT('utils/SoftRefresh.kt'), 'utf8');
  ok('never calls location.reload', !/location\s*\.\s*reload/.test(src));
  ok('never assigns location.href', !/location\s*\.\s*href\s*=/.test(src));
  ok('reports back through onSoftRefresh', src.includes('onSoftRefresh'));

  const js = rawString(KT('utils/SoftRefresh.kt'), 'fun script()')
    .replace(/^\s*\$(\w+)/gm, '');

  // A page with a selected tab: refresh by re-tapping it, no reload.
  {
    const dom = new JSDOM(
      '<body><div role="tab" aria-selected="true" style="width:10px">Home</div></body>',
      { runScripts: 'outside-only', pretendToBeVisual: true }
    );
    let handled = null, clicked = false;
    dom.window.FBPro = { onSoftRefresh: (h) => { handled = h; } };
    const tab = dom.window.document.querySelector('[role="tab"]');
    tab.getBoundingClientRect = () => ({ width: 10, height: 10 });
    tab.addEventListener('click', () => { clicked = true; });
    dom.window.eval(js);
    ok('re-taps the current tab', clicked);
    ok('reports handled=true', handled === true);
  }

  // A page with nothing to work with: ask the app for a real reload.
  {
    const dom = new JSDOM('<body><p>nothing here</p></body>',
      { runScripts: 'outside-only', pretendToBeVisual: true });
    let handled = null;
    dom.window.FBPro = { onSoftRefresh: (h) => { handled = h; } };
    dom.window.eval(js);
    ok('falls back when the page cannot refresh itself', handled === false);
  }

  // No bridge at all must not throw.
  {
    const dom = new JSDOM('<body></body>',
      { runScripts: 'outside-only', pretendToBeVisual: true });
    let threw = false;
    try { dom.window.eval(js); } catch (e) { threw = true; }
    ok('survives a missing bridge', !threw);
  }
}

// ------------------------------------------------------------ offline capture
console.log('\nOfflineCapture');
{
  const src = fs.readFileSync(KT('utils/OfflineCapture.kt'), 'utf8');
  ok('does not remove nodes', !/\.remove\(\)|removeChild/.test(src));
  ok('does not rewrite innerHTML', !/innerHTML\s*=/.test(src));
  // Cards are stored as their own outerHTML - a few KB each.
  //
  // The whole document is captured too, but only once per screen and only
  // because Facebook answers m.facebook.com with HTTP 400 to any plain HTTP
  // client, so a live WebView is the only place that page can be obtained.
  // The earlier failure was sending the document on every scroll tick.
  ok('cards are stored as their own markup',
     /markupOf/.test(src) && /outerHTML/.test(src));
  ok('the document is sent at most once per screen',
     /if \(pageSent\) return;/.test(src));
  ok('and only when it is a real screen, not a skeleton',
     /html\.length < 20000/.test(src));

  const js = rawString(KT('utils/OfflineCapture.kt'), 'fun script(')
    .replace('$reelTarget', '50')
    .replace('$MAX_CARD_CHARS', '120000')
    .replace('${knownIds.asJsSet()}', '')
    .replace(/\$\{if \(syncMode\)[^}]*\}/, 'false');

  const html = `<html><body><div data-type="vscroller">
    <div class="story" data-video-id="1">
      <video src="https://video.fcgp1-1.fbcdn.net/reel1.mp4"></video>
      <span>first reel caption</span>
    </div>
    <div class="story" data-video-id="2">
      <img src="https://scontent.fcgp1-1.fbcdn.net/photo.jpg">
      <span>a photo post</span>
    </div>
    <div class="story"><span>x</span></div>
  </div></body></html>`;

  const dom = new JSDOM(html, {
    runScripts: 'outside-only', pretendToBeVisual: true,
    url: 'https://m.facebook.com/reel/111'
  });

  let got = null;
  dom.window.FBPro = {
    onOfflineItems: (s, json, done) => { got = { s, items: JSON.parse(json), done }; }
  };
  dom.window.eval(js);
  Object.defineProperty(dom.window.document, 'visibilityState',
    { value: 'hidden', configurable: true });
  dom.window.eval('document.dispatchEvent(new Event("visibilitychange"))');

  ok('reported items', got !== null && got.items.length > 0, String(got));
  if (got) {
    ok('named the reels section', got.s === 'reels', got.s);
    ok('kept the reel markup',
       got.items.some((i) => /reel1\.mp4/.test(i.h)));
    ok('kept the photo markup',
       got.items.some((i) => /photo\.jpg/.test(i.h)));
    ok('listed the media to download',
       got.items.some((i) =>
         i.m.indexOf('https://video.fcgp1-1.fbcdn.net/reel1.mp4') >= 0));
    ok('skipped the empty card', got.items.length === 2,
       String(got.items.length));
    ok('payload is a sensible size',
       JSON.stringify(got.items).length < 20000);
  }
}

// -------------------------------------------------------------- session state
console.log('\nSessionState');
{
  const ss = fs.readFileSync(KT('utils/SessionState.kt'), 'utf8');
  ok('writes atomically', ss.includes('.part') && ss.includes('renameTo'));
  ok('expires stale state', ss.includes('MAX_AGE_MS'));
  ok('caps the blob size', /1024 \* 1024/.test(ss));

  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  ok('restores from disk when the bundle is gone',
     ma.includes('SessionState.restore(this)'));
  ok('persists on pause', /onPause[\s\S]{0,600}persistSession\(\)/.test(ma));

  // A restore repaints from history and issues no request, so a page saved
  // while the connection was down came back on every launch afterwards --
  // Facebook's own "Can't load the page" screen, with a working connection,
  // escapable only by Reset app. Both ends are now guarded.
  ok('a restored state is validated before it is trusted',
     /fun isUsable\(/.test(ss));
  ok('only Facebook pages count as usable',
     /facebook\.com/.test(ss.slice(ss.indexOf('fun isUsable'))));
  ok('about:blank and data: are rejected',
     /about:blank/.test(ss) && /startsWith\("data:"\)/.test(ss));
  ok('cold start checks the restored url',
     /SessionState\.isUsable\(restoredUrl\)/.test(ma));
  ok('an unusable restore falls through to a real load',
     /if \(restoredUrl != null\)[\s\S]{0,300}SessionState\.clear\(this\)/.test(ma) &&
     /binding\.webView\.loadUrl\(target\)/.test(ma));
  ok('an unusable page is never written to disk',
     /persistSession[\s\S]{0,500}!SessionState\.isUsable\(binding\.webView\.url\)/.test(ma));

  // Reset app has to remove the saved history too. It lives in filesDir, so
  // deleting cacheDir left it behind and the reset was not a reset.
  {
    const sa = fs.readFileSync(KT('ui/SettingsActivity.kt'), 'utf8');
    const body = sa.slice(sa.indexOf('private fun clearAllData'));
    ok('Reset app clears the saved WebView history',
       /SessionState\.clear\(ctx\)/.test(body.slice(0, 900)));
    ok('SettingsActivity imports SessionState',
       /import com\.dustbook\.app\.utils\.SessionState/.test(sa));
  }
  ok('pull-to-refresh no longer reloads directly',
     !/setOnRefreshListener\s*\{\s*binding\.webView\.reload\(\)/.test(ma));
  // The offline screen is Facebook's stored document now, not a page of ours.
  ok('serves the stored document for the main frame',
     ma.includes('OfflineDocs.serve') && ma.includes('isForMainFrame'));
  ok('View saved content renders the store, not a dead reload',
     ma.includes('showSavedContent') &&
     !/errorOffline\.setOnClickListener[\s\S]{0,300}loadUrl\(last\)/.test(ma));
  // Only a stored Facebook page counts now; cards alone have nothing to be
  // rendered inside except a page of our own, which is what was removed.
  ok('the button only appears when a stored page exists',
     /fun hasAnythingOffline[\s\S]{0,400}savedScreens\(\)\.isNotEmpty\(\)/.test(ma));
  ok('reels download in the background', ma.includes('maybeSyncOffline'));
}

// --------------------------------------------------------------- offline feed
console.log('\nOfflineFeed');
{
  const of = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');
  ok('only stores facebook media',
     of.includes('fbcdn.net') && of.includes('fbsbx.com'));
  ok('only offers items whose media is on disk',
     /media\.any \{ OfflineCache\.has\(it\) \}/.test(of));
  // Drawing our own page is exactly what lost the real controls.
  ok('does not render a page of its own', !of.includes('fun renderPage'));
  ok('de-duplicates and caps the store',
     of.includes('seen.add') && of.includes('limit'));
  ok('refuses encoded responses', of.includes('Accept-Encoding') &&
     of.includes('identity'));
  ok('does not queue overlapping prefetch passes',
     of.includes('compareAndSet'));
  ok('honours the include-video setting', of.includes('includeVideo'));
}

// ---------------------------------------------------------------- sync
console.log('\nOfflineSync');
{
  const os = fs.readFileSync(KT('utils/OfflineSync.kt'), 'utf8');
  ok('runs at most one pass at a time', os.includes('running'));
  ok('is throttled', os.includes('MIN_INTERVAL_MS'));
  ok('always tears the WebView down', os.includes('destroy()') &&
     os.includes('TIMEOUT_MS'));
  ok('strips ads before capturing', os.includes('MFacebookAds.script()'));
  ok('respects the request blocklist', os.includes('shouldBlockRequest'));
  ok('needs a signed-in session', os.includes('isLoggedIn'));
}

// ------------------------------------------------------------ video playback
console.log('\nVideo playback');
{
  // Nothing here reshapes the player any more. Several releases tried to
  // remove the browser's placeholder frame from inside the page, and each
  // attempt traded one bug for another: display:none took the button out of a
  // flex container and shifted every reel upwards, pointer-events:none left
  // stories stuck, and replacing the player outright lost the like, comment,
  // share and seek controls that come with it. The page's own player is left
  // exactly as Facebook ships it.
  const files = fs.readdirSync(path.join(ROOT, 'app/src/main/java/com/dustbook/app/utils'));
  ok('no video polish script', !files.includes('VideoPolish.kt'));
  ok('no native video bridge', !files.includes('NativeVideo.kt'));
  ok('no native video surface',
     !fs.existsSync(KT('ui/NativeVideoLayer.kt')));

  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  ok('nothing is injected against the player',
     !/VideoPolish|NativeVideo/.test(ma));
  ok('the autoplay preference is honoured again',
     /mediaPlaybackRequiresUserGesture = !prefs\.autoplayVideo/.test(ma));

  const of = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');
  // Saved cards are Facebook's own markup, so their players are Facebook's
  // players - there is nothing of ours for controls to be missing from.
  ok('saved cards are stored markup', /val html: String/.test(of));
  ok('no control-stripping css remains',
     !/media-controls|inline-video-icon/.test(of));
}

// -------------------------------------------------------- offline looks real
console.log('\nOffline looks like online');
{
  const od = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');
  const ob = fs.readFileSync(KT('utils/OfflineBanner.kt'), 'utf8');
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');

  ok('stores whole Facebook documents', od.includes('text/html') &&
     od.includes('fun serve'));
  ok('keeps the tab bar screens', od.includes('notifications') &&
     od.includes('marketplace') && od.includes('friends'));
  ok('sends the session cookie', od.includes('getCookie'));
  ok('never stores a logged-out page', od.includes('type=\\"password'));
  ok('refuses encoded responses',
     od.includes('Accept-Encoding') && od.includes('identity'));
  ok('rewrites root-relative assets', od.includes('fun rewriteForOffline'));
  ok('writes atomically', od.includes('.part') && od.includes('renameTo'));
  ok('caps the document size', od.includes('MAX_DOC_BYTES'));

  ok('offline serves the stored document first',
     /OfflineDocs\.serve\(request\)/.test(ma));
  // Any saved content counts now, not only a stored page - a store full of
  // reels used to still show "Can't load the page".
  ok('no error page when anything is saved',
     /onReceivedError[\s\S]{0,1200}hasAnythingOffline\(\)[\s\S]{0,120}showSavedContent/
       .test(ma));
  ok('assets for stored pages are downloaded too',
     ma.includes('OfflineDocs.mediaUrls'));
  ok('media is fetched in one pass, not dropped',
     ma.includes('flatMap') && ma.includes('prefetchUrls'));

  // Video is fetched with Range headers, always. Refusing to intercept them
  // meant every offline video request went to a dead network.
  const oc = fs.readFileSync(KT('utils/OfflineCache.kt'), 'utf8');
  ok('range requests are answered offline', oc.includes('fun range('));
  ok('range replies use 206 with Content-Range',
     oc.includes('206') && oc.includes('Content-Range'));
  ok('range requests are no longer refused outright',
     !/equals\("Range", true\) \} \) return false/.test(oc));
  ok('main activity routes ranges to the store',
     /Range[\s\S]{0,200}OfflineCache\.range\(request\)/.test(ma));
  ok('a reel-sized file is not dropped by the size cap',
     /MAX_ENTRY = 60L/.test(oc));

  // Facebook serves a different renderer per user agent, so a page fetched
  // without one is not the page the WebView would have been given.
  ok('stored pages are fetched with the webview user agent',
     od.includes('userAgent?.let') && ma.includes('OfflineDocs.userAgent'));
  ok('the user agent is read after the settings are applied',
     /applyWebSettings\(\)[\s\S]{0,900}OfflineDocs\.userAgent/.test(ma));
  ok('a stub page is never stored as a screen', od.includes('MIN_DOC_BYTES'));
  ok('why a screen is missing can be seen', od.includes('fun statusLine'));

  ok('write actions are blocked, not hidden',
     ob.includes('preventDefault') && !/display:\s*none/.test(ob));
  // Offline must be indistinguishable from online. Dimming and a toast were
  // both ours; the real site has neither, so they are gone.
  ok('layout is left identical to online',
     !/opacity/.test(ob) && !/<div/.test(ob) && !/<style/.test(ob));
  ok('a dead tap says nothing, as it would online',
     !/__db_toast/.test(ob));
}

// ------------------------------------------------------- automatic downloads
console.log('\nAutomatic downloads');
{
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  const os = fs.readFileSync(KT('utils/OfflineSync.kt'), 'utf8');
  const pr = fs.readFileSync(KT('utils/Prefs.kt'), 'utf8');
  const sx = fs.readFileSync(
    path.join(ROOT, 'app/src/main/res/xml/settings_offline.xml'), 'utf8');

  ok('no manual download button', !sx.includes('offline_sync_now'));
  ok('the feed can be saved as well as reels',
     sx.includes('offline_feed') && pr.includes('offlineFeed'));
  // V4 moved the queueing into OfflineManager, which MainActivity delegates
  // to; the sections are chosen there rather than inline.
  const mgrPath = KT('utils/OfflineManager.kt');
  const mgr = fs.existsSync(mgrPath) ? fs.readFileSync(mgrPath, 'utf8') : '';
  ok('both sections are queued',
     (mgr.includes('SECTION_FEED') && mgr.includes('SECTION_REELS') &&
      mgr.includes('runAll')) ||
     (ma.includes('SECTION_FEED') && ma.includes('SECTION_REELS') &&
      ma.includes('runAll')));
  ok('sections run one at a time, not in parallel',
     os.includes('fun runAll') && os.includes('step('));
  ok('the count is still user-selectable',
     sx.includes('offline_reel_count') && /coerceIn\(\d+, \d+\)/.test(pr));

  // The offered values, the default and the clamp all have to agree: a
  // ListPreference whose default is missing from entryValues shows no
  // summary and nothing selected, and a value outside the clamp is silently
  // changed under the user.
  {
    const strings = fs.readFileSync(
      path.join(ROOT, 'app/src/main/res/values/strings.xml'), 'utf8');
    const block = strings.slice(strings.indexOf('reel_count_values'));
    const values = [...block.slice(0, block.indexOf('</string-array>'))
      .matchAll(/<item>(\d+)<\/item>/g)].map((m) => Number(m[1]));
    const clamp = pr.match(/coerceIn\((\d+), (\d+)\)/);
    // There may be more than one coerceIn now (e.g. appIcon). Find the one
    // that caps the reel count, which must be the one with lo >= 30.
    let lo = 0, hi = 0;
    for (const m of pr.matchAll(/coerceIn\((\d+), (\d+)\)/g)) {
      const l = Number(m[1]), h = Number(m[2]);
      if (l >= 30) { lo = l; hi = h; break; }
    }
    const def = Number((sx.match(/offline_reel_count[\s\S]{0,400}?defaultValue="(\d+)"/) || [])[1]);

    ok('every offered value survives the clamp',
       values.every((v) => v >= lo && v <= hi),
       values.join(',') + ' vs ' + lo + '..' + hi);
    ok('the default is one of the offered values',
       values.includes(def), def + ' not in ' + values.join(','));
  }
  ok('the count applies even with reels off',
     !/if \(!offlineReels\) return 0/.test(pr));
  ok('progress is visible in settings', sx.includes('offline_status'));
}

// ------------------------------------------------------- logging in only once
//
// Facebook posts the sign-in form to /login/device-based/regular/login/ and
// redirects via /login/?next=..., so isAuthPage() is true for the very page
// that hands over the session. While evaluateAuthState tested byUrl before
// the cookie, onAuthPage was re-latched to true the moment the credentials
// were accepted: the native screen reappeared with empty fields and the user
// had to log in a second time.
console.log('\nLogging in once');
{
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  const uh = fs.readFileSync(KT('utils/UrlHelper.kt'), 'utf8');

  const i = ma.indexOf('private fun evaluateAuthState');
  ok('evaluateAuthState exists', i >= 0);
  const body = ma.slice(i, i + 2200);

  const whenBlock = body.slice(body.indexOf('val auth = when'),
                               body.indexOf('}', body.indexOf('val auth = when')));
  const pSignedIn = whenBlock.indexOf('signedIn ->');
  const pByUrl    = whenBlock.indexOf('byUrl ->');
  ok('both branches are still present', pSignedIn >= 0 && pByUrl >= 0);
  ok('the session cookie is tested before the URL',
     pSignedIn >= 0 && pByUrl >= 0 && pSignedIn < pByUrl,
     'signedIn at ' + pSignedIn + ', byUrl at ' + pByUrl);
  ok('a live session still means not-an-auth-page',
     /signedIn\s*->\s*false/.test(whenBlock));

  // Model the decision exactly as the Kotlin evaluates it, then replay the
  // real sequence a login goes through.
  const authSegments = ['login', 'reg', 'signup', 'checkpoint', 'recover',
                        'confirmemail', 'two_step_verification', 'authentication'];
  const isAuthPage = (u) => {
    if (!u) return false;
    const path = u.split('?')[0].replace(/^https?:\/\/[^/]+/, '');
    const first = path.replace(/^\/+|\/+$/g, '').split('/')[0].toLowerCase();
    return authSegments.includes(first) ||
           ['r.php', 'reg.php', 'login.php'].includes(first);
  };
  // Order taken from the source so the model cannot drift from the code.
  const cookieFirst = pSignedIn < pByUrl;
  const decide = (url, signedIn, domOut) => cookieFirst
    ? (signedIn ? false : (isAuthPage(url) ? true : (domOut || !signedIn)))
    : (isAuthPage(url) ? true : (signedIn ? false : (domOut || !signedIn)));

  // Cold start, no session: the native screen must show.
  ok('cold start with no session shows the login screen',
     decide('https://www.facebook.com/', false, true) === true);

  // Credentials accepted. Facebook is still on its own /login/ POST target
  // and the redirect has not landed yet, but c_user now exists.
  ok('stays signed in on the /login/ POST target once c_user exists',
     decide('https://www.facebook.com/login/device-based/regular/login/', true, false) === false);
  ok('stays signed in through the /login/?next= redirect',
     decide('https://www.facebook.com/login/?next=%2F', true, false) === false);

  // The DOM probe can still be reporting the old logged-out shell here.
  ok('a stale logged-out DOM reading cannot bring the screen back',
     decide('https://www.facebook.com/login/?next=%2F', true, true) === false);

  // Landed on the feed.
  ok('feed with a session is not an auth page',
     decide('https://www.facebook.com/', true, false) === false);

  // Genuine sign-out must still be detected.
  ok('no session on /login/ is still an auth page',
     decide('https://www.facebook.com/login/', false, true) === true);
  ok('no session on a checkpoint is still an auth page',
     decide('https://www.facebook.com/checkpoint/', false, false) === true);

  // A profile named like an auth route must not be mistaken for one.
  ok('a /loginsmith profile is not an auth page',
     isAuthPage('https://www.facebook.com/loginsmith') === false);

  // There are no app-owned credential fields left to clear -- the form lives
  // in the page. The transition still has to land on the homepage, so that a
  // /login/?next= intermediate is never what the user ends up looking at.
  ok('no app-owned password field is left to clear',
     !/loginPassword/.test(ma));
  ok('signing in lands on the homepage',
     /wasAuth && !auth[\s\S]{0,700}loadUrl\(prefs\.homepage\)/.test(body));

  ok('isAuthPage still requires a path-segment boundary',
     /substringBefore\('\/'\)/.test(uh));
}

// -------------------------------------------- a first failure is not an outage
//
// The app worked on Wi-Fi and showed "Can't load the page" on mobile data.
// Wi-Fi is normally associated and resolving before the activity starts; a
// cellular radio often is not, so the very first main-frame request fails with
// ERROR_HOST_LOOKUP / ERROR_CONNECT / ERROR_TIMEOUT. onReceivedError went
// straight to showErrorPage() with no second attempt, so that transient
// failure became a dead end.
console.log('\nTransient network failures');
{
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');

  ok('a transient main-frame error is retried, not shown',
     /isTransientNetworkError\(code\)/.test(ma) &&
     /mainFrameRetries < MAX_MAIN_FRAME_RETRIES/.test(ma));
  ok('the retry classifier covers the cellular codes',
     /ERROR_HOST_LOOKUP/.test(ma) && /ERROR_CONNECT/.test(ma) &&
     /ERROR_TIMEOUT/.test(ma));
  ok('retries are bounded', /MAX_MAIN_FRAME_RETRIES = \d+/.test(ma));

  const cap = Number((ma.match(/MAX_MAIN_FRAME_RETRIES = (\d+)/) || [])[1]);
  ok('the bound is sane', cap >= 2 && cap <= 5, 'cap=' + cap);

  ok('the retry backs off instead of hammering',
     /attempt \* \d+L/.test(ma));
  ok('only retried while the system still reports a connection',
     /isOnline && isTransientNetworkError/.test(ma));
  ok('a genuine outage still reaches the error screen',
     /showErrorPage\(\)/.test(ma));
  ok('the counter resets once a page loads',
     /onPageFinished[\s\S]{0,400}mainFrameRetries = 0/.test(ma));
  ok('pressing Retry resets the counter',
     /errorRetry\.setOnClickListener[\s\S]{0,200}mainFrameRetries = 0/.test(ma));

  // Offline with saved content must still short-circuit before any retry.
  const onErr = ma.slice(ma.indexOf('override fun onReceivedError'));
  const savedAt = onErr.indexOf('showSavedContent()');
  const retryAt = onErr.indexOf('isTransientNetworkError');
  ok('stored content is still preferred over retrying',
     savedAt >= 0 && retryAt >= 0 && savedAt < retryAt);
}

// ------------------------------------------------- background audio and sync
//
// Two bugs with one shape: both asked the URL a question the URL cannot answer.
// Facebook's lite renderer swaps screens in place, so the address bar stays on
// the home feed while a reel plays, and a fresh login produces no further
// onPageFinished for the feed.
console.log('\nBackground audio follows playback, not the URL');
{
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  const ab = fs.readFileSync(KT('utils/AdBlocker.kt'), 'utf8');

  const onPause = ma.slice(ma.indexOf('override fun onPause'),
                           ma.indexOf('override fun onPause') + 1400);
  ok('the audio decision no longer greps the URL',
     !/isReelPage/.test(onPause) &&
     !/url\.contains\("\/reel"\)/.test(onPause));
  ok('it uses reported playback instead',
     /prefs\.backgroundAudio && mediaPlaying/.test(onPause));
  ok('the field exists and is volatile',
     /@Volatile private var mediaPlaying/.test(ma));
  ok('the bridge can receive it',
     /fun onMediaState\(playing: Boolean\)/.test(ma));

  ok('the page reports playback',
     /window\.FBPro\.onMediaState/.test(ab));
  ok('a paused or ended element does not count',
     /!m\.paused && !m\.ended/.test(ab));
  ok('media events are captured, since they do not bubble',
     /addEventListener\(e, tell, true\)/.test(ab));
  ok('a swapped-out reel is caught by a backstop poll',
     /setInterval\(tell,/.test(ab));
}

console.log('\nOffline saving starts on its own');
{
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');

  const starts = (ma.match(/BackgroundSyncManager\.start\(\)/g) || []).length;
  ok('sync can start from more than one place', starts >= 3,
     'call sites: ' + starts);

  const onResume = ma.slice(ma.indexOf('override fun onResume'),
                            ma.indexOf('override fun onResume') + 2000);
  ok('opening the app is enough to begin',
     /BackgroundSyncManager\.start\(\)/.test(onResume));
  ok('and only while there is a connection',
     /isOnline[\s\S]{0,80}BackgroundSyncManager\.start\(\)/.test(onResume));

  const signedIn = ma.slice(ma.indexOf('if (wasAuth && !auth)'),
                            ma.indexOf('if (wasAuth && !auth)') + 900);
  ok('signing in for the first time begins a pass',
     /BackgroundSyncManager\.start\(\)/.test(signedIn));

  ok('the original onPageFinished trigger is still there',
     /onPageFinished[\s\S]{0,1200}BackgroundSyncManager\.start\(\)/.test(ma));

  const bs = fs.readFileSync(KT('utils/BackgroundSyncManager.kt'), 'utf8');
  ok('start() is safe to call repeatedly',
     /fun start\(\)[\s\S]{0,200}if \(isRunning\) return/.test(bs));
  ok('and refuses when signed out or saving is off',
     /!UrlHelper\.isLoggedIn\(\) \) *return|!UrlHelper\.isLoggedIn\(\)\) return/.test(bs) &&
     /!p\.offlineMode\) return/.test(bs));
}

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);

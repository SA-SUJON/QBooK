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
  ok('a muted element does not count as audio',
     /if \(m\.muted\) return false;/.test(ab));
  ok('volume 0 is treated the same as muted',
     /m\.volume === 0\) return false/.test(ab));
  ok('turning the sound on is noticed without waiting for the poll',
     /'volumechange'\s*\]?\s*\)?\s*\n?\s*\.forEach/.test(ab) ||
     /'emptied', 'volumechange'/.test(ab));
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

// ------------------------------------- playback survives the window going away
//
// The foreground service was not enough on its own. Android suspends a
// WebView's media pipeline itself when the window is hidden, below anything
// onPause can reach — which is why a notification appeared and the audio
// stopped anyway.
console.log('\nPlayback survives the window being hidden');
{
  // Read defensively: if the file is gone the assertions must fail, not
  // crash the suite before the remaining sections have run.
  const mw = fs.existsSync(KT('ui/MediaWebView.kt'))
    ? fs.readFileSync(KT('ui/MediaWebView.kt'), 'utf8') : '';
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  const lay = fs.readFileSync(
    path.join(ROOT, 'app/src/main/res/layout/activity_main.xml'), 'utf8');

  ok('the WebView can ignore a hidden window',
     /override fun onWindowVisibilityChanged/.test(mw));
  ok('it only does so when told to',
     /keepMediaAlive && visibility != View\.VISIBLE/.test(mw) &&
     /return\b/.test(mw));
  ok('the default behaviour is still reachable',
     /super\.onWindowVisibilityChanged\(visibility\)/.test(mw));
  ok('the layout actually uses it',
     /com\.dustbook\.app\.ui\.MediaWebView/.test(lay));
  ok('the flag is raised only for real playback',
     /keepMediaAlive = keepAudioAlive/.test(ma) &&
     /prefs\.backgroundAudio && mediaPlaying/.test(ma));
  ok('and lowered again on resume',
     /onResume[\s\S]{0,400}keepMediaAlive = false/.test(ma));
  ok('the service is still started alongside it',
     /keepAudioAlive\)[\s\S]{0,400}startBgAudioService\(\)/.test(ma));
}

console.log('\nDownloading survives the app closing');
{
  const ss = fs.existsSync(KT('ui/SyncService.kt'))
    ? fs.readFileSync(KT('ui/SyncService.kt'), 'utf8') : '';
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  const mf = fs.readFileSync(
    path.join(ROOT, 'app/src/main/AndroidManifest.xml'), 'utf8');

  ok('there is a foreground service for saving',
     /class SyncService : Service\(\)/.test(ss));
  ok('it is declared as a data sync service',
     /android:name="\.ui\.SyncService"/.test(mf) &&
     /android:foregroundServiceType="dataSync"/.test(mf));
  ok('the permission is requested',
     /FOREGROUND_SERVICE_DATA_SYNC/.test(mf));
  ok('it starts wherever a pass starts',
     (ma.match(/SyncService\.startIfNeeded/g) || []).length >= 3);
  ok('it refuses to start when no pass is running',
     /if \(!BackgroundSyncManager\.isRunning\) return/.test(ss));
  ok('it stops itself when the pass finishes',
     /if \(!BackgroundSyncManager\.isRunning\)[\s\S]{0,80}stopSelf\(\)/.test(ss));
  ok('the user can stop it from the notification',
     /ACTION_STOP/.test(ss) && /"Stop"/.test(ss));
  ok('the notification stays out of the way',
     /IMPORTANCE_MIN/.test(ss) && /setSilent\(true\)/.test(ss));
  ok('a refused start does not crash the process',
     /startForeground\(NOTIFICATION_ID[\s\S]{0,400}catch \(e: Exception\)[\s\S]{0,300}stopSelf\(\)/.test(ss));
  ok('it is not resurrected pointlessly',
     /START_NOT_STICKY/.test(ss));
}

// -------------------------------- background audio must not steal the focus
//
// Requesting AUDIOFOCUS_GAIN here looked like the fix for "stops after a few
// seconds" and made it stop instantly instead: AudioManager does not
// special-case two requesters in one process, so granting focus to the service
// sent AUDIOFOCUS_LOSS to the WebView's own player, which paused itself.
console.log('\nBackground audio leaves the focus alone');
{
  const as = fs.existsSync(KT('ui/AudioService.kt'))
    ? fs.readFileSync(KT('ui/AudioService.kt'), 'utf8') : '';
  ok('the service does not request audio focus',
     !/requestAudioFocus/.test(as));
  ok('nor abandons it', !/abandonAudioFocus/.test(as));
  ok('the reason is recorded so it is not tried again',
     /AUDIOFOCUS_LOSS/.test(as));
  ok('it still runs as a media foreground service',
     /startForeground\(NOTIFICATION_ID/.test(as));
}

console.log('\nDownloading survives the task being swiped away');
{
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');

  // pauseTimers() is documented as "a global request, not restricted to just
  // this WebView", so calling it on leaving also froze the offscreen WebView
  // OfflineSync runs. The service stayed up and the notification stayed on
  // screen, which is why this looked like a service problem and was not.
  ok('the global timer pause is not applied while a pass is running',
     /if \(!BackgroundSyncManager\.isRunning\) \{\s*\n\s*binding\.webView\.pauseTimers\(\)/
       .test(ma));
  ok('an idle app still pauses timers as before',
     /pauseTimers\(\)/.test(ma));
  ok('the per-WebView pause is still unconditional',
     /binding\.webView\.onPause\(\)\s*\n\s*if \(!BackgroundSyncManager\.isRunning\)/.test(ma));
  ok('resuming restores them, since that call is global too',
     /onResume[\s\S]{0,400}resumeTimers\(\)/.test(ma));

  const ss = fs.existsSync(KT('ui/SyncService.kt'))
    ? fs.readFileSync(KT('ui/SyncService.kt'), 'utf8') : '';
  ok('the service handles the task being removed',
     /override fun onTaskRemoved/.test(ss));
  ok('and keeps going while a pass is running',
     /onTaskRemoved[\s\S]{0,400}if \(!BackgroundSyncManager\.isRunning\)[\s\S]{0,80}stopSelf/.test(ss));
  ok('it does not call through to the default behaviour',
     !/onTaskRemoved[\s\S]{0,400}super\.onTaskRemoved/.test(ss));
}

console.log('\nVideo starts audible');
{
  const ab = fs.readFileSync(KT('utils/AdBlocker.kt'), 'utf8');
  ok('the page clears the muted flag', /v\.muted = false/.test(ab));
  ok('only before playback has begun',
     /if \(v\.paused \|\| v\.currentTime === 0\)/.test(ab));
  ok('a running muted video is deferred, never unmuted in place',
     /pendingGesture = true/.test(ab));
  ok('and picked up on the next real gesture',
     /touchend/.test(ab) && /function onGesture/.test(ab));
  ok('metadata events are used, which fire before playback',
     /loadedmetadata/.test(ab));
}

console.log('\nOnline layout is not left with stale insets');
{
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  ok('a finished page asks for a fresh inset pass',
     /onPageFinished[\s\S]{0,400}refreshInsetsAfterLoad\(\)/.test(ma) &&
     /fun refreshInsetsAfterLoad\(\)[\s\S]{0,600}requestApplyInsets\(binding\.root\)/.test(ma));
  ok('but not while a video is fullscreen, which would restart it',
     /fun refreshInsetsAfterLoad[\s\S]{0,500}if \(customView != null \|\| inFullscreenTransition\) return/.test(ma));
  const n = (ma.match(/ViewCompat\.requestApplyInsets/g) || []).length;
  // Two: the page-load path and the single shared fullscreen settle. It used
  // to be three because the enter and exit handlers each carried their own
  // copy of the same block.
  ok('every un-suppress point does so', n >= 2, 'found ' + n);
}

// ------------------------------------------------- the feed jumping upwards
//
// Reported as: now and then the feed sits too high, up under the status bar,
// and sometimes goes fullscreen on its own.
//
// Page loads and fullscreen transitions both want insets held still, and they
// shared one boolean. Tap a reel while the feed is still loading and the
// sequence is: onShowCustomView holds and hides the system bars, then
// onPageFinished clears the shared flag while still fullscreen. The next
// inset pass measures the immersive bars - top = 0 - and writes that into the
// root, so when the bars come back the padding stays at zero.
//
// The settle callbacks were also never cancelled. Tapping through reels
// produces exit/enter pairs much closer together than the 500ms delay, so the
// previous transition's callback fired in the middle of the next fullscreen,
// cleared both flags and re-laid out the root under the player.
console.log('\nInset holds are counted, and stale settles are cancelled');
{
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');

  ok('holds are counted, not a single shared boolean',
     /private var insetHolds = 0/.test(ma) &&
     /private fun holdInsets\(\)/.test(ma) &&
     /private fun releaseInsets\(\)/.test(ma));
  ok('suppression only lifts when the last holder releases',
     /if \(insetHolds == 0\) suppressInsets = false/.test(ma));
  // The only writes are the declaration's initialiser and the two inside
  // holdInsets/releaseInsets. Anything else means a caller is bypassing the
  // count, which is exactly how the two paths used to clobber each other.
  ok('nothing writes the flag outside the hold helpers',
     (ma.match(/suppressInsets = (true|false)/g) || []).length === 3 &&
     /private fun holdInsets\(\) \{[\s\S]{0,120}suppressInsets = true/.test(ma) &&
     /private fun releaseInsets\(\) \{[\s\S]{0,200}suppressInsets = false/.test(ma));

  ok('a page load takes and returns exactly one hold',
     /pageLoadHoldsInsets = true; holdInsets\(\)/.test(ma) &&
     /pageLoadHoldsInsets = false; releaseInsets\(\)/.test(ma));
  ok('so finishing a load cannot release the fullscreen hold',
     /if \(pageLoadHoldsInsets\)/.test(ma));

  ok('a new transition cancels the previous settle',
     /private fun beginFullscreenTransition\(\)[\s\S]{0,400}removeCallbacks/.test(ma));
  ok('the settle is held so it can be cancelled',
     /private var fullscreenSettle: Runnable\? = null/.test(ma));
  // Three each: the declaration and the two call sites (enter and exit).
  ok('entering and leaving both go through the pair',
     (ma.match(/beginFullscreenTransition\(\)/g) || []).length === 3 &&
     (ma.match(/endFullscreenTransition\(\)/g) || []).length === 3 &&
     /override fun onShowCustomView[\s\S]{0,1800}beginFullscreenTransition\(\)/.test(ma) &&
     /override fun onHideCustomView[\s\S]{0,400}beginFullscreenTransition\(\)/.test(ma));
  ok('the settle refuses to measure while still fullscreen',
     /private fun endFullscreenTransition[\s\S]{0,900}if \(!suppressInsets && customView == null\)/
       .test(ma));

  // The listener is the last line of defence: the system dispatches its own
  // passes when the bars animate, long after any transition has settled.
  // The real fix, and the reason the flags alone were never going to be
  // enough: getInsets() reports zero the moment the bars are hidden, so any
  // pass taken during immersive playback - or during either animation - said
  // "no status bar" and that is what landed in the padding. The system keeps
  // dispatching its own passes long after a transition settles, so no amount
  // of suppression can guarantee the last one is a good measurement.
  //
  // getInsetsIgnoringVisibility reports the space the bars occupy when shown,
  // which does not change while they are hidden.
  ok('system bars are measured ignoring their visibility',
     /getInsetsIgnoringVisibility\(\s*WindowInsetsCompat\.Type\.systemBars\(\)\s*\)/.test(ma));
  ok('the bars are never measured the old way',
     !/getInsets\(WindowInsetsCompat\.Type\.systemBars\(\)\)/.test(ma));
  ok('but the keyboard still is, because it really does come and go',
     /getInsets\(WindowInsetsCompat\.Type\.ime\(\)\)/.test(ma));

  {
    // Drive both APIs through a full enter/exit and compare.
    const BAR = 63;
    const visibility = [true, true, false, false, false, false, true];
    let oldPad = BAR, newPad = BAR;
    const zeroed = { old: 0, fresh: 0 };
    for (const visible of visibility) {
      oldPad = visible ? BAR : 0;      // getInsets
      newPad = BAR;                    // getInsetsIgnoringVisibility
      if (oldPad === 0) zeroed.old++;
      if (newPad === 0) zeroed.fresh++;
    }
    ok('the old measurement collapsed to zero mid-transition', zeroed.old > 0,
       'zero readings: ' + zeroed.old);
    ok('the new one never does', zeroed.fresh === 0);
    ok('so the padding is correct at every step', newPad === BAR);
  }

  // Stronger than the old guard, which had the listener bail out while a
  // video was fullscreen. That was only needed because the padding lived on
  // root, which is the parent of BOTH the feed and the fullscreen container -
  // so it had to drop to zero for fullscreen and be put back afterwards, and
  // any missed pass left the feed padded with zero. Scrolling appeared to fix
  // it because a scroll forces a fresh layout.
  //
  // The padding now sits on contentRoot, a sibling of the video container, so
  // it never has to move at all.
  ok('the feed is padded, not the root that also holds the video',
     /setOnApplyWindowInsetsListener\(binding\.contentRoot\)/.test(ma));
  ok('the error screen is padded too, being a sibling as well',
     /setOnApplyWindowInsetsListener\(binding\.errorView\)/.test(ma));
  ok('and its own layout padding is added to, not thrown away',
     /val errorBasePadding = binding\.errorView\.paddingTop/.test(ma) &&
     /top = errorBasePadding \+ bars\.top/.test(ma));
  ok('root itself is never padded any more',
     !/setOnApplyWindowInsetsListener\(binding\.root\)/.test(ma));

  // GONE un-measures the feed, so it is re-measured on the way back - while
  // the bars are still animating in, which gives it the full screen height.
  // The page then stays laid out for a viewport it does not have.
  ok('the feed stays measured while the video is fullscreen',
     /binding\.contentRoot\.visibility = View\.INVISIBLE/.test(ma) &&
     !/binding\.contentRoot\.visibility = View\.GONE/.test(ma));

  {
    // Model the two arrangements across one fullscreen episode.
    const SCREEN = 2400, STATUS = 63, NAV = 48;
    const correct = SCREEN - STATUS - NAV;

    // Padding on root: it must fall to zero for fullscreen, and the restore
    // is a moving part that can be missed.
    const onRoot = (restorePassArrives) => {
      let pad = { top: STATUS, bottom: NAV };
      pad = { top: 0, bottom: 0 };                 // fullscreen needs it gone
      if (restorePassArrives) pad = { top: STATUS, bottom: NAV };
      return SCREEN - pad.top - pad.bottom;
    };
    ok('with padding on root a missed pass leaves the feed wrong',
       onRoot(false) !== correct);
    ok('and it is only right when the restore happens to land',
       onRoot(true) === correct);

    // Padding on contentRoot: never changes, so there is nothing to miss.
    const onContent = (restorePassArrives) => {
      const pad = { top: STATUS, bottom: NAV };    // untouched by fullscreen
      return SCREEN - pad.top - pad.bottom;
    };
    ok('with padding on the feed it is right either way',
       onContent(false) === correct && onContent(true) === correct);

    // GONE vs INVISIBLE for the feed itself.
    const height = (mode) => mode === 'GONE' ? SCREEN : correct;
    ok('GONE brings the feed back at the wrong height',
       height('GONE') !== correct);
    ok('INVISIBLE brings it back at the height it left at',
       height('INVISIBLE') === correct);
  }

  // Behavioural: run the real ordering.
  const App = class {
    constructor() {
      this.holds = 0; this.suppress = false; this.inTrans = false;
      this.customView = null; this.padded = 63; this.pageHolds = false;
      this.settle = null; this.q = [];
    }
    hold() { this.holds++; this.suppress = true; }
    release() { if (this.holds > 0) this.holds--; if (this.holds === 0) this.suppress = false; }
    post(at, fn) { const r = { at, fn }; this.q.push(r); return r; }
    cancel(r) { const i = this.q.indexOf(r); if (i >= 0) this.q.splice(i, 1); }
    applyInsets(bars) {
      if (this.suppress) return;
      if (this.customView) return;           // immersive measurement, refuse
      this.padded = bars;
    }
    begin() {
      if (this.settle) { this.cancel(this.settle); this.settle = null; }
      if (!this.inTrans) { this.inTrans = true; this.hold(); }
    }
    end(now) {
      if (this.settle) this.cancel(this.settle);
      this.settle = this.post(now + 500, () => {
        this.settle = null;
        if (this.inTrans) { this.inTrans = false; this.release(); }
        if (!this.suppress && !this.customView) this.applyInsets(63);
      });
    }
    pageStart() { if (!this.pageHolds) { this.pageHolds = true; this.hold(); } }
    pageFinish() {
      if (this.pageHolds) { this.pageHolds = false; this.release(); }
      if (!this.suppress && !this.customView) this.applyInsets(63);
    }
    drain() {
      this.q.sort((x, y) => x.at - y.at);
      while (this.q.length) { const j = this.q.shift(); j.fn(); }
    }
  };

  // Tap a reel while the feed is still loading.
  const a = new App();
  a.pageStart();
  a.post(100, () => { a.customView = {}; a.begin(); a.end(100); });
  a.post(300, () => a.pageFinish());
  a.drain();
  ok('a load finishing mid-fullscreen leaves the padding alone', a.padded === 63,
     'padding ' + a.padded);

  // Rapid exit then enter: the stale settle must not fire mid-fullscreen.
  const b = new App();
  b.customView = null; b.begin(); b.end(0);
  b.post(200, () => { b.customView = {}; b.begin(); b.end(200); });
  b.drain();
  ok('a stale settle cannot clear the new transition', b.inTrans === false);
  ok('and cannot write immersive padding', b.padded === 63, 'padding ' + b.padded);

  // An ordinary exit still restores the padding.
  const c = new App();
  c.customView = {}; c.padded = 0;
  c.customView = null; c.begin(); c.end(0);
  c.drain();
  ok('leaving fullscreen restores the padding', c.padded === 63, 'padding ' + c.padded);
}

console.log('\nOnline requests are never delayed by the offline store');
{
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  const body = ma.slice(ma.indexOf('override fun shouldInterceptRequest'));
  const online = body.indexOf('if (isOnline) return null');
  const check = body.indexOf('OfflineCache.isInterceptable');
  ok('the online short-circuit comes first',
     online >= 0 && check >= 0 && online < check,
     'isOnline at ' + online + ', isInterceptable at ' + check);
  ok('so no disk lookup happens on the resource thread while online',
     /if \(isOnline\) return null[\s\S]{0,400}OfflineCache\.isInterceptable/.test(body));
}

// ------------------------------------------ downloading on a metered network
//
// A full pass fetches feed pages, reels and their video -- hundreds of
// megabytes. Doing that silently on a mobile plan is the user's month gone, so
// it is restricted to unmetered networks unless they opt in.
console.log('\nOffline saving respects the network choice');
{
  const np = fs.existsSync(KT('utils/NetworkPolicy.kt'))
    ? fs.readFileSync(KT('utils/NetworkPolicy.kt'), 'utf8') : '';
  const prefs = fs.readFileSync(KT('utils/Prefs.kt'), 'utf8');
  const feed = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');
  const sync = fs.readFileSync(KT('utils/OfflineSync.kt'), 'utf8');
  const bsm = fs.readFileSync(KT('utils/BackgroundSyncManager.kt'), 'utf8');
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  const sa = fs.readFileSync(KT('ui/SettingsActivity.kt'), 'utf8');
  const xml = fs.readFileSync(
    path.join(ROOT, 'app/src/main/res/xml/settings_offline.xml'), 'utf8');
  const arrays = fs.readFileSync(
    path.join(ROOT, 'app/src/main/res/values/arrays.xml'), 'utf8');

  ok('there is a single policy helper', /object NetworkPolicy/.test(np));
  ok('it tests metered, not "is this wifi"',
     /NET_CAPABILITY_NOT_METERED/.test(np) && !/TRANSPORT_WIFI/.test(np));
  ok('an unknown network is treated as metered, never as free',
     /val c = caps\(context\) \?: return false/.test(np));

  ok('the default is Wi-Fi only',
     /android:defaultValue="wifi"/.test(xml) &&
     /getString\(KEY_OFFLINE_WIFI_ONLY, "wifi"\)/.test(prefs));
  ok('the choice is stored as a string, which is what ListPreference writes',
     /getString\(KEY_OFFLINE_WIFI_ONLY/.test(prefs) &&
     !/getBoolean\(KEY_OFFLINE_WIFI_ONLY/.test(prefs));
  ok('both options are offered',
     /<item>wifi<\/item>/.test(arrays) && /<item>any<\/item>/.test(arrays));

  // Every place that pulls bytes has to ask, not just the top of the pipeline.
  ok('the sync pipeline asks before starting',
     /NetworkPolicy\.canDownload/.test(bsm));
  ok('the offscreen page loader asks too',
     /NetworkPolicy\.canDownload/.test(sync));
  ok('the media queue refuses to enqueue',
     /prefetchUrls[\s\S]{0,200}if \(!downloadAllowed\(\)\) return/.test(feed));
  ok('and the worker re-checks every item, for a mid-pass network change',
     /while \(enabled\)[\s\S]{0,300}if \(!downloadAllowed\(\)\) break/.test(feed));
  ok('the resume-time sync asks as well',
     /NetworkPolicy\.canDownload\(applicationContext, prefs\)/.test(ma));

  ok('the user is told why saving is idle',
     /blockedByMetered/.test(np) && /blockedByMetered/.test(sa));
  ok('choosing mobile data starts a pass immediately',
     /KEY_OFFLINE_WIFI_ONLY[\s\S]{0,400}BackgroundSyncManager\.start\(\)/.test(sa));
  ok('a missing context does not silently disable saving',
     /val c = appContext \?: return true/.test(feed));
}

// ------------------------------------------- scrolling must stay the app's
//
// A WebView has to live on the main thread — the thread that draws the feed.
// Two background engines each built one, so three WebViews shared it: the feed
// scrolled in steps, and just after signing in the screen sat dimmed and
// ignored taps while they all loaded.
console.log('\nOnly one thing competes with the feed');
{
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  const sync = fs.readFileSync(KT('utils/OfflineSync.kt'), 'utf8');
  const docs = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');

  ok('the second collecting engine is not started',
     !/OfflineManager\.startProactivePreparation/.test(ma));
  ok('nor on reconnect',
     !/OfflineManager\.onNetworkRestored/.test(ma));
  ok('collecting still happens, through the one owner',
     /BackgroundSyncManager\.start\(\)/.test(ma) &&
     /BackgroundSyncManager\.onNetworkRestored\(\)/.test(ma));
  ok('starting twice is still harmless',
     /if \(!BackgroundSyncManager\.isRunning\)[\s\S]{0,200}BackgroundSyncManager\.start\(\)/.test(ma));

  // Blocking images in the sync WebView was tried here and reverted: Facebook
  // lazy-loads feed images, so the tags never receive a real fbcdn URL and
  // capture collected none. The saved page came back with blank gaps.
  ok('the offscreen WebView still loads images',
     /loadsImagesAutomatically = true/.test(sync) &&
     /blockNetworkImage = false/.test(sync));
  ok('and the reason is recorded so it is not tried again',
     /lazy-load/.test(sync));

  // The offline unmute sweep walks the DOM; scrolling mutates it constantly.
  ok('the unmute sweep is debounced',
     /pending = setTimeout\(function\(\)\{ pending = 0; sweep\(\); \}, \d+\)/.test(docs));
  ok('and only runs when nodes were added',
     /if \(muts\[i\]\.addedNodes && muts\[i\]\.addedNodes\.length\)/.test(docs));
  ok('it is still bounded per pass', /j < 3000/.test(docs));
}

console.log('\nService notifications stay out of the way');
{
  const as = fs.existsSync(KT('ui/AudioService.kt'))
    ? fs.readFileSync(KT('ui/AudioService.kt'), 'utf8') : '';
  const ss = fs.existsSync(KT('ui/SyncService.kt'))
    ? fs.readFileSync(KT('ui/SyncService.kt'), 'utf8') : '';

  // A foreground service must post a notification; since API 26 there is no
  // way to suppress it. MIN keeps it out of the status bar entirely.
  for (const [name, src] of [['audio', as], ['sync', ss]]) {
    ok(name + ': channel importance is the lowest available',
       /IMPORTANCE_MIN/.test(src));
    ok(name + ': no status-bar priority',
       /PRIORITY_MIN/.test(src) && !/PRIORITY_LOW/.test(src));
    ok(name + ': silent, no vibration, no light',
       /setSilent\(true\)/.test(src) && /enableVibration\(false\)/.test(src) &&
       /enableLights\(false\)/.test(src));
    ok(name + ': hidden on the lock screen',
       /VISIBILITY_SECRET/.test(src));
    ok(name + ': no badge and no timestamp',
       /setShowBadge\(false\)/.test(src) && /setShowWhen\(false\)/.test(src));
  }
  ok('both still call startForeground, or the service is killed',
     /startForeground\(NOTIFICATION_ID/.test(as) &&
     /startForeground\(NOTIFICATION_ID/.test(ss));
}

// ------------------------------------------------ the loading bar setting
//
// "Loading bar" was switched off and a thin blue line still ran across the top
// on every screen change. The app's own ProgressBar was never the one on
// screen: Facebook's lite renderer builds its own and styles it from its own
// sheet. Captured from the live site (m.facebook.com, Android UA, logged-in
// lite renderer, rsrc.php/v5/yQ/l/0,cross/hVpdIx3cRa1.css):
//
//   .loading-bar-animation{position:fixed;top:0;left:0;background-color:#fff;
//     height:2px;width:100%;display:block;animation:prog 15s linear forwards;
//     transform-origin:left;z-index:1}
//   .loading-bar-background{position:fixed;top:0;left:0;
//     background-color:#5c7db0;height:2px;width:100%;display:block}
//   .revamped-progress-bar-color .loading-bar-animation{
//     background:linear-gradient(90deg,#004cc6,#0079ff)}
//
// and from the lite bundle (rsrc.php/v4/yJ/r/FGFfFbJPOIc.js):
//
//   a.e.className='loading-bar-animation';
//   a.f.className='loading-bar-background';
//
// So the setting has to reach Facebook's element, not only ours.
console.log('\nLoading bar off also hides the one Facebook draws');
{
  const ab = fs.readFileSync(KT('utils/AdBlocker.kt'), 'utf8');
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');

  ok('the style script can be asked to hide the site bar',
     /fun getStyleScript\([\s\S]{0,200}hideSiteLoadingBar: Boolean/.test(ab));
  ok('it targets the class Facebook actually uses',
     /"\.loading-bar-animation"/.test(ab) && /"\.loading-bar-background"/.test(ab));
  // This assertion used to say the opposite, and it was wrong.
  //
  // Hiding only the bar left Facebook's grey wash on screen with nothing
  // moving on it - rgba(0,0,0,0.6) over everything in dark mode - so tapping
  // a link looked like the app had frozen. One function builds all three and
  // the overlay is the bar's own parent:
  //
  //   a.g.className='loading-overlay';
  //   a.e.className='loading-bar-animation';
  //   a.g.appendChild(a.e);
  //
  // 'loading-overlay' is assigned in exactly one place in the whole lite
  // bundle, so removing it cannot affect anything else.
  ok('the dimming layer goes with the bar it carries',
     /"\.loading-overlay"/.test(ab) && /"\.loading-overlay-background"/.test(ab));

  // The flag has to be the inverse of the user's setting, and it has to be
  // passed at both injection points or the setting only takes effect on the
  // next navigation.
  const passes = ma.match(/hideSiteLoadingBar = !prefs\.showProgress/g) || [];
  ok('both injection points pass the setting through', passes.length >= 2,
     'call sites: ' + passes.length);
  ok('the static sheet is re-applied on a live settings change',
     /private fun injectAll\(view: WebView\?\) \{[\s\S]{0,400}AdBlocker\.getStyleScript\(/
       .test(ma));

  // Switching the setting back on must actually restore the bar. An empty
  // rule list used to return a no-op, which left the previous sheet in force.
  ok('an empty rule set clears the sheet instead of doing nothing',
     /if \(rules\.isEmpty\(\)\)[\s\S]{0,260}getElementById\('fbpro-style'\)[\s\S]{0,120}textContent = ''/
       .test(ab));

  // Run the emitted CSS against Facebook's real markup shape.
  const emit = (promos, ads, hideBar) => {
    const src = ab.slice(ab.indexOf('fun getStyleScript'));
    const rules = [];
    if (hideBar) {
      // Read the real rule list out of getStyleScript rather than restating
      // it. A hand-written copy passes whatever the app actually does, which
      // is how the missing overlay rule went unnoticed in the first place.
      const start = src.indexOf('if (hideSiteLoadingBar) {');
      const block = src.slice(start, src.indexOf('if (blockPromos)', start));
      const listed = block.match(/"(\.[a-z-]+)"/g) || [];
      for (const r of listed) rules.push(r.replace(/"/g, ''));
    }
    if (promos) rules.push('#header-notices');
    if (ads) rules.push('ins.adsbygoogle');
    if (!rules.length) return '';
    return rules.join(',') + '{display:none !important;}';
  };

  const dom = new JSDOM(
    '<div class="loading-overlay revamped revamped-progress-bar-color">' +
    '<div class="loading-bar-animation revamped-animation"></div>' +
    '<div class="loading-bar-background"></div></div>' +
    '<div id="feed">a real post</div>'
  );
  const d = dom.window.document;
  const style = d.createElement('style');
  style.textContent = emit(true, true, true);
  d.head.appendChild(style);

  const hidden = (sel) => {
    const el = d.querySelector(sel);
    return !!el && /display:\s*none/.test(
      dom.window.getComputedStyle(el).display === 'none'
        ? 'display: none' : ''
    );
  };
  ok('the animated bar is hidden', hidden('.loading-bar-animation'));
  ok('the bar background is hidden', hidden('.loading-bar-background'));
  ok('the feed is untouched', !hidden('#feed'));

  {
    // Facebook's real loading markup, and the two things that must survive.
    // Both are separate classes: .dialog-screen for real dialogs and
    // .overlay for content overlays. Neither is a loading indicator.
    const dm = new JSDOM(
      '<style>' + emit(true, true, true) + '</style>' +
      '<div class="loading-overlay revamped revamped-progress-bar-color" id="dim">' +
      '  <div class="loading-bar-animation revamped-animation" id="bar"></div>' +
      '  <div class="loading-bar-background" id="barbg"></div>' +
      '  <div class="loading-overlay-background dark" id="dimbg"></div>' +
      '</div>' +
      '<div class="dialog-screen" id="realDialog">a real dialog</div>' +
      '<div class="overlay" id="contentOverlay">a content overlay</div>' +
      '<div id="page">the page</div>'
    );
    const dw = dm.window;
    const gone = (id) =>
      dw.getComputedStyle(dw.document.getElementById(id)).display === 'none';

    ok('the wash the user was left staring at is gone', gone('dim'));
    ok('and its dark backing with it', gone('dimbg'));
    ok('a real dialog still shows', !gone('realDialog'));
    ok('a content overlay still shows', !gone('contentOverlay'));
    ok('the page still shows', !gone('page'));
  }

  // And with the setting on, nothing about the bar is emitted.
  ok('with the bar switched on no bar rule is emitted',
     !/loading-bar/.test(emit(true, true, false)));
}

// ------------------------------------------------- background audio scope
//
// Background audio is a Reels feature: it must keep a reel playing when the
// app is closed, and must do nothing for the home feed, which autoplays
// whatever scrolls past. Three faults were found here.
//
// 1. unmute() latched on a per-element flag. Facebook's feed player sets
//    muted straight back on when it starts an autoplay - browsers only allow
//    an unattended play() while the clip is silent - so after the first
//    attempt the flag said "already done" and the sweep never tried again.
//    Reels appeared to work only because the user taps those, and the tap
//    goes through onGesture, which is a different path.
//
// 2. anyPlaying() counted a muted element as playing, so leaving the app
//    during a silent feed autoplay started the service and held the
//    notification up with nothing audible behind it.
console.log('\nBackground audio is for Reels, and only when audible');
(async () => {
  const ab = fs.readFileSync(KT('utils/AdBlocker.kt'), 'utf8');
  const script = rawString(KT('utils/AdBlocker.kt'), 'fun getNativeFeelScript');

  ok('unmute() is no longer one-shot',
     !/if \(!v \|\| v\.__dbUnmuted\) return;/.test(ab));
  ok('a deliberate mute is still respected',
     /__dbUserMuted/.test(ab));

  // Behavioural: drive the real script the way the page does.
  const make = () => {
    const dom = new JSDOM('<body></body>',
      { runScripts: 'outside-only', pretendToBeVisual: true, url: 'https://m.facebook.com/' });
    const w = dom.window;
    const reports = [];
    w.FBPro = {
      onMediaState(p) { reports.push(p); }, onScrollState() {},
      onAuthState() {}, onLoginFormReady() {}, onBlobDownload() {},
    };
    w.requestAnimationFrame = (f) => setTimeout(f, 0);
    const v = w.document.createElement('video');
    const st = { paused: true, ct: 0 };
    Object.defineProperty(v, 'paused', { get: () => st.paused });
    Object.defineProperty(v, 'ended', { get: () => false });
    Object.defineProperty(v, 'currentTime', { get: () => st.ct });
    v.muted = true; v.volume = 1;
    w.document.body.appendChild(v);
    w.eval(script);
    return { w, v, st, reports };
  };
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));

  // A. feed clip: autoplays muted, user taps it -> sound must come on
  const A = make();
  await wait(60);
  A.v.dispatchEvent(new A.w.Event('loadstart'));
  A.v.muted = true; A.st.paused = false; A.st.ct = 1.5;   // Facebook re-mutes
  await wait(1300);
  A.w.document.dispatchEvent(new A.w.Event('touchend'));
  await wait(1300);
  ok('a tapped feed video ends up audible', A.v.muted === false);
  // ...but sound alone does not make it background audio. This element has no
  // player node around it, so it is not a reel, and closing the app on it must
  // not keep anything alive. The reel case is asserted further down.
  ok('an unlabelled feed clip is still not background audio',
     !A.reports.includes(true));

  // B. the user muted it on purpose -> must stay muted
  const B = make();
  await wait(60);
  B.v.dispatchEvent(new B.w.Event('loadstart'));
  B.st.paused = false; B.st.ct = 2;
  B.w.document.dispatchEvent(new B.w.Event('touchend'));
  B.v.muted = true;
  B.v.dispatchEvent(new B.w.Event('volumechange'));
  await wait(2300);
  ok('a video the user muted stays muted', B.v.muted === true);

  // C. silent autoplay must never start the service
  const C = make();
  await wait(60);
  C.v.muted = true; C.st.paused = false; C.st.ct = 3;
  C.v.__dbUserMuted = true;
  await wait(1300);
  ok('a silent feed autoplay is not reported as audio',
     !C.reports.includes(true));

  // Background audio is a Reels feature. An ordinary feed post that happens
  // to be audible must still not keep the app alive once it is closed -
  // scrolling the feed autoplays whatever passes the viewport, so that would
  // hold the notification up for a post the user never chose to listen to.
  //
  // Facebook labels the difference itself. Captured live from
  // m.facebook.com, every Watch-feed player node carries:
  //   data-is-reels="false"   data-mcomponent="ServerMVideo"
  ok('the reel test reads the label Facebook already provides',
     /data-is-reels/.test(ab));

  const player = (attr, rect) => {
    const dom = new JSDOM(`<div data-mcomponent="ServerMVideo"${attr}><video></video></div>`,
      { runScripts: 'outside-only', pretendToBeVisual: true, url: 'https://m.facebook.com/' });
    const w = dom.window;
    const reports = [];
    w.FBPro = {
      onMediaState(p) { reports.push(p); }, onScrollState() {},
      onAuthState() {}, onLoginFormReady() {}, onBlobDownload() {},
    };
    w.requestAnimationFrame = (f) => setTimeout(f, 0);
    const v = w.document.querySelector('video');
    Object.defineProperty(v, 'paused', { get: () => false });
    Object.defineProperty(v, 'ended', { get: () => false });
    Object.defineProperty(v, 'currentTime', { get: () => 2 });
    v.muted = false; v.volume = 1;
    v.getBoundingClientRect = () => rect;
    w.eval(script);
    return reports;
  };
  const small = { height: 200, width: 360, top: 0, left: 0, bottom: 200, right: 360 };
  const tall = { height: 700, width: 360, top: 0, left: 0, bottom: 700, right: 360 };

  const feed = player(' data-is-reels="false"', small);
  await wait(1300);
  ok('an audible home-feed video is not background audio',
     !feed.includes(true));

  const reel = player(' data-is-reels="true"', small);
  await wait(1300);
  ok('an audible reel is background audio', reel.includes(true));

  // If Facebook ever drops the attribute, the dedicated Reels screen still
  // fills the viewport, which the feed's inline player never does.
  const bare = player('', tall);
  await wait(1300);
  ok('an unlabelled full-viewport player still counts', bare.includes(true));

  // ------------------------------------------------ instant response to a tap
  //
  // Reported as: tapping anything does nothing until the next screen arrives,
  // so the app reads as slow even when the load is quick.
  //
  // The native-feel CSS turns off the browser's own tap highlight, which is
  // correct for an app - but only if something replaces it. Facebook's lite
  // renderer ships its own, .mtfi [data-action-id].highlight, and it never
  // fires: .mtfi appears in the stylesheet and on no element of the page.
  // Measured on a captured screen - 6 occurrences, all inside CSS text, zero as
  // a class attribute. So there was no feedback at all.
  console.log('\nA tap responds immediately');
  {
    const ab = fs.readFileSync(KT('utils/AdBlocker.kt'), 'utf8');
    const script = rawString(KT('utils/AdBlocker.kt'), 'fun getNativeFeelScript');

    ok('the browser highlight is still off, as an app would have it',
       /-webkit-tap-highlight-color:transparent/.test(ab));
    ok('but something replaces it',
       /__db_press\{opacity/.test(ab));
    ok('it reacts on touchstart, not on click',
       /addEventListener\('touchstart'/.test(ab));
    ok('and click is not used for the press, since it waits for the gesture',
       !/addEventListener\('click', function\(ev\) \{[\s\S]{0,200}__db_press/.test(ab));

    const build = () => {
      const dom = new JSDOM(
        '<body>' +
        '<div data-action-id="1" id="btn"><span id="label">Comment</span></div>' +
        '<div data-sigil="huge" id="huge">the whole scroller</div>' +
        '<div id="plain">not a control</div>' +
        '</body>',
        { runScripts: 'outside-only', pretendToBeVisual: true,
          url: 'https://m.facebook.com/' }
      );
      const w = dom.window, d = w.document;
      w.FBPro = {
        onScrollState() {}, onMediaState() {}, onAuthState() {},
        onLoginFormReady() {}, onBlobDownload() {},
      };
      w.requestAnimationFrame = (f) => setTimeout(f, 0);
      Object.defineProperty(w, 'innerHeight', { value: 800, configurable: true });
      const rect = (h, wd) => () => ({ height: h, width: wd, top: 0, left: 0,
                                       bottom: h, right: wd });
      d.getElementById('btn').getBoundingClientRect = rect(100, 300);
      d.getElementById('huge').getBoundingClientRect = rect(900, 1080);
      w.eval(script);
      return { w, d };
    };
    const fire = (w, el, type) => el.dispatchEvent(new w.Event(type, { bubbles: true }));
    const pressed = (d, id) => d.getElementById(id).classList.contains('__db_press');

    {
      const { w, d } = build();
      fire(w, d.getElementById('label'), 'touchstart');
      ok('tapping a label dims the control around it', pressed(d, 'btn'));
      fire(w, d.getElementById('label'), 'touchend');
      ok('lifting the finger clears it', !pressed(d, 'btn'));
    }
    {
      const { w, d } = build();
      fire(w, d.getElementById('huge'), 'touchstart');
      ok('a node taller than most of the screen is left alone',
         !pressed(d, 'huge'));
    }
    {
      const { w, d } = build();
      fire(w, d.getElementById('plain'), 'touchstart');
      ok('something that is not a control is left alone', !pressed(d, 'plain'));
    }
    {
      // A swipe that happens to start on a button must not leave it dimmed.
      const { w, d } = build();
      fire(w, d.getElementById('label'), 'touchstart');
      d.dispatchEvent(new w.Event('scroll', { bubbles: true }));
      ok('scrolling away releases the press', !pressed(d, 'btn'));
    }
    {
      // If the page is swapped while the finger is down there may be no
      // touchend, and a control stuck at half opacity is worse than no
      // feedback at all.
      const { w, d } = build();
      fire(w, d.getElementById('label'), 'touchstart');
      ok('a press is held while the finger is down', pressed(d, 'btn'));
      await wait(1400);
      ok('and a stuck press clears itself', !pressed(d, 'btn'));
    }
  }

  console.log('\n' + pass + ' passed, ' + fail + ' failed');
  process.exit(fail ? 1 : 0);
})();

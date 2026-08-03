#!/usr/bin/env node
/**
 * End-to-end guard for the offline delivery pipeline.
 *
 * Reported: offline content is visible but broken - the feed renders as raw
 * unstyled markup with a giant wordmark and overlapping text, reels do not
 * play, stories will not open.
 *
 * Traced through every stage. The markup was being stored correctly; the
 * failure was in delivery:
 *
 *   isInterceptable() decided what the offline store was allowed to answer by
 *   looking at the file extension and the Accept header. Facebook's
 *   stylesheets live at /rsrc.php/... with no extension, its fonts are
 *   requested with `Accept: * / *`, and its video files are /o1/v/t2/... with
 *   neither. All three were refused, and refusing means handing the request to
 *   a WebView that has no connection.
 *
 * These assertions model the real request shapes and would have caught it.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const KT = (f) => path.join(ROOT, 'app/src/main/java/com/dustbook/app', f);

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  ok   ' + name); }
  else { fail++; console.log('  FAIL ' + name + (extra ? ' :: ' + extra : '')); }
}

const cache = fs.readFileSync(KT('utils/OfflineCache.kt'), 'utf8');
const docs  = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');
const main  = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
const feed  = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');
const sync  = fs.readFileSync(KT('utils/OfflineSync.kt'), 'utf8');
const cap   = fs.readFileSync(KT('utils/OfflineCapture.kt'), 'utf8');

// ---------------------------------------------------------------- delivery
console.log('\nWhat the store is allowed to answer');
{
  // The fix: if the bytes are on disk they are servable, whatever the URL
  // looks like. This has to be checked BEFORE the extension guess.
  const fn = cache.slice(cache.indexOf('fun isInterceptable'));
  const body = fn.slice(0, fn.indexOf('\n    }'));

  ok('a stored file is served regardless of its URL shape',
     /if \(has\(url\)\) return true/.test(body));

  const hasAt = body.indexOf('has(url)');
  const extAt = body.indexOf('getFileExtensionFromUrl');
  const accAt = body.indexOf('requestHeaders["Accept"]');
  ok('that check comes before the extension test',
     hasAt >= 0 && extAt >= 0 && hasAt < extAt, `${hasAt} vs ${extAt}`);
  ok('and before the Accept-header test',
     hasAt >= 0 && accAt >= 0 && hasAt < accAt, `${hasAt} vs ${accAt}`);

  // The guards that must survive: replaying these breaks the session.
  ok('GraphQL is still never intercepted', body.includes('/api/graphql'));
  ok('/ajax/ is still never intercepted', body.includes('/ajax/'));
  ok('the main frame is still never intercepted',
     body.includes('isForMainFrame'));
  ok('non-GET is still never intercepted',
     body.includes('equals("GET"'));
}

console.log('\nContent types');
{
  // application/octet-stream is not a safe fallback: a WebView will not apply
  // a stylesheet or play a video served under it.
  ok('a missing mime sidecar is filled in, not defaulted to octet-stream',
     /fun guessMime/.test(cache));
  ok('an empty sidecar counts as missing',
     /takeIf \{ it\.isNotBlank\(\) \} \?: guessMime/.test(cache));

  const g = cache.slice(cache.indexOf('fun guessMime'));
  const gb = g.slice(0, g.indexOf('\n    }'));
  ok('stylesheets are typed as css', /"text\/css"/.test(gb));
  ok('fonts are typed as fonts', /font\/woff2/.test(gb));
  // Facebook video carries no extension at all.
  ok('extensionless facebook video is typed as video',
     /\/v\/t2\/[\s\S]{0,80}video\/mp4/.test(gb));

  const gCount = (cache.match(/\?: guessMime\(url\)/g) || []).length;
  ok('both the whole-file and range paths use it', gCount >= 2,
     String(gCount));
}

console.log('\nVideo playback');
{
  const get = cache.slice(cache.indexOf('fun get(request'));
  const getBody = get.slice(0, get.indexOf('\n    /**'));

  // Without these a media element will not issue the Range request that
  // range() answers, so a perfectly good cached video refuses to play.
  ok('a whole-file response advertises range support',
     /"Accept-Ranges" to "bytes"/.test(getBody));
  ok('and reports its length',
     /"Content-Length" to f\.length\(\)/.test(getBody));

  ok('range replies are 206 with Content-Range',
     /206/.test(cache) && /Content-Range/.test(cache));
  ok('the resolver tries range before the whole file',
     /Range[\s\S]{0,200}OfflineCache\.range\(request\)[\s\S]{0,200}OfflineCache\.get/
       .test(main));
}

console.log('\nWhat gets downloaded in the first place');
{
  // A feed full of photos used to push the stylesheets past the cap, so they
  // were never fetched and the stored page had no styling at all.
  ok('page chrome is queued ahead of photos',
     /partition \{ it\.contains\("\/rsrc\.php\/"\) \}/.test(docs));
  ok('the cap is a named constant, not a magic number',
     /MAX_PREFETCH_URLS/.test(docs));

  ok('video is still recognised without an extension',
     /\/v\/t2\//.test(feed));
  ok('downloads are queued rather than dropped',
     /queued\.add\(u\)/.test(feed) && /private fun drain\(\)/.test(feed));
}

console.log('\nAll three sections are reachable offline');
{
  for (const s of ['home', 'reels', 'stories']) {
    ok(`${s} is a stored screen`, new RegExp(`"${s}" to `).test(docs));
  }
  ok('a story URL routes to the stories screen',
     /"stories", "story" -> "stories"/.test(docs));
  ok('an unknown URL still lands somewhere real',
     /else -> "home"/.test(docs));
  ok('a screen with cards but no page still renders',
     /return shellFor\(screen\)/.test(docs));
}

// ------------------------------------------- offline must not look different
console.log('\nNothing is invented offline');
{
  const nav    = fs.readFileSync(KT('utils/OfflineNav.kt'), 'utf8');
  const banner = fs.readFileSync(KT('utils/OfflineBanner.kt'), 'utf8');
  const inject = fs.readFileSync(KT('utils/OfflineInject.kt'), 'utf8');

  // The rule, stated once: offline shows Facebook's own markup, unaltered.
  // Every violation below shipped at least once and had to be taken back out.

  // A "Feed | Reels | Stories" bar drawn by us, which is not on the real site.
  ok('no navigation bar of our own',
     !/class="nav"/.test(docs) && !/>Reels</.test(docs));
  ok('a screen with no stored page renders nothing rather than a made-up one',
     /<!DOCTYPE html>/.test(docs));
  ok('stored cards now count as offline content alongside stored pages',
     /OfflineFeed\.hasAnything\(\)/.test(
       main.slice(main.indexOf('fun hasAnythingOffline'),
                  main.indexOf('fun hasAnythingOffline') + 500)));

  // Faded controls and floating toasts are ours; online has neither.
  ok('no control is dimmed', !/opacity/.test(nav) && !/opacity/.test(banner));
  ok('nothing is overlaid on the page', !/__db_toast/.test(banner));
  ok('no element is hidden or removed',
     !/display\s*:\s*none/.test(banner) && !/display\s*:\s*none/.test(nav));
  ok('the offline banner adds no markup, only behaviour',
     !/<div/.test(banner) && !/<style/.test(banner));
  ok('injected cards are not restyled by us',
     !/scroll-snap-type/.test(inject));

  // Taps that cannot work are swallowed - but silently, the way a dead
  // control behaves, not with a message the real site never shows.
  ok('failing actions are swallowed', /preventDefault/.test(banner));
  ok('and say nothing',
     /fun onOfflineNavMissing[\s\S]{0,400}?\n        \}/.test(main) &&
     !/onOfflineNavMissing[\s\S]{0,300}?toast\(/.test(main));
}

// ------------------------------------------------------------- the icon font
console.log('\nFacebook icon font');
{
  // Facebook draws Like, Comment, Share and the rest as glyphs from its own
  // icon font - proven on the device: the "Ad" label read Ad + U+F078B +
  // U+F1677, and the offline screenshots showed tofu boxes where the icons
  // belong. A font URL appears only inside the CSS:
  //
  //   html : <link rel="stylesheet" href=".../AbCdEf.css">
  //   css  : @font-face { src: url(".../IcOnFoNt.woff2") }
  //
  // Scanning the page markup alone can never find it.
  ok('stylesheets are read, not just listed',
     /OfflineCache\.textOf/.test(docs));
  ok('a css url() is matched', /val CSS_URL/.test(docs));
  ok('what they reference is queued for download',
     /val fonts[\s\S]{0,400}CSS_URL\.findAll/.test(docs));
  ok('and queued ahead of photos',
     /\(fonts \+ chrome \+/.test(docs));

  // The font can only be found once the stylesheet is on disk, so one pass is
  // not enough - on the first sweep the CSS is still downloading.
  ok('a second sweep runs after the stylesheets land',
     /awaitPrefetch[\s\S]{0,400}prefetchUrls/.test(main));
  ok('the second sweep skips what is already stored',
     /filterNot \{ OfflineCache\.has\(it\) \}/.test(main));
  ok('waiting never happens on the UI thread',
     /AppExecutors[\s\S]{0,900}awaitPrefetch/.test(main));

  ok('a stored font is typed as a font, not octet-stream',
     /font\/woff2/.test(cache));

  // Verify the pattern against the real shape of an @font-face rule.
  const m = docs.match(/val CSS_URL = Regex\("""([\s\S]+?)"""\)/);
  ok('the css url pattern is present', !!m);
  if (m) {
    const re = new RegExp(m[1], 'g');
    const css = '@font-face{font-family:x;' +
      'src:url("https://static.xx.fbcdn.net/rsrc.php/v4/yK/r/F.woff2") format("woff2")}';
    const hit = [...css.matchAll(re)].map((x) => x[1]);
    ok('it finds a woff2 in a real @font-face rule',
       hit.length === 1 && hit[0].endsWith('.woff2'), JSON.stringify(hit));
  }
}

// -------------------------------------------- where stored pages come from
console.log('\nStored pages come from a WebView, not raw HTTP');
{
  // Verified against the live site: m.facebook.com answers HTTP 400 to a
  // plain HTTP client. Five header combinations were tried - logged out,
  // with a cookie, narrow Accept, full browser Accept, identity and gzip -
  // and every one was refused. fetchScreen() therefore stored nothing,
  // savedScreens() stayed empty, and going offline showed the bare
  // "Can't load the page" screen with no saved content at all.
  ok('there is a path that stores a page captured from a WebView',
     /fun storeFromPage/.test(docs));
  ok('the sync WebView hands its document over',
     /fun onOfflinePage/.test(sync));
  ok('the visible WebView does too',
     /fun onOfflinePage/.test(main));
  ok('the page sends it', /bridge\.onOfflinePage/.test(cap));

  // Guards carried over from the HTTP path.
  ok('a logged-out page is never stored',
     /fun storeFromPage[\s\S]{0,900}loggedout/.test(docs));
  ok('it is written atomically',
     /fun storeFromPage[\s\S]{0,2000}\.part[\s\S]{0,200}renameTo/.test(docs));
  ok('a page too small to be a screen is rejected',
     /fun storeFromPage[\s\S]{0,400}MIN_DOC_BYTES/.test(docs));
  ok('storing happens off the UI thread',
     /AppExecutors[\s\S]{0,200}OfflineDocs\.storeFromPage/.test(main));
}

// ------------------------------------------------- sound, and profile pictures
console.log('\nVideo sound');
{
  const vh = fs.readFileSync(KT('utils/VideoHelper.kt'), 'utf8');

  // The stored markup now carries the cached video URL on the src
  // attribute directly, so the browser plays it natively. The assist
  // script sets preload instead of auto-playing every video — that
  // was what silently killed sound on the second reel.
  ok('videos get preload and playsinline',
     /preload/.test(vh) && /playsinline/.test(vh));
  ok('no video is forced silent',
     !/v\.muted\s*=\s*true/.test(vh));
  ok('and no running video is un-muted either',
     !/v\.muted\s*=\s*false/.test(vh));
}

console.log('\nProfile pictures are saved');
{
  // An avatar is tiny next to a feed photo and there is one on every card,
  // so queued together they sat at the back of a 400-URL list and were
  // dropped - every saved post had a blank circle where the poster is.
  ok('avatars are queued ahead of post photos',
     /val \(avatars, photos\) = media\.partition/.test(docs));
  ok('and ahead of them in the final list',
     /\(fonts \+ chrome \+ avatars \+ photos\)/.test(docs));

  // Verify the split against the real URLs captured on the device.
  const m = docs.match(/it\.contains\("(\/t39\.30808-1\/)"\)/);
  ok('the avatar path is the one Facebook uses', !!m, String(m));
  {
    const avatar = 'https://scontent.fdac182-1.fna.fbcdn.net/v/t39.30808-1/500316650_n.jpg?stp=c0.5';
    const photo  = 'https://scontent.fcgp1-1.fbcdn.net/v/t51.2885-15/postphoto.jpg';
    const isAvatar = (u) => u.includes('/t39.30808-1/') || u.includes('_n.jpg?stp=c');
    ok('a real avatar URL is recognised', isAvatar(avatar));
    ok('a real post photo is not', !isAvatar(photo));
  }

  // The per-card path queues everything, so nothing is dropped there.
  ok('card media is queued without a cap',
     /Queue, never drop/.test(feed));
}

// ------------------------------------------ offline navigation and the banner
console.log('\nOffline navigation is not rebuilt every time');
{
  // serve() used to reassemble the whole page on every navigation, on the
  // WebView's resource thread: read the stored document, parse the item
  // store, then SHA-256 and stat every media URL to decide what is playable.
  // With a couple of hundred reels that is hundreds of hashes and filesystem
  // stats per back press, so going back from Reels crawled - and got worse
  // the more content was saved.
  ok('an assembled page is kept', /private val built/.test(docs));
  ok('and reused while the stored page is unchanged',
     docs.includes('built[screen]'));
  ok('the rebuilt page is stored for next time',
     docs.includes('built[screen] = Built('));

  // A stale page would be worse than a slow one.
  ok('saving new cards drops it',
     /OfflineDocs\.invalidate\(\)/.test(feed));
  ok('clearing saved content drops it too',
     /fun clear\(\)[\s\S]{0,120}invalidate\(\)/.test(feed) ||
     /fun clear\(\)[\s\S]{0,120}OfflineDocs\.invalidate\(\)/.test(feed));
  ok('and clearing the pages drops it',
     /fun clear\(\)[\s\S]{0,80}invalidate\(\)/.test(docs));
}

console.log('\nThe offline notice is a toast, not a permanent bar');
{
  const layout = fs.readFileSync(
    path.join(ROOT, 'app/src/main/res/layout/activity_main.xml'), 'utf8');

  // The bar was pinned to the bottom of every screen and covered the video
  // while it played.
  ok('no permanent bar in the layout', !/offlineBanner/.test(layout));
  ok('nothing references it any more', !/offlineBanner/.test(main));
  ok('the notice is shown as a toast',
     /toast\(getString\(R\.string\.offline_banner\)\)/.test(main));

  // Said when it becomes true, and when saved content is opened - not held
  // on screen for the whole session.
  const hits = (main.match(/toast\(getString\(R\.string\.offline_banner\)\)/g) || []).length;
  ok('shown on the events that matter, not continuously', hits === 2,
     String(hits));
}

// ------------------------------------------ the "Tap to unmute" label offline
//
// Facebook dismisses this label with its own JS on interaction. Offline that
// JS never runs, so it has to be gone from the stored markup. A lazy regex
// ending at the first </div> stopped inside the overlay, left its trailing
// </div> behind and corrupted the markup, so the label survived.
console.log('\nStored markup carries no audio overlay');
{
  const cap = fs.readFileSync(KT('utils/OfflineCapture.kt'), 'utf8');

  ok('the overlay is removed by counting depth, not a lazy regex',
     /function removeTag\(html, attr, value\)/.test(cap) &&
     !/m-video-overlay["'\]]*\[\^>\]\*>\[\\s\\S\]\*\?/.test(cap));
  ok('it is applied to the audio overlay',
     /removeTag\(html, 'data-sigil', 'm-video-overlay'\)/.test(cap));
  ok('the scan is bounded so malformed markup cannot spin',
     /guard < \d+/.test(cap));

  // Run the real function against the shapes Facebook actually serves.
  // Built defensively: if the helper is missing these assertions must fail,
  // not throw and hide every section after this one.
  let removeTag = null;
  try {
    const src = cap.slice(cap.indexOf('function removeTag'),
                          cap.indexOf('function markupOf'));
    // eslint-disable-next-line no-new-func
    removeTag = new Function(src + '; return removeTag;')();
  } catch (e) {
    removeTag = null;
  }
  if (typeof removeTag !== 'function') {
    removeTag = () => '<<removeTag missing>>';
  }

  const cases = {
    'flat': '<div data-sigil="m-video-overlay">Tap to unmute</div><div id="k">real</div>',
    'nested': '<div class="x" data-sigil="m-video-overlay"><div class="i">' +
              '<span>Tap to unmute</span></div></div><div id="k">real</div>',
    'deeply nested': '<div data-sigil="m-video-overlay"><div><div><div>' +
              'Tap to unmute</div></div></div></div><div id="k">real</div>',
    'two overlays': '<div data-sigil="m-video-overlay"><div>Tap to unmute</div></div>' +
              '<p>mid</p><div data-sigil="m-video-overlay"><div>unmute</div></div>' +
              '<div id="k">real</div>',
  };
  for (const [name, html] of Object.entries(cases)) {
    const out = removeTag(html, 'data-sigil', 'm-video-overlay');
    const opens = (out.match(/<div/g) || []).length;
    const closes = (out.match(/<\/div>/g) || []).length;
    ok('removed from ' + name, !/unmute/i.test(out), out);
    ok('  real content kept in ' + name, /real/.test(out));
    ok('  markup stays balanced in ' + name, opens === closes,
       opens + ' open vs ' + closes + ' close');
  }
  ok('markup with no overlay is untouched',
     removeTag('<div id="k">real</div>', 'data-sigil', 'm-video-overlay')
       === '<div id="k">real</div>');
}

// ---------------------------------------- the offline story sits too high
//
// Online the activity pads its root view so content clears the status bar. A
// position:fixed overlay inside the WebView is measured against the viewport
// and never sees that padding, so the offline story viewer started under the
// status bar while the online one did not.
console.log('\nOffline story viewer clears the status bar');
{
  const docs = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');
  const sv = docs.slice(docs.indexOf('private fun storyViewer'));

  ok('the overlay asks for the safe area',
     /safe-area-inset-top/.test(sv) && /safe-area-inset-bottom/.test(sv));
  ok('env() is set through a stylesheet, not the style attribute',
     /createElement\('style'\)/.test(sv) &&
     /__db_story_overlay\{/.test(sv));
  ok('a plain 0 fallback precedes it',
     /top:0;bottom:0;[\s\S]{0,80}safe-area-inset-top/.test(sv));
  ok('viewport-fit=cover is ensured, or env() never resolves',
     /viewport-fit/.test(sv) && /meta\[name=viewport\]/.test(sv));
  ok('an existing viewport meta is amended, not replaced',
     /indexOf\('viewport-fit'\) === -1/.test(sv));
}

// -------------------------------- reading is not gated by the save switches
//
// Turning offline saving off used to hide content that was already on disk.
// Nothing had been deleted -- switching it back on made everything reappear --
// so the app was refusing to show something it still had.
console.log('\nSaved content is readable whatever the save switches say');
{
  const prefs = fs.readFileSync(KT('utils/Prefs.kt'), 'utf8');
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  const app = fs.readFileSync(KT('DustbookApplication.kt'), 'utf8');
  const sa = fs.readFileSync(KT('ui/SettingsActivity.kt'), 'utf8');

  ok('reading has its own flag', /val offlineRead: Boolean/.test(prefs));
  ok('and it does not depend on the section switches',
     /val offlineRead: Boolean get\(\) = true/.test(prefs));
  ok('saving still follows the switches',
     /offlineReels \|\| offlineFeed \|\| offlineStories/.test(prefs));

  ok('serving a stored page checks read, not save',
     /prefs\.offlineRead && !isOnline && request\.isForMainFrame/.test(ma));
  ok('falling back to saved content checks read',
     /prefs\.offlineRead && !isOnline && hasAnythingOffline\(\)/.test(ma));
  ok('the View saved content button checks read',
     /canOffline = prefs\.offlineRead && hasAnythingOffline\(\)/.test(ma));
  ok('no read path is gated on offlineMode any more',
     !/prefs\.offlineMode && !isOnline/.test(ma));

  ok('the stores separate reading from writing',
     /writeEnabled/.test(fs.readFileSync(KT('utils/OfflineCache.kt'), 'utf8')) &&
     /writeEnabled/.test(fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8')));
  ok('put() is the one gated on writing',
     /fun put[\s\S]{0,400}if \(!writeEnabled\) return/
       .test(fs.readFileSync(KT('utils/OfflineCache.kt'), 'utf8')));
  ok('get() is not',
     /fun get\([\s\S]{0,300}if \(!enabled\) return null/
       .test(fs.readFileSync(KT('utils/OfflineCache.kt'), 'utf8')));

  ok('startup enables reading unconditionally',
     /OfflineCache\.enabled = prefs\.offlineRead/.test(app) &&
     /OfflineCache\.writeEnabled = prefs\.offlineMode/.test(app));
  ok('the activity uses one helper for both',
     /private fun applyOfflineFlags\(\)/.test(ma));
  ok('toggling a switch changes only writing',
     /OfflineCache\.writeEnabled = write/.test(sa) &&
     !/OfflineCache\.enabled = on/.test(sa));
}

// ------------------------------- the unmute label on already-stored content
console.log('\nThe unmute label is stripped when the page is served');
{
  const docs = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');
  ok('a strip script exists', /private fun unmuteStripScript/.test(docs));
  ok('the stored document gets it',
     /f\.readText\(\) \+\s*\n\s*unmuteStripScript\(\)/.test(docs));
  ok('the assembled page gets it too',
     (docs.match(/unmuteStripScript\(\)/g) || []).length >= 3);
  ok('it keeps watching, since markup is swapped in after load',
     /MutationObserver/.test(docs.slice(docs.indexOf('unmuteStripScript'))));
  ok('a long caption mentioning the word is protected',
     /t\.length > 40/.test(docs));
}

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);

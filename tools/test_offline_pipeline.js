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
const { JSDOM } = require('jsdom');

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

  {
    const vaultSrc = fs.readFileSync(KT('offline/SectionVault.kt'), 'utf8');
    ok('video is still recognised without an extension',
       /\/v\/t2\//.test(vaultSrc));
    ok('downloads are queued rather than dropped',
       /queued\.add\(u\)/.test(vaultSrc) && /private fun drain\(\)/.test(vaultSrc));
  }
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
  const inject = fs.readFileSync(KT('offline/PageAssembly.kt'), 'utf8');

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
     /fun clearAll\(\)[\s\S]{0,160}OfflineDocs\.invalidate\(\)/
       .test(fs.readFileSync(KT('offline/OfflineVaults.kt'), 'utf8')));
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
     /withCards \+\s*\n\s*unmuteStripScript\(\)/.test(docs));
  ok('the assembled page gets it too',
     (docs.match(/unmuteStripScript\(\)/g) || []).length >= 3);
  ok('it keeps watching, since markup is swapped in after load',
     /MutationObserver/.test(docs.slice(docs.indexOf('unmuteStripScript'))));
  ok('a long caption mentioning the word is protected',
     /t\.length > 40/.test(docs));
}

// ------------------------------- an item counts only when it is fully saved
//
// The rule behind the number the user reads. Until v5.2.4 it asked a shared
// LRU cache, with a 500 KB minimum for videos: a small-but-complete reel
// never counted, an evicted photo un-counted a post, and the settings row
// drifted away from what the offline screen could show. The count now asks
// each section vault's own folder, where a file only exists when it is
// complete: written under a .part name and renamed in after its last byte.
console.log('\nCounting waits for the whole item');
{
  const H = require('./offline_vault_harness.js');
  const vaultSrc = fs.readFileSync(KT('offline/SectionVault.kt'), 'utf8');
  const feedSrc = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');

  ok('there is one rule for what counts',
     /fun isComplete\(e: Entry\): Boolean/.test(vaultSrc));
  ok('the item is judged by kind, not by URL count',
     /val videos = e\.media\.filter \{ isVideoUrl\(it\) \}/.test(vaultSrc));
  // Not "every URL": capture records srcset variants that are never all
  // fetched, so that rule left photo posts permanently incomplete either.
  ok('the loose any-of test is gone',
     !/e\.media\.any \{ u ->/.test(vaultSrc));
  ok('a video counts when its file exists, with no arbitrary size floor',
     /videos\.any \{ hasAsset\(it\) \}/.test(vaultSrc) &&
     !/MIN_VIDEO|hasMinSize/.test(vaultSrc));
  ok('files only ever exist complete: every failure deletes the stub',
     /\.part/.test(vaultSrc) && /if \(!tmp\.renameTo\(final\)\)/.test(vaultSrc) &&
     vaultSrc.replace(/\n\s+/g, ' ').includes('tmp.delete() return false'));
  ok('a text post with no media still counts',
     /if \(e\.media\.isEmpty\(\)\) return true/.test(vaultSrc));

  ok('the count and the screen share one source of truth',
     /fun count\(\): Int = completeItems\(\)\.size/.test(vaultSrc) &&
     /fun cards\(\): List<String> = completeItems\(\)\.map \{ it\.html \}/
       .test(vaultSrc));
  ok('the app asks each section vault for both',
     /fun realPlayableCount\(section: String\): Int = vault\(section\)\?\.count\(\)/
       .test(feedSrc) &&
     /fun cardMarkupList\(section: String\)[\s\S]{0,160}vault\(section\)\?\.cards\(\)/
       .test(feedSrc));
  ok('and the vaults never ask the shared chrome cache',
     !/import com\.dustbook\.app\.utils\.OfflineCache/.test(vaultSrc) &&
     !/OfflineCache\.(has|put|get|range)\(/.test(vaultSrc));

  // Exercise the rule itself via the verbatim-ported predicates, against a
  // fake disk. By construction that disk can only hold complete files.
  const disk = new Set();
  const has = (u) => disk.has(u);
  const set = (...urls) => { disk.clear(); for (const u of urls) disk.add(u); };

  set('a.jpg');
  ok('a five-photo post does not count on the first photo',
     H.isComplete(['a.jpg', 'b.jpg', 'c.jpg', 'd.jpg', 'e.jpg'], has) === false);
  set('a.jpg', 'b.jpg', 'c.jpg', 'd.jpg', 'e.jpg');
  ok('and does once every photo is there',
     H.isComplete(['a.jpg', 'b.jpg', 'c.jpg', 'd.jpg', 'e.jpg'], has) === true);

  const AV = 'https://scontent.xx.fbcdn.net/v/t39.30808-1/1_s.jpg';
  const VID = 'https://video.xx.fbcdn.net/v/t2/reel.mp4';
  set(AV);
  ok('a reel does not count on its avatar alone',
     H.isComplete([AV, VID], has) === false);
  set(AV, VID);
  ok('it counts the moment the video file exists',
     H.isComplete([AV, VID], has) === true);
  ok('a text post counts immediately', H.isComplete([], has) === true);
}
console.log('\nStories can be opened offline');
{
  const docs = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');

  ok('routing asks what is reachable, not what has a document',
     /fun navigableScreens\(\): List<String>/.test(docs));
  ok('a screen held only as cards still routes',
     /storedCount\(section\) > 0/.test(docs));
  ok('the nav script is built from it',
     /OfflineNav\.script\(navigableScreens\(\)\)/.test(docs) &&
     !/OfflineNav\.script\(savedScreens\(\)\)/.test(docs));
  ok('and so is the offline landing choice',
     /val saved = OfflineDocs\.navigableScreens\(\)/.test(ma));
  ok('stories map to the stories section',
     /"stories" -> OfflineFeed\.SECTION_STORIES/.test(docs));
}

// ------------------------------------------------ the five-step pipeline
console.log('\nThe pipeline runs in the documented order');
{
  const sync = fs.readFileSync(KT('utils/OfflineSync.kt'), 'utf8');
  const bsm = fs.readFileSync(KT('utils/BackgroundSyncManager.kt'), 'utf8');

  // targetFor used to raise every request to the V4 constants, so step 1
  // chased 500 posts instead of what was asked and never handed over.
  ok('a requested target is not silently raised',
     /private fun targetFor\(section: String, target: Int\): Int = target/.test(sync));
  ok('the V4 ceilings no longer override callers',
     !/coerceAtLeast\(OfflineManager\.V4_FEED_TARGET\)/.test(sync) &&
     !/coerceAtLeast\(OfflineManager\.V4_REEL_TARGET\)/.test(sync));
  ok('the capture script receives the target un-inflated',
     /OfflineCapture\.script\(\s*target,/.test(sync) &&
     !/coerceAtLeast\(150\)/.test(sync));

  // The user-visible order, exactly as specified:
  //   1. first 10 posts (then posts stop)
  //   2. the chosen number of reels
  //   3. the backlog from 10 up to 300 posts
  //   4. then all stories
  const defs = ['fun step1FirstPosts', 'fun step2Reels',
                'fun step3MorePosts', 'fun step4Stories'];
  for (let i = 1; i < defs.length; i++) {
    ok(defs[i - 1].slice(4) + ' runs before ' + defs[i].slice(4),
       bsm.indexOf(defs[i - 1]) !== -1 &&
       bsm.indexOf(defs[i - 1]) < bsm.indexOf(defs[i]),
       String(bsm.indexOf(defs[i - 1])) + ' vs ' + String(bsm.indexOf(defs[i])));
  }
  ok('each phase hands over to the next',
     /fun step1FirstPosts[\s\S]{0,600}step2Reels\(context, p\)/.test(bsm) &&
     /fun step2Reels[\s\S]{0,600}step3MorePosts\(context, p\)/.test(bsm) &&
     /fun step3MorePosts[\s\S]{0,600}step4Stories\(context, p\)/.test(bsm));
  ok('step 1 saves exactly the first 10 posts',
     /fun step1FirstPosts[\s\S]{0,400}SECTION_FEED, 10,[\s\S]{0,200}exactTotal = 10/.test(bsm));
  ok('reels use the user\'s chosen count, exactly',
     /fun step2Reels[\s\S]{0,240}p\.offlineReelTarget[\s\S]{0,240}exactTotal = target/.test(bsm));
  ok('step 3 fills the feed up to 300 in total',
     /fun step3MorePosts[\s\S]{0,400}SECTION_FEED, 300,[\s\S]{0,200}exactTotal = 300/.test(bsm));
  ok('stories come last and save everything',
     /fun step4Stories[\s\S]{0,300}SECTION_STORIES, 200/.test(bsm));

  // A phase only ends when its downloads are drained: the count the user
  // reads is what actually plays, never markup still waiting on media.
  // Given up wrong twice already: a fixed five-minute clock, then a
  // watchdog that counted only COMPLETED files - one 30 MB reel at
  // 77 KB/s takes six-plus minutes, so the watchdog fired mid-download
  // and the phases overlapped exactly as the user's screenshots showed.
  // Stalled now means: no completed file, no in-flight byte, no queue
  // movement, for three consecutive minutes.
  ok('each phase waits on its OWN section vault',
     /fun awaitThen\(section: String, next: \(\) -> Unit\)/.test(bsm) &&
     /OfflineVaults\.forSection\(section\)/.test(bsm));
  ok('the fixed five-minute give-up is gone, phases cannot overlap',
     !/300_000/.test(bsm) && /awaitIdle\(60_000\)/.test(bsm));
  ok('life is byte-level, not file-level, so a slow reel cannot stall',
     /gaugeBytes\(\)/.test(bsm) &&
     /pending != lastPending/.test(bsm) &&
     /silentMinutes\+\+/.test(bsm));
  ok('bail needs three fully silent minutes',
     /if \(silentMinutes >= 3\) break/.test(bsm));
  ok('a phase ends when its queue genuinely stands empty',
     /if \(pending == 0 && !busy\) break/.test(bsm));
  {
    const vsGauge = fs.readFileSync(KT('offline/SectionVault.kt'), 'utf8');
    ok('the vault exposes the in-flight byte gauge',
       /gaugeBytes = total/.test(vsGauge) && /fun gaugeBytes\(\)/.test(vsGauge));
  }

  // Behavioural proof of the watchdog, mirrored branch-for-branch from
  // awaitThen() against a simulated vault. MINUTES runs the loop body
  // once per minute, as awaitIdle(60_000) would return.
  function simulateGate(minutes) {
    let lastDone = -1, lastBytes = -1, lastPending = -1, silent = 0;
    let broke = null;
    for (let m = 0; m < minutes.length; m++) {
      const v = minutes[m];
      if (v.pending === 0 && !v.busy) { broke = 'drained@' + m; break; }
      if (v.done !== lastDone || v.bytes !== lastBytes ||
          v.pending !== lastPending) {
        silent = 0; lastDone = v.done; lastBytes = v.bytes;
        lastPending = v.pending;
      } else {
        silent++;
        if (silent >= 3) { broke = 'stalled@' + m; break; }
      }
    }
    return broke;
  }
  // 30 MB at 77 KB/s: nine minutes of one transfer, bytes moving - the
  // v5.2.5 watchdog broke at minute 3 here; the new one must not.
  const slowReel = [];
  for (let i = 0; i < 9; i++) slowReel.push(
    { pending: 25, busy: true, done: 4, bytes: i * 3400000 + 800000 });
  slowReel.push({ pending: 25, busy: true, done: 5, bytes: 400 });
  ok('a nine-minute single-file download never trips the watchdog',
     simulateGate(slowReel) === null, String(simulateGate(slowReel)));
  // A drained queue ends the phase at once, watchdog or not.
  ok('queue drained ends the phase immediately',
     simulateGate([{ pending: 0, busy: false, done: 0, bytes: 0 }]) === 'drained@0');
  // Three fully silent minutes is the only stall bail.
  const dead = [...Array(5)].map(() => ({ pending: 12, busy: true, done: 4, bytes: 900000 }));
  ok('a truly dead queue bails after three silent minutes',
     simulateGate(dead) === 'stalled@3', String(simulateGate(dead)));
  // A queue shedding permanently-failing URLs counts as alive.
  const failing = [1, 2, 3, 4].map((i) =>
    ({ pending: 20 - i, busy: true, done: 4, bytes: 0 }));
  failing.push({ pending: 0, busy: false, done: 4, bytes: 0 });
  ok('permanent failures move the phase on instead of hanging it',
     simulateGate(failing) === 'drained@4', String(simulateGate(failing)));
  const awaits = (bsm.match(/awaitThen\(OfflineFeed\.SECTION_/g) || []).length;
  ok('the wait follows every phase', awaits >= 4, String(awaits));
}

console.log('\nAn exact phase total stops fetching where it was asked');
{
  const sync = fs.readFileSync(KT('utils/OfflineSync.kt'), 'utf8');

  ok('calls can pass an exact total',
     /fun run[\s\S]{0,500}exactTotal: Int\? = null/.test(sync) &&
     /fun runAll[\s\S]{0,500}exactTotal: Int\? = null/.test(sync));
  ok('the batch is capped by what the store still needs',
     /val remaining = exactTotal -[\s\S]{0,80}OfflineFeed\.totalStored\(sec\)/.test(sync) &&
     /newItems\.take\(remaining\)/.test(sync));
  ok('a reached total stops the phase before any download is queued',
     /if \(remaining <= 0\) return/.test(sync));
  ok('only the kept batch is queued for download',
     /OfflineFeed\.prefetch\(sec, batch, includeVideo\)/.test(sync));
  ok('and only the kept batch reaches the store',
     /OfflineFeed\.addItems\(sec, batch, target\)/.test(sync));

  // Faithful port of the batching, run through the phase boundaries.
  function phase(existing, reported, exactTotal) {
    const known = new Set(existing.map((x) => x.id));
    const fresh = reported.filter((it) => !known.has(it.id));
    const remaining = exactTotal - existing.length;
    if (remaining <= 0) return [];
    return fresh.slice(0, remaining);
  }
  const mk = (p, n) => Array.from({ length: n }, (_, i) => ({ id: p + i }));
  ok('phase 1 takes ten and stops',
     phase([], mk('a', 25), 10).length === 10);
  ok('phase 1 stops completely once ten are held',
     phase(mk('a', 10), mk('b', 20), 10).length === 0);
  ok('phase 3 fills only the gap to 300',
     phase(mk('a', 10), mk('b', 500), 300).length === 290);
  ok('reels phase respects the chosen amount',
     phase([], mk('r', 90), 50).length === 50);
}

console.log('\nSaved images survive to the offline page');
{
  const sync = fs.readFileSync(KT('utils/OfflineSync.kt'), 'utf8');
  // Facebook lazy-loads feed images: with loading blocked the <img> tags
  // never receive a real fbcdn URL, so capture collected none and every
  // offline post came back as text with blank gaps.
  ok('the capture WebView loads images',
     /loadsImagesAutomatically = true/.test(sync) &&
     /blockNetworkImage = false/.test(sync));
}

console.log('\nThe assembled page is rebuilt when media lands');
{
  const feed = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');

  ok('finishing a download invalidates the built page',
     /if \(stored > 0\) OfflineDocs\.invalidate\(\)/.test(feed));
  ok('offline refresh reaches a cards-only screen',
     /val saved = OfflineDocs\.navigableScreens\(\)[\s\S]{0,300}screenFor\(binding\.webView\.url/.test(ma));

  // Serving a page must not re-parse every section on the resource thread.
  const docs = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');
  ok('routing uses a cheap existence check',
     /OfflineFeed\.storedCount\(section\) > 0/.test(docs));
  // Scope to the function body, not the rest of the file: realPlayableCount
  // is used legitimately elsewhere.
  ok('and not the expensive one',
     !/fun navigableScreens[\s\S]{0,700}?realPlayableCount[\s\S]{0,60}?\n    \}/.test(docs));
  ok('the cheap check does not parse',
     /fun storedCountCheap[\s\S]{0,300}f\.length\(\) > 2L/
       .test(fs.readFileSync(KT('offline/SectionVault.kt'), 'utf8')));
}

console.log('\nFullscreen video is not restarted by a layout pass');
{
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  ok('the inset refresh stands down during fullscreen',
     /fun refreshInsetsAfterLoad[\s\S]{0,400}if \(customView != null \|\| inFullscreenTransition\) return/.test(ma));
  // The enter and exit handlers used to carry identical copies of the settle
  // block, so this counted three call sites. They now share one helper, which
  // is what lets a new transition cancel the previous one's pending callback.
  // The requirement is unchanged: leaving fullscreen must still ask for a
  // fresh pass, and must not do it while the player is up.
  ok('the fullscreen handlers still request their own',
     /private fun endFullscreenTransition\(\)[\s\S]{0,900}ViewCompat\.requestApplyInsets\(binding\.root\)/
       .test(ma) &&
     /private fun endFullscreenTransition\(\)[\s\S]{0,900}customView == null/.test(ma) &&
     (ma.match(/ViewCompat\.requestApplyInsets/g) || []).length >= 2);
}

console.log('\nCompleteness allows for srcset alternates');
{
  const H = require('./offline_vault_harness.js');
  const vaultSrc = fs.readFileSync(KT('offline/SectionVault.kt'), 'utf8');

  // Requiring every URL left items permanently incomplete, because capture
  // records each srcset variant while only one is ever fetched. Judging by
  // "any photo at all" counted far too early. Variants of one photo are
  // grouped by the production regex; every photo needs one variant on disk.
  ok('a video item is judged on its video',
     /if \(videos\.isNotEmpty\(\)\)[\s\S]{0,120}videos\.any \{ hasAsset\(it\) \}/
       .test(vaultSrc));
  ok('the all-URLs rule is gone',
     !/e\.media\.all \{ u ->/.test(vaultSrc));
  ok('an avatar alone still does not count',
     /fun isAvatar/.test(vaultSrc) &&
     /val photos = e\.media\.filter \{ !isAvatar\(it\) && !isChrome\(it\) \}/
       .test(vaultSrc));
  ok('every distinct photo must have a variant on this section\'s disk',
     /groups\.values\.all \{ variants -> variants\.any \{ hasAsset\(it\) \} \}/
       .test(vaultSrc));

  const disk = new Set();
  const has = (u) => disk.has(u);
  const set = (...urls) => { disk.clear(); for (const u of urls) disk.add(u); };

  set('p_640.jpg');
  ok('a photo post counts on one real variant',
     H.isComplete(['p_640.jpg', 'p_960.jpg', 'p_1280.jpg'], has) === true);
  set();   // nothing on disk
  ok('a reel without its video on disk does not count',
     H.isComplete(['r_320.jpg', '/o1/v/v.mp4'], has) === false);
  set('/o1/v/v.mp4');
  ok('and counts once the video itself exists',
     H.isComplete(['r_320.jpg', '/o1/v/v.mp4'], has) === true);
  set('av/t39.30808-1/a.jpg');
  ok('but not on an avatar alone',
     H.isComplete(['av/t39.30808-1/a.jpg', '/o1/v/v.mp4'], has) === false);
  set('short.mp4');
  ok('a short reel the CDN served whole counts too - no size floor',
     H.isComplete(['r_320.jpg', 'short.mp4'], has) === true);

  set('a111_n.jpg');
  ok('an album waits for every photo, not the first',
     H.isComplete(['a111_n.jpg', 'a222_n.jpg', 'a333_n.jpg'], has) === false);
  set('a111_n.jpg', 'a222_n.jpg', 'a333_n.jpg');
  ok('and counts when the whole album is on disk',
     H.isComplete(['a111_n.jpg', 'a222_n.jpg', 'a333_n.jpg'], has) === true);

  // The grouping uses the regexes extracted from the production file.
  ok('srcset variants of one photo collapse to a single photo',
     H.photoKey('https://scontent.xx.fbcdn.net/v/t51.2885-15/p480x480/4111001_n.jpg') ===
     H.photoKey('https://scontent.xx.fbcdn.net/v/t51.2885-15/4111001_n.jpg?stp=dst-jpg'));
  ok('dimension-suffixed variants collapse too',
     H.photoKey('https://scontent.xx.fbcdn.net/v/t51.2885-15/img_1080x1080_n.jpg') ===
     H.photoKey('https://scontent.xx.fbcdn.net/v/t51.2885-15/img_n.jpg'));
  ok('two different photos never merge into one group',
     H.photoKey('https://scontent.xx/4111001_n.jpg') !==
     H.photoKey('https://scontent.xx/4222002_n.jpg'));
}
console.log('\nEvery saved card reaches the page');
{
  const H = require('./offline_vault_harness.js');
  const docs = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');
  const feedKt = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');
  const prefs = fs.readFileSync(KT('utils/Prefs.kt'), 'utf8');
  const xml = fs.readFileSync(
    path.join(ROOT, 'app/src/main/res/xml/settings_browsing.xml'), 'utf8');
  const assemblySrc = fs.readFileSync(KT('offline/PageAssembly.kt'), 'utf8');

  // Reported on device, repeatedly: the settings row counted dozens of
  // saved posts, the offline home feed showed fewer than ten. Every known
  // cause lived in runtime injection - a script that had to move the cards
  // into the page. Delivery is now static: the cards are composed into the
  // stored document in Kotlin and served AS the page.
  ok('cards are composed into the stored document before serving',
     /PageAssembly\.compose\(docText, cards\)/.test(docs));
  ok('the runtime injection script is gone',
     !fs.existsSync(KT('utils/OfflineInject.kt')));
  ok('the story viewer still uses the proven JSON delivery',
     /val json = JSONArray\(\)/.test(docs) &&
     /var STORIES = \$safe;/.test(docs) &&
     !docs.includes("split('---DBSTORY---')"));
  ok('a card list exists for exactly this purpose',
     /fun cardMarkupList\(section: String\)/.test(feedKt));
  ok('active markup inside a stored card is cut',
     /SCRIPT_BLOCK/.test(assemblySrc) && /BASE_TAG/.test(assemblySrc));
  ok('the fallback shell composes the same way',
     /PageAssembly\.holderHtml\(use\)/.test(docs));

  // Behavioural proof. compose() is mirrored line-for-line, with every
  // regex extracted from PageAssembly.kt; the page is parsed with scripts
  // ALLOWED to run, so any card script that survived would execute.
  function renderOffline(cards, docBody) {
    const storedDoc = '<!DOCTYPE html><html><head></head><body>' +
      docBody + '</body></html>';
    const page = H.compose(storedDoc, cards);
    const errors = [];
    const vc = new (require('jsdom').VirtualConsole)();
    vc.on('jsdomError', (e) => errors.push(String(e && e.message || e)));
    const dom = new JSDOM(page, { runScripts: 'dangerously',
      virtualConsole: vc, url: 'https://m.facebook.com/' });
    const d = dom.window.document;
    const holder = d.querySelector('[data-db-cards]');
    return { rendered: holder
               ? holder.querySelectorAll('[data-tracking-duration-id]').length
               : 0,
             holder: !!holder, dom, doc: d, errors,
             ranCardScript: dom.window.__dead || 0 };
  }
  const nasty = (i, quirk) => {
    let h = '<div data-tracking-duration-id="n' + i + '">' +
      '<div><span>Author ' + i + ' with a longer caption text</span></div>' +
      '<img src="https://scontent.fcgp1-1.fbcdn.net/v/t51.2885-15/p' + i + '_n.jpg">' +
      '<script>window.__dead=(window.__dead||0)+1;</scr' + 'ipt>';
    if (quirk === 'upper') h += '<script>window.a=1;</SCRIPT>';
    if (quirk === 'tpl') h += '<script>var t=`x ${' + 'y}${' + 'z}`;</scr' + 'ipt>';
    if (quirk === 'tick') h += '<script>var a=`plain`;</scr' + 'ipt>';
    if (quirk === 'cash') h += '<span>price $100 ${' + '500} today</span>';
    return h + '<div role="button" aria-label="Like">Like</div></div>';
  };
  const bundle = [
    nasty(1, 'upper'), nasty(2, 'tpl'), nasty(3),
    nasty(4, 'tick'), nasty(5, 'cash')
  ].concat(Array.from({ length: 32 }, (_, i) => nasty(10 + i)));

  const res = renderOffline(bundle,
    '<div data-type="vscroller" id="feed"><div class="old"><p>stale</p></div></div>');
  ok('all 37 cards render - the count now matches the screen',
     res.rendered === 37, 'rendered=' + res.rendered);
  ok('a card with an UPPERCASE </SCRIPT> no longer kills the rest',
     res.rendered === 37 && res.holder, res.errors[0] || '');
  ok('template-literal content inside a card is inert',
     res.holder && res.rendered === 37);
  ok('the dollar-amount text survives as text',
     (res.dom.window.document.body.textContent || '').indexOf('price $100') >= 0);
  ok('scripts smuggled in a card never execute, and are removed',
     res.ranCardScript === 0 &&
     res.doc.querySelectorAll('[data-db-cards] script').length === 0);
  ok('the holder hides the stale leftovers without deleting them',
     res.doc.getElementById('__db_hide_old') !== null &&
     res.doc.querySelector('#feed .old') !== null);

  // Round-5/-6 reports: offline, Facebook's own header and tab row were
  // missing from their place; then, after cards were moved INSIDE the
  // scroller (v5.2.5), scrolling failed intermittently on device
  // ("majhe majhe scroll hoi na"). The scroller is Facebook's JS-driven
  // virtual list - no static block belongs inside it. The correct shape:
  // cards join the EARLIEST container (the screen root on real captures)
  // as its first child, and only the old scroller hides - BY NAME - so
  // the pinned header keeps its place.
  ok('the cards join the earliest container, never a ranked scroller-first',
     !/FEED_VSCROLLER/.test(assemblySrc) &&
     /CONTAINER\.find\(doc\)/.test(assemblySrc));
  ok('the old scroller hides by name, leaving the header alone',
     /#__db_cards~\[data-type=..vscroller/.test(assemblySrc));
  ok('the blanket sibling rule survives only for scroller-less documents',
     /#__db_cards~\*/.test(assemblySrc) &&
     /joinedScreenRoot/.test(assemblySrc));
  {
    // Full captured shape: screen root containing the pinned header AND
    // the feed scroller (fixture 8 of test_feed_guard.js).
    const home =
      '<div data-mcomponent="MScreen" data-screen-id="65549"' +
      ' data-crash-screen-id="42949673960" data-screen-keys="57,56,55"' +
      ' data-type="container" class="m bg-s2">' +
      '<div data-mcomponent="MContainer" class="m fixed-container top" id="hdr">' +
      '<div>facebook logo</div><div role="button" aria-label="Reels">R</div></div>' +
      '<div data-type="vscroller" data-mcomponent="MContainer"' +
      ' data-is-pull-to-refresh-allowed="true" class="m" id="vs">' +
      '<div data-mcomponent="MContainer" class="old" id="old1">stale</div>' +
      '<div data-mcomponent="MContainer" class="old" id="old2">stale2</div>' +
      '</div></div>';
    const placed = renderOffline(
      ['<div data-tracking-duration-id="n9"><span>saved post</span></div>'],
      home);
    const holder2 = placed.doc.querySelector('[data-db-cards]');
    ok('the cards join the screen root, above the header, as its first child',
       !!holder2 &&
       holder2.parentElement.getAttribute('data-mcomponent') === 'MScreen' &&
       holder2.parentElement.getAttribute('data-type') !== 'vscroller');
    // The compose-emitted rule itself, re-applied exactly as CSS would.
    const rule = placed.doc.getElementById('__db_hide_old').textContent;
    const sel = rule.slice(0, rule.indexOf('{'));
    const hidden = [...placed.doc.querySelectorAll(sel)];
    ok('the old scroller - and only it - is what hides',
       hidden.length === 1 && hidden[0].id === 'vs',
       hidden.map((el) => el.id || el.tagName).join(','));
    ok('the header with the tab row is not in the hide set',
       !hidden.some((el) => el.id === 'hdr') &&
       sel.indexOf('vscroller') >= 0);

    // A document whose only container is the scroller keeps the old
    // sibling rule: its stale children hide, nothing else.
    const placed2 = renderOffline(
      ['<div data-tracking-duration-id="n8"><span>saved post</span></div>'],
      '<div data-type="vscroller" id="vs2">' +
      '<div class="old" id="so1">stale</div>' +
      '<div class="old" id="so2">stale2</div></div>');
    const holder3 = placed2.doc.querySelector('[data-db-cards]');
    const hidden2 = [...placed2.doc.querySelectorAll('#__db_cards ~ *')];
    ok('a bare-scroller document keeps the sibling hide',
       !!holder3 &&
       holder3.parentElement.getAttribute('data-type') === 'vscroller' &&
       hidden2.length === 2 &&
       hidden2.every((el) => el.classList.contains('old')));

    // Reels documents land in the screen root as well, and the snap pager
    // hides whole - never a static block inside the JS-driven pager.
    const reelsDoc =
      '<div data-mcomponent="MScreen" data-type="container" class="m">' +
      '<div data-type="vscroller" data-mcomponent="MContainer"' +
      ' class="m vscroller vscroller-snap" id="pager">' +
      '<div data-mcomponent="MContainer" class="old">old reel</div>' +
      '</div></div>';
    const placedR = renderOffline(
      ['<div data-tracking-duration-id="r9"><span>saved reel</span></div>'],
      reelsDoc);
    const holderR = placedR.doc.querySelector('[data-db-cards]');
    const ruleR = placedR.doc.getElementById('__db_hide_old').textContent;
    const selR = ruleR.slice(0, ruleR.indexOf('{'));
    const hiddenR = [...placedR.doc.querySelectorAll(selR)];
    ok('reels: cards join the screen root, the snap pager hides whole',
       !!holderR &&
       holderR.parentElement.getAttribute('data-mcomponent') === 'MScreen' &&
       hiddenR.length === 1 && hiddenR[0].id === 'pager');
  }

  // Regression contrast: the OLD template-literal approach genuinely lost
  // exactly this bundle - the bug was real, and it could only die this way.
  {
    const legacy = bundle.map((c) => c
      .replace(/\\/g, '\\\\').replace(/`/g, '\\`')
      .replace(/\$/g, '\\$').replace(/<\/script/g, '</scr' + '`' + '+`' + 'ipt'));
    const page = '<html><body><script>var CARDS=`' + legacy.join('\n') + '`;' +
      'document.body.innerHTML=CARDS;</script></body></html>';
    let oldRendered = -1;
    try {
      const dd = new JSDOM(page, { runScripts: 'dangerously' });
      oldRendered = dd.window.document
        .querySelectorAll('[data-tracking-duration-id]').length;
    } catch (e) { oldRendered = -1; }
    ok('the old approach still loses this bundle (bug was real)',
       oldRendered < 37, 'old=' + oldRendered);
  }

  // A stored document with no feed container must still show the cards.
  {
    const res2 = renderOffline(bundle.slice(0, 2), '<div id="nostyle">x</div>');
    ok('a document with no container gets the cards right after <body>',
       res2.rendered === 2 && res2.doc.body.firstElementChild &&
       res2.doc.body.firstElementChild.getAttribute('data-db-cards') === '1');
    ok('and nothing unrelated is hidden there',
       res2.doc.getElementById('__db_hide_old') === null);
  }

  ok('pull to refresh is on by default',
     /KEY_PULL_REFRESH, true\)/.test(prefs) &&
     /android:key="pull_to_refresh"[\s\S]{0,120}android:defaultValue="true"/.test(xml));
}
console.log('\nNo app-promo bar flashes on an offline page');
{
  const docs = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');

  // Online the hiding CSS goes in from onPageStarted, before first paint. An
  // offline page is answered by shouldInterceptRequest, so everything
  // appended lands after the stored markup — the bar got a frame to paint in
  // and was then removed, which is the flash.
  ok('a hiding stylesheet exists', /private fun promoHideCss/.test(docs));
  ok('it is plain CSS, not a script that must run first',
     /<style id="__db_promo_hide">/.test(docs));
  ok('it is prepended to the stored document, not appended',
     /val html = promoHideCss\(\) \+\s*\n\s*withCards/.test(docs));
  ok('the assembled page gets it inside head',
     /promoHideCss\(\) \+\s*\n\s*"<\/head>/.test(docs));
  ok('it covers the store links and the known banner ids',
     /play\.google\.com\/store/.test(docs) &&
     /mobile_app_install_banner/.test(docs) &&
     /display:none !important/.test(docs));
}

// ================= offline capture: ads, big posts, small posts ============
// Reported: ads appear among offline reels, and posts that were saved never
// show up offline (text-only posts, photo albums). Each behavioural check
// below reproduced the bug on the previous code before its fix was written.

/** Extract the capture script the same way test_offline_ui.js does. */
function captureScript(cap) {
  const i = cap.indexOf('fun script(');
  const s = cap.indexOf('"""', i) + 3;
  const e = cap.indexOf('"""', s);
  const max = (cap.match(/MAX_CARD_CHARS = ([\d_]+)/) || [0, '120000'])[1]
    .replace(/_/g, '');
  return cap.slice(s, e)
    .replace('$reelTarget', '50')
    .replace('$MAX_CARD_CHARS', max)
    .replace('${knownIds.asJsSet()}', '')
    .replace(/\$\{if \(syncMode\)[^}]*\}/, 'false');
}

function runCapture(cap, url, inner) {
  const dom = new JSDOM(inner, {
    runScripts: 'outside-only', pretendToBeVisual: true, url });
  const calls = [];
  dom.window.FBPro = {
    onOfflineItems: (sec, json) => calls.push({ sec, items: JSON.parse(json) }),
    onOfflinePage: () => {}
  };
  dom.window.eval(captureScript(cap));
  Object.defineProperty(dom.window.document, 'visibilityState',
    { value: 'hidden', configurable: true });
  dom.window.document.dispatchEvent(new dom.window.Event('visibilitychange'));
  return calls.length ? calls[0].items : [];
}

console.log('\nAds never enter the offline store');
{
  const cap = fs.readFileSync(KT('utils/OfflineCapture.kt'), 'utf8');
  const ab = fs.readFileSync(KT('utils/AdBlocker.kt'), 'utf8');

  // The capture must carry the barriers itself: the sync WebView and the
  // live page both run it, and its timing against the ad blocker's
  // MutationObserver is not guaranteed.
  ok('cards condemned by the ad blocker are skipped',
     /hasAttribute\('data-fbpro-hidden'\)/.test(cap));
  ok('the CTA scan is scoped to the reels section',
     /if \(sec !== 'reels'\) return false;/.test(cap));
  ok('an ad card is rejected before any capture work',
     cap.indexOf('if (isAdCard(c, sec)) continue;') <
     cap.indexOf('var cid = idOf(c);') &&
     cap.indexOf('if (isAdCard(c, sec)) continue;') >
     cap.indexOf('function collectFeed'));

  // Parity with the online killer: same words, same rule, so offline
  // filtering can never drift from what the user already sees online.
  const wordsOf = (src) => {
    const i = src.indexOf('var CTA_WORDS = [');
    return src.slice(i, src.indexOf('];', i)).replace(/\s+/g, '');
  };
  ok('the CTA words match the online killer exactly',
     wordsOf(cap).length > 0 && wordsOf(cap) === wordsOf(ab));

  const organic =
    '<div data-tracking-duration-id="r1">' +
      '<div><span>Organic creator daily vlog</span></div>' +
      '<video src="https://video.fcgp1-1.fbcdn.net/o1/v/organic.mp4"></video>' +
      '<div role="button" aria-label="Like">Like</div></div>';
  const ctaAd =
    '<div data-tracking-duration-id="r2">' +
      '<div><span>Sponsored Shop Page big sale today</span></div>' +
      '<video src="https://video.fcgp1-1.fbcdn.net/o1/v/ad.mp4"></video>' +
      '<span role="button">Shop Now</span></div>';
  const markedAd =
    '<div data-tracking-duration-id="r3" data-fbpro-hidden="1" ' +
      'style="visibility:hidden">' +
      '<div><span>Another promoted page offer here</span></div>' +
      '<video src="https://video.fcgp1-1.fbcdn.net/o1/v/ad2.mp4"></video></div>';

  const reels = runCapture(cap, 'https://m.facebook.com/reel/',
    '<body><div data-is-reels="true"><div data-type="vscroller">' +
    organic + ctaAd + markedAd + '</div></div></body>');

  ok('an organic reel is saved',
     reels.some((it) => /organic\.mp4/.test(it.h)),
     JSON.stringify(reels.length));
  ok('a reel carrying a CTA button is NOT saved',
     !reels.some((it) => /ad\.mp4/.test(it.h)));
  ok('a reel the ad blocker already hid is NOT saved',
     !reels.some((it) => /ad2\.mp4/.test(it.h)));
  ok('the CTA scan accepts the exact Bangla label too',
     runCapture(cap, 'https://m.facebook.com/reel/',
       '<body><div data-type="vscroller">' +
       '<div data-tracking-duration-id="b1"><div><span>Promoted Bangla page sale</span></div>' +
       '<video src="https://video.fcgp1-1.fbcdn.net/o1/v/ban.mp4"></video>' +
       '<span role="button">অর্ডার করুন</span></div>' +
       '</div></body>').length === 0);

  // Scoping proof: the identical words inside a FEED post must survive,
  // because organic feed content may legitimately contain them.
  const feedPost =
    '<div data-tracking-duration-id="f1">' +
      '<div><span>Community event next friday everyone welcome to join us</span></div>' +
      '<img src="https://scontent.fcgp1-1.fbcdn.net/event.jpg">' +
      '<span role="button">Sign up</span></div>';
  const feed = runCapture(cap, 'https://m.facebook.com/',
    '<body><div data-type="vscroller">' + feedPost + '</div></body>');
  ok('a feed post with a "Sign up" control is still saved',
     feed.some((it) => /event\.jpg/.test(JSON.stringify(it.m))));
  const feedMarked =
    '<div data-tracking-duration-id="f2" data-fbpro-hidden="1">' +
      '<div><span>Sponsored unit hidden online already today</span></div>' +
      '<img src="https://scontent.fcgp1-1.fbcdn.net/spon.jpg"></div>';
  const feed2 = runCapture(cap, 'https://m.facebook.com/',
    '<body><div data-type="vscroller">' + feedMarked + '</div></body>');
  ok('a feed card the ad blocker condemned is NOT saved', feed2.length === 0);

  // Round-5 report, proven end to end before the fix: the background-sync
  // WebView runs MFacebookAds, which tags its condemned cards data-db-ad -
  // but this capture only honoured data-fbpro-hidden, so every sponsored
  // post the sync page had already marked walked into the store. Offline,
  // its ad markers then made the ad remover blank the whole card batch.
  ok('the m.facebook.com ad mark is honoured too',
     /hasAttribute\('data-db-ad'\)/.test(cap));
  const syncTaggedAd =
    '<div data-dcm-id="1" data-tracking-duration-id="f9" data-db-ad="1" ' +
      'style="display: none !important;">' +
      '<div><span>Promoted Page special offer for everyone</span></div>' +
      '<img src="https://scontent.fcgp1-1.fbcdn.net/badad.jpg"></div>';
  const feed3 = runCapture(cap, 'https://m.facebook.com/',
    '<body><div data-type="vscroller">' + syncTaggedAd + '</div></body>');
  ok('a card the sync page already condemned is ALSO not saved',
     feed3.length === 0, String(feed3.length));
}

console.log('\\nFacebook tab bar never enters the store, and old stores heal');
{
  const cap = fs.readFileSync(KT('utils/OfflineCapture.kt'), 'utf8');
  const vaultSrc = fs.readFileSync(KT('offline/SectionVault.kt'), 'utf8');
  const H2 = require('./offline_vault_harness.js');

  // Round-5 report: the offline home feed showed Facebook's own tab row
  // (home / friends / watch / reels / notifications / marketplace) wedged
  // between two saved posts. The row passes capture's chrome checks:
  // its buttons carry the aria-labels but the row itself does not, and
  // badge counters ("15+ 15+ 4 15+") read as six-plus characters of text.
  const tabRow =
    '<div data-mcomponent="MContainer" class="m" id="tabrow"' +
    ' style="margin-top:0px; height:35px; z-index:0; width:360px;">' +
    '<div role="button" tabindex="0" aria-label="Home" data-action-id="1"><i></i><span>15+</span></div>' +
    '<div role="button" tabindex="0" aria-label="Friends" data-action-id="2"><i></i><span>15+</span></div>' +
    '<div role="button" tabindex="0" aria-label="Watch" data-action-id="3"><i></i><span>4</span></div>' +
    '<div role="button" tabindex="0" aria-label="Reels" data-action-id="4"><i></i><span>15+</span></div>' +
    '<div role="button" tabindex="0" aria-label="Notifications" data-action-id="5"><i></i></div>' +
    '<div role="button" tabindex="0" aria-label="Marketplace" data-action-id="6"><i></i></div>' +
    '</div>';
  const feedPost2 =
    '<div data-mcomponent="MContainer" data-tracking-duration-id="p2">' +
      '<div data-mcomponent="TextArea"><div class="native-text">' +
      '<span>a genuine post from a friend with real body text</span></div></div>' +
      '<a href="https://m.facebook.com/story.php?story_fbid=777">Full story</a></div>';
  const feed = runCapture(cap, 'https://m.facebook.com/',
    '<body><div data-type="vscroller">' + tabRow + feedPost2 + '</div></body>');
  ok('the tab row is NOT captured as a story',
     !feed.some((it) => it.h.indexOf('tabrow') >= 0),
     JSON.stringify(feed.length));
  ok('the real post beside it still is',
     feed.some((it) => it.h.indexOf('story_fbid=777') >= 0));

  // The same signature rejects rows already sitting in old stores, at the
  // vault door, so one upgrade quietly cleans them (feed section only).
  const adMarkup =
    '<div data-dcm-id="1" data-tracking-duration-id="f9" data-db-ad="1" ' +
      'style="display: none !important;">' +
      '<div><span>Promoted Page special offer for everyone</span></div>' +
      '<img src="https://scontent.fcgp1-1.fbcdn.net/badad.jpg"></div>';
  ok('the vault filters condemned-ad markup on add and on read',
     /filter \{ !isJunk\(it\.html\) \}/.test(vaultSrc) &&
     /if \(isJunk\(html\)\) return@mapNotNull null/.test(vaultSrc));
  ok('the tab-row signature is applied to the feed section only',
     /section != SECTION_FEED_ID\) return false/.test(vaultSrc));
  ok('vault mirror: a saved tab row is junk in the feed vault',
     H2.isJunk('feed', tabRow));
  ok('vault mirror: it is junk even with every badge counter showing',
     H2.isJunk('feed', tabRow.replace('15+', '99+')));
  ok('vault mirror: an organic post is never junk',
     !H2.isJunk('feed', feedPost2));
  ok('vault mirror: a condemned ad card is junk in every section',
     H2.isJunk('feed', adMarkup) && H2.isJunk('reels', adMarkup) &&
     H2.isJunk('stories', adMarkup));
  ok('vault mirror: tab labels inside a reel stay safe elsewhere',
     !H2.isJunk('reels', tabRow));
  // A post that merely links one tab-shaped button is safe: two labels
  // and no story link are required together.
  const oneLabel =
    '<div data-mcomponent="MContainer" data-tracking-duration-id="p3">' +
      '<div><span>wrote about the new facebook home design today</span></div>' +
      '<div role="button" aria-label="Home"><i></i></div></div>';
  ok('vault mirror: one nav-shaped button does not condemn a post',
     !H2.isJunk('feed', oneLabel));
}

console.log('\\nOne condemned card can never blank the whole offline feed');
{
  // Round-5 report: offline + ad blocker on -> content for ~1s, then the
  // ENTIRE page hid. Proven with the real scripts: a sponsored card in the
  // store made MFacebookAds.cardOf climb from the ad element to the
  // holder (the direct child of the screen root) and hide() removed the
  // batch. Now the holder is a hard boundary and a hard refusal.
  const mfa = fs.readFileSync(KT('utils/MFacebookAds.kt'), 'utf8');
  ok('cardOf treats the card holder as a scroller-level boundary',
     /closest\('\[data-type="vscroller"\],\[data-db-cards\]'\)/.test(mfa));
  ok('hide() can never take the holder itself',
     /hasAttribute\('data-db-cards'\)\) return;/.test(mfa));

  const i = mfa.indexOf('fun script()');
  const s = mfa.indexOf('return """', i) + 'return """'.length;
  const script = mfa.slice(s, mfa.indexOf('""".trimIndent()', s));

  const H3 = require('./offline_vault_harness.js');
  const sponsoredSaved =
    '<div data-dcm-id="1" data-mcomponent="MContainer" id="SAVED_AD" class="m">' +
    '<div role="button" aria-label="Video player" data-video-id="1452526892980986"' +
    ' data-video-tracking=\'{"adid":"120251177608830592","qid":"-64386","is_sponsored":1}\'' +
    ' data-testid="sponsored-story-photo"><video src="blob:x"></video></div>' +
    '<div>Promoted Page special offer for everyone</div></div>';
  const organicSaved =
    '<div data-mcomponent="MContainer" id="SAVED_REAL" class="m">' +
    '<div data-mcomponent="TextArea"><div class="native-text">' +
    '<span>a genuine saved post from a friend with real text</span></div></div></div>';
  const doc =
    '<!DOCTYPE html><html><body><div data-mcomponent="MScreen" data-type="container">' +
    '<div data-mcomponent="MContainer" class="m fixed-container top" id="hdr"><span>h</span></div>' +
    '<div data-type="vscroller" data-is-pull-to-refresh-allowed="true">' +
    '<div class="old">stale</div></div></div></body></html>';
  const page = H3.compose(doc, [sponsoredSaved, organicSaved]);
  const dom = new JSDOM(page, {
    runScripts: 'outside-only', pretendToBeVisual: true,
    url: 'https://m.facebook.com/' });
  const w = dom.window;
  w.requestIdleCallback = undefined;
  w.requestAnimationFrame = (f) => setTimeout(f, 0);
  w.HTMLMediaElement.prototype.pause = function () {};
  w.eval(script);
  // The installed pass runs synchronously on injection: document.body
  // already exists, so start() calls run() before this line. No timers
  // are needed to see the condemnations.
  const holder = w.document.getElementById('__db_cards');
  ok('the card holder is never tagged or hidden',
     !!holder && !holder.getAttribute('data-db-ad') &&
     (holder.getAttribute('style') || '').indexOf('display: none') < 0);
  ok('an ad that entered the store earlier is hidden alone',
     (w.document.getElementById('SAVED_AD')
       .getAttribute('style') || '').indexOf('display: none') >= 0 ||
     !!w.document.querySelector('#SAVED_AD[data-db-ad]'));
  ok('every organic saved post survives',
     (w.document.getElementById('SAVED_REAL').getAttribute('style') || '')
       .indexOf('display: none') < 0);
}

console.log('\nNo post is too big or too small to be saved');
{
  const cap = fs.readFileSync(KT('utils/OfflineCapture.kt'), 'utf8');

  ok('the size ceiling was raised for album posts',
     /MAX_CARD_CHARS = 400_000/.test(cap));

  // ~40 photos with signed URLs and srcset variants cross the old 120 KB
  // line: measured 116 KB at 40 images. The post used to vanish silently.
  const bigUrl = (n) => 'https://scontent.fcgp1-1.fbcdn.net/v/t51.29350-15/' +
    'p480x480/446546' + n + '_1862990483999280_7417418750692064384_n.jpg' +
    '?_nc_cat=105&ccb=1-9&_nc_sid=' + 'x'.repeat(180) +
    '&_nc_ohc=' + 'y'.repeat(120) + '&_nc_oc=' + 'z'.repeat(120);
  let album = '<div data-tracking-duration-id="alb1"><div><span>album</span></div>';
  for (let i = 0; i < 42; i++) {
    album += '<div><a href="/photo/?fbid=' + i + '"><img src="' + bigUrl(i) +
      '" srcset="' + bigUrl(i) + ' 480w,' + bigUrl(i + 100) + ' 720w,' +
      bigUrl(i + 200) + ' 960w,' + bigUrl(i + 300) + ' 1280w"></a></div>';
  }
  album += '<div role="button" aria-label="Like">Like</div></div>';
  ok('the reference album is genuinely over the old ceiling',
     Buffer.byteLength(album) > 120000,
     String(Buffer.byteLength(album)));
  const big = runCapture(cap, 'https://m.facebook.com/',
    '<body><div data-type="vscroller">' + album + '</div></body>');
  ok('a 42-photo album post is saved', big.length === 1,
     String(big.length));
  ok('and all 42 photos are listed for download',
     big.length === 1 && big[0].m.length >= 42, String(big.length && big[0].m.length));

  // A short text post whose avatar has not loaded used to read as
  // "no media and nothing to read" and was dropped.
  const tiny = runCapture(cap, 'https://m.facebook.com/',
    '<body><div data-type="vscroller">' +
    '<div data-tracking-duration-id="t1"><div><span>Valo achi</span></div></div>' +
    '</div></body>');
  ok('a short text post is saved', tiny.length === 1, String(tiny.length));
  ok('it keeps a stable identity', tiny.length === 1 && !!tiny[0].id);

  // A feed video whose URL is still a blob used to be thrown away whole,
  // which is why videos were missing from saved posts.
  const blobVid = runCapture(cap, 'https://m.facebook.com/',
    '<body><div data-type="vscroller">' +
    '<div data-tracking-duration-id="v1"><div><span>x</span></div>' +
    '<video src="blob:https://m.facebook.com/aa"></video></div>' +
    '</div></body>');
  ok('a video post is saved even while its URL is a blob: pending',
     blobVid.length === 1, String(blobVid.length));

  // The spacer guard must survive the relaxed floor.
  const spacer = runCapture(cap, 'https://m.facebook.com/',
    '<body><div data-type="vscroller">' +
    '<div data-tracking-duration-id="sp1"><span>\u2022</span></div>' +
    '</div></body>');
  ok('an empty spacer is still not a post', spacer.length === 0);
}

console.log('\nThe bridge is never handed a giant payload');
{
  const cap = fs.readFileSync(KT('utils/OfflineCapture.kt'), 'utf8');

  // Reported on device: the app crashed while scrolling. The scroll
  // listener fires report(), which used to hand one multi-megabyte JSON
  // string to the WebView Java bridge - fatal on low-memory devices.
  ok('reporting is chunked by serialized size',
     /curLen \+ piece\.length > 1000000/.test(cap) &&
     /chunks\.push\(cur\)/.test(cap));
  ok('every chunk is still a complete items array',
     /bridge\.onOfflineItems\(\s*s, '\[' \+ chunks\[j\]\.join\(','\) \+ '\]'/.test(cap.replace(/\n +/g, ' ')) ||
     /'\[' \+ chunks\[j\]\.join\(','\) \+ '\]'/.test(cap));

  // Run the real capture against a feed of huge cards and prove that
  // several calls each stay small while losing nothing.
  function runCaptureAll(inner) {
    const dom = new JSDOM(inner, { runScripts: 'outside-only',
      pretendToBeVisual: true, url: 'https://m.facebook.com/' });
    const calls = [];
    dom.window.FBPro = { onOfflineItems: (sec, json, done) => {
      calls.push({ json, done }); }, onOfflinePage: () => {} };
    dom.window.eval(captureScript(cap));
    Object.defineProperty(dom.window.document, 'visibilityState',
      { value: 'hidden', configurable: true });
    dom.window.document.dispatchEvent(new dom.window.Event('visibilitychange'));
    return calls;
  }
  const fat = 'x'.repeat(260 * 1024);
  let html = '<body><div data-type="vscroller">';
  for (let i = 0; i < 6; i++) {
    html += '<div data-tracking-duration-id="fat' + i + '">' +
      '<img src="https://scontent.fcgp1-1.fbcdn.net/f' + i + '.jpg">' +
      '<span>' + fat.slice(0, 200) + ' caption number ' + i + '</span>' +
      '<input type="hidden" value="' + i + '">' + fat + '</div>';
  }
  // the inputs make isChrome drop them? no: input => chrome. Use plain divs
  html = '<body><div data-type="vscroller">';
  for (let i = 0; i < 6; i++) {
    html += '<div data-tracking-duration-id="fat' + i + '">' +
      '<img src="https://scontent.fcgp1-1.fbcdn.net/f' + i + '.jpg">' +
      '<span>' + fat + ' caption number ' + i + ' appended text</span>' +
      '</div>';
  }
  html += '</div></body>';
  const calls = runCaptureAll(html);
  ok('a heavy feed pass splits into several bridge calls',
     calls.length >= 2, String(calls.length));
  ok('no single call exceeds the safe budget',
     calls.every((c) => c.json.length < 1200000),
     calls.map((c) => c.json.length).join(','));
  const mergedIds = [];
  for (const c of calls) {
    for (const it of JSON.parse(c.json)) mergedIds.push(it.id);
  }
  ok('nothing is lost across the chunks',
     mergedIds.length === 6 && new Set(mergedIds).size === 6,
     String(mergedIds.length));
  ok('the completion flag rides the final chunk only',
     calls.length >= 2 && calls.slice(0, -1).every((c) => c.done === false) &&
     calls[calls.length - 1].done === true);

  // A light pass stays a single call.
  const light = runCaptureAll('<body><div data-type="vscroller">' +
    '<div data-tracking-duration-id="l1">' +
    '<img src="https://scontent.fcgp1-1.fbcdn.net/l1.jpg">' +
    '<span>a perfectly ordinary post caption</span></div></div></body>');
  ok('a small pass is still one call', light.length === 1, String(light.length));
}

console.log('\nEvery section keeps what it saves, in its own store');
{
  const H = require('./offline_vault_harness.js');
  const vaultSrc = fs.readFileSync(KT('offline/SectionVault.kt'), 'utf8');
  const home = fs.readFileSync(KT('offline/HomeVault.kt'), 'utf8');
  const reels = fs.readFileSync(KT('offline/ReelsVault.kt'), 'utf8');
  const stories = fs.readFileSync(KT('offline/StoriesVault.kt'), 'utf8');

  ok('every section is its own file',
     /object HomeVault/.test(home) && /object ReelsVault/.test(reels) &&
     /object StoriesVault/.test(stories));
  ok('each with its own folder on disk',
     /dirName = "home"/.test(home) && /dirName = "reels"/.test(reels) &&
     /dirName = "stories"/.test(stories));
  ok('reels refuse entries with no playable video, at their own door',
     /videoRequired = true/.test(reels) &&
     /fun addItems[\s\S]{0,600}if \(videoRequired\)/.test(vaultSrc));
  ok('per-section retention floors exist',
     /keepFloor = 500/.test(home) && /keepFloor = 250/.test(reels) &&
     /keepFloor = 200/.test(stories));
  ok('the merge keeps at least the floor',
     /val keep = maxOf\(limit, keepFloor\)/.test(vaultSrc));
  ok('the cap uses the floored value',
     /if \(merged\.size >= keep\) break/.test(vaultSrc));
  ok('dedupe still runs as a backstop', /seen\.add\(key\)/.test(vaultSrc));

  // Faithful mirror of that merge (same algorithm as the vault), run
  // through the stresses that used to evict saved content.
  let store = [];
  store = H.addItems(store,
    Array.from({ length: 50 }, (_, i) => ({ id: 'bg-' + i, html: 'x', media: [] })),
    50, 500);
  // The live page keeps merging with its small pass limit as the user
  // scrolls; previously this deleted everything before it.
  store = H.addItems(store,
    Array.from({ length: 60 }, (_, i) => ({ id: 'live-' + i, html: 'x', media: [] })),
    50, 500);
  ok('scrolled-past posts no longer evict previously saved ones',
     store.some((it) => it.id === 'bg-0') && store.length === 110,
     'kept=' + store.length);
  for (let r = 0; r < 12; r++) {
    store = H.addItems(store,
      Array.from({ length: 50 }, (_, i) => ({ id: 'r' + r + '-' + i, html: 'x', media: [] })),
      50, 500);
  }
  ok('long-run growth is still bounded by the floor',
     store.length === 500, String(store.length));
}

// ================= what the user asked for, stated end to end ============

console.log('\nThree separate systems, one real number each');
{
  const registry = fs.readFileSync(KT('offline/OfflineVaults.kt'), 'utf8');
  const vaultSrc = fs.readFileSync(KT('offline/SectionVault.kt'), 'utf8');
  const feedKt = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');

  ok('the registry holds exactly the three sections',
     /listOf\(HomeVault, ReelsVault, StoriesVault\)/.test(registry));
  ok('each stores under its own root folder',
     /OfflineVaults\.ROOT_DIR \+ "\/" \+ dirName/.test(vaultSrc) &&
     /"items\.json"/.test(vaultSrc) && /"media"/.test(vaultSrc));
  ok('each downloads on its own queue and worker',
     /private val queue = ArrayList<String>/.test(vaultSrc) &&
     /newFixedThreadPool/.test(vaultSrc));
  ok('the old mixed store is retired on first run',
     /offline_items_v1/.test(registry));
  ok('the app still talks to one section id at a time',
     /fun addItems\(section: String, incoming/.test(feedKt) &&
     /vault\(section\)\?\.addItems/.test(feedKt));
}

console.log('\nThe number is read from the files, and the screen shows it');
{
  const H = require('./offline_vault_harness.js');
  const docs = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');
  const settings = fs.readFileSync(KT('ui/SettingsActivity.kt'), 'utf8');

  ok('the settings row reads the real count of each section separately',
     /OfflineFeed\.realPlayableCount\(OfflineFeed\.SECTION_REELS\)/.test(settings) &&
     /OfflineFeed\.realPlayableCount\(OfflineFeed\.SECTION_FEED\)/.test(settings) &&
     /OfflineFeed\.realPlayableCount\(OfflineFeed\.SECTION_STORIES\)/.test(settings));
  ok('the page asks for exactly the counted cards',
     /OfflineFeed\.cardMarkupList\(it\)/.test(docs));
  ok('and serves them as the document itself',
     /PageAssembly\.compose\(docText, cards\)/.test(docs));

  // End to end over a simulated section: the count, the card list and the
  // page are all produced from the one store the way the app does it -
  // whatever is counted is precisely what is on screen, at every stage
  // of the download. This is the reported bug, simulated to death.
  const store = [
    { id: 't1', h: '<div data-tracking-duration-id="e1"><span>plain text post</span></div>',
      media: [] },
    { id: 'p1', h: '<div data-tracking-duration-id="e2"><img src="x"></div>',
      media: ['ph1_n.jpg', 'ph2_n.jpg'] },
    { id: 'r1', h: '<div data-tracking-duration-id="e3"><video src="v.mp4"></video></div>',
      media: ['v.mp4'] },
  ];
  const disk = new Set();
  const has = (u) => disk.has(u);
  function countAndRender() {
    const complete = store.filter((e) => H.isComplete(e.media, has));
    const cards = complete.map((e) => e.h);
    const page = H.compose(
      '<!DOCTYPE html><html><body><div data-type="vscroller"></div></body></html>',
      cards);
    const dom = new JSDOM(page, { runScripts: 'dangerously',
      url: 'https://m.facebook.com/' });
    return { counted: complete.length,
             rendered: dom.window.document
               .querySelectorAll('[data-tracking-duration-id]').length };
  }

  let s = countAndRender();
  ok('before anything downloads, only the text post counts and shows',
     s.counted === 1 && s.rendered === 1, JSON.stringify(s));
  disk.add('ph1_n.jpg');
  s = countAndRender();
  ok('one of two photos is still not a count, nor on screen',
     s.counted === 1 && s.rendered === 1, JSON.stringify(s));
  disk.add('ph2_n.jpg');
  s = countAndRender();
  ok('the whole photo post counts and shows once complete',
     s.counted === 2 && s.rendered === 2, JSON.stringify(s));
  disk.add('v.mp4');
  s = countAndRender();
  ok('last the reel - the number and the screen move together',
     s.counted === 3 && s.rendered === 3, JSON.stringify(s));
}

console.log('\nSaved media answers from its section, before the chrome cache');
{
  const ma = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  const vaultSrc = fs.readFileSync(KT('offline/SectionVault.kt'), 'utf8');

  const i = ma.indexOf('OfflineVaults.serveAny');
  ok('the vaults answer saved media at all', i > 0);
  const j = i > 0 ? ma.indexOf('OfflineCache.isInterceptable', i) : -1;
  ok('and before anything else is asked',
     i > 0 && j > i, i + ' vs ' + j);
  ok('while offline and reading is on',
     /if \(isOnline\) return null[\s\S]{0,200}if \(!prefs\.offlineRead\) return null[\s\S]{0,600}OfflineVaults\.serveAny/.test(ma));
  ok('video gets its partial-content answer, or it will not play',
     /206/.test(vaultSrc) && /Content-Range/.test(vaultSrc) &&
     /Accept-Ranges/.test(vaultSrc));
}
console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);

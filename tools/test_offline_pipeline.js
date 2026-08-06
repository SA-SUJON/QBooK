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

// ------------------------------- an item counts only when it is fully saved
//
// The old rule was `any {}`: one cached asset was enough. A post with five
// photos counted as saved when one had arrived, so the number climbed while
// the content behind it was still downloading.
console.log('\nCounting waits for the whole item');
{
  const feed = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');

  ok('there is one rule for what counts',
     /fun isFullyDownloaded\(item: Item\): Boolean/.test(feed));
  // Not "every URL": that was tried and left every item permanently
  // incomplete, because capture records srcset variants that are never all
  // fetched. The rule is per-kind — see the section below.
  ok('the item is judged by kind, not by URL count',
     /val videos = item\.media\.filter \{ isVideoUrl\(it\) \}/.test(feed));
  ok('the loose any-of test is gone',
     !/item\.media\.any \{ u ->[\s\S]{0,120}OfflineCache\.has\(u\)/.test(feed));
  ok('a video must also be a plausible size, not merely present',
     /OfflineCache\.hasMinSize\(it, MIN_VIDEO_BYTES\)/.test(feed));
  ok('a text post with no media still counts',
     /if \(item\.media\.isEmpty\(\)\) return true/.test(feed));

  ok('posts, reels and stories share the rule',
     /fun realPlayableCount\(section: String\): Int =\s*\n\s*loadItems\(section\)\.count \{ isFullyDownloaded/.test(feed));
  ok('what is displayed uses the same rule as what is counted',
     /fun realPlayableItems[\s\S]{0,160}isFullyDownloaded/.test(feed) &&
     /fun cardsHtml[\s\S]{0,120}realPlayableItems/.test(feed));

  // Exercise the rule itself rather than trusting its shape.
  const MIN = 500000;
  const disk = {};
  const isVideo = (u) => /\/o1\/v\/|\.mp4|video/.test(u);
  const full = (media) => media.length === 0 ? true : media.every((u) =>
    isVideo(u) ? (disk[u] !== undefined && disk[u] >= MIN) : disk[u] !== undefined);

  const set = (o) => { for (const k of Object.keys(disk)) delete disk[k]; Object.assign(disk, o); };

  set({ 'a.jpg': 9 });
  ok('a five-photo post does not count on the first photo',
     full(['a.jpg', 'b.jpg', 'c.jpg', 'd.jpg', 'e.jpg']) === false);
  set({ 'a.jpg': 9, 'b.jpg': 9, 'c.jpg': 9, 'd.jpg': 9, 'e.jpg': 9 });
  ok('and does once they are all there',
     full(['a.jpg', 'b.jpg', 'c.jpg', 'd.jpg', 'e.jpg']) === true);
  set({ 'av_s.jpg': 4000 });
  ok('a reel does not count on its avatar alone',
     full(['av_s.jpg', '/o1/v/vid']) === false);
  set({ 'av_s.jpg': 4000, '/o1/v/vid': 1000 });
  ok('nor on a truncated video',
     full(['av_s.jpg', '/o1/v/vid']) === false);
  set({ 'av_s.jpg': 4000, '/o1/v/vid': 9000000 });
  ok('but does once the video is really there',
     full(['av_s.jpg', '/o1/v/vid']) === true);
  ok('a text post counts immediately', full([]) === true);

  // ---------------------------------------------------------------
  // A post whose only images are chrome must still be readable.
  //
  // Reported as: offline shows only a handful of the posts that were saved.
  //
  // Capture records every <img> inside a card, so an ordinary text update
  // carries the author's avatar and any emoji in the body. The rule then read
  // that as "this item has media and none of it arrived" and hid the post -
  // even though the words were already in the stored markup and there was
  // nothing left to wait for. On a feed of text updates that hides almost
  // everything.
  //
  // Faithful port of the real predicates, so this exercises the decision and
  // not a paraphrase of it.
  const isVideoUrl = (u) => {
    const c = u.split('?')[0].toLowerCase();
    return c.endsWith('.mp4') || c.endsWith('.webm') || c.includes('/v/t2/');
  };
  const isAvatar = (u) => {
    const c = u.split('?')[0];
    return c.includes('/t39.30808-1/') || (c.includes('profile') && c.includes('_s.'));
  };
  const isChrome = (u) => {
    const c = u.split('?')[0].toLowerCase();
    return c.includes('/emoji.php/') || c.includes('static.xx.fbcdn.net') ||
           c.includes('/rsrc.php/') || c.endsWith('.svg');
  };
  // Strict per-photo port: every distinct photo needs one cached variant.
  // Mirrors OfflineFeed.photoKey(): filename without query, size markers
  // removed (img_1080x1080_x.jpg -> img_x.jpg, p_640.jpg -> p.jpg).
  const photoKey = (u) => {
    const stem = u.split('?')[0].split('/').pop().toLowerCase();
    return stem.replace(/[._-][0-9]+x[0-9]+/g, '').replace(/_[0-9]+(?=\.)/, '');
  };
  const shown = (media, cache) => {
    if (media.length === 0) return true;
    const has = (u) => cache[u] !== undefined;
    const hasMin = (u) => (cache[u] || 0) >= MIN;
    const videos = media.filter(isVideoUrl);
    const images = media.filter((u) => !isVideoUrl(u));
    if (videos.length) return videos.some(hasMin);
    const photos = images.filter((u) => !isAvatar(u) && !isChrome(u));
    if (photos.length === 0) return true;
    const groups = {};
    for (const p of photos) {
      const k = photoKey(p);
      (groups[k] = groups[k] || []).push(p);
    }
    return Object.values(groups).every((vs) => vs.some(has));
  };

  const AV = 'https://scontent.xx.fbcdn.net/v/t39.30808-1/1_s.jpg';
  const PHOTO = 'https://scontent.xx.fbcdn.net/v/t51.0-10/photo_n.jpg';
  const EMOJI = 'https://static.xx.fbcdn.net/images/emoji.php/v9/t4b/1/16/1f600.png';
  const VID = 'https://video.xx.fbcdn.net/v/t2/reel.mp4';

  ok('a text post carrying only the author avatar is shown',
     shown([AV], {}) === true);
  ok('and one carrying an avatar and an emoji is shown',
     shown([AV, EMOJI], {}) === true);
  ok('a photo post is still withheld until the photo arrives',
     shown([AV, PHOTO], { [AV]: 4000 }) === false);
  ok('and shown once it has',
     shown([AV, PHOTO], { [AV]: 4000, [PHOTO]: 90000 }) === true);
  ok('a reel is still withheld while its video downloads',
     shown([AV, VID], { [AV]: 4000, [VID]: 120000 }) === false);
  ok('and shown once the video is really there',
     shown([AV, VID], { [AV]: 4000, [VID]: 9000000 }) === true);

  // The reported bug: the settings count and the offline display used to
  // jump the moment the FIRST photo of a multi-photo post landed.
  const P1 = 'https://scontent.xx.fbcdn.net/v/t51.2885-15/4111001_n.jpg';
  const P2 = 'https://scontent.xx.fbcdn.net/v/t51.2885-15/4222002_n.jpg';
  const P3 = 'https://scontent.xx.fbcdn.net/v/t51.2885-15/4333003_n.jpg';
  ok('a three-photo post does NOT count when only its first photo has landed',
     shown([P1, P2, P3], { [P1]: 90000 }) === false,
     'the old rule counted the post here - the premature count');
  ok('nor when two of three are on disk',
     shown([P1, P2, P3], { [P1]: 90000, [P2]: 90000 }) === false);
  ok('it counts once every photo has a variant stored',
     shown([P1, P2, P3], { [P1]: 90000, [P2]: 90000, [P3]: 90000 }) === true);
  ok('srcset variants of one photo still collapse to a single photo',
     photoKey('https://scontent.xx.fbcdn.net/v/t51.2885-15/p480x480/4111001_n.jpg') ===
     photoKey('https://scontent.xx.fbcdn.net/v/t51.2885-15/4111001_n.jpg?stp=dst-jpg'));
  ok('dimension-suffixed variants collapse too',
     photoKey('https://scontent.xx.fbcdn.net/v/t51.2885-15/img_1080x1080_n.jpg') ===
     photoKey('https://scontent.xx.fbcdn.net/v/t51.2885-15/img_n.jpg'));
  ok('two different photos never merge into one group',
     photoKey(P1) !== photoKey(P2));

  const feedSrc = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');
  ok('the chrome test exists in the source',
     /private fun isChrome\(url: String\): Boolean/.test(feedSrc));
  ok('content photos are separated from avatar and chrome first',
     /val photos = images\.filter \{ !isAvatar\(it\) && !isChrome\(it\) \}/
       .test(feedSrc));
  ok('and the nothing-to-wait-for return sits after that split',
     feedSrc.indexOf('val photos = images.filter') <
     feedSrc.indexOf('if (photos.isEmpty()) return true'));
  ok('every distinct photo must have a variant on disk',
     /groups\.values\.all \{ variants -> variants\.any \{ OfflineCache\.has\(it\) \} \}/
       .test(feedSrc));
  ok('srcset variants are grouped by photo identity',
     /photos\.groupBy \{ photoKey\(it\) \}/.test(feedSrc) &&
     /private fun photoKey\(url: String\): String/.test(feedSrc));
}

// ------------------------------------------- tapping Stories offline works
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
console.log('\nThe pipeline runs in the order it documents');
{
  const sync = fs.readFileSync(KT('utils/OfflineSync.kt'), 'utf8');
  const bsm = fs.readFileSync(KT('utils/BackgroundSyncManager.kt'), 'utf8');

  // targetFor used to raise every request to the V4 constants, so step 1
  // chased 500 posts instead of 50 and never handed over to reels.
  ok('a requested target is not silently raised',
     /private fun targetFor\(section: String, target: Int\): Int = target/.test(sync));
  ok('the V4 ceilings no longer override callers',
     !/coerceAtLeast\(OfflineManager\.V4_FEED_TARGET\)/.test(sync) &&
     !/coerceAtLeast\(OfflineManager\.V4_REEL_TARGET\)/.test(sync));
  ok('step 1 asks for 50 posts', /val target = 50/.test(bsm));
  ok('and hands over to reels', /step1NewPosts[\s\S]{0,600}step2Reels\(context, p\)/.test(bsm));
  ok('reels use the user\'s chosen count',
     /step2Reels[\s\S]{0,200}p\.offlineReelTarget/.test(bsm));
  ok('then stories, then more posts',
     /step3WaitForVideo[\s\S]{0,600}step4Stories\(context, p\)/.test(bsm) &&
     /step4Stories[\s\S]{0,600}step5MorePosts\(context, p\)/.test(bsm));
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
     /fun storedCount[\s\S]{0,300}f\.length\(\) > 2L/.test(feed));
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
  const feed = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');

  // Requiring every URL was too strict. Capture records each srcset variant,
  // but only the one the renderer chose is ever fetched, so an item could
  // never reach "complete" and nothing was served offline at all.
  ok('a video item is judged on its video',
     /if \(videos\.isNotEmpty\(\)\)[\s\S]{0,160}videos\.any \{ OfflineCache\.hasMinSize/.test(feed));
  ok('the all-URLs rule is gone',
     !/item\.media\.all \{ u ->/.test(feed));
  ok('an avatar alone still does not count',
     /private fun isAvatar/.test(feed) &&
     /val photos = images\.filter \{ !isAvatar\(it\) && !isChrome\(it\) \}/.test(feed));

  // Expectation moved from "any photo" to "every photo, one variant each":
  // that is the whole point of the premature-count fix.
  const MIN = 500000; const disk = {};
  const isVideo = (u) => /\/o1\/v\/|\.mp4|video/.test(u);
  const isAvatar = (u) => { const c = u.split('?')[0];
    return c.includes('/t39.30808-1/') || (c.includes('profile') && c.includes('_s.')); };
  const isChrome2 = (u) => { const c = u.split('?')[0].toLowerCase();
    return c.includes('/emoji.php/') || c.includes('static.xx.fbcdn.net') ||
           c.includes('/rsrc.php/') || c.endsWith('.svg'); };
  const pKey = (u) => { const s = u.split('?')[0].split('/').pop().toLowerCase();
    return s.replace(/[._-][0-9]+x[0-9]+/g, '').replace(/_[0-9]+(?=\.)/, ''); };
  const has = (u) => disk[u] !== undefined;
  const full = (m) => { if (!m.length) return true;
    const v = m.filter(isVideo), i = m.filter((u) => !isVideo(u));
    if (v.length) return v.some((u) => has(u) && disk[u] >= MIN);
    const photos = i.filter((u) => !isAvatar(u) && !isChrome2(u));
    if (!photos.length) return true;
    const g = {};
    for (const p of photos) { const k = pKey(p); (g[k] = g[k] || []).push(p); }
    return Object.values(g).every((vs) => vs.some(has)); };
  const set = (o) => { for (const k of Object.keys(disk)) delete disk[k]; Object.assign(disk, o); };

  set({ 'p_640.jpg': 50000 });
  ok('a photo post counts on one real variant',
     full(['p_640.jpg', 'p_960.jpg', 'p_1280.jpg']) === true);
  set({ 'r_320.jpg': 9000, '/o1/v/v.mp4': 9000000 });
  ok('a reel counts once its video is on disk',
     full(['r_320.jpg', 'r_640.jpg', '/o1/v/v.mp4']) === true);
  set({ 'av/t39.30808-1/a.jpg': 4000 });
  ok('but not on an avatar alone',
     full(['av/t39.30808-1/a.jpg', '/o1/v/v.mp4']) === false);
  set({ '/o1/v/v.mp4': 1000 });
  ok('nor on a truncated video', full(['/o1/v/v.mp4']) === false);
  set({ 'a111_n.jpg': 90000 });
  ok('an album no longer counts on its first photo',
     full(['a111_n.jpg', 'a222_n.jpg', 'a333_n.jpg']) === false);
  set({ 'a111_n.jpg': 90000, 'a222_n.jpg': 90000, 'a333_n.jpg': 90000 });
  ok('it counts when the whole album is on disk',
     full(['a111_n.jpg', 'a222_n.jpg', 'a333_n.jpg']) === true);
}

console.log('\nA reel keeps a playable video URL offline');
{
  const cap = fs.readFileSync(KT('utils/OfflineCapture.kt'), 'utf8');
  // data-video-url normally sits on a child MVideo wrapper. Reading only the
  // card root left the <video> on its dead blob:, so offline showed a poster
  // and a play button that did nothing.
  ok('the wrapper is searched, not just the card root',
     /card\.querySelector\('\[data-video-url\]'\)/.test(cap));
  ok('the dead blob src is replaced',
     /src\s*=\s*\\?"' \+ dv|' \+ dv \+ '/.test(cap));
  ok('and source children are stripped first',
     /<source\\b\[\^>\]\*>/.test(cap));
}

console.log('\nEvery saved card reaches the page');
{
  const inj = fs.readFileSync(KT('utils/OfflineInject.kt'), 'utf8');
  const docs = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');
  const prefs = fs.readFileSync(KT('utils/Prefs.kt'), 'utf8');
  const xml = fs.readFileSync(
    path.join(ROOT, 'app/src/main/res/xml/settings_browsing.xml'), 'utf8');

  // Cards are embedded in a JS template literal inside a <script>. The HTML
  // parser ends that script at the first "</script>" it sees, even inside a
  // string — and Facebook's stored markup contains inline scripts. One such
  // card truncated the block and lost every card after it.
  ok('the closing-script sequence is broken up',
     /\.replace\("<\/script", "<\/scr` \+ `ipt"\)/.test(inj));
  ok('the story viewer does the same',
     /\.replace\("<\/script", "<\/scr` \+ `ipt"\)/.test(docs));
  ok('backtick and dollar are still escaped first',
     /\.replace\("`", "\\\\`"\)/.test(inj));

  // Prove the behaviour, not the shape.
  const esc = (str, fix) => {
    let e = str.replace(/\\/g, '\\\\').replace(/`/g, '\\`').replace(/\$/g, '\\$');
    return fix ? e.replace(/<\/script/g, '</scr` + `ipt') : e;
  };
  let cards = '';
  for (let i = 0; i < 20; i++) {
    cards += i === 5
      ? '<div class="card">Post ' + i + '<script>var a=1;</script></div>'
      : '<div class="card">Post ' + i + '</div>';
  }
  const render = (fix) => {
    const page = '<html><body><div id="box"></div><script>' +
      'var CARDS = `' + esc(cards, fix) + '`;' +
      "document.getElementById('box').innerHTML = CARDS;" +
      '</script></body></html>';
    try {
      // The unfixed case deliberately produces a SyntaxError; jsdom prints it
      // to the console, which would look like a suite failure. Swallow it.
      const vc = new (require('jsdom').VirtualConsole)();
      const d = new JSDOM(page, { runScripts: 'dangerously', virtualConsole: vc });
      return d.window.document.querySelectorAll('#box .card').length;
    } catch (e) { return -1; }
  };
  ok('without the fix an inline script loses the cards', render(false) === 0);
  ok('with it every card renders', render(true) === 20);

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
     /val html = promoHideCss\(\) \+\s*\n\s*f\.readText\(\)/.test(docs));
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

console.log('\nThe store keeps what it saves');
{
  const feed = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');

  ok('per-section retention floors exist',
     /STORE_KEEP_FLOOR_FEED = 500/.test(feed) &&
     /STORE_KEEP_FLOOR_REELS = 250/.test(feed) &&
     /STORE_KEEP_FLOOR_STORIES = 200/.test(feed));
  ok('the merge keeps at least the floor',
     /val keep = maxOf\(limit, storeKeepFloor\(section\)\)/.test(feed));
  ok('the cap uses the floored value',
     /if \(merged\.size >= keep\) break/.test(feed));
  ok('dedupe still runs as a backstop', /seen\.add\(key\)/.test(feed));

  // Faithful port of the merge: newest first, dedupe by key, stop at keep.
  function addItems(existing, incoming, limit, floor) {
    const keep = Math.max(limit, floor);
    const seen = new Set();
    const merged = [];
    const keyFor = (it) => it.id ||
      ((it.media[0] || '') + '|' + it.html.slice(0, 180));
    for (const it of [...incoming, ...existing]) {
      const k = keyFor(it);
      if (seen.has(k)) continue;
      seen.add(k);
      merged.push(it);
      if (merged.length >= keep) break;
    }
    return merged;
  }
  let store = [];
  store = addItems(store,
    Array.from({ length: 50 }, (_, i) => ({ id: 'bg-' + i, html: 'x', media: [] })),
    50, 500);
  // The live page keeps merging with its small pass limit as the user
  // scrolls; previously this deleted everything before it.
  store = addItems(store,
    Array.from({ length: 60 }, (_, i) => ({ id: 'live-' + i, html: 'x', media: [] })),
    50, 500);
  ok('scrolled-past posts no longer evict previously saved ones',
     store.some((it) => it.id === 'bg-0') && store.length === 110,
     'kept=' + store.length);
  // And the floor still caps the truly absurd in the long run.
  for (let r = 0; r < 12; r++) {
    store = addItems(store,
      Array.from({ length: 50 }, (_, i) => ({ id: 'r' + r + '-' + i, html: 'x', media: [] })),
      50, 500);
  }
  ok('long-run growth is still bounded by the floor',
     store.length === 500, String(store.length));
}

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);

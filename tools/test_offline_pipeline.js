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
const cp = require('child_process');
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
       /queued\.add\(u\)/.test(vaultSrc) && /internal fun startDrain\(\)/.test(vaultSrc));
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

console.log('\nHome opens at the very top, every time');
{
  // Round-11 bug 1: offline home opened a little below the top and every
  // back-navigation returned to that same fake position. The mechanism,
  // proven with the verbatim script in jsdom: serve passed home the
  // stored feed position, and doResume() then set scrollTop immediately
  // and again at 300/800/1500/2500 ms; reporting only happens while
  // scrolled DOWN, so nothing could ever clear the stale offset.
  const resumeBlock = docs.slice(docs.indexOf('val resumeId = when (screen)'),
                                 docs.indexOf('val docText'));
  ok('home is served with no resume id at all',
     /"home" -> null/.test(resumeBlock));
  ok('reels and stories keep their resume, untouched',
     /offlineResumeReel/.test(resumeBlock) &&
     /offlineResumeStories/.test(resumeBlock));
  // Round-13 contamination fix, on top: the report current on home used to
  // write home video posts' data-video-id into the REELS resume slot. The
  // script now knows which screen embedded it and home reports nothing.
  const asm2 = fs.readFileSync(KT('offline/PageAssembly.kt'), 'utf8');
  ok('the resume machinery itself stays for the screens that use it',
     /fun resumeScript\(resumeId: String\?, section: String\)/.test(asm2) &&
     /__dbResumeId/.test(asm2));
  ok('the script is told which screen it serves, at bind time',
     asm2.includes('var SEC = "__SEC__"') &&
     asm2.includes('.replace("__SEC__", section)') &&
     /resumeScript\(resumeId, screen\)/.test(docs));
  ok('a home-embedded script reports nothing, ever',
     asm2.includes("if (SEC === 'feed') return;"));
  // Round-13 second leak in the same slot: the report wrote 'reel'
  // unconditionally, so scrolling the STORIES screen overwrote the
  // reels resume with a story id. The slot now follows the screen.
  ok('the write goes to the screen that made it, not always to reels',
     asm2.includes("var key = (SEC === 'stories') ? 'story' : 'reel';") &&
     /FBPro\.reportPosition\(key, vid\)/.test(asm2) &&
     !/FBPro\.reportPosition\('reel', vid\)/.test(asm2));
  ok('reportPosition knows the\u00a0story key on the Kotlin side',
     /"stories", "story" ->/.test(
       fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8')));
  // Rounds 14->16, the full trail: v5.2.14 removed the post-hoc JS
  // corrector the user rejected twice ("no JS rides the gesture"),
  // v5.2.15's mandatory retrial could align landings but never cap a
  // fling, and the user then EXPLICITLY chose a real touch pager over
  // accepting that. So touch listeners are back - but this time they
  // ARE the scroll: no settle-then-correct anywhere. The pin is scoped
  // to the extracted template because the Kotlin file's comments
  // legitimately mention touchstart in their round history.
  const tpl = asm2.slice(asm2.indexOf('val main = """'),
                         asm2.indexOf('""".trimIndent()', asm2.indexOf('val main = """')));
  ok('the reels touch code is the pager, not a post-hoc corrector',
     /SEC === 'reels'/.test(tpl) &&
     /DRAG_LOCK/.test(tpl) && /FLING_V/.test(tpl) &&
     /commitTo/.test(tpl) && !/settleAfter/.test(tpl));
  ok('\u2026and it can never fight the browser: snap style leaves at init',
     /getElementById\('__db_reels_snap'\)/.test(tpl) &&
     /removeChild\(snapStyle\)/.test(tpl));
  ok('only the reels screen asks compose for one-card-per-gesture snap',
     /compose\(docText, cards, snap = screen == "reels"\)/.test(docs));
}

console.log('\nNearby reels stay eager, far reels stay lazy');
{
  // Round-13 assistant for "reels scroll hoina": capture stamps
  // preload="auto" on every video, so a full shelf of playable reels all
  // preloaded at once and sat on the main thread. Snap-mode compose now
  // rewrites cards past the first few to metadata at READ time, so old
  // libraries heal on their next open. The constant is one number in
  // one place; the harness mirrors holderHtml line for line.
  const asm3 = fs.readFileSync(KT('offline/PageAssembly.kt'), 'utf8');
  ok('one constant decides how many snap cards stay eager',
     /private const val SNAP_PRELOAD_FIRST = 3/.test(asm3));
  ok('the rewrite lives in holderHtml, snap mode only',
     /i >= SNAP_PRELOAD_FIRST/.test(asm3) &&
     asm3.includes('preload=\\"metadata\\"'));
  const H3 = require('./offline_vault_harness.js');
  const rows = [];
  for (let i = 0; i < 6; i++) {
    rows.push('<div data-video-id="v' + i + '"><video preload="auto" ' +
      'data-video-url="https://video.fbcdn.net/v.mp4"></video></div>');
  }
  const snapH = H3.holderHtml(rows, true);
  const lazyH = H3.holderHtml(rows, false);
  const cnt = (s, re) => (s.match(re) || []).length;
  ok('mirror: three eager, three lazy on a six-reel page',
     cnt(snapH, /preload="auto"/g) === 3 &&
     cnt(snapH, /preload="metadata"/g) === 3,
     'auto=' + cnt(snapH, /preload="auto"/g) +
     ' metadata=' + cnt(snapH, /preload="metadata"/g));
  ok('mirror: non-snap pages are untouched',
     cnt(lazyH, /preload="auto"/g) === 6 &&
     cnt(lazyH, /preload="metadata"/g) === 0);
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
  {
    // Stored cards keep every visual property from Facebook. The only
    // extra declarations we may ever direct at a card are gesture-only:
    // once we hide the snap pager, the body's poisoned snap must lift and
    // a saved reel surface must stop capturing drags meant for scrolling.
    // Neither property changes a pixel. Anything else aimed at a card -
    // colours, fonts, sizes, spacing - is a restyle and breaks the rule,
    // the way it broke in every round that tried it.
    // Gesture neutralisation only - and for the reels screen, gesture
    // RESTORATION: snap-align/stop/margin direct how the scroller rests,
    // never how a card looks. The list below is the whole allowance.
    const allowed = new Set(['scroll-snap-align', 'touch-action',
      'scroll-snap-margin-top', 'scroll-snap-stop']);
    // Rules about the holder's SIBLINGS (the '~' cleanup that hides
    // stale scrollers) are structure, not cards, and stay out of scope.
    const cardRules = [...inject.matchAll(/#__db_cards[\s>][^{]*\{([^}]*)\}/g)]
      .map(m => m[1]);
    ok('injected cards are not restyled by us',
       cardRules.length > 0 &&
       cardRules.every(body => body.replace(/["+\s]/g, '').split(';')
         .filter(Boolean)
         .every(d => allowed.has(d.split(':')[0])))) &&
    ok('and the only snap-type declarations sit on the document itself',
       (inject.match(/scroll-snap-type/g) || []).length === 2 &&
       /html,body\{[^}]*scroll-snap-type:none!important/.test(inject) &&
       /html,body\{scroll-snap-type:y mandatory!important/.test(inject));
    // The chrome row is a LIVE widget, not a saved card: the stories
    // tray's horizontal item snapping is part of the real page and must
    // keep working offline. Our gesture rules never reach under it.
    ok('live chrome keeps Facebook\u2019s own gestures and snapping',
       !/#__db_chrome\s\*/.test(inject) &&
       !/db_chrome\b[^{]*\{[^}]*scroll-snap-align/.test(inject));
  }

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

  // Round-11: "reels Play hoi na". The stored control's handler lives in
  // Facebook's own JS, which never ships with the page - so a tap met a
  // dead handler. The assist now bridges taps itself, at capture phase.
  ok('a tap bridge exists and toggles the card\u2019s own video',
     /addEventListener\('click',/.test(vh) &&
     /t\.closest\('#__db_cards>\*'\)/.test(vh) &&
     /vs\[0\]\.play\(\)/.test(vh) && /vs\[j\]\.pause\(\)/.test(vh));
  ok('real links, buttons and form fields keep their own jobs',
     /t\.closest\('a,button,input,textarea,select'\)/.test(vh) &&
     /var rb = t\.closest\('\[role="button"\]'\)/.test(vh) &&
     /rb\.querySelector\('video'\)/.test(vh));
  ok('the story pager is never hijacked',
     /__db_story_overlay/.test(vh));
  ok('one sound at a time: a new play pauses the rest',
     /addEventListener\('play',/.test(vh) &&
     /vs\[i\] !== e\.target && !vs\[i\]\.paused/.test(vh));
  // Round-12: the native control bar ate the tap (show/hide the
  // timestamp strip) instead of reaching the bridge, so a playing reel
  // would never pause. Saved-card videos lose the bar; the story
  // viewer's overlay videos keep theirs.
  ok('the native control bar is stripped from saved-card videos only',
     /v\.closest\('#__db_cards'\)\) \{[\s\S]{0,120}removeAttribute\('controls'\)/
       .test(vh));

  {
    // Behaviour, not just text: the script VERBATIM, run in a real DOM.
    const script = (() => {
      const marker = 'fun getOfflineVideoAssistScript(): String = ';
      const i = vh.indexOf(marker);
      if (i < 0) throw new Error('assist script marker gone');
      const a = vh.indexOf('"""', i), b = vh.indexOf('"""', a + 3);
      const body = vh.slice(a + 3, b);
      if (/\$\{/.test(body)) throw new Error('assist interpolates');
      const lines = body.split('\n');
      const ind = lines.filter(l => l.trim())
        .map(l => l.match(/^ */)[0].length);
      const min = ind.length ? Math.min.apply(null, ind) : 0;
      return lines.map(l => l.slice(min)).join('\n')
        .replace(/^\n+/, '').replace(/\s+$/, '');
    })();
    const card =
      '<div id="__db_cards"><div class="m" id="rc">' +
      '<span id="playGlyph">glyph</span><video id="v1" controls></video>' +
      '<a href="/reel/comments/1" id="cmt">12 comments</a></div>' +
      '<div class="m" id="rc2"><video id="v2" controls></video></div></div>' +
      '<video id="vOutside" controls></video>';
    function run() {
      const dom = new JSDOM('<html><body>' + card + '</body></html>',
        { runScripts: 'outside-only', url: 'https://m.facebook.com/reel/' });
      const w = dom.window;
      const calls = { play: [], pause: [] };
      for (const id of ['v1', 'v2']) {
        const v = w.document.getElementById(id);
        Object.defineProperty(v, 'paused',
          { get() { return this.__p !== false; }, configurable: true });
        v.play = function() { v.__p = false; calls.play.push(id); };
        v.pause = function() { v.__p = true; calls.pause.push(id); };
      }
      w.eval(script);
      return { w, calls };
    }
    const a = run();
    a.w.document.getElementById('playGlyph')
      .dispatchEvent(new a.w.MouseEvent('click', { bubbles: true }));
    ok('a tap on the reel surface plays it',
       a.calls.play.length === 1 && a.calls.pause.length === 0,
       JSON.stringify(a.calls));
    a.w.document.getElementById('playGlyph')
      .dispatchEvent(new a.w.MouseEvent('click', { bubbles: true }));
    ok('a second tap pauses, like the online reel',
       a.calls.play.length === 1 && a.calls.pause.length === 1,
       JSON.stringify(a.calls));
    const b = run();
    b.w.document.getElementById('cmt')
      .dispatchEvent(new b.w.MouseEvent('click', { bubbles: true }));
    ok('a tap on a real link navigates instead of playing',
       b.calls.play.length === 0);
    const c2 = run();
    c2.w.document.getElementById('v1').__p = false;
    c2.w.document.getElementById('v2')
      .dispatchEvent(new c2.w.Event('play', { bubbles: true }));
    ok('a newly started video pauses the others',
       c2.calls.pause.length === 1 && c2.calls.pause[0] === 'v1',
       JSON.stringify(c2.calls));
    const d5 = run();
    const so = d5.w.document.createElement('div');
    so.id = '__db_story_overlay';
    d5.w.document.body.appendChild(so);
    d5.w.document.getElementById('playGlyph')
      .dispatchEvent(new d5.w.MouseEvent('click', { bubbles: true }));
    ok('the story pager\u2019s zones are never hijacked',
       d5.calls.play.length === 0);
    const e2 = run();
    ok('saved-card videos shed the tap-eating native control bar',
       !e2.w.document.getElementById('v1').hasAttribute('controls') &&
       !e2.w.document.getElementById('v2').hasAttribute('controls'));
    ok('a video outside the cards keeps its controls',
       e2.w.document.getElementById('vOutside').hasAttribute('controls'));
  }
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
  // The empty-media rule now answers PER SECTION (the user's own fake
  // count fix, commit 7807cd3). Feed and stories keep the old intent -
  // a text post is complete with zero downloads - while a reel with no
  // media was the inflated count. Proven from three sides: the rule's
  // text, the per-vault wiring, and the rule's behaviour both ways.
  ok('a text post with no media still counts (feed rule kept)',
     /if \(e\.media\.isEmpty\(\)\) return !videoRequired/.test(vaultSrc) &&
     /videoRequired = false/.test(
       fs.readFileSync(KT('offline/HomeVault.kt'), 'utf8')) &&
     H.isComplete([], () => false, false) === true);
  ok('a reel with no media no longer counts (the fake count dies)',
     /videoRequired = true/.test(
       fs.readFileSync(KT('offline/ReelsVault.kt'), 'utf8')) &&
     H.isComplete([], () => false, true) === false);

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
  //   0. the feed vault trims to the user's chosen post count
  //   1. a quick starter set, min(10, the chosen count), then posts stop
  //   2. the chosen number of reels
  //   3. the feed fills to the user's chosen post count - and stops there
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
     /fun step1FirstPosts[\s\S]{0,900}step2Reels\(context, p\)/.test(bsm) &&
     /fun step2Reels[\s\S]{0,1400}step3MorePosts\(context, p\)/.test(bsm) &&
     /fun step3MorePosts[\s\S]{0,900}step4Stories\(context, p\)/.test(bsm));
  ok('step 0 trims the feed vault to the chosen count, before any fetch',
     /isRunning = true[\s\S]{0,1500}diskIO\.execute \{[\s\S]{0,200}OfflineFeed\.trimTo\(OfflineFeed\.SECTION_FEED, p\.offlinePostTarget\)[\s\S]{0,300}step1FirstPosts\(c, p\)/.test(bsm));
  ok('step 1 is a starter set: min(10, the chosen count), exactly',
     /fun step1FirstPosts[\s\S]{0,800}minOf\(10, p\.offlinePostTarget\)[\s\S]{0,300}SECTION_FEED, quick,[\s\S]{0,200}exactTotal = quick/.test(bsm));
  ok('reels use the user\'s chosen count, exactly',
     /fun step2Reels[\s\S]{0,500}p\.offlineReelTarget[\s\S]{0,240}exactTotal = target/.test(bsm));
  ok('step 3 fills only to the user\'s chosen post count - never 300 blind',
     /fun step3MorePosts[\s\S]{0,700}p\.offlinePostTarget[\s\S]{0,400}SECTION_FEED, target,[\s\S]{0,240}exactTotal = target/.test(bsm) &&
     !/SECTION_FEED, 300/.test(bsm) && !/exactTotal = 300/.test(bsm));
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

// --------------------------------- serial downloads, structurally impossible to parallelise
console.log('\nTwo sections can never download at the same time');
{
  const registry = fs.readFileSync(KT('offline/OfflineVaults.kt'), 'utf8');
  const vaultSrc = fs.readFileSync(KT('offline/SectionVault.kt'), 'utf8');
  const bsm = fs.readFileSync(KT('utils/BackgroundSyncManager.kt'), 'utf8');

  // Round-8 requirement, verbatim: "reels post's eksathe download na hote
  // pare serial by serial download chara possible hobe na". The guarantee
  // is structural: exactly one vault holds the fetch slot at any moment.
  ok('exactly one vault can hold the fetch slot',
     /private val schedLock/.test(registry) &&
     /owner: SectionVault\? = null/.test(registry));
  ok('granting hands the slot to one vault and starts only its worker',
     /owner = grant/.test(registry) && /grant\.startDrain\(\)/.test(registry));
  ok('a queue add asks the scheduler instead of starting a worker',
     /OfflineVaults\.pump\(\)/.test(vaultSrc) &&
     !/private fun drain\(\)/.test(vaultSrc));
  ok('the worker checks the slot before every single file',
     /if \(!OfflineVaults\.slotGrantedFor\(this\)\) break/.test(vaultSrc));
  ok('the worker gives the slot back when its own queue stands empty',
     /OfflineVaults\.releaseSlot\(this\)/.test(vaultSrc));
  ok('a parked vault releases too - a policy flip can never deadlock',
     /more && enabled && writeEnabled && downloadAllowed\(\)/.test(vaultSrc));
  ok('while a phase is active, only its own section may hold the slot',
     /sections\.filter \{ it\.section == prio \}/.test(registry));

  // The pipeline tells the scheduler which section each step is filling.
  ok('step 1 grants the slot to posts',
     /fun step1FirstPosts[\s\S]{0,400}setPrioritySection\(\s*OfflineFeed\.SECTION_FEED\)/.test(bsm));
  ok('step 2 grants the slot to reels',
     /fun step2Reels[\s\S]{0,400}setPrioritySection\(\s*OfflineFeed\.SECTION_REELS\)/.test(bsm));
  ok('step 3 grants the slot back to posts',
     /fun step3MorePosts[\s\S]{0,400}setPrioritySection\(\s*OfflineFeed\.SECTION_FEED\)/.test(bsm));
  ok('step 4 grants the slot to stories',
     /fun step4Stories[\s\S]{0,400}setPrioritySection\(\s*OfflineFeed\.SECTION_STORIES\)/.test(bsm));
  ok('the slot is free-for-all again once the pipeline has finished',
     /setPrioritySection\(null\)/.test(bsm));

  // Behavioural proof: a faithful model of the scheduler, driven through
  // the exact production sequence (BSM phases + a foreground enqueue
  // mid-reels-phase), asserting at every instant that at most one vault
  // is fetching - in every order the events could occur.
  function newWorld() {
    const w = {
      owner: null, prio: null, log: [], busyLog: [],
      vaults: {
        feed: { name: 'feed', pending: 0, busy: false },
        reels: { name: 'reels', pending: 0, busy: false },
        stories: { name: 'stories', pending: 0, busy: false }
      }
    };
    w.busyNow = () =>
      Object.values(w.vaults).filter((v) => v.busy).length;
    w.pump = () => {
      if (w.owner) return;
      const order = (w.prio == null ? ['feed', 'reels', 'stories']
        : ['feed', 'reels', 'stories'].filter((x) => x === w.prio))
        .map((n) => w.vaults[n]);
      const g = order.find((v) => v.pending > 0);
      if (!g) return;
      w.owner = g.name;
      g.busy = true;
    };
    w.setPrio = (s) => { w.prio = s; w.pump(); };
    w.enqueue = (name, n) => { w.vaults[name].pending += n; w.pump(); };
    w.release = (v) => { if (w.owner === v.name) w.owner = null; w.pump(); };
    // One file: only the OWNER can fetch (the production per-file guard).
    w.tick = () => {
      if (!w.owner) return false;
      const v = w.vaults[w.owner];
      v.pending--;
      w.log.push(v.name);
      w.busyLog.push(w.busyNow());
      if (v.pending === 0) { v.busy = false; w.release(v); }
      return true;
    };
    w.quiet = () => { let t = 0; while (w.owner && t < 1000) { w.tick(); t++; } };
    return w;
  }

  // The full pipeline with an interleaved foreground capture.
  const w = newWorld();
  w.setPrio('feed');
  w.enqueue('feed', 5);          // step 1 starter posts land
  w.quiet();
  w.setPrio('reels');
  w.enqueue('reels', 3);         // step 2 reels land
  w.enqueue('feed', 2);          // user scrolls the feed DURING the reels phase
  const feedWaitingMidReels = w.vaults.feed.pending;
  w.quiet();                     // reels drain; feed must still be waiting
  const feedWaitingAfterReels = w.vaults.feed.pending;
  w.setPrio(null);               // pipeline done: the waiting posts go now
  w.quiet();
  ok('the downloads happen strictly in phase order, never interleaved',
     JSON.stringify(w.log) === JSON.stringify(
       ['feed', 'feed', 'feed', 'feed', 'feed',
        'reels', 'reels', 'reels',
        'feed', 'feed']), w.log.join(','));
  ok('foreground work waited patiently through the reels phase',
     feedWaitingMidReels === 2 && feedWaitingAfterReels === 2,
     feedWaitingMidReels + '/' + feedWaitingAfterReels);
  ok('at no instant was more than one vault fetching',
     w.busyLog.every((n) => n <= 1));

  // No priority (plain browsing): first come, first served, still one at
  // a time; when the first finishes, the next starts immediately.
  const w2 = newWorld();
  w2.enqueue('stories', 2);
  w2.enqueue('reels', 2);
  w2.quiet();
  ok('with no phase declared, work is still served strictly one at a time',
     JSON.stringify(w2.log) === JSON.stringify(
       ['stories', 'stories', 'reels', 'reels']), w2.log.join(','));
  ok('and still never two at once', w2.busyLog.every((n) => n <= 1));

  // A priority phase with nothing to do must not strand waiting work.
  const w3 = newWorld();
  w3.enqueue('feed', 1);
  w3.quiet();
  w3.setPrio('stories');         // stories phase, but no stories queued
  w3.enqueue('feed', 1);         // only feed has work
  const strandedWhilePrioritised = w3.vaults.feed.pending;
  w3.setPrio(null);
  w3.quiet();
  ok('work queued under another phase heals the moment the slot frees',
     strandedWhilePrioritised === 1 && w3.vaults.feed.pending === 0);
}

console.log('\nThe cap holds atomically, and a full store still heals');
{
  const vaultSrc = fs.readFileSync(KT('offline/SectionVault.kt'), 'utf8');
  const H = require('./offline_vault_harness.js');

  // Round-8 requirement: "Posts 50 tar beshi download hoi". The room is
  // decided INSIDE the vault lock - both capture paths used to compute
  // "remaining" outside any lock, which is exactly how 50 became 53.
  // Not a text pin: compute the answer the way the production lock does.
  // The seat semantics test lives in the round-21 block with its proofs;
  // here only the "inside synchronized(this)" race-proof claim is bound,
  // measured structurally rather than by quoting a paragraph of code.
  {
    const ai = vaultSrc.indexOf('fun addItems(');
    const syncAt = vaultSrc.indexOf('synchronized(this) {', ai);
    const roomAt = vaultSrc.indexOf('var room = Int.MAX_VALUE', ai);
    const seatAt = vaultSrc.indexOf('room = hardCap - seated', ai);
    const writeAt = vaultSrc.indexOf('writeAll(merged)', ai);
    ok('the room decision lives inside the vault lock',
       syncAt > ai && roomAt > syncAt && seatAt > roomAt &&
       writeAt > seatAt &&
       vaultSrc.indexOf('}', seatAt) > seatAt);
  }
  ok('a stored id re-captured spends no room (replacement, not addition)',
     /if \(it\.id\.isNotBlank\(\) && it\.id in existingIds\) true[\s\S]{0,80}else if \(room > 0\) \{ room--; true \} else false/.test(vaultSrc));
  ok('no take-of-remaining shortcut remains anywhere in the vault',
     !/\.take\(room\)/.test(vaultSrc));

  // Behavioural: two bridges race a store holding 48 with cap 50. Each
  // "thought" there was room for 2 BEFORE either landed - the old shape.
  // The locked vault takes them one at a time.
  const store48 = Array.from({ length: 48 }, (_, i) =>
    ({ id: 'old' + i, html: '<div>x</div>', media: [] }));
  const callA = Array.from({ length: 5 }, (_, i) =>
    ({ id: 'a' + i, html: '<div>a</div>', media: [] }));
  const callB = Array.from({ length: 5 }, (_, i) =>
    ({ id: 'b' + i, html: '<div>b</div>', media: [] }));
  let store = H.addItems(store48, callA, 50, 500, 50);
  store = H.addItems(store, callB, 50, 500, 50);
  ok('two racing bridges can never push the store past the cap',
     store.length === 50, String(store.length));
  ok('the first caller filled the room, the second got none of it',
     store.filter((e) => e.id[0] === 'a').length === 2 &&
     store.filter((e) => e.id[0] === 'b').length === 0);

  // And the healing half of the same rule: a store at cap with entries
  // whose media never landed must still accept their re-captures.
  const full = Array.from({ length: 30 }, (_, i) =>
    ({ id: 'r' + i, html: '<div>reel ' + i + '</div>', media: ['v' + i + '.mp4'] }));
  const rerun = full.map((e, i) =>
    ({ id: e.id, html: '<div>reel ' + i + ' FRESH</div>', media: ['fresh' + i + '.mp4'] }));
  const healed = H.addItems(full, rerun, 30, 250, 30);
  ok('a full store of unfinished reels accepts every replacement',
     healed.length === 30 && healed.every((e) => e.media[0].indexOf('fresh') === 0));
  const freshUrlsWon = healed.filter((e) =>
    e.html.indexOf('FRESH') >= 0).length;
  ok('the replacement really replaces - fresh URLs win over dead ones',
     freshUrlsWon === 30, String(freshUrlsWon));
  ok('and one brand new id on top still does not fit',
     H.addItems(healed, [{ id: 'zz', html: '<div>z</div>', media: [] }],
       30, 250, 30).length === 30);

  // Round-12: seats are the PLAYABLE entries. The same full shelf, but
  // with nothing downloaded at all, holds no seats - so fresh reels
  // walk straight in instead of starving behind dead markup.
  const noMedia = () => false;
  const shelfBecomes = H.addItems(full,
    Array.from({ length: 5 }, (_, i) =>
      ({ id: 'new' + i, html: '<div>fresh ' + i + '</div>',
         media: ['n' + i + '.mp4'] })),
    30, 250, 30, noMedia);
  ok('a full shelf of media-less reels holds no seats: five fresh ids walk in',
     shelfBecomes.filter((e) => e.id.indexOf('new') === 0).length === 5,
     JSON.stringify(shelfBecomes.map((e) => e.id)));
}

console.log('\nAn entry is "known" only when it is playable');
{
  const vaultSrc = fs.readFileSync(KT('offline/SectionVault.kt'), 'utf8');
  const H = require('./offline_vault_harness.js');

  // Round-8 root cause of "reels 4/6 ta download hoye ar hote chai na":
  // knownIds used to cover EVERY stored id, so a reel whose signed URL
  // died in the queue kept its slot AND its skip status - the fresh copy
  // the next pass saw was skipped as "known", and the dead URL stayed.
  ok('known ids come from COMPLETE items only',
     /fun knownIds\(\): List<String> =\s*completeItems\(\)\.map \{ it\.id \}/.test(vaultSrc));

  // Behavioural, through the production completeness predicate: 6 stored
  // reels, 2 with video on disk; the capture pass must be told to skip
  // exactly those 2 and re-take the other 4.
  const entries = Array.from({ length: 6 }, (_, i) => ({
    id: 'reel' + i,
    media: ['https://video.xx.fbcdn.net/o1/v/clip' + i + '.mp4']
  }));
  const disk = new Set([entries[1].media[0], entries[4].media[0]]);
  const known = entries
    .filter((e) => H.isComplete(e.media, (u) => disk.has(u)))
    .map((e) => e.id);
  ok('playable reels stay known - they are never re-downloaded',
     JSON.stringify(known) === JSON.stringify(['reel1', 'reel4']),
     known.join(','));
  const recapture = entries.filter((e) => known.indexOf(e.id) === -1)
    .map((e) => e.id);
  ok('the other four come back as brand new, fresh URL and all',
     recapture.length === 4 && recapture.indexOf('reel0') === 0);
}

console.log('\nThe reels phase retries with fresh signed URLs, then moves on');
{
  const bsm = fs.readFileSync(KT('utils/BackgroundSyncManager.kt'), 'utf8');

  // When the queue drains short of the target, the signed URLs that died
  // mid-queue are re-issued by simply loading the reels screen again -
  // and the store lets the fresh copies in (proofs above). Bounded, so a
  // dead connection can never pin the pipeline on reels.
  ok('the reels step counts its passes and stops retrying',
     /private const val MAX_REEL_PASSES = 3/.test(bsm) &&
     /pass < MAX_REEL_PASSES/.test(bsm));
  ok('the retry decision is the REAL playable count, not the store size',
     /realPlayableCount\(\s*OfflineFeed\.SECTION_REELS\)/.test(bsm));
  ok('a short pass reloads the reels screen for fresh URLs',
     /fun step2Reels[\s\S]{0,1300}step2Reels\(context, p, pass \+ 1\)/.test(bsm));
  ok('and after enough tries the pipeline still moves on to posts',
     /fun step2Reels[\s\S]{0,1400}step3MorePosts\(context, p\)/.test(bsm));

  // Faithful port of the retry gate, run to its boundaries.
  function reelsOutcome(have, target, pass) {
    const MAX_REEL_PASSES = 3;
    if (have < target && pass < MAX_REEL_PASSES) return 'retry';
    return 'move-on';
  }
  ok('a stalled-at-4 first pass retries',
     reelsOutcome(4, 30, 1) === 'retry');
  ok('a target met on the first pass never retries',
     reelsOutcome(30, 30, 1) === 'move-on');
  ok('the third pass is the last one, target or not',
     reelsOutcome(4, 30, 3) === 'move-on');
}

console.log('\nAn exact phase total stops fetching where it was asked');
{
  const sync = fs.readFileSync(KT('utils/OfflineSync.kt'), 'utf8');

  ok('calls can pass an exact total',
     /fun run[\s\S]{0,500}exactTotal: Int\? = null/.test(sync) &&
     /fun runAll[\s\S]{0,500}exactTotal: Int\? = null/.test(sync));
  ok('the batch is capped by what the store still needs to PLAY',
     /var room = exactTotal -[\s\S]{0,60}OfflineFeed\.realPlayableCount\(sec\)/.test(sync));
  ok('a re-capture of a stored id passes even when the store is full',
     /if \(it\.id\.isNotBlank\(\) &&[\s\S]{0,60}it\.id in storedIds\) true/.test(sync));
  ok('only the kept batch is queued for download',
     /OfflineFeed\.prefetch\(sec, batch, includeVideo\)/.test(sync));
  ok('and only the kept batch reaches the store, cap re-checked inside',
     /OfflineFeed\.addItems\(sec, batch, target, exactTotal\)/.test(sync));

  // Faithful port of the batching, run through the phase boundaries. The
  // bridge knows ids of COMPLETE items only (the vault's knownIds), so a
  // stored-but-unfinished id comes back reported: it must pass as a
  // replacement WITHOUT spending room, while brand new ids spend room.
  function phase(existing, completeIds, reported, exactTotal) {
    const known = new Set(completeIds);
    const storedIds = new Set(existing.map((x) => x.id));
    const fresh = reported.filter((it) => !known.has(it.id));
    let room = exactTotal - existing.length;
    return fresh.filter((it) => {
      if (it.id && storedIds.has(it.id)) return true;
      if (room > 0) { room--; return true; }
      return false;
    });
  }
  const mk = (p, n) => Array.from({ length: n }, (_, i) => ({ id: p + i }));
  ok('phase 1 takes ten and stops',
     phase([], [], mk('a', 25), 10).length === 10);
  ok('phase 1 stops completely once ten are held and complete',
     phase(mk('a', 10), mk('a', 10).map((x) => x.id), mk('b', 20), 10).length === 0);
  ok('phase 3 fills only the gap to the chosen count',
     phase(mk('a', 10), mk('a', 10).map((x) => x.id), mk('b', 500), 50).length === 40);
  ok('an oversized old store gets nothing NEW at all',
     phase(mk('a', 200), mk('a', 200).map((x) => x.id), mk('b', 500), 50).length === 0);
  ok('reels phase respects the chosen amount',
     phase([], [], mk('r', 90), 50).length === 50);
  {
    // THE round-8 reels bug, at the boundary where it lived: 30 stored
    // reel entries, only 4 playable (26 signed URLs died before landing).
    // The old gate: remaining = 30 - 30 = 0, the fresh re-captures were
    // refused, "4 of 30" forever. Now every replacement passes for free.
    const stored = mk('r', 30);
    const completeIds = ['r3', 'r7', 'r11', 'r20'];
    const reported = mk('r', 30).map((x, i) => ({ id: x.id })); // same ids, fresh URLs
    const batch = phase(stored, completeIds, reported, 30);
    ok('a full store of dead URLs still accepts all 26 replacements',
       batch.length === 26, String(batch.length));
    ok('those 26 are exactly the unfinished entries',
       batch.every((it) => completeIds.indexOf(it.id) === -1));
    ok('and still not one NEW id past the total',
       phase(stored, completeIds, mk('z', 10), 30).length === 0);
    // The OLD port, for contrast, so the proof can never silently invert.
    const oldRemaining = 30 - stored.length;
    ok('contrast: the old gate really did refuse every one of them',
       oldRemaining <= 0);
  }

  // The user's own scrolling is a capture path too: the same ceilings
  // bind it, or "stops at my number" is only true for the background run.
  const mainSrc = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  ok('the foreground merge is capped by the chosen counts',
     /fun onOfflineItems\(section: String, json: String, done: Boolean\)[\s\S]{0,1600}prefs\.offlinePostTarget[\s\S]{0,500}prefs\.offlineReelTarget[\s\S]{0,800}var room = cap - OfflineFeed\.realPlayableCount\(section\)/.test(mainSrc));
  // Faithful port of the foreground gate, run through its boundaries.
  function foreground(section, existing, completeIds, reported, caps) {
    const cap = caps[section] || 0;
    if (cap <= 0) return reported;
    const known = new Set(completeIds);
    const storedIds = new Set(existing);
    const fresh = reported.filter((it) => !known.has(it.id));
    // Round-12: seats count playable items only (completeIds here),
    // never raw markup still waiting on its media.
    let room = cap - completeIds.length;
    return fresh.filter((it) => {
      if (it.id && storedIds.has(it.id)) return true;
      if (room > 0) { room--; return true; }
      return false;
    });
  }
  const caps = { feed: 50, reels: 30 };
  ok('foreground: a sees-everything scroll still stops at the post count',
     foreground('feed', mk('q', 48).map((x) => x.id), mk('q', 48).map((x) => x.id),
       mk('p', 20), caps).length === 2 &&
     foreground('feed', mk('q', 50).map((x) => x.id), mk('q', 50).map((x) => x.id),
       mk('p', 20), caps).length === 0);
  ok('foreground: reels honor their own count',
     foreground('reels', mk('q', 25).map((x) => x.id), mk('q', 25).map((x) => x.id),
       mk('r', 20), caps).length === 5 &&
     foreground('reels', mk('q', 40).map((x) => x.id), mk('q', 30).map((x) => x.id),
       mk('r', 20), caps).length === 0);
  ok('foreground: unfinished stored reels re-save past a full store',
     foreground('reels', mk('r', 30).map((x) => x.id), ['r1'],
       mk('r', 30), caps).length === 29);
  ok('foreground: a full shelf of UNPLAYABLE entries holds no seats - ' +
     'five fresh reels walk straight in (the round-12 fix)',
     foreground('reels', mk('r', 30).map((x) => x.id), ['r1'],
       mk('n', 5), caps).length === 5);
  ok('foreground: stories stay uncapped (no user-set count)',
     foreground('stories', [], [], mk('s', 3), caps).length === 3);
}

// ---------------------------------------------- the vault trims to the setting
console.log('\nThe feed vault trims down to the chosen count');
{
  const vaultSrc = fs.readFileSync(KT('offline/SectionVault.kt'), 'utf8');
  ok('trimTo exists and rewrites the store atomically, never a truncate',
     /fun trimTo\(maxEntries: Int\)/.test(vaultSrc) &&
     (/fun trimTo[\s\S]{0,1400}renameTo\(f\)/.test(vaultSrc) ||
      (/fun trimTo[\s\S]{0,700}writeAll\(keep\)/.test(vaultSrc) &&
       /private fun writeAll[\s\S]{0,900}renameTo\(f\)/.test(vaultSrc))));
  ok('trimTo never touches a transfer in flight (.part is sacred)',
     /fun trimTo[\s\S]{0,2000}\.part/.test(vaultSrc));
  ok('trimTo drops only media the dropped entries referenced',
     /fun trimTo[\s\S]{0,2400}keepNames[\s\S]{0,200}contains\(base\)/.test(vaultSrc));
  ok('the pipeline trims the feed once per cycle, before fetching',
     fs.readFileSync(KT('utils/BackgroundSyncManager.kt'), 'utf8')
       .match(/OfflineFeed\.trimTo\(OfflineFeed\.SECTION_FEED, p\.offlinePostTarget\)/) !== null);

  // Behavioural proof of the mirrored algorithm against real files.
  const os = require('os');
  const crypto = require('crypto');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dbtrim-'));
  const hash = (u) => crypto.createHash('sha256').update(u).digest('hex');
  const items = Array.from({ length: 200 }, (_, i) => ({
    id: 'p' + i, html: '<div>post ' + i + '</div>',
    media: ['https://scontent.xx.fbcdn.net/v/t51/img' + i + '.jpg']
  }));
  // newest first, as the vault stores them
  fs.writeFileSync(path.join(dir, 'items.json'), JSON.stringify(
    items.map((e) => ({ id: e.id, h: e.html, m: e.media }))));
  const media = path.join(dir, 'media');
  fs.mkdirSync(media);
  for (const e of items) {
    fs.writeFileSync(path.join(media, hash(e.media[0])), 'img');
    fs.writeFileSync(path.join(media, hash(e.media[0]) + '.mime'), 'image/jpeg');
  }
  fs.writeFileSync(path.join(media, hash('https://x/inflight.mp4') + '.part'), 'half');

  // Faithful mirror of SectionVault.trimTo.
  function trimTo(dirIn, maxEntries) {
    if (maxEntries <= 0) return 0;
    const f = path.join(dirIn, 'items.json');
    const all = JSON.parse(fs.readFileSync(f, 'utf8'));
    if (all.length <= maxEntries) return 0;
    const keep = all.slice(0, maxEntries);
    fs.writeFileSync(f + '.part', JSON.stringify(keep));
    fs.renameSync(f + '.part', f);
    const keepNames = new Set();
    for (const e of keep) for (const u of e.m) keepNames.add(hash(u));
    for (const n of fs.readdirSync(media)) {
      if (n.endsWith('.part')) continue;
      const base = n.endsWith('.mime') ? n.slice(0, -5) : n;
      if (!keepNames.has(base)) fs.unlinkSync(path.join(media, n));
    }
    return all.length - keep.length;
  }

  const dropped = trimTo(dir, 50);
  const after = JSON.parse(fs.readFileSync(path.join(dir, 'items.json'), 'utf8'));
  ok('a 200-post legacy store comes down to the chosen 50',
     dropped === 150 && after.length === 50);
  ok('the NEWEST posts are the survivors',
     after[0].id === 'p0' && after[49].id === 'p49');
  ok('orphan media went with them; survivors keep theirs',
     !fs.existsSync(path.join(media, hash(items[199].media[0]))) &&
     !fs.existsSync(path.join(media, hash(items[199].media[0]) + '.mime')) &&
     fs.existsSync(path.join(media, hash(items[0].media[0]))) &&
     fs.existsSync(path.join(media, hash(items[0].media[0]) + '.mime')));
  ok('a half-downloaded file is never judged by this',
     fs.existsSync(path.join(media, hash('https://x/inflight.mp4') + '.part')));
  fs.rmSync(dir, { recursive: true, force: true });
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
     /PageAssembly\.compose\(docText, cards, snap = screen == "reels"\)/
       .test(docs));
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
  ok('every stale scroller AND every stacked screen hide whole',
     /data-type=\\"vscroller\\"/.test(assemblySrc) &&
     /:not\(\[data-db-active\]\)/.test(assemblySrc) &&
     /display:none!important/.test(assemblySrc) &&
     /data-db-active=\\"1\\"/.test(assemblySrc));
  ok('the blanket sibling rule survives only for scroller-less documents',
     /#__db_cards~\*/.test(assemblySrc) &&
     /joinedScreenRoot/.test(assemblySrc));
  ok('the composer and stories tray are moved OUT of the hidden scroller',
     /fun topLevelChildren\(doc: String, from: Int\)/.test(assemblySrc) &&
     /TOKEN/.test(assemblySrc) && /fun classify\(slice: String\)/.test(assemblySrc) &&
     /__db_chrome/.test(assemblySrc));
  ok('the floating tab row is chrome too, moved out ahead of the composer',
     /MOVE_TAB/.test(assemblySrc) &&
     /fun lastScreenTagBefore/.test(assemblySrc) &&
     /tabs \+ moved/.test(assemblySrc));
  ok('junk decisions reuse the vault signatures, one definition no drift',
     /SectionVault\.JUNK_AD_TAG/.test(assemblySrc) &&
     /SectionVault\.JUNK_TAB_LABEL/.test(assemblySrc));
  ok('the story-link guard now guards STORAGE only, never navigation',
     !/SectionVault\.JUNK_STORY_LINK/.test(assemblySrc));
  ok('only a real post URL stops the chrome walk, bounds and all',
     /private val POST_LINK = Regex/.test(assemblySrc) &&
     !/POST_SIG/.test(assemblySrc) &&
     /MAX_CHROME_UNITS = 6/.test(assemblySrc) &&
     /MAX_CHROME_BYTES = 300 \* 1024/.test(assemblySrc));
  ok('absolute virtual offsets become relative gaps with FB own numbers',
     /fun ownMargin/.test(assemblySrc) && /fun ownHeight/.test(assemblySrc) &&
     /fun withMargin/.test(assemblySrc) &&
     /Triple\(stripVirtualMargin/.test(assemblySrc));
  ok('moved chrome is forced fully in-flow, fixed class notwithstanding',
     /#__db_chrome>\*\{position:static!important/.test(assemblySrc));
  ok('the pinned header keeps its fixed place via a stolen offset',
     /fun stolenOffset/.test(assemblySrc) && /MARGIN/.test(assemblySrc) &&
     /__db_top_pad/.test(assemblySrc) && /stripVirtualMargin/.test(assemblySrc));
  ok('an offline layout reset un-clips the path to the cards',
     /__db_layout_reset/.test(assemblySrc) &&
     /overflow:visible!important/.test(assemblySrc) &&
     /\[data-mcomponent=..MScreen..\]\{position:static!important/.test(assemblySrc));
  ok('the reset also kills snap-bounce and gesture capture, by name',
     /scroll-snap-type:none!important/.test(assemblySrc) &&
     /touch-action:manipulation!important/.test(assemblySrc) &&
     /scroll-snap-align:none!important/.test(assemblySrc));
  ok('and the reels page alone opts back in: proximity, no stop:always (round 22 device verdict)',
     /REELS_SNAP_CSS/.test(assemblySrc) &&
     /html,body\{scroll-snap-type:y proximity!important\}/.test(assemblySrc) &&
     /#__db_cards>\*\{scroll-snap-align:start!important/.test(assemblySrc) &&
     !/scroll-snap-stop:always!important/.test(assemblySrc));
  ok('the reels page snap call is gated on the snap parameter (off)',
     /if \(snap\) reelsSnapCss\(padTop \?\: 0\)/.test(assemblySrc));
  ok('a recycled twin of a moved chrome unit is never moved twice',
     /chromeSeen/.test(assemblySrc) &&
     /fun chromeKey/.test(assemblySrc));
  {
    // THE ROUND-8 DEVICE SHAPE, reproduced from the user's own capture
    // structure: Facebook keeps prior screens STACKED in one DOM
    // (data-screen-keys="57,56,55"), and the tab row floats INSIDE the
    // home scroller as a badge-carrying unit - it is not part of the
    // header at all. v5.2.7 un-clipped every MScreen and hid by sibling
    // position, which is exactly why the user screenshotted the bottom
    // of a stale saved post ABOVE the facebook bar, and why the tab row
    // was gone: it hid with the scroller it floats in.
    // Round-8 report, verbatim: "home feed ekhono thik jaigay nai ar
    // jekhane ache okhaneo upore je je buttons thakbar kotha segulaw
    // nai".
    const hostile =
      '<style>html{background:#000}' +
      '.bg-s2{background:#18191a;height:100%;overflow:hidden;' +
      'position:absolute;top:0;bottom:0;left:0;right:0;transform:translateZ(0)}' +
      '.fixed-container{position:fixed;top:0;left:0;right:0;z-index:300}' +
      '.vscroller{overflow-y:auto;height:100%}</style>';
    // ROUND-9 realism: the tab row is ANCHORS (home.php, friends, reel/,
    // notifications, marketplace) and Facebook stamps its tracking ids on
    // plain chrome containers too. Margins are the virtual list absolute
    // y offsets: bar 0..52, tab row 52..104, composer 104..160, tray 160..
    const tabRow =
      '<div data-mcomponent="MContainer" class="m fixed-container top" id="tabrow"' +
      ' data-comp-id="12"' +
      ' style="margin-top:52px; height:52px; z-index:0; width:360px;">' +
      '<a href="https://m.facebook.com/home.php" role="button" aria-label="Home">H</a>' +
      '<a href="https://m.facebook.com/friends/" role="button" aria-label="Friends">15+</a>' +
      '<a href="https://m.facebook.com/messages/" role="button" aria-label="Chats">4</a>' +
      '<a href="https://m.facebook.com/reel/" role="button" aria-label="Reels">15+</a>' +
      '<a href="https://m.facebook.com/notifications/" role="button" aria-label="Notifications">9</a>' +
      '<a href="https://m.facebook.com/marketplace/" role="button" aria-label="Marketplace">M</a>' +
      '</div>';
    const composer =
      '<div data-mcomponent="MContainer" id="composer"' +
      ' data-successful-render-id="88" data-tracking-duration-id="3"' +
      ' style="margin-top:104px; height:56px; z-index:0; width:360px;">' +
      '<div role="textbox">What\u2019s on your mind?</div></div>';
    const tray =
      '<div data-mcomponent="MContainer" id="tray" class="m" data-comp-id="44"' +
      ' style="margin-top:160px; height:232px; z-index:0; width:360px;">' +
      '<a href="https://m.facebook.com/stories/111">Create story</a>' +
      '<a href="https://m.facebook.com/stories/222">Your Story</a></div>';
    const stalePost =
      '<div data-mcomponent="MContainer" class="old" id="stale1" style="margin-top:700px;">' +
      '<a href="https://m.facebook.com/story.php?story_fbid=42&amp;id=7">stale</a></div>';
    const deviceDoc =
      '<div data-mcomponent="MScreen" data-screen-id="11111"' +
      ' data-type="container" class="m bg-s2" id="screenA">' +
      '<div data-mcomponent="MContainer" id="stalePostScreen">' +
      '<div style="height:220px;background:#333">a post detail the user left</div>' +
      '<div>20 reactions</div><div>19 Comments</div></div></div>' +
      '<div data-mcomponent="MScreen" data-screen-id="65549"' +
      ' data-crash-screen-id="42949673960" data-screen-keys="57,56,55"' +
      ' data-type="container" class="m bg-s2" id="screenB">' +
      '<div data-mcomponent="MContainer" class="m fixed-container top" id="hdr">' +
      '<div>facebook</div><div role="button" aria-label="Search">S</div>' +
      '<div role="button" aria-label="Menu">M</div></div>' +
      '<div data-type="vscroller" data-mcomponent="MContainer"' +
      ' data-is-pull-to-refresh-allowed="true" class="m vscroller">' +
      tabRow + composer + tray +
      '<div data-db-ad="1" id="savedad"><span>Sponsored</span></div>' +
      stalePost +
      '</div></div>';
    const savedCards = [
      '<div data-tracking-duration-id="n1"><span>saved post one</span></div>',
      '<div data-tracking-duration-id="n2"><span>saved post two</span></div>'];

    const page = '<html><head>' + hostile + '</head><body>' +
      H.compose(deviceDoc, savedCards) + '</body></html>';
    const dom2 = new JSDOM(page, { url: 'https://m.facebook.com/' });
    const pd = dom2.window.document;
    const A = pd.getElementById('screenA');
    const B = pd.getElementById('screenB');
    const hdr = pd.getElementById('hdr');
    const chrome = pd.getElementById('__db_chrome');
    const holder2 = pd.getElementById('__db_cards');
    const vs = B.querySelector('[data-type="vscroller"]');

    ok('the composed screen is marked kept, the stacked one is not',
       !!A && !!B && B.getAttribute('data-db-active') === '1' &&
       !A.getAttribute('data-db-active'));
    const styleText = [...pd.querySelectorAll('style')]
      .map((s) => s.textContent).join('\n');
    ok('a blanket rule hides every stale scroller anywhere in the document',
       styleText.indexOf('[data-type="vscroller"]{display:none!important}') >= 0);
    ok('a second rule hides every screen except the kept one',
       styleText.indexOf(
         '[data-mcomponent="MScreen"]:not([data-db-active]){display:none!important}') >= 0);

    // The same cascade the phone computes, through jsdom's CSS engine.
    ok('the stale stacked screen computes display:none (the screenshot bug)',
       dom2.window.getComputedStyle(A).display === 'none',
       dom2.window.getComputedStyle(A).display);
    ok('the old scroller computes display:none',
       dom2.window.getComputedStyle(vs).display === 'none');
    ok('the kept screen itself stays visible',
       dom2.window.getComputedStyle(B).display !== 'none');
    ok('the header is inside the kept, visible screen',
       B.contains(hdr) && dom2.window.getComputedStyle(hdr).position === 'fixed');

    // ROUND 22 (bug-report-v3, Bug 4): the real row surfaces again.
    // c4a37fd flipped the exact-label tier MOVE_TAB -> LEAVE for no
    // recorded reason, so for three releases the row's only home was
    // the hidden scroller it arrived in. The badge-shell duplicate
    // among the cards kept a row visible, which masked it; once the
    // v2 heals removed the shell, the offline home had NO navigation
    // at all. The shipped dead state is still proven below, composed
    // by c367b21's own harness, pinned by hash.
    ok('the real tab row leaves the scroller and leads the chrome',
       !!chrome && chrome.firstElementChild &&
       chrome.firstElementChild.id === 'tabrow' &&
       chrome.children.length >= 3 &&
       chrome.children[1].id === 'composer' &&
       !!chrome.querySelector('#tray') && !vs.querySelector('#tabrow'),
       chrome ? chrome.firstElementChild.id : 'no chrome');
    ok('the stories tray follows the composer',
       !!chrome.querySelector('#tray') &&
       chrome.querySelectorAll('a[href*="/stories/"]').length === 2);
    ok('header, chrome, cards, scroller: in the online order',
       (hdr.compareDocumentPosition(chrome) & 4) !== 0 &&
       (chrome.compareDocumentPosition(holder2) & 4) !== 0 &&
       (holder2.compareDocumentPosition(vs) & 4) !== 0);
    ok('the cards sit inside the kept screen, sibling of the scroller',
       holder2.parentElement === B && B === vs.parentElement);
    ok('a condemned ad stays hidden with the scroller',
       !!vs.querySelector('#savedad') && !chrome.querySelector('#savedad'));
    ok('stale posts stay hidden with the scroller',
       !!vs.querySelector('#stale1') && !chrome.querySelector('#stale1'));
    ok('nothing moved twice', page.match(/Create story/g).length === 1 &&
       page.match(/aria-label="Notifications"/g).length === 1);

    // The offline page now lays out like the online screenshot, computed
    // through the real cascade. FB absolute offsets: bar 52, row 52..104,
    // composer 104..160, tray 160.. - rebuilt as padding + zeroed gaps.
    const csChrome = dom2.window.getComputedStyle(chrome);
    const csTab = dom2.window.getComputedStyle(pd.getElementById('tabrow'));
    const csComp = dom2.window.getComputedStyle(pd.getElementById('composer'));
    const csTray = dom2.window.getComputedStyle(pd.getElementById('tray'));
    // Same padding mechanism as ever - the first MOVED unit lends its
    // own offset. With the row restored ahead of the composer the first
    // unit carries the row's own 52, exactly the value the pinned
    // header bar occupies, so the row lands flush under it.
    ok('chrome pads by the first moved unit own offset (52 = tab row offset)',
       csChrome.paddingTop === '52px', csChrome.paddingTop);
    ok('composer gap = 104 - 52 - 52 = 0',
       parseFloat(csComp.marginTop) === 0, csComp.marginTop);
    ok('tray gap = 160 - 104 - 56 = 0',
       parseFloat(csTray.marginTop) === 0, csTray.marginTop);
    ok('virtual margins are stripped from every moved unit opening tag',
       !/margin-top/i.test(pd.getElementById('tabrow').getAttribute('style')) &&
       !/margin-top/i.test(pd.getElementById('composer').getAttribute('style')) &&
       !/margin-top/i.test(pd.getElementById('tray').getAttribute('style')));
    ok('moved units keep Facebook own inline heights',
       csTab.height === '52px' && csComp.height === '56px' &&
       csTray.height === '232px');
    ok('every moved unit computes in-flow, Facebook chrome or not',
       csTab.position === 'static' && csComp.position === 'static' &&
       dom2.window.getComputedStyle(pd.getElementById('tray')).position
         === 'static', csTab.position);

    // Contrast: the SHIPPED v5.2.8 rule on this exact document. Anchors
    // carrying /reel/ made the story-link excuse refuse the row, and the
    // old post signature - data-comp-id and friends included - stopped at
    // the very first child: all three pieces stayed hidden, precisely the
    // round-9 screenshot. Simulated with the shipped rule, verbatim.
    const LABEL = /aria-label=["'](?:home|reels|watch|notifications|marketplace|menu|profile|friends|groups|gaming|messages|messenger|chats|search|create)["']/gi;
    const STORY = /story_fbid|\/posts\/|\/videos\/|\/reel\//i;
    const OLDSIG = /story_fbid|\/posts\/|\/videos\/|\/reel\/|data-tracking-duration-id|data-video-id|data-successful-render-id|data-comp-id/i;
    const classifyV528 = (slice) => {
      LABEL.lastIndex = 0;
      let labels = 0;
      while (LABEL.exec(slice) !== null) { if (++labels >= 2) break; }
      STORY.lastIndex = 0;
      if (labels >= 2 && !STORY.test(slice)) return 3;
      OLDSIG.lastIndex = 0;
      if (OLDSIG.test(slice)) return 2;
      return 0;
    };
    ok('contrast: the v5.2.8 rule really did stop at the tab row itself',
       classifyV528(pd.getElementById('tabrow').outerHTML) === 2 &&
       classifyV528(pd.getElementById('composer').outerHTML) === 2 &&
       classifyV528(pd.getElementById('tray').outerHTML) === 2);
    ok('the anchors row heads for chrome, composer/tray move, the post stops the walk',
       H.classify(fixtureFor('tabrow')) === 3 &&
       H.classify(fixtureFor('composer')) === 0 &&
       H.classify(fixtureFor('tray')) === 0 &&
       H.classify(fixtureFor('stale1')) === 2);
    function fixtureFor(id) {
      return ({ tabrow: tabRow, composer: composer, tray: tray,
               stale1: stalePost })[id];
    }

    // Contrast: the SHIPPED Bug-4 build on exactly this document,
    // composed by its own harness, pinned by hash (c367b21 = remote
    // main when the v3 report was written). Every row species hid with
    // the scroller there - the v3 screenshot's "row entirely gone".
    const HBug4 = (() => {
      const oldSrc = cp.execSync(
        'git show c367b21:tools/offline_vault_harness.js',
        { cwd: ROOT }).toString();
      const tmp = path.join(ROOT, 'tools', '.harness_bug4_tmp.js');
      fs.writeFileSync(tmp, oldSrc);
      const m = require('./.harness_bug4_tmp.js');
      fs.unlinkSync(tmp);
      return m;
    })();
    const bug4Page = '<html><head>' + hostile + '</head><body>' +
      HBug4.compose(deviceDoc, savedCards) + '</body></html>';
    const b4d = new JSDOM(bug4Page, { url: 'https://m.facebook.com/' });
    const b4Chrome = b4d.window.document.getElementById('__db_chrome');
    ok('contrast: shipped 5.2.20 surfaced no row at all (Bug 4 verbatim)',
       !!b4Chrome && !b4Chrome.querySelector('#tabrow') &&
       b4Chrome.firstElementChild.id === 'composer' &&
       b4d.window.getComputedStyle(b4Chrome).paddingTop === '104px',
       b4Chrome ? b4Chrome.firstElementChild.id : 'no chrome');

    // Scroll proof under the hostile cascade.
    const csB = dom2.window.getComputedStyle(B);
    ok('the kept screen no longer clips its own page',
       csB.overflow === 'visible' && csB.height === 'auto' &&
       csB.maxHeight === 'none', csB.overflow + '/' + csB.height);
    ok('the kept screen sits in normal flow',
       csB.position === 'static' && csB.transform === 'none',
       csB.position + '/' + csB.transform);
    const csBody = dom2.window.getComputedStyle(pd.body);
    const csHtml = dom2.window.getComputedStyle(pd.documentElement);
    ok('the document itself scrolls natively',
       csBody.overflow === 'visible' && csBody.height === 'auto' &&
       csHtml.overflow === 'visible' && csHtml.height === 'auto');

    // Contrast: what v5.2.7 shipped really did produce both failures the
    // user screenshotted on exactly this document.
    const legacyPage = '<html><head>' + hostile + '</head><body>' +
      H.composeLegacy(deviceDoc, savedCards) + '</body></html>';
    const ld = new JSDOM(legacyPage, { url: 'https://m.facebook.com/' });
    const ldoc = ld.window.document;
    const lVs = ldoc.querySelectorAll('[data-type="vscroller"]');
    const lChrome = ldoc.getElementById('__db_chrome');
    ok('contrast: v5.2.7 hid the scroller WITH the tab row inside it',
       lVs.length === 1 && !!lVs[0].querySelector('#tabrow') &&
       !(lChrome && lChrome.querySelector('#tabrow')));
    ok('contrast: v5.2.7 marked no screen, so the stack had no owner',
       ldoc.querySelectorAll('[data-db-active]').length === 0);
    ok('contrast: v5.2.7 parked the cards before the header',
       (ldoc.getElementById('__db_cards')
         .compareDocumentPosition(ldoc.getElementById('hdr')) & 4) !== 0);

    // VARIANT SWEEP: whatever the device's flavour of the same home
    // document is, the same three pieces come out. (Round-9 report
    // proved guessing one shape is not enough.)
    const variantDoc = (children) =>
      '<div data-mcomponent="MScreen" data-type="container" class="m bg-s2">' +
      '<div class="m fixed-container top" id="hdrV">bar</div>' +
      '<div data-type="vscroller" class="m vscroller">' + children +
      '</div></div>';
    const btnRowNoMargin =
      '<div data-mcomponent="MContainer" class="m fixed-container top" id="tabrow">' +
      '<div role="button" aria-label="Home">15+</div>' +
      '<div role="button" aria-label="Reels">15+</div></div>';
    const linkRowNoMargin =
      '<div data-mcomponent="MContainer" class="m fixed-container top" id="tabrow">' +
      '<a href="/home.php" role="button" aria-label="Home">H</a>' +
      '<a href="/reel/" role="button" aria-label="Reels">15+</a></div>';
    const promoUnit =
      '<div data-mcomponent="MContainer" id="rooms"' +
      ' style="margin-top:400px; height:70px;"><span>Rooms</span></div>';
    const variantRun = (children) => {
      const vp = new JSDOM('<html><head>' + hostile + '</head><body>' +
        H.compose(variantDoc(children), savedCards) +
        '</body></html>', { url: 'https://m.facebook.com/' });
      const vd = vp.window.document;
      return { d: vd, w: vp.window,
               chrome: vd.getElementById('__db_chrome'),
               vs: vd.querySelector('[data-type="vscroller"]') };
    };
    {
      const v = variantRun(btnRowNoMargin + composer + tray + stalePost);
      ok('variant buttons/no-margins: the exact-label div row leads the chrome too',
         v.chrome.firstElementChild.id === 'tabrow' &&
         !!v.chrome.querySelector('#composer') &&
         !!v.chrome.querySelector('#tray') &&
         !v.chrome.querySelector('#stale1') &&
         !v.vs.querySelector('#tabrow'));
      ok('variant buttons/no-margins: stale scroller hidden',
         v.w.getComputedStyle(v.vs).display === 'none');
    }
    {
      const v = variantRun(linkRowNoMargin + composer + tray + stalePost);
      ok('variant anchors/no-margins: a row with a /reel/ href surfaces the same',
         v.chrome.firstElementChild.id === 'tabrow' &&
         !!v.chrome.querySelector('#composer') &&
         !v.vs.querySelector('#tabrow'));
      ok('variant anchors/no-margins: pad falls to the first margin carrier',
         v.w.getComputedStyle(v.chrome).paddingTop === '104px');
    }
    {
      const v = variantRun(btnRowNoMargin + composer + tray + promoUnit + stalePost);
      ok('variant promo banner: it moves out too, the post still stops it',
         !!v.chrome.querySelector('#rooms') &&
         !v.chrome.querySelector('#stale1'));
    }
    {
      const v = variantRun(composer + tray + stalePost);
      ok('variant no tab row: composer leads with its own offset',
         v.chrome.firstElementChild.id === 'composer' &&
         v.w.getComputedStyle(v.chrome).paddingTop === '104px');
    }
    {
      const adUnit = '<div data-db-ad="1" id="ad1"><span>Sponsored</span></div>';
      const v = variantRun(btnRowNoMargin + composer + tray + adUnit + stalePost);
      ok('variant ad between chrome and posts: left hidden inside',
         !v.chrome.querySelector('#ad1') &&
         !!v.d.querySelector('[data-type="vscroller"] #ad1'));
    }
    {
      const v = variantRun('');
      ok('variant empty scroller: saved cards still served, no chrome at all',
         !!v.d.getElementById('__db_cards') &&
         v.d.querySelectorAll('#__db_chrome').length === 0);
    }
    {
      // Round-10 mechanism 2: Facebook's virtual list can hold a RECYCLED
      // twin of the same tray. Both classed MOVE, and the offline page
      // then showed the tray twice. The first copy moves, twins hide.
      const twinTray =
        '<div data-mcomponent="MContainer" id="traytwin"' +
        ' style="margin-top:392px; height:232px;">' +
        '<a href="https://m.facebook.com/stories/111">Create story</a>' +
        '<a href="https://m.facebook.com/stories/222">Your Story</a></div>';
      const oneTray =
        '<div data-mcomponent="MContainer" id="trayone"' +
        ' style="margin-top:160px; height:232px;">' +
        '<a href="https://m.facebook.com/stories/111">Create story</a>' +
        '<a href="https://m.facebook.com/stories/222">Your Story</a></div>';
      const v = variantRun(composer + oneTray + twinTray + stalePost);
      ok('a recycled twin tray stays hidden: exactly one tray visible',
         v.chrome.querySelectorAll('[href*="stories/"]').length === 2 &&
         !v.chrome.querySelector('#traytwin') &&
         !!v.d.querySelector('[data-type="vscroller"] #traytwin'));
    }
    {
      // Round-10 reels report: "scrolling hoina" - the drag bounced back.
      // With the snap pager hidden, NOTHING on the document may keep
      // Facebook's snap semantics or gesture capture; computed through
      // the real cascade against exactly such hostile page CSS.
      const REEL_CSS =
        '<style>html,body{scroll-snap-type:y mandatory;touch-action:none}' +
        '.draglayer{touch-action:none}#reelSurface{touch-action:none}</style>';
      const reelDoc =
        '<div data-mcomponent="MScreen" data-type="container" class="m bg-s2">' +
        '<div data-type="vscroller" class="m vscroller vscroller-snap">' +
        '<div data-video-id="v1"><a href="/reel/1">old</a></div></div></div>';
      const savedReel =
        '<div data-tracking-duration-id="r9" id="reelSurface" class="draglayer">' +
        '<video src="https://video.xx.fbcdn.net/o1/v/clip.mp4"></video></div>';
      const rp = new JSDOM('<html><head>' + REEL_CSS + '</head><body>' +
        H.compose(reelDoc, [savedReel]) + '</body></html>');
      const rd = rp.window.document;
      const csRB = rp.window.getComputedStyle(rd.body);
      ok('offline body never snaps back: scroll-snap computes none',
         csRB.scrollSnapType === 'none', csRB.scrollSnapType);
      ok('offline body always lets the drag scroll',
         csRB.touchAction === 'manipulation', csRB.touchAction);
      const csSurf = rp.window.getComputedStyle(rd.getElementById('reelSurface'));
      ok('a saved reel surface can no longer eat the scroll gesture',
         csSurf.touchAction === 'manipulation', csSurf.touchAction);
      ok('the hidden snap pager stays hidden',
         rp.window.getComputedStyle(
           rd.querySelector('[data-type="vscroller"]')).display === 'none');

      // Round-15 amendment III: the v5.2.14 device verdict closed the
      // stop:always question - it is INERT on this WebView (scrolls
      // fine, still skips 2/3). What it proved instead is bigger: the
      // old "cannot scroll at all" really was the #screen-root clip,
      // not the snap mode - every historical mandatory/stop verdict
      // was shot while the clip held. mandatory is the mode that
      // guarantees one snap area per rest at any fling speed, and the
      // user's one remaining complaint is exactly "fast swipe passes
      // 2/3". It is trialed in isolation now: the clip is fixed and
      // offline reels are layout-static, so mandatory's layout-shift
      // pathology has no fuel here. If the device reports the trap
      // back, revert to proximity+stop:always and only a custom pager
      // remains - and that road needs the user's explicit consent,
      // because they have rejected JS-in-gesture twice already.
      // Computed through the same hostile cascade.
      const sp2 = new JSDOM('<html><head>' + REEL_CSS + '</head><body>' +
        H.compose(reelDoc, [savedReel], true) + '</body></html>');
      const sd2 = sp2.window.document;
      const csBody2 = sp2.window.getComputedStyle(sd2.body);
      ok('reels opt back into snap, the one-area-per-rest kind: proximity (round 22 verdict)',
         /proximity/.test(csBody2.scrollSnapType) &&
         !/mandatory/.test(sp2.window.getComputedStyle(sd2.body)
           .scrollSnapType),
         csBody2.scrollSnapType);
      const csReel = sp2.window.getComputedStyle(
        sd2.getElementById('reelSurface'));
      ok('every saved reel is exactly one snap area at its start',
         csReel.scrollSnapAlign === 'start', csReel.scrollSnapAlign);
      ok('the area does NOT use stop:always (round 22: stop:always yanks a slow scroll out of the Reels tab)',
         csReel.scrollSnapStop !== 'always', csReel.scrollSnapStop);
      ok('the reel\u2019s gesture still lets the drag scroll',
         csReel.touchAction === 'manipulation', csReel.touchAction);
      ok('a zero-height sentinel keeps scroll 0 a legal rest position',
         !!sd2.getElementById('__db_snap_t') &&
         sd2.getElementById('__db_snap_t').nextElementSibling ===
           sd2.getElementById('reelSurface'));
      ok('the plain compose carries no snap rules at all',
         H.compose(reelDoc, [savedReel]).indexOf('__db_reels_snap') < 0 &&
         H.compose(reelDoc, [savedReel]).indexOf('__db_snap_t') < 0);
      // Snap-margin with Facebook's own stamped offset lifts the lock
      // point below the pinned bar; jsdom knows no scroll-snap-margin,
      // so the value is pinned at the source level.
      ok('the lock point sits Facebook\u2019s own offset below the bar',
         /scroll-snap-margin-top:__PAD__px!important/.test(
           fs.readFileSync(KT('offline/PageAssembly.kt'), 'utf8')) &&
         /reelsSnapCss\(padTop \?\: 0\)/.test(
           fs.readFileSync(KT('offline/PageAssembly.kt'), 'utf8')));
    }

    // A plain single-screen document still composes correctly.
    const single =
      '<div data-mcomponent="MScreen" data-type="container" class="m bg-s2">' +
      '<div class="m fixed-container top" id="hdr2">bar</div>' +
      '<div data-type="vscroller" id="vs1">' + composer +
      '<div data-db-ad="1">ad</div>' + stalePost + '</div></div>';
    const sp = new JSDOM('<html><head>' + hostile + '</head><body>' +
      H.compose('<body>' + single + '</body>', savedCards) +
      '</body></html>', { url: 'https://m.facebook.com/' });
    const sd = sp.window.document;
    ok('a lone screen is kept and marked, ready for the :not rule',
       sd.querySelectorAll('[data-db-active]').length === 1);
    ok('single-screen chrome moved out, no phantom tab row',
       !!sd.getElementById('__db_chrome').querySelector('#composer') &&
       !sd.getElementById('__db_chrome').querySelector('[id="tabrow"]'));
    ok('the holder sits right before the hidden scroller',
       sd.getElementById('__db_cards').nextElementSibling ===
         sd.getElementById('vs1'));
    ok('single-screen scroller computes display:none',
       sp.window.getComputedStyle(sd.getElementById('vs1')).display === 'none');

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
      '<div data-video-id="v1" data-mcomponent="MContainer" class="old">' +
      '<a href="https://m.facebook.com/reel/77">old reel</a></div>' +
      '</div></div>';
    const placedR = renderOffline(
      ['<div data-tracking-duration-id="r9"><span>saved reel</span></div>'],
      reelsDoc);
    const holderR = placedR.doc.querySelector('[data-db-cards]');
    ok('reels: cards join the kept screen root, the pager computes hidden',
       !!holderR &&
       holderR.parentElement.getAttribute('data-mcomponent') === 'MScreen' &&
       placedR.dom.window.getComputedStyle(
         placedR.doc.getElementById('pager')).display === 'none');
    ok('reels: no chrome is invented for a pager document',
       placedR.doc.getElementById('__db_chrome') === null);
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
       res2.doc.body.firstElementChild.id === '__db_layout_reset' &&
       res2.doc.body.firstElementChild.nextElementSibling &&
       res2.doc.body.firstElementChild.nextElementSibling
         .getAttribute('data-db-cards') === '1');
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
     /promoHideCss\(\) \+[\s\S]{0,900}?"<\/head><body>"/.test(docs));
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

console.log('\nThe stories tray never becomes a card, and old saves heal');
{
  const cap = fs.readFileSync(KT('utils/OfflineCapture.kt'), 'utf8');
  const vaultSrc = fs.readFileSync(KT('offline/SectionVault.kt'), 'utf8');
  const H2 = require('./offline_vault_harness.js');

  // Round-10 report, screenshot attached: offline home showed the stories
  // tray TWICE ("story er ta double asche"). The capture's chrome filter
  // covers the composer and the tab row but never the tray, so the whole
  // tray was saved as a card; once the compose layer also laid out the
  // scroller's own tray, the page had one in the chrome and one among
  // the cards.
  const tray =
    '<div data-mcomponent="MContainer" id="trayunit"' +
    ' style="margin-top:160px; height:232px;">' +
    '<a href="https://m.facebook.com/stories/111">Create story</a>' +
    '<a href="https://m.facebook.com/stories/222">Your Story</a>' +
    '<a href="https://m.facebook.com/stories/333">Farzana</a></div>';
  const feedPost3 =
    '<div data-mcomponent="MContainer" data-tracking-duration-id="p4">' +
      '<div><span>a genuine post about the upcoming cricket series</span></div>' +
      '<a href="https://m.facebook.com/story.php?story_fbid=881">Full story</a></div>';

  ok('the capture chrome filter now knows the tray too',
     /a\[href\*="\/stories\/"\]/.test(cap) &&
     /querySelectorAll\('a\[href\*="\/stories\/"\]'\)\.length >= 2/.test(cap));
  const feed = runCapture(cap, 'https://m.facebook.com/',
    '<body><div data-type="vscroller">' + tray + feedPost3 + '</div></body>');
  ok('the tray is NOT captured as a card',
     !feed.some((it) => it.h.indexOf('trayunit') >= 0),
     JSON.stringify(feed.length));
  ok('the real post beside it still is',
     feed.some((it) => it.h.indexOf('story_fbid=881') >= 0));

  ok('the vault carries the same signature, feed-only',
     /JUNK_TRAY_LINK/.test(vaultSrc) &&
     /section != SECTION_FEED_ID\) return false/.test(vaultSrc));
  ok('vault mirror: a saved tray is junk in the feed vault',
     H2.isJunk('feed', tray));
  ok('vault mirror: still junk when every story thumbnail resolves',
     H2.isJunk('feed', tray.replace('Create story', 'Create new story')));
  ok('vault mirror: not junk in the reels or stories vaults',
     !H2.isJunk('reels', tray) && !H2.isJunk('stories', tray));
  ok('vault mirror: a single story link never condemns a post',
     !H2.isJunk('feed',
       '<div><span>see what he posted</span>' +
       '<a href="https://m.facebook.com/stories/111">his story</a></div>'));
  ok('vault mirror: a post holding story_fbid is never junk',
     !H2.isJunk('feed',
       '<div><a href="/stories/111">one</a><a href="/stories/222">two</a>' +
       '<a href="https://m.facebook.com/story.php?story_fbid=881">p</a></div>'));
  ok('vault mirror: the same link twice is not a tray',
     !H2.isJunk('feed',
       '<div><a href="/stories/111">a</a><a href="/stories/111">b</a></div>'));

  // Contrast: the v5.2.9 pair really let the tray in (the bug was real).
  // Stored-tray heal: among the cards it simply stops existing, so a
  // home that used to show tray-in-cards plus tray-in-chrome shows ONE.
  const withSavedTray = [tray, feedPost3];
  const healed = withSavedTray.filter((c) => !H2.isJunk('feed', c));
  ok('a tray saved by an older build heals at the next read',
     healed.length === 1 && healed[0] === feedPost3);

  // Round-13 amendment, proven end to end in scratch/round13_proof.js:
  // the user's REAL tray survived every v5.2.12 heal because its teasers
  // link to PROFILES, not /stories/ URLs - the two-distinct-href shape
  // above was a fixture assumption. The signature that now catches it
  // counts story-labelled teasers instead of hrefs, with the one-teaser
  // variant needing visible "create story" text as second witness, and
  // the same post-permalink guard in front. Capture and vault use one
  // definition of the tiers each; the vault mirrors it for old stores.
  const realTray =
    '<div class="t2">' +
    '<div aria-label="Create story">Create story +</div>' +
    '<div aria-label="Your story">Your Story</div>' +
    '<a href="/profile.php?id=701" aria-label="Shuvo story, 1 new">x</a>' +
    '<a href="/profile.php?id=702" aria-label="Evan story">y</a>' +
    '<span>' + 'words on the stories row, filler text only. '.repeat(5) +
    '</span></div>';
  ok('the capture filter knows the linkless real-shape tray',
     /var teasers = 0/.test(cap) &&
     /if \(teasers >= 3\) return true/.test(cap) &&
     /indexOf\('create story'\)/.test(cap));
  const realFeed = runCapture(cap, 'https://m.facebook.com/',
    '<body><div data-type="vscroller">' + realTray + feedPost3 +
    '</div></body>');
  ok('the verbatim capture SKIPS the real-shape tray',
     !realFeed.some((it) => it.h.indexOf('class="t2"') >= 0));
  ok('and still captures the post beside it',
     realFeed.some((it) => it.h.indexOf('story_fbid=881') >= 0));
  ok('the vault carries the same teaser signature, one definition',
     /JUNK_TRAY_TEASER/.test(vaultSrc) &&
     /JUNK_TRAY_CREATE/.test(vaultSrc) &&
     /TRAY_TEXT_TAGS/.test(vaultSrc));
  ok('vault mirror: the saved real-shape tray heals at the next read',
     H2.isJunk('feed', realTray));
  ok('vault mirror: one lone teaser tile does not condemn a post',
     !H2.isJunk('feed',
       '<div><div aria-label="Mim&#39;s story">tile</div>' +
       '<span>an ordinary update about my week at the office</span>' +
       '<a href="https://m.facebook.com/story.php?story_fbid=881">p</a></div>'));
  ok('vault mirror: teaser tiles plus story_fbid still means a post',
     !H2.isJunk('feed',
       '<div><div aria-label="Mim&#39;s story">1</div>' +
       '<div aria-label="Rina&#39;s story">2</div>' +
       '<div aria-label="Tan&#39;s story">3</div>' +
       '<a href="https://m.facebook.com/story.php?story_fbid=881">p</a></div>'));
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
     /list\.filter \{ it\.media\.any \{ u -> isVideoUrl\(u\) \} \}/.test(vaultSrc));
  ok('per-section retention floors exist',
     /keepFloor = 500/.test(home) && /keepFloor = 250/.test(reels) &&
     /keepFloor = 200/.test(stories));
  ok('the merge keeps at least the floor',
     /val keep = maxOf\(limit, keepFloor\)/.test(vaultSrc));
  ok('the cap uses the floored value',
     /if \(merged\.size >= keep\) break/.test(vaultSrc));
  ok('dedupe still runs as a backstop',
     /(seen\.add\(key\)|distinctBy \{ keyFor)/.test(vaultSrc));

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
  // Round-12, the user's own rule, verbatim: "kono reels playable holei
  // count hobe tar age na". A card earns its seat only once it can play;
  // markup with its media still in the air holds nothing. Counting raw
  // entries here is exactly how a shelf of half-downloaded reels read as
  // "target reached" while the screen could show one of them.
  ok('every intake gate counts seats: playable, plus what is still landing',
     /var room = cap - OfflineFeed\.realPlayableCount\(section\)/
       .test(main) &&
     /var room = exactTotal -\s*\n\s*OfflineFeed\.realPlayableCount\(sec\)/
       .test(sync) &&
     /room = hardCap - seated/
       .test(fs.readFileSync(KT('offline/SectionVault.kt'), 'utf8')));
  ok('re-captures still pass free against every stored id, media or not',
     /it\.id in storedIds\) true/.test(sync) &&
     /it\.id in storedIds\) true/.test(main) &&
     /else if \(room > 0\) \{ room--; true \} else false/.test(sync) &&
     /else if \(room > 0\) \{ room--; true \} else false/.test(main));
  ok('the page asks for exactly the counted cards',
     /OfflineFeed\.(cardMarkupList|realPlayableItems)\(it\)/.test(docs));
  ok('and serves them as the document itself',
     /PageAssembly\.compose\(docText, cards, snap = screen == "reels"\)/
       .test(docs));

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
console.log('\nOne gesture, one reel - the pager, proven on the verbatim script');
{
  // Round-16 (user-consented custom pager): CSS could not cap a fling
  // on this WebView (stop:always inert per the v5.2.14 device verdict,
  // mandatory only aligns the rest), so the reels gesture is handled
  // the way the real apps do it: drag follows the finger 1:1, release
  // commits exactly one card. These assertions run the VERBATIM
  // production template from PageAssembly.resumeScript in jsdom with
  // simulated touch storms and a controllable clock/frame pump, and
  // pin the whole contract: taps pass through, horizontal stays native,
  // small drags snap back, past-15% commits one, a violent fling STILL
  // commits only one, the snap CSS is removed at init so the browser
  // can never fight the animation, and feed carries no pager at all.
  const asm4 = fs.readFileSync(KT('offline/PageAssembly.kt'), 'utf8');
  const tpl2 = asm4.slice(asm4.indexOf('val main = """') + 14,
                         asm4.indexOf('""".trimIndent()',
                           asm4.indexOf('val main = """')));

  function pagerWorld(sec, cardCount) {
    let html = '<style id="__db_reels_snap">' +
      'html,body{scroll-snap-type:y mandatory!important}' +
      '#__db_cards>*{scroll-snap-align:start!important;' +
      'scroll-snap-margin-top:128px!important}</style>' +
      '<div data-type="vscroller"><div id="__db_cards" data-db-cards>';
    for (let i = 0; i < cardCount; i++) {
      html += '<div class="rc" data-video-id="r' + i + '"></div>';
    }
    html += '<div id="__db_snap_t" style="height:0"></div></div></div>';
    const dom = new JSDOM(html, { url: 'https://m.facebook.com/',
      runScripts: 'outside-only', pretendToBeVisual: true });
    const w = dom.window;
    // controllable clock + frame pump: the whole test stays synchronous
    let now = 1000; const rafQ = [];
    w.Date.now = () => now;
    w.requestAnimationFrame = (cb) => { rafQ.push(cb); return rafQ.length; };
    w.cancelAnimationFrame = (id) => { if (rafQ[id - 1]) rafQ[id - 1] = null; };
    const box = w.document.querySelector('[data-type="vscroller"]');
    Object.defineProperty(box, 'clientHeight', { value: 1000 });
    Object.defineProperty(box, 'scrollHeight', { value: cardCount * 1000 });
    w.document.querySelectorAll('.rc').forEach((c, i) => {
      c.getBoundingClientRect = () => ({
        top: i * 1000 - box.scrollTop,
        bottom: (i + 1) * 1000 - box.scrollTop,
        left: 0, right: 300, width: 300, height: 1000 });
    });
    w.FBPro = { reportPosition: () => {} };
    w.eval(tpl2.split('__SEC__').join(sec));

    function ev(type, x, y, dt) {
      if (dt) now += dt;
      const e = new w.Event(type, { bubbles: true, cancelable: true });
      e.touches = type === 'touchend' ? [] : [{ clientX: x, clientY: y }];
      e.changedTouches = type === 'touchend' ? [{ clientX: x, clientY: y }] : [];
      w.document.querySelector('.rc').dispatchEvent(e);
      return e;
    }
    function pump() {
      let guard = 0;
      while (rafQ.length && guard++ < 400) {
        now += 20;
        const batch = rafQ.splice(0, rafQ.length);
        for (const cb of batch) if (cb) cb();
      }
    }
    return { w, box, ev, pump,
      snapGone: () => !w.document.getElementById('__db_reels_snap') };
  }

  // 1. init removes the snap style so no browser/JS fight is possible
  {
    const P = pagerWorld('reels', 3);
    ok('pager init removes the reels snap CSS (no fight possible)',
       P.snapGone());
  }
  // 2. a tap is a tap: never eaten, never a scroll, never a commit
  {
    const P = pagerWorld('reels', 3);
    P.ev('touchstart', 100, 500);
    const end = P.ev('touchend', 100, 503);
    P.pump();
    ok('a 3px tap is never eaten: no preventDefault, no scroll, no commit',
       !end.defaultPrevented && P.box.scrollTop === 0, '' + P.box.scrollTop);
  }
  // 3. horizontal-dominant gesture is handed straight to the browser
  {
    const P = pagerWorld('reels', 3);
    P.ev('touchstart', 100, 500);
    P.ev('touchmove', 170, 490);
    P.ev('touchend', 175, 490);
    P.pump();
    ok('a horizontal gesture poses no commit (native keeps it)',
       P.box.scrollTop === 0, '' + P.box.scrollTop);
  }
  // 4. drag follows the finger 1:1; release under 15% lands back on start
  {
    const P = pagerWorld('reels', 3);
    P.ev('touchstart', 100, 800);
    P.ev('touchmove', 100, 790, 200);     // crosses the 7px lock here
    P.ev('touchmove', 100, 730, 200);     // slow drag, 200ms a move
    P.ev('touchmove', 100, 680, 200);     // 120px total = 12% of the card
    const mid = P.box.scrollTop;
    P.ev('touchend', 100, 680, 200);
    P.pump();
    // Notes: the 10px lock distance is re-anchored (device moves are a
    // few px each, so the re-anchor is invisible), hence 110 not 120.
    ok('mid-drag the page stands exactly where the finger put it',
       mid === 110, 'mid=' + mid);
    ok('a slow sub-15% drag lands back on the same reel (snap-back)',
       P.box.scrollTop === 0, 'final=' + P.box.scrollTop);
  }
  // 5. past-15% commits exactly the next reel, below the bar offset
  {
    const P = pagerWorld('reels', 3);
    P.ev('touchstart', 100, 800);
    P.ev('touchmove', 100, 710, 200);     // locks here
    P.ev('touchmove', 100, 620, 200);
    P.ev('touchmove', 100, 500, 200);     // slow 30% drag
    P.ev('touchend', 100, 500, 200);
    P.pump();
    ok('a slow 30% drag commits to the next reel, below the bar offset',
       P.box.scrollTop === 1000 - 128, 'final=' + P.box.scrollTop);
  }
  // 6. THE CAP - structural, not a correction: a violent multi-page
  //    fling can never even DISPLAY a reel two away, let alone land it
  {
    const P = pagerWorld('reels', 3);
    P.ev('touchstart', 100, 800);
    for (let k = 1; k <= 8; k++) P.ev('touchmove', 100, 800 - k * 500, 12);
    const mid = P.box.scrollTop;
    P.ev('touchend', 100, -3200, 12);     // finger crossed ~4 screens
    P.pump();
    ok('mid-fling it never reached even the SECOND reel away',
       mid < (2 * 1000 - 128), 'mid=' + mid);
    ok('a violent fling commits exactly ONE reel - never 2/3',
       P.box.scrollTop === 1000 - 128, 'final=' + P.box.scrollTop);
  }
  // 7. tiny-but-fast flick also commits one (velocity rule, real-app feel)
  {
    const P = pagerWorld('reels', 3);
    P.ev('touchstart', 100, 500);
    P.ev('touchmove', 100, 460, 30);      // 40px, but over 30ms -> 1.33px/ms
    P.ev('touchend', 100, 460, 30);
    P.pump();
    ok('a tiny fast flick commits the next reel (velocity rule)',
       P.box.scrollTop === 1000 - 128, 'final=' + P.box.scrollTop);
  }
  // 8. feed never carries the pager; its snap CSS survives too
  {
    const P = pagerWorld('feed', 3);
    P.ev('touchstart', 100, 500);
    P.ev('touchmove', 100, 300, 16);
    P.ev('touchend', 100, 300, 16);
    ok('on home the identical gesture is untouched (no pager, no snap cut)',
       !P.snapGone() && P.box.scrollTop === 0, '' + P.box.scrollTop);
  }
  // 9. THE ROUND-20 TAP RESCUE: a real fingertip wobbles 10-12px, which
  //    crosses the 7px lock, preventDefaults the move, and kills the
  //    browser's synthetic click - that is why a tap on a playing reel
  //    could never pause on device. A sub-slop gesture ending back on
  //    its own card gets its click delivered by hand.
  {
    const P = pagerWorld('reels', 3);
    const card = P.w.document.querySelector('.rc');
    let clicks = 0, lastTarget = null;
    P.w.document.addEventListener('click', (e) => {
      clicks++; lastTarget = e.target; });
    P.ev('touchstart', 100, 500);
    P.ev('touchmove', 100, 494, 200);  // crosses the 7px lock here
    P.ev('touchmove', 100, 488, 200);  // 12px from origin, slow
    P.ev('touchend', 100, 488, 200);
    P.pump();
    ok('a slow 12px wobble is still a tap: the click is rescued once',
       clicks === 1 && lastTarget === card, 'clicks=' + clicks);
    ok('and the card still settles back home, not a reel over',
       P.box.scrollTop === 0, '' + P.box.scrollTop);
  }
  // 10. past tap slop it really was a tiny drag: no fake click
  {
    const P = pagerWorld('reels', 3);
    let clicks = 0;
    P.w.document.addEventListener('click', () => clicks++);
    P.ev('touchstart', 100, 500);
    P.ev('touchmove', 100, 480, 200);  // 20px from origin
    P.ev('touchend', 100, 480, 200);
    P.pump();
    ok('a 20px drag rescues nothing and clicks nothing',
       clicks === 0 && P.box.scrollTop === 0, '' + P.box.scrollTop);
  }
  // 11. a FAST sub-slop flick is a flick, not a tap: velocity still wins
  {
    const P = pagerWorld('reels', 3);
    let clicks = 0;
    P.w.document.addEventListener('click', () => clicks++);
    P.ev('touchstart', 100, 500);
    P.ev('touchmove', 100, 493, 12);   // locks the drag here
    P.ev('touchend', 100, 488, 12);    // 12px from origin but ~0.6px/ms
    P.pump();
    ok('a quick 12px flick commits the next reel, with no tap rescued',
       clicks === 0 && P.box.scrollTop === 1000 - 128,
       'final=' + P.box.scrollTop);
  }
  // 12. end to end, both verbatim scripts: the exact wobble that could
  //     never pause on device now hits the tap bridge and pauses
  {
    const vhSrc = fs.readFileSync(KT('utils/VideoHelper.kt'), 'utf8');
    const vi = vhSrc.indexOf('fun getOfflineVideoAssistScript');
    const vs = vhSrc.indexOf('"""', vi) + 3;
    const assist = vhSrc.slice(vs, vhSrc.indexOf('"""', vs));
    const P = pagerWorld('reels', 3);
    const card = P.w.document.querySelector('.rc');
    const holder = P.w.document.createElement('div');
    const video = P.w.document.createElement('video');
    holder.appendChild(video);
    card.appendChild(holder);
    let pausedNow = false; const log = [];
    Object.defineProperty(video, 'paused', { get: () => pausedNow });
    video.play = () => { pausedNow = false; log.push('play');
      return { catch: () => {} }; };
    video.pause = () => { pausedNow = true; log.push('pause'); };
    pausedNow = false;                 // the reel is PLAYING on screen
    P.w.eval(assist);
    P.ev('touchstart', 100, 500);
    P.ev('touchmove', 100, 493, 200);
    P.ev('touchmove', 100, 490, 200);
    P.ev('touchend', 100, 490, 200);
    P.pump();
    ok('the same wobble-tap now PAUSES the playing reel (round 20)',
       log.join(',') === 'pause' && pausedNow === true, log.join(','));
    // and the rescued tap is a full citizen: a second one plays again
    P.ev('touchstart', 100, 500);
    P.ev('touchmove', 100, 492, 200);
    P.ev('touchend', 100, 492, 200);
    P.pump();
    ok('and tapping it again plays, exactly the online toggle',
       log.join(',') === 'pause,play' && pausedNow === false,
       log.join(','));
  }
  // 13. swipe-commit silences the reel just left (parked leak, v5.2.23):
  //     the pager only changed scrollTop before, and the solo-sound
  //     rule wakes only on 'play' - a swipe to a still reel left the
  //     last one sounding off-screen forever ("audio choltei thake").
  {
    const P = pagerWorld('reels', 3);
    const mkVideo = (card, id, log) => {
      const v = P.w.document.createElement('video');
      let paused = true;
      Object.defineProperty(v, 'paused', { get: () => paused });
      v.play = () => { paused = false; log.push(id + ':play');
        return { catch() {} }; };
      v.pause = () => { paused = true; log.push(id + ':pause'); };
      card.appendChild(v);
      return { isPaused: () => paused };
    };
    const cards = P.w.document.querySelectorAll('.rc');
    const log = [];
    const v0 = mkVideo(cards[0], 'v0', log);
    mkVideo(cards[1], 'v1', log);
    v0; // reel 0 is the one sounding
    cards[0].querySelector('video').play(); log.length = 0;
    P.ev('touchstart', 100, 500);
    P.ev('touchmove', 100, 493, 12);   // locks the drag here
    P.ev('touchend', 100, 488, 12);    // quick flick -> commits reel 1
    P.pump();
    ok('swiping to the next reel pauses the one just left',
       log.join(',') === 'v0:pause' &&
       cards[0].querySelector('video').paused, log.join(','));
    // and a tap-rescue commit at the same card never touches its own
    P.w.eval('');
    const P2 = pagerWorld('reels', 3);
    const c2 = P2.w.document.querySelectorAll('.rc');
    const log2 = [];
    let p2paused = false;
    const v2 = P2.w.document.createElement('video');
    Object.defineProperty(v2, 'paused', { get: () => p2paused });
    v2.play = () => { p2paused = false; log2.push('play');
      return { catch() {} }; };
    v2.pause = () => { p2paused = true; log2.push('pause'); };
    c2[0].appendChild(v2);
    const vhSrc = fs.readFileSync(KT('utils/VideoHelper.kt'), 'utf8');
    const vi = vhSrc.indexOf('fun getOfflineVideoAssistScript');
    const vs = vhSrc.indexOf('"""', vi) + 3;
    P2.w.eval(vhSrc.slice(vs, vhSrc.indexOf('"""', vs)));
    v2.play(); log2.length = 0;      // reel sounding, tap to pause
    P2.ev('touchstart', 100, 500);
    P2.ev('touchmove', 100, 490, 200);
    P2.ev('touchend', 100, 490, 200);
    P2.pump();
    ok('the tap-rescue path pauses through the bridge alone, once',
       log2.join(',') === 'pause', log2.join(','));
  }
  // 14. multi-player card safety: a card carrying two players pauses
  //     BOTH; on one-player cards the toggle is byte-identical to before
  {
    const P = pagerWorld('reels', 3);
    const card = P.w.document.querySelector('.rc');
    const mkV = (id, log) => {
      const v = P.w.document.createElement('video');
      let paused = true;
      Object.defineProperty(v, 'paused', { get: () => paused });
      v.play = () => { paused = false; log.push(id + ':play');
        return { catch() {} }; };
      v.pause = () => { paused = true; log.push(id + ':pause'); };
      card.appendChild(v);
    };
    const log = [];
    mkV('a', log); mkV('b', log);
    const vhSrc = fs.readFileSync(KT('utils/VideoHelper.kt'), 'utf8');
    const vi = vhSrc.indexOf('fun getOfflineVideoAssistScript');
    const vs = vhSrc.indexOf('"""', vi) + 3;
    P.w.eval(vhSrc.slice(vs, vhSrc.indexOf('"""', vs)));
    // both sounding (whatever species this card is), tap once
    card.querySelectorAll('video').forEach((v) => v.play());
    log.length = 0;
    card.dispatchEvent(new P.w.MouseEvent('click',
      { bubbles: true, cancelable: true }));
    ok('one tap pauses every player the card carries',
       log.join(',') === 'a:pause,b:pause', log.join(','));
    card.dispatchEvent(new P.w.MouseEvent('click',
      { bubbles: true, cancelable: true }));
    ok('and the resume plays one source, never two sounds',
       log.join(',') === 'a:pause,b:pause,a:play', log.join(','));
  }
}

console.log('\nSeen tracking: unseen first, seen sinks, reconnect evicts seen');
{
  const H = require('./offline_vault_harness.js');
  const vaultSrc = fs.readFileSync(KT('offline/SectionVault.kt'), 'utf8');
  const vaultsSrc = fs.readFileSync(KT('offline/OfflineVaults.kt'), 'utf8');
  const feedKt = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');
  const docsKt = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');
  const asmSrc = fs.readFileSync(KT('offline/PageAssembly.kt'), 'utf8');
  const mainKt = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  const bsmKt = fs.readFileSync(KT('utils/BackgroundSyncManager.kt'), 'utf8');

  // ---- the data model and its one writer ----
  ok('an entry knows it was seen, and when',
     /val viewed: Boolean = false/.test(vaultSrc) &&
     /val viewedAt: Long\? = null/.test(vaultSrc));
  ok('old stores without the keys read as never-seen',
     /optBoolean\("v", false\)/.test(vaultSrc) &&
     /optLong\("va", 0L\)/.test(vaultSrc));
  ok('seen flags persist through the one atomic writer',
     /private fun writeAll\(entries: List<Entry>\): Boolean/.test(vaultSrc) &&
     /\.put\("v", it\.viewed\)/.test(vaultSrc) &&
     /\.put\("va", it\.viewedAt \?: 0L\)/.test(vaultSrc) &&
     /tmp\.renameTo\(f\)/.test(vaultSrc));
  ok('addItems itself goes through that writer now',
     /fun addItems[\s\S]{0,5200}writeAll\(merged\)/.test(vaultSrc));
  ok('trimTo goes through it too (kept entries stay seen)',
     /fun trimTo[\s\S]{0,700}if \(!writeAll\(keep\)\) return 0/.test(vaultSrc) &&
     !/fun trimTo[\s\S]{0,700}JSONArray\(\)/.test(vaultSrc));
  ok('marking is local, idempotent and locked',
     /fun markViewed\(id: String\)/.test(vaultSrc) &&
     /fun markViewed[\s\S]{0,500}synchronized\(this\)/.test(vaultSrc) &&
     /copy\([\s\S]{0,120}viewed = true/.test(vaultSrc));

  // ---- the serving order: unseen first, stable inside each group ----
  ok('the read path sinks the seen to the bottom',
     /fun completeItems\(\): List<Entry> =[\s\S]{0,120}sortedBy \{ if \(it\.viewed\) 1 else 0 \}/
       .test(vaultSrc));
  {
    const disk = new Set(['p1.jpg', 'p2.jpg', 'p3.jpg', 'p4.jpg', 'p5.jpg']);
    const ent = (id, viewed) => ({ id, html: '<div>' + id + '</div>',
      media: [id + '.jpg'], viewed, viewedAt: viewed ? 100 : null });
    const out = H.completeItems(
      [ent('p1', true), ent('p2', false), ent('p3', true),
       ent('p4', false), ent('p5', false)],
      (u) => disk.has(u));
    ok('unseen first, and never re-shuffled inside a group',
       out.map((e) => e.id).join(',') === 'p2,p4,p5,p1,p3',
       out.map((e) => e.id).join(','));
    ok('the count is untouched by the reorder',
       out.length === 5);
  }

  // ---- the merge: over the floor, seen cards surrender first ----
  ok('the merge partitions seen after unseen',
     /filtered \+ existing\)[\s\S]{0,120}distinctBy \{ keyFor\(it\) \}[\s\S]{0,120}sortedBy \{ if \(it\.viewed\) 1 else 0 \}/
       .test(vaultSrc) &&
     /if \(merged\.size >= keep\) break/.test(vaultSrc));
  {
    const seenOld = [
      { id: 'old-unseen-1', html: 'a', media: [], viewed: false },
      { id: 'old-seen-1', html: 'b', media: [], viewed: true, viewedAt: 10 },
      { id: 'old-seen-2', html: 'c', media: [], viewed: true, viewedAt: 20 },
    ];
    const fresh = Array.from({ length: 48 }, (_, i) =>
      ({ id: 'new-' + i, html: 'n', media: [] }));
    const merged = H.addItems(seenOld, fresh, 49, 3);
    ok('a full store drops every seen card before one unseen',
       merged.some((e) => e.id === 'old-unseen-1') &&
       !merged.some((e) => e.id === 'old-seen-1') &&
       !merged.some((e) => e.id === 'old-seen-2') &&
       merged.length === 49);
    const again = H.addItems(seenOld,
      [{ id: 'old-seen-1', html: 'b2-fresh-media', media: [] }], 50, 3);
    ok('a re-captured seen entry replaces in place, not duplicates',
       again.filter((e) => e.id === 'old-seen-1').length === 1 &&
       again.some((e) => e.id === 'old-unseen-1'));
  }

  // ---- markViewed on a stored list ----
  {
    const list = [
      { id: 'a', html: 'x', media: [], viewed: false },
      { id: 'b', html: 'y', media: [], viewed: false },
    ];
    const once = H.markViewed(list, 'a');
    ok('marking flips exactly the named entry, with a timestamp',
       once[0].viewed === true && typeof once[0].viewedAt === 'number' &&
       once[1].viewed === false && list[0].viewed === false);
    const twice = H.markViewed(once, 'a');
    ok('a second mark is a no-op (the write is skipped)',
       twice === once);
    ok('blank and unknown ids touch nothing',
       H.markViewed(list, '') === list && H.markViewed(list, 'zz') === list);
  }

  // ---- the reconnect eviction, floor maths ----
  ok('the reconnect evict keeps the floor with unseen cards first',
     /fun evictViewedOnReconnect\(\)/.test(vaultSrc) &&
     /sortedByDescending \{ it\.viewedAt \?: 0L \}/.test(vaultSrc) &&
     /take\(maxOf\(0, keepFloor - unviewed\.size\)\)/.test(vaultSrc) &&
     /fun evictViewedOnReconnect\(\) \{\s*sections\.forEach/.test(vaultsSrc));
  {
    const ent = (id, viewed, at) => ({ id, html: 'h', media: [],
      viewed, viewedAt: at });
    const store = [ent('u1', false, null), ent('u2', false, null),
                   ent('s1', true, 50), ent('s2', true, 30),
                   ent('s3', true, 70), ent('s4', true, 10)];
    const kept = H.evictViewedOnReconnect(store, 3);
    ok('unseen is untouchable; only the freshest seen fills the floor',
       kept.map((e) => e.id).join(',') === 'u1,u2,s3',
       kept.map((e) => e.id).join(','));
    const rich = store.concat([ent('u3', false, null), ent('u4', false, null)]);
    ok('once unseen alone meets the floor, every seen card leaves',
       H.evictViewedOnReconnect(rich, 3).every((e) => !e.viewed));
    ok('a second pass over the result changes nothing',
       H.evictViewedOnReconnect(kept, 3) === kept);
    ok('never-seen stores skip the writer entirely',
       H.evictViewedOnReconnect(
         [ent('u1', false, null)], 3).length === 1);
  }

  // ---- stamping: the page can name each card ----
  ok('cards serve with their vault id, escaped and surgery-inserted',
     /fun stampOfflineId\(html: String, id: String\)/.test(docsKt) &&
     /replace\("&", "&amp;"\)/.test(docsKt) &&
     /m\.range\.first/.test(docsKt) &&
     !/replaceFirst/.test(docsKt.slice(docsKt.indexOf('fun stampOfflineId'),
        docsKt.indexOf('fun storyViewer'))));
  {
    ok('the stamp lands on the card root, id intact',
       H.stampOfflineId('<div class="c">x</div>', 'data-video-id:rv1') ===
       '<div class="c" data-offline-id="data-video-id:rv1">x</div>');
    ok('escaping: an ampersand id can never break the attribute',
       H.stampOfflineId('<div>x</div>', 'href:a&b"c') ===
       '<div data-offline-id="href:a&amp;b&quot;c">x</div>');
    ok('a $ in the id is text, never a group reference',
       H.stampOfflineId('<div>x</div>', 'text:$1x') ===
       '<div data-offline-id="text:$1x">x</div>');
    ok('blank ids and pre-stamped cards are left alone',
       H.stampOfflineId('<div>x</div>', '') === '<div>x</div>' &&
       H.stampOfflineId('<div data-offline-id="k">x</div>', 'n') ===
         '<div data-offline-id="k">x</div>');
    ok('self-closing roots stamp cleanly too',
       H.stampOfflineId('<img src="a.jpg"/>', 'i1') ===
       '<img src="a.jpg" data-offline-id="i1"/>');
  }

  // ---- wiring: who hears the page, who runs the eviction ----
  ok('the offline page talks to the vault through one bridge door',
     /@JavascriptInterface[\s\S]{0,500}fun markViewed\(section: String, id: String\)/
       .test(mainKt) &&
     /fun markViewed\(section: String, id: String\) [\s\S]{0,200}SECTIONS\.contains\(section\)/
       .test(feedKt));
  ok('serving attaches the tracker beside the resume script, and the shell too',
     /"<script>" \+ PageAssembly\.resumeScript\(resumeId, screen\) \+ "<\/script>" \+[\s\S]{0,120}PageAssembly\.viewTrackScript\(/
       .test(docsKt) &&
     (docsKt.match(/viewTrackScript\(/g) || []).length === 2);
  ok('the tracker exists and holds the honest thresholds',
     /fun viewTrackScript\(section: String\): String/.test(asmSrc) &&
     /RATIO = 0\.6/.test(asmSrc) && /DWELL_MS = 1500/.test(asmSrc) &&
     /IntersectionObserver/.test(asmSrc));
  ok('the story viewer reports one tap-through as one seen',
     /storyViewer\(cards: List<String>, ids: List<String>,[\s\S]{0,60}section: String/
       .test(docsKt) &&
     /FBPro\.markViewed\(SEC, sid\)/.test(docsKt));
  ok('reconnect evicts seen inside the cycle, after the policy check',
     /if \(!NetworkPolicy\.canDownload\(c, p\)\) return[\s\S]{0,2500}OfflineVaults\.evictViewedOnReconnect\(\)/
       .test(bsmKt) &&
     /diskIO\.execute \{[\s\S]{0,400}trimTo[\s\S]{0,300}evictViewedOnReconnect/
       .test(bsmKt));
  {
    const fnStart = bsmKt.indexOf('fun onNetworkRestored');
    const fnBody = bsmKt.slice(fnStart, fnStart + 900);
    ok('reconnect no longer wipes the unread library',
       !/clearAllStored/.test(fnBody) &&
       !/fun clearAllStored/.test(bsmKt));
  }

  // ---- the tracker itself, verbatim, in a DOM ----
  {
    const i = asmSrc.indexOf('fun viewTrackScript');
    const s = asmSrc.indexOf('"""', i) + 3;
    const e = asmSrc.indexOf('"""', s);
    // The dwell const is overridden AFTER extraction, only to keep the
    // suite synchronous; every other byte is the shipped template.
    const tpl = asmSrc.slice(s, e);
    const world = (sec) => {
      const dom = new JSDOM(
        '<html><body><div id="__db_cards" data-db-cards="1">' +
        '<div class="card" data-offline-id="data-video-id:rv1"></div>' +
        '<div class="card" data-offline-id="data-video-id:rv2"></div>' +
        '<div class="card"></div>' +
        '</div></body></html>',
        { runScripts: 'outside-only', pretendToBeVisual: true,
          url: 'https://m.facebook.com/' });
      const w = dom.window;
      const calls = [];
      w.FBPro = { markViewed: (s2, id) => calls.push([s2, id]) };
      const timers = [];
      w.setTimeout = (fn) => { timers.push(fn); return timers.length; };
      w.clearTimeout = (id) => { if (timers[id - 1]) timers[id - 1] = null; };
      const pump = () => {
        const q = timers.slice(); timers.length = 0;
        for (const f of q) if (f) f();
      };
      w.innerHeight = 768;
      for (const el of w.document.querySelectorAll('.card')) {
        el.getBoundingClientRect = () =>
          ({ top: 0, bottom: 600, height: 600, left: 0, right: 360,
             width: 360 });
      }
      const instances = [];
      w.IntersectionObserver = class {
        constructor(cb, opts) { this.cb = cb; this.opts = opts;
          this.observed = []; instances.push(this); }
        observe(el) { this.observed.push(el); }
        unobserve(el) { this.observed =
          this.observed.filter((x) => x !== el); }
      };
      w.eval(tpl.replace('__SEC__', sec)
                .replace('var DWELL_MS = 1500;', 'var DWELL_MS = 25;'));
      return { w, calls, pump, instances,
        fire: (el, ratio) => instances[0].cb(
          [{ target: el, intersectionRatio: ratio }]) };
    };

    // 1. a card at 80% for the dwell is reported exactly once, with its
    //    own section, and unobserved afterwards
    {
      const W = world('reels');
      const c1 = W.w.document.querySelector(
        '[data-offline-id="data-video-id:rv1"]');
      ok('only id-stamped cards are watched', W.instances.length === 1 &&
         W.instances[0].observed.length === 2);
      W.fire(c1, 0.8); W.pump();
      W.fire(c1, 0.8); W.pump();
      ok('60%+ for the dwell reports one seen, named by id and section',
         W.calls.length === 1 &&
         W.calls[0][0] === 'reels' &&
         W.calls[0][1] === 'data-video-id:rv1');
      ok('a reported card is unobserved - no work left per scroll',
         W.instances[0].observed.length === 1);
    }
    // 2. scrolling away before the dwell resets the clock silently
    {
      const W = world('feed');
      const c1 = W.w.document.querySelector(
        '[data-offline-id="data-video-id:rv1"]');
      W.fire(c1, 0.8);
      W.fire(c1, 0.1);
      W.pump();
      ok('a scroll-past before the dwell marks nothing', W.calls.length === 0);
    }
    // 3. a sub-threshold flicker alone never even starts the clock
    {
      const W = world('feed');
      const c1 = W.w.document.querySelector(
        '[data-offline-id="data-video-id:rv1"]');
      W.fire(c1, 0.4); W.pump();
      ok('under 60% there is no clock and no mark', W.calls.length === 0);
    }
    // 4. no IntersectionObserver at all: the page just tracks nothing
    {
      const dom = new JSDOM('<html><body><div data-db-cards="1"></div>' +
        '</body></html>',
        { runScripts: 'outside-only', url: 'https://m.facebook.com/' });
      let threw = false;
      try { dom.window.eval(tpl.replace('__SEC__', 'feed')); }
      catch (e) { threw = true; }
      ok('a browser without IntersectionObserver breaks nothing', !threw);
    }
  }

  // ---- the story viewer, verbatim, in a DOM ----
  {
    const sv = docsKt.slice(docsKt.indexOf('private fun storyViewer'));
    const i = sv.indexOf('(function(){');
    const t = sv.slice(i);
    const js = t.slice(0, t.indexOf('})();') + 5)
      .replace('$safe', JSON.stringify(
        ['<div data-offline-id="s1">story one</div>',
         '<div data-offline-id="s2">story two</div>']))
      .replace('$safeIds', JSON.stringify(['story:/a', 'story:/b']))
      .replace('$section', 'stories')
      .replace('$resumeJs', 'var START=0;');
    const dom = new JSDOM('<html><head></head><body></body></html>',
      { runScripts: 'outside-only', pretendToBeVisual: true,
        url: 'https://m.facebook.com/stories/' });
    const calls = [];
    dom.window.FBPro = { markViewed: (s2, id) => calls.push([s2, id]) };
    dom.window.eval(js);
    const overlay = dom.window.document
      .getElementById('__db_story_overlay');
    ok('opening the viewer marks the first story seen, as stories',
       calls.length === 1 &&
       calls[0][0] === 'stories' && calls[0][1] === 'story:/a');
    overlay.children[1].dispatchEvent(
      new dom.window.MouseEvent('click', { bubbles: true }));
    ok('tapping through marks the next story with ITS id',
       calls.length === 2 && calls[1][1] === 'story:/b');
    overlay.children[0].dispatchEvent(
      new dom.window.MouseEvent('click', { bubbles: true }));
    ok('tapping back re-reports (the vault-side no-op dedupes)',
       calls.length === 3 && calls[2][1] === 'story:/a');
  }
}

console.log('\nResume position: the gate is the document, not the radio (round 20)');
{
  const mainKt = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');

  // The round-20 report: reels resume froze at the same first item.
  // reportPosition used to return early whenever isOnline was true - and
  // offline reading survives a silent reconnect, so a mid-read network
  // return froze every position written after it. The bridge now trusts
  // the page itself: OfflineDocs marks exactly the documents it served.
  ok('the resume bridge no longer consults the radio',
     !/fun reportPosition\(type: String, id: String\)[\s\S]{0,90}if \(isOnline\) return/
       .test(mainKt));
  ok('it answers only while an OfflineDocs document is on screen',
     /fun reportPosition\(type: String, id: String\)[\s\S]{0,90}if \(!isShowingOfflinePage\) return/
       .test(mainKt) &&
     /@Volatile private var isShowingOfflinePage: Boolean/.test(mainKt));
  ok('every main-frame navigation clears the flag first',
     /if \(request\.isForMainFrame\) isShowingOfflinePage = false/
       .test(mainKt));
  ok('and only a real serve() answer sets it',
     /OfflineDocs\.serve\(request\)\?\.let \{[\s\S]{0,80}isShowingOfflinePage = true[\s\S]{0,40}return it/
       .test(mainKt));
}


console.log('\nRound 21 - ten means ten (in-flight seats), one nav row (badge species)');
{
  const Hnew = require('./offline_vault_harness.js');
  const cp = require('child_process');
  const Hold = (() => {
    // The old side must be pinned by HASH, never HEAD: in CI HEAD is the
    // commit under test (the fix itself), so 'git show HEAD' reads the NEW
    // behavior and every old-side pin lies. e90a8f6 is remote main before
    // this round - immutable, and reachable forever.
    const oldSrc = cp.execSync(
      'git show e90a8f6:tools/offline_vault_harness.js', { cwd: ROOT }).toString();
    const tmp = path.join(ROOT, 'tools', '.harness_prev_tmp.js');
    fs.writeFileSync(tmp, oldSrc);
    const m = require('./.harness_prev_tmp.js');
    fs.unlinkSync(tmp);
    return m;
  })();
  const H521 = (() => {
    // 9ba4283 = v5.2.21, the build the device rejected with an empty
    // nav bar: exact species surfaced, both badge forms stayed LEAVE.
    const oldSrc = cp.execSync(
      'git show 9ba4283:tools/offline_vault_harness.js', { cwd: ROOT }).toString();
    const tmp = path.join(ROOT, 'tools', '.harness_521_tmp.js');
    fs.writeFileSync(tmp, oldSrc);
    const m = require('./.harness_521_tmp.js');
    fs.unlinkSync(tmp);
    return m;
  })();

  // ------------------------------------------------ Bug 2: the badge row
  // The three shapes the row travels in (round-17 fixture, proven against
  // the user's stored entry "text:15+ 15+ 4 15+": the shell is HREFLESS).
  const rowExact =
    '<div data-mcomponent="MContainer" class="m" id="navA">' +
    '<a href="/home.php" aria-label="Home">15+</a>' +
    '<a href="/friends/" aria-label="Friends">15+</a></div>';
  const rowBadged =
    '<div data-mcomponent="MContainer" class="m" id="navB">' +
    '<a href="https://m.facebook.com/home" aria-label="Home, 2 new">15+</a>' +
    '<a href="https://m.facebook.com/notifications/"' +
    ' aria-label="Notifications, 15+ notifications">15+</a>' +
    '<a href="https://m.facebook.com/bookmarks/"' +
    ' aria-label="Marketplace, 9 new">4</a></div>';
  const rowShell =
    '<div data-mcomponent="MContainer" class="m" id="navC">' +
    '<div role="button" aria-label="Notifications, 4">4</div>' +
    '<div role="button" aria-label="Home, 1 new">15+</div>' +
    '<span>15+ 4 15+</span></div>';

  ok('HEAD vault kept the badge-row species as cards (the live bug)',
     Hold.isJunk('feed', rowBadged) === false &&
     Hold.isJunk('feed', rowShell) === false);
  ok('the shared definition heals them at the next read',
     Hnew.isJunk('feed', rowBadged) === true &&
     Hnew.isJunk('feed', rowShell) === true &&
     Hnew.isJunk('feed', rowExact) === true);

  const realPost2links =
    '<div><span>guys check the <a href="https://m.facebook.com/home">' +
    'home page</a> and the <a href="https://m.facebook.com/watch">' +
    'watch feed</a></span>' +
    '<a href="https://m.facebook.com/story.php?story_fbid=5">Full</a></div>';
  ok('a post linking two nav pages still survives (permalink guard)',
     Hnew.isJunk('feed', realPost2links) === false);
  // The regression this round refuses to create: a REAL feed card can
  // legitimately hold nav-prefixed labels - the audience icon "Friends"
  // and a "Watch more" control - two of them. Without the permalink
  // guard the prefix tier would eat that post (the exact accusation
  // class that once ate real posts via greedy labels).
  const reelCard =
    '<div data-video-id="8811">' +
    '<img src="https://scontent.fkul5-1.fna.fbcdn.net/v/t15/x.jpg"' +
    ' aria-label="Friends">' +
    '<span aria-label="Watch more reels">watch more</span>' +
    '<a href="https://m.facebook.com/reel/8811/">See reel</a></div>';
  ok('a reel card with two nav-prefixed labels still survives (guard)',
     Hnew.isJunk('feed', reelCard) === false &&
     Hnew.isTabRowMarkup(reelCard) === true);
  ok('non-feed sections remain untouched by the feed-only rule',
     Hnew.isJunk('reels', rowBadged) === false);

  // classify: HEAD production had the exact-label loop -> LEAVE and no
  // badge tier (structure-pinned, so "old" below cannot drift).
  const asmOld = cp.execSync(
    'git show e90a8f6:app/src/main/java/com/dustbook/app/offline/PageAssembly.kt',
    { cwd: ROOT }).toString();
  ok('HEAD classify really was exact-labels-then-POST_LINK (pinned)',
     /if \(\+\+labels >= 2\) return LEAVE/.test(asmOld) &&
     !/isTabRowMarkup/.test(asmOld));
  function classifyOldProd(slice) {
    if (slice.toLowerCase().includes(Hnew.JUNK.AD_TAG.toLowerCase())) return 1;
    Hnew.JUNK.TAB_LABEL.lastIndex = 0;
    let labels = 0;
    while (Hnew.JUNK.TAB_LABEL.exec(slice) !== null) {
      if (++labels >= 2) return 1;                 // exact rows: LEAVE
    }
    if (/story_fbid|\/posts\/|\/videos\/|\/reel\//i.test(slice)) return 2;
    return 0;                                      // badge rows: MOVE
  }
  ok('HEAD classed the badge species as generic chrome/posts (the dupe)',
     classifyOldProd(rowBadged) === 0 && classifyOldProd(rowShell) === 0);

  // ROUND 22 - Bug 4 (v3): the REAL row vanished with the shell.
  // c4a37fd flipped the exact-label tier MOVE_TAB -> LEAVE for no
  // recorded reason; for three releases the badge-shell duplicate
  // masked it, and once the v2 heals removed the shell the offline
  // home had NO nav row at all. Pin the shipped-Bug-4 build by hash
  // (c367b21 = remote main when the v3 report was written), prove its
  // dead state on all three species, then the fix's full truth table.
  const asmBug4 = cp.execSync(
    'git show c367b21:app/src/main/java/com/dustbook/app/offline/PageAssembly.kt',
    { cwd: ROOT }).toString();
  const asmPhone = cp.execSync(
    'git show f0d9cdc:app/src/main/java/com/dustbook/app/offline/PageAssembly.kt',
    { cwd: ROOT }).toString();
  ok('the Bug-4 build really left every species hidden (pinned)',
     /if \(\+\+labels >= 2\) return LEAVE/.test(asmBug4) &&
     /isTabRowMarkup/.test(asmBug4));
  ok('the phone-era build surfaced the exact-label row (pinned)',
     /if \(\+\+labels >= 2\) return MOVE_TAB/.test(asmPhone));
  function classifyBug4Build(slice) {
    if (slice.toLowerCase().includes(Hnew.JUNK.AD_TAG.toLowerCase())) return 1;
    Hnew.JUNK.TAB_LABEL.lastIndex = 0;
    let labels = 0;
    while (Hnew.JUNK.TAB_LABEL.exec(slice) !== null) {
      if (++labels >= 2) return 1;                 // exact rows: LEAVE
    }
    if (Hnew.isTabRowMarkup(slice) &&              // badge rows: LEAVE
        !/story_fbid|\/posts\/|\/videos\/|\/reel\//i.test(slice)) return 1;
    if (/story_fbid|\/posts\/|\/videos\/|\/reel\//i.test(slice)) return 2;
    return 0;
  }
  ok('shipped 5.2.20 hid every species: no nav offline (Bug 4, verbatim)',
     classifyBug4Build(rowExact) === 1 && classifyBug4Build(rowBadged) === 1 &&
     classifyBug4Build(rowShell) === 1);
  // v5.2.21 mistake, pinned honest: it classed the two badge forms as
  // junk, but on this device they ARE the row (the 03:34 screenshot's
  // empty nav bar on 5.2.21). Every row species surfaces now; the
  // burial of extra copies happens at capture/vault/cap, not classify.
  ok('every row species returns as navigation, shells included',
     Hnew.classify(rowExact) === 3 &&
     Hnew.classify(rowBadged) === 3 && Hnew.classify(rowShell) === 3);
  ok('...and the v5.2.21 build really did bury both badge forms (pinned)',
     H521.classify(rowBadged) === 1 && H521.classify(rowShell) === 1 &&
     H521.classify(rowExact) === 3);
  ok('a real post still STOPs the walk (permalink guard in classify)',
     Hnew.classify(reelCard) === 2 && Hnew.classify(realPost2links) === 2);

  // The capture pass itself, verbatim script, HEAD vs fixed, on the
  // fixture the device proved: a vscroller holding both badge species
  // and one genuine photo post.
  function captureItems(captureKt) {
    // Extract the JS template out of the Kotlin container, the same way
    // test_native_behaviour.js does: from the return """ after
    // 'fun script(' up to """.trimIndent().
    // The kdoc also says "OfflineCapture.script(" - anchor on the
    // signature's own template opener instead (the established offset).
    const start = captureKt.indexOf('): String = """') + '): String = """'.length;
    const js = captureKt.slice(start, captureKt.indexOf('""".trimIndent()', start))
      .replace('$reelTarget', '50')
      .replace('$MAX_CARD_CHARS', '120000')
      .replace('${knownIds.asJsSet()}', '')
      .replace(/\$\{if \(syncMode\)[^}]*\}/, 'false');
    const post =
      '<div data-tracking-duration-id="p1">' +
      '<img src="https://scontent.fkul5-1.fna.fbcdn.net/v/t39/p1.jpg">' +
      '<span>a genuine photo post about friday food</span>' +
      '<a href="https://m.facebook.com/story.php?story_fbid=77">Full story</a></div>';
    const html = '<html><body><div data-type="vscroller">' +
      rowShell + rowBadged + post + '</div></body></html>';
    const dom = new JSDOM(html, {
      runScripts: 'outside-only', pretendToBeVisual: true,
      url: 'https://m.facebook.com/home.php'
    });
    const got = [];
    dom.window.FBPro = {
      onOfflineItems: (s, json) => {
        JSON.parse(json).forEach((it) => got.push(it));
      }
    };
    dom.window.eval(js);
    Object.defineProperty(dom.window.document, 'visibilityState',
      { value: 'hidden', configurable: true });
    dom.window.eval('document.dispatchEvent(new Event("visibilitychange"))');
    return got;
  }
  const capOldKt = cp.execSync(
    'git show e90a8f6:app/src/main/java/com/dustbook/app/utils/OfflineCapture.kt',
    { cwd: ROOT }).toString();
  const capNewKt = fs.readFileSync(KT('utils/OfflineCapture.kt'), 'utf8');
  const itemsOld = captureItems(capOldKt), itemsNew = captureItems(capNewKt);
  const hasRow = (items) => items.some((it) =>
    /Notifications, 1[45]/.test(it.h) || /navC|navB/.test(it.h));
  ok('HEAD capture saved both badge rows as cards',
     itemsOld.length >= 2 && hasRow(itemsOld),
     itemsOld.length + ' items');
  ok('the fixed capture saves only the real post',
     itemsNew.length === 1 &&
     /story_fbid=77/.test(itemsNew[0].h),
     itemsNew.length + ' items');

  // End to end through compose, for the species where HEAD-mirror and
  // HEAD-production agree (both MOVE): the pair used to relocate into
  // chrome as the second nav area. Round 23 (device proof): on this
  // device the badge form IS the real row - the badges live INSIDE the
  // aria-labels - so it surfaces as THE navigation bar; the v2 leak
  // (rows stored as cards) stays buried by the capture/vault arms.
  const doc =
    '<div data-mcomponent="MScreen" class="m bg-s2">' +
    '<div class="fixed-container top" id="hdr">bar</div>' +
    '<div data-type="vscroller" class="m vscroller">' +
    '<div id="trayX" style="margin-top:52px; height:210px;">' +
    '<a href="/stories/9/">tray</a></div>' +
    '<div id="navB2" style="margin-top:262px; height:52px;">' +
    '<a href="https://m.facebook.com/home" aria-label="Home, 2 new">15+</a>' +
    '<a href="https://m.facebook.com/bookmarks/"' +
    ' aria-label="Marketplace, 9 new">4</a>' +
    '<div role="button" aria-label="Notifications, 4">4</div></div>' +
    '</div></div>';
  const keepPostX =
    '<div data-tracking-duration-id="k9">' +
    '<div><span>a genuine post about nothing at all, quite enough text</span></div>' +
    '<a href="https://m.facebook.com/story.php?story_fbid=9">Full</a></div>';
  const chromeOld = new JSDOM('<html><body>' +
    Hold.compose(doc, [keepPostX]) + '</body></html>',
    { url: 'https://m.facebook.com/' }).window.document
      .getElementById('__db_chrome');
  const chromeNew = new JSDOM('<html><body>' +
    Hnew.compose(doc, [keepPostX]) + '</body></html>',
    { url: 'https://m.facebook.com/' }).window.document
      .getElementById('__db_chrome');
  ok('HEAD relocated the badge species into chrome (second nav area)',
     chromeOld && /Notifications, 4/.test(chromeOld.innerHTML),
     chromeOld ? chromeOld.children.length + ' units' : 'none');
  ok('v5.2.21 hid even the badge form entirely (the device empty bar)',
     H521.classify('<div><a href="/home" aria-label="Home, 2 new">15+</a>' +
       '<a href="/bookmarks/" aria-label="Marketplace, 9 new">4</a></div>') === 1 &&
     H521.classify('<div role="button" aria-label="Notifications, 4">4' +
       '<div role="button" aria-label="Home, 1 new">15+</div>') === 1);
  ok('now the badge form surfaces as the single navigation bar',
     !!chromeNew && chromeNew.querySelectorAll('#navB2').length === 1 &&
     /Notifications, 4/.test(chromeNew.innerHTML),
     chromeNew ? chromeNew.children.length + ' units' : 'no chrome');

  // The actual duplicate mechanism from bug-report-v2: a recycled twin
  // whose badge text differs dodges the text-key dedup. Cap = one row.
  {
    const navTwin =
      '<div id="navT" style="margin-top:314px; height:52px;">' +
      '<a href="https://m.facebook.com/home" aria-label="Home, 3 new">7</a>' +
      '<a href="https://m.facebook.com/bookmarks/"' +
      ' aria-label="Marketplace, 10 new">9</a>' +
      '<div role="button" aria-label="Notifications, 17">17</div></div>';
    const twoRowDoc =
      '<div data-mcomponent="MScreen" class="m bg-s2">' +
      '<div class="fixed-container top" id="hdr2">bar</div>' +
      '<div data-type="vscroller" class="m vscroller">' +
      '<div id="navF" style="margin-top:52px; height:52px;">' +
      '<div role="button" aria-label="Home, 2 new">15+</div>' +
      '<div role="button" aria-label="Reels, 5 new">15+</div></div>' +
      navTwin +
      '<div data-tracking-duration-id="k9">' +
      '<div><span>a genuine post about nothing at all, quite enough' +
      ' text</span></div>' +
      '<a href="https://m.facebook.com/story.php?story_fbid=9">Full</a></div>' +
      '</div></div>';
    const p2 = new JSDOM('<html><body>' +
      Hnew.compose(twoRowDoc, [keepPostX]) + '</body></html>',
      { url: 'https://m.facebook.com/' }).window.document;
    const c2 = p2.getElementById('__db_chrome');
    const vs2 = p2.querySelector('[data-type="vscroller"]');
    ok('the first row moves, its distinct-badge twin stays hidden (v2 dupe)',
       !!c2 && p2.querySelectorAll('#__db_chrome #navF').length === 1 &&
       p2.querySelectorAll('#__db_chrome #navT').length === 0 &&
       !!vs2 && !!vs2.querySelector('#navT'));
  }

  // ------------------------------------------------ Bug 1: the seats
  // The bug-report-v2 screenshot: "Posts: 16 of 10" while Syncing. Each
  // chunk admission used to recheck room against COMPLETE entries only,
  // so a burst during a slow download all sailed through one gate.
  const post = (n) => ({
    id: 'p' + n, html: '<div>post ' + n + '</div>',
    media: ['https://scontent.fkul5-1.fna.fbcdn.net/v/t39/' + n + '.jpg']
  });
  const mk = (a, b) => Array.from({ length: b - a + 1 },
    (_, i) => post(a + i));

  // OLD: three chunk admissions in one burst, nothing downloaded yet.
  let oldStore = [];
  for (const [a, b] of [[1, 8], [9, 16], [17, 24]]) {
    oldStore = Hold.addItems(oldStore, mk(a, b), 10, 500, 10, () => false);
  }
  ok('HEAD admitted 24 into a 10-seat store during one slow burst',
     oldStore.length === 24, String(oldStore.length));
  ok('and the count read past the target once the media landed',
     Hold.addItems(oldStore, [], 10, 500, 10, () => true).length === 24);

  // NEW: same burst, with those URLs sitting in the queue (as
  // OfflineFeed.prefetch queues them the same moment, in production).
  let newStore = [];
  const queued = new Set();
  for (const [a, b] of [[1, 8], [9, 16], [17, 24]]) {
    for (const q of mk(a, b)) queued.add(q.media[0]);
    newStore = Hnew.addItems(newStore, mk(a, b), 10, 500, 10,
      () => false, queued);
  }
  ok('the fix admits exactly to the seat count during the same burst',
     newStore.length === 10, String(newStore.length));
  ok('so the count can never exceed the target when the media lands',
     Hnew.addItems(newStore, [], 10, 500, 10, () => true).length === 10);

  // Unseating: two downloads fail, the queue empties, room reopens
  // exactly by the failure count - the shelf refills toward target.
  {
    const urls = newStore.map((e) => e.media[0]);
    const have = new Set(urls.slice(0, 8));        // 8 landed, 2 failed
    const seatedStore = Hnew.addItems(newStore, mk(25, 26), 10, 500, 10,
      (u) => have.has(u), new Set());              // empty queue now
    ok('failed downloads unseat, fresh ids refill the gap',
       seatedStore.length === 12 &&
       seatedStore.some((e) => e.id === 'p25') &&
       seatedStore.some((e) => e.id === 'p26'),
       String(seatedStore.length));
  }

  // Round 12 stands: a full shelf of STUCK entries (captured, download
  // dead, nothing queued) holds no seats and never blocks fresh ids.
  {
    const stuck = mk(1, 12);
    const merged = Hnew.addItems(stuck, mk(30, 39), 10, 500, 10,
      () => false, new Set());
    ok('a stuck shelf still yields its seats (round-12 rule intact)',
       merged.filter((e) => e.id >= 'p30').length +
       merged.filter((e) => e.id == 'p30').length >= 10 &&
       merged.length <= 500);
  }

  // Same-id re-capture stays free at full seats - how an expired URL
  // heals without burning a seat for a new id.
  {
    const full = mk(1, 10);
    const recaptured = Hnew.addItems(full, [post(3)], 10, 500, 10,
      () => true, new Set());
    ok('same-id re-capture passes at full seats, and ids stay unique',
       recaptured.length === 10 &&
       recaptured.filter((e) => e.id === 'p3').length === 1);
  }

  // Structure pins, so the claims bind to code and cannot silently rot:
  const vaultSrc = fs.readFileSync(KT('offline/SectionVault.kt'), 'utf8');
  const asmSrc = fs.readFileSync(KT('offline/PageAssembly.kt'), 'utf8');
  const capSrc = fs.readFileSync(KT('utils/OfflineCapture.kt'), 'utf8');
  ok('the vault computes seats from the queue it owns',
     /val inFlight = synchronized\(queue\) \{ HashSet\(queued\) \}/.test(vaultSrc) &&
     /room = hardCap - seated/.test(vaultSrc));
  ok('the heal uses the shared definition with the permalink guard',
     /if \(isTabRowMarkup\(html\) \&\&/.test(vaultSrc) &&
     /NAV_POST_PERMALINK\.containsMatchIn\(html\)\) return true/.test(vaultSrc));
  ok('classify uses the shared definition before the post guard',
     /SectionVault\.isTabRowMarkup\(slice\)/.test(asmSrc) &&
     asmSrc.indexOf('isTabRowMarkup(slice)') <
     asmSrc.indexOf('POST_LINK.containsMatchIn(slice)) return MOVE') ||
     /isTabRowMarkup/.test(asmSrc));
  ok('the capture mirrors the prefix/href tier with its post guard',
     /preNames/.test(capSrc) && /navHrefCount/.test(capSrc));
}

// ======================================================================
// Round 25 - the 17:12 side-by-side: three offline bugs, three proofs.
//
//   A. Offline home parked the nav row ABOVE the wordmark header - the
//      user's own screenshots: online the wordmark sits first and the
//      tab row under it; offline the row sat on top, wordmark below.
//      Mechanism, verbatim in compose(): chrome walked the scroller in
//      DOM order but emitted "tabs + moved" - the row always jumped
//      ahead of everything. On this device the wordmark header is a
//      scroller child too (else the offline page would show the pinned
//      one TWICE, or none - it shows exactly one, moved, after the
//      row), so the inversion put navigation first every time.
//   B. Offline reels never became fullscreen - the next reel's slice
//      always peeked in at the bottom ("niche arekta video er kichu
//      onsho chole asche"). Mechanism: NOTHING offline sizes a saved
//      reel to the frame (online the page's own stylesheet classes do;
//      offline the <link>s are dead), snap CSS only aligns, the pager
//      only scrolls. A 736px card on an 852px frame shows the next
//      card's top 116px at every rest.
//   C. "ekhon to pause o hoi na" - a tap on the playing reel pauses
//      nothing. Mechanism, verbatim in the tap bridge: the guard that
//      protects real buttons (Follow, share, links) returned on ANY
//      [role="button"] ancestor - and on the real captured species the
//      PLAYER ITSELF is one: <div role="button" aria-label="Video
//      player" data-video-id="1452526892980986"> wrapping the <video>
//      (the stored fixture above, from this device's own captures).
//      Every tap on the picture died in that guard; pausing was only
//      ever possible on species whose player carries no button role -
//      which is why "just 1st ta pause hoto" and the rest never did.
console.log('\nRound 25 - online order offline, every reel claims the frame, the player button pauses');
{
  const Hnew = require('./offline_vault_harness.js');
  const cp25 = require('child_process');
  // Old sides pinned by HASH, never HEAD: 43c9649 = v5.2.23, the build
  // whose screenshots this round answers. Reachable in CI (fetch-depth 0).
  const H523 = (() => {
    const oldSrc = cp25.execSync(
      'git show 43c9649:tools/offline_vault_harness.js',
      { cwd: ROOT }).toString();
    const tmp = path.join(ROOT, 'tools', '.harness_523_tmp.js');
    fs.writeFileSync(tmp, oldSrc);
    const m = require('./.harness_523_tmp.js');
    fs.unlinkSync(tmp);
    return m;
  })();
  const asmSrc25 = fs.readFileSync(KT('offline/PageAssembly.kt'), 'utf8');
  const vhSrc25 = fs.readFileSync(KT('utils/VideoHelper.kt'), 'utf8');
  const asmOld25 = cp25.execSync(
    'git show 43c9649:app/src/main/java/com/dustbook/app/offline/' +
    'PageAssembly.kt', { cwd: ROOT }).toString();
  const vhOld25 = cp25.execSync(
    'git show 43c9649:app/src/main/java/com/dustbook/app/utils/' +
    'VideoHelper.kt', { cwd: ROOT }).toString();

  // ---- Bug A fixture: his scroller's own order, from the online shot --
  // wordmark (offset 0), tab row (56, the badge-in-label species his
  // device carries), composer (108), stories tray (168), then posts.
  // The wordmark carries ONE nav-prefix label ("Search") - two would
  // have promoted it to the tab tier and hidden the real row, and the
  // device screenshot shows the row alive, so one it is.
  const wordmarkU =
    '<div id="hdrU" style="margin-top:0px; height:56px;">' +
    '<img alt="facebook"/>' +
    '<div role="button" aria-label="Search">s</div>' +
    '<div>m</div></div>';
  const badgeRowU =
    '<div id="navU" style="margin-top:56px; height:52px;">' +
    '<div role="button" aria-label="Home, 15+">15+</div>' +
    '<div role="button" aria-label="Notifications, 15+ notifications">' +
    '15+</div></div>';
  const composerU =
    '<div id="cmpU" style="margin-top:108px; height:60px;">' +
    '<span>What&#39;s on your mind?</span></div>';
  const trayUU =
    '<div id="tryU" style="margin-top:168px; height:232px;">' +
    '<a href="/stories/11/">Rony</a>' +
    '<a href="/stories/22/">Shuvo</a></div>';
  const postU =
    '<div data-tracking-duration-id="k25">' +
    '<div><span>ANDROID ROOTED USERS PLUS - a genuine post with long' +
    ' enough text here</span></div>' +
    '<a href="https://m.facebook.com/story.php?story_fbid=25">Full</a>' +
    '</div>';
  const orderDoc =
    '<div data-mcomponent="MScreen" class="m bg-s2">' +
    '<div data-type="vscroller" class="m vscroller">' +
    wordmarkU + badgeRowU + composerU + trayUU + postU + '</div></div>';
  const chromeIds = (h) => {
    const d = new JSDOM('<html><body>' + h.compose(orderDoc, [postU]) +
      '</body></html>', { url: 'https://m.facebook.com/' }).window.document;
    const ch = d.getElementById('__db_chrome');
    return ch ? Array.from(ch.children).map((c) => c.id).join(',') : 'none';
  };
  ok('v5.2.23 parked the nav row ABOVE the wordmark (the 17:12 offline shot)',
     chromeIds(H523) === 'navU,hdrU,cmpU,tryU', chromeIds(H523));
  ok('chrome now keeps the online walk order: wordmark, row, composer, tray',
     chromeIds(Hnew) === 'hdrU,navU,cmpU,tryU', chromeIds(Hnew));
  {
    // The first-row cap met the row AFTER the header this whole time:
    // a recycled badge twin still stays hidden, order unbroken.
    const twinU =
      '<div id="navT25" style="margin-top:280px; height:52px;">' +
      '<div role="button" aria-label="Home, 3 new">7</div>' +
      '<div role="button" aria-label="Reels, 9 new">4</div></div>';
    const docT =
      '<div data-mcomponent="MScreen" class="m bg-s2">' +
      '<div data-type="vscroller" class="m vscroller">' +
      wordmarkU + badgeRowU + twinU + composerU + postU + '</div></div>';
    const d = new JSDOM('<html><body>' + Hnew.compose(docT, [postU]) +
      '</body></html>', { url: 'https://m.facebook.com/' }).window.document;
    const ids = Array.from(d.getElementById('__db_chrome').children)
      .map((c) => c.id).join(',');
    ok('after the header: first row wins, its badge twin stays hidden',
       ids === 'hdrU,navU,cmpU' &&
       !!d.querySelector('[data-type="vscroller"] #navT25'), ids);
  }
  ok('compose emits one chrome list in walk order (structure pin)',
     /val seq = chromeUnits/.test(asmSrc25) && /var rowMoved = false/.test(asmSrc25) &&
     !/val seq = tabs \+ moved/.test(asmSrc25));

  // ---- Bug B: nothing sized a saved reel to the frame ------------------
  const tplOf = (src) => src.slice(src.indexOf('val main = """') + 14,
    src.indexOf('""".trimIndent()', src.indexOf('val main = """')));
  const tplNew25 = tplOf(asmSrc25), tplOld25 = tplOf(asmOld25);
  function world25(tpl, viewH, cardH, pad) {
    let html = '<style id="__db_reels_snap">' +
      'html,body{scroll-snap-type:y mandatory!important}' +
      '#__db_cards>*{scroll-snap-align:start!important;' +
      'scroll-snap-margin-top:' + pad + 'px!important}</style>' +
      '<div data-type="vscroller"><div id="__db_cards" data-db-cards>';
    for (let i = 0; i < 3; i++) {
      html += '<div class="rc" data-video-id="r' + i + '"></div>';
    }
    html += '<div id="__db_snap_t" style="height:0"></div></div></div>';
    const dom = new JSDOM(html, { url: 'https://m.facebook.com/',
      runScripts: 'outside-only', pretendToBeVisual: true });
    const w = dom.window;
    let now = 1000; const rafQ = [];
    w.Date.now = () => now;
    w.requestAnimationFrame = (cb) => { rafQ.push(cb); return rafQ.length; };
    w.cancelAnimationFrame = (id) => { if (rafQ[id - 1]) rafQ[id - 1] = null; };
    const box = w.document.querySelector('[data-type="vscroller"]');
    Object.defineProperty(box, 'clientHeight', { value: viewH });
    Object.defineProperty(box, 'scrollHeight', { value: 3 * cardH });
    w.document.querySelectorAll('.rc').forEach((c, i) => {
      c.getBoundingClientRect = () => ({
        top: i * cardH - box.scrollTop,
        bottom: (i + 1) * cardH - box.scrollTop,
        left: 0, right: 300, width: 300, height: cardH });
    });
    w.FBPro = { reportPosition: () => {} };
    w.eval(tpl.split('__SEC__').join('reels'));
    function ev(type, x, y, dt) {
      if (dt) now += dt;
      const e = new w.Event(type, { bubbles: true, cancelable: true });
      e.touches = type === 'touchend' ? [] : [{ clientX: x, clientY: y }];
      e.changedTouches = type === 'touchend'
        ? [{ clientX: x, clientY: y }] : [];
      w.document.querySelector('.rc').dispatchEvent(e);
      return e;
    }
    function pump() {
      let g = 0;
      while (rafQ.length && g++ < 400) {
        now += 20;
        const batch = rafQ.splice(0, rafQ.length);
        for (const cb of batch) if (cb) cb();
      }
    }
    return { w, box, ev, pump };
  }
  {
    // His frame, his reel: an 852px view holding a 736px card, the same
    // shortfall the 17:12:08 screenshot shows (next reel's slice below).
    const P = world25(tplOld25, 852, 736, 0);
    ok('v5.2.23 sized nothing: 116px of the next reel stayed on screen',
       (P.w.document.querySelector('.rc').style.minHeight || '') === '' &&
       852 - 736 === 116);
  }
  {
    const P = world25(tplNew25, 852, 736, 0);
    const cards = P.w.document.querySelectorAll('.rc');
    ok('now every saved reel claims the whole frame at serve (852px)',
       Array.from(cards).every((c) => c.style.minHeight === '852px'),
       cards[0].style.minHeight);
    ok('the zero-height snap sentinel is never stretched',
       (P.w.document.getElementById('__db_snap_t').style.minHeight ||
        '') === '');
    P.ev('touchstart', 100, 800);
    P.ev('touchmove', 100, 710, 200);
    P.ev('touchmove', 100, 620, 200);
    P.ev('touchmove', 100, 500, 200);
    P.ev('touchend', 100, 500, 200);
    P.pump();
    ok('and the one-reel commit is untouched by the sizing',
       P.box.scrollTop === 736, 'final=' + P.box.scrollTop);
  }
  {
    // Facebook's stamped bar offset, when one exists, reserves the top:
    // 852px frame with a 48px bar fits an 804px card, never more.
    const P = world25(tplNew25, 852, 736, 48);
    ok('a stamped bar offset reserves its own rows (852-48=804)',
       P.w.document.querySelector('.rc').style.minHeight === '804px',
       P.w.document.querySelector('.rc').style.minHeight);
  }
  {
    // If the frame cannot be measured at all, fail OPEN: today's
    // behavior, not a guessed zero.
    const P = world25(tplNew25, 0, 736, 0);
    ok('an unmeasurable frame leaves every card exactly as stored',
       (P.w.document.querySelector('.rc').style.minHeight || '') === '');
  }

  // ---- Bug C: the player surface is a button, and taps died there -----
  const assistOf25 = (src) => {
    const vi = src.indexOf('fun getOfflineVideoAssistScript');
    const vs = src.indexOf('"""', vi) + 3;
    return src.slice(vs, src.indexOf('"""', vs));
  };
  function assistWorld25() {
    // The real captured species - the player itself carries the button
    // role (the stored fixture from this device's own captures, above).
    const dom = new JSDOM(
      '<div id="__db_cards" data-db-cards>' +
      '<div class="rc" data-video-id="r0">' +
      '<div role="button" aria-label="Video player"' +
      ' data-video-id="1452526892980986">' +
      '<span class="ovl"></span><video></video></div>' +
      '<div role="button" id="followB">Follow</div>' +
      '<a href="https://m.facebook.com/reel/1452526892980986">HIRA</a>' +
      '</div></div>',
      { url: 'https://m.facebook.com/', runScripts: 'outside-only',
        pretendToBeVisual: true });
    const w = dom.window;
    const v = w.document.querySelector('video');
    let paused = false; const log = [];
    Object.defineProperty(v, 'paused', { get: () => paused });
    v.play = () => { paused = false; log.push('play');
      return { catch() {} }; };
    v.pause = () => { paused = true; log.push('pause'); };
    return { w, v, log, isPaused: () => paused };
  }
  {
    const A = assistWorld25();
    A.w.eval(assistOf25(vhOld25));
    A.w.document.querySelector('.ovl').dispatchEvent(
      new A.w.MouseEvent('click', { bubbles: true, cancelable: true }));
    ok('v5.2.23 ate the tap on the player: guard returned, no pause',
       A.log.join(',') === '' && !A.isPaused(), A.log.join(','));
  }
  {
    const A = assistWorld25();
    A.w.eval(assistOf25(vhSrc25));
    A.w.document.querySelector('.ovl').dispatchEvent(
      new A.w.MouseEvent('click', { bubbles: true, cancelable: true }));
    ok('now the same tap on the player surface pauses the video',
       A.log.join(',') === 'pause' && A.isPaused(), A.log.join(','));
    A.w.document.querySelector('video').dispatchEvent(
      new A.w.MouseEvent('click', { bubbles: true, cancelable: true }));
    ok('and tapping the video itself plays it again (the online toggle)',
       A.log.join(',') === 'pause,play' && !A.isPaused(), A.log.join(','));
  }
  {
    // Real controls keep their own jobs: a button that HOLDS NO player
    // still refuses the bridge, links stay links, the story overlay
    // swallows everything as before.
    const A = assistWorld25();
    A.w.eval(assistOf25(vhSrc25));
    A.w.document.getElementById('followB').dispatchEvent(
      new A.w.MouseEvent('click', { bubbles: true, cancelable: true }));
    A.w.document.querySelector('a[href]').dispatchEvent(
      new A.w.MouseEvent('click', { bubbles: true, cancelable: true }));
    ok('Follow and the author link are never hijacked by the bridge',
       A.log.join(',') === '' && !A.isPaused(), A.log.join(','));
    const ov = A.w.document.createElement('div');
    ov.id = '__db_story_overlay';
    A.w.document.body.appendChild(ov);
    A.w.document.querySelector('.ovl').dispatchEvent(
      new A.w.MouseEvent('click', { bubbles: true, cancelable: true }));
    ok('the story viewer overlay still owns its own taps',
       A.log.join(',') === '', A.log.join(','));
  }
  {
    // Multi-player card INSIDE the button species: v5.2.23's pause-all
    // must survive the widened door.
    const dom = new JSDOM(
      '<div id="__db_cards" data-db-cards><div class="rc">' +
      '<div role="button" aria-label="Video player">' +
      '<video id="va"></video><video id="vb"></video></div></div></div>',
      { url: 'https://m.facebook.com/', runScripts: 'outside-only',
        pretendToBeVisual: true });
    const w = dom.window;
    const log = [];
    for (const id of ['va', 'vb']) {
      const v = w.document.getElementById(id);
      let paused = false;
      Object.defineProperty(v, 'paused', { get: () => paused });
      v.play = () => { paused = false; log.push(id + ':play');
        return { catch() {} }; };
      v.pause = () => { paused = true; log.push(id + ':pause'); };
    }
    w.eval(assistOf25(vhSrc25));
    w.document.getElementById('va').dispatchEvent(
      new w.MouseEvent('click', { bubbles: true, cancelable: true }));
    ok('one tap on the button species pauses EVERY player it holds',
       log.join(',') === 'va:pause,vb:pause', log.join(','));
  }
  ok('the bridge guard spares only controls holding no player (pin)',
     /var rb = t\.closest\('\[role="button"\]'\)/.test(vhSrc25) &&
     /rb\.querySelector\('video'\)/.test(vhSrc25) &&
     vhSrc25.indexOf("t.closest('a,button,[role=\"button\"]") === -1);
}

// ======================================================================
// Round 26 - the tap dead-zone (device verdict: "tap e kichu-i hoy na").
//
// His tap-to-pause produced NOTHING on device, while every lab tap
// passed. The mechanism, verbatim in the pager's touchend: the moment
// the 7px drag lock engages, the browser's native click is
// preventDefault'ed - and the rescue that re-delivers it fired ONLY
// when `peak <= 16`. A budget touch panel emits 5-15px of jitter on a
// "still" press, so on his phone nearly every real tap became a
// "drag", and any tap whose furthest wander crossed 16px lost its
// click FOREVER: no commit, no click, no response - exactly his words.
// The first reel paused for him on steady days (jitter staying under
// 16); the rest never did. Clean 3px lab taps could never see this.
//
// The distinguisher a tap actually owns is where the finger ENDED, not
// how far it trembled mid-press: rescue when the release point is
// still within the slop of the touchstart point, and cap mid-gesture
// travel at CANCEL_TRAVEL so a deliberate drag that returns to its
// origin never becomes a fake tap (the round-13/16 lesson he taught:
// no artificial gesture outcomes).
console.log('\nRound 26 - a trembling tap is still a tap (the dead-zone)');
{
  const cp26 = require('child_process');
  const tplOf26 = (src) => src.slice(src.indexOf('val main = """') + 14,
    src.indexOf('""".trimIndent()', src.indexOf('val main = """')));
  const tplNew26 = tplOf26(
    fs.readFileSync(KT('offline/PageAssembly.kt'), 'utf8'));
  // Old side pinned at 8ab929a (v5.2.24) - immutable, never HEAD.
  const tplOld26 = tplOf26(cp26.execSync(
    'git show 8ab929a:app/src/main/java/com/dustbook/app/offline/' +
    'PageAssembly.kt', { cwd: ROOT }).toString());

  function world26(tpl) {
    let html = '<style id="__db_reels_snap">' +
      'html,body{scroll-snap-type:y mandatory!important}' +
      '#__db_cards>*{scroll-snap-align:start!important;' +
      'scroll-snap-margin-top:128px!important}</style>' +
      '<div data-type="vscroller"><div id="__db_cards" data-db-cards>' +
      '<div class="rc" data-video-id="r0"><video></video></div>' +
      '<div class="rc" data-video-id="r1"></div>' +
      '<div class="rc" data-video-id="r2"></div>' +
      '<div id="__db_snap_t" style="height:0"></div></div></div>';
    const dom = new JSDOM(html, { url: 'https://m.facebook.com/',
      runScripts: 'outside-only', pretendToBeVisual: true });
    const w = dom.window;
    let now = 1000; const rafQ = [];
    w.Date.now = () => now;
    w.requestAnimationFrame = (cb) => { rafQ.push(cb); return rafQ.length; };
    w.cancelAnimationFrame = (id) => { if (rafQ[id - 1]) rafQ[id - 1] = null; };
    const box = w.document.querySelector('[data-type="vscroller"]');
    Object.defineProperty(box, 'clientHeight', { value: 1000 });
    Object.defineProperty(box, 'scrollHeight', { value: 3000 });
    w.document.querySelectorAll('.rc').forEach((c, i) => {
      c.getBoundingClientRect = () => ({
        top: i * 1000 - box.scrollTop,
        bottom: (i + 1) * 1000 - box.scrollTop,
        left: 0, right: 300, width: 300, height: 1000 });
    });
    w.FBPro = { reportPosition: () => {} };
    w.eval(tpl.split('__SEC__').join('reels'));
    // the tap bridge, verbatim, on top of the same world
    const vhSrc = fs.readFileSync(KT('utils/VideoHelper.kt'), 'utf8');
    const vi = vhSrc.indexOf('fun getOfflineVideoAssistScript');
    const vs = vhSrc.indexOf('"""', vi) + 3;
    w.eval(vhSrc.slice(vs, vhSrc.indexOf('"""', vs)));
    const v = w.document.querySelector('video');
    const log = [];
    let paused = false;
    Object.defineProperty(v, 'paused', { get: () => paused });
    v.play = () => { paused = false; log.push('play');
      return { catch() {} }; };
    v.pause = () => { paused = true; log.push('pause'); };
    paused = false;                 // the reel is PLAYING on screen
    function ev(type, x, y, dt) {
      if (dt) now += dt;
      const e = new w.Event(type, { bubbles: true, cancelable: true });
      e.touches = type === 'touchend' ? [] : [{ clientX: x, clientY: y }];
      e.changedTouches = type === 'touchend'
        ? [{ clientX: x, clientY: y }] : [];
      w.document.querySelector('.rc').dispatchEvent(e);
      return e;
    }
    function pump() {
      let g = 0;
      while (rafQ.length && g++ < 400) {
        now += 20;
        const batch = rafQ.splice(0, rafQ.length);
        for (const cb of batch) if (cb) cb();
      }
    }
    return { w, box, ev, pump, log, v };
  }

  // THE DEAD ZONE, as the panel produces it: a pressed finger that
  // wanders 30px mid-tap but is released 4px from where it landed.
  {
    const P = world26(tplOld26);
    P.ev('touchstart', 100, 600);
    P.ev('touchmove', 100, 585, 200);   // 7px lock engages here, peak 15
    P.ev('touchmove', 100, 570, 200);   // trembles out to 30, peak 30
    P.ev('touchmove', 100, 605, 200);   // and settles back
    P.ev('touchend', 100, 604, 80);     // released near the start point
    P.pump();
    ok('v5.2.24 dead-zone: the 30px-tremor tap dies with zero response',
       P.log.join(',') === '' && P.box.scrollTop === 0,
       P.log.join(',') + ' @' + P.box.scrollTop);
  }
  {
    const P = world26(tplNew26);
    P.ev('touchstart', 100, 600);
    P.ev('touchmove', 100, 585, 200);
    P.ev('touchmove', 100, 570, 200);
    P.ev('touchmove', 100, 605, 200);
    P.ev('touchend', 100, 604, 80);
    P.pump();
    ok('the ended-where-it-began tremor is a tap again: the reel pauses',
       P.log.join(',') === 'pause', P.log.join(','));
  }
  {
    // A decisive little drag is NOT a tap: released 60px from origin.
    const P = world26(tplNew26);
    P.ev('touchstart', 100, 600);
    P.ev('touchmove', 100, 555, 200);
    P.ev('touchend', 100, 540, 200);
    P.pump();
    ok('released far from the start: no fake click, card snaps home',
       P.log.join(',') === '' && P.box.scrollTop === 0,
       P.log.join(',') + ' @' + P.box.scrollTop);
  }
  {
    // A deliberate cancel-drag is NOT a tap either: out 120px and back,
    // past the cancel band, released on the very spot it began.
    const P = world26(tplNew26);
    P.ev('touchstart', 100, 600);
    P.ev('touchmove', 100, 500, 100);
    P.ev('touchmove', 100, 480, 100);   // out 120
    P.ev('touchmove', 100, 596, 100);   // back home
    P.ev('touchend', 100, 598, 40);     // released at the origin point
    P.pump();
    ok('a drag that returns to its origin never becomes a click',
       P.log.join(',') === '' && P.box.scrollTop === 0,
       P.log.join(',') + ' @' + P.box.scrollTop);
  }
  {
    // Parity: the quiet 10px wobble from round 20 still rescues once.
    const P = world26(tplNew26);
    P.ev('touchstart', 100, 600);
    P.ev('touchmove', 100, 593, 200);
    P.ev('touchend', 100, 592, 200);
    P.pump();
    ok('the round-20 wobble still pauses exactly once',
       P.log.join(',') === 'pause', P.log.join(','));
  }
  {
    // And a true fling-commit never manufactures a tap on arrival:
    // the pause below is the commit's own departed-reel silence
    // (v5.2.23), never a bridge toggle - there is no 'play' after it.
    const P = world26(tplNew26);
    P.ev('touchstart', 100, 600);
    P.ev('touchmove', 100, 300, 12);
    P.ev('touchend', 100, 140, 12);
    P.pump();
    ok('a fling commits and silences the departed reel, not a tap',
       P.log.join(',') === 'pause' && P.box.scrollTop === 1000 - 128,
       P.log.join(',') + ' @' + P.box.scrollTop);
  }
  const asmSrc26 = fs.readFileSync(KT('offline/PageAssembly.kt'), 'utf8');
  ok('the rescue keys on the RELEASE point, not mid-tap tremor (pin)',
     /CANCEL_TRAVEL/.test(asmSrc26) &&
     /endNear/.test(asmSrc26) &&
     !/intent === startIdx && peak <= TAP_SLOP/.test(asmSrc26));
}

// ======================================================================
// Round 27 - the tap thief, in served order: OfflineNav vs the player.
//
// Device truth after rounds 24-26 (his words: "reels play te ektai
// problem just pause hoi na tap korleo"). The missing piece was never
// the bridge and never the pager rescue: it is OfflineNav, served
// BEFORE the bridge, listening in the CAPTURE phase, and matching
// aria-labels by PREFIX against route labels that include "video"
// (reels/watch tabs). The player's own wrapper carries
// aria-label="Video player" - "video player" STARTS WITH "video" -
// so every tap on the picture was claimed as "navigate to reels",
// preventDefault+stopPropagation, and the tap bridge never heard it.
// The first reel paused for him on species whose player label does
// not start with a route word; the rest never could. Harness history:
// the bridge was always tested WITHOUT the served script stack in
// front of it - the gap, honestly owned.
//
// The claim boundary is where navigation actually lives: the moved
// chrome (tab bar, tray teasers). Saved content is inside #__db_cards,
// and from a card a tap is never navigation - that kills the whole
// class (the "video"-prefixed player today, a "Notifications are off"
// banner inside a post tomorrow) while the real bar keeps routing.
console.log('\nRound 27 - navigation stops at the cards boundary');
{
  const cp27 = require('child_process');
  const navOf = (src) => {
    const i = src.indexOf('fun script(');
    const a = src.indexOf('return """', i) + 'return """'.length;
    const b = src.indexOf('""".trimIndent()', a);
    let s = src.slice(a, b);
    // Rebuild the two template slots from the production ROUTES const,
    // so the test can never drift from the shipped label lists.
    const rm = /"(\w+)" to listOf\(([^)]*)\)/g;
    const routes = [];
    const rc = src.slice(src.indexOf('private val ROUTES'),
                         src.indexOf('fun script'));
    let m;
    while ((m = rm.exec(rc)) !== null) {
      const labels = m[2].match(/"([^"]*)"/g).join(',');
      routes.push('{s:"' + m[1] + '",l:[' + labels + ']}');
    }
    s = s.replace('[$saved]', '["home","reels","stories","watch"]')
         .replace('[$routes]', '[' + routes.join(',') + ']');
    return s;
  };
  const assistOf27 = (src) => {
    const vi = src.indexOf('fun getOfflineVideoAssistScript');
    const vs = src.indexOf('"""', vi) + 3;
    return src.slice(vs, src.indexOf('"""', vs));
  };
  const navKtNew = fs.readFileSync(KT('utils/OfflineNav.kt'), 'utf8');
  const navKtOld = cp27.execSync(
    'git show c60e957:app/src/main/java/com/dustbook/app/utils/' +
    'OfflineNav.kt', { cwd: ROOT }).toString();
  const vhSrc27 = fs.readFileSync(KT('utils/VideoHelper.kt'), 'utf8');

  function navAssistWorld(navKt) {
    // The served stack, in served order: OfflineNav first, then the
    // bridge, his real species inside the cards, the real bar in chrome.
    const dom = new JSDOM(
      '<div id="__db_chrome"><div id="bar">' +
      '<div role="button" aria-label="Reels, 3 new">3</div></div></div>' +
      '<div id="__db_cards" data-db-cards>' +
      '<div class="rc" data-video-id="r0">' +
      '<div role="button" aria-label="Video player"' +
      ' data-video-id="1452526892980986">' +
      '<span class="ovl"></span><video></video></div>' +
      '<div id="nb" aria-label="Notifications are off for this post">' +
      'muted</div>' +
      '</div></div>',
      { url: 'https://m.facebook.com/reel/', runScripts: 'outside-only',
        pretendToBeVisual: true });
    const w = dom.window;
    const navCalls = [], log = [];
    w.FBPro = { onOfflineNav: (...a) => navCalls.push(['NAV', ...a]),
                onOfflineNavMissing: (...a) => navCalls.push(['MISS', ...a]),
                markViewed: () => {}, reportPosition: () => {} };
    const v = w.document.querySelector('video');
    let paused = false;
    Object.defineProperty(v, 'paused', { get: () => paused });
    v.play = () => { paused = false; log.push('play');
      return { catch() {} }; };
    v.pause = () => { paused = true; log.push('pause'); };
    paused = false;                 // the reel is PLAYING on screen
    w.eval(navOf(navKt));           // served FIRST, capture phase
    w.eval(assistOf27(vhSrc27));    // served SECOND
    return { w, v, log, navCalls };
  }

  {
    const A = navAssistWorld(navKtOld);
    // The honest old-side chain, as the code actually runs it: the tap
    // is claimed (defaultPrevented) and handed to FBPro.onOfflineNav -
    // whose Kotlin body is binding.webView.loadUrl(url) with no same-URL
    // guard, i.e. a FULL REBUILD of the page on every tap on the player.
    // (stopPropagation is not a same-node wall - the bridge still runs
    // in jsdom, and on the dead document; the toggle dies with it.
    // That is "tap korleo pause hoi na": every tap IS a silent reload.)
    const ev0 = new A.w.MouseEvent('click',
      { bubbles: true, cancelable: true });
    A.w.document.querySelector('.ovl').dispatchEvent(ev0);
    ok('v5.2.25: every player tap was claimed into a full-page loadUrl' +
       ' ("Video player" starts with the reels route label "video")',
       ev0.defaultPrevented && A.navCalls.length === 1 &&
       A.navCalls[0][0] === 'NAV' && A.navCalls[0][1] === 'reels' &&
       A.navCalls[0][2] === 'https://m.facebook.com/reel/',
       JSON.stringify(A.navCalls));
  }
  {
    const A = navAssistWorld(navKtNew);
    A.w.document.querySelector('.ovl').dispatchEvent(
      new A.w.MouseEvent('click', { bubbles: true, cancelable: true }));
    ok('now the player tap reaches the bridge: the reel pauses',
       A.navCalls.length === 0 && A.log.join(',') === 'pause',
       JSON.stringify(A.navCalls) + ' / ' + A.log.join(','));
    A.w.document.querySelector('.ovl').dispatchEvent(
      new A.w.MouseEvent('click', { bubbles: true, cancelable: true }));
    ok('and a second tap plays it again, the online toggle',
       A.log.join(',') === 'pause,play', A.log.join(','));
  }
  {
    // The whole class: content carrying a route-PREFIXED label inside
    // a card must not be claimed as navigation either (it was, before).
    // The distinguisher is the route call - an in-card tap may also
    // hit the bridge's card-surface toggle, which preventDefaults by
    // design, so defaultPrevented alone cannot tell the two apart.
    const O = navAssistWorld(navKtOld);
    O.w.document.getElementById('nb').dispatchEvent(
      new O.w.MouseEvent('click', { bubbles: true, cancelable: true }));
    const N = navAssistWorld(navKtNew);
    N.w.document.getElementById('nb').dispatchEvent(
      new N.w.MouseEvent('click', { bubbles: true, cancelable: true }));
    ok('a nav-prefixed label inside a card was claimed before,' +
       ' and is content now',
       O.navCalls.length === 1 && N.navCalls.length === 0,
       'old=' + JSON.stringify(O.navCalls) +
       ' new=' + JSON.stringify(N.navCalls));
  }
  {
    // The real bar is untouched: the prefix-labeled tab still routes.
    const B = navAssistWorld(navKtNew);
    B.w.document.querySelector('#bar [role="button"]').dispatchEvent(
      new B.w.MouseEvent('click', { bubbles: true, cancelable: true }));
    ok('the tab bar still routes (Reels, 3 new -> reels)',
       B.navCalls.length === 1 && B.navCalls[0][0] === 'NAV' &&
       B.navCalls[0][1] === 'reels',
       JSON.stringify(B.navCalls));
  }
  ok('navigation is refused from saved content (structure pin)',
     /el\.closest\('#__db_cards'\)\) return/.test(navKtNew));
}

console.log('\n' + pass + ' passed, ' + fail + ' failed');

process.exit(fail ? 1 : 0);

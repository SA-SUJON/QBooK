#!/usr/bin/env node
/**
 * Offline navigation and content injection.
 *
 * The reported state: offline, the header and tab bar render but the feed is a
 * grey skeleton and tapping Reels does nothing.
 *
 * Two causes. Facebook's controls carry no href - they are data-action-id
 * driven and resolved over the network - so offline a tap resolves nothing.
 * And what the server renders into the feed area is a placeholder that their
 * own JS fills over the network, so the stored copy stores the placeholder.
 *
 * The rule these tests exist to enforce is the one that was asked for: nothing
 * may be duplicated, and nothing may differ from online.
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

function raw(file, marker) {
  const src = fs.readFileSync(file, 'utf8');
  const i = src.indexOf(marker);
  const start = src.indexOf('"""', i) + 3;
  const end = src.indexOf('"""', start);
  return src.slice(start, end);
}

/** The tab bar exactly as the device captures show it: no href anywhere. */
const BAR = `
  <div role="button" tabindex="0" aria-label="Home" data-action-id="1"
       data-mcomponent="MContainer">Home</div>
  <div role="button" tabindex="0" aria-label="Reels" data-action-id="32761"
       data-mcomponent="MContainer">Reels</div>
  <div role="button" tabindex="0" aria-label="Marketplace" data-action-id="9"
       data-mcomponent="MContainer">Marketplace</div>`;

function navScript(saved) {
  return raw(KT('utils/OfflineNav.kt'), 'fun script(')
    .replace('$saved', saved.map((s) => `"${s}"`).join(','))
    .replace('$routes', fs.readFileSync(KT('utils/OfflineNav.kt'), 'utf8')
      .match(/"(\w+)" to listOf\(([^)]*)\)/g)
      .map((m) => {
        const s = m.match(/"(\w+)" to/)[1];
        const l = m.match(/listOf\(([^)]*)\)/)[1];
        return `{s:"${s}",l:[${l}]}`;
      }).join(','));
}

console.log('\nNavigation uses the buttons already there');
{
  const dom = new JSDOM(`<body>${BAR}<div data-type="vscroller"></div></body>`,
    { runScripts: 'outside-only', pretendToBeVisual: true,
      url: 'https://m.facebook.com/' });

  // Navigation is handed to the app rather than driven from the page.
  let navigated = null;
  dom.window.FBPro = { onOfflineNav: (screen, url) => { navigated = url; } };

  const before = dom.window.document.querySelectorAll('[role="button"]').length;
  dom.window.eval(navScript(['home', 'reels']));
  const after = dom.window.document.querySelectorAll('[role="button"]').length;

  // The explicit requirement: no second set of controls.
  ok('adds no controls of its own', before === after, before + ' -> ' + after);

  const reels = dom.window.document.querySelector('[aria-label="Reels"]');
  reels.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
  ok('tapping Reels goes to the stored reels screen',
     navigated === 'https://m.facebook.com/reel/', String(navigated));
}

console.log('\nA tab with nothing stored');
{
  const dom = new JSDOM(`<body>${BAR}</body>`,
    { runScripts: 'outside-only', pretendToBeVisual: true,
      url: 'https://m.facebook.com/' });
  let missing = null;
  dom.window.FBPro = { onOfflineNavMissing: (s) => { missing = s; } };
  dom.window.eval(navScript(['home']));

  const mk = dom.window.document.querySelector('[aria-label="Marketplace"]');
  // Not dimmed any more: a faded tab is a visible difference from online.
  ok('is left looking exactly as it does online',
     !mk.getAttribute('style'), mk.getAttribute('style'));

  mk.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
  ok('and the tap is handled, not passed to a dead action id',
     missing === 'marketplace', String(missing));

  // Nothing is hidden or removed: the bar keeps the shape it has online.
  ok('the control is still visible', mk.style.display !== 'none');
}

console.log('\nSaved content is IN the page, and cannot duplicate');
{
  // Delivery is now static: the served document IS the page, so nothing
  // can run twice and there is no runtime script left to misbehave.
  const H = require('./offline_vault_harness.js');
  const doc = '<div data-type="vscroller">' +
      '<div class="skeleton"></div><div class="skeleton"></div>' +
    '</div>';
  const page = '<html><body>' +
    H.compose(doc, ['<div class="dbcard">post</div>']) + '</body></html>';

  const dom = new JSDOM(page, { runScripts: 'dangerously',
    pretendToBeVisual: true, url: 'https://m.facebook.com/' });
  const d = dom.window.document;
  const box = d.querySelector('[data-type="vscroller"]');
  const holder = d.querySelector('#__db_cards');

  ok('the cards are in the document itself',
     d.querySelectorAll('.dbcard').length === 1);
  ok('inside the real feed container', !!box && box.contains(holder));
  ok('ahead of what the server left behind',
     // two styles precede it now: the layout reset and the hide rule
     !!holder && holder.parentElement === box &&
     [...box.children].indexOf(holder) === 2);
  ok('the server leftovers are only hidden, by one stylesheet',
     d.querySelectorAll('.skeleton').length === 2 &&
     d.getElementById('__db_hide_old') !== null);
  ok('the hiding rule is scoped to that container',
     (d.getElementById('__db_hide_old') || {}).parentElement === box);

  // Serving is one string: asking for the page again returns the same
  // page. There is no script that could add the cards a second time.
  const again = new JSDOM(page, { runScripts: 'dangerously',
    pretendToBeVisual: true, url: 'https://m.facebook.com/' });
  ok('a second serve is the same page, nothing doubles',
     again.window.document.querySelectorAll('.dbcard').length === 1 &&
     again.window.document.querySelectorAll('[data-db-cards]').length === 1);
}

console.log('\nNone of this can reach a live page');
{
  const docs = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');
  // serve() only ever returns a document read from disk.
  ok('injection happens only in the stored-document path',
     /fun serve[\s\S]*?OfflineNav\.script/.test(docs));
  ok('and only for the main frame',
     /fun serve[\s\S]{0,200}isForMainFrame/.test(docs));

  const main = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  ok('the stored document is only served while offline',
     /!isOnline[\s\S]{0,400}OfflineDocs\.serve/.test(main));
  ok('nothing is injected into live pages',
     !/evaluateJavascript\([\s\S]{0,40}(OfflineNav|OfflineInject)/.test(main));
}

console.log('\nThere is something to store in the first place');
{
  const prefs = fs.readFileSync(KT('utils/Prefs.kt'), 'utf8');
  // This was the root cause of the empty feed: saving it defaulted to off,
  // so the home screen never had any content on disk at all.
  ok('the home feed is saved by default',
     /KEY_OFFLINE_FEED, true/.test(prefs));
  ok('reels are saved by default',
     /KEY_OFFLINE_REELS, true/.test(prefs));

  const xml = fs.readFileSync(
    path.join(ROOT, 'app/src/main/res/xml/settings_offline.xml'), 'utf8');
  const block = xml.slice(xml.indexOf('offline_feed'));
  ok('and the settings screen agrees',
     /defaultValue="true"/.test(block.slice(0, 300)));
}

// ---------------------------------------- Facebook's own markup is what is saved
console.log("\nSaved cards are Facebook's own markup");
{
  const cap = raw(KT('utils/OfflineCapture.kt'), 'fun script(')
    .replace('$reelTarget', '50')
    .replace('$MAX_CARD_CHARS', '120000')
    .replace('${knownIds.asJsSet()}', '')
    .replace(/\$\{if \(syncMode\)[^}]*\}/, 'false');

  // A story card with the controls that kept going missing from rebuilt cards.
  const dom = new JSDOM(
    `<body><div data-type="vscroller">
       <div id="composer">
         <div role="textbox">What's on your mind?</div><span>Photo</span>
       </div>
       <div id="post" data-tracking-duration-id="14">
         <div data-mcomponent="TextArea"><span>Real Person</span></div>
         <img src="https://scontent.fcgp1-1.fbcdn.net/photo.jpg">
         <div data-mcomponent="TextArea"><span>a real caption</span></div>
         <div role="button" aria-label="Like">Like</div>
         <div role="button" aria-label="Comment">Comment</div>
         <div role="button" aria-label="Share">Share</div>
         <style>.otf-748866::after{content:"";z-index:-1}</style>
       </div>
     </div></body>`,
    { runScripts: 'outside-only', pretendToBeVisual: true,
      url: 'https://m.facebook.com/' });

  let items = [];
  dom.window.FBPro = {
    onOfflineItems: (s, json) => { items = JSON.parse(json); }
  };
  dom.window.eval(cap);
  Object.defineProperty(dom.window.document, 'visibilityState',
    { value: 'hidden', configurable: true });
  dom.window.document.dispatchEvent(new dom.window.Event('visibilitychange'));

  ok('a card is saved', items.length === 1, String(items.length));
  if (items.length) {
    const h = items[0].h;
    // The whole point: these are Facebook's nodes, so the buttons come with
    // them. Every earlier version rebuilt a card and lost these.
    ok('Like is kept', /aria-label="Like"/.test(h));
    ok('Comment is kept', /aria-label="Comment"/.test(h));
    ok('Share is kept', /aria-label="Share"/.test(h));
    ok('the author is kept', /Real Person/.test(h));
    ok('the caption is kept', /a real caption/.test(h));
    ok('the media is listed for download',
       items[0].m.indexOf('https://scontent.fcgp1-1.fbcdn.net/photo.jpg') >= 0);
    ok('it has a stable id', !!items[0].id, items[0].id);
    ok('nothing is invented - no emoji stand-ins',
       !/&#128077;|&#128172;/.test(h));
  }
  ok('the composer is not saved as a post',
     !items.some((i) => /what's on your mind/i.test(i.h || '')));
}

console.log('\nStories are captured from the story screen');
{
  const cap = raw(KT('utils/OfflineCapture.kt'), 'fun script(')
    .replace('$reelTarget', '50')
    .replace('$MAX_CARD_CHARS', '120000')
    .replace('${knownIds.asJsSet()}', '')
    .replace(/\$\{if \(syncMode\)[^}]*\}/, 'false');

  // A story is one full screen with its own controls - not a list, which is
  // why walking the feed container saved none of them.
  const dom = new JSDOM(
    `<body><div data-mcomponent="MScreen">
       <div data-mcomponent="TextArea"><span>Alif BinTay</span></div>
       <video src="https://video.fcgp1-1.fbcdn.net/story.mp4"></video>
       <div role="button" aria-label="Send message">Send message</div>
     </div></body>`,
    { runScripts: 'outside-only', pretendToBeVisual: true,
      url: 'https://m.facebook.com/stories/1234/' });

  let got = null;
  dom.window.FBPro = {
    onOfflineItems: (s, json) => { got = { s, items: JSON.parse(json) }; }
  };
  dom.window.eval(cap);
  Object.defineProperty(dom.window.document, 'visibilityState',
    { value: 'hidden', configurable: true });
  dom.window.document.dispatchEvent(new dom.window.Event('visibilitychange'));

  ok('a story is captured', got !== null && got.items.length === 1,
     JSON.stringify(got && got.items.length));
  if (got && got.items.length) {
    ok('into the stories section', got.s === 'stories', got.s);
    ok('with the whole screen, controls included',
       /aria-label="Send message"/.test(got.items[0].h));
    ok('and its video queued for download',
       got.items[0].m.indexOf('https://video.fcgp1-1.fbcdn.net/story.mp4') >= 0);
  }
}

console.log('\nNothing is reconstructed');
{
  const feed = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');
  const vault = fs.readFileSync(KT('offline/SectionVault.kt'), 'utf8');
  // The model is markup, not fields.
  ok('an item is markup plus its media',
     /val html: String/.test(feed) && /val media: List<String>/.test(feed));
  ok('no field-by-field model remains',
     !/val author: String/.test(feed) && !/val likes: String/.test(feed));
  ok('cards are returned as saved, from the same list that is counted',
     /fun cards\(\): List<String> = completeItems\(\)\.map \{ it\.html \}/
       .test(vault) &&
     /fun cardMarkupList\(section: String\)[\s\S]{0,120}vault\(section\)\?\.cards\(\)/
       .test(feed));
  ok('no card is drawn by us', !/dbcard|dbhead|dbactions/.test(feed));
  ok('the self-made offline screen is gone', !/fun renderPage/.test(feed));

  const assembly = fs.readFileSync(KT('offline/PageAssembly.kt'), 'utf8');
  {
    // Same rule as the pipeline pins: the only declarations aimed INSIDE
    // the cards holder are gesture-only (dead snap pager clean-up); the
    // single snap-type lift sits on html,body; siblings and chrome are
    // never touch-targets for styling. Nothing about how a card looks
    // may come from us.
    const allowed = new Set(['scroll-snap-align', 'touch-action',
      'scroll-snap-margin-top', 'scroll-snap-stop']);
    const cardRules = [...assembly.matchAll(/#__db_cards[\s>][^{]*\{([^}]*)\}/g)]
      .map(m => m[1]);
    ok('injected markup is not restyled',
       cardRules.length > 0 &&
       cardRules.every(body => body.replace(/["+\s]/g, '').split(';')
         .filter(Boolean)
         .every(d => allowed.has(d.split(':')[0]))) &&
       (assembly.match(/scroll-snap-type/g) || []).length === 2 &&
       // Round 22: the reels snap is y proximity, not y mandatory.
       /scroll-snap-type:y proximity!important/.test(assembly) &&
       /html,body\{[^}]*scroll-snap-type:none!important/.test(assembly) &&
       !/db_chrome\b[^{]*\{[^}]*scroll-snap-align/.test(assembly) &&
       // Round 22: stop:always is no longer used anywhere.
       !/scroll-snap-stop:always!important/.test(assembly));
  }
}

console.log('\nThe settings screen is the six things asked for');
{
  const xml = fs.readFileSync(
    path.join(ROOT, 'app/src/main/res/xml/settings_offline.xml'), 'utf8');
  const keys = [...xml.matchAll(/android:key="([^"]+)"/g)].map((m) => m[1]);

  ok('keep reels', keys.includes('offline_reels'));
  ok('keep home feed', keys.includes('offline_feed'));
  ok('keep stories', keys.includes('offline_stories'));
  ok('how many reels to download', keys.includes('offline_reel_count'));
  ok('how many posts to download', keys.includes('offline_post_count'));
  ok('a count of what is saved', keys.includes('offline_status'));
  ok('clear saved content', keys.includes('clear_offline'));
  ok('which networks may be used', keys.includes('offline_network'));
  ok('and nothing else', keys.length === 8, keys.join(','));
  ok('the reel count follows the reels switch',
     /offline_reel_count[\s\S]{0,400}dependency="offline_reels"/.test(xml));
  ok('the post count follows the feed switch',
     /offline_post_count[\s\S]{0,400}dependency="offline_feed"/.test(xml));

  const strings = fs.readFileSync(
    path.join(ROOT, 'app/src/main/res/values/strings.xml'), 'utf8');
  ok('the post count offers real choices with its default among them',
     /<string-array name="post_count_labels">/.test(strings) &&
     /<string-array name="post_count_values">/.test(strings) &&
     /<item>50<\/item>/.test(strings.slice(
       strings.indexOf('post_count_values'))));

  const prefsSrc = fs.readFileSync(KT('utils/Prefs.kt'), 'utf8');
  ok('the post target is clamped and defaults to 50',
     /KEY_OFFLINE_POST_COUNT, null\) \?: return 50/.test(prefsSrc) &&
     /coerceIn\(10, 300\) \?: 50/.test(prefsSrc));

  const settings = fs.readFileSync(KT('ui/SettingsActivity.kt'), 'utf8');
  ok('reels are counted against the target',
     /offlineReelTarget/.test(settings));
  // Round-18 rule: bind the behaviour (the Posts row is fed by
  // offlinePostTarget), not one spelling of it - the threading fix hoists
  // the read into a local so a background thread never touches Prefs.
  ok('posts are counted against their own target now',
     /offlinePostTarget/.test(settings) &&
     (/Posts: " \+ fc \+ " of " \+ p\.offlinePostTarget/.test(settings) ||
      (/val postTarget = p\.offlinePostTarget/.test(settings) &&
       /Posts: " \+ fc \+ " of " \+ postTarget/.test(settings))));
  ok('posts and stories are counted too',
     /SECTION_FEED/.test(settings) && /SECTION_STORIES/.test(settings));
}

console.log('\nSaved content refreshes when the connection returns');
{
  const main = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  ok('a sync runs once a page settles', /maybeSyncOffline/.test(main));

  const sync = fs.readFileSync(KT('utils/OfflineSync.kt'), 'utf8');
  const feed = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');
  ok('all three sections are queued',
     /SECTION_REELS/.test(main) || /SECTION_REELS/.test(sync) || /SECTION_REELS/.test(feed));

  ok('sections run one at a time', /fun runAll/.test(sync));
  ok('and it is throttled, not constant', /MIN_INTERVAL_MS/.test(sync));
}

// ------------------------------------------------------- downloading is automatic
console.log('\nDownloading does not wait for the user');
{
  const main = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
  const sync = fs.readFileSync(KT('utils/OfflineSync.kt'), 'utf8');
  const feed = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');

  // Opening the app is enough: nothing has to be watched or scrolled past
  // for content to be saved.
  ok('a pass starts when the app comes to the front',
     /onResume[\s\S]{0,2000}maybeSyncOffline/.test(main));
  // The requirement is that reconnecting resumes collecting. Which engine
  // does it is not the point -- BackgroundSyncManager owns that now, and
  // OfflineManager is no longer started alongside it.
  ok('and again when the connection returns',
     /onAvailable[\s\S]{0,1500}BackgroundSyncManager\.onNetworkRestored/.test(main));
  ok('only one collecting engine is started',
     !/OfflineManager\.startProactivePreparation/.test(main) &&
     !/OfflineManager\.onNetworkRestored/.test(main));

  // One pass stops when the page runs out of cards, which is far short of the
  // target. It used to give up there and wait fifteen minutes.
  ok('it keeps going until the target is met',
     /MAX_ROUNDS/.test(sync) && /rounds\+\+/.test(sync));
  ok('with a ceiling, so it cannot loop forever',
     /rounds < MAX_ROUNDS/.test(sync));
  ok('the retry gap is short, not a quarter of an hour',
     /MIN_INTERVAL_MS = 90_000L/.test(sync));

  // Downloads were dropped, not queued, so everything after the first batch
  // was silently thrown away. Every section vault runs one worker of its own.
  const vault = fs.readFileSync(KT('offline/SectionVault.kt'), 'utf8');
  ok('media requests are queued, never dropped',
     /queued\.add\(u\)/.test(vault) && /queue\.add\(u\)/.test(vault));
  ok('a single worker drains the queue, through the serial scheduler',
     /internal fun startDrain\(\)/.test(vault));
  ok('work added late is not stranded while the slot is still held',
     /if \(more && enabled && writeEnabled && downloadAllowed\(\) &&[\s\S]{0,250}startDrain\(\)/.test(vault));
}

console.log('\nOffline never shows the browser error page');
{
  const main = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');

  ok('only a stored Facebook page counts as offline content',
     /fun hasAnythingOffline[\s\S]{0,400}savedScreens\(\)\.isNotEmpty\(\)/.test(main));
  ok('the error page is skipped when there is content',
     /!isOnline && hasAnythingOffline\(\)[\s\S]{0,120}showSavedContent/.test(main));

  // Sending the user to the home URL when only reels were stored produced the
  // error page again, because nothing answered for it.
  ok('it opens a screen that will actually answer',
     /saved\.contains\("reels"\)/.test(main) &&
     /saved\.contains\("stories"\)/.test(main));
  ok('and falls back to whatever is stored',
     /OfflineDocs\.urlFor\(saved\.first\(\)\)/.test(main));
}

console.log('\nThe saved count is always visible');
{
  const settings = fs.readFileSync(KT('ui/SettingsActivity.kt'), 'utf8');

  // It used to be written only from a change listener, so the row was blank
  // until something was toggled at random.
  ok('filled in when the screen opens',
     /setPreferencesFromResource\(res, rootKey\)[\s\S]{0,800}refreshOfflineSize\(\)/
       .test(settings));
  ok('and again on resume',
     /override fun onResume[\s\S]{0,200}refreshOfflineSize\(\)/.test(settings));
  ok('kept moving while downloading',
     /postDelayed\(this, 2000\)/.test(settings));
  ok('the ticker is stopped when the screen goes away',
     /override fun onPause[\s\S]{0,200}removeCallbacks/.test(settings));
  ok('harmless on screens without the row',
     /findPreference<Preference>\("offline_status"\) == null\) return/
       .test(settings));
  ok('downloading shows as downloading',
     /OfflineFeed\.isPrefetching\(\)/.test(settings));
}

console.log('\nOffline never falls through to the browser');
{
  const docs = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');

  // Cards and documents are saved by separate passes, so the store can hold
  // fifty reels and have no page to put them in. serve() returned null there,
  // which handed the request to a WebView with no network - the raw
  // ERR_INTERNET_DISCONNECTED page, with everything downloaded.
  // A page of our own around the cards is exactly what made offline look
  // different from online, so there is deliberately nothing to fall back to.
  ok('no page is invented when only cards are held',
     /shellFor/.test(docs) &&
     /OfflineFeed\.(cardMarkupList|realPlayableItems)/.test(docs));

  // A restored session can land on a permalink or a profile, and nothing is
  // stored for those.
  ok('an unknown page falls back to the home screen',
     /else -> "home"/.test(docs));
}

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);

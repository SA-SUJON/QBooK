#!/usr/bin/env node
/**
 * Regression guard for m.facebook.com ad removal.
 *
 * The DOM here is not invented: it was captured from a real device with the
 * in-app inspector. The markers are the ones Facebook actually sets.
 *
 *   data-video-tracking='{"adid":"...","is_sponsored":1,...}'
 *   data-testid="sponsored-story-photo"
 *
 * and each story is a direct child of div[data-type="vscroller"], which gives
 * an exact card boundary.
 *
 * Asserts both directions: ads go, and the feed, the screen and real posts -
 * including a real post that contains a video - all survive.
 */
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const KT = path.join(__dirname, '..',
  'app/src/main/java/com/dustbook/app/utils/MFacebookAds.kt');
const src = fs.readFileSync(KT, 'utf8');
const i = src.indexOf('fun script()');
const start = src.indexOf('return """', i) + 'return """'.length;
const script = src.slice(start, src.indexOf('""".trimIndent()', start));

function run(html, url) {
  const dom = new JSDOM(html, {
    runScripts: 'outside-only', pretendToBeVisual: true,
    url: url || 'https://m.facebook.com/',
  });
  const w = dom.window;
  w.requestIdleCallback = undefined;
  w.requestAnimationFrame = (f) => setTimeout(f, 0);
  try { w.HTMLMediaElement.prototype.pause = function () {}; } catch (e) {}
  w.eval(script);
  return new Promise((r) => setTimeout(() => r(w), 800));
}

function removed(w, sel) {
  const el = w.document.querySelector(sel);
  if (!el) return 'MISSING';
  if (el.getAttribute('data-db-ad') === '1') return true;
  let p = el.parentElement;
  while (p) {
    if (p.getAttribute && p.getAttribute('data-db-ad') === '1') return true;
    p = p.parentElement;
  }
  return false;
}

let pass = 0, fail = 0;
const check = (n, got, want) => {
  const ok = got === want;
  ok ? pass++ : fail++;
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${n}${ok ? '' : ` (got ${got}, want ${want})`}`);
};

const FEED = `<body id="app-body"><div id="screen-root">
<div data-mcomponent="MScreen" data-type="container" class="m bg-s30 dark-mode">
 <div data-type="vscroller" data-mcomponent="MContainer" data-is-pull-to-refresh-allowed="true" class="m">
  <div data-dcm-id="1" data-mcomponent="MContainer" data-type="container" id="AD_VIDEO" class="m">
    <div tabindex="0" data-action-id="32484" data-mcomponent="MContainer" data-type="container" class="m bg-s2">
      <div role="button" aria-label="Video player" data-video-id="1452526892980986"
           data-video-tracking='{"adid":"120251177608830592","qid":"-64386","is_sponsored":1}'
           data-type="video" data-mcomponent="MVideo" data-testid="sponsored-story-photo" class="m">
        <div class="inline-video-container"><video src="blob:x"></video></div>
      </div></div>
    <div>Regal Emporium Ad</div></div>
  <div data-mcomponent="MContainer" data-type="container" id="REAL_VIDEO" class="m">
    <div><span>আল আমিন</span><span>2h</span></div>
    <div role="button" data-video-id="999"
         data-video-tracking='{"qid":"-6438599","mf_story_key":"2433474","top_level_post_id":"12"}'
         data-mcomponent="MVideo"><video src="blob:y"></video></div>
    <div>a genuine post from a friend</div></div>
  <div data-mcomponent="MContainer" data-type="container" id="REAL_TEXT" class="m">
    <div><span>Mim</span><span>3h</span></div><div>another genuine post</div></div>
  <div data-mcomponent="MContainer" data-type="container" id="AD_ABOUT" class="m">
    <div>Some Brand</div><a href="/ads/about/?entry_product=ad_preferences">Why this ad</a></div>
 </div></div></div></body>`;

// Image ad: none of the video markers, only the "Ad" header label.
// Captured from a real device after the video-only rules shipped and this
// still got through.
const IMAGE_AD = `<body id="app-body"><div id="screen-root">
<div data-mcomponent="MScreen" data-type="container" class="m bg-s30 dark-mode">
 <div data-type="vscroller" data-mcomponent="MContainer" class="m">
  <div data-dcm-id="1" data-tracking-duration-id="13" data-mcomponent="MContainer" data-type="container" id="IMG_AD" class="m">
    <div data-mcomponent="MContainer" data-type="container" class="m bg-s2">
      <div data-mcomponent="TextArea" data-type="text"><div dir="auto" class="native-text"><span role="link">Horlicks Bangladesh</span></div></div>
      <div data-mcomponent="TextArea" data-type="text"><div dir="auto" class="native-text"><span>Ad</span></div></div>
    </div>
    <div data-testid="story-photo-0"><img src="https://scontent.fdac1-1.fna.fbcdn.net/v/t39/x.jpg"></div>
    <div>চকোলেট হরলিক্‌সে আছে ২৩টি প্রয়োজনীয়</div></div>
  <div data-tracking-duration-id="14" data-mcomponent="MContainer" data-type="container" id="IMG_REAL" class="m">
    <div data-mcomponent="MContainer" data-type="container" class="m bg-s2">
      <div data-mcomponent="TextArea"><div class="native-text"><span role="link">Zihan Khan</span></div></div>
      <div data-mcomponent="TextArea"><div class="native-text"><span>1d</span></div></div></div>
    <div data-testid="story-photo-0"><img src="https://scontent.fdac1-1.fna.fbcdn.net/v/t39/y.jpg"></div>
    <div>a genuine post from a friend</div></div>
  <div data-tracking-duration-id="15" data-mcomponent="MContainer" data-type="container" id="IMG_ADWORD" class="m">
    <div data-mcomponent="TextArea"><div class="native-text"><span>Dhaka Ad Agency</span></div></div>
    <div data-mcomponent="TextArea"><div class="native-text"><span>2h</span></div></div>
    <div>we ran a great ad campaign this week</div></div>
 </div></div></div></body>`;

// The label carries Facebook icon-font glyphs from the supplementary Private
// Use Area, so the node reads "Ad\u{F078B}\u{F1677}" rather than "Ad". They are
// invisible in a screenshot. v3.5.1 compared the raw text against "ad", never
// matched, and let every image ad through.
const AD_GLYPH = 'Ad\u{F078B}\u{F1677}';
const GLYPH_FEED = `<body id="app-body"><div id="screen-root">
<div data-mcomponent="MScreen" data-type="container" class="m bg-s23 dark-mode">
 <div data-type="vscroller" data-mcomponent="MContainer" class="m">
  <div data-dcm-id="1" data-tracking-duration-id="4" data-mcomponent="MContainer" data-type="container" id="GLYPH_AD" class="m">
    <div data-mcomponent="MContainer" data-type="container" class="m bg-s2">
      <div data-mcomponent="TextArea"><div class="native-text rslh"><span role="link">Discover ASR \u{F0089}</span></div></div>
      <div data-mcomponent="TextArea"><div class="native-text">${AD_GLYPH}</div></div></div>
    <div data-testid="story-photo-0"><img src="x.jpg"></div></div>
  <div data-tracking-duration-id="9" data-mcomponent="MContainer" data-type="container" id="GLYPH_REAL" class="m">
    <div data-mcomponent="MContainer" data-type="container" class="m bg-s2">
      <div data-mcomponent="TextArea"><div class="native-text"><span role="link">Zihan Khan</span></div></div>
      <div data-mcomponent="TextArea"><div class="native-text">1d</div></div></div>
    <div data-testid="story-photo-0"><img src="y.jpg"></div>
    <div>a genuine post</div></div>
  <div data-tracking-duration-id="10" data-mcomponent="MContainer" data-type="container" id="GLYPH_ADCO" class="m">
    <div data-mcomponent="TextArea"><div class="native-text"><span>Dhaka Ad Agency</span></div></div>
    <div data-mcomponent="TextArea"><div class="native-text">3h</div></div>
    <div>we ran a great ad campaign</div></div>
 </div></div></div></body>`;

const REELS = `<body id="app-body"><div id="screen-root">
<div data-mcomponent="MScreen" data-type="container" class="m bg-s30 dark-mode">
 <div data-type="vscroller" data-mcomponent="MContainer" class="m vscroller vscroller-snap">
  <div data-mcomponent="MContainer" data-type="container" id="REEL_REAL" class="m bg-s39 vertically-snappable">
    <div data-video-tracking='{"qid":"-6438599762053815597","mf_story_key":"2433474437163646"}'
         data-mcomponent="MVideo"><video></video></div>
    <div>Jeanetta Marlow</div></div>
  <div data-mcomponent="MContainer" data-type="container" id="REEL_AD" class="m bg-s39 vertically-snappable">
    <div data-video-tracking='{"adid":"120251177608830592","is_sponsored":1}'
         data-testid="sponsored-story-photo" data-mcomponent="MVideo"><video></video></div>
    <div>Sponsored reel</div></div>
  <div data-mcomponent="MContainer" data-type="container" id="REEL_REAL2" class="m bg-s39 vertically-snappable">
    <div>Mehedi Hasan real reel</div></div>
 </div></div></div></body>`;

(async () => {
  console.log('m.facebook ad removal, markup captured from a real device\n');

  let w = await run(FEED);
  console.log('home feed');
  check('sponsored video card removed', removed(w, '#AD_VIDEO'), true);
  check('/ads/about card removed', removed(w, '#AD_ABOUT'), true);
  check('real post with video survives', removed(w, '#REAL_VIDEO'), false);
  check('real text post survives', removed(w, '#REAL_TEXT'), false);
  check('feed scroller survives', removed(w, '[data-type="vscroller"]'), false);
  check('screen survives', removed(w, '[data-mcomponent="MScreen"]'), false);

  w = await run(IMAGE_AD);
  console.log('home feed, image ad with no video markers');
  check('image ad removed', removed(w, '#IMG_AD'), true);
  check('real post survives', removed(w, '#IMG_REAL'), false);
  check('post by "Dhaka Ad Agency" survives', removed(w, '#IMG_ADWORD'), false);
  check('scroller survives', removed(w, '[data-type="vscroller"]'), false);

  w = await run(GLYPH_FEED);
  console.log('home feed, label with icon-font glyphs attached');
  check('ad with "Ad<glyph><glyph>" label removed', removed(w, '#GLYPH_AD'), true);
  check('real post survives', removed(w, '#GLYPH_REAL'), false);
  check('page "Dhaka Ad Agency" survives', removed(w, '#GLYPH_ADCO'), false);
  check('scroller survives', removed(w, '[data-type="vscroller"]'), false);

  w = await run(REELS, 'https://m.facebook.com/reel/1602844891173220/');
  console.log('reels tab');
  check('sponsored reel removed', removed(w, '#REEL_AD'), true);
  check('real reel survives', removed(w, '#REEL_REAL'), false);
  check('second real reel survives', removed(w, '#REEL_REAL2'), false);
  check('reels scroller survives', removed(w, '[data-type="vscroller"]'), false);

  console.log(`\n${pass} passed, ${fail} failed`);
  if (fail) {
    console.log('\n::error::m.facebook ad removal regression');
    process.exit(1);
  }
})();

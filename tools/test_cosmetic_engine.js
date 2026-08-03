#!/usr/bin/env node
/**
 * Verifies the filter-list engine against real Facebook markup.
 *
 * Rules come from uBlock Origin and AdGuard, so they name exactly what to
 * remove. Unlike the old heuristics there is no walking up the tree, which is
 * what previously took the whole feed with it.
 */
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const ROOT = path.join(__dirname, '..');
const KT = path.join(ROOT, 'app/src/main/java/com/dustbook/app/utils/CosmeticFilters.kt');
const ASSET = path.join(ROOT, 'app/src/main/assets/fb_cosmetic.txt');

const asset = fs.readFileSync(ASSET, 'utf8');
const plain = asset.split('[plain]')[1].split('[procedural]')[0].trim().split('\n').filter(Boolean);
const proc = asset.split('[procedural]')[1].trim().split('\n').filter(Boolean);

const kt = fs.readFileSync(KT, 'utf8');
const i = kt.indexOf('fun proceduralScript');
const start = kt.indexOf('return """', i) + 'return """'.length;
const engine = kt.slice(start, kt.indexOf('""".trimIndent()', start))
  .replace('$list', proc.map((r) => `'${r.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`).join(','))
  .replace(/\$\{'\$'\}/g, '$');

function run(html) {
  const dom = new JSDOM(html, {
    runScripts: 'outside-only', pretendToBeVisual: true, url: 'https://m.facebook.com/',
  });
  const w = dom.window;
  w.requestIdleCallback = undefined;
  w.requestAnimationFrame = (f) => setTimeout(f, 0);
  const styled = new Set();
  for (const sel of plain) {
    try { for (const el of w.document.querySelectorAll(sel)) styled.add(el); } catch (e) {}
  }
  w.eval(engine);
  return new Promise((r) => setTimeout(() => r({ w, styled }), 700));
}

function removed(ctx, sel) {
  const el = ctx.w.document.querySelector(sel);
  if (!el) return 'MISSING';
  if (ctx.styled.has(el) || el.getAttribute('data-db-hidden') === '1') return true;
  let p = el.parentElement;
  while (p) {
    if (ctx.styled.has(p) || (p.getAttribute && p.getAttribute('data-db-hidden') === '1')) return true;
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

(async () => {
  console.log(`filter engine: ${plain.length} plain, ${proc.length} procedural rules\n`);

  const ctx = await run(`<div id="m_newsfeed_stream">
    <article data-sigil="AdStory marea" id="ad1"><div><span>Spa</span><span>Ad</span></div><div>promo</div></article>
    <article data-ft='{"quick_promotion_id":"1"}' id="ad2"><div>promoted</div></article>
    <article id="ad3"><div>Sponsored</div><a href="/ads/about/?entry_product=ad_preferences">Why</a></article>
    <article data-sigil="story_div" id="p1"><div><span>Friend</span><span>2h</span></div><div>a genuine post with text</div></article>
    <article data-sigil="story_div" id="p2"><div><span>Other</span><span>3h</span></div><div>another genuine post</div></article>
    <article data-sigil="story_div" id="p3"><div><span>Dhaka Ad Agency</span></div><div>we are hiring</div></article>
  </div>
  <div data-mcomponent="MContainer" class="m fixed-container bottom" id="bar"><a href="/lite/">Open app</a></div>
  <div data-sigil="m-promo" id="promo">install our app</div>`);

  console.log('ads and banners must go');
  check('AdStory article', removed(ctx, '#ad1'), true);
  check('quick_promotion article', removed(ctx, '#ad2'), true);
  check('/ads/about sponsored post', removed(ctx, '#ad3'), true);
  check('fixed bottom Open app bar', removed(ctx, '#bar'), true);
  check('m-promo banner', removed(ctx, '#promo'), true);

  console.log('the feed must survive');
  check('real post 1', removed(ctx, '#p1'), false);
  check('real post 2', removed(ctx, '#p2'), false);
  check('page named "Dhaka Ad Agency"', removed(ctx, '#p3'), false);
  check('feed container', removed(ctx, '#m_newsfeed_stream'), false);

  // ------------------------------------------------------------------
  // "Hide ad elements" must not take Facebook's own bottom sheets.
  //
  // The rule used to be an unqualified
  //   div[data-mcomponent="MContainer"][class="m fixed-container bottom"]
  // which reads as "the promo bar pinned to the bottom". It is not. The lite
  // renderer draws every bottom sheet with that same class - the comment
  // composer, the account switcher, the login prompt. Measured on a captured
  // m.facebook.com screen, the one node matching it was 737px tall and held
  // the whole "Get the full experience" sheet, so with the switch ON those
  // surfaces were removed and tapping them left only the dimmed backdrop.
  //
  // The promo bar is now told apart by what it links to: an app-store or
  // app-scheme link. Facebook's own sheets link to /login/ and /reg/.
  const sheets = await run(`<div id="scr" data-mcomponent="MScreen">
    <div data-mcomponent="MContainer" class="m fixed-container bottom" id="promoBar"
         data-actual-height="53"><a href="/lite/?entry=bookmark">Open app</a></div>
    <div data-mcomponent="MContainer" class="m fixed-container bottom" id="storeBar">
      <a href="https://play.google.com/store/apps/details?id=com.facebook.katana">Get the app</a></div>
    <div data-mcomponent="MContainer" class="m fixed-container bottom" id="loginSheet"
         data-actual-height="737">
      <div>Get the full experience</div>
      <div>Log in to see the latest content and explore your interests.</div>
      <a href="https://m.facebook.com/login/?next=x" data-action-id="32721">Log in</a>
      <a href="https://m.facebook.com/reg/?next=x" data-action-id="32722">Create new account</a>
    </div>
    <div data-mcomponent="MContainer" class="m fixed-container bottom" id="commentSheet"
         data-actual-height="612">
      <div>Comments</div>
      <textarea placeholder="Write a comment"></textarea>
      <div data-action-id="4401">Post</div>
    </div>
    <div data-mcomponent="MContainer" class="m fixed-container bottom" id="switcherSheet"
         data-actual-height="480">
      <div>Switch profile</div>
      <div data-action-id="5501">Rabbi</div>
      <div data-action-id="5502">Add account</div>
    </div>
  </div>`);

  console.log('bottom bars: the promo goes, Facebook\u2019s own sheets stay');
  check('app-scheme promo bar removed', removed(sheets, '#promoBar'), true);
  check('play-store promo bar removed', removed(sheets, '#storeBar'), true);
  check('login sheet survives', removed(sheets, '#loginSheet'), false);
  check('comment composer survives', removed(sheets, '#commentSheet'), false);
  check('profile switcher survives', removed(sheets, '#switcherSheet'), false);
  check('the screen root survives', removed(sheets, '#scr'), false);

  {
    const all = sheets.w.document.querySelectorAll('[data-action-id]');
    let lost = 0;
    for (const el of all) {
      let p = el;
      while (p) {
        if (sheets.styled.has(p) ||
            (p.getAttribute && p.getAttribute('data-db-hidden') === '1')) { lost++; break; }
        p = p.parentElement;
      }
    }
    check(`sheet controls stay reachable (lost ${lost} of ${all.length})`, lost === 0, true);
  }

  console.log(`\n${pass} passed, ${fail} failed`);
  if (fail) {
    console.log('\n::error::cosmetic filter engine regression');
    process.exit(1);
  }
})();

#!/usr/bin/env node
/**
 * Regression guard for the "remove app download banner blanks the page" bug.
 *
 * History: this broke three separate times. Each fix tried to recognise the
 * feed by shape - ids, class names, child counts, story markers - and none of
 * those exist reliably on Facebook's obfuscated mobile DOM. Too strict and the
 * ad survived, too loose and the whole page disappeared.
 *
 * The working approach measures instead: a card is a small fraction of the
 * page's text, so the walk stops before it can swallow the feed.
 *
 * This runs the real cosmetic script, extracted from AdBlocker.kt, against
 * several DOM shapes. If a future edit reintroduces either failure mode, this
 * exits non-zero and CI fails.
 */
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const KT = path.join(__dirname, '..', 'app/src/main/java/com/dustbook/app/utils/AdBlocker.kt');

function extract(fnName, flags) {
  const src = fs.readFileSync(KT, 'utf8');
  const i = src.indexOf('fun ' + fnName);
  if (i < 0) throw new Error('function not found: ' + fnName);
  const start = src.indexOf('return """', i) + 'return """'.length;
  const end = src.indexOf('""".trimIndent()', start);
  return src.slice(start, end)
    .replace(/\$\{'"'\}/g, '"')
    .replace(/\$flagsJs/g, flags)
    .replace(/\$blockAds/g, 'true')
    .replace(/\$blockAppPromos/g, 'true');
}

const script = extract('getCosmeticScript', 'stories:false');

function run(html) {
  const dom = new JSDOM(html, { runScripts: 'outside-only', pretendToBeVisual: true });
  const w = dom.window;
  w.requestIdleCallback = undefined;
  w.requestAnimationFrame = (f) => setTimeout(f, 0);
  w.FBPro = {
    onAuthState() {}, onScrollState() {},
    onLoginFormReady() {}, onBlobDownload() {},
  };
  w.eval(script);
  return new Promise((res) => setTimeout(() => res(w), 900));
}

function hidden(w, sel) {
  const el = w.document.querySelector(sel);
  if (!el) return 'MISSING';
  if (el.getAttribute('data-fbpro-hidden') === '1') return true;
  let p = el.parentElement;
  while (p) {
    if (p.getAttribute && p.getAttribute('data-fbpro-hidden') === '1') return true;
    p = p.parentElement;
  }
  return false;
}

let pass = 0, fail = 0;
function check(name, got, want) {
  const ok = got === want;
  ok ? pass++ : fail++;
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${ok ? '' : ` (got ${got}, want ${want})`}`);
}

function posts(n, prefix) {
  let s = '';
  for (let i = 0; i < n; i++) {
    s += `<div class="card" id="${prefix}${i}"><div><span>User${i}</span><span>${i}h</span></div>` +
         `<div>a genuine post with a reasonable amount of body text number ${i}</div></div>`;
  }
  return s;
}

(async () => {
  console.log('feed guard: the page must never disappear, the banner must go\n');

  // 1. Pinned "Open app" bar beside a normal feed
  let w = await run(`<div><div role="main"><div class="wrap">${posts(8, 'p')}</div></div>
    <div id="bar" style="position:fixed;bottom:0"><a href="/lite/?entry=bookmark">Open app</a></div></div>`);
  console.log('A) pinned Open app bar');
  check('bar removed', hidden(w, '#bar'), true);
  check('feed survives', hidden(w, '.wrap'), false);
  check('first post survives', hidden(w, '#p0'), false);
  check('last post survives', hidden(w, '#p7'), false);

  // 2. Store link inside a feed card
  w = await run(`<div role="main"><div class="wrap">
    <div class="card" id="promo"><div>Get the app</div>
      <a href="https://play.google.com/store/apps/details?id=com.facebook.katana">Install</a></div>
    ${posts(6, 'q')}</div></div>`);
  console.log('B) store link inside a card');
  check('promo card removed', hidden(w, '#promo'), true);
  check('feed survives', hidden(w, '.wrap'), false);
  check('real posts survive', hidden(w, '#q0'), false);

  // 3. Ad label plus a banner in the same feed
  w = await run(`<div role="main"><div class="wrap">
    <div class="card" id="ad"><div><span>Brand</span><span>Ad</span></div><div>advert body</div></div>
    ${posts(6, 'r')}
    <div id="bar2"><a href="fb://feed">Open in app</a></div></div></div>`);
  console.log('C) ad label and banner together');
  check('ad removed', hidden(w, '#ad'), true);
  check('banner removed', hidden(w, '#bar2'), true);
  check('feed survives', hidden(w, '.wrap'), false);
  check('real posts survive', hidden(w, '#r5'), false);

  // 4. Obfuscated DOM: no ids, no roles, no article tags
  w = await run(`<div class="x9f619"><div class="x1qjc9v5"><div class="x78zum5">
    <div class="x1lliihq"><div><span>Spa</span><span>Ad</span></div><div>promo body text</div>
      <div><a href="/lite/?entry=bookmark">Open app</a></div></div>
    <div class="x1lliihq" id="s1"><div><span>Friend</span><span>2h</span></div><div>a real post with body text</div></div>
    <div class="x1lliihq" id="s2"><div><span>Other</span><span>3h</span></div><div>another real post with body text</div></div>
    </div></div></div>`);
  console.log('D) obfuscated DOM, no markers at all');
  check('feed wrapper survives', hidden(w, '.x78zum5'), false);
  check('real post 1 survives', hidden(w, '#s1'), false);
  check('real post 2 survives', hidden(w, '#s2'), false);

  // 5. Content that merely mentions apps must never be touched
  w = await run(`<div role="main"><div class="wrap">
    <div class="card" id="t1"><div>I am building a weather app this weekend, feedback welcome</div></div>
    <div class="card" id="t2"><div>Ad astra per aspera has always been my favourite motto</div></div>
    <div class="card" id="t3"><div><span>Dhaka Ad Agency</span></div><div>we are hiring designers</div></div>
    ${posts(4, 'u')}</div></div>`);
  console.log('E) false positives');
  check('post about building an app stays', hidden(w, '#t1'), false);
  check('"Ad astra" post stays', hidden(w, '#t2'), false);
  check('"Dhaka Ad Agency" page stays', hidden(w, '#t3'), false);
  check('feed survives', hidden(w, '.wrap'), false);

  // 6. The login page banner. Facebook serves this above the sign-in form:
  //
  //      "Get Facebook for Android and browse faster."
  //
  //    It is a plain <div> with no role, no class and no store link, and the
  //    wording never contains the word "app" -- it is the product name plus a
  //    platform. That defeated both passes at once: the literal PROMO_TEXT
  //    list only ran over a/button/[role=*], and the structural sweep's
  //    PROMO_RE is anchored on "app". The banner therefore survived on the
  //    one screen a new user sees first.
  w = await run(`<div><div id="pageArea">
    <div id="banner"><a href="/lite/?entry=login"><img alt=""></a>
      <div>Get Facebook for Android and browse faster.</div></div>
    <div id="loginForm"><form method="post" action="/login/">
      <input type="email" name="email" placeholder="Mobile number or email address">
      <input type="password" name="pass" placeholder="Password">
      <button type="submit">Log in</button>
      </form>
      <div id="forgot"><a href="/recover/initiate/">Forgotten password?</a></div>
      <div id="create"><a href="/reg/">Create new account</a></div>
    </div></div></div>`);
  console.log('F) login page app banner');
  check('banner removed', hidden(w, '#banner'), true);
  check('login form survives', hidden(w, '#loginForm'), false);
  check('email field survives', hidden(w, 'input[type="email"]'), false);
  check('password field survives', hidden(w, 'input[type="password"]'), false);
  check('log in button survives', hidden(w, 'button[type="submit"]'), false);
  check('forgotten password survives', hidden(w, '#forgot'), false);
  check('create account survives', hidden(w, '#create'), false);
  check('page wrapper survives', hidden(w, '#pageArea'), false);

  // 7. Widening the text pass to bare <div>s must not start eating posts that
  //    merely name a platform. These all contain "Android" or "Facebook" and
  //    must be left alone.
  w = await run(`<div role="main"><div class="wrap">
    <div class="card" id="v1"><div>Just got Facebook for Android working on my old tablet</div></div>
    <div class="card" id="v2"><div>Does anyone develop for Android here?</div></div>
    <div class="card" id="v3"><div>Facebook for Business has a new dashboard</div></div>
    ${posts(4, 'w')}</div></div>`);
  console.log('G) platform names in real content');
  check('post naming the Android app stays', hidden(w, '#v1'), false);
  check('post asking about Android stays', hidden(w, '#v2'), false);
  check('Facebook for Business post stays', hidden(w, '#v3'), false);
  check('feed survives', hidden(w, '.wrap'), false);

  // 8. The lite renderer's screen root must survive.
  //
  //    Facebook's mobile site now draws a whole screen inside a single
  //    MScreen node. It carries no id, no role and no data-sigil, so none of
  //    the stop rules matched it and an "Open app" label five levels down let
  //    the walk climb all the way to it.
  //
  //    Measured against a captured m.facebook.com lite screen before the fix:
  //    hiding MScreen removed 77 of the 77 nodes carrying a data-action-id -
  //    the comment control, the account switcher, the tab bar, everything.
  //    That is the "screen goes dim and the dialog never appears" report and
  //    the "comment option is missing" one, both from the same cause.
  //
  //    The shape below is the real one: attribute names and nesting depth are
  //    taken from the captured page, including the inline scripts. Those
  //    matter. The walk is bounded by a share of the page's text, and on the
  //    real page 48565 of the body's 51844 characters are script source, so
  //    the entire visible screen (584 characters) sits far under the limit
  //    and the size guard never fires. Without the scripts here the fixture
  //    passes against the broken code and proves nothing.
  //    Two measurements from the captured page drive the shape below:
  //      - the outermost node whose whole text is "Open app" sits exactly 5
  //        levels under MScreen, and the promo pass walks up to 5, so it
  //        lands precisely on the screen root
  //      - 26176 of the body's 51844 characters are inline script, so the
  //        visible screen is a small share of the "page text" the size guard
  //        measures and that guard never fires
  //    The script sits beside the screen, not inside it, exactly as on the
  //    real page: MScreen there holds 584 characters out of a 51844-character
  //    body. Put the padding inside the screen instead and the screen becomes
  //    the whole page, the size guard fires for the wrong reason, and the
  //    fixture stops reproducing the bug.
  w = await run(`<div id="screen-root">
      <script>var pad = "${'x'.repeat(26000)}";</script>
      <div data-mcomponent="MScreen" data-screen-id="65549"
        data-crash-screen-id="42949673960" data-screen-keys="57,56,55"
        data-type="container" class="m bg-s2">
      <div data-mcomponent="MContainer" class="m fixed-container top">
       <div data-mcomponent="MContainer">
        <div data-mcomponent="ServerTextArea" data-action-id="12">
         <div><span>Open app</span></div></div>
        <div data-mcomponent="ServerTextArea" data-action-id="13">
         <div><span>Log in</span></div></div>
       </div>
      </div>
      <div data-type="vscroller">
        <div data-mcomponent="MContainer" id="liteFeed">
          <div data-mcomponent="MContainer" data-action-id="21">
            <div data-mcomponent="TextArea">a genuine post with real body text here</div>
            <div data-mcomponent="ServerTextArea" data-action-id="22" id="liteComment">
              <div><span>Comment</span></div></div>
            <div data-mcomponent="ServerTextArea" data-action-id="23" id="liteLike">
              <div><span>Like</span></div></div>
          </div>
          <div data-mcomponent="MContainer" data-action-id="24" id="liteSwitch">
            <div data-mcomponent="TextArea">Switch profile</div></div>
          ${posts(5, 'lite')}
        </div>
      </div>
    </div></div>`);
  console.log('H) lite renderer screen root');
  check('the screen root survives', hidden(w, '[data-mcomponent="MScreen"]'), false);
  check('the scroller survives', hidden(w, '[data-type="vscroller"]'), false);
  check('the feed inside it survives', hidden(w, '#liteFeed'), false);
  check('the comment control survives', hidden(w, '#liteComment'), false);
  check('the like control survives', hidden(w, '#liteLike'), false);
  check('the profile switcher survives', hidden(w, '#liteSwitch'), false);
  check('a genuine lite post survives', hidden(w, '#lite0'), false);

  // Whatever else happens, the great majority of the screen's controls have
  // to still be reachable. Counting is what caught this: the individual
  // checks above all passed while the screen as a whole was being removed.
  {
    const all = w.document.querySelectorAll('[data-action-id]');
    let lost = 0;
    for (const el of all) {
      let p = el;
      while (p) {
        if (p.getAttribute && p.getAttribute('data-fbpro-hidden') === '1') { lost++; break; }
        p = p.parentElement;
      }
    }
    check(`most controls stay reachable (lost ${lost} of ${all.length})`,
          lost < all.length / 2, true);
  }

  // 9. Removing a promo must not take the header it sits in.
  //
  //    Reported as: the feed appears for about a second and then hides, with
  //    the app-download bar visible underneath while it happens.
  //
  //    Facebook puts "Open app" inside the pinned top bar, beside the logo,
  //    the Log in button and the tab row. Every promo text test matches that
  //    bar, because its text reads "Open appLog inVideo", and the walk then
  //    climbed out of the link and hid the whole header - 8 controls on the
  //    captured page. The app-download bar survived because it is a separate
  //    node further down, which is exactly what the report described.
  //
  //    The text-size guard cannot catch this: the header is a few dozen
  //    characters against a body of tens of thousands, most of it script.
  //    Counting controls is what tells a banner from the bar around it.
  w = await run(`<div id="screenRoot">
      <script>var pad = "${'x'.repeat(26000)}";</script>
      <div class="m fixed-container top" id="header">
        <div data-mcomponent="MContainer">
          <div data-mcomponent="ServerImageArea" data-action-id="1"
               aria-label="Facebook Logo" id="logo"><div class="fl ac">f</div></div>
          <div data-mcomponent="ServerTextArea" data-action-id="2" id="openApp">
            <div class="fl ac"><div dir="auto" class="native-text">Open app</div></div>
          </div>
          <div data-mcomponent="ServerTextArea" data-action-id="3"
               aria-label="Log in" id="loginBtn">
            <div class="fl ac"><div dir="auto" class="native-text">Log in</div></div>
          </div>
          <div data-mcomponent="ServerTextArea" data-action-id="4" id="videoTab">
            <div class="fl ac"><div dir="auto" class="native-text">Video</div></div>
          </div>
          <div data-mcomponent="ServerTextArea" data-action-id="5" id="searchBtn">
            <div class="fl ac"><div dir="auto" class="native-text">Search</div></div>
          </div>
        </div>
      </div>
      <div id="feedArea">
        ${posts(5, 'hdr')}
      </div>
      <div class="m fixed-container bottom" id="promoBar">
        <a href="/lite/?entry=login">Get the app</a>
      </div>
    </div>`);
  console.log('I) an app promo inside the header');
  check('the promo link itself goes', hidden(w, '#openApp'), true);
  check('the header survives', hidden(w, '#header'), false);
  check('the logo survives', hidden(w, '#logo'), false);
  check('the Log in button survives', hidden(w, '#loginBtn'), false);
  check('the Video tab survives', hidden(w, '#videoTab'), false);
  check('search survives', hidden(w, '#searchBtn'), false);
  check('the feed survives', hidden(w, '#feedArea'), false);
  check('a real post survives', hidden(w, '#hdr0'), false);
  // The standalone bar is a promo through and through, so it still goes.
  check('the app-download bar still goes', hidden(w, '#promoBar'), true);

  // 10. killSponsored() must hide sponsored posts even when the card
  //     carries other controls (Shop Now link, brand link, etc.).
  //
  //     The ctrls() guard was tuned to "+1" to stop the promo walk from
  //     taking the 8-control header. A bare <a href="/ads/about/"> reads as
  //     startCtrls=0 (querySelectorAll sees descendants, not the element),
  //     so the threshold was "parent has >1 control" — any ad card with 2+
  //     controls was left alone. Fixed by counting the element itself when
  //     it IS a control, and widening to "+3": an ad card's nearest parent
  //     has 2-5 controls, a header has 5+.
  w = await run(`<div id="screenRoot">
      <script>var pad = "${'x'.repeat(26000)}";</script>
      <div id="feedArea">
        <div data-sigil="story_div" class="card" id="sponAbout">
          <div class="ad-header">
            <span><a href="/brand-page">Ad Brand</a></span>
            <span>Sponsored</span>
            <a href="/ads/about/?entry_product=ad_preferences">Why am I seeing this?</a>
            <a href="https://shop.example.com/product">Shop Now</a>
          </div>
          <div class="ad-body">Buy our amazing product today! Limited time offer.</div>
          <div class="reactions">
            <span data-action-id="r1">Like</span>
            <span data-action-id="r2">Comment</span>
            <span data-action-id="r3">Share</span>
          </div>
        </div>
        <div data-sigil="story_div" class="card" id="sponText">
          <div class="ad-header">
            <span>Another Brand</span>
            <span>Ad ·</span>
            <a href="https://shop.example.com/item2">Learn More</a>
          </div>
          <div class="ad-body">Special discount for new customers.</div>
        </div>
        <div class="card" id="sponLabel">
          <article aria-label="Sponsored" id="sponArt">
            <div class="ad-header">
              <span>Third Brand</span>
              <span>Ad</span>
            </div>
            <div class="ad-body">Quality products since 1999.</div>
            <div><button data-action-id="b1">Shop</button></div>
          </article>
        </div>
        ${posts(3, 'spnReal')}
      </div>
    </div>`);
  console.log('J) sponsored posts with multiple controls');
  check('/ads/about/ sponsored card with Shop Now link removed', hidden(w, '#sponAbout'), true);
  check('Ad · text label card with Learn More link removed', hidden(w, '#sponText'), true);
  // The article itself is hidden; the wrapper div is just a test fixture shell.
  check('aria-label Sponsored article hidden directly', hidden(w, '#sponArt'), true);
  check('real post 1 survives', hidden(w, '#spnReal0'), false);
  check('real post 2 survives', hidden(w, '#spnReal1'), false);
  check('real post 3 survives', hidden(w, '#spnReal2'), false);
  check('feed area survives', hidden(w, '#feedArea'), false);

  console.log(`\n${pass} passed, ${fail} failed`);
  if (fail) {
    console.log('\n::error::feed guard failed - the blank-page or unblocked-ad bug is back');
    process.exit(1);
  }
})();

// -----------------------------------------------------------------------
// Reels: marker-less ad cards, caught by CTA text only.
//
// Facebook stopped labelling promoted reels at all. The only difference
// from an organic reel is a call-to-action button. Six approaches were
// tried and rejected on this exact bug (display:none resets the snap
// scroller; opacity:0 leaves a visible blank gap; removeChild loses to
// Facebook's own snap handler; an RAF loop runs too late and burns CPU) -
// see failed-attempts.md for the full history. This guards the shape that
// finally held: CTA text match, scoped to data-is-reels, hidden with
// visibility so the card's layout space survives (no scroller resize, no
// gap), video torn down without .load() (which was its own separate
// freeze), and the actual style write deferred to a microtask so it lands
// after Facebook's own synchronous handling of the same insert rather than
// racing it.
(async () => {
  const reelScript = extract('getCosmeticScript', 'stories:false');

  function reelCard(id, cta, hasVideo) {
    return `<div id="${id}" class="card">
        <div class="reel-body">${hasVideo ? '<video src="https://cdn.example.com/clip.mp4"></video>' : ''}</div>
        <div><span role="button">${cta}</span></div>
      </div>`;
  }

  async function runReels(html) {
    const dom = new JSDOM(html, { runScripts: 'outside-only', pretendToBeVisual: true });
    const w = dom.window;
    w.requestIdleCallback = undefined;
    w.requestAnimationFrame = (f) => setTimeout(f, 0);
    w.FBPro = {
      onAuthState() {}, onScrollState() {},
      onLoginFormReady() {}, onBlobDownload() {},
    };
    w.eval(reelScript);
    // Long enough for the 900ms-scale settle used elsewhere in this file,
    // and generous for the microtask the hide is deferred through.
    return new Promise((res) => setTimeout(() => res(w), 900));
  }

  const w = await runReels(`<div data-is-reels="true" id="reelsScreen">
      ${reelCard('ctaOrderNow', 'Order Now', true)}
      ${reelCard('ctaShopNow', 'Shop Now', true)}
      ${reelCard('ctaBangla', 'অর্ডার করুন', false)}
      ${reelCard('realReel1', 'Like', true)}
      ${reelCard('realReel2', 'Comment', true)}
    </div>`);

  console.log('\nReels: marker-less CTA ads');
  check('an Order Now reel card is hidden', hidden(w, '#ctaOrderNow'), true);
  check('a Shop Now reel card is hidden', hidden(w, '#ctaShopNow'), true);
  check('a Bangla CTA reel card is hidden', hidden(w, '#ctaBangla'), true);
  check('a real reel with a Like control survives', hidden(w, '#realReel1'), false);
  check('a real reel with a Comment control survives', hidden(w, '#realReel2'), false);

  // The layout-preservation requirement itself: a hidden card must still
  // occupy its natural height, or the snap scroller's scrollHeight shrinks
  // and the previous display:none regression (reset to the first reel
  // mid-scroll) is back.
  const hiddenCard = w.document.querySelector('#ctaOrderNow');
  check('a hidden reel card keeps visibility, not display:none',
    hiddenCard.style.display !== 'none' &&
    hiddenCard.style.visibility === 'hidden', true);

  // The video-freeze regression: .load() after removeAttribute('src') sent
  // Chromium's media pipeline through resource selection, emptied, abort,
  // then error recovery on the main thread - a 1-2s hang that looked like a
  // black screen. It must not come back inside the reel-ad path either.
  check('killVideos never calls load() on an ad reel\'s video',
    !/killVideos[\s\S]{0,260}\.load\(\)/.test(reelScript), true);

  // 11. The offline card holder is a container, never a card.
  //
  //     Report, proven end to end: offline, with the ad blocker on, the
  //     whole feed painted for about a second and then vanished. An ad
  //     that had slipped into the saved store sat inside [data-db-cards];
  //     the sponsored walk climbed to the batch node and condemned every
  //     saved post at once. The holder is now an explicit stop node AND
  //     an explicit hide refusal, on both layers.
  {
    const abSrc = fs.readFileSync(KT, 'utf8');
    check('the holder is a listed stop-node', 
      /hasAttribute\('data-db-cards'\)\) return true;/.test(abSrc), true);
    check('hide() refuses the holder even alone',
      /hasAttribute\('data-db-cards'\)\) return;/.test(abSrc), true);

    const adCard = `<div class="card" id="holdAd">
        <span><a href="/brand-page">Ad Brand</a></span>
        <span>Sponsored</span>
        <a href="/ads/about/?entry_product=ad_preferences">Why am I seeing this?</a>
      </div>`;
    const realCard = `<div class="card" id="holdReal"><div><span>Mim with a real saved post for offline reading</span></div></div>`;
    const w2 = await run(`<div id="screenRoot">
      <script>var pad = "${'x'.repeat(26000)}";</script>
      <div data-mcomponent="MScreen" data-type="container">
        <div id="__db_cards" data-db-cards="1">${adCard}${realCard}</div>
      </div></div>`);
    console.log('J) the offline card holder is untouchable');
    check('the holder survives', hidden(w2, '#__db_cards'), false);
    check('the ad inside it still goes', hidden(w2, '#holdAd'), true);
    check('the saved real post survives', hidden(w2, '#holdReal'), false);
  }

  console.log(`\n${pass} passed, ${fail} failed`);
  if (fail) {
    console.log('\n::error::reel CTA ad guard failed - the black/blank/loading-screen bug is back');
    process.exit(1);
  }
})();

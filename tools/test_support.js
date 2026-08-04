#!/usr/bin/env node
/**
 * Guards for the "buy me a coffee" prompt.
 *
 * The things worth protecting here are mostly about restraint and about not
 * losing the user's money:
 *
 *   - the wallet address must be exactly right, and its checksum must verify.
 *     A single wrong character sends a donation into nothing, and no test
 *     that merely compares against a copy of the same typo would notice.
 *   - no suggested figures. A preset amount reads as a price; this is a gift.
 *   - paying is optional, and "Don't show again" must actually stick.
 *   - no API key, ever. The repo is public.
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { JSDOM } = require('jsdom');

const ROOT = path.join(__dirname, '..');
const read = (p) => (fs.existsSync(p) ? fs.readFileSync(p, 'utf8') : '');

const html = read(path.join(ROOT, 'app/src/main/assets/support.html'));
const prompt = read(path.join(ROOT, 'app/src/main/java/com/dustbook/app/ui/SupportPrompt.kt'));
const prefs = read(path.join(ROOT, 'app/src/main/java/com/dustbook/app/utils/Prefs.kt'));
const settings = read(path.join(ROOT, 'app/src/main/java/com/dustbook/app/ui/SettingsActivity.kt'));
const aboutXml = read(path.join(ROOT, 'app/src/main/res/xml/settings_about.xml'));
const main = read(path.join(ROOT, 'app/src/main/java/com/dustbook/app/ui/MainActivity.kt'));
const app = read(path.join(ROOT, 'app/src/main/java/com/dustbook/app/DustbookApplication.kt'));

let pass = 0, fail = 0;
const ok = (name, cond, extra) => {
  if (cond) { pass++; console.log('  ok   ' + name); }
  else { fail++; console.log('  FAIL ' + name + (extra ? ' :: ' + extra : '')); }
};

const USDT = 'TUGdBjkWv1KN3otAYAPaWEDBknrCpBWmPf';
const BINANCE = '749542753';

console.log('the payment details are correct');
{
  ok('the page exists', html.length > 0);
  ok('the USDT address is present, exactly', html.includes(USDT));
  ok('the Binance Pay ID is present', html.includes(BINANCE));

  // Verify the address rather than compare it to itself. base58 decode,
  // check the 0x41 TRON prefix, then the doubled-SHA256 checksum.
  const AL = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
  let n = 0n;
  for (const ch of USDT) {
    const i = AL.indexOf(ch);
    if (i < 0) { n = -1n; break; }
    n = n * 58n + BigInt(i);
  }
  let valid = false, tron = false;
  if (n > 0n) {
    const raw = Buffer.from(n.toString(16).padStart(50, '0'), 'hex');
    const body = raw.subarray(0, 21), chk = raw.subarray(21);
    tron = body[0] === 0x41;
    const calc = crypto.createHash('sha256')
      .update(crypto.createHash('sha256').update(body).digest())
      .digest().subarray(0, 4);
    valid = calc.equals(chk);
  }
  ok('it is a TRON address (0x41 prefix)', tron);
  ok('and its checksum verifies, so it is not a typo', valid);

  ok('the network is spelled out, since the wrong one loses the money',
     /TRC20/.test(html) && /Other networks may result in lost funds/.test(html));
}

console.log('\nnothing is hardcoded, and nothing is demanded');
{
  ok('there is an amount field', /id="amount"/.test(html));
  ok('it is free text, with no value baked in',
     !/id="amount"[^>]*value=/.test(html));
  ok('no preset figures are offered',
     !/data-amount|>\s*\$\s*(1|2|3|5|10|20|25|50|100)\s*</.test(html));
  ok('the amount is required before the success state fires', /Amount/.test(html));
  ok('paying can be skipped outright', /id="skip"/.test(html));

  // The repo is public. An embedded key would be readable by anyone.
  ok('no payment gateway and no API key',
     !/api[_-]?key|secret|bearer |spv-payment/i.test(html));
  ok('the page loads nothing from the network',
     !/https?:\/\//.test(html.replace(/xmlns="[^"]*"/g, '')));
}

console.log('\nthe prompt behaves when driven');
if (!html) {
  // A deleted page must fail these, not crash the run and hide every
  // section below it.
  for (const n of ['two methods', 'USDT first', 'tab switching', 'copy via the app',
                   'no execCommand', 'USDT copies', 'skip closes', 'box reports']) {
    ok(n, false, 'support.html missing');
  }
} else {
  const dom = new JSDOM(html, { runScripts: 'dangerously', pretendToBeVisual: true });
  const w = dom.window, d = w.document;
  const calls = [];
  w.DBSupport = {
    copy(t) { calls.push(['copy', t]); },
    close(x) { calls.push(['close', x]); },
  };
  d.dispatchEvent(new w.Event('DOMContentLoaded'));
  const click = (el) => el.dispatchEvent(new w.Event('click', { bubbles: true }));

  const tabs = [...d.querySelectorAll('.pay-tab')];
  ok('two methods: USDT and Binance', tabs.length === 2);
  ok('USDT is shown first',
     d.getElementById('pane-usdt').classList.contains('active'));

  click(tabs[1]);
  ok('switching to Binance shows only that pane',
     d.getElementById('pane-binance').classList.contains('active') &&
     !d.getElementById('pane-usdt').classList.contains('active'));

  // Copying has to go through the app: navigator.clipboard needs a secure
  // origin and execCommand is gone from newer WebViews, so a page-side copy
  // would silently do nothing on a file:// page.
  click(d.querySelector('#pane-binance .copy-btn'));
  ok('copy is handed to the app, not attempted in the page',
     calls.some((c) => c[0] === 'copy' && c[1] === BINANCE));
  // A mention in a comment is fine; an actual call is not.
  ok('and the page does not rely on execCommand',
     !/document\.execCommand\s*\(/.test(html));

  click(tabs[0]);
  click(d.querySelector('#pane-usdt .copy-btn'));
  ok('the USDT address copies too',
     calls.some((c) => c[0] === 'copy' && c[1] === USDT));

  click(d.getElementById('skip'));
  ok('skipping closes it without silencing it forever',
     calls.some((c) => c[0] === 'close' && c[1] === false));

  calls.length = 0;
  d.getElementById('dont-show').checked = true;
  click(d.getElementById('close'));
  ok('ticking the box reports it', calls.some((c) => c[0] === 'close' && c[1] === true));
}

// ------------------------------------------------------- the Donate button
//
// An amount is now required before the thank-you can trigger. The card shows
// "Thank you" first at full size, then shrinks into a small round checkmark
// badge and auto-closes - it never claims money actually moved, since the
// app can neither take a payment nor verify one.
console.log('\nDonate: the amount now leads somewhere');
{
  const build = () => {
    const dom = new JSDOM(html, { runScripts: 'dangerously', pretendToBeVisual: true });
    const w = dom.window, d = w.document;
    const calls = [];
    w.DBSupport = {
      copy(t) { calls.push(['copy', t]); },
      close(x) { calls.push(['close', x]); },
      donated() { calls.push(['donated']); },
    };
    d.dispatchEvent(new w.Event('DOMContentLoaded'));
    return { w, d, calls };
  };
  const tap = (w, el) => el.dispatchEvent(new w.Event('click', { bubbles: true }));

  const a1 = build();
  const hasBtn = !!a1.d.getElementById('donate');
  ok('there is a Donate button', hasBtn);
  if (!hasBtn) {
    for (const n of ['thank-you hidden first', 'pressing shows it',
                     'app told', 'no instant close',
                     'empty amount is rejected', 'invalid amount shakes',
                     'skip is not donating', 'skip does not silence',
                     'tick is drawn', 'reduced motion',
                     'donate stops the prompt', 'decision recorded'])
      ok(n, false, 'no Donate button');
  } else {
  ok('the thank-you is hidden until it is pressed',
     !a1.d.getElementById('thanks').classList.contains('show'));

  // No amount: the field shakes and nothing fires.
  tap(a1.w, a1.d.getElementById('donate'));
  ok('an empty amount is rejected, not silently accepted',
     !a1.d.getElementById('thanks').classList.contains('show') &&
     !a1.calls.some((c) => c[0] === 'donated'));
  ok('the amount row is marked with an error to shake',
     a1.d.querySelector('.amount-row').classList.contains('error'));

  const a2 = build();
  a2.d.getElementById('amount').value = 'nonsense';
  tap(a2.w, a2.d.getElementById('donate'));
  ok('a non-numeric amount is rejected the same way',
     !a2.d.getElementById('thanks').classList.contains('show') &&
     !a2.calls.some((c) => c[0] === 'donated'));

  const a3 = build();
  a3.d.getElementById('amount').value = '25';
  tap(a3.w, a3.d.getElementById('donate'));
  ok('a valid amount shows the thank-you',
     a3.d.getElementById('thanks').classList.contains('show'));
  ok('the app is told', a3.calls.some((c) => c[0] === 'donated'));
  ok('and it does not close instantly, so the animation can play',
     !a3.calls.some((c) => c[0] === 'close'));

  // Skipping is not donating.
  const a4 = build();
  tap(a4.w, a4.d.getElementById('skip'));
  ok('skipping does not count as donating',
     !a4.calls.some((c) => c[0] === 'donated'));
  ok('and does not silence the prompt',
     a4.calls.some((c) => c[0] === 'close' && c[1] === false));

  ok('the tick draws itself rather than loading an image',
     /stroke-dashoffset/.test(html) && !/<img/.test(html));
  ok('the animation is dropped for users who ask for less motion',
     /prefers-reduced-motion[\s\S]{0,400}animation:none !important/.test(html));

  // --------------------------------------------------- the celebration
  //
  // Two beats, driven by explicit state rather than chained CSS delays:
  // the "Thank you" message shows at full card size, then the card shrinks
  // into a round badge with a checkmark that draws itself in.

  const a5 = build();
  a5.d.getElementById('amount').value = '10';
  tap(a5.w, a5.d.getElementById('donate'));

  ok('the popup enters the thanking state, hiding the form',
     a5.d.querySelector('.popup').classList.contains('thanking'));
  ok('the thank-you message is shown first',
     a5.d.getElementById('thanks-message').classList.contains('show'));
  ok('the card has not shrunk into the badge yet',
     !a5.d.querySelector('.popup').classList.contains('success'));

  ok('a ring flash and tick badge exist for the second beat',
     !!a5.d.querySelector('.ring-flash') && !!a5.d.querySelector('.tick-wrap'));
  ok('the tick is a self-drawing stroked path, not an image',
     /stroke-dasharray/.test(html) && !/<img/.test(html));

  ok('the two-stage handoff is driven by JS state, not stacked CSS delays',
     /MESSAGE_HOLD_MS/.test(html) && /setTimeout/.test(html));
  ok('the card actually shrinks into a round badge in the success state',
     /\.popup\.success\{[\s\S]{0,200}border-radius:50%/.test(html));

  // This is the part that decides whether it feels smooth on a phone.
  // transform and opacity are composited; width, top, height, margin and
  // box-shadow force a layout or a repaint on every single frame.
  {
    const frames = [...html.matchAll(/@keyframes\s+(\w+)\s*\{((?:[^{}]|\{[^{}]*\})*)\}/g)];
    const allowed = new Set(['transform', 'opacity', 'stroke-dashoffset']);
    const offenders = [];
    for (const [, name, bodyText] of frames) {
      for (const prop of new Set([...bodyText.matchAll(/([a-z-]+)\s*:/g)].map((m) => m[1]))) {
        if (!allowed.has(prop)) offenders.push(name + ':' + prop);
      }
    }
    ok('every keyframe animates only composited properties',
       frames.length >= 3 && offenders.length === 0, offenders.join(', '));
  }

  // A keyboard sliding away resizes the WebView underneath the animation,
  // which is exactly what makes it stutter on a real device.
  ok('the amount field is blurred first, so no keyboard slides away mid-animation',
     /activeElement\.blur\(\)[\s\S]{0,400}classList\.add\('thanking'\)/.test(html));

  ok('it closes automatically after the badge has had time to settle',
     /setTimeout\(done, MESSAGE_HOLD_MS \+ SHRINK_SETTLE_MS/.test(html));
  ok('and it can only close once', /if \(closed\) return/.test(html));

  ok('pressing Donate stops the prompt for good',
     /fun donated\(\)[\s\S]{0,220}supportHidden = true/.test(prompt));
  ok('and the decision is recorded', /supportDonatedAt/.test(prompt) &&
     /supportDonatedAt/.test(prefs));
  }
}

console.log('\nit is wired in, and it does not nag');
{
  ok('the dialog is implemented', prompt.length > 0);
  ok('the box is remembered', /supportHidden/.test(prefs) && /supportHidden = true/.test(prompt));
  ok('and it silences the automatic prompt',
     /if \(prefs\.supportHidden\) return false/.test(prompt));
  ok('but never the About entry, which was asked for explicitly',
     /fun showNow\(activity: Activity\)[\s\S]{0,200}present\(/.test(prompt) &&
     !/fun showNow[\s\S]{0,200}supportHidden/.test(prompt));

  ok('it waits until the app has actually been in use for a few days',
     /DAYS_BEFORE_ASKING/.test(prompt) &&
     /prefs\.firstLaunchAt/.test(prompt));

  // The gate used to be a launch count, which meant someone who opened the
  // app once a day still needed several cold starts before being asked.
  // Gating on elapsed time since the first launch means it fires on
  // schedule regardless of how often the app is opened in between.
  {
    const days = /DAYS_BEFORE_ASKING = (\d+)L/.exec(prompt);
    const gap = /DAYS_BETWEEN_ASKS = (\d+)L/.exec(prompt);
    ok('it waits at least a few days after install',
       !!days && Number(days[1]) >= 1, days ? days[1] : 'absent');
    ok('and at most once a day between asks',
       !!gap && Number(gap[1]) <= 1, gap ? gap[1] : 'absent');

    // Drive the real rule rather than trusting the constants in isolation.
    const WAIT = Number(days[1]), DAY = 24 * 60 * 60 * 1000;
    const GAP = Number(gap[1]) * DAY;
    const show = (st, now) => {
      if (st.hidden) return false;
      if (st.firstLaunchAt === 0 || now - st.firstLaunchAt < WAIT * DAY) return false;
      if (now - st.lastShown < GAP) return false;
      st.lastShown = now;
      return true;
    };
    const st = { hidden: false, firstLaunchAt: 0, lastShown: 0 };
    ok('never shown before the first launch is stamped', show(st, DAY) === false);
    st.firstLaunchAt = DAY;
    ok('still silent the day after install', show(st, st.firstLaunchAt + DAY) === false);
    const dueAt = st.firstLaunchAt + WAIT * DAY;
    ok('shows once the wait has elapsed', show(st, dueAt) === true);
    ok('reopening the same day does not', show(st, dueAt + 3600e3) === false);
    ok('the next day does', show(st, dueAt + DAY + 1) === true);

    const off = { hidden: true, firstLaunchAt: 1, lastShown: 0 };
    ok('the checkbox stops it for good', show(off, 999 * DAY) === false);
  }

  ok('it is offered soon after the feed paints, not minutes later',
     /}, 3500\)/.test(main));
  ok('the first launch is stamped once per install', /firstLaunchAt = System\.currentTimeMillis\(\)/.test(app));
  ok('and it does not come back straight away', /DAYS_BETWEEN_ASKS/.test(prompt));

  ok('About offers it', /support_dev/.test(aboutXml) &&
     /"support_dev"[\s\S]{0,200}SupportPrompt\.showNow/.test(settings));
  ok('it never lands on top of an update prompt',
     /UpdateWatcher\.pending == null/.test(main));
  ok('nor over a fullscreen video', /customView == null[\s\S]{0,120}SupportPrompt\.maybeShow/.test(main));
  ok('and only once per session', /supportAsked = true/.test(main));

  ok('tapping outside does not count as "don\'t show again"',
     /setCanceledOnTouchOutside\(true\)/.test(prompt) &&
     !/setCanceledOnTouchOutside[\s\S]{0,200}supportHidden = true/.test(prompt));
  ok('the WebView is destroyed with the dialog', /web\.destroy\(\)/.test(prompt));
  ok('the page cannot reach the filesystem',
     /allowFileAccess = false/.test(prompt));
}

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);

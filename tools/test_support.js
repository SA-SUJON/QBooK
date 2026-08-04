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
  ok('the amount is marked optional', /Amount \(optional\)/.test(html));
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

console.log('\nit is wired in, and it does not nag');
{
  ok('the dialog is implemented', prompt.length > 0);
  ok('the box is remembered', /supportHidden/.test(prefs) && /supportHidden = true/.test(prompt));
  ok('and it silences the automatic prompt',
     /if \(prefs\.supportHidden\) return false/.test(prompt));
  ok('but never the About entry, which was asked for explicitly',
     /fun showNow\(activity: Activity\)[\s\S]{0,200}present\(/.test(prompt) &&
     !/fun showNow[\s\S]{0,200}supportHidden/.test(prompt));

  ok('it waits until the app has been used',
     /MIN_LAUNCHES_BEFORE_ASKING/.test(prompt) &&
     /prefs\.launchCount < MIN_LAUNCHES_BEFORE_ASKING/.test(prompt));

  // The gate was eight launches and a fortnight between asks, which meant
  // the prompt never actually appeared - the feature was wired up and inert.
  // What was asked for is: it shows when the app opens, and the checkbox is
  // what stops it. Only the very first launch is still held back.
  {
    const min = /MIN_LAUNCHES_BEFORE_ASKING = (\d+)/.exec(prompt);
    const days = /DAYS_BETWEEN_ASKS = (\d+)L/.exec(prompt);
    ok('it appears from the second launch, not the eighth',
       !!min && Number(min[1]) <= 2, min ? min[1] : 'absent');
    ok('and at most once a day, not once a fortnight',
       !!days && Number(days[1]) <= 1, days ? days[1] : 'absent');

    // Drive the real rule rather than trusting the constants in isolation.
    const MIN = Number(min[1]), DAY = 24 * 60 * 60 * 1000;
    const GAP = Number(days[1]) * DAY;
    const show = (st, now) => {
      if (st.hidden) return false;
      if (st.launches < MIN) return false;
      if (now - st.lastShown < GAP) return false;
      st.lastShown = now;
      return true;
    };
    const st = { hidden: false, launches: 1, lastShown: 0 };
    ok('the very first launch is silent', show(st, DAY) === false);
    st.launches = 2;
    ok('the second launch shows it', show(st, DAY) === true);
    ok('reopening the same day does not', show(st, DAY + 3600e3) === false);
    ok('the next day does', show(st, 2 * DAY + 1) === true);

    const off = { hidden: true, launches: 50, lastShown: 0 };
    ok('the checkbox stops it for good', show(off, 999 * DAY) === false);
  }

  ok('it is offered soon after the feed paints, not minutes later',
     /}, 3500\)/.test(main));
  ok('launches are counted once per process', /launchCount = prefs\.launchCount \+ 1/.test(app));
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

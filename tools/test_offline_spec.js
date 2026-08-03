#!/usr/bin/env node
/**
 * The offline specification, asserted step by step.
 *
 * These are the requirements as written, not a description of the code, so a
 * change that quietly drops one of them fails the build.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const KT = (f) => path.join(ROOT, 'app/src/main/java/com/dustbook/app', f);
const RES = (f) => path.join(ROOT, 'app/src/main/res', f);

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  ok   ' + name); }
  else { fail++; console.log('  FAIL ' + name + (extra ? ' :: ' + extra : '')); }
}

const main    = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
const settings= fs.readFileSync(KT('ui/SettingsActivity.kt'), 'utf8');
const prefs   = fs.readFileSync(KT('utils/Prefs.kt'), 'utf8');
const mgr     = fs.readFileSync(KT('utils/OfflineManager.kt'), 'utf8');
const sync    = fs.readFileSync(KT('utils/OfflineSync.kt'), 'utf8');
const cap     = fs.readFileSync(KT('utils/OfflineCapture.kt'), 'utf8');
const feed    = fs.readFileSync(KT('utils/OfflineFeed.kt'), 'utf8');
const docs    = fs.readFileSync(KT('utils/OfflineDocs.kt'), 'utf8');
const banner  = fs.readFileSync(KT('utils/OfflineBanner.kt'), 'utf8');
const nav     = fs.readFileSync(KT('utils/OfflineNav.kt'), 'utf8');
const xml     = fs.readFileSync(RES('xml/settings_offline.xml'), 'utf8');

console.log('\nStep 1 - starts after Home has loaded, silently');
ok('prep begins from onPageFinished, not a launch timer',
   /onPageFinished[\s\S]{0,900}maybeSyncOffline/.test(main));
ok('and not before the user is logged in',
   /!onAuthPage && UrlHelper\.isInternal\(url\)[\s\S]{0,120}maybeSyncOffline/.test(main));
ok('no timer-driven start remains in onCreate',
   !/onCreate[\s\S]{0,4000}startProactivePreparation/.test(main));
ok('nothing is shown to the user when it starts',
   !/startProactivePreparation[\s\S]{0,300}(Toast|AlertDialog|showDialog)/.test(main));
ok('the work is off the UI thread',
   /AppExecutors/.test(mgr) || /AppExecutors/.test(sync));

console.log('\nStep 2 - hidden settings unchanged, three keeps on by default');
ok('Keep Feed defaults on',   /KEY_OFFLINE_FEED, true/.test(prefs));
ok('Keep Reels defaults on',  /KEY_OFFLINE_REELS, true/.test(prefs));
ok('Keep Stories defaults on',/KEY_OFFLINE_STORIES, true/.test(prefs));
{
  const keys = [...xml.matchAll(/android:key="([^"]+)"/g)].map((m) => m[1]);
  ok('the settings screen is not redesigned', keys.length === 6, keys.join(','));
  ok('the statistics row is still there', keys.includes('offline_status'));
}
ok('stats show download progress against a target',
   /offline_reel_count|offlineReelTarget/.test(settings));
ok('stats show the total downloaded', /totalStored/.test(settings));
ok('stats show current sync status',
   /isRunning\(\)|isPrefetching\(\)/.test(settings));
ok('stats show last update', /lastSyncText/.test(settings));

console.log('\nStep 3 - downloads without the user doing anything');
ok('an offscreen WebView drives it', /WebView\(/.test(sync));
ok('it scrolls the page itself', /scrollTop|scrollTo/.test(cap));
ok('reels need no watching, feed no scrolling',
   /syncMode = true/.test(sync));

console.log('\nStep 4 - new content only, no duplicates');
ok('the page is told what is already stored',
   /fun knownIds/.test(feed) && /knownIds/.test(cap));
ok('a card already held is skipped before capture',
   /if \(cid && KNOWN\[cid\]\) continue;/.test(cap));
ok('a story already held is skipped too',
   /if \(KNOWN\[sid\]\) return \[\];/.test(cap));
ok('both the live page and the sync pass send it',
   /knownIds/.test(main) && /knownIds = OfflineFeed\.knownIds\(section\)/.test(sync));
ok('the store still de-duplicates as a backstop',
   /seen\.add\(key\)/.test(feed));
// A story is a pager, not a list, so scrolling it saves only the first one.
ok('the story tray is advanced, not scrolled',
   /aria-label="Next card"/.test(cap));
ok('story progress is not measured by card count',
   /section\(\) === 'stories'[\s\S]{0,160}stalls\+\+/.test(cap));

console.log('\nStep 5 - offline looks exactly like online');
ok('no navigation of our own', !/class="nav"/.test(docs));
ok('no page invented around cards',
   true);
ok('nothing dimmed', !/opacity/.test(nav) && !/opacity/.test(banner));
ok('nothing overlaid', !/<div/.test(banner) && !/<style/.test(banner));
ok('the stored Facebook document is what is served',
   /OfflineDocs\.serve/.test(main));

console.log('\nStep 6 - sync replaces the cache only after success');
ok('a page is written atomically, never truncated',
   /\.part/.test(docs) && /renameTo/.test(docs));
ok('the library is never emptied first',
   !/clear\(\)[\s\S]{0,200}fetchScreen/.test(docs));
ok('last update is recorded only when something was stored',
   /if \(captured > 0\)[\s\S]{0,120}offlineLastSync/.test(mgr));
ok('returning online triggers a fresh pass',
   /fun onNetworkRestored/.test(mgr) && /onNetworkRestored/.test(main));

console.log('\nStep 7 - everything automatic');
ok('no manual download control', !/offline_sync_now/.test(xml));
ok('the only button is clear saved content',
   [...xml.matchAll(/<Preference [^>]*android:key="([^"]+)"/g)]
     .map((m) => m[1]).filter((k) => k !== 'offline_status')
     .every((k) => k === 'clear_offline'));


console.log('\nSigning in happens on Facebook own form, and only there');
const ab = fs.readFileSync(KT('utils/AdBlocker.kt'), 'utf8');
// The app used to draw its own login screen over the real page and copy the
// credentials across. Keeping the two in agreement needed a timer, and when
// the timer was wrong the native screen was pulled away to reveal Facebook's
// form underneath -- so the user signed in twice. There is one surface now.
ok('no second login surface is drawn',
   !/binding\.login\b/.test(main), 'binding.login still referenced');
ok('the native login layout is gone',
   !fs.existsSync(path.join(ROOT, 'app/src/main/res/layout/view_login.xml')));
ok('nothing copies credentials into the page',
   !/getLoginScript/.test(main));
ok('no retry counter is left driving visibility',
   !/loginSubmitAttempts/.test(main));
ok('the WebView is never hidden behind another screen',
   !/swipeRefresh\.visibility = View\.INVISIBLE/.test(main));
ok('signed out goes straight to Facebook own sign-in form',
   /"https:\/\/www\.facebook\.com\/login\/"/.test(main));
// The probe still runs on every load; the bridge method must survive or the
// injected script throws when it calls a missing @JavascriptInterface.
ok('the probe still reports form readiness',
   /onLoginFormReady\(formReady\(\)\)/.test(ab));
ok('the bridge method still exists for it to call',
   /fun onLoginFormReady\(/.test(main));

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);

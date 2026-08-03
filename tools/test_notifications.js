#!/usr/bin/env node
/**
 * Guards for the background notification check.
 *
 * There is no push channel available to a WebView wrapper - that would need a
 * Facebook app id and their servers delivering to ours. The only source of
 * truth is the notifications page itself, read in an offscreen WebView signed
 * in with the same cookies, which is the arrangement OfflineSync already uses.
 *
 * The failure modes worth guarding are all about restraint:
 *   - the first pass must be silent, or switching the feature on hands the
 *     user a burst of notifications for things they have already seen
 *   - a row already announced must never be announced twice
 *   - the offscreen WebView must be destroyed, and must never be the visible
 *     one
 *   - tapping must open the item, not a generic screen
 */
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const ROOT = path.join(__dirname, '..');
const KT = (f) => path.join(ROOT, 'app/src/main/java/com/dustbook/app', f);
const read = (f) => (fs.existsSync(f) ? fs.readFileSync(f, 'utf8') : '');

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  ok   ' + name); }
  else { fail++; console.log('  FAIL ' + name + (extra ? ' :: ' + extra : '')); }
}

const scraper = read(KT('utils/NotificationScraper.kt'));
const store = read(KT('utils/NotificationStore.kt'));
const presenter = read(KT('utils/NotificationPresenter.kt'));
const worker = read(KT('utils/NotificationWorker.kt'));
const prefs = read(KT('utils/Prefs.kt'));
const app = read(path.join(ROOT, 'app/src/main/java/com/dustbook/app/DustbookApplication.kt'));
const settings = read(KT('ui/SettingsActivity.kt'));
const manifest = read(path.join(ROOT, 'app/src/main/AndroidManifest.xml'));
const gradle = read(path.join(ROOT, 'app/build.gradle.kts'));
const browsingXml = read(path.join(ROOT, 'app/src/main/res/xml/settings_browsing.xml'));
const strings = read(path.join(ROOT, 'app/src/main/res/values/strings.xml'));

console.log('the pieces exist and are wired together');
{
  ok('the scraper exists', scraper.length > 0);
  ok('the seen-store exists', store.length > 0);
  ok('the presenter exists', presenter.length > 0);
  ok('the worker exists', worker.length > 0);
  ok('WorkManager is a dependency',
     /implementation\(libs\.androidx\.work\.runtime\)/.test(gradle));
  ok('there is a switch for it', /push_notifications/.test(browsingXml));
  ok('the switch is off by default',
     /val pushNotifications: Boolean get\(\) = sp\.getBoolean\(KEY_PUSH_NOTIFICATIONS, false\)/
       .test(prefs));
  ok('the schedule is re-asserted on process start',
     /NotificationWorker\.sync\(this, prefs\)/.test(app));
  ok('the switch schedules and cancels immediately',
     /NotificationWorker\.schedule\(ctx\)/.test(settings) &&
     /NotificationWorker\.cancel\(ctx\)/.test(settings));
  ok('POST_NOTIFICATIONS is declared',
     /android\.permission\.POST_NOTIFICATIONS/.test(manifest));
}

console.log('\nit does not shout on the first pass, or repeat itself');
{
  ok('the first pass only seeds', /isFirstRun/.test(worker) &&
     /markFirstRunDone/.test(worker));
  ok('and posts nothing while seeding',
     /if \(NotificationStore\.isFirstRun\(ctx\)\)[\s\S]{0,400}return Result\.success\(\)/
       .test(worker));
  ok('rows are recorded before anything is posted',
     worker.indexOf('NotificationStore.remember') <
     worker.indexOf('NotificationPresenter.post'));
  ok('only unseen rows are posted',
     /filter \{ it\.id\.isNotEmpty\(\) && !seen\.contains\(it\.id\) \}/.test(worker));
  ok('the seen set is capped', /MAX_KEPT/.test(store));
  ok('there is a ceiling on one pass', /MAX_PER_PASS/.test(presenter));
  ok('turning it off forgets what was seen',
     /NotificationStore\.clear\(ctx\)/.test(settings));
}

console.log('\nthe background pass cannot disturb the visible page');
{
  ok('it builds its own WebView', /WebView\(context\.applicationContext\)/.test(scraper));
  ok('it is laid out, or Facebook renders nothing',
     /w\.layout\(0, 0, 1080, 1920\)/.test(scraper));
  ok('it is destroyed when the pass ends', /web\?\.destroy\(\)/.test(scraper));
  ok('a stuck load still finishes', /TIMEOUT_MS/.test(scraper) &&
     /main\.postDelayed\(\{ finish\(emptyList\(\)\) \}, TIMEOUT_MS\)/.test(scraper));
  ok('only one pass at a time', /if \(running\)/.test(scraper));
  ok('it does nothing when signed out',
     /if \(!UrlHelper\.isLoggedIn\(\)\)/.test(scraper) &&
     /if \(!UrlHelper\.isLoggedIn\(\)\) return Result\.success\(\)/.test(worker));
  ok('it does nothing when the switch is off',
     /if \(!prefs\.pushNotifications\) return Result\.success\(\)/.test(worker));
  ok('the worker waits for the callback rather than returning early',
     /CountDownLatch/.test(worker) && /latch\.await/.test(worker));
  ok('fifteen minutes, the shortest period WorkManager allows',
     /INTERVAL_MINUTES = 15L/.test(worker));
  ok('the schedule is not restarted on every launch',
     /ExistingPeriodicWorkPolicy\.KEEP/.test(worker));
  ok('it only runs with a connection',
     /setRequiredNetworkType\(NetworkType\.CONNECTED\)/.test(worker));
}

console.log('\ntwo channels, so requests can be silenced separately');
{
  ok('activity and requests are separate channels',
     /CHANNEL_ACTIVITY/.test(presenter) && /CHANNEL_REQUESTS/.test(presenter));
  ok('the channel ids do not collide with the services',
     !/dustbook_audio|dustbook_sync/.test(presenter));
  ok('the notification ids do not collide either',
     /ID_BASE = 4000/.test(presenter));
  ok('no invented icon: it reuses the existing bell',
     /R\.drawable\.ic_bell/.test(presenter));
  ok('the channel names are strings, not literals',
     /notif_channel_activity/.test(strings) && /notif_channel_requests/.test(strings));

  // Routing is pure text, so it can be exercised directly.
  const fnBody = presenter.slice(presenter.indexOf('fun isFriendRequest'));
  const rules = [...fnBody.matchAll(/t\.contains\("([^"]+)"\)/g)].map((m) => m[1]);
  const isRequest = (s) => rules.some((r) => s.toLowerCase().includes(r));
  ok('a friend request routes to the requests channel',
     isRequest('Rahim sent you a friend request'));
  ok('an accepted request routes there too',
     isRequest('Karim accepted your friend request'));
  ok('a like routes to activity', !isRequest('Rahim likes your photo'));
  ok('a comment routes to activity',
     !isRequest('Karim commented on your post: nice one'));
  ok('a share routes to activity', !isRequest('Sadia shared your post'));
  ok('Bangla friend requests are recognised too',
     isRequest('রহিম আপনাকে বন্ধুত্বের অনুরোধ পাঠিয়েছেন'));
}

console.log('\ntapping opens the item itself');
{
  ok('the tap carries a VIEW intent', /Intent\.ACTION_VIEW/.test(presenter));
  ok('it targets MainActivity, which already handles deep links',
     /Intent\(context, MainActivity::class\.java\)/.test(presenter));
  ok('each row gets its own PendingIntent',
     /ID_BASE \+ index/.test(presenter));
  ok('the notification clears when tapped', /setAutoCancel\(true\)/.test(presenter));

  // resolveUrl is pure, so re-implement its cases from the source and check.
  const relOk = /if \(u\.startsWith\("\/"\)\) return "https:\/\/m\.facebook\.com\$u"/.test(presenter);
  ok('a relative href is made absolute', relOk);
  ok('an absolute href is left alone',
     /if \(u\.startsWith\("http:\/\/"\) \|\| u\.startsWith\("https:\/\/"\)\) return u/.test(presenter));
  ok('an unopenable litelink falls back to the notifications page',
     /return "https:\/\/m\.facebook\.com\/notifications"/.test(presenter));
}

console.log('\nthe reader survives markup it does not recognise');
{
  // Run the real reader script against both renderers.
  // The function is `= """ ... """`, not `return """ ... """`.
  const i = scraper.indexOf('private fun readScript');
  const start = scraper.indexOf('"""', i) + 3;
  const js = scraper.slice(start, scraper.indexOf('""".trimIndent()', start));

  const run = (html) => {
    const dom = new JSDOM(html, { runScripts: 'outside-only', url: 'https://m.facebook.com/' });
    const w = dom.window;
    let got = null;
    w.DBNotify = { onRows(json) { got = JSON.parse(json); } };
    w.eval(js);
    return got;
  };

  // Classic renderer: rows are links to the story.
  const classic = run(`<div id="root">
    <div data-sigil="notification unread">
      <a href="/story.php?story_fbid=123&notif_id=999&notif_t=like">
        Rahim likes your photo</a></div>
    <div data-sigil="notification">
      <a href="/story.php?story_fbid=124&notif_id=1000&notif_t=comment">
        Karim commented on your post</a></div>
  </div>`);
  ok('classic rows are read', Array.isArray(classic) && classic.length === 2,
     JSON.stringify(classic));
  ok('the story id is used as the row id',
     !!classic && classic[0].id === '999');
  ok('the row text comes through',
     !!classic && /likes your photo/.test(classic[0].text));
  ok('the link is kept for the tap target',
     !!classic && classic[0].url.indexOf('/story.php') === 0);
  ok('an unread row is marked unread', !!classic && classic[0].unread === true);

  // Nothing recognisable: must return an empty list, not throw.
  const empty = run('<div><p>no notifications here</p></div>');
  ok('unknown markup yields no rows, rather than wrong ones',
     Array.isArray(empty) && empty.length === 0);

  // Short strings are chrome, not notifications.
  const noise = run(`<div>
    <a href="/story.php?notif_id=1">ok</a>
    <a href="/story.php?notif_id=2">Rahim and 3 others liked your post</a>
  </div>`);
  ok('a too-short row is skipped',
     Array.isArray(noise) && noise.length === 1 &&
     /Rahim and 3 others/.test(noise[0].text));

  // The same row twice must only be reported once.
  const dupe = run(`<div>
    <a href="/story.php?notif_id=7">Rahim commented on your post</a>
    <a href="/story.php?notif_id=7">Rahim commented on your post</a>
  </div>`);
  ok('a duplicated row is only reported once',
     Array.isArray(dupe) && dupe.length === 1);
}

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);

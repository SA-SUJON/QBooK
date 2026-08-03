#!/usr/bin/env node
/**
 * Guards for automatic update prompting.
 *
 * The reported bug: a published release was only offered after the user went
 * into the hidden settings and checked by hand. The check ran once, in
 * MainActivity.onCreate, behind a twelve hour throttle, and the dialog could
 * only be drawn by that one Activity.
 *
 * These assertions pin the fixed shape so it cannot quietly regress.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const KT = (f) => path.join(ROOT, 'app/src/main/java/com/dustbook/app', f);

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  ok   ' + name); }
  else { fail++; console.log('  FAIL ' + name + (extra ? ' :: ' + extra : '')); }
}

const watcher = fs.readFileSync(KT('utils/UpdateWatcher.kt'), 'utf8');
const prompt = fs.readFileSync(KT('ui/UpdatePrompt.kt'), 'utf8');
const main = fs.readFileSync(KT('ui/MainActivity.kt'), 'utf8');
const appk = fs.readFileSync(KT('DustbookApplication.kt'), 'utf8');
const settings = fs.readFileSync(KT('ui/SettingsActivity.kt'), 'utf8');
const checker = fs.readFileSync(KT('utils/UpdateChecker.kt'), 'utf8');

console.log('\nUpdateWatcher');
ok('starts with the process, not one screen', appk.includes('UpdateWatcher.start(this)'));
ok('follows the foreground activity',
   watcher.includes('registerActivityLifecycleCallbacks') &&
   watcher.includes('onActivityResumed'));
ok('checks again while the app stays open', watcher.includes('POLL_INTERVAL_MS'));
ok('polls far more often than daily',
   /POLL_INTERVAL_MS\s*=\s*30L \* 60 \* 1000/.test(watcher));
ok('re-offers a known release on screen change',
   /onActivityResumed[\s\S]{0,400}pending != null[\s\S]{0,120}present\(\)/.test(watcher));
// V4 routes background work through AppExecutors instead of raw threads.
ok('never blocks the main thread',
   watcher.includes('AppExecutors.background.execute') ||
   watcher.includes('Thread {'));
ok('drops the activity reference on pause',
   watcher.includes('WeakReference') && watcher.includes('onActivityPaused'));
ok('re-verifies the version before prompting',
   /present\(\)[\s\S]{0,600}isNewer/.test(watcher));
ok('failures stay silent', /catch \(e: Exception\)/.test(watcher));

console.log('\nUpdatePrompt');
ok('works from any Activity', /fun show\(\s*activity: Activity/.test(prompt));
ok('is still mandatory',
   prompt.includes('setCancelable(false)') &&
   prompt.includes('setCanceledOnTouchOutside(false)'));
ok('offers no skip or later button',
   !/update_skip|update_later/.test(prompt));
ok('never stacks two dialogs', prompt.includes('isShowing()'));
ok('installs in the app, not a browser',
   prompt.includes('ApkInstaller.install') && !/ACTION_VIEW/.test(prompt));
ok('stops re-prompting once installed', prompt.includes('UpdateWatcher.clear()'));

console.log('\nWiring');
ok('MainActivity no longer owns the dialog',
   !main.includes('setCancelable(false)') && !main.includes('dialog_update'));
ok('MainActivity registers a presenter',
   main.includes('UpdateWatcher.presenter'));
ok('the old one-shot check is gone',
   !main.includes('maybeCheckForUpdate'));
ok('settings no longer closes itself to update',
   !/pendingUpdateCheck = true/.test(settings));
ok('settings shows the same prompt', settings.includes('UpdatePrompt.show'));
ok('version comparison is still numeric', checker.includes('fun isNewer'));

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);

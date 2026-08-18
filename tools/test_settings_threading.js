#!/usr/bin/env node
/**
 * Settings screen threading (the "hidden settings thamia jai" bug).
 *
 * What the device showed: opening the offline settings screen hitched, then
 * froze - worst while a download phase was running.
 *
 * The shipped 5.2.18 cause, proven statically here and quantitatively in the
 * JVM harness (docs/BUG3-HARNESS.md): refreshOfflineSize() ran
 * realPlayableCount() x3 + three recursive size walks + a full-store JSON
 * read per section ON THE MAIN THREAD - at screen open, at resume, and every
 * 2 seconds from the tick. (The bug report's "counts are already on a
 * background executor" was a misread: the executor two hundred lines above
 * wraps the update-check click, not this path. These tests exist so that
 * class of claim is never trusted again without a structural check.)
 *
 * The 5.2.19 fix: same expression, assembled on the background pool, with an
 * in-flight guard so the 2 s tick cannot stack overlapping refreshes (the
 * pool's caller-runs rejection policy would otherwise pull the disk work
 * back onto the main thread), and the Preference write posted back to the
 * main thread, where View-tree objects belong.
 *
 * Behaviour pins (not just text): the row's content, the tick cadence, and
 * every call site must be exactly the shipped ones; and the BASELINE source
 * (read from git, immutable) must still show the violation - so these pins
 * cannot go green by both sides rotting.
 */
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const ROOT = path.join(__dirname, '..');
const SET = path.join(ROOT, 'app/src/main/java/org/qbook/ui/SettingsActivity.kt');

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  ok   ' + name); }
  else { fail++; console.log('  FAIL ' + name + (extra ? ' :: ' + extra : '')); }
}

const src = fs.readFileSync(SET, 'utf8');

/** The body of refreshOfflineSize(), from signature to its closing brace. */
function bodyOf(source, signature) {
  const i = source.indexOf(signature);
  if (i < 0) return null;
  const j = source.indexOf('{', i);
  let depth = 0, k = j;
  for (; k < source.length; k++) {
    if (source[k] === '{') depth++;
    else if (source[k] === '}') { depth--; if (depth === 0) break; }
  }
  return source.slice(i, k + 1);
}

console.log('settings threading pins');

// ---------------------------------------------------------------- baseline
// The shipped tree (commit 72da7ce, v5.2.18) must still carry the bug, so
// "fixed" below is a real contrast, not a tautology. Read it straight from
// git - no one can edit history to make this pass.
let base = null;
try {
  base = execSync(
    'git -C "' + ROOT + '" show 72da7ce:app/src/main/java/com/dustbook/app/ui/SettingsActivity.kt',
    { encoding: 'utf8' });
} catch (e) { base = null; }
ok('baseline source readable from git', !!base);

if (base) {
  const bb = bodyOf(base, 'private fun refreshOfflineSize()');
  ok('baseline had counts directly in refreshOfflineSize (the bug)',
    !!bb && bb.indexOf('OfflineFeed.realPlayableCount') >= 0 &&
    bb.indexOf('AppExecutors.background') < 0);
  // ...and its call sites were the main-thread ones (onCreatePreferences,
  // onResume, and the view-posted tick - Android runs all three on main).
  ok('baseline called it from onResume', /override fun onResume\(\)[\s\S]{0,220}refreshOfflineSize\(\)/.test(base));
}

// ---------------------------------------------------------------- the fix
const nb = bodyOf(src, 'private fun refreshOfflineSize()');
ok('refreshOfflineSize exists', !!nb);

if (nb) {
  // The caller path (before the executor lambda) must not touch the disk.
  const execAt = nb.indexOf('AppExecutors.background.execute');
  ok('counts moved into AppExecutors.background.execute', execAt > 0);
  const caller = execAt > 0 ? nb.slice(0, execAt) : nb;
  ok('caller path has no realPlayableCount', caller.indexOf('realPlayableCount') < 0);
  ok('caller path has no sizeBytes', caller.indexOf('sizeBytes') < 0);
  ok('caller path keeps the same-row guard (offline_status lookup)',
    caller.indexOf('offline_status') >= 0);

  // In-flight guard: before the executor, and reset exactly where the
  // posted result lands (and on the early-not-attached return).
  ok('in-flight guard before executor',
    execAt > 0 && caller.indexOf('refreshInFlight.compareAndSet(false, true)') >= 0 &&
    caller.indexOf('compareAndSet') < nb.indexOf('compareAndSet') + 1);
  ok('guard released in the posted result',
    /runOnUiThread \{[\s\S]{0,200}refreshInFlight\.set\(false\)/.test(nb));
  ok('guard released when fragment is gone',
    (nb.match(/refreshInFlight\.set\(false\)/g) || []).length >= 2);

  // The count expression itself is byte-identical in operands: same three
  // sections, same two size sources, same status row fields.
  for (const needle of [
    'OfflineFeed.realPlayableCount(OfflineFeed.SECTION_REELS)',
    'OfflineFeed.realPlayableCount(OfflineFeed.SECTION_FEED)',
    'OfflineFeed.realPlayableCount(OfflineFeed.SECTION_STORIES)',
    'OfflineCache.sizeBytes() + OfflineFeed.sizeBytes()',
    'OfflineDocs.sizeBytes()',
    '"Posts: " + fc + " of " + postTarget',
    '"  \u2022  Reels: " + rc + " of " + reelTarget',
    'lastSyncText(p)']) {
    ok('row content unchanged: ' + needle.slice(0, 44), nb.indexOf(needle) >= 0);
  }

  // The detached-fragment trap: Fragment.getString() throws off-thread, and
  // a throw in the pool task would strand the in-flight flag. All fragment
  // or resource reads must sit on the caller side of the executor line.
  const bgRegion = execAt > 0 ? nb.slice(execAt) : '';
  ok('no getString inside the disk-work lambda', bgRegion.indexOf('getString(') < 0);
  ok('no lastSyncText inside the disk-work lambda', bgRegion.indexOf('lastSyncText(') < 0);
  ok('Prefs + strings resolved on caller',
    caller.indexOf('Prefs(') >= 0 && caller.indexOf('lastSyncText(') >= 0);

  // View-tree write happens on the main thread.
  ok('summary written inside runOnUiThread',
    /runOnUiThread \{[\s\S]{0,300}offline_status"\)\?\.summary/.test(nb));

  // Pool caller-runs protection: skip-don't-stack is inside the function,
  // so a slow refresh over a 2 s tick can never queue disk work onto main.
  ok('skip-dont-stack comment anchors the why', /caller-runs? rejection policy/i.test(nb) || /caller.runs/i.test(nb));
}

// Call sites unchanged: open, resume, tick - the same moments, the same cadence.
ok('still called from onCreatePreferences',
  /setPreferencesFromResource\(res, rootKey\)[\s\S]{0,800}refreshOfflineSize\(\)/.test(src));
ok('still called from onResume',
  /override fun onResume\(\)[\s\S]{0,200}refreshOfflineSize\(\)/.test(src));
ok('tick cadence unchanged (2 s)',
  (src.match(/postDelayed\(this, 2000\)|postDelayed\(tick!!, 2000\)/g) || []).length >= 1);

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);

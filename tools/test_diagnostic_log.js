#!/usr/bin/env node
/**
 * Tests for the in-app diagnostic log.
 *
 * The class lives in app/src/main/java/com/dustbook/app/utils/DiagnosticLog.kt
 * and is intentionally small: a file-backed ring buffer with a 5 MB cap.
 * The test pins the contract that production code relies on:
 *
 *   - when disabled, every write returns immediately (no file IO)
 *   - the file lives at cacheDir/diagnostic/dustbook.log
 *   - the file is capped at 5 MB and trimmed from the top when over
 *   - clear() deletes the file
 *   - readAll() returns the whole file, newest entry last
 *
 * The class itself is not loaded by the test (no JVM here) - the
 * pin reads the source and asserts the constants and the call
 * shape, exactly like test_native_behaviour.js does for the
 * inline gates. A real device run is the only proof that the IO
 * itself is correct; this test catches regressions in the contract.
 */
const fs = require('fs');
const path = require('path');
const ROOT = path.join(__dirname, '..');

let pass = 0, fail = 0;
function ok(name, cond, info) {
  if (cond) { pass++; console.log('  PASS:', name); }
  else { fail++; console.log('  FAIL:', name, info ? '| ' + info : ''); }
}

const code = fs.readFileSync(
  path.join(ROOT, 'app/src/main/java/com/dustbook/app/utils/DiagnosticLog.kt'),
  'utf8'
);

console.log('=== Shape ===');
ok('class is named DiagnosticLog',
   /class DiagnosticLog\(private val appContext: Context\)/.test(code));
ok('has a write method taking tag and message',
   /fun write\(tag: String, message: String\)/.test(code));
ok('has a readAll method',
   /fun readAll\(\): String/.test(code));
ok('has a clear method',
   /fun clear\(\)/.test(code));
ok('has a path method',
   /fun path\(\): String/.test(code));
ok('has an enabled flag',
   /@Volatile var enabled: Boolean/.test(code));

console.log('');
console.log('=== The disabled path returns immediately ===');
ok('write returns at once when enabled is false',
   /if \(!enabled\) return[\s\S]{0,20}val line = formatLine/.test(code));

console.log('');
console.log('=== File location and cap ===');
ok('the file lives under cacheDir',
   /File\(appContext\.cacheDir, "diagnostic"\)/.test(code));
ok('the file is named dustbook.log',
   /return File\(dir, "dustbook\.log"\)/.test(code));
ok('the cap is 5 MB',
   /MAX_BYTES = 5L \* 1024 \* 1024/.test(code));
ok('trim drops 256 KB at a time',
   /TRIM_BYTES = 256L \* 1024/.test(code));

console.log('');
console.log('=== Thread safety ===');
ok('uses a ReentrantLock',
   /private val lock = ReentrantLock\(\)/.test(code));
ok('write takes the lock',
   /lock\.withLock \{[\s\S]{0,200}file\(\)\.appendText/.test(code));
ok('readAll takes the lock',
   /fun readAll\(\): String = lock\.withLock/.test(code));
ok('clear takes the lock',
   /fun clear\(\)[\s\S]{0,40}lock\.withLock/.test(code));

console.log('');
console.log('=== Safety: never crash the app ===');
ok('appendText is wrapped in try/catch',
   /file\(\)\.appendText\(line, Charsets\.UTF_8\)[\s\S]{0,200}catch \(e: Exception\)/.test(code));
ok('readAll is wrapped in try/catch',
   /fun readAll[\s\S]{0,300}catch \(e: Exception\)/.test(code));
ok('clear is wrapped in try/catch',
   /fun clear[\s\S]{0,200}catch \(e: Exception\)/.test(code));

console.log('');
console.log('=== Line format ===');
ok('every entry is timestamped',
   /SimpleDateFormat\("MM-dd HH:mm:ss\.SSS"/.test(code));
ok('every entry includes the tag',
   /"\$ts \$tag: \$safe\\n"/.test(code));
ok('newlines in the message are replaced with spaces',
   /message\.replace\('\\n', ' '\)\.replace\('\\r', ' '\)/.test(code));

console.log('');
console.log('=== Wiring ===');
ok('Prefs exposes diagnosticLog accessor',
   /val diagnosticLog: Boolean[\s\S]{0,200}diagLog\.enabled/.test(
     fs.readFileSync(path.join(ROOT, 'app/src/main/java/com/dustbook/app/utils/Prefs.kt'), 'utf8')
   ));
ok('Settings wires the switch to prefs.diagnosticLog',
   /prefs\.diagnosticLog = v as Boolean/.test(
     fs.readFileSync(path.join(ROOT, 'app/src/main/java/com/dustbook/app/ui/SettingsActivity.kt'), 'utf8')
   ));
ok('Settings wires the three buttons',
   /view_diagnostic_log[\s\S]{0,200}DiagnosticLogActivity/.test(
     fs.readFileSync(path.join(ROOT, 'app/src/main/java/com/dustbook/app/ui/SettingsActivity.kt'), 'utf8')
   ) &&
   /share_diagnostic_log[\s\S]{0,500}ACTION_SEND/.test(
     fs.readFileSync(path.join(ROOT, 'app/src/main/java/com/dustbook/app/ui/SettingsActivity.kt'), 'utf8')
   ) &&
   /clear_diagnostic_log[\s\S]{0,200}prefs\.diagLog\.clear/.test(
     fs.readFileSync(path.join(ROOT, 'app/src/main/java/com/dustbook/app/ui/SettingsActivity.kt'), 'utf8')
   ));

console.log('');
console.log('=== Write-site count (sanity, not a contract) ===');
const writeSites = (
  fs.readFileSync(path.join(ROOT, 'app/src/main/java/com/dustbook/app/ui/MainActivity.kt'), 'utf8')
).match(/prefs\.diagLog\.write\(|this@MainActivity\.prefs\.diagLog\.write\(/g) || [];
console.log('  write call sites in MainActivity: ' + writeSites.length);
ok('at least 15 write sites (lifecycle + webview + bridge + relayout)',
   writeSites.length >= 15,
   'got ' + writeSites.length);

console.log('');
console.log(pass + ' passed, ' + fail + ' failed');
process.exit(fail === 0 ? 0 : 1);

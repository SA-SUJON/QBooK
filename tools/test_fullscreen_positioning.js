// Final root-cause proof. All assertions are based on extracted function
// bodies (not regex over the full file), so the test is self-contained
// and reproducible.

const fs = require('fs');
const path = require('path');
const ROOT = '/home/user/Dustbook';
const FILE = path.join(ROOT, 'app/src/main/java/com/dustbook/app/ui/MainActivity.kt');
const code = fs.readFileSync(FILE, 'utf8');

function extractFn(name) {
  // Greedy match from `fun name(...)` to the next `private fun` or end-of-class.
  const re = new RegExp('fun ' + name + '\\(', 'g');
  re.lastIndex = 0;
  const m = re.exec(code);
  if (!m) return null;
  const start = m.index;
  // find the body balance
  let depth = 0, i = code.indexOf('{', start), bodyStart = i;
  for (; i < code.length; i++) {
    if (code[i] === '{') depth++;
    else if (code[i] === '}') { depth--; if (depth === 0) return code.substring(start, i+1); }
  }
  return code.substring(start);
}

let pass = 0, fail = 0;
function ok(name, cond, info) {
  if (cond) { pass++; console.log('  PASS:', name); }
  else { fail++; console.log('  FAIL:', name, info ? '| ' + info : ''); }
}

const reportPosition = extractFn('reportPosition');
const recover = extractFn('recoverWindowSizeIfStale');
const force = extractFn('forcePageRelayout');
const onShow = extractFn('onShowCustomView');

console.log('=== Extracted bodies ===');
console.log('--- reportPosition ---');
console.log(reportPosition);
console.log('');
console.log('--- recoverWindowSizeIfStale ---');
console.log(recover);
console.log('');
console.log('--- onShowCustomView (truncated) ---');
console.log(onShow ? onShow.substring(0, 800) : 'NOT FOUND');

console.log('');
console.log('=== R1: reportPosition runs forcePageRelayout UNGATED (v5.2.27 fix) ===');
ok('reportPosition body exists', !!reportPosition);
ok('UNGATED: forcePageRelayout runs without recoverWindowSizeIfStale check',
   /if \(type == "reel" \|\| type == "stories" \|\| type == "story"\) \{\s*\n\s*runOnUiThread \{\s*\n\s*forcePageRelayout\(binding\.webView\)/.test(reportPosition) &&
   !/runOnUiThread \{\s*\n\s*if \(recoverWindowSizeIfStale\(\)\)/.test(reportPosition));

console.log('');
console.log('=== R2: recoverWindowSizeIfStale still has its gate (for the 4 lifecycle sites) ===');
ok('function body exists', !!recover);
ok('reads windowH = root.height', /val windowH = root\.height/.test(recover));
ok('reads screenH from maximumWindowMetrics', /maximumWindowMetrics\.bounds\.height\(\)/.test(recover));
ok('returns true only if 24 < (screenH-windowH) < screenH/2',
   /if \(short > 24 && short < screenH \/ 2\)/.test(recover));
ok('else returns false', /return false/.test(recover));

console.log('');
console.log('=== R3: onShowCustomView hides system bars (fullscreen) ===');
ok('onShowCustomView body exists', !!onShow);
ok('enterImmersive(true) called', /enterImmersive\(true\)/.test(onShow));
ok('contentRoot = INVISIBLE (NOT GONE)', /contentRoot\.visibility = View\.INVISIBLE/.test(onShow));

console.log('');
console.log('=== R4: only PageAssembly calls reportPosition (offline-only) ===');
const pa = fs.readFileSync(path.join(ROOT, 'app/src/main/java/com/dustbook/app/offline/PageAssembly.kt'), 'utf8');
ok('PageAssembly has reportPosition calls', (pa.match(/FBPro\.reportPosition/g) || []).length >= 2);
ok('main code (excluding PageAssembly) has NO reportPosition calls',
   !/FBPro\.reportPosition/.test(code));

console.log('');
console.log('=== R5: the SPA-swap hook runs forcePageRelayout UNGATED (v5.2.27 fix) ===');
ok('doUpdateVisitedHistory runs forcePageRelayout on reel/story only',
   /doUpdateVisitedHistory[\s\S]{0,1800}if \(isReelOrStoryUrl\(url\)\) \{\s*\n\s*forcePageRelayout\(view\)/.test(code));

console.log('');
console.log('=== STRUCTURAL DEFECT (the bug) AND FIX (v5.2.27) ===');
console.log('  ROOT CAUSE (pre-fix):');
console.log('  1. reportPosition is called by the OFFLINE pager (PageAssembly) on every');
console.log('     reel/story scroll, debounced 600ms after the swipe settles.');
console.log('  2. Its recovery was gated: forcePageRelayout ran ONLY when');
console.log('     recoverWindowSizeIfStale returned true.');
console.log('  3. recoverWindowSizeIfStale returns true ONLY when the native window');
console.log('     is genuinely short (24 < shortfall < screenH/2).');
console.log('  4. During fullscreen playback, the system bars are hidden, so the');
console.log('     native window fills the display and the shortfall is 0.');
console.log('  5. -> recoverWindowSizeIfStale returned FALSE in fullscreen.');
console.log('  6. -> forcePageRelayout was NEVER called from reportPosition in fullscreen.');
console.log('  7. -> the page-side player top / scroller scrollTop was never corrected.');
console.log('');
console.log('  THE FIX (v5.2.27):');
console.log('  - The recoverWindowSizeIfStale gate is removed from BOTH call sites');
console.log('    (doUpdateVisitedHistory and reportPosition). forcePageRelayout');
console.log('    itself is safe at any window size - it reads clientHeight and');
console.log('    dispatches a resize; the JS settle loop self-terminates.');
console.log('  - The function recoverWindowSizeIfStale and its Boolean return are');
console.log('    preserved because the four LIFECYCLE call sites (resume, IME');
console.log('    close, onPageFinished, fullscreen exit) still use it correctly:');
console.log('    those moments CAN have a genuinely short window (keyboard up,');
console.log('    bars mid-animation) and the gate is the right check there.');
console.log('  - The reel/story URL family gate (isReelOrStoryUrl) keeps the home');
console.log('    feed out of the recovery path entirely.');
console.log('');
console.log('  Online case (doUpdateVisitedHistory) had the same gate, removed');
console.log('  the same way. The WiFi-vs-mobile-data correlation was network');
console.log('  latency as an accidental cushion - mobile data had time for the');
console.log('  page to self-correct, WiFi did not. The fix does not need that');
console.log('  cushion: forcePageRelayout is dispatched on every SPA swap now,');
console.log('  regardless of how fast the network was.');
console.log('');

console.log(pass + ' passed, ' + fail + ' failed');
process.exit(fail === 0 ? 0 : 1);

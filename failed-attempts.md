# Reel Ads — All Failed Attempts & Current State

**Goal:** Reels tab-এ promoted/ad reels silently remove করা, user কোনো loading/blank/black screen/gap দেখবে না। Smooth scrolling।

**Context:** Facebook তাদের reel ad delivery change করেছে (Aug 2026). আগে:
```json
data-video-tracking='{"adid":"...","is_sponsored":1}'
data-testid="sponsored-story-photo"
```
এই markers ছিল — `MFacebookAds.kt` ধরতে পারত। এখন **সব marker সরিয়ে** organic reel-এর মতো করে দিয়েছে:
```json
data-video-tracking='{"qid":"...","mf_story_key":"...","top_level_post_id":"..."}'
```
একটাও ad marker নেই। একমাত্র পার্থক্য: **CTA buttons** (Order Now, Shop Now, Send Message, Learn More, Sign Up, Download) — organic reels-এ থাকে না।

---

## Attempts on v5.2.1 (Aug 5, 2026)

### Attempt 1 — Initial: `killReelCtaAds()` via `hideStory()`
**Commit:** `f520bc5`
**Approach:** CTA button text match → `hideStory(el, 8)` → full card hide.
**Why failed:** `hideStory()`-র ভিতরে `ctrls()` guard (`startCtrls + 3`) reel card-এ থেমে যায়। Reel card-এ Like/Comment/Share/CTA = 4-5 controls → `ctrls(parent) > startCtrls + 3` → break। Shudhu CTA button hide হয়, পুরা ad card থেকে যায়।
**Tests:** 54 passed.

---

### Attempt 2 — Direct climb + `hide(card)` → `display:none`
**Commit:** `688f6b7`
**Approach:** `hideStory` bypass → সরাসরি `hide(card)` → `display:none`.
**Why failed:** `display:none` element-কে layout থেকে সরিয়ে দেয়। `vscroller-snap` container-এর scrollHeight 700px কমে যায় → scroller মনে করে viewport overflow → **snap reset → preview/first reel-এ ফিরে যায়।**
**Symptom:** "5/7 reels scroll korar por loading hocche tarpor preview dekha reels ta abar asche"
**Tests:** 63 passed.

---

### Attempt 3 — `opacity:0` + `pointer-events:none`
**Commit:** `9516b90`
**Approach:** `display:none` না দিয়ে `opacity:0` + `pointer-events:none` — layout preserve করে, শুধু visual hide।
**Why failed:** Card height (700px) layout-এ থেকে যায় → **blank gap**। Scroll করলে 700px ফাঁকা জায়গা দেখা যায়।
**Symptom:** "ads card ta asche tobe full blank"
**Tests:** 63 passed.

---

### Attempt 4 — RAF loop for zero-delay detection
**Commit:** `74dc605`
**Approach:** `requestAnimationFrame` loop চালু — reel scroller থাকাকালীন প্রতি frame-এ `killReelCtaAds()`।
**Problem:** 250ms debounce + `requestIdleCallback` = ad 2-3 seconds flash. RAF 16ms per frame.
**Problem still:** opacity:0 → gap visible. RAF adds CPU overhead.
**Symptom:** "reels scroll korte korte abar sei loading ebar 1 second"
**Tests:** 63 passed.

---

### Attempt 5 — Synchronous MO callback + `killVideos()`
**Commit:** `059e0bc`
**Approach:** RAF সরিয়ে MutationObserver-এর callback-এই synchronously `killReelCtaAds()` + `killVideos()` (video pause + src remove before paint) + `opacity:0`.
**Why failed:** opacity:0 → blank gap still visible.
**Symptom:** "ads card ta asche tobe full blank"
**Tests:** 63 passed.

---

### Attempt 6 — `removeChild` + scrollTop recovery
**Commit:** `cc2b030`
**Approach:** `removeChild()` + scrollTop recalculate + RAF re-correct.
**Why failed:** Facebook-এর নিজস্ব snap handler আমাদের scrollTop-কে override করে → blank screen।
**Symptom:** "ads er screen a blank screen theke jacche"
**Tests:** 63 passed.

---

### Attempt 7 — `visibility:hidden` (CURRENT)
**Commit:** `a221aa6`
**Approach:** `visibility:hidden` + `pointer-events:none` + `killVideos()` synchronous in MO callback.
- `visibility:hidden` = layout space **preserved** (snap height unchanged ✅) AND visual rendering **disabled** ✅
- `opacity:0` gap সমস্যা সমাধান করে না (element এখনো paint হয়)
- `display:none` scrollHeight shrink করে
- `removeChild` Facebook handler override করে
**Current problem:** "koyekta reels scroll korle black/blank screen ashe ar loading screen asche"
**Tests:** 63 passed, all 10 suites 837 passed.

---

## Root Cause Analysis: কেন এখনো black screen + loading হয়?

### What we know:
1. **CTA detection works** — buttons match হয়, `card` পাওয়া যায় ✅
2. **killVideos() works** — video pause + src remove = no buffer ✅
3. **Synchronous MO fires** — DOM insert-এর same microtask-এ ✅
4. **`visibility:hidden`** — layout preserved ✅

### What we DON'T know (device-level):
| Unknown | Why it matters |
|---|---|
| Facebook snap handler timing | Our hide vs their layout calc race condition |
| vscroller-snap internal structure | Facebook may auto-remove hidden children after snap compute |
| WebView rendering pipeline | Chromium-এ visibility:hidden + pointer-events:none combination কেমন behave করে |
| Reel preloading | Reels tab আগে থেকেই 2-3 reels preload করে — ad card already rendered before our MO fires |

### Theories:
1. **WebView Chromium bug:** কিছু Chromium বিল্ড-এ `visibility:hidden` sibling-এর snap position misinterpret করে
2. **Facebook pre-render:** Reels pre-loaded before our script runs — card already painted
3. **Facebook auto-cleanup:** Hidden card detect করে Facebook নিজেই remove/relayout করে — যা blank screen trigger করে
4. **MO timing race:** document.body observe করার আগেই DOM elements insert — our callback fires for child but parent scroller not yet constructed

---

## Approaches NOT yet tried:

### A. `document_start` injection (WebView API)
`addDocumentStartJavaScript` — Facebook-এর কোনো JS run করার আগেই script inject। CTA text match এখানে কাজ করবে না (DOM নেই), কিন্তু CSS rule inject করা যায়:
```css
[data-mcomponent="MContainer"]:has(a:is(:not([href]):empty)) /* hide containers with dead CTA */ 
```
Problem: CSS `:has()` WebView version-dependent।

### B. `shouldInterceptRequest` — network level
Reel video request-এর URL pattern detect করে block। Ad reel-এর video URL-এ কিছু pattern থাকতে পারে। কিন্তু এটা verified না — URL capture লাগবে।

### C. CSS injection via `getStyleScript()`
`AdBlocker.getStyleScript()`-এ reel-specific CSS rule:
```css
.vscroller-snap > div:has([data-action-id]):not(:has([data-action-id*="like"])) { visibility:hidden !important; }
```
Problem: CSS-only detection fragile, false positives inevitable.

### D. `requestAnimationFrame` scroll observer
Scroll position monitor + card re-hide after Facebook handler overrides. Essentially fight fire with fire — but battery cost।

### E. Override Facebook snap handler
`vscroller-snap` element-এর scroll event listener-গুলো trace করে নিজস্ব handler ইনজেক্ট করা। Most aggressive, most risky.

---

## Current Code Structure (v5.2.1)

```
MutationObserver callback (fires BEFORE paint)
  │
  ├── scanAddedNodes(muts)
  │     └── killReelCtaAds(root)
  │           ├── querySelectorAll buttons
  │           ├── normCta() text match (PUA/bidi stripped)
  │           ├── closest('.vscroller-snap') → reel scroller check
  │           ├── climb to card (direct child of scroller)
  │           ├── killVideos(card) → pause + remove src
  │           └── visibility:hidden + pointer-events:none
  │
  └── schedule() → 250ms debounce → run()
        └── killSponsored() + killReelCtaAds() + killPromos() + killSection()
```

---

## Key Rule: Follow is NOT an ad marker

Real unfollowed page-এর organic reel-এও Follow button থাকে। Follow block করলে পুরা reels feed destroy হবে।

---

## Full Attempt History Summary

| # | Approach | Hide method | Problem |
|---|---|---|---|
| 1 | CTA → hideStory | display:none | ctrls guard stops at card |
| 2 | Direct climb → hide | display:none | Snap scroller reset |
| 3 | opacity:0 | opacity:0 | Blank gap visible |
| 4 | RAF loop | opacity:0 | Still gap + CPU overhead |
| 5 | Sync MO | opacity:0 + video kill | Still gap |
| 6 | removeChild | DOM remove + scroll fix | Facebook wins → blank |
| 7 | visibility:hidden | visibility:hidden + video kill | Black screen + loading after few scrolls |

---

## What needs to be solved:

1. **Black/blank screen:** `visibility:hidden` করার পরও scroll করতে করতে screen black হয়ে যায়
2. **Loading:** অ্যাড hide/remove করার সময় 1-2 second loading screen
3. **Smooth scrolling:** কোনো interruption ছাড়া reels scroll হবে
4. **No ad cards:** কোনো ad card (hidden or visible) reels feed-এ থাকবে না
5. **No snap reset:** Scroller position ঠিক থাকবে

---

> Generated: 2026-08-05 | Repo: build-rabbi/Dustbook | Branch: main | Tag: v5.2.1

---

# Offline Capture & Store — Solved (Aug 6, 2026, v5.2.2)

**Reported:**
1. Offline এ অনেক saved posts আসে না — text-only posts, albums with many images, videos missing from offline home feed.
2. Offline reels এ ads ও save হয়ে যাচ্ছে — must be filtered by CTA buttons.
3. Hidden settings-এর count full post save হওয়ার আগেই update হয়ে যায় — reels ও same rule হতে হবে.

Every fix below was first reproduced against the old code with a jsdom harness running the **production** capture/store logic (the same methodology as `tools/test_offline_*`), then the fix was verified by the same harness turning green. All suites: 870 passed, 0 failed.

## Root causes found (with proof)

| # | Root cause | Proof |
|---|---|---|
| R1 | **Store evicted older posts.** Live merges used `limit = max(reelTarget, 30)`, background step 1 used 50 — the store capped itself at the pass target, so every 50 scrolled posts deleted the 50 already downloaded. | Port simulation: store kept 50, first post gone |
| R2 | **`MAX_CARD_CHARS = 120_000` silently dropped big posts.** A photo album carries ~3 KB of markup per image (signed URL + srcset variants); ≈40+ images crosses 120 KB. | jsdom: 42-image card = 122 KB → old: dropped, new: saved |
| R3 | **`text.length < 12` floor dropped short text posts** whose avatars had not lazy-loaded yet (no media + short text = "not a post"). | jsdom: "Valo achi" post → old: dropped |
| R4 | **Blob-video posts dropped entirely** — media list empty while `src` is a pending `blob:`, short text → discarded. | jsdom: blob `<video>` card → old: dropped |
| R5 | **Premature completeness/count.** `images.any { has && !isAvatar }` → a five-photo post counted AND was served offline the moment its FIRST photo landed (4 blank frames). This is both "posts appear without images" and "count updates early". | Port: 3-photo post counted with 1 photo on disk |
| R6 | **Capture had no ad filter at all.** The blocker hides reel ads with `visibility:hidden` + marks them `data-fbpro-hidden`, but a hidden card's text and media remain fully readable, so every ad was copied into the offline library (video download included). | jsdom: CTA ad + marked ad both captured |

## Fixes

1. **`OfflineCapture.kt`**
   - `MAX_CARD_CHARS`: 120 KB → **400 KB** (a whole-page container is megabytes, so the guard still refuses containers).
   - New `isAdCard()`: skips cards condemned by the blocker (`data-fbpro-hidden`) in any section, **plus a CTA-text scan in the reels section only** — the exact word list (incl. Bangla) and the ≤24-char exact-match rule from the online killer in `AdBlocker.kt` (parity asserted by test). Scoped to reels so organic feed posts with e.g. an event "Sign up" control are never dropped.
   - Content floor relaxed: `text < 12` → `text < 6`, and a `<video>` element itself counts as content (blob URLs resolve later).
2. **`OfflineFeed.kt`**
   - Retention floor per section (`feed=500, reels=250, stories=200`): `keep = maxOf(passTarget, floor)` — the pass target now only controls *fetching*, never *forgetting*. Still bounded long-run.
   - `isFullyDownloaded()`: **per-photo completeness** — distinct photos are grouped by `photoKey()` (filename minus size markers; srcset variants of one photo collapse, different photos never merge) and every photo must have ≥1 cached variant. The settings count and the offline display share this rule, so both now wait for the whole post. Avatars, emoji sprites and other chrome are still not waited for, and reel videos still require the full-size file on disk.
3. Version 5.2.1 → **5.2.2 (112)**.

## Explicitly NOT touched (so no regressions)

- `AdBlocker.kt` and the whole online blocking path (18/18 ad tests, 306/306 native tests still pass).
- Settings screen structure — still exactly the 7 pinned keys; the count fix lives in the counting rule, not the UI.
- Story capture path, offline serve/inject pipeline, navigation, update flow.

> Session: 2026-08-06 | Tag: v5.2.2 | Tests: 870 passed, 0 failed (10 suites)

---

# Offline Display & Download Phases — Solved (Aug 6, 2026, v5.2.3)

**Reported (device):** settings বলে 37 posts saved, offline এ মাত্র 7-8 টা post দেখা যায়। Offline home feed videos mute থাকা উচিত নয়। Download ক্রম হবে: 10 posts → user-selected reels → posts to 300 → সব stories; প্রতিটি phase full download + playable হলেই count, আগে নয়। এছাড়া app crash report.

## Root cause of "37 counted, ~8 shown" (proof-driven)

প্রথমে ভুল hypothesis port করা ছিল (`${}` template-literal death) — সেটা production-code সঠিক port করলে **rule out** হয়: `$`, backtick, `$100`, `${var}` সব production escaping-এ survive করে। Actual proven killer:

- **Case-variant `</SCRIPT>` / `</ScrIpt>`** inside ANY stored card: the HTML parser closing a `<script>` block is case-insensitive, but the escape in OfflineInject/storyViewer was a **lowercase-only** `.replace("</script", ...)`. One card (e.g. a code snippet shared in a post) → the host script block ends there → **every card after it lost at parse time** → the page falls back to the ~8 server-rendered posts inside the stored document. Reproduced in jsdom against the verbatim production escaping: the whole bundle dies with `SyntaxError: Unexpected end of input`.

## Fixes

1. **JSON-array delivery (OfflineInject + storyViewer).** Cards no longer travel as one giant template literal. They go as a JSON array (one string per card) with every `</` neutralised to `<\/` — no interpolation exists (`${`, backticks inert), no closing sequence exists in ANY letter case, and one malformed card is isolated from the rest. The whole output is produced by `org.json.JSONArray` + `replace("</", "<\\/")`. New behavioural tests run the production script verbatim with nasty payloads (uppercase `</SCRIPT>`, inline template literals, `$100`, backticks) → 37/37 render, and a regression-contrast test shows the old approach losing the same bundle.
2. **`cardMarkupList(section)`** on OfflineFeed replaces the joined `cardsHtml` for display (count/display still share `realPlayableItems`).
3. **Offline home-feed videos default to sound on** (`unmuteByDefaultScript`, home screen only): `v.muted=false`, `defaultMuted=false`, `muted` attribute removed, once per video at rest before playback — never on a running clip (autoplay-mute safety documented). Reels/stories untouched.
4. **Download pipeline (BackgroundSyncManager) re-ordered to the exact spec:**
   - Step 1: first **10 posts** (`exactTotal=10`) → wait for downloads
   - Step 2: **reels = user's chosen amount** (exact) → wait
   - Step 3: feed continues **from 10 up to 300 total** (`exactTotal=300`) → wait
   - Step 4: **stories - all** (watched + unwatched) → wait
   - Every phase ends only after `awaitPrefetch(300_000)` drains its queue, and the feed count reads from disk - so the number the user sees is always what actually plays offline.
5. **`OfflineSync`:** new `exactTotal` parameter bounds the *batch* (and therefore the download queue), never the store: retention stays the store's floor problem. The capture script is handed the target **un-inflated** (the `coerceAtLeast(150)` override made a "first 10 posts" phase fill the store before moving on).
6. Version 5.2.2 → **5.2.3 (113)**.

## What was deliberately NOT touched

Online ad blocking, settings UI (7 pinned keys), story-viewer UI/overlay logic, resume/scroll logic, OfflineManager's proactive path (gets floor behaviour, no exact totals).

## Crash (reported: while scrolling) — fixed

**Mechanism:** `report()` fires from the scroll listener and handed ONE JSON string of up to several megabytes (up to `TARGET+20` cards, now up to 400 KB each) to the WebView Java bridge in a single call. Multi-megabyte Java-bridge payloads are a documented way to kill the app process on low-memory devices — and the trigger being *scrolling* matches the listener exactly.

**Fix:** `report()` now chunks by serialized size (~1 MB per bridge call); each chunk is a complete items array, merged and downloaded exactly as one call would be; the completion flag rides the final chunk. The old halves-retry stays as a last resort. jsdom proof: 6 × 260 KB cards → 2 calls, each < 1.2 MB, all 6 ids preserved, completion flag only on the last chunk.

> Session: 2026-08-06 | Tag: v5.2.3 | Tests all green locally (10 suites)

---

# Offline System Rebuilt From Scratch — Separate Vaults Per Section (Aug 7, 2026, v5.2.4)

**Reported (device, verbatim):** "dhur evabe hobe na ... kichui thik nai counting vul shon posts ashe na. home feed er jonno alada file create koro reels save er jonno alada fire create koro story er jonno alada file create koro shob system alada hobe but sync thakbe ekhon jemon ache counting real ar alada files theke asbe new vabe create koro shob offline system but ui naver change."

v5.2.3 proved its fixes in the harness, but the user still saw the wrong count and missing posts on device. The patient's problem was structural, so patching stopped and the subsystem was rebuilt as ordered.

## The two structural defects behind every report

1. **Count and bytes lived in two systems.** Items in `OfflineFeed`, media bytes in the LRU `OfflineCache` with a 500 KB video floor. Eviction trimmed counted items away; small-but-complete reels **never counted** (`hasMinSize(500_000)`); a count computed against a trimmed cache can lie both ways.
2. **Delivery was a runtime script.** Saved cards were moved into a stored Facebook document by JS at page-run time. `</SCRIPT>` in any letter case was only ONE of the ways that chain died; any runtime failure left the user staring at the document's own handful of server posts while the store said dozens.

## The rebuild (see offline-v2.md)

- Three files, three systems: `offline/HomeVault.kt`, `offline/ReelsVault.kt`, `offline/StoriesVault.kt` — each with its own store file (`offline_vaults/<section>/items.json`), its own media folder, its own download queue + worker, its own count.
- Shared engine `offline/SectionVault.kt`: media files are written `.part` and renamed in after the last byte — **existence == complete == playable == counted**. No size floors, no cache coupling (`OfflineCache` never consulted: isolation asserted in tests).
- `offline/PageAssembly.kt`: cards are composed INTO the stored document **in Kotlin before serving** (first child of the real `data-type="vscroller"` container; its stale server children hidden by one scoped stylesheet; graceful fallbacks when a document lacks the container). Card-borne `<script>`/`<base>` cut at compose time. If every script on the device failed, the saved posts would still render — they ARE the document now.
- `OfflineFeed` stays as the public facade (UI/bridges/pipeline say what they always said); `OfflineInject.kt` deleted.
- Settings screen (7 pinned keys), capture rules, the Facebook shell layer, and the 4-phase sync order: **unchanged**.

## Proof battery (local, 10 suites, 909 checks)

- End-to-end simulation of the reported bug: store of 3 items, disk filling step by step → **count == rendered cards at every step** (text 1 → +photo 2 → +reel 3), using verbatim-extracted production predicates.
- 37 nasty cards (uppercase `</SCRIPT>`, `${`, backticks, embedded `<script>`) statically composed and parsed with scripts enabled → **37/37 present, card scripts never execute**; regression contrast shows the old template-literal delivery still losing the same bundle.
- No-container documents still land the cards right after `<body>`, hiding nothing unrelated.
- Per-section isolation, retention floors, reels video-required filter, exact-phase batching, bridge chunking, pipeline order: re-proved against the new files.

> Session: 2026-08-07 | Tag: v5.2.4 | Tests: 909 passed, 0 failed (10 suites)

---

# Sync Phases Ran Concurrently Because the Wait Had a Timer (Aug 7, 2026, v5.2.5)

**Reported (device, verbatim, with screenshot):** "tomake ki bolechilam? first 10 post save Hobe tarpor posts save stop / tarpor reels download hobe complete hole ... but screenshot ta dekho 29 ta posts save hoyeche abar reels o same eksathe 3 ta save hoyeche emon keno? eksathe to save hobar kotha na?" — status row showed `Posts: 29 • Reels: 4 of 30` climbing **together**.

## Root cause (proven by code, not guessed)

`BackgroundSyncManager.awaitThen` waited on `OfflineFeed.awaitPrefetch(300_000)` — a fixed **5-minute wall clock**. Thirty reels of video (~150-360 MB on this connection) cannot finish in 5 minutes, so the timer fired and step 3 (posts → 300) started while the reel vault was still downloading. Each vault drains its own queue with its own worker, so the two sections climbed side by side. Live-scroll capture was ruled out first: `MainActivity.injectAll` explicitly never populates the store from the visible page.

## Fix

`awaitThen(section, next)` now waits on the actual vault for that section: loop { `vault.awaitIdle(60_000)`; break only when `pending() == 0 && !busy` }; a progress watchdog (no new completed file for 3 consecutive minutes) breaks instead of hanging on a dead network. No fixed timeout remains; phases are truly sequential.

> Session: 2026-08-07 | Tag: v5.2.5 | Tests: pipeline suite re-proved with progress-gating; 10/10 suites green locally

---

# Offline Blank-With-AdBlock + Tab Bar Missing From Its Place (Aug 7, 2026, v5.2.5)

**Reported (device, verbatim, with screenshot):** "offline mode a open kore dekhi screen pura blank 1 seconds er jonno eshe shob hide hoye geche ... AdBlocker off korle screen a content ashe but Facebook er je options gula thake reels chat notification etc. eita tar nijer jaigai nai" — screenshot: no header on top, Facebook's tab row (home/friends/watch-4/reels-15+/bell/shop, badges and all) sitting **between** two feed posts.

Every link below was reproduced in jsdom against the verbatim production scripts before any fix was written.

## Bug A — the blank screen, two links in one chain

1. **Ads leaked into the store through the sync WebView.** The capture's ad barrier checked only `data-fbpro-hidden` — the mark of the cosmetic pass that runs on the *visible* page. The background-sync WebView runs `MFacebookAds`, whose mark is `data-db-ad`. A sponsored post the sync page had *already condemned* was captured regardless (proven: `saved: [AD, post1, post2]` with the tag plainly present).
2. **One leaked ad condemned the whole batch.** Offline, `MFacebookAds.cardOf` looked for `vscroller` or `MScreen` above the ad element. On the composed page the saved cards sit in `#__db_cards` **beside** the hidden scroller, so the walk's boundary became the screen root — whose direct child from that node is the holder itself. `hide()` then removed `#__db_cards`: every saved post gone in one operation (proven: `style= display: none !important` on the holder ~ms after injection — the user's "1 second then everything hides").

## Bug B — the tab bar in the wrong place, also two links

1. **The real header was being hidden.** A captured home document opens its screen root (`data-mcomponent="MScreen"`) **before** the feed scroller, and `PageAssembly`'s single alternating regex matches the *earliest* such tag — so the holder became the screen's first child and the sibling rule `#__db_cards ~ *` hid **the pinned header and the tab bar with it** (proven: the header and the whole scroller both matched the selector).
2. **And a second tab bar was saved as a post.** `OfflineCapture.isChrome` never recognised the tab row: labels live on its buttons, not the row, and badge counters (`"15+ 15+ 4 15+"` — exactly the string in the user's store, proven) pass the six-character content floor. Offline it was served back inline between two posts — the screenshot.

## Fixes (no structure, no UI change)

- `OfflineCapture`: tab row detected by ≥2 tab-labelled buttons with no story link; `isAdCard` honours `data-db-ad` as well as `data-fbpro-hidden`.
- `MFacebookAds`: `cardOf` treats `[data-db-cards]` as a scroller-level boundary (nearest-ancestor, so online behaviour is byte-identical); `hide()` refuses the holder outright.
- `AdBlocker.getCosmeticScript`: `[data-db-cards]` is an `isContainer` stop node; `hide()` refuses it.
- `PageAssembly`: the **non-snap** feed scroller is matched first and receives the cards (only its own stale children hide; the header keeps its place). The reels snap pager (`vscroller-snap`) is excluded by a negative lookahead, so reels/stories assembly is **unchanged** from the version that already worked.
- `SectionVault`: junk markup (`data-db-ad` tags; the tab-row signature, feed section only) is filtered on add **and on read** — existing polluted stores heal themselves at first launch after the upgrade.

## Proof battery

- All three device symptoms reproduced before the fix and shown resolved after, with verbatim production scripts: compose placement (header survives, only stale scroller children hide), capture (tab row + condemned ad rejected), MFacebookAds (ad card hidden alone, holder untagged, organic posts survive), `getCosmeticScript` (holder is an untouchable stop node).
- 10/10 suites green locally (940 assertions, incl. 23 new pipeline + 5 new feed-guard checks pinning all of the above).
- `kotlinc` over the tree: identical fallout before/after — zero new compile errors introduced.

> Session: 2026-08-07 | Tag: v5.2.5 | Tests: 940 passed, 0 failed (10 suites)

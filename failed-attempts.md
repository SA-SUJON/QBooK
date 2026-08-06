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

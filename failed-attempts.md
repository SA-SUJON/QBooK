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

# Bug-3 harness: why the hidden settings screen froze

## The claim that needed proof

v5.2.18 and earlier: opening the offline settings screen hitched, then
froze — worst while a download phase was running ("settings e dhukle
thamia jai").

The bug report said the counts were already computed inside
`AppExecutors.background.execute` (SettingsActivity.kt:362) and therefore
"correctly threaded". **That was a misread.** Line 362 wraps the
update-check *click handler*. The refresh that runs at screen open, at
every resume, and **every 2 seconds** from the tick runnable
(`refreshOfflineSize()`) did all of this on the **main thread**:

- `OfflineFeed.realPlayableCount()` × 3 sections
  → per section: full `items.json` read + parse, then per entry
  `isComplete()` → `hasAsset(url)` → `File(...).exists() && length() > 0`
  per referenced media URL;
- `OfflineCache.sizeBytes() + OfflineFeed.sizeBytes() + OfflineDocs.sizeBytes()`
  → one flat listing plus three recursive `walkTopDown()` directory walks.

`tools/test_settings_threading.js` pins the old shape into the shipped
baseline (read from git, so history cannot be edited to pass) and the new
shape into the fix.

## The measurement

Verbatim extraction from production `SectionVault.kt` (`isComplete`,
`hasAsset`, `assetFile`, `hashFor`, `isVideoUrl`, `photoKey`, `isChrome`,
`isAvatar`, `sizeBytes`, the `completeItems` pipeline) compiled into a JVM
sim against a real on-disk fixture inside the app's own allowed settings
ranges: **200 posts + 50 reels + 120 stories** (≈690 media files, ≈320 MB),
plus a 300-file cache dir and 12 stored documents. `items.json` is ~2.2 MB
across the three sections — read per refresh; the JSON *parse* a phone
would do is **excluded**, so the OLD numbers below are a **lower bound**.

Caller-thread cost per refresh (40 timed runs after 15 warm):

| variant | median | p90 | max |
|---|---|---|---|
| OLD (as shipped, idle disk) | 12.6 ms | 17.3 ms | 25.6 ms |
| OLD (while a writer threads hammers the same media dir — i.e. a live download) | 13.8 ms | 19.5 ms | **36.1 ms** |
| NEW (this fix) | **0.00 ms** | 0.00 ms | 0.00 ms |

That is on a workstation NVMe where a syscall is nearly free. A budget
phone's eMMC/UFS with a video download in flight is several times slower,
and every millisecond of it sat on the UI thread — once at open, once at
resume, then **every 2 seconds** — on top of the excluded multi-megabyte
JSON parse. A frame deadline is 16 ms; the freeze the user saw is this
loop, hitched to a live download, eventually eating several frames in a
row.

The NEW row displays **byte-identical text** (verified: identical checksum
string OLD vs NEW), the tick cadence is unchanged (2 s), and every call
site is unchanged.

## The fix

`refreshOfflineSize()` now: resolves every fragment/resource value on the
caller (so a detached fragment cannot throw `getString` inside the pool
and strand the guard), then hands exactly the same disk expression to
`AppExecutors.background`, and posts the assembled line back with
`runOnUiThread`. An `AtomicBoolean` guard makes the 2 s tick **skip, not
stack**: the pool uses caller-runs rejection, so overlapping submissions
would pull the disk work back onto the main thread the fix just freed.

## Rerun

The harness is throwaway-by-design (Kotlin/JVM, needs `kotlinc`); the
permanent regression guard is the structural pin suite:
`node tools/test_settings_threading.js` (27 pins).

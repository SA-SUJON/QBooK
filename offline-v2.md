# Offline system, rebuilt (v5.2.4 / versionCode 114)

A ground-up rebuild of the offline subsystem, requested after repeated
reports: **"counting is wrong, saved posts never show up."**

The UI, the settings screen, the capture of Facebook's own markup, and the
download pipeline's order are unchanged. What changed is where saved
content lives, how it is counted, and how it is delivered to the screen.

## What was wrong

Two structural defects produced the reported symptoms:

1. **The count and the bytes lived in different systems.** Items sat in
   `OfflineFeed`, media bytes in `OfflineCache` (an LRU store with a 500 KB
   video minimum). Anything the cache evicted, rejected, or never finished
   made the settings count wrong in both directions: too high while
   downloads queued, too low after eviction, and small-but-complete reels
   never counted at all.

2. **Cards reached the screen via a runtime script** injected into a stored
   Facebook document. Any failure in that chain — one nasty card, one
   markup variant the container lookup did not recognise — and the user saw
   only the handful of server-rendered posts the document itself carried,
   while the count said dozens.

## The new design

Three independent systems, one file per section, synchronised by the
pipeline exactly as before:

| Section   | File (code)                    | Data on disk                                   |
|-----------|--------------------------------|------------------------------------------------|
| Home feed | `offline/HomeVault.kt`         | `offline_vaults/home/items.json` + `media/`    |
| Reels     | `offline/ReelsVault.kt`        | `offline_vaults/reels/items.json` + `media/`   |
| Stories   | `offline/StoriesVault.kt`      | `offline_vaults/stories/items.json` + `media/` |

- **`offline/SectionVault.kt`** — the engine every section runs on:
  its own store file, its own media folder, its own download queue and
  worker, its own count. Media files are written `.part` and renamed in
  after the last byte, so **a file that exists is a file that is complete
  and playable**. A reel counts the moment its video file exists — any
  real size, no arbitrary floor.
- **`offline/OfflineVaults.kt`** — the small registry: routes section ids,
  clears all three, serves whichever vault holds a requested URL, retires
  the old `offline_items_v1` store on first run.
- **`offline/PageAssembly.kt`** — static composition. Saved cards are
  inserted into the stored Facebook document **in Kotlin, before serving**,
  as the first child of the real feed container; the container's old
  grey/stale children are hidden by one scoped stylesheet. No runtime
  script stands between the user and their content: if every script on
  the page failed, the saved posts would still be on screen, because they
  ARE the document. Scripts smuggled inside captured cards are cut at
  compose time.

**Counting is real and comes from the files:** an item counts when every
media file it needs exists in its own section's media folder (photos by
srcset group, one variant each; one video; text immediately). The settings
row (`Posts • Reels • Stories`) reads `vault.count()`; the served page is
built from `vault.cards()` — **the same `completeItems()` list**, so the
number on screen and the number in settings cannot disagree.

## What is deliberately untouched

- The settings screen: exactly the same seven keys.
- Offline capture (`OfflineCapture`) and its ads/short-post/album rules.
- The stored Facebook documents and everything that gives offline the
  online look (nav taps, tap-swallowing, unmute strip/default, promo CSS,
  icon-font discovery) — plus the story viewer and scroll resume.
- The four-phase pipeline order: 10 posts → chosen reels → posts to 300 →
  stories, each phase completing only when its downloads are drained.
- The chrome cache (`OfflineCache`) for page furniture (CSS, fonts,
  avatars): not user content, not counted, mechanism unchanged.
- Online behaviour: vault stores and media are consulted only while
  offline, and only after the existing read/online guards.

## Migration

On first run the legacy `offline_items_v1` store is deleted (its counts
could never be trusted); the stored Facebook documents and the chrome
cache are kept. The next sync cycle rebuilds the library with real counts.

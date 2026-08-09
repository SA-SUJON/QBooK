package com.dustbook.app.offline

import android.content.Context
import android.webkit.WebResourceResponse
import com.dustbook.app.utils.BlockList
import com.dustbook.app.utils.NetworkPolicy
import com.dustbook.app.utils.OfflineDocs
import com.dustbook.app.utils.Prefs
import com.dustbook.app.utils.UrlHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Storage and download engine for exactly one offline section.
 *
 * This is the V3 storage layer. The old system kept item markup in one
 * place ([com.dustbook.app.utils.OfflineFeed]) and the media bytes in
 * another ([com.dustbook.app.utils.OfflineCache]). The count was computed
 * against the cache, which is an LRU store: anything it evicted - or never
 * accepted, like a video below its arbitrary 500 KB minimum - made the
 * number wrong in both directions. The settings count has been repeatedly
 * reported as honest while the offline screen showed far less.
 *
 * A vault keeps BOTH halves of a section, together, in its own folder:
 *
 *     filesDir/offline_vaults/<dir>/
 *         items.json     the captured cards (id, markup, media URLs)
 *         media/<sha256> one file per downloaded asset, plus a .mime sidecar
 *
 * Counting therefore needs no second system and no heuristics: an item is
 * counted when every media file it needs exists in the vault's own media
 * folder. Files are written to a .part name and renamed into place, so an
 * existing file is always a COMPLETE file - there is no such thing here as
 * a half-downloaded video that exists but does not play. "Counting real,
 * from the section's own files" is the design, not a promise.
 *
 * Downloading is per vault too: each section has its own queue and worker,
 * so the reel queue can never starve the feed queue - the systems are
 * separate - while the pipeline that drives them stays exactly as ordered.
 */
open class SectionVault(
    /** OfflineFeed section id: "feed", "reels" or "stories". Kept for callers. */
    val section: String,
    /** This vault's folder name under offline_vaults/. */
    private val dirName: String,
    /** Retention floor: a section never trims below this many entries. */
    private val keepFloor: Int,
    /** Reels: an entry without a playable video is not worth storing at all. */
    private val videoRequired: Boolean
) {

    /**
     * One saved piece of content, exactly as it arrived over the bridge.
     *
     * @param id    stable identity, so the same post is never stored twice.
     * @param html  Facebook's own markup for the card - nothing is rebuilt.
     * @param media every media URL the card references.
     * @param viewed    the user has already seen this one (on-device state
     *                  only, set by the offline pages' own view tracker -
     *                  no network is ever involved). Unviewed always
     *                  outranks viewed: it is served first and evicted last.
     * @param viewedAt  when it was marked, null while never viewed; only
     *                  used to keep the freshest viewed entries at the
     *              floor line during a reconnect eviction.
     */
    data class Entry(
        val id: String,
        val html: String,
        val media: List<String>,
        val viewed: Boolean = false,
        val viewedAt: Long? = null
    )

    private val pool = Executors.newFixedThreadPool(2)
    private val busy = AtomicBoolean(false)

    /** Media waiting to be fetched, and what is already in the queue. */
    private val queue = ArrayList<String>()
    private val queued = HashSet<String>()

    @Volatile private var root: File? = null
    @Volatile private var media: File? = null
    @Volatile private var appContext: Context? = null

    /** Reading is always allowed; [writeEnabled] gates collecting new content. */
    @Volatile var enabled: Boolean = true
    @Volatile var writeEnabled: Boolean = true

    /** Media stored by the last download pass, shown in hidden settings. */
    @Volatile var lastStored: Int = 0
        private set

    /** Files fetched since the app started, for the settings screen. */
    @Volatile var downloaded: Int = 0
        private set

    /**
     * Bytes of the file currently arriving, reset per file. The pipeline's
     * stall watchdog samples this every minute: a large reel on a slow
     * connection completes NO new file for several minutes but is plainly
     * alive here, and zero movement here - not zero new files - is what a
     * dead connection actually looks like.
     */
    @Volatile private var gaugeBytes: Long = 0L

    /** Bytes pulled so far for the transfer in flight (0 between files). */
    fun gaugeBytes(): Long = gaugeBytes

    fun init(context: Context) {
        appContext = context.applicationContext
        if (root != null) return
        synchronized(this) {
            if (root != null) return
            val d = File(context.filesDir, OfflineVaults.ROOT_DIR + "/" + dirName)
            if (!d.exists()) d.mkdirs()
            val m = File(d, "media")
            if (!m.exists()) m.mkdirs()
            media = m
            root = d
        }
    }

    private fun itemsFile(): File? {
        val dir = root ?: return null
        return File(dir, "items.json")
    }

    // ------------------------------------------------------------ storage

    /**
     * Merge newly captured entries into this vault's own file.
     *
     * Newest first, de-duplicated by id (falling back to a content key),
     * trimmed to maxOf(limit, keepFloor). Written atomically: a truncated
     * store is worse than a stale one.
     */
    fun addItems(incoming: List<Entry>, limit: Int, hardCap: Int? = null) {
        if (!enabled || !writeEnabled || incoming.isEmpty()) return
        val f = itemsFile() ?: return
        synchronized(this) {
            val existing = load()

            // The user's chosen total, and it is absolute for NEW entries.
            // Checking the room INSIDE this lock is what makes the cap
            // race-proof: the background pipeline and the main-page capture
            // used to both compute "remaining" outside any lock and both
            // squeeze through - that is precisely how "50" quietly became
            // "53 of 50".
            //
            // One exception, and it is what keeps a partial store healable:
            // a re-capture of an entry the store ALREADY holds (same id).
            // It replaces the old copy - fresh media URLs included - rather
            // than adding alongside it, so it spends no room. Refusing it
            // because the store was full is exactly how "reels 4/30"
            // became permanent: the known ids are complete-only now, so
            // every incomplete id comes back through this door.
            val existingIds = HashSet<String>()
            for (e in existing) if (e.id.isNotBlank()) existingIds.add(e.id)
            // Seats are counted in playable entries: an entry whose media
            // never landed holds no seat (the round-12 rule, counted the
            // same way at both sync gates above the vault). Counting raw
            // entries here is what made a shelf of undownloaded reels
            // permanently read as "full".
            // Seats once again, now honestly: COMPLETE entries, plus
            // entries still IN FLIGHT (at least one of their media URLs
            // sits in this vault's download queue right now). Counting
            // seats by complete entries alone let a capture burst admit
            // far past the target while the first batch's media was
            // still downloading: every chunk's admission recomputed
            // room from a store whose media had not landed yet, so a
            // "Posts to download: 10" setting could read "16 of 10"
            // the moment the queue emptied (bug-report-v2 screenshot).
            // A stuck entry - captured, download dead, nothing of it
            // left in the queue - holds NO seat, so a same-id
            // re-capture with a fresh signed URL still walks in free
            // and heals it (the round-12 rule stands exactly: a shelf
            // of undownloaded entries must never read as full).
            var room = Int.MAX_VALUE
            if (hardCap != null) {
                val inFlight = synchronized(queue) { HashSet(queued) }
                var seated = 0
                for (e in existing) {
                    if (isComplete(e)) { seated++; continue }
                    var flying = false
                    for (u in e.media) if (inFlight.contains(u)) {
                        flying = true
                        break
                    }
                    if (flying) seated++
                }
                room = hardCap - seated
            }

            val filtered = incoming
                .filter { !isJunk(it.html) }
                .let { list ->
                    if (videoRequired) {
                        list.filter { it.media.any { u -> isVideoUrl(u) } }
                    } else list
                }
                .filter {
                    if (it.id.isNotBlank() && it.id in existingIds) true
                    else if (room > 0) { room--; true } else false
                }
            if (filtered.isEmpty()) return

            val keep = maxOf(limit, keepFloor)
            val merged = ArrayList<Entry>(keep)

            fun keyFor(it: Entry): String {
                if (it.id.isNotBlank()) return it.id
                val mediaKey = it.media.firstOrNull() ?: ""
                val textKey = it.html.take(180)
                return "$mediaKey|$textKey"
            }

            // Over the floor, VIEWED entries surrender their seats first:
            // the reconnect rule (delete what was seen, keep what was not)
            // applies at every merge too, so a full pass naturally refills
            // with unviewed content instead of freezing on already-read
            // cards. New captures are never viewed, which keeps the
            // newest-first intake order untouched inside each group
            // (distinctBy and sortedBy are both stable).
            val ordered = (filtered + existing)
                .distinctBy { keyFor(it) }
                .sortedBy { if (it.viewed) 1 else 0 }
            for (it in ordered) {
                merged.add(it)
                if (merged.size >= keep) break
            }

            writeAll(merged)
        }
    }

    /**
     * The one atomic write behind [addItems], [trimTo], [markViewed], and
     * the reconnect eviction: whole JSON array to a `.part` sibling,
     * renamed into place. A truncated store is worse than a stale one, so
     * there is no other way in. Successful writes stale every assembled
     * page. Callers must already hold this vault's lock.
     *
     * @return true only when the new list actually replaced the old one.
     */
    private fun writeAll(entries: List<Entry>): Boolean {
        val f = itemsFile() ?: return false
        val arr = JSONArray()
        for (it in entries) {
            val m = JSONArray()
            for (u in it.media) m.put(u)
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("h", it.html)
                    .put("m", m)
                    .put("v", it.viewed)
                    .put("va", it.viewedAt ?: 0L)
            )
        }
        return try {
            val tmp = File(f.parentFile, f.name + ".part")
            tmp.writeText(arr.toString())
            if (tmp.renameTo(f)) {
                // The assembled offline pages embed these cards, so they
                // are stale now and must rebuild on the next request.
                // Scoped to this section: a reel markViewed write must not
                // also throw away the home feed's already-built cache.
                OfflineDocs.invalidate(section)
                true
            } else {
                tmp.delete()
                false
            }
        } catch (e: Exception) {
            // Out of space: the previous list stays usable.
            false
        }
    }

    /**
     * Marks a stored entry as seen by the user. Pure local state - the
     * offline pages report it over the bridge while being read, no
     * network is involved at any point. Safe to call repeatedly: a
     * second mark for the same id changes nothing.
     */
    fun markViewed(id: String) {
        if (id.isBlank()) return
        if (itemsFile() == null) return
        synchronized(this) {
            val existing = load()
            val idx = existing.indexOfFirst { it.id == id }
            if (idx < 0 || existing[idx].viewed) return
            val updated = existing.toMutableList()
            updated[idx] = existing[idx].copy(
                viewed = true, viewedAt = System.currentTimeMillis())
            writeAll(updated)
        }
    }

    /**
     * Called once per sync cycle when connectivity is back and downloads
     * are allowed again. Viewed entries have served their purpose, so
     * they hand their disk room to the next fetch: everything unviewed
     * stays, plus just enough of the freshest viewed ones to keep the
     * section at its floor. Media that only a dropped entry referenced
     * goes with it - freeing seats without freeing bytes would not be
     * "jaiga khali" at all.
     */
    fun evictViewedOnReconnect() {
        val f = itemsFile() ?: return
        synchronized(this) {
            val existing = load()
            if (existing.isEmpty()) return
            val unviewed = existing.filter { !it.viewed }
            val viewed = existing.filter { it.viewed }
            if (viewed.isEmpty()) return
            val keepViewed = viewed
                .sortedByDescending { it.viewedAt ?: 0L }
                .take(maxOf(0, keepFloor - unviewed.size))
            val keep = unviewed + keepViewed
            if (keep.size == existing.size) return
            writeAll(keep)

            // Media only the dropped entries referenced is now unreachable
            // (same purge as trimTo: never a .part of a live transfer).
            val keepNames = HashSet<String>()
            for (e in keep) for (u in e.media) keepNames.add(hashFor(u))
            val dir = media
            if (dir != null) {
                try {
                    for (file in dir.listFiles() ?: emptyArray()) {
                        val n = file.name
                        if (n.endsWith(".part")) continue
                        val base = if (n.endsWith(".mime"))
                            n.removeSuffix(".mime") else n
                        if (!keepNames.contains(base)) file.delete()
                    }
                } catch (e: Exception) {}
            }
        }
    }

    /**
     * Identities whose media is REALLY on disk, handed to capture so it can
     * skip them.
     *
     * This used to be every stored id, complete or not - and it is why a
     * section could jam at a partial count forever ("reels 4/30 ar hote
     * chai na"). A reel whose signed URL expired before its video landed
     * stayed in items.json with a dead URL, and every later capture pass
     * skipped the FRESH copy as "already known", so the fresh URL never
     * replaced the dead one. Complete items keep their skip (the point of
     * knownIds); incomplete ones are deliberately NOT known, so the next
     * pass re-stores them - and because [addItems] merges incoming FIRST,
     * the replacement with a live URL wins over the dead entry with the
     * same id rather than duplicating it.
     */
    fun knownIds(): List<String> =
        completeItems().map { it.id }.filter { it.isNotBlank() }

    /** Every stored entry, whether or not its media has finished arriving. */
    fun load(): List<Entry> {
        val f = itemsFile() ?: return emptyList()
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val html = o.optString("h", "")
                if (html.isBlank()) return@mapNotNull null
                // Junk that entered older stores is filtered at the door of
                // every read too, so one upgrade quietly heals an existing
                // vault: condemned ad cards and the captured tab row simply
                // stop existing for counting, serving and identity.
                if (isJunk(html)) return@mapNotNull null
                val m = o.optJSONArray("m")
                val mediaUrls = if (m == null) emptyList() else
                    (0 until m.length()).mapNotNull { j -> m.optString(j, null) }
                Entry(
                    id = o.optString("id", ""),
                    html = html,
                    media = mediaUrls,
                    // Stores written before the seen-tracking build carry
                    // neither key: everything in them simply reads as not
                    // yet seen, no migration step is needed.
                    viewed = o.optBoolean("v", false),
                    viewedAt = o.optLong("va", 0L).let {
                        if (it == 0L) null else it }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Stored markup that is not content, decided from the markup alone.
     *
     * Three kinds, all proven against real captured stores:
     *
     *  - a card the m.facebook.com ad remover had already condemned when
     *    the capture read it: the mark (`data-db-ad`) is part of the saved
     *    outerHTML, so such a card stays recognisable forever. It is an
     *    ad by definition and never belongs in the library.
     *  - the stories tray: the capture's chrome filter covers the com-
     *    poser and the tab row but never the tray, so a whole tray was
     *    saved as a card; offline it rendered once as this card and once
     *    as the moved chrome - the user's "double story" screenshot. Two
     *    or more distinct story links and no post permalink: only a tray.
     *  - Facebook's pinned tab row, saved when the background scroll
     *    caught it mid-scroller with badge counters showing. Six-plus
     *    characters of badge text and no container-level label let it
     *    pass every capture filter; offline it was served back between
     *    two posts as if it were a story. The signature mirrors the
     *    capture-side check in OfflineCapture.isChrome exactly: several
     *    tab-labelled buttons and no link a story could have. Feed-only,
     *    because only the home scroller ever contains that row.
     */
    /** The stories tray's own signature: story links, nothing else. */
    internal val JUNK_TRAY_LINK = Regex(
        "href=[\"']([^\"']*/stories/[^\"']*)",
        RegexOption.IGNORE_CASE
    )

    /**
     * The shape-agnostic tray signature (round 13): elements labelled
     * "...story" - "Create story", "Your Story", "<name>'s story" in the
     * user's own screenshots. Round 10 keyed on /stories/ hrefs, a
     * fixture assumption; the real tray's links were something else,
     * which is the only way its saved card survived a dozen heals.
     */
    internal val JUNK_TRAY_TEASER = Regex(
        "aria-label\\s*=\\s*[\"'][^\"']*story[^\"']*[\"']",
        RegexOption.IGNORE_CASE
    )

    /** The lone-teaser variant's second witness: its create tile text. */
    internal val JUNK_TRAY_CREATE = Regex("create story", RegexOption.IGNORE_CASE)

    /** Tags stripped, so caption prose never poses as tray tile text. */
    private val TRAY_TEXT_TAGS = Regex("<[^>]+>", RegexOption.IGNORE_CASE)

    private fun isJunk(html: String): Boolean {
        if (html.contains(JUNK_AD_TAG, ignoreCase = true)) return true
        if (section != SECTION_FEED_ID) return false
        if (JUNK_STORY_LINK.containsMatchIn(html)) return false
        // The stories tray. The capture's chrome filter covers the
        // composer and the tab row but never the tray, so a whole tray
        // walked into the store as a card - and once the compose layer
        // also moved the scroller's tray out, offline home showed it
        // TWICE ("story er ta double asche", round-10 screenshot). Two
        // or more DISTINCT story links - with the post-permalink guard
        // above already passed - is only ever that tray. Existing saves
        // heal at the door of every read, the same way old tab rows did.
        var stories = 0
        val seenStories = HashSet<String>()
        val st = JUNK_TRAY_LINK.findAll(html).iterator()
        while (st.hasNext()) {
            if (seenStories.add(st.next().groupValues[1]) && ++stories >= 2) {
                return true
            }
        }
        // Shape-agnostic tier: three-plus "...story" labels is only ever
        // the tray; one label plus the create tile's own text is the
        // two-tile shape. Real posts already passed the permalink guard
        // above, so they cannot reach this.
        var teasers = 0
        val te = JUNK_TRAY_TEASER.findAll(html).iterator()
        while (te.hasNext()) {
            te.next()
            if (++teasers >= 3) return true
        }
        if (teasers >= 1 &&
            JUNK_TRAY_CREATE.containsMatchIn(html.replace(TRAY_TEXT_TAGS, " "))) {
            return true
        }
        var labels = 0
        val it = JUNK_TAB_LABEL.findAll(html).iterator()
        while (it.hasNext()) {
            it.next()
            if (++labels >= 2) return true
        }
        // Badge-carrying shell copies of the same row: prefix labels or
        // two distinct nav hrefs. The permalink guard is what a real post
        // always carries and the stored shells provably never do - the
        // exact proof entry is the hrefless "text:15+ 15+ 4 15+" card.
        if (isTabRowMarkup(html) &&
            !NAV_POST_PERMALINK.containsMatchIn(html)) return true
        // The shortcuts/bookmarks menu (Finances, Marketplace, Groups,
        // Memories, Saved, ...) - an app-drawer tile grid that carries its
        // own small icon per tile, so it clears every check above and
        // gets saved as if it were a post ("Finances" + Tether wallet
        // icon between real cards, round-14 screenshot). No permalink a
        // real post would carry (already true here - the early-return
        // above passed only when it's absent), and its own heading is
        // one of Facebook's fixed shortcut-menu labels.
        val heading = TRAY_TEXT_TAGS.replace(html, " ").replace(Regex("\\s+"), " ").trim()
        if (JUNK_SHORTCUT_HEADING.matches(heading)) return true
        return false
    }

    /** Total stored entries (media may still be in flight). */
    fun totalStored(): Int = load().size

    /**
     * Cheap "is there anything here at all" test for the WebView's resource
     * thread: file exists and holds more than an empty array. Never parses.
     */
    fun storedCountCheap(): Int {
        val f = itemsFile() ?: return 0
        return try {
            if (f.exists() && f.length() > 2L) 1 else 0
        } catch (e: Exception) {
            0
        }
    }

    // ----------------------------------------------------------- counting

    /**
     * Whether this entry is FULLY downloaded and playable offline.
     *
     * The rule needs exactly one source of truth: the vault's own media
     * folder, where files only ever exist complete (they are renamed into
     * place after the last byte). No minimum video size, no "enough of the
     * photos arrived" judgement calls:
     *
     *  - a text post carries no media: complete the moment it is stored.
     *  - a video entry is complete when one of its video URLs is on disk.
     *  - a photo entry is complete when every PHOTO has at least one of its
     *    variants on disk (Facebook re-issues one photo at several sizes;
     *    any variant renders the photo).
     *  - avatars and emoji chrome do not count either way: the words and
     *    pictures of a post are what make it readable.
     *
     * This is the ONLY completeness predicate. The settings count and the
     * served page both read it, so the number and the screen cannot
     * disagree: what is counted is what is shown.
     */
    fun isComplete(e: Entry): Boolean {
        if (e.media.isEmpty()) return !videoRequired

        val videos = e.media.filter { isVideoUrl(it) }
        if (videos.isNotEmpty()) {
            return videos.any { hasAsset(it) }
        }

        val photos = e.media.filter { !isAvatar(it) && !isChrome(it) }
        if (photos.isEmpty()) return true

        val groups = photos.groupBy { photoKey(it) }
        return groups.values.all { variants -> variants.any { hasAsset(it) } }
    }

    /**
     * The entries that are ready to show offline - the only honest count.
     *
     * Serving order is UNSEEN FIRST: what the user already read sinks to
     * the bottom on the next offline session, what is still new floats to
     * the top. sortedBy is stable, so the newest-first intake order is
     * preserved inside each of the two groups - only the groups swap
     * places, nothing inside them is re-shuffled.
     */
    fun completeItems(): List<Entry> =
        load().filter { isComplete(it) }
            .sortedBy { if (it.viewed) 1 else 0 }

    /** The real count: complete entries, read from this vault's own files. */
    fun count(): Int = completeItems().size

    /** The markup of exactly the entries [count] reports. */
    fun cards(): List<String> = completeItems().map { it.html }

    // ----------------------------------------------------------- download

    /** Queue every asset these entries reference. Returns immediately. */
    fun enqueue(entries: List<Entry>, includeVideo: Boolean) {
        enqueueUrls(entries.flatMap { it.media }.distinct(), includeVideo)
    }

    /**
     * Queue media URLs for download into this vault's media folder.
     *
     * Queue, never drop: refusing a second call was why only a handful of
     * items ever finished. Each vault drains its own queue with its own
     * worker, so sections download independently.
     */
    fun enqueueUrls(urls: List<String>, includeVideo: Boolean) {
        if (!enabled || !writeEnabled || urls.isEmpty()) return
        if (!downloadAllowed()) return
        synchronized(queue) {
            for (u in urls) {
                if (!includeVideo && isVideoUrl(u)) continue
                if (hasAsset(u)) continue
                if (queued.add(u)) queue.add(u)
            }
        }
        // Never start a worker directly: the serial scheduler in
        // OfflineVaults decides which single vault may fetch right now.
        OfflineVaults.pump()
    }

    /**
     * Whether the current connection may be used for saving.
     * Defaults to allowing it when the context is not yet known.
     */
    private fun downloadAllowed(): Boolean {
        val c = appContext ?: return true
        return NetworkPolicy.canDownload(c, Prefs(c))
    }

    /**
     * One worker drains the queue; further calls just add to it.
     *
     * Runs only while THIS vault holds the serial download slot: the
     * pipeline downloads posts, THEN reels, THEN stories, and the user's
     * "eksathe download na hote pare" is guaranteed structurally - two
     * vaults can never be fetching at once, whoever enqueued what.
     * Granted by OfflineVaults.pump(); released the moment the queue is
     * empty so the next section's turn begins.
     */
    internal fun startDrain() {
        if (!busy.compareAndSet(false, true)) return
        pool.execute {
            var stored = 0
            try {
                while (enabled && writeEnabled) {
                    // Re-checked per file: walking out of Wi-Fi range must
                    // not keep pulling video over mobile data.
                    if (!downloadAllowed()) break
                    if (!OfflineVaults.slotGrantedFor(this)) break
                    val u = synchronized(queue) {
                        if (queue.isEmpty()) null else queue.removeAt(0)
                    } ?: break
                    if (hasAsset(u)) continue
                    if (fetchInto(u)) {
                        stored++
                        downloaded++
                        // The entry this file completes became countable
                        // just now; the assembled pages and the settings
                        // count must see it at the same moment.
                        OfflineDocs.invalidate()
                    }
                }
            } catch (e: Exception) {
                // Network died mid-pass: what was stored is still valid.
            } finally {
                lastStored = stored
                if (stored > 0) OfflineDocs.invalidate()
                busy.set(false)
                val more = synchronized(queue) { queue.isNotEmpty() }
                if (more && enabled && writeEnabled && downloadAllowed() &&
                    OfflineVaults.slotGrantedFor(this)) {
                    // The queue still has work and it is still our turn.
                    startDrain()
                } else {
                    // Queue empty, or the network policy just turned
                    // against us: either way the turn is over. Holding the
                    // slot while unable to fetch would starve the other
                    // sections, and re-arming would spin a thread on a
                    // policy check. The next enqueue or phase change
                    // re-pumps the scheduler.
                    OfflineVaults.releaseSlot(this)
                }
            }
        }
    }

    /**
     * Fetch one asset into this vault's media folder.
     *
     * Streamed straight to a .part file and renamed when - and only when -
     * the last byte is on disk. That rename is what makes "the file exists"
     * mean "the whole file is here", which is what the count relies on.
     *
     * Encoding matters: we ask for identity and refuse anything that still
     * arrives encoded - the WebView must never be served compressed bytes
     * as if they were the file.
     */
    private fun fetchInto(url: String): Boolean {
        if (!looksLikeMedia(url)) {
            if (!isVideoUrl(url)) return false
        }
        val tmp = assetFile(url, part = true) ?: return false
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
            }
            if (conn.responseCode != 200) { tmp.delete(); return false }
            val enc = conn.getHeaderField("Content-Encoding")
            if (enc != null && !enc.equals("identity", true)) {
                tmp.delete(); return false
            }
            val type = conn.contentType
            val mime = type ?: if (isVideoUrl(url)) "video/mp4"
                else guessMime(url)

            var total = 0L
            gaugeBytes = 0L
            conn.inputStream.use { inp ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = inp.read(buf)
                        if (r < 0) break
                        total += r
                        gaugeBytes = total
                        if (total > MAX_ENTRY_BYTES) break
                        out.write(buf, 0, r)
                    }
                }
            }
            gaugeBytes = 0L
            if (total <= 0L || total > MAX_ENTRY_BYTES) {
                tmp.delete()
                return false
            }
            val final = assetFile(url) ?: run { tmp.delete(); return false }
            mimeFileFor(final).writeText(mime)
            if (!tmp.renameTo(final)) {
                tmp.delete()
                return false
            }
            true
        } catch (e: Exception) {
            tmp.delete()
            false
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
        }
    }

    // ------------------------------------------------------------- assets

    private val hashCache =
        object : LinkedHashMap<String, String>(256, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, String>?
            ): Boolean = size > 256
        }

    private fun hashFor(url: String): String {
        synchronized(hashCache) { hashCache[url] }?.let { return it }
        val md = MessageDigest.getInstance("SHA-256")
        val b = md.digest(url.toByteArray())
        val sb = StringBuilder(64)
        for (x in b) sb.append(String.format("%02x", x))
        val k = sb.toString()
        synchronized(hashCache) { hashCache[url] = k }
        return k
    }

    private fun assetFile(url: String, part: Boolean = false): File? {
        val dir = media ?: return null
        return File(dir, hashFor(url) + if (part) ".part" else "")
    }

    private fun mimeFileFor(asset: File): File = File(asset.path + ".mime")

    /** True when this asset's bytes are really on disk (complete files only). */
    fun hasAsset(url: String): Boolean {
        val f = assetFile(url) ?: return false
        return try { f.exists() && f.length() > 0L } catch (e: Exception) { false }
    }

    /** The recorded content type for a stored asset, guessed when absent. */
    private fun mimeOf(url: String, f: File): String {
        val recorded = try {
            mimeFileFor(f).takeIf { it.exists() }?.readText()
        } catch (e: Exception) {
            null
        }?.takeIf { it.isNotBlank() }
        return recorded ?: guessMime(url)
    }

    /**
     * Serve a stored asset to the WebView.
     *
     * Range support is not optional: a media element will not play from a
     * plain 200 when it asked for bytes, it needs 206 with a matching
     * Content-Range. This is what makes saved video actually play offline.
     */
    fun serve(url: String, rangeHeader: String?): WebResourceResponse? {
        if (!enabled) return null
        val f = assetFile(url) ?: return null
        if (!f.exists() || f.length() == 0L) return null
        val mime = mimeOf(url, f)
        if (rangeHeader == null) return full(f, mime)
        return partial(f, mime, rangeHeader) ?: full(f, mime)
    }

    private fun full(f: File, mime: String): WebResourceResponse? {
        return try {
            WebResourceResponse(
                mime.substringBefore(';').trim(),
                null,
                200,
                "OK",
                mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Cache-Control" to "max-age=604800",
                    "Accept-Ranges" to "bytes",
                    "Content-Length" to f.length().toString()
                ),
                FileInputStream(f)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun partial(f: File, mime: String, header: String): WebResourceResponse? {
        val total = f.length()
        val spec = header.substringAfter("bytes=", "").trim()
        if (spec.isBlank()) return null
        val start = spec.substringBefore('-').toLongOrNull() ?: 0L
        val end = spec.substringAfter('-').toLongOrNull()?.coerceAtMost(total - 1)
            ?: (total - 1)
        if (start < 0 || start > end || start >= total) return null
        return try {
            val stream = FileInputStream(f)
            stream.skip(start)
            WebResourceResponse(
                mime.substringBefore(';').trim(),
                null,
                206,
                "Partial Content",
                mapOf(
                    "Accept-Ranges" to "bytes",
                    "Content-Range" to "bytes $start-$end/$total",
                    "Content-Length" to (end - start + 1).toString(),
                    "Access-Control-Allow-Origin" to "*"
                ),
                stream
            )
        } catch (e: Exception) {
            null
        }
    }

    fun isPrefetching(): Boolean = busy.get()

    fun pending(): Int = synchronized(queue) { queue.size }

    /**
     * Block until this vault's queue is drained, or the timeout expires.
     * Used by the pipeline to hold a phase until its media is really on
     * disk - that is what makes the settings count real at that moment.
     * Never called from the UI thread.
     */
    fun awaitIdle(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!busy.get() && pending() == 0) return
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    fun sizeBytes(): Long {
        val dir = root ?: return 0
        return try {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Trim this vault down to at most [maxEntries] entries, newest first.
     *
     * The capture gates refuse to ADD beyond the user's chosen total, but a
     * store written while an older build chased a larger one (the 300-post
     * backlog phase, or a setting the user has since lowered) stays large
     * forever: nothing added, nothing removed, the count permanently above
     * the number on the settings screen. Trimming once when the pipeline
     * starts makes the on-disk library match the chosen total within that
     * cycle, honestly - the kept entries are untouched, and every media file
     * only the dropped entries referenced is deleted.
     *
     * `.part` files are never touched: they belong to transfers in flight
     * and deleting one mid-write is how a half file becomes a "complete"
     * one. Atomic rewrite, same as [addItems]: a truncated store is worse
     * than a stale one.
     *
     * @return how many entries were dropped (0 when already within bounds).
     */
    fun trimTo(maxEntries: Int): Int {
        if (maxEntries <= 0) return 0
        val f = itemsFile() ?: return 0
        synchronized(this) {
            val items = load()
            if (items.size <= maxEntries) return 0
            val keep = items.take(maxEntries)

            // One writer, again: trimming inline once forgot the viewed
            // flags and silently un-saw everything it kept.
            if (!writeAll(keep)) return 0

            // Media only the dropped entries referenced is now unreachable.
            val keepNames = HashSet<String>()
            for (e in keep) for (u in e.media) keepNames.add(hashFor(u))
            val dir = media
            if (dir != null) {
                try {
                    for (file in dir.listFiles() ?: emptyArray()) {
                        val n = file.name
                        if (n.endsWith(".part")) continue
                        val base = if (n.endsWith(".mime"))
                            n.removeSuffix(".mime") else n
                        if (!keepNames.contains(base)) file.delete()
                    }
                } catch (e: Exception) {}
            }
            OfflineDocs.invalidate()
            return items.size - keep.size
        }
    }

    /** Wipe this section - items and media - and nothing else. */
    fun clear() {
        synchronized(queue) {
            queue.clear()
            queued.clear()
        }
        val dir = root ?: return
        try {
            dir.walkTopDown().filter { it.isFile }.forEach { it.delete() }
        } catch (e: Exception) {}
        OfflineDocs.invalidate()
    }

    companion object {

        /** One media asset can be this large; bigger is an error, not content. */
        private const val MAX_ENTRY_BYTES = 60L * 1024 * 1024   // 60 MB

        // Junk signatures for isJunk(), above. Kept as constants so the
        // test suite can extract them verbatim. Internal rather than
        // private: PageAssembly reuses the very same signatures to decide
        // which captured scroller children are chrome worth keeping and
        // which are junk to leave hidden - one definition, no drift.
        internal const val JUNK_AD_TAG = "data-db-ad="
        internal const val SECTION_FEED_ID = "feed"
        internal val JUNK_STORY_LINK = Regex(
            "story_fbid|/posts/|/videos/|/reel/", RegexOption.IGNORE_CASE)
        internal val JUNK_TAB_LABEL = Regex(
            "aria-label=[\"'](?:home|reels|watch|notifications|marketplace|" +
                "menu|profile|friends|groups|gaming|messages|messenger|" +
                "chats|search|create)[\"']",
            RegexOption.IGNORE_CASE)

        /**
         * The tab row with Facebook's badge counters still in the label:
         * "Notifications, 15+ notifications" is the SAME row, but the
         * exact matcher above refuses it - so a badge-carrying shell copy
         * walked past capture (exact labels there too), past this vault's
         * heal, and past classify, and rendered as a card between posts
         * (round-17 screenshot; still reproducible on main: the stored
         * entry is a HREFLESS button shell, id "text:15+ 15+ 4 15+").
         * Prefix-anywhere match: the label STARTS with a nav name (a
         * word boundary stands between "home" and "homemade").
         */
        internal val JUNK_TAB_PREFIX = Regex(
            "aria-label=[\"']\\s*(?:home|reels|watch|notifications|" +
                "marketplace|menu|profile|friends|groups|gaming|messages|" +
                "messenger|chats|search|create)\\b",
            RegexOption.IGNORE_CASE)

        /**
         * The row's unchanging skeleton: its links. Icons, badges and
         * label text vary; the navigation hrefs do not. Two or more
         * DISTINCT nav paths in one unit is only ever that row.
         * PageAssembly and OfflineCapture use these same constants, so
         * capture, vault and compose never drift apart on what a row is.
         */
        internal val JUNK_TAB_HREF = Regex(
            "href=[\"'](?:https?://m\\.facebook\\.com)?/([a-z]+)",
            RegexOption.IGNORE_CASE)
        internal val NAV_PATHS = setOf(
            "home", "friends", "watch", "reels", "notifications",
            "marketplace", "groups", "menu", "profile", "messages",
            "chats", "gaming", "bookmarks")

        /**
         * What a tab row never carries: a content permalink. The badge
         * shell proofs are hrefless by construction, so this guard costs
         * the heal nothing and is what keeps a REAL post safe - a feed
         * card can legitimately hold one nav-prefixed label (audience
         * "Friends", a "Watch more" control), never two plus a
         * permalink it doesn't have. getNativeFeelScript labels made
         * this class of accusation eat real posts once; not twice.
         */
        internal val NAV_POST_PERMALINK = Regex(
            "/posts/|/videos/|/reel/|story_fbid",
            RegexOption.IGNORE_CASE)

        /** One shared test: does this unit's markup say "tab row"? */
        fun isTabRowMarkup(html: String): Boolean {
            var pre = 0
            val labels = JUNK_TAB_PREFIX.findAll(html).iterator()
            while (labels.hasNext() && pre < 2) {
                labels.next()
                pre++
            }
            if (pre >= 2) return true
            val seen = HashSet<String>()
            val hrefs = JUNK_TAB_HREF.findAll(html).iterator()
            while (hrefs.hasNext()) {
                val seg = hrefs.next().groupValues[1].lowercase()
                if (NAV_PATHS.contains(seg)) seen.add(seg)
                if (seen.size >= 2) return true
            }
            return false
        }
        internal val JUNK_SHORTCUT_HEADING = Regex(
            "^(?:finances|marketplace|groups|memories|saved|pages|events|" +
                "friends|feeds)$",
            RegexOption.IGNORE_CASE)


        fun isVideoUrl(url: String): Boolean {
            val clean = url.substringBefore('?').lowercase(Locale.ROOT)
            return clean.endsWith(".mp4") || clean.endsWith(".webm") ||
                clean.contains("/v/t2/")
        }

        /**
         * Identity of the photo a URL serves, ignoring which sized variant
         * it is. Facebook re-issues one photo at several sizes; the content
         * id in the filename is constant while size markers move around, so
         * stripping the size markers only ever merges true variants of one
         * photo - never two different photos.
         */
        fun photoKey(url: String): String {
            val stem = url.substringBefore('?')
                .substringAfterLast('/')
                .lowercase(Locale.ROOT)
            return stem
                .replace(Regex("[._-][0-9]+x[0-9]+"), "")
                .replace(Regex("_[0-9]+(?=\\.)"), "")
        }

        /** Emoji sprites, static icons, spacers: furniture, not content. */
        fun isChrome(url: String): Boolean {
            val clean = url.substringBefore('?').lowercase(Locale.ROOT)
            return clean.contains("/emoji.php/") ||
                clean.contains("static.xx.fbcdn.net") ||
                clean.contains("/rsrc.php/") ||
                clean.endsWith(".svg")
        }

        /** Profile pictures arrive first and are tiny; they never count. */
        fun isAvatar(url: String): Boolean {
            val clean = url.substringBefore('?')
            return clean.contains("/t39.30808-1/") ||
                (clean.contains("profile") && clean.contains("_s."))
        }

        /**
         * A content type for an asset whose sidecar is missing or empty.
         * application/octet-stream would leave a file invisible to the
         * WebView - it will not play a video or apply a stylesheet under it.
         */
        fun guessMime(url: String): String {
            val clean = url.substringBefore('?').lowercase(Locale.ROOT)
            return when {
                clean.endsWith(".css") -> "text/css"
                clean.endsWith(".js") -> "application/javascript"
                clean.endsWith(".woff2") -> "font/woff2"
                clean.endsWith(".woff") -> "font/woff"
                clean.endsWith(".ttf") -> "font/ttf"
                clean.endsWith(".otf") -> "font/otf"
                clean.endsWith(".svg") -> "image/svg+xml"
                clean.endsWith(".png") -> "image/png"
                clean.endsWith(".webp") -> "image/webp"
                clean.endsWith(".gif") -> "image/gif"
                clean.endsWith(".jpg") || clean.endsWith(".jpeg") -> "image/jpeg"
                clean.endsWith(".mp4") || clean.endsWith(".m4v") -> "video/mp4"
                clean.endsWith(".webm") -> "video/webm"
                // Facebook's video files carry no extension at all.
                clean.contains("/v/t2/") || clean.contains("/o1/v/") -> "video/mp4"
                else -> "application/octet-stream"
            }
        }

        /** Facebook content hosts only - never the API, never trackers. */
        private fun looksLikeMedia(url: String): Boolean {
            if (!url.startsWith("https://")) return false
            val h = UrlHelper.hostOf(url) ?: return false
            if (!(h.endsWith("fbcdn.net") || h.endsWith("fbsbx.com"))) return false
            return !BlockList.blocksHost(h)
        }
    }
}

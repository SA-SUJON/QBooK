package com.dustbook.app.utils

import android.content.Context
import android.net.Uri
import com.dustbook.app.offline.OfflineVaults
import com.dustbook.app.offline.SectionVault
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Public face of the offline library.
 *
 * The storage itself now lives in three separate vaults - one file for
 * the home feed, one for reels, one for stories
 * ([com.dustbook.app.offline.HomeVault],
 * [com.dustbook.app.offline.ReelsVault],
 * [com.dustbook.app.offline.StoriesVault]) - each with its own store, its
 * own media folder, its own download queue and its own real count. This
 * object only routes the app's existing calls to the right vault, so
 * screens, bridges and the pipeline keep saying what they always said.
 *
 * What stays here because it belongs to no section: the small fetcher
 * that downloads the PAGE furniture (stylesheets, icon fonts, avatars)
 * referenced by the stored Facebook documents. Those bytes are not user
 * content and are not counted; they still go to [OfflineCache], exactly
 * as before.
 */
object OfflineFeed {

    const val SECTION_FEED = "feed"
    const val SECTION_REELS = "reels"
    const val SECTION_STORIES = "stories"

    private val SECTIONS = setOf(SECTION_FEED, SECTION_REELS, SECTION_STORIES)

    /**
     * One saved story, as Facebook's own markup.
     *
     * Kept as the exchange type with the capture bridge - the vaults store
     * the same three fields.
     */
    data class Item(
        val id: String,
        val html: String,
        val media: List<String>
    )

    @Volatile var enabled: Boolean = true
        set(value) {
            field = value
            for (v in OfflineVaults.sections) v.enabled = value
        }

    /**
     * Whether new content may be *written*. Reading is always allowed;
     * only collecting new content follows the user's switches.
     */
    @Volatile var writeEnabled: Boolean = true
        set(value) {
            field = value
            for (v in OfflineVaults.sections) v.writeEnabled = value
        }

    fun init(context: Context) {
        chromeContext = context.applicationContext
        OfflineVaults.init(context)
    }

    /** Which vault a section id belongs to. */
    private fun vault(section: String): SectionVault? =
        OfflineVaults.forSection(section)

    fun sectionForUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = UrlHelper.hostOf(url) ?: return null
        if (!host.endsWith("facebook.com")) return null
        val path = try {
            Uri.parse(url).path?.lowercase(Locale.ROOT) ?: ""
        } catch (e: Exception) {
            return null
        }
        val first = path.trim('/').substringBefore('/')
        return when {
            first == "stories" || first == "story" -> SECTION_STORIES
            first == "reel" || first == "reels" || first == "watch" -> SECTION_REELS
            first.isEmpty() || first == "home.php" -> SECTION_FEED
            else -> null
        }
    }

    // ------------------------------------------------------------- store

    /**
     * Merge newly captured items into the section's own store.
     *
     * @param hardCap the user's chosen total for this section, when a
     *                caller knows it. Enforced INSIDE the vault's lock,
     *                so two paths calling at the same moment still can
     *                never push the store past it.
     */
    fun addItems(section: String, incoming: List<Item>, limit: Int,
                 hardCap: Int? = null) {
        if (!SECTIONS.contains(section)) return
        vault(section)?.addItems(
            incoming.map { SectionVault.Entry(it.id, it.html, it.media) },
            limit,
            hardCap
        )
    }

    /** Identities already stored, so capture skips what we hold. */
    fun knownIds(section: String): List<String> =
        vault(section)?.knownIds() ?: emptyList()

    /**
     * The offline pages report what the user has already seen through
     * this one door. Pure local state in the section's own vault: no
     * network, works fully offline.
     */
    fun markViewed(section: String, id: String) {
        if (!SECTIONS.contains(section)) return
        vault(section)?.markViewed(id)
    }

    fun loadItems(section: String): List<Item> =
        vault(section)?.load()?.map { Item(it.id, it.html, it.media) }
            ?: emptyList()

    fun totalStored(section: String): Int = vault(section)?.totalStored() ?: 0

    /**
     * Bring a section down to the user's chosen total. The capture gates
     * refuse to add beyond it, but a store made oversized by an older build
     * only ever shrinks when someone explicitly trims it. Runs on a caller
     * thread; the pipeline calls it once when a sync starts.
     */
    fun trimTo(section: String, maxEntries: Int): Int =
        vault(section)?.trimTo(maxEntries) ?: 0

    /** Cheap "is there anything here at all" test; never parses. */
    fun storedCount(section: String): Int = vault(section)?.storedCountCheap() ?: 0

    // ----------------------------------------------------------- counting

    /**
     * The real counts: complete items in each section's own files. The
     * served page is built from the same lists, so the number the user
     * reads and the content the user sees are the same thing.
     */
    fun realPlayableItems(section: String): List<Item> =
        vault(section)?.completeItems()?.map { Item(it.id, it.html, it.media) }
            ?: emptyList()

    fun realPlayableCount(section: String): Int = vault(section)?.count() ?: 0

    /** Alias kept for the diagnostics screens. */
    fun freshCount(section: String): Int = realPlayableCount(section)

    fun playableItems(section: String): List<Item> = realPlayableItems(section)

    fun playableCount(section: String): Int = realPlayableCount(section)

    /**
     * The saved cards as separate strings - exactly the items the count
     * reports, so number and screen can never disagree.
     */
    fun cardMarkupList(section: String): List<String> =
        vault(section)?.cards() ?: emptyList()

    fun hasAnything(): Boolean = OfflineVaults.hasAnything()

    fun sizeBytes(): Long = OfflineVaults.sizeBytes()

    fun clear() {
        OfflineVaults.clearAll()
    }

    // --------------------------------------------------- section downloads

    /** Queue a section's card media into that section's own vault. */
    fun prefetch(section: String, items: List<Item>, includeVideo: Boolean) {
        vault(section)?.enqueue(
            items.map { SectionVault.Entry(it.id, it.html, it.media) },
            includeVideo
        )
    }

    /** Media stored by the last pass across the sections plus chrome. */
    val lastStored: Int
        get() = chromeStored + OfflineVaults.sections.sumOf { it.lastStored }

    /** Files fetched since the app started, for the settings screen. */
    val downloaded: Int
        get() = chromeDownloaded + OfflineVaults.downloadedTotal()

    fun pending(): Int = chromePending() + OfflineVaults.pendingTotal()

    fun isPrefetching(): Boolean =
        chromeBusy.get() || OfflineVaults.anyPrefetching()

    /**
     * Block until every section's media and the chrome queue are on disk,
     * or the timeout expires. The pipeline holds each phase on this, so a
     * phase's count is real the moment the next phase begins.
     */
    fun awaitPrefetch(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isPrefetching() && pending() == 0) return
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    // ---------------------------------------------- page chrome downloads

    private val chromePool = Executors.newFixedThreadPool(2)
    private val chromeBusy = AtomicBoolean(false)
    private val chromeQueue = ArrayList<String>()
    private val chromeQueued = HashSet<String>()
    @Volatile private var chromeStored: Int = 0
    @Volatile private var chromeDownloaded: Int = 0

    /**
     * Download the media a stored PAGE references - stylesheets, icon
     * fonts, avatars - into [OfflineCache]. These bytes are furniture for
     * the stored Facebook documents, not user content, and are not part
     * of any count; the mechanism is unchanged from before.
     */
    fun prefetchUrls(urls: List<String>, includeVideo: Boolean) {
        if (!enabled || urls.isEmpty()) return
        if (!downloadAllowed()) return

        // Queue, never drop; one worker drains it.
        synchronized(chromeQueue) {
            for (u in urls) {
                if (!includeVideo && isVideoUrl(u)) continue
                if (chromeQueued.add(u)) chromeQueue.add(u)
            }
        }
        drainChrome()
    }

    private fun downloadAllowed(): Boolean {
        val c = chromeContext ?: return true
        return NetworkPolicy.canDownload(c, Prefs(c))
    }

    @Volatile private var chromeContext: Context? = null

    private fun drainChrome() {
        if (!chromeBusy.compareAndSet(false, true)) return
        chromePool.execute {
            var stored = 0
            try {
                while (enabled) {
                    if (!downloadAllowed()) break
                    val u = synchronized(chromeQueue) {
                        if (chromeQueue.isEmpty()) null else chromeQueue.removeAt(0)
                    } ?: break
                    if (OfflineCache.has(u)) continue
                    if (fetchInto(u)) {
                        stored++
                        chromeDownloaded++
                    }
                }
            } catch (e: Exception) {
                // Network died mid-pass: whatever was stored is still valid.
            } finally {
                chromeStored = stored
                busyFinished(stored)
                chromeBusy.set(false)
                val more = synchronized(chromeQueue) { chromeQueue.isNotEmpty() }
                if (more && enabled) drainChrome()
            }
        }
    }

    private fun busyFinished(stored: Int) {
        if (stored > 0) OfflineDocs.invalidate()
    }

    private fun chromePending(): Int = synchronized(chromeQueue) { chromeQueue.size }

    private fun isVideoUrl(url: String): Boolean = SectionVault.isVideoUrl(url)

    private fun looksLikeMedia(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val h = UrlHelper.hostOf(url) ?: return false
        if (!(h.endsWith("fbcdn.net") || h.endsWith("fbsbx.com"))) return false
        return !BlockList.blocksHost(h)
    }

    /**
     * Fetch one page asset into [OfflineCache]. Asks for identity encoding
     * and refuses anything still encoded, so the WebView never reads
     * compressed bytes as if they were the file.
     */
    private fun fetchInto(url: String): Boolean {
        if (!looksLikeMedia(url)) {
            if (!isVideoUrl(url)) return false
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
            }
            if (conn.responseCode != 200) return false
            val enc = conn.getHeaderField("Content-Encoding")
            if (enc != null && !enc.equals("identity", true)) return false
            val mime = conn.contentType
                ?: if (isVideoUrl(url)) "video/mp4"
                   else "application/octet-stream"
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return false
            OfflineCache.put(url, mime, bytes)
            OfflineCache.has(url)
        } catch (e: Exception) {
            false
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
        }
    }
}

package com.dustbook.app.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dustbook.app.offline.OfflineVaults

/**
 * Central orchestrator for the complete offline content lifecycle.
 *
 * Lifecycle:
 *   App opens online → start()
 *     Step 0: Trim the feed vault to the user's chosen post count, so a
 *             store made oversized by an older build (or a lowered
 *             setting) matches the setting within this cycle
 *     Step 1: Save the first few feed posts - min(10, the chosen count),
 *             a quick starter set usable offline almost immediately,
 *             then posts STOP for now
 *     Step 2: Save the user's chosen number of reels, video included
 *     Step 3: Continue the feed up to the user's chosen post count -
 *             the same hard ceiling as everywhere else, never past it
 *     Step 4: Once posts are complete, save the stories of the user's
 *             followed friends (watched + unwatched)
 *   User reads offline → viewed cards mark themselves via the bridge
 *   App goes offline → saved content displays, unseen first
 *   App comes back online → seen entries evicted (floor aside) →
 *     start() refills with what is still unread
 *
 * A phase only ends when its media queue is empty: the settings count reads
 * from disk, so after a phase's downloads are drained the number shown is
 * exactly the number that plays offline - never a markup count while the
 * photos are still in flight.
 *
 * Downloads are serial BY CONSTRUCTION: OfflineVaults hands a single fetch
 * slot to exactly one vault, and each step below names its own section as
 * the priority, so not one byte of a later phase downloads while an
 * earlier phase is active. "Posts and reels at the same time" is no
 * longer a race to prevent but a state that cannot exist.
 *
 * Everything runs silently on background threads. No user action needed.
 */
object BackgroundSyncManager {

    @Volatile var isRunning = false
        private set

    @Volatile var currentStep = ""
        private set

    /**
     * DIAGNOSTIC — temporary. Records why the last start() call did or did
     * not proceed past BackgroundSyncManager's own gates, with a timestamp,
     * so "downloads never start" can be triaged from Settings → About →
     * Developer Options without adb. Remove once the offline-download bug
     * is confirmed fixed.
     */
    @Volatile var lastBlockReason: String = "not called yet"
        private set

    private fun stampedNote(msg: String): String {
        val stamp = java.text.SimpleDateFormat(
            "HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        return "$stamp $msg"
    }

    /** Records the reason and returns Unit, so it can sit in `return note(...)`. */
    private fun note(msg: String) {
        lastBlockReason = stampedNote(msg)
    }

    private var ctx: Context? = null
    private var prefs: Prefs? = null

    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context, p: Prefs) {
        ctx = context.applicationContext
        prefs = p
    }

    /**
     * Start the full sync pipeline. Called once after login is confirmed
     * and the home page has loaded. Runs entirely on background threads.
     */
    fun start() {
        if (isRunning) return note("already running")
        val c = ctx ?: return note("init() never called (no context)")
        val p = prefs ?: return note("init() never called (no prefs)")
        if (!UrlHelper.isLoggedIn()) return note("isLoggedIn() == false")
        if (!p.offlineMode) return note("offlineMode disabled in settings")
        // Saving pulls feed pages, reels and their video. Not on a metered
        // connection unless the user has said that is fine.
        if (!NetworkPolicy.canDownload(c, p)) return note(
            "canDownload() == false " +
                "(wifiOnly=${p.offlineWifiOnly}, " +
                "connected=${NetworkPolicy.isConnected(c)}, " +
                "unmetered=${NetworkPolicy.isUnmetered(c)})")
        lastBlockReason = stampedNote("passed all gates, pipeline started")

        isRunning = true
        // Hard-ceiling bookkeeping BEFORE any fetching: the feed vault
        // may still hold the hundreds an older build raced to (its step 3
        // chased 300 no matter what), or more than a setting the user has
        // since lowered. Trimming once per cycle is what makes "stops at
        // my number" true on disk, not just a promise about new adds.
        //
        // Same moment, the reconnect rule: entries the user already saw
        // hand their disk room to the coming fetch (unseen entries are
        // never touched, and the floor still counts unseen-first). This
        // runs only because downloads are allowed - start() has already
        // passed the network policy check above - so freed room is always
        // refillable. Fire and forget, same disk thread as the trim.
        AppExecutors.diskIO.execute {
            OfflineFeed.trimTo(OfflineFeed.SECTION_FEED, p.offlinePostTarget)
            OfflineVaults.evictViewedOnReconnect()
        }
        step1FirstPosts(c, p)
    }

    /** Called when connectivity returns after being offline. */
    fun onNetworkRestored() {
        // Reconnecting no longer wipes the library. A full clear punished
        // exactly the entries the user had NOT seen yet - everything was
        // thrown out, seen or not, and refetched. start()'s cycle-opening
        // bookkeeping now evicts only what was seen (floor aside), then
        // the fresh pass refills around what is still unread.
        if (!isRunning) {
            start()
        }
    }

    /**
     * A phase is over only when everything IT queued is on disk.
     *
     * History of getting this wrong, in order:
     *
     *  1. a fixed five-minute wall clock (posts and reels climbed
     *     together on the counter the moment reels outlasted it);
     *  2. a three-minute "no new COMPLETED file" watchdog. That measured
     *     files, not progress: one 30 MB reel on a 77 KB/s connection
     *     takes six-plus minutes to finish, so the watchdog fired in the
     *     middle of a healthy download and the posts phase began filling
     *     while the videos were still arriving - the exact overlap the
     *     user reported twice, with the settings screenshot to match.
     *
     * What "stalled" means now: no completed file, no byte of the
     * transfer in flight, and no movement in the queue, for three
     * consecutive minutes. A slow-but-alive download moves its byte
     * gauge every minute, so it can sit well past three minutes itself
     * without ever looking dead; a truly dead connection produces a read
     * timeout inside a minute and the queue then visibly shrinks - so
     * three silent minutes only ever happen when nothing at all is
     * happening, and the pipeline moving on there is correct, never a
     * frozen screen.
     */
    private fun awaitThen(section: String, next: () -> Unit) {
        AppExecutors.background.execute {
            val vault = com.dustbook.app.offline.OfflineVaults.forSection(section)
            var lastDone = -1
            var lastBytes = -1L
            var lastPending = -1
            var silentMinutes = 0
            while (true) {
                vault?.awaitIdle(60_000)
                val pending = vault?.pending() ?: 0
                val busy = vault?.isPrefetching() ?: false
                if (pending == 0 && !busy) break
                val done = vault?.downloaded ?: 0
                val bytes = vault?.gaugeBytes() ?: 0L
                if (done != lastDone || bytes != lastBytes ||
                    pending != lastPending) {
                    silentMinutes = 0
                    lastDone = done
                    lastBytes = bytes
                    lastPending = pending
                } else {
                    silentMinutes++
                    if (silentMinutes >= 3) break
                }
            }
            main.post { next() }
        }
    }

    // -------------------------------------------------------- steps

    /** Reels passes per cycle, counting the first one. Bounded, always. */
    private const val MAX_REEL_PASSES = 3

    private fun step1FirstPosts(context: Context, p: Prefs) {
        currentStep = "posts-first"
        // This step owns the serial download slot: no reel moves while
        // the starter posts are landing.
        com.dustbook.app.offline.OfflineVaults.setPrioritySection(
            OfflineFeed.SECTION_FEED)
        // min(10, the chosen count): a fast, immediately usable starter
        // set. The phase total is exact so fetching stops here and reels
        // can start - and a user who set 10 posts is already done here.
        val quick = minOf(10, p.offlinePostTarget)
        OfflineSync.run(context, OfflineFeed.SECTION_FEED, quick,
            includeVideo = true, force = true, exactTotal = quick) {
            awaitThen(OfflineFeed.SECTION_FEED) { step2Reels(context, p) }
        }
    }

    private fun step2Reels(context: Context, p: Prefs, pass: Int = 1) {
        currentStep = "reels"
        // The serial slot serves reels only for this whole step.
        com.dustbook.app.offline.OfflineVaults.setPrioritySection(
            OfflineFeed.SECTION_REELS)
        val target = p.offlineReelTarget.coerceAtLeast(30)
        OfflineSync.run(context, OfflineFeed.SECTION_REELS, target,
            includeVideo = true, force = true, exactTotal = target) {
            awaitThen(OfflineFeed.SECTION_REELS) {
                // A pass fills the STORE on time; some videos still miss
                // when a signed URL expires before its turn in the queue.
                // Those entries sit stored but unplayable, and re-capture
                // is now allowed to replace them (same id, fresh URL) -
                // so load the reels screen afresh and take another pass.
                // Bounded, so a dead connection moves the pipeline on.
                val have = OfflineFeed.realPlayableCount(
                    OfflineFeed.SECTION_REELS)
                if (have < target && pass < MAX_REEL_PASSES) {
                    step2Reels(context, p, pass + 1)
                } else {
                    step3MorePosts(context, p)
                }
            }
        }
    }

    private fun step3MorePosts(context: Context, p: Prefs) {
        currentStep = "posts-full"
        com.dustbook.app.offline.OfflineVaults.setPrioritySection(
            OfflineFeed.SECTION_FEED)
        // Continue FROM the starter set up to the user's chosen count:
        // the phase total counts what the store already holds, so this
        // fills exactly the gap - and stops. Gone is the hardcoded 300
        // that kept climbing no matter what the first phase promised.
        val target = p.offlinePostTarget
        OfflineSync.run(context, OfflineFeed.SECTION_FEED, target,
            includeVideo = true, force = true, exactTotal = target) {
            awaitThen(OfflineFeed.SECTION_FEED) { step4Stories(context, p) }
        }
    }

    private fun step4Stories(context: Context, p: Prefs) {
        currentStep = "stories"
        com.dustbook.app.offline.OfflineVaults.setPrioritySection(
            OfflineFeed.SECTION_STORIES)
        // Stories: save ALL, not just unwatched.
        OfflineSync.run(context, OfflineFeed.SECTION_STORIES, 200,
            includeVideo = true, force = true) {
            awaitThen(OfflineFeed.SECTION_STORIES) {
                currentStep = "done"
                isRunning = false
                // Free-for-all again: anything queued outside the phases
                // (an item the user scrolled past) drains in its turn.
                com.dustbook.app.offline.OfflineVaults.setPrioritySection(null)
            }
        }
    }
}

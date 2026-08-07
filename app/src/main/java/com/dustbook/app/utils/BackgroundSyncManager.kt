package com.dustbook.app.utils

import android.content.Context
import android.os.Handler
import android.os.Looper

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
 *   User browses online → seen content tracked via bridge + store
 *   App goes offline → saved content displays
 *   App comes back online → clearAll() → start() fresh
 *
 * A phase only ends when its media queue is empty: the settings count reads
 * from disk, so after a phase's downloads are drained the number shown is
 * exactly the number that plays offline - never a markup count while the
 * photos are still in flight.
 *
 * Everything runs silently on background threads. No user action needed.
 */
object BackgroundSyncManager {

    @Volatile var isRunning = false
        private set

    @Volatile var currentStep = ""
        private set

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
        if (isRunning) return
        val c = ctx ?: return
        val p = prefs ?: return
        if (!UrlHelper.isLoggedIn()) return
        if (!p.offlineMode) return
        // Saving pulls feed pages, reels and their video. Not on a metered
        // connection unless the user has said that is fine.
        if (!NetworkPolicy.canDownload(c, p)) return

        isRunning = true
        // Hard-ceiling bookkeeping BEFORE any fetching: the feed vault
        // may still hold the hundreds an older build raced to (its step 3
        // chased 300 no matter what), or more than a setting the user has
        // since lowered. Trimming once per cycle is what makes "stops at
        // my number" true on disk, not just a promise about new adds.
        // Fire and forget: the newest entries survive, and the first
        // phase below adds nothing while the store is still oversized.
        AppExecutors.diskIO.execute {
            OfflineFeed.trimTo(OfflineFeed.SECTION_FEED, p.offlinePostTarget)
        }
        step1FirstPosts(c, p)
    }

    /** Called when connectivity returns after being offline. */
    fun onNetworkRestored() {
        if (!isRunning) {
            clearAllStored()
            start()
        }
    }

    /** Clear the entire offline library for a fresh sync cycle. */
    fun clearAllStored() {
        AppExecutors.diskIO.execute {
            OfflineCache.clear()
            OfflineFeed.clear()
            OfflineDocs.clear()
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

    private fun step1FirstPosts(context: Context, p: Prefs) {
        currentStep = "posts-first"
        // min(10, the chosen count): a fast, immediately usable starter
        // set. The phase total is exact so fetching stops here and reels
        // can start - and a user who set 10 posts is already done here.
        val quick = minOf(10, p.offlinePostTarget)
        OfflineSync.run(context, OfflineFeed.SECTION_FEED, quick,
            includeVideo = true, force = true, exactTotal = quick) {
            awaitThen(OfflineFeed.SECTION_FEED) { step2Reels(context, p) }
        }
    }

    private fun step2Reels(context: Context, p: Prefs) {
        currentStep = "reels"
        val target = p.offlineReelTarget.coerceAtLeast(30)
        OfflineSync.run(context, OfflineFeed.SECTION_REELS, target,
            includeVideo = true, force = true, exactTotal = target) {
            awaitThen(OfflineFeed.SECTION_REELS) { step3MorePosts(context, p) }
        }
    }

    private fun step3MorePosts(context: Context, p: Prefs) {
        currentStep = "posts-full"
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
        // Stories: save ALL, not just unwatched.
        OfflineSync.run(context, OfflineFeed.SECTION_STORIES, 200,
            includeVideo = true, force = true) {
            awaitThen(OfflineFeed.SECTION_STORIES) {
                currentStep = "done"
                isRunning = false
            }
        }
    }
}

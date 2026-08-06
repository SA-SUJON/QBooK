package com.dustbook.app.utils

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Central orchestrator for the complete offline content lifecycle.
 *
 * Lifecycle:
 *   App opens online → start()
 *     Step 1: Save the first 10 feed posts - a quick starter set the user
 *             can open offline almost immediately, then posts STOP for now
 *     Step 2: Save the user's chosen number of reels, video included
 *     Step 3: Continue the feed backlog from those 10 up to 300 posts
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
     * This used to give up after five fixed minutes and start the next
     * phase anyway. Each vault drains its own queue, so thirty reels of
     * video kept downloading long after that timer fired while the posts
     * phase had already begun to fill - which is exactly how posts and
     * reels came to climb together on the settings counter. The order is
     * the whole point of the pipeline, so a phase now waits on ITS OWN
     * section by progress: the wait ends the moment that section's queue
     * stands empty, keeps standing while files keep arriving, and only a
     * genuinely dead queue (nothing new for three minutes straight, e.g.
     * the connection died) lets the pipeline move on rather than hang.
     */
    private fun awaitThen(section: String, next: () -> Unit) {
        AppExecutors.background.execute {
            val vault = com.dustbook.app.offline.OfflineVaults.forSection(section)
            var lastDone = -1
            var stalledMinutes = 0
            while (true) {
                vault?.awaitIdle(60_000)
                val pending = vault?.pending() ?: 0
                val busy = vault?.isPrefetching() ?: false
                if (pending == 0 && !busy) break
                val done = vault?.downloaded ?: 0
                if (done == lastDone) {
                    stalledMinutes++
                    if (stalledMinutes >= 3) break   // dead network: do not hang forever
                } else {
                    stalledMinutes = 0
                    lastDone = done
                }
            }
            main.post { next() }
        }
    }

    // -------------------------------------------------------- steps

    private fun step1FirstPosts(context: Context, p: Prefs) {
        currentStep = "posts-10"
        // Exactly ten: a fast, immediately usable starter set. The phase
        // total is exact so fetching stops here and reels can start.
        OfflineSync.run(context, OfflineFeed.SECTION_FEED, 10,
            includeVideo = true, force = true, exactTotal = 10) {
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
        currentStep = "posts-300"
        // Continue FROM the 10 already saved up to 300 total: the phase
        // total counts what the store already holds, so this fills the gap.
        OfflineSync.run(context, OfflineFeed.SECTION_FEED, 300,
            includeVideo = true, force = true, exactTotal = 300) {
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

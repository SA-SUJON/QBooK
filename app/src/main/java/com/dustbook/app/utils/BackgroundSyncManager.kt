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

    /** A phase is over only when everything it queued is on disk. */
    private fun awaitThen(next: () -> Unit) {
        AppExecutors.background.execute {
            // Wait up to 5 minutes for the downloads of this phase to finish.
            OfflineFeed.awaitPrefetch(300_000)
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
            awaitThen { step2Reels(context, p) }
        }
    }

    private fun step2Reels(context: Context, p: Prefs) {
        currentStep = "reels"
        val target = p.offlineReelTarget.coerceAtLeast(30)
        OfflineSync.run(context, OfflineFeed.SECTION_REELS, target,
            includeVideo = true, force = true, exactTotal = target) {
            awaitThen { step3MorePosts(context, p) }
        }
    }

    private fun step3MorePosts(context: Context, p: Prefs) {
        currentStep = "posts-300"
        // Continue FROM the 10 already saved up to 300 total: the phase
        // total counts what the store already holds, so this fills the gap.
        OfflineSync.run(context, OfflineFeed.SECTION_FEED, 300,
            includeVideo = true, force = true, exactTotal = 300) {
            awaitThen { step4Stories(context, p) }
        }
    }

    private fun step4Stories(context: Context, p: Prefs) {
        currentStep = "stories"
        // Stories: save ALL, not just unwatched.
        OfflineSync.run(context, OfflineFeed.SECTION_STORIES, 200,
            includeVideo = true, force = true) {
            awaitThen {
                currentStep = "done"
                isRunning = false
            }
        }
    }
}

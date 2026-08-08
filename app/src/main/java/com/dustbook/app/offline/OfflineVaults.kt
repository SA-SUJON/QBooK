package com.dustbook.app.offline

import android.content.Context
import android.webkit.WebResourceResponse
import com.dustbook.app.utils.OfflineDocs
import java.io.File

/**
 * Registry of the three section vaults.
 *
 * The sections themselves are deliberately separate - own file, own
 * queue, own count, own clear - but the app addresses them by one section
 * id, and a few questions are naturally asked of the whole library ("is
 * anything saved", "serve whichever vault holds this URL"). Those glue
 * questions live here and nowhere else, so the per-section systems never
 * need to know about each other.
 */
object OfflineVaults {

    const val ROOT_DIR = "offline_vaults"

    /** The three independent section systems, in pipeline order. */
    val sections: List<SectionVault> = listOf(HomeVault, ReelsVault, StoriesVault)

    @Volatile private var inited = false

    fun init(context: Context) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            val ctx = context.applicationContext
            val base = File(ctx.filesDir, ROOT_DIR)
            if (!base.exists()) base.mkdirs()
            // One-time retirement of the v1 store. It counted captured
            // markup against a separately-evicted media cache, so its
            // numbers could never be trusted; the new system starts empty
            // and counts from its own files.
            try {
                File(ctx.filesDir, "offline_items_v1").deleteRecursively()
            } catch (e: Exception) {
                // Best effort: a leftover old file wastes space, nothing else.
            }
            for (v in sections) v.init(ctx)
            inited = true
        }
    }

    /** The vault for a section id ("feed", "reels", "stories"), if any. */
    fun forSection(section: String): SectionVault? =
        sections.firstOrNull { it.section == section }

    // ------------------------------------------- serial download slot
    //
    // Three vaults with three workers meant whatever had queued bytes
    // fetched them at the same time - the user's screenshots showed posts
    // and reels downloading together twice. Parallel is now structurally
    // impossible: exactly one vault holds the slot, and it keeps it until
    // its own queue is empty. The pipeline tells us which section is in
    // its active phase, so work enqueued for a LATER phase (a photo the
    // user scrolled past while the reels pass is running) simply waits
    // for its turn instead of competing.
    private val schedLock = Any()
    @Volatile private var owner: SectionVault? = null
    @Volatile private var prioritySection: String? = null

    /** Which section the pipeline is filling now; null between runs. */
    fun setPrioritySection(section: String?) {
        synchronized(schedLock) { prioritySection = section }
        pump()
    }

    /** True for the vault currently allowed to fetch. */
    fun slotGrantedFor(v: SectionVault): Boolean =
        synchronized(schedLock) { owner === v }

    /**
     * Called when a vault's queue is empty. The next waiting section -
     * in pipeline order - is granted immediately.
     */
    fun releaseSlot(v: SectionVault) {
        synchronized(schedLock) { if (owner === v) owner = null }
        pump()
    }

    /**
     * Grant the slot to the next vault with work, if nobody holds it.
     * With a priority section set, ONLY that section may hold the slot:
     * during the reels phase not even one feed photo downloads.
     */
    fun pump() {
        val grant: SectionVault
        synchronized(schedLock) {
            if (owner != null) return
            val prio = prioritySection
            val order = if (prio == null) sections
                else sections.filter { it.section == prio }
            grant = order.firstOrNull { it.pending() > 0 } ?: return
            owner = grant
        }
        grant.startDrain()
    }

    /** True when any section holds at least one complete, countable item. */
    fun hasAnything(): Boolean = sections.any { it.count() > 0 }

    /**
     * One reconnect pass over every section: entries the user has already
     * seen hand their room to the next fetch, unseen ones never move. See
     * [SectionVault.evictViewedOnReconnect] for the per-vault rule.
     */
    fun evictViewedOnReconnect() {
        sections.forEach { it.evictViewedOnReconnect() }
    }

    /**
     * Serve a stored asset, whichever vault holds it.
     *
     * A card's media URL is only ever requested because a card referenced
     * it, and every card lives in exactly one vault, so the first vault
     * that really has the bytes is the right one.
     */
    fun serveAny(url: String, rangeHeader: String?): WebResourceResponse? {
        for (v in sections) {
            if (v.hasAsset(url)) return v.serve(url, rangeHeader)
        }
        return null
    }

    /** Bytes used by all three sections together. */
    fun sizeBytes(): Long = sections.sumOf { it.sizeBytes() }

    /** Files fetched across all sections since the app started. */
    fun downloadedTotal(): Int = sections.sumOf { it.downloaded }

    /** Media still queued across all sections. */
    fun pendingTotal(): Int = sections.sumOf { it.pending() }

    fun anyPrefetching(): Boolean = sections.any { it.isPrefetching() }

    /** Clear all three sections. Used by "clear saved content" and resync. */
    fun clearAll() {
        for (v in sections) v.clear()
        OfflineDocs.invalidate()
    }

    /**
     * Block until every vault's download queue is drained, or the timeout
     * expires. The pipeline holds each phase on this, which is what makes
     * "counted" and "fully downloaded" describe the same moment.
     */
    fun awaitAllIdle(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!anyPrefetching() && pendingTotal() == 0) return
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }
}

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

    /** True when any section holds at least one complete, countable item. */
    fun hasAnything(): Boolean = sections.any { it.count() > 0 }

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

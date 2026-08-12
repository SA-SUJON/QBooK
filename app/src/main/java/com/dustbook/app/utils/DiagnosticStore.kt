package com.dustbook.app.utils

import android.content.Context
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Per-channel log store. Each [Diag.Channel] has its own file
 * under cacheDir/diagnostic/ so a single busy channel (network)
 * cannot crowd out a quiet one (reels). The file format is one
 * JSON object per line, in append order, with the oldest at
 * the top and the newest at the bottom. Read is line-by-line.
 *
 * Files are capped at 2 MB per channel; when the next write
 * would push the file over the cap, the top half of the file
 * is dropped in one pass. The trim aligns to the next newline
 * so the file always starts on an entry boundary. No
 * compression, no indexing, no rotation - a developer opens
 * the export and reads top to bottom.
 *
 * The enabled flags live on the [Prefs] companion and are read
 * on every [write]. The store does not cache them; the cost
 * of a SharedPreferences read is one volatile field load,
 * which is what gating is for.
 *
 * Thread safety: each channel has its own [ReentrantLock]. The
 * locks are taken on every [write], [read], [clear], [list],
 * and [export], never on a fast path. The fast path is
 * [DiagCapture.write], which reads the enabled flag and
 * short-circuits before touching the store at all.
 */
class DiagnosticStore(private val appContext: Context) {

    private val locks: Map<Diag.Channel, ReentrantLock> =
        Diag.Channel.values().associateWith { ReentrantLock() }

    /** Append one entry. Returns immediately if the channel
     *  is not enabled - that is the hot path. */
    fun write(channel: Diag.Channel, entry: Diag) {
        if (!prefs().diagChannelEnabled(channel)) return
        val line = entry.toJson().toString()
        val lock = locks[channel]!!
        lock.withLock {
            try {
                val f = file(channel)
                f.appendText(line + "\n", Charsets.UTF_8)
                if (f.length() > MAX_BYTES) trim(channel)
            } catch (e: Exception) {
                // Best-effort. A failure to write does not
                // crash the host; the entry is dropped and
                // the next call continues. The diagnostic
                // log is a debug aid, not a correctness
                // path.
            }
        }
    }

    /** Read every entry in a channel as a list, oldest first.
     *  Used by the in-app viewer and the export. */
    fun read(channel: Diag.Channel): List<Diag> {
        val lock = locks[channel]!!
        return lock.withLock {
            try {
                val f = file(channel)
                if (!f.exists()) return@withLock emptyList()
                f.readLines(Charsets.UTF_8).mapNotNull(Diag::fromJsonLine)
            } catch (e: Exception) { emptyList() }
        }
    }

    /** Drop a channel's file. */
    fun clear(channel: Diag.Channel) {
        val lock = locks[channel]!!
        lock.withLock {
            try {
                val f = file(channel)
                if (f.exists()) f.delete()
            } catch (_: Exception) {}
        }
    }

    /** Drop every channel's file. */
    fun clearAll() {
        Diag.Channel.values().forEach { clear(it) }
    }

    /** Sum of byte sizes across all channels. Used by the
     *  developer-options screen to show how much disk the
     *  log subsystem is using. */
    fun totalBytes(): Long =
        Diag.Channel.values().sumOf { file(it).length() }

    /** Absolute path of a channel's file. The export
     *  reads from these in a second pass, and the share
     *  intent hands the path to the system chooser. */
    fun path(channel: Diag.Channel): String = file(channel).absolutePath

    private fun file(channel: Diag.Channel): File {
        val dir = File(appContext.cacheDir, "diagnostic")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, channel.name.lowercase() + ".log")
    }

    private fun trim(channel: Diag.Channel) {
        val f = file(channel)
        if (!f.exists() || f.length() <= MAX_BYTES) return
        val keep = MAX_BYTES / 2
        val skip = f.length() - keep
        if (skip <= 0) return
        val tail = f.readBytes().let { all ->
            var alignAt = skip.toInt()
            while (alignAt < all.size && all[alignAt] != '\n'.code.toByte()) alignAt++
            if (alignAt < all.size) alignAt++ else return
            all.copyOfRange(alignAt, all.size)
        }
        f.writeBytes(tail)
    }

    private fun prefs(): Prefs = Prefs(appContext)

    companion object {
        /** 2 MB per channel. With seven channels the worst
         *  case is 14 MB on disk, well inside the budget
         *  Android allows for cache. A single channel
         *  cap means a runaway network trace cannot
         *  crowd out the reels log the developer is
         *  trying to read. */
        private const val MAX_BYTES = 2L * 1024 * 1024
    }
}

package com.dustbook.app.utils

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One log entry. Reused for both online and offline modes. The
 * [mode] field lets a single export file mix both runs and the
 * reader filter by it. The [channel] is the topic the entry
 * belongs to: home-feed, reels, story, ads, network, save.
 *
 * Entries are plain data, written in a structured form to a
 * per-channel log file and read back by [DiagnosticStore]. The
 * format is the same in both the per-channel file (one entry
 * per line, JSON) and the in-memory buffer. A text export is
 * the same JSON lines without the wrapping brackets.
 *
 * The [ts] is a millisecond unix epoch so chronological order
 * is preserved across mode switches and channel switches. The
 * text form is rendered with [Diag.fmtTs] for human reading.
 */
data class Diag(
    val ts: Long,
    val mode: Mode,
    val channel: Channel,
    val level: Level,
    val message: String,
    /** Process/session context makes logs from separate reproductions distinguishable. */
    val sessionId: String = "",
    /** Thread name is essential for proving main-thread stalls and races. */
    val thread: String = ""
) {
    enum class Mode { ONLINE, OFFLINE }
    enum class Level { INFO, WARN, ERROR }

    /** Channel is a single flat enum so the export filter can
     *  reason about every topic uniformly. The toggle on the
     *  developer-options screen maps one-to-one to a [Channel]. */
    enum class Channel {
        HOME_FEED,
        REELS,
        STORY,
        ADS,
        NETWORK,
        OFFLINE_SAVE,
        APP_LIFECYCLE
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("ts", ts)
        put("mode", mode.name)
        put("channel", channel.name)
        put("level", level.name)
        put("message", message)
        put("sessionId", sessionId)
        put("thread", thread)
    }

    companion object {
        private val FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        /** Render a unix epoch in milliseconds as a human
         *  readable timestamp. The format is the same one
         *  adb logcat uses, so a slice pasted into a bug
         *  report aligns with the device log. */
        fun fmtTs(ts: Long): String = FMT.format(Date(ts))

        /** Parse a single line of a per-channel file back
         *  into a [Diag]. The line is JSON. Returns null
         *  on a parse failure, a blank line, or a line
         *  from a different file format. The caller drops
         *  the null silently - a partial file is the
         *  normal case after a crash mid-write. */
        fun fromJsonLine(line: String): Diag? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed[0] != '{') return null
            return try {
                val o = JSONObject(trimmed)
                Diag(
                    ts = o.optLong("ts", 0L),
                    mode = runCatching { Mode.valueOf(o.optString("mode")) }.getOrNull() ?: Mode.ONLINE,
                    channel = runCatching { Channel.valueOf(o.optString("channel")) }.getOrNull() ?: Channel.HOME_FEED,
                    level = runCatching { Level.valueOf(o.optString("level")) }.getOrNull() ?: Level.INFO,
                    message = o.optString("message", ""),
                    sessionId = o.optString("sessionId", ""),
                    thread = o.optString("thread", "")
                )
            } catch (e: Exception) { null }
        }
    }
}

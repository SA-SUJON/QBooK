package com.dustbook.app.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * In-app diagnostic log, written to a file in cacheDir and read on demand
 * by the developer-options screen. Off by default; when off, every
 * [write] call returns immediately and the cost is one boolean read
 * (R8 inlines the rest away at call sites that gate on [isEnabled]).
 *
 * The on-disk format is a plain text file with one entry per line, each
 * prefixed with a millisecond timestamp and the tag. The file is capped
 * at [MAX_BYTES]; when writing the next entry would push the file over
 * the cap, the oldest bytes are dropped from the top of the file in
 * [trim] chunks. No rotation, no compression, no indexing: a developer
 * opens it, scrolls, shares it.
 *
 * Thread safety: every public method takes [lock]. The lock is held for
 * the duration of one [write] or [read] call; the append-then-trim path
 * is a single critical section so the file never has a torn line on
 * screen and is never larger than [MAX_BYTES] by more than one entry
 * (or one [trim] chunk, whichever is bigger).
 */
class DiagnosticLog(private val appContext: Context) {

    private val lock = ReentrantLock()

    /** True while a developer is actively investigating. The Settings
     *  switch writes this and [readAll] / [clear] read it; everything
     *  else just reads it. */
    @Volatile var enabled: Boolean = false

    /**
     * Append one entry. Returns immediately when [enabled] is false,
     * which is the path the production user takes on every call.
     */
    fun write(tag: String, message: String) {
        if (!enabled) return
        val line = formatLine(tag, message)
        lock.withLock {
            try {
                val f = file()
                f.appendText(line, Charsets.UTF_8)
                if (f.length() > MAX_BYTES) {
                    trim()
                }
            } catch (e: Exception) {
                // Catch body must match the try body's type. The try
                // body yields Unit (the if-without-else) so the catch
                // body must also yield Unit; Log.w's Int return is
                // discarded by the trailing Unit statement.
                Log.w(TAG, "write failed", e)
            }
            Unit
        }
    }

    /** Read the whole file as a string, newest entry last. Used by
     *  the developer-options screen and the share intent. */
    fun readAll(): String = lock.withLock {
        try {
            val f = file()
            if (f.exists()) {
                f.readText(Charsets.UTF_8)
            } else {
                ""
            }
        } catch (e: Exception) {
            // Both branches yield String. Log.w's Int return is
            // discarded by the trailing "" literal.
            Log.w(TAG, "readAll failed", e)
            ""
        }
    }

    /** Drop the file. */
    fun clear() {
        lock.withLock {
            try {
                val f = file()
                if (f.exists()) f.delete()
            } catch (e: Exception) {
                // Same as write: catch body must yield Unit.
                Log.w(TAG, "clear failed", e)
            }
            Unit
        }
    }

    /** Absolute path of the log file, used in the share intent. */
    fun path(): String = file().absolutePath

    private fun file(): File {
        val dir = File(appContext.cacheDir, "diagnostic")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "dustbook.log")
    }

    private fun formatLine(tag: String, message: String): String {
        // One line per entry. The format is intentionally the same as
        // adb logcat's "MM-DD HH:MM:SS.mmm" prefix so a developer
        // who pastes a slice into a bug report can match it against
        // their own logcat capture without translation.
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        // Newlines in the message would split it across two lines on
        // read; replace them with the literal escape so the file stays
        // one-line-per-entry.
        val safe = message.replace('\n', ' ').replace('\r', ' ')
        return "$ts $tag: $safe\n"
    }

    /** When the file is over [MAX_BYTES], drop [TRIM_BYTES] from the
     *  top. The trim aligns to the next newline so the file always
     *  starts on an entry boundary, never in the middle of one. */
    private fun trim() {
        val f = file()
        if (!f.exists() || f.length() <= MAX_BYTES) return
        RandomAccessFile(f, "rw").use { raf ->
            // Skip the first TRIM_BYTES and find the next newline.
            raf.seek(TRIM_BYTES)
            var alignAt = TRIM_BYTES
            while (alignAt < f.length()) {
                val b = raf.read()
                if (b < 0) break
                alignAt++
                if (b == '\n'.code) break
            }
            // Shift the tail to the front.
            val tail = f.length() - alignAt
            if (tail <= 0) return
            val buf = ByteArray(tail.toInt().coerceAtMost(64 * 1024))
            var readAt = alignAt
            var writeAt = 0L
            while (readAt < f.length()) {
                raf.seek(readAt)
                val n = raf.read(buf)
                if (n <= 0) break
                raf.seek(writeAt)
                raf.write(buf, 0, n)
                readAt += n
                writeAt += n
            }
            raf.setLength(writeAt)
        }
    }

    companion object {
        private const val TAG = "DBProDiag"
        private const val MAX_BYTES = 5L * 1024 * 1024
        private const val TRIM_BYTES = 256L * 1024
    }
}

package com.dustbook.app.utils

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import java.io.File

/**
 * Keeps the WebView's state across process death.
 *
 * Android only hands `onSaveInstanceState` back when it killed the activity
 * itself. When the user swipes the app away, or the system reclaims the
 * process, that bundle is gone - so the next launch starts from a bare
 * WebView, Facebook re-runs its whole bootstrap, and the user watches the
 * header and tab bar appear one piece at a time. That is browser behaviour.
 *
 * Writing the same bundle to disk closes the gap: on the next cold start we
 * restore the back-forward list and the last page, and the shell paints from
 * the WebView's cache instead of being rebuilt.
 *
 * The file is small (a few KB of history), written on pause, and any failure
 * is non-fatal - we simply fall back to loading the start URL.
 */
object SessionState {

    private const val FILE = "webview_state.bin"

    /** A state older than this is not worth restoring; the feed would be stale. */
    private const val MAX_AGE_MS = 12L * 60 * 60 * 1000

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun save(context: Context, bundle: Bundle) {
        val parcel = Parcel.obtain()
        try {
            bundle.writeToParcel(parcel, 0)
            val bytes = parcel.marshall()
            // A huge blob means something other than history got in there.
            // Writing it would cost more than the restore is worth.
            if (bytes.size > 1024 * 1024) {
                clear(context)
                return
            }
            val f = file(context)
            val tmp = File(f.parentFile, "$FILE.part")
            tmp.outputStream().use { it.write(bytes) }
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (e: Exception) {
            // Not fatal: the app just cold-starts next time.
            try { clear(context) } catch (e2: Exception) {}
        } finally {
            parcel.recycle()
        }
    }

    fun restore(context: Context): Bundle? {
        val f = file(context)
        if (!f.exists() || f.length() == 0L) return null
        if (System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) {
            clear(context)
            return null
        }
        val parcel = Parcel.obtain()
        return try {
            val bytes = f.readBytes()
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(parcel).apply {
                // Force the class loader now, so a malformed file fails here
                // rather than half way through restoring the WebView.
                classLoader = context.classLoader
            }
        } catch (e: Exception) {
            clear(context)
            null
        } finally {
            parcel.recycle()
        }
    }

    fun clear(context: Context) {
        try { file(context).delete() } catch (e: Exception) {}
    }
}

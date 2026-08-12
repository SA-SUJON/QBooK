package com.dustbook.app.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export utility for the per-channel diagnostic log files.
 *
 * Two formats:
 *   - [FORMAT_JSON]: a single JSON object with metadata
 *     (mode, channel, level, ts, message) and a top-level
 *     "entries" array. Suitable for offline analysis with
 *     jq, python, or any JSON tool.
 *   - [FORMAT_TEXT]: one line per entry, in the form
 *     "[yyyy-MM-dd HH:mm:ss.SSS] [MODE] [CHANNEL] [LEVEL] message".
 *     The same shape adb logcat uses. A developer can paste
 *     a slice into a bug report and the line numbers line up
 *     with the device log.
 *
 * Both formats are written to a file in cacheDir/diagnostic/exports
 * and a content:// URI is returned so the share intent can hand
 * it to the system chooser. The file is named with the export
 * time so multiple exports do not overwrite each other; the
 * developer-options screen calls [purge] on entry to keep the
 * exports directory from growing.
 */
object DiagnosticExport {

    const val FORMAT_JSON = "json"
    const val FORMAT_TEXT = "txt"

    /** Build an export file for the given channels and format,
     *  share it, and return the path that was shared. The
     *  caller can hand the same path to a follow-up "delete
     *  the export" action, or just let the next [purge] clear
     *  it. */
    fun share(
        ctx: Context,
        channels: List<Diag.Channel>,
        format: String
    ): String? {
        val store = DiagCapture.init(ctx)
        val entries = channels.flatMap { store.read(it) }
            .sortedBy { it.ts }
        val file = when (format) {
            FORMAT_JSON -> writeJson(ctx, entries)
            FORMAT_TEXT -> writeText(ctx, entries)
            else -> return null
        }
        // FileProvider is the only path that lets the
        // receiving app read our file. The authority is
        // declared in AndroidManifest as ${packageName}.fileprovider
        // and the cache path is in res/xml/file_paths.xml.
        val uri = FileProvider.getUriForFile(
            ctx, ctx.packageName + ".fileprovider", file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (format == FORMAT_JSON) "application/json" else "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Diagnostic log")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
        return file.absolutePath
    }

    /** Delete every previous export. Called on entry to
     *  the developer-options screen so the cache directory
     *  does not grow unboundedly across sessions. */
    fun purge(ctx: Context) {
        val dir = File(ctx.cacheDir, "diagnostic/exports")
        if (!dir.exists()) return
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun writeJson(ctx: Context, entries: List<Diag>): File {
        val arr = JSONArray()
        for (e in entries) arr.put(e.toJson())
        val root = JSONObject().apply {
            put("app", "Dustbook")
            put("versionName", try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            } catch (_: Exception) { "unknown" })
            put("exportedAt", System.currentTimeMillis())
            put("count", entries.size)
            put("sessionIds", JSONArray().apply {
                entries.map { it.sessionId }.filter { it.isNotBlank() }.distinct().forEach { put(it) }
            })
            put("device", JSONObject().apply {
                put("manufacturer", android.os.Build.MANUFACTURER)
                put("model", android.os.Build.MODEL)
                put("android", android.os.Build.VERSION.RELEASE)
                put("sdk", android.os.Build.VERSION.SDK_INT)
            })
            put("entries", arr)
        }
        return writeFile(ctx, "json", root.toString(2))
    }

    private fun writeText(ctx: Context, entries: List<Diag>): File {
        val sb = StringBuilder()
        sb.appendLine("Dustbook diagnostic log")
        sb.appendLine("Exported: " + Diag.fmtTs(System.currentTimeMillis()))
        sb.appendLine("Entries: " + entries.size)
        sb.appendLine("Sessions: " + entries.map { it.sessionId }.filter { it.isNotBlank() }.distinct().joinToString(", "))
        sb.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine()
        for (e in entries) {
            sb.append('[').append(Diag.fmtTs(e.ts)).append("] ")
              .append('[').append(e.mode.name).append("] ")
              .append('[').append(e.channel.name).append("] ")
              .append('[').append(e.level.name).append("] ")
              .appendLine(e.message)
        }
        return writeFile(ctx, "txt", sb.toString())
    }

    private fun writeFile(ctx: Context, ext: String, body: String): File {
        val dir = File(ctx.cacheDir, "diagnostic/exports")
        if (!dir.exists()) dir.mkdirs()
        // Time-stamped filename so multiple exports do not
        // overwrite each other. The format is sortable as
        // a string.
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "dustbook_$stamp.$ext")
        file.writeText(body, Charsets.UTF_8)
        return file
    }
}

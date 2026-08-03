package com.dustbook.app.utils

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Downloads an update and hands it to the package installer, so the user never
 * leaves the app for a browser.
 *
 * Uses DownloadManager for the transfer (survives backgrounding, shows normal
 * system progress) and a FileProvider URI for the install intent, which is
 * required from Android N onward.
 */
object ApkInstaller {

    private const val DIR = "updates"

    /** Reason the last download failed, shown in the dialog. */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Where the APK is stored while downloading.
     *
     * Must be on external app storage, not cacheDir. DownloadManager runs in
     * its own system process and cannot write into an app's private internal
     * cache, so enqueueing there failed immediately and the dialog appeared to
     * do nothing.
     */
    private fun target(context: Context, version: String): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, DIR).apply { mkdirs() }
        return File(dir, "Dustbook-$version.apk")
    }

    /**
     * Start the download.
     * @return the DownloadManager id, or -1 if it could not be queued.
     */
    fun startDownload(context: Context, url: String, version: String): Long {
        return try {
            val file = target(context, version)
            if (file.exists()) file.delete()

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("Dustbook $version")
                setDescription("Downloading update")
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationUri(Uri.fromFile(file))
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setMimeType("application/vnd.android.package-archive")
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            -1L
        }
    }

    /** Progress as 0..100, or -1 when unknown. */
    fun progress(context: Context, id: Long): Int {
        if (id <= 0) return -1
        var cursor: Cursor? = null
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            cursor = dm.query(DownloadManager.Query().setFilterById(id))
            if (cursor == null || !cursor.moveToFirst()) return -1
            val soFar = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            )
            val total = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            )
            if (total <= 0) -1 else ((soFar * 100) / total).toInt()
        } catch (e: Exception) {
            -1
        } finally {
            try { cursor?.close() } catch (e: Exception) {}
        }
    }

    fun status(context: Context, id: Long): Int {
        if (id <= 0) return DownloadManager.STATUS_FAILED
        var cursor: Cursor? = null
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            cursor = dm.query(DownloadManager.Query().setFilterById(id))
            if (cursor == null || !cursor.moveToFirst()) return DownloadManager.STATUS_FAILED
            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        } catch (e: Exception) {
            DownloadManager.STATUS_FAILED
        } finally {
            try { cursor?.close() } catch (e: Exception) {}
        }
    }

    /** Launch the system installer for a completed download. */
    fun install(context: Context, version: String): Boolean {
        return try {
            val file = target(context, version)
            if (!file.exists() || file.length() < 1000) return false

            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** True when the device will refuse to install without user consent. */
    fun canInstall(context: Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /** Open the system screen granting install permission. */
    fun requestInstallPermission(context: Context) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (e: Exception) { /* user can grant it manually */ }
    }

    fun cleanup(context: Context) {
        try {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            File(base, DIR).listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {}
    }
}

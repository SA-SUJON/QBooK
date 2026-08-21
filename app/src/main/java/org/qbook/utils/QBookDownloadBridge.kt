package org.qbook.utils

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.qbook.R
import java.io.File
import java.util.concurrent.Executors

/**
 * Saves media fetched by the in-page downloader without replacing QBooK's
 * existing WebView DownloadListener pipeline.
 */
class QBookDownloadBridge(
    context: Context,
    private val requestLegacyStoragePermission: () -> Unit
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "qbook-media-save").apply { isDaemon = true }
    }
    @Volatile private var pending: PendingDownload? = null

    @JavascriptInterface
    @Suppress("unused")
    fun downloadBase64File(dataUrl: String, mimeType: String) {
        if (needsLegacyStoragePermission()) {
            pending = PendingDownload(dataUrl, mimeType)
            android.os.Handler(appContext.mainLooper).post {
                requestLegacyStoragePermission()
            }
            return
        }
        saveAsync(dataUrl, mimeType)
    }

    /** Called by MainActivity after its existing permission launcher returns. */
    fun onLegacyPermissionResult(granted: Boolean) {
        val download = pending ?: return
        pending = null
        if (granted) {
            saveAsync(download.dataUrl, download.mimeType)
        } else {
            showToast(R.string.download_failed)
        }
    }

    private fun saveAsync(dataUrl: String, mimeType: String) {
        executor.execute {
            runCatching {
                val comma = dataUrl.indexOf(',')
                require(comma > 0) { "invalid data URL" }

                val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
                require(bytes.isNotEmpty()) { "empty media" }

                val normalizedMime = mimeType.substringBefore(';')
                    .ifBlank { dataUrl.substring(5, comma).substringBefore(';') }
                    .ifBlank { "application/octet-stream" }
                val extension = MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(normalizedMime)
                    ?: when {
                        normalizedMime.startsWith("image/") -> "jpg"
                        normalizedMime.startsWith("video/") -> "mp4"
                        normalizedMime.startsWith("audio/") -> "mp3"
                        else -> "bin"
                    }
                val filename = uniqueFilename("QBooK_${System.currentTimeMillis()}.$extension")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveWithMediaStore(bytes, normalizedMime, filename)
                } else {
                    saveLegacy(bytes, filename)
                }

                showToast(R.string.download_completed)
            }.onFailure {
                showToast(R.string.download_failed)
            }
        }
    }

    private fun needsLegacyStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED

    private fun saveWithMediaStore(bytes: ByteArray, mimeType: String, filename: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("MediaStore output stream unavailable")
            val complete = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(uri, complete, null, null)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(bytes: ByteArray, filename: String) {
        val directory = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        ).apply { mkdirs() }
        File(directory, filename).outputStream().use { it.write(bytes) }
    }

    @Suppress("DEPRECATION")
    private fun uniqueFilename(base: String): String {
        val directory = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        var candidate = base
        var counter = 1
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val extension = if (dot > 0) base.substring(dot) else ""
        while (File(directory, candidate).exists() && counter < 500) {
            candidate = "$stem($counter)$extension"
            counter++
        }
        return candidate
    }

    private fun showToast(messageRes: Int) {
        android.os.Handler(appContext.mainLooper).post {
            Toast.makeText(appContext, messageRes, Toast.LENGTH_SHORT).show()
        }
    }

    private data class PendingDownload(val dataUrl: String, val mimeType: String)
}

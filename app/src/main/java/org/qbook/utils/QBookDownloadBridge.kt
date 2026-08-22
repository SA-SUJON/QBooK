package org.qbook.utils

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import org.qbook.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Saves media fetched by the in-page downloader without replacing QBooK's
 * existing WebView DownloadListener pipeline.
 *
 * Labs options are consumed here, at the final save boundary: organized paths
 * affect the destination and filename, audio extraction emits a second M4A
 * when the downloaded video contains an AAC audio track, and batch save
 * queues several media items through the same single executor.
 */
class QBookDownloadBridge(
    context: Context,
    private val requestLegacyStoragePermission: () -> Unit,
    private val onStatus: (String) -> Unit = {},
    private val labsSavePaths: () -> Boolean = { false },
    private val labsAudioExtraction: () -> Boolean = { false },
    private val labsBatchSave: () -> Boolean = { false }
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "qbook-media-save").apply { isDaemon = true }
    }
    @Volatile private var pending: PendingDownload? = null

    @JavascriptInterface
    @Suppress("unused")
    fun isBatchSaveEnabled(): Boolean = labsBatchSave()

    @JavascriptInterface
    @Suppress("unused")
    fun downloadBase64File(dataUrl: String, mimeType: String) {
        if (needsLegacyStoragePermission()) {
            pending = PendingDownload(dataUrl, mimeType)
            android.os.Handler(appContext.mainLooper).post { requestLegacyStoragePermission() }
            return
        }
        saveAsync(dataUrl, mimeType)
    }

    /** Called by the Labs downloader for a JSON array of {dataUrl,mimeType}. */
    @JavascriptInterface
    @Suppress("unused")
    fun downloadBatch(itemsJson: String) {
        if (!labsBatchSave()) {
            showToast(R.string.download_failed)
            return
        }
        if (needsLegacyStoragePermission()) {
            pending = PendingDownload(itemsJson, batch = true)
            android.os.Handler(appContext.mainLooper).post { requestLegacyStoragePermission() }
            return
        }
        saveBatchAsync(itemsJson)
    }

    /** Extract audio from a completed DownloadManager URI when supported. */
    fun extractAudioFromUri(uriString: String, baseName: String) {
        if (!labsAudioExtraction()) return
        executor.execute {
            runCatching {
                val bytes = appContext.contentResolver.openInputStream(android.net.Uri.parse(uriString))
                    ?.use { it.readBytes() }
                    ?: return@runCatching
                extractAudio(bytes, baseName.substringBeforeLast('.'))
            }
        }
    }

    /** Called by MainActivity after its existing permission launcher returns. */
    fun onLegacyPermissionResult(granted: Boolean) {
        val download = pending ?: return
        pending = null
        if (!granted) {
            showToast(R.string.download_failed)
            return
        }
        if (download.batch) saveBatchAsync(download.dataUrl)
        else saveAsync(download.dataUrl, download.mimeType)
    }

    private fun saveAsync(dataUrl: String, mimeType: String) {
        onStatus("saving")
        executor.execute {
            saveOne(dataUrl, mimeType, showCompletion = true)
            onStatus("finished")
        }
    }

    private fun saveBatchAsync(itemsJson: String) {
        onStatus("saving")
        executor.execute {
            val items = parseBatch(itemsJson)
            var saved = 0
            items.forEach { item ->
                if (saveOne(item.first, item.second, showCompletion = false)) saved++
            }
            if (saved > 0) showToast(R.string.download_completed)
            else showToast(R.string.download_failed)
            onStatus("finished")
        }
    }

    private fun parseBatch(itemsJson: String): List<Pair<String, String>> = runCatching {
        val array = JSONArray(itemsJson)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.opt(index)
                if (item is JSONObject) {
                    val dataUrl = item.optString("dataUrl")
                    val mimeType = item.optString("mimeType", "application/octet-stream")
                    if (dataUrl.startsWith("data:")) add(dataUrl to mimeType)
                } else if (item is String && item.startsWith("data:")) {
                    add(item to "application/octet-stream")
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun saveOne(dataUrl: String, mimeType: String, showCompletion: Boolean): Boolean =
        runCatching {
            val comma = dataUrl.indexOf(',')
            require(comma > 0) { "invalid data URL" }

            val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
            require(bytes.isNotEmpty()) { "empty media" }

            val normalizedMime = mimeType.substringBefore(';')
                .ifBlank { dataUrl.substring(5, comma).substringBefore(';') }
                .ifBlank { "application/octet-stream" }
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(normalizedMime)
                ?: when {
                    normalizedMime.startsWith("image/") -> "jpg"
                    normalizedMime.startsWith("video/") -> "mp4"
                    normalizedMime.startsWith("audio/") -> "mp3"
                    else -> "bin"
                }
            val filename = nextFilename(normalizedMime, extension)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(bytes, normalizedMime, filename)
            } else {
                saveLegacy(bytes, normalizedMime, filename)
            }

            if (labsAudioExtraction() && normalizedMime.startsWith("video/")) {
                extractAudio(bytes, filename.substringBeforeLast('.'))
            }
            if (showCompletion) showToast(R.string.download_completed)
            true
        }.getOrElse {
            if (showCompletion) showToast(R.string.download_failed)
            false
        }

    private fun nextFilename(mimeType: String, extension: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = if (labsSavePaths()) {
            val kind = mimeType.substringAfter('/', "media").replace(Regex("[^A-Za-z0-9]"), "")
            "QBooK_${kind}_$stamp"
        } else {
            "QBooK_${System.currentTimeMillis()}"
        }
        return uniqueFilename("$base.$extension")
    }

    private fun outputFolder(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "Images"
        mimeType.startsWith("video/") -> "Videos"
        mimeType.startsWith("audio/") -> "Audio"
        else -> "Other"
    }

    private fun needsLegacyStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(bytes: ByteArray, mimeType: String, filename: String) {
        val relativePath = if (labsSavePaths()) {
            "${Environment.DIRECTORY_DOWNLOADS}/QBooK/${outputFolder(mimeType)}/"
        } else {
            "${Environment.DIRECTORY_DOWNLOADS}/"
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("MediaStore output stream unavailable")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(bytes: ByteArray, mimeType: String, filename: String) {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val directory = if (labsSavePaths()) File(base, "QBooK/${outputFolder(mimeType)}") else base
        directory.mkdirs()
        File(directory, filename).outputStream().use { it.write(bytes) }
    }

    @Suppress("DEPRECATION")
    private fun uniqueFilename(base: String): String {
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
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

    @Suppress("WrongConstant")
    private fun extractAudio(videoBytes: ByteArray, baseName: String) {
        val input = File.createTempFile("qbook-video-", ".mp4", appContext.cacheDir)
        val output = File.createTempFile("qbook-audio-", ".m4a", appContext.cacheDir)
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var started = false
        try {
            input.writeBytes(videoBytes)
            extractor = MediaExtractor().apply { setDataSource(input.absolutePath) }
            val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return
            val format = extractor.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime != "audio/mp4a-latm") return
            extractor.selectTrack(audioTrack)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = muxer.addTrack(format)
            muxer.start()
            started = true
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(track, buffer, info)
                extractor.advance()
                buffer.clear()
            }
            muxer.stop()
            started = false
            val audioName = uniqueFilename("${baseName}_audio.m4a")
            val audioBytes = output.readBytes()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(audioBytes, "audio/mp4", audioName)
            } else {
                saveLegacy(audioBytes, "audio/mp4", audioName)
            }
        } catch (_: Throwable) {
            // Unsupported codecs and malformed media should not turn a valid
            // video download into a failed download.
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
            input.delete()
            output.delete()
        }
    }

    private fun showToast(messageRes: Int) {
        android.os.Handler(appContext.mainLooper).post {
            Toast.makeText(appContext, messageRes, Toast.LENGTH_SHORT).show()
        }
    }

    private data class PendingDownload(
        val dataUrl: String,
        val mimeType: String = "",
        val batch: Boolean = false
    )
}

package org.qbook.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.content.FileProvider
import org.qbook.R
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Copies an image fetched by the WebView into the system clipboard. */
class ClipboardBridge(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "qbook-clipboard").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    @Suppress("unused")
    fun copyImageToClipboard(base64Data: String, mimeType: String) {
        executor.execute {
            val result = runCatching {
                val comma = base64Data.indexOf(',')
                require(comma > 0) { "invalid image data" }
                val bytes = Base64.decode(base64Data.substring(comma + 1), Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("invalid image")
                val directory = File(appContext.cacheDir, "clipboard_images").apply { mkdirs() }
                directory.listFiles()?.forEach { file ->
                    if (System.currentTimeMillis() - file.lastModified() > 60 * 60 * 1000L) {
                        file.delete()
                    }
                }
                val imageFile = File(directory, "clipboard_${System.currentTimeMillis()}.png")
                FileOutputStream(imageFile).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
                bitmap.recycle()
                val contentUri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    imageFile
                )
                main.post {
                    val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newUri(appContext.contentResolver, "Image", contentUri))
                    Toast.makeText(appContext, R.string.media_copied_to_clipboard, Toast.LENGTH_SHORT).show()
                }
            }
            if (result.isFailure) {
                main.post {
                    Toast.makeText(appContext, R.string.clipboard_copy_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

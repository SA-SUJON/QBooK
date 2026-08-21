package org.qbook.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.qbook.R
import org.qbook.utils.Prefs
import org.qbook.utils.NativeTypography
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only index for media saved by QBooK and Android's DownloadManager.
 * The screen deliberately reads the public Downloads collection and never
 * uploads or copies media into a second private vault.
 */
class DownloadCenterActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Prefs(this).amoled) theme.applyStyle(R.style.ThemeOverlay_Amoled, true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_center)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(
            R.id.download_center_toolbar
        )
        toolbar.setNavigationOnClickListener { finish() }
        summary = findViewById(R.id.download_center_summary)
        list = findViewById(R.id.download_center_list)
        loadItems()
        NativeTypography.applyActivity(this)
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) {
            loadItems()
            NativeTypography.applyActivity(this)
        }
    }

    private fun loadItems() {
        list.removeAllViews()
        val items = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) queryMediaStore() else queryLegacyFiles()
        summary.text = if (items.isEmpty()) {
            getString(R.string.labs_download_center_empty)
        } else {
            getString(R.string.labs_download_center_count, items.size)
        }
        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.labs_download_center_empty_detail)
                setTextColor(getColor(R.color.fb_text_dim))
                textSize = 15f
                setPadding(20, 28, 20, 20)
            }
            list.addView(empty)
        } else {
            items.forEach { addRow(it) }
        }
    }

    private fun queryMediaStore(): List<DownloadItem> {
        val result = mutableListOf<DownloadItem>()
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.DATE_ADDED,
            MediaStore.Downloads.SIZE
        )
        contentResolver.query(
            collection,
            projection,
            "${MediaStore.Downloads.IS_PENDING}=0",
            null,
            "${MediaStore.Downloads.DATE_ADDED} DESC"
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
            val date = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            val size = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            while (cursor.moveToNext()) {
                val uri = Uri.withAppendedPath(collection, cursor.getLong(id).toString())
                result += DownloadItem(
                    cursor.getString(name) ?: getString(R.string.labs_unknown_file),
                    cursor.getString(mime) ?: "application/octet-stream",
                    cursor.getLong(date) * 1000L,
                    cursor.getLong(size),
                    uri
                )
            }
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun queryLegacyFiles(): List<DownloadItem> =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                DownloadItem(
                    file.name,
                    contentResolver.getType(Uri.fromFile(file)) ?: "application/octet-stream",
                    file.lastModified(),
                    file.length(),
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                )
            }
            ?: emptyList()

    private fun addRow(item: DownloadItem) {
        val card = MaterialCardView(this).apply {
            radius = 18f
            setCardBackgroundColor(getColor(R.color.fb_surface))
            strokeWidth = 1
            strokeColor = getColor(R.color.fb_border)
            useCompatPadding = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10 }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 14, 14)
        }
        val title = TextView(this).apply {
            text = item.name
            setTextColor(getColor(R.color.fb_text))
            textSize = 15f
            maxLines = 2
        }
        val meta = TextView(this).apply {
            text = "${item.mime} · ${formatSize(item.size)} · ${formatDate(item.date)}"
            setTextColor(getColor(R.color.fb_text_dim))
            textSize = 12f
            setPadding(0, 5, 0, 8)
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val preview = MaterialButton(this).apply {
            text = getString(R.string.labs_preview)
            setOnClickListener { openItem(item, false) }
        }
        val share = MaterialButton(this).apply {
            text = getString(R.string.labs_share)
            setOnClickListener { openItem(item, true) }
        }
        actions.addView(preview, LinearLayout.LayoutParams(0, 44, 1f))
        actions.addView(share, LinearLayout.LayoutParams(0, 44, 1f).apply { marginStart = 8 })
        content.addView(title)
        content.addView(meta)
        content.addView(actions)
        card.addView(content)
        list.addView(card)
    }

    private fun openItem(item: DownloadItem, share: Boolean) {
        try {
            val intent = if (share) {
                Intent(Intent.ACTION_SEND).apply {
                    type = item.mime
                    putExtra(Intent.EXTRA_STREAM, item.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(item.uri, item.mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            startActivity(if (share) Intent.createChooser(intent, getString(R.string.labs_share)) else intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.labs_no_handler, Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
    }

    private fun formatDate(time: Long): String = DateFormat.getDateTimeInstance(
        DateFormat.SHORT, DateFormat.SHORT
    ).format(Date(time))

    private data class DownloadItem(
        val name: String,
        val mime: String,
        val date: Long,
        val size: Long,
        val uri: Uri
    )
}

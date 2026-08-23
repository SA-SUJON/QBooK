package org.qbook.ui

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.qbook.R
import org.qbook.utils.NativeTypography
import org.qbook.utils.Prefs
import org.qbook.utils.QBookDownloadBridge
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Local media manager for files visible in Android's Downloads collection. */
class DownloadCenterActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private lateinit var search: EditText
    private var allItems: List<DownloadItem> = emptyList()
    private var sortMode = SortMode.NEWEST
    private var downloadBridge: QBookDownloadBridge? = null

    private enum class SortMode { NEWEST, LARGEST, NAME }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startupPrefs = Prefs(this)
        if (startupPrefs.amoled) theme.applyStyle(R.style.ThemeOverlay_Amoled, true)
        super.onCreate(savedInstanceState)
        ScreenMotion.enter(this)
        setContentView(R.layout.activity_download_center)

        findViewById<MaterialToolbar>(R.id.download_center_toolbar).apply {
            setNavigationOnClickListener { finish() }
        }
        summary = findViewById(R.id.download_center_summary)
        list = findViewById(R.id.download_center_list)
        search = findViewById(R.id.download_search)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        findViewById<MaterialButton>(R.id.download_sort).setOnClickListener { showSortDialog() }
        downloadBridge = QBookDownloadBridge(
            this,
            requestLegacyStoragePermission = {},
            onStatus = {},
            labsSavePaths = { Prefs(this).labsSavePaths },
            labsAudioExtraction = { Prefs(this).labsAudioExtraction },
            labsBatchSave = { false }
        )
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

    override fun onDestroy() {
        downloadBridge = null
        super.onDestroy()
    }

    private fun loadItems() {
        allItems = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) queryMediaStore() else queryLegacyFiles()
        render()
    }

    private fun render() {
        val query = search.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val filtered = allItems.filter { query.isBlank() ||
            it.name.lowercase(Locale.getDefault()).contains(query) ||
                it.mime.lowercase(Locale.getDefault()).contains(query)
        }.let { items ->
            when (sortMode) {
                SortMode.NEWEST -> items.sortedByDescending { it.date }
                SortMode.LARGEST -> items.sortedByDescending { it.size }
                SortMode.NAME -> items.sortedBy { it.name.lowercase(Locale.getDefault()) }
            }
        }

        summary.text = if (query.isBlank()) {
            getString(if (allItems.isEmpty()) R.string.labs_download_center_empty else R.string.labs_download_center_count, allItems.size)
        } else {
            getString(R.string.download_search_count, filtered.size, allItems.size)
        }
        list.removeAllViews()
        if (filtered.isEmpty()) {
            list.addView(TextView(this).apply {
                text = getString(if (allItems.isEmpty()) R.string.labs_download_center_empty_detail else R.string.download_no_matches)
                setTextColor(getColor(R.color.fb_text_dim))
                textSize = 15f
                setPadding(20, 28, 20, 20)
            })
        } else {
            filtered.forEach { addRow(it) }
        }
    }

    private fun showSortDialog() {
        val labels = arrayOf(
            getString(R.string.download_sort_newest),
            getString(R.string.download_sort_largest),
            getString(R.string.download_sort_name)
        )
        val checked = sortMode.ordinal
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_sort)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                sortMode = SortMode.entries[which]
                findViewById<MaterialButton>(R.id.download_sort).text = labels[which]
                dialog.dismiss()
                render()
            }
            .show()
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
            null
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
            val date = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            val size = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            while (cursor.moveToNext()) {
                val uri = Uri.withAppendedPath(collection, cursor.getLong(id).toString())
                result += DownloadItem(
                    name = cursor.getString(name) ?: getString(R.string.labs_unknown_file),
                    mime = cursor.getString(mime) ?: contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" },
                    date = cursor.getLong(date) * 1000L,
                    size = cursor.getLong(size),
                    uri = uri,
                    file = null
                )
            }
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun queryLegacyFiles(): List<DownloadItem> {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return root.walkTopDown().maxDepth(5).filter { it.isFile }.map { file ->
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            DownloadItem(
                name = file.name,
                mime = contentResolver.getType(uri) ?: mimeFromName(file.name),
                date = file.lastModified(),
                size = file.length(),
                uri = uri,
                file = file
            )
        }.toList()
    }

    private fun addRow(item: DownloadItem) {
        val card = MaterialCardView(this).apply {
            radius = 18f
            setCardBackgroundColor(getColor(R.color.fb_surface))
            strokeWidth = 1
            strokeColor = getColor(R.color.fb_border)
            useCompatPadding = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 14, 14)
        }
        content.addView(TextView(this).apply {
            text = item.name
            setTextColor(getColor(R.color.fb_text))
            textSize = 15f
            maxLines = 2
        })
        content.addView(TextView(this).apply {
            text = "${item.mime} · ${formatSize(item.size)} · ${formatDate(item.date)}"
            setTextColor(getColor(R.color.fb_text_dim))
            textSize = 12f
            setPadding(0, 5, 0, 8)
        })
        val firstActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        firstActions.addView(actionButton(R.string.labs_preview) { openItem(item, false) }, weightParams())
        firstActions.addView(actionButton(R.string.labs_share) { openItem(item, true) }, weightParams(8))
        firstActions.addView(actionButton(R.string.download_rename) { showRenameDialog(item) }, weightParams(8))
        content.addView(firstActions)

        val secondActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        secondActions.addView(actionButton(R.string.download_delete) { confirmDelete(item) }, weightParams())
        if (item.mime.startsWith("video/")) {
            secondActions.addView(actionButton(R.string.download_audio_extract) { extractAudio(item) }, weightParams(8))
        }
        content.addView(secondActions)
        card.addView(content)
        list.addView(card)
    }

    private fun actionButton(textRes: Int, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = getString(textRes)
        setTextSize(11f)
        minHeight = dp(44)
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener { action() }
    }

    private fun weightParams(marginStart: Int = 0) = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
        if (marginStart > 0) this.marginStart = dp(marginStart)
    }

    private fun showRenameDialog(item: DownloadItem) {
        val input = EditText(this).apply {
            setText(item.name)
            setSelection(text.length)
            setSingleLine(true)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_rename_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.download_rename) { _, _ ->
                val newName = safeFilename(input.text.toString(), item.name)
                if (renameItem(item, newName)) {
                    toast(R.string.download_rename_success)
                    loadItems()
                } else toast(R.string.download_rename_failed)
            }
            .show()
    }

    private fun renameItem(item: DownloadItem, newName: String): Boolean {
        if (newName.isBlank()) return false
        return if (item.file != null) {
            val target = File(item.file.parentFile, newName)
            item.file.renameTo(target)
        } else {
            contentResolver.update(item.uri, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, newName)
            }, null, null) > 0
        }
    }

    private fun confirmDelete(item: DownloadItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_delete)
            .setMessage(getString(R.string.download_delete_confirm, item.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.download_delete) { _, _ ->
                val deleted = if (item.file != null) item.file.delete() else contentResolver.delete(item.uri, null, null) > 0
                if (deleted) {
                    toast(R.string.download_deleted)
                    loadItems()
                } else toast(R.string.download_rename_failed)
            }
            .show()
    }

    private fun extractAudio(item: DownloadItem) {
        if (!Prefs(this).labsAudioExtraction) {
            toast(R.string.download_audio_disabled)
            return
        }
        downloadBridge?.extractAudioFromUri(item.uri.toString(), item.name.substringBeforeLast('.'))
        toast(R.string.download_audio_started)
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
            toast(R.string.labs_no_handler)
        }
    }

    private fun safeFilename(raw: String, oldName: String): String {
        val clean = raw.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (clean.isBlank()) return oldName
        val oldExtension = oldName.substringAfterLast('.', "").takeIf { oldName.contains('.') }
        return if (oldExtension != null && !clean.substringAfterLast('.', "").equals(oldExtension, true)) {
            "$clean.$oldExtension"
        } else clean
    }

    private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
    }

    private fun formatDate(time: Long): String = DateFormat.getDateTimeInstance(
        DateFormat.SHORT, DateFormat.SHORT
    ).format(Date(time))

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private data class DownloadItem(
        val name: String,
        val mime: String,
        val date: Long,
        val size: Long,
        val uri: Uri,
        val file: File?
    )
}

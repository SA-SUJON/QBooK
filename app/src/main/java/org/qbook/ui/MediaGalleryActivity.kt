package org.qbook.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import org.qbook.R
import org.qbook.utils.NativeTypography
import org.qbook.utils.Prefs
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * A local-only gallery for media saved by QBooK. It deliberately scopes the
 * query to QBooK-created files instead of exposing the user's whole Downloads
 * directory.
 */
class MediaGalleryActivity : AppCompatActivity() {
    private lateinit var grid: GridLayout
    private lateinit var summary: TextView
    private lateinit var empty: TextView
    private var allItems: List<GalleryItem> = emptyList()
    private var selectedFilter = Filter.ALL

    private enum class Filter { ALL, IMAGES, VIDEOS, AUDIO }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startupPrefs = Prefs(this)
        AppCompatDelegate.setDefaultNightMode(startupPrefs.nightMode())
        if (startupPrefs.amoled) theme.applyStyle(R.style.ThemeOverlay_Amoled, true)
        super.onCreate(savedInstanceState)
        ScreenMotion.enter(this)
        setContentView(R.layout.activity_media_gallery)

        if (startupPrefs.labsAnimatedTheme) {
            findViewById<View>(R.id.gallery_root).apply {
                alpha = 0f
                animate().alpha(1f).setDuration(240L).start()
            }
        }
        findViewById<View>(R.id.gallery_close).setOnClickListener { finish() }
        summary = findViewById(R.id.gallery_summary)
        empty = findViewById(R.id.gallery_empty)
        grid = findViewById(R.id.gallery_grid)
        buildFilters()
        loadItems()
        NativeTypography.applyActivity(this)
    }

    override fun onResume() {
        super.onResume()
        if (::grid.isInitialized) {
            loadItems()
            NativeTypography.applyActivity(this)
        }
    }

    private fun buildFilters() {
        val filters = findViewById<LinearLayout>(R.id.gallery_filters)
        filters.removeAllViews()
        addFilter(filters, Filter.ALL, R.string.labs_gallery_all, R.drawable.ic_gallery_image)
        addFilter(filters, Filter.IMAGES, R.string.labs_gallery_images, R.drawable.ic_gallery_image)
        addFilter(filters, Filter.VIDEOS, R.string.labs_gallery_videos, R.drawable.ic_gallery_video)
        addFilter(filters, Filter.AUDIO, R.string.labs_gallery_audio, R.drawable.ic_gallery_audio)
    }

    private fun addFilter(parent: LinearLayout, filter: Filter, textRes: Int, iconRes: Int) {
        val button = MaterialButton(this).apply {
            text = getString(textRes)
            icon = ContextCompat.getDrawable(context, iconRes)
            iconTint = ColorStateList.valueOf(primaryColor())
            cornerRadius = dp(24)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(resolveColor(R.attr.qbookSettingsGlassStroke))
            setOnClickListener {
                selectedFilter = filter
                updateFilterStyles(parent)
                render()
            }
        }
        button.tag = filter
        parent.addView(button, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(48)
        ).apply { marginEnd = dp(8) })
        updateFilterStyles(parent)
    }

    private fun updateFilterStyles(parent: LinearLayout) {
        for (index in 0 until parent.childCount) {
            val button = parent.getChildAt(index) as? MaterialButton ?: continue
            val selected = button.tag == selectedFilter
            button.backgroundTintList = ColorStateList.valueOf(
                if (selected) withAlpha(primaryColor(), 42) else Color.TRANSPARENT
            )
            button.setTextColor(if (selected) primaryColor() else resolveColor(com.google.android.material.R.attr.colorOnBackground))
        }
    }

    private fun loadItems() {
        allItems = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryMediaStore()
        } else {
            queryLegacyFiles()
        }
        render()
    }

    private fun render() {
        val filtered = allItems.filter { item ->
            when (selectedFilter) {
                Filter.ALL -> true
                Filter.IMAGES -> item.mime.startsWith("image/")
                Filter.VIDEOS -> item.mime.startsWith("video/")
                Filter.AUDIO -> item.mime.startsWith("audio/")
            }
        }
        summary.text = getString(R.string.labs_gallery_count, filtered.size)
        grid.removeAllViews()
        empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        filtered.forEach { addTile(it) }
    }

    private fun queryMediaStore(): List<GalleryItem> {
        val result = mutableListOf<GalleryItem>()
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.DATE_ADDED,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.RELATIVE_PATH
        )
        val selection = "(${MediaStore.Downloads.DISPLAY_NAME} LIKE ? OR ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?) AND ${MediaStore.Downloads.IS_PENDING}=0"
        contentResolver.query(
            collection,
            projection,
            selection,
            arrayOf("QBooK_%", "%/QBooK/%"),
            "${MediaStore.Downloads.DATE_ADDED} DESC"
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
            val date = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            val size = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            while (cursor.moveToNext()) {
                val uri = Uri.withAppendedPath(collection, cursor.getLong(id).toString())
                result += GalleryItem(
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
    private fun queryLegacyFiles(): List<GalleryItem> {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return root.walkTopDown()
            .maxDepth(4)
            .filter { file ->
                file.isFile && (file.name.startsWith("QBooK_") || file.path.contains("/QBooK/"))
            }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                GalleryItem(
                    name = file.name,
                    mime = contentResolver.getType(uri) ?: mimeFromName(file.name),
                    date = file.lastModified(),
                    size = file.length(),
                    uri = uri,
                    file = file
                )
            }
            .toList()
    }

    private fun addTile(item: GalleryItem) {
        val card = MaterialCardView(this).apply {
            radius = dp(22).toFloat()
            setCardBackgroundColor(resolveColor(R.attr.qbookSettingsGlassSurface))
            strokeWidth = dp(1)
            strokeColor = resolveColor(R.attr.qbookSettingsGlassStroke)
            cardElevation = dp(3).toFloat()
            useCompatPadding = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val preview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(132)
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = item.name
            if (item.mime.startsWith("image/")) {
                setImageURI(item.uri)
            } else {
                setImageResource(
                    when {
                        item.mime.startsWith("video/") -> R.drawable.ic_gallery_video
                        item.mime.startsWith("audio/") -> R.drawable.ic_gallery_audio
                        else -> R.drawable.ic_gallery_image
                    }
                )
                setPadding(dp(44), dp(44), dp(44), dp(44))
                setBackgroundColor(withAlpha(resolveColor(R.attr.qbookSettingsBackground), 80))
                imageTintList = ColorStateList.valueOf(primaryColor())
            }
            setOnClickListener { preview(item) }
        }
        content.addView(preview)
        content.addView(TextView(this).apply {
            text = item.name
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnBackground))
            textSize = 13f
            maxLines = 2
            setPadding(dp(2), dp(9), dp(2), 0)
        })
        content.addView(TextView(this).apply {
            text = "${formatSize(item.size)} · ${formatDate(item.date)}"
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            textSize = 11f
            setPadding(dp(2), dp(3), dp(2), dp(7))
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(actionButton(R.string.labs_gallery_preview, R.drawable.ic_gallery_video) { preview(item) }, LinearLayout.LayoutParams(0, dp(42), 1f))
        actions.addView(actionButton(R.string.labs_gallery_share, R.drawable.ic_gallery_share) { share(item) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
        actions.addView(actionButton(R.string.labs_gallery_delete, R.drawable.ic_gallery_delete) { confirmDelete(item) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
        content.addView(actions)
        card.addView(content)
        val column = GridLayout.LayoutParams().apply {
            width = 0
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
        grid.addView(card, column)
    }

    private fun actionButton(textRes: Int, iconRes: Int, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = getString(textRes)
        icon = ContextCompat.getDrawable(context, iconRes)
        iconTint = ColorStateList.valueOf(primaryColor())
        cornerRadius = dp(18)
        setTextSize(10f)
        setPadding(dp(3), 0, dp(3), 0)
        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(resolveColor(R.attr.qbookSettingsGlassStroke))
        setOnClickListener { action() }
    }

    private fun preview(item: GalleryItem) {
        if (item.mime.startsWith("image/")) {
            val image = ImageView(this).apply {
                setImageURI(item.uri)
                adjustViewBounds = true
                maxHeight = (resources.displayMetrics.heightPixels * 0.62f).toInt()
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(item.name)
                .setView(image)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        openExternal(item)
    }

    private fun share(item: GalleryItem) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mime
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.labs_gallery_share))) }
            .onFailure { toast(R.string.labs_gallery_no_handler) }
    }

    private fun openExternal(item: GalleryItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, item.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { toast(R.string.labs_gallery_no_handler) }
    }

    private fun confirmDelete(item: GalleryItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.labs_gallery_delete_title)
            .setMessage(getString(R.string.labs_gallery_delete_message, item.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.labs_gallery_delete) { _, _ ->
                val deleted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.delete(item.uri, null, null) > 0
                } else {
                    item.file?.delete() == true
                }
                if (deleted) {
                    toast(R.string.labs_gallery_deleted)
                    loadItems()
                } else {
                    toast(R.string.labs_gallery_delete_failed)
                }
            }
            .show()
    }

    private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "jpg", "jpeg", "png", "webp", "gif" -> "image/*"
        "mp4", "webm", "mkv", "3gp" -> "video/*"
        "mp3", "m4a", "aac", "ogg", "wav" -> "audio/*"
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

    private fun primaryColor(): Int = resolveColor(com.google.android.material.R.attr.colorPrimary)

    private fun resolveColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(messageRes: Int) = Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()

    private data class GalleryItem(
        val name: String,
        val mime: String,
        val date: Long,
        val size: Long,
        val uri: Uri,
        val file: File?
    )
}

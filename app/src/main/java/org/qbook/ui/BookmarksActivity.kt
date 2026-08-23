package org.qbook.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.qbook.R
import org.qbook.utils.BookmarkStore
import org.qbook.utils.NativeTypography
import org.qbook.utils.Prefs
import java.text.DateFormat
import java.util.Date

/** Local bookmark browser for captured Facebook post markup. */
class BookmarksActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private var preview: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val startupPrefs = Prefs(this)
        if (startupPrefs.amoled) theme.applyStyle(R.style.ThemeOverlay_Amoled, true)
        super.onCreate(savedInstanceState)
        ScreenMotion.enter(this)
        buildUi()
        render()
        NativeTypography.applyActivity(this)
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized && preview == null) render()
        NativeTypography.applyActivity(this)
    }

    override fun onBackPressed() {
        if (preview != null) {
            preview?.destroy()
            preview = null
            buildUi()
            render()
        } else {
            super.onBackPressed()
        }
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface))
        }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.bookmarks_title)
            setTitleTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface))
            navigationIcon = ContextCompat.getDrawable(context, R.drawable.ic_back)
            navigationIcon?.setTint(resolveColor(com.google.android.material.R.attr.colorOnSurface))
            setNavigationOnClickListener { onBackPressed() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(64)))
        summary = TextView(this).apply {
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            textSize = 14f
            setPadding(dp(20), dp(4), dp(20), dp(12))
        }
        root.addView(summary)
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(32))
        }
        scroll.addView(list, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun render() {
        val items = BookmarkStore.list(this)
        summary.text = getString(R.string.bookmarks_count, items.size)
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = getString(R.string.bookmarks_empty)
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                textSize = 16f
                setPadding(dp(8), dp(48), dp(8), dp(20))
            })
            return
        }
        items.forEach { addBookmarkCard(it) }
    }

    private fun addBookmarkCard(item: BookmarkStore.Bookmark) {
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurfaceContainer))
            strokeWidth = dp(1)
            strokeColor = resolveColor(com.google.android.material.R.attr.colorOutlineVariant)
            useCompatPadding = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }
        content.addView(TextView(this).apply {
            text = item.title
            textSize = 18f
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 3
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.bookmarks_saved_at, formatDate(item.createdAt))
            textSize = 12f
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(5), 0, dp(10))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val open = MaterialButton(this).apply {
            text = getString(R.string.bookmarks_open)
            icon = ContextCompat.getDrawable(context, R.drawable.ic_open_external)
            setOnClickListener { openBookmark(item) }
        }
        actions.addView(open, LinearLayout.LayoutParams(0, dp(48), 1f))
        val delete = MaterialButton(this).apply {
            text = getString(R.string.bookmarks_delete)
            icon = ContextCompat.getDrawable(context, R.drawable.ic_gallery_delete)
            setOnClickListener { confirmDelete(item) }
        }
        actions.addView(delete, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        content.addView(actions)
        card.addView(content)
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    private fun openBookmark(item: BookmarkStore.Bookmark) {
        preview?.destroy()
        val web = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            webViewClient = WebViewClient()
            loadDataWithBaseURL(
                item.url.ifBlank { "https://www.facebook.com/" },
                item.html,
                "text/html",
                "UTF-8",
                item.url
            )
        }
        preview = web
        root.removeAllViews()
        val toolbar = MaterialToolbar(this).apply {
            title = item.title
            setTitleTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface))
            navigationIcon = ContextCompat.getDrawable(context, R.drawable.ic_back)
            navigationIcon?.setTint(resolveColor(com.google.android.material.R.attr.colorOnSurface))
            setNavigationOnClickListener { onBackPressed() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun confirmDelete(item: BookmarkStore.Bookmark) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bookmarks_delete)
            .setMessage(getString(R.string.bookmarks_delete_confirm, item.title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.bookmarks_delete) { _, _ ->
                BookmarkStore.delete(this, item.id)
                render()
            }
            .show()
    }

    private fun formatDate(time: Long): String = DateFormat.getDateTimeInstance(
        DateFormat.SHORT, DateFormat.SHORT
    ).format(Date(time))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun resolveColor(attribute: Int): Int {
        val typed = android.util.TypedValue()
        theme.resolveAttribute(attribute, typed, true)
        return if (typed.resourceId != 0) getColor(typed.resourceId) else typed.data
    }
}

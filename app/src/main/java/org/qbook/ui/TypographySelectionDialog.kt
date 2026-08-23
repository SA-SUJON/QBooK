package org.qbook.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.qbook.R
import org.qbook.utils.NativeTypography

/** Branded font-selection surface shared by Appearance and Typography Preview. */
object TypographySelectionDialog {
    fun show(
        context: Context,
        title: String,
        subtitle: String,
        entries: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val primary = ContextCompat.getColor(context, R.color.primary)
        val titleColor = ContextCompat.getColor(context, R.color.settings_title_foreground)
        val bodyColor = ContextCompat.getColor(context, R.color.settings_body_foreground)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            setBackgroundResource(R.drawable.bg_launcher_picker)
        }
        root.addView(TextView(context).apply {
            text = title
            textSize = 21f
            setTextColor(titleColor)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
        root.addView(TextView(context).apply {
            text = subtitle
            textSize = 13f
            setTextColor(bodyColor)
            setPadding(0, dp(2), 0, dp(10))
        })

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        val scroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(430)))

        val dialog = AlertDialog.Builder(context)
            .setView(root)
            .setNegativeButton(R.string.dialog_dismiss, null)
            .create()

        entries.forEachIndexed { index, entry ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(16), 0, dp(12), 0)
                setBackgroundResource(
                    if (index == selectedIndex) R.drawable.bg_settings_glass_pill
                    else R.drawable.bg_settings_option
                )
                setOnClickListener {
                    onSelected(index)
                    dialog.dismiss()
                }
            }
            val label = TextView(context).apply {
                text = entry
                textSize = 15f
                setTextColor(if (index == selectedIndex) primary else titleColor)
                setTypeface(typeface, if (index == selectedIndex) Typeface.BOLD else Typeface.NORMAL)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            row.addView(TextView(context).apply {
                text = if (index == selectedIndex) "✓" else ""
                textSize = 20f
                setTextColor(primary)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT))
            list.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply { bottomMargin = dp(6) })
        }

        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0.68f }
                setLayout(
                    minOf(context.resources.displayMetrics.widthPixels - dp(24), dp(420)),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(primary)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                minWidth = dp(88)
            }
        }
        dialog.show()
        NativeTypography.applyDialog(dialog, context)
    }
}

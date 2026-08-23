package org.qbook.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.qbook.R
import org.qbook.utils.FontManager
import org.qbook.utils.NativeTypography
import org.qbook.utils.PredefinedFonts
import org.qbook.utils.Prefs

/** Unsaved typography preview for the Appearance section. */
object TypographyPreviewDialog {
    private val scales = intArrayOf(75, 85, 90, 100, 110, 120, 135, 150, 175)

    fun show(
        context: Context,
        initialFamily: String,
        initialScale: Int,
        onApply: (family: String, scale: Int) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val titleColor = ContextCompat.getColor(context, R.color.settings_title_foreground)
        val bodyColor = ContextCompat.getColor(context, R.color.settings_body_foreground)
        val primary = ContextCompat.getColor(context, R.color.primary)

        val families = mutableListOf(getString(context, R.string.font_system) to FontManager.SYSTEM_VALUE)
        PredefinedFonts.all.forEach { families += it.name to it.asset }
        if (FontManager.hasCustomFont(context)) {
            families += context.getString(R.string.font_custom_fmt, Prefs(context).customFontName) to FontManager.CUSTOM_VALUE
        }
        var selectedFamily = if (families.any { it.second == initialFamily }) initialFamily else FontManager.SYSTEM_VALUE
        var selectedScale = scales.minByOrNull { kotlin.math.abs(it - initialScale) } ?: 100

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            setBackgroundResource(R.drawable.bg_launcher_picker)
        }
        root.addView(TextView(context).apply {
            text = "Preview Typography"
            textSize = 22f
            setTextColor(titleColor)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))
        root.addView(TextView(context).apply {
            text = "Tune your typeface and scale before committing the change."
            textSize = 13f
            setTextColor(bodyColor)
            setPadding(0, dp(2), 0, dp(12))
        })

        val styleLabel = TextView(context).apply {
            text = "Font style"
            textSize = 12f
            setTextColor(bodyColor)
        }
        root.addView(styleLabel)
        val styleSelector = TextView(context).apply {
            isClickable = true
            isFocusable = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundResource(R.drawable.bg_settings_option)
            textSize = 15f
            setTextColor(titleColor)
        }
        root.addView(styleSelector, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(6)
            bottomMargin = dp(14)
        })

        val sizeLabel = TextView(context).apply {
            textSize = 12f
            setTextColor(bodyColor)
        }
        root.addView(sizeLabel)
        val sizeBar = SeekBar(context).apply {
            max = scales.lastIndex
            progressTintList = android.content.res.ColorStateList.valueOf(primary)
            thumbTintList = android.content.res.ColorStateList.valueOf(primary)
        }
        root.addView(sizeBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        val preview = TextView(context).apply {
            text = "Aa  The quick brown fox jumps over the lazy dog\nবাংলা • 1234567890\nLiquid glass, clear hierarchy, and comfortable reading."
            setTextColor(titleColor)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(R.drawable.bg_settings_option)
            includeFontPadding = true
        }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(108)).apply {
            topMargin = dp(10)
        })

        fun renderPreview() {
            val selectedIndex = families.indexOfFirst { it.second == selectedFamily }.coerceAtLeast(0)
            styleSelector.text = families[selectedIndex].first
            sizeLabel.text = "Font scale  •  $selectedScale%"
            NativeTypography.apply(preview, context, selectedFamily, selectedScale)
        }
        styleSelector.setOnClickListener {
            TypographySelectionDialog.show(
                context = context,
                title = "Font style",
                subtitle = "Choose the typeface used across QBooK’s native surfaces.",
                entries = families.map { it.first },
                selectedIndex = families.indexOfFirst { it.second == selectedFamily }.coerceAtLeast(0)
            ) { index ->
                selectedFamily = families[index].second
                renderPreview()
            }
        }
        sizeBar.progress = scales.indexOf(selectedScale).coerceAtLeast(0)
        sizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedScale = scales[progress]
                renderPreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        renderPreview()

        val dialog = AlertDialog.Builder(context)
            .setView(root)
            .setNegativeButton(R.string.dialog_dismiss, null)
            .setPositiveButton("Apply", null)
            .create()
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
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(ContextCompat.getColor(context, R.color.on_primary))
                backgroundTintList = android.content.res.ColorStateList.valueOf(primary)
                minWidth = dp(88)
                setOnClickListener {
                    onApply(selectedFamily, selectedScale)
                    dialog.dismiss()
                }
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

    private fun getString(context: Context, id: Int): String = context.getString(id)
}

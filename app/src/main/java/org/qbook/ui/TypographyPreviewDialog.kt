package org.qbook.ui

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
        val padding = (24 * context.resources.displayMetrics.density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        val preview = TextView(context).apply {
            text = "Aa  The quick brown fox jumps over the lazy dog\nবাংলা • 1234567890"
            setTextColor(if ((context.resources.configuration.uiMode and 0x30) == 0x20) Color.WHITE else Color.BLACK)
            setPadding(0, padding / 2, 0, padding / 2)
        }
        val styleLabel = TextView(context).apply { text = "Font style" }
        val styleSpinner = Spinner(context)
        val sizeLabel = TextView(context)
        val sizeBar = SeekBar(context).apply { max = scales.lastIndex }

        val families = mutableListOf(getString(context, R.string.font_system) to FontManager.SYSTEM_VALUE)
        PredefinedFonts.all.forEach { families += it.name to it.asset }
        if (FontManager.hasCustomFont(context)) {
            families += context.getString(R.string.font_custom_fmt, Prefs(context).customFontName) to FontManager.CUSTOM_VALUE
        }
        styleSpinner.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            families.map { it.first }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        var selectedFamily = if (families.any { it.second == initialFamily }) initialFamily else FontManager.SYSTEM_VALUE
        var selectedScale = scales.minByOrNull { kotlin.math.abs(it - initialScale) } ?: 100
        styleSpinner.setSelection(families.indexOfFirst { it.second == selectedFamily }.coerceAtLeast(0))
        sizeBar.progress = scales.indexOf(selectedScale).coerceAtLeast(0)
        fun renderPreview() {
            sizeLabel.text = "Font size: $selectedScale%"
            NativeTypography.apply(preview, context, selectedFamily, selectedScale)
        }
        styleSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedFamily = families[position].second
                renderPreview()
            }
        }
        sizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedScale = scales[progress]
                renderPreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        root.addView(styleLabel, matchWrap())
        root.addView(styleSpinner, matchWrap())
        root.addView(sizeLabel, matchWrap())
        root.addView(sizeBar, matchWrap())
        root.addView(preview, matchWrap())
        renderPreview()

        val dialog = AlertDialog.Builder(context)
            .setTitle("Preview Typography")
            .setMessage("Try a font style and size. Nothing is saved until you press Apply.")
            .setView(root)
            .setNegativeButton(R.string.dialog_dismiss, null)
            .setPositiveButton("Apply", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                onApply(selectedFamily, selectedScale)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun getString(context: Context, id: Int): String = context.getString(id)
}

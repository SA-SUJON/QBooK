package com.dustbook.app.utils

import android.content.Context
import android.util.TypedValue
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * V4 Step 4: Small UI/UX utilities for consistency and polish.
 */
object UiUtils {

    fun dpToPx(context: Context, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }

    /**
     * Applies consistent edge-to-edge + keyboard handling.
     * Already mostly done in MainActivity, exposed here for reuse.
     */
    fun applyEdgeToEdgeWithKeyboard(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                maxOf(systemBars.bottom, ime.bottom)
            )
            insets
        }
    }

    /**
     * Simple haptic helper (used in multiple places).
     */
    fun performHaptic(view: View, type: Int = android.view.HapticFeedbackConstants.VIRTUAL_KEY) {
        view.performHapticFeedback(type)
    }
}
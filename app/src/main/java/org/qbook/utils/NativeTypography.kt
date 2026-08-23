package org.qbook.utils

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import java.util.WeakHashMap

/** Applies one consistent typography state to every QBooK-native surface. */
object NativeTypography {
    private data class Baseline(val typeface: Typeface?, val sizePx: Float)

    private val baselines = WeakHashMap<TextView, Baseline>()
    private val appliedStates = WeakHashMap<TextView, String>()
    private val appliedTypefaces = WeakHashMap<TextView, Typeface?>()
    private val observedRoots = WeakHashMap<View, ViewTreeObserver.OnGlobalLayoutListener>()

    /** Apply and observe an entire Activity window. */
    fun applyActivity(activity: Activity) {
        val root = activity.window.decorView
        val prefs = Prefs(activity)
        apply(root, activity, prefs.fontFamily, prefs.fontScale)
        observe(root, activity)
    }

    /** Apply typography recursively using an immutable baseline per TextView. */
    fun apply(root: View, context: Context, family: String, scalePercent: Int) {
        val typeface = FontManager.nativeTypeface(context, family)
        val customToken = if (family == FontManager.CUSTOM_VALUE) {
            Prefs(context).customFontName
        } else ""
        val targetScale = scalePercent.coerceIn(75, 175) / 100f
        walk(root) { textView ->
            val baseline = baselines.getOrPut(textView) {
                Baseline(textView.typeface, textView.textSize)
            }
            val desiredSize = baseline.sizePx * targetScale
            if (kotlin.math.abs(textView.textSize - desiredSize) > 0.25f) {
                textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, desiredSize)
            }
            val style = baseline.typeface?.style ?: Typeface.NORMAL
            val desiredTypeface = if (typeface == null) baseline.typeface else Typeface.create(typeface, style)
            val state = "${family.lowercase()}:$style:$customToken"
            val stateChanged = appliedStates[textView] != state
            val typefaceReset = !appliedTypefaces.containsKey(textView) ||
                textView.typeface !== appliedTypefaces[textView]
            if (stateChanged || typefaceReset) {
                textView.typeface = desiredTypeface
                appliedTypefaces[textView] = desiredTypeface
                appliedStates[textView] = state
            }
        }
    }

    fun restoreDefault(root: View) = apply(root, root.context, FontManager.SYSTEM_VALUE, 100)

    /** Apply and observe a dialog window after Dialog.show(). */
    fun applyDialog(dialog: Dialog, context: Context) {
        dialog.window?.decorView?.let { root ->
            val prefs = Prefs(context)
            apply(root, context, prefs.fontFamily, prefs.fontScale)
            observe(root, context)
        }
    }

    private fun observe(root: View, context: Context) {
        if (observedRoots.containsKey(root)) return
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (root.isAttachedToWindow) {
                val prefs = Prefs(context)
                apply(root, context, prefs.fontFamily, prefs.fontScale)
            }
        }
        observedRoots[root] = listener
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun walk(view: View, action: (TextView) -> Unit) {
        if (view is TextView) action(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) walk(view.getChildAt(index), action)
        }
    }
}

package org.qbook.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import org.qbook.R

/**
 * Selectable section header used by the single-screen Control Center.
 * Child visibility is managed by SettingsActivity so preference values remain
 * owned by AndroidX Preference and the existing Prefs binding layer.
 */
class ExpandablePreferenceCategory @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : PreferenceCategory(context, attrs) {

    init {
        isEnabled = true
        isSelectable = true
    }

    /**
     * AndroidX PreferenceCategory overrides isEnabled() to always return false.
     * The Control Center uses this category as a selectable section header, so
     * it must report enabled for Preference.performClick() to dispatch clicks.
     */
    override fun isEnabled(): Boolean = true

    var expanded: Boolean = false
        set(value) {
            field = value
            notifyChanged()
        }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val indicator = holder.itemView.findViewById<View>(R.id.expand_indicator)
        indicator?.let {
            val target = if (expanded) 180f else 0f
            val previous = it.getTag(R.id.expand_indicator) as? Float
            it.setTag(R.id.expand_indicator, target)
            if (previous == null || previous == target || !it.isLaidOut) {
                it.rotation = target
            } else {
                it.animate()
                    .rotation(target)
                    .setDuration(220L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
        indicator?.contentDescription = context.getString(R.string.settings_expand_section)
    }
}

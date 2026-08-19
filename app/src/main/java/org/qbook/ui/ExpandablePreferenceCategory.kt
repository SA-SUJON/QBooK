package org.qbook.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
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
        indicator?.rotation = if (expanded) 180f else 0f
        indicator?.contentDescription = context.getString(R.string.settings_expand_section)
    }
}

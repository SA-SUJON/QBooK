package org.qbook.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import org.qbook.R
import org.qbook.utils.Prefs

/** Navigation card whose icon can participate in the Animated Theme Engine. */
class AnimatedNavigationPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val preferenceKey = key ?: return
        holder.itemView.findViewById<View>(android.R.id.icon)?.let { icon ->
            AnimatedThemeIconAnimator.bind(icon, preferenceKey, Prefs(context).labsAnimatedTheme)
        }
    }
}

package org.qbook.ui

import android.app.Activity
import androidx.fragment.app.Fragment
import com.google.android.material.transition.MaterialSharedAxis
import org.qbook.R

/** Shared-axis motion used by QBooK screen and section navigation. */
object ScreenMotion {
    fun enter(activity: Activity) {
        activity.overridePendingTransition(R.anim.shared_axis_enter, R.anim.shared_axis_exit)
    }

    fun exit(activity: Activity) {
        activity.overridePendingTransition(R.anim.shared_axis_pop_enter, R.anim.shared_axis_pop_exit)
    }

    fun configureSharedAxis(fragment: Fragment) {
        fragment.enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true).apply {
            duration = 280L
        }
        fragment.returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false).apply {
            duration = 240L
        }
    }
}

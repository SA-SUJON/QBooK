package org.qbook.ui

import android.content.Context
import android.util.AttributeSet
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.materialswitch.MaterialSwitch

/** Material switch with a small, physics-based response to state changes. */
class SpringMaterialSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : MaterialSwitch(context, attrs) {

    override fun setChecked(checked: Boolean) {
        val changed = isChecked != checked
        super.setChecked(checked)
        if (changed && isLaidOut) playToggleSpring()
    }

    private fun playToggleSpring() {
        val spring = SpringForce(1f)
            .setStiffness(SpringForce.STIFFNESS_HIGH)
            .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)

        scaleX = 0.94f
        scaleY = 0.94f
        SpringAnimation(this, DynamicAnimation.SCALE_X)
            .setSpring(spring)
            .start()
        SpringAnimation(this, DynamicAnimation.SCALE_Y)
            .setSpring(SpringForce(1f)
                .setStiffness(SpringForce.STIFFNESS_HIGH)
                .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY))
            .start()
    }
}

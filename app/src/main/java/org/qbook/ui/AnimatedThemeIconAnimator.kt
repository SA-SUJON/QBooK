package org.qbook.ui

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin
import org.qbook.R
import org.qbook.utils.Prefs

/**
 * Applies a distinct, low-amplitude motion profile to each Control Center icon.
 * The animation is deliberately view-local so recycled Preference rows can start
 * and stop cleanly as the setting changes or rows leave the viewport.
 */
object AnimatedThemeIconAnimator {
    private enum class Motion {
        ROTATE,
        PULSE,
        LIFT,
        SWAY,
        FLOAT,
        LOCK,
        GLOW,
        ORBIT
    }

    fun bind(view: View, key: String, enabled: Boolean) {
        ensureAttachListener(view)
        view.setTag(R.id.animated_theme_key_tag, key)

        val motion = motionFor(key)
        val running = view.getTag(R.id.animated_theme_animator_tag) as? ValueAnimator
        val boundMotion = view.getTag(R.id.animated_theme_motion_tag) as? Motion

        if (enabled) {
            if (running?.isStarted == true && boundMotion == motion) return
            stop(view)

            val animator = ValueAnimator.ofFloat(0f, (Math.PI * 2.0).toFloat()).apply {
                duration = durationFor(motion)
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { frame ->
                    apply(view, motion, frame.animatedValue as Float)
                }
            }
            view.setTag(R.id.animated_theme_animator_tag, animator)
            view.setTag(R.id.animated_theme_motion_tag, motion)
            animator.start()
        } else {
            stop(view)
        }
    }

    private fun ensureAttachListener(view: View) {
        if (view.getTag(R.id.animated_theme_attach_listener_tag) != null) return
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(attached: View) {
                val key = attached.getTag(R.id.animated_theme_key_tag) as? String ?: return
                bind(attached, key, Prefs(attached.context).labsAnimatedTheme)
            }

            override fun onViewDetachedFromWindow(detached: View) {
                stop(detached)
            }
        }
        view.addOnAttachStateChangeListener(listener)
        view.setTag(R.id.animated_theme_attach_listener_tag, listener)
    }

    private fun stop(view: View) {
        (view.getTag(R.id.animated_theme_animator_tag) as? ValueAnimator)?.cancel()
        view.setTag(R.id.animated_theme_animator_tag, null)
        view.setTag(R.id.animated_theme_motion_tag, null)
        reset(view)
    }

    private fun motionFor(key: String): Motion = when (key) {
        "section_appearance" -> Motion.ROTATE
        "section_browsing" -> Motion.PULSE
        "section_blocking" -> Motion.LIFT
        "section_home" -> Motion.SWAY
        "section_offline" -> Motion.FLOAT
        "section_privacy" -> Motion.LOCK
        "section_about" -> Motion.GLOW
        "labs_navigation" -> Motion.ORBIT
        else -> Motion.ROTATE
    }

    private fun durationFor(motion: Motion): Long = when (motion) {
        Motion.ROTATE -> 3_600L
        Motion.PULSE -> 1_900L
        Motion.LIFT -> 2_400L
        Motion.SWAY -> 2_700L
        Motion.FLOAT -> 3_100L
        Motion.LOCK -> 2_200L
        Motion.GLOW -> 2_900L
        Motion.ORBIT -> 5_400L
    }

    private fun apply(view: View, motion: Motion, phase: Float) {
        val wave = sin(phase.toDouble()).toFloat()
        val cosine = cos(phase.toDouble()).toFloat()
        when (motion) {
            Motion.ROTATE -> {
                view.rotation = wave * 7f
            }
            Motion.PULSE -> {
                val scale = 1f + wave * 0.06f
                view.scaleX = scale
                view.scaleY = scale
            }
            Motion.LIFT -> {
                view.translationY = -((wave + 1f) * 0.5f) * 5f
            }
            Motion.SWAY -> {
                view.translationX = wave * 4f
                view.rotation = cosine * 2f
            }
            Motion.FLOAT -> {
                view.translationY = wave * 5f
                view.scaleX = 1f + cosine * 0.025f
                view.scaleY = 1f - cosine * 0.025f
            }
            Motion.LOCK -> {
                view.rotation = wave * 4f
                view.scaleX = 1f + cosine * 0.05f
                view.scaleY = 1f - cosine * 0.05f
            }
            Motion.GLOW -> {
                view.alpha = 0.72f + ((wave + 1f) * 0.14f)
            }
            Motion.ORBIT -> {
                view.rotation = (phase * 180f / Math.PI.toFloat()) % 360f
            }
        }
    }

    private fun reset(view: View) {
        view.rotation = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.alpha = 1f
    }
}

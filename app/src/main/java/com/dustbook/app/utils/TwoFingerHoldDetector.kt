package com.dustbook.app.utils

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Two fingers held still for a moment.
 *
 * Used only by the layout diagnostic. A one-finger long press is a real
 * gesture on a Facebook page - it opens previews and context menus - so it
 * cannot be borrowed. Two fingers resting is not, which makes it safe to
 * attach without changing how the app behaves.
 */
class TwoFingerHoldDetector(
    private val holdMs: Long = 700L,
    private val onHold: () -> Unit
) {

    constructor(onHold: () -> Unit) : this(700L, onHold)

    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var downX = 0f
    private var downY = 0f

    /** Movement beyond this cancels: the user is pinching or scrolling. */
    private val slop = 40f

    fun onTouchEvent(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2) {
                    downX = ev.getX(0)
                    downY = ev.getY(0)
                    cancel()
                    val r = Runnable { pending = null; onHold() }
                    pending = r
                    main.postDelayed(r, holdMs)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (pending != null && ev.pointerCount >= 1) {
                    if (abs(ev.getX(0) - downX) > slop ||
                        abs(ev.getY(0) - downY) > slop
                    ) cancel()
                }
            }

            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> cancel()
        }
    }

    private fun cancel() {
        pending?.let { main.removeCallbacks(it) }
        pending = null
    }
}

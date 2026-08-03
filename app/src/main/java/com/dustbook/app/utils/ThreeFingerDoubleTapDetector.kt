package com.dustbook.app.utils

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration

/**
 * Detects a THREE FINGER DOUBLE TAP.
 *
 * A "three finger tap" = a gesture where at some point 3 pointers were down,
 * the whole gesture lasted less than [TAP_TIMEOUT] ms and no pointer moved
 * further than the touch slop. Two of those in a row within [DOUBLE_TAP_WINDOW]
 * ms fire [onDetected].
 *
 * Fed from Activity.dispatchTouchEvent so it works over the WebView without
 * ever consuming or delaying normal touches (it is purely observational).
 */
class ThreeFingerDoubleTapDetector(
    slopPx: Int = ViewConfiguration.getTouchSlop() * 3,
    private val onDetected: () -> Unit
) {

    private companion object {
        const val TAP_TIMEOUT = 500L
        const val DOUBLE_TAP_WINDOW = 900L
        const val REQUIRED_FINGERS = 3
    }

    private val slop = slopPx
    private var downTime = 0L
    private var maxPointers = 0
    private var moved = false
    private var startX = FloatArray(10)
    private var startY = FloatArray(10)
    private var lastTapTime = 0L

    /** Call from dispatchTouchEvent. Never consumes the event. */
    fun onTouchEvent(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = SystemClock.uptimeMillis()
                maxPointers = 1
                moved = false
                record(ev)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, ev.pointerCount)
                record(ev)
            }

            MotionEvent.ACTION_MOVE -> {
                if (moved) return
                for (i in 0 until minOf(ev.pointerCount, startX.size)) {
                    val id = ev.getPointerId(i)
                    if (id >= startX.size) continue
                    if (kotlin.math.abs(ev.getX(i) - startX[id]) > slop ||
                        kotlin.math.abs(ev.getY(i) - startY[id]) > slop
                    ) {
                        moved = true
                        return
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                val duration = SystemClock.uptimeMillis() - downTime
                val validTap = !moved &&
                    maxPointers >= REQUIRED_FINGERS &&
                    duration <= TAP_TIMEOUT

                if (validTap) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTapTime in 1..DOUBLE_TAP_WINDOW) {
                        lastTapTime = 0L
                        onDetected()
                    } else {
                        lastTapTime = now
                    }
                }
                reset()
            }

            MotionEvent.ACTION_CANCEL -> reset()
        }
    }

    private fun record(ev: MotionEvent) {
        for (i in 0 until ev.pointerCount) {
            val id = ev.getPointerId(i)
            if (id < startX.size) {
                startX[id] = ev.getX(i)
                startY[id] = ev.getY(i)
            }
        }
    }

    private fun reset() {
        maxPointers = 0
        moved = false
    }
}

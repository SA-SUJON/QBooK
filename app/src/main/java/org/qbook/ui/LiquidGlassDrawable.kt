package org.qbook.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import org.qbook.R
import kotlin.math.max
import kotlin.math.min

/**
 * A small, self-contained glass surface for the floating settings controls.
 * It samples only the scrolling settings host, so no extra header panel is
 * introduced behind the back button or title pill.
 */
class LiquidGlassDrawable(
    private val context: Context,
    private val sourceView: View,
    private val shape: Shape
) : Drawable() {
    enum class Shape { CIRCLE, PILL }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val refractionPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val destination = RectF()
    private val source = Rect()
    private var snapshot: Bitmap? = null
    private var snapshotWidth = 0
    private var snapshotHeight = 0
    private var blurredRegion: Bitmap? = null
    private var blurredRegionWidth = 0
    private var blurredRegionHeight = 0
    private val density = context.resources.displayMetrics.density
    private val isLightMode = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_NO
    private val lightGlassBaseColor = Color.rgb(198, 202, 211)
    private val lightGlassPressedColor = Color.rgb(183, 188, 199)
    private val lightGlassEdgeColor = Color.rgb(132, 138, 150)

    private val surfaceColor = resolveColor(R.attr.qbookSettingsGlassSurface)
    private val pressedSurfaceColor = resolveColor(R.attr.qbookSettingsGlassSurfacePressed)
    private val strokeColor = resolveColor(R.attr.qbookSettingsGlassStroke)
    private val highlightColor = resolveColor(R.attr.qbookSettingsGlassHighlight)
    private val shadowColor = resolveColor(R.attr.qbookSettingsGlassShadow)

    init {
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = density
    }

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= 0f) return

        destination.set(0f, 0f, width, height)
        buildClipPath(width, height)
        val saveCount = canvas.save()
        canvas.clipPath(clipPath)

        val host = callback as? View
        if (host != null && captureSnapshot()) {
            val sourceLocation = IntArray(2)
            val hostLocation = IntArray(2)
            sourceView.getLocationOnScreen(sourceLocation)
            host.getLocationOnScreen(hostLocation)
            val left = hostLocation[0] - sourceLocation[0]
            val top = hostLocation[1] - sourceLocation[1]
            val right = left + width.toInt()
            val bottom = top + height.toInt()
            source.set(
                left.coerceIn(0, snapshotWidth),
                top.coerceIn(0, snapshotHeight),
                right.coerceIn(0, snapshotWidth),
                bottom.coerceIn(0, snapshotHeight)
            )
            if (source.width() > 0 && source.height() > 0) {
                drawBlurredSnapshot(canvas, source, destination)
                drawRefraction(canvas, source, destination)
            } else {
                drawSurface(canvas, width, height)
            }
        } else {
            drawSurface(canvas, width, height)
        }

        val pressed = state.any { it == android.R.attr.state_pressed }
        val baseGlassColor = if (isLightMode) {
            if (pressed) lightGlassPressedColor else lightGlassBaseColor
        } else {
            if (pressed) pressedSurfaceColor else surfaceColor
        }
        tintPaint.shader = LinearGradient(
            0f,
            0f,
            width,
            height,
            withAlpha(baseGlassColor, if (isLightMode) 150 else 190),
            withAlpha(baseGlassColor, if (isLightMode) 82 else 45),
            Shader.TileMode.CLAMP
        )
        tintPaint.alpha = 110
        canvas.drawRect(destination, tintPaint)
        tintPaint.shader = null

        highlightPaint.shader = RadialGradient(
            width * 0.18f,
            height * 0.08f,
            max(width, height) * 1.15f,
            intArrayOf(withAlpha(highlightColor, if (isLightMode) 220 else 185), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(destination, highlightPaint)
        highlightPaint.shader = null
        canvas.restoreToCount(saveCount)

        strokePaint.color = if (isLightMode) lightGlassEdgeColor else strokeColor
        strokePaint.alpha = if (isLightMode) 220 else 235
        strokePaint.strokeWidth = if (isLightMode) density * 1.25f else density
        canvas.drawPath(clipPath, strokePaint)

        if (isLightMode) {
            strokePaint.color = Color.WHITE
            strokePaint.alpha = 155
            strokePaint.strokeWidth = density * 0.7f
            canvas.drawPath(clipPath, strokePaint)
        }
    }

    private fun drawSurface(canvas: Canvas, width: Float, height: Float) {
        tintPaint.color = if (isLightMode) lightGlassBaseColor else surfaceColor
        tintPaint.alpha = if (isLightMode) 178 else 210
        canvas.drawRect(0f, 0f, width, height, tintPaint)
    }

    private fun drawRefraction(canvas: Canvas, sourceRect: Rect, destinationRect: RectF) {
        val shift = max(2f, density * 4.2f)
        refractionPaint.alpha = if (isLightMode) 92 else 82
        canvas.drawBitmap(snapshot!!, sourceRect, offset(destinationRect, shift, -shift * 0.45f), refractionPaint)
        refractionPaint.alpha = if (isLightMode) 78 else 70
        canvas.drawBitmap(snapshot!!, sourceRect, offset(destinationRect, -shift * 0.8f, shift * 0.65f), refractionPaint)
        refractionPaint.alpha = if (isLightMode) 58 else 54
        canvas.drawBitmap(snapshot!!, sourceRect, offset(destinationRect, shift * 0.35f, shift), refractionPaint)
    }

    private fun drawBlurredSnapshot(canvas: Canvas, sourceRect: Rect, destinationRect: RectF) {
        val width = sourceRect.width()
        val height = sourceRect.height()
        if (width <= 0 || height <= 0) return
        if (blurredRegion == null || blurredRegionWidth != width || blurredRegionHeight != height) {
            blurredRegion?.recycle()
            blurredRegion = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            blurredRegionWidth = width
            blurredRegionHeight = height
        }
        val region = blurredRegion ?: return
        region.eraseColor(Color.TRANSPARENT)
        val regionCanvas = Canvas(region)
        regionCanvas.drawBitmap(
            snapshot!!,
            sourceRect,
            Rect(0, 0, width, height),
            bitmapPaint
        )
        boxBlur(region, 7)
        bitmapPaint.alpha = if (isLightMode) 180 else 175
        canvas.drawBitmap(region, null, destinationRect, bitmapPaint)
    }

    private fun boxBlur(bitmap: Bitmap, radius: Int) {
        val width = bitmap.width
        val height = bitmap.height
        val sourcePixels = IntArray(width * height)
        val horizontalPixels = IntArray(width * height)
        val outputPixels = IntArray(width * height)
        bitmap.getPixels(sourcePixels, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                for (sampleX in max(0, x - radius)..min(width - 1, x + radius)) {
                    val color = sourcePixels[y * width + sampleX]
                    a += Color.alpha(color)
                    r += Color.red(color)
                    g += Color.green(color)
                    b += Color.blue(color)
                    count++
                }
                horizontalPixels[y * width + x] = Color.argb(
                    a / count,
                    r / count,
                    g / count,
                    b / count
                )
            }
        }

        for (x in 0 until width) {
            for (y in 0 until height) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                for (sampleY in max(0, y - radius)..min(height - 1, y + radius)) {
                    val color = horizontalPixels[sampleY * width + x]
                    a += Color.alpha(color)
                    r += Color.red(color)
                    g += Color.green(color)
                    b += Color.blue(color)
                    count++
                }
                outputPixels[y * width + x] = Color.argb(
                    a / count,
                    r / count,
                    g / count,
                    b / count
                )
            }
        }
        bitmap.setPixels(outputPixels, 0, width, 0, 0, width, height)
    }

    private fun offset(rect: RectF, dx: Float, dy: Float): RectF =
        RectF(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy)

    private fun buildClipPath(width: Float, height: Float) {
        clipPath.reset()
        if (shape == Shape.CIRCLE) {
            val diameter = min(width, height)
            clipPath.addOval(
                RectF(
                    (width - diameter) / 2f,
                    (height - diameter) / 2f,
                    (width + diameter) / 2f,
                    (height + diameter) / 2f
                ),
                Path.Direction.CW
            )
        } else {
            clipPath.addRoundRect(
                RectF(0f, 0f, width, height),
                height / 2f,
                height / 2f,
                Path.Direction.CW
            )
        }
    }

    private fun captureSnapshot(): Boolean {
        val width = sourceView.width
        val height = sourceView.height
        if (width <= 0 || height <= 0) return false
        if (snapshot == null || snapshotWidth != width || snapshotHeight != height) {
            snapshot?.recycle()
            snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            snapshotWidth = width
            snapshotHeight = height
        }
        val target = snapshot ?: return false
        target.eraseColor(Color.TRANSPARENT)
        sourceView.draw(Canvas(target))
        return true
    }

    private fun resolveColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    override fun isStateful(): Boolean = true

    override fun onStateChange(stateSet: IntArray): Boolean {
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {
        bitmapPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        bitmapPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    fun release() {
        snapshot?.recycle()
        blurredRegion?.recycle()
        snapshot = null
        blurredRegion = null
        snapshotWidth = 0
        snapshotHeight = 0
        blurredRegionWidth = 0
        blurredRegionHeight = 0
    }

    @Deprecated("Deprecated in Android graphics APIs")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

private fun RectF.toRect(): Rect = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

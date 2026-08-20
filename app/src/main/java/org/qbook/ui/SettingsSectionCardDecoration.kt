package org.qbook.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import org.qbook.R

/**
 * Draws one continuous inner surface behind the visible children of each
 * expanded top-level settings section. The decoration is derived from stable
 * adapter positions, so scrolling never changes section expansion state.
 */
class SettingsSectionCardDecoration(
    context: Context,
    private val sectionKeys: Set<String>
) : RecyclerView.ItemDecoration() {

    private val density = context.resources.displayMetrics.density
    private val inset = 8f * density
    private val verticalBleed = 5f * density
    private val cornerRadius = 26f * density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = resolveThemeColor(context, R.attr.qbookSettingsCardSurface)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = resolveThemeColor(context, R.attr.qbookSettingsGlassStroke)
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
        val layoutManager = parent.layoutManager ?: return
        val itemCount = adapter.itemCount
        if (itemCount == 0) return

        for (position in 0 until itemCount) {
            val preference = adapter.getItem(position)
            if (preference !is ExpandablePreferenceCategory || preference.key !in sectionKeys) continue

            val firstChildPosition = position + 1
            var lastChildPosition = firstChildPosition
            while (lastChildPosition < itemCount &&
                adapter.getItem(lastChildPosition) !is ExpandablePreferenceCategory
            ) {
                lastChildPosition++
            }
            if (lastChildPosition <= firstChildPosition) continue

            val firstVisible = findFirstVisiblePosition(parent, layoutManager, firstChildPosition, lastChildPosition)
            val lastVisible = findLastVisiblePosition(parent, layoutManager, firstChildPosition, lastChildPosition)
            if (firstVisible < 0 || lastVisible < 0 || lastVisible < firstVisible) continue

            val firstView = layoutManager.findViewByPosition(firstVisible) ?: continue
            val lastView = layoutManager.findViewByPosition(lastVisible) ?: continue
            val left = maxOf(parent.paddingLeft.toFloat(), firstView.left.toFloat())
            val right = minOf(
                (parent.width - parent.paddingRight).toFloat(),
                lastView.right.toFloat()
            )
            val top = if (firstVisible == firstChildPosition) {
                firstView.top - verticalBleed
            } else {
                parent.paddingTop.toFloat()
            }
            val bottom = if (lastVisible == lastChildPosition - 1) {
                lastView.bottom + verticalBleed
            } else {
                (parent.height - parent.paddingBottom).toFloat()
            }
            if (bottom <= top || right <= left) continue

            val rect = RectF(left, top, right, bottom)
            val topRounded = firstVisible == firstChildPosition
            val bottomRounded = lastVisible == lastChildPosition - 1
            val radii = floatArrayOf(
                if (topRounded) cornerRadius else 0f,
                if (topRounded) cornerRadius else 0f,
                if (topRounded) cornerRadius else 0f,
                if (topRounded) cornerRadius else 0f,
                if (bottomRounded) cornerRadius else 0f,
                if (bottomRounded) cornerRadius else 0f,
                if (bottomRounded) cornerRadius else 0f,
                if (bottomRounded) cornerRadius else 0f
            )
            val path = Path().apply {
                addRoundRect(rect, radii, Path.Direction.CW)
            }
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, strokePaint)
        }
    }

    private fun findFirstVisiblePosition(
        parent: RecyclerView,
        layoutManager: RecyclerView.LayoutManager,
        start: Int,
        endExclusive: Int
    ): Int {
        for (position in start until endExclusive) {
            if (layoutManager.findViewByPosition(position) != null) return position
        }
        return -1
    }

    private fun findLastVisiblePosition(
        parent: RecyclerView,
        layoutManager: RecyclerView.LayoutManager,
        start: Int,
        endExclusive: Int
    ): Int {
        for (position in (endExclusive - 1) downTo start) {
            if (layoutManager.findViewByPosition(position) != null) return position
        }
        return -1
    }

    private fun resolveThemeColor(context: Context, attribute: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) {
            context.getColor(value.resourceId)
        } else {
            value.data
        }
    }
}

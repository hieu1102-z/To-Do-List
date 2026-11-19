package com.finalexam.to_do_list.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/** Wraps choice chips on narrow screens and when the system font is enlarged. */
class ChipFlowLayout(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private val gap get() = (6 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            if (x > 0 && x + child.measuredWidth > available) {
                y += rowHeight + gap; x = 0; rowHeight = 0
            }
            x += child.measuredWidth + gap
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec),
            resolveSize(y + rowHeight + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = width - paddingLeft - paddingRight
        var x = 0
        var y = paddingTop
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (x > 0 && x + child.measuredWidth > available) {
                y += rowHeight + gap; x = 0; rowHeight = 0
            }
            val left = if (layoutDirection == View.LAYOUT_DIRECTION_RTL)
                width - paddingRight - x - child.measuredWidth else paddingLeft + x
            child.layout(left, y, left + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + gap
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}

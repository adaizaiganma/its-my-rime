package com.kingzcheung.xime

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/** Wraps candidate views at their measured text width inside the keyboard. */
internal class CandidateFlowLayout(
    context: Context,
    private val horizontalGap: Int,
    private val verticalGap: Int
) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = if (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            (View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        }
        var lineWidth = 0
        var lineHeight = 0
        var contentWidth = 0
        var contentHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            measureChild(child, widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val childWidth = child.measuredWidth
            if (lineWidth > 0 && lineWidth + horizontalGap + childWidth > availableWidth) {
                contentWidth = max(contentWidth, lineWidth)
                contentHeight += lineHeight + verticalGap
                lineWidth = 0
                lineHeight = 0
            }
            if (lineWidth > 0) lineWidth += horizontalGap
            lineWidth += childWidth
            lineHeight = max(lineHeight, child.measuredHeight)
        }
        contentWidth = max(contentWidth, lineWidth)
        contentHeight += lineHeight
        setMeasuredDimension(
            resolveSize(contentWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rightEdge = right - left - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            if (x > paddingLeft && x + child.measuredWidth > rightEdge) {
                x = paddingLeft
                y += lineHeight + verticalGap
                lineHeight = 0
            }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + horizontalGap
            lineHeight = max(lineHeight, child.measuredHeight)
        }
    }
}

package com.family.dakacamera

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 按固定宽高比约束取景区域的容器(默认 3:4,与竖屏成片一致)。
 * 在可用空间内等比缩放并居中,剩余区域由父布局显示为黑边(操作区)。
 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 宽 / 高 */
    var aspectRatio: Float = 3f / 4f
        set(value) {
            if (value > 0f && field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec)

        var width = maxWidth
        var height = (width / aspectRatio).toInt()
        if (height > maxHeight) {
            height = maxHeight
            width = (height * aspectRatio).toInt()
        }

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}

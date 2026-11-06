package com.family.dakacamera

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/**
 * 相机界面上的实时水印层:与成片使用同一套绘制逻辑([WatermarkPainter])。
 * 不可点击、不拦截触摸事件。
 */
class WatermarkOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var data: WatermarkData? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = data ?: return
        WatermarkPainter.paint(canvas, width.toFloat(), height.toFloat(), d)
    }
}

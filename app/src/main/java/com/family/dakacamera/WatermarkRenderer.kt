package com.family.dakacamera

import android.graphics.Bitmap
import android.graphics.Canvas

/** 把水印合成到照片位图上(实际绘制逻辑见 [WatermarkPainter],与实时预览共用)。 */
object WatermarkRenderer {

    fun render(src: Bitmap, data: WatermarkData): Bitmap {
        // CameraX 产出的位图不可变,必须先复制;否则 Canvas 构造直接抛 IllegalStateException
        val bmp = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        WatermarkPainter.paint(Canvas(bmp), bmp.width.toFloat(), bmp.height.toFloat(), data)
        return bmp
    }
}

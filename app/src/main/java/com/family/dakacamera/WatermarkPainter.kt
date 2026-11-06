package com.family.dakacamera

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/** 水印内容(时间/日期/地址/防伪码,均已格式化好) */
data class WatermarkData(
    val timeText: String,   // "14:32"
    val dateText: String,   // "2026.10.11 星期六"
    val address: String,    // 详细地址,或降级的经纬度串,或"无定位信息"
    val code: String,       // 已分组防伪码,如 "AB12-CD34-EF56-GH78-JK90"
)

/**
 * 水印绘制核心。同一个绘制流程同时用于:
 *  - 照片合成(WatermarkRenderer)
 *  - 相机界面实时预览(WatermarkOverlayView)
 * 从而保证「预览所见 = 成片所得」。
 *
 * 所有尺寸按「宽度 / 1080」比例缩放,适配任意分辨率;
 * 文字带黑色阴影保证在深浅背景上均可读。
 */
object WatermarkPainter {

    private const val BADGE_TEXT = "打卡"
    private const val FOOTER_1 = "打卡相机已验证"
    private const val FOOTER_2 = " | 时间地点真实"
    private const val BRAND = "打卡相机 / 真实可验"
    private const val CODE_PREFIX = "防伪 "
    private const val BADGE_COLOR = 0xFFFFD400.toInt()

    fun paint(canvas: Canvas, width: Float, height: Float, data: WatermarkData) {
        if (width <= 0f || height <= 0f) return

        val w = width
        val h = height
        val s = w / 1080f

        val margin = 36f * s
        val left = margin
        val gap = 18f * s
        val sizeSmall = 26f * s
        val sizeNormal = 36f * s
        val sizeTime = 96f * s

        // 地址最多两行,行宽不超过宽度的 62%
        val addrPaint = textPaint(Color.WHITE, sizeNormal, bold = false)
        val addrLayout = StaticLayout.Builder
            .obtain(data.address, 0, data.address.length, addrPaint, (w * 0.62f).toInt())
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(6f * s, 1f)
            .build()

        // 自下而上推算各行位置(注意: drawText 的 y 是基线,StaticLayout 的 y 是顶部)
        val footerBaseline = h - margin
        val dateBaseline = footerBaseline - sizeSmall - gap
        val addrBottom = dateBaseline - sizeNormal - gap
        val addrTop = addrBottom - addrLayout.height
        val timeBaseline = addrTop - gap - sizeTime * 0.25f

        // ---- 底部小字(两段,前段加粗)----
        val footerPaint1 = textPaint(Color.WHITE, sizeSmall, bold = true)
        val footerPaint2 = textPaint(Color.WHITE, sizeSmall, bold = false).apply { alpha = 200 }
        canvas.drawText(FOOTER_1, left, footerBaseline, footerPaint1)
        canvas.drawText(
            FOOTER_2, left + footerPaint1.measureText(FOOTER_1), footerBaseline, footerPaint2
        )

        // ---- 日期 ----
        canvas.drawText(
            data.dateText, left, dateBaseline, textPaint(Color.WHITE, sizeNormal, bold = false)
        )

        // ---- 地址(StaticLayout 用顶部坐标)----
        canvas.save()
        canvas.translate(left, addrTop)
        addrLayout.draw(canvas)
        canvas.restore()

        // ---- 「打卡」徽标 + 大号时间(同一行)----
        val badgePaint = textPaint(Color.BLACK, sizeNormal, bold = true).apply { clearShadowLayer() }
        val padH = 20f * s
        val padV = 10f * s
        val badgeW = badgePaint.measureText(BADGE_TEXT) + padH * 2
        val badgeH = sizeNormal + padV * 2
        val badgeCenterY = timeBaseline - sizeTime * 0.36f
        val badgeTop = badgeCenterY - badgeH / 2f
        val radius = 10f * s
        canvas.drawRoundRect(
            left, badgeTop, left + badgeW, badgeTop + badgeH, radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BADGE_COLOR }
        )
        canvas.drawText(BADGE_TEXT, left + padH, badgeTop + padV + sizeNormal * 0.82f, badgePaint)
        canvas.drawText(
            data.timeText, left + badgeW + 20f * s, timeBaseline,
            textPaint(Color.WHITE, sizeTime, bold = true)
        )

        // ---- 右下角:品牌 + 防伪码 ----
        drawRightBlock(canvas, w, h, s, data.code)
    }

    private fun drawRightBlock(canvas: Canvas, w: Float, h: Float, s: Float, code: String) {
        val right = w - 36f * s
        var y = h - 36f * s
        fun row(text: String, paint: TextPaint) {
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(text, right, y, paint)
            y -= paint.textSize * 1.5f
        }
        row(CODE_PREFIX + code, textPaint(Color.WHITE, 24f * s, bold = false).apply { alpha = 235 })
        row(BRAND, textPaint(Color.WHITE, 32f * s, bold = true))
    }

    private fun textPaint(color: Int, size: Float, bold: Boolean): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setShadowLayer(size * 0.16f, 0f, size * 0.05f, Color.BLACK)
            isSubpixelText = true
        }
}

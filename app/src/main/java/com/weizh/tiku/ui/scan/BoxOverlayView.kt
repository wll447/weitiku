package com.weizh.tiku.ui.scan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 把 OCR 检测到的文字框标在预览上（绿色），对齐同类产品的"识别区域高亮"。
 * 坐标是 PreviewView 的 View 坐标系。
 */
class BoxOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val stroke = Paint().apply {
        color = Color.parseColor("#FF34C759")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val fill = Paint().apply {
        color = Color.parseColor("#3334C759")
        style = Paint.Style.FILL
    }

    private val boxes = ArrayList<RectF>()

    fun setBoxes(list: List<RectF>) {
        boxes.clear()
        boxes.addAll(list)
        invalidate()
    }

    fun clear() {
        if (boxes.isEmpty()) return
        boxes.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (b in boxes) {
            canvas.drawRect(b, fill)
            canvas.drawRect(b, stroke)
        }
    }
}

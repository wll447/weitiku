package com.weizh.tiku.ui.widget.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 题目框浮层 —— 照同类产品 QuestionBoxOverlayView 移植。
 *
 * 对齐的关键设计：
 *  - **多个框**，每个框对应一道题（`setRects`）
 *  - **归一化坐标**（0..1，随图片缩放自动适配）
 *  - `hitTest(x,y)` 点选、`setSelectedIndex(i)`
 *  - 拖拽/缩放五种模式：MOVE / RESIZE_LT / RT / LB / RB，最小尺寸 `MIN_SIZE_RATIO = 0.08f`
 *  - 编辑时**自己画 取消/确定 两个按钮**（原来是 mBtnCancelRect / mBtnConfirmRect）
 *  - `setAdjustedFlags` 记录用户改过的框（画成不同颜色）、`setReadOnly` 只读
 *  - `OnBoxEditListener`：onBoxTap / onBoxEditConfirmed / onBoxEditCancelled
 */
class QuestionBoxOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        private const val DRAG_NONE = 0
        private const val DRAG_MOVE = 1
        private const val DRAG_RESIZE_LT = 2
        private const val DRAG_RESIZE_RT = 3
        private const val DRAG_RESIZE_LB = 4
        private const val DRAG_RESIZE_RB = 5
        private const val MIN_SIZE_RATIO = 0.08f
        private const val HANDLE = 14f
    }

    interface OnBoxEditListener {
        fun onBoxTap(index: Int)
        fun onBoxEditConfirmed(index: Int, normRect: RectF)
        fun onBoxEditCancelled(index: Int)
    }

    private val mNormRects = ArrayList<RectF>()
    private var mSelectedIndex = 0
    private var mEditingIndex = -1
    private var mReadOnly = false
    private var mAdjustedFlags: BooleanArray? = null

    private var mImgW = 1
    private var mImgH = 1

    private var mDragMode = DRAG_NONE
    private var mLastX = 0f
    private var mLastY = 0f
    private var mEditOriginal = RectF()

    private var mListener: OnBoxEditListener? = null

    private val mNormalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xCCFFFFFF.toInt()
    }
    private val mSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.parseColor("#FF6B35")
    }
    private val mAdjustedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.parseColor("#FF34C759")
    }
    private val mEditPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.parseColor("#FF3D7BFF")
    }
    private val mLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC000000") }
    private val mLabelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 26f
    }
    private val mBtnBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E6FFFFFF") }
    private val mBtnText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF222222"); textSize = 28f; textAlign = Paint.Align.CENTER
    }
    private val mBtnCancelRect = RectF()
    private val mBtnConfirmRect = RectF()

    /** 绑定图片：用来算 fitCenter 下图片的实际显示区域 */
    fun bind(imageView: ImageView, imgW: Int, imgH: Int) {
        mImgW = max(1, imgW)
        mImgH = max(1, imgH)
        invalidate()
    }

    fun setOnBoxEditListener(l: OnBoxEditListener) { mListener = l }

    fun setReadOnly(ro: Boolean) { mReadOnly = ro }

    fun setRects(list: List<RectF>) {
        mNormRects.clear()
        mNormRects.addAll(list)
        if (mSelectedIndex >= mNormRects.size) mSelectedIndex = 0
        mEditingIndex = -1
        invalidate()
    }

    fun setSelectedIndex(i: Int) {
        if (i in mNormRects.indices) { mSelectedIndex = i; invalidate() }
    }

    fun selectedIndex(): Int = mSelectedIndex

    fun setAdjustedFlags(flags: BooleanArray?) { mAdjustedFlags = flags; invalidate() }

    fun rectCount(): Int = mNormRects.size

    fun normRect(i: Int): RectF? = mNormRects.getOrNull(i)

    // ---------- 坐标换算 ----------

    /** fitCenter 下图片在本 View 里的显示区域 */
    private fun imageDisplay(): RectF {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val scale = min(vw / mImgW, vh / mImgH)
        val w = mImgW * scale
        val h = mImgH * scale
        val l = (vw - w) / 2f
        val t = (vh - h) / 2f
        return RectF(l, t, l + w, t + h)
    }

    private fun toView(n: RectF): RectF {
        val d = imageDisplay()
        return RectF(
            d.left + n.left * d.width(), d.top + n.top * d.height(),
            d.left + n.right * d.width(), d.top + n.bottom * d.height()
        )
    }

    private fun toNorm(v: RectF): RectF {
        val d = imageDisplay()
        if (d.width() <= 0 || d.height() <= 0) return RectF()
        return RectF(
            ((v.left - d.left) / d.width()).coerceIn(0f, 1f),
            ((v.top - d.top) / d.height()).coerceIn(0f, 1f),
            ((v.right - d.left) / d.width()).coerceIn(0f, 1f),
            ((v.bottom - d.top) / d.height()).coerceIn(0f, 1f)
        )
    }

    /** 照原版 hitTest：返回命中的框序号，-1 表示没命中 */
    fun hitTest(x: Float, y: Float): Int {
        for (i in mNormRects.indices.reversed()) {
            if (toView(mNormRects[i]).contains(x, y)) return i
        }
        return -1
    }

    private fun hitHandle(v: RectF, x: Float, y: Float): Int {
        if (abs(x - v.left) < HANDLE && abs(y - v.top) < HANDLE) return DRAG_RESIZE_LT
        if (abs(x - v.right) < HANDLE && abs(y - v.top) < HANDLE) return DRAG_RESIZE_RT
        if (abs(x - v.left) < HANDLE && abs(y - v.bottom) < HANDLE) return DRAG_RESIZE_LB
        if (abs(x - v.right) < HANDLE && abs(y - v.bottom) < HANDLE) return DRAG_RESIZE_RB
        return DRAG_NONE
    }

    // ---------- 编辑 ----------

    fun startEdit(i: Int) {
        if (i !in mNormRects.indices || mReadOnly) return
        mEditingIndex = i
        mSelectedIndex = i
        mEditOriginal.set(mNormRects[i])
        invalidate()
    }

    fun cancelEdit() {
        val i = mEditingIndex
        if (i < 0) return
        mNormRects[i].set(mEditOriginal)
        mEditingIndex = -1
        mListener?.onBoxEditCancelled(i)
        invalidate()
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val editing = mEditingIndex
        for (i in mNormRects.indices) {
            val v = toView(mNormRects[i])
            val adjusted = mAdjustedFlags?.getOrNull(i) == true
            val paint = when {
                i == editing -> mEditPaint
                i == mSelectedIndex -> mSelectedPaint
                adjusted -> mAdjustedPaint
                else -> mNormalPaint
            }
            canvas.drawRect(v, paint)

            // 框上标签：题号
            val label = (i + 1).toString()
            val tw = mLabelText.measureText(label)
            val pad = 8f
            val lr = RectF(v.left, max(0f, v.top - mLabelText.textSize - pad), v.left + tw + pad * 2, v.top)
            canvas.drawRect(lr, mLabelBg)
            canvas.drawText(label, lr.left + pad, lr.bottom - pad, mLabelText)

            // 编辑中：画四个角把手
            if (i == editing) {
                val s = 8f
                for (p in listOf(
                    floatArrayOf(v.left, v.top), floatArrayOf(v.right, v.top),
                    floatArrayOf(v.left, v.bottom), floatArrayOf(v.right, v.bottom)
                )) {
                    canvas.drawRect(p[0] - s, p[1] - s, p[0] + s, p[1] + s, mEditPaint)
                }
            }
        }

        // 编辑中：取消 / 确定（照原版自绘按钮）
        if (editing >= 0) {
            val v = toView(mNormRects[editing])
            val bw = 150f
            val bh = 62f
            val gap = 26f
            var by = v.bottom + gap
            if (by + bh > height) by = max(0f, v.top - gap - bh)
            var bx = v.centerX() - bw - gap / 2f
            mBtnCancelRect.set(bx, by, bx + bw, by + bh)
            bx = v.centerX() + gap / 2f
            mBtnConfirmRect.set(bx, by, bx + bw, by + bh)

            canvas.drawRoundRect(mBtnCancelRect, 12f, 12f, mBtnBg)
            canvas.drawRoundRect(mBtnConfirmRect, 12f, 12f, mBtnBg)
            canvas.drawText("取消", mBtnCancelRect.centerX(), mBtnCancelRect.centerY() + 10f, mBtnText)
            canvas.drawText("确定", mBtnConfirmRect.centerX(), mBtnConfirmRect.centerY() + 10f, mBtnText)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mReadOnly) return false
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 编辑中优先响应按钮
                if (mEditingIndex >= 0) {
                    if (mBtnCancelRect.contains(x, y)) { cancelEdit(); return true }
                    if (mBtnConfirmRect.contains(x, y)) {
                        val i = mEditingIndex
                        mEditingIndex = -1
                        mListener?.onBoxEditConfirmed(i, RectF(mNormRects[i]))
                        invalidate()
                        return true
                    }
                }
                val hit = hitTest(x, y)
                if (hit >= 0) {
                    mSelectedIndex = hit
                    mDragMode = if (mEditingIndex == hit) hitHandle(toView(mNormRects[hit]), x, y) else DRAG_NONE
                    if (mDragMode == DRAG_NONE) mDragMode = DRAG_MOVE
                    mLastX = x; mLastY = y
                    invalidate()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (mEditingIndex < 0 || mDragMode == DRAG_NONE) return false
                val dx = x - mLastX
                val dy = y - mLastY
                mLastX = x; mLastY = y
                val d = imageDisplay()
                val ndx = dx / d.width()
                val ndy = dy / d.height()
                val r = mNormRects[mEditingIndex]
                when (mDragMode) {
                    DRAG_MOVE -> {
                        r.offset(ndx, ndy)
                        if (r.left < 0) r.offset(-r.left, 0f)
                        if (r.top < 0) r.offset(0f, -r.top)
                        if (r.right > 1) r.offset(1 - r.right, 0f)
                        if (r.bottom > 1) r.offset(0f, 1 - r.bottom)
                    }
                    DRAG_RESIZE_LT -> { r.left += ndx; r.top += ndy }
                    DRAG_RESIZE_RT -> { r.right += ndx; r.top += ndy }
                    DRAG_RESIZE_LB -> { r.left += ndx; r.bottom += ndy }
                    DRAG_RESIZE_RB -> { r.right += ndx; r.bottom += ndy }
                }
                val minW = MIN_SIZE_RATIO
                if (r.width() < minW) {
                    if (mDragMode == DRAG_RESIZE_LT || mDragMode == DRAG_RESIZE_LB) r.left = r.right - minW
                    else r.right = r.left + minW
                }
                if (r.height() < minW) {
                    if (mDragMode == DRAG_RESIZE_LT || mDragMode == DRAG_RESIZE_RT) r.top = r.bottom - minW
                    else r.bottom = r.top + minW
                }
                r.left = r.left.coerceIn(0f, 1f); r.top = r.top.coerceIn(0f, 1f)
                r.right = r.right.coerceIn(0f, 1f); r.bottom = r.bottom.coerceIn(0f, 1f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mDragMode != DRAG_NONE && mEditingIndex >= 0) {
                    mAdjustedFlags?.let { if (mEditingIndex < it.size) it[mEditingIndex] = true }
                    mDragMode = DRAG_NONE
                    invalidate()
                    return true
                }
                // 单击选中框 → 回调（原版 onBoxTap）
                val hit = hitTest(x, y)
                if (hit >= 0) { mSelectedIndex = hit; mListener?.onBoxTap(hit); invalidate(); return true }
                return false
            }
        }
        return super.onTouchEvent(event)
    }
}

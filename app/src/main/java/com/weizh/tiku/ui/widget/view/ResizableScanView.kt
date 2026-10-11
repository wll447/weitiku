package com.weizh.tiku.ui.widget.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 识别区域框 —— 照同类产品 ResizableScanView 一比一移植（包名保持同一结构，便于对照）。
 *
 * 常量与行为全部对齐：
 *   CENTER_TOUCH_SIZE = 180   中心拖动判定区
 *   CORNER_SIZE       = 40    角把手判定
 *   MAX_SIZE_RATIO    = 0.9   最大占 View 比例（默认框就是 0.9 × 0.9 居中）
 *   MIN_SIZE          = 100   最小尺寸
 *   绘制：只有 4 个角的 L 形角线（白色、20dp 长、2dp 粗）+ 一条上下扫的扫描线，无遮罩无整边框
 *   交互：拖中心移动 / 拖角缩放 / 双指捏合缩放
 *
 * 与同类产品的差异（按要求只去掉 VIP）：
 *   - setInteractionEnabled(false) 时同类产品会弹会员框；本项目恒为可交互
 *   - 扫描线用同类产品那张位图；这里自绘一条渐变扫描线（不搬它的美术资源）
 */
class ResizableScanView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        private const val CENTER_TOUCH_SIZE = 180
        private const val CORNER_SIZE = 40
        private const val MAX_SIZE_RATIO = 0.9f
        private const val MIN_SIZE = 100.0f
    }

    private enum class Corner { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private val mRectF = RectF()
    private val mDefaultRect = RectF()
    private val mDisplayRect = RectF()

    /** 恒为 true；保留该字段是为了与"会员才能调框"的原逻辑对照 */
    private var mInteractionEnabled = true

    private var mIsDraggingCenter = false
    private var mIsDraggingCorner = Corner.NONE
    private var mLastTouchX = 0f
    private var mLastTouchY = 0f

    private var mScanImageY = 0f
    private var mScanAnimator: ValueAnimator? = null

    private val mBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.0f)
    }
    private val mCornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.0f)
    }
    private val mScanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 框被用户改动过（用于点亮「重置识别区域」，对齐同类产品 updateResetButtonStyle） */
    var onScanAreaChanged: ((changed: Boolean) -> Unit)? = null
    private var mChanged = false
    var hasUserAdjusted = false
        private set

    private val mScaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val w = mRectF.width() * scaleFactor
            val h = mRectF.height() * scaleFactor
            if (w < MIN_SIZE || h < MIN_SIZE) return true
            if (w > width * MAX_SIZE_RATIO || h > height * MAX_SIZE_RATIO) return true
            val cx = mRectF.centerX()
            val cy = mRectF.centerY()
            mRectF.left = cx - w / 2f
            mRectF.right = cx + w / 2f
            mRectF.top = cy - h / 2f
            mRectF.bottom = cy + h / 2f
            constrainToBounds()
            markChanged()
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            markChanged()
        }
    })

    init {
        setupScanAnimation()
    }

    private fun dpToPx(v: Float): Float = v * resources.displayMetrics.density

    private fun setupScanAnimation() {
        mScanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                mScanImageY = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (mRectF.isEmpty) {
            // 默认框：0.9 × 0.9 居中（与同类产品完全一致）
            val fw = w * MAX_SIZE_RATIO
            val fh = h * MAX_SIZE_RATIO
            val left = (w - fw) / 2f
            val top = (h - fh) / 2f
            mRectF.set(left, top, left + fw, top + fh)
            mDefaultRect.set(mRectF)
            return
        }
        if (oldw <= 0 || oldh <= 0) return
        if (oldw == w && oldh == h) return
        val sx = w.toFloat() / oldw
        val sy = h.toFloat() / oldh
        mRectF.left *= sx; mRectF.right *= sx
        mRectF.top *= sy; mRectF.bottom *= sy
        mDefaultRect.left *= sx; mDefaultRect.right *= sx
        mDefaultRect.top *= sy; mDefaultRect.bottom *= sy
        constrainToBounds()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val len = dpToPx(20.0f)
        // 四个角的 L 形角线（白色 2dp）
        canvas.drawLine(mRectF.left, mRectF.top, mRectF.left + len, mRectF.top, mCornerPaint)
        canvas.drawLine(mRectF.left, mRectF.top, mRectF.left, mRectF.top + len, mCornerPaint)
        canvas.drawLine(mRectF.right - len, mRectF.top, mRectF.right, mRectF.top, mCornerPaint)
        canvas.drawLine(mRectF.right, mRectF.top, mRectF.right, mRectF.top + len, mCornerPaint)
        canvas.drawLine(mRectF.left, mRectF.bottom - len, mRectF.left, mRectF.bottom, mCornerPaint)
        canvas.drawLine(mRectF.left, mRectF.bottom, mRectF.left + len, mRectF.bottom, mCornerPaint)
        canvas.drawLine(mRectF.right - len, mRectF.bottom, mRectF.right, mRectF.bottom, mCornerPaint)
        canvas.drawLine(mRectF.right, mRectF.bottom - len, mRectF.right, mRectF.bottom, mCornerPaint)

        if (mRectF.isEmpty) return
        // 扫描线：自绘渐变横线，位置 = top + height * mScanImageY（与同类产品的位图扫描同一运动规律）
        val y = mRectF.top + mRectF.height() * mScanImageY
        if (y < mRectF.top || y > mRectF.bottom) return
        mScanLinePaint.shader = LinearGradient(
            mRectF.left, y, mRectF.right, y,
            intArrayOf(0x00FFFFFF, 0xCCFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        mScanLinePaint.strokeWidth = dpToPx(2.0f)
        canvas.drawLine(mRectF.left, y, mRectF.right, y, mScanLinePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        mScaleDetector.onTouchEvent(event)
        if (!mInteractionEnabled) {
            if (event.action == MotionEvent.ACTION_DOWN) {
                val corner = getTouchedCorner(event.x, event.y)
                val inCenter = isInCenterArea(event.x, event.y)
                if (corner != Corner.NONE || inCenter) {
                    // 同类产品在这里弹会员框；本项目恒可交互，不会走到这
                }
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mIsDraggingCorner = getTouchedCorner(event.x, event.y)
                mIsDraggingCenter = mIsDraggingCorner == Corner.NONE && isInCenterArea(event.x, event.y)
                mLastTouchX = event.x
                mLastTouchY = event.y
                return mIsDraggingCorner != Corner.NONE || mIsDraggingCenter
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) return true
                if (!mIsDraggingCenter && mIsDraggingCorner == Corner.NONE) return false
                val dx = event.x - mLastTouchX
                val dy = event.y - mLastTouchY
                mLastTouchX = event.x
                mLastTouchY = event.y
                if (mIsDraggingCenter) {
                    mRectF.offset(dx, dy)
                    constrainToBounds()
                } else {
                    resizeByCorner(mIsDraggingCorner, dx, dy)
                }
                markChanged()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mIsDraggingCenter = false
                mIsDraggingCorner = Corner.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun resizeByCorner(corner: Corner, dx: Float, dy: Float) {
        when (corner) {
            Corner.TOP_LEFT -> { mRectF.left += dx; mRectF.top += dy }
            Corner.TOP_RIGHT -> { mRectF.right += dx; mRectF.top += dy }
            Corner.BOTTOM_LEFT -> { mRectF.left += dx; mRectF.bottom += dy }
            Corner.BOTTOM_RIGHT -> { mRectF.right += dx; mRectF.bottom += dy }
            else -> {}
        }
        // 尺寸下限
        if (mRectF.width() < MIN_SIZE) {
            if (corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT) mRectF.left = mRectF.right - MIN_SIZE
            else mRectF.right = mRectF.left + MIN_SIZE
        }
        if (mRectF.height() < MIN_SIZE) {
            if (corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT) mRectF.top = mRectF.bottom - MIN_SIZE
            else mRectF.bottom = mRectF.top + MIN_SIZE
        }
        // 尺寸上限
        if (mRectF.width() > width * MAX_SIZE_RATIO) {
            if (corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT) mRectF.left = mRectF.right - width * MAX_SIZE_RATIO
            else mRectF.right = mRectF.left + width * MAX_SIZE_RATIO
        }
        if (mRectF.height() > height * MAX_SIZE_RATIO) {
            if (corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT) mRectF.top = mRectF.bottom - height * MAX_SIZE_RATIO
            else mRectF.bottom = mRectF.top + height * MAX_SIZE_RATIO
        }
        constrainToBounds()
    }

    private fun getTouchedCorner(x: Float, y: Float): Corner = when {
        near(x, y, mRectF.left, mRectF.top) -> Corner.TOP_LEFT
        near(x, y, mRectF.right, mRectF.top) -> Corner.TOP_RIGHT
        near(x, y, mRectF.left, mRectF.bottom) -> Corner.BOTTOM_LEFT
        near(x, y, mRectF.right, mRectF.bottom) -> Corner.BOTTOM_RIGHT
        else -> Corner.NONE
    }

    private fun near(x: Float, y: Float, cx: Float, cy: Float): Boolean =
        abs(x - cx) < CORNER_SIZE && abs(y - cy) < CORNER_SIZE

    private fun isInCenterArea(x: Float, y: Float): Boolean =
        abs(x - mRectF.centerX()) < CENTER_TOUCH_SIZE / 2f && abs(y - mRectF.centerY()) < CENTER_TOUCH_SIZE / 2f

    private fun constrainToBounds() {
        if (mRectF.left < 0f) mRectF.offset(-mRectF.left, 0f)
        if (mRectF.top < 0f) mRectF.offset(0f, -mRectF.top)
        if (mRectF.right > width) mRectF.offset(width - mRectF.right, 0f)
        if (mRectF.bottom > height) mRectF.offset(0f, height - mRectF.bottom)
    }

    private fun markChanged() {
        hasUserAdjusted = true
        if (!mChanged) {
            mChanged = true
            onScanAreaChanged?.invoke(true)
        }
    }

    /** 恢复默认框（对应同类产品点击「重置识别区域」） */
    fun resetScanArea() {
        mRectF.set(mDefaultRect)
        if (mRectF.isEmpty) {
            val fw = width * MAX_SIZE_RATIO
            val fh = height * MAX_SIZE_RATIO
            val left = (width - fw) / 2f
            val top = (height - fh) / 2f
            mRectF.set(left, top, left + fw, top + fh)
            mDefaultRect.set(mRectF)
        }
        mChanged = false
        hasUserAdjusted = false
        onScanAreaChanged?.invoke(false)
        invalidate()
    }

    /** 外部指定识别区（自动框题用）。传进来的坐标是本 View 坐标系。 */
    fun setScanRect(r: RectF) {
        mRectF.set(r)
        constrainToBounds()
        mChanged = true
        onScanAreaChanged?.invoke(true)
        invalidate()
    }

    /** 选到整张图（本 View 覆盖整图时即全屏） */
    fun selectAll() {
        hasUserAdjusted = true
        mRectF.set(0f, 0f, width.toFloat(), height.toFloat())
        constrainToBounds()
        mChanged = true
        onScanAreaChanged?.invoke(true)
        invalidate()
    }

    fun setInteractionEnabled(enabled: Boolean) {
        mInteractionEnabled = enabled
    }

    /** 供 OCR 裁图使用（与同类产品 getScanRect 对应） */
    fun getScanRect(): RectF {
        mDisplayRect.set(mRectF)
        return RectF(mDisplayRect)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mScanAnimator?.takeIf { it.isRunning }?.cancel()
    }
}

package com.weizh.tiku.ui.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.weizh.tiku.R
import com.weizh.tiku.data.Repo
import com.weizh.tiku.databinding.ActivityScreenSearchBinding
import com.weizh.tiku.ui.photo.OcrSearch
import com.weizh.tiku.ui.widget.view.ResizableScanView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 录屏搜题的引导与授权页（对应同类产品 ScreenGuideActivity / ScreenSearchActivity 的授权流程）。
 * 流程：悬浮窗权限 → 录屏权限 → 启动 ScreenSearchService → 本页关闭。
 */
class ScreenSearchActivity : androidx.appcompat.app.AppCompatActivity() {

    private val projLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == RESULT_OK && r.data != null) {
            ScreenSearchService.start(this, r.resultCode, r.data!!)
            finish()
        } else {
            android.widget.Toast.makeText(this, "没有录屏权限，无法开启录屏搜题", android.widget.Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            android.widget.Toast.makeText(this, "请先允许「显示在其他应用上层」，再回来点录屏搜题", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projLauncher.launch(mpm.createScreenCaptureIntent())
    }
}

/**
 * 录屏搜题服务：MediaProjection 抓屏 → 按识别区裁剪 → OCR → 本地检索 → 更新悬浮面板。
 *
 * 悬浮层两个窗口（对齐同类产品 ScreenFloatView 的做法）：
 *   ① 全屏不可触摸的识别区窗口（ResizableScanView，平时触摸穿透；点「调整区域」才接管触摸）
 *   ② 可拖动的结果面板（照 activity_screen_search.xml：答案 + 识别原文 + 退出/最小化）
 */
class ScreenSearchService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CH_ID = "weitiku_screen"
        private const val NOTI_ID = 1001
        private const val INTERVAL = 900L

        fun start(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, ScreenSearchService::class.java)
                .putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScreenSearchService::class.java))
        }
    }

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var maskView: ResizableScanView? = null
    private var maskParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private lateinit var panelBinding: ActivityScreenSearchBinding

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var lastText = ""
    @Volatile private var locked = false
    @Volatile private var adjusting = false

    private var screenW = 0
    private var screenH = 0
    private var density = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNoti()
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        density = dm.densityDpi
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_CODE, -1) ?: -1
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (code == -1 || data == null) { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY

        showMask()
        showPanel()
        startProjection(code, data)
        loop()
        return START_NOT_STICKY
    }

    // ---------- 录屏抓帧 ----------

    private fun startProjection(code: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data) ?: run {
            Log.e("WeiTikuScreen", "getMediaProjection failed"); return
        }
        projection = p
        reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        display = p.createVirtualDisplay(
            "weitiku-screen", screenW, screenH, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null
        )
    }

    private fun loop() {
        job = scope.launch {
            while (isActive) {
                delay(INTERVAL)
                if (locked) continue
                runCatching { grabOnce() }.onFailure { Log.e("WeiTikuScreen", "grab", it) }
            }
        }
    }

    private suspend fun grabOnce() {
        val r = reader ?: return
        val image = r.acquireLatestImage() ?: return
        val bmp = try {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val rowPadding = rowStride - pixelStride * screenW
            val w = screenW + rowPadding / pixelStride
            val full = Bitmap.createBitmap(w, screenH, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            if (w != screenW) Bitmap.createBitmap(full, 0, 0, screenW, screenH).also { full.recycle() } else full
        } finally {
            image.close()
        }

        val sel = selectionRect(bmp.width, bmp.height)
        if (sel.width() < 40 || sel.height() < 20) return
        val crop = runCatching {
            Bitmap.createBitmap(
                bmp, sel.left.toInt(), sel.top.toInt(),
                sel.width().toInt().coerceAtMost(bmp.width - sel.left.toInt()),
                sel.height().toInt().coerceAtMost(bmp.height - sel.top.toInt())
            )
        }.getOrNull() ?: return
        bmp.recycle()

        val result = OcrSearch.run(this, crop, 3)
        crop.recycle()
        val text = result.text
        if (text.isEmpty() || text == lastText) return
        lastText = text
        updatePanel(text, result.hits)
    }

    /** 识别区（屏幕坐标，VirtualDisplay 与屏幕 1:1，所以直接就是图像坐标） */
    private fun selectionRect(w: Int, h: Int): RectF {
        val v = maskView ?: return RectF(0f, 0f, 0f, 0f)
        val r = v.getScanRect()
        // 若 VirtualDisplay 尺寸与屏幕一致，则坐标相同；否则等比换算
        val sx = w.toFloat() / screenW
        val sy = h.toFloat() / screenH
        return RectF(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy)
    }

    // ---------- 悬浮层 ----------

    private fun showMask() {
        val v = ResizableScanView(this)
        maskView = v
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        maskParams = lp
        runCatching { wm.addView(v, lp) }
    }

    private fun showPanel() {
        panelBinding = ActivityScreenSearchBinding.inflate(LayoutInflater.from(this))
        val v = panelBinding.root
        panel = v
        panelBinding.tvSuggest.text = "录屏搜题已开启"
        panelBinding.tvContent.text = "把识别区对准题目，会自动出答案。点「调整区域」可拖动识别框。"
        panelBinding.btnExit.setOnClickListener { stopSelf() }
        panelBinding.btnPip.setOnClickListener {
            val collapsed = panelBinding.tvContent.visibility == View.VISIBLE
            panelBinding.tvContent.visibility = if (collapsed) View.GONE else View.VISIBLE
            panelBinding.tvSuggest.text = if (collapsed) (panelBinding.tvSuggest.text) else "录屏搜题已开启"
        }

        val lp = WindowManager.LayoutParams(
            screenW - dp(24), WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(12)
        lp.y = dp(80)
        panelParams = lp
        runCatching { wm.addView(v, lp) }

        // 拖动面板 + 长按进入/退出识别区调整
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        panelBinding.tvSuggest.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; false
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(v, lp) }
                    true
                }
                else -> false
            }
        }
        panelBinding.tvSuggest.setOnLongClickListener { toggleAdjust(); true }
    }

    /** 调整识别区：临时让识别层可触摸，拖完再点面板恢复穿透 */
    private fun toggleAdjust() {
        val lp = maskParams ?: return
        adjusting = !adjusting
        if (adjusting) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            panelBinding.tvSuggest.text = "调整中：拖动识别框"
            panelBinding.tvContent.text = "捏合缩放 / 拖角改大小 / 拖中心移动，长按这里结束调整。"
        } else {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            panelBinding.tvSuggest.text = "录屏搜题已开启"
            panelBinding.tvContent.text = "识别区已保存。长按标题可再次调整。"
            lastText = ""
        }
        runCatching { maskView?.let { wm.updateViewLayout(it, lp) } }
    }

    private fun updatePanel(text: String, rows: List<com.weizh.tiku.search.SearchEngine.ScanHit>) {
        Handler(Looper.getMainLooper()).post {
            val b = panelBinding
            if (rows.isEmpty()) {
                b.tvSuggest.text = "没匹配到本地题库"
                b.tvContent.text = "识别：$text"
                return@post
            }
            val top = rows.first()
            val q = top.question
            b.tvSuggest.text = "答案：${q.answer}"
            val sb = StringBuilder()
            sb.append(q.question).append('\n')
            q.options.forEachIndexed { i, o -> if (i < 10 && o.isNotBlank()) sb.append('A' + i).append(". ").append(o).append('\n') }
            sb.append("\n识别：").append(text)
            if (rows.size > 1) {
                sb.append("\n\n其他候选：")
                rows.drop(1).forEach { sb.append('\n').append(it.question.answer).append(' ').append(it.question.question.take(24)) }
            }
            b.tvContent.text = sb.toString()
            b.tvContent.visibility = View.VISIBLE
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun startForegroundNoti() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_ID, "录屏搜题", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, com.weizh.tiku.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CH_ID) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setContentTitle("魏题库 · 录屏搜题运行中")
            .setContentText("点悬浮面板「退出」可停止")
            .setSmallIcon(R.drawable.ic_kong_scan)
            .setContentIntent(pi)
            .build()
        startForeground(NOTI_ID, n)
    }

    override fun onDestroy() {
        job?.cancel()
        runCatching { maskView?.let { wm.removeView(it) } }
        runCatching { panel?.let { wm.removeView(it) } }
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        super.onDestroy()
    }
}

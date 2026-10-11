package com.weizh.tiku.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.weizh.tiku.data.Repo
import com.weizh.tiku.databinding.ActivityScanBinding
import com.weizh.tiku.ocr.OcrClient
import com.weizh.tiku.ocr.OcrEngine
import com.weizh.tiku.search.SearchEngine
import com.weizh.tiku.ui.ResultAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫描搜题。界面结构照同类产品 activity_camera.xml：
 *   相机全屏做背景 → 垂直分栏：上半「可调识别区」/ 下半「结果列表」，两者物理不重叠，
 *   所以答案不会挡住识别区域。识别到的文字框高亮标回预览，方便确认认没认对。
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var b: ActivityScanBinding
    private val adapter = ResultAdapter(scanMode = true)
    private val busy = AtomicBoolean(false)
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var lastRun = 0L
    @Volatile private var lastText = ""
    @Volatile private var lastIds: List<Long> = emptyList()
    @Volatile private var locked = false

    private val minIntervalMs = 150L

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = runCatching {
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp == null) { toast("图片读取失败"); return@launch }
            process(bmp, fromCamera = false)
        }
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else b.tvScanHint.text = "没有相机权限，长按返回键可用「选图识别」"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityScanBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.rvHits.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.rvHits.adapter = adapter

        b.btnBack.setOnClickListener { finish() }
        // 同类产品没这个入口，藏成长按返回键，不干扰主界面
        b.btnBack.setOnLongClickListener {
            pickImage.launch(arrayOf("image/*")); true
        }

        // 框改动 → 点亮"重置识别区域"，对齐同类产品 updateResetButtonStyle
        b.scanView.onScanAreaChanged = { changed -> updateResetStyle(changed) }
        b.tvReset.setOnClickListener {
            b.scanView.resetScanArea()
            b.boxOverlay.clear()
            lastText = ""; lastIds = emptyList()
            b.tvOcr.text = "已重置识别区域，重新对准题目"
        }
        b.tvLock.setOnClickListener {
            locked = !locked
            b.tvLock.text = if (locked) "已解锁" else "锁定结果"
            b.tvScanHint.text = if (locked) "结果已锁定（点「已解锁」继续识别）"
            else "缩放：捏合手势/拖动边缘 | 移动：拖动中心"
            Toast.makeText(this, if (locked) "已锁定，不再刷新结果" else "已解锁", Toast.LENGTH_SHORT).show()
        }
        updateResetStyle(false)

        lifecycleScope.launch(Dispatchers.IO) {
            val t = System.currentTimeMillis()
            OcrClient.warmUp(this@ScanActivity)
            val ok = true
            val n = Repo.rebuildIndex(this@ScanActivity)
            Log.i("WeiTikuScan", "prepare: ocr=$ok index=$n in ${System.currentTimeMillis() - t}ms")
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }

        if (com.weizh.tiku.BuildConfig.DEBUG) {
            val ti = intent?.getBooleanExtra("testimage", false) == true
            val tc = intent?.getBooleanExtra("testimagecam", false) == true
            if (ti || tc) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val bmp = assets.open("ocr/test.png").use { BitmapFactory.decodeStream(it) }
                    process(bmp, fromCamera = tc)
                }
            }
        }
    }

    private fun updateResetStyle(changed: Boolean) {
        b.tvReset.setBackgroundResource(
            if (changed) com.weizh.tiku.R.drawable.btn_bg_reset_white
            else com.weizh.tiku.R.drawable.btn_bg_reset_dim
        )
        b.tvReset.setTextColor(if (changed) 0xFF222222.toInt() else 0xB3FFFFFF.toInt())
    }

    // ---------------- 相机 ----------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(b.preview.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analyzerExecutor) { proxy -> onFrame(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (t: Throwable) {
                Log.e("WeiTikuScan", "camera start failed", t)
                b.tvScanHint.text = "相机不可用（长按返回键可用选图识别）"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(proxy: ImageProxy) {
        // 节流照同类产品 OcrManager.isThreadRunning()：上一次识别没跑完就直接丢帧。
        // 识别快就跟得快，识别慢自动降频，不会积压——比固定间隔更合理。
        if (locked || busy.get()) { proxy.close(); return }
        // 兜底下限：同类产品没有这个（帧率天然受限），这里防止异常情况狂刷 CPU
        val now = System.currentTimeMillis()
        if (now - lastRun < minIntervalMs) { proxy.close(); return }
        lastRun = now
        busy.set(true)

        // 旋转换算（对齐同类产品把 frame.getRotationToView() 传给 Utils.getCrop 的做法）：
        // ImageProxy.toBitmap() 给的是传感器方向的帧，后摄竖屏时 rotationDegrees=90，
        // 不转正的话"扫描框 → 图像坐标"的映射会整体错位（框住题干却裁到别处）。
        val deg = proxy.imageInfo.rotationDegrees
        val bmp = try {
            val raw = proxy.toBitmap()
            if (deg == 0) raw else rotate(raw, deg)
        } catch (t: Throwable) {
            Log.e("WeiTikuScan", "frame decode failed", t); null
        } finally {
            proxy.close()
        }
        if (bmp == null) { busy.set(false); return }
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                process(bmp, fromCamera = true)
            } finally {
                // 每帧的位图（可能还带一次旋转换算）必须回收，否则连续扫描会持续涨内存
                if (!bmp.isRecycled) bmp.recycle()
                busy.set(false)
            }
        }
    }

    // ---------------- 识别 + 检索 ----------------

    private suspend fun process(bmp: Bitmap, fromCamera: Boolean) {
        try {
            if (locked) return
            if (!SearchEngine.get().isReady) Repo.rebuildIndex(this)

            val region = if (fromCamera) mapScanRectToBitmap(bmp) else null

            // 先按识别区域裁剪再送 OCR：整帧识别会把长边压到 960，框里的小字会被压糊
            val cropped = if (region != null && region.width() > 48 && region.height() > 48) {
                runCatching {
                    Bitmap.createBitmap(
                        bmp, region.left.toInt(), region.top.toInt(),
                        region.width().toInt().coerceAtMost(bmp.width - region.left.toInt()),
                        region.height().toInt().coerceAtMost(bmp.height - region.top.toInt())
                    )
                }.getOrNull()
            } else null
            val target = cropped ?: bmp

            val ox = if (cropped != null) (region?.left ?: 0f) else 0f
            val oy = if (cropped != null) (region?.top ?: 0f) else 0f

            // ① 优先走 :ocr 隔离进程（照原版设计）；拿到 null 说明不可用，回退进程内
            val iso = OcrClient.recognize(this, target)
            val text: String
            if (iso != null) {
                if (iso.isEmpty()) {
                    if (cropped != null) cropped.recycle()
                    runOnUiThread {
                        b.tvOcr.text = "识别区里没找到文字，把框对准题干"
                        b.boxOverlay.clear()
                    }
                    return
                }
                if (fromCamera) {
                    val viewRects = iso.mapNotNull { ln ->
                        mapBitmapRectToView(
                            RectF(ln.left + ox, ln.top + oy, ln.right + ox, ln.bottom + oy),
                            bmp.width.toFloat(), bmp.height.toFloat()
                        )
                    }
                    runOnUiThread { b.boxOverlay.setBoxes(viewRects) }
                }
                text = iso.joinToString(" ") { it.text }.trim()
            } else {
                // 回退：隔离进程不可用时才在主进程加载模型
                OcrEngine.ensureLoaded(this)
                if (!OcrEngine.isReady) {
                    if (cropped != null) cropped.recycle()
                    runOnUiThread { b.tvOcr.text = "OCR 模型加载中…" }
                    return
                }
                val boxes = OcrEngine.detect(target)
                if (boxes.isEmpty()) {
                    if (cropped != null) cropped.recycle()
                    runOnUiThread {
                        b.tvOcr.text = "识别区里没找到文字，把框对准题干"
                        b.boxOverlay.clear()
                    }
                    return
                }
                if (fromCamera) {
                    val viewRects = boxes.mapNotNull { box ->
                        mapBitmapRectToView(
                            RectF(box.left + ox, box.top + oy, box.right + ox, box.bottom + oy),
                            bmp.width.toFloat(), bmp.height.toFloat()
                        )
                    }
                    runOnUiThread { b.boxOverlay.setBoxes(viewRects) }
                }
                val sb = StringBuilder()
                for (box in boxes) {
                    val line = OcrEngine.recognizeLine(target, box)
                    if (line.isNotBlank()) sb.append(line).append(' ')
                }
                text = sb.toString().trim()
            }
            if (cropped != null && cropped != bmp) cropped.recycle()

            if (text.isEmpty()) return
            if (text == lastText) return
            lastText = text

            val (rows, reason) = SearchEngine.get().searchForScan(text, 3)
            if (rows.isEmpty()) {
                runOnUiThread { b.tvOcr.text = "识别：$text　·　$reason" }
                return
            }
            val ids = rows.map { it.question.id }
            if (ids == lastIds) {
                runOnUiThread { b.tvOcr.text = "识别：$text" }
                return
            }
            lastIds = ids
            runOnUiThread {
                b.tvOcr.text = "识别：$text"
                adapter.submit(rows)
            }
        } catch (t: Throwable) {
            Log.e("WeiTikuScan", "process failed", t)
        }
    }

    /** 帧转正：转正后扫描框坐标可按 PreviewView 的 FILL_CENTER 直接映射（对齐同类产品传 degree 的做法） */
    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val m = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    /** 识别区（ScanFrameView 坐标）→ 位图坐标；相机预览 FILL_CENTER，且扫描区只占屏幕一部分 */
    private fun mapScanRectToBitmap(bmp: Bitmap): RectF? {
        val pv = b.preview
        val sf = b.scanView
        val vw = pv.width.toFloat()
        val vh = pv.height.toFloat()
        if (vw <= 0f || vh <= 0f) return null
        val pvLoc = IntArray(2).also { pv.getLocationOnScreen(it) }
        val sfLoc = IntArray(2).also { sf.getLocationOnScreen(it) }
        val ox = (sfLoc[0] - pvLoc[0]).toFloat()
        val oy = (sfLoc[1] - pvLoc[1]).toFloat()

        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val scale = maxOf(vw / bw, vh / bh)
        val dx = (vw - bw * scale) / 2f
        val dy = (vh - bh * scale) / 2f

        val r = sf.getScanRect()
        val out = RectF(
            ((r.left + ox - dx) / scale).coerceIn(0f, bw),
            ((r.top + oy - dy) / scale).coerceIn(0f, bh),
            ((r.right + ox - dx) / scale).coerceIn(0f, bw),
            ((r.bottom + oy - dy) / scale).coerceIn(0f, bh)
        )
        // 诊断用：真机上若"框住题干却识别到别处"，看这行即可判断是坐标映射问题
        Log.i(
            "WeiTikuScan",
            "map: preview=${vw.toInt()}x${vh.toInt()} img=${bw.toInt()}x${bh.toInt()} " +
                "off=(${ox.toInt()},${oy.toInt()}) scan=(${r.left.toInt()},${r.top.toInt()},${r.right.toInt()},${r.bottom.toInt()}) " +
                "-> crop=(${out.left.toInt()},${out.top.toInt()},${out.right.toInt()},${out.bottom.toInt()})"
        )
        return out
    }

    /** 位图坐标 → ScanFrameView 坐标（逆变换，供绿色识别框） */
    private fun mapBitmapRectToView(r: RectF, bw: Float, bh: Float): RectF? {
        val pv = b.preview
        val sf = b.scanView
        val vw = pv.width.toFloat()
        val vh = pv.height.toFloat()
        if (vw <= 0f || vh <= 0f || bw <= 0f || bh <= 0f) return null
        val pvLoc = IntArray(2).also { pv.getLocationOnScreen(it) }
        val sfLoc = IntArray(2).also { sf.getLocationOnScreen(it) }
        val ox = (sfLoc[0] - pvLoc[0]).toFloat()
        val oy = (sfLoc[1] - pvLoc[1]).toFloat()

        val scale = maxOf(vw / bw, vh / bh)
        val dx = (vw - bw * scale) / 2f
        val dy = (vh - bh * scale) / 2f
        return RectF(
            r.left * scale + dx - ox, r.top * scale + dy - oy,
            r.right * scale + dx - ox, r.bottom * scale + dy - oy
        )
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { analyzerExecutor.shutdown() }
    }
}

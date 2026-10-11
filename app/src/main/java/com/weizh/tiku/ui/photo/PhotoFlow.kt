package com.weizh.tiku.ui.photo

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.weizh.tiku.data.Repo
import com.weizh.tiku.databinding.ActivityPhotoCameraBinding
import com.weizh.tiku.databinding.ActivityPhotoCropBinding
import com.weizh.tiku.databinding.ActivityPhotoResultBinding
import com.weizh.tiku.ocr.OcrClient
import com.weizh.tiku.ocr.OcrEngine
import com.weizh.tiku.search.SearchEngine
import com.weizh.tiku.ui.ResultAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** OCR + 检索的公共入口：拍照搜题和录屏搜题共用 */
object OcrSearch {
    suspend fun run(ctx: Context, bmp: Bitmap, topN: Int = 10): Pair<String, List<SearchEngine.ScanHit>> {
        if (!SearchEngine.get().isReady) Repo.rebuildIndex(ctx)

        // ① 优先走 :ocr 隔离进程（照原版设计：模型不占主进程内存）
        val iso = withContext(Dispatchers.IO) { OcrClient.recognize(ctx, bmp) }
        val lines = ArrayList<String>()
        if (iso != null) {
            iso.forEach { if (it.text.isNotBlank()) lines.add(it.text.trim()) }
        } else {
            // ② 隔离进程不可用 → 回退到进程内 OCR
            OcrEngine.ensureLoaded(ctx)
            if (!OcrEngine.isReady) return "" to emptyList()
            for (box in OcrEngine.detect(bmp)) {
                val line = OcrEngine.recognizeLine(bmp, box)
                if (line.isNotBlank()) lines.add(line.trim())
            }
        }
        val text = lines.joinToString(" ")
        if (text.isEmpty()) return "" to emptyList()

        // ① 整段当一道题去匹配
        var (rows, _) = SearchEngine.get().searchForScan(text, topN)
        // 【关键】本地命中判定 —— 照同类产品 PhotoResultActivity.searchLocalForCard 的规则：
        //     lcsLen >= 8 且 lcsLen / queryLen >= LOCAL_MATCH_MIN_RATIO(0.6)
        // 不满足就认为"本地题库未匹配到该题目"，继续往下走（最终会交给云端作答）。
        // 之前缺这道判定 → 拍"中国的首都在哪里"会把共享 中/国/的/在 的无关铁路题当命中返回，
        // 而且因为"本地有结果"，云端兜底永远轮不到。
        val qLen = text.count { !it.isWhitespace() }
        val needLcs = localMatchMinLcs(qLen)
        rows = rows.filter { it.lcs >= needLcs && it.ratio >= LOCAL_MATCH_MIN_RATIO }
        if (rows.isNotEmpty()) return text to rows

        // ② 整段匹配不到，说明拍到了整页（多道题/带其他文字）：
        //    逐行找"最像题干"的一行分别检索，取公共片段最长的那条结果。
        data class Best(val line: String, val hits: List<SearchEngine.ScanHit>, val lcs: Int)
        val cands = lines.filter { it.count { c -> !c.isWhitespace() } >= 6 }.mapNotNull { line ->
            val (h, _) = SearchEngine.get().searchForScan(line, 1)
            val top = h.firstOrNull() ?: return@mapNotNull null
            val lLen = line.count { !it.isWhitespace() }
            if (top.lcs < localMatchMinLcs(lLen) || top.ratio < LOCAL_MATCH_MIN_RATIO) return@mapNotNull null
            Best(line, h, top.lcs)
        }
        val best = cands.maxByOrNull { it.lcs } ?: return cloudTry(ctx, bmp, text)

        val idx = lines.indexOf(best.line)
        val ctxLine = lines.subList(maxOf(0, idx - 1), minOf(lines.size, idx + 4)).joinToString(" ")
        val (rows2, _) = SearchEngine.get().searchForScan(ctxLine, topN)
        // 返回"实际命中用的那段文本"，不要返回整页原文——否则 Word 工具栏之类
        // 的界面文字会一起显示在「识别：」里
        if (rows2.isNotEmpty()) return ctxLine to rows2

        // ③ 还不行 → 用选项乱序排列匹配（照原版 allPerm：题库之间选项顺序不同也能对上）
        val perm = SearchEngine.get().searchWithPermutations(ctxLine, topN)
        if (perm.isNotEmpty()) return ctxLine to perm

        // ④ 本地彻底没辙 → 云端 VLM 兜底（结构化 + 补答案）。开关在「我的-设置」里，默认关。
        return cloudTry(ctx, bmp, text)
    }

    /**
     * 云端兜底：把整页图发给 VLM 要结构化题目。
     * 先拿云端裁好的题干回本地检索（本地命中就显示本地答案），本地没有才用云端的答案。
     * 未开启 / 网络失败 / 解析失败 → 返回原文本 + 空结果（不影响主流程）。
     */
    private suspend fun cloudTry(
        ctx: Context, bmp: Bitmap, text: String
    ): Pair<String, List<SearchEngine.ScanHit>> {
        val ai = withContext(Dispatchers.IO) { com.weizh.tiku.cloud.CloudVlm.structure(ctx, bmp) }
        if (ai.isNullOrEmpty()) return text to emptyList()
        val hits = ArrayList<SearchEngine.ScanHit>()
        for (a in ai) {
            val (loc, _) = SearchEngine.get().searchText(a.question + a.options.joinToString(""), 1)
            val lh = loc.firstOrNull()
            if (lh != null && lh.lcs >= 6) { hits.add(lh); continue }
            hits.add(
                SearchEngine.ScanHit(
                    com.weizh.tiku.data.Question(
                        id = 0, tid = AI_TID,
                        question = a.question,
                        answers = a.options.joinToString(com.weizh.tiku.data.Db.DELIM_A),
                        answer = buildString {
                            append(a.answer.ifBlank { "题目已识别，但未能给出答案（可到设置里检查云端 AI 是否正常）" })
                            if (a.explain.isNotBlank()) append("\n\n【AI 解析】").append(a.explain)
                        }
                    ),
                    score = 0.0, lcs = 0, ratio = 1.0
                )
            )
        }
        return text to hits
    }

    /** 云端结果的标记 tid，UI 上据此显示"来源：AI" */
    const val AI_TID = "ai"

    /** 显示/识别用的最大边长。手机原图可达 5560x7408（41MP，ARGB_8888 = 164MB），
     *  超过 GPU 最大纹理尺寸（常见 4096）时 ImageView 根本画不出来（表现为全黑），
     *  而且 164MB 位图随时 OOM。这里解码时就降采样。 */
    private const val MAX_SIDE = 2048

    /**
     * 读图：先只读尺寸算出 inSampleSize，再按需解码。
     * 直接 decodeFile 原图会拿到 164MB 的位图 → ImageView 黑屏 + OOM 风险。
     */
    fun readBitmap(path: String): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > MAX_SIDE) sample *= 2
        val opt = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(path, opt)
    }.getOrNull()
}

/**
 * 拍照搜题页，照同类产品 activity_photo_camera.xml：
 * 全屏相机 + 返回/闪光 + 底部栏（相册 / 快门 / 扫描搜题）
 */
class PhotoCameraActivity : AppCompatActivity() {

    private lateinit var b: ActivityPhotoCameraBinding
    private var imageCapture: ImageCapture? = null
    private val analyzerExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val analyzing = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastAnalyze = 0L
    /** 实时框到的题目区域（归一化 0..1），按快门时用它初始化裁剪框 */
    @Volatile private var liveNorm: android.graphics.RectF? = null
    private var pendingFile: File? = null
    private var flashOn = false

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val f = File(cacheDir, "picked_${System.currentTimeMillis()}.jpg")
            runCatching {
                contentResolver.openInputStream(uri)?.use { ins -> FileOutputStream(f).use { ins.copyTo(it) } }
            }
            if (f.exists()) openCrop(f.absolutePath) else runOnUiThread { toast("图片读取失败") }
        }
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else toast("没有相机权限，可点左下角从相册选图")
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingFile
        if (ok && f != null && f.exists()) openCrop(f.absolutePath)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPhotoCameraBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.btnFlash.setOnClickListener {
            flashOn = !flashOn
            imageCapture?.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
            b.btnFlash.setImageResource(if (flashOn) com.weizh.tiku.R.drawable.ic_flash_on else com.weizh.tiku.R.drawable.ic_flash_off)
        }
        b.containerHistory.setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        b.btnPickAlbum2.setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        b.containerScan.setOnClickListener {
            startActivity(Intent(this, com.weizh.tiku.ui.scan.ScanActivity::class.java))
            finish()
        }
        b.btnShutter.setOnClickListener { shoot() }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) startCamera() else askCamera.launch(android.Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(b.cameraView.surfaceProvider) }
                imageCapture = ImageCapture.Builder().build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analyzerExec) { proxy -> onAnalyze(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
            }.onFailure {
                Log.e("WeiTikuPhoto", "camera failed", it)
                showCameraHint("相机没能启动。\n可以点下面按钮从相册选图。")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 实时框题（豆包式）：每 ~700ms 抓一帧，只跑【检测】（不识别，快），
     * 算出题目区域画在预览上。这样你举着手机就能看到框有没有套住题目。
     */
    private fun onAnalyze(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (analyzing.get() || now - lastAnalyze < 700) { proxy.close(); return }
        lastAnalyze = now
        analyzing.set(true)

        val deg = proxy.imageInfo.rotationDegrees
        val bmp = try {
            val raw = proxy.toBitmap()
            if (deg == 0) raw else {
                val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
                android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                    .also { if (it !== raw) raw.recycle() }
            }
        } catch (t: Throwable) {
            null
        } finally {
            proxy.close()
        }
        if (bmp == null) { analyzing.set(false); return }

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val boxes = com.weizh.tiku.ocr.OcrClient.detect(this@PhotoCameraActivity, bmp)
                if (boxes.isNullOrEmpty()) return@launch
                val norm = QuestionRegion.pickFromBoxes(boxes, bmp.width, bmp.height) ?: return@launch
                liveNorm = norm
                val v = normToView(norm, bmp.width, bmp.height)
                runOnUiThread { b.boxOverlay.setBoxes(listOf(v)) }
            } catch (t: Throwable) {
                Log.w("WeiTikuPhoto", "analyze failed", t)
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
                analyzing.set(false)
            }
        }
    }

    /** 归一化图坐标 → 预览 View 坐标（PreviewView FILL_CENTER） */
    private fun normToView(n: android.graphics.RectF, imgW: Int, imgH: Int): android.graphics.RectF {
        val vw = b.cameraView.width.toFloat()
        val vh = b.cameraView.height.toFloat()
        if (vw <= 0f || vh <= 0f || imgW <= 0 || imgH <= 0) return android.graphics.RectF()
        val scale = maxOf(vw / imgW, vh / imgH)
        val dx = (vw - imgW * scale) / 2f
        val dy = (vh - imgH * scale) / 2f
        return android.graphics.RectF(
            n.left * imgW * scale + dx, n.top * imgH * scale + dy,
            n.right * imgW * scale + dx, n.bottom * imgH * scale + dy
        )
    }

    private fun shoot() {
        val cap = imageCapture ?: run { toast("相机未就绪，可从相册选图"); return }
        val f = File(cacheDir, "shot_${System.currentTimeMillis()}.jpg")
        pendingFile = f
        captureOnce(cap, f, attempt = 1)
    }

    /**
     * 拍照。有些设备第一次出图会是黑的（预览正常、JPEG 全黑/极暗），
     * 这里出图后立刻量一下亮度：太暗就自动重拍一次；仍不行再交给裁剪页并提示。
     * 同时把统计写进日志，便于定位。
     */
    private fun captureOnce(cap: ImageCapture, f: File, attempt: Int) {
        cap.takePicture(
            ImageCapture.OutputFileOptions.Builder(f).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    val st = fileStats(f)
                    Log.i("WeiTikuPhoto", "capture#$attempt ${f.name} size=${f.length()}B $st")
                    if (st.dark && attempt < 2) {
                        Log.w("WeiTikuPhoto", "capture#$attempt too dark, retry")
                        captureOnce(cap, f, attempt + 1)
                        return
                    }
                    if (st.dark) toast("照片偏暗（相机可能没出图），可在裁剪页点「重拍」")
                    openCrop(f.absolutePath)
                }

                override fun onError(e: ImageCaptureException) {
                    Log.e("WeiTikuPhoto", "takePicture error", e)
                    toast("拍照失败：${e.message}")
                }
            }
        )
    }

    /** 量化一张 JPEG 的亮度：解码后每 16 像素采样，返回平均/最大通道值 */
    private data class ImgStat(val dark: Boolean, val desc: String)

    private fun fileStats(f: File): ImgStat {
        val bmp = runCatching { android.graphics.BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
            ?: return ImgStat(true, "decode=null")
        var sum = 0L
        var cnt = 0L
        var maxV = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val p = bmp.getPixel(x, y)
                val v = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                sum += v; cnt++
                if (v > maxV) maxV = v
                x += 16
            }
            y += 16
        }
        val avg = if (cnt == 0L) 0 else (sum / cnt).toInt()
        bmp.recycle()
        // 判据：平均亮度 < 8 视为没出图（正常拍屏幕/纸张平均都在 40 以上）
        return ImgStat(avg < 8, "w=${bmp.width} h=${bmp.height} avg=$avg max=$maxV")
    }

    private fun openCrop(path: String) {
        val i = Intent(this, PhotoCropActivity::class.java).putExtra("path", path)
        // 把取景时框到的区域带过去，裁剪页直接按它初始化（对齐豆包：拍下即已框好）
        liveNorm?.let { i.putExtra("norm", android.graphics.RectF(it)) }
        startActivity(i)
    }

    /** 相机不可用时给明确提示，不要只留一片黑 */
    private fun showCameraHint(msg: String) {
        b.llCameraHint.visibility = View.VISIBLE
        b.tvCameraHint.text = msg
        b.tvPhotoPoints.text = "相机不可用"
    }


    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

}

/**
 * 裁剪页，照同类产品 activity_photo_crop.xml：
 * 整图 + 可拖动/缩放的裁剪框（复用 ResizableScanView）+ 底部（重拍 / 确认）
 */
class PhotoCropActivity : AppCompatActivity() {

    private lateinit var b: ActivityPhotoCropBinding
    private var bmp: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPhotoCropBinding.inflate(layoutInflater)
        setContentView(b.root)

        val path = intent.getStringExtra("path").orEmpty()
        lifecycleScope.launch(Dispatchers.IO) {
            val loaded = OcrSearch.readBitmap(path)
            withContext(Dispatchers.Main) {
                bmp = loaded
                if (loaded == null) { toast("图片读取失败"); finish(); return@withContext }
                b.ivCropImage.setImageBitmap(loaded)
                b.tvPhotoPoints.text = if (isNearlyBlack(loaded))
                    "照片是黑的：相机可能被占用/遮挡，点「重拍」或用相册选图"
                else "正在自动框选题目…"
                if (!isNearlyBlack(loaded)) {
                    // 取景时就框好的区域优先生效（豆包式）；没有才本地检测
                    val norm: android.graphics.RectF? =
                        @Suppress("DEPRECATION") intent?.getParcelableExtra("norm")
                    if (norm != null && norm.width() > 0f && norm.height() > 0f) {
                        // 关键：必须等 ImageView 布局完成（宽高非 0）再设框，
                        // 否则 imageRectToView 拿不到尺寸，框会整体错位
                        b.ivCropImage.post {
                            val r = android.graphics.RectF(
                                norm.left * loaded.width, norm.top * loaded.height,
                                norm.right * loaded.width, norm.bottom * loaded.height
                            )
                            b.cropView.setScanRect(imageRectToView(r, loaded))
                            b.tvPhotoPoints.text = "已按取景框套好题目，可拖角微调或点「整页」"
                        }
                    } else {
                        autoPickRegion(loaded)
                    }
                }
            }
        }

        b.btnBack.setOnClickListener { finish() }
        b.btnRetake.setOnClickListener { finish() }
        b.btnConfirm.setOnClickListener { confirm() }
        b.btnFullPage.setOnClickListener {
            b.cropView.selectAll()
            b.tvPhotoPoints.text = "已选整页（可拖四个角再收紧）"
        }
    }

    private fun confirm() {
        val src = bmp ?: return
        if (isNearlyBlack(src)) {
            toast("照片是黑的，识别不了。点「重拍」，或长按返回键从相册选图")
            return
        }
        b.btnConfirm.isEnabled = false
        lifecycleScope.launch {
            val r = rectInImage(src)
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    Bitmap.createBitmap(
                        src, r.left.toInt().coerceIn(0, src.width - 1), r.top.toInt().coerceIn(0, src.height - 1),
                        r.width().toInt().coerceIn(1, src.width - r.left.toInt()),
                        r.height().toInt().coerceIn(1, src.height - r.top.toInt())
                    )
                }.getOrNull()
            }
            if (out == null) { toast("裁剪失败"); b.btnConfirm.isEnabled = true; return@launch }
            val f = File(cacheDir, "crop_${System.currentTimeMillis()}.jpg")
            withContext(Dispatchers.IO) {
                FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            }
            startActivity(Intent(this@PhotoCropActivity, PhotoResultActivity::class.java)
                .putExtra("path", f.absolutePath))
            finish()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /**
     * 拍照后自动框住题目（对齐豆包的取题框）：跑一遍检测 → 挑出题目块 →
     * 把裁剪框设过去。用户仍可拖角微调，或点「整页」选全图。
     */
    private fun autoPickRegion(src: Bitmap) {
        lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) {
                com.weizh.tiku.ocr.OcrClient.recognize(this@PhotoCropActivity, src)
            }
            if (lines.isNullOrEmpty()) {
                b.tvPhotoPoints.text = "没检测到文字，可手动拖动取景框"
                return@launch
            }
            val rectInImg = QuestionRegion.pick(lines, src.width, src.height)
            if (rectInImg == null) {
                b.tvPhotoPoints.text = "没框到明显题目，可手动拖动取景框"
                return@launch
            }
            // 同样要等布局完成再设框
            b.ivCropImage.post {
                b.cropView.setScanRect(imageRectToView(rectInImg, src))
                b.tvPhotoPoints.text = "已自动框住题目，可拖角微调或点「整页」"
            }
        }
    }

    /** 原图像素坐标 → 裁剪框 View 坐标（fitCenter 的反变换） */
    private fun imageRectToView(r: RectF, src: Bitmap): RectF {
        val vw = b.ivCropImage.width.toFloat()
        val vh = b.ivCropImage.height.toFloat()
        if (vw <= 0f || vh <= 0f) return r
        val scale = minOf(vw / src.width, vh / src.height)
        val dx = (vw - src.width * scale) / 2f
        val dy = (vh - src.height * scale) / 2f
        return RectF(
            r.left * scale + dx, r.top * scale + dy,
            r.right * scale + dx, r.bottom * scale + dy
        )
    }

    /** 把裁剪框（View 坐标）映射回原图像素坐标；ImageView 是 fitCenter */
    private fun rectInImage(src: Bitmap): RectF {
        val vw = b.ivCropImage.width.toFloat()
        val vh = b.ivCropImage.height.toFloat()
        val r = b.cropView.getScanRect()
        if (vw <= 0f || vh <= 0f) return RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        val scale = minOf(vw / src.width, vh / src.height)
        val dx = (vw - src.width * scale) / 2f
        val dy = (vh - src.height * scale) / 2f
        return RectF(
            ((r.left - dx) / scale).coerceIn(0f, src.width.toFloat()),
            ((r.top - dy) / scale).coerceIn(0f, src.height.toFloat()),
            ((r.right - dx) / scale).coerceIn(0f, src.width.toFloat()),
            ((r.bottom - dy) / scale).coerceIn(0f, src.height.toFloat())
        )
    }
}

/**
 * 结果页，照同类产品 activity_photo_result.xml：
 * toolbar（返回 / 标题 / 调整裁剪图）+ 裁剪图 + 识别原文 + 结果卡片（白卡，同搜索页）
 */
class PhotoResultActivity : AppCompatActivity() {

    private lateinit var b: ActivityPhotoResultBinding
    private val adapter = ResultAdapter(scanMode = false)
    private var boxTexts: List<String> = emptyList()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** 点某个题目框 → 滚到对应的结果卡（按文本相似度配） */
    private fun scrollToBox(index: Int) {
        val txt = boxTexts.getOrNull(index) ?: return
        var bestPos = -1
        var bestScore = 0
        for (p in 0 until adapter.itemCount) {
            val q = adapter.questionAt(p) ?: continue
            val s = lcsLen(txt, q)
            if (s > bestScore) { bestScore = s; bestPos = p }
        }
        if (bestPos >= 0 && bestScore >= 6) b.rvResult.scrollToPosition(bestPos)
        else toast("第 ${index + 1} 个框：本地题库没匹配到这道题")
    }

    private fun lcsLen(a: String, b: String): Int {
        val n = a.length; val m = b.length
        if (n == 0 || m == 0) return 0
        val dp = IntArray(m + 1)
        var prev: Int
        for (i in 1..n) {
            var last = 0
            for (j in 1..m) {
                prev = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) last + 1 else maxOf(dp[j], dp[j - 1])
                last = prev
            }
        }
        return dp[m]
    }
    private var path = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPhotoResultBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.rvResult.layoutManager = LinearLayoutManager(this)
        b.rvResult.adapter = adapter
        b.btnBack.setOnClickListener { finish() }
        b.btnAdjustCrop.setOnClickListener {
            if (path.isNotEmpty()) startActivity(Intent(this, PhotoCropActivity::class.java).putExtra("path", path))
        }

        path = intent.getStringExtra("path").orEmpty()
        if (path.isEmpty()) { b.tvOcr.text = "没有图片"; return }

        lifecycleScope.launch {
            val src = withContext(Dispatchers.IO) { OcrSearch.readBitmap(path) }
            if (src == null) { b.tvOcr.text = "图片读取失败"; return@launch }
            b.ivPhotoImage.setImageBitmap(src)
            b.tvOcr.text = "识别中…"
            val (text, rows) = withContext(Dispatchers.IO) { OcrSearch.run(this@PhotoResultActivity, src, 20) }

            // 【模型级】云端 VLM 直接给出每道题的归一化框（对齐同类产品：框来自题目识别模型）
            // 未开云端/取不到时，回退到本地 det 行框 + 规则切分
            val ai = withContext(Dispatchers.IO) {
                com.weizh.tiku.cloud.CloudVlm.structure(this@PhotoResultActivity, src)
            }
            // 注意：ai 在"云端未开启/请求失败"时是 null，这里必须判空——
            // 之前写成 ai!! 会在没配 API Key 的机器上直接 NPE 崩溃
            val boxes: List<Pair<android.graphics.RectF, String>> =
                if (!ai.isNullOrEmpty() && ai.all { it.box != null }) {
                    ai.map { it.box!! to it.question }
                } else {
                val lines = withContext(Dispatchers.IO) {
                    com.weizh.tiku.ocr.OcrClient.recognize(this@PhotoResultActivity, src)
                }
                if (lines.isNullOrEmpty()) emptyList()
                else QuestionRegion.pickQuestions(lines, src.width, src.height)
            }
            if (boxes.isNotEmpty()) {
                boxTexts = boxes.map { it.second }
                b.boxOverlay.bind(b.ivPhotoImage, src.width, src.height)
                b.boxOverlay.setRects(boxes.map { it.first })
                b.boxOverlay.setAdjustedFlags(BooleanArray(boxes.size))
                b.boxOverlay.setOnBoxEditListener(object : com.weizh.tiku.ui.widget.view.QuestionBoxOverlayView.OnBoxEditListener {
                    override fun onBoxTap(index: Int) { scrollToBox(index) }
                    override fun onBoxEditConfirmed(index: Int, normRect: android.graphics.RectF) {
                        toast("已更新第 ${index + 1} 个框")
                    }
                    override fun onBoxEditCancelled(index: Int) {}
                })
                b.tvOcr.text = "识别到 ${boxes.size} 道题 · 点框定位，长按框可微调"
            }
            b.tvOcr.text = if (text.isEmpty()) "没识别到文字，点右上角「调整裁剪图」重新框选" else "识别：$text"
            adapter.submit(rows)
            if (rows.isEmpty() && text.isNotEmpty()) {
                b.tvOcr.text = "命中片段：$text　·　本地题库没有匹配到这道题"
            }
        }
    }
}

/** 采样判断位图是否接近全黑（相机没出图时常见）。每 16 像素取一个点，看最大通道值。 */
/** 本地命中最低覆盖率（照同类产品 PhotoResultActivity 的 LOCAL_MATCH_MIN_RATIO = 0.6） */
private const val LOCAL_MATCH_MIN_RATIO = 0.6

/**
 * 本地命中要求的最短公共片段。同类产品硬编码 8，但很短的题干（判断题等）
 * 永远达不到 8，所以短查询按 60% 放宽，下限 3。
 */
private fun localMatchMinLcs(queryLen: Int): Int =
    minOf(8, maxOf(3, (queryLen * LOCAL_MATCH_MIN_RATIO).toInt()))

private fun isNearlyBlack(bmp: Bitmap): Boolean {
    val w = bmp.width
    val h = bmp.height
    if (w <= 0 || h <= 0) return true
    var maxV = 0
    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            val p = bmp.getPixel(x, y)
            val v = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            if (v > maxV) maxV = v
            if (maxV > 24) return false
            x += 16
        }
        y += 16
    }
    return true
}

package com.weizh.tiku.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.ArrayDeque

/**
 * 端侧 OCR：ONNX Runtime + PP-OCRv4（PaddleOCR 官方权重）
 *   det = ch_PP-OCRv4_det_infer   文本检测（DB）
 *   rec = ch_PP-OCRv4_rec_infer   文本识别（CTC）
 *
 * 说明：DB 后处理做了工程化简化 —— 用连通域 + 轴对齐外接框（而非 minAreaRect + pyclipper 多边形外扩），
 * 对本题场景（题目文字基本水平、结构化）足够；后面对倾斜文本效果会打折。
 */
object OcrEngine {

    private const val TAG = "WeiTikuOcr"

    // det
    private const val DET_LIMIT_SIDE = 960
    private const val DET_THRESH = 0.3f
    private const val DET_MIN_AREA = 4

    // rec
    private const val REC_HEIGHT = 48
    private const val REC_MAX_WIDTH = 1600

    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var det: OrtSession? = null
    @Volatile private var rec: OrtSession? = null
    @Volatile private var keys: List<String> = emptyList()
    @Volatile private var ready = false
    @Volatile private var loading = false
    private val lock = Any()

    val isReady: Boolean get() = ready

    /** 首次调用会把 assets 里的模型拷到私有目录（避免每次读压缩流） */
    fun ensureLoaded(ctx: Context): Boolean {
        if (ready) return true
        synchronized(lock) {
            if (ready) return true
            if (loading) return false
            loading = true
            try {
                val dir = File(ctx.filesDir, "ocr").apply { mkdirs() }
                val detFile = File(dir, "det.onnx")
                val recFile = File(dir, "rec.onnx")
                if (!detFile.exists() || detFile.length() < 1000) copyAsset(ctx, "ocr/det.onnx", detFile)
                if (!recFile.exists() || recFile.length() < 1000) copyAsset(ctx, "ocr/rec.onnx", recFile)
                keys = ctx.assets.open("ocr/keys.txt").bufferedReader(Charsets.UTF_8).readLines()
                    .map { it.trimEnd('\r', '\n') }
                    .filter { it.isNotEmpty() }
                val e = OrtEnvironment.getEnvironment()
                val opt = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                det = e.createSession(detFile.absolutePath, opt)
                rec = e.createSession(recFile.absolutePath, opt)
                env = e
                ready = true
                Log.i(TAG, "OCR ready: keys=${keys.size}")
            } catch (t: Throwable) {
                Log.e(TAG, "OCR load failed", t)
                ready = false
            } finally {
                loading = false
            }
            return ready
        }
    }

    private fun copyAsset(ctx: Context, name: String, dst: File) {
        ctx.assets.open(name).use { ins -> FileOutputStream(dst).use { outs -> ins.copyTo(outs, 1 shl 16) } }
    }

    // ---------------- 对外主入口 ----------------

    /** 整张图识别，返回按阅读顺序拼接的文本 */
    fun recognizeAll(bmp: Bitmap): String {
        if (!ready) return ""
        val boxes = detect(bmp)
        if (boxes.isEmpty()) return ""
        val sb = StringBuilder()
        for (b in boxes) {
            val line = recognizeLine(bmp, b)
            if (line.isNotBlank()) sb.append(line).append(' ')
        }
        return sb.toString().trim()
    }

    // ---------------- det ----------------

    /** 文本检测：返回原图坐标系下的文本框（已按阅读顺序排序） */
    fun detect(bmp: Bitmap): List<RectF> {
        val session = det ?: return emptyList()
        val e = env ?: return emptyList()

        // 缩放
        var scale = 1f
        var w = bmp.width
        var h = bmp.height
        val maxSide = maxOf(w, h)
        if (maxSide > DET_LIMIT_SIDE) {
            scale = DET_LIMIT_SIDE.toFloat() / maxSide
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }
        // 对齐到 32 的倍数
        val rw = maxOf(32, ((w + 31) / 32) * 32)
        val rh = maxOf(32, ((h + 31) / 32) * 32)

        val scaled = Bitmap.createScaledBitmap(bmp, rw, rh, true)
        val input = FloatArray(3 * rw * rh)
        val pixels = IntArray(rw * rh)
        scaled.getPixels(pixels, 0, rw, 0, 0, rw, rh)
        val area = rw * rh
        for (i in 0 until area) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            input[i] = (r - MEAN[0]) / STD[0]
            input[area + i] = (g - MEAN[1]) / STD[1]
            input[2 * area + i] = (b - MEAN[2]) / STD[2]
        }
        if (scaled != bmp) scaled.recycle()

        val shape = longArrayOf(1, 3, rh.toLong(), rw.toLong())
        val tensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(input), shape)
        val map: FloatArray
        var mapW = rw
        var mapH = rh
        try {
            session.run(mapOf("x" to tensor)).use { out ->
                val o = out[0]
                val arr = o.value
                @Suppress("UNCHECKED_CAST")
                val data = (arr as Array<Array<Array<FloatArray>>>)[0][0]
                mapH = data.size
                mapW = data[0].size
                map = FloatArray(mapH * mapW)
                for (y in 0 until mapH) {
                    val row = data[y]
                    System.arraycopy(row, 0, map, y * mapW, mapW)
                }
            }
        } finally {
            tensor.close()
        }

        // 连通域 → 轴对齐框
        val visited = BooleanArray(mapH * mapW)
        val boxes = ArrayList<RectF>()
        val q = ArrayDeque<Int>()
        val sx = bmp.width.toFloat() / mapW
        val sy = bmp.height.toFloat() / mapH
        for (start in 0 until mapH * mapW) {
            if (visited[start] || map[start] < DET_THRESH) continue
            var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1; var cnt = 0
            q.clear(); q.add(start); visited[start] = true
            while (q.isNotEmpty()) {
                val idx = q.poll()
                val x = idx % mapW
                val y = idx / mapW
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                cnt++
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= mapW || ny >= mapH) continue
                    val ni = ny * mapW + nx
                    if (!visited[ni] && map[ni] >= DET_THRESH) { visited[ni] = true; q.add(ni) }
                }
            }
            if (cnt < DET_MIN_AREA) continue
            val pad = 2f
            var l = (minX * sx) - pad
            var t = (minY * sy) - pad
            var r = ((maxX + 1) * sx) + pad
            var b = ((maxY + 1) * sy) + pad
            l = l.coerceAtLeast(0f); t = t.coerceAtLeast(0f)
            r = r.coerceAtMost(bmp.width.toFloat()); b = b.coerceAtMost(bmp.height.toFloat())
            if (r - l < 8 || b - t < 12) continue   // 太小的框识别出来基本都是噪声
            boxes.add(RectF(l, t, r, b))
        }

        // 同一行的框合并（纵向重叠 > 60%，横向间距近）
        val merged = ArrayList<RectF>()
        val sorted = boxes.sortedWith(compareBy({ it.top }, { it.left }))
        for (bx in sorted) {
            val hit = merged.firstOrNull { m ->
                val overlap = minOf(m.bottom, bx.bottom) - maxOf(m.top, bx.top)
                val minH = minOf(m.height(), bx.height())
                overlap > minH * 0.6f && (bx.left - m.right) < minH * 1.5f && (m.left - bx.right) < minH * 1.5f
            }
            if (hit == null) merged.add(RectF(bx)) else hit.union(bx)
        }
        merged.sortWith(compareBy({ it.top }, { it.left }))
        Log.i(TAG, "det boxes=${boxes.size} merged=${merged.size}")
        return merged
    }

    // ---------------- rec ----------------

    /** 识别单个文本框 */
    fun recognizeLine(bmp: Bitmap, box: RectF): String {
        val session = rec ?: return ""
        val e = env ?: return ""
        // 外扩白边（PaddleOCR 的识别输入是带留白的文本行，贴边裁会掉精度）
        val pad = 3
        val x = (box.left.toInt() - pad).coerceAtLeast(0)
        val y = (box.top.toInt() - pad).coerceAtLeast(0)
        val r = (box.right.toInt() + pad).coerceAtMost(bmp.width)
        val btm = (box.bottom.toInt() + pad).coerceAtMost(bmp.height)
        val w = r - x
        val h = btm - y
        if (w < 4 || h < 4) return ""
        val crop = Bitmap.createBitmap(bmp, x, y, w, h)

        // 关键：保持长宽比，不做横向压缩（压缩会让 B→3、首→自 这类错误暴增）
        val rw = Math.round(REC_HEIGHT * w.toFloat() / h).coerceIn(16, REC_MAX_WIDTH)
        val scaled = Bitmap.createScaledBitmap(crop, rw, REC_HEIGHT, true)
        if (scaled != crop) crop.recycle()

        val area = rw * REC_HEIGHT
        val input = FloatArray(3 * area)
        val pixels = IntArray(area)
        scaled.getPixels(pixels, 0, rw, 0, 0, rw, REC_HEIGHT)
        scaled.recycle()
        for (i in 0 until area) {
            val p = pixels[i]
            input[i] = (((p shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
            input[area + i] = (((p shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
            input[2 * area + i] = ((p and 0xFF) / 255f - 0.5f) / 0.5f
        }

        val shape = longArrayOf(1, 3, REC_HEIGHT.toLong(), rw.toLong())
        val tensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(input), shape)
        val probs: Array<FloatArray>
        try {
            session.run(mapOf("x" to tensor)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val data = (out[0].value as Array<Array<FloatArray>>)[0]   // [T, C]
                probs = data
            }
        } finally {
            tensor.close()
        }
        return ctcDecode(probs)
    }

    /** CTC 贪心解码：0 = blank，最后一维 = 空格，其余映射到字典 */
    private fun ctcDecode(probs: Array<FloatArray>): String {
        val sb = StringBuilder()
        var prev = -1
        val dictSize = keys.size
        for (t in probs.indices) {
            val row = probs[t]
            var best = 0
            var bestV = row[0]
            for (c in 1 until row.size) {
                if (row[c] > bestV) { bestV = row[c]; best = c }
            }
            if (best != 0 && best != prev) {
                when {
                    best - 1 < dictSize -> sb.append(keys[best - 1])
                    best == dictSize + 1 -> sb.append(' ')
                }
            }
            prev = best
        }
        return sb.toString()
    }
}

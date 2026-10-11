package com.weizh.tiku.ocr

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Pure JVM PP-PicoDet decoder. Matches RapidLayout's pp/post_process.py. */
object LayoutDecoder {
    const val WIDTH = 608
    const val HEIGHT = 800
    val labels = listOf("text", "title", "figure", "figure_caption", "table",
        "table_caption", "header", "footer", "reference", "equation")
    private val strides = intArrayOf(8, 16, 32, 64)

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float,
                   val score: Float, val classId: Int)

    fun decode(scores: List<Array<FloatArray>>, distances: List<Array<FloatArray>>,
               originalWidth: Int, originalHeight: Int,
               threshold: Float = 0.4f, nmsThreshold: Float = 0.5f): List<Box> {
        require(originalWidth > 0 && originalHeight > 0)
        require(scores.size == 4 && distances.size == 4)
        val byClass = List(labels.size) { ArrayList<Box>() }
        for (level in strides.indices) {
            val stride = strides[level]
            // np.arange(input_size / stride) includes the last partial cell.
            val rows = (HEIGHT + stride - 1) / stride
            val cols = (WIDTH + stride - 1) / stride
            val ss = scores[level]
            val dd = distances[level]
            require(ss.size == rows * cols && dd.size == ss.size) { "Invalid layout level $level" }
            require(ss.all { it.size == labels.size } && dd.all { it.size == 32 })
            val candidates = ss.indices.sortedByDescending { ss[it].maxOrNull() ?: 0f }.take(1000)
            for (anchor in candidates) {
                if (ss[anchor].none { it.isFinite() && it > threshold }) continue
                val raw = dd[anchor]
                if (raw.any { !it.isFinite() }) continue
                val d = FloatArray(4) { side ->
                    val base = side * 8
                    var peak = raw[base]
                    for (k in 1..7) peak = max(peak, raw[base + k])
                    var sum = 0.0
                    var weighted = 0.0
                    for (k in 0..7) {
                        val p = exp((raw[base + k] - peak).toDouble())
                        sum += p
                        weighted += p * k
                    }
                    (weighted / sum * stride).toFloat()
                }
                val cx = (anchor % cols + 0.5f) * stride
                val cy = (anchor / cols + 0.5f) * stride
                // NMS is performed on unclipped model-space boxes, as in the reference.
                for (c in labels.indices) {
                    val confidence = ss[anchor][c]
                    if (confidence.isFinite() && confidence > threshold) {
                        byClass[c].add(Box(cx - d[0], cy - d[1], cx + d[2], cy + d[3], confidence, c))
                    }
                }
            }
        }
        val result = ArrayList<Box>()
        for (group in byClass) {
            val kept = ArrayList<Box>()
            // Reference hard_nms has candidate_size=200 and top_k=100 per class.
            for (candidate in group.sortedByDescending { it.score }.take(200)) {
                if (kept.none { iou(it, candidate) > nmsThreshold }) {
                    kept.add(candidate)
                    if (kept.size == 100) break
                }
            }
            for (b in kept) {
                result.add(b.copy(
                    left = b.left.coerceIn(0f, WIDTH.toFloat()) * originalWidth / WIDTH,
                    top = b.top.coerceIn(0f, HEIGHT.toFloat()) * originalHeight / HEIGHT,
                    right = b.right.coerceIn(0f, WIDTH.toFloat()) * originalWidth / WIDTH,
                    bottom = b.bottom.coerceIn(0f, HEIGHT.toFloat()) * originalHeight / HEIGHT
                ))
            }
        }
        return result
    }

    fun iou(a: Box, b: Box): Float {
        val intersection = max(0f, min(a.right, b.right) - max(a.left, b.left)) *
            max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val aa = max(0f, a.right - a.left) * max(0f, a.bottom - a.top)
        val ba = max(0f, b.right - b.left) * max(0f, b.bottom - b.top)
        return intersection / (aa + ba - intersection + 1e-5f)
    }
}

package com.weizh.tiku.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import java.nio.FloatBuffer

/** CDLA document regions, not question boxes. Uses RapidLayout's PP preprocessing. */
object LayoutEngine {
    private const val ASSET = "layout/layout_cdla.onnx"
    val CLASS_NAMES = LayoutDecoder.labels
    data class Region(val rect: RectF, val score: Float, val classId: Int, val label: String)
    @Volatile private var session: OrtSession? = null
    val isReady: Boolean get() = session != null
    private val environment by lazy { OrtEnvironment.getEnvironment() }

    @Synchronized
    fun ensureLoaded(ctx: Context): Boolean {
        if (session != null) return true
        return try {
            // Read the packaged model directly: no stale private-directory copy after an upgrade.
            val model = ctx.assets.open(ASSET).use { it.readBytes() }
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                session = environment.createSession(model, options)
            }
            true
        } catch (e: Exception) {
            Log.e("WeiTikuLayout", "Model loading failed", e)
            false
        }
    }

    @Synchronized
    fun detect(bitmap: Bitmap): List<Region> {
        val s = checkNotNull(session) { "Layout model not ready" }
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0)
        val width = LayoutDecoder.WIDTH
        val height = LayoutDecoder.HEIGHT
        // PP uses direct resize, BGR channel order, ImageNet mean/std; NOT YOLO letterbox.
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        try {
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        val area = width * height
        val data = FloatArray(3 * area)
        for (i in pixels.indices) {
            val p = pixels[i]
            data[i] = ((p and 255) / 255f - 0.485f) / 0.229f
            data[area + i] = (((p shr 8) and 255) / 255f - 0.456f) / 0.224f
            data[2 * area + i] = (((p shr 16) and 255) / 255f - 0.406f) / 0.225f
        }
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(data),
            longArrayOf(1, 3, height.toLong(), width.toLong())).use { tensor ->
            s.run(mapOf("image" to tensor)).use { out ->
                require(out.size() == 8) { "Unexpected CDLA output count: ${out.size()}" }
                @Suppress("UNCHECKED_CAST")
                fun rows(index: Int) = (out[index].value as Array<Array<FloatArray>>)[0]
                LayoutDecoder.decode((0..3).map { rows(it) }, (4..7).map { rows(it) },
                    bitmap.width, bitmap.height).map {
                    Region(RectF(it.left, it.top, it.right, it.bottom), it.score, it.classId,
                        CLASS_NAMES[it.classId])
                }
            }
        }
    }
}

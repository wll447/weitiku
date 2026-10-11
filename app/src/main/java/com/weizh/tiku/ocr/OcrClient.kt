package com.weizh.tiku.ocr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 主进程侧的 OCR 客户端（照原版 OcrManager）：绑定 `:ocr` 进程的服务，
 * 把位图落盘成临时文件传过去，收回"文本 + 框"。
 * 拿不到服务时返回 null，调用方回退到进程内 OCR（保证不会因为隔离进程挂了就搜不了题）。
 */
object OcrClient {

    private const val TAG = "WeiTikuOcrClient"
    private const val BIND_TIMEOUT_MS = 4000L

    data class Line(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

    data class LayoutRegion(
        val rect: android.graphics.RectF,
        val score: Float,
        val classId: Int
    )

    @Volatile private var remote: IOcrIsolate? = null
    @Volatile private var connected = false
    @Volatile private var binding = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = IOcrIsolate.Stub.asInterface(service)
            connected = remote != null
            binding = false
            Log.i(TAG, ":ocr 服务已连接")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            connected = false
            Log.w(TAG, ":ocr 服务断连（子进程可能崩溃）")
        }
    }

    private fun bind(ctx: Context): IOcrIsolate? {
        remote?.let { if (connected) return it }
        if (!binding) {
            binding = true
            val ok = runCatching {
                ctx.applicationContext.bindService(
                    Intent(ctx.applicationContext, OcrIsolateService::class.java), conn, Context.BIND_AUTO_CREATE
                )
            }.getOrDefault(false)
            if (!ok) { binding = false; return null }
        }
        val start = System.currentTimeMillis()
        while (!connected && System.currentTimeMillis() - start < BIND_TIMEOUT_MS) {
            Thread.sleep(30)
        }
        return remote
    }

    /** 识别一张位图。返回 null 表示隔离进程不可用，调用方应回退到进程内 OCR */
    fun recognize(ctx: Context, bmp: Bitmap): List<Line>? {
        val r = bind(ctx) ?: return null
        val f = File(ctx.cacheDir, "ocr_isolate_in.png")
        return try {
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val raw = r.recognizeFile(f.absolutePath) ?: return null
            if (raw.isBlank()) return emptyList()
            raw.trim().split('\n').mapNotNull { row ->
                val parts = row.split('\t')
                if (parts.size < 2) return@mapNotNull null
                val c = parts[1].split(',')
                if (c.size < 4) return@mapNotNull null
                runCatching {
                    Line(parts[0], c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), c[3].toFloat())
                }.getOrNull()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "recognize failed", t)
            null
        } finally {
            runCatching { f.delete() }
        }
    }

    /** 只做文字检测（实时框题用）：返回图像坐标下的文字框；不可用返回 null */
    fun detect(ctx: Context, bmp: Bitmap): List<android.graphics.RectF>? {
        val r = bind(ctx) ?: return null
        val f = File(ctx.cacheDir, "ocr_detect_in.png")
        return try {
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            val raw = r.detectFile(f.absolutePath) ?: return null
            if (raw.isBlank()) return emptyList()
            raw.trim().split('\n').mapNotNull { row ->
                val c = row.split(',')
                if (c.size < 4) return@mapNotNull null
                runCatching {
                    android.graphics.RectF(
                        c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), c[3].toFloat()
                    )
                }.getOrNull()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "detect failed", t); null
        } finally {
            runCatching { f.delete() }
        }
    }

    /** 版面区域检测。不可用返回 null，空列表表示模型成功但没有区域。 */
    fun detectLayout(ctx: Context, bmp: Bitmap): List<LayoutRegion>? {
        val r = bind(ctx) ?: return null
        // Unique files prevent overlapping crop/live requests overwriting one another.
        val f = File.createTempFile("layout_", ".png", ctx.cacheDir)
        val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
        val input = if (scale < 1f) Bitmap.createScaledBitmap(bmp,
            (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        val sx = bmp.width.toFloat() / input.width
        val sy = bmp.height.toFloat() / input.height
        return try {
            FileOutputStream(f).use { check(input.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val raw = r.detectLayoutFile(f.absolutePath) ?: return null
            if (raw.isBlank()) return emptyList()
            raw.trim().split('\n').mapNotNull { row ->
                val c = row.split(',')
                if (c.size < 6) return@mapNotNull null
                runCatching {
                    LayoutRegion(
                        android.graphics.RectF(c[0].toFloat() * sx, c[1].toFloat() * sy,
                            c[2].toFloat() * sx, c[3].toFloat() * sy),
                        c[4].toFloat(), c[5].toInt()
                    )
                }.getOrNull()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "layout detect failed", t); null
        } finally {
            if (input !== bmp) input.recycle()
            runCatching { f.delete() }
        }
    }

    /** 预热 :ocr 进程（避免第一次搜题卡 1~2 秒） */
    fun warmUp(ctx: Context) {
        Thread { runCatching { bind(ctx)?.warmUp() } }.start()
    }
}

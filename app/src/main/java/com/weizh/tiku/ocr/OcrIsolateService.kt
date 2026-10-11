package com.weizh.tiku.ocr

import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.IBinder
import android.util.Log

/**
 * 跑在 `:ocr` 独立进程里的 OCR 服务（照原版 :ocr 隔离进程的设计）。
 * 主进程只通过 AIDL 传文件路径、收文本，模型和内存压力都留在这个进程里。
 */
class OcrIsolateService : Service() {

    private val binder = object : IOcrIsolate.Stub() {
        override fun warmUp() {
            runCatching { OcrEngine.ensureLoaded(this@OcrIsolateService) }
                .onSuccess { Log.i(TAG, "warmUp done, ready=${OcrEngine.isReady}") }
                .onFailure { Log.e(TAG, "warmUp failed", it) }
        }

        override fun isReady(): Boolean = OcrEngine.isReady

        override fun detectFile(path: String): String {
            OcrEngine.ensureLoaded(this@OcrIsolateService)
            if (!OcrEngine.isReady) return ""
            val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return ""
            return try {
                val sb = StringBuilder()
                for (b in OcrEngine.detect(bmp)) {
                    sb.append(b.left.toInt()).append(',').append(b.top.toInt()).append(',')
                        .append(b.right.toInt()).append(',').append(b.bottom.toInt()).append('\n')
                }
                sb.toString()
            } catch (t: Throwable) {
                Log.e(TAG, "detect failed", t); ""
            } finally {
                bmp.recycle()
            }
        }

        override fun recognizeFile(path: String): String {
            OcrEngine.ensureLoaded(this@OcrIsolateService)
            if (!OcrEngine.isReady) return ""
            val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return ""
            return try {
                val boxes = OcrEngine.detect(bmp)
                val sb = StringBuilder()
                for (b in boxes) {
                    val line = OcrEngine.recognizeLine(bmp, b)
                    if (line.isNotBlank()) {
                        sb.append(line.trim()).append('\t')
                            .append(b.left.toInt()).append(',').append(b.top.toInt()).append(',')
                            .append(b.right.toInt()).append(',').append(b.bottom.toInt())
                            .append('\n')
                    }
                }
                sb.toString()
            } catch (t: Throwable) {
                Log.e(TAG, "recognize failed", t)
                ""
            } finally {
                bmp.recycle()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        // 进程一起来就预热，避免第一次识别卡顿
        Thread { runCatching { OcrEngine.ensureLoaded(this) } }.start()
    }

    companion object {
        private const val TAG = "WeiTikuOcrIsolate"
    }
}

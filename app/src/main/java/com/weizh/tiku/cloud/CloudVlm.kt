package com.weizh.tiku.cloud

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端 VLM 兜底（智谱 GLM-4V-Flash，官方免费）。
 *
 * 用途：本地 PP-OCRv4 + 本地题库都失败时，把整页图发给视觉模型，要它**直接输出结构化题目**，
 * 补上两个缺口：① 结构理解（切题）② 题库覆盖（本地没有的题也能给答案）。
 *
 * 设计要点：
 *  - **只在本地失败时调用**（调用量降一个数量级，免费额度也够）
 *  - API Key 存 SharedPreferences，不硬编码进 APK
 *  - 图片长边压到 1024 + JPEG 80，控制 token 与上传耗时
 *  - 断网/超时/解析失败一律返回 null，静默退回本地结果，不影响主流程
 */
object CloudVlm {

    private const val TAG = "WeiTikuCloud"
    private const val PREFS = "weitiku_cloud"
    private const val KEY_API = "api_key"
    private const val KEY_ENABLED = "enabled"
    private const val URL_CHAT = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
    private const val MODEL = "glm-4v-flash"
    private const val MAX_SIDE = 1024
    private const val TIMEOUT_MS = 25000

    /** 云端返回的一道题。box = 归一化坐标 [l,t,r,b]（0..1），这就是同类产品那条链里
     *  由题目识别模型给出的 normRect；explain = AI 解析。 */
    data class AiQuestion(
        val question: String,
        val options: List<String>,
        val answer: String,
        val box: android.graphics.RectF? = null,
        val explain: String = ""
    )

    fun apiKey(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API, "").orEmpty()

    fun setApiKey(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_API, key.trim()).apply()
    }

    /** 开关：默认关，用户填了 Key 且在设置里打开才启用 */
    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false) && apiKey(ctx).isNotBlank()

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    private val PROMPT = """
        这是一张试题图片。请提取图中所有题目，只输出 JSON，不要任何解释。
        格式：
        {"questions":[{"question":"题干原文","options":["选项1内容","选项2内容"],"answer":"答案","box":[0.10,0.32,0.90,0.45],"explain":"简要解析"}]}
        规则：
        - question 只写题干，不要带题号、不要带选项
        - options 按 A/B/C/D 顺序，只写选项内容，不要带 "A." 前缀；判断题写 ["正确","错误"]
        - answer 用大写字母（多选如 "AB"）；判断题用 "A"（正确）或 "B"（错误）；若图里没有答案则填空字符串
        - box 是该题在【整张图】中的位置，归一化到 0~1 的 [左,上,右,下]，必须尽量贴合题干+选项的范围
        - explain 用一两句话说明为什么选这个答案（不确定就留空字符串）
        - 若图中没有题目，返回 {"questions":[]}
    """.trimIndent()

    /**
     * 请求云端做结构化识别。
     * @return 解析出的题目列表；失败返回 null（调用方据此保持本地结果）
     */
    fun structure(ctx: Context, bmp: Bitmap): List<AiQuestion>? {
        if (!isEnabled(ctx)) return null
        val key = apiKey(ctx)
        if (key.isBlank()) return null

        val b64 = runCatching { encode(bmp) }.getOrNull() ?: return null
        val body = JSONObject().apply {
            put("model", MODEL)
            put("temperature", 0.1)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", PROMPT))
                    put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
                })
            }))
        }

        val text = runCatching { post(key, body.toString()) }.getOrElse {
            Log.w(TAG, "request failed: ${it.javaClass.simpleName}")
            return null
        } ?: return null

        return runCatching { parse(text) }.getOrElse {
            Log.w(TAG, "parse failed")
            null
        }
    }

    private fun encode(src: Bitmap): String {
        val w = src.width
        val h = src.height
        val scale = if (maxOf(w, h) > MAX_SIDE) MAX_SIDE.toFloat() / maxOf(w, h) else 1f
        val b = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
        } else src
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        if (b !== src) b.recycle()
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun post(key: String, json: String): String? {
        val conn = (URL(URL_CHAT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $key")   // 注意：不要把 key 打进日志
        }
        try {
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(json) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "http $code: ${resp.take(200)}")
                return null
            }
            val obj = JSONObject(resp)
            return obj.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
        } finally {
            conn.disconnect()
        }
    }

    /** 宽松解析：模型可能把 JSON 包在 ```json 或多余文字里 */
    private fun parse(content: String): List<AiQuestion> {
        val s = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val root = JSONObject(s.substring(start, end + 1))
        val arr = root.optJSONArray("questions") ?: return emptyList()
        val out = ArrayList<AiQuestion>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val q = o.optString("question").trim()
            if (q.isEmpty()) continue
            val opts = ArrayList<String>()
            o.optJSONArray("options")?.let { a ->
                for (j in 0 until a.length()) opts.add(a.optString(j).trim())
            }
            var box: android.graphics.RectF? = null
            o.optJSONArray("box")?.let { a ->
                if (a.length() >= 4) {
                    val v = FloatArray(4) { a.optDouble(it, 0.0).toFloat() }
                    if (v[2] > v[0] && v[3] > v[1]) box = android.graphics.RectF(v[0], v[1], v[2], v[3])
                }
            }
            out.add(AiQuestion(q, opts, o.optString("answer").trim(), box, o.optString("explain").trim()))
        }
        return out
    }

    /**
     * AI 解析（纯文本，不传图——练题页只需要题干+选项，省 token 也更快）。
     * @return 解析文本；未开启/失败返回 null
     */
    fun explain(ctx: Context, stem: String, options: List<String>, answer: String): String? {
        if (!isEnabled(ctx)) return null
        val key = apiKey(ctx)
        if (key.isBlank()) return null
        val opts = options.mapIndexed { i, o -> "${'A' + i}. $o" }.joinToString("\n")
        val ask = "题目：$stem\n$opts\n正确答案：$answer\n请用两三句话解释为什么选这个答案，直接给解析内容，不要重复题目。"
        val body = JSONObject().apply {
            put("model", MODEL)
            put("temperature", 0.3)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", ask)
            }))
        }
        val text = runCatching { post(key, body.toString()) }.getOrNull() ?: return null
        return runCatching {
            JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")?.trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}

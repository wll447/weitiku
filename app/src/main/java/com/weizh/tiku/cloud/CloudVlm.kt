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
 * 云端模型调用（厂商可自选）。
 *
 * 定位：**只在本地题库搜不到时用**。
 *   1) 识别出题干 → 先回本地题库找 → 有就用库里的答案
 *   2) 库里没有 → 由云端模型**直接作答**（不是只做识别）
 *
 * 厂商不写死：内置换国内几家厂商的 OpenAI 兼容接口，也可自定义 baseUrl + 模型名。
 * API Key 存 SharedPreferences，不进代码、不进 APK。
 */
object CloudVlm {

    private const val TAG = "WeiTikuCloud"
    private const val PREFS = "weitiku_cloud"
    private const val KEY_API = "api_key"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROVIDER = "provider_id"
    private const val KEY_MODEL = "model"
    private const val KEY_BASE = "base_url"

    private const val MAX_SIDE = 1024
    private const val TIMEOUT_MS = 40000

    /** 一个厂商预设（都是 OpenAI 兼容的 chat/completions 接口） */
    data class Provider(
        val id: String,
        val name: String,
        val base: String,
        val model: String,
        val vision: Boolean,
        val note: String = "",
        /** 该厂商常用的**视觉**模型（/models 接口通常只列文本模型，所以这里内置一份供点选） */
        val visionModels: List<String> = emptyList()
    )

    val PROVIDERS = listOf(
        Provider("zhipu", "智谱 GLM（有免费额度）", "https://open.bigmodel.cn/api/paas/v4", "glm-4.6v-flash", true, "免费视觉模型，实测可用", listOf("glm-4.6v-flash", "glm-4.6v", "glm-4.5v", "glm-4.1v-thinking-flash")),
        Provider("dashscope", "阿里 通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-vl-plus", true, "", listOf("qwen3-vl-plus", "qwen-vl-max", "qwen-vl-plus", "qwen-vl-max-latest", "qwen3-vl-flash")),
        Provider("ark", "字节 豆包（火山方舟）", "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-1-6-vision", true, "Ark 需先在控制台开通并建「接入点」，模型名填接入点 ID 或模型 ID", listOf("doubao-seed-1-6-vision", "doubao-1.5-vision-pro", "doubao-vision-pro-32k")),
        Provider("siliconflow", "硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-VL-72B-Instruct", true, "聚合多家开源模型", listOf("Qwen/Qwen3-VL-32B-Instruct", "Qwen/Qwen3-VL-30B-A3B-Instruct", "Qwen/Qwen2.5-VL-72B-Instruct", "Qwen/Qwen2.5-VL-32B-Instruct")),
        Provider("moonshot", "月之暗面 Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k-vision-preview", true, "", listOf("moonshot-v1-8k-vision-preview", "moonshot-v1-32k-vision-preview", "kimi-latest")),
        Provider("baidu", "百度 文心一言", "https://qianfan.baidubce.com/v2", "ernie-4.5-turbo-vl", true, "", listOf("ernie-4.5-turbo-vl", "ernie-4.5-vl-28b-a3b", "ernie-4.5-turbo-vl-preview")),
        Provider("stepfun", "阶跃星辰 Step", "https://api.stepfun.com/v1", "step-1o-turbo-vision", true, "", listOf("step-1o-turbo-vision", "step-1o-vision-32k")),
        Provider("deepseek", "DeepSeek（纯文本）", "https://api.deepseek.com", "deepseek-chat", false, "无视觉，拍照搜题用不了"),
        Provider("custom", "自定义（OpenAI 兼容）", "", "", true, "自己填 baseUrl 和模型名")
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun provider(ctx: Context): Provider {
        val id = sp(ctx).getString(KEY_PROVIDER, "zhipu")
        return PROVIDERS.firstOrNull { it.id == id } ?: PROVIDERS.first()
    }

    fun setProvider(ctx: Context, p: Provider) {
        sp(ctx).edit().putString(KEY_PROVIDER, p.id).apply()
    }

    /** 模型名：用户填过就用用户的，否则用厂商预设 */
    fun model(ctx: Context): String =
        sp(ctx).getString(KEY_MODEL, "").orEmpty().ifBlank { provider(ctx).model }

    fun setModel(ctx: Context, m: String) {
        sp(ctx).edit().putString(KEY_MODEL, m.trim()).apply()
    }

    /** 实际 base：自定义厂商时取用户填的 */
    fun base(ctx: Context): String =
        sp(ctx).getString(KEY_BASE, "").orEmpty().ifBlank { provider(ctx).base }

    fun setBase(ctx: Context, b: String) {
        sp(ctx).edit().putString(KEY_BASE, b.trim()).apply()
    }

    fun apiKey(ctx: Context): String = sp(ctx).getString(KEY_API, "").orEmpty()

    fun setApiKey(ctx: Context, key: String) {
        sp(ctx).edit().putString(KEY_API, key.trim()).apply()
    }

    fun isEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ENABLED, false) && apiKey(ctx).isNotBlank()

    fun setEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** 云端返回的一道题；box 是归一化 [l,t,r,b]（模型给的题目位置） */
    data class AiQuestion(
        val question: String,
        val options: List<String>,
        val answer: String,
        val box: android.graphics.RectF? = null,
        val explain: String = ""
    )

    private val PROMPT = """
        这是一张试题图片。请提取题目并作答，只输出 JSON，不要任何解释。
        格式：
        {"questions":[{"question":"题干原文","options":["选项1内容","选项2内容"],"answer":"答案","box":[0.10,0.32,0.90,0.45],"explain":"简要解析"}]}
        规则：
        - question 只写题干，不要带题号、不要带选项
        - options 按 A/B/C/D 顺序，只写选项内容，不要带 "A." 前缀；判断题写 ["正确","错误"]；没有选项就填空数组
        - answer 是这道题的答案本身：
          图里印着答案就照抄（选择题用大写字母，多选如 "AB"；判断题 "A"=正确、"B"=错误）；
          图里没有答案时（常态），必须由你根据题目内容作答，不要留空：
          选择/判断题给字母；填空题给要填的内容；问答题/简答题给简明答案（一两句）；
          只有连题目都读不出来时才填 "无法作答"
        - box 是该题在整张图中的位置，归一化到 0~1 的 [左,上,右,下]，尽量贴合题干+选项范围
        - explain 用一两句话说明依据
        - 若图中没有题目，返回 {"questions":[]}
    """.trimIndent()

    private fun endpoint(ctx: Context): String {
        val b = base(ctx).trimEnd('/')
        return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
    }

    /** 拍照搜题：图片 → 结构化题目 + 答案（含模型给的题目框） */
    fun structure(ctx: Context, bmp: Bitmap): List<AiQuestion>? {
        if (!isEnabled(ctx)) return null
        val key = apiKey(ctx)
        if (key.isBlank()) return null
        val b64 = runCatching { encode(bmp) }.getOrNull() ?: return null

        val content = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", PROMPT))
            put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
            )
        }
        val body = JSONObject().apply {
            put("model", model(ctx))
            put("temperature", 0.2)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        }
        val raw = runCatching { post(ctx, key, body.toString()) }.getOrElse {
            Log.w(TAG, "structure failed: ${it.javaClass.simpleName}")
            return null
        } ?: return null
        return runCatching { parse(raw) }.getOrElse { null }
    }

    /**
     * 只给题干（本地题库没有、或图里没选项）时，让模型**直接作答**。
     * 纯文本调用，不传图，更快更省。
     */
    fun answerQuestion(ctx: Context, stem: String, options: List<String>): AiQuestion? {
        if (!isEnabled(ctx)) return null
        val key = apiKey(ctx)
        if (key.isBlank()) return null
        val opts = if (options.isEmpty()) "（无选项）"
        else options.mapIndexed { i, o -> "${'A' + i}. $o" }.joinToString("\n")
        val ask = "题目：$stem\n$opts\n\n请作答。只输出 JSON：{\"answer\":\"答案\",\"explain\":\"简要解析\"}\n" +
            "选择/判断题 answer 给字母（多选如 \"AB\"；判断题 A=正确 B=错误）；填空/问答题 answer 给要填的内容或简明答案。"
        val body = JSONObject().apply {
            put("model", model(ctx))
            put("temperature", 0.2)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", ask)))
        }
        val raw = runCatching { post(ctx, key, body.toString()) }.getOrNull() ?: return null
        return runCatching {
            val s = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val i = s.indexOf('{'); val j = s.lastIndexOf('}')
            if (i < 0 || j <= i) return@runCatching null
            val o = JSONObject(s.substring(i, j + 1))
            AiQuestion(stem, options, o.optString("answer").trim(), null, o.optString("explain").trim())
        }.getOrNull()
    }

    /**
     * 拉取该厂商**真实的模型列表**（OpenAI 兼容的 GET {base}/models）。
     * 成功返回模型 id 列表；不支持该接口/未填 Key 返回 null。
     */
    fun listModels(ctx: Context): List<String>? {
        val key = apiKey(ctx)
        if (key.isBlank()) return null
        val url = base(ctx).trimEnd('/') + "/models"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 25000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            val code = conn.responseCode
            val s = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "models http $code: ${s.take(150)}")
                return null
            }
            val arr = JSONObject(s).optJSONArray("data") ?: return null
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id")?.trim().orEmpty()
                if (id.isNotEmpty()) out.add(id)
            }
            out.sorted()
        } catch (e: Throwable) {
            Log.w(TAG, "models failed: ${e.javaClass.simpleName}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /** 练题页 AI 解析（纯文本） */
    fun explain(ctx: Context, stem: String, options: List<String>, answer: String): String? {
        if (!isEnabled(ctx)) return null
        val key = apiKey(ctx)
        if (key.isBlank()) return null
        val opts = options.mapIndexed { i, o -> "${'A' + i}. $o" }.joinToString("\n")
        val ask = "题目：$stem\n$opts\n正确答案：$answer\n请用两三句话解释为什么选这个答案，直接给解析内容。"
        val body = JSONObject().apply {
            put("model", model(ctx))
            put("temperature", 0.3)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", ask)))
        }
        val raw = runCatching { post(ctx, key, body.toString()) }.getOrNull() ?: return null
        return raw.takeIf { it.isNotBlank() }
    }

    // ---------- 内部 ----------

    private fun encode(src: Bitmap): String {
        val w = src.width; val h = src.height
        val scale = if (maxOf(w, h) > MAX_SIDE) MAX_SIDE.toFloat() / maxOf(w, h) else 1f
        val b = if (scale < 1f)
            Bitmap.createScaledBitmap(src, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
        else src
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 82, bos)
        if (b !== src) b.recycle()
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun post(ctx: Context, key: String, json: String): String? {
        val conn = (URL(endpoint(ctx)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $key")   // 不要把 key 写进日志
        }
        try {
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(json) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "http $code: ${resp.take(180)}")
                return null
            }
            return chatText(resp)
        } finally {
            conn.disconnect()
        }
    }

    /** 取 chat/completions 的正文（兼容 content 为字符串或数组两种形状） */
    private fun chatText(resp: String): String? = runCatching {
        val msg = JSONObject(resp).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: return@runCatching null
        when (val c = msg.opt("content")) {
            is String -> c
            is JSONArray -> buildString {
                for (i in 0 until c.length()) append(c.optJSONObject(i)?.optString("text") ?: "")
            }
            else -> null
        }
    }.getOrNull()

    /** 宽松解析题目 JSON（模型可能包 ```json 或加解释文字） */
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
            o.optJSONArray("options")?.let { a -> for (j in 0 until a.length()) opts.add(a.optString(j).trim()) }
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
}

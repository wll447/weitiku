package com.weizh.tiku.ui.photo

import android.graphics.RectF
import com.weizh.tiku.ocr.OcrClient

/**
 * 从 OCR 结果里切出**每道题一个框**（对齐同类产品 QuestionBoxOverlayView 的多框设计）。
 *
 * 同类产品的框来自服务端的题目识别模型；本地用 det 的行框 + 规则切分达到同样效果：
 *   1) 按纵向相邻聚簇（行距 > 2 倍行高就断开）—— 这层天然把工具栏/页眉与正文分开
 *   2) 簇内按题号（1. / 1、/ (1) / 第1题）或【题型】起新题，选项行（A. B. …）归到当前题
 *   3) 输出每题**归一化**矩形 + 该题文本（供与结果卡联动）
 *
 * 判据用"最长行字数"而不是"总字数"：Word 工具栏那类一堆短标签总字数不少，
 * 但单行都很短；题干是长句。靠这个把工具栏排除在外。
 */
object QuestionRegion {

    private val RE_QNUM = Regex("^\\s*(?:第\\s*)?\\d{1,3}\\s*(?:题)?\\s*[.、,):：]")
    private val RE_QTAG = Regex("^\\s*[【\\[]\\s*(?:单项?选择|多项?选择|不定项|判断|填空|简答|问答|论述|案例)\\s*题?\\s*[】\\]]")
    private val RE_OPT = Regex("^\\s*[A-Ha-h]\\s*[.、,):：]")

    private fun len(s: String) = s.count { !it.isWhitespace() }

    /**
     * 实时取景用：只有框、没有文字（只跑了检测没跑识别）。
     * 没有文字就没法用"最长行"判据，改用几何：按纵向相邻聚簇，取**面积最大**的那簇。
     */
    fun pickFromBoxes(boxes: List<RectF>, imgW: Int, imgH: Int): RectF? {
        val bs = boxes.filter { it.width() > 2 && it.height() > 2 }.sortedBy { it.top }
        if (bs.isEmpty() || imgW <= 0 || imgH <= 0) return null
        val hs = bs.map { it.height() }.sorted()
        val medianH = hs[hs.size / 2]

        val clusters = ArrayList<MutableList<RectF>>()
        var cur = ArrayList<RectF>()
        for (b in bs) {
            if (cur.isEmpty()) { cur.add(b); continue }
            if (b.top - cur.last().bottom <= medianH * 1.5f) cur.add(b)
            else { clusters.add(cur); cur = ArrayList() }
        }
        if (cur.isNotEmpty()) clusters.add(cur)

        val best = clusters.maxByOrNull { c ->
            val l = c.minOf { it.left }; val t = c.minOf { it.top }
            val r = c.maxOf { it.right }; val b = c.maxOf { it.bottom }
            (r - l) * (b - t)
        } ?: return null
        var l = best.minOf { it.left }; var t = best.minOf { it.top }
        var r = best.maxOf { it.right }; var b = best.maxOf { it.bottom }
        val padX = (r - l) * 0.05f + 6f
        val padY = medianH * 0.6f
        return RectF(
            (l - padX).coerceAtLeast(0f) / imgW, (t - padY).coerceAtLeast(0f) / imgH,
            (r + padX).coerceAtMost(imgW.toFloat()) / imgW, (b + padY).coerceAtMost(imgH.toFloat()) / imgH
        )
    }

    /** 兼容旧调用：只返回最像题目的一簇 */
    fun pick(lines: List<OcrClient.Line>, imgW: Int, imgH: Int): RectF? {
        val q = pickQuestions(lines, imgW, imgH)
        if (q.isEmpty()) return null
        val best = q.maxByOrNull { it.second.count { c -> !c.isWhitespace() } } ?: return null
        return best.first
    }

    /** 每道题一个框。返回 (归一化矩形, 题目文本)，按纵向顺序 */
    fun pickQuestions(
        lines: List<OcrClient.Line>, imgW: Int, imgH: Int
    ): List<Pair<RectF, String>> {
        val ls = lines.filter { it.text.isNotBlank() }.sortedBy { it.top }
        if (ls.isEmpty() || imgW <= 0 || imgH <= 0) return emptyList()

        val heights = ls.map { (it.bottom - it.top).coerceAtLeast(1f) }.sorted()
        val medianH = heights[heights.size / 2]

        // 1) 纵向聚簇
        val clusters = ArrayList<MutableList<OcrClient.Line>>()
        var cur = ArrayList<OcrClient.Line>()
        for (l in ls) {
            if (cur.isEmpty()) { cur.add(l); continue }
            if (l.top - cur.last().bottom <= medianH * 2f) cur.add(l)
            else { clusters.add(cur); cur = ArrayList() }
        }
        if (cur.isNotEmpty()) clusters.add(cur)

        val out = ArrayList<Pair<RectF, String>>()
        for (c in clusters) {
            // 簇里如果没有一条"长行"（>=8 字），判定为工具栏/页眉，整簇丢掉
            if ((c.maxOfOrNull { len(it.text) } ?: 0) < 8) continue

            // 2) 簇内按题号切题
            val groups = ArrayList<MutableList<OcrClient.Line>>()
            var g = ArrayList<OcrClient.Line>()
            for (l in c) {
                val t = l.text.trim()
                val isNewQ = RE_QNUM.containsMatchIn(t) || RE_QTAG.containsMatchIn(t)
                if (isNewQ && g.isNotEmpty()) { groups.add(g); g = ArrayList() }
                g.add(l)
            }
            if (g.isNotEmpty()) groups.add(g)

            for (grp in groups) {
                val text = grp.joinToString("") { it.text.trim() }
                if (len(text) < 10) continue
                var l = grp.minOf { it.left }
                var t = grp.minOf { it.top }
                var r = grp.maxOf { it.right }
                var b = grp.maxOf { it.bottom }
                val padX = (r - l) * 0.04f + 6f
                val padY = medianH * 0.5f
                l = (l - padX).coerceAtLeast(0f)
                t = (t - padY).coerceAtLeast(0f)
                r = (r + padX).coerceAtMost(imgW.toFloat())
                b = (b + padY).coerceAtMost(imgH.toFloat())
                out.add(RectF(l / imgW, t / imgH, r / imgW, b / imgH) to text)
            }
        }
        return out
    }
}

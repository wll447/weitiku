package com.weizh.tiku.ui.photo

import android.graphics.RectF
import com.weizh.tiku.ocr.OcrClient
import kotlin.math.max
import kotlin.math.min

/** Layout anchors + OCR lines form complete questions; all public rectangles are 0..1. */
object QuestionRegion {
    private val RE_QNUM = Regex("^\\s*(?:第\\s*)?\\d{1,3}\\s*(?:题)?\\s*[.、,):：]")
    private val RE_QTAG = Regex("^\\s*[【\\[]\\s*(?:单项选择|多项选择|单选|多选|不定项|判断|填空|简答|问答|论述|案例)\\s*题?\\s*[】\\]]")
    private val RE_OPT = Regex("^\\s*[A-Ha-h]\\s*[.、,):：]")
    private val contentClasses = setOf(0, 1, 4, 9)
    private fun startsQuestion(s: String) = RE_QNUM.containsMatchIn(s) || RE_QTAG.containsMatchIn(s)

    private fun normalize(r: RectF, w: Int, h: Int): RectF? {
        if (w <= 0 || h <= 0 || r.width() <= 0f || r.height() <= 0f) return null
        return RectF(r.left.coerceIn(0f, w.toFloat()) / w, r.top.coerceIn(0f, h.toFloat()) / h,
            r.right.coerceIn(0f, w.toFloat()) / w, r.bottom.coerceIn(0f, h.toFloat()) / h)
    }

    /** Live camera selection without recognition. Conservative union keeps missing option rows. */
    fun pickFromBoxes(boxes: List<RectF>, imgW: Int, imgH: Int): RectF? {
        val valid = boxes.filter { it.width() > 2f && it.height() > 2f }
        if (valid.isEmpty()) return null
        val union = RectF(valid.first())
        valid.drop(1).forEach { union.union(it) }
        val padX = union.width() * 0.04f + 6f
        val padY = valid.map { it.height() }.sorted()[valid.size / 2] * 0.6f
        union.inset(-padX, -padY)
        return normalize(union, imgW, imgH)
    }

    /** No OCR text: trust body/table/equation regions; title-only detections are
     * deliberately rejected because camera backgrounds can produce false title boxes. */
    fun pickFromLayout(regions: List<OcrClient.LayoutRegion>, imgW: Int, imgH: Int): RectF? {
        val strict = regions.filter { it.classId in setOf(0, 4, 9) && it.score >= 0.4f }
        val source = if (strict.isNotEmpty()) strict else regions.filter { it.classId == 0 && it.score >= 0.4f }
        return pickFromBoxes(source.map { it.rect }, imgW, imgH)
    }

    fun pickSingleQuestion(regions: List<OcrClient.LayoutRegion>, lines: List<OcrClient.Line>,
                           imgW: Int, imgH: Int): RectF? {
        val questions = pickQuestions(lines, imgW, imgH, regions)
        // Prefer the question nearest the center, rather than the largest page heading.
        return questions.minByOrNull {
            val dx = it.first.centerX() - 0.5f
            val dy = it.first.centerY() - 0.5f
            dx * dx + dy * dy
        }?.first ?: pickFromLayout(regions, imgW, imgH)
    }

    fun pick(lines: List<OcrClient.Line>, imgW: Int, imgH: Int): RectF? =
        pickSingleQuestion(emptyList(), lines, imgW, imgH)

    /** Each complete question includes wrapped stem lines and every following option row. */
    fun pickQuestions(lines: List<OcrClient.Line>, imgW: Int, imgH: Int,
                      regions: List<OcrClient.LayoutRegion> = emptyList()): List<Pair<RectF, String>> {
        if (imgW <= 0 || imgH <= 0) return emptyList()
        val valid = lines.filter { it.text.isNotBlank() && it.right > it.left && it.bottom > it.top }
        if (valid.isEmpty()) return emptyList()
        val medianH = valid.map { it.bottom - it.top }.sorted()[valid.size / 2]
        val content = regions.filter { it.classId in contentClasses && it.score >= 0.4f }
        val columns = ArrayList<MutableList<OcrClient.Line>>()
        // Wide title regions are not columns. Numbered stems also reveal columns when
        // CDLA returns too few anchors; left aligned wrapping then stays with its stem.
        val anchors = (content.map { it.rect } + valid.filter { startsQuestion(it.text.trim()) }
            .map { RectF(it.left, it.top, it.right, it.bottom) })
            .filter { it.width() < imgW * 0.62f }
        val left = anchors.filter { it.centerX() < imgW * 0.5f }
        val right = anchors.filter { it.centerX() >= imgW * 0.5f }
        val splitColumns = left.isNotEmpty() && right.isNotEmpty() &&
            left.maxOf { it.right } + medianH < right.minOf { it.left }
        if (splitColumns) {
            val split = (left.maxOf { it.right } + right.minOf { it.left }) / 2f
            columns.add(valid.filter { it.left < split }.toMutableList())
            columns.add(valid.filter { it.left >= split }.toMutableList())
        } else columns.add(valid.toMutableList())

        val result = ArrayList<Pair<RectF, String>>()
        for (column in columns) {
            val ordered = column.sortedWith(compareBy<OcrClient.Line> { it.top }.thenBy { it.left })
            val numbered = ordered.any { startsQuestion(it.text.trim()) }
            var current = ArrayList<OcrClient.Line>()
            fun flush() {
                if (current.isEmpty()) return
                val text = current.joinToString(" ") { it.text.trim() }
                if (text.count { !it.isWhitespace() } >= 6) {
                    val r = RectF(current.minOf { it.left }, current.minOf { it.top },
                        current.maxOf { it.right }, current.maxOf { it.bottom })
                    r.inset(-max(6f, r.width() * 0.03f), -medianH * 0.6f)
                    normalize(r, imgW, imgH)?.let { result.add(it to text) }
                }
                current = ArrayList()
            }
            for (line in ordered) {
                val text = line.text.trim()
                if (startsQuestion(text)) flush()
                // Page headings before the first numbered stem are excluded. Once a
                // question starts, large blank gaps do not split off its options.
                if (numbered && current.isEmpty() && !startsQuestion(text)) continue
                if (!numbered && current.isNotEmpty() && !RE_OPT.containsMatchIn(text) &&
                    line.top - current.maxOf { it.bottom } > medianH * 5f) flush()
                current.add(line)
            }
            flush()
        }
        return result
    }
}

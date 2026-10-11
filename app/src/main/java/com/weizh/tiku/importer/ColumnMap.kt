package com.weizh.tiku.importer

/** 一个工作表：名字 + 按行列铺开的单元格文本 */
data class Sheet(val name: String, val rows: List<List<String>>)

/**
 * 表头驱动的列角色识别。
 * 解决"列位置不固定"的问题：不同题库文件有 序号/题目/答案/选项A… 各种排布。
 */
class ColumnMap private constructor(
    val questionCol: Int,
    val answerCol: Int,
    val optionCols: List<Int>,
    val typeCol: Int,
    val explainCol: Int
) {
    companion object {
        private val RE_OPTION = Regex("^\\s*选项\\s*([A-Ha-h0-9])|^\\s*[A-Ha-h]\\s*$|^\\s*[A-Ha-h][\\.、:：]")
        private val RE_TYPE = Regex("类型|题型|类别|题目种类")
        private val RE_EXPLAIN = Regex("解析|解释|说明|分析")
        private val RE_ANSWER = Regex("答案")
        private val RE_QUESTION = Regex("题目|题干|问题|试题|课题")
        private val RE_IGNORE = Regex("序号|编号|序号|难度|章节|分类|备注|来源|分值|评分|id", RegexOption.IGNORE_CASE)

        private fun isOption(h: String) = !h.contains("答案") && RE_OPTION.containsMatchIn(h)

        /** 找到表头行（前 4 行内含 题目/题干/答案 关键字的那一行） */
        private fun findHeaderRow(rows: List<List<String>>): Int {
            for (i in 0 until minOf(4, rows.size)) {
                val joined = rows[i].joinToString("|")
                if (RE_QUESTION.containsMatchIn(joined) || RE_ANSWER.containsMatchIn(joined)) return i
            }
            return -1
        }

        fun detect(rows: List<List<String>>): Pair<ColumnMap, Int> {
            if (rows.isEmpty()) return fallback(rows) to 0
            val h = findHeaderRow(rows)
            if (h >= 0) {
                val header = rows[h]
                var q = -1; var a = -1; var t = -1; var e = -1
                val opts = ArrayList<Int>()
                header.forEachIndexed { idx, raw ->
                    val c = raw.trim()
                    if (c.isEmpty()) return@forEachIndexed
                    when {
                        isOption(c) -> opts.add(idx)
                        RE_TYPE.containsMatchIn(c) -> if (t < 0) t = idx
                        RE_EXPLAIN.containsMatchIn(c) -> if (e < 0) e = idx
                        RE_ANSWER.containsMatchIn(c) -> if (a < 0) a = idx
                        RE_QUESTION.containsMatchIn(c) -> if (q < 0) q = idx
                        RE_IGNORE.containsMatchIn(c) -> {}
                        else -> if (q < 0 && !RE_ANSWER.containsMatchIn(c)) q = idx
                    }
                }
                // 题干列兜底：表头里没有题干关键字时，取第一个非答案/非选项列
                if (q < 0) {
                    q = header.indices.firstOrNull { it != a && !opts.contains(it) && !RE_TYPE.containsMatchIn(header[it]) && !RE_EXPLAIN.containsMatchIn(header[it]) } ?: 0
                }
                if (a < 0) {
                    // 没有答案列：取题干之后的第一个非选项/非类型列
                    a = header.indices.firstOrNull { it != q && !opts.contains(it) && !RE_TYPE.containsMatchIn(header[it]) && !RE_EXPLAIN.containsMatchIn(header[it]) && it > q } ?: (q + 1)
                }
                return ColumnMap(q, a, opts, t, e) to (h + 1)
            }
            return fallback(rows) to 0
        }

        /** 无表头时的兜底：跳过纯序号列，题干取第一个文本列，答案取其后第一列 */
        private fun fallback(rows: List<List<String>>): ColumnMap {
            val sample = rows.take(20)
            val width = sample.maxOfOrNull { it.size } ?: 0
            val numericCols = (0 until width).filter { c ->
                sample.count { it.getOrNull(c)?.trim()?.matches(Regex("-?\\d+(\\.\\d+)?")) == true } >= sample.size * 6 / 10
            }
            val q = (0 until width).firstOrNull { it !in numericCols } ?: 0
            val a = q + 1
            val opts = ((a + 1) until width).toList()
            return ColumnMap(q, a, opts, -1, -1)
        }
    }
}

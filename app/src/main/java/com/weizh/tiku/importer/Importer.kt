package com.weizh.tiku.importer

import com.weizh.tiku.data.Db
import com.weizh.tiku.data.Question
import java.io.File
import java.io.InputStream

/**
 * 题库导入器。
 *
 * 列位置**不再写死**：按表头关键字自动识别
 *   题干列：题目内容 / 题目标题 / 题目 / 题干 / 问题 / 试题
 *   答案列：答案 / 正确答案 / 参考答案
 *   选项列：选项A…选项H / A…H / 选项1…选项9
 *   忽略列：序号 / 编号 / 难度 / 分类 / 备注 / 分值 …
 * 无表头时按"跳过纯数字序号列"的启发式兜底。
 *
 * 答案三种形态都支持：
 *   ① 选项字母（A / AB / ABCD）→ 选择题
 *   ② 对/错/正确/错误/√/×/T/F  → 判断题（自动补「正确 / 错误」两个选项）
 *   ③ 其它任意文本（一整段）      → 问答题/笔答题（无选项，答案即原文）
 *
 * 支持格式：.xlsx（自研轻量解析）/ .xls（jxl，老版 BIFF8）/ .csv / .txt / .tsv
 * 多工作表会全部合并进同一个题库。
 */
object Importer {

    data class Result(
        val ok: Int,
        val failed: Int,
        val questions: List<Question>,
        val errors: List<String>,
        val sheets: List<String> = emptyList()
    )

    private val TRUE_SET = setOf("T", "Y", "对", "正确", "√", "✔")
    private val FALSE_SET = setOf("F", "N", "错", "错误", "×")
    private val LETTERS = Regex("^[A-Ha-h]{1,8}$")

    fun importFile(name: String, input: InputStream, tmpDir: File): Result {
        val lower = name.lowercase()
        return try {
            when {
                lower.endsWith(".xls") -> fromSheets(XlsReader.read(input, tmpDir))
                lower.endsWith(".xlsx") -> fromSheets(XlsxReader.read(input))
                lower.endsWith(".csv") || lower.endsWith(".txt") || lower.endsWith(".tsv") ->
                    fromSheets(listOf(Sheet("文本", parseText(input))))
                else -> Result(0, 0, emptyList(), listOf("不支持的文件类型：$name（支持 .xlsx / .xls / .csv / .txt）"))
            }
        } catch (t: Throwable) {
            Result(0, 0, emptyList(), listOf("解析失败：${t.javaClass.simpleName} ${t.message ?: ""}"))
        }
    }

    // ---------------- 统一入口：Sheet 列表 → 题目 ----------------

    private fun fromSheets(sheets: List<Sheet>): Result {
        val all = ArrayList<Question>()
        val errors = ArrayList<String>()
        var failed = 0
        val usedSheets = ArrayList<String>()
        for (sheet in sheets) {
            if (sheet.rows.isEmpty()) continue
            val (map, dataStart) = ColumnMap.detect(sheet.rows)
            var got = 0
            for (i in dataStart until sheet.rows.size) {
                val q = buildQuestion(sheet.rows[i], map)
                if (q != null) { all.add(q); got++ } else {
                    val row = sheet.rows[i]
                    // 只统计"看起来有内容但不是表头"的行
                    if (row.any { it.isNotBlank() } && !isHeaderLike(row)) failed++
                }
            }
            if (got > 0) {
                usedSheets.add("${sheet.name}($got)")
            }
            if (got == 0) errors.add("工作表「${sheet.name}」没有解析到有效题目")
        }
        return Result(all.size, failed, all, errors, usedSheets)
    }

    private fun isHeaderLike(row: List<String>): Boolean {
        val j = row.joinToString("|")
        return j.contains("题目标题") || j.contains("题干") || j.contains("答案") && j.contains("选项")
    }

    // ---------------- 行 → 题目 ----------------

    fun buildQuestion(rowRaw: List<String>, map: ColumnMap): Question? {
        // ① 字符级归一：全角标点/字母数字 → 半角，空白折叠，去不可见字符
        val row = rowRaw.map { QuestionNormalizer.normChars(clean(it)) }
        // ② 题干：去「来源：」水印 + 剥掉题号/【单选题】等前缀
        val q = QuestionNormalizer.normStem(row.getOrNull(map.questionCol).orEmpty())
        if (q.isBlank()) return null
        val ansRaw = row.getOrNull(map.answerCol)?.trim().orEmpty()
        if (ansRaw.isBlank()) return null

        // ③ 选项：去掉 A. / A、/ A) 这类前缀，只留内容
        val opts = ArrayList<String>()
        for (c in map.optionCols) opts.add(QuestionNormalizer.normOption(row.getOrNull(c).orEmpty()))
        while (opts.isNotEmpty() && opts.last().isBlank()) opts.removeAt(opts.size - 1)

        val up = ansRaw.uppercase()
        // 答案写成数字序号（1/2/3 或 "1,2"）时也按选择题处理 → 1→A、2→B
        val isDigitAns = ansRaw.isNotEmpty() && ansRaw.all { it.isDigit() || it == ',' || it == '，' || it == '、' || it.isWhitespace() }
        return when {
            // ① 选项字母 / 数字序号 → 选择题
            LETTERS.matches(ansRaw) || isDigitAns -> {
                val ans = QuestionNormalizer.normAnswer(ansRaw, opts.size.coerceAtLeast(1))
                    .ifBlank { up.toSortedSet().joinToString("") }
                if (ans.isEmpty()) return null
                for (ch in ans) {
                    val idx = ch - 'A'
                    if (idx > 7) return null
                    while (opts.size <= idx) opts.add("")
                }
                Question(0, "", q, opts.joinToString(Db.DELIM_A), ans)
            }
            // ② 对错 → 判断题
            TRUE_SET.any { up == it || ansRaw == it } -> {
                val o = if (opts.isEmpty()) listOf("正确", "错误") else opts
                Question(0, "", q, o.joinToString(Db.DELIM_A), "A")
            }
            FALSE_SET.any { up == it || ansRaw == it } -> {
                val o = if (opts.isEmpty()) listOf("正确", "错误") else opts
                Question(0, "", q, o.joinToString(Db.DELIM_A), "B")
            }
            // ③ 其它 → 问答题，答案保留原文
            else -> Question(0, "", q, opts.joinToString(Db.DELIM_A), ansRaw)
        }
    }

    // ---------------- 文本（csv / txt / tsv） ----------------

    fun parseText(input: InputStream): List<List<String>> {
        val text = input.bufferedReader(Charsets.UTF_8).readText().removePrefix("\uFEFF")
        val lines = text.split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val delim = detectDelimiter(lines[0])
        return lines.map { splitCsvLine(it, delim) }
    }

    private fun detectDelimiter(line: String): Char {
        val t = line.count { it == '\t' }
        val c = line.count { it == ',' }
        return if (t >= c) '\t' else ','
    }

    private fun splitCsvLine(line: String, delim: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && !inQuote -> inQuote = true
                ch == '"' && inQuote -> {
                    if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ } else inQuote = false
                }
                ch == delim && !inQuote -> { out.add(sb.toString()); sb.setLength(0) }
                ch == '\r' -> {}
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    /** 全角转半角 + 清理不可见字符（对齐原版 toDBC/clean） */
    fun clean(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch == '\u3000' -> sb.append(' ')
                ch.code in 0xFF01..0xFF5E -> sb.append((ch.code - 0xFEE0).toChar())
                ch == '\u000C' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}

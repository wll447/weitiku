package com.weizh.tiku.importer

/**
 * 题库标准化加工。
 *
 * 目的：同一个题目在不同题库文件里的写法千奇百怪（全角/半角混用、带题号、带来源水印、
 * 选项带字母前缀、答案写成"正确/对/1"），不加工就会导致"明明有这道题却搜不到"。
 * 导入时统一加工一遍，检索时对 OCR 文本做同样的字符级归一，两边就能对上。
 *
 * 对应同类产品 SearchEngine.formatQuestion 去「来源：/出题：/推荐：」水印的思路，做了扩展。
 */
object QuestionNormalizer {

    /** 水印标签：同类产品的做法是截掉标签之后的内容 */
    private val WATERMARK_TAGS = listOf("来源", "出题", "推荐", "录入", "整理")

    private val RE_SPACES = Regex("[\\s\\u3000\\u00A0\\u200B\\uFEFF]+")
    /** 题干开头的"第N题"（后面可能不跟标点，如"第4题　全角..."） */
    private val RE_LEADING_QI = Regex("^\\s*第\\s*\\d{1,4}\\s*题\\s*")
    /** 题干开头的题号：1. 1、1) (1) 1- 等 */
    private val RE_LEADING_NUM = Regex("^\\s*(?:第\\s*)?\\d{1,4}\\s*(?:题)?\\s*[.、,):：\\-—]\\s*")
    private val RE_LEADING_PAREN = Regex("^\\s*[(\\[]\\s*\\d{1,4}\\s*[)\\]]\\s*")
    /**
     * 题干开头的题型标签：【单选题】【多选】等。
     * 注意要写成 单选/多选（不是"单项选择"），否则"单选题"匹配不上。
     */
    private val RE_TYPE_TAG = Regex(
        "^\\s*[【\\[]\\s*(?:单项选择|多项选择|不定项选择|不定项|单选|多选|判断|填空|简答|问答|论述|案例)\\s*题?\\s*[】\\]]\\s*"
    )
    /** 选项开头的字母前缀：A. A、A) A： */
    private val RE_OPT_PREFIX = Regex("^\\s*[A-Ha-h]\\s*[.、,):：]\\s*")

    /**
     * 字符级归一：全角标点→半角、全角字母数字→半角、空白折叠、去不可见字符。
     * 检索时对 OCR 文本调用同一个函数，保证两边一致。
     */
    fun normChars(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            val r = when (c) {
                '\u3000', '\u00A0' -> ' '
                '\u200B', '\uFEFF' -> continue
                '（' -> '('
                '）' -> ')'
                '［' -> '['
                '］' -> ']'
                '｛' -> '{'
                '｝' -> '}'
                '：' -> ':'
                '；' -> ';'
                '，' -> ','
                '．' -> '.'
                '？' -> '?'
                '！' -> '!'
                '、' -> ','
                '＝' -> '='
                '＋' -> '+'
                '－' -> '-'
                '～' -> '~'
                in 'Ａ'..'Ｚ' -> 'A' + (c - 'Ａ')
                in 'ａ'..'ｚ' -> 'a' + (c - 'ａ')
                in '０'..'９' -> '0' + (c - '０')
                else -> c
            }
            sb.append(r)
        }
        return RE_SPACES.replace(sb.toString(), " ").trim()
    }

    /** 截掉「来源：xxx」这类水印尾巴（同类产品 formatQuestion 的做法） */
    fun stripWatermark(raw: String): String {
        var s = raw
        for (tag in WATERMARK_TAGS) {
            for (colon in listOf(':', '：')) {
                val key = "$tag$colon"
                val i = s.indexOf(key)
                if (i > 0) s = s.substring(0, i)   // i>0：避免把整句以标签开头时截成空
            }
        }
        return s
    }

    /** 题干标准化：字符归一 + 去水印 + 剥题号/题型标签 + 去尾部多余符号 */
    fun normStem(raw: String): String {
        var s = normChars(stripWatermark(raw))
        // 可能同时有题型标签和题号，循环剥
        var changed = true
        var guard = 0
        while (changed && guard++ < 6) {
            changed = false
            for (re in listOf(RE_TYPE_TAG, RE_LEADING_PAREN, RE_LEADING_QI, RE_LEADING_NUM)) {
                val m = re.find(s)
                if (m != null && m.range.first == 0) {
                    s = s.substring(m.range.last + 1).trimStart()
                    changed = true
                }
            }
        }
        // 末尾的孤立括号/标点（OCR 常见）清掉
        s = s.trimEnd(' ', '.', ',', ';', ':', '-', '—', '_')
        return s
    }

    /** 选项标准化：去掉字母前缀，只留内容 */
    fun normOption(raw: String): String {
        var s = normChars(raw)
        s = RE_OPT_PREFIX.replace(s, "")
        return s.trim()
    }

    /**
     * 答案标准化。
     * - 有选项：统一成大写字母、去重、排序（"B,A" / "ba" / "A、B" → "AB"）；数字答案 1/2/3 → A/B/C；
     *   判断题的 正确/对/√/T → A，错误/错/×/F → B（配合 ["正确","错误"] 选项）
     * - 无选项（问答题）：保留原文，只做字符归一
     */
    fun normAnswer(raw: String, optionCount: Int): String {
        val s = normChars(raw)
        if (s.isEmpty()) return ""
        if (optionCount <= 0) return s

        val letters = sortedSetOf<Char>()
        for (c in s.uppercase()) {
            when {
                c in 'A'..'Z' -> if (c - 'A' < optionCount) letters.add(c)
                c in '0'..'9' -> {
                    val idx = c - '1'          // "1" → A
                    if (idx in 0 until optionCount) letters.add('A' + idx)
                }
                else -> {}
            }
        }
        if (letters.isNotEmpty()) return letters.joinToString("")

        // 没有字母/数字 → 判断题语义
        val t = s.replace(" ", "")
        val yes = listOf("正确", "对", "是", "√", "✓", "T", "TRUE", "Y", "YES")
        val no = listOf("错误", "错", "否", "×", "✗", "X", "F", "FALSE", "N", "NO")
        if (yes.any { t.equals(it, true) || (it.length > 1 && t.contains(it, true)) }) {
            return if (optionCount >= 1) "A" else s
        }
        if (no.any { t.equals(it, true) || (it.length > 1 && t.contains(it, true)) }) {
            return if (optionCount >= 2) "B" else s
        }
        return s
    }

    /** 整题加工 */
    fun normalize(
        stem: String, options: List<String>, answer: String
    ): Triple<String, List<String>, String> {
        val q = normStem(stem)
        // 选项：去前缀 → 去掉全空项 → 保留顺序；同时把题干里混进来的选项文本剔除
        val opts = options.map { normOption(it) }
        // 如果选项里混入了"答案：X"这种，剥掉
        val cleaned = opts.map { o ->
            val i = o.indexOf("答案")
            if (i == 0) "" else o
        }
        val cnt = cleaned.count { it.isNotBlank() }
        return Triple(q, cleaned, normAnswer(answer, cnt))
    }
}

package com.weizh.tiku.search

import com.weizh.tiku.data.Question
import kotlin.math.log10

/**
 * 本地题库检索引擎。算法对齐原版 SearchEngine：
 *   1) 倒排索引 term -> 记录集合
 *   2) 打分 TF-IDF：score += tf(id) * log10(N / (df + 1))，其中 tf(id) = 1 / 该记录词数
 *   3) 二次校验用最长公共子序列 LCS：lcsLen >= 8 且 lcsLen / queryLen >= 0.6 才算命中
 * 分词在中文上做了增强：单字 + 相邻二字（bigram），比原版纯单字更准。
 */
class SearchEngine {

    data class Hit(val question: Question, val score: Double)

    /** 扫描/展示用的一行结果：带 LCS 公共片段长度与覆盖率 */
    data class ScanHit(
        val question: Question,
        val score: Double,
        val lcs: Int,
        val ratio: Double
    )

    data class IndexMatch(
        var id: Long = -1,
        var lcsLen: Int = 0,
        var queryLen: Int = 0,
        var score: Double = -1.0
    ) {
        /**
         * 命中判定：比例 >= 0.6，且公共片段长度达标。
         * 短题干（如判断题「地球是圆的吗」只有 6 字）不可能达到绝对 8 字，
         * 因此门槛取 min(8, max(3, 题干长度/2))。
         */
        val accepted: Boolean
            get() {
                if (id < 0 || queryLen <= 0) return false
                val need = minOf(MIN_LCS_LEN, maxOf(3, queryLen / 2))
                return lcsLen >= need && lcsLen.toDouble() / queryLen >= MIN_RATIO
            }
    }

    companion object {
        const val MIN_LCS_LEN = 8
        const val MIN_RATIO = 0.6
        private const val MIN_SCORE = 0.001

        /** 扫描搜题的最小有效文本长度：低于这个长度说明没对准/识别失败，不该去检索 */
        const val MIN_SCAN_CHARS = 6

        /** 手输文本搜题的最小长度（用户是故意输入的） */
        const val MIN_TEXT_CHARS = 2

        // 选项排列表，照原版 P2/P3/P4（2 选项 2 种、3 选项 6 种、4 选项 24 种）
        private val P2 = arrayOf(intArrayOf(0, 1), intArrayOf(1, 0))
        private val P3 = arrayOf(
            intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2),
            intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0)
        )
        private val P4 = arrayOf(
            intArrayOf(0, 1, 2, 3), intArrayOf(0, 1, 3, 2), intArrayOf(0, 2, 1, 3), intArrayOf(0, 2, 3, 1),
            intArrayOf(0, 3, 1, 2), intArrayOf(0, 3, 2, 1), intArrayOf(1, 0, 2, 3), intArrayOf(1, 0, 3, 2),
            intArrayOf(1, 2, 0, 3), intArrayOf(1, 2, 3, 0), intArrayOf(1, 3, 0, 2), intArrayOf(1, 3, 2, 0),
            intArrayOf(2, 0, 1, 3), intArrayOf(2, 0, 3, 1), intArrayOf(2, 1, 0, 3), intArrayOf(2, 1, 3, 0),
            intArrayOf(2, 3, 0, 1), intArrayOf(2, 3, 1, 0), intArrayOf(3, 0, 1, 2), intArrayOf(3, 0, 2, 1),
            intArrayOf(3, 1, 0, 2), intArrayOf(3, 1, 2, 0), intArrayOf(3, 2, 0, 1), intArrayOf(3, 2, 1, 0)
        )

        @Volatile private var inst: SearchEngine? = null
        fun get(): SearchEngine = inst ?: synchronized(this) { inst ?: SearchEngine().also { inst = it } }

        /**
         * 检索侧归一。必须与 QuestionNormalizer 用同一套规则：
         * 入库时题库文本走 QuestionNormalizer，查询时 OCR 文本走这里——
         * 两边规则不一致（例如一边转全角冒号、一边转半角）就永远匹配不上。
         */
        fun formatString(raw: String): String =
            com.weizh.tiku.importer.QuestionNormalizer.normChars(
                com.weizh.tiku.importer.QuestionNormalizer.stripWatermark(raw)
            )

        /** 中文：单字 + bigram；英文数字：整词小写 */
        fun tokenize(text: String): List<String> {
            val out = ArrayList<String>(text.length * 2)
            val sb = StringBuilder()
            fun flush() {
                if (sb.length > 0) { out.add(sb.toString().lowercase()); sb.setLength(0) }
            }
            var prevCjk: Char? = null
            for (ch in text) {
                val isCjk = ch.code in 0x4E00..0x9FFF
                if (isCjk) {
                    flush()
                    out.add(ch.toString())
                    prevCjk?.let { out.add("$it$ch") }
                    prevCjk = ch
                } else if (ch.isLetterOrDigit()) {
                    sb.append(ch); prevCjk = null
                } else {
                    flush(); prevCjk = null
                }
            }
            flush()
            return out
        }

        /** 最长公共子序列长度 */
        fun lcsLength(a: String, b: String): Int {
            // 最长公共【连续子串】—— 必须连续！
            // 之前写成"最长公共子序列"（允许跳字）会导致严重误匹配：
            //   「架空配电线路的挡距不得大于()m，线间距离不得小于0.3m」
            //   会跟「…与牵引供电设备带电部分的距离不得少于()。」凑出 7 分的子序列，
            //   而两者的最长连续子串只有 4 字（"距离不得"）。同类产品的 lcs 就是连续子串，
            //   并在索引层规定 <= 5 字直接判为"没匹配到"。
            if (a.isEmpty() || b.isEmpty()) return 0
            var best = 0
            val m = b.length
            var prev = IntArray(m + 1)
            var cur = IntArray(m + 1)
            for (i in 1..a.length) {
                val ca = a[i - 1]
                for (j in 1..m) {
                    cur[j] = if (ca == b[j - 1]) prev[j - 1] + 1 else 0
                    if (cur[j] > best) best = cur[j]
                }
                val tmp = prev; prev = cur; cur = tmp
                java.util.Arrays.fill(cur, 0)
            }
            return best
        }

        /** 最长公共子序列文本（用于展示命中片段） */
        fun lcsString(a: String, b: String): String {
            val m = a.length; val n = b.length
            if (m == 0 || n == 0) return ""
            if (m * n > 4_000_000) return "" // 超长文本跳过回溯，避免卡顿
            val dp = Array(m + 1) { IntArray(n + 1) }
            for (i in 1..m) for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
            }
            val sb = StringBuilder()
            var i = m; var j = n
            while (i > 0 && j > 0) {
                when {
                    a[i - 1] == b[j - 1] -> { sb.append(a[i - 1]); i--; j-- }
                    dp[i - 1][j] >= dp[i][j - 1] -> i--
                    else -> j--
                }
            }
            return sb.reverse().toString()
        }
    }

    @Volatile private var ready = false
    private var dataSize = 0
    private val postings = HashMap<String, MutableSet<Long>>()
    private val tfs = HashMap<Long, Double>()
    private val byId = HashMap<Long, Question>()

    val isReady: Boolean get() = ready
    val size: Int get() = dataSize

    /** 建索引（在后台线程调用；完成后 isReady = true） */
    @Synchronized
    fun buildIndex(questions: List<Question>) {
        ready = false
        postings.clear(); tfs.clear(); byId.clear()
        var n = 0
        for (q in questions) {
            val id = q.id
            if (id <= 0) continue
            byId[id] = q
            val tokens = tokenize(formatString(q.question)) + tokenize(q.options.joinToString(""))
            val distinct = LinkedHashSet(tokens)
            tfs[id] = if (tokens.isEmpty()) 1.0 else 1.0 / tokens.size
            for (t in distinct) postings.getOrPut(t) { HashSet() }.add(id)
            n++
        }
        dataSize = n
        ready = true
    }

    /** 返回 topN 命中（已按 TF-IDF 排序，未做 LCS 校验） */
    fun query(text: String, topN: Int = 3): List<Hit> {
        if (!ready || text.isBlank()) return emptyList()
        val acc = HashMap<Long, Double>()
        for (t in tokenize(formatString(text))) {
            val set = postings[t] ?: continue
            if (set.isEmpty() || set.size == dataSize) continue
            val idf = log10(dataSize.toDouble() / (set.size + 1))
            if (idf <= 0) continue
            for (id in set) {
                val tf = tfs[id] ?: 1.0
                acc[id] = (acc[id] ?: 0.0) + tf * idf
            }
        }
        return acc.entries
            .filter { it.value > MIN_SCORE }
            .sortedByDescending { it.value }
            .asSequence()
            .mapNotNull { e -> byId[e.key]?.let { Hit(it, e.value) } }
            .take(topN)
            .toList()
    }

    /**
     * 扫描搜题的检索：先做**质量门禁**，再按 LCS 覆盖率排序。
     *
     * 为什么不能直接用 TF-IDF：OCR 只认出 1~2 个字时（没对准、屏幕反光、字太小），
     * 任何题目都能"命中"，而且 1÷1=100% 会显示成"匹配度 100%"，列表每帧重排 → 看起来乱跳。
     * 这里：文本 < MIN_SCAN_CHARS 直接拒绝；候选按公共片段长度排序并设下限。
     */
    fun searchForScan(text: String, topN: Int = 3, maxCandidates: Int = 60) =
        search(text, topN, maxCandidates, scan = true)

    /** 手输/粘贴的文本搜题：门槛低得多（用户是故意输的），短关键词要求整串出现 */
    fun searchText(text: String, topN: Int = 20, maxCandidates: Int = 200) =
        search(text, topN, maxCandidates, scan = false)

    private fun search(
        text: String, topN: Int, maxCandidates: Int, scan: Boolean
    ): Pair<List<ScanHit>, String> {
        val norm = formatString(text)
        val chars = norm.count { !it.isWhitespace() }
        val minChars = if (scan) MIN_SCAN_CHARS else MIN_TEXT_CHARS
        if (chars < minChars) {
            return emptyList<ScanHit>() to if (scan) "文字太少（$chars 字），继续对准题目"
            else "至少输入 $minChars 个字"
        }
        if (!ready) return emptyList<ScanHit>() to "题库索引未就绪"

        val candidates = query(norm, maxCandidates)
        if (candidates.isEmpty()) return emptyList<ScanHit>() to "题库中没有相似题目"

        // 扫描：要求覆盖 1/4 以上（防垃圾）；手输短关键词：要求整串命中
        val minLcs = if (scan) maxOf(4, norm.length / 4) else minOf(norm.length, 4)

        fun target(h: Hit) = formatString(h.question.question) + h.question.options.joinToString("")
        val scored = candidates.map { h ->
            val l = lcsLength(norm, target(h))
            ScanHit(h.question, h.score, l, if (norm.isEmpty()) 0.0 else l.toDouble() / norm.length)
        }.filter { it.lcs >= minLcs }
            .sortedWith(compareByDescending<ScanHit> { it.lcs }.thenByDescending { it.score })
            .take(topN)

        if (scored.isNotEmpty()) return scored to ""
        val best = candidates.maxOfOrNull { lcsLength(norm, target(it)) } ?: 0
        return emptyList<ScanHit>() to if (scan) "公共片段不足（最多 $best 字），继续对准题目"
        else "没有匹配到（最多公共片段 $best 字）"
    }

    // ---------- 选项乱序排列（照原版 allPerm） ----------

    /**
     * 同一个题在不同题库里选项顺序可能不一样（题库A: A.北京 B.上海；题库B: A.上海 B.北京，答案跟着变）。
     * 原版 allPerm 会把候选题的选项按 2/3/4 个选项的**所有排列**各算一遍 LCS，取最长的那个作为匹配度。
     * 这里只用于**算匹配**，展示时仍用题库自己的选项顺序和答案。
     */
    private fun bestLcsOverPermutations(norm: String, q: Question): Int {
        val base = formatString(q.question)
        val opts = q.options
        if (opts.isEmpty()) return lcsLength(norm, base)

        var best = lcsLength(norm, base + opts.joinToString(""))
        val perms = when (opts.size) {
            2 -> P2
            3 -> P3
            4 -> P4
            else -> return best
        }
        for (perm in perms) {
            val sb = StringBuilder(base)
            for (i in perm) sb.append(opts[i])
            val l = lcsLength(norm, sb.toString())
            if (l > best) best = l
        }
        return best
    }

    /**
     * 带选项排列的两阶段检索：先用原顺序快筛出 top 候选，再只对这些候选做全排列精算，
     * 避免对全部候选做 24 倍 LCS 计算。
     */
    fun searchWithPermutations(text: String, topN: Int = 10, maxCandidates: Int = 60): List<ScanHit> {
        val norm = formatString(text)
        if (norm.length < 2 || !ready) return emptyList()
        val cands = query(norm, maxCandidates)
        if (cands.isEmpty()) return emptyList()
        val pre = cands.map { h ->
            ScanHit(h.question, h.score, lcsLength(norm, target(h)), 0.0)
        }.sortedByDescending { it.lcs }.take(12)
        return pre.map { h ->
            val l = bestLcsOverPermutations(norm, h.question)
            h.copy(lcs = l, ratio = if (norm.isEmpty()) 0.0 else l.toDouble() / norm.length)
        }.sortedWith(compareByDescending<ScanHit> { it.lcs }.thenByDescending { it.score })
            .take(topN)
    }

    private fun target(h: Hit) = formatString(h.question.question) + h.question.options.joinToString("")

    /**
     * 扫描搜题用的精确判定：先 TF-IDF 取候选，再用 LCS 校验比例。
     * 返回 (题目, 匹配信息)；accepted=false 表示"本地未命中"。
     */
    fun bestMatch(text: String, candidateTop: Int = 5): Pair<Question?, IndexMatch> {
        val normalized = formatString(text)
        val match = IndexMatch(queryLen = normalized.length)
        if (!ready || normalized.isBlank()) return null to match

        val candidates = query(normalized, candidateTop)
        if (candidates.isEmpty()) return null to match

        var best: Question? = null
        var bestScore = -1.0
        var bestLcs = 0
        var bestId = -1L
        for (h in candidates) {
            val target = formatString(h.question.question) + h.question.options.joinToString("")
            val len = lcsLength(normalized, target)
            val score = h.score
            if (len > bestLcs || (len == bestLcs && score > bestScore)) {
                bestLcs = len; bestScore = score; best = h.question; bestId = h.question.id
            }
        }
        match.id = bestId; match.lcsLen = bestLcs; match.score = bestScore
        return if (match.accepted) best to match else null to match
    }
}

package com.weizh.tiku.data

import android.content.Context
import android.net.Uri
import com.weizh.tiku.importer.Importer
import com.weizh.tiku.search.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 题库仓库：导入 + 建索引 的统一入口 */
object Repo {

    data class ImportOutcome(
        val tikuName: String,
        val ok: Int,
        val failed: Int,
        val errors: List<String>,
        val sheets: List<String> = emptyList()
    )

    /** 从 uri 导入，自动创建题库并重建索引 */
    /**
     * 把已导入的题库重新加工一遍（标准化：去水印/剥题号/选项去前缀/答案规整）。
     * 用于本次改动之前导入的题库——它们的文本还没加工过，检索匹配率偏低。
     * @return 实际被改动的题目数
     */
    suspend fun renormalizeAll(ctx: Context): Int = withContext(Dispatchers.IO) {
        val db = Db.get(ctx)
        val all = db.allQuestions(200000)
        var changed = 0
        for (q in all) {
            val (stem, opts, ans) =
                com.weizh.tiku.importer.QuestionNormalizer.normalize(q.question, q.options, q.answer)
            val answers = opts.joinToString(Db.DELIM_A)
            if (stem != q.question || answers != q.answers || ans != q.answer) {
                db.updateQuestion(q.id, stem, answers, ans)
                changed++
            }
        }
        rebuildIndex(ctx)
        changed
    }

    suspend fun importFromUri(ctx: Context, uri: Uri, displayName: String): ImportOutcome =
        withContext(Dispatchers.IO) {
            val db = Db.get(ctx)
            val name = displayName.substringBeforeLast('.').ifBlank { "导入题库" }
            val tid = "MY" + System.currentTimeMillis().toString().takeLast(10)

            val result = ctx.contentResolver.openInputStream(uri)?.use { input ->
                Importer.importFile(displayName, input, ctx.cacheDir)
            } ?: return@withContext ImportOutcome(name, 0, 0, listOf("无法读取文件"))

            if (result.questions.isEmpty()) {
                return@withContext ImportOutcome(
                    name, 0, result.failed,
                    result.errors.ifEmpty { listOf("没有解析到题目，请检查列格式") },
                    result.sheets
                )
            }

            db.upsertTiku(Tiku(tid = tid, name = name))
            val inserted = db.insertQuestions(tid, result.questions)
            rebuildIndex(ctx)
            ImportOutcome(name, inserted, result.failed, result.errors, result.sheets)
        }

    /** 重建检索索引（导入后 / 手动触发） */
    suspend fun rebuildIndex(ctx: Context): Int = withContext(Dispatchers.IO) {
        val db = Db.get(ctx)
        val all = db.loadAllForIndex(null)
        SearchEngine.get().buildIndex(all)
        all.size
    }

    /** 搜索：返回带匹配度的结果 */
    suspend fun search(ctx: Context, text: String, topN: Int = 10): List<SearchEngine.Hit> =
        withContext(Dispatchers.IO) {
            val engine = SearchEngine.get()
            if (!engine.isReady) rebuildIndex(ctx)
            engine.query(text, topN)
        }

    suspend fun loadForPractice(ctx: Context, mode: String, tid: String?): List<Question> =
        withContext(Dispatchers.IO) {
            val db = Db.get(ctx)
            when (mode) {
                PracticeMode.SEQ -> if (tid.isNullOrBlank()) db.allQuestions() else db.questionsByTiku(tid)
                PracticeMode.RANDOM -> db.randomQuestions(if (tid.isNullOrBlank()) null else listOf(tid), 50)
                PracticeMode.WRONG -> db.wrongIds().mapNotNull { db.findById(it) }
                PracticeMode.FAV -> db.favIds().mapNotNull { db.findById(it) }
                else -> emptyList()
            }
        }

    object PracticeMode {
        const val SEQ = "seq"
        const val RANDOM = "random"
        const val WRONG = "wrong"
        const val FAV = "fav"
    }
}

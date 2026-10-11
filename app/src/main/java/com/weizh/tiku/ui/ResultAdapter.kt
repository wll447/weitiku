package com.weizh.tiku.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.weizh.tiku.databinding.ItemOcrQuestionBinding
import com.weizh.tiku.databinding.ItemSearchResultBinding
import com.weizh.tiku.search.SearchEngine

/**
 * 结果卡片适配器。两种形态照同类产品的两个布局：
 *   scanMode = true  → item_ocr_question（黑卡、300dp 宽、高度撑满、横向滑动）
 *   scanMode = false → item_search_result（白卡，结构照 item_search_tiku：标题/描述/200×40dp 按钮）
 */
class ResultAdapter(private val scanMode: Boolean = false) :
    RecyclerView.Adapter<ResultAdapter.VH>() {

    private val data = ArrayList<SearchEngine.ScanHit>()

    fun submit(list: List<SearchEngine.ScanHit>) {
        data.clear(); data.addAll(list); notifyDataSetChanged()
    }

    fun ids(): List<Long> = data.map { it.question.id }

    /** 供题目框联动：取某条结果的题目文本 */
    fun questionAt(pos: Int): String? = data.getOrNull(pos)?.question?.question

    /** 点结果卡 → 高亮对应题目框 */
    var onCardClick: ((Int) -> Unit)? = null

    class VH(val ocr: ItemOcrQuestionBinding?, val card: ItemSearchResultBinding?) :
        RecyclerView.ViewHolder((ocr?.root ?: card!!.root))

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val ctx = p.context
        return if (scanMode) {
            val b = ItemOcrQuestionBinding.inflate(LayoutInflater.from(ctx), p, false)
            b.root.layoutParams = RecyclerView.LayoutParams(dp(ctx, 300), ViewGroup.LayoutParams.MATCH_PARENT)
            VH(b, null)
        } else {
            val b = ItemSearchResultBinding.inflate(LayoutInflater.from(ctx), p, false)
            b.root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            VH(null, b)
        }
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val hit = data[pos]
        val q = hit.question
        val optionsText = q.options.mapIndexed { i, o ->
            if (i < 10 && o.isNotBlank()) "${'A' + i}. $o" else null
        }.filterNotNull().joinToString("\n")

        h.ocr?.let { b ->
            b.tvAnswer.text = answerLabel(q.answer, q.options)
            b.tvText.text = buildString {
                append(q.question)
                append("\n==========")
                if (optionsText.isNotEmpty()) append("\n").append(optionsText)
                else append("\n【参考答案】").append(q.answer)
            }
            b.tvMeta.text = if (q.tid == "ai") "来源：AI 云端结构化（本地题库没有这道题）"
            else "匹配度 ${(hit.ratio * 100).toInt()}% · 公共片段 ${hit.lcs} 字 · 得分 ${"%.2f".format(hit.score)}"
        }

        h.card?.let { b ->
            b.tvTitle.text = q.question
            // 云端兜底的答案里可能带着 "\n\n【AI 解析】xxx"，直接塞进答案胶囊会被裁掉，
            // 所以拆开：答案进胶囊，解析放到下面那一行（题目没选项时那行本来是空的）
            val rawAns = q.answer
            val mark = "\n\n【AI 解析】"
            val cut = rawAns.indexOf(mark)
            val ansOnly = if (cut >= 0) rawAns.substring(0, cut) else rawAns
            val explain = if (cut >= 0) rawAns.substring(cut + 1) else ""
            b.tvExam.text = "答案：" + answerLabel(ansOnly, q.options)
            b.tvDesc.text = buildString {
                if (optionsText.isNotEmpty()) append(optionsText)
                if (explain.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(explain)
                }
            }
        }
    }

    private fun answerLabel(answer: String, options: List<String>): String {
        val idx = if (answer.length == 1 && answer[0] in 'A'..'Z') answer[0] - 'A' else -1
        val t = if (idx >= 0) options.getOrNull(idx) else null
        return if (!t.isNullOrBlank()) "$answer-$t" else answer
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}

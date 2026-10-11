package com.weizh.tiku.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.weizh.tiku.data.Db
import com.weizh.tiku.data.Question
import com.weizh.tiku.data.Repo
import com.weizh.tiku.databinding.ActivityExerciseBinding
import com.weizh.tiku.databinding.ItemQuestionPageBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 练题页。对应同类产品 activity_exercise + item_question_page：
 * 一页一题（ViewPager2）+ 底部上一题/下一题 + 顶部题号进度。
 */
class ExerciseActivity : AppCompatActivity() {

    private lateinit var b: ActivityExerciseBinding
    private var questions: List<Question> = emptyList()
    private var adapter: QuestionAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityExerciseBinding.inflate(layoutInflater)
        setContentView(b.root)

        val tid = intent.getStringExtra("tid")
        val mode = intent.getStringExtra("mode") ?: Repo.PracticeMode.SEQ
        val plain = intent.getBooleanExtra("plain", false)
        val limit = intent.getIntExtra("limit", 0)
        val title = intent.getStringExtra("title")

        b.btnBack.setOnClickListener { finish() }
        b.spinKit.visibility = View.VISIBLE

        lifecycleScope.launch {
            var list = withContext(Dispatchers.IO) {
                if (plain) Db.get(this@ExerciseActivity).allQuestions()
                else Repo.loadForPractice(this@ExerciseActivity, mode, tid)
            }
            if (limit > 0 && list.size > limit) list = list.shuffled().take(limit)
            b.spinKit.visibility = View.GONE
            questions = list
            if (list.isEmpty()) {
                b.tvTitle.text = "这里还没有题目"
                b.tvProgress.text = "0/0"
                return@launch
            }
            b.tvTitle.text = title ?: when (mode) {
                Repo.PracticeMode.RANDOM -> "随机练习"
                Repo.PracticeMode.FAV -> "我的收藏"
                Repo.PracticeMode.WRONG -> "错题本"
                else -> "顺序练习"
            }
            adapter = QuestionAdapter(list)
            b.vpQuestion.adapter = adapter
            b.vpQuestion.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    b.tvProgress.text = "${position + 1}/${questions.size}"
                }
            })
            b.tvProgress.text = "1/${list.size}"
            b.btnPrev.setOnClickListener {
                val i = b.vpQuestion.currentItem
                if (i > 0) b.vpQuestion.currentItem = i - 1
            }
            b.btnNext.setOnClickListener {
                val i = b.vpQuestion.currentItem
                if (i < questions.size - 1) b.vpQuestion.currentItem = i + 1 else finish()
            }
        }
    }

    inner class QuestionAdapter(private val data: List<Question>) :
        RecyclerView.Adapter<QuestionAdapter.VH>() {

        inner class VH(val b: ItemQuestionPageBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val b = ItemQuestionPageBinding.inflate(LayoutInflater.from(p.context), p, false)
            return VH(b)
        }

        override fun getItemCount() = data.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val q = data[pos]
            h.b.exerciseview.bind(q)
            h.b.exerciseview.onAnswered = { correct ->
                val db = Db.get(this@ExerciseActivity)
                db.logAnswer(q.id, q.answer, correct)
                if (correct) db.removeWrong(q.id) else db.markWrong(q.id)
            }
            // AI 解析：点「本地题库暂无解析」去云端要一条（免费模型，纯文本调用）
            h.b.tvExplainAction.setOnClickListener {
                val act = this@ExerciseActivity
                if (!com.weizh.tiku.cloud.CloudVlm.isEnabled(act)) {
                    h.b.tvExplainAction.text = "未开启云端AI（我的-设置）"
                    return@setOnClickListener
                }
                h.b.tvExplainAction.text = "解析中…"
                lifecycleScope.launch {
                    val txt = withContext(Dispatchers.IO) {
                        com.weizh.tiku.cloud.CloudVlm.explain(act, q.question, q.options, q.answer)
                    }
                    if (txt.isNullOrBlank()) {
                        h.b.tvExplainAction.text = "解析失败，再试一次"
                    } else {
                        h.b.tvExplainAction.text = "AI 解析"
                        h.b.tvExplainContent.text = txt
                    }
                }
            }
            h.b.tvAheadQuestion.text = if (pos == data.size - 1) "已是最后一题" else "下一题"
            h.b.tvAheadQuestion.setOnClickListener {
                if (pos < data.size - 1) b.vpQuestion.currentItem = pos + 1
            }
        }
    }
}

package com.weizh.tiku.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.weizh.tiku.data.Db
import com.weizh.tiku.data.Question
import com.weizh.tiku.data.Repo
import com.weizh.tiku.databinding.ActivityPracticeBinding
import kotlinx.coroutines.launch

/** 答题页：顺序 / 随机 / 错题 / 收藏，四种模式共用 */
class PracticeActivity : AppCompatActivity() {

    private lateinit var b: ActivityPracticeBinding
    private val questions = ArrayList<Question>()
    private var index = 0
    private var selected = sortedSetOf<Char>()
    private var submitted = false

    private val db by lazy { Db.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPracticeBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnSubmit.setOnClickListener { submit() }
        b.btnNext.setOnClickListener { move(1) }
        b.btnPrev.setOnClickListener { move(-1) }
        b.tvFav.setOnClickListener { toggleFav() }

        val mode = intent.getStringExtra("mode") ?: Repo.PracticeMode.SEQ
        val tid = intent.getStringExtra("tid")
        b.tvQuestion.text = "加载中…"

        lifecycleScope.launch {
            val list = Repo.loadForPractice(this@PracticeActivity, mode, tid)
            questions.clear(); questions.addAll(list)
            if (questions.isEmpty()) {
                b.tvQuestion.text = when (mode) {
                    Repo.PracticeMode.WRONG -> "错题本是空的，先去练几题吧"
                    Repo.PracticeMode.FAV -> "还没有收藏的题目"
                    else -> "这个题库还没有题目"
                }
                b.btnSubmit.isEnabled = false
                b.btnNext.isEnabled = false
                b.btnPrev.isEnabled = false
                return@launch
            }
            index = 0
            render()
        }
    }

    // ---------------- 渲染 ----------------

    private fun render() {
        val q = questions[index]
        submitted = false
        selected.clear()

        b.tvProgress.text = "${index + 1} / ${questions.size}"
        b.tvQuestion.text = q.question
        b.tvFav.text = if (db.isFav(q.id)) "★ 已收藏" else "☆ 收藏"
        b.tvFeedback.visibility = View.GONE
        b.btnSubmit.isEnabled = true
        b.btnSubmit.text = if (q.options.isEmpty()) "显示答案" else "提交答案"

        b.optionContainer.removeAllViews()
        if (q.options.isEmpty()) {
            // 问答题 / 笔答题：没有选项，走"看题 → 显示答案 → 自评"的闪卡模式
            val tv = TextView(this).apply {
                text = "这道题是问答题/笔答题，点下方「显示答案」自评"
                textSize = 14f
                setTextColor(Color.parseColor("#888888"))
                setPadding(28, 26, 28, 26)
                background = getDrawable(com.weizh.tiku.R.drawable.bg_card)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            b.optionContainer.addView(tv)
            return
        }
        q.options.forEachIndexed { i, text ->
            if (i > 9) return@forEachIndexed
            val label = ('A' + i).toString()
            val tv = TextView(this).apply {
                this.text = "$label. $text"
                textSize = 15f
                setTextColor(Color.parseColor("#222222"))
                setPadding(28, 26, 28, 26)
                background = getDrawable(com.weizh.tiku.R.drawable.bg_option)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 18 }
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { if (!submitted) toggleOption(label[0], this) }
            }
            b.optionContainer.addView(tv)
        }
    }

    private fun toggleOption(c: Char, tv: TextView) {
        val multi = (questions[index].answer.length > 1)
        if (multi) {
            if (selected.contains(c)) selected.remove(c) else selected.add(c)
        } else {
            selected.clear(); selected.add(c)
        }
        refreshOptionStyles()
    }

    private fun refreshOptionStyles() {
        for (i in 0 until b.optionContainer.childCount) {
            val tv = b.optionContainer.getChildAt(i) as TextView
            val c = ('A' + i)
            tv.background = getDrawable(
                if (selected.contains(c)) com.weizh.tiku.R.drawable.bg_option_selected
                else com.weizh.tiku.R.drawable.bg_option
            )
        }
    }

    // ---------------- 答题 ----------------

    private fun submit() {
        val q = questions[index]

        // 问答题：直接显示参考答案（闪卡模式）
        if (q.options.isEmpty()) {
            submitted = true
            db.logAnswer(q.id, "review", true)
            b.tvFeedback.visibility = View.VISIBLE
            b.tvFeedback.setTextColor(Color.parseColor("#2FA84F"))
            b.tvFeedback.text = "参考答案：\n${q.answer}"
            b.btnSubmit.isEnabled = false
            b.btnSubmit.text = "已显示答案"
            return
        }

        if (selected.isEmpty()) { Toast.makeText(this, "先选一个答案", Toast.LENGTH_SHORT).show(); return }

        val chosen = selected.joinToString("")
        val correct = chosen == q.answer
        submitted = true

        // 选项着色：正确的绿、选错的红
        for (i in 0 until b.optionContainer.childCount) {
            val tv = b.optionContainer.getChildAt(i) as TextView
            val c = ('A' + i)
            val isRight = q.answer.contains(c)
            val isChosen = selected.contains(c)
            tv.background = getDrawable(
                when {
                    isRight -> com.weizh.tiku.R.drawable.bg_option_right
                    isChosen -> com.weizh.tiku.R.drawable.bg_option_wrong
                    else -> com.weizh.tiku.R.drawable.bg_option
                }
            )
        }

        db.logAnswer(q.id, chosen, correct)
        if (!correct) db.markWrong(q.id) else db.removeWrong(q.id)

        b.tvFeedback.visibility = View.VISIBLE
        if (correct) {
            b.tvFeedback.text = "✅ 回答正确\n正确答案：${q.answer}"
            b.tvFeedback.setTextColor(Color.parseColor("#2FA84F"))
        } else {
            b.tvFeedback.text = "❌ 回答错误\n你的答案：$chosen\n正确答案：${q.answer}" +
                "\n（已加入错题本）"
            b.tvFeedback.setTextColor(Color.parseColor("#E04B4B"))
        }
        b.btnSubmit.isEnabled = false
        b.btnSubmit.text = "已提交"
    }

    private fun move(delta: Int) {
        val n = index + delta
        if (n < 0) { Toast.makeText(this, "已经是第一题", Toast.LENGTH_SHORT).show(); return }
        if (n >= questions.size) {
            Toast.makeText(this, "已到最后一题", Toast.LENGTH_SHORT).show()
            return
        }
        index = n
        render()
        b.tvQuestion.post { b.tvQuestion.parent?.let { (it as? View)?.scrollTo(0, 0) } }
    }

    private fun toggleFav() {
        if (questions.isEmpty()) return
        val on = db.toggleFav(questions[index].id)
        b.tvFav.text = if (on) "★ 已收藏" else "☆ 收藏"
        Toast.makeText(this, if (on) "已收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
    }
}

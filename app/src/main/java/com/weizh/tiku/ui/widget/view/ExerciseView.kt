package com.weizh.tiku.ui.widget.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.weizh.tiku.R
import com.weizh.tiku.data.Question

/**
 * 答题控件 —— 照同类产品 ExerciseView（inflate layout_exercise_view + 每题若干个 layout_item_answer）。
 *
 * 行为对齐：
 *   - 顶部显示题型（单选题/多选题/判断题/简答题）
 *   - 右上「查看答案」开关：打开后直接显示答案与对错（对应 iv_see_answer_switch）
 *   - 选项行：选中/正确/错误三种底色；对/错图标显示在右侧（iv_status）
 *   - 多选题漏选时右侧显示「漏」（tv_status_lose）
 *   - 无选项的问答/笔答题：隐藏选项，出现「提交」按钮，点「显示答案」看参考答案
 */
class ExerciseView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private val mQuestionText: TextView
    private val mQuestionType: TextView
    private val mAnswers: LinearLayout
    private val mTvShowAnswer: TextView
    private val mIvSwitch: ImageView
    private val mTvManualSubmit: TextView

    private var question: Question? = null
    private var submitted = false
    private val selected = sortedSetOf<Char>()

    /** 是否处于"查看答案"模式（由活动页的开关控制） */
    var seeAnswerMode = false
        set(v) { field = v; if (question != null) render() }

    var onAnswered: ((correct: Boolean) -> Unit)? = null
    var onRequestNext: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.layout_exercise_view, this, true)
        mQuestionText = findViewById(R.id.question)
        mQuestionType = findViewById(R.id.tv_type)
        mAnswers = findViewById(R.id.answers)
        mTvShowAnswer = findViewById(R.id.tv_show_answer)
        mIvSwitch = findViewById(R.id.iv_see_answer_switch)
        mTvManualSubmit = findViewById(R.id.tv_manual_submit)

        mIvSwitch.setOnClickListener { seeAnswerMode = !seeAnswerMode }
        mTvShowAnswer.setOnClickListener { revealAnswer() }
        mTvManualSubmit.setOnClickListener { revealAnswer() }
    }

    fun bind(q: Question, indexLabel: String? = null) {
        question = q
        submitted = false
        selected.clear()
        render()
    }

    private fun typeLabel(q: Question): String = when {
        q.options.isEmpty() -> "简答题"
        q.answer.length > 1 -> "多选题"
        q.options.size == 2 && q.options[0] == "正确" -> "判断题"
        else -> "单选题"
    }

    private fun render() {
        val q = question ?: return
        mQuestionType.text = typeLabel(q)
        mQuestionText.text = q.question
        mIvSwitch.setImageResource(if (seeAnswerMode) R.drawable.ic_switch_on else R.drawable.ic_switch_off)

        // 问答/笔答题：无选项，直接给"显示答案"
        if (q.options.isEmpty()) {
            mAnswers.removeAllViews()
            mTvManualSubmit.visibility = GONE
            mTvShowAnswer.visibility = VISIBLE
            mTvShowAnswer.text = if (submitted) "参考答案：\n${q.answer}" else "显示答案"
            return
        }

        mTvManualSubmit.visibility = if (q.answer.length > 1) VISIBLE else GONE
        mTvShowAnswer.visibility = if (submitted || seeAnswerMode) VISIBLE else GONE
        if (submitted || seeAnswerMode) mTvShowAnswer.text = "正确答案：${q.answer}"

        mAnswers.removeAllViews()
        q.options.forEachIndexed { i, text ->
            if (i > 9 || text.isBlank()) return@forEachIndexed
            val row = LayoutInflater.from(context).inflate(R.layout.layout_item_answer, mAnswers, false)
            val tv = row.findViewById<TextView>(R.id.text)
            val iv = row.findViewById<ImageView>(R.id.iv_status)
            val lose = row.findViewById<TextView>(R.id.tv_status_lose)
            val letter = 'A' + i
            tv.text = "$letter. $text"
            iv.visibility = GONE
            lose.visibility = GONE

            val answered = submitted || seeAnswerMode
            val isRight = q.answer.contains(letter)
            val isChosen = selected.contains(letter)
            tv.setBackgroundResource(
                when {
                    answered && isRight -> R.drawable.btn_answerbg_right
                    answered && isChosen -> R.drawable.btn_answerbg_wrong
                    !answered && isChosen -> R.drawable.btn_answerbg_select
                    else -> R.drawable.btn_answerbg_normal
                }
            )
            if (answered) {
                if (isRight) {
                    iv.setImageResource(R.drawable.ic_status_right)
                    iv.visibility = VISIBLE
                } else if (isChosen) {
                    iv.setImageResource(R.drawable.ic_status_wrong)
                    iv.visibility = VISIBLE
                }
                // 多选漏选：正确答案里有、但没选 → 显示「漏」
                if (q.answer.length > 1 && isRight && !isChosen) {
                    lose.visibility = VISIBLE
                    iv.visibility = GONE
                }
            }

            row.setOnClickListener {
                if (submitted || seeAnswerMode) return@setOnClickListener
                if (q.answer.length > 1) {
                    if (selected.contains(letter)) selected.remove(letter) else selected.add(letter)
                } else {
                    selected.clear(); selected.add(letter)
                }
                render()
            }
            mAnswers.addView(row)
        }
    }

    private fun revealAnswer() {
        val q = question ?: return
        if (q.options.isEmpty()) {
            submitted = true
            mTvShowAnswer.text = "参考答案：\n${q.answer}"
            return
        }
        if (selected.isEmpty()) {
            // 没选就直接看答案 → 等同于查看答案模式
            seeAnswerMode = true
            submitted = true
            onAnswered?.invoke(false)
        } else {
            submitted = true
            submit()
        }
    }

    private fun submit() {
        val q = question ?: return
        val chosen = selected.joinToString("")
        val correct = chosen == q.answer
        submitted = true
        render()
        onAnswered?.invoke(correct)
    }
}

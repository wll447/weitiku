package com.weizh.tiku.data

/** 题库元信息（对齐原版 TikuRecord 结构） */
data class Tiku(
    val tid: String,
    var name: String,
    var desc: String = "",
    var curver: Int = 1,
    var num: Int = 0,
    var updated: Long = System.currentTimeMillis(),
    var isMine: Boolean = true
)

/** 题目（对齐原版 QuestionRecord：选项用 \t 拼接） */
data class Question(
    val id: Long = 0,
    val tid: String,
    val question: String,
    val answers: String,   // 选项，\t 分隔
    val answer: String     // 答案字母，如 "A" / "AB"
) {
    val options: List<String>
        get() = if (answers.isEmpty()) emptyList() else answers.split("\t")

    /** 组装展示文本：题干 + A. xx / B. xx */
    fun display(): String {
        val sb = StringBuilder(question)
        options.forEachIndexed { i, o ->
            if (i < 10 && o.isNotBlank()) sb.append("\n").append(('A' + i)).append(". ").append(o)
        }
        return sb.toString()
    }

    /** 与题干+答案拼接用于 LCS 比对（对齐原版 formatQuestion + answers 的做法） */
    fun matchText(): String = question + options.joinToString("")
}

/** 答题记录 */
data class AnswerLog(
    val id: Long = 0,
    val qid: Long,
    val chosen: String,
    val correct: Boolean,
    val time: Long = System.currentTimeMillis()
)

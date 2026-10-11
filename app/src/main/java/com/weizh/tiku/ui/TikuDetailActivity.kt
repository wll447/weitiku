package com.weizh.tiku.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.weizh.tiku.data.Db
import com.weizh.tiku.data.Repo
import com.weizh.tiku.databinding.ActivityTikuDetailBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 题库详情。布局照同类产品 activity_tiku_detail.xml：
 * 题库信息卡（标题/说明/来源·题目数量·导入时间）+ 练习入口卡（顺序/随机/模拟 + 错题/收藏/清空）+ 删除卡。
 */
class TikuDetailActivity : AppCompatActivity() {

    private lateinit var b: ActivityTikuDetailBinding
    private lateinit var tid: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTikuDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        tid = intent.getStringExtra("tid").orEmpty()
        val name = intent.getStringExtra("name").orEmpty()
        b.btnBack.setOnClickListener { finish() }

        val db = Db.get(this)
        val num = db.countQuestions(tid)
        val tiku = db.listTiku().firstOrNull { it.tid == tid }
        b.tvTikuTitle.text = name.ifBlank { tiku?.name ?: "题库" }
        b.tvTikuText.text = "本地题库，共 $num 道题。可顺序练习、随机练习或模拟考试；做错的题会自动进错题本。"
        b.tvQuestionsNum.text = num.toString()
        b.tvTikuSource.text = "本地导入"
        b.tvTikuLastTime.text = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            .format(Date(tiku?.updated ?: System.currentTimeMillis()))

        b.llExercise.setOnClickListener { play(Repo.PracticeMode.SEQ, "顺序练习") }
        b.llRandom.setOnClickListener { play(Repo.PracticeMode.RANDOM, "随机练习") }
        b.llExam.setOnClickListener { exam() }

        b.llWrongExercise.setOnClickListener {
            startActivity(Intent(this, ExerciseActivity::class.java)
                .putExtra("mode", Repo.PracticeMode.WRONG).putExtra("title", "错题练习"))
        }
        b.llStarExercise.setOnClickListener {
            startActivity(Intent(this, ExerciseActivity::class.java)
                .putExtra("mode", Repo.PracticeMode.FAV).putExtra("title", "收藏练习"))
        }
        b.llClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空记录")
                .setMessage("清空做题记录、错题本和收藏？题库本身不会被删除。")
                .setPositiveButton("清空") { _, _ ->
                    db.clearAnswerLog()
                    db.clearWrongAndFav()
                    Toast.makeText(this, "已清空做题记录", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        b.llDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("删除题库")
                .setMessage("确定删除「${b.tvTikuTitle.text}」及其 $num 道题？此操作不可恢复。")
                .setPositiveButton("删除") { _, _ ->
                    db.deleteTiku(tid)
                    lifecycleScope.launch { Repo.rebuildIndex(this@TikuDetailActivity) }
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun play(mode: String, title: String) {
        startActivity(Intent(this, ExerciseActivity::class.java)
            .putExtra("tid", tid).putExtra("mode", mode).putExtra("title", title))
    }

    /** 模拟考试：随机抽 20 题（本地没有试卷结构，按最常见做法抽题） */
    private fun exam() {
        startActivity(Intent(this, ExerciseActivity::class.java)
            .putExtra("tid", tid).putExtra("mode", Repo.PracticeMode.RANDOM)
            .putExtra("limit", 20).putExtra("title", "模拟考试"))
    }
}

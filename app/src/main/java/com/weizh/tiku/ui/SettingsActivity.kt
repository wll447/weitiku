package com.weizh.tiku.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.weizh.tiku.BuildConfig
import com.weizh.tiku.cloud.CloudVlm
import com.weizh.tiku.data.Db
import com.weizh.tiku.data.Repo
import com.weizh.tiku.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页。用共享样式（WeiTiku.Toolbar / Card / Row）搭的，跟其它二级页一致，
 * 不再是系统样式的弹窗列表。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.tvVersion.text = "魏题库 v${BuildConfig.VERSION_NAME}"

        b.llAi.setOnClickListener { cloudSettings() }
        b.llGuide.setOnClickListener {
            startActivity(Intent(this, ImportGuideActivity::class.java))
        }
        b.llNormalize.setOnClickListener {
            lifecycleScope.launch {
                val n = withContext(Dispatchers.IO) { Repo.renormalizeAll(this@SettingsActivity) }
                toast("已重新加工 $n 道题")
            }
        }
        b.llReindex.setOnClickListener {
            lifecycleScope.launch {
                val n = withContext(Dispatchers.IO) { Repo.rebuildIndex(this@SettingsActivity) }
                toast("索引已重建，共 $n 题")
            }
        }
        b.llClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空做题记录")
                .setMessage("清空做题流水、错题本和收藏？题库本身不受影响。")
                .setPositiveButton("清空") { _, _ ->
                    val db = Db.get(this)
                    db.clearAnswerLog()
                    db.clearWrongAndFav()
                    toast("已清空")
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        b.tvAiStatus.text = if (CloudVlm.isEnabled(this)) "已开启" else "未开启"
        b.tvAiStatus.setTextColor(
            if (CloudVlm.isEnabled(this)) getColor(com.weizh.tiku.R.color.brand_primary)
            else getColor(com.weizh.tiku.R.color.text_sub)
        )
    }

    /** 填云端 AI 的 API Key（只存本机，不进代码、不进 APK） */
    private fun cloudSettings() {
        val input = EditText(this).apply {
            hint = "粘贴 API Key"
            setText(CloudVlm.apiKey(this@SettingsActivity))
            setSingleLine()
            setPadding(48, 24, 48, 24)
        }
        val on = CloudVlm.isEnabled(this)
        AlertDialog.Builder(this)
            .setTitle(if (on) "云端 AI 兜底（已开启）" else "云端 AI 兜底（未开启）")
            .setMessage(
                "本地 OCR + 本地题库都搜不到时，把整页图发给视觉模型，让它直接输出结构化题目和答案；" +
                    "练题页的「AI 解析」也用它。\n\n" +
                    "· 默认接智谱 GLM-4V-Flash，官方有免费额度\n" +
                    "· Key 只存本机，不会打进 APK\n" +
                    "· 题目图片会上传到该服务商，涉密内容别用\n" +
                    "· 本地能搜到时不联网"
            )
            .setView(input)
            .setPositiveButton("保存并开启") { _, _ ->
                CloudVlm.setApiKey(this, input.text.toString())
                CloudVlm.setEnabled(this, true)
                refresh()
                toast("已开启云端兜底")
            }
            .setNeutralButton("关闭此功能") { _, _ ->
                CloudVlm.setEnabled(this, false)
                refresh()
                toast("已关闭云端兜底")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

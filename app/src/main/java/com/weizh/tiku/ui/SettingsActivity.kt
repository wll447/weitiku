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
        b.tvAiStatus.text = if (CloudVlm.isEnabled(this)) CloudVlm.provider(this).name else "未开启"
        b.tvAiStatus.setTextColor(
            if (CloudVlm.isEnabled(this)) getColor(com.weizh.tiku.R.color.brand_primary)
            else getColor(com.weizh.tiku.R.color.text_sub)
        )
    }

    /**
     * 云端模型设置：
     *  - 厂商用下拉框选；**选好厂商后 Base URL 自动锁定**，只有「自定义」才可填
     *  - 模型名可手填，也可点「获取」拉该厂商真实模型列表来选
     * 全部存本机，不进代码、不进 APK。
     */
    private fun cloudSettings() {
        val ctx = this
        val cur = CloudVlm.provider(ctx)

        fun label(s: String) = android.widget.TextView(ctx).apply {
            text = s
            textSize = 12f
            setTextColor(getColor(com.weizh.tiku.R.color.text_sub))
            setPadding(0, 18, 0, 6)
        }

        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 12, 48, 8)
        }

        // ① 厂商下拉框
        val names = CloudVlm.PROVIDERS.map { p -> p.name + if (p.vision) "" else "（无视觉）" }
        val spinner = android.widget.Spinner(ctx)
        val sa = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_item, names)
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = sa
        spinner.setSelection(CloudVlm.PROVIDERS.indexOf(cur).coerceAtLeast(0))

        // ② 模型名 + 「获取」按钮
        val etModel = EditText(ctx).apply {
            hint = "留空用 ${cur.model}"
            setText(CloudVlm.model(ctx).takeIf { it != cur.model } ?: "")
            setSingleLine()
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        var picked = cur
        // 「常用」：直接点选该厂商的视觉模型（/models 接口不列视觉模型，只能内置一份）
        val btnCommon = android.widget.Button(ctx).apply {
            text = "常用"
            textSize = 13f
            setOnClickListener {
                val list = picked.visionModels
                if (list.isEmpty()) {
                    toast("该厂商无内置视觉模型，手动填或点「获取」")
                } else {
                    AlertDialog.Builder(ctx)
                        .setTitle("${picked.name} 常用视觉模型")
                        .setItems(list.toTypedArray()) { _, i -> etModel.setText(list[i]) }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
        val btnFetch = android.widget.Button(ctx).apply {
            text = "获取"
            textSize = 13f
            setOnClickListener {
                text = "获取中…"
                isEnabled = false
                lifecycleScope.launch {
                    val list = withContext(Dispatchers.IO) { CloudVlm.listModels(ctx) }
                    text = "获取"
                    isEnabled = true
                    // 关键：上游 /models 接口**通常只返回文本模型**
                    //（实测智谱一个视觉模型都不返回），所以把内置的视觉模型并进来，
                    // 并标注来源，保证「获取」一定能选出能用的视觉模型。
                    val ids = ArrayList<String>()
                    val labels = ArrayList<String>()
                    for (m in picked.visionModels) {
                        ids.add(m); labels.add("$m　（内置·视觉）")
                    }
                    list?.forEach { m -> if (m !in ids) { ids.add(m); labels.add("$m　（接口）") } }
                    if (ids.isEmpty()) {
                        toast("没取到模型：该厂商不支持 /models 且无内置列表，请手填")
                    } else {
                        AlertDialog.Builder(ctx)
                            .setTitle("选择模型（内置 ${picked.visionModels.size} · 接口 ${list?.size ?: 0}）")
                            .setItems(labels.toTypedArray()) { _, i -> etModel.setText(ids[i]) }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
        }
        val modelRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(etModel)
            addView(btnCommon)
            addView(btnFetch)
        }

        // ③ Base URL：只有自定义厂商可填
        val etBase = EditText(ctx).apply {
            setText(CloudVlm.base(ctx).takeIf { it != cur.base } ?: "")
            setSingleLine()
        }
        // ④ Key
        val etKey = EditText(ctx).apply {
            hint = "API Key"
            setText(CloudVlm.apiKey(ctx))
            setSingleLine()
        }

        fun syncFields(p: CloudVlm.Provider, resetBase: Boolean) {
            etModel.hint = "留空用 ${p.model}"
            val custom = p.id == "custom"
            etBase.isEnabled = custom
            etBase.alpha = if (custom) 1f else 0.5f
            when {
                custom -> etBase.hint = "必填，OpenAI 兼容，如 https://api.xxx.com/v1"
                else -> {
                    etBase.hint = "由厂商预设锁定：${p.base}"
                    if (resetBase) etBase.setText("")     // 换厂商就清掉手填的，用预设
                }
            }
            btnFetch.text = "获取"
        }

        box.addView(label("模型提供商"))
        box.addView(spinner)
        box.addView(label("模型名（可手填，或点「获取」拉真实列表）"))
        box.addView(modelRow)
        box.addView(label("接口地址 Base URL"))
        box.addView(etBase)
        box.addView(label("API Key"))
        box.addView(etKey)

        syncFields(cur, false)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                picked = CloudVlm.PROVIDERS[pos]
                syncFields(picked, true)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        val on = CloudVlm.isEnabled(ctx)
        AlertDialog.Builder(ctx)
            .setTitle(if (on) "云端模型（已开启）" else "云端模型（未开启）")
            .setMessage(
                "用法：本地题库里有这道题就直接用库里的答案；没有时才把题目发给云端模型让它作答。\n" +
                    "Key 只存本机；题目内容会上传到所选服务商，涉密别用；本地能搜到时不联网。"
            )
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("保存并开启") { _, _ ->
                CloudVlm.setProvider(ctx, picked)
                CloudVlm.setModel(ctx, etModel.text.toString())
                CloudVlm.setBase(ctx, if (picked.id == "custom") etBase.text.toString() else "")
                CloudVlm.setApiKey(ctx, etKey.text.toString())
                CloudVlm.setEnabled(ctx, true)
                refresh()
                toast("已开启：${picked.name} / ${CloudVlm.model(ctx)}")
            }
            .setNeutralButton("关闭此功能") { _, _ ->
                CloudVlm.setEnabled(ctx, false)
                refresh()
                toast("已关闭云端模型")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

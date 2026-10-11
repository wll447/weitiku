package com.weizh.tiku.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.weizh.tiku.BuildConfig
import com.weizh.tiku.data.Db
import com.weizh.tiku.data.Repo
import com.weizh.tiku.databinding.FragmentMyBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 我的页。布局照同类产品 fragment_my.xml，去掉 VIP 横幅/云备份/广告位，
 * 同类产品靠登录+云端拉取的数据（做题统计、错题、收藏）改为本地库统计。
 */
class MineFragment : Fragment() {

    private var _b: FragmentMyBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentMyBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        b.tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        b.llWrong.setOnClickListener { play(Repo.PracticeMode.WRONG, "错题本") }
        b.llFav.setOnClickListener { play(Repo.PracticeMode.FAV, "我的收藏") }

        b.llSettings.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("设置")
                .setItems(arrayOf("题库导入方法", "云端 AI 兜底", "重新加工已有题库", "重建题库索引", "清空做题记录")) { _, which ->
                    when (which) {
                        0 -> startActivity(
                            Intent(requireContext(), ImportGuideActivity::class.java)
                        )
                        1 -> cloudSettings()
                        2 -> viewLifecycleOwner.lifecycleScope.launch {
                            val n = withContext(Dispatchers.IO) { Repo.renormalizeAll(requireContext()) }
                            toast("已重新加工 $n 道题（去水印/剥题号/选项去前缀/答案规整）")
                            refresh()
                        }
                        3 -> viewLifecycleOwner.lifecycleScope.launch {
                            val n = withContext(Dispatchers.IO) { Repo.rebuildIndex(requireContext()) }
                            toast("索引已重建，共 $n 题")
                        }
                        4 -> {
                            Db.get(requireContext()).clearAnswerLog()
                            toast("做题记录已清空")
                            refresh()
                        }
                    }
                }
                .show()
        }

        b.llShare.setOnClickListener {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "魏题库：无广告、无会员的本地搜题练题 App（题库自己导入）")
            }
            startActivity(Intent.createChooser(i, "分享"))
        }

        b.llAbout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("关于魏题库")
                .setMessage(
                    "版本 v${BuildConfig.VERSION_NAME}\n\n" +
                        "· 无广告、无会员、无登录\n" +
                        "· 题库、做题记录全部存在本机，不联网上传\n" +
                        "· 支持导入 Excel(.xlsx/.xls)、CSV、TXT 题库\n" +
                        "· 搜题：扫描搜题（相机）+ 文本搜题（本地检索）"
                )
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    /** 云端 AI 兜底设置：填智谱 API Key（GLM-4V-Flash 免费），只存本机 */
    private fun cloudSettings() {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            hint = "粘贴智谱 API Key"
            setText(com.weizh.tiku.cloud.CloudVlm.apiKey(ctx))
            setSingleLine()
            setPadding(48, 24, 48, 24)
        }
        val on = com.weizh.tiku.cloud.CloudVlm.isEnabled(ctx)
        AlertDialog.Builder(ctx)
            .setTitle(if (on) "云端 AI 兜底（已开启）" else "云端 AI 兜底（未开启）")
            .setMessage(
                "本地 OCR + 本地题库都搜不到时，把整页图发给智谱 GLM-4V-Flash（官方免费），" +
                    "让它直接输出结构化题目和答案。\n\n" +
                    "· Key 只存在本机，不会打进 APK\n" +
                    "· 题目图片会上传到智谱，涉密内容别用\n" +
                    "· 本地能搜到时不联网"
            )
            .setView(input)
            .setPositiveButton("保存并开启") { _, _ ->
                com.weizh.tiku.cloud.CloudVlm.setApiKey(ctx, input.text.toString())
                com.weizh.tiku.cloud.CloudVlm.setEnabled(ctx, true)
                toast("已开启云端兜底")
            }
            .setNeutralButton("关闭此功能") { _, _ ->
                com.weizh.tiku.cloud.CloudVlm.setEnabled(ctx, false)
                toast("已关闭云端兜底")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            val db = Db.get(requireContext())
            val (total, right) = withContext(Dispatchers.IO) { db.statToday() }
            b.tvStatToday.text = "今日 $total 题"
            b.tvStatRate.text = if (total <= 0) "正确率 --" else "正确率 ${right * 100 / total}%"
            b.tvWrongCount.text = "${withContext(Dispatchers.IO) { db.wrongIds().size }} 题"
            b.tvFavCount.text = "${withContext(Dispatchers.IO) { db.favIds().size }} 题"
        }
    }

    private fun play(mode: String, title: String) {
        startActivity(
            Intent(requireContext(), ExerciseActivity::class.java)
                .putExtra("mode", mode)
                .putExtra("title", title)
        )
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

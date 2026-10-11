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
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
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

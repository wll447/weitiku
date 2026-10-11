package com.weizh.tiku.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.weizh.tiku.MainActivity
import com.weizh.tiku.data.Db
import com.weizh.tiku.data.Repo
import com.weizh.tiku.data.Tiku
import com.weizh.tiku.databinding.FragmentTikuBinding
import com.weizh.tiku.databinding.ItemTikuBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页。布局照同类产品 fragment_home.xml（顶部背景 + 金刚区 + 题库列表），
 * 去掉顶部搜索栏、广告位、VIP 角标、云备份入口。
 */
class TikuFragment : Fragment() {

    private var _b: FragmentTikuBinding? = null
    private val b get() = _b!!
    private val adapter = TikuAdapter()

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val name = queryName(uri)
        Toast.makeText(requireContext(), "正在导入 $name …", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val r = Repo.importFromUri(requireContext(), uri, name)
            val msg = buildString {
                append("「${r.tikuName}」导入完成：成功 ${r.ok} 题")
                if (r.failed > 0) append("，失败 ${r.failed}")
                if (r.sheets.isNotEmpty()) append("\n工作表：${r.sheets.joinToString("、")}")
                if (r.errors.isNotEmpty()) append("\n").append(r.errors.first())
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentTikuBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        b.categoryList.layoutManager = LinearLayoutManager(requireContext())
        b.categoryList.adapter = adapter
        b.categoryList.isNestedScrollingEnabled = false

        // 金刚区
        b.rlScanner.setOnClickListener {
            startActivity(Intent(requireContext(), com.weizh.tiku.ui.scan.ScanActivity::class.java))
        }
        b.rlPhoto.setOnClickListener {
            startActivity(Intent(requireContext(), com.weizh.tiku.ui.photo.PhotoCameraActivity::class.java))
        }
        b.rlExercise.setOnClickListener { (activity as? MainActivity)?.switchToPractice() }
        b.rlScreen.setOnClickListener {
            startActivity(Intent(requireContext(), com.weizh.tiku.ui.screen.ScreenSearchActivity::class.java))
        }
        b.rlImport.setOnClickListener { pickFile.launch(IMPORT_MIME) }
        // 长按看导入格式说明（同类产品这个入口在"我的-设置"里）
        b.rlImport.setOnLongClickListener {
            startActivity(Intent(requireContext(), ImportGuideActivity::class.java)); true
        }
        b.rlSearch.setOnClickListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }

        adapter.onPractice = { tid, name ->
            startActivity(Intent(requireContext(), TikuDetailActivity::class.java)
                .putExtra("tid", tid).putExtra("name", name))
        }
        adapter.onDelete = { t ->
            Db.get(requireContext()).deleteTiku(t.tid)
            viewLifecycleOwner.lifecycleScope.launch { Repo.rebuildIndex(requireContext()); refresh() }
            toast("已删除「${t.name}」")
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = Db.get(requireContext()).listTiku()
        adapter.submit(list)
        b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val total = list.sumOf { Db.get(requireContext()).countQuestions(it.tid) }
        b.categoryTitle.text = "我的题库（${list.size}）"
        b.categoryDesc.text = if (list.isEmpty()) "点上面「导入题库」添加" else "共 $total 题 · 点题库进入练题"
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    private fun queryName(uri: Uri): String {
        var name = "导入题库.xlsx"
        runCatching {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        }
        return name
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    companion object {
        private val IMPORT_MIME = arrayOf(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel",
            "text/csv", "text/comma-separated-values", "text/plain", "*/*"
        )
    }
}

class TikuAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<TikuAdapter.VH>() {

    private val data = ArrayList<Tiku>()
    var onPractice: ((String, String) -> Unit)? = null
    var onDelete: ((Tiku) -> Unit)? = null

    fun submit(list: List<Tiku>) { data.clear(); data.addAll(list); notifyDataSetChanged() }

    class VH(val b: ItemTikuBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
        VH(ItemTikuBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val t = data[pos]
        h.b.tvName.text = t.name
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(t.updated))
        val n = Db.get(h.b.root.context).countQuestions(t.tid)
        h.b.tvSub.text = "$n 题 · 导入于 $time"
        h.b.tvAction.text = "练习"
        h.b.tvDelete.text = "删除"
        h.b.tvAction.setOnClickListener { onPractice?.invoke(t.tid, t.name) }
        h.b.root.setOnClickListener { onPractice?.invoke(t.tid, t.name) }
        h.b.tvDelete.setOnClickListener { onDelete?.invoke(t) }
    }
}

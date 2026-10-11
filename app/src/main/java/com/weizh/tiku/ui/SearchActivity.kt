package com.weizh.tiku.ui

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.weizh.tiku.data.Repo
import com.weizh.tiku.databinding.ActivitySearchBinding
import com.weizh.tiku.search.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 文本搜题。布局照同类产品 activity_search.xml（顶部搜索条 + 结果区）。 */
class SearchActivity : AppCompatActivity() {

    private lateinit var b: ActivitySearchBinding
    private val adapter = ResultAdapter(scanMode = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.feed.layoutManager = LinearLayoutManager(this)
        b.feed.adapter = adapter

        b.btnBack.setOnClickListener { finish() }
        b.btnSearch.setOnClickListener { search() }
        b.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }

        val q = intent.getStringExtra("q").orEmpty()
        if (q.isNotBlank()) { b.etSearch.setText(q); search() }
    }

    private fun search() {
        val q = b.etSearch.text.toString().trim()
        if (q.isBlank()) { b.tvStatus.text = "请输入题干"; return }
        b.tvStatus.text = "搜索中…"
        b.spinKit.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            // 索引没建好就先建（冷启动或刚导入）
            if (!SearchEngine.get().isReady) withContext(Dispatchers.IO) { Repo.rebuildIndex(this@SearchActivity) }
            val (rows0, reason) = SearchEngine.get().searchText(q, 20)
            // 没命中就再用选项乱序排列匹配试一次（照原版 allPerm）
            val rows = if (rows0.isNotEmpty()) rows0
            else SearchEngine.get().searchWithPermutations(q, 20)
            b.spinKit.visibility = android.view.View.GONE
            adapter.submit(rows)
            b.tvStatus.text = when {
                rows.isNotEmpty() -> "命中 ${rows.size} 条（按公共片段排序）"
                reason.isNotEmpty() -> reason
                else -> "本地题库未匹配到该题目"
            }
        }
    }
}

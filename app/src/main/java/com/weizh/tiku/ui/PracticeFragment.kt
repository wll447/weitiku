package com.weizh.tiku.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.weizh.tiku.data.Db
import com.weizh.tiku.data.Repo
import com.weizh.tiku.databinding.FragmentPracticeBinding
import com.weizh.tiku.databinding.ItemTikuBinding

class PracticeFragment : Fragment() {

    private var _b: FragmentPracticeBinding? = null
    private val b get() = _b!!
    private val adapter = PickTikuAdapter()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentPracticeBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        b.rvTikuPick.layoutManager = LinearLayoutManager(requireContext())
        b.rvTikuPick.adapter = adapter

        b.cardSeq.setOnClickListener { start(Repo.PracticeMode.SEQ, null) }
        b.cardRandom.setOnClickListener { start(Repo.PracticeMode.RANDOM, null) }
        b.cardWrong.setOnClickListener { start(Repo.PracticeMode.WRONG, null) }
        b.cardFav.setOnClickListener { start(Repo.PracticeMode.FAV, null) }

        adapter.onClick = { tid -> start(Repo.PracticeMode.SEQ, tid) }
    }

    override fun onResume() {
        super.onResume()
        val ctx = requireContext()
        val db = Db.get(ctx)
        val list = db.listTiku()
        adapter.submit(list)
        b.tvSeqSub.text = "按题库顺序"
        b.tvRandomSub.text = "随机抽 50 题"
        b.tvWrongSub.text = "已收录 ${db.wrongIds().size} 题"
        b.tvFavSub.text = "已收藏 ${db.favIds().size} 题"
    }

    private fun start(mode: String, tid: String?) {
        val ctx = requireContext()
        if (Db.get(ctx).listTiku().isEmpty()) {
            Toast.makeText(ctx, "先到「题库」页导入一份题库", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(ctx, ExerciseActivity::class.java)
            .putExtra("mode", mode)
            .putExtra("tid", tid))
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

class PickTikuAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<PickTikuAdapter.VH>() {
    private val data = ArrayList<com.weizh.tiku.data.Tiku>()
    var onClick: ((String) -> Unit)? = null

    fun submit(list: List<com.weizh.tiku.data.Tiku>) { data.clear(); data.addAll(list); notifyDataSetChanged() }

    class VH(val b: ItemTikuBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
        VH(ItemTikuBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val t = data[pos]
        val n = Db.get(h.b.root.context).countQuestions(t.tid)
        h.b.tvName.text = t.name
        h.b.tvSub.text = "$n 题"
        h.b.tvAction.text = "练习"
        h.b.tvDelete.visibility = View.GONE
        h.b.root.setOnClickListener { onClick?.invoke(t.tid) }
        h.b.tvAction.setOnClickListener { onClick?.invoke(t.tid) }
    }
}

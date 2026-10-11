package com.weizh.tiku

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.weizh.tiku.data.Db
import com.weizh.tiku.data.Repo
import com.weizh.tiku.data.Tiku
import com.weizh.tiku.importer.Importer
import com.weizh.tiku.search.SearchEngine
import com.weizh.tiku.ui.MineFragment
import com.weizh.tiku.ui.PracticeFragment
import com.weizh.tiku.ui.TikuFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val tikuFrag by lazy { TikuFragment() }
    private val practiceFrag by lazy { PracticeFragment() }
    private val mineFrag by lazy { MineFragment() }
    private var current: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_tiku -> { show(tikuFrag); true }
                R.id.tab_practice -> { show(practiceFrag); true }
                R.id.tab_mine -> { show(mineFrag); true }
                else -> false
            }
        }
        if (savedInstanceState == null) show(tikuFrag)

        // 开发自检：adb shell am start -n com.weizh.tiku/.MainActivity --ez selftest true
        Log.i("WeiTikuSelfTest", "onCreate DEBUG=${BuildConfig.DEBUG} extra=${intent?.getBooleanExtra("selftest", false)}")
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("selftest", false) == true) {
            Log.i("WeiTikuSelfTest", "trigger self test")
            runSelfTest()
        }
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("cloudtest", false) == true) {
            Log.i("WeiTikuSelfTest", "trigger cloud test")
            lifecycleScope.launch(Dispatchers.IO) {
                // 允许用 extra 直接注入 key/模型，省得在模拟器上点 UI
                intent?.getStringExtra("apikey")?.takeIf { it.isNotBlank() }?.let {
                    com.weizh.tiku.cloud.CloudVlm.setApiKey(this@MainActivity, it)
                    com.weizh.tiku.cloud.CloudVlm.setEnabled(this@MainActivity, true)
                }
                intent?.getStringExtra("model")?.takeIf { it.isNotBlank() }?.let {
                    com.weizh.tiku.cloud.CloudVlm.setModel(this@MainActivity, it)
                }
                val out = com.weizh.tiku.cloud.CloudVlm.selfTest(this@MainActivity)
                Log.i("WeiTikuSelfTest", "=== CLOUD SELFTEST ===\n" + out)
            }
        }
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("ocrtest", false) == true) {
            Log.i("WeiTikuSelfTest", "trigger ocr test")
            runOcrTest()
        }
        // 导入自检：adb push 文件到 /data/data/com.weizh.tiku/files/ 后
        //   adb shell am start -n com.weizh.tiku/.MainActivity --es importtest 文件名.xls
        if (BuildConfig.DEBUG) {
            intent?.getStringExtra("importtest")?.let { name ->
                Log.i("WeiTikuSelfTest", "trigger import test: $name")
                runImportTest(name)
            }
        }
    }

    /** 用真实题库文件验证导入链路（列识别 + 多工作表 + 长文本答案 + 入库 + 建索引 + 检索） */
    private fun runImportTest(fileName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val tag = "WeiTikuSelfTest"
            fun log(s: String) = Log.i(tag, s)
            try {
                val f = java.io.File(filesDir, fileName)
                if (!f.exists()) { log("file not found: ${f.absolutePath}"); return@launch }
                log("file=${f.name} size=${f.length()}")

                val t0 = System.currentTimeMillis()
                val r = f.inputStream().use {
                    com.weizh.tiku.importer.Importer.importFile(f.name, it, cacheDir)
                }
                log("import: ok=${r.ok} failed=${r.failed} sheets=${r.sheets} in ${System.currentTimeMillis() - t0}ms")
                r.errors.take(5).forEach { log("  err: $it") }
                log("--- 前 3 题 ---")
                r.questions.take(3).forEachIndexed { i, q ->
                    log("  #${i + 1} Q=[${q.question.take(40)}] options=${q.options.size} A=[${q.answer.take(60)}]")
                }

                if (r.questions.isNotEmpty()) {
                    val db = Db.get(this@MainActivity)
                    val tid = "MYTEST_IMP"
                    runCatching { db.deleteTiku(tid) }
                    db.upsertTiku(Tiku(tid = tid, name = "导入测试"))
                    val n = db.insertQuestions(tid, r.questions)
                    val idx = Repo.rebuildIndex(this@MainActivity)
                    log("insert=$n index=$idx")

                    // 用第一题题干去检索，验证能命中自己
                    val probe = r.questions.first().question
                    val hits = SearchEngine.get().query(probe, 3)
                    log("search probe=[${probe.take(30)}] hits=${hits.size}")
                    hits.firstOrNull()?.let { log("  top1=[${it.question.question.take(30)}] A=[${it.question.answer.take(40)}] score=${"%.2f".format(it.score)}") }
                }
                log("SELFTEST DONE")
            } catch (e: Throwable) {
                Log.e(tag, "IMPORT TEST FAILED: ${e.message}", e)
            }
        }
    }

    /** OCR 自检：识别内置中文题图并走一遍本地检索 */
    private fun runOcrTest() {
        lifecycleScope.launch(Dispatchers.Default) {
            val tag = "WeiTikuSelfTest"
            fun log(s: String) = Log.i(tag, s)
            try {
                var t = System.currentTimeMillis()
                val loaded = com.weizh.tiku.ocr.OcrEngine.ensureLoaded(this@MainActivity)
                log("ocr load=$loaded in ${System.currentTimeMillis() - t}ms")

                t = System.currentTimeMillis()
                val bmp = assets.open("ocr/test.png").use { android.graphics.BitmapFactory.decodeStream(it) }
                log("image ${bmp.width}x${bmp.height} decode ${System.currentTimeMillis() - t}ms")

                t = System.currentTimeMillis()
                val boxes = com.weizh.tiku.ocr.OcrEngine.detect(bmp)
                log("det: ${boxes.size} boxes in ${System.currentTimeMillis() - t}ms -> " +
                    boxes.joinToString(" | ") { "${it.left.toInt()},${it.top.toInt()},${it.right.toInt()},${it.bottom.toInt()}" })

                t = System.currentTimeMillis()
                val text = com.weizh.tiku.ocr.OcrEngine.recognizeAll(bmp)
                log("ocr text=[$text] in ${System.currentTimeMillis() - t}ms")

                val (hit0, m0) = com.weizh.tiku.search.SearchEngine.get().bestMatch(text)
                log("strict match -> ${hit0?.answer ?: "MISS"} lcs=${m0.lcsLen} ratio=${if (m0.queryLen > 0) m0.lcsLen * 100 / m0.queryLen else 0}%")

                // 扫描链路用的是 TF-IDF topN 排序（与原版 Utils.getQuestionList 一致），不做 LCS 门禁
                val indexed = com.weizh.tiku.data.Repo.rebuildIndex(this@MainActivity)
                log("index=$indexed")
                val top = com.weizh.tiku.search.SearchEngine.get().query(text, 3)
                log("topN(旧接口)=${top.size}")

                // 质量门禁验证：视频里那些垃圾短文本必须被拒绝，而不是返回"匹配度100%"的鬼结果
                for (bad in listOf("专", "管", "a 8 8", "正确", "的", "识别")) {
                    val (rows, why) = com.weizh.tiku.search.SearchEngine.get().searchForScan(bad, 3)
                    log("  门禁 [$bad] -> hits=${rows.size} reason=$why")
                }
                // 正常长文本应当命中
                val (good, whyGood) = com.weizh.tiku.search.SearchEngine.get().searchForScan("中国的首都是哪里A北京B上海C广州D深圳", 3)
                log("  门禁 [正常题目] -> hits=${good.size} reason=$whyGood top1=${good.firstOrNull()?.question?.answer} lcs=${good.firstOrNull()?.lcs}")
                log("topN=${top.size}")
                for ((i, hit) in top.withIndex()) {
                    val qq = hit.question
                    val lcs = com.weizh.tiku.search.SearchEngine.lcsLength(
                        com.weizh.tiku.search.SearchEngine.formatString(text),
                        com.weizh.tiku.search.SearchEngine.formatString(qq.question) + qq.options.joinToString("")
                    )
                    log("  #${i + 1} score=${"%.2f".format(hit.score)} lcs=$lcs  Q=[${qq.question}] A=[${qq.answer}]")
                }

                t = System.currentTimeMillis()
                val boxes2 = com.weizh.tiku.ocr.OcrEngine.detect(bmp)
                var txt2 = StringBuilder()
                for (b in boxes2) txt2.append(com.weizh.tiku.ocr.OcrEngine.recognizeLine(bmp, b)).append(' ')
                log("full(text+options)=[${txt2.toString().trim()}] in ${System.currentTimeMillis() - t}ms")
                log("SELFTEST DONE")
            } catch (e: Throwable) {
                Log.e(tag, "OCR TEST FAILED: ${e.message}", e)
            }
        }
    }

    private fun show(f: Fragment) {
        if (current === f) return
        val tx = supportFragmentManager.beginTransaction()
        current?.let { tx.hide(it) }
        if (f.isAdded) tx.show(f) else tx.add(R.id.container, f, f.javaClass.simpleName)
        tx.commitAllowingStateLoss()
        current = f
    }

    fun switchToPractice() {
        findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.tab_practice
    }

    // ---------------- 自检 ----------------

    private fun runSelfTest() {
        lifecycleScope.launch(Dispatchers.IO) {
            val tag = "WeiTikuSelfTest"
            fun log(s: String) = Log.i(tag, s)
            try {
                val db = Db.get(this@MainActivity)
                val tid = "MYTEST"

                // 清理旧数据
                runCatching { db.deleteTiku(tid) }
                db.listTiku().filter { it.name == "sample_tiku" }.forEach { runCatching { db.deleteTiku(it.tid) } }

                // 1) 解析 CSV（走统一导入入口）
                val parsed = assets.open("sample_tiku.csv").use { input ->
                    Importer.importFile("sample_tiku.csv", input, cacheDir)
                }
                log("parse: ok=${parsed.ok} failed=${parsed.failed} errors=${parsed.errors.take(3)}")

                // 2) 入库
                db.upsertTiku(Tiku(tid = tid, name = "sample_tiku"))
                val inserted = db.insertQuestions(tid, parsed.questions)
                log("insert: $inserted 题")

                // 3) 建索引
                val indexed = Repo.rebuildIndex(this@MainActivity)
                val engine = SearchEngine.get()
                log("index: $indexed 题, ready=${engine.isReady}")

                // 4) 检索命中
                for (q in listOf(
                    "中国的首都是哪里",
                    "水的化学式是什么",
                    "一个直角三角形的两个锐角之和是",
                    "地球是圆的吗"
                )) {
                    val (hit, m) = engine.bestMatch(q)
                    log("search OK  [$q] -> ${hit?.answer ?: "MISS"}  lcs=${m.lcsLen} ratio=${if (m.queryLen > 0) m.lcsLen * 100 / m.queryLen else 0}%")
                }

                // 5) 检索未命中（题库里没有的题）
                val (miss, mm) = engine.bestMatch("请问魏题库的作者是谁并且本题绝对不在题库里")
                log("search MISS -> ${miss?.question ?: "正确未命中"}  accepted=${mm.accepted} lcs=${mm.lcsLen}")

                // 6) 练题数据
                val seq = Repo.loadForPractice(this@MainActivity, Repo.PracticeMode.SEQ, tid)
                val rnd = Repo.loadForPractice(this@MainActivity, Repo.PracticeMode.RANDOM, tid)
                log("practice: seq=${seq.size} random=${rnd.size} first=[${seq.firstOrNull()?.question}]")

                // 7) 错题/收藏链路
                val firstId = seq.firstOrNull()?.id ?: -1L
                if (firstId > 0) {
                    db.markWrong(firstId)
                    db.toggleFav(firstId)
                    log("wrongBook=${db.wrongIds().size} fav=${db.favIds().size} isFav=${db.isFav(firstId)}")
                }
                log("SELFTEST DONE")
            } catch (t: Throwable) {
                Log.e(tag, "SELFTEST FAILED: ${t.message}", t)
            }
        }
    }
}

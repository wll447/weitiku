package com.weizh.tiku.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地题库数据库。纯 SQLite（不引 Room），表结构对齐原版语义：
 * tiku(题库) / question(题目) / wrong(错题) / fav(收藏) / answer_log(答题流水)
 */
class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    companion object {
        private const val NAME = "weitiku.db"
        private const val VERSION = 1
        /** 选项分隔符，与原版 G.DELIMITER_A 一致 */
        const val DELIM_A = "\t"

        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx).also { inst = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tiku(
                 tid TEXT PRIMARY KEY, name TEXT NOT NULL, desc TEXT DEFAULT '',
                 curver INTEGER DEFAULT 1, num INTEGER DEFAULT 0,
                 updated INTEGER DEFAULT 0, is_mine INTEGER DEFAULT 1)"""
        )
        db.execSQL(
            """CREATE TABLE question(
                 id INTEGER PRIMARY KEY AUTOINCREMENT, tid TEXT NOT NULL,
                 question TEXT NOT NULL, answers TEXT NOT NULL, answer TEXT NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_q_tid ON question(tid)")
        db.execSQL("CREATE INDEX idx_q_text ON question(question)")
        db.execSQL("CREATE TABLE wrong(qid INTEGER PRIMARY KEY, wrong_count INTEGER DEFAULT 1, last_time INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE fav(qid INTEGER PRIMARY KEY, time INTEGER DEFAULT 0)")
        db.execSQL(
            """CREATE TABLE answer_log(
                 id INTEGER PRIMARY KEY AUTOINCREMENT, qid INTEGER, chosen TEXT,
                 correct INTEGER, time INTEGER)"""
        )
        db.execSQL("CREATE TABLE progress(k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 自用库：结构变更直接重建题目相关表，题库内容可从文件重导
    }

    // ---------------- 题库 ----------------

    fun upsertTiku(t: Tiku) {
        val v = ContentValues().apply {
            put("tid", t.tid); put("name", t.name); put("desc", t.desc)
            put("curver", t.curver); put("num", t.num); put("updated", t.updated)
            put("is_mine", if (t.isMine) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("tiku", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun listTiku(): List<Tiku> {
        val out = ArrayList<Tiku>()
        readableDatabase.rawQuery("SELECT tid,name,desc,curver,num,updated,is_mine FROM tiku ORDER BY updated DESC", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Tiku(
                        tid = c.getString(0), name = c.getString(1), desc = c.getString(2) ?: "",
                        curver = c.getInt(3), num = c.getInt(4), updated = c.getLong(5), isMine = c.getInt(6) == 1
                    )
                )
            }
        }
        return out
    }

    fun countQuestions(tid: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM question WHERE tid=?", arrayOf(tid)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun refreshTikuNum(tid: String) {
        val n = countQuestions(tid)
        writableDatabase.execSQL(
            "UPDATE tiku SET num=?, updated=? WHERE tid=?",
            arrayOf<Any>(n, System.currentTimeMillis(), tid)
        )
    }

    fun deleteTiku(tid: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("question", "tid=?", arrayOf(tid))
            writableDatabase.delete("tiku", "tid=?", arrayOf(tid))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /** 批量写入题目，返回成功条数 */
    /** 更新单题的标准化结果（题库重加工用） */
    fun updateQuestion(id: Long, question: String, answers: String, answer: String) {
        writableDatabase.execSQL(
            "UPDATE question SET question=?, answers=?, answer=? WHERE id=?",
            arrayOf<Any>(question, answers, answer, id)
        )
    }

    fun insertQuestions(tid: String, list: List<Question>): Int {
        val db = writableDatabase
        db.beginTransaction()
        var n = 0
        try {
            val sql = "INSERT INTO question(tid,question,answers,answer) VALUES(?,?,?,?)"
            val st = db.compileStatement(sql)
            for (q in list) {
                st.clearBindings()
                st.bindString(1, tid)
                st.bindString(2, q.question)
                st.bindString(3, q.answers)
                st.bindString(4, q.answer)
                st.executeInsert()
                n++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshTikuNum(tid)
        return n
    }

    // ---------------- 查询 ----------------

    fun loadAllForIndex(tids: List<String>?): List<Question> {
        val out = ArrayList<Question>()
        val where: String
        val args: Array<String>?
        if (tids.isNullOrEmpty()) { where = ""; args = null } else {
            where = " WHERE tid IN (" + tids.joinToString(",") { "?" } + ")"
            args = tids.toTypedArray()
        }
        readableDatabase.rawQuery(
            "SELECT MIN(id), question, answers, answer FROM question$where GROUP BY question, answers", args
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Question(c.getLong(0), "", c.getString(1), c.getString(2), c.getString(3)))
            }
        }
        return out
    }

    fun findById(id: Long): Question? =
        readableDatabase.rawQuery("SELECT id,tid,question,answers,answer FROM question WHERE id=?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) Question(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4)) else null
        }

    /** 随机取题（练题用） */
    fun randomQuestions(tids: List<String>?, limit: Int): List<Question> {
        val where = if (tids.isNullOrEmpty()) "" else " WHERE tid IN (" + tids.joinToString(",") { "?" } + ")"
        val args = (tids?.toTypedArray() ?: emptyArray()) + arrayOf(limit.toString())
        val out = ArrayList<Question>()
        readableDatabase.rawQuery(
            "SELECT id,tid,question,answers,answer FROM question$where ORDER BY RANDOM() LIMIT ?", args
        ).use { c ->
            while (c.moveToNext()) out.add(Question(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)))
        }
        return out
    }

    fun questionsByTiku(tid: String, limit: Int = 5000): List<Question> {
        val out = ArrayList<Question>()
        readableDatabase.rawQuery(
            "SELECT id,tid,question,answers,answer FROM question WHERE tid=? ORDER BY id LIMIT ?",
            arrayOf(tid, limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Question(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)))
        }
        return out
    }

    /** 全部题目（不限题库），顺序练习用 */
    fun allQuestions(limit: Int = 5000): List<Question> {
        val out = ArrayList<Question>()
        readableDatabase.rawQuery(
            "SELECT id,tid,question,answers,answer FROM question ORDER BY id LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Question(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)))
        }
        return out
    }

    // ---------------- 错题 / 收藏 ----------------

    fun markWrong(qid: Long) {
        writableDatabase.execSQL(
            "INSERT INTO wrong(qid,wrong_count,last_time) VALUES(?,1,?) " +
                "ON CONFLICT(qid) DO UPDATE SET wrong_count=wrong_count+1, last_time=excluded.last_time",
            arrayOf<Any>(qid, System.currentTimeMillis())
        )
    }

    fun removeWrong(qid: Long) { writableDatabase.delete("wrong", "qid=?", arrayOf(qid.toString())) }

    fun wrongIds(): List<Long> {
        val out = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT qid FROM wrong ORDER BY last_time DESC", null).use {
            while (it.moveToNext()) out.add(it.getLong(0))
        }
        return out
    }

    fun toggleFav(qid: Long): Boolean {
        val has = readableDatabase.rawQuery("SELECT 1 FROM fav WHERE qid=?", arrayOf(qid.toString())).use { it.moveToFirst() }
        if (has) writableDatabase.delete("fav", "qid=?", arrayOf(qid.toString()))
        else writableDatabase.execSQL("INSERT INTO fav(qid,time) VALUES(?,?)", arrayOf<Any>(qid, System.currentTimeMillis()))
        return !has
    }

    fun isFav(qid: Long): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM fav WHERE qid=?", arrayOf(qid.toString())).use { it.moveToFirst() }

    fun favIds(): List<Long> {
        val out = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT qid FROM fav ORDER BY time DESC", null).use {
            while (it.moveToNext()) out.add(it.getLong(0))
        }
        return out
    }

    // ---------------- 答题流水 / 进度 ----------------

    fun logAnswer(qid: Long, chosen: String, correct: Boolean) {
        writableDatabase.execSQL(
            "INSERT INTO answer_log(qid,chosen,correct,time) VALUES(?,?,?,?)",
            arrayOf<Any>(qid, chosen, if (correct) 1 else 0, System.currentTimeMillis())
        )
    }

        /** 清空答题流水（设置页用） */
    fun clearAnswerLog() {
        writableDatabase.delete("answer_log", null, null)
    }

    /** 清空错题本与收藏（题库详情页"清空记录"用） */
    fun clearWrongAndFav() {
        writableDatabase.delete("wrong", null, null)
        writableDatabase.delete("fav", null, null)
    }

    fun statToday(): Pair<Int, Int> {
        val start = System.currentTimeMillis() - 24 * 3600_000L
        var total = 0; var right = 0
        readableDatabase.rawQuery("SELECT correct FROM answer_log WHERE time>=?", arrayOf(start.toString())).use {
            while (it.moveToNext()) { total++; if (it.getInt(0) == 1) right++ }
        }
        return total to right
    }

    fun putProgress(k: String, v: String) {
        writableDatabase.execSQL(
            "INSERT INTO progress(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", arrayOf<Any>(k, v)
        )
    }

    fun getProgress(k: String, def: String = ""): String =
        readableDatabase.rawQuery("SELECT v FROM progress WHERE k=?", arrayOf(k)).use {
            if (it.moveToFirst()) it.getString(0) else def
        }
}

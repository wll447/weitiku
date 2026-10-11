package com.weizh.tiku.importer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** 轻量 xlsx 读取（zip + XML 流式，不依赖 POI），返回全部工作表 */
object XlsxReader {

    fun read(input: InputStream): List<Sheet> {
        var shared = emptyList<String>()
        val sheets = LinkedHashMap<String, ByteArray>()   // 文件名 -> 字节
        var workbookXml: ByteArray? = null

        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val n = e.name
                when {
                    n == "xl/sharedStrings.xml" -> shared = parseSharedStrings(zip.readBytes())
                    n == "xl/workbook.xml" -> workbookXml = zip.readBytes()
                    n.startsWith("xl/worksheets/") && n.endsWith(".xml") -> sheets[n] = zip.readBytes()
                }
                e = zip.nextEntry
            }
        }

        val names = parseSheetNames(workbookXml)
        val out = ArrayList<Sheet>()
        val sortedKeys = sheets.keys.sortedWith(compareBy({ it.length }, { it }))
        sortedKeys.forEachIndexed { i, key ->
            val rows = parseSheet(sheets[key]!!, shared)
            if (rows.isEmpty()) return@forEachIndexed
            val name = names.getOrNull(i) ?: key.substringAfterLast('/').removeSuffix(".xml")
            out.add(Sheet(name, rows))
        }
        return out
    }

    /** xl/workbook.xml 里 <sheet name="..."> 按文档顺序 */
    private fun parseSheetNames(bytes: ByteArray?): List<String> {
        if (bytes == null) return emptyList()
        val out = ArrayList<String>()
        try {
            val p = Xml.newPullParser()
            p.setInput(ByteArrayInputStream(bytes), "UTF-8")
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "sheet") {
                    p.getAttributeValue(null, "name")?.let { out.add(it) }
                }
                ev = p.next()
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(bytes), "UTF-8")
        var ev = p.eventType
        var inSi = false
        val sb = StringBuilder()
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> if (p.name == "si") { inSi = true; sb.setLength(0) }
                XmlPullParser.TEXT -> if (inSi) sb.append(p.text)
                XmlPullParser.END_TAG -> if (p.name == "si") { out.add(sb.toString()); inSi = false }
            }
            ev = p.next()
        }
        return out
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(bytes), "UTF-8")
        var ev = p.eventType
        var cur: ArrayList<String>? = null
        var cellCol = -1
        var cellType = ""
        var inValue = false
        val value = StringBuilder()

        fun put(col: Int, v: String) {
            val row = cur ?: return
            while (row.size <= col) row.add("")
            row[col] = v
        }

        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> cur = ArrayList()
                    "c" -> {
                        val ref = p.getAttributeValue(null, "r")
                        cellCol = if (ref != null) colOf(ref) else (cur?.size ?: 0)
                        cellType = p.getAttributeValue(null, "t") ?: ""
                        value.setLength(0)
                    }
                    "v", "t" -> inValue = true
                }
                XmlPullParser.TEXT -> if (inValue) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val raw = value.toString()
                        val text = when (cellType) {
                            "s" -> shared.getOrNull(raw.toIntOrNull() ?: -1) ?: ""
                            "b" -> if (raw == "1") "TRUE" else "FALSE"
                            else -> raw
                        }
                        if (cellCol >= 0) put(cellCol, text)
                    }
                    "row" -> { cur?.let { rows.add(it) }; cur = null }
                }
            }
            ev = p.next()
        }
        return rows
    }

    /** "AB12" → 27 */
    private fun colOf(ref: String): Int {
        var n = 0
        for (c in ref) {
            if (c in 'A'..'Z') n = n * 26 + (c - 'A' + 1)
            else if (c in 'a'..'z') n = n * 26 + (c - 'a' + 1)
            else break
        }
        return n - 1
    }
}

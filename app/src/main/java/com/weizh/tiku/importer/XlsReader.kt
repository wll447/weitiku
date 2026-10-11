package com.weizh.tiku.importer

import jxl.Workbook
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 老版 .xls（OLE2 / BIFF8）读取。
 * 用 jxl（709KB，纯 Java、无 AWT 依赖）实现；jxl 只能按文件读，
 * 所以先把输入流落盘到私有目录再解析。
 */
object XlsReader {

    fun read(input: InputStream, tmpDir: File): List<Sheet> {
        val tmp = File(tmpDir, "import_${System.currentTimeMillis()}.xls")
        try {
            FileOutputStream(tmp).use { out: OutputStream -> input.copyTo(out, 1 shl 16) }
            val wb = Workbook.getWorkbook(tmp)
            val out = ArrayList<Sheet>()
            try {
                for (i in 0 until wb.numberOfSheets) {
                    val sh = wb.getSheet(i)
                    val rows = ArrayList<List<String>>()
                    for (r in 0 until sh.rows) {
                        val row = ArrayList<String>(sh.columns)
                        var hasContent = false
                        for (c in 0 until sh.columns) {
                            val v = runCatching { sh.getCell(c, r)?.contents ?: "" }.getOrDefault("")
                            if (v.isNotBlank()) hasContent = true
                            row.add(v)
                        }
                        // 跳过完全空行
                        if (hasContent) rows.add(row)
                    }
                    out.add(Sheet(sh.name ?: "sheet${i + 1}", rows))
                }
            } finally {
                runCatching { wb.close() }
            }
            return out
        } finally {
            runCatching { tmp.delete() }
        }
    }
}

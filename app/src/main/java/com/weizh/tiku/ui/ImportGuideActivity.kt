package com.weizh.tiku.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.weizh.tiku.databinding.ActivityImportGuideBinding

/** 题库导入方法说明页（布局照同类产品 activity_import_guide.xml，文案按本项目真实导入规则） */
class ImportGuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityImportGuideBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }
    }
}

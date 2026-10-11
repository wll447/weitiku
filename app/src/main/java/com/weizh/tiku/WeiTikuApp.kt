package com.weizh.tiku

import android.app.Application
import android.util.Log
import com.weizh.tiku.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口：启动时后台构建题库检索索引（对齐原版 App.onCreate 里 SearchEngine.initWithThread 的做法）。
 */
class WeiTikuApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            try {
                val t = System.currentTimeMillis()
                val n = Repo.rebuildIndex(this@WeiTikuApp)
                Log.i("WeiTiku", "index built: $n 题 in ${System.currentTimeMillis() - t}ms")
            } catch (e: Throwable) {
                Log.e("WeiTiku", "index build failed", e)
            }
        }
    }
}

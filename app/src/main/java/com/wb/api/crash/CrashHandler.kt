package com.wb.api.crash

import android.content.Context
import com.wb.api.core.LogBus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 全局未捕获异常记录：写入 filesDir/crash.log，便于排查。 */
object CrashHandler {
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val f = File(app.filesDir, "crash.log")
                // 无界追加在「反复崩溃」时会把这个文件写到任意大：读取端虽然只读尾部
                // 8KB，但文件本身会一直长，最终占满用户存储。超过 256KB 就以本次栈重置
                // （比「永远不记」好：至少留下最近一次）。
                if (f.exists() && f.length() > 256 * 1024) runCatching { f.delete() }
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                f.appendText("[$ts] ${t.name}: ${e.message}\n${e.stackTraceToString()}\n\n")
                LogBus.log("[sys] crash: ${e.message}")
            }
            prev?.uncaughtException(t, e)
        }
    }
}

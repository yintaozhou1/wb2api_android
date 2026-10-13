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
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                f.appendText("[$ts] ${t.name}: ${e.message}\n${e.stackTraceToString()}\n\n")
                LogBus.log("[sys] crash: ${e.message}")
            }
            prev?.uncaughtException(t, e)
        }
    }
}

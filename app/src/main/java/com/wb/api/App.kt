package com.wb.api

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.StrictMode
import com.wb.api.crash.CrashHandler

/**
 * 进程入口。
 *
 * 冷启动纪律（对齐 Google「Application.onCreate 极简」的官方建议）：
 *  - 只做「UI 首帧之前确实需要」的事：崩溃处理器安装、网关核心初始化
 *    （UI 会在 ViewModel 构造时读 `Gateway.store.config`，所以它必须同步完成）；
 *  - 其余一切（读历史崩溃文件、切分行、写日志）**不占主线程**。
 *    `Application.onCreate` 是启动路径上最贵的一段，多一次磁盘读就是多几十毫秒的冷启动。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 仅 debug：把「主线程磁盘读写 / 网络 / 未关闭的流」当场打进 logcat。
        // 官方《App startup analysis and optimization》推荐用它定位启动路径上的磁盘与 IPC 违规；
        // 只加 penaltyLog（不加 penaltyDeath），不会打断调试。
        if (BuildConfig.DEBUG) installStrictMode()
        CrashHandler.install(this)
        Gateway.init(this)
        // 一次性 fire-and-forget：只碰 filesDir 与内存里的日志环，不持有任何可泄漏引用
        // （不引用 Activity/View），线程随进程结束而结束，因此用裸线程是安全的。
        Thread({ reportLastCrash() }, "wb2api-crash-report").apply { isDaemon = true }.start()
    }

    private fun installStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectActivityLeaks()
                .detectLeakedClosableObjects()      // 抓未关闭的 Socket / Stream（本项目有自研 HTTP 服务器）
                .detectLeakedRegistrationObjects()
                .penaltyLog()
                .build(),
        )
    }

    /**
     * 内存压力回调：把「随时可以重建」的缓存交还系统。
     *
     * 本应用是「常驻前台服务 + 液态玻璃动画 + 请求归档」的重度场景，而 Android 17 起引入了
     * per-app 内存上限（超限会先压缩再强杀）。系统报压力时主动放掉空闲连接池，
     * 比等着被压划算；不碰任何运行状态，下一请求会自然新建连接。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            if (Gateway.isInitialized()) Gateway.trimMemory()
        }
    }

    /**
     * 上次崩溃若存在，摘要写入日志，便于在「日志」页直接看到原因。
     *
     * 只读文件**尾部 8KB**：崩溃日志可能被反复追加得很大，`readLines()` 会整份读进内存。
     */
    private fun reportLastCrash() {
        runCatching {
            val f = java.io.File(filesDir, "crash.log")
            if (!f.exists()) return@runCatching
            val text = java.io.RandomAccessFile(f, "r").use { raf ->
                val size = raf.length()
                val start = (size - 8192).coerceAtLeast(0)
                raf.seek(start)
                val buf = ByteArray((size - start).toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
            val tail = text.lineSequence().filter { it.isNotBlank() }.toList().takeLast(14).joinToString("\n")
            if (tail.isNotBlank()) com.wb.api.core.LogBus.log("[sys] 检测到历史崩溃记录：\n$tail")
            f.delete()
        }
    }
}

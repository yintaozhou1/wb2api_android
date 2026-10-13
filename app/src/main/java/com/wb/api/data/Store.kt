package com.wb.api.data

import android.content.Context
import com.wb.api.core.AppJson
import com.wb.api.core.objOrNull
import com.wb.api.core.Durations
import com.wb.api.core.GatewayConfig
import com.wb.api.core.Ids
import com.wb.api.core.LogBus
import com.wb.api.core.PrettyJson
import com.wb.api.model.AccountState
import com.wb.api.model.AuthEntry
import com.wb.api.model.PoolStateFile
import com.wb.api.model.UsageRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地存储：账号凭证目录、池状态、用量、配置。
 * 全部使用「临时文件 + 原子改名」写入，保证崩溃不损坏数据。
 */
class Store(private val context: Context) {

    val root: File get() = context.filesDir
    val authDir: File get() = File(root, "auths").apply { mkdirs() }
    val dataDir: File get() = File(root, "data").apply { mkdirs() }
    private val configFile: File get() = File(root, "config.json")
    private val stateFile: File get() = File(dataDir, "state.json")
    private val usageFile: File get() = File(dataDir, "usage.json")
    private val requestLogFile: File get() = File(dataDir, "requests.jsonl")

    @Volatile
    var config: GatewayConfig = GatewayConfig()
        private set

    private val authCache = ConcurrentHashMap<String, AuthEntry>()
    private val stateCache = ConcurrentHashMap<String, AccountState>()

    // ---------------- 配置 ----------------

    /**
     * 读取配置。
     *
     * 修复点：原实现解析失败时直接 `getOrDefault(GatewayConfig())`——用户配置全丢，
     * 且**新生成的 config 里 api_key 为空**（等于把局域网鉴权关掉）。现在：
     *  1) 先备份损坏文件（config.json.bad），便于事后排查；
     *  2) 沿用上一次内存中的 config（至少保住 api_key 与用户设置）；
     *  3) 明确写日志，不再静默降级。
     */
    @Synchronized
    fun loadConfig(): GatewayConfig {
        val rawText = if (configFile.exists()) runCatching { configFile.readText() }.getOrDefault("") else ""
        var cfg = if (configFile.exists()) {
            runCatching { AppJson.decodeFromString(GatewayConfig.serializer(), rawText) }
                .getOrElse { e ->
                    LogBus.log("[sys] config.json 解析失败（${e.message}），已备份并沿用上次配置")
                    runCatching { configFile.copyTo(File(root, "config.json.bad"), overwrite = true) }
                    if (config.api_key.isNotBlank()) config.copy()
                    else GatewayConfig(api_key = Ids.apiKey())
                }
        } else {
            val fresh = GatewayConfig(api_key = Ids.apiKey())
            saveConfig(fresh)
            fresh
        }
        // 配置迁移（只在升级 ui_rev 时执行一次，之后**不再改动用户选择**）：
        //  - ui_rev < 2（1.3.1 之前）：把「动态取色」默认关掉（用户反馈默认莫奈取色不合适）；
        //  - ui_rev < 3（1.4.5 之前）且主题仍是「深色」：那时的默认值就是「深色」，历史配置里
        //    无法区分「用户主动选深色」与「从没动过」，而本版要求把默认值改成「跟随系统」，
        //    因此按需求做一次性迁移；确实想固定深色的用户，在设置里再选一次即可。
        //
        // 判据必须取**原始 JSON**：缺键时 kotlinx 会用「当前默认值」填充，解码结果无法区分
        // 「没写过」和「写过 3」—— 这正是上一版用 rawText.contains(...) 的原因，这里保持同思路、
        // 但改成读键值，避免同名字符串出现在其它字段里时误判。
        if (rawText.isNotEmpty()) {
            val uiObj = runCatching { AppJson.parseToJsonElement(rawText).jsonObject["ui"]?.objOrNull() }.getOrNull()
            val storedRev = runCatching { (uiObj?.get("ui_rev") as? JsonPrimitive)?.content?.toIntOrNull() }.getOrNull() ?: 0
            val storedTheme = runCatching { (uiObj?.get("theme") as? JsonPrimitive)?.content }.getOrNull()
            val needMonetOff = storedRev < 2
            val needAutoTheme = storedRev < 3 && (storedTheme == null || storedTheme == "dark")
            if (needMonetOff || needAutoTheme) {
                cfg = cfg.copy(ui = cfg.ui.copy(
                    theme = if (needAutoTheme) "system" else cfg.ui.theme,
                    monet = if (needMonetOff) false else cfg.ui.monet,
                    ui_rev = 3,
                ))
                LogBus.log("[sys] 配置迁移：主题=${cfg.ui.theme}，动态取色=${cfg.ui.monet}（ui_rev→3）")
                runCatching { saveConfig(cfg) }
            }
        }
        config = cfg
        return cfg
    }

    @Synchronized
    fun saveConfig(cfg: GatewayConfig) {
        config = cfg
        atomicWrite(configFile, PrettyJson.encodeToString(GatewayConfig.serializer(), cfg))
    }

    fun configJson(): JsonObject =
        PrettyJson.encodeToJsonElement(GatewayConfig.serializer(), config) as JsonObject

    // ---------------- 账号 ----------------

    fun loadAuths(): List<AuthEntry> {
        authCache.clear()
        authDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
            runCatching {
                val e = AppJson.decodeFromString(AuthEntry.serializer(), f.readText())
                if (e.uid.isNotBlank()) authCache[e.uid] = e
            }.onFailure { LogBus.log("[sys] 读取账号失败 ${f.name}: ${it.message}") }
        }
        return authCache.values.toList()
    }

    fun auths(): List<AuthEntry> = authCache.values.toList()

    fun getAuth(uid: String): AuthEntry? = authCache[uid]

    fun saveAuth(e: AuthEntry) {
        authCache[e.uid] = e
        atomicWrite(File(authDir, "${e.uid}.json"), AppJson.encodeToString(AuthEntry.serializer(), e))
    }

    fun removeAuth(uid: String) {
        authCache.remove(uid)
        File(authDir, "$uid.json").delete()
        stateCache.remove(uid)
        saveState()
    }

    // ---------------- 池状态 ----------------

    @Volatile
    private var state: PoolStateFile = PoolStateFile()

    // 与 saveState() 用**同一把锁**：loadState 会整体重建 state / stateCache，
    // 若不加锁，就可能与请求线程的 putState + saveState 交错 —— 造成丢写，
    // 甚至把刚被删除的状态重新"复活"回来。（@Synchronized 可重入，内部不会死锁。）
    @Synchronized
    fun loadState(): PoolStateFile {
        state = if (stateFile.exists()) {
            runCatching { AppJson.decodeFromString(PoolStateFile.serializer(), stateFile.readText()) }
                .getOrElse { e ->
                    // 与 loadConfig 同口径：**备份损坏文件 + 沿用上次内存状态 + 明确告警**。
                    // 原实现是 `getOrDefault(PoolStateFile())` —— 静默清空，于是 state.json
                    // 一旦半写/损坏，所有账号的禁用状态、熔断计数、签到时刻、模型冷却台账
                    // 会一起归零，且不留任何痕迹。
                    LogBus.log("[sys] state.json 解析失败（${e.message}），已备份并沿用上次状态")
                    runCatching { stateFile.copyTo(File(root, "state.json.bad"), overwrite = true) }
                    state
                }
        } else PoolStateFile()
        stateCache.clear()
        stateCache.putAll(state.accounts)
        return state
    }

    fun stateOf(uid: String): AccountState =
        stateCache.getOrPut(uid) { state.accounts[uid] ?: AccountState(uid = uid) }

    fun putState(s: AccountState) { stateCache[s.uid] = s }

    @Synchronized
    fun saveState(sticky: Map<String, com.wb.api.model.StickyBind> = state.sticky) {
        state = PoolStateFile(accounts = stateCache.toMap(), sticky = sticky, usage_seq = state.usage_seq)
        atomicWrite(stateFile, AppJson.encodeToString(PoolStateFile.serializer(), state))
    }

    // ---------------- 用量 ----------------

    @Synchronized
    fun appendUsage(rec: UsageRecord) {
        runCatching { usageFile.appendText(AppJson.encodeToString(UsageRecord.serializer(), rec) + "\n") }
    }

    fun readUsage(): List<UsageRecord> {
        if (!usageFile.exists()) return emptyList()
        return usageFile.readLines().mapNotNull {
            runCatching { AppJson.decodeFromString(UsageRecord.serializer(), it) }.getOrNull()
        }
    }

    // ---------------- 请求归档写入 ----------------
    //
    // 性能与稳定性（本次重构）：
    //  1. 原实现**每行** `appendText`（open → write → close 三次系统调用），高频请求下 syscall 开销可观；
    //     现在常驻一个 8KB BufferedWriter，按「累计 8 行 或 距上次 flush 超过 1s」批量落盘。
    //  2. 原实现超限裁剪时 `readLines()` 把整个文件读成 `List<String>`——上限 100MB 时
    //     峰值堆占用可达数百 MB（每个 String 还有对象头），是明确的 OOM/GC 抖动源；
    //     现在改为**按字节定位 + 流式拷贝**到临时文件再改名，内存占用与文件大小无关。
    //  3. 裁剪是字节级的，不经过字符解码，因此不会破坏多字节（中文）内容。
    //
    // 代价：进程被系统直接杀死时，最多丢失尚未 flush 的 8 行 / 1 秒内容（归档是排查用途，可接受）。
    // 停止服务 / 导出 / 读取 / 删除前都会先 flush，正常路径不丢数据。

    private var logWriter: java.io.BufferedWriter? = null
    private var logPending = 0
    private var logLastFlush = 0L
    private val logLock = Any()

    /** 攒够这么多行就落盘。 */
    private val flushLines = 8

    /** 或距上次落盘超过这么久就落盘（保证低流量下也不会长时间只停留在缓冲里）。 */
    private val flushIntervalMs = 1000L

    private fun openLogWriterLocked(): java.io.BufferedWriter? = runCatching {
        logWriter ?: java.io.BufferedWriter(
            java.io.OutputStreamWriter(java.io.FileOutputStream(requestLogFile, true), Charsets.UTF_8),
            8 * 1024,
        ).also { logWriter = it }
    }.getOrNull()

    /** 追加一条请求归档记录（见上文的缓冲与裁剪策略）。 */
    fun appendRequestLog(line: String) {
        if (!config.logging.request_archive_enabled) return
        synchronized(logLock) {
            runCatching {
                val w = openLogWriterLocked()
                if (w == null) {
                    requestLogFile.appendText(line + "\n")
                    return
                }
                w.append(line).append('\n')
                logPending++
                val now = System.currentTimeMillis()
                if (logPending >= flushLines || now - logLastFlush >= flushIntervalMs) {
                    w.flush()
                    logPending = 0
                    logLastFlush = now
                    trimIfOversizeLocked()
                }
            }
        }
    }

    /** 立即把缓冲区落盘（读取 / 导出 / 停止服务前调用）。 */
    fun flushRequestLog() {
        synchronized(logLock) {
            runCatching {
                logWriter?.flush()
                logPending = 0
                logLastFlush = System.currentTimeMillis()
            }
        }
    }

    /** 关闭归档句柄（服务停止时调用；之后再写入会自动重开）。 */
    fun closeRequestLog() {
        synchronized(logLock) {
            runCatching { logWriter?.flush(); logWriter?.close() }
            logWriter = null
            logPending = 0
        }
    }

    /**
     * 超过容量上限 → 按字节裁剪到 80%（最旧优先丢弃）。
     *
     * **流式**实现：定位到「保留窗口起点」后字节级跳过被截断的半行，再把剩余字节整块拷到
     * 临时文件并原子改名。全程不把文件读进内存。
     */
    private fun trimIfOversizeLocked() {
        val maxBytes = config.logging.request_archive_max_mb.coerceAtLeast(1).toLong() * 1024 * 1024
        if (!requestLogFile.exists() || requestLogFile.length() <= maxBytes) return
        val keepTarget = (maxBytes * 8 / 10).coerceAtLeast(64 * 1024)
        val keepFrom = (requestLogFile.length() - keepTarget).coerceAtLeast(0)
        runCatching { logWriter?.flush(); logWriter?.close() }
        logWriter = null
        val tmp = File(requestLogFile.parentFile, requestLogFile.name + ".tmp.trim")
        runCatching {
            java.io.RandomAccessFile(requestLogFile, "r").use { raf ->
                raf.seek(keepFrom)
                // 跳过被截断的半行（逐字节找换行；字节级操作，不解码，中文不会损坏）
                if (keepFrom > 0) {
                    var b = raf.read()
                    while (b != -1 && b != '\n'.code) b = raf.read()
                }
                java.io.FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = raf.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            if (!tmp.renameTo(requestLogFile)) {
                requestLogFile.delete()
                if (!tmp.renameTo(requestLogFile)) LogBus.log("[sys] 归档裁剪改名失败，保留原文件")
            }
        }.onFailure { LogBus.log("[sys] 归档裁剪失败：${it.message}") }
        runCatching { if (tmp.exists()) tmp.delete() }
    }

    fun readRequestLogs(limit: Int = 200): List<String> {
        flushRequestLog()
        if (!requestLogFile.exists()) return emptyList()
        return requestLogFile.readLines().takeLast(limit)
    }

    /**
     * 归档条数。
     *
     * 性能：UI 每 2 秒刷新一次统计，而**统计只是展示**——原实现每次都 `readLines()` 整份文件
     * （100MB 上限时是每 2 秒一次几百 MB 的堆分配 + 全量解码），属于典型的自伤式开销。
     * 现在：① 流式按行计数（内存恒定，不构造 List<String>）；② 结果缓存 30 秒。
     * 删除归档后由 [clearRequestLog] 立即失效。
     */
    private var logCountCache: Int = 0
    private var logCountAt: Long = 0L

    fun requestLogCount(): Int {
        val now = System.currentTimeMillis()
        if (now - logCountAt < 30_000 && logCountAt > 0) return logCountCache
        flushRequestLog()
        logCountCache = if (requestLogFile.exists()) countLines(requestLogFile) else 0
        logCountAt = now
        return logCountCache
    }

    private fun countLines(f: File): Int = runCatching {
        java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(f), Charsets.UTF_8), 64 * 1024)
            .use { r ->
                var n = 0
                while (r.readLine() != null) n++
                n
            }
    }.getOrDefault(0)

    /** 归档文件字节数（0 = 还没有归档）。 */
    fun requestLogSizeBytes(): Long = runCatching { if (requestLogFile.exists()) requestLogFile.length() else 0L }.getOrDefault(0L)

    /** 归档文件最后写入时间（epoch 秒，0 = 不存在）。 */
    fun requestLogModifiedAt(): Long =
        runCatching { if (requestLogFile.exists()) requestLogFile.lastModified() / 1000 else 0L }.getOrDefault(0L)

    /**
     * 把归档原文流式拷给调用方（导出到设备存储用）。
     * 用流而不是 readText：归档上限默认 100MB，整段读进内存会 OOM。
     */
    fun copyRequestLogTo(out: java.io.OutputStream): Long {
        flushRequestLog()   // 先落盘，否则导出的文件会缺最后几行
        if (!requestLogFile.exists()) return 0L
        return requestLogFile.inputStream().use { it.copyTo(out) }
    }

    /**
     * 删除归档文件（含清理过程中残留的临时文件）。
     * 只动 `requests.jsonl`，不触碰 accounts/state/usage，删完自动重建空文件。
     */
    @Synchronized
    fun clearRequestLog(): Boolean {
        // 必须先关掉写句柄：否则它仍指向被删除的 inode，后续 append 会写进一个
        // 「看不见的幽灵文件」，表现为「删了之后导出又是空的」。
        closeRequestLog()
        val deleted = runCatching { !requestLogFile.exists() || requestLogFile.delete() }.getOrDefault(false)
        runCatching {
            requestLogFile.parentFile?.listFiles { f -> f.name.startsWith("requests.jsonl.tmp") }?.forEach { it.delete() }
            if (deleted) requestLogFile.createNewFile()
        }
        logCountCache = 0
        logCountAt = 0
        return deleted
    }

    // ---------------- 工具 ----------------

    private val tmpSeq = AtomicLong(0)

    /** 原子写：先写唯一临时文件再改名。临时名带序号，避免并发写同一目标文件互相踩踏。 */
    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp" + tmpSeq.incrementAndGet())
        runCatching {
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                // 同一文件系统内 renameTo 应当原子成功；失败通常意味着目标已存在且不可直接替换。
                // 退化为「先删目标再改名」，避免直接 `writeText` 覆盖 —— 那会让并发读者看到半截内容。
                runCatching { if (target.exists()) target.delete() }
                if (!tmp.renameTo(target)) target.writeText(content)
            }
        }.onFailure { LogBus.log("[sys] 写入 ${target.name} 失败：${it.message}") }
        runCatching { if (tmp.exists()) tmp.delete() }
    }
}

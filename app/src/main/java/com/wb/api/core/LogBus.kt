package com.wb.api.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class LogChannel(val label: String) { CHAT("对话"), TASK("任务"), SYS("系统") }

/**
 * 日志条目。
 *
 * [tsLabel] 在**创建时**格式化一次（本地时间 HH:mm:ss）。
 * 为什么不让列表渲染时现算：`SimpleDateFormat.format()` 每次都要归零/计算内部 Calendar，
 * 在 `LazyColumn` 的 item 里逐帧调用是典型的掉帧来源；而且 `SimpleDateFormat` **非线程安全**，
 * 原来那个共享实例被多个工作线程（请求线程、调度线程）同时 format，
 * 存在取到错乱结果甚至抛异常的风险。改用 `java.time`（不可变、线程安全）并在写入时定稿。
 *
 * [seq] 是单调递增序号：给 LazyColumn 当 `key` 用。不能拿「时间戳 + 内容 hashCode」当 key
 * ——同一毫秒里推两条同样的日志就会撞 key 并**直接抛异常崩掉列表**。
 */
@androidx.compose.runtime.Immutable
data class LogEntry(
    val seq: Long,
    val ts: Long,
    val ch: LogChannel,
    val text: String,
    val tsLabel: String,
)

/**
 * 环形日志（容量 500，FIFO），按上游 ring.go 的规则分类到 chat/task/sys 三频道。
 */
object LogBus {
    private const val CAPACITY = 500
    private val buf = ArrayDeque<LogEntry>(CAPACITY)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    /**
     * 合并推送（coalesce）。
     *
     * 日志是**高频**写入源：一次流式请求会打 3~6 条，多请求并发时每秒可达几十条，而原实现
     * 每写一条就 `buf.toList()`（500 元素拷贝）+ 推一次 StateFlow —— 等于把「拷贝 + 下游重组」
     * 的成本乘上日志条数。这里把 120 ms 窗口内的多次写入合并成**一次**推送：
     * 仍然是完整快照（不丢、不乱序），只是把推送次数从「每条一次」降到「每 120 ms 一次」。
     */
    private const val COALESCE_MS = 120L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var publishScheduled = false
    private val publishTask = Runnable {
        synchronized(this) { publishScheduled = false }
        publish()
    }

    private fun schedulePublish() {
        if (publishScheduled) return
        publishScheduled = true
        main.postDelayed(publishTask, COALESCE_MS)
    }

    private fun publish() {
        val snapshot = synchronized(this) { buf.toList() }
        _entries.value = snapshot
    }

    private val taskPrefixes = listOf(
        "school ", "streak-bonus ", "travel ", "blackcat ", "lottery ",
        "checkin ", "activity ", "keepalive ", "balance ", "user-resource ",
        "panel: 任务", "panel: 一键", "panel: checkin", "panel: 手动",
        "panel: 队列", "panel: 券码", "[task]"
    )

    private val tsPrefix = Regex("^\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2} ")

    /** 线程安全的本地时间格式器（java.time 的实现是不可变的）。 */
    private val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

    private fun label(ts: Long): String =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(fmt)

    fun classify(text: String): LogChannel {
        val t = text.trimStart()
        if (t.startsWith("| #")) return LogChannel.CHAT
        if (taskPrefixes.any { t.startsWith(it) }) return LogChannel.TASK
        return LogChannel.SYS
    }

    /** 日志序号：给 LazyColumn 当稳定 key（同一毫秒内的重复日志也不会撞 key）。 */
    private val seqGen = java.util.concurrent.atomic.AtomicLong(0)

    @Synchronized
    fun log(text: String, ch: LogChannel? = null) {
        val clean = tsPrefix.replace(text, "")
        val channel = ch ?: classify(clean)
        val now = System.currentTimeMillis()
        val entry = LogEntry(seqGen.incrementAndGet(), now, channel, clean, label(now))
        buf.addLast(entry)
        while (buf.size > CAPACITY) buf.removeFirst()
        schedulePublish()
    }

    /** 最近一次**已推送**的快照（可能存在 120 ms 内的未合并增量）。 */
    fun snapshot(): List<LogEntry> = _entries.value

    /** 立即取当前缓冲的完整快照（不等合并窗口）；需要「读到刚写的那条」时用它。 */
    fun snapshotNow(): List<LogEntry> = synchronized(this) { buf.toList() }

    fun clear() {
        // ⚠️ `buf` 是普通 ArrayDeque：`log()`/`snapshotNow()`/`publish()` 都在 `synchronized(this)`
        // 内读写它，这里若在锁外 `buf.clear()`，与请求线程的 `log()` 并发就是数据竞争
        // （ConcurrentModificationException / 读写脏数据）。必须同锁。
        synchronized(this) {
            buf.clear()
            publishScheduled = false
        }
        main.removeCallbacks(publishTask)
        publish()
    }

    /** 兼容入口：需要临时格式化某个时间戳时使用（内部同一条线程安全实现）。 */
    fun timeLabel(ts: Long): String = label(ts)
}

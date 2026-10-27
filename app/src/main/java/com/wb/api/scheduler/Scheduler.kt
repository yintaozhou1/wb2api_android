package com.wb.api.scheduler

import com.wb.api.Gateway
import com.wb.api.core.Durations
import com.wb.api.core.LogBus
import com.wb.api.core.ScheduleCfg
import com.wb.api.model.AuthEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 定时任务调度：每分钟检查一次。
 *
 * 本轮修正的几处逻辑：
 *  1. **空账号直接返回**——原来会「跑一轮、刷一条『完成 0 个账号』日志」，看起来像执行成功；
 *  2. **定点签到 / 巡检补签两种模式**（`checkin_mode`），补齐用户诉求「检测到未签到就自动签到」；
 *  3. 幂等键改为 `任务名-日期-小时`，余额刷新单独用「时间槽」键，不再与小时键共用一张表（原来
 *     两者混用同一 map，清理时会互相误伤）；
 *  4. 每次 tick 顺手 `pool.flush()`（补写节流的状态）与 `stickyGC()`（清理过期会话粘性）；
 *  5. 未接入上游的扩展任务**如实标记为未实现**，不再假装「已完成」。
 */
object Scheduler {
    private var job: Job? = null
    private val ranToday = ConcurrentHashMap<String, String>() // key -> 幂等标记
    private var lastBalanceSlot = -1L
    private var lastCheckinScanMs = 0L

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            LogBus.log("scheduler started")
            // ⚠️ 这里**必须**接住异常并记日志：tick() 内部要跑签到 / 成长任务 / 余额刷新等
            // 多段上游调用，任何一处抛出未被捕获的异常，都会让这个 while(true) 协程**永久退出**
            // —— 症状是「所有定时任务静默停摆，且日志里什么都没有」，极难排查。
            // （本文件其它 runCatching 都带了 onFailure，此前只有主循环这两处漏掉。）
            runCatching { tick(first = true) }
                .onFailure { LogBus.log("[sys] scheduler tick 异常（首轮）：${it.message}") }
            while (true) {
                delay(60_000)
                runCatching { tick(first = false) }
                    .onFailure { LogBus.log("[sys] scheduler tick 异常：${it.message}") }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    fun isRunning(): Boolean = job != null

    private fun dayFmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun todayStartSec(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis / 1000
    }

    private fun tick(first: Boolean) {
        val cfg = Gateway.store.config
        // 顺手做两件低成本的维护：状态补写 + 粘性 GC。
        runCatching { Gateway.pool.flush() }
        runCatching { Gateway.pool.stickyGC() }

        val accounts = Gateway.store.auths()
        if (accounts.isEmpty()) return // 没有账号：任何定时任务都无意义

        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val s = cfg.schedule

        if (s.checkin_enabled) {
            if (s.checkin_mode == "missed") missedCheckin(s, first)
            else if (hour in s.checkin_hours) once("checkin", hour) { runBulk("签到") { checkinOne(it) } }
        }

        if (s.balance_refresh_enabled) {
            val minutes = s.balance_refresh_minutes.coerceAtLeast(1)
            val slot = System.currentTimeMillis() / (minutes * 60_000L)
            if (slot != lastBalanceSlot) {
                lastBalanceSlot = slot
                // 快过期窗口取自配置（与选号里的加权判据同源，避免两处口径漂移）。
                val soonSec = Durations.parse(cfg.pool.expiring_soon, 168 * 3600L)
                runBulk("余额刷新") { a ->
                    val b = Gateway.upstream.balanceDetailed(a, soonSec)
                    if (!b.known) throw IllegalStateException("上游未返回余额")
                    Gateway.pool.setCredits(a.uid, b.remain, b.earliestAt, b.earliestRemaining)
                    "ok"
                }
            }
        }

        // 定时任务区。**成长任务 / 活跃上报 / 连登管家均已接入真实上游接口**（1.5.1~1.5.2 完成）；
        // 仍未接入的只有猫猫旅行与夜猫子 —— 见下方对应分支，如实记为「未实现」，绝不伪装成功。
        if (s.growth_enabled && hour in s.growth_hours) once("growth", hour) {
            // `once` 的 block 不是挂起上下文 —— 与「补签巡检」同样处理：就地 runBlocking。
            // 该分支跑在 Scheduler 的 IO 调度协程里，阻塞的是调度线程而不是主线程。
            runCatching { runBlocking { com.wb.api.server.GrowthService.scanAll() } }
                .onFailure { LogBus.log("[成长] 定时扫描失败：${it.message}") }
        }
        // 活跃上报（已接入，对齐 report.go:145-147）：每号每天一条 chat_request_send，
        // 点亮 growth 连登 + 解锁 first_buddy。按天幂等。
        if (s.activity_enabled && hour in s.activity_hours) once("activity", hour) {
            runCatching { runBlocking { com.wb.api.server.GrowthService.activeReportAll() } }
                .onFailure { LogBus.log("[活跃] 上报失败：${it.message}") }
        }
        // 连登管家：跟随签到排程末尾（对齐 Go internal/scheduler/streak.go，挂在 RunCheckinNow 之后）。
        // 幂等：未解锁档位静默跳过、无抽奖次数不动。
        if (s.checkin_enabled && hour in s.checkin_hours) once("streak", hour) {
            runCatching { runBlocking { StreakService.runAll() } }
                .onFailure { LogBus.log("[连登] 管家失败：${it.message}") }
        }
        if (s.travel_enabled && hour in s.travel_hours) once("travel", hour) { LogBus.log("猫猫旅行：上游接口未接入，跳过") }
        if (s.keepalive_enabled && hour in s.keepalive_hours) once("keepalive", hour) { LogBus.log("token 保活：上游接口未接入，跳过") }
        if (s.blackcat_enabled && hour in s.blackcat_hours) once("blackcat", hour) { LogBus.log("夜猫子：上游接口未接入，跳过") }

        // 键的形态是 "<name>-<yyyy-MM-dd>-<hour>"，所以判「今天的键」必须用 **contains**：
        // 原实现用 endsWith(日期)，对任何键恒为 false → 触发阈值时把**当天所有**幂等标记
        // 一起删掉，同一小时的 checkin/growth/streak 会在下一分钟再跑一遍（重复请求上游）。
        if (ranToday.size > 500) {
            val today = dayFmt()
            ranToday.keys.removeIf { !it.contains("-$today-") }
        }
    }

    /**
     * 巡检补签：每 checkin_scan_minutes 分钟查一次「今天还没签到的账号」并自动补签。
     * 首次启动（first=true）且配置允许时立即执行一次，无需等满一个巡检周期。
     */
    private fun missedCheckin(s: ScheduleCfg, first: Boolean) {
        val intervalMs = s.checkin_scan_minutes.coerceAtLeast(5) * 60_000L
        val nowMs = System.currentTimeMillis()
        if (first) {
            if (!s.checkin_on_start) { lastCheckinScanMs = nowMs; return }
        } else if (nowMs - lastCheckinScanMs < intervalMs) {
            return
        }
        lastCheckinScanMs = nowMs

        val start = todayStartSec()
        val pending = Gateway.store.auths().filter { a ->
            // 企业号不在补签范围（上游不支持），否则会永远被算作「今日未签到」并反复空发请求。
            if (a.isEnterprise) return@filter false
            val st = Gateway.store.stateOf(a.uid)
            !st.disabled && st.last_checkin_at < start
        }
        if (pending.isEmpty()) {
            LogBus.log("补签巡检：全部账号今日已签到")
            return
        }
        LogBus.log("补签巡检：${pending.size} 个账号今日未签到，开始自动补签")
        pending.forEach { a ->
            runCatching { runBlocking { checkinOne(a) } }
                .onSuccess { LogBus.log("补签 ${a.nickname.ifBlank { a.uid.take(8) }}: $it") }
                .onFailure { LogBus.log("补签 ${a.nickname.ifBlank { a.uid.take(8) }} 失败: ${it.message}") }
        }
    }

    private fun once(name: String, hour: Int, block: () -> Unit) {
        val key = "$name-${dayFmt()}-$hour"
        if (ranToday.containsKey(key)) return
        ranToday[key] = "1"
        LogBus.log("$name triggered at $hour:00")
        runCatching { block() }
    }

    /** 签到单个账号，并**记下签到时刻**（供「未签到自动补签」判定）。 */
    suspend fun checkinOne(a: AuthEntry): String {
        // 企业版门控（对齐上游 scheduler 的 IsEnterprise 门控）：企业号没有个人成长体系，
        // `daily-checkin` 会被上游以 400 code 10001「企业账号不支持该操作」拒绝，
        // 因此不再对注定失败的端点空发请求，也不记账为失败。
        if (a.isEnterprise) return "企业版无签到（上游不支持）"
        val r = Gateway.upstream.checkin(a)
        Gateway.pool.markCheckin(a.uid)
        return r
    }

    private fun runBulk(label: String, op: suspend (AuthEntry) -> Any?) {
        val accounts = Gateway.store.auths()
        if (accounts.isEmpty()) return
        var ok = 0
        accounts.forEach { a ->
            runCatching { runBlocking { op(a) } }
                .onSuccess { ok++ }
                .onFailure { LogBus.log("$label ${a.nickname.take(6)}: ${it.message}") }
        }
        LogBus.log("panel: $label 完成 $ok/${accounts.size} 个账号")
    }
}

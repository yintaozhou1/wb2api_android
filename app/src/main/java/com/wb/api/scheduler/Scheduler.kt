package com.wb.api.scheduler

import com.wb.api.Gateway
import com.wb.api.core.Durations
import com.wb.api.core.LogBus
import com.wb.api.core.ScheduleCfg
import com.wb.api.model.AuthEntry
import com.wb.api.upstream.UpstreamClient
import com.wb.api.model.CredentialHealth
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

        // 凭证保活巡检：AT 剩余不足 30 分钟就提前续一次。这是「闲置一天后第一个请求先撞 401」
        // 的正解 —— 1.7.3 之前只在请求路径上临期续期，账号不请求就永远不会续，UI 上看到的
        // 「可用」其实是「本地记录早已过期」。
        runCatching { keepaliveSweep(s.keepalive_enabled) }
            .onFailure { LogBus.log("[token] 保活巡检异常：${it.message}") }

        // 定时任务区。**成长任务 / 活跃上报 / 连登管家 / token 保活均已接入真实上游接口**；
        // 仍未接入的是猫猫旅行（上游接口未公开）—— 如实记为「未实现」，绝不伪装成功。
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
        // 整点全量保活：把「还没到巡检窗口但用户已配置」的号也刷一遍（幂等：不需要续期的直接跳过）。
        if (s.keepalive_enabled && hour in s.keepalive_hours) once("keepalive", hour) {
            runCatching { keepaliveAll(force = true) }
                .onFailure { LogBus.log("[token] 整点保活失败：${it.message}") }
        }

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
        // 先登记再执行：block 抛异常时也不该在同一小时内反复重试（上游动作多为幂等，
        // 但重试会把日志刷满、并发窗口内还可能重复上报）。
        ranToday[key] = "1"
        LogBus.log("$name triggered at $hour:00")
        // 必须记失败：否则日志里只剩「triggered」，看起来「跑了但没结果」。
        runCatching { block() }.onFailure { LogBus.log("[sys] $name 执行失败：${it.message}") }
    }

    /** 签到单个账号，并**记下签到时刻**（供「未签到自动补签」判定）。 */
    suspend fun checkinOne(a: AuthEntry): String {
        // 企业版门控（对齐上游 scheduler 的 IsEnterprise 门控）：企业号没有个人成长体系，
        // `daily-checkin` 会被上游以 400 code 10001「企业账号不支持该操作」拒绝，
        // 因此不再对注定失败的端点空发请求，也不记账为失败。
        if (a.isEnterprise) return "企业版无签到（上游不支持）"
        val r = Gateway.upstream.checkin(a)
        // 「处理中」不是终态：不记签到时刻，否则今天不会再补签、这一天的积分就丢了。
        if (r == "ok" || r == "已签到") Gateway.pool.markCheckin(a.uid)
        return r
    }

    // ---------- 凭证保活 ----------

    /** 巡检节流：15 分钟一轮（AT 有效期 1 小时，15 分钟粒度足够早，又不会把上游打成刷子）。 */
    private val keepaliveSweepMs = 15 * 60_000L

    /** 单轮最多续多少个号：避免多账号时一次性把 refresh 打成尖峰。 */
    private val maxKeepalivePerSweep = 8

    private var lastKeepaliveMs = 0L

    /** 续期失败的短期退避（uid → 可重试时刻 ms）：坏号不要每轮都撞一次上游。 */
    private val keepaliveBackoff = ConcurrentHashMap<String, Long>()

    /**
     * 保活巡检（每 tick 调用，内部按 [keepaliveSweepMs] 节流）。
     *
     * 只挑「AT 剩余 < 30 分钟且还有 RT、RT 未过期」的号；跳过禁用/暂停。
     * 续期成功 → 落盘 + 池重载（UI 的「凭证过期」会立刻回到「可用」）；
     * 明确被拒 → 标记 session dead（UI 显示「凭证失效」，并提示重新登录）；
     * 无结论 → 30 分钟内不重试（网络抖动不该变成每 15 分钟一次的上游请求）。
     */
    private fun keepaliveSweep(enabled: Boolean) {
        if (!enabled) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastKeepaliveMs < keepaliveSweepMs) return
        lastKeepaliveMs = nowMs
        // 清掉已到期的退避项，避免 map 无限增长。
        keepaliveBackoff.entries.removeIf { it.value <= nowMs }
        val now = nowMs / 1000
        val targets = Gateway.store.auths().asSequence()
            .filter { a ->
                val st = Gateway.store.stateOf(a.uid)
                !st.disabled && (keepaliveBackoff[a.uid] ?: 0L) <= nowMs
            }
            .filter { a ->
                CredentialHealth.needsRenew(
                    a.access_token, a.access_expires_at,
                    a.refresh_token, a.refresh_expires_at, now, 1800,
                )
            }
            .take(maxKeepalivePerSweep)
            .toList()
        if (targets.isEmpty()) return
        LogBus.log("[token] 保活巡检：${targets.size} 个账号 AT 即将到期，开始续期")
        var ok = 0
        targets.forEach { a ->
            val r = runCatching { runBlocking { Gateway.upstream.keepalive(a, 1800) } }
                .getOrElse { UpstreamClient.KeepaliveResult("unknown", it.message ?: "续期异常") }
            when (r.state) {
                "renewed" -> {
                    r.renewed?.let { Gateway.store.saveAuth(it) }
                    Gateway.pool.reload()
                    ok++
                }
                "dead" -> Gateway.pool.markSessionDead(a.uid)
                "unknown" -> keepaliveBackoff[a.uid] = nowMs + 30 * 60_000L
                // ok / skipped：无需动作（ok = 会话仍有效但本次续期没成，等下一轮自然重试）
            }
            LogBus.log("[token] uid=${a.uid.take(8)} ${r.state}：${r.message}")
        }
        LogBus.log("[token] 保活完成 $ok/${targets.size}")
    }

    /**
     * 全量保活（整点任务 / 面板 `keepalive_all`）。
     *
     * @param force 忽略「剩余 30 分钟」窗口：用于用户主动点击「全部保活」时把每个能续的号都续一次。
     *              仍然跳过「不需要续期」的号（RT 缺失 / RT 已过期 / AT 仍够久），所以幂等。
     */
    fun keepaliveAll(force: Boolean = false): Int {
        val now = System.currentTimeMillis() / 1000
        val window = if (force) 3600L else 1800L
        var ok = 0
        Gateway.store.auths().forEach { a ->
            val st = Gateway.store.stateOf(a.uid)
            if (st.disabled) return@forEach
            val r = runCatching { runBlocking { Gateway.upstream.keepalive(a, window) } }.getOrNull() ?: return@forEach
            if (r.state == "renewed") {
                r.renewed?.let { Gateway.store.saveAuth(it) }
                ok++
            } else if (r.state == "dead") {
                Gateway.pool.markSessionDead(a.uid)
            }
        }
        if (ok > 0) Gateway.pool.reload()
        LogBus.log("[token] 全量保活：$ok 个账号已续期")
        return ok
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

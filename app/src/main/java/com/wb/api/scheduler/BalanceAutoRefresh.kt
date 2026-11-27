package com.wb.api.scheduler

import com.wb.api.Gateway
import com.wb.api.core.LogBus
import com.wb.api.server.CreditsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 余额「请求后冷静期」自动刷新（1.7.5 取代原「每 N 分钟定时刷新」）。
 *
 * 语义（用户口径）：一个请求**成功结束**后开始倒计时；冷静期（默认 15s，可选 5/15/30/60s）
 * 内**没有**新的成功请求，就刷新一次额度；冷静期内又来了请求，倒计时重新开始 —— 于是
 * 一轮密集对话只会刷新一次，不会每个请求都打一次余额端点。
 *
 * 为什么不是定时轮询：
 *  - 余额只在「有请求」时才会变，没有请求的时段刷新纯属空转（N 个账号 × 每 5 分钟）；
 *  - 反过来，用户刚聊完正是最想知道「还剩多少」的时刻，固定周期最长要等 5 分钟才更新。
 * 事件驱动 + 冷静期同时解决这两件事（长时段无请求的兜底见 Scheduler 的每小时补偿刷新：
 * 上游每日发放/到期与请求无关，不能完全依赖请求驱动）。
 *
 * 刷新范围只是**这一轮真正服务过请求的账号**（[dirty] 集合），通常 1~2 个；多账号池不会
 * 因为一次对话把 N 个号全扫一遍。冷启动（进程首次启动）另做一次全量刷新：那时还没有任何
 * 请求，用户打开 App 想看到的就是完整余额。
 *
 * 实现要点：
 *  - 只维护**一个**等待协程（[waiter]），每次请求只更新 `lastActivityMs` 并把它叫醒，
 *    不用「每次 cancel 再 launch」—— 后者会在刷新进行到一半时把协程取消掉；
 *  - 计时用**单调时钟**（`System.nanoTime`）：挂钟被 NTP/用户改时间拨动时，冷静期不该
 *    跟着变成一天或瞬间归零；
 *  - 「取批次」与「判冷静期」在**同一个**临界区完成：分两个锁会让刚进来的请求绕过冷静期；
 *  - 刷新自身也计入冷静期（刷新耗时超过冷静期时，不会背靠背再刷一轮）。
 */
object BalanceAutoRefresh {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    /** 本轮待刷新的账号（LinkedHashSet：顺带保留首次服务顺序，日志可读）。 */
    private val dirty = LinkedHashSet<String>()

    /** 最近一次「成功请求结束」的单调时刻（ms）。冷静期从它开始算。 */
    private var lastActivityMs = 0L

    /** 唯一等待协程；为空表示当前没有待办。 */
    private var waiter: Job? = null

    /** 冷启动全量刷新每个进程只做一次。 */
    private val coldStartDone = AtomicBoolean(false)

    /** 冷启动刷新延迟：让 UI 首帧与网关监听先完成，别和启动路径抢上游连接。 */
    private const val COLD_START_DELAY_MS = 2_500L

    /** 冷静期可选值（秒）——UI 与配置校验共用这一处，避免两边各写一份。 */
    val COOLDOWN_CHOICES = listOf(5, 15, 30, 60)
    const val DEFAULT_COOLDOWN_SEC = 15

    /** 单调时钟（ms）：不受系统改时间影响，与 `delay` 的时间源一致。 */
    private fun monoMs(): Long = System.nanoTime() / 1_000_000

    private fun enabled(): Boolean = Gateway.store.config.schedule.balance_refresh_enabled

    /**
     * 实际生效的冷静期（秒）。
     *
     * 配置被手改成非法值（0/10/999）时收敛到默认值；UI 也用它显示，避免出现
     * 「高亮 5 秒、文案写 10 秒、运行时等 15 秒」三不一致。
     */
    fun effectiveCooldownSec(): Int =
        normalizeCooldown(Gateway.store.config.schedule.balance_refresh_cooldown)

    /** 冷静期取值收敛（UI 与运行时共用这一条规则）。 */
    fun normalizeCooldown(sec: Int): Int = if (sec in COOLDOWN_CHOICES) sec else DEFAULT_COOLDOWN_SEC

    private fun cooldownMs(): Long = effectiveCooldownSec() * 1000L

    /**
     * 一个请求**成功结束**时调用（[com.wb.api.server.ChatHandler] 的 runPipeline finally）。
     *
     * 失败请求不计入：它们不改余额，让它重置倒计时只会把刷新无限推后。
     */
    fun noteRequest(uid: String) {
        if (uid.isBlank() || !enabled()) return
        synchronized(lock) {
            dirty += uid
            lastActivityMs = monoMs()
            if (waiter?.isActive != true) waiter = scope.launch { drain() }
        }
    }

    /**
     * 进程冷启动后刷新一次全量额度（每个进程一次）。
     *
     * 放在 Application.onCreate 之后调用：此时 [Gateway.store] 已初始化，但网关可能还没启动 ——
     * 这也正合适：用户打开 App 就是为了看余额，不必等网关先跑起来。
     */
    fun onProcessStart() {
        // 开关关闭时**不消耗**这次机会：用户稍后打开开关仍能享受本进程内的冷启动刷新。
        if (!enabled()) return
        if (!coldStartDone.compareAndSet(false, true)) return
        scope.launch {
            delay(COLD_START_DELAY_MS)
            if (!enabled()) return@launch
            val accounts = Gateway.store.auths().filter { !Gateway.store.stateOf(it.uid).disabled }
            if (accounts.isEmpty()) return@launch
            val r = try {
                CreditsService.refresh(accounts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                LogBus.log("[balance] 冷启动刷新异常：${e.message}")
                return@launch
            }
            LogBus.log("[balance] 冷启动刷新 ${accounts.size} 个账号：${CreditsService.summary(r)}")
        }
    }

    /** 停机时清空待办（GatewayService.onDestroy）。冷启动标记保持——同一进程不重复全量刷新。 */
    fun stop() {
        synchronized(lock) {
            dirty.clear()
            lastActivityMs = 0L
            waiter?.cancel()
            waiter = null
        }
    }

    /**
     * 等待协程主体：循环「等够冷静期 → 取走待刷新集合 → 刷新」。
     *
     * 刷新期间若又有请求进来，[dirty] 会被重新填上，循环回到等待分支再等一个冷静期 ——
     * 既不会丢这次请求，也不会在同一分钟内反复刷。
     */
    private suspend fun drain() {
        // 自己这一代的 Job 引用：退出时只清「还是自己」的 waiter，绝不误清后来者。
        val self = currentCoroutineContext()[Job]
        var retried = false
        while (true) {
            // 「判冷静期 + 取批次」必须在**同一个**临界区：分成两段时，刚进来的请求会在
            // 两段之间把 dirty 填上，于是这次刷新立刻绕过冷静期（用户看到「刚发完请求就刷新了」）。
            // Triple(等待毫秒, 本批账号, 是否退出)：用非空类型返回，避免可空 Pair 的智能转换问题。
            val step: Triple<Long, List<String>?, Boolean> = synchronized(lock) {
                val remain = lastActivityMs + cooldownMs() - monoMs()
                when {
                    remain > 0 -> Triple(remain, null, false)
                    // 冷静期已满但没有待办（或用户刚关掉开关）：只清自己这一代的引用再退出。
                    !enabled() || dirty.isEmpty() -> {
                        if (waiter === self) waiter = null
                        Triple(0L, null, true)
                    }
                    else -> Triple(0L, dirty.toList().also { dirty.clear() }, false)
                }
            }
            if (step.third) return
            val b = step.second
            if (b == null) {
                delay(step.first)
                continue
            }
            val r = try {
                CreditsService.refreshUids(b)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                LogBus.log("[balance] 请求后冷静期刷新异常：${e.message}")
                CreditsService.Outcome(0, b.size, 0.0, b)
            }
            LogBus.log("[balance] 请求后冷静期刷新（${b.size} 个账号）：${CreditsService.summary(r)}")
            val done = synchronized(lock) {
                // 刷新自身计入冷静期：刷新耗时 > 冷静期时不要背靠背再打一轮。
                lastActivityMs = monoMs()
                if (r.failed.isNotEmpty() && !retried) {
                    // 失败补一次（有界：retried 只允许一次，不会失败→回填→再失败的循环）。
                    retried = true
                    dirty.addAll(r.failed)
                } else if (r.failed.isEmpty()) {
                    retried = false
                }
                if (dirty.isEmpty()) {
                    if (waiter === self) waiter = null
                    true
                } else false
            }
            if (done) return
        }
    }
}

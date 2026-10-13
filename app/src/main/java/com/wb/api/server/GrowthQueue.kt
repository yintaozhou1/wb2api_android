// ⚠️ 未接入（代码审查 P1-1 标注）：本文件实现的是上游 taskcenter 的「任务队列」模型
// （并发夹取 / per-account 锁 / seq），但目前**没有任何调用方** —— 实际生效的成长链路是
// GrowthService.scanAll()（Scheduler / AppViewModel / PanelApi 三处入口）。
// 保留它是为了对齐上游结构，便于后续把 GrowthService 的执行改造成队列；在此之前请勿把
// 它当作「已在运行的能力」。
package com.wb.api.server

import com.wb.api.Gateway
import com.wb.api.core.LogBus
import com.wb.api.model.AuthEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * GrowthQueue.kt —— 成长任务「执行队列」。
 *
 * 依据：Go internal/panel/taskcenter.go（tasksRunQueue / startGrowthQueue / runQueueItems）。
 * 语义：
 *  - **账号内串行**（复用 per-account 互斥锁，与单任务互斥）；**账号间并发**，并发度 `concurrency`
 *    夹取 `[1,4]`（taskcenter.go:158-163）；
 *  - 启动时**先扫描组装待办**（只保留「未完成且可自动化」项）；无可执行待办则不入队；
 *  - `seq` 每启动一次 +1，供调用方区分「本轮」；已在执行时返回 `seq==-1`（冲突）；
 *  - 账号内先批量 `accept` 未报名任务（失败不阻塞），再逐项执行动作（对齐 taskcenter.go:319-345）。
 *
 * 移动端取舍说明（对齐任务书第 9 条授权）：桌面端用 `sync.WaitGroup` + 信号量 + 常驻 `queueState`
 * 做长驻队列；移动端简化为「一次性并发批次 + 内存状态快照」，并发度仍夹取 [1,4] 且保留 per-account
 * 锁与 seq，但**不提供跨进程持久队列**（App 退出即停）。这足以表达「账号内串行/账号间并发」的核心
 * 语义，也避免 Android 后台被系统杀进程时留下悬挂的常驻协程。
 */
object AccountLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun mutex(uid: String): Mutex = locks.getOrPut(uid) { Mutex() }

    /** 尝试获取账号锁（非阻塞）：拿到则执行 block 并返回其结果，拿不到返回 null（对齐 Go tryLockAccount）。 */
    suspend fun <T> withAccount(uid: String, block: suspend () -> T): T? {
        val m = mutex(uid)
        if (!m.tryLock()) return null
        return try {
            block()
        } finally {
            m.unlock()
        }
    }
}

object GrowthQueue {

    /** 队列执行单元（对齐 queueItem，taskcenter.go:118-126）。 */
    data class Item(val uid: String, val nickname: String, val code: String, var status: String = "pending", var message: String = "")

    /** 队列运行状态（对齐 queueState，taskcenter.go:130-137）。 */
    data class State(val running: Boolean, val total: Int, val conc: Int, val started: Boolean, val startedAt: Long, val seq: Int, val items: List<Item>)

    private val lock = Any()
    private var running = false
    private var seq = 0
    private var startedAt = 0L
    private var conc = 1
    private var items: List<Item> = emptyList()
    private var job: Job? = null

    fun status(): State = synchronized(lock) { State(running, items.size, conc, startedAt > 0, startedAt, seq, items) }

    fun isRunning(): Boolean = synchronized(lock) { running }

    /**
     * 启动队列；并发夹取 [1,4]。返回 `(started, total, seq)`：seq==-1 表示已在执行（冲突），
     * started=false 表示无待办。
     */
    suspend fun start(scope: CoroutineScope, concurrency: Int): Triple<Boolean, Int, Int> {
        synchronized(lock) { if (running) return Triple(false, 0, -1) }
        val c = concurrency.coerceIn(1, 4)
        val plan = buildPlan()
        if (plan.isEmpty()) return Triple(false, 0, 0)
        val newItems = plan.flatMap { (a, actions) -> actions.map { Item(a.uid, a.nickname, it.code) } }
        val mySeq: Int
        synchronized(lock) {
            running = true; seq += 1; mySeq = seq
            startedAt = System.currentTimeMillis(); conc = c; items = newItems
        }
        job = scope.launch(Dispatchers.IO) {
            try { runPlan(plan, c) } finally { synchronized(lock) { running = false } }
        }
        LogBus.log("[成长队列] 启动 seq=$mySeq 共 ${newItems.size} 项（并发 $c）")
        return Triple(true, newItems.size, mySeq)
    }

    /** 组装待办：门控账号 × 「未完成且可自动化」任务（对齐 startGrowthQueue 的扫描段）。 */
    private suspend fun buildPlan(): List<Pair<AuthEntry, List<GrowthAction>>> {
        val out = ArrayList<Pair<AuthEntry, List<GrowthAction>>>()
        for (a in GrowthService.gatedAccounts()) {
            val tasks = runCatching { Gateway.upstream.growthTasksMerged(a) }.getOrDefault(emptyList())
            if (tasks.isEmpty()) continue
            val pending = GrowthActions.ordered().filter { act ->
                val t = tasks.firstOrNull { GrowthService.taskCode(it) == act.code } ?: return@filter false
                !GrowthService.isClaimed(t) && !GrowthService.isLocked(t)
            }
            if (pending.isNotEmpty()) out.add(a to pending)
        }
        return out
    }

    /** 账号间并发（信号量=conc），账号内串行（per-account 锁）；先 accept 再逐项执行。 */
    private suspend fun runPlan(plan: List<Pair<AuthEntry, List<GrowthAction>>>, conc: Int) {
        val sem = Semaphore(conc)
        coroutineScope {
            for ((a, actions) in plan) {
                launch {
                    sem.withPermit {
                        val done = AccountLocks.withAccount(a.uid) {
                            val accepted = runCatching { GrowthService.acceptPendingAuth(a) }.getOrDefault(0)
                            if (accepted > 0) delay(GrowthService.REPORT_GAP_MS) // 给上游状态流转留时间
                            for (act in actions) {
                                markItem(a.uid, act.code, "running", "")
                                val o = GrowthService.runGrowthItem(a, act)
                                markItem(a.uid, act.code, o.status, o.message)
                            }
                        }
                        if (done == null) {
                            // 未拿到账号锁 → 该账号本轮跳过（对齐 Go tryLockAccount 失败标 skipped）。
                            for (act in actions) markItem(a.uid, act.code, "skipped", "账号正在执行其它任务")
                        }
                    }
                }
            }
        }
    }

    private fun markItem(uid: String, code: String, status: String, msg: String) {
        synchronized(lock) {
            items.firstOrNull { it.uid == uid && it.code == code }?.let { it.status = status; it.message = msg }
        }
    }
}

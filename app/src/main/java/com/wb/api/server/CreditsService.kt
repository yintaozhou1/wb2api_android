package com.wb.api.server

import com.wb.api.Gateway
import com.wb.api.core.Durations
import com.wb.api.core.LogBus
import com.wb.api.model.AuthEntry
import com.wb.api.upstream.Up
import kotlinx.coroutines.CancellationException

/**
 * 余额（积分）刷新的**唯一入口**。
 *
 * 为什么要有这一层：余额查询此前在四处各写一遍（账号详情页单号刷新、全量刷新、定时任务、
 * 面板 bulk 接口），每处都必须记得把「最早到期批次」一起写回池 —— 漏一处就让
 * `pool.prefer_expiring`（快到期额度优先消耗）静默失效。1.7.5 起统一走这里。
 *
 * 口径约定（与 [com.wb.api.upstream.UpstreamClient.balanceDetailed] 保持一处）：
 *  - `known = false`（上游 HTTP 200 但没有 Accounts 段）算**失败**，不是「余额 0」——
 *    当 0 写回会让池把这个号判成积分耗尽（硬冷却到次日 04:00），一次上游抖动就能
 *    把整池打成不可用；
 *  - 失败只记日志、不抛异常：批量刷新里一个号失败不该中断其余账号；失败的 uid 会随
 *    [Outcome.failed] 返回，由调用方（请求后冷静期刷新）决定是否补一次。
 */
object CreditsService {

    /** 一次批量刷新的结果（金额单位与池内一致：积分）。 */
    data class Outcome(
        val ok: Int,
        val fail: Int,
        val credits: Double,
        /** 本次失败的 uid（供调用方做一次有界补刷）。 */
        val failed: List<String> = emptyList(),
    ) {
        val total: Int get() = ok + fail
        val allFailed: Boolean get() = ok == 0 && fail > 0
    }

    /** 快过期窗口：与选号里的加权判据同源，避免两处口径漂移。 */
    private fun soonSec(): Long =
        Durations.parse(Gateway.store.config.pool.expiring_soon, 168 * 3600L)

    /**
     * 查询单个账号并写回池，失败**抛出**（消息可直接展示给用户）。
     *
     * 需要「失败原因」的调用方（详情页手动刷新）用这个；批量/自动刷新用 [refreshOne]。
     */
    suspend fun fetchIntoPool(a: AuthEntry): Double {
        val b = Gateway.upstream.balanceDetailed(a, soonSec())
        // known=false：上游 200 但没有 Accounts 段。绝不能当 0 写回（见文件头）。
        if (!b.known) throw IllegalStateException("上游未返回余额数据")
        // 带上最早到期批次：快过期额度才能被 prefer_expiring 优先消耗。
        Gateway.pool.setCredits(a.uid, b.remain, b.earliestAt, b.earliestRemaining)
        return b.remain
    }

    /** 刷新单个账号；失败返回 null 并记日志（批量路径不因一个号失败而中断）。 */
    suspend fun refreshOne(a: AuthEntry): Double? = try {
        fetchIntoPool(a)
    } catch (e: CancellationException) {
        // 协程取消必须原样抛出：runCatching 会把 CancellationException 当普通失败吞掉，
        // 让「已取消的刷新」继续逐个账号打日志、甚至抢走下一批待办。
        throw e
    } catch (e: Throwable) {
        LogBus.log("[balance] ${a.nickname.ifBlank { a.uid.take(8) }}: ${e.message}")
        null
    }

    /** 串行刷新一批账号（串行而非并发：余额端点对单账号有频控，且池写入本身很轻）。 */
    suspend fun refresh(accounts: List<AuthEntry>): Outcome {
        var ok = 0
        var sum = 0.0
        val failed = ArrayList<String>()
        for (a in accounts) {
            val v = refreshOne(a)
            if (v == null) failed += a.uid else { ok++; sum += v }
        }
        return Outcome(ok, failed.size, sum, failed)
    }

    /** 按 uid 刷新（请求后冷静期刷新的是「刚刚真正服务过请求」的那几个号）。 */
    suspend fun refreshUids(uids: Collection<String>): Outcome {
        if (uids.isEmpty()) return Outcome(0, 0, 0.0)
        val wanted = uids.toHashSet()
        // 禁用号不刷：与冷启动刷新同口径（禁用号不参与选号，余额没有意义）。
        return refresh(
            Gateway.store.auths().filter { it.uid in wanted && !Gateway.store.stateOf(it.uid).disabled },
        )
    }

    /** 统一文案：报「可用积分总和」，而不是只报刷新了几个账号（后者看不出结果）。 */
    fun summary(o: Outcome): String = when {
        o.total == 0 -> "本次无账号可刷新"
        else -> buildString {
            append("可用 ").append(Up.creditsText(o.credits)).append(" 积分")
            append("（").append(o.ok).append(" 个账号）")
            if (o.fail > 0) append(" · 失败 ").append(o.fail).append(" 个")
        }
    }
}

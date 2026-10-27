package com.wb.api.scheduler

import com.wb.api.Gateway
import com.wb.api.core.LogBus
import com.wb.api.core.objOrNull
import com.wb.api.core.primTextOrNull
import com.wb.api.model.AuthEntry
import kotlinx.serialization.json.JsonArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * StreakService.kt —— 连登管家（签到排程末尾自动执行）。
 *
 * 依据：Go internal/scheduler/streak.go（RunStreakBonusNow / streakBonusAccount）与
 * internal/upstream/{streak,blackcat,travel}.go。
 *
 * 闭环顺序（对齐 scheduler/streak.go:36-88）：
 *  ① 补签保连登（昨日漏签且有补签卡 → 用卡补上）；
 *  ② 领新手礼包（ClaimGift）→ 领活动补偿（ClaimCompensation），每号一次，无则静默；
 *  ③ 查连登完整状态 → 遍历档位（7d/14d/28d），`status != locked && != claimed` 才**兑换**；
 *  ④ 查抽奖次数 → **有次数全部抽完**，奖品记日志。
 *
 * 门控：跳过 `disabled` 与 `global`（scheduler/streak.go:22-31，D4 门控）。
 * 幂等：未解锁兑换上游返回 403（静默跳过），无次数则不动；重复运行无副作用。
 */
object StreakService {

    suspend fun runAll() {
        val accounts = Gateway.store.auths().filter { a ->
            val st = Gateway.store.stateOf(a.uid)
            !st.disabled && a.realm != "global" && a.access_token.isNotBlank()
        }
        if (accounts.isEmpty()) return
        var done = 0
        for (a in accounts) {
            if (runCatching { runOne(a) }.isSuccess) done++
        }
        LogBus.log("[连登] 管家完成 $done/${accounts.size} 个账号")
    }

    private suspend fun runOne(a: AuthEntry) {
        val tag = a.uid.take(8)
        // ① 补签保连登
        makeupYesterday(a, tag)
        // ② 新手礼包（每号一次，无则静默）。
        //    官方「活动补偿」端点已下线（上游 5367e78dc 删除 compensation.go）：继续打它
        //    只会每轮多一次必然失败的请求，日志里还会留下误导性的错误行。
        Gateway.upstream.claimGift(a)?.let { LogBus.log("streak-bonus $tag: 新手礼包 +${it}c") }

        // ③ 兑换所有已解锁档位
        val full = Gateway.upstream.growthStreakStatus(a)
        if (full == null) { LogBus.log("streak-bonus $tag: 连登状态拉取失败"); return }
        val rs = full["redemption_status"]?.objOrNull()
        val statuses = mapOf(
            "7d" to (rs?.get("tier_7d_status")?.primTextOrNull() ?: ""),
            "14d" to (rs?.get("tier_14d_status")?.primTextOrNull() ?: ""),
            "28d" to (rs?.get("tier_28d_status")?.primTextOrNull() ?: ""),
        )
        val tiers = rs?.get("tiers") as? JsonArray
        if (tiers != null) {
            for (te in tiers) {
                val o = te.objOrNull() ?: continue
                val tier = o["tier"]?.primTextOrNull() ?: continue
                val status = statuses[tier] ?: ""
                if (status == "locked" || status == "claimed") continue
                if (Gateway.upstream.growthRedeemTier(a, tier)) {
                    val c = o["credit"]?.primTextOrNull() ?: "0"
                    val e = o["energy"]?.primTextOrNull() ?: "0"
                    LogBus.log("streak-bonus $tag: ★ 兑换 $tier 档（+${c}c +${e}e）")
                }
            }
        }

        // ④ 抽完所有 chances
        val chances = Gateway.upstream.lotteryChances(a)
        if (chances <= 0) return
        for (i in 0 until chances) {
            val prize = Gateway.upstream.lotteryDraw(a) ?: break
            LogBus.log("streak-bonus $tag: 🎲 第${i + 1}抽 ${prize.take(220)}")
        }
        LogBus.log("streak-bonus $tag: 抽奖完成 $chances 次")
    }

    /** 昨日漏签且有补签卡 → 用卡补签（blackcat.go:98-126 + scheduler/streak.go:101-116）。 */
    private suspend fun makeupYesterday(a: AuthEntry, tag: String) {
        if (!runCatching { Gateway.upstream.heatmapYesterdayMissed(a) }.getOrDefault(false)) return
        val full = Gateway.upstream.growthStreakStatus(a) ?: return
        val bal = full["makeup_cards"]?.objOrNull()?.get("balance")?.primTextOrNull()?.toIntOrNull() ?: 0
        if (bal <= 0) return
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() - 86_400_000L))
        if (runCatching { Gateway.upstream.useMakeupCard(a, yesterday) }.getOrDefault(false)) {
            LogBus.log("streak-bonus $tag: ★ 已用补签卡补签 $yesterday（保连登）")
        }
    }
}

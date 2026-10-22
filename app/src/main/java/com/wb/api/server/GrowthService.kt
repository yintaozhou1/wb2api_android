package com.wb.api.server

import com.wb.api.Gateway
import com.wb.api.core.LogBus
import com.wb.api.core.objOrNull
import com.wb.api.core.primTextOrNull
import com.wb.api.model.AuthEntry
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/**
 * 成长任务服务：**扫描 → 报名（分片）→ 行为事件上报 → 异步回读 → 自动领奖**。
 *
 * 逐字对齐上游 workbuddy2api-panel：
 *  - 列表/报名/领奖：`internal/upstream/tasks.go` + `internal/panel/tasks.go`；
 *  - 自动化动作与回读：`internal/panel/autotask.go` + `internal/panel/taskcenter.go`。
 *
 * 与旧实现的差异（本次移植修正）：
 *  1. 领奖走 **Web 域**（`www.workbuddy.cn/activity/growth/tasks/<code>/claim`），不再是 chat 域的
 *     错误路径（差异 #3，见 `UpstreamClient.growthClaim`）；
 *  2. 列表合并 **mp 口径**并按 task_code 去重（差异 #15）；
 *  3. accept **分片 20 + 批间节流**，mp 任务 accept **回读验证登记生效**（差异 #5/#14）；
 *  4. 加入**行为事件上报 + 自动化动作表 + 有界轮询回读**（差异 #1/#2/#6）；
 *  5. 账号门控**以 Go 口径为准**：跳过 `disabled` 与 `global`，**不再过滤企业号**（差异 #13 —— Go
 *     成长链路未显式过滤 enterprise；旧 Android 版过滤企业号的语义被移除，见 scanAll 注释）。
 */
object GrowthService {

    // 节流/轮询常量（照抄 Go：autotask.go:241-243,745 / tasks.go:16-17 / taskcenter.go:317-326）
    const val REPORT_GAP_MS = 1050L
    const val ACCEPT_BATCH_GAP_MS = 1050L
    const val CLAIM_POLL_ATTEMPTS = 4
    const val CLAIM_POLL_GAP_MS = 3000L
    const val MP_ACTION_GAP_MS = 2000L

    /** 小程序限定任务码（对齐 Go mpTaskCodes，autotask.go:201-213）。 */
    val MP_CODES: Set<String> = setOf(
        "school_season",
        "Sequential_Tasks_1", "Sequential_Tasks_2", "Sequential_Tasks_3", "Sequential_Tasks_4",
        "Sequential_Tasks_5", "Sequential_Tasks_6", "Sequential_Tasks_7",
    )

    fun isMpCode(code: String): Boolean = code in MP_CODES

    /** 单账号的扫描结果（字段保持与旧 UI 兼容）。 */
    data class AccountResult(
        val uid: String,
        val nickname: String,
        val total: Int,
        val accepted: Int,
        val claimed: Int,
        val credit: Long,
        val energy: Long,
        val failed: Int = 0,
        val error: String? = null,
    )

    /** 全部账号的扫描汇总。 */
    data class Report(val results: List<AccountResult>) {
        val total: Int get() = results.sumOf { it.total }
        val accepted: Int get() = results.sumOf { it.accepted }
        val claimed: Int get() = results.sumOf { it.claimed }
        val credit: Long get() = results.sumOf { it.credit }
        val energy: Long get() = results.sumOf { it.energy }
        val failed: Int get() = results.sumOf { it.failed }
        val okAccounts: Int get() = results.count { it.error == null }
        fun summary(): String =
            if (results.isEmpty()) "没有可扫描的账号"
            else "成长任务：${okAccounts}/${results.size} 个账号扫描成功 · " +
                "报名 $accepted · 领奖 $claimed 项（+$credit 积分" +
                (if (energy > 0) " +$energy 能量" else "") + "）" +
                (if (failed > 0) " · $failed 项未成功（多为已完成/未达标）" else "")
    }

    /** 单任务执行结果（含是否触发领奖）。 */
    data class ItemOutcome(val status: String, val message: String, val credit: Long, val energy: Long, val claimedCode: Boolean)

    // ─────────────────────────── 任务字段解析（对齐 parseGrowthTasks，tasks.go:148-204）───────────────────────────

    fun str(o: JsonObject, vararg ks: String): String {
        for (k in ks) { val v = o[k]?.primTextOrNull(); if (!v.isNullOrBlank()) return v }
        return ""
    }

    fun long(o: JsonObject, vararg ks: String): Long {
        for (k in ks) { o[k]?.primTextOrNull()?.toLongOrNull()?.let { return it } }
        return 0L
    }

    fun taskCode(o: JsonObject): String = str(o, "task_code")

    /** `claimed = (accept_status=="claimed")`（tasks.go:185,203-204）。 */
    fun isClaimed(o: JsonObject): Boolean = str(o, "accept_status").lowercase() == "claimed"

    fun isLocked(o: JsonObject): Boolean = o["locked"]?.primTextOrNull()?.toBooleanStrictOrNull() ?: false

    fun acceptStatus(o: JsonObject): String = str(o, "accept_status")

    /** target：优先 `progress.target`，否则平铺（tasks.go:174-184）。 */
    fun target(o: JsonObject): Long {
        val v = o["progress"]?.objOrNull()?.get("target")?.primTextOrNull()?.toLongOrNull()
        return v ?: long(o, "target")
    }

    /** current：优先 `progress.current`，否则平铺。 */
    fun current(o: JsonObject): Long {
        val v = o["progress"]?.objOrNull()?.get("current")?.primTextOrNull()?.toLongOrNull()
        return v ?: long(o, "current")
    }

    /** `claimable = !claimed && target>0 && current>=target`（tasks.go:203）。 */
    fun claimable(o: JsonObject): Boolean = !isClaimed(o) && target(o) > 0 && current(o) >= target(o)

    // ─────────────────────────── 回读（异步计分轮询）───────────────────────────

    /** 默认口径按 code 定位任务。 */
    suspend fun taskByCode(auth: AuthEntry, code: String): JsonObject? {
        val ts = Gateway.upstream.growthTasks(auth)
        return ts.firstOrNull { taskCode(it) == code }
    }

    /** mp 口径按 code 定位任务（mp 列表是超集）。 */
    suspend fun taskByCodeMP(auth: AuthEntry, code: String): JsonObject? {
        val ts = Gateway.upstream.growthTasksMP(auth)
        return ts.firstOrNull { taskCode(it) == code }
    }

    /**
     * 回读任务进度，未达标则在有界预算内轮询等待（对齐 taskByCodeWaiting，autotask.go:248-270）：
     * 已达标立即返回；否则最多 [CLAIM_POLL_ATTEMPTS] 次、每次隔 [CLAIM_POLL_GAP_MS]（预算约 12s）。
     * 上游计分异步：上报后进度要数秒才刷新，一次性回读会漏领。
     */
    suspend fun taskByCodeWaiting(auth: AuthEntry, code: String): JsonObject? {
        var t = taskByCode(auth, code) ?: return null
        if (claimable(t) || isClaimed(t)) return t
        var i = 1
        while (i < CLAIM_POLL_ATTEMPTS) {
            delay(CLAIM_POLL_GAP_MS)
            val t2 = runCatching { taskByCode(auth, code) }.getOrNull()
            if (t2 != null) { t = t2; if (claimable(t) || isClaimed(t)) return t }
            i++
        }
        return t
    }

    /** 领奖：mp 码走 mp 口径（chat 域 + 400 降级 Web 域），其余走 Web 域。 */
    suspend fun claimTask(auth: AuthEntry, code: String): Pair<Long, Long>? =
        if (isMpCode(code)) Gateway.upstream.growthClaimMP(auth, code) else Gateway.upstream.growthClaim(auth, code)

    // ─────────────────────────── 报名（分片 + 回读验证）───────────────────────────

    /**
     * 报名该账号所有未报名任务：默认口径分片批量 accept；mp 码单独逐条 accept 并**回读验证**。
     * 对齐 Go acceptPendingTasks（tasks.go:86-143 / taskcenter.go:319-326）。
     */
    suspend fun acceptPendingAuth(auth: AuthEntry): Int {
        val tasks = runCatching { Gateway.upstream.growthTasksMerged(auth) }.getOrDefault(emptyList())
        if (tasks.isEmpty()) return 0
        val defaultCodes = ArrayList<String>()
        val mpCodes = ArrayList<String>()
        for (t in tasks) {
            if (isClaimed(t) || isLocked(t)) continue
            val st = acceptStatus(t).lowercase()
            if (st == "accepted" || st == "completed") continue
            val code = taskCode(t); if (code.isBlank()) continue
            if (isMpCode(code)) mpCodes.add(code) else defaultCodes.add(code)
        }
        var n = 0
        if (defaultCodes.isNotEmpty()) {
            n += Gateway.upstream.growthAcceptSharded(auth, defaultCodes)
            delay(REPORT_GAP_MS)
        }
        for (c in mpCodes) {
            if (acceptWithVerifyMP(auth, c)) n++
            delay(MP_ACTION_GAP_MS)
        }
        return n
    }

    /**
     * mp accept + 回读验证登记生效（对齐 acceptWithVerifyMP，autotask.go:290-304）：
     * 上游存在「200+OK 但未登记」形态（此时上报事件全部不归账），故以回读 `accept_status` 为准，
     * 未生效重试一次；判定 `!="not_accepted" && !=""`。
     */
    suspend fun acceptWithVerifyMP(auth: AuthEntry, code: String): Boolean {
        for (attempt in 1..2) {
            val ok = Gateway.upstream.growthAcceptMP(auth, listOf(code))
            if (!ok) continue
            delay(MP_ACTION_GAP_MS)
            val st = runCatching { taskByCodeMP(auth, code) }.getOrNull()?.let { acceptStatus(it) } ?: ""
            if (st.isNotEmpty() && st != "not_accepted") return true
        }
        return false
    }

    // ─────────────────────────── 单项执行（动作 → 回读 → 自动领奖）───────────────────────────

    /**
     * 执行单个动作并自动领奖（对齐 runGrowthQueued，taskcenter.go:406-452）：
     * 定位任务 → 已领跳过 → act.run → 有界回读 → 达标则领奖。
     * 完成判据是**回读任务进度**，不是「上报 200」（spec §3.3）。
     */
    suspend fun runGrowthItem(auth: AuthEntry, act: GrowthAction): ItemOutcome {
        val t = runCatching { taskByCode(auth, act.code) }.getOrNull()
            ?: return ItemOutcome("skipped", "任务未下发（活动可能已结束）", 0, 0, false)
        if (isClaimed(t)) return ItemOutcome("skipped", "已领取", 0, 0, false)
        val msg = try {
            act.run(auth)
        } catch (e: Throwable) {
            return ItemOutcome("error", e.message ?: "动作失败", 0, 0, false)
        }
        val t2 = runCatching { taskByCodeWaiting(auth, act.code) }.getOrNull()
        if (t2 != null && claimable(t2)) {
            val got = runCatching { claimTask(auth, act.code) }.getOrNull()
            if (got != null) return ItemOutcome("done", "$msg · 自动领奖 +${got.first} 分 +${got.second} 能", got.first, got.second, true)
        }
        return ItemOutcome("done", msg, 0, 0, false)
    }

    /** 单账号完整流水线：报名 → 逐动作推进并领奖。 */
    suspend fun processAccount(auth: AuthEntry): AccountResult {
        val tasks = try {
            Gateway.upstream.growthTasksMerged(auth)
        } catch (e: Throwable) {
            return AccountResult(auth.uid, auth.nickname, 0, 0, 0, 0, 0, 0, e.message ?: "拉取失败")
        }
        if (tasks.isEmpty()) return AccountResult(auth.uid, auth.nickname, 0, 0, 0, 0, 0, 0, null)

        val accepted = acceptPendingAuth(auth)

        var claimed = 0; var credit = 0L; var energy = 0L; var failed = 0
        for (act in GrowthActions.ordered()) {
            val o = runGrowthItem(auth, act)
            if (o.status == "error") failed++
            if (o.claimedCode) { claimed++; credit += o.credit; energy += o.energy }
        }
        return AccountResult(auth.uid, auth.nickname, tasks.size, accepted, claimed, credit, energy, failed, null)
    }

    /**
     * 门控账号（**以 Go 口径为准**，taskcenter.go:64,77-79）：跳过 `disabled` 与 `global`。
     *
     * 与旧方案差异：旧 `scanAll()` 用 `!it.isEnterprise` 过滤企业号；Go 成长链路**未显式过滤
     * 企业账号**，故此处不再过滤 enterprise（若上游对企业号返回 403，只会在该号上记录失败，
     * 由日志体现，不影响其它账号）。
     */
    fun gatedAccounts(): List<AuthEntry> =
        Gateway.store.auths().filter { a ->
            val st = Gateway.store.stateOf(a.uid)
            !st.disabled && a.realm != "global" && a.access_token.isNotBlank()
        }

    /** 扫描/推进全部账号（UI「扫描并领取」入口 + 定时任务共用）。 */
    suspend fun scanAll(onProgress: (String) -> Unit = {}): Report {
        val accounts = gatedAccounts()
        if (accounts.isEmpty()) {
            onProgress("没有可扫描的账号（已跳过禁用账号与 global 账号）")
            return Report(emptyList())
        }
        onProgress("共 ${accounts.size} 个账号待扫描…")
        LogBus.log("[成长] 开始扫描 ${accounts.size} 个账号")
        val results = mutableListOf<AccountResult>()
        accounts.forEachIndexed { i, a ->
            // 逐账号上报「当前进度」：既推给设置页的进度行，也写进日志页，
            // 让用户随时能看到「跑到第几个、这个号结果如何」，而不是盯着一个静止的按钮干等。
            val who = a.nickname.ifBlank { a.uid.take(8) }
            val step = "(${i + 1}/${accounts.size})"
            onProgress("$step 正在处理 $who …")
            LogBus.log("[成长] $step $who 处理中…")
            val r = processAccount(a)
            results += r
            val tail = if (r.error != null) "失败：${r.error}"
            else "任务 ${r.total} · 报名 ${r.accepted} · 领奖 ${r.claimed}" +
                (if (r.credit > 0) " · +${r.credit} 积分" else "")
            LogBus.log("[成长] $step $who → $tail")
            onProgress("$step $who 完成 · $tail")
        }
        val r = Report(results)
        LogBus.log("[成长] ${r.summary()}")
        return r
    }

    /**
     * 活跃上报（对齐 report.go:145-147）：给每个门控账号发一条 chat_request_send，
     * 点亮 growth 连登 + 解锁 first_buddy。每号每天 1 次即可（按天幂等）。
     */
    suspend fun activeReportAll() {
        val accounts = gatedAccounts()
        var ok = 0
        for (a in accounts) {
            val cid = "wb2api-active-${System.currentTimeMillis()}-${a.uid.take(8)}"
            if (runCatching { Gateway.upstream.reportChatActivity(a, cid, "") }.isSuccess) ok++
        }
        LogBus.log("[活跃] 已上报 $ok/${accounts.size} 个账号（点亮连登 + first_buddy）")
    }
}

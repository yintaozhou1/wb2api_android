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

    /**
     * 小程序限定任务码（对齐 Go mpTaskCodes，autotask.go:245-256）。
     *
     * 上游 10-31 起只剩 `Sequential_Tasks_1..7`：`school_season`（开学季）活动已下架，
     * 保留它会让 accept/claim 对一个上游根本不存在的 task_code 多打两次请求。
     */
    val MP_CODES: Set<String> = setOf(
        "Sequential_Tasks_1", "Sequential_Tasks_2", "Sequential_Tasks_3", "Sequential_Tasks_4",
        "Sequential_Tasks_5", "Sequential_Tasks_6", "Sequential_Tasks_7",
    )

    fun isMpCode(code: String): Boolean = code in MP_CODES

    /** 扫描单飞闸（防设置页与调度器并发跑同一批账号）。 */
    private val scanning = java.util.concurrent.atomic.AtomicBoolean(false)

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

    /** 任务码：global 列表用 `code` 字段（无 task_code），对齐上游 parseGrowthTasks。 */
    fun taskCode(o: JsonObject): String = str(o, "task_code", "code")

    /** `claimed = (accept_status=="claimed")`（tasks.go:185,203-204）。 */
    fun isClaimed(o: JsonObject): Boolean = str(o, "accept_status").lowercase() == "claimed"

    fun isLocked(o: JsonObject): Boolean = o["locked"]?.primTextOrNull()?.toBooleanStrictOrNull() ?: false

    fun acceptStatus(o: JsonObject): String = str(o, "accept_status")

    /**
     * 进度二元组：`progress` 内 **current 与 target 同时存在**才整体覆盖平铺字段
     * （对齐上游 parseGrowthTasks：单边存在时整组回落，避免 0 值/半截对象混口径）。
     */
    private fun progressPair(o: JsonObject): Pair<Long, Long> {
        val pr = o["progress"]?.objOrNull()
        val c = pr?.get("current")?.primTextOrNull()?.toLongOrNull()
        val t = pr?.get("target")?.primTextOrNull()?.toLongOrNull()
        if (c != null && t != null) return c to t
        return long(o, "current") to long(o, "target")
    }

    fun target(o: JsonObject): Long = progressPair(o).second

    fun current(o: JsonObject): Long = progressPair(o).first

    /**
     * 进度是否已达标（对齐上游 `taskProgressReached`，autotask.go）：
     * target>0 且 current>=target，**或** accept_status=completed，或 status=complete。
     *
     * 只认进度会漏掉「上游把任务标成 completed 但没下发 progress」的形态：这类任务
     * 自动链路跳过、手工领取还会被误报「进度未达标（0/0）」。
     */
    fun reached(o: JsonObject): Boolean =
        (target(o) > 0 && current(o) >= target(o)) ||
            acceptStatus(o).equals("completed", true) ||
            str(o, "status").equals("complete", true)

    /** `claimable = !claimed && reached`。 */
    fun claimable(o: JsonObject): Boolean = !isClaimed(o) && reached(o)

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

    // ─────────────────────────── 任务中心（UI 明细）───────────────────────────

    /**
     * 任务中心单条视图：只读展示 + 可单条领取。
     *
     * 为什么需要：旧 UI 只有「扫描并领取」一个黑盒按钮 + 一行汇总，用户看不到
     * 「有哪些任务、进度到哪、奖励多少、为什么没领到」。任务明细在服务端本来就有
     * （`growthTasksMerged` 已带 title/task_desc/progress/accept_status/reward_*），
     * 之前只是没映射出来。
     */
    data class TaskView(
        val code: String,
        val title: String,
        val desc: String,
        val current: Long,
        val target: Long,
        val credit: Long,
        val energy: Long,
        val status: String,
        val claimable: Boolean,
        val locked: Boolean,
        val mpOnly: Boolean,
    ) {
        /** accept_status 已 claimed（领过的不再显示「领取」）。 */
        val claimed: Boolean get() = status.equals("claimed", ignoreCase = true)

        /** 进度文案：无目标（进度未知）时只显示当前值。 */
        fun progressText(): String =
            if (target > 0) "$current / $target" else if (current > 0) "$current" else "—"

        /** 状态文案（UI 标签）。 */
        fun statusText(): String = when {
            claimed -> "已领取"
            claimable -> "可领取"
            locked -> "未解锁"
            status.equals("accepted", true) || status.equals("completed", true) -> "进行中"
            // mpOnly 不再占用状态位：小程序任务也可能「未报名」，行内已有「· 小程序」后缀。
            else -> "未报名"
        }
    }

    /** 拉取任务中心明细（默认口径 + mp 口径合并按 task_code 去重）。 */
    suspend fun tasks(auth: AuthEntry): List<TaskView> =
        Gateway.upstream.growthTasksMerged(auth).mapNotNull { t ->
            val code = taskCode(t)
            if (code.isBlank()) null else TaskView(
                code = code,
                title = str(t, "title").ifBlank { code },
                desc = str(t, "task_desc", "description"),
                current = current(t),
                target = target(t),
                credit = long(t, "reward_credit", "credit"),
                energy = long(t, "reward_energy", "energy"),
                status = acceptStatus(t).ifBlank { if (isLocked(t)) "locked" else "not_accepted" },
                claimable = claimable(t),
                locked = isLocked(t),
                mpOnly = isMpCode(code),
            )
        }

    /**
     * 单条领取（任务中心「领取」按钮）。
     *
     * 先回读一次确认可领：claim 对未达标任务返回业务错误，直接打会让 UI 只能显示
     * 上游的英文错误；这里提前给出「进度未达标」的中文原因。已领取直接返回 (0,0)。
     */
    suspend fun claimOne(auth: AuthEntry, code: String): Pair<Long, Long> {
        val t = Gateway.upstream.growthTasksMerged(auth).firstOrNull { taskCode(it) == code }
            ?: throw IllegalStateException("任务未下发（活动可能已结束）")
        if (isClaimed(t)) return 0L to 0L
        if (!claimable(t)) throw IllegalStateException("进度未达标（${current(t)}/${target(t)}）")
        return claimTask(auth, code) ?: throw IllegalStateException("领奖未成功，请稍后重试")
    }

    // ─────────────────────────── 单项执行（动作 → 回读 → 自动领奖）───────────────────────────

    /**
     * 执行单个动作并自动领奖（对齐 runGrowthQueued，taskcenter.go:406-452）：
     * 定位任务 → 已领跳过 → act.run → 有界回读 → 达标则领奖。
     * 完成判据是**回读任务进度**，不是「上报 200」（spec §3.3）。
     */
    suspend fun runGrowthItem(auth: AuthEntry, act: GrowthAction): ItemOutcome {
        // 拉取失败 ≠ 任务没下发：两者共用 skipped「活动可能已结束」会把网络抖动说成下架。
        val t = try {
            taskByCode(auth, act.code)
        } catch (e: Throwable) {
            return ItemOutcome("error", "任务列表拉取失败：${e.message ?: e.javaClass.simpleName}", 0, 0, false)
        } ?: return ItemOutcome("skipped", "该账号没有此任务", 0, 0, false)
        if (isLocked(t)) return ItemOutcome("skipped", "任务尚未解锁", 0, 0, false)
        if (isClaimed(t)) return ItemOutcome("skipped", "已领取", 0, 0, false)
        // 已达标先领奖、不再跑动作（对齐上游 executeAutoTask）：否则 template_5 会连发
        // 5 组事件链、chat_5 反复补报——白耗配额，还可能踩上游反作弊回滚。
        // first_buddy 是例外：进度满只说明对话做过，奖励在 agreement + buddy/first 链里。
        if (reached(t) && act.code != "first_buddy") return claimAndReport(auth, act.code, "任务已达标")
        val msg = try {
            act.run(auth)
        } catch (e: Throwable) {
            return ItemOutcome("error", e.message ?: "动作失败", 0, 0, false)
        }
        val t2 = try {
            taskByCodeWaiting(auth, act.code)
        } catch (e: Throwable) {
            return ItemOutcome("error", "$msg；回读进度失败：${e.message ?: e.javaClass.simpleName}", 0, 0, false)
        } ?: return ItemOutcome("awaiting", "$msg；回读时未找到该任务，暂不能确认完成", 0, 0, false)
        if (isClaimed(t2)) return ItemOutcome("done", "$msg；服务端已确认领取", 0, 0, false)
        if (!reached(t2)) return ItemOutcome("awaiting", "$msg；服务端进度尚未达标，等待入账", 0, 0, false)
        return claimAndReport(auth, act.code, msg)
    }

    /**
     * 领奖并归一结果（对齐上游 claimAutoTask）：
     *  - 成功 → done（带积分/能量）；
     *  - 失败 → claim_pending（**不能**当成 done：用户会以为已完成而积分没涨）。
     */
    private suspend fun claimAndReport(auth: AuthEntry, code: String, msg: String): ItemOutcome {
        val got = try {
            claimTask(auth, code)
        } catch (e: Throwable) {
            return ItemOutcome("claim_pending", "$msg；已达标但领奖失败：${e.message ?: e.javaClass.simpleName}", 0, 0, false)
        } ?: return ItemOutcome("claim_pending", "$msg；已达标但领奖失败（接口未确认）", 0, 0, false)
        return if (got.first > 0 || got.second > 0) {
            ItemOutcome("done", "$msg；已自动领奖 +${got.first} 分 +${got.second} 能", got.first, got.second, true)
        } else {
            // 0 分 0 能：上游对「此前已领过」返回业务成功但无奖励，不能说成失败。
            ItemOutcome("done", "$msg；领奖接口已确认（奖励此前可能已领取）", 0, 0, true)
        }
    }

    /** 单账号完整流水线：报名 → 逐动作推进并领奖。 */
    suspend fun processAccount(auth: AuthEntry): AccountResult {
        val tasks = try {
            // 严格版：拉取失败要进 AccountResult.error（旧实现吞成空列表，汇总永远「成功」，
            // 日志还把 401/断网说成「活动可能已结束」）。
            Gateway.upstream.growthTasksMergedStrict(auth)
        } catch (e: Throwable) {
            return AccountResult(auth.uid, auth.nickname, 0, 0, 0, 0, 0, 0, e.message ?: "拉取失败")
        }
        if (tasks.isEmpty()) return AccountResult(auth.uid, auth.nickname, 0, 0, 0, 0, 0, 0, null)

        val accepted = acceptPendingAuth(auth)

        var claimed = 0; var credit = 0L; var energy = 0L; var failed = 0
        for (act in GrowthActions.ordered()) {
            val o = runGrowthItem(auth, act)
            // error 与 claim_pending 都算「没做成」：后者的语义是「已达标但领奖失败」，
            // 旧实现把它当 done，用户只看到「已完成」而积分没涨。
            if (o.status == "error" || o.status == "claim_pending") failed++
            if (o.claimedCode) { claimed++; credit += o.credit; energy += o.energy }
        }
        // ── claim-only 兜底（上游 issue #174）──
        // 动作表只覆盖 11 个可脚本化任务；表外任务（expert_5 / Sequential_Tasks_* /
        // WorkBuddy_Valorant …）达标后没人领，积分会永久卡住（上游实测卡在 2000/2100）。
        // 这里对「已达标且未领、且没有自动动作」的任务只领奖、不做任何上报。
        for (t in tasks) {
            if (isClaimed(t) || isLocked(t) || !reached(t)) continue
            val code = taskCode(t)
            if (code.isBlank() || GrowthActions.forCode(code) != null) continue
            val got = runCatching { claimTask(auth, code) }.getOrNull() ?: continue
            if (got.first > 0 || got.second > 0) {
                claimed++
                credit += got.first
                energy += got.second
                LogBus.log("[成长] ${auth.nickname.ifBlank { auth.uid.take(8) }} 补领 $code +${got.first} 分 +${got.second} 能")
            }
        }
        return AccountResult(auth.uid, auth.nickname, tasks.size, accepted, claimed, credit, energy, failed, null)
    }

    /**
     * 门控账号（对齐上游 `SupportsPersonalEndpoints`，auth.go:312-317 / taskcenter.go:49-56）：
     * 跳过 `disabled`、`global` 与**企业版账号**。
     *
     * 企业版没有个人成长体系（growth 系列接口上游一律 403 / 400 code 10001），发给它
     * 只会攒一串注定失败的请求，并且让汇总误报「扫描失败」。上一版注释曾写「Go 未过滤
     * 企业号」——那是旧口径，上游已明确按 `!IsEnterprise() && !IsAPIKey()` 门控。
     */
    fun gatedAccounts(): List<AuthEntry> =
        Gateway.store.auths().filter { a ->
            val st = Gateway.store.stateOf(a.uid)
            !st.disabled && a.realm != "global" && !a.isEnterprise && a.access_token.isNotBlank()
        }

    /** 扫描/推进全部账号（UI「扫描并领取」入口 + 定时任务共用）。 */
    suspend fun scanAll(onProgress: (String) -> Unit = {}): Report {
        // 单飞闸：设置页「扫描并领取」与 Scheduler 定时任务会同时触发（上游用 queue.running
        // + tryLockAccount 双保险）。不拦的话同一账号的 accept/claim/事件上报会并发重复。
        if (!scanning.compareAndSet(false, true)) {
            onProgress("已有一轮扫描在进行，请稍候…")
            LogBus.log("[成长] 已有扫描在进行，本次跳过")
            return Report(emptyList())
        }
        try {
            val accounts = gatedAccounts()
            if (accounts.isEmpty()) {
                onProgress("没有可扫描的账号（已跳过禁用、企业版与 global 账号）")
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
        } finally {
            scanning.set(false)
        }
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

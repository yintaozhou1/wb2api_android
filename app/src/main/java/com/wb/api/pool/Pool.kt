package com.wb.api.pool

import com.wb.api.core.Durations
import com.wb.api.core.GatewayConfig
import com.wb.api.core.LogBus
import com.wb.api.data.Store
import com.wb.api.model.AccountState
import com.wb.api.model.ModelLimit
import com.wb.api.model.StickyBind
import com.wb.api.model.TokenUsage
import com.wb.api.upstream.ErrKind
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 快过期账号在新会话候选集中的**虚拟实例权重**（对齐上游 `expiringVirtualSlots = 3`）：
 * 等价于「该账号在候选集里出现 3 次」，于是随机加权时有明显更高概率被选中。
 */
private const val EXPIRING_VIRTUAL_SLOTS = 3.0

/** 账号池视图（用于 /status、面板）。字段皆为 val、集合只整体替换 → 声明不可变，
 *  让 Compose 强跳过能按 equals 判断「内容没变就不重组」。 */
@androidx.compose.runtime.Immutable
data class AccountView(
    val uid: String,
    val nickname: String,
    val realm: String,
    val disabled: Boolean,
    val paused: Boolean = false,
    /** 企业版账号（无个人成长体系、额度走企业口径，见 [com.wb.api.model.AuthEntry.isEnterprise]）。 */
    val enterprise: Boolean = false,
    val breaker_until: Long,
    val breaker_fail_streak: Int,
    val degrade_until: Long,
    val cool_remaining_sec: Long,
    val cool_kind: String,
    val in_flight: Int,
    val credits: Double,
    val credits_total: Double,
    val success_count: Long,
    val err_total: Long,
    val rate_limited_models: List<ModelLimit>,
    val model_costs: Map<String, Double>,
    val token_usage: TokenUsage,
    val access_expires_at: Long,
    val last_checkin_at: Long,
)

@androidx.compose.runtime.Immutable
data class PoolCounts(
    val total: Int, val healthy: Int, val cooling: Int, val disabled: Int, val inFlightFull: Int,
    /** 其中「暂停选号」的数量（已计入 [disabled]，对齐上游 /status 口径）。 */
    val paused: Int = 0,
)

/** 全池「模型级阻塞」画像 —— **本端自造**的上报结构（上游 Go 无同名类型，其判定散在 handler 内）。 */
@androidx.compose.runtime.Immutable
data class ModelBlockStatus(
    /** true = 每个参与选号的账号都对该模型处于模型级冷却。 */
    val blocked: Boolean = false,
    /** 触发原因（上游原文优先，其次冷却类型）。 */
    val reason: String = "",
    /** 最早解封时刻（秒；0 = 上游未给重置时刻）。 */
    val until: Long = 0,
    /** 因此被挡的账号数。 */
    val count: Int = 0,
)

/** 可信生成窗口下限：扣除 TTFB 后不足此值即认为「生成时长不可测」，退回端到端耗时。 */
private const val MIN_GEN_WINDOW_MS = 200L

class AccountPool(
    private val store: Store,
    private val cfg: () -> GatewayConfig,
) {
    private val inFlight = ConcurrentHashMap<String, AtomicInteger>()
    private val sticky = ConcurrentHashMap<String, StickyBind>()

    // WAF IP 级 fail-fast（**按 realm 分桶**：原来 CN 与 global 共用一份，任一 realm 触发
    // 会把另一个 realm 一起停摆 60s，属跨域误伤）。
    private val wafHits = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val wafActiveUntil = ConcurrentHashMap<String, Long>()

    // state.json 落盘节流（原实现每个成功请求都整份写盘）。
    @Volatile private var lastPersistMs = 0L
    @Volatile private var stateDirty = false

    fun reload() {
        store.loadAuths()
        val st = store.loadState()
        sticky.clear(); sticky.putAll(st.sticky)
        recoverChannelBlocked()
        LogBus.log("loaded ${store.auths().size} account(s)")
    }

    /**
     * 解除历史误判留下的**禁用**：`cool_kind == "account_banned"` 的唯一来源是
     * 「ACCOUNT_FAULT + body 含 request illegal」→ Disable。而 1.6.4 及更早的实现
     * 把上游的**渠道校验**（11-128 / 11128 "request illegal"）也判进了这条分支，
     * 于是好号被永久踢出池子，用户升级后仍会看到 `503 no_healthy_account`。
     *
     * 这里在每次加载账号时做一次幂等自愈（无该类状态时零开销）：
     *  - 真封禁（11140）在修复后的代码里仍会重新 Disable，故恢复是安全的 ——
     *    最坏情况是多打一次请求再被禁回去，不会让真封禁的号长期留在池里；
     *  - 不做这一步的话，用户必须手工逐个「启用」，等于把误判的代价永久留在设备上。
     */
    private fun recoverChannelBlocked() {
        var n = 0
        for (a in store.auths()) {
            val s = store.stateOf(a.uid)
            if (s.disabled && s.cool_kind == "account_banned") {
                store.putState(s.copy(disabled = false, paused = false, cool_kind = "", cool_until = 0))
                n++
            }
        }
        if (n > 0) {
            store.saveState(sticky)
            LogBus.log("恢复 $n 个被渠道校验误禁用的账号（1.6.4 及更早的 ACCOUNT_FAULT 误判）")
        }
    }

    fun accounts() = store.auths()

    fun stateOf(uid: String) = store.stateOf(uid)

    fun inFlightOf(uid: String): Int = inFlight[uid]?.get() ?: 0

    fun stickyCount(): Int = sticky.size

    private fun now() = System.currentTimeMillis() / 1000

    // ── 会话积分消耗跟踪（首页「本次启动网关消耗的积分」）────────────────────────
    // 没有任何**请求级**单价可依据（上游模型目录只给「x0.05 积分」这类相对费率，且成本
    // 台账 model_costs 未移植），所以唯一可靠的量纲是**余额差值**：启动网关时以各账号当前
    // credits 为基线，之后每次余额刷新把「相对上次观测的下降量」累计为本次消耗；只累计下降
    // （忽略奖励/重置带来的上升），得到单调不减的「净消耗」。
    private val sessionCreditSeen = java.util.concurrent.ConcurrentHashMap<String, Double>()
    @Volatile private var sessionCreditConsumed = 0.0

    /**
     * 启动网关时调用：清零本次消耗，并**丢弃基线**，等启动后的第一次余额刷新来建立基线。
     *
     * 为什么丢弃基线而不是「拿上次落盘的余额当基线」：上次落盘的余额可能是几分钟前（甚至
     * 上次运行）的旧值，用它当基线会把**启动之前**发生的消耗算进本次运行。改由「启动后立即
     * 触发的那次余额刷新」（见 [com.wb.api.Gateway.refreshBalances]）建立基线 —— 基线即
     * 「启动时刻的真实余额」，本次消耗只统计启动之后的下降。
     *
     * 配套：停止后同样补一次余额刷新，把最后一段未刷新窗口的消耗计入本次（否则会少算）。
     */
    fun resetSessionCreditTracking() {
        sessionCreditConsumed = 0.0
        sessionCreditSeen.clear()
    }

    /** 本次运行累计消耗的积分（单调不减）。 */
    fun sessionCreditConsumed(): Double = sessionCreditConsumed

    private fun cooling(a: AccountState): Boolean =
        (a.cool_until > now()) || (a.breaker_until > now()) || (a.degrade_until > now())

    private fun realmMaxInFlight(realm: String): Int =
        if (realm == "global") cfg().pool.max_in_flight_global else cfg().pool.max_in_flight

    /**
     * 选号：健康号加权随机；**无健康号时走全冷却兜底**（对齐 Go `pickEarliestExpiryLocked`）。
     *
     * 兜底是 Go 对「单账号被限流后整池 503」的正解：429 给账号加冷却后，若此处直接返回
     * null，handler 会立刻回 `503 no_healthy_account`——但那个号可能已恢复，重试成本仅
     * 一轮换。Go 的口径是：只要池里还有「非禁用、非余额耗尽硬冷却」的号，就挑「冷却截止
     * 最早」的那个继续尝试；只有池内一个可用号都没有时才返回 nil。
     */
    @Synchronized
    fun pick(realm: String, model: String, tried: Set<String> = emptySet()): com.wb.api.model.AuthEntry? {
        // WAF IP 级封锁期：该 realm 期间不给号（noteWaf 写、wafActive 读，两侧成对）。
        if (wafActive(realm)) return null
        val max = realmMaxInFlight(realm)
        val pool = store.auths().filter { a ->
            val s = store.stateOf(a.uid)
            // 禁用与「暂停选号」都不参与选号（对齐 Go entry.healthy：disabled || paused）。
            // 注意：这一层过滤同时决定了下面全冷却兜底的候选集 —— 暂停号不会走兜底。
            a.realm == realm && a.uid !in tried && !s.disabled && !s.paused
        }
        val candidates = pool.filter { a ->
            !cooling(store.stateOf(a.uid)) && inFlightOf(a.uid) < max
        }
        if (candidates.isEmpty()) return fallbackEarliestExpiry(pool, max)
        // 过滤模型级限额
        // 过滤模型级限额：**候选全部**对该模型处于模型级冷却时返回 null，不硬挑一个冷却中的
        // 号去撞限流 —— 对齐 Go pick（模型冷却的号一律排除、pick 返回 nil），由 handler 上报
        // model_unavailable（见 mapError / modelBlocked()）。原实现此处 `.ifEmpty { candidates }`
        // 回退等于忽略模型级冷却，使 modelBlocked() 成为不可达的死代码。
        val usable = candidates.filter { a ->
            store.stateOf(a.uid).rate_limited_models.none { it.model == model && it.until > now() }
        }
        if (usable.isEmpty()) return null
        // 最早到期优先 + 闲置加权
        val scored = usable.map { a ->
            val s = store.stateOf(a.uid)
            val idleSec = (now() - s.last_used_at).coerceAtLeast(0)
            val idleWeight = 1.0 + (idleSec / 3600.0 * cfg().pool.idle_weight_per_hour).coerceAtMost(cfg().pool.idle_weight_max)
            // 快过期优先（对齐上游 routingWeightOf）：账号存在「未来到期且在快过期窗口内」的
            // 批次时按 EXPIRING_VIRTUAL_SLOTS 倍加权，让快到期的额度优先被消耗掉。
            //
            // 1.4.4 及以前这里是个自造公式 `1 + 1/(earliest_remaining/86400 + 1)`：
            //   ① 语义错位——把「剩余量」当成「剩余天数」用；
            //   ② 恒定不生效——`earliest_remaining` 从来没有写入方（只在选号时被读），
            //     于是 prefer_expiring 形同虚设。现在改回上游口径，并由余额查询真正写入
            //     「最早到期批次」，这个开关才第一次真正起作用。
            val poolCfg = cfg().pool
            val soonSec = Durations.parse(poolCfg.expiring_soon, 168 * 3600L)
            val expireBoost = if (
                poolCfg.prefer_expiring &&
                s.earliest_remaining > 0 &&
                s.earliest_at > now() &&
                (soonSec <= 0 || s.earliest_at - now() <= soonSec)
            ) {
                EXPIRING_VIRTUAL_SLOTS
            } else 1.0
            a to (idleWeight * expireBoost)
        }
        var total = scored.sumOf { it.second }
        var r = Math.random() * total
        for ((a, w) in scored) { r -= w; if (r <= 0) return a }
        return scored.last().first
    }

    fun acquire(uid: String): Boolean {
        val a = inFlight.computeIfAbsent(uid) { AtomicInteger(0) }
        return a.incrementAndGet() > 0
    }

    fun release(uid: String) {
        inFlight[uid]?.let { if (it.decrementAndGet() < 0) it.set(0) }
    }

    fun onSuccess(
        uid: String,
        tokens: Long,
        latencyMs: Long,
        credit: Double,
        model: String,
        promptTokens: Long = 0,
        cachedTokens: Long = 0,
        /** 首 token 等待（ms）。0 = 没有观测（非流式天然没有首帧）→ 速率不扣减。 */
        ttfbMs: Long = 0,
    ) = store.withStateLock {
        val s = store.stateOf(uid)
        val tps = tokensPerSecond(tokens, latencyMs, ttfbMs)
        val prompt = promptTokens.coerceAtLeast(0)
        // 命中的缓存 token 不能超过输入 token（避免个别上游回包异常把命中率抬到 >100%）。
        val cached = cachedTokens.coerceIn(0, prompt)
        store.putState(s.copy(
            success_count = s.success_count + 1,
            last_used_at = now(),
            // 成功是「账号已恢复」的最强证据（对齐 Go NoteSuccess）：把全部**运行态**失败
            // 计数与截止一并清零 —— 熔断的连续失败计数与触发次数及其截止、连败降权计数与
            // 截止、软限流连续计数。漏清 soft_streak 会让账号一生中每次零星 429 都累加、
            // 把软冷却逐次推向 soft_rate_max 封顶（Go 已修复的历史 bug，本项审计对齐）。
            // **不动** cool_until/cool_kind（即时冷却各自到期）与 err_total（终身累计）。
            breaker_fail_streak = 0,
            breaker_retry = 0,
            breaker_until = 0,
            degrade_fail_streak = 0,
            degrade_until = 0,
            soft_streak = 0,
            token_usage = TokenUsage(
                request_count = s.token_usage.request_count + 1,
                total_tokens = s.token_usage.total_tokens + tokens,
                last_latency_ms = latencyMs,
                last_tokens_per_second = tps,
                // 累计输入 token 与命中缓存 token → 账号详情展示「缓存命中率」。
                prompt_tokens = s.token_usage.prompt_tokens + prompt,
                cached_tokens = s.token_usage.cached_tokens + cached,
            ),
            credits = if (credit > 0) (s.credits - credit).coerceAtLeast(0.0) else s.credits,
        ))
    }.also { persistThrottled() }

    /**
     * 吐字速率（token/s）。分母是**生成耗时** = 端到端耗时 − 首 token 等待（TTFB）。
     *
     * 为什么必须减（口径同上游 —— Go 由 `TokenUsageDelta.TokensPerSecond` 传入
     * `RecordTokenUsage`，并无同名函数；issue #34）：不减的话，首 token 等待越长、
     * 报出来的速率越低 —— 同一模型换条网络，TTFB 从 0.3s 涨到 2s，速率能凭空掉一半，
     * 读起来像「模型变慢了」，其实只是排队久了。
     *
     * 两条边界（对齐上游 issue #127）：
     *   • `ttfb<=0` = 没测到（非流式天然没有首帧）→ **不猜、不扣**：凭空假定一个 TTFB
     *     会把分母推向零、把速率抬成虚高，比不扣更糟；
     *   • 扣完不足 200ms（上游把整段响应攒到最后一次性下发时，首帧与末帧几乎同时到）
     *     → 退回端到端耗时，否则几百 token 会被除成上万 tok/s 的幻数。
     */
    private fun tokensPerSecond(completionTokens: Long, totalMs: Long, ttfbMs: Long): Double {
        if (completionTokens < 0 || totalMs <= 0) return 0.0
        var genMs = totalMs
        if (ttfbMs > 0) {
            val g = totalMs - ttfbMs
            if (g >= MIN_GEN_WINDOW_MS) genMs = g
        }
        return completionTokens * 1000.0 / genMs
    }

    // ---------- 落盘节流 ----------

    /** 高频路径（成功请求）用：1s 内合并为一次整份写盘，避免每请求一次 IO。 */
    @Synchronized
    private fun persistThrottled() {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastPersistMs < 1000L) { stateDirty = true; return }
        lastPersistMs = nowMs
        stateDirty = false
        store.saveState(sticky)
    }

    /** 调度器周期调用：把节流期间攒下的状态补写落盘（崩溃最多丢 1 分钟计数）。 */
    @Synchronized
    fun flush() {
        if (!stateDirty) return
        lastPersistMs = System.currentTimeMillis()
        stateDirty = false
        store.saveState(sticky)
    }

    /**
     * 错误 → 冷却策略（逐条对齐 Go `handler.applyErrorPolicy`）。
     *
     * 本轮关键修正：429 不再是「账号级 600s 冷却」的粗糙实现——
     *  - body 带重置墙钟 → 冷却**精确对齐该时刻**（封顶 soft_rate_max），绝不指数堆加；
     *    其中 `"code":6004` 是**模型级**限流（账号健康、仅该模型此刻受限，只写模型级
     *    冷却，切模型即可用），其余形态才走账号级；
     *  - body 无重置文案但带 `Retry-After` 头 → 冷却到该时刻（不做指数堆加，头低于文案）；
     *  - 都没有 → 账号级有界退避（soft_rate 起、softStreak 翻倍、封顶 soft_rate_max），
     *    且**已在软冷却中时不再推进**（防"兜底探测把冷却越堆越厚"，Go 注释点名的历史 bug）。
     * 冷却时长优先级逐字对齐 Go：body 重置文案 > Retry-After 头 > 有界退避。
     *
     * 错误 → 动作的映射**逐条对齐上游 `handler.applyErrorPolicy`**（2026-10-11 审计修正）：
     *   HARD_CREDIT → 冷却到次日 04:00；SOFT_RATE → 重置墙钟 / Retry-After / 有界退避；
     *   MODEL_BLOCKED → (账号,模型) 负缓存；NOT_FOUND → 固定短冷却；
     *   **SESSION_DEAD → 禁用**；**ACCOUNT_FAULT → 11140 禁用 / 其余软冷却**；
     *   **CHANNEL_BLOCK（11-128 / 11128 渠道校验）→ 软冷却，绝不 Disable**；
     *   **WAF_BLOCK → 软冷却（60s 基数）**；**SERVER → 喂熔断（NoteError）**；
     *   **CLIENT → 连败降权（NoteFailures）**；其余零动作。
     */
    fun onError(uid: String, kind: ErrKind, status: Int, model: String, body: String = "", retryAfterSec: Long = 0) = store.withStateLock {
        val s = store.stateOf(uid)
        val p = cfg()
        val t = now()
        // 台账原因取上游原文（截断）：模型级阻塞报错时要能把「为什么不能用」原样带给客户端。
        val why = body.take(200).replace('\n', ' ').trim()
        var ns = s.copy(err_total = s.err_total + 1, last_used_at = t)
        val cap = Durations.parse(p.cooldown.soft_rate_max, 7200)
        when (kind) {
            ErrKind.SOFT_RATE -> {
                val base = Durations.parse(p.cooldown.soft_rate, 600)
                val resetAt = com.wb.api.upstream.RateLimit.parseReset(body)
                val modelLevel = com.wb.api.upstream.RateLimit.isModelRateLimit(body)
                val keep = ns.rate_limited_models.filter { it.model != model }
                when {
                    resetAt > t -> {
                        // 模型级冷却**不**受 soft_rate_max 封顶：它只停 (账号, 模型) 这一个
                        // 组合，同号切其他模型照常可用，按上游墙钟完整恢复没有「无限期拉黑」
                        // 的风险。被 2h 封顶截断的后果是「上游说 19:25 恢复、网关 19:14 就
                        // 开始重试」，冷却期内反复撞 429、污染错误日志（上游 2026-10-24）。
                        // 账号级仍封顶：防一个号被长期冷却后又层层叠加。
                        val until = if (modelLevel) resetAt else minOf(resetAt, t + cap)
                        ns = ns.copy(rate_limited_models = keep +
                            ModelLimit(model, "rate_limit", reset_at = resetAt, until = until, reason = why))
                        if (!modelLevel) ns = ns.copy(cool_until = until, cool_kind = "soft_rate")
                        log(if (modelLevel) "soft_rate/model" else "soft_rate", uid, model, until - t)
                    }
                    retryAfterSec > 0 -> {
                        // body 无重置文案，但上游给了 Retry-After 头 → 冷却到该时刻。
                        // 模型级同样不封顶（理由见上面的 resetAt 分支）。
                        val until = if (modelLevel) t + retryAfterSec else minOf(t + retryAfterSec, t + cap)
                        ns = ns.copy(rate_limited_models = keep +
                            ModelLimit(model, "rate_limit", reset_at = until, until = until, reason = why))
                        if (!modelLevel) ns = ns.copy(cool_until = until, cool_kind = "soft_rate")
                        log(if (modelLevel) "soft_rate/model(retry-after)" else "soft_rate(retry-after)", uid, model, until - t)
                    }
                    s.cool_kind == "soft_rate" && s.cool_until > t -> {
                        // 已在软冷却中再 429：只更新模型台账，不延长账号级冷却
                        ns = ns.copy(rate_limited_models = keep +
                            ModelLimit(model, "rate_limit", reset_at = 0, until = s.cool_until, reason = why))
                        log("soft_rate(held)", uid, model, s.cool_until - t)
                    }
                    else -> {
                        val streak = s.soft_streak + 1
                        val cool = (base * (1L shl (streak - 1).coerceIn(0, 8))).coerceAtMost(cap)
                        ns = ns.copy(cool_until = t + cool, cool_kind = "soft_rate", soft_streak = streak,
                            rate_limited_models = keep +
                                ModelLimit(model, "rate_limit", reset_at = 0, until = t + cool, reason = why))
                        log("soft_rate", uid, model, cool)
                    }
                }
            }
            ErrKind.MODEL_BLOCKED -> {
                // 11102「该后端无此模型」：(账号,模型) 负缓存，TTL 6h 起按命中次数翻倍、封顶 24h。
                // 该模型请求成功即由 clearModelBlock 清除（对齐 Go BlockModelBackoff/Clear）。
                val prev = s.rate_limited_models.firstOrNull { it.model == model && it.kind == "model_unavailable" }
                val hits = (prev?.hits ?: 0) + 1
                val ttl = minOf(6L * 3600 * (1L shl (hits - 1).coerceIn(0, 4)), 24L * 3600)
                ns = ns.copy(rate_limited_models = s.rate_limited_models.filter { it.model != model } +
                    ModelLimit(model, "model_unavailable", reset_at = 0, until = t + ttl, hits = hits, reason = why))
                log("model_blocked", uid, model, ttl)
            }
            ErrKind.HARD_CREDIT -> {
                val until = nextHardReset()
                ns = ns.copy(cool_until = until, cool_kind = "hard_credit")
                log("hard_credit", uid, model, until - t)
            }
            ErrKind.NOT_FOUND -> {
                // 上游对 404 只做**账号级短冷却**，不写模型级台账 —— 404 是「这个号打这个
                // 端点不存在」，不是「该模型对全池不可用」，写进模型台账会误伤其它账号。
                ns = ns.copy(cool_until = t + 60, cool_kind = "not_found")
            }
            ErrKind.SESSION_DEAD -> {
                // 上游 applyErrorPolicy：ErrSessionDead → **Disable**（12153 会话失效不可自愈，
                // 必须重新登录才能恢复）。原实现给的是「300 秒软冷却」——冷却到点后这个号仍会
                // 被选中、继续 12153，等于把「需要重新登录的终态」错当成一次临时抖动。
                ns = ns.copy(disabled = true, paused = false, cool_kind = "session_dead")
                log("session_dead/disable", uid, model, 0)
            }
            ErrKind.CHANNEL_BLOCK -> {
                // 渠道校验拦截（11-128 / 11128 request illegal，见 Classify.isChannelBlock）：
                // 上游自述「这次调用没通过渠道校验，可能不是官方客户端发出的，请重新发送」，
                // 是**请求级可重试信号**，不是账号被封。处置对齐新上游 17b5204：
                // 软冷却（soft_rate 基数 + soft_streak 指数、封顶 soft_rate_max），
                // **不 Disable、不喂熔断** —— 上游解除标记 / 本端补齐指纹后自愈。
                val base = Durations.parse(p.cooldown.soft_rate, 600)
                val streak = s.soft_streak + 1
                val cool = (base * (1L shl (streak - 1).coerceIn(0, 8))).coerceAtMost(cap)
                ns = ns.copy(cool_until = t + cool, cool_kind = "channel_block", soft_streak = streak)
                log("channel_block(keep account)", uid, model, cool)
            }
            ErrKind.MODEL_BUSY -> {
                // 14003「模型繁忙」（上游 issue #46）：**模型级**短冷却，不碰账号级。
                //
                // 首档 10s、同 (号,模型) 反复命中翻倍、封顶 60s：生产日志显示上游繁忙是
                // **间歇**的（同一分钟内既有 14003 也有成功），首档取分钟级会把 10 秒抖动
                // 放大成整段客户端可见的断流。同一窗口内重复命中不延长（并发请求/兜底探测
                // 会把窗口越堆越厚，与 soft_rate 的 held 语义一致）。
                val prev = ns.rate_limited_models.firstOrNull { it.model == model && it.kind == "model_busy" }
                if (prev != null && prev.until > t) {
                    log("model_busy(held)", uid, model, prev.until - t)
                } else {
                    val hits = (prev?.hits ?: 0) + 1
                    var cool = 10L
                    var i = 1
                    while (i < hits && cool < 60L) { cool *= 2; i++ }
                    cool = minOf(cool, 60L)
                    ns = ns.copy(rate_limited_models = ns.rate_limited_models.filter { it.model != model } +
                        ModelLimit(model, "model_busy", reset_at = 0, until = t + cool, hits = hits, reason = why))
                    log("model_busy", uid, model, cool)
                }
            }
            ErrKind.ACCOUNT_FAULT -> {
                // 上游 applyErrorPolicy 按 msg 分野（口径与 Classify 的 accountFaultMarkers 一致）：
                //   - "request illegal"（code 11140）→ 账号被上游**授权封禁**：硬禁用（重登才可解，
                //     继续选它只会次次 403，把用户时间烧在必然失败的轮转上）；
                //   - 其余（14017 trial not activated）→ **软冷却**：补完 register 后可能自愈，
                //     禁用会让用户补完后仍然用不了。
                if (body.contains("request illegal", ignoreCase = true)) {
                    ns = ns.copy(disabled = true, paused = false, cool_kind = "account_banned")
                    log("account_banned/disable", uid, model, 0)
                } else {
                    val base = Durations.parse(p.cooldown.soft_rate, 600)
                    ns = ns.copy(cool_until = t + base, cool_kind = "account_fault", soft_streak = 0)
                    log("account_fault", uid, model, base)
                }
            }
            ErrKind.WAF_BLOCK -> {
                // 上游 applyErrorPolicy：ErrWafBlock → **软冷却**（基数 wafCooldownBase=60s +
                // softStreak 指数升级、封顶 soft_rate_max），**不喂熔断、不 Disable** ——
                // WAF 是出口链路/IP 级拦截，不是账号故障，把账号熔断掉等于误伤健康号。
                //
                // 本项目在此之上再维护一层「realm 级 WAF 闸门」（noteWaf：同一 realm 60 秒内
                // ≥2 个不同账号被 WAF 拦 → 暂停该 realm 选号 60 秒），那是出口 IP 级的判断，
                // 与账号级软冷却互补，故两者都保留。
                noteWaf(uid)
                // 对齐 Go：WAF 403 若带 Retry-After 头则**冷却到该时刻**（不做指数堆加）；
                // 否则 60s 基数 + soft_streak 指数、封顶 soft_rate_max。
                if (retryAfterSec > 0) {
                    val cool = minOf(retryAfterSec, cap)
                    ns = ns.copy(cool_until = t + cool, cool_kind = "waf", soft_streak = ns.soft_streak + 1)
                    log("waf(retry-after)", uid, model, cool)
                } else {
                    val streak = ns.soft_streak + 1
                    val cool = (60L * (1L shl (streak - 1).coerceIn(0, 8))).coerceAtMost(cap)
                    ns = ns.copy(cool_until = t + cool, cool_kind = "waf", soft_streak = streak)
                    log("waf", uid, model, cool)
                }
            }
            ErrKind.SERVER -> {
                // 上游 applyErrorPolicy：ErrServer → **NoteError**，喂「唯一的连续失败计数器」，
                // 达 breaker_threshold 触发熔断（指数退避、封顶 breaker_cooldown_max）。
                //
                // 这是本次审计修掉的核心偏差：原实现把 5xx 归入「不罚号」，于是**持续 5xx /
                // 连不上的坏号永远不会被熔断**，池子会一直选中它 —— 用户侧表现为「反复 502、
                // 反复换号却总回到同一个坏号」。
                // 退避指数是「**历次触发熔断的次数**」（breaker_retry），不是连续失败数
                // （对齐 Go recordBreakerFailureLocked：d = breakerCooldown << retryCount，
                //  触发即 fails=0、retryCount++）。用连续失败数当指数会让同一个坏号每次再
                // 失败都线性放大，且触发后不归零 → 退避无界膨胀。
                val streak = ns.breaker_fail_streak + 1
                if (streak >= p.pool.breaker_threshold) {
                    val base = Durations.parse(p.pool.breaker_cooldown, 1800)
                    val cap2 = Durations.parse(p.pool.breaker_cooldown_max, 21600)
                    val cool = (base * (1L shl ns.breaker_retry.coerceIn(0, 6))).coerceAtMost(cap2)
                    ns = ns.copy(breaker_until = t + cool, breaker_fail_streak = 0, breaker_retry = ns.breaker_retry + 1)
                    log("breaker", uid, model, cool)
                } else ns = ns.copy(breaker_fail_streak = streak)
            }
            ErrKind.CLIENT -> {
                // 上游 applyErrorPolicy 的 default 分支：**未知 4xx（ErrClient）喂连败计数**
                // （issue #114 NoteFailures）—— 连续 degrade_threshold 次即让账号**临时出池**；
                // 单次/偶发不罚，避免误伤。此前 degrade_* 三个配置项与状态字段「只读不写」，
                // 这里把它们真正接上（时长与清零规则见 degradeNext）。
                val (nsc, dcool) = degradeNext(ns, p.pool, t)
                ns = nsc
                if (dcool > 0) log("degrade", uid, model, dcool)
            }
            else -> {
                // 其余全部零动作，与上游一致：
                //   NONE 防御路径 / CONTENT_BLOCKED 内容误报 / BAD_PARAMS 请求体畸形 /
                //   PROMPT_TOO_LONG 11115 / IMAGE_INVALID 图片无效 —— 都是「请求的问题不是
                //   账号的问题」，同一 body 换任何号结果相同，罚号只会误伤健康账号。
            }
        }
        store.putState(ns)
    }.also {
        // 与成功路径同口径：错误风暴时**不要**每个请求都整份写盘 + fsync（那会拖慢本就
        // 紧张的 HTTP 线程池）。1s 节流 + 调度器 flush 兜底，崩溃最多丢 1s 的计数。
        //
        // **必须在 withStateLock 之外**：persistThrottled 拿的是 Pool 监视器，而 flush()
        // 是「持 Pool 监视器 → 取 Store 监视器」；若在 Store 临界区内取 Pool 锁，就是
        // 教科书式的 AB-BA 死锁（Store 监视器永不释放 → 之后所有 stateOf/putState/pick
        // 全部永久阻塞，网关静默假死）。
        persistThrottled()
    }

    /**
     * 传输层失败（连不上 / TLS / 超时等**拿不到上游 HTTP 响应**的失败）的处置。
     *
     * 对齐 Go handler 的网络抖动分支：**只换号，不喂熔断计数**（传输层错误对「连续失败
     * 连坐熔断」过于严苛 —— 网络抖动会误伤健康号），改喂**连败降权**（NoteFailures）：
     * 连败 degrade_threshold 次才临时出池。原实现把它当 `ErrKind.SERVER` 走 onError → 喂
     * 熔断，于是上游网络一抖，健康账号就被熔断。
     */
    fun onTransportFailure(uid: String, model: String, why: String) = store.withStateLock {
        val s = store.stateOf(uid)
        val t = now()
        val (ns, cool) = degradeNext(s.copy(last_used_at = t), cfg().pool, t)
        store.putState(ns)
        if (cool > 0) log("degrade/transport", uid, model, cool)
    }.also { persistThrottled() }

    /** WAF 触发记录（按 realm 分桶，60s 窗口内出现 ≥2 个不同账号即判定该 realm 被 WAF）。 */
    fun noteWaf(uid: String) {
        val realm = store.getAuth(uid)?.realm ?: "cn"
        val t = now()
        val dq = wafHits.computeIfAbsent(realm) { ArrayDeque() }
        synchronized(dq) {
            dq.addLast(t)
            while (dq.isNotEmpty() && dq.first() < t - 60) dq.removeFirst()
            if (dq.size >= 2) wafActiveUntil[realm] = t + 60
        }
    }

    fun wafActive(realm: String = "cn"): Boolean = now() < (wafActiveUntil[realm] ?: 0L)

    /**
     * 连败降权（对齐 Go NoteFailures / degrade.go）：连续 [com.wb.api.core.PoolCfg.degrade_threshold]
     * 次「不罚号的失败」触发**固定时长**临时出池。三点逐条对齐上游：
     *  ① 时长为**固定值** degrade_cooldown（钳到 [默认, degrade_cooldown_max]），**不做指数升级**
     *     —— 触发次数在达阈时清零、不持久化历史，档位不可推导；
     *  ② 达阈即把连续失败计数**清零**（供下一轮重新累计）；
     *  ③ 已在降权期内再次达阈：**不延长、不翻倍**（防用户重试把降权越堆越厚）。
     * 返回 (新状态, 本次触发时长)；时长为 0 表示未触发。
     */
    private fun degradeNext(
        s: com.wb.api.model.AccountState,
        p: com.wb.api.core.PoolCfg,
        t: Long,
    ): Pair<com.wb.api.model.AccountState, Long> {
        val streak = s.degrade_fail_streak + 1
        if (streak < p.degrade_threshold) return s.copy(degrade_fail_streak = streak) to 0L
        if (s.degrade_until > t) return s.copy(degrade_fail_streak = 0) to 0L
        val base = Durations.parse(p.degrade_cooldown, 600).let { if (it <= 0) 600L else it }
        val cap = Durations.parse(p.degrade_cooldown_max, 7200)
        val cool = minOf(base, cap)
        return s.copy(degrade_until = t + cool, degrade_fail_streak = 0) to cool
    }

    /**
     * 硬冷却（积分耗尽 HARD_CREDIT）的恢复时刻：下一个 04:00。
     *
     * 时区**有意固定为 CST（UTC+8）**（与上游 `nextDay4AM` 用 `now.Location()` 的本地时区
     * 口径不同）：本项目的冷却重置墙钟一律按 UTC+8 解析（见 429 重置文案的解析规则），两者
     * 必须同源，否则「上游说 CST 04:00 恢复」与本地算出的恢复时刻会差一个时区。
     *
     * 关键分支：**CST 00:00~04:00 之间返回「当天」04:00** —— 该窗内签到尚未执行，等当天
     * 签到即可恢复，返回次日会白冷约一天；04:00 整及之后才返回次日 04:00。原实现无条件
     * `+1 天`，凌晨触发的硬冷却被多冷接近一整天。
     */
    private fun nextHardReset(): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("GMT+8"))
        if (cal.get(Calendar.HOUR_OF_DAY) >= 4) cal.add(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 4); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis / 1000
    }

    fun revive(uid: String) {
        val s = store.stateOf(uid)
        // 「解冻」是全清：暂停选号一并解除（对齐上游 state.go 的 Revive）。
        store.putState(s.copy(disabled = false, paused = false, breaker_until = 0, breaker_fail_streak = 0,
            degrade_until = 0, degrade_fail_streak = 0, cool_until = 0, cool_kind = "", soft_streak = 0,
            rate_limited_models = emptyList()))
        store.saveState(sticky)
    }

    fun setDisabled(uid: String, disabled: Boolean) {
        val s = store.stateOf(uid)
        // 禁用是比「暂停选号」更强的终态：置禁用时顺手清掉暂停（对齐上游 disableLocked），
        // 否则解禁后会残留一个「已暂停」，与用户看到的状态不符。
        store.putState(s.copy(disabled = disabled, paused = if (disabled) false else s.paused))
        store.saveState(sticky)
    }

    /**
     * 暂停 / 恢复「选号」（对齐上游 Go `Pool.Pause` / `Resume`）。
     *
     * 只置一个标志：不动冷却、不动熔断、不动任何计数 —— 账号是「临时让位」而不是「判死」，
     * 恢复后原有的冷却/熔断观测仍然有效，也无需重新登录。
     */
    fun setPaused(uid: String, paused: Boolean) {
        store.putState(store.stateOf(uid).copy(paused = paused))
        store.saveState(sticky)
        LogBus.log(
            if (paused) "pause uid=${uid.take(8)} 已暂停选号（不接新请求，状态与冷却保留）"
            else "resume uid=${uid.take(8)} 已恢复选号",
        )
    }

    fun remove(uid: String) = store.removeAuth(uid)

    /**
     * 更新账号余额（并顺带记录**最早到期批次**）。
     *
     * @param earliestAt        最早未来到期批次的时刻（epoch 秒）；0 = 「本次没拿到到期信息」，
     *                          此时**保留旧值不清零** —— 否则一次不带到期信息的查询会把已有的
     *                          快过期信号抹掉，`prefer_expiring` 就永远不生效了。
     * @param earliestRemaining 该批次的剩余量之和。
     */
    fun setCredits(uid: String, credits: Double, earliestAt: Long = 0, earliestRemaining: Double = 0.0) {
        val s = store.stateOf(uid)
        // 会话积分消耗：与该账号上次观测值比较，下降量计入「本次运行消耗」。
        val seen = sessionCreditSeen.put(uid, credits)
        if (seen != null) {
            val drop = seen - credits
            if (drop > 0) sessionCreditConsumed += drop
        }
        store.putState(
            s.copy(
                credits = credits,
                credits_total = maxOf(s.credits_total, credits),
                last_balance_at = now(),
                earliest_at = if (earliestAt > 0) earliestAt else s.earliest_at,
                earliest_remaining = if (earliestAt > 0) earliestRemaining else s.earliest_remaining,
            ),
        )
        store.saveState(sticky)
    }

    /** 记录签到时刻（「今日是否已签到」判定 / 自动补签的依据）。 */
    fun markCheckin(uid: String) {
        val s = store.stateOf(uid)
        store.putState(s.copy(last_checkin_at = now()))
        store.saveState(sticky)
    }

    fun counts(): PoolCounts {
        val all = store.auths()
        var healthy = 0; var cooling = 0; var disabled = 0; var full = 0; var paused = 0
        all.forEach { a ->
            val s = store.stateOf(a.uid)
            when {
                // 「暂停选号」与禁用同口径计入「不可用」（对齐上游 /status），另有 paused 细分。
                s.disabled || s.paused -> {
                    disabled++
                    if (s.paused) paused++
                }
                cooling(s) -> cooling++
                inFlightOf(a.uid) >= realmMaxInFlight(a.realm) -> full++
                else -> healthy++
            }
        }
        return PoolCounts(all.size, healthy, cooling, disabled, full, paused)
    }

    fun countsByRealm(realm: String): PoolCounts {
        val all = store.auths().filter { it.realm == realm }
        var healthy = 0; var cooling = 0; var disabled = 0; var full = 0; var paused = 0
        all.forEach { a ->
            val s = store.stateOf(a.uid)
            when {
                s.disabled || s.paused -> {
                    disabled++
                    if (s.paused) paused++
                }
                cooling(s) -> cooling++
                inFlightOf(a.uid) >= realmMaxInFlight(a.realm) -> full++
                else -> healthy++
            }
        }
        return PoolCounts(all.size, healthy, cooling, disabled, full, paused)
    }

    /**
     * 是否有可服务的账号。
     *
     * 修复点：原来只认 `healthy > 0`，于是「全部号处于冷却」时 /healthz 报 503，
     * 但 pick() 仍会通过全冷却兜底挑号成功——健康检查与真实可用性自相矛盾。
     * 现在与 pick 的口径对齐：只要存在「非禁用且非余额耗尽硬冷却」的号就算可服务。
     */
    fun servableNow(): Boolean {
        if (counts().healthy > 0) return true
        val t = now()
        return store.auths().any { a ->
            val s = store.stateOf(a.uid)
            !s.disabled && !s.paused && !(s.cool_kind == "hard_credit" && s.cool_until > t)
        }
    }

    fun listViews(): List<AccountView> {
        val t = now()
        return store.auths().map { a ->
            val s = store.stateOf(a.uid)
            val coolRemaining = listOf(s.cool_until, s.breaker_until, s.degrade_until)
                .maxOrNull()?.let { (it - t).coerceAtLeast(0) } ?: 0
            AccountView(
                uid = a.uid, nickname = a.nickname, realm = a.realm,
                disabled = s.disabled, paused = s.paused, enterprise = a.isEnterprise,
                breaker_until = s.breaker_until, breaker_fail_streak = s.breaker_fail_streak,
                degrade_until = s.degrade_until, cool_remaining_sec = coolRemaining, cool_kind = s.cool_kind,
                in_flight = inFlightOf(a.uid), credits = s.credits, credits_total = s.credits_total,
                success_count = s.success_count, err_total = s.err_total,
                rate_limited_models = s.rate_limited_models, model_costs = s.model_costs,
                token_usage = s.token_usage, access_expires_at = a.access_expires_at,
                last_checkin_at = s.last_checkin_at,
            )
        }
    }

    // ---------- 会话粘性 ----------

    fun stickyGet(key: String): String? {
        // 开关必须在这里生效：原先只有 stickyGC 看 enabled，关掉开关后选号仍然粘、
        // 只是不再过期回收 —— 行为与用户预期相反，且 sticky map 会无限增长。
        if (!cfg().session_sticky.enabled) return null
        val b = sticky[key] ?: return null
        val ttl = Durations.parse(cfg().session_sticky.ttl, 1800)
        if (now() - b.updated_at > ttl) { sticky.remove(key); return null }
        return b.uid
    }

    fun stickyBind(key: String, uid: String) {
        // 同 stickyGet：开关关闭时不再写入新绑定（否则关开关只是「不 GC」）。
        if (!cfg().session_sticky.enabled) return
        sticky[key] = StickyBind(uid, now())
    }

    fun stickyUnbind(key: String) { sticky.remove(key) }

    @Volatile private var stickyGcAt = 0L

    fun stickyGC() {
        if (!cfg().session_sticky.enabled) return
        val t = now()
        // gc_interval 原先是死配置（GC 只由调度器 60s 周期驱动）：现在按它自行节流，
        // 用户把间隔调大就真的少扫（ttl 更短的过期项由 stickyGet 的懒过期兜住）。
        val every = Durations.parse(cfg().session_sticky.gc_interval, 300)
        if (every > 0 && t - stickyGcAt < every) return
        stickyGcAt = t
        val ttl = Durations.parse(cfg().session_sticky.ttl, 1800)
        sticky.entries.removeIf { t - it.value.updated_at > ttl }
    }

    private fun log(kind: String, uid: String, model: String, secs: Long) {
        LogBus.log("cooldown $kind uid=${uid.take(8)} model=$model ${Durations.format(secs)}")
    }

    // ---------- 健康判定 / 全冷却兜底（对齐 Go entry.healthyForModel / pickEarliestExpiryLocked） ----------

    /** 账号对指定模型是否可选：禁用 / 账号级冷却 / 该模型独立冷却 任一成立即不可选。 */
    fun healthyForModel(uid: String, model: String): Boolean {
        val s = store.stateOf(uid)
        if (s.disabled || s.paused || cooling(s)) return false
        if (model.isNotBlank() && s.rate_limited_models.any { it.model == model && it.until > now() }) return false
        return true
    }

    fun inFlightFull(uid: String, realm: String): Boolean = inFlightOf(uid) >= realmMaxInFlight(realm)

    /**
     * 该模型此刻是否被**全池**模型级冷却挡住（**本端自造**的上报能力：上游 Go 无同名方法）。
     *
     * 为什么需要它：选号返回 nil 有两种完全不同的成因 ——
     *   (a) 池子真没号（账号级冷却 / 在途占满 / 积分保底）→ 该等一会儿；
     *   (b) 号都在，但每个都对**这个模型**处于模型级冷却（11102/6004）→ 换模型才有用。
     * 两者过去都回 `no_healthy_account`，于是「模型不可用」被读成「号全挂了」，
     * 客户端还会按可重试错误反复重试（上游 issue #102 附带发现）。
     *
     * 口径与选号严格一致：禁用 / 暂停号不参与（它们的不可用与模型无关）；
     * 只要有**任意**一个参与选号的账号还能服务该模型，就返回 blocked=false，
     * 让上层继续用原有的 no_healthy_account 文案。
     */
    fun modelBlocked(model: String, realm: String = ""): ModelBlockStatus {
        if (model.isBlank()) return ModelBlockStatus()
        val t = now()
        var count = 0
        var reason = ""
        var until = 0L
        store.auths().forEach { a ->
            // 选号是**分 realm** 的，判定也必须分 realm：否则 cn 号全被某模型冷却、
            // global 号只因账号级冷却而选不到时，会被误报成「该模型全池不可用」，
            // 诱导用户换掉本来可用的模型。
            if (realm.isNotEmpty() && a.realm != realm) return@forEach
            val s = store.stateOf(a.uid)
            if (s.disabled || s.paused) return@forEach
            val lim = s.rate_limited_models.firstOrNull { it.model == model && it.until > t }
                ?: return ModelBlockStatus()   // 还有账号能服务这个模型 → 不是模型级阻塞
            count++
            if (reason.isBlank()) reason = lim.reason.ifBlank { lim.kind }
            // 取**最早**解封：那才是「再等多久值得重试」的答案。
            if (lim.until > 0 && (until == 0L || lim.until < until)) until = lim.until
        }
        if (count == 0) return ModelBlockStatus()   // 池里压根没有可参与选号的账号
        return ModelBlockStatus(blocked = true, reason = reason, until = until, count = count)
    }

    /**
     * 「全池该模型只是忙」的等待建议（毫秒；0 = 不值得等）。
     *
     * 对齐新上游 issue #46（ecbd2f785 + 97ba22813）：判据**三条同时成立**才等 ——
     *  1. 全池都被该模型的冷却挡住（[modelBlocked]）；
     *  2. 原因是 14003 模型繁忙（11102「模型不存在」/11158 换号或等待都不会变好）；
     *  3. 最早解封在 [capMs] 之内。
     * 抖动只取**正向**（0 ~ +25%）：早醒一次等于白等（上游实测 4/8 → 8/8），
     * 晚一点没有任何代价。
     */
    fun modelBusyWaitMs(model: String, capMs: Long, realm: String = ""): Long {
        val st = modelBlocked(model, realm)
        if (!st.blocked || st.until <= 0) return 0
        val r = st.reason.lowercase()
        val busy = r.contains("14003") || r.contains("model_busy") || r.contains("繁忙") || r.contains("busy")
        if (!busy) return 0
        val remain = st.until * 1000 - System.currentTimeMillis()
        if (remain <= 0 || remain > capMs) return 0
        return remain + (Math.random() * remain * 0.25).toLong()
    }

    /**
     * 401 已被 refresh **求证为真死**：禁用（不可自愈，必须重新登录）。
     *
     * 与旧实现（收到 SESSION_DEAD 就禁用）的区别：禁用这个决定现在只由「refresh 被应用层
     * 明确拒绝」触发，边缘拦截 / 网络抖动不再能误杀伤健康账号（上游 issue #66）。
     */
    fun markSessionDead(uid: String) = store.withStateLock {
        val s = store.stateOf(uid)
        store.putState(
            // **不动 paused**：那是用户手动「暂停选号」的意图，禁用不该顺手清掉。
            s.copy(
                disabled = true, cool_kind = "session_dead",
                last_used_at = now(), err_total = s.err_total + 1,
            ),
        )
        log("session_dead/verified", uid, "", 0L)
        // 终态**立即**落盘：禁用状态若丢在节流窗口里（进程被杀），死号会复活继续刷 401。
        // saveState 与 withStateLock 是同一把 Store 监视器（可重入），不会引入 Pool 锁。
        store.saveState(sticky)
    }

    /**
     * 401 但 refresh **没有给出判词**（或证明账号正常）：只做软冷却，**不计死**。
     *
     * 用 soft_rate 家族而不是固定 60s：一个「chat 恒 401 但 refresh 恒成功」的降级 token
     * 号会被每 60s 轮换一次 RT（一天 1440 次），指数退避能把这类号的请求频率压下来。
     */
    fun softCooldownForSession(uid: String, reason: String, baseSec: Long = 0) = store.withStateLock {
        val s = store.stateOf(uid)
        val p = cfg()
        val t = now()
        val cap = Durations.parse(p.cooldown.soft_rate_max, 7200)
        val base = if (baseSec > 0) baseSec else Durations.parse(p.cooldown.soft_rate, 600)
        val streak = s.soft_streak + 1
        val cool = (base * (1L shl (streak - 1).coerceIn(0, 8))).coerceAtMost(cap)
        // **只延长、不缩短**：hard_credit（到次日 04:00）/ breaker / account_fault 这类更强的
        // 冷却不能被一次 401 覆盖成 600s —— 否则账号提前回到轮转、继续撞 402/429。
        val until = maxOf(s.cool_until, t + cool)
        val stronger = s.cool_until > t && s.cool_kind !in setOf("", "gateway_401", "soft_rate")
        store.putState(
            s.copy(
                cool_until = until,
                cool_kind = if (stronger) s.cool_kind else "gateway_401",
                soft_streak = streak,
                last_used_at = t,
                err_total = s.err_total + 1,
            ),
        )
        log("gateway_401(soft)", uid, "", until - t)
        if (reason.isNotBlank()) LogBus.log("[pool] $reason uid=${uid.take(8)}")
    }.also { persistThrottled() }

    /** 某模型请求成功：清除 11102 负缓存（对齐 Go BlockModelClear）。 */
    fun clearModelBlock(uid: String, model: String) {
        val s = store.stateOf(uid)
        // 成功是「该模型此刻可服务」的最强证据：11102 负缓存与 14003 短冷却一起清
        // （后者本就是 10~60s 的临时停车，成功即证明不必再等）。
        val filtered = s.rate_limited_models.filterNot {
            it.model == model && (it.kind == "model_unavailable" || it.kind == "model_busy")
        }
        if (filtered.size != s.rate_limited_models.size) {
            store.putState(s.copy(rate_limited_models = filtered))
            store.saveState(sticky)
        }
    }

    /** 账号恢复时刻（无任何冷却为 0）。 */
    private fun expiryOf(s: AccountState): Long {
        val t = now()
        return maxOf(
            if (s.cool_until > t) s.cool_until else 0,
            if (s.breaker_until > t) s.breaker_until else 0,
            if (s.degrade_until > t) s.degrade_until else 0,
        )
    }

    /**
     * 全冷却兜底（对齐 Go `pickEarliestExpiryLocked`）：在非禁用、非余额耗尽硬冷却、
     * 在途未满的号里选「恢复最早」的一个继续尝试。余额耗尽号（hard_credit，等次日 04:00
     * 签到）必 402，参与只会浪费一轮轮换，故排除。池内无可用号才返回 nil。
     */
    private fun fallbackEarliestExpiry(pool: List<com.wb.api.model.AuthEntry>, max: Int): com.wb.api.model.AuthEntry? {
        val t = now()
        val usable = pool.filter { a ->
            val s = store.stateOf(a.uid)
            !(s.cool_kind == "hard_credit" && s.cool_until > t) && inFlightOf(a.uid) < max
        }
        val best = usable.minByOrNull { expiryOf(store.stateOf(it.uid)) } ?: return null
        val exp = expiryOf(store.stateOf(best.uid))
        if (exp <= 0L) return null
        LogBus.log("fallback_earliest_expiry uid=${best.uid.take(8)} kind=${store.stateOf(best.uid).cool_kind} 恢复于 ${Durations.format(exp - t)}后 -> 仍尝试")
        return best
    }
}

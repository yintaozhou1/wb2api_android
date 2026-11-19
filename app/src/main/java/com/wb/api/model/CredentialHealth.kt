package com.wb.api.model

/**
 * 登录凭证健康度的**单一判定口径**。
 *
 * 为什么单独抽出来：修 1.7.4 之前，「账号是否可用」被拆成了三份互不相干的判断 ——
 *  - 账号页只看 `disabled / paused / 冷却`，于是**凭证早已过期**的号照样显示「可用」；
 *  - 选号只看池状态，照样把过期号选中，白撞一次 401；
 *  - 「网络自检」只看 `access_token` 字段是不是非空，于是回一句「token=有」。
 * 三处都只看「有没有字段」，没人看「还有多久过期」。现在统一由这里判定，UI / 选号 /
 * 自检都调用同一份逻辑，避免三处口径漂移（这是「拆东墙补西墙」的典型来源）。
 *
 * 时间口径全部是 **epoch 秒**（与 [AuthEntry] 一致）。
 */
enum class CredentialState {
    /** AT 与 RT 都缺失：无法发出任何请求。 */
    MISSING,

    /**
     * AT 已过期，但还有 RT 可以续期。
     *
     * 注意：这**不等于不能用** —— 下一个请求（或保活巡检）会先 refresh 再发。
     * 所以它是一条「需要动作」的状态，而不是「已死」。
     */
    ACCESS_EXPIRED,

    /**
     * AT 不可用**且根本没有 refresh_token**：续期已不可能。
     *
     * 注意：这里**不看** RT 的到期时间。RT 的 30 天是本地**估算值**（上游授权响应不带
     * refresh 的到期时间），而闲置超过 30 天的号 RT 往往依然有效 —— 按估算值判死会让
     * 这类号被永久排除在选号之外，连一次 refresh 都不会试。真正「RT 已死」只能由上游
     * 明确拒绝（401 求证的 DEAD / `session_dead`）确认。
     */
    REFRESH_EXPIRED,

    /** AT 在 [CredentialHealth.EXPIRING_WINDOW_SEC] 内过期：仍可用，但很快需要续期。 */
    EXPIRING,

    /** AT 有效且不临期。 */
    OK,
}

object CredentialHealth {

    /** 「临期」窗口：10 分钟。与 `ChatHandler.ensureFreshToken` 的 120 秒阈值分工不同 ——
     *  120 秒是「请求路径上的兜底」，这里是「提前告诉用户 + 巡检提前续」。 */
    const val EXPIRING_WINDOW_SEC = 600L

    fun of(a: AuthEntry, nowSec: Long = System.currentTimeMillis() / 1000): CredentialState =
        of(a.access_token, a.access_expires_at, a.refresh_token, a.refresh_expires_at, nowSec)

    fun of(
        accessToken: String,
        accessExpiresAt: Long,
        refreshToken: String,
        refreshExpiresAt: Long,
        nowSec: Long,
    ): CredentialState = ofFlags(
        hasAccess = accessToken.isNotBlank(),
        accessExpiresAt = accessExpiresAt,
        hasRefresh = refreshToken.isNotBlank(),
        refreshExpiresAt = refreshExpiresAt,
        nowSec = nowSec,
    )

    /** 只按「是否持有 + 到期时刻」判定（UI 的 AccountView 不持有 token 原文）。 */
    fun ofFlags(
        hasAccess: Boolean,
        accessExpiresAt: Long,
        hasRefresh: Boolean,
        refreshExpiresAt: Long,
        nowSec: Long,
    ): CredentialState {
        val hasAt = hasAccess
        val hasRt = hasRefresh
        val atExpired = accessExpiresAt > 0 && accessExpiresAt <= nowSec

        if (!hasAt && !hasRt) return CredentialState.MISSING
        // 有 RT 就永远不算「确定死」：估算的 RT 到期时间不足以定罪（见枚举注释）。
        if (!hasAt) return if (hasRt) CredentialState.ACCESS_EXPIRED else CredentialState.REFRESH_EXPIRED
        if (atExpired) return if (hasRt) CredentialState.ACCESS_EXPIRED else CredentialState.REFRESH_EXPIRED
        if (!hasRt) {
            // 只有 AT：现在能用，但到期后无法续 —— 临期时按 EXPIRING 提前提醒。
            return if (accessExpiresAt > 0 && accessExpiresAt - nowSec <= EXPIRING_WINDOW_SEC) {
                CredentialState.EXPIRING
            } else {
                CredentialState.OK
            }
        }
        if (accessExpiresAt > 0 && accessExpiresAt - nowSec <= EXPIRING_WINDOW_SEC) {
            return CredentialState.EXPIRING
        }
        return CredentialState.OK
    }

    /** 当前是否能发出请求（本地可判定为「必然失败」的状态返回 false）。 */
    fun canServe(state: CredentialState): Boolean =
        state != CredentialState.MISSING && state != CredentialState.REFRESH_EXPIRED

    /**
     * 是否应当主动续期（有 RT、且 AT 临期/已过期）。
     *
     * 不看 `refreshExpiresAt`：那是本地估算，真到「估算过期」时更该试一次 —— 试成了就
     * 把估算值一起刷新（`Upstream.renewedAuth`），试输了会由上游明确拒绝并判死。
     * 反过来「因为估算过期就不试」会让号永久卡在过期状态。
     */
    fun needsRenew(
        accessToken: String,
        accessExpiresAt: Long,
        refreshToken: String,
        @Suppress("UNUSED_PARAMETER") refreshExpiresAt: Long,
        nowSec: Long,
        windowSec: Long = EXPIRING_WINDOW_SEC,
    ): Boolean {
        if (refreshToken.isBlank()) return false
        if (accessToken.isBlank()) return true
        if (accessExpiresAt <= 0) return false
        return accessExpiresAt - nowSec <= windowSec
    }

    /** 剩余有效期（秒）；未知到期时间返回 -1。 */
    fun remainingSec(accessExpiresAt: Long, nowSec: Long): Long =
        if (accessExpiresAt <= 0) -1 else (accessExpiresAt - nowSec).coerceAtLeast(0)

    /** 面向用户的一句话说明（详情页 / 自检结论共用）。 */
    fun describe(state: CredentialState, remainingSec: Long): String = when (state) {
        CredentialState.OK ->
            if (remainingSec < 0) "有效（到期时间未知）" else "有效 · 剩余 ${fmt(remainingSec)}"
        CredentialState.EXPIRING ->
            if (remainingSec < 0) "即将过期" else "即将过期 · 剩余 ${fmt(remainingSec)}"
        CredentialState.ACCESS_EXPIRED -> "访问令牌已过期 · 可自动续期"
        CredentialState.REFRESH_EXPIRED -> "没有续期凭证 · 需要重新登录"
        CredentialState.MISSING -> "凭证缺失 · 需要重新登录"
    }

    /** 秒 → 「x 小时 y 分」/「y 分」/「不到 1 分」。 */
    fun fmt(sec: Long): String = when {
        sec <= 0 -> "已过期"
        sec < 60 -> "不到 1 分"
        sec < 3600 -> "${sec / 60} 分"
        else -> {
            val h = sec / 3600
            val m = (sec % 3600) / 60
            if (m == 0L) "$h 小时" else "$h 小时 $m 分"
        }
    }
}

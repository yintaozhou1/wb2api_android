package com.wb.api.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.wb.api.core.Durations
import com.wb.api.model.CredentialHealth
import com.wb.api.model.CredentialState
import com.wb.api.pool.AccountView

/** 语义色（状态派生出「语气」，具体颜色由 UI 在当前主题下解析）。 */
enum class AccountTone { Ok, Warn, Danger, Info }

/**
 * 账号「一眼可读」的状态 —— **唯一**的状态派生入口。
 *
 * 1.7.4 之前，账号页与详情页各写了一份 `when { disabled / paused / 冷却 / else 可用 }`，
 * 而这两份都只看**池状态**、不看凭证本身：于是「access 过期、refresh 也失效」的号
 * 依旧显示绿色的「可用」，点进去「网络自检」也只回一句「token=有」。
 *
 * 现在的优先级（从上到下，先命中者胜）：
 *  1. 服务端已判死（`disabled` 且 `cool_kind == session_dead`）→ 凭证失效，需重新登录
 *  2. 用户手动禁用 / 暂停 → 如实显示（这是用户意图，不该被凭证状态盖住）
 *  3. 冷却中 → 如实显示
 *  4. 凭证**确定不可服务**（无凭证 / AT 过期且 RT 也过期）→ 凭证失效
 *  5. AT 已过期但可续 → 凭证过期（下次请求或巡检会自动续）
 *  6. AT 临期（≤10 分钟）→ 仍「可用」，但 detail 提示「x 分钟后到期」
 *  7. 其余 → 可用
 */
@androidx.compose.runtime.Immutable
data class AccountStatus(
    /** 主徽标文案（卡片右上 / 详情页头部）。 */
    val label: String,
    val tone: AccountTone,
    /**
     * 副说明：**只在需要引起注意时非空**（凭证异常 / 冷却剩余）。
     * 正常账号留空 —— 卡片第三行会回落到「在途 / 近期速率」，不被每秒变化的倒计时占满。
     */
    val detail: String = "",
    /** 是否需要用户动作（决定详情页是否显示「立即续期」）。 */
    val action: Action = Action.None,
) {
    enum class Action { None, Renew, Relogin }
}

/** 把语义语气解析成当前主题下的颜色（只在 Composable 里调用）。 */
@Composable
fun AccountStatus.color(): Color = when (tone) {
    AccountTone.Ok -> WbPalette.ok
    AccountTone.Warn -> WbPalette.warn
    AccountTone.Danger -> WbPalette.danger
    AccountTone.Info -> WbPalette.info
}

/** 由 [AccountView] 派生状态；[nowSec] 允许调用方复用同一时刻快照。 */
fun accountStatus(v: AccountView, nowSec: Long = System.currentTimeMillis() / 1000): AccountStatus {
    val cred = v.credentialState(nowSec)
    val left = CredentialHealth.remainingSec(v.access_expires_at, nowSec)
    return when {
        // ① 服务端已判死：比「已禁用」更具体，直接告诉用户下一步动作。
        v.disabled && v.cool_kind == "session_dead" -> AccountStatus(
            "凭证失效", AccountTone.Danger,
            "续期被上游拒绝，需要重新登录", AccountStatus.Action.Relogin,
        )
        v.disabled -> AccountStatus("已禁用", AccountTone.Danger, "已停止接流量，可手动启用")
        v.paused -> AccountStatus("已暂停选号", AccountTone.Info, "不接新请求，凭证与冷却保留")
        v.cool_remaining_sec > 0 -> AccountStatus(
            "冷却中", AccountTone.Warn,
            "剩余 ${Durations.format(v.cool_remaining_sec)}" +
                if (v.cool_kind.isNotBlank()) " · ${v.cool_kind}" else "",
        )
        // ② 凭证层：确定不可用
        cred == CredentialState.REFRESH_EXPIRED -> AccountStatus(
            "凭证失效", AccountTone.Danger, "没有续期凭证且访问令牌已过期，需要重新登录", AccountStatus.Action.Relogin,
        )
        cred == CredentialState.MISSING -> AccountStatus(
            "凭证缺失", AccountTone.Danger, "没有可用的登录凭证，需要重新登录", AccountStatus.Action.Relogin,
        )
        // ③ 可续：不是「已死」，别让用户去重新登录
        cred == CredentialState.ACCESS_EXPIRED -> AccountStatus(
            "凭证过期", AccountTone.Warn,
            if (v.has_refresh) "访问令牌已过期 · 下次请求自动续期" else "访问令牌已过期 · 无续期凭证",
            if (v.has_refresh) AccountStatus.Action.Renew else AccountStatus.Action.Relogin,
        )
        cred == CredentialState.EXPIRING -> AccountStatus(
            "可用", AccountTone.Ok,
            "访问令牌 ${CredentialHealth.fmt(left)}后到期",
            if (v.has_refresh) AccountStatus.Action.Renew else AccountStatus.Action.None,
        )
        else -> AccountStatus("可用", AccountTone.Ok)
    }
}

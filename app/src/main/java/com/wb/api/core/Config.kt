package com.wb.api.core

import kotlinx.serialization.Serializable
import androidx.compose.runtime.Immutable

/**
 * 网关配置，字段名与上游 config.json 完全一致（snake_case），
 * 保证 1:1 读写兼容。默认值取自 config.example.json。
 *
 * 兼容性约定：**只增字段、不改语义、不动旧字段名**。旧版本写出的 config.json
 * 缺少新字段时由 kotlinx.serialization 的默认值补齐，因此升级不丢配置。
 */
@Serializable
@Immutable
data class GatewayConfig(
    val listen: String = ":7863",
    val api_key: String = "",
    val auth_dir: String = "./auths",
    val state_file: String = "./data/state.json",
    val panel: PanelCfg = PanelCfg(),
    val logging: LoggingCfg = LoggingCfg(),
    val cooldown: CooldownCfg = CooldownCfg(),
    val schedule: ScheduleCfg = ScheduleCfg(),
    val global: GlobalCfg = GlobalCfg(),
    val upstream: UpstreamCfg = UpstreamCfg(),
    val features: FeaturesCfg = FeaturesCfg(),
    val prompt: PromptCfg = PromptCfg(),
    val upstash: UpstashCfg = UpstashCfg(),
    val pool: PoolCfg = PoolCfg(),
    val session_sticky: SessionStickyCfg = SessionStickyCfg(),
    val ui: UiCfg = UiCfg(),
)

/**
 * 界面偏好（仅本机生效，不参与网关逻辑，也不会发给上游）。
 *
 * - theme: system（跟随系统）| light（浅色）| dark（深色），**默认跟随系统（自动）**
 * - monet: 系统动态取色（Material You，API 31+）。**默认关闭**——原生调色板随壁纸变化，
 *   在状态色语义（可用/冷却/禁用）需要稳定可辨的场景下反而干扰判断。
 * - model_columns: 模型列表列数（1 单列 / 2 双列）
 * - model_sort: 模型排序（default 默认 | name 名称 | context 上下文）
 * - ui_rev: 界面配置版本，用于**一次性迁移**（缺此键即视为旧配置）。
 *   2 → 3：主题默认值由「深色」改回「跟随系统」，迁移细节见 `Store.load()`。
 */
@Serializable
@Immutable
data class UiCfg(
    val theme: String = "system",
    val haptics: Boolean = true,
    val animations: Boolean = true,
    /**
     * 网关地址浮窗：启动网关后在其它应用上层显示 `127.0.0.1:<port>`。
     *
     * 默认开。需要 `SYSTEM_ALERT_WINDOW` 权限（系统设置页授予）：没有权限时浮窗静默
     * 不显示、并在启动时提示一次，**不阻断网关启动**（浮窗是附加展示，网关是主功能）。
     */
    val overlay: Boolean = true,
    val monet: Boolean = false,
    val model_columns: Int = 2,
    val model_sort: String = "default",
    val density: String = "normal",
    val ui_rev: Int = 3,
)

@Serializable
@Immutable
data class PanelCfg(val package_detail_limit: Int = 5)

/**
 * 请求归档（原「请求归档」开关，语义不清晰，现改为可细调的一组选项）：
 * - request_archive_enabled: 总开关
 * - request_client_info: 是否记录客户端 IP / UA（隐私相关，可关）
 * - request_retention_days: **预留字段**（当前实现只按容量裁剪、不按天清理；保留字段
 *   以兼容旧配置，不要让 UI 或注释承诺「超期自动清理」）
 * - request_archive_max_mb: 归档文件容量上限（MB），超出后按最旧优先丢弃
 * - request_body_log: 是否额外记录出站请求体摘要（默认关，仅排查时打开）
 */
@Serializable
@Immutable
data class LoggingCfg(
    val request_archive_enabled: Boolean = true,
    val request_retention_days: Int = 7,
    val request_archive_max_mb: Int = 100,
    val request_client_info: Boolean = true,
    val request_body_log: Boolean = false,
)

@Serializable
@Immutable
data class CooldownCfg(
    val soft_rate: String = "600s",
    val soft_rate_max: String = "2h",
)

/**
 * 定时任务。
 *
 * checkin_mode 决定「定时签到」的触发方式（这是原实现最大的逻辑盲区）：
 * - "fixed"  ：固定时间点签到，时间点取自 checkin_hours（默认 9 / 21 点）
 * - "missed" ：每 checkin_scan_minutes 分钟巡检一次，**发现有账号当天未签到就补签**
 *              （对齐用户诉求「检测账户未签到就自动签到」）
 *
 * 其余 *_enabled 为各定时任务开关；*_hours 为触发小时（0-23）。
 */
@Serializable
@Immutable
data class ScheduleCfg(
    val checkin_hours: List<Int> = listOf(9, 21),
    val growth_hours: List<Int> = listOf(1),
    val travel_hours: List<Int> = listOf(9, 21),
    val activity_hours: List<Int> = listOf(10),
    val keepalive_hours: List<Int> = listOf(22),
    val blackcat_hours: List<Int> = listOf(23),
    val checkin_enabled: Boolean = true,
    val growth_enabled: Boolean = true,
    val travel_enabled: Boolean = true,
    val activity_enabled: Boolean = true,
    val keepalive_enabled: Boolean = true,
    val blackcat_enabled: Boolean = true,
    val balance_refresh_enabled: Boolean = true,
    val balance_refresh_minutes: Int = 5,
    val checkin_mode: String = "fixed",
    val checkin_scan_minutes: Int = 30,
    /** 巡检式补签是否在启动网关时立即执行一次（避免刚启动就等 30 分钟）。 */
    val checkin_on_start: Boolean = true,
)

@Serializable
@Immutable
data class GlobalCfg(
    val enabled: Boolean = true,
    val chat_base: String = "",
    val billing_base: String = "",
)

@Serializable
@Immutable
data class UpstreamCfg(
    val timeout_seconds: Int = 120,
    val header_timeout_seconds: Int = 120,
    val idle_timeout_seconds: Int = 300,
    val user_agent: String = "",
    val client_version: String = "",
    val cli_version: String = "",
    val client_name: String = "",
    val device_token: String = "",
    val device_token_file: String = "",
    val passthrough_ip: Boolean = false,
    /**
     * 减少首字延迟（TTFT）/ 提高上游前缀缓存命中：
     * 出站连接池复用 + 不做无谓的 body 重排（默认开）。
     */
    val low_latency: Boolean = true,
)

@Serializable
@Immutable
data class FeaturesCfg(val sanitize_blacklist_fingerprints: Boolean = true)

@Serializable
@Immutable
data class PromptCfg(val mode: String = "passthrough", val file: String = "")

@Serializable
@Immutable
data class UpstashCfg(val url: String = "", val token: String = "")

@Serializable
@Immutable
data class PoolCfg(
    // 默认值（1.4.9 调整）：单账号 5、global 池 7；两者在设置页的可调上限同为 20。
    val max_in_flight: Int = 5,
    val max_in_flight_global: Int = 7,
    val breaker_threshold: Int = 3,
    val breaker_cooldown: String = "30m",
    val breaker_cooldown_max: String = "6h",
    val degrade_threshold: Int = 5,
    val degrade_cooldown: String = "10m",
    val degrade_cooldown_max: String = "2h",
    val idle_weight_per_hour: Double = 0.5,
    val idle_weight_max: Double = 5.0,
    val prefer_expiring: Boolean = true,
    val expiring_soon: String = "168h",
    val cost_explore_interval: String = "30m",
    /**
     * 积分保底：**当前仅展示**（`/status` 回显），未参与选号。
     *
     * 不接入选号的理由：账号余额在「尚未刷新」时是 0（不是未知），按 floor 过滤会把
     * 新加账号整批排除，代价远大于收益（真正的 402 由 HARD_CREDIT 分支冷却到次日）。
     * 旧注释把它写成「选号失败成因」，与实现不符，一并纠正。
     */
    val credit_floor: Long = 100,
)

@Serializable
@Immutable
data class SessionStickyCfg(
    val enabled: Boolean = true,
    val ttl: String = "30m",
    val gc_interval: String = "5m",
)

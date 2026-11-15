package com.wb.api.model

import kotlinx.serialization.Serializable
import androidx.compose.runtime.Immutable

/** auths 目录下的账号凭证文件（明文，与上游一致；落盘权限受限）。 */
@Serializable
@Immutable
data class AuthEntry(
    val uid: String = "",
    val nickname: String = "",
    val realm: String = "cn",
    val access_token: String = "",
    val refresh_token: String = "",
    val access_expires_at: Long = 0,   // epoch 秒
    val refresh_expires_at: Long = 0,  // epoch 秒
    val device_token: String = "",
    val device_id: String = "",
    val domain: String = "",
    val enterprise_id: String = "",
    val region: String = "",
    val created_at: Long = 0,
    val updated_at: Long = 0,
) {
    /**
     * 是否为企业版账号（凭证带非空 `enterprise_id`）。
     *
     * 对齐上游 `auth.Auth.IsEnterprise()`。企业版**没有个人成长体系**：签到等端点上
     * 一律被拒（`daily-checkin` → 400 code 10001「企业账号不支持该操作」；growth 系列接口 → 403），
     * 额度也不在个人「资源包」体系内 —— 所以这两处都必须按企业口径分流/跳过。
     */
    val isEnterprise: Boolean get() = enterprise_id.isNotBlank()
}

/** data/state.json 中每个账号的运行时状态。 */
@Serializable
@Immutable
data class AccountState(
    val uid: String = "",
    val disabled: Boolean = false,
    /**
     * 暂停选号（对齐上游 Go `entry.paused`）：账号**退出选号候选**，但状态/冷却/计数一律不动。
     *
     * 与 [disabled] 的区别：disabled 是「终态」——判死，需要人工启用；paused 是「临时让位」——
     * 账号本身健康，随时可恢复，且不清冷却台账（恢复后已有的冷却/熔断观测仍然有效）。
     */
    val paused: Boolean = false,
    val breaker_until: Long = 0,
    val breaker_fail_streak: Int = 0,
    /**
     * 熔断**触发次数**（对齐 Go entry.retryCount）：驱动熔断指数退避的指数。
     * 触发一次 +1、成功请求清零；与「连续失败计数」[breaker_fail_streak] 分工不同。
     */
    val breaker_retry: Int = 0,
    val degrade_until: Long = 0,
    val degrade_fail_streak: Int = 0,
    val cool_until: Long = 0,
    val cool_kind: String = "",
    val soft_streak: Int = 0,
    val credits: Double = 0.0,
    val credits_total: Double = 0.0,
    val earliest_remaining: Double = 0.0,
    /**
     * **最早未来到期批次**的时刻（epoch 秒，0 = 无有效批次）。
     *
     * 与 [earliest_remaining]（该批次的剩余量之和）配对，由余额查询（[com.wb.api.upstream.UpstreamClient.balanceDetailed]）
     * 写入，供账号池 `prefer_expiring` 加权使用。
     */
    val earliest_at: Long = 0,
    val success_count: Long = 0,
    val err_total: Long = 0,
    val last_used_at: Long = 0,
    val last_checkin_at: Long = 0,
    val last_balance_at: Long = 0,
    val model_costs: Map<String, Double> = emptyMap(),
    val rate_limited_models: List<ModelLimit> = emptyList(),
    val token_usage: TokenUsage = TokenUsage(),
)

@Serializable
@Immutable
data class ModelLimit(
    val model: String = "",
    val kind: String = "",          // rate_limit | model_unavailable
    val reset_at: Long = 0,
    val until: Long = 0,
    val hits: Int = 0,              // 11102 负缓存命中次数（指数退避用）
    // 触发原因（优先上游原文，截断 200 字符）。模型级阻塞报错时原样带给客户端
    // （对齐上游 cooldown 台账的 Reason → model_unavailable 的 hint）。
    val reason: String = "",
)

@Serializable
@Immutable
data class TokenUsage(
    val request_count: Long = 0,
    val total_tokens: Long = 0,
    val last_latency_ms: Long = 0,
    val last_tokens_per_second: Double = 0.0,
    /** 累计输入（prompt）token，用于计算缓存命中率。 */
    val prompt_tokens: Long = 0,
    /** 累计命中上游前缀缓存的输入 token（cached_tokens）。 */
    val cached_tokens: Long = 0,
)

/** 池整体状态文件。 */
@Serializable
@Immutable
data class PoolStateFile(
    val accounts: Map<String, AccountState> = emptyMap(),
    val sticky: Map<String, StickyBind> = emptyMap(),
    val usage_seq: Long = 0,
)

@Serializable
@Immutable
data class StickyBind(
    val uid: String = "",
    val updated_at: Long = 0,
)

/** 单条用量记录（usage.json 追加存储）。 */
@Serializable
@Immutable
data class UsageRecord(
    val t: Long = 0,
    val account: String = "",
    val model: String = "",
    val realm: String = "cn",
    val prompt_tokens: Long = 0,
    val completion_tokens: Long = 0,
    val total_tokens: Long = 0,
    val latency_ms: Long = 0,
    val credit: Double = 0.0,
    val error: Boolean = false,
)

/** 上游模型信息条目。 */
@Serializable
@Immutable
data class ModelInfo(
    val id: String = "",             // cn:<model> / global:<model>
    val name: String = "",
    val description: String = "",
    val credits: String = "",
    val tags: List<String> = emptyList(),
    val vendor: String = "",
    val is_default: Boolean = false,
    val supports_images: Boolean = false,
    val supports_reasoning: Boolean = false,
    val can_disable_thinking: Boolean = false,
    val supports_tool_call: Boolean = false,
    val only_reasoning: Boolean = false,
    val max_allowed_size: Long = 0,
    val default_effort: String = "",
    /** 上游 reasoning.summary（思考摘要档位，可空）。 */
    val reasoning_summary: String = "",
    val supported_efforts: List<String> = emptyList(),
    /** 上下文窗口（**仅取上游动态字段 maxInputTokens**；上游未下发则为 0 = 未知，不做任何兜底）。 */
    val context_length: Long = 0,
    /** 最大输出 token（**仅取上游动态字段 maxOutputTokens**；上游未下发则为 0 = 未知，不编造）。 */
    val max_output_tokens: Long = 0,
    val realm: String = "cn",
    val model: String = "",
)

/** 成长任务。 */
@Serializable
@Immutable
data class GrowTask(
    val task_code: String = "",
    val title: String = "",
    val description: String = "",
    val current: Int = 0,
    val target: Int = 0,
    val done: Boolean = false,
    val claimed: Boolean = false,
    val auto: Boolean = false,
    val reward: String = "",
)

/** 运行期请求指标。 */
@Serializable
@Immutable
data class RequestMetrics(
    val completed: Long = 0,
    val success: Long = 0,
    val httpSuccess: Long = 0,
    val avgDurationMs: Long = 0,
    val inFlight: Int = 0,
)

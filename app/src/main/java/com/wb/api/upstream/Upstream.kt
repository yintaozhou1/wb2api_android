package com.wb.api.upstream

import com.wb.api.core.AppJson
import com.wb.api.core.objOrNull
import com.wb.api.core.primText
import com.wb.api.core.primTextOrNull
import com.wb.api.core.LogBus
import com.wb.api.model.AuthEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 上游常量：base URL / path / UA / 稳定派生（与 Go 源码逐字对齐）。 */
object Up {
    const val CHAT_BASE_CN = "https://copilot.tencent.com"
    const val BILLING_BASE_CN = "https://www.codebuddy.cn"
    const val WEB_BASE_CN = "https://www.workbuddy.cn"
    const val GLOBAL_BASE = "https://www.workbuddy.ai"
    const val ORIGIN_CN = "https://www.codebuddy.cn"
    const val ORIGIN_GLOBAL = "https://www.workbuddy.ai"

    /** 设备授权 / 插件域仍用插件 CLI 形态（该端点按插件口径签发）。 */
    const val UA_CLI = "CLI/2.63.2 CodeBuddy/2.63.2"
    const val UA_IDE = "CodeBuddyIDE/4.12.0 CodeBuddy/4.12.0"

    /**
     * 出站 WorkBuddy 客户端版本 —— 对齐新上游 `headers.go`（2026-10-20/21 双版本真机抓包）：
     * 国内版 **5.8.3**（endpoint www.workbuddy.cn）、国际版 **5.8.4**（www.workbuddy.ai）。
     * 两版发版节奏不同，**必须按 realm 分开**：国际版用 CN 的版本号会与上游实际分发包对不上
     * （旧实现共用 5.5.4，是「UA 指纹过期」的一部分）。
     */
    const val DEFAULT_CLIENT_VERSION_CN = "5.8.3"
    const val DEFAULT_CLIENT_VERSION_GLOBAL = "5.8.4"
    /** 官方内置 CLI 版本（两版分发包同一值）。 */
    /** 官方 CLI 版本（2026-10-24 起为 2.174.0）：UA 的 `CLI/<v>` 段与登录链接的 version 同源。 */
    const val DEFAULT_CLI_VERSION = "2.174.0"

    /** 按 realm 取生效的客户端版本。 */
    fun clientVersion(realm: String): String =
        if (realm == "global") DEFAULT_CLIENT_VERSION_GLOBAL else DEFAULT_CLIENT_VERSION_CN

    // paths
    const val P_CHAT = "/v2/chat/completions"
    const val P_MODELS_V3 = "/v3/config"
    const val P_MODELS_BIZ = "/console/enterprises/personal/models"
    const val P_MODELS_BIZ2 = "/v2/enterprises/personal/models"
    const val P_AUTH_STATE = "/v2/plugin/auth/state"
    const val P_AUTH_TOKEN = "/v2/plugin/auth/token"
    const val P_AUTH_REFRESH = "/v2/plugin/auth/token/refresh"
    const val P_LOGIN_ACCOUNT = "/v2/plugin/login/account"
    const val P_REPORT = "/v2/report"
    const val P_CHECKIN = "/v2/billing/meter/daily-checkin"
    const val P_CHECKIN_ALT = "/billing/meter/daily-checkin"
    const val P_RESOURCE = "/v2/billing/meter/get-user-resource"
    const val P_RESOURCE_ALT = "/billing/meter/get-user-resource"

    /**
     * 企业版账号的**分配额度**查询（对齐上游 `internal/upstream/client.go enterpriseResource`）。
     *
     * 为什么必须分流：企业额度不在个人「资源包」体系内 —— `get-user-resource*` 对
     * `enterpriseId` 非空的账号恒返回空 `Accounts`（实测 code 0 且 Accounts=null）→
     * 企业号余额会**恒显示 0**（额度其实是有的）。`X-Enterprise-Id` 由 billing 头自动注入。
     */
    const val P_ENTERPRISE_USAGE = "/v2/billing/meter/get-enterprise-user-usage"
    const val P_ENTERPRISE_USAGE_ALT = "/billing/meter/get-enterprise-user-usage"

    /**
     * 企业版「不限量」（上游口径 `limitNum < 0`）在池内的等价剩余 —— **本端自定的哨兵值**：
     * 上游 Go 并没有对应的常量或状态位（曾误标为「对齐上游 enterpriseUnlimitedRemain」）。
     * 用一个足够大的值让路由权重与 credit_floor 两处都不构成限制；界面上由
     * `wbCreditsText()` 显示成「不限」。
     */
    const val ENTERPRISE_UNLIMITED_REMAIN: Double = 1099511627776.0

    /** 「快过期」默认窗口（与 `PoolCfg.expiring_soon` 默认的 168h 一致），调用方可覆盖。 */
    const val EXPIRING_SOON_DEFAULT_SEC: Long = 168 * 3600L
    const val P_COUNTRY = "/billing/area/get-country-code"
    const val P_GROWTH_TASKS = "/v2/activity/growth/tasks"
    const val P_GROWTH_ACCEPT = "/v2/activity/growth/tasks/accept"

    // ── 成长任务真实移植：扩展路径 / UA / 头值（逐字对齐 Go streak.go / travel.go / desktop.go / school.go）──
    /** 连登完整状态（streak.go:24）。 */
    const val P_GROWTH_STREAK = "/activity/growth/streak"
    /** 连登档位兑换（streak.go:21）。 */
    const val P_GROWTH_REDEEM = "/activity/growth/redeem"
    /** 抽奖次数查询（streak.go:22）。 */
    const val P_GROWTH_LOTTERY_SUMMARY = "/activity/growth/lottery/summary"
    /** 抽奖一次（streak.go:23）。 */
    const val P_GROWTH_LOTTERY_DRAW = "/activity/growth/lottery/draw"
    /** 漏签 heatmap（blackcat.go:100）。 */
    const val P_GROWTH_HEATMAP = "/activity/growth/heatmap"
    /** 使用补签卡（blackcat.go:123）。 */
    const val P_GROWTH_MAKEUP = "/activity/growth/makeup-cards/use"
    /**
     * 领奖路径前缀：**task_code 在路径里、无 /v2 前缀、无 body**（tasks.go:216-253 / 111-131）。
     * 默认口径在 Web 域（www.workbuddy.cn），mp 口径在 chat 域（copilot.tencent.com）——见 §2.5/2.6。
     */
    const val P_GROWTH_CLAIM_PREFIX = "/activity/growth/tasks/"
    /** Buddy 领养协议（travel.go:23）。 */
    const val P_BUDDY_AGREEMENT = "/activity/growth/buddy/agreement"
    /** 领养第一只 Buddy（travel.go:22）。 */
    const val P_BUDDY_FIRST = "/activity/growth/buddy/first"
    /** 主题设置（desktop.go:41）。 */
    const val P_APPEARANCE_SET = "/v2/user-asset/appearance/set"
    /** 新手礼包（blackcat.go:73）。 */
    const val P_CLAIM_GIFT = "/billing/meter/claim-gift"
    /** 活动补偿（blackcat.go:86）。 */
    const val P_CLAIM_COMPENSATION = "/billing/meter/claim-compensation"

    /** 桌面客户端 UA：WorkBuddy 5.8.3 内嵌 CLI 2.161.0。 */
    const val UA_DESKTOP = "WorkBuddy/5.8.3 WorkBuddy/5.8.3 CLI/2.174.0"
    /** Web 端浏览器 UA（desktop.go:267）。 */
    const val UA_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
    /** 小程序口径头值（tasks.go:35）。 */
    const val MP_PLATFORM = "miniprogram"

    fun base(realm: String, billing: Boolean = false): String = when {
        realm == "global" -> GLOBAL_BASE
        billing -> BILLING_BASE_CN
        else -> CHAT_BASE_CN
    }

    fun origin(realm: String): String = if (realm == "global") ORIGIN_GLOBAL else ORIGIN_CN

    /**
     * 计费域 base 候选（按序尝试）：
     * CN → [codebuddy.cn, copilot.tencent.com]。某些网络环境下 codebuddy.cn 的 API 路由不可达
     * （表现为 `404 page not found`），而 CLI 域 copilot.tencent.com 同名路径存在，可兜底。
     * global → [workbuddy.ai]。
     */
    fun billingBases(realm: String): List<String> =
        if (realm == "global") listOf(GLOBAL_BASE) else listOf(BILLING_BASE_CN, CHAT_BASE_CN)

    /** 对话/模型域 base 候选：CN → [copilot.tencent.com, codebuddy.cn]；global → [workbuddy.ai]。 */
    fun chatBases(realm: String): List<String> =
        if (realm == "global") listOf(GLOBAL_BASE) else listOf(CHAT_BASE_CN, BILLING_BASE_CN)

    /**
     * Web 域 base 候选（领奖 / web 指纹上报）：CN → [www.workbuddy.cn]；global → [workbuddy.ai]。
     * 对齐 Go client.go:850 webBase；**不做 chat 域回退**（领奖只在 Web 域提供，见 tasks.go:218-225）。
     */
    fun webBases(realm: String): List<String> =
        if (realm == "global") listOf(GLOBAL_BASE) else listOf(WEB_BASE_CN)

    /** billing/meter 路径候选：global 先无 /v2 前缀；CN 先有 /v2 前缀，均含兜底。 */
    fun meterPaths(realm: String, v2: String, alt: String): List<String> =
        if (realm == "global") listOf(alt, v2) else listOf(v2, alt)

    /**
     * 三段式 WorkBuddy UA（官方 `buildUserAgent` 形态）：`WorkBuddy/<v> <brand>/<v> CLI/<cli>`。
     *
     * 首段**恒为驼峰 WorkBuddy**，只有第二段随 realm 换品牌：CN `WorkBuddy`、
     * global `workbuddy-ai`（国际版 productName，小写连字符）。旧实现给 global 用
     * `WorkBuddy AI`（带空格大写）与真机抓包不符。
     */
    fun workBuddyUA(
        realm: String,
        clientVersion: String = clientVersion(realm),
        cliVersion: String = DEFAULT_CLI_VERSION,
    ): String {
        val brand = if (realm == "global") "workbuddy-ai" else "WorkBuddy"
        return "WorkBuddy/$clientVersion $brand/$clientVersion CLI/$cliVersion"
    }

    /** billing 域单段 UA（官方 banner 覆写形态，按 realm 取版本）。 */
    fun billingUA(realm: String = "cn", clientVersion: String = clientVersion(realm)): String =
        "WorkBuddy/$clientVersion"

    /**
     * `/v3/config` 的第二路探测 UA：单品牌两段式（`WorkBuddy/<v> CLI/<cli>`）。
     * 该端点按 UA 家族下发不同模型集合，官方真机同时出现两种形态，故两路都要探。
     */
    fun workBuddyUaLite(realm: String): String =
        "WorkBuddy/${clientVersion(realm)} CLI/$DEFAULT_CLI_VERSION"

    /** 按 uid + 用途稳定派生 36 hex 标识（跨重启恒定，账号间互异）。 */
    fun stableId(uid: String, purpose: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("wb2a:$purpose:$uid".toByteArray())
        return d.take(18).joinToString("") { "%02x".format(it) }
    }

    /**
     * `X-Conversation-ID`（客户端没声明会话时使用）—— **渠道校验三件套**之一。
     *
     * 上游按「ID / Request-ID / Message-ID 是否齐全」做渠道校验，缺任一头即
     * 400 code=11-128 "conversation id missing"；2026-10-21 实测的 11128
     * "request illegal"（displayTips「没通过渠道校验」）同属这一族。
     *
     * 该头同时是上游**前缀缓存槽**的实际来源，所以两个约束都不能丢：
     *  - 按 uid 隔离：同一客户端会话打在不同账号上不能共用一个缓存槽；
     *  - 跨轮稳定：每轮都变等于每轮都是新会话，前缀缓存与账号粘性同时失效。
     *
     * 空会话键（无 messages 可比对、面板探针等）现造一个 32 hex：上游只要求
     * 「有值」，这类路径本来也没有会话状态可言。
     */
    fun conversationId(uid: String, sessionKey: String?): String {
        val key = sessionKey?.trim().orEmpty()
        // 无会话源（无 messages 可比对 / 面板探针）：现造一个 UUID v4 形态值。
        // 上游同样如此（session.NewConversationID），只是不需要跨请求稳定。
        if (key.isEmpty()) return uuidFor(uid, com.wb.api.core.Ids.randomHex(32), "")
        return uuidFor(uid, key, "")
    }

    /**
     * 由 (uid, 会话键 [, 用途]) 稳定派生 **UUID v4 形态**（36 字符带横线）。
     *
     * 为什么必须是这个形态（issue #51 真机取证）：官方 `X-Conversation-ID` 恒为
     * `xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx`；旧实现 `wb2a-<uid8>-<hex>` 长 48、
     * 自带项目缩写、还把账号 UID 前 8 位明文写进请求头与 body —— 三处都可与官方值
     * 区分，等于自证网关身份。
     *
     * 语义不变：uid 仍参与哈希输入（跨账号绝不碰撞），同 (uid, 会话) 恒得同值
     * （前缀缓存槽语义不变），[purpose] 用于同一会话需要多个互异稳定值时的域分隔
     * （例如 X-Conversation-ID 与 acp-connection-id）。
     *
     * 版本/变体位按 RFC 4122 置位（第 7 字节 0x40、第 9 字节 0x80），否则
     * `4xxx` / `89ab` 位置出现别的值，本身又成了一种可判别特征。
     */
    fun uuidFor(uid: String, key: String?, purpose: String = ""): String {
        val input = uid + "|" + (key?.trim().orEmpty()) + if (purpose.isEmpty()) "" else "|$purpose"
        val sum = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        val u = sum.copyOf(16)
        u[6] = ((u[6].toInt() and 0x0f) or 0x40).toByte()
        u[8] = ((u[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = u.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) +
            "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32)
    }

    /**
     * 官方形态的消息/请求 ID：32 位小写 hex，**前 12 位是毫秒时间戳**。
     *
     * 官方真机样本 `01a160b2b722740da9b34be7d909a6ed`（5.8.4，2026-10-22 抓包）：
     * 前 48 bit 解出 1792623687458 ms，正是该次发送的时刻，且同会话内严格单调。
     * 纯随机 ID 按时间排序是乱的，本身构成一种可聚类的批次特征（issue #51）。
     */
    fun messageId(): String =
        "%012x".format(System.currentTimeMillis()) + com.wb.api.core.Ids.randomHex(20)

    /** 纯随机 32 hex（X-Trace-ID / B3 TraceId）：官方这两个值与 request id 恒不相等。 */
    fun traceId(): String = com.wb.api.core.Ids.randomHex(32)

    /** 纯随机 16 hex（B3 SpanId / ParentSpanId）：官方 span 与 trace、request 均无关系。 */
    fun spanId(): String = com.wb.api.core.Ids.randomHex(16)

    /**
     * B3 TraceId 合法性：只认 16 / 32 位 hex。
     *
     * B3 规范如此；把任意形状的值（带横线 / 超长 / 非 hex）塞进 X-B3-TraceId
     * 会破坏上游的链路关联，所以非法时调用方回落到恒 32 hex 的消息级 ID。
     */
    fun validTraceId(s: String): Boolean {
        if (s.length != 16 && s.length != 32) return false
        return s.all { (it in '0'..'9') || (it in 'a'..'f') || (it in 'A'..'F') }
    }
}

/**
 * chat 出站的会话头族参数（对齐新上游 `upstream.ChatMeta` + `injectConversationHeaders`）。
 *
 * 为什么需要它：上游把「官方客户端会发的那组会话头」当作**渠道校验**的一部分，
 * 一个都不发（旧实现就是如此）会被判成非官方客户端 → 400 code=11-128 / 11128。
 *
 * 各字段职责不同，一个都不能省：
 *  - [clientConversationId]：客户端在 body 里声明的 `conversation_id`，**原值透传**
 *    （上游要求透传优先，同时它是前缀缓存槽来源）；
 *  - [sessionKey]：网关派生的会话键，客户端没声明时用它 + uid 派生 X-Conversation-ID；
 *  - [turnId]：**轮级聚合主键**，一次下游请求生成一次，轮转 / 换号 / 重试全部复用，
 *    上游后台按它把「一次用户发送」内的多次上游请求聚合成一条；
 *  - [traceId]：入站 `X-Trace-ID` 透传，空则回落轮级 ID。
 *
 * 消息级 ID（X-Conversation-Message-ID / X-Request-ID / B3 SpanId）每条出站独立生成，
 * 属于传输层细节，不放进这个数据类。
 */
/** 401 的求证结果（见 [UpstreamClient.verifySession]）。 */
enum class SessionVerdict {
    /** refresh 成功：401 是边缘瞬时拦截，账号健康。 */
    ALIVE,

    /** refresh 被应用层明确拒绝：token 真失效，只能重新登录。 */
    DEAD,

    /** refresh 未给出判词（传输错误 / 5xx / 429 / HTML 拦截页 / 无 RT）：不下结论。 */
    UNKNOWN,
}

/** [UpstreamClient.verifySession] 的返回值：判词 + refresh 成功时的新凭证。 */
data class SessionProof(val verdict: SessionVerdict, val renewed: AuthEntry? = null)

data class ConvIds(
    val clientConversationId: String = "",
    val sessionKey: String = "",
    val turnId: String = "",
    val traceId: String = "",
)

class UpstreamException(val kind: ErrKind, val status: Int, val body: String) : Exception(body)

enum class ErrKind {
    NONE, HARD_CREDIT, SOFT_RATE, SESSION_DEAD, NOT_FOUND, SERVER,
    CONTENT_BLOCKED, BAD_PARAMS, ACCOUNT_FAULT, MODEL_BLOCKED,
    WAF_BLOCK, PROMPT_TOO_LONG, IMAGE_INVALID, CLIENT,

    /**
     * 14003「模型繁忙」（官方文案「当前模型请求繁忙，请切换模型或稍后重试」）。
     *
     * 与 SOFT_RATE 的分野是**瞬时 vs 持续**：生产日志里同一分钟内既有 14003 也有成功
     * （上游 issue #46），按 soft_rate 家族（600s 起、翻倍、封顶 2h）罚号会把 10 秒
     * 抖动放大成整分钟客户端可见的断流。处置：**模型级**短冷却 10s 起、翻倍、封顶 60s，
     * 不碰账号级、不换号（换号打同一个模型同样繁忙）。
     */
    MODEL_BUSY,

    /**
     * 上游**渠道校验**拦截（400 code=11-128 / 11128，msg "request illegal"）。
     *
     * 语义是「这次调用没通过渠道校验，可能不是官方客户端发出的，请重新发送」——
     * 上游自己把它归为**请求级可重试信号**，不是账号被封。处置：软冷却 + 继续轮转，
     * **绝不 Disable**（新上游 17b5204：误禁用 = 永久丢号 + 人工解冻，误软冷却 =
     * 短暂轮换 + 退避自愈，两者代价不对称）。
     */
    CHANNEL_BLOCK,
}

data class DeviceAuth(val state: String, val authUrl: String, val realm: String)

/** 单次上游 HTTP 结果（含定位信息，便于日志与报错展示）。 */
data class HttpResult(val code: Int, val text: String, val url: String) {
    val ok: Boolean get() = code in 200..299
}

/** 上游客户端：基于 OkHttp（HTTP/1.1，连接层按上游实测加固）。 */
class UpstreamClient(
    private val userAgentOverride: String = "",
    timeoutSeconds: Long = 120,
    private val lowLatency: Boolean = true,
) {
    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    /**
     * 出站连接层：**强制 HTTP/1.1**（Go 版实测：ALPN 协商出的 h2 在上游会出现
     * "timeout awaiting response headers"/异常路由，唯一正确做法是禁用 h2），
     * 并设短 keepalive / 握手超时 / 空闲连接 30s 淘汰。
     *
     * 首字延迟（TTFT）优化：keep-alive 空闲回收从 30s 放宽到 5 分钟、连接池上限提到 32。
     * 原来 30s 就丢连接，隔一会儿再来一个请求就要重做 TCP+TLS 握手（多 1~3 个 RTT），
     * 这是「首字很慢」最直接的来源之一。并把 Dispatcher 的并发上限调高，
     * 避免多请求排队。
     */
    val http: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(okhttp3.ConnectionPool(32, 5, TimeUnit.MINUTES))
        .apply {
            if (lowLatency) {
                dispatcher(okhttp3.Dispatcher().apply { maxRequests = 128; maxRequestsPerHost = 32 })
            }
        }
        .build()

    /**
     * 聊天专用客户端：复用 [http] 的连接池 / 协议 / 头等全部配置，**仅把读超时拉长**。
     *
     * 推理型模型（reasoning / 多轮工具调用）经常在「思考」阶段长时间不吐字节；默认 120s 读超时
     * 会让这类请求中途被判超时、连接被掐断 —— 表现就是「多轮对话 / 多轮工具调用概率性中断」。
     * 流式与非流式聚合都走这个客户端（聚合同样要读完整个上游流）。
     */
    private val chatHttp: OkHttpClient = http.newBuilder()
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    /**
     * 求证 / 续期专用客户端：**必须有 callTimeout**。
     *
     * 共享的 [http] 是 `callTimeout(0)` + `readTimeout(120s)`，而 401 求证发生在 HTTP 工作
     * 线程里（BIO：core 8 / max 32，满即 503）。全池 token 同时过期时，32 个线程可能被
     * 「connect 10s + read 120s」一起挂住 → 网关整体 503。刷新请求本身很短，6s 足够。
     */
    private val verifyHttp: OkHttpClient = http.newBuilder()
        .callTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    // ---------- 请求头 ----------

    /**
     * 按端点族注入请求头（对齐 Go 的 CommonHeaders / BillingHeaders / ChatHeaders）：
     *  - 共用（REST 层）：Content-Type / Accept / X-CodeBuddy-Request:1 / Accept-Language /
     *    X-User-Id / X-Machine-ID / X-Session-ID / Origin / Referer / 设备与域头
     *  - models：同 REST 层（`/v3/config` 需要 Origin/Referer 与三段式 UA）
     *  - billing：不加 Origin/Referer，UA 为单段 `WorkBuddy/<ver>`
     *  - chat：**在共用头之上反而要删掉** Origin / Referer / Accept-Language /
     *    X-Machine-ID / X-Session-ID / X-No-* —— 官方 chat 走 openai-node SDK，
     *    这几个头客户端从不发，多发一个就是一处可判别特征（见下方 chat 分支）
     */
    private fun applyHeaders(
        b: Request.Builder,
        realm: String,
        auth: AuthEntry?,
        billing: Boolean,
        chat: Boolean = false,
        conv: ConvIds? = null,
    ) {
        b.header("Content-Type", "application/json")
        b.header("X-CodeBuddy-Request", "1")
        b.header("Accept-Language", "zh")   // 跟随系统区域，不按账号域切（新上游实测口径）
        if (billing) {
            b.header("Accept", "application/json")
            b.header("User-Agent", if (userAgentOverride.isNotBlank()) userAgentOverride else Up.billingUA(realm))
        } else {
            b.header("Accept", "application/json, */*")
            val origin = Up.origin(realm)
            b.header("X-Requested-With", "XMLHttpRequest")
            b.header("Origin", origin)
            b.header("Referer", "$origin/")
            b.header("User-Agent", if (userAgentOverride.isNotBlank()) userAgentOverride else Up.workBuddyUA(realm))
        }
        if (auth != null) {
            if (auth.access_token.isNotBlank()) b.header("Authorization", "Bearer ${auth.access_token}")
            if (auth.uid.isNotBlank()) {
                b.header("X-User-Id", auth.uid)
                b.header("X-Machine-ID", Up.stableId(auth.uid, "machine"))
                b.header("X-Session-ID", Up.stableId(auth.uid, "session"))
            }
            if (auth.enterprise_id.isNotBlank()) {
                b.header("X-Enterprise-Id", auth.enterprise_id)
                b.header("X-Tenant-Id", auth.enterprise_id)
            } else if (!billing) {
                // REST 层沿用 CodeBuddy CLI 的「无企业」哨兵头；**chat 域随后会删掉**
                // （WorkBuddy 桌面端个人号两个方向都不发 X-No-*，见 chat 分支）。
                b.header("X-No-Enterprise-Id", "1")
            }
            // X-Domain 按域分发（对齐上游 CommonHeaders + injectGlobalChatHeaders）：
            //  - global 的 chat：**固定** www.workbuddy.ai（上游硬编码，不回退登录会话原值，
            //    对齐 intl 客户端出站头；缺失会被国际版风控按「可疑域」判定）；
            //  - 其余：用账号落盘 domain，没有则声明「无部门信息」。
            if (chat && realm == "global") {
                // global 只声明 X-Domain；X-No-Enterprise-Id 在 chat 分支会被删掉
                // （真机实测个人号不发哨兵头）。
                b.header("X-No-Enterprise-Id", "1")
                b.header("X-Domain", "www.workbuddy.ai")
            } else if (auth.domain.isNotBlank()) {
                b.header("X-Domain", auth.domain)
            } else if (!billing) {
                // 同上：REST 层哨兵头，chat 域会删除。
                b.header("X-No-Department-Info", "1")
            }
            if (auth.device_token.isNotBlank()) b.header("X-Device-Token", auth.device_token)
        }
        // chat 专用的**用量归属四头**（对齐上游 injectAttribution 的官方桌面端指纹）：
        // 缺了它们，上游后台会把请求归因成「client/agentPurpose 为空」的网关特征。
        // 仅 chat 路径注入（billing / models 域不带，与上游一致）。
        if (chat) {
            // ── chat 域头族：逐头对齐新上游 headers.go（2026-10-22 issue #51 两版真机对照）──
            //
            // 与 REST 域（models / billing）的差异不是漏写，而是官方客户端形态：chat 走
            // openai-node SDK，没有浏览器语义，因此**不发**下面这几个头。旧实现把它们当
            // 「通用头」全带上，每一个都是一处可判别特征：
            //   Origin / Referer          ← CLI/SDK 没有 Origin 语义
            //   Accept-Language           ← 桌面端 chat 不发（billing 层才偶发 zh）
            //   X-Machine-ID/X-Session-ID ← 网关自造的 36 hex 派生值，客户端没有
            //   X-No-Enterprise-Id / X-No-Department-Info ← CodeBuddy CLI 的约定，
            //     WorkBuddy 桌面端个人号两个方向都不发哨兵头
            b.removeHeader("Origin")
            b.removeHeader("Referer")
            b.removeHeader("Accept-Language")
            b.removeHeader("X-Machine-ID")
            b.removeHeader("X-Session-ID")
            b.removeHeader("X-No-Enterprise-Id")
            b.removeHeader("X-No-Department-Info")
            // Accept 与官方一致：只发 application/json，照样收 SSE 流（两版实测）。
            b.header("Accept", "application/json")
            // 用量归属：X-Product 是 **SKU 段**，官方两版实测恒为 "SaaS"；产品名只在
            // X-IDE-Name/Type。旧实现让 X-Product 也取产品名 ——「同名不同值」比多一个
            // 头更容易被规则命中。
            b.header("X-Agent-Purpose", "conversation")
            b.header("X-IDE-Name", "WorkBuddy")
            b.header("X-IDE-Type", "WorkBuddy")
            b.header("X-IDE-Version", Up.clientVersion(realm))
            b.header("X-Product", "SaaS")
            // 官方 chat 出站恒带：语义是「请求含私有数据」，客户端无条件声明。
            b.header("X-Private-Data", "true")
            // SDK / agent 段：官方 openai-node 自动加，两版真机完全一致（同为 6.25.0 /
            // node v22.21.1）。网关是 Kotlin 实现没有对应运行时，按实测值固定 ——
            // 不发这几个头，上游看到的是一份「缺了 SDK 段」的残缺头集合。
            b.header("X-Agent-Type", "main")
            b.header("X-Agent-Intent", "craft")
            b.header("X-Stainless-Lang", "js")
            b.header("X-Stainless-Package-Version", "6.25.0")
            b.header("X-Stainless-OS", "Windows")
            b.header("X-Stainless-Arch", "x64")
            b.header("X-Stainless-Runtime", "node")
            b.header("X-Stainless-Runtime-Version", "v22.21.1")
            b.header("X-Stainless-Retry-Count", "0")

            // ── 会话头族（渠道校验三件套 + 链路族）──
            // 缺任一头，上游 400 code=11-128 "conversation id missing"；一个都不发时
            // 实测得到 11128 "request illegal" + displayTips「没通过渠道校验」。
            val msgId = Up.messageId()
            val turnId = conv?.turnId?.takeIf { it.isNotBlank() } ?: Up.messageId()
            val convId = conv?.clientConversationId?.takeIf { it.isNotBlank() }
                ?: Up.conversationId(auth?.uid.orEmpty(), conv?.sessionKey)
            // X-Trace-ID **不**回落 request id：真机实测两族恒不相等（一条独立 32 hex），
            // 旧实现空则回落，反而让 trace 与 request 恒等 —— 是「网关自造」的可见痕迹。
            val trId = conv?.traceId?.takeIf { it.isNotBlank() } ?: Up.traceId()
            // SpanId / ParentSpanId 各自独立随机：官方 span 与 trace、request 无关；
            // 曾用 messageID[:16]，会把消息 ID 的 12 hex 时间前缀一起带出来。
            val spanId = Up.spanId()
            val parentId = Up.spanId()
            b.header("X-Conversation-ID", convId)
            b.header("X-Conversation-Request-ID", turnId)
            b.header("X-Conversation-Message-ID", msgId)
            b.header("X-Request-ID", msgId)
            b.header("X-Root-Request-ID", turnId)
            b.header("X-Trace-ID", trId)
            // B3 TraceId 跟随 trace（官方 X-B3-TraceId == X-Trace-ID）；入站透传值非法
            // （含横线/超长/非 hex）时回落消息级 ID（恒 32 hex，必为合法 B3 TraceId）。
            val b3Trace = if (Up.validTraceId(trId)) trId else msgId
            b.header("X-B3-TraceId", b3Trace)
            b.header("X-B3-SpanId", spanId)
            b.header("X-B3-ParentSpanId", parentId)
            b.header("X-B3-Sampled", "1")
            // 单头 b3 与 W3C traceparent：官方两版都双发，缺它们只剩三件套，
            // 而客户端从不只发三件套。
            b.header("b3", b3Trace + "-" + spanId + "-1-" + parentId)
            b.header("traceparent", "00-" + b3Trace + "-" + spanId + "-01")
            // acp-connection-id：官方把它列进标准出站头族（与 X-Conversation-ID 同批设置），
            // 值是会话注册表的 acpConnectionId —— 同会话稳定、与 X-Conversation-ID 不同值。
            // 网关没有 ACP 连接层，按「同 (账号, 会话) 稳定 + 不同域」派生，语义一致。
            val sessSrc = conv?.sessionKey?.takeIf { it.isNotBlank() }
                ?: conv?.clientConversationId?.takeIf { it.isNotBlank() }
            b.header(
                "acp-connection-id",
                if (sessSrc != null) Up.uuidFor(auth?.uid.orEmpty(), sessSrc, "acp")
                else Up.uuidFor("", Up.traceId(), "acp"),
            )
        }
    }

    private fun commonHeaders(b: Request.Builder, realm: String, auth: AuthEntry?) = applyHeaders(b, realm, auth, billing = false)
    private fun billingHeaders(b: Request.Builder, realm: String, auth: AuthEntry?) = applyHeaders(b, realm, auth, billing = true)

    private fun parseEnvelope(text: String): JsonObject {
        // 安全取对象：异常时上游会返回 HTML 错误页（APISIX / WAF）或空体，裸 `.jsonObject`
        // 会抛 IllegalArgumentException —— 而 release 包被 R8 剥离了 kotlin-reflect，异常消息
        // 会退化成不可读形式，且绕过了 UpstreamException 的统一分类。这里收敛为可读异常
        // （与 core/Json.kt 里 primText/objOrNull 的安全扩展同一套口径）。
        val root = AppJson.parseToJsonElement(text).objOrNull()
            ?: throw UpstreamException(ErrKind.CLIENT, 200, "上游返回非 JSON 对象响应：${text.take(120)}")
        val code = (root["code"]?.primTextOrNull() ?: "0").toDoubleOrNull() ?: 0.0
        if (code != 0.0) {
            val msg = root["msg"]?.primTextOrNull() ?: ""
            throw UpstreamException(ErrKind.CLIENT, 200, "code=${code.toInt()} msg=$msg")
        }
        return (root["data"]?.objOrNull()) ?: JsonObject(emptyMap())
    }

    /** 逐 URL 记录上游结果（便于在 App 日志中定位 host/路径问题）。 */
    private fun logCall(method: String, r: HttpResult) {
        LogBus.log("[up] $method ${r.url} -> http ${r.code}")
    }

    /** 单次 billing 请求（不做回退）。 */
    private fun billingOnce(url: String, realm: String, auth: AuthEntry, method: String, body: String?): HttpResult {
        val b = Request.Builder().url(url)
        if (method == "POST") b.post((body ?: "{}").toRequestBody(mediaJson)) else b.get()
        billingHeaders(b, realm, auth)
        val r = http.newCall(b.build()).execute()
        return r.use { HttpResult(it.code, it.body?.string().orEmpty(), url) }
    }

    /**
     * billing 域请求：按 base 候选 × 路径候选顺序尝试；**仅在 404（路由不存在）时**继续换下一个，
     * 其他状态码（200/401/403/400…）立即返回——换 base 只解决"路由不可达"，不掩盖鉴权/业务错误。
     */
    private fun billingCall(realm: String, auth: AuthEntry, method: String, paths: List<String>, body: String? = null): HttpResult {
        var last = HttpResult(0, "", "")
        for (base in Up.billingBases(realm)) {
            for (path in paths) {
                val r = runCatching { billingOnce(base + path, realm, auth, method, body) }
                    .getOrElse { HttpResult(0, it.message ?: "network error", base + path) }
                logCall(method, r)
                if (r.ok) return r
                last = r
                if (r.code != 404) return r
            }
        }
        return last
    }

    /** 对话/模型域请求：按 base 候选尝试，仅 404 时换 base。 */
    private fun chatCall(realm: String, auth: AuthEntry?, method: String, path: String, body: String? = null, accept: String? = null, raw: Boolean = false): HttpResult {
        var last = HttpResult(0, "", "")
        for (base in Up.chatBases(realm)) {
            val url = base + path
            val b = Request.Builder().url(url)
            if (method == "POST") b.post((body ?: "{}").toRequestBody(mediaJson)) else b.get()
            commonHeaders(b, realm, auth)
            if (accept != null) b.header("Accept", accept)
            val r = runCatching {
                http.newCall(b.build()).execute().use { resp ->
                    if (raw) HttpResult(resp.code, "", url) else HttpResult(resp.code, resp.body?.string().orEmpty(), url)
                }
            }.getOrElse { HttpResult(0, it.message ?: "network error", url) }
            logCall(method, r)
            if (r.code in 200..499 && r.code != 404) return r
            last = r
            if (r.code == 0) return r
        }
        return last
    }

    // ---------- OAuth 设备授权 ----------

    suspend fun deviceAuthStart(realm: String): DeviceAuth = withContext(Dispatchers.IO) {
        val url = Up.base(realm) + Up.P_AUTH_STATE + "?platform=CLI"
        val req = Request.Builder().url(url).post("{}".toRequestBody(mediaJson)).apply {
            val origin = Up.origin(realm)
            header("Content-Type", "application/json")
            header("Accept", "application/json")
            header("X-Requested-With", "XMLHttpRequest")
            header("Origin", origin)
            header("Referer", "$origin/")
            header("User-Agent", Up.UA_CLI)
        }.build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw UpstreamException(Classify.of(resp.code, text), resp.code, text)
            val data = parseEnvelope(text)
            val state = data["state"]?.primTextOrNull()
                ?: data["authState"]?.primTextOrNull() ?: ""
            val authUrl = data["authUrl"]?.primTextOrNull()
                ?: data["url"]?.primTextOrNull()
                ?: data["loginUrl"]?.primTextOrNull() ?: ""
            DeviceAuth(state, authUrl, realm)
        }
    }

    /**
     * 轮询一次授权状态：
     *  - 未授权 / 待完成 → 返回 null（调用方继续轮询）；
     *  - 已授权 → 用 access token 请求 /v2/plugin/login/account（**必须带 Authorization: Bearer**）
     *    取 uid/nickname，返回可落盘的凭证；
     *  - 已发 token 但账号信息异常（无 uid）→ 抛 UpstreamException，让 UI 明确报错。
     */
    suspend fun deviceAuthPoll(realm: String, state: String): AuthEntry? = withContext(Dispatchers.IO) {
        val origin = Up.origin(realm)
        val enc = java.net.URLEncoder.encode(state, "UTF-8")
        val url = Up.base(realm) + Up.P_AUTH_TOKEN + "?state=" + enc
        val req = Request.Builder().url(url).get().apply {
            header("Accept", "application/json")
            header("X-Requested-With", "XMLHttpRequest")
            header("Origin", origin)
            header("Referer", "$origin/")
            header("User-Agent", Up.UA_CLI)
        }.build()
        val text = http.newCall(req).execute().use { it.body?.string().orEmpty() }
        val root = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@withContext null
        val code = (root["code"]?.primTextOrNull() ?: "0").toDoubleOrNull() ?: 0.0
        if (code != 0.0) return@withContext null  // 未授权 / 等待用户完成
        val data = root["data"]?.objOrNull() ?: return@withContext null
        val access = data.str("access_token", "accessToken", "token")
        if (access.isBlank()) return@withContext null
        val expiresIn = data.num("expires_in", "expiresIn")?.toLong() ?: 3600L

        // 账号信息：带 Bearer 的权威来源。
        var uid = ""
        var nickname = ""
        var enterpriseId = ""
        runCatching {
            val u = Up.base(realm) + Up.P_LOGIN_ACCOUNT + "?state=" + enc
            val r2 = Request.Builder().url(u).get().apply {
                header("Accept", "application/json")
                header("X-Requested-With", "XMLHttpRequest")
                header("Origin", origin)
                header("Referer", "$origin/")
                header("User-Agent", Up.UA_CLI)
                header("Authorization", "Bearer $access")
            }.build()
            val t2 = http.newCall(r2).execute().use { it.body?.string().orEmpty() }
            val d2 = AppJson.parseToJsonElement(t2).jsonObject["data"]?.objOrNull()
            uid = d2?.str("uid", "userId", "user_id", "uin") ?: ""
            nickname = d2?.str("nickname", "name", "userName", "displayName") ?: ""
            enterpriseId = d2?.str("enterpriseId", "enterprise_id", "orgId") ?: ""
        }.onFailure { LogBus.log("[sys] 拉取账号信息失败：${it.message}") }
        if (uid.isBlank()) uid = data.str("uid", "userId", "user_id")
        if (nickname.isBlank()) nickname = data.str("nickname", "name", "userName")
        if (uid.isBlank()) {
            throw UpstreamException(
                ErrKind.CLIENT, 200,
                "已获取令牌但未取到账号 uid（account 接口鉴权失败），请重新登录",
            )
        }
        val nowSec = System.currentTimeMillis() / 1000
        AuthEntry(
            uid = uid, nickname = nickname, realm = realm,
            access_token = access,
            refresh_token = data.str("refresh_token", "refreshToken"),
            access_expires_at = nowSec + expiresIn,
            refresh_expires_at = nowSec + 30L * 86400,
            device_token = data.str("device_token", "deviceToken"),
            domain = data.str("domain"),
            enterprise_id = enterpriseId,
            created_at = nowSec,
            updated_at = nowSec,
        )
    }

    /**
     * 用 Bearer 取账号信息（uid / nickname）——**仅用于导入凭证时的补全/校验**。
     *
     * 逐字对齐 [deviceAuthPoll] 里「用 access token 请求 /v2/plugin/login/account
     * （**必须带 Authorization: Bearer**）取 uid/nickname」的那一段。
     *
     * 与 deviceAuthPoll 的区别（有意为之）：这里**不抛异常**，失败一律返回 null ——
     * 对齐 import.go:133「顺带校验/激活（幂等，失败仅记日志，不阻断导入）」的口径，
     * 「校验失败不得阻断导入」是本功能的硬约束。
     */
    suspend fun fetchLoginAccount(realm: String, accessToken: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            if (accessToken.isBlank()) return@withContext null
            val origin = Up.origin(realm)
            runCatching {
                val url = Up.base(realm) + Up.P_LOGIN_ACCOUNT
                val req = Request.Builder().url(url).get().apply {
                    header("Accept", "application/json")
                    header("X-Requested-With", "XMLHttpRequest")
                    header("Origin", origin)
                    header("Referer", "$origin/")
                    header("User-Agent", Up.UA_CLI)
                    header("Authorization", "Bearer $accessToken")
                }.build()
                val text = http.newCall(req).execute().use { it.body?.string().orEmpty() }
                val data = AppJson.parseToJsonElement(text).jsonObject["data"]?.objOrNull()
                    ?: return@runCatching null
                val uid = data.str("uid", "userId", "user_id", "uin")
                val nickname = data.str("nickname", "name", "userName", "displayName")
                if (uid.isBlank() && nickname.isBlank()) null else (uid to nickname)
            }.getOrNull()
        }

    suspend fun refresh(auth: AuthEntry): AuthEntry? = withContext(Dispatchers.IO) {
        val realm = auth.realm
        val url = Up.base(realm) + Up.P_AUTH_REFRESH
        val body = buildJsonObject {
            put("refresh_token", JsonPrimitive(auth.refresh_token))
            put("refreshToken", JsonPrimitive(auth.refresh_token))
        }
        val req = Request.Builder().url(url).post(AppJson.encodeToString(JsonObject.serializer(), body).toRequestBody(mediaJson)).apply {
            commonHeaders(this, realm, null)
        }.build()
        runCatching {
            // 短超时：该方法现在也在请求路径上被主动续期调用（见 ChatHandler.ensureFreshToken），
            // 用 120s 的共享客户端会把一次普通请求拖成两分钟。
            verifyHttp.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext null
                val data = parseEnvelope(text)
                val access = data.str("access_token", "accessToken", "token")
                if (access.isBlank()) return@withContext null
                auth.copy(
                    access_token = access,
                    refresh_token = data.str("refresh_token", "refreshToken").ifBlank { auth.refresh_token },
                    access_expires_at = System.currentTimeMillis() / 1000 + 3600,
                    updated_at = System.currentTimeMillis() / 1000,
                )
            }
        }.getOrNull()
    }

    /**
     * 用 refresh 端点**求证**一次 401（上游 issue #66）。
     *
     * 为什么必须求证：网关边缘拦截（APISIX 的 HTML/JSON 401、无业务信封）与 token 真失效
     * 的响应体可能完全一样，只有 refresh 的结果能区分。旧实现「一次 401 就禁用」的实案是：
     * 两个号各累计 3 次 401 被判死，人工 revive 后立刻连续 200 —— 好号被永久踢出池子。
     *
     * 判词规则（对齐上游 handler 的 gateway 401 分支）：
     *  - refresh 成功 → ALIVE（并把新凭证带回，调用方**必须落盘**：只改内存的话重启会用
     *    旧 RT 去刷新，反而变成真死）；
     *  - refresh 失败且是**应用层明确判词**（4xx、非 429、非 HTML 拦截页、非空体）→ DEAD；
     *  - 其余（传输错误 / 5xx / 429 / HTML 页 / 空体 / 无 RT）→ UNKNOWN，绝不据此禁用。
     */
    suspend fun verifySession(auth: AuthEntry): SessionProof = withContext(Dispatchers.IO) {
        if (auth.refresh_token.isBlank()) {
            return@withContext SessionProof(SessionVerdict.UNKNOWN)
        }
        val deadHints = listOf("invalid", "expired", "revoked", "失效", "已过期", "登录过期")
        val deadSubjects = listOf("refresh", "token", "session", "grant", "登录")
        fun looksDead(t: String): Boolean =
            deadHints.any { t.contains(it, ignoreCase = true) } &&
                deadSubjects.any { t.contains(it, ignoreCase = true) }
        val realm = auth.realm
        val url = Up.base(realm) + Up.P_AUTH_REFRESH
        val body = buildJsonObject {
            put("refresh_token", JsonPrimitive(auth.refresh_token))
            put("refreshToken", JsonPrimitive(auth.refresh_token))
        }
        val req = Request.Builder().url(url)
            .post(AppJson.encodeToString(JsonObject.serializer(), body).toRequestBody(mediaJson))
            .apply { commonHeaders(this, realm, null) }
            .build()
        try {
            verifyHttp.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val data = runCatching { parseEnvelope(text) }.getOrNull()
                    val access = data?.str("access_token", "accessToken", "token").orEmpty()
                    if (access.isBlank()) {
                        // HTTP 200 + 业务错误信封（`{"code":xxx,"msg":"invalid refresh token"}`）
                        // 是「RT 真失效」最常见的形态之一：只有命中失效文案才判死。
                        return@use SessionProof(
                            if (looksDead(text)) SessionVerdict.DEAD else SessionVerdict.UNKNOWN,
                        )
                    }
                    val renewed = auth.copy(
                        access_token = access,
                        refresh_token = data?.str("refresh_token", "refreshToken")
                            ?.ifBlank { auth.refresh_token } ?: auth.refresh_token,
                        access_expires_at = System.currentTimeMillis() / 1000 + 3600,
                        updated_at = System.currentTimeMillis() / 1000,
                    )
                    return@use SessionProof(SessionVerdict.ALIVE, renewed)
                }
                // 限流 / 服务端故障 / 空体 / 边缘 HTML 页：都不构成「token 判死」的证据。
                if (resp.code == 429 || resp.code >= 500 || text.isBlank() ||
                    text.contains("<html", ignoreCase = true)
                ) {
                    return@use SessionProof(SessionVerdict.UNKNOWN)
                }
                // 只有「明确的失效文案」才算死。404（路径变更）/ 400（请求体字段变了）/
                // 403（WAF 的 JSON 页）都不是 token 失效的证据 —— 判死会一夜间清空整池。
                SessionProof(if (looksDead(text)) SessionVerdict.DEAD else SessionVerdict.UNKNOWN)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消必须原样抛出：吞掉会让停机/超时后仍继续改池状态、写盘。
            throw e
        } catch (e: Throwable) {
            // 传输层失败（连不上 / TLS / 超时）当然不能当成「token 死了」。
            SessionProof(SessionVerdict.UNKNOWN)
        }
    }

    // ---------- 模型 ----------

    /**
     * 拉取模型目录 —— **多路并发探测 + 按权威序并集合并**（对齐上游 `FetchModels`
     * （CN 两路）与 `probeGlobalModels`（global 四路））。
     *
     * 上游有两套口径：
     *  - **CN**：`/v3/config`（IDE UA）+ 企业端点 `/console/enterprises/personal/models`，两路并发；
     *  - **global**：`/v3/config` **三路 UA**（桌面端 `WorkBuddy/<v> WorkBuddy AI/<v> CLI/<v>`
     *    为主路，另有 IDE / CLI 两路，各自下发**不同**的模型集合）+ 企业端点家族
     *    （`/v2/enterprises/personal/models` 优先、`/console` 兜底），四路并发。
     *
     * 为什么必须多路（2026-10-11 对 global 账号实测）：同一个账号、同一个端点，
     *  - 桌面端 UA → 28 条（含 gpt-6-sol/luna、grok-4.7、gemini-3.5-flash、gpt-5.6-* 等）
     *  - CLI UA   → 22 条（含 deepseek 系列、kimi-k2.8-preview）
     *  - IDE UA   → **仅 12 条**，且过滤掉补全/图像类后只剩 default/fast/balanced/primary
     *    这几个别名 —— 这就是「global 拉不到（真）模型」的根因。
     *  - 企业端点 `/v2/...` → 18 条**第三方主力模型**（gpt-5.6-*、gpt-5.5/5.4/5.3-codex、
     *    gemini-3.5-flash、glm-5.x、kimi-k3/k2.6…），且 `/console/...` 在 global 实测 500。
     *
     * 合并口径与上游一致：**按权威序**（各 /v3 路在前、企业端点在最后）去重，id 首次出现的
     * 条目为准（字段权威）。逐路容错：某一路失败只丢该路结果并记日志；全部为空才抛错
     * （不再回落任何静态名单 —— 与上游「纯动态、无兜底」的产品决策一致）。
     */
    suspend fun fetchModels(realm: String, auth: AuthEntry): List<JsonObject> = withContext(Dispatchers.IO) {
        // 追加官方现有两种 UA 形态（三段式 + 两段式 Lite）作为**纯增量**探测路：
        // 合并按 id 去重、只在多不在少，可兜住「旧 UA 被上游收紧后目录变空」。
        val lite = Up.workBuddyUaLite(realm)
        val v3UAs = if (realm == "global") {
            listOf(Up.workBuddyUA(realm), lite, Up.UA_IDE, Up.UA_CLI)
        } else {
            listOf(Up.workBuddyUA(realm), lite, Up.UA_IDE)
        }
        val enterprisePaths = if (realm == "global") {
            listOf(Up.P_MODELS_BIZ2, Up.P_MODELS_BIZ)
        } else {
            listOf(Up.P_MODELS_BIZ)
        }

        val routes: List<Pair<String, List<JsonObject>>> = coroutineScope {
            val v3Jobs = v3UAs.map { ua ->
                async {
                    val label = "v3:${ua.substringBefore('/')}"
                    label to runCatching { fetchV3Config(realm, auth, ua) }.getOrElse { e ->
                        LogBus.log("[up] 模型路 $label 失败：${e.message}")
                        emptyList()
                    }
                }
            }
            val bizJob = async {
                "enterprise" to runCatching { fetchEnterpriseModels(realm, auth, enterprisePaths) }.getOrElse { e ->
                    LogBus.log("[up] 模型路 enterprise 失败：${e.message}")
                    emptyList()
                }
            }
            (v3Jobs + bizJob).map { it.await() }
        }

        val seen = HashSet<String>()
        val merged = ArrayList<JsonObject>()
        for ((label, list) in routes) {
            if (list.isEmpty()) continue
            LogBus.log("[up] 模型路 $label -> ${list.size} 条")
            for (o in list) {
                val id = o["id"]?.primTextOrNull()
                    ?.removePrefix("cn:")?.removePrefix("global:").orEmpty().trim()
                if (id.isEmpty() || !seen.add(id)) continue
                merged.add(o)
            }
        }
        if (merged.isEmpty()) {
            throw UpstreamException(ErrKind.SERVER, 502, "模型目录为空（${routes.size} 路探测全部失败）")
        }
        LogBus.log("[up] 模型目录合并后 ${merged.size} 条（$realm，${routes.size} 路）")
        merged
    }

    /**
     * 单路 `/v3/config` 探测（UA 参数化）。
     *
     * 该端点对 UA 极敏感：不带可解析的 CodeBuddy 版本号会 400 code=12403
     * （"check ua, get coding copilot version error"）；同时必须带
     * `X-Domain`（账号落盘 domain，缺省回落 chatBase host）与 `X-Product: SaaS`。
     * 不同 UA 下发的模型集合不同，故 global 侧要跑三路（见 [fetchModels]）。
     */
    private fun fetchV3Config(realm: String, auth: AuthEntry, ua: String): List<JsonObject> {
        var last = HttpResult(0, "", "")
        for (base in Up.chatBases(realm)) {
            val url = base + Up.P_MODELS_V3
            val b = Request.Builder().url(url).get()
            b.header("Accept", "application/json, text/plain, */*")
            b.header("X-Requested-With", "XMLHttpRequest")
            b.header("Content-Type", "application/json")
            b.header("X-CodeBuddy-Request", "1")
            b.header("Accept-Language", "zh")   // 跟随系统区域，不按账号域切（新上游实测口径）
            if (auth.access_token.isNotBlank()) b.header("Authorization", "Bearer ${auth.access_token}")
            if (auth.uid.isNotBlank()) b.header("X-User-Id", auth.uid)
            b.header("X-Domain", v3Domain(auth))
            b.header("X-Product", "SaaS")
            b.header("User-Agent", ua)
            val r = runCatching {
                http.newCall(b.build()).execute().use { HttpResult(it.code, it.body?.string().orEmpty(), url) }
            }.getOrElse { HttpResult(0, it.message ?: "network error", url) }
            logCall("GET", r)
            if (r.ok) {
                val data = parseEnvelope(r.text)
                val arr = (data["models"] ?: data["data"]) as? kotlinx.serialization.json.JsonArray
                return arr?.mapNotNull { it as? JsonObject } ?: emptyList()
            }
            last = r
            if (r.code != 404) break
        }
        throw UpstreamException(Classify.of(last.code, last.text), last.code, "HTTP ${last.code} @ ${last.url} :: ${last.text.take(160)}")
    }

    /**
     * 企业模型端点（家族：任一路 2xx 即用）—— 只取 `data.agents[name=cli].models` 里出现的
     * id，再按 `data.models` 查表取**全字段条目**；`disabled=true` 剔除（对齐上游
     * `fetchEnterpriseModels` 的 cli 面过滤）。
     *
     * 这是 /v3/config 之外的必要补充：global 实测该端点给出的正是**第三方主力模型**
     * （gpt-5.6-sol/terra/luna、gpt-5.5/5.4/5.3-codex、gemini-3.5-flash、glm-5.x、kimi-k3/k2.6…）；
     * CN 侧该端点由上游 `FetchModels` 第二路使用（路径 `/console/...`）。
     */
    private fun fetchEnterpriseModels(realm: String, auth: AuthEntry, paths: List<String>): List<JsonObject> {
        var last = HttpResult(0, "", "")
        for (base in Up.chatBases(realm)) {
            for (path in paths) {
                val url = base + path
                val b = Request.Builder().url(url).get()
                b.header("Accept", "application/json, text/plain, */*")
                b.header("X-Requested-With", "XMLHttpRequest")
                b.header("X-CodeBuddy-Request", "1")
                b.header("Accept-Language", "zh")   // 跟随系统区域，不按账号域切（新上游实测口径）
                if (auth.access_token.isNotBlank()) b.header("Authorization", "Bearer ${auth.access_token}")
                if (auth.uid.isNotBlank()) b.header("X-User-Id", auth.uid)
                b.header("X-Domain", v3Domain(auth))
                b.header("X-Product", "SaaS")
                b.header("User-Agent", Up.workBuddyUA(realm))
                val r = runCatching {
                    http.newCall(b.build()).execute().use { HttpResult(it.code, it.body?.string().orEmpty(), url) }
                }.getOrElse { HttpResult(0, it.message ?: "network error", url) }
                logCall("GET", r)
                if (!r.ok) {
                    last = r
                    // 404/405 = 该路径不存在 → 换下一个候选；其余（含 500）也继续试同族下一路。
                    continue
                }
                val data = parseEnvelope(r.text)
                val table = (data["models"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.associateBy { it["id"]?.primTextOrNull().orEmpty() }
                    .orEmpty()
                val cliIds = ((data["agents"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.firstOrNull { it["name"]?.primTextOrNull() == "cli" }
                    ?.get("models") as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { it.primTextOrNull() }
                    .orEmpty()
                val out = ArrayList<JsonObject>(cliIds.size)
                for (id in cliIds) {
                    val o = table[id] ?: continue
                    if (o["disabled"]?.primTextOrNull()?.toBoolean() == true) continue
                    out.add(o)
                }
                if (out.isNotEmpty()) return out
                last = r
            }
        }
        throw UpstreamException(
            Classify.of(last.code, last.text), last.code,
            "HTTP ${last.code} @ ${last.url} :: ${last.text.take(160)}",
        )
    }

    /**
     * `/v2/billing/meter/get-user-resource` 的固定请求体（与 Go `UserResourceDetailedWithExpiry`
     * 逐字对齐）。**余额查询与网络自检必须共用同一份**：该端点是 POST-only，用 GET 会被上游
     * 当作未知路由返回 Go 默认的 `404 page not found`——诊断里出现这种 404 会误导排查方向。
     */
    private fun resourceBody(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val now = System.currentTimeMillis()
        val body = buildJsonObject {
            put("PageNumber", JsonPrimitive(1))
            put("PageSize", JsonPrimitive(100))
            put("ProductCode", JsonPrimitive("p_tcaca"))
            put("Status", kotlinx.serialization.json.buildJsonArray {
                add(JsonPrimitive(0)); add(JsonPrimitive(3))
            })
            put("PackageEndTimeRangeBegin", JsonPrimitive(fmt.format(java.util.Date(now))))
            put("PackageEndTimeRangeEnd", JsonPrimitive(fmt.format(java.util.Date(now + 365L * 101 * 24 * 3600 * 1000L))))
        }
        return AppJson.encodeToString(JsonObject.serializer(), body)
    }

    private fun v3Domain(auth: AuthEntry): String {
        val d = auth.domain.trim()
        if (d.isNotEmpty()) return d.removePrefix("https://").removePrefix("http://").trimEnd('/')
        return runCatching { java.net.URI(Up.base(auth.realm)).host }.getOrNull() ?: "copilot.tencent.com"
    }

    // ---------- 聊天 ----------

    /** 发起聊天，返回原始 OkHttp Response（调用方负责读流与关闭）。 */
    suspend fun chat(
        auth: AuthEntry,
        bodyText: String,
        clientIp: String?,
        conv: ConvIds = ConvIds(),
    ): Response = withContext(Dispatchers.IO) {
        val realm = auth.realm
        var last: Response? = null
        for (base in Up.chatBases(realm)) {
            val url = base + Up.P_CHAT
            val req = Request.Builder().url(url)
                .post(bodyText.toRequestBody(mediaJson))
                .apply {
                    // chat 域：在通用头之上叠加 global 专属域声明与用量归属头（见 applyHeaders）。
                    applyHeaders(this, realm, auth, billing = false, chat = true, conv = conv)
                    // 官方 chat 只发 application/json（照样收 SSE）；多带
                    // ", text/event-stream" 是自造形态（issue #51 两版实测）。
                    header("Accept", "application/json")
                    // SSE 首字延迟：**不要**让中间层对 event-stream 做 gzip 缓冲——
                    // 压缩器要攒够一个块才吐字节，会把 TTFT 从几十毫秒推到几百毫秒。
                    // 显式声明 identity 即关闭压缩（上游是 Go net/http，正常遵守该声明）；
                    // 关掉「低延迟模式」即恢复默认（OkHttp 自动 gzip + 透明解压）。
                    if (lowLatency) header("Accept-Encoding", "identity")
                }.build()
            val resp = try {
                chatHttp.newCall(req).execute()
            } catch (e: Throwable) {
                // 单 base 传输层异常（连接被拒 / TLS / 首字超时）→ 换下一个 base 再试，
                // 而不是直接把失败当作「账号问题」去换号（换号解决不了 base 不可达）。
                LogBus.log("[up] POST $url transport error: ${e.message} -> 尝试下一个 base")
                continue
            }
            LogBus.log("[up] POST $url -> http ${resp.code}")
            if (resp.isSuccessful) return@withContext resp
            last?.close()
            last = resp
            if (resp.code != 404) return@withContext resp
        }
        last ?: throw UpstreamException(ErrKind.SERVER, 502, "no chat base available")
    }

    // ---------- 签到 / 余额 ----------

    /** 每日签到（**POST**）。上游对「今天已签到」返回业务错误，按幂等成功处理。 */
    suspend fun checkin(auth: AuthEntry): String = withContext(Dispatchers.IO) {
        val paths = Up.meterPaths(auth.realm, Up.P_CHECKIN, Up.P_CHECKIN_ALT)
        val r = billingCall(auth.realm, auth, "POST", paths, "{}")
        if (r.ok) return@withContext "ok"
        if (r.text.contains("已签到") || r.text.lowercase().contains("already")) return@withContext "已签到"
        throw UpstreamException(Classify.of(r.code, r.text), r.code, "HTTP ${r.code} @ ${r.url} :: ${r.text.take(160)}")
    }

    /**
     * 余额查询结果（对齐上游 `UserResourceDetailedWithExpiry` 的元组，只取我方实际消费的项）。
     *
     * - [remain]：剩余额度。**-1 = 上游未返回（未知）**；0 表示「真的为 0」——两者不可混同
     *   （把未知当 0 会把池内积分静默清零）。
     * - [earliestAt] / [earliestRemaining]：**最早未来到期批次**的时刻与该批次剩余量之和
     *   （无有效批次时为 0），供账号池 `prefer_expiring` 加权。
     * - [expiring]：其中在「快过期窗口」内到期作废的那部分剩余量。
     */
    data class Balance(
        val remain: Double,
        val earliestAt: Long = 0,
        val earliestRemaining: Double = 0.0,
        val expiring: Double = 0.0,
    ) {
        /** 上游是否给出了明确余额（-1 表示未知）。 */
        val known: Boolean get() = remain >= 0
    }

    /** 只要剩余量的旧调用面（-1 = 未知）。 */
    suspend fun balance(auth: AuthEntry): Double = balanceDetailed(auth).remain

    /**
     * 查询账号余额 + 到期批次；**企业版与个人版走不同端点、且字段口径相反**。
     *
     * 个人口径：`/v2/billing/meter/get-user-resource` 是 **POST-only** 且需要固定 JSON body
     * （与 Go 的 UserResourceDetailedWithExpiry 逐字对齐：PageNumber/PageSize/
     * ProductCode=p_tcaca/Status=[0,3]/有效期时间窗）。用 GET 会被上游当作未知路由，
     * 返回 Go 默认的 `404 page not found`；响应形如 `data.Response.Data.Accounts[]`，
     * 按 Go 的 packageRemainUsed 规则汇总剩余量，并按 `CycleEndTime`（回落 `PackageEndTime`）
     * 归组出**最早未来到期批次**。
     *
     * @param soonSec 「快过期」窗口（秒）；≤0 表示不分桶（[Balance.expiring] 恒 0）。
     */
    suspend fun balanceDetailed(auth: AuthEntry, soonSec: Long = Up.EXPIRING_SOON_DEFAULT_SEC): Balance =
        withContext(Dispatchers.IO) {
            // 企业版额度不在个人「资源包」体系内：必须分流，否则余额恒为 0（详见 enterpriseUsage）。
            if (auth.isEnterprise) return@withContext enterpriseUsage(auth, soonSec)
            val realm = auth.realm
            val paths = Up.meterPaths(realm, Up.P_RESOURCE, Up.P_RESOURCE_ALT)
            val r = billingCall(realm, auth, "POST", paths, resourceBody())
            if (!r.ok) throw UpstreamException(Classify.of(r.code, r.text), r.code, "HTTP ${r.code} @ ${r.url} :: ${r.text.take(160)}")
            val data = parseEnvelope(r.text)

            fun longOf(o: JsonObject, k: String): Long =
                (o[k]?.primTextOrNull())?.toDoubleOrNull()?.toLong() ?: 0L

            val accounts = ((data["Response"] as? JsonObject)?.get("Data") as? JsonObject)
                ?.get("Accounts") as? kotlinx.serialization.json.JsonArray
                // 上游没返回 Accounts 段时返回 -1（未知），而不是 0：0 会被上层当作
                // 「余额真的是 0」并写进池状态，导致积分被静默清零。
                ?: return@withContext Balance(-1.0)
            var remain = 0L
            var expiring = 0L
            // 到期时刻 → 该时刻到期的剩余量之和（同一时刻可能有多个包，Go 亦按时刻归并）。
            val byExpiry = HashMap<Long, Long>()
            val nowSec = System.currentTimeMillis() / 1000
            for (el in accounts) {
                val o = el as? JsonObject ?: continue
                val size = longOf(o, "CycleCapacitySize")
                val left = if (size > 0) {
                    var rr = longOf(o, "CycleCapacityRemain")
                    if (rr < 0) rr = 0
                    if (rr > size) rr = size
                    var used = size - rr
                    val cycUsed = longOf(o, "CycleCapacityUsed")
                    if (cycUsed > used) {
                        used = cycUsed
                        if (size >= used) rr = size - used
                    }
                    rr
                } else {
                    longOf(o, "CapacityRemain").coerceAtLeast(0)
                }
                remain += left
                if (left <= 0) continue
                // 已过期的包不构成「未来到期批次」（与 Go 一致）。
                val endSec = parseEndSec(o, "CycleEndTime") ?: parseEndSec(o, "PackageEndTime") ?: continue
                if (endSec <= nowSec) continue
                byExpiry[endSec] = (byExpiry[endSec] ?: 0L) + left
                if (soonSec > 0 && endSec - nowSec <= soonSec) expiring += left
            }
            val earliest = byExpiry.keys.minOrNull()
            Balance(
                remain = remain.toDouble(),
                earliestAt = earliest ?: 0L,
                earliestRemaining = (earliest?.let { byExpiry[it] } ?: 0L).toDouble(),
                expiring = expiring.toDouble(),
            )
        }

    /**
     * 企业版账号的**分配额度**查询（对齐上游 `internal/upstream/client.go enterpriseResource`）。
     *
     * 字段语义与个人**相反**：`credit` = 本周期**已用**（个人 `CapacityRemain` 是剩余）、
     * `limitNum` = 分配给本账号的额度 → `remain = limitNum − credit`；`credit` 是浮点，
     * 四舍五入到整数（向下取整会低报剩余，与「还剩多少额度」的展示意图相悖）。
     * 兼容 camelCase / snake_case 两套字段（上游桌面端有两条解析路径）。
     * `limitNum < 0` 视为**不限量**（见 [Up.ENTERPRISE_UNLIMITED_REMAIN]）。
     *
     * 注：企业**池**总额度是另一回事、成员账号无权查询（上游所有池端点 403），
     * 这里只反映该账号被分配的额度。
     */
    private fun enterpriseUsage(auth: AuthEntry, soonSec: Long): Balance {
        val paths = Up.meterPaths(auth.realm, Up.P_ENTERPRISE_USAGE, Up.P_ENTERPRISE_USAGE_ALT)
        val r = billingCall(auth.realm, auth, "POST", paths, "{}")
        if (!r.ok) throw UpstreamException(Classify.of(r.code, r.text), r.code, "HTTP ${r.code} @ ${r.url} :: ${r.text.take(160)}")
        val data = parseEnvelope(r.text)
        val limit = data.num("limitNum", "limit_num")?.toLong() ?: 0L
        val used = data.num("credit", "used_num") ?: 0.0
        if (limit < 0) return Balance(Up.ENTERPRISE_UNLIMITED_REMAIN)
        val usedInt = used.toLong() + if (used - used.toLong() >= 0.5) 1L else 0L
        val remain = (limit - usedInt).coerceAtLeast(0L).toDouble()
        // 企业配额按周期重置、未用完即作废，与个人「奖励积分到期作废」同性质：
        // 把周期末作为唯一到期批次，于是周期末企业号会被 prefer_expiring 优先选中（期望行为）。
        val nowSec = System.currentTimeMillis() / 1000
        val endSec = parseEndSec(data, "cycleEndTime") ?: parseEndSec(data, "cycleResetTime") ?: 0L
        if (endSec <= nowSec || remain <= 0.0) return Balance(remain)
        val expiring = if (soonSec > 0 && endSec - nowSec <= soonSec) remain else 0.0
        return Balance(remain, endSec, remain, expiring)
    }

    /**
     * 解析上游的到期时刻（`yyyy-MM-dd HH:mm:ss`，**固定按 UTC+8 解释**，与 Go
     * `parsePackageEndTime` 用的 `softRateResetLoc` 同口径）；缺失或解析失败返回 null。
     *
     * 这里每次新建 formatter：`SimpleDateFormat` 非线程安全，而余额刷新（定时任务）
     * 与手动刷新可能并发，共用实例会出现解析错乱。
     */
    private fun parseEndSec(o: JsonObject, key: String): Long? {
        val raw = o[key]?.primTextOrNull() ?: return null
        if (raw.isBlank()) return null
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("GMT+8")
        }
        return runCatching { fmt.parse(raw)?.time }.getOrNull()?.let { it / 1000 }
    }

    suspend fun report(auth: AuthEntry, events: String): String = withContext(Dispatchers.IO) {
        val r = chatCall(auth.realm, auth, "POST", Up.P_REPORT, events)
        if (!r.ok) throw UpstreamException(Classify.of(r.code, r.text), r.code, "HTTP ${r.code} @ ${r.url} :: ${r.text.take(160)}")
        r.text
    }

    /**
     * growth 域请求：**对齐 Go `growthJSON`** —— base 仍走 chatBase（`c.chatBase(a) + path`），
     * 但头族用 **billing 头族**（Go 里 growth 系列调的是 `BillingHeaders`，不是 chat 头族；
     * 后者会多带 X-Conversation-* / X-No-Enterprise-Id 等 chat 专有头）。仅 404 时换 base。
     */
    private fun growthOnce(base: String, auth: AuthEntry, method: String, path: String, body: String?): HttpResult {
        val url = base + path
        val b = Request.Builder().url(url)
        if (method == "POST") b.post((body ?: "{}").toRequestBody(mediaJson)) else b.get()
        billingHeaders(b, auth.realm, auth)
        return runCatching { http.newCall(b.build()).execute().use { HttpResult(it.code, it.body?.string().orEmpty(), url) } }
            .getOrElse { HttpResult(0, it.message ?: "network error", url) }
    }

    private fun growthCall(auth: AuthEntry, method: String, path: String, body: String? = null): HttpResult {
        var last = HttpResult(0, "", "")
        for (base in Up.chatBases(auth.realm)) {
            val r = growthOnce(base, auth, method, path, body)
            logCall(method, r)
            if (r.code in 200..499 && r.code != 404) return r
            last = r
            if (r.code == 0) return r
        }
        return last
    }

    /**
     * 成长任务列表。对齐 Go `ListTasks`：`GET /v2/activity/growth/tasks`，响应体 `data.tasks[]`。
     * 拉取失败返回空列表（调用方按「无任务」处理，不把网络问题伪装成任务为空）。
     */
    suspend fun growthTasks(auth: AuthEntry): List<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val r = growthCall(auth, "GET", Up.P_GROWTH_TASKS)
            if (!r.ok) return@runCatching emptyList()
            val data = parseEnvelope(r.text)
            val arr = (data["tasks"] ?: data["list"]) as? kotlinx.serialization.json.JsonArray
            arr?.mapNotNull { it as? JsonObject } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * 报名（accept）成长任务。对齐 Go `AcceptTasks`：`POST /v2/activity/growth/tasks/accept`，
     * body `{"task_codes": [...]}`。
     *
     * 语义（Go 注释原文）：**accept 是「报名」，不产生进度**；进度由服务端行为事件点亮，
     * 因此 accept 后可幂等重放 —— 重复报名不是错误。
     */
    suspend fun growthAccept(auth: AuthEntry, taskCodes: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (taskCodes.isEmpty()) return@withContext true
        val body = AppJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("task_codes", kotlinx.serialization.json.buildJsonArray { taskCodes.forEach { add(JsonPrimitive(it)) } })
            },
        )
        runCatching { growthCall(auth, "POST", Up.P_GROWTH_ACCEPT, body).ok }.getOrDefault(false)
    }

    /**
     * 领取成长任务奖励。**当前领奖失败的根因修正**（对齐 Go `ClaimReward`，tasks.go:216-272）：
     *
     *  - 旧实现走 chat 域 `{chatBase}/v2/activity/growth/tasks/<code>/claim`（task_code 在路径、带 /v2），
     *    **该路径在 CLI 域不存在**，一直返回 400「task not completed」——这正是此前领奖失败的真相；
     *  - 正确路径是 **Web 域** `{webBase}/activity/growth/tasks/<code>/claim`（**无 /v2 前缀**，
     *    **task_code 在路径**，**无 body**），头族为 Web 头（带 `x-client-platform: web`）。
     *
     * 幂等：响应 `already_claimed==true` → 返回 `(0,0)`（重复领不算错误、无新增奖励，tasks.go:259-270）。
     * 返回 `(credit, energy)`；网络/HTTP 失败返回 null。
     */
    suspend fun growthClaim(auth: AuthEntry, taskCode: String): Pair<Long, Long>? = claimRewardWeb(auth, taskCode)

    // ─────────────────────────── 以下为真实移植新增（对齐 Go 上游）───────────────────────────

    /** 通用原始请求：自定义 Content-Type / 头 / body（桌面、web、mp 三条指纹通道共用）。 */
    private fun execRaw(method: String, url: String, contentType: String, body: String, build: (Request.Builder) -> Unit): HttpResult {
        val b = Request.Builder().url(url)
        if (method == "POST") b.post(body.toRequestBody("application/json".toMediaType())) else b.get()
        b.header("Content-Type", contentType)
        build(b)
        return runCatching { http.newCall(b.build()).execute().use { HttpResult(it.code, it.body?.string().orEmpty(), url) } }
            .getOrElse { HttpResult(0, it.message ?: "network error", url) }
    }

    /** 幂等令牌 client_token（前端 randomUUID 同款语义，streak.go:26-33）。 */
    private fun clientToken(): String {
        val b = ByteArray(16); java.security.SecureRandom().nextBytes(b)
        val h = b.joinToString("") { "%02x".format(it) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    // ---------- 小程序（mp）口径列表 / accept / claim ----------

    /** mp 口径 growth 请求：叠加 `X-Client-Platform: miniprogram`（对齐 tasks.go:86-102 growthJSONMP）。 */
    private fun growthOnceMP(base: String, auth: AuthEntry, method: String, path: String, body: String?): HttpResult {
        val url = base + path
        val b = Request.Builder().url(url)
        if (method == "POST") b.post((body ?: "{}").toRequestBody(mediaJson)) else b.get()
        billingHeaders(b, auth.realm, auth)
        b.header("X-Client-Platform", Up.MP_PLATFORM)
        return runCatching { http.newCall(b.build()).execute().use { HttpResult(it.code, it.body?.string().orEmpty(), url) } }
            .getOrElse { HttpResult(0, it.message ?: "network error", url) }
    }

    private fun growthCallMP(auth: AuthEntry, method: String, path: String, body: String? = null): HttpResult {
        var last = HttpResult(0, "", "")
        for (base in Up.chatBases(auth.realm)) {
            val r = growthOnceMP(base, auth, method, path, body)
            logCall(method, r)
            if (r.code in 200..499 && r.code != 404) return r
            last = r
            if (r.code == 0) return r
        }
        return last
    }

    /** 小程序口径任务列表（对齐 Go ListTasksMP，tasks.go:76-82）。 */
    suspend fun growthTasksMP(auth: AuthEntry): List<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val r = growthCallMP(auth, "GET", Up.P_GROWTH_TASKS)
            if (!r.ok) return@runCatching emptyList()
            val data = parseEnvelope(r.text)
            val arr = (data["tasks"] ?: data["list"]) as? JsonArray
            arr?.mapNotNull { it as? JsonObject } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * 默认口径 + 小程序口径**合并去重**（对齐 Go panel/tasks.go:41-54 accountTasks）：
     * mp 列表是默认口径的超集，按 `task_code` 去重；mp 拉取失败静默（无 mp 部署/活动期零影响）。
     */
    suspend fun growthTasksMerged(auth: AuthEntry): List<JsonObject> = withContext(Dispatchers.IO) {
        val base = growthTasks(auth)
        val seen = base.mapNotNull { it["task_code"]?.primTextOrNull() }.toHashSet()
        val out = base.toMutableList()
        val mp = runCatching { growthTasksMP(auth) }.getOrDefault(emptyList())
        for (t in mp) {
            val code = t["task_code"]?.primTextOrNull() ?: continue
            if (seen.add(code)) out.add(t)
        }
        out
    }

    /** mp 口径报名（对齐 Go AcceptTasksMP，tasks.go:104-109）。 */
    suspend fun growthAcceptMP(auth: AuthEntry, taskCodes: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (taskCodes.isEmpty()) return@withContext true
        val body = buildJsonObject {
            put("task_codes", buildJsonArray { taskCodes.forEach { add(JsonPrimitive(it)) } })
        }.toString()
        runCatching { growthCallMP(auth, "POST", Up.P_GROWTH_ACCEPT, body).ok }.getOrDefault(false)
    }

    /**
     * 批量报名：**分片 20 + 批间 acceptBatchGap**（对齐 Go panel/tasks.go:109-124）。
     * mp=true 时逐条走 mp 头（Go 对 mp 码单独批量）。
     */
    suspend fun growthAcceptSharded(auth: AuthEntry, taskCodes: List<String>, mp: Boolean = false): Int = withContext(Dispatchers.IO) {
        if (taskCodes.isEmpty()) return@withContext 0
        var accepted = 0
        var i = 0
        while (i < taskCodes.size) {
            val end = minOf(i + 20, taskCodes.size)
            val chunk = taskCodes.subList(i, end)
            val ok = if (mp) growthAcceptMP(auth, chunk) else growthAccept(auth, chunk)
            if (ok) accepted += end - i
            i = end
            if (i < taskCodes.size) delay(1050) // acceptBatchGap（tasks.go:16-17）
        }
        accepted
    }

    // ---------- 领奖（默认口径 Web 域 / mp 口径 chat 域）----------

    /** Web 域领奖单次请求：无 /v2 前缀、task_code 在路径、无 body、Web 头族（tasks.go:227-253）。 */
    private fun claimWebOnce(base: String, auth: AuthEntry, code: String): HttpResult {
        val url = base + Up.P_GROWTH_CLAIM_PREFIX + java.net.URLEncoder.encode(code, "UTF-8") + "/claim"
        val b = Request.Builder().url(url).post("".toRequestBody(mediaJson))
        b.header("Authorization", "Bearer ${auth.access_token}")
        b.header("Accept", "application/json, text/plain, */*")
        b.header("Content-Type", "application/json")
        b.header("Origin", base)
        b.header("Referer", "$base/profile/growth-center")
        b.header("x-client-platform", "web")
        b.header("User-Agent", if (userAgentOverride.isNotBlank()) userAgentOverride else Up.workBuddyUA(auth.realm))
        if (auth.uid.isNotBlank()) b.header("X-User-Id", auth.uid)
        if (auth.enterprise_id.isNotBlank()) { b.header("X-Enterprise-Id", auth.enterprise_id); b.header("X-Tenant-Id", auth.enterprise_id) }
        if (auth.domain.isNotBlank()) b.header("X-Domain", auth.domain)
        return runCatching { http.newCall(b.build()).execute().use { HttpResult(it.code, it.body?.string().orEmpty(), url) } }
            .getOrElse { HttpResult(0, it.message ?: "network error", url) }
    }

    /** mp 口径领奖单次请求：chat 域 + BillingHeaders + mp 头（tasks.go:111-131）。 */
    private fun claimChatMPOnce(base: String, auth: AuthEntry, code: String): HttpResult {
        val url = base + Up.P_GROWTH_CLAIM_PREFIX + java.net.URLEncoder.encode(code, "UTF-8") + "/claim"
        val b = Request.Builder().url(url).post("".toRequestBody(mediaJson))
        billingHeaders(b, auth.realm, auth)
        b.header("X-Client-Platform", Up.MP_PLATFORM)
        return runCatching { http.newCall(b.build()).execute().use { HttpResult(it.code, it.body?.string().orEmpty(), url) } }
            .getOrElse { HttpResult(0, it.message ?: "network error", url) }
    }

    /** 解析领奖响应 `{already_claimed, credit, energy}`（parseClaimReward，tasks.go:133-144）。 */
    private fun parseClaimData(text: String): Pair<Long, Long> {
        val data = runCatching { parseEnvelope(text) }.getOrNull() ?: return 0L to 0L
        if (data["already_claimed"]?.primTextOrNull()?.toBooleanStrictOrNull() == true) return 0L to 0L
        val credit = data["credit"]?.primTextOrNull()?.toLongOrNull() ?: 0L
        val energy = data["energy"]?.primTextOrNull()?.toLongOrNull() ?: 0L
        return credit to energy
    }

    /** Web 域领奖（默认口径主路径）。 */
    suspend fun claimRewardWeb(auth: AuthEntry, taskCode: String): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        for (base in Up.webBases(auth.realm)) {
            val r = claimWebOnce(base, auth, taskCode)
            logCall("POST", r)
            if (r.ok) return@withContext parseClaimData(r.text)
            if (r.code == 0) break
        }
        null
    }

    /**
     * mp 口径领奖：先 chat 域 mp 头；**HTTP 400 时降级 Web 域**（对齐 Go ClaimRewardMP，tasks.go:114-131）。
     */
    suspend fun growthClaimMP(auth: AuthEntry, taskCode: String): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        var badRequest = false
        for (base in Up.chatBases(auth.realm)) {
            val r = claimChatMPOnce(base, auth, taskCode)
            logCall("POST", r)
            if (r.ok) return@withContext parseClaimData(r.text)
            if (r.code == 400) { badRequest = true; break }
            if (r.code == 0) break
        }
        if (badRequest) claimRewardWeb(auth, taskCode) else null
    }

    // ---------- 行为事件上报通道（/v2/report 三条指纹）----------

    /**
     * 对话活跃上报（billing 域 /v2/report，数组体）。**硬性约束：事件体必含 `userId`**，
     * 否则上游返回 200 但静默丢弃（report.go:2-4）。默认 model=`deepseek-v4-flash`。
     * 一条上报同时点亮 growth 连登 + 解锁 first_buddy（report.go:6）。
     */
    suspend fun reportChatActivity(auth: AuthEntry, conversationId: String, requestId: String, modelId: String = "", modelName: String = ""): String = withContext(Dispatchers.IO) {
        val rid = requestId.ifBlank { conversationId }
        val mid = modelId.ifBlank { "deepseek-v4-flash" }
        val mname = modelName.ifBlank { mid }
        val now = System.currentTimeMillis()
        val ev = buildJsonObject {
            put("eventCode", JsonPrimitive("chat_request_send"))
            put("timestamp", JsonPrimitive(now))
            put("reportDelay", JsonPrimitive(0))
            put("mode", JsonPrimitive("craft"))
            put("conversationId", JsonPrimitive(conversationId))
            put("requestId", JsonPrimitive(rid))
            put("inputLength", JsonPrimitive(12))
            put("requestModelId", JsonPrimitive(mid))
            put("requestModelName", JsonPrimitive(mname))
            put("isPlan", JsonPrimitive(false))
            put("isAutoExecuteTerminal", JsonPrimitive(false))
            put("isAutoModify", JsonPrimitive(false))
            put("codebaseEnable", JsonPrimitive(false))
            put("maxToken", JsonPrimitive(0)); put("maxSteps", JsonPrimitive(0))
            put("temperature", JsonPrimitive(0)); put("maxRetries", JsonPrimitive(0))
            put("mentionContexts", buildJsonArray {}); put("knowledgeId", buildJsonArray {}); put("knowledgeName", buildJsonArray {})
            put("codebaseId", JsonPrimitive("")); put("mentionContextCount", JsonPrimitive(0))
            put("command", JsonPrimitive("")); put("expertId", JsonPrimitive("")); put("recommendId", JsonPrimitive(""))
            put("skillId", JsonPrimitive("")); put("skillCount", JsonPrimitive(0)); put("totalCount", JsonPrimitive(0))
            put("fileUri", JsonPrimitive("")); put("presentAt", JsonPrimitive(now)); put("traceId", JsonPrimitive(""))
            put("rootRequestId", JsonPrimitive(rid)); put("parentConversationId", JsonPrimitive(conversationId))
            put("agentName", JsonPrimitive("default")); put("agentType", JsonPrimitive("conversation"))
            put("userId", JsonPrimitive(auth.uid)) // 必填：缺失上游 200 静默丢弃（report.go:2-4）
        }
        val body = buildJsonArray { add(ev) }.toString()
        val r = billingCall(auth.realm, auth, "POST", listOf(Up.P_REPORT), body)
        if (!r.ok) throw UpstreamException(Classify.of(r.code, r.text), r.code, "report HTTP ${r.code} @ ${r.url} :: ${r.text.take(120)}")
        "ok"
    }

    /** 公共桌面指纹（注入每个事件，覆盖同名业务键），对齐 desktopFingerprint（desktop.go:59-86）。 */
    private fun desktopFingerprint(auth: AuthEntry): JsonObject {
        val now = System.currentTimeMillis()
        return buildJsonObject {
            put("timezone", JsonPrimitive("Asia/Shanghai")); put("reportDelay", JsonPrimitive(2000))
            put("userId", JsonPrimitive(auth.uid)); put("username", JsonPrimitive(auth.nickname))
            put("userNickname", JsonPrimitive(auth.nickname)); put("product", JsonPrimitive("SaaS"))
            put("releaseDate", JsonPrimitive(1789036585355L))
            put("commit", JsonPrimitive("5f9692923c93033111c51ad7b003eb80204a9b75"))
            put("ideName", JsonPrimitive("WorkBuddy")); put("ideType", JsonPrimitive("WorkBuddy")); put("ideVersion", JsonPrimitive("5.5.6"))
            put("machineId", JsonPrimitive(Up.stableId(auth.uid, "machine"))); put("sessionId", JsonPrimitive(Up.stableId(auth.uid, "session")))
            put("extName", JsonPrimitive("workbuddy-desktop")); put("extVersion", JsonPrimitive("5.5.6"))
            put("os", JsonPrimitive("win32")); put("arch", JsonPrimitive("x64")); put("osVersion", JsonPrimitive("10.0.26220"))
            put("cpuCores", JsonPrimitive(20)); put("memorySize", JsonPrimitive(24))
            put("timestamp", JsonPrimitive(now)); put("presentAt", JsonPrimitive(now))
        }
    }

    /**
     * 桌面指纹上报（chatBase /v2/report，`workbuddy-desktop` 事件族）。
     * 对齐 Go ReportDesktopEvent（desktop.go:88-127）：每事件自动注入桌面指纹，业务字段优先。
     */
    suspend fun reportDesktopEvent(auth: AuthEntry, events: List<JsonObject>): String = withContext(Dispatchers.IO) {
        if (events.isEmpty()) return@withContext "no events"
        val fp = desktopFingerprint(auth)
        val body = buildJsonArray { events.forEach { add(mergeJsonObject(fp, it)) } }.toString()
        val base = Up.base(auth.realm) // desktopBase = chatBase（desktop.go:46-47）
        val r = execRaw("POST", base + Up.P_REPORT, "application/json;charset=UTF-8", body) { b ->
            b.header("Authorization", "Bearer ${auth.access_token}")
            b.header("Accept", "application/json, text/plain, */*")
            b.header("User-Agent", Up.UA_DESKTOP)
            b.header("X-Domain", base)
            b.header("X-Product", "SaaS")
            b.header("X-Request-ID", Up.stableId(auth.uid, "req") + (System.nanoTime() % 1_000_000L))
            if (auth.uid.isNotBlank()) b.header("X-User-Id", auth.uid)
        }
        logCall("POST", r)
        if (!r.ok) throw UpstreamException(Classify.of(r.code, r.text), r.code, "desktop report HTTP ${r.code} @ ${r.url}")
        "ok"
    }

    /** Web 指纹上报（webBase /v2/report，页面行为类；用于 Library_read），desktop.go:263-295。 */
    suspend fun reportWebEvent(auth: AuthEntry, eventCode: String, pageURL: String, elementId: String, elementName: String): String = withContext(Dispatchers.IO) {
        val web = Up.webBases(auth.realm).first()
        val ev = buildJsonObject {
            put("eventCode", JsonPrimitive(eventCode)); put("timestamp", JsonPrimitive(System.currentTimeMillis()))
            put("reportDelay", JsonPrimitive(0)); put("pageURL", JsonPrimitive(pageURL))
            put("elementId", JsonPrimitive(elementId)); put("elementName", JsonPrimitive(elementName))
            put("os", JsonPrimitive("Win32")); put("arch", JsonPrimitive("")); put("osVersion", JsonPrimitive("10.0"))
            put("userAgent", JsonPrimitive(Up.UA_WEB)); put("machineId", JsonPrimitive(Up.stableId(auth.uid, "webmachine")))
            put("userId", JsonPrimitive(auth.uid)); put("userNickname", JsonPrimitive(auth.nickname))
            put("enterpriseId", JsonPrimitive(auth.enterprise_id))
        }
        val body = buildJsonArray { add(ev) }.toString()
        val r = execRaw("POST", web + Up.P_REPORT, "application/json", body) { b ->
            b.header("Authorization", "Bearer ${auth.access_token}")
            b.header("Accept", "application/json")
            b.header("x-client-platform", "web")
            b.header("Origin", web)
            b.header("Referer", pageURL)
            b.header("User-Agent", Up.UA_WEB)
            if (auth.uid.isNotBlank()) b.header("X-User-Id", auth.uid)
        }
        logCall("POST", r)
        if (!r.ok) throw UpstreamException(Classify.of(r.code, r.text), r.code, "web report HTTP ${r.code} @ ${r.url}")
        "ok"
    }

    /** 小程序事件公共指纹（对齐 school.go:62-80 mpEventBase）。 */
    private fun mpEventBase(auth: AuthEntry): JsonObject = buildJsonObject {
        put("ideType", JsonPrimitive("WorkBuddy_MP")); put("ideVersion", JsonPrimitive("2.4.0")); put("extVersion", JsonPrimitive("2.4.0"))
        put("extName", JsonPrimitive("workbuddy-mp")); put("product", JsonPrimitive("SaaS"))
        put("ideName", JsonPrimitive("wx_app_cloud")); put("platform", JsonPrimitive("mini_program"))
        put("os", JsonPrimitive("windows")); put("machineId", JsonPrimitive(Up.stableId(auth.uid, "mpmachine")))
        put("userId", JsonPrimitive(auth.uid)); put("userNickname", JsonPrimitive(auth.nickname))
    }

    /**
     * 小程序指纹上报（BillingBaseCN /v2/report），school.go:82-119 ReportMPEvent。
     * 说明：本通道已实现但**当前无自动化动作调用**——mp 判据只能由 mini chat 事件点亮，
     * 而 mini chat 需遵守 45s 真人节奏（见 GrowthActions 文档），故未接入（避免死循环/高风险重试）。
     */
    suspend fun reportMpEvent(auth: AuthEntry, events: List<JsonObject>): String = withContext(Dispatchers.IO) {
        if (events.isEmpty()) return@withContext "no events"
        val base = mpEventBase(auth)
        val body = buildJsonArray { events.forEach { add(mergeJsonObject(base, it)) } }.toString()
        val r = execRaw("POST", Up.BILLING_BASE_CN + Up.P_REPORT, "application/json", body) { b ->
            b.header("Authorization", "Bearer ${auth.access_token}")
            b.header("Accept", "application/json")
            if (auth.uid.isNotBlank()) b.header("X-User-Id", auth.uid)
            b.header("X-Client-Product", "workbuddy-mp"); b.header("X-Client-Version", "2.4.0")
            b.header("X-Client-Platform", "mp-weixin"); b.header("X-Platform", "wechatmp")
        }
        logCall("POST", r)
        if (!r.ok) throw UpstreamException(Classify.of(r.code, r.text), r.code, "mp report HTTP ${r.code} @ ${r.url}")
        "ok"
    }

    /** 设置外观主题（chatBase /v2/user-asset/appearance/set），desktop.go:206-226。 */
    suspend fun setAppearanceTheme(auth: AuthEntry, resourceKey: String): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("kind", JsonPrimitive("theme")); put("resource_key", JsonPrimitive(resourceKey)) }.toString()
        val r = execRaw("POST", Up.base(auth.realm) + Up.P_APPEARANCE_SET, "application/json;charset=UTF-8", body) { b ->
            b.header("Authorization", "Bearer ${auth.access_token}")
            b.header("Accept", "application/json, text/plain, */*")
            b.header("User-Agent", Up.UA_DESKTOP); b.header("X-Product", "SaaS")
            if (auth.uid.isNotBlank()) b.header("X-User-Id", auth.uid)
        }
        logCall("POST", r)
        if (!r.ok) throw UpstreamException(Classify.of(r.code, r.text), r.code, "appearance HTTP ${r.code} @ ${r.url}")
        "ok"
    }

    // ---------- Buddy（first_buddy 动作）----------

    /** 同意 Buddy 协议（幂等），travel.go:129-133。 */
    suspend fun buddyAgreement(auth: AuthEntry): Boolean = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("agree", JsonPrimitive(true)) }.toString()
        growthCall(auth, "POST", Up.P_BUDDY_AGREEMENT, body).ok
    }

    /** 领养第一只 Buddy；门槛未过返回 false + 响应体（HTTP 400 + first_buddy 关键词），travel.go:122-127/155-166。 */
    suspend fun buddyFirst(auth: AuthEntry): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val r = growthCall(auth, "POST", Up.P_BUDDY_FIRST, "{}")
        (r.ok) to r.text
    }

    // ---------- 连登（streak）闭环 ----------

    /** 连登完整状态（GET /activity/growth/streak），streak.go:64-74。 */
    suspend fun growthStreakStatus(auth: AuthEntry): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            val r = growthCall(auth, "GET", Up.P_GROWTH_STREAK)
            if (!r.ok) return@runCatching null
            parseEnvelope(r.text)
        }.getOrNull()
    }

    /** 兑换连登档位（tier ∈ 7d/14d/28d），streak.go:78-82。未解锁上游返回 403（调用方按 locked 跳过）。 */
    suspend fun growthRedeemTier(auth: AuthEntry, tier: String): Boolean = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("tier", JsonPrimitive(tier)); put("client_token", JsonPrimitive(clientToken())) }.toString()
        growthCall(auth, "POST", Up.P_GROWTH_REDEEM, body).ok
    }

    /** 抽奖次数（GET /activity/growth/lottery/summary），streak.go:85-100。 */
    suspend fun lotteryChances(auth: AuthEntry): Int = withContext(Dispatchers.IO) {
        runCatching {
            val r = growthCall(auth, "GET", Up.P_GROWTH_LOTTERY_SUMMARY)
            if (!r.ok) return@runCatching 0
            parseEnvelope(r.text)["chances"]?.primTextOrNull()?.toIntOrNull() ?: 0
        }.getOrDefault(0)
    }

    /** 抽奖一次，返回原始奖品载荷字符串，streak.go:103-105。 */
    suspend fun lotteryDraw(auth: AuthEntry): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject { put("client_token", JsonPrimitive(clientToken())) }.toString()
            val r = growthCall(auth, "POST", Up.P_GROWTH_LOTTERY_DRAW, body)
            if (!r.ok) return@runCatching null
            r.text
        }.getOrNull()
    }

    /** 昨日是否漏签（heatmap cell score==0），blackcat.go:98-119。 */
    suspend fun heatmapYesterdayMissed(auth: AuthEntry): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val r = growthCall(auth, "GET", Up.P_GROWTH_HEATMAP)
            if (!r.ok) return@runCatching false
            val data = parseEnvelope(r.text)
            val y = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(System.currentTimeMillis() - 86_400_000L))
            val cells = data["cells"] as? JsonArray ?: return@runCatching false
            for (c in cells) {
                val o = c.objOrNull() ?: continue
                val d = o["date"]?.primTextOrNull() ?: ""
                if (d.length >= 10 && d.substring(0, 10) == y) {
                    return@runCatching (o["score"]?.primTextOrNull()?.toIntOrNull() ?: 0) == 0
                }
            }
            false
        }.getOrDefault(false)
    }

    /** 使用补签卡（保住连登），blackcat.go:121-126。 */
    suspend fun useMakeupCard(auth: AuthEntry, date: String): Boolean = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("target_date", JsonPrimitive(date)) }.toString()
        growthCall(auth, "POST", Up.P_GROWTH_MAKEUP, body).ok
    }

    /** 领新手礼包（billing 域），blackcat.go:71-82。每号一次，已领返回 null。 */
    suspend fun claimGift(auth: AuthEntry): Long? = withContext(Dispatchers.IO) {
        val r = billingCall(auth.realm, auth, "POST", listOf(Up.P_CLAIM_GIFT), "{}")
        if (!r.ok) return@withContext null
        runCatching { parseEnvelope(r.text)["credit"]?.primTextOrNull()?.toLongOrNull() ?: 0L }.getOrNull()
    }

    /** 领活动补偿（billing 域），blackcat.go:85-95。有则领，无则 null。 */
    suspend fun claimCompensation(auth: AuthEntry): Long? = withContext(Dispatchers.IO) {
        val r = billingCall(auth.realm, auth, "POST", listOf(Up.P_CLAIM_COMPENSATION), "{}")
        if (!r.ok) return@withContext null
        runCatching { parseEnvelope(r.text)["credit"]?.primTextOrNull()?.toLongOrNull() ?: 0L }.getOrNull()
    }

    // ---------- 网络自检（诊断） ----------

    /**
     * 对单个账号做一次端到端探测，逐条打印 URL / HTTP 状态 / 响应片段，
     * 用于在 App 日志中直接定位「哪个域名或路径不可达」。
     */
    suspend fun diagnose(auth: AuthEntry): String = withContext(Dispatchers.IO) {
        val realm = auth.realm
        val sb = StringBuilder()
        sb.append("realm=$realm uid=${auth.uid.take(8)} token=${if (auth.access_token.isBlank()) "空" else "有(${auth.access_token.length}字符)"}\n")
        for (base in Up.billingBases(realm)) {
            val r = runCatching { billingOnce(base + Up.P_RESOURCE, realm, auth, "POST", resourceBody()) }
                .getOrElse { HttpResult(0, it.message ?: "err", base + Up.P_RESOURCE) }
            sb.append("资源 ${r.url} -> HTTP ${r.code} ${r.text.take(90).replace('\n', ' ')}\n")
                .also { if (r.ok) sb.append("（POST + 固定 body，与真实余额查询一致；返回 200 即该域可用）\n") }
        }
        for (base in Up.chatBases(realm)) {
            val r = chatCallSingle(base, realm, auth)
            sb.append("模型 $base${Up.P_MODELS_V3} -> HTTP ${r.code} ${r.text.take(90).replace('\n', ' ')}\n")
        }
        sb.toString().trim()
    }

    private fun chatCallSingle(base: String, realm: String, auth: AuthEntry): HttpResult {
        val url = base + Up.P_MODELS_V3
        val b = Request.Builder().url(url).get()
        commonHeaders(b, realm, auth)
        return runCatching { http.newCall(b.build()).execute().use { HttpResult(it.code, it.body?.string().orEmpty(), url) } }
            .getOrElse { HttpResult(0, it.message ?: "err", url) }
    }

    // ---------- 辅助 ----------

    private fun JsonObject.str(vararg keys: String): String {
        for (k in keys) {
            val v = this[k]?.primTextOrNull()
            if (!v.isNullOrBlank()) return v
        }
        return ""
    }

    private fun JsonObject.num(vararg keys: String): Double? {
        for (k in keys) {
            val v = this[k]?.primTextOrNull() ?: continue
            v.toDoubleOrNull()?.let { return it }
        }
        return null
    }
}

private val JsonPrimitive.strOrNull: String?
    get() = if (this is JsonNull) null else content

/**
 * 错误分类：**判定顺序逐条对齐 Go `internal/upstream/client.go:Classify`**。
 *
 * 顺序自「严」到「宽」，每层先后都有语义依据（Go 注释原文摘要）：
 *  0. 11102「该后端无此模型」最具体，最先判（只认 400/404；429+11102 属限流走第 4 层）；
 *  1. 402 真正的计费余额耗尽，最严、最不可自愈；
 *  2. session 终态（12153 等）需人工重登，精确 marker 优先于宽泛的限流层；
 *  3. 账号级授权/配额故障（11140 request illegal / 14017 trial not activated）——
 *     **必须先于 status==429**：14017 常带 429，落到限流层会误判（限流可自愈，账号故障等不来）；
 *  4. 429 + code 14018 = 明确积分耗尽 → 硬冷却（只认结构化业务码，不靠文案猜测）；
 *  5. status==429 → 限流兜底（状态码比关键词权威；真正的余额耗尽由 402/14018 捕获）；
 *  6. 非 429 才允许计费关键词兜底；
 *  7. 非 429 携带限流文案 → 限流（issue #28）；
 *  8. 请求级确定性错误（11115 过长 / 图片格式 / 内容策略）——换号必然同样失败；
 *  9. WAF 403（无业务信封）→ 其余 4xx/5xx。
 */
object Classify {
    private val hardMarkers = listOf("insufficient", "余额不足", "额度不足", "quota exceeded")
    private val softRateMarkers = listOf("rate limit", "rate-limiting", "too many requests", "限流", "请求过于频繁")
    private val sessionDeadMarkers = listOf("12153", "session dead", "token expired", "invalid token", "登录过期")
    private val accountFaultMarkers = listOf("11140", "request illegal", "14017", "trial not activated")
    private val contentBlockedMarkers = listOf(
        "风险", "违规内容", "content policy", "content_filter", "sensitive",
        // 11128 的另一种形态（新上游 client_test：{"code":11128,"msg":"blocked by security policy"}
        // → ErrContentBlocked）。它不是渠道校验（msg 里没有渠道文案），属**零动作**的内容误报，
        // 不能因为 code 相同就被 isChannelBlock 当成渠道拦截，也不能落进 CLIENT 去降权。
        "security policy", "blocked by security",
    )
    private val promptTooLongMarkers = listOf("11115", "prompt is too long", "context length")
    private val invalidImageMarkers = listOf("11133", "invalid image", "image format", "unsupported image", "图片格式")
    /** 请求体解析失败（HTTP 400 + `"code":11101` / "Unmarshal chat params failed"）→ 请求级确定性错误。 */
    private val badParamsMarkers = listOf("11101", "unmarshal chat params failed")

    /**
     * 渠道校验拦截的业务码。上游有**两种**编码形态，必须都认：
     *  - 字符串 `"11-128"`（新上游 handler.go:1823 与 17b5204 提交取证的真实形态）；
     *  - 数字 `11128`（同一拦截的另一编码；2026-10-21 实测报文为 code 11128 +
     *    msg "request illegal" + displayTips「这次调用没通过渠道校验」）。
     *
     * 只按数字判会漏掉字符串形态，只按字符串判会漏掉实测现场 —— 两者都要。
     */
    private val channelBlockCodes = listOf("11-128", "11128")

    /** msg 侧判据：这些文案都属于「渠道校验 / 反探测」这一族，而不是账号授权故障。 */
    private val channelBlockTexts = listOf(
        "request illegal",           // 实测形态（11128 / 11-128 共用该 msg）
        "unapproved channel",        // 首条 developer
        "conversation id missing",   // 三件套缺头
        "not system prompt",         // 首条不是 system
        "channel verification",      // 文案兜底
        "渠道校验",
    )

    /** `"code":N` / `"code": N` / `"code":"N"` / `"code": "N"` 的空白容差匹配（lower 须为小写）。 */
    private fun codeMarker(lower: String, code: String): Boolean {
        val q = "\""
        return lower.contains(q + "code" + q + ":" + code) ||
            lower.contains(q + "code" + q + ": " + code) ||
            lower.contains(q + "code" + q + ":" + q + code + q) ||
            lower.contains(q + "code" + q + ": " + q + code + q)
    }

    /**
     * 渠道校验拦截判据（`ErrKind.CHANNEL_BLOCK` 的唯一入口）。
     *
     * 判据**必须同时**要求「业务码命中」与「msg 属于渠道文案」：
     *  - 只看 msg："request illegal" 也出现在 11140（账号级授权风控，必须硬禁用）；
     *  - 只看 code：11128 也用于内容审核（`{"code":11128,"msg":"blocked by security policy"}`，
     *    新上游 client_test 的 ErrContentBlocked 用例）—— 那属零动作，不该软冷却。
     * 两者同时成立才是「渠道校验」。
     */
    /**
     * 14003 模型繁忙判据：结构化 code 主判，官方中文/英文文案兜底（上游 issue #46）。
     *
     * 只在 4xx/429 语境使用（见 [of] 的调用位置）；5xx 属服务端故障优先，不在此列。
     */
    fun isModelBusy(body: String): Boolean {
        val b = body.lowercase().replace("\\", "")
        if (codeMarker(b, "14003")) return true
        return b.contains("模型繁忙") || b.contains("请求繁忙") || b.contains("model busy") ||
            b.contains("model is busy") || b.contains("currently busy")
    }

    fun isChannelBlock(body: String): Boolean {
        // 去掉反斜杠再判：SSE error 帧与网关 sendError 会把上游信封**再序列化一层**，
        // 于是 `"code":11128` 以 `\"code\":11128` 的形态出现（实测报文就是这样）。
        // 只在本函数内做，不影响其它分类判据的原文语义。
        val b = body.lowercase().replace("\\", "")
        if (!b.contains("\"code\"")) return false
        if (channelBlockCodes.none { codeMarker(b, it) }) return false
        return channelBlockTexts.any { b.contains(it) }
    }

    fun of(status: Int, body: String): ErrKind {
        val b = body.lowercase()
        return when {
            // 0. 11102「该后端无此模型」
            (status == 400 || status == 404) && b.contains("11102") -> ErrKind.MODEL_BLOCKED
            // 0.5 渠道校验拦截（11-128 / 11128 "request illegal"）
            //
            // 必须排在 accountFaultMarkers 之前：后者的 "request illegal" 判据会把这一类
            // 也吞成 ACCOUNT_FAULT → 旧实现据此 **Disable** 账号（把健康号永久踢出池子），
            // 一次渠道校验失败就把整池打空，表象就是「所有请求 503 no_healthy_account」。
            isChannelBlock(body) -> ErrKind.CHANNEL_BLOCK
            // 0.6 14003 模型繁忙：**必须先于 429 兜底**，否则会被当成账号级限流
            // （soft_rate 600s 起），把间歇抖动放大成整分钟不可用（上游 issue #46）。
            isModelBusy(body) -> ErrKind.MODEL_BUSY
            // 1. 402 计费余额耗尽
            status == 402 -> ErrKind.HARD_CREDIT
            // 2. session 终态
            sessionDeadMarkers.any { b.contains(it) } -> ErrKind.SESSION_DEAD
            // 3. 账号级授权/配额故障（先于 429）
            accountFaultMarkers.any { b.contains(it) } -> ErrKind.ACCOUNT_FAULT
            // 4. 429 + 14018 = 积分耗尽
            status == 429 && b.contains("14018") -> ErrKind.HARD_CREDIT
            // 5. 429 限流兜底
            status == 429 -> ErrKind.SOFT_RATE
            // 6. 非 429 的计费关键词
            hardMarkers.any { b.contains(it) } -> ErrKind.HARD_CREDIT
            // 7. 非 429 的限流文案
            softRateMarkers.any { b.contains(it) } -> ErrKind.SOFT_RATE
            // 8. 请求级确定性错误
            promptTooLongMarkers.any { b.contains(it) } -> ErrKind.PROMPT_TOO_LONG
            invalidImageMarkers.any { b.contains(it) } -> ErrKind.IMAGE_INVALID
            contentBlockedMarkers.any { b.contains(it) } -> ErrKind.CONTENT_BLOCKED
            // 9. WAF 403（无业务信封）
            status == 403 && !body.contains("\"code\"") && !body.contains("\"msg\"") -> ErrKind.WAF_BLOCK
            status == 404 -> ErrKind.NOT_FOUND
            status >= 500 -> ErrKind.SERVER
            // 4xx 兜底：只有「请求体解析失败」（11101 / Unmarshal chat params failed）才是
            // 请求级确定性错误（BAD_PARAMS → fail-fast）；**其余 4xx 归 CLIENT** —— 只换号 +
            // 连败降权、继续轮转（对齐 Go client.go:578-593）。
            //
            // 原实现把**所有** 4xx 都归 BAD_PARAMS，而 failFastKinds 含 BAD_PARAMS，于是任何
            // 未知 4xx（401/403 带信封、405、非特定文案的 400…）都会 fail-fast 直接放弃池里
            // 其它账号 —— 与 failFastKinds 的注释、README 1.6.1 的「CLIENT 继续轮转」自述矛盾。
            badParamsMarkers.any { b.contains(it) } -> ErrKind.BAD_PARAMS
            status >= 400 -> ErrKind.CLIENT
            else -> ErrKind.NONE
        }
    }

    /**
     * SSE `error` 帧分类（逐字对齐 Go `upstream.FrameKind`）。
     *
     * 上游**常以「HTTP 200 + error 帧」下发** 6004 模型级限流 / 内容拦截 / 审核，
     * 而不是用 HTTP 状态码（Go `sse.go:489` 明示这是真实形态）。此时：
     *  - 帧内含 `"code":6004` → 直接判模型级限流（不依赖 status）；
     *  - 其余取帧内 `error.message` 走 Classify（请求级 400 口径，与 Go 一致）。
     */
    fun frame(payload: String): ErrKind {
        if (RateLimit.isModelRateLimit(payload)) return ErrKind.SOFT_RATE
        val err = runCatching {
            AppJson.parseToJsonElement(payload).jsonObject["error"]?.objOrNull()
        }.getOrNull()
        val msg = err?.get("message")?.primText().orEmpty()
        if (msg.isBlank()) return ErrKind.NONE
        // 把 `error.code` 拼回一个最小信封再分类：渠道校验（11-128 / 11128）与 11140
        // **共用同一句** "request illegal"，只拿 message 判会把前者也归进 ACCOUNT_FAULT
        // → 旧实现据此 Disable 账号（一次渠道拦截就把整池打空）。帧路径（SSE error 帧、
        // 内嵌失败帧）的 code 因此必须参与判定，与 Classify.of 同口径。
        val code = err?.get("code")?.primText()?.trim().orEmpty()
        if (code.isBlank()) return of(400, msg)
        val numeric = code.all { it.isDigit() }
        val envelope = if (numeric) {
            "{\"code\":" + code + ",\"msg\":\"" + msg + "\"}"
        } else {
            "{\"code\":\"" + code + "\",\"msg\":\"" + msg + "\"}"
        }
        return of(400, envelope)
    }
}

/**
 * 429 限流信封解析（逐条对齐 Go `upstream.IsModelRateLimit` / `upstream.ParseRateReset`）。
 *
 * - `isModelRateLimit`：body 含 `"code":6004`（容忍空格与字符串引号）→ 该**模型级**限流：
 *   账号本身健康，仅此模型此刻被限，切模型即可用（Go issue #31）。绝不能当账号级冷却，
 *   否则单账号池一被 429 就整池不可用。
 * - `parseReset`：CN 文案「将在 <时间> 重置」/ global 英文「reset at <时间>」，时间固定
 *   按 UTC+8 的 `yyyy-MM-dd HH:mm:ss` 解释；解析不到返回 0（走有界退避）。
 */
object RateLimit {
    private val reModelRateLimit = Regex("\"code\"\\s*:\\s*\"?6004\"?")
    private val reResetCN = Regex("将在 (.+?) 重置")
    private val reResetEN = Regex("(?i)reset at (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})")
    /**
     * 必须用 `java.time`（不可变、线程安全）：`SimpleDateFormat` 是**非线程安全**的，而
     * [parseReset] 的调用点（`Pool.onError` 的 429 分支）跑在 HTTP 线程池的多个并发请求线程上。
     * 共享实例被并发 parse 时，轻则解析出错、重则抛异常 —— 结果是「重置墙钟解析失败 →
     * 退化成有界退避」，把上游给出的精确冷却时间丢掉。（同仓库 LogBus 已因同一原因改 java.time。）
     */
    private val resetZone: java.time.ZoneId = java.time.ZoneId.of("GMT+8")

    private val fmt: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)

    /**
     * 429 业务码 6000 只在带「切换其他模型」文案时才算**模型级**（上游 2026-10-24）：
     * 不带该文案的 6000 语义未知，仍按账号级处理 —— 把「单模型限流」误判成账号级会把
     * 整号冻结 soft_rate_max（2h），其他模型也被殃及。
     *
     * 注意**不要**把 14003 加进来：它是 `ErrKind.MODEL_BUSY`（10~60s 独立短冷却），
     * 而本函数的调用方（[Classify.frame]）会把它判成账号级 SOFT_RATE，直接回退 1.6.6 的修复。
     */
    private val reCode6000 = Regex("\"code\"\\s*:\\s*\"?6000\"?")
    private val reSwitchModelHint =
        Regex("切换其他模型|切换其他模型继续使用|switch to another model|try another model", RegexOption.IGNORE_CASE)

    fun isModelRateLimit(body: String): Boolean =
        reModelRateLimit.containsMatchIn(body) ||
            (reCode6000.containsMatchIn(body) && reSwitchModelHint.containsMatchIn(body))

    /** 解析上游重置墙钟（epoch 秒）；无重置文案或解析失败 → 0。 */
    fun parseReset(body: String): Long {
        val m = reResetCN.find(body) ?: reResetEN.find(body) ?: return 0
        val ts = m.groupValues[1].trim().removeSuffix(" UTC+8")
        val t = runCatching {
            java.time.LocalDateTime.parse(ts, fmt).atZone(resetZone).toInstant().toEpochMilli()
        }.getOrNull() ?: return 0
        return t / 1000
    }
}

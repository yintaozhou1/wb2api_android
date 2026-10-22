package com.wb.api.server

import com.wb.api.Gateway
import com.wb.api.model.ModelInfo
import com.wb.api.core.primText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** 模型列表服务：按 realm 拉取上游 /v3/config，缓存 10 分钟。 */
object ModelService {
    private const val TTL = 10 * 60 * 1000L
    // @Volatile：cache/cachedAt 会被多个 HTTP 工作线程、UI 线程与面板线程并发读；不加会有
    // 可见性问题（可能读到半初始化的 list 或过期的 cachedAt）。
    @Volatile private var cache: List<ModelInfo> = emptyList()
    @Volatile private var cachedAt = 0L

    suspend fun listModels(force: Boolean = false): List<JsonElement> {
        val infos = cached(force)
        return infos.map { toOpenAi(it) }
    }

    fun listInfos(force: Boolean = false): List<ModelInfo> = cached(force)

    /** 该模型的 supported_efforts（键为裸模型名），供出站 reasoning_effort 降级；无缓存则空。 */
    fun supportedEfforts(realm: String, model: String): Map<String, List<String>> {
        val mi = cache.firstOrNull { it.realm == realm && it.model == model } ?: return emptyMap()
        return if (mi.supported_efforts.isEmpty()) emptyMap() else mapOf(model to mi.supported_efforts)
    }

    /** 该模型的默认 reasoning 档位；无则空（thinking 注入回落 high）。 */
    fun defaultEfforts(realm: String, model: String): Map<String, String> {
        val mi = cache.firstOrNull { it.realm == realm && it.model == model } ?: return emptyMap()
        return if (mi.default_effort.isBlank()) emptyMap() else mapOf(model to mi.default_effort)
    }

    /** 该模型声明的最大输出 token（出站缺省补值用）；未知返回 0（表示不干预）。 */
    fun maxOutputTokens(realm: String, model: String): Long =
        cache.firstOrNull { it.realm == realm && it.model == model }?.max_output_tokens ?: 0L

    private fun cached(force: Boolean): List<ModelInfo> {
        // 快路径：无锁读（@Volatile 保证可见性）。
        if (!force && cache.isNotEmpty() && System.currentTimeMillis() - cachedAt < TTL) return cache
        // 慢路径：**加锁串行化刷新**。否则并发请求会各自打满 4 路上游探测
        // （thundering herd），并且互相覆盖结果；加锁后同一时刻只有一次真实探测。
        return synchronized(this) {
            if (!force && cache.isNotEmpty() && System.currentTimeMillis() - cachedAt < TTL) {
                return@synchronized cache
            }
            val result = mutableListOf<ModelInfo>()
            val realmAccounts = Gateway.store.auths().groupBy { it.realm }
            val globalEnabled = Gateway.store.config.global.enabled
            val realms = if (globalEnabled) listOf("cn", "global") else listOf("cn")
            for (realm in realms) {
                val auth = realmAccounts[realm]?.firstOrNull() ?: continue
                runCatching {
                    val raw = runBlocking { Gateway.upstream.fetchModels(realm, auth) }
                    result += raw.mapNotNull { toModelInfo(it, realm) }
                }
            }
            if (result.isNotEmpty()) {
                cache = result
                cachedAt = System.currentTimeMillis()
            }
            if (result.isNotEmpty()) result else cache
        }
    }

    /**
     * 上游模型对象 → [ModelInfo]。
     *
     * **只映射上游 /v3/config 动态下发的字段**，逐字对齐 Go 端 dynModelEntry 的 json tag；
     * 上游没给的字段一律留空 / 0（未知），不做任何本地 / 预估兜底。
     *  - 顶层：id / name / descriptionZh / credits / tags / vendor / isDefault /
     *    maxInputTokens（上下文窗口）/ maxOutputTokens（最大输出）/ maxAllowedSize /
     *    disabled / supportsImages / supportsReasoning / supportsToolCall / onlyReasoning
     *  - 嵌套：reasoning.{ effort, summary, defaultEffort, canDisableThinking, supportedEfforts }
     */
    private fun toModelInfo(o: JsonObject, realm: String): ModelInfo? {
        fun s(vararg k: String): String {
            for (key in k) { val v = o[key]?.primText(); if (!v.isNullOrBlank()) return v }
            return ""
        }
        // 裸模型名：剥离上游可能附带 / 重复的 realm 前缀（"cn:" / "global:"），
        // 避免拼出 "cn:cn:xxx" 这类客户端无法识别、也无法匹配内置模型目录的 id。
        val model = s("id", "modelId", "model", "model_id", "name")
            .removePrefix("cn:").removePrefix("global:").trim()
        if (model.isBlank()) return null
        // 上游显式下架的模型不进列表（对齐 Go：disabled=true 整条剔除）。
        if (s("disabled").toBoolean()) return null
        val reasoning = o["reasoning"] as? JsonObject

        /** 先查嵌套 reasoning.*，再回落顶层同名键。 */
        fun rs(vararg keys: String): String {
            for (k in keys) {
                val v = (reasoning?.get(k) ?: o[k])?.primText()
                if (!v.isNullOrBlank()) return v
            }
            return ""
        }

        val credits = s("credits")
        val tags = runCatching {
            (o["tags"] as? JsonArray)?.mapNotNull { it.primText() }
        }.getOrNull() ?: emptyList()
        // 对齐上游 nonChatModel：嵌入 / 补全 / 代码专用模型（前缀 + 极小输出 + 生成类标签）
        // 选上去只会报 11102，直接从列表剔除。
        if (nonChatModel(model, s("maxOutputTokens").toLongOrNull() ?: 0L, tags)) return null
        val efforts = runCatching {
            ((reasoning?.get("supportedEfforts") ?: o["supportedEfforts"]) as? JsonArray)
                ?.mapNotNull { it.primText() }
        }.getOrNull() ?: emptyList()

        // 真实上下文 / 最大输出：**只取上游动态字段**，缺失即 0（未知），不做任何本地 / 预估兜底。
        val contextLength = s("maxInputTokens").toLongOrNull() ?: 0L
        val maxOutput = s("maxOutputTokens").toLongOrNull() ?: 0L

        return ModelInfo(
            // id 用**裸模型名**：客户端可据此匹配内置模型目录，自动带出上下文 / 思考能力；
            // 仅 global 域加 "global:" 前缀以免与 CN 同名模型冲突（chat 路由对裸名默认走 CN）。
            id = if (realm == "global") "global:$model" else model,
            name = s("name").ifBlank { model },
            description = s("descriptionZh"),
            credits = credits,
            tags = tags,
            vendor = s("vendor"),
            is_default = s("isDefault").toBoolean(),
            supports_images = s("supportsImages").toBoolean(),
            supports_reasoning = s("supportsReasoning").toBoolean(),
            can_disable_thinking = rs("canDisableThinking").toBoolean(),
            supports_tool_call = s("supportsToolCall").toBoolean(),
            only_reasoning = s("onlyReasoning").toBoolean(),
            max_allowed_size = s("maxAllowedSize").toLongOrNull() ?: 0,
            default_effort = rs("defaultEffort", "effort"),
            reasoning_summary = rs("summary"),
            supported_efforts = efforts,
            context_length = contextLength,
            max_output_tokens = maxOutput,
            realm = realm,
            model = model,
        )
    }

    /**
     * 上游模型 → OpenAI /v1/models 条目。
     *
     * **严格对齐上游 workbuddy2api-panel 的 /v1/models 输出**（internal/server/handler.go 的
     * modelList / applyModelInfoFields）：字段为 id/object/created/owned_by + context_length +
     * max_output_tokens + name/description/credits/tags/vendor + is_default/supports_images/
     * supports_reasoning/can_disable_thinking/supports_tool_call/only_reasoning/max_allowed_size +
     * reasoning_effort/reasoning_summary/reasoning_supported_efforts/reasoning_default_effort。
     *
     * 关键规则（与上游一致）：**布尔能力一律「true 才输出」**，绝不输出 false、也不附加自创的
     * architecture / capabilities / modalities 等字段——否则客户端会把「字段存在」误判为
     * 「支持该能力」（例如把纯文本模型判成支持图片）。上下文键名与上游一致：context_length。
     */
    private fun toOpenAi(m: ModelInfo): JsonElement = buildJsonObject {
        put("id", JsonPrimitive(m.id))
        put("object", JsonPrimitive("model"))
        put("created", JsonPrimitive(1753600000))
        put("owned_by", JsonPrimitive("workbuddy"))
        if (m.context_length > 0) put("context_length", JsonPrimitive(m.context_length))
        if (m.max_output_tokens > 0) put("max_output_tokens", JsonPrimitive(m.max_output_tokens))
        if (m.name.isNotBlank()) put("name", JsonPrimitive(m.name))
        val credPrefix = fmtCreditsPrefix(m.credits)
        if (credPrefix.isNotEmpty() || m.description.isNotBlank()) {
            put("description", JsonPrimitive((credPrefix + " " + m.description).trim()))
        }
        if (m.credits.isNotBlank()) put("credits", JsonPrimitive(m.credits))
        if (m.tags.isNotEmpty()) put("tags", buildJsonArray { m.tags.forEach { add(JsonPrimitive(it)) } })
        if (m.vendor.isNotBlank()) put("vendor", JsonPrimitive(m.vendor))
        if (m.is_default) put("is_default", JsonPrimitive(true))
        if (m.supports_images) put("supports_images", JsonPrimitive(true))
        if (m.supports_reasoning) {
            put("supports_reasoning", JsonPrimitive(true))
            if (m.can_disable_thinking) put("can_disable_thinking", JsonPrimitive(true))
        }
        if (m.supports_tool_call) put("supports_tool_call", JsonPrimitive(true))
        if (m.only_reasoning) put("only_reasoning", JsonPrimitive(true))
        if (m.max_allowed_size > 0) put("max_allowed_size", JsonPrimitive(m.max_allowed_size))
        if (m.default_effort.isNotBlank()) put("reasoning_effort", JsonPrimitive(m.default_effort))
        if (m.reasoning_summary.isNotBlank()) put("reasoning_summary", JsonPrimitive(m.reasoning_summary))
        if (m.supported_efforts.isNotEmpty()) {
            put("reasoning_supported_efforts", buildJsonArray { m.supported_efforts.forEach { add(JsonPrimitive(it)) } })
            if (m.default_effort.isNotBlank()) put("reasoning_default_effort", JsonPrimitive(m.default_effort))
        }
    }

    /** 上游 credits 原文（"x0.05 credits" / "x0.29" / "x0.00 credits" 等）→ "[x0.05 credit]"。 */
    private fun fmtCreditsPrefix(raw: String): String {
        val s = raw.trim().removeSuffix("credits").trim()
        return if (s.isEmpty()) "" else "[$s credit]"
    }

    /**
     * 对齐上游 nonChatModel：判定「非对话模型」（应从 /v1/models 剔除）。
     *  - id 前缀 nes- / completion- / codewise-（嵌入 / 补全 / 代码专用，选了报 11102）；
     *  - maxOutputTokens ≤ 256（tiny 输出）；
     *  - tags 含生成类标签（图片 / 视频生成走各自专用端点）。
     */
    private fun nonChatModel(id: String, maxOutput: Long, tags: List<String>): Boolean {
        val lid = id.lowercase().trim()
        if (lid.startsWith("nes-") || lid.startsWith("completion-") || lid.startsWith("codewise-")) return true
        if (maxOutput in 1..256) return true
        return tags.any {
            it == "text-to-image" || it == "image-to-image" || it == "text-to-video" || it == "image-to-video"
        }
    }
}

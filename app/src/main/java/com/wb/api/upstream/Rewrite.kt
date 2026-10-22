package com.wb.api.upstream

import com.wb.api.core.primText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * 出站请求体改写管线。
 *
 * 逐字对齐 Go：
 *  - internal/server/handler.go  → outbound `model` 重写为裸名（realm 前缀是网关内路由协议，上游不认）
 *  - internal/upstream/payload.go → stream / max_completion_tokens / stream_options / tool_choice /
 *    tool patterns / roles / image_url
 *  - internal/upstream/tool_pairing.go → 孤儿 tool_call↔tool 配对清理 + 结果块重排
 *  - internal/upstream/thinking.go → DeepSeek thinking 注入 / effort 降级 / reasoning_content 回填
 *  - internal/upstream/client.go → ensureConsoleSystem(global) / prompt_cache_key
 *
 * 内部统一用「可变的 Map/List + 原生标量」表示 JSON（对齐 Go 的 map[string]any 语义），
 * 进出各做一次转换。
 */
object Rewrite {

    private const val DEFAULT_DEEPSEEK_EFFORT = "high"

    /**
     * 渠道校验兜底 system 的正文（同新上游 `prompt.Degraded`）。
     *
     * 刻意极简中性：只承担「让 messages[0] 是 system」这一个结构职责，不注入任何
     * 人格 / 工具约定 / 项目规范 —— 那些属于客户端。改写用户语义会改变模型行为，
     * 而这个兜底的存在理由与内容无关。
     */
    private const val DEGRADED_SYSTEM =
        "You are a helpful assistant. Respond in the user's language, follow the user's instructions, and be direct and concise."

    private val effortRank = mapOf(
        "off" to 0, "minimal" to 1, "low" to 2, "medium" to 3, "high" to 4, "xhigh" to 5, "max" to 6,
    )

    /** 顶层指纹字段（sanitize 开关打开时剔除）。 */
    private val fingerprintKeys = setOf(
        "user", "metadata", "store", "safety_identifier", "system_fingerprint",
        "x_device_token", "device_id",
    )

    /**
     * 完整管线。`bareModel` 必须是剥离 realm 前缀后的裸模型名。
     * `efforts`/`defaultEfforts` 来自 /v3/config 缓存（空表示未知 → 不做档位降级，同 Go）。
     */
    fun prepare(
        input: JsonObject,
        sanitize: Boolean,
        bareModel: String,
        efforts: Map<String, List<String>> = emptyMap(),
        defaultEfforts: Map<String, String> = emptyMap(),
        maxOutput: Long = 0L,
    ): JsonObject {
        val obj = input.toPlainObject()

        // 1) outbound model 重写为裸名 —— 上游只认裸模型名（否则 400 code=11102）。
        obj["model"] = bareModel
        // 2) 强制 stream:true（上游拒绝非流式）。
        obj["stream"] = true
        // 3) max_completion_tokens → max_tokens。
        translateMaxCompletionTokens(obj)
        // 3.1) 客户端没给输出上限时，用模型元数据兜底：
        //      不是所有客户端都会带上限，缺省时上游行为不稳定（可能被截断或被拒），
        //      显式补一个「该模型声明的最大输出」更可预期。仅在客户端未指定时补，不覆盖用户值。
        if (maxOutput > 0 && !obj.containsKey("max_tokens") && !obj.containsKey("max_completion_tokens")) {
            obj["max_tokens"] = maxOutput
        }
        // 4) stream_options 缺省补 include_usage。
        if (!obj.containsKey("stream_options")) {
            obj["stream_options"] = mutableMapOf<String, Any?>("include_usage" to true)
        }
        normalizeToolChoice(obj)
        normalizeToolPatterns(obj)
        normalizeRoles(obj)
        normalizeImageURL(obj)
        // 新上游 payload.go：`stop` 传字符串时上游要求数组形态；content parts 里的
        // 思考片段（reasoning_text / reasoning）上游不认，留着会 400。
        normalizeStop(obj)
        dropReasoningContentParts(obj)

        // 5) 工具配对：先重排再清理（不完整配对会让上游对之后每条消息都返 400）。
        @Suppress("UNCHECKED_CAST")
        val msgs0 = obj["messages"] as? MutableList<Any?>
        if (msgs0 != null) {
            val repacked = repackToolResultBlocks(msgs0)
            val cleaned = cleanupOrphanToolCalls(repacked)
            obj["messages"] = cleaned
        }

        // 6) DeepSeek 思维链：注入 enabled + 默认档 → 档位降级 → reasoning_content 回填。
        val modelName = obj["model"] as? String ?: ""
        injectThinking(obj, defaultEfforts[modelName] ?: "")
        normalizeReasoningEffort(obj, efforts)
        backfillReasoningContent(obj)

        // 7) 指纹脱敏（开关）。
        if (sanitize) {
            for (k in fingerprintKeys) obj.remove(k)
        }

        return obj.toJsonObject()
    }

    /**
     * 保证 `messages[0]` 是 `role=system` —— 上游**渠道校验的硬要求**。
     *
     * 2026-10-20 真机实测（国际版 5.8.4）三条对照：
     * ```
     * 首条 user       → 400 11-128 "first message is not system prompt"
     * 首条 developer  → 400 11-128 "Illegal API invocation from an unapproved channel"
     * 首条 system     → 200（同一 body 其余部分不变）
     * ```
     * 也就是说这不是内容策略、不是账号问题：**首条角色本身就是渠道校验的一部分**。
     * 大量 SDK / 手搓 curl / 探针都不带 system（本项目的模型自检就是
     * `messages:[{role:user,content:"hi"}]`），于是每一次请求都必然被拦。
     *
     * 语义边界（对齐新上游 `prompt.EnsureSystemFirst`）：
     *  - 首条已是 system → 零改动；
     *  - 首条是 developer → **就地改成 system**（内容逐字保留：上游 role 白名单不含
     *    developer，另插一条 developer 只会多制造一次必然归一）；
     *  - 其余（首条 user/assistant/tool）→ 最前补一条中性 system，其它消息逐字不动。
     *
     * 提示词用中性极简（同新上游 `prompt.Degraded`）：只为了让请求通过渠道校验，
     * 不改变用户指令的语义，也不替客户端做人格设定。
     */
    fun ensureSystemFirst(input: JsonObject): JsonObject {
        val obj = input.toPlainObject()
        @Suppress("UNCHECKED_CAST")
        val msgs = obj["messages"] as? MutableList<Any?> ?: return input
        if (msgs.isEmpty()) return input
        @Suppress("UNCHECKED_CAST")
        val first = msgs[0] as? MutableMap<Any?, Any?> ?: return input
        val role = (first["role"] as? String)?.trim()?.lowercase()
        if (role == "system") return input
        if (role == "developer") {
            first["role"] = "system"
            return obj.toJsonObject()
        }
        val injected = mutableListOf<Any?>(
            mutableMapOf<String, Any?>("role" to "system", "content" to DEGRADED_SYSTEM),
        )
        injected.addAll(msgs)
        obj["messages"] = injected
        return obj.toJsonObject()
    }

    /**
     * 最终序列化：`messages[0]` 兜底成 system（渠道校验）+ 确定 `prompt_cache_key`
     * （费用与延迟优化）。
     *
     * 缓存键的两条规则（对齐官方 Prompt Caching 语义）：
     *  1. 客户端**自带** `prompt_cache_key` 时原样沿用——多租户 / 多工作流隔离是客户端的职责，
     *     网关擅自改写会把本应分开的流量并到同一个路由桶里，反而降低命中率；
     *  2. 否则派生一个**跨轮稳定**的键（账号 + 稳定前缀）。键必须跨轮稳定，否则第二轮起
     *     就是一条全新的路由，前缀缓存永远打不中——原实现把整段 system+user 历史都算进键里，
     *     每轮都变，这正是「缓存不生效」的直接原因（同时它也让会话粘性每轮失效）。
     */
    fun finalize(prepared: JsonObject, uid: String, conversationId: String?, turnId: String = ""): String {
        val obj = ensureSystemFirst(prepared).toPlainObject()
        val existing = (obj["prompt_cache_key"] as? String)?.trim().orEmpty()
        if (existing.isEmpty() && uid.isNotBlank()) {
            // 与 X-Conversation-ID 同源、同形态（UUID v4）：该键是上游**前缀缓存槽**的来源，
            // 官方两者本来就对应同一个会话。旧实现是 "wb2a-"+12 hex —— 自带项目缩写、
            // 长度也与官方不同，是可直接判别的指纹（上游 issue #51）。
            obj["prompt_cache_key"] = Up.uuidFor(uid, conversationId, "")
        }
        // body 侧会话轮级字段（上游 conv_fields.go / issue #51）：头里写了
        // X-Conversation-Request-ID，体里一条都没有，本身就是「头体不一致」的形态。
        if (turnId.isNotBlank()) injectConversationFields(obj, turnId)
        return com.wb.api.core.AppJson.encodeToString(JsonElement.serializer(), obj.toJsonObject())
    }

    /**
     * body 侧会话轮级字段（对齐新上游 `conv_fields.go` / issue #51）。
     *
     * 官方桌面端把「这一轮属于哪个 request-id」同时写在**头**和**体**里：
     * ```
     * messages[i] = { role:"user", content:[...], agent:"cli",
     *                 conversationRequestId:"<该轮 X-Conversation-Request-ID>",
     *                 startsNewUserRequest:true }
     * ```
     *
     * 边界（不伪造）：
     *  - 只给**当前轮**（最后一条 user 消息）写；历史消息的轮级 ID 客户端没给，
     *    OpenAI 协议里也没有这个字段，网关无从得知 —— 对不上的 ID 比缺失更容易被
     *    关联分析抓出来，所以不编造；
     *  - 客户端已自带 `conversationRequestId` → 原值优先，不覆盖。
     */
    private fun injectConversationFields(obj: MutableMap<String, Any?>, turnId: String) {
        @Suppress("UNCHECKED_CAST")
        val msgs = obj["messages"] as? MutableList<Any?> ?: return
        for (i in msgs.indices.reversed()) {
            @Suppress("UNCHECKED_CAST")
            val m = msgs[i] as? MutableMap<Any?, Any?> ?: continue
            if (!(m["role"] as? String).equals("user", ignoreCase = true)) continue
            if ((m["conversationRequestId"] as? String)?.isNotBlank() == true) return
            m["conversationRequestId"] = turnId
            m["startsNewUserRequest"] = true
            if ((m["agent"] as? String).isNullOrBlank()) m["agent"] = "cli"
            return
        }
    }

    // ======================= 内部实现 =======================

    private fun JsonObject.toPlainObject(): MutableMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        for ((k, v) in this) m[k] = v.toPlain()
        return m
    }

    private fun JsonElement.toPlain(): Any? = when (this) {
        is JsonObject -> LinkedHashMap<String, Any?>().also { m -> for ((k, v) in this) m[k] = v.toPlain() }
        is JsonArray -> ArrayList<Any?>(size).also { l -> for (e in this) l.add(e.toPlain()) }
        is JsonNull -> null
        is JsonPrimitive -> if (isString) content
        else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    }

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
        is List<*> -> JsonArray(map { it.toJsonElement() })
        is Boolean -> JsonPrimitive(this)
        is Int -> JsonPrimitive(this)
        is Long -> JsonPrimitive(this)
        is Double -> JsonPrimitive(this)
        is Float -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        else -> JsonPrimitive(this.toString())
    }

    private fun MutableMap<String, Any?>.toJsonObject(): JsonObject =
        JsonObject(entries.associate { (k, v) -> k to v.toJsonElement() })

    @Suppress("UNCHECKED_CAST")
    private fun asStringMap(v: Any?): MutableMap<String, Any?>? = v as? MutableMap<String, Any?>

    // ---- max_completion_tokens 翻译 ----

    private fun translateMaxCompletionTokens(obj: MutableMap<String, Any?>) {
        val hasAlias = obj.containsKey("max_completion_tokens")
        val alias = obj.remove("max_completion_tokens")
        if (!hasAlias) return
        if (obj.containsKey("max_tokens")) return
        val v = (alias as? Number)?.toDouble() ?: return
        if (v > 0 && v == Math.floor(v)) obj["max_tokens"] = v.toLong()
    }

    // ---- tool_choice 归一 ----

    private fun normalizeToolChoice(obj: MutableMap<String, Any?>) {
        if (!obj.containsKey("tool_choice")) return
        val tc = obj["tool_choice"]
        fun suppress() { obj.remove("tools"); obj.remove("functions") }
        when (tc) {
            is String -> if (tc.trim().equals("none", ignoreCase = true)) {
                obj.remove("tool_choice"); suppress()
            }
            is Map<*, *> -> {
                val typ = (tc["type"] as? String)?.trim()?.lowercase() ?: ""
                when (typ) {
                    "none" -> { obj.remove("tool_choice"); suppress() }
                    "auto", "required" -> obj["tool_choice"] = typ
                    "function" -> {
                        val fn = tc["function"] as? Map<*, *>
                        var name = (fn?.get("name") as? String)?.trim() ?: ""
                        if (name.isEmpty()) name = (tc["name"] as? String)?.trim() ?: ""
                        obj["tool_choice"] = if (name.isNotEmpty()) name else "auto"
                    }
                    else -> obj.remove("tool_choice")
                }
            }
            else -> obj.remove("tool_choice")
        }
    }

    // ---- tools pattern 归一（\_ → _） ----

    private fun normalizeToolPatterns(obj: MutableMap<String, Any?>) {
        val tools = obj["tools"] as? List<*> ?: return
        for (raw in tools) {
            val tool = raw as? MutableMap<*, *> ?: continue
            @Suppress("UNCHECKED_CAST")
            (tool as? MutableMap<String, Any?>)?.let { t ->
                unescapePatternLiteralEscapes(t["function"]?.let { (it as? MutableMap<String, Any?>)?.get("parameters") })
                unescapePatternLiteralEscapes(t["parameters"])
            }
        }
    }

    private fun unescapePatternLiteralEscapes(node: Any?) {
        when (node) {
            is MutableMap<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val m = node as MutableMap<String, Any?>
                val p = m["pattern"]
                if (p is String && p.contains("\\_")) m["pattern"] = p.replace("\\_", "_")
                val props = m["patternProperties"]
                if (props is MutableMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val pm = props as MutableMap<String, Any?>
                    if (pm.keys.any { it.contains("\\_") }) {
                        val fixed = LinkedHashMap<String, Any?>()
                        for ((k, v) in pm) fixed[k.replace("\\_", "_")] = v
                        m["patternProperties"] = fixed
                    }
                }
                for (v in m.values.toList()) unescapePatternLiteralEscapes(v)
            }
            is List<*> -> for (v in node) unescapePatternLiteralEscapes(v)
            else -> {}
        }
    }

    // ---- role 归一 ----

    private fun normalizeRoles(obj: MutableMap<String, Any?>) {
        val msgs = obj["messages"] as? List<*> ?: return
        for (m in msgs) {
            val msg = m as? MutableMap<*, *> ?: continue
            @Suppress("UNCHECKED_CAST")
            val mm = msg as MutableMap<String, Any?>
            val role = (mm["role"] as? String) ?: continue
            if (role.trim().equals("developer", ignoreCase = true)) mm["role"] = "system"
        }
    }

    // ---- image_url 归一（字符串 → 对象） ----

    private fun normalizeImageURL(obj: MutableMap<String, Any?>) {
        val msgs = obj["messages"] as? List<*> ?: return
        for (rawMsg in msgs) {
            @Suppress("UNCHECKED_CAST")
            val msg = rawMsg as? MutableMap<String, Any?> ?: continue
            val parts = msg["content"] as? List<*> ?: continue
            for (rawPart in parts) {
                @Suppress("UNCHECKED_CAST")
                val part = rawPart as? MutableMap<String, Any?> ?: continue
                if (part["type"] != "image_url") continue
                val url = part["image_url"]
                if (url is String && url.isNotEmpty()) {
                    part["image_url"] = mutableMapOf<String, Any?>("url" to url)
                }
            }
        }
    }

    // ---- 工具配对：结果块重排 ----

    /**
     * `stop` 由字符串归一成数组（同一语义，规避上游只接受数组的校验）。
     * 对齐新上游 `payload.go:normalizeStop`。
     */
    private fun normalizeStop(obj: MutableMap<String, Any?>) {
        val s = obj["stop"] as? String ?: return
        obj["stop"] = listOf(s)
    }

    /**
     * 删除 content 数组里的思考片段（`reasoning_text` / `reasoning`）。
     *
     * 部分客户端把思维链塞进 content parts，上游不认这类 part → 400；整条只剩思考片段、
     * 又没有 tool_calls 的消息一并删除（否则上游会为空 content 报错）。
     * 对齐新上游 `payload.go:dropReasoningContentParts`。
     */
    private fun dropReasoningContentParts(obj: MutableMap<String, Any?>) {
        val msgs = obj["messages"] as? MutableList<Any?> ?: return
        val out = ArrayList<Any?>(msgs.size)
        for (raw in msgs) {
            val msg = raw as? MutableMap<String, Any?>
            val parts = msg?.get("content") as? List<*>
            if (msg == null || parts == null) {
                out.add(raw) // 字符串 content（或字段缺失）不受影响
                continue
            }
            val kept = parts.filterNot { p ->
                val t = ((p as? Map<*, *>)?.get("type") as? String)?.trim()
                t == "reasoning_text" || t == "reasoning"
            }
            if (kept.isEmpty() && !msg.containsKey("tool_calls")) continue
            msg["content"] = kept
            out.add(msg)
        }
        obj["messages"] = out
    }

    private fun repackToolResultBlocks(messages: MutableList<Any?>): MutableList<Any?> {
        if (messages.size < 3) return messages
        val out = ArrayList<Any?>(messages.size)
        var changed = false
        var i = 0
        while (i < messages.size) {
            @Suppress("UNCHECKED_CAST")
            val m = messages[i] as? MutableMap<String, Any?>
            val tcs = m?.get("tool_calls") as? List<*>
            if (m == null || m["role"] != "assistant" || tcs == null || tcs.isEmpty()) {
                out.add(messages[i]); i++; continue
            }
            val want = HashSet<String>()
            for (tci in tcs) {
                val id = (tci as? Map<*, *>)?.get("id") as? String
                if (!id.isNullOrEmpty()) want.add(id)
            }
            out.add(messages[i]); i++
            val results = ArrayList<Any?>()
            val between = ArrayList<Any?>()
            var sawNonTool = false
            while (i < messages.size) {
                val mm = messages[i] as? Map<*, *> ?: break
                val role = mm["role"] as? String
                if (role == "tool") {
                    val id = mm["tool_call_id"] as? String
                    if (id == null || !want.contains(id)) break
                    results.add(messages[i])
                    if (sawNonTool) changed = true
                    i++
                    continue
                }
                if (results.isEmpty()) break
                if (role == "assistant") {
                    val next = mm["tool_calls"] as? List<*>
                    if (next != null && next.isNotEmpty()) break
                }
                between.add(messages[i]); sawNonTool = true; i++
            }
            out.addAll(results)
            out.addAll(between)
        }
        return if (!changed) messages else out
    }

    // ---- 工具配对：孤儿清理 ----

    private fun cleanupOrphanToolCalls(messages: MutableList<Any?>): MutableList<Any?> {
        if (messages.isEmpty()) return messages
        val callIDs = HashSet<String>()
        val resultIDs = HashSet<String>()
        // 缺功能名的调用要**连同结果成对剔除**（见 toolCallNameMissing）。
        val nameBadIDs = HashSet<String>()
        var hasTraffic = false
        for (m in messages) {
            val msg = m as? Map<*, *> ?: continue
            when (msg["role"]) {
                "tool" -> {
                    val id = msg["tool_call_id"] as? String
                    if (!id.isNullOrEmpty()) { resultIDs.add(id); hasTraffic = true }
                }
                "assistant" -> {
                    val tcs = msg["tool_calls"] as? List<*> ?: continue
                    for (tci in tcs) {
                        val tc = tci as? Map<*, *>
                        val id = tc?.get("id") as? String
                        if (!id.isNullOrEmpty()) {
                            if (toolCallNameMissing(tc)) nameBadIDs.add(id)
                            callIDs.add(id); hasTraffic = true
                        }
                    }
                }
            }
        }
        if (!hasTraffic) return messages
        val keepCalls = callIDs.filter { resultIDs.contains(it) && !nameBadIDs.contains(it) }.toHashSet()
        var changed = false
        // 1) assistant.tool_calls 按 keepCalls 对称裁剪
        for (m in messages) {
            @Suppress("UNCHECKED_CAST")
            val msg = m as? MutableMap<String, Any?> ?: continue
            if (msg["role"] != "assistant") continue
            val tcs = msg["tool_calls"] as? List<*> ?: continue
            if (tcs.isEmpty()) continue
            val kept = tcs.filter { tc -> ((tc as? Map<*, *>)?.get("id") as? String)?.let { keepCalls.contains(it) } == true }
            if (kept.size == tcs.size) continue
            changed = true
            if (kept.isEmpty()) msg.remove("tool_calls") else msg["tool_calls"] = kept
        }
        // 2) role:tool 结果：孤儿整条删除
        val keptMsgs = ArrayList<Any?>(messages.size)
        for (m in messages) {
            val msg = m as? Map<*, *>
            if (msg != null && msg["role"] == "tool") {
                val id = msg["tool_call_id"] as? String
                if (id == null || !keepCalls.contains(id)) { changed = true; continue }
            }
            keptMsgs.add(m)
        }
        return if (!changed) messages else keptMsgs
    }

    /**
     * tool_calls 单项是否缺少可用功能名（function 缺失 / name 缺失 / name 空白）。
     *
     * 这类条目上游直接判 **11133**（`tool_calls[i].function.name must be a non-empty string`），
     * **带不带配对结果都拒** —— 是线上「整条会话每轮 400、只能手动清空历史」的成因。
     * 对齐新上游 `tool_pairing.go:toolCallNameMissing`。
     */
    private fun toolCallNameMissing(tc: Map<*, *>?): Boolean {
        val fn = tc?.get("function") as? Map<*, *> ?: return true
        val name = fn["name"] as? String ?: return true
        return name.isBlank()
    }

    // ---- DeepSeek thinking ----

    private fun isDeepSeekModel(model: String): Boolean =
        model.trim().lowercase().startsWith("deepseek")

    /**
     * 「预算被思考吃光」的预防阈值（对齐新上游 `starved.go` 的实测数据）。
     *
     * 上游把 reasoning 与正文字节算在**同一份** max_tokens 里，思考没有独立上限：
     * ```
     * max_tokens=60/100/150/200/300 → thinking 恰等于预算，正文 0 字节
     * max_tokens=400                → 思考 166~272，正文开始出现（时好时坏）
     * thinking:{type:disabled} 同预算 → 正文正常、finish=stop
     * ```
     * 客户端给这种额度时，思考会独吞整份预算 → 空回复（finish_reason=length）。
     * Agent 循环尤其致命：客户端认为回合正常结束，任务静默停住。
     *
     * 上游的做法是「事后同额度关思考重发一次」；本端改为**事前不注入思考** ——
     * 结果等价（思考本来也拿不到），但覆盖流式与非流式、且不额外消耗一次上游请求。
     * 客户端**显式**声明 thinking / reasoning_effort 时不干预（尊重其语义）。
     */
    private const val STARVED_BUDGET_LOW = 16L
    private const val STARVED_BUDGET_HIGH = 400L

    /** 读输出限额的三个别名（max_completion_tokens 已在前面翻译成 max_tokens）。未携带 → 0。 */
    private fun outputBudget(obj: MutableMap<String, Any?>): Long {
        for (k in listOf("max_tokens", "max_completion_tokens", "max_output_tokens")) {
            val v = when (val raw = obj[k]) {
                is Number -> raw.toLong()
                is String -> raw.trim().toLongOrNull()
                else -> null
            } ?: continue
            if (v > 0) return v
        }
        return 0L
    }

    private fun injectThinking(obj: MutableMap<String, Any?>, defaultEffort: String) {
        val model = obj["model"] as? String ?: return
        if (!isDeepSeekModel(model)) return
        val th = asStringMap(obj["thinking"])
        val typ = (th?.get("type") as? String)?.trim() ?: ""
        if (typ.isEmpty()) {
            // 客户端没声明思考：小额预算下思考必然吃光正文 → 不注入（见上面的实测表）。
            // 16 以下不拦：官方客户端用 max_tokens=1 做 key 探活，那种请求按原样即可。
            val budget = outputBudget(obj)
            if (budget in STARVED_BUDGET_LOW until STARVED_BUDGET_HIGH) return
        }
        if (typ.isNotEmpty()) {
            if (typ.equals("disabled", ignoreCase = true)) {
                obj.remove("reasoning_effort"); obj.remove("reasoningEffort"); return
            }
            ensureDeepSeekEffort(obj, defaultEffort); return
        }
        if (th == null) obj["thinking"] = mutableMapOf<String, Any?>("type" to "enabled")
        else th["type"] = "enabled"
        ensureDeepSeekEffort(obj, defaultEffort)
    }

    private fun ensureDeepSeekEffort(obj: MutableMap<String, Any?>, defaultEffort: String) {
        if (obj.containsKey("reasoning_effort") || obj.containsKey("reasoningEffort")) return
        obj["reasoning_effort"] = defaultEffort.ifEmpty { DEFAULT_DEEPSEEK_EFFORT }
    }

    private fun normalizeReasoningEffort(obj: MutableMap<String, Any?>, efforts: Map<String, List<String>>) {
        if (efforts.isEmpty()) return
        val model = obj["model"] as? String ?: return
        if (model.isEmpty()) return
        val supported = efforts[model] ?: return
        if (supported.isEmpty()) return
        val key = when {
            obj.containsKey("reasoning_effort") -> "reasoning_effort"
            obj.containsKey("reasoningEffort") -> "reasoningEffort"
            else -> return
        }
        val reqStr = (obj[key] as? String)?.trim()?.lowercase() ?: return
        val reqIdx = effortRank[reqStr] ?: return
        var best = ""; var bestIdx = -1
        for (s in supported) {
            val idx = effortRank[s.trim().lowercase()] ?: continue
            if (idx <= reqIdx && idx > bestIdx) { best = s; bestIdx = idx }
        }
        if (best.isEmpty()) {
            var lowIdx = Int.MAX_VALUE; var low = ""
            for (s in supported) {
                val idx = effortRank[s.trim().lowercase()] ?: continue
                if (idx < lowIdx) { lowIdx = idx; low = s }
            }
            best = low
        }
        if (best.isNotEmpty() && best != obj[key]) obj[key] = best
    }

    private fun backfillReasoningContent(obj: MutableMap<String, Any?>) {
        val model = obj["model"] as? String ?: return
        if (!isDeepSeekModel(model)) return
        val msgs = obj["messages"] as? List<*> ?: return
        if (msgs.isEmpty()) return
        val thinkingEnabled = (asStringMap(obj["thinking"])?.get("type") as? String)
            ?.trim()?.equals("enabled", ignoreCase = true) == true
        var hasTrace = false
        for (mm in msgs) {
            val msg = mm as? Map<*, *> ?: continue
            val r = msg["reasoning"] as? String
            if (r != null && r.isNotEmpty()) { hasTrace = true; break }
            if (msg.containsKey("reasoning_content")) { hasTrace = true; break }
        }
        if (!thinkingEnabled && !hasTrace) return
        for (mm in msgs) {
            @Suppress("UNCHECKED_CAST")
            val msg = mm as? MutableMap<String, Any?> ?: continue
            if (msg["role"] != "assistant") continue
            var rc = msg["reasoning_content"] as? String
            if (rc == null) {
                rc = msg["reasoning"] as? String ?: ""
                msg["reasoning_content"] = rc
            }
            val r = msg["reasoning"]
            if (r !is String || r.isEmpty()) {
                msg["reasoning"] = if (rc.isNotEmpty()) rc else " "
            }
        }
    }

    // ---- 会话键派生 ----

    /**
     * 派生「稳定会话前缀」：**只取 system 消息 + 第一条 user 消息**。
     *
     * 这个键同时用于两件必须跨轮稳定的东西：
     *  1. 会话粘性（同一会话固定同一账号）——原实现把每一轮的 user 消息都拼进去，
     *     于是第二轮开始键就变了，粘性实际从未生效，账号每轮都在换；
     *  2. 前缀缓存路由（prompt_cache_key）——键变了就是新路由，缓存永远打不中。
     *
     * 只取「前缀」是刻意的：缓存命中本来就依赖**公共前缀**（system + 首个用户提问），
     * 后续轮次追加的内容越多，键越不稳定，命中率越低。
     */
    fun deriveSessionKey(body: JsonObject): String? {
        val msgs = body["messages"] as? JsonArray ?: return null
        val sb = StringBuilder()
        var firstUserTaken = false
        for (m in msgs) {
            val obj = runCatching { m.jsonObject }.getOrNull() ?: continue
            val role = obj["role"]?.primText() ?: continue
            when (role) {
                "system" -> sb.append("system:").append(textOf(obj["content"])).append('|')
                "user" -> if (!firstUserTaken) {
                    firstUserTaken = true
                    sb.append("user:").append(textOf(obj["content"])).append('|')
                }
            }
            if (sb.length > 4000) break
        }
        if (sb.isEmpty()) return null
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray())
        return "d-" + d.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun textOf(el: JsonElement?): String = when (el) {
        null -> ""
        is JsonPrimitive -> el.content
        is JsonArray -> el.joinToString("") { textOf(it) }
        is JsonObject -> el["text"]?.primText() ?: ""
    }
}

package com.wb.api.server

import com.wb.api.Gateway
import com.wb.api.core.AppJson
import com.wb.api.core.objOrNull
import com.wb.api.core.Mask
import com.wb.api.core.primText
import com.wb.api.core.primTextOrNull
import com.wb.api.core.Ids
import com.wb.api.core.LogBus
import com.wb.api.model.AuthEntry
import com.wb.api.model.UsageRecord
import com.wb.api.upstream.Classify
import com.wb.api.upstream.ErrKind
import com.wb.api.upstream.Rewrite
import com.wb.api.upstream.UpstreamException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * OpenAI 兼容入口：`/v1/chat/completions` 与 `/v1/responses`。
 *
 * 两个端点共用同一条管线（选号 → 改写 → 上游 SSE → 回写/聚合 → 记账）。
 * Responses 端点由 [Responses] 做**双向**协议适配（官方规格见
 * https://platform.openai.com/docs/guides/migrate-to-responses）：
 *  - 请求侧：扁平 tools → 嵌套 tools；function_call / function_call_output items → assistant.tool_calls / role:tool；
 *    reasoning.effort → 顶层 reasoning_effort；text.format → response_format。
 *  - 响应侧：tool_calls → function_call items；reasoning_content → reasoning item；
 *    流式额外产出 response.function_call_arguments.delta 等事件（否则客户端只会看到空回复）。
 */
object ChatHandler {
    private const val MAX_ROTATE = 3

    /**
     * 请求级**确定性**错误：同一 body 换任何账号结果都不变，立即透传上游原文并终止轮转
     * （对齐 Go fail-fast：仅 ErrBadParams / ErrPromptTooLong / ErrImageInvalid / ErrContentBlocked）。
     *
     * **不含 ErrKind.CLIENT**：未知 4xx 可能只是「这一个号」的偶发问题，上游对它走
     * applyErrorPolicy 的 default 分支（连败降权）并**继续轮转**，换号后常常就成功；
     * 直接 fail-fast 会白白放弃池里其它健康账号。
     */
    private val failFastKinds = setOf(
        ErrKind.BAD_PARAMS, ErrKind.CONTENT_BLOCKED,
        ErrKind.PROMPT_TOO_LONG, ErrKind.IMAGE_INVALID,
    )

    private val frameWhitelist = setOf(
        "id", "object", "created", "model", "system_fingerprint", "service_tier", "choices", "usage",
    )

    /**
     * 一跳的结果（带上 token 用量，供 usage 落盘与账号速率统计）。
     *
     * @param sent 是否**已向客户端发送过任何数据**（响应头 / 字节）。SSE 一旦发出字节就无法
     *   再改状态码，因此只有 sent=false 的失败才允许换号重试（对客户端无痕）。
     */
    /**
     * 取上游响应的字节流。`okhttp3.Response.body` 是**可空**的：正常网络响应几乎总有 body，
     * 但「2xx 且无 body」在极端/异常路径下可能出现 —— 原实现四处用 `resp.body!!`，一旦落空
     * 就是 NPE，冒到 HttpServer 兜底 catch 变成 500（错误信息还不可读）。
     * 这里统一收敛成一个可分类的上游异常（等价「上游异常」，可被上层按服务器错误处置）。
     */
    private fun bodyStreamOf(resp: okhttp3.Response): java.io.InputStream =
        resp.body?.byteStream()
            ?: throw UpstreamException(ErrKind.SERVER, resp.code, "上游返回 2xx 但响应体为空")

    internal data class Hop(
        val ok: Boolean,
        val promptTokens: Long = 0,
        val completionTokens: Long = 0,
        val totalTokens: Long = 0,
        val sent: Boolean = false,
    )

    // ============================================================
    // 入口
    // ============================================================

    fun handle(req: Req, res: Res) {
        val bodyObj = parseBody(req, res) ?: return
        val stream = streamFlag(bodyObj)
        runPipeline(req, res, bodyObj, stream) { r, resp, auth, model, realm, rq, reqId, start ->
            if (stream) forwardStream(r, resp, auth, model, realm, rq, reqId, start)
            else aggregate(r, resp, auth, model, realm, rq, reqId, start)
        }
    }

    fun handleResponses(req: Req, res: Res) {
        val raw = parseBody(req, res) ?: return
        val conv = Responses.toChatRequest(raw)
        // 本网关无法支持的请求形态（如 background:true、需要服务端任务存储）→ 明确 400，
        // 不静默降级成「看起来在跑其实什么都没做」。
        conv.reject?.let { res.sendError(400, it, "invalid_request_error"); return }
        val clientModel = conv.chat["model"]?.primText() ?: ""
        if (clientModel.isBlank()) {
            res.sendError(400, "missing required parameter: 'model'", "invalid_request_error")
            return
        }
        val inputEmpty = when (val i = raw["input"]) {
            null -> true
            is JsonPrimitive -> i.content.isBlank()
            is JsonArray -> i.isEmpty()
            else -> true
        }
        if (inputEmpty) {
            res.sendError(
                400,
                "this gateway is stateless: please send the full conversation in `input`. " +
                    "`previous_response_id` is accepted for session stickiness but conversation state is not stored.",
                "invalid_request_error",
            )
            return
        }
        val stream = streamFlag(raw)
        // 指纹脱敏开着时，被丢弃的字段**不能回显**——否则等于告诉客户端「参数已被接受」，
        // 与实际上游根本没收到这件事矛盾。
        val sanitize = Gateway.store.config.features.sanitize_blacklist_fingerprints
        val echo = if (sanitize) {
            JsonObject(conv.echo.filterKeys { it !in Responses.SANITIZED_ECHO })
        } else conv.echo
        runPipeline(req, res, conv.chat, stream) { r, resp, auth, model, realm, rq, reqId, start ->
            if (stream) Responses.forwardStream(r, resp, auth.uid, clientModel, realm, model, reqId, start, conv.effort, echo)
            else Responses.aggregate(r, resp, auth.uid, clientModel, realm, model, reqId, start, rq, conv.effort, echo)
        }
    }

    // ============================================================
    // 公共管线
    // ============================================================

    private fun parseBody(req: Req, res: Res): JsonObject? {
        val obj = runCatching { AppJson.parseToJsonElement(String(req.body)).jsonObject }.getOrNull()
        if (obj == null) res.sendError(400, "invalid JSON body", "invalid_request_error")
        return obj
    }

    private fun streamFlag(body: JsonObject): Boolean =
        body["stream"]?.primText()?.toBoolean() ?: false

    private fun runPipeline(
        req: Req,
        res: Res,
        chatBody: JsonObject,
        stream: Boolean,
        responder: (Res, okhttp3.Response, AuthEntry, String, String, Req, String, Long) -> Hop,
    ) {
        val modelRaw = chatBody["model"]?.primText() ?: ""
        val (realm, model) = resolveModel(modelRaw)
        val cfg = Gateway.store.config

        val prepared = Rewrite.prepare(
            chatBody,
            cfg.features.sanitize_blacklist_fingerprints,
            model,
            ModelService.supportedEfforts(realm, model),
            ModelService.defaultEfforts(realm, model),
            ModelService.maxOutputTokens(realm, model),
        )

        val conversationId = chatBody["conversation_id"]?.primText()
        val sessionKey = conversationId ?: Rewrite.deriveSessionKey(chatBody)

        val reqId = Ids.requestId()
        val start = System.currentTimeMillis()
        Gateway.noteRequestStart()

        var lastKind = ErrKind.NONE
        var lastStatus = 503
        var lastBody = ""
        var hop = Hop(ok = false)
        val tried = HashSet<String>()
        // 实际**服务成功**的那个账号。`tried` 是 HashSet（无插入序），用它取 `lastOrNull()`
        // 得到的是「哈希序最后一个」，会把用量记账到被淘汰的账号上（代码审查 P0-1）。
        var servedUid = ""
        // 选号返回 null 的两种成因要分开报：这里记下「是否因选不到号而终止」，
        // 供 mapError 判定是不是「号都在、只是都对这个模型冷却」（上游 issue #102）。
        var selectionFailed = false

        try {
            var attempt = 0
            while (attempt < MAX_ROTATE) {
                val auth = pickAccount(realm, model, sessionKey, tried)
                if (auth == null) {
                    selectionFailed = true
                    break
                }
                tried += auth.uid
                Gateway.pool.acquire(auth.uid)
                try {
                    val outbound = Rewrite.finalize(prepared, realm == "global", auth.uid, sessionKey)
                    if (attempt == 0) LogBus.log(outboundSummary(prepared, realm, model, auth.uid, stream))
                    val resp = try {
                        runBlocking { Gateway.upstream.chat(auth, outbound, req.clientIp) }
                    } catch (e: Throwable) {
                        val why = e.message ?: e.javaClass.simpleName
                        lastKind = ErrKind.SERVER; lastStatus = 502
                        lastBody = "upstream transport error: $why"
                        // 传输层失败只换号、不喂熔断（对齐 Go 网络抖动分支）：改喂连败降权，
                        // 否则上游网络一抖就会把健康账号熔断。
                        Gateway.pool.onTransportFailure(auth.uid, model, why)
                        Gateway.pool.stickyUnbind(sessionKey ?: reqId)
                        LogBus.log("$realm|$model 出站传输异常 uid=${auth.uid.take(8)}: $why -> 换号")
                        null
                    }
                    if (resp != null && resp.isSuccessful) {
                        hop = responder(res, resp, auth, model, realm, req, reqId, start)
                        if (hop.ok) {
                            servedUid = auth.uid
                            Gateway.pool.clearModelBlock(auth.uid, model)
                            Gateway.pool.stickyBind(sessionKey ?: reqId, auth.uid)
                            return
                        }
                        // 失败但**尚未向客户端发送任何数据**（首帧前上游断流 / 首帧即 error 帧）：
                        // 这次尝试对客户端无痕，换号重试（SSE 发过字节后无法改状态码，故仅 sent=false 生效）。
                        if (hop.sent) {
                            Gateway.pool.stickyUnbind(sessionKey ?: reqId)
                            return
                        }
                        LogBus.log("$realm|$model uid=${auth.uid.take(8)} 首帧前失败 -> 换号重试")
                        Gateway.pool.stickyUnbind(sessionKey ?: reqId)
                        attempt++
                        if (attempt < MAX_ROTATE) Thread.sleep(backoff(attempt))
                        continue
                    }
                    if (resp != null) {
                        val text = resp.body?.string().orEmpty()
                        val retryAfter = resp.header("Retry-After")?.trim()?.toLongOrNull() ?: 0L
                        val kind = Classify.of(resp.code, text)
                        lastKind = kind; lastStatus = resp.code; lastBody = text
                        Gateway.pool.onError(auth.uid, kind, resp.code, model, text, retryAfter)
                        Gateway.pool.stickyUnbind(sessionKey ?: reqId)
                        LogBus.log("$realm|$model rotate uid=${auth.uid.take(8)} kind=$kind status=${resp.code} body=${text.take(200).replace('\n', ' ')}")
                        if (kind in failFastKinds) { mapError(res, kind, lastStatus, lastBody, model, false); return }
                    }
                } finally {
                    Gateway.pool.release(auth.uid)
                }
                attempt++
                if (attempt < MAX_ROTATE) Thread.sleep(backoff(attempt))
            }
            mapError(res, lastKind, lastStatus, lastBody, model, selectionFailed)
        } finally {
            val dur = System.currentTimeMillis() - start
            // 第二参 httpOk：HTTP 层面是否把一次成功响应交给了客户端 —— 正常完成（hop.ok）
            // 或已向客户端发出 2xx 流（hop.sent）都算。原实现硬编码 true，使 http_success
            // 恒等于 completed、该指标失去意义。
            Gateway.noteRequestEnd(hop.ok, hop.ok || hop.sent, dur)
            if (hop.ok) {
                Gateway.store.appendUsage(UsageRecord(
                    t = System.currentTimeMillis() / 1000,
                    account = servedUid,
                    model = model,
                    realm = realm,
                    prompt_tokens = hop.promptTokens,
                    completion_tokens = hop.completionTokens,
                    total_tokens = hop.totalTokens,
                    latency_ms = dur,
                    error = false,
                ))
            }
            // 归档时间用**设备本地时区**的 ISO-8601（带 UTC 偏移，例：中国 UTC+8 → 2026-10-06T16:06:14.123+08:00）。
            // 需求：时间随「当前设备时区」表达（中国即 UTC+8），而不是恒为 UTC 的 …Z 形式或 Unix 时间戳。
            //
            // 用 JSON 序列化而不是字符串拼接：`model` / `client_ip` 来自客户端，含引号或换行时
            // 会破坏 .jsonl 的「一行一条」结构（污染后续解析）。
            Gateway.store.appendRequestLog(
                AppJson.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("time", JsonPrimitive(java.time.OffsetDateTime.now().toString()))
                    put("model", JsonPrimitive(model))
                    put("realm", JsonPrimitive(realm))
                    put("request_id", JsonPrimitive(reqId))
                    put("duration_ms", JsonPrimitive(dur))
                    put("ok", JsonPrimitive(hop.ok))
                    put("prompt_tokens", JsonPrimitive(hop.promptTokens))
                    put("completion_tokens", JsonPrimitive(hop.completionTokens))
                    if (cfg.logging.request_client_info) put("client_ip", JsonPrimitive(req.clientIp))
                })
            )
        }
    }

    private fun backoff(n: Int): Long {
        val base = 500L * (1L shl n)
        val capped = base.coerceAtMost(8000)
        val jitter = (Math.random() * 0.5 - 0.25) * capped
        return (capped + jitter).toLong().coerceAtLeast(100)
    }

    private fun resolveModel(raw: String): Pair<String, String> = when {
        raw.startsWith("global:") -> "global" to raw.substringAfter("global:")
        raw.startsWith("cn:") -> "cn" to raw.substringAfter("cn:")
        else -> "cn" to raw
    }

    /**
     * 选号：会话粘性号优先，但**必须先过健康校验**（对齐 Go `PickByUIDForModel`）——
     * 粘性号在当前模型被限流/账号冷却/在途占满时，必须解绑并回落普通轮换。
     */
    private fun pickAccount(realm: String, model: String, sessionKey: String?, tried: Set<String>): AuthEntry? {
        if (sessionKey != null) {
            val uid = Gateway.pool.stickyGet(sessionKey)
            if (uid != null && uid !in tried) {
                val a = Gateway.store.getAuth(uid)
                if (a != null && a.realm == realm &&
                    Gateway.pool.healthyForModel(uid, model) &&
                    !Gateway.pool.inFlightFull(uid, realm)) {
                    return a
                }
                LogBus.log("sticky unbind uid=${uid.take(8)}（该模型不可用/占满）-> 普通轮换")
                Gateway.pool.stickyUnbind(sessionKey)
            }
        }
        return Gateway.pool.pick(realm, model, tried)
    }

    /**
     * 上游流内累积的 token 用量（含**缓存命中**与**推理**明细）。
     *
     * 为什么要记这两项：客户端（以及本机的「缓存命中率」口径）需要它们才能判断
     * 前缀缓存是否生效。上游是 OpenAI 兼容形态，取值路径有两种：
     *  - OpenAI：[usage.prompt_tokens_details.cached_tokens] / [usage.completion_tokens_details.reasoning_tokens]
     *  - DeepSeek：[usage.prompt_cache_hit_tokens] / 顶层的 `reasoning_tokens`
     * 两种都读，先到先取（原实现只读 prompt/completion/total，缓存信息在网关侧就丢了）。
     */
    internal class Tokens(
        val prompt: Long = 0,
        val completion: Long = 0,
        val total: Long = 0,
        /** 命中上游前缀缓存的输入 token（读侧叫 cached_tokens）。 */
        val cached: Long = 0,
        /** 推理 token（计入 completion）。 */
        val reasoning: Long = 0,
    ) {
        fun merge(el: JsonObject): Tokens {
            val u = el["usage"]?.let { if (it is JsonNull) null else runCatching { it.jsonObject }.getOrNull() } ?: return this
            fun n(k: String) = u[k]?.primText()?.toLongOrNull() ?: 0L
            fun nested(parent: String, child: String): Long =
                ((u[parent] as? JsonObject)?.get(child))?.primText()?.toLongOrNull() ?: 0L
            val c = n("prompt_cache_hit_tokens").takeIf { it > 0 }
                ?: nested("prompt_tokens_details", "cached_tokens").takeIf { it > 0 }
                ?: cached
            val r = nested("completion_tokens_details", "reasoning_tokens").takeIf { it > 0 }
                ?: n("reasoning_tokens").takeIf { it > 0 }
                ?: reasoning
            return Tokens(
                n("prompt_tokens").takeIf { it > 0 } ?: prompt,
                n("completion_tokens").takeIf { it > 0 } ?: completion,
                n("total_tokens").takeIf { it > 0 } ?: total,
                c,
                r,
            )
        }
    }

    /** 上游 tool_calls 的流式增量（按 index 聚合 arguments 分片）。 */
    internal class ToolAcc {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
        fun toJson(): JsonObject = buildJsonObject {
            put("id", JsonPrimitive(id.ifBlank { "call_" + Ids.requestId().replace("-", "").take(20) }))
            put("type", JsonPrimitive("function"))
            put("function", buildJsonObject {
                put("name", JsonPrimitive(name))
                put("arguments", JsonPrimitive(args.toString().ifBlank { "{}" }))
            })
        }
    }

    /**
     * 工具参数是否为「被截断的残缺 JSON」。
     * 空串 / 纯空白 → false（合法无参工具）；非空但 JSON 解析失败 → true（截断）。
     * 与 Go `upstream/truncation.go:isTruncatedArguments` 同语义。
     */
    private fun isTruncatedArgs(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty()) return false
        return runCatching { AppJson.parseToJsonElement(t) }.isFailure
    }

    /**
     * 收敛 tool_call 的 function.name：同一 index 只有首片保留 name，后续分片删除该键
     * （对齐 OpenAI 官方流与 Go `upstream/sse.go:stripToolCallNames`）。
     * 部分客户端对重复出现的 name 会误判为「新开了一个工具调用」，进而中断续传。
     */
    private fun stripToolCallNames(choices: JsonElement, seen: MutableSet<Int>): JsonElement {
        val arr = choices as? JsonArray ?: return choices
        return JsonArray(arr.map { cEl ->
            val c = cEl as? JsonObject ?: return@map cEl
            val delta = c["delta"] as? JsonObject ?: return@map cEl
            val tcs = delta["tool_calls"] as? JsonArray ?: return@map cEl
            val newTcs = JsonArray(tcs.map { tcEl ->
                val tc = tcEl as? JsonObject ?: return@map tcEl
                val idx = tc["index"]?.primText()?.toIntOrNull() ?: 0
                if (seen.add(idx)) return@map tcEl
                val fn = tc["function"] as? JsonObject ?: return@map tcEl
                JsonObject(
                    tc.filterKeys { it != "function" } +
                        ("function" to JsonObject(fn.filterKeys { it != "name" })),
                )
            })
            JsonObject(c + ("delta" to JsonObject(delta + ("tool_calls" to newTcs))))
        })
    }

    internal fun collectToolCalls(delta: JsonObject?, into: LinkedHashMap<Int, ToolAcc>) {
        val arr = delta?.get("tool_calls") as? JsonArray ?: return
        for (el in arr) {
            val o = runCatching { el.jsonObject }.getOrNull() ?: continue
            val idx = o["index"]?.primText()?.toIntOrNull() ?: 0
            val acc = into.getOrPut(idx) { ToolAcc() }
            o["id"]?.primText()?.takeIf { it.isNotBlank() }?.let { acc.id = it }
            (o["function"] as? JsonObject)?.let { f ->
                f["name"]?.primText()?.takeIf { it.isNotBlank() }?.let { acc.name = it }
                f["arguments"]?.primText()?.let { acc.args.append(it) }
            }
        }
    }

    // ---------- /v1/chat/completions 流式转发 ----------

    private fun forwardStream(res: Res, resp: okhttp3.Response, auth: AuthEntry, model: String, realm: String, req: Req, reqId: String, start: Long): Hop {
        val reader = BufferedReader(InputStreamReader(bodyStreamOf(resp)))
        var started = false          // 是否已向客户端发送响应头（此后失败无法回退）
        // 首 token 等待（ms）：第一个 data 帧到达时记一次。用于吐字速率的分母
        // （速率 = 输出 tokens / (端到端 − TTFB)，见 Pool.tokensPerSecond 的注释）。
        var ttfbMs = 0L
        var firstId: String? = null
        var frames = 0
        var errFrame: String? = null
        var usage = Tokens()
        var sawDone = false
        // tool_call 的 name 收敛（与官方 OpenAI 流、Go stripToolCallNames 一致）：
        // 同一 index 只有首片保留 function.name，后续分片删除该键。
        val seenToolNames = HashSet<Int>()

        fun beginOnce() {
            if (!started) {
                ttfbMs = System.currentTimeMillis() - start
                res.beginStream(200, "text/event-stream", mapOf(
                    "Cache-Control" to "no-cache", "X-Accel-Buffering" to "no", "X-Request-Id" to reqId,
                ))
                started = true
            }
        }

        // 关键：**首个 data 帧到达前不发送响应头**。这样「首帧前上游断流 / 首帧即 error 帧」
        // 对客户端无痕 —— 上层 runPipeline 可换号重试（SSE 一旦发过字节就无法改状态码，
        // 这是「多轮对话 / 多轮工具调用概率性中断」的主要成因之一）。
        try {
            while (true) {
                val line = try {
                    reader.readLine()
                } catch (e: Throwable) {
                    if (!started) return Hop(ok = false, sent = false)
                    LogBus.log("| # $realm/$model uid=${auth.uid.take(8)} 上游流中断（已开流）: ${e.message}")
                    break
                } ?: break
                if (!line.startsWith("data:")) {
                    // SSE 注释 / 心跳行（": ping" 之类）：原样转发，帮助客户端与中间代理保活。
                    if (line.isNotBlank() && started) res.chunk("$line\n")
                    continue
                }
                val payload = line.substring(5).trim()
                if (!started) {
                    // 首帧：先判 error 帧（如 6004 限流）——未发送则直接换号重试
                    if (payload != "[DONE]") {
                        val el0 = runCatching { AppJson.parseToJsonElement(payload).jsonObject }.getOrNull()
                        if (el0 != null && el0.containsKey("error")) {
                            val kind = Classify.frame(payload)
                            Gateway.pool.onError(auth.uid, kind, 200, model, payload)
                            LogBus.log("| # $realm/$model uid=${auth.uid.take(8)} 首帧 error kind=$kind -> 换号重试")
                            return Hop(ok = false, sent = false)
                        }
                    }
                    beginOnce()
                }
                if (payload == "[DONE]") { sawDone = true; break }
                val el = runCatching { AppJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                if (el.containsKey("error")) {
                    errFrame = payload
                    res.chunk("data: $payload\n\n"); frames++; continue
                }
                usage = usage.merge(el)
                val norm = normalizeFrame(el, firstId, seenToolNames)
                firstId = norm.first
                res.chunk("data: ${AppJson.encodeToString(JsonObject.serializer(), norm.second)}\n\n")
                frames++
            }
            if (!started) {
                // 整个流没有任何 data 帧（上游空流 / 首帧前断开）→ 无痕，换号重试
                LogBus.log("| # $realm/$model uid=${auth.uid.take(8)} 上游空流/首帧前断开 -> 换号重试")
                return Hop(ok = false, sent = false)
            }
            if (frames == 0) {
                res.chunk("""data: {"error":{"message":"empty upstream stream","type":"upstream_error","code":"upstream_parse"}}""" + "\n\n")
            }
            if (!sawDone) {
                LogBus.log("| # $realm/$model uid=${auth.uid.take(8)} 上游未发 [DONE]（EOF 收尾）→ 网关兜底补发")
            }
            // ★ 关键修复：无论上游是否显式发过 [DONE]，都**恰好**向客户端补一个 [DONE]。
            //   权威依据：Go 参考实现 internal/upstream/sse.go 的 StreamHint ——
            //   「保证恰好写一个 [DONE]（上游漏发时兜底补上）」。上游经常在 EOF 处直接收尾
            //   而不发 [DONE]，此时 Agent 类客户端会认为流未正常结束，于是**不再发起下一轮**
            //   （表现为「模型回复完就不续传，要手动说"继续"」）。
            res.chunk("data: [DONE]\n\n")
        } finally {
            runCatching { reader.close() }
        }
        res.endStream()
        val dur = System.currentTimeMillis() - start
        val ef = errFrame
        if (ef != null) {
            val kind = Classify.frame(ef)
            Gateway.pool.onError(auth.uid, kind, 200, model, ef)
            LogBus.log("| # $realm/$model uid=${auth.uid.take(8)} 上游 error 帧 kind=$kind payload=${ef.take(200).replace('\n', ' ')}")
            return Hop(ok = false, sent = true)
        }
        Gateway.pool.onSuccess(auth.uid, usage.completion, dur, 0.0, model, usage.prompt, usage.cached, ttfbMs)
        // 原来这条日志把**端到端耗时**写成 `ttfb=`（名不符实），一并修正：两者都打出来。
        LogBus.log("| # $realm/$model uid=${auth.uid.take(8)} stream dur=${dur}ms ttfb=${ttfbMs}ms tokens=${usage.completion} id=$reqId")
        return Hop(ok = true, usage.prompt, usage.completion, usage.total, sent = true)
    }

    private fun normalizeFrame(
        el: JsonObject,
        cachedId: String?,
        seenToolNames: MutableSet<Int>,
    ): Pair<String?, JsonObject> {
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in el) if (k in frameWhitelist) out[k] = v
        out["choices"]?.let { out["choices"] = stripToolCallNames(it, seenToolNames) }
        val id = el["id"]?.primText()?.takeIf { it.isNotBlank() } ?: cachedId ?: "chatcmpl-wb2api"
        out["id"] = JsonPrimitive(id)
        out["object"] = JsonPrimitive("chat.completion.chunk")
        if (out["created"] == null) out["created"] = JsonPrimitive(System.currentTimeMillis() / 1000)
        if (out["model"] == null) out["model"] = JsonPrimitive(el["model"]?.primText() ?: "")
        if (out["usage"] == null) out["usage"] = JsonNull
        return id to JsonObject(out)
    }

    // ---------- /v1/chat/completions 非流式聚合 ----------

    /**
     * 非流式聚合。
     *
     * 修复点：原实现只读 `delta.content` / `delta.reasoning_content`，
     * **`delta.tool_calls` 被直接丢掉**——于是非流式的工具调用永远返回空回复
     * （客户端以为模型没要调工具）。现在按 index 聚合 arguments 分片并回填 `message.tool_calls`。
     */
    private fun aggregate(res: Res, resp: okhttp3.Response, auth: AuthEntry, model: String, realm: String, req: Req, reqId: String, start: Long): Hop {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = LinkedHashMap<Int, ToolAcc>()
        var id = "chatcmpl-wb2api"
        val created = System.currentTimeMillis() / 1000
        var finish = "stop"
        var sawDone = false
        var errFrame: String? = null
        var usage = Tokens()
        val reader = BufferedReader(InputStreamReader(bodyStreamOf(resp)))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload == "[DONE]") { sawDone = true; break }
                val el = runCatching { AppJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                if (el.containsKey("error")) { errFrame = payload; break }
                el["id"]?.primText()?.let { if (it.isNotBlank()) id = it }
                usage = usage.merge(el)
                (el["choices"] as? JsonArray)?.firstOrNull()?.objOrNull()?.let { ch ->
                    val delta = ch["delta"]?.objOrNull()
                    delta?.get("content")?.primText()?.let { content.append(it) }
                    delta?.get("reasoning_content")?.primText()?.let { reasoning.append(it) }
                    collectToolCalls(delta, toolCalls)
                    ch["finish_reason"]?.primText()?.let { if (it != "null") finish = it }
                }
            }
        } finally { runCatching { reader.close() } }

        if (errFrame != null) {
            val ef = errFrame
            val kind = Classify.frame(ef)
            Gateway.pool.onError(auth.uid, kind, 200, model, ef)
            LogBus.log("| # $realm/$model uid=${auth.uid.take(8)} nonstream 上游 error 帧 kind=$kind payload=${ef.take(200).replace('\n', ' ')}")
            // 非流式路径拿到的上游 error 帧：这里不是「选号失败」，所以只按 kind 分类
            // （selectionFailed = false 表示不要去做「全池模型级阻塞」判定）。
            // 错误体**已经写回客户端** → 这里必须标记 sent=true：否则 runPipeline 会把它
            // 当成「首帧前失败」而换号重发，但 Res 已发出字节（sendBytes 里 `if (sent) return`），
            // 后续写入成为 no-op —— 最终「客户端拿到错误、网关却记为成功」并白白多打一次上游。
            mapError(res, kind, 0, ef, model, false)
            return Hop(ok = false, sent = true)
        }

        val message = buildJsonObject {
            put("role", JsonPrimitive("assistant"))
            if (toolCalls.isEmpty()) {
                put("content", JsonPrimitive(content.toString()))
            } else {
                put("content", if (content.isEmpty()) JsonNull else JsonPrimitive(content.toString()))
                val calls = toolCalls.toSortedMap().values.toList()
                // 流被截断时（模型因 max_tokens 提前中止 finish_reason==length，或上游 EOF
                // 收尾却没发 data: [DONE]）tool_call 的 arguments 只剩半截 JSON —— 原样下发会让
                // 客户端解析非法 JSON 而卡死会话。对齐 Go upstream/truncation.go：**丢弃**残缺
                // 调用（不伪造成 {}），空参数的合法调用照常保留。
                val kept = if (finish == "length" || !sawDone) {
                    calls.filter { !isTruncatedArgs(it.args.toString()) }
                } else {
                    calls
                }
                if (kept.isNotEmpty()) {
                    put("tool_calls", buildJsonArray { kept.forEach { add(it.toJson()) } })
                }
            }
            if (reasoning.isNotEmpty()) put("reasoning_content", JsonPrimitive(reasoning.toString()))
        }
        val pt = if (usage.prompt > 0) usage.prompt else (String(req.body).length / 4).toLong()
        val ct = if (usage.completion > 0) usage.completion else content.length.toLong()
        val tt = if (usage.total > 0) usage.total else pt + ct
        val out = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("object", JsonPrimitive("chat.completion"))
            put("created", JsonPrimitive(created))
            put("model", JsonPrimitive(model))
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", JsonPrimitive(0))
                    put("message", message)
                    put("finish_reason", JsonPrimitive(finish))
                })
            })
            put("usage", buildJsonObject {
                put("prompt_tokens", JsonPrimitive(pt))
                put("completion_tokens", JsonPrimitive(ct))
                put("total_tokens", JsonPrimitive(tt))
                // 缓存 / 推理明细必须跟着透传：客户端据此算命中率与推理开销，
                // 原实现只给三个总数，缓存信息在网关侧就被抹平了。
                // OpenAI 记 details.*，DeepSeek 记顶层 prompt_cache_hit_tokens —— 两种都给。
                put("prompt_tokens_details", buildJsonObject {
                    put("cached_tokens", JsonPrimitive(usage.cached))
                })
                put("completion_tokens_details", buildJsonObject {
                    put("reasoning_tokens", JsonPrimitive(usage.reasoning))
                })
                if (usage.cached > 0) {
                    put("prompt_cache_hit_tokens", JsonPrimitive(usage.cached))
                    put("prompt_cache_miss_tokens", JsonPrimitive((pt - usage.cached).coerceAtLeast(0)))
                }
            })
        }
        res.sendJson(200, out, mapOf("X-Request-Id" to reqId))
        val dur = System.currentTimeMillis() - start
        Gateway.pool.onSuccess(auth.uid, ct, dur, 0.0, model, pt, usage.cached)
        LogBus.log("| # $realm/$model uid=${auth.uid.take(8)} nonstream ${dur}ms tokens=$ct tools=${toolCalls.size} id=$reqId")
        return Hop(ok = true, pt, ct, tt)
    }

    /**
     * 出站请求的一行**结构化摘要**（替代原来把整个 JSON body 前 200 字符直接打进日志）。
     *
     * 为什么改：原日志把请求体原文（含 system 提示词与用户消息）截断后打出来，既长到折行
     * 难看、又把对话内容透进日志（隐私）。这里只报**结构信息**（消息/工具条数、是否流式、
     * 输出上限、思考档位），全部脱敏、单行可读；仅当 `logging.request_body_log` 打开时追加
     * 一段**已脱敏的 JSON 骨架**（键名与结构，长字符串折叠），供排查。
     */
    private fun outboundSummary(prepared: JsonObject, realm: String, model: String, uid: String, stream: Boolean): String {
        val sb = StringBuilder("\u2192 \u51fa\u7ad9 ")
        sb.append(realm).append('/').append(model)
        sb.append(" uid=").append(Mask.uid(uid))
        sb.append(if (stream) " stream" else " nonstream")
        (prepared["messages"] as? JsonArray)?.let { sb.append(" \u00b7 ").append(it.size).append(" \u6761\u6d88\u606f") }
        (prepared["tools"] as? JsonArray)?.let { if (it.isNotEmpty()) sb.append(" \u00b7 ").append(it.size).append(" \u4e2a\u5de5\u5177") }
        prepared["max_tokens"]?.primTextOrNull()?.let { sb.append(" \u00b7 max_tokens ").append(it) }
        prepared["reasoning_effort"]?.primTextOrNull()?.let { sb.append(" \u00b7 \u601d\u8003 ").append(it) }
        if (Gateway.store.config.logging.request_body_log) {
            sb.append(" \u00b7 body ").append(redactedSkeleton(prepared))
        }
        return sb.toString()
    }

    /** 脱敏 JSON 骨架：保留键名；数组只报长度；长字符串折叠；总长封顶 240 字符。 */
    private fun redactedSkeleton(obj: JsonObject): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in obj) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(k).append("\":")
            when (v) {
                is JsonObject -> sb.append(redactedSkeleton(v))
                is JsonArray -> sb.append('[').append(v.size).append(']')
                else -> {
                    val s = v.primTextOrNull() ?: "null"
                    if (s.length > 24) sb.append('"').append(s.take(24)).append("\u2026\"") else sb.append('"').append(s).append('"')
                }
            }
            if (sb.length > 240) { sb.append('\u2026').append('}'); return sb.toString() }
        }
        sb.append('}')
        return sb.toString()
    }

    private fun mapError(res: Res, kind: ErrKind, status: Int, body: String, model: String, selectionFailed: Boolean) {
        val msg = body.ifBlank {
            when (kind) {
                ErrKind.SOFT_RATE -> "rate limited: all accounts are cooling down, please wait a moment and try again"
                ErrKind.WAF_BLOCK -> "upstream WAF blocked"
                ErrKind.HARD_CREDIT -> "account credit exhausted"
                ErrKind.SESSION_DEAD -> "all accounts session dead, re-login required"
                else -> "all accounts are temporarily unavailable, please retry later"
            }
        }
        val (code, errCode) = when (kind) {
            ErrKind.SOFT_RATE -> 429 to "rate_limit_exceeded"
            ErrKind.WAF_BLOCK -> 503 to "waf_ip_blocked"
            ErrKind.CONTENT_BLOCKED -> 400 to "content_blocked"
            ErrKind.PROMPT_TOO_LONG -> 400 to "prompt_too_long"
            ErrKind.IMAGE_INVALID -> 400 to "image_invalid"
            ErrKind.BAD_PARAMS, ErrKind.CLIENT -> 400 to "invalid_request_error"
            ErrKind.HARD_CREDIT -> 402 to "insufficient_credit"
            ErrKind.SESSION_DEAD -> 503 to "session_dead"
            // 11102「该后端无此模型」：用 400 + 明确 code 讲清「不可重试，换个模型」。
            // 停在默认的 no_healthy_account 是「服务端过载」的语义，客户端会一直重试
            // （对齐上游 handler 的 ErrModelBlocked 分支与 issue #81）。
            ErrKind.MODEL_BLOCKED -> 400 to "model_unavailable"
            else -> 503 to "no_healthy_account"
        }
        var finalCode = code
        var finalErrCode = errCode
        var hint = if (errCode == "no_healthy_account") "no healthy account available in pool; check /status or retry later" else ""

        // ★ 模型级阻塞覆盖上面的通用文案（对齐上游 ModelBlocked 覆盖，优先级最高）。
        //   条件与上游一致：**选号失败**（不是别的错误）且全池都被该模型冷却挡住。
        //   为什么要覆盖：Reason 就是台账里存的上游原文（已在上面的 msg 里带出），
        //   所以覆盖不会丢信息，反而补上上游不会告诉客户端的两件事 ——
        //   「有几个号被挡」和「最早什么时候解封」。
        // 只在「非 WAF 型失败」时判定模型级阻塞：WAF 封锁期内 pick 也会返回 null，
        // 那种情况必须保持 waf_ip_blocked 的语义，不能改写成 model_unavailable。
        if (selectionFailed && kind != ErrKind.WAF_BLOCK) {
            val block = Gateway.pool.modelBlocked(model)
            if (block.blocked) {
                finalCode = 400
                finalErrCode = "model_unavailable"
                val sb = StringBuilder("model_blocked: ${block.count} account(s) cooling down this model")
                if (block.until > 0) {
                    val iso = java.time.OffsetDateTime.ofInstant(
                        java.time.Instant.ofEpochSecond(block.until), java.time.ZoneId.systemDefault(),
                    )
                    sb.append("; earliest unblock at ").append(iso)
                }
                if (block.reason.isNotBlank()) sb.append("; upstream: ").append(block.reason)
                hint = sb.toString()
            }
        }
        res.sendError(finalCode, msg, finalErrCode, hint)
    }

    // ============================================================
    // Responses API 适配器（/v1/responses）
    // ============================================================

    internal object Responses {

        /** 转换结果：chat 请求体 + 客户端要的思考档位（响应里回显）+ 工具存在性 + 请求侧回显 + 拒绝原因。 */
        data class Converted(
            val chat: JsonObject,
            val effort: String,
            val toolsPresent: Boolean,
            /** 要原样回显进响应对象的请求侧字段（透传证据：客户端据此确认参数被接受）。 */
            val echo: JsonObject = JsonObject(emptyMap()),
            /** 非 null ⇒ 该请求本网关无法支持，直接以此文本 400 终止（不要静默丢弃）。 */
            val reject: String? = null,
        )

        /** Responses 与 chat/completions **同名同义**、可直接透传的顶层字段。 */
        private val EXTRA_PASSTHROUGH = listOf(
            "store", "service_tier", "prompt_cache_key", "safety_identifier", "parallel_tool_calls",
        )

        /** Responses 请求里携带、但本网关无法映射到上游的字段（记录一次，便于排查）。 */
        private val UNMAPPED = listOf("background", "truncation", "include", "max_tool_calls", "context_management")

        /** 指纹脱敏丢弃的字段：脱敏开着时不允许回显（回显会与「上游并未收到」自相矛盾）。 */
        val SANITIZED_ECHO = setOf("metadata", "store", "user", "safety_identifier")

        /** 会被回显到响应对象里的请求侧字段（官方 response 对象本身就包含这些）。 */
        private val ECHO_KEYS = listOf(
            "store", "metadata", "service_tier", "prompt_cache_key", "safety_identifier",
            "parallel_tool_calls", "truncation", "include", "text", "instructions",
            "max_output_tokens", "previous_response_id", "conversation", "tool_choice",
            "tools", "temperature", "top_p", "top_logprobs", "user",
        )

        private fun JsonObject.str(k: String): String = this[k]?.primText() ?: ""

        // ---------------- 请求侧 ----------------

        /**
         * Responses 请求 → chat/completions 请求体。
         *
         * 透传策略分三类，明确区分「映射」「直通」「无法支持」：
         *  1. 同名同义 → 直通（temperature/top_p/seed/stop/penalties/logit_bias/user/metadata/
         *     store/service_tier/prompt_cache_key/safety_identifier/parallel_tool_calls）；
         *  2. 不同名但可映射 → `max_output_tokens→max_tokens`、`text.format→response_format`、
         *     `text.verbosity→verbosity`、`top_logprobs→logprobs+top_logprobs`、
         *     `reasoning.effort→reasoning_effort`、`instructions→system`、
         *     `input` items→messages、`previous_response_id/conversation`→conversation_id；
         *  3. 无对应能力 → **明确报错或记日志**，绝不假装成功（background/truncation/include/
         *     max_tool_calls/context_management）。
         */
        fun toChatRequest(raw: JsonObject): Converted {
            val effort = extractEffort(raw)
            val tools = convertTools(raw["tools"])

            // 需要「服务端状态」的形态本网关给不了（无状态网关），必须显式拒绝而不是假装在跑。
            if (raw["background"]?.primText()?.toBoolean() == true) {
                return Converted(
                    buildJsonObject { put("model", JsonPrimitive(raw.str("model"))) }, effort, tools != null,
                    reject = "this gateway does not support `background: true` (no server-side job store); " +
                        "please use stream=true with a persistent connection.",
                )
            }

            val unmappedUsed = UNMAPPED.filter { raw.containsKey(it) && raw[it] !is JsonNull }
            if (unmappedUsed.isNotEmpty()) {
                LogBus.log("[sys] /v1/responses：请求含无法映射的字段 ${unmappedUsed.joinToString()}，已按「忽略」处理")
            }

            val conversationId = conversationIdOf(raw)

            val chat = buildJsonObject {
                put("model", JsonPrimitive(raw.str("model")))
                for (k in PASSTHROUGH) raw[k]?.let { if (it !is JsonNull) put(k, it) }
                for (k in EXTRA_PASSTHROUGH) raw[k]?.let { if (it !is JsonNull) put(k, it) }

                // structured outputs：text.format → response_format；text.verbosity → verbosity
                (raw["text"] as? JsonObject)?.let { text ->
                    text["format"]?.let { fmt ->
                        val fo = runCatching { fmt.jsonObject }.getOrNull()
                        when (fo?.get("type")?.primText()) {
                            "json_schema" -> put("response_format", buildJsonObject {
                                put("type", JsonPrimitive("json_schema"))
                                put("json_schema", buildJsonObject {
                                    fo["name"]?.let { put("name", it) }
                                    fo["schema"]?.let { put("schema", it) }
                                    fo["strict"]?.let { put("strict", it) }
                                })
                            })
                            "json_object" -> put("response_format", buildJsonObject { put("type", JsonPrimitive("json_object")) })
                        }
                    }
                    text["verbosity"]?.let { put("verbosity", it) }
                }

                if (tools != null) put("tools", tools)
                convertToolChoice(raw["tool_choice"])?.let { put("tool_choice", it) }
                (raw["max_output_tokens"] ?: raw["max_tokens"])?.let { put("max_tokens", it) }
                // top_logprobs 必须与 logprobs=true 同时出现，否则上游会忽略（官方语义）。
                raw["top_logprobs"]?.let {
                    put("logprobs", JsonPrimitive(true))
                    put("top_logprobs", it)
                }
                // 思考档位：**顶层 reasoning_effort** —— 上游（DeepSeek 系）认这个键，
                // 配合 Rewrite.injectThinking 注入的 thinking.type=enabled 一起生效。
                if (effort.isNotBlank()) put("reasoning_effort", JsonPrimitive(effort))

                put("messages", buildMessages(raw))

                // previous_response_id / conversation 仅用于粘性 + 缓存命名空间（本网关无状态存储）
                if (conversationId != null) put("conversation_id", JsonPrimitive(conversationId))
            }
            if (raw.containsKey("tools") && tools == null) {
                LogBus.log("[sys] /v1/responses：请求只包含内置工具（web_search 等），上游不支持，已忽略 tools")
            }
            val echo = buildJsonObject {
                for (k in ECHO_KEYS) raw[k]?.let { if (it !is JsonNull) put(k, it) }
            }
            return Converted(chat, effort, tools != null, echo)
        }

        /** 会话标识：`previous_response_id` 与 `conversation`（字符串或对象形态）都接。 */
        private fun conversationIdOf(raw: JsonObject): String? {
            raw["previous_response_id"]?.primText()?.takeIf { it.isNotBlank() }?.let { return "resp:" + it }
            return when (val c = raw["conversation"]) {
                is JsonPrimitive -> c.content.takeIf { it.isNotBlank() }?.let { "resp:" + it }
                is JsonObject -> (c["id"]?.primText())?.takeIf { it.isNotBlank() }?.let { "resp:" + it }
                else -> null
            }
        }

        /** 思考档位提取：兼容 reasoning.effort（对象/字符串）、顶层 reasoning_effort。 */
        private fun extractEffort(raw: JsonObject): String {
            val nested = when (val r = raw["reasoning"]) {
                is JsonObject -> r["effort"]?.primText()
                is JsonPrimitive -> r.content
                else -> null
            }
            return (nested ?: raw["reasoning_effort"]?.primText() ?: "").trim()
        }

        /**
         * 扁平 tools → 嵌套 tools。
         *
         * Responses：`{"type":"function","name":..,"description":..,"parameters":{..},"strict":true}`
         * chat/completions：`{"type":"function","function":{name,description,parameters,strict}}`
         * 原实现原样透传扁平结构 → 上游按 chat 协议解析不到 function → 工具调用必然失败。
         */
        private fun convertTools(tools: JsonElement?): JsonArray? {
            val arr = tools as? JsonArray ?: return null
            val out = ArrayList<JsonElement>()
            for (el in arr) {
                val o = runCatching { el.jsonObject }.getOrNull() ?: continue
                val nested = o["function"] as? JsonObject
                val type = o.str("type")
                if (type != "function" && type.isNotBlank()) continue // 内置工具：上游不支持
                val name = nested?.str("name")?.takeIf { it.isNotBlank() } ?: o.str("name")
                if (name.isBlank()) continue
                val desc = nested?.str("description")?.takeIf { it.isNotBlank() } ?: o.str("description")
                val params = nested?.get("parameters") ?: o["parameters"]
                val strict = nested?.get("strict") ?: o["strict"]
                out += buildJsonObject {
                    put("type", JsonPrimitive("function"))
                    put("function", buildJsonObject {
                        put("name", JsonPrimitive(name))
                        if (desc.isNotBlank()) put("description", JsonPrimitive(desc))
                        put("parameters", params ?: buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put("properties", buildJsonObject { })
                        })
                        // Responses 里 strict 默认为 true；此处**只在客户端显式给出时**透传，
                        // 避免把不满足 strict 约束的 schema 强行标记为 strict 而被上游拒绝。
                        strict?.let { if (it !is JsonNull) put("strict", it) }
                    })
                }
            }
            return if (out.isEmpty()) null else JsonArray(out)
        }

        private fun convertToolChoice(tc: JsonElement?): JsonElement? {
            return when (tc) {
                null, is JsonNull -> null
                is JsonPrimitive -> if (tc.content in setOf("auto", "none", "required")) JsonPrimitive(tc.content) else null
                is JsonObject -> {
                    val type = tc.str("type")
                    val name = tc["name"]?.primText()
                        ?: (tc["function"] as? JsonObject)?.str("name")
                    // 显式标注类型：内层 when 分支的公共超类型会被推断为 Any?，不标注会与返回类型冲突。
                    val res: JsonElement? = when {
                        type == "function" && !name.isNullOrBlank() -> buildJsonObject {
                            put("type", JsonPrimitive("function"))
                            put("function", buildJsonObject { put("name", JsonPrimitive(name)) })
                        }
                        type == "auto" || type == "none" || type == "required" -> JsonPrimitive(type)
                        else -> null
                    }
                    res
                }
                else -> null
            }
        }

        /** input items → chat messages（含工具往返与 reasoning 回填）。 */
        private fun buildMessages(raw: JsonObject): JsonArray {
            val msgs = ArrayList<JsonElement>()
            raw["instructions"]?.primText()?.takeIf { it.isNotBlank() }?.let {
                msgs += buildJsonObject { put("role", JsonPrimitive("system")); put("content", JsonPrimitive(it)) }
            }
            var pendingReasoning = ""
            when (val input = raw["input"]) {
                is JsonPrimitive -> if (input.content.isNotBlank()) {
                    msgs += buildJsonObject { put("role", JsonPrimitive("user")); put("content", JsonPrimitive(input.content)) }
                }
                is JsonArray -> input.forEach { item ->
                    val o = runCatching { item.jsonObject }.getOrNull() ?: return@forEach
                    val type = o.str("type").ifBlank { if (o.containsKey("role")) "message" else "" }
                    when (type) {
                        "message" -> {
                            val role = o.str("role").ifBlank { "user" }.let { if (it == "developer") "system" else it }
                            // 注意：lambda 内不能用 continue，需用 return@forEach
                            val content = convertContent(o["content"]) ?: return@forEach
                            val m = buildJsonObject {
                                put("role", JsonPrimitive(role))
                                put("content", content)
                            }
                            // 回填推理轨迹（DeepSeek 系上游认 reasoning_content）
                            if (pendingReasoning.isNotBlank() && role == "assistant") {
                                msgs += JsonObject(m.toMutableMap().apply { put("reasoning_content", JsonPrimitive(pendingReasoning)) })
                            } else msgs += m
                            pendingReasoning = ""
                        }
                        "function_call" -> {
                            val callId = o.str("call_id").ifBlank { o.str("id") }
                            if (callId.isBlank() || o.str("name").isBlank()) return@forEach
                            val tc = buildJsonObject {
                                put("id", JsonPrimitive(callId))
                                put("type", JsonPrimitive("function"))
                                put("function", buildJsonObject {
                                    put("name", JsonPrimitive(o.str("name")))
                                    put("arguments", JsonPrimitive(argumentsToString(o["arguments"])))
                                })
                            }
                            appendToolCall(msgs, tc, pendingReasoning)
                            pendingReasoning = ""
                        }
                        "function_call_output" -> {
                            val callId = o.str("call_id")
                            if (callId.isBlank()) return@forEach
                            msgs += buildJsonObject {
                                put("role", JsonPrimitive("tool"))
                                put("tool_call_id", JsonPrimitive(callId))
                                put("content", JsonPrimitive(outputToString(o["output"])))
                            }
                        }
                        "reasoning" -> pendingReasoning = summaryText(o)
                        else -> LogBus.log("[sys] /v1/responses 忽略不支持的 input item: $type")
                    }
                }
                else -> {}
            }
            return JsonArray(msgs)
        }

        /** 把 function_call 合并进「上一条只有 tool_calls 的 assistant 消息」（OpenAI 的标准形态）。 */
        private fun appendToolCall(msgs: ArrayList<JsonElement>, tc: JsonObject, reasoning: String) {
            val last = msgs.lastOrNull() as? JsonObject
            val lastIsToolCallAssistant = last != null && last.str("role") == "assistant" &&
                last["tool_calls"] is JsonArray && (last["content"] == null || last["content"] is JsonNull)
            if (lastIsToolCallAssistant) {
                val merged = (last["tool_calls"] as JsonArray).toMutableList()
                merged += tc
                msgs[msgs.size - 1] = JsonObject(last.toMutableMap().apply { put("tool_calls", JsonArray(merged)) })
            } else {
                msgs += buildJsonObject {
                    put("role", JsonPrimitive("assistant"))
                    put("content", JsonNull)
                    put("tool_calls", JsonArray(listOf(tc)))
                    if (reasoning.isNotBlank()) put("reasoning_content", JsonPrimitive(reasoning))
                }
            }
        }

        private fun convertContent(content: JsonElement?): JsonElement? = when (content) {
            null, is JsonNull -> null
            is JsonPrimitive -> content
            is JsonArray -> {
                val parts = ArrayList<JsonElement>()
                for (el in content) {
                    val p = runCatching { el.jsonObject }.getOrNull() ?: continue
                    when (p.str("type")) {
                        "input_text", "output_text", "text", "summary_text" ->
                            parts += buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(p.str("text")))
                            }
                        "input_image", "image_url" -> {
                            val url = p.str("image_url").ifBlank { p.str("url") }
                                .ifBlank { (p["image_url"] as? JsonObject)?.str("url") ?: "" }
                            if (url.isNotBlank()) {
                                parts += buildJsonObject {
                                    put("type", JsonPrimitive("image_url"))
                                    put("image_url", buildJsonObject { put("url", JsonPrimitive(url)) })
                                }
                            }
                        }
                    }
                }
                when {
                    parts.isEmpty() -> null
                    parts.size == 1 && (parts[0] as? JsonObject)?.str("type") == "text" ->
                        parts[0].jsonObject["text"]
                    else -> JsonArray(parts)
                }
            }
            else -> null
        }

        /** function_call.arguments 可能是字符串（标准）也可能是对象（部分客户端）→ 统一为字符串。 */
        private fun argumentsToString(a: JsonElement?): String = when (a) {
            null, is JsonNull -> "{}"
            is JsonPrimitive -> a.content.ifBlank { "{}" }
            else -> AppJson.encodeToString(JsonElement.serializer(), a)
        }

        private fun outputToString(o: JsonElement?): String = when (o) {
            null, is JsonNull -> ""
            is JsonPrimitive -> o.content
            is JsonArray -> o.joinToString("") { el ->
                runCatching { el.jsonObject }.getOrNull()?.str("text") ?: (el as? JsonPrimitive)?.content ?: ""
            }
            else -> AppJson.encodeToString(JsonElement.serializer(), o)
        }

        /**
         * reasoning item → 纯文本。
         *
         * 两个字段都可能是**数组**（官方定义：`summary: [{type:"summary_text",text}]`、
         * `content: [{type:"reasoning_text",text}]`），旧实现只读了 summary 数组、
         * 且把 content 当字符串追加 —— content 为数组时会直接抛
         * 「is not a JsonPrimitive」（多轮里客户端回传 reasoning item 就命中）。
         * 现在两者都按数组遍历提取 text，最后才退回「content 是字符串」的历史形态。
         */
        private fun summaryText(item: JsonObject): String = buildString {
            fun fromArray(key: String) {
                (item[key] as? JsonArray)?.forEach { s ->
                    runCatching { s.jsonObject }.getOrNull()?.str("text")?.let { append(it) }
                }
            }
            fromArray("summary")
            if (isEmpty()) fromArray("content")
            if (isEmpty()) append(item.str("content"))
        }

        // ---------------- 响应侧 ----------------

        private fun newId(prefix: String) = prefix + Ids.requestId().replace("-", "").take(24)

        private fun usageJson(p: Long, c: Long, t: Long, cached: Long, reasoning: Long) = buildJsonObject {
            put("input_tokens", JsonPrimitive(p))
            put("input_tokens_details", buildJsonObject { put("cached_tokens", JsonPrimitive(cached)) })
            put("output_tokens", JsonPrimitive(c))
            put("output_tokens_details", buildJsonObject { put("reasoning_tokens", JsonPrimitive(reasoning)) })
            put("total_tokens", JsonPrimitive(t))
        }

        /**
         * Responses 响应对象。
         *
         * [echo] 是**请求侧字段的回显**（store / metadata / service_tier / prompt_cache_key /
         * truncation / text / instructions / max_output_tokens / parallel_tool_calls / tools …）。
         * 官方 Responses 响应体本来就带这些字段：不回显，客户端会以为参数被网关吞掉了
         * ——用户反馈的「透传不彻底」有一半指的就是这里。
         */
        private fun envelope(
            id: String, model: String, createdAt: Long, status: String,
            output: JsonElement, p: Long, c: Long, t: Long,
            effort: String = "", err: String? = null,
            cached: Long = 0, reasoning: Long = 0, echo: JsonObject? = null,
            /** 非 null ⇒ 写入 incomplete_details.reason（status=incomplete，见 forwardStream）。 */
            incompleteReason: String? = null,
        ): JsonObject {
            // 在 builder 外先判定：buildJsonObject 的接收者是 builder 而非 Map，
            // 直接调 containsKey 会解析不到（编译期 unresolved）。
            val hasParallel = echo?.containsKey("parallel_tool_calls") ?: false
            val hasToolChoice = echo?.containsKey("tool_choice") ?: false
            return buildJsonObject {
                put("id", JsonPrimitive(id))
                put("object", JsonPrimitive("response"))
                put("created_at", JsonPrimitive(createdAt))
                put("status", JsonPrimitive(status))
                put("model", JsonPrimitive(model))
                put("output", output)
                // 回显思考档位：客户端（及其 UI）据此展示/校验「思考等级是否生效」。
                if (effort.isNotBlank()) put("reasoning", buildJsonObject {
                    put("effort", JsonPrimitive(effort))
                    put("summary", JsonNull)
                })
                put("error", if (err == null) JsonNull else buildJsonObject {
                    put("code", JsonPrimitive("upstream_error"))
                    put("message", JsonPrimitive(err))
                })
                put(
                    "incomplete_details",
                    if (incompleteReason == null) JsonNull
                    else buildJsonObject { put("reason", JsonPrimitive(incompleteReason)) },
                )
                if (echo != null) for ((k, v) in echo) put(k, v)
                if (!hasParallel) put("parallel_tool_calls", JsonPrimitive(true))
                if (!hasToolChoice) put("tool_choice", JsonPrimitive("auto"))
                put("usage", usageJson(p, c, t, cached, reasoning))
            }
        }

        private fun reasoningItem(text: String, itemId: String = newId("rs_")): JsonObject = buildJsonObject {
            put("type", JsonPrimitive("reasoning"))
            put("id", JsonPrimitive(itemId))
            put("status", JsonPrimitive("completed"))
            put("summary", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("summary_text"))
                    put("text", JsonPrimitive(text))
                })
            })
        }

        private fun messageItem(text: String, itemId: String = newId("msg_")): JsonObject = buildJsonObject {
            put("type", JsonPrimitive("message"))
            put("id", JsonPrimitive(itemId))
            put("status", JsonPrimitive("completed"))
            put("role", JsonPrimitive("assistant"))
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("output_text"))
                    put("text", JsonPrimitive(text))
                    put("annotations", buildJsonArray { })
                })
            })
        }

        /** chat 的 tool_call → Responses 的 function_call item。 */
        private fun functionCallItem(tc: JsonObject, itemId: String = newId("fc_")): JsonObject {
            val fn = tc["function"] as? JsonObject
            return buildJsonObject {
                put("type", JsonPrimitive("function_call"))
                put("id", JsonPrimitive(itemId))
                put("call_id", JsonPrimitive(tc.str("id").ifBlank { newId("call_") }))
                put("name", JsonPrimitive(fn?.str("name") ?: ""))
                put("arguments", JsonPrimitive(fn?.str("arguments")?.ifBlank { "{}" } ?: "{}"))
                put("status", JsonPrimitive("completed"))
            }
        }

        private fun outputItems(text: String, reasoning: String, toolCalls: Collection<ToolAcc>): JsonArray = buildJsonArray {
            if (reasoning.isNotBlank()) add(reasoningItem(reasoning))
            if (text.isNotBlank()) add(messageItem(text))
            toolCalls.forEach { add(functionCallItem(it.toJson())) }
        }

        /** 非流式：聚合上游 chat 流 → Responses 对象（含 function_call 输出与缓存用量）。 */
        fun aggregate(
            res: Res, resp: okhttp3.Response, uid: String, clientModel: String, realm: String,
            model: String, reqId: String, start: Long, req: Req, effort: String,
            echo: JsonObject = JsonObject(emptyMap()),
        ): Hop {
            val content = StringBuilder()
            val reasoning = StringBuilder()
            val toolCalls = LinkedHashMap<Int, ToolAcc>()
            var errFrame: String? = null
            var finish: String? = null
            var sawDone = false
            var usage = Tokens()
            val reader = BufferedReader(InputStreamReader(bodyStreamOf(resp)))
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") { sawDone = true; break }
                    val el = runCatching { AppJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                    if (el.containsKey("error")) { errFrame = payload; break }
                    usage = usage.merge(el)
                    (el["choices"] as? JsonArray)?.firstOrNull()?.objOrNull()?.let { ch ->
                        ch["finish_reason"]?.primText()?.takeIf { it.isNotBlank() && it != "null" }?.let { finish = it }
                        val d = ch["delta"]?.objOrNull()
                        d?.get("content")?.primText()?.let { content.append(it) }
                        d?.get("reasoning_content")?.primText()?.let { reasoning.append(it) }
                        collectToolCalls(d, toolCalls)
                    }
                }
            } finally { runCatching { reader.close() } }

            if (errFrame != null) {
                val ef = errFrame
                val kind = Classify.frame(ef)
                Gateway.pool.onError(uid, kind, 200, model, ef)
                LogBus.log("| # $realm/$model responses 上游 error 帧 kind=$kind payload=${ef.take(200).replace('\n', ' ')}")
                // 同上：错误体已回写 → sent=true，避免被 runPipeline 当成「未发数据」而重试。
                res.sendError(mapCode(kind), ef, "upstream_error")
                return Hop(ok = false, sent = true)
            }

            val pt = if (usage.prompt > 0) usage.prompt else (String(req.body).length / 4).toLong()
            val ct = if (usage.completion > 0) usage.completion else content.length.toLong()
            val tt = if (usage.total > 0) usage.total else pt + ct
            // 与 chat 路径同口径（见 [isTruncatedArgs] / Go upstream/truncation.go）：截断时
            // 丢弃 arguments 残缺的 tool_call（原样下发非法 JSON 会让客户端卡死会话），并把
            // 响应标记为 incomplete，告诉客户端「生成未完成、可继续」。
            val truncated = finish == "length" || (finish == null && !sawDone)
            val calls = toolCalls.toSortedMap().values.toList()
            val kept = if (truncated) calls.filter { !isTruncatedArgs(it.args.toString()) } else calls
            val obj = envelope(
                newId("resp_"), clientModel, System.currentTimeMillis() / 1000,
                if (truncated) "incomplete" else "completed",
                outputItems(content.toString(), reasoning.toString(), kept),
                pt, ct, tt, effort, null, usage.cached, usage.reasoning, echo,
                incompleteReason = if (truncated) "max_output_tokens" else null,
            )
            res.sendJson(200, obj, mapOf("X-Request-Id" to reqId))
            val dur = System.currentTimeMillis() - start
            Gateway.pool.onSuccess(uid, ct, dur, 0.0, model, pt, usage.cached)
            LogBus.log(
                "| # $realm/$model responses nonstream ${dur}ms tokens=$ct cached=${usage.cached} " +
                    "reasoning=${usage.reasoning} tools=${toolCalls.size} effort=${effort.ifBlank { "-" }}" +
                    (if (truncated) " [截断→incomplete]" else "") + " id=$reqId",
            )
            return Hop(ok = true, pt, ct, tt)
        }

        /**
         * 流式：上游 chat chunk → Responses 事件流。
         *
         * 事件序列（官方规格）：response.created / in_progress →
         * [reasoning: output_item.added → reasoning_summary_part.added → reasoning_summary_text.delta×N → done] →
         * [message: output_item.added → content_part.added → output_text.delta×N → text.done → part.done → item.done] →
         * [function_call: output_item.added → function_call_arguments.delta×N → arguments.done → item.done] →
         * response.completed。
         */
        fun forwardStream(
            res: Res, resp: okhttp3.Response, uid: String, clientModel: String, realm: String,
            model: String, reqId: String, start: Long, effort: String,
            echo: JsonObject = JsonObject(emptyMap()),
        ): Hop {
            res.beginStream(200, "text/event-stream", mapOf(
                "Cache-Control" to "no-cache", "X-Accel-Buffering" to "no", "X-Request-Id" to reqId,
            ))
            val id = newId("resp_")
            val createdAt = System.currentTimeMillis() / 1000
            var seq = 0

            fun ev(type: String, data: JsonObject) {
                seq++
                res.chunk("event: $type\ndata: ${AppJson.encodeToString(JsonObject.serializer(), JsonObject(data.toMutableMap().apply { put("sequence_number", JsonPrimitive(seq)) }))}\n\n")
            }

            ev("response.created", buildJsonObject {
                put("type", JsonPrimitive("response.created"))
                put("response", envelope(id, clientModel, createdAt, "in_progress", buildJsonArray { }, 0, 0, 0, effort))
            })
            ev("response.in_progress", buildJsonObject {
                put("type", JsonPrimitive("response.in_progress"))
                put("response", envelope(id, clientModel, createdAt, "in_progress", buildJsonArray { }, 0, 0, 0, effort))
            })

            val content = StringBuilder()
            val reasoning = StringBuilder()
            val toolCalls = LinkedHashMap<Int, ToolAcc>()
            val outIndex = LinkedHashMap<String, Int>()

            var reasoningItemId = ""
            var messageItemId = ""
            val toolItemIds = HashMap<Int, String>()

            var errFrame: String? = null
            var usage = Tokens()
            // 收尾信号：`finish_reason`（normal stop / length / tool_calls）与是否收到 [DONE]。
            // 两者共同决定「这是正常完成还是被截断」——决定上报 completed 还是 incomplete。
            var finish: String? = null
            var sawDone = false
            val reader = BufferedReader(InputStreamReader(bodyStreamOf(resp)))
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") { sawDone = true; break }
                    val el = runCatching { AppJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                    if (el.containsKey("error")) { errFrame = payload; break }
                    usage = usage.merge(el)
                    val ch = (el["choices"] as? JsonArray)?.firstOrNull()?.objOrNull() ?: continue
                    // finish_reason 常出现在「没有 delta 的收尾帧」里，必须在取 delta 之前读。
                    ch["finish_reason"]?.primText()?.takeIf { it.isNotBlank() && it != "null" }?.let { finish = it }
                    val d = ch["delta"]?.objOrNull() ?: continue

                    // ---- 推理轨迹 ----
                    d["reasoning_content"]?.primText()?.takeIf { it.isNotEmpty() }?.let { piece ->
                        if (reasoningItemId.isEmpty()) {
                            reasoningItemId = newId("rs_")
                            outIndex["reasoning"] = (outIndex.size)
                            ev("response.output_item.added", buildJsonObject {
                                put("type", JsonPrimitive("response.output_item.added"))
                                put("output_index", JsonPrimitive(outIndex["reasoning"] ?: 0))
                                put("item", buildJsonObject {
                                    put("type", JsonPrimitive("reasoning"))
                                    put("id", JsonPrimitive(reasoningItemId))
                                    put("summary", buildJsonArray { })
                                })
                            })
                            ev("response.reasoning_summary_part.added", buildJsonObject {
                                put("type", JsonPrimitive("response.reasoning_summary_part.added"))
                                put("item_id", JsonPrimitive(reasoningItemId))
                                put("output_index", JsonPrimitive(outIndex["reasoning"] ?: 0))
                                put("summary_index", JsonPrimitive(0))
                                put("part", buildJsonObject { put("type", JsonPrimitive("summary_text")); put("text", JsonPrimitive("")) })
                            })
                        }
                        reasoning.append(piece)
                        ev("response.reasoning_summary_text.delta", buildJsonObject {
                            put("type", JsonPrimitive("response.reasoning_summary_text.delta"))
                            put("item_id", JsonPrimitive(reasoningItemId))
                            put("output_index", JsonPrimitive(outIndex["reasoning"] ?: 0))
                            put("summary_index", JsonPrimitive(0))
                            put("delta", JsonPrimitive(piece))
                        })
                    }

                    // ---- 正文 ----
                    d["content"]?.primText()?.takeIf { it.isNotEmpty() }?.let { piece ->
                        if (messageItemId.isEmpty()) {
                            messageItemId = newId("msg_")
                            outIndex["message"] = outIndex.size
                            ev("response.output_item.added", buildJsonObject {
                                put("type", JsonPrimitive("response.output_item.added"))
                                put("output_index", JsonPrimitive(outIndex["message"] ?: 0))
                                put("item", buildJsonObject {
                                    put("id", JsonPrimitive(messageItemId)); put("type", JsonPrimitive("message"))
                                    put("status", JsonPrimitive("in_progress")); put("role", JsonPrimitive("assistant"))
                                    put("content", buildJsonArray { })
                                })
                            })
                            ev("response.content_part.added", buildJsonObject {
                                put("type", JsonPrimitive("response.content_part.added"))
                                put("item_id", JsonPrimitive(messageItemId))
                                put("output_index", JsonPrimitive(outIndex["message"] ?: 0))
                                put("content_index", JsonPrimitive(0))
                                put("part", buildJsonObject {
                                    put("type", JsonPrimitive("output_text")); put("text", JsonPrimitive(""))
                                    put("annotations", buildJsonArray { })
                                })
                            })
                        }
                        content.append(piece)
                        ev("response.output_text.delta", buildJsonObject {
                            put("type", JsonPrimitive("response.output_text.delta"))
                            put("item_id", JsonPrimitive(messageItemId))
                            put("output_index", JsonPrimitive(outIndex["message"] ?: 0))
                            put("content_index", JsonPrimitive(0))
                            put("delta", JsonPrimitive(piece))
                        })
                    }

                    // ---- 工具调用（关键：原实现完全没处理 delta.tool_calls）----
                    (d["tool_calls"] as? JsonArray)?.forEach { t ->
                        val o = runCatching { t.jsonObject }.getOrNull() ?: return@forEach
                        val idx = o["index"]?.primText()?.toIntOrNull() ?: 0
                        val acc = toolCalls.getOrPut(idx) { ToolAcc() }
                        o["id"]?.primText()?.takeIf { it.isNotBlank() }?.let { acc.id = it }
                        val fn = o["function"] as? JsonObject
                        val name = fn?.get("name")?.primText()
                        if (!name.isNullOrBlank() && acc.name.isEmpty()) acc.name = name
                        if (!toolItemIds.containsKey(idx)) {
                            toolItemIds[idx] = newId("fc_")
                            "fc:$idx".let { outIndex[it] = outIndex.size }
                            ev("response.output_item.added", buildJsonObject {
                                put("type", JsonPrimitive("response.output_item.added"))
                                put("output_index", JsonPrimitive(outIndex["fc:$idx"] ?: 0))
                                put("item", buildJsonObject {
                                    put("type", JsonPrimitive("function_call"))
                                    put("id", JsonPrimitive(toolItemIds[idx] ?: ""))
                                    put("call_id", JsonPrimitive(acc.id.ifBlank { newId("call_") }))
                                    put("name", JsonPrimitive(acc.name))
                                    put("arguments", JsonPrimitive(""))
                                    put("status", JsonPrimitive("in_progress"))
                                })
                            })
                        }
                        val piece = fn?.get("arguments")?.primText()
                        if (!piece.isNullOrEmpty()) {
                            acc.args.append(piece)
                            ev("response.function_call_arguments.delta", buildJsonObject {
                                put("type", JsonPrimitive("response.function_call_arguments.delta"))
                                put("item_id", JsonPrimitive(toolItemIds[idx] ?: ""))
                                put("output_index", JsonPrimitive(outIndex["fc:$idx"] ?: 0))
                                put("delta", JsonPrimitive(piece))
                            })
                        }
                    }
                }
            } finally { runCatching { reader.close() } }

            if (errFrame != null) {
                val ef = errFrame
                val kind = Classify.frame(ef)
                Gateway.pool.onError(uid, kind, 200, model, ef)
                LogBus.log("| # $realm/$model responses 上游 error 帧 kind=$kind payload=${ef.take(200).replace('\n', ' ')}")
                ev("response.failed", buildJsonObject {
                    put("type", JsonPrimitive("response.failed"))
                    put(
                        "response",
                        envelope(id, clientModel, createdAt, "failed", buildJsonArray { }, 0, 0, 0, effort, ef.take(400), 0, 0, echo),
                    )
                })
                res.endStream()
                return Hop(ok = false)
            }

            // 收尾事件（顺序与 output 数组一致）
            val reaText = reasoning.toString()
            if (reasoningItemId.isNotEmpty()) {
                val oi = outIndex["reasoning"] ?: 0
                ev("response.reasoning_summary_text.done", buildJsonObject {
                    put("type", JsonPrimitive("response.reasoning_summary_text.done"))
                    put("item_id", JsonPrimitive(reasoningItemId)); put("output_index", JsonPrimitive(oi))
                    put("summary_index", JsonPrimitive(0)); put("text", JsonPrimitive(reaText))
                })
                ev("response.reasoning_summary_part.done", buildJsonObject {
                    put("type", JsonPrimitive("response.reasoning_summary_part.done"))
                    put("item_id", JsonPrimitive(reasoningItemId)); put("output_index", JsonPrimitive(oi))
                    put("summary_index", JsonPrimitive(0))
                    put("part", buildJsonObject { put("type", JsonPrimitive("summary_text")); put("text", JsonPrimitive(reaText)) })
                })
                ev("response.output_item.done", buildJsonObject {
                    put("type", JsonPrimitive("response.output_item.done"))
                    put("output_index", JsonPrimitive(oi))
                    put("item", reasoningItem(reaText, reasoningItemId))
                })
            }
            val text = content.toString()
            if (messageItemId.isNotEmpty()) {
                val oi = outIndex["message"] ?: 0
                ev("response.output_text.done", buildJsonObject {
                    put("type", JsonPrimitive("response.output_text.done"))
                    put("item_id", JsonPrimitive(messageItemId)); put("output_index", JsonPrimitive(oi))
                    put("content_index", JsonPrimitive(0)); put("text", JsonPrimitive(text))
                })
                ev("response.content_part.done", buildJsonObject {
                    put("type", JsonPrimitive("response.content_part.done"))
                    put("item_id", JsonPrimitive(messageItemId)); put("output_index", JsonPrimitive(oi))
                    put("content_index", JsonPrimitive(0))
                    put("part", buildJsonObject {
                        put("type", JsonPrimitive("output_text")); put("text", JsonPrimitive(text))
                        put("annotations", buildJsonArray { })
                    })
                })
                ev("response.output_item.done", buildJsonObject {
                    put("type", JsonPrimitive("response.output_item.done"))
                    put("output_index", JsonPrimitive(oi))
                    put("item", messageItem(text, messageItemId))
                })
            }
            toolCalls.toSortedMap().forEach { (idx, acc) ->
                val oi = outIndex["fc:$idx"] ?: 0
                val args = acc.args.toString().ifBlank { "{}" }
                ev("response.function_call_arguments.done", buildJsonObject {
                    put("type", JsonPrimitive("response.function_call_arguments.done"))
                    put("item_id", JsonPrimitive(toolItemIds[idx] ?: "")); put("output_index", JsonPrimitive(oi))
                    put("arguments", JsonPrimitive(args))
                })
                ev("response.output_item.done", buildJsonObject {
                    put("type", JsonPrimitive("response.output_item.done"))
                    put("output_index", JsonPrimitive(oi))
                    put("item", functionCallItem(acc.toJson(), toolItemIds[idx] ?: newId("fc_")))
                })
            }

            val ct = if (usage.completion > 0) usage.completion else text.length.toLong()
            val tt = if (usage.total > 0) usage.total else usage.prompt + ct
            // 截断判定：finish_reason==length（撞 max_tokens）或流异常收尾（既没有 [DONE]
            // 也没给出 finish_reason）。此时**必须**用 `response.incomplete` 收尾、status 置
            // incomplete 且带 incomplete_details.reason —— 官方语义是「生成未完成、可继续」，
            // agent 客户端据此自动续传。
            //
            // 这正是本端与 /v1/chat/completions 的行为差异所在：chat 客户端能直接看到
            // finish_reason=length 的截断信号，而过去这里一律谎报 status=completed +
            // incomplete_details=null，于是 agent 判定「任务已结束」而停下、不自动续传。
            val truncated = finish == "length" || (finish == null && !sawDone)
            if (truncated) {
                ev("response.incomplete", buildJsonObject {
                    put("type", JsonPrimitive("response.incomplete"))
                    put("response", envelope(
                        id, clientModel, createdAt, "incomplete",
                        outputItems(text, reaText, toolCalls.toSortedMap().values),
                        usage.prompt, ct, tt, effort, null, usage.cached, usage.reasoning, echo,
                        incompleteReason = "max_output_tokens",
                    ))
                })
            } else {
                ev("response.completed", buildJsonObject {
                    put("type", JsonPrimitive("response.completed"))
                    put("response", envelope(
                        id, clientModel, createdAt, "completed",
                        outputItems(text, reaText, toolCalls.toSortedMap().values),
                        usage.prompt, ct, tt, effort, null, usage.cached, usage.reasoning, echo,
                    ))
                })
            }
            res.endStream()
            val dur = System.currentTimeMillis() - start
            Gateway.pool.onSuccess(uid, ct, dur, 0.0, model, usage.prompt, usage.cached)
            LogBus.log(
                "| # $realm/$model responses stream ${dur}ms tokens=$ct cached=${usage.cached} " +
                    "reasoning=${usage.reasoning} tools=${toolCalls.size} effort=${effort.ifBlank { "-" }}" +
                    (if (truncated) " [截断→incomplete]" else "") + " id=$reqId",
            )
            return Hop(ok = true, usage.prompt, ct, tt)
        }

        private fun mapCode(kind: ErrKind): Int = when (kind) {
            ErrKind.SOFT_RATE -> 429
            ErrKind.HARD_CREDIT -> 402
            ErrKind.CONTENT_BLOCKED, ErrKind.PROMPT_TOO_LONG, ErrKind.IMAGE_INVALID, ErrKind.BAD_PARAMS, ErrKind.CLIENT -> 400
            else -> 503
        }

        /** 顶层可直通的标量/对象字段（Responses 与 chat 同名同义）。 */
        private val PASSTHROUGH = listOf(
            "temperature", "top_p", "stream", "stop", "seed",
            "frequency_penalty", "presence_penalty", "logit_bias", "user", "metadata",
        )
    }
}

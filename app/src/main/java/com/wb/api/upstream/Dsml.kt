package com.wb.api.upstream

import com.wb.api.core.AppJson
import com.wb.api.core.objOrNull
import com.wb.api.core.primTextOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * DSML 工具调用标记还原层 —— 逐条对齐新上游 `internal/upstream/dsml.go`。
 *
 * 背景：本网关背后的模型（DeepSeek 系）原生工具调用不是 OpenAI 的 JSON tool_calls，
 * 而是一段带**全角竖线**分隔符的标记文本：
 *
 * ```
 * <｜｜DSML｜｜ calls>
 * <｜｜DSML｜｜ invoke name="exec_command">
 * <｜｜DSML｜｜ parameter name="cmd" string="true">ls -la<｜｜DSML｜｜ parameter>
 * </｜｜DSML｜｜ invoke>
 * </｜｜DSML｜｜ calls>
 * ```
 *
 * 上游只在请求**声明了 tools** 时才把它解析成结构化 tool_calls。一旦某次请求没带 tools
 * （客户端一次性探测、复核回合、或 tools 在中转环节丢失），模型仍想调工具，这段标记就以
 * 纯文本落进 content —— 下游没有任何一层认识它：Codex 只执行结构化的 function_call，
 * 于是把标记当普通助手文本写进历史，回合直接结束、工具一次都没跑，任务停在半路。
 *
 * 本文件是最后一道防线：识别标记并还原成 tool_calls。判定分两级（因为「客户端声明了哪些
 * 工具」在链路上并不可靠）：
 *  - 严格判定（名单非空）：块内每个 invoke 的工具名都必须在名单里；
 *  - 弱判定（名单为空）：只要求块结构完美闭合、不夹带正文、工具名形如合法标识符。
 *
 * 任何一条前提不满足 → **原文逐字节回吐**，绝不吞字节。
 */
object Dsml {

    /** 标记分隔符：两个全角竖线 U+FF5C。 */
    const val PIPE: String = "\uFF5C\uFF5C"

    const val OPEN: String = "<" + PIPE + "DSML" + PIPE + " calls>"
    const val CLOSE: String = "</" + PIPE + "DSML" + PIPE + " calls>"
    const val INVOKE_CLOSE: String = "</" + PIPE + "DSML" + PIPE + " invoke>"
    const val PARAM_CLOSE: String = "</" + PIPE + "DSML" + PIPE + " parameter>"

    private val TAG_PREFIX = Regex.escape("<" + PIPE + "DSML" + PIPE)
    private val INVOKE_RE = Regex(TAG_PREFIX + """\s+invoke\s+name="([^"]*)"\s*>""")
    private val PARAM_RE = Regex(TAG_PREFIX + """\s+parameter\s+name="([^"]*)"(?:\s+string="(true|false)")?\s*>""")

    /** 工具名的合理形态；弱判定用它挡住模型在正文里「讨论」这段语法时写出的占位符。 */
    private val TOOL_NAME_RE = Regex("""^[A-Za-z0-9_][A-Za-z0-9_.:-]{0,63}$""")

    /** 单个标记块的累积上限；超限未闭合即判定「这不是标记」。 */
    private const val MAX_BLOCK = 256 * 1024

    /** 一次还原出来的工具调用。 */
    data class Call(val name: String, val arguments: String)

    /**
     * 从客户端请求体里收集工具名（两个来源都要，因为二者会分别缺失）：
     * 1. 本请求声明的工具：`tools[].function.name`、`tools[].name`、`functions[].name`；
     * 2. 历史消息里出现过的调用名：`messages[].tool_calls[].function.name`、`messages[].function_call.name`。
     */
    fun allowlist(body: JsonObject?): Set<String> {
        if (body == null) return emptySet()
        val names = HashSet<String>()
        (body["tools"] as? JsonArray)?.forEach { t ->
            val o = t.objOrNull() ?: return@forEach
            val fn = (o["function"] as? JsonObject)?.get("name")?.primTextOrNull()
            if (!fn.isNullOrBlank()) {
                names.add(fn)
                return@forEach
            }
            val bare = o["name"]?.primTextOrNull()
            if (!bare.isNullOrBlank()) names.add(bare)
        }
        (body["functions"] as? JsonArray)?.forEach { f ->
            f.objOrNull()?.get("name")?.primTextOrNull()?.takeIf { it.isNotBlank() }?.let { names.add(it) }
        }
        (body["messages"] as? JsonArray)?.forEach { m ->
            val o = m.objOrNull() ?: return@forEach
            (o["tool_calls"] as? JsonArray)?.forEach { tc ->
                val fn = (tc.objOrNull()?.get("function") as? JsonObject)?.get("name")?.primTextOrNull()
                if (!fn.isNullOrBlank()) names.add(fn)
            }
            (o["function_call"] as? JsonObject)?.get("name")?.primTextOrNull()
                ?.takeIf { it.isNotBlank() }?.let { names.add(it) }
        }
        return names
    }

    /**
     * 廉价的「要不要算名单」闸门：请求体里根本没有 tool 相关键时直接返回空集，
     * 省掉一次完整遍历（对齐新上游 `toolNamesIfPresent`）。
     */
    fun allowlistIfPresent(body: JsonObject?, rawBody: String): Set<String> =
        if (rawBody.contains("\"tool")) allowlist(body) else emptySet()

    /**
     * 流式/非流式共用的标记修复状态机。**非线程安全**：一条响应流一个实例。
     *
     * @param allowed 客户端声明的工具名（可能为空——tools 声明在中转环节丢失是常态）。
     * @param allowUnknown 名单为空时是否启用弱判定（生产路径恒为 true，对齐新上游）。
     */
    class Repair(
        private val allowed: Set<String>,
        private val allowUnknown: Boolean = true,
    ) {
        private var hold: String = ""
        private var inBlock: Boolean = false
        var seen: Int = 0; private set
        var rejected: Int = 0; private set
        var converted: Int = 0; private set

        val enabled: Boolean get() = allowed.isNotEmpty() || allowUnknown

        /** 喂入一段正文，返回（可立即下发的正文, 本次还原出的调用）。 */
        fun feed(text: String): Pair<String, List<Call>> {
            if (!enabled) return text to emptyList()
            var s = hold + text
            hold = ""
            val emit = StringBuilder()
            val calls = ArrayList<Call>()
            while (true) {
                if (!inBlock) {
                    val i = startCandidate(s)
                    if (i < 0) {
                        emit.append(s)
                        break
                    }
                    if (s.length - i < OPEN.length) {
                        // 起点被截断在增量边界（上游把标记切成了两片）：留到下一片再判定。
                        emit.append(s, 0, i)
                        hold = s.substring(i)
                        break
                    }
                    emit.append(s, 0, i)
                    s = s.substring(i + OPEN.length)
                    inBlock = true
                    continue
                }
                val k = s.indexOf(CLOSE)
                if (k < 0) {
                    if (s.length > MAX_BLOCK) {
                        // 超长未闭合：不是标记，原文回吐并退出块态。
                        emit.append(OPEN).append(s)
                        inBlock = false
                        break
                    }
                    hold = s
                    break
                }
                val inner = s.substring(0, k)
                s = s.substring(k + CLOSE.length)
                inBlock = false
                seen++
                val got = parseBlock(inner, allowed)
                if (got != null) {
                    calls.addAll(got)
                    converted += got.size
                    continue
                }
                // 块内不是纯调用（夹带正文 / 工具名不合规 / 参数畸形）：原文回吐。
                rejected++
                emit.append(OPEN).append(inner).append(CLOSE)
            }
            return emit.toString() to calls
        }

        /** 响应流结束时调用：把仍未判定的尾部缓冲原文交还。 */
        fun flush(): String {
            if (!enabled) return ""
            val out = if (inBlock) OPEN + hold else hold
            hold = ""
            inBlock = false
            return out
        }

        /** 最早的「可能是标记起点」的下标（完整标记或被增量边界截断的前缀）。 */
        private fun startCandidate(s: String): Int {
            for (i in s.indices) {
                if (s[i] != '<') continue
                val rest = s.substring(i)
                if (rest.length >= OPEN.length) {
                    if (rest.startsWith(OPEN)) return i
                    continue
                }
                if (OPEN.startsWith(rest)) return i
            }
            return -1
        }
    }

    /**
     * 解析 `calls` 块内部。成功要求：块内除 invoke 与空白外没有别的字节；每个 invoke 都
     * 闭合；每个 invoke 的参数列表合法；工具名判定通过。任一不满足返回 null（调用方原文回吐）。
     */
    private fun parseBlock(inner: String, allowed: Set<String>): List<Call>? {
        val strict = allowed.isNotEmpty()
        val out = ArrayList<Call>()
        var pos = 0
        while (true) {
            val m = INVOKE_RE.find(inner, pos) ?: break
            if (inner.substring(pos, m.range.first).isNotBlank()) return null
            val name = m.groupValues[1]
            val bodyStart = m.range.last + 1
            val end = inner.indexOf(INVOKE_CLOSE, bodyStart)
            if (end < 0) return null
            val body = inner.substring(bodyStart, end)
            pos = end + INVOKE_CLOSE.length
            val args = parseParams(body) ?: return null
            if (strict) {
                if (name !in allowed) return null
            } else if (!TOOL_NAME_RE.matches(name)) {
                return null
            }
            out.add(Call(name, args))
        }
        if (out.isEmpty()) return null
        if (inner.substring(pos).isNotBlank()) return null
        return out
    }

    /** 解析一个 invoke 内的 parameter 列表，产出 JSON 对象文本。 */
    private fun parseParams(body: String): String? {
        val args = LinkedHashMap<String, JsonElement>()
        var pos = 0
        while (true) {
            val m = PARAM_RE.find(body, pos) ?: break
            if (body.substring(pos, m.range.first).isNotBlank()) return null
            val name = m.groupValues[1]
            val isString = m.groupValues.size > 2 && m.groupValues[2] == "true"
            val valStart = m.range.last + 1
            val end = body.indexOf(PARAM_CLOSE, valStart)
            if (end < 0) return null
            val raw = body.substring(valStart, end)
            pos = end + PARAM_CLOSE.length
            if (isString) {
                args[name] = JsonPrimitive(raw)
                continue
            }
            // 非 string 参数：先按 JSON 解析，失败退化为字符串（值永远不丢）。
            val parsed = runCatching { AppJson.parseToJsonElement(raw.trim()) }.getOrNull()
            args[name] = parsed ?: JsonPrimitive(raw)
        }
        if (body.substring(pos).isNotBlank()) return null
        return AppJson.encodeToString(JsonObject.serializer(), JsonObject(args))
    }

    /** 流式 delta.tool_calls 形态（带 index）；index 用 Int 写入，由调用方决定基准。 */
    fun streamToolCalls(calls: List<Call>, base: Int): JsonArray = JsonArray(calls.mapIndexed { i, c ->
        kotlinx.serialization.json.buildJsonObject {
            put("index", JsonPrimitive(base + i))
            put("id", JsonPrimitive(callId(base + i)))
            put("type", JsonPrimitive("function"))
            put("function", kotlinx.serialization.json.buildJsonObject {
                put("name", JsonPrimitive(c.name))
                put("arguments", JsonPrimitive(c.arguments))
            })
        }
    })

    private fun callId(idx: Int): String =
        "call_" + System.nanoTime() + "_" + idx
}

package com.wb.api.upstream

import com.wb.api.core.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 一个 SSE `data:` 行里塞了多个 JSON 时的拆分器。
 *
 * 上游 2026-11（issue #197）实测：某些中转/网关会把多个事件拼在同一行
 * （`{"choices":[...]}{"choices":[...]}`），客户端按 `\n\n` 切帧后整块反序列化失败，
 * 直接丢弃 —— 表现为「偶发丢字、工具调用消失」。上游 `internal/upstream/sse_frame_split.go`
 * 的做法是：**整行能解析就不拆**，解析失败才逐字符扫描按「顶层值边界」切开，
 * 任一片不合法就整份放弃（绝不改写字节）。
 *
 * 这里的实现与上游同一判据：
 *  - 字符串内的 `{}`/`[]` 不计深度（否则正文里出现花括号就会被切坏）；
 *  - 反斜杠转义的下一个字符无条件吞掉（`\"` 不结束字符串）；
 *  - 深度回到 0 即切一刀；深度为负立刻放弃；
 *  - 片数上限 [MAX_PARTS]，超出放弃（防止畸形输入把线程拖住）。
 */
object SseSplit {

    /** 单行最多拆出的片数。 */
    private const val MAX_PARTS = 64

    /**
     * @return 解析成功的事件列表（原文本 + 对象）。整行是一个合法 JSON 时返回单元素；
     *         拆不出来时返回空列表，调用方按「无法解析的帧」跳过（与既有行为一致）。
     */
    fun splitFrames(payload: String): List<Pair<String, JsonObject>> {
        val single = runCatching { AppJson.parseToJsonElement(payload).jsonObject }.getOrNull()
        if (single != null) return listOf(payload to single)
        val parts = scan(payload)
        if (parts.size < 2) return emptyList()
        // 任一片不是 JSON 对象就整份放弃：宁可原样透传（调用方按「无法解析」跳过），
        // 也不要把「多数帧 + 少数丢帧」当成成功 —— 那会让 usage / tool_calls 静默缺失。
        val parsed = ArrayList<Pair<String, JsonObject>>(parts.size)
        for (p in parts) {
            val o = runCatching { AppJson.parseToJsonElement(p).jsonObject }.getOrNull() ?: return emptyList()
            parsed += p to o
        }
        return parsed
    }

    /** 扫描并按顶层值边界切片；任何不确定都返回空列表（调用方回退到原样处理）。 */
    fun scan(raw: String): List<String> {
        val t = raw.trim()
        if (t.isEmpty()) return emptyList()
        val out = ArrayList<String>(2)
        val n = t.length
        var i = 0
        while (i < n) {
            while (i < n && (t[i].isWhitespace() || t[i] == ',')) i++
            if (i >= n) break
            val start = i
            var depth = 0
            var inStr = false
            var esc = false
            var end = -1
            while (i < n) {
                val c = t[i]
                if (inStr) {
                    when {
                        esc -> esc = false
                        c == '\\' -> esc = true
                        c == '"' -> {
                            inStr = false
                            if (depth == 0) {
                                end = i + 1
                                i++
                                break
                            }
                        }
                    }
                } else {
                    when {
                        c == '"' -> inStr = true
                        c == '{' || c == '[' -> depth++
                        c == '}' || c == ']' -> {
                            depth--
                            // 深度为负 = 结构不配对（尾部多余的闭合括号），整份放弃。
                            if (depth < 0) return emptyList()
                            if (depth == 0) {
                                end = i + 1
                                i++
                                break
                            }
                        }
                        depth == 0 && (c == ',' || c.isWhitespace()) -> {
                            end = i
                            break
                        }
                    }
                }
                i++
            }
            // 扫到末尾仍未闭合（半截值）→ 整份放弃：宁可原样透传也不吐半个 JSON。
            if (end < 0) return emptyList()
            val part = t.substring(start, end).trim()
            if (part.isEmpty()) return emptyList()
            if (runCatching { AppJson.parseToJsonElement(part) }.isFailure) return emptyList()
            out += part
            if (out.size > MAX_PARTS) return emptyList()
            if (end <= start) return emptyList()
            i = end
        }
        return out
    }
}

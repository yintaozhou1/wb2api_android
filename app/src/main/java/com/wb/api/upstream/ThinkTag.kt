package com.wb.api.upstream

/**
 * 把混进正文的思考标签拆回 thinking 通道。
 *
 * 上游 2026-11（issue #194）实测：某些模型会把 `<think:SUF>` / `</think:SUF>`（SUF 是
 * checkpoint 后缀，如 `6124c78e`）直接写进 `delta.content`，客户端就把它们当正文渲染出来。
 * 上游 `internal/upstream/thinktag.go` 的处理口径（这里逐条对齐）：
 *
 *  1. **只有正文以 `<think:` 开头才启用**：正文中段出现该串不改写（那时它多半是用户/模型
 *     在讨论标签本身，改动就是破坏内容）。
 *  2. 标签本体（到 `>` 为止）整段丢弃，`inTag` 取反：标签内文本进 thinking，标签外进正文。
 *  3. 跨 chunk：尾部可能是标签前缀（最多 7 字符）时先挂住，等下一片再判；找到标签但还没有
 *     `>` 时整段挂住。流结束调用 [flush] 时按正文吐出（宁少改一次，不丢内容）。
 *
 * 注意：`<think>`（无冒号）不处理 —— 上游同样只认带冒号的两串。
 */
class ThinkTag {

    private val open = "<think:"
    private val close = "</think:"

    /** 是否已判定要处理（首片以 `<think:` 开头，或首片还是它的前缀）。 */
    private var decided = false

    /** 判定结果：为 false 时 [feed] 直接透传，零开销。 */
    var enabled = false
        private set

    /** 当前是否处于「思考标签内」。 */
    private var inTag = false

    private val buffer = StringBuilder()

    /**
     * 喂一片正文，返回 (thinking, body) 两段（任一可为空）。
     *
     * 未启用时原样返回 body，且不产生任何分配之外的行为差异。
     */
    fun feed(chunk: String): Pair<String, String> {
        if (!decided) {
            // 首片太短且仍是 open 的前缀：先挂住，等下一片再判定。
            if (buffer.isEmpty() && chunk.length < open.length && open.startsWith(chunk)) {
                buffer.append(chunk)
                return "" to ""
            }
            buffer.append(chunk)
            decided = true
            enabled = buffer.startsWith(open)
            if (!enabled) {
                val out = buffer.toString()
                buffer.setLength(0)
                return "" to out
            }
            // 注意：本片已经 append 过了，这里**直接 drain**，不能再 append 第二次
            // （否则首片内容会翻倍：一半进 thinking、一半进正文）。
            return drain(flush = false)
        }
        buffer.append(chunk)
        return drain(flush = false)
    }

    /** 流结束：把挂住的尾巴全部吐出（未闭合标签按正文处理）。 */
    fun flush(): Pair<String, String> {
        if (!decided) {
            // 整个流都没触发 ThinkTag（或只有前缀）→ 一律当正文。
            val out = buffer.toString()
            buffer.setLength(0)
            return "" to out
        }
        if (!enabled) {
            val out = buffer.toString()
            buffer.setLength(0)
            return "" to out
        }
        return drain(flush = true)
    }

    private fun drain(flush: Boolean): Pair<String, String> {
        val think = StringBuilder()
        val body = StringBuilder()
        while (true) {
            val s = buffer.toString()
            if (s.isEmpty()) break
            val io = s.indexOf(open)
            val ic = s.indexOf(close)
            val idx = when {
                io < 0 -> ic
                ic < 0 -> io
                else -> if (io < ic) io else ic
            }
            if (idx < 0) {
                if (flush) {
                    append(if (inTag) think else body, s)
                    buffer.setLength(0)
                    break
                }
                val keep = prefixLen(s)
                append(if (inTag) think else body, s.substring(0, s.length - keep))
                buffer.setLength(0)
                buffer.append(s.substring(s.length - keep))
                break
            }
            append(if (inTag) think else body, s.substring(0, idx))
            val tagEnd = s.indexOf('>', idx)
            if (tagEnd < 0) {
                if (flush) {
                    // 未闭合标签：这段是标签本体（不是正文），一律丢弃 —— 否则客户端会
                    // 看到半截 `<think:SUF` 字面量，且它还会被算进 thinking。
                    buffer.setLength(0)
                    break
                }
                buffer.setLength(0)
                buffer.append(s.substring(idx))
                break
            }
            // 标签本体丢弃，翻转通道状态，继续扫剩余部分。
            inTag = !inTag
            buffer.setLength(0)
            buffer.append(s.substring(tagEnd + 1))
        }
        return think.toString() to body.toString()
    }

    private fun append(sb: StringBuilder, s: String) {
        if (s.isNotEmpty()) sb.append(s)
    }

    companion object {
        /**
         * 非流式一次性拆分：正文不以 `<think:` 开头时原样返回（与流式同一启用判据）。
         * @return (thinking, body)
         */
        fun splitFull(text: String): Pair<String, String> {
            if (!text.startsWith("<think:")) return "" to text
            val t = ThinkTag()
            val a = t.feed(text)
            val b = t.flush()
            return (a.first + b.first) to (a.second + b.second)
        }
    }

    /** 尾部作为标签前缀的最长长度（0 表示不需要挂住）。 */
    private fun prefixLen(s: String): Int {
        val max = minOf(close.length - 1, s.length)
        for (k in max downTo 1) {
            val tail = s.substring(s.length - k)
            if (open.startsWith(tail) || close.startsWith(tail)) return k
        }
        return 0
    }
}

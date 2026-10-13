package com.wb.api.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 全局 JSON：宽松解析、忽略未知键，保证与上游/客户端 JSON 兼容。 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

/** 用于配置回显的美化 JSON。 */
val PrettyJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = true
}

/** 解析 Go 风格时长："30m" / "2h" / "600s" / "1h30m" / "168h" / 纯数字(秒)。返回秒。 */
object Durations {
    fun parse(s: String?, defSeconds: Long = 0): Long {
        if (s.isNullOrBlank()) return defSeconds
        val str = s.trim()
        str.toLongOrNull()?.let { return it }
        var i = 0
        var total = 0L
        var matched = false
        while (i < str.length) {
            val start = i
            while (i < str.length && (str[i].isDigit() || str[i] == '.')) i++
            if (i == start) return defSeconds
            val num = str.substring(start, i).toDoubleOrNull() ?: return defSeconds
            // 读取单位
            val us = i
            while (i < str.length && !str[i].isDigit()) i++
            val unit = str.substring(us, i).trim()
            val mult = when (unit) {
                "", "s", "sec", "secs" -> 1.0
                "ms" -> 0.001
                "m", "min", "mins" -> 60.0
                "h", "hr", "hrs" -> 3600.0
                "d" -> 86400.0
                else -> return defSeconds
            }
            total += (num * mult).toLong()
            matched = true
            if (unit == "ms") { /* keep */ }
        }
        return if (matched) total else defSeconds
    }

    fun format(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val d = seconds / 86400; val h = (seconds % 86400) / 3600
        val m = (seconds % 3600) / 60; val s = seconds % 60
        val sb = StringBuilder()
        if (d > 0) sb.append(d).append("d")
        if (h > 0) sb.append(h).append("h")
        if (m > 0) sb.append(m).append("m")
        if (s > 0 && d == 0L) sb.append(s).append("s")
        return sb.toString().ifEmpty { "0s" }
    }
}

/** 生成随机十六进制 ID，形如 req-xxxxxxxx。 */
object Ids {
    private val hex = "0123456789abcdef".toCharArray()

    /**
     * 密码学安全的随机源。
     *
     * 原实现用 `java.util.Random()` —— 48 位种子的线性同余伪随机，**输出可预测**；
     * 而这里生成的 `apiKey()`（`sk-wb2api-…`）是访问本网关的凭据：序列一旦被推断，
     * 攻击者就能离线枚举出网关密钥。同仓库的 clientToken 早已用 SecureRandom，此处对齐。
     */
    private val rng = java.security.SecureRandom()

    fun randomHex(n: Int): String {
        val sb = StringBuilder(n)
        repeat(n) { sb.append(hex[rng.nextInt(16)]) }
        return sb.toString()
    }
    fun requestId(): String = "req-" + randomHex(8)
    fun apiKey(): String = "sk-wb2api-" + randomHex(32)
}


// ─────────────────────────────────────────────────────────────────────────────
// 安全读取 primitive 文本
//
// 为什么必须走这里而不是直接用 `jsonPrimitive`：kotlinx.serialization 的
// `JsonElement.jsonPrimitive` 在元素**不是 JsonPrimitive**（对象 / 数组）时**抛
// IllegalArgumentException**（`?.` 只防 null，不防类型不符）；而它抛出的消息在 release 包里
// 因 R8 剥离 kotlin-reflect 会变成
//     `Element class pn0 (Kotlin reflection is not available) is not a JsonPrimitive`
// 直接冒到调用方 —— 这正是「/v1/responses 连续对话后 500」的现场。
//
// 网关面对的是**任意客户端**：Responses API 里 `content` / `output` / `summary` 这些字段
// 在多轮场景下本来就是数组（多模态 part、工具结果、推理摘要），被当字符串读就会整请求失败。
// 宽容兜底（读不到当空）永远优于抛异常。
// ─────────────────────────────────────────────────────────────────────────────

/** 安全取 primitive 文本；非 JsonPrimitive → null（与 `jsonPrimitive.content` 同语义，但不抛异常）。 */
fun JsonElement?.primText(): String? = (this as? JsonPrimitive)?.content

/** 同 [primText]，但 JsonNull → null（等价 kotlinx 的 `JsonPrimitive.strOrNull`，且不抛异常）。 */
fun JsonElement?.primTextOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/** 安全取 JsonObject；非对象 → null（与 `jsonObject` 同语义，但不抛异常）。 */
fun JsonElement?.objOrNull(): JsonObject? = this as? JsonObject

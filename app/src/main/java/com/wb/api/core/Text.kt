package com.wb.api.core

/**
 * 按「不切开代理对」的口径截断（日志 / 错误体展示专用）。
 *
 * 为什么需要它：Kotlin 的 `take(n)` 按 **UTF-16 code unit** 切，第 n 个位置恰好落在
 * emoji 等补充平面字符的**高代理项**后面时，会留下一个孤立代理项。这个半截字符串
 *  - 进日志：终端/日志面板显示成 `?`，无法还原；
 *  - 进 JSON（例如把上游错误原文回显给客户端）：严格的 JSON 解析器会拒收孤立代理项，
 *    客户端拿到的是「解析失败」而不是「上游说了什么」。
 *
 * 对齐上游 `fba9e14`（截断上游回执按 rune 边界，别劈开汉字）：上游是 byte 切、我们是
 * code unit 切，问题同源，解法同理 —— 切点落在高代理项后时后退一位。
 */
fun String.safeTake(n: Int): String {
    if (n <= 0) return ""
    if (length <= n) return this
    val end = if (Character.isHighSurrogate(this[n - 1])) n - 1 else n
    return substring(0, end)
}

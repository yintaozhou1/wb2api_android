package com.wb.api.core

/**
 * 展示层脱敏（隐私）。
 *
 * 账号昵称与 UID 属于身份信息：在**账号列表**这类一眼可见的位置默认打码，
 * 「账号详情」这类用户主动查看的单账号视图仍显示原文。日志侧也复用同一套，
 * 做到「非必要不落真值」。纯函数、无状态，任何线程可调用。
 */
object Mask {
    /**
     * UID：保留前 4 位与后 4 位，中间固定 4 个圆点。
     *
     * 过短的 ID 返回**固定 4 点**（而不是按长度生成等量圆点）：后者等于把真实长度精确
     * 暴露出来（原实现 `"•".repeat(s.length)` 与「不暴露真实长度」的注释自相矛盾）。
     */
    fun uid(id: String): String {
        val s = id.trim()
        if (s.isEmpty()) return "—"
        if (s.length <= 8) return "\u2022\u2022\u2022\u2022"
        return s.take(4) + "\u2022\u2022\u2022\u2022" + s.takeLast(4)
    }

    /**
     * 昵称：保留首尾各 1 字、中间打码（长度 <=4 时只留首尾）。
     *
     * 1 字昵称**也打码**：原实现直接返回原文，等于把该账号唯一的身份字符完整暴露
     * （与「非必要不落真值」的脱敏目标冲突）。
     */
    fun name(n: String): String {
        val s = n.trim()
        if (s.isEmpty()) return ""
        if (s.length == 1) return "\u2022"
        if (s.length <= 4) return s.first() + "\u2022" + s.last()
        return s.first() + "\u2022\u2022\u2022" + s.last()
    }
}

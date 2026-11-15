package com.wb.api.core

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * 敏感文本（登录凭证）专用的剪贴板读写。
 *
 * 为什么单独封装（依据 Android 官方「安全剪贴板处理」与 Android 13 行为变更）：
 *  - 敏感内容在 `setPrimaryClip` 之前必须给 `ClipDescription` 打 `EXTRA_IS_SENSITIVE` 标记。
 *    否则 Android 13+ 的系统复制预览与输入法剪贴板面板会把 access_token **明文**展示出来，
 *    肩窥或录屏即可窃取；打了标记后系统只提示「已复制」而不显示内容。
 *  - API 33 用常量 `ClipDescription.EXTRA_IS_SENSITIVE`；更低版本用同一字符串 key 的旧写法
 *    （本项目 minSdk=32，两条分支都要在）。
 *  - 读取剪贴板只在**用户手势触发**时调用（Android 10+ 起后台读取被系统拒绝；本项目只在
 *    用户点「从剪贴板粘贴」时读），且只接受 `text/plain`，不会把图片 / URI 当文本解析。
 */
object SecretClipboard {

    /** 剪贴板标签（供系统与用户辨认来源，不含任何凭证内容）。 */
    private const val LABEL = "wb2api-credentials"

    /** Android 13 之前 `EXTRA_IS_SENSITIVE` 的字符串 key（官方文档给出的兼容写法）。 */
    private const val EXTRA_IS_SENSITIVE_LEGACY = "android.content.extra.IS_SENSITIVE"

    /** 单次读取的文本上限：凭证 JSON 正常不过几十 KB，超过 4MB 视为误操作，直接拒绝。 */
    private const val MAX_READ_CHARS = 4 * 1024 * 1024

    private fun manager(ctx: Context): ClipboardManager? =
        ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    /**
     * 把敏感文本写入剪贴板，并标记为「敏感」。
     *
     * @return 成功返回 true；无剪贴板服务（极少数多用户/受限场景）或系统拒绝写入时返回 false，
     *         由调用方决定提示文案，绝不静默当成功。
     */
    fun copySensitive(ctx: Context, text: String, label: String = LABEL): Boolean {
        if (text.isEmpty()) return false
        val cm = manager(ctx) ?: return false
        val clip = ClipData.newPlainText(label, text)
        // 标记必须挂在 description.extras 上（ClipEntry 包装的也是同一份 ClipData）。
        clip.description.extras = PersistableBundle().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            } else {
                putBoolean(EXTRA_IS_SENSITIVE_LEGACY, true)
            }
        }
        return runCatching { cm.setPrimaryClip(clip) }.isSuccess
    }

    /**
     * 读取剪贴板里的纯文本。
     *
     * 返回 null 的三种情况都属「正常而非异常」，调用方只需提示「剪贴板里没有可用的文本」：
     *  1) 无剪贴板服务 / 剪贴板为空；
     *  2) 当前内容不是 text/plain（图片、URI 等）；
     *  3) 超过 [MAX_READ_CHARS]（多半是误复制了别的大文本）。
     */
    fun readText(ctx: Context): String? {
        val cm = manager(ctx) ?: return null
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return null
        if (clip.itemCount <= 0) return null
        if (!clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) return null
        val item = clip.getItemAt(0)
        // 只取纯文本字段：`coerceToText` 会把 URI/Intent 条目解析成目标内容（甚至读文件），
        // 与「只接受 text/plain」的本意矛盾，也绕过用户意图。
        val text = runCatching { item.text?.toString() }.getOrNull()
        if (text.isNullOrEmpty() || text.length > MAX_READ_CHARS) return null
        return text
    }
}

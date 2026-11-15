package com.wb.api.data

import com.wb.api.core.AppJson
import com.wb.api.core.PrettyJson
import com.wb.api.core.primTextOrNull
import com.wb.api.model.AuthEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 登录凭证「导入 / 导出（复制）」的编解码与校验。
 *
 * 上游权威依据（逐字对齐）：`gosrc/internal/panel/import.go` 的 `importCockpit`
 *  —— 它接收 cockpit tools 导出的 JSON**数组**，字段为
 *  `id / email / uid / nickname / access_token / refresh_token / token_type /
 *   expires_at(毫秒) / domain / dosage_notify_code / payment_type / status /
 *   usage_updated_at / last_checkin_time / checkin_streak / created_at / last_used`；
 *  必填 `uid + access_token + refresh_token`，缺失或 uid 非法则跳过；
 *  `expires_at` 是**毫秒**需转秒，≤0 时兜底为「当前 + 365 天」；
 *  realm 由 `domain` 推断（`workbuddy.ai` 家族 → global，否则 cn，依据 `auth.ResolveRealm`）。
 *
 * 落盘字段口径参考上游 `gosrc/internal/auth/auth.go` 的 `Auth`：
 *  AccessToken / RefreshToken / ExpiresAt / Domain / realm / UID / EnterpriseID / Nickname / DeviceToken。
 * **DeviceToken / EnterpriseID 按同名键解析并落盘**（上游 `Auth` 的字段；此前漏解析，
 * 见 buildRow 注释）；`device_id` 为本项目自有字段，一并透传保存但不出站。
 */
object CredentialTransfer {

    /** 单条导入结果：Ready = 已构造出可落盘的 AuthEntry；Dropped = 跳过并给出原因。 */
    sealed interface Row {
        data class Ready(val entry: AuthEntry, val id: String) : Row
        data class Dropped(val id: String, val reason: String) : Row

        /**
         * 「最小凭证」（只给了 access_token + refresh_token，没给 uid）：
         * uid 交由调用方用 access_token 联网换取后再落盘 —— 这样用户复制/粘贴的字符量最少。
         * [realm] 已按 domain 推断或回退到用户选择的域。
         */
        data class NeedsUid(
            val id: String,
            val accessToken: String,
            val refreshToken: String,
            val realm: String,
            val domain: String,
            val expiresAt: Long,
            val nickname: String,
            /** 设备风控令牌（X-Device-Token）；凭证里给了就一并落盘，缺省为空。 */
            val deviceToken: String = "",
            /** 设备标识；同上。 */
            val deviceId: String = "",
            /** 企业 ID（企业版账号）；同上。 */
            val enterpriseId: String = "",
        ) : Row
    }

    /** 整体解析失败（JSON 非法 / 空数组）时抛出，由调用方转成用户可见报错。 */
    class ParseException(message: String) : Exception(message)

    /** 毫秒时间戳的判定门槛：`1e11` 秒约为公元 5138 年，超过它必然是毫秒（如 2024 ≈ 1.7e12）。 */
    private const val MS_THRESHOLD = 100_000_000_000L

    /**
     * 把粘贴的文本解析成「一条条 cockpit 对象」。
     *
     * 对齐 import.go:62-70 —— 上游只接受**数组**（`json.Unmarshal(raw, &accounts []cockpitAccount)`）；
     * 本实现对「单个对象」也宽容接受（便于从账号详情页复制单条后原样粘回），
     * 其余形态（数字 / 字符串 / 空数组）一律按上游口径判为非法。
     */
    fun parseRows(raw: String): List<JsonObject> = decodeObjects(raw)

    /**
     * 把一段文本解析成「一条条账号对象」，兼容多种导出形态。
     *
     * 对齐上游 import.go 2026-11 版 `decodeCockpitAccounts`（commit 585ae7f2 / cd884503）——
     * 上游此前只认顶层数组，用户从 cockpit 详情页复制的**嵌套/信封/字典**形态一律被判非法。
     * 现在按顺序识别（先命中者胜，递归深度上限 2）：
     *  ① 顶层数组 → 逐条；
     *  ② 信封对象 `{accounts|data|list|items|results|auths: [...] 或 {...}}` → 拆开后逐条
     *     （**不把信封名当 uid**，这是上游专门修过的 bug）；
     *  ③ 字典 `{"<uid>": {...}}`（顶层没有任何账号字段、且所有值都是对象）→ 逐条，
     *     条目自身没有 uid 时才用字典 key 兜底；
     *  ④ 单个扁平对象 / 含 `account`、`auth` 的嵌套对象 → 当一条（由 [buildRow] 展平）。
     *
     * 解析不出来一律抛 [ParseException]，由调用方转成用户可见报错。
     */
    fun decodeObjects(raw: String): List<JsonObject> {
        val text = raw.trim()
        if (text.isEmpty()) throw ParseException("内容为空")
        val el = runCatching { AppJson.parseToJsonElement(text) }
            .getOrElse { throw ParseException("JSON 解析失败：${it.message}") }
        val out = ArrayList<JsonObject>()
        when (el) {
            // 数组元素也递归展开：`[{"data":[{...}]}]`、`[{"<uid>":{...}}]` 这类与单对象
            // 同构的形态不该因为多套了一层数组就整份被丢（上游 decodeCockpitAccounts 同口径）。
            is JsonArray -> el.forEach { if (it is JsonObject) out += unwrap(it, 0) }
            is JsonObject -> out += unwrap(el, 0)
            else -> throw ParseException("未解析到任何账号（内容不是 JSON 对象或数组）")
        }
        // 对齐 import.go 的「empty accounts array」。
        if (out.isEmpty()) throw ParseException("未解析到任何账号（空数组或格式不符）")
        return out
    }

    /** 信封键（上游 import.go 的探测顺序）。 */
    private val ENVELOPE_KEYS = listOf("accounts", "data", "list", "items", "results", "auths")

    /** 「这看起来是一个账号对象」的特征键 —— 用于把字典形态与扁平对象区分开。 */
    private val ACCOUNT_KEYS = setOf(
        "uid", "userId", "user_id", "uin",
        "access_token", "accessToken", "token", "refresh_token", "refreshToken",
        "account", "auth", "domain", "expires_at", "expiresAt", "device_token", "deviceToken",
    )

    private fun unwrap(obj: JsonObject, depth: Int): List<JsonObject> {
        if (depth > 2) return listOf(obj)
        // 先判「这本身就是一个账号」：账号对象里完全可能有个叫 data / items 的业务字段，
        // 若先走信封分支会把整条替换成那个字段（丢 uid/token，报「缺少必填字段」）。
        if (obj.keys.any { it in ACCOUNT_KEYS }) return listOf(obj)
        // ① 信封对象：值是数组 → 逐条；值是单个对象 → 当一条（并继续下钻，可能是字典）。
        for (k in ENVELOPE_KEYS) {
            when (val v = obj[k]) {
                is JsonArray -> return v.filterIsInstance<JsonObject>().flatMap { unwrap(it, depth + 1) }
                is JsonObject -> return unwrap(v, depth + 1)
                else -> Unit
            }
        }
        // ② 字典形态 {"<uid>": {...}}：自身没有账号特征键，且至少有一个对象值。
        //    非对象值（如 "total": 2 这类统计字段）忽略，不影响其余账号的导入。
        val objValues = obj.entries.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }
        if (objValues.isNotEmpty()) {
            return objValues.sortedBy { it.first }.flatMap { (k, v) ->
                // 条目自身有 uid 就以它为准；没有才用字典 key 兜底 —— 但 key 必须是合法 uid
                // 形态（邮箱 / 含空格的中文键注进去只会被判「uid 非法」并丢掉整条）。
                if (v.text("uid", "userId", "user_id", "uin").isBlank() && validUid(k)) {
                    listOf(JsonObject(v + ("uid" to JsonPrimitive(k))))
                } else {
                    listOf(v)
                }
            }
        }
        // ③ 扁平 / 嵌套单条。
        return listOf(obj)
    }

    /**
     * 把嵌套形态 `{account:{...}, auth:{...}}` 展平成一层（内层覆盖外层）。
     * 对齐上游 `decodeCockpitOne` 的第三种尝试；不是嵌套形态时原样返回。
     */
    private fun flatten(obj: JsonObject): JsonObject {
        var cur = obj
        var guard = 0
        while (guard++ < 3) {
            val acc = cur["account"] as? JsonObject
            val auth = cur["auth"] as? JsonObject
            if (acc == null && auth == null) break
            val base: Map<String, kotlinx.serialization.json.JsonElement> = cur - "account" - "auth"
            cur = JsonObject(base + (acc ?: emptyMap()) + (auth ?: emptyMap()))
        }
        return cur
    }

    /**
     * 单条 cockpit 对象 → [AuthEntry]。逐条对齐 import.go:75-112 的必填校验与字段映射。
     *
     * @param fallbackRealm 凭证里**没有** domain 时兜底使用的域（用户勾选 CN / Global）。
     */
    fun buildRow(rawObj: JsonObject, fallbackRealm: String): Row {
        // 嵌套形态（account/auth）先展平；扁平形态原样返回。
        val obj = flatten(rawObj)
        val id = obj.text("id")
        val uid = obj.text("uid", "userId", "user_id", "uin").trim()
        val at = obj.text("access_token", "accessToken", "token").trim()
        val rt = obj.text("refresh_token", "refreshToken").trim()

        // 必填只有 access_token + refresh_token —— 这就是「最小登录凭证」：
        // 有这一对就足以登录（uid 可联网换取、domain 可由用户选择兜底、过期时间兜底 365 天）。
        // 对齐上游 import.go:79-83 的「缺必填 → skipped」，只是把 uid 从必填降为可缺省。
        if (at.isEmpty() || rt.isEmpty()) {
            return Row.Dropped(id, "缺少必填字段（access_token / refresh_token）")
        }

        // 设备风控令牌 / 设备标识 / 企业 ID：对应上游 `auth.Auth` 的 `DeviceToken` 与
        // `EnterpriseID` 两个落盘字段（**上游没有 DeviceID 字段**，这里的 `device_id` 是
        // 本项目 AuthEntry 自带的字段，目前只做透传保存，不参与任何出站头）。
        // 此前这里**完全没有解析它们**，于是「从一台设备导出、在另一台导入」时会静默丢掉
        // `device_token` —— 而国际版 chat 端点的风控更紧，缺它更容易被拦。
        val deviceToken = obj.text("device_token", "deviceToken").trim()
        val deviceId = obj.text("device_id", "deviceId").trim()
        val enterpriseId = obj.text("enterprise_id", "enterpriseId", "orgId").trim()

        val domain = obj.text("domain").trim()
        // 对齐 import.go:91：realm := auth.ResolveRealm("", acc.Domain)。
        // 差异点：上游对「domain 为空」恒判 cn；本实现对无 domain 的手写凭证改用**用户选择的域**兜底。
        // domain 非空时判定口径与上游 / auth.ResolveRealm 完全一致。
        val realm = if (domain.isNotEmpty()) resolveRealm(domain) else fallbackRealm

        // 对齐 import.go:93-97：cockpit 的 expires_at 为毫秒 → 秒；≤0 兜底「当前 + 365 天」。
        // 宽容点：若数值已在「秒」量级（< 1e11）则直接采用，不再除以 1000（上游只做 /1000）。
        val now = System.currentTimeMillis() / 1000
        val rawExp = obj.long("expires_at", "expiresAt")
        val expiresAt = when {
            rawExp == null || rawExp <= 0 -> now + 365L * 24 * 3600
            rawExp >= MS_THRESHOLD -> rawExp / 1000
            else -> rawExp
        }

        // 对齐 import.go:99-102：nickname 为空时回落 email。
        val nickname = obj.text("nickname", "name", "userName", "displayName").trim()
            .ifEmpty { obj.text("email").trim() }

        // uid 缺省（最小凭证）→ 交给调用方用 access_token 联网换取（NeedsUid）。
        // uid 若**给了但格式非法**，属于明确的错误输入，仍按上游口径直接丢弃。
        if (uid.isEmpty()) {
            return Row.NeedsUid(id, at, rt, realm, domain, expiresAt, nickname, deviceToken, deviceId, enterpriseId)
        }
        // 对齐 import.go:84-88 + validImportUID：uid 非法 → skipped。
        if (!validUid(uid)) {
            return Row.Dropped(id, "uid 非法（仅允许字母数字与 - _，且 ≤ 64 字符）")
        }

        return Row.Ready(
            entry = AuthEntry(
                uid = uid,
                nickname = nickname,
                realm = realm,
                access_token = at,
                refresh_token = rt,
                access_expires_at = expiresAt,
                device_token = deviceToken,
                device_id = deviceId,
                enterprise_id = enterpriseId,
                domain = domain,
                created_at = now,
                updated_at = now,
            ),
            id = id,
        )
    }

    /**
     * 导出为**与上游 cockpitAccount 同名字段**的单个 JSON 对象（用于「复制登录凭证」）。
     *
     * 与导入端对称：`expires_at` 采用 cockpit 口径的**毫秒**；仅输出账号详情页能提供、
     * 且上游 `auth.Auth` 落盘口径覆盖的字段（uid / nickname / access_token / refresh_token /
     * domain / expires_at），其余 cockpit 专有字段（token_type 之外）不臆造。
     */
    /**
     * 批量导出：输出**带元数据**的 JSON 数组（uid / nickname / domain / expires_at + 一对 token）。
     *
     * 与单条 [encode] 的取舍不同：单条复制只给最小凭证（越短越省事、暴露面越小）；批量导出
     * 是「换机 / 备份」场景，必须带 uid 才能分辨账号、带 expires_at 才能保留有效期，
     * 因此这里输出完整但**仍只含上游 auth.Auth 口径**的字段（不臆造 cockpit 专有字段）。
     */
    fun encodeBulk(entries: List<AuthEntry>): String {
        val arr = entries.map { toJson(it, withMeta = true) }
        return PrettyJson.encodeToString(JsonArray.serializer(), JsonArray(arr))
    }

    private fun toJson(a: AuthEntry, withMeta: Boolean): JsonObject = buildJsonObject {
        if (withMeta) {
            put("uid", JsonPrimitive(a.uid))
            if (a.nickname.isNotBlank()) put("nickname", JsonPrimitive(a.nickname))
            val d = if (a.domain.isNotBlank()) a.domain else if (a.realm == "global") "workbuddy.ai" else "codebuddy.cn"
            put("domain", JsonPrimitive(d))
            // 与导入端对称：cockpit 口径的毫秒；无有效期则省略（导入侧会兜底 365 天）。
            if (a.access_expires_at > 0) put("expires_at", JsonPrimitive(a.access_expires_at * 1000L))
        }
        put("access_token", JsonPrimitive(a.access_token))
        put("refresh_token", JsonPrimitive(a.refresh_token))
        if (a.device_token.isNotBlank()) put("device_token", JsonPrimitive(a.device_token))
        if (a.device_id.isNotBlank()) put("device_id", JsonPrimitive(a.device_id))
        if (a.enterprise_id.isNotBlank()) put("enterprise_id", JsonPrimitive(a.enterprise_id))
    }

    /** 导出文件名（SAF 预填用）：`wb2api-credentials-20261115-1730.json`。 */
    fun suggestFileName(prefix: String = "wb2api-credentials"): String {
        val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        return "$prefix-$ts.json"
    }

    fun encode(a: AuthEntry): String {
        // ★ 只导出**最小可用凭证**（一对 token）。uid / domain / expires_at 都能在导入侧自动补齐
        //   （uid 用 access_token 联网换取、domain 走用户选择、过期时间兜底 365 天），
        //   所以不再把一长串元数据塞给用户 —— 复制更短、粘贴更省事、暴露面也更小。
        val o = buildJsonObject {
            put("access_token", JsonPrimitive(a.access_token))
            put("refresh_token", JsonPrimitive(a.refresh_token))
            // 设备风控令牌 / 设备 ID / 企业 ID：**有才带**。带上它们才能把「已登录的设备身份」
            // 一起迁移 —— 国际版（global）chat 的风控校验更紧，缺 device_token 更容易被网关拦下；
            // 没有则维持最小凭证形态（更短、暴露面更小）。
            if (a.device_token.isNotBlank()) put("device_token", JsonPrimitive(a.device_token))
            if (a.device_id.isNotBlank()) put("device_id", JsonPrimitive(a.device_id))
            if (a.enterprise_id.isNotBlank()) put("enterprise_id", JsonPrimitive(a.enterprise_id))
        }
        return PrettyJson.encodeToString(JsonObject.serializer(), o)
    }

    /** 对齐 auth.go:195 `isGlobalDomain`：`workbuddy.ai` / `*.workbuddy.ai` → global。 */
    fun isGlobalDomain(d: String): Boolean {
        val s = d.trim().lowercase()
        return s == "workbuddy.ai" || s.endsWith(".workbuddy.ai")
    }

    /**
     * 对齐 auth.go:135 `ResolveRealm`（此处只做「按 domain 推断」这一支 —— 导入端的 explicit 恒为空串）。
     */
    fun resolveRealm(domain: String): String = if (isGlobalDomain(domain)) "global" else "cn"

    /** 对齐 import.go:169 `validImportUID`：非空、≤ 64 字符、仅字母数字与 `-` `_`。 */
    fun validUid(uid: String): Boolean {
        if (uid.isEmpty() || uid.length > 64) return false
        return uid.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' }
    }

    /** 宽容取文本（多别名，取第一个非空白）。对齐上游的宽松解析风格。 */
    private fun JsonObject.text(vararg keys: String): String {
        for (k in keys) {
            val v = this[k]?.primTextOrNull()
            if (!v.isNullOrBlank()) return v
        }
        return ""
    }

    /** 宽容取整型（多别名）。数字 / 字符串数字均可。 */
    private fun JsonObject.long(vararg keys: String): Long? {
        for (k in keys) {
            val v = this[k]?.primTextOrNull() ?: continue
            v.toLongOrNull()?.let { return it }
        }
        return null
    }
}

/**
 * 一次批量导入的汇总结果，字段口径对齐上游 import.go:159-165 的返回体
 * `{ok, total, imported, skipped, errors}`。
 */
data class ImportReport(
    val ok: Boolean,
    val total: Int,
    val imported: Int,
    val skipped: Int,
    val errors: List<String> = emptyList(),
    /** 其中「覆盖了本地已有账号」的条数（对齐上游 importCockpit 的 overwrites 口径）。 */
    val overwrites: Int = 0,
) {
    /** 一行摘要（同时用于 toast 与弹层展示）。 */
    fun summary(): String = buildString {
        append("成功 ").append(imported).append(" 个")
        if (overwrites > 0) append("（覆盖 ").append(overwrites).append(" 个）")
        if (skipped > 0) append("，跳过 ").append(skipped).append(" 个")
        append("（共 ").append(total).append(" 条）")
    }
}

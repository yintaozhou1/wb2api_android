package com.wb.api.upstream

import com.wb.api.core.objOrNull
import com.wb.api.core.primTextOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 「客户端 picker 口径」：`/v3/config` 的 `data.agents` 里那个默认 agent 的 `models`
 * 数组，就是官方桌面客户端模型菜单的数据源。
 *
 * 为什么必须按它裁剪（对齐上游 workbuddy2api-panel `internal/upstream/clientpick.go`，
 * 2026-10-29 真机实测，5.9.3 两域）：
 *
 *  - CN   `www.workbuddy.cn` `data.models` 58 条 / `data.agents[cli].models` 16 条
 *  - INTL `www.workbuddy.ai` `data.models` 25 条 / `data.agents[cli].models` 23 条
 *
 * `data.models` 是**平台全量目录**（含 codewise-* 补全模型、hy3-b/c/x 这类路由变体、
 * default-1.1/1.2 这类历史别名、已 disabled 的旧模型）。客户端从不把它们全画出来：
 * 菜单项只取 `agents[].models`，再叠三条客户端自己的规则——照单全列等于把一堆客户端
 * 根本不显示、点了还会 11102 的内部 id 暴露给下游，用户侧表现为「模型列表里混进
 * 一堆不可用 / 过时的模型」。
 *
 * 客户端侧三条规则：
 *  1. 只列默认 agent 的 models（tags 含 default → name==cli → 首个 models 非空）。
 *  2. `ModelRateLimitCap.lines[].paidId` 不单列：客户端把它并进 freeId 那一行，
 *     免费额度撞顶后再切过去。
 *  3. 快速 / 均衡 / 极致（fast-model / balanced-model / deep-model）是客户端的速度
 *     预设，即使不在 `agents[].models` 里也照样出现在菜单顶部；`auto` / `auto-chat`
 *     是「自动模式」开关本身，不是菜单行。
 *
 * 口径缺失即不裁剪（保留上游全量）：上游没下发 agents 时（老版本 / 私有化部署），
 * 无从得知客户端会显示什么，此时宁可照旧全列也不臆造白名单。
 */
internal class ClientPick private constructor(
    /** 默认 agent 的 models 原序。 */
    private val order: List<String>,
    /** ModelRateLimitCap 的 paidId 集合（不单列）。 */
    private val paid: Set<String>,
) {
    /**
     * 报告 id 是否属于客户端菜单会显示的那一批。
     * 与 [ordered] 共用同一套判据，避免「列表过滤了但点名仍可调」的不对称。
     */
    fun allows(id: String): Boolean {
        val v = id.trim()
        if (v.isEmpty() || v in AUTO_MODE_IDS || v in paid) return false
        return v in orderSet || v in PRESET_IDS
    }

    private val orderSet: Set<String> by lazy { order.toSet() }

    /**
     * 按客户端菜单顺序返回可见 id（只保留 [byId] 里真实存在的条目——目录缺条的 id
     * 不臆造）。顺序规则：`[不在 agent 名单里的速度预设] + [agent 原序过滤后]`。
     *
     * CN 的 agents 里没有 fast/balanced/deep，故三个预设被前置；INTL 的 agents
     * 本身就含 fast-model/balanced-model，预设已在名单内不再前置（否则会把
     * Fast/Balanced 顶到 Auto 前面，id 集合对、顺序错）。
     */
    fun ordered(byId: Map<String, JsonObject>): List<String> {
        val out = ArrayList<String>(order.size + PRESET_IDS.size)
        val seen = HashSet<String>(order.size + PRESET_IDS.size)
        fun add(id: String) {
            if (id in seen || !allows(id)) return
            if (!byId.containsKey(id)) return
            seen.add(id)
            out.add(id)
        }
        for (p in PRESET_IDS) if (p !in orderSet) add(p)
        for (id in order) add(id)
        return out
    }

    companion object {
        /**
         * 客户端当作「自动模式」开关、不画成菜单行的 id。
         * 企业端点探测（`fetchEnterpriseModels`）与 picker 口径共用这一份判据。
         */
        val AUTO_MODE_IDS: Set<String> = setOf("auto", "auto-chat")

        /** 客户端的三个速度预设（菜单顶部「快速 / 均衡 / 极致」），顺序即客户端顺序。 */
        val PRESET_IDS: List<String> = listOf("fast-model", "balanced-model", "deep-model")

        /** 从 `/v3/config` 的 data 对象解析出 picker 口径；口径缺失返回 null（不裁剪）。 */
        fun parse(data: JsonObject): ClientPick? {
            val agents = (data["agents"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
            val agent = defaultAgent(agents) ?: return null
            val order = (agent["models"] as? JsonArray)
                ?.mapNotNull { it.primTextOrNull()?.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            if (order.isEmpty()) return null
            // 免费额度撞顶后的付费变体：客户端把它并进 freeId 那一行，不单列。
            val lines = data["productFeaturesConfig"].objOrNull()
                ?.get("ModelRateLimitCap").objOrNull()
                ?.get("lines") as? JsonArray
            val paid = lines
                ?.mapNotNull { (it as? JsonObject)?.get("paidId")?.primTextOrNull()?.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                .orEmpty()
            return ClientPick(order, paid)
        }

        /** 客户端选默认 agent 的同一条优先级：tags 含 default → name==cli → 首个 models 非空。 */
        private fun defaultAgent(agents: List<JsonObject>): JsonObject? {
            for (a in agents) {
                val tags = (a["tags"] as? JsonArray)?.mapNotNull { it.primTextOrNull() }.orEmpty()
                if (tags.any { it.trim().equals("default", ignoreCase = true) }) return a
            }
            for (a in agents) {
                if (a["name"]?.primTextOrNull()?.trim().orEmpty().equals("cli", ignoreCase = true)) return a
            }
            return agents.firstOrNull {
                ((it["models"] as? JsonArray)?.size ?: 0) > 0
            }
        }
    }
}

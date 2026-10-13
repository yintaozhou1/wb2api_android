package com.wb.api.server

import com.wb.api.Gateway
import com.wb.api.model.AuthEntry
import com.wb.api.upstream.desktopAutomationCreateEvent
import com.wb.api.upstream.desktopBuddyAppSequence
import com.wb.api.upstream.desktopChatSequence
import com.wb.api.upstream.desktopDesignCanvasSequence
import com.wb.api.upstream.desktopPlaybookPromptSequence
import com.wb.api.upstream.desktopTemplateUseSequence
import com.wb.api.upstream.jsonOf
import kotlinx.coroutines.delay

/**
 * GrowthActions.kt —— 成长任务「自动化动作表」。
 *
 * 依据：Go internal/panel/autotask.go（`autoActions`，autotask.go:50-177）与
 * internal/panel/autotask.go:740-1106 各 `run*` 实现。
 *
 * 移植范围（对齐 spec §5.2）：只登记**零真实对话、纯行为事件上报**即可完成的任务，
 * 即 CLI/billing /v2/report（chat_5、first_buddy）+ 桌面指纹（RichMeow/Buddy_App/
 * automation_1/template_5/playbook_prompt/create_canvas/Hp_Appearance）+ web 指纹（Library_read）。
 *
 * **未登记**（需真实对话 / 小程序 / 专家链路，属增量）：
 *  - `Model_chat_GLM5.2` / `black_cat` / `skill_1` / `Expert_lighthouse`：需 glm-5.2 或 fast-model
 *    真实对话（ChatStream）并回读服务端 requestId；
 *  - `expert_5` / `Expert_team_use_3`：需专家市场真实专家 + 真实对话；
 *  - `school_season` / `Sequential_Tasks_1`~`7`（mp 口径）：判据只能由 **mini chat 事件**点亮，
 *    且必须遵守 `mpChatEventGap=45s(+0~10s)` 真人节奏，数秒级连发会被上游反作弊**整体回滚**
 *    （autotask.go:320-326 实测：2s 连发 4 条全灭，45s 逐条全存活）。在移动端一键流程里跑
 *    45s×N 的等待链风险过高（用户长时间无反馈、易中断），故**不实施 mini chat 事件上报**；
 *    mp 口径仅实现「列表合并去重 + accept 回读验证 + claim（含 chat 域 400 降级 Web 域）」。
 *  - `Expert_Philanthropy`（真实捐款）不移植。
 *
 * 每个动作：执行步骤 = run；完成判据 = 回读任务进度（由 GrowthService.runGrowthItem 统一做，
 * 不走「上报 200 即成功」）；幂等键 = 任务自身进度（Claimed / current>=target 由引擎跳过，
 * 行为事件按天幂等，README:110）；失败处理 = 单动作异常不阻塞其它任务（引擎逐项 try）。
 */
data class GrowthAction(
    val code: String,
    val desc: String,
    /** true = 尝试型（上游未证实可脚本化，跑了可能不点亮），对齐 Go autoAction.Attempt。 */
    val attempt: Boolean = false,
    val run: suspend (AuthEntry) -> String,
)

object GrowthActions {

    /** 已实现动作表（顺序即执行顺序：先解锁依赖项，对齐 autoActions 顺序）。 */
    val table: List<GrowthAction> = listOf(
        GrowthAction("chat_5", "上报 5 条对话活跃事件（自动补足差额）") { a -> runChat5(a) },
        GrowthAction("first_buddy", "上报解锁 → 同意协议 → 领取第一只 Buddy（+300 分）") { a -> runFirstBuddy(a) },
        GrowthAction("RichMeow_Chat", "桌面指纹对话事件链上报（workbuddy-desktop）") { a -> runRichMeow(a) },
        GrowthAction("Buddy_App", "上报「进入 Buddy 应用」事件链") { a -> runBuddyApp(a) },
        GrowthAction("Buddy_App_QQ", "上报「进入企鹅教师助手」事件链（与 Buddy_App 共用）") { a -> runBuddyApp(a) },
        GrowthAction("automation_1", "上报「定时任务创建」事件（automated_task_create_suc）") { a -> runAutomationCreate(a) },
        GrowthAction("Library_read", "上报「读资料库介绍」事件（web 指纹）") { a -> runLibraryRead(a) },
        GrowthAction("template_5", "上报「使用模板创建任务」事件组 ×5") { a -> runTemplateUse(a) },
        GrowthAction("playbook_prompt", "上报「灵感案例做同款发送 Prompt」事件组") { a -> runPlaybookPrompt(a) },
        GrowthAction("create_canvas", "上报「设计创意画布创建」事件组（+300 分）") { a -> runCreateCanvas(a) },
        GrowthAction("Hp_Appearance", "设置主题 API + 皮肤生效事件") { a -> runAppearance(a) },
    )

    private val byCode = table.associateBy { it.code }

    fun forCode(code: String): GrowthAction? = byCode[code]

    fun ordered(): List<GrowthAction> = table

    // ───────────────────────── 动作实现（逐条对齐 Go run*）─────────────────────────

    /** 补足 chat_5 进度：按差额上报 chat_request_send（对齐 runChat5，autotask.go:748-774）。 */
    private suspend fun runChat5(a: AuthEntry): String {
        val t = GrowthService.taskByCode(a, "chat_5") ?: return "任务未下发"
        var target = GrowthService.target(t); if (target <= 0) target = 5
        val need = target - GrowthService.current(t)
        if (need <= 0) return "进度已达标，无需上报"
        var i = 0L
        while (i < need) {
            val cid = "wb2api-chat5-${System.currentTimeMillis()}-$i"
            Gateway.upstream.reportChatActivity(a, cid, "")
            i++
            if (i < need) delay(GrowthService.REPORT_GAP_MS) // reportGap=1050ms
        }
        return "已补报 $need 条对话事件"
    }

    /** 领养：report（解锁前置）→ agreement → first（对齐 runFirstBuddy，autotask.go:777-792）。 */
    private suspend fun runFirstBuddy(a: AuthEntry): String {
        Gateway.upstream.reportChatActivity(a, "wb2api-adopt-${System.currentTimeMillis()}", "")
        delay(GrowthService.REPORT_GAP_MS)
        Gateway.upstream.buddyAgreement(a)
        val (ok, body) = Gateway.upstream.buddyFirst(a)
        if (ok) return "已领取 Buddy（+300 分 +8 能量）"
        if (body.contains("first_buddy task not completed yet")) return "前置已上报，但领养门槛未过，请稍后重试"
        return "领取 Buddy 未成功：${body.take(80)}"
    }

    /** 桌面端对话事件链（对齐 runRichMeow，autotask.go:835-845）。 */
    private suspend fun runRichMeow(a: AuthEntry): String {
        val ms = System.currentTimeMillis()
        Gateway.upstream.reportDesktopEvent(
            a, desktopChatSequence("wb2api-rm-$ms", "wb2api-rm-req-$ms", "msg-rm-$ms", "fast-model", "fast-model"),
        )
        return "已上报桌面端对话事件链"
    }

    /** buddyapp 五连事件（对齐 runBuddyApp，autotask.go:852-858）。 */
    private suspend fun runBuddyApp(a: AuthEntry): String {
        Gateway.upstream.reportDesktopEvent(a, desktopBuddyAppSequence("cb_y5Dy46tPQGGWtueMxXbe", "企鹅教师助手"))
        return "已上报 buddyapp 进入五连事件（覆盖 Buddy_App 与 Buddy_App_QQ）"
    }

    /** 定时任务创建事件（对齐 runAutomationCreate，autotask.go:863-869）。 */
    private suspend fun runAutomationCreate(a: AuthEntry): String {
        Gateway.upstream.reportDesktopEvent(a, listOf(desktopAutomationCreateEvent("wb2api 自动化")))
        return "已上报定时任务创建事件"
    }

    /** web 域读资料库事件（对齐 runLibraryRead，autotask.go:874-881）。 */
    private suspend fun runLibraryRead(a: AuthEntry): String {
        val docURL = "https://www.workbuddy.cn/space/d/o0KWYeynteVv06UnAZqIFm"
        Gateway.upstream.reportWebEvent(a, "web_element_click", docURL, "library_doc_intro_click", "WorkBuddy资料库介绍")
        return "已上报资料库介绍阅读事件"
    }

    /** 模板使用事件组 ×5（对齐 runTemplateUse，autotask.go:1009-1022）。 */
    private suspend fun runTemplateUse(a: AuthEntry): String {
        val templates = listOf("1" to "深度研究", "2" to "周报生成", "3" to "竞品分析", "4" to "活动策划", "5" to "代码评审")
        for ((i, tp) in templates.withIndex()) {
            val ms = System.currentTimeMillis()
            Gateway.upstream.reportDesktopEvent(
                a, desktopTemplateUseSequence("wb2api-tpl-$ms-$i", "wb2api-tpl-req-$ms-$i", tp.first, tp.second),
            )
            delay(300) // 组间间隔（autotask.go:1019）
        }
        return "已上报 template_used ×5"
    }

    /** 灵感案例做同款（对齐 runPlaybookPrompt，autotask.go:1026-1035）。 */
    private suspend fun runPlaybookPrompt(a: AuthEntry): String {
        val ms = System.currentTimeMillis()
        Gateway.upstream.reportDesktopEvent(
            a, desktopPlaybookPromptSequence("wb2api-pb-$ms", "wb2api-pb-req-$ms", "pm-gtm-launch-plan", "新产品上市 GTM 发布计划一页纸"),
        )
        return "已上报 playbook_cta_click + playbook_prompt_send"
    }

    /** 设计创意画布创建（对齐 runCreateCanvas，autotask.go:1039-1048）。 */
    private suspend fun runCreateCanvas(a: AuthEntry): String {
        val ms = System.currentTimeMillis()
        Gateway.upstream.reportDesktopEvent(a, desktopDesignCanvasSequence("wb2api-canvas-$ms", "wb2api-canvas-req-$ms"))
        return "已上报 wbx_design_canvas_task_create/open"
    }

    /** 换主题：set API 留痕 + appearance_skin_apply 事件（对齐 runAppearance，autotask.go:991-1004）。 */
    private suspend fun runAppearance(a: AuthEntry): String {
        val themeKey = "theme-tkmw7j" // 和平精英激战金秋（Hp_Appearance 判据主题）
        Gateway.upstream.setAppearanceTheme(a, themeKey)
        delay(2000) // 事件留出主题生效时间（autotask.go:996）
        Gateway.upstream.reportDesktopEvent(
            a, listOf(jsonOf(
                "eventCode" to "appearance_skin_apply", "action" to "apply", "source" to "settings_close",
                "id" to themeKey, "vipLevel" to 0, "series" to "", "type" to "unknown",
            )),
        )
        return "已设置主题并上报皮肤生效事件"
    }
}

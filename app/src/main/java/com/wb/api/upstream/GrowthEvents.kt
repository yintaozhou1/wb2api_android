package com.wb.api.upstream

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * GrowthEvents.kt —— 成长任务「行为事件」构造器（桌面指纹 event 族）。
 *
 * 依据：Go internal/upstream/desktop.go（2026-09-12 抓包实测事件形状）。
 * 每个构造器只产出业务载荷；公共客户端指纹（ideName/extName/machineId/userId…）
 * 由 UpstreamClient.reportDesktopEvent 统一注入（对齐 Go desktopFingerprint 注入语义）。
 */

/** Any? → JsonElement（布尔/数字/字符串/数组/嵌套对象直接映射，其余按字符串兜底）。 */
internal fun toJsonAny(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is Boolean -> JsonPrimitive(v)
    is Int -> JsonPrimitive(v)
    is Long -> JsonPrimitive(v)
    is Double -> JsonPrimitive(v)
    is String -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map { toJsonAny(it) })
    is Map<*, *> -> JsonObject(v.entries.associate { (k, vv) -> k.toString() to toJsonAny(vv) })
    else -> JsonPrimitive(v.toString())
}

/** 便捷构造：jsonOf("eventCode" to "x", "count" to 1)。 */
internal fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.associate { it.first to toJsonAny(it.second) })

/** 由 Map 构造（用于合并后的键值集合，避免 vararg 展开的类型不变性问题）。 */
internal fun jsonOfMap(m: Map<String, Any?>): JsonObject =
    JsonObject(m.mapValues { toJsonAny(it.value) })

/** base 打底、extra 覆盖同名键（Go desktopFingerprint 注入语义）。 */
internal fun mergeJsonObject(base: JsonObject, extra: JsonObject): JsonObject =
    JsonObject(java.util.LinkedHashMap(base).apply { putAll(extra) })

/**
 * 一次「桌面端成功对话」6 事件链（对齐 Go DesktopChatSequence，desktop.go:133-201）：
 * agent_task_created → chat_message_send → chat_request_send → chat_message_response(isSuccessful=true)
 * → chat_message_status → chat_request_response。实测点亮 RichMeow_Chat。
 */
fun desktopChatSequence(conversationId: String, requestId: String, messageId: String, modelId: String, modelName: String): List<JsonObject> {
    val now = System.currentTimeMillis()
    val e1 = jsonOf(
        "eventCode" to "agent_task_created", "source" to "LOCAL", "name" to "working",
        "task_target" to "local", "mode" to "craft",
        "requestModelId" to modelId, "requestModelName" to modelName,
        "has_repo" to false, "repo_type" to "none", "workspace_type" to "empty",
        "has_connector" to false, "connector_types" to emptyList<Any?>(),
        "has_mention" to false, "mention_types" to emptyList<Any?>(),
        "has_template" to false, "action" to "", "template_name" to "",
        "has_expert" to false, "expert_id" to "", "expert_name" to "", "expert_industry_id" to "",
        "has_skill" to false, "skill_names" to emptyList<Any?>(),
        "conversationId" to conversationId, "messageId" to messageId,
        "buddyId" to "", "buddyName" to "",
    )
    val e2 = jsonOf(
        "eventCode" to "chat_message_send", "messageId" to "$messageId-assistant",
        "historyCount" to 0, "isContextTruncated" to false, "currentStepCount" to 1,
        "traceId" to requestId, "rootRequestId" to requestId, "parentConversationId" to conversationId,
        "agentName" to "cli", "agentType" to "main",
    )
    val e3 = jsonOf(
        "eventCode" to "chat_request_send", "inputLength" to 24,
        "isPlan" to false, "isAutoExecuteTerminal" to false, "isAutoModify" to false,
        "codebaseEnable" to false, "maxToken" to 0, "maxSteps" to 500, "temperature" to 0, "maxRetries" to 0,
        "mentionContexts" to emptyList<Any?>(), "knowledgeId" to emptyList<Any?>(), "knowledgeName" to emptyList<Any?>(),
        "codebaseId" to "", "mentionContextCount" to 0, "command" to "", "recommendId" to "",
        "skillId" to "", "skillCount" to 0, "totalCount" to 0,
        "traceId" to requestId, "rootRequestId" to requestId, "parentConversationId" to conversationId,
        "agentName" to "cli", "agentType" to "main",
        "codebuddy.session_id" to conversationId, "codebuddy.conversation_request_id" to requestId,
    )
    val e4 = jsonOf(
        "eventCode" to "chat_message_response", "messageId" to "$messageId-assistant",
        "responseModelId" to modelId, "inputToken" to 120, "outputToken" to 80, "totalToken" to 200,
        "cachedTokens" to 0, "cachedWriteTokens" to 0, "cachedMissTokens" to 0,
        "isSuccessful" to true, "messageErrorCode" to "", "finishReason" to "stop",
        "firstTokenAt" to now, "traceId" to requestId, "conversationId" to conversationId,
        "rootRequestId" to requestId, "parentConversationId" to conversationId,
        "agentName" to "cli", "agentType" to "main",
        "codebuddy.session_id" to conversationId, "codebuddy.conversation_request_id" to requestId,
    )
    val e5 = jsonOf(
        "eventCode" to "chat_message_status", "messageId" to "$messageId-assistant",
        "messageErrorCode" to "0", "traceId" to requestId, "rootRequestId" to requestId,
        "parentConversationId" to conversationId, "agentName" to "cli", "agentType" to "main",
    )
    val e6 = jsonOf(
        "eventCode" to "chat_request_response", "mode" to "craft", "toolCallCount" to 0,
        "inputToken" to 120, "outputToken" to 80, "totalToken" to 200,
        "cachedTokens" to 0, "cachedWriteTokens" to 0, "cachedMissTokens" to 0,
        "isSuccessful" to true, "messageErrorCode" to "", "finishReason" to "stop",
        "rootRequestId" to requestId, "parentConversationId" to conversationId,
    )
    return listOf(e1, e2, e3, e4, e5, e6)
}

/**
 * 「进入 Buddy 应用」五连事件（对齐 Go DesktopBuddyAppSequence，desktop.go:232-250）：
 * discover → show → enter_click → auth_confirm → bindaccount_skip。
 * 用企鹅教师助手（Buddy_App_QQ 判据应用）作载体，同一组事件同时满足 Buddy_App。
 */
fun desktopBuddyAppSequence(buddyId: String, buddyName: String): List<JsonObject> {
    fun ev(code: String, extra: Map<String, Any?>): JsonObject {
        val m = LinkedHashMap<String, Any?>()
        m["eventCode"] = code; m["mode"] = "LOCAL"; m["buddyId"] = buddyId; m["buddyName"] = buddyName
        m.putAll(extra)
        return jsonOfMap(m)
    }
    return listOf(
        ev("buddyapp_discover_click", emptyMap()),
        ev("buddyapp_show", mapOf("elementId" to buddyId, "elementName" to buddyName, "position" to 2)),
        ev("buddyapp_enter_click", mapOf("elementId" to buddyId, "elementName" to buddyName, "position" to 2, "isFirstPage" to "1")),
        ev("buddyapp_auth_confirm_click", mapOf("elementId" to buddyId, "elementName" to buddyName)),
        ev("buddyapp_bindaccount_skip_click", mapOf("elementId" to buddyId, "elementName" to buddyName)),
    )
}

/** 「定时任务创建成功」事件（对齐 Go DesktopAutomationCreateEvent，desktop.go:254-261）。 */
fun desktopAutomationCreateEvent(name: String): JsonObject = jsonOf(
    "eventCode" to "automated_task_create_suc", "name" to name,
    "source" to "manually", "modelId" to "fast-model", "modelIsThinking" to true,
    "connectorCount" to 0, "skills" to "", "skillCount" to 0,
    "scheduleType" to "once", "mode" to "LOCAL",
)

/** 「使用模板创建任务」事件组（对齐 Go DesktopTemplateUseSequence，desktop.go:307-317）。 */
fun desktopTemplateUseSequence(conversationId: String, requestId: String, templateId: String, templateName: String): List<JsonObject> {
    val events = desktopChatSequence(conversationId, requestId, "msg-$templateId", "fast-model", "fast-model").toMutableList()
    events.add(jsonOf(
        "eventCode" to "agent_task_created_with_template", "mode" to "working",
        "isCustomModel" to false, "id" to templateId, "name" to templateName, "requestId" to requestId,
    ))
    events.add(jsonOf("eventCode" to "template_used", "template_id" to templateId, "task_mode" to "working"))
    return events
}

/** 「灵感案例做同款」事件组（对齐 Go DesktopPlaybookPromptSequence，desktop.go:322-349）。 */
fun desktopPlaybookPromptSequence(conversationId: String, requestId: String, caseId: String, caseName: String): List<JsonObject> {
    val events = desktopChatSequence(conversationId, requestId, "msg-pb", "fast-model", "fast-model").toMutableList()
    val payload: Map<String, Any?> = mapOf("id" to caseId, "name" to caseName, "type" to "document", "categoryId" to "", "categoryName" to "")
    events.add(jsonOf(
        "eventCode" to "web_element_click", "pageName" to "playbook_detail",
        "elementId" to "playbook_ctaClick", "elementName" to caseName, "source" to "discover",
    ))
    events.add(jsonOfMap(mapOf<String, Any?>("eventCode" to "playbook_cta_click", "source" to "discover", "position" to 0) + payload))
    events.add(jsonOfMap(mapOf<String, Any?>("eventCode" to "playbook_prompt_send", "conversationId" to conversationId, "requestId" to requestId) + payload))
    return events
}

/** 「设计创意画布」事件组（对齐 Go DesktopDesignCanvasSequence，desktop.go:354-367）。 */
fun desktopDesignCanvasSequence(conversationId: String, requestId: String): List<JsonObject> {
    val events = desktopChatSequence(conversationId, requestId, "msg-canvas", "fast-model", "fast-model").toMutableList()
    val tail = if (requestId.length >= 8) requestId.substring(requestId.length - 8) else requestId
    events.add(jsonOf(
        "eventCode" to "wbx_design_canvas_task_create", "conversationId" to conversationId,
        "requestId" to requestId, "source" to "summon_keyword", "cost" to 12000, "isSuccessful" to true,
    ))
    events.add(jsonOf(
        "eventCode" to "wbx_design_canvas_open", "conversationId" to conversationId,
        "requestId" to requestId, "id" to "ardot-file-$tail",
        "source" to "summon_keyword", "type" to "page", "cost" to 13000, "isSuccessful" to true,
    ))
    return events
}

package com.wb.api.server

import com.wb.api.Gateway
import com.wb.api.core.AppJson
import com.wb.api.core.GatewayConfig
import com.wb.api.core.LogBus
import com.wb.api.core.PrettyJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/** panel api —— 面板后端接口（与 Go 面板 API 对齐）。 */
object PanelApi {

    fun handle(req: Req, res: Res) {
        val p = req.path.removePrefix("/panel/api/").trimEnd('/')
        val parts = p.split('/').filter { it.isNotEmpty() }
        try {
            when {
                parts.isEmpty() -> res.sendError(404, "not found")
                parts[0] == "overview" -> overview(res)
                parts[0] == "logs" -> logs(res)
                parts[0] == "config" -> if (req.method == "POST") saveConfig(req, res) else getConfig(res)
                parts[0] == "models" -> models(res)
                parts[0] == "usage" -> usage(req, res)
                parts[0] == "request_metrics" -> requestMetrics(res)
                parts[0] == "request_logs" -> requestLogs(req, res)
                parts[0] == "login" && parts.size >= 2 && parts[1] == "start" -> loginStart(req, res)
                parts[0] == "login" && parts.size >= 2 && parts[1] == "poll" -> loginPoll(req, res)
                parts[0] == "checkin_all" -> bulk("checkin", res)
                parts[0] == "balance_all" -> bulk("balance", res)
                parts[0] == "activity_all" -> bulk("activity", res)
                parts[0] == "travel_all" -> bulk("travel", res)
                parts[0] == "keepalive_all" -> bulk("keepalive", res)
                parts[0] == "accounts" && parts.size >= 3 -> accountOp(parts[1], parts.drop(2), req, res)
                parts[0] == "tasks" && parts.size >= 2 && parts[1] == "scan_all" -> scanAll(res)
                else -> res.sendError(404, "unknown panel api: $p")
            }
        } catch (e: Throwable) {
            res.sendError(500, e.message ?: "internal error")
        }
    }

    // ---------- 概览 ----------

    private fun overview(res: Res) {
        val counts = Gateway.pool.counts()
        val obj = buildJsonObject {
            put("version", JsonPrimitive("1.11.11-panel-android"))
            put("uptime_sec", JsonPrimitive(Gateway.uptimeSec()))
            put("running", JsonPrimitive(Gateway.running.value))
            put("port", JsonPrimitive(Gateway.displayPort()))
            put("auth_required", JsonPrimitive(Gateway.store.config.api_key.isNotBlank()))
            put("redis_mode", JsonPrimitive("noop"))
            put("sticky_sessions", JsonPrimitive(Gateway.pool.stickyCount()))
            put("total", JsonPrimitive(counts.total))
            put("healthy", JsonPrimitive(counts.healthy))
            put("cooling", JsonPrimitive(counts.cooling))
            put("disabled", JsonPrimitive(counts.disabled))
            put("in_flight_full", JsonPrimitive(counts.inFlightFull))
            put("accounts", buildJsonArray {
                Gateway.pool.listViews().forEach { add(Router.accountToJson(it)) }
            })
            put("metrics", metricsJson())
        }
        res.sendJson(200, obj)
    }

    private fun metricsJson(): JsonObject {
        val m = Gateway.metrics()
        return buildJsonObject {
            put("completed", JsonPrimitive(m.completed))
            put("success", JsonPrimitive(m.success))
            put("http_success", JsonPrimitive(m.httpSuccess))
            put("avg_duration_ms", JsonPrimitive(m.avgDurationMs))
            put("in_flight", JsonPrimitive(m.inFlight))
            put("success_rate", JsonPrimitive(if (m.completed > 0) m.success.toDouble() / m.completed else 0.0))
        }
    }

    private fun logs(res: Res) {
        val entries = LogBus.snapshot()
        val obj = buildJsonObject {
            put("entries", buildJsonArray {
                entries.forEach { e ->
                    add(buildJsonObject {
                        put("ts", JsonPrimitive(e.ts))
                        put("ch", JsonPrimitive(e.ch.name.lowercase()))
                        put("text", JsonPrimitive(e.text))
                    })
                }
            })
        }
        res.sendJson(200, obj)
    }

    private fun requestMetrics(res: Res) = res.sendJson(200, metricsJson())

    private fun requestLogs(req: Req, res: Res) {
        val limit = req.query["limit"]?.toIntOrNull() ?: 200
        val lines = Gateway.store.readRequestLogs(limit)
        val obj = buildJsonObject {
            put("entries", buildJsonArray {
                lines.forEach { l ->
                    add(runCatching { AppJson.parseToJsonElement(l) }.getOrElse { JsonPrimitive(l) })
                }
            })
            put("limit", JsonPrimitive(limit))
        }
        res.sendJson(200, obj)
    }

    // ---------- 配置 ----------

    private fun getConfig(res: Res) {
        val obj = buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("path", JsonPrimitive("app://config.json"))
            put("config", Gateway.store.configJson())
        }
        res.sendJson(200, obj)
    }

    private fun saveConfig(req: Req, res: Res) {
        val incoming = runCatching { AppJson.parseToJsonElement(String(req.body)).jsonObject }.getOrNull()
        if (incoming == null) { res.sendError(400, "invalid config json"); return }
        val current = Gateway.store.config
        val cfg = runCatching {
            AppJson.decodeFromJsonElement(GatewayConfig.serializer(), mergeConfig(current, incoming))
        }.getOrElse { res.sendError(400, "config validation failed: ${it.message}"); return }
        Gateway.applyConfig(cfg)
        val obj = buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("restart_required", buildJsonArray { add(JsonPrimitive("listen")) })
        }
        res.sendJson(200, obj)
    }

    private fun mergeConfig(current: GatewayConfig, patch: JsonObject): kotlinx.serialization.json.JsonElement {
        val cur = PrettyJson.encodeToJsonElement(GatewayConfig.serializer(), current).jsonObject
        val out = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for ((k, v) in cur) out[k] = v
        for ((k, v) in patch) {
            val base = out[k]
            if (base is JsonObject && v is JsonObject) {
                val m = base.toMutableMap(); m.putAll(v); out[k] = JsonObject(m)
            } else out[k] = v
        }
        return JsonObject(out)
    }

    // ---------- 模型 / 用量 ----------

    private fun models(res: Res) {
        val infos = ModelService.listInfos(force = true)
        val obj = buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("models", buildJsonArray {
                infos.forEach { m ->
                    add(buildJsonObject {
                        put("id", JsonPrimitive(m.id))
                        put("name", JsonPrimitive(m.name))
                        put("description", JsonPrimitive(m.description))
                        put("credits", JsonPrimitive(m.credits))
                        put("vendor", JsonPrimitive(m.vendor))
                        put("realm", JsonPrimitive(m.realm))
                        put("model", JsonPrimitive(m.model))
                        put("is_default", JsonPrimitive(m.is_default))
                        put("supports_images", JsonPrimitive(m.supports_images))
                        put("supports_reasoning", JsonPrimitive(m.supports_reasoning))
                        put("supports_tool_call", JsonPrimitive(m.supports_tool_call))
                        put("default_effort", JsonPrimitive(m.default_effort))
                        put("supported_efforts", buildJsonArray { m.supported_efforts.forEach { add(JsonPrimitive(it)) } })
                        put("context_length", JsonPrimitive(m.context_length))
                        put("max_output_tokens", JsonPrimitive(m.max_output_tokens))
                        put("tags", buildJsonArray { m.tags.forEach { add(JsonPrimitive(it)) } })
                    })
                }
            })
        }
        res.sendJson(200, obj)
    }

    private fun usage(req: Req, res: Res) {
        val records = Gateway.store.readUsage()
        var requests = 0L; var errors = 0L; var pt = 0L; var ct = 0L; var tt = 0L
        records.forEach { r ->
            requests++; if (r.error) errors++
            pt += r.prompt_tokens; ct += r.completion_tokens; tt += r.total_tokens
        }
        val obj = buildJsonObject {
            put("totals", buildJsonObject {
                put("requests", JsonPrimitive(requests))
                put("errors", JsonPrimitive(errors))
                put("prompt_tokens", JsonPrimitive(pt))
                put("completion_tokens", JsonPrimitive(ct))
                put("total_tokens", JsonPrimitive(tt))
            })
            put("window_from", JsonPrimitive(0))
            put("window_to", JsonPrimitive(System.currentTimeMillis() / 1000))
            put("records", JsonPrimitive(records.size))
        }
        res.sendJson(200, obj)
    }

    // ---------- 登录 ----------

    private val loginStates = java.util.concurrent.ConcurrentHashMap<String, String>() // state -> realm

    private fun loginStart(req: Req, res: Res) {
        val realm = req.query["realm"] ?: "cn"
        val da = runBlocking { Gateway.upstream.deviceAuthStart(realm) }
        loginStates[da.state] = realm
        val obj = buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("state", JsonPrimitive(da.state))
            put("auth_url", JsonPrimitive(da.authUrl))
            put("realm", JsonPrimitive(realm))
        }
        res.sendJson(200, obj)
    }

    private fun loginPoll(req: Req, res: Res) {
        val state = req.query["state"] ?: ""
        val realm = req.query["realm"] ?: loginStates[state] ?: "cn"
        val entry = runBlocking { Gateway.upstream.deviceAuthPoll(realm, state) }
        if (entry == null) {
            res.sendJson(200, buildJsonObject { put("status", JsonPrimitive("pending")) })
            return
        }
        Gateway.store.saveAuth(entry)
        Gateway.pool.reload()
        LogBus.log("panel: 登录成功 ${entry.nickname}(${entry.uid.take(8)})")
        res.sendJson(200, buildJsonObject {
            put("status", JsonPrimitive("ok"))
            put("account", buildJsonObject {
                put("uid", JsonPrimitive(entry.uid))
                put("nickname", JsonPrimitive(entry.nickname))
                put("realm", JsonPrimitive(entry.realm))
            })
        })
    }

    // ---------- 账号操作 ----------

    private fun accountOp(uid: String, rest: List<String>, req: Req, res: Res) {
        val op = rest.firstOrNull() ?: run { res.sendError(400, "missing op"); return }
        when (op) {
            "revive" -> { Gateway.pool.revive(uid); ok(res) }
            "disable" -> { Gateway.pool.setDisabled(uid, true); ok(res) }
            "enable" -> { Gateway.pool.setDisabled(uid, false); ok(res) }
            "remove" -> { Gateway.pool.remove(uid); ok(res) }
            "balance" -> {
                val a = Gateway.store.getAuth(uid)
                if (a == null) { res.sendError(404, "account not found"); return }
                val soonSec = com.wb.api.core.Durations.parse(Gateway.store.config.pool.expiring_soon, 168 * 3600L)
                val b = runCatching { runBlocking { Gateway.upstream.balanceDetailed(a, soonSec) } }
                    .getOrElse { com.wb.api.upstream.UpstreamClient.Balance(-1.0) }
                if (b.known) Gateway.pool.setCredits(uid, b.remain, b.earliestAt, b.earliestRemaining)
                res.sendJson(200, buildJsonObject { put("ok", JsonPrimitive(true)); put("credits", JsonPrimitive(b.remain)) })
            }
            "checkin" -> {
                val a = Gateway.store.getAuth(uid)
                if (a == null) { res.sendError(404, "account not found"); return }
                // 企业版门控：上游对企业号不支持签到，直接如实返回，不空发请求、不记失败。
                if (a.isEnterprise) {
                    res.sendJson(200, buildJsonObject {
                        put("ok", JsonPrimitive(false))
                        put("already", JsonPrimitive(false))
                        put("message", JsonPrimitive("企业版账号无签到（上游不支持该操作）"))
                    })
                    return
                }
                val r = runCatching { runBlocking { Gateway.upstream.checkin(a) } }
                if (r.isSuccess) {
                    // 只有成功才记录签到时刻 + 解除冷却；失败时保留上游给的冷却信号。
                    Gateway.pool.markCheckin(uid)
                    Gateway.pool.revive(uid)
                }
                val already = r.getOrNull() == "已签到"
                res.sendJson(200, buildJsonObject {
                    put("ok", JsonPrimitive(r.isSuccess))
                    put("already", JsonPrimitive(already))
                    put("message", JsonPrimitive(
                        r.getOrNull()?.let { if (it == "已签到") "今日已签到" else "签到成功" }
                            ?: (r.exceptionOrNull()?.message ?: "签到失败")
                    ))
                })
            }
            "tasks" -> {
                val a = Gateway.store.getAuth(uid)
                if (a == null) { res.sendError(404, "account not found"); return }
                // 合并 mp 口径并按 task_code 去重（对齐 Go panel/tasks.go:41-54 accountTasks）。
                val tasks = runCatching { runBlocking { Gateway.upstream.growthTasksMerged(a) } }.getOrDefault(emptyList())
                res.sendJson(200, buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("tasks", buildJsonArray { tasks.forEach { add(it) } })
                })
            }
            else -> res.sendError(404, "unknown account op $op")
        }
    }

    private fun scanAll(res: Res) {
        val out = buildJsonArray {
            Gateway.store.auths().forEach { a ->
                val tasks = runCatching { runBlocking { Gateway.upstream.growthTasksMerged(a) } }.getOrDefault(emptyList())
                add(buildJsonObject {
                    put("uid", JsonPrimitive(a.uid))
                    put("nickname", JsonPrimitive(a.nickname))
                    put("tasks", buildJsonArray { tasks.forEach { add(it) } })
                })
            }
        }
        res.sendJson(200, buildJsonObject { put("ok", JsonPrimitive(true)); put("accounts", out) })
    }

    // ---------- 批量任务 ----------

    private fun bulk(op: String, res: Res) {
        val accounts = Gateway.store.auths()
        // 无账号时**明确失败**，而不是回一个「0 个账号、全部 ok」的假成功（用户点完以为成功了）。
        if (accounts.isEmpty()) {
            res.sendJson(200, buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("results", buildJsonArray { })
                put("message", JsonPrimitive("no accounts"))
            })
            return
        }
        if (op !in setOf("checkin", "balance", "activity")) {
            res.sendError(501, "operation '$op' is not implemented by this client", "not_implemented")
            return
        }
        val results = buildJsonArray {
            accounts.forEach { a ->
                val r = runCatching {
                    runBlocking {
                        when (op) {
                            "checkin" -> {
                                if (a.isEnterprise) throw IllegalStateException("企业版无签到（上游不支持）")
                                val v = Gateway.upstream.checkin(a)
                                Gateway.pool.markCheckin(a.uid)
                                if (v == "已签到") "今日已签到" else "签到成功"
                            }
                            "balance" -> {
                                val soonSec = com.wb.api.core.Durations.parse(Gateway.store.config.pool.expiring_soon, 168 * 3600L)
                                val b = Gateway.upstream.balanceDetailed(a, soonSec)
                                if (!b.known) throw IllegalStateException("上游未返回余额数据")
                                Gateway.pool.setCredits(a.uid, b.remain, b.earliestAt, b.earliestRemaining)
                                "ok"
                            }
                            else -> { Gateway.upstream.report(a, buildJsonObject { put("events", buildJsonArray {}) }.toString()); "ok" }
                        }
                    }
                }
                add(buildJsonObject {
                    put("uid", JsonPrimitive(a.uid))
                    put("nickname", JsonPrimitive(a.nickname))
                    put("ok", JsonPrimitive(r.isSuccess))
                    put("message", JsonPrimitive(r.getOrNull() ?: (r.exceptionOrNull()?.message ?: "failed")))
                })
            }
        }
        LogBus.log("panel: $op 全量完成 ${accounts.size} 个账号")
        res.sendJson(200, buildJsonObject { put("ok", JsonPrimitive(true)); put("results", results) })
    }

    private fun ok(res: Res) = res.sendJson(200, buildJsonObject { put("ok", JsonPrimitive(true)) })
}

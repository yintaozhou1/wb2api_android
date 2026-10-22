package com.wb.api.server

import com.wb.api.Gateway
import com.wb.api.core.AppJson
import com.wb.api.core.GatewayConfig
import com.wb.api.core.LogBus
import com.wb.api.core.Ids
import com.wb.api.core.PrettyJson
import com.wb.api.model.UsageRecord
import com.wb.api.pool.AccountPool
import com.wb.api.upstream.Classify
import com.wb.api.upstream.ErrKind
import com.wb.api.upstream.Rewrite
import com.wb.api.upstream.Up
import com.wb.api.upstream.UpstreamException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// ---------------- HTTP 传输层 ----------------

data class Req(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val clientIp: String get() = headers["x-forwarded-for"]?.substringBefore(',')?.trim()
        ?: headers["x-real-ip"] ?: "127.0.0.1"
    fun header(n: String) = headers[n.lowercase()]
}

class Res(private val out: OutputStream) {
    private var sent = false
    private var chunked = false

    private fun statusText(c: Int) = when (c) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"; 500 -> "Internal Server Error"; 501 -> "Not Implemented"
        502 -> "Bad Gateway"; 503 -> "Service Unavailable"; else -> "OK"
    }

    private fun writeHeaders(code: Int, ctype: String, len: Long?, chunked: Boolean, extra: Map<String, String> = emptyMap()) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n")
        sb.append("Content-Type: ").append(ctype).append("\r\n")
        if (chunked) sb.append("Transfer-Encoding: chunked\r\n")
        else if (len != null) sb.append("Content-Length: ").append(len).append("\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Connection: keep-alive\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()
        sent = true
    }

    fun sendBytes(code: Int, bytes: ByteArray, ctype: String, extra: Map<String, String> = emptyMap()) {
        if (sent) return
        writeHeaders(code, ctype, bytes.size.toLong(), false, extra)
        out.write(bytes); out.flush()
    }

    fun sendText(code: Int, text: String, ctype: String = "text/plain; charset=utf-8", extra: Map<String, String> = emptyMap()) =
        sendBytes(code, text.toByteArray(), ctype, extra)

    fun sendJson(code: Int, el: JsonElement, extra: Map<String, String> = emptyMap()) =
        sendBytes(code, AppJson.encodeToString(JsonElement.serializer(), el).toByteArray(), "application/json", extra)

    fun sendError(
        code: Int,
        message: String,
        errCode: String = "api_error",
        hint: String = "",
        extra: Map<String, String> = emptyMap(),
    ) {
        val obj = buildJsonObject {
            put("error", buildJsonObject {
                put("message", JsonPrimitive(message))
                put("type", JsonPrimitive("api_error"))
                put("code", JsonPrimitive(errCode))
                if (hint.isNotBlank()) put("gateway_hint", JsonPrimitive(hint))
            })
        }
        sendJson(code, obj, extra)
    }

    fun beginStream(code: Int = 200, ctype: String = "text/event-stream", extra: Map<String, String> = emptyMap()) {
        if (sent) return
        chunked = true
        writeHeaders(code, ctype, null, true, extra)
    }

    fun chunk(s: String) {
        if (!chunked) return
        // 空串会被编码成 "0\\r\\n\\r\\n" —— 那是 chunked 的**终止符**，会静默关闭流。
        if (s.isEmpty()) return
        val b = s.toByteArray()
        out.write("${Integer.toHexString(b.size)}\r\n".toByteArray())
        out.write(b); out.write("\r\n".toByteArray()); out.flush()
    }

    fun endStream() {
        if (!chunked) return
        out.write("0\r\n\r\n".toByteArray()); out.flush()
        chunked = false
    }

    val isStreaming: Boolean get() = chunked
}

/**
 * 请求体上限。
 *
 * 服务器监听 `0.0.0.0`，同一局域网内任意设备都能发请求，所以「请求体大小」是必须自己兜住的
 * 资源边界：32 MB 远高于正常请求（OpenAI 兼容请求体、含 base64 图片的多模态请求都在其内），
 * 同时把「一个 Content-Length 头就打爆进程堆」这条路封死。
 */
private const val MAX_BODY_BYTES = 32L * 1024 * 1024

/**
 * 单行（请求行 / 请求头）的字节上限。
 *
 * 监听在 `0.0.0.0`，而 [readLine] 原实现逐字节累积、**没有任何上限**：一条不带换行
 * 的 10 GB 请求行（或无限条请求头）就能把堆打满、进程被 OOM 杀掉。body 的
 * [MAX_BODY_BYTES] 只兜住了 body，请求头这一侧同样要有边界。
 * 8 KB 远大于任何正常请求行/头（URL 与 Cookie 的常见上限也在此量级）。
 */
private const val MAX_LINE_BYTES = 8 * 1024

/** 请求头条数上限（同样防「无限头」把内存打满）。 */
private const val MAX_HEADER_COUNT = 128

/** 请求体超过 [MAX_BODY_BYTES] 时由 [EmbeddedHttpServer.readRequest] 抛出，回 413。 */
class RequestTooLargeException : java.io.IOException("request body too large")

/**
 * 同时可处理的连接数上限（BIO 模型：一个连接一个线程，含 keep-alive 长连接）。
 *
 * 取 32 的依据：网关跑在手机上，**流式**请求会各自占住一个线程直到流结束；32 路并发流对移动端
 * 场景足够，同时把线程栈的虚拟内存占用挡在可控范围。达到上限后**不再排队等待**（排队 = 客户端
 * 干等到超时），而是立刻回 503，让调用方明确知道该退避。
 */
private const val HTTP_MAX_THREADS = 32

/** 常驻工作线程数；允许空闲回收（allowCoreThreadTimeOut），静默期不额外占资源。 */
private const val HTTP_CORE_THREADS = 8

class EmbeddedHttpServer(
    private val portProvider: () -> Int,
    /**
     * 账号维度并发描述（例如「单账号 5 / global 7」），**只用于启动日志**。
     *
     * 存在的理由：「连接并发」（HTTP 工作线程上限，本类自己决定）与「账号并发」（同一账号
     * 同时在跑的请求数上限，来自配置）是**两个不同维度**。启动日志只写一个「并发上限 32」
     * 会被读成「设置页里的并发上限是 32」，与用户实际配置对不上 —— 所以两个维度都打印、
     * 各自标注口径，避免歧义。
     */
    private val accountConcurrency: () -> String = { "" },
    private val handler: (Req, Res) -> Unit,
) {
    private var serverSocket: ServerSocket? = null

    /** 接受连接的**独立**线程（不再占用工作池 —— 这正是 1.4.8 连接超时的根因，见 [acceptLoop]）。 */
    @Volatile private var acceptThread: Thread? = null

    /** 线程池随 start/stop 生命周期创建销毁（stop 时必须 shutdown，否则反复启停会累积线程）。 */
    @Volatile private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.get()) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        // 显式 backlog=128（默认 50 偏小）：建连突发时内核认可更多待处理连接，
        // 与「不排队、只快速失败」的策略配套，避免客户端 connect 阶段就失败。
        ss.bind(InetSocketAddress("0.0.0.0", portProvider()), 128)
        serverSocket = ss
        running.set(true)
        // 工作池：**每个连接要么立刻拿到线程，要么立刻被拒绝**。
        //
        // 1.4.7 起这里用的是 (core 2, max 32, LinkedBlockingQueue(64)) + CallerRunsPolicy，
        // 并且把 accept 循环也提交进同一个池 —— 这正是用户反馈「启动网关后访问
        // 127.0.0.1:7863 有概率超时、不稳定」的根因（JDK 文档实证，见 README 1.4.9 章节）：
        //
        //   a) ThreadPoolExecutor 只有在 `poolSize < corePoolSize` 时才新建线程，之后**一律先入
        //      队列**，仅当队列满才扩容（JDK 原文：「如果运行的线程多于 corePoolSize 而少于
        //      maximumPoolSize，则仅当队列满时才创建新线程」）。accept 循环自己先占了 1 个线程，
        //      于是**实际只有 1 个线程**在处理连接，第 2 个并发连接只能进队列；
        //   b) 每个连接都是 keep-alive 长连接（一个线程从建连活到空闲 60 s 或被客户端关闭），
        //      所以排队中的连接要等前一个连接**释放**才可能被处理 —— 客户端干等数十秒 → 超时。
        //      浏览器/客户端会同时开多个连接，所以「复现概率很大」；
        //   c) 队列满（64）后 CallerRunsPolicy 会让**提交线程即 accept 循环自己**去执行
        //      handle()，accept 就此停摆最长 60 s，期间新连接全堆在内核 backlog 里。
        //
        // 现在：accept 循环独占一个线程（见 [acceptLoop]），工作池改用 SynchronousQueue +
        // AbortPolicy —— 空闲线程不足时**直接新建**（直到 HTTP_MAX_THREADS），达上限则**立刻拒绝**
        // 并回 503。既不静默排队，也不会无限建线程；core 线程允许空闲回收（静默期占资源≈0）。
        val ex = java.util.concurrent.ThreadPoolExecutor(
            HTTP_CORE_THREADS, HTTP_MAX_THREADS, 30L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.SynchronousQueue(),
            java.util.concurrent.ThreadFactory { r -> Thread(r, "wb2api-http").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
        )
        ex.allowCoreThreadTimeOut(true)
        executor = ex
        Thread({ acceptLoop(ss, ex) }, "wb2api-accept").apply { isDaemon = true }.also {
            acceptThread = it
            it.start()
        }
        val acct = accountConcurrency().trim().let { if (it.isEmpty()) "" else " · $it" }
        LogBus.log("http server listening on 0.0.0.0:${portProvider()}（连接并发 $HTTP_MAX_THREADS$acct）")
    }

    /**
     * 接受连接的独立循环。
     *
     * 两点必须守住（原实现都踩了）：
     *  1. accept 抛**非致命**异常（如 EMFILE：fd 耗尽）时必须退避 —— 原来的 `catch {}` 后立刻
     *     重试，会变成 100% CPU 的忙循环，而且因为一直失败永远无法恢复；
     *  2. 工作池拒绝时**立刻回 503 并关闭连接** —— 否则连接悬挂，客户端又只能等到超时，
     *     那就退回成「看起来一样的超时」，修了等于没修。
     */
    private fun acceptLoop(ss: ServerSocket, ex: ExecutorService) {
        while (running.get()) {
            val sock = try {
                ss.accept()
            } catch (e: Throwable) {
                if (!running.get()) break
                LogBus.log("[sys] accept 失败：${e.javaClass.simpleName}: ${e.message}")
                runCatching { Thread.sleep(200) }
                continue
            }
            try {
                ex.execute { handle(sock) }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                runCatching {
                    Res(sock.getOutputStream()).sendError(
                        503,
                        "gateway busy: too many concurrent connections",
                        "server_busy",
                        "并发连接已达上限（$HTTP_MAX_THREADS），请稍后重试",
                    )
                }
                runCatching { sock.close() }
            }
        }
    }

    fun stop() {
        running.set(false)
        // 关监听 socket 会让阻塞中的 accept 抛异常退出；interrupt 作为兜底（accept 线程已独立于
        // 工作池，shutdownNow 不再能覆盖它）。
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { acceptThread?.interrupt() }
        acceptThread = null
        // 修复线程泄漏：原实现只关 socket，keep-alive 处理线程（soTimeout 10 分钟）会长期存活。
        runCatching { executor?.shutdownNow() }
        executor = null
    }

    private fun handle(sock: Socket) {
        // TCP_NODELAY：关闭 Nagle 算法。Nagle 会把小包攒到收到 ACK 才发，局域网里的小响应
        // （/v1/models、401/404 错误体）与 SSE 的首个 chunk 会平白多出几十毫秒的等待 ——
        // 在本机/局域网场景没有任何收益，纯延迟。
        runCatching { sock.tcpNoDelay = true }
        // keep-alive 空闲上限从 10 分钟收紧到 60 s：soTimeout 只作用于**读请求**，
        // 不会打断正在写的 SSE 响应；空闲超时后客户端会按 HTTP 语义自然重连。
        // 60 s 也**短于**主流客户端（OkHttp 默认 5 min）—— 由服务端先关是 keep-alive 的正确
        // 方向（反之客户端会复用一条已被对端关闭的连接）。
        sock.soTimeout = 60_000
        try {
            val input = BufferedInputStream(sock.getInputStream())
            val output = sock.getOutputStream()
            while (true) {
                val req = try {
                    readRequest(input)
                } catch (_: RequestTooLargeException) {
                    runCatching { Res(output).sendError(413, "request body too large") }
                    break
                } ?: break
                val res = Res(output)
                try { handler(req, res) } catch (e: Throwable) {
                    LogBus.log("[sys] handler error: ${e.message}")
                    // 已进入流式输出时再写 500 会被 Res 的 `sent` 挡掉 —— 结果是「既没有错误响应、
                    // 也没有终止块」，客户端只能挂到 soTimeout 才断开（代码审查 P0-2）。
                    // 先补一个 chunked 终止块收尾，让客户端的流能正常结束。
                    runCatching { if (res.isStreaming) res.endStream() }
                    runCatching { res.sendError(500, e.message ?: "internal error") }
                }
                if (req.headers["connection"]?.contains("close") == true) break
            }
        } catch (_: java.io.IOException) {
            // 客户端断开 / 半路取消 / 空闲超时都属于网络常态，不刷日志。
        } catch (e: Throwable) {
            // 非 IO 异常才是真问题。原实现把两者一起吞掉，「连接异常」在日志里完全不可见，
            // 只能靠猜 —— 这正是本次超时问题排查困难的直接原因之一。
            LogBus.log("[sys] 连接处理异常：${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun readRequest(input: InputStream): Req? {
        // 跳过请求行前的空行：原实现用递归（`return readRequest(input)`），
        // 恶意客户端持续发空行会吃光栈空间。
        var line: String
        while (true) {
            line = readLine(input) ?: return null
            if (line.isNotEmpty()) break
        }
        val parts = line.split(" ")
        if (parts.size < 2) return null
        val method = parts[0]
        val target = parts[1]
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            if (headers.size >= MAX_HEADER_COUNT) throw RequestTooLargeException()
            val idx = h.indexOf(':')
            if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
        }
        val body = if (headers["transfer-encoding"]?.contains("chunked") == true) readChunked(input)
        else {
            // 先判上限**再**分配：原实现是 `ByteArray(content-length)`，一个
            // `Content-Length: 2147483000` 的请求头就能当场 OOM（见 MAX_BODY_BYTES 的说明）。
            val len = headers["content-length"]?.toLongOrNull() ?: 0L
            if (len > MAX_BODY_BYTES) throw RequestTooLargeException()
            if (len > 0) readN(input, len.toInt()) else ByteArray(0)
        }
        val qIdx = target.indexOf('?')
        val path = if (qIdx >= 0) target.substring(0, qIdx) else target
        val query = if (qIdx >= 0) parseQuery(target.substring(qIdx + 1)) else emptyMap()
        return Req(method, path, query, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c = input.read()
        if (c == -1) return null
        while (c != -1 && c != '\n'.code) {
            if (c != '\r'.code) {
                // 超长单行 → 413（与 body 超限同一条拒绝路径），而不是无界累积到 OOM。
                if (sb.length >= MAX_LINE_BYTES) throw RequestTooLargeException()
                sb.append(c.toChar())
            }
            c = input.read()
        }
        return sb.toString()
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n); var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            // 负数分块（"-1\\r\\n"）会让下面的 ByteArray(-1) 抛 NegativeArraySizeException；
            // 畸形分块按「流结束」处理即可（与 toIntOrNull 失败同一条路径）。
            val size = sizeLine.trim().split(';')[0].toIntOrNull(16)?.takeIf { it >= 0 } ?: break
            if (size == 0) { readLine(input); break }
            // 分块传输同样要有上限：原实现往 ByteArrayOutputStream 无界累积，等价于一条无上限的写。
            if (out.size().toLong() + size > MAX_BODY_BYTES) throw RequestTooLargeException()
            out.write(readN(input, size))
            readLine(input)
        }
        return out.toByteArray()
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').mapNotNull {
            if (it.isBlank()) return@mapNotNull null
            val i = it.indexOf('=')
            if (i < 0) it to "" else URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
}

// ---------------- 路由与处理器 ----------------

class Router {
    fun handle(req: Req, res: Res) {
        if (req.method == "OPTIONS") { res.sendText(204, ""); return }
        val path = req.path
        when {
            path == "/healthz" -> healthz(res)
            path == "/v1/models" -> { if (auth(req, res)) models(res) }
            path == "/v1/chat/completions" && req.method == "POST" -> { if (auth(req, res)) ChatHandler.handle(req, res) }
            // OpenAI Responses API（/v1/responses）：与 chat/completions 等价能力，走同一套
            // 选号/改写/错误分类/用量统计管线（见 ChatHandler.handleResponses）。
            path == "/v1/responses" && req.method == "POST" -> { if (auth(req, res)) ChatHandler.handleResponses(req, res) }
            path == "/status" -> { if (auth(req, res)) status(res) }
            path.startsWith("/panel/api/") -> { if (auth(req, res)) PanelApi.handle(req, res) }
            path == "/panel" || path == "/panel/" -> res.sendText(200, "<html><body>wb2api panel runs in the native app.</body></html>", "text/html")
            else -> res.sendError(404, "not found", "not_found")
        }
    }

    private fun auth(req: Req, res: Res): Boolean {
        val key = Gateway.store.config.api_key
        if (key.isBlank()) return true
        val h = req.header("authorization") ?: ""
        val token = if (h.startsWith("Bearer ")) h.substring(7) else ""
        val ok = constantTimeEquals(token, key)
        if (!ok) res.sendError(401, "missing or invalid API key", "invalid_api_key")
        return ok
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ha = java.security.MessageDigest.getInstance("SHA-256").digest(a.toByteArray())
        val hb = java.security.MessageDigest.getInstance("SHA-256").digest(b.toByteArray())
        return java.security.MessageDigest.isEqual(ha, hb)
    }

    private fun healthz(res: Res) {
        val counts = Gateway.pool.counts()
        val healthy = if (Gateway.pool.servableNow()) 200 else 503
        val obj = buildJsonObject {
            put("healthy", JsonPrimitive(counts.healthy))
            put("total", JsonPrimitive(counts.total))
            put("service", JsonPrimitive("workbuddy2api"))
            put("running", JsonPrimitive(Gateway.isRunning()))
            put("realm_servable", buildJsonObject {
                put("cn", JsonPrimitive(Gateway.pool.countsByRealm("cn").healthy > 0))
                put("global", JsonPrimitive(Gateway.pool.countsByRealm("global").healthy > 0))
            })
        }
        res.sendJson(healthy, obj, mapOf("X-Service" to "workbuddy2api"))
    }

    private fun status(res: Res) {
        val views = Gateway.pool.listViews()
        val counts = Gateway.pool.counts()
        val obj = buildJsonObject {
            put("accounts", buildJsonArray { views.forEach { add(accountToJson(it)) } })
            put("total", JsonPrimitive(counts.total))
            put("healthy", JsonPrimitive(counts.healthy))
            put("cooling", JsonPrimitive(counts.cooling))
            put("disabled", JsonPrimitive(counts.disabled))
            // paused 是 disabled 的细分（已计入 disabled），单独透出让面板能区分「停用原因」。
            put("paused", JsonPrimitive(counts.paused))
            put("in_flight_full", JsonPrimitive(counts.inFlightFull))
            put("sticky_sessions", JsonPrimitive(Gateway.pool.stickyCount()))
            put("redis_mode", JsonPrimitive("noop"))
            put("credit_floor", JsonPrimitive(Gateway.store.config.pool.credit_floor))
        }
        res.sendJson(200, obj)
    }

    private fun models(res: Res) {
        val list = runBlocking { ModelService.listModels() }
        val obj = buildJsonObject {
            put("object", JsonPrimitive("list"))
            put("data", buildJsonArray { list.forEach { add(it) } })
        }
        res.sendJson(200, obj)
    }

    companion object {
        fun accountToJson(v: com.wb.api.pool.AccountView): JsonObject = buildJsonObject {
            put("uid", JsonPrimitive(v.uid))
            put("nickname", JsonPrimitive(v.nickname))
            put("realm", JsonPrimitive(v.realm))
            put("disabled", JsonPrimitive(v.disabled))
            put("paused", JsonPrimitive(v.paused))
            // 对齐上游 /status 的 enterprise 标记（企业版走企业额度口径、无成长体系）。
            put("enterprise", JsonPrimitive(v.enterprise))
            put("breaker_until", JsonPrimitive(v.breaker_until))
            put("degrade_until", JsonPrimitive(v.degrade_until))
            put("cool_remaining_sec", JsonPrimitive(v.cool_remaining_sec))
            put("cool_kind", JsonPrimitive(v.cool_kind))
            put("in_flight", JsonPrimitive(v.in_flight))
            put("credits", JsonPrimitive(v.credits))
            put("credits_total", JsonPrimitive(v.credits_total))
            put("success_count", JsonPrimitive(v.success_count))
            put("err_total", JsonPrimitive(v.err_total))
            put("access_expires_at", JsonPrimitive(v.access_expires_at))
            put("rate_limited_models", buildJsonArray {
                v.rate_limited_models.forEach { m ->
                    add(buildJsonObject {
                        put("model", JsonPrimitive(m.model)); put("kind", JsonPrimitive(m.kind))
                        put("reset_at", JsonPrimitive(m.reset_at)); put("until", JsonPrimitive(m.until))
                    })
                }
            })
            put("token_usage", buildJsonObject {
                put("request_count", JsonPrimitive(v.token_usage.request_count))
                put("total_tokens", JsonPrimitive(v.token_usage.total_tokens))
                put("last_latency_ms", JsonPrimitive(v.token_usage.last_latency_ms))
                put("last_tokens_per_second", JsonPrimitive(v.token_usage.last_tokens_per_second))
                put("prompt_tokens", JsonPrimitive(v.token_usage.prompt_tokens))
                put("cached_tokens", JsonPrimitive(v.token_usage.cached_tokens))
            })
        }
    }
}

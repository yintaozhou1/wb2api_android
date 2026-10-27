package com.wb.api

import android.content.Context
import com.wb.api.core.Durations
import com.wb.api.core.GatewayConfig
import com.wb.api.core.LogBus
import com.wb.api.data.Store
import com.wb.api.model.RequestMetrics
import com.wb.api.pool.AccountPool
import com.wb.api.upstream.UpstreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 全局网关核心：持有存储、账号池、上游客户端与运行指标。 */
object Gateway {
    lateinit var store: Store
        private set
    lateinit var pool: AccountPool
        private set
    lateinit var upstream: UpstreamClient
        private set

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** 最近一次启动失败原因（端口占用等）。UI 直接展示，避免「点不动却没反应」。 */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    /** 监听端口：`applyConfig`（HTTP/UI 线程）写、accept 线程与面板读 → 需 @Volatile。 */
    @Volatile
    var port: Int = 7863

    /** 本次运行的起始时刻（epoch 秒）；未运行时为 0。 */
    @Volatile
    var startedAt: Long = 0

    /**
     * 上一次运行的总时长（秒）。
     *
     * 修复点：原实现停止后**不清零 startedAt**，于是 `uptimeSec()` 在网关停止后仍在
     * 递增（用户反馈「关闭网关后运行时长还在涨」）。现在停止时把时长冻结到本字段，
     * 重新启动才重置，语义是「本次运行时长 / 上次运行时长」。
     */
    @Volatile
    private var frozenUptimeSec: Long = 0L

    private val completed = AtomicLong(0)
    private val success = AtomicLong(0)
    private val httpSuccess = AtomicLong(0)
    private val totalDuration = AtomicLong(0)
    private val _inFlight = AtomicInteger(0)

    @Volatile private var initialized = false

    // @Synchronized：Application.onCreate 与 GatewayService.onStartCommand 都会调用 init，
    // 无锁的 check-then-act 在极端竞态下会**双重初始化**（两个 AccountPool、两次 reload）。
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        store = Store(context.applicationContext)
        store.loadConfig()
        port = parsePort(store.config.listen)
        pool = AccountPool(store) { store.config }
        pool.reload()
        upstream = buildUpstream(store.config)
        initialized = true
        LogBus.log("gateway initialized, port=$port, api_key=${if (store.config.api_key.isBlank()) "(none)" else "set"}")
    }

    fun isInitialized() = initialized

    /**
     * **实际**绑定的端口（0 = 当前没有监听）。
     *
     * 与 [port]（配置值）分开的理由：改监听端口不会重新 bind（正在跑的 SSE 不能被打断），
     * 于是「配置端口」与「真正在听的端口」会不一致。浮窗 / 常驻通知 / 面板 / 状态必须读
     * 这一个 —— 否则用户会拿到一个**没人在听**的地址，连不上还查不出原因。
     */
    @Volatile
    var boundPort: Int = 0
        private set

    /** 展示用端口：有监听就用实际端口，否则退回配置端口（UI 上「将要使用」的地址）。 */
    fun displayPort(): Int = if (boundPort > 0) boundPort else port

    private fun parsePort(listen: String): Int {
        val p = listen.substringAfterLast(':').toIntOrNull()
        return if (p != null && p in 1..65535) p else 7863
    }

    fun buildUpstream(cfg: GatewayConfig): UpstreamClient =
        UpstreamClient(cfg.upstream.user_agent, cfg.upstream.timeout_seconds.toLong(), cfg.upstream.low_latency)

    /**
     * 应用新配置。
     *
     * @return 监听端口是否发生变化 —— 调用方据此提示「需要重启网关」（端口不能热生效，
     *   ServerSocket 只在 startServer 里 bind 一次）。返回布尔而不是让调用方自己去比
     *   `store.config.listen`：config 在这一行之后已经是新值，比较必须发生在保存**之前**。
     */
    fun applyConfig(cfg: GatewayConfig): Boolean {
        val before = store.config
        val listenChanged = before.listen != cfg.listen
        store.saveConfig(cfg)
        port = parsePort(cfg.listen)
        // 只在真正影响出站行为的字段变化时重建客户端：否则每次保存配置都会换掉 OkHttp 实例，
        // 打断连接池复用（直接推高首字延迟），与「减少首字延迟」的目标相悖。
        if (before.upstream != cfg.upstream) upstream = buildUpstream(cfg)
        // 监听端口**不会**热生效（ServerSocket 只在 startServer 时 bind 一次）。这里显式留痕，
        // 免得用户在日志里找不到「为什么改了端口还是旧地址」。
        if (listenChanged) {
            val now = if (boundPort > 0) "当前仍在 $boundPort 监听，重启网关后生效）" else "下次启动网关生效）"
            LogBus.log("监听端口变更：${before.listen} → ${cfg.listen}（$now")
        }
        LogBus.log("config applied")
        return listenChanged
    }

    /**
     * 生命周期余额刷新用的**进程级**作用域。
     *
     * 不复用 Service 的 scope：停止网关时 Service 会销毁并 cancel 它自己的 scope，而「停止后
     * 补一次余额刷新」恰要在那之后跑完。进程级作用域保证这次刷新能落地（用户是回到 UI 里点
     * 停止的，进程仍在），把最后一段未刷新窗口的消耗补记进会话统计。
     */
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 后台刷新全池余额，并**同步**「可用积分 / 本次消耗」两处口径（经
     * [AccountPool.setCredits]，它同时更新账号余额与会话消耗差值）。
     *
     * 由 [setRunning] 在启动后、停止后各触发一次：
     *  - 启动后：建立「启动时刻余额」基线（否则首屏消耗可能被漏算，或把启动前的算进来）；
     *  - 停止后：补齐最后一个余额刷新周期内的消耗（否则「消耗积分」会少算、甚至为 0）。
     * 手动「刷新余额」按钮与定时任务走各自路径，三者共用同一个 setCredits 口径，互不冲突。
     */
    fun refreshBalances(reason: String) {
        if (!initialized) return
        lifecycleScope.launch {
            val accounts = store.auths()
            if (accounts.isEmpty()) return@launch
            val soonSec = Durations.parse(store.config.pool.expiring_soon, 168 * 3600L)
            var ok = 0
            for (a in accounts) {
                runCatching { upstream.balanceDetailed(a, soonSec) }
                    .onSuccess { b ->
                        if (b.known) {
                            pool.setCredits(a.uid, b.remain, b.earliestAt, b.earliestRemaining)
                            ok++
                        }
                    }
                    .onFailure { LogBus.log("[sys] 余额刷新($reason) ${a.uid.take(8)}: ${it.message}") }
            }
            if (ok > 0) LogBus.log("[sys] 余额刷新($reason)：$ok/${accounts.size} 个账号已同步")
        }
    }

    fun setRunning(r: Boolean) {
        val was = _running.value
        if (r && !was) {
            startedAt = nowSec()
            frozenUptimeSec = 0L
            _lastError.value = null
            // 本次运行从此刻起统计「消耗积分」：先清零，再立刻刷一次余额建立基线
            // （基线不能用上次落盘的旧值，见 AccountPool.resetSessionCreditTracking）。
            if (initialized) {
                pool.resetSessionCreditTracking()
                refreshBalances("启动")
            }
        }
        if (!r && was) {
            frozenUptimeSec = if (startedAt == 0L) frozenUptimeSec else (nowSec() - startedAt).coerceAtLeast(0)
            startedAt = 0L
            // 停止后补一次余额刷新：把最后一段未刷新窗口的消耗计入本次运行（否则会少算）。
            if (initialized) refreshBalances("停止")
        }
        _running.value = r
    }

    private fun nowSec() = System.currentTimeMillis() / 1000

    fun isRunning(): Boolean = _running.value

    fun failStart(reason: String) {
        _lastError.value = reason
        setRunning(false)
        LogBus.log("[sys] 网关启动失败：$reason")
    }

    fun noteRequestStart() { _inFlight.incrementAndGet() }
    fun noteRequestEnd(ok: Boolean, httpOk: Boolean, durationMs: Long) {
        _inFlight.decrementAndGet()
        completed.incrementAndGet()
        if (ok) success.incrementAndGet()
        if (httpOk) httpSuccess.incrementAndGet()
        totalDuration.addAndGet(durationMs)
    }

    fun metrics(): RequestMetrics {
        val c = completed.get()
        return RequestMetrics(
            completed = c,
            success = success.get(),
            httpSuccess = httpSuccess.get(),
            avgDurationMs = if (c > 0) totalDuration.get() / c else 0,
            inFlight = _inFlight.get(),
        )
    }

    /** 运行中 → 本次已运行秒数；已停止 → 上次运行总秒数（不再随时间增长）。 */
    fun uptimeSec(): Long = if (startedAt == 0L) frozenUptimeSec else (nowSec() - startedAt).coerceAtLeast(0)

    // ---------- 服务器控制 ----------

    @Volatile private var server: com.wb.api.server.EmbeddedHttpServer? = null
    private val router = com.wb.api.server.Router()

    /**
     * 启动内嵌 HTTP 服务。**失败必须显式暴露**：原实现把异常吞在 Service 里，
     * 结果通知显示「运行中」而 UI 显示「启动网关」，用户点了没反应也不知道为什么。
     */
    @Synchronized
    fun startServer() {
        // 修掉与 stopServer 的**锁不对称**：`server` 是 @Volatile，原来「判空 → 赋值 → 使用」
        // 全程无锁，与并发到来的 stopServer（把 server 置 null）交错时 `server!!` 会 NPE；
        // 而它落在 runCatching 里，于是真实的竞态被伪装成「启动失败（端口占用/未知错误）」，
        // 掩盖了真正的原因。现在整段加锁，并用局部变量消除「判空与使用之间被改」的窗口。
        val s = server ?: com.wb.api.server.EmbeddedHttpServer(
            portProvider = { port },
            accountConcurrency = { accountConcurrencyDesc() },
            handler = router::handle,
        ).also { server = it }
        val r = runCatching { s.start() }
        if (r.isFailure) {
            val why = r.exceptionOrNull()?.message ?: r.exceptionOrNull()?.javaClass?.simpleName ?: "未知错误"
            server = null
            boundPort = 0
            failStart(if (why.contains("in use", true) || why.contains("EADDRINUSE", true)) "端口 $port 已被占用" else why)
            return
        }
        boundPort = port
        setRunning(true)
    }

    @Synchronized
    fun stopServer() {
        server?.stop()
        server = null
        boundPort = 0
        // 停止前把池状态落盘：落盘有 1s 节流窗口，且唯一的周期 flush 由 Scheduler 提供
        // （它在 stopServer 之前就先停了）—— 不补这一下，刚发生的冷却 / 禁用 / 计数会
        // 随「正常停止」一起丢掉，重启后立刻又去打上游（注释只承诺「崩溃最多丢 1s」）。
        runCatching { pool.flush() }
        // 释放上游空闲连接，避免 keep-alive 半死连接残留。
        runCatching { upstream.http.connectionPool.evictAll() }
        setRunning(false)
        LogBus.log("http server stopped")
    }

    fun serverRunning(): Boolean = server != null

    /**
     * 账号维度的并发描述，供启动日志把两个维度区分开（见 EmbeddedHttpServer 的同名参数）。
     * 每次启动时读取当下配置 —— 日志反映的是**本次运行的生效值**，不是硬编码常量。
     */
    private fun accountConcurrencyDesc(): String {
        val p = store.config.pool
        return "单账号 ${p.max_in_flight} / global ${p.max_in_flight_global}"
    }

    /**
     * 释放「随时可重建」的资源（由 `Application.onTrimMemory` 在系统报内存压力时调用）。
     *
     * 只回收空闲的 HTTP 连接池：不触碰运行状态，网关继续服务，下一个请求会自然新建连接。
     * 常驻前台服务 + 液态玻璃动画本就吃内存，能主动让小内存掉下来就别等着被压。
     */
    fun trimMemory() {
        runCatching { upstream.http.connectionPool.evictAll() }
    }
}

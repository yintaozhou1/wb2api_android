package com.wb.api.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wb.api.Gateway
import com.wb.api.core.Durations
import com.wb.api.core.GatewayConfig
import com.wb.api.core.LogBus
import com.wb.api.core.LogEntry
import com.wb.api.core.Net
import com.wb.api.core.UiCfg
import com.wb.api.core.UpdateCheck
import com.wb.api.data.CredentialTransfer
import com.wb.api.data.ImportReport
import com.wb.api.model.AuthEntry
import com.wb.api.model.ModelInfo
import com.wb.api.pool.AccountView
import com.wb.api.pool.PoolCounts
import com.wb.api.scheduler.Scheduler
import com.wb.api.server.GrowthService
import com.wb.api.server.ModelService
import com.wb.api.overlay.AddressOverlay
import com.wb.api.service.GatewayService
import com.wb.api.upstream.DeviceAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.compose.runtime.Immutable
data class Overview(
    val counts: PoolCounts = PoolCounts(0, 0, 0, 0, 0),
    val views: List<AccountView> = emptyList(),
    val port: Int = 7863,
    val apiKey: String = "",
    val running: Boolean = false,
    val uptime: Long = 0,
    /** 全池可用积分合计（不含企业版「不限」账号；用于首页「总共可用积分」）。 */
    val creditsTotal: Double = 0.0,
    /** 参与合计的账号数（credits 已知且非「不限」）。 */
    val creditsAccounts: Int = 0,
    /** 池内是否存在企业版「不限」账号（合计需标注「含不限」）。 */
    val creditsUnlimited: Boolean = false,
    /** 本次运行累计消耗的积分（余额差值口径；网关未启动过则为 0）。 */
    val creditConsumed: Double = 0.0,
    val completed: Long = 0,
    val successRate: Double = 0.0,
    val inFlight: Int = 0,
    val sticky: Int = 0,
    val lastError: String? = null,
    /** 本机局域网 IPv4（在后台线程算好后随快照下发；无则 null）。 */
    val lanIp: String? = null,
) {
    val hasAccounts: Boolean get() = counts.total > 0
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * 概览快照。
     *
     * 1.4.7 起轮询改为「**有人订阅才跑**」（`SharingStarted.WhileSubscribed`）：原实现在
     * `viewModelScope` 里无条件 `while (true) { 刷新; delay(2000) }`，即使用户早就把应用切到
     * 后台、界面完全不可见，也照样每 2 秒唤醒一次（读池状态、算归档统计、再推一次列表）。
     * 改成 stateIn 之后：界面离开 STARTED（`collectAsStateWithLifecycle` 停止收集）满 5 秒
     * 自动停轮询，回到前台立刻恢复并**立即**给出一份新值。StateFlow 自带相等去重，
     * 于是「网关停止后 uptime 恒定」这类静止状态也不再产生无意义的推送与重组。
     */
    private val pollTick = flow { while (true) { emit(Unit); delay(2_000) } }

    val overview: StateFlow<Overview> = pollTick
        .map { buildOverview() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Overview())

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models

    private val _config = MutableStateFlow(Gateway.store.config)
    val config: StateFlow<GatewayConfig> = _config

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _loadingModels = MutableStateFlow(false)
    val loadingModels: StateFlow<Boolean> = _loadingModels

    /** 账号重载进行中（驱动「账号」页的进度条，与「模型-刷新」体验一致）。 */
    private val _loadingAccounts = MutableStateFlow(false)
    val loadingAccounts: StateFlow<Boolean> = _loadingAccounts

    /** 全量余额刷新中（驱动账号页顶栏刷新按钮的图标旋转）。 */
    private val _refreshingBalance = MutableStateFlow(false)
    val refreshingBalance: StateFlow<Boolean> = _refreshingBalance

    /** 启停网关进行中：驱动 Loading 遮罩（启动要等端口绑定 + 调度器起来，不是瞬时的）。 */
    private val _gatewayBusy = MutableStateFlow<String?>(null)
    val gatewayBusy: StateFlow<String?> = _gatewayBusy

    /** 请求归档统计（条数 / 字节 / 最后写入），由 refreshOverview 周期刷新。 */
    private val _archiveStats = MutableStateFlow(ArchiveStats())
    val archiveStats: StateFlow<ArchiveStats> = _archiveStats

    fun toast(msg: String) { _message.value = msg }
    fun consumeMessage() { _message.value = null }

    /**
     * 启动时检查一次远程版本公告。
     *
     * 放在 ViewModel 的 init 里（而不是 Activity.onCreate）：进程内只会跑一次，Activity
     * 因配置变化重建时不会重复请求、也不会重复弹提示。检查本身是网络 IO，由
     * [UpdateCheck.fetch] 自己切到 IO 线程，这里不阻塞启动。
     */
    init {
        checkUpdate(manual = false)
    }

    /**
     * 检查远程「最新版x.x.x」公告。
     *
     * @param manual true = 用户主动点「检查更新」：没新版本/没解析到标记都要给明确回执；
     *   false = 启动时自动检查：只在**有新版本**或**网络失败**时提示（后者是用户明确要求
     *   的行为：连不上/超时要说出来），其余情况只写日志，避免每次冷启动都弹一句「已是最新」。
     */
    fun checkUpdate(manual: Boolean = true) {
        viewModelScope.launch {
            val cur = com.wb.api.BuildConfig.VERSION_NAME
            if (manual) toast("正在检查更新…")
            when (val r = UpdateCheck.fetch()) {
                is UpdateCheck.Result.Latest -> {
                    val newer = UpdateCheck.compareVersions(r.version, cur) > 0
                    when {
                        newer -> {
                            toast("发现新版本 ${r.version}（当前 $cur）· 设置 → 关于 → 检查更新")
                            LogBus.log("[sys] 检测到新版本 ${r.version}（当前 $cur）")
                        }
                        manual -> toast("已是最新版本（$cur）")
                        else -> LogBus.log("[sys] 版本检查：当前 $cur 已是最新（远程 ${r.version}）")
                    }
                }
                UpdateCheck.Result.NoMarker -> {
                    if (manual) toast("检查更新失败：远程文本里没有「最新版x.x.x」")
                    else LogBus.log("[sys] 检查更新：远程文本里没有「最新版x.x.x」")
                }
                is UpdateCheck.Result.Failed -> {
                    // 自动检查也提示：用户明确要求「网络异常/超时要说出来」。
                    toast("检查更新失败：${r.reason}")
                }
            }
        }
    }

    /**
     * 装配一份概览快照。
     *
     * 运行在 `Dispatchers.Default` 上（见 [overview] 的 `flowOn`）：这里会读池状态、
     * 扫账号列表、算归档统计，都是一次性的 CPU/IO 混合工作，放在主线程每 2 秒跑一遍
     * 是典型的掉帧来源。顺带把**局域网 IP** 也在这里算 —— 原实现是在 Compose 组合期
     * 直接调 `Net.lanIp()`，等于每次重组都在主线程遍历一遍 NetworkInterface。
     */
    private fun buildOverview(): Overview {
        val m = Gateway.metrics()
        _archiveStats.value = ArchiveStats(
            enabled = Gateway.store.config.logging.request_archive_enabled,
            count = Gateway.store.requestLogCount(),
            bytes = Gateway.store.requestLogSizeBytes(),
            updatedAt = Gateway.store.requestLogModifiedAt(),
        )
        val views = Gateway.pool.listViews()
        val unlimited = com.wb.api.upstream.Up.ENTERPRISE_UNLIMITED_REMAIN
        return Overview(
            counts = Gateway.pool.counts(),
            views = views,
            port = Gateway.displayPort(),
            apiKey = Gateway.store.config.api_key,
            running = Gateway.running.value,
            uptime = Gateway.uptimeSec(),
            completed = m.completed,
            successRate = if (m.completed > 0) m.success.toDouble() / m.completed else 0.0,
            inFlight = m.inFlight,
            sticky = Gateway.pool.stickyCount(),
            lastError = Gateway.lastError.value,
            lanIp = Net.lanIp(),
            creditsTotal = views.sumOf { if (it.credits >= unlimited) 0.0 else it.credits },
            // 计数也要排除禁用号：金额已经把它们的 credits 置 0，计数再算进去就会出现
            // 「合计 0 · 3 个账号」这种自相矛盾的口径（全池禁用时应显示「暂无余额数据」）。
            creditsAccounts = views.count { !it.disabled && it.credits < unlimited },
            creditsUnlimited = views.any { !it.disabled && it.credits >= unlimited },
            creditConsumed = Gateway.pool.sessionCreditConsumed(),
        )
    }

    fun logsFlow(): StateFlow<List<LogEntry>> = LogBus.entries

    /**
     * 引导用户把本应用设为「不受电池优化限制」（长连接保活的唯一官方合规途径）。
     *
     * **首选 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，且必须带 `package:` URI**
     * （Android 官方 Settings API 文档原文：Input: The Intent's data URI must specify the
     * application package name to be shown, with the "package" scheme. That is
     * "package:com.my.app"）。系统会直接弹出「是否允许 wb2api 忽略电池优化？」的确认框，
     * 点「允许」即把本应用加入白名单 —— 这就是「一步选到『无限制』」，对应 Manifest 里的
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限（已声明；该权限为 normal 级，安装即授予）。
     *
     * 旧实现走 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`（**列表页**），要求用户自己在
     * 列表里翻找本应用；部分 ROM 把它映射到「电池使用情况」这类纯展示页，用户反馈
     * 「点了之后根本没有能选『无限制』的地方」。
     *
     * 回落链（定制 ROM 可能缺少某一项，逐级降级、绝不静默失败）：
     *   1. 直接请求豁免 —— 首选，一步到位；
     *   2. 应用详情页 —— 必然存在；用户可在「电池 / 省电策略」里选「无限制」；
     *   3. 电池优化列表页 —— 老 ROM 的等价入口。
     */
    fun openBatterySettings() {
        val ctx = getApplication<Application>()
        val pm = ctx.packageManager
        val self = android.net.Uri.fromParts("package", ctx.packageName, null)
        if (isBatteryExempt(ctx)) {
            toast("本应用已不受电池优化限制，后台长连接不会被 Doze 切断")
            return
        }
        val candidates = listOf(
            android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(self) to "请在弹窗中选「允许」，让 wb2api 不受电池优化限制",
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(self) to "请在「电池 / 省电策略」里选「无限制」",
            android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) to
                "请在列表中找到 wb2api 并选择「不优化」",
        )
        runCatching {
            for ((raw, tip) in candidates) {
                val i = raw.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                if (i.resolveActivity(pm) != null) {
                    ctx.startActivity(i)
                    toast(tip)
                    return
                }
            }
            // 三个入口都没有：给出可照做的手动路径，而不是「点了没反应」。
            toast("系统未提供电池优化入口，请到「设置 → 应用 → wb2api → 电池」选「无限制」")
        }.onFailure { toast("无法打开电池设置：${it.message}") }
    }

    /** 本应用当前是否已豁免电池优化（Doze 白名单）。 */
    private fun isBatteryExempt(ctx: Application): Boolean = runCatching {
        (ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager)
            ?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    }.getOrDefault(false)

    /** 启停网关期间 Loading 遮罩的最小可见时长：状态切换通常 <100ms，不兜底就只会「闪一帧」。 */
    private val minBusyMs = 900L

    /**
     * 启动 / 停止网关。
     *
     * **无账号不允许启动**：一个没有账号的网关只是「开着但任何请求都会 503」，
     * 属于用户明确指出的「不符合逻辑」——这里直接拦截并说明原因。
     *
     * 启停都不是瞬时的（启动要等端口绑定与调度器拉起，停止要等线程池收口）。
     * 因此：① 全程挂 [gatewayBusy] 驱动 Loading 遮罩并**保证至少可见 [minBusyMs]**，
     * 文案随阶段变化；② 结束以状态真正落地为准；③ 失败把 `lastError` 原文透出。
     */
    fun toggleGateway() {
        val ctx = getApplication<Application>()
        if (Gateway.running.value || Gateway.serverRunning()) {
            viewModelScope.launch {
                val t0 = android.os.SystemClock.elapsedRealtime()
                _gatewayBusy.value = "正在停止网关…"
                withContext(Dispatchers.IO) {
                    GatewayService.stop(ctx)
                    Scheduler.stop()
                    Gateway.stopServer()
                    Gateway.store.flushRequestLog()
                }
                // 等状态真正落地（最多 4s），避免「已停止」的提示早于实际状态。
                val ok = withContext(Dispatchers.IO) {
                    var i = 0
                    while (i < 40 && (Gateway.running.value || Gateway.serverRunning())) { delay(100); i++ }
                    !(Gateway.running.value || Gateway.serverRunning())
                }
                if (ok) {
                    _gatewayBusy.value = "正在收尾…"
                    Gateway.store.closeRequestLog()
                }
                val elapsed = android.os.SystemClock.elapsedRealtime() - t0
                if (elapsed < minBusyMs) delay(minBusyMs - elapsed)
                _gatewayBusy.value = null
                toast(if (ok) "网关已停止" else "停止超时，请重试或查看日志")
            }
        } else {
            if (Gateway.store.auths().isEmpty()) {
                toast("请先添加账号，再启动网关")
                return
            }
            viewModelScope.launch {
                val t0 = android.os.SystemClock.elapsedRealtime()
                _gatewayBusy.value = "正在启动网关…"
                GatewayService.start(ctx)
                // 悬浮窗权限：**不阻断启动**，且**必须在拉起服务之后**再跳授权页 ——
                // 跳转会立刻把应用推入后台，若先跳再 startForegroundService，
                // Android 12+ 的后台启动限制可能直接拒绝（用户只看到「启动失败」）。
                if (Gateway.store.config.ui.overlay && !AddressOverlay.canDraw(ctx)) {
                    android.widget.Toast.makeText(
                        ctx,
                        "请开启「显示在其他应用上层」权限，返回应用后会自动显示网关地址浮窗",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    AddressOverlay.requestPermission(ctx)
                }
                val ok = withContext(Dispatchers.IO) {
                    var i = 0
                    while (i < 60 && !Gateway.isRunning()) { delay(100); i++ }
                    Gateway.isRunning()
                }
                // 阶段文案：状态确认后还要拉起调度器，让用户看到「在做事」而不是硬切换。
                if (ok) _gatewayBusy.value = "正在拉起定时任务…"
                val elapsed = android.os.SystemClock.elapsedRealtime() - t0
                if (elapsed < minBusyMs) delay(minBusyMs - elapsed)
                _gatewayBusy.value = null
                if (ok) {
                    toast("网关已启动 · 端口 ${Gateway.port}")
                } else {
                    val e = Gateway.lastError.value
                    toast(if (e != null) "启动失败：$e" else "启动失败，请查看日志")
                }
            }
        }
    }

    /**
     * 重载账号池。
     *
     * 文案修复（用户反馈）：原来只回一句「账号已重载」，用户看不出**重载了几个** ——
     * 现在报出实际条数，并同样写入日志（与「刷新模型」保持一致的可见性）。
     */
    fun reloadAccounts() {
        viewModelScope.launch {
            _loadingAccounts.value = true
            withContext(Dispatchers.IO) { Gateway.pool.reload() }
            _loadingAccounts.value = false
            val n = Gateway.store.auths().size
            if (n == 0) {
                LogBus.log("[账号] 重载账号池：目录下没有账号")
                toast("已重载：目录下没有账号")
            } else {
                LogBus.log("[账号] 重载账号池：共 $n 个账号")
                toast("已重载 $n 个账号")
            }
        }
    }

    /** 刷新脉冲：每次模型列表刷新完成后自增，UI 据此播一次「列表已更新」的过场动画。 */
    private val _modelsPulse = kotlinx.coroutines.flow.MutableStateFlow(0)
    val modelsPulse: kotlinx.coroutines.flow.StateFlow<Int> = _modelsPulse

    fun loadModels(force: Boolean = true) {
        viewModelScope.launch {
            _loadingModels.value = true
            val list = withContext(Dispatchers.IO) { ModelService.listInfos(force) }
            _models.value = list
            _loadingModels.value = false
            // 无论成功与否都推进脉冲：用户点的是「刷新」，需要看到按钮**确实产生了动作**，
            // 而不是「内容没变 → 看上去没反应」。
            _modelsPulse.value = _modelsPulse.value + 1
            if (list.isEmpty()) {
                val noAccount = Gateway.store.auths().isEmpty()
                LogBus.log(if (noAccount) "[模型] 刷新模型：尚未添加账号，跳过" else "[模型] 刷新模型：未获取到模型（请稍后重试）")
                toast(if (noAccount) "需先添加账号才能拉取模型" else "未获取到模型，请稍后重试")
            } else {
                // 分域统计，日志里能直接看出「哪个域返回了几个」——排查目录问题时这是关键线索。
                val cn = list.count { it.realm != "global" }
                val gl = list.size - cn
                LogBus.log("[模型] 刷新模型：共 ${list.size} 个（CN $cn · global $gl）")
                toast("已加载 ${list.size} 个模型")
            }
        }
    }

    /** 成长任务：扫描全部账号（报名 + 领奖），结果写日志并 toast 摘要。 */
    private val _growthReport = kotlinx.coroutines.flow.MutableStateFlow<GrowthService.Report?>(null)
    val growthReport: kotlinx.coroutines.flow.StateFlow<GrowthService.Report?> = _growthReport
    private val _growthBusy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val growthBusy: kotlinx.coroutines.flow.StateFlow<Boolean> = _growthBusy

    /** 扫描进行中的逐账号进度文案（如「(2/3) 正在处理 xxx …」），供设置页实时展示。 */
    private val _growthProgress = kotlinx.coroutines.flow.MutableStateFlow("")
    val growthProgress: kotlinx.coroutines.flow.StateFlow<String> = _growthProgress

    /**
     * 任务中心：当前账号的任务明细（只读展示 + 单条领取）。
     *
     * 旧 UI 只有「扫描并领取」一个黑盒按钮 + 一行汇总，看不到有哪些任务、进度到哪、
     * 奖励多少、为什么没领到。明细本来就在 `growthTasksMerged` 的响应里，补上映射即可。
     */
    private val _growthTasks = MutableStateFlow<List<GrowthService.TaskView>>(emptyList())
    val growthTasks: StateFlow<List<GrowthService.TaskView>> = _growthTasks
    private val _growthTaskAccount = MutableStateFlow("")
    val growthTaskAccount: StateFlow<String> = _growthTaskAccount
    private val _growthTasksBusy = MutableStateFlow(false)
    val growthTasksBusy: StateFlow<Boolean> = _growthTasksBusy
    private var growthTaskUid: String? = null
    /** 惰性加载的一次性标记：设置页 item 滚出视口再滚回来会重跑 LaunchedEffect。 */
    private var growthTasksTried = false

    /**
     * 加载 / 刷新任务明细（取第一个门控账号；无可用账号则清空并提示）。
     *
     * @param force 用户主动点「任务账号」行时传 true；页面首次进入用 false（不重复打网络）。
     */
    fun loadGrowthTasks(force: Boolean = false) {
        if (_growthTasksBusy.value) return
        if (!force && growthTasksTried) return
        growthTasksTried = true
        viewModelScope.launch {
            val acc = GrowthService.gatedAccounts().firstOrNull()
            if (acc == null) {
                growthTaskUid = null
                _growthTasks.value = emptyList()
                _growthTaskAccount.value = ""
                toast("没有可查询的账号（已跳过禁用与 global 账号）")
                return@launch
            }
            _growthTasksBusy.value = true
            growthTaskUid = acc.uid
            _growthTaskAccount.value = acc.nickname.ifBlank { acc.uid.take(8) }
            val list = runCatching {
                withContext(Dispatchers.IO) { GrowthService.tasks(acc) }
            }.getOrElse { e ->
                toast("任务列表拉取失败：${e.message ?: e.javaClass.simpleName}")
                emptyList()
            }
            _growthTasks.value = list
            _growthTasksBusy.value = false
        }
    }

    /** 单条领取（任务中心「领取」按钮）：先本地校验可领，成功后刷新明细。 */
    fun claimGrowthTask(code: String) {
        val uid = growthTaskUid ?: return
        viewModelScope.launch {
            val acc = Gateway.store.auths().firstOrNull { it.uid == uid } ?: return@launch
            runCatching {
                withContext(Dispatchers.IO) { GrowthService.claimOne(acc, code) }
            }.onSuccess { (c, e) ->
                toast(
                    if (c > 0 || e > 0) "已领取 +$c 积分" + (if (e > 0) " +$e 能量" else "")
                    // (0,0) 是上游「此前已领取」的幂等成功，不等于「本次没领到」。
                    else "领奖接口已确认（奖励此前可能已领取）",
                )
                loadGrowthTasks(force = true)
            }.onFailure { toast(it.message ?: "领取失败") }
        }
    }

    fun runGrowthTasks() {
        if (_growthBusy.value) return
        viewModelScope.launch {
            if (Gateway.store.auths().isEmpty()) {
                toast("请先添加账号")
                return@launch
            }
            _growthBusy.value = true
            _growthProgress.value = "正在准备…"
            // 扫描是「多账号 × 分片报名 + 行为事件上报 + 回读 + 领奖」的长链路（可能几十秒到几分钟），
            // 所以把逐账号进度实时推给 UI —— 否则用户面对一个静止的按钮只能干等，不知道进行到哪一步。
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    GrowthService.scanAll { msg -> _growthProgress.value = msg }
                }
            }.getOrElse { GrowthService.Report(emptyList()) }
            _growthBusy.value = false
            _growthProgress.value = ""
            _growthReport.value = r
            toast(r.summary())
        }
    }

    // ---------- 账号操作 ----------

    fun revive(uid: String) = io { Gateway.pool.revive(uid); "已解除冷却/熔断" }
    fun disable(uid: String) = io { Gateway.pool.setDisabled(uid, true); "已禁用" }
    fun enable(uid: String) = io { Gateway.pool.setDisabled(uid, false); "已启用" }

    /**
     * 暂停 / 恢复「选号」：账号仍在池里、状态与冷却都不动，只是不接新请求
     * （对齐上游 v1.12.0 的暂停选号）。多号轮换场景下比「禁用」轻得多 ——
     * 禁用是判死要重新启用，暂停随时可恢复。
     */
    fun pause(uid: String) = io { Gateway.pool.setPaused(uid, true); "已暂停选号" }
    fun resume(uid: String) = io { Gateway.pool.setPaused(uid, false); "已恢复选号" }
    fun remove(uid: String) = io { Gateway.pool.remove(uid); "已移除" }

    /**
     * 单账号签到。
     *
     * 修复点：原来无论成功失败都 `revive(uid)`（把上游刚给的冷却/熔断清掉，等于抹掉错误信号），
     * 且把上游的幂等成功「已签到」当成新签到成功。现在只在成功时 revive，并区分文案。
     */
    fun checkin(uid: String) = ioAsync("签到") {
        val a = Gateway.store.getAuth(uid) ?: return@ioAsync "账号不存在"
        // 企业版门控：上游对企业号拒绝签到（400 code 10001），如实说明而不是报「签到失败」。
        if (a.isEnterprise) return@ioAsync "企业版账号无签到（上游不支持该操作）"
        val r = runCatching { Gateway.upstream.checkin(a) }
        val v = r.getOrNull()
        if (v != null) {
            // 账号可用 → 解除冷却；但「处理中」不记签到时刻（见 isCheckinDone 注释）。
            Gateway.pool.revive(uid)
            if (Gateway.upstream.isCheckinDone(v)) Gateway.pool.markCheckin(uid)
            when (v) {
                "已签到" -> "今日已签到（无需重复）"
                "处理中" -> "上游处理中，稍后由巡检自动补签"
                else -> "签到成功"
            }
        } else {
            "签到失败：${r.exceptionOrNull()?.message ?: "未知错误"}"
        }
    }

    fun balance(uid: String) = ioAsync("余额刷新") {
        val a = Gateway.store.getAuth(uid) ?: return@ioAsync "账号不存在"
        val soonSec = Durations.parse(Gateway.store.config.pool.expiring_soon, 168 * 3600L)
        val r = runCatching { Gateway.upstream.balanceDetailed(a, soonSec) }
        val b = r.getOrNull()
        when {
            r.isFailure -> "获取失败：${r.exceptionOrNull()?.message ?: "未知错误"}"
            b == null || !b.known -> "上游未返回余额数据"
            else -> {
                // 带上最早到期批次：快过期额度才能被 prefer_expiring 优先消耗。
                Gateway.pool.setCredits(uid, b.remain, b.earliestAt, b.earliestRemaining)
                "余额 ${wbCreditsText(b.remain)}"
            }
        }
    }

    /** 全量签到：无账号时明确拒绝，不再假装「完成 0/0 = 成功」。 */
    fun checkinAll() = ioAsync("全量签到") {
        val accounts = Gateway.store.auths()
        if (accounts.isEmpty()) return@ioAsync "尚未添加账号"
        var ok = 0; var already = 0; var fail = 0; var skipped = 0; var pending = 0
        accounts.forEach { a ->
            // 企业号跳过（上游不支持），既不计成功也不计失败。
            if (a.isEnterprise) { skipped++; return@forEach }
            runCatching { Gateway.upstream.checkin(a) }
                .onSuccess { v ->
                    when {
                        // 处理中：不记签到时刻，计入「待补签」而不是失败/成功。
                        v == "处理中" -> pending++
                        Gateway.upstream.isCheckinDone(v) -> {
                            Gateway.pool.markCheckin(a.uid)
                            if (v == "已签到") already++ else ok++
                        }
                        // 未知返回值（上游改了回执形态）不能算成功：否则「手动签到」的汇总
                        // 会把失败报成「成功 N」，用户以为签上了、其实没有。
                        else -> {
                            fail++
                            LogBus.log("[task] 签到 ${a.nickname.ifBlank { a.uid.take(8) }}: 未知回执 $v")
                        }
                    }
                }
                .onFailure {
                    fail++
                    LogBus.log("[task] 签到 ${a.nickname.ifBlank { a.uid.take(8) }}: ${it.message}")
                }
        }
        buildString {
            append("成功 $ok")
            if (already > 0) append("，今日已签到 $already")
            if (pending > 0) append("，上游处理中 $pending（稍后补签）")
            if (skipped > 0) append("，企业版跳过 $skipped")
            if (fail > 0) append("，失败 $fail")
        }
    }

    /**
     * 全量刷新余额。
     *
     * toast 文案：**报可用积分总和**（用户要的是「现在还剩多少可用」），
     * 而不是只报「刷新了几个账号」—— 后者完全看不出刷新结果。
     * 失败账号仍逐个写进日志，便于定位是哪一个号取不到余额。
     */
    fun balanceAll() {
        if (_refreshingBalance.value) return
        viewModelScope.launch {
            _refreshingBalance.value = true
            val msg = withContext(Dispatchers.IO) {
                val accounts = Gateway.store.auths()
                if (accounts.isEmpty()) {
                    "尚未添加账号"
                } else {
                    var ok = 0; var fail = 0
                    var total = 0.0
                    val soonSec = Durations.parse(Gateway.store.config.pool.expiring_soon, 168 * 3600L)
                    accounts.forEach { a ->
                        runCatching { Gateway.upstream.balanceDetailed(a, soonSec) }
                            .onSuccess { b ->
                                if (b.known) {
                                    Gateway.pool.setCredits(a.uid, b.remain, b.earliestAt, b.earliestRemaining)
                                    ok++
                                    total += b.remain
                                } else {
                                    fail++
                                }
                            }
                            .onFailure {
                                fail++
                                LogBus.log("[task] 余额 ${a.nickname.ifBlank { a.uid.take(8) }}: ${it.message}")
                            }
                    }
                    buildString {
                        append("可用 ").append(wbCreditsText(total)).append(" 积分")
                        append("（").append(ok).append(" 个账号）")
                        if (fail > 0) append(" · 失败 ").append(fail).append(" 个")
                    }
                }
            }
            _refreshingBalance.value = false
            LogBus.log("[task] 全量余额：$msg")
            toast(msg)
        }
    }

    /**
     * 网络 / 凭证自检。
     *
     * 1.7.4 起自检做**真实动作**（临期先续期 → 用 Bearer 验证 AT → 查余额 → 探模型目录），
     * 结论直接回答「这个号现在能不能用、还剩多久、要不要重新登录」，而不是「token=有」。
     * 自检过程中得到的结论会写回系统：续期成功 → 落盘并入池；确认失效 → 标记 session dead。
     */
    fun diagnose(uid: String) = ioAsync("网络自检") {
        val a = Gateway.store.getAuth(uid) ?: return@ioAsync "账号不存在"
        val report = runCatching { Gateway.upstream.diagnose(a) }
            .getOrElse { return@ioAsync "自检异常：${it.message}" }
        report.renewed?.let {
            Gateway.store.saveAuth(it)
            Gateway.pool.reload()
        }
        if (report.dead) Gateway.pool.markSessionDead(uid)
        report.lines.forEach { LogBus.log("[diag] $it") }
        report.verdict
    }

    /**
     * 立即续期（详情页按钮）。
     *
     * 与定时保活的区别：**强制**触发一次 refresh，不看「还剩多久」——用户点它就是要现在续。
     * 仍然要求 RT 存在且未被判死，否则直接给「请重新登录」而不是打一次注定失败的请求。
     */
    fun renewCredential(uid: String) = ioAsync("续期") {
        val a = Gateway.store.getAuth(uid) ?: return@ioAsync "账号不存在"
        if (a.refresh_token.isBlank()) return@ioAsync "没有续期凭证，请重新登录"
        val r = runCatching { Gateway.upstream.keepalive(a, force = true) }
            .getOrElse { return@ioAsync "续期失败：${it.message}" }
        r.renewed?.let {
            Gateway.store.saveAuth(it)
            Gateway.pool.reload()
        }
        if (r.state == "dead") {
            Gateway.pool.markSessionDead(uid)
            return@ioAsync "续期凭证已失效，请重新登录"
        }
        r.message
    }

    /** 全部保活（设置页 / 面板同款动作）：把每个能续的号都续一次。 */
    fun keepaliveAll() = ioAsync("全部保活") {
        val n = com.wb.api.scheduler.Scheduler.keepaliveAll(force = true)
        if (n > 0) Gateway.pool.reload()
        if (n == 0) "没有需要续期的账号" else "已续期 $n 个账号"
    }

    private fun io(block: () -> String) {
        viewModelScope.launch(Dispatchers.IO) {
            val msg = runCatching { block() }.getOrElse { it.message ?: "操作失败" }
            toast(msg)
        }
    }

    private fun ioAsync(label: String, block: suspend () -> String) {
        viewModelScope.launch(Dispatchers.IO) {
            val msg = runCatching { block() }.getOrElse { it.message ?: "$label 失败" }
            toast(msg)
        }
    }

    // ---------- 登录凭证导入 / 导出（对齐上游 panel/import.go） ----------

    private val _importBusy = MutableStateFlow(false)
    val importBusy: StateFlow<Boolean> = _importBusy

    private val _importReport = MutableStateFlow<ImportReport?>(null)
    val importReport: StateFlow<ImportReport?> = _importReport

    /**
     * 从文件选择、**尚未写入**的导入内容（先预览、确认再导入）。
     *
     * 为什么要有这一层：导入会覆盖同名账号的凭证（等于换掉账号身份），误选一个文件就静默
     * 覆盖是不可接受的。上游 2026-11 的 panel 也改成了「选中先 dryRun 预览、确认再写入」。
     */
    data class ImportPending(
        val names: List<String>,
        val texts: List<String>,
        /** 本地预解析出的账号条数（不联网、不写盘）。 */
        val accountCount: Int,
        val errors: List<String> = emptyList(),
    )

    private val _importPending = MutableStateFlow<ImportPending?>(null)
    val importPending: StateFlow<ImportPending?> = _importPending

    fun clearImportPending() { _importPending.value = null }

    /**
     * 读取用户通过 SAF 选中的文件（可多选），暂存为待导入内容。
     *
     * 只做「读文本 + 本地预解析」：不联网换 uid、不写盘、不动号池 —— 预览阶段必须无副作用。
     * 单文件上限 [MAX_IMPORT_BYTES]：凭证 JSON 实际只有几 KB，超过上限几乎一定是选错文件，
     * 与其读到 OOM 不如明确拒绝。
     */
    fun stageFiles(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val names = ArrayList<String>()
            val texts = ArrayList<String>()
            val errors = ArrayList<String>()
            for (u in uris) {
                val nm = queryDisplayName(ctx, u) ?: u.lastPathSegment ?: "未命名文件"
                val t = runCatching { readTextLimited(ctx, u) }.getOrElse { e ->
                    errors += "$nm：读取失败（${e.message ?: "无法打开"}）"
                    null
                }
                if (t.isNullOrBlank()) {
                    if (errors.none { it.startsWith("$nm：") }) errors += "$nm：文件为空"
                    continue
                }
                // 预解析只统计条数，并提前暴露「不是合法 JSON」——避免用户在导入后才发现白选。
                runCatching { CredentialTransfer.parseRows(t).size }
                    .onSuccess { names += nm; texts += t }
                    .onFailure { errors += "$nm：${it.message ?: "格式不符"}" }
            }
            val count = texts.sumOf { t -> runCatching { CredentialTransfer.parseRows(t).size }.getOrDefault(0) }
            _importPending.value = ImportPending(names, texts, count, errors)
            toast(
                when {
                    count > 0 -> "已读取 ${names.size} 个文件、$count 条账号，确认后导入"
                    else -> "未解析到账号" + (errors.firstOrNull()?.let { "：$it" } ?: "")
                },
            )
        }
    }

    /** 把已暂存的文件内容真正导入（用户点了「确认导入」）。 */
    fun importPendingCredentials(fallbackRealm: String) {
        // 先判忙再清 pending：反过来会「清掉了暂存内容，但导入被忙标记拒绝」——
        // 用户看到的是「点了确认什么也没发生」，且要重新选文件。
        if (_importBusy.value) {
            toast("正在导入，请稍候")
            return
        }
        val p = _importPending.value ?: return
        if (p.texts.isEmpty()) return
        _importPending.value = null
        importCredentialsFrom(p.texts, fallbackRealm)
    }

    private fun queryDisplayName(ctx: android.content.Context, uri: android.net.Uri): String? =
        runCatching {
            ctx.contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst() && c.columnCount > 0) c.getString(0) else null }
        }.getOrNull()

    /**
     * 按字符流读文本并限制总长度。
     *
     * 不用 `InputStream.readNBytes`：它是 Java 9 API，minSdk 32 上不可用（编译期就会报错）。
     * 也不整份 `readText()`：那会把任意大的文件一次读进内存。
     */
    private fun readTextLimited(ctx: android.content.Context, uri: android.net.Uri): String {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val sb = StringBuilder()
            ins.bufferedReader(Charsets.UTF_8).use { br ->
                val buf = CharArray(8192)
                var total = 0
                while (true) {
                    val n = br.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_IMPORT_BYTES) {
                        throw java.io.IOException("文件超过 ${MAX_IMPORT_BYTES / 1024 / 1024}M 字符")
                    }
                    sb.append(buf, 0, n)
                }
            }
            return sb.toString()
        }
        throw java.io.IOException("无法打开文件")
    }


    /** 关闭「添加账号」弹层时清掉上一次的导入结果，避免下次打开残留。 */
    fun clearImportReport() { _importReport.value = null }

    /**
     * 导入粘贴的 cockpit 凭证（单个对象或数组）。
     *
     * 全程对齐上游 import.go 的 importCockpit：逐条校验 → 落盘（SaveAtomic）→ 入池（pool.Add）
     * → 解除冷却（pool.Revive），最后汇总 `{ok,total,imported,skipped,errors}`。
     * 解析失败（JSON 非法 / 空数组）时把 ok=false、原因放进 errors。
     */
    fun importCredentials(raw: String, fallbackRealm: String) =
        importCredentialsFrom(listOf(raw), fallbackRealm)

    /**
     * 从**多份**文本导入（多选文件 / 文件 + 粘贴混用）。
     *
     * 与单份导入的区别只在「解析阶段」：某一份不是合法 JSON 只记一条 error，不拖垮其余文件
     * —— 导入本来就是逐条容错（skipped），这里保持同一语义，避免「一个文件坏掉整体失败」。
     */
    fun importCredentialsFrom(texts: List<String>, fallbackRealm: String) {
        // 忙标记必须在 launch 之前置位：写在协程体内时，连点 / 「粘贴 + 文件」两路都能
        // 穿过检查，同一批凭证会被落盘两次（重复签到、重复刷余额、统计错乱）。
        if (_importBusy.value) return
        _importBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            _importReport.value = null
            val report = runCatching { importBlockingFrom(texts, fallbackRealm) }
                .getOrElse {
                    ImportReport(false, 0, 0, 0, listOf(it.message ?: "导入失败"))
                }
            _importReport.value = report
            _importBusy.value = false
            toast(
                if (report.ok) "导入完成：${report.summary()}"
                else "导入失败：${report.errors.firstOrNull() ?: "未知错误"}",
            )
        }
    }

    private suspend fun importBlocking(raw: String, fallbackRealm: String): ImportReport =
        importBlockingFrom(listOf(raw), fallbackRealm)

    private suspend fun importBlockingFrom(texts: List<String>, fallbackRealm: String): ImportReport {
        val errors = ArrayList<String>()
        val rows = ArrayList<kotlinx.serialization.json.JsonObject>()
        texts.forEachIndexed { i, t ->
            runCatching { CredentialTransfer.parseRows(t) }
                .onSuccess { rows += it }
                .onFailure { e ->
                    errors += if (texts.size > 1) {
                        "第 ${i + 1} 份内容解析失败：${e.message ?: "格式不符"}"
                    } else {
                        e.message ?: "解析失败"
                    }
                }
        }
        // 一份都解析不出来：什么都不写，直接判失败 —— 不能出现「报告成功但实际没导入」。
        if (rows.isEmpty()) return ImportReport(false, 0, 0, 0, errors)
        var imported = 0
        var skipped = 0
        var overwrites = 0
        // 批内 uid 去重（对齐上游 seenUID：同一批里同一账号只处理一次，其余计 skipped）。
        val seen = HashSet<String>()
        for (obj in rows) {
            // parseRows 返回原始对象列表（对齐 import.go:62-66 的 json.Unmarshal）；buildRow 承担
            // 「必填校验 + 字段映射」（对齐 import.go:75-112）。
            //
            // 「最小凭证」（只给了一对 token）走 NeedsUid 分支：用 access_token 向
            // /v2/plugin/login/account 换回 uid / nickname 再落盘。这与上游 importCockpit 的
            // 「uid 必填」并不冲突 —— 上游要求的是**最终能拿到合法 uid**，我们只是把这一步自动化，
            // 省掉用户手抄 uid。
            val resolved = resolveRow(CredentialTransfer.buildRow(obj, fallbackRealm))
            when (val row = resolved) {
                // 对齐 import.go:79-88：缺必填 / uid 非法 → 计入 skipped 并记明细。
                is CredentialTransfer.Row.Dropped -> {
                    skipped++
                    errors += "跳过：${row.reason}" + if (row.id.isNotEmpty()) "（id=${row.id}）" else ""
                }
                // resolveRow 已把 NeedsUid 解析成 Ready / Dropped；这里只是**穷尽性兜底**
                // （理论上不可达），避免以后新增凭证形态时静默漏掉。
                is CredentialTransfer.Row.NeedsUid -> {
                    skipped++
                    errors += "跳过：「最小凭证」缺少 uid 且无法自动换取" +
                        if (row.id.isNotEmpty()) "（id=${row.id}）" else ""
                }
                is CredentialTransfer.Row.Ready -> {
                    if (!seen.add(row.entry.uid)) {
                        skipped++
                        errors += "跳过：同一批内重复账号（uid=${row.entry.uid}）"
                        continue
                    }
                    var entry = row.entry
                    // 覆盖判定必须取「导入前」的快照（saveAuth 之后恒为 true，会全记成覆盖）。
                    val replacing = Gateway.store.getAuth(entry.uid) != null
                    // 顺带用 Bearer 校验一次、补全 uid/nickname；**失败绝不阻断导入**
                    // （对齐 import.go:133「失败仅记日志」）。
                    runCatching { fetchAndEnrich(entry) }
                        .onSuccess { enriched -> if (enriched != null) entry = enriched }
                        .onFailure { LogBus.log("[导入] Bearer 校验失败 uid=${entry.uid}: ${it.message}") }
                    // ① 落盘是唯一「必修项」：它失败才算 skipped。
                    val saved = runCatching { Gateway.store.saveAuth(entry) }
                        .onFailure {
                            skipped++
                            errors += "uid=${entry.uid}: 落盘失败：${it.message}"
                        }.isSuccess
                    if (!saved) continue
                    // ② 落盘成功后的副作用（入池 / 签到 / 余额）失败只记日志 —— 绝不能把
                    //    「已经写进磁盘的账号」再计成 skipped（否则报告与磁盘互相矛盾）。
                    runCatching {
                        Gateway.pool.reload()            // 入池（对齐 import.go:130 pool.Add）
                        Gateway.pool.revive(entry.uid)   // 解除冷却/熔断（对齐 import.go:131 pool.Revive）
                        // 顺带签到（幂等；失败仅记日志，不阻断）——对齐 import.go:145-149。
                        // global 域在本项目无 GlobalCompleteRegistration / ClaimTrial 等价实现，跳过。
                        if (entry.realm != "global" && !entry.isEnterprise) {
                            runCatching { Gateway.upstream.checkin(entry) }
                                // 「处理中」不是终态，不能记签到时刻（否则今天不会再补签）。
                                .onSuccess { r -> if (r == "ok" || r == "已签到") Gateway.pool.markCheckin(entry.uid) }
                                .onFailure { LogBus.log("[导入] 签到 uid=${entry.uid}: ${it.message}") }
                        }
                        // 同步余额：**与 OAuth 登录路径逐行一致**（见 LoginWebActivity 的
                        // saveAuth → checkin → balanceDetailed 三步）。不刷的话，新导入的账号
                        // 在池里没有积分，账号栏会显示 0。失败仅记日志，不阻断导入。
                        val soonSec = Durations.parse(Gateway.store.config.pool.expiring_soon, 168 * 3600L)
                        runCatching { Gateway.upstream.balanceDetailed(entry, soonSec) }
                            .onSuccess { b ->
                                if (b.known) {
                                    Gateway.pool.setCredits(entry.uid, b.remain, b.earliestAt, b.earliestRemaining)
                                }
                            }
                            .onFailure { LogBus.log("[导入] 余额 uid=${entry.uid}: ${it.message}") }
                        Gateway.pool.reload()
                    }.onFailure {
                        LogBus.log("[导入] 后置动作失败 uid=${entry.uid}: ${it.message}")
                    }
                    if (replacing) overwrites++
                    imported++
                }
            }
        }
        LogBus.log("[导入] 完成 total=${rows.size} imported=$imported overwrites=$overwrites skipped=$skipped")
        return ImportReport(true, rows.size, imported, skipped, errors, overwrites)
    }

    /**
     * 取一次上游账号信息用于补全：仅当本地 nickname 为空时才用校验结果覆盖，
     * uid 不一致时只记日志（**以凭证自带 uid 为准** —— 落盘文件名由它派生）。
     */
    /**
     * 把「最小凭证」（[CredentialTransfer.Row.NeedsUid]）补全成可落盘的 [CredentialTransfer.Row.Ready]：
     * 用 access_token 调 `/v2/plugin/login/account` 换取 uid / nickname。
     *
     * 换取失败（离线 / token 已失效）时按 skipped 处理并给出可读原因 —— 既不静默丢弃，
     * 也不用 refresh_token 之类硬凑一个假 uid（那会造成重复账号与无效入池）。
     */
    private suspend fun resolveRow(row: CredentialTransfer.Row): CredentialTransfer.Row {
        if (row !is CredentialTransfer.Row.NeedsUid) return row
        val pair = runCatching { Gateway.upstream.fetchLoginAccount(row.realm, row.accessToken) }.getOrNull()
        val uid = pair?.first.orEmpty()
        if (!CredentialTransfer.validUid(uid)) {
            return CredentialTransfer.Row.Dropped(
                row.id,
                "无法用 access_token 解析账号（uid 缺失；请确认凭证有效且网络可用）",
            )
        }
        val now = System.currentTimeMillis() / 1000
        return CredentialTransfer.Row.Ready(
            AuthEntry(
                uid = uid,
                nickname = row.nickname.ifBlank { pair?.second.orEmpty() },
                realm = row.realm,
                access_token = row.accessToken,
                refresh_token = row.refreshToken,
                access_expires_at = row.expiresAt,
                device_token = row.deviceToken,
                device_id = row.deviceId,
                enterprise_id = row.enterpriseId,
                domain = row.domain,
                created_at = now,
                updated_at = now,
            ),
            row.id,
        )
    }

    private suspend fun fetchAndEnrich(a: AuthEntry): AuthEntry? {
        val pair = Gateway.upstream.fetchLoginAccount(a.realm, a.access_token) ?: return null
        val (uid, nickname) = pair
        if (uid.isNotBlank() && uid != a.uid) {
            LogBus.log("[导入] 凭证 uid 与校验结果不一致（以凭证为准）：${a.uid} / $uid")
        }
        var e = a
        if (nickname.isNotBlank() && e.nickname.isBlank()) e = e.copy(nickname = nickname)
        return e
    }

    /**
     * 生成「复制登录凭证」用的 JSON（与上游 cockpitAccount 同名字段，单个对象）。
     * 账号不存在时返回 null。**属敏感信息，调用方需提示「请勿外传」。**
     */
    fun credentialJson(uid: String): String? =
        Gateway.store.getAuth(uid)?.let { CredentialTransfer.encode(it) }

    /**
     * 多账号批量导出（JSON 数组，带 uid/nickname/domain/expires_at 元数据）。
     *
     * @param uids 选中的 uid；不存在的 uid 静默跳过（列表刷新后可能已被移除）。
     * @return null 表示一个有效账号都没有（调用方提示而不是导出空数组）。
     */
    fun credentialJsonBulk(uids: Collection<String>): String? {
        val entries = uids.mapNotNull { Gateway.store.getAuth(it) }
        if (entries.isEmpty()) return null
        return CredentialTransfer.encodeBulk(entries)
    }

    /** 批量导出建议文件名（SAF 预填）。 */
    fun credentialFileName(prefix: String = "wb2api-credentials"): String =
        CredentialTransfer.suggestFileName(prefix)

    /**
     * 把文本写入用户通过 SAF 选定的位置（导出文件）。
     *
     * 用 `"wt"` 模式：用户选择「替换已有文件」时**必须截断**，否则新内容比旧文件短会留下旧尾巴，
     * 下次导入解析直接失败（典型的「看着导出成功、导入报错」）。
     */
    fun exportCredentialsToFile(uri: android.net.Uri, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.flush()
                } ?: return@runCatching false
                true
            }.getOrElse { e ->
                LogBus.log("[导出] 写入失败：${e.message}")
                false
            }
            toast(if (ok) "已导出 ${text.length} 个字符到所选位置" else "导出失败，请重试或换一个位置")
        }
    }

    // ---------- 请求归档 ----------

    /**
     * 把请求归档导出到**用户可见的设备存储**（MediaStore 的公共 Downloads 目录）。
     *
     * 为什么用 MediaStore 而不是应用私有目录：归档在 `filesDir/data/requests.jsonl`，
     * 那是个普通用户（以及任何文件管理器）都看不到的沙箱路径——用户反馈「即使开了归档
     * 也无法查看日志」正是这个原因。minSdk 32 起，写公共 Downloads **无需任何权限**，
     * 也不涉及运行时授权弹窗。
     */
    fun exportArchive() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val size = Gateway.store.requestLogSizeBytes()
            if (size <= 0L) { toast("暂无归档内容，先开启归档并产生一次请求"); return@launch }
            val name = "wb2api-requests-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date()) + ".jsonl"
            val ok = runCatching {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/x-ndjson")
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        put(
                            android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS + "/wb2api",
                        )
                    }
                }
                val uri = ctx.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
                ) ?: return@runCatching false
                ctx.contentResolver.openOutputStream(uri)?.use { out -> Gateway.store.copyRequestLogTo(out) }
                    ?: return@runCatching false
                true
            }.getOrElse { e ->
                LogBus.log("[sys] 导出归档失败：${e.message}")
                false
            }
            toast(if (ok) "已导出到「下载/wb2api/$name」" else "导出失败，请查看日志")
        }
    }

    /** 删除本地归档日志文件（只删 requests.jsonl，不动账号/状态/用量）。 */
    fun clearArchive() {
        viewModelScope.launch(Dispatchers.IO) {
            val n = Gateway.store.requestLogCount()
            val ok = Gateway.store.clearRequestLog()
            toast(
                when {
                    !ok -> "删除失败，请查看日志"
                    n > 0 -> "已删除归档日志（$n 条记录）"
                    else -> "归档本来就是空的"
                }
            )
        }
    }

    // ---------- 配置 ----------

    /** 保存并应用网关配置（会重建上游客户端）。 */
    fun saveConfig(cfg: GatewayConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            val listenChanged = Gateway.applyConfig(cfg)
            _config.value = cfg
            // 端口不能热生效（bind 只在启动时做一次）：文案必须说清楚，不能让用户以为
            // 已经在听新端口了 —— 那会变成「连不上又找不到原因」。
            toast(if (listenChanged) "配置已保存，端口将在重启网关后生效" else "配置已保存并生效")
        }
    }

    /**
     * 只更新界面偏好（主题/取色/模型列数…）：**不走 applyConfig**，不会重建上游客户端、
     * 也不会打断正在进行中的请求，切换即时生效。
     */
    fun updateUi(ui: UiCfg) {
        val cfg = Gateway.store.config.copy(ui = ui)
        Gateway.store.saveConfig(cfg)
        _config.value = cfg
        // 浮窗开关/外观变化后立刻对齐窗口（含「恢复本组默认」把 overlay 改回 true 的路径：
        // 旧实现只在开关的 onCheckedChange 里同步，恢复默认会出现「配置说开、屏上没有」）。
        runCatching { com.wb.api.overlay.AddressOverlay.sync(getApplication()) }
    }

    /** 恢复出厂默认（保留 api_key，避免把局域网鉴权关掉）。 */
    fun resetConfigDefaults() {
        viewModelScope.launch(Dispatchers.IO) {
            val fresh = GatewayConfig(api_key = Gateway.store.config.api_key)
            Gateway.applyConfig(fresh)
            _config.value = fresh
            // 恢复默认会把 overlay 置回 true：窗口要跟着回来，不能等下次 onResume。
            runCatching { com.wb.api.overlay.AddressOverlay.sync(getApplication()) }
            toast("已恢复默认设置")
        }
    }

    fun reloadConfig() { _config.value = Gateway.store.config }
}

/**
 * 请求归档统计。
 *
 * @param enabled    归档开关（关着时 UI 要提示「未启用」，而不是显示「0 条」）
 * @param count      已归档条数
 * @param bytes      文件字节数（导出前给用户一个量级预期）
 * @param updatedAt  最后写入时刻（epoch 秒，0 = 从未写入）
 */
data class ArchiveStats(
    val enabled: Boolean = true,
    val count: Int = 0,
    val bytes: Long = 0,
    val updatedAt: Long = 0,
)

/** 单文件导入上限（字符数）：凭证 JSON 实际只有几 KB，超出几乎一定是选错文件。 */
private const val MAX_IMPORT_BYTES = 4 * 1024 * 1024

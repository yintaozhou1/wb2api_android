package com.wb.api.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle
import com.wb.api.LoginWebActivity
import com.wb.api.core.LogBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 路由：5 个顶级 Tab + 账号详情二级页。 */
sealed class Route {
    data object Dashboard : Route()
    data object Accounts : Route()
    data object Models : Route()
    data object Logs : Route()
    data object Settings : Route()
    data class AccountDetail(val uid: String) : Route()
}

private val dockRoutes = listOf(Route.Dashboard, Route.Accounts, Route.Models, Route.Logs, Route.Settings)

/**
 * 二级页（账号详情）滑出动画时长（毫秒）。
 *
 * 「顶栏保持二级页形态」的延迟与 AnimatedContent 的滑出时长**必须共用这一个值** ——
 * 两处一旦不一致，就会出现「顶栏已经切回来、正文还在滑」或反之的错位。
 */
private const val DetailExitMs = 280L

private fun titleOf(r: Route): String = when (r) {
    Route.Dashboard -> "首页"
    Route.Accounts -> "账号"
    Route.Models -> "模型"
    Route.Logs -> "日志"
    Route.Settings -> "设置"
    is Route.AccountDetail -> "账号详情"
}

/**
 * 应用根：**内容层 + 悬浮玻璃顶栏 + 悬浮玻璃底栏**三件套。
 *
 * 布局要点（与系统栏的关系）：
 *  - 内容层用 Scaffold 的 contentPadding 避开状态栏 / 导航栏，并在其上挂
 *    `layerBackdrop(pageBackdrop)` 把「页面内容」记录成玻璃可采样的背景层；
 *  - 顶栏 / 底栏是**悬浮**在内容层之上的兄弟节点（不在背景层内，避免自我采样反馈），
 *    分别贴住内容层的上下边缘 —— 因此它们采样到的正是自己背后的页面内容；
 *  - 页面内容需要自己留出 `WbD.pageTop` / `WbD.pageBottom` 的净空（见 Screens.kt）。
 *
 * 交互要点：五个顶级页放在 `HorizontalPager` 里，所以
 *  ① 左右滑动内容区 = 切换栏目；② 直接按住底栏左右滑动 = 拖动指示器并切换；
 *  ③ 点击底栏 = 切换。三者都只改同一个 Pager，**指示器位置直接读取 Pager 的连续页码**，
 *  因此悬浮栏动画在任何输入下都连续、不会跳变。
 */
@Composable
fun AppRoot(vm: AppViewModel) {
    val ov by vm.overview.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val loadingModels by vm.loadingModels.collectAsStateWithLifecycle()
    val gatewayBusy by vm.gatewayBusy.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val hapticOn = LocalWbHaptics.current
    val ctx = LocalContext.current
    val loadingAccounts by vm.loadingAccounts.collectAsStateWithLifecycle()
    val refreshingBalance by vm.refreshingBalance.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // 动画总开关（设置页可关）：二级页进出场必须跟着它走，否则「关动画」之后这里还会滑一下。
    val animOn = LocalWbAnimations.current

    // 玻璃背景层：把页面内容记录进去，供顶栏 / 底栏 / 分段控件 / 按钮等采样。
    val pageBackdrop = rememberLayerBackdrop()

    // 五页 Pager：内容左右滑动即切换栏目。指示器位置 = 它的连续页码。
    val pagerState = rememberPagerState(pageCount = { dockRoutes.size })
    // （1.5.6 起不再需要实测页宽：底栏拖动已改为「遮罩跟手 + 松手一次切换」，
    //   不再往 Pager 里喂像素增量。）
    var detailUid by remember { mutableStateOf<String?>(null) }

    // 顶栏专用的「二级页 uid」：进入时立即跟随 detailUid，**退出时延迟到滑出动画结束**再清除。
    // 若不这样做，detailUid 一置 null，顶栏就立刻变回「账号」、返回箭头也跟着消失，而正文
    // 还在滑出 —— 一次返回被拆成「顶栏瞬变 + 正文滑走」两段，观感割裂。
    var detailBarUid by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(detailUid) {
        if (detailUid != null) {
            detailBarUid = detailUid
        } else if (detailBarUid != null) {
            // 与滑出动画同时长的延迟：动画播完，顶栏才跟着回到栏目形态。
            delay(DetailExitMs)
            detailBarUid = null
        }
    }

    val topIndex = pagerState.currentPage
    // 顶栏（标题 / 返回键 / 动作）跟随 detailBarUid（见上），正文则跟随 detailUid。
    val current: Route = detailBarUid?.let { Route.AccountDetail(it) } ?: dockRoutes[topIndex]

    // 底栏「力度」0..1（驱动放大 / 折射 / 高光）。
    // 内容区滚动（含切栏后的动画）时拉到 1；而「按住底栏不放」这种静止场景由
    // GlassTabBar 内部自己把力度推满（它知道手指是否还在屏上）。
    val press = remember { Animatable(0f) }
    val contentScrolling = pagerState.isScrollInProgress
    val glassActive = contentScrolling
    androidx.compose.runtime.LaunchedEffect(glassActive) {
        press.animateTo(
            targetValue = if (glassActive) 1f else 0f,
            animationSpec = if (glassActive) spring(0.6f, 500f) else spring(0.85f, 400f),
        )
    }
    val pageOffset: () -> Float = { pagerState.currentPage + pagerState.currentPageOffsetFraction }
    // 底栏条目只算一次（ImageVector + 文案），避免每次重组都重建列表。
    val dockItems = remember { dockRoutes.map { dockMeta(it) } }
    val pressValue: () -> Float = { press.value }

    // Android 17（API 37）起「访问局域网」是运行时权限：未授权时 PC 端无法用局域网地址
    // 访问网关（本机回环不受影响，那条链路由安装期权限 USE_LOOPBACK_INTERFACE 覆盖）。
    // 因此在「启动网关」这个动作上按需申请；授权与否都继续启动 —— 只把后果讲清楚，不阻断主流程。
    val permCtx = LocalContext.current
    val lanPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) vm.toast("未授予「本地网络」权限：Android 17 起局域网客户端将无法连接网关")
        vm.toggleGateway()
    }
    val toggleGateway: () -> Unit = {
        // 只在「启动」前申请（停止不需要任何权限）。
        if (!ov.running && needsLanPermission(permCtx)) {
            if (hapticOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            lanPermLauncher.launch(PERM_LOCAL_NETWORK)
        } else {
            vm.toggleGateway()
        }
    }

    var showAdd by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var toastText by remember { mutableStateOf<String?>(null) }

    val snackbar = remember { SnackbarHostState() }
    // 失败/错误类走 Snackbar（需要被注意到），操作回执类走 Toast（不打断）。
    androidx.compose.runtime.LaunchedEffect(msg) {
        msg?.let {
            val needsAttention = it.contains("失败") || it.contains("错误") || it.contains("异常") || it.contains("不可用")
            if (needsAttention) snackbar.showSnackbar(it) else toastText = it
            vm.consumeMessage()
        }
    }

    BackHandler(enabled = detailUid != null) { detailUid = null }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        CompositionLocalProvider(LocalPageBackdrop provides pageBackdrop) {
            Box(Modifier.fillMaxSize()) {
                // ---------- 内容层（被记录为玻璃背景）----------
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .layerBackdrop(pageBackdrop),
                ) {
                    // ★ 页面内容【退出】玻璃背景层作用域（LocalPageBackdrop -> null）。
                    //   原因：页面内部的玻璃控件（分段控件 / 页面内按钮 / 保存栏）位于
                    //   「被记录成背景层的子树」里；若它们再用 drawBackdrop 采样该层，就会形成
                    //     pageBackdrop --记录--> 控件自身图层 --采样--> pageBackdrop
                    //   的**循环显示列表**，HWUI 在合成时会无限递归，进程被直接杀掉
                    //   （1.4 版「打开任意非首页栏目即闪退」的根因）。
                    //   这些控件改用 wbGlass 的「无背景层」磨砂形态；顶栏 / 底栏 / Toast /
                    //   Loading 是内容层的**兄弟节点**（不在被记录的子树内），照旧用真玻璃。
                    CompositionLocalProvider(LocalPageBackdrop provides null) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                            when (dockRoutes[page]) {
                                Route.Dashboard -> DashboardScreen(
                                    ov, vm,
                                    onOpenAccounts = { scope.launch { pagerState.animateScrollToPage(1) } },
                                    onOpenModels = { scope.launch { pagerState.animateScrollToPage(2) } },
                                    onToggleGateway = toggleGateway,
                                )
                                Route.Accounts -> AccountsScreen(
                                    ov, vm,
                                    onAdd = { showAdd = true },
                                    onOpen = { detailUid = it.uid },
                                )
                                Route.Models -> ModelsScreen(
                                    models, loadingModels, vm,
                                    ui = config.ui,
                                    onUiChange = { vm.updateUi(it) },
                                )
                                // 日志的收集放在 LogsScreen 内部（原来挂在根节点，任意一条日志都会带动整棵根重组）。
                                Route.Logs -> LogsScreen(vm)
                                Route.Settings -> SettingsScreen(config, vm, onAbout = { showAbout = true })
                                else -> Unit
                            }
                        }

                        // 二级页（账号详情）：从右侧推入并覆盖 Pager。
                        //
                        // ★ 为什么用 AnimatedContent 而不是 AnimatedVisibility：
                        //   AnimatedVisibility 在**退出动画期间会继续重组它的 content**，而 content
                        //   里读的 detailUid 那时已经是 null → `if (uid != null)` 直接落空、内容被
                        //   瞬间清空，退出动画就变成「一个空盒在滑」——观感就是**返回没有动画**
                        //   （用户实测反馈）。AnimatedContent 会把**旧状态的内容一直保留到退出动画
                        //   结束**，动画全程页面完整可见，因此退出与进入一样有真实可见的滑动。
                        //
                        // ★ 两端内容都包一层 fillMaxSize：让容器尺寸恒定。否则 AnimatedContent 默认的
                        //   SizeTransform 会在「满屏二级页 ↔ 空内容」之间做尺寸动画（等于把页面裁剪着
                        //   缩小），又是一种观感缺陷。
                        //
                        // ★ 进入 / 离开分开写 transitionSpec（AnimatedContent 的 exit 方向必须显式描述，
                        //   不像 AnimatedVisibility 那样只写一次即可）：进入时旧内容本就是空的、用
                        //   ExitTransition.None 跳过；离开时新内容是空的、用 EnterTransition.None 跳过。
                        AnimatedContent(
                            targetState = detailUid,
                            transitionSpec = {
                                when {
                                    // 关闭动画（设置页开关）时直接切换，不做任何过渡。
                                    !animOn -> EnterTransition.None togetherWith ExitTransition.None
                                    targetState != null -> (slideInHorizontally(
                                        tween(DetailExitMs.toInt(), easing = FastOutSlowInEasing),
                                    ) { it / 4 } + fadeIn(tween(200))) togetherWith ExitTransition.None
                                    else -> EnterTransition.None togetherWith (slideOutHorizontally(
                                        tween(DetailExitMs.toInt(), easing = FastOutSlowInEasing),
                                    ) { it / 4 } + fadeOut(tween(160)))
                                }
                            },
                            label = "accountDetail",
                        ) { uid ->
                            Box(Modifier.fillMaxSize()) {
                                if (uid != null) {
                                    // ★ 退出动画期间 ov 可能已经把该账号剔除 ——「移除该账号」的路径是
                                    //   `vm.remove(v.uid)` 先改 ov.views，紧接着 `onRemoved()` 才把
                                    //   detailUid 置 null。此时若直接查 ov，会查到 null，滑出去的就是
                                    //   「账号不存在或已被移除」的空态页。
                                    //   用 remember(uid) 的快照兜住进入时的那一份数据；live 一旦有值就
                                    //   刷新它（余额等实时更新照旧生效），live 变 null 时保留最后一份。
                                    val live = ov.views.firstOrNull { it.uid == uid }
                                    val shown = remember(uid) { mutableStateOf(live) }
                                    LaunchedEffect(live) { if (live != null) shown.value = live }
                                    val v = shown.value
                                    if (v != null) {
                                        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                                            AccountDetailScreen(
                                                v = v,
                                                vm = vm,
                                                onRemoved = { detailUid = null },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ---------- 悬浮玻璃顶栏 ----------
                WbGlassTopBar(
                    title = titleOf(current),
                    // 与标题同源（current 由 detailBarUid 派生）：退出动画期间返回键继续保持可见，
                    // 动画结束后才与标题一起消失 —— 避免「返回键先没了、页面还在滑」。
                    canBack = current is Route.AccountDetail,
                    onBack = {
                        if (hapticOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        detailUid = null
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = padding.calculateTopPadding()),
                ) {
                    // ActionBar：当前页最常用的动作（与页面内按钮并存，便于单手触达）。
                    when (current) {
                        // 刷新的加载反馈**就在点击处**（图标就地旋转，见 WbRefreshAction）：
                        // 原来是页面内部的进度条，离点击处很远还会与相邻组件挤在一起。
                        // 账号页最常用的动作是「刷新余额」（看还剩多少可用），而不是重载配置。
                        Route.Accounts -> WbRefreshAction(refreshingBalance, "刷新余额") { vm.balanceAll() }
                        Route.Models -> WbRefreshAction(loadingModels, "刷新模型") { vm.loadModels(true) }
                        Route.Logs -> IconButton(onClick = {
                            if (hapticOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            LogBus.clear()
                            vm.toast("日志已清空")
                        }) {
                            Icon(MiuixIcons.Delete, contentDescription = "清空日志", tint = MiuixTheme.colorScheme.primary)
                        }
                        Route.Dashboard -> IconButton(onClick = {
                            if (hapticOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            showAbout = true
                        }) {
                            Icon(MiuixIcons.Info, contentDescription = "关于", tint = MiuixTheme.colorScheme.primary)
                        }
                        else -> Unit
                    }
                }

                // ---------- 悬浮玻璃底栏 ----------
                WbGlassTabBar(
                    pageBackdrop = pageBackdrop,
                    items = dockItems,
                    selectedIndex = topIndex,
                    pageOffset = pageOffset,
                    press = pressValue,
                    onTap = { i ->
                        // 账号详情是**盖在 Pager 之上的二级页浮层**：不先退出它，点底栏只会切换
                        // 底下的 Pager，详情页仍盖在最上层 —— 用户看到的就是「点底栏没反应，
                        // 必须手动返回才能切栏目」。所以切栏目=先退出二级页。
                        detailUid = null
                        scope.launch { pagerState.animateScrollToPage(i) }
                    },
                    // 指示器要不要「严格实时跟手」：只有**内容区滚动中**才需要无动画跟随；
                    // 底栏自己的拖动不再驱动 Pager（见 GlassTabBar 手势块的说明），
                    // 所以按下/拖动期间由组件内部用平滑动画把遮罩送过去。
                    live = { pagerState.isScrollInProgress },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = padding.calculateBottomPadding())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )

                // Toast 层：浮在底栏之上，不拦截触摸。
                WbToastHost(
                    text = toastText,
                    onDismiss = { toastText = null },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = padding.calculateBottomPadding())
                        // 让过**页面里所有**常驻底部元素（悬浮底栏 + 页面动作条），
                        // 否则会压在「复制全部」这类按钮上（详见 WbD.toastBottom）。
                        .padding(bottom = WbD.toastBottom),
                )

                // 启停网关的 Loading 遮罩（玻璃卡片）。
                WbBusyOverlay(
                    visible = gatewayBusy != null,
                    label = gatewayBusy ?: "处理中…",
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        // ---------- 弹窗 / 抽屉 ----------

        AddAccountSheet(
            show = showAdd,
            vm = vm,
            onDismiss = { showAdd = false },
            onPick = { realm ->
                showAdd = false
                // 授权页放在**独立 Activity + 纯 View WebView**里：脱离 Compose 树后，
                // 输入法不再被 Compose 每次重组打断 WebView 焦点（修复字符倒序 / 光标跳首），
                // 且该页自带「返回」，不会阻塞主界面与底部导航。
                runCatching {
                    ctx.startActivity(
                        Intent(ctx, LoginWebActivity::class.java).putExtra(LoginWebActivity.EXTRA_REALM, realm),
                    )
                }
            },
        )

        if (showAbout) {
            OverlayDialog(
                show = true,
                title = "关于 wb2api",
                summary = "OpenAI 兼容网关 · Android 原生版",
                onDismissRequest = { showAbout = false },
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WbD.gapS)) {
                    Text(
                        "把上游账号包装成本地 OpenAI 兼容接口：多账号池、熔断冷却、会话粘性、定时任务。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        "已适配 /v1/chat/completions 与 /v1/responses：工具调用双向转换、思考档位透传、" +
                            "缓存命中（cached_tokens）与推理用量回传。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        "免责声明：本应用为个人学习与研究用途的非官方客户端，与上游服务提供方无任何关联、" +
                            "未获其授权或认可；请自行遵守相关服务条款与所在地法律法规，使用本应用产生的一切后果由使用者自行承担。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        "版本 ${com.wb.api.BuildConfig.VERSION_NAME}（versionCode ${com.wb.api.BuildConfig.VERSION_CODE}）· Miuix UI",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    WbButton(
                        "访问开源项目",
                        onClick = {
                            runCatching {
                                ctx.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://sharechain.qq.com/42e2768703ac7fd23d461423fa493198"),
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    WbButton(
                        "知道了",
                        onClick = { showAbout = false },
                        modifier = Modifier.fillMaxWidth(),
                        variant = WbButtonVariant.Tonal,
                    )
                }
            }
        }
    }
}

/**
 * 悬浮液态玻璃顶栏：自绘（而非 Miuix `SmallTopAppBar`）——因为需要把玻璃修饰符挂在
 * 背景上，且要让内容从它下方穿过（滚动时被玻璃折射 / 模糊）。
 */
@Composable
private fun WbGlassTopBar(
    title: String,
    canBack: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit,
) {
    val shape = remember { RoundedRectangle(24.dp, RoundedCornerStyle.Continuous) }
    // 「应用名 + 版本」：名称取自 PackageManager（跟随实际安装的 label），版本取自
    // BuildConfig —— 都不硬编码，改 versionName 或改应用名后顶栏自动跟着变。
    val ctx = LocalContext.current
    val badge = remember(ctx) {
        val name = runCatching {
            ctx.packageManager.getApplicationLabel(ctx.applicationInfo).toString()
        }.getOrNull().orEmpty().ifBlank { "wb2api" }
        "$name ${com.wb.api.BuildConfig.VERSION_NAME}"
    }
    // 用 Box 叠层（而非 Row 顺序排布）：这样中央的 badge 是相对**整条顶栏**居中的，
    // 不会因为左侧「返回键 / 标题」「右侧动作按钮」宽度不同而被推得偏左或偏右。
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .height(52.dp)
            .wbGlass(
                shape = shape,
                tint = WbGlass.neutralTint(),
                lensHeight = 14.dp,
                lensAmount = 14.dp,
                enableShadow = true,
            )
            .padding(start = 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左：返回键（二级页）+ 页面标题。
        //
        // 用「两侧等 weight(1f)」而不是 Box 叠层来承载中央徽标：叠层里徽标虽然对齐到中心点，
        // 但左侧标题一旦变长（如「账号详情」）就会和它**重叠** —— 叠层没有互相避让的机制，
        // 徽标变大后这个风险更明显。等权分配让两侧自动收缩（标题/徽标各自省略号），
        // 中央元素才真正既居中、又不被两侧压到。
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canBack) {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, contentDescription = "返回", tint = MiuixTheme.colorScheme.onSurface)
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title,
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 中：应用名 + 版本。
        //
        // 原为 footnote2 —— **11sp**（Miuix AAR 反编译实证值）：低于 12sp 的可读下限，且与 18sp
        // 的页面标题并排时落差过大，观感就是「一行小灰字浮在中间，跟页面不搭」。
        // 现提到 footnote1（13sp）+ Medium 字重：清晰、有合适的存在感，又仍低于标题，
        // 保持「环境标识」应有的从属层级（不抢主标题）。
        // widthIn + 省略号是防御性约束：徽标文案随应用名/版本号变化，窄屏上不能无限变宽。
        Text(
            badge,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 128.dp)
                .padding(horizontal = 6.dp),
        )
        // 右：当前页动作（与左侧等权，保证中央徽标严格居中）
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { action() }
    }
}

/** Android 17 起「访问局域网」是运行时权限（NEARBY_DEVICES 权限组）。 */
private const val PERM_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** 该权限只在 Android 17+ 存在；低版本系统上恒视为「不需要」。 */
private fun needsLanPermission(ctx: android.content.Context): Boolean =
    android.os.Build.VERSION.SDK_INT >= 37 &&
        ctx.checkSelfPermission(PERM_LOCAL_NETWORK) != android.content.pm.PackageManager.PERMISSION_GRANTED

private fun dockMeta(route: Route): Pair<ImageVector, String> = when (route) {
    Route.Dashboard -> MiuixIcons.Home to "首页"
    Route.Accounts -> MiuixIcons.Contacts to "账号"
    Route.Models -> MiuixIcons.Layers to "模型"
    Route.Logs -> MiuixIcons.ListView to "日志"
    Route.Settings -> MiuixIcons.Settings to "设置"
    is Route.AccountDetail -> MiuixIcons.Contacts to "账号"
}

@Composable
private fun AddAccountSheet(
    show: Boolean,
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    // 弹层内部两种模式：pick = 选域 OAuth 登录入口；import = 粘贴凭证导入。
    var mode by remember { mutableStateOf("pick") }
    var text by remember { mutableStateOf("") }
    var fallbackRealm by remember { mutableStateOf("cn") }
    val busy by vm.importBusy.collectAsStateWithLifecycle()
    val report by vm.importReport.collectAsStateWithLifecycle()
    // 每次打开复位（否则会残留上次粘贴的敏感文本 / 导入结果）。
    LaunchedEffect(show) {
        if (show) {
            mode = "pick"; text = ""; fallbackRealm = "cn"; vm.clearImportReport()
        }
    }
    OverlayBottomSheet(show = show, title = "添加账号", onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                // 内容可能超出弹层高度（尤其粘贴长凭证时）：允许纵向滚动，保证
                // 「导入 / 返回」按钮在任何字符量下都可达 —— 此前长文本会把按钮顶出屏幕。
                .verticalScroll(rememberScrollState())
                .padding(start = WbD.gapL, end = WbD.gapL, top = WbD.gapXs, bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(WbD.gapS),
        ) {
            // 两个子视图之间做横向滑动 + 淡入淡出过渡 —— 原先的硬切换割裂感很强。
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    val forward = targetState == "import"
                    val enter = slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn(tween(220))
                    val exit = slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut(tween(160))
                    enter togetherWith exit
                },
                label = "addAccountMode",
            ) { m ->
            Column(verticalArrangement = Arrangement.spacedBy(WbD.gapS)) {
            if (m == "pick") {
                Text(
                    "将打开 OAuth 授权页；登录完成后自动拉取凭证并入池，顺带签到与同步余额，无需重启。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                WbButton(
                    text = "CN 账号（codebuddy.cn）",
                    onClick = { onPick("cn") },
                    modifier = Modifier.fillMaxWidth(),
                    variant = WbButtonVariant.Tonal,
                )
                WbButton(
                    text = "Global 账号（workbuddy.ai）",
                    onClick = { onPick("global") },
                    modifier = Modifier.fillMaxWidth(),
                    variant = WbButtonVariant.Tonal,
                )
                // 与「CN / Global 账号」并列的第三种入口：粘贴凭证导入（对齐上游 panel/import.go）。
                WbButton(
                    text = "导入登录凭证（粘贴 JSON）",
                    onClick = { mode = "import" },
                    modifier = Modifier.fillMaxWidth(),
                    variant = WbButtonVariant.Tonal,
                    icon = MiuixIcons.Import,
                )
                WbTextButton("取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            } else {
                Text(
                    "粘贴登录凭证 JSON。最小凭证只需 access_token 与 refresh_token 两项" +
                        "（uid 会自动换取、域按下方选择兜底）； cockpit tools 导出的完整对象" +
                        "（单个或数组）同样接受。导入后自动落盘、入池、解除冷却并同步余额。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                WbHintBlock(
                    title = "凭证等同账号，请勿外传",
                    body = "access_token / refresh_token 即账号访问权限，粘贴或转存时请确保环境可信。",
                    icon = MiuixIcons.Info,
                )
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "粘贴登录凭证 JSON",
                    // 限高：凭证可能很长，不限高的话输入框会把下方按钮一路顶出屏幕
                    // （实测「按钮挤出屏幕外、点不到」）。超出部分在框内滚动。
                    modifier = Modifier.fillMaxWidth().heightIn(max = 168.dp),
                )
                Text(
                    "凭证未带 domain 字段时，按此域兜底（最小凭证没带 domain，就按这里选）：",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                WbSegmented(
                    listOf("CN 账号", "Global 账号"),
                    selectedIndex = if (fallbackRealm == "cn") 0 else 1,
                    onSelect = { fallbackRealm = if (it == 0) "cn" else "global" },
                )
                WbButton(
                    text = if (busy) "导入中…" else "导入",
                    onClick = { vm.importCredentials(text, fallbackRealm) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && text.isNotBlank(),
                    icon = MiuixIcons.Import,
                )
                report?.let { r ->
                    WbHintBlock(
                        title = if (r.ok) "导入结果：${r.summary()}" else "导入失败",
                        body = buildString {
                            if (!r.ok) append(r.errors.joinToString("\n"))
                            else if (r.errors.isEmpty()) append("全部导入成功。")
                            else append(r.errors.take(20).joinToString("\n"))
                        },
                        icon = MiuixIcons.Info,
                    )
                }
                WbTextButton("返回", onClick = { mode = "pick" }, modifier = Modifier.fillMaxWidth())
            }
            }
            }
        }
    }
}

package com.wb.api.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import android.content.ClipData
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wb.api.core.Durations
import com.wb.api.core.GatewayConfig
import com.wb.api.core.LogBus
import com.wb.api.core.LogChannel
import com.wb.api.core.LogEntry
import com.wb.api.core.LoggingCfg
import com.wb.api.core.Mask
import com.wb.api.core.PoolCfg
import com.wb.api.core.PrettyJson
import com.wb.api.core.ScheduleCfg
import com.wb.api.core.SessionStickyCfg
import com.wb.api.core.UiCfg
import com.wb.api.model.ModelInfo
import com.wb.api.pool.AccountView
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Alarm
import top.yukonga.miuix.kmp.icon.extended.BankCards
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.FileDownloads
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import com.wb.api.upstream.Up
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val pageTv = PaddingValues(
    start = WbD.pageH, end = WbD.pageH, top = WbD.pageTop, bottom = WbD.pageBottom,
)

// 浮动玻璃表头的占位高度（= 悬浮顶栏净空 + 表头设计高度 + 表头呼吸间距 headerGap）。
// 表头是「浮」在列表之上的兄弟节点、不占内容流，所以必须由列表自己用 contentPadding 留白
// （详见 WbGlassHeaderPage）。
//
// 模型 / 账号两页都改用**实测高度**（见各自 onSizeChanged）；下面两个常量只在首帧
// （还没量到高度时）兜底 —— 宁可略大一点：小了会让首项被表头压住一瞬。
private val AccountsHeaderSpace = WbD.pageTop + 112.dp
private val ModelsHeaderSpace = WbD.pageTop + 142.dp
private val LogsHeaderSpace = WbD.pageTop + 60.dp
private val LogsFooterSpace = 156.dp

// ============================================================
// 首页（顺序：服务开关 → 快捷操作 → 健康总览 → 运行指标 → 接入信息）
// ============================================================

@Composable
fun DashboardScreen(
    ov: Overview,
    vm: AppViewModel,
    onOpenAccounts: () -> Unit,
    onOpenModels: () -> Unit,
    /** 启动/停止网关（入口在首页顶部卡片）。抽成参数是为了在启动前按需申请
     *  Android 17 的「本地网络」权限 —— 见 AppRoot 的 toggleGateway。 */
    onToggleGateway: () -> Unit,
) {
    val noAccounts = !ov.hasAccounts
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = pageTv,
        verticalArrangement = Arrangement.spacedBy(WbD.gap),
    ) {
        item { ServiceHeroCard(ov, vm, onToggleGateway) }

        item {
            // 「账号概览」= 账号健康指标（上）+ 维护动作（下），**合成一张卡**：
            //   ① 逻辑上本就是一回事（先看池子状态、再对池子做维护），拆两张卡只是多一层层级；
            //   ② 视觉上原来两张卡各自内嵌了一层异色底（surfaceContainerHighest），而页面其它
            //      卡片统一是「卡片底色 + 行 + 图标底座」——不同源，所以读起来突兀。
            //   现在取消所有内嵌色块：指标段用 1dp 竖线分栏，动作格用 [WbIconTile] 图标底座，
            //   与「运行指标」卡完全同一套语言；两块之间用与卡片其它分隔线同色的 1dp 横线。
            WbSection("账号概览", subtitle = if (noAccounts) "需先添加账号" else null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = WbD.cardPad, vertical = WbD.rowPadV),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WbMiniStat("账号", "${ov.counts.total}", MiuixTheme.colorScheme.primary, Modifier.weight(1f), onClick = onOpenAccounts)
                    WbStatDivider()
                    WbMiniStat("可用", "${ov.counts.healthy}", WbPalette.ok, Modifier.weight(1f))
                    WbStatDivider()
                    WbMiniStat("冷却", "${ov.counts.cooling}", WbPalette.warn, Modifier.weight(1f))
                    WbStatDivider()
                    WbMiniStat(
                        "禁用", "${ov.counts.disabled}",
                        if (ov.counts.disabled > 0) WbPalette.danger else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        Modifier.weight(1f),
                    )
                }
                WbCreditsRow(ov, onClick = onOpenAccounts)
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = WbD.cardPad),
                    color = MiuixTheme.colorScheme.dividerLine,
                )
                Column(
                    Modifier.padding(WbD.cardPad),
                    verticalArrangement = Arrangement.spacedBy(WbD.gapXs),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(WbD.gapS)) {
                        WbActionCell("全量签到", MiuixIcons.Alarm, onClick = { vm.checkinAll() }, modifier = Modifier.weight(1f), enabled = !noAccounts)
                        WbActionCell("刷新余额", MiuixIcons.BankCards, onClick = { vm.balanceAll(); onOpenAccounts() }, modifier = Modifier.weight(1f), enabled = !noAccounts)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(WbD.gapS)) {
                        // 与「刷新余额」保持一致：执行完顺手切到账号栏，让用户直接看到结果。
                        WbActionCell("重载账号", MiuixIcons.Refresh, onClick = { vm.reloadAccounts(); onOpenAccounts() }, modifier = Modifier.weight(1f))
                        WbActionCell("拉取模型", MiuixIcons.Layers, onClick = { vm.loadModels(); onOpenModels() }, modifier = Modifier.weight(1f), enabled = !noAccounts)
                    }
                }
            }
        }

        item {
            WbSection("运行指标") {
                WbRow("运行时长", uptimeText(ov), leading = { WbIconTile(MiuixIcons.Timer, MiuixTheme.colorScheme.primary, size = 28.dp) }, showDivider = true)
                WbRow("已完成请求", "${ov.completed}", leading = { WbIconTile(MiuixIcons.Send, WbPalette.info, size = 28.dp) }, showDivider = true)
                WbRow(
                    "成功率",
                    if (ov.completed == 0L) "—" else "${(ov.successRate * 100).toInt()}%",
                    valueColor = if (ov.completed == 0L) null else if (ov.successRate >= 0.9) WbPalette.ok else WbPalette.warn,
                    leading = { WbIconTile(MiuixIcons.Ok, WbPalette.ok, size = 28.dp) },
                    showDivider = true,
                )
                WbRow("在途请求 / 会话粘性", "${ov.inFlight} / ${ov.sticky}", leading = { WbIconTile(MiuixIcons.Link, MiuixTheme.colorScheme.primary, size = 28.dp) })
            }
        }

        item {
            WbSection("接入信息") {
                if (ov.apiKey.isBlank()) {
                    WbBanner(
                        "未设置访问密钥：局域网内任何设备都能调用本机网关。建议在「设置 → 服务」里填一个密钥。",
                        WbBannerKind.Warn, icon = MiuixIcons.Lock,
                        modifier = Modifier.padding(horizontal = WbD.gapS, vertical = WbD.gapS),
                    )
                }
                WbCopyRow("Base URL（本机）", "http://127.0.0.1:${ov.port}/v1", showDivider = true, onCopied = { vm.toast("已复制 Base URL") })
                // 局域网地址由 ViewModel 在后台线程算好后随 Overview 下发 ——
                // 原实现在这里直接调 Net.lanIp()，等于**每次重组都在主线程枚举一遍网卡**
                // （Overview 每 2 秒刷新一次，就是每 2 秒一次主线程 NetworkInterface 遍历）。
                ov.lanIp?.let { lan ->
                    WbCopyRow("Base URL（局域网）", "http://$lan:${ov.port}/v1", showDivider = true, onCopied = { vm.toast("已复制局域网地址") })
                }
                // 密钥默认脱敏（原文只露前后几位），可点眼睛查看，右侧有独立的一键复制。
                WbSecretRow(
                    label = "API Key",
                    secret = ov.apiKey,
                    leading = { WbIconTile(MiuixIcons.Lock, WbPalette.warn, size = 28.dp) },
                    onCopied = { vm.toast("已复制 API Key") },
                )
            }
        }
    }
}

/** 运行时长文案；网关启动过（运行中 / 上次跑过）时附带「本次消耗的积分」。 */
private fun uptimeText(ov: Overview): String {
    val consumed = if (ov.creditConsumed > 0.0) " · 消耗 ${wbCreditsText(ov.creditConsumed)} 积分" else ""
    return when {
        ov.running -> Durations.format(ov.uptime) + consumed
        ov.uptime > 0 -> "已停止（上次 ${Durations.format(ov.uptime)}）$consumed"
        else -> "未运行"
    }
}

/** 纯时长文案（服务卡片副标题用，不掺「消耗积分」，免得副标题过长）。 */
private fun uptimePlain(ov: Overview): String = when {
    ov.running -> Durations.format(ov.uptime)
    ov.uptime > 0 -> "已停止（上次 ${Durations.format(ov.uptime)}）"
    else -> "未运行"
}

@Composable
private fun ServiceHeroCard(ov: Overview, vm: AppViewModel, onToggle: () -> Unit) {
    // 具体实现见 Widgets.kt：颜色/文案/按钮全部走过渡动画（不再是 if/else 硬切换）。
    WbHeroCard(
        running = ov.running,
        port = ov.port,
        uptimeLabel = uptimePlain(ov),
        lastError = ov.lastError,
        onToggle = onToggle,
    )
}

/**
 * 首页「账号概览」里的**总共可用积分**一行：图标 + 文案 + **滚动增长**的数值，整行可点进
 * 账号页。数值用 `animateFloatAsState` 做一次 0→目标 / 变化时的滚动动画（关掉动画偏好时
 * `tween(0)` 退化为瞬时），让「余额变了」看得见，而不是硬切换。
 */
@Composable
private fun WbCreditsRow(ov: Overview, onClick: () -> Unit) {
    val animOn = LocalWbAnimations.current
    val value by animateFloatAsState(
        targetValue = ov.creditsTotal.toFloat(),
        animationSpec = tween(if (animOn) 520 else 0),
        label = "creditsTotal",
    )
    val hasData = ov.creditsAccounts > 0 || ov.creditsUnlimited
    val suffix = if (ov.creditsUnlimited) " · 含不限" else ""
    Row(
        Modifier
            .fillMaxWidth()
            .wbPress(onClick = onClick)
            .heightIn(min = WbD.tapMin)
            // 竖向节奏对齐卡片内的标准行（WbRow 用 rowPadV）：此前用的是 gapS(6dp)，
            // 比上方「账号概览」四个数字行矮一截，读起来「上宽下窄、被分割线压住」。
            .padding(horizontal = WbD.cardPad, vertical = WbD.rowPadV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 无左侧图标：原实现是「左（图标 + 两行文字）/ 右（单个数值）」，
        // 左重右轻、读起来割裂且不对称（用户反馈）。图标去掉后改为标准的
        // 「左标签 / 右数值」行；数值放大到 title2 与左标签形成对等重量，
        // 它本就是这一行的视觉主体。
        Column(Modifier.weight(1f)) {
            Text(
                "总共可用积分",
                style = MiuixTheme.textStyles.body1,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            WbHSpace(1.dp)
            Text(
                if (!hasData) "暂无余额数据" else "${ov.creditsAccounts} 个账号$suffix",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        WbHSpace(WbD.gap)
        Text(
            if (hasData) wbCreditsText(value.toDouble()) + " 积分" else "—",
            style = MiuixTheme.textStyles.title2,
            color = WbPalette.warn,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 玻璃表头里的「动作条」：图标 + 标题(+副标题) + 尾部图标，透明底、整行可点。 */
@Composable
private fun WbGlassActionRow(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    /** 行尾槽位。留空则该行右侧为空；「添加账号」用它放 CN/GL 账号数（取代重复的 ➕）。 */
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WbD.radiusS))
            .wbPress(onClick = onClick)
            .padding(horizontal = WbD.gapS, vertical = WbD.gapS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WbIconTile(icon, MiuixTheme.colorScheme.primary, size = 28.dp)
        WbHSpace(WbD.gap)
        Column(Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body1)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
        }
        if (trailing != null) {
            WbHSpace(WbD.gapS)
            trailing()
        }
    }
}

/**
 * 「添加账号」行右侧的账号数概览：按 realm 显示 `CN n` / `GL n` 两行。
 *
 * 取代原先行尾那个 ➕ —— 它与左侧图标底座里的 ➕ 重复，语义含糊（用户反馈「两个加号」）。
 * 数字用 [animateIntAsState] 做一次增长滚动，账号增删时可见地变化；池内为空时整体不显示，
 * 避免空态下出现「CN 0 / GL 0」的噪声。
 */
@Composable
private fun WbRealmCounts(views: List<AccountView>) {
    val cn = views.count { it.realm != "global" }
    val gl = views.count { it.realm == "global" }
    if (cn == 0 && gl == 0) return
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        WbRealmCount("CN", cn, MiuixTheme.colorScheme.primary)
        WbRealmCount("GL", gl, WbPalette.info)
    }
}

@Composable
private fun WbRealmCount(label: String, count: Int, color: Color) {
    val animOn = LocalWbAnimations.current
    val n by animateIntAsState(
        targetValue = count,
        animationSpec = tween(if (animOn) 420 else 0),
        label = "realmCount",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
        WbHSpace(WbD.gapXs)
        Text(
            "$n",
            style = MiuixTheme.textStyles.footnote2,
            color = color,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// ============================================================
// 账号列表
// ============================================================

private enum class AccFilter(val label: String) {
    All("全部"), Healthy("可用"), Cooling("冷却"), Disabled("禁用"), Paused("暂停")
}

@Composable
fun AccountsScreen(ov: Overview, vm: AppViewModel, onAdd: () -> Unit, onOpen: (AccountView) -> Unit) {
    var filter by remember { mutableStateOf(AccFilter.All) }
    val filtered = when (filter) {
        AccFilter.All -> ov.views
        // 「暂停选号」的账号不接新请求，因此不算「可用」也不该混进「冷却」——
        // 否则它与首页「可用」计数会出现口径不一致。
        AccFilter.Healthy -> ov.views.filter { !it.disabled && !it.paused && it.cool_remaining_sec <= 0 }
        AccFilter.Cooling -> ov.views.filter { !it.disabled && !it.paused && it.cool_remaining_sec > 0 }
        AccFilter.Disabled -> ov.views.filter { it.disabled }
        AccFilter.Paused -> ov.views.filter { it.paused }
    }
    // 表头做成**一块**液态玻璃面板（与模型页同一套语言，见 ModelsScreen）：上半是「添加
    // 账号」动作条、细分隔线、下半是筛选分段控件。与模型页的区别：这里没有搜索框，而是
    // 一个偏「动作」的添加入口（图标 + 文案 + 尾部 ＋），一眼能分清两页。
    //
    // 高度**实测**（onSizeChanged 挂在 padding 之外）：表头是浮动的兄弟节点、不占内容流，
    // 列表必须按它的实际高度留白，否则改表头就会「首张卡片贴到玻璃底下」。
    var headerPx by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val headerSpace = if (headerPx > 0) with(density) { headerPx.toDp() } else AccountsHeaderSpace
    WbGlassHeaderPage(
        header = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { headerPx = it.height }
                    .padding(horizontal = WbD.pageH)
                    .padding(top = WbD.pageTop, bottom = WbD.headerGap),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .wbGlass(
                            shape = remember { com.kyant.shapes.RoundedRectangle(WbD.radius, com.kyant.shapes.RoundedCornerStyle.Continuous) },
                            tint = WbGlass.neutralTint(),
                            lensHeight = 12.dp,
                            lensAmount = 12.dp,
                        )
                        .padding(WbD.gapS),
                ) {
                    // ① 添加账号：透明底动作条 —— 与玻璃面板共用一块底，不再自带按钮底色。
                    WbGlassActionRow(
                        "添加账号", "通过 OAuth 登录后自动入池", MiuixIcons.Add, onClick = onAdd,
                        trailing = { WbRealmCounts(ov.views) },
                    )
                    // ② 细分隔线：区分「动作」与「筛选」两栏（同一块玻璃里的分组）。
                    WbHairline(Modifier.padding(vertical = WbD.gapS))
                    // ③ 筛选：玻璃面板上的分段控件（plain=true，避免两层底色压叠）。
                    WbSegmented(
                        AccFilter.entries.map { it.label },
                        AccFilter.entries.indexOf(filter),
                        plain = true,
                        onSelect = { filter = AccFilter.entries[it] },
                    )
                }
            }
        },
    ) {
        // 始终用**同一个** LazyColumn（连空态也放进列表里）——与模型页一致，列表永远可上下
        // 拖动并回弹：内容不足一屏时用 fillParentMaxSize 占满视口，Miuix 的越界回弹半径取自
        // 窗口高度、与内容尺寸无关，所以空态同样能拖动。旧实现空态用裸 WbEmpty（非滚动容器），
        // 于是「没账号 / 筛选为空」时整页拖不动。
        LazyColumn(
            // overScrollVertical：把回弹显式挂到列表上，保证「拖得动」；同时关闭列表自身的
            // 越界效果（overscrollEffect = null）避免两层回弹叠加 —— 两者弹簧物理同源。
            modifier = Modifier.fillMaxSize().overScrollVertical(),
            overscrollEffect = null,
            contentPadding = PaddingValues(
                start = WbD.pageH, end = WbD.pageH,
                top = headerSpace, bottom = WbD.pageBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(WbD.gap),
        ) {
            if (ov.views.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.TopCenter) {
                        WbEmpty(
                            "暂无账号\n点击「添加账号」通过 OAuth 登录后自动入池",
                            modifier = Modifier.padding(top = WbD.gap),
                            icon = MiuixIcons.Add,
                        )
                    }
                }
            } else if (filtered.isEmpty()) {
                item(key = "empty-filter") {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.TopCenter) {
                        WbEmpty("当前筛选条件下没有账号", modifier = Modifier.padding(top = WbD.gap))
                    }
                }
            } else {
                items(filtered, key = { it.uid }) { a -> AccountCard(a) { onOpen(a) } }
            }
        }
    }
}

@Composable
private fun AccountCard(a: AccountView, onClick: () -> Unit) {
    val statusColor = when {
        a.disabled -> WbPalette.danger
        a.paused -> WbPalette.info
        a.cool_remaining_sec > 0 -> WbPalette.warn
        else -> WbPalette.ok
    }
    val statusText = when {
        a.disabled -> "已禁用"
        a.paused -> "已暂停选号"
        a.cool_remaining_sec > 0 -> "冷却中"
        else -> "可用"
    }
    WbSection {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = WbD.tapMin).padding(horizontal = WbD.cardPad, vertical = WbD.gapS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WbIconTile(MiuixIcons.BankCards, statusColor)
                WbHSpace(WbD.gap)
                Column(Modifier.weight(1f)) {
                    // 列表页默认脱敏（隐私）：昵称与 UID 打码；点进「账号详情」仍显示原文。
                    Text(
                        if (a.nickname.isBlank()) Mask.uid(a.uid) else Mask.name(a.nickname),
                        style = MiuixTheme.textStyles.subtitle,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        "UID ${Mask.uid(a.uid)}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(WbD.gapXs)) {
                    WbPill(statusText, statusColor)
                    WbPill(if (a.realm == "global") "GLOBAL" else "CN", if (a.realm == "global") WbPalette.info else MiuixTheme.colorScheme.primary)
                    // 企业版没有个人成长体系（签到等一律被上游拒绝）：标出来，避免被误当成异常。
                    if (a.enterprise) WbPill("企业版", WbPalette.warn)
                }
            }
            WbRow(
                "积分 ${wbCreditsText(a.credits)}",
                "成功 ${a.success_count} · 失败 ${a.err_total}",
                showDivider = true,
            )
            WbRow(
                "详情 / 操作",
                when {
                    a.cool_remaining_sec > 0 -> "冷却剩余 ${Durations.format(a.cool_remaining_sec)}${if (a.cool_kind.isNotBlank()) " (${a.cool_kind})" else ""}"
                    a.in_flight > 0 -> "在途 ${a.in_flight}"
                    else -> "近期速率 ${rateText(a)}"
                },
                chevron = true,
                onClick = onClick,
            )
        }
    }
}

private fun rateText(a: AccountView): String =
    if (a.token_usage.request_count == 0L || a.token_usage.last_tokens_per_second <= 0.0) "—"
    else "%.1f tok/s".format(a.token_usage.last_tokens_per_second)

/** 缓存命中率 = 命中上游前缀缓存的输入 token / 累计输入 token；无数据返回 -1。 */
private fun cacheHitRatio(a: AccountView): Double {
    val p = a.token_usage.prompt_tokens
    return if (p <= 0) -1.0 else a.token_usage.cached_tokens.toDouble() / p.toDouble()
}

private fun cacheHitText(a: AccountView): String {
    val r = cacheHitRatio(a)
    if (r < 0) return "—"
    val pct = r * 100
    val shown = if (pct >= 10 || pct == pct.toInt().toDouble()) "${pct.toInt()}%" else "%.1f%%".format(pct)
    return "$shown（${a.token_usage.cached_tokens} / ${a.token_usage.prompt_tokens}）"
}

// ============================================================
// 账号详情
// ============================================================

@Composable
fun AccountDetailScreen(v: AccountView?, vm: AppViewModel, onRemoved: () -> Unit) {
    if (v == null) {
        WbEmpty("账号不存在或已被移除")
        return
    }
    val statusColor = when {
        v.disabled -> WbPalette.danger
        v.paused -> WbPalette.info
        v.cool_remaining_sec > 0 -> WbPalette.warn
        else -> WbPalette.ok
    }
    val checkedIn = v.last_checkin_at >= todayStartSec()
    // 「复制登录凭证」用的剪贴板（与 WbCopyRow 同款：新剪贴板 API 为 suspend，走组合作用域）。
    val clipboard = LocalClipboard.current
    val copyScope = rememberCoroutineScope()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = pageTv,
        verticalArrangement = Arrangement.spacedBy(WbD.gap),
    ) {
        item {
            WbSection {
                Column(Modifier.padding(WbD.cardPad), verticalArrangement = Arrangement.spacedBy(WbD.gapS)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WbIconTile(MiuixIcons.BankCards, statusColor)
                        WbHSpace(WbD.gap)
                        Column(Modifier.weight(1f)) {
                            Text(
                                v.nickname.ifBlank { v.uid.take(12) },
                                style = MiuixTheme.textStyles.title4,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(v.uid, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(WbD.gapS)) {
                        WbPill(
                            when {
                                v.disabled -> "已禁用"
                                v.paused -> "已暂停选号"
                                v.cool_remaining_sec > 0 -> "冷却中"
                                else -> "正常"
                            },
                            statusColor,
                        )
                        WbPill(if (v.realm == "global") "GLOBAL" else "CN", if (v.realm == "global") WbPalette.info else MiuixTheme.colorScheme.primary)
                        if (v.enterprise) WbPill("企业版", WbPalette.warn)
                        // 企业版没有成长体系、签到会被上游 400 拒绝：不能显示「今日未签到」，
                        // 那会把「上游不支持」误报成「今天还没签」。
                        if (v.enterprise) {
                            WbPill("企业版无签到", MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        } else {
                            WbPill(if (checkedIn) "今日已签到" else "今日未签到", if (checkedIn) WbPalette.ok else WbPalette.warn)
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(WbD.gap)) {
                WbStatTile(
                    "剩余积分",
                    if (v.credits >= Up.ENTERPRISE_UNLIMITED_REMAIN) 0 else v.credits.toInt(),
                    Modifier.weight(1f),
                    accent = WbPalette.info,
                    icon = MiuixIcons.BankCards,
                    text = if (v.credits >= Up.ENTERPRISE_UNLIMITED_REMAIN) "不限" else null,
                )
                WbStatTile("累计请求", v.token_usage.request_count.toInt(), Modifier.weight(1f), icon = MiuixIcons.Send)
            }
        }
        item {
            WbSection("额度与用量") {
                WbRow("剩余积分", wbCreditsText(v.credits), showDivider = true)
                WbRow("总额度", wbCreditsText(v.credits_total), showDivider = true)
                WbRow("成功 / 失败", "${v.success_count} / ${v.err_total}", showDivider = true)
                WbRow("最近耗时", if (v.token_usage.last_latency_ms > 0) "${v.token_usage.last_latency_ms} ms" else "—", showDivider = true)
                WbRow("近期速率", rateText(v), showDivider = true)
                WbRow("累计 tokens", if (v.token_usage.total_tokens > 0) "${v.token_usage.total_tokens}" else "—", showDivider = true)
                WbRow(
                    "缓存命中率",
                    cacheHitText(v),
                    valueColor = if (cacheHitRatio(v) >= 0.5) WbPalette.ok else null,
                )
            }
        }
        item {
            WbSection("运行状态") {
                WbRow(
                    "启用状态",
                    when {
                        v.disabled -> "已禁用"
                        v.paused -> "已暂停选号（不参与选号，状态与冷却保留）"
                        else -> "正常"
                    },
                    valueColor = statusColor,
                    showDivider = true,
                )
                if (v.enterprise) {
                    WbRow(
                        "账号类型",
                        "企业版（无个人成长体系：签到等任务自动跳过，额度按企业口径查询）",
                        showDivider = true,
                    )
                }
                WbRow("冷却剩余", if (v.cool_remaining_sec > 0) Durations.format(v.cool_remaining_sec) else "无", showDivider = true)
                WbRow("冷却类型", v.cool_kind.ifBlank { "—" }, showDivider = true)
                WbRow("熔断失败次数", "${v.breaker_fail_streak}", showDivider = true)
                WbRow(
                    "令牌到期",
                    if (v.access_expires_at > 0) Durations.format(v.access_expires_at - System.currentTimeMillis() / 1000) else "—",
                    showDivider = true,
                )
                WbRow("在途请求", "${v.in_flight}", showDivider = v.rate_limited_models.isNotEmpty())
                if (v.rate_limited_models.isNotEmpty()) {
                    Column(Modifier.padding(horizontal = WbD.rowPadH, vertical = WbD.gapS), verticalArrangement = Arrangement.spacedBy(WbD.gapXs)) {
                        Text("受限模型", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        v.rate_limited_models.forEach { m ->
                            val left = (m.until - System.currentTimeMillis() / 1000).coerceAtLeast(0)
                            WbTag("${m.model} · ${m.kind} · ${Durations.format(left)}", if (m.kind == "model_unavailable") WbPalette.danger else WbPalette.warn)
                        }
                    }
                }
            }
        }
        item {
            WbSection("操作") {
                Column(Modifier.padding(WbD.cardPad), verticalArrangement = Arrangement.spacedBy(WbD.gapS)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(WbD.gapS)) {
                        // 企业版签到会被上游以 400 code 10001 拒绝（无个人成长体系），不提供该入口。
                        if (!v.enterprise) {
                            WbButton("签到", onClick = { vm.checkin(v.uid) }, modifier = Modifier.weight(1f), variant = WbButtonVariant.Tonal, icon = MiuixIcons.Alarm)
                        }
                        WbButton("刷新余额", onClick = { vm.balance(v.uid) }, modifier = Modifier.weight(1f), variant = WbButtonVariant.Tonal, icon = MiuixIcons.BankCards)
                    }
                    if (v.disabled) {
                        WbButton("启用账号", onClick = { vm.enable(v.uid) }, modifier = Modifier.fillMaxWidth(), variant = WbButtonVariant.Tonal)
                    } else {
                        WbButton("禁用账号", onClick = { vm.disable(v.uid) }, modifier = Modifier.fillMaxWidth(), variant = WbButtonVariant.Tonal)
                    }
                    // 暂停选号：不判死、不清冷却，只是暂时不接新请求（对齐上游 v1.12.0），
                    // 与「禁用」互斥（禁用后是终态，不再提供暂停入口）。
                    if (!v.disabled) {
                        if (v.paused) {
                            WbButton(
                                "恢复选号",
                                onClick = { vm.resume(v.uid) },
                                modifier = Modifier.fillMaxWidth(),
                                variant = WbButtonVariant.Tonal,
                                icon = MiuixIcons.Play,
                            )
                        } else {
                            WbButton(
                                "暂停选号",
                                onClick = { vm.pause(v.uid) },
                                modifier = Modifier.fillMaxWidth(),
                                variant = WbButtonVariant.Tonal,
                                icon = MiuixIcons.Pause,
                            )
                        }
                    }
                    WbButton("解除冷却 / 熔断", onClick = { vm.revive(v.uid) }, modifier = Modifier.fillMaxWidth(), variant = WbButtonVariant.Tonal, icon = MiuixIcons.Refresh)
                    WbTextButton("网络自检（结果写入日志）", onClick = { vm.diagnose(v.uid) }, modifier = Modifier.fillMaxWidth())
                    // 登录凭证（复制）：JSON 与上游 cockpitAccount 同名字段，可再粘回「添加账号 → 导入登录凭证」。
                    WbHintBlock(
                        title = "登录凭证属敏感信息",
                        body = "复制出的 JSON 含 access_token / refresh_token，等同于账号本人，请勿外传。",
                        icon = MiuixIcons.Lock,
                    )
                    WbTextButton(
                        "复制登录凭证",
                        onClick = {
                            val json = vm.credentialJson(v.uid)
                            if (json == null) {
                                vm.toast("账号不存在，无法复制凭证")
                            } else {
                                copyScope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("wb2api-credential", json)))
                                }
                                vm.toast("已复制登录凭证到剪贴板，请勿外传")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    WbTextButton("移除该账号", onClick = { vm.remove(v.uid); onRemoved() }, modifier = Modifier.fillMaxWidth(), danger = true)
                }
            }
        }
    }
}

private fun todayStartSec(): Long {
    val c = java.util.Calendar.getInstance()
    c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
    c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
    return c.timeInMillis / 1000
}

// ============================================================
// 模型
// ============================================================

@Composable
fun ModelsScreen(
    models: List<ModelInfo>,
    loading: Boolean,
    vm: AppViewModel,
    ui: UiCfg,
    onUiChange: (UiCfg) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // 排序只保留两项：「默认」（上游原序）与「上下文」（大窗口优先）。
    val sortIndex = when (ui.model_sort) {
        "context" -> 1
        else -> 0
    }
    // 排序键做**次级比较**，否则同值元素看起来像是「没排序」。
    val processed = remember(models, query, ui.model_sort) {
        val q = query.trim().lowercase()
        val filtered = if (q.isBlank()) models else models.filter {
            it.id.lowercase().contains(q) || it.name.lowercase().contains(q) || it.vendor.lowercase().contains(q)
        }
        when (ui.model_sort) {
            // 上下文：大窗口优先；同值再按最大输出、再按默认模型（稳定排序保持上游原序）。
            "context" -> filtered.sortedWith(
                compareByDescending<ModelInfo> { it.context_length }
                    .thenByDescending { it.max_output_tokens }
                    .thenByDescending { it.is_default },
            )
            // 默认 = 上游原序（默认模型置顶）；稳定排序保证同值元素维持上游下发顺序。
            else -> filtered.sortedWith(compareByDescending<ModelInfo> { it.is_default })
        }
    }

    // 刷新过场：点「刷新模型」后，让列表整体播一次「淡入 + 轻微上移」。
    //
    // 为什么需要：即使上游目录没有任何变化，`_models` 也会被赋成**内容相同的新列表**，
    // 界面上完全看不出变化 —— 用户会以为「刷新按钮没反应」。给一次可感知的过场，
    // 是对「我点了、它做了事」的明确回应；同时不用假造数据、不改变任何真实状态。
    val pulse = vm.modelsPulse.collectAsStateWithLifecycle().value
    val listAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
    val listShift = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(pulse) {
        if (pulse == 0) return@LaunchedEffect
        listAlpha.snapTo(0.30f)
        listShift.snapTo(12f)
        // 两条动画并发：alpha 稍快于位移，收尾时更自然（避免「一边到位一边还在动」）。
        launch { listAlpha.animateTo(1f, tween(340, easing = FastOutSlowInEasing)) }
        listShift.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
    }
    val listFx = Modifier.graphicsLayer {
        alpha = listAlpha.value
        // 注意：`GraphicsLayerScope` 自带 `density: Float`（dp→px 比例），且**作用域内会遮蔽**
        // 外层的 `LocalDensity` 对象 —— 所以这里直接乘 `density`，写成 `density.density` 会
        // 报「Unresolved reference 'density' on receiver of type 'Float'」。
        translationY = listShift.value * density
    }

    // 表头（搜索 + 布局 + 排序）**合并成一块玻璃面板**。
    //
    // 为什么合并：三块独立玻璃各有自己的边框、彼此留缝，视觉上被切成三段 ——
    // 这正是「内容割裂」的来源。合并后是**一块连续的玻璃**（列表从它下方滚过并被折射），
    // 内部只用细分隔线区分「搜索 | 筛选」两栏、以及两组筛选之间的竖线 ——
    // 既是同一块玻璃，又分得清组。
    //
    // 表头高度**实测**：表头是浮动的兄弟节点，不占内容流，列表必须按它的高度留白；
    // 实测值让「改表头」与「列表留白」自动保持一致（首帧尚未量到时回退到估值常量）。
    var headerPx by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val headerSpace = if (headerPx > 0) with(density) { headerPx.toDp() } else ModelsHeaderSpace
    WbGlassHeaderPage(
        header = {
            Column(
                Modifier
                    .fillMaxWidth()
                    // ⚠️ onSizeChanged 必须挂在**所有 padding 之外**（修饰符链最前）。
                    //    Compose 的修饰符是「外层先量、内层后量」：写在 `padding(...)` **之后**的
                    //    onSizeChanged 量到的是被 padding 收缩后的**内容**尺寸 —— 会把 pageTop(72dp)
                    //    整段漏掉，于是列表留白比表头实际占用少 72dp，首行卡片被压在玻璃表头底下
                    //    （1.4.3 的实际表现：「模型出现在液态玻璃底部…被挡住」）。
                    .onSizeChanged { headerPx = it.height }
                    .padding(horizontal = WbD.pageH)
                    .padding(top = WbD.pageTop, bottom = WbD.headerGap),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .wbGlass(
                            shape = remember { com.kyant.shapes.RoundedRectangle(WbD.radius, com.kyant.shapes.RoundedCornerStyle.Continuous) },
                            tint = WbGlass.neutralTint(),
                            lensHeight = 12.dp,
                            lensAmount = 12.dp,
                        )
                        .padding(WbD.gapS),
                ) {
                    // ① 搜索：透明底 → 与面板共用同一块玻璃（「刷新」已移除：悬浮顶栏右上角已有）。
                    WbSearchField(query, { query = it }, "搜索模型 / 供应商", transparent = true)
                    // ② 细分隔线：区分「搜索」与「筛选」两栏（同一块玻璃里的分组）。
                    WbHairline(Modifier.padding(vertical = WbD.gapS))
                    // ③ 两组筛选：布局 | 排序 —— 中间一条竖细线，仍是同一块玻璃里的两组。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            WbSegmented(listOf("单列", "双列"), (ui.model_columns.coerceIn(1, 2)) - 1, plain = true, onSelect = { onUiChange(ui.copy(model_columns = it + 1)) })
                        }
                        WbHairline(vertical = true, modifier = Modifier.padding(horizontal = WbD.gapS))
                        // 排序只保留「默认」（上游原序）与「上下文」（大窗口优先）两项。
                        Box(Modifier.weight(1f)) {
                            WbSegmented(listOf("默认", "上下文"), sortIndex, plain = true, onSelect = {
                                onUiChange(ui.copy(model_sort = if (it == 1) "context" else "default"))
                            })
                        }
                    }
                    // ④ 加载进度已移到**悬浮顶栏的刷新按钮**（图标就地旋转，见 WbRefreshAction）：
                    //    原来这里是紧贴分段控件下沿的一条 4dp 进度槽，既挤压版式又与相邻组件视觉重叠。
                }
            }
        },
    ) {
        when {
            models.isEmpty() && !loading -> WbEmpty(
                "暂无模型\n请先添加账号，再点右上角刷新",
                modifier = Modifier.padding(top = headerSpace),
                icon = MiuixIcons.Layers,
            )
            processed.isEmpty() -> WbEmpty("没有匹配「$query」的模型", modifier = Modifier.padding(top = headerSpace))
            ui.model_columns >= 2 -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = WbD.pageH, end = WbD.pageH,
                    top = headerSpace, bottom = WbD.pageBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(WbD.gap),
                horizontalArrangement = Arrangement.spacedBy(WbD.gap),
            ) {
                gridItems(processed, key = { it.id }) { m -> ModelCard(m, compact = true) }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = WbD.pageH, end = WbD.pageH,
                    top = headerSpace, bottom = WbD.pageBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(WbD.gap),
            ) {
                items(processed, key = { it.id }) { m -> ModelCard(m, compact = false) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(m: ModelInfo, compact: Boolean) {
    val realmColor = if (m.realm == "global") WbPalette.info else MiuixTheme.colorScheme.primary
    WbSection {
        Column(Modifier.padding(if (compact) WbD.gap else WbD.cardPad), verticalArrangement = Arrangement.spacedBy(WbD.gapS)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.name.ifBlank { m.model.ifBlank { m.id } },
                    style = if (compact) MiuixTheme.textStyles.body2 else MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (compact) 2 else 1,
                    modifier = Modifier.weight(1f),
                )
                if (!compact) {
                    WbHSpace(WbD.gapS)
                    WbPill(if (m.realm == "global") "GLOBAL" else "CN", realmColor)
                }
            }
            if (compact) {
                WbTag(if (m.realm == "global") "GLOBAL" else "CN", realmColor)
            } else if (m.description.isNotBlank()) {
                Text(
                    m.description,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                )
            }
            // 参数与能力统一走 FlowRow + WbTag：气泡等高、自动换行；单列 / 双列排版一致，不会错位。
            // 上下文 / 输出 / 积分 / 多模态 / 工具 / 思考档位都来自上游字段，缺失即不显示。
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(WbD.gapXs),
                verticalArrangement = Arrangement.spacedBy(WbD.gapXs),
            ) {
                if (m.credits.isNotBlank()) WbTag("${m.credits} 积分", WbPalette.warn)
                if (m.context_length > 0) {
                    WbTag("上下文 ${fmtK(m.context_length)}", MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                WbTag(
                    if (m.max_output_tokens > 0) "输出 ${fmtK(m.max_output_tokens)}" else "输出未知",
                    MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (m.supports_images) WbTag("多模态", WbPalette.info)
                if (m.supports_tool_call) WbTag("工具", realmColor)
                m.supported_efforts.take(if (compact) 4 else 6).forEach { e -> WbTag(e, realmColor) }
            }
        }
    }
}

/**
 * token 数量的人类可读格式：1000 → `1K`，131072 → `131K`，1000000 → `1M`。
 *
 * 上游 panel 用 `Math.round(v/1000)+'K'`（1M 会显示成 `1000K`）；这里 ≥1M 换用 M 更易读，
 * 并且 4 舍 5 入到千位（131072 → 131K 而不是 131.072K）。
 */
private fun fmtK(v: Long): String = when {
    v <= 0 -> "—"
    v >= 1_000_000 -> {
        val m = v / 1_000_000.0
        if (m >= 10 || m == m.toLong().toDouble()) "${m.toLong()}M" else "%.1fM".format(m)
    }
    v >= 1000 -> "${(v + 500) / 1000}K"
    else -> "$v"
}

// ============================================================
// 日志
// ============================================================

@Composable
fun LogsScreen(vm: AppViewModel) {
    // 日志的收集**下移到本页**：原来挂在 AppRoot 顶层，于是「任意一条新日志」都会让整个
    // AppRoot（同时读着 overview/config/models/message/...）参与重组；而真正用日志的只有本页。
    // 注意收集用 collectAsStateWithLifecycle：离开日志页即停止订阅（后台不再刷新）。
    val logs by vm.logsFlow().collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("全部", "对话", "任务", "系统")
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // 过滤结果缓存：原来每次重组都要重新 filter（外加 asReversed 再分配一个列表），
    // 日志持续追加时就是持续的重复分配 + 重复计算。现在按 (logs, tab) 记忆。
    val filtered = remember(logs, tab) {
        val f = when (tab) {
            1 -> logs.filter { it.ch == LogChannel.CHAT }
            2 -> logs.filter { it.ch == LogChannel.TASK }
            3 -> logs.filter { it.ch == LogChannel.SYS }
            else -> logs
        }
        // 反转一并缓存：原实现在渲染期 `filtered.asReversed()` 每帧新建一个反转视图。
        f.asReversed()
    }

    // 表头层铺满整页：顶上「全部/对话/任务/系统」玻璃分段控件、底下「清空/复制」玻璃按钮，
    // 中间留空让日志列表照常滚动与点击（表头层本身不拦截未命中控件的触摸）。
    WbGlassHeaderPage(
        header = {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = WbD.pageH)
                        .padding(top = WbD.pageTop, bottom = WbD.headerGap),
                ) {
                    WbSegmented(tabs, tab, onSelect = { tab = it })
                }
                Spacer(Modifier.weight(1f))
                Row(
                    // 底部留白避开悬浮玻璃底栏，否则「清空日志 / 复制全部」会被压在玻璃栏底下。
                    Modifier.fillMaxWidth().padding(horizontal = WbD.pageH).padding(bottom = WbD.pageBottom),
                    horizontalArrangement = Arrangement.spacedBy(WbD.gapS),
                ) {
                    WbButton("清空日志", onClick = { LogBus.clear(); vm.toast("日志已清空") }, modifier = Modifier.weight(1f), variant = WbButtonVariant.Tonal, glass = true)
                    WbButton("复制全部", onClick = {
                        val all = LogBus.snapshot().joinToString("\n") { "${LogBus.timeLabel(it.ts)} ${it.text}" }
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("wb2api 日志", all)))
                        }
                        vm.toast("已复制 ${LogBus.snapshot().size} 条日志")
                    }, modifier = Modifier.weight(1f), variant = WbButtonVariant.Tonal, icon = MiuixIcons.Copy, glass = true)
                }
            }
        },
    ) {
        if (filtered.isEmpty()) {
            WbEmpty("暂无日志", modifier = Modifier.padding(top = LogsHeaderSpace))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = WbD.pageH, end = WbD.pageH,
                    top = LogsHeaderSpace, bottom = LogsFooterSpace,
                ),
                verticalArrangement = Arrangement.spacedBy(WbD.gapXs),
            ) {
                // key 用 LogBus 分配的单调序号（唯一）；时间文本直接用写入时定稿的 tsLabel，
                // 渲染期不再做日期格式化（原实现逐行调用 SimpleDateFormat.format）。
                items(filtered, key = { it.seq }) { e ->
                    val chColor = when (e.ch) {
                        LogChannel.CHAT -> MiuixTheme.colorScheme.primary
                        LogChannel.TASK -> WbPalette.info
                        LogChannel.SYS -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            e.tsLabel,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(width = 56.dp, height = 18.dp),
                        )
                        Box(Modifier.padding(top = 6.dp).size(6.dp).clip(RoundedCornerShape(50)).background(chColor))
                        WbHSpace(WbD.gapS)
                        Text(e.text, style = MiuixTheme.textStyles.footnote1, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ============================================================
// 设置
// ============================================================

@Composable
fun SettingsScreen(cfg: GatewayConfig, vm: AppViewModel, onAbout: () -> Unit) {
    // 草稿不随 cfg 变化重置：外观类偏好会即时落盘（cfg 随之变化），若用 remember(cfg)
    // 会把尚未保存的其它改动一起冲掉。仅在保存/放弃/恢复默认时显式对齐。
    var draft by remember { mutableStateOf(cfg) }
    val dirty = draft != cfg
    var showJson by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    fun applyUi(ui: UiCfg) {
        vm.updateUi(ui)
        draft = draft.copy(ui = ui)
    }

    val themeIndex = when (draft.ui.theme.lowercase()) {
        "light" -> 1
        "dark" -> 2
        else -> 0
    }

    // 单一根节点（列表 + 浮动保存栏）。
    // 一个页面只能有一个根级子节点：之前把「保存栏」作为第二个根节点发出，在 Pager 的
    // 页面槽里它不一定被放置 —— 表现为改完设置后「保存更改 / 放弃」整条看不见。
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = pageTv,
            verticalArrangement = Arrangement.spacedBy(WbD.gap),
        ) {
            // ---------- 外观 ----------
            item {
                WbGroup(
                    title = "外观",
                    subtitle = if (draft.ui == UiCfg()) "即时生效，无需保存" else "已改动 · 即时生效，无需保存",
                    icon = MiuixIcons.Theme,
                    modified = draft.ui != UiCfg(),
                    onReset = { applyUi(UiCfg()) },
                ) {
                    Column(
                        Modifier.padding(horizontal = WbD.rowPadH, vertical = WbD.rowPadV),
                        verticalArrangement = Arrangement.spacedBy(WbD.gapS),
                    ) {
                        Text("主题", style = MiuixTheme.textStyles.body1)
                        WbSegmented(listOf("自动", "浅色", "深色"), themeIndex, onSelect = { i ->
                            applyUi(draft.ui.copy(theme = when (i) { 1 -> "light"; 2 -> "dark"; else -> "system" }))
                        })
                    }
                    WbSwitchRow(
                        title = "动态取色（Material You）",
                        summary = "跟随壁纸取色；默认关闭——关闭时语义色（可用/冷却/禁用）更稳定可辨",
                        checked = draft.ui.monet,
                        onCheckedChange = { applyUi(draft.ui.copy(monet = it)) },
                    )
                    WbSwitchRow(
                        title = "触感反馈",
                        summary = "点击按钮 / 切换开关时轻微震动",
                        checked = draft.ui.haptics,
                        onCheckedChange = { applyUi(draft.ui.copy(haptics = it)) },
                    )
                    WbSwitchRow(
                        title = "界面动画",
                        summary = "页面过渡、数值滚动与按压回弹",
                        checked = draft.ui.animations,
                        onCheckedChange = { applyUi(draft.ui.copy(animations = it)) },
                    )
                    // LocalContext.current 是 @Composable 属性，必须在组合作用域里取一次，
                    // 不能在 onCheckedChange 的普通 lambda 里调用（会抛
                    // "CompositionLocal ... not present"）。
                    val overlayCtx = androidx.compose.ui.platform.LocalContext.current
                    WbSwitchRow(
                        title = "网关地址浮窗",
                        summary = "启动网关后在其它应用上层显示 127.0.0.1:端口（需「显示在其他应用上层」权限）",
                        checked = draft.ui.overlay,
                        onCheckedChange = { on ->
                            applyUi(draft.ui.copy(overlay = on))
                            if (!on) {
                                // 浮窗是系统级窗口，不主动摘掉就会一直盖在别的应用上。
                                com.wb.api.overlay.AddressOverlay.hide()
                            } else if (com.wb.api.Gateway.serverRunning() &&
                                com.wb.api.overlay.AddressOverlay.canDraw(overlayCtx)
                            ) {
                                com.wb.api.overlay.AddressOverlay.show(
                                    overlayCtx,
                                    "127.0.0.1:${com.wb.api.Gateway.displayPort()}",
                                )
                            }
                            if (on && !com.wb.api.overlay.AddressOverlay.canDraw(overlayCtx)) {
                                android.widget.Toast.makeText(
                                    overlayCtx,
                                    "请开启「显示在其他应用上层」权限，返回后浮窗会自动出现",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                                com.wb.api.overlay.AddressOverlay.requestPermission(overlayCtx)
                            }
                        },
                    )
                }
            }

            // ---------- 服务 ----------
            item {
                WbGroup(
                    title = "服务",
                    subtitle = "监听地址与访问密钥（保存后生效）",
                    icon = MiuixIcons.Link,
                    modified = draft.listen != ":7863" || draft.api_key.isNotBlank() || !draft.upstream.low_latency,
                    onReset = { draft = draft.copy(listen = ":7863", api_key = "", upstream = draft.upstream.copy(low_latency = true)) },
                ) {
                    Column(
                        Modifier.padding(horizontal = WbD.rowPadH, vertical = WbD.rowPadV),
                        verticalArrangement = Arrangement.spacedBy(WbD.gapS),
                    ) {
                        TextField(
                            value = draft.listen,
                            onValueChange = { draft = draft.copy(listen = it) },
                            label = "监听地址（如 :7863）",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextField(
                            value = draft.api_key,
                            onValueChange = { draft = draft.copy(api_key = it) },
                            label = "访问密钥（留空 = 局域网无鉴权）",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(WbD.gapS)) {
                            WbButton(
                                "随机生成",
                                onClick = { draft = draft.copy(api_key = com.wb.api.core.Ids.apiKey()) },
                                modifier = Modifier.weight(1f),
                                variant = WbButtonVariant.Tonal,
                                icon = MiuixIcons.Refresh,
                            )
                            WbButton(
                                "清空",
                                onClick = { draft = draft.copy(api_key = "") },
                                modifier = Modifier.weight(1f),
                                variant = WbButtonVariant.Tonal,
                                icon = MiuixIcons.Delete,
                            )
                        }
                    }
                    WbSwitchRow(
                        title = "低延迟模式",
                        summary = "复用连接、放宽空闲回收、SSE 关闭压缩，降低首字延迟",
                        checked = draft.upstream.low_latency,
                        onCheckedChange = { draft = draft.copy(upstream = draft.upstream.copy(low_latency = it)) },
                    )
                    // 后台保活：唯一的官方合规途径是让系统把本应用排除在 Doze/电池优化之外。
                    // 点击后直接请求系统弹出「允许忽略电池优化」确认框（= 设为「无限制」），
                    // 而不是丢用户到电池优化列表里自己找。这里只做「检测 + 跳转引导」，
                    // 不做任何被平台证伪的保活黑科技。
                    WbRow(
                        label = "后台保活设置",
                        value = "省电策略 · 无限制",
                        leading = { WbIconTile(MiuixIcons.Info, WbPalette.info, size = 26.dp) },
                        chevron = true,
                        onClick = { vm.openBatterySettings() },
                    )
                }
            }

            // ---------- 并发与容错 ----------
            item {
                WbGroup(
                    title = "并发与容错",
                    subtitle = "并发上限与熔断/降级阈值（滑动调整）",
                    icon = MiuixIcons.Tune,
                    modified = draft.pool != PoolCfg(),
                    onReset = { draft = draft.copy(pool = PoolCfg()) },
                ) {
                    WbSliderRow(
                        title = "单账号最大并发",
                        summary = "同一账号同时在跑的请求数上限",
                        value = draft.pool.max_in_flight,
                        range = 1..20,
                        onChange = { draft = draft.copy(pool = draft.pool.copy(max_in_flight = it)) },
                    )
                    WbSliderRow(
                        title = "global 账号并发",
                        summary = "global 池单独的并发上限",
                        value = draft.pool.max_in_flight_global,
                        range = 1..20,
                        onChange = { draft = draft.copy(pool = draft.pool.copy(max_in_flight_global = it)) },
                    )
                    WbSliderRow(
                        title = "熔断阈值",
                        summary = "连续失败达到该次数后账号进入熔断冷却",
                        value = draft.pool.breaker_threshold,
                        range = 1..10,
                        onChange = { draft = draft.copy(pool = draft.pool.copy(breaker_threshold = it)) },
                    )
                    WbSliderRow(
                        title = "降级阈值",
                        summary = "连续软限流达到该次数后账号降权",
                        value = draft.pool.degrade_threshold,
                        range = 1..20,
                        onChange = { draft = draft.copy(pool = draft.pool.copy(degrade_threshold = it)) },
                    )
                }
            }

            // ---------- 自动化与策略 ----------
            item {
                WbGroup(
                    title = "自动化与策略",
                    subtitle = "定时任务仅在网关运行且有账号时执行",
                    icon = MiuixIcons.Tasks,
                    modified = draft.schedule != ScheduleCfg() || draft.session_sticky != SessionStickyCfg(),
                    onReset = { draft = draft.copy(schedule = ScheduleCfg(), session_sticky = SessionStickyCfg()) },
                ) {
                    WbSwitchRow(
                        title = "会话粘性",
                        summary = "同一会话固定使用同一账号，提高上游缓存命中",
                        checked = draft.session_sticky.enabled,
                        onCheckedChange = { draft = draft.copy(session_sticky = draft.session_sticky.copy(enabled = it)) },
                    )
                    WbSwitchRow(
                        title = "定时签到",
                        summary = if (draft.schedule.checkin_mode == "missed")
                            "自动补签：发现有账号今日未签到就补签"
                        else "定点签到：在下方选定的小时自动签到",
                        checked = draft.schedule.checkin_enabled,
                        onCheckedChange = { draft = draft.copy(schedule = draft.schedule.copy(checkin_enabled = it)) },
                    )
                    if (draft.schedule.checkin_enabled) {
                        Column(
                            Modifier.padding(horizontal = WbD.rowPadH).padding(bottom = WbD.gapS),
                            verticalArrangement = Arrangement.spacedBy(WbD.gapS),
                        ) {
                            Text("触发方式", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            WbSegmented(
                                listOf("定点时间", "自动补签"),
                                if (draft.schedule.checkin_mode == "missed") 1 else 0,
                                onSelect = { draft = draft.copy(schedule = draft.schedule.copy(checkin_mode = if (it == 1) "missed" else "fixed")) },
                            )
                        }
                        if (draft.schedule.checkin_mode == "missed") {
                            WbSliderRow(
                                title = "巡检间隔",
                                summary = "每隔这么久检查一次未签到账号",
                                value = draft.schedule.checkin_scan_minutes,
                                range = 5..240,
                                step = 5,
                                unit = " 分钟",
                                onChange = { draft = draft.copy(schedule = draft.schedule.copy(checkin_scan_minutes = it)) },
                            )
                            WbSwitchRow(
                                title = "启动网关时立即巡检一次",
                                checked = draft.schedule.checkin_on_start,
                                onCheckedChange = { draft = draft.copy(schedule = draft.schedule.copy(checkin_on_start = it)) },
                            )
                        } else {
                            WbHourGrid(
                                hours = draft.schedule.checkin_hours.toSet(),
                                onToggle = { h ->
                                    val set = draft.schedule.checkin_hours.toMutableSet()
                                    if (!set.add(h)) set.remove(h)
                                    draft = draft.copy(schedule = draft.schedule.copy(checkin_hours = set.sorted()))
                                },
                            )
                        }
                    }
                    WbSwitchRow(
                        title = "余额自动刷新",
                        summary = "定时同步账号剩余积分",
                        checked = draft.schedule.balance_refresh_enabled,
                        onCheckedChange = { draft = draft.copy(schedule = draft.schedule.copy(balance_refresh_enabled = it)) },
                    )
                    if (draft.schedule.balance_refresh_enabled) {
                        WbSliderRow(
                            title = "刷新间隔",
                            summary = "定时同步账号剩余积分",
                            value = draft.schedule.balance_refresh_minutes,
                            range = 1..120,
                            step = 1,
                            unit = " 分钟",
                            onChange = { draft = draft.copy(schedule = draft.schedule.copy(balance_refresh_minutes = it)) },
                        )
                    }
                    Column(
                        Modifier.padding(WbD.gap),
                        verticalArrangement = Arrangement.spacedBy(WbD.gapS),
                    ) {
                        // 成长任务：上游接口**已接入**（对齐 workbuddy2api-panel 的 tasks.go）。
                        // 这里给出「扫描并领取」入口 + 实时进度 + 最近一次结果。
                        val growthReport by vm.growthReport.collectAsStateWithLifecycle()
                        val growthBusy by vm.growthBusy.collectAsStateWithLifecycle()
                        val growthProgress by vm.growthProgress.collectAsStateWithLifecycle()
                        WbHintBlock(
                            // 注意：正文里**不要**写 Markdown 的粗体记号（两个星号）——
                            // WbHintBlock 用的是纯 Text，星号会被原样显示出来。
                            title = "成长任务（已接入上游）",
                            body = "扫描全部账号的成长任务：自动报名（分片 + 回读验证）→ 经行为事件上报真实" +
                                "推进进度 → 异步回读 → 自动领奖（走 Web 域正确路径）。行为事件按天幂等，" +
                                "已达标 / 已领取自动跳过；跳过禁用与 global 账号。",
                            icon = MiuixIcons.Ok,
                        )
                        WbButton(
                            text = if (growthBusy) "正在扫描…" else "扫描并领取成长任务",
                            onClick = { vm.runGrowthTasks() },
                            modifier = Modifier.fillMaxWidth(),
                            variant = WbButtonVariant.Tonal,
                            icon = MiuixIcons.Refresh,
                            enabled = !growthBusy,
                            loading = growthBusy,
                        )
                        if (growthBusy) {
                            // 扫描可能跑几十秒到几分钟（多账号 × 分片报名 + 事件上报 + 回读 + 领奖）：
                            // 给出「第几个账号 / 共几个」的实时文案 + 图标旋转，替换「点了没反应」的干等。
                            //
                            // 刻意不用不确定进度条：Miuix 的进度组件在各版本里签名不稳，
                            // 而「逐账号文字 + 旋转图标」已足以说明「进行到哪、还剩几个」。
                            Text(
                                growthProgress.ifBlank { "正在准备…" },
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                        growthReport?.let { r ->
                            // 汇总摘要**单独成行**：它很长（含各账号合计），塞进 WbRow 的右值位会挤成好几行。
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(WbD.gapXs),
                            ) {
                                Text(
                                    "上次结果",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Text(
                                    r.summary(),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                            r.results.forEachIndexed { i, a ->
                                WbRow(
                                    a.nickname.ifBlank { a.uid.take(8) },
                                    if (a.error != null) "失败：${a.error}"
                                    else "任务 ${a.total} · 报名 ${a.accepted} · 领奖 ${a.claimed}" +
                                        (if (a.credit > 0) " · +${a.credit} 积分" else ""),
                                    showDivider = i != r.results.lastIndex,
                                )
                            }
                        }
                        WbHintBlock(
                            title = "仍未接入的上游任务",
                            body = "猫猫旅行 / token 保活 / 夜猫子：上游对应接口尚未接入，" +
                                "开关保留在配置中但不会执行（日志会注明「未接入」）。" +
                                "活跃上报与连登管家（兑换/抽奖/补签/礼包）已接入，跟随签到排程自动执行。",
                            icon = MiuixIcons.Info,
                        )
                    }
                }
            }

            // ---------- 请求归档 ----------
            item {
                WbGroup(
                    title = "请求归档",
                    subtitle = "把每次请求记为本地文件，可导出到「下载」或删除",
                    icon = MiuixIcons.FileDownloads,
                    modified = draft.logging != LoggingCfg(),
                    onReset = { draft = draft.copy(logging = LoggingCfg()) },
                ) {
                    Column(Modifier.padding(WbD.gap)) {
                        WbHintBlock(
                            title = "这个开关做什么",
                            body = "把每次请求的模型、耗时、token 用量写成本地文件（data/requests.jsonl），" +
                                "用于事后排查「哪个模型慢 / 哪次失败」。它与「日志」页不是一回事：日志页只保留最近 500 条。",
                            icon = MiuixIcons.Info,
                        )
                    }
                    WbSwitchRow(
                        title = "启用请求归档",
                        summary = if (draft.logging.request_archive_enabled) "正在记录；可随时关闭" else "关闭后不再写入归档文件",
                        checked = draft.logging.request_archive_enabled,
                        onCheckedChange = { draft = draft.copy(logging = draft.logging.copy(request_archive_enabled = it)) },
                    )
                    if (draft.logging.request_archive_enabled) {
                        WbSliderRow(
                            title = "保留天数",
                            summary = "超期自动清理",
                            value = draft.logging.request_retention_days,
                            range = 1..90,
                            unit = " 天",
                            onChange = { draft = draft.copy(logging = draft.logging.copy(request_retention_days = it)) },
                        )
                        WbSliderRow(
                            title = "容量上限",
                            summary = "超出后按最旧优先丢弃",
                            value = draft.logging.request_archive_max_mb,
                            range = 10..500,
                            step = 10,
                            unit = " MB",
                            onChange = { draft = draft.copy(logging = draft.logging.copy(request_archive_max_mb = it)) },
                        )
                        WbSwitchRow(
                            title = "记录客户端信息",
                            summary = "在归档里写入来源 IP（隐私相关，可关）",
                            checked = draft.logging.request_client_info,
                            onCheckedChange = { draft = draft.copy(logging = draft.logging.copy(request_client_info = it)) },
                        )
                    }
                    // 出站请求体骨架（默认关）：开启后在「日志」页的出站条目后追加一段**已脱敏**
                    // 的 JSON 骨架（键名与结构，长字符串折叠），供排查请求形态；不含消息正文。
                    WbSwitchRow(
                        title = "记录出站请求体骨架",
                        summary = "在日志里附上脱敏后的请求结构（排查用）；默认关",
                        checked = draft.logging.request_body_log,
                        onCheckedChange = { draft = draft.copy(logging = draft.logging.copy(request_body_log = it)) },
                    )
                    // 归档文件落在应用私有目录（data/requests.jsonl），文件管理器看不到：
                    // 必须给出「导出到下载」与「删除」两个出口，否则开了归档也等于无法查看。
                    WbArchiveActions(vm = vm)
                }
            }

            // ---------- 关于 ----------
            item {
                WbSection("关于") {
                    WbRow("版本", "${com.wb.api.BuildConfig.VERSION_NAME} (versionCode ${com.wb.api.BuildConfig.VERSION_CODE})", showDivider = true)
                    // 长文本用「标签在上、值在下」的两行布局，避免单行挤压重叠。
                    WbKeyValueRow(
                        "支持的接口",
                        "/v1/chat/completions · /v1/responses（工具调用 / 思考档位 / 缓存用量全量透传）\n" +
                            "/v1/models · /status · /healthz",
                        showDivider = true,
                        leading = { WbIconTile(MiuixIcons.Link, MiuixTheme.colorScheme.primary, size = 26.dp) },
                    )
                    WbRow("查看配置 JSON", "只读", chevron = true, onClick = { showJson = true }, showDivider = true)
                    WbRow("项目说明", "wb2api", chevron = true, onClick = onAbout, showDivider = true)
                    WbRow(
                        "恢复所有默认设置",
                        "",
                        valueColor = WbPalette.danger,
                        labelColor = WbPalette.danger,
                        onClick = { confirmReset = true },
                    )
                }
            }

            item { WbVSpace(72.dp) }
        }

        // 底部动作条：只有存在未保存改动时出现。
        Box(Modifier.align(Alignment.BottomCenter)) {
            WbSaveBar(
                dirty = dirty,
                onSave = { vm.saveConfig(draft) },
                onDiscard = { draft = cfg },
                // 底部留白必须避开悬浮玻璃底栏，否则「保存更改 / 放弃」会被压在玻璃栏底下。
                modifier = Modifier.padding(horizontal = WbD.pageH).padding(bottom = WbD.pageBottom),
            )
        }
    }

    if (showJson) {
        OverlayDialog(
            show = true,
            title = "配置 JSON",
            summary = "当前草稿（未保存的改动也在其中）",
            onDismissRequest = { showJson = false },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WbD.gapS)) {
                val json = runCatching { PrettyJson.encodeToString(GatewayConfig.serializer(), draft) }.getOrDefault("{}")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(WbD.radiusS))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .padding(WbD.gap)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(json, style = MiuixTheme.textStyles.footnote2)
                }
                WbButton("复制 JSON", onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("wb2api 配置", json))) }
                    vm.toast("已复制配置")
                }, modifier = Modifier.fillMaxWidth(), variant = WbButtonVariant.Tonal, icon = MiuixIcons.Copy)
                WbTextButton("关闭", onClick = { showJson = false }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (confirmReset) {
        OverlayDialog(
            show = true,
            title = "恢复所有默认设置",
            summary = "将重置监听地址、并发、定时策略与归档设置（访问密钥保留）。此操作不可撤销。",
            onDismissRequest = { confirmReset = false },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WbD.gapS)) {
                WbButton("确认恢复", onClick = {
                    val key = draft.api_key
                    vm.resetConfigDefaults()
                    draft = GatewayConfig(api_key = key)
                    confirmReset = false
                }, modifier = Modifier.fillMaxWidth(), variant = WbButtonVariant.Danger)
                WbTextButton("取消", onClick = { confirmReset = false }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

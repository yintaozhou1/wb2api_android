package com.wb.api.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import android.content.ClipData
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置分组：**默认折叠**，点击分组标题展开。
 *
 * 为什么要折叠：设置项有 6 组、四十几行，全部铺开时用户只能靠滚动找目标，组与组之间的
 * 边界也看不出来（用户反馈「布局一塌糊涂」）。折叠后首屏看到的是 6 个明确的组标题，
 * 点哪组看哪组。
 *
 * 「恢复本组默认」按钮的语义也在这里收紧：
 *  - **只在改组确实被改过时才出现**（[modified]），没动过就不该有「恢复」；
 *  - 位置放在**卡片底部、分隔线之下**——读完一组选项后才看到「撤销本组改动」，
 *    而不是悬在标题右侧当装饰。
 */
@Composable
fun WbGroup(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    modified: Boolean = false,
    onReset: (() -> Unit)? = null,
    resetLabel: String = "恢复本组默认",
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val haptics = LocalHapticFeedback.current
    val hapticOn = LocalWbHaptics.current
    val animOn = LocalWbAnimations.current

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(WbD.radiusS))
                .wbPress(haptic = HapticFeedbackType.SegmentTick) { expanded = !expanded }
                .padding(start = 4.dp, end = 4.dp, top = WbD.gapS, bottom = WbD.gapS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                WbIconTile(icon, MiuixTheme.colorScheme.primary, size = 26.dp)
                WbHSpace(WbD.gapS)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.subtitle, color = MiuixTheme.colorScheme.onSurface)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            WbHSpace(WbD.gapS)
            Icon(
                if (expanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (animOn) fadeIn(tween(160)) + expandVertically(tween(200)) else androidx.compose.animation.EnterTransition.None,
            exit = if (animOn) fadeOut(tween(120)) + shrinkVertically(tween(180)) else androidx.compose.animation.ExitTransition.None,
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = contentPadding,
            ) {
                content()
                if (modified && onReset != null) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = WbD.rowPadH),
                        color = MiuixTheme.colorScheme.dividerLine,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = WbD.rowPadH, vertical = WbD.gapS),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GroupResetButton(label = resetLabel) {
                            if (hapticOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onReset()
                        }
                    }
                }
            }
        }
    }
}

/** 组内「恢复本组默认」：带边框的胶囊按钮，只在改组被改过之后渲染。 */
@Composable
private fun GroupResetButton(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .border(BorderStroke(1.dp, MiuixTheme.colorScheme.dividerLine), RoundedCornerShape(50))
            .wbPress(onClick = onClick)
            .padding(horizontal = WbD.gap + 2.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            MiuixIcons.Reset,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(14.dp),
        )
        WbHSpace(WbD.gapXs)
        Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/**
 * 滑动调整行（并发 / 容错 / 刷新间隔等数值项）。
 *
 * 交互要点：
 *  - 滑块**连续**拖动，但对外回调的值按 [step] 吸附到整数格，配置里不会出现小数；
 *  - 右上角常显当前值（含单位），拖动时实时变化，不必松手才知道值；
 *  - 吸附后与当前值相同则不回调，避免一次拖动产生几十次无效写入。
 */
@Composable
fun WbSliderRow(
    title: String,
    value: Int,
    range: IntRange,
    modifier: Modifier = Modifier,
    summary: String? = null,
    step: Int = 1,
    unit: String = "",
    enabled: Boolean = true,
    onChange: (Int) -> Unit,
) {
    val safeStep = step.coerceAtLeast(1)
    Column(
        modifier.fillMaxWidth().padding(horizontal = WbD.rowPadH, vertical = WbD.rowPadV),
        verticalArrangement = Arrangement.spacedBy(WbD.gapXs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurface)
                if (summary != null) {
                    Text(
                        summary,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            WbHSpace(WbD.gapS)
            Text(
                "$value$unit",
                style = MiuixTheme.textStyles.body1,
                color = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontWeight = FontWeight.Medium,
            )
        }
        // 位置参数调用（value, onValueChange, modifier, enabled, valueRange），避免依赖参数名。
        Slider(
            value.toFloat(),
            { raw ->
                val snapped = ((raw / safeStep).roundToInt() * safeStep).coerceIn(range.first, range.last)
                if (snapped != value) onChange(snapped)
            },
            Modifier.fillMaxWidth(),
            enabled,
            range.first.toFloat()..range.last.toFloat(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${range.first}$unit", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text("${range.last}$unit", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

/**
 * 密钥行：**默认脱敏**（只露前后几位）+ 可见性切换 + 一键复制。
 *
 * 对齐要求（用户反馈）：复制图标必须和 Base URL 行的复制图标**落在同一条右边线上**。
 * 因此这里的行结构、内边距、图标尺寸（18dp）与右端顺序都和 [WbCopyRow] 完全一致，
 * 复制图标是最后一个元素（贴着 rowPadH 的右边距），只是它左侧多了一个眼睛按钮。
 */
@Composable
fun WbSecretRow(
    label: String,
    secret: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    showDivider: Boolean = false,
    onCopied: () -> Unit = {},
) {
    var revealed by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val hapticOn = LocalWbHaptics.current

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = WbD.tapMin)
                .padding(horizontal = WbD.rowPadH, vertical = WbD.rowPadV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                WbHSpace(WbD.gap)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WbD.gapXs)) {
                Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(
                    if (secret.isBlank()) "未设置（局域网内无鉴权）"
                    else if (revealed) secret else maskSecret(secret),
                    style = MiuixTheme.textStyles.body2,
                    color = if (secret.isBlank()) WbPalette.warn else MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            if (secret.isNotBlank()) {
                WbHSpace(WbD.gapS)
                // 眼睛：视觉 18dp（与复制图标同规格），可点区域 28dp
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (hapticOn) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            revealed = !revealed
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (revealed) MiuixIcons.Hide else MiuixIcons.Show,
                        contentDescription = if (revealed) "隐藏密钥" else "显示密钥",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                WbHSpace(WbD.gapXs)
                // 复制：右对齐的 18dp 图标（与 WbCopyRow 的复制图标严格对齐）
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (hapticOn) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("API Key", secret)))
                            }
                            onCopied()
                        },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Copy,
                        contentDescription = "复制密钥",
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(modifier = Modifier.padding(start = WbD.rowPadH), color = MiuixTheme.colorScheme.dividerLine)
        }
    }
}

/** 脱敏：保留前缀 4~6 位与末 4 位，中间固定 8 个圆点（不暴露真实长度）。 */
private fun maskSecret(s: String): String {
    if (s.length <= 10) return "•".repeat(8)
    val head = s.take(if (s.startsWith("wb2a-") || s.startsWith("sk-")) 6 else 4)
    return head + "••••••••" + s.takeLast(4)
}

/**
 * 健康总览的单个指标段：**数字在上、标签在下**，本身**不带底色** ——
 * 整条的底色由 [WbStatStrip] 统一提供（原来每格一块 10% 语义色底，四格并排很碎很花）。
 *
 * 数字变化时做一次轻微的上移淡入（`AnimatedContent`）：刷新余额 / 账号上下线时
 * 看得见「数字确实变了」，而不是硬切换；关掉动画偏好时退化为瞬时切换。
 */
@Composable
fun WbMiniStat(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val animOn = LocalWbAnimations.current
    val transition = if (animOn) {
        (slideInVertically { it / 2 } + fadeIn(tween(170))) togetherWith
            (slideOutVertically { -it / 2 } + fadeOut(tween(110)))
    } else {
        EnterTransition.None togetherWith ExitTransition.None
    }
    Column(
        modifier
            .then(if (onClick != null) Modifier.wbPress(onClick = onClick) else Modifier)
            .padding(vertical = WbD.gapS, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        AnimatedContent(targetState = value, transitionSpec = { transition }, label = "statValue") { v ->
            Text(v, style = MiuixTheme.textStyles.title3, color = accent, fontWeight = FontWeight.SemiBold)
        }
        Text(label, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
    }
}

/**
 * 指标段之间的**发丝竖线**（首页「账号概览」的 4 段分栏）。
 *
 * 1.4.6 曾把 4 段装进一条带容器底色的「指标条」（WbStatStrip）；1.4.7 起取消该底色
 * —— 页面里其它卡片都是「卡片底色 + 行 + 图标底座」，卡片内部再嵌一层异色块属于
 * 不同源的第三种视觉语言，读起来突兀（用户反馈「取色与其它卡片突兀」）。
 */
@Composable
fun WbStatDivider() {
    Box(Modifier.width(1.dp).height(22.dp).background(MiuixTheme.colorScheme.dividerLine))
}

/**
 * 快捷操作的「动作格」（首页「账号概览」卡内 2×2 排布）。
 *
 * 视觉语言刻意与「运行指标」卡一致：**图标底座（[WbIconTile]：主色 16% 圆角底 + 主色图标）
 * + 中性文字**。1.4.6 曾给整格铺一层容器底色（surfaceContainerHighest），在白色卡片上等于
 * 「卡里又嵌了一层异色块」，与页面其它卡片不同源 —— 这是用户反馈「取色突兀」的来源。
 * 现在取消整格底色，只保留图标底座；可点性与按压反馈仍由 [wbPress] 提供（缩放 + 触感）。
 */
@Composable
fun WbActionCell(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val cs = MiuixTheme.colorScheme
    val tint = if (enabled) cs.primary else cs.disabledOnSurface
    Row(
        (if (enabled) modifier else modifier.alpha(0.38f))
            .clip(RoundedCornerShape(WbD.radiusS))
            .wbPress(enabled = enabled, onClick = onClick)
            .padding(horizontal = WbD.gapS, vertical = WbD.gapS),
        verticalAlignment = Alignment.CenterVertically,
        // 「图标 + 文字」作为**一个整体**在格内居中：否则两列里
        // 「全量签到 / 刷新余额」字数不同，图标起始位置会一左一右错开，
        // 整体看起来就是「没对齐 / 视觉割裂」（用户反馈）。
        horizontalArrangement = Arrangement.Center,
    ) {
        WbIconTile(icon, tint, size = 30.dp, corner = 9.dp)
        WbHSpace(WbD.gapS + 2.dp)
        Text(label, style = MiuixTheme.textStyles.button, color = cs.onSurface, maxLines = 1)
    }
}

/**
 * 首页服务卡片（网关开关）。
 *
 * 用户反馈「启动后直接显示已启动、Loading 只闪一帧」——问题不在状态本身，
 * 而在**界面把状态变化做成了硬切换**：颜色、文案、按钮样式都是 if/else 直接换值，
 * 一帧内完成，观感就是「跳变」。这里统一改成过渡：
 *  - 主色/底色用 `animateColorAsState` 渐变（运行=成功色，停止=中性色，380ms）；
 *  - 图标、标题、副标题、按钮用 `AnimatedContent` 交叉淡入淡出；
 *  - 运行中的状态点保持呼吸动画（WbDot pulse）。
 */
@Composable
fun WbHeroCard(
    running: Boolean,
    port: Int,
    uptimeLabel: String,
    lastError: String?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animOn = LocalWbAnimations.current
    // 过渡时长 / 缓动（用户反馈「半透红绿底是硬切换」）：
    //   原为 380ms 且**不带缓动**——线性变化在眼睛看来接近「到点就换」，加上底色透明度只有
    //   8%（浅色主题下几乎读不出色相），于是整卡状态变化被感知为「啪」地一下。
    //   现在：560ms + FastOutSlowInEasing（快起慢收），底色提到 11% —— 底色、图标底座、
    //   状态点、按钮四者同步过渡，状态切换能被清楚「看见」而不是猜。
    val easing = androidx.compose.animation.core.FastOutSlowInEasing
    val duration = if (animOn) 560 else 0
    // 状态色：运行中 = 语义绿，已停止 = 语义红（图标 / 状态点 / 底色统一，一眼可辨）。
    val accent by animateColorAsState(
        if (running) WbPalette.ok else WbPalette.danger,
        tween(duration, easing = easing),
        label = "heroAccent",
    )
    // 底色与运行态同向的**透色**：运行中透绿、已停止透红，而不是「什么都没运行」的空白底。
    val surface by animateColorAsState(
        accent.copy(alpha = 0.11f),
        tween(duration, easing = easing),
        label = "heroSurface",
    )
    // 文案 / 按钮的交叉淡入同步拉长：否则会出现「底色还在过渡、文字已经换完」的割裂感。
    val crossfade = if (animOn) fadeIn(tween(360, easing = easing)) togetherWith fadeOut(tween(240, easing = easing))
    else androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None

    WbSection(modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().background(surface).padding(WbD.cardPad),
            verticalArrangement = Arrangement.spacedBy(WbD.gap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(targetState = running, transitionSpec = { crossfade }, label = "heroIcon") { r ->
                    WbIconTile(if (r) MiuixIcons.Play else MiuixIcons.Pause, accent)
                }
                WbHSpace(WbD.gap)
                Box(Modifier.weight(1f)) {
                    AnimatedContent(targetState = running, transitionSpec = { crossfade }, label = "heroTitle") { r ->
                        Column {
                            Text(
                                if (r) "网关运行中" else "网关已停止",
                                style = MiuixTheme.textStyles.subtitle,
                                fontWeight = FontWeight.SemiBold,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                if (r) "监听 0.0.0.0:$port · $uptimeLabel" else "点击下方按钮启动服务",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
                WbDot(accent, 10, pulse = running)
            }
            if (lastError != null && !running) {
                WbBanner("上次启动失败：$lastError", WbBannerKind.Danger, icon = MiuixIcons.Info)
            }
            AnimatedContent(targetState = running, transitionSpec = { crossfade }, label = "heroButton") { r ->
                WbButton(
                    text = if (r) "停止网关" else "启动网关",
                    onClick = onToggle,
                    modifier = Modifier.fillMaxWidth(),
                    // 「启动网关」= 透绿（成功色半透明底），与「停止网关」的透红对称；
                    // 卡片底色（透绿 / 透红）与按钮底色因此同向，状态一眼可辨。
                    variant = if (r) WbButtonVariant.Danger else WbButtonVariant.Success,
                    icon = if (r) MiuixIcons.Pause else MiuixIcons.Play,
                )
            }
        }
    }
}

/**
 * 请求归档的出口：导出到设备（公共「下载」目录）+ 删除本地归档文件。
 *
 * 归档文件在应用私有目录，文件管理器看不到 —— 没有这两个出口，「开了归档」等于没有归档。
 * 导出与删除都会改写/移除用户数据，因此**都走二次确认**（用户明确要求导出也要确认）。
 */
@Composable
fun WbArchiveActions(vm: AppViewModel) {
    val stats by vm.archiveStats.collectAsStateWithLifecycle()
    var confirmExport by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    // 值列必须短：WbRow 的值不带换行/省略，长文本会和标签抢宽度。
    val status = when {
        !stats.enabled -> "未启用"
        stats.count <= 0 -> "无内容"
        else -> "${stats.count} 条"
    }

    WbRow(
        label = "导出到设备（下载/wb2api）",
        value = status,
        leading = { WbIconTile(MiuixIcons.Backup, if (stats.count > 0) WbPalette.info else MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 26.dp) },
        chevron = stats.count > 0,
        onClick = { if (stats.count > 0) confirmExport = true else vm.toast("暂无归档内容：先开启归档，再产生一次请求") },
        showDivider = true,
    )
    WbRow(
        label = "删除归档日志文件",
        value = "不可撤销",
        leading = { WbIconTile(MiuixIcons.Delete, WbPalette.danger, size = 26.dp) },
        labelColor = WbPalette.danger,
        onClick = { confirmClear = true },
    )

    if (confirmExport) {
        OverlayDialog(
            show = true,
            title = "导出归档",
            summary = "将把本地归档（${stats.count} 条 · ${fmtBytes(stats.bytes)}）复制到公共的「下载/wb2api/」目录，" +
                "文件名带时间戳。原文件保留不动，可随时再导出或删除。",
            onDismissRequest = { confirmExport = false },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WbD.gapS)) {
                WbButton(
                    "确认导出",
                    onClick = { vm.exportArchive(); confirmExport = false },
                    modifier = Modifier.fillMaxWidth(),
                    icon = MiuixIcons.Backup,
                )
                WbTextButton("取消", onClick = { confirmExport = false }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (confirmClear) {
        val detail = if (stats.count > 0) {
            "当前归档 ${stats.count} 条 · ${fmtBytes(stats.bytes)}" +
                if (stats.updatedAt > 0) "，最近写入 ${fmtTime(stats.updatedAt)}" else ""
        } else "当前归档没有内容"
        OverlayDialog(
            show = true,
            title = "删除归档日志",
            summary = "$detail。删除后不可恢复；账号、池状态与用量统计不受影响。",
            onDismissRequest = { confirmClear = false },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WbD.gapS)) {
                WbButton(
                    "确认删除",
                    onClick = { vm.clearArchive(); confirmClear = false },
                    modifier = Modifier.fillMaxWidth(),
                    variant = WbButtonVariant.Danger,
                    icon = MiuixIcons.Delete,
                )
                WbTextButton("取消", onClick = { confirmClear = false }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun fmtBytes(b: Long): String = when {
    b <= 0 -> "0 B"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    else -> "%.1f MB".format(b / 1024.0 / 1024.0)
}

// 用 java.time（不可变、线程安全）替代每次新建的 SimpleDateFormat —— 与 LogBus 的既有做法一致。
private val fmtTimeFmt: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm", java.util.Locale.US)

private fun fmtTime(epochSec: Long): String =
    java.time.Instant.ofEpochSecond(epochSec).atZone(java.time.ZoneId.systemDefault()).format(fmtTimeFmt)

/**
 * 启停网关时的 Loading 遮罩。
 *
 * 内部要等服务真正起来（端口绑定、调度器拉起）或真正停下（线程池收口），这段时间界面
 * 必须有明确反馈；遮罩会拦截触摸，避免重复点击。最短可见时长由 ViewModel 保证（否则
 * 状态切换只要 <100ms，动画就只剩一帧的「闪」）。
 */
@Composable
fun WbBusyOverlay(visible: Boolean, label: String, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(200)),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .wbGlass(
                        shape = com.kyant.shapes.RoundedRectangle(26.dp, com.kyant.shapes.RoundedCornerStyle.Continuous),
                        tint = WbGlass.neutralTint(),
                        enableShadow = true,
                    )
                    .padding(horizontal = WbD.gapL, vertical = WbD.gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                    val transition = rememberInfiniteTransition(label = "busy")
                    val angle by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                        label = "busyAngle",
                    )
                    Icon(
                        MiuixIcons.Refresh,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = angle },
                    )
                    WbHSpace(WbD.gapS)
                    Text(label, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurface)
                }
            }
        }
    }

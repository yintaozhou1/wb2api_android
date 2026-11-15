package com.wb.api.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Reset
import com.wb.api.upstream.Up
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏刷新按钮：**加载反馈就在点击处** —— 加载中时图标持续旋转并禁用重复点击。
 *
 * 为什么不再用页面内进度条：反馈出现的位置离手指很远，并且会挤进版面中间
 * （模型页原来在玻璃面板底边一条 4dp 槽、账号页在「添加账号」与分段控件之间），
 * 与相邻组件视觉重叠、还会把内容顶来顶去。旋转图标与按钮同位，天然不会压到任何组件，
 * 两个页面的刷新交互也就此统一。
 */
@Composable
fun WbRefreshAction(
    loading: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val hapticOn = LocalWbHaptics.current
    val tint = MiuixTheme.colorScheme.primary
    IconButton(
        onClick = {
            if (hapticOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        // 加载中禁用重复点击（Miuix IconButton 自带 enabled 语义与视觉降级）。
        enabled = !loading,
    ) {
        if (loading) {
            // 动画只在加载期存在：空闲时不留下一个常驻的动画帧循环。
            val transition = rememberInfiniteTransition(label = "refreshSpin")
            val angle by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                label = "refreshAngle",
            )
            Icon(
                MiuixIcons.Refresh,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.graphicsLayer { rotationZ = angle },
            )
        } else {
            Icon(MiuixIcons.Refresh, contentDescription = contentDescription, tint = tint)
        }
    }
}

// ============================================================
// 交互原语：按压缩放动画 + 震动反馈（全局统一，所有可点区域都走这里）
// ============================================================

/**
 * 按压缩放 + 震动。
 *
 * 之前所有自绘可点区域都是裸 `Modifier.clickable`——既没有按压反馈，也没有震动。
 * 现在统一走本原语：按下缩到 0.97（spring 回弹），点击时按类型触发系统触感。
 */
@Composable
fun Modifier.wbPress(
    enabled: Boolean = true,
    haptic: HapticFeedbackType = HapticFeedbackType.LongPress,
    onClick: () -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val hapticOn = LocalWbHaptics.current
    val animOn = LocalWbAnimations.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && animOn) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 900f),
        label = "wbPressScale",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
        ) {
            if (hapticOn && enabled) haptics.performHapticFeedback(haptic)
            onClick()
        }
}

@Composable
private fun rowHighlight(pressed: Boolean): Color =
    if (pressed) MiuixTheme.colorScheme.surfaceContainerHigh else Color.Transparent

// ============================================================
// 布局辅助
// ============================================================

@Composable
fun WbVSpace(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun WbHSpace(w: Dp) = Spacer(Modifier.width(w))

/** 带色块底座的图标：一眼区分「这是哪个功能区」，而不是清一色的灰图标。 */
@Composable
fun WbIconTile(
    icon: ImageVector,
    tint: Color,
    size: Dp = WbD.iconTile,
    corner: Dp = WbD.radiusS,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/**
 * 分组卡片：统一小标题 + 圆角卡片。
 * `trailing` 用于在标题行右侧放「恢复默认」这类就地操作（替代全局"保存并应用"按钮）。
 */
@Composable
fun WbSection(
    title: String? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    SmallTitle(text = title)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                        )
                    }
                }
                trailing?.invoke()
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = contentPadding,
            content = content,
        )
    }
}

// ============================================================
// 列表行
// ============================================================

/**
 * 统一列表行：左标签 / 右值 / 可选箭头 / 可选分隔线 / 可选点击。
 * 行高、内边距、字号全部取令牌，保证「同一卡片内所有行严格对齐」。
 */
@Composable
fun WbRow(
    label: String,
    value: String? = null,
    valueColor: Color? = null,
    chevron: Boolean = false,
    showDivider: Boolean = false,
    labelColor: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val hapticOn = LocalWbHaptics.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(rowHighlight(pressed && onClick != null), tween(120), label = "rowBg")

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(bg)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(interactionSource = interaction, indication = null) {
                            if (hapticOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClick()
                        }
                    } else Modifier,
                )
                .heightIn(min = WbD.tapMin)
                .padding(horizontal = WbD.rowPadH, vertical = WbD.rowPadV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                WbHSpace(WbD.gap)
            }
            // 标签：单行 + 省略号。**绝不能**让它被长 value 挤到 0 宽 —— 那会让
            // 「上次结果」四个字逐字换行、竖着排成一列（实测截图里的排版错乱就是这个）。
            Text(
                label,
                style = MiuixTheme.textStyles.body1,
                color = labelColor ?: MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                // 始终占满自己的权重槽位（= 行宽的一半）。
                // ⚠️ 这里**不能**用 fill = false：那样标签只占内容宽，而权重槽位仍是半行，
                //    于是值会从「标签右边界」开始（而不是从半行处开始）→ 值不再贴右、
                //    悬在中间偏左（1.5.4 的回归，用户截图可见）。
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                WbHSpace(WbD.gapS)
                // 值：占据标签之外的剩余宽度，允许换行、右对齐 —— 长文本不再挤爆标签。
                Text(
                    value,
                    style = MiuixTheme.textStyles.body2,
                    color = valueColor ?: MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
            if (chevron) {
                WbHSpace(WbD.gapS)
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = WbD.rowPadH),
                color = MiuixTheme.colorScheme.dividerLine,
            )
        }
    }
}

/** 可复制的行（Base URL / API Key）：点击即复制并震动。 */
@Composable
fun WbCopyRow(label: String, value: String, showDivider: Boolean = false, onCopied: (() -> Unit)? = null) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val hapticOn = LocalWbHaptics.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(rowHighlight(pressed), tween(120), label = "copyBg")

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(bg)
                .clickable(interactionSource = interaction, indication = null) {
                    // 新剪贴板 API 是 suspend 的（对应系统剪贴板的异步语义），走本组件的
                    // rememberCoroutineScope；相比旧的同步 setText 行为一致、不阻塞点击回调。
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value)))
                    }
                    if (hapticOn) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onCopied?.invoke()
                }
                .heightIn(min = WbD.tapMin)
                .padding(horizontal = WbD.rowPadH, vertical = WbD.rowPadV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                WbVSpace(WbD.gapXs)
                Text(value, style = MiuixTheme.textStyles.body2, maxLines = 1)
            }
            WbHSpace(WbD.gapS)
            Icon(
                imageVector = MiuixIcons.Copy,
                contentDescription = "复制",
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        if (showDivider) {
            HorizontalDivider(modifier = Modifier.padding(start = WbD.rowPadH), color = MiuixTheme.colorScheme.dividerLine)
        }
    }
}

// ============================================================
// 指标块 / 标签 / 空态 / 横幅
// ============================================================

/** 指标块：数值带滚动增长动画（0 → 目标值），可选彩色图标底座，点击进对应页。 */
/**
 * 额度文案：企业版「不限量」在池内用 [Up.ENTERPRISE_UNLIMITED_REMAIN]（1<<40）表示，
 * 直接格式化会打印出 1099511627776 —— 这里统一显示成「不限」。
 */
fun wbCreditsText(v: Double): String =
    if (v >= Up.ENTERPRISE_UNLIMITED_REMAIN) "不限" else "%.1f".format(v)

@Composable
fun WbStatTile(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    icon: ImageVector? = null,
    sub: String? = null,
    onClick: (() -> Unit)? = null,
    /** 覆盖显示文本（例如企业版「不限」）；非空时不再显示动画数字。 */
    text: String? = null,
) {
    var target by remember { mutableIntStateOf(0) }
    LaunchedEffect(value) { if (text == null) target = value }
    val shown by animateIntAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "statCount",
    )
    val haptics = LocalHapticFeedback.current
    val hapticOn = LocalWbHaptics.current
    val tint = accent ?: MiuixTheme.colorScheme.primary

    val body: @Composable ColumnScope.() -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                WbIconTile(icon, tint, size = 28.dp, corner = WbD.radiusXs)
                WbHSpace(WbD.gapS)
            }
            Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        WbVSpace(WbD.gapS)
        Text(
            text ?: "$shown",
            style = MiuixTheme.textStyles.title2,
            fontWeight = FontWeight.Bold,
            color = tint,
        )
        if (sub != null) {
            WbVSpace(WbD.gapXs)
            Text(sub, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
    if (onClick != null) {
        Card(
            modifier = modifier,
            insideMargin = PaddingValues(WbD.cardPad),
            onClick = {
                if (hapticOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
            content = body,
        )
    } else {
        Card(modifier = modifier, insideMargin = PaddingValues(WbD.cardPad), content = body)
    }
}

/** 胶囊标签。 */
@Composable
fun WbPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(text, style = MiuixTheme.textStyles.footnote2, color = color, fontWeight = FontWeight.Medium)
    }
}

/** 小标签（模型能力/标签用）。 */
@Composable
fun WbTag(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(WbD.radiusXs))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, style = MiuixTheme.textStyles.footnote2, color = color)
    }
}

/** 状态圆点；pulse=true 时呼吸闪烁（运行中/加载中的视觉信号）。 */
@Composable
fun WbDot(color: Color, size: Int = 10, pulse: Boolean = false) {
    val animOn = LocalWbAnimations.current
    val transition = rememberInfiniteTransition(label = "wbDot")
    val alpha by transition.dotAlpha(pulse && animOn)
    Box(
        Modifier
            .size(size.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun androidx.compose.animation.core.InfiniteTransition.dotAlpha(pulse: Boolean): androidx.compose.runtime.State<Float> = animateFloat(
    initialValue = 1f,
    targetValue = if (pulse) 0.3f else 1f,
    animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    label = "wbDotAlpha",
)

enum class WbBannerKind { Info, Warn, Danger, Ok }

/** 横幅提示：用于解释「这个开关到底做什么」以及风险提示。 */
@Composable
fun WbBanner(text: String, kind: WbBannerKind = WbBannerKind.Info, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val color = when (kind) {
        WbBannerKind.Info -> WbPalette.info
        WbBannerKind.Warn -> WbPalette.warn
        WbBannerKind.Danger -> WbPalette.danger
        WbBannerKind.Ok -> WbPalette.ok
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WbD.radiusS))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = WbD.gap, vertical = WbD.gapS),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon ?: MiuixIcons.Info, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        WbHSpace(WbD.gapS)
        Text(text, style = MiuixTheme.textStyles.footnote1, color = color, modifier = Modifier.weight(1f))
    }
}

/** 空态。 */
@Composable
fun WbEmpty(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(
                icon, contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp),
            )
            WbVSpace(WbD.gap)
        }
        Text(
            text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

// ============================================================
// 按钮
// ============================================================

enum class WbButtonVariant { Primary, Tonal, Success, Danger }

@Composable
fun WbButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: WbButtonVariant = WbButtonVariant.Primary,
    icon: ImageVector? = null,
    /** 是否用液态玻璃底（目前仅 Tonal 变体支持）。 */
    glass: Boolean = false,
    /** 进行中：图标就地旋转（与 WbRefreshAction 同一套观感），并自动拒绝重复点击。 */
    loading: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val hapticOn = LocalWbHaptics.current
    fun fire() {
        if (!enabled || loading) return
        if (hapticOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onClick()
    }
    val m = if (enabled) modifier else modifier.alpha(0.4f)

    // 进行中的图标旋转：动画只在 loading 期存在（空闲时不留下常驻的帧循环）。
    val spin = if (loading) {
        val tr = rememberInfiniteTransition(label = "btnSpin")
        tr.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "btnSpinAngle",
        ).value
    } else 0f

    when (variant) {
        WbButtonVariant.Primary -> Button(onClick = { fire() }, modifier = m) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = spin },
                )
                WbHSpace(WbD.gapS)
            }
            Text(text)
        }
        WbButtonVariant.Tonal -> {
            val tint = MiuixTheme.colorScheme.primary
            val deco = if (glass) {
                Modifier.wbGlass(
                    shape = com.kyant.shapes.RoundedRectangle(WbD.radiusS, com.kyant.shapes.RoundedCornerStyle.Continuous),
                    tint = tint.copy(alpha = if (wbIsDark()) 0.34f else 0.20f),
                    lensHeight = 8.dp,
                    lensAmount = 8.dp,
                )
            } else {
                Modifier.clip(RoundedCornerShape(WbD.radiusS)).background(tint.copy(alpha = 0.14f))
            }
            Row(
                m
                    .then(deco)
                    .wbPress(enabled = enabled) { fire() }
                    .heightIn(min = 42.dp)
                    .padding(horizontal = WbD.gap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = spin },
                    )
                    WbHSpace(WbD.gapS)
                }
                Text(text, style = MiuixTheme.textStyles.button, color = tint)
            }
        }
        // Success / Danger 共用一套实现：「半透明语义色底 + 同色文字/图标」，只差颜色。
        // 于是「启动网关」（透绿）与「停止网关」（透红）天然成对，状态一眼可辨
        // （原来启动用的是实心主题色，与透红的停止按钮不成对）。
        WbButtonVariant.Success, WbButtonVariant.Danger -> {
            val tint = if (variant == WbButtonVariant.Success) WbPalette.ok else WbPalette.danger
            Row(
                m
                    .clip(RoundedCornerShape(WbD.radiusS))
                    .background(tint.copy(alpha = 0.13f))
                    .wbPress(enabled = enabled) { fire() }
                    .heightIn(min = 42.dp)
                    .padding(horizontal = WbD.gap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = spin },
                    )
                    WbHSpace(WbD.gapS)
                }
                Text(text, style = MiuixTheme.textStyles.button, color = tint)
            }
        }
    }
}

@Composable
fun WbTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val hapticOn = LocalWbHaptics.current
    val label = if (enabled) text else "$text（不可用）"
    if (danger) {
        Row(
            (if (enabled) modifier else modifier.alpha(0.4f))
                .clip(RoundedCornerShape(WbD.radiusS))
                .wbPress(enabled = enabled, haptic = HapticFeedbackType.Reject) {
                    if (hapticOn) haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    onClick()
                }
                .heightIn(min = 42.dp)
                .padding(horizontal = WbD.gap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(label, style = MiuixTheme.textStyles.button, color = WbPalette.danger)
        }
    } else {
        TextButton(
            text = label,
            onClick = {
                if (enabled) {
                    if (hapticOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            },
            modifier = if (enabled) modifier else modifier.alpha(0.4f),
        )
    }
}

/** 「恢复默认」就地按钮（挂在分组标题右侧）。lambda 放最后，便于 `WbResetAction { ... }` 调用。 */
@Composable
fun WbResetAction(text: String = "恢复默认", onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .wbPress(haptic = HapticFeedbackType.SegmentTick, onClick = onClick)
            .padding(horizontal = WbD.gapS, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MiuixIcons.Reset, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(14.dp))
        WbHSpace(WbD.gapXs)
        Text(text, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

// ============================================================
// 分段控件 / 开关 / 步进 / 搜索 / 小时选择
// ============================================================

@Composable
fun WbSegmented(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    /**
     * true = 已经躺在**别人的玻璃面板**上（例如模型页那块合并后的表头玻璃）：
     * 不再自带玻璃容器，只留分段胶囊本体 —— 否则面板里再套一层玻璃，两层底色会互相压叠。
     */
    plain: Boolean = false,
    onSelect: (Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val hapticOn = LocalWbHaptics.current
    val container = if (plain) {
        modifier
    } else {
        modifier
            .wbGlass(
                shape = com.kyant.shapes.RoundedRectangle(WbD.radiusS + 2.dp, com.kyant.shapes.RoundedCornerStyle.Continuous),
                tint = WbGlass.neutralTint(),
                lensHeight = 10.dp,
                lensAmount = 10.dp,
            )
            .padding(3.dp)
    }
    Row(
        container.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val bg by animateColorAsState(
                if (selected) MiuixTheme.colorScheme.primary else Color.Transparent,
                tween(180),
                label = "segBg",
            )
            val fg by animateColorAsState(
                if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                tween(180),
                label = "segFg",
            )
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(WbD.radiusS))
                    .background(bg)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (hapticOn) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        if (!selected) onSelect(index)
                    }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MiuixTheme.textStyles.footnote1, color = fg, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun WbSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val hapticOn = LocalWbHaptics.current
    val animOn = LocalWbAnimations.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(rowHighlight(pressed && enabled), tween(120), label = "swBg")

    val offset by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (animOn) spring(dampingRatio = 0.62f, stiffness = 1100f) else tween(0),
        label = "swOffset",
    )
    val trackColor by animateColorAsState(
        if (checked) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHighest,
        tween(180),
        label = "swTrack",
    )
    val alpha = if (enabled) 1f else 0.45f

    Row(
        modifier
            .fillMaxWidth()
            .alpha(alpha)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null) {
                if (!enabled) return@clickable
                if (hapticOn) {
                    haptics.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                }
                onCheckedChange(!checked)
            }
            .heightIn(min = WbD.tapMin)
            .padding(horizontal = WbD.rowPadH, vertical = WbD.rowPadV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body1)
            if (summary != null) {
                WbVSpace(WbD.gapXs)
                Text(summary, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        WbHSpace(WbD.gap)
        Box(
            Modifier
                .width(50.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(50))
                .background(trackColor),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .graphicsLayer { translationX = offset * 20.dp.toPx() }
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.surface),
            )
        }
    }
}

/** 搜索框（模型列表用）。 */
@Composable
fun WbSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    /** true = 直接铺在玻璃面板上：去掉自带底色，与面板共用同一块玻璃。 */
    transparent: Boolean = false,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = placeholder,
        singleLine = true,
        colors = if (transparent) {
            TextFieldDefaults.textFieldColors(
                backgroundColor = Color.Transparent,
                labelColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            TextFieldDefaults.textFieldColors()
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 玻璃面板内的**分组细线**（1dp）。
 *
 * 用在「一块玻璃里分成几栏」的场景（模型页表头的「搜索 | 筛选」、两组筛选之间）：
 * 比 Miuix 的 HorizontalDivider 更细、不占视觉重量，只负责把同一块玻璃划出层次。
 */
@Composable
fun WbHairline(modifier: Modifier = Modifier, vertical: Boolean = false) {
    Box(
        modifier
            .then(
                if (vertical) Modifier.size(width = 1.dp, height = 20.dp)
                else Modifier.fillMaxWidth().height(1.dp),
            )
            .background(MiuixTheme.colorScheme.dividerLine),
    )
}

/** 小时多选（定时签到/余额刷新时间点）。24 个小格子，横向两行。 */
@Composable
fun WbHourGrid(
    hours: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val hapticOn = LocalWbHaptics.current
    Column(modifier.fillMaxWidth().padding(horizontal = WbD.rowPadH, vertical = WbD.rowPadV)) {
        Text("触发时间（0-23 点，可多选）", style = MiuixTheme.textStyles.body1)
        WbVSpace(WbD.gapS)
        val rows = listOf(0..11, 12..23)
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                r.forEach { h ->
                    val on = h in hours
                    val bg by animateColorAsState(
                        if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHigh,
                        tween(160), label = "hourBg",
                    )
                    val fg by animateColorAsState(
                        if (on) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        tween(160), label = "hourFg",
                    )
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(WbD.radiusXs))
                            .background(bg)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                if (hapticOn) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onToggle(h)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$h", style = MiuixTheme.textStyles.footnote2, color = fg, fontWeight = FontWeight.Medium)
                    }
                }
            }
            WbVSpace(6.dp)
        }
    }
}

// ============================================================
// 底部保存栏（替代全局「保存并应用」）
// ============================================================

/**
 * 设置页底部动作条：
 *  - 没有改动时不显示（减少视觉噪音）；
 *  - 有改动时出现「保存更改 / 放弃」，即「调整后选择应用此选项」；
 *  - 各分组标题旁另有「恢复默认」，即「恢复默认」（局部）。
 */
@Composable
fun WbSaveBar(
    dirty: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!dirty) return
    val accent = MiuixTheme.colorScheme.primary
    val neutral = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        modifier
            .fillMaxWidth()
            .wbGlass(
                shape = com.kyant.shapes.RoundedRectangle(WbD.radius, com.kyant.shapes.RoundedCornerStyle.Continuous),
                tint = WbGlass.neutralTint(),
                enableShadow = true,
            )
            .padding(start = WbD.gap, end = WbD.gapXs, top = WbD.gapS, bottom = WbD.gapS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WbD.gapXs),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            WbDot(WbPalette.warn, 8, pulse = true)
            WbHSpace(WbD.gapS)
            Text(
                "有未保存的更改",
                style = MiuixTheme.textStyles.footnote1,
                color = neutral,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 两个动作按钮**同高同形**（36dp、自带透明底）。
        // 1.4.2 用的是 Miuix 的 TextButton + Button：两者各自带 minWidth/minHeight 与
        // 自己的容器底色，塞进 48dp 高的玻璃条里会高低不齐、底色与玻璃条互相压叠。
        SaveBarAction("放弃", tint = neutral, filled = false, onClick = onDiscard)
        SaveBarAction("保存更改", tint = accent, filled = true, onClick = onSave)
    }
}

/**
 * 保存栏内的紧凑动作按钮。
 *
 * 不直接用 WbButton / WbTextButton：两者都建立在 Miuix 的容器组件上（`defaultMinSize`
 * + 自己的底色），在这条 48dp 玻璃栏里会各自撑出不同高度、并把自己那块底色压在玻璃上。
 * 这里只用「圆角 + 可选半透明强调底 + 按下缩放」，与玻璃栏天然同高、同圆角。
 */
@Composable
private fun SaveBarAction(
    label: String,
    tint: Color,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val hapticOn = LocalWbHaptics.current
    Row(
        Modifier
            .clip(RoundedCornerShape(WbD.radiusS))
            .background(if (filled) tint.copy(alpha = 0.16f) else Color.Transparent)
            .wbPress(haptic = HapticFeedbackType.SegmentTick) {
                if (hapticOn) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onClick()
            }
            .height(36.dp)
            .padding(horizontal = WbD.gap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(label, style = MiuixTheme.textStyles.button, color = tint, maxLines = 1)
    }
}

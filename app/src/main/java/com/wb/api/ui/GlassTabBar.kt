package com.wb.api.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedCornerStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ─────────────────────────────────────────────────────────────────────────────
// 液态玻璃悬浮底栏（真折射版本，见 ui/Glass.kt 的技术说明）
//
// 两层结构：
//   层1 玻璃底栏：vibrancy + blur + lens + 镜面高光 + 投影；
//   层2 液态指示器：**同一块玻璃被「抬高」** —— 中性无色相的底色 + 更厚的模糊 +
//       折射/色散 + 镜面高光 + 投影，强度随按压力度变化；放大倍率（1.50x）刻意
//       远大于栏体（1.06x），拖动时明显涨出栏体。
//   指示器采样 pageBackdrop（页面内容层），模糊的是**真实内容**，不是「自己的玻璃」
//   —— 既不会自采样成环，也不再需要旧版那块「强调色玻璃拷贝」层。
//
// 交互（全部由 AppRoot 的 Pager 统一驱动，见 AppRoot 注释）：
//   • **无需长按**，直接按住底栏左右滑动即可拖动指示器（越过分栏即切换）；
//   • 指示器位置 = Pager 的连续页码（currentPage + offsetFraction），
//     所以「手指拖底栏」「手指滑内容区」「点击某栏」三种输入共享同一条动画来源，
//     悬浮栏动画天然连贯、不跳变；
//   • **拖动只移动遮罩、完全不驱动 Pager**：遮罩位置由手指 x **连续插值**（线性跟手，
//     手指在栏中心 ⇒ 遮罩正好居中；越过栏边界只有一半位置 ⇒ 遮罩也只走一半），
//     松手时才由 onTap(手指所在栏) → AppRoot 的 animateScrollToPage 从**当前页**
//     一次动画切过去（详见手势块注释）。
//   • **高亮（图标 / 文字着色 + 放大）跟随「手指所在的那一栏」**：拖动中即变色，不必等松手
//     —— 与遮罩的连续位置配合，让「松开手会切到哪一栏」在拖动过程中就一目了然
//     （用户反馈：原先只有松手后才变色，拖动时看不出目标栏）。
// ─────────────────────────────────────────────────────────────────────────────

private val BarHeight = 62.dp
private val BarInnerPad = 5.dp
private val IndicatorHeight = 52.dp

@Composable
fun WbGlassTabBar(
    pageBackdrop: Backdrop,
    items: List<Pair<ImageVector, String>>,
    selectedIndex: Int,
    /** 连续页码（含拖动中的实时偏移），驱动指示器位置。 */
    pageOffset: () -> Float,
    /** 按压进度 0..1：驱动放大 / 折射 / 高光强度。 */
    press: () -> Float,
    /** 松手时调用，参数是**手指最终落在的那一栏**（也是要切换过去的目标栏）。 */
    onTap: (Int) -> Unit,
    live: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val hapticOn = LocalWbHaptics.current
    val cs = MiuixTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val accent = cs.primary
    val glassColor = remember(isDark) { WbGlass.surfaceTint(isDark) }
    val barShape = remember { Capsule(RoundedCornerStyle.Continuous) }
    val indicatorShape = remember { Capsule(RoundedCornerStyle.Continuous) }

    val n = items.size.coerceAtLeast(1)

    // ── 按下跟随：遮罩要在「手指按下的那一栏」就位（而不是只跟选中项）────────────────
    // 语义：按下的那一栏 = 用户即将切过去的那一栏。指示器立刻平滑移过去；松手时 Pager 的
    // 切换动画接力，两段动画首尾相接，中间不会闪回旧栏。
    val pressedIdx = remember { mutableStateOf<Int?>(null) }
    val pressing = remember { mutableStateOf(false) }

    // 拖动中遮罩的**连续**位置（单位：栏；手指在第 k 栏中心时为 k）。null = 未在拖动。
    //
    // 为什么需要它：如果遮罩只按「整数栏号」走，就会**非线性** —— 手指刚越过栏边界、
    // 还没到下一栏中心，整数栏号就已经变成下一栏，遮罩于是提前吸到下一栏中心
    // （用户实测反馈：「缓慢拖动时还没拖到另一栏，遮罩已经移到那一栏的中心」）。
    // 记录连续位置后，遮罩与手指 1:1（见手势块 posAt）。
    val dragPos = remember { mutableStateOf<Float?>(null) }

    // 松手之后：等 Pager 真的滚到按下的那一页（或最多 700ms）才交还控制权 ——
    // 否则「松手瞬间指示器先闪回旧页、再滑过去」，比不跟随还难看。
    LaunchedEffect(pressing.value, pressedIdx.value) {
        if (pressing.value) return@LaunchedEffect
        val p = pressedIdx.value ?: return@LaunchedEffect
        withTimeoutOrNull(700) {
            snapshotFlow { pageOffset() }.first { abs(it - p) < 0.02f }
        }
        if (pressedIdx.value == p) pressedIdx.value = null
    }

    // live → snap()：直接取 pageOffset()，与历史行为完全一致（跟手不滞后）；
    // !live → spring：按下 → 平滑移过去，取消 → 平滑回原栏。
    val liveNow = live()
    // 取一次当前按下项再判断（不在 targetValue 表达式中对 State 做 !! 断言 —— 取值两次
    // 之间理论上可能被手势线程改写，且 !! 一旦落空就是崩溃）。
    val pressedNow = pressedIdx.value
    val dragNow = dragPos.value
    // 优先级：拖动中的连续位置 > 已按下的目标栏（按下未拖动）> Pager 的连续页码。
    val posTarget = dragNow ?: pressedNow?.toFloat() ?: pageOffset()
    val indicatorPos by animateFloatAsState(
        targetValue = posTarget,
        animationSpec = if (dragNow != null || (liveNow && pressedNow == null)) {
            // 拖动中 / 内容区滚动中：严格实时、不做任何插值 —— 这是「线性跟手」的关键。
            // （若这里用 spring，遮罩会滞后于手指并被反复重定向，观感就是"不跟手"。）
            snap()
        } else {
            spring(dampingRatio = 0.82f, stiffness = 620f)
        },
        label = "glassTabPos",
    )
    // 按住不动时也把「按压力度」推满，让遮罩真的涨起来；拖动仍由 AppRoot 的 press 驱动。
    val pressValue by animateFloatAsState(
        targetValue = if (pressedNow != null && !liveNow) 1f else press(),
        animationSpec = if (liveNow) snap() else spring(dampingRatio = 0.72f, stiffness = 620f),
        label = "glassTabPress",
    )

    // 指示器（选中态那块「抬起的玻璃」）的底色：中性、无色相。
    // 1.4.2 用的是「强调色玻璃拷贝 + 透蓝底块」，观感像涂了一层蓝；现在靠「更厚的模糊 +
    // 折射 + 镜面高光 + 投影」表达选中，颜色交还给内容本身。
    val indicatorTint = remember(isDark) { WbGlass.raisedTint(isDark) }

    BoxWithConstraints(modifier.fillMaxWidth().height(BarHeight)) {
        val tabWidth = (maxWidth - BarInnerPad * 2) / n
        val tabWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { tabWidth.toPx() }

        // ── 层1：玻璃底栏（拖动时轻微放大）────────────────────────────────
        Box(
            Modifier
                .fillMaxSize()
                .drawBackdrop(
                    backdrop = pageBackdrop,
                    shape = { barShape },
                    effects = {
                        vibrancy()
                        blur(WbGlass.blur.toPx())
                        lens(WbGlass.lensHeight.toPx(), WbGlass.lensAmount.toPx())
                    },
                    // 高光 / 投影都做主题适配：白色镜面高光在浅色背景下读不出边界，
                    // 过重的投影在亮背景上又会读成「气泡下面压了第二层」（详见 WbGlass）。
                    highlight = { Highlight(alpha = if (isDark) 1f else 0.4f) },
                    shadow = { if (isDark) Shadow() else Shadow(alpha = 0.20f) },
                    layerBlock = {
                        // 玻璃栏本体只轻微放大，把「涨出来」的视觉留给下面的指示器。
                        val s = lerp(1f, 1.06f, pressValue)
                        scaleX = s
                        scaleY = s
                    },
                    onDrawSurface = { drawRect(glassColor) },
                )
                // 主题自适应发丝描边：白描边在浅色背景上读不出边界（详见 WbGlass.edgeColor）。
                .border(width = 0.8.dp, brush = WbGlass.edgeBrush(isDark), shape = barShape),
        )

        Box(Modifier.fillMaxSize().padding(horizontal = BarInnerPad)) {
            // ── 层2：液态指示器（一块被「抬高」的玻璃，而不是有色底块）────────
            // 放大倍率刻意**远大于**玻璃底栏（1.50x vs 1.06x）：指示器实测高 52dp，
            // 放大后 ≈78dp，明显**涨出 62dp 的玻璃栏本体**，一眼看出「正在拖动底栏」。
            // 采样 pageBackdrop（页面内容层，不含玻璃本体）→ 模糊的是真实内容，
            // 所以它读起来是「这块玻璃厚了一层」，而不是「盖了个色块」。
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .graphicsLayer {
                        translationX = indicatorPos * tabWidthPx
                        val s = lerp(1f, 1.5f, pressValue)
                        scaleX = s
                        scaleY = s
                    }
                    .width(tabWidth)
                    .height(IndicatorHeight)
                    .drawBackdrop(
                        backdrop = pageBackdrop,
                        shape = { indicatorShape },
                        effects = {
                            vibrancy()
                            // 比底栏更厚的模糊（1.35x）：同一片内容在指示器里更糊 → 有厚度感。
                            blur(WbGlass.blur.toPx() * 1.35f)
                            val p = pressValue
                            if (p > 0.01f) {
                                lens(12.dp.toPx() * p, 20.dp.toPx() * p, chromaticAberration = true)
                            }
                        },
                        // 浅色同样需要**读得出来**的镜面高光：1.4.6 把浅色底色从 96% 降到 55%
                        // 之后，若高光还停在 0.20，整块指示器会「平」得看不出边界 —— 抬到与底栏同档
                        // （0.40），并把静止态投影从 0.10 增到 0.12，让它始终「浮」在栏体上。
                        // 深色维持原样（静止无投影、越用力越明显）。
                        highlight = { Highlight(alpha = if (isDark) 0.35f + 0.55f * pressValue else 0.40f + 0.30f * pressValue) },
                        shadow = { if (isDark) Shadow(alpha = pressValue) else Shadow(alpha = 0.12f + 0.35f * pressValue) },
                        onDrawSurface = { drawRect(indicatorTint) },
                    )
                    .border(width = 0.8.dp, brush = WbGlass.edgeBrush(isDark), shape = indicatorShape),
            )
            // ── 层4：图标 / 文字 + 手势（无需长按，直接拖）──────────────────
            Row(
                Modifier
                    .fillMaxSize()
                    .pointerInput(n) {
                        awaitEachGesture {
                            try {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                // 底栏要**独占**这串手势：立刻消费 down，否则下层的 HorizontalPager
                                // 会同时处理同一次拖动（内容被移动两次 → 抖动、越滑越偏）。
                                down.consume()
                                // 手指落在第几栏 —— 即**松手后要去的目标栏**（整数）。
                                fun idxAt(x: Float): Int =
                                    if (tabWidthPx <= 0f) 0 else (x / tabWidthPx).toInt().coerceIn(0, n - 1)
                                // 连续栏号：第 k 栏中心在 (k + 0.5) * 栏宽，故 x / 栏宽 - 0.5
                                // 正好在「手指位于第 k 栏中心」时等于 k。夹在 [0, n-1] 内。
                                fun posAt(x: Float): Float =
                                    if (tabWidthPx <= 0f) 0f
                                    else (x / tabWidthPx - 0.5f).coerceIn(0f, (n - 1).toFloat())
                                var targetIdx = idxAt(down.position.x)
                                pressing.value = true
                                pressedIdx.value = targetIdx
                                // 按下即把遮罩放到手指的**连续**位置（线性跟手，不做吸附）。
                                dragPos.value = posAt(down.position.x)
                                // ★ 拖动**只移动遮罩、不动 Pager**，松手时再由 onTap →
                                //   animateScrollToPage 从「当前页」动画到目标栏。
                                //
                                //   为什么不是「拖动实时驱动 Pager」：那样位移只能相对**当前页**算，
                                //   于是「首页按住设置、滑到日志」会被算成「首页 + (-1) 栏」；
                                //   若在拖动开始瞬间把 Pager 对齐到按下的栏，又会有一次
                                //   「首页 → 设置」的抢跑，变成「设置 → 日志」两段动画。
                                //   现在是「遮罩跟手预览 + 松手一次干净切换」：首页 → 日志。
                                //
                                //   拖动过程中**只**更新「连续位置」（遮罩 1:1 跟手）与「目标栏」
                                //   （松手时要去的那一栏），不碰 Pager。
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    dragPos.value = posAt(change.position.x)
                                    val i = idxAt(change.position.x)
                                    if (i != targetIdx) {
                                        targetIdx = i
                                        pressedIdx.value = i
                                    }
                                }
                                if (hapticOn) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onTap(targetIdx)
                            } finally {
                                // 无论正常抬手还是手势被打断，都清掉「按下态」与「拖动位置」；
                                // 之后位置由 LaunchedEffect 交还给 pageOffset()。
                                pressing.value = false
                                dragPos.value = null
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { i, (icon, label) ->
                    // 高亮跟「手指当前所在 / 即将切过去的那一栏」走，而不是等松手后 Pager 真正切完页：
                    // 拖动中 pressedIdx 会实时更新，于是手指拖到哪一栏、那一栏的图标与文字**当场**变色。
                    // 未拖动时（pressedNow == null）回落到 Pager 的当前页。
                    // 颜色与缩放本来就走 animateColorAsState / animateFloatAsState 过渡，
                    // 所以跨越栏位时是「平滑交叉」，不会突兀。
                    val selected = i == (pressedNow ?: selectedIndex)
                    val tint by animateColorAsState(
                        targetValue = if (selected) accent else cs.onSurfaceVariantSummary,
                        animationSpec = tween(200),
                        label = "glassTint",
                    )
                    val scale by animateFloatAsState(
                        targetValue = if (selected) 1f else 0.94f,
                        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
                        label = "glassIconScale",
                    )
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
                            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            label,
                            style = MiuixTheme.textStyles.footnote2,
                            color = tint,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

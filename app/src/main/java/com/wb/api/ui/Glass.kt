package com.wb.api.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ─────────────────────────────────────────────────────────────────────────────
// 液态玻璃基座
//
// 全应用共用同一套「液态玻璃」渲染：把页面内容记录成背景层（LocalPageBackdrop），
// 玻璃组件通过 `Modifier.wbGlass(...)` 对**背后的真实内容**做
//   ① vibrancy（内容感知饱和度） ② GPU 高斯模糊 ③ AGSL 真实折射（lens）
//   ④ 边缘镜面高光（highlight）   ⑤ 可选投影（shadow）
//
// 两种形态：
//   • **真玻璃**：能取到背景层时 → vibrancy + blur + AGSL 折射 + 高光 + 投影。
//     只允许用在**被记录子树之外**的悬浮层（顶栏 / 底栏 / Toast / Loading）。
//   • **磨砂玻璃**：取不到背景层时 → 半透明底 + 顶部高光描边（+ 软阴影）。
//     页面内部的玻璃控件必须走这条 —— 否则会自采样形成循环显示列表并闪退。
//
// 注意：`lens()` 要求形状是 `RoundedRectangularShape`（com.kyant.shapes 的
// Capsule / RoundedRectangle）或 Compose 的 CornerBasedShape，否则会抛异常——
// 因此本文件所有形状都来自 com.kyant.shapes。
// ─────────────────────────────────────────────────────────────────────────────

/** 玻璃调参集中处（量级对齐 Backdrop 官方示例 / LiquidTab）。 */
object WbGlass {
    /** 高斯模糊半径。 */
    val blur = 8.dp
    /** 折射（玻璃厚度）。 */
    val lensHeight = 18.dp
    /** 折射位移量。 */
    val lensAmount = 18.dp

    /**
     * 中性玻璃底色 —— **真玻璃**形态（能采到背景层、会做 blur 的悬浮层）用。
     *
     * 刻意不用主题色，否则整片玻璃会被染成一种色而失去玻璃感。
     * **浅色是本版重点**：浅色页面本身就接近白色，纯白底色（42% 也好、62% 也罢）叠上去都与
     * 背景**同亮度** —— 肉眼看到的就是一条「实心白条」：既没有透明度，也读不出玻璃，
     * 与深色（#121212@42%，暗底让内容透出来）完全不对称。
     * 因此浅色改用**淡灰玻璃**（#E9E9ED @ 55%，比页面暗约 5%）：
     *   ① 底色与页面之间出现可见层次；② 背后内容（卡片、蓝色图标）会**透出来**被模糊成色斑。
     * 依据：用户提供的浅/深色悬浮底栏对比截图 —— 浅色时底栏与页面「亮度完全相同、无内容透过」。
     */
    fun surfaceTint(isDark: Boolean): Color =
        if (isDark) Color(0xFF121212).copy(alpha = 0.42f) else Color(0xFFE9E9ED).copy(alpha = 0.55f)

    /**
     * 中性玻璃底色 —— **磨砂**形态（取不到背景层、没有 blur 的页面内玻璃）用。
     *
     * 没有模糊帮忙，底色必须更「实」才立得住：浅色给到白 86%；深色仍是近黑半透明
     * （与真玻璃一致 —— 深色页面本身就暗，42% 的黑已足够读成一块玻璃）。
     */
    fun frostedTint(isDark: Boolean): Color =
        if (isDark) Color(0xFF121212).copy(alpha = 0.42f) else Color.White.copy(alpha = 0.86f)

    /**
     * 玻璃**边缘描边**色（主题自适应）。
     *
     * 原来是固定的白色渐变（白 34% → 透明）：深色下是清亮的镜面高光，**浅色下等于隐形**
     * ——「液态玻璃的边缘线是白色，切到浅色模式看不清」就是这么来的。
     * 浅色改用**深色发丝线**（黑 16% → 透明），与「白底 + 发丝描边」的通行做法一致，
     * 玻璃边界因此读得出来。
     */
    fun edgeColor(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.34f) else Color.Black.copy(alpha = 0.16f)

    /** 描边渐变的收尾色（上亮下隐）。 */
    fun edgeColorSoft(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f)

    /** 边缘画笔：磨砂形态与真玻璃形态的描边共用同一条（明暗各一套）。 */
    fun edgeBrush(isDark: Boolean): Brush = Brush.verticalGradient(
        0f to edgeColor(isDark),
        0.55f to edgeColorSoft(isDark),
        1f to Color.Transparent,
    )

    /**
     * 投影色。
     *
     * 浅色下必须**更轻**：亮背景上一团深色会读成「气泡底下压了第二层、还错开了一点」。
     */
    fun shadowColor(isDark: Boolean): Color =
        if (isDark) Color.Black.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.13f)

    /**
     * 中性玻璃底色的**统一入口**：按「当前能不能采到背景层」自动选择 [surfaceTint] /
     * [frostedTint]。
     *
     * 同一个玻璃组件会在两种形态下出现 —— 例如分段控件既在「浮动玻璃表头」里（真玻璃）、
     * 也在设置页正文里（磨砂）。调用方不必自己判断所处形态，交给这里即可。
     */
    @Composable
    fun neutralTint(): Color =
        if (LocalPageBackdrop.current != null) surfaceTint(wbIsDark()) else frostedTint(wbIsDark())

    /**
     * 「抬起的玻璃」底色（悬浮底栏的选中指示器用）：中性、微亮、**不带任何色相**。
     *
     * 为什么不用强调色：染成蓝/紫的指示器看起来就是「一块涂了色的底块」；
     * 液态玻璃的表达方式是**同一块玻璃被抬高**，靠更厚的模糊 + 折射 + 镜面高光 + 投影
     * 拉开层次。
     *
     * 浅色这一档是 1.4.6 的重点修正。曾取「白 96%」，理由是「浅色栏体是高透白，
     * 指示器必须接近纯白才读得出抬起来」——但 1.4.5 起栏体已改成**淡灰玻璃**
     * （见 [surfaceTint]：#E9E9ED@55%），这个前提不再成立：96% 的白等于**一块不透明的
     * 实心白块**，背后被 blur 的内容一点也透不出来，读不出任何玻璃感
     * （用户反馈「浅色模式选中栏不是透明液态玻璃，深色正常」）。
     *
     * 现在降到 55%：与栏体拉开约 3% 明度差，同时留一半以上的透过率 ——
     * 指示器里同样是**被模糊过的真实内容**，与深色（白 10%）在「透明度」这一维上对称；
     * 「抬起来」的层次改由更厚的模糊（1.35x）+ 折射 + 高光 + 投影承担。
     */
    fun raisedTint(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.55f)
}

/**
 * 当前页面的「背景层」。由 AppRoot 在内容容器上通过 `Modifier.layerBackdrop(...)` 记录；
 * 玻璃组件据此采样背后内容。为 null 时玻璃组件自动降级为「半透明底 + 高光描边」。
 */
val LocalPageBackdrop = compositionLocalOf<Backdrop?> { null }

/** 当前是否深色主题（以 surface 亮度判定，跟随应用内主题切换）。同包内可直接调用，无需 import。 */
@Composable
fun wbIsDark(): Boolean = MiuixTheme.colorScheme.surface.luminance() < 0.5f

/** 真玻璃的镜面高光：白色高光在浅色亮背景上等于没有边缘，浅色压到 0.4（边缘交给描边）。 */
private fun glassHighlight(isDark: Boolean): () -> Highlight = { Highlight(alpha = if (isDark) 1f else 0.4f) }

/** 真玻璃的投影：浅色压淡，避免亮背景上读成「第二层气泡」。 */
private fun glassShadow(isDark: Boolean): () -> Shadow = { if (isDark) Shadow() else Shadow(alpha = 0.20f) }

/**
 * 把任意组件变成一块液态玻璃。
 *
 * @param shape 玻璃形状（必须是 kyant shapes 的圆角形状或 CornerBasedShape，折射需要 SDF）。
 * @param tint  玻璃底色（半透明）。
 */
@Composable
fun Modifier.wbGlass(
    shape: Shape,
    tint: Color,
    blurRadius: Dp = WbGlass.blur,
    lensHeight: Dp = WbGlass.lensHeight,
    lensAmount: Dp = WbGlass.lensAmount,
    enableBlur: Boolean = true,
    enableLens: Boolean = true,
    enableHighlight: Boolean = true,
    enableShadow: Boolean = false,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier {
    val backdrop = LocalPageBackdrop.current
    val isDark = wbIsDark()
    val edge = WbGlass.edgeBrush(isDark)
    if (backdrop == null) {
        // 「磨砂玻璃」形态（没有背景层可采样时）：半透明底 + 主题自适应发丝描边（+ 可选软阴影）。
        //
        // ⚠️ 页面内部的玻璃控件（分段控件 / 页面内按钮 / 保存栏）走的就是这条路径。它们位于
        //    「被记录成背景层的子树」里，一旦用 drawBackdrop 采样该层，就会形成
        //      pageBackdrop --记录--> 控件自身图层 --采样--> pageBackdrop
        //    的**循环显示列表**，HWUI 合成时无限递归 → 进程被杀（1.4 版点击任意非首页栏目的闪退根因）。
        //    因此 AppRoot 把页面内容包在 `LocalPageBackdrop provides null` 作用域内。
        //    描边与投影都必须主题自适应：白描边在浅色背景上不可见；深色投影在亮背景上会读成
        //    「气泡底下压了第二层」（见 WbGlass.edgeColor / shadowColor）。
        val deco = if (enableShadow) {
            shadow(
                elevation = 4.dp,
                shape = shape,
                clip = false,
                ambientColor = WbGlass.shadowColor(isDark),
                spotColor = WbGlass.shadowColor(isDark),
            )
        } else this
        return deco
            .clip(shape)
            .background(tint)
            .border(width = 0.8.dp, brush = edge, shape = shape)
    }
    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                if (enableBlur) blur(blurRadius.toPx())
                if (enableLens) lens(lensHeight.toPx(), lensAmount.toPx())
            },
            highlight = if (enableHighlight) glassHighlight(isDark) else null,
            shadow = if (enableShadow) glassShadow(isDark) else null,
            layerBlock = layerBlock,
            onDrawSurface = { drawRect(tint) },
        )
        // 主题发丝描边（画在玻璃面之上）——两种主题下边界都可读。
        .border(width = 0.8.dp, brush = edge, shape = shape)
}

/**
 * 「玻璃表头 + 可被折射的内容」页面骨架。
 *
 * 页面主体（列表）被记录成一块**玻璃背景层**；表头（含分段控件等玻璃控件）作为它的
 * **兄弟节点**浮在上方 —— 这是 backdrop 库唯一安全的叠放方式（详见本文件顶部说明）：
 * 表头不在被记录的子树里，所以它采样该层不会成环。
 *
 * 好处：列表向上滚动时会滑到表头**下方**被真实模糊 / 折射，形成连续过渡，
 * 不会出现「卡片贴着表头下边缘被硬裁切」的割裂感。
 *
 * 注意：调用方需要给列表留出表头高度的顶部间距（表头是浮动的，不占内容流），
 * 例如 `contentPadding = PaddingValues(top = 表头高度 + WbD.pageTop, bottom = WbD.pageBottom)`。
 */
@Composable
fun WbGlassHeaderPage(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    // 表头采样这一层；它记录的是「不含玻璃控件」的内容子树。
    val headerBackdrop = rememberLayerBackdrop()
    Box(modifier.fillMaxSize()) {
        // 内容层：被记录成玻璃背景层 —— 内部**不允许**再出现玻璃控件（否则自采样成环闪退）。
        Box(Modifier.fillMaxSize().layerBackdrop(headerBackdrop)) {
            CompositionLocalProvider(LocalPageBackdrop provides null) { content() }
        }
        // 表头层：兄弟节点（不在被记录子树内）→ 玻璃控件可安全采样 headerBackdrop。
        CompositionLocalProvider(LocalPageBackdrop provides headerBackdrop) { header() }
    }
}

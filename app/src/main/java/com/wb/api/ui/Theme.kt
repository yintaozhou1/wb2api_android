package com.wb.api.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 全局尺寸令牌。
 *
 * 圆角只有三档，且**按元素尺度**分配，不再随手写数值：
 *  - [radius]   大容器（卡片内层大块、保存栏、Hero）
 *  - [radiusS]  常规控件（分段控件、图标底座、输入框容器）
 *  - [radiusXs] 极小元素（标签 WbTag）；胶囊类一律 50%
 * 间距同理分档：块间 [gap]、块内 [gapS]、紧邻 [gapXs]。
 */
object WbD {
    /** 页面左右安全边距（与 Miuix 卡片外边距对齐）。 */
    val pageH = 16.dp
    // 悬浮玻璃顶栏（高 52 + 上下 8*2 = 68dp）之下的净空，页面内容从它下方开始；
    // 滚动时内容会从玻璃顶栏下方穿过，被折射 / 模糊。
    val pageTop = 72.dp
    // 悬浮玻璃底栏占用高度（栏高 58 + 上下间距 12*2 = 82dp，再留约 22dp 呼吸）；
    // 列表底部与页面操作按钮据此留白，内容可延伸到栏下被玻璃模糊。
    val pageBottom = 104.dp

    /**
     * 浮动玻璃表头与其下方内容之间的**呼吸间距**。
     *
     * 表头（模型 / 账号 / 日志页那条玻璃）是浮在内容之上的兄弟节点、不占内容流，
     * 内容靠 `contentPadding` 给它留白。留白若只等于表头高度，首项就会**紧贴玻璃下边缘**
     * （模型页更糟：留白实测漏算了顶部净空，首行卡片直接被压在玻璃底下）。
     * 留出这段间距后：静止时首项完整可见，只有**主动上滑**时内容才滑进玻璃下方被模糊
     * —— 与日志页的表现一致。
     */
    val headerGap = 12.dp

    /** 页面底部动作条（日志页「清空日志 / 复制全部」那一行）的高度。 */
    val bottomActionH = 42.dp

    /**
     * Toast 的底部偏移。
     *
     * Toast 是全局浮层，必须让过**页面里所有**常驻的底部元素，否则会压在按钮上：
     *   悬浮底栏净空 [pageBottom] + 页面动作条 [bottomActionH] + 12dp 呼吸。
     * （历史上只让过底栏，于是日志页「已复制 N 条日志」正好落在「复制全部」上。）
     */
    val toastBottom = pageBottom + bottomActionH + 12.dp

    /** 层级间距：块与块 [gap]，同块内行与行 [gapS]，紧邻元素 [gapXs]。 */
    val gapL = 14.dp
    val gap = 10.dp
    val gapS = 6.dp
    val gapXs = 4.dp

    val cardPad = 14.dp
    val rowPadV = 11.dp
    val rowPadH = 14.dp

    val radius = 14.dp
    val radiusS = 12.dp
    val radiusXs = 8.dp

    val icon = 20.dp
    val iconL = 24.dp
    val tapMin = 44.dp

    /** 图标底座（带色块）尺寸。 */
    val iconTile = 34.dp
}

/**
 * 语义色（成功/警告/信息/危险）。随明暗切换，保证两种主题下对比度都达标。
 *
 * 这些颜色**不替代** Miuix 的 primary/secondary/tertiary，而是补足「状态语义」——
 * 状态必须一眼可辨（可用/冷却/禁用），用主题色表达状态会让人分不清「这是装饰还是告警」。
 */
@Immutable
data class WbSemantic(
    val ok: Color,
    val warn: Color,
    val info: Color,
    val danger: Color,
)

private val LightSemantic = WbSemantic(
    ok = Color(0xFF12894B),
    warn = Color(0xFFB25E00),
    info = Color(0xFF1B62D6),
    danger = Color(0xFFC3352B),
)

private val DarkSemantic = WbSemantic(
    ok = Color(0xFF4ED07E),
    warn = Color(0xFFFFB340),
    info = Color(0xFF6FB4FF),
    danger = Color(0xFFFF6961),
)

val LocalWbSemantic = staticCompositionLocalOf { LightSemantic }

/** 当前主题下的语义色。用法：`WbPalette.ok`。 */
object WbPalette {
    val ok: Color @Composable get() = LocalWbSemantic.current.ok
    val warn: Color @Composable get() = LocalWbSemantic.current.warn
    val info: Color @Composable get() = LocalWbSemantic.current.info
    val danger: Color @Composable get() = LocalWbSemantic.current.danger
}

/** 触感反馈总开关（设置页可关；关掉后所有组件不再震动）。 */
val LocalWbHaptics = staticCompositionLocalOf { true }

/** 动画总开关（设置页可关；关掉后过渡动画退化为瞬间切换）。 */
val LocalWbAnimations = staticCompositionLocalOf { true }

/** 当前是否深色（供状态栏/导航栏图标明暗判定）。 */
val LocalWbDark = staticCompositionLocalOf { true }

/**
 * 应用主题：**跟随系统 / 浅色 / 深色** 三态，并可叠加系统动态取色（Material You）。
 *
 * - 用 Miuix 官方 `ThemeController(ColorSchemeMode)` + `MiuixTheme(controller = ...)`；
 * - `monet=true` 时优先用 `Monet*` 模式，让全站配色跟随壁纸，色彩更丰富且天然协调
 *   （这正是「配色过于单调」的正解：不是硬塞彩色，而是把系统调色板用到该用的地方）；
 * - 语义色随明暗切换，避免深色下文字/状态色对比度不足。
 */
@Composable
fun WbTheme(mode: String, monet: Boolean = false, content: @Composable () -> Unit) {
    val normalized = mode.lowercase()
    val dark = when (normalized) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val schemeMode = when {
        monet && normalized == "light" -> ColorSchemeMode.MonetLight
        monet && normalized == "dark" -> ColorSchemeMode.MonetDark
        monet -> ColorSchemeMode.MonetSystem
        normalized == "light" -> ColorSchemeMode.Light
        normalized == "dark" -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }
    val controller = remember(schemeMode) { ThemeController(schemeMode) }
    MiuixTheme(controller = controller) {
        CompositionLocalProvider(
            LocalWbSemantic provides (if (dark) DarkSemantic else LightSemantic),
            LocalWbDark provides dark,
        ) {
            content()
        }
    }
}

/**
 * 系统栏适配（Android 官方 API，见 `WindowInsetsControllerCompat`）。
 *
 * 用户反馈的现象「浅色模式下状态栏也是浅色，看不清时间」根因是**没有人设置状态栏图标的明暗**：
 * 图标默认跟随系统/首帧的浅色，于是浅底浅字。修法就是把外观交给主题：
 *  - 深色主题 → `isAppearanceLightStatusBars = false`（浅色图标）
 *  - 浅色主题 → `true`（深色图标）
 * 导航栏同理；同时把窗口底色刷成当前 surface，避免状态栏/边缘露出系统默认底色。
 */
@Composable
fun WbSystemBars() {
    val dark = LocalWbDark.current
    val view = LocalView.current
    val ctx = LocalContext.current
    val surface = MiuixTheme.colorScheme.surface
    SideEffect {
        val window = (ctx as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        runCatching { window.decorView.setBackgroundColor(surface.toArgb()) }
    }
}

package com.wb.api.overlay

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.wb.api.Gateway
import com.wb.api.MainActivity
import com.wb.api.core.LogBus
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 网关地址浮窗（`SYSTEM_ALERT_WINDOW`）。
 *
 * 形态：一颗**液态玻璃胶囊**，显示 `127.0.0.1:<port>` 与运行状态点（浮窗只在网关运行期
 * 存在，所以点的含义就是「运行中」）；点击回到应用，拖动可换位置（位置落盘，下次沿用）。
 *
 * 为什么不用 Compose 画：overlay 窗口里的 `ComposeView` 需要宿主提供
 * `ViewTreeLifecycleOwner / SavedStateRegistryOwner / ViewModelStoreOwner`，而 Service
 * 没有这些宿主，缺一个就在 `addView` 时崩；这里只需要「一层渐变 + 一条描边 + 一行字」，
 * 自绘 View 更小、更可控，也没有重组与主题订阅的额外开销。
 *
 * 玻璃质感与主界面 `WbGlass` 同一套思路（本文件不引用 Compose，故取值内联）：
 *  - 浅色主题：淡灰玻璃（比页面暗一档）+ 深色发丝描边，避免「白叠白」被读成实心白条；
 *  - 深色主题：`#1A1A1C` 62%~70% + 白色发丝描边 + 顶部高光；
 *  - 圆角取高度的一半（胶囊）：这么小的尺度上连续曲率肉眼看不出差别，胶囊边界最稳；
 *  - **不做投影**：overlay 窗口没有背景采样，`setShadowLayer` 又必须开软件层，
 *    而投影会被窗口自身边界裁掉（只剩四个角）——花了软渲染的代价却拿不到效果。
 *
 * 位置：**水平方向最多拖出 2/3**（屏幕内始终留 1/3 可见，否则会变成「拖出去就再也抓不回来」），
 * 垂直方向保持整颗在屏内（上下贴边就够了，不需要半出屏）。
 *
 * 能出屏的**前提是 `FLAG_LAYOUT_NO_LIMITS`**（官方语义："allow window to extend outside of
 * the screen"）：没有它时 WindowManager 会把 x/y 夹回显示区域，无论我们自己的 clamp 放多宽
 * —— 1.6.8 只改了 clamp、没加这个 flag，所以表现仍是「拖不出屏」。
 *
 * 主题：玻璃取值由 [GlassPill.applyTheme] 统一重算，两条触发路径 ——
 *  - 应用内主题切换（`ui.theme`）：[refreshTheme] 由 MainActivity 的 LaunchedEffect 调；
 *  - 系统深浅色切换（`ui.theme = system`）：`Application.registerComponentCallbacks`。
 * 两条都必须触发重算：旧实现把颜色算在 init 里，切主题后浮窗要「关了再开」才变。
 *
 * 线程：WindowManager 与 View 动画都要求主线程，[show] / [hide] 内部统一 post 到主线程，
 * 调用方（Service / Activity / ViewModel）不必关心自己跑在哪个线程。
 *
 * 生命周期：由 [com.wb.api.service.GatewayService] 在网关**启动成功后**显示、
 * 停止 / 销毁时移除。权限缺失只记日志不抛异常 —— 浮窗是附加能力，绝不能影响网关本身。
 */
object AddressOverlay {

    private const val PREFS = "wb2api_overlay"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var view: GlassPill? = null
    @Volatile private var params: WindowManager.LayoutParams? = null
    @Volatile private var windowManager: WindowManager? = null

    /** 是否已获得「显示在其他应用上层」权限。 */
    fun canDraw(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /**
     * 跳系统授权页。
     *
     * 必须带 `package:` URI —— 不带的话部分 ROM 只打开总列表，用户还得自己找应用；
     * 少数 ROM 没有带包的入口（ActivityNotFoundException），故做一次无参回退。
     */
    fun requestPermission(ctx: Context) {
        val ok = runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (ok) return
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { e -> LogBus.log("[overlay] 无法打开悬浮窗授权页：${e.message}") }
    }

    /**
     * 水平夹取：允许最多拖出 **2/3**（屏幕内保留 1/3）。
     *
     * 为什么不是「完全不许出屏」：贴边时指尖会压着屏幕边缘，想再往里拖很容易变成系统
     * 返回/手势操作；留一点出屏反而更好拖。但必须留 1/3 可见 —— 全出屏就等于把浮窗弄丢了。
     * `coerceIn` 在 w >= 屏宽时 (lo > hi) 会抛，故先夹一次上界。
     */
    private fun clampX(x: Int, w: Int, screenW: Int): Int {
        val lo = -(w * 2) / 3
        val hi = (screenW - w / 3).coerceAtLeast(lo)
        return x.coerceIn(lo, hi)
    }

    /** 垂直夹取：整颗在屏内（上下不做半出屏）。 */
    private fun clampY(y: Int, h: Int, screenH: Int): Int =
        y.coerceIn(0, (screenH - h).coerceAtLeast(0))

    /**
     * 屏幕**物理**尺寸（含状态栏 / 导航栏）。
     *
     * 不能用 `resources.displayMetrics`：它是「应用可用区域」（已扣掉系统栏），而窗口加上
     * `FLAG_LAYOUT_NO_LIMITS` 后坐标系原点在**屏幕左上角**、也不再受系统栏约束 —— 两者混用
     * 会让底部边界少算一条导航栏的高度（表现为「拖到底还差一条缝」、或反过来把胶囊夹到
     * 导航栏上面去）。API 30+ 的 `currentWindowMetrics.bounds` 返回整屏，minSdk 32 可直接用。
     */
    private fun screenSize(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val b = runCatching { wm?.currentWindowMetrics?.bounds }.getOrNull()
        if (b != null && b.width() > 0 && b.height() > 0) return b.width() to b.height()
        val m = ctx.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    fun isShowing(): Boolean = view?.isAttachedToWindow == true

    /**
     * 按当前主题重算玻璃取值（应用内主题切换后由 MainActivity 调用）。
     *
     * 走主线程：`View.invalidate()` 本身线程安全，但这里还要读配置，和其他浮窗操作统一
     * 在主线程排队，避免与 show/hide 交错。
     */
    fun refreshTheme() {
        if (Looper.myLooper() == Looper.getMainLooper()) view?.applyTheme()
        else main.post { view?.applyTheme() }
    }

    /** 显示浮窗；已显示时只更新文字（换端口不必重建窗口）。 */
    fun show(ctx: Context, address: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) showOnMain(ctx, address)
        else {
            val app = ctx.applicationContext
            main.post { showOnMain(app, address) }
        }
    }

    /** 移除浮窗（淡出后真正 removeView）。 */
    fun hide() {
        if (Looper.myLooper() == Looper.getMainLooper()) hideOnMain()
        else main.post { hideOnMain() }
    }

    private fun showOnMain(ctx: Context, address: String) {
        val current = view
        // isAttachedToWindow 必须判：用户撤销「显示在其他应用上层」时系统会直接移除窗口，
        // 但单例里的 view 仍非空 —— 只看非空会命中早退分支，重新授权后浮窗**永远不再出现**。
        if (current != null && current.isAttachedToWindow) {
            current.setAddress(address)
            return
        }
        if (current != null) {
            runCatching { windowManager?.removeView(current) }
            view = null
            params = null
            windowManager = null
        }
        if (!canDraw(ctx)) {
            LogBus.log("[overlay] 未获得悬浮窗权限，跳过显示")
            return
        }
        val app = ctx.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pill = GlassPill(app, address)
        // 系统深浅色变化时重算取值（应用内主题切换走 refreshTheme，两条路径互补）。
        // 先注销再注册：`registerComponentCallbacks` 对同一实例重复注册会重复回调，
        // 而「显示→被系统移窗→再显示」这条路径会走到这里第二次（幂等写法省心）。
        runCatching { app.unregisterComponentCallbacks(configCallbacks) }
        runCatching { app.registerComponentCallbacks(configCallbacks) }
        val metrics = app.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢焦点（浮窗只展示，不接输入法）；NOT_TOUCH_MODAL 让窗口外的触摸照常落到下层；
            // **FLAG_LAYOUT_NO_LIMITS 允许窗口超出屏幕边界** —— 这是「能拖出屏 2/3」的唯一开关，
            // 少了它系统会把 x/y 夹回屏内（1.6.8 的根因，见类头注释）。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, (16 * metrics.density).roundToInt())
            y = prefs.getInt(KEY_Y, (160 * metrics.density).roundToInt())
        }
        attachTouch(pill, lp, wm, prefs)
        if (!runCatching { wm.addView(pill, lp) }.isSuccess) {
            // 回调是刚注册的：addView 失败时窗口根本不存在，留着它只会让主题变更路径
            // 继续持有并调用一个不会显示的 View（单例场景泄漏有限，但注册与注销要成对）。
            runCatching { app.unregisterComponentCallbacks(configCallbacks) }
            LogBus.log("[overlay] 添加浮窗失败（ROM 拒绝？）")
            return
        }
        view = pill
        params = lp
        windowManager = wm
        // 实测尺寸之后再把位置夹回边界：持久化的坐标可能来自别的分辨率/横屏，直接用会
        // 让胶囊「存在但整颗在屏幕外」（点不到也拖不回来）。
        pill.post { clampToBounds() }
        pill.playEnter()
        LogBus.log("[overlay] 地址浮窗已显示：$address")
    }

    private fun hideOnMain() {
        val v = view ?: return
        val wm = windowManager
        // 先清空状态：停止流程与 onDestroy 可能各调一次，动画重入会重复 removeView。
        view = null
        params = null
        windowManager = null
        runCatching { v.context.applicationContext.unregisterComponentCallbacks(configCallbacks) }
        v.animate().cancel()
        v.animate()
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(140)
            .withEndAction { runCatching { wm?.removeView(v) } }
            .start()
    }

    /** 按当前实测尺寸把已有位置夹回边界（显示后 / 配置变化后调用）。 */
    private fun clampToBounds() {
        val v = view ?: return
        val lp = params ?: return
        val wm = windowManager ?: return
        val (sw, sh) = screenSize(v.context)
        val nx = clampX(lp.x, v.width, sw)
        val ny = clampY(lp.y, v.height, sh)
        if (nx != lp.x || ny != lp.y) {
            lp.x = nx
            lp.y = ny
            runCatching { wm.updateViewLayout(v, lp) }
            // 夹取结果要**写回**：否则持久化的还是那个越界值，每次启动都要再夹一次；
            // 更糟的是「换到更小的屏幕/横屏」时，旧值会一直把胶囊压在边缘外。
            runCatching {
                v.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_X, nx).putInt(KEY_Y, ny).apply()
            }
        }
    }

    /**
     * 系统配置变化（最典型是深浅色切换、横竖屏、字号）：
     * 主题重算 + 位置按新屏幕尺寸重新夹取。
     */
    private val configCallbacks = object : android.content.ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            view?.applyTheme()
            clampToBounds()
        }

        override fun onLowMemory() {}
    }

    private fun attachTouch(
        v: View,
        lp: WindowManager.LayoutParams,
        wm: WindowManager,
        prefs: SharedPreferences,
    ) {
        val slop = 6f * v.resources.displayMetrics.density
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var downAt = 0L
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    downAt = SystemClock.uptimeMillis()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    // 超过 6dp 才算拖动：手指抖动不该把「点击」变成「位移」。
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val (sw, sh) = screenSize(v.context)
                        lp.x = clampX(startX + dx.roundToInt(), v.width, sw)
                        lp.y = clampY(startY + dy.roundToInt(), v.height, sh)
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                    } else if (SystemClock.uptimeMillis() - downAt < 400) {
                        openApp(v.context)
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun openApp(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        }.onFailure { e -> LogBus.log("[overlay] 打开应用失败：${e.message}") }
    }

    /** 浮窗本体：玻璃胶囊 + 状态点 + 等宽地址。 */
    private class GlassPill(ctx: Context, address: String) : View(ctx) {

        private val density = resources.displayMetrics.density
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f * resources.displayMetrics.scaledDensity
            isSubpixelText = true
        }
        private val path = Path()
        private val rect = RectF()

        private val padH = 14f * density
        private val padV = 10f * density
        private val dotR = 3.5f * density
        private val gap = 7f * density

        // 玻璃底色由 applyTheme() 按当前主题重算，故必须是 var（构造时只算一次 = 旧 bug）。
        private var bgTop: Int = 0
        private var bgBottom: Int = 0

        private var text = address

        init {
            contentDescription = "网关地址 $address"
            applyTheme()
        }

        /**
         * 按**当前**主题重算玻璃取值（可重复调用）。
         *
         * 必须独立成方法而不是写在 init 里：颜色原本只在构造时算一次，于是「切换主题后
         * 浮窗不跟着变、必须关了再开」—— 因为浮窗是长生命周期对象，构造时机与主题变更
         * 时机无关。重算后 invalidate 即可，无需重建窗口。
         */
        fun applyTheme() {
            if (resolveDark(context)) {
                bgTop = 0x9E1A1A1C.toInt()
                bgBottom = 0xB31A1A1C.toInt()
                edgePaint.color = 0x33FFFFFF
                highlightPaint.color = 0x1FFFFFFF
                textPaint.color = 0xF2FFFFFF.toInt()
                dotPaint.color = 0xFF34C759.toInt()
            } else {
                // 浅色：淡灰玻璃（比页面暗一档）+ 深色发丝描边，避免读成实心白条
                bgTop = 0xB8E9E9ED.toInt()
                bgBottom = 0x9CE4E4EA.toInt()
                edgePaint.color = 0x1F000000
                highlightPaint.color = 0x66FFFFFF
                textPaint.color = 0xE61C1C1E.toInt()
                dotPaint.color = 0xFF2FB457.toInt()
            }
            invalidate()
        }

        fun setAddress(address: String) {
            if (address == text) return
            text = address
            contentDescription = "网关地址 $address"
            requestLayout()
            invalidate()
        }

        fun playEnter() {
            alpha = 0f
            scaleX = 0.90f
            scaleY = 0.90f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val textW = textPaint.measureText(text)
            val fm = textPaint.fontMetrics
            val w = (padH * 2 + dotR * 2 + gap + textW).roundToInt()
            val h = (padV * 2 + (fm.descent - fm.ascent)).roundToInt()
            setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2f // 胶囊：圆角 = 高度一半，边界连续无折角
            rect.set(0f, 0f, w, h)
            path.reset()
            path.addRoundRect(rect, r, r, Path.Direction.CW)

            // 玻璃底：纵向渐变（上略透、下略实），模拟厚玻璃的密度差
            bgPaint.shader = LinearGradient(0f, 0f, 0f, h, bgTop, bgBottom, Shader.TileMode.CLAMP)
            canvas.drawPath(path, bgPaint)

            // 顶部高光：细窄的内缘亮线，玻璃感的主要来源
            canvas.save()
            canvas.clipPath(path)
            canvas.drawLine(r * 0.6f, 1f * density, w - r * 0.6f, 1f * density, highlightPaint)
            canvas.restore()

            // 发丝描边
            canvas.drawPath(path, edgePaint)

            // 状态点 + 地址
            val cy = h / 2f
            canvas.drawCircle(padH + dotR, cy, dotR, dotPaint)
            val fm = textPaint.fontMetrics
            val baseline = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(text, padH + dotR * 2 + gap, baseline, textPaint)
        }

        private fun resolveDark(ctx: Context): Boolean {
            val mode = runCatching { Gateway.store.config.ui.theme }.getOrNull() ?: "system"
            return when (mode) {
                "dark" -> true
                "light" -> false
                else -> (ctx.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }
}

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

    fun isShowing(): Boolean = view?.isAttachedToWindow == true

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
        val metrics = app.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢焦点（浮窗只展示，不接输入法）；NOT_TOUCH_MODAL 让窗口外的触摸照常落到下层。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, (16 * metrics.density).roundToInt())
            y = prefs.getInt(KEY_Y, (160 * metrics.density).roundToInt())
        }
        attachTouch(pill, lp, wm, prefs)
        if (!runCatching { wm.addView(pill, lp) }.isSuccess) {
            LogBus.log("[overlay] 添加浮窗失败（ROM 拒绝？）")
            return
        }
        view = pill
        params = lp
        windowManager = wm
        // 实测尺寸之后再把位置夹回视口：持久化的坐标可能来自别的分辨率/横屏，
        // 直接用会让胶囊「存在但整颗在屏幕外」（点不到也拖不回来）。
        pill.post {
            val maxX = (metrics.widthPixels - pill.width).coerceAtLeast(0)
            val maxY = (metrics.heightPixels - pill.height).coerceAtLeast(0)
            val nx = lp.x.coerceIn(0, maxX)
            val ny = lp.y.coerceIn(0, maxY)
            if (nx != lp.x || ny != lp.y) {
                lp.x = nx
                lp.y = ny
                runCatching { wm.updateViewLayout(pill, lp) }
            }
        }
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
        v.animate().cancel()
        v.animate()
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(140)
            .withEndAction { runCatching { wm?.removeView(v) } }
            .start()
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
                        val metrics = v.resources.displayMetrics
                        val maxX = (metrics.widthPixels - v.width).coerceAtLeast(0)
                        val maxY = (metrics.heightPixels - v.height).coerceAtLeast(0)
                        lp.x = (startX + dx.roundToInt()).coerceIn(0, maxX)
                        lp.y = (startY + dy.roundToInt()).coerceIn(0, maxY)
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

        private val bgTop: Int
        private val bgBottom: Int

        private var text = address

        init {
            if (resolveDark(ctx)) {
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
            contentDescription = "网关地址 $address"
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

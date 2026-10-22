package com.wb.api

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebSettings
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.wb.api.core.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OAuth 授权页（独立 Activity，**纯 View 的 WebView，完全不经过 Compose**）。
 *
 * 为什么要独立 Activity + 纯 View：
 * 在 Compose 的 `AndroidView` 里内嵌 WebView 时，Compose 的测量 / 重组会在输入法每次
 * KeyUp 时干扰 WebView 的焦点，导致登录页里受控 `<input>` 的光标被重置到最前 ——
 * 表现为「输入 123456 得到 654321」「光标总停在第一个字符前」。把 WebView 放进独立
 * Activity（脱离 Compose 树）后，其输入法行为与系统浏览器一致。
 *
 * 该页自带：① 顶部「返回」按钮（随时退出，不阻塞主界面）；② 底部「用系统浏览器打开」
 * 与「刷新凭证」两个圆角按钮（带涟漪反馈）；③ 登录轮询与自动入库。
 */
class LoginWebActivity : ComponentActivity() {

    private var realm = "cn"

    /** 系统深色主题。本页是纯 View 页面，不依赖 XML theme，故自行解析配色。 */
    private var isNight = false
    private var barColor = 0
    private var textMainColor = 0
    private var accentSoftColor = 0

    private var web: WebView? = null
    private var authUrl: String = ""
    @Volatile private var done = false
    private var pollJob: Job? = null

    /** 环境诊断行（显示页面实际看到的环境 + 当前页 URL，排查「跳去了哪一页」的一手线索）。 */
    private var envView: TextView? = null
    private var envText: String = ""

    /** 微信授权引导行：页面要跳 open.weixin.qq.com 时亮起（见 shouldOverrideUrlLoading）。 */
    private var wxHintView: TextView? = null

    companion object {
        const val EXTRA_REALM = "realm"
        private const val ACCENT = "#0A59F7"
        private const val ACCENT_SOFT = "#E7EFFE"

        /**
         * 桌面 Chrome UA。
         *
         * ⚠️ **重要更正（1.5.5，已抓取真实前端代码取证）**：改 UA **并不能**让微信登录出二维码。
         *
         * 真实流程：`www.codebuddy.cn` 登录页点「微信登录」→ 请求
         * `/auth/realms/copilot/console/auth/wechat-url` → 服务端签发一条
         * `open.weixin.qq.com/connect/qrconnect?…` 链接 → 前端 `location.href` 跳过去。
         * 而**微信侧只对「微信客户端内」的请求放行**：微信自己的授权页源码里就是
         * `var isWeixin = ua.indexOf('micromessenger') != -1;` —— 非微信环境一律渲染
         * 「请在手机打开网页链接 / 请在微信客户端打开链接」这类提示页。
         *
         * 也就是说：**这里跟 PC / 移动、跟视口宽度、跟任何指纹伪装都无关**，
         * 在 WebView 里（没有微信登录通道）永远出不了二维码。
         *
         * 因此保留桌面 UA 只是为了让登录页渲染桌面版布局（不影响微信流程本身）；
         * 微信授权改为「拦截跳转 → 复制链接 → 到微信里打开」，见 [shouldOverrideUrlLoading]。
         */
        private const val PC_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"
        /**
         * 匹配页面自带的 viewport meta。
         *
         * 要求「name 属性出现在本标签内」，避免误伤正文里恰好含 viewport 字样的其它标签。
         */
        private val VIEWPORT_META_RE =
            Regex("<meta[^>]*name\\s*=\\s*[\"']?viewport[\"']?[^>]*>", RegexOption.IGNORE_CASE)

        /**
         * 唯一允许存在的 viewport：1280 宽（桌面布局宽度）。
         *
         * 刻意**不带 initial-scale** —— 留空才能让 `loadWithOverviewMode` 真正生效，
         * 把 1280 宽的桌面页面整体缩放到手机屏宽之内（用户看得全，而 innerWidth 仍是 1280）。
         */
        private const val VIEWPORT_META_1280 = "<meta name=\"viewport\" content=\"width=1280\">"

        private const val BAR_BG = "#F2F3F5"
        private const val TEXT_MAIN = "#222222"

        // 深色主题配色（状态栏 / 顶栏 / 底栏 / 正文）。
        private const val ACCENT_SOFT_DARK = "#1B2A46"
        private const val BAR_BG_DARK = "#1C1C1E"
        private const val PAGE_BG_DARK = "#121212"
        private const val TEXT_MAIN_DARK = "#EDEDED"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        realm = intent.getStringExtra(EXTRA_REALM)?.takeIf { it.isNotBlank() } ?: "cn"

        // ---------- 深色主题解析（跟随系统）----------
        isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        barColor = if (isNight) Color.parseColor(BAR_BG_DARK) else Color.parseColor(BAR_BG)
        textMainColor = if (isNight) Color.parseColor(TEXT_MAIN_DARK) else Color.parseColor(TEXT_MAIN)
        accentSoftColor = if (isNight) Color.parseColor(ACCENT_SOFT_DARK) else Color.parseColor(ACCENT_SOFT)
        val pageBg = if (isNight) Color.parseColor(PAGE_BG_DARK) else Color.WHITE

        // 状态栏 / 导航栏图标亮度取反：深色底配浅色图标，浅色底配深色图标。
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }

        // ---------- 清理上一次会话的网页数据（修复「无法登录第二个账号」）----------
        //
        // 现象：登录过一个账号后，再次「添加账号」会**直接导入上次那个账号的凭证**，走不到新账号授权。
        // 原因：Cookie / WebStorage / 缓存都是**进程级共享**的 —— 第二次进入本页时页面带着上一次
        // 的登录态，于是授权轮询一开始就能取到「上一次那个账号」的凭证。
        // 本页的使命是「完成一次全新的授权」，因此每次进入都先清干净再开始。
        // 副作用可控：本应用只有这一个 WebView，不存在需要保留的网页数据。
        runCatching {
            val cm = android.webkit.CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.removeSessionCookies(null)
            cm.flush()
        }
        runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBg)
        }

        // ---------- 顶部栏：返回 + 标题 ----------
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(barColor)
        }
        val back = TextView(this).apply {
            text = "‹ 返回"
            textSize = 17f
            setTextColor(Color.parseColor(ACCENT))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            background = ripple(GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setColor(Color.TRANSPARENT)
            })
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "登录授权 · ${if (realm == "global") "Global" else "CN"}"
            textSize = 16f
            setTextColor(textMainColor)
        }
        topBar.addView(back)
        topBar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---------- WebView ----------
        // ---------- 桌面化：让 WebView 对页面表现为「真正的 PC 浏览器」 ----------
        //
        // 背景（用户反馈 + 实证）：只改 UA 不够。授权页在「UA 说是 PC、其余特征全是手机」的
        // 矛盾环境下会落到兜底提示「请在手机打开网页链接」，而不是渲染扫码二维码。
        // 依据：① 上游 Go 面板用的是**同一个** authUrl（panel/login.go 也是
        // `?platform=CLI`），在真实 PC 浏览器里能正常出码；② 页面按视口宽度分流
        // （≤1070px 判为移动端）；③ WebView 默认暴露 navigator.platform="Linux armv8l"、
        // maxTouchPoints>0，一眼可辨为移动设备。
        //
        // 因此这里同时抹平三处：UA（已设）、**视口宽度**（注入 width=1280 的 viewport）、
        // **navigator 桌面特征**。注入必须发生在页面任何脚本之前，所以走
        // shouldInterceptRequest 改写主文档（WebView 默认渲染管线里没有「文档开始前注入」的
        // 无依赖入口；androidx.webkit 的 addDocumentStartJavaScript 需要新依赖，而构建是离线的）。
        val cookieJar = object : okhttp3.CookieJar {
            private val store = java.util.concurrent.ConcurrentHashMap<String, MutableList<okhttp3.Cookie>>()
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                val list = store.getOrPut(url.host) { mutableListOf() }
                cookies.forEach { c -> list.removeAll { it.name == c.name }; list.add(c) }
            }
            override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
                store[url.host]?.filter { it.matches(url) } ?: emptyList()
        }
        val probe = okhttp3.OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .followRedirects(true)
            .build()

        val wv = WebView(this).apply {
            // 桌面端 UA（见 PC_UA 的说明）：不设置的话 WebView 会报 Android 移动端 UA，
            // 授权页就不提供微信扫码登录了。
            settings.userAgentString = PC_UA
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // 授权流程依赖第三方 Cookie（登录态在各域之间传递），默认关闭会直接断链。
            runCatching { android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true) }
            // 深色主题下让网页内容一并变暗（API 33+ 用官方开关；29~32 退化用 forceDark）。
            if (Build.VERSION.SDK_INT >= 33) {
                settings.isAlgorithmicDarkeningAllowed = isNight
            } else {
                @Suppress("DEPRECATION")
                settings.forceDark = if (isNight) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
            }
            isFocusable = true
            isFocusableInTouchMode = true
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): android.webkit.WebResourceResponse? {
                    // 只改写**主文档的 GET**：子资源（图片/JS/CSS/接口）原样交给 WebView，
                    // 既不拖慢页面，也避免影响页面自身的 XHR / SSE 行为。
                    if (!request.isForMainFrame || request.method != "GET") return null
                    return try {
                        val b = okhttp3.Request.Builder().url(request.url.toString())
                        request.requestHeaders.forEach { (k, v) -> if (!k.startsWith(":")) b.header(k, v) }
                        b.header("User-Agent", PC_UA)
                        probe.newCall(b.build()).execute().use { resp ->
                            val body = resp.body ?: return null
                            val charset = body.contentType()?.charset(java.nio.charset.Charset.forName("UTF-8"))
                                ?: java.nio.charset.Charset.forName("UTF-8")
                            val isHtml = (resp.header("Content-Type") ?: "").contains("html", ignoreCase = true)
                            val bytes = body.byteStream().readBytes()
                            val payload = if (isHtml) injectDesktopHint(bytes.toString(charset)).toByteArray(charset) else bytes
                            // 把上游下发的 Cookie 同步给 WebView，供页面内后续请求使用。
                            resp.headers("Set-Cookie").forEach {
                                runCatching { android.webkit.CookieManager.getInstance().setCookie(request.url.toString(), it) }
                            }
                            val headers = HashMap<String, String>()
                            resp.headers.toMultimap().forEach { (k, vs) ->
                                val lk = k.lowercase()
                                // 必须剔除：长度已变（注入后）、编码已被 OkHttp 解压、
                                // 分块传输由 WebResourceResponse 自行处理。
                                if (lk != "content-length" && lk != "content-encoding" && lk != "transfer-encoding") {
                                    headers[k] = vs.joinToString(", ")
                                }
                            }
                            android.webkit.WebResourceResponse(
                                resp.header("Content-Type")?.substringBefore(';') ?: "text/html",
                                charset.name(), resp.code, resp.message, headers,
                                java.io.ByteArrayInputStream(payload),
                            )
                        }
                    } catch (_: Throwable) {
                        // 任何异常都回退到 WebView 默认加载：绝不能因为「注入」把登录页搞成白屏。
                        null
                    }
                }

                /**
                 * 拦截「跳去微信授权页」这一步。
                 *
                 * 微信授权**只能在微信客户端内完成**（见 PC_UA 的取证说明），而 WebView 里没有
                 * 微信的登录通道；继续加载只会把用户带到一个「请在手机/微信打开」的错误页。
                 * 所以这里**不加载**，改为把这条由服务端签发、**自带 state** 的链接交给用户：
                 * 复制到微信里打开即可完成授权。授权一旦完成，本页的 deviceAuthPoll 轮询照样
                 * 能取到凭证 —— 「在哪里授权」与「向谁取凭证」本来就是解耦的。
                 */
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): Boolean {
                    val u = request.url?.toString().orEmpty()
                    // weixin:// 是「唤起微信 App」的自定义 scheme（WebView 内必然无 handler）。
                    if (u.contains("open.weixin.qq.com") || u.startsWith("weixin://")) {
                        onWechatAuthUrl(u)
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    // 运行时兜底：页面脚本可能又把 viewport 写回去（viewportFixJs 里另有 MutationObserver）。
                    runCatching {
                        view.evaluateJavascript("try{window.__wbVfix&&window.__wbVfix();}catch(e){}", null)
                    }
                    // 延迟一拍再读环境：viewport 变更是**异步重排**，立即读会拿到旧值
                    // （表现为「明明改了却还是 394」，反而误导排查方向）。
                    view.postDelayed({ readEnvironment(view) }, 600)
                }
            }
        }

        web = wv
        root.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---------- 底部操作栏 ----------
        //
        // 底部动作区。**1.5.5 起的关键认知**（已抓取真实前端代码取证，见 PC_UA）：
        //  www.codebuddy.cn 的「微信登录」必须在**微信客户端内**才能走通 —— 它拿到一条
        //  `open.weixin.qq.com/connect/qrconnect…` 链接，而微信侧只在 UA 含 micromessenger 时
        //  放行；在 WebView / PC 浏览器里无论怎么伪装，微信都会渲染「请在手机/微信打开」的
        //  提示页（不存在可扫的二维码）。所以这里不再假装能出码，而是把
        //  「复制链接 → 到微信打开」做成主路径：
        //  - **复制授权链接**：在微信里打开登录页，微信内点「微信登录」即可完成授权；
        //  - 用系统浏览器打开：本机尝试（手机/电脑浏览器里同样完不成微信授权，仅便于查看）；
        //  - 刷新凭证：重新发起一次授权（换新的 state）。
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(barColor)
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val copyBtn = pillButton("复制授权链接", primary = true) { copyAuthUrl() }
        val openBtn = pillButton("用系统浏览器打开", primary = false) { openInBrowser() }
        val refreshBtn = pillButton("刷新凭证", primary = false) { startFlow() }
        // 微信授权引导行：平时隐藏，一旦页面要跳 open.weixin.qq.com 就亮起，
        // 给出「复制 → 打开微信 → 粘贴」的明确步骤（比让用户撞微信的错误页有用得多）。
        val wxHint = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor(ACCENT))
            setPadding(dp(2), dp(8), dp(2), 0)
            visibility = android.view.View.GONE
        }
        wxHintView = wxHint
        row1.addView(copyBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) })
        row1.addView(openBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // 环境诊断行：显示**页面实际看到的环境**（UA / 平台 / 触摸 / 视口 / 屏幕）。
        // 授权页正是靠这些值分流，一旦再次出现「请在手机打开网页链接」，这行就是唯一的一手线索
        // （点一下即可复制），不必再靠猜。
        val env = TextView(this).apply {
            textSize = 10f
            setTextColor(textMainColor)
            alpha = 0.62f
            setPadding(dp(2), dp(8), dp(2), 0)
            isClickable = true
            setOnClickListener { copyText(envText, "环境信息已复制") }
        }
        envView = env
        bottom.addView(row1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottom.addView(wxHint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottom.addView(env, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottom.addView(
            refreshBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
        )
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)

        // Android 15+ 强制 edge-to-edge：内容延伸进系统栏区域。这里把系统栏 inset 分别补给
        // 顶栏 / 底栏（而不是整体根布局），使顶栏与底栏底色**铺满状态栏与导航栏区域**——
        // 深色主题下状态栏即为深底 + 浅色图标，不会再出现「白底白字看不清」。
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            topBar.setPadding(dp(6) + b.left, dp(4) + b.top, dp(6) + b.right, dp(4))
            bottom.setPadding(dp(12) + b.left, dp(10), dp(12) + b.right, dp(10) + b.bottom)
            insets
        }

        startFlow()
    }

    /**
     * 复制授权链接到系统剪贴板。
     *
     * 该链接自带 state，在**任何设备**上完成授权后本页的轮询都能取到凭证。
     * **关键在"用哪个 App 打开"**：微信登录必须在**微信里**完成 —— 用手机浏览器或电脑浏览器
     * 打开都到不了微信授权那一环（已用真实前端代码取证，见 PC_UA）。
     */
    private fun copyAuthUrl() {
        if (authUrl.isBlank()) {
            toast("尚未获取到授权链接，请点「刷新凭证」")
            return
        }
        copyText(authUrl, "已复制：请打开「微信」→ 粘贴到任意聊天窗口 → 点开链接，在微信里完成授权")
    }

    /**
     * 页面即将跳去微信授权页（`open.weixin.qq.com/...`，或 `weixin://` 唤起）时的处理。
     *
     * 不加载它：微信只对「微信客户端内」的请求放行，其余环境一律落到
     * 「请在手机/微信打开」的提示页（这正是用户截图那句话的来源，与 UA、视口、指纹无关）。
     * 改为把这条**由服务端签发、自带 state** 的链接交给用户复制到微信里打开；
     * 授权完成后本页轮询照样取到凭证。
     */
    private fun onWechatAuthUrl(url: String) {
        if (url.isBlank()) return
        runOnUiThread {
            copyText(url, "微信授权链接已复制：请打开「微信」粘贴并打开，完成授权后本页自动获取凭证")
            wxHintView?.apply {
                visibility = android.view.View.VISIBLE
                text = "微信授权只能在微信客户端内完成。链接已复制：" +
                    "打开「微信」→ 粘贴到任意聊天窗口 → 点开 → 授权；成功后本页会自动入库。" +
                    "（也可以直接用手机号 / 邮箱 / SSO 在本页登录）"
            }
        }
    }

    /** 复制任意文本到系统剪贴板（本页是纯 View 页面，用系统 ClipboardManager 而非 Compose 的）。 */
    private fun copyText(text: String, tip: String) {
        if (text.isBlank()) return
        runCatching {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("wb2api", text))
        }
        toast(tip)
    }

    /**
     * 读回页面**实际看到**的环境，显示在底部诊断行 —— 这是判断「伪装是否生效」的一手证据。
     *
     * 成功的样子是 `iw` 达到 1280（视口被钉成桌面宽度）、`plat=Win32`、`touch=0`、`sw=1920`。
     * 一旦再次出现「请在手机打开网页链接」，这一行就能直接指出是哪一维没达成，不必再猜。
     */
    private fun readEnvironment(view: WebView) {
        runCatching {
            view.evaluateJavascript(
                "(function(){try{return JSON.stringify({ua:navigator.userAgent,plat:navigator.platform," +
                    "touch:navigator.maxTouchPoints,iw:window.innerWidth,ih:window.innerHeight," +
                    "sw:screen.width,sh:screen.height,dpr:window.devicePixelRatio});}catch(e){return '';}})()",
            ) { raw ->
                val txt = raw?.trim('"')?.replace("\\\"", "\"")?.replace("\\/", "/").orEmpty()
                runOnUiThread {
                    // 同时记录**当前页面 URL**：排查「到底跳去了哪一页」时这是最直接的线索
                    // （点一下诊断行即可把 URL + 环境一起复制走）。1.5.4 那条「视口未达桌面宽度」
                    // 告警已删除 —— 取证表明视口宽度与微信登录能否成功无关，留着只会误导排查方向。
                    val u = runCatching { view.url.orEmpty() }.getOrDefault("")
                    val shortU = runCatching {
                        java.net.URI(u).let { uri -> (uri.host ?: "") + (uri.path ?: "") }
                    }.getOrDefault(u)
                    envText = if (u.isBlank()) txt else txt + "\nURL: " + u
                    envView?.text =
                        if (txt.isBlank()) "" else "环境：" + txt + "\n页面：" + shortU + "（点击复制全部）"
                }
            }
        }
    }

    /** 发起 / 重新发起一次授权流程（获取链接 → 加载 → 轮询凭证）。 */
    private fun startFlow() {
        pollJob?.cancel()
        done = false
        pollJob = lifecycleScope.launch { runFlow() }
    }

    private suspend fun runFlow() {
        val da = runCatching { Gateway.upstream.deviceAuthStart(realm) }.getOrNull()
        val url = da?.authUrl.orEmpty()
        if (da == null || url.isBlank()) {
            toast("未获取到授权链接，请点「刷新凭证」重试")
            return
        }
        authUrl = url
        web?.loadUrl(url)

        repeat(160) {
            delay(2500)
            if (done) return
            val entry = runCatching { Gateway.upstream.deviceAuthPoll(realm, da.state) }.getOrNull()
            if (entry == null) return@repeat
            withContext(Dispatchers.IO) {
                Gateway.store.saveAuth(entry)
                Gateway.pool.reload()
                Gateway.pool.revive(entry.uid)
                // 企业版无个人成长体系：不发起注定被拒的签到（上游 400 code 10001）。
                if (realm != "global" && !entry.isEnterprise) {
                    runCatching { Gateway.upstream.checkin(entry) }
                        .onSuccess { Gateway.pool.markCheckin(entry.uid) }
                }
                val soonSec = com.wb.api.core.Durations.parse(Gateway.store.config.pool.expiring_soon, 168 * 3600L)
                runCatching { Gateway.upstream.balanceDetailed(entry, soonSec) }
                    .onSuccess { b ->
                        if (b.known) Gateway.pool.setCredits(entry.uid, b.remain, b.earliestAt, b.earliestRemaining)
                    }
                Gateway.pool.reload()
                LogBus.log("[sys] 新账号已加入账号池 uid=${entry.uid} realm=$realm")
            }
            toast("账号 ${entry.nickname.ifBlank { entry.uid.take(8) }} 已添加并入池")
            finish()
            return
        }
        if (!done) toast("授权超时，可点「刷新凭证」重试，或返回后重新发起")
    }

    /**
     * 桌面特征注入片段：紧随 `<head>` 之后插入，保证**先于页面任何脚本**生效。
     *
     * - `viewport width=1280`：把 CSS 视口从手机宽度（≈400px）或 WebView 默认的 980px
     *   抬到桌面宽度 —— 页面按 1070px 分界判断 PC/移动，980px 会在分界之下被当成移动端。
     * - `navigator.platform / maxTouchPoints / vendor`：WebView 默认值（Linux armv8l、>0、
     *   "Google Inc."）会直接暴露「这是 Android 设备」，与 PC UA 自相矛盾。
     * - `userAgentData`：Chrome 90+ 的 Client Hints，WebView 上会带出 Android 平台，
     *   这里一并屏蔽（设为 undefined 比返回一个 mobile:true 的对象更安全）。
     */
    /**
     * 视口修复脚本 —— **运行时兜底**（第一道防线在 [injectDesktopHint] 的静态改写）。
     *
     * 权威依据：Android 官方《Migrating to WebView in Android 4.4》明确
     * 「only the last viewport is used and all others are ignored」——现代 WebView 对多个
     * viewport meta **不再合并，只取 DOM 里的最后一条**（Blink `viewport_data.h` 的
     * `ShouldOverrideLegacyDescription` 用 `>=` 比较，后写者胜）。
     *
     * 所以「往 head 开头插一条 width=1280」注定无效：页面自带的 width=device-width 在它之后，
     * 会直接覆盖它。这正是实测 `innerWidth=394`（而不是 1280）的根因。
     *
     * 本函数做两件事：① 收敛为「唯一且最后一条」的 1280 宽 viewport；
     * ② 用 MutationObserver 兜住页面脚本**动态**写入的 viewport（静态改写覆盖不到的路径）。
     * 注意 Chromium 支持运行时改 viewport 并**重算重排**（Blink `html_meta_element.cc`）。
     */
    private val viewportFixJs: String = buildString {
        append("function vfix(){try{")
        append("var ms=document.querySelectorAll('meta[name=\"viewport\"]'),keep=null,i,m,c;")
        append("for(i=0;i<ms.length;i++){m=ms[i];c=m.getAttribute('content')||'';")
        append("if(/width\\s*=\\s*1280/.test(c)){if(keep){m.parentNode&&m.parentNode.removeChild(m);}else{keep=m;}}")
        append("else{m.parentNode&&m.parentNode.removeChild(m);}}")
        append("if(!keep&&document.head){m=document.createElement('meta');m.setAttribute('name','viewport');")
        append("m.setAttribute('content','width=1280');document.head.appendChild(m);}}catch(e){}}")
        append("vfix();window.__wbVfix=vfix;")
        append("try{if(window.MutationObserver&&document.head){var vn=0;")
        append("new MutationObserver(function(){if(vn<200){vn++;vfix();}}).observe(document.head,")
        append("{childList:true,subtree:true,attributes:true,attributeFilter:['content','name']});}}catch(e){}")
    }

    private val desktopHintScript: String = buildString {
        append("<script>(function(){")
        append("function d(o,p,v){try{Object.defineProperty(o,p,{get:function(){return v;},configurable:true});}catch(e){}}")
        append("d(navigator,'platform','Win32');")
        append("d(navigator,'maxTouchPoints',0);")
        append("d(navigator,'vendor','Google Inc.');")
        append("d(navigator,'hardwareConcurrency',8);")
        append("try{Object.defineProperty(navigator,'userAgentData',{get:function(){return undefined;},configurable:true});}catch(e){}")
        append("d(screen,'width',1920);d(screen,'height',1080);d(screen,'availWidth',1920);d(screen,'availHeight',1040);")
        append("d(screen,'colorDepth',24);d(screen,'pixelDepth',24);")
        append("d(window,'devicePixelRatio',1);d(window,'outerWidth',1920);d(window,'outerHeight',1080);")
        append("try{delete window.ontouchstart;}catch(e){try{Object.defineProperty(window,'ontouchstart',{value:undefined,configurable:true});}catch(e2){}}")
        // PC Chrome 才有的 window.chrome —— 部分站点用它做「是不是真 Chrome」的一票否决。
        append("try{if(!window.chrome){window.chrome={runtime:{},app:{},csi:function(){},loadTimes:function(){}};}}catch(e){}")
        // WebGL 渲染器串里带着 Adreno / Mali 之类的移动 GPU 型号，同样一眼看穿是手机。
        append("try{var gp=WebGLRenderingContext.prototype.getParameter;WebGLRenderingContext.prototype.getParameter=function(p){")
        append("if(p===37445){return 'Google Inc. (NVIDIA)';}")
        append("if(p===37446){return 'ANGLE (NVIDIA, NVIDIA GeForce GTX 1080 Direct3D11 vs_5_0 ps_5_0, D3D11)';}")
        append("return gp.apply(this,arguments);};}catch(e){}")
        append(viewportFixJs)
        append("})();<\u002fscript>")
    }

    /**
     * 静态改写主文档（**第一道防线，也是决定性的一道**）：
     *
     * 1. 删掉页面自带的**全部** `<meta name="viewport">`。现代 WebView 只认最后一条 viewport，
     *    留着页面那条就会覆盖我们的（实测 innerWidth 停在 394，页面因此判为移动端）；
     * 2. 在 `<head>` 开头插入 [desktopHintScript] —— navigator 伪装必须**先于页面任何脚本**执行，
     *    否则页面自己的特征检测已经跑完了；
     * 3. 在 `</head>` 前插入**唯一**的 1280 宽 viewport，让它成为 DOM 里最后一条 viewport。
     *    配合 `settings.useWideViewPort = true`，CSS 视口（= `window.innerWidth`）即稳定为 1280
     *    —— 授权页按 1070px 分界判 PC / 移动，达到 1280 才不会被当成移动端。
     */
    private fun injectDesktopHint(html: String): String {
        val cleaned = VIEWPORT_META_RE.replace(html, "")
        val withScript = insertAfterHeadOpen(cleaned, desktopHintScript)
        val lower = withScript.lowercase()
        val end = lower.lastIndexOf("</head>")
        return if (end >= 0) {
            withScript.substring(0, end) + VIEWPORT_META_1280 + withScript.substring(end)
        } else {
            // 没有 </head> 的极端页面：追加到末尾，同样会成为最后一条 viewport。
            withScript + VIEWPORT_META_1280
        }
    }

    /** 把片段插到 `<head ...>` 之后；没有 head 就退到 `<html ...>` 之后，再退到最前。 */
    private fun insertAfterHeadOpen(html: String, snippet: String): String {
        val lower = html.lowercase()
        for (tag in listOf("<head", "<html")) {
            val i = lower.indexOf(tag)
            if (i < 0) continue
            val close = html.indexOf('>', i)
            if (close > 0) return html.substring(0, close + 1) + snippet + html.substring(close + 1)
        }
        return snippet + html
    }

    private fun openInBrowser() {
        if (authUrl.isBlank()) {
            toast("尚未获取到授权链接，请点「刷新凭证」")
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast("未找到可用浏览器") }
    }

    override fun onDestroy() {
        done = true
        pollJob?.cancel()
        web?.let { w ->
            runCatching { (w.parent as? ViewGroup)?.removeView(w) }
            runCatching { w.stopLoading() }
            runCatching { w.destroy() }
        }
        web = null
        super.onDestroy()
    }

    /** 圆角按钮（带涟漪反馈）：primary=true 用实心主色，否则用浅色底。 */
    private fun pillButton(text: String, primary: Boolean, onClick: () -> Unit): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(11), dp(16), dp(11))
            setTextColor(if (primary) Color.WHITE else Color.parseColor(ACCENT))
            isClickable = true
            background = ripple(GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (primary) Color.parseColor(ACCENT) else accentSoftColor)
            })
            setOnClickListener { onClick() }
        }
        return tv
    }

    private fun ripple(bg: GradientDrawable): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33000000")), bg, null)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

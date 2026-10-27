# wb2api — Android 原生移植版（基于 workbuddy2api-panel）

把上游 Go 网关 `workbuddy2api-panel`（腾讯 CodeBuddy 账号 → OpenAI 兼容反向代理）
**全部重构**为原生 Android 应用：

- 语言：**Kotlin**（非套壳、非 HTML UI）
- UI：**Jetpack Compose + Miuix 0.9.4**（`top.yukonga.miuix.kmp`）：`Scaffold` / `SmallTopAppBar` / `NavigationBar`(dock) /
  `Card` / `SmallTitle` / `TabRow` / `Switch` / `TextField` / `SliderPreference` / `SwitchPreference` /
  `OverlayDialog` / `OverlayBottomSheet` / `SnackbarHost` —— 全部为 Miuix 原生组件，含原生转场动画与按压反馈
- 传输：**内嵌原生 HTTP 服务器**（自研 `ServerSocket` + 线程池，支持 SSE 分块转发）
- 上游：**OkHttp** 直连官方桌面端 endpoint（CN 主域 `www.workbuddy.cn`，`codebuddy.cn` / `copilot.tencent.com` 兜底），Kotlin 重写请求改写与错误分类
- 并发：**Kotlin 协程**；后台：**前台 Service**（`dataSync`）常驻

## 变更（1.6.8 / versionCode 47）— 悬浮窗可拖出屏 + 主题实时跟随、上游 10-27 跟进、审计修复

### 1. 悬浮窗：拖动边界与主题跟随

| 项 | 1.6.7 | 1.6.8 |
|---|---|---|
| 拖动边界 | 完全不许出屏（`0..屏宽−宽`） | **水平最多拖出 2/3**，屏幕内始终留 **1/3** 可见；垂直方向仍整颗在屏内 |
| 主题 | 玻璃颜色在构造时算一次 → 切换主题后**必须关了再开**才变 | 抽出 `applyTheme()` 可重算：应用内切换 + 系统深浅色**实时跟随** |

为什么允许出屏：贴边时指尖压在屏幕边缘，再往里拖很容易被系统手势吃掉（尤其左右边缘的返回手势）；
留一点出屏反而更好抓。但**绝不能全出屏**——那等于把浮窗弄丢了，而浮窗没有「找回」入口。

为什么之前不跟随主题：`GlassPill` 是个长生命周期对象，颜色写在 `init` 里算一次，而主题变更
发生在它构造之后 —— 与「关开就变」的表现完全吻合。现在颜色由 `applyTheme()` 统一重算，
两条触发路径都是必要的：

- **应用内主题**（浅色/深色/跟随系统）：`MainActivity` 的 `LaunchedEffect(cfg.ui.theme)` → `AddressOverlay.refreshTheme()`；
- **系统深浅色**（`ui.theme = system` 时）：`Application.registerComponentCallbacks` 的 `onConfigurationChanged`，同时按新屏幕尺寸重新夹取位置。

### 2. 上游 10-24 → 10-27 移植（37 个提交）

| 上游提交 | 内容 | 本端落地 |
|---|---|---|
| `741cf5971` | CN 对话主域改回官方**桌面端** endpoint `www.workbuddy.cn` | `CHAT_BASE_CN` 改指 workbuddy.cn；`copilot.tencent.com`（官方 NetworkFailover 备选）降为**末位兜底**，仍保留三档候选 |
| `8550aac3d` | 首帧否决窗口补上 HTTP 200 内嵌 `{code,msg}` 失败帧 | 1.6.6 已实现（`embeddedErrorPayload` + 首帧否决），本轮核对无回归 |
| `0ebd6e70c` + `5c..` | 6020「queue.waiting」与 6021 同族 | 新增 `ErrKind.UPSTREAM_BUSY`：任意状态码认 `"code":6020/6021`，**不罚号、不换号**（排的是渠道队列，换号无用），503 透传 + `Retry-After: 3` |
| `dc75c04e2` | `GET /v1/models` 兼容 Anthropic 的 `x-api-key` | `auth(allowApiKey=true)`：Bearer 优先、空则取 `x-api-key`；该端点是双协议共用，此前「同一份 key 能调 `/v1/messages` 却列不出模型」 |
| `handler.go`（10-26） | 全池模型锁「清一色用量配额」→ 429 + 上游业务码 + **不发 Retry-After** | `ModelBlockStatus.reasons` 逐号收集锁因 → `modelBlockAllQuota()`；命中则 `type=insufficient_quota` + `code=6004/6000`（**数字**，客户端按数字码判可重试性），`message` 用上游原文，hint 带重置时刻 |
| `verify.go` | `SessionDeadButUsable`：refresh 判死但 AT 仍有效 → 继续用 | `ensureFreshToken` 刷新失败只按原凭证继续（不罚号）；401 路径的求证仍按三态处置 |
| `244d4e65d` | 官方 CLI 2.176.0 | `DEFAULT_CLI_VERSION` / `UA_DESKTOP` → 2.176.0 |
| `5367e78dc` | **下线官方补偿**（删除 `compensation.go`） | 删除 `claimCompensation()` 与端点常量：继续打只会每轮多一次必然失败的请求，日志还被误导性错误行污染 |
| `394c74976` | 会话 `touch` 改 CAS | 本端 `stickyBind/Unbind` 是显式操作、无「快路径 RUnlock→写锁」窗口，不适用 |
| `7d5cbf438` | 池 12153 中间计数/清零落盘 | 本端没有「连续 N 次才禁用」的计数机制（`AccountState` 无该字段），不适用 |

`models.allowed` 白名单（issue #68）、`/v1/embeddings`（实验）、`/v1/capacity`、Web 面板、请求事件日志大屏、
面板防重入与短缓存、更新器健壮性：**不移植** —— 前两项是面向多租户/桌面场景的新能力，
后几项是上游 Web 面板自身的基础设施，与 Android 单机形态无关。

### 3. 审计修复

本轮做了一次全量只读审计（并发/锁、资源泄漏、逻辑正确性、状态一致性、注释与实现是否相符），
逐条核验后落地以下几项：

| 等级 | 位置 | 问题 | 处置 |
|---|---|---|---|
| **P0** | `HttpServer` | 客户端 socket **只有读超时**，Java 也没有 `SO_SNDTIMEO`：切网/对端被 kill 且不发 RST 时，`out.flush()` 会阻塞到内核重传耗尽（分钟级）；32 个工作线程被钉满后新连接全 503 —— 用户侧就是「网关假死」 | 新增 `WriteGuard`：`Res` 的每个写出口统一 `begin/end` 计时，看门狗线程每 5s 扫一次，**单次写阻塞 > 90s** 就关掉该 socket（只影响真正卡住的那条连接，长 TTFB 的空闲不受影响） |
| **P0** | `Store.appendUsage` / `readUsage` | 用量归档**只追加永不裁剪**，而读取是 `readLines()` 全量反序列化 —— 常驻数月即确定性 OOM/ANR（`requests.jsonl` 早就做了流式裁剪，这个文件当年漏了） | 加 8 MB 上限 + 字节级流式裁剪（保留 80%，最旧优先丢）；每次追加后一次 `length()`（stat，O(1)） | 
| **P0** | `ChatHandler.ensureFreshToken` | 主动续期**没走** 401 求证那把 uid 单飞锁：token 到期瞬间，`max_in_flight` 个并发请求同时拿同一份旧 RT 去刷 → 上游轮换 RT 后后到者 4xx → `proveSession` 判 DEAD → **健康号被永久禁用**（1.6.7 修掉的误杀从新入口复发） | 与 `proveSession` 共用 `sessionLocks`，锁内**二次读最新凭证**：别人刚刷过就直接用新的，不再重复打 refresh |
| **P1** | `Gateway` / 通知 / 浮窗 / 面板 | 改监听端口不会重新 bind（`ServerSocket` 只在启动时 bind），但 UI/通知/浮窗立刻按**新**端口显示 —— 用户拿到一个没人在听的地址 | 新增 `boundPort`（0=未监听）与 `displayPort()`：有监听用实际端口、否则用配置端口；所有展示点统一走它；保存时若端口变了，toast 明确说「重启网关后生效」 |
| **P1** | `Gateway.stopServer` | 池状态落盘有 1s 节流，唯一的周期 `flush()` 由 Scheduler 提供、而它在 `stopServer` **之前**就停了 —— 「正常停止」也会丢刚发生的冷却/禁用/计数 | `stopServer()` 内补 `pool.flush()`（此时未持 Store 锁，锁序安全） |
| **P2** | `StreakService` | 注释与实际不符（「礼包 / 补偿」） | 随补偿下线一并修正 |

审计中**核验为「无问题」**的高风险项（避免下轮重复研究）：Store↔Pool 锁没有 AB-BA（1.6.7 的修复无回归）、
`resp.body!!` 已全部收敛为可分类异常、流式/非流式 reader 都有关闭、线程池随 start/stop 创建销毁、
`ConstantTimeEquals` 用 SHA-256 摘要比较、`loadState` 与 `stateOf` 的 `@Volatile` 已覆盖启动期竞态。

### 4. 其它

- 版本：`1.6.8 / versionCode 47`（覆盖安装，签名不变）
- 文档头部的上游域名说明同步为 `www.workbuddy.cn`（主域）

## 变更（1.6.7 / versionCode 46）— 地址悬浮窗 + 上游 10-24 修复移植 + 审计加固

### 1. 网关地址悬浮窗（新功能）

启动网关后，在**其它应用上层**显示一颗液态玻璃胶囊，内容是 `127.0.0.1:<端口>`（端口取实际
监听值，不是写死）。形态与交互：

| 项 | 实现 |
|---|---|
| 窗口 | `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE`（只展示、不抢输入法），`SYSTEM_ALERT_WINDOW` 权限 |
| 视觉 | 渐变玻璃底（浅色淡灰 / 深色 `#1A1A1C`）+ 发丝描边 + 顶部内缘高光 + 状态点 + 等宽地址；**圆角取高度一半**（胶囊） |
| 动效 | 进入 220ms 淡入 + 0.90→1.0 缩放；退出 140ms 淡出后 `removeView` |
| 交互 | 点击回到应用；拖动改位置（超过 6dp 才算拖动，落盘到 SharedPreferences，下次沿用） |
| 边界 | 拖动与恢复都按「屏幕 − 视图尺寸」夹取，不会出现「整颗在屏幕外、点不到也拖不回来」 |
| 权限 | 点启动时若未授权：Toast 提示 + 跳 `ACTION_MANAGE_OVERLAY_PERMISSION`（带 `package:` URI，失败回退总列表）；**不阻断网关启动**，返回应用后 `onResume` 自动补显示 |
| 开关 | 设置 → 界面 → 「网关地址浮窗」；关闭立即摘窗，开启且网关在跑立即显示 |

工程决策：
- **不用 Compose 画**：overlay 里的 `ComposeView` 需要宿主提供 `ViewTreeLifecycleOwner /
  SavedStateRegistryOwner / ViewModelStoreOwner`，Service 没有这些宿主，缺一个就在 `addView`
  时崩；这里只有「一层渐变 + 一条描边 + 一行字」，自绘 View 更小更可控。
- **不做投影**：overlay 没有背景采样，`setShadowLayer` 必须开软件层，而投影会被窗口自身边界
  裁掉（只剩四个角）——花软渲染的代价却拿不到效果，改为「渐变 + 高光 + 发丝描边」。
- **不用 `FLAG_BLUR_BEHIND`**：`setBackgroundBlurRadius` 在多数 ROM 上要么不支持、要么对
  overlay 无效，真玻璃需要的背景层在这个窗口里不存在，模拟反而更稳定。

### 2. 上游 10-24 修复移植

| 上游提交 | 内容 | 本端落地 |
|---|---|---|
| `614e7b496` / `0bdbc0246` / `21a6e4569`（issue #66） | 网关 401 先 refresh 求证再计 session dead | 新增 `verifySession()`（ALIVE/DEAD/UNKNOWN 三态）+ 统一错误入口 `noteError()`：401 先求证，**只有 refresh 被明确拒绝才禁用**，瞬时拦截/无判词一律软冷却 |
| `cdad965d4` | 429 code 6000 带「切换其他模型」文案时按模型级限流 | `RateLimit.isModelRateLimit` 增加该判据（不带文案仍走账号级，避免误判） |
| `85e44b278` | 模型级冷却完整对齐上游重置墙钟 | 模型级 `until` 不再被 `soft_rate_max` 截断（账号级仍封顶） |
| `e7d1e70bb` | 尊重客户端 off/none 关闭思考 | `effortValueOff()`：off/none/disabled 直接写 `thinking.type=disabled` 并清档位，且**优先于**任何 enabled 形态 |
| `185ce77f4` | 模型未调工具的正常收尾降为 INFO | 静默回合日志 WARN → INFO（模型选择直接回答是合法行为，不该按错误汇总） |
| `f0c427d2c` | 官方 CLI 2.174.0 | `DEFAULT_CLI_VERSION` / `UA_DESKTOP` 同步到 2.174.0 |

### 3. 审计加固（含本轮新代码自身）

| 等级 | 位置 | 问题 | 处置 |
|---|---|---|---|
| **P0** | `Pool` / `Store` 锁序 | `withStateLock`（Store 监视器）内调 `persistThrottled`（Pool 监视器），而 `flush()` 是「Pool → Store」，构成 AB-BA → 可能永久假死 | 落盘全部移到 `withStateLock` **之外**（`.also { persistThrottled() }`）；禁用这种终态改用同锁可重入的 `saveState` |
| **P0** | `verifySession` 并发 | 并发 401 让多个请求用**同一份旧 RT** 刷新：上游轮换 RT 后，后到者 4xx → 判 DEAD → 健康号被永久禁用 | 按 uid **单飞**（`synchronized`）+ 二次读取**最新**凭证；已被别人刷新过则直接判 ALIVE |
| **P0** | `verifySession` 超时 | 用共享 client（`callTimeout=0`、read 120s），全池 token 同时过期时 32 个 HTTP 线程可能被一起挂住 → 整体 503 | 新增 `verifyHttp`（callTimeout/readTimeout 6s）；`refresh()` 也改用短超时（它现在在请求路径上被主动续期调用） |
| **P1** | DEAD 判据 | 「4xx 非 429 非 HTML」把 404（路径变更）/400（字段变更）/WAF 的 JSON 403 都判成 token 失效 → 一次可清空整池 | 只认**失效文案**（invalid/expired/revoked/失效 + refresh/token/grant 语境）；200 + 业务错误信封同样按文案判 |
| **P1** | 401 未进求证 | `Classify` 只在 body 命中特定文案时才出 `SESSION_DEAD`，边缘拦截的 401 走的是 `CLIENT` —— 最需要求证的形态反而漏掉 | `noteError` 触发条件改为「`SESSION_DEAD` **或** HTTP 401」 |
| **P1** | 软冷却覆盖 | `softCooldownForSession` 无条件写 `cool_until`，会把 `hard_credit`（次日 04:00）/熔断等更强冷却改成 600s | 「只延长不缩短」：`maxOf` + 更强 kind 不被覆盖 |
| **P1** | access token 无主动续期 | 本端按 1h 记到期且没有续期点 → 每个号每小时必然 401 一次（ALIVE 后再罚 600s 等于每小时白停 10 分钟） | ① 新增 `ensureFreshToken()`：临期 120s 内先刷新并落盘；② ALIVE 的冷却降为 30s |
| **P1** | 浮窗 `show()` 早退 | 用户撤销权限时系统已移窗但单例仍非空，重新授权后 `show()` 命中早退 → 浮窗永远不再出现 | 判 `isAttachedToWindow`，否则清理状态重建 |
| **P1** | 浮窗线程 | `WindowManager` 与 `View.animate()` 要求主线程，字段无可见性保证 | `show/hide` 内部统一 post 主线程 + 字段 `@Volatile` |
| **P1** | 浮窗边界 | 拖动不做夹取、落盘坐标可为负/超屏 | 拖动与恢复都按「屏幕 − 视图尺寸」夹取 |
| **P1** | 设置开关 | 关闭开关后浮窗仍盖在别的应用上（要等下次 onResume） | 关闭立即 `hide()`、开启且运行中立即 `show()` |
| **P1** | 启动顺序 | 先跳授权页再 `startForegroundService`：应用已进入后台，Android 12+ 可能直接拒绝启动 | 改为先启动服务，再提示/跳转 |
| **P2** | `markSessionDead` | 顺手把 `paused=false`（清掉用户手动暂停的意图）；终态走 1s 节流可能丢 | 不动 `paused`；终态用 `saveState` 立即落盘 |
| **P2** | `verifySession` 取消 | `catch (Throwable)` 吞掉 `CancellationException`，停机后仍改池状态 | 先重抛 `CancellationException` |
| **P2** | 浮窗注释 | KDoc 声称「运行状态点」但无运行态入参 | 改为「浮窗只在运行期存在，点的含义即运行中」 |

## 变更（1.6.6 / versionCode 45）— 跟进上游 10-22 发版（issue #51 头族 / #46 繁忙 / 空回复）

上游在 2026-10-22 连发三版（`v2026.10.22.1044 / .1330 / .1823`）。本轮逐提交比对后移植
与「能不能调用、会不会莫名断流」直接相关的三组，其余按「新增能力 vs 修复」的界线不并入。

### 1. 出站头族与标识按两版真机对齐（上游 issue #51）

上一版补的会话头族方向是对的，但**形态与集合**还有偏差 —— 上游用两版客户端（国际 5.8.4 /
国内 5.8.3）逐头对照后做了修正：

| 项 | 1.6.5（旧） | 1.6.6（对齐真机） |
|---|---|---|
| 会话 ID 形态 | 32 hex | **UUID v4（36 字符带横线）**，`4xxx`/`89ab` 位按 RFC 4122 置位 |
| 消息 ID | 纯随机 32 hex | 32 hex，**前 12 位是毫秒时间戳**（官方同会话严格单调） |
| `X-Trace-ID` | 空则回落轮级 ID | **独立随机 32 hex**（官方两族恒不相等；旧值让 trace 与 request 恒等） |
| `X-B3-SpanId` | 消息 ID 前 16 位 | **独立随机 16 hex**（否则会带出消息 ID 的时间前缀） |
| `X-B3-ParentSpanId` / `b3` / `traceparent` | 无 | **补齐**（官方两版都双发单头 b3 与 W3C traceparent） |
| `acp-connection-id` | 无 | **补**（同 (账号, 会话) 稳定的 UUID，与 X-Conversation-ID 不同值） |
| `X-Product` | `WorkBuddy` | **`SaaS`**（SKU 段；产品名只在 `X-IDE-Name/Type`） |
| `x-stainless-*`（7 个）/ `X-Agent-Type` / `X-Agent-Intent` | 无 | **补**（openai-node SDK 自报段，两版完全一致） |
| `prompt_cache_key` | `wb2a-` + 12 hex | 与 `X-Conversation-ID` **同源同形态的 UUID**（旧值自带项目缩写） |
| chat 域 `Origin`/`Referer`/`Accept-Language`/`X-Machine-ID`/`X-Session-ID`/`X-No-*` | 全发 | **全删**（官方 chat 走 SDK，没有浏览器语义；多发一个就是一处指纹） |
| chat `Accept` | `application/json, text/event-stream` | **`application/json`**（官方只发它，SSE 照常返回） |
| body 侧 `conversationRequestId` | 无 | **给当前轮最后一条 user 消息补**，与头一致（含 `startsNewUserRequest` / `agent:"cli"`） |

> 边界：历史消息的轮级 ID 客户端没给、OpenAI 协议里也没有，**不编造**（对不上的 ID 比缺失
> 更容易被关联分析抓出来）；客户端已带则原值优先。

### 2. 14003「模型繁忙」（上游 issue #46 / #46 补丁 97ba2281）

上游繁忙是**间歇**的（同一分钟内既有 14003 也有成功）。旧实现把它当 429 走 `soft_rate`
（600s 起、翻倍、封顶 2h），三个号叠起来就是整分钟客户端可见的黑障。

- 新增 `ErrKind.MODEL_BUSY`：**模型级**短冷却 10s 起、同 (号,模型) 反复命中翻倍、封顶 60s，
  不碰账号级（同号换模型立即可用）；
- 全池该模型都被挡住且原因只有 14003、最早解封 ≤10s 时，**先等一次**再挑（正向抖动
  0~+25%，早醒等于白等）；仍不行则 `503 model_busy` + `Retry-After`（不再是误导性的
  `429 rate_limit_exceeded`）；
- 该模型任意一次成功即清掉它的 14003 短冷却（成功是最强证据）。

### 3. 「预算被思考吃光」导致空回复（上游 b0320cf1）

上游把 reasoning 与正文算在同一份 `max_tokens` 里，思考没有独立上限。上游实测：

```
max_tokens=60/100/150/200/300 → thinking 恰等于预算，正文 0 字节（finish=length）
max_tokens=400                → 思考 166~272，正文开始出现
thinking:{type:disabled} 同额度 → 正文正常、finish=stop
```

Agent 循环里这等于「回合正常结束、任务静默停住」。上游的做法是事后**同额度关思考重发一次**；
本端改为**事前不注入思考**（客户端没显式声明 thinking / reasoning_effort、且额度在 16~399
区间时）—— 结果等价，但覆盖流式与非流式、不额外消耗一次上游请求。`max_tokens ≤ 15` 的探针
请求不干预（官方客户端就用 1 做 key 探活）。

### 4. 本轮审计修复（全量代码 + 记忆库）

| 等级 | 位置 | 问题 | 处置 |
|---|---|---|---|
| **P0** | `ChatHandler` Responses 流式 error 帧 | 返回 `Hop(ok=false)` 漏 `sent=true` → `runPipeline` 判成「首帧前失败」而换号重试，但字节已发出、重试全被丢弃 → 客户端只看到一个 failed，网关白打 2 次上游并记 3 次错误 | 改 `sent = true`（与 chat 流式同口径） |
| **P0** | `Store`/`Pool` 池状态 | `stateOf → copy → putState` 无锁 RMW，同账号并发（in-flight 5）会互相覆盖：**冷却被抹、`disabled` 可被回滚、计数与模型台账丢更新** | 新增 `Store.withStateLock`（同一监视器、可重入），`onError`/`onSuccess`/`onTransportFailure` 三条热路径整体进入临界区 |
| **P0** | `HttpServer` 请求行/请求头 | 只有 body 有上限，请求行与头条数**无上限**；监听 0.0.0.0，一条超长行即可 OOM | `MAX_LINE_BYTES=8KB`、`MAX_HEADER_COUNT=128`，超限回 413 |
| **P1** | `Store.readRequestLogs` | 为最后 200 行把 100MB 归档整份读成 `List<String>` | 自文件尾按 8KB 块反向读，内存与文件大小无关 |
| **P1** | `Pool` 粘性开关 | `session_sticky.enabled` 只有 GC 在读 → 关掉开关仍粘、且 map 无限增长 | `stickyGet`/`stickyBind` 也判断开关 |
| **P1** | `Pool.modelBlocked` | 不分 realm → cn 号全冷却会把 global 的可用模型误报成「全池不可用」 | 加 realm 过滤，`mapError`/等待逻辑一并传 realm |
| **P1** | `Pool.onError` 落盘 | 每次错误整份写盘 + fsync，与类内「落盘节流」注释自相矛盾 | 改 `persistThrottled()`（调度器 flush 兜底） |
| **P1** | `HttpServer.readChunked` / `Res.chunk` | 负分块触发 `NegativeArraySizeException`；`chunk("")` 会写出 chunked **终止符** | 负值按流结束处理；空串直接返回 |
| **P2** | `Config` | `credit_floor`（积分保底）从未参与选号；`request_retention_days` 承诺「超期自动清理」但无实现 | 注释改为与实现一致（诚实标注「仅展示 / 预留」），不谎报能力 |
| **P2** | `Pool.persistThrottled` / `stickyGC` | 前者与 `flush` 混用两套同步；后者无视 `gc_interval` 配置 | 统一 `@Synchronized`；GC 按 `gc_interval` 节流 |

### 5. 仍未移植（按「新增能力 vs 修复」的界线）

Web 面板 / `/v1/search` 联网检索 / `/v1/messages`（Anthropic 协议）/ `/v2/chat/busy` 预检 /
DSH（DeepSeek Harness）系统提示词替换 / 面板级任务调度与成本账本 —— 属新增能力或与移动端
形态无关，本轮不并入（避免把改动面摊大到无法验证）。

## 变更（1.6.5 / versionCode 44）— 修「渠道校验」：11128 / 11-128 request illegal

**现象**（真机）：所有上游调用返回
`HTTP 400 {"code":11128,"msg":"request illegal","displayTips":"这次调用没通过渠道校验，可能不是官方客户端发出的"}`，
之后整池变成 `503 no_healthy_account`（一个号都不剩），网关看起来"大部分功能无法调用"。

**这不是账号问题，是请求指纹问题**。逐条比对 `misakano7545/workbuddy2api-panel`（含其
`629d9e72d` 会话头族、`17b5204` 错误处置两次提交）后确认三处缺失，全部修掉：

### 1. 出站**会话头族**一个都不发（渠道校验三件套）
上游按 `X-Conversation-ID` / `X-Conversation-Request-ID` / `X-Conversation-Message-ID`
是否**齐全**做渠道校验，缺任一头即 `400 code=11-128 "conversation id missing"`。
旧实现一个都不发（只有无关的 `X-Request-ID` 出现在别的端点）→ 每次调用都被判非官方客户端。

现按官方语义补齐（`Upstream.applyHeaders` chat 分支）：

| 头 | 值 | 语义 |
|---|---|---|
| `X-Conversation-ID` | 客户端 body 的 `conversation_id` **原值透传**；没有则按「uid + 会话键」派生 32 hex | 会话级、跨轮稳定；同时是上游**前缀缓存槽**来源 |
| `X-Conversation-Request-ID` | 轮级 ID，**轮转循环外生成一次**，换号 / 重试 / 降级全部复用 | 上游后台按它把「一次用户发送」聚合成一条 |
| `X-Conversation-Message-ID` | 每条出站独立 32 hex | 消息级 |
| `X-Request-ID` / `X-Root-Request-ID` / `X-Trace-ID` | = 消息级 / 轮级 / 入站透传 | 链路追踪 |
| `X-B3-TraceId` / `X-B3-SpanId` / `X-B3-Sampled` | 轮级（非法时回落消息级）/ 消息级前 16 位 / `1` | B3 链路族，只认 16/32 hex |

### 2. 首条消息不是 `system` 就必然被拒
上游**渠道校验的硬要求**：`messages[0]` 必须是 `system`（2026-10-20 真机三条对照）：

```
首条 user       → 400 11-128 "first message is not system prompt"
首条 developer  → 400 11-128 "Illegal API invocation from an unapproved channel"
首条 system     → 200（同一 body 其余部分不变）
```

旧实现只在 global 分支补 system，且把「首条 `developer`」漏掉。现改为**全 realm 统一**
（`Rewrite.ensureSystemFirst`，对齐新上游 `prompt.EnsureSystemFirst`）：
首条 `system` 零改动 / 首条 `developer` 就地改成 `system`（内容逐字保留）/ 其余最前补一条
中性提示词（同 `prompt.Degraded`，不替客户端做人格设定）。

> 模型自检的 body 正是 `messages:[{role:"user",content:"hi"}]` —— 这一条会让它 100% 被拒。

### 3. 把「渠道校验」当成「账号封禁」→ 一次失败打空整池
旧 `Classify` 只要 body 含 `request illegal` 就判 `ACCOUNT_FAULT`，而 `Pool` 对该分支里的
`request illegal` 直接 **`Disable()`**。于是第 1、2 条的拦截把**健康账号永久踢出池子**，
表象变成 `503 no_healthy_account`。

新上游的分野（`handler.go:1823`、`17b5204`）：同一条 msg 骑两种 code，按 code 分开处置 ——

| code | 含义 | 处置 |
|---|---|---|
| `"11-128"` / `11128` + 渠道文案 | 渠道校验拦截 | **软冷却 + 继续轮转，绝不 Disable** |
| `11140` | auth_forbidden 账号级授权风控 | 硬禁用（保持） |
| `11128` + `blocked by security policy` | 内容审核误报 | 零动作（保持，靠 msg 与上面区分） |

落地：新增 `ErrKind.CHANNEL_BLOCK` + `Classify.isChannelBlock()`（**同时**要求 code 命中与
渠道文案命中；容忍 `\"code\"` 被再序列化一层的转义形态）→ `Pool` 软冷却（`soft_rate` 基数 +
`soft_streak` 指数、封顶 `soft_rate_max`）、不喂熔断；对外返回
`502 channel_verification_failed` 并附排查提示（不再是误导性的 `no_healthy_account`）。
帧路径（SSE error 帧 / 内嵌失败帧）也把 `error.code` 拼回信封再分类，否则"只拿到 message"
会把渠道拦截重新判成账号封禁。

### 4. 顺带修掉的两个静默缺陷
1. **非流式聚合不认「内嵌失败帧」**：上游把失败塞进 HTTP 200 的 SSE 顶层 `{code,msg}`
   （既无 `choices` 也无 `error`）。chat 与 responses 的**非流式**路径此前只认 `error`，
   于是跳过该帧 → `content` 为空、`finish_reason` 保持 `stop` → 客户端拿到一个
   **"成功但什么都没有"**的响应，账号也不被处置。现在先归一成标准 error 帧再分类。
2. **历史误禁用自动解除**：1.6.4 及更早把渠道校验判成封禁，用户设备上可能已有
   `disabled + cool_kind=account_banned` 的账号。`Pool.reload()` 现在幂等解除这一类
   （真封禁的 11140 会在修复后的代码里重新禁回去，故安全）—— 否则升级后仍是 503。

### 5. 本轮**未**动的项（避免拆东墙补西墙）
- billing 域 UA（上游实测为 `axios/1.16.1`，本端仍发 `WorkBuddy/<ver>`）：当前签到/余额/成长
  任务实测可用，改 UA 只会扩大回归面，留待单独验证；
- `/v2/report` 等非 chat 域补 `X-Conversation-ID`：属成长任务计分的独立议题，与「无法调用」无关。

## 变更（1.6.4 / versionCode 43）— 跟进新上游分支（旧上游已停维护）

**背景**：直接上游 `linguo2625469/workbuddy2api-panel` 于 2026-10-21 宣布停止维护；上游接口与
客户端指纹持续变化，导致本 APP 的网关调用大面积异常。本轮按接力分支
`misakano7545/workbuddy2api-panel`（fork 链路 `Sliverkiss → linguo → misakano`）**逐文件比对**
后移植 —— 该分支在本轮新增 143 个 Go 文件，协议层改动集中在
`internal/upstream/{headers,payload,sse,dsml,tool_pairing}.go`。

### 1. 出站指纹对齐（UA / 版本 / 头族）
| 项 | 旧值 | 新值（对齐新上游 `headers.go` 2026-10-20/21 双版本抓包） |
|---|---|---|
| 客户端版本 | 5.5.4（CN/GL 共用） | **CN 5.8.3 / global 5.8.4**（两版发版节奏不同，不能共用） |
| CLI 版本 | 2.137.1 | **2.161.0** |
| 三段式 UA（global） | `WorkBuddy/v WorkBuddy AI/v CLI/v` | `WorkBuddy/5.8.4 **workbuddy-ai**/5.8.4 CLI/2.161.0`（品牌小写连字符） |
| `X-IDE-Version` | 恒 5.5.4 | 按 realm 取 5.8.3 / 5.8.4 |
| `Accept-Language` | global → `en-US` | **统一 `zh`**（实测跟随系统区域，不按账号域切；旧值是臆测） |
| `X-Private-Data` | 无 | **`true`**（官方 chat 出站恒带） |
| 模型探测 | 三路旧 UA | **追加**官方「三段式 + 两段式 Lite」两路（纯增量：合并按 id 去重，只会多不会少） |

### 2. 网关「调用异常」的三处根因修复
1. **内嵌失败帧**（新上游 `sse.go:StreamErrorFrame`）：上游把 11140 内容拦截、6004 模型级限流
   塞在 SSE 的**顶层** `{code,msg}`（既无 `choices` 也无 `error`）。旧实现只认 `{"error"}`，
   帧白名单又把 `code/msg` 剥掉 → 客户端只看到「干净地停下、无任何失败」，网关日志记成
   「断流」。现在先归一成标准 error 帧，再做账号处置（首帧即失败仍能无痕换号重试）。
2. **断流被当正常完成**（新上游 `TruncationFinishReason`）：既无 `finish_reason` 也无
   `[DONE]` 就 EOF 时，`finish_reason` 由默认的 `stop` 改为 **`length`**，客户端据此知道
   「输出残缺、不要当完整结果」。Responses 侧沿用 1.6.2 的 `response.incomplete`。
3. **空名 tool_call 让整条会话每轮 400**（新上游 `tool_pairing.go:toolCallNameMissing`）：
   `function.name` 缺失/空白的调用，上游直接判 **11133**（`tool_calls[i].function.name must
   be a non-empty string`），带不带配对结果都拒。现在把这类调用连同它的 `role:tool` 结果
   **成对剔除** —— 这正是「整条会话每轮 400、只能手动清空历史」的成因。

### 3. DSML 原生工具调用标记还原（新模块 `upstream/Dsml.kt`）
DeepSeek 系模型的原生工具调用是一段带**全角竖线**（`U+FF5C`）的标记文本；上游只在请求
**声明了 tools** 时才把它解析成结构化 `tool_calls`。一旦 tools 在中转环节丢失，这段标记就以
纯文本落进 `content`，agent 客户端（Codex 等）不认识 → 把标记当正文写进历史、回合直接结束、
工具一次都没跑（**任务停在半路**，与「手动继续才接着做」的现象吻合）。

新增状态机逐条对齐新上游 `dsml.go`：两级判定（名单非空按严格比对；名单为空按「块结构完美 +
工具名形如标识符」弱判定）、**拿不准就原文回吐（绝不吞字节）**、流式 / 非流式 / Responses
三路全覆盖；命中时把正文帧拆成「正文 + N 个 tool_calls 帧」，并把 `finish_reason` 改写为
`tool_calls`。

### 4. 请求体归一化补全
- `stop` 传字符串时归一成**数组**（上游只接受数组形态）；
- content parts 里的 `reasoning_text` / `reasoning` 片段删除（上游不认这类 part，会 400）；
  整条只剩思考片段且没有 `tool_calls` 的消息一并删除（避免空 content 报错）。

### 5. 本轮**未**移植（明确不做，附理由）
Web 面板侧能力（官方补偿、积分历史、API Key 管理、告警）与 `/v1/search` 联网检索、
`/v1/messages`（Anthropic 协议）、`/v2/chat/busy` 繁忙度、成本账本选号 —— 属**新增能力**而非
「修复既有功能」，会显著扩大改动面与回归风险。本 APP 的既有功能（对话 / 工具调用 / 账号池 /
签到 / 成长任务 / 余额 / token 刷新 / 模型目录）已按新上游口径对齐。

## 变更（1.6.3 / versionCode 42）— 首页总积分行重排 + 第三轮审计

### 1. 首页「总共可用积分」行重排（去掉左侧图标）
1.6.2 把它做成「图标 + 两行文字（左）/ 单个数值（右）」，左重右轻、读起来割裂且不对称
（用户反馈）。本轮**去掉左侧图标**，改为标准的「左标签 / 右数值」行，并把数值放大到
`title2`（24sp）与左标签形成对等重量 —— 数值本就是这一行的视觉主体。

### 2. 第三轮审计（子代理全量审计 + 逐条自核后落地）
| 项 | 问题 | 处置 |
|---|---|---|
| **P0 4xx 分类** | `Classify.of` 把**所有** 4xx 兜底成 `BAD_PARAMS`，而 `failFastKinds` 含 `BAD_PARAMS` → 任何未知 4xx（401/403 带信封、405、非特定文案的 400…）都会 fail-fast **直接放弃池里其它账号**；`Classify` 实际从不返回 `CLIENT`，与 `failFastKinds` 注释、README 1.6.1 的自述正面矛盾 | 对齐 Go `client.go:578-593`：仅命中 `11101` / `Unmarshal chat params failed` → `BAD_PARAMS`；**其余 4xx → `CLIENT`**（只换号 + 连败降权、继续轮转） |
| **P0 非流式错误帧** | chat / responses 非流式在回写错误体后返回 `Hop(ok=false)`（`sent` 默认 false）→ `runPipeline` 误判「首帧前失败」而换号重发，但 `Res` 已发出字节、后续写入是 no-op → **客户端拿到错误、网关却记为成功**，并白打一次上游 | 两处改为 `Hop(ok = false, sent = true)` |
| **P1 模型级冷却回退** | `pick()` 里 `.ifEmpty { candidates }` 等于忽略模型级冷却 → 硬挑一个冷却中的号去撞限流；`modelBlocked()` 成为不可达死代码 | 去掉回退，候选全被模型级冷却时返回 `null`（对齐 Go `pick`）→ 上报 `model_unavailable` |
| **P2 归档注入** | 请求归档用字符串拼接 JSON，客户端可控的 `model` / `client_ip` 含引号或换行会破坏 `.jsonl` 单行结构 | 改用 `buildJsonObject` + `AppJson` 序列化 |
| **P2 脱敏** | `Mask.uid` 对短 ID 生成等量圆点（精确泄露长度）；`Mask.name` 对 1 字昵称直接返回原文 | 短 UID 固定 4 点；1 字昵称也打码 |
| **P2 失真注释** | 多处「对齐上游 Go `X`」引用的上游符号并不存在（`Pool.ModelBlockStatus` / `Pool.ModelBlocked` / `enterpriseUnlimitedRemain` / `tokensPerSecond`） | 校正为「本端自造 / 上游无同名符号」，避免下一轮审计被误当「已对齐」 |
| **P2 时区注释** | `nextHardReset` 写死 GMT+8，注释却称「对齐 Go nextDay4AM」（后者用设备本地时区） | 保留 CST 行为（与「429 重置墙钟固定按 UTC+8 解析」同源），注释改为说明这是**有意**偏离 |

**本轮明确不改（附理由）**：① WAF 软冷却未加上游的 `jitterDur` ±25% 抖动 —— 本项目已有
「realm 级 WAF 闸门」抗同刻齐触发，且确定性冷却更易排查；② `sendError` 的 `type` / `code`
字段放置与 OpenAI 略有出入（多数客户端只看 `message`，逐个改调用点收益低、风险高）；
③ `missedCheckin` 未排除「暂停选号」账号 —— 与「全量余额刷新」同样不排除暂停号，单独改一处
会造成两套口径；④ 未接线配置字段与未移植的成本选号策略（见 1.6.0 说明）。

## 变更（1.6.2 / versionCode 41）— Responses 续传、总积分排版、消耗口径、账号计数

### 1. `/v1/responses`：被截断时必须上报 `incomplete`（修复 agent「不自动续传」）
**问题**：用 `/v1/responses` 的 agent 经常在任务未完成时停下、要手动「继续」才接着做；同样
的 agent 走 `/v1/chat/completions` 就没这个问题。

**根因**（对照官方语义核实）：Responses 流式**必须以
`response.completed` / `response.incomplete` / `response.failed` 三者之一收尾**，其中
「生成被 `max_output_tokens` 截断」要用 **`response.incomplete`** 收尾，并给出
`status:"incomplete"` + `incomplete_details.reason:"max_output_tokens"`。而本端过去**完全没有
读 `finish_reason`**，一律把收尾写成 `response.completed` + `status:"completed"` +
`incomplete_details:null` —— 于是：
- 撞到 `max_output_tokens` 被截断时，agent 收到的是「正常完成」，就判定任务结束而**停下**；
- 而 chat/completions 的客户端能直接看到 `finish_reason:"length"`，会自行续写 → 这就是两条
  端点行为不一致的**唯一来源**（客户端设置偏小的 `max_output_tokens` 时尤其频繁）。

**修复**：流式与非流式都记录 `finish_reason` 与 `[DONE]`，据此判定截断
（`finish_reason=="length"` 或「既无 `[DONE]` 又无 `finish_reason`」的异常收尾）：
- 流式：截断时改发 **`response.incomplete`**（`status:"incomplete"`、`incomplete_details.reason`
  为 `max_output_tokens`）；日志追加 `[截断→incomplete]` 便于排查。
- 非流式：`status` 置 `incomplete`；且**丢弃 arguments 残缺的 tool_call**（与 chat 路径
  `isTruncatedArgs` / Go `upstream/truncation.go` 同口径），避免下发非法 JSON 卡死会话。
- 顺带补上 `reasoning` item 缺失的 `status:"completed"`（官方 reasoning item 带该字段）。

### 2. 首页「总共可用积分」排版优化
原实现让**数值对「标题+副标题」两行整体垂直居中**，视觉上数值沉到副标题基线附近、与标题
错位；且该行竖向内边距用的是 `gapS(6dp)`，比上方四个数字行矮一截，读起来「上宽下窄、被分割
线压住」。现改为：数值与标题**同处一行、同一基线**，右缘对齐卡片内容右边界（与「运行指标」
等行的值右对齐一致）；竖向节奏改用标准行距 `rowPadV`；无余额数据时数值显示 `—`。

### 3. 「本次消耗积分」口径修正：启动/停止各刷一次余额
**问题**：余额按固定间隔（如 5 分钟）刷新，若某个窗口内没刷新，这段时间的消耗就不会被统计；
停止后也没有最后补刷，导致「消耗积分」少算、甚至一直是 0。

**修复**（`Gateway.setRunning` + 新增 `Gateway.refreshBalances(reason)`，用**进程级**作用域，
不随 Service 销毁取消）：
- **启动后**立刻刷一次余额 → 建立「启动时刻余额」基线。基线来源也一并修正：
  `AccountPool.resetSessionCreditTracking()` 改为**丢弃基线**、交给这次刷新建立，而不是拿
  「上次落盘的旧余额」当基线（那样会把**启动之前**的消耗算进本次运行）。
- **停止后**再刷一次 → 把最后一个未刷新窗口的消耗补记进来。
- 三次路径（启动/停止/手动「刷新余额」按钮）+ 定时任务共用同一个 `AccountPool.setCredits`
  口径，**同时**更新账号余额与本次消耗，互不冲突、不重复计。

### 4. 账号页：「添加账号」行右侧的 ➕ 改为账号数概览
原行尾硬编码一个 ➕，与左侧图标底座里的 ➕ 重复（用户反馈「两个加号」）。现把行尾槽位改为
按 realm 的账号计数 `CN n` / `GL n`（两行、右对齐、`animateIntAsState` 增长动画）；池内为空时
不显示，避免空态出现「CN 0 / GL 0」的噪声。

### 5. 记忆系统同步
`memory_md` 整体重写（对齐 1.6.2：Responses 续传口径、消耗口径、账号计数）；`agent_md` 追加
「Responses 官方流式收尾语义（completed/incomplete/failed + incomplete_details）」等经验，
并刷新沙箱/构建条目（工具链重建脚本、构建脚本命名）。

## 变更（1.6.1 / versionCode 40）— 五项体验修复 + 第二轮审计

### 1. 出站日志：结构化摘要 + 脱敏（不再打印请求体原文）
原日志把整个出站 JSON body 的前 200 字符直接打出来（就是那种折行、被截断、还夹着 system
提示词与用户消息的行）。现改为**一行结构化摘要**：

```
→ 出站 cn/deepseek-v4.1-flash uid=7582••••b2d17 stream · 12 条消息 · 3 个工具 · max_tokens 8192 · 思考 medium
```

内容全部脱敏（不含任何消息文本），uid 也打码。同时把**一直没用上的** `logging.request_body_log`
真正接上：开启后额外追加一段**已脱敏的 JSON 骨架**（保留键名、数组只报长度、长字符串折叠），
并在设置页「请求归档」组补了开关。

### 2. 首页：账号概览新增「总共可用积分」；运行时长附带「本次消耗」
- 「账号概览」卡新增一行「**总共可用积分**」：数值做滚动增长动画，点按进账号页；企业版
  「不限」账号不计入合计，并标注「含不限」。
- 「运行指标 → 运行时长」在网关启动过后追加「**· 消耗 N 积分**」：运行中 `1h20m · 消耗 12.0 积分`，
  停止后 `已停止（上次 1h20m）· 消耗 12.0 积分`。
- 消耗口径 = **余额差值**：启动网关时以各账号当前 `credits` 为基线，之后每次余额刷新把下降量
  累计（只累计下降、忽略奖励/重置带来的上升）。因为上游目录只给相对费率、成本台账未移植，
  没有可靠的**请求级**单价。实现见 `AccountPool.resetSessionCreditTracking()` /
  `sessionCreditConsumed()`（`Gateway.setRunning(true)` 时重置）。

### 3. 账号列表脱敏
列表里的**昵称与 UID** 默认打码（`core/Mask.kt`：`7582••••b2d17` / `犀•••汐`），
点进「账号详情」仍显示原文。

### 4. 账号页：液态玻璃合并表头 + 列表始终可拖动
- 「添加账号」与「全部/可用/冷却/禁用/暂停」合并成**一块**液态玻璃面板（与模型页同构：
  上半动作条 → 细分隔线 → 下半分段控件；区别是没有搜索框、上半是「动作」入口，一眼可分两页）。
- 列表改用**唯一的 LazyColumn**（空态也作为列表项、`fillParentMaxSize` 占满视口），并显式挂
  `Modifier.overScrollVertical()`（同时 `overscrollEffect = null`，避免两层回弹叠加）。
  由此**即使账号不足一屏、甚至为空也拖得动**（对齐模型页；Miuix 回弹半径取自窗口高度、
  与内容量无关）。

### 5. 第二轮全量审计：与上游逐条比对后的修复
| 项 | 修正前 | 修正后（= 上游） |
|---|---|---|
| 传输层失败 | 归为 `ErrKind.SERVER` → **喂熔断**（网络一抖就熔断健康号） | 走 `onTransportFailure` → **连败降权**（`NoteFailures`，只换号不喂熔断）。本条**修正 1.6.0 表里 `SERVER` 一行括注的「传输失败」** |
| fail-fast 集合 | 误含 `ErrKind.CLIENT` → 未知 4xx 直接放弃其它账号 | 只留 `BAD_PARAMS/CONTENT_BLOCKED/PROMPT_TOO_LONG/IMAGE_INVALID`；`CLIENT` **继续轮转** |
| 硬冷却复位 | 无条件「次日 04:00」（凌晨触发多冷近一天） | 本地 00:00–04:00 → **当天** 04:00，否则次日 04:00（对齐 `nextDay4AM`） |
| 成功后复位 | 只清 `breaker/degrade_fail_streak` → `soft_streak` 永远累加、软冷却被推向封顶 | 清 `soft_streak`+`breaker_retry`+`breaker_until`+`degrade_until`（对齐 `NoteSuccess`） |
| 熔断指数 | 用「连续失败数」当指数、触发后不清零 → 退避无界膨胀 | 新增 `AccountState.breaker_retry`（触发次数）驱动指数，触发即清零连续失败计数 |
| 连败降权时长 | 指数退避、达阈后不复位 | **固定时长**（钳到 `[degrade_cooldown, degrade_cooldown_max]`）、达阈清零、降权期内不延长 |
| WAF 冷却 | 忽略 `Retry-After` | 有则冷却到该时刻，否则指数基数 |
| 404 | 额外写模型级 `model_unavailable` 台账 | 只做账号级短冷却 |
| `http_success` | 硬编码 `true` → 恒等于 `completed` | 传 `hop.ok \|\| hop.sent` |
| `Gateway.port` | 非 `@Volatile`（accept 线程读 / UI 线程写） | 加 `@Volatile` |
| 日志页 | `filtered.asReversed()` 每帧新建视图 | 反转并入 `remember` |
| 归档时间格式 | 每次调用 `new SimpleDateFormat` | 改用线程安全的 `java.time` |

**本轮明确不改（附理由）**：`pick()` 的 `credit_floor` / `cost_explore_interval` / `model_costs`
成本台账属**未移植**的非核心选号策略（改动会直接改变选号分布，风险>收益，前文已列「有意保留」）；
`mapError` 的终端状态码（`402 insufficient_credit` / `400 model_unavailable` 等）是**有意的客户端
增强**（比上游的「一律 503」更能指导重试），保留并在代码注释中标注；若干未接线的配置字段
（`GlobalCfg.chat_base`、`upstash`、`prompt`、`header_timeout_seconds` 等）为与上游 `config.json`
保持 1:1 读写兼容而保留。

## 变更（1.6.0 / versionCode 39）— 全量代码审计后的修复与重构

本轮对全部 35 个 Kotlin 源文件逐文件审计，并与 Go 上游（`/sandbox/workspace/gosrc`）逐项比对。
下面按「错误处置 / 并发安全 / 解析健壮性 / 注释与死代码」四类列出**实际落地**的修改。

### 1. 错误处置状态机：逐条对齐上游 `handler.applyErrorPolicy`（本轮最重要）

审计发现 `AccountPool.onError` 的错误 → 动作映射**与上游成体系地不一致**，而注释却写着"逐条对齐 Go"。
对照上游 `internal/server/handler.go:1190-1300` 后修正：

| 错误类型 | 修正前（本地） | 修正后（= 上游语义） |
|---|---|---|
| `SERVER`（5xx / 传输失败） | **不罚号** | **喂连续失败计数器**，达 `breaker_threshold` 触发熔断（指数退避、封顶 `breaker_cooldown_max`） |
| `ACCOUNT_FAULT` | 喂熔断 | 按 msg 分野：含 `request illegal`(11140) → **禁用**；其余(14017) → **软冷却**（补完 register 可自愈，不该判死） |
| `WAF_BLOCK` | 喂熔断 | **账号级软冷却**（基数 60s + `soft_streak` 指数、封顶 `soft_rate_max`）；**不喂熔断、不 Disable**（WAF 是出口链路级拦截，熔断账号等于误伤健康号）。本项目额外的「realm 级 WAF 闸门」保留，与账号级软冷却互补 |
| `SESSION_DEAD`(12153) | 300 秒软冷却 | **禁用**（会话失效不可自愈；冷却到点后这个号仍会被选中并继续 12153） |
| `CLIENT`（未知 4xx） | 不罚号 | **连败降权**（`degrade_*`：连续 `degrade_threshold` 次临时出池）—— 此前 `degrade_*` 三个配置项与状态字段「只读不写」，等于从未生效，本次真正接上 |
| 其余（`NONE` / 内容误报 / 畸形请求体 / 11115 超长 / 图片无效） | 不罚号 | 不罚号（与上游一致） |

**为什么这是关键修复**：修正前**持续 5xx / 连不上的坏号永远不会被熔断**（`SERVER` 落在"不罚号"），
池子会一直选中它 —— 用户侧表现正是「反复失败、反复换号却总回到同一个坏号」；同时 11140 / WAF
被误当成账号熔断，会误伤健康号。

### 2. 并发与资源安全

| 位置 | 问题 | 修复 |
|---|---|---|
| `core/LogBus.kt` `clear()` | 在 `synchronized` **之外**改非线程安全的 `ArrayDeque`（与请求线程的 `log()` 竞争 → `ConcurrentModificationException` / 脏读） | 补加入锁 |
| `server/ChatHandler.kt`（4 处） | `resp.body!!` —— `okhttp3.Response.body` 可空，落空即 NPE 并冒成 500 | 新增 `bodyStreamOf(resp)` 统一安全取值，收敛为可分类的上游异常 |
| `server/ModelService.kt` | `cache/cachedAt` 非 `@Volatile` 且刷新**无互斥** → 并发刷新会各打 4 路上游探测（thundering herd）并互相覆盖 | 加 `@Volatile` + 双检锁串行化刷新 |
| `Gateway.kt` `init()` | 无锁 check-then-act；`Application.onCreate` 与 `GatewayService.onStartCommand` 都会调用，极端竞态下会双重初始化 | 加 `@Synchronized` |

### 3. 解析健壮性

`Upstream.parseEnvelope` 原先用裸 `.jsonObject`：当上游返回 **HTML 错误页**（APISIX / WAF 的 401 / 500）
或空体时抛 `IllegalArgumentException` —— release 包因 R8 剥离 kotlin-reflect 使异常消息不可读，且绕过了
`UpstreamException` 的统一分类（部分调用点没有 `runCatching` 兜底）。改为安全取对象并抛可读的上游异常。

### 4. 注释与失真描述修正

- `pool/Pool.kt`：错误处置 KDoc 重写为「与上游 `applyErrorPolicy` 的完整映射表」；WAF 分支中描述
  历史实现的时态改为现状。
- `data/CredentialTransfer.kt`：修正 `device_id` 的失真说明 —— **上游 `auth.Auth` 并没有 `DeviceID`
  字段**（全仓零命中），`device_id` 是本项目 `AuthEntry` 自有字段，只做透传保存、不参与出站；
  真正与上游对应、并注入 `X-Device-Token` 的是 `device_token`。

### 5. 审计中**核查后决定不改**的项（附理由，避免误以为遗漏）

| 项 | 不改的理由 |
|---|---|
| `pick()` 的 top5 短名单 + 成本分层（上游 `pick.go`） | 属**有意简化**：改动会直接改变选号分布与打散热点行为，风险高于收益；现有「全候选加权随机 + 全冷却兜底」已在生产验证 |
| `credit_floor` 积分保底、`cost_explore_interval` 成本探索 | 同理属未移植的非核心策略；配置项保留作前向兼容，不臆造默认行为 |
| `P_COUNTRY` / `GrowTask` / `AuthEntry.region` / `reportMpEvent` 等未被引用的符号 | 多为**序列化兼容字段**（删掉会让旧 `auths/*.json` / `state.json` 解析变化）或已标注未接入的扩展点；保留比删除更安全 |
| `ModelService` 的 `runBlocking` | 调用点全部在工作/IO 线程；改成真挂起需要动整条调用链（含面板侧），风险大于收益，本轮只修并发正确性 |

### 6. 版本

`1.6.0`（versionCode `39`）。

## 变更（1.5.9 / versionCode 38）— global 模型目录多路探测修复、global 出站头族对齐、凭证导入补齐设备字段

### 1. global「无法正确拉取模型」的根因与修复

**现象**：用 global（workbuddy.ai）账号登录后，模型列表里只有 `default-model / fast-model / balanced-model / primary-model`
几个**别名**，看不到 gpt-5.x / gemini / glm / kimi 等任何真实模型，客户端里选哪个都不好用。

**根因（已抓包实测）**：`Upstream.fetchModels` **只探测了一路** —— `GET /v3/config` + IDE UA。

同一个 global 账号、同一天的实测结果：

| 探测路 | 结果 |
|---|---|
| `/v3/config` + **桌面端 UA**（`WorkBuddy/5.5.4 WorkBuddy AI/5.5.4 CLI/2.137.1`） | **28 条**（含 gpt-6-sol/luna、grok-4.7、gemini-3.5-flash、gpt-5.6-* …） |
| `/v3/config` + **CLI UA**（`CLI/2.63.2 CodeBuddy/2.63.2`） | 22 条（含 deepseek 系列、kimi-k2.8-preview） |
| `/v3/config` + **IDE UA**（`CodeBuddyIDE/4.12.0 CodeBuddy/4.12.0`） | **仅 12 条**，过滤补全/图像类后只剩 4~5 个别名 |
| `/v2/enterprises/personal/models`（企业端点） | **18 条第三方主力模型**（gpt-5.6-*、gpt-5.5/5.4/5.3-codex、gemini-3.5-flash、glm-5.x、kimi-k3/k2.6） |
| `/console/enterprises/personal/models` | global 下 **500**（openresty / APISIX） |

结论：**同一端点在不同 UA 下下发的模型集合完全不同**，而真正的第三方模型还额外躺在**企业端点**上
—— App 这两条路**都没走**。

**修复**：`fetchModels` 改为**多路并发探测 + 按权威序并集合并**，逐字对齐上游
`internal/upstream/client.go:FetchModels`（CN 两路）与 `internal/upstream/global_models.go:probeGlobalModels`（global 四路）：

- **CN**：`/v3/config`（IDE UA）∥ 企业端点 `/console/...`；
- **global**：`/v3/config` **三路 UA**（桌面端主路 + IDE + CLI）∥ 企业端点家族（`/v2/...` 优先、`/console/...` 兜底）；
- 合并口径与上游一致：**按权威序**（各 /v3 路在前、企业端点在最后）去重，id 首次出现的条目字段权威；
- 企业端点只取 `data.agents[name=cli].models` 里的 id、再按 `data.models` 查表取全字段（对齐上游的 cli 面过滤），`disabled=true` 剔除；
- **逐路容错**：单路失败只丢该路并记日志；全部为空才报错（不回落任何静态名单，与上游「纯动态、无兜底」的产品决策一致）。

**修复前后对照**（用同一个 global 账号的真实抓包数据跑合并逻辑）：`12 条 → 37 条`，过滤非对话模型后
`≈5 条 → 30 条`：

```
default-model, fast-model, balanced-model, primary-model, deep-model,
deepseek-v4.1-flash, deepseek-v4.1-flash-sg, gpt-6-astra, hy4-preview, hy3,
kimi-k2.8-preview, gpt-5.6-sol, gpt-5.6-terra, gpt-5.6-luna, gpt-5.5, gpt-5.4,
gemini-3.5-flash, glm-5.3-flash, glm-5.3, glm-5.2, kimi-k3, kimi-k2.6, grok-4.7,
gpt-6-sol, gpt-6-luna, gpt-6.1-sol, auto-chat, enhance-1.0, o4-mini, gpt-5.3-codex
```

### 2. global 出站头族对齐上游（配套修复）

`applyHeaders` 此前对 global 域发的是「账号落盘 domain，缺失则 `X-No-Department-Info: 1`」；
而上游 `injectGlobalChatHeaders` 对 global 的 **chat** 请求是**硬编码** `X-Domain: www.workbuddy.ai`
+ `X-No-Enterprise-Id: 1`（不回退登录会话原值，对齐 intl 客户端出站头）。此外上游 `injectAttribution`
还会补一组**用量归属头**（`X-Agent-Purpose: conversation` + `X-IDE-Name/Type/Version` + `X-Product`），
缺了会被上游后台归因成「client / agentPurpose 为空」的网关特征。

本次按上述口径修正：global 的 chat 固定域头 + 补齐归属四头（**仅 chat 路径**注入，billing / models 域不变）。

### 3. 登录凭证导入 / 导出补齐设备字段（配套修复）

`CredentialTransfer` 此前**只解析** access_token / refresh_token / uid / domain / expires_at，
凭证里若带 `device_token` / `device_id` / `enterprise_id` 会被**静默丢弃**（尽管落盘模型 `AuthEntry`
一直有这三个字段）。而 `X-Device-Token` 正是国际版的**设备风控头**（上游三源注入：auth 每号 > config 全局 > 文件兜底），
跨设备迁移凭证时丢掉它就等于丢掉设备身份。

现已补齐：`buildRow` / `Row.NeedsUid` / 导入落盘 / 导出编码四处都带上这三个字段（**有才带**，
没有则维持最小凭证形态）。

### 4. 版本与构建

`1.5.9`（versionCode `38`）。

## 变更（1.5.8 / versionCode 37）— 账号详情返回动画修复、底栏高亮跟随拖动目标栏

### 1. 账号详情（二级页）返回动画修复（问题 1）

**现象**：进入账号详情后触发返回，**看不到滑出动画**（页面像被瞬间抽走）。

**根因（既有 bug，非 1.5.7 引入）**：二级页由 `AnimatedVisibility` 承载，而它的 content 内读的是
`detailUid`。`AnimatedVisibility` 在**退出动画期间会继续重组 content**，那时 `detailUid` 已变成
`null` → `if (uid != null)` 落空、**内容被瞬间清空** → 退出动画退化成"一个空盒在滑"，视觉上就等于没有动画。

**修法**：改用 `AnimatedContent(targetState = detailUid)` —— 它会把**旧状态的内容保留到退出动画结束**，
动画全程页面完整可见；两端内容各包一层 `Box(fillMaxSize)` 让容器尺寸恒定，避免默认 `SizeTransform`
（`clip` 默认 true）在"满屏 ↔ 空"之间做尺寸动画、把页面裁着缩小。
进入 / 离开分别写 `transitionSpec`（用 `ExitTransition.None` / `EnterTransition.None` 跳过空的那一侧）。

**顺带修掉同一路径上的两处观感瑕疵**：

| 瑕疵 | 原因 | 处理 |
|---|---|---|
| 退出瞬间顶栏就变回「账号」、返回箭头消失，正文却还在滑 | 顶栏标题/返回键直接读 `detailUid` | 新增 `detailBarUid`：进入立即跟随、**退出延迟 `DetailExitMs`(280ms)** 再清除；顶栏标题、返回键、动作全部改由它派生 |
| 点「移除该账号」时，滑出去的是「账号不存在或已被移除」空态页 | `vm.remove(uid)` 先改 `ov.views`，紧接着 `onRemoved()` 才置 `detailUid = null`，退出动画期间已查不到该账号 | 用 `remember(uid)` 快照兜住进入时的数据；`live` 有值就刷新它（余额等实时更新照旧生效），`live` 变 null 时保留最后一份 |

**同时**：二级页进出场现在也尊重设置页的「动画总开关」（关掉后直接切换，不再滑一下）。

### 2. 底栏高亮跟随「拖动目标栏」（问题 2）

**现象**：把遮罩从「首页」拖向「账号」时，只有**松手之后**账号栏的图标 / 文字才变蓝 —— 拖动过程中看不出"松手会切到哪一栏"。

**根因**：高亮判据是 `i == selectedIndex`，而 `selectedIndex` 是 **Pager 的当前页**；拖动期间 Pager 并未跟随
（1.5.6 起拖动只移动遮罩、不驱动 Pager），所以高亮要等松手切页完成才更新。

**修法**：高亮判据改为 `i == (pressedIdx ?: selectedIndex)` —— 拖动中 `pressedIdx` 实时跟随手指所在栏，
于是**手指拖到哪一栏、那一栏当场变色**；松手后 `pressedIdx` 会一直保持到 Pager 真正滚到目标页才交还，
全程无闪回。颜色 / 缩放本就带 200ms 过渡与 spring，跨栏时是平滑交叉。

> 一点说明：高亮的切换点 = 手指越过栏边界 = **遮罩覆盖面积最大的那一栏** = 松手后要切过去的那一栏
> （三者数学上一致，都是 `floor(x / 栏宽)`）。所以「变色的那一栏」「遮罩所在的那一栏」「松手会去的那一栏」
> 永远是同一个，不会互相错位。

### 3. 版本与构建

`1.5.8`（versionCode `37`）。

## 变更（1.5.7 / versionCode 36）— 底栏遮罩线性跟手、导入凭证后同步余额、底层优化核查、构建 API 更新

### 1. 底栏遮罩改为「连续线性跟手」（问题 1）

**现象**：缓慢拖动底栏时，手指还没到下一栏中心，遮罩就已经跳到那一栏中心 —— 观感"非线性、一顿一顿"。

**根因**：遮罩位置只取**整数栏号**（`pressedIdx`）：手指一越过栏边界，整数就变成了下一栏，遮罩于是提前吸附过去。

**修法**：拖动期间遮罩改读**连续栏号** `x / 栏宽 - 0.5`（手指位于第 k 栏中心时正好等于 k，夹在 `[0, n-1]`），
配合 `snap()` 严格跟手（不做任何插值）；松手仍按「手指所在的那一栏」切换页面。
于是「按住设置 → 滑到日志 → 松手」时：遮罩一路 1:1 跟随手指，松手后页面一次动画从**当前页**切到日志
（1.5.6 定下的「松手一次干净切换」语义保持不变）。

### 2. 粘贴登录凭证后同步余额（问题 2）

**现象**：用「添加账号 → 导入登录凭证」登录后，账号积分显示为 0。

**根因（已抓代码取证）**：OAuth 登录路径（`LoginWebActivity`）有完整的三步
`saveAuth → checkin → balanceDetailed → pool.setCredits`；
而**粘贴导入路径**（`AppViewModel.importBlocking`）只做了 `saveAuth → reload → revive → checkin`，
**唯独漏掉余额同步** —— 新账号入池时积分字段为空，界面于是显示 0。

**修法**：给导入路径补上与 OAuth 路径**逐行一致**的余额同步（`balanceDetailed` → `setCredits`），
失败仅记日志、不阻断导入。弹层文案同步改为「落盘、入池、解除冷却**并同步余额**」。

### 3. Android 底层优化核查（问题 3）

针对 Android 12L～17（API 32～37）+ AGP 9.4.1 + Kotlin 2.4.20 做过一轮**逐条核查**（联网取证官方文档），
只采纳低风险、官方在册、可验证的项目：

**① 已采纳**

| 项 | 说明 |
|---|---|
| `android:enableOnBackInvokedCallback="true"` | Android 16 起 target ≥ 36 **强制**启用预测式返回，且系统不再调用 `onBackPressed()` / 不再派发 `KEYCODE_BACK`。本应用早已使用 Compose `BackHandler`（走 `OnBackPressedDispatcher`，官方支持的返回导航 API），显式声明把意图写明，并让 Android 13～15 也走同一条路径。 |
| `<profileable android:shell="true"/>` | 让 **release 包**也能被 perfetto / simpleperf / heap dump 量测（`debuggable=false` 时默认禁止剖析，该标签只在明确要求剖析时放开）。本应用有大量自绘玻璃与常驻前台服务，线上包能量测是必要的。 |
| `dependenciesInfo { includeInApk = false }` | 不再把「依赖签名清单」写进 APK：体积更小、不在包内暴露完整依赖树。（该块只用于 Google Play 的依赖漏洞检查，本应用不走 Play。） |
| `androidResources.localeFilters` | 取代 AGP 9 已弃用的 `resourceConfigurations`（构建警告原文：*Support for resource configurations will be removed*），等效保留「只打包 zh / en 语言资源」。 |
| `android.nonFinalResIds=true` | 显式声明（AGP 8+ 的默认值），减少 R 类常量。 |

**② 核查后明确不做（附理由）**

| 项 | 不做的理由 |
|---|---|
| 16 KB 页对齐（.so 重打包） | **本项目不含任何 native 库**；官方口径是"用了 NDK 库才需要重打包"。APK 侧已用 `zipalign -c -P 16` 校验通过。 |
| 手写 `baseline-prof.txt` | 收益必须靠真机 Macrobenchmark 量化；手写清单既无法验证收益，写宽了反而增加磁盘访问（官方建议"保守"）。**而依赖自带的 baseline profile 已经随 AAR 合并进 APK**（`assets/dexopt/baseline.prof` + `.profm`，API 28+ 由系统 PMS 在安装时直接 AOT）—— 这部分收益已经在拿。 |
| `androidx.profileinstaller` | 该库是为 Android 7.0–8.1 补装 profile；minSdk 32 的系统已原生识别，加了没有意义。 |
| 升级 Compose BOM / Miuix | 全站玻璃组件高度依赖 Miuix 0.9.4 与 kyant backdrop 的实测行为，为"可能有"的收益引入回归风险不划算。 |
| `stabilityConfigurationFile` | 收益来自减少无效重组；本项目关键模型（`Overview` 等）已逐个标注 `@Immutable`，边际收益低、且该 DSL 在各版本间有差异风险。 |
| 预测式返回跟手动画（`PredictiveBackHandler`） | 需要重写返回链路，回归风险高于收益，本轮不动。 |
| 大屏自适应整改 | 项目未固定 `screenOrientation`（只声明了 `configChanges`），不存在该问题。 |
| SplashScreen 库 / `dex2oat` 调参 / Cloud Profiles | minSdk 32 平台已原生支持启动主题；`dex2oat` 与 Cloud Profiles 是系统 / Play 侧行为，应用无权配置。 |

### 4. 版本与构建

`1.5.7`（versionCode `36`）。

## 变更（1.5.6 / versionCode 35）— 底栏拖动语义修正、添加账号过场动画、首页动作、账号页「刷新余额」

### 1. 底栏拖动：改成「遮罩跟手 + 松手一次干净切换」（问题 1）

**现象**：在「首页」按住「设置」拖到「日志」松手 → 页面是**从设置滑到日志**（期望从首页滑到日志）。

**根因**（1.5.5 的修法带来的**新问题**）：1.5.5 为修「拖到日志却停在账号」，在越过 touchSlop 的
瞬间把 Pager **瞬时对齐到按下的那一栏** —— 逻辑上解掉了相对位移的错位，但视觉上就多出一次
「首页 → 设置」的抢跑，于是松手后成了「设置 → 日志」两段动画。

**修法（换掉模型，而不是再打一层补丁）**：拖动**只移动遮罩**（遮罩跟随手指当前所在的栏），
**完全不驱动 Pager**；松手时再由 `animateScrollToPage(手指所在栏)` 从**当前页**一次动画过去：

- 「首页按住设置 → 滑到日志 → 松手」＝ 遮罩依次划过 设置 / 日志（预览），松手后 **首页 → 日志** 一次干净切换；
- 这也正是分段控件（segmented control）的标准交互：按下预览、松手才生效。

配套简化：底栏的 `onDrag / onDragStart / onDragEnd` 三个回调、以及为折算像素而实测的
`pagerWidthPx` **全部删除**（不再往 Pager 喂增量）；`live` 现在只由**内容区滚动**驱动 ——
左右滑内容区时指示器依旧严格实时跟手，手感不变。

### 2. 添加账号：两个子视图之间加了过场动画（问题 2）

「选择登录方式」与「粘贴凭证」原先用 `if/else` 硬切换，割裂感很强。现在用 `AnimatedContent`
做**横向滑动 + 淡入淡出**（进入凭证页从右滑入、返回时反向），高度变化也由它自带的 size transform
平滑过渡。

### 3. 首页「重载账号」与「刷新余额」行为统一（问题 3）

两处动作现在一致：**执行完顺带切到账号栏**，让用户直接看到结果
（原先只有「刷新余额」会跳、「重载账号」不跳，行为不一致）。

### 4. 账号页顶栏：重载账号 → 刷新余额（问题 4）

- 账号页右上角的刷新按钮从「重载账号」换成 **「刷新余额」**（这才是账号页最常用的动作），
  图标就地旋转，进行中自动拒绝重复点击。
- **toast 改为报可用积分**：从原先的「刷新 3 个」变成 `可用 <积分> 积分（N 个账号）`，
  失败时追加 `· 失败 N 个`（失败账号逐个写日志，便于定位是哪一个号取不到余额）。
  只看「刷新了几个」看不出刷新结果，用户要的是"现在还剩多少可用"。

### 5. 版本

`1.5.6`（versionCode `35`）。

## 变更（1.5.5 / versionCode 34）— 微信登录的真实根因（已抓代码取证）、列表行回归修复、底栏拖动基准、代码审计

> 本版核心是**把「微信扫码登录」这件事彻底查清**（前两版方向错了），并修掉 1.5.4 引入的一处排版回归。

### 1. 微信登录为什么一直出不来二维码 —— 已抓真实前端代码取证（问题 1）

前两版都在"如何把 WebView 伪装得更像 PC"上做文章，**方向是错的**。本版直接抓取了
`www.codebuddy.cn` 登录页的**真实前端代码**，把流程完整还原：

1. 点「微信登录」→ 前端请求 `/auth/realms/copilot/console/auth/wechat-url`；
2. 服务端**签发**并返回一条链接，host 必须是 `open.weixin.qq.com`（前端会校验）——实抓到的正是：

   ```
   https://open.weixin.qq.com/connect/qrconnect?scope=snsapi_login&appid=wxd1fa324ebb4cbed1
   &redirect_uri=.../broker/weixinwebchat/endpoint&state=…&login_type=jssdk&style=white
   &href=data:text/css;base64,…   ← 前端还定制了扫码页样式（白底、二维码 210px）
   ```

   即：**这本来就是 PC 扫码登录（qrconnect）**；
3. 前端 `location.href` 跳过去；
4. **微信侧只对「微信客户端内」放行** —— 微信自己的授权页源码里就是
   `var isWeixin = ua.indexOf('micromessenger') != -1;`，非微信环境一律渲染
   「请在手机打开网页链接 / 请在微信客户端打开链接」这类提示页。

**结论：这跟 PC/移动、视口宽度、任何指纹伪装都无关。** 在 Android WebView 里（没有微信登录
通道）**永远不可能出二维码**。所以本版不再在那条路上耗：

**修法**：`shouldOverrideUrlLoading` **拦截**要跳往 `open.weixin.qq.com`（以及 `weixin://`）的请求
—— 不加载那个注定失败的微信页，而是把这条**由服务端签发、自带 state** 的链接**自动复制**到剪贴板，
并在底部亮起明确引导：

> 微信授权只能在微信客户端内完成。链接已复制：打开「微信」→ 粘贴到任意聊天窗口 → 点开 → 授权；
> 成功后本页会自动入库。（也可以直接用手机号 / 邮箱 / SSO 在本页登录）

**为什么这样一定能成**：「在哪里完成授权」与「向谁取凭证」是**解耦**的 —— 授权在微信里完成后，
服务端会把凭证挂到那个 `state` 上，本页的 `deviceAuthPoll` 轮询照样取到并自动入池。

另外：底部「复制授权链接」的提示也从"粘贴到电脑浏览器"改成了"**到微信里打开**"；诊断行现在会
**同时显示当前页面 URL**（点一下可把 URL + 环境一起复制），以后排查"到底跳去了哪一页"有一手线索。
1.5.4 那个「视口未达桌面宽度 ⚠」告警已删除 —— 取证表明视口宽度与微信登录能否成功无关，
留着只会误导排查方向。

### 2. 账号信息排版回归修复（问题 2）★

**这是 1.5.4 引入的 bug（典型的"拆东墙补西墙"）**，本版修掉。

- 1.5.4 为修「长值把标签挤成 0 宽、竖排」，给标签加了 `weight(1f, fill = value == null)`。
- 但 `fill = false` 时标签只占**内容宽**，而 Row 的**权重槽位仍是半行** —— 于是值是从
  「标签的右边界」开始排的（而不是从半行处），**值不再贴右、而是悬在中间偏左**，
  右侧留下一片空白（正是截图里看到的样子）。
- 修法：标签恢复 `weight(1f)`（占满自己的半行槽位），值 `weight(1f) + textAlign = End`。
  长值仍可换行、标签仍不会被压扁，**同时值重新贴右**。该修复对全应用所有 `WbRow` 生效。

### 3. 底栏「按下即跟随」的拖动基准错位（问题 3）

**现象**：在「模型」页按住「设置」（未松手），滑到「日志」后松手 → 却切到了「账号」。

**根因**：拖动位移是相对「Pager 的**当前页**」算的，而按下时只移动了遮罩、**并没有移动 Pager**。
于是"从设置滑到日志"这 1 栏的位移，被算成了"模型 −1 栏 = 账号"。

**修法**：越过 touchSlop、真正开始拖动的那一刻，先喂一个 `(按下栏 − 当前页)` 的增量把 Pager
**瞬时对齐到按下的那一栏**（`dispatchRawDelta` 是无动画的同步平移，不会"先跳一下"），
此后位移自然相对按下栏计算 —— 按下设置、滑到日志、松手，就是切到日志。

顺带去掉 `pressedIdx.value!!` 的不安全断言（改用局部变量）；保留原有的
「按住不动才用平滑动画、拖动/惯性期间严格实时跟手（snap）」分工，拖动手感不变。

### 4. 代码审计与清理（问题 4）

本版做了全仓审计（含未引用符号、过时注释、逻辑缺陷扫描），落地：

- **P0 · 定时任务会静默停摆**：`Scheduler` 主循环的 `runCatching { tick() }` 没有 `onFailure`
  ——`tick()` 内部要跑签到 / 成长任务 / 余额刷新等一串上游调用，任何一处抛出未捕获异常，
  都会让这个 `while(true)` 协程**永久退出**，症状是「所有定时任务停摆且日志里什么都没有」。
  已补 `onFailure` 日志（同文件其它 6 处本来就有，只主循环这两处漏了）。
- **P0 · 池状态可能丢写**：`Store.loadState()`（无锁）与 `saveState()`（`@Synchronized`）锁不对称，
  而 `loadState()` 会在登录轮询 / 导入等 IO 线程上被反复调用，与请求线程的 `putState + saveState`
  交错会丢写、甚至把刚删除的状态"复活"。已给 `loadState()` 加同一把锁。
- **删除死代码**：`AppViewModel` 里有一套**从未被调用**的 OAuth 设备授权登录实现
  （`startLogin / pollLogin / retryLogin / cancelLogin / doneLogin / loginState / LoginFlow`，约 90 行）
  —— 与真正在用的 `LoginWebActivity` 形成"两套并存"，是最容易改错地方的陷阱。已删除。
- **安全**：`Ids.apiKey()` 原用 `java.util.Random`（48 位种子、可预测）生成网关密钥，已改为
  `SecureRandom`（与同仓库的 clientToken 对齐）。
- 清掉一处字面 `**`（提示块用纯 `Text`，不渲染 Markdown，星号会原样显示给用户）。

审计中发现的**尚未处理**项（下一轮候选）：`Store.readRequestLogs` 对最大 100MB 归档用
`readLines()` 全量读入（OOM 风险）、`Upstream.refresh()` 零调用（令牌自动续期链路缺失）、
27 处未使用 import、`GrowthQueue` 的 `getOrPut` 非原子等。

### 5. 版本

`1.5.5`（versionCode `34`）。

## 变更（1.5.4 / versionCode 33）— 真正的桌面视口、「最小凭证」、成长任务进度、底栏按下跟随

### 1. 添加账号：微信登录终于能出二维码了（问题 1）★

这一版找到了**真正的根因**，前面两版都判断偏了。

**根因**：Android 官方文档《Migrating to WebView in Android 4.4》写明 ——

> Previously, if you included multiple viewport tags in a web page, WebView would **merge** the
> properties from all the tags. **In the new WebView, only the last viewport is used and all others
> are ignored.**

也就是说：页面里存在多条 `<meta name="viewport">` 时，WebView **只取 DOM 里的最后一条**
（Blink 源码 `viewport_data.h` 的 `ShouldOverrideLegacyDescription` 用 `>=` 比较，后写者胜）。

而此前是把 `width=1280` **插在 `<head>` 的开头** —— 授权页自带的那条
`<meta name="viewport" content="width=device-width, initial-scale=1">` 排在它**后面**，
于是**页面那条胜出**，CSS 视口仍等于设备宽度。实测环境把这一点钉死了：

```
环境: {"ua":"...Windows NT 10.0... Chrome/131...", "plat":"Win32", "touch":0,
      "iw":394, "ih":581, "sw":1920, "sh":1080, "dpr":1}
```

UA / 平台 / 触摸 / 屏幕 / dpr **全部伪装成功**，唯独 `iw:394` 没达成 —— 页面因此仍判为移动端。

**修法（两道防线）**：

1. **静态改写主文档**（决定性的一道）：在 `shouldInterceptRequest` 改写 HTML 时，先用正则
   **删掉页面里全部 viewport meta**，再在 `</head>` 前插入**唯一**一条
   `<meta name="viewport" content="width=1280">`，让它成为 DOM 里最后（也是唯一）的那一条。
   配合原有的 `useWideViewPort = true`，`window.innerWidth` 即稳定为 1280（PC 分界是 1070px）。
2. **运行时兜底**：注入脚本里带一个 `MutationObserver`，页面脚本若**动态**写回自己的 viewport
   meta 就立刻收敛掉；`onPageFinished` 时再主动跑一次（Chromium 支持运行时改 viewport 并重排）。

**刻意不写 `initial-scale`**：留空才能让 `loadWithOverviewMode` 生效，把 1280 宽的桌面页面
整体缩放到手机屏宽之内 —— 用户看得全，而 `innerWidth` 仍是 1280。

顺带把 PC 指纹再补两维：`window.chrome`（真 Chrome 才有）与 WebGL 的
`UNMASKED_VENDOR/RENDERER`（不补会暴露成 Adreno / Mali 移动 GPU）。

**环境诊断行新增告警**：读到 `iw < 1070` 时直接标出 ⚠ —— 一眼看出是哪一维没达成。

### 2. 凭证登录：「最小凭证」+ 长文本不再挤爆布局（问题 2）

- **导出**：账号详情页「复制登录凭证」现在只复制**两个字段** ——
  `{"access_token":"…","refresh_token":"…"}`。其余信息都能在导入侧补齐，
  没必要把一长串元数据塞给用户（更短、更好抄、暴露面更小）。
- **导入**：只要求 `access_token` + `refresh_token`。uid 缺失时自动用 access_token 调
  `/v2/plugin/login/account` 换回 uid / nickname 再落盘（换不到就明确报
  「无法用 access_token 解析账号」，绝不静默丢弃、也不拿 refresh_token 硬凑一个假 uid）。
  上游完整的 cockpit 对象 / 数组依然照收。
- **布局修复**：该弹层内容现在可**纵向滚动**，输入框也加了高度上限
  （`heightIn(max = 168.dp)`，超出在框内滚动）。此前粘贴长凭证会把「导入」按钮一路顶出屏幕，
  根本点不到。

### 3. 成长任务：不再「干等」，且「上次结果」排版修正（问题 3）

- **实时进度**：扫描是「多账号 × 分片报名 + 行为事件上报 + 回读 + 领奖」的长链路，可能几十秒到
  几分钟。现在**逐个账号上报进度**：按钮进入图标就地旋转的进行中态，下方实时显示
  `(2/3) 正在处理 xxx …`，并且每一步都写进**日志页**。
- **「上次结果」竖排修复**：根因在通用列表行 `WbRow` —— 它的标签带 `weight(1f)`、值却不带权重，
  于是**值很长时标签被压成 0 宽**，「上次结果」四个字只能逐字换行、竖着排成一列（正是截图里的
  现象）。现在：标签单行 + 省略号；值占剩余宽度、可换行、右对齐。
  **该修复对全应用所有 `WbRow` 生效。**
- 汇总摘要改为**独立成行**（它很长，塞在列表行的右值位会挤成好几行）。
- 顺带修掉提示块正文里字面显示的 `**` —— `WbHintBlock` 用的是纯 `Text`，不解析 Markdown，
  星号会被原样显示出来。

### 4. 底栏：按下即跟随（问题 4）

按住某一栏（还没松手）时，「抬起的玻璃遮罩」现在就**平滑移到那一栏**，而不再死守原选中项。

实现要点（刻意避免踩坏原有的跟手体验）：

- 按下 → 遮罩平滑移过去；松手时 Pager 的切换动画接力，两段首尾相接，**中途不闪回旧栏**；
  松手后若没真正切换（滑出取消），遮罩**平滑退回**原栏，不会卡住。
- **拖动 / 惯性滚动期间仍然严格实时跟手**（`snap`，无补间），只有「按住不动」才用平滑动画 ——
  由新增的 `live` 参数（`pagerState.isScrollInProgress || dragging`）区分。这是为了**不改变**
  原本 1:1 的拖动手感。
- 按住不动时按压力度也推满，遮罩真的「涨起来」；手势被打断时用 `finally` 清理按下态，
  不会永久停在按下的那一栏。

### 5. 版本

`1.5.4`（versionCode `33`）。

## 变更（1.5.3 / versionCode 32）— 登录会话隔离、真实 PC 化 + 复制授权链接、凭证导入导出、代码审查落地

### 1. 添加账号：隔离上次会话（问题 1）

**现象**：登录过一个账号后再点「添加账号」，会**直接导入上次那个账号的凭证**，无法登录第二个账号。

**根因**：Cookie / WebStorage / 缓存都是**进程级共享**的 —— 第二次进入登录页时页面带着上一次的
登录态，于是授权轮询一开始就能取到上一次的凭证。

**修复**：每次进入 `LoginWebActivity` 先清 `CookieManager`（`removeAllCookies` +
`removeSessionCookies` + `flush`）与 `WebStorage.deleteAllData()`。副作用可控 —— 本应用只有这一个
WebView，不存在需要保留的网页数据。

### 2. 添加账号：真正的桌面化 +「复制授权链接」（问题 3）★

**结论先行**：只改 UA 不够，只补几个 `navigator` 属性也不够。联网核实后，现代站点用的是
**四维设备指纹联合判定** —— `UA` + `screen.width/height` + `navigator.platform` +
`matchMedia`/视口宽度，再加上触摸能力（`'ontouchstart' in window`）。WebView 在**每一个**维度都露馅，
所以才会落到「请在手机打开网页链接」的兜底页。

本次抹平的全部维度：

| 维度 | WebView 默认值 | 现在伪装为 |
|---|---|---|
| `User-Agent` | Android WebView | Windows Chrome |
| **CSS 视口宽度** | 980px（页面带 `width=device-width` 时 ≈400px） | **1280px**（注入 viewport meta） |
| `navigator.platform` | `Linux armv8l` | `Win32` |
| `navigator.maxTouchPoints` | >0 | `0` |
| `window.ontouchstart` | 存在 | 删除 |
| `screen.width/height` | 设备物理像素 | `1920 × 1080` |
| `window.devicePixelRatio` | 2~3 | `1` |
| `window.outerWidth/Height` | 0 或设备值 | `1920 × 1080` |
| `navigator.userAgentData` | 带 Android | 屏蔽 |

**并且新增了确定性兜底：底部「复制授权链接」主按钮** —— 这正是上游面板的做法
（`app.js` 把授权 URL 展示出来并提供 `btnCopyUrl` / `btnOpenUrl`）。授权链接自带 `state`，
在**任意设备的浏览器**里完成授权，App 的轮询都能取到凭证 —— 所以「复制 → 贴到电脑浏览器 → 扫码」
是一条 100% 可行的路径（手机端浏览器 UA 会被腾讯判为移动端而不出码，这正是你遇到的现象）。

**新增环境诊断行**：页面加载完成后，底部显示**页面实际看到的环境**（UA / platform / touch /
innerWidth / screen / dpr），点一下即可复制。以后再出现异常，这一行就是一手线索，不必再靠猜。

### 3. 凭证导入 / 导出（问题 2）★

对齐上游 `internal/panel/import.go` 的 `importCockpit` 口径：

- **导入**：「添加账号」弹层新增「导入登录凭证（粘贴 JSON）」入口，支持**单个对象或数组批量**；
  必填 `uid + access_token + refresh_token`；`expires_at` 兼容毫秒（上游为毫秒）与秒；
  realm 由 `domain` 推断（`workbuddy.ai` 家族 → global），无 `domain` 时按用户所选 CN/Global 兜底；
  导入后**落盘 + 入池 + 解除冷却**，CN 域 best-effort 顺带签到（幂等，失败不阻断导入）；
  结果按上游 `{total, imported, skipped, errors}` 口径给出明细。
- **导出**：账号详情页新增「复制登录凭证」（字段名与上游 `cockpitAccount` 对齐），
  并配红色语义的**敏感提示块**（凭证等同账号，请勿外传）。

### 4. 代码审查落地（问题 4）

按审查报告修了 **2 个 P0** 与若干高价值 P1：

- **P0-1 用量记到错误账号**（`ChatHandler`）：`tried` 是 `HashSet`（无插入序），
  对它 `lastOrNull()` 得到的既不是「最后尝试的账号」也不是「服务成功的账号」→
  改为显式记录 `servedUid`。
- **P0-2 已开流后异常导致客户端挂死**（`HttpServer`）：handler 抛异常时写 500 会被 `Res.sent` 挡掉，
  于是**既没有错误响应、也没有终止块**，客户端只能干等到 60s 超时 → 先补 `endStream()` 收尾
  （这也让此前声明却零引用的 `isStreaming` 真正派上用场）。
- **注释与代码矛盾**（`Scheduler`）：注释仍称成长任务 / 活跃上报「未接入」，实际 1.5.1~1.5.2 已接入 → 改正。
- **默认值冲突**（`Theme`）：`WbTheme(monet = true)` 与 `UiCfg.monet = false` 恰好相反 → 统一为 `false`。
- **死代码标注**（`GrowthQueue`）：零引用的队列模型文件加显著「未接入」说明，
  避免被误当成「正在运行的能力」。

### 5. 版本

`1.5.3`（versionCode `32`）。

## 变更（1.5.2 / versionCode 31）— 成长任务「真实移植」、登录页真正的桌面化

### 1. 成长任务：从「三件套」到真实移植（问题 1）★

1.5.1 只做了「列表 → 报名 → 领奖」，那是**空转** —— 上游的成长任务靠**服务端行为事件**点亮进度，
  不产生事件就永远拿不到可领奖的任务。本版按上游 `workbuddy2api-panel` 逐文件对齐后补齐
  （依据 `internal/upstream/{tasks,report,desktop,streak}.go`、`internal/panel/{autotask,taskcenter,tasks}.go`）：

**① 修正领奖路径（原实现是错的）**
原实现走 chat 域 `{chatBase}/v2/activity/growth/tasks/<code>/claim`；上游该路径只在 **Web 域**
`www.workbuddy.cn/activity/growth/tasks/<code>/claim` 存在（`tasks.go:216-253`），且要求
`x-client-platform: web`、**task_code 在路径、无请求体**，chat 域同名路径返回 400。
已按此改写，并保留 mp 路径的 chat→Web 降级。

**② 接入行为事件上报（进度的唯一来源）**
实现 `/v2/report` 的三条通道：billing / 桌面指纹 / web 指纹（外加 mp 通道备用）。
硬性约束：事件体必须含 `userId`，否则上游**返回 200 但静默丢弃**（`report.go:2-4`）。

**③ 自动化动作表**
实现 `autoActions` 中不依赖小程序 / 旅行 / 专家对话即可完成的 11 个动作：`chat_5`、`first_buddy`、
`RichMeow_Chat`、`Buddy_App`、`Buddy_App_QQ`、`automation_1`、`Library_read`、`template_5`、
`playbook_prompt`、`create_canvas`、`Hp_Appearance`。每个动作都有执行步骤、完成判据（回读）、
幂等键与逐项失败处理。

**④ 回读验证与节流**
accept 后**回读登记状态**（不再把「HTTP 200 但没落账」当成功）；accept 分片每批 20、
批间 1050ms（`acceptBatchGap`）；claim 异步计分轮询 4 次 × 3s（`claimPollAttempts` / `claimPollGap`）。

**⑤ 小程序（mp）口径**
列表**合并去重**（mp 是默认口径的超集）+ mp accept / claim。mp 的 mini chat 事件链**未实施** ——
它要求 45s + 抖动的**真人节奏**，数秒级连发会被反作弊整体回滚，移动端一键流程跑 45s×N 风险过高
（代码注释已写明原因）。

**⑥ 连登闭环**
连登状态 → 补签 → 礼包/补偿 → 兑换已解锁档位 → 抽完抽奖次数（对齐 `streak.go` 与
`scheduler/streak.go`），挂在签到排程末尾，幂等（未解锁静默跳过）。

**⑦ 队列执行模型与账号门控**
并发夹取 [1,4] + per-account 互斥锁 + `seq`；门控**改为对齐 Go 口径**：跳过 `Disabled` 与
`global` 账号（1.5.1 是按企业号过滤 —— 两者口径不同，这次以 Go 为准）。

> **仍然不做**（如实列出）：需真实对话的任务（`Model_chat_GLM5.2`、专家市场 `Expert_*`）、
> 猫猫旅行、夜猫子、开学季券码 —— 上游把它们放在另一套链路里，属增量能力。

### 2. 登录页：真正的桌面化（问题 2）★

**现象**：设置 PC UA 后，点微信登录不再走「唤起微信 App」，但仍不出二维码，而是显示
「请在手机打开网页链接」。

**根因**：只改 UA 不够 —— WebView 在页面眼里是「UA 说自己是 PC、其余特征全是手机」的**矛盾环境**，
于是落到兜底提示。证据链：

1. 上游 Go 面板（`panel/login.go`）用的是**同一个** authUrl（同为 `?platform=CLI`），在真实 PC
   浏览器里能正常出码 → 差异只在浏览器环境，不在 URL；
2. 页面按**视口宽度**分流（≤1070px 判为移动端），而 WebView 默认 CSS 视口是 980px，
   **正好落在分界之下**；若页面自带 `width=device-width` 则只有约 400px；
3. WebView 默认暴露 `navigator.platform = "Linux armv8l"`、`maxTouchPoints > 0`、
   `userAgentData` 带 Android —— 与 PC UA 自相矛盾。

**修复**：三处同时抹平，且注入必须**先于页面任何脚本**：

- `viewport width=1280` + `initial-scale=1`（把 CSS 视口抬到桌面宽度，跨过 1070px 分界）；
- `navigator.platform / maxTouchPoints / vendor` 改为桌面值，并屏蔽 `userAgentData`；
- 注入方式：在 `shouldInterceptRequest` 里改写**主文档**（只拦 main frame 的 GET，子资源与接口
  原样透传），配合 OkHttp 内存 CookieJar 与 `Set-Cookie` 回写 `CookieManager`，并开启
  **第三方 Cookie**（登录态跨域传递依赖它）；
- 任何异常都**回退到 WebView 默认加载** —— 绝不能因为注入失败把登录页变成白屏。

> 说明：项目构建是离线的，无法新增 `androidx.webkit`（其 `addDocumentStartJavaScript` 是官方
> 「文档开始前注入」入口），因此改用上述无依赖方案，效果等价。

### 3. 版本

`1.5.2`（versionCode `31`）。

## 变更（1.5.1 / versionCode 30）— 成长任务接入、刷新反馈、状态过渡动画、登录 UA

### 1. 刷新模型：写入日志（问题 1）

`loadModels` 现在把结果写进日志：`[模型] 刷新模型：共 N 个（CN x · global y）`；
无账号 / 未取到模型也各有明确日志行。分域计数是刻意加的 —— 排查「某个域的目录为空」时，
这是第一手线索。

### 2. 刷新模型：列表过场动画（问题 2）

即使上游目录没有任何变化，列表也会整体播一次「淡入 + 上移 12dp」（alpha 0.30→1、
位移 12dp→0，340–420ms，`FastOutSlowInEasing`）。刷新脉冲由 `AppViewModel.modelsPulse`
在每次刷新完成后自增。

为什么值得做：内容不变时界面**毫无反馈**，用户会以为「刷新按钮没反应」；给一次可感知的过场
是对「我点了、它确实做了事」的明确回应。**不假造数据、不改任何真实状态**，纯视觉反馈。

### 3. 重载账号：报出实际数量（问题 3）

`toast("账号已重载")` → `toast("已重载 N 个账号")`（N = 0 时仍是「目录下没有账号」），
并写入日志 `[账号] 重载账号池：共 N 个账号`。

### 4. 网关状态卡底色过渡动画（问题 4）

原实现虽然用了 `animateColorAsState`，但**时长 380ms 且不带缓动**（线性），加上底色透明度只有
8%（浅色主题下几乎读不出色相）—— 观感就是「啪」地一下硬切换。现在：

- 时长 380 → **560ms**，并显式使用 **`FastOutSlowInEasing`**（快起慢收，结束不顿挫）；
- 透色 alpha 8% → **11%**（浅色主题下才真正看得出色相在变）；
- 文案 / 按钮的交叉淡入同步拉长到 360 / 240ms —— 否则会出现「底色还在过渡、文字已经换完」的割裂感。

### 5. 成长任务接入（问题 5）★

设置 → 定时任务 里那张「未接入上游的任务」提示**已经过时**。逐条对齐上游
workbuddy2api-panel 的 `internal/upstream/tasks.go` 与 `internal/panel/tasks.go` 后接入：

| 动作 | 上游端点 | 语义要点 |
|---|---|---|
| 列表 | `GET /v2/activity/growth/tasks` | growth 域，**billing 头族**（Go 的 `growthJSON` 用的是 `BillingHeaders`，不是 chat 头族） |
| 报名 | `POST /v2/activity/growth/tasks/accept` | body `{"task_codes":[...]}`；accept **只是报名、不产生进度**，因此可幂等重放 |
| 领奖 | `POST /v2/activity/growth/tasks/{code}/claim` | 仅进度达标可领；重复领取由上游返回业务错误（Go 注释：**安全**，不需要前置状态判断） |

新增：

- `Upstream.growthCall/growthAccept/growthClaim`（growth 域调用，逐字对齐 Go 的 header 与路径口径）；
- `server/GrowthService.kt`：扫描 → 报名未报名的 → 领取已达标的 → 逐账号汇总；
- 设置页原来的「未接入」提示块换成**「成长任务（已接入上游）」卡片**：一键「扫描并领取成长任务」，
  并逐条列出每个账号的「任务 / 报名 / 领奖 / 积分」结果；
- 定时任务里的「成长任务」开关从「打印『未接入』」改为**真正执行**（结果同样落日志）。

企业版账号自动跳过 —— 上游对 growth 系列在企业号上返回 403（无个人成长体系）。

> 仍未接入的是另外四项：活跃上报 / 猫猫旅行 / token 保活 / 夜猫子。提示文案已把这四项与
> 成长任务**分开列出**，不再一句话把成长任务也打成「未接入」。

### 6. 添加账号：WebView 使用桌面端 UA（问题 6）

微信扫码登录**只在桌面浏览器 UA 下渲染二维码**；移动端 UA 会走「唤起微信 App」的链路，
而纯 WebView 里没有微信回调通道，页面干脆不提供扫码入口。已核实这一行为（微信开放平台文档与
多份实测说明：「其他手机浏览器打开 → 跳转微信的扫码登录页面」；「网页微信不支持在平板或手机
浏览器中直接访问，仅限 Windows/macOS 桌面浏览器」）。

现在把登录 WebView 的 UA 显式覆盖为桌面 Chrome：

```
Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36
```

### 7. 版本

`1.5.1`（versionCode `30`）。

## 变更（1.5.0 / versionCode 29）— 后台保活跳转对齐官方「无限制」入口、启动日志区分并发维度

### 1.「后台保活设置」跳转（设置 → 服务）（问题 2）

**旧行为**：点击后跳 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`（**电池优化列表页**），
用户得自己在列表里翻找本应用；部分 ROM 把它映射到「电池使用情况」这类**纯展示页**，于是
出现「点了之后根本没有能选『无限制』的地方」。

**新行为**：首选 **`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `package:` URI**。依据
Android 官方 Settings API 文档原文：

> Activity Action: Ask the user to allow an app to ignore battery optimizations …
> **Input: The Intent's data URI must specify the application package name to be shown, with
> the "package" scheme. That is "package:com.my.app".**
> For an app to use this, it also must hold the
> `Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission.

系统会直接弹出「是否允许 wb2api 忽略电池优化？」确认框，点「允许」即一步设为「无限制」
（对应 Manifest 里的 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，本工程已声明；该权限为
normal 级，安装即授予、无需运行时申请）。

**三级回落**（定制 ROM 可能缺少某一项，逐级降级、绝不静默失败）：

1. `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（带 `package:`）—— 首选，一步到位；
2. `ACTION_APPLICATION_DETAILS_SETTINGS`（带 `package:`）—— 应用详情页，用户可在
   「电池 / 省电策略」里选「无限制」；
3. `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` —— 老 ROM 的等价入口；
4. 三者都不可用 → 明确给出可照做的手动路径，而不是「点了没反应」。

已豁免时点击不再重复跳转，直接提示「本应用已不受电池优化限制」；设置页该行文案同步改为
「省电策略 · 无限制」。

### 2. 启动日志区分并发维度（问题 3）

日志原为 `http server listening on 0.0.0.0:7863（并发上限 32）`。其中的 **32 是「连接并发」**
（HTTP 工作线程上限），与设置页的「单账号最大并发」「global 账号并发」（**账号维度**：同一
账号同时在跑的请求数上限）**不是同一个东西** —— 只写一个数字会被读成「设置页里的并发上限
是 32」，与实际配置对不上。

现在两个维度都打印、各自标注口径，且账号维度**取当下生效的配置值**（每次启动时读取，
不硬编码）：

`http server listening on 0.0.0.0:7863（连接并发 32 · 单账号 5 / global 7）`

### 3. 版本

`1.5.0`（versionCode `29`）。

### 备注：模型列表「多模态」标签（问题 1，本版未改动）

按要求本版**未处理**。已完成的核查结论（留档，供后续定位）：App 的「多模态」标签
**直接透传**上游 `/v3/config` 的 `supportsImages` 字段，与上游 Go 的
`SupportsImages: m.SupportsImages`（client.go 的 `dynModelEntry.modelInfo()`）逐字等价，
代码层既没有写死、也没有推断逻辑。另外查证到一处**与上游的真实差异**：上游拉模型是
**两路并发合并**（`/v3/config` + `/console/enterprises/personal/models`，各自容错、
按模型 id 去重、v3 优先），App 目前只走 `/v3/config` 单路，会漏掉只出现在企业端点的模型 ——
待确认口径后一并处理。

## 变更（1.4.9 / versionCode 28）— 网关连接超时根因修复、顶栏标识排版、并发限量调整

> 本版修的是 **1.4.7 引入的回归**：当时为防「无上限线程池」换成有界池，但池的配置方式把并发
> 处理能力压成了 **1 路连接** —— 这才是「启动网关后访问 7863 有概率超时」的真正原因。
> 结论由 JDK 官方文档与 Miuix AAR 反编译**实证**，不靠猜。

### 1. 网关连接超时 / 不稳定（问题 1）★★★ 回归修复

**现象**：启动网关后访问 `http://127.0.0.1:7863/...` 大概率超时、时好时坏。

**根因（三条叠加，都在 `EmbeddedHttpServer`）**：

1. **accept 循环与连接处理共用同一个线程池 → 并发被压成 1 路。**
   1.4.7 起的配置是 `ThreadPoolExecutor(core=2, max=32, LinkedBlockingQueue(64), CallerRunsPolicy)`，
   而接受连接的循环 `ex.execute { while (...) ss.accept() }` 也提交进了**同一个池**。
   JDK 文档对提交行为的原文规定是：「如果运行的线程少于 corePoolSize，则创建新线程……
   如果运行的线程多于 corePoolSize 而少于 maximumPoolSize，则**仅当队列满时才创建新线程**」。
   accept 循环自己先占掉 1 个线程 → 池里**只剩 1 个线程**真正处理连接，第 2 个并发连接
   只能进队列。
2. **每个连接都是 keep-alive 长连接，占线程时间以「秒」计**（从建连一直占到客户端关闭或
   空闲 60 s）。于是队列里的连接要等前一个连接**释放**才可能被处理 —— 客户端干等数十秒，
   就是你看到的「超时」。浏览器/客户端本来就会同时开多条连接，所以「复现概率很大」。
3. **队列满后 CallerRunsPolicy 让提交线程（也就是 accept 循环自己）去执行处理函数**，
   accept 就此停摆最长 60 s，新连接全部堆在内核 backlog 里 —— 雪上加霜。

**修复**：

- **accept 循环独占一个线程**（`wb2api-accept`），彻底移出工作池；
- 工作池改 `SynchronousQueue + AbortPolicy`（core 8 / max 32 + `allowCoreThreadTimeOut`）：
  空闲线程不足时**直接新建**线程（直到上限），达上限则**立刻拒绝**并回 `503 server_busy`。
  「明确失败」永远优于「静默排队到超时」；静默期线程全部回收，不占内存；
- **accept 异常退避**：原来 `catch {}` 后立刻重试，遇到 EMFILE（fd 耗尽）这类持续性错误会变成
  100% CPU 的忙循环，而且因为一直失败永远恢复不了；现在记录日志并 `sleep(200ms)`；
- **工作池拒绝时立刻回 503 并关闭连接**（否则连接悬挂，又变成「看起来一样的超时」）；
- **监听 backlog 显式设为 128**（默认 50 在建连突发时偏小）；
- **`TCP_NODELAY = true`**：关闭 Nagle 算法，小响应（`/v1/models`、401/404）与 SSE 首 chunk
  不再被攒包平白拖几十毫秒；
- **连接层异常分型记录**：IO 异常（客户端断开 / 空闲超时）静默；非 IO 异常落日志。
  原实现把所有异常一起吞掉 —— 这正是这类问题「只能靠猜」的直接原因。

**keep-alive 60 s 保持不变**：服务端空闲超时**短于**客户端（OkHttp 默认 5 min）才是正确方向，
由服务端先关，客户端就不会复用一条已被对端关闭的连接。

### 2. 顶栏「应用名 + 版本」字号与排版（问题 2）

- **字号 11sp → 13sp，并加 Medium 字重**。11sp 是 `footnote2` 的实测值（反编译 Miuix 0.9.4
  AAR 取得：footnote2 = **11sp**、footnote1 = 13sp、body2 = 14sp、title4 = 18sp）—— 低于 12sp
  的可读下限，与 18sp 的页面标题并排时落差过大，这就是「太小、跟页面不搭」的直接原因。
  13sp + Medium 清晰可读，又仍低于标题，保持「环境标识」的从属层级；
- **居中方式从 Box 叠层改为「两侧等 `weight(1f)` + 中间自然宽」**：叠层只能把元素对齐到中心点，
  左侧标题一长（如「账号详情」）就会和徽标**重叠**（叠层没有互相避让的机制）；等权分配让两侧
  自动收缩（标题与徽标各自省略号），徽标既严格居中、又不会被压到；徽标另加
  `widthIn(max = 128dp)` + 省略号作为防御性约束。

### 3. 并发限量调整（问题 3）

| 项 | 原值 | 新值 |
|---|---|---|
| 单账号最大并发 · 默认 | 3 | **5** |
| 单账号最大并发 · 可调上限 | 10 | **20** |
| global 账号并发 · 默认 | 2 | **7** |
| global 账号并发 · 可调上限 | 10 | **20** |

已保存过配置的实例不受影响（改动只作用于默认值与滑块上限）；「恢复默认设置」后取新默认值。

### 4. 版本

`1.4.9`（versionCode `28`）。其余构建配置与 1.4.8 相同。

## 变更（1.4.8 / versionCode 27）— /v1/responses 连续对话 500 修复、首页动作格居中、顶栏加应用标识

> 本版含一个**崩溃级修复**（多轮对话必现的 500）：先查权威规范、再改代码，
> 并把同一类「类型假设错误」的隐患在全仓一次性清掉。

### 1. `/v1/responses` 连续对话后 500 —— 根因与修复（问题 1）★★

**现象**：`HTTP 500: Element class pn0 (Kotlin reflection is not available) is not a JsonPrimitive`

**根因**：
- 这行消息来自 kotlinx.serialization 的 `JsonElement.jsonPrimitive` —— 它在元素**不是**
  JsonPrimitive（对象 / 数组）时**抛 IllegalArgumentException**（`?.` 只防 null、不防类型不符）；
  release 包因 R8 剥离 kotlin-reflect，类名只能显示成混淆名 `pn0`，消息才长这样。
- **为什么偏偏「连续对话」才炸**：按官方规范（`openai/openai-openapi` 的 `InputItem` 定义
  与官方 Cookbook），Responses 请求在多轮场景下 `input` 数组会携带历史 item，其中
  - `function_call_output.output` = **string ｜ array**（多模态工具结果）
  - `message.content` = **string ｜ array**（`input_text` / `input_image` / `input_file` …）
  - `reasoning.summary` / `reasoning.content` = **数组**（`summary_text` / `reasoning_text` part）

  单轮（纯文本 prompt）碰不到这些字段 —— 所以只有第二轮起才暴露。

**修复（三层）**：
1. **根因层**：新增**不抛异常**的安全读取扩展 `JsonElement?.primText()` /
   `primTextOrNull()` / `objOrNull()`（`core/Json.kt`），把全仓 **55 处**
   `?.jsonPrimitive?.content`、`?.jsonPrimitive?.strOrNull`、`it.jsonPrimitive.content`、
   `?.jsonObject` 统一替换过去。语义与原来**逐一等价**（含 JsonNull 的取值行为），
   只是类型不符时**返回 null 而不是抛异常**。覆盖：`ChatHandler` 34+6 处、`Upstream` 13+4 处、
   `ModelService` 4 处、`Rewrite` 2 处、`Store` 1 处。
2. **业务层**：`summaryText()` 原来只读 `reasoning.summary` 数组，却把 `reasoning.content`
   **当字符串**追加 —— content 为数组时直接命中同一个异常（客户端回传 reasoning item 即触发）。
   现在两者都按数组提取 `text`，最后才退回「content 是字符串」的历史形态。
3. **防复发**：替换后重新 grep 全仓，确认不再有任何 `jsonPrimitive` / `jsonObject` 直读。

> 为什么选「宽容兜底」而不是「严格抛错」：网关是**兼容层**，客户端实现千差万别；
> 读不到就当空、其余内容照常转给上游，远好于让整条请求 500。

### 2. 首页「账号概览」动作格内容居中（问题 2）

`WbActionCell` 原来是「图标 + 文字」从左边开始排 —— 两列里「全量签到 / 刷新余额」字数不同，
两格的图标起始位置一左一右错开，整体看就是没对齐、视觉割裂。现在给整格加
`Arrangement.Center`：「图标 + 文字」作为**一个整体**在格内居中；`Modifier.weight(1f)`
保证两格等宽，所以左右两格严格对称。

### 3. 五个顶栏中央显示「应用名 + 版本」（问题 3）

- `WbGlassTopBar` 从 `Row` 顺序排布改为 **Box 叠层**：中央标识相对**整条顶栏**居中，
  不会因为左侧「返回键 / 标题」与右侧动作按钮宽度不同而被推偏；
- 文案**动态获取**：名称取自 `PackageManager.getApplicationLabel(applicationInfo)`（跟随实际
  安装的 label），版本取自 `BuildConfig.VERSION_NAME` —— 改 `versionName`、改应用名后顶栏
  自动跟着变，全工程零硬编码；
- 样式用 `footnote2` + 次要色：它是「环境标识」，不与页面标题争主次。

### 4. 顺带修复（审查中发现的同类隐患）

- `JsonElement.jsonObject` 与 `jsonPrimitive` 同源（非对象即抛异常）—— 已一并安全化
  （`?.jsonObject` → `?.objOrNull()`，落在流式解析循环里的那几处尤其危险：上游偶发非对象帧会让整请求失败）；
- 删掉 `PanelApi.kt` / `HttpServer.kt` 里两个**从未使用**的 `jsonPrimitive` import。

### 5. 版本

`1.4.8`（versionCode `27`）。其余构建配置与 1.4.7 相同（R8 + 资源缩减、仅 v3 签名、
仅 `arm64-v8a`、`.so` 不压缩 + 16 KB 页对齐）。

## 变更（1.4.7 / versionCode 26）— 首页「账号概览」合并重构、启动/内存/并发加固、全量代码审查

> 本版按「企业级交付」推进：先联网核对官方一手文档、再做一次全量只读代码审查，然后
> **只实施风险可控、且能静态验证的改动**；凡是「必须真机回归才能确认」的优化一律记录、
> 不冒险改（见第 6 节），避免拆东墙补西墙。

### 1. 首页：两张卡合并为一张「账号概览」（问题 1）★

**诊断**：「快捷操作」与「健康总览」是首页里**唯一**使用「内嵌异色块」的两张卡
（`surfaceContainerHighest` 铺底），而页面其它卡片统一是「卡片底色 + 行 + 图标底座」——
卡片内部再嵌一层异色块等于引入了第三种视觉语言，取色与布局因此显得突兀。

**改法**（取消全部内嵌色块，回到页面既有语言）：

- 两卡**合并为一张**「账号概览」：上半是账号健康指标、下半是维护动作，中间用 1dp 横线分隔
  —— 逻辑上它们本就是同一件事（先看池子状态、再对池子做维护），拆成两张卡只是多一层层级；
- 指标段：4 等分，数字用语义色 `title3`、标签用灰 `footnote2`，段间 1dp 发丝竖线（不再铺底）；
- 动作区：2×2，每格 = **`WbIconTile` 图标底座（主色 16% 圆角底 + 主色图标）+ 中性文字**，
  与「运行指标」卡完全同一套语言；可点性与按压缩放仍由全局 `wbPress` 提供；
- 数值变化仍保留轻微上移淡入（`AnimatedContent`），可在设置里关掉动画。

### 2. 冷启动与运行时优化（问题 2 / 3）★★

**a. 删掉两个未使用依赖 —— 其中一个直接省冷启动**

- `androidx.work:work-runtime-ktx`：全项目零引用，但 WorkManager 会注册 **App Startup 的
  `WorkManagerInitializer`**，让 `InitializationProvider`（ContentProvider）在每次冷启动时多跑一段
  初始化 —— 删掉即从启动路径上摘掉这一项。已用 `aapt2 dump xmltree` 核对：包内
  `InitializationProvider` 的 initializer 只剩 `ProfileInstallerInitializer`，
  `androidx.work` / `WorkManager` 相关类与字符串均已为零；
- `top.yukonga.miuix.kmp:miuix-preference-android`：零引用，移除（减包体）。

> **如实说明（一处与预期不同的核对结果）**：`androidx.profileinstaller` 的显式声明也删了，
> 但它其实由 `androidx.compose.ui` / `lifecycle` 链路**传递引入**（`dependencyInsight` 已核实），
> 所以包内仍有 `ProfileInstallerInitializer`。要彻底剔除得写
> `configurations.all { exclude(group = "androidx.profileinstaller") }`，但那可能影响
> Compose / emoji2 的初始化，收益不确定、且在无真机环境下无法验证，**故未做**（宁保守）。

**b. 概览轮询改为「有人订阅才跑」**
`AppViewModel.overview` 从 `viewModelScope` 里无条件 `while (true) { 刷新; delay(2000) }`
改为 `flow{}.map{}.flowOn(Default).stateIn(viewModelScope, WhileSubscribed(5_000), …)`：
界面离开 STARTED 满 5 秒自动停轮询，回到前台立即恢复并**马上**给一份新值；
StateFlow 自带相等去重，「网关停止后 uptime 恒定」这类静止状态不再产生无意义推送与重组。

**c. 局域网 IP 移出组合期**
`Net.lanIp()` 原来在 Compose 组合里直接调用 → **每次重组都在主线程遍历一遍 NetworkInterface**
（概览每 2 秒刷新一次，就是每 2 秒一次主线程网卡枚举）。现在在后台线程算好、随 `Overview.lanIp` 下发。

**d. 日志链路减负**

- `LogBus` 推给 UI 改为 **120 ms 合并推送**：日志是高频写入源（一次流式请求 3~6 条），原实现
  每写一条就 `buf.toList()`（500 元素拷贝）+ 推一次 StateFlow；现在窗口内合并成一次
  （仍是完整快照，不丢、不乱序）；
- 「日志的收集」从 `AppRoot` **下移到 `LogsScreen`**：原来挂在根节点，于是任意一条新日志都会让
  整棵根（同时读着 overview/config/models/message…）参与重组，而真正需要日志的只有日志页。

**e. 启动路径与内存**

- `App.onCreate` 增加 **debug-only StrictMode**（磁盘读写 / 网络 / 未关闭的流 / Activity 泄漏），
  用于持续暴露启动路径上的主线程 I/O（release 不带，零运行时成本）；
- 新增 `Application.onTrimMemory` → 系统报内存压力时释放 OkHttp 空闲连接池
  （常驻前台服务 + 玻璃动画本就吃内存，Android 17 起还有 per-app 内存上限）；
- `MainActivity` 在「首屏数据就绪」后调用 `reportFullyDrawn()`（Jetpack 启动指标里 TTFD
  只有应用自己调用才会有；在首帧之前调用会被记成与 TTID 同值），带 3 秒兜底。

**f. 构建产物**

- `packaging.jniLibs.useLegacyPackaging = false` 显式写出：`.so` 以未压缩形式随包安装
  （`loadLibrary` 无需解压），也是 **16 KB 页对齐**（Android 15+ 要求，Play 2025-11 起强制）的前提。

### 3. 并发与健壮性修复（审查发现，均为低风险）★

| 问题 | 位置 | 后果 | 修法 |
|---|---|---|---|
| 共享 `SimpleDateFormat` 被多线程并发 parse | `upstream/Upstream.kt` `RateLimit.fmt` | 429 的「重置墙钟」解析出错 → 冷却退化成有界退避，丢掉上游给的精确时间 | 改 `java.time`（不可变、线程安全） |
| HTTP 请求体无上限 | `server/HttpServer.kt` | 监听 `0.0.0.0`，局域网内一个 `Content-Length: 2147483000` 就能当场 OOM | 加 32 MB 上限，超限回 `413` |
| 线程池无界 + keep-alive 10 分钟 | 同上 | 几百个连接 → 线程数爆炸（每线程默认 1 MB 栈） | 有界池（2/32、60 s 空闲回收、队列 64、CallerRuns 背压）+ keep-alive 收紧到 60 s |
| `startServer()` 与 `stopServer()` 锁不对称 | `Gateway.kt` | `server!!` 竞态 NPE 被 `runCatching` 吞成「启动失败（端口占用）」 | 整段加锁 + 局部变量消除窗口 |
| `state.json` 解析失败静默清空 | `data/Store.kt` `loadState` | 全部账号的禁用/冷却/熔断/签到台账一起归零且无日志 | 与 `loadConfig` 同口径：备份 `.bad` + 沿用上次 + 明确告警 |
| `atomicWrite` 的 rename 失败会直接覆盖写 | 同上 | 并发读者可能看到半截文件 | 改为「先删目标再改名」，仍失败才退化为覆盖写 |
| 凭据可被云备份（安全） | `AndroidManifest.xml` | `auths/*.json`（OAuth token 明文）与 `config.json`（api_key）会进 Google 云备份 / `adb backup` | `allowBackup="false"` + `dataExtractionRules` 全排除 |
| 权限面 | 同上 | `ACCESS_NETWORK_STATE` / `WAKE_LOCK` 全项目零引用 | 移除（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` **保留**：`isIgnoringBatteryOptimizations` 属边角行为，宁保守） |

### 4. R8 / 混淆配置清理

AGP 9.1 下 **R8 full mode 已默认开启**，且 AGP 9.0 起 `strictFullModeForKeepRules = true`
（`-keep class A` 不再隐含保留默认构造函数）。`proguard-rules.pro` 里删除了一组**与 manifest
自动 keep 重复**的规则（`App` / `MainActivity` / `GatewayService` 由 AGP 按 manifest 自动保留，
配合默认 `proguard-android-optimize.txt` 对 Activity/Service/Application 的保底规则；
`CrashHandler` 被 `App.onCreate` 直接引用，属可达代码）—— 它们把这几个类的成员全部钉死、
挡住了 R8 的内联与裁剪。

### 5. 版本

`1.4.7`（versionCode `26`）。R8 + 资源缩减照旧；签名仅 v3；ABI 仅 `arm64-v8a`；
targetSdk 37（Android 17）/ compileSdk 37 / minSdk 32；`.so` 不压缩 + 16 KB 页对齐。

### 6. 已知、但**不建议盲改**（留待真机回归后再做）

以下项经官方文档核对确实有收益，但**无法在无真机的构建环境里验证**；为遵守「不拆东墙补西墙」，
本次只记录不实施：

- **Baseline Profile / Startup Profile**：官方推荐用 Macrobenchmark + `androidx.baselineprofile`
  插件生成（本环境跑不了宏基准，需真机/模拟器）；手写 `baseline-prof.txt` 官方明确「不推荐」。
  接入建议放在 CI + 真机（官方口径：首次执行约提速 30%）。
- **收紧序列化 keep**（如 `-keep class com.wb.api.model.** { *; }`）：所有 `@Serializable` 都走
  显式 `Xxx.serializer()`，理论上可删；但删错会直接让 release 包的「读配置 / 读账号」崩，
  必须真机回归后再删。
- **`-keepattributes *Annotation*` 收窄**：同上（涉及 `kotlin.Metadata` 与运行时注解的保留面）。
- **LeakCanary**：需新增依赖（当前构建为离线模式）；可直接用 Android Studio 新版 Profiler
  内置的 LeakCanary 替代（零依赖）。
- **`android:enableOnBackInvokedCallback`（预测性返回）**：未找到 Android 16/17 改变其默认值的
  一手依据，不据此改动。

## 变更（1.4.6 / versionCode 25）— 浅色选中指示器的真·透明玻璃、首页「快捷操作 / 健康总览」重做

> 本版两件事：修掉一个**只在浅色主题下出现**的玻璃失效（选中指示器变成实心白块），
> 以及把首页两个卡片的**底色/圆角/布局/动画**收拾干净（不加新信息、不加新装饰）。

### 1. 浅色下底栏的**选中指示器**不再是「实心白块」（问题 1）★

用户反馈：深色下选中栏的指示器是正常的透明液态玻璃，**浅色下不是**。

- 根因：指示器底色（`WbGlass.raisedTint`）浅色取的是 **白 96%**。这个值来自 1.4.5 之前的判断
  「浅色栏体本身就是高透白，指示器必须接近纯白才读得出抬起来」；但 **1.4.5 已把栏体改成
  淡灰玻璃** `#E9E9ED@55%`，该前提不再成立 —— 96% 的白叠上去就是**一块不透明的实心白块**：
  背后被 blur 的内容完全透不出来，读不出玻璃感。（深色是白 10%，所以一切正常。）
- 修法：浅色指示器底色降到 **白 55%**：① 与栏体（合成后约 94% 明度）拉开约 3% 明度差；
  ② 留一半以上的透过率，指示器里同样是**被模糊过的真实内容**，与深色在「透明度」这一维上对称。
- 配套：浅色下指示器的高光从 `0.20` 抬到 **`0.40`**（与底栏同档）、静止态投影 `0.10 → 0.12`
  —— 底色变透之后边界不再由「白块」提供，必须由镜面高光 + 发丝描边 + 投影承担起「抬起来」的表达。

### 2. 首页「快捷操作」与「健康总览」重做（问题 2）★

两个卡片原来都是「四种色底并排」：动作格各自一块主色 14% 底、指标格各自一块语义色 10% 底，
小尺寸下很碎、很花，8dp 的格内圆角也与卡片 16dp 不成套。

- **快捷操作** → 2×2「动作格」（新增 `WbActionCell`）：统一中性容器底
  （`surfaceContainerHighest`，比卡片底高一档）+ **主色只落在图标上**；格子统一 46dp 高、12dp 圆角，
  行/列间距 6 → 8dp，按下走全局 `wbPress`（缩放 + 触感），禁用态整块 40% 不透明度。
- **健康总览** → 一条整底的**指标条**（新增 `WbStatStrip` / `WbStatDivider`）：等分 4 段 +
  段间 1dp 发丝竖线，底色只留一处，**色彩只保留在数字上**（语义色）；数字改用 `title3`，
  并在数值变化时做一次轻微的上移淡入（`AnimatedContent`，可在设置里关掉动画）——
  刷新余额 / 账号上下线时看得见「数字确实变了」，而不是硬切换。
- 两处的「账号」段仍然可点（进账号页），信息量一字未增。

### 3. 版本

`1.4.6`（versionCode `25`）。R8 + 资源缩减照旧；签名仅 v3；ABI 仅 `arm64-v8a`；
targetSdk 37（Android 17）/ compileSdk 37 / minSdk 32。

## 变更（1.4.5 / versionCode 24）— 浅色真液态玻璃、默认主题「自动」、刷新反馈就地化、上游 1.13.0 移植

### 1. 默认主题改为「跟随系统（自动）」（问题 1）

`UiCfg.theme` 默认值 `dark` → `system`；`ui_rev` 升到 **3**，并在 `Store.load()` 里做一次性迁移：

- 老配置若 **`ui_rev < 3` 且主题仍是「深色」** → 迁为 `system`。
  为什么敢动：1.4.4 之前 theme 的默认值就是「深色」，历史配置里**无法区分**「用户主动选深色」与
  「从没动过」，而本版要求把默认改成「跟随系统」，只能按需求一并迁移；
  确实想固定深色的用户，在设置里再选一次即可（迁移在 `ui_rev` 升级时只发生一次）。
- 判据取**原始 JSON 的键值**而不是解码结果：缺键时 kotlinx 会用「当前默认值」填充，
  解码后无法区分「没写过」与「写过 3」（沿用上一版 rawText 判断的思路，但改为读键，避免误判）。

### 2. 浅色下的「真液态玻璃」：纯白 → 淡灰玻璃（问题 2）★★

用户给的浅/深色对比截图把问题指得很清楚：**浅色下底栏与页面「亮度完全相同」，且没有任何内容透过**。

- 根因：浅色页面本身就接近白色，**纯白底色（42% 也好、62% 也罢）叠上去与背景同亮度** ——
  肉眼看到的是一条「实心白条」：既没有透明度，也读不出玻璃。深色之所以像玻璃，是因为
  `#121212@42%` 与暗背景之间有明度差，背后内容会透出来。
- 修法：浅色改用**淡灰玻璃** `#E9E9ED @ 55%`（比页面暗约 5%）：① 与页面拉开可见层次；
  ② 背后内容（卡片、蓝色图标）会透出来并被模糊成色斑，与深色观感对称。
- 边缘/高光/投影沿用 1.4.4 的主题自适应体系（浅色发丝描边 + 收敛的高光与投影）。

> 页面内「磨砂」控件（保存栏、设置页分段控件）**保持较高不透明度**：它们没有 blur 可依赖，
> 靠不透明度保证可读性 —— 与「浮动玻璃」是两种形态，本版不动它们（避免修一个坏一个）。

### 3. 刷新/重载的 loading 反馈移到**点击处**（问题 3）★

原来账号页在「添加账号」与分段控件之间插一条全宽进度条、模型页在玻璃面板底边留一条 4dp 进度槽：
反馈出现的位置**离点击处很远**，而且挤在版面中间，容易与相邻组件视觉重叠。

新增 `WbRefreshAction`：悬浮顶栏右上角的刷新按钮**加载时图标就地旋转**、并禁用重复点击
（`IconButton(enabled = !loading)`，Miuix 原生能力）；两处页面内进度条删除，两个页面的刷新交互就此统一。
（动画只在加载期创建，空闲时不留下常驻动画帧循环。）

### 4. 账号详情页不再「挡住」底栏切换（问题 4）★

账号详情是**盖在 Pager 之上的二级页浮层**，而底栏点击只改 Pager：详情打开时点底栏，底下的 Pager
确实切了，但详情页仍盖在最上层 —— 用户看到的就是「点底栏没反应，必须手动返回」。
现在**点底栏 / 按住底栏拖动都会先退出二级页**（`detailUid = null`），再切栏目。

### 5. 上游 1.13.0 审查与移植（问题 5）★★

上游已从 `1.11.11`（我方快照基线）推进到 **`1.13.0`**（`cmd/server/main.go: appVersion="1.13.0-panel"`），
共 26 改 9 增。逐项审查后（完整报告见交付说明），**移植 3 项、其余按口径排除**：

**(a) 企业版额度分流**（上游 `internal/upstream/client.go enterpriseResource`）★

企业额度不在个人「资源包」体系内：`get-user-resource*` 对 `enterpriseId` 非空的账号恒返回空
`Accounts`（实测 code 0 且 `Accounts=null`）→ **企业号余额恒显示 0**（额度其实是有的）。
现在按 `enterprise_id` 非空分流到 `POST /v2/billing/meter/get-enterprise-user-usage`，并做口径换算：

- 字段语义与个人**相反**：企业 `credit` = 本周期**已用**、`limitNum` = 分配给本账号的额度
  → `remain = limitNum − credit`（`credit` 是浮点，四舍五入到整数）；
- 兼容 camelCase / snake_case 两套字段；`limitNum < 0` = **不限量** → 池内用
  `ENTERPRISE_UNLIMITED_REMAIN = 1<<40` 表示，界面上显示成「**不限**」（否则会打印 1099511627776）；
- 周期末（`cycleEndTime`，回落 `cycleResetTime`）作为唯一到期批次，参与「快过期优先」。

**(b) 企业版识别与任务门控**（上游 `internal/auth/auth.go IsEnterprise` + `scheduler/*`）★

`AuthEntry.isEnterprise`（`enterprise_id` 非空）。企业版**没有个人成长体系**：上游对
`daily-checkin` 返回 400 code 10001「企业账号不支持该操作」、`growth/*` 返回 403。
因此：定时签到的**定点/补签**都跳过企业号（不再对注定失败的端点空发请求，也不记账为失败）、
手动「签到」按钮对企业号给出如实说明、详情页不显示「今日未签到」（避免把「上游不支持」误报成
「今天还没签」）、并给出「企业版」标记。

**(c) `/status` 透出 `enterprise`**（对齐上游 `state.go` 的 `Enterprise` 字段）：纯新增键，零回归。

**本版明确不移植**（理由见下）：`internal/panel/*` 七个视图、`model_locks` 全清单视图、
`include_disabled_in_tasks`、`blackcat`/`travel` 暂停口径、`global_register`、`trial` 加油包、
`device_token_file`、`nowForNightWindow` 测试钩子（我方无 Web 面板、无保号任务体系）、
归档按事件时间排序（我方归档是**单文件** `requests.jsonl`，不存在多文件 mtime 排序问题）。

### 6. 顺带修复：`prefer_expiring` 之前是「只读不写 + 语义错位」

审查时发现 `AccountState.earliest_remaining` 只在选号时被**读**、从来没有写入方，而加权公式
`1 + 1/(earliest_remaining/86400 + 1)` 又把「剩余量」当成了「剩余天数」用 —— 于是这个开关
**恒定不生效**（形同虚设）。现在改成上游口径：

- 余额查询真正写入「最早未来到期批次」（`earliest_at` + `earliest_remaining`，到期时刻按
  UTC+8 解释，与 Go `parsePackageEndTime` 同口径）；
- 选号加权改为上游的 **×3 虚拟实例**（`expiringVirtualSlots`），判据 = 有未来到期的批次
  且落在 `expiring_soon`（默认 168h）窗口内；窗口取自配置，与加权判据同源，避免口径漂移；
- 顺带：一次不带到期信息的查询**不会清零**已有的快过期信号。

### 7. 版本

`1.4.5`（versionCode `24`）。R8 + 资源缩减照旧；签名仅 v3；ABI 仅 `arm64-v8a`；
targetSdk 37（Android 17）/ compileSdk 37 / minSdk 32。

## 变更（1.4.4 / versionCode 23）— 浅色主题下的玻璃可见性、Toast 位置、浮动表头留白 bug

> 本版全部围绕「浅色主题」与「浮动玻璃表头」的正确性：前者是**配色在浅色下失效**，
> 后者是一个**修饰符顺序导致的真 bug**（内容被压在玻璃底下）。

### 1. 液态玻璃**主题自适应**：边缘线不再「只有白色」（问题 1）★

浅色模式下玻璃几乎**隐形**，共五处根因：

| 元素 | 原值 | 浅色下的后果 |
|---|---|---|
| 边缘描边 | 白 34% → 透明（固定） | 白边叠在浅色页面上 = 看不出边界 |
| 中性底色 | `#FAFAFA @42%` | 浅色页面本就近白，42% 白 ≈ 什么都没画 |
| 镜面高光 | `Highlight()`（白色 + `blendMode=Plus`） | 只会往更白加 —— 亮背景上没有边缘 |
| 选中指示器 | 白 32% | 叠在浅色栏体上几乎无差别（**选中态看不出来**） |
| 投影 | 默认黑 | 亮背景上一团深灰 → 读成「气泡底下压了第二层」 |

现在全部随主题走（都在 `WbGlass` 里，一处定义、两处渲染路径共用）：

- `edgeColor(isDark)` / `edgeBrush(isDark)`：深色仍是白 34% → 透明的镜面高光；
  **浅色改为黑 16% → 透明的发丝描边**（与「白卡片 + 发丝边」的通行做法一致）；
- `surfaceTint`（真玻璃，有 blur 撑腰）：浅色白 **62%**；`frostedTint`（磨砂，没有 blur）：
  浅色白 **86%** —— 没模糊帮忙时底色必须更实才立得住；
- `neutralTint()`：**中性底色的统一入口**，自动按「当前能不能采到背景层」在两者之间选。
  同一个分段控件既出现在浮动玻璃表头里（真玻璃）、也出现在设置页正文里（磨砂），
  调用方不必自己判断所处形态；
- 镜面高光：浅色压到 `Highlight(alpha = 0.4f)`，边缘改由描边定形；
- 投影：`WbGlass.shadowColor` / `glassShadow`，浅色用黑 13% / 20%。

两条渲染路径（`drawBackdrop` 真玻璃 / `background+border` 磨砂）与底栏（`GlassTabBar`）现在
**共用同一条 `edgeBrush`**：浮动的顶栏·底栏·Toast 与页面内的分段控件·保存栏·表头玻璃，
在明暗两套主题下都能看出边界。

### 2. Toast 抬到页面动作条之上（问题 2）★

日志页点「复制全部」时，Toast「已复制 N 条日志」正好压在按钮上。原因：Toast 的底部偏移
（96dp）只让过了**悬浮底栏**，没让过**页面底部的动作条**（自 `pageBottom` 104dp 起、高 42dp）。

新增 `WbD.toastBottom = pageBottom + bottomActionH + 12.dp`（= 158dp）：Toast 让过页面里
**所有**常驻底部元素，不再与任何按钮重叠。

### 3. 设置页保存栏：不再「两层气泡」（问题 3）★

「有未保存的更改 / 放弃 / 保存更改」这条玻璃在浅色下底色不可见（见第 1 条），于是唯一可见的
只剩**投影**与蓝色按钮底 —— 一团深灰被读成「气泡底下又压了一层、还错开了」，蓝色底也像漂在
气泡之外。修法同第 1 条：保存栏改走 `neutralTint()`（浅色白 86%）+ 发丝描边 + 更轻的投影，
现在是一条**边界清晰、只有一层**的玻璃条。

### 4. 模型页 / 账号页：`onSizeChanged` 的修饰符顺序 bug（问题 4、5）★★

**根因（真 bug）**：表头是浮动的兄弟节点、不占内容流，列表靠 `contentPadding` 给它留白，
留白值取自 `onSizeChanged` 的实测高度 —— 但代码把它写在了 `padding(...)` **之后**：

```kotlin
Modifier.fillMaxWidth()
    .padding(top = WbD.pageTop, bottom = WbD.gapS)   // ← 外层：先量
    .onSizeChanged { headerPx = it.height }          // ← 量到的是被收缩后的「内容」高度
```

Compose 的修饰符「外层先量、内层后量」，所以 `headerPx` 只等于**玻璃面板本身**的高度，
**漏掉了 `pageTop` 的 72dp 顶栏净空**。列表留白因此比表头实际占用少 72dp ——
首行模型卡片被压在玻璃表头底下（`暂无模型` 空态图标同理，就是「出现在液态玻璃底部」）。
把 `onSizeChanged` 提到链上**最前**，留白立即正确。

**顺带两处**：

- 新增 `WbD.headerGap = 12dp`：表头与其下内容之间的呼吸间距（原来只有 6dp，卡片几乎贴着
  玻璃下边缘）。留出这段间距后，**静止时首项完整可见，只有主动上滑时内容才滑进玻璃下方
  被模糊** —— 与日志页的表现一致（用户给的正例）；
- 账号页表头也改成**实测高度**：它含「重载进度条」，出现与否会改变表头高度；用固定常量会在
  重载瞬间少留一截、首张卡片贴到分段控件上（就是「没有间距、太贴顶部」）。

### 5. 代码审查

- 删掉 `Glass.kt` 里已无用的 `DefaultHighlight` / `DefaultShadow`（被主题化的
  `glassHighlight` / `glassShadow` 取代）；
- `AppRoot.WbGlassTopBar` 里为取底色而写的 `isDark` 与 `luminance` import 一并移除
  （改由 `WbGlass.neutralTint()` 内部判定）；
- 其余为注释与文档同步（`WbGlassHeaderPage` 的留白公式补上 `headerGap`）。

### 6. 版本

`1.4.4`（versionCode `23`）。R8 + 资源缩减照旧；签名仅 v3；ABI 仅 `arm64-v8a`；
targetSdk 37（Android 17）/ compileSdk 37 / minSdk 32。

### 本版**未**做 / 有意保留

- 冻结「深色下的观感」：指示器与栏体的高光/投影在深色下**逐值保持不变**（只改浅色分支），
  避免「修浅色、坏深色」；
- 没有给页面内玻璃加真正的 GPU 模糊：那需要把玻璃控件移出被记录子树（`WbGlassHeaderPage`
  的结构约束，否则自采样成环闪退，见 1.4.1 的根因记录），而页面内控件做不到全部外移 ——
  故采用「更实的磨砂底 + 发丝描边」这条安全路线。

## 变更（1.4.3 / versionCode 22）— 跟手修复、玻璃化收口、上游 v1.12.0 移植、Android 17 适配

### 1. 底栏拖动「跟手」的真正根因（问题 1）★

**根因**：指示器按「页码 × 每栏宽」平移（每栏宽 ≈ 页宽/5），而 `PagerState` 的滚动量以
**页宽**为单位。1.4.2 把**手指像素**直接喂给 `dispatchRawDelta`，位移于是被缩到
`栏宽/页宽 ≈ 1/5` —— 从上「首页」拖到「日志」（3 个栏宽）只前进 0.6 页，四舍五入后停在
「账号」。这正是「按首页拖到日志却切到账号」的成因（不是 slop、也不是驱动 API 的问题）。

**修法**：在两侧各用「自己知道的尺寸」做换算 ——

- `GlassTabBar` 知道栏宽：把手指位移折算成**页码增量** `dx / tabWidthPx`；
- `AppRoot` 知道页宽（`onSizeChanged` 实测）：`dispatchRawDelta(pageDelta * pagerWidthPx)`。

于是指示器与手指严格 1:1：拖 3 个栏宽就是 3 页；拖到「日志 / 设置之间」页码为 3.x，
底色照样放大，松手 `animateScrollToPage(round(...))` 吸附到最近栏目。
（驱动仍用同步的 `dispatchRawDelta` → `performScroll`：按像素平移 + 边界 coerceIn，
无吸附、无动画、立即生效；手势回调处在 `@RestrictsSuspension` 的 `AwaitPointerEventScope` 里，
编译期本来也不允许调用 suspend 的 `scroll { }`。）

### 2. 指示器改成**中性液态玻璃**，不再是「透蓝色块」（问题 1）★

删掉了 1.4.2 的「强调色玻璃拷贝」层（那一层只为给指示器染蓝，且每帧多渲染一整层玻璃），
指示器现在直接采样 `pageBackdrop`（页面内容层）：

- 底色改为 `WbGlass.raisedTint()`：中性、微亮、**不带任何色相**（深色 +10% 白、浅色 +32% 白）；
- 模糊比底栏更厚（1.35×）+ vibrancy + 折射/色散（按压力度驱动）+ 镜面高光 + 投影。

读起来是「同一块玻璃被抬起来」，而不是「盖了个色块」；放大倍率仍是 1.50×（栏体 1.06×），
拖动时明显涨出 62dp 的玻璃栏本体。顺带省掉了一层每帧玻璃渲染。

### 3. 设置页「保存更改」栏：按钮同高、不再压叠（问题 2）★

原实现把 Miuix 的 `TextButton` + `Button` 塞进 48dp 的玻璃条：两者各自带 `defaultMinSize`
与自己的容器底色，于是高低不齐、底色与玻璃条互相压叠。现在两个动作按钮改为同一套
**36dp、无自带容器**的紧凑样式（`SaveBarAction`，只保留圆角 + 选中态半透明强调底 + 按下缩放），
并给左侧文案加 `maxLines = 1` + 省略号，栏内边距改为非对称（右端少留）以免右侧显出空隙。

### 4. 模型页表头：合并成**一块玻璃面板**（问题 3）★

搜索框与两组筛选原本是三块独立玻璃、彼此留缝，视觉上被切成三段（「内容割裂」的来源）。
现在合并为一块连续玻璃：

```
玻璃面板
├── 搜索（透明底 → 与面板共用同一块玻璃）
├── 细分隔线             ← 区分「搜索」与「筛选」两栏
└── 单列/双列 │ 默认/上下文   ← 中间一条竖细线，同一块玻璃里的两组
```

- 新增 `WbSegmented(plain = true)`：躺在外层玻璃面板上时不再自带玻璃容器（避免两层底色压叠）；
- 新增 `WbHairline(vertical)`：面板内的 1dp 分组细线（比 `HorizontalDivider` 更轻）；
- 新增 `WbSearchField(transparent = true)`：`TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent)`，
  让输入框与面板共用同一块玻璃；
- **表头高度改为实测**（`onSizeChanged`）：浮动表头不占内容流，列表必须按它留白；实测值让
  「改表头」与「列表留白」自动保持一致（首帧回退到估值常量），不再需要一个靠手算的魔数；
- 加载进度放进**恒定 4dp 槽位**：不加载时是空位，因此表头高度恒定，加载态下也不会把第一张卡片顶下去。

### 5. 首页「启动网关」改成**透绿**（问题 4）

新增 `WbButtonVariant.Success`（半透明成功色底 + 成功色文字/图标），与 `Danger`（透红）共用一套
实现、只差颜色。「启动网关」用它 —— 于是卡片底色（透绿/透红）与按钮底色同向，状态一眼可辨
（原来启动用实心主题色，与透红的停止按钮不成对）。

### 6. 上游 `workbuddy2api-panel` v1.12.0 移植（问题 6-1）★

逐条比对上游 `v1.12.0-panel`（2026-10-05）相对我们已对齐基线的增量，移植三项**网关语义**
（面板层的东西不适用，见下方「未移植」）：

1. **「暂停选号」`paused`**（`internal/pool/{state,entry,transition,persist,pick}.go`）
   —— 与 `disabled` 正交的新状态：退出选号候选（`pick` / 全冷却兜底 / `healthyForModel` /
   `servableNow` 全部照 `disabled` 的口径排除），但**不动任何惩罚域**（不清冷却、不清熔断、
   不重置计数），随时可恢复；`revive`（解冻）是全清、`setDisabled(true)` 会顺手清掉暂停；
   持久化进 `state.json`，`/status` 的 `counts.paused` 与 `accounts[].paused` 一并透出。
   UI：账号卡片/详情页显示「已暂停选号」（info 色，与冷却 warn、禁用 danger 区分）、
   账号筛选新增「暂停」一档、详情页新增「暂停选号 / 恢复选号」按钮。
2. **模型级阻塞单独报错 `model_unavailable`(400)**（`internal/server/handler.go` issue #102 +
   `internal/pool/cooldown.go` `ModelBlocked`）—— 新增 `AccountPool.modelBlocked(model)`：
   若**每个参与选号的账号**都对该模型处于模型级冷却，则判定为全池模型阻塞（有任意一个号
   还能服务该模型就不算）。选号失败时用它覆盖错误：`400 model_unavailable`，hint 里带
   「被挡账号数 + 最早解封时刻 + 上游原文」。原来一律报 `503 no_healthy_account`，
   客户端会把「这个模型不能用」读成「号全挂了」并反复重试（上游 issue #81/#102）。
   同时 `ErrKind.MODEL_BLOCKED`（11102）也不再落到 `no_healthy_account` 的默认分支。
3. **吐字速率扣除首 token 等待（TTFB）**（`internal/server/handler.go` issue #34 / #127）——
   `tokensPerSecond(tokens, total, ttfb)`：分母用「端到端 − TTFB」；
   `ttfb<=0`（非流式天然没有首帧）**不猜不扣**；扣完不足 200ms（上游攒批下发时首帧与末帧
   几乎同时到）退回端到端，避免除出上万 tok/s 的幻数。流式路径在首个 data 帧处记录 TTFB，
   日志也从「把端到端耗时写成 ttfb」修正为 `dur=… ttfb=…`（名实相符）。

### 7. Android 17 / Android 15 的平台适配（问题 6-2）★

- **`USE_LOOPBACK_INTERFACE`（Android 17 必须）**：Android 17 起跨应用的
  `127.0.0.1 / localhost / ::1` 通信默认禁止，通信**双方**都必须声明该安装期权限，否则连接
  返回 `EPERM`。本应用正是「本机网关服务端」这一角色（Claude Code 等客户端连 127.0.0.1），
  未声明等于在 Android 17 上直接失效 —— 已在清单声明（安装期权限，无弹窗）。
  依据：Android 17 行为变更 + 荣耀/ vivo 官方适配指南（均在 README 末尾来源列表中）。
- **`ACCESS_LOCAL_NETWORK`（Android 17 的局域网运行时权限）**：本应用监听 `0.0.0.0`，
  PC 端要用「手机局域网 IP」访问；Android 17 起这条链路需要该运行时权限（NEARBY_DEVICES 组）。
  已在清单声明，并在**点击「启动网关」时按需申请**：用户拒绝也照常启动（本机回环不受影响），
  只提示「Android 17 起局域网客户端将无法连接网关」。
- **`dataSync` 前台服务的 6 小时上限（Android 15+）**：系统允许 `dataSync` 在 24 小时内累计
  运行 6 小时，到点回调 `Service.onTimeout(int, int)`，必须在数秒内 `stopSelf()`，
  否则系统抛 `RemoteServiceException`（进程被杀、连日志都留不下）。本应用是「常驻网关」，
  与这条限制天然冲突 → 现在实现了 `onTimeout(startId, fgsType)`（API 35）与
  `onTimeout(startId)`（API 34 的 shortService），**优雅停机**：停调度器/网关、flush 归档、
  记一条可读日志 + 发一条可点击的「已被系统限时停止」通知。用户把应用切回前台即可重置计时。
- **前台服务启动异常兜底**：`GatewayService.start()` 用 `runCatching` 包住
  `startForegroundService` —— Android 12+ 的后台启动限制、Android 15+ 的配额耗尽都会抛
  `ForegroundServiceStartNotAllowedException`，原来会**在按钮点击处崩掉**；现在记日志，
  上层照常报「启动失败」。
- **显式前台服务类型**：`ServiceCompat.startForeground(..., ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)`，
  不再依赖「清单推定」，避免 `MissingForegroundServiceTypeException` 这一类运行时异常。

### 8. 性能与内存（问题 6-2）

- **Compose 强跳过（Kotlin 2.0.20+ 默认开启）需要「稳定」的参数类型**：给 28 个「只有 `val`、
  集合只整体替换」的数据类加上 `@Immutable`（`Overview` / `AccountView` / `PoolCounts` /
  `LogEntry` / `ModelInfo` / `GatewayConfig` 及各子配置 …）。此前它们是「不稳定类型」，
  强跳过只能用 `===` 比较，于是每次轮询刷新（账号列表、概览、日志）都会整片重组；
  现在内容没变就**直接跳过重组**。
- **16 KB 页大小合规（Android 15+ / Play 2025-11 起强制）**：核验了 APK 里唯一的 native 库
  `lib/arm64-v8a/libandroidx.graphics.path.so` —— 三个 `LOAD` 段的 `Align` 均为 `0x4000`（16 KB），
  已合规，无需改动（AGP 8.5.1+ 会自动对未压缩 `.so` 做 16 KB ZIP 对齐）。
- **Baseline Profile**：依赖（`profileinstaller 1.4.1`）在，但**工程里没有 `baseline-prof.txt`**
  —— 也就是说这个依赖一直在空转。没有真机/受管设备就无法用 macrobenchmark 生成并验证
  profile，而手写一份无法验证收益、还可能因类名漂移而失效，因此本版**不臆造**：
  README 记录生成方式（`androidx.baselineprofile` + macrobenchmark 模块，产出
  `src/main/baseline-prof.txt`），并同时提示「冷启动」的其它真实杠杆已就位
  （`Application.onCreate` 只做必须在首帧前完成的事、崩溃上报走 daemon 线程）。
- **已核实无需改动**：`LogBus` 已是容量 500 的环形缓冲（`ArrayDeque`，FIFO 淘汰），不存在
  日志无限增长导致的内存泄漏；`App.onCreate` 已是最小初始化；`Net.lanIp()` 只在首页 `item` 内调用。

### 9. 代码清理（问题 6-3）

- 删除死代码 `WbGlass.accentTint()`（全工程无引用）；
- `GlassTabBar` 删掉整层「强调色玻璃拷贝」及其 5 个不再需要的 import（`ColorFilter`、
  `rememberLayerBackdrop`、`rememberCombinedBackdrop`、`layerBackdrop`、`alpha`），
  文件头部设计说明同步改写成「两层结构」；
- `WbButton` 的 `Success` / `Danger` 合并为一套实现（原来要抄两份同样的 Row）；
- 账号筛选口径修正：`可用 / 冷却` 不再把「已暂停」的账号算进去（与首页计数口径对齐）；
- `ModelsHeaderSpace` 从「手算魔数」降级为**首帧兜底估值**，正常路径走实测高度。

### 10. 版本

`1.4.3`（versionCode `22`）。R8 + 资源缩减照旧；签名仅 v3；ABI 仅 `arm64-v8a`；
targetSdk 37（Android 17）/ compileSdk 37 / minSdk 32。

### 本版**未**移植/未做的事（避免"拆东墙补西墙"，也避免臆造）

- 上游面板层（`internal/panel/*`、`internal/reqlog/*`）：模型锁池视图、到期分布、积分包折叠、
  `modelPromotions` 优惠价、请求日志按事件时间归档 —— 我方是 Compose 原生 UI，非 Web 面板；
  `/status` 的 `model_locks` 全清单也没有 UI 消费方（账号详情页已按账号列出「受限模型」）。
- `include_disabled_in_tasks`（保号任务覆盖禁用号）、`blackcat`/`travel` 的暂停号口径修正：
  我方没有上游那套保号任务体系，移植即死代码。
- `global_register`（新号注册补地区）、`trial` 加油包领取：需要额外的注册链路与本机无关联的
  部署形态，本次不动（`14017` 已按账号故障软冷却处理）。
- `device_token_file`：上游是为「宿主容器挂载桌面端 token 文件」设计，Android 无此部署形态。

## 变更（1.4.2 / versionCode 21）— 底栏跟手拖动与放大强化、页面内分段控件真玻璃、若干 UI 修复

### 1. 底栏拖动「跟手」+ 放大强化（问题 1）★

- **跟手修复**：进入拖动的判定越过 `touchSlop` 时，把基准点重置到**手指当前位置**
  （`lastX = change.position.x`）。原来把 `touchSlop` 那一段位移一次性补进增量，
  视觉上就是「先卡住、再猛跳一下」——这正是「不跟手」的来源。
- **驱动方式（有源码依据）**：底栏拖动用 `pagerState.dispatchRawDelta(dx)` 驱动 Pager。
  读 Compose 1.12.1 源码确认 `PagerState.scrollableState = ScrollableState { performScroll(it) }`，
  而 `performScroll` 只做「整数像素累积 + 按边界 coerceIn」——**无吸附、无动画、同步生效**，
  所以位移与手指严格一致。
  （不改成 `pagerState.scroll { scrollBy(dx) }` 的原因：手势回调运行在 `AwaitPointerEventScope`，
  它是 `@RestrictsSuspension` 受限挂起作用域（`SuspendingPointerInputFilter.kt:63`），
  编译期不允许调用外部 `suspend` 函数，会报 `ILLEGAL_RESTRICTED_SUSPENDING_FUNCTION_CALL`。）
- **抢占惯性**：手指落到「底栏」时先 `scope.launch { pagerState.scroll { } }` 抢一次滚动锁，
  把可能还在跑的惯性滑动停掉 —— 否则「惯性位移 + 手指位移」叠加，指示器会乱飘。
- **连续切换**：拖到任意两栏之间（例如「日志 / 设置之间」）底色照样放大，松手后
  `animateScrollToPage(round(currentPage + offsetFraction))` 吸附到最近栏目。
- **放大层级重做**：玻璃栏本体 `1.06×`，**指示器（底色）`1.50×`** —— 指示器实测高 52dp，
  放大后 ≈78dp，**明显涨出 62dp 的玻璃栏本体**，一眼看出「正在拖动底栏」。
- **内容区滑动同步放大**：力度值改为 `dragging || pagerState.isScrollInProgress` 驱动，
  于是「按住底栏拖」与「左右滑内容区」共用同一套放大 / 折射 / 高光动画，观感统一。

### 2. 去掉重复入口（问题 2）

- 账号页：移除表头右侧「重载」按钮（悬浮玻璃顶栏右侧已有刷新）；
  「添加账号」改为整行宽度。
- 模型页：移除搜索框右侧的刷新按钮（同上），搜索框独占整行。

### 3. 页面内分段控件改成**真液态玻璃**（问题 3）★

新增 `WbGlassHeaderPage`（`ui/Glass.kt`）把「表头」与「内容」拆成**兄弟节点**：

```
Box
├── Box(Modifier.layerBackdrop(headerBackdrop))  ← 列表（被记录成玻璃背景层）
└── header()                                     ← 表头（兄弟节点，采样上面那层）
```

这样表头里的分段控件可以**真实折射 / 模糊**滚过它下面的列表内容，
同时**不会**形成上一版那种 `pageBackdrop → 控件图层 → pageBackdrop` 的循环显示列表。
模型页（单列/双列、默认/上下文）、日志页（全部/对话/任务/系统）已按此改造；
列表按其表头高度留出 `contentPadding`，内容可一直滚到玻璃下方，消除「卡片贴着表头下边缘
被硬裁切」的割裂感。账号页的分段控件一并按同样方式处理，保持全应用一致。

### 4. 修复「修改设置后『保存更改』不见了」（问题 4）★

`SettingsScreen` 之前向页面**发出了两个根级子节点**（`LazyColumn` + 承载保存栏的 `Box`）。
在 `HorizontalPager` 的页面槽里，第二棵子树不一定被放置 —— 表现为保存栏整条消失。
已改为**单一根节点**（外层 `Box` 包住列表 + `Modifier.align(BottomCenter)` 的保存栏）。

### 5. 首页「网关已停止」改用透红底色（问题 5）

`WbHeroCard` 的状态色改为：运行中 = 语义绿、已停止 = 语义红，
底色统一取该色的 **8% 透明度**（运行中透绿、已停止透红），图标 / 状态点 / 底色一致，
一眼区分两个状态。

### 6. 顺带处理

- `AppRoot` 底栏条目 / 文案只 `remember` 一次；力度动画抽成单一 `LaunchedEffect`，
  不再在拖动回调里反复 `scope.launch`（避免多次动画互相打断）。

## 变更（1.4.1 / versionCode 20）— 修复「切换栏目闪退」★

### 症状

1.4（vc19）打开即停在「首页」正常，**点按 / 拖动底栏切到「账号」「模型」「日志」「设置」时进程立刻闪退**。

### 根因（读库源码 + 官方示例逐一比对，非猜测）

`io.github.kyant0:backdrop`（真液态玻璃）的模型是：
**「背景层」必须由一棵「不含玻璃控件」的子树记录，玻璃控件作为它的兄弟节点绘制在其上方。**
官方示例里只有两种合法用法：

- `BackdropDemoScaffold`：把**静态壁纸 `Image`** 记为背景层，玻璃控件画在壁纸上层（兄弟关系）；
- `LiquidSlider` / `LiquidToggle`：`Box(Modifier.layerBackdrop(trackBackdrop))` 只记录**轨道**，玻璃滑块是它的**兄弟**；
- 需要嵌套时用 `exportedBackdrop`（`GlassPlaygroundContent` 的面板 + 内部滑块）——即子控件采样**父玻璃导出的层**，而不是内容层。

而 1.4 把 **整个页面内容**（`Box(...).layerBackdrop(pageBackdrop)`，其中就包含页面内的玻璃控件）
记成了背景层，页面内的分段控件 / 按钮 / 保存栏又在**同一子树内**用 `drawBackdrop` 采样这一层。
于是 `GraphicsLayer` 的显示列表变成闭环：

```
pageBackdrop 的显示列表 --包含--> 控件自身图层
控件自身图层的显示列表 --包含--> pageBackdrop
```

（Compose 侧证据：`DrawScope.drawLayer(graphicsLayer)` 会把
`drawContext.graphicsLayer`（= **当前正在录制的图层**）作为 `parentLayer` 传下去，见
`androidx.compose.ui.graphics.layer.GraphicsLayer.kt`；
`LayerBackdropNode.draw()` 会 `drawContent()` + `recordLayer(graphicsLayer){ drawContent() }`，
即被记录的子树会被**再画一遍进该层**——所以页面内的玻璃控件一定会把自己的图层录进 `pageBackdrop`，
同时自己又采样 `pageBackdrop`。）
合成期 HWUI 递归展开这串显示列表 → 栈溢出 → 进程被直接杀掉。
**只有「首页」不含页面内玻璃控件，所以只有它不崩**，这也印证了根因。

### 修复

1. `ui/AppRoot.kt`：把**页面内容**移出玻璃背景层作用域 ——
   内容层改为包在 `CompositionLocalProvider(LocalPageBackdrop provides null)` 里；
   顶栏 / 底栏 / Toast / Loading 依旧是内容层的**兄弟节点**，继续使用真玻璃（它们采样 `pageBackdrop` 不会成环）。
2. `ui/Glass.kt`：明确两种形态并让「无背景层」形态更完整 ——
   **真玻璃**（vibrancy + blur + AGSL 折射 + 高光 + 投影，仅悬浮层）与
   **磨砂玻璃**（半透明底 + 顶部高光描边 + 可选软阴影，页面内控件）。
   页面内控件改用磨砂形态后**不再采样任何图层**，从结构上不可能再成环。
3. `ui/GlassTabBar.kt`：底栏手势**独占**（`awaitFirstDown` 后立即 `down.consume()`），
   避免下层的 `HorizontalPager` 对同一次拖动再处理一遍（表现为「拖一次跳两格」的抖动）。
4. `AppRoot`：底栏条目 / 文案只 `remember` 计算一次。

> 说明：页面内控件（分段控件、日志页按钮、保存栏）拿不到「真实的背后内容」——
> 它们下方的背景本来就是纯色页面底，折射纯色与不做折射在视觉上等价，
> 因此改用「磨砂玻璃」（半透明 + 高光描边）既安全又符合 iOS 26 分段控件的观感；
> 会真正压住滚动内容的**顶栏 / 底栏 / Toast / Loading 仍然是真液态玻璃**。

## 变更（1.4 / versionCode 19）— 液态玻璃全面铺开、底栏免长按直接拖动、Pager 滑动换栏、流式续传兜底修复

### 1. 底栏交互重做：**无需长按**，直接拖动即切换（问题 1 / 2 / 3）★

- **拖动**：手指按在底栏上横向移动超过 `touchSlop` 即进入拖动（上下滑动不干扰），越过分栏自动切换，
  抬手后吸附到最近一栏；**轻点（未移动）= 点击切栏**，触感反馈保留。
- **内容区左右滑动换栏**：五个顶级页改由 `HorizontalPager` 承载 —— 首页左滑到「账号」、日志右滑到「模型」；
  二级页（账号详情）以 `slideInHorizontally` 从右侧推入覆盖，返回按钮 / 系统返回键均可退出。
- **动画连贯性**：拖动底栏、滑动内容区、点击图标**三种输入共用同一个 Pager 状态**，
  指示器位置直接读 `currentPage + currentPageOffsetFraction`，因此悬浮栏动画在任何输入下都连续、不跳变。
- **放大层级（拖动时）**：按下进度 `press 0..1` 同时驱动两层缩放 ——
  **底色（指示器）1.22× ＞ 悬浮玻璃栏 1.03×**，强调色块明显「涨出来」，一眼看出正在拖动底栏；
  指示器的折射（lens）+ 色散（chromaticAberration）、镜面高光、投影强度同步随力度增强。

### 2. 液态玻璃铺开到全应用（问题 4 / 5 / 6 / 7 / 8）

统一入口 `Modifier.wbGlass(...)`：对**背后真实内容**做 vibrancy（内容感知饱和度）+ GPU 高斯模糊
+ AGSL 实时折射（lens）+ 镜面高光 + 可选投影；**取不到背景层时自动降级**为「半透明底 + 顶部高光描边」
（在独立窗口的弹窗里不会画错）。

| 位置 | 玻璃化对象 |
|---|---|
| 顶部标题栏 | 自绘**悬浮**玻璃顶栏（内容可从其下方滚过并被折射）+ 右侧页面动作按钮 |
| 悬浮底栏 | 玻璃底栏 → 强调色玻璃拷贝 → 液态指示器（三层结构） |
| 分段控件 | 模型「单列 / 双列 / 默认 / 上下文」、日志「全部 / 对话 / 任务 / 系统」、账号「全部 / 可用 / 冷却 / 禁用」 |
| 浮层 | Toast、启停网关的 Loading 卡片、设置页「有未保存的更改」提示条（`WbSaveBar`） |
| 按钮 | 日志页底部「清空日志 / 复制全部」（`WbButton(glass = true)`） |

顶栏高 68dp，内容净空 `WbD.pageTop = 72dp` / `pageBottom = 104dp`；账号页与模型页的表头（按钮 / 搜索 / 分段控件）
改为**固定停在顶栏之下**，列表在其下滚动，不再被顶栏遮住。

### 3. Agent 多轮续传概率性中断修复（「其他 1」）★

**症状**：与 Agent 连续多轮对话 / 工具调用时，偶发「回复完就不动了」，必须手动输入「继续」「完成未完成的任务」
才会进入下一轮。

**根因（对齐 Go 参考实现，非猜测）**：上游在本轮结束时有概率**直接关闭连接而不发 `data: [DONE]`**。
Go 参考实现 `workbuddy2api-panel/internal/upstream/sse.go` 的 `StreamHint` 注释明确要求并实现
「**保证恰好写一个 `[DONE]`（上游漏发时兜底补上）**」—— 循环结束后**无条件**
`io.WriteString(w, "data: [DONE]\n\n")`。本工程原先只在收到上游 `[DONE]` 时才转发，
于是上游 EOF 收尾时客户端拿不到流终止符，Agent 认为本轮未结束、不再发起下一轮。

**修复**（`server/ChatHandler.kt`，chat/completions 与 responses 两条流式路径）：

1. `forwardStream` 记录 `sawDone`，循环结束后**无条件**补发 `data: [DONE]\n\n`（日志标注 `[DONE] 兜底`）；
2. **空流**也先发 `error` 帧、再补 `[DONE]`，任何异常路径都必然收尾；
3. SSE 注释 / 心跳行（非 `data:` 行）**原样透传**，不被吞掉（与上游保活语义一致）；
4. **tool_call 名称收敛**：同一 `index` 的分片只保留首片的 `function.name`，后续分片删除 name 键
   （对齐 OpenAI 官方流式；否则部分客户端会误判为「新开了一次工具调用」）；
5. **截断工具调用丢弃**：`finishReason == "length"` 或本轮被上游截断（`!sawDone`）时，
   丢弃 arguments 只有半截 JSON 的 tool_call（**空串 = 合法无参调用，保留**；残缺调用丢弃而非伪造成 `{}`），
   避免客户端解析非法 JSON 卡死会话 —— 与 Go 侧 `internal/upstream/truncation.go` 同款策略。

## 变更（1.3.9 / versionCode 18）— 真·液态玻璃（AGSL 折射）、长按拖动切换、深色主题修复

### 1. 悬浮底栏重构：从「半透明色块」升级为真·液态玻璃 ★

上一版用 Haze 只做了高斯模糊 + 半透明底色，观感仍是「半透蓝色遮罩」。本版改为**逐层还原
Apple Liquid Glass**（参考 `Kyant0/AndroidLiquidGlass` 的 Backdrop 库与 `big-dictator/LiquidTab`）：

- **真实折射（lens）**：接入 `io.github.kyant0:backdrop`，用 AGSL `RuntimeShader` 把玻璃背后
  的内容按玻璃厚度**实际弯折（refraction）**，边缘还带**色散（chromatic aberration）**——
  这是与「模糊 + 半透明」的本质区别；
- **三层结构**（对齐官方示例 LiquidBottomTabs）：玻璃底栏 → 强调色玻璃「拷贝」层（不可见，
  仅记录供折射采样）→ 液态指示器；
- **中性底色**：底色改用中性 40% 不透明度（`#FAFAFA`/`#121212`），不再用主题色 —— 消除「半透蓝」；
- **内容感知着色**：对折射前的背景做饱和度提升（vibrancy 1.5×）；
- **连续圆角**：改用 `kyant.shapes` 的 `Capsule(Continuous)`（连续曲率，且为折射 SDF 所需形状）；
- **兼容性**：AGSL 需 Android 13（API 33）；API 32 自动降级为「模糊 + 饱和度 + 高光」（不崩）。

### 2. 长按拖动切换栏目 ★

长按底栏任意位置即出现**液态玻璃按压效果**（指示器放大、折射与高光增强、触感反馈），
随后**拖动指示器**跨越栏目，松手吸附到最近栏目并切换。手势由单个 `awaitEachGesture` 统一处理
（单击切换 + 长按拖动），避免 `clickable` 与 `detectDragGesturesAfterLongPress` 争抢消费导致长按失效。

### 3. 设置页「有未保存的更改」动作条被底栏遮挡 ★

底部动作条贴底显示（10dp 间距），被悬浮玻璃栏压住导致「保存更改 / 放弃」点不到。改为使用
底栏净空 `WbD.pageBottom`（104dp），动作条稳定落在玻璃栏之上。

### 4. 登录授权页深色主题不生效（状态栏白底看不清）★

该页是独立 Activity + 纯 View，配色此前写死为浅色。现在：

- 按系统 `uiMode` 解析深 / 浅配色（顶栏、底栏、正文、页面底色）；
- **系统栏 inset 分别补给顶栏 / 底栏**，让二者的底色铺满状态栏与导航栏区域；
- `isAppearanceLightStatusBars` / `isAppearanceLightNavigationBars` 取反 —— 深色底配浅色图标；
- 新增 `values-night/themes.xml`（深色 `windowBackground`），修复冷启动闪白；
- WebView 跟随深色（API 33+ `isAlgorithmicDarkeningAllowed`，29~32 `forceDark`）。

---

## 变更（1.3.8 / versionCode 17）— 真正的液态玻璃底栏、日志页按钮遮挡修复

### 1. 悬浮底栏：按 Apple「液态玻璃」设计语言 + LiquidTab 参考重构 ★

对照 Apple WWDC25「Liquid Glass」的核心要素（半透明、真实模糊、内容感知着色 / 饱和度、镜面高光 / 折射、
动态变形、连续圆角），并逐条参考 LSPosed 项目 **big-dictator/LiquidTab**（Apache-2.0）在 Android 上的
落地方式重写底栏：

- **连续圆角（squircle）**：用三次贝塞尔近似 Apple 连续曲率（`controlRatio = 0.448 - 0.182·strength`，
  与 LiquidTab 同源），替代普通圆角矩形，边缘与屏幕圆角自然衔接；
- **真实背景模糊 + 内容感知着色**：Haze `hazeBlur` 做 GPU 高斯模糊，并对模糊结果做 **饱和度 1.45×**
  （`ColorFilter.colorMatrix` + `ColorMatrix.setToSaturation`）——玻璃会「吸收」背后内容的色彩，
  而非简单半透明底色；模糊半径由 26dp 降到 **14dp**，更通透（玻璃 = 透而柔，不是糊住）；
- **镜面高光**：顶部 1dp 白色渐隐描边（specular），模拟光线在玻璃上边缘的反射；浅色 0.58、深色 0.34；
- **悬浮层次**：向下柔和投影（`Modifier.dropShadow` + `Shadow`，20dp 半径 / 6dp 下移）；
- **动态变形（液态）**：选中指示器用低阻尼 spring（dampingRatio 0.55）弹性滑动，并在切换时做
  **squash & stretch**（拉伸 1.07× → 回弹），图标 / 文字 spring 缩放 + 按压回缩；
- **留白与尺寸**：栏高 58dp、左右外边距 16dp、内边距 6dp，按 LiquidTab 的 56dp / 12dp 量级校准；
  深色底色透明度 46%、浅色 34%。

### 2. 日志页底部按钮被悬浮栏遮挡 ★

`日志` 页在有内容时「清空日志 / 复制全部」会被悬浮玻璃栏压住。根因是其底部留白仍按普通页面间距
（10dp），小于悬浮栏占用高度（栏高 58 + 上下间距 24 = 82dp）。改为使用底栏净空
`WbD.pageBottom`（104dp），确保按钮落在玻璃栏之上、可点。

---

## 变更（1.3.7 / versionCode 16）— 登录页按钮、多轮工具调用稳定性、液态玻璃底栏、性能

### 1. 登录授权页：补齐「系统浏览器打开 / 刷新凭证」★

独立 Activity（纯 View WebView）补齐两个圆角按钮（带涟漪反馈）：「用系统浏览器打开」把当前授权链接
交给系统浏览器；「刷新凭证」重新拉取授权链接并重启轮询。顶部「返回」与底部按钮都按系统栏 insets 做
内边距，任意机型可点。

### 2. 多轮对话 / 多轮工具调用稳定性 ★

定位并修复三处会导致「概率性中断」的问题：

- **流式改为「首帧到达前不发响应头」**：此前上游一返回 200 就向客户端发出 SSE 响应头，一旦上游在首帧前
  断流 / 首帧即 error 帧，客户端只能收到残缺响应且无法重试。现在只有**第一个有效帧**到达才发响应头；
  此前的失败对客户端**无痕**，`runPipeline` 会**换号重试**（新增 `Hop.sent` 标记）。
- **流式读超时 120s → 600s**：推理型模型（reasoning / 多轮工具调用）常在「思考」阶段长时间不吐字节，
  120s 读超时会把这类请求直接掐断。聊天走独立的 `chatHttp`（复用连接池，仅拉长读超时）。
- **base 级重试**：单个 base 传输层异常（连接被拒 / TLS / 首字超时）时**先换下一个 base**
  （`copilot.tencent.com` ↔ `codebuddy.cn`）再试，而不是直接当作「账号问题」换号。

### 3. 底栏：液态玻璃效果 ★

- 引入 **Haze**（Compose backdrop blur 事实标准）做**真实背景模糊**：页面内容用 `hazeSource` 捕获，
  底栏用 `hazeBlur` 取背后内容模糊 + 极轻噪点 + 半透明底色；
- **悬浮玻璃栏**（内容延伸其下）；选中项用**弹性滑动的高亮胶囊**（spring 动画），位移改用
  `graphicsLayer.translationX`（只重绘、不重排 → 不掉帧），并叠加图标着色与字重强调。

### 4. 性能

- 引入 `androidx.profileinstaller`（Baseline Profile 运行时安装器）；
- 底栏指示器动画改为 `graphicsLayer` 位移（避免每帧触发布局）；底部内容留白改为 104dp，供悬浮栏使用；
- 冷启动路径复核：`Application.onCreate` 只做崩溃处理器安装 + 网关初始化，磁盘 IO 全部在后台线程。

### 说明（企业级基线）

Baseline / Startup Profile 的**生成**需要真机（Macrobenchmark / BaselineProfile Gradle 插件）。本仓库已
接入 `profileinstaller`，具备 `baseline-prof.txt` 后即可在真机生成并随包发布；建议后续在 CI 上用
`./gradlew :app:generateBaselineProfile` 产出并纳入发布流程。

---

## 变更（1.3.6 / versionCode 15）— 模型 id 裸名、登录返回键、首页关于入口

### 1. 模型 id 改为裸模型名（修「客户端无法自动识别上下文 / 思考」）★

深度核对上游源码（`internal/upstream/client.go` / `internal/server/handler.go`）确认：

- 上游 `/v3/config` 的模型 `id` 是**裸名**（如 `deepseek-v4.1-flash`）；
- 上游 `/v1/models` 是 `"cn:" + 裸名`；且**只有 UA 正确时才下发完整能力**（版本过旧会拿到精简目录，无 `maxInputTokens` / `supportedEfforts`）。

`/v1/models` 的 `id` 改为**裸模型名**（仅 global 域加 `global:` 前缀以免与 CN 同名冲突），客户端据此匹配
内置模型目录，从而自动带出上下文 / 思考能力；同时对可能重复的 `cn:` / `global:` 前缀做剥离（不再出现
`cn:cn:xxx`）。chat 路由对裸名默认走 CN，并继续兼容 `cn:` / `global:` 前缀写法（旧配置不受影响）。

### 2. 补齐上游 nonChatModel 过滤

对齐上游：剔除 `nes-` / `completion-` / `codewise-` 前缀、`maxOutputTokens ≤ 256`，以及 tags 含
`text-to-image` / `image-to-image` / `text-to-video` / `image-to-video` 的**非对话模型**（选中只会报 11102）。

### 3. 登录授权页「返回」按钮可点击

Android 15+ 强制 edge-to-edge，内容会延伸进状态栏，导致顶部「返回」被顶到状态栏区域点不到。现读取
系统栏 insets 给根布局补内边距，任何机型都可点。

### 4. 首页右上角「关于」入口

新增「关于」按钮 → 弹窗（Miuix `OverlayDialog`，圆角 + 转场动画）：含**免责声明**与
**「访问开源项目」**跳转按钮。

---

## 变更（1.3.5 / versionCode 14）— 对齐上游 /v1/models、独立登录页、双列排版与细节修复

### 1. 模型列表双列错位修复 ★

双列卡片里「积分 / 上下文 / 输出 / 思考档位」此前用「气泡 + 裸文本 + weight」混排，容易文字错位、
气泡不对齐。现统一改为 `FlowRow` + `WbTag`（气泡等高、自动换行），单列与双列排版完全一致。

### 2. /v1/models 严格对齐上游（修「多模态误判」）★

逐字对照上游 `workbuddy2api-panel` 的 `internal/server/handler.go`（`modelList` /
`applyModelInfoFields`）后，改为**输出与上游完全一致的字段集**：

- 上下文 `context_length`（≤0 省略）、最大输出 `max_output_tokens`（≤0 省略）
- `name` / `description`（含 `[x0.05 credit]` 前缀）/ `credits` / `tags` / `vendor`
- **布尔能力「true 才输出」**：`is_default` / `supports_images` / `supports_reasoning`
  （+ `can_disable_thinking`）/ `supports_tool_call` / `only_reasoning`
- 思考档位：`reasoning_effort` / `reasoning_summary` / `reasoning_supported_efforts` /
  `reasoning_default_effort`

**移除了上版自创的 `architecture` / `capabilities` / `modalities` / `top_provider` 及各别名字段** ——
它们恒存在，会让客户端把「字段存在」误判为「支持该能力」（即使纯文本模型也显示支持图片）。
对齐上游后，行为与直连上游 `/v1/models` 一致。

### 3. 登录授权页改为独立 Activity（根治输入倒序 / 光标跳首 / 无法返回）★

经查证（Kotlin 官方论坛同现象帖：`instead of "Hello" it says "olleH"`），在 Compose `AndroidView`
里内嵌 WebView 时，Compose 的测量 / 重组会在输入法每次 KeyUp 时打断 WebView 焦点，导致登录页受控
`<input>` 的光标被重置到最前（输入 `123456` → `654321`）。

**修法**：新增 `LoginWebActivity`（**纯 View 的 WebView，完全脱离 Compose 树**）：
- 输入法行为与系统浏览器一致，输入不再倒序；
- 顶部自带「‹ 返回」按钮，随时可退出；
- 不再全屏覆盖主界面，底部导航（首页 / 日志 / 设置…）不受影响。

### 4. 账号「重载」加刷新动画

与「模型-刷新」体验一致：重载进行中在账号页顶部显示进度条。

### 5. 设置-服务：访问密钥加「随机生成 / 清空」按钮

### 6. 设置-关于版本号修复

原来硬编码 `1.3.2 (versionCode 11)`（与应用真实版本脱节）。现改为读取
`BuildConfig.VERSION_NAME` / `VERSION_CODE`，「设置-关于」与「项目说明」两处均永远与实际版本一致。

---

## 变更（1.3.4 / versionCode 13）— 只信上游：模型参数/元数据、缓存命中率、输入法修复

本版按你的要求把「模型信息」彻底收紧为**只反映上游动态下发的真实字段**，并修复输入法与若干体验问题。

### 1. 模型列表：只用上游动态字段（移除本地知识表与预估值）★

- **彻底移除** 1.3.3 引入的静态知识表兜底（`assets/model_catalog.json`）与「context 未知按 1M」兜底：
  上下文只读上游 **`maxInputTokens`**、最大输出只读 **`maxOutputTokens`**；上游没给即显示「未知」
  （`0`），**不再以任何本地/估算数据填充**，列表里每个数字都能在上游 `/v3/config` 响应里逐字对上。
  （上一版把未收录模型标「估」，本版按要求去掉——预估值本身就是「编造」。）
- 其余字段同样收敛为上游字段名：`supportsImages` / `supportsReasoning` / `supportsToolCall` /
  `onlyReasoning` / `isDefault` / `maxAllowedSize` / `descriptionZh` / `vendor` / `credits`。
- **双列也显示积分与思考等级**：双列卡片现在同样显示「`credits` 积分」标签与
  `reasoning.supportedEfforts` 档位标签（原来只显示一行上下文/输出），单列、双列信息量一致。
- **排序精简为「默认 / 上下文」两项**（移除「名称 / 输出」）：默认 = 上游原序（默认模型置顶）；
  上下文 = 大窗口优先，同值再按最大输出、再按默认模型（稳定排序）。

### 2. `/v1/models` 模型能力元数据透传（Agent 客户端自动识别）★

`/v1/models` 官方 schema 只有 `id/object/created/owned_by`，模型能力/上下文长度属**各家扩展**，
事实标准是 **OpenRouter**（`context_length` + `architecture.input_modalities` + `supported_parameters`
+ `top_provider`）。为让 Agent 客户端自动判断（免手填），本版在每个模型对象里**同时输出多套命名**：

| 维度 | 输出字段（并存，最大化客户端兼容） |
| --- | --- |
| 上下文 | `context_length` / `max_context_length` / `context_window` / `max_input_tokens` |
| 最大输出 | `max_output_tokens` / `max_output_length` / `max_completion_tokens` |
| 模态 | `architecture.{input_modalities,output_modalities,modality}` + `modalities.{input,output}` |
| 能力 | `supports_images` / `supports_vision` / `supports_reasoning` / `supports_tool_call` / `supports_function_calling` / `capabilities.{vision,reasoning,tool_calls}` |
| 工具/推理 | `supported_parameters` 含 `tools` / `tool_choice` / `reasoning` / `reasoning_effort` |
| 次级位置 | `top_provider.{context_length,max_completion_tokens}` |

> 说明（已联网核对）：Cherry Studio / OpenWebUI / LobeChat 等主要从 `/v1/models` 取 `id`，能力靠内置
> 目录（models.dev / OpenRouter）按模型 ID 匹配；Cline/Roo/Kilo 仍需手填。故此处采用「OpenRouter 事实
> 标准 + 常见别名」的最大兼容输出：客户端能识别的就自动带出，不能识别的也能直接复制填表。

### 3. 账号详情：缓存命中率 ★

两条管线（`/v1/chat/completions`、`/v1/responses`）本已解析 `cached_tokens`，但未按账号累计。本版在
账号运行状态里**累计输入 token 与命中缓存 token**（并保证 `cached ≤ prompt`），账号详情新增
「缓存命中率」一行：`命中率%（命中 / 输入）`，≥50% 显示绿色。`/status` 的 `token_usage` 同步输出
`prompt_tokens` / `cached_tokens`。

### 4. 归档时间：改为设备本地时区 ★

`requests.jsonl` 的 `time` 由 UTC（`…Z`）改为**设备本地时区的 ISO-8601（带偏移）**：中国时区即
`2026-10-06T16:06:14.123+08:00`（`java.time.OffsetDateTime.now()`）。时间随设备时区走，不再恒为 UTC。

### 5. 修复：登录 WebView 输入字符倒序 / 光标停在最前 ★

**根因**：登录授权页此前内嵌在 Compose **`OverlayDialog`** 里。Compose Dialog 有**独立的 Window**，
其软键盘行为与 Activity 不同；Compose 也拿不到该 Window 对象，Activity 的
`windowSoftInputMode="adjustResize"` 对它**不生效**（官方与社区一致结论）。WebView 在这种窗口下，
输入法弹出/窗口尺寸变化时容易丢失编辑选区，表现为「输入 123456 → 654321、光标总在第一个字符前」。

**修法**：把登录授权页由 Dialog 改为**覆盖在 Activity 窗口内的全屏层**（`LoginOverlay`），使 Manifest
的 `adjustResize` 对 WebView 生效；并给 WebView 显式 `isFocusable` / `isFocusableInTouchMode`。
（`onRelease { destroy() }` 的内存释放保留。）

### 6. 首页「刷新余额」跳转账号栏

与「拉取模型 → 跳到模型栏」一致：点击首页「刷新余额」后自动切到**账号**栏，立即看到各账号最新余额。

---

## 变更（1.3.3 / versionCode 12）— 模型元数据修正 + 交互/动画 + 性能与保活

### 1. 模型列表：上下文 / 最大输出与排序（联网核对上游源码后修正）

对照上游仓库（`linguo2625469/workbuddy2api-panel`，字段以 `internal/upstream/client.go` 的
`dynModelEntry` 为准）逐字核对 json tag，发现**旧实现读的键在上游根本不存在**：

| 语义 | 上游真实字段 | 旧实现读的键（❌ 不存在） |
| --- | --- | --- |
| 上下文窗口 | **`maxInputTokens`** | `context_length` / `contextLength` / `maxContextTokens` |
| 最大输出 | **`maxOutputTokens`** | `max_output_tokens`（幸而作为别名保留） |
| 默认思考档 | **`reasoning.defaultEffort`**（老键 `reasoning.effort`） | 顶层 `default_effort` |
| 支持档位 | **`reasoning.supportedEfforts`** | 顶层 `supported_efforts` |
| 禁止关思考 | **`reasoning.canDisableThinking`** | 顶层 `can_disable_thinking` |
| 中文描述 | **`descriptionZh`** | `description` |
| 下架标记 | **`disabled`**（true 整条剔除） | 未处理 |

后果：上下文恒为 0 → 卡片不显示、按上下文排序**永远是空操作**（这正是「排序不生效」的根因）。
现在：字段名对齐上游；并移植上游的**四级兜底链**（上游动态值 → 静态知识表 → `context` 未知按 1M
兜底 → `max_output` 未知则省略不编造）。静态知识表落为 `assets/model_catalog.json`
（27 个模型，取自上游 `context_catalog.go` + `model.json`），未收录的模型在卡片上标注「估」，
不把估算值伪装成实测值。

排序选项补齐为**默认 / 名称 / 上下文 / 输出**（与上游 panel 一致），并对同值元素做次级比较，
避免同值看起来「像没排序」；「默认」保持上游原序（稳定排序）。

### 2. 交互与动画

- **启停网关不再「硬切换」**：主色、底色用 `animateColorAsState` 渐变，图标/标题/副标题/按钮用
  `AnimatedContent` 交叉淡入；运行中状态点保持呼吸动画。
- **Loading 不再只闪一帧**：启停操作加**最短可见时长（900ms）**+ 阶段文案（「正在启动网关…」→
  「正在拉起定时任务…」/「正在停止网关…」→「正在收尾…」），遮罩吃掉触摸防连点。
- **添加账号底部弹窗**：补左右安全边距 + 56dp 底部留白（原来紧贴底边，被手势条/导航栏压住）；
  两个同级按钮统一为 `Tonal`（原来「CN 实心 + Global 浅色」底色不一致，视觉上像分主次）。
- **API Key 复制按钮对齐**：脱敏行的行结构、内边距、图标规格（18dp）与右端顺序完全对齐
  `WbCopyRow`，复制图标落在同一条右边线上；眼睛按钮只占用复制图标左侧空间。
- **归档导出加二次确认**（导出与删除都走确认弹窗，并显示条数/体积/最近写入时间）。

### 3. 时间格式

请求归档 `requests.jsonl` 的 `time` 由 **Unix 秒**改为**设备 UTC 的 ISO-8601**
（例：`"time":"2026-10-06T16:06:14.123Z"`）。人读日志不必再换算时间戳，跨时区排查无歧义。

### 4. 性能 / 稳定性（对照 Google 官方建议逐条落实）

- **归档写入**：每行 `appendText`（3 次 syscall）→ 常驻 8KB `BufferedWriter`，按「累计 8 行或
  距上次 flush 超 1s」批量落盘；停止服务/导出/读取/删除前强制 flush，正常路径不丢行。
- **归档裁剪**：`readLines()`（100MB 上限时是数百 MB 堆分配）→ **按字节定位 + 流式拷贝**到临时文件
  再原子改名，内存占用与文件大小无关，且字节级操作不会损坏中文。
- **归档条数统计**：原来**每 2 秒** `readLines()` 整份文件计数（自伤式开销）→ 流式按行计数 + 30s 缓存，
  删除时立即失效。
- **日志时间格式**：`SimpleDateFormat`（非线程安全，被多线程共用存在错乱风险）→ `java.time`
  且在**写入时**定稿（`LogEntry.tsLabel`），列表渲染不再逐项做日期格式化。
- **Overview 状态推送**：原来每 2 秒无条件替换 state（整棵树每 2 秒重组一次）→ 比较「去掉 uptime 后的
  结构」，仅在真实变化或运行中（需要走秒）时推送。
- **冷启动**：`Application.onCreate` 里读历史崩溃文件的磁盘 IO 移到后台一次性线程，且只读**尾部 8KB**
  （原来 `readLines()` 整份读入）。
- **内存泄漏**：登录弹窗的 `WebView` 增加 `onRelease { destroy() }`（原来退出组合后长期占用 native 内存）。
- **后台保活**（合规路径）：前台服务已声明 `foregroundServiceType="dataSync"` +
  `FOREGROUND_SERVICE_DATA_SYNC`；设置页新增「后台保活设置」，检测并引导用户把应用加入
  **电池优化白名单**（长连接防止被 Doze 切断的唯一官方途径；不使用任何已证伪的双进程互拉/1px Activity 等黑科技）。

## 变更（1.3.2 / versionCode 11）— API 透传补全与缓存/延迟优化 + 交互精修

### 1. `/v1/responses` 与 `/v1/chat/completions` 透传补全（本次核心）

先按 OpenAI 官方 API Reference / Guides 与 DeepSeek 文档逐字段核对，找出并补上三处**真实缺口**：

| 类别 | 缺口 | 本版修法 |
| --- | --- | --- |
| 用量里的**缓存信息** | `Tokens` 只解析 `prompt/completion/total`，`usageJson` 把 `cached_tokens`、`reasoning_tokens` 硬编码成 **0**；chat 非流式聚合也只回三个总数 —— 客户端永远算不出缓存命中率 | 两头取值：OpenAI 的 `prompt_tokens_details.cached_tokens` / `completion_tokens_details.reasoning_tokens` 与 DeepSeek 的 `prompt_cache_hit_tokens`；Responses 侧输出 `input_tokens_details.cached_tokens` / `output_tokens_details.reasoning_tokens`，chat 非流式补 `prompt_tokens_details` / `completion_tokens_details`（并附 DeepSeek 语义的 hit/miss） |
| 请求侧**字段透传** | 只透传 temperature/top_p/seed/stop/penalties/logit_bias/user/metadata；`store`/`service_tier`/`prompt_cache_key`/`safety_identifier`/`parallel_tool_calls` 被静默丢弃，响应体也不回显，客户端以为参数被网关吞了 | 新增 `EXTRA_PASSTHROUGH` 直通同名同义字段；`text.verbosity→verbosity`、`top_logprobs→logprobs+top_logprobs`、`conversation`（字符串/对象）→`conversation_id`；response 对象**回显** store/metadata/service_tier/prompt_cache_key/truncation/text/instructions/max_output_tokens/tools/tool_choice 等（开指纹脱敏时不回显被丢弃的字段，避免自相矛盾） |
| 无法支持的形态 | `background:true`、`truncation`、`include`、`max_tool_calls` 等被静默忽略 | `background:true` → **明确 400**（无服务端任务存储）；其余记日志说明「已忽略」 |

### 2. 缓存与延迟优化

- **缓存键跨轮稳定**（关键修复）：`deriveSessionKey` 原来把每轮的 user 消息都拼进键里，**每轮都变** —— 后果是 ① `prompt_cache_key` 每轮换路由，上游前缀缓存**永远打不中**；② 会话粘性实际从未生效（每轮都在换账号）。现在只取「system 消息 + 第一条 user 消息」作为稳定前缀。
- **尊重客户端的 `prompt_cache_key`**：自带时原样沿用（多租户/多工作流隔离是客户端职责，网关改写反而降低命中）；缺省才派生 `wb2a-<账号+前缀>`。
- **SSE 关闭压缩**：低延迟模式下显式 `Accept-Encoding: identity`，避免中间层 gzip 缓冲把 TTFT 推高（关掉「低延迟模式」即恢复默认）。
- 保留既有的连接复用（keep-alive 5 分钟、连接池 32、HTTP/1.1、强制 `stream_options.include_usage`）。

### 3. 界面精修

- **首页 API Key**：改为**脱敏显示**（前缀 + 末 4 位，长度不外露）+ 眼睛切换 + **一键复制**（原为明文直出且无复制入口）。
- **首页健康总览**：2×2 大卡片 → **单行 4 格紧凑指标条**（去掉图标底座、内边距减半），不再占掉近半屏。
- **设置页整体重构**：6 个分组改为**默认折叠**的 `WbGroup`（图标 + 标题 + 副标题 + 折叠箭头），点击标题展开，首屏只看到分组边界，不再「一塌糊涂」。
- **数值全部改滑块**：并发与容错（单账号并发 / global 并发 / 熔断阈值 / 降级阈值）、巡检间隔、刷新间隔、归档保留天数与容量上限，均由「+/- 步进」改为 `WbSliderRow` 滑块（连续拖动、按步长吸附、右上角实时显示当前值，并在两端标注量程）。
- **「恢复本组默认」重做**：只在**该组确实被改过**时才出现（`modified` 判定），位置从标题右侧的灰色小字移到**卡片底部、分隔线之下**的有边框胶囊按钮——符合「读完一组选项再撤销」的阅读顺序。
- **请求归档**：新增**导出到设备**（写入公共「下载/wb2api/」，走 MediaStore，minSdk 32 免权限、无需授权弹窗）+ **删除归档日志**（二次确认弹窗，只删 `requests.jsonl`，不影响账号/用量），组内显示归档条数。
  （原归档写在应用私有目录，文件管理器看不到，等于「开了归档也没法查看」。）
- **启停网关 Loading**：启动/停止都改为「挂起 → 等待状态真正落地（最多 4~6s）→ 回执成功/失败原因」，期间全屏 `WbBusyOverlay` 遮罩 + 旋转指示 + 吃掉触摸，杜绝「点了没反应」的重复点击。
- 沿用：Toast（操作回执）/ Snackbar（失败类）/ Dialog（JSON、恢复默认、删除归档）/ ActionBar（顶栏动作）。

## 变更（1.3.1 / versionCode 10）— Responses 工具/思考链路修复 + 界面精修

### 1. `/v1/responses` 修复（本次核心，先查证官方规格再改）

依据 OpenAI《Migrate to the Responses API》等官方文档逐条比对，确认是**结构性差异**而非猜：

| 项 | Responses API | chat/completions | 1.3.0 的实现 | 本版修法 |
| --- | --- | --- | --- | --- |
| `tools` | **扁平**：`{type:function, name, description, parameters, strict}` | **嵌套**：`{type:function, function:{…}}` | 原样透传 → 上游解析不到 function，**工具调用必然失败** | 请求侧扁平→嵌套，`strict` 仅在客户端显式给出时透传（避免把非 strict schema 强行标 strict 被拒） |
| 工具往返 | `input` 中的 `function_call` / `function_call_output` items（携带 `call_id`） | `assistant.tool_calls` + `role:tool` 消息 | 未做转换 | 双向映射；多个 `function_call` 合并进同一条 assistant 消息（OpenAI 标准形态） |
| 思考档位 | `reasoning:{effort}`（部分客户端用顶层 `reasoning_effort`） | 顶层 `reasoning_effort` | 只认 `reasoning.effort`，其它形态被丢弃；响应也不回显 | 两种形态都接收并落到顶层 `reasoning_effort`（配合 `thinking:{type:enabled}`）；响应 envelope **回显** `reasoning.effort` |
| 工具调用结果 | `output` 里的 `function_call` item | `delta.tool_calls` | **完全没处理** → 客户端只看到空回复 | 流式产出 `output_item.added` / `function_call_arguments.delta` / `.done` / `output_item.done`；非流式回填 `output[].function_call` |
| 思考内容 | `reasoning` item（`summary[].summary_text`） | `delta.reasoning_content` | 无 | 流式产出 `reasoning_summary_part.added` / `reasoning_summary_text.delta/.done`；非流式产出 reasoning item |

同时修掉 `/v1/chat/completions` 一个既有 bug：**非流式聚合丢弃 `tool_calls`**（只读 `delta.content`），现在按 `index` 聚合 arguments 分片并回填 `message.tool_calls`。

其它适配：`text.format` → `response_format`（结构化输出）、`max_output_tokens` → `max_tokens`、`instructions` → system、
`developer` → `system`、`input_image` → `image_url`、所有流式事件带 `sequence_number`；
`previous_response_id` 用于会话粘性/缓存命名空间（本网关无状态存储，`input` 为空时返回**明确的 400 说明**而不是静默出错）。

### 2. 界面精修

- **设置 → 关于**：「支持的接口」从单行改为**两行键值行**（`WbKeyValueRow`），长文本不再挤压重叠。
- **首页**：重排为「服务开关 → 快捷操作 → 健康总览 → 运行指标 → 接入信息」；间距与内边距整体收紧
  （块间 12→10、卡片内边距 16→14、行内 13→11、页顶 8→6、页底 32→24）。
- **设置页**：统一为「分组标题 + 卡片 + 统一行组件」三段式，去掉嵌套 padding 拼凑；每段右侧保留「恢复默认」。
- **圆角统一**为三档：容器 14 / 控件 12 / 极小 8（胶囊类一律 50%），不再随手写数值。
- **关闭默认动态取色（莫奈）**：`ui.monet` 默认 false；旧配置做一次性迁移（缺 `ui_rev` 键即视为旧配置，自动关闭）。
- 新增界面元素：**Toast**（底部胶囊，操作回执：已复制 / 已重载 / 签到成功）、**Snackbar**（启动失败等需注意的消息）、
  **Dialog**（配置 JSON 查看与复制、恢复所有默认的二次确认）、**ActionBar**（账号 / 模型 / 日志页顶栏动作）。
  分流规则：含「失败 / 错误 / 异常 / 不可用」→ Snackbar，其余 → Toast。

## 变更（1.3.0 / versionCode 9）— 逻辑修复、主题、策略与 API 扩展

### 1. 主题与状态栏
- `ui` 段新增 `monet`（动态取色 / Material You）、`model_columns`、`model_sort`、`density`。
- **默认主题改为深色**：新装有新默认；老配置（没有 `monet` 字段）在 `Store.loadConfig` 里做**一次性迁移**，
  否则升级后仍是旧的「跟随系统」而看不到新默认。
- **状态栏/导航栏修复**：浅色主题下图标为深色、深色主题下为浅色。修法是 Android 官方
  `WindowInsetsControllerCompat.isAppearanceLightStatusBars / isAppearanceLightNavigationBars`
  随主题赋值（`WbSystemBars`），并把窗口底色刷成当前 `surface`。此前没有任何代码设置图标明暗，
  所以浅底配浅字——这正是「浅色模式看不清状态栏时间」的根因。

### 2. 逻辑缺陷修复（逐条确证，非猜测）
| 现象 | 根因（文件:行） | 修法 |
| --- | --- | --- |
| 关闭网关后运行时长仍增长 | `Gateway.setRunning` 只在 true 时写 `startedAt`，`stopServer` 不清零，`uptimeSec` 继续相减 | 停止时把时长**冻结**到 `frozenUptimeSec` 并清零 `startedAt`；UI 区分「运行中 / 已停止（上次 X）」 |
| 未登录也能「签到成功 / 余额刷新成功」 | `checkinAll/balanceAll` 对 0 个账号返回「完成 0/0」，`ioAsync` 一律当成功外报 | 空账号直接返回「尚未添加账号」；UI 同步禁用按钮 |
| 重复签到也报「签到成功」 | 上游「已签到」是幂等业务错误，被 `runCatching.success` 吞成成功 | `Upstream.checkin` 的返回文案区分「签到成功 / 今日已签到」，并写 `last_checkin_at` |
| 没有账号也能启动网关 | 无任何守卫 | `toggleGateway` 拦截并提示；启动失败（端口占用）回执给用户，不再「点了没反应」 |
| 账号详情「近期速率」不显示 | `Store.appendUsage` **全项目零调用**（usage 从不落盘），计数器恒为 0 | `forwardStream/aggregate` 返回值由 `Boolean` 改为带回 token 的 `Hop`，落 `usage.json`；无请求时显示 `—` |
| 单账号签到失败也 `revive()` | 失败时把上游刚给的冷却/熔断清掉，等于抹掉错误信号 | 只在成功时 `revive` + 记签到时刻 |

其他确证并修复的问题：WAF 触发状态**按 realm 分桶**（原先 CN 与 global 共用，跨域误伤 60s）；
`/healthz` 与选号口径不一致（全部冷却时 healthz 报 503 但 `pick` 仍会兜底选到号）；
成功请求**每次整份写 state.json** → 改为 1s 节流 + 调度器周期 `flush()`；
`config.json` 解析失败会**丢掉 api_key** → 改为备份坏文件并沿用上次配置；
`EmbeddedHttpServer.stop()` 不关线程池（反复启停累积线程）→ `shutdownNow()`；
`readRequest` 对空行**递归**（可被恶意输入打爆栈）→ 改循环；
`appendRequestLog` 无锁「读整文件+重写」→ 加锁 + 按字节回收；
`Scheduler` 用 `runBlocking` 嵌套且幂等键与余额刷新键混用同一张表 → 分离并修正；
面板 API 的 `keepalive/travel` 等批量操作**落 `else -> "ok"` 假装成功** → 未实现就返回 501。

### 3. 模型列表
- 新增**单列 / 双列**切换（`LazyVerticalGrid`）+ 搜索（名称/供应商）+ 排序（默认/名称/上下文），
  选择写入 `ui.model_columns`、`ui.model_sort` 持久化。

### 4. 自动化与策略
- 「请求归档」改为**可理解 + 可细调**：加了一段说明（它存什么、和「日志页」的区别），
  并开放保留天数、容量上限、是否记录客户端 IP。
- 定时签到支持两种模式：**定点时间**（0–23 点任选，可多选）与**自动补签**
  （每 N 分钟巡检「今日未签到的账号」并补签，可设置启动即巡检一次）。
- 移除全局「保存并应用」：设置页底部改为**仅在存在未保存改动时**出现的
  「保存更改 / 放弃」动作条；每个分组标题右侧另有**「恢复默认」**做局部重置。外观类偏好即时生效。
- 未接入上游的任务（成长/活跃/旅行/保活/夜猫子）如实标注为「未接入」，不再假装执行。

### 5. 配色
- 接入 `ColorSchemeMode.Monet*`：跟随壁纸的动态取色（minSdk 32 恒可用），并按语义使用
  primary / secondary / container 等角色；状态语义色（成功/警告/信息/危险）随明暗切换。
  卡片内加入彩色图标底座、分类色标签、状态胶囊、横幅提示——**点睛而不喧哗**。

### 6. API 扩展与体验优化
- 新增 **`POST /v1/responses`**（OpenAI Responses API）：请求转换为 chat 形态复用同一条
  选号/改写/错误分类/记账管线；非流式返回标准 `response` 对象（含 `output[].content[].output_text`、
  `usage.input_tokens/output_tokens`）；流式按 Responses 事件序列回写
  （`response.created` → `output_item.added` → `content_part.added` → `output_text.delta` × N →
  `output_text.done` → `content_part.done` → `output_item.done` → `response.completed`），
  `reasoning.effort` 映射为 `reasoning_effort`。
- 降低首字延迟：keep-alive 空闲回收 30s → **5 分钟**、连接池 16→32、Dispatcher 并发上限提高；
  保存配置时**不再无条件重建 OkHttp 客户端**（原先每次保存都换实例，等于清空连接复用）。
- 默认输出上限：客户端未指定时用模型元数据的 `max_output_tokens` 兜底；
  思考档位继续按模型 `supported_efforts/default_effort` 注入与降级；`prompt_cache_key`
  仍按 (账号, 会话) 稳定派生以命中上游前缀缓存。

## 变更（1.2.0 / versionCode 8）— 界面与交互重构

功能未动，全部是 UI/交互层重写。此前的界面问题与对应修法：

| 反馈 | 根因 | 修法 |
| --- | --- | --- |
| 排版不合理、不符合逻辑 | 各页面混用 8/12/14/16/28dp 间距与 11~26sp 字号，卡片内外边距各行其是 | 新增 **设计令牌** `WbD`（间距/圆角/触达尺寸）与 **Miuix 文本样式**（Title/Body/Footnote），全站统一取用 |
| 按钮动画、震动都没有 | 自绘可点区域全是裸 `Modifier.clickable`，无按压反馈、无触感 | 新增交互原语 **`Modifier.wbPress`**（按下 0.97 弹性缩放 + 系统触感）；列表行/复制行/开关/分段控件全部接入 |
| 动画一塌糊涂 | 同级 Tab 切换也做成左右横移，层级切换与平级切换不分 | 过渡分级：**平级 Tab** 淡入+微缩放；**进入二级页**右侧推入；**返回**向右退出；设置页可一键**关闭动画** |
| 功能区排列混乱 | 首页卡片顺序随机、模型/日志页缺少明确主操作 | 首页重排为「服务状态 → 健康总览(2×2) → 快捷操作 → 接入信息 → 运行指标」；每页顶部固定主操作 |
| 缺明暗色主题 | 只有 `isSystemInDarkTheme()` 二态、且不能手动切 | 接入 Miuix 官方 `ThemeController(ColorSchemeMode)`，支持 **自动 / 浅色 / 深色** 三态，设置页切换**即时生效**并持久化到 `config.json` 的 `ui` 段 |

其他：数值指标改为**滚动增长动画**（0 → 目标值）；运行中的状态点加**呼吸动画**；开关自绘带滑块位移与开/关不同触感；新增 `WbSegmented` 分段控件替换裸按钮组。新增 `ui` 配置段（`theme` / `haptics` / `animations`），可随时关闭触感与动画。

## 变更（1.1.5 / versionCode 7）

真机日志（1.1.4）：

```
00:22:46  cn|deepseek-v4.1-flash rotate uid=7582e1d9 kind=SOFT_RATE status=429
00:22:46  cooldown soft_rate/model uid=7582e1d9 model=deepseek-v4.1-flash 2h
00:22:46  [up] POST https://copilot.tencent.com/v2/chat/completions -> http 429
```

即：上游 429 body 带 `"code":6004` + 一个 ≥2 小时后的重置墙钟（**模型级限流，账号本身健康**）。

对照 Go，找到一条**此前完全没移植的关键链路**——上游「HTTP 200 已开流 + 一帧 error」是
6004 限流的真实形态（Go `sse.go:489`、`hint.go:FrameKind`、`handler.go:924-983`）。Go 的
注释原文点出的正是本版本的症状：

> 上游「200 已开流 + 一帧 error」是真实形态（6004 限流、内容拦截、审核），此前在读第一帧
> 之前就 NoteSuccess + 清 11102 负缓存 + 绑粘性 → **被限流的号记成健康、粘性把会话钉死在
> 它身上，后续每一轮都打同一个限流号**。

本版本的 `forwardStream` 把 error 帧原样转发、还记成功、还绑粘性，于是：

1. 已限流的号永远显示「健康」，模型级冷却从不生效；
2. 会话被粘死在同一个号上；
3. 同一个号被反复打 → 上游升级为硬 429（即截图那条）。

修复：

- `forwardStream` / `aggregate` 现在返回「这一跳是否真成功」；流尾发现 error 帧 → 按帧内容
  分类（`Classify.frame`，6004 → 模型级限流）+ 处置账号，**不记成功、不清负缓存、不绑粘性**
  （与 Go 逐条对齐）。
- 冷却时长优先级补齐 Go 的三级：**body 重置文案 > `Retry-After` 头 > 有界退避**（此前缺中间一级）。
- 轮转日志新增打印**出站体头部**与**上游错误原文**（截断 200 字符）——「上游到底为什么不给用」
  以后有原文可查，不靠猜。

## 变更（1.1.4 / versionCode 6）

真机日志（1.1.3）关键行：

```
23:45:10 [up] POST .../v2/chat/completions -> http 429
23:45:10 cn|deepseek-v4.1-flash rotate uid=7582e1d9 kind=SOFT_RATE status=429
23:45:10 cooldown soft_rate uid=7582e1d9 model=deepseek-v4.1-flash 10m
…
客户端： Transient error: [503] all accounts are temporarily unavailable, please retry later
```

裸模型名重写已生效（出站出现 200），但**一次 429 之后整池被判为不可用**。对照 Go 源码，
定位到三处移植偏差：

1. **选号缺少「全冷却兜底」**（Go `internal/pool/pick.go:pickEarliestExpiryLocked`）
   我的 `pick()` 在无健康号时直接返回 null → handler 立刻回 `503 no_healthy_account`。
   Go 的口径是：只要池里还有「非禁用、非余额耗尽硬冷却」的号，就挑「恢复最早」的那个
   继续尝试，仅当池内一个可用号都没有才返回 nil。
   → 这正是「一个号被限流 = 整池 503」的直接原因。

2. **429 冷却的粒度与时长失真**（Go `internal/server/handler.go:applyErrorPolicy`）
   - body 带重置墙钟（CN「将在 … 重置」/ EN `reset at …`）→ 冷却**精确对齐该墙钟**、封顶
     soft_rate_max，绝不指数堆加；
   - `"code":6004` 是**模型级**限流（账号健康、仅该模型受限，切模型即可用），**不能**当
     账号级冷却；
   - 无重置墙钟 → 账号级有界退避，且「已在冷却中再 429」不再推进（Go 注释点名的
     "越堆越厚"历史 bug）。
   → 我原实现一律 `cool_until = now + 600s`，把整个账号打死 10 分钟。

3. **粘性号绕过健康校验**（Go `internal/pool/state.go:PickByUIDForModel`）
   我的粘性路径直接返回绑定号、不查冷却/模型限流/在途 → 会钉在已冷却的号上反复失败。
   → 现改为校验失败即解绑、回落普通选号。

附带修复：

- 出站**传输层异常**（连接重置/握手超时/DNS 失败）不再掐断连接，转为可分类错误透传（Go 同款）。
- `网络自检`的「资源」探测此前用 **GET**，必然 404、误导排查方向 → 现与真实余额查询共用
  POST + 固定 body。
- 错误分类补齐 Go 的判定顺序：11102 / 6004 / 11115 / 14018 / 14017 / 11140 / 12153；
  请求级确定性错误（400 系）即刻透传原文、不再回退轮换。
- 轮换改为请求级不重复选号（`tried` 集合），次数对齐 Go 的 `MaxRotate = 3`。

## 变更（1.1.3 / versionCode 5）

真机日志（1.1.2）：余额 `get-user-resource -> 200`、模型 `/v3/config -> 200` 均已正常；
但 `/v2/chat/completions` 返回 400，上游原文：

```
{"code":11102,"msg":"model [cn:deepseek-v4.1-flash] service info not found", ...}
```

→ **带 `cn:` 前缀的模型名被原样发给了上游**。对照 Go `internal/server/handler.go:673`：

```go
// outbound model 名重写为 bareModel（D6）：realm 前缀是网关侧路由协议，上游不认前缀
if bareModel != peek.Model { body = rewriteModel(body, bareModel) }
```

我的移植只把前缀用于选号/路由，**没有重写出站 body**，上游自然报「模型不存在」。

**1.1.3 修复：**

1. **出站 `model` 重写为裸名**（`cn:`/`global:` 前缀只作用于网关内部路由）。
2. **补齐 Go 的完整出站 body 管线**（此前只移植了 4 条规则）：
   - `max_completion_tokens` → `max_tokens` 翻译（别名一律删除）
   - `stream_options = {include_usage: true}` 缺省注入
   - `tool_choice` 全形态归一（`none`→删 tools、对象→字符串、`function`→函数名）
   - `tools` JSON-Schema `pattern` 中 `\_` → `_`（上游严格文法否则 400 code=11129）
   - **tool_call ↔ tool 结果配对清理 + 结果块重排**（残缺配对会让上游对之后每条消息都返 400）
   - DeepSeek 思维链：注入 `thinking.type=enabled` + 默认档位、`reasoning_effort` 按支持档降级、
     assistant 消息 `reasoning_content` 回填（多轮一致性）
   - global realm 兜底 system 注入（`You are a helpful assistant.`）
   - 按账号注入 `prompt_cache_key`（上游前缀缓存，费用优化）

## 变更（1.1.2 / versionCode 4）

由**真机日志**定位到的确定性缺陷（非猜测）：

真机日志（1.1.1）：
```
[up] GET  https://www.codebuddy.cn/v2/billing/meter/get-user-resource -> http 404
[up] GET  https://www.codebuddy.cn/billing/meter/get-user-resource   -> http 404
[up] GET  https://copilot.tencent.com/billing/meter/get-user-resource -> http 404
[up] GET  https://copilot.tencent.com/v2/billing/meter/get-user-resource -> http 404
[up] POST https://www.codebuddy.cn/v2/billing/meter/daily-checkin    -> http 400
```
同一路径下 `daily-checkin` 能到业务层（400），`get-user-resource` 却 404 —— 差别是 **HTTP 方法**。

1. **余额查询方法错误（根因）**：`/v2/billing/meter/get-user-resource` 是 **POST-only**，
   且需要固定 JSON body（对齐 Go `UserResourceDetailedWithExpiry`：
   `PageNumber/PageSize/ProductCode=p_tcaca/Status=[0,3]/PackageEndTimeRangeBegin/End`）。
   此前用 GET 空 body → 上游按未知路由返回 `404 page not found`。现改为 POST + 同款 body，
   并按 Go `packageRemainUsed` 规则解析 `data.Response.Data.Accounts[]` 汇总剩余积分。
2. **模型接口 UA 错误**：`/v3/config` 必须带 `CodeBuddyIDE/4.12.0 CodeBuddy/4.12.0`
   （否则 400 `code=12403 check ua, get coding copilot version error`），且需
   `X-Domain`（账号 domain，缺省回落 chatBase host）与 `X-Product: SaaS`。
3. **签到幂等**：上游返回「今天已签到」不再判为失败（识别 `已签到` / `already`）。
4. **chat 域补齐** `X-No-Enterprise-Id` / `X-No-Department-Info`（对齐 Go `ChatHeaders`）。

## 变更（1.1.1 / versionCode 3）

针对「余额/签到失败、API 无法调用、服务关不掉」的修复：

1. **上游请求头按端点族对齐 Go 原版**：统一注入 `X-CodeBuddy-Request: 1`、`Accept-Language`、
   `X-User-Id`、`X-Enterprise-Id`/`X-Tenant-Id`、`X-Domain`、`X-Machine-ID`/`X-Session-ID`、`X-Device-Token`；
   billing 域改用单段 UA `WorkBuddy/5.5.4` 且不带 Origin/Referer（与 Go `BillingHeaders` 一致）。
2. **计费域/路径自动回退**：CN 账号 `www.codebuddy.cn` 不可达（返回 `404 page not found`）时，
   自动改走 CLI 域 `copilot.tencent.com`（该域同名路径实测存在）；global 走 `workbuddy.ai`。
   billing/meter 同时支持 `/v2/...` 与无 `/v2` 前缀两条路径。**仅在 404 时回退**，不掩盖鉴权类错误。
3. **强制 HTTP/1.1**：Go 原版实测 h2（ALPN 协商）在上游会出现异常路由与 header 超时，故出站禁用 h2，
   并设 10s 连接 / 30s 空闲连接淘汰 / 短 keepalive。
4. **每个上游请求都写日志**：`[up] GET <url> -> http <code>`；错误信息带上 `HTTP <code> @ <url> :: <响应片段>`，
   一眼定位是哪台主机、哪条路径失败。
5. **新增「网络自检」**：账号详情页 → 一键逐域名探测并写入日志。
6. **服务启停确定性化**：停止时同步停 Service + 内嵌服务器 + 调度器并立即置状态，避免「关不掉」；
   常驻通知的「停止」按钮同样走完整停止链路。
7. **接入地址补齐局域网 IP**：PC/其他设备接入需用 `http://<手机IP>:7863/v1`（127.0.0.1 只对手机自身有效）。
8. **崩溃可见**：启动时把上次 `crash.log` 摘要写入日志页。
9. 定时「活跃上报」在缺少上游事件链时改为显式跳过，不再产生必然失败的请求。

## 变更（1.1.0 / versionCode 2）

1. **修复「登录后无法自动入池」**（关键 bug）：
   轮询 `/v2/plugin/login/account` 时补齐 **`Authorization: Bearer <access_token>`** 头。
   上游该端点是鉴权端点，缺 Bearer 会鉴权失败 → 取不到 uid → 凭证被静默丢弃，导致账号永不入池。
2. 登录成功后自动执行：**凭证落盘 → 热加载入池 → 解除冷却/熔断 → 签到（CN）→ 余额同步**，并回报「已入池」结果。
3. 登录链路增加**错误可视化**：授权成功但取号失败会明确报错并提供「重新检测」；轮询超时提示重登。
4. 授权页支持**内嵌 WebView** 与**系统浏览器打开**两种方式（`<queries>` 已声明包可见性）。
5. **UI 全面 Miuix 化**：统一为 Miuix 组件与主题色，新增二级页（账号详情）、底部抽屉（添加账号）、
   原生 Snackbar 取代 Toast、TabRow 日志分频道、Slider/Switch 偏好项；导航为「顶栏 + 底部 dock + 二级页返回」的移动端结构。
6. **开启 R8 混淆 + 资源压缩**（`isMinifyEnabled`/`isShrinkResources` = true），APK 由 26.7MB 降至 **3.6MB**。

## 产物信息

| 项 | 值 |
|---|---|
| APK | `wb2api.apk`（release，R8） |
| 应用名 | wb2api |
| 包名 | `com.wb.api` |
| 版本 | 1.4.2 (versionCode 21) |
| minSdkVersion / targetSdkVersion | 32 / 37 |
| compileSdk | 37 (Android 17, minor 0) |
| ABI | **仅 arm64-v8a** |
| 签名 | keystore=`keystore/wb2api.jks`，alias/口令=`yintao123` |
| 签名方案 | **仅 v3**（v1=false, v2=false, v3=true） |
| 构建 | AGP 9.4.1 + Gradle 9.7.1 + Kotlin 2.4.20 + JDK 21 |
| SHA-256 | `709e236e770b3d5c934879580ca7b475a8132095ddedcf4b690724fe2c586339` |

## 架构（Go → Kotlin）

| 上游模块 | 本工程 |
|---|---|
| `cmd/server` + `internal/server` | `Gateway.kt`、`server/HttpServer.kt`、`server/ChatHandler.kt` |
| `internal/upstream` | `upstream/Upstream.kt`、`upstream/Rewrite.kt` |
| `internal/pool` | `pool/Pool.kt` |
| `internal/auth` | `upstream/Upstream.kt`（设备授权）+ `data/Store.kt` |
| `internal/scheduler` | `scheduler/Scheduler.kt` |
| `internal/panel` + `app.js` | **原生 Miuix UI**：`ui/AppRoot.kt`、`ui/Screens.kt`、`ui/Components.kt` |
| `internal/livecfg` + `config` | `core/Config.kt` + `data/Store.kt` |
| 日志分频道 | `core/LogBus.kt` |
| Docker 常驻 | `service/GatewayService.kt`（前台服务 + 通知） |

## UI 结构（移动端）

```
Scaffold
├─ topBar:   SmallTopAppBar(title，二级页显示返回)
├─ bottomBar:NavigationBar(dock：首页 / 账号 / 模型 / 日志 / 设置)
├─ content:  AnimatedContent(滑入淡出转场)
│            首页 / 账号 / 账号详情(二级) / 模型 / 日志 / 设置
├─ snackbarHost: SnackbarHost
├─ OverlayBottomSheet  → 添加账号
├─ OverlayDialog       → OAuth 授权 / 移除确认 / 关于
└─ BackHandler         → 二级页返回
```

## Android 专项优化

- 前台 Service + 常驻通知（`FOREGROUND_SERVICE_DATA_SYNC`）+ `WAKE_LOCK`，后台不被杀；支持一键启停。
- 协程 IO/Default 分离；`ConcurrentHashMap` 在途计数；原子落盘（临时文件 + 改名）。
- 本地 `127.0.0.1` 网关，无外部依赖；`usesCleartextTraffic` 便于本地 SDK 接入。
- 原生 WebView 承载 OAuth 页（非 HTML 布局 UI），并支持系统浏览器。
- 明暗主题跟随系统；Miuix 原生动画与按压反馈。

## 构建

环境：**JDK 21** + Android SDK（`platforms;android-37.0` + `build-tools;37.0.0`）+ **Gradle 9.7.1**
（仓库内含 Gradle Wrapper，直接用 `./gradlew` 即可）。

```bash
export JAVA_HOME=<jdk21>
export ANDROID_HOME=<android-sdk>
./gradlew :app:assembleRelease     # 或 gradle :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

### 发布版签名（凭据不入库）

本仓库**不包含**签名密钥与口令。缺省时 `assembleRelease` 产出
`app-release-unsigned.apk`（未签名，公开源码在无密钥环境同样能构建成功）；
要出可安装的正式包，把密钥放在任意本地路径，并在 `local.properties`
（已被 `.gitignore` 忽略，模板见 `local.properties.example`）填入：

```properties
sdk.dir=/path/to/Android/sdk
RELEASE_STORE_FILE=keystore/your-release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

以上四项也接受同名环境变量（CI 更方便）。**切勿**把密钥文件或口令提交到仓库
—— 密钥一旦公开，任何人都能伪造同签名 APK。

## 说明

- 上游为逆向的非官方网关，仅限本人授权账号、本机/私有环境测试；请勿商用或二次分发。
- 本环境无 arm64 真机/模拟器：已完成编译、R8 混淆打包、清单/SDK/ABI/签名方案/对齐逐项核对与 dex 关键类存活检查；
  未做真机运行验证，运行期崩溃会记录到 `filesDir/crash.log`。

# wb2api — Android 原生网关

将 WorkBuddy/CodeBuddy 账号转换为 **OpenAI 兼容 API** 的 Android 原生实现。应用内直接运行一个
无外部依赖的 HTTP 网关，把账号池、会话粘性、错误处置与定时任务全部收敛到手机本地，
对外提供 `/v1/chat/completions`、`/v1/responses`、`/v1/models` 等标准端点，
供 Claude Code、Cherry Studio、OpenWebUI、Cline 等任意 OpenAI 兼容客户端接入。

- **上游项目**：[misakano7545/workbuddy2api-panel](https://github.com/misakano7545/workbuddy2api-panel)
  （接续 [linguo2625469/workbuddy2api-panel](https://github.com/linguo2625469/workbuddy2api-panel)；
  后者已于 2026-10-21 停止维护，本工程的协议对齐以仍在维护的接力分支为基准）
- **本工程定位**：上游 Go 网关的原生 Android 移植与重构（Kotlin 实现，非套壳、非 HTML UI）

---

## 免责声明

本项目为个人学习与技术研究用途的开源实现，仅供在**本人拥有合法授权的账号**与
**本机／私有环境**中测试使用。使用者应自行确保其使用行为符合当地法律法规及服务提供方的
相关条款，并自行承担由此产生的一切后果。本项目不对账号状态、服务可用性或数据完整性作任何
保证，亦不承担因使用本项目而产生的任何直接或间接损失。请勿用于商业用途或二次分发。

---

## 功能概览

- **账号池**：多账号轮转、加权随机选号、会话粘性、在途并发上限、账号级余额与状态可见。
- **OpenAI 兼容端点**：`/v1/chat/completions`（流式／非流式）、`/v1/responses`、
  `/v1/models`；附带 `/healthz`、`/status` 与面板接口 `/panel/api/*`。
- **错误处置**：熔断、连败降权、模型级冷却、WAF 闸门，按错误类型区分「换号重试 / 冷却 / 禁用」。
- **自动化任务**：定时签到（定点或自动补签）、余额刷新、成长任务扫描与领奖。
- **可观测性**：分频道日志（全部／对话／任务／系统）、运行指标、token 用量与缓存命中率。
- **请求归档**：请求级 JSONL 归档，支持保留天数／容量上限／客户端信息开关与导出到设备。
- **界面**：Jetpack Compose + Miuix，支持明暗主题、液态玻璃浮层与本地化（zh / en）。

---

---

## 版本与更新日志

### 1.6.6（versionCode 45）
跟进上游 2026-10-22 连续三个发版（issue #51 头族对齐 / #46 模型繁忙 / 空回复救援）：

- **出站头族与标识按两版真机取证对齐**：`X-Conversation-ID` 改 UUID v4 形态（36 字符带横线）、
  消息级 ID 带毫秒时间位、`X-Trace-ID` 与 B3 `SpanId` 改为独立随机、补齐
  `X-B3-ParentSpanId` / 单头 `b3` / `traceparent` / `acp-connection-id` / 7 个 `x-stainless-*`；
  `X-Product` 改 `SaaS`；chat 域**删掉**官方从不发的 `Origin` / `Referer` / `Accept-Language` /
  `X-Machine-ID` / `X-Session-ID` / `X-No-*`；body 侧补 `conversationRequestId`（头体一致）。
- **14003「模型繁忙」**：模型级短冷却（10s 起、翻倍、封顶 60s），全池被挡时先等一次（≤10s），
  仍不可用返回 `503 model_busy` + `Retry-After` —— 不再按 429 罚号 600s（那会把 10 秒抖动
  放大成整分钟不可用）。
- **空回复预防**：输出额度在 16~399 且客户端未显式要求思考时不注入 thinking（上游实测该区间
  思考会吃光预算、正文 0 字节，日志表现为 `finish_reason=length` 的空回复）。
- **审计修复**：Responses 流式错误帧漏 `sent=true`（会重复打上游并记多次错误）、
  池状态读-改-写竞态（冷却可能被并发覆盖）、HTTP 请求行/头条数无上限（OOM 风险）、
  归档读取改为自尾反向读、粘性开关真正生效、模型阻塞判定分 realm、错误路径落盘走节流、
  chunked 负长度与空块保护。

### 1.6.5（versionCode 44）
修复「渠道校验」导致的大面积调用失败（上游 `400 code=11128/11-128 request illegal`）：

- 补会话头族三件套 `X-Conversation-ID` / `X-Conversation-Request-ID` /
  `X-Conversation-Message-ID`（上游按三件套是否齐全做渠道校验，缺任一头即 400）；
- `messages[0]` 兜底为 `system`（首条 `user` 会被判 `first message is not system prompt`，
  首条 `developer` 判 `unapproved channel`）；
- 渠道校验不再被误判成账号封禁（旧实现会 `Disable` 健康号 → 整池 `503`），改软冷却，
  并自动解除历史误禁用；
- 非流式聚合补认「内嵌失败帧」（HTTP 200 + 顶层 `{code,msg}`）。

### 1.6.4（versionCode 43）
跟随上游接力分支（原直接上游停止维护）完成协议对齐：

- 出站指纹：CN 客户端 5.8.3 / global 5.8.4、CLI 2.161.0、`workbuddy-ai` 品牌、`X-Private-Data`；
- SSE 内嵌失败帧归一、断流如实上报 `finish_reason=length`；
- 空名 `tool_call` 连同结果成对剔除（否则整条会话每轮 400）；
- 新增 DSML 原生工具调用标记还原 —— DeepSeek 系「工具调用没执行、任务停在半路」的根因修复。

---

## 工作原理

### 总体形态

传统形态是「在服务器／Docker 上跑一个 Go 网关进程」；本工程把同一个网关**搬进 Android
应用进程内**，用前台服务常驻，把手机本身变成一台局域网可访问的 API 网关。因此：

- 无 Docker、无外部运行时依赖，安装即用；
- 账号凭据与服务端进程同处一个设备，无需跨网传输令牌；
- 网关的启停、账号管理、观测与排查全部在应用内完成（不依赖 Web 面板）。

### 请求链路

```
OpenAI 兼容客户端
      │  HTTP / SSE
      ▼
内嵌 HTTP 服务器（ServerSocket + 线程池，监听 0.0.0.0:7863）
      │  鉴权 → 路由 → 解析请求体
      ▼
选号器（账号池）
      │  健康过滤 → 模型级冷却过滤 → 加权随机 → 会话粘性
      ▼
请求改写（Kotlin 重写，逐条对齐上游 Go）
      │  模型名裸名化、工具消息配对清理、思考档位注入、缓存键派生 …
      ▼
上游（OkHttp 直连，HTTP/1.1）
      │
      ▼
响应回写（SSE 分块转发 / 非流式聚合 → OpenAI 标准形态）
      │
      ▼
错误分类与账号处置（成功记账 / 冷却 / 降权 / 熔断 / 禁用）
```

### 关键设计

**1. 选号器与账号状态机。** 每个账号维护健康状态、软／硬冷却截止时间、熔断计数、
降权窗口、在途并发与余额。选号在可用候选上做加权随机（并入空闲权重与临期偏好），
无健康号时按「恢复最早」兜底，而不是直接对客户端报错。

**2. 错误分类驱动的处置策略。** 上游错误被分类为若干类型，映射到不同动作：

| 错误类型 | 处置 |
|---|---|
| 5xx / 传输失败 | 计入连续失败，达阈值触发熔断（指数退避、封顶） |
| 限流（含模型级） | 模型级冷却，或按重置墙钟精确冷却；不影响账号整体健康 |
| WAF 拦截 | 账号级软冷却 + 全局（realm 级）闸门，不误伤健康号 |
| 会话失效 | 直接禁用（不可自愈） |
| 客户端类 4xx | 去重轮转 + 连败降权 |
| 参数／内容类 | 即时透传原文，不再消耗其它账号 |

**3. 会话粘性。** 同一会话在同一账号上保持亲和，以命中上游前缀缓存；粘性命中的账号
仍需通过健康与冷却校验，不合法即解绑并回落普通轮转。

**4. 两条 API 链路复用同一管线。** `/v1/responses` 与 `/v1/chat/completions` 共用
选号、改写、错误分类与记账逻辑，仅在请求／响应形态上双向映射，保证两端点行为一致。

---

## 高性能方案

### 1. 入口层：零依赖自研 HTTP 服务器

不引入任何 Web 框架，直接基于 `ServerSocket` 实现请求解析与响应写回，把连接模型的
可预测性握在自己手里：

- **accept 独立线程**，不占用工作池；工作池为 `ThreadPoolExecutor(core 8, max 32)`，
  采用 `SynchronousQueue` + `AbortPolicy`——即「要么立刻拿到线程，要么立刻被拒绝」，
  不会静默排队造成客户端干等超时；被拒绝时立即回 `503` 并关闭连接。
- 核心线程允许空闲回收（`allowCoreThreadTimeOut`），静默期资源占用趋近于零。
- `backlog=128` + `reuseAddress`，容忍建连突发。
- `TCP_NODELAY` 关闭 Nagle 算法，消除局域网小响应与 SSE 首包数十毫秒的攒包延迟。
- keep-alive 空闲上限收紧至 60s，**短于主流客户端（OkHttp 默认 5 分钟）**，
  由服务端先关连接，避免客户端复用已被对端关闭的链路。
- 线程池随启停生命周期创建／销毁，反复启停不累积线程。

### 2. 出口层：连接复用与低延迟

- **强制 HTTP/1.1**：规避上游在 h2（ALPN 协商）下的异常路由与头超时。
- **连接池 32 条、空闲复用 5 分钟**；Dispatcher 上限 `maxRequests=128`、
  `maxRequestsPerHost=32`，高并发下不排队。
- 连接超时 10s；普通请求读超时 120s，**聊天走独立客户端、读超时 600s**——
  推理型模型在思考阶段可能长时间不吐字节，短读超时会把请求直接掐断。
- **低延迟模式**（默认开启）：显式 `Accept-Encoding: identity`，避免中间层 gzip
  缓冲推高首字延迟；同时稳定派生 `prompt_cache_key` 以命中上游前缀缓存。

### 3. 流式转发

- **首帧到达前不发响应头**：上游在首帧前断流或首帧即错误帧时，这次失败对客户端无痕，
  网关可换号重试；只有第一个有效帧到达才提交响应头。
- **`[DONE]` 兜底**：上游偶发直接关闭连接而不发终止符，网关在流结束后无条件补发，
  避免 Agent 客户端把未终止的流当作「本轮未结束」而停止续传。
- SSE 注释行与心跳行原样透传，不被吞掉；`tool_call` 分片按 `index` 收敛名称，
  截断产生的残缺 arguments 直接丢弃而非伪造，避免客户端解析非法 JSON 卡死会话。

### 4. 工程与打包

- 协程 IO／Default 分离，阻塞调用统一收敛到 IO 线程；在途计数使用 `ConcurrentHashMap`。
- **原子落盘**（临时文件 + 改名）；`state.json` 按 1s 节流写入，避免成功请求每次都全量写盘。
- **归档写入**使用常驻 8KB `BufferedWriter`，按「累计 8 行或距上次 flush 超 1s」批量落盘，
  正常路径不丢行；裁剪按字节定位 + 流式拷贝，内存占用与文件大小无关；条数统计走流式计数 + 30s 缓存。
- 时间处理统一使用 `java.time`，替代非线程安全的 `SimpleDateFormat`。
- **R8 混淆 + 资源压缩**，关闭依赖信息块，`localeFilters` 仅打包 zh / en，显著压缩包体。
- 不含任何 native 库，APK 侧已通过 `zipalign -c -P 16` 的 16KB 页对齐校验；
  依赖自带的 baseline profile 随 AAR 合并进包，安装时由系统直接 AOT。
- `<profileable android:shell="true"/>` 使 release 包也可被 perfetto / simpleperf 量测。

### 5. 界面渲染

- 全部使用 Jetpack Compose + Miuix 原生组件；液态玻璃效果由 AGSL 实时折射（`backdrop`）
  实现，取不到背景层时自动降级为半透明 + 高光描边（API 32 无 AGSL 亦不崩）。
- 高频动画（底栏指示器等）以 `graphicsLayer` 位移驱动，只重绘不重排；
  状态模型标注 `@Immutable`，列表与派生值使用 `remember` 缓存，避免无效重组。

---

## 构建

环境要求：**JDK 21** + Android SDK（`platforms;android-37.0` + `build-tools;37.0.0`）
+ Gradle 9.7.1（仓库内含 Gradle Wrapper，直接使用 `./gradlew` 即可）。

```bash
export JAVA_HOME=<jdk21>
export ANDROID_HOME=<android-sdk>
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

### 发布版签名

本仓库不包含签名密钥与口令。缺省情况下 `assembleRelease` 产出 `app-release-unsigned.apk`，
保证公开源码在无密钥环境同样构建成功。需要生成可安装的正式包时，将密钥放在任意本地路径，
并在 `local.properties`（已被 `.gitignore` 忽略，模板见 `local.properties.example`）中填写：

```properties
sdk.dir=/path/to/Android/sdk
RELEASE_STORE_FILE=keystore/your-release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

以上四项同样接受同名环境变量（便于 CI 使用）。请勿将密钥文件或口令提交到仓库——
密钥一旦公开，任何人都可伪造同签名 APK。

---

## 架构映射（上游 Go → 本工程 Kotlin）

| 上游模块 | 本工程 |
|---|---|
| `cmd/server` + `internal/server` | `Gateway.kt`、`server/HttpServer.kt`、`server/ChatHandler.kt` |
| `internal/upstream` | `upstream/Upstream.kt`、`upstream/Rewrite.kt` |
| `internal/pool` | `pool/Pool.kt` |
| `internal/auth` | `upstream/Upstream.kt`（设备授权）+ `data/Store.kt` |
| `internal/scheduler` | `scheduler/Scheduler.kt` |
| `internal/panel` + `app.js` | 原生 Compose UI：`ui/AppRoot.kt`、`ui/Screens.kt`、`ui/Components.kt` |
| `internal/livecfg` + `config` | `core/Config.kt` + `data/Store.kt` |
| 日志分频道 | `core/LogBus.kt` |
| Docker 常驻 | `service/GatewayService.kt`（前台服务 + 常驻通知） |

---

## 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/healthz` | 健康检查（依据账号池当前可服务性返回 200 / 503） |
| `GET` | `/v1/models` | 模型目录，字段与上游一致（能力字段为「真才输出」） |
| `POST` | `/v1/chat/completions` | 聊天补全，支持流式与非流式 |
| `POST` | `/v1/responses` | OpenAI Responses API |
| `GET` | `/status` | 池状态、账号详情、token 用量与缓存命中 |
| `*` | `/panel/api/*` | 面板接口：概览 / 日志 / 配置 / 模型 / 用量 / 账号操作 / 批量任务（支持 `overview`、`logs`、`config`、`models`、`usage`、`request_metrics`、`request_logs`、`login/start` / `login/poll`、`checkin_all`、`balance_all`、`activity_all`、`accounts/<uid>/<op>`、`tasks/scan_all` 等） |

未在本工程实现的操作（如 `travel_all`、`keepalive_all`）会如实返回 `501 not_implemented`，
不以假成功掩盖。

---

## 致谢

本项目基于上游 [workbuddy2api-panel](https://github.com/linguo2625469/workbuddy2api-panel)
的协议行为与实现细节完成移植，架构对齐、错误处置语义与模型目录口径均以上游为基准；
界面实现参考 Miuix 与 `Kyant0/AndroidLiquidGlass`。

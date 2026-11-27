# wb2api — Android 原生移植版（基于 workbuddy2api-panel）

把上游 Go 网关 [`workbuddy2api-panel`](https://github.com/misakano7545/workbuddy2api-panel)
（腾讯 CodeBuddy 账号 → OpenAI 兼容反向代理）**全部重构**为原生 Android 应用：

- 上游：**[misakano7545/workbuddy2api-panel](https://github.com/misakano7545/workbuddy2api-panel)**
  —— 请求头族、错误码分类、模型目录、冷却/熔断策略均以它为准，本工程逐版本跟进其发版
- 语言：**Kotlin**（非套壳、非 HTML UI）
- UI：**Jetpack Compose + Miuix 0.9.4**（`top.yukonga.miuix.kmp`）：`Scaffold` / `SmallTopAppBar` / `NavigationBar`(dock) /
  `Card` / `SmallTitle` / `TabRow` / `Switch` / `TextField` / `SliderPreference` / `SwitchPreference` /
  `OverlayDialog` / `OverlayBottomSheet` / `SnackbarHost` —— 全部为 Miuix 原生组件，含原生转场动画与按压反馈
- 传输：**内嵌原生 HTTP 服务器**（自研 `ServerSocket` + 线程池，支持 SSE 分块转发）
- 出站：**OkHttp** 直连官方桌面端 endpoint（CN 主域 `www.workbuddy.cn`，`codebuddy.cn` / `copilot.tencent.com` 兜底），Kotlin 重写请求改写与错误分类
- 并发：**Kotlin 协程**；后台：**前台 Service** 常驻（Android 14+ 用 `specialUse`、12–13 用 `dataSync` —— 规避 Android 15 起 `dataSync` 的「24 小时内累计 6 小时」上限）
- 版本公告：启动时拉取远程「最新版x.x.x」文本（正则解析 + 语义化版本比较），高于本地即提示；
  网络异常/超时会明确回执，设置 → 关于 → 「检查更新」可手动重试
- 模型目录：按 `/v3/config` 的 `data.agents[默认].models`（**官方客户端菜单口径**）裁剪 ——
  去掉 `auto` / 付费变体 `paidId`、补 `fast/balanced/deep` 三个速度预设；不再把平台全量目录里的
  内部路由变体、历史别名与已下架模型列给下游（CN 实测 58 条目录 → 17 项菜单）
- 成长任务：设置页「成长任务」= 任务中心明细（标题 / 进度 / 奖励 / 状态）+ 单条领取 +
  一键扫描（报名 → 行为事件点亮 → 回读 → 自动领奖，含动作表外任务的补领兜底）
- 悬浮窗：网关运行时在其它应用上层常驻 `127.0.0.1:端口` 与状态点；**双击回到应用**
  （单击不动作 —— 常驻浮窗上单击最容易误触，会把用户从当前应用拽走），拖动可移动、
  可拖出屏幕边缘并保留 1/3 可点回，位置持久化
- 长回答保护：按最终思考档位抬 `max_tokens` 下限（low 4096 / medium 6144 / high 8192 /
  xhigh 12288 / max 16384）—— 上游把思考与正文算在同一份额度里，高档位思考会把客户端
  拍的小额度吃光、正文写到一半就断；抬的是上限不是目标长度，不改变答案长度
- 账号批量导出 / 导入：账号页长按卡片或表头「选择」进入多选（全选 / 反选，只作用于当前筛选），
  可**复制到剪贴板**或**导出为 JSON 文件**（SAF，无需存储权限）；导入支持**粘贴 / 读剪贴板 /
  多选文件**，文件先本地预览（文件名、条数、错误）确认后才写入，不会自动覆盖账号。
  剪贴板写入带 `ClipDescription.EXTRA_IS_SENSITIVE` 标记，系统复制预览与输入法剪贴板面板
  都不显示 access_token 明文；导入兼容数组 / 信封 / 嵌套（`account`+`auth`）/ 字典等形态
- 流式健壮性：上游把多个 JSON 拼在同一个 `data:` 行时按顶层值边界拆帧（不拆就会整块被客户端
  丢弃，表现为偶发丢字与工具调用消失）；混进正文的 `<think:SUF>` 拆回 `reasoning_content`；
  出站空 `text` 段自动补位（上游对空内容判 11151 直接 400）
- 签到语义：区分「已签到 / 处理中 / 失败」——`处理中` 不记签到时刻（否则当天不会再补签），
  由巡检补签自然重试
- 凭证健康度：账号状态由**统一派生**（池状态 + 凭证到期时间）—— 访问令牌过期显示「凭证过期」、
  续期凭证缺失显示「凭证失效」，不再出现「过期了还显示可用」。详情页给出「凭证状态 / 续期凭证」
  两行与「立即续期」；只有服务端**明确拒绝**才判死，本地 30 天的续期估期不用于定罪
- 凭证券活：定时任务新增「凭证券活」开关——每 15 分钟巡检一次，访问令牌剩余不足 30 分钟就自动
  续期（请求路径上仍保留临期续期兜底），避免账号闲置一天后第一个请求先撞 401
- 网络自检回答真问题：临期先续期 → 用 Bearer 调鉴权端点验证令牌 → 查余额 → 探模型目录，
  结论写成一句可执行的话（「凭证有效 · 剩余 42 分」「已自动续期」「需重新登录」），明细进日志
- 积分口径：禁用账号的余额报 0（原值保留为「冻结快照」仅在详情页展示），避免把封禁前的
  死数据算进可用合计与积分保底
- 余额刷新（1.7.5）：从「每 N 分钟定时刷新」改为**请求后冷静期** —— 一次请求成功结束后开始
  倒计时（默认 15s，可选 5/15/30/60s），期间没有新的成功请求才刷新一次额度；又来请求则重新
  计时，所以一轮密集对话只刷一次、刚聊完也能马上看到最新积分。冷启动（每次进程启动）另刷
  一次全量；长时间没有对话时由定时任务做**每小时一次**的兜底（上游每日发放/到期与请求无关）
- 错误分类（1.7.5）：11102 / 14401「该模型 id 不存在」改判**请求级**（同一 body 换遍全池都是
  同一句，换号只是白打全池）→ 立即 400 透传原文、不罚号、不写负缓存；11158「该账号类型无权
  使用该模型」仍是 (账号, 模型) 负缓存 + 换号；11103「该模型后端未启用」归请求级
- 工具可用性（1.7.5）：工具 schema 里 JS 风格的正则转义 `\uXXXX` 自动译成 RE2 的 `\x{...}`
  （译后仍编译不过则丢掉该 pattern —— 丢一条本地正则远好过整包 tools 被上游 11129 拒绝）；
  历史里的相邻 tool_calls 批自动合并、同一 call_id 的重复结果只留第一条（都属 11148 的成因）

## 更新日志

每个版本的完整变更说明见 [Releases](https://github.com/yintaozhou1/wb2api_android/releases)。
仓库内不再保留历史更新日志 —— 与 Release 说明重复，且会让 README 随版本线性膨胀。

## 架构（Go → Kotlin）

| 上游模块 | 本工程 |
|---|---|
| `cmd/server` + `internal/server` | `Gateway.kt`、`server/HttpServer.kt`、`server/ChatHandler.kt` |
| `internal/upstream` | `upstream/Upstream.kt`、`upstream/Rewrite.kt` |
| `internal/upstream/clientpick.go` | `upstream/ClientPick.kt`（模型菜单口径） |
| `internal/panel/autotask.go` + `taskcenter.go` | `server/GrowthService.kt`、`server/GrowthActions.kt` |
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

- 前台 Service + 常驻通知（Android 14+ 用 `FOREGROUND_SERVICE_SPECIAL_USE`、12–13 用 `FOREGROUND_SERVICE_DATA_SYNC`），后台常驻不被系统回收（`specialUse` 不受 `dataSync` 的 「24 小时内累计 6 小时」上限约束）；支持一键启停。
- 协程 IO/Default 分离；`ConcurrentHashMap` 在途计数；原子落盘（临时文件 + 改名）。
- 本地 `127.0.0.1` 网关，无外部依赖；`usesCleartextTraffic` 便于本地 SDK 接入。
- 原生 WebView 承载 OAuth 页（非 HTML 布局 UI），并支持系统浏览器。
- 明暗主题跟随系统；Miuix 原生动画与按压反馈。
- 版本公告检测：独立 OkHttp（8s 超时、浏览器 UA）拉远程分享页 → 正则 `最新版\s*[vV]?\s*(\d+(?:\.\d+){1,3})`
  取版本 → 按段数值比较（不按字典序，`1.10.0` 要判成比 `1.9.9` 新）。只在冷启动跑一次，不占网关连接池。

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

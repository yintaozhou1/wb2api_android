# wb2api — Android 原生移植版（基于 workbuddy2api-panel）

把上游 Go 网关 `workbuddy2api-panel`（腾讯 CodeBuddy 账号 → OpenAI 兼容反向代理）
**全部重构**为原生 Android 应用：

- 语言：**Kotlin**（非套壳、非 HTML UI）
- UI：**Jetpack Compose + Miuix 0.9.4**（`top.yukonga.miuix.kmp`）：`Scaffold` / `SmallTopAppBar` / `NavigationBar`(dock) /
  `Card` / `SmallTitle` / `TabRow` / `Switch` / `TextField` / `SliderPreference` / `SwitchPreference` /
  `OverlayDialog` / `OverlayBottomSheet` / `SnackbarHost` —— 全部为 Miuix 原生组件，含原生转场动画与按压反馈
- 传输：**内嵌原生 HTTP 服务器**（自研 `ServerSocket` + 线程池，支持 SSE 分块转发）
- 上游：**OkHttp** 直连官方桌面端 endpoint（CN 主域 `www.workbuddy.cn`，`codebuddy.cn` / `copilot.tencent.com` 兜底），Kotlin 重写请求改写与错误分类
- 并发：**Kotlin 协程**；后台：**前台 Service** 常驻（Android 14+ 用 `specialUse`、12–13 用 `dataSync` —— 规避 Android 15 起 `dataSync` 的「24 小时内累计 6 小时」上限）

## 更新日志

每个版本的完整变更说明见 [Releases](https://github.com/yintaozhou1/wb2api_android/releases)。
仓库内不再保留历史更新日志 —— 与 Release 说明重复，且会让 README 随版本线性膨胀。

## 产物信息

| 项 | 值 |
|---|---|
| APK | `wb2api.apk`（release，R8） |
| 应用名 | wb2api |
| 包名 | `com.wb.api` |
| 版本 | 1.6.9 (versionCode 48) |
| minSdkVersion / targetSdkVersion | 32 / 37 |
| compileSdk | 37 (Android 17, minor 0) |
| ABI | **仅 arm64-v8a** |
| 签名 | keystore=`keystore/wb2api.jks`（**不入库**），alias 与口令见本地 `local.properties` |
| 签名方案 | **仅 v3**（v1=false, v2=false, v3=true） |
| 构建 | AGP 9.4.1 + Gradle 9.7.1 + Kotlin 2.4.20 + JDK 21 |
| SHA-256 | `0191c61286d95415ba000a90b75754de22335bfc2e2c290b70a71ee30d67ec7b`（1.6.9 release APK） |

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

- 前台 Service + 常驻通知（Android 14+ 用 `FOREGROUND_SERVICE_SPECIAL_USE`、12–13 用 `FOREGROUND_SERVICE_DATA_SYNC`），后台常驻不被系统回收（`specialUse` 不受 `dataSync` 的 「24 小时内累计 6 小时」上限约束）；支持一键启停。
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

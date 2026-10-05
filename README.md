# Endfield Charge Plus for Android

**Endfield Charge Plus for Android** — Endfield Charge Plus 的 Android 原生实现。

这是一个真正的 Android 原生应用：Kotlin、Gradle Kotlin DSL、Jetpack Compose、Material 3、
AndroidX、Coroutines/Flow、DataStore，以及原生的 `Service` / `Notification` / `WindowManager`。
没有使用 Avalonia Android、Flutter、React Native、MAUI 或 WebView 套壳。

```
Endfield Charge Plus
├─ Windows
├─ Linux
├─ macOS
└─ Android   ← 本仓库（独立原生实现）
```

- 版本：`0.1.0`（versionCode 1）
- 包名：`com.glacierglimmer.endfieldchargeplus`
- `minSdk 26`，`compileSdk / targetSdk 36`
- 作者：GlacierGlimmer / 冰川雪貓
- 官网：zmd-bar.x-neko.com
- 基于上游项目 [QinAnze/zmd-charge](https://github.com/QinAnze/zmd-charge)（MIT）

---

## 一、产品构成

设置界面与屏幕 HUD 是两件不同的东西，这一点和桌面版本保持一致：

| | 界面 | 实现 |
| --- | --- | --- |
| **软件本体** | Android 原生设置应用（Material 3、edge-to-edge、深色/浅色、横竖屏、刘海/挖孔、任意 DPI） | Jetpack Compose，7 个页面：首页 / 显示 / HUD 内容 / 数据源 / 高级 / 变量库 / 关于 |
| **屏幕 HUD** | 终末地风格状态 HUD（几何线条、`/// MEMORY` 标签、环形进度、左右分区、图标） | 自定义 `View` + `Canvas`，同一个渲染器同时用于悬浮窗与设置页预览 |

显示方式支持两种：

- **悬浮窗**：`SYSTEM_ALERT_WINDOW` + `TYPE_APPLICATION_OVERLAY`，由前台服务维护。窗口尺寸只等于
  HUD 本身，不是全屏透明层；不抢焦点、可点击穿透、可拖动、横竖屏分别记忆位置、避让刘海。
- **灵动岛接口**（实验性）：统一 `IslandProvider` 抽象，当前提供 `AndroidLiveUpdateProvider`
  （Android 官方 promoted ongoing notification / Live Update）与 `XiaomiHyperIslandProvider`
  （小米超级岛）。设置页如实显示 自动 / Android 系统 / 小米超级岛 的可用与授权状态。

### 灵动岛的真实状态（重要）

- **Android 系统**：只有系统版本、通知权限、通知渠道与平台资格全部满足时才会标为可用；平台
  不允许任意自定义布局，因此能力列表会明确写出限制（无自定义布局、无左右分区、更新频率受限）。
- **小米超级岛**：需要小米开发者平台申请、场景审核与厂商授权。本应用**没有**该授权，因此状态
  显示为 `尚未授权 / 需要申请小米超级岛权限`。代码中不含任何反射调用私有系统 API 的逻辑，也不会
  伪造接入成功。
- 不会把普通通知伪装成灵动岛接入成功，也不会通过 Hack 绕过 Android 限制。

---

## 二、功能

- **数据采集**：内存、电池、网络速率、存储、时间 / 日进程，统一由 `MetricRepository` 通过
  Flow/StateFlow 提供给输出层；悬浮窗与灵动岛共享同一份数据，切换显示方式不会重建采集系统。
- **变量系统**：沿用 ECP 变量命名（`memory.usage`、`battery.remaining_mwh`、`probe.latency_ms`、
  `deepseek.balance` …），并兼容旧的 `ping.*` 与 `memory.usage_percent` 等写法。变量库页面可按分类
  与关键字检索，查看类型、单位、说明与常用格式。
- **模板与表达式**：`{变量}`、`{变量|格式}`、`{= 表达式}`、`{= 表达式|格式}`。格式是 `|` 串联管道
  （`gb`/`mb`/`kb`/`bytes`/`speed` 为 1024 进制，`mbps`/`kbps` 为十进制，另有 `math:`、`sub:`、
  `replace:`、`upper/lower`、`time:`、`auto:n`、`duration`、`percent` 等）。表达式支持四则运算、
  `^`、比较、`&& || !`、`?:`、`??` 以及 `if/min/max/avg/sum/clamp/round/floor/ceil/abs/...`。
- **方案系统**：内置方案（电池 / CPU / 内存 / GPU / 网络 / 系统盘 / 日进程 / DeepSeek 余额时段 /
  网络包探测器）+ 自定义方案；新建、保存、复制、删除、重命名、修改内置后另存为自定义、方案预览；
  自动轮播支持顺序调整、间隔设置与动画模式，删除被引用方案时自动清理轮播队列。
- **动画**：完整动画（6 秒基线时间轴：标题、波纹、圆形→方形形态切换、回弹）与简洁动画（5 秒基线，
  快速展开），方案切换统一走 `隐去 → 内容切换 → 唤出`，隐藏为固定 180ms 收起。
- **网络包探测器**：ICMP / TCP / UDP、IPv4 / IPv6 / 域名，HUD 左侧显示延迟、右侧显示丢包率。
  Android 普通应用无法可靠使用原始 ICMP，因此 TCP 连接延迟是主要路径，探测失败时显示原因而**不是**
  假的 `0ms` / `0%`。
- **DeepSeek API**：余额、当前高峰 / 低谷时段、时段剩余与进度，密钥使用 Android Keystore 加密保存，
  永不写入日志或诊断导出，请求频率不低于 60 秒并带失败退避。
- **自定义 HTTP / JSON 数据源**：GET 一个 JSON 接口并把字段映射为 `custom.<数据源>.<字段>`
  （同时镜像旧的 `http.` 前缀），仅允许 HTTPS（回环地址除外），有超时、响应体积上限与失败退避。
- **国际化**：简体中文 / English，默认跟随系统语言（任何 `zh*` 都使用简体中文，其它语言使用英文），
  可在设置中手动切换并持久化。界面、对话框、权限说明、通知、前台服务、错误信息、关于页、灵动岛状态
  与诊断信息都走同一套本地化通道，切到 English 后不会残留中文。
- **配置**：DataStore 持久化，敏感内容单独加密存储；方案与复杂配置使用 JSON，字段与桌面版本保持一致，
  目标是跨平台导入导出。导入非法文件时保留上一份配置（`config_previous`）而不是破坏现有设置。
- **诊断**：应用内日志查看器、可分享的诊断报告、硬件能力重新检测、权限状态总览。

---

## 三、Android 能读到什么（如实说明）

Android 普通第三方应用（无 Root）无法像桌面那样读取全部硬件数据。本应用只报告**真实读到**的数据，
读不到时按平台规则显示 `--`（与桌面版本一致的不可用标记），绝不返回 0 或随机值来冒充。

| 数据 | Android 状态 |
| --- | --- |
| 内存总量 / 可用 / 使用率 | ✅ 系统 API |
| 电池电量 / 温度 / 电压 / 充电状态 | ✅ 系统 API |
| 电池电流 | ⚠️ 设备支持时提供（`BATTERY_PROPERTY_CURRENT_NOW`），否则不可用 |
| 网络上下行速率、网络类型 | ✅ `TrafficStats` + `ConnectivityManager` |
| 存储总量 / 已用 / 剩余 | ✅ `StatFs` / `StorageManager` |
| 时间 / 日期 / 日进程 / 目标时间 | ✅ |
| 网络探测延迟 / 丢包 | ✅（TCP 为主，ICMP 尽力而为） |
| 进程内存明细（缓存 / Swap） | ⚠️ `/proc/meminfo` 可读时提供 |
| CPU 总占用率、每核心占用 | ⚠️ `/proc/stat` 可读时提供，部分设备受限 |
| CPU 频率 | ⚠️ 多数零售设备自 Android 10 起不可读 |
| CPU 温度 | ⚠️ 仅当存在可识别的 thermal zone |
| GPU 占用 / 频率 / 温度 / 显存 | ❌ 无公开 API，通常不支持（除非厂商节点真实可读） |
| Wi-Fi SSID | ❌ 需要位置权限，应用不会自行申请 |

设置中的「高级 → 重新检测硬件能力」会现场探测并列出每一项的结论与依据（探测了哪个 API 或 sysfs 节点、
为什么失败）。

---

## 四、构建

前置：JDK 17、Android SDK（`platforms;android-36`、`build-tools`、`platform-tools`）。

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-17"
$env:ANDROID_HOME="<你的 Android SDK 路径>"
# 或在 local.properties 写入 sdk.dir
.\gradlew.bat :core:test :app:testDebugUnitTest      # 单元测试
.\gradlew.bat :app:assembleDebug                     # 调试 APK
.\gradlew.bat :app:lintDebug                         # Android lint
.\gradlew.bat :app:assembleRelease                   # R8 发行构建
```

---

## 五、架构

```
:core（纯 Kotlin/JVM，可在 JVM 上直接测试）
  model / expression / template / metrics / i18n / json / product
:app（Android）
  ui / overlay / service / island / metrics / network / deepseek / data / permission / diagnostics / di
```

强制分层：UI ≠ 数据采集、UI ≠ 系统 API、Overlay ≠ 数据 Provider、Island ≠ Overlay、
配置 ≠ Compose State；`EcpContainer` 是唯一的对象图入口。详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

采样由统一的 `SamplingScheduler` 按档位（快速 / 普通 / 慢速 / 空闲）驱动，采集器不各自持有定时器；
HUD 隐藏、屏幕关闭或无人消费时会自动降频，网络请求有间隔下限与失败退避。

---

## 六、测试与验证状态

| 项目 | 结果 |
| --- | --- |
| `:core:test` | 74 项通过 |
| `:app:testDebugUnitTest` | 354 项通过 |
| `:app:lintDebug` | 0 error（21 warning，均为建议类） |
| `:app:assembleDebug` | 成功（app-debug.apk，约 19.7 MB） |
| `:app:assembleRelease`（R8） | 成功（未签名发行包，约 1.9 MB） |
| 真机验证 | **未完成**（本环境没有 Android 设备） |

需要真机验证的清单（悬浮窗授权与拒绝、权限撤销、横竖屏、挖孔屏、不同 DPI、Activity 划走、
Service 重启、锁屏、屏幕关闭、Wi-Fi ↔ 移动网络、无网络、IPv6、DeepSeek 错误、HTTP 超时、
配置损坏、语言切换、系统语言切换、Android 杀后台）记录在 [docs/TESTING.md](docs/TESTING.md)。
在真机验证完成前，**不要**把本版本描述为已通过设备测试。

---

## 七、许可与来源

- 本仓库：MIT License（见 [LICENSE](LICENSE)）。
- 基于 [QinAnze/zmd-charge](https://github.com/QinAnze/zmd-charge) 的二次开发，保留原作者与项目来源。
- 第三方组件与声明见 [NOTICE.md](NOTICE.md)。
- 本应用不上传用户数据；自定义 HTTP 数据源与网络探测只访问用户自行配置的地址。

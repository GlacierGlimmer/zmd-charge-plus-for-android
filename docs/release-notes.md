# Endfield Charge Plus for Android v0.1.1

Android 稳定版。此说明覆盖上一个发行 **v0.1.0-beta（Git 标签 `v0.1.0`）→ v0.1.1** 的变化。

- 产品版本：`v0.1.1`；Android 安装版本：`versionCode 2`。
- 构建日期：`2026.10.11`；系统要求：Android 8.0+，`minSdk 26` / `targetSdk 36`。
- 包名保持 `com.glacierglimmer.endfieldchargeplus`，沿用原正式签名证书，可覆盖升级旧发行包并保留应用数据。

## 修复

- 修复首次安装后默认悬浮窗 HUD 不显示、必须切换灵动岛后再切回才显示的问题；完善首次授权和返回应用时的启动流程。
- 修复开启灵动岛后应用 UI 卡顿、卡死与闪退风险，减少主线程工作，合并通知更新并串行处理，改善滑动条响应和采样节奏。
- 修复原生灵动岛紧凑/展开内容的数值拆分、截断和进度显示；首页的权限/支持状态在首次启动、恢复前台时及时刷新，不再依赖切换选项卡。
- 修复竖屏切横屏时悬浮窗抖动、尺寸比例过大和距离顶部过远；按当前窗口尺寸、屏幕方向及安全区域重新计算位置与缩放，并处理横竖屏历史位置。
- 修复语言初始化、即时汉化、部分变量说明、CJK 文字裁切与间距、磁盘可用空间别名，以及网络/电池/CPU 等数据读取与不可用状态处理。
- 修复更改采样频率后的调度与唤醒行为，减少不必要采样和后台工作。

## 新增与调整

- **移除小米超级岛接入**，保留 Android 原生 Live Updates/灵动岛，并移除后端选择与不适用/无效的设置。
- **检查更新**：加入关于页手动检查、启动时检查及新版本提醒，与 PC 使用相同的版本比较逻辑。
- **采样速率可自定义**；外部 API 保持自己的刷新策略，避免将界面刷新与昂贵的数据请求绑定。
- **自动轮播动画可选择**，按轮播顺序和所选动画切换方案。
- **变量库按 Android 实际数据源整理**，移除 PC 专用和占位变量；新增/完善 ROOT 可读取的 CPU、GPU、电池及内核数据。读取失败时明确显示不可用，不伪造读数。
- **真实 FPS**：新增“系统 - 显示器帧率”和“系统 - 窗口帧率”方案、目标选择及 100% 对应帧率设置；使用 ROOT/SurfaceFlinger 帧时间与计数，未使用屏幕刷新率冒充 FPS。
- **无效数据提示**：方案所需数据不可用或无效时显示缺失变量提示，并抑制同一故障持续期间的重复提醒。
- **DeepSeek 高峰/低谷判断修正**：按官方规则使用北京时间的高峰时段，结合中国法定节假日及调休安排；遵守周末始终低谷的规则。内置已公布安排与已知法定日期覆盖至 2030 年，支持年度日历联网校验更新及缓存；尚未公布完整安排时提示日历待更新。
- “关于”页新增本次构建日期的中英文显示；正式构建关闭开发调试日志与调试属性。

## 下载与验证

普通用户安装通用 **APK**；**AAB** 用于应用商店提交，不能直接安装。两者均为本次重新构建的正式签名 Release。

| 文件 | 字节数 | SHA256 |
| --- | ---: | --- |
| `EndfieldChargePlusForAndroid-v0.1.1-android-universal.apk` | 1985899 | `3dc2f7e7442a89c1864c9b230383c251488afea2e1addc4139fb07fd2c38b9ba` |
| `EndfieldChargePlusForAndroid-v0.1.1.aab` | 5071151 | `8402854a0cc3437de24d3c4fd4b8ce73bf441691c1135c013df9cfde1f2f6d72` |

独立校验和文件：`SHA256SUMS-Android.txt`。

APK 通过 v2/v3 签名及 16 KB 页对齐检查；AAB 的全部应用载荷已核验为原生产证书签名，清单无 `debuggable`/`testOnly`。生产证书 SHA256：`c542dae25771f940c7b0b336ec30bcc61245d757626853b48bb498d4aaea517e`。

本次验证：core **84** 项、Release app **385** 项单元测试通过（0 失败）；Release APK/AAB 构建及 R8 压缩通过；Lint **0 errors / 26 warnings**。修复包含用户实机反馈；ROOT 硬件指标、SurfaceFlinger FPS、不同 ROM 的 Live Updates 与后台保活仍取决于设备支持和授权。

## English

Stable Android v0.1.1, covering changes since the previous v0.1.0-beta release (tag `v0.1.0`). Build date: 2026.10.11; Android 8.0+, versionCode 2, unchanged production package and signing certificate.

- Fixed the missing first-install overlay, permission/support status initialization, sluggish sliders and UI freezes caused by island updates.
- Fixed collapsed/expanded native Live Update content and portrait-to-landscape overlay shaking, excessive size and vertical placement.
- Improved localization, text spacing, genuine CPU/battery/network/storage reads and sampling scheduling; removed Xiaomi HyperIsland and irrelevant controls.
- Added startup/manual update checks and notifications, a custom sampling interval, selectable carousel transitions, and consolidated unavailable-data feedback.
- Removed desktop-only/placeholder variables and added actual ROOT sensor sources. Added display and selected-window/layer FPS profiles from SurfaceFlinger data, selectable targets and the full-scale FPS setting; screen refresh rate is not used as FPS.
- Corrected DeepSeek period calculation using Beijing time, official windows, Chinese holidays and the always-off-peak weekend rule. Calendar coverage reaches 2030 with validated annual updates/cache and an explicit unknown state for unannounced schedules.
- Added the bilingual About build date. Both APK and AAB are production-signed non-debuggable Release builds. Install the universal APK; the AAB is intended for store submission.

Validation: 84 core + 385 Release app tests passed, APK v2/v3 and every AAB payload signature verified, APK alignment checked, Lint 0 errors / 26 warnings. ROOT/FPS and Live Updates availability remain device- and permission-dependent.

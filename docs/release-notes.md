# ECP For Android v0.1.2

正式版 · v0.1.1 → v0.1.2 · 2026-10-13

## 简体中文：本次变化

- 新增「时间 - 世界时钟」内置方案：可选择 39 个中英文固定 UTC 偏移，按所选时区计算时间、日进度与目标时间；仍可使用原有目标时间模式。
- 内置方案按系统、时间、网络探测、DeepSeek 等大类排列；保留已保存的自动轮播队列，避免更新后改变用户的切换顺序。
- 修正时区配置的序列化、归一化、方案编辑、变量解析和 HUD 渲染；世界时钟变量在数据检查中按真实值判断可用性。
- 版本字段统一为 v0.1.2，安装版本码由 2 升到 3；重新构建 R8 优化、资源压缩的正式 APK / AAB，沿用原生产签名证书，支持从 v0.1.1 覆盖升级。
- 关于页构建日期为 2026.10.13；正式构建禁止开发日志开关，无 debug 包名或版本后缀。

## English: changes since v0.1.1

- Add the World clock built-in with 39 bilingual fixed UTC offsets, local-day progress and the existing target-time modes.
- Group built-in profiles by category while preserving saved automatic-rotation queues.
- Wire time-zone settings through configuration, profile editing, variable resolution, unavailable-data checks and HUD rendering.
- Rebuild production APK/AAB as v0.1.2, versionCode 3, using the original production certificate for in-place upgrades from v0.1.1. R8 and resource shrinking remain enabled.
- Update the About build date to 2026.10.13. Production builds disable developer logging and use no debug package or version suffix.

## 运行与兼容性 / Requirements and compatibility

Android 8.0+；原生 Live Update 需 Android 16+ 且系统允许推广通知。ROOT FPS／硬件变量仍取决于设备与权限。UTC 选项为固定偏移，不自动切换夏令时。

Android 8.0+; native Live Update requires Android 16+ and promotion eligibility. ROOT metrics depend on the device and permissions. UTC choices are fixed offsets, without automatic daylight-saving changes.

## 验证 / Validation

477 项单元测试通过；Release lint 无错误（26 个非阻断警告）；APK／AAB 使用生产密钥签名，并核对版本与证书。

每个平台单独提供 SHA-256 校验文件。Use the platform-specific SHA256SUMS file to verify downloaded assets.

[完整源码变化 / Source comparison](https://github.com/GlacierGlimmer/zmd-charge-plus-for-android/compare/v0.1.1...v0.1.2)

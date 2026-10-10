# Android 实机反馈修复 · 2026-10-09 · r2

根据用户在小米手机上提供的两张实机截图及卡顿／闪退反馈修改。当前改动仅在本地，等待用户复测通过后再一起同步仓库。

- 移除小米超级岛提供器、授权入口、APP_ID／构建元数据及对应专用测试；旧配置的 XiaomiHyperIsland 选项迁移到 AndroidSystem。
- 修正“snapshot → render → setActiveProfile → requestImmediate → snapshot”的无限即时采样反馈；只有方案实际改变才触发额外采样。
- 滑块使用本地拖动状态和实时数值，松开时保存／应用；配置加载、规范化和持久化在 IO 线程处理，避免每次指针移动都写入整份配置。
- 已运行的原生通知不因重应用相同设置而被取消／重启；通知发布在后台合并，只保留最新待发布帧，最多每 5 秒一次，不重复发布相同内容。
- 收起短文本采用不超过 7 个字符的完整摘要；截图中的内存方案显示 `56%`。展开内容保留 `8.5/14.9 GB · 56%` 等完整指标，使用 BigTextStyle 与系统单条进度条；通知使用单色图标并提供打开应用的点击入口。
- 非有限进度值安全降级，零进度保留同一系统通知样式。

原生展示由 Android 系统决定，格式约束参考 [Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update) 与 [setShortCriticalText](https://developer.android.com/reference/android/app/Notification.Builder#setShortCriticalText(java.lang.String))。

本次自动验证：现有 core/app 单元测试、采样反馈回归、万次高频通知合并／5 秒限频、停止后的排队帧丢弃、旧配置迁移与截图指标映射，外加 Debug 构建、lint 和 APK 签名检查。当前未连接 Android 实机，实际流畅度与 ROM 展示仍由本轮手机复测确认。

测试包版本为 `0.1.0-debug-r2`，应用包名及 Debug 签名沿用原包。建议依次测试：HUD 关闭时拖动滑块 → 开启普通悬浮 HUD → 开启 Android 原生灵动岛 → 点击展开 → 连续操作设置 → 关闭后重新开启，记录有无卡顿、闪退及数值／图标异常。

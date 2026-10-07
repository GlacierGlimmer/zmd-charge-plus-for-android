# 小米超级岛客户端接入

本版本已实现本地客户端发送：使用原生通知携带 `miui.focus.param` JSON 和 `miui.focus.pics` 图标 Bundle，固定通知 ID 更新同一条通知，停止服务时取消通知。无需另加小米 SDK 或 MiPush。模板来自[小米官方开发指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)。通知投递成功表示通知栈接受了请求；是否实际显示超级岛由 ROM 决定。

当前客户端采用图文模板，可显示标题、数值文字和应用图标；没有实现专用进度条模板，能力提示会明确显示这一限制。

## 当前可用条件

- 小米／Redmi／POCO 设备，`notification_focus_protocol >= 3`。
- 有小米签发的 APP_ID，应用包名与注册信息匹配。
- 当前 APK 的签名指纹已登记，场景审核与正式权限或调试白名单已开通。
- 普通通知已开启，厂商 `canShowFocus` 查询明确返回允许。

未配置 APP_ID 时界面显示“需要申请小米超级岛权限”。协议或权限查询失败不会被当作已经授权。代码不会生成虚假 APP_ID，也不会绕过厂商鉴权。

## 获批后的构建

APP_ID 可用 Gradle 属性 `xiaomiHyperIslandAppId` 或环境变量 `ECP_XIAOMI_APP_ID` 提供。构建自动生成 Manifest 的 `com.xiaomi.xms.APP_ID` 与 `com.xiaomi.xms.BUILD_TYPE_DEBUG`，debug 为 true，release 为 false。

```powershell
./gradlew.bat :app:assembleRelease -PxiaomiHyperIslandAppId=小米签发的实际APP_ID
```

Release 仍需原有 `keystore.properties` 或 `ECP_KEYSTORE_PROPERTIES` 配置签名；没有签名材料时会输出不能直接安装的 unsigned APK。

Debug 默认使用独立包名 `com.glacierglimmer.endfieldchargeplus.debug`。若小米控制台登记的是正式包名，可在已登记 debug 签名指纹的前提下使用：

```powershell
./gradlew.bat :app:assembleDebug -PxiaomiHyperIslandAppId=小米签发的实际APP_ID -PxiaomiHyperIslandUseRegisteredPackage=true
```

这个选项会取消 `.debug` 包名后缀。与已有正式版签名不同的 APK 无法直接覆盖安装；保留并备份配置后再由发布者决定测试方式。

## 仍需发布者完成的厂商步骤

[官方接入流程](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132)要求开发者认证、在架应用、服务开通、签名登记、场景预审／正式审核、设备白名单联调和上线验证。客户端代码无法代替这些步骤。

当前 payload 的业务标识是 `charging`。正式提交应限定在用户主动启动、具有明确开始和结束的充电监测场景，并提交全过程方案。长时间常驻的通用 CPU／内存状态 HUD 不等于获准的充电场景；是否接受须以小米审核结果为准。不要把没有审核的通用状态方案作为已获批场景发布。

现场验证：在白名单 HyperOS 3 真机开启通知后启动灵动岛模式，确认大岛、小岛、展开通知的文字和图标，检查更新同一 ID、退出后消失、撤销焦点通知后正确显示未授权。当前工作环境没有连接 Android 真机，尚未完成上述 ROM 验证。

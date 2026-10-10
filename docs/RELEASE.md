# Android v0.1.1 发行说明

发行版本固定为 v0.1.1，versionCode 2。只有用户要求修改版本时才递增。

正式 APK 为覆盖四个 Android ABI 的通用安装包，最低 Android 8.0（API 26），目标 API 36。AAB 用于应用商店提交，不能直接安装。两者使用工作目录提供的正式密钥签名，证书与旧版正式 APK 一致；构建脚本通过 ECP_KEYSTORE_PROPERTIES 指定密钥配置，密钥及密码不进入源码或发行档案。

Release 构建关闭 debuggable，启用 R8 和资源压缩。开发日志开关不会出现在正式版界面，导入旧配置也不能启用开发日志。保留普通运行与错误日志，支持用户主动导出问题报告。

2026-10-10 发行检查：核心模块 72 项测试、应用 Release 模块 385 项测试通过，Release Lint 为 0 错误。APK 的 v2/v3 签名与 AAB 的 JAR 签名均通过完整性核验，公开证书 SHA-256 为 c542dae25771f940c7b0b336ec30bcc61245d757626853b48bb498d4aaea517e。

本次仅导出本地发行文件，没有创建 GitHub Release 或上传发行包。

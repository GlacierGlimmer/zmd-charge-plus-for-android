# Notices / 来源与第三方许可

## 本产品 / This product

**Endfield Charge Plus for Android** (`GlacierGlimmer/zmd-charge-plus-for-android`)

It is the Android edition of **Endfield Charge Plus**, an independent native Android
implementation that follows the Windows, Linux and macOS editions in product behaviour,
variable naming, scheme semantics, HUD styling and localization.

本产品是 **Endfield Charge Plus** 的 Android 版本，为独立的 Android 原生实现，
在功能行为、变量命名、方案语义、HUD 视觉语言与国际化上与其他平台版本保持一致。

## 原项目 / Upstream

Endfield Charge Plus is a modified derivative of the HUD project
[QinAnze/zmd-charge](https://github.com/QinAnze/zmd-charge).
本项目基于 QinAnze 的 zmd-charge 进行二次开发，保留原项目来源和署名。

The original project's README identifies its license as MIT. The upstream repository did
not expose a standalone LICENSE file at the time this notice was prepared; no unverified
original copyright wording is asserted here. The MIT LICENSE in this repository covers
GlacierGlimmer's contributions; original code/assets remain subject to their applicable
upstream terms.

原项目 README 声明其许可证为 MIT。本声明编写时，上游仓库未提供独立的 LICENSE 文件，
因此此处不主张任何未经核实的原始版权措辞。本仓库中的 MIT LICENSE 覆盖
GlacierGlimmer 的贡献；原始代码与资源的授权仍以其上游适用条款为准。

## 后续开发者 / Maintainer

**GlacierGlimmer / 冰川雪貓** — https://github.com/GlacierGlimmer

Project website / 项目网站: `zmd-bar.x-neko.com`

## Third-party components / 第三方组件

This Android edition is written in Kotlin and uses the Android platform APIs. It does
**not** use Avalonia, .NET, LibreHardwareMonitor or any desktop-only component.

本 Android 版本使用 Kotlin 与 Android 平台 API 编写，**不**使用 Avalonia、.NET、
LibreHardwareMonitor 或任何桌面专属组件。

### AndroidX / Jetpack (Android Jetpack)

- `androidx.core:core-ktx`
- `androidx.activity:activity-compose`
- `androidx.lifecycle:lifecycle-*`
- `androidx.navigation:navigation-compose`
- `androidx.datastore:datastore-preferences`
- `androidx.security:security-crypto`
- `androidx.compose.*` (Compose UI, Material 3, Material Icons)

Copyright The Android Open Source Project. Licensed under the Apache License, Version 2.0.

Project: https://developer.android.com/jetpack

### Kotlin and kotlinx

- `org.jetbrains.kotlin` (Kotlin standard library, Gradle plugin, Compose compiler plugin)
- `org.jetbrains.kotlinx:kotlinx-coroutines-*`
- `org.jetbrains.kotlinx:kotlinx-serialization-json`

Copyright JetBrains s.r.o. and Kotlin Programming Language contributors.
Licensed under the Apache License, Version 2.0.

Project: https://github.com/JetBrains/kotlin

### Android Gradle Plugin / Gradle

- Android Gradle Plugin — Copyright Google LLC, Apache License 2.0.
- Gradle — Copyright Gradle, Inc., Apache License 2.0.

### Runtime / transitive dependencies

Release builds also carry transitive dependencies brought in by the packages above. Their
original license metadata and upstream notices remain applicable. When preparing a public
binary release, keep this notice together with the source release information and retain
all license files required by the corresponding upstream components.

发行版本同样包含上述依赖带来的传递依赖，其原始许可信息与上游声明继续适用。
在发布公开二进制版本时，请将此声明与源码发布信息一并保留，并保留各上游组件要求的许可文件。

## Data and privacy / 数据与隐私

The Android edition does not upload user data. The DeepSeek API key is stored with
Android Keystore backed encryption, is never written to logs in clear text, and is never
included in diagnostics exports. Custom HTTP/JSON data sources contact only the URLs the
user configures, and the network packet probe contacts only the user-configured target.

本 Android 版本不会上传用户数据。DeepSeek API Key 使用 Android Keystore 加密保存，
不会以明文写入日志，也不会出现在诊断导出中。自定义 HTTP/JSON 数据源只访问用户
自行配置的地址；网络包探测器只探测用户自行配置的目标。

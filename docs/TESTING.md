# Testing and verification — Endfield Charge Plus for Android

This document records **what has actually been verified**, how to reproduce it, and — just as
importantly — **what still requires a real Android device**. Numbers in the summary tables are
updated from the commands below; nothing here is an estimate.

## 1. What can be verified without a phone

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-17"
$env:ANDROID_HOME="<android-sdk>"
cd <repo>
.\gradlew.bat :core:test :app:testDebugUnitTest --console=plain   # unit tests
.\gradlew.bat :app:assembleDebug --console=plain                  # debug APK
.\gradlew.bat :app:lintDebug --console=plain                      # Android lint
.\gradlew.bat :core:build :app:assembleRelease --console=plain     # R8 release build
```

| Check | Tool | Scope |
| --- | --- | --- |
| Compatibility core | `:core:test` (JUnit, pure JVM) | expression grammar, format pipeline, templates, variable registry, i18n resolution, config codec/normalizer |
| Android layers | `:app:testDebugUnitTest` | collectors/parsers, sampling scheduler, probe & DeepSeek parsing, island policy, overlay animation cues, config persistence, permission logic, UI state |
| Resource/lint health | `:app:lintDebug` | manifest, permissions, API-level misuse, unused/invalid resources. **`:core` is not analysed by AGP lint** (the Android Gradle Plugin treats a plain Kotlin/JVM module as an external dependency); its code is covered by `:core:test` and the Kotlin compiler only |
| Shrinkability | `:app:assembleRelease` | R8/ProGuard keeps serialization + reflection-free wiring |

Because these run on the JVM, they verify logic, parsing, arithmetic, policy, configuration
handling and localization — **not** Android runtime behaviour.

## 2. Emulator checks (optional but recommended before device testing)

An Android emulator (API 26, and again API 34+/36) can confirm: first-launch flow, permission
prompts appearing only when the feature is enabled, overlay window creation and dragging, HUD
rendering, foreground notification appearance, language switching without a restart, and config
import/export round-trips.

## 3. REQUIRES A REAL DEVICE — do not claim these as verified

The following cannot be validated in this environment and must be tested on physical hardware.
Each item lists what to do and what to watch for.

### 3.1 Overlay permission and window behaviour
- Fresh install: enable the overlay → the system overlay settings page appears; returning with the
  permission granted starts the HUD. Denying it must leave the app usable with a clear explanation.
- Revoke the permission while the HUD is running: the overlay must disappear and the runtime status
  must report the loss instead of crashing or looping.
- Confirm the overlay window is **only as large as the HUD**: touches outside the HUD must reach the
  app underneath.
- Click-through on: the HUD must not consume any touch; on: dragging must move the HUD and persist.
- The HUD must not steal focus and must not break the on-screen keyboard of other apps.

### 3.2 Screen configuration
- Portrait ↔ landscape rotation: the HUD must reappear at the position remembered for that
  orientation.
- Devices with a display cutout/notch: with cutout avoidance enabled the HUD must not be clipped.
- Multiple DPI settings and font scales: the HUD must stay legible and correctly scaled.
- Foldable/inner-outer display switch if available.

### 3.3 Service lifecycle
- Swipe the Activity away: the HUD must keep running while Android allows it.
- Notification action `关闭 HUD` must stop the service and remove the overlay.
- Device reboot with "start on boot" enabled: the HUD restarts only when the user enabled it
  (and vendor battery policies may still block it — document what the device does).
- Aggressive vendor task killers (Xiaomi/Huawei/OPPO/vivo): record whether the HUD survives and
  whether the user must whitelist the app.

### 3.4 Metric plausibility (per device)
Compare the HUD against a trustworthy source while under load:
- Memory total/used/percentage vs. system settings.
- Battery percentage, temperature, voltage, current (sign/attribution varies by ROM — the app only
  reports a current when the value is trustworthy).
- Storage: system and data volumes vs. system storage page.
- Network: download/upload rates while downloading a large file; Wi-Fi ↔ cellular handover must
  switch the reported network type.
- CPU: `/proc/stat` derived usage and frequency availability differ per vendor — check that
  unsupported values show the sentinel rather than a number.
- GPU: on most devices every GPU metric must be reported as unsupported. **If a number appears,
  verify that a real sysfs node produced it.**

### 3.5 Network packet probe
- IPv4, IPv6 and hostname targets; ICMP is best-effort on Android, so also test TCP.
- Airplane mode / no network: the probe must report a status reason, never `0 ms` and never `0 %`.
- Packet loss: disable Wi-Fi momentarily and confirm the loss percentage moves and recovers.
- Long runs: no socket leaks, no wake-lock abuse, no measurable battery drain.

### 3.6 DeepSeek API
- Without a key: the balance variables must be reported as unavailable with the "key required"
  explanation, and no request may be sent.
- With a key: balance, peak/off-peak period, remaining time and progress must match the server data;
  check that the log and the diagnostics export contain only a masked key.
- Wrong key / no connectivity: clear error status, no crash, no repeated hammering (respect the
  ≥ 60 s cadence and backoff).

### 3.7 Custom HTTP/JSON sources
- A plain HTTPS JSON endpoint mapping into `custom.<source>.<field>`; confirm values update at the
  configured interval and that a failure turns the variables unavailable rather than stale.
- Cleartext HTTP to a non-loopback host must be refused (documented policy).

### 3.8 Island / 灵动岛
- Android 16+ device: confirm the settings page reports the real eligibility state, and that a
  device/scenario the platform refuses shows "不支持/不可用" — never a false success.
- Xiaomi HyperOS device: without vendor authorization the page must show
  `尚未授权 / 需要申请小米超级岛权限`. **Do not claim HyperIsland support** until an authorized
  vendor integration actually exists.

### 3.9 Configuration and language
- Export → reinstall → import: schemes, carousel queue, HTTP sources and DeepSeek windows must
  survive; the API key is intentionally not exported in clear text.
- Import a deliberately corrupted file: the previous configuration must be preserved and no crash.
- Switch to English everywhere (including dialogs, permission text, island states, notifications,
  diagnostics): no Chinese text may remain. Switch back and reboot to confirm persistence.
- Change the system language while `Auto` is selected: the app must follow it.

## 4. Known limitations stated honestly in the product

- GPU load/frequency/temperature/VRAM: no public Android API; reported unsupported unless a vendor
  sysfs node is genuinely readable and sane.
- CPU temperature and cpufreq: frequently blocked by SELinux on retail devices; capability-scanned.
- Raw ICMP requires `CAP_NET_RAW`; TCP-connect latency is the reliable probe path.
- Wi-Fi SSID requires location permission, which the app never requests on its own.
- Xiaomi HyperIsland requires vendor authorization from the Xiaomi developer platform and is
  therefore reported as 尚未授权 rather than "supported".

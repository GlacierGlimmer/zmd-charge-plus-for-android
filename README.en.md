# Endfield Charge Plus for Android

**Endfield Charge Plus for Android** — a native Android implementation of Endfield Charge Plus.

This is a real native Android application: Kotlin, Gradle Kotlin DSL, Jetpack Compose, Material 3,
AndroidX, Coroutines/Flow, DataStore, and the platform `Service` / `Notification` / `WindowManager`
APIs. It is not Avalonia Android, Flutter, React Native, MAUI, or a WebView shell.

```
Endfield Charge Plus
├─ Windows
├─ Linux
├─ macOS
└─ Android   ← this repository (an independent native implementation)
```

- Version: `v0.1.1` (versionCode 2); changed only on user request, with no automatic test suffix. See [version policy](docs/VERSIONING.md).
- Package: `com.glacierglimmer.endfieldchargeplus`
- `minSdk 26`, `compileSdk / targetSdk 36`
- Author: GlacierGlimmer / 冰川雪貓
- Website: zmd-bar.x-neko.com
- Based on [QinAnze/zmd-charge](https://github.com/QinAnze/zmd-charge) (MIT)

---

## 1. Product structure

The settings application and the on-screen HUD are deliberately two different things, exactly as on
the desktop platforms:

| | Surface | Implementation |
| --- | --- | --- |
| **Application** | A native Android settings app (Material 3, edge-to-edge, dark/light, portrait/landscape, cutouts, any DPI) | Jetpack Compose, seven pages: Home / Display / HUD Content / Data sources / Advanced / Variables / About |
| **On-screen HUD** | The Endfield-style status HUD (geometric lines, `/// MEMORY` tagline, progress ring, left/right areas, icons) | Custom `View` + `Canvas`; the same renderer serves the overlay window and the settings preview |

Two display modes:

- **Overlay** — `SYSTEM_ALERT_WINDOW` with a `TYPE_APPLICATION_OVERLAY` window owned by a foreground
  service. The window is only as large as the HUD itself (never a full-screen transparent layer), it
  does not take focus, supports click-through, can be dragged, remembers its position per orientation,
  and avoids display cutouts.
- **Island interface** (experimental) — `AndroidLiveUpdateProvider` uses Android's official promoted
  ongoing notification / Live Update. Settings show native Android availability and authorization.
  The Xiaomi integration has been removed; old selections migrate to Android system.

### The honest state of the island layer

- **Android system**: only marked available when the platform version, notification permission,
  channel state and platform eligibility all pass. Arbitrary custom layouts are not permitted by the
  platform. Settings retain only supported notification content and progress options; devices below Android 16 offer overlay mode only.
- The chip shows a short metric summary; expanded notifications keep all readings and a native
  progress bar. Rapid changes are merged and published no more than once every five seconds.
- A plain notification is never presented as a successful island connection, and Android limits are
  never bypassed with hacks.

---

## 2. Features

- **Data collection** — CPU/GPU, memory, battery, network rates, storage, time/day progress. A single
  `MetricRepository` publishes Flow/StateFlow data to every output; the overlay and the island layer
  share it, so switching display mode never rebuilds the collection system.
- **Variable system** — ECP variable names are preserved (`memory.usage`, `battery.remaining_mwh`,
  `probe.latency_ms`, `deepseek.balance`, …) with compatibility for legacy spellings such as `ping.*`
  and `memory.usage_percent`. The Variables page browses and searches the registry with type, unit,
  description and common formats. The fixed Android catalog contains 91 variables backed by collectors; per-core and HTTP fields come from actual snapshot keys. Desktop-only and unimplemented entries are omitted.
- **Templates and expressions** — `{variable}`, `{variable|format}`, `{= expression}` and
  `{= expression | format}`. Formats are a chained `|` pipeline (`gb`/`mb`/`kb`/`bytes`/`speed` are
  binary, `mbps`/`kbps` are decimal-SI, plus `math:`, `sub:`, `replace:`, `upper/lower`, `time:`,
  `auto:n`, `duration`, `percent`). Expressions support arithmetic, `^`, comparisons, `&& || !`, `?:`,
  `??` and `if/min/max/avg/sum/clamp/round/floor/ceil/abs/...`.
- **Schemes** — built-in schemes (Battery / CPU / Memory / GPU / Network / System Disk / Day Progress
  / DeepSeek balance & period / Packet Probe) plus custom schemes: create, save, copy, delete, rename,
  save a modified built-in as a custom scheme, and preview. Auto-cycling supports reordering, an
  interval and an animation mode, and deleting a referenced scheme cleans up the carousel queue.
- **Animation** — the full animation (6 s baseline timeline: title, ripples, circle→square shape
  morph, bounce) and the simple animation (5 s baseline, immediate reveal). Scheme switches always run
  `hide → swap content → reveal`, and hiding is a fixed 180 ms collapse.
- **Packet probe** — ICMP / TCP / UDP against IPv4, IPv6 or a hostname, with latency on the left of
  the HUD and packet loss on the right. Since an unprivileged Android app cannot rely on raw ICMP,
  TCP connect latency is the practical path; failures show a reason, never a fabricated `0 ms` / `0 %`.
- **DeepSeek API** — balance, current peak/off-peak period, remaining time and progress. The API key is
  stored with Android Keystore backed encryption, is never logged or exported, and requests are limited
  to at most one per minute with failure backoff.
- **Custom HTTP/JSON sources** — GET a JSON endpoint and map fields to
  `custom.<source>.<field>` (the legacy `http.` prefix is mirrored), HTTPS-only except loopback, with
  timeouts, a response size cap and failure backoff.
- **Localization** — Simplified Chinese and English, defaulting to the system language (any `zh*`
  locale uses Simplified Chinese, everything else English), switchable in the app and persisted. The UI,
  dialogs, permission explanations, notification, foreground service, error messages, About page,
  island states and diagnostics all use one localization path, so switching to English leaves no
  Chinese text behind.
- **Configuration** — DataStore persistence with sensitive values stored separately; schemes and
  complex configuration are JSON with the same field names as the desktop editions, aiming at
  cross-platform import/export. A corrupt import keeps the previous configuration
  (`config_previous`) instead of destroying the user's settings.
- **Update checks** — once per app process startup, plus a manual check in About. The Android repository latest Release/Tag is compared using numeric versions; a new release prompts in the app and posts a notification when allowed. Downloads open the stable Releases page.
- **Root collection** — an opt-in switch in Advanced requests Root for a persistent read-only channel to restricted CPU/GPU/memory nodes. Missing hardware nodes still render `--`. The channel closes when both the app UI and HUD stop; temporary permission failures never erase scheme expressions.
- **Diagnostics** — an in-app log viewer, a shareable diagnostics report, hardware capability
  re-detection and a permission overview.

---

## 3. What Android actually allows (stated plainly)

An unprivileged third-party Android app cannot read everything a desktop can. This application reports
only data it really read; when a metric is unavailable it renders the platform sentinel `--` (the same
marker the desktop editions use) and never substitutes `0` or a random value.

| Metric | Android status |
| --- | --- |
| Memory total / available / usage | ✅ system API |
| Battery level / temperature / voltage / charging state | ✅ system API |
| Battery current | ⚠️ when the device supports `BATTERY_PROPERTY_CURRENT_NOW`, otherwise unavailable |
| Network up/down rates, network type | ✅ `TrafficStats` + `ConnectivityManager` |
| Storage total / used / free | ✅ `StatFs` / `StorageManager` |
| Time / date / day progress / target time | ✅ |
| Probe latency / packet loss | ✅ (TCP primary, ICMP best-effort) |
| Memory detail (cached / swap) | ⚠️ when `/proc/meminfo` is readable |
| CPU total and per-core usage | ⚠️ when `/proc/stat` is readable; restricted on some devices |
| CPU frequency / maximum frequency | ⚠️ real cpufreq nodes; authorized Root can supplement access |
| CPU temperature | ⚠️ real CPU thermal zones, optionally via Root; never battery temperature |
| GPU load / frequency / temperature / model | ⚠️ real KGSL/Mali/devfreq/thermal nodes, optionally via Root; unavailable when absent |
| VRAM / Wi-Fi SSID / desktop-only metrics | Omitted from the library; this version has no applicable reader |

Advanced → Re-detect hardware capabilities probes the device live and lists every verdict with its
evidence (which API or sysfs node was probed and why it failed). Root changes trigger a new scan. Selected overlay anchors remain stable during rotation; per-orientation coordinates apply only after a manual drag. Display offers a button to restore the selected anchor.

---

## 4. Building

Requirements: JDK 17 and an Android SDK with `platforms;android-36`, `build-tools` and
`platform-tools`.

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-17"
$env:ANDROID_HOME="<your Android SDK>"
# or set sdk.dir in local.properties
.\gradlew.bat :core:test :app:testDebugUnitTest      # unit tests
.\gradlew.bat :app:assembleDebug                     # debug APK
.\gradlew.bat :app:lintDebug                         # Android lint
.\gradlew.bat :app:assembleRelease                   # R8 release build
```

---

## 5. Architecture

```
:core  (pure Kotlin/JVM, unit-testable without an emulator)
  model / expression / template / metrics / i18n / json / product
:app   (Android)
  ui / overlay / service / island / metrics / network / deepseek / data / permission / diagnostics / di
```

The layering is enforced: UI ≠ data collection, UI ≠ system APIs, overlay ≠ data provider,
island ≠ overlay, configuration ≠ Compose state. `EcpContainer` is the single object graph entry
point. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

Sampling is driven by one `SamplingScheduler` per tier (fast / normal / slow / idle); collectors never
own timers. The cadence drops automatically when the HUD is hidden, the screen is off or nothing is
consuming data, and network requests have interval floors and failure backoff.

---

## 6. Release checks

| Item | Result |
| --- | --- |
| `:core:test` | v0.1.1: 72 tests passed |
| `:app:testReleaseUnitTest` | v0.1.1: 385 tests passed, including the production logging guard |
| `:app:lintRelease` | 0 errors and 26 non-blocking warnings |
| `:app:assembleRelease` / `:app:bundleRelease` | R8 optimization, resource shrinking, APK and AAB signed with the production key |
| Application identity | `com.glacierglimmer.endfieldchargeplus`, v0.1.1, versionCode 2 |
| Signing continuity | Same certificate as the v0.1.0 production APK, allowing in-place upgrades |
| Production UI | Development logging controls are hidden; imported settings cannot enable development logging |

Startup, permission refresh, Live Updates, UI stalls and orientation issues were corrected from device feedback. Build verification and device coverage are recorded separately. See [docs/TESTING.md](docs/TESTING.md) and [docs/RELEASE.md](docs/RELEASE.md).

---

## 7. Licence and attribution

- This repository: MIT License (see [LICENSE](LICENSE)).
- Derived from [QinAnze/zmd-charge](https://github.com/QinAnze/zmd-charge); the upstream project and
  its authorship are preserved.
- Third-party components and notices: [NOTICE.md](NOTICE.md).
- The app does not upload user data; custom HTTP sources and the packet probe only contact
  user-configured addresses.

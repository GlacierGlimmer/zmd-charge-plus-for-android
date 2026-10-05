# Architecture — Endfield Charge Plus for Android

This document explains how the Android edition is put together and, more importantly, *why*.
It is the reference for the layering rules that the source code enforces.

## 1. Modules and packages

```
EndfieldChargePlusAndroid
├─ :core   pure Kotlin/JVM — no Android dependency, unit-testable on the JVM
│    core.model      configuration + scheme model (ECP-compatible field names)
│    core.expression expression engine        ({= expr | fmt })
│    core.template   template engine + value formatter ({var|fmt}, "--" sentinel)
│    core.metrics    variable names, snapshot, variable registry
│    core.i18n       UiLanguage, bilingual string API, built-in scheme localization
│    core.json       config codec + cross-platform normalizer
│    core.product    product identity (name, version, author, licence)
└─ :app    Android application
     ui.*            Jetpack Compose + Material 3 settings application
     overlay.*       floating HUD window (custom View + Canvas), position, animation
     service.*       foreground service, notification, boot receiver, runtime state
     island.*        island/live-update provider abstraction and implementations
     metrics.*       collectors, unified sampler, MetricRepository, capabilities
     network.*       packet probe, HTTP/JSON sources
     deepseek.*      DeepSeek balance client and collector
     data.*          DataStore configuration, encrypted secret storage, file transfer
     permission.*    permission state and request policy
     diagnostics.*   logging, diagnostics report
     di.*            explicit object graph (no DI framework)
```

`:core` is a plain Kotlin/JVM library on purpose: the whole compatibility surface (templates,
expressions, formatting, configuration model, normalizer, localization) can be tested with
`./gradlew :core:test` in seconds and without an emulator.

## 2. The layering rules

The task specification requires six separations. They are enforced structurally:

| Rule | How it is enforced |
| --- | --- |
| UI ≠ data collection | Compose screens only read `MetricRepository.snapshot`; no screen touches `ActivityManager`, `StatFs`, `TrafficStats` or `/proc`. |
| UI ≠ system API | System APIs live in `metrics.*`/`hardware.*` behind `MetricCollector`. |
| Overlay ≠ data provider | `OverlayHudView` draws a `HudRenderData`; it never reads a metric. |
| Island ≠ overlay | Both consume the same `HudRenderData` from `HudStateBuilder`; neither knows about the other. |
| Config ≠ Compose state | `AppConfig` is a serializable model written through `ConfigRepository`; Compose state is derived, never serialized. |
| Shared data layer | One `MetricRepository` instance is shared by overlay and island output. Switching display mode swaps the renderer only. |

```
Android system APIs
        │  (collectors, sampling tiers)
        ▼
  MetricRepository ──► MetricSnapshot (Flow)
        │                    │
        │             ExpressionEngine / TemplateEngine ──► HudRenderData
        │                    │
        │            HudStateBuilder (schemes, colour rules, progress)
        ▼                    │
   sampling demand           ├────────────► OverlayHudView  (WindowManager overlay)
                             └────────────► IslandProvider   (platform / vendor island)
```

## 3. Sampling and power

Collectors never own a timer. `SamplingScheduler` runs exactly one coroutine per tier
(FAST / NORMAL / SLOW / IDLE) and calls the collectors whose tier is due. The cadence comes from
`AppConfig.android.fastRefreshMs`, `normalRefreshMs`, `slowRefreshMs`, `idleRefreshMs`, and the
demand signal (`MetricDemand`) throttles further when the HUD is hidden, the screen is off, or
nothing is consuming data. Remote sources get their own floors: the packet probe is single-flight,
custom HTTP sources are clamped to ≥ 5 s with backoff, and the DeepSeek balance is never requested
more than once a minute.

## 4. The variable system

Variable names are a cross-platform contract. `core.metrics.Variables` holds the canonical names
(`memory.usage`, `battery.remaining_mwh`, `probe.latency_ms`, `deepseek.balance`, …) and
`VariableAliases` maps legacy spellings (`ping.*`, `memory.usage_percent`) onto them, so a scheme
written for the desktop editions keeps working.

A metric that Android cannot read is **never** reported as `0`. Collectors publish
`MetricValue.Unavailable(reason)` (or omit the variable), and the template layer renders the
platform sentinel `--`, exactly like the desktop editions. `HardwareCapabilities` records the
evidence for every verdict (which API or sysfs node was probed and why it failed), and the
settings UI shows that reasoning to the user.

## 5. Templates and expressions

`{variable}`, `{variable|format}`, `{= expression}` and `{= expression | format}` are supported with
the same grammar as the Windows/Linux/macOS editions, including the 29 whitelisted expression
functions and the chained `|` format pipeline (binary `gb/mb/kb/bytes/speed` units, decimal-SI
`mbps/kbps`, `math:`, `sub:`, `replace:`, `upper`, `lower`, `time:`, `auto:n`, `duration`,
`percent`). This is what makes a scheme semantically portable between platforms.

## 6. Output modes

* **Overlay mode** — a real `TYPE_APPLICATION_OVERLAY` window sized as small as the HUD itself,
  owned by `HudForegroundService`. It is non-focusable, optionally non-touchable, draggable when
  interactive, restored per orientation, and aware of display cutouts.
* **Island mode** — `IslandProvider` implementations. `AndroidLiveUpdateProvider` gates on the
  official promoted-ongoing-notification API and reports its real limitations (no arbitrary
  RemoteViews, no continuous hardware streaming, scenario restrictions).
  `XiaomiHyperIslandProvider` detects HyperOS with public information only and reports
  `尚未授权 / 需要申请小米超级岛权限` until the vendor SDK is legitimately available. Neither path
  ever fakes a successful connection, and neither uses reflection into private system APIs.

## 7. Configuration and portability

`AppConfig` mirrors the desktop `AppSettings` field names and defaults for everything that exists on
all platforms, and keeps Android-only settings in a nested `AndroidSettings` block. Schemes
(`CustomHudSettings`), colour rules, carousel queue, HTTP sources and DeepSeek peak windows use the
same shape as the desktop editions, so an exported configuration can be imported on either side.
`ConfigNormalizer` reproduces the desktop normalizer (re-materialized built-ins, carousel cleanup,
range clamping) and keeps the previous configuration so nothing is destroyed silently.

Secrets are separate from configuration: the DeepSeek API key lives in Android Keystore backed
encrypted storage, is registered for log redaction, and is never written to an export.

## 8. Localization

Every user-visible string in the app, the HUD, the notification and the island layer goes through
the same bilingual API (`Strings.t(zh, en)` with the dictionary ported from the desktop
`LocalizationManager`). `UiLanguage.resolve` implements the shared rule: any `zh*` locale →
Simplified Chinese, otherwise English; an explicit user choice always wins and is persisted.

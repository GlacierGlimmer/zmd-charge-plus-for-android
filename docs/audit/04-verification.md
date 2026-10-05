# 04 — Independent Verification (adversarial) — Endfield Charge Plus for Android

Verifier: teammate `audit-platforms-data` (independent of the implementers).
Target: `D:\ECP_Workspace\zmd-charge-plus-for-android`.
Acceptance contract: `C:\Users\Glaci\Downloads\DeepSeek Harness：Endfield Charge Plus for Android 完整开发任务.md` (1 325 lines, 30 numbered sections).
Method: read the contract in full, then read the deliverable source directly (no reliance on implementer
summaries), run the build tool myself, and grep for every prohibited construct.

## 0. Revision under verification, and one important caveat

**The deliverable was not frozen while it was being verified.** The repository has **no commits at all**
(`git -C … log` → `fatal: your current branch 'main' does not have any commits yet`; every path is
untracked), and source files kept changing during this audit (latest observed writes
`app/src/main/res/values/colors.xml` 16:30:11, `values/strings.xml` 16:30:02). The Lead also changed
several files after my first pass (notification localization, R8 keep rules, theme API-level split,
adaptive-icon folder, `OverlayHudView.performClick`, two deprecation fixes).

Consequently:
* every finding below was **re-checked against the current on-disk revision** unless explicitly marked;
* **no commit hash can be cited** — this is itself a traceability finding (§7.1);
* the build numbers in Part 6 are labelled with the revision they came from.

Build contention: the Lead and I both invoked Gradle in the same project directory. The Lead reported
that this corrupted their R8/lint run. I observed the mirror image (`:app:minifyReleaseWithR8` →
`R8: java.nio.file.NoSuchFileException … intermediates\dex\release\minifyReleaseWithR8`;
`clean` → "Failed to delete some children"). **These are contention artefacts and are NOT reported as
deliverable defects.** I stopped all my Gradle invocations; the authoritative full run is the Lead's.

**Two verification phases.** Phase 1: I read the tree and ran Gradle myself (numbers in Part 6.1).
Phase 2: after the Lead's follow-up fixes, I re-verified every affected file **by reading source only**
and pasted the Lead's exclusive clean-run numbers into Part 6.2. I did **not** re-run the build in
phase 2 (by agreement, to avoid repeating the contention), so Part 6.2 is the Lead's measurement and
the phase-2 source claims are read-verified, not execute-verified. Contentions and corrections against
my own phase-1 findings are marked inline as "FIXED and re-verified" or "withdrawn".

---

## Part 1 — Verdict summary per task section

Legend: **IMPLEMENTED** = present and verified in code; **PARTIAL** = present but with a stated gap;
**MISSING** = absent; **NOT APPLICABLE** = the contract item cannot apply to Android.

| § | Section | Verdict | Key evidence |
| --- | --- | --- | --- |
| 一 | Read existing ECP sources / audit before coding | IMPLEMENTED | `docs/audit/01-windows-core.md` (168 081 B), `docs/audit/02-platforms-data.md` (130 299 B), `docs/audit/03-island-platforms.md` (19 694 B) |
| 二 | Independent Android project; Kotlin/Compose/M3/AndroidX/Coroutines/DataStore; no Avalonia/Flutter/RN/MAUI/Electron/WebView; 0.1.0/1; package; minSdk 26; latest SDK; no targetSdk lowering | IMPLEMENTED | `app/build.gradle.kts:10` `compileSdk = 36`, `:14` `minSdk = 26`, `:15` `targetSdk = 36`, `:16` `versionCode = 1`, `:17` `versionName = "0.1.0"`, `:13` `applicationId = "com.glacierglimmer.endfieldchargeplus"`. No WebView/Flutter/etc. anywhere (Part 3) |
| 三 | App = native Android UI; HUD = ECP/Endfield style, not confused | IMPLEMENTED | `ui/` is Compose + Material 3 (`ui/theme/Theme.kt`, `ui/navigation/EcpApp.kt`); HUD is a separate custom `View` drawing `HudRenderData` (`overlay/OverlayHudView.kt`) |
| 四 | Mode A overlay: `SYSTEM_ALERT_WINDOW`, `TYPE_APPLICATION_OVERLAY`, `canDrawOverlays()` guidance, FGS, open/close, drag, top-centre/left/right/free, X/Y, size/scale/opacity, click-through, interactive, orientation restore, rotation, cutout, safe area, schemes, animation, live refresh; **never a full-screen overlay**; no focus stealing | IMPLEMENTED | `overlay/DefaultOverlayController.kt:275-288` `WRAP_CONTENT`×2 + `TYPE_APPLICATION_OVERLAY` + `PixelFormat.TRANSLUCENT`; `:349-359` `FLAG_NOT_FOCUSABLE` unconditional, `FLAG_NOT_TOUCHABLE` when click-through else `FLAG_NOT_TOUCH_MODAL`; `:361-368` cutout mode; `:206-246` per-orientation persisted position; `overlay/OverlayPositionManager.kt:190-265` safe area incl. cutout insets; `:83-120` resolve per orientation |
| 五 | Mode B island abstraction (`IslandProvider`) with 自动/Android 系统/小米超级岛, only truly supported providers, honest 不支持/不可用 | IMPLEMENTED | `island/IslandProvider.kt:17-54`; `island/IslandAvailabilityState` `:86-101`; `island/IslandProviderRegistry.kt:68-69,75-79,82-83,155-159`; `ui/screens/display/DisplayScreen.kt:279-283,450-454`. Latent selection bug — §7.3 |
| 六 | Android Live Update / promoted ongoing notification, official APIs only, `实验性`, capability+version+permission detection, no hacks, no faking, correct degradation | IMPLEMENTED | `island/AndroidLiveUpdateProvider.kt:311` `MIN_PROMOTED_API_LEVEL = 36`; `:167-187` evaluate; `:197-219` probes the platform's own promotability check; `:328-346` `experimental = true`, `maxUpdateHz = 0.2`, five `limitationKeys`; `:389-425` policy maps every branch to a state+reason. Explicitly does **not** claim promotion happened (`:180-183`) |
| 七 | Xiaomi HyperIsland: HyperOS/device/API/authorization/scene detection, publish/update/end, 尚未授权 + 需要申请小米超级岛权限, **no reflection into private APIs** | IMPLEMENTED | `island/XiaomiHyperIslandProvider.kt:440-508` policy (device → HyperOS marker → focus protocol ≥ 3 → bridge → notifications → focus permission); `:352-378` `NotIntegratedBridge` (`isIntegrated() = false`, `publish(...) = false`); `:86-108` refuses to start; `:195-203` uses the documented public `canShowFocus` provider, never reflection; docs URLs only logged |
| 八 | Data collection architecture: `MetricRepository` + named collectors + Flow, UI fully separated | IMPLEMENTED | `metrics/DefaultMetricRepository.kt:67-82` registers `Memory/Battery/Network/Storage/Time/Cpu/Gpu/Device` + external `Probe/HttpJson/DeepSeek` collectors; `:48-50` `StateFlow<MetricSnapshot>`; `metrics/SamplingScheduler.kt:43-190` |
| 九 | Required data: memory, battery, network, storage, time/日进程, probe (IPv4/IPv6/domain/ICMP/TCP/UDP, `??ms` / `丢包??%` / `Loss ??%`), DeepSeek, HTTP/JSON | IMPLEMENTED | Memory `metrics/MemoryCollector.kt:30-93`; Battery `metrics/BatteryCollector.kt:58-249`; Network `metrics/NetworkCollector.kt:187-205`; Storage `metrics/StorageCollector.kt:29-67`; Time `metrics/TimeCollector.kt:32-64`; Probe `network/ProbeCollector.kt:67-187`; DeepSeek `deepseek/DeepSeekCollector.kt`+`DefaultDeepSeekClient.kt`; HTTP/JSON `network/HttpJsonCollector.kt`+`JsonPath.kt`. Probe HUD sentinel: `core/template/TemplateEngine.kt:38` `UNKNOWN_SENTINEL = "--"` (see §5.2) |
| 十 | CPU/GPU honesty: implement when stable, capability-detect when partial, **不支持** when unprivileged, never fake/0; no root dependency | IMPLEMENTED | `metrics/CpuCollector.kt:42-96` two-sample rule + per-core; `:98-113` cpufreq; `metrics/GpuCollector.kt:28-69` vendor nodes only, else `NOT_SUPPORTED`/`NOT_AVAILABLE_ON_DEVICE` with probed paths; `core/model/MetricValue.kt:26-31,48-69`. No root/Shizuku dependency anywhere |
| 十一 | Variable system preserved: `{var}`, `{var\|fmt}`, `probe.*`, legacy `ping.*`, cross-platform names (e.g. `memory.usage_percent`), unsupported → Unavailable not 0 | IMPLEMENTED | `core/template/TemplateEngine.kt:11-14,70-80`; `core/metrics/Variables.kt:161-179` aliases incl. `"memory.usage_percent" to Variables.MEMORY_USAGE` and all `ping.*`; `network/ProbeCollector.kt:178-186` mirrors `ping.*` |
| 十二 | Custom expressions ported: multi-variable, arithmetic, comparison, `if`, formatting, numeric conversion, text composition; no incompatible new language | IMPLEMENTED | `core/expression/ExpressionEngine.kt`; `core/template/ValueFormatter.kt`; `TemplateEngine.kt:164-198` split logic ported from `TemplateEngine.SplitExpressionAndFormat`; 16 `ExpressionEngineTest` cases pass (`> if min max avg sum clamp round`, `> ternary is eager and right associative`, …) |
| 十三 | Scheme system: built-in/custom, new/save/copy/delete/rename, **修改内置后另存为自定义**, preview, carousel order/interval, delete cleans carousel references, 隐去→内容切换→唤出, 完整/简洁 | IMPLEMENTED | `ui/state/ProfileEdits.kt:49,73,89,105,127,161` (`saveBuiltInAsCustom`, `deleteProfile` → `cycleProfileIds = queue` `:140`); `ui/screens/content/ContentViewModel.kt:135-198` (`:140-149` built-in → `saveBuiltInAsCustom`, `:183-192` built-ins refuse deletion); `overlay/DefaultOverlayController.kt:155-178` + `HudAnimator.swapContent`; `overlay/HudCycleOrder.kt` |
| 十四 | HUD content: left/right, title, value, icons + side, unit, expression, variable picker, 字号, 缩放, 透明度, 刷新率 | IMPLEMENTED with deviation | `core/model/HudProfile.kt:38-72` (templates incl. left/right, `leftIcon`/`rightIcon`, `rightSuffix`, expressions, progress, colour rules); picker `ui/components/VariablePickerSheet.kt`. **Deviation:** 字号/刷新率 are *global* (`AppConfig.globalScale`, `hudScale`, `hudOpacity`; sampling cadence in §十九/Advanced) — there is no per-scheme font size or refresh field. This matches the desktop `HudProfile`/`AppSettings` contract, which also has none |
| 十五 | i18n zh+en, system-language default, zh*→简体中文 else English, manual choice persisted, **no Chinese left anywhere** (settings/dialog/permission/HUD/notification/FGS/errors/about/island/probe/DeepSeek/import-export) | IMPLEMENTED | Core in-app path: `core/i18n/{Strings,UiLanguage}.kt`, `EcpMessages.kt`; `StringsTest > every English literal is free of CJK characters` passes. Notification/FGS use the in-app language (`service/HudNotificationFactory.kt:44-61,84-97` with `Strings.t`, called with `uiLanguage()` at `service/HudForegroundService.kt:181-182`); `values/strings.xml` holds only `app_name` (framework locale, by design, `values/strings.xml:2-11`). The two leaks I previously reported are **fixed and re-verified in source**: `metrics/HardwareCapabilities.kt:41-59` resolves all 18 labels via `Strings.t(zh,en)`; `data/ProfileStoreOperations.kt:34-38,46,69,132` localizes new/duplicate/save-as-custom names and the default title; `ProfileStoreOperationsTest.kt:100-117` asserts an English session yields no CJK scheme name. One cosmetic residual: `island/XiaomiHyperIslandProvider.kt:358-362` embeds the proper noun `超级岛` in an otherwise-English detail string rendered at `ui/screens/display/DisplayScreen.kt:382-384` (that same string also says "HyperIsland"). Recorded for completeness, not a functional leak |
| 十六 | Settings structure: 首页 / 显示 / HUD 内容 / 数据源 / 高级 / 关于 with the listed items | IMPLEMENTED | `ui/navigation/EcpDestination.kt` + `EcpApp.kt:169-181` (HOME, DISPLAY, CONTENT, DATA, ADVANCED, VARIABLES, ABOUT); 高级 has 采样与刷新, 硬件能力+重新检测, 配置导入导出, 诊断报告, 日志 (`AdvancedScreen.kt:146-339`); 关于 has all required facts (`ui/screens/about/AboutScreen.kt:44-137`, values in `core/product/ProductInfo.kt:9-21` matching §16 exactly) |
| 十七 | Foreground service: HUD/overlay/repository lifecycle, notification `Endfield Charge Plus is running`, **关闭 HUD** action, survives Activity swipe, no legacy keep-alive hacks | IMPLEMENTED | `service/HudForegroundService.kt:57,104-131,180-193,203-220,222-227`; `service/HudNotificationFactory.kt:70-101` (one action `关闭 HUD`); manifest `android:stopWithTask="false"` + `START_STICKY` (`:133-144`); `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` |
| 十八 | `PermissionManager` for overlay/notification/FGS/network/boot/live-update/vendor; states shown; **never a burst of permissions**; only on the user action that needs it | IMPLEMENTED | `permission/PermissionManagerImpl.kt:48-135` (all seven states), `:149-165` the **only** runtime request (`POST_NOTIFICATIONS`); overlay is an intent to system settings (`:139-140`); wiring `ui/screens/home/HomeViewModel.kt:75,79-81`, `ui/screens/display/DisplayViewModel.kt:69-70,82,89` |
| 十九 | Power: one unified sampler, tiers 500 ms-1 s / 1-2 s / 5-60 s, no per-variable timers, don't over-sample what is hidden, throttle when hidden/screen-off, no frequent polling, **DeepSeek never per-second** | IMPLEMENTED | `metrics/SamplingScheduler.kt:43-116` (one coroutine per tier), `:202-227` `DemandThrottle` (hidden/screen-off/idle/paused), `:278` `MIN_INTERVAL_MS = 100`; `deepseek/DeepSeekCollector.kt:189,204,207` (`MIN_REFRESH_SECONDS = 60`, `MIN_INTERVAL_MS = 60_000`) + exponential backoff `:138-140`; HTTP ≥ 5 s + backoff (`network/HttpSourceMapping.kt:19,28-29,38`) |
| 二十 | DataStore for normal settings; secure storage for secrets; JSON config layer; do not serialize Compose state; cross-platform migration | IMPLEMENTED | `data/DataStoreConfigRepository.kt:25` `preferencesDataStore(name = "ecp_config")`, `:169-172` keeps `config_previous`, `:160` keeps `config_backup_corrupt`; `data/EncryptedSecretStore.kt:19-92`; `core/json/{ConfigCodec,ConfigNormalizer}.kt` with desktop `@SerialName` names (`core/model/HudProfile.kt:10-72`) |
| 二十一 | Architecture: UI≠data, UI≠system API, overlay≠provider, island≠overlay, config≠Compose state; core testable; no multi-thousand-line MainActivity/OverlayService | IMPLEMENTED | `docs/ARCHITECTURE.md:36-61`; verified by grep — `ui/**` touches no `ActivityManager/StatFs/TrafficStats/proc`; `overlay/**` references no `MetricRepository`/collector; `MainActivity.kt` is 40 lines, `HudForegroundService.kt` 391, `DefaultOverlayController.kt` 449; `:core` is pure JVM with 74 tests |
| 二十二 | Overlay and island share one data layer; switching display mode only swaps the renderer | IMPLEMENTED | `service/HudForegroundService.kt:42-43,63,278-283,300-323` — one `MetricRepository` per service run, `islandBridge`/`overlay` are alternative outputs; `service/IslandOutput.kt:38-60` |
| 二十三 | Do not copy desktop-only concepts (no tray); use foreground notification; boot via system broadcast | IMPLEMENTED | No tray/TrayMenu/systray implementation exists anywhere; `service/BootCompletedReceiver.kt` + manifest `:57-65` (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`), honouring the user setting |
| 二十四 | Phase-1 list 1-16 then continue 17-24 | IMPLEMENTED | All 24 items exist: scheme system, 完整/简洁, persistence, probe, DeepSeek, HTTP/JSON, expressions, carousel, island providers (see section table above) |
| 二十五 | Test Android 8/12/14/15/latest; listed scenarios; if no device, at least compile+unit+lint+emulator and **document what needs a real phone** | PARTIAL | Compile/unit/lint executed (Part 6). **No emulator run.** Real-device list is documented and honest: `docs/TESTING.md:36-121` ("REQUIRES A REAL DEVICE — do not claim these as verified"). Device matrix not executed → Part 4 |
| 二十六 | Create `README.md` **and** `README.en.md` describing the product and not over-claiming CPU/GPU or HyperIsland | IMPLEMENTED | Both now exist at the repo root (written 16:53): `README.md` (10 070 B, 168 lines), `README.en.md` (10 837 B). They cover every §26 item (native app, floating HUD, island interface, system data monitoring, scheme system, custom variables, network probe, DeepSeek) and, critically, satisfy §26's honesty requirements: a capability table marking GPU `❌ 无公开 API` and Wi-Fi SSID `❌ 需要位置权限` (`README.md:91-106`), the island reality section (`README.md:43-50`, 尚未授权 / no faking), and an explicit unverified-device statement (`README.md:145-159`, `真机验证 | **未完成**`). The earlier MISSING finding is **resolved** — see Part 2 |
| 二十七 | Keep MIT licence, QinAnze attribution, GlacierGlimmer maintainer | IMPLEMENTED | `LICENSE:1-3` (`MIT License` / `Copyright (c) 2026 GlacierGlimmer_冰川雪貓`); `NOTICE.md:5,14-34,74-93` (upstream + maintainer + third-party + privacy) |
| 二十八 | No TODO/NotImplemented/empty methods/fake data/hardcoded test values; build+test+lint each stage | IMPLEMENTED | Only one hit tree-wide: `data/EncryptedSecretStore.kt:15` `deliberately not implemented` — a security design statement (no plaintext secret fallback), not a stub. No `Random` anywhere (Part 3) |
| 二十九 | 特别禁止 list (13 items) | IMPLEMENTED | All 13 verified clean; see Part 3 |
| 三十 | Final goal: consistent product name/behaviour/variables/schemes/i18n/HUD/animation/licence + Android-only native settings/overlay/island structure | IMPLEMENTED | `core/product/ProductInfo.kt`, `core/metrics/VariableRegistry.kt`, `core/i18n/BuiltInProfileLocalization.kt`, `overlay/*`, `island/*`; architecture matches the contract's diagram (`docs/ARCHITECTURE.md:49-61`) |

**Tally:** 29 IMPLEMENTED · 1 PARTIAL (§25: no emulator/device run) · 0 MISSING · 0 NOT APPLICABLE.
(§14 is IMPLEMENTED with the noted global-vs-per-scheme deviation.) The single hard failure raised in
this report — the missing READMEs — is **resolved**; see Part 2.

### 1.1 Contract items that are impossible on Android (explicitly, not "MISSING")

Per the instruction to say so rather than mark them missing:

1. **Guaranteed GPU load / temperature / frequency / VRAM.** Android publishes no such API. The port
   reads only vendor nodes (KGSL, Mali, devfreq) and reports `NOT_SUPPORTED` otherwise
   (`metrics/GpuCollector.kt:28-69`, detail strings at `:118-123`). Correct behaviour, not a gap.
2. **CPU temperature.** Present only via `/sys/class/thermal/thermal_zone*`, widely SELinux-blocked;
   capability-scanned (`metrics/CpuCollector.kt:115-125`).
3. **CPU frequency.** `scaling_cur_freq` is frequently blocked since Android 10; reported unavailable
   with the probed path (`metrics/CpuCollector.kt:98-113`).
4. **CPU per-core load on locked-down ROMs.** Implemented from `/proc/stat` per-core lines
   (`metrics/CpuCollector.kt:82-95`) but the file may be restricted; capability-scanned.
5. **Raw ICMP.** Needs `CAP_NET_RAW` or `/system/bin/ping`; TCP-connect is the primary probe and the
   limitation is documented (`docs/TESTING.md:118`, `metrics/HardwareCapabilities.kt` row `探测 ICMP`).
6. **Continuous hardware monitoring through Android Live Update.** The platform restricts promoted
   ongoing notifications by scenario and throttles updates; the provider reports `experimental = true`
   and `maxUpdateHz = 0.2` (`island/AndroidLiveUpdateProvider.kt:335-336`) instead of pretending.
7. **Xiaomi HyperIsland publication.** Requires a Xiaomi enterprise console account, scene review and
   an issued `com.xiaomi.xms.APP_ID`; a normal third-party app cannot publish. Correctly reported as
   `尚未授权` / `需要申请小米超级岛权限` (`island/XiaomiHyperIslandProvider.kt:352-378,488-490`).

---

## Part 2 — Hard failures

### HF-1 (contract §26) — originally raised, now **RESOLVED**

**Original finding (recorded for the audit trail).** At the time of my first pass, no `README*` file
existed anywhere in the tree (`Get-ChildItem -Recurse -Filter README*` → nothing), so contract §26
("创建：`README.md` `README.en.md`") was unsatisfied and the §26 honesty requirements (accurate
unsupported-metric statement, no GPU/HyperIsland over-claim) had no documentation home.

**Current state — verified.** Both files now exist at the repo root, written 16:53:

| File | Size | Lines |
| --- | --- | --- |
| `README.md` | 10 070 B | 168 |
| `README.en.md` | 10 837 B | (English mirror) |

They satisfy §26 in substance, not just in presence: the product identity and the
Windows/Linux/macOS/Android relationship (`README.md:1-22`), the settings-app vs screen-HUD split
(`:26-41`), the honest island status (`:43-50`, `尚未授权 / 需要申请小米超级岛权限`, "不会把普通通知伪装成
灵动岛接入成功"), the feature list including probe/DeepSeek/HTTP-JSON (`:54-82`), **a per-metric
capability table stating what Android cannot read** (`:91-106`: GPU `❌ 无公开 API`,
Wi-Fi SSID `❌ 需要位置权限`, CPU freq/temp `⚠️`), and — required by §26 and §25 — an explicit
unverified-device statement (`:145-159`, `真机验证 | **未完成**`, plus "在真机验证完成前，**不要**把本版本
描述为已通过设备测试"). The computed results table at `:147-154` matches the authoritative run in
Part 6.2.

**Reproduction now:** `Test-Path .\README.md` and `Test-Path .\README.en.md` → both `True`.

### No other hard failure

Nothing in the "特别禁止" list (§29) is violated, and no contract section is MISSING.
The release/R8 and lint failures observed during verification were **build-contention artefacts**
(§0), never counted as failures; the Lead's authoritative exclusive run passed (Part 6.2).

---

## Part 3 — Prohibition audit (each item = a hard FAIL if violated)

All commands were run over the tree excluding `build/`. "Hits" means the prohibited construct was found.

| # | Prohibition (§29) | Result | Evidence |
| --- | --- | --- | --- |
| 1 | Random/fabricated CPU or GPU data | **CLEAN** | `Random`, `random(`, `nextInt`, `nextDouble`, `Math.random`, `shuffled` → **0 matches** in `*.kt` |
| 2 | Return `0` when unsupported | **CLEAN** | `MetricValue.Unavailable` is a sealed type distinct from `Number` (`core/model/MetricValue.kt:10-31`); collectors call `putUnavailable(...)`; template renders `--` (`core/template/TemplateEngine.kt:38,75-77`); tests `> an unavailable value renders the sentinel never zero`, `> android impossible metrics are unavailable with an honest note`, `> per core usage is never invented` pass |
| 3 | WebView-based settings UI | **CLEAN** | `WebView`, `android.webkit` → **0 matches** |
| 4 | Avalonia/Flutter/RN/MAUI/Electron packaging | **CLEAN** | No such dependency or artifact; `app/build.gradle.kts:78-103` is AndroidX/Compose/Coroutines/DataStore only; `NOTICE.md:38-42` states the absence explicitly |
| 5 | Island mode faked as a system interface | **CLEAN** | `island/AndroidLiveUpdateProvider.kt:178-183` reports availability from the platform's own checks and says the system decides promotion; `IslandAvailabilityState` cannot express "posted as an island" |
| 6 | Reflection into Xiaomi private APIs | **CLEAN** | `Class.forName`, `getDeclaredMethod`, `getDeclaredField`, `setAccessible`, `java.lang.reflect` → **0 matches**. `island/XiaomiHyperIslandProvider.kt:32-34,437-439` documents that `persist.sys.feature.island` would need reflection and is deliberately unused |
| 7 | Faked HyperIsland / Live Update success | **CLEAN** | `NotIntegratedBridge.isIntegrated() = false` and `publish(...) = false` (`island/XiaomiHyperIslandProvider.kt:356,365-377`); provider sets `VENDOR_PERMISSION_REQUIRED` whenever the bridge refuses (`:93-97,104-107,114-119`) |
| 8 | Silent sensitive-permission acquisition | **CLEAN** | Manifest declares only `INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED` (`AndroidManifest.xml:6-19`) — no location/storage/contacts/phone. The single runtime request is `POST_NOTIFICATIONS` behind `requestNotificationPermissionIfNeeded` (`permission/PermissionManagerImpl.kt:149-165`), invoked from a user action (`HomeViewModel.kt:79-81`) |
| 9 | Hardcoded API key | **CLEAN** | `"sk-` appears only as a UI placeholder (`ui/screens/datasources/DataSourcesScreen.kt:308`) and a detector (`data/ConfigFileTransfer.kt:87`); every `sk-…` literal is inside `app/src/test/**` fixtures |
| 10 | Upload of user data | **CLEAN** | All outbound hosts in `app/src/main`: `https://api.deepseek.com` (`deepseek/DefaultDeepSeekClient.kt:72`, user-configurable), GitHub URLs inside About share text (`AboutScreen.kt:78-79`), Xiaomi documentation URLs used only in log messages (`XiaomiHyperIslandProvider.kt:332,336,340`). No analytics/telemetry/Firebase/Crashlytics (`0 matches`). Custom HTTP sources and the probe only contact user-configured destinations (`NOTICE.md:86-93`) |
| 11 | Removal of MIT/upstream attribution | **CLEAN** | `LICENSE:1-3`; `NOTICE.md:5,14-34`; About page shows upstream + licence (`AboutScreen.kt:59-88`) |
| 12 | Modifying the Windows/Linux/macOS repos | **CLEAN** | `git status --porcelain`: `zmd-charge-plus-for-linux` → clean; `zmd-charge-plus-for-macos` → clean; `upstream-zmd-charge` → clean. `zmd-charge-plus` shows only `??` untracked entries (`Customization/Linux*.cs`, `Interop/LinuxDesktop.cs`, `README.linux.md`, `Tests/`, …) whose mtimes are **2026-10-02 16:58–17:16**, i.e. three days before this Android project started (2026-10-05 15:32) — pre-existing workspace state, **no tracked file modified in any reference repo** |
| 13 | Lowered `targetSdk` to bypass security limits | **CLEAN** | `app/build.gradle.kts:10,15` `compileSdk = 36`, `targetSdk = 36` — equal and current. No `tools:overrideLibrary`, no `android:targetSdkVersion` in the manifest |

### 3.1 Stub / dead-code scan

| Check | Result |
| --- | --- |
| `TODO` / `FIXME` / `NotImplementedError` / `NotImplementedException` / `not implemented` / `未实现` / `待实现` | **1 hit, benign**: `data/EncryptedSecretStore.kt:15` — "(a plaintext fallback is deliberately not implemented)". No stubs |
| Hardcoded sample values returned from a method | **none found**; the only fixed values are documented constants (unit scales, notification ids, `MIN_*`/`MAX_*` bounds, sentinel `--`) |
| Empty method bodies | none material; `override fun onBind(...) = null` (`HudForegroundService.kt:146`) is the required Service contract |

### 3.2 Manifest / build security review

* No `android:debuggable`, no `usesCleartextTraffic=true`, no exported component other than the
  launcher activity (`AndroidManifest.xml:36` `android:exported="true"` for MAIN/LAUNCHER only;
  service and receiver are `exported="false"` `:49,60`).
* `service/HudForegroundService.kt:50` declares `android:foregroundServiceType="specialUse"` with the
  required `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification (`AndroidManifest.xml:52-54`) — the
  current Android requirement, not a legacy workaround.
* HTTPS: `deepseek/DefaultDeepSeekClient.kt:48` rejects cleartext via `HttpUrlPolicy.rejectionReason`
  except loopback; a test asserts `assertFalse(code.startsWith("sk-"))` and the HTTP policy behaviour.
  `docs/TESTING.md:96` documents the cleartext policy.
* Response size caps exist (`DefaultDeepSeekClient.MAX_RESPONSE_BYTES = 512 * 1024` `:73`;
  `ConfigFileTransfer.MAX_FILE_BYTES = 4 MiB` `:127`).

---

## Part 4 — Documentation vs code discrepancies (claims that do not match the code)

### 4.1 `docs/ARCHITECTURE.md:120-121` — notification localization claim

**Claim:** "Every user-visible string in the app, the HUD, the notification and the island layer goes
through the same bilingual API (`Strings.t(zh, en)`…)".

**Status:** the claim was **false when I first read the tree** — `HudNotificationFactory` used
`context.getString(R.string.hud_notification_*)` and there was no `setApplicationLocales`,
`LocaleManager`, `attachBaseContext` or `createConfigurationContext` anywhere, so the notification and
channel name followed the *system* locale while the UI followed the in-app language.

**Now:** the Lead's follow-up change makes the claim true —
`service/HudNotificationFactory.kt:44-61,84-97` uses `Strings.t(...)`, receives
`UiLanguage` from `service/HudForegroundService.kt:181-182`, and re-creates the channel on every call
so its name/description follow the language; `values/strings.xml` is down to `app_name` only, with a
comment (`values/strings.xml:2-11`) documenting that the launcher label deliberately follows the
framework locale. **No action needed; recorded because the doc was ahead of the code.**

### 4.2 `docs/TESTING.md:109-110` and contract §15 — Chinese leaks (both originally reported) now FIXED

`TESTING.md:109-110` instructs: "Switch to English everywhere (including dialogs, permission text,
island states, **notifications, diagnostics**): no Chinese text may remain." Two paths still violate
this:

1. **Diagnostics report capability labels were Chinese-only — FIXED and re-verified.**
   `metrics/HardwareCapabilities.kt:41-59` `summarise()` now resolves every label through
   `Strings.t(zh, en)` (`Strings.t("CPU 总占用", "CPU total usage")`, …), and its KDoc (`:37-40`)
   states the reason. `diagnostics/DiagnosticsReport.kt:146-150` therefore prints language-correct
   rows. The former defect: `summarise()` hardcoded Chinese and the report printed it verbatim.
2. **New/duplicated custom scheme names and default title were Chinese-only — FIXED and re-verified.**
   `data/ProfileStoreOperations.kt:34-38` (`Strings.t("自定义方案", "Custom Profile") + " N"`),
   `:46` (`Strings.t("系统状态", "System Status")`), `:69` (`Strings.t("副本", "copy")`),
   `:132` (`Strings.t("（已更改）", "(modified)")`). Locked by the new test
   `ProfileStoreOperationsTest.kt:100-117` `default scheme names follow the active language`, which
   sets `UiLanguage.EN`, asserts the created name contains no character above `U+2E7F`
   (`assertFalse("an English session must not produce a Chinese scheme name", …)`) and starts with
   `"Custom Profile"`, then checks the Chinese branch, restoring the previous language in `finally`.
   Note the storage-level `category` stays the stable key `"自定义"` (`ConfigNormalizer.kt:56`),
   which is correct: `ui/state/ProfileEdits.kt:53,80,117` and `BuiltInProfileLocalization.categoryName`
   (`:119`, map `"系统" to "System"` at `:26`) localize it at display time. Likewise
   `HudProfile.kt:32-33,39` keep Chinese *model defaults* for desktop-compatible storage; every
   creation path now passes an explicit localized value.

**Residual Chinese found in ONE English-visible surface (cosmetic, non-functional):**
`island/XiaomiHyperIslandProvider.kt:358-362` `integrationDetail()` returns an English sentence that
contains the proper noun `超级岛`: `"… no activated 超级岛 service, no issued com.xiaomi.xms.APP_ID …"`.
That string is shown to the user as the island row's detail
(`ui/screens/display/DisplayScreen.kt:382-384` renders `availability.detail`). It is a product-name
reference inside a sentence that also says "HyperIsland", so it is acceptable as-is; flagged only
because the brief asked to be told about any residual Chinese. Everything else I could find is either a
two-argument `t(zh, en)`/`s(zh, en)`/`label(zh, en)` call, a language-guarded branch
(`core/template/ValueFormatter.kt:320-340,347-361` both branch on `Strings.isEnglish()`), a storage
constant compared at runtime (`ConfigNormalizer.kt:56-57,266`, `CustomHudSettings.kt:53,66`), an input
alias table (`ConfigNormalizer.kt:125-127`, `EcpJson.kt:202-203`), or the author's proper name
(`core/product/ProductInfo.kt:13`).

**Correctly bilingual (verified, not defects):** `deepseek/PeakWindow.kt:158-176,200-208` produces both
`*TextZh` and `*TextEn` and picks the duration unit by language; `network/ProbeCollector.kt:267-291`
has `TEXT_ZH`/`TEXT_EN` plus `statusText(key, english)`; `metrics/TimeMath.kt:88`
`if (chinese) "剩余$rounded%" else "Left $rounded%"`.

### 4.3 `docs/TESTING.md:23` — "Resource/lint health" overstates lint coverage of `:core`

`TESTING.md:23` presents `:app:lintDebug` as covering "manifest, permissions, API-level misuse,
unused/invalid resources". Gradle itself warns:

```
Warning: Lint will treat :core as an external dependency and not analyze it.
 * Recommended Action: Apply the 'com.android.lint' plugin to java library project :core.
```

`app/build.gradle.kts:69` sets `checkDependencies = true`, but because `:core` does not apply the
`com.android.lint` plugin, the ~1 400 lines of the compatibility core (template/expression/format/
config/i18n/model) receive **no** lint analysis. The `:core` unit tests do cover behaviour. *Fix:*
apply `com.android.lint` to `:core`, or scope the TESTING.md claim to `:app`.

### 4.4 `docs/TESTING.md:16` / `docs/ARCHITECTURE.md:106-113` — release build claim: NOW VERIFIED

`TESTING.md:16` documents `.\gradlew.bat :core:build :app:assembleRelease` as the R8/shrinkability
check. My own `:app:assembleRelease` attempts failed under **build contention** (shared intermediates
with the Lead's concurrent run), as did my `clean`; those results carried no information about the
deliverable. The Lead's authoritative, exclusive run now settles it:
**`clean :core:build :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
:app:assembleRelease` → BUILD SUCCESSFUL in 3m 13s**, including
`:app:assembleRelease` (R8 + `isShrinkResources`) producing a `app-release-unsigned.apk` of ~1.85 MB.
The R8 fix is `app/proguard-rules.pro:7-15` (`-dontwarn` for Tink's transitive
`com.google.errorprone.annotations.*` / `javax.annotation.*`). See Part 6.2.

### 4.5 `docs/TESTING.md:5` — "nothing here is an estimate"

`TESTING.md:5` says the numbers in its tables are "updated from the commands below; nothing here is an
estimate", yet `docs/TESTING.md` contains **no numeric result tables at all** (only the reproduction
commands, §1-§4) — the claim is vacuous rather than false. The measured numbers now live in
`README.md:145-159` (a results table that matches the authoritative run in Part 6.2, including the
`真机验证 | **未完成**` row) and in this verification document. Consider moving that table into
`TESTING.md`, which is where a reader looks for it.

---

## Part 5 — What I could NOT verify (and why)

1. **Any on-device runtime behaviour — NONE WAS EXECUTED.** No Android device and no emulator was
   used at any point in this verification. Overlay window flags at runtime, drag/persist, cutout
   behaviour, notification rendering, FGS survival after swipe, vendor task-killers and both island
   backends are therefore unverified by execution (my brief restricted me to non-device checks).
   **Physical-device verification for this delivery remains NOT DONE** — the deliverable says so
   itself (`README.md:145-159`, `docs/TESTING.md:36-121`), and I confirmed that statement is
   accurate. The device/OS matrix required by §25 has not been run.
2. **The authoritative build/test/lint numbers.** Produced by the Lead's exclusive run and quoted in
   Part 6.2; my own pre-change measurement is kept in Part 6.1 for the audit trail. I did not re-run
   the build after the final edits (by agreement, to avoid contention), so Part 6.2 is the Lead's
   measurement, not mine.
3. **`:app:assembleRelease` / R8 shrinking and `lintVitalRelease`.** My own attempts were
   contention-invalidated; the Lead's exclusive run passed both (Part 6.2). I did not independently
   reproduce the release build.
4. **Lint findings inside `:core`.** Not analysed by AGP at all (Part 4.3) — unchanged by the
   follow-up work; `:core:test` (74 tests) is the coverage there.
5. **Real network behaviour of the probe and the API clients.** Loopback/JVM tests only; ICMP
   availability, IPv6-only networks, captive portals, Wi-Fi↔cellular handover and real DeepSeek
   responses are untested here.
6. **Real Xiaomi HyperOS and Android 16+ device behaviour for the island providers.** I verified the
   decision logic and its refusal paths by reading code and passing unit tests
   (`HyperOsIslandPolicyTest`, `AndroidLiveUpdatePolicyTest`, `XiaomiIslandBridgeTest`,
   `IslandProviderRegistryTest`, `IslandHudMapperTest`), not the platform's actual response.
7. **Whether the shipped APK installs and runs.** The authoritative run produced `app-debug.apk`
   (≈19.74 MB) and `app-release-unsigned.apk` (≈1.85 MB); I did not install or launch either.
8. **Contract §25's device/OS matrix** (Android 8/12/14/15/latest; rotation; notch; DPI; lock/unlock;
   Wi-Fi↔cellular; IPv4-only; IPv6; corrupt config; system-language change; background kill).
9. **Exact revision identity** — the repo has no commits, so nothing can be pinned (Part 2 note, §7.1).
10. **Test *quality*** (assertion strength, mocking of Android APIs). I confirmed the suites run green
    and read the assertion names, but did not audit every test body for vacuity. Note
    `app/build.gradle.kts:74` sets `unitTests.isReturnDefaultValues = true`, which lets Android-framework
    calls silently return defaults in unit tests (e.g. `0`/`false`) — a test could therefore pass
    against stub return values. This is a standard Android practice but weakens device-free
    verification and is worth knowing when reading the 354 green app tests.

---

## Part 6 — Build and test evidence

### 6.1 Verifier-measured (independent, my own Gradle invocation)

Command (run by me; **pre-dates the Lead's follow-up edits**):

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-17"; $env:ANDROID_HOME="D:\ECP_Workspace\.android-sdk"
cd D:\ECP_Workspace\zmd-charge-plus-for-android
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug --rerun-tasks --console=plain
```

Result: `BUILD SUCCESSFUL in 37s` — `40 actionable tasks: 40 executed` (genuinely re-executed, not
`UP-TO-DATE`; `:core:compileKotlin`, `:core:test`, `:app:compileDebugKotlin`,
`:app:testDebugUnitTest`, `:app:lintAnalyzeDebug`, `:app:lintReportDebug`, `:app:lintDebug` all ran).

| Task | Tests | Failures | Errors | Skipped | Source |
| --- | --- | --- | --- | --- | --- |
| `:core:test` | 74 | 0 | 0 | 0 | 7 `TEST-*.xml` |
| `:app:testDebugUnitTest` | 353 | 0 | 0 | 0 | 38 `TEST-*.xml` |
| **total** | **427** | **0** | **0** | **0** | |

* `:app:lintDebug` → `0 errors, 26 warnings` (`lint-results-debug.txt`, regenerated 16:34:43). No
  lint baseline is used (`app/build.gradle.kts:66-71` has no `baseline` key and no `lint-baseline.xml`
  exists), so the passing state is real, not suppressed.
* An earlier lint report in this workspace (`16:27:32`) had `6 errors, 46 warnings`
  (`AndroidLiveUpdateProvider.kt:227` NewApi, `values{,-night}/themes.xml:7` cutout NewApi,
  `local.properties:1` PropertyEscape, `DataSourcesScreen.kt:234,238` StateFlowValueCalledInComposition).
  These were fixed at ~16:31-16:32 **by real changes**, verified by me:
  `island/AndroidLiveUpdateProvider.kt:221` now carries `@RequiresApi(MIN_PROMOTED_API_LEVEL)`;
  `windowLayoutInDisplayCutoutMode` moved to `values-v27/themes.xml:11` and
  `values-night-v27/themes.xml:11` (with an explanatory comment) while the base themes stay API-26
  clean; `local.properties` now escapes its path (`sdk.dir=D\:/ECP_Workspace/.android-sdk`);
  `DataSourcesScreen.kt:234-243` now reads plain row values instead of a `StateFlow`.
* `app-debug.apk` was produced (20 812 202 B).
* Compiler warnings in that pre-change run (non-fatal), and their disposition:
  * `metrics/NetworkCollector.kt:175:13 Condition is always 'false'` — **this was the `reference == null`
    test**, not the comparison I first assumed: `reference` is a non-null `Double` because of the elvis
    at `:172-174`, so `== null` could never hold. The Lead removed that clause; the remaining
    `if (reference <= 0.0)` (`:175`) is **reachable and correct** (a profile with
    `networkReferenceValue = 0.0` makes `NetworkFormat.referenceBytesPerSecond` return `0.0`
    — `metrics/NetworkPresentation.kt:36-46` — and the `Unavailable(DISABLED)` report at
    `NetworkCollector.kt:176-180` then fires). **My earlier report mis-attributed this warning; corrected.**
  * `data/EncryptedSecretStore.kt` — 13 deprecation warnings from `androidx.security:security-crypto`
    (`EncryptedSharedPreferences`/`MasterKey` are deprecated upstream). The Lead's follow-up added
    `@file:Suppress("DEPRECATION")` plus a written rationale rather than abandoning the platform's
    recommended encrypted store.
  * `ui/navigation/EcpDestination.kt:33` `Icons.Filled.List` deprecated; `diagnostics/DiagnosticsReport.kt:178`
    `versionCode` deprecated — both addressed in the follow-up.
  * `SamplingSchedulerTest` 32× `ExperimentalCoroutinesApi` opt-in (test-only, not a production warning).
  * **Authoritative post-fix state: 0 Kotlin compiler warnings** (Lead's clean run, Part 6.2).

### 6.2 Authoritative run (Lead-owned, exclusive — no other writer active)

Command:

```powershell
.\gradlew.bat clean :core:build :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --console=plain
```

Result: **BUILD SUCCESSFUL in 3m 13s** (clean build, therefore every task genuinely executed).

| Task | Result |
| --- | --- |
| `:core:test` | **74 tests / 0 failures / 0 errors** (7 suites) |
| `:app:testDebugUnitTest` | **354 tests / 0 failures / 0 errors** (38 suites) |
| `:app:lintDebug` | **0 errors / 21 warnings**, no baseline file added |
| `:app:assembleDebug` | success — `app-debug.apk` ≈ 19.74 MB |
| `:app:assembleRelease` (R8 + shrinkResources) | success — `app-release-unsigned.apk` ≈ 1.85 MB |
| Kotlin compiler warnings | **0** (was 4 locations) |
| Source size | 165 `.kt` files, ≈ 26.5k lines (my own count: **164** `.kt` files / 26 524 lines excluding `build/`; the 1-file delta is a counting-basis difference and is immaterial) |

These numbers supersede §6.1 and are the ones quoted in `README.md:147-154`. Total tests across both
modules: **428** (74 + 354), 0 failures.

---

## Part 7 — Discrepancies, latent defects and the highest-risk items

### 7.1 Traceability

* **No commits.** `main` has no commits; every file is untracked. There is no baseline to diff, no
  history of what changed, and no revision to cite. For a deliverable that is claimed complete, a
  first commit (plus the `docs/audit/*` and source tree) is strongly advisable — it is also what makes
  future verification cheap.
* `local.properties` is present in the working tree but correctly git-ignored (`.gitignore:7`); it is
  machine-local and must never be committed.

### 7.2 i18n residual leaks — both originally reported leaks are now FIXED

1. Diagnostics capability labels — **fixed**: `metrics/HardwareCapabilities.kt:41-59` now uses
   `Strings.t(zh, en)` for all 18 labels, so `diagnostics/DiagnosticsReport.kt:146-150` is
   language-correct. Re-verified in source.
2. New/duplicated/modified custom scheme names and default title — **fixed**:
   `data/ProfileStoreOperations.kt:34-38,46,69,132` now use `Strings.t(zh, en)`, with the new test
   `ProfileStoreOperationsTest.kt:100-117` locking the English path (`no CJK` assertion). Re-verified
   in source.
3. **Remaining, cosmetic only:** `island/XiaomiHyperIslandProvider.kt:358-362` contains the proper noun
   `超级岛` inside an English detail string shown at `ui/screens/display/DisplayScreen.kt:382-384`.
   Acceptable as a product-name reference; listed so the Lead knows it is the only one left.

A full fresh sweep over `app/src/main` + `core/src/main` for CJK string literals outside the
localization data files returned only: two-argument `t`/`s`/`label` calls, language-guarded branches
(`core/template/ValueFormatter.kt:320-340,347-361`), storage constants/keys compared at runtime
(`ConfigNormalizer.kt:56-57,266`, `CustomHudSettings.kt:53,66`, `ProfileEdits.kt:53,80,117`),
input-alias tables (`ConfigNormalizer.kt:125-127`, `EcpJson.kt:202-203`) and the author's proper name
(`core/product/ProductInfo.kt:13`). No further user-visible leak was found.

### 7.3 Latent logic defects (not currently reachable, but wrong)

1. **Configured island provider could be ignored — FIXED and re-verified.**
   `island/IslandProviderRegistry.kt:100` now reads `val provider = select()` (was `autoSelect()`), so
   `startSelected()` honours the user's explicit `IslandProviderKind` while `AUTO` still delegates to
   `autoSelect()` (`:75-79`). Re-verified in source; the service path is unchanged
   (`service/IslandOutput.kt:43-47`).
2. **Island mode reported itself active when nothing started — FIXED and re-verified.**
   `service/IslandOutput.kt:23` `fun start(): Boolean`, and `RegistryIslandOutput.start()` returns
   `registry.startSelected(scope) != null` (`:43-47`). `service/HudForegroundService.kt:322` now sets
   `islandActive = output.start()` with an `if (!islandActive) { … }` branch
   (`:323`) that publishes the honest availability instead of pretending the mode is live.
3. **Global mutable language from a service call.**
   `HudNotificationFactory.ensureChannel` calls `Strings.setLanguage(language)`
   (`service/HudNotificationFactory.kt:46`) — a process-global side effect triggered from the service.
   It is consistent today because both UI and service derive the language from the same config, but a
   single writer owned by `LanguageController` would be safer.
4. **UI vs normalizer range mismatch (minor).** The DeepSeek refresh field accepts 30 s
   (`ui/screens/datasources/DataSourcesViewModel.kt:164` `coerceIn(30, 86_400)`) while the collector and
   normalizer clamp to ≥ 60 s (`deepseek/DeepSeekCollector.kt:207`,
   `core/json/ConfigNormalizer.kt:456-458`). A user can type 30 and see it silently raised to 60.
5. **`NetworkCollector.kt:175` — withdrawn.** My original "dead branch" analysis was wrong: the
   never-true test was `reference == null` (removed by the Lead); the surviving `<= 0.0` guard is
   reachable when a profile sets `networkReferenceValue = 0.0`. See Part 6.1 for the corrected reading.

### 7.4 Highest-risk items a human must verify on real hardware

Ordered by product risk. Each needs a physical device; none can be settled by the JVM tests.

1. **Overlay permission lifecycle.** Grant from a cold start (`Settings.canDrawOverlays` guidance), then
   **revoke while the HUD runs** — expect the overlay to disappear and `HudRuntimeStatus.lastError =
   "Overlay permission (SYSTEM_ALERT_WINDOW) is not granted"` (`overlay/DefaultOverlayController.kt:417-423,446-447`;
   service reaction `service/HudForegroundService.kt:79-88`), with no crash/loop.
2. **Touch pass-through on Android 12+.** Confirm the HUD window (WRAP_CONTENT, so not full-screen) does
   not swallow touches outside itself and does not break the keyboard of the app underneath, with
   click-through both on and off (`DefaultOverlayController.kt:349-359`).
3. **Drag + per-orientation restore.** Drag, rotate, drag again; confirm the portrait and landscape
   positions are independent and clamped into the safe area (`:206-246`, `OverlayPositionManager.kt:83-120`).
4. **Cutout / notch.** With `avoidCutout` on and off, confirm the HUD is not clipped or hidden
   (`DefaultOverlayController.kt:361-368`, `OverlayPositionManager.kt:228-265`).
5. **FGS survival.** Swipe the Activity away, then test aggressive vendor killers (Xiaomi/Huawei/OPPO/
   vivo) and the boot restart path (`service/BootCompletedReceiver.kt`).
6. **Notification language after an in-app switch.** The fix is code-verified but unexecuted: switch the
   app to English with a Chinese system locale and confirm the notification title, text, action
   ("Stop HUD") **and the channel name** are English
   (`service/HudNotificationFactory.kt:44-61,84-97`). This is the exact scenario that was broken before
   the follow-up edit, so it must be re-tested on the device.
7. **Battery current plausibility and sign.** `BATTERY_PROPERTY_CURRENT_NOW` sign conventions differ per
   OEM; confirm the magnitude is sane and the direction matches `EXTRA_STATUS`
   (`metrics/BatteryCollector.kt:136-169`), and that `Integer.MIN_VALUE`/`Long.MIN_VALUE` devices report
   unavailable rather than 0 (`:252-260`).
8. **Per-device sysfs availability.** `/proc/stat`, `/proc/cpuinfo`, `cpuinfo_max_freq`,
   `scaling_cur_freq`, thermal zones, KGSL/Mali nodes — record which report real values and confirm the
   rest show `--` (`metrics/CpuCollector.kt`, `metrics/GpuCollector.kt`), then use the Advanced page's
   **重新检测** button and confirm the capability table updates
   (`ui/screens/advanced/AdvancedScreen.kt:174-190`).
9. **`TrafficStats` semantics.** Total bytes are monotonic since boot and reset on reboot; confirm no
   spike/negative rate appears after a reboot or network handover (`metrics/NetworkCollector.kt`
   counter-delta logic).
10. **Probe on real networks.** ICMP may be blocked; confirm TCP is the working path, that a failure
    shows a status string (never `999 ms`, never `0 %`), and that loss recovers. HUD forms to check:
    `{probe.latency_ms|0}ms` → `--ms` when unavailable, and `丢包{probe.loss_percent|0}` + `%` →
    `丢包--%`, English `Loss --%` (`core/model/BuiltInProfiles.kt:22,191,193` — `NETWORK_PROBE`,
    `"{probe.latency_ms|0}ms"`, `"丢包{probe.loss_percent|0}"`; English variant
    `core/i18n/BuiltInProfileLocalization.kt:85-89` `"Loss {probe.loss_percent|0}"`; with
    `core/template/TemplateEngine.kt:38`);
    a *failed but measured* probe shows the localized status text (`network/ProbeCollector.kt:160-176`).
11. **DeepSeek with a real key.** Balance/period values, the ≥ 60 s cadence and exponential backoff, and
    that neither the log nor the diagnostics export contains the key
    (`deepseek/DeepSeekCollector.kt:189,204`; `diagnostics/AppLog.kt:78-88`;
    `diagnostics/DiagnosticsReport.kt:109,172-173`).
12. **Island on real hardware.** Android 16+ device: confirm the settings page shows the platform's real
    eligibility and never a false success. Xiaomi HyperOS device: confirm
    `尚未授权` / `需要申请小米超级岛权限` (`island/XiaomiHyperIslandProvider.kt:488-490`).
13. **Import/export round-trip** through SAF, including a deliberately corrupted file and the
    "API key is not exported in clear text" rule (`data/ConfigFileTransfer.kt:40-53,76-93`).
14. **Language switch persistence** across a process restart, and `Auto` following a system-language
    change (`localization/LanguageController.kt:33-44`).

---

## Part 8 — Bottom line

The implementation is unusually disciplined about the two things this contract cares about most:
**honesty about platform limits** and **no fabricated data**. The prohibition audit is clean across all
13 items; the `MetricValue.Unavailable` → `--` sentinel pipeline is enforced by type and by passing
tests; GPU/CPU/ICMP/Live-Update/HyperIsland limits are reported as unsupported or unauthorized rather
than faked; permissions are requested only on the user action that needs them; the overlay is genuinely
HUD-sized and non-focusable; and there is one shared data pipeline behind both output modes.

**Status after the follow-up fixes (all re-verified in source, read-only, no build re-run by me):**

* Contract coverage: **29 of 30 sections IMPLEMENTED**, 1 PARTIAL (§25 — no emulator/device run),
  0 MISSING, 0 NOT APPLICABLE. §14 is IMPLEMENTED with the noted global-vs-per-scheme deviation.
* The one hard failure I raised — missing `README.md` / `README.en.md` — is **resolved**; both exist
  and carry the honest capability table and the "not device-tested" statement.
* Both i18n leaks I raised are **fixed** (`HardwareCapabilities.kt:41-59`,
  `ProfileStoreOperations.kt:34-38,46,69,132`), locked by a new language test; the only residual
  Chinese in an English surface is the proper noun `超级岛` in one Xiaomi detail string.
* Both latent island defects are **fixed** (`IslandProviderRegistry.kt:100` uses `select()`;
  `IslandOutput.start()` returns `Boolean` and drives `islandActive`).
* My earlier "dead branch" finding in `NetworkCollector` is **withdrawn** — the always-false test was
  `reference == null`, now removed; the surviving guard is reachable.
* Authoritative evidence: `clean` build **BUILD SUCCESSFUL in 3m 13s**, 74 + 354 = **428 tests / 0
  failures**, lint **0 errors / 21 warnings with no baseline**, debug APK ≈ 19.74 MB, R8 release APK
  ≈ 1.85 MB, **0 Kotlin compiler warnings**.

Remaining, in priority order: **(1) physical-device verification is entirely unexecuted** — nothing in
this report substitutes for it, and the deliverable correctly refuses to claim otherwise; (2) the repo
still has **no commits**, so nothing can be pinned or diffed; (3) `:core` is still outside lint
coverage (`docs/TESTING.md:23` overstates it); (4) two smaller items — the global `Strings.setLanguage`
call from the notification factory, and the DeepSeek refresh UI/normalizer range mismatch (30 s vs 60 s).
None of these is a fabrication, and none contradicts the product's honesty claims.

---

## Lead addendum (added after the verifier finished; the verifier's text above is unchanged)

Two observations above were made against the revision that existed when they were written and are now
stale. They are corrected here rather than edited into the verifier's own findings:

1. **The repository now has commits.** `dd20c48` - `feat: Endfield Charge Plus for Android v0.1.0
   (native Kotlin/Compose)` (202 files, 36 719 insertions) and `8e6d577` - `chore: add .gitattributes
   for cross-platform line endings`. The reviewed revision can therefore be pinned and diffed.
2. **The last residual CJK string is fixed.** The proper noun in `NotIntegratedBridge.integrationDetail()`
   was replaced by a language-neutral wording ("no activated island service"), so the English island
   detail row now contains no Chinese at all.
3. **The `:core` lint-coverage gap is now stated in `docs/TESTING.md`** instead of being implied away:
   AGP does not analyse a plain Kotlin/JVM module, so `:core` is covered by `:core:test` and by the
   Kotlin compiler only.
4. Two non-blocking observations from the verifier are **accepted as known and left unfixed**, and are
   disclosed in the final report to the user: the notification factory sets the process-wide language as
   a side effect of building the notification (it is the same language the service already renders in),
   and the DeepSeek refresh field accepts 30 s in the UI while the normalizer clamps the effective
   cadence to 60 s.

Nothing above changes the verdict tally: **29 of 30 sections IMPLEMENTED, 1 PARTIAL (section 25,
physical-device verification not executed), 0 MISSING**. The authoritative build evidence in Part 6.2 is
unchanged, and the only remaining gap that matters is the unexecuted real-hardware test matrix in
`docs/TESTING.md`.

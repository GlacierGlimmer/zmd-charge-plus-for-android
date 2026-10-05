# 01 — Windows Core Compatibility Contract (Endfield Charge Plus)

Audit of the Windows source tree `D:\ECP_Workspace\zmd-charge-plus` (read-only audit) as the
semantic contract for the native Android port. Every claim below carries a `file:line`
reference. Paths are relative to the Windows repo root unless stated otherwise.
Line numbers are from the files as they exist in this checkout (1-based).

Product: **Endfield Charge Plus** ("ECP"), Windows edition, v0.1.0
(`EndfieldChargePlus.csproj:11-19`).

---

## 0. Method, coverage, and source-tree caveats

### 0.1 Read coverage

All non-generated sources were read in full: `Customization/*` (including the three large
files `VariableCatalog.cs` 813 lines / 95 771 B, `VariableHub.cs` 1 786 lines / 85 260 B,
`AdvancedVariableProvider.cs` 1 320 lines / 78 401 B), `Animations/HudAnimations.cs`,
`Settings/*`, `Views/*`, `Interop/*`, `LocalizationManager.cs`, `ProductInfo.cs`,
`Diagnostics/AppLog.cs`, `Program.cs`, `App.axaml.cs`, `Styles/*.axaml`, `Tests/*`,
`README.md`, `README.en.md`, `NOTICE.md`, `PRIVACY.md`, packaging scripts.
Nothing under `bin/`, `obj/`, `Tests/bin/`, `Tests/obj/`, `artifacts/tools/` was treated as
source (generated/3rd-party payloads).

### 0.2 CRITICAL caveat — this Windows checkout contains Linux-only sources that cannot compile

The task brief assumed a pure Windows tree. In fact this checkout contains cross-platform
files that reference symbols which exist **only** in the Linux repo
(`D:\ECP_Workspace\zmd-charge-plus-for-linux`):

| Symbol used in the Windows tree | Referenced at | Declared only in |
| --- | --- | --- |
| `VariableCatalog.PlatformIndependentDefinitions` | `Customization/LinuxVariableCatalog.cs:74`, `Tests/LinuxGuiAudit.cs:56,74` | `zmd-charge-plus-for-linux/Customization/VariableCatalog.cs:762` (NOT present in the Windows `VariableCatalog.cs`) |
| `HudProfileRenderer.BuildEffectiveVariables` as `internal` | `Tests/LinuxGuiAudit.cs:43` | Windows declares it `private` (`Customization/HudProfileRenderer.cs:81`); Linux declares it `internal` (`...for-linux/Customization/HudProfileRenderer.cs:81`) |
| `new VariableHub(HttpClient)` | `Tests/LinuxGuiAudit.cs:35,121` | Windows `VariableHub` has **no** constructor (only fields + `Dispose`, `Customization/VariableHub.cs:21-27,1785`); Linux has one |
| `SecretStore` producing `linux-aesgcm-v1:` ciphertext | `Tests/Program.cs:96-100` | Windows `SecretStore` uses Windows DPAPI and no prefix (`Customization/SecretStore.cs:9-31`) |

Consequences for the port:
1. Treat `Tests/LinuxGuiAudit.cs` and `Tests/Program.cs` as **documentation of intended
   cross-platform behaviour**, not as a Windows-runnable acceptance suite.
2. The Windows `Customization/VariableCatalog.cs` read here is the authoritative Windows
   catalog and contains exactly **429** `V(...)` definitions (verified by count:
   278 in `StaticBuiltIns`, 151 in `AdvancedBuiltIns`). README.en.md:28,91 states
   "429 fixed built-in variables + 17 dynamic per-drive variable templates" — the counts match.
3. The Windows `VariableHub` implements Windows collectors *and* `AdvancedVariableProvider`
   also contains Windows-only collectors; the Linux partial providers
   (`LinuxVariableProvider.cs`, `LinuxSystemVariables.cs`, …) are present but unused by the
   Windows codepaths.
4. Do **not** assume the checkout builds as-is. This audit reports source semantics, not
   build status.

### 0.3 Registry vs. runtime key space

Three distinct key spaces exist and must not be conflated:

1. **Catalog keys** — the 429 advertised names (`VariableCatalog.AllBuiltIns`),
   used by the in-app Variable Library UI.
2. **Runtime keys** — everything `VariableHub.SnapshotAsync` may put into the value
   dictionary, which is a **superset** of the catalog (it also emits profile-derived
   `network.display_*` / `network.profile_*` and `time.display.*` / `time.target.*`,
   dynamic `disk.<letter>.*`, and `custom.<source>.<field>`).
3. **Template keys** — what `TemplateEngine` accepts: any key of form
   `[a-zA-Z0-9_.-]+` (`Customization/TemplateEngine.cs:11`).

---

## 1. Variable system

### 1.1 Registry model

`VariableDefinition` is a positional record (`Customization/VariableCatalog.cs:8-20`):

```csharp
public sealed record VariableDefinition(
    string Key, string Name, string Category, string Description,
    string ValueType, string Unit, string RecommendedUse, string RecommendedFormats)
{
    public string TemplateToken => "{" + Key + "}";
    public override string ToString() => $"{Name}    {Key}";
}
```

Factory defaults (`Customization/VariableCatalog.cs:24-27`):
`valueType = "数值"`, `unit = ""`, `use = "按数据含义决定"`, `formats = "0 或 0.0"`.

`AllBuiltIns` composition and ordering (`Customization/VariableCatalog.cs:759-768`):
`StaticBuiltIns` → `AdvancedBuiltIns` → `DynamicDriveBuiltIns`, de-duplicated by key
(case-insensitive, first wins), then ordered by
`CategoryDisplayRank` (`:534-556`) → prefix rank within category
(`VariableDisplayPrefixes`, `:560-740`) → source index.
`Find(key)` is case-insensitive first match (`:770-771`).
`IsAdvancedKey(key)` tests membership of the 151-key advanced set (`:521-523`).

Category display ranks (`:534-556`), in order:
`CPU`0, `GPU`1, `内存`2, `磁盘`3, `电池`4, `网络`5, `网络探测`6, `Ping（兼容）`7, `系统`8,
`显示器`9, `时间`10, `进程`11, `ECP 应用`12, `DeepSeek API`13, `安全`14, `USB / 外设`15,
`剪贴板`16, `开发者工具`17, `自定义数据`18. Unknown categories rank `int.MaxValue` (`:742-743`).
Category ordering is deliberately semantic, not alphabetical (`:532-533`).

Per-category key counts in the Windows catalog (computed from the `V(...)` calls):

| Category | Keys | Category | Keys |
| --- | ---: | --- | ---: |
| 电池 Battery | 28 | 时间 Time | 43 |
| CPU | 32 | DeepSeek API | 21 |
| 内存 Memory | 24 | 进程 Processes | 12 |
| GPU | 38 | 剪贴板 Clipboard | 7 |
| 网络 Network | 43 | USB / 外设 | 9 |
| 网络探测 Network Probe | 26 | 开发者工具 Developer | 18 |
| Ping（兼容） | 24 | 安全 Security | 16 |
| 磁盘 Disk | 19 | ECP 应用 | 16 |
| 系统 System | 38 | **total** | **429** |
| 显示器 Display | 15 | | |

All values are produced as `double`, `string`, `bool`, or `int` objects in a
`Dictionary<string, object?>` keyed case-insensitively
(`Customization/VariableHub.cs:121`). Nothing is strongly typed at the template layer;
`TemplateEngine` coerces at render time (`Customization/TemplateEngine.cs:214-247`).

### 1.2 Complete variable registry (Windows catalog, 429 keys)

Producer legend (all under `Customization/`):

* `VH.Clock` = `VariableHub.AddClockAndSystem` (`VariableHub.cs:189-273`)
* `VH.App` = `VariableHub.AddApp` (`:275-298`)
* `VH.Display` = `VariableHub.AddDisplay` (`:300-317`)
* `VH.CpuMem` = `VariableHub.AddCpuAndMemory` (`:319-494`) — Win32 `GetSystemTimes` /
  `GlobalMemoryStatusEx` / WMI
* `VH.Battery` = `VariableHub.AddBattery` (`:496-617`)
* `VH.Gpu` = `VariableHub.AddGpu` (`:619-780`) + `GpuAdapterCatalog`
* `VH.Net` = `VariableHub.AddNetwork` (`:1114-1225`)
* `VH.Disk` = `VariableHub.AddDisk` (`:1227-1320`)
* `VH.Probe` = `VariableHub.AddPingAsync` (`:796-943`)
* `VH.DeepSeek` = `VariableHub.AddDeepSeekBalanceAsync` (`:1322-1363`) / `CopyDeepSeek`
  (`:1365-1374`) / `AddDeepSeekPeriod` (`:1400-1441`)
* `VH.Custom` = `VariableHub.AddCustomHttpAsync` (`:1443-1491`)
* `AVP.*` = `AdvancedVariableProvider` (all methods named in the legend)
* `HPR.*` = `HudProfileRenderer.BuildEffectiveVariables` (`HudProfileRenderer.cs:81-185`)

#### 1.2.1 电池 Battery — 28 keys

Producers: `VH.Battery` (`VariableHub.cs:496-617`); WMI `root\WMI` classes
`BatteryStatus` (`:505-521`, polled every 5 s), `BatteryFullChargedCapacity` (`:531-536`),
`BatteryStaticData` (`:542-547`), `BatteryCycleCount` (`:553-558`, static data polled every
10 min); Win32 `GetSystemPowerStatus` (`:570-578`, `SYSTEM_POWER_STATUS` at `:1747-1756`).
Advanced: `AVP.AddBatteryAdvanced` (`AdvancedVariableProvider.cs:130-171`).

| Key | Name (zh) | Unit/Type | Derivation |
| --- | --- | --- | --- |
| `battery.percent` | 电池电量 | % | `clamp(RemainingCapacity/FullChargedCapacity*100,0,100)`; overridden by `ps.BatteryLifePercent` when `!= 255` (`VH.Battery:563-565,573-574,592`) |
| `battery.remaining_mwh` | 当前电池容量 | mWh | `BatteryStatus.RemainingCapacity`; if `<=0` and full>0 → `full*percent/100` (`:580-582,593`) |
| `battery.full_mwh` | 满充容量 | mWh | `BatteryFullChargedCapacity.FullChargedCapacity` (`:534,594`) |
| `battery.design_mwh` | 设计容量 | mWh | `BatteryStaticData.DesignedCapacity` (`:545,595`) |
| `battery.remaining_wh` | 当前电池容量（Wh） | Wh | `remaining_mwh/1000` (`:596`) |
| `battery.full_wh` | 满充容量（Wh） | Wh | `full_mwh/1000` (`:597`) |
| `battery.design_wh` | 设计容量（Wh） | Wh | `design_mwh/1000` (`:598`) |
| `battery.health_percent` | 电池健康度 | % | `clamp(full/design*100,0,200)`; 0 if either is 0 (`:607-609`) |
| `battery.ac_online` | 外接电源状态 | bool | `BatteryStatus.PowerOnline`, overwritten by `ps.ACLineStatus == 1` (`:515,572,599`) |
| `battery.charging` | 正在充电 | bool | `BatteryStatus.Charging` (`:513,600`) |
| `battery.discharging` | 正在放电 | bool | `BatteryStatus.Discharging` (`:514,601`) |
| `battery.rate_watts` | 电池实时功率 | W | charging→ChargeRate/1000; discharging→DischargeRate/1000; else `max(charge,discharge)` (`:511-519,602`) |
| `battery.charge_rate_watts` | 充电功率 | W | `max(0,ChargeRate)/1000` (`:511,603`) |
| `battery.discharge_rate_watts` | 放电功率 | W | `max(0,DischargeRate)/1000` (`:512,604`) |
| `battery.voltage_mv` | 电池电压（mV） | mV | `max(0,BatteryStatus.Voltage)` (`:516,605`) |
| `battery.voltage_v` | 电池电压（V） | V | `voltage_mv/1000` (`:606`) |
| `battery.time_remaining_seconds` | 预计剩余使用时间 | s | `ps.BatteryLifeTime` when `>=0`, else 0 (`:575,610`) |
| `battery.time_remaining_text` | 预计剩余使用时间文字 | text | `HH:MM:SS` when >0 else localized "未知"/"Unknown" (`:611`, `FormatDuration` `:1640-1645`) |
| `battery.full_life_seconds` | 预计满电续航 | s | `ps.BatteryFullLifeTime` when `>=0`, else 0 (`:576,612`) |
| `battery.saver_on` | 节电模式状态 | bool | `ps.SystemStatusFlag == 1` (`:577,613`) |
| `battery.status_text` | 电池状态文字 | text | charging→充电中/Charging; discharging→使用电池/On Battery; AC→已接通电源/AC Connected; else 未知/Unknown (`:584-590,614`) |
| `battery.power_source` | 当前电源来源 | text | AC→交流电源/AC else 电池/Battery (`:615`) |
| `battery.cycle_count` | 电池循环次数 | int 次 | `BatteryCycleCount.CycleCount` (`:556,616`) |
| `battery.design_vs_current_health` | 设计容量健康度 | % | advanced; = `battery.health_percent` when >0 (`AVP.AddBatteryAdvanced:135,141`) |
| `battery.estimated_time_to_empty` | 预计耗尽剩余时间 | s | advanced; emitted only if `battery.time_remaining_seconds > 0` (`:136,138`) |
| `battery.estimated_time_to_full` | 预计充满剩余时间 | s | advanced; `(full_mwh-remaining_mwh)/(charge_w*1000)*3600` only when charge>0 and full>remaining (`:139-140`) |
| `battery.temperature` | 电池温度 | °C | advanced; WMI `BatteryTemperature.Temperature`; value >1000 treated as tenths-Kelvin (`raw/10-273.15`), accepted only in (−50,150) (`:146-157`) |
| `battery.chemistry` | 电池化学类型 | text | advanced; `Win32_Battery.Chemistry` mapped by `BatteryChemistryName` 1..8 (`:162-168`, `:1214-1225`) |

#### 1.2.2 CPU — 32 keys

Producers: `VH.CpuMem` (`VariableHub.cs:319-442`); CPU load from `GetSystemTimes`
(`kernel32` `:1711-1712`) delta; static info from WMI `Win32_Processor`
(`:358-389`, cached 10 min); live frequency from
`Win32_PerfFormattedData_Counters_ProcessorInformation WHERE Name='_Total'`
(`:398-410`, cached 750 ms) with `Win32_Processor.CurrentClockSpeed` fallback.
Advanced: `AVP.AddCpuAdvanced` (`AdvancedVariableProvider.cs:173-204`) using
`EnsurePerfOs` (`:568-611`) and LibreHardwareMonitor (`EnsureHardware`, `:654-715`).

| Key | Name (zh) | Unit/Type | Derivation |
| --- | --- | --- | --- |
| `cpu.usage` | CPU 使用率 | % | `clamp(user+kernel,0,100)` from `GetSystemTimes` deltas; 0 until primed (`:324-348`) |
| `cpu.user_usage` | CPU 用户态使用率 | % | `userDelta/totalDelta*100` (`:336,349`) |
| `cpu.kernel_usage` | CPU 内核态使用率 | % | `(kernelDelta-idleDelta)/totalDelta*100` (`:337,350`) |
| `cpu.idle_percent` | CPU 空闲率 | % | `idleDelta/totalDelta*100`; if not primed `max(0,100-usage)` (`:335,351`) |
| `cpu.frequency_ghz` | CPU 当前频率 | GHz | `maxGhz*PercentProcessorPerformance/100`, else `ProcessorFrequency/1000` (`:403-408,431`) |
| `cpu.frequency_mhz` | CPU 当前频率（MHz） | MHz | `frequency_ghz*1000` (`:432`) |
| `cpu.max_frequency_ghz` | CPU 最大频率 | GHz | `max(Win32_Processor.MaxClockSpeed)/1000` (`:377,384,433`) |
| `cpu.max_frequency_mhz` | CPU 最大频率（MHz） | MHz | ×1000 (`:434`) |
| `cpu.frequency_percent` | CPU 频率比例 | % | `cur/max*100`, 0 when max<=0, can exceed 100 (`:435`) |
| `cpu.name` | CPU 名称 | text | `Win32_Processor.Name` first socket, default `"CPU"` (`:368,428`) |
| `cpu.manufacturer` | CPU 制造商 | text | `Win32_Processor.Manufacturer` (`:369,429`) |
| `cpu.architecture` | CPU 架构 | text | `CpuArchitectureName(Win32_Processor.Architecture)`; 0 x86, 5 ARM, 6 Itanium, 9 x64, 12 ARM64, else process arch (`:370,1530-1542`) |
| `cpu.physical_cores` | CPU 物理核心数 | int 核 | Σ `NumberOfCores`; fallback `max(1,ProcessorCount/2)` (`:375,382`) |
| `cpu.logical_processors` | CPU 逻辑处理器数 | int 线程 | Σ `NumberOfLogicalProcessors`; fallback `Environment.ProcessorCount` (`:376,383`) |
| `cpu.socket_count` | CPU 插槽数 | int 个 | count of `Win32_Processor` rows, min 1 (`:365,381`) |
| `cpu.virtualization_enabled` | CPU 固件虚拟化 | bool | `VirtualizationFirmwareEnabled` (`:371,439`) |
| `cpu.l2_cache_kb` | CPU L2 缓存 | KB | `L2CacheSize` (`:372,440`) |
| `cpu.l3_cache_kb` | CPU L3 缓存 | KB | `L3CacheSize` (`:373,441`) |
| `cpu.usage_avg_1m` | CPU 1 分钟平均使用率 | % | advanced; rolling samples ≤15.5 min, average since now−1 min (`AVP:179-186`) |
| `cpu.usage_avg_5m` | CPU 5 分钟平均使用率 | % | as above, 5 min window |
| `cpu.usage_avg_15m` | CPU 15 分钟平均使用率 | % | as above, 15 min window |
| `cpu.usage_max` | CPU 近期最高使用率 | % | max over retained samples (`:187`) |
| `cpu.context_switches` | 上下文切换速率 | 次/s | `Win32_PerfFormattedData_PerfOS_System.ContextSwitchesPersec` (`:575-579,192`) |
| `cpu.system_calls` | 系统调用速率 | 次/s | `SystemCallsPersec` (`:579,193`) |
| `cpu.interrupts` | 硬件中断速率 | 次/s | `ProcessorInformation.InterruptsPersec` (`:589,194`) |
| `cpu.dpc_time` | DPC 时间比例 | % | `PercentDPCTime` (`:590,195`) |
| `cpu.instructions_per_second` | CPU 每秒退休指令 | instr/s | `InstructionsRetiredPersec` (`:591,196`) |
| `cpu.temperature_max` | CPU 最高温度 | °C | LHM max Temperature sensor on CPU (`:688-690,199`) |
| `cpu.core.temperature_avg` | CPU 核心平均温度 | °C | average of sensors whose name contains "Core", else `temperature_max` (`:689-691,200`) |
| `cpu.power_max` | CPU 功耗 | W | max `SensorType.Power` (`:692,201`) |
| `cpu.core.voltage` | CPU 核心电压 | V | first Voltage sensor containing "Core", else max Voltage (`:693,202`) |
| `cpu.bus_speed` | CPU 总线/BCLK | MHz | first Clock sensor containing "Bus", else "BCLK" (`:694,203`) |

#### 1.2.3 内存 Memory — 24 keys

Producers: `VH.CpuMem` memory block (`VariableHub.cs:444-493`) via
`GlobalMemoryStatusEx` (`MEMORYSTATUSEX` `:1733-1745`) and
`Win32_PerfFormattedData_PerfOS_Memory` (`:476-492`);
advanced `AVP.AddMemoryAdvanced` (`AdvancedVariableProvider.cs:206-220`) via `EnsurePerfOs`
and `EnsureMemoryStatic` (`:613-652`).

| Key | Name (zh) | Unit/Type | Derivation |
| --- | --- | --- | --- |
| `memory.used_bytes` | 已用物理内存 | Byte | `total-avail` from `ullTotalPhys/ullAvailPhys` (`:449-451,461`) |
| `memory.available_bytes` | 可用物理内存 | Byte | `ullAvailPhys` (`:460`) |
| `memory.total_bytes` | 物理内存总量 | Byte | `ullTotalPhys` (`:459`) |
| `memory.usage` | 内存使用率 | % | `used/total*100`, 0 if total<=0 (`:462`) |
| `memory.free_percent` | 内存空闲率 | % | `avail/total*100` (`:463`) |
| `memory.commit_used_bytes` | 已提交内存 | Byte | `ullTotalPageFile-ullAvailPageFile` (`:453-454,466`) |
| `memory.commit_available_bytes` | 剩余提交额度 | Byte | `ullAvailPageFile` (`:465`) |
| `memory.commit_limit_bytes` | 提交限制 | Byte | `ullTotalPageFile` (`:464`) |
| `memory.commit_usage` | 提交使用率 | % | `commitUsed/commitLimit*100` (`:467`) |
| `memory.virtual_used_bytes` | 已用虚拟内存 | Byte | `ullTotalVirtual-ullAvailVirtual` (`:455-457,470`) |
| `memory.virtual_available_bytes` | 可用虚拟内存 | Byte | `ullAvailVirtual` (`:469`) |
| `memory.virtual_total_bytes` | 虚拟内存总量 | Byte | `ullTotalVirtual` (`:468`) |
| `memory.virtual_usage` | 虚拟内存使用率 | % | `virtualUsed/virtualTotal*100` (`:471`) |
| `memory.cache_bytes` | 系统缓存内存 | Byte | `CacheBytes`; 0 on failure (`:481,489`) |
| `memory.paged_pool_bytes` | 分页池 | Byte | `PoolPagedBytes` (`:482`) |
| `memory.nonpaged_pool_bytes` | 非分页池 | Byte | `PoolNonpagedBytes` (`:483`) |
| `memory.standby_bytes` | Standby 缓存 | Byte | advanced; Σ `StandbyCacheCoreBytes`+`StandbyCacheNormalPriorityBytes`+`StandbyCacheReserveBytes`, only if >0 (`AVP:601-604`) |
| `memory.modified_bytes` | Modified 页面 | Byte | advanced; `ModifiedPageListBytes` (`:605`) |
| `memory.hardware_reserved_bytes` | 硬件保留内存 | Byte | advanced; Σ `Win32_PhysicalMemory.Capacity` − `ullTotalPhys`, ≥0 (`:625-648`) |
| `memory.speed_mhz` | 内存工作频率 | MHz | advanced; max of `max(ConfiguredClockSpeed,Speed)` (`:630-637`) |
| `memory.slot_count` | 内存插槽总数 | int 个 | advanced; Σ `Win32_PhysicalMemoryArray.MemoryDevices` (`:642-643`) |
| `memory.slot_used` | 已使用内存插槽 | int 个 | advanced; count of enumerated memory modules (`:628,636`) |
| `memory.form_factor` | 内存形态 | text | advanced; distinct `MemoryFormFactorName` joined `" / "` (`:632,638,1226`) |
| `memory.type` | 内存类型 | text | advanced; `MemoryTypeName(SMBIOSMemoryType>0 ? it : MemoryType)` joined `" / "` (`:633-639,1227`) |

Note: SPD timing variables are deliberately **not** exposed (`AdvancedVariableProvider.cs:219`).

#### 1.2.4 GPU — 38 keys

Producers: `VH.Gpu` (`VariableHub.cs:619-780`); adapter identity/size from
`GpuAdapterCatalog.GetAdapters()` (`GpuAdapterCatalog.cs:60-75`); engine load from
`Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine` (`:675-700`, cached 700 ms),
memory from `..._GPUAdapterMemory` (`:720-735`); advanced
`AVP.AddGpuAdvanced` (`AdvancedVariableProvider.cs:222-279`) + `EnsureNvidia`
(`nvidia-smi --query-gpu=...`, `:717-737`).

| Key | Name (zh) | Unit/Type | Derivation |
| --- | --- | --- | --- |
| `gpu.usage` | GPU 总使用率 | % | max over per-engine summed utilization, clamped 0–100 (`:705-707,764`) |
| `gpu.usage_3d` | GPU 3D 使用率 | % | Σ per type `3D`, max across adapters, clamp (`:708,765`, `MaxGpuType` `:1544-1554`) |
| `gpu.usage_compute` | GPU Compute 使用率 | % | `max(Compute_0, Compute_1)` (`:709,766`) |
| `gpu.usage_copy` | GPU Copy 使用率 | % | type `Copy` (`:710,767`) |
| `gpu.usage_video_decode` | GPU 视频解码使用率 | % | `max(VideoDecode, Video_Decode)` (`:711,768`) |
| `gpu.usage_video_encode` | GPU 视频编码使用率 | % | `max(VideoEncode, Video_Encode)` (`:712,769`) |
| `gpu.name` | GPU 名称 | text | selected adapter `Name`, default `"GPU"` (`:630,757`) |
| `gpu.adapter_id` | GPU 设备 ID | text | selected adapter `Id` — `pnp:<PNPDeviceID>` or `dxgi:luid_0x…_0x…` (`:629,758`, `GpuAdapterCatalog:154-156,247`) |
| `gpu.physical_index` | GPU 物理索引 | int | DXGI `phys_<n>` resolved index, may be −1 (`:631,759`, `GpuAdapterCatalog:340-358`) |
| `gpu.count` | GPU 数量 | int 个 | `adapters.Count` (`:760`) |
| `gpu.vram_bytes` | GPU 专用显存总量 | Byte | dedicated total (`:761`) |
| `gpu.dedicated_total_bytes` | 专用显存总量 | Byte | same (`:762`) |
| `gpu.dedicated_used_bytes` | 已用专用显存 | Byte | max `DedicatedUsage` over matching LUID rows (`:730,770`) |
| `gpu.dedicated_usage` | 专用显存使用率 | % | `dedicatedUsed/dedicatedTotal*100`, clamp (`:772`) |
| `gpu.shared_limit_bytes` | 共享 GPU 内存上限 | Byte | `SharedSystemMemory` (`:634,763`) |
| `gpu.shared_used_bytes` | 已用共享 GPU 内存 | Byte | max `SharedUsage` (`:731,771`) |
| `gpu.shared_usage` | 共享 GPU 内存使用率 | % | `sharedUsed/sharedLimit*100`, clamp (`:773`) |
| `gpu.memory_used_bytes` | GPU 当前显存占用 | Byte | `displayUsed = dedicated_used` (`:746,774`) |
| `gpu.memory_total_bytes` | GPU 显存总量 | Byte | `displayTotal = dedicatedTotal`; if total<=0 and used>0 → total=used (`:747-752,775`) |
| `gpu.total_memory_used_bytes` | GPU 总内存占用 | Byte | dedicated+shared used (`:754,776`) |
| `gpu.total_memory_limit_bytes` | GPU 总可用内存上限 | Byte | dedicatedTotal+sharedLimit (`:755,777`) |
| `gpu.total_memory_usage` | GPU 总内存使用率 | % | ratio, clamp (`:778`) |
| `gpu.uses_unified_memory` | GPU 是否主要使用共享内存 | bool | `DedicatedMemoryBytes < 2 GiB && Shared > Dedicated` (`:779`, `GpuAdapterCatalog:24-25`) |
| `gpu.temperature` | GPU 核心温度 | °C | advanced; LHM Voltage/Temperature sensor "Core" (`AVP:230,699`) |
| `gpu.hotspot_temperature` | GPU 热点温度 | °C | advanced; "Hot Spot"/"Hotspot" (`:231,700`) |
| `gpu.memory_junction_temperature` | GPU 显存结温 | °C | advanced; "Memory Junction"/"Memory" (`:232,701`) |
| `gpu.power_w` | GPU 当前功率 | W | advanced; LHM Power "Total"→"Package"→max (`:236,705`) |
| `gpu.power_limit_w` | GPU 功率限制 | W | advanced; `nvidia-smi power.limit` (`:272,722,731`) |
| `gpu.voltage_v` | GPU 核心电压 | V | advanced; LHM Voltage "Core"→max (`:233,702`) |
| `gpu.core_clock_mhz` | GPU 核心频率 | MHz | advanced; LHM Clock "Core", else nvidia-smi `clocks.gr` (`:234,273,703,731`) |
| `gpu.memory_clock_mhz` | GPU 显存频率 | MHz | advanced; LHM Clock "Memory", else `clocks.mem` (`:235,274,704,732`) |
| `gpu.pcie_gen` | GPU PCIe 代际 | Gen | advanced; `pcie.link.gen.current` (`:275,732`) |
| `gpu.pcie_lanes` | GPU PCIe 通道数 | lane | advanced; `pcie.link.width.current` (`:276,733`) |
| `gpu.driver_version` | GPU 驱动版本 | text | advanced; `Win32_VideoController.DriverVersion` (`:252-253`) |
| `gpu.driver_date` | GPU 驱动日期 | text | advanced; WMI date → `yyyy-MM-dd` (`:254-255`, `TryWmiDate` `:1228`) |
| `gpu.bios_version` | GPU VBIOS 版本 | text | advanced; `nvidia-smi vbios_version` (`:271,722,731`) |
| `gpu.nvidia_smi_available` | nvidia-smi 可用 | bool | advanced; `CommandExists("nvidia-smi.exe")` (`:260-261`, `:1244`) |
| `gpu.amd_adrenalin_available` | AMD Adrenalin 可用 | bool | advanced; process `RadeonSoftware` or `%ProgramFiles%\AMD\CNext\CNext\RadeonSoftware.exe` (`:262-263`) |

Unified-memory note (important for Android iGPUs): the default HUD deliberately shows
**dedicated** VRAM and does not fold shared memory into capacity
(`VariableHub.cs:742-747`).

#### 1.2.5 网络 Network — 43 keys

Producers: `VH.Net` (`VariableHub.cs:1114-1225`); counters are summed over all NICs with
`OperationalStatus.Up` and type ≠ Loopback (`:1129-1131`); rates are deltas against
`_lastRx/_lastTx/_lastNetworkAt` with `dt >= 0.05 s` (`:1176-1179`); first sample yields 0
because `_lastRx == 0` (`:1178-1179`). Advanced: `AVP.AddNetworkAdvanced`
(`AdvancedVariableProvider.cs:281-295`) + `EnsureNetworkStatic` (`:739-775`, 3 s cache) +
`AddPublicIpAsync` (`:552-566`, 5 min cache).

| Key | Name (zh) | Unit/Type | Derivation |
| --- | --- | --- | --- |
| `network.download_bps` | 系统总下载速度 | Byte/s | `max(0,(rx-lastRx)/dt)`; 0 on first sample (`:1178,1183`) |
| `network.upload_bps` | 系统总上传速度 | Byte/s | `max(0,(tx-lastTx)/dt)` (`:1179,1184`) |
| `network.total_bps` | 系统总吞吐量 | Byte/s | download+upload (`:1180,1185`) |
| `network.download_mbps` | 系统总下载速度（Mbps） | Mbps | `download*8/1e6` (decimal) (`:1186`) |
| `network.upload_mbps` | 系统总上传速度（Mbps） | Mbps | `upload*8/1e6` (`:1187`) |
| `network.total_mbps` | 系统总吞吐量（Mbps） | Mbps | `total*8/1e6` (`:1188`) |
| `network.link_speed_bps` | 总链路速率 | bit/s | Σ `nic.Speed` (`:1144-1148,1189`) |
| `network.max_link_speed_bps` | 最高单接口链路速率 | bit/s | max `nic.Speed` (`:1147,1190`) |
| `network.utilization_percent` | 网络链路利用率 | % | `clamp(total*8/linkSpeed*100,0,100)`, 0 if linkSpeed<=0 (`:1181,1191`) |
| `network.total_received_bytes` | 累计接收流量 | Byte | Σ `IPv4Statistics.BytesReceived` (`:1138,1192`) |
| `network.total_sent_bytes` | 累计发送流量 | Byte | Σ `BytesSent` (`:1139,1193`) |
| `network.total_transferred_bytes` | 累计总流量 | Byte | rx+tx (`:1194`) |
| `network.active_interface_count` | 活动网络接口数量 | int 个 | count of Up non-loopback NICs (`:1134,1195`) |
| `network.interface_names` | 活动网络接口名称 | text | `", "` joined distinct `nic.Name` (`:1135,1196`) |
| `network.interface_types` | 活动网络接口类型 | text | distinct `NetworkInterfaceType.ToString()` (`:1136,1197`) |
| `network.ipv4_addresses` | 本机 IPv4 地址 | text | distinct unicast IPv4 (`:1153-1156,1198`) |
| `network.ipv6_addresses` | 本机 IPv6 地址 | text | distinct non-link-local IPv6 (`:1157-1159,1199`) |
| `network.default_gateways` | 默认网关 | text | gateway addresses ≠ `0.0.0.0`/`::` (`:1161-1166,1200`) |
| `network.dns_servers` | DNS 服务器 | text | `IPProperties.DnsAddresses` (`:1167-1171,1201`) |
| `network.available` | 网络可用状态 | bool | `NetworkInterface.GetIsNetworkAvailable()` (`:1202`) |
| `network.packets_received` | 累计接收数据包 | 包 | Σ `UnicastPacketsReceived` (`:1140,1203`) |
| `network.packets_sent` | 累计发送数据包 | 包 | Σ `UnicastPacketsSent` (`:1141,1204`) |
| `network.receive_errors` | 接收错误 | 个 | Σ `IncomingPacketsWithErrors` (`:1142,1205`) |
| `network.send_errors` | 发送错误 | 个 | Σ `OutgoingPacketsWithErrors` (`:1143,1206`) |
| `network.signal_dbm` | Wi-Fi 信号强度 | dBm | advanced; `netsh wlan show interfaces` Signal% → `clamp(q/2-100,-100,-50)` (`AVP:788-793`, `:290`) |
| `network.public_ipv4` | 公网 IPv4 | text | advanced; `GET https://api.ipify.org`, 5 min cache, validated `InterNetwork` (`:557,564`) |
| `network.public_ipv6` | 公网 IPv6 | text | advanced; `GET https://api6.ipify.org`, validated `InterNetworkV6` (`:562,565`) |
| `network.vpn_status` | VPN 状态 | bool | advanced; Up non-loopback NIC of type Ppp/Tunnel or name/description matches `VPN\|WireGuard\|Tailscale\|ZeroTier\|OpenVPN\|Wintun\|IKEv2` (`:746-751,284`) |
| `network.vpn_name` | VPN 名称 | text | advanced; that NIC's `Name` if non-empty (`:285`) |
| `network.proxy_status` | 系统代理状态 | bool | advanced; HKCU `Internet Settings\ProxyEnable != 0` (`:760-761,286`) |
| `network.proxy_address` | 系统代理地址 | text | advanced; `ProxyServer` (`:762,287`) |
| `network.dns_latency_ms` | 系统 DNS 解析耗时 | ms | advanced; stopwatch around `Dns.GetHostAddresses("one.one.one.one")` (`:767-770,294`) |
| `network.tcp_connections` | 活动 TCP 连接数 | int 条 | advanced; `IPGlobalProperties.GetActiveTcpConnections().Length` (`:754,288`) |
| `network.udp_connections` | UDP 监听端点数 | int 个 | advanced; `GetActiveUdpListeners().Length` (`:755,289`) |
| `network.wifi_channel` | Wi-Fi 信道 | 数值 | advanced; netsh `Channel`/`信道` (`:794-795,291`) |
| `network.wifi_band` | Wi-Fi 频段 | text | advanced; netsh `Band`/`波段`; fallback `channel<=14 ? "2.4 GHz" : "5/6 GHz"` (`:796,799-800,292`) |
| `network.wifi_standard` | Wi-Fi 标准 | text | advanced; netsh `Radio type`/`无线电类型` (`:797,293`) |
| `network.display_download` | 方案下载速度文字 | text | `HPR.FormatNetworkSpeed(download_bps, profile.NetworkDisplayUnit)` (`HudProfileRenderer.cs:144`, `:205-216`) |
| `network.display_upload` | 方案上传速度文字 | text | as above for upload (`:145`) |
| `network.profile_percent` | 方案网络百分比 | % | `clamp(measuredBps/referenceBps*100,0,100)` (`:174-178`) |
| `network.profile_percent_text` | 方案网络百分比文字 | text | `"{prefix}{round(percent):0}%"`; prefix `↓ `/`↑ ` for Download/Upload, empty for Total/Max (`:150-169,181`) |
| `network.profile_percent_bps` | 百分比计算速度 | Byte/s | the measured rate used for the percentage (`:161,166,179`) |
| `network.profile_percent_mode` | 网络百分比模式 | text | normalized `Total`/`Download`/`Upload`/`Max` (`:196-203`, `:180`) |

On total failure of the network collector a reduced fallback set is written:
`download_bps`, `upload_bps`, `total_bps`, `download_mbps`, `upload_mbps`, `total_mbps`,
`link_speed_bps`, `max_link_speed_bps`, `utilization_percent` = 0 and
`network.available = false` (`VariableHub.cs:1212-1224`).

#### 1.2.6 网络探测 probe.* — 26 keys

Producer: `VH.Probe` (`VariableHub.cs:796-943`). Per-`(protocol|target|port)` state key
(`:806`); a probe is issued at most every 800 ms (`:829`); history is a rolling queue of 20
results (`:849-851`). `PingTimeoutMs = 1000` (`:29`), `PingFullScaleMs = 999` (`:30`).
Protocol is normalized to `ICMP` unless `TCP`/`UDP` (`:945-949`). Port defaults to 443 and
is clamped 1..65535 (`:805`). ICMP uses `System.Net.Ping`,
TCP uses `TcpClient.ConnectAsync` with linked timeout, UDP sends one byte
`0x00` and waits for a reply (`:959-1099`).

| Key | Name (zh) | Unit/Type | Derivation |
| --- | --- | --- | --- |
| `probe.target` | 探测地址 | text | configured target or `"1.1.1.1"` (`:803,891`) |
| `probe.address` | 实际响应地址 | text | responding address (ICMP reply / TCP remote / UDP remote) (`:892`) |
| `probe.protocol` | 检测协议 | text | normalized protocol (`:893`) |
| `probe.port` | 检测端口 | int | port for TCP/UDP, `0` for ICMP (`:894`) |
| `probe.endpoint` | 探测端点 | text | ICMP → target; else `"{target}:{port}"` (`:888,895`) |
| `probe.online` | 探测是否成功 | bool | last result success (`:896`) |
| `probe.status_text` | 探测状态文字 | text | localized status, e.g. 在线/Online, 超时/Timed out, 端口不可达/Port unreachable (`:897`, `PingStatusText` `:1101-1112`) |
| `probe.reply_status` | 探测原始状态 | text | raw protocol status: `IPStatus` name, or `Connected`, `TimedOut`, `Response`, `SocketErrorCode` (`:898,986,1032,1037,1080`) |
| `probe.latency_ms` | 探测延迟 | ms | success → RTT/connect/echo ms; failure → 999 (`:884,886,899`) |
| `probe.latency_text` | 探测延迟文字 | text | `"{ms}ms"` on success, else `StatusText` (`:887,900`) |
| `probe.latency_progress` | 延迟进度 | % | `clamp(latency/999*100,0,100)`; failures use 999 → 100% (`:884-885,901`) |
| `probe.full_scale_ms` | 延迟 100% 对应值 | ms | constant 999 (`:902`) |
| `probe.timeout_ms` | 单次探测超时 | ms | constant 1000 (`:903`) |
| `probe.ttl` | ICMP TTL | int | ICMP reply TTL; 0 for TCP/UDP (`:904`) |
| `probe.sent` | 探测样本数 | int 次 | history count (`:867,905`) |
| `probe.received` | 成功样本数 | int 次 | successes in history (`:868,906`) |
| `probe.lost` | 失败样本数 | int 次 | `sent-received` (`:869,907`) |
| `probe.loss_percent` | 丢包率 | % | `lost/sent*100`; if sent==0 → 0 when current success else 100 (`:870,908`) |
| `probe.avg_latency_ms` | 平均延迟 | ms | mean of successful sample latencies, 0 if none (`:872,909`) |
| `probe.min_latency_ms` | 最低延迟 | ms | min of successes (`:873,910`) |
| `probe.max_latency_ms` | 最高延迟 | ms | max of successes (`:874,911`) |
| `probe.jitter_ms` | 延迟抖动 | ms | mean |Δ| between adjacent successes (`:876-882,912`) |
| `probe.last_success` | 最近成功时间 | text | `yyyy-MM-dd HH:mm:ss` or `""` (`:913`) |
| `probe.error` | 探测错误 | text | `""` on success else raw reply status (`:914`, record `:1780`) |
| `probe.dns_resolve_time` | DNS 解析耗时 | ms | advanced key, emitted by `VH.Probe` when hostname was resolved (`:915`); direct IP → 0 (`:974`) |
| `probe.tcp_connect_time` | TCP 建连耗时 | ms | emitted for TCP probes (`:916`, `:1032`) |

Pre-probe placeholder when no result exists yet (`:862`): success=false,
`IPStatus.TimedOut`, status 等待检测/Waiting, error 尚未完成检测/Probe not completed.

#### 1.2.7 Ping（兼容） ping.* — 24 keys

`VH.Probe` writes an identical alias set immediately after the `probe.*` block
(`VariableHub.cs:918-942`) with these name differences only:
`ping.progress` (≡ `probe.latency_progress`, `:929`),
`ping.full_scale_ms` (≡ `probe.full_scale_ms`, `:930`),
`ping.timeout_ms` (≡ `probe.timeout_ms`, `:931`).
All other names are `probe.X` → `ping.X` with identical values:
`ping.target`(`:919`), `ping.address`(`:920`), `ping.protocol`(`:921`), `ping.port`(`:922`),
`ping.endpoint`(`:923`), `ping.online`(`:924`), `ping.status_text`(`:925`),
`ping.reply_status`(`:926`), `ping.latency_ms`(`:927`), `ping.latency_text`(`:928`),
`ping.ttl`(`:932`), `ping.sent`(`:933`), `ping.received`(`:934`), `ping.lost`(`:935`),
`ping.loss_percent`(`:936`), `ping.avg_latency_ms`(`:937`), `ping.min_latency_ms`(`:938`),
`ping.max_latency_ms`(`:939`), `ping.jitter_ms`(`:940`), `ping.last_success`(`:941`),
`ping.error`(`:942`).
There is **no** `ping.dns_resolve_time` / `ping.tcp_connect_time` alias, and **no**
`probe.progress` alias. Compatibility rule: both families are always populated together from
the same snapshot; neither is marked deprecated at runtime. The catalog describes `ping.*`
as "Ping（兼容）" / "Ping (Compatibility)" (`VariableCatalog.cs:177-200`, category rank 7 at
`:544`). Any port must keep both so old custom profiles keep working.

#### 1.2.8 磁盘 Disk — 19 static + 17 dynamic templates

Static producers: `VH.Disk` (`VariableHub.cs:1227-1320`); system volume = root of
`Environment.SystemDirectory` (`:1229`); `DriveInfo` cached 5 s (`:1232-1248`); perf counters
`Win32_PerfFormattedData_PerfDisk_LogicalDisk WHERE Name='<X:>'` cached 900 ms
(`:1250-1269`); fixed-drive aggregate from `DriveInfo.GetDrives()` with
`IsReady && DriveType.Fixed` (`:1291-1311`). Advanced: `AVP.AddDiskAdvanced`
(`AdvancedVariableProvider.cs:297-346`) + `EnsureDiskAdvanced` (`:803-841`, 60 s cache,
PowerShell `Get-Partition`/`Get-Disk`/`Get-StorageReliabilityCounter`, `fsutil behavior
query DisableDeleteNotify`).

| Key | Name (zh) | Unit/Type | Derivation |
| --- | --- | --- | --- |
| `disk.system.root` | 系统盘盘符 | text | `DriveInfo(systemRoot).Name`, fallback raw root (`VariableHub.cs:1272`) |
| `disk.system.label` | 系统盘卷标 | text | `VolumeLabel` (`:1273`) |
| `disk.system.filesystem` | 系统盘文件系统 | text | `DriveFormat` (`:1274`) |
| `disk.system.used_bytes` | 系统盘已用空间 | Byte | `max(0,TotalSize-AvailableFreeSpace)` (`:1271,1277`) |
| `disk.system.free_bytes` | 系统盘可用空间 | Byte | `AvailableFreeSpace` (`:1276`) |
| `disk.system.total_bytes` | 系统盘总容量 | Byte | `TotalSize` (`:1275`) |
| `disk.system.usage` | 系统盘使用率 | % | `used/total*100` (`:1278`) |
| `disk.system.free_percent` | 系统盘空闲率 | % | `free/total*100` (`:1279`) |
| `disk.system.read_bps` | 系统盘读取速度 | Byte/s | `DiskReadBytesPersec` (`:1261,1280`) |
| `disk.system.write_bps` | 系统盘写入速度 | Byte/s | `DiskWriteBytesPersec` (`:1262,1281`) |
| `disk.system.io_bps` | 系统盘总 IO 速度 | Byte/s | read+write (`:1282`) |
| `disk.system.active_percent` | 系统盘活动时间 | % | `clamp(PercentDiskTime,0,100)` (`:1263,1283`) |
| `disk.system.queue_length` | 系统盘队列长度 | 项 | `CurrentDiskQueueLength` (`:1264,1284`) |
| `disk.fixed.count` | 固定磁盘数量 | int 个 | fixed-drive count (`:1287,1293,1314`) |
| `disk.fixed.used_bytes` | 所有固定磁盘已用空间 | Byte | Σ (total−free) (`:1296-1297,1317`) |
| `disk.fixed.free_bytes` | 所有固定磁盘可用空间 | Byte | Σ free (`:1298,1316`) |
| `disk.fixed.total_bytes` | 所有固定磁盘总容量 | Byte | Σ total (`:1297,1315`) |
| `disk.fixed.usage` | 所有固定磁盘总体使用率 | % | `allUsed/allTotal*100` (`:1318`) |
| `disk.fixed.list` | 固定磁盘列表 | text | `", "` joined drive roots (`:1299,1319`) |

Dynamic per-drive variables: for every ready fixed drive, letter =
`drive.Name.TrimEnd('\\','/').TrimEnd(':').ToLowerInvariant()` (`:1301`),
`VariableHub` writes `disk.<letter>.used_bytes|free_bytes|total_bytes|usage|label|filesystem`
(`:1303-1308`). The catalog advertises 17 templates per letter at
`VariableCatalog.AddDynamicDriveDefinitions` (`VariableCatalog.cs:783-812`) and the
advanced provider fills the rest: `read_bps|write_bps|io_bps|active_percent|queue_length`
(`AdvancedVariableProvider.cs:310-314`), `partition_count` (`:326-329`),
`health|temperature|power_on_hours|trim_status|smart_status` (`:339-343`).
Advanced dynamic keys are recognized by regex
`^disk\.[a-z]\.(read_bps|write_bps|io_bps|active_percent|queue_length|health|temperature|power_on_hours|trim_status|smart_status|partition_count)$`
(`:125-128`). `disk.<letter>.total_written_bytes` is intentionally omitted (`:345`).

#### 1.2.9 系统 System — 38 keys

Producers: `VH.Clock` (`VariableHub.cs:189-273`) — clock/system is read synchronously
(not on a background thread) specifically to avoid second-skips (`:123-124`);
advanced `AVP.AddSystemAdvanced` (`AdvancedVariableProvider.cs:348-370`) +
`EnsureSystemStatic` (`:843-917`, 2 min cache) + `ReadSecuritySnapshot` (`:1080-1156`) +
`EnsureHardware` (`:654-715`).

| Key | Name (zh) | Unit/Type | Derivation |
| --- | --- | --- | --- |
| `system.time` | 当前时间 | text | `now.ToString("HH:mm:ss", InvariantCulture)` (`:198`) |
| `system.date` | 当前日期 | text | `yyyy-MM-dd` (`:199`) |
| `system.datetime` | 当前日期时间 | text | `yyyy-MM-dd HH:mm:ss` (`:200`) |
| `system.uptime_seconds` | 系统运行时间 | s | `Environment.TickCount64/1000` (`:192,201`) |
| `system.uptime_text` | 系统运行时间文字 | text | `FormatDurationLong` (`:202,1570-1577`) |
| `system.boot_time` | 系统启动时间 | text | now−uptime, `yyyy-MM-dd HH:mm:ss` (`:203`) |
| `system.machine_name` | 计算机名称 | text | `Environment.MachineName` (`:204`) |
| `system.host_name` | 主机名 | text | `Dns.GetHostName()`, fallback machine name (`:205`) |
| `system.user_name` | 当前用户名 | text | `Environment.UserName` (`:206`) |
| `system.user_domain` | 当前用户域 | text | `Environment.UserDomainName` (`:207`) |
| `system.os_description` | 操作系统名称 | text | `RuntimeInformation.OSDescription.Trim()` (`:208`) |
| `system.os_version` | 操作系统版本 | text | `Environment.OSVersion.Version.ToString()` (`:209`) |
| `system.os_build` | Windows Build | int | `Version.Build` (`:210`) |
| `system.os_architecture` | 操作系统架构 | text | `RuntimeInformation.OSArchitecture` (`:211`) |
| `system.process_architecture` | 应用进程架构 | text | `RuntimeInformation.ProcessArchitecture` (`:212`) |
| `system.framework_version` | .NET 版本 | text | `RuntimeInformation.FrameworkDescription` (`:213`) |
| `system.processor_count` | 系统逻辑处理器数 | int 个 | `Environment.ProcessorCount` (`:214`) |
| `system.timezone_id` | 时区 ID | text | `TimeZoneInfo.Local.Id` (`:215`) |
| `system.timezone_name` | 时区名称 | text | `TimeZoneInfo.Local.DisplayName` (`:216`) |
| `system.utc_offset_hours` | UTC 时差 | 小时 | `tz.GetUtcOffset(now).TotalHours` (`:217`) |
| `system.culture` | 系统区域语言 | text | `CultureInfo.CurrentCulture.Name` (`:218`) |
| `system.power_plan` | 电源计划 GUID | text | advanced; GUID regex from `powercfg /GETACTIVESCHEME` (`AVP:850-854`) |
| `system.power_plan_name` | 电源计划名称 | text | advanced; `(...)` tail of that output (`:855-856`) |
| `system.bios_version` | BIOS 版本 | text | advanced; `Win32_BIOS.SMBIOSBIOSVersion` (`:862-865`) |
| `system.bios_date` | BIOS 日期 | text | advanced; `Win32_BIOS.ReleaseDate` → `yyyy-MM-dd` (`:866`) |
| `system.motherboard_manufacturer` | 主板制造商 | text | advanced; `Win32_BaseBoard.Manufacturer` (`:869-872`) |
| `system.motherboard_model` | 主板型号 | text | advanced; `Win32_BaseBoard.Product` (`:873`) |
| `system.update_pending` | Windows 更新待处理 | bool | advanced; RebootRequired or Component Based Servicing RebootPending, or WU count>0 (`:897-898,915`) |
| `system.update_last_installed` | 最近安装更新日期 | text | advanced; max `Win32_QuickFixEngineering.InstalledOn` → `yyyy-MM-dd` (`:901-908`) |
| `system.defender_status` | Microsoft Defender 状态 | text | advanced; from security snapshot (`:912`, `:1085-1089`) |
| `system.firewall_status` | Windows 防火墙状态 | text | advanced; from security snapshot (`:913`, `:1101-1112`) |
| `system.bitlocker_status` | 系统盘 BitLocker 状态 | text | advanced; `Win32_EncryptableVolume.ProtectionStatus` (`:914`, `:1116-1125`) |
| `system.hyper_v_status` | Hyper-V 状态 | bool | advanced; `Win32_OptionalFeature Microsoft-Hyper-V-All InstallState==1` (`:880-881,359`) |
| `system.wsl_status` | WSL 状态 | bool | advanced; Lxss subkeys >0 or `Microsoft-Windows-Subsystem-Linux` installed (`:886-894,360`) |
| `system.wsl_distro_count` | WSL 发行版数量 | int 个 | advanced; HKCU `...\Lxss` subkey count (`:887,361`) |
| `system.motherboard_temperature` | 主板最高温度 | °C | advanced; LHM motherboard max Temperature (`:710,367`) |
| `system.fan_speed` | 风扇最高转速 | RPM | advanced; max `SensorType.Fan` (`:711,368`) |
| `system.fan_speed_percent` | 风扇控制百分比 | % | advanced; max `SensorType.Control` (`:712,369`) |

#### 1.2.10 时间 Time — 43 keys

Producers: clock/derived progress in `VH.Clock` (`VariableHub.cs:221-272`); world clocks in
`AVP.AddWorldTime` (`AdvancedVariableProvider.cs:451-457`); profile-scoped display/target in
`HPR.BuildEffectiveVariables` (`HudProfileRenderer.cs:96-135`).
Week start = Monday (`:225`), month/year boundaries at 00:00 (`:227-230`), all progress values
`Math.Clamp(...,0,100)` (`:262-271`).

| Key | Name (zh) | Unit/Type | Derivation |
| --- | --- | --- | --- |
| `time.current` | 当前时间（24 小时） | text | `HH:mm:ss` invariant (`:239`) |
| `time.current_12h` | 当前时间（12 小时） | text | `hh:mm:ss tt` current culture (`:240`) |
| `time.date` | 当前日期 | text | `yyyy-MM-dd` (`:241`) |
| `time.datetime` | 当前日期时间 | text | `yyyy-MM-dd HH:mm:ss` (`:242`) |
| `time.iso` | ISO 日期时间 | text | `DateTimeOffset.ToString("O")` (`:243`) |
| `time.year` `time.month` `time.day` | 年/月/日 | int | `now.*` (`:244-247`) |
| `time.month_name` | 月份名称 | text | `DateTimeFormat.GetMonthName` (`:246`) |
| `time.day_of_week` | 星期（中文） | text | `DayOfWeekZh`, English when locale is English (`:248`, `:1556-1568`) |
| `time.day_of_week_en` | 星期（英文） | text | `DayOfWeek.ToString()` (`:249`) |
| `time.day_of_year` | 一年中的第几天 | int | `now.DayOfYear` (`:250`) |
| `time.week_of_year` | 周序号 | int | `ISOWeek.GetWeekOfYear` (`:251`) |
| `time.hour` `time.minute` `time.second` `time.millisecond` | 时/分/秒/毫秒 | int | `now.*` (`:252-255`) |
| `time.is_weekend` | 是否周末 | bool | Saturday/Sunday (`:256`) |
| `time.unix_seconds` | Unix 时间戳（秒） | s | `ToUnixTimeSeconds` (`:257`) |
| `time.unix_milliseconds` | Unix 时间戳（毫秒） | ms | `ToUnixTimeMilliseconds` (`:258`) |
| `time.day.progress` | 当天进程 | % | elapsed seconds / 86400 × 100 (`:260-262`) |
| `time.day.elapsed_seconds` | 当天已过时间 | s | `now.TimeOfDay.TotalSeconds` (`:260`) |
| `time.day.remaining_seconds` | 当天剩余时间 | s | `max(0, 86400−elapsed)` (`:261`) |
| `time.week.progress` | 本周进程 | % | Monday-based week (`:225-232,265`) |
| `time.week.elapsed_seconds` | 本周已过时间 | s | `(now−weekStart)` (`:231,263`) |
| `time.week.remaining_seconds` | 本周剩余时间 | s | `weekTotal−elapsed` (`:264`) |
| `time.month.progress` | 本月进程 | % | `monthStart`→`monthEnd` (`:227-228,268`) |
| `time.month.elapsed_seconds` | 本月已过时间 | s | `(now−monthStart)` (`:233,266`) |
| `time.month.remaining_seconds` | 本月剩余时间 | s | `monthTotal−elapsed` (`:267`) |
| `time.year.progress` | 本年进程 | % | `yearStart`→`yearEnd` (`:229-230,271`) |
| `time.year.elapsed_seconds` | 本年已过时间 | s | `(now−yearStart)` (`:235,269`) |
| `time.year.remaining_seconds` | 本年剩余时间 | s | `yearTotal−elapsed` (`:270`) |
| `time.world.nyc` | 纽约时间 | text | `Eastern Standard Time`, `yyyy-MM-dd HH:mm:ss` (`AVP:453,1177-1180`) |
| `time.world.london` | 伦敦时间 | text | `GMT Standard Time` (`:454`) |
| `time.world.tokyo` | 东京时间 | text | `Tokyo Standard Time` (`:455`) |
| `time.world.beijing` | 北京时间 | text | `China Standard Time` (`:456`) |
| `time.display.progress` | 时间方案显示进度 | % | profile: target mode → `remaining_percent`, else `dayProgress` (`HPR:123-134`) |
| `time.display.status_text` | 时间方案状态文字 | text | target mode → `剩余{0}%`/`Left {0}%`, else `"{dayProgress:0}%"` (`HPR:126-133`) |
| `time.target.value` | 目标时间 | text | `TimeTarget` normalized `hh\:mm\:ss`, default 10:00:00 (`HPR:106,115`, `ParseTime:230-243`) |
| `time.target.remaining_seconds` | 距离目标时间 | s | seconds to next occurrence of the daily target (`HPR:107-116`) |
| `time.target.remaining_percent` | 目标时间剩余比例 | % | `clamp(remaining/86400*100,0,100)` (`HPR:112,117`) |
| `time.target.progress` | 目标时间已过比例 | % | `100 − remaining_percent` (`HPR:113,118`) |
| `time.target.remaining_text` | 目标时间剩余文字 | text | `剩余{n}%`/`Left {n}%` (`HPR:119-121`) |

#### 1.2.11 DeepSeek API — 21 keys

Producers: period state `VH.AddDeepSeekPeriod` (`VariableHub.cs:1400-1441`) with
`GetDeepSeekPeriodState` (`:1382-1398`), `ParseWindows` (`:1654-1667`),
`FindNextTransition` (`:1669-1685`), `FindCurrentSegmentStart` (`:1687-1707`); balance
`VH.AddDeepSeekBalanceAsync` (`:1322-1363`) + `CopyDeepSeek` (`:1365-1374`); latency
advanced field written by `CopyDeepSeek` (`:1373`).

* Endpoint: `GET https://api.deepseek.com/user/balance` with
  `Authorization: Bearer <apiKey>` (`:1335-1336`).
* HTTP timeout 8 s (`HttpClient` at `:23`).
* Response parsing: `is_available` must be `JsonValueKind.True` (`:1343`); only
  `balance_infos` entries with `currency == "CNY"` are summed (`:1346-1356`);
  fields `total_balance`, `granted_balance`, `topped_up_balance` accept number or string
  (`ReadJsonNumber` `:1630-1638`).
* Cache: 1 minute (`:1359`). No API key ⇒ the whole balance block is **not written at all**
  (`:1324-1325`).
* Period basis: Beijing time via `TimeZoneInfo.FindSystemTimeZoneById("China Standard Time")`,
  fallback `UtcNow+8h` (`:1385-1392`). Peak = weekday (Mon–Fri) and inside one of the
  configured windows (`:1395-1396`). Default windows `09:00-12:00;14:00-18:00`
  (`Models.cs:96`).

| Key | Name (zh) | Unit/Type | Derivation |
| --- | --- | --- | --- |
| `deepseek.balance` | DeepSeek 总余额 | CNY | Σ CNY `total_balance` (`:1353,1369`) |
| `deepseek.balance_text` | DeepSeek 总余额文字 | text | `$"¥{Total:0.00}"` (`:1370`) |
| `deepseek.granted_balance` | DeepSeek 赠送余额 | CNY | Σ `granted_balance` (`:1354,1371`) |
| `deepseek.topped_up_balance` | DeepSeek 充值余额 | CNY | Σ `topped_up_balance` (`:1355,1372`) |
| `deepseek.available` | DeepSeek API 可用状态 | bool | `is_available` (`:1343,1367`) |
| `deepseek.available_text` | DeepSeek API 可用状态文字 | text | 可用/Available or 不可用/Unavailable (`:1368`) |
| `deepseek.period.name` | 当前时段（英文） | text | `PEAK` / `OFF-PEAK` (`:1410,1418`) |
| `deepseek.period.name_zh` | 当前时段（中文） | text | `高峰`/`低谷`, but `PEAK`/`OFF-PEAK` when UI language is English (`:1409,1419`) |
| `deepseek.period.is_peak` | 是否高峰 | bool | peak flag (`:1420`) |
| `deepseek.period.is_off_peak` | 是否低谷 | bool | `!peak` (`:1421`) |
| `deepseek.period.remaining_seconds` | 距离时段切换 | s | `max(0,(next−now).TotalSeconds)` (`:1404,1422`) |
| `deepseek.period.remaining_text` | 时段剩余文字 | text | `"{高峰\|低谷}时段剩余HH:MM:SS"` / `"{Peak\|Off-peak} left HH:MM:SS"` (`:1411-1413,1423`) |
| `deepseek.period.progress` | 当前时段已过进度 | % | `clamp((now−segmentStart)/(next−segmentStart)*100,0,100)` (`:1405-1407,1424`) |
| `deepseek.period.progress_text` | 时段已过文字 | text | `"{高峰\|低谷}已过{n}%"` / `"{Peak\|Off-peak} {n}%"` (`:1414-1416,1425`) |
| `deepseek.period.next_switch_time` | 下次切换时间（北京时间） | text | `HH:mm:ss` at UTC+08:00 (`:1430-1435`) |
| `deepseek.period.next_switch_datetime` | 下次切换日期时间（北京时间） | text | `yyyy-MM-dd HH:mm:ss` at UTC+08:00 (`:1436`) |
| `deepseek.period.next_switch_time_local` | 下次切换时间（本地） | text | same instant in local tz, `HH:mm:ss` (`:1433,1437`) |
| `deepseek.period.next_switch_datetime_local` | 下次切换日期时间（本地） | text | local `yyyy-MM-dd HH:mm:ss` (`:1438`) |
| `deepseek.period.timezone` | DeepSeek 峰谷基准时区 | text | 北京时间 (UTC+08:00) / Beijing Time (UTC+08:00) (`:1439`) |
| `deepseek.period.local_timezone` | 用户本地时区 | text | `"{TimeZoneInfo.Local.Id} (UTC±HH:MM)"` (`:1440`, `FormatUtcOffset:1647-1652`) |
| `deepseek.api.latency_ms` | DeepSeek API 延迟 | ms | stopwatch around the balance HTTP call (`:1337-1339,1373`) |

Remaining-window fallbacks: if no transition can be found within 8 days the provider returns
`now.AddHours(1)` (`:1684`); if the current off-peak segment start cannot be found it returns
`now.AddHours(-1)` (`:1706`).

#### 1.2.12 ECP 应用 App — 16 keys

Producers: `VH.App` (`VariableHub.cs:275-298`) using `Process.GetCurrentProcess()` and
`Assembly.GetName().Version?.ToString(3) ?? "0.1.0"` (`:281`); advanced `AVP.AddAppAdvanced`
(`AdvancedVariableProvider.cs:402-424`).

| Key | Name (zh) | Unit/Type | Derivation |
| --- | --- | --- | --- |
| `app.name` | 应用进程名称 | text | `ProcessName` (`:284`) |
| `app.version` | 应用版本 | text | assembly version 3-part (`:285`) |
| `app.pid` | 应用 PID | int | `p.Id` (`:286`) |
| `app.start_time` | 应用启动时间 | text | `yyyy-MM-dd HH:mm:ss` (`:287`) |
| `app.uptime_seconds` | 应用运行时间 | s | now−start (`:283,288`) |
| `app.uptime_text` | 应用运行时间文字 | text | `FormatDurationLong` (`:289`) |
| `app.working_set_bytes` | 应用工作集 | Byte | `WorkingSet64` (`:290`) |
| `app.private_memory_bytes` | 应用专用内存 | Byte | `PrivateMemorySize64` (`:291`) |
| `app.virtual_memory_bytes` | 应用虚拟内存 | Byte | `VirtualMemorySize64` (`:292`) |
| `app.thread_count` | 应用线程数 | int 个 | `Threads.Count` (`:293`) |
| `app.handle_count` | 应用句柄数 | int 个 | `HandleCount` (`:294`) |
| `app.cpu_time_seconds` | 应用累计 CPU 时间 | s | `TotalProcessorTime.TotalSeconds` (`:295`) |
| `app.theme` | 应用主题 | text | 深色/Dark (hard-coded) (`AVP:404`) |
| `app.preset_name` | 当前方案名称 | text | `BuiltInProfileLocalization.DisplayName(activeProfile)` (`:405-408`) |
| `app.active_profile` | 当前方案 ID | text | `settings.ActiveProfileId` (`:405`) |
| `app.gpu_usage` | 本程序 GPU 使用率 | % | advanced; Σ GPUEngine rows matching `pid_<currentPid>`, clamp 0–100 (`:414-421`) |

#### 1.2.13 显示器 Display — 15 keys

Producers: `VH.Display` (`VariableHub.cs:300-317`) via `GetSystemMetrics`
indices 0/1/76/77/78/79/80 and `GetDpiForSystem` (`:1706-1724`); advanced
`AVP.AddDisplayAdvanced` (`AdvancedVariableProvider.cs:426-449`) via
`EnumDisplayDevices`/`EnumDisplaySettings` (`:1158-1175`).

| Key | Name (zh) | Unit/Type | Derivation |
| --- | --- | --- | --- |
| `display.primary_width_px` | 主显示器宽度 | px | `GetSystemMetrics(0)` (`:306`) |
| `display.primary_height_px` | 主显示器高度 | px | `GetSystemMetrics(1)` (`:307`) |
| `display.virtual_x_px` | 虚拟桌面 X 起点 | px | `GetSystemMetrics(76)` (`:308`) |
| `display.virtual_y_px` | 虚拟桌面 Y 起点 | px | `GetSystemMetrics(77)` (`:309`) |
| `display.virtual_width_px` | 虚拟桌面宽度 | px | `GetSystemMetrics(78)` (`:310`) |
| `display.virtual_height_px` | 虚拟桌面高度 | px | `GetSystemMetrics(79)` (`:311`) |
| `display.monitor_count` | 显示器数量 | int 台 | `GetSystemMetrics(80)` (`:312`) |
| `display.system_dpi` | 系统 DPI | int DPI | `GetDpiForSystem()`, fallback 96 (`:304-305,313`) |
| `display.scale_percent` | 系统缩放比例 | % | `dpi/96*100` (`:314`) |
| `display.primary.name` | 主显示设备名称 | text | first primary `DISPLAY_DEVICE.DeviceString` (`AVP:433-436`) |
| `display.primary.color_depth` | 主显示器色深 | int bit | `dmBitsPerPel` (`:434,1169`) |
| `display.primary.refresh_rate` | 主显示器刷新率 | int Hz | `dmDisplayFrequency` (`:435,1169`) |
| `display.secondary.name` | 第二显示器名称 | text | second active device (`:440`) |
| `display.secondary.resolution` | 第二显示器分辨率 | text | `"{w}×{h}"` (`:442`) |
| `display.secondary.refresh_rate` | 第二显示器刷新率 | int Hz | second device frequency (`:443`) |

`display.secondary.*` is written only when ≥2 active display devices exist (`:438-444`).

#### 1.2.14 进程 Process — 12 keys

Producer: `AVP.AddProcessAdvanced` (`AdvancedVariableProvider.cs:372-400`) using
`EnsureProcesses` (`:919-981`, 1 s cache). Process CPU is normalized by
`Environment.ProcessorCount` (`:941`); GPU per-process is the sum of `pid_<n>` engine rows
capped at 100 (`:971`).

| Key | Name (zh) | Unit/Type | Derivation |
| --- | --- | --- | --- |
| `process.background.count` | 后台进程数量 | int 个 | processes with `MainWindowHandle == IntPtr.Zero` (`:933-934`) |
| `process.top_cpu.name` | CPU 占用最高进程 | text | max Δ CPU time/process (`:937-942`) |
| `process.top_cpu.pid` | CPU 最高进程 PID | int | same (`:942`) |
| `process.top_cpu.usage` | 最高进程 CPU 使用率 | % | `Δcpu/wall/ProcessorCount*100` (`:940-942`) |
| `process.top_memory.name` | 内存占用最高进程 | text | max `WorkingSet64` (`:935-936`) |
| `process.top_memory.pid` | 内存最高进程 PID | int | same (`:936`) |
| `process.top_memory.usage` | 最高进程内存占用 | Byte | that working set (`:386`, `:936`) |
| `process.top_disk.name` | 磁盘 I/O 最高进程 | text | max `PerfProc_Process.IODataBytesPersec` (`:954-958`) |
| `process.top_disk.pid` | 磁盘 I/O 最高进程 PID | int | same (`:958`) |
| `process.top_disk.usage` | 最高进程磁盘 I/O | Byte/s | that I/O rate (`:392,958`) |
| `process.gpu.top.name` | GPU 占用最高进程 | text | process name of max Σ engine util (`:973-976`) |
| `process.gpu.top.usage` | 最高进程 GPU 使用率 | % | capped sum (`:971,975,397`) |

Per-process network attribution is intentionally not exposed (`:399`).

#### 1.2.15 剪贴板 Clipboard — 7 keys

Producer: `AVP.AddClipboard` (`AdvancedVariableProvider.cs:459-491`); Windows-only
(`:461`). Sequence number change sets `_clipboardLastUpdated` (`:464-469`).

| Key | Name (zh) | Unit/Type | Derivation |
| --- | --- | --- | --- |
| `clipboard.has_text` | 剪贴板含文本 | bool | `IsClipboardFormatAvailable(CF_UNICODETEXT=13)` (`:471`) |
| `clipboard.text_length` | 剪贴板文本长度 | int 字符 | length of read Unicode text, else 0 (`:475-484`) |
| `clipboard.preview` | 剪贴板文本预览 | text | whitespace-collapsed text, first 80 chars + `…` (`:478-479`) |
| `clipboard.has_image` | 剪贴板含图像 | bool | `CF_DIB(8)` or `CF_BITMAP(2)` (`:472`) |
| `clipboard.image_size` | 剪贴板图像尺寸 | text | `"{w}×{h}"` from DIB header, absolute height (`:487-488,1267-1271`) |
| `clipboard.file_count` | 剪贴板文件数量 | int 个 | `DragQueryFile(CF_HDROP=15, 0xFFFFFFFF, …)` (`:473,1272-1276`) |
| `clipboard.last_updated` | 剪贴板最后变化时间 | text | `yyyy-MM-dd HH:mm:ss` of observed sequence change, `""` if never (`:470`) |

#### 1.2.16 USB / 外设 — 9 keys

Producer: `AVP.AddUsbAndPeripherals` (`AdvancedVariableProvider.cs:493-506`) +
`EnsureUsb` (`:1021-1070`, 30 s cache).

| Key | Name (zh) | Unit/Type | Derivation |
| --- | --- | --- | --- |
| `usb.device.count` | USB 设备数量 | int 个 | `Win32_PnPEntity WHERE PNPDeviceID LIKE 'USB%'` count (`:1029-1035`) |
| `usb.device.list` | USB 设备列表 | text | distinct names, max 30, `", "` joined (`:1036`) |
| `usb.storage.count` | 可移动存储数量 | int 个 | ready `DriveType.Removable` count (`:1042-1043`) |
| `usb.storage.list` | 可移动存储列表 | text | drive roots joined (`:1043`) |
| `peripheral.mouse.name` | 鼠标名称 | text | first non-empty `Win32_PointingDevice.Name` (`:1048-1049`) |
| `peripheral.keyboard.name` | 键盘名称 | text | first non-empty `Win32_Keyboard.Name` (`:1050-1051`) |
| `peripheral.gamepad.count` | XInput 手柄数量 | int 个 | `XInputGetState(0..3)==0` count (`:1056-1060`) |
| `peripheral.gamepad.name` | 手柄名称 | text | `"XInput Controller {i+1}"` (`:1061`) |
| `peripheral.gamepad.battery` | 手柄电量 | % | `XInputGetBatteryInformation` → 0/25/60/100, −1 = unknown (not written) (`:1062-1063,1260,504`) |

HID battery/DPI/backlight and headset battery are intentionally not fabricated (`:505`).

#### 1.2.17 开发者工具 Developer — 18 keys

Producer: `AVP.AddDeveloper` (`AdvancedVariableProvider.cs:508-529`) + `EnsureDeveloper`
(`:983-1019`, 15 s cache). Command helper `Run(file,args,timeout,cwd)` (`:1245-1253`);
`CommandExists` searches PATH plus `%WINDIR%\System32\nvidia-smi.exe` (`:1244`).

| Key | Name (zh) | Unit/Type | Derivation |
| --- | --- | --- | --- |
| `dev.docker.running` | Docker 是否运行 | bool | process names `Docker Desktop`, `com.docker.backend`, `dockerd` (`:988`) |
| `dev.docker.containers` | Docker 运行容器数 | int 个 | `docker ps -q` line count; not written if Docker absent (`:989-991,512`) |
| `dev.docker.images` | Docker 镜像数 | int 个 | distinct `docker images -q` lines (`:992,513`) |
| `dev.wsl.running` | WSL 是否运行 | bool | processes `wsl`, `wslhost`, `vmmemWSL`, `vmmem` (`:994`) |
| `dev.wsl.distro` | WSL 发行版列表 | text | `wsl -l -q` non-empty lines joined `", "` (`:995-998`) |
| `dev.wsl.memory_usage` | WSL 内存占用 | Byte | Σ WorkingSet of `vmmemWSL`/`vmmem` (`:1000`) |
| `dev.git.branch` | Git 当前分支 | text | `git rev-parse --abbrev-ref HEAD` in CWD when `<cwd>\.git` exists (`:1002-1004`) |
| `dev.git.status` | Git 工作区状态 | text | `clean` or `"{n} changed"` from `git status --porcelain` (`:1005-1006`) |
| `dev.git.last_commit` | Git 最近提交 | text | `git log -1 --pretty=%h%x20%s` (`:1007`) |
| `dev.node.version` | Node.js 版本 | text | `node --version` first line (`:1009`) |
| `dev.python.version` | Python 版本 | text | `python --version` else `py --version` (`:1010`) |
| `dev.java.version` | Java 版本 | text | `java -version` first line (`:1011`) |
| `dev.golang.version` | Go 版本 | text | `go version` (`:1012`) |
| `dev.rust.version` | Rust 版本 | text | `rustc --version` (`:1013`) |
| `dev.vscode.running` | VS Code 是否运行 | bool | processes `Code`, `Code - Insiders` (`:1014`) |
| `dev.terminal.running` | 终端是否运行 | bool | `WindowsTerminal`, `wt`, `pwsh`, `powershell`, `cmd` (`:1015`) |
| `dev.ide.running` | IDE 是否运行 | bool | VS Code or `devenv`, `idea64`, `rider64`, `pycharm64` (`:1016`) |
| `dev.llm.local_status` | 本地 LLM 状态 | text | `ollama`, `lmstudio`, `LM Studio`, `llama-server` → 运行中/Running else 未运行/Not running (`:1017,528`) |

#### 1.2.18 安全 Security — 16 keys

Producers: `AVP.AddSecurity` (`AdvancedVariableProvider.cs:531-550`) and
`ReadSecuritySnapshot` (`:1080-1156`, 5 min cache); `security.vpn.active` /
`security.proxy.enabled` read the NIC/proxy snapshot (`:548-549`).

| Key | Name (zh) | Unit/Type | Derivation |
| --- | --- | --- | --- |
| `security.defender.status` | Defender 状态 | text | 已启用/Enabled, 实时保护关闭/Real-time protection off, 未启用/Disabled (`:1088-1089`) |
| `security.defender.last_scan` | Defender 最近扫描 | text | max `QuickScanEndTime`/`FullScanEndTime` → `yyyy-MM-dd HH:mm:ss` (`:1090-1092`) |
| `security.defender.threats` | Defender 当前威胁数 | int 项 | count of `MSFT_MpThreat` rows; −1 means unknown/not written (`:1095-1096,536`) |
| `security.firewall.status` | 防火墙状态 | text | any enabled active profile → 已启用/Enabled else 已关闭/Off (`:1110-1112`) |
| `security.firewall.profile` | 防火墙配置文件 | text | 域/专用/公用 (Domain/Private/Public) joined `/` from `CurrentProfileTypes` bits 1/2/4 (`:1105-1112`) |
| `security.bitlocker.status` | BitLocker 状态 | text | `ProtectionStatus==1` → 已保护/Protected else 未保护/Not protected (`:1122`) |
| `security.bitlocker.encryption_percent` | BitLocker 加密进度 | % | `EncryptionPercentage` if ≥0 (`:1123`) |
| `security.secure_boot` | Secure Boot | bool | HKLM `SYSTEM\CurrentControlSet\Control\SecureBoot\State\UEFISecureBootEnabled == 1` (`:1128`) |
| `security.tpm.present` | TPM 存在 | bool | `Win32_Tpm` enumerable (`:1131-1132`) |
| `security.tpm.version` | TPM 版本 | text | `Win32_Tpm.SpecVersion` first comma-part (`:1132`) |
| `security.uac_status` | UAC 状态 | bool | HKLM `...\Policies\System\EnableLUA != 0` (`:1135`) |
| `security.smartscreen_status` | SmartScreen 状态 | text | HKLM `...\Explorer\SmartScreenEnabled` else 未知/Unknown (`:1138`) |
| `security.windows_update.status` | Windows Update 状态 | text | 等待重启/Restart pending, 正常/OK, 有可用更新/Updates available (`:1143-1151`) |
| `security.windows_update.pending_count` | 待安装更新数量 | int 项 | COM `Microsoft.Update.Session` search `IsInstalled=0 and IsHidden=0`; −1 unknown (`:1146-1150,1082`) |
| `security.vpn.active` | VPN 活动状态 | bool | `network.vpn_status` same source (`:548`, `:751`) |
| `security.proxy.enabled` | 代理启用状态 | bool | `network.proxy_status` same source (`:549`, `:761`) |

#### 1.2.19 自定义数据 custom.* (not in the 429 static catalog)

Dynamic keys are generated at runtime (`VariableHub.cs:1443-1491`):
`custom.{Sanitize(source.Name)}.{Sanitize(field.Variable)}` (`:1486`), where `Sanitize`
lowercases and replaces every non-`[a-z0-9_]` char with `_` (`:1585-1586`).
Source config model = `CustomHttpSource` (`Models.cs:73-81`) with fields
`Name`, `Enabled`, `Url`, `RefreshSeconds` (clamped 5..86400 at `:1471`), `Headers`,
`Fields`; mapping = `HttpFieldMapping{ Variable, JsonPath }` (`Models.cs:67-71`).
Headers support `${env:NAME}` expansion (`:1579-1583`). JSON path syntax supports dotted
properties and `[index]` (`:1588-1618`); `"$"`, `"."` or empty = root (`:1591`).
The catalog synthesizes a definition via `VariableCatalog.CreateCustom(key)`
(`VariableCatalog.cs:773-781`, category `自定义数据`, type `动态`, recommended
formats `"0 / 0.0 / gb:1 / speed / duration 等"`).
CI fixture expectations: `{"data":{"items":[{"value":42}]}}` with path `data.items[0].value`
→ `42`; a missing path must still produce a value (state string) rather than `--`
(`Tests/LinuxGuiAudit.cs:135-137`).

### 1.3 Which keys exist for a given render (request gating)

`SnapshotAsync(settings, requestedVariables, gpuAdapterId, pingTarget, probeProtocol, probePort, ct)`
(`VariableHub.cs:101-184`) only collects what the caller requested:

* `requestedVariables == null` ⇒ collect everything (`:117-119`).
* Prefix gates: `system.`/`time.`/`app.`/`display.` read synchronously (`:124-133`);
  `cpu.`/`memory.`/`battery.`/`network.`/`disk.`/`gpu.` on a background `Task.Run`
  (`:136-164`); `ping.` or `probe.` triggers probing (`:142,166-167`).
* Advanced collectors run only when some requested key of that prefix is a known advanced
  key (or an advanced dynamic disk key) (`AdvancedVariableProvider.cs:78-102,125-128`).
* `deepseek.period.` gates the period block; the balance HTTP call is skipped when **only**
  period keys were requested (`:173-178`, `NeedsOnlyPeriodVariables` `:1505-1510`).
* `custom.` gates HTTP collection (`:180-181,1449-1453`).
* Language change invalidates probe-state history (`:110-115`) and advanced localized caches
  (`AdvancedVariableProvider.cs:108-123`).

Requested key set in practice comes from `HudProfileRenderer.GetRequiredVariables`
(`HudProfileRenderer.cs:35-72`): template keys + expression keys + color-rule variables,
plus `time.current` for any `time.*` or time profile and `network.download_bps` /
`network.upload_bps` for any `network.*` or the network built-in.

### 1.4 Unavailable / unsupported / missing values

Two different mechanisms exist. Do not conflate them.

1. **Key absent or value `null` → the literal string `--`** in every template
   (`TemplateEngine.cs:24,26,33`). This is the universal "no data" marker and the Android port
   must reproduce it exactly. Catalog variables on this machine render `--` if the collector
   could not produce them.
2. **Advanced collectors omit the key entirely** rather than writing zeros
   (`AdvancedVariableProvider.cs:23-27,1188-1192`). `PutIfNumber` skips `null`, `NaN`,
   `Infinity` (`:1188-1191`); `PutText` skips null/whitespace (`:1192`). Core collectors
   *do* write zeros in their catch blocks (e.g. `memory.cache_bytes = 0` at
   `VariableHub.cs:489-491`, network fallback at `:1212-1224`).
3. **Probe failures are values, not omissions**: `probe.latency_ms` becomes `999`, loss can
   be 100%, `probe.error` carries the raw status (`VariableHub.cs:884-914`).
4. **Balance without an API key is absent** (no `deepseek.balance*` keys at all,
   `VariableHub.cs:1324-1325`) ⇒ templates render `--`.
   The Linux acceptance suite documents the intended cross-platform behaviour differently
   (show a configuration state string instead of `--`): `Tests/LinuxGuiAudit.cs:122-123`
   asserts `missingKey["deepseek.balance"] is string`. On Windows the key is simply missing.
   **This is a behavioural divergence to decide explicitly for Android.**
5. **HTTP failures must not become fake data** — the Linux suite asserts the keys still hold
   status strings (`Tests/LinuxGuiAudit.cs:138-141`); the Windows `AddCustomHttpAsync`
   instead `continue`s and writes nothing on failure (`VariableHub.cs:1474-1477`).
6. `TemplateEngine.Render` never throws for a bad key; it substitutes `--`.

### 1.5 Refresh cadence summary (Windows)

| Data | Cadence | Reference |
| --- | --- | --- |
| HUD runtime timer | 100 ms | `CustomHudRuntime.cs:47` |
| Persistent non-clock refresh | 1 s | `CustomHudRuntime.cs:514` |
| Clock-accurate profiles (`NeedsSecondAccurateClock`) | once per wall-clock second | `CustomHudRuntime.cs:74-79,445-452,514-516` |
| Transient live refresh | 50 ms poll, apply on second change | `HudWindow.axaml.cs:171-177` |
| CPU usage | every snapshot (delta since last) | `VariableHub.cs:324-346` |
| CPU static info | 10 min | `:355` |
| CPU live frequency | 750 ms | `:395` |
| Battery status | 5 s | `:502` |
| Battery static (full/design/cycles) | 10 min | `:528` |
| Disk volume info | 5 s | `:1234` |
| Disk perf counters | 900 ms | `:1252` |
| GPU info/adapter | 2 min | `:649` |
| GPU usage/memory | 700 ms | `:666` |
| Probe per state key | ≥800 ms | `:829` |
| Probe history | last 20 samples | `:850-851` |
| DeepSeek balance | 1 min cache | `:1359` |
| Public IPv4/IPv6 | 5 min | `AdvancedVariableProvider.cs:556,561` |
| perf OS / system calls | 900 ms | `:571` |
| memory static | 10 min | `:616` |
| hardware (LHM) | 900 ms | `:657` |
| nvidia-smi | 5 s | `:720` |
| network static (VPN/proxy/Wi-Fi/DNS) | 3 s | `:742` |
| disk advanced (health/TRIM) | 60 s | `:806` |
| system static | 2 min | `:846` |
| process snapshot | 1 s | `:924` |
| developer snapshot | 15 s | `:986` |
| USB snapshot | 30 s | `:1024` |
| security snapshot | 5 min | `:1075` |
| clipboard | every advanced request (sequence-number diff) | `:464-469` |
| app CPU averages | sample history ≤15.5 min | `:180-181` |
| GPU adapter catalog | 5 min | `GpuAdapterCatalog.cs:72` |

---

## 2. Formatting system

### 2.1 Token grammar

`Customization/TemplateEngine.cs:11-12`:

```csharp
private static readonly Regex TokenRegex = new(@"\{(?<key>[a-zA-Z0-9_.-]+)(?:\|(?<fmt>[^}]+))?\}", RegexOptions.Compiled);
private static readonly Regex ExpressionTokenRegex = new(@"\{=(?<body>[^{}]*)\}", RegexOptions.Compiled);
```

* Key charset: `[a-zA-Z0-9_.-]+` — **no** `/`, no spaces, no `$`.
* Format charset after `|`: `[^}]+` — anything except `}`, including spaces, `|`, `:`.
* Optional format; `{key}` is legal.
* Expression token body: `[^{}]*` — **cannot contain braces**, so no nested/templated braces inside a `{= … }` token.

Render order (`:14-36`): all `{= …}` expression tokens are replaced first, then remaining
`{key|fmt}` tokens. **Consequence:** text produced by an expression is re-scanned for
`{key}` tokens, so an expression returning the literal string `{cpu.usage}` will be
substituted afterwards. Reproduce this order for exact parity.

### 2.2 Pipeline

`FormatPipeline(value, fmt, key)` (`:115-133`): the format string is split on `|` with
`StringSplitOptions.RemoveEmptyEntries | TrimEntries`; each token is applied left→right,
rewriting the current value. Per token, in this exact order:

1. `TryMath` (`:135-155`) — prefix `math:` (case-insensitive).
2. `TryText` (`:157-178`) — `sub:`, `replace:`, `upper`, `lower`.
3. `TryTime` (`:180-197`) — prefix `time:`.
4. token starts with `auto` (case-insensitive) → `SmartAuto` (`:199-212`).
5. otherwise `BaseFormat(current, token, key)` (`:214-241`).

An empty/whitespace `fmt` goes straight to `BaseFormat(value, "", key)` (`:117`).
Final output is `Convert.ToString(current, InvariantCulture) ?? ""` (`:132`).

### 2.3 `BaseFormat` — the unit/kind table

`Customization/TemplateEngine.cs:214-241`. `ParseDigits(fmt, fallback)` = the part after the
first `:` parsed as int, **clamped 0..6**, else the fallback (`:258-262`).

| Value / format token | Result | Ref |
| --- | --- | --- |
| `DateTime`, empty fmt | `dt.ToString("G")` (current culture) | `:216-217` |
| `DateTime`, fmt | `dt.ToString(fmt.Replace("dt:", ""))` | `:217` |
| `DateTimeOffset` | as `DateTime` (same two cases) | `:218-219` |
| `bool` | `"true"` / `"false"` (regardless of fmt) | `:220` |
| `string` with empty fmt | the string itself | `:221` |
| number, empty fmt | `n.ToString("0.##", Invariant)` | `:225` |
| `bytes` | `HumanBytes(n, perSecond:false, digits:1)` | `:226` |
| `speed` | `HumanBytes(n, perSecond:true, digits:1)` | `:227` |
| `gb` / `gb:n` | `(n/1024³).ToString("F"+(n or 1))` — e.g. `{memory.used_bytes\|gb:1}` | `:228` |
| `mb` / `mb:n` | `(n/1024²)` F(n or 1) | `:229` |
| `kb` / `kb:n` | `(n/1024)` F(n or 1) | `:230` |
| `tb` / `tb:n` | `(n/1024⁴)` F(n or **2**) | `:231` |
| `mbps` / `mbps:n` | `(n*8/1_000_000)` F(n or 1) — **decimal** Mbps | `:232` |
| `kbps` / `kbps:n` | `(n*8/1_000)` F(n or 1) | `:233` |
| `percent` / `percent:n` | `n.ToString("F"+(n or 0)) + "%"` | `:234` |
| `duration` | `Duration(n)` | `:235` |
| `duration-long` | `DurationLong(n)` | `:236` |
| anything else, numeric | `n.ToString(fmt, Invariant)`; on exception → `"0.##"` | `:237-238` |
| non-numeric, non-special | `Convert.ToString(value, Invariant)` | `:240` |

Notes / gotchas:

* `gb`, `mb`, `kb`, `tb`, `mbps`, `kbps`, `percent`, `bytes`, `speed`, `duration`,
  `duration-long` are matched **case-insensitively** and by **prefix** (so `gb2`, `mbx`
  still select the unit; digits are only read after a `:`). `gb:2` = 2 decimals.
* `bytes`/`speed` always produce 1 decimal and an explicit unit suffix.
* Plain numeric formats (`0`, `0.0`, `0.00`, `0.000`, `#,##0.00`, …) are .NET custom
  numeric format strings; on Windows .NET 8, half-way values are formatted with
  IEEE-compliant rounding. **Ambiguity to verify**: the code never passes an explicit
  `MidpointRounding`, so tie-breaking (`.5`) parity with Kotlin/Java formatting must be
  pinned by fixtures; `0.00` is a custom format string, not `"F2"`, and the two can differ
  on sign/grouping.
* A numeric-looking **string** with a non-empty format is parsed and reformatted
  (`:221` requires empty fmt to short-circuit), so `{probe.latency_text|gb:1}` would divide
  its text by 1024³ when the text parses as a number.

### 2.4 `HumanBytes`

`Customization/TemplateEngine.cs:264-270`:

```
units = { "B", "KB", "MB", "GB", "TB", "PB" }
while |bytes| >= 1024 and i < 5: bytes /= 1024; i++
result = F{digits} + " " + units[i] + ("/s" if perSecond)
```

Binary (1024) scaling, one space before the unit, digits from `ParseDigits` (0..6).
This differs from the network **display-unit** formatter, which is decimal-SI
(see §2.7).

### 2.5 `Duration` / `DurationLong`

* `Duration(seconds)` (`:272-276`): `TimeSpan.FromSeconds(max(0, (long)seconds))`;
  if `TotalHours >= 1` → `"{h:00}:{m:00}:{s:00}"`, else `"{m:00}:{s:00}"`.
* `DurationLong(seconds)` (`:278-284`): if `TotalDays >= 1` →
  `"{days}天 {h:00}:{m:00}:{s:00}"` or `"{days}d {h:00}:{m:00}:{s:00}"` by UI language,
  else `"{h:00}:{m:00}:{s:00}"`.
* Both truncate toward zero via `(long)` cast after `Math.Floor`-like `max(0, seconds)`.
  (Exact: `Math.Max(0, (long)seconds)` — i.e. C# truncation of the double.)

### 2.6 `time:` formats

`TryTime` (`:180-197`):

| Token | Behaviour |
| --- | --- |
| `time:relative` | `DateTime`/`DateTimeOffset` → `RelativeTime`; numeric → `RelativeDuration`; else `"--"` (`:184-190`) |
| `time:<fmt>` | if the value parses as a date (`TryDateTime`, `:249-256`: `DateTime`, `DateTimeOffset.LocalDateTime`, or `DateTime.TryParse` current-culture then invariant) → `ToString(<fmt>, CurrentCulture)`; parse failure or format exception → fallback `ToString(CurrentCulture)` / `"--"` (`:191-196`) |

`RelativeTime` (`:286-309`): Chinese `"{n}秒/分钟/小时/天/个月/年"` + `后`/`前`;
English `"{n}s/m/h/d/mo/y"` with `in {x}` / `{x} ago`. Thresholds: <60 s, <60 min,
<24 h, <30 d, <365 d, else years. Uses `DateTime.Now - dt`; future ⇒ suffix.
`RelativeDuration` (`:311-325`): Chinese `秒/分钟/小时/天`, English `s/m/h/d`, no suffix,
`Math.Abs`.

### 2.7 Profile-level network display formatter (not the `|` pipeline)

`HudProfileRenderer.FormatNetworkSpeed` (`HudProfileRenderer.cs:205-216`) formats the
`network.display_download` / `network.display_upload` strings selected by
`HudProfile.NetworkDisplayUnit` (`AutoBytes` | `Mbps`):

* `Mbps` → `"{bps*8/1_000_000:0.0} Mbps"` (decimal).
* otherwise **decimal SI**: `< 1_000_000 B/s` → `"{bps/1_000:0.0} KB/s"`, else
  `"{bps/1_000_000:0.0} MB/s"` (`:211-215`).

`ToBytesPerSecond` (`:218-228`) for the percentage reference:
`Mbps → v*1e6/8`, `KB/s → v*1e3`, `MB/s → v*1e6`, unknown → `v*1e6`.
`0.0` uses the same half-way rounding caveat as §2.3.

### 2.8 `SmartAuto` (`auto`, `auto:n`)

`Customization/TemplateEngine.cs:199-212`; digits default 1, clamped 0..6.
The branch is decided by the **variable key**, not the value:

| Key test (in order) | Result |
| --- | --- |
| ends `_bps`, or contains `.read_bps` / `.write_bps` / `.io_bps` | `HumanBytes(n, true, digits)` |
| ends `_bytes`, or contains `bytes` | `HumanBytes(n, false, digits)` |
| ends `_seconds` | `Duration(n)` |
| contains `percent`, or ends `.usage`, or ends `.progress` | `F{digits}` + `"%"` |
| else | `F{digits}` |

Catalog `RecommendedFormats` reference `auto:1` for
`cpu.instructions_per_second`, `memory.standby_bytes`, `memory.modified_bytes`,
`memory.hardware_reserved_bytes`, `process.top_memory.usage`, `process.top_disk.usage`,
`dev.wsl.memory_usage`, and the dynamic `disk.<letter>.read_bps|write_bps|io_bps`
(`VariableCatalog.cs:354,361-363,428,431,488,798-800`).

### 2.9 `math:` tokens

`TryMath` (`:135-155`). Grammar `math:<op>[:<arg>]`, case-insensitive, arg parsed with
`NumberStyles.Any` invariant, missing/invalid arg = 0.

| op | Result | Notes |
| --- | --- | --- |
| `add` | `n + arg` | `:144` |
| `sub` | `n − arg` | `:145` |
| `mul` | `n * arg` | `:146` |
| `div` | `n / arg`, **`NaN` if `abs(arg) < double.Epsilon`** | `:147` |
| `round` | `Math.Round(n, digits, MidpointRounding.AwayFromZero)`, digits from 3rd part clamped 0..8 (default 0) | `:148` |
| `floor` | `Math.Floor(n)` | `:149` |
| `ceil` | `Math.Ceiling(n)` | `:150` |
| `abs` | `Math.Abs(n)` | `:151` |
| unknown | value unchanged, token consumed | `:152` |

If the incoming value is not convertible to double, the value becomes the string `"--"`
and the token still returns true (`:138`).

### 2.10 Text tokens

`TryText` (`:157-178`):

| Token | Behaviour |
| --- | --- |
| `sub:start` / `sub:start:length` | substring of `Convert.ToString(value, Invariant)`; `start<0 → 0`; missing length → `s.Length−start`; `start >= s.Length` → `""`; length clipped to available | `:159-167` |
| `replace:old:new` | `string.Replace(old, new, StringComparison.Ordinal)`; only when `Split(':',3)` yields 3 parts, so `new` may itself contain `:` | `:168-174` |
| `upper` | `ToUpperInvariant()` | `:175` |
| `lower` | `ToLowerInvariant()` | `:176` |
| anything else | not handled → falls through to `auto`/`BaseFormat` | `:177` |

Used in the catalog for `usb.device.list` (`sub:0:80`) and `dev.git.last_commit`
(`sub:0:80`) (`VariableCatalog.cs:473,491`).

### 2.11 Formatting fallbacks / sentinels summary

| Situation | Output |
| --- | --- |
| key absent, value null | `--` (per token) |
| expression evaluation failed | `--` (whole expression token, `TemplateEngine.cs:24`) |
| expression evaluated to null | `--` (`:25`) |
| non-numeric value with numeric format | raw `Convert.ToString(value)` |
| `math:` on non-numeric | `--` |
| `div` by ~0 | `NaN` (rendered as `"NaN"` when it is the last token) |
| `time:` on non-date | `--` |
| unknown numeric format string | `n.ToString("0.##")` |
| `Duration`/`DurationLong` for 0 | `"00:00"` |

### 2.12 Documented format examples

README.en.md:102-107 / README.md:102-107:

```text
{cpu.usage|0}                 CPU utilization
{memory.used_bytes|gb:1}      Used memory (GB)
{network.download_bps|speed}  Network download speed
{deepseek.balance|0.00}       DeepSeek API balance
```

In-app hint (identical zh/en): `{}模板支持 {变量|格式}；高级表达式使用 {= 表达式 | 格式}…`
(`LocalizationManager.cs:140`, `HudCustomizerView.axaml:281-282`).

---

## 3. Expression engine

`Customization/ExpressionEngine.cs` — self-described as a "small, sandboxed expression
evaluator": variables, literals, math/logical operators and a function whitelist; it cannot
invoke .NET methods, access files or allocate objects (`:8-12`).

### 3.1 Where expressions appear

| Surface | Form | Code |
| --- | --- | --- |
| Template token | `{= <expr> [\| <fmt>] }` | `TemplateEngine.cs:12,20-27` |
| Progress variable | `= <expr>`, `{= <expr> }`, `{= <expr> \| <fmt> }`, or a bare key | `TemplateEngine.cs:54-69` |
| Key extraction | `TemplateEngine.ExtractKeys` includes expression variables | `:38-52` |
| Settings UI hint | progress may also be an `=` expression | `HudCustomizerView.axaml:293` |

### 3.2 Expression / format split rule

`SplitExpressionAndFormat` (`TemplateEngine.cs:89-113`): scan left→right; skip quoted
strings (`'`/`"`, `\` escapes), track `(` depth; the first `|` at depth 0 that is **not**
part of `||` (`body[i-1] == '|'` or `body[i+1] == '|'`) splits expression and format.
Everything is `.Trim()`ed. No split found ⇒ `(body.Trim(), "")`.

### 3.3 Grammar and precedence (lowest → highest)

`Parser` (`ExpressionEngine.cs:92-440`):

```
Parse        := ParseTernary EOF
Ternary      := Coalesce [ '?' Ternary ':' Ternary ]        // right-assoc, eager
Coalesce     := Or ( '??' Or )*                             // null OR empty-string replaces left
Or           := And ( ('||' | 'or') And )*                 // word ops case-insensitive
And          := Equality ( ('&&' | 'and') Equality )*
Equality     := Comparison ( ('==' | '!=') Comparison )*
Comparison   := Additive ( ('>=' | '<=' | '>' | '<') Additive )*
Additive     := Multiplicative ( ('+' | '-') Multiplicative )*
Multiplicative := Power ( ('*' | '/' | '%') Power )*
Power        := Unary [ '^' Power ]                         // right-assoc
Unary        := ('!' | 'not' | '+' | '-') Unary | Primary
Primary      := '(' Ternary ')' | string | number | 'true' | 'false' | 'null'
              | identifier [ '(' args ')' ]                  // function call
```

Exact operator handling:

* Ternary (`:113-122`) evaluates **both** branches (it parses then selects); no short-circuit.
* Coalesce (`:124-134`): replaces left when `left is null` **or** `left` is the empty
  string; loops for chains; `a ?? b ?? c` works.
* `||`/`or` and `&&`/`and` (`:136-164`) are short-circuit only in the sense that both sides
  are parsed; `ToBool` is applied to each operand and the result is a `bool`.
* Equality (`:166-176`): `==` → `ValuesEqual`, `!=` → negation.
* Comparison (`:178-190`): `Compare(left, right)`; numeric compare when both parse as
  numbers, else **case-insensitive string** compare.
* `+` (`:198-205`): if **either** operand is a `string` → string concatenation via `ToText`;
  otherwise numeric addition.
* `-`, `*`, `/`, `%` are numeric only; `/` and `%` throw `DivideByZeroException`
  when the divisor's absolute value `< double.Epsilon` (`:218-229`).
* `^` is power (`Math.Pow`), **not** XOR (`:234-240`).
* Unary `!`/`not` (`:245-246`), unary `+`/`-` (`:247-248`).
* No bitwise operators, no assignment, no statements, no indexing, no member access
  (a `.` is only an identifier character, so dotted names are single identifiers).

### 3.4 Literals, identifiers, escaping

* Numbers (`:360-381`): digits and `.` freely, plus at most one `e`/`E` exponent with an
  optional sign; parsed with `NumberStyles.Float` invariant. Leading `.5` is accepted
  (`:266-267`). No hex, no `_` separators, no locale decimal comma.
* Strings (`:383-399`): delimited by `'` or `"`; escapes `\n`, `\r`, `\t`, `\\`, `\'`, `\"`;
  any other escaped char is taken literally; unterminated string ⇒ error
  `"字符串缺少结束引号"/"Missing closing quote"`.
* Identifiers (`:269-290`): start letter or `_`; continue letter/digit/`_`/`.`.
  `true`/`false`/`null` are case-insensitive keywords.
  A bare identifier that is not a function and not found in the variable dictionary
  evaluates to **`null`**, not an error (`:290`).
* Whitespace is skipped everywhere (`:434-437`).

### 3.5 Function whitelist (exact names, arity, behaviour)

Whitelisted set (`ExpressionEngine.cs:15-21`, case-insensitive). `Call` (`:296-337`):

| Function | Arity | Result |
| --- | --- | --- |
| `if(cond, a, b)` | exactly 3 | `ToBool(cond) ? a : b` (`:301`) |
| `min(...)`, `max(...)` | ≥1 | numeric min/max (`:302-303`) |
| `avg(...)` | ≥1 | numeric average (`:304`) |
| `sum(...)` | any (0 ⇒ 0) | numeric sum (`:305`) |
| `clamp(v, lo, hi)` | exactly 3 | `Math.Clamp(ToNumber(v), lo, hi)` (`:306`) |
| `round(v)` / `round(v, digits)` | 1–2 | `Math.Round(ToNumber(v), clamp(digits,0,8), MidpointRounding.AwayFromZero)` (`:339-344`) |
| `floor(v)` | 1 | `Math.Floor` (`:308`) |
| `ceil(v)` | 1 | `Math.Ceiling` (`:309`) |
| `abs(v)` | 1 | `Math.Abs` (`:310`) |
| `sqrt(v)` | 1 | `Math.Sqrt(Math.Max(0, v))` (`:311`) |
| `pow(a, b)` | 2 | `Math.Pow` (`:312`) |
| `log(v)` | 1 | natural log (`:313`) |
| `log10(v)` | 1 | `Math.Log10` (`:314`) |
| `exp(v)` | 1 | `Math.Exp` (`:315`) |
| `sin`, `cos`, `tan` | 1 | radians (`:316-318`) |
| `sign(v)` | 1 | `Math.Sign` (`:319`) |
| `len(v)` | 1 | `ToText(v).Length` (`:320`) |
| `contains(a,b)`, `startswith(a,b)`, `endswith(a,b)` | 2 | `StringComparison.OrdinalIgnoreCase` (`:321-323`) |
| `upper(v)`, `lower(v)` | 1 | invariant case (`:324-325`) |
| `concat(...)` | any | `string.Concat` of `ToText` of every arg (`:326`) |
| `isnull(v)` | 1 | `v is null` (`:327`) |
| `isempty(v)` | 1 | `string.IsNullOrWhiteSpace(ToText(v))` (`:328`) |
| `isnan(v)` | 1 | `double.IsNaN(ToNumber(v))` (`:329`) |
| `number(v)` | 1 | `ToNumber(v)` (`:330`) |
| `string(v)` | 1 | `ToText(v)` (`:331`) |
| `bool(v)` | 1 | `ToBool(v)` (`:332`) |
| `percent(v, total)` | 2 | `v/total*100`; 0 when `abs(total) < double.Epsilon` (`:333`) |
| `between(v, lo, hi)` | 3 | `lo <= v <= hi` (`:334`) |
| anything else | — | throws `"不支持的函数：{name}" / "Unsupported function: {name}"` (`:335`) |

Arity errors throw `FormatException`:
`"函数 {name} 需要 {count} 个参数"` / `"Function {name} requires {count} arguments"`
(`:348-352`), or the "at least" variant (`:354-358`).

Argument lists (`:277-288`): `f()`, `f(a)`, `f(a, b, …)`; commas separate; empty argument
list is allowed syntactically (`if()` then fails arity).

Note for parity: `FunctionNames` is also used by `ExtractVariables` to skip names that are
not followed by `(` (`:77-79`), so a variable literally named `max` would never be extracted.

### 3.6 Coercion rules

`TryNumber` (`:456-471`): `null` → false (0); `bool` → 1/0 with **true**; otherwise
`Convert.ToDouble` invariant, then `double.TryParse` invariant, then current culture;
returns false when the result is `NaN`.
`ToNumber` (`:473-478`): `null` → 0; otherwise throws `FormatException`
`"'{text}' 不是数值" / "'{text}' is not numeric"`.
`ToBool` (`:480-492`): `null` → false; `bool` → itself; numeric → `|n| > double.Epsilon`;
string → `bool.TryParse`, else true unless empty/`"false"`/`"off"`/`"no"`/`"0"`
(all case-insensitive except the `"0"` comparison).
`ToText` (`:494`): `Convert.ToString(value, InvariantCulture) ?? ""`.
`ValuesEqual` (`:442-448`): both null ⇒ true; both numeric ⇒ `|a−b| < 1e-7`;
either bool ⇒ `ToBool` comparison; else case-insensitive string equality.
`Compare` (`:450-454`): numeric when both numeric, else case-insensitive string compare
(so `'b' > 'A'` is a culture-free ordinal-ish comparison via `string.Compare(..., OrdinalIgnoreCase)`).

### 3.7 Errors and error surfaces

`TryEvaluate` (`:28-47`) wraps the parser and catches **only**
`FormatException`, `InvalidOperationException`, `DivideByZeroException`, returning
`false` with `error = ex.Message` and `value = null`.
Parse errors are `FormatException` with a localized message including the 1-based position:
`"表达式错误（位置 {n}）：{message}"` / `"Expression error (position {n}): {message}"` (`:439`).

**Ambiguities / risks to replicate deliberately:**

* Exceptions outside that list escape `TryEvaluate`. In particular
  `clamp(v, 10, 5)` makes `Math.Clamp` throw `ArgumentException`, which is **not** caught
  (`:306`) — the exception propagates out of `TemplateEngine.Render`. Most call sites
  (`CustomHudRuntime`, `HudWindow`) guard with try/catch, but the template layer itself has
  no such protection.
* `ToNumber` failures (`FormatException`) *are* caught → `--`.
* `pow`/`log`/`sqrt` domain issues produce `NaN`/`Infinity` (no exception); `NaN` then
  typically surfaces as `"NaN"` in output or `--` if re-coerced by later stages.

### 3.8 Template substitution vs. expression evaluation — the exact differences

| Aspect | `{key\|fmt}` substitution | `{= expr \| fmt}` expression |
| --- | --- | --- |
| Regex | `TokenRegex` `:11` | `ExpressionTokenRegex` `:12` |
| Body charset | key `[a-zA-Z0-9_.-]+`, fmt `[^}]+` | body `[^{}]*` (no braces), fmt split at top-level `\|` |
| Order | after expressions (`:29`) | first (`:20`) |
| Unknown name | `--` | identifier resolves to `null`; null result renders `--` |
| Failure | never throws | `TryEvaluate == false` ⇒ `--` |
| Format pipeline | identical (`FormatPipeline`) | identical (`FormatPipeline`) |
| Multi-key access | one key per token; concatenate tokens | multiple keys inside one expression token |
| Used for | every template field, `ProgressVariable` (bare key) | templates, `ProgressVariable` with `=`/`{=}` |

`EvaluateNumber` (`:54-69`) is the numeric bridge for `ProgressVariable`:
strips a leading `{=`…`}` or `=`, splits off a format, evaluates, and converts with
`Convert.ToDouble`; any failure returns the supplied fallback (`ProgressMin` at the call
site, `HudProfileRenderer.cs:15`). A bare (non-`=`) progress value goes through
`Number(key, vars, fallback)` = dictionary lookup + `Convert.ToDouble` (`:82-87`).

`ExtractExpressionKeys` (`:71-80`): for a bare source returns `new[] { text }` (the raw key);
otherwise strips `{=`/`=`, splits the format, and returns `ExpressionEngine.ExtractVariables`.
`ExtractVariables` (`:49-87`) skips quoted strings, keywords (`true/false/null/and/or/not`)
and function names, and returns a case-insensitive set.

---

## 4. Profile system

### 4.1 `HudProfile` — every field, type, default

`Customization/Models.cs:15-65`. Serialized by `System.Text.Json` as-is (PascalCase property
names, `WriteIndented`), since `SettingsManager` sets no naming policy
(`Settings/SettingsManager.cs:11-15`).

| # | Property | Type | Default (`Models.cs`) | Meaning |
| --- | --- | --- | --- | --- |
| 1 | `Id` | string | `Guid.NewGuid().ToString("N")` (`:17`) | 32-hex identity, referenced by `ActiveProfileId` and the cycle queue |
| 2 | `IsBuiltIn` | bool | `false` (`:20`) | built-in flag; read-only in UI |
| 3 | `BuiltInKey` | string | `""` (`:21`) | stable built-in identity (`system.cpu`, …) |
| 4 | `Category` | string | `"自定义"` (`:25`) | internal category; merged into the display name |
| 5 | `Name` | string | `"自定义 HUD"` (`:26`) | internal name; display name = `"{Category} - {Name}"` |
| 6 | `AnimationMode` | string | `"Full"` (`:27`) | `Simple` \| `Full` |
| 7 | `TaglineTemplate` | string | `"/// SYSTEM MONITOR"` (`:29`) | small upper text |
| 8 | `TitleTemplate` | string | `"系统状态"` (`:30`) | title stage |
| 9 | `PrimaryTemplate` | string | `"{cpu.frequency_ghz\|0.00}"` (`:31`) | left main value |
| 10 | `SecondaryTemplate` | string | `" GHz"` (`:32`) | left secondary text |
| 11 | `RightTemplate` | string | `"{cpu.usage\|0}"` (`:33`) | right status value |
| 12 | `RightSuffix` | string | `"%"` (`:34`) | right suffix |
| 13 | `ProgressVariable` | string | `"cpu.usage"` (`:36`) | bare key, `= expr` or `{= expr}` |
| 14 | `ProgressMin` | double | `0` (`:37`) | ring mapping lower bound |
| 15 | `ProgressMax` | double | `100` (`:38`) | ring mapping upper bound |
| 16 | `LeftIcon` | string | `"cpu"` (`:40`) | icon in the left circle/square |
| 17 | `RightIcon` | string | `"cpu"` (`:41`) | icon/badge on the right |
| 18 | `AccentColor` | string | `"#C6CA4C"` (`:42`) | default accent |
| 19 | `ColorRules` | `List<HudColorRule>` | empty (`:43`) | first match wins |
| 20 | `TimeTargetEnabled` | bool | `false` (`:46`) | target-time mode for the time category |
| 21 | `TimeTarget` | string | `"10:00:00"` (`:47`) | daily target, `hh:mm:ss` |
| 22 | `GpuAdapterId` | string | `""` (`:50`) | per-profile GPU; empty = first adapter |
| 23 | `NetworkDisplayUnit` | string | `"AutoBytes"` (`:55`) | `AutoBytes` \| `Mbps` |
| 24 | `NetworkPercentMode` | string | `"Total"` (`:56`) | `Total` \| `Download` \| `Upload` \| `Max` |
| 25 | `NetworkReferenceValue` | double | `100d` (`:57`) | 100 % reference value |
| 26 | `NetworkReferenceUnit` | string | `"MB/s"` (`:58`) | `Mbps` \| `KB/s` \| `MB/s` |
| 27 | `PingTarget` | string | `"1.1.1.1"` (`:62`) | probe target (IPv4/IPv6/host) |
| 28 | `ProbeProtocol` | string | `"ICMP"` (`:63`) | `ICMP` \| `TCP` \| `UDP` |
| 29 | `ProbePort` | int | `443` (`:64`) | TCP/UDP port; ignored for ICMP |

`HudColorRule` (`Models.cs:7-13`): `Variable` (default `""`), `Operator` (default `">="`;
one of `>`, `>=`, `<`, `<=`, `==`, `!=`), `Value` (double), `Color` (string, default
`"#C6CA4C"`).
Rule evaluation order and semantics (`HudProfileRenderer.ResolveAccent`, `:245-264`):
iterate rules in list order; skip a rule when the variable is missing or non-numeric; first
match returns its color; `==`/`!=` use `|a−b| < 1e-6`; no match → `AccentColor`.
Unknown operator → no match (`:259`).

`HudRenderData` (`Models.cs:306-317`) is the render output record:
`Tagline, Title, PrimaryText, SecondaryText, RightText, RightSuffix, Progress (0..1),
LeftIcon, RightIcon, AccentColor, SimpleAnimation`.

Progress mapping (`HudProfileRenderer.cs:15-18`):
`span = ProgressMax − ProgressMin`; `span <= 0` ⇒ progress 0; else
`clamp((raw − ProgressMin)/span, 0, 1)`.

### 4.2 Built-in profiles — full configured content

`CustomHudSettings.CreateDefaultProfiles()` (`Models.cs:119-303`) defines **9** built-ins.
Fields not listed keep the `HudProfile` defaults from §4.1. Quoted verbatim:

```csharp
new()
{
    IsBuiltIn = true,
    BuiltInKey = "system.battery",
    Category = "系统",
    Name = "电池",
    AnimationMode = "Full",
    TaglineTemplate = "/// BATTERY",
    TitleTemplate = "电池",
    PrimaryTemplate = "{battery.remaining_mwh|0}",
    SecondaryTemplate = "/{battery.full_mwh|0}",
    RightTemplate = "{battery.percent|0}",
    RightSuffix = "%",
    ProgressVariable = "battery.percent",
    LeftIcon = "bolt",
    RightIcon = "battery",
    ColorRules = new()
    {
        new() { Variable = "battery.percent", Operator = "<=", Value = 20, Color = "#FF4D4F" }
    }
},
new()
{
    IsBuiltIn = true,
    BuiltInKey = "system.cpu",
    Category = "系统",
    Name = "CPU",
    AnimationMode = "Full",
    TaglineTemplate = "/// CPU",
    TitleTemplate = "CPU",
    PrimaryTemplate = "{cpu.frequency_ghz|0.00}",
    SecondaryTemplate = " GHz",
    RightTemplate = "{cpu.usage|0}",
    RightSuffix = "%",
    ProgressVariable = "cpu.usage",
    LeftIcon = "cpu",
    RightIcon = "cpu",
    ColorRules = new()
    {
        new() { Variable = "cpu.usage", Operator = ">=", Value = 90, Color = "#FF4D4F" },
        new() { Variable = "cpu.usage", Operator = ">=", Value = 75, Color = "#FFB84D" },
    }
},
new()
{
    IsBuiltIn = true,
    BuiltInKey = "system.memory",
    Category = "系统",
    Name = "内存",
    AnimationMode = "Full",
    TaglineTemplate = "/// MEMORY",
    TitleTemplate = "内存",
    PrimaryTemplate = "{memory.used_bytes|gb:1}",
    SecondaryTemplate = "/{memory.total_bytes|gb:1} GB",
    RightTemplate = "{memory.usage|0}",
    RightSuffix = "%",
    ProgressVariable = "memory.usage",
    LeftIcon = "memory",
    RightIcon = "memory"
},
new()
{
    IsBuiltIn = true,
    BuiltInKey = "system.gpu",
    Category = "系统",
    Name = "GPU",
    AnimationMode = "Full",
    TaglineTemplate = "/// GPU",
    TitleTemplate = "GPU",
    PrimaryTemplate = "{gpu.memory_used_bytes|gb:1}",
    SecondaryTemplate = "/{gpu.memory_total_bytes|gb:1} GB",
    RightTemplate = "{gpu.usage|0}",
    RightSuffix = "%",
    ProgressVariable = "gpu.usage",
    LeftIcon = "gpu",
    RightIcon = "gpu"
},
new()
{
    IsBuiltIn = true,
    BuiltInKey = "system.network",
    Category = "系统",
    Name = "网络",
    AnimationMode = "Full",
    TaglineTemplate = "/// NETWORK",
    TitleTemplate = "网络",
    PrimaryTemplate = "↓ {network.display_download}",
    SecondaryTemplate = "  ↑ {network.display_upload}",
    RightTemplate = "{network.profile_percent_text}",
    RightSuffix = "",
    ProgressVariable = "network.profile_percent",
    ProgressMin = 0,
    ProgressMax = 100,
    LeftIcon = "network",
    RightIcon = "network",
    NetworkDisplayUnit = "AutoBytes",
    NetworkPercentMode = "Total",
    NetworkReferenceValue = 500d,
    NetworkReferenceUnit = "Mbps"
},
new()
{
    IsBuiltIn = true,
    BuiltInKey = "system.disk",
    Category = "系统",
    Name = "系统盘",
    AnimationMode = "Full",
    TaglineTemplate = "/// SYSTEM DISK",
    TitleTemplate = "系统盘",
    PrimaryTemplate = "{disk.system.used_bytes|gb:1}",
    SecondaryTemplate = "/{disk.system.total_bytes|gb:1} GB",
    RightTemplate = "{disk.system.usage|0}",
    RightSuffix = "%",
    ProgressVariable = "disk.system.usage",
    LeftIcon = "disk",
    RightIcon = "disk"
},
new()
{
    IsBuiltIn = true,
    BuiltInKey = "time.day-progress",
    Category = "时间",
    Name = "日进程",
    AnimationMode = "Full",
    TaglineTemplate = "/// TIME",
    TitleTemplate = "日进程",
    PrimaryTemplate = "{time.current}",
    SecondaryTemplate = "",
    RightTemplate = "{time.display.status_text}",
    RightSuffix = "",
    ProgressVariable = "time.display.progress",
    ProgressMin = 0,
    ProgressMax = 100,
    LeftIcon = "clock",
    RightIcon = "clock",
    TimeTargetEnabled = false,
    TimeTarget = "10:00:00"
},
new()
{
    IsBuiltIn = true,
    BuiltInKey = "deepseek.balance-period",
    Category = "DeepSeek API",
    Name = "余额 / 时段",
    AnimationMode = "Full",
    TaglineTemplate = "/// DEEPSEEK API",
    TitleTemplate = "DeepSeek 当前{deepseek.period.name_zh}",
    PrimaryTemplate = "¥{deepseek.balance|0.00}",
    SecondaryTemplate = "  {deepseek.period.remaining_text}",
    RightTemplate = "{deepseek.period.progress_text}",
    RightSuffix = "",
    ProgressVariable = "deepseek.period.progress",
    LeftIcon = "api",
    RightIcon = "clock"
},
new()
{
    IsBuiltIn = true,
    BuiltInKey = "network.ping",
    Category = "网络",
    Name = "网络包探测器",
    AnimationMode = "Full",
    TaglineTemplate = "/// NETWORK PROBE",
    TitleTemplate = "网络包探测器",
    PrimaryTemplate = "{probe.latency_ms|0}ms",
    SecondaryTemplate = "",
    RightTemplate = "丢包{probe.loss_percent|0}",
    RightSuffix = "%",
    ProgressVariable = "probe.loss_percent",
    ProgressMin = 0,
    ProgressMax = 100,
    LeftIcon = "signal",
    RightIcon = "gauge",
    PingTarget = "1.1.1.1",
    ProbeProtocol = "ICMP",
    ProbePort = 443,
    ColorRules = new()
    {
        new() { Variable = "probe.loss_percent", Operator = ">=", Value = 30, Color = "#FF4D4F" },
        new() { Variable = "probe.loss_percent", Operator = ">=", Value = 10, Color = "#FFB84D" },
    }
}
```

Default state (`CustomHudSettings.CreateDefault`, `Models.cs:101-117`):
`Profiles = CreateDefaultProfiles()`, `ActiveProfileId = Id of "system.memory"` (fallback:
first profile), `CycleProfileIds = all profiles whose Category == "系统" **or**
BuiltInKey == "time.day-progress"` (i.e. battery, CPU, memory, GPU, network, disk,
day-progress — 7 entries, in list order), `CycleAnimationMode = "Simple"`.
README.en.md:60 confirms "The default profile is **System – Memory**".

### 4.3 `CustomHudSettings` (the per-profile container)

`Models.cs:83-99`:

| Property | Type | Default | Persisted JSON key |
| --- | --- | --- | --- |
| `AutoCycle` | bool | `false` | `AutoCycle` |
| `CycleSeconds` | int | `10` | `CycleSeconds` |
| `CycleProfileIds` | `List<string>?` | **`null`** (`:89`) | `CycleProfileIds` (may be `null`; `null` means "legacy file, migrate once") |
| `CycleAnimationMode` | string | `"Simple"` | `CycleAnimationMode` |
| `ActiveProfileId` | string | `""` | `ActiveProfileId` |
| `DeepSeekApiKeyProtected` | string | `""` | `DeepSeekApiKeyProtected` |
| `DeepSeekPeakWindows` | string | `"09:00-12:00;14:00-18:00"` | `DeepSeekPeakWindows` |
| `Profiles` | `List<HudProfile>` | `new()` | `Profiles` |
| `HttpSources` | `List<CustomHttpSource>` | `new()` | `HttpSources` |

`CustomHttpSource` (`Models.cs:73-81`): `Name` (`"custom"`), `Enabled` (`true`), `Url`
(`""`), `RefreshSeconds` (`60`), `Headers` (`Dictionary<string,string>`, default empty),
`Fields` (`List<HttpFieldMapping>`).
`HttpFieldMapping` (`Models.cs:67-71`): `Variable` (`"value"`), `JsonPath` (`""`).

### 4.4 Built-in vs custom — the exact distinction

* Built-in ⇔ `IsBuiltIn == true` **or** `BuiltInKey` non-empty; this pair is treated as
  "built-in" in `NormalizeProfile` (`HudSettingsNormalizer.cs:100`),
  `BuiltInProfileLocalization.DisplayName` (`:61`), the editor
  (`HudCustomizerView.axaml.cs:458,463`) and the normalizer loop (`:58`).
* Built-in structural/template content is **canonical and immutable**: the normalizer always
  rewrites it from `CreateDefaultProfiles()` (`HudSettingsNormalizer.cs:20-52`).
* Only four fields may survive from the stored built-in: `Id` (identity preservation),
  `TimeTargetEnabled` + `TimeTarget` (only for `time.day-progress`),
  `GpuAdapterId` (only for `system.gpu`) (`:31-47`).
* Custom profiles keep `Category = "自定义"` and `BuiltInKey = ""`
  (`NormalizeCustomProfile`, `:124-146`).
* Display name: built-ins → `ForCurrentLanguage(profile)` then `"{Category} - {Name}"`;
  custom → `Name`, or `"自定义方案"/"Custom Profile"` when empty
  (`BuiltInProfileLocalization.cs:59-70`).

### 4.5 `HudSettingsNormalizer` — rules Android must replicate

`Normalize(settings)` (`HudSettingsNormalizer.cs:9-96`):

1. Null input → `CustomHudSettings.CreateDefault()` (`:11`).
2. Stored profiles are cloned (`ColorRules` deep-copied) (`:12`, `CloneProfile` `:174-175`).
3. For each of the **9 canonical built-ins, in `CreateDefaultProfiles()` order** (`:20`):
   * find an existing profile with the same `BuiltInKey` (case-insensitive) (`:22-24`);
   * else find a not-yet-consumed legacy profile with empty `BuiltInKey` whose
     `Category` **and** `Name` both equal the built-in's (case-insensitive)
     (`:26-29`, `IsLegacyMatch` `:148-150`);
   * emit `builtin` cloned, with `Id = existing?.Id ?? builtin.Id`, `IsBuiltIn = true`,
     `BuiltInKey = builtin.BuiltInKey`, plus the three preserved fields listed in §4.4
     (`:31-47`);
   * mark that existing Id consumed (`:50-51`).
4. Every remaining stored profile that is not built-in and has no `BuiltInKey` is appended
   as a custom profile, normalized (`:55-60`).
   Saved built-ins that no longer exist in code are dropped (`IsBuiltIn == true` rows are
   skipped at `:58`).
5. `ActiveProfileId`: if empty or not found among normalized profiles → first profile's Id,
   else `""` (`:62-64`).
6. `CycleProfileIds` (`:70-82`):
   * `null` (legacy) → **all** normalized profile Ids in order (one-time migration);
   * otherwise → keep only ids present in the valid set, dropping blanks, deduplicated
     case-insensitively, preserving order. An explicit empty list stays empty.
7. `CycleSeconds = clamp(source.CycleSeconds, 3, 3600)` (`:92`).
8. `CycleAnimationMode`: exactly `"Simple"` (case-insensitive) → `"Simple"`, anything else →
   `"Full"` (`:84-86`).
9. Returns `source with { … }` so all other `CustomHudSettings` fields pass through
   (including `DeepSeekApiKeyProtected` and `HttpSources`, which are **not** normalized here)
   (`:88-95`).

`NormalizeProfile(p)` (`:98-122`):

* built-in → canonical clone with `Id = p.Id` + the three preserved fields;
* custom → `NormalizeCustomProfile` (`:124-146`):
  * `Category`: blank → `"自定义"`, else trimmed (`:126`).
  * `Name`: blank → `"自定义 HUD"`, else trimmed (`:127`).
  * **Legacy two-level fold**: if `Category != "自定义"` then
    `Name = "{Category} - {Name}"`; then `Category = "自定义"` (`:130-139`).
  * `TimeTarget` → `NormalizeTargetTime` (`:140`): `TimeSpan.TryParse` must succeed and be in
    `[0, 1 day)`; formatted `"{(int)TotalHours:00}:{Minutes:00}:{Seconds:00}"`; otherwise
    `"10:00:00"` (`:152-157`).
  * `PingTarget` → trimmed; blank → `"1.1.1.1"` (`:160-164`).
  * `ProbeProtocol` → upper-case; only `TCP`/`UDP` kept, else `ICMP` (`:166-170`).
  * `ProbePort` → `clamp(value <= 0 ? 443 : value, 1, 65535)` (`:172`).
  * `ColorRules` deep-copied; null → empty list (`:144`).
  * `IsBuiltIn = false`, `BuiltInKey = ""` (`:136-137`).

### 4.6 Save / copy / rename / delete semantics (profile editor)

`Customization/HudCustomizerView.axaml.cs`.

* **Load** (`Load`, `:283-321`): `_loaded = HudSettingsNormalizer.Normalize(settings)`;
  `_profiles` = normalized profiles, each cloned then `NormalizeProfile`d (`:294-297`);
  cycle ids filtered against existing profile ids, de-duplicated (`:299-303`);
  `HttpSourcesJsonBox` = JSON of sources, or the comment-only example when empty (`:306-308`).
* **Export/commit** (`ExportSettings`, `:323-343`): first `SaveCurrentProfile()` and
  `TryParseHttpJson(showErrors: true)`, then emits
  `AutoCycle`, `CycleSeconds = clamp(editor value, 3, 3600)`,
  `CycleProfileIds = GetSanitizedCycleProfileIds()`,
  `CycleAnimationMode = index==0 ? "Simple" : "Full"`,
  `ActiveProfileId = selected profile id`,
  `DeepSeekApiKeyProtected = SecretStore.Protect(DeepSeekApiKeyBox.Text)`,
  `DeepSeekPeakWindows = blank ? "09:00-12:00;14:00-18:00" : trimmed`,
  `Profiles = cloned`, `HttpSources = cloned`.
* **New** (`OnAddProfile`, `:885-901`): `Name = "自定义方案 {n}"/"Custom Profile {n}"` with
  `n = count(non-built-in) + 1`; `TitleTemplate = "系统状态"/"System Status"`;
  `AnimationMode = "Full"`; `LeftIcon = "clock"`; `RightIcon = "clock"`; new `Guid` Id;
  `Category = "自定义"`; then selects it.
* **Save (custom)** (`SaveCurrentProfile`, `:453-508`): edits are read with
  `BuildEditedProfile`, re-normalized with `IsBuiltIn=false`, `BuiltInKey=""`,
  `Category="自定义"`, `Name = entered name or old name`; replaced in place at the same index.
  The list combo is rebuilt only when something functionally changed.
* **Save a built-in → creates a copy** (`:463-488`): if the name was changed, or the editor
  differs from the canonical built-in (`ProfilesFunctionallyEqual`), a **new custom** profile
  is created with `Id = Guid.NewGuid().ToString("N")`, `IsBuiltIn=false`, `BuiltInKey=""`,
  `Category="自定义"`, `Name = entered name` or
  `"{built-in display name}（已更改）" / "… (Modified)"`, passed through
  `MakeUniqueCustomName` (`:623-636`: appends `" 2"`, `" 3"`, … up to 999 against display
  names, case-insensitive), appended to the list, and selected. The original built-in is
  untouched. If nothing changed, no copy is made (`:469`).
* **Delete** (`OnDeleteProfile`, `:919-939`): built-ins are refused with the status text
  `"内置方案不可删除"/"Built-in profiles cannot be deleted"`; a custom profile is removed,
  its Id is removed from `_cycleProfileIds` (`RemoveAll`, `:931`), and the selection moves to
  `clamp(oldIndex, 0, count-1)`. Deleting the last profile leaves an empty list and only
  refreshes the cycle editor (`:932-936`) — note the settings can therefore persist
  `Profiles = []`, but the next `HudSettingsNormalizer.Normalize` re-materializes all 9
  built-ins.
* **Functional comparison** (`ProfilesFunctionallyEqual`, `:582-621`) compares, in order:
  `AnimationMode`, `TaglineTemplate`, `TitleTemplate`, `PrimaryTemplate`,
  `SecondaryTemplate`, `RightTemplate`, `RightSuffix`, `ProgressVariable`,
  `ProgressMin`/`ProgressMax` (1e-6), `LeftIcon`/`RightIcon` (ignore case),
  `AccentColor` (ignore case), `TimeTargetEnabled`, `TimeTarget`, `GpuAdapterId`,
  `NetworkDisplayUnit`, `NetworkPercentMode`, `NetworkReferenceValue`, `NetworkReferenceUnit`,
  `PingTarget` (ignore case), `ProbeProtocol` (ignore case), `ProbePort`,
  `ColorRules.Count`, then each rule's `Variable` (ignore case), `Operator` (ordinal),
  `Value` (1e-6), `Color` (ignore case). `Id`, `Category`, `Name`, `IsBuiltIn`,
  `BuiltInKey` are **not** compared.
* **Conditional editor fields** (`BuildEditedProfile`, `:510-580`): GPU adapter is only
  re-read when the profile needs a `gpu.*` variable (`ProfileNeedsGpu`, `:708-709`);
  network unit/mode/reference only when it needs `network.*`/`system.network` (`:715-717`);
  probe protocol/port/target only when it needs `ping.*`/`probe.*`/`network.ping`
  (`:723-727`); target-time fields only for `time.day-progress`/`time.*` (`:711-713`).
  Unrelated fields keep their previous values.
* **Color-rule text format** (`ParseColorRules`, `:1143-1161`), one rule per line, regex:
  `^\s*(?<var>[A-Za-z0-9_.-]+)\s*(?<op>>=|<=|==|!=|>|<)\s*(?<value>-?[0-9]+(?:\.[0-9]+)?)\s*=>\s*(?<color>#[0-9A-Fa-f]{6,8})\s*$`
  — invalid lines are silently skipped; value parsed with `NumberStyles.Float` invariant.
  Round-trip rendering uses
  `$"{Variable} {Operator} {Value.ToString(InvariantCulture)} => {Color}"` (`:419-420`).
* **HTTP source JSON** (`TryParseHttpJson` `:1106-1126`, `StripHttpJsonCommentLines`
  `:1128-1141`): full-line `//` comments are stripped first (so `https://…` survives);
  JSON parse failure keeps the previous valid config and shows an error.

### 4.7 Carousel (auto cycle)

Data model: `AutoCycle` (bool), `CycleSeconds` (int, valid 3..3600, default 10),
`CycleProfileIds` (ordered list of profile Ids; `null` = legacy → all), `CycleAnimationMode`
(`Simple`|`Full`, default `Simple`). There is no other carousel state; the current position
is **not persisted**.

Editor behaviour:

* Queue edits are identity based; renaming a profile keeps its queue position
  (`HudSettingsNormalizer.cs:66-69`).
* Add (`OnCycleAdd`, `HudCustomizerView.axaml.cs:851-859`): appends the selected
  not-yet-queued profile; no duplicates (case-insensitive).
* Remove (`:861-867`), Move up (`:869-875`), Move down (`:877-883`): index-preserving
  in-place list operations.
* Queue sanitation (`GetSanitizedCycleProfileIds`, `:773-781`): drop blanks, drop ids not in
  `_profiles`, de-duplicate case-insensitively — this is the "reference cleanup when a profile
  is deleted" rule (also applied on delete at `:931`).
* Enabled/disabled buttons and status strings: `:816-849`.

Runtime behaviour (`CustomHudRuntime.cs`):

* `ApplySettings` sets `_profileIndex = AutoCycle ? 0 : ResolveActiveProfileIndex()` and
  `_nextPersistentCycle = UtcNow + clamp(CycleSeconds,3,3600)` (`:55-57`).
* Persistent tick (`TickPersistentAsync`, `:393-526`):
  * profile list = `ResolveCycleProfiles()` when `AutoCycle`, else all `Profiles` (`:395-397`);
  * **explicit empty queue is valid**: falls back to the active profile so something stays
    visible (`:401-406`);
  * on timer expiry: if currently shown, `_profileIndex = (_profileIndex + 1) % count`;
    if not shown, wrap index to 0 only when out of range; then reset the timer and force a
    refresh (`:421-431`);
  * when cycling is **off**, the index follows `ActiveProfileId` (`:432-441`).
* `ResolveCycleProfiles` (`:528-544`): maps ids → profiles by Id (case-insensitive),
  skipping unknown ids and duplicates, preserving order.
* `ApplyCycleAnimationMode` (`:546-552`): when auto-cycle is on, the profile's own
  `AnimationMode` is **overridden** by `CycleAnimationMode` for every persistent render
  (`:486-488`); the sole exception is a DeepSeek peak/off-peak boundary, which forces
  `"Full"` (`:469-488`).
* A cycle/profile change is rendered as: `HideAnimatedAsync()` then `ShowPersistentAsync()`
  (`:497-502`) — never an in-place morph.
* DeepSeek period change detection uses the rendered `deepseek.period.name_zh` value across
  ticks (`:469-481`) and also triggers an event transition while not persistent
  (`PollDeepSeekPeriodTransition`, `:335-361`).

### 4.8 Built-in text localization

`BuiltInProfileLocalization.ForLanguage(profile, language)` (`:7-57`) maps only when
`language == English` **and** `BuiltInKey` is non-empty; it rewrites `Category`, `Name` and
`TitleTemplate` for all nine built-ins, plus `RightTemplate` for `network.ping`
(`"Loss {probe.loss_percent|0}"`). Exact mapping:

| BuiltInKey | English Category | English Name | English TitleTemplate | Extra |
| --- | --- | --- | --- | --- |
| `system.battery` | System | Battery | `Battery` | |
| `system.cpu` | System | CPU | `CPU` | |
| `system.memory` | System | Memory | `Memory` | |
| `system.gpu` | System | GPU | `GPU` | |
| `system.network` | System | Network | `Network` | |
| `system.disk` | System | System Disk | `System Disk` | |
| `time.day-progress` | Time | Day Progress | `Day Progress` | |
| `deepseek.balance-period` | DeepSeek API | Balance / Period | `DeepSeek {deepseek.period.name}` | |
| `network.ping` | Network | Packet Probe | `Packet Probe` | `RightTemplate = "Loss {probe.loss_percent\|0}"` |

`ForCurrentLanguage` = `ForLanguage(profile, LocalizationManager.Current)` (`:7-8`), applied
on every `HudProfileRenderer.Render` (`HudProfileRenderer.cs:12`) and
`GetRequiredVariables` (`:37`) call. Because the translated title for DeepSeek switches the
variable from `name_zh` to `name` in English, both `deepseek.period.name` and
`.name_zh` are requested regardless.
Renderer/editor canonical-text translation (used when switching language inside the editor)
is `LocalizeBuiltInEditorText` (`HudCustomizerView.axaml.cs:250-281`).

---

## 5. HUD layout and animation

### 5.1 Window and visual tree

`Views/HudWindow.axaml`:

* Window: 1200 × 160 px, `SystemDecorations=None`, `Background=Transparent`, `Topmost=True`
  (transient), `ShowInTaskbar=False`, `ShowActivated=False`, `CanResize=False`,
  `Focusable=False`, `WindowStartupLocation=Manual` (`:6-16`).
  The window is intentionally **larger than the visible pill** to give the ripple room
  (`:567-568`).
* `Root` Grid 1200 × 100, `IsHitTestVisible=False` (`:26`).
* `GlobalScale` Grid carries the user scale as `ScaleTransform` with
  `RenderTransformOrigin="50%,50%"` (default `0.8` in XAML, `:27-32`).
* `ScaleHost` — second scale layer used only by the entrance/exit scale animations
  (initial 1,1) (`:34-39`).
* `Pill` — Border 560 × 60, `CornerRadius=30`, `Background=#FF312F30`, `ClipToBounds=True`,
  initial `Opacity=0` and `ScaleTransform 0.6` (`:41-51`).
* `RippleHost` Grid with a `TranslateTransform X` (`:53-58`) containing
  `RippleInnerHost`/`RippleMidHost`/`RippleOuterHost`, each with a `TranslateTransform Y=16`,
  and the ellipses `RippleInner` (160×160 filled `#FF656363`, initial scale 0, opacity 0),
  `RippleMid` (220×220 stroke `#FF656363` 5 px) and `RippleOuter` (280×280 stroke 3.5 px)
  (`:60-85`).
* `BoltIcon` Grid 32 × 32 with `TransformGroup { ScaleTransform 0.4, TranslateTransform X }`
  (`:89-98`) containing two forms:
  * `CircleForm` — white-ish `#FFE9E7E4` circle 32 px with `PathIcon CircleGlyph` 18 px
    (18 px glyph) using `Geo.Bolt` by default, foreground `#FF141313` (`:100-105`);
  * `SquareForm` (initial `Opacity=0`) — `#FFE9E7E4` Border 18 × 18, `CornerRadius=4.5`,
    inner `PathIcon SquareGlyph` 12 px (`:107-115`).
* `TitleHost` StackPanel (vertical, spacing 2, initial opacity 0) with
  `TagLineText` and `TitleText` (`:118-133`).
* `NumHost` Grid width 560 with the fixed column layout
  `32 | 18 | 14 | Auto | * | Auto | 14 | Auto | 5` (`:140-150`):
  * column 3 (`Auto`): horizontal `StackPanel` spacing 2 with `PrimaryText` then
    `SecondaryText` (`:152-162`);
  * column 5 (`Auto`): horizontal `StackPanel` spacing 1 with `RightText` then
    `RightSuffixText` (`:164-174`);
  * column 7 (`Auto`): the `Badge` Grid 46 × 46 (`:176-205`).

Fonts and colors (`Views/HudWindow.axaml` + `Styles/HudTheme.axaml`):

| Element | Font family resource | Size | Weight | Spacing | Opacity |
| --- | --- | --- | --- | --- | --- |
| `TagLineText` | `Hud.FontFamilyNumeric` | 9 | Medium | 2 | 0.40 |
| `TitleText` | `Hud.FontFamily` | 26 | Bold | 2 | 1 |
| `PrimaryText` | `Hud.FontFamilyNumeric` | 26 | Medium | — | 1 |
| `SecondaryText` | `Hud.FontFamilyNumeric` | 14 | Medium | — | 0.55 |
| `RightText` | `Hud.FontFamilyNumeric` | 22 | Medium | — | 1 |
| `RightSuffixText` | `Hud.FontFamilyNumeric` | 13 | Medium | — | 0.55 |

Refs: `HudWindow.axaml:123-132,153-173`; families at `Styles/HudTheme.axaml:13-14`:
`Hud.FontFamily = Inter, "Microsoft YaHei UI", "PingFang SC", sans-serif`;
`Hud.FontFamilyNumeric = "Inter Medium", Inter, "Microsoft YaHei UI", "PingFang SC", sans-serif`.
Theme palette (`Styles/HudTheme.axaml:4-12`): `Hud.Pill #FF312F30`, `Hud.IconPlate #FFE9E7E4`,
`Hud.BoltDark #FF141313`, `Hud.TextPrimary #FFFFFFFF`, `Hud.TextSecondaryOpacity 0.55`,
`Hud.TextTertiaryOpacity 0.40`, `Hud.Accent #FFC6CA4C`, `Hud.BadgeDark #FF262425`,
`Hud.PillRadius 43` (note: the pill itself uses 30; the 43 resource appears unused by the HUD
window in this revision — ambiguity).

### 5.2 Content-area semantics

| Slot | Template field | Position | Rendered into |
| --- | --- | --- | --- |
| Tagline | `TaglineTemplate` | above the title, centered | `TagLineText` |
| Title | `TitleTemplate` | center of the pill, title stage only | `TitleText` |
| Primary (left main) | `PrimaryTemplate` | left group, column 3, largest 26 px | `PrimaryText` |
| Secondary (left note) | `SecondaryTemplate` | immediately right of primary, 14 px, 55 % opacity | `SecondaryText` |
| Right status | `RightTemplate` | right group, column 5, 22 px | `RightText` |
| Right suffix | `RightSuffix` | immediately right of the status, 13 px, 55 % | `RightSuffixText` |
| Left icon | `LeftIcon` | 32 px plate at the final C-state X offset −245 | `CircleGlyph` **and** `SquareGlyph` geometry |
| Right icon | `RightIcon` | 46 px badge in column 7 | `BadgeGlyph` geometry, or the built-in "laptop" badge |

`HudWindow.ApplyRenderData` (`HudWindow.axaml.cs:322-367`) writes each text only when it
changed, and updates the left icon geometry for both forms (`:333-338`).
The right icon has a special case (`:340-350`): if `RightIcon` is `battery` **or** `laptop`
(case-insensitive), the hand-drawn `BadgeLaptop` (17 × 11.5 screen + 24 × 3 base) and
`BadgeElectrode` (9 × 3, radius 1,1,0,0) are shown and `BadgeGlyph` hidden; for any other
name `BadgeGlyph.IsVisible = true` and its `Data` is set from `IconCatalog.GetGeometry`.
The default `system.battery` profile therefore renders the laptop badge, **not** the
`battery` glyph.

Accent color (`:352-361`): parsed with `Color.Parse`, fallback `#C6CA4C`
(`TryColor`, `:425-429`); the resulting brush is applied to `BadgeArc.Stroke`,
`LaptopScreen.BorderBrush`, `LaptopBase.Background`, `BadgeElectrode.Background`,
`BadgeGlyph.Foreground`. The badge arc (`BadgeArc`, stroke 4.5, round caps) renders the
progress ring.

Icon left/right option: both are free-form icon identifiers entered through combo boxes
populated from `IconCatalog.Names` (`HudCustomizerView.axaml.cs:130-134`), so no
left/right-specific icon restriction exists; only the right-icon `battery`/`laptop`
special case above.

### 5.3 Progress ring

`HudWindow.axaml.cs`:

* Ring geometry: `BuildRingGeometry(fraction, diameter: 46, thickness: 4.5)` (`:437-460`);
  radius = `(diameter − thickness)/2`; start angle **−90°** (12 o'clock), sweep =
  `clamp(360·fraction, 0.5°, 359.5°)`; `IsLargeArc = sweep > 180°`;
  `SweepDirection.Clockwise`; single `ArcSegment`, open figure.
* Live smoothing: a 16 ms `DispatcherTimer` (`:47-48`) eases from the currently displayed
  fraction to the new target over `ProgressTransitionDuration = 320 ms` (`:38`), using
  `eased = 1 − (1−t)³` (cubic ease-out) (`:402-423`).
* Re-targeting rule (`SetProgressTarget`, `:369-400`): first value (or non-animated) snaps;
  a change smaller than `0.0005` snaps; otherwise the current displayed value becomes the
  new `from` (no snapping back to a previous target).
* `ApplyRenderData` only re-targets when `|previous.Progress − data.Progress| > 0.0005` and
  animates only when a previous render exists **and** the window is visible (`:363-364`).

### 5.4 Animation options (user-scaled)

`Animations/HudAnimations.cs:11-26`:

| Option | Setting | Default | Valid range (clamp) |
| --- | --- | --- | --- |
| `DurationSeconds` | `AppSettings.DisplayDurationSeconds` | 6.0 | 3 … 10 |
| `BounceStrength` | `AppSettings.BounceStrength` | 0.275 | 0 … 0.5 |
| `RippleIntensity` | `AppSettings.RippleIntensity` | 1.0 | 0 … 2 |
| `RippleSpread` | `AppSettings.RippleSpread` | 1.0 | 0.5 … 1.5 |

All timelines are created with `FillMode.Forward` and
`Duration = clamp(DurationSeconds, 3, 10)` (`:265-275`).

### 5.5 Cue → time mapping

Full timeline cues (`:31-51`): `BaselineSeconds = 6.0`, `IntroEndCue = 0.42`,
`TStart 0.04`, `TAppear 0.07`, `TPillOut 0.09`, `TBoltPop 0.10`, `TExpand 0.12`,
`TMove 0.20`, `TTitle 0.25`, `THoldB 0.30`, `TContract 0.36`, `THoldC 0.86`, `TClose 0.89`,
`TNumIn 0.38`, `TNumReady 0.42`.

`MapCue` (`:61-68`): with `d = clamp(DurationSeconds,3,10)` and
`introFrac = 0.42 · 6 / d`:
`cue <= 0.42` → `cue / 0.42 · introFrac`; else
`introFrac + (cue − 0.42)/0.58 · (1 − introFrac)`.
At the default `d = 6` this is the identity mapping.

Simple timeline (`:206-210`): `SimpleBaselineSeconds = 5.0`,
`SimpleIntroEndCue = 0.08`, `TSimpleAppear 0.05`, `TSimpleHold 0.75`,
`TSimpleClose 0.80`; `MapCueSimple` (`:212-219`) uses the same formula with
`introFrac = 0.08 · 5 / d`.

Easings (`:53-59`):
`KS_In = cubic(0.42,0,1,1)`, `KS_Out = cubic(0,0,0.58,1)`,
`KS_InOut = cubic(0.42,0,0.58,1)`, `KS_Smooth = cubic(0.65,0,0.35,1)`,
`BackOut(o) = cubic(0.175, 0.885, 0.32, 1 + BounceStrength)`.

### 5.6 Full animation — state sequence and what it does

Targets and exact setters (all `Setter`s on Avalonia properties):
`Op`→`Visual.Opacity`, `TX`/`TY`→`TranslateTransform.X/Y`, `SX`/`SY`→`ScaleTransform.ScaleX/Y`,
`CR`→`Border.CornerRadius`, `H`→`Border.Height` (`:287-294`).

| Animation | Target | Cue sequence (setters) | Ref |
| --- | --- | --- | --- |
| `PillCorner` | `Pill` | 0.00 r=30 → 0.07 r=30 → 0.12 r=18 → 0.30 r=18 → 0.36 r=30 | `:70-79` |
| `PillAppear` | `Pill` | 0.00/0.04/0.07 opacity 0 scale 0.6 → 0.09 (BackOut) opacity 1 scale 1 → 0.86 hold | `:81-90` |
| `PillHeight` | `Pill`, `RippleHost` | 0.00 h=60 → 0.07 h=60 → 0.12 (BackOut) h=90 → 0.30 h=90 → 0.36 h=60 → 0.86 h=60 | `:92-102`, bound twice at `HudWindow.axaml.cs:127-128,243-244` |
| `ScaleOut` | `ScaleHost` | 0.00 s=1 → 0.86 s=1 → 0.89 s=0 | `:104-111` |
| `BoltIcon` | `BoltIcon` | 0.00/0.04 opacity 0 scale 0.4 X=0 → 0.10 (BackOut) opacity 1 scale 1.12 → 0.12 scale 1 → 0.20 (Smooth) X=−179 → 0.30 X=−179 → 0.36 (Smooth) X=−245 → 0.86 X=−245 | `:113-125` |
| `RippleHost` | `RippleHost` | X: 0 → 0.12 X=0 → 0.20 X=−179 → 0.30 X=−179 → 0.36 X=−245 | `:127-136` |
| `CircleForm` | `CircleForm` | opacity 0 @0.00 → 0 @0.04 → **1** @0.07 (KS_Out) → 1 @0.30 → **0** @0.36 | `:138-147` (cues 0, TStart, TAppear, THoldB, TContract) |
| `SquareForm` | `SquareForm` | opacity 0 through 0.30 → 0.36 opacity 1 | `:149-156` |
| `TitleHost` | `TitleHost` | 0 → 0.20 0 → 0.25 1 → 0.30 1 → 0.36 0 | `:158-167` |
| `NumHost` | `NumHost` | 0 → 0.36 0 → 0.38 0 → 0.42 1 → 0.86 1 | `:169-178` |
| `RippleRise` | `RippleInnerHost`, `RippleMidHost`, `RippleOuterHost` | TY 16 → 0.12 16 → 0.14 16 → 0.30 0 → 0.36 0 | `:180-189` |
| `Ripple(o, endScale, peakOp)` | `RippleInner`(1.5,0.50), `RippleMid`(2.0,0.50), `RippleOuter`(2.5,0.60) | target = `endScale · RippleSpread`; peak = `min(1, peakOp · RippleIntensity)`; opacity 0/scale 0 → 0.14 → 0.14 opacity peak scale 0.05 → 0.30 opacity peak scale target → 0.36 opacity 0 scale target | `:191-204`, call sites `HudWindow.axaml.cs:136-138,251-253` |

Sequence as documented in the task brief, mapped to code: the **title/ripple/shape phase**
(cues 0.00–0.36) is the "隐去 → 内容切换 → 唤出" style entrance in which the circle form is
replaced by the square form and the title fades out; the **C state** (cue ≥ ~0.42, held to
0.86) is the final pill with the square icon at X = −245, number host visible, title hidden;
`ScaleOut`/`TClose` (0.89) collapses `ScaleHost` to 0 for transient shows.

Transient show (`ShowCustomAsync`, `HudWindow.axaml.cs:85-164`) runs the full set above
(including `ScaleOut`, `:124-141`) and hides the window when the timeline completes
(`:157-162`). Persistent show (`ShowPersistentAsync`, `:201-277`) runs the **same set minus
`ScaleOut`** (`:240-256`) and then calls `SetPersistentFinalState()` (`:259`, implemented
`:530-557`).

### 5.7 Simple animation — state sequence

Enabled when `AnimationMode != "Full"` (case-insensitive)
(`HudProfileRenderer.cs:32`; `SimpleAnimation = !Equals(profile.AnimationMode, "Full")`).

| Animation | Target | Cue sequence | Ref |
| --- | --- | --- | --- |
| `SimplePillAppear` | `Pill` | 0.00 opacity 0 scale 0.6 → 0.05 opacity 1 scale 1 → 0.75 hold | `:221-228` |
| `SimpleFadeIn` | `BoltIcon`, `NumHost` | 0.00 0 → 0.05 0 → 0.08 1 → 0.75 1 | `:230-238` |
| `SimpleScaleOut` | `ScaleHost` | 1 → 0.75 1 → 0.80 0 | `:240-247` |

Transient simple show (`ShowCustomAsync`, `:113-121`): `SetSimpleCState()` then the three
simple animations **including** `SimpleScaleOut`, which collapses the host to nothing.
Persistent simple show (`:230-237`): the same three minus `SimpleScaleOut`, then
`SetPersistentFinalState()`.

### 5.8 The two C states

`SetSimpleCState()` (`HudWindow.axaml.cs:502-528`): pill 560 × 60 radius 30 **opacity 0**,
scale 0.6; `BoltIcon` scale 1, X = −245, opacity 0; `CircleForm` 0; `SquareForm` 1;
`TitleHost` 0; `RippleHost.Height = 60`, X = 0; ripple hosts Y = 16; ripples opacity 0;
`NumHost` X = 0 opacity 0. This is the *starting* state for the simple timeline — the pill
fades in and the number host fades in next.

`SetPersistentFinalState()` (`:530-557`): `Root` opacity 1, `ScaleHost` scale 1,
pill 560 × 60 radius 30 **opacity 1** scale 1; `BoltIcon` scale 1 X = −245 opacity 1;
`CircleForm` 0; `SquareForm` 1; `RippleHost.Height = 60` X = −245; all ripples opacity 0;
`TitleHost` opacity 0; `NumHost` X = 0 opacity 1.
Note the persistent final state keeps the **square** form (not the circle) and keeps the
pill fully opaque.

`HideAnimatedAsync` (`:295-320`): cancels live refresh, forces
`SetPersistentFinalState()` and runs `CloseFromC()` = 180 ms scale 1 → 0 with `KS_In`
(`HudAnimations.cs:253-263`), then hides the window. This is the only retract used for
persistent/close/settings-switch paths, which is why a settings switch is always
"retract → apply → summon" (`CustomHudRuntime.cs:82-89`).

`ResetToInitial()` (`:468-500`) restores the document-order initial states before each show.

### 5.9 Positioning, scale, opacity, layer, click-through

* Position (`PositionHud`, `HudWindow.axaml.cs:559-608`): uses the target screen's
  `WorkingArea`; `hudWidthPx = 560 · GlobalScale · scaling`,
  `hudHeightPx = 60 · GlobalScale · scaling`; window padding
  `paddingX = max(0,(windowWidthPx − hudWidthPx)/2)`, same for Y;
  `const margin = 16`.
  * `CustomCoordinates` → `area.X + HudCustomX`, `area.Y + HudCustomY` (offsets ignored).
  * Preset → left/center/right per `HudPosition`, top/center/bottom per `HudPosition`,
    then `+= HudOffsetX/HudOffsetY`.
  * final window position = `round(hudLeft − paddingX)`, `round(hudTop − paddingY)`.
* Monitor (`ResolveScreen`, `:610-619`): `MonitorIndex < 0` → primary; else
  `Screens.All[MonitorIndex]`; out of range → primary.
* Opacity: `Opacity = clamp(settings.HudOpacity, 0…1)` (`:64`); persisted clamp 0.10…1.0
  (`SettingsManager.cs:151`); slider 10…100 % (`SettingsWindow.axaml:124`).
* Scale: `GlobalScale.RenderTransform = ScaleTransform(scale, scale)` (`:63`);
  UI range 0.4…1.4 (`SettingsWindow.axaml:188`), default 0.8 (`AppSettings.cs:7`).
* Layer (`ApplyWindowLayer`, `:71-83`): `Topmost = !persistent || PersistentLayer == Topmost`.
  Transient HUD is always topmost.
* Click-through (`EnsureInputHitTest`, `:652-672`; `Interop/WindowsHudHitTest.cs`): forces
  `WS_EX_LAYERED | WS_EX_TRANSPARENT` (`:15-18`), refreshes the frame with `SetWindowPos`
  (`:82-83`), verifies by reading back, retries/logs on failure; `Reapply()` is called after
  every show/topmost change. The `IsPointInsideVisibleHud` predicate is passed in but
  **ignored** by `TryAttach` (`WindowsHudHitTest.cs:38-52`): the whole 1200 × 160 window is
  mouse-through. `IsPointInsideVisibleHud` (`HudWindow.axaml.cs:674-693`) remains for
  reference (it computes the visible pill rect with 4 px tolerance and uses the *current*
  animated `Pill.Height`, clamped to ≥ 60).
* Trigger hot zone (`IsPointInTopCenterHotZone`, `:636-650`): default
  `width = 240, height = 5`; halfWidth = `max(40,width/2)`; hotHeight = `max(2,height)`;
  matches a screen point within the horizontal centre band of the target monitor and within
  the top 5 px of the monitor **bounds** (not working area).

### 5.10 Trigger policy (transient mode)

`CustomHudRuntime.TickTransientTriggersAsync` (`:246-297`), evaluated on the 100 ms timer:

1. `PollPowerSource` (`:299-333`, polled at most every 150 ms): `_lastAcOnline` must change,
   be confirmed twice, and remain stable for **≥ 400 ms**; then a power event is queued.
   The battery profile is shown with `AnimationMode = acOnline ? "Full" : "Simple"`
   (`:251-258`).
2. DeepSeek peak/off-peak transition while the active profile is a DeepSeek profile →
   transient full show (`:260-272,335-361`).
3. Pointer hot zone: only when not persistent; requires the cursor inside the top-centre
   zone; latched so it fires once per entry; **battery profiles never trigger on hot zone**
   (`:284-296`).
   Cursor acquisition uses `WindowsSystemProbe.TryGetCursorPosition` (`Interop/WindowsSystemProbe.cs:35-42`).
4. `AlwaysVisible` mode ignores the hot zone entirely (`:230-235`).

Battery-profile detection (`IsBatteryProfile`, `:570-575`): `BuiltInKey == "system.battery"`
**or** (`Category` ∈ {系统, System} and `Name` ∈ {电池, Battery}), falling back to the
canonical built-in if no such profile exists (`FindBatteryProfile`, `:562-568`).
DeepSeek-profile detection (`IsDeepSeekProfile`, `:577-581`): `BuiltInKey ==
"deepseek.balance-period"` **or** `Category == "DeepSeek API"` and `Name` contains 余额 or
Balance.

### 5.11 Persistent live refresh

* `TickPersistentAsync` decides clock-accurate vs 1 s cadence (`:445-456`).
  `NeedsSecondAccurateClock` (`HudProfileRenderer.cs:74-79`) is true when the profile is a
  time profile (`BuiltInKey == "time.day-progress"` or `Category` ∈ {时间, Time},
  `:187-190`) or requires `system.time`, any `time.*`, or any `deepseek.period.*`.
* Clock-accurate profiles re-render at most once per local wall-clock second
  (`:449-452`) to avoid skipped seconds; others every ≥ 1 s (`:514`).
* While the HUD is visible, `RefreshLiveDataAsync` (`HudWindow.axaml.cs:166-199`) polls
  every 50 ms and applies a new render at most once per second, so numbers keep updating
  during the animation tail.
* A render is applied in place via `UpdatePersistent(data)` when neither the profile nor the
  DeepSeek period changed (`:503-511`); otherwise the HUD is retracted and re-summoned
  (`:497-502`).

---

## 6. Settings model and persistence

### 6.1 `AppSettings` — every persisted field

`Settings/AppSettings.cs:5-39`; constants at `:7-12`.

| Property | Type | Default | Valid range / values | Notes |
| --- | --- | --- | --- | --- |
| `HudEnabled` | bool | `true` | bool | master switch; **forced to `true` on every launch** (`SettingsManager.cs:38,48-50`), may be turned off for the session |
| `StartWithWindows` | bool | `false` | bool | writes HKCU `...\Run` value `Endfield Charge Plus` = `"<exe>" --autostart` (`StartupManager.cs:8-9,29`) |
| `UiLanguage` | string | `"Auto"` | `Auto` \| `zh-CN` \| `en-US` | normalized by `LocalizationManager.NormalizePreference` |
| `GlobalScale` | double | `0.8` | UI 0.4…1.4 | `DefaultGlobalScale` (`:7`) |
| `DisplayDurationSeconds` | double | `6.0` | UI 3…10 (clamped again in `AnimationOptions`) | `:8` |
| `BounceStrength` | double | `0.275` | UI 0…0.5 | `:9` |
| `RippleIntensity` | double | `1.0` | UI 0…2 | `:10` |
| `RippleSpread` | double | `1.0` | UI 0.5…1.5 | `:11` |
| `HudOpacity` | double | `1.0` | persisted `clamp(…,0.10,1.0)` (`SettingsManager.cs:151`); UI slider 10…100 % | `:12` |
| `AlwaysVisible` | bool | `false` | bool | persistent vs on-demand |
| `PersistentLayer` | enum `PersistentHudLayer` | `Desktop` | `Topmost`=0, `Desktop`=1 | persisted as **number** |
| `PositionMode` | enum `HudPositionMode` | `Preset` | `Preset`=0, `CustomCoordinates`=1 | persisted as number |
| `HudPosition` | enum `HudPosition` | `TopCenter` | `TopLeft`=0 … `BottomRight`=8 (row-major, `SettingsWindow.axaml.cs:697-723`) | persisted as number; `TopCenter`=1 |
| `HudOffsetX` | int | `0` | UI −10000…10000 | preset mode only |
| `HudOffsetY` | int | `0` | UI −10000…10000 | preset mode only |
| `HudCustomX` | int | `0` | UI −10000…20000 | custom mode |
| `HudCustomY` | int | `0` | UI −10000…20000 | custom mode |
| `MonitorIndex` | int | `-1` | `-1` = primary/auto; else 0-based screen index | combo index 0 ⇒ −1 (`SettingsWindow.axaml.cs:329`) |
| `CustomHud` | `CustomHudSettings` | `CreateDefault()` | §4.3 | nested object |

Enum persistence: `SettingsManager.JsonOptions` sets only `WriteIndented` and
`PropertyNameCaseInsensitive` (`SettingsManager.cs:11-15`); there is **no**
`JsonStringEnumConverter`, so enums serialize as integers (`PersistentLayer: 1`,
`PositionMode: 0`, `HudPosition: 1`). Any Android importer must accept numbers (and should
tolerate the C# enum names case-insensitively for robustness — that tolerance is *not* in the
Windows code).

Position enum ↔ index mapping (`SettingsWindow.axaml.cs:697-723`):
0 TopLeft, 1 TopCenter, 2 TopRight, 3 CenterLeft, 4 Center, 5 CenterRight, 6 BottomLeft,
7 BottomCenter, 8 BottomRight. UI labels (`RebuildLocalizedChoiceItems`, `:150-153`):
`左上/顶部居中/右上/左侧居中/屏幕居中/右侧居中/左下/底部居中/右下` ↔
`Top Left/Top Center/Top Right/Center Left/Center/Bottom Left/Bottom Center/Bottom Right`
(English list in code: `"Center Right"` for index 5 — note the zh/en arrays are aligned).

Other settings-window semantics worth porting:

* Transparency label = `round(slider)` + `"%"` (`:488-491`).
* "Reset size & animation" restores the five constants (`:479-486`); "Reset all" loads
  defaults into the editor **without saving** (`:468-477`).
* Save (`OnSave`, `:334-360`): collect from UI → `SettingsManager.Save` →
  `StartupManager.Apply` → `_runtime.ApplySettingsWithTransitionAsync` → button shows
  `应用中…/Applying…` then `已保存/Saved`, reverting to `保存并应用/Save & Apply` after
  1.2 s.
* Language buttons persist **only** the language immediately (`:160-179`).
* Monitor combo (`:236-255`): item 0 = `主显示器（自动）/Primary Display (Auto)`,
  then `显示器 {i+1} · {w}×{h}` (+ ` · 主/ · Primary`), labels translated on language change
  (`ProcessStartInfo` style, no code change required for Android re-layout).
* Update check: GitHub API `releases/latest` then `tags?per_page=1`
  (`:617-652`) on repo `GlacierGlimmer/zmd-charge-plus`; version normalization strips a
  leading `v`/`V` and any `-`/`+` suffix (`:654-665`); status kinds at `:24-34`; runs at
  startup (`App.axaml.cs:136-158`) and shows a tray notification when an update exists.

### 6.2 Persistence format and location (Windows)

`Settings/SettingsManager.cs`:

* Directory: `%LOCALAPPDATA%\EndfieldChargePlus` (`:17-19`), file `settings.json` (`:21`).
* Backups directory `%LOCALAPPDATA%\EndfieldChargePlus\Backups` (`:22`); the last good file
  is copied to `settings.previous.json` before every save (`:88-89`); manual/import backups
  are `settings.{reason}-{yyyyMMdd-HHmmss}.json` (`:134`) and corrupt files are backed up as
  `settings.corrupt-{timestamp}.json` (`:162`); only the newest 20 backups are kept (`:173-187`).
* Legacy migration paths, tried in order when `settings.json` is absent (`:24-28,32-34`):
  `%LOCALAPPDATA%\EndfieldCharge-CustomHUD\settings.json`, then
  `%LOCALAPPDATA%\EndfieldCharge\settings.json`; a successful load is immediately written to
  the new path (`:52-56`).
* Write is atomic-ish: serialize → `settings.json.tmp` → `File.Move(..., overwrite:true)`
  (`:81-93`). Save failure rethrows after deleting the temp file and logging (`:96-101`).
* Load flow (`:30-70`): no file → first-run defaults with `HudEnabled=true`;
  parse → `Normalize` → force `HudEnabled=true`; parse failure → log, back up the corrupt
  file, save and return defaults.
* `Normalize` (`:148-154`): clamp opacity, normalize language, normalize `CustomHud`.
* JSON: indented, property names as declared (PascalCase), case-insensitive on read
  (`:11-15`), `null` values are written (no `DefaultIgnoreCondition`).
* Related paths: logs `%LOCALAPPDATA%\EndfieldChargePlus\Logs` with
  `EndfieldChargePlus-yyyyMMdd.log` + mirrored `latest.log`, 14 files kept
  (`Diagnostics/AppLog.cs:16-21,30-31,71-95`);
  Linux-only data root logic in `Interop/AppPaths.cs:5-18` (`$XDG_DATA_HOME/EndfieldChargePlus`)
  is present but unused on Windows and irrelevant to Android except as naming precedent.

### 6.3 Import / export

`SettingsManager.ExportToFile(destinationPath, settings)` (`:104-116`): normalizes, creates
the directory, writes the same indented JSON.
`ImportFromFile(sourcePath)` (`:118-126`): requires an existing file, normalizes, returns the
settings (does not save).
UI (`SettingsWindow.axaml.cs:362-425`): export suggests
`EndfieldChargePlus-settings-{yyyyMMdd-HHmmss}.json` with `*.json` filter; import backs up
the current file as `settings.before-import-*.json` first, then saves, applies autostart,
re-initializes language, re-applies localization, reloads controls and runs the HUD
transition. Failure paths log and show a status line.
`BackupCurrent(reason)` (`:128-139`) returns the backup path or `null` when no file exists.

### 6.4 API-key storage

`Customization/SecretStore.cs` (Windows): `ProtectedData.Protect/Unprotect` with
`DataProtectionScope.CurrentUser`, UTF-8, Base64 (`:9-31`). Any failure returns `""`
(never throws). The encrypted string is stored in `CustomHudSettings.DeepSeekApiKeyProtected`
as Base64. Android cannot unprotect DPAPI blobs; the field must be treated as
platform-opaque and re-entered (the Android port needs its own keystore-backed scheme, e.g.
prefix-tagged like the Linux `linux-aesgcm-v1:` convention documented in
`zmd-charge-plus-for-linux`/`Tests/Program.cs:96-100`).

### 6.5 Single instance / startup

* Mutex `Local\EndfieldChargePlus.SingleInstance`, activation event
  `Local\EndfieldChargePlus.Activate` (`Program.cs:12-13`); a second non-autostart launch
  signals the first (which opens Settings) and exits; autostart second launches exit silently
  (`:38-56`). `--autostart` argument detected case-insensitively (`:24`).
* Shutdown mode is `OnExplicitShutdown` (`App.axaml.cs:31`); tray icon only on Windows
  (`:162-182`); tray menu items Preview / Settings / Exit (`:184-220`).

---

## 7. Localization

### 7.1 Structure — there is no resource-key system

Localization is code-based, in three independent layers:

1. **Static UI dictionary** `LocalizationManager.ZhToEn` (`LocalizationManager.cs:22-178`,
   ≈145 entries). **The Simplified-Chinese literal is the key**; the English string is the
   value. `EnToZh` is built by reversing it with `StringComparer.Ordinal` (`:180`), so the
   English values must be unique for reverse lookup to work.
   `TranslateLiteral(value)` looks up `ZhToEn` when English, `EnToZh` otherwise, and returns
   the input unchanged on miss (`:217-223`).
   `ApplyStaticText(Control root)` walks the logical tree and rewrites `Window.Title`,
   `TabItem.Header`, `ToggleSwitch.OnContent/OffContent`, `TextBox.Watermark`,
   `TextBlock.Text`, `ContentControl.Content` (`:225-253`).
   **Implication for Android:** these are UI-shell strings, not part of the HUD data contract;
   they can be re-authored as Android string resources, but the HUD-visible strings in the
   same dictionary (e.g. `系统状态`) must keep their meaning.
2. **Inline runtime strings** `LocalizationManager.Text(zh, en)` (`:215`) — used at every
   place where a generated value must be localized (battery status, probe status, DeepSeek
   period text, security statuses, …). Both variants are hard-coded at the call site.
   Representative call sites: `VariableHub.cs:585-590` (battery status),
   `:611` (`未知`/`Unknown`), `:615` (`交流电源`/`AC`, `电池`/`Battery`),
   `:842` (`检测失败`/`Failed`), `:862` (waiting), `:985,1032,1080` (`在线`/`Online`),
   `:1101-1112` (IP status), `:1368` (`可用`/`Available`), `:1411-1416` (period text),
   `AdvancedVariableProvider.cs:834` (TRIM), `:1089-1151` (security statuses),
   `HudProfileRenderer.cs:119-133` (time status text).
3. **Domain-specific localizers**:
   * `Customization/BuiltInProfileLocalization.cs` — built-in profile category/name/title/right
     template (§4.8).
   * `Customization/VariableLocalization.cs` — the Variable Library text: `CategoryMap`
     (22 entries, `:10-34`), `TokenMap` (53 entries, `:36-51`), `HumanizeKey`
     (`:110-117`: drops the first dotted segment, then title-cases `_`-split words),
     `LocalizeUnit` (`:132-148`), `LocalizeUse` (`:150-167`), `BuildDescription` (`:90-108`).
     Applied via `VariableLocalization.Localize(item)` and returns the item unchanged unless
     `LocalizationManager.IsEnglish` (`:55-56`).

### 7.2 Language selection

* `AppLanguage { SimplifiedChinese, English }` (`:10-14`). Static default before init is
  **English** (`:182`) — `Initialize` is called from `App.axaml.cs:35` with
  `AppSettings.UiLanguage`.
* `NormalizePreference` (`:199-204`): `"zh-CN"` (case-insensitive) → `"zh-CN"`,
  `"en-US"` → `"en-US"`, anything else (including empty) → `"Auto"`.
* `Initialize` (`:186-197`): `zh-CN` → SimplifiedChinese; `en-US` → English;
  `Auto` → SimplifiedChinese when `CultureInfo.CurrentUICulture.Name` starts with `zh`
  (case-insensitive), else English.
* `PreferenceFor(language)` (`:206`) maps back to `en-US`/`zh-CN`.
* `SetLanguage` fires `LanguageChanged` when the value actually changes (`:208-213`).
  The HUD/settings re-read `Current` during the next render/refresh;
  `CustomHudRuntime`/`VariableHub`/`AdvancedVariableProvider` detect the change and reset
  language-sensitive caches (`VariableHub.cs:110-115`, `AdvancedVariableProvider.cs:72-76,108-123`).
* UI: language is persisted immediately on button click (`SettingsWindow.axaml.cs:160-179`);
  import re-initializes it from the imported file (`:407`).

### 7.3 Key naming convention and examples

Because the Chinese literal *is* the key, the "convention" is a set of families:

| Family | Example keys (zh) | Example values (en) | Ref |
| --- | --- | --- | --- |
| Settings shell | `显示与位置`, `HUD 内容与数据`, `关于` | `Display & Position`, `HUD Content & Data`, `About` | `:25-75` |
| Switches | `HUD 总开关`, `开机启动`, `一直显示`, `自动轮播` | `HUD Master Switch`, `Start with Windows`, `Always Visible`, `Auto Cycle` | `:31,33,36,114` |
| Position/geometry | `预设位置`, `X 微调 / px`, `X 坐标 / px`, `恢复默认` | `Preset`, `X Offset / px`, `X / px`, `Reset` | `:45,46,49,52` |
| Sizing/animation | `HUD 缩放`, `总时长 / 秒`, `回弹强度`, `波纹强度`, `波纹幅度` | `HUD Scale`, `Duration / s`, `Bounce`, `Ripple Strength`, `Ripple Spread` | `:53-57` |
| Profile editor | `方案管理`, `当前方案`, `新建`, `保存`, `删除`, `方案名称`, `动画模式` | `Profile Management`, `Active Profile`, `New`, `Save`, `Delete`, `Profile Name`, `Animation` | `:78-110` |
| Display content | `标题阶段`, `上行文字`, `主标题`, `左侧主体信息`, `主值`, `次值 / 补充文字`, `右侧状态值`, `状态值`, `后缀` | `Title Stage`, `Tagline`, `Title`, `Left Main Info`, `Primary`, `Secondary / Note`, `Right Status`, `Status`, `Suffix` | `:128-139` |
| Icons/ring | `图标与进度环`, `进度变量`, `最小值`, `最大值`, `左侧图标`, `右侧图标` | `Icons & Progress Ring`, `Progress Value`, `Minimum`, `Maximum`, `Left Icon`, `Right Icon` | `:141-147` |
| Colors | `颜色`, `默认强调色`, `条件变色 · 每行：变量 运算符 数值 => 颜色` | `Color`, `Accent Color`, `Color rules · one per line: variable operator value => color` | `:148-150` |
| Variable library | `变量库`, `变量名`, `模板写法`, `数据属性`, `类型`, `单位`, `使用建议`, `常用格式` | `Variables`, `Variable Key`, `Template`, `Data Properties`, `Type`, `Unit`, `Recommended Use`, `Formats` | `:153-166` |
| Data sources | `数据源`, `工作日高峰窗口（北京时间，分号分隔）` | `Data Sources`, `Weekday peak windows (Beijing time; separate with semicolons)` | `:169-172` |
| Tray | `预览 HUD`, `设置`, `退出` | `Preview HUD`, `Settings`, `Exit` | `:175-177` |
| About | `终末地风格状态栏 HUD`, `构建日期  2026.09.23`, `当前版本：v0.1.0`, `项目与协议`, `开源协议`, `本项目 GitHub`, `原项目 GitHub`, `项目网站` | `Endfield-style Status HUD`, `Build  2026.09.23`, `Current: v0.1.0`, `Project & License`, `License`, `Project GitHub`, `Upstream GitHub`, `Website` | `:58-69` |

Note the literal double spaces inside `构建日期  2026.09.23` and the full-width characters in
`条件变色 · 每行：变量 运算符 数值 => 颜色` — both are part of the exact key
(`LocalizationManager.cs:59,150`).

### 7.4 Built-in profile text is localized separately

Yes — built-in profile `Category`/`Name`/`TitleTemplate` (and `network.ping.RightTemplate`)
are **not** in `ZhToEn`; they are produced by `BuiltInProfileLocalization.ForLanguage`
(§4.8). Notable behaviour:

* The stored profile keeps its Chinese values; translation happens at render/extract time
  (`HudProfileRenderer.cs:12,37`).
* The English `deepseek.balance-period` title is `DeepSeek {deepseek.period.name}` while the
  Chinese one is `DeepSeek 当前{deepseek.period.name_zh}` (`BuiltInProfileLocalization.cs:45-49`).
* The English `network.ping` right template is `Loss {probe.loss_percent|0}` vs Chinese
  `丢包{probe.loss_percent|0}` (`:50-54`).
* A custom profile's name is never translated; it is whatever the user typed.
* `app.preset_name` uses `DisplayName`, so it reflects the current language
  (`AdvancedVariableProvider.cs:408`).

### 7.5 Localized value strings that reach templates (port-critical)

| Variable/value | zh | en | Ref |
| --- | --- | --- | --- |
| `battery.status_text` | 充电中 / 使用电池 / 已接通电源 / 未知 | Charging / On Battery / AC Connected / Unknown | `VariableHub.cs:584-590` |
| `battery.power_source` | 交流电源 / 电池 | AC / Battery | `:615` |
| `battery.time_remaining_text` | 未知 | Unknown | `:611` |
| `probe.status_text`/`ping.status_text` | 在线 / 超时 / 主机不可达 / 网络不可达 / 端口不可达 / 目标无效 / 路由无效 / TTL 已过期 / 不可达 / 检测失败 / 连接失败 / 等待检测 | Online / Timed out / Host unreachable / Network unreachable / Port unreachable / Invalid target / Invalid route / TTL expired / Unreachable / Failed / Connection failed / Waiting | `:985,1032,1037,1043,1080,1085,1091,1101-1112,842,862` |
| `probe.error` placeholder | 尚未完成检测 | Probe not completed | `:862` |
| `deepseek.available_text` | 可用 / 不可用 | Available / Unavailable | `:1368` |
| `deepseek.period.name_zh` | 高峰 / 低谷 | PEAK / OFF-PEAK (English UI) | `:1409,1419` |
| `deepseek.period.remaining_text` | 高峰时段剩余HH:MM:SS | Peak left HH:MM:SS | `:1411-1413` |
| `deepseek.period.progress_text` | 高峰已过n% | Peak n% | `:1414-1416` |
| `deepseek.period.timezone` | 北京时间 (UTC+08:00) | Beijing Time (UTC+08:00) | `:1439` |
| `time.target.remaining_text` / `time.display.status_text` (target mode) | 剩余n% | Left n% | `HudProfileRenderer.cs:119-128` |
| `system.uptime_text`, `app.uptime_text` | `n天 HH:MM:SS` | `nd HH:MM:SS` | `VariableHub.cs:1570-1577` |
| `security.*` statuses | 已启用 / 未启用 / 实时保护关闭 / 已保护 / 未保护 / 等待重启 / 正常 / 有可用更新 / 已关闭 / 未知 / 域 / 专用 / 公用 | Enabled / Disabled / Real-time protection off / Protected / Not protected / Restart pending / OK / Updates available / Off / Unknown / Domain / Private / Public | `AdvancedVariableProvider.cs:1089,1112,1122,1145,1151,1107,1138` |
| `battery.chemistry` | 锂离子 / 锂聚合物 / … | Li-ion / Li-polymer / … | `:1214-1225` |
| `disk.<l>.trim_status` | 启用 / 禁用 | Enabled / Disabled | `:834` |
| `dev.llm.local_status` | 运行中 / 未运行 | Running / Not running | `:528` |
| `app.theme` | 深色 | Dark | `:404` |
| Template engine generated | `{n}秒/分钟/小时/天/个月/年` + 前/后, `{n}天 HH:MM:SS` | `{n}s/m/h/d/mo/y`, `in`/`ago`, `{n}d HH:MM:SS` | `TemplateEngine.cs:282-325` |
| `time.day_of_week` | 星期一 … 星期日 | Monday … (via `DayOfWeek.ToString()`) | `VariableHub.cs:1556-1568` |

---

## 8. Icon catalog

`Customization/IconCatalog.cs`. Geometry is an Avalonia path mini-language string rendered by
`StreamGeometry.Parse`; unknown/blank names fall back to `bolt` (`:60-65`). The exported order
is `IconCatalog.Names` (`:51-58`), 35 identifiers.

| # | Identifier | Meaning / intended use (interpretation of the glyph) | Line |
| --- | --- | --- | --- |
| 1 | `bolt` | lightning bolt — default/fallback; battery profile left icon | `:12` |
| 2 | `cpu` | CPU die with pins | `:13` |
| 3 | `memory` | memory module with contact pins | `:14` |
| 4 | `network` | three-bar wireless/network arcs with dot | `:15` |
| 5 | `disk` | drive enclosure with platter window | `:16` |
| 6 | `clock` | analog clock | `:17` |
| 7 | `api` | boxed "API" plug/chip shape | `:18` |
| 8 | `gpu` | expansion card with fan vents | `:19` |
| 9 | `battery` | battery body with terminal | `:20` |
| 10 | `laptop` | laptop outline with base | `:23` |
| 11 | `monitor` | monitor with stand | `:24` |
| 12 | `server` | three stacked server units with LEDs | `:25` |
| 13 | `database` | cylinder database | `:26` |
| 14 | `cloud` | cloud | `:27` |
| 15 | `wifi` | Wi-Fi arcs | `:28` |
| 16 | `signal` | ascending signal bars | `:29` |
| 17 | `ethernet` | RJ45 plug with pins | `:30` |
| 18 | `download` | down arrow into tray | `:31` |
| 19 | `upload` | up arrow out of tray | `:32` |
| 20 | `thermometer` | thermometer with bulb | `:33` |
| 21 | `fan` | four-blade fan | `:34` |
| 22 | `gauge` | gauge arc with needle | `:35` |
| 23 | `calendar` | calendar grid with rings | `:36` |
| 24 | `timer` | stopwatch | `:37` |
| 25 | `wallet` | wallet with clasp and card slot | `:38` |
| 26 | `coin` | coin with "S"-like mark | `:39` |
| 27 | `terminal` | terminal window with `>_` prompt | `:40` |
| 28 | `code` | angle brackets with slash | `:41` |
| 29 | `globe` | globe with meridian | `:42` |
| 30 | `activity` | ECG/activity trace | `:43` |
| 31 | `settings` | gear with centre hole | `:44` |
| 32 | `shield` | shield with check | `:45` |
| 33 | `power` | power symbol | `:46` |
| 34 | `link` | two chain links | `:47` |
| 35 | `refresh` | two curved refresh arrows | `:48` |

Exact geometry (verbatim from `IconCatalog.cs:12-48`):

```text
bolt        = M10,1 L4,11 L9,11 L7,19 L16,8 L11,8 Z
cpu         = M5,5 L19,5 L19,19 L5,19 Z M9,9 L15,9 L15,15 L9,15 Z M8,1 L8,4 M12,1 L12,4 M16,1 L16,4 M8,20 L8,23 M12,20 L12,23 M16,20 L16,23 M1,8 L4,8 M1,12 L4,12 M1,16 L4,16 M20,8 L23,8 M20,12 L23,12 M20,16 L23,16
memory      = M3,6 L21,6 L21,18 L3,18 Z M6,9 L18,9 L18,14 L6,14 Z M6,18 L6,21 M10,18 L10,21 M14,18 L14,21 M18,18 L18,21
network     = M12,3 C7,3 3,6 1,9 L4,12 C6,10 9,8 12,8 C15,8 18,10 20,12 L23,9 C21,6 17,3 12,3 Z M12,10 C9,10 7,11 5,14 L8,17 C9,16 10,15 12,15 C14,15 15,16 16,17 L19,14 C17,11 15,10 12,10 Z M12,18 C10.9,18 10,18.9 10,20 C10,21.1 10.9,22 12,22 C13.1,22 14,21.1 14,20 C14,18.9 13.1,18 12,18 Z
disk        = M4,3 L20,3 L20,21 L4,21 Z M7,6 L17,6 L17,14 L7,14 Z M7,17 L9,17 M12,17 L17,17
clock       = M12,2 A10,10 0 1 1 11.99,2 M12,6 L12,12 L16,14
api         = M7,4 L17,4 L17,8 L20,8 L20,16 L17,16 L17,20 L7,20 L7,16 L4,16 L4,8 L7,8 Z M9,9 L15,9 L15,15 L9,15 Z
gpu         = M3,6 L21,6 L21,18 L3,18 Z M7,9 A3,3 0 1 1 6.99,9 M14,9 L18,9 M14,12 L18,12 M14,15 L18,15
battery     = M6,6 L18,6 L18,18 L6,18 Z M10,3 L14,3 L14,6
laptop      = M4,5 L20,5 L20,16 L4,16 Z M2,18 L22,18 L20,21 L4,21 Z
monitor     = M3,4 L21,4 L21,17 L3,17 Z M9,19 L15,19 L15,21 L9,21 Z
server      = M3,3 L21,3 L21,9 L3,9 Z M3,10 L21,10 L21,16 L3,16 Z M3,17 L21,17 L21,23 L3,23 Z M6,6 A1,1 0 1 1 5.99,6 M6,13 A1,1 0 1 1 5.99,13 M6,20 A1,1 0 1 1 5.99,20
database    = M4,5 A8,3 0 1 1 20,5 A8,3 0 1 1 4,5 Z M4,5 L4,12 C4,14 7.6,15.5 12,15.5 C16.4,15.5 20,14 20,12 L20,5 C18.4,7 15.2,8 12,8 C8.8,8 5.6,7 4,5 Z M4,12 L4,19 C4,21 7.6,22.5 12,22.5 C16.4,22.5 20,21 20,19 L20,12 C18.4,14 15.2,15 12,15 C8.8,15 5.6,14 4,12 Z
cloud       = M7,19 C4.2,19 2,16.8 2,14 C2,11.5 3.8,9.4 6.2,9 C7.2,5.9 10,4 13.2,4 C17.2,4 20.4,7.1 20.5,11 C22.5,11.5 24,13.2 24,15.3 C24,17.4 22.3,19 20.2,19 Z
wifi        = M2,8 C7.5,3.5 16.5,3.5 22,8 L19.5,10.8 C15.4,7.6 8.6,7.6 4.5,10.8 Z M6.5,13 C9.6,10.5 14.4,10.5 17.5,13 L15,15.8 C13.3,14.5 10.7,14.5 9,15.8 Z M12,18 A2,2 0 1 1 11.99,18 Z
signal      = M3,18 L6,18 L6,21 L3,21 Z M8,14 L11,14 L11,21 L8,21 Z M13,9 L16,9 L16,21 L13,21 Z M18,4 L21,4 L21,21 L18,21 Z
ethernet    = M4,3 L20,3 L20,13 L16,13 L16,17 L13,17 L13,21 L11,21 L11,17 L8,17 L8,13 L4,13 Z M7,6 L9,6 L9,10 L7,10 Z M11,6 L13,6 L13,10 L11,10 Z M15,6 L17,6 L17,10 L15,10 Z
download    = M10,3 L14,3 L14,12 L18,12 L12,18 L6,12 L10,12 Z M4,20 L20,20 L20,22 L4,22 Z
upload      = M12,3 L18,9 L14,9 L14,18 L10,18 L10,9 L6,9 Z M4,20 L20,20 L20,22 L4,22 Z
thermometer = M9,4 A3,3 0 0 1 15,4 L15,14.2 A5,5 0 1 1 9,14.2 Z M11,5 L13,5 L13,15.2 C14.2,15.6 15,16.7 15,18 A3,3 0 1 1 9,18 C9,16.7 9.8,15.6 11,15.2 Z
fan         = M12,9 A3,3 0 1 1 11.99,9 Z M12,2 C16,2 18,5 16,8 C15,9.4 13.6,9.8 12.7,10.1 C13.4,7.7 12.9,5.3 12,2 Z M22,12 C22,16 19,18 16,16 C14.6,15 14.2,13.6 13.9,12.7 C16.3,13.4 18.7,12.9 22,12 Z M12,22 C8,22 6,19 8,16 C9,14.6 10.4,14.2 11.3,13.9 C10.6,16.3 11.1,18.7 12,22 Z M2,12 C2,8 5,6 8,8 C9.4,9 9.8,10.4 10.1,11.3 C7.7,10.6 5.3,11.1 2,12 Z
gauge       = M3,18 A9,9 0 1 1 21,18 L18,18 A6,6 0 1 0 6,18 Z M12,12 L18,8 L14,14 Z
calendar    = M4,4 L7,4 L7,2 L9,2 L9,4 L15,4 L15,2 L17,2 L17,4 L20,4 L20,21 L4,21 Z M6,8 L18,8 L18,19 L6,19 Z
timer       = M9,2 L15,2 L15,4 L9,4 Z M17,5 L19,3 L21,5 L19,7 Z M12,5 A8,8 0 1 1 11.99,5 M12,8 L12,13 L16,13
wallet      = M3,5 L19,5 L19,8 L21,8 L21,19 L3,19 Z M16,11 L21,11 L21,16 L16,16 Z M18,13 A1,1 0 1 1 17.99,13
coin        = M12,2 A10,10 0 1 1 11.99,2 M10,6 L14,6 L14,8 L11,8 C10.4,8 10,8.4 10,9 C10,9.6 10.4,10 11,10 L13,10 C15.2,10 17,11.8 17,14 C17,16.2 15.2,18 13,18 L13,20 L11,20 L11,18 L7,18 L7,16 L13,16 C13.6,16 14,15.6 14,15 C14,14.4 13.6,14 13,14 L11,14 C8.8,14 7,12.2 7,10 C7,7.8 8.8,6 11,6 Z
terminal    = M3,4 L21,4 L21,20 L3,20 Z M6,8 L10,12 L6,16 L8,16 L12,12 L8,8 Z M12,16 L18,16 L18,18 L12,18 Z
code        = M8,5 L2,12 L8,19 L10,17 L6,12 L10,7 Z M16,5 L14,7 L18,12 L14,17 L16,19 L22,12 Z M13,4 L15,4 L11,20 L9,20 Z
globe       = M12,2 A10,10 0 1 1 11.99,2 M2,12 L22,12 M12,2 C8,5 8,19 12,22 C16,19 16,5 12,2 Z
activity    = M2,13 L6,13 L9,6 L13,19 L16,11 L22,11 L22,14 L18,14 L13,23 L9,11 L8,16 L2,16 Z
settings    = M10,2 L14,2 L15,5 C16,5.4 17,6 17.8,6.7 L21,6 L23,10 L20.5,12 C20.6,12.7 20.6,13.3 20.5,14 L23,16 L21,20 L17.8,19.3 C17,20 16,20.6 15,21 L14,24 L10,24 L9,21 C8,20.6 7,20 6.2,19.3 L3,20 L1,16 L3.5,14 C3.4,13.3 3.4,12.7 3.5,12 L1,10 L3,6 L6.2,6.7 C7,6 8,5.4 9,5 Z M12,9 A4,4 0 1 1 11.99,9 Z
shield      = M12,2 L21,6 L20,13 C19.5,17.5 16.7,20.7 12,23 C7.3,20.7 4.5,17.5 4,13 L3,6 Z M11,7 L13,7 L13,12 L17,12 L17,14 L11,14 Z
power       = M11,2 L13,2 L13,12 L11,12 Z M7,5 C3.8,6.8 2,10 2,13.5 C2,19 6.5,23 12,23 C17.5,23 22,19 22,13.5 C22,10 20.2,6.8 17,5 L15.5,7.6 C17.7,8.8 19,11 19,13.5 C19,17.4 15.9,20 12,20 C8.1,20 5,17.4 5,13.5 C5,11 6.3,8.8 8.5,7.6 Z
link        = M7,7 C4.2,7 2,9.2 2,12 C2,14.8 4.2,17 7,17 L10,17 L10,14 L7,14 C5.9,14 5,13.1 5,12 C5,10.9 5.9,10 7,10 L11,10 L11,7 Z M13,7 L17,7 C19.8,7 22,9.2 22,12 C22,14.8 19.8,17 17,17 L13,17 L13,14 L17,14 C18.1,14 19,13.1 19,12 C19,10.9 18.1,10 17,10 L13,10 Z M8,11 L16,11 L16,13 L8,13 Z
refresh     = M12,3 C16.4,3 20,6 20.8,10 L17.5,10 L22,15 L26,10 L23.8,10 C22.9,4.3 18,0 12,0 C7,0 2.7,3 1,7 L4,8.2 C5.3,5.1 8.4,3 12,3 Z M12,21 C7.6,21 4,18 3.2,14 L6.5,14 L2,9 L-2,14 L0.2,14 C1.1,19.7 6,24 12,24 C17,24 21.3,21 23,17 L20,15.8 C18.7,18.9 15.6,21 12,21 Z
```

Additional window-chrome geometries live in `Styles/Geometries.axaml` and are **not** part of
the icon picker: `Geo.Bolt` = `M13 2 L4 13 L12 13 L18 2 Z M13 11 L20 11 L13 22 L4 22 Z`
(default `CircleGlyph`/`SquareGlyph` data, `HudWindow.axaml:103,112`),
`Geo.Bolt.Small`, `Geo.Plug` (`Styles/Geometries.axaml:3-5`).
The tray icon raster is `Assets/tray_bolt.png` (`EndfieldChargePlus.csproj:36`),
window icon `Assets/tray_bolt.ico` (`:21`).

Icon selection rules recap:

* Left icon → glyph geometry for both `CircleForm` and `SquareForm`
  (`HudWindow.axaml.cs:333-338`).
* Right icon → `battery`/`laptop` uses the hand-drawn laptop badge; anything else uses
  `BadgeGlyph` with that icon's geometry (`:340-350`).
* Icon combo boxes are populated from `IconCatalog.Names` for both slots
  (`HudCustomizerView.axaml.cs:130-134`); defaults for a new custom profile are
  `clock`/`clock` (`:896-897`).
* `IconCatalog.GetGeometry` never fails: blank/unknown → `bolt` (`IconCatalog.cs:60-65`).

---

## 9. Product facts (as they appear in code)

| Fact | Value | Ref |
| --- | --- | --- |
| Product / assembly name | `Endfield Charge Plus` / `EndfieldChargePlus` | `EndfieldChargePlus.csproj:10-12` |
| `ProductInfo.Name` (Windows) | `Endfield Charge Plus`; Linux name `Endfield Charge Plus For Linux` | `ProductInfo.cs:5-6` |
| `ProductInfo.Brand(value)` | On Linux swaps `Endfield Charge Plus` ⇄ `Endfield Charge Plus For Linux` and the upper-case variants; identity elsewhere | `ProductInfo.cs:7-14` |
| Version | `0.1.0` (`Version`), `AssemblyVersion`/`FileVersion` `0.1.0.0` | `csproj:17-19` |
| Displayed version | `v0.1.0` in About; `Current: v0.1.0` status line | `SettingsWindow.axaml:233,253`; `SettingsWindow.axaml.cs:110-113` |
| Author / Company / Copyright | `GlacierGlimmer_冰川雪貓` / same / `© 2026 GlacierGlimmer_冰川雪貓` | `csproj:13-15` |
| Footer copyright | `© 2026 GlacierGlimmer_冰川雪貓` | `SettingsWindow.axaml:298` |
| Build date string | `构建日期  2026.09.23` / `Build  2026.09.23` | `SettingsWindow.axaml:234`; `LocalizationManager.cs:59` |
| Tagline | `终末地风格状态栏 HUD` / `Endfield-style Status HUD` | `SettingsWindow.axaml:230`; `LocalizationManager.cs:58` |
| Description | `Configurable Endfield-style HUD for Windows` | `csproj:16` |
| Window titles | Settings `Endfield Charge Plus 设置` → `Endfield Charge Plus Settings`; HUD `Endfield Charge Plus` | `SettingsWindow.axaml:7`; `HudWindow.axaml:8`; `LocalizationManager.cs:25` |
| License shown in UI | `MIT` | `SettingsWindow.axaml:267` |
| License reality | New ECP contributions MIT; derived from `QinAnze/zmd-charge` (upstream README states MIT; no standalone upstream LICENSE file at notice time) | `NOTICE.md:5-13`; `README.en.md:129-133` |
| Project GitHub (UI + update checks) | `github.com/GlacierGlimmer/zmd-charge-plus`; API repo `GlacierGlimmer/zmd-charge-plus` | `SettingsWindow.axaml:270`; `SettingsWindow.axaml.cs:103,620,636` |
| Upstream GitHub | `github.com/QinAnze/zmd-charge` | `SettingsWindow.axaml:274`; `SettingsWindow.axaml.cs:104` |
| Website | `zmd-bar.x-neko.com` | `SettingsWindow.axaml:278`; `SettingsWindow.axaml.cs:105` |
| Microsoft Store id | `9P3PPLD3LX7W6` (README: `9P3PLD3LX7W6`, winget id `9P3PLD3LX7W6`) | `README.en.md:9,39` |
| Update user agent | `EndfieldChargePlus/<version>`, Accept `application/vnd.github+json`, 10 s timeout | `SettingsWindow.axaml.cs:493-502` |
| Privacy statement | `PRIVACY.md` (API keys stored locally, encrypted for the current user; custom HTTP requests target user-configured endpoints) | `PRIVACY.md`; `README.en.md:111` |
| Third-party licences | Avalonia 11.2.1 (MIT), Inter typeface (OFL-1.1), `System.Management` 10.0.2 (MIT), `System.Security.Cryptography.ProtectedData` 8.0.0 (MIT), `LibreHardwareMonitorLib` 0.9.6 (MPL-2.0) | `NOTICE.md:17-57`; `csproj:26-32` |
| Self-description of derivation | "modified derivative of the HUD project QinAnze/zmd-charge"; "Unofficial community derivative. Not affiliated with … Arknights: Endfield" | `NOTICE.md:5-7`; `README.en.md:11` |
| Feature counts advertised | "429 fixed built-in variables + 17 dynamic per-drive variable templates" | `README.en.md:28,91` |
| Runtime / platform | .NET 8, `net8.0-windows`, RIDs `win-x64;win-x86`; OS "Windows 10 / 11" | `csproj:4,8`; `README.en.md:115` |
| Single-instance objects | `Local\EndfieldChargePlus.SingleInstance`, `Local\EndfieldChargePlus.Activate` | `Program.cs:12-13` |
| Autostart registry | HKCU `Software\Microsoft\Windows\CurrentVersion\Run`, value name `Endfield Charge Plus`, data `"<exe>" --autostart` | `StartupManager.cs:8-9,29` |
| Android repo (pointer) | `https://github.com/GlacierGlimmer/zmd-charge-plus-for-android` | `README.en.md:9,76` |

---

## 10. Ambiguities, risks, and decisions required for Android

Explicit uncertainties (do not treat as settled):

1. **This checkout does not compile as-is** (Linux-only sources referencing missing Windows
   symbols; §0.2). Confirm the intended Windows revision before treating anything above as
   final, and re-verify line numbers after the tree is fixed.
2. **DeepSeek without an API key**: Windows writes **no** `deepseek.*` balance keys
   (`VariableHub.cs:1324-1325`) so templates show `--`; the Linux acceptance suite asserts a
   *status string* instead (`Tests/LinuxGuiAudit.cs:122-123`). Pick one behaviour for Android.
3. **HTTP source failure**: Windows writes nothing (`VariableHub.cs:1474-1477`) whereas the
   Linux suite expects a status string (`Tests/LinuxGuiAudit.cs:138-141`).
4. **Half-way rounding of numeric formats**: `0.00`/`F` formatting and `0.0` in string
   interpolation have no explicit `MidpointRounding`, so .NET 8 tie-breaking must be pinned
   by fixtures; expression `round()` explicitly uses `MidpointRounding.AwayFromZero`
   (`ExpressionEngine.cs:343`) and percentage text uses
   `Math.Round(x, MidpointRounding.AwayFromZero)` (`VariableHub.cs:1415`,
   `HudProfileRenderer.cs:120-128`).
5. **`clamp(v, lo, hi)` with `lo > hi` throws `ArgumentException` that `TryEvaluate` does not
   catch** (`ExpressionEngine.cs:41-46,306`) and `Math.Clamp` also throws for `NaN` bounds.
   Decide whether Android degrades to `--` instead.
6. **Expression output is re-scanned for `{key}` tokens** (`TemplateEngine.cs:20-35`); a
   literal `{`/`}` cannot be produced by an expression token, and a produced `{key}` will be
   substituted. This is almost certainly unintended but is observable behaviour.
7. **`extract keys` treats bare progress values as keys** (`TemplateEngine.cs:71-80`), so a
   `ProgressVariable` of `cpu.usage` is requested as a variable; a misspelled key therefore
   yields `ProgressMin` (fallback) rather than an error.
8. **`Hud.PillRadius = 43` resource is unused by the HUD window** (which hard-codes 30);
   `Hud.Pill` etc. are also partly duplicated by literal colors in `HudWindow.axaml`.
   Treat the literals in `HudWindow.axaml` as authoritative.
9. **`square` vs `circle` form at rest**: the persistent final state uses `SquareForm`
   (`HudWindow.axaml.cs:547`), while the startup `ResetToInitial` shows neither. The
   "circle → square" transition is only visible during the full entrance.
10. **Ripple opacity/scale peak values** are `min(1, peakOp · RippleIntensity)` and scale
    `endScale · RippleSpread` — the UI slider `波纹强度`(RippleIntensity) therefore also
    affects opacity, and `波纹幅度`(RippleSpread) affects final ripple size.
11. **Monitor targeting on Android** has no exact analogue of Avalonia `Screens.All` indices
    or `WorkingArea`; the `MonitorIndex = -1` convention and the preset margin of 16 px
    (`HudWindow.axaml.cs:575`) should be re-expressed for Android display metrics.
12. **Click-through is window-wide**, not pill-shaped (`WindowsHudHitTest.cs:38-52` ignores
    the hit predicate); on Android, decide whether the HUD layer consumes touches at all
    (the app is expected to be a non-interactive overlay).
13. **`probe.loss_percent` when `sent == 0`** is 100 when the current probe failed and 0 when
    it succeeded (`VariableHub.cs:870`) — a subtle first-sample rule.
14. **`app.version` uses the assembly version, not `ProductInfo`** (`VariableHub.cs:281`), so
    it is `0.1.0`.
15. **`memory.used_bytes` on Linux** is `MemAvailable`-based (per `Tests/Program.cs:36-41`),
    while Windows uses `GlobalMemoryStatusEx` — the Android implementation must choose a
    platform-appropriate equivalent rather than copying either formula blindly.
16. The `Tests/Program.cs` assertions encode Linux-specific expectations
    (`SecretStore` prefixes, `linux-aesgcm-v1:`) and should not be used as Windows/Android
    acceptance criteria (`Tests/Program.cs:95-102`).

### 10.1 Acceptance checks extracted from the shipped tests (behaviour to preserve)

From `Tests/Program.cs` (platform collectors, valid for any Android implementation that
follows the "no fake values" rule):

* Missing sensors must not become fake zeros (`:34`).
* Rate metrics require two samples before reporting (`:37,43`).
* Counter resets must re-prime, not underflow (`:42-43`).
* Unsupported optional fields must be **absent**, not zero (`:78`).
* `-1` sentinels are not real values (`:81-82`).

From `Tests/LinuxGuiAudit.cs` (cross-platform contract):

* Every advertised catalog variable must render to something other than `--`
  (`:59-60`); built-in profiles must render without `--` in primary/secondary/right text
  (`:63-67`).
* `TemplateToken` rendering of every variable must not be `--` (`:52-60`).
* Failed TCP probes must not fabricate latency numbers (`:133-134`).
* Custom HTTP/JSON mapping and missing-field states must work (`:135-137`).
* Unsupported references in imported profiles are cleaned from templates/color rules while
  the originals remain in backup files
  (`:68-70`, `LinuxVariableCatalog.RemoveUnsupportedReferences` in the Linux repo).
* GPU catalog must match the selected adapter's capabilities (`:72-77`).

### 10.2 Quick reference — the five facts most likely to break a port

1. Missing/`null` values render the literal **`--`** everywhere
   (`TemplateEngine.cs:24,26,33`).
2. Formats are a **chained pipeline** (`|`-separated tokens) whose units are binary for
   `gb/mb/kb/tb/bytes/speed` but **decimal** for `mbps/kbps` and the network display strings
   (`TemplateEngine.cs:226-233`, `HudProfileRenderer.cs:205-216`).
3. Expressions are `{= … }` tokens with a fixed precedence chain and a 29-function whitelist
   (`ExpressionEngine.cs:15-21,104-290`).
4. Built-in profiles are **dictated by code** and re-materialized on every settings load;
   only `Id`, the time target and the GPU adapter survive normalization
   (`HudSettingsNormalizer.cs:20-52`).
5. The full animation's cue timeline is normalized from a 6 s baseline with the intro
   compressed/expanded by `MapCue`, and Simple mode is a separate 5 s baseline timeline
   (`HudAnimations.cs:31-68,206-219`).


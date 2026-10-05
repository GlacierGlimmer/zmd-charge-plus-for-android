# 02 — Platform Data Collection, Degradation and Upstream Lineage

Audit of the two existing non-Windows Endfield Charge Plus (ECP) ports, plus the upstream
original, as the data-collection contract for the native Android port.
Read-only audit: nothing under the audited trees was modified.

Sibling document: [`01-windows-core.md`](01-windows-core.md) (Windows tree
`D:\ECP_Workspace\zmd-charge-plus`, 429 catalog keys). This document covers the Linux and
macOS trees and the upstream original.

**Path legends used below (paths inside a legend are repo-relative):**

| Legend | Absolute root |
| --- | --- |
| `LN/` | `D:\ECP_Workspace\zmd-charge-plus-for-linux\` |
| `MC/` | `D:\ECP_Workspace\zmd-charge-plus-for-macos\` |
| `WIN/` | `D:\ECP_Workspace\zmd-charge-plus\` (reference only) |
| `UP/` | `D:\ECP_Workspace\upstream-zmd-charge\` (clone of `QinAnze/zmd-charge`) |

All `file:line` references are 1-based and come from the checkout as it exists on disk today.
`bin/`, `obj/`, `artifacts/` binaries and `apt-repo/*.deb` were not treated as source.

---

## 0. Method, coverage, version identity

Read in full for this audit:

- Linux: `Customization/LinuxCommand.cs`, `LinuxDisks.cs`, `LinuxNetwork.cs`, `LinuxNvidia.cs`,
  `LinuxSystemVariables.cs`, `LinuxUiVariables.cs`, `LinuxVariableCatalog.cs`,
  `LinuxVariableProvider.cs`, `VariableHub.cs` (1 870 lines), `AdvancedVariableProvider.cs`
  (1 338 lines), `TemplateEngine.cs`, `Models.cs`, `HudProfileRenderer.cs`,
  `HudSettingsNormalizer.cs`, `BuiltInProfileLocalization.cs`, `SecretStore.cs`,
  `Interop/{AppPaths,LinuxDesktop,WindowsSystemProbe}.cs`, `Diagnostics/AppLog.cs`,
  `Settings/{AppSettings,SettingsManager}.cs`, `Tests/Program.cs`, `Tests/LinuxGuiAudit.cs`,
  `README.linux.md`, `PRIVACY.md`, `NOTICE.md`, `LICENSE`, `docs/linux-validation.md`,
  `artifacts/linux-audit/VARIABLE-AUDIT.md`.
- macOS: `native/ecpmac.m` (301 lines), `Interop/{MacNative,MacSystemProbe,AppPaths}.cs`,
  `Customization/{MacVariableProvider,MacVariableCatalog,MacUiVariables,MacSharedKeys,SecretStore,VariableHub,Models}.cs`,
  `Settings/{AppSettings,SettingsManager,MacUpdateRelease}.cs`, `Diagnostics/AppLog.cs`,
  `Tests/Program.cs`, `README.md`, `PRIVACY.md`, `NOTICE.md`, `LICENSE`.
- Upstream: every non-generated file listed in §8.

Cross-cutting ownership: `VariableCatalog.cs` and most of `VariableHub.cs` /
`TemplateEngine.cs` / `Models.cs` / `HudProfileRenderer.cs` are **shared source files with
per-platform edits**; the `Linux*` / `Mac*` files are platform-specific additions.
`LINUX/LinuxVariableCatalog.cs:74` and `MC/MacVariableCatalog.cs:43` both call
`VariableCatalog.PlatformIndependentDefinitions`, which exists in
`LN/Customization/VariableCatalog.cs:762` and `MC/Customization/VariableCatalog.cs:535`
(and, per `01-windows-core.md:34`, is **not** declared in the Windows copy).

---

## 1. Per-platform variable providers

### 1.1 Linux — provider entry point and gating

- `LINUX/Customization/LinuxVariableProvider.cs:8` — `internal sealed partial class LinuxVariableProvider`.
- `LINUX/Customization/LinuxVariableProvider.cs:17` — `internal LinuxVariableProvider(string root = "/")`.
  The `root` parameter exists so procfs/sysfs **fixtures** can drive deterministic tests
  (`LINUX/Tests/Program.cs:25` constructs `new LinuxVariableProvider(fixture)`).
  `PathAt(string path)` (`:18`) joins the root, so every read below is `root`-relative.
- `LINUX/Customization/LinuxVariableProvider.cs:20-49` — `Collect(IDictionary<string, object?> values, HashSet<string>? requested, string? gpuId, CustomHudSettings? settings = null)`.
  Everything runs under `lock (_gate)` (`:23`). Dispatch is prefix-gated by
  `Need(string prefix) => requested is null || requested.Any(k => k.StartsWith(prefix, …))` (`:22`):

  | Gate | Call | Line |
  | --- | --- | --- |
  | `cpu.` | `AddCpu` | `:25` |
  | `memory.` | `AddMemory` | `:26` |
  | `network.` | `AddLinuxNetwork` | `:27` |
  | `battery.` | `AddBattery` | `:28` |
  | `disk.` | `CollectDisks` | `:29` |
  | `gpu.` | `AddGpu` | `:30` |
  | `process.` / `dev.` | `AddProcesses(values, Need("process."), Need("dev."))` | `:31` |
  | `system.` / `security.` | `AddLinuxSystem` | `:32` |
  | `usb.` / `peripheral.` | `AddDevices` | `:33` |
  | `dev.` | `AddDeveloper` | `:34` |
  | `app.` | inline `app.theme` / `app.preset_name` / `app.active_profile` | `:35-47` |

- Read primitive: `Read(string path)` = `File.ReadAllText(path).Trim()` catching `IOException`
  and `UnauthorizedAccessException` → `null` (`:333-338`); `Directories(path)` likewise (`:339-344`);
  `Parse` uses `InvariantCulture` + `double.IsFinite` (`:331`); `Put` **skips null values**
  (`:327-330`). This is the core "unavailable = absent, never 0" mechanism.

### 1.2 Linux — variable table

Cadence column: "each snapshot" means the value is recomputed on every `Collect` where the
family is requested; "N s cache" means the provider holds its own expiry.

#### CPU

| Variable(s) | Source | Units / scale | Cadence |
| --- | --- | --- | --- |
| `cpu.usage`, `cpu.user_usage`, `cpu.kernel_usage`, `cpu.idle_percent` | `/proc/stat` first `cpu ` line, `Skip(1).Take(8)` = user, nice, system, idle, iowait, irq, softirq, steal (`LN/…/LinuxVariableProvider.cs:53-55`) | % | each snapshot; **requires two monotonic samples** |
| `cpu.name` | `/proc/cpuinfo` `model name`, else `Hardware` (`:76-77`) | text | each snapshot |
| `cpu.manufacturer` | `/proc/cpuinfo` `vendor_id` (`:78`) | text | each snapshot |
| `cpu.architecture` | `RuntimeInformation.OSArchitecture.ToString()` (`:79`) | text | each snapshot |
| `cpu.logical_processors` | `Environment.ProcessorCount` (`:80`) | count | each snapshot |
| `cpu.frequency_mhz`, `cpu.frequency_ghz` | average of `/proc/cpuinfo` `cpu MHz` (`:81-86`) | MHz, MHz/1000 | each snapshot |
| `cpu.max_frequency_ghz`, `cpu.max_frequency_mhz`, `cpu.frequency_percent` | `/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq` (kHz) (`:87-93`) | kHz/1e6, kHz/1000, % | each snapshot |
| `cpu.physical_cores` | distinct `(physical id, core id)` pairs in `/proc/cpuinfo` (`:94-100`) | count | each snapshot |
| `cpu.socket_count` | distinct `physical id` (`:101-102`) | count | each snapshot |
| `cpu.temperature_max` | `sys/class/hwmon/*/temp*_input` for hwmon `name` ∈ `coretemp`, `k10temp`, `cpu_thermal`, divided by 1000 (`:103-109`) | °C | each snapshot |
| `cpu.usage_avg_1m`, `cpu.usage_avg_5m`, `cpu.usage_avg_15m`, `cpu.usage_max` | in-process `Queue<(DateTime Time, double Value)> _cpuHistory` pruned to 15 min (`:12`, `:110-118`) | % | each snapshot |
| `cpu.context_switches`, `cpu.interrupts` | `/proc/stat` `ctxt` / `intr`, delta ÷ `Stopwatch` elapsed (`:119-135`) | per second | each snapshot; requires two samples |

Formula specifics (`LN/…/LinuxVariableProvider.cs:56-70`): `guest`/`guest_nice` are **not** counted
(comment `:56`); `idle = delta[3] + delta[4]` i.e. **idle + iowait** (`:65`);
`cpu.usage = clamp(100*(total-idle)/total, 0, 100)`; `cpu.user_usage = 100*(user+nice)/total` (`:67`);
`cpu.kernel_usage = 100*(system + irq + softirq)/total` (`:68`). The two-sample requirement is
`_lastCpu` non-null, same length, and every new counter `>=` the previous (`:59`) — a counter
reset simply re-primes and emits nothing.

#### Memory

`LINUX/Customization/LinuxVariableProvider.cs:138-170`. `/proc/meminfo` is split on `:` and the
numeric field is multiplied by **1024** (kB → Byte, `:141`).

| Variable | meminfo key | Unit |
| --- | --- | --- |
| `memory.total_bytes` | `MemTotal` | Byte |
| `memory.available_bytes` | `MemAvailable` | Byte |
| `memory.used_bytes`, `memory.usage` | `MemTotal - MemAvailable` (clamped ≥0; % clamped 0-100) | Byte, % |
| `memory.free_percent` | `MemAvailable/MemTotal*100` | % |
| `memory.cache_bytes` | `Cached` | Byte |
| `memory.commit_limit_bytes` | `CommitLimit` | Byte |
| `memory.commit_used_bytes` | `Committed_AS` | Byte |
| `memory.commit_available_bytes`, `memory.commit_usage` | `CommitLimit - Committed_AS`, ratio | Byte, % |
| `memory.swap_total_bytes` | `SwapTotal` | Byte |
| `memory.swap_available_bytes` | `SwapFree` | Byte |
| `memory.swap_used_bytes`, `memory.swap_usage` | `SwapTotal - SwapFree` | Byte, % |

#### Battery / power supply

`LINUX/Customization/LinuxVariableProvider.cs:172-274`.

- AC detection `TryGetAcOnline` (`:172-185`, also called from `Interop/WindowsSystemProbe.cs:47`):
  iterate `sys/class/power_supply/*`, skip `type == "Battery"`, accept only supplies whose
  `online` is exactly `0` or `1`; `battery.ac_online` = OR of them; returns `false` when no such
  supply exists (so the key is then **absent**).
- Batteries = `type == "Battery"` **and** `present != 0` (`:191-192`). No batteries → `return`
  before writing anything (`:193`).
- `battery.charging` / `battery.discharging` from any `status == "Charging"` / `"Discharging"`
  (`:195-198`).
- `battery.status_text` (`:199-203`) exact strings:
  `充放电混合` / `Charging and discharging`; `充电中` / `Charging`; `使用电池` / `On Battery`;
  `已充满` / `Full`; `未充电` / `Not charging`.
- `battery.power_source` (`:204`) = `交流电源` / `AC` or `电池` / `Battery`.
- Energy normalisation — `Energy(string suffix)` (`:206-213`):
  `energy_<suffix>` ÷ 1000 (µWh → mWh); else `charge_<suffix>` × (`voltage_min_design` ?? `voltage_now`)
  ÷ 1_000_000_000 (µAh × µV → mWh). Applied to `now`, `full`, `full_design` →
  `battery.remaining_mwh`, `battery.full_mwh`, `battery.design_mwh` and the `_wh` variants
  (÷1000) (`:214-221`).
- `battery.percent` = `remaining/full*100` clamped, else average of each pack's `capacity`
  when all are 0-100 (`:222-227`). `battery.health_percent` = `full/design*100` (`:228`) and
  `battery.design_vs_current_health` (`:257`).
- Power (`:229-238`): per pack `power_now`/1e6 (µW → W), else `current_now * voltage_now / 1e12`,
  then `Math.Abs`. `battery.rate_watts` = sum; `battery.charge_rate_watts` /
  `battery.discharge_rate_watts` sum only packs whose `status` matches.
- Time estimates (`:239-247`): `battery.time_remaining_seconds` / `battery.time_remaining_text`
  (`H:MM`) / `battery.estimated_time_to_empty` only when `discharging && !charging && watts > 0`;
  `battery.estimated_time_to_full` only when `charging && !discharging`.
- Unavailable-time markers (`:248-256`) — these are **status strings written into numeric slots**:
  `当前无法估算` / `Estimate unavailable`, `未放电` / `Not discharging`, `未充电` / `Not charging`.
- Single-pack-only fields (`:259-267`): `battery.voltage_mv` (`voltage_now`/1000),
  `battery.voltage_v` (`voltage_now`/1e6), `battery.cycle_count` (only when `>= 0`),
  `battery.temperature` (`temp`/10), `battery.chemistry` (`technology`, skipped when `Unknown`).
  Comment `:258`: voltage/cycle count have no meaningful sum across packs.

#### Disk

`LINUX/Customization/LinuxDisks.cs`.

- `Mounts()` (`:16-30`) parses `/proc/self/mountinfo`: `p[4]` = mount point, `p[2]` = major:minor,
  `p[dash+1]` = filesystem, `p[dash+2]` = device. Keeps `point == "/"` or
  (`device.StartsWith("/dev/")` **and** `Directory.Exists(point)`) (`:26`). Grouped by mount point,
  ordinal-sorted.
- `DecodeMount` (`:32`) un-escapes octal `\040`-style sequences.
- Per-mount key prefix: `"disk.mount_" + Convert.ToHexString(SHA256(UTF8(mountPoint)))[..12].ToLowerInvariant()` (`:12`).
  This is a **stable hash prefix, not the raw path** — deliberately filesystem-path-safe.
- Per mount (`:46-60`): `<prefix>.root`, `.label` (device), `.filesystem`, `.total_bytes`,
  `.free_bytes`, `.used_bytes`, `.usage`, `.free_percent` from `DriveInfo.IsReady` /
  `TotalSize` / `AvailableFreeSpace`. `/` additionally mirrored to `disk.system.*` (`:61`).
- Block I/O (Linux-only; **macOS has no equivalent**) (`:63-83`): `/sys/dev/block/<major:minor>/stat`,
  fields `[2]` × 512 = bytes read, `[6]` × 512 = bytes written, `[9]` = busy ms, `[8]` = queue length:
  `<prefix>.queue_length`, `.read_bps`, `.write_bps`, `.io_bps`,
  `.active_percent = clamp((busyΔ)/seconds/10, 0, 100)` (`:78`). Requires monotonic counters
  (`:74`); state kept per mount point in `_diskSamples` (`:14`).
- Totals deduplicated by major:minor so multiple mounts of one device count once (`:62`):
  `disk.fixed.count`, `.list`, `.total_bytes`, `.free_bytes`, `.used_bytes`, `.usage` (`:87-96`).

#### Network

`LINUX/Customization/LinuxNetwork.cs` (all inside `AddLinuxNetwork`).

- Interface set: `NetworkInterface.GetAllNetworkInterfaces()` where `OperationalStatus == Up`
  and type `!= Loopback` (`:13-14`).
- `network.active_interface_count`, `.interface_names` (ordinal-sorted, `", "`-joined),
  `.interface_types`, `.ipv4_addresses`, `.ipv6_addresses` (link-local excluded),
  `.default_gateways`, `.dns_servers`, `.available` (`:33-41`).
- Packet/error counters from `sys/class/net/<if>/statistics/{rx_packets,tx_packets,rx_errors,tx_errors}`
  — written **only when every interface reports the field** (`:42-46`).
- Byte counters from `…/statistics/rx_bytes` and `tx_bytes`; `network.total_received_bytes`,
  `.total_sent_bytes`, `.total_transferred_bytes` (`:47-52`).
- Rates (`:53-71`): `Stopwatch.GetTimestamp()` delta against `_networkSample`, valid only when the
  interface **name set is unchanged** and both counters are monotonic (`:56`); disconnected
  (zero interfaces) is reported as `0` explicitly (`:55`, comment "actual disconnected state").
  Emits `network.download_bps`, `.upload_bps`, `.total_bps`, `.download_mbps`, `.upload_mbps`,
  `.total_mbps` (×8/1e6) and `.utilization_percent` only when every interface reported
  `sys/class/net/<if>/speed` (`:69-70`).
- Link speeds: `sys/class/net/<if>/speed` is Mbit/s → ×1_000_000 (`:21-22`) →
  `network.link_speed_bps` (sum), `.max_link_speed_bps` (max) (`:73-76`).
- Connection counts via `IPGlobalProperties.GetIPGlobalProperties()`:
  `network.tcp_connections` = `GetActiveTcpConnections().Length`,
  `network.udp_connections` = `GetActiveUdpListeners().Length` (`:77-83`).

#### GPU

Adapter enumeration `GetGpuAdapters(string root = "/")` (`LINUX/Customization/LinuxVariableProvider.cs:276-293`):

1. NVIDIA via `LinuxNvidia.Read()` — **only** when `root == "/"` and `OperatingSystem.IsLinux()` (`:278`).
2. Otherwise `/sys/class/drm/card[0-9]+` that has a `device/` subdirectory; NVIDIA (`device/vendor == "0x10de"`)
   is **skipped if `nvidia-smi` already produced rows** (`:281`), preventing double counting.
3. Vendor map (`:286`): `0x1002` → `AMD`, `0x10de` → `NVIDIA`, `0x8086` → `Intel`, else `GPU`.
   Name = `device/product_name` else `"<Brand> cardN"` (`:287`).
   `GpuAdapterInfo(Id, Name, PhysicalIndex, VramBytes, 0, [], [])` with `Id` = `cardN` or
   `nvidia:<GPU-uuid>`; VRAM = `device/mem_info_vram_total` or `0` for NVIDIA rows (`:288-292`).

`AddGpu(v, gpuId)` (`:295-325`): picks the adapter whose `Id == gpuId` else the first (`:298`);
writes `gpu.name`, `gpu.adapter_id`, `gpu.physical_index`, `gpu.count` (`:300-303`).

- NVIDIA path (`:304-309`): copies **every** key from the matching `LinuxNvidia.Read()` row and returns.
- DRM path (`:310-324`): `gpu.usage` = `gpu_busy_percent`; `gpu.memory_total_bytes` =
  `mem_info_vram_total`, `gpu.memory_used_bytes` = `mem_info_vram_used`; `gpu.vram_bytes`,
  `gpu.dedicated_total_bytes`, `gpu.dedicated_used_bytes`, `gpu.dedicated_usage`;
  `gpu.temperature` = `hwmon/temp1_input`/1000; `gpu.power_w` = `hwmon/power1_average`/1_000_000.

`LINUX/Customization/LinuxNvidia.cs`:

- 2-second cache `_expires = DateTime.UtcNow.AddSeconds(2)` under a lock (`:11-23`).
- Command `nvidia-smi --query-gpu=index,uuid,name,memory.total,memory.used,utilization.gpu,temperature.gpu,power.draw,power.limit,clocks.gr,clocks.mem,driver_version,vbios_version,pcie.link.gen.current,pcie.link.width.current --format=csv,noheader,nounits` (`:17-19`).
- `Parse` requires exactly 15 comma fields, integer `index`, and `uuid` starting `GPU-` (`:31`).
- MiB → Byte ×1 048 576 (`:42-43`); published keys `gpu.memory_total_bytes`,
  `gpu.memory_used_bytes`, `gpu.usage`, `gpu.temperature`, `gpu.power_w`, `gpu.power_limit_w`,
  `gpu.core_clock_mhz`, `gpu.memory_clock_mhz`, `gpu.pcie_gen`, `gpu.pcie_lanes`,
  `gpu.adapter_id` (`"nvidia:" + uuid`), `gpu.name`, `gpu.physical_index`,
  `gpu.nvidia_smi_available` (`:33-46`).
- `[N/A]` string fields are **omitted**, not zeroed (`:47-48`); `double.IsFinite(n) && n >= 0`
  gate in `Metric` (`:39`). Derived `gpu.vram_bytes` / `gpu.dedicated_total_bytes` /
  `gpu.dedicated_used_bytes` / `gpu.dedicated_usage` (`:49-57`).

#### System / security

`LINUX/Customization/LinuxSystemVariables.cs:13-52`.

| Variable | Source | Notes |
| --- | --- | --- |
| `system.uptime_seconds`, `.uptime_text`, `.boot_time` | `/proc/uptime` first field | text format `d\.hh\:mm\:ss` (`:19`); boot time = `DateTime.Now - uptime` (`:20`) |
| `system.kernel_version` | `/proc/sys/kernel/osrelease` | `:22` |
| `system.os_description` | `/etc/os-release` `PRETTY_NAME=` (quotes trimmed) | `:23-25` |
| `system.bios_version`, `.bios_date`, `.motherboard_manufacturer`, `.motherboard_model` | `sys/class/dmi/id/{bios_version,bios_date,board_vendor,board_name}` | `:26-28` |
| `system.power_plan`, `.power_plan_name` | `sys/firmware/acpi/platform_profile` | both set to the same profile string (`:29-34`) |
| `system.reboot_required` | only if `/etc/debian_version` exists; value = existence of `/var/run/reboot-required` | `:35-36` |
| `security.tpm.present`, `.version` | `sys/class/tpm/*` dirs; `tpm_version_major` | `:37-43` |
| `security.secure_boot` | `sys/firmware/efi/efivars/SecureBoot-*`, byte `[4] == 1` | `:44-48` |
| `system.fan_speed` | max of `sys/class/hwmon/*/fan*_input` | `:49-51` |

#### Processes / dev tools / devices

- `AddProcesses` (`:54-110`): iterates `Process.GetProcesses()`, uses `ProcessName`,
  `StartTime.ToUniversalTime().Ticks`, `TotalProcessorTime.TotalSeconds`, and
  `/proc/<pid>/io` `read_bytes` + `write_bytes` (`:69-71`).
  `process.count` counts numeric `/proc` directory entries (`:86`).
  Top entries via `PutProcess` (`:112-117`): `process.top_memory.{name,pid,usage}`,
  `process.top_cpu.{name,pid,usage}`, `process.top_disk.{name,pid,usage}`.
  CPU % = `clamp((cpuΔ)/elapsed/Environment.ProcessorCount*100, 0, 100)` (`:75`) — **normalised by
  logical processor count**. Requires elapsed > 0 and a matching prior `StartTime` (`:73`).
  Dev flags by process name (`:102-109`): `dev.docker.running` (`dockerd`, `docker-desktop`),
  `dev.vscode.running` (`code`, `code-insiders`, `codium`), `dev.terminal.running`
  (`gnome-terminal-server`, `gnome-terminal-`, `konsole`, `alacritty`, `kitty`, `xterm`,
  `xfce4-terminal`, `foot`, `ptyxis`), `dev.ide.running` (`code`, `codium`, `idea`, `pycharm`,
  `rider`, `clion`, `eclipse`), `dev.llm.local_status` (`ollama`, `llama-server`, `lm-studio`,
  `LM Studio`) with fallback text `未运行` / `Not running` (`:108`).
- `AddDeveloper` (`:139-165`): **30-second cache** (`_nextDeveloperRead = DateTime.UtcNow.AddSeconds(30)`, `:143`).
  Commands → keys: `node --version` → `dev.node.version`; `python3 --version` → `dev.python.version`;
  `java -version` → `dev.java.version`; `go version` → `dev.golang.version`;
  `rustc --version` → `dev.rust.version`; `git branch --show-current` → `dev.git.branch`;
  `git log -1 --format=%h %s` → `dev.git.last_commit`;
  `git status --porcelain` → `dev.git.status` = `clean` or `"N changes"` (`:157-158`);
  `docker ps -q` → `dev.docker.containers`; `docker images -q` → `dev.docker.images`
  (distinct count) (`:159-162`). Only first output line is kept (`:155`).
- `AddDevices` (`:119-137`): `usb.device.count`, `usb.device.list` from
  `sys/bus/usb/devices/*/idVendor` (product else `idVendor:idProduct`) (`:121-126`);
  `peripheral.mouse.name` / `peripheral.keyboard.name` from `/proc/bus/input/devices`
  `H: Handlers=` containing `mouse` / `kbd` (`:127-132`);
  `peripheral.gamepad.count` / `.name` from `sys/class/input/js[0-9]+` (`:133-136`).
- `LINUX/Customization/LinuxCommand.cs` — the process-exec primitive:
  `Exists`/`Resolve` scan `PATH` (default `/usr/bin:/bin`), require a rooted path and, on Linux,
  at least one execute bit (`:7-20`). `Run` sets `LC_ALL=C` and `GIT_OPTIONAL_LOCKS=0`,
  redirects stdout/stderr, `WaitForExit(2500)`, and on timeout `process.Kill(entireProcessTree: true)`
  returning `null`; non-zero exit → `null`; returns trimmed stdout, or stderr when stdout is blank
  (`:22-41`). **Hard 2 500 ms cap per external command.**

#### Display / clipboard (Linux)

`LINUX/Customization/LinuxUiVariables.cs:12-58`, only when an Avalonia desktop lifetime exists:
`display.primary_width_px`, `.primary_height_px`, `.virtual_x_px`, `.virtual_y_px`,
`.virtual_width_px`, `.virtual_height_px`, `.monitor_count`, `.system_dpi` (96 × `Scaling`),
`.scale_percent` (100 × `Scaling`) (`:27-35`). Clipboard (`:38-54`):
`clipboard.has_text`, `.text_length`, `.preview` (whitespace-collapsed, first 80 chars),
`.has_image` (any format starting `image/`), `.last_updated` (time ECP first observed a change,
fingerprint = formats + text); reads are wrapped in `WaitAsync(2 s)` and `TimeoutException` is
swallowed (`:42-53`).

### 1.3 macOS — provider entry point, native interop, variable table

- `MAC/Customization/MacVariableProvider.cs:8` — `internal sealed class MacVariableProvider`.
- `Collect` (`:34-57`) computes a bit `mask` from the requested prefixes
  (`:38-39`): bit 1 = `cpu.` or `system.`, 2 = `memory.`, 4 = `battery.`, 8 = `disk.`, 16 = `network.`,
  then makes **one** native call `MacNative.Snapshot(mask)` (`:42`) and hands the JSON to
  `Consume(json.RootElement, values, Stopwatch.GetTimestamp())` (`:43`). GPU is a separate call
  (`:45-56`).
- `MAC/Customization/VariableHub.cs:42-80` — `SnapshotAsync`: clock/system/app inline, then
  `_mac.Collect` on a background task (`:63`), then a priming loop
  `for (int attempt=0; attempt<6 && (… !vars.ContainsKey("cpu.usage") || … !vars.ContainsKey("network.download_bps")); attempt++) { await Task.Delay(200, ct); _mac.Collect(…); }`
  (`:64-69`) — **up to 6 × 200 ms until two samples exist**.
- `MAC/Interop/MacNative.cs:8` — `const string Library = "ecpmac"`; `DllImport` entry points
  `ecp_snapshot(int mask)`, `ecp_gpus()`, `ecp_display()`, `ecp_clipboard()`, `ecp_free(IntPtr)`,
  `ecp_hud_window(IntPtr, int topmost)`, `ecp_hud_flags(IntPtr)`, `ecp_pointer(out double, out double)`,
  `ecp_keychain_set(string, string)` (UTF-8), `ecp_keychain_get(string)` (`:9-18`).
  `Consume` marshals `PtrToStringUTF8` and always calls `ecp_free` (`:19-24`).

#### macOS `Consume` semantics (`MAC/Customization/MacVariableProvider.cs:59-112`)

1. Every non-`__` JSON property is copied verbatim; `String`→string, `Number`→double,
   `True`/`False`→bool, else `null` (`:61-69`).
2. `__cpu_ticks` (4-element array, `[USER, SYSTEM, IDLE, NICE]`): deltas via `unchecked(uint - uint)`
   so tick wrap is handled (`:70-86`); emits `cpu.usage = (total-idle)/total*100`,
   `cpu.user_usage = (user+nice)/total*100`, `cpu.kernel_usage = system/total*100`,
   `cpu.idle_percent = idle/total*100` (`:77-83`). Requires a previous 4-element array.
3. `__network_samples` (object `name → [rx, tx]`): only names present in **both** samples with
   monotonic counters contribute (`:94-96`); subtracts the `Stopwatch` timestamps
   (`seconds = (timestamp - _networkAt)/Stopwatch.Frequency`) and emits `network.download_bps`,
   `.upload_bps`, `.total_bps`, `.download_mbps`, `.upload_mbps`, `.total_mbps` (`:97-100`).
   Absent/renamed interfaces simply contribute 0 → no hotplug spike.
4. `battery.status_text` derived here (`:104-106`): `充电中` / `Charging` when charging,
   else `已接通电源` / `AC power` when `battery.ac_online`, else `使用电池` / `On battery`.
5. `system.uptime_text` formatted `"<d>d HH:mm:ss"` (`:107-111`).

#### macOS `ecpmac.m` JSON contract (`MAC/native/ecpmac.m`)

`ecp_snapshot` mask bits (`:186-193`): `1` = `cpu(v)`, `2` = `memory(v)`, `4` = `battery(v)`,
`8` = `disks(v)`, `16` = `network(v)`.

| Helper | APIs used | Keys emitted | Units |
| --- | --- | --- | --- |
| `cpu()` `:40-71` | `sysctlbyname("machdep.cpu.brand_string")`, `hw.physicalcpu`, `hw.logicalcpu`; `host_processor_info(host, PROCESSOR_CPU_LOAD_INFO, …)`; `sysctlbyname("kern.boottime")`; `NSProcessInfo.operatingSystemVersion` | `cpu.name`, `cpu.physical_cores`, `cpu.logical_processors`, `__cpu_ticks` `[USER,SYSTEM,IDLE,NICE]`, `system.uptime_seconds`, `system.boot_time` (`yyyy-MM-dd HH:mm:ss`, `en_US_POSIX`), `system.os_version`, `system.os_description` (`"macOS " + version`) | ticks (raw), seconds, text |
| `memory()` `:73-99` | `sysctlbyname("hw.memsize")`, `host_page_size`, `host_statistics64(HOST_VM_INFO64)`, `sysctlbyname("vm.swapusage")` | `memory.total_bytes`, `.used_bytes`, `.available_bytes`, `.usage`, `.free_bytes`, `.active_bytes`, `.inactive_bytes`, `.wired_bytes`, `.compressed_bytes`, `.page_size`, `.swap_total_bytes`, `.swap_used_bytes`, `.swap_available_bytes` | Byte (`counts × page`), % |
| `battery()` `:101-120` | `IOPSCopyPowerSourcesInfo`, `IOPSCopyPowerSourcesList`, `IOPSGetPowerSourceDescription`, keys `kIOPSTypeKey`, `kIOPSIsPresentKey`, `kIOPSCurrentCapacityKey`, `kIOPSMaxCapacityKey`, `kIOPSPowerSourceStateKey`, `kIOPSIsChargingKey`, `kIOPSTimeToEmptyKey`, `kIOPSTimeToFullChargeKey` | `battery.percent`, `.ac_online`, `.charging`, `.discharging`, `.estimated_time_to_empty`, `.time_to_full_seconds` | %, bool, seconds (`minutes × 60`) |
| `disks()` `:122-136` | `statfs("/System/Volumes/Data")`, fallback `"/"` | `disk.system.total_bytes`, `.free_bytes`, `.available_bytes`, `.used_bytes`, `.usage`, `.root`, `.filesystem` | Byte, %, text |
| `network()` `:138-184` | `sysctl(CTL_NET, PF_ROUTE, 0, 0, NET_RT_IFLIST2, 0)`, `struct if_msghdr2`, `if_indextoname`, `getifaddrs` + `inet_ntop` | `__network_samples`, `network.total_received_bytes`, `.total_sent_bytes`, `.total_transferred_bytes`, `.packets_received`, `.packets_sent`, `.receive_errors`, `.send_errors`, `.active_interface_count`, `.available`, `.interface_names`, `.ipv4_addresses`, `.ipv6_addresses` | Byte, counts, text |

Key degradations baked into the native layer:

- Memory口径 comment `:80-81`: "Used = active + wired + compressed. Inactive/file cache remains
  reclaimable." `used = MIN(total, (active + wire + compressor_page_count) * page)`;
  `available = MAX(0, total - used)`.
- `battery()` `break`s after the **first** internal battery (`:117`, comment "Internal battery, not
  a UPS or Bluetooth accessory"); `percent` only if `maximum > 0` (`:108`); time estimates only when
  the OS returns a positive value and the state is right (`:115-116`).
- `disks()` returns without writing anything if `statfs` fails or `total <= 0` (`:125,127`) and
  `free = MIN(total, f_bfree*bsize)` (`:128`).
- `network()` includes **only interfaces whose name starts with `en`** and which are `IFF_UP`
  (`:151`) — this is the deliberate exclusion of loopback and VPN/utun interfaces.
- `cpu()` comment `:58`: "`hw.cpufrequency` is nominal on some Macs; it is deliberately NOT labeled
  current GHz." **No macOS CPU-frequency, temperature, fan, GPU-load or VRAM key exists at all.**

Non-snapshot entry points:

- `ecp_gpus()` `:194-204` — `MTLCopyAllDevices()` → array of
  `{"id":"metal:<registryID>", "name", "unified":hasUnifiedMemory, "budget":recommendedMaxWorkingSetSize, "lowPower", "removable"}`.
- `ecp_hud_window` `:206-218` — `ignoresMouseEvents=YES`, `hidesOnDeactivate=NO`, `opaque=NO`,
  clear `backgroundColor`, `hasShadow=NO`,
  `collectionBehavior = CanJoinAllSpaces | FullScreenAuxiliary | IgnoresCycle`,
  `level = topmost ? NSFloatingWindowLevel : NSNormalWindowLevel`.
- `ecp_hud_flags` `:219-228` — returns bit 1 mouse-transparent, 2 all-Spaces, 4 transparency
  (test asserts `== 7`, `MAC/Tests/Program.cs:245`).
- `ecp_pointer` `:229-232` — `CGEventCreate` + `CGEventGetLocation`; **no Accessibility permission**
  (asserted `MAC/Tests/Program.cs:246`).
- `ecp_display` `:233-265` — `CGGetActiveDisplayList`, union of `CGDisplayBounds` →
  `display.virtual_x_points`, `.virtual_y_points`, `.virtual_width_points`, `.virtual_height_points`
  (**logical points, not pixels**), `display.monitor_count`;
  `CGDisplayCopyDisplayMode` + `CGDisplayModeGetPixelWidth/Height` → `display.primary_width_px`,
  `.primary_height_px`; `NSScreen.backingScaleFactor` → `display.scale_percent` (`100×`) and
  `display.system_dpi` (`96×`).
- `ecp_clipboard` `:266-274` — `NSPasteboard.generalPasteboard`, `NSPasteboardTypeString`,
  `canReadObjectForClasses:@[NSImage.class]`, `changeCount` → `{"text","hasImage","sequence"}`.
- `ecp_keychain_set/get` `:275-301` — `kSecClassGenericPassword`,
  `kSecAttrService = @"com.glacierglimmer.endfieldchargeplus.macos"`,
  `SecItemUpdate` then `SecItemAdd` on `errSecItemNotFound`, `SecItemCopyMatching` on read.

`MAC/Interop/MacSystemProbe.cs`: cursor via `ecp_pointer` (`:7-13`); AC state via
`MacNative.Snapshot(4)` cached for **1 second** (`_nextPowerRead = DateTime.UtcNow.AddSeconds(1)`, `:16-23`).

`MAC/Interop/AppPaths.cs:4-7`: `DataDirectory` =
`<UserProfile>/Library/Application Support/EndfieldChargePlusForMacOS`.

`MAC/Customization/SecretStore.cs`: `private const string Prefix = "macos-keychain-v1:"` (`:7`);
`account = Convert.ToHexString(SHA256.HashData(UTF8(plain)))` (`:12`) — the Keychain *account* is a
hash of the secret, and the settings value is `Prefix + account` (`:15`); `Unprotect` returns `""`
unless the value carries that prefix and the OS is macOS (`:19`).

#### macOS GPU variables

`MAC/Customization/MacVariableProvider.cs:45-56`: `gpu.name`, `gpu.count`,
`gpu.unified_memory`, `gpu.recommended_working_set_bytes`, `gpu.low_power`, `gpu.removable`.
`ReadGpus` caches the device list for **10 seconds** (`:27`). `MacVariableCatalog.cs:62-65` documents
the intent: "Metal hasUnifiedMemory，不将统一内存当作独立显存" and
"Metal recommendedMaxWorkingSetSize；是预算，不是显存容量或 GPU 已用内存". There is deliberately
**no** `gpu.usage`, `gpu.temperature`, `gpu.memory_used_bytes` on macOS
(readme claim `MC/README.md:39,44`; test asserts absence, `MAC/Tests/Program.cs:122`).

### 1.4 Capability detection and degradation — exact mechanism and markers

Two independent mechanisms exist. Do not conflate them.

**(a) Capability catalogue (which keys are *advertised*)**

- Linux `LINUX/Customization/LinuxVariableCatalog.cs`:
  - `Detected` (`:31-34`) is a lazily built `IReadOnlySet<string>` from `Refresh()`.
  - `Refresh()` (`:36-61`): returns immediately unless `OperatingSystem.IsLinux()`; constructs
    `new LinuxVariableProvider()`, `Collect(measured, null, null)`, then
    `Thread.Sleep(80)` and a **second** `Collect` (`:42-44`) with the comment
    `// CPU/process/disk rates require a real second sample, never a fabricated initial zero.`
    `_detected` = keys whose value is non-null (`:45`). GPU keys are detected **per adapter**
    (`:47-54`) and merged (`:53`).
  - `Supports(string key)` (`:70-78`), in order:
    1. `custom.*` → always `true` (`:72`);
    2. key ∈ `Shared` allowlist (`:12-29`) or ∈ `Detected` → `true`;
    3. key **not** present in `VariableCatalog.PlatformIndependentDefinitions` → `false`;
    4. `time.*` or `deepseek.*` → `true`;
    5. `probe.*` / `ping.*` **except** `probe.ttl` and `ping.ttl` → `true`.
  - `Shared` (`:12-29`) is the OS-independent allowlist: `system.*`, `app.*`, `network.download_bps
    upload_bps total_bps download_mbps upload_mbps total_mbps display_download display_upload
    profile_percent profile_percent_text profile_percent_bps profile_percent_mode
    total_received_bytes total_sent_bytes total_transferred_bytes active_interface_count
    interface_names interface_types ipv4_addresses ipv6_addresses default_gateways dns_servers
    available packets_received packets_sent receive_errors send_errors public_ipv4 public_ipv6`,
    `display.*` (px variants), `clipboard.*`, `app.theme app.preset_name app.active_profile`.
  - `Build` (`:80-108`) filters the original definitions and appends Linux-only definitions
    (`memory.swap_total_bytes`, `memory.swap_available_bytes`, `memory.swap_used_bytes`,
    `memory.swap_usage`, `process.count`, `system.kernel_version`, `system.reboot_required`)
    **only when `Detected` contains them** (`:85-95`), then clones `disk.system.*` into
    `disk.mount_<hash>.*` for every real mount (`:96-106`), and de-duplicates by key (`:107`).
  - `AdaptProfiles` (`:135-165`) rewrites built-in schemes instead of showing blanks:
    `system.battery` is **removed** when `battery.percent` is not detected (`:143`), and degraded to
    `PrimaryTemplate = "{battery.status_text}" / SecondaryTemplate = ""` when
    `battery.full_mwh` or `battery.remaining_mwh` is missing (`:144-145`);
    `system.cpu` → `PrimaryTemplate="{cpu.logical_processors}", SecondaryTemplate=" CPU"` when
    `cpu.frequency_ghz` is missing (`:147-148`); `system.gpu` removed when `gpu.name` is missing,
    else `{gpu.name}` with `RightTemplate="{gpu.count}", RightSuffix=" GPU", ProgressVariable="", ProgressMax=1`
    when `gpu.usage` is missing (`:149-157`); `deepseek.balance-period` →
    `PrimaryTemplate="{deepseek.balance_text}"` (`:158-159`); `network.ping` →
    `PrimaryTemplate="{probe.latency_text}"` (`:160-161`).
  - `RemoveUnsupportedReferences` (`:169-183`) deletes unsupported `{tokens}` from every template
    of imported profiles, clears an invalid `ProgressVariable`, and drops `ColorRules` whose
    `Variable` is unsupported. Comment `:167-168`: "Imported custom profiles are not destroyed.
    … settings.previous.json and import backups preserve the original content."
- macOS `MAC/Customization/MacVariableCatalog.cs`:
  - `UiKeys` (`:9-19`) is an explicit allowlist for UI-only keys, including
    `time.world.nyc|london|tokyo|beijing`, `time.target.*`, `time.display.*`, the point-based
    `display.virtual_*_points` keys, and `network.public_ipv4|public_ipv6|display_*|profile_*`.
  - `MacSharedKeys.Keys` (`MAC/Customization/MacSharedKeys.cs:4-66`) is the OS-independent set
    (`app.*`, `system.*` minus `system.user_domain`/`system.os_build`, `time.*` including
    `time.day|week|month|year` elapsed/progress/remaining and `time.current|current_12h|date|datetime|iso|…`).
  - `Refresh()` (`:24-38`) samples once, then retries `Thread.Sleep(200)` up to 6 times while
    `!values.ContainsKey("cpu.usage")` (`:31-35`).
  - `Supports` (`:39-47`): `custom.*` always; `MacSharedKeys.Keys` ∪ `UiKeys` ∪ `Detected`;
    must exist in `PlatformIndependentDefinitions`; `deepseek.*` always;
    `probe.*`/`ping.*` except `probe.ttl`/`ping.ttl`.
  - `Build` (`:48-74`) appends macOS-only definitions such as `memory.free_bytes`, `memory.active_bytes`,
    `memory.inactive_bytes`, `memory.wired_bytes`, `memory.swap_*`, `disk.system.available_bytes`,
    `battery.time_to_full_seconds`, `gpu.unified_memory`, `gpu.recommended_working_set_bytes`,
    `gpu.low_power`, `gpu.removable`, `display.virtual_*_points` (`:53-69`).
  - `AdaptProfiles` (`:93-115`) always sets `system.battery` → `{battery.status_text}`,
    `system.cpu` → `{cpu.logical_processors} CPU`, `system.gpu` → `{gpu.name}` + `{gpu.count} GPU`
    with `ProgressVariable=""`, and then runs `RemoveUnsupportedReferences` (`:112`).
  - `Describe` (`:75-92`) rewrites Windows-flavoured descriptions:
    `.Replace("Windows","macOS").Replace("Eastern Standard Time","America/New_York").Replace("China Standard Time","Asia/Shanghai")` (`:89-91`).

**(b) Runtime degradation (what a *requested* key becomes when it cannot be measured)**

Priority order, exactly as implemented:

1. **Omitted** — the default for hardware. `Put` skips `null` (`LN/…/LinuxVariableProvider.cs:327-330`);
   `LinuxVariableCatalog.Supports` prevents an unsupported key from ever being advertised;
   macOS native helpers simply do not set a key they cannot measure
   (`MAC/native/ecpmac.m:1` comment: "Missing measurements are omitted, never fabricated").
2. **Explicit localized status string written into the slot** — for *network-facing* and
   *battery-estimate* values (see the exact marker list below).
3. **Template placeholder `--`** — `TemplateEngine.Render` returns the literal `--` for a
   variable that is absent or null (`LN/Customization/TemplateEngine.cs:24`, `:33`), and `--` for a
   non-evaluable `{=expression}` token (`:24`). `BaseFormat` is also where a numeric format applied
   to a string value silently degrades to the bare string (`:214-241`).

**Exact unavailable/status markers (quote-ready):**

| Marker (zh / en) | Written to | Reference |
| --- | --- | --- |
| `当前无法估算` / `Estimate unavailable` | `battery.time_remaining_*`, `battery.estimated_time_to_empty` | `LN/…/LinuxVariableProvider.cs:250` |
| `未放电` / `Not discharging` | same keys when not discharging | `:251` |
| `未充电` / `Not charging` | `battery.estimated_time_to_full` | `:255` |
| `充放电混合` / `Charging and discharging` | `battery.status_text` | `:199` |
| `充电中` / `Charging`, `使用电池` / `On Battery`, `已充满` / `Full`, `未充电` / `Not charging` | `battery.status_text` | `:200-203` |
| `交流电源` / `AC`, `电池` / `Battery` | `battery.power_source` | `:204` |
| `未运行` / `Not running` | `dev.llm.local_status` | `LN/…/LinuxSystemVariables.cs:108` |
| `请配置 API Key` / `Set API key` | all seven `deepseek.*` value keys | `LN/…/VariableHub.cs:1373`, `:1429-1433` |
| `API 请求失败` / `API request failed` | same | `:1425` |
| `可用` / `Available`, `不可用` / `Unavailable` | `deepseek.available_text` | `:1438` |
| `数据源请求失败` / `Source request failed` | every `custom.<src>.<field>` | `:1548` |
| `JSON 路径不存在` / `JSON path missing` | that one field | `:1561` |
| `JSON 数据无效` / `Invalid JSON` | every field of that source | `:1568` |
| `JSON 值为空` / `JSON value is null` | that field (value present but null) | `:1560` |
| `IPv4 查询失败` / `IPv4 lookup failed`, `IPv6 查询失败` / `IPv6 lookup failed` | `network.public_ipv4`, `.public_ipv6` | `LN/…/AdvancedVariableProvider.cs:581-582` |
| `检测失败` / `Failed` | probe `status_text` | `:874`, `:1040`, `:1044`, `:1143` |
| `等待检测` / `Waiting`, `尚未完成检测` / `Probe not completed` | first-snapshot probe placeholder | `:894` |
| `超时` / `Timed out`, `连接失败` / `Connection failed`, `端口不可达` / `Port unreachable`, `不可达` / `Unreachable`, `主机不可达` / `Host unreachable`, `网络不可达` / `Network unreachable`, `目标无效` / `Invalid target`, `路由无效` / `Invalid route`, `TTL 已过期` / `TTL expired`, `在线` / `Online` | probe `status_text` | `:1147-1158` |
| `当前协议不适用` / `Not applicable to this protocol` | `probe.tcp_connect_time` for non-TCP | `:987` |
| `--` | any absent/null template variable | `LN/Customization/TemplateEngine.cs:24`, `:33` |

**Documented degradation contract** (Linux README, verbatim `LN/README.linux.md:50`):
"API 数据仍需有效配置，网络失败显示明确状态，不伪造余额、IP 或 `999 ms` 延迟。"
macOS equivalently `MC/README.md:44`: "外部服务失败时显示真实错误状态，不伪造测量值。"
and `MAC/Customization/MacVariableCatalog.cs:83`: "不提供系统未公开的全局 GPU 使用率、温度和显存占用".

**Observed capability set on the validation machine** (`LN/artifacts/linux-audit/VARIABLE-AUDIT.md:3`):
`Advertised variables: 339. Missing values: 0.` The "Removed or unavailable on this machine"
section (`:349` onward, 121 keys) is the authoritative example of the allowlist in action; it
includes the whole Windows-only set (`security.defender.*`, `security.bitlocker.*`, `dev.wsl.*`,
`cpu.dpc_time`, `gpu.usage_3d`, `memory.paged_pool_bytes`, `battery.saver_on`, `network.vpn_status`,
`system.defender_status`, `system.wsl_status`, …) plus genuinely unavailable Linux metrics
(`cpu.max_frequency_ghz`, `cpu.temperature_max`, `battery.chemistry`, `battery.cycle_count`,
`probe.ttl`, `ping.ttl`, `gpu.power_limit_w`, `system.power_plan`, `network.dns_latency_ms`, …).
Note `probe.ttl` / `ping.ttl` are *advertised* in the shared catalog yet explicitly excluded from
`Supports` on both ports (`LN/LinuxVariableCatalog.cs:77`, `MC/MacVariableCatalog.cs:46`).

Two extra degradation details worth copying:

- Non-NVIDIA DRM adapters that lack `mem_info_vram_*` produce a GPU profile showing only the real
  name and count — asserted at `LN/Tests/Program.cs:72-75`:
  `"A different GPU must not inherit unavailable metrics."`
- `LinuxNvidia.Parse` of an unsupported field must not become zero — asserted at
  `LN/Tests/Program.cs:78`: `"NVIDIA unsupported fields must not become zeroes."`
- Missing sensors must not become zero — asserted at `LN/Tests/Program.cs:34`:
  `"Missing sensors must not become fake zeroes."`

---

## 2. Network packet probe (probe.* / ping.*)

Implementation is byte-for-byte equivalent in both ports apart from the OS guard:
`LN/Customization/VariableHub.cs:828-1158` and `MC/Customization/VariableHub.cs:189-518`.

### 2.1 Constants, defaults, configuration

| Item | Value | Reference |
| --- | --- | --- |
| `PingTimeoutMs` | `1000` | `LN/…/VariableHub.cs:37`, `MC/…/VariableHub.cs:36` |
| `PingFullScaleMs` | `999d` | `LN:38`, `MC:37` |
| minimum interval between probes | `now.AddMilliseconds(800)` per state key | `LN:861`, `MC:222` |
| rolling history depth | `20` (`while (state.History.Count > 20) Dequeue()`) | `LN:881-883`, `MC:242-244` |
| default target | `"1.1.1.1"` when blank | `LN:835`, `MC:196` |
| protocol normalisation | `.Trim().ToUpperInvariant()`; only `"TCP"` / `"UDP"` accepted, everything else → `"ICMP"` | `LN:991-995`, `MC:352-356` |
| port clamp | `Math.Clamp(configuredPort <= 0 ? 443 : configuredPort, 1, 65535)` | `LN:837`, `MC:198` |
| state key | `$"{protocol}|{target}|{(protocol == "ICMP" ? 0 : port)}"` | `LN:838`, `MC:199` |
| profile config fields | `PingTarget="1.1.1.1"`, `ProbeProtocol="ICMP"`, `ProbePort=443` | `LN/Customization/Models.cs:62-64` |

The probe is only invoked when `ping.` or `probe.` is in the request set
(`LN/…/VariableHub.cs:151,197-198`). Switching UI language clears all ping state and
rebuilds the localized status text (`LN:119-124`, `MC:47-51`).

### 2.2 Algorithm (single-flight, no retry queue)

1. Resolve `target`, `protocol`, `port`, `stateKey` (`LN:835-838`).
2. Under `_pingGate`: get-or-create `PingTargetState`. Issue a new probe **only if**
   (`state.LastProbe is null || now >= state.NextProbeUtc`) **and**
   (`state.InFlight is null || state.InFlight.IsCompleted`). Then
   `state.InFlight = ProbeEndpointAsync(...)` and `state.NextProbeUtc = now.AddMilliseconds(800)`
   (`LN:844-865`). There is **no retry counter and no backoff** — a failed probe is simply the
   next sample; the 800 ms floor is the only rate limit.
3. Await the in-flight task; a non-cancellation exception becomes
   `PingProbeResult(false, IPStatus.Unknown, 0, "", 0, "检测失败"/"Failed", ex.Message, DateTime.Now)`
   (`LN:867-875`). `OperationCanceledException` is rethrown.
4. Store `LastProbe`, clear `InFlight`, enqueue into the 20-slot history, record
   `LastSuccessLocal` on success (`LN:877-887`).
5. Compute and publish (below). Percentages/statistics are recomputed on **every** snapshot, even
   when no new probe ran, so the HUD keeps showing the last state.

### 2.3 Per-protocol transports

`ProbeEndpointAsync` dispatch: `"TCP"` → `ProbeTcpAsync`, `"UDP"` → `ProbeUdpAsync`, default →
`ProbeIcmpAsync` (`LN:997-1003`, `MC:358-364`).

**ICMP** — `ProbeIcmpAsync` (`LN:1005-1046`):

- Domain resolution: if `!IPAddress.TryParse(target, out _)`, run
  `await Dns.GetHostAddressesAsync(target).WaitAsync(ct)` around a `Stopwatch`, store
  `dnsMs = dns.Elapsed.TotalMilliseconds`, then pick
  `addresses.FirstOrDefault(x => x.AddressFamily is AddressFamily.InterNetwork or AddressFamily.InterNetworkV6)`.
  A literal IP sets `dnsMs = 0d`. There is **no DNS result caching** and **no A-vs-AAAA preference**
  beyond "first address of either family".
- Transport: `System.Net.NetworkInformation.Ping` — `await ping.SendPingAsync(probeTarget, PingTimeoutMs).WaitAsync(ct)`.
  Works IPv4 and IPv6, unprivileged, on Linux (validation: `LN/docs/linux-validation.md:11`
  "普通用户 ICMP 回环 … 通过") and macOS (`MAC/Tests/Program.cs:155` "ICMP loopback works without root").
- Result: success ⇔ `reply.Status == IPStatus.Success`; latency = `reply.RoundtripTime`;
  address = `reply.Address?.ToString() ?? ""`; **TTL = `reply.Options?.Ttl ?? 0`**;
  `ReplyStatus = reply.Status.ToString()` (e.g. `Success`, `TimedOut`, `DestinationHostUnreachable`).
- `PingException` → `ReplyStatus = (ex.InnerException?.Message ?? ex.Message)`; any other exception →
  `ex.Message`; `StatusText` = `检测失败`/`Failed` (`LN:1038-1045`).

**TCP** — `ProbeTcpAsync` (`LN:1048-1096`):

- Resolution as above (`Dns.GetHostAddressesAsync`); no address → `SocketException((int)SocketError.HostNotFound)`.
- `using var tcp = new TcpClient(address.AddressFamily);` a linked
  `CancellationTokenSource` with `timeout.CancelAfter(PingTimeoutMs)`.
- **Two stopwatches**: `total` measures the whole operation → `LatencyMs`; `connect` measures only
  `TcpClient.ConnectAsync` → `TcpConnectMs`. `ReplyStatus = "Connected"`; remote address from
  `tcp.Client.RemoteEndPoint`.
- `OperationCanceledException` while `ct` was **not** cancelled → `超时`/`Timed out`,
  `ReplyStatus = "TimedOut"`, latency 0 (`:1080-1084`).
- `SocketException` → `连接失败`/`Connection failed` with `ReplyStatus = ex.SocketErrorCode.ToString()`
  (`:1086-1090`).

**UDP** — `ProbeUdpAsync` (`LN:1098-1145`):

- `UdpClient(address.AddressFamily)`, `udp.Connect(new IPEndPoint(address, port))`, payload
  `byte[] payload = { 0x00 }`, `SendAsync`, then
  `await udp.ReceiveAsync().WaitAsync(TimeSpan.FromMilliseconds(PingTimeoutMs), ct)`.
- Success **requires an actual reply** (`ReplyStatus = "Response"`); latency = one stopwatch around
  send+receive. A closed port surfaces as `SocketException` with `SocketError.ConnectionReset` →
  `端口不可达`/`Port unreachable`; other socket errors → `检测失败`/`Failed`;
  `TimeoutException` → `超时`/`Timed out`, `ReplyStatus = "TimedOut"`.
  **A UDP probe therefore only measures a peer that echoes; it is not an ICMP-unreachable probe.**
  This is exercised with a real loopback echo server in `LN/Tests/LinuxGuiAudit.cs:126-130`
  and `MAC/Tests/Program.cs:156-165`.

### 2.4 Latency / loss / smoothing maths

`LN/…/VariableHub.cs:899-914` (identical `MC:260-275`):

```
sent      = history.Count
received  = history.Count(x => x.Success)
lost      = Max(0, sent - received)
loss      = sent <= 0 ? (current.Success ? 0d : 100d) : lost * 100d / sent
avg/min/max = over history.Where(x => x.Success).Select(x => (double)x.LatencyMs); 0d when none
jitter    = (successLatencies.Count > 1)
            ? sum(|lat_i - lat_{i-1}| for i in 1..n-1) / (n - 1)      // mean absolute adjacent delta
            : 0d
latencyForProgress = current.Success ? current.LatencyMs : PingFullScaleMs
latencyProgress    = Clamp(latencyForProgress / PingFullScaleMs * 100d, 0d, 100d)
latencyMs          = current.Success ? current.LatencyMs : PingFullScaleMs
latencyText        = current.Success ? $"{current.LatencyMs}ms" : current.StatusText
```

**Smoothing is exactly one mechanism**: a 20-sample rolling window. There is no EMA, EWMA,
median filter, or packet-size/sequence configuration. `jitter` is the mean absolute adjacent
difference over successful samples only.

### 2.5 Published variables

`probe.*` (`LN:923-948`, `MC:284-309`):

| Key | Type / value |
| --- | --- |
| `probe.target` | configured target (default `1.1.1.1`) |
| `probe.address` | address that actually replied/connected (empty string on failure) |
| `probe.protocol` | `ICMP` \| `TCP` \| `UDP` |
| `probe.port` | `0` for ICMP, else the clamped port |
| `probe.endpoint` | `target` for ICMP, else `"{target}:{port}"` |
| `probe.online` | bool (`current.Success`) |
| `probe.status_text` | localized status |
| `probe.reply_status` | raw transport status (`Success`/`Connected`/`Response`/`TimedOut`/`SocketErrorCode`/exception text) |
| `probe.latency_ms` | ms; **999** when the last probe failed (Windows semantics) |
| `probe.latency_text` | `"{ms}ms"` on success, else `StatusText` |
| `probe.latency_progress` | `clamp(latency_ms/999*100, 0, 100)` |
| `probe.full_scale_ms` | constant `999` |
| `probe.timeout_ms` | constant `1000` |
| `probe.ttl` | ICMP reply TTL, 0 for TCP/UDP |
| `probe.sent`, `probe.received`, `probe.lost` | ints from the 20-sample window |
| `probe.loss_percent` | `clamp(loss, 0, 100)` |
| `probe.avg_latency_ms`, `probe.min_latency_ms`, `probe.max_latency_ms`, `probe.jitter_ms` | ms |
| `probe.last_success` | `yyyy-MM-dd HH:mm:ss` local, or `""` |
| `probe.error` | `""` on success, else `ReplyStatus` (`PingProbeResult.Error`, `LN:1860`) |
| `probe.dns_resolve_time` | ms — **only when `DnsResolveMs.HasValue`** (`LN:947`) |
| `probe.tcp_connect_time` | ms — **only when `TcpConnectMs.HasValue`** (`LN:948`) |

`ping.*` compatibility aliases (`LN:950-974`, `MC:311-335`) — comment
`// Backward-compatible ping.* aliases remain available for existing custom schemes.`:
everything above is duplicated under `ping.` with **one difference**:
the progress key is `ping.progress` (`LN:961`), *not* `ping.latency_progress`.
`probe.ttl` ↔ `ping.ttl` (`LN:936`, `:964`).

### 2.6 Linux/macOS override: failed probes must not fabricate `999`

`LN/Customization/VariableHub.cs:975-988` and `MC/Customization/VariableHub.cs:336-349`
(guarded by `OperatingSystem.IsLinux()` / `OperatingSystem.IsMacOS()`):

```
// A failed probe has no measured latency. Keep its real status, never a fake 999 ms.
foreach (string prefix in new[] { "probe", "ping" })
{
    if (!current.Success) v[prefix + ".latency_ms"] = current.StatusText;
    if (successLatencies.Count == 0)
        foreach (string metric in new[] { "avg_latency_ms", "min_latency_ms", "max_latency_ms", "jitter_ms" })
            v[prefix + "." + metric] = current.StatusText;
}
if (!current.DnsResolveMs.HasValue) v["probe.dns_resolve_time"] = current.StatusText;
if (!current.TcpConnectMs.HasValue) v["probe.tcp_connect_time"] = protocol == "TCP"
    ? current.StatusText : LocalizationManager.Text("当前协议不适用", "Not applicable to this protocol");
```

So on Linux/macOS `probe.latency_ms` is a **string** whenever the last probe failed, whereas the
Windows tree leaves it numeric `999` (`WIN/Customization/VariableHub.cs`, documented in
`docs/audit/01-windows-core.md:396,846`). The Linux/macOS behaviour is the one the port should copy;
it is locked by tests:
`LN/Tests/LinuxGuiAudit.cs:134` — `"Failed TCP probes must not fabricate latency numbers."` and
`MAC/Tests/Program.cs:153` — `"Failed connection reports status without a fabricated latency"`.

Note: for a **direct IP** target `DnsResolveMs = 0d` (has value), so `probe.dns_resolve_time = 0`
*is* published; the string substitution only happens when resolution never ran (early failure).

### 2.7 Exact HUD text forms

The built-in probe profile:

- `LN/Customization/Models.cs:279-305` (`BuiltInKey = "network.ping"`, `Name = "网络包探测器"`,
  `TaglineTemplate = "/// NETWORK PROBE"`, `TitleTemplate = "网络包探测器"`):
  - `:288` `PrimaryTemplate = "{probe.latency_ms|0}ms"`
  - `:290` `RightTemplate = "丢包{probe.loss_percent|0}"`
  - `:291` `RightSuffix = "%"`
  - `:292` `ProgressVariable = "probe.loss_percent"`, `ProgressMin = 0`, `ProgressMax = 100`
  - `:302-303` `ColorRules`: `probe.loss_percent >= 30` → `#FF4D4F`; `>= 10` → `#FFB84D`
  - `:297-299` `PingTarget = "1.1.1.1"`, `ProbeProtocol = "ICMP"`, `ProbePort = 443`
- `MAC/Customization/Models.cs:281-304` is the same profile: `:288` `PrimaryTemplate = "{probe.latency_ms|0}ms"`,
  `:290` `RightTemplate = "丢包{probe.loss_percent|0}"`, `:291` `RightSuffix = "%"`,
  `:297-299` `PingTarget/ProbeProtocol/ProbePort = "1.1.1.1"/"ICMP"/443`, `:302-303` the same colour rules.
  English is handled by `MAC/Customization/BuiltInProfileLocalization.cs:50-53`
  (`Category = "Network", Name = "Packet Probe", TitleTemplate = "Packet Probe"`,
  `RightTemplate = "Loss {probe.loss_percent|0}"`), i.e. the same rewrite as Linux.
- English: `LN/Customization/BuiltInProfileLocalization.cs:50-54` rewrites only
  `RightTemplate = "Loss {probe.loss_percent|0}"` and
  `Category = "Network", Name = "Packet Probe", TitleTemplate = "Packet Probe"`;
  `RightSuffix` stays `"%"`.
- `LN/Customization/LinuxVariableCatalog.cs:160-161` replaces the Linux `PrimaryTemplate` with
  `{probe.latency_text}` (so Linux shows the status text instead of a numeric ms on failure).
- Fallback engine: `LN/Customization/TemplateEngine.cs:33` —
  `if (!vars.TryGetValue(key, out var value) || value is null) return "--";`
  (`:24-26` the same for `{=expression}` tokens).
- Composition: `LN/Customization/HudProfileRenderer.cs:26-27` renders `RightTemplate` and
  `RightSuffix` separately, so the suffix `%` is still appended.

Therefore the **exact** rendered forms are:

| Situation | PrimaryText | RightText (+ suffix) |
| --- | --- | --- |
| `probe.latency_ms` absent/null (the brief's "`??ms`") | `--ms` | — |
| `probe.loss_percent` absent/null, zh (the brief's "`丢包??%`") | — | `丢包--%` |
| `probe.loss_percent` absent/null, en (the brief's "`Loss ??%`") | — | `Loss --%` |
| successful probe | `"<RTT>ms"` (e.g. `20ms`) | `丢包0%` / `Loss 0%` |
| failed probe, Linux/macOS | `"<status>ms"` e.g. `超时ms`, `端口不可达ms` (string falls through `BaseFormat`, `TemplateEngine.cs:214-241`) | `丢包100%` / `Loss 100%` |
| failed probe, Windows | `999ms` | `丢包100%` / `Loss 100%` |
| Linux (AdaptProfiles) | `{probe.latency_text}` → `20ms` or `超时` | unchanged |

**Uncertainty noted:** the brief wrote the placeholders as `??ms` / `丢包??%` / `Loss ??%`.
No literal `??` token exists anywhere in the Linux, macOS, Windows or upstream trees
(verified by exhaustive search over the three ECP trees and `upstream-zmd-charge`); the real
sentinel is `--` as shown above. The `??` sequences that *do* appear are prose in catalog
descriptions, e.g. `LN/Customization/VariableCatalog.cs:305,310,326`
(`"剩余??%"`, `"高峰已过??%"`) — documentation wording for "a number goes here", not a runtime marker.

---

## 3. DeepSeek API

`LN/Customization/VariableHub.cs:1368-1511`, `MC/Customization/VariableHub.cs:521-664`.

### 3.1 Endpoint, request, response

- Endpoint: **`GET https://api.deepseek.com/user/balance`** (`LN:1385`, `MC:538`) — the only
  DeepSeek URL in the trees (`api.deepseek.com` in the Linux audit fixture, `LN/Tests/LinuxGuiAudit.cs:160`).
- Header: `req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", apiKey)` (`LN:1386`).
- Client: the shared `static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(8) }`
  (`LN:23`, `MC:22`) — the same instance used for custom HTTP sources. No custom user-agent,
  no retry handler.
- `res.EnsureSuccessStatusCode()` then `JsonDocument.Parse(await res.Content.ReadAsStringAsync(ct))`
  (`LN:1390-1391`).
- Response fields consumed:
  - `is_available` — counted as available only when `ValueKind == JsonValueKind.True` (`LN:1393`);
  - `balance_infos` — array; entries are skipped unless
    `currency` equals `"CNY"` (OrdinalIgnoreCase) (`LN:1397-1404`);
  - per CNY entry: `total_balance`, `granted_balance`, `topped_up_balance` (`LN:1412-1414`).
- Parsing strictness (Linux/macOS only, `LN:1405-1418`): each of the three fields must exist, parse
  with `NumberStyles.Float` + `InvariantCulture`, and be finite, otherwise
  `throw new JsonException("Invalid balance response.")`; and if no CNY entry was found,
  `throw new JsonException("Missing CNY balance.")`. (Windows uses the lenient
  `ReadJsonNumber` helper, `LN:1710-1718`, which returns 0 for anything unparseable.)
- Latency: `var apiTimer = Stopwatch.StartNew(); … apiTimer.Stop();` → `LatencyMs`
  (`LN:1387-1389`) → `deepseek.api.latency_ms`.

Fixture that locks the schema in (`LN/Tests/LinuxGuiAudit.cs:160`):
`{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"12.50","granted_balance":"2.50","topped_up_balance":"10.00"}]}`
with assertion `Convert.ToDouble(values["deepseek.balance"]) == 12.5` (`:62`).

### 3.2 Published variables and failure contract

`CopyDeepSeek` (`LN:1435-1444`):

| Key | Value |
| --- | --- |
| `deepseek.available` | bool |
| `deepseek.available_text` | `可用` / `不可用` | `Available` / `Unavailable` |
| `deepseek.balance` | double (sum of CNY `total_balance`) |
| `deepseek.balance_text` | `$"¥{c.Total:0.00}"` |
| `deepseek.granted_balance` | double |
| `deepseek.topped_up_balance` | double |
| `deepseek.api.latency_ms` | double (ms) |

`DeepSeekFailure(IDictionary, string status)` (`LN:1429-1433`) writes the **same string into all
seven keys**:

```
foreach (var key in new[] { "available", "available_text", "balance", "balance_text",
                            "granted_balance", "topped_up_balance", "api.latency_ms" })
    v["deepseek." + key] = status;
```

Failure triggers: empty/missing key → `请配置 API Key` / `Set API key` (`LN:1373`, only on
Linux/macOS); any exception other than caller cancellation → `API 请求失败` / `API request failed`
(`LN:1423-1426`). The comment in the capability catalog (`LN/LinuxVariableCatalog.cs:115`) states
the intent: "需配置有效 API Key；未配置或请求失败时显示明确状态，不返回伪造余额。"
Test lock: `LN/Tests/LinuxGuiAudit.cs:123` `"Missing credentials must show a configuration state."`
and `:141` `"HTTP failures must show status instead of fake data or placeholders."`

### 3.3 Refresh scheduling / backoff

- Cache record: `private sealed record DeepSeekCache(bool Available, double Total, double Granted,
  double Topped, double LatencyMs, DateTime ExpiresAt)` (`LN:1838`, `MC:942`).
- Reuse condition (`LN:1377-1381`): `_deepSeekCacheKey == apiKey && _deepSeekCache is { } cache &&
  DateTime.UtcNow < cache.ExpiresAt` → copy cached values, no HTTP.
- TTL: `DateTime.UtcNow.AddMinutes(1)` (`LN:1420`, `MC:573`) — **1 minute**.
- **No exponential backoff and no retry.** A failure is *not* cached (`_deepSeekCache` is only
  assigned on success, `LN:1419-1420`), so the next snapshot that requests a `deepseek.*` key will
  attempt the request again. The practical request rate is therefore bounded by the HUD render
  cadence (max ~1/s for a persistent clock-accurate profile) but a failing endpoint can be hit once
  per second.
- Request gating: `deepseek.period.*` is computed locally and never triggers HTTP
  (`NeedsOnlyPeriodVariables`, `LN:1585-1590`; `LN:204-209`).

### 3.4 Peak / off-peak (高峰 / 低谷) derivation

- `GetDeepSeekPeriodState` (`LN:1452-1468`):
  - Beijing time: `TimeZoneInfo.ConvertTime(DateTimeOffset.UtcNow, TimeZoneInfo.FindSystemTimeZoneById("China Standard Time")).DateTime`;
    on any exception it falls back to `DateTime.UtcNow.AddHours(8)` (`LN:1454-1462`).
  - `windows = ParseWindows(settings.DeepSeekPeakWindows)`.
  - `weekday = beijing.DayOfWeek is >= DayOfWeek.Monday and <= DayOfWeek.Friday`.
  - `peak = weekday && windows.Any(w => beijing.TimeOfDay >= w.Start && beijing.TimeOfDay < w.End)`
    (`:1465-1466`). **Weekends are always off-peak.**
- `ParseWindows` (`LN:1734-1747`): split on `';'`, each part split on `'-'` (max 2), both sides
  `TimeSpan.TryParse`, require `b > a`, sort by start. Default from settings:
  `DeepSeekPeakWindows = "09:00-12:00;14:00-18:00"` (`LN/Customization/Models.cs:96`).
- `FindNextTransition` (`LN:1749-1765`): scans `for (int d = 0; d < 8; d++)` over days from today,
  weekday-only, returns the first window `Start` after `now`, else the first window `End` after
  `now`; fallback `now.AddHours(1)`.
- `FindCurrentSegmentStart` (`LN:1767-1787`): peak → today's containing window `Start`;
  off-peak → the latest previous weekday window `End` (`<= now`) searching back up to 8 days;
  fallback `now.AddHours(-1)`.
- `remaining = Max(0, (next - beijing).TotalSeconds)`; `total = Max(1, (next - segmentStart).TotalSeconds)`;
  `progress = Clamp((beijing - segmentStart).TotalSeconds / total * 100, 0, 100)` (`LN:1473-1477`).
- `GetDeepSeekPeriodNameZh` (`LN:1446-1450`) returns `高峰` or `低谷`; the HUD runtime polls it
  every 250 ms and raises a boundary event with a forced `Full` animation
  (`LN/Customization/CustomHudRuntime.cs:335-361`, `:483-488`).

Published keys (`LN:1488-1510`), with exact text forms:

| Key | Value |
| --- | --- |
| `deepseek.period.name` | `PEAK` / `OFF-PEAK` |
| `deepseek.period.name_zh` | `高峰` / `低谷`, but `PEAK` / `OFF-PEAK` when the UI is English (`:1489`) |
| `deepseek.period.is_peak`, `.is_off_peak` | bool |
| `deepseek.period.remaining_seconds` | double |
| `deepseek.period.remaining_text` | `"{高峰\|低谷}时段剩余{FormatDuration(remaining)}"` / `"{Peak\|Off-peak} left {…}"` (`:1481-1483`) |
| `deepseek.period.progress` | 0-100 |
| `deepseek.period.progress_text` | `"{高峰\|低谷}已过{N}%"` / `"{Peak\|Off-peak} {N}%"`, `N = Math.Round(progress, MidpointRounding.AwayFromZero)` (`:1484-1486`) |
| `deepseek.period.next_switch_time`, `.next_switch_datetime` | Beijing, `HH:mm:ss` / `yyyy-MM-dd HH:mm:ss` |
| `deepseek.period.next_switch_time_local`, `.next_switch_datetime_local` | same instant via `ToLocalTime()` |
| `deepseek.period.timezone` | `北京时间 (UTC+08:00)` / `Beijing Time (UTC+08:00)` |
| `deepseek.period.local_timezone` | `"{TimeZoneInfo.Local.Id} (UTC{±HH:mm})"` |

`FormatDuration` (`LN:1720-1725`) → `"{(int)TotalHours:00}:{Minutes:00}:{Seconds:00}"`.
Built-in profile `deepseek.balance-period` (`LN/Customization/Models.cs:262-278`) uses
`PrimaryTemplate = "¥{deepseek.balance|0.00}"`, `TitleTemplate = "DeepSeek 当前{deepseek.period.name_zh}"`,
`RightTemplate = "{deepseek.period.progress_text}"`, `ProgressVariable = "deepseek.period.progress"`.

### 3.5 Key storage

- Linux `LN/Customization/SecretStore.cs`: AES-256-GCM, prefix
  `"linux-aesgcm-v1:"` + base64(`nonce(12) ‖ tag(16) ‖ ciphertext`) (`:18-23`);
  32-byte key at `<SettingsManager.SettingsDirectory>/secrets/master.key`, created with
  `UnixCreateMode = UserRead|UserWrite` and directory `0700`, verified with
  `File.SetUnixFileMode` and re-checked (`:63-91`). Fails closed: if the filesystem cannot hold
  private permissions, `throw new CryptographicException("The key filesystem must support private
  Unix file permissions.")` (`:88-89`). This is **not** an OS keyring — `PRIVACY.md:21-23` says so
  explicitly. Tamper test: `LN/Tests/Program.cs:100`.
- macOS `MC/Customization/SecretStore.cs`: login Keychain, prefix `"macos-keychain-v1:"`,
  account = SHA-256 hex of the secret, service
  `com.glacierglimmer.endfieldchargeplus.macos` (`:7-21`, `MC/native/ecpmac.m:275-301`).
  `Protect` throws `CryptographicException($"Keychain could not save the key (OSStatus {status}).")`
  on failure (`:14`). Foreign blob → `""` (`:19`, test `MAC/Tests/Program.cs:83`).
- Both ports only ever store the **protected** value in `CustomHudSettings.DeepSeekApiKeyProtected`
  (`LN/Customization/Models.cs:95`), and export writes it unchanged (`SettingsManager.ExportToFile`,
  `LN/Settings/SettingsManager.cs:102-114`) — so a cross-platform import needs the key re-entered
  (`LN/README.linux.md:55`, `LN/PRIVACY.md:21`).

### 3.6 Logging redaction

**There is no redaction layer.** Findings, stated plainly:

- `LN/Diagnostics/AppLog.cs:42-66` (and the byte-identical `MC/Diagnostics/AppLog.cs`) writes
  `$"[{timestamp}] [{level}] {message}"` plus the full `Exception.ToString()` to
  `<DataDirectory>/Logs/EndfieldChargePlus-yyyyMMdd.log` and mirrors to `latest.log`.
  There is no regex, no key-name filter and no marker that scrubs API keys, bearer tokens,
  custom header values or URLs.
- What protects secrets instead is **not logging them**: `AddDeepSeekBalanceAsync` never logs the
  key or the request URI; `AddCustomHttpAsync` never logs headers or the body; `SecretStore`
  logs only the constant messages `"Failed to protect the API key."` /
  `"Failed to decrypt the API key."` (`LN:31`, `:58`).
- Custom HTTP header secrets **are** persisted in plaintext in `settings.json` and exports unless the
  user uses the `${env:NAME}` form; `LN/PRIVACY.md:29-31` states exactly that, and
  `ExpandEnvironment` (`LN/…/VariableHub.cs:1659-1663`) is what makes `${env:...}` viable
  (expanded at request time, reference-only on disk).
- Window titles/URLs are never logged either; the only URL-like strings in logs are settings paths
  (`SettingsManager.cs:52,113,122,135`).
- Uncertainty: `AppLog.CurrentLogPath` is process-wide and the exception text of a failed
  `HttpRequestException` can embed the request URI (and, for a malformed custom header,
  `TryAddWithoutValidation` failures are silent, so header values do not reach logs that way).

**Port implication:** an Android port that adds any HttpLoggingInterceptor or verbose
`Log.d` around the DeepSeek/custom-HTTP calls must add the redaction the desktop never needed.

---

## 4. Custom HTTP / JSON data source

`LN/Customization/VariableHub.cs:1513-1571`, plus `LN:1659-1708`;
`MC/Customization/VariableHub.cs:666-...`, plus `MC:818-871`.

### 4.1 Exact configuration shape

`LN/Customization/Models.cs:67-81` (macOS identical, `MC/Customization/Models.cs:67-81`):

```csharp
public sealed record HttpFieldMapping
{
    public string Variable { get; init; } = "value";
    public string JsonPath { get; init; } = "";
}

public sealed record CustomHttpSource
{
    public string Name { get; init; } = "custom";
    public bool Enabled { get; init; } = true;
    public string Url { get; init; } = "";
    public int RefreshSeconds { get; init; } = 60;
    public Dictionary<string, string> Headers { get; init; } = new();
    public List<HttpFieldMapping> Fields { get; init; } = new();
}
```

Stored as `CustomHudSettings.HttpSources` (`LN/Customization/Models.cs:99`). Serialized field names
are the PascalCase property names above (no naming policy, see §7). There is **no** HTTP method,
body, auth-scheme, TLS, or retry field — the schema is GET + headers + JSON paths only.

UI hint from `LN/Customization/HudCustomizerView.axaml.cs:28,74`:
`// fields.jsonPath JSON 字段路径，例如 data.cpu、players[0].name`.

### 4.2 Execution

`AddCustomHttpAsync(v, settings, ct, requested)` (`LN:1513-1571`), gated by
`NeedsPrefix(requested, "custom.")` (`LN:211-212`):

1. Iterate `settings.HttpSources.Where(x => x.Enabled && !string.IsNullOrWhiteSpace(x.Url))` (`:1519`).
2. `sourcePrefix = $"custom.{Sanitize(source.Name)}."`; if a request set was supplied and contains no
   key with that prefix, `continue` (`:1521-1523`).
3. `cacheKey = source.Name + "|" + source.Url` (`:1525`).
4. Cache miss or `DateTime.UtcNow >= cache.ExpiresAt` → build `new HttpRequestMessage(HttpMethod.Get, source.Url)` (`:1530`).
5. Headers: `foreach (var h in source.Headers) { var value = ExpandEnvironment(h.Value);
   req.Headers.TryAddWithoutValidation(h.Key, value); }` (`:1531-1535`) — request headers, not
   content headers; failures of `TryAddWithoutValidation` are silently ignored.
6. `using var res = await _http.SendAsync(req, ct)` — shared client, **8 s timeout** (`LN:23`),
   default TLS validation, default redirect following, no size cap (`:1537`).
7. `res.EnsureSuccessStatusCode()` (`:1538`) — non-2xx throws.
8. `cache = new HttpCacheEntry(await res.Content.ReadAsStringAsync(ct),
   DateTime.UtcNow.AddSeconds(Math.Clamp(source.RefreshSeconds, 5, 86400)))` (`:1539-1542`) —
   refresh clamped to **5 s .. 86 400 s**; only one cache entry per `(Name, Url)`.
9. On any exception: for every configured field,
   `v[sourcePrefix + Sanitize(f.Variable)] = "数据源请求失败" / "Source request failed"`
   (Linux/macOS only — other platforms omit the keys) (`:1544-1550`); the cached old body is *not*
   used.
10. Parse with `JsonDocument.Parse(cache.Json)` and per field:
    `if (TryJsonPath(doc.RootElement, f.JsonPath, out var value))
        v[$"custom.{Sanitize(source.Name)}.{Sanitize(f.Variable)}"] =
            JsonToObject(value) ?? "JSON 值为空" / "JSON value is null";
     else "JSON 路径不存在" / "JSON path missing"` (`:1555-1562`); a document-level parse failure sets
    `"JSON 数据无效" / "Invalid JSON"` on every field (`:1564-1568`).

### 4.3 Key name sanitisation (collision risk)

`Sanitize` (`LN:1665-1666`, `MC:818-819`):

```csharp
private static string Sanitize(string s) =>
    new((s ?? "").ToLowerInvariant().Select(c => char.IsLetterOrDigit(c) || c == '_' ? c : '_').ToArray());
```

Lower-cased; every non-alphanumeric, non-underscore character becomes `_`. Consequently
`"My Source"`, `"my-source"` and `"My.Source"` all collapse to `my_source` and would collide in a
single source's `Fields` list (last write wins). The **original** name is still used for the cache key.

### 4.4 JSON path grammar

`TryJsonPath(JsonElement root, string path, out JsonElement value)` (`LN:1668-1698`, `MC:821-851`):

- empty / `"$"` / `"."` → the root (`:1671`);
- `path.Trim().TrimStart('$').TrimStart('.')`, then split on `'.'`;
- each token may be a property name and/or one or more `[index]` selectors, processed in order;
- property access requires `ValueKind == Object` and `TryGetProperty` (`:1680-1682`);
- index access requires `ValueKind == Array`, a parseable non-negative integer, and
  `idx < GetArrayLength()` (`:1687-1692`);
- any failure returns `false` (→ "path missing").

Supported: `data.cpu`, `players[0].name`, `metrics[0].value`, nested `a[0][1]`.
**Not supported:** wildcards, recursive descent, quoted keys containing `.`, negative indices,
slices, filters, unions, `$..`. Interval test fixture:
`{"data":{"items":[{"value":42}]}}` with path `data.items[0].value` → `42`
(`LN/Tests/LinuxGuiAudit.cs:135,159`).

### 4.5 Value coercion

`JsonToObject(JsonElement e)` (`LN:1700-1708`, `MC:853-861`): `String` → string,
`Number` → `double` (via `TryGetDouble`), `True`/`False` → bool, `Null` → `null`
(converted to the "JSON 值为空" string on Linux/macOS), anything else (object/array) →
`e.GetRawText()` (raw JSON as a string).

### 4.6 Placeholder substitution

There is **no substitution into the request** — `Url` is used verbatim, there is no body, and
headers are static except for `${env:...}`. `ExpandEnvironment` (`LN:1659-1663`) is:

```csharp
private static string ExpandEnvironment(string value) =>
    Regex.Replace(value ?? "", @"\$\{env:(?<n>[A-Za-z_][A-Za-z0-9_]*)\}",
        m => Environment.GetEnvironmentVariable(m.Groups["n"].Value) ?? "");
```

i.e. `${env:NAME}` → the environment variable's value, or `""` when unset; resolved **per request**,
never persisted. Placeholders in the other direction are HUD template tokens
`{custom.<sanitized-source>.<sanitized-field>}` handled by `TemplateEngine`
(`LN/Customization/TemplateEngine.cs:11`), plus the raw value is available in the Variable Library.

### 4.7 Security constraints — what is and is not enforced

| Aspect | Actual behaviour |
| --- | --- |
| TLS | not enforced: any scheme accepted; no certificate pinning; default platform validation only |
| Redirects | default `HttpClientHandler` behaviour (auto-follow, up to 50); not disabled, not restricted |
| Response size | **no limit** — `ReadAsStringAsync` buffers the entire body into memory |
| Timeout | 8 s total, from the shared `static HttpClient` (`LN:23`), not configurable per source |
| Method | GET only |
| Auth | only whatever the user puts in `Headers` |
| Secrets in config | stored verbatim unless `${env:NAME}` is used (`LN/PRIVACY.md:29-31`) |
| Concurrency | one request per `(Name, Url)` per refresh window; the cache is not `lock`-protected |
| Error surfacing | status strings in the variable values; no log entry, no exception propagation |
| URL validation | none beyond `!string.IsNullOrWhiteSpace(x.Url)` |

**Port implication:** on Android, enforce HTTPS for non-loopback hosts (the desktop does not),
cap the response body (e.g. 1 MiB), set explicit connect/read timeouts (there is no shared
client default to inherit), and replace `${env:...}` with a Keystore-backed reference because a
normal Android app has no ambient environment variables.

---

## 5. Time / 日进程 (daily progress)

Two cooperating implementations; both must be reproduced.

### 5.1 Raw clock and calendar → variables

`LN/Customization/VariableHub.cs:220-304` (`AddClockAndSystem`), `MC` equivalent at `:83-...`.
Input is `var now = DateTime.Now` (local wall clock) and `dayTotal = TimeSpan.FromDays(1).TotalSeconds`.

| Variable | Formula | Line |
| --- | --- | --- |
| `time.current` | `now.ToString("HH:mm:ss", InvariantCulture)` | `:270` |
| `time.current_12h` | `"hh:mm:ss tt"` current culture | `:271` |
| `time.date`, `time.datetime`, `time.iso` | `yyyy-MM-dd`, `yyyy-MM-dd HH:mm:ss`, `"O"` | `:272-274` |
| `time.year`, `time.month`, `time.day`, `time.hour`, `time.minute`, `time.second`, `time.millisecond` | components | `:275-286` |
| `time.month_name`, `time.day_of_week`, `time.day_of_week_en`, `time.day_of_year`, `time.week_of_year`, `time.is_weekend` | culture / `ISOWeek.GetWeekOfYear` / Sat-Sun | `:277-287` |
| `time.unix_seconds`, `time.unix_milliseconds` | `DateTimeOffset(now)` | `:288-289` |
| `time.day.elapsed_seconds` | `now.TimeOfDay.TotalSeconds` | `:291` |
| `time.day.remaining_seconds` | `Max(0, 86400 - seconds)` | `:292` |
| `time.day.progress` | `Clamp(seconds / 86400 * 100, 0, 100)` | `:293` |
| `time.week.*` | `weekStart = now.Date.AddDays(-(((int)now.DayOfWeek + 6) % 7))` → **Monday**; `weekEnd = +7d` | `:256-257,294-296` |
| `time.month.*` | `monthStart = new DateTime(now.Year, now.Month, 1)`, `monthEnd = AddMonths(1)` | `:258-259,297-299` |
| `time.year.*` | `yearStart = new DateTime(now.Year,1,1)`, `yearEnd = AddYears(1)` | `:260-261,300-302` |

Each period emits `elapsed_seconds`, `remaining_seconds` (`Max(0, total - elapsed)`) and
`progress` (`Clamp(elapsed/total*100, 0, 100)`), with the denominator floored at 1
(`Math.Max(1d, …)`, `:263-267`) so a zero-length span cannot divide by zero.

### 5.2 Target-time mode → derived variables

`LN/Customization/HudProfileRenderer.cs:96-135` (`BuildEffectiveVariables`), recomputed on every
render so the HUD never lags the clock:

```
target = ParseTime(profile.TimeTarget, new TimeSpan(10, 0, 0));       // :106
nextTarget = now.Date + target;  if (nextTarget <= now) nextTarget = nextTarget.AddDays(1);   // :107-109
remainingSeconds = Max(0, (nextTarget - now).TotalSeconds);           // :111
remainingPercent = Clamp(remainingSeconds / 86400 * 100, 0, 100);     // :112
targetProgress   = 100 - remainingPercent;                            // :113
```

| Variable | Value | Line |
| --- | --- | --- |
| `time.target.value` | `target.ToString(@"hh\:mm\:ss")` | `:115` |
| `time.target.remaining_seconds` | seconds until the next occurrence of the target time | `:116` |
| `time.target.remaining_percent` | `remainingSeconds/86400*100` clamped | `:117` |
| `time.target.progress` | `100 - remainingPercent` | `:118` |
| `time.target.remaining_text` | `剩余{N}%` / `Left {N}%`, `N = Math.Round(remainingPercent, MidpointRounding.AwayFromZero)` | `:119-121` |
| `time.display.progress` | target mode → `remainingPercent`; normal mode → `dayProgress` | `:123-133` |
| `time.display.status_text` | target mode → `剩余{N}%` / `Left {N}%`; normal mode → `"{N}%"` | `:126-133` |

`ParseTime` (`:230-243`) accepts `hh\:mm\:ss` first via `TimeSpan.TryParseExact`, then
`TimeSpan.TryParse`, requiring `>= TimeSpan.Zero` and `< TimeSpan.FromDays(1)`; otherwise it falls
back to `new TimeSpan(10, 0, 0)`. Default from the profile is `TimeTarget = "10:00:00"`
(`LN/Customization/Models.cs:47`), and `HudSettingsNormalizer.NormalizeTargetTime` re-formats it to
`HH:mm:ss` or resets it to `"10:00:00"` (`:158-163`).

### 5.3 The built-in 日进程 scheme

`LN/Customization/Models.cs:241-261`:

```
IsBuiltIn = true, BuiltInKey = "time.day-progress", Category = "时间", Name = "日进程",
AnimationMode = "Full", TaglineTemplate = "/// TIME", TitleTemplate = "日进程",
PrimaryTemplate = "{time.current}", SecondaryTemplate = "",
RightTemplate = "{time.display.status_text}", RightSuffix = "",
ProgressVariable = "time.display.progress", ProgressMin = 0, ProgressMax = 100,
LeftIcon = "clock", RightIcon = "clock",
TimeTargetEnabled = false, TimeTarget = "10:00:00"
```

`time.display.*` and `time.target.*` are **never produced by `VariableHub`** — only by the renderer;
a request set that names them still has to include a real clock key. `GetRequiredVariables` adds
`time.current` whenever the profile is a time profile or any required key starts with `time.`
(`LN/Customization/HudProfileRenderer.cs:58-60`).

### 5.4 Second-accurate cadence

`HudProfileRenderer.NeedsSecondAccurateClock` (`:74-79`) is true for time profiles and for any key
`system.time` / `time.*` / `deepseek.period.*`. `CustomHudRuntime.TickPersistentAsync` then
(`LN/Customization/CustomHudRuntime.cs:445-452,515-516`) re-renders only when
`DateTime.Now.Ticks / TimeSpan.TicksPerSecond` changes, instead of the 1 s refresh timer, with the
comment (`LN/Customization/CustomHudRuntime.cs:132`) that clock+system reads are kept on the fast
path precisely to avoid "1 秒定时器 + Task 调度造成跳秒" (skipped seconds from timer/task jitter).

### 5.5 World clocks (cross-platform defect worth noting)

- Linux `LN/Customization/AdvancedVariableProvider.cs:464-469` uses **Windows** time-zone IDs:
  `time.world.nyc` ← `"Eastern Standard Time"`, `.london` ← `"GMT Standard Time"`,
  `.tokyo` ← `"Tokyo Standard Time"`, `.beijing` ← `"China Standard Time"`.
- macOS `MC/Customization/AdvancedVariableProvider.cs:20-26` uses **IANA** IDs:
  `"America/New_York"`, `"Europe/London"`, `"Asia/Tokyo"`, `"Asia/Shanghai"`.
- Both funnel through `PutWorld` with `try { … } catch { }` (`LN:1195`, `MC:49-52`), so a
  failed zone lookup **silently omits the key**. On the WSL validation host .NET's ICU mapping made
  the Windows IDs resolve (`LN/artifacts/linux-audit/VARIABLE-AUDIT.md:290-293`), but an
  invariant-globalization or ICU-trimmed Linux build would drop all four keys without any status
  text. Android must use IANA IDs (`java.time.ZoneId`) — copy the macOS form, not the Linux one.

---

## 6. Sampling / power strategy

There is **no unified sampler**. Throttling is three-layered: request-set gating (what to collect),
per-provider caches (how often), and single-flight/priming rules (how many samples a value needs).

### 6.1 Intervals and caches (exact literals)

| Mechanism | Interval | Reference |
| --- | --- | --- |
| HUD runtime timer (wall-clock sync + hot-zone poll) | `TimeSpan.FromMilliseconds(100)` | `LN/Customization/CustomHudRuntime.cs:47`, `MC:47` |
| Persistent HUD refresh | `DateTime.UtcNow.AddSeconds(1)` (or wall-clock second for clock-accurate profiles) | `LN:514,516` |
| Refresh retry after an exception | `DateTime.UtcNow.AddSeconds(2)` | `LN:520` |
| Power-source poll gate | `now.AddMilliseconds(150)` | `LN:303` |
| Power-source confirm debounce | `< 400 ms` → ignore | `LN:327` |
| DeepSeek period poll gate | `now.AddMilliseconds(250)` | `LN:339` |
| Auto-cycle period | `Math.Clamp(_settings.CycleSeconds, 3, 3600)`, default `CycleSeconds = 10` | `LN:428`, `LN/Customization/Models.cs:86` |
| Linux two-sample priming (VariableHub) | `await Task.Delay(80, ct)` then re-collect only the missing rate keys | `LN/Customization/VariableHub.cs:183-195` |
| Linux capability refresh in catalog | `Thread.Sleep(80)` between two full collects | `LN/Customization/LinuxVariableCatalog.cs:43` |
| macOS priming loop | `Task.Delay(200, ct)` × up to 6 until `cpu.usage` and `network.download_bps` exist | `MC/Customization/VariableHub.cs:64-69` |
| macOS capability refresh | `Thread.Sleep(200)` × up to 6 | `MC/Customization/MacVariableCatalog.cs:31-35` |
| `nvidia-smi` result cache | `AddSeconds(2)` | `LN/Customization/LinuxNvidia.cs:16` |
| Linux developer-command cache | `AddSeconds(30)` | `LN/Customization/LinuxSystemVariables.cs:143` |
| macOS Metal device list cache | `AddSeconds(10)` | `MC/Customization/MacVariableProvider.cs:27` |
| macOS AC-state cache | `AddSeconds(1)` | `MC/Interop/MacSystemProbe.cs:18` |
| Probe per state key | `now.AddMilliseconds(800)`, single-flight | `LN:861`, `MC:222` |
| DeepSeek balance cache | `AddMinutes(1)` | `LN:1420` |
| Public IPv4 / IPv6 cache | `AddMinutes(5)` each, independent timestamps | `LN/…/AdvancedVariableProvider.cs:569,574` |
| Custom HTTP cache | `Clamp(source.RefreshSeconds, 5, 86400)`, default 60 | `LN:1541`, `LN/Customization/Models.cs:78` |
| Advanced `HttpClient` (public IP) | `Timeout = TimeSpan.FromSeconds(3)` | `LN/…/AdvancedVariableProvider.cs:30` |
| Shared `HttpClient` (DeepSeek + custom HTTP) | `Timeout = TimeSpan.FromSeconds(8)` | `LN/Customization/VariableHub.cs:23` |
| External command | `WaitForExit(2500)` then kill tree | `LN/Customization/LinuxCommand.cs:36` |
| Clipboard read guard | `WaitAsync(TimeSpan.FromSeconds(2))` | `LN/Customization/LinuxUiVariables.cs:42-43` |

Windows-only advanced collector cadences are present in the shared file but unreachable off-Windows
(`EnrichAsync` returns early for non-Windows, `LN/…/AdvancedVariableProvider.cs:76-84`):
perf OS 900 ms (`:589`), hardware 900 ms (`:675`), process 1 s (`:942`), network static 3 s (`:760`),
NVIDIA 5 s (`:738`), disk advanced 1 min (`:824`), system static 2 min (`:864`), memory static 10 min (`:634`),
developer 15 s (`:1004`), USB 30 s (`:1042`), security 5 min (`:1093`).

### 6.2 Request-set gating (the real "collect only what is needed")

- Every family is guarded by `NeedsPrefix(requested, prefix)` (`LN/Customization/VariableHub.cs:1573-1577`);
  `requested == null` means "collect everything".
- `HudProfileRenderer.GetRequiredVariables` (`LN/Customization/HudProfileRenderer.cs:35-72`) is the
  single source of the request set: template tokens + expression keys + `ProgressVariable` keys +
  `ColorRules[].Variable`, plus `time.current` for any time profile and
  `network.download_bps` + `network.upload_bps` for any network profile (`:58-69`).
- `LinuxVariableCatalog.Refresh` and the Windows-only advanced families additionally use
  `VariableCatalog.IsAdvancedKey` so that a plain CPU/RAM HUD does not pay for LibreHardwareMonitor
  (`LN/…/AdvancedVariableProvider.cs:91-98`, comment `:96-97`).
- The **macOS** provider goes further and collapses the request set into a bit mask so one native
  call covers cpu+system / memory / battery / disk / network (`MC/Customization/MacVariableProvider.cs:38-43`).

### 6.3 Two-sample rule (rates are never fabricated)

Restated because it is the most important sampling rule for the port:

- Linux CPU/context-switch/probe/network/disk rates require a prior sample and monotonic counters
  (`LN/…/LinuxVariableProvider.cs:59`, `:74`, `:128`, `LinuxNetwork.cs:56`, `LinuxDisks.cs:74`).
- macOS requires 2 native snapshots for both `cpu.usage` and `network.*_bps`
  (`MC/Customization/MacVariableProvider.cs:70-86`, `:87-103`).
- Both capability refreshes deliberately take two samples 80 ms / 200 ms apart
  (`LN/Customization/LinuxVariableCatalog.cs:42-44`, `MC/Customization/MacVariableCatalog.cs:31-35`).
- Asserted: `LN/Tests/Program.cs:37` `"CPU requires two samples."`;
  `LN/Tests/Program.cs:43` `"Counter reset must re-prime, not underflow."`;
  `MAC/Tests/Program.cs:59` `"Rates require two measured samples"`;
  `MAC/Tests/Program.cs:62` `"CPU tick wrap and normalized utilization"`;
  `MAC/Tests/Program.cs:66` `"Interface reset/hotplug does not create traffic spikes"`.

### 6.4 Hidden / visible state handling

`LN/Customization/CustomHudRuntime.cs`:

- `HudEnabled == false` → hide the persistent HUD and **return without sampling** (`:219-228`).
- `AlwaysVisible == true` → `TickPersistentAsync` (`:393-526`): re-render at most once per second
  (`:455`, `:514`) or only when the wall-clock second changes for clock-accurate/DeepSeek profiles
  (`:447-452`), and skip while `_hud.IsHudBusy` (`:450`, `:455`).
- `AlwaysVisible == false` → transient mode (`TickTransientTriggersAsync`, `:246-297`): sampling
  happens **only** when triggered — a power-source change (`:251-258`), a DeepSeek peak/off-peak
  boundary (`:260-272`), or the X11 top-centre hot zone being entered (`:274-296`). Each trigger
  calls `SnapshotAsync` once and then keeps refreshing during the show/hold/hide animation through
  the `refresh` delegate (`:107-111`, `:169-174`, `:374-378`).
- Re-entrancy guards: `Interlocked.Exchange(ref _busy, 1)` (`:365`, `:458`) and
  `_settingsTransitionBusy` (`:77`, `:144`). While `_settingsTransitionBusy != 0` the timer tick
  returns immediately (`:217`).
- Persistent layer selection `PersistentLayer = { Topmost, Desktop }` (`LN/Settings/AppSettings.cs:28,41-45`)
  and topmost/desktop is applied through the platform window (`LN/Views/HudWindow.axaml.cs`, and
  `ecp_hud_window(handle, topmost)` on macOS, `MC/native/ecpmac.m:206-218`).
- Language change invalidates localized caches: ping states cleared (`LN/Customization/VariableHub.cs:119-124`)
  and all language-sensitive advanced caches reset
  (`LN/…/AdvancedVariableProvider.cs:85-89,121-136`, comment `:123-124`).
- Global pointer: Linux uses `XQueryPointer` on `libX11.so.6` with a `libXext.so.6` shape mask for
  click-through (`LN/Interop/LinuxDesktop.cs:39-55,88-92`); macOS uses `CGEventCreate`/`CGEventGetLocation`
  (`MC/native/ecpmac.m:229-232`) and `ignoresMouseEvents` (`:212`). Both are reached through
  `WindowsSystemProbe.TryGetCursorPosition` (`LN/Interop/WindowsSystemProbe.cs:35-43`) /
  `MacSystemProbe.TryGetCursorPosition` (`MC/Interop/MacSystemProbe.cs:7-13`).

---

## 7. Import / export and configuration format

### 7.1 Serialization

- Serializer: `System.Text.Json` with
  `new JsonSerializerOptions { WriteIndented = true, PropertyNameCaseInsensitive = true }`
  (`LN/Settings/SettingsManager.cs:11-15`, `MC/Settings/SettingsManager.cs:11-15`).
- **No `JsonNamingPolicy`, no `[JsonPropertyName]`, no source-generated context** anywhere in any of
  the three trees (verified by search). Property names are therefore the exact PascalCase C#
  property names below.
- Read order: `<DataDirectory>/settings.json`, else the first existing of
  `%LOCALAPPDATA%\EndfieldCharge-CustomHUD\settings.json` and
  `%LOCALAPPDATA%\EndfieldCharge\settings.json` (`LN/Settings/SettingsManager.cs:22-32`).
  The legacy paths are Windows-shaped even in the Linux and macOS ports (dead code there, but it
  documents the migration lineage).
- On load, `HudEnabled` is forced to `true` for the session (`:44-48`); the setting is re-persisted
  to the new location when a legacy file was the source (`:50-54`).
- Corrupt file → copy to `Backups/settings.corrupt-yyyyMMdd-HHmmss.json`, then write defaults (`:58-67,154-169`).
- Save → copy current file to `Backups/settings.previous.json`, write `settings.json.tmp`, then
  `File.Move(temp, SettingsPath, overwrite: true)` (`:82-99`).
- `BackupCurrent(reason)` → `Backups/settings.<sanitized-reason>-yyyyMMdd-HHmmss.json` (`:126-137`);
  `TrimBackups` keeps the 20 newest `settings.*.json` (`:171-185`).
- Log rotation keeps the 14 newest `EndfieldChargePlus-*.log` plus a mirrored `latest.log`
  (`LN/Diagnostics/AppLog.cs:68-92`).

### 7.2 File locations

| Platform | Settings / backups / logs | Secrets | Autostart |
| --- | --- | --- | --- |
| Linux | `$XDG_DATA_HOME/EndfieldChargePlus`, default `~/.local/share/EndfieldChargePlus` (`LN/Interop/AppPaths.cs:5-18`) | `secrets/master.key` (0600, dir 0700) (`LN/Customization/SecretStore.cs:66-90`) | `~/.config/autostart/endfield-charge-plus-for-linux.desktop` (`LN/README.linux.md:53`) |
| macOS | `~/Library/Application Support/EndfieldChargePlusForMacOS` (`MC/Interop/AppPaths.cs:4-7`) | login Keychain, service `com.glacierglimmer.endfieldchargeplus.macos` | `~/Library/LaunchAgents/com.glacierglimmer.endfieldchargeplus.macos.plist` (`MC/README.md:42`) |
| Windows (reference) | `%LOCALAPPDATA%\EndfieldChargePlus` (`LN/PRIVACY.md:9`) | DPAPI current-user blob in settings | `HKCU\...\CurrentVersion\Run` |

### 7.3 Exact field names

`AppSettings` (`LN/Settings/AppSettings.cs:15-38`, macОS `:15-38`, byte-for-byte the same record):

```
HudEnabled, StartWithWindows, UiLanguage, GlobalScale, DisplayDurationSeconds,
BounceStrength, RippleIntensity, RippleSpread, HudOpacity, AlwaysVisible,
PersistentLayer, PositionMode, HudPosition, HudOffsetX, HudOffsetY, HudCustomX,
HudCustomY, MonitorIndex, CustomHud
```

Defaults (`:7-38`): `DefaultGlobalScale = 0.8`, `DefaultDisplayDurationSeconds = 6.0`,
`DefaultBounceStrength = 0.275`, `DefaultRippleIntensity = 1.0`, `DefaultRippleSpread = 1.0`,
`DefaultHudOpacity = 1.0`, `HudEnabled = true`, `StartWithWindows = false`, `UiLanguage = "Auto"`,
`AlwaysVisible = false`, `PersistentLayer = Desktop`, `PositionMode = Preset`,
`HudPosition = TopCenter`, `MonitorIndex = -1`, `HudCustomX/Y = 0`, `HudOffsetX/Y = 0`.

Enum string values: `PersistentHudLayer { Topmost, Desktop }` (`:41-45`),
`HudPositionMode { Preset, CustomCoordinates }` (`:47-51`),
`HudPosition { TopLeft, TopCenter, TopRight, CenterLeft, Center, CenterRight, BottomLeft, BottomCenter, BottomRight }` (`:53-64`).
Note `StartWithWindows` is **reused as the launch-at-login flag on macOS** (`MC/Settings/AppSettings.cs:16`
with the comment only differing in "macOS UI culture"), and it survives normalisation because
`Normalize` never rewrites it (`:146-152`).

`CustomHudSettings` (`LN/Customization/Models.cs:83-99`):

```
AutoCycle (false), CycleSeconds (10, clamped 3..3600), CycleProfileIds (List<string>? = null),
CycleAnimationMode ("Simple"), ActiveProfileId (""),
DeepSeekApiKeyProtected (""), DeepSeekPeakWindows ("09:00-12:00;14:00-18:00"),
Profiles (List<HudProfile>), HttpSources (List<CustomHttpSource>)
```

`HudProfile` (`LN/Customization/Models.cs:15-65`):

```
Id (Guid "N"), IsBuiltIn, BuiltInKey, Category ("自定义"), Name ("自定义 HUD"),
AnimationMode ("Full"), TaglineTemplate ("/// SYSTEM MONITOR"), TitleTemplate ("系统状态"),
PrimaryTemplate ("{cpu.frequency_ghz|0.00}"), SecondaryTemplate (" GHz"),
RightTemplate ("{cpu.usage|0}"), RightSuffix ("%"),
ProgressVariable ("cpu.usage"), ProgressMin (0), ProgressMax (100),
LeftIcon ("cpu"), RightIcon ("cpu"), AccentColor ("#C6CA4C"), ColorRules (List<HudColorRule>),
TimeTargetEnabled (false), TimeTarget ("10:00:00"), GpuAdapterId (""),
NetworkDisplayUnit ("AutoBytes"), NetworkPercentMode ("Total"),
NetworkReferenceValue (100d), NetworkReferenceUnit ("MB/s"),
PingTarget ("1.1.1.1"), ProbeProtocol ("ICMP"), ProbePort (443)
```

`HudColorRule` (`:7-13`): `Variable (""), Operator (">="), Value, Color ("#C6CA4C")`.
`HttpFieldMapping` / `CustomHttpSource`: see §4.1.

### 7.4 Export / import behaviour

- `ExportToFile(destinationPath, settings)` serializes the **normalised** settings with the same
  options (`LN/Settings/SettingsManager.cs:102-114`) — no extra wrapper object, no version field, no
  checksum, and **no key material added**: the Linux export contains the `linux-aesgcm-v1:` blob but
  not `secrets/master.key`; the macOS export contains the `macos-keychain-v1:<hash>` account
  reference, not the secret (`MC/PRIVACY.md:7`, `LN/PRIVACY.md:21`).
- `ImportFromFile(sourcePath)` → `Normalize(DeserializeFile(...))` (`:116-124`), so a foreign file
  is accepted, then platform filtering runs via `HudSettingsNormalizer.Normalize` →
  `LinuxVariableCatalog.RemoveUnsupportedReferences` / `AdaptProfiles` (Linux) or the macOS
  equivalents (`LN/Customization/HudSettingsNormalizer.cs:13,49,61,122,127`).
- UI: save picker suggests `EndfieldChargePlus-settings-yyyyMMdd-HHmmss.json` with file type
  `"JSON 配置"` / `"JSON Config"` and pattern `*.json`; open picker uses the same filter
  (`LN/Settings/SettingsWindow.axaml.cs:370-408`).
- Normalisation on load, in order (`LN/Customization/HudSettingsNormalizer.cs`):
  built-ins are canonicalised and matched by `BuiltInKey` first, then by legacy `Category` + `Name`
  (`:20-30`, `:154-156`), preserving the previous `Id` where possible (`:31-52`);
  user profiles are folded from legacy two-level `Category` + `Name` into
  `"<Category> - <Name>"` once (`:130-152`); `CycleProfileIds` is ID-validated and a `null` queue
  (legacy) is migrated once while an explicit empty list stays empty (`:68-84`); `CycleSeconds`
  clamped (`:94`); `HudOpacity` clamped 0.10-1.0 and `UiLanguage` normalised
  (`LN/Settings/SettingsManager.cs:146-152`).

### 7.5 Cross-platform compatibility intent (evidence)

1. `AppSettings`, `CustomHudSettings`, `HudProfile`, `HudColorRule`, `CustomHttpSource` and
   `HttpFieldMapping` are **byte-identical schemas** in the Linux, macOS and Windows trees
   (same property names, same defaults), and all three set `PropertyNameCaseInsensitive = true`.
2. Built-in profile identity is `BuiltInKey` (`system.battery`, `system.cpu`, `system.memory`,
   `system.gpu`, `system.network`, `system.disk`, `time.day-progress`, `deepseek.balance-period`,
   `network.ping`), so a profile written on one platform is re-bound to the platform's canonical
   template on load instead of being discarded.
3. Cycles are identity-based by `Id`, so renaming a user scheme keeps its queue position
   (`LN/Customization/HudSettingsNormalizer.cs:68-71`).
4. Unsupported references are **stripped, not fatal**, and the pre-import content is preserved in
   `settings.previous.json` and the import backups
   (`LN/Customization/LinuxVariableCatalog.cs:167-168`, `:169-183`).
5. `README.linux.md:9-15` and `MC/README.md:11-17` list Windows, macOS, Linux and **Android** as
   sibling repositories of the same product line; `README.linux.md:28` keeps the internal assembly
   and executable name `EndfieldChargePlus` explicitly "以保持资源和配置兼容"
   (to preserve resource and configuration compatibility).
6. `NOTICE.md:5-13` (byte-identical in both ports) declares the derivative relationship to
   `QinAnze/zmd-charge`.
7. **Limits to compatibility:** the DeepSeek key blob is platform-tagged and refuses foreign input
   (`MC/Customization/SecretStore.cs:19`, `LN/Customization/SecretStore.cs:41-43`, test
   `MAC/Tests/Program.cs:83`), so the key must be re-entered; and `StartWithWindows` means
   "launch at login" on macOS while meaning a registry Run key on Windows.
8. No `settings.json` sample file exists anywhere in the workspace, so the JSON shape below is
   derived from the record declarations rather than from an on-disk artifact (marked UNCERTAIN as to
   exact key order/omission behaviour, though `System.Text.Json` writes all properties including
   defaults).

Canonical shape implied by the records (illustrative, field names exact):

```json
{
  "HudEnabled": true,
  "StartWithWindows": false,
  "UiLanguage": "Auto",
  "GlobalScale": 0.8,
  "DisplayDurationSeconds": 6.0,
  "BounceStrength": 0.275,
  "RippleIntensity": 1.0,
  "RippleSpread": 1.0,
  "HudOpacity": 1.0,
  "AlwaysVisible": false,
  "PersistentLayer": "Desktop",
  "PositionMode": "Preset",
  "HudPosition": "TopCenter",
  "HudOffsetX": 0, "HudOffsetY": 0, "HudCustomX": 0, "HudCustomY": 0,
  "MonitorIndex": -1,
  "CustomHud": {
    "AutoCycle": false,
    "CycleSeconds": 10,
    "CycleProfileIds": ["..."],
    "CycleAnimationMode": "Simple",
    "ActiveProfileId": "...",
    "DeepSeekApiKeyProtected": "linux-aesgcm-v1:...",
    "DeepSeekPeakWindows": "09:00-12:00;14:00-18:00",
    "Profiles": [
      {
        "Id": "…", "IsBuiltIn": true, "BuiltInKey": "network.ping",
        "Category": "网络", "Name": "网络包探测器", "AnimationMode": "Full",
        "TaglineTemplate": "/// NETWORK PROBE", "TitleTemplate": "网络包探测器",
        "PrimaryTemplate": "{probe.latency_ms|0}ms", "SecondaryTemplate": "",
        "RightTemplate": "丢包{probe.loss_percent|0}", "RightSuffix": "%",
        "ProgressVariable": "probe.loss_percent", "ProgressMin": 0, "ProgressMax": 100,
        "LeftIcon": "signal", "RightIcon": "gauge", "AccentColor": "#C6CA4C",
        "ColorRules": [
          { "Variable": "probe.loss_percent", "Operator": ">=", "Value": 30, "Color": "#FF4D4F" },
          { "Variable": "probe.loss_percent", "Operator": ">=", "Value": 10, "Color": "#FFB84D" }
        ],
        "TimeTargetEnabled": false, "TimeTarget": "10:00:00", "GpuAdapterId": "",
        "NetworkDisplayUnit": "AutoBytes", "NetworkPercentMode": "Total",
        "NetworkReferenceValue": 100.0, "NetworkReferenceUnit": "MB/s",
        "PingTarget": "1.1.1.1", "ProbeProtocol": "ICMP", "ProbePort": 443
      }
    ],
    "HttpSources": [
      {
        "Name": "audit", "Enabled": true, "Url": "https://example.invalid/data",
        "RefreshSeconds": 60,
        "Headers": { "Authorization": "Bearer ${env:ECP_TOKEN}" },
        "Fields": [ { "Variable": "value", "JsonPath": "data.items[0].value" } ]
      }
    ]
  }
}
```

---

## 8. Upstream lineage (QinAnze/zmd-charge)

### 8.1 Clone result

**The clone succeeded.** `git clone --depth 1 https://github.com/QinAnze/zmd-charge D:\ECP_Workspace\upstream-zmd-charge`
produced a working tree; `git -C … rev-parse --is-shallow-repository` → `true`;
`git log --oneline -1` → `1e3e061 Merge pull request #4 from programming666/feat/hotkey-and-battery-mode`;
`git remote -v` → `origin  https://github.com/QinAnze/zmd-charge (fetch/push)`.
(The PowerShell call reported `[exit code: 1]` only because git writes clone progress to stderr,
which PowerShell surfaces as a native-command error; the tree is complete — 33 files plus `.git`.)
No fallback to the GitHub web/raw API was needed.

### 8.2 Project layout (complete, excluding `.git`)

```
UP/.github/workflows/build.yml            3 594 B
UP/.gitignore                             1 180 B
UP/Animations/HudAnimations.cs           22 351 B
UP/App.axaml                                586 B
UP/App.axaml.cs                          16 922 B
UP/app.manifest                             934 B
UP/Assets/tray_bolt.ico                  14 276 B
UP/Assets/tray_bolt.png                  31 319 B
UP/EndfieldCharge.csproj                  2 117 B
UP/installer/EndfieldCharge.iss           3 252 B
UP/installer/Languages/ChineseSimplified.isl  21 516 B
UP/Localization.cs                       10 269 B
UP/Program.cs                               873 B
UP/README.md                              6 780 B
UP/Services/AutoStart.cs                  1 886 B
UP/Services/BatteryService.cs             4 651 B
UP/Services/HotkeyService.cs             12 415 B
UP/Services/Logger.cs                     1 877 B
UP/Services/PowerNative.cs               11 333 B
UP/Services/PowerWatcher.cs              13 069 B
UP/Services/UpdateChecker.cs              2 297 B
UP/Settings/AppSettings.cs                1 887 B
UP/Settings/SettingsManager.cs            1 252 B
UP/Settings/SettingsWindow.axaml         19 876 B
UP/Settings/SettingsWindow.axaml.cs      25 426 B
UP/Styles/Geometries.axaml                1 197 B
UP/Styles/HudTheme.axaml                  2 184 B
UP/Views/HudWindow.axaml                 12 971 B
UP/Views/HudWindow.axaml.cs              18 954 B
UP/Views/TrayMenuWindow.axaml             2 284 B
UP/Views/TrayMenuWindow.axaml.cs          3 746 B
```

There is **no `LICENSE`, no `NOTICE`, no `COPYING`, and no `.md` other than `README.md`.**

### 8.3 README claims (exact quotes)

- `UP/README.md:1` — `# EndfieldCharge · 终末地风格电量 HUD`
- `UP/README.md:3` — `插上 / 拔掉充电器时，从屏幕顶部弹出一块"灵动岛"式 HUD，显示当前电量（mWh 与百分比）。`
- `UP/README.md:4` — `视觉与动画风格复刻《终末地》工业 / 超充模式 HUD。`
- `UP/README.md:6-7` — plug-in = full three-state animation; unplug = mirrored "电池模式" with
  ripples contracting inward.
- `UP/README.md:11` — download link: `从 [Releases](https://github.com/Lenkmat/endfield-charge/releases) 下载：`
  (**note: owner `Lenkmat`, not the clone's owner `QinAnze`**).
- `UP/README.md:15-16` — artifacts `EndfieldCharge-x.y.z-setup.exe` (Inno Setup) and
  `EndfieldCharge-x.y.z-portable.zip`.
- `UP/README.md:18-35` — feature table (verbatim highlights):
  `电量显示 … 读取 CallNtPowerInformation，WMI 兜底` (`:22`);
  `电源监听 RegisterPowerSettingNotification 订阅 GUID_ACDC_POWER_SOURCE，2s 轮询兜底，400ms 双向去抖（过滤 Windows 满电瞬时抖动）` (`:23`);
  `低电量变色 电量 < 20% 时黄绿电量圈变红（#FF4D4F）` (`:24`);
  `提醒通知 低电量提醒（阈值可调 5–40%）与充满提醒（≥99%）…` (`:25`);
  `设置窗口 全局缩放（0.4–1.2）、显示时长（2–10s）、HUD 位置（顶部居中/靠右/靠左）、显示器选择、语言、开机自启…` (`:26`);
  `托盘菜单 左键单击弹出自定义深色菜单（预览 / 设置 / 检查更新 / 退出）` (`:27`);
  `全局快捷键 默认 Ctrl + Alt + H …` (`:28`);
  `动画微调 设置窗口「动画」页实时预览并微调时长 / 回弹 / 波纹参数…` (`:29`);
  `节能模式提示 … 24H2+（build 26100+）订阅 GUID_ENERGY_SAVER_STATUS 通知、轮询注册表 EnergySaverState；旧系统用 GUID_POWER_SAVING_STATUS + SystemStatusFlag` (`:30`);
  `检查更新 读取 GitHub Releases API，比较程序集版本，一键跳转下载页` (`:31`);
  `多语言 中文 / 英文，默认跟随系统…` (`:32`);
  `开机自启 … 写 HKCU\...\CurrentVersion\Run（当前用户级，无需管理员）` (`:33`);
  `统一图标 托盘 / 各窗口 / exe / 安装器 / 卸载器统一使用 Assets\tray_bolt 图标` (`:34`);
  `日志 %TEMP%\EndfieldCharge\log-YYYYMMDD.txt…` (`:35`).
- `UP/README.md:37-41` — 运行要求: `Windows 10 1809+ / Windows 11`, `.NET 8 运行时`,
  `x64`.
- `UP/README.md:43-69` — build/CI: `dotnet build -c Debug`, `dotnet publish -c Release -o publish`,
  `iscc installer\EndfieldCharge.iss`; `PublishSingleFile` caveat about SkiaSharp native DLLs (`:56-58`);
  push to `main` builds artifacts, `v*` tags create a Release (`:62-69`).
- `UP/README.md:71-83` — debug switches `--demo`, `--preview`, `--preview-unplug`, `--debug-ring`,
  `--power-log`.
- `UP/README.md:85-113` — 项目结构 tree (quoted in full at `UP/README.md:87-113`).
- `UP/README.md:115-119` — animation implementation notes (Avalonia 11 `KeySpline` per segment;
  `Border.HeightProperty` animatable; outer `ScaleHost` `RenderTransform` for the final shrink).
- `UP/README.md:121-123` — the licence section:
  ```
  ## 许可证

  MIT
  ```

**LICENSE file: absent.** So the upstream licence is asserted **only** by those three README lines.
No standalone licence text and no `Copyright` line exist in the upstream tree.

**NOTICE / attribution requirement: absent.** There is no attribution file, no third-party notice,
and no bundling instruction of any kind.

**The only copyright-holder line in the entire upstream tree** is in the project file
(`UP/EndfieldCharge.csproj:15-17`, quoted exactly):

```xml
<Authors>Lenkmat</Authors>
<Company>Lenkmat</Company>
<Copyright>Copyright © 2026 Lenkmat</Copyright>
```

Other owner identifiers: `UP/README.md:11` → `Lenkmat/endfield-charge`;
`UP/Services/UpdateChecker.cs:27-29` hard-codes
`.Replace("{owner}", "Lenkmat").Replace("{repo}", "endfield-charge")`;
`UP/installer/EndfieldCharge.iss:7-8` → `#define MyAppPublisher "Lenkmat"` /
`#define MyAppURL "https://github.com/Lenkmat/endfield-charge"`;
`UP/installer/EndfieldCharge.iss:12` → `AppId={{6B6BD34B-6E4D-490C-A8AE-62963965257A}`.

**UNCERTAIN:** whether `Lenkmat/endfield-charge` is a rename/fork of `QinAnze/zmd-charge` or a
separate project. The clone URL is `QinAnze/zmd-charge`; every in-tree owner reference is `Lenkmat`.
Both were treated as the same upstream provenance for this audit.

### 8.4 ECP's own licence and notice (for contrast)

- `LN/LICENSE` and `MC/LICENSE` are **byte-identical**:
  `MIT License` / `Copyright (c) 2026 GlacierGlimmer_冰川雪貓` (lines 1-3).
- `LN/NOTICE.md` and `MC/NOTICE.md` are **byte-identical** (2 618 B each). Lines 5-13 verbatim:

  ```
  Endfield Charge Plus is a modified derivative of the HUD project
  [QinAnze/zmd-charge](https://github.com/QinAnze/zmd-charge).
  本项目基于 QinAnze 的 zmd-charge 进行二次开发，保留原项目来源和署名。

  The original project's README identifies its license as MIT. The upstream
  repository did not expose a standalone LICENSE file at the time this notice
  was prepared; no unverified original copyright wording is asserted here.
  The MIT LICENSE in this repository covers GlacierGlimmer's contributions;
  original code/assets remain subject to their applicable upstream terms.
  ```

  This matches the audit finding exactly: README says MIT, no LICENSE file exists upstream, and ECP
  deliberately declines to assert unverified upstream copyright wording — which is precisely why the
  report above quotes the csproj `<Copyright>` line and not a LICENSE header.
- Third-party notices in `NOTICE.md:15-61`: Avalonia 11.2.1 (MIT), Inter typeface (OFL-1.1),
  `System.Management` 10.0.2 (MIT), `System.Security.Cryptography.ProtectedData` 8.0.0 (MIT),
  `LibreHardwareMonitorLib` 0.9.6 (**MPL-2.0** — the only copyleft-flavoured dependency), plus the
  instruction at `:61` to keep the notice with source releases and retain upstream licence files.

### 8.5 What the upstream contained, and which ideas ECP inherited

Upstream is a **single-purpose Windows battery HUD**: plug/unplug trigger → three-state Avalonia
animation showing mWh and percent. It has no variable catalog, no template engine, no hardware
sensor library, no network probe, no API integrations, no custom data sources, and no profile system.

| Upstream element | Exact reference | ECP descendant |
| --- | --- | --- |
| `CallNtPowerInformation(SystemBatteryState)` primary + WMI `Win32_Battery` fallback; struct fields `AcOnLine, BatteryPresent, Charging, Discharging, …, MaxCapacity, RemainingCapacity, Rate, EstimatedTime` (mWh/mW/seconds) | `UP/Services/PowerNative.cs:20,23-49,51-84`; `UP/Services/BatteryService.cs:47-125`; `UP/README.md:22` | `battery.*` family — Windows `CallNtPowerInformation`/WMI; Linux `/sys/class/power_supply` (`LN/…/LinuxVariableProvider.cs:187-268`); macOS `IOPowerSources` (`MC/native/ecpmac.m:101-120`). The mWh/Wh/percent/rate/health vocabulary is inherited verbatim. |
| `Rate` sign convention "正=充电，负=放电" and `s.Rate == 0 ? null : s.Rate / 1000.0` | `UP/Services/PowerNative.cs:41-42`; `UP/Services/BatteryService.cs:71` | Linux takes `Math.Abs` and then groups by `Charging`/`Discharging` status (`LN/…/LinuxVariableProvider.cs:229-238`) — the safer Android precedent. |
| `EstimatedTime is 0 or 0x80000000 ? null` | `UP/Services/BatteryService.cs:72-74` | Linux `未放电`/`未充电`/`当前无法估算` markers (`LN/…/LinuxVariableProvider.cs:250-255`) and macOS `> 0` guards (`MC/native/ecpmac.m:115-116`). |
| Power events via `RegisterPowerSettingNotification` + `GUID_ACDC_POWER_SOURCE` (`5d3e9a59-e9d5-4b00-a6bd-ff34ff516548`), 2 s polling fallback, **400 ms two-way debounce** | `UP/Services/PowerWatcher.cs:33,42,101-137,206-256`; `UP/README.md:23` | ECP `CustomHudRuntime.PollPowerSource` — 150 ms poll gate, `if ((now - _candidateAcSince).TotalMilliseconds < 400) return;`, with the comment `// 与上游项目一致采用双向稳定确认，过滤 Windows 电源状态的瞬时抖动。` (`LN/Customization/CustomHudRuntime.cs:299-333`). |
| Energy-saver GUID split at build 26100 (`GUID_POWER_SAVING_STATUS` vs `GUID_ENERGY_SAVER_STATUS`, registry `EnergySaverState`) | `UP/Services/PowerNative.cs:104-144`; `UP/Services/PowerWatcher.cs:25-30,312-322`; `UP/README.md:30` | `battery.saver_on` remains a Windows-only advanced key; equivalent concepts on Linux are `system.power_plan` (ACPI platform profile, `LN/…/LinuxSystemVariables.cs:29-34`). |
| Manual message-only window (`RegisterClassExW`/`CreateWindowExW` with `HWND_MESSAGE = -3`), STA thread, `PostThreadMessageW(WM_QUIT)` | `UP/Services/PowerNative.cs:188-283`; `UP/Services/PowerWatcher.cs:79-99,162-188` | Windows-only; the Linux/macOS equivalents are the Avalonia dispatcher timer plus `XQueryPointer` / `CGEventGetLocation`. |
| Global hotkey `RegisterHotKey`, default `Ctrl+Alt+H` (`HotkeyModifiers = 3`, `HotkeyKey = 0x48`) | `UP/Services/HotkeyService.cs`; `UP/Services/PowerNative.cs:285-305`; `UP/Settings/AppSettings.cs:31-37`; `UP/README.md:28` | ECP keeps the same Win32 modifier/key semantics on Windows; Linux/macOS rely on the settings window and hot-zone/preview. |
| Autostart via `HKCU\...\CurrentVersion\Run`, value name `EndfieldCharge`, quoted exe path | `UP/Services/AutoStart.cs:10-11,33-38`; `UP/README.md:33` | ECP per platform: XDG `.desktop` on Linux, `LaunchAgent` plist on macOS, registry on Windows (`LN/Settings/StartupManager.cs`; `MC/Settings/StartupManager.cs`, tests `MC/Tests/Program.cs:86-112`). |
| Single-instance mutex `Local\EndfieldCharge_SingleInstance_7C1D`, silent exit when already running | `UP/Program.cs:9,15-18` | ECP single instance + "relaunch reopens Settings" (`LN/README.linux.md:37`; `LN/docs/linux-validation.md:16` "静默自启动、单实例唤回设置通过"). |
| File logger `%TEMP%\EndfieldCharge\log-YYYYMMDD.txt`, `Enabled` gate, lock + swallow | `UP/Services/Logger.cs:12-13,21-25,32-52`; `UP/README.md:35` | `Diagnostics.AppLog` → `<DataDirectory>/Logs/EndfieldChargePlus-YYYYMMDD.log` + `latest.log`, 14-file rotation (`LN/Diagnostics/AppLog.cs:16-35,68-92`). |
| GitHub Releases update check (assembly version vs `tag_name`) | `UP/Services/UpdateChecker.cs:10,26-52`; `UP/README.md:31` | ECP per-platform, now asset-aware: macOS `MacUpdateRelease.SelectTag` filters `EndfieldChargePlusForMacOS-…-osx-arm64.dmg`/`-osx-x64.dmg`, rejects `draft` and `prerelease`, and only accepts the running architecture (`MC/Settings/MacUpdateRelease.cs:6-18`; tests `MAC/Tests/Program.cs:69-78`). |
| HUD animation parameter vocabulary: `GlobalScale`, `DisplayDurationSeconds`, `BounceStrength`, `RippleIntensity`, `RippleSpread` | `UP/Settings/AppSettings.cs:5-17`; `UP/Animations/HudAnimations.cs`; `UP/README.md:29,115-119` | ECP keeps the identical property names and defaults (`LN/Settings/AppSettings.cs:7-25`, `MC:7-25`) and the per-segment `KeySpline` approach in `Animations/HudAnimations.cs`. |
| Low-battery colour `#FF4D4F` at `< 20%` | `UP/README.md:24` | ECP default `HudColorRule.Color = "#C6CA4C"` with the built-in battery rule `battery.percent <= 20 → #FF4D4F` (`LN/Customization/Models.cs:140-143`) and the probe rules `#FF4D4F`/`#FFB84D` (`:302-303`). |
| Inno Setup installer with Chinese-language ISL bundled, per-user install, optional desktop icon and startup task, SkiaSharp native DLL caveat | `UP/installer/EndfieldCharge.iss` (whole file, esp. `:5-9,20,29-38,44-45,51-56,63-67`); `.github/workflows/build.yml:51-58,60-74,86-97,99-107` | ECP ships DEB/RPM/tar.gz/AppImage on Linux (`LN/scripts/package-linux.sh`, `LN/apt-repo/`) and a signed DMG on macOS, but keeps the same version-stamping and artifact-naming discipline (`UP/.github/workflows/build.yml:30-39` ↔ `LN/.github/workflows/linux.yml`). |
| "Origin of the animation style" note (terminal-style HUD of 《终末地》) | `UP/README.md:4` | ECP keeps it (`LN/Customization/Models.cs` taglines `/// CPU`, `/// NETWORK PROBE`, `/// TIME`, `/// DEEPSEEK API`). |

**Entirely ECP-original layers** (no upstream counterpart exists in the clone — verified by the
file listing in §8.2): the variable catalog (`Customization/VariableCatalog.cs`, 429 keys on
Windows, 339 detected on the audited Linux host), `VariableHub` (all collectors and API
integrations), the `{variable|format}` template engine and `{=expression}` evaluator,
`HudProfileRenderer` + profile/colour-rule model, LibreHardwareMonitor sensor integration,
the ICMP/TCP/UDP packet probe, DeepSeek balance and peak/off-peak period, custom HTTP/JSON sources,
per-platform capability catalogues, and the platform secret stores.

---

## 9. Porting implications for Android (no fake data)

### 9.1 What the desktop implementations rely on, per metric family

| Family | Linux relies on | macOS relies on | Android reality for a non-root app | Recommendation |
| --- | --- | --- | --- | --- |
| **CPU total** | `/proc/stat` `cpu` line, two-sample delta; idle = idle+iowait; guest excluded (`LN/…/LinuxVariableProvider.cs:53-70`) | `host_processor_info(PROCESSOR_CPU_LOAD_INFO)` aggregated, `unchecked` uint deltas (`MC/native/ecpmac.m:46-56`; `MC/…/MacVariableProvider.cs:70-86`) | `/proc/stat` is world-readable on Android; parse the same 8 fields with the same idle=idle+iowait convention. No public framework API exists for system CPU load (`/proc/self/stat` covers only the app). | Read `/proc/stat`; **two samples required**; if unreadable → omit `cpu.usage`, never emit 0. |
| **CPU per-core** | Implicit: `cpu.frequency_*` is an average over cores; per-core usage is *not* published by ECP at all | not published | `/proc/stat` also has `cpu0..cpuN` lines (readable), but ECP has **no** `cpu.core.*.usage` variable to match — only `cpu.core.temperature_avg` and `cpu.core.voltage` (Windows-only, absent on Linux) | Do not invent per-core usage keys; if added, follow the same two-sample rule and document them as a new extension. |
| **CPU temperature** | `sys/class/hwmon/*/temp*_input` filtered to hwmon `name` ∈ {`coretemp`,`k10temp`,`cpu_thermal`} ÷1000 (`LN/…/LinuxVariableProvider.cs:103-109`) | **not available** — deliberately absent (`MC/README.md:44`) | `/sys/class/thermal/thermal_zone*/temp` and hwmon exist but are widely SELinux-restricted; no public SDK API; `BatteryManager` covers battery only | Probe thermal zones once at first run and include the key in the capability catalog **only** if a real read succeeds; otherwise report unsupported (as Apple/Linux do). |
| **CPU frequency / max frequency** | `/proc/cpuinfo` `cpu MHz` (current, often coarse), `cpu0/cpufreq/cpuinfo_max_freq` (max) (`:81-93`) | **not available** (comment `MC/native/ecpmac.m:58`: nominal `hw.cpufrequency` deliberately not used) | `scaling_cur_freq` / `cpuinfo_max_freq` under `/sys/devices/system/cpu/cpu*/cpufreq/` — readable on many devices, frequently blocked since Android 10 | Attempt `cpuinfo_max_freq` (max) first; current frequency only if readable, else omit `cpu.frequency_*`; never show the nominal max as "current" (this is exactly Apple's stated reason). |
| **GPU load / temp / frequency / VRAM** | DRM `gpu_busy_percent`, `mem_info_vram_total/used`, hwmon `temp1_input`, `power1_average`; or `nvidia-smi` (2 s cache) (`LN/…/LinuxVariableProvider.cs:295-325`; `LinuxNvidia.cs`) | **only** Metal name/count/`hasUnifiedMemory`/`recommendedMaxWorkingSetSize`/`isLowPower`/`isRemovable`; explicitly no load, temp or VRAM (`MC/native/ecpmac.m:194-204`; `MC/Customization/MacVariableCatalog.cs:83`) | No public GPU load/temp/frequency API in the SDK. Vendor nodes (`/sys/class/kgsl/kgsl-3d0/gpubusy` on Qualcomm, Mali equivalents) are vendor- and SELinux-specific. `recommendedMaxWorkingSetSize` is *not* VRAM and must not be relabelled as such | Either omit the GPU family entirely, or expose only verifiable identity (`GL_RENDERER`/`GL_VENDOR` via EGL as a **name**, plus adapter count) and keep `gpu.usage`/`gpu.temperature`/`gpu.memory_*` unsupported. Do **not** map Metal-style budget numbers onto "VRAM". |
| **Memory** | `/proc/meminfo`, `MemAvailable` as the used/free basis, ×1024 to Byte (`LN/…/LinuxVariableProvider.cs:138-170`) | `host_statistics64(HOST_VM_INFO64)` + `hw.memsize`; `used = min(total, (active+wire+compressor)*page)`; `vm.swapusage` for swap (`MC/native/ecpmac.m:73-99`) | `ActivityManager.MemoryInfo` (`totalMem`, `availMem`, `threshold`, `lowMemory`) is the supported path; `/proc/meminfo` is usually readable but field availability varies, and Android's own accounting (cached/reclaimable) differs from `MemAvailable` | Use `MemoryInfo.totalMem`/`availMem` (or `MemTotal`/`MemAvailable` when accessible) and publish the **same口径** the platform provides. Do not translate macOS's "active+wired+compressed" formula onto Android. ZRAM/swap only if `/proc/swaps` or `SwapTotal` is readable. |
| **Battery current** | `power_now` (µW) else `current_now × voltage_now / 1e12`, `Math.Abs`, then split by `Charging`/`Discharging` (`LN/…/LinuxVariableProvider.cs:229-238`); percent/health/time markers as in §1.2 | `IOPowerSources` **percent only** (`currentCapacity/maxCapacity`), plus OS `TimeToEmpty`/`TimeToFullCharge` when positive; **no mWh, no current** (`MC/native/ecpmac.m:101-120`; `MC/README.md:36` "不把容量百分比当成 mWh") | `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` (µA), `CURRENT_AVERAGE`, `CHARGE_COUNTER` (µAh), `ENERGY_COUNTER` (nWh), `CAPACITY`; `ACTION_BATTERY_CHANGED` extras `EXTRA_LEVEL`/`EXTRA_SCALE`/`EXTRA_VOLTAGE` (mV)/`EXTRA_TEMPERATURE` (0.1 °C)/`EXTRA_STATUS`/`EXTRA_PLUGGED`/`EXTRA_HEALTH`/`EXTRA_TECHNOLOGY`; `BATTERY_PROPERTY_*` can return `Integer.MIN_VALUE` for unsupported | **Adopt the Linux precedent, not the macOS one, for current**: take `Math.Abs(current_now)`, and attribute charge vs discharge from `EXTRA_STATUS` (`BATTERY_STATUS_CHARGING`/`DISCHARGING`), never from the sign alone (OEM sign conventions differ). Guard `Integer.MIN_VALUE`/unsupported as absent. Percent from `CAPACITY`; **do not** claim mWh/Wh unless `ENERGY_COUNTER` or `CHARGE_COUNTER × voltage` yields a consistent value. Temperature/voltage/technology from the extras. Cycle count only if `EXTRA_CYCLE_COUNT` exists (API 34+). |
| **Disk capacity** | `/proc/self/mountinfo` + `DriveInfo` (`TotalSize`/`AvailableFreeSpace`); stable key `disk.mount_<SHA256[..12]>.*`; `disk.system.*` for `/` (`LN/Customization/LinuxDisks.cs`) | `statfs("/System/Volumes/Data")` with `/` fallback; `f_blocks`/`f_bfree`/`f_bavail` (`MC/native/ecpmac.m:122-136`) | `StatFs` on `Context.getFilesDir()` / `Environment.getDataDirectory()` / `getExternalStorageDirectory()` gives total/available for the volume. No public block-device I/O API | Capacity from `StatFs`; keep the Linux idea of a **hashed per-volume key** (`disk.mount_<hash>.*`) and a `disk.system.*` alias for the data volume. Deduplicate volumes by filesystem/device as Linux does by major:minor (`LinuxDisks.cs:62`). |
| **Disk I/O rate** | `/sys/dev/block/<maj:min>/stat` fields `[2]`,`[6]` (×512), `[9]` (busy ms) (`LinuxDisks.cs:63-83`) | **not available** | No public API; `/proc/diskstats` is generally restricted | Report `disk.*.read_bps`/`write_bps`/`active_percent`/`queue_length` as **unsupported** (catalog-removed), exactly like macOS. Do not fabricate 0 B/s. |
| **Network throughput** | `sys/class/net/*/statistics/{rx,tx}_bytes` summed over Up non-loopback interfaces, delta ÷ Stopwatch, monotonic + same-name-set guard (`LN/Customization/LinuxNetwork.cs:47-71`) | `NET_RT_IFLIST2` counters restricted to `en*` + `IFF_UP` (`MC/native/ecpmac.m:138-184`) | `TrafficStats.getTotalRxBytes()`/`getTotalTxBytes()` (monotonic since boot; may reset on reboot); per-interface/per-UID reads were removed for third-party apps in API 31+; `NetworkCapabilities`/`LinkProperties` for transport, IP addresses, DNS and routes; `WifiInfo.getLinkSpeed()` (deprecated API 30+) for Wi-Fi link speed only | Use `TrafficStats` totals with the **same monotonic + reset guard** as Linux/macOS (on a reset, skip the sample rather than emit a negative or huge rate). Addresses/DNS from `LinkProperties`; link speed only where the platform reports it. Do not publish `network.tcp_connections`/`udp_connections` (no API). |
| **Network probe (ICMP/TCP/UDP)** | `System.Net.NetworkInformation.Ping` (unprivileged), `TcpClient` with 1000 ms timeout on a linked CTS, `UdpClient` echo with a 1000 ms `WaitAsync` (`LN/…/VariableHub.cs:1005-1145`) | identical code paths — `ProbeIcmpAsync` `MC/…/VariableHub.cs:366`, `ProbeTcpAsync` `:409`, `ProbeUdpAsync` `:459`, `PingStatusText` `:508` | Raw ICMP needs `CAP_NET_RAW` (not grantable to a normal app); `/system/bin/ping` may still work for the app's UID but is not a public API. `TcpClient`/`Socket` connect needs only `INTERNET`. `InetAddress.isReachable()` is documented as unreliable and typically falls back to TCP port 7 | Make **TCP connect** the primary probe (identical semantics: latency = total connect time, `tcp_connect_time` = connect phase, `ReplyStatus = "Connected"`), keep UDP only with a real responder, and treat ICMP as optional/`unsupported` when it cannot be performed. Keep the 1000 ms timeout, the 20-sample window, the same jitter/loss formulae, and the Linux/macOS rule that a failed probe publishes a **status string, never `999`**. |
| **DNS resolve timing** | `Stopwatch` around `Dns.GetHostAddressesAsync`, published as `probe.dns_resolve_time` (0 for literal IPs) (`LN:1013-1020`) | same | `InetAddress.getAllByName()` around a manual timer; Android caches DNS aggressively | Keep as-is; document that the value is cache-affected. |
| **Public IP** | `https://api.ipify.org` / `https://api6.ipify.org`, 3 s client timeout, 5-minute cache, `IPAddress.TryParse` validation, failure text (`LN/…/AdvancedVariableProvider.cs:565-584`) | same (`MC/…/AdvancedVariableProvider.cs:28-47`) | Works with `INTERNET` | Keep the 5-minute cache, the address-family validation, and the failure text; add an explicit opt-in/HTTPS-only policy. |
| **Time / 日进程** | Pure local computation + `HudProfileRenderer` derivation (`LN/…/VariableHub.cs:252-303`, `LN/Customization/HudProfileRenderer.cs:96-135`) | identical | Pure computation; no permission | Port verbatim, including `Math.Round(…, MidpointRounding.AwayFromZero)` and the 86400-based remaining-percent definition. **Careful:** the Monday week start uses `(((int)now.DayOfWeek + 6) % 7)` with .NET's Sunday=0; `java.time.DayOfWeek.getValue()` is Monday=1, so re-derive with the equivalent expression rather than translating blindly. Use IANA zone IDs (macOS form) for `time.world.*`. |
| **DeepSeek balance / period** | HTTPS GET + 1-minute cache + string-status failure contract (`LN/…/VariableHub.cs:1368-1511`) | identical, plus Keychain storage (`MC/Customization/SecretStore.cs`) | Works with `INTERNET`; store the key in `EncryptedSharedPreferences`/Keystore (`macOS Keychain` analogue) — **do not** copy the Linux file-key scheme | Port the 1-minute cache, the seven-key string-status failure contract, and the Beijing-time weekday/window peak logic verbatim; keep "no fabricated ¥0.00". |
| **Custom HTTP/JSON** | GET + headers (`${env:…}` only) + JSON path + per-source refresh 5 s..24 h + status strings (`LN/…/VariableHub.cs:1513-1571`) | identical | Works with `INTERNET` | Keep the config schema and status strings; **add** HTTPS enforcement, a response size cap, explicit connect/read timeouts, and a Keystore reference in place of `${env:…}` (see §4.7). |
| **Display / clipboard** | Avalonia screens + clipboard with a 2 s read guard (`LN/…/LinuxUiVariables.cs`) | CoreGraphics points/pixels + `NSPasteboard.changeCount` (`MC/native/ecpmac.m:233-274`, `MC/…/MacUiVariables.cs`) | `DisplayMetrics`/`WindowMetrics` for size+density (report in **px and dp** the way macOS reports px and pt); clipboard via `ClipboardManager` with `hasText`/text length and **no** unrestricted preview of sensitive content on Android 12+ (the OS shows a toast; do not add extra logging) | Keep `display.primary_width_px/height_px`, `display.system_dpi`, `display.scale_percent`; for clipboard keep `has_text`/`text_length`/`preview` but treat it as user-visible-sensitive (the desktop previews are 80 chars — consider truncating less or requiring an explicit opt-in). |

### 9.2 The "no fake data" contract to reproduce

Copy these five rules exactly; they are already enforced by code and tests in both desktop ports:

1. **Unmeasurable ⇒ absent.** `Put` skips nulls (`LN/…/LinuxVariableProvider.cs:327-330`); native
   helpers simply do not set the key (`MC/native/ecpmac.m:1`). Assertion:
   `LN/Tests/Program.cs:34` `"Missing sensors must not become fake zeroes."`
2. **A rate is never published from a single sample** and a counter reset re-primes rather than
   producing a delta (`LN/…/LinuxVariableProvider.cs:59`, `:74`, `:128`;
   `MC/…/MacVariableProvider.cs:70-103`). Assertions:
   `LN/Tests/Program.cs:37,43`; `MAC/Tests/Program.cs:59,62,66`.
3. **A failed remote/probe/battery-estimate value becomes an explicit localized status string,
   never a number.** Marker table in §1.4; probe rule §2.6; assertions
   `LN/Tests/LinuxGuiAudit.cs:134`, `:141`; `MAC/Tests/Program.cs:153`.
4. **Capability is discovered, then re-discoverable.** Build the advertised key set from a real
   double-sampled probe (`LN/Customization/LinuxVariableCatalog.cs:36-61`,
   `MC/Customization/MacVariableCatalog.cs:24-38`), expose a "re-detect" action
   (`"Detect Linux variables again"`, asserted `LN/Tests/LinuxGuiAudit.cs:96`), and re-detect after
   hardware/tool/permission changes (`LN/README.linux.md:49-50`).
5. **Strip unsupported references instead of rendering blanks** in built-in *and* imported profiles,
   while preserving the user's original file in `settings.previous.json` / import backups
   (`LN/Customization/LinuxVariableCatalog.cs:167-183`; assertions
   `LN/Tests/LinuxGuiAudit.cs:66,70`; `MAC/Tests/Program.cs:79-82`). `--` remains the display
   fallback only for a variable that is genuinely absent (`TemplateEngine.cs:24,33`).

Concrete Android consequences of rule 1-5: on a typical non-root device the honest catalog is roughly
CPU total, memory, battery (percent/status/voltage/temperature and *maybe* current), storage
capacity, network throughput via `TrafficStats`, TCP/UDP probes, time, 日进程, DeepSeek and custom
HTTP/JSON — while CPU temperature/frequency, GPU load/temp/VRAM, disk I/O rates, per-interface
traffic, connection counts and ICMP should appear as **unsupported** (removed from the catalog),
not as `0`.

### 9.3 Sampling changes Android forces

- The desktop's 100 ms `DispatcherTimer` (`LN/Customization/CustomHudRuntime.cs:47`) is only viable
  while the HUD is on screen; Android needs a foreground service (or `Choreographer`-driven updates
  while visible) and `WorkManager`/`AlarmManager` for anything in the background. Keep the
  once-per-wall-clock-second rule for time and DeepSeek-period profiles (`:445-452`) rather than a
  naive 1 Hz timer.
- Keep the request-set gating (`HudProfileRenderer.GetRequiredVariables`, `:35-72`) — on Android it
  is also the battery-saving mechanism.
- Keep the probe's **800 ms single-flight floor** (`:861`) only while visible; relax it to ≥5 s in
  the background, and re-use the Linux/macOS `stateKey = "{protocol}|{target}|{port}"` design so
  changing target/protocol does not inherit history.
- Keep the 1-minute DeepSeek cache but add a failure backoff on Android (the desktop retries at the
  render cadence, which is unacceptable for a mobile radio) — this is a deliberate, documented
  deviation.
- Keep two-sample priming (80 ms/200 ms loops on desktop) but drive it from the sensor callback
  cadence on Android; do not emit a first-sample rate.

---

## 10. Uncertainties and limitations

1. **No on-disk `settings.json` sample** exists in the workspace for either port; §7.3-7.5 is
   derived from the record declarations. Property order/omission details are `System.Text.Json`
   defaults (all properties written, including defaults) — marked UNCERTAIN, though the field names
   themselves are exact.
2. **`??ms` / `丢包??%` / `Loss ??%`** in the task brief do not correspond to any literal token in
   the trees; the real sentinel is `--` (see §2.7). The `??` sequences present in the source are
   prose inside catalog descriptions (`LN/Customization/VariableCatalog.cs:305,310,326`).
3. **Upstream ownership**: the clone is `QinAnze/zmd-charge` but every in-tree owner reference is
   `Lenkmat`; whether these are the same project or a rename/fork is UNCERTAIN (§8.3).
4. **Upstream licence**: MIT is asserted **only** in `UP/README.md:121-123`; no `LICENSE` file, no
   `NOTICE`, no `Copyright` line outside `UP/EndfieldCharge.csproj:17`. ECP's own `NOTICE.md:9-13`
   says the same thing. Anything stronger would be unverified.
5. **Android API availability** assertions in §9 are from platform knowledge, not from code in this
   workspace; they must be validated on real devices/target API levels during implementation.
   Vendor/SELinux-dependent sysfs nodes (thermal, cpufreq, KGSL/Mali) are explicitly
   device-dependent and must be probe-gated.
6. **The Linux `time.world.*` Windows-zone-ID dependency** works on the WSL/ICU validation host
   (`LN/artifacts/linux-audit/VARIABLE-AUDIT.md:290-293`) but is a latent failure on
   invariant-globalization builds because `PutWorld` swallows the exception
   (`LN/…/AdvancedVariableProvider.cs:1195`). Do not copy it to Android.
7. **`AppLog` performs no redaction** on either desktop platform (§3.6). Any Android logger added by
   the port must not log DeepSeek keys, custom-header values or full request URIs.
8. **The audited Linux capability set (339 keys, 0 missing)** is from one host
   (`Ubuntu 24.04 / WSL2 / Xvfb`, `LN/docs/linux-validation.md:3`), not a hardware certification
   (`LN/README.linux.md:50`, `LN/docs/linux-validation.md:33`).

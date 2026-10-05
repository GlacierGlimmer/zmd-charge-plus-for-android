package com.glacierglimmer.endfieldchargeplus.core.i18n

import com.glacierglimmer.endfieldchargeplus.core.model.AppLanguage

/**
 * Runtime UI/HUD language state and the shared bilingual string helper.
 *
 * This is the Android port of `EndfieldChargePlus.LocalizationManager`. It keeps the exact
 * behaviour of the Windows edition:
 *
 *  * the authoritative UI wording is the Windows zh→en dictionary ([zhToEn]); English text is
 *    never machine translated;
 *  * [translateLiteral] translates in both directions, so a literal that was already translated
 *    can be mapped back (the desktop menu does the same through `EnToZh`);
 *  * the resolved language is a [UiLanguage]; the persisted preference is an [AppLanguage].
 *
 * The default language is [UiLanguage.EN], matching `LocalizationManager.Current`'s initial value
 * before the application calls [setLanguage]/[initialize].
 */
object Strings {

    @Volatile
    private var language: UiLanguage = UiLanguage.EN

    /**
     * The full authoritative ECP zh→en table, copied verbatim from
     * `LocalizationManager.cs` (`ZhToEn`). Keys are Simplified Chinese literals, values are the
     * ECP English wording. Exposed for the settings UI and for dictionary-completeness tests.
     */
    val zhToEn: Map<String, String> = mapOf(
        // Settings shell
        "Endfield Charge Plus 设置" to "Endfield Charge Plus Settings",
        "配置文件夹" to "Config Folder",
        "保存并应用" to "Save & Apply",
        "显示与位置" to "Display & Position",
        "HUD 内容与数据" to "HUD Content & Data",
        "关于" to "About",
        "HUD 总开关" to "HUD Master Switch",
        "控制 HUD 的全部显示功能。" to "Controls all HUD display functions.",
        "开机启动" to "Start with Windows",
        "登录 Windows 后自动启动 Endfield Charge Plus。" to "Start Endfield Charge Plus after Windows sign-in.",
        "显示方式" to "Display Mode",
        "一直显示" to "Always Visible",
        "开启：HUD 持续显示。\n关闭：电源插拔显示电池；鼠标移到目标屏幕顶部中央可唤出当前方案。" to
            "On: keep the HUD visible.\nOff: power changes show Battery; move the pointer to the top-center of the target display to summon the active profile.",
        "常驻状态" to "Persistent",
        "显示层级" to "Layer",
        "HUD 不透明度" to "HUD Opacity",
        "100% 为完全不透明。" to "100% is fully opaque.",
        "位置" to "Position",
        "目标显示器" to "Target Display",
        "定位方式" to "Positioning",
        "预设位置" to "Preset",
        "X 微调 / px" to "X Offset / px",
        "Y 微调 / px" to "Y Offset / px",
        "X 向右为正，Y 向下为正。" to "+X right, +Y down.",
        "X 坐标 / px" to "X / px",
        "Y 坐标 / px" to "Y / px",
        "尺寸与动画" to "Size & Animation",
        "恢复默认" to "Reset",
        "HUD 缩放" to "HUD Scale",
        "总时长 / 秒" to "Duration / s",
        "回弹强度" to "Bounce",
        "波纹强度" to "Ripple Strength",
        "波纹幅度" to "Ripple Spread",
        "终末地风格状态栏 HUD" to "Endfield-style Status HUD",
        "构建日期  2026.09.23" to "Build  2026.09.23",
        "检查更新" to "Check Updates",
        "当前版本：v0.1.0" to "Current: v0.1.0",
        "最新版本：尚未获取" to "Latest: not checked",
        "状态：尚未检查" to "Status: not checked",
        "项目与协议" to "Project & License",
        "开源协议" to "License",
        "本项目 GitHub" to "Project GitHub",
        "原项目 GitHub" to "Upstream GitHub",
        "项目网站" to "Website",
        "打开" to "Open",
        "配置与维护" to "Config & Maintenance",
        "导出配置" to "Export",
        "导入配置" to "Import",
        "备份当前配置" to "Backup",
        "日志文件夹" to "Logs",
        "恢复全部默认" to "Reset All",

        // HUD customizer - profile tab
        "HUD 配置" to "HUD Profiles",
        "方案管理" to "Profile Management",
        "选择当前显示方案。内置方案修改后会另存为新方案，原预设保持不变。" to
            "Select the active HUD profile. Editing a built-in profile creates a copy; the original stays unchanged.",
        "预览当前方案" to "Preview",
        "当前方案" to "Active Profile",
        "新建" to "New",
        "保存" to "Save",
        "删除" to "Delete",
        "DeepSeek API 数据源" to "DeepSeek API Source",
        "此方案需要先在“数据源 → DeepSeek API”中配置 DeepSeek API Key；峰谷时段也可在该数据源中设置。未配置 API Key 时，余额相关数据无法获取。" to
            "Configure the DeepSeek API key under Data Sources → DeepSeek API first. Peak/off-peak windows are set there too. Balance data is unavailable without a key.",
        "目标时间模式" to "Target-time Mode",
        "关闭时显示当天已过进度；开启后显示距离下一个每日目标时间的剩余比例。" to
            "Off: show today's elapsed progress. On: show the remaining share until the next daily target time.",
        "每日目标时间" to "Daily Target",
        "GPU 设备" to "GPU Device",
        "检测到多个 GPU 时，选择此方案要显示的图形处理器。" to
            "Choose the GPU used by this profile when multiple adapters are detected.",
        "网络显示单位" to "Network Unit",
        "Mbps：按兆比特每秒显示；KB/s / MB/s：根据速度大小自动切换。" to
            "Mbps uses megabits/s; KB/s / MB/s switches automatically by rate.",
        "百分比计算方式" to "Percent Basis",
        "使用系统全部有效网络接口的流量。可按总吞吐量、仅下载、仅上传或上下行较大值计算右侧百分比。" to
            "Uses all active interfaces. Calculate the right-side percentage from total traffic, download, upload, or the larger direction.",
        "100% 对应网络速度" to "100% Reference Speed",
        "当前所选百分比模式的速度达到这里设定的值时显示 100%；计算前统一换算为 Byte/s。" to
            "The selected rate reaches 100% at this value. Rates are normalized to Byte/s before calculation.",
        "数值" to "Value",
        "单位" to "Unit",
        "检测地址" to "Probe Target",
        "支持 IPv4、IPv6 或域名。" to "IPv4, IPv6, or hostname.",
        "例如：1.1.1.1、2606:4700:4700::1111 或 example.com" to "e.g. 1.1.1.1, 2606:4700:4700::1111, or example.com",
        "检测协议" to "Protocol",
        "ICMP 使用标准 Ping；TCP 测量端口连接延迟；UDP 会发送探测数据并等待目标服务响应。" to
            "ICMP uses Ping; TCP measures connect latency; UDP sends probe data and waits for a service response.",
        "检测端口" to "Port",
        "TCP / UDP 使用该端口；ICMP 不使用端口。" to "Used by TCP / UDP; ignored by ICMP.",
        "方案名称" to "Profile Name",
        "例如：我的服务器 - 在线状态" to "e.g. My Server - Online",
        "动画模式" to "Animation",
        "简洁：直接展开最终 HUD，适合频繁查看。" to "Simple: quickly reveal the final HUD.",
        "完整：包含标题、波纹与形态切换，再进入最终 HUD。" to "Full: title, ripple, and shape transition before the final HUD.",
        "选择框中的方案即当前使用方案；始终保持一个方案被选中。" to
            "The selected profile is the active profile; one profile is always selected.",
        "自动轮播" to "Auto Cycle",
        "开启后按照下方轮播队列的顺序循环切换方案。" to "Cycle profiles in the queue below.",
        "轮播队列" to "Cycle Queue",
        "从上到下依次切换，到达末尾后回到第一项。队列只引用当前存在的方案。" to
            "Cycles top to bottom, then returns to the first item. The queue only references existing profiles.",
        "轮播间隔 / 秒" to "Interval / s",
        "轮播动画" to "Cycle Animation",
        "开启自动轮播后，以这里的动画为准，忽略各方案自身的动画模式。" to
            "When auto cycle is enabled, this animation overrides each profile's own animation setting.",
        "简洁：收回上一项后，快速唤出下一项。" to "Simple: retract the previous item, then quickly reveal the next.",
        "完整：收回上一项后，以完整标题、波纹与形态动画唤出下一项。" to
            "Full: retract the previous item, then reveal the next with the full title/ripple sequence.",
        "添加" to "Add",
        "上移" to "Up",
        "下移" to "Down",
        "显示内容" to "Display Content",
        "左侧显示主体信息，右侧显示百分比、进度或状态类数据。" to
            "Main information appears on the left; percentage, progress, or status appears on the right.",
        "标题阶段" to "Title Stage",
        "上行文字" to "Tagline",
        "主标题" to "Title",
        "系统状态" to "System Status",
        "左侧主体信息" to "Left Main Info",
        "容量、当前频率、速率、余额、当前时间等主要信息。" to
            "Capacity, frequency, rate, balance, time, and other primary values.",
        "主值" to "Primary",
        "次值 / 补充文字" to "Secondary / Note",
        "右侧状态值" to "Right Status",
        "使用率、剩余比例、已过进度等状态信息，并可与圆环联动。" to
            "Usage, remaining share, elapsed progress, and other status values; can drive the progress ring.",
        "状态值" to "Status",
        "后缀" to "Suffix",
        "{}模板支持 {变量|格式}；高级表达式使用 {= 表达式 | 格式}，支持 + - * / % ^、比较、&& / || / !、?:、??、if()、min/max/avg/sum/clamp/round 等；进度变量也可填写 = 表达式。例：{= if(memory.usage >= 80, '高', '正常')}。" to
            "Templates use {variable|format}. Advanced expressions use {= expression | format}; supported operators include + - * / % ^, comparisons, && / || / !, ?:, ??, and functions such as if(), min/max/avg/sum/clamp/round. Progress may also be an = expression. Example: {= if(memory.usage >= 80, 'High', 'OK')}.",
        "图标与进度环" to "Icons & Progress Ring",
        "进度变量" to "Progress Value",
        "cpu.usage 或 = (cpu.usage + gpu.usage) / 2" to "cpu.usage or = (cpu.usage + gpu.usage) / 2",
        "最小值" to "Minimum",
        "最大值" to "Maximum",
        "左侧图标" to "Left Icon",
        "右侧图标" to "Right Icon",
        "颜色" to "Color",
        "默认强调色" to "Accent Color",
        "条件变色 · 每行：变量 运算符 数值 => 颜色" to "Color rules · one per line: variable operator value => color",

        // Variable library
        "变量库" to "Variables",
        "按名称、变量名或分类查找，选择变量后查看完整说明。" to
            "Search by name, key, or category; select a variable for details.",
        "按功能分类；分类内优先显示常用状态与实时指标，再显示详细信息。支持按名称、变量名或分类查找。" to
            "Grouped by function; common status and live metrics come first, followed by detailed values. Search by name, key, or category.",
        "搜索：CPU 使用率 / cpu.usage" to "Search: CPU usage / cpu.usage",
        "选择一个变量" to "Select a variable",
        "从左侧列表选择变量后，这里会显示变量含义和使用建议。" to
            "Select a variable on the left to view its meaning and usage guidance.",
        "变量名" to "Variable Key",
        "复制变量名" to "Copy Key",
        "模板写法" to "Template",
        "复制模板" to "Copy Template",
        "数据属性" to "Data Properties",
        "类型" to "Type",
        "使用建议" to "Recommended Use",
        "常用格式" to "Formats",

        // Data sources
        "数据源" to "Data Sources",
        "配置余额查询与峰谷时段。API Key 使用 Windows 当前用户加密存储。" to
            "Configure balance queries and peak/off-peak windows. The API key is encrypted for the current Windows user.",
        "工作日高峰窗口（北京时间，分号分隔）" to "Weekday peak windows (Beijing time; separate with semicolons)",
        "将 GET JSON 字段映射为 custom.source.variable；Header 可引用环境变量。" to
            "Map GET JSON fields to custom.source.variable; headers may reference environment variables.",

        // Tray
        "预览 HUD" to "Preview HUD",
        "设置" to "Settings",
        "退出" to "Exit",
    )

    /**
     * Reverse table (en→zh), built exactly like `LocalizationManager.EnToZh`
     * (`ZhToEn.ToDictionary(value → key)`); when two Chinese literals share the same English
     * wording the last entry wins, matching `ToDictionary` order semantics.
     */
    private val enToZh: Map<String, String> = buildMap {
        for ((zh, en) in zhToEn) put(en, zh)
    }

    /** The language the UI is currently rendered in. */
    fun current(): UiLanguage = language

    /** Switches the language. Rendering reads this value on the next frame. */
    fun setLanguage(language: UiLanguage) {
        this.language = language
    }

    /** True when the UI renders in English. */
    fun isEnglish(): Boolean = language.isEnglish

    /** Picks the ECP wording for the current language; the desktop helper is `LocalizationManager.Text`. */
    fun t(zh: String, en: String): String = if (language.isEnglish) en else zh

    /**
     * Translates a fixed UI literal both ways, like `LocalizationManager.TranslateLiteral`.
     *
     * An unknown literal is returned unchanged; `null` becomes the empty string. This is what makes
     * ported desktop views (whose literal text is Chinese) render in English without a second view.
     */
    fun translateLiteral(text: String?): String {
        if (text.isNullOrEmpty()) return text ?: ""
        return if (language.isEnglish) zhToEn[text] ?: text else enToZh[text] ?: text
    }

    /**
     * Resolves the persisted preference plus the device language tag.
     *
     * Delegates to [UiLanguage.resolve] so there is exactly one resolution rule: any `zh*` device
     * tag resolves to Simplified Chinese, everything else to English, while an explicit
     * `zh-CN`/`en-US` preference always wins. Mirrors `LocalizationManager.Initialize`.
     */
    fun resolve(preference: String, systemLanguageTag: String): UiLanguage =
        UiLanguage.resolve(preference, systemLanguageTag)

    /**
     * Convenience wrapper over [resolve] + [setLanguage], mirroring
     * `LocalizationManager.Initialize(preference)`.
     */
    fun initialize(preference: String?, systemLanguageTag: String?): UiLanguage {
        val resolved = UiLanguage.resolve(preference, systemLanguageTag)
        setLanguage(resolved)
        return resolved
    }

    /** Convenience wrapper for the strongly typed persisted preference. */
    fun initialize(language: AppLanguage?, systemLanguageTag: String?): UiLanguage =
        initialize(language?.wire, systemLanguageTag)
}

package com.glacierglimmer.endfieldchargeplus.core.model

import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import java.util.Locale

data class WorldClockZone(val minutes: Int,val zh: String,val en: String) {
    fun label(language: UiLanguage): String = WorldClockTimeZones.offsetText(minutes)+" "+if(language.isEnglish) en else zh
}

/** Fixed offsets; region labels are examples and do not imply automatic DST. */
object WorldClockTimeZones {
    val all: List<WorldClockZone> = listOf(
        WorldClockZone(-720,"国际日期变更线以西","International Date Line West"),
        WorldClockZone(-660,"美属萨摩亚","American Samoa"),
        WorldClockZone(-600,"夏威夷","Hawaii"),
        WorldClockZone(-570,"马克萨斯群岛","Marquesas Islands"),
        WorldClockZone(-540,"阿拉斯加","Alaska"),
        WorldClockZone(-480,"太平洋时间（美国和加拿大）","Pacific Time (US & Canada)"),
        WorldClockZone(-420,"山地时间（美国和加拿大）","Mountain Time (US & Canada)"),
        WorldClockZone(-360,"中部时间（美国和加拿大）","Central Time (US & Canada)"),
        WorldClockZone(-300,"东部时间（美国和加拿大）","Eastern Time (US & Canada)"),
        WorldClockZone(-240,"大西洋时间（加拿大）","Atlantic Time (Canada)"),
        WorldClockZone(-210,"纽芬兰","Newfoundland"),
        WorldClockZone(-180,"布宜诺斯艾利斯、巴西利亚","Buenos Aires, Brasilia"),
        WorldClockZone(-120,"南乔治亚岛","South Georgia"),
        WorldClockZone(-60,"佛得角","Cape Verde"),
        WorldClockZone(0,"协调世界时","Coordinated Universal Time"),
        WorldClockZone(60,"柏林、巴黎、罗马","Berlin, Paris, Rome"),
        WorldClockZone(120,"雅典、开罗、赫尔辛基","Athens, Cairo, Helsinki"),
        WorldClockZone(180,"莫斯科、利雅得","Moscow, Riyadh"),
        WorldClockZone(210,"德黑兰","Tehran"),
        WorldClockZone(240,"迪拜、阿布扎比","Dubai, Abu Dhabi"),
        WorldClockZone(270,"喀布尔","Kabul"),
        WorldClockZone(300,"伊斯兰堡、卡拉奇","Islamabad, Karachi"),
        WorldClockZone(330,"新德里、孟买","New Delhi, Mumbai"),
        WorldClockZone(345,"加德满都","Kathmandu"),
        WorldClockZone(360,"达卡、廷布","Dhaka, Thimphu"),
        WorldClockZone(390,"仰光","Yangon"),
        WorldClockZone(420,"曼谷、河内、雅加达","Bangkok, Hanoi, Jakarta"),
        WorldClockZone(480,"北京、香港、新加坡","Beijing, Hong Kong, Singapore"),
        WorldClockZone(525,"尤克拉","Eucla"),
        WorldClockZone(540,"东京、首尔","Tokyo, Seoul"),
        WorldClockZone(570,"阿德莱德、达尔文","Adelaide, Darwin"),
        WorldClockZone(600,"悉尼、布里斯班","Sydney, Brisbane"),
        WorldClockZone(630,"豪勋爵岛","Lord Howe Island"),
        WorldClockZone(660,"所罗门群岛、新喀里多尼亚","Solomon Islands, New Caledonia"),
        WorldClockZone(720,"奥克兰、斐济","Auckland, Fiji"),
        WorldClockZone(765,"查塔姆群岛","Chatham Islands"),
        WorldClockZone(780,"努库阿洛法、阿皮亚","Nukuʻalofa, Apia"),
        WorldClockZone(825,"查塔姆群岛夏季偏移","Chatham Islands summer offset"),
        WorldClockZone(840,"莱恩群岛","Line Islands"),
    )
    fun normalize(minutes: Int): Int = if(minutes in -720..840 && minutes%15==0) minutes else 0
    fun usesUtcOffset(profile: HudProfile): Boolean = profile.timeUtcOffsetMinutes!=null || profile.builtInKey.equals("time.world-clock",true)
    fun offsetText(minutes: Int): String = String.format(Locale.US,"UTC%s%02d:%02d",if(minutes<0) "-" else "+",kotlin.math.abs(minutes)/60,kotlin.math.abs(minutes)%60)
}

object BuiltInProfileOrder {
    val keys = listOf(
        "system.battery",
        "system.cpu",
        "system.gpu",
        "system.memory",
        "system.disk",
        "system.network",
        "system.display-fps",
        "system.window-fps",
        "time.day-progress",
        "time.world-clock",
        "network.ping",
        "deepseek.balance-period",
    )
    fun rank(key: String): Int = keys.indexOfFirst { it.equals(key,true) }.let { if(it<0) Int.MAX_VALUE else it }
}

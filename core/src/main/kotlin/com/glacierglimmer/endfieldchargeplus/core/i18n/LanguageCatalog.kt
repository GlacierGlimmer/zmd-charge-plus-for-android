package com.glacierglimmer.endfieldchargeplus.core.i18n

import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

data class LanguageInfo(val code:String,val nativeName:String,val region:String,val languageWord:String,val rightToLeft:Boolean,val minecraftCode:String?) {
    val displayName:String get()=if(region.isBlank()) nativeName else "$nativeName ($region)"
}

object LanguageCatalog {
    private fun json(name:String):JsonObject = LanguageCatalog::class.java.getResourceAsStream("/localization/$name")?.bufferedReader(Charsets.UTF_8)?.use {Json.parseToJsonElement(it.readText()).jsonObject}
        ?: error("Missing bundled language resource: $name")
    val languages:List<LanguageInfo> by lazy {
        json("catalog.json").getValue("languages").jsonArray.map { entry ->
            val e=entry.jsonObject
            LanguageInfo(e.getValue("code").jsonPrimitive.content,e.getValue("nativeName").jsonPrimitive.content,e.getValue("region").jsonPrimitive.content,
                e.getValue("languageWord").jsonPrimitive.content,e.getValue("rightToLeft").jsonPrimitive.boolean,e["minecraftCode"]?.jsonPrimitive?.contentOrNull)
        }
    }
    fun find(code:String):LanguageInfo?=languages.firstOrNull {it.code.equals(code.replace('_','-'),true) || it.minecraftCode?.replace('_','-').equals(code.replace('_','-'),true)}
    fun resolve(raw:String?):UiLanguage {
        val code=raw.orEmpty().replace('_','-')
        val parts=code.split('-')
        val regional=if(parts.size>2)find(parts.first()+"-"+parts.last()) else null
        val parent=if(parts.size>2)(parts.size-1 downTo 2).firstNotNullOfOrNull {find(parts.take(it).joinToString("-"))} else null
        val match=find(code)?.code ?: regional?.code ?: parent?.code ?: when {
            code.startsWith("zh",true) && code.endsWith("-HK",true) -> "zh-HK"
            code.startsWith("zh-Hant",true) || code.startsWith("zh-MO",true) -> "zh-TW"
            code.startsWith("zh",true) -> "zh-CN"
            code.startsWith("en-",true) -> "en-US"
            code.substringBefore('-').equals("nb",true) -> "no-NO"
            code.substringBefore('-').equals("iw",true) -> "he-IL"
            code.substringBefore('-').equals("in",true) -> "id-ID"
            else -> languages.firstOrNull {it.code.substringBefore('-').equals(code.substringBefore('-'),true)}?.code ?: "en-US"
        }
        return UiLanguage.entries.first {it.wire==match}
    }
    private val packs=ConcurrentHashMap<String,Map<String,String>>()
    private data class Format(val expression:Regex,val translated:String,val slots:List<String>,val weight:Int)
    private val formats=ConcurrentHashMap<String,List<Format>>()
    fun translate(english:String,language:UiLanguage):String {
        if(language.isEnglish || english.isEmpty())return english
        val pack=packs.getOrPut(language.wire) {
            LanguageCatalog::class.java.getResourceAsStream("/localization/"+language.wire+".json")?.bufferedReader(Charsets.UTF_8)?.use {reader ->
                Json.parseToJsonElement(reader.readText()).jsonObject.mapValues {it.value.jsonPrimitive.content}
            } ?: emptyMap()
        }
        pack[english]?.let {return it}
        // Kotlin interpolation reaches this helper as a resolved string. Match only
        // explicit numeric slots from reviewed messages, keeping values unchanged.
        val choices=formats.getOrPut(language.wire) {
            pack.entries.mapNotNull { (key,value) ->
                val slots=Regex("\\{[0-9]+\\}").findAll(key).toList()
                if(slots.isEmpty())null else {
                    var end=0
                    val pattern=buildString {
                        append('^')
                        slots.forEach {slot ->append(Regex.escape(key.substring(end,slot.range.first)));append("(.*?)");end=slot.range.last+1}
                        append(Regex.escape(key.substring(end)));append('$')
                    }
                    Format(Regex(pattern,RegexOption.DOT_MATCHES_ALL),value,slots.map {it.value},key.length-slots.sumOf {it.value.length})
                }
            }.sortedByDescending {it.weight}
        }
        for(format in choices) {
            val match=format.expression.matchEntire(english) ?: continue
            val values=format.slots.zip(match.groupValues.drop(1)).toMap()
            return Regex("\\{[0-9]+\\}").replace(format.translated) {values[it.value] ?: it.value}
        }
        return english
    }
    fun info(language:UiLanguage):LanguageInfo=languages.first {it.code==language.wire}
}

package com.glacierglimmer.endfieldchargeplus.ui.screens.manual

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.glacierglimmer.endfieldchargeplus.core.i18n.LanguageCatalog
import com.glacierglimmer.endfieldchargeplus.localization.LocalUiLanguage
import com.glacierglimmer.endfieldchargeplus.localization.t
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Suppress("DEPRECATION")
@Composable
fun UserManualScreen(onNavigate:(String)->Unit) {
    val language=LocalUiLanguage.current
    val chapters=remember(language) {
        val stream=LanguageCatalog::class.java.getResourceAsStream("/localization/manual.full.${language.wire}.json")
            ?: LanguageCatalog::class.java.getResourceAsStream("/localization/manual.full.en-US.json")!!
        stream.bufferedReader(Charsets.UTF_8).use {Json.parseToJsonElement(it.readText()).jsonArray.map {it.jsonObject}}
    }
    var search by remember {mutableStateOf("")}
    val filtered=remember(chapters,search) {chapters.filter {search.isBlank() || it.toString().contains(search.trim(),true)}}
    val list=rememberLazyListState()
    val scope=rememberCoroutineScope()
    val clipboard=LocalClipboardManager.current
    fun JsonObject.text(key:String)=this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    Column(Modifier.fillMaxSize().padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value=search,onValueChange={search=it},label={Text(t("搜索章节或变量","Search chapters or keys"))},singleLine=true,modifier=Modifier.fillMaxWidth())
        LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items(filtered,key={it.text("id")}) {chapter ->
                AssistChip(onClick={scope.launch {list.animateScrollToItem(filtered.indexOf(chapter))}},label={Text(chapter.text("title"))})
            }
        }
        LazyColumn(Modifier.weight(1f),state=list,verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(filtered,key={it.text("id")}) {chapter ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(chapter.text("title"),style=MaterialTheme.typography.titleLarge)
                        Text(chapter.text("summary"),style=MaterialTheme.typography.bodyMedium)
                        chapter.getValue("blocks").jsonArray.forEach {element ->
                            val block=element.jsonObject
                            HorizontalDivider()
                            Text(block.text("title"),style=MaterialTheme.typography.titleMedium)
                            SelectionContainer {Text(block.text("body"))}
                            if(block.text("code").isNotBlank()) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    SelectionContainer {Text(block.text("code"),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)}
                                }
                                TextButton(onClick={clipboard.setText(AnnotatedString(block.text("code")))}) {Text(t("复制示例","Copy example"))}
                            }
                            if(block.text("target").isNotBlank())TextButton(onClick={onNavigate(block.text("target"))}) {Text(t("前往设置","Open settings"))}
                        }
                    }
                }
            }
        }
    }
}

package com.glacierglimmer.endfieldchargeplus.ui.components

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glacierglimmer.endfieldchargeplus.core.i18n.LanguageCatalog
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.localization.LanguageController
import com.glacierglimmer.endfieldchargeplus.localization.LocalUiLanguage
import com.glacierglimmer.endfieldchargeplus.localization.t
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun LanguageEntry(container:EcpContainer,controller:LanguageController) {
    var open by remember {mutableStateOf(false)}
    val word=LanguageCatalog.info(LocalUiLanguage.current).languageWord
    TextButton(onClick={open=true}) {
        Icon(Icons.Default.Language,contentDescription=null)
        Spacer(Modifier.width(6.dp))
        Text("Language / $word")
    }
    if(open) LanguageSelector(container,controller) {open=false}
}

@Composable
private fun LanguageSelector(container:EcpContainer,controller:LanguageController,onClose:()->Unit) {
    val config by container.configRepository.config.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var search by remember {mutableStateOf("")}
    var appearance by remember {mutableStateOf(false)}
    val word=LanguageCatalog.info(LocalUiLanguage.current).languageWord
    val filtered=remember(search) { LanguageCatalog.languages.filter {
        search.isBlank() || "${it.displayName} ${it.code} ${it.minecraftCode}".contains(search.trim(),ignoreCase=true)
    }}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val listHeight=(maxHeight*.6f).coerceAtLeast(160.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=14.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Text("Language / $word",style=MaterialTheme.typography.headlineSmall)
                OutlinedTextField(value=search,onValueChange={search=it},singleLine=true,
                    label={Text(t("搜索语言、地区或语言代码","Search languages, regions or language codes"))},
                    modifier=Modifier.fillMaxWidth().widthIn(max=760.dp).padding(vertical=12.dp))
                LazyColumn(Modifier.height(listHeight).fillMaxWidth().widthIn(max=760.dp)) {
                    if(search.isBlank() || "Auto System 跟随系统".contains(search,true)) item(key="Auto") {
                        LanguageRow(t("跟随系统","Follow system"),config.uiLanguage=="Auto") {controller.chooseCode("Auto",scope)}
                    }
                    items(filtered,key={it.code}) {language ->
                        LanguageRow(language.displayName,config.uiLanguage==language.code) {controller.chooseCode(language.code,scope)}
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick={appearance=true},modifier=Modifier.weight(1f)) {Text(t("全局字体大小和UI缩放","Global font size and UI scale"),textAlign=TextAlign.Center)}
                    Button(onClick=onClose,modifier=Modifier.weight(1f)) {Text(t("完成","Done"))}
                }
            }
            }
        }
    }
    if(appearance) {
        var font by remember {mutableFloatStateOf(config.globalFontScale.toFloat())}
        var ui by remember {mutableFloatStateOf(config.globalUiScale.toFloat())}
        var saving by remember {mutableStateOf(false)}
        AlertDialog(onDismissRequest={if(!saving)appearance=false},
            title={Text(t("全局字体大小和UI缩放","Global font size and UI scale"))},
            text={Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(t("字体缩放","Font scale")+" · ${(font*100).roundToInt()}%")
                Slider(value=font,onValueChange={font=it},valueRange=.75f..2f,steps=24)
                Text(t("UI 缩放","UI scale")+" · ${(ui*100).roundToInt()}%")
                Slider(value=ui,onValueChange={ui=it},valueRange=.75f..2f,steps=24)
                Text(t("应用于整个设置界面和所有 HUD。单个 HUD 的大小设置会与全局 UI 缩放相乘。","Applies to all settings and every HUD. Individual HUD size is multiplied by global UI scale."))
                TextButton(onClick={font=1f;ui=1f}) {Text(t("恢复 100%","Reset to 100%"))}
            }},
            confirmButton={TextButton(enabled=!saving,onClick={
                saving=true
                scope.launch {try {container.configRepository.update {it.copy(globalFontScale=font.toDouble(),globalUiScale=ui.toDouble())};appearance=false} finally {saving=false}}
            }) {Text(t("保存并应用","Save & Apply"))}},
            dismissButton={TextButton(enabled=!saving,onClick={appearance=false}) {Text(t("取消","Cancel"))}})
    }
}

@Composable
private fun LanguageRow(label:String,selected:Boolean,onClick:()->Unit) {
    Surface(modifier=Modifier.fillMaxWidth().padding(vertical=2.dp).clickable(onClick=onClick),
        border=if(selected)BorderStroke(1.dp,MaterialTheme.colorScheme.primary) else null,
        color=if(selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Text(label,modifier=Modifier.fillMaxWidth().padding(12.dp),textAlign=TextAlign.Center,style=MaterialTheme.typography.bodyLarge)
    }
}

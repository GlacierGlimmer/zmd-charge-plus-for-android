package com.glacierglimmer.endfieldchargeplus.ui.state

import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.core.metrics.MetricSnapshot
import com.glacierglimmer.endfieldchargeplus.core.metrics.VariableRegistry
import com.glacierglimmer.endfieldchargeplus.localization.EcpMessages

/**
 * Projects the engine's variable registry into the UI model.
 *
 * This is the single place where the settings UI talks to the registry, so a change in the engine
 * only ever touches this file. Availability is taken from the live snapshot — an unavailable
 * variable is marked as such instead of pretending to have a value.
 */
object VariableProjection {

    fun rows(snapshot: MetricSnapshot, language: UiLanguage): List<VariableRow> =
        (VariableRegistry.all() + snapshot.values.keys.filter { it.startsWith("custom.") || it.matches(Regex("cpu\\.core[0-9]+\\.usage")) }
            .sorted().mapNotNull(VariableRegistry::byName)).distinctBy { it.name }.map { descriptor ->
            val value = snapshot[descriptor.name]
            val localizedLabel=com.glacierglimmer.endfieldchargeplus.core.i18n.Strings.t(descriptor.labelZh.ifBlank {descriptor.name},descriptor.labelEn.ifBlank {descriptor.name},language)
            val localizedNote=com.glacierglimmer.endfieldchargeplus.core.i18n.Strings.t(descriptor.androidNote,descriptor.androidNoteEn,language)
            VariableRow(
                name = descriptor.name,
                categoryKey = descriptor.categoryKey,
                label = localizedLabel,
                typeLabel = EcpMessages.variableTypeLabel(descriptor.type.name),
                unit = descriptor.unit,
                description = if(language.isChineseSource) descriptor.descriptionZh else if(localizedNote.isNotBlank()) "$localizedLabel. $localizedNote" else com.glacierglimmer.endfieldchargeplus.core.i18n.Strings.t(descriptor.descriptionZh,descriptor.descriptionEn,language),
                formats = descriptor.commonFormats.joinToString(" / "),
                androidNote = localizedNote,
                available = value?.isAvailable == true,
                supportedOnAndroid = descriptor.isSupportedOnAndroid,
                unavailableReason = snapshot.unavailableReason(descriptor.name)
                    ?.let { EcpMessages.unavailableReason(it) }
                    .orEmpty(),
            )
        }
}

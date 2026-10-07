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
        VariableRegistry.all().map { descriptor ->
            val value = snapshot[descriptor.name]
            VariableRow(
                name = descriptor.name,
                categoryKey = descriptor.categoryKey,
                label = if (language.isEnglish) {
                    descriptor.labelEn.ifBlank { descriptor.name }
                } else {
                    descriptor.labelZh.ifBlank { descriptor.name }
                },
                typeLabel = EcpMessages.variableTypeLabel(descriptor.type.name),
                unit = descriptor.unit,
                description = if (language.isEnglish) descriptor.descriptionEn else descriptor.descriptionZh,
                formats = descriptor.commonFormats.joinToString(" / "),
                androidNote = if (language.isEnglish) descriptor.androidNoteEn else descriptor.androidNote,
                available = value?.isAvailable == true,
                supportedOnAndroid = descriptor.isSupportedOnAndroid,
                unavailableReason = snapshot.unavailableReason(descriptor.name)
                    ?.let { EcpMessages.unavailableReason(it) }
                    .orEmpty(),
            )
        }
}

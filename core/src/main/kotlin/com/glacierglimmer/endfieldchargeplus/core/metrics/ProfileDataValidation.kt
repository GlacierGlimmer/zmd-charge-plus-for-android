package com.glacierglimmer.endfieldchargeplus.core.metrics

import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.template.TemplateEngine

/** Validates the same values the HUD renderer uses; a legitimate numeric zero remains valid. */
object ProfileDataValidation {
    fun missing(profile: HudProfile, snapshot: MetricSnapshot): List<String> {
        val effective = WorldClockValues.effectiveSnapshot(profile, snapshot)
        val templates=listOf(profile.taglineTemplate,profile.titleTemplate,profile.primaryTemplate,
            profile.secondaryTemplate,profile.rightTemplate,profile.rightSuffix)
        val required=TemplateEngine.extractKeys(*templates.toTypedArray())+
            TemplateEngine.extractExpressionKeys(profile.progressVariable)+
            profile.colorRules.flatMap { TemplateEngine.extractExpressionKeys(it.variable) }
        val missing=required.filter { key -> when(val value=effective[key]) {
            is MetricValue.Number -> !value.value.isFinite()
            is MetricValue.Text -> value.value.isBlank() || value.value=="--" || value.value=="N/A"
            else -> true
        } }.toMutableSet()
        for(template in templates) for(token in Regex("\\{=[^{}]*\\}").findAll(template)) {
            val rendered=TemplateEngine.render(token.value,{ effective[it] })
            if(rendered=="--" || rendered.contains("NaN") || rendered.contains("Infinity")) missing.add(token.value)
        }
        if(profile.progressVariable.isNotBlank() && !TemplateEngine.evaluateNumber(profile.progressVariable,{effective[it]},Double.NaN).isFinite())
            missing.add(profile.progressVariable)
        return missing.sorted()
    }
}

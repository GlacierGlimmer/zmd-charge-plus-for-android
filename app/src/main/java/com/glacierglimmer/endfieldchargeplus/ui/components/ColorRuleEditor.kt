package com.glacierglimmer.endfieldchargeplus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.glacierglimmer.endfieldchargeplus.core.model.HudColorRule
import com.glacierglimmer.endfieldchargeplus.ui.state.ColorRuleText

/** Localized labels of [ColorRuleEditor]; the screen owns the translation. */
data class ColorRuleStrings(
    val help: String,
    val variable: String,
    val operator: String,
    val threshold: String,
    val color: String,
    val add: String,
    val empty: String,
    val invalidLines: (List<Int>) -> String,
)

/**
 * The colour-rule editor.
 *
 * Users edit the same line format the desktop editions accept —
 * `variable operator value => #RRGGBB` — and the parsed rules are pushed through
 * [ColorRuleText], which is a verbatim port of the desktop parser. Malformed lines are listed
 * instead of being silently swallowed, and the parsed result is always shown underneath.
 */
@Composable
fun ColorRuleEditor(
    rules: List<HudColorRule>,
    onRulesChange: (List<HudColorRule>) -> Unit,
    strings: ColorRuleStrings,
    modifier: Modifier = Modifier,
    editorKey: Any? = null,
    enabled: Boolean = true,
) {
    var text by remember(editorKey) { mutableStateOf(ColorRuleText.format(rules)) }
    var draftVariable by remember(editorKey) { mutableStateOf("") }
    var draftOperator by remember(editorKey) { mutableStateOf(ColorRuleText.operators.first()) }
    var draftValue by remember(editorKey) { mutableStateOf("") }
    var draftColor by remember(editorKey) { mutableStateOf("#FF4D4F") }

    val invalid = ColorRuleText.invalidLines(text)

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = strings.help,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { updated ->
                text = updated
                onRulesChange(ColorRuleText.parse(updated))
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            enabled = enabled,
            isError = invalid.isNotEmpty(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            supportingText = if (invalid.isEmpty()) null else {
                { Text(strings.invalidLines(invalid)) }
            },
        )

        if (rules.isEmpty()) {
            Text(
                text = strings.empty,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            rules.forEachIndexed { index, rule ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${ColorRuleText.format(rule)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { onRulesChange(rules.filterIndexed { i, _ -> i != index }) },
                        enabled = enabled,
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draftVariable,
                onValueChange = { draftVariable = it },
                modifier = Modifier.weight(1.4f),
                label = { Text(strings.variable) },
                singleLine = true,
                enabled = enabled,
            )
            OutlinedTextField(
                value = draftValue,
                onValueChange = { draftValue = it },
                modifier = Modifier.weight(0.8f),
                label = { Text(strings.threshold) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draftColor,
                onValueChange = { draftColor = it },
                modifier = Modifier.weight(1f),
                label = { Text(strings.color) },
                singleLine = true,
                enabled = enabled,
            )
            DropdownRow(
                title = strings.operator,
                options = ColorRuleText.operators,
                selectedIndex = ColorRuleText.operators.indexOf(draftOperator).coerceAtLeast(0),
                onSelected = { draftOperator = ColorRuleText.operators[it] },
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
        }
        TextButton(
            onClick = {
                val value = draftValue.trim().toDoubleOrNull() ?: return@TextButton
                val color = draftColor.trim()
                if (draftVariable.isBlank() || !color.startsWith("#")) return@TextButton
                val added = HudColorRule(
                    variable = draftVariable.trim(),
                    operator = draftOperator,
                    value = value,
                    color = color,
                )
                val updated = rules + added
                text = ColorRuleText.format(updated)
                onRulesChange(updated)
                draftVariable = ""
                draftValue = ""
            },
            enabled = enabled && draftVariable.isNotBlank() && draftValue.trim().toDoubleOrNull() != null,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(strings.add)
        }
    }
}

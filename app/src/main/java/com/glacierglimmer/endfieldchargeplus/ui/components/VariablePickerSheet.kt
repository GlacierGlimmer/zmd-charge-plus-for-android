package com.glacierglimmer.endfieldchargeplus.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.glacierglimmer.endfieldchargeplus.ui.state.VariableRow
import com.glacierglimmer.endfieldchargeplus.ui.state.VariableSearch

/**
 * Variable library picker.
 *
 * Used both as a full page (变量库 Variables) and as an insert sheet behind a template field, so a
 * user never has to remember a variable name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VariablePickerSheet(
    rows: List<VariableRow>,
    title: String,
    searchPlaceholder: String,
    allCategoriesLabel: String,
    unavailableLabel: String,
    onDismiss: () -> Unit,
    onPick: (VariableRow) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(VariableSearch.ALL_CATEGORIES) }

    val categories = remember(rows) { VariableSearch.categories(rows) }
    val filtered = remember(rows, query, category) { VariableSearch.filter(rows, query, category) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(searchPlaceholder) },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            CategoryFilterRow(
                categories = categories,
                selected = category,
                allLabel = allCategoriesLabel,
                onSelect = { category = it },
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            if (filtered.isEmpty()) {
                EmptyState(unavailableLabel)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(filtered, key = { it.name }) { row ->
                        VariablePickerItem(row = row, onPick = { onPick(row) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryFilterRow(
    categories: List<String>,
    selected: String,
    allLabel: String,
    onSelect: (String) -> Unit,
) {
    val options = listOf(VariableSearch.ALL_CATEGORIES) + categories
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (selected == VariableSearch.ALL_CATEGORIES) allLabel else selected,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
        }
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(options) { option ->
                val label = if (option == VariableSearch.ALL_CATEGORIES) allLabel else option
                androidx.compose.material3.FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun VariablePickerItem(row: VariableRow, onPick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick() }
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = row.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (row.unit.isNotBlank()) {
                Text(
                    text = row.unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = row.typeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.formats.isNotBlank()) {
                Text(
                    text = row.formats,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

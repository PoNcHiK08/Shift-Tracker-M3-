package com.vkurylo.shifttracker.ui.jobs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.model.Currencies
import com.vkurylo.shifttracker.ui.util.currentLocale

internal const val CURRENCY_LIST_TAG = "currency-list"

/** "PLN · Polish Zloty" */
@Composable
fun currencyLabel(code: String): String = "$code · ${Currencies.displayName(code, currentLocale())}"

/**
 * Searchable list of currencies: the common ones (PLN, UAH, EUR, USD, GBP, CZK) on top, then the
 * rest alphabetically. Search matches the code, the name (in the app language) and the symbol.
 */
@Composable
fun CurrencyPickerDialog(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val locale = currentLocale()
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val matches = Currencies.all.filter { code ->
        q.isEmpty() || code.contains(q, ignoreCase = true) ||
            Currencies.displayName(code, locale).contains(q, ignoreCase = true) ||
            Currencies.symbol(code).equals(q, ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.currency_pick_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.currency_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp).testTag(CURRENCY_LIST_TAG)) {
                    items(matches, key = { it }) { code ->
                        // A divider after the pinned group (only while it is shown in full).
                        if (q.isEmpty() && code == Currencies.all[Currencies.pinned.size]) HorizontalDivider()
                        ListItem(
                            modifier = Modifier.selectable(selected = code == selected, role = Role.RadioButton) { onSelect(code) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(Currencies.displayName(code, locale)) },
                            supportingContent = { Text("$code · ${Currencies.symbol(code)}") },
                            trailingContent = if (code == selected) {
                                { Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                            } else null,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

package com.vkurylo.shifttracker.ui.shift

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.calc.RateScope
import com.vkurylo.shifttracker.ui.summary.signedMoney
import com.vkurylo.shifttracker.ui.util.Formatters
import com.vkurylo.shifttracker.ui.util.currentLocale

internal const val APPLY_RATE_ALL_BUTTON = "apply-rate-all-button"
internal const val APPLY_RATE_DIALOG = "apply-rate-dialog"
internal const val APPLY_RATE_SUMMARY = "apply-rate-summary"
internal const val APPLY_RATE_SCOPE = "apply-rate-scope-"
internal const val APPLY_RATE_CONFIRM = "apply-rate-confirm"

/**
 * "Apply the current rate?": which shifts (all of the job's, or from this shift's date), how many change and by
 * how much money, before anything is written; and that past months are rewritten.
 */
@Composable
internal fun ApplyRateDialogView(
    dialog: ApplyRateDialog,
    onScope: (RateScope) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = currentLocale()
    val plan = dialog.plan
    AlertDialog(
        modifier = Modifier.testTag(APPLY_RATE_DIALOG),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apply_rate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.selectableGroup()) {
                    ScopeOption(RateScope.ALL, stringResource(R.string.apply_rate_scope_all), dialog.scope, onScope)
                    ScopeOption(
                        RateScope.FROM_DATE,
                        stringResource(R.string.apply_rate_scope_from, Formatters.shortDate(dialog.date, locale)),
                        dialog.scope,
                        onScope,
                    )
                }
                Text(
                    if (plan.count == 0) {
                        stringResource(R.string.apply_rate_none)
                    } else {
                        pluralStringResource(
                            R.plurals.apply_rate_summary,
                            plan.count,
                            plan.count,
                            signedMoney(plan.differenceMinor, dialog.job.currency, locale),
                        )
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag(APPLY_RATE_SUMMARY),
                )
                Text(stringResource(R.string.apply_rate_warning), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = plan.count > 0 && !dialog.applying,
                modifier = Modifier.testTag(APPLY_RATE_CONFIRM),
            ) { Text(stringResource(R.string.apply_rate_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ScopeOption(scope: RateScope, label: String, selected: RateScope, onScope: (RateScope) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = scope == selected, role = Role.RadioButton, onClick = { onScope(scope) })
            .testTag(APPLY_RATE_SCOPE + scope.name)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = scope == selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

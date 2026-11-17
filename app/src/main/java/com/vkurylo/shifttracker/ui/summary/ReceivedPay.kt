package com.vkurylo.shifttracker.ui.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.calc.JobYearReconciliation
import com.vkurylo.shifttracker.domain.calc.PayReconciliation
import com.vkurylo.shifttracker.domain.model.Currencies
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Payout
import com.vkurylo.shifttracker.ui.components.JobAvatar
import com.vkurylo.shifttracker.ui.components.MonthStepperField
import com.vkurylo.shifttracker.ui.components.PickerField
import com.vkurylo.shifttracker.ui.components.SegmentedItem
import com.vkurylo.shifttracker.ui.components.textLengthSupport
import com.vkurylo.shifttracker.ui.util.Formatters
import com.vkurylo.shifttracker.ui.util.currentLocale
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

const val RECEIVED_PAY_ROW_TAG = "received-pay-row-"
const val RECEIVED_PAY_SHEET_TAG = "received-pay-sheet"
const val RECEIVED_PAY_ADD_TAG = "received-pay-add"
const val RECEIVED_PAY_PAYMENT_TAG = "received-pay-payment-"
const val RECEIVED_PAY_AMOUNT_TAG = "received-pay-amount"
const val RECEIVED_PAY_SAVE_TAG = "received-pay-save"
const val RECEIVED_PAY_DELETE_TAG = "received-pay-delete"
const val RECEIVED_PAY_CANCEL_TAG = "received-pay-cancel"

/** "+12,00 zł" / "-12,00 zł" / "0,00 zł": the sign always says which way the difference goes. */
internal fun signedMoney(minor: Long, currency: String, locale: java.util.Locale): String =
    (if (minor > 0) "+" else "") + Formatters.money(minor, currency, locale)

@Composable
private fun differenceColor(difference: Long): Color = when {
    difference < 0 -> MaterialTheme.colorScheme.error
    difference > 0 -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun AmountLine(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, color = valueColor)
    }
}

@Composable
private fun receivedText(payouts: List<Payout>, receivedMinor: Long, currency: String): String =
    if (payouts.isEmpty()) {
        stringResource(R.string.received_pay_not_entered)
    } else {
        Formatters.money(receivedMinor, currency, currentLocale()) + " · " +
            pluralStringResource(R.plurals.received_pay_count, payouts.size, payouts.size)
    }

/**
 * One job of the month: what the app calculates (net, always), what arrived, and the difference. Tap to see and
 * change the payments.
 */
@Composable
internal fun ReceivedPayItem(row: PayReconciliation, index: Int, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    SegmentedItem(
        index = index,
        count = count,
        onClick = onClick,
        role = Role.Button,
        modifier = modifier.testTag(RECEIVED_PAY_ROW_TAG + row.job.id),
    ) { interaction ->
        Column(interaction.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                JobAvatar(row.job)
                Spacer(Modifier.width(16.dp))
                Text(row.job.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            AmountLine(stringResource(R.string.received_pay_calculated), Formatters.money(row.calculatedNetMinor, row.currency, locale))
            AmountLine(stringResource(R.string.received_pay_received), receivedText(row.payouts, row.receivedMinor, row.currency))
            row.differenceMinor?.let { difference ->
                AmountLine(
                    stringResource(R.string.received_pay_difference),
                    signedMoney(difference, row.currency, locale),
                    valueColor = differenceColor(difference),
                )
            }
        }
    }
}

/** Year view: one job's received pay over the months that have it, against the calculated net of those months. */
@Composable
internal fun ReceivedPayYearItem(line: JobYearReconciliation, index: Int, count: Int, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    SegmentedItem(index = index, count = count, modifier = modifier.testTag(RECEIVED_PAY_ROW_TAG + line.job.id)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                JobAvatar(line.job)
                Spacer(Modifier.width(16.dp))
                Text(line.job.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            AmountLine(stringResource(R.string.received_pay_calculated), Formatters.money(line.calculatedNetMinor, line.currency, locale))
            AmountLine(stringResource(R.string.received_pay_received), Formatters.money(line.receivedMinor, line.currency, locale))
            AmountLine(
                stringResource(R.string.received_pay_difference),
                signedMoney(line.differenceMinor, line.currency, locale),
                valueColor = differenceColor(line.differenceMinor),
            )
        }
    }
}

/**
 * The payments of one job for one month, and the form to add or change one. [row] is the live reconciliation
 * of that job and month (null when nothing is there any more: the list is then empty).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReceivedPaySheet(
    job: Job,
    month: YearMonth,
    row: PayReconciliation?,
    editor: PayoutEditorViewModel,
    onDismiss: () -> Unit,
) {
    val draft by editor.draft.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = {
            editor.cancel()
            onDismiss()
        },
        sheetState = sheetState,
        modifier = Modifier.testTag(RECEIVED_PAY_SHEET_TAG),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            val current = draft
            if (current == null) {
                PaymentsList(job, month, row, editor)
            } else {
                PaymentForm(current, editor)
            }
        }
    }
}

@Composable
private fun PaymentsList(job: Job, month: YearMonth, row: PayReconciliation?, editor: PayoutEditorViewModel) {
    val locale = currentLocale()
    val currency = job.currency
    Row(verticalAlignment = Alignment.CenterVertically) {
        JobAvatar(job, size = 48.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(stringResource(R.string.received_pay_sheet_title, job.name), style = MaterialTheme.typography.titleLarge)
            Text(
                Formatters.monthYear(month, locale),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    val payouts = row?.payouts.orEmpty()
    AmountLine(stringResource(R.string.received_pay_calculated), Formatters.money(row?.calculatedNetMinor ?: 0, currency, locale))
    AmountLine(stringResource(R.string.received_pay_received), receivedText(payouts, row?.receivedMinor ?: 0, currency))
    row?.differenceMinor?.let { difference ->
        AmountLine(stringResource(R.string.received_pay_difference), signedMoney(difference, currency, locale), valueColor = differenceColor(difference))
    }
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        payouts.forEach { payout -> PaymentRow(payout, currency, onClick = { editor.startEdit(payout, job) }) }
    }
    Spacer(Modifier.height(12.dp))
    FilledTonalButton(onClick = { editor.startNew(job, month) }, modifier = Modifier.fillMaxWidth().testTag(RECEIVED_PAY_ADD_TAG)) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.received_pay_add))
    }
}

@Composable
private fun PaymentRow(payout: Payout, currency: String, onClick: () -> Unit) {
    val locale = currentLocale()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().testTag(RECEIVED_PAY_PAYMENT_TAG + payout.id),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    payout.receivedOn?.let { stringResource(R.string.received_pay_received_on_value, Formatters.mediumDate(it, locale)) }
                        ?: stringResource(R.string.received_pay_received),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (!payout.note.isNullOrBlank()) {
                    Text(
                        payout.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(Formatters.money(payout.amountMinor, currency, locale), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentForm(draft: PayoutDraft, editor: PayoutEditorViewModel) {
    val locale = currentLocale()
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(if (draft.isNew) R.string.received_pay_new else R.string.received_pay_edit),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = editor::cancel, modifier = Modifier.testTag(RECEIVED_PAY_CANCEL_TAG)) {
            Icon(Icons.Filled.Close, stringResource(R.string.close))
        }
    }
    Text(draft.job.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = draft.amount,
        onValueChange = editor::setAmount,
        label = { Text(stringResource(R.string.received_pay_amount)) },
        suffix = { Text(Currencies.symbol(draft.currency)) },
        singleLine = true,
        isError = draft.showErrors && draft.amountMinor == null,
        supportingText = if (draft.showErrors && draft.amountMinor == null) {
            { Text(stringResource(R.string.error_amount_invalid)) }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        textStyle = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.fillMaxWidth().testTag(RECEIVED_PAY_AMOUNT_TAG),
    )
    Spacer(Modifier.height(12.dp))
    MonthStepperField(
        label = stringResource(R.string.received_pay_month),
        hint = stringResource(R.string.received_pay_month_hint),
        month = draft.month,
        onChange = editor::setMonth,
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        PickerField(
            label = stringResource(R.string.received_pay_received_on),
            value = draft.receivedOn?.let { Formatters.fullDate(it, locale) }.orEmpty(),
            icon = { Icon(Icons.Filled.Event, contentDescription = null) },
            onClick = { showDatePicker = true },
            modifier = Modifier.weight(1f),
        )
        if (draft.receivedOn != null) {
            IconButton(onClick = { editor.setReceivedOn(null) }) {
                Icon(Icons.Filled.Close, stringResource(R.string.received_pay_received_on_clear))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = draft.note,
        onValueChange = editor::setNote,
        label = { Text(stringResource(R.string.received_pay_note)) },
        isError = draft.noteTooLong,
        supportingText = textLengthSupport(draft.note.length),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        if (!draft.isNew) {
            TextButton(onClick = editor::delete, modifier = Modifier.testTag(RECEIVED_PAY_DELETE_TAG)) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.weight(1f))
        }
        Button(onClick = editor::save, enabled = !draft.saving, modifier = Modifier.testTag(RECEIVED_PAY_SAVE_TAG)) { Text(stringResource(R.string.save)) }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = (draft.receivedOn ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        editor.setReceivedOn(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

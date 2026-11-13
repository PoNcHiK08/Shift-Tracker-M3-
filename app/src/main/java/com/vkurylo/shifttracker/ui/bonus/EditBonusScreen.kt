package com.vkurylo.shifttracker.ui.bonus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.model.Currencies
import com.vkurylo.shifttracker.ui.components.ChoiceButtonGroup
import com.vkurylo.shifttracker.ui.components.EmptyState
import com.vkurylo.shifttracker.ui.components.MonthStepperField
import com.vkurylo.shifttracker.ui.components.textLengthSupport
import com.vkurylo.shifttracker.ui.components.ExpressiveFilterPill
import com.vkurylo.shifttracker.ui.components.JobColorDot
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.PickerField
import com.vkurylo.shifttracker.ui.components.composeColor
import com.vkurylo.shifttracker.ui.util.Formatters
import com.vkurylo.shifttracker.ui.util.currentLocale
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditBonusScreen(
    onClose: () -> Unit,
    onManageJobs: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditBonusViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = currentLocale()
    val snackbar = LocalSnackbarController.current
    val context = LocalContext.current
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                EditBonusEvent.Saved -> snackbar.show(context.getString(R.string.bonus_saved))
                is EditBonusEvent.Deleted -> snackbar.show(
                    message = context.getString(R.string.bonus_deleted),
                    actionLabel = context.getString(R.string.undo),
                    onAction = { viewModel.undoDelete(event.bonus) },
                )
            }
            onClose()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.new_bonus else R.string.edit_bonus)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
                },
                actions = {
                    if (!state.isNew) {
                        // Like Save: only once the entry is loaded. A tap during loading used to set the
                        // "confirm delete" flag on a state the loader then replaced, so it did nothing.
                        IconButton(onClick = viewModel::requestDelete, enabled = !state.loading) {
                            Icon(Icons.Filled.DeleteOutline, stringResource(R.string.delete_bonus))
                        }
                    }
                    TextButton(onClick = viewModel::save, enabled = !state.loading && state.jobs.isNotEmpty() && !state.saving) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) return@Scaffold
        if (state.jobs.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.WorkOutline,
                title = stringResource(R.string.no_jobs_title),
                message = stringResource(R.string.no_jobs_message),
                modifier = Modifier.padding(padding),
                action = { Button(onClick = onManageJobs) { Text(stringResource(R.string.add_job)) } },
            )
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = state.amount,
                onValueChange = viewModel::setAmount,
                label = { Text(stringResource(R.string.bonus_amount)) },
                suffix = { Text(Currencies.symbol(state.currency)) },
                singleLine = true,
                isError = state.showErrors && state.amountGrosze == null,
                supportingText = if (state.showErrors && state.amountGrosze == null) {
                    { Text(stringResource(R.string.error_amount_invalid)) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                textStyle = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.job),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.jobs.forEach { job ->
                    ExpressiveFilterPill(
                        selected = job.id == state.jobId,
                        onClick = { viewModel.selectJob(job) },
                        label = job.name,
                        leadingIcon = { JobColorDot(job.composeColor(), size = 12.dp) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            // Either a bonus on a day (as before) or one for a whole month, which has no day.
            ChoiceButtonGroup(
                options = listOf(false, true),
                selected = state.wholeMonth,
                onSelect = viewModel::setWholeMonth,
                label = { wholeMonth -> stringResource(if (wholeMonth) R.string.bonus_kind_month else R.string.bonus_kind_day) },
            )
            Spacer(Modifier.height(12.dp))
            if (state.wholeMonth) {
                MonthStepperField(
                    label = stringResource(R.string.bonus_month),
                    month = state.month,
                    onChange = viewModel::setMonth,
                )
            } else {
                PickerField(
                    label = stringResource(R.string.date),
                    value = Formatters.fullDate(state.date, locale),
                    icon = { Icon(Icons.Filled.Event, contentDescription = null) },
                    onClick = { showDatePicker = true },
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::setNote,
                label = { Text(stringResource(R.string.bonus_note)) },
                isError = state.noteTooLong,
                supportingText = textLengthSupport(state.note.length),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (state.wholeMonth) R.string.bonus_hint_month else R.string.bonus_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        viewModel.setDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) {
            DatePicker(state = pickerState)
        }
    }
    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            icon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
            title = { Text(stringResource(R.string.delete_bonus_title)) },
            confirmButton = {
                TextButton(onClick = viewModel::delete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

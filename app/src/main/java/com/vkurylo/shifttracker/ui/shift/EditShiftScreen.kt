package com.vkurylo.shifttracker.ui.shift

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.ui.components.EmptyState
import com.vkurylo.shifttracker.ui.components.ExpressiveFilterPill
import com.vkurylo.shifttracker.ui.components.JobColorDot
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.PickerField
import com.vkurylo.shifttracker.ui.components.ChoiceButtonGroup
import com.vkurylo.shifttracker.ui.components.RollingMoney
import com.vkurylo.shifttracker.ui.components.RollingText
import com.vkurylo.shifttracker.ui.components.TimeDialog
import com.vkurylo.shifttracker.ui.components.composeColor
import com.vkurylo.shifttracker.ui.jobs.jobRatesLabel
import com.vkurylo.shifttracker.ui.util.Formatters
import com.vkurylo.shifttracker.ui.util.currentLocale
import java.time.Instant
import java.time.ZoneOffset

private val BreakPresets = listOf(0, 15, 30, 45, 60)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditShiftScreen(
    onClose: () -> Unit,
    onManageJobs: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditShiftViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = currentLocale()
    val snackbar = LocalSnackbarController.current
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                EditShiftEvent.Saved -> snackbar.show(context.getString(R.string.shift_saved))
                is EditShiftEvent.Deleted -> snackbar.show(
                    message = context.getString(R.string.shift_deleted),
                    actionLabel = context.getString(R.string.undo),
                    onAction = { viewModel.undoDelete(event.shift) },
                )
            }
            onClose()
        }
    }

    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showStartPicker by rememberSaveable { mutableStateOf(false) }
    var showEndPicker by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.new_shift else R.string.edit_shift)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = viewModel::requestDelete) {
                            Icon(Icons.Filled.DeleteOutline, stringResource(R.string.delete_shift))
                        }
                    }
                    TextButton(onClick = viewModel::save, enabled = !state.loading && state.jobs.isNotEmpty()) {
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
            FieldLabel(stringResource(R.string.job))
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
            if (state.showErrors && state.jobMissing) {
                Text(stringResource(R.string.error_job_required), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))
            PickerField(
                label = stringResource(R.string.date),
                value = Formatters.fullDate(state.date, locale),
                icon = { Icon(Icons.Filled.Event, contentDescription = null) },
                onClick = { showDatePicker = true },
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PickerField(
                    label = stringResource(R.string.start_time),
                    value = Formatters.time(state.start),
                    icon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                    onClick = { showStartPicker = true },
                    modifier = Modifier.weight(1f),
                )
                PickerField(
                    label = stringResource(R.string.end_time),
                    value = Formatters.time(state.end),
                    icon = { Icon(if (state.isOvernight) Icons.Filled.NightsStay else Icons.Filled.Schedule, contentDescription = null) },
                    onClick = { showEndPicker = true },
                    modifier = Modifier.weight(1f),
                    supporting = if (state.isOvernight) stringResource(R.string.ends_next_day) else null,
                )
            }

            Spacer(Modifier.height(16.dp))
            FieldLabel(stringResource(R.string.unpaid_break))
            // One connected button group (like Week / Month / Year): the chosen break becomes a pill.
            // A break typed below that isn't a preset leaves none selected.
            ChoiceButtonGroup(
                options = BreakPresets,
                selected = state.breakValue?.takeIf { it in BreakPresets },
                onSelect = { minutes -> viewModel.setBreak(minutes.toString()) },
                label = { minutes ->
                    if (minutes == 0) stringResource(R.string.no_break) else stringResource(R.string.minutes_short, minutes)
                },
                weight = { minutes -> if (minutes == 0) 1.6f else 1f },
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedTextField(
                value = state.breakMinutes,
                onValueChange = viewModel::setBreak,
                label = { Text(stringResource(R.string.break_minutes)) },
                suffix = { Text(stringResource(R.string.minutes_suffix)) },
                singleLine = true,
                isError = state.breakValue == null || state.breakTooLong,
                supportingText = if (state.breakTooLong) {
                    { Text(stringResource(R.string.error_break_too_long)) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::setNote,
                label = { Text(stringResource(R.string.note_optional)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            PreviewCard(state, onUseCurrentRate = viewModel::useCurrentRate)
            Spacer(Modifier.height(24.dp))
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
    if (showStartPicker) {
        TimeDialog(
            title = stringResource(R.string.start_time),
            initial = state.start,
            onConfirm = { viewModel.setStart(it); showStartPicker = false },
            onDismiss = { showStartPicker = false },
        )
    }
    if (showEndPicker) {
        TimeDialog(
            title = stringResource(R.string.end_time),
            initial = state.end,
            onConfirm = { viewModel.setEnd(it); showEndPicker = false },
            onDismiss = { showEndPicker = false },
        )
    }
    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            icon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
            title = { Text(stringResource(R.string.delete_shift_title)) },
            text = { Text(stringResource(R.string.delete_shift_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::delete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreviewCard(state: EditShiftUiState, onUseCurrentRate: () -> Unit) {
    val locale = currentLocale()
    val onColor = MaterialTheme.colorScheme.onSecondaryContainer
    val pay = state.pay
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = onColor),
    ) {
        Column(Modifier.padding(20.dp)) {
            // Live "drum" counters: change a time or the break and the numbers roll.
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.earnings), style = MaterialTheme.typography.labelMedium, color = onColor.copy(alpha = 0.75f))
                    RollingMoney(
                        grosze = pay.earningsGrosze,
                        style = MaterialTheme.typography.headlineMediumEmphasized,
                        color = onColor,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.paid_hours), style = MaterialTheme.typography.labelMedium, color = onColor.copy(alpha = 0.75f))
                    RollingText(
                        text = Formatters.hoursMinutes(pay.paidMinutes),
                        value = pay.paidMinutes,
                        style = MaterialTheme.typography.titleLarge,
                        color = onColor,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val second = state.secondRate
            if (second == null) {
                Text(
                    stringResource(R.string.rate_used, Formatters.rate(state.rateGrosze, locale)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                // Per-rate split, e.g. "33,00 zł/h · 6:00" and "23:00–06:00 · 37,00 zł/h · 2:00".
                Text(
                    stringResource(
                        R.string.rate_line_base,
                        Formatters.rate(state.rateGrosze, locale),
                        Formatters.hoursMinutes(pay.baseRateMinutes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(
                        R.string.rate_line_second,
                        Formatters.time(second.from),
                        Formatters.time(second.to),
                        Formatters.rate(second.rateGrosze, locale),
                        Formatters.hoursMinutes(pay.secondRateMinutes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if ((state.breakValue ?: 0) > 0 && pay.secondRateMinutes > 0 && pay.baseRateMinutes > 0) {
                    Text(
                        stringResource(R.string.break_split_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = onColor.copy(alpha = 0.75f),
                    )
                }
            }
            AnimatedVisibility(state.rateOutdated) {
                Column {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.rate_changed_hint, state.selectedJob?.let { jobRatesLabel(it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onUseCurrentRate) { Text(stringResource(R.string.use_current_rate)) }
                }
            }
        }
    }
}

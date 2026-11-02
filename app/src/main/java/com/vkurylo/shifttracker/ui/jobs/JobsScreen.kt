package com.vkurylo.shifttracker.ui.jobs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.calc.MoneyParser
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.JobPalette
import com.vkurylo.shifttracker.domain.model.TimedRate
import com.vkurylo.shifttracker.ui.components.EmptyState
import com.vkurylo.shifttracker.ui.components.JobAvatar
import com.vkurylo.shifttracker.ui.components.PickerField
import com.vkurylo.shifttracker.ui.components.TimeDialog
import com.vkurylo.shifttracker.ui.components.onColor
import com.vkurylo.shifttracker.ui.util.Formatters
import com.vkurylo.shifttracker.ui.util.currentLocale
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(
    onBack: () -> Unit,
    viewModel: JobsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = currentLocale()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.jobs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            val label = stringResource(R.string.add_job)
            ExtendedFloatingActionButton(
                // M3 clears the FAB's inner text semantics; give TalkBack an explicit label.
                modifier = Modifier.semantics { contentDescription = label },
                onClick = {
                    val used = state.jobs.map { it.color }.toSet()
                    viewModel.startCreate(JobPalette.colors.firstOrNull { it !in used } ?: JobPalette.default)
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(label) },
            )
        },
    ) { padding ->
        if (!state.loading && state.jobs.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.WorkOutline,
                title = stringResource(R.string.no_jobs_title),
                message = stringResource(R.string.no_jobs_message),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
            ) {
                items(state.jobs, key = { it.id }) { job ->
                    ListItem(
                        modifier = Modifier.animateItem().clickable { viewModel.startEdit(job) },
                        leadingContent = { JobAvatar(job) },
                        headlineContent = { Text(job.name) },
                        supportingContent = { Text(jobRatesLabel(job)) },
                        trailingContent = {
                            IconButton(onClick = { viewModel.requestDelete(job) }) {
                                Icon(Icons.Filled.DeleteOutline, stringResource(R.string.delete_job_named, job.name))
                            }
                        },
                    )
                }
            }
        }
    }

    state.editing?.let { job ->
        JobEditorSheet(
            job = job,
            onDismiss = viewModel::dismissEditor,
            onSave = viewModel::save,
            onDelete = { viewModel.requestDelete(job) },
        )
    }

    state.pendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            icon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
            title = { Text(stringResource(R.string.delete_job_title, pending.job.name)) },
            text = {
                Text(
                    if (pending.shiftCount == 0) stringResource(R.string.delete_job_no_shifts)
                    else pluralStringResource(R.plurals.delete_job_with_shifts, pending.shiftCount, pending.shiftCount),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun JobEditorSheet(
    job: Job,
    onDismiss: () -> Unit,
    onSave: (Job) -> Unit,
    onDelete: () -> Unit,
) {
    val isNew = job.id == 0L
    var name by rememberSaveable(job.id) { mutableStateOf(job.name) }
    var rate by rememberSaveable(job.id) { mutableStateOf(if (isNew) "" else MoneyParser.toInput(job.hourlyRateGrosze)) }
    var color by rememberSaveable(job.id) { mutableIntStateOf(job.color) }
    var showErrors by rememberSaveable(job.id) { mutableStateOf(false) }
    var secondEnabled by rememberSaveable(job.id) { mutableStateOf(job.secondRate != null) }
    var secondRate by rememberSaveable(job.id) {
        mutableStateOf(job.secondRate?.let { MoneyParser.toInput(it.rateGrosze) } ?: "")
    }
    var secondFrom by rememberSaveable(job.id) {
        mutableIntStateOf((job.secondRate?.from ?: TimedRate.DEFAULT_NIGHT_FROM).toSecondOfDay() / 60)
    }
    var secondTo by rememberSaveable(job.id) {
        mutableIntStateOf((job.secondRate?.to ?: TimedRate.DEFAULT_NIGHT_TO).toSecondOfDay() / 60)
    }
    var pickingFrom by rememberSaveable { mutableStateOf(false) }
    var pickingTo by rememberSaveable { mutableStateOf(false) }

    val rateGrosze = MoneyParser.parseGrosze(rate)
    val secondRateGrosze = MoneyParser.parseGrosze(secondRate)
    val nameError = name.isBlank()
    val rateError = rateGrosze == null
    val secondRateError = secondEnabled && secondRateGrosze == null
    val windowError = secondEnabled && secondFrom == secondTo
    val fromTime = LocalTime.of(secondFrom / 60, secondFrom % 60)
    val toTime = LocalTime.of(secondTo / 60, secondTo % 60)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                JobAvatar(job.copy(name = name.ifBlank { "?" }, color = color), size = 48.dp)
                Spacer(Modifier.size(16.dp))
                Text(
                    stringResource(if (isNew) R.string.new_job else R.string.edit_job),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.job_name)) },
                singleLine = true,
                isError = showErrors && nameError,
                supportingText = if (showErrors && nameError) {
                    { Text(stringResource(R.string.error_name_required)) }
                } else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = rate,
                onValueChange = { rate = it },
                label = { Text(stringResource(if (secondEnabled) R.string.base_rate else R.string.hourly_rate)) },
                suffix = { Text("zł/h") },
                singleLine = true,
                isError = showErrors && rateError,
                supportingText = if (showErrors && rateError) {
                    { Text(stringResource(R.string.error_rate_invalid)) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            // Optional second rate inside a daily time window (e.g. night rate 23:00–06:00).
            ListItem(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .toggleable(value = secondEnabled, role = Role.Switch, onValueChange = { secondEnabled = it }),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text(stringResource(R.string.second_rate_toggle)) },
                supportingContent = { Text(stringResource(R.string.second_rate_toggle_summary)) },
                trailingContent = { Switch(checked = secondEnabled, onCheckedChange = null) },
            )
            AnimatedVisibility(secondEnabled) {
                Column {
                    OutlinedTextField(
                        value = secondRate,
                        onValueChange = { secondRate = it },
                        label = { Text(stringResource(R.string.second_rate)) },
                        suffix = { Text("zł/h") },
                        singleLine = true,
                        isError = showErrors && secondRateError,
                        supportingText = if (showErrors && secondRateError) {
                            { Text(stringResource(R.string.error_rate_invalid)) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PickerField(
                            label = stringResource(R.string.second_rate_from),
                            value = Formatters.time(fromTime),
                            icon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                            onClick = { pickingFrom = true },
                            modifier = Modifier.weight(1f),
                        )
                        PickerField(
                            label = stringResource(R.string.second_rate_to),
                            value = Formatters.time(toTime),
                            icon = { Icon(Icons.Filled.NightsStay, contentDescription = null) },
                            onClick = { pickingTo = true },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (windowError) {
                        Text(
                            stringResource(R.string.error_window_empty),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.job_color), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))
            ColorPalette(selected = color, onSelect = { color = it })
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isNew) {
                    OutlinedButton(onClick = onDelete, modifier = Modifier.height(52.dp)) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                Button(
                    onClick = {
                        if (nameError || rateError || secondRateError || windowError) {
                            showErrors = true
                        } else {
                            val second = if (secondEnabled && secondRateGrosze != null) {
                                TimedRate(secondRateGrosze, fromTime, toTime)
                            } else null
                            onSave(job.copy(name = name, hourlyRateGrosze = rateGrosze, color = color, secondRate = second))
                        }
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }

    if (pickingFrom) {
        TimeDialog(
            title = stringResource(R.string.second_rate_from),
            initial = fromTime,
            onConfirm = { secondFrom = it.hour * 60 + it.minute; pickingFrom = false },
            onDismiss = { pickingFrom = false },
        )
    }
    if (pickingTo) {
        TimeDialog(
            title = stringResource(R.string.second_rate_to),
            initial = toTime,
            onConfirm = { secondTo = it.hour * 60 + it.minute; pickingTo = false },
            onDismiss = { pickingTo = false },
        )
    }
}

/** "33,00 zł/h · 23:00–06:00: 37,00 zł/h" */
@Composable
fun jobRatesLabel(job: Job): String {
    val locale = currentLocale()
    val base = Formatters.rate(job.hourlyRateGrosze, locale)
    val second = job.secondRate ?: return base
    return base + " · " + stringResource(
        R.string.second_rate_summary,
        Formatters.time(second.from),
        Formatters.time(second.to),
        Formatters.rate(second.rateGrosze, locale),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPalette(selected: Int, onSelect: (Int) -> Unit) {
    val colorNames = androidx.compose.ui.res.stringArrayResource(R.array.palette_color_names)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        JobPalette.colors.forEachIndexed { index, value ->
            val color = Color(value)
            val isSelected = value == selected
            val description = colorNames.getOrElse(index) { "" }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(color)
                    .then(
                        if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier,
                    )
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(value) })
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) Icon(Icons.Filled.Check, contentDescription = null, tint = color.onColor())
            }
        }
    }
}

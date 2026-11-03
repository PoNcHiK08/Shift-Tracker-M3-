package com.vkurylo.shifttracker.ui.importics

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import com.vkurylo.shifttracker.ui.components.EmptyState
import com.vkurylo.shifttracker.ui.components.ExpressiveFilterPill
import com.vkurylo.shifttracker.ui.components.JobColorDot
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.SectionHeader
import com.vkurylo.shifttracker.ui.components.composeColor
import com.vkurylo.shifttracker.ui.util.Formatters
import com.vkurylo.shifttracker.ui.util.currentLocale
import com.vkurylo.shifttracker.ui.util.hoursLabel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImportIcsScreen(
    onClose: () -> Unit,
    onManageJobs: () -> Unit,
    viewModel: ImportIcsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = currentLocale()
    val snackbar = LocalSnackbarController.current
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.done.collect { count ->
            snackbar.show(context.resources.getQuantityString(R.plurals.imported_shifts, count, count))
            onClose()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_schedule)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close)) } },
            )
        },
        bottomBar = {
            if (!state.loading && !state.error && state.candidates.isNotEmpty() && state.jobs.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = viewModel::import,
                        enabled = state.canImport,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp)
                            .height(52.dp),
                    ) {
                        Text(pluralStringResource(R.plurals.import_n_shifts, state.selected.size, state.selected.size))
                    }
                }
            }
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error -> EmptyState(
                icon = Icons.Filled.EventBusy,
                title = stringResource(R.string.import_error_title),
                message = stringResource(R.string.import_error_message),
                modifier = Modifier.padding(padding),
            )
            state.candidates.isEmpty() -> EmptyState(
                icon = Icons.Filled.EventBusy,
                title = stringResource(R.string.import_empty_title),
                message = stringResource(R.string.import_empty_message),
                modifier = Modifier.padding(padding),
            )
            state.jobs.isEmpty() -> EmptyState(
                icon = Icons.Filled.WorkOutline,
                title = stringResource(R.string.no_jobs_title),
                message = stringResource(R.string.import_needs_job),
                modifier = Modifier.padding(padding),
                action = { Button(onClick = onManageJobs) { Text(stringResource(R.string.add_job)) } },
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            ) {
                item(key = "info") {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                pluralStringResource(R.plurals.import_found_events, state.candidates.size, state.candidates.size),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (state.duplicateCount > 0) {
                                Text(pluralStringResource(R.plurals.import_duplicates, state.duplicateCount, state.duplicateCount))
                            }
                            if (state.skippedCount > 0) {
                                Text(pluralStringResource(R.plurals.import_skipped, state.skippedCount, state.skippedCount))
                            }
                        }
                    }
                }
                item(key = "job-header") { SectionHeader(stringResource(R.string.import_assign_job)) }
                item(key = "jobs") {
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.jobs.forEach { job ->
                            ExpressiveFilterPill(
                                selected = job.id == state.jobId,
                                onClick = { viewModel.selectJob(job) },
                                label = job.name,
                                leadingIcon = { JobColorDot(job.composeColor(), size = 12.dp) },
                            )
                        }
                    }
                }
                item(key = "events-header") {
                    Row(
                        Modifier.fillMaxWidth().padding(end = 8.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        SectionHeader(stringResource(R.string.import_events), Modifier.weight(1f))
                        TextButton(onClick = viewModel::selectAllNew) { Text(stringResource(R.string.select_new)) }
                        TextButton(onClick = viewModel::selectNone) { Text(stringResource(R.string.select_none)) }
                    }
                }
                itemsIndexed(state.candidates, key = { index, _ -> index }) { index, candidate ->
                    val event = candidate.event
                    val checked = index in state.selected
                    val minutes = ShiftCalculator.durationMinutes(event.start.toLocalTime(), event.end.toLocalTime())
                    ListItem(
                        modifier = Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = { viewModel.toggle(index) }),
                        leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                        headlineContent = {
                            Text(
                                "${Formatters.shortDate(event.start.toLocalDate(), locale)} · " +
                                    "${Formatters.time(event.start.toLocalTime())}–${Formatters.time(event.end.toLocalTime())}",
                            )
                        },
                        supportingContent = {
                            Text(listOfNotNull(event.summary, hoursLabel(minutes)).joinToString(" · "), maxLines = 2)
                        },
                        trailingContent = if (candidate.isDuplicate) {
                            { SuggestionChip(onClick = { viewModel.toggle(index) }, label = { Text(stringResource(R.string.duplicate)) }) }
                        } else null,
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

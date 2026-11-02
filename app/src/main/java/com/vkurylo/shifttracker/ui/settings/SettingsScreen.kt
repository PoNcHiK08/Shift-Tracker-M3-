package com.vkurylo.shifttracker.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vkurylo.shifttracker.BuildConfig
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.model.AppLanguage
import com.vkurylo.shifttracker.domain.model.ThemeMode
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.SectionHeader
import com.vkurylo.shifttracker.ui.theme.supportsDynamicColor
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onManageJobs: () -> Unit,
    onImportIcs: (Uri) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarController.current
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbar.show(context.getString(message.resId, *message.args.toTypedArray()))
        }
    }

    val pickIcs = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportIcs)
    }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::export)
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::requestImport)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                LargeTopAppBar(
                    title = { Text(stringResource(R.string.settings_title)) },
                    scrollBehavior = scrollBehavior,
                )
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            item { SectionHeader(stringResource(R.string.section_work)) }
            item {
                ListItem(
                    modifier = Modifier.clickable(onClick = onManageJobs),
                    leadingContent = { Icon(Icons.Filled.WorkOutline, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.jobs_and_rates)) },
                    supportingContent = { Text(pluralStringResource(R.plurals.job_count, state.jobCount, state.jobCount)) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable {
                        pickIcs.launch(arrayOf("text/calendar", "text/x-vcalendar", "application/ics", "application/octet-stream", "text/plain"))
                    },
                    leadingContent = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.import_schedule)) },
                    supportingContent = { Text(stringResource(R.string.import_schedule_summary)) },
                )
            }

            item { SectionHeader(stringResource(R.string.section_appearance)) }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Filled.DarkMode, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.theme)) },
                    supportingContent = {
                        val options = listOf(
                            ThemeMode.SYSTEM to R.string.theme_system,
                            ThemeMode.LIGHT to R.string.theme_light,
                            ThemeMode.DARK to R.string.theme_dark,
                        )
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            options.forEachIndexed { index, (mode, label) ->
                                SegmentedButton(
                                    selected = state.preferences.themeMode == mode,
                                    onClick = { viewModel.setThemeMode(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                                ) { Text(stringResource(label), maxLines = 1) }
                            }
                        }
                    },
                )
            }
            item {
                val supported = supportsDynamicColor()
                val checked = supported && state.preferences.useDynamicColor
                ListItem(
                    modifier = Modifier.toggleable(
                        value = checked,
                        enabled = supported,
                        role = Role.Switch,
                        onValueChange = viewModel::setDynamicColor,
                    ),
                    leadingContent = { Icon(Icons.Filled.Palette, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.dynamic_color)) },
                    supportingContent = {
                        Text(stringResource(if (supported) R.string.dynamic_color_summary else R.string.dynamic_color_unsupported))
                    },
                    trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = supported) },
                )
            }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Language, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.language)) },
                    supportingContent = {
                        val options = listOf(
                            AppLanguage.UKRAINIAN to R.string.language_ukrainian,
                            AppLanguage.ENGLISH to R.string.language_english,
                            AppLanguage.SYSTEM to R.string.language_system,
                        )
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            options.forEachIndexed { index, (language, label) ->
                                SegmentedButton(
                                    selected = state.language == language,
                                    onClick = { viewModel.setLanguage(language) },
                                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                                ) { Text(stringResource(label), maxLines = 1) }
                            }
                        }
                    },
                )
            }

            item { SectionHeader(stringResource(R.string.section_data)) }
            item {
                ListItem(
                    modifier = Modifier.clickable(enabled = !state.busy) {
                        exportBackup.launch("shift-tracker-${LocalDate.now()}.json")
                    },
                    leadingContent = { Icon(Icons.Filled.FileUpload, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.export_data)) },
                    supportingContent = { Text(stringResource(R.string.export_data_summary)) },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable(enabled = !state.busy) {
                        importBackup.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                    },
                    leadingContent = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.import_data)) },
                    supportingContent = { Text(stringResource(R.string.import_data_summary)) },
                )
            }

            item { SectionHeader(stringResource(R.string.section_about)) }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.app_name)) },
                    supportingContent = { Text(stringResource(R.string.version, BuildConfig.VERSION_NAME)) },
                )
            }
        }
    }

    if (state.pendingImport != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            icon = { Icon(Icons.Filled.Restore, contentDescription = null) },
            title = { Text(stringResource(R.string.import_backup_title)) },
            text = { Text(stringResource(R.string.import_backup_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmImport) {
                    Text(stringResource(R.string.replace_data), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelImport) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

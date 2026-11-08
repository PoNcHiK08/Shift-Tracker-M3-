package com.vkurylo.shifttracker.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vkurylo.shifttracker.BuildConfig
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.model.AppLanguage
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.ThemeMode
import com.vkurylo.shifttracker.ui.components.ChoiceButtonGroup
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.SectionHeader
import com.vkurylo.shifttracker.ui.components.SegmentedItem
import com.vkurylo.shifttracker.ui.components.SegmentedListDefaults
import com.vkurylo.shifttracker.ui.theme.supportsDynamicColor
import java.time.LocalDate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onManageJobs: () -> Unit,
    onImportIcs: (Uri) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val cloud by viewModel.cloud.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
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
            // Android 16 style: every section is one segmented group of rows.
            item { SectionHeader(stringResource(R.string.section_work)) }
            item {
                SettingsRow(index = 0, count = 3, onClick = onManageJobs) {
                    ListItem(
                        modifier = it,
                        colors = TransparentListItem,
                        leadingContent = { Icon(Icons.Filled.WorkOutline, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.jobs_and_rates)) },
                        supportingContent = { Text(pluralStringResource(R.plurals.job_count, state.jobCount, state.jobCount)) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    )
                }
            }
            item {
                SettingsRow(
                    index = 1,
                    count = 3,
                    onClick = {
                        pickIcs.launch(arrayOf("text/calendar", "text/x-vcalendar", "application/ics", "application/octet-stream", "text/plain"))
                    },
                ) {
                    ListItem(
                        modifier = it,
                        colors = TransparentListItem,
                        leadingContent = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.import_schedule)) },
                        supportingContent = { Text(stringResource(R.string.import_schedule_summary)) },
                    )
                }
            }
            item {
                // Gross / Net for every earnings figure (calendar, day panel, summary).
                SettingsRow(index = 2, count = 3) {
                    ListItem(
                        modifier = it,
                        colors = TransparentListItem,
                        leadingContent = { Icon(Icons.Filled.Payments, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.earnings_mode)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.earnings_mode_summary))
                                ChoiceButtonGroup(
                                    options = EarningsMode.entries,
                                    selected = state.preferences.earningsMode,
                                    onSelect = viewModel::setEarningsMode,
                                    label = { mode ->
                                        stringResource(if (mode == EarningsMode.NET) R.string.earnings_net else R.string.earnings_gross)
                                    },
                                    height = 40.dp,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        },
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.section_appearance)) }
            item {
                SettingsRow(index = 0, count = 3) {
                    ListItem(
                        modifier = it,
                        colors = TransparentListItem,
                        leadingContent = { Icon(Icons.Filled.DarkMode, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.theme)) },
                        supportingContent = {
                            ChoiceButtonGroup(
                                options = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
                                selected = state.preferences.themeMode,
                                onSelect = viewModel::setThemeMode,
                                label = { mode ->
                                    stringResource(
                                        when (mode) {
                                            ThemeMode.SYSTEM -> R.string.theme_system
                                            ThemeMode.LIGHT -> R.string.theme_light
                                            ThemeMode.DARK -> R.string.theme_dark
                                        },
                                    )
                                },
                                height = 40.dp,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        },
                    )
                }
            }
            item {
                val supported = supportsDynamicColor()
                val checked = supported && state.preferences.useDynamicColor
                SettingsRow(
                    index = 1,
                    count = 3,
                    onClick = { viewModel.setDynamicColor(!checked) },
                    checked = checked,
                    enabled = supported,
                ) {
                    ListItem(
                        modifier = it,
                        colors = TransparentListItem,
                        leadingContent = { Icon(Icons.Filled.Palette, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.dynamic_color)) },
                        supportingContent = {
                            Text(stringResource(if (supported) R.string.dynamic_color_summary else R.string.dynamic_color_unsupported))
                        },
                        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = supported) },
                    )
                }
            }
            item {
                SettingsRow(index = 2, count = 3) {
                    ListItem(
                        modifier = it,
                        colors = TransparentListItem,
                        leadingContent = { Icon(Icons.Filled.Language, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.language)) },
                        supportingContent = {
                            ChoiceButtonGroup(
                                options = listOf(AppLanguage.UKRAINIAN, AppLanguage.ENGLISH, AppLanguage.SYSTEM),
                                selected = state.language,
                                onSelect = viewModel::setLanguage,
                                label = { language ->
                                    stringResource(
                                        when (language) {
                                            AppLanguage.UKRAINIAN -> R.string.language_ukrainian
                                            AppLanguage.ENGLISH -> R.string.language_english
                                            AppLanguage.SYSTEM -> R.string.language_system
                                        },
                                    )
                                },
                                height = 40.dp,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        },
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.section_cloud)) }
            val account = cloud.account
            when {
                !cloud.available -> item {
                    SettingsRow(index = 0, count = 1, enabled = false) {
                        ListItem(
                            modifier = it,
                            colors = TransparentListItem,
                            leadingContent = { Icon(Icons.Filled.CloudOff, contentDescription = null) },
                            headlineContent = { Text(stringResource(R.string.cloud_sync)) },
                            supportingContent = { Text(stringResource(R.string.cloud_unavailable)) },
                        )
                    }
                }
                account == null -> item {
                    SettingsRow(
                        index = 0,
                        count = 1,
                        enabled = !cloud.signingIn,
                        // The account sheet is shown over this Activity; signing in is cancelled with the screen.
                        onClick = { scope.launch { viewModel.signIn(context) } },
                    ) {
                        ListItem(
                            modifier = it,
                            colors = TransparentListItem,
                            leadingContent = { Icon(Icons.Filled.Cloud, contentDescription = null) },
                            headlineContent = { Text(stringResource(R.string.cloud_sign_in)) },
                            supportingContent = {
                                Text(stringResource(if (cloud.signingIn) R.string.cloud_signing_in else R.string.cloud_sign_in_summary))
                            },
                        )
                    }
                }
                else -> {
                    item {
                        SettingsRow(
                            index = 0,
                            count = 2,
                            onClick = viewModel::retryCloudAccess.takeIf { cloud.access != CloudAccess.OK && cloud.access != CloudAccess.CHECKING },
                        ) {
                            ListItem(
                                modifier = it,
                                colors = TransparentListItem,
                                leadingContent = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                                headlineContent = { Text(account.email ?: stringResource(R.string.cloud_google_account)) },
                                supportingContent = {
                                    Text(
                                        when (cloud.access) {
                                            CloudAccess.OK -> stringResource(R.string.cloud_access_ok)
                                            CloudAccess.DENIED -> stringResource(R.string.cloud_access_denied)
                                            CloudAccess.OFFLINE -> stringResource(R.string.cloud_access_offline)
                                            CloudAccess.ERROR -> stringResource(R.string.cloud_access_error, cloud.accessError.orEmpty())
                                            CloudAccess.CHECKING, null -> stringResource(R.string.cloud_access_checking)
                                        },
                                        color = if (cloud.access == CloudAccess.DENIED || cloud.access == CloudAccess.ERROR) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            Color.Unspecified
                                        },
                                    )
                                },
                            )
                        }
                    }
                    item {
                        SettingsRow(index = 1, count = 2, onClick = viewModel::signOut) {
                            ListItem(
                                modifier = it,
                                colors = TransparentListItem,
                                leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                                headlineContent = { Text(stringResource(R.string.cloud_sign_out)) },
                                supportingContent = { Text(stringResource(R.string.cloud_sign_out_summary)) },
                            )
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.section_data)) }
            item {
                SettingsRow(
                    index = 0,
                    count = 2,
                    enabled = !state.busy,
                    onClick = { exportBackup.launch("shift-tracker-${LocalDate.now()}.json") },
                ) {
                    ListItem(
                        modifier = it,
                        colors = TransparentListItem,
                        leadingContent = { Icon(Icons.Filled.FileUpload, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.export_data)) },
                        supportingContent = { Text(stringResource(R.string.export_data_summary)) },
                    )
                }
            }
            item {
                SettingsRow(
                    index = 1,
                    count = 2,
                    enabled = !state.busy,
                    onClick = { importBackup.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                ) {
                    ListItem(
                        modifier = it,
                        colors = TransparentListItem,
                        leadingContent = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.import_data)) },
                        supportingContent = { Text(stringResource(R.string.import_data_summary)) },
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.section_about)) }
            item {
                SettingsRow(index = 0, count = 1) {
                    ListItem(
                        modifier = it,
                        colors = TransparentListItem,
                        leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.app_name)) },
                        supportingContent = { Text(stringResource(R.string.version, BuildConfig.VERSION_NAME)) },
                    )
                }
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

/** A settings row as one segment of its section's group. */
@Composable
private fun SettingsRow(
    index: Int,
    count: Int,
    onClick: (() -> Unit)? = null,
    checked: Boolean? = null,
    enabled: Boolean = true,
    content: @Composable (interaction: Modifier) -> Unit,
) {
    SegmentedItem(
        index = index,
        count = count,
        onClick = onClick,
        checked = checked,
        enabled = enabled,
        modifier = Modifier.padding(SegmentedListDefaults.ItemPadding),
        content = content,
    )
}

/** The segment draws the row's background, so the list item itself is transparent. */
private val TransparentListItem: ListItemColors
    @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

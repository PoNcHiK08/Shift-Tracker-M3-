package com.vkurylo.shifttracker.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ClipData
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.vkurylo.shifttracker.data.sync.FileSafetyBackup
import com.vkurylo.shifttracker.data.sync.SafetyBackup
import com.vkurylo.shifttracker.data.sync.SafetyBackupFile
import com.vkurylo.shifttracker.data.sync.SignInChoice
import com.vkurylo.shifttracker.data.sync.SyncProblem
import com.vkurylo.shifttracker.domain.model.AppLanguage
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.ThemeMode
import com.vkurylo.shifttracker.ui.components.ChoiceButtonGroup
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.SectionHeader
import com.vkurylo.shifttracker.ui.components.SegmentedItem
import com.vkurylo.shifttracker.ui.components.SegmentedListDefaults
import com.vkurylo.shifttracker.ui.theme.supportsDynamicColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onManageJobs: () -> Unit,
    onImportIcs: (Uri) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val cloud by viewModel.cloud.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarController.current
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbar.show(message.text(context))
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.safetyBackupShares.collect { uri ->
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/json")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The read grant travels with the clip data through the share sheet to the app picked.
            send.clipData = ClipData.newRawUri(null, uri)
            context.startActivity(Intent.createChooser(send, context.getString(R.string.safety_backup_share)))
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
    val safetyBackups by viewModel.safetyBackupFiles.collectAsStateWithLifecycle()
    var showingSafetyBackups by rememberSaveable { mutableStateOf(false) }
    // The safety backup being saved, while the system's file picker is open.
    var savingSafetyBackup by rememberSaveable { mutableStateOf<String?>(null) }
    val saveSafetyBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val name = savingSafetyBackup
        savingSafetyBackup = null
        if (uri != null && name != null) viewModel.exportSafetyBackup(name, uri)
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
                        // Google's account sheet is shown over this Activity.
                        onClick = { viewModel.signIn(context.findActivity() ?: context) },
                    ) {
                        ListItem(
                            modifier = it,
                            colors = TransparentListItem,
                            leadingContent = { Icon(Icons.Filled.Cloud, contentDescription = null) },
                            headlineContent = { Text(stringResource(R.string.cloud_sign_in)) },
                            supportingContent = {
                                val error = cloud.signInError
                                when {
                                    cloud.signingIn -> Text(stringResource(R.string.cloud_signing_in))
                                    error != null -> Text(error.text(context), color = MaterialTheme.colorScheme.error)
                                    else -> Text(stringResource(R.string.cloud_sign_in_summary))
                                }
                            },
                        )
                    }
                }
                else -> {
                    val sync = cloud.sync
                    val deleting = cloud.cloudDeletion == CloudDeletion.DELETING
                    val rows = if (sync.enabled) 5 else 4
                    item {
                        // Sync off: whether the cloud lets this account in (tap to check again after an error).
                        // Sync on: how sync stands (tap after the cloud data was deleted to decide what to do).
                        val onClick = when {
                            sync.enabled && sync.cloudReset -> viewModel::reviewCloudReset
                            !sync.enabled && cloud.access != CloudAccess.OK && cloud.access != CloudAccess.CHECKING -> viewModel::retryCloudAccess
                            else -> null
                        }
                        SettingsRow(index = 0, count = rows, onClick = onClick) {
                            ListItem(
                                modifier = it,
                                colors = TransparentListItem,
                                leadingContent = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                                headlineContent = { Text(account.email ?: stringResource(R.string.cloud_google_account)) },
                                supportingContent = {
                                    if (sync.enabled) {
                                        val problem = sync.last?.problem
                                        Column {
                                            cloud.syncLines.forEachIndexed { i, line ->
                                                Text(
                                                    line.text(context),
                                                    color = if (i == 0 && !sync.running && (sync.cloudReset || (problem != null && problem != SyncProblem.OFFLINE))) {
                                                        MaterialTheme.colorScheme.error
                                                    } else {
                                                        Color.Unspecified
                                                    },
                                                )
                                            }
                                        }
                                    } else {
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
                                    }
                                },
                            )
                        }
                    }
                    item {
                        // Off until turned on, also after signing in.
                        SettingsRow(
                            index = 1,
                            count = rows,
                            onClick = { viewModel.setSyncEnabled(!sync.enabled) },
                            checked = sync.enabled,
                            enabled = !cloud.switchingOn && !deleting,
                        ) {
                            ListItem(
                                modifier = it,
                                colors = TransparentListItem,
                                leadingContent = { Icon(Icons.Filled.Sync, contentDescription = null) },
                                headlineContent = { Text(stringResource(R.string.sync_switch)) },
                                supportingContent = {
                                    Text(stringResource(if (cloud.switchingOn) R.string.sync_turning_on else R.string.sync_switch_summary))
                                },
                                trailingContent = { Switch(checked = sync.enabled, onCheckedChange = null, enabled = !cloud.switchingOn && !deleting) },
                            )
                        }
                    }
                    if (sync.enabled) {
                        item {
                            SettingsRow(index = 2, count = rows, onClick = viewModel::syncNow, enabled = !sync.running && !cloud.switchingOn) {
                                ListItem(
                                    modifier = it,
                                    colors = TransparentListItem,
                                    leadingContent = { Icon(Icons.Filled.CloudSync, contentDescription = null) },
                                    headlineContent = { Text(stringResource(R.string.sync_now)) },
                                    supportingContent = {
                                        Text(stringResource(if (sync.running) R.string.sync_running else R.string.sync_now_summary))
                                    },
                                )
                            }
                        }
                    }
                    item {
                        // The account's data in the cloud, deleted after a confirmation; this phone keeps its own.
                        SettingsRow(
                            index = rows - 2,
                            count = rows,
                            onClick = viewModel::requestDeleteCloudData,
                            enabled = !deleting && !cloud.switchingOn && !sync.running,
                        ) {
                            ListItem(
                                modifier = it,
                                colors = TransparentListItem,
                                leadingContent = { Icon(Icons.Filled.DeleteForever, contentDescription = null) },
                                headlineContent = { Text(stringResource(R.string.cloud_delete)) },
                                supportingContent = {
                                    Text(stringResource(if (deleting) R.string.cloud_deleting else R.string.cloud_delete_summary))
                                },
                            )
                        }
                    }
                    item {
                        SettingsRow(index = rows - 1, count = rows, onClick = viewModel::signOut, enabled = !deleting) {
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
                    count = 3,
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
                    count = 3,
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
            item {
                // Copies the app saved itself before replacing this phone's data; each can be saved to a file.
                SettingsRow(
                    index = 2,
                    count = 3,
                    enabled = !state.busy && safetyBackups.isNotEmpty(),
                    onClick = { showingSafetyBackups = true },
                ) {
                    ListItem(
                        modifier = it,
                        colors = TransparentListItem,
                        leadingContent = { Icon(Icons.Filled.SettingsBackupRestore, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.safety_backups)) },
                        supportingContent = {
                            Text(
                                if (safetyBackups.isEmpty()) stringResource(R.string.safety_backups_none)
                                else stringResource(R.string.safety_backups_summary, safetyBackups.size),
                            )
                        },
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
        // With sync on, the import replaces the data on every phone of the account, not only here.
        val everywhere = cloud.sync.enabled
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            icon = { Icon(Icons.Filled.Restore, contentDescription = null) },
            title = { Text(stringResource(if (everywhere) R.string.import_backup_title_everywhere else R.string.import_backup_title)) },
            text = {
                Text(
                    stringResource(
                        when {
                            everywhere -> R.string.import_backup_message_synced
                            cloud.sync.linked -> R.string.import_backup_message_linked
                            else -> R.string.import_backup_message
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmImport) {
                    Text(
                        stringResource(if (everywhere) R.string.replace_data_everywhere else R.string.replace_data),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelImport) { Text(stringResource(R.string.cancel)) } },
        )
    }

    cloud.decision?.let { decision -> SyncDecisionDialog(decision, viewModel) }

    if (showingSafetyBackups) {
        SafetyBackupsDialog(
            backups = safetyBackups,
            onSave = { backup ->
                showingSafetyBackups = false
                savingSafetyBackup = backup.name
                saveSafetyBackup.launch("shift-tracker-safety-${backup.name}")
            },
            onShare = { backup ->
                showingSafetyBackups = false
                viewModel.shareSafetyBackup(backup.name)
            },
            onDismiss = { showingSafetyBackups = false },
        )
    }

    if (cloud.cloudDeletion == CloudDeletion.CONFIRMING) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDeleteCloudData,
            icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null) },
            title = { Text(stringResource(R.string.cloud_delete_title)) },
            text = {
                Text(stringResource(R.string.cloud_delete_message, cloud.account?.email ?: stringResource(R.string.cloud_google_account)))
            },
            confirmButton = {
                TextButton(onClick = viewModel::deleteCloudData) {
                    Text(stringResource(R.string.cloud_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelDeleteCloudData) { Text(stringResource(R.string.cancel)) } },
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

/** The Activity behind a Compose context (Credential Manager shows its sheet over an Activity). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val SAFETY_BACKUP_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")

/** The safety backups, newest first; each can be saved to a file the user picks, or shared with an app. */
@Composable
private fun SafetyBackupsDialog(
    backups: List<SafetyBackupFile>,
    onSave: (SafetyBackupFile) -> Unit,
    onShare: (SafetyBackupFile) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.SettingsBackupRestore, contentDescription = null) },
        title = { Text(stringResource(R.string.safety_backups)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.safety_backups_message, FileSafetyBackup.KEEP))
                backups.forEach { backup ->
                    ListItem(
                        colors = TransparentListItem,
                        headlineContent = {
                            Text(SAFETY_BACKUP_TIME.format(Instant.ofEpochMilli(backup.createdAt).atZone(ZoneId.systemDefault())))
                        },
                        supportingContent = {
                            val reason = when (backup.reason) {
                                SafetyBackup.Reason.BEFORE_IMPORT -> stringResource(R.string.safety_backup_reason_import)
                                SafetyBackup.Reason.BEFORE_TAKING_CLOUD_DATA -> stringResource(R.string.safety_backup_reason_cloud)
                                else -> backup.reason
                            }
                            val counts = backup.counts?.let { stringResource(R.string.safety_backup_counts, it.jobs, it.shifts, it.bonuses) }
                                ?: stringResource(R.string.safety_backup_unreadable)
                            Text("$reason\n$counts")
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { onSave(backup) }) {
                                    Icon(Icons.Filled.SaveAlt, contentDescription = stringResource(R.string.safety_backup_save))
                                }
                                IconButton(onClick = { onShare(backup) }) {
                                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.safety_backup_share))
                                }
                            }
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** The question to answer before sync can be turned on (or go on). Nothing syncs while it is open. */
@Composable
private fun SyncDecisionDialog(decision: SyncDecision, viewModel: SettingsViewModel) {
    when (decision) {
        is SyncDecision.Merge -> AlertDialog(
            onDismissRequest = viewModel::dismissDecision,
            icon = { Icon(Icons.Filled.CloudSync, contentDescription = null) },
            title = { Text(stringResource(R.string.sync_merge_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.sync_merge_message,
                        decision.phone.jobs, decision.phone.shifts, decision.phone.bonuses,
                        decision.cloud.jobs, decision.cloud.shifts, decision.cloud.bonuses,
                        decision.lookAlikes,
                    ),
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { viewModel.answer(SignInChoice.MERGE) }) { Text(stringResource(R.string.sync_merge)) }
                    TextButton(onClick = { viewModel.answer(SignInChoice.USE_CLOUD_ONLY) }) {
                        Text(stringResource(R.string.sync_use_account_data), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = viewModel::dismissDecision) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
        is SyncDecision.AccountSwitch -> AlertDialog(
            onDismissRequest = viewModel::dismissDecision,
            icon = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
            title = { Text(stringResource(R.string.sync_account_switch_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.sync_account_switch_message,
                        decision.previousEmail ?: stringResource(R.string.sync_other_account),
                        decision.currentEmail ?: stringResource(R.string.cloud_google_account),
                        decision.phone.jobs, decision.phone.shifts, decision.phone.bonuses,
                        decision.cloud.jobs, decision.cloud.shifts, decision.cloud.bonuses,
                    ),
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { viewModel.answer(SignInChoice.MERGE) }) { Text(stringResource(R.string.sync_add_phone_data)) }
                    TextButton(onClick = { viewModel.answer(SignInChoice.USE_CLOUD_ONLY) }) {
                        Text(stringResource(R.string.sync_replace_with_account), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = viewModel::dismissDecision) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
        SyncDecision.CloudReset -> AlertDialog(
            onDismissRequest = viewModel::dismissDecision,
            icon = { Icon(Icons.Filled.CloudOff, contentDescription = null) },
            title = { Text(stringResource(R.string.sync_reset_title)) },
            text = { Text(stringResource(R.string.sync_reset_message)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { viewModel.answer(SignInChoice.UPLOAD) }) { Text(stringResource(R.string.sync_upload_again)) }
                    TextButton(onClick = viewModel::turnSyncOff) { Text(stringResource(R.string.sync_turn_off)) }
                    TextButton(onClick = viewModel::dismissDecision) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
}

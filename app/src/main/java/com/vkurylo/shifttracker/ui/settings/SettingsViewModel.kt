package com.vkurylo.shifttracker.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.data.DocumentIo
import com.vkurylo.shifttracker.domain.model.AppLanguage
import com.vkurylo.shifttracker.domain.model.ThemeMode
import com.vkurylo.shifttracker.domain.model.UserPreferences
import com.vkurylo.shifttracker.domain.repository.BackupRepository
import com.vkurylo.shifttracker.domain.repository.InvalidBackupException
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.LanguageRepository
import com.vkurylo.shifttracker.domain.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val preferences: UserPreferences = UserPreferences(),
    val language: AppLanguage = AppLanguage.SYSTEM,
    val jobCount: Int = 0,
    val busy: Boolean = false,
    /** Backup picked by the user, waiting for the "replace all data?" confirmation. */
    val pendingImport: Uri? = null,
)

/** A message for the snackbar: string resource + format args. */
data class UiMessage(val resId: Int, val args: List<Any> = emptyList())

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val languageRepository: LanguageRepository,
    private val backupRepository: BackupRepository,
    private val documentIo: DocumentIo,
    jobRepository: JobRepository,
) : ViewModel() {

    private val language = MutableStateFlow(languageRepository.current())
    private val busy = MutableStateFlow(false)
    private val pendingImport = MutableStateFlow<Uri?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        preferencesRepository.preferences,
        language,
        jobRepository.observeJobs(),
        busy,
        pendingImport,
    ) { prefs, lang, jobs, isBusy, pending ->
        SettingsUiState(prefs, lang, jobs.size, isBusy, pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(language = languageRepository.current()))

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { preferencesRepository.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setDynamicColor(enabled) }

    fun setLanguage(value: AppLanguage) {
        language.update { value }
        languageRepository.set(value) // recreates the activity with the new locale
    }

    fun export(uri: Uri) = runBusy {
        val json = backupRepository.exportJson()
        documentIo.writeText(uri, json)
        _messages.send(UiMessage(R.string.export_done))
    }

    fun requestImport(uri: Uri) = pendingImport.update { uri }
    fun cancelImport() = pendingImport.update { null }

    fun confirmImport() {
        val uri = pendingImport.value ?: return
        pendingImport.update { null }
        runBusy {
            val counts = backupRepository.importJson(documentIo.readText(uri))
            _messages.send(UiMessage(R.string.import_backup_done, listOf(counts.jobs, counts.shifts, counts.bonuses)))
        }
    }

    private fun runBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.update { true }
            try {
                block()
            } catch (e: InvalidBackupException) {
                // The file was rejected before anything was written; say why, and that data is intact.
                val message = when (e.reason) {
                    InvalidBackupException.Reason.NEWER_VERSION -> R.string.error_backup_newer_version
                    InvalidBackupException.Reason.INVALID -> R.string.error_backup_invalid
                }
                _messages.send(UiMessage(message))
            } catch (e: Exception) {
                // Reading/writing the document failed (or the import transaction was rolled back).
                _messages.send(UiMessage(R.string.error_file_operation))
            } finally {
                busy.update { false }
            }
        }
    }
}

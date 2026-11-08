package com.vkurylo.shifttracker.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.data.DocumentIo
import com.vkurylo.shifttracker.data.sync.CloudAccount
import com.vkurylo.shifttracker.data.sync.RemoteStore
import com.vkurylo.shifttracker.data.sync.firebase.CloudAuth
import com.vkurylo.shifttracker.data.sync.firebase.CloudProblem
import com.vkurylo.shifttracker.data.sync.firebase.SignInException
import com.vkurylo.shifttracker.data.sync.firebase.cloudProblem
import com.vkurylo.shifttracker.domain.model.AppLanguage
import com.vkurylo.shifttracker.domain.model.EarningsMode
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
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

/** Whether the signed-in account can reach its data in the cloud (firestore.rules let it in). */
enum class CloudAccess { CHECKING, OK, DENIED, OFFLINE, ERROR }

data class CloudUiState(
    /** Cloud sync is set up in this build (it was made with google-services.json). */
    val available: Boolean = false,
    val account: CloudAccount? = null,
    /** Null while signed out. */
    val access: CloudAccess? = null,
    /** What went wrong, for [CloudAccess.ERROR]. */
    val accessError: String? = null,
    val signingIn: Boolean = false,
)

/** A message for the snackbar: string resource + format args. */
data class UiMessage(val resId: Int, val args: List<Any> = emptyList())

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val languageRepository: LanguageRepository,
    private val backupRepository: BackupRepository,
    private val documentIo: DocumentIo,
    private val cloudAuth: CloudAuth,
    private val remoteStore: RemoteStore,
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

    private val cloudAccess = MutableStateFlow<Pair<CloudAccess, String?>?>(null)
    private val signingIn = MutableStateFlow(false)

    val cloud: StateFlow<CloudUiState> = combine(cloudAuth.account, cloudAccess, signingIn) { account, access, busy ->
        val check = access.takeIf { account != null }
        CloudUiState(cloudAuth.isAvailable, account, check?.first, check?.second, busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudUiState(available = cloudAuth.isAvailable))

    init {
        // Whenever an account is signed in, check that the cloud lets it in (google-services.json, the app's
        // fingerprints and firestore.rules all set up right).
        viewModelScope.launch {
            cloudAuth.account.collectLatest { account -> if (account != null) checkCloudAccess(account) else cloudAccess.update { null } }
        }
    }

    /** Shows Google's account sheet over [activityContext] (the Activity) and signs in with the chosen account. */
    suspend fun signIn(activityContext: Context) {
        if (signingIn.value) return
        signingIn.update { true }
        try {
            val account = cloudAuth.signIn(activityContext) ?: return // the user closed the sheet
            _messages.send(UiMessage(R.string.cloud_signed_in, listOf(account.email.orEmpty())))
        } catch (e: SignInException) {
            _messages.send(
                when (e.reason) {
                    SignInException.Reason.NO_ACCOUNT -> UiMessage(R.string.cloud_sign_in_no_account)
                    SignInException.Reason.NETWORK -> UiMessage(R.string.cloud_sign_in_offline)
                    SignInException.Reason.OTHER -> UiMessage(R.string.cloud_sign_in_failed, listOf(e.message.orEmpty()))
                },
            )
        } finally {
            signingIn.update { false }
        }
    }

    fun signOut() = viewModelScope.launch {
        cloudAuth.signOut()
        _messages.send(UiMessage(R.string.cloud_signed_out))
    }

    fun retryCloudAccess() {
        val account = cloudAuth.currentAccount() ?: return
        viewModelScope.launch { checkCloudAccess(account) }
    }

    private suspend fun checkCloudAccess(account: CloudAccount) {
        cloudAccess.update { CloudAccess.CHECKING to null }
        val result = try {
            remoteStore.readMeta(account.uid)
            CloudAccess.OK to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when (e.cloudProblem()) {
                CloudProblem.OFFLINE -> CloudAccess.OFFLINE to null
                CloudProblem.ACCESS_DENIED -> CloudAccess.DENIED to null
                CloudProblem.OTHER -> CloudAccess.ERROR to (e.message ?: e.javaClass.simpleName)
            }
        }
        cloudAccess.update { result }
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { preferencesRepository.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setDynamicColor(enabled) }
    fun setEarningsMode(mode: EarningsMode) = viewModelScope.launch { preferencesRepository.setEarningsMode(mode) }

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

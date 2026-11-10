package com.vkurylo.shifttracker.ui.settings

import android.content.Context
import android.net.Uri
import android.util.Log
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.data.DocumentIo
import com.vkurylo.shifttracker.data.sync.CloudAccount
import com.vkurylo.shifttracker.data.sync.Counts
import com.vkurylo.shifttracker.data.sync.RemoteStore
import com.vkurylo.shifttracker.data.sync.SignInChoice
import com.vkurylo.shifttracker.data.sync.SignInPlan
import com.vkurylo.shifttracker.data.sync.SyncController
import com.vkurylo.shifttracker.data.sync.SyncOutcome
import com.vkurylo.shifttracker.data.sync.SyncProblem
import com.vkurylo.shifttracker.data.sync.SyncStatus
import com.vkurylo.shifttracker.data.sync.firebase.CloudAuth
import com.vkurylo.shifttracker.data.sync.firebase.CloudProblem
import com.vkurylo.shifttracker.data.sync.firebase.SIGN_IN_LOG_TAG
import com.vkurylo.shifttracker.data.sync.firebase.SignInException
import com.vkurylo.shifttracker.data.sync.firebase.SignInFailures
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** Why the last sign-in failed (with its code), shown until the next attempt. Null after a success. */
    val signInError: UiMessage? = null,
    val sync: SyncStatus = SyncStatus(),
    /** What the account row says about sync while it is on (one message per line). */
    val syncLines: List<UiMessage> = emptyList(),
    /** Turning sync on is in progress (reading the account, linking, the first sync). */
    val switchingOn: Boolean = false,
    /** A question to answer before sync can go on. */
    val decision: SyncDecision? = null,
)

/** A question to answer before sync can be turned on (or go on). */
sealed interface SyncDecision {
    /** This phone has never synced, and both it and the account have data. */
    data class Merge(val phone: Counts, val cloud: Counts, val lookAlikes: Int) : SyncDecision

    /** This phone's data was synced with another account. */
    data class AccountSwitch(val previousEmail: String?, val currentEmail: String?, val phone: Counts, val cloud: Counts) : SyncDecision

    /** The account's cloud data was deleted after this phone synced with it. */
    data object CloudReset : SyncDecision
}

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
    private val syncController: SyncController,
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
    private val signInError = MutableStateFlow<UiMessage?>(null)
    private val switchingOn = MutableStateFlow(false)
    private val decision = MutableStateFlow<SyncDecision?>(null)

    private val account = combine(cloudAuth.account, cloudAccess, signingIn, signInError) { account, access, busy, error ->
        val check = access.takeIf { account != null }
        CloudUiState(cloudAuth.isAvailable, account, check?.first, check?.second, busy, error.takeIf { account == null })
    }

    val cloud: StateFlow<CloudUiState> = combine(account, syncController.status, switchingOn, decision) { base, sync, switching, question ->
        base.copy(sync = sync, syncLines = sync.describe(), switchingOn = switching, decision = question)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudUiState(available = cloudAuth.isAvailable))

    init {
        // Whenever an account is signed in, check that the cloud lets it in (google-services.json, the app's
        // fingerprints and firestore.rules all set up right).
        viewModelScope.launch {
            cloudAuth.account.collectLatest { account -> if (account != null) checkCloudAccess(account) else cloudAccess.update { null } }
        }
    }

    /**
     * Shows Google's account sheet over [activityContext] (the Activity) and signs in with the chosen account.
     * Runs in the ViewModel's scope, so a recomposition, rotation or leaving the screen can't cancel it half-way
     * (the Activity is only held while the sheet is open). Closing the sheet shows nothing; any other failure
     * shows why, with its code, in a snackbar and in the row until the next attempt. CloudAuth logs them all.
     */
    fun signIn(activityContext: Context) {
        if (signingIn.value) return
        signingIn.update { true }
        signInError.update { null }
        viewModelScope.launch {
            try {
                // Signing in alone never syncs: sync stays off until it is turned on.
                syncController.disable()
                val account = cloudAuth.signIn(activityContext) ?: return@launch // the user closed the sheet
                _messages.send(UiMessage(R.string.cloud_signed_in, listOf(account.email.orEmpty())))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = e as? SignInException
                    ?: SignInException(SignInException.Reason.OTHER, SignInFailures.codeOf(e), e).also {
                        Log.e(SIGN_IN_LOG_TAG, "Sign-in failed: ${it.code}", e)
                    }
                val message = failure.toMessage()
                signInError.update { message }
                _messages.send(message)
            } finally {
                signingIn.update { false }
            }
        }
    }

    private fun SignInException.toMessage(): UiMessage = when (reason) {
        SignInException.Reason.NO_ACCOUNT -> UiMessage(R.string.cloud_sign_in_no_account, listOf(code))
        SignInException.Reason.NETWORK -> UiMessage(R.string.cloud_sign_in_offline, listOf(code))
        SignInException.Reason.SETUP ->
            UiMessage(R.string.cloud_sign_in_setup, listOf(code, packageName ?: "?", signingSha1 ?: "?"))
        SignInException.Reason.OTHER -> UiMessage(R.string.cloud_sign_in_failed, listOf(code))
    }

    fun signOut() = viewModelScope.launch {
        decision.update { null }
        syncController.cancelDecision()
        syncController.signOut()
        _messages.send(UiMessage(R.string.cloud_signed_out))
    }

    // --- Sync on/off --------------------------------------------------------------------------------------

    /**
     * The Sync switch. On: reads what linking needs; a question (merge, another account's data, the cloud data
     * deleted) is shown first and nothing syncs until it is answered; otherwise it links and syncs right away.
     */
    fun setSyncEnabled(enabled: Boolean) {
        if (!enabled) {
            viewModelScope.launch { syncController.disable() }
            return
        }
        if (switchingOn.value || decision.value != null) return
        switchingOn.update { true }
        viewModelScope.launch {
            try {
                when (val plan = syncController.prepare()) {
                    is SignInPlan.Ready -> link(plan.choice)
                    is SignInPlan.AskMerge -> decision.update { SyncDecision.Merge(plan.phone, plan.cloud, plan.lookAlikes) }
                    is SignInPlan.AskAccountSwitch -> decision.update {
                        SyncDecision.AccountSwitch(plan.previousEmail, cloudAuth.currentAccount()?.email, plan.phone, plan.cloud)
                    }
                    SignInPlan.CloudWasReset -> decision.update { SyncDecision.CloudReset }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(SyncController.TAG, "Turning sync on failed", e)
                _messages.send(UiMessage(R.string.sync_turn_on_failed, listOf(e.toSyncMessage())))
            } finally {
                switchingOn.update { false }
            }
        }
    }

    /** The answer to [CloudUiState.decision]. */
    fun answer(choice: SignInChoice) {
        decision.update { null }
        switchingOn.update { true }
        viewModelScope.launch {
            try {
                link(choice)
            } finally {
                switchingOn.update { false }
            }
        }
    }

    /** The question was closed without an answer: sync stays as it was. */
    fun dismissDecision() {
        decision.update { null }
        syncController.cancelDecision()
    }

    /** The cloud data was deleted while sync was on: ask what to do (from the account row). */
    fun reviewCloudReset() {
        if (decision.value == null) decision.update { SyncDecision.CloudReset }
    }

    /** "Turn sync off" in the cloud-reset question. */
    fun turnSyncOff() {
        decision.update { null }
        syncController.cancelDecision()
        viewModelScope.launch { syncController.disable() }
    }

    private suspend fun link(choice: SignInChoice) {
        val outcome = syncController.link(choice)
        val problem = outcome.problem
        _messages.send(
            if (problem == null || problem == SyncProblem.BUDGET) UiMessage(R.string.sync_turned_on)
            else UiMessage(R.string.sync_first_sync_failed, listOf(outcome.problemMessage(0)))
        )
    }

    /** "Sync now". The result shows in the account row. */
    fun syncNow() {
        viewModelScope.launch { syncController.syncNow() }
    }

    override fun onCleared() {
        // A question left open when the screen goes away must not keep sync waiting.
        if (decision.value != null) syncController.cancelDecision()
    }

    fun retryCloudAccess() {
        val account = cloudAuth.currentAccount() ?: return
        viewModelScope.launch { checkCloudAccess(account) }
    }

    private suspend fun checkCloudAccess(account: CloudAccount) {
        cloudAccess.update { CloudAccess.CHECKING to null }
        val result = try {
            withContext(Dispatchers.Default) { remoteStore.readMeta(account.uid) }
            CloudAccess.OK to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when (e.cloudProblem()) {
                CloudProblem.OFFLINE -> CloudAccess.OFFLINE to null
                CloudProblem.ACCESS_DENIED -> CloudAccess.DENIED to null
                CloudProblem.WRITE_REFUSED, CloudProblem.OTHER -> CloudAccess.ERROR to (e.message ?: e.javaClass.simpleName)
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

private val SYNC_TIME = DateTimeFormatter.ofPattern("HH:mm")
private val SYNC_DATE_TIME = DateTimeFormatter.ofPattern("dd.MM, HH:mm")

/** When it happened, short: "14:32" today, "08.11, 14:32" before. */
internal fun syncTime(millis: Long, today: LocalDate = LocalDate.now()): String {
    val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return (if (time.toLocalDate() == today) SYNC_TIME else SYNC_DATE_TIME).format(time)
}

/** What the account row says about sync while it is on. */
internal fun SyncStatus.describe(today: LocalDate = LocalDate.now()): List<UiMessage> {
    if (!enabled) return emptyList()
    val lines = mutableListOf<UiMessage>()
    val outcome = last
    when {
        running -> lines += UiMessage(R.string.sync_running)
        cloudReset -> lines += UiMessage(R.string.sync_cloud_reset)
        outcome?.problem != null -> lines += outcome.problemMessage(writesToday)
        lastSyncedAt != null -> lines += UiMessage(
            R.string.sync_done,
            listOf(syncTime(lastSyncedAt, today), outcome?.downloaded ?: 0, outcome?.uploaded ?: 0, writesToday),
        )
        else -> lines += UiMessage(R.string.sync_not_yet)
    }
    if (!running && (outcome?.skipped ?: 0) > 0) lines += UiMessage(R.string.sync_skipped, listOf(outcome!!.skipped))
    if (!running && pending > 0) lines += UiMessage(R.string.sync_pending, listOf(pending))
    return lines
}

/** Why the last attempt didn't complete, for the user. */
internal fun SyncOutcome.problemMessage(writesToday: Int): UiMessage = when (problem) {
    SyncProblem.OFFLINE -> UiMessage(R.string.sync_offline)
    SyncProblem.ACCESS_DENIED -> UiMessage(R.string.cloud_access_denied)
    SyncProblem.CLOCK -> UiMessage(R.string.sync_clock_ahead, listOf(detail ?: "?"))
    SyncProblem.WRITE_REFUSED_CLOCK_UNKNOWN -> UiMessage(R.string.sync_refused_check_clock)
    SyncProblem.WRITE_REFUSED -> UiMessage(R.string.sync_refused_rules)
    SyncProblem.CLOUD_RESET -> UiMessage(R.string.sync_cloud_reset)
    SyncProblem.BUDGET -> UiMessage(R.string.sync_budget, listOf(writesToday, SyncController.DAILY_AUTOMATIC_WRITES))
    SyncProblem.OTHER, null -> UiMessage(R.string.sync_error, listOf(detail ?: "?"))
}

private fun Exception.toSyncMessage(): UiMessage = when (cloudProblem()) {
    CloudProblem.OFFLINE -> UiMessage(R.string.sync_offline)
    CloudProblem.ACCESS_DENIED -> UiMessage(R.string.cloud_access_denied)
    else -> UiMessage(R.string.sync_error, listOf("${javaClass.simpleName}: $message"))
}

/** The message's text; an argument that is itself a [UiMessage] is turned into its text first. */
fun UiMessage.text(context: Context): String =
    context.getString(resId, *args.map { if (it is UiMessage) it.text(context) else it }.toTypedArray())

package com.vkurylo.shifttracker.data.sync

import android.util.Log
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.sync.firebase.CloudAuth
import com.vkurylo.shifttracker.data.sync.firebase.CloudProblem
import com.vkurylo.shifttracker.data.sync.firebase.cloudProblem
import com.vkurylo.shifttracker.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Where sync stands, for Settings. */
data class SyncStatus(
    /** The user turned sync on. */
    val enabled: Boolean = false,
    val running: Boolean = false,
    /** Changes on this phone waiting to be uploaded. */
    val pending: Int = 0,
    /** End of the last complete sync. */
    val lastSyncedAt: Long? = null,
    /** The last attempt, complete or not. */
    val last: SyncOutcome? = null,
    val cloudReset: Boolean = false,
    /** Cloud writes this phone made today. */
    val writesToday: Int = 0,
    /** This phone's data is linked to an account (also while sync is off): its changes go there once sync is on. */
    val linked: Boolean = false,
)

/** Runs syncs in the background (WorkManager): one at a time, when the phone is online, retried with backoff. */
interface SyncScheduler {
    fun request()

    fun cancel()
}

/** What the background job should do after an automatic run. */
enum class AutomaticRun { DONE, RETRY }

/**
 * Decides when the [SyncEngine] runs, and keeps what Settings shows about it.
 *
 * - Sync is off until the user, signed in, turns it on ([prepare] and [link]); signing in alone syncs nothing.
 *   Signing out turns it off.
 * - Automatic syncs ([SyncScheduler], [runAutomatic]): at app start and when the app comes to the foreground (at
 *   most once a minute), and a few seconds after the last local change ([start]). The local-change trigger
 *   watches only the changes not uploaded yet, which applying the cloud's versions never creates (they are stored
 *   as uploaded), so a download can't set off an upload and two phones can't keep sending a record back and forth.
 * - Nothing runs while a first-link question is open or after the account's cloud data was deleted, until the user
 *   answers. A JSON import and a sync never overlap ([SyncLock]).
 * - Automatic syncs write at most [DAILY_AUTOMATIC_WRITES] records a day (the free Firestore quota is 20 000), so a
 *   bug that keeps changing records can't use it up; "Sync now" isn't limited.
 * - All of it runs off the main thread.
 */
@OptIn(FlowPreview::class)
@Singleton
class SyncController @Inject constructor(
    private val engine: SyncEngine,
    private val auth: CloudAuth,
    private val prefs: SyncPreferences,
    private val db: ShiftDatabase,
    private val scheduler: SyncScheduler,
    private val serverClock: ServerClock,
    private val clock: WallClock,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val running = MutableStateFlow(false)

    /** A first-link question is open (between [prepare] and [link] or [cancelDecision]). */
    private val deciding = MutableStateFlow(false)

    private val dirty = db.syncDao().observeDirty().distinctUntilChanged()

    val status: Flow<SyncStatus> = combine(prefs.settings, prefs.state, running, dirty) { settings, state, isRunning, waiting ->
        SyncStatus(
            enabled = settings.enabled,
            running = isRunning,
            pending = waiting.count,
            lastSyncedAt = state.lastSyncedAt,
            last = settings.last,
            cloudReset = settings.cloudReset,
            writesToday = settings.writesOn(today()),
            linked = state.boundUid != null,
        )
    }

    /** Starts the local-change trigger. Called once, from Application.onCreate. */
    fun start() {
        scope.launch {
            dirty.filter { it.count > 0 }.debounce(LOCAL_CHANGE_DELAY_MS).collect { requestIfReady() }
        }
    }

    /** The app came to the foreground (also when it starts). */
    fun onForeground() {
        scope.launch {
            val last = prefs.get().lastSyncedAt
            if (last == null || clock.nowMillis() - last >= FOREGROUND_MIN_INTERVAL_MS) requestIfReady()
        }
    }

    private suspend fun requestIfReady() {
        if (canRun()) scheduler.request()
    }

    private suspend fun canRun(): Boolean {
        val account = auth.currentAccount() ?: return false
        val settings = prefs.currentSettings()
        return settings.enabled && !settings.cloudReset && !deciding.value && prefs.get().boundUid == account.uid
    }

    // --- Turning sync on and off ----------------------------------------------------------------------

    /**
     * Turning sync on, first half: what linking this phone's data to the signed-in account needs (see
     * [SyncEngine.prepareSignIn]). From here until [link] or [cancelDecision], nothing syncs.
     */
    suspend fun prepare(): SignInPlan = withContext(Dispatchers.Default) {
        val account = auth.currentAccount() ?: throw IllegalStateException("Not signed in")
        deciding.value = true
        try {
            engine.prepareSignIn(account)
        } catch (e: Throwable) {
            deciding.value = false
            throw e
        }
    }

    /** The question [prepare] raised was dismissed: sync stays as it was. */
    fun cancelDecision() {
        deciding.value = false
    }

    /**
     * Turning sync on, second half: carries out [choice], links this phone's data to the account and syncs. Sync
     * is on from then on, also when this first sync fails after the link was made (it is retried); the outcome
     * says how it went.
     */
    suspend fun link(choice: SignInChoice): SyncOutcome = withContext(Dispatchers.Default) {
        val account = auth.currentAccount() ?: throw IllegalStateException("Not signed in")
        running.value = true
        try {
            val result = engine.completeSignIn(account, choice, ::countWrites)
            prefs.updateSettings { it.copy(enabled = true, cloudReset = false) }
            record(result, manual = true) ?: SyncOutcome(clock.nowMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (prefs.get().boundUid == account.uid) prefs.updateSettings { it.copy(enabled = true, cloudReset = false) }
            record(failure(e))
        } finally {
            running.value = false
            deciding.value = false
        }
    }

    /** Sync off. The link stays: turning it on again with the same account just carries on. */
    suspend fun disable() {
        prefs.updateSettings { it.copy(enabled = false) }
        scheduler.cancel()
    }

    /** Signs out and turns sync off. The data on the phone stays. */
    suspend fun signOut() {
        disable()
        auth.signOut()
    }

    /**
     * "Delete my cloud data": turns sync off first (nothing scheduled can upload into the deletion), then deletes
     * the signed-in account's records and marker from the cloud. This phone keeps its data; other phones that
     * sync with the account stop and ask what to do. Turning sync on again uploads this phone's data afresh.
     * Returns the number of records deleted; throws when the cloud can't be reached (deleting again finishes).
     */
    suspend fun deleteCloudData(): Int = withContext(Dispatchers.Default) {
        val account = auth.currentAccount() ?: throw IllegalStateException("Not signed in")
        disable()
        val deleted = engine.deleteCloudData(account)
        prefs.updateSettings { it.copy(cloudReset = false) }
        Log.i(TAG, "Deleted the account's cloud data: $deleted records")
        deleted
    }

    // --- Syncing ----------------------------------------------------------------------------------------

    /** "Sync now": a sync right away, without the daily write limit. Null when sync isn't on (or must wait). */
    suspend fun syncNow(): SyncOutcome? = run(manual = true)

    /** The background job's sync. */
    suspend fun runAutomatic(): AutomaticRun {
        val outcome = run(manual = false) ?: return AutomaticRun.DONE
        return when (outcome.problem) {
            SyncProblem.OFFLINE, SyncProblem.OTHER -> AutomaticRun.RETRY
            else -> AutomaticRun.DONE
        }
    }

    private suspend fun run(manual: Boolean): SyncOutcome? = withContext(Dispatchers.Default) {
        if (!canRun()) return@withContext null
        val left = DAILY_AUTOMATIC_WRITES - prefs.currentSettings().writesOn(today())
        if (!manual && left <= 0) return@withContext record(SyncOutcome(clock.nowMillis(), limited = true, problem = SyncProblem.BUDGET))
        running.value = true
        try {
            record(engine.sync(if (manual) Int.MAX_VALUE else left, ::countWrites), manual)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            record(failure(e))
        } finally {
            running.value = false
        }
    }

    private suspend fun record(result: SyncResult, manual: Boolean): SyncOutcome? = when (result) {
        is SyncResult.Synced -> {
            Log.i(
                TAG,
                "Synced: ${result.downloaded} downloaded, ${result.uploaded} uploaded (= cloud writes), " +
                    "${result.skipped} skipped${if (result.limited) ", stopped at the write limit" else ""}",
            )
            record(
                SyncOutcome(
                    at = clock.nowMillis(),
                    downloaded = result.downloaded,
                    uploaded = result.uploaded,
                    skipped = result.skipped,
                    limited = result.limited,
                    problem = if (result.limited && !manual) SyncProblem.BUDGET else null,
                ),
            )
        }
        SyncResult.CloudWasReset -> {
            Log.w(TAG, "The account's cloud data was deleted since this phone synced: waiting for the user")
            prefs.updateSettings { it.copy(cloudReset = true) }
            scheduler.cancel()
            record(SyncOutcome(clock.nowMillis(), problem = SyncProblem.CLOUD_RESET))
        }
        SyncResult.NotLinked -> null
    }

    private suspend fun record(outcome: SyncOutcome): SyncOutcome {
        prefs.updateSettings { it.copy(last = outcome) }
        return outcome
    }

    private suspend fun failure(e: Exception): SyncOutcome {
        Log.e(TAG, "Sync failed", e)
        val now = clock.nowMillis()
        return when (e.cloudProblem()) {
            CloudProblem.OFFLINE -> SyncOutcome(now, problem = SyncProblem.OFFLINE)
            CloudProblem.ACCESS_DENIED -> SyncOutcome(now, problem = SyncProblem.ACCESS_DENIED)
            CloudProblem.WRITE_REFUSED -> refusedWrite(now)
            CloudProblem.OTHER -> SyncOutcome(now, problem = SyncProblem.OTHER, detail = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * The cloud refused an upload. firestore.rules refuses change times more than 10 minutes ahead of the server,
     * so compare the phone's clock, and the newest change waiting, with the server's time.
     */
    private suspend fun refusedWrite(now: Long): SyncOutcome {
        val server = serverClock.nowMillis() ?: return SyncOutcome(now, problem = SyncProblem.WRITE_REFUSED_CLOCK_UNKNOWN)
        val newest = db.syncDao().dirty().newest ?: Long.MIN_VALUE
        val ahead = maxOf(now - server, newest - server)
        Log.w(TAG, "Upload refused; phone clock ${now - server} ms from the server's, newest change ${newest - server} ms")
        return if (ahead > CLOCK_TOLERANCE_MS) {
            SyncOutcome(now, problem = SyncProblem.CLOCK, detail = ((ahead + 59_999) / 60_000).toString())
        } else {
            SyncOutcome(now, problem = SyncProblem.WRITE_REFUSED)
        }
    }

    /** Counts cloud writes towards today's total as they are made. */
    private suspend fun countWrites(count: Int) {
        if (count == 0) return
        val day = today()
        prefs.updateSettings { it.copy(writesDay = day, writesToday = it.writesOn(day) + count) }
    }

    private fun today(): Long = Instant.ofEpochMilli(clock.nowMillis()).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    private fun SyncSettings.writesOn(day: Long) = if (writesDay == day) writesToday else 0

    companion object {
        const val TAG = "CloudSync"

        /** Automatic syncs stop writing for the day after this many cloud writes (a quarter of the free quota). */
        const val DAILY_AUTOMATIC_WRITES = 5_000

        /** Edits in a row go up together, this long after the last one. */
        const val LOCAL_CHANGE_DELAY_MS = 3_000L

        /** Coming back to the app syncs only if the last sync is at least this old. */
        const val FOREGROUND_MIN_INTERVAL_MS = 60_000L

        /** firestore.rules allows 10 minutes; a refusal with more than this ahead is the clock. */
        const val CLOCK_TOLERANCE_MS = 9 * 60_000L
    }
}

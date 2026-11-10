package com.vkurylo.shifttracker.data.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.sync.firebase.CloudAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** A [SyncPreferences] on a DataStore file of its own (one per test phone). */
internal fun testSyncPreferences(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)): SyncPreferences {
    val file = File.createTempFile("sync", ".preferences_pb").apply { delete(); deleteOnExit() }
    return SyncPreferences(PreferenceDataStoreFactory.create(scope = scope) { file })
}

/** Counts the background syncs asked for, instead of running them. */
class FakeScheduler : SyncScheduler {
    var requests = 0
    var cancels = 0

    override fun request() {
        requests++
    }

    override fun cancel() {
        cancels++
    }
}

/** A [CloudAuth] whose sign-in does what [onSignIn] says (return an account, null for "closed", or throw). */
class FakeCloudAuth(override val isAvailable: Boolean = true, signedInAs: CloudAccount? = null) : CloudAuth {
    private val signedIn = MutableStateFlow(signedInAs)
    var onSignIn: suspend () -> CloudAccount? = { null }
    var signInCalls = 0

    override val account: Flow<CloudAccount?> = signedIn

    override fun currentAccount(): CloudAccount? = signedIn.value

    override suspend fun signIn(activityContext: Context): CloudAccount? {
        signInCalls++
        return onSignIn()?.also { signedIn.value = it }
    }

    override suspend fun signOut() {
        signedIn.value = null
    }
}

/** A [SyncController] on a phone's [db], with the cloud and the account given and fakes for the rest. */
internal fun testSyncController(
    db: ShiftDatabase,
    remote: RemoteStore,
    auth: CloudAuth,
    prefs: SyncPreferences = testSyncPreferences(),
    scheduler: SyncScheduler = FakeScheduler(),
    serverClock: ServerClock = ServerClock { null },
    clock: WallClock = WallClock.System,
    lock: SyncLock = SyncLock(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): SyncController {
    val engine = SyncEngine(db, remote, prefs, { }, clock, lock)
    return SyncController(engine, auth, prefs, db, scheduler, serverClock, clock, scope)
}

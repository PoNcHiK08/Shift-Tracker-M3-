package com.vkurylo.shifttracker.data.sync.firebase

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.memoryCacheSettings
import com.google.firebase.firestore.firestoreSettings
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.data.sync.CloudAccount
import com.vkurylo.shifttracker.data.sync.CloudMeta
import com.vkurylo.shifttracker.data.sync.InMemorySyncStateStore
import com.vkurylo.shifttracker.data.sync.PushResult
import com.vkurylo.shifttracker.data.sync.RemoteBonus
import com.vkurylo.shifttracker.data.sync.RemoteJob
import com.vkurylo.shifttracker.data.sync.RemoteShift
import com.vkurylo.shifttracker.data.sync.SignInChoice
import com.vkurylo.shifttracker.data.sync.SignInPlan
import com.vkurylo.shifttracker.data.sync.SyncEngine
import com.vkurylo.shifttracker.data.sync.SyncResult
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * [FirestoreRemoteStore] and the [SyncEngine] against the Firebase emulators, with firestore.rules loaded:
 * the real Firestore and Auth SDKs, signed in with a (fake) Google account. Skipped unless the emulators run:
 *
 *   firebase/run-emulator-tests.sh
 *
 * which starts them with the rules (the placeholder email replaced by [EMAIL]) and runs this class.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class FirestoreEmulatorTest {

    private lateinit var app: FirebaseApp
    private lateinit var firestore: FirebaseFirestore
    private lateinit var store: FirestoreRemoteStore
    private lateinit var account: CloudAccount
    private val databases = mutableListOf<ShiftDatabase>()

    @Before
    fun setUp() {
        assumeTrue(
            "Firebase emulators not running",
            System.getenv("FIRESTORE_EMULATOR_HOST") != null && System.getenv("FIREBASE_AUTH_EMULATOR_HOST") != null,
        )
        // Every test starts from empty emulators (the Auth emulator gives the same email the same user).
        clearEmulator("http://${System.getenv("FIRESTORE_EMULATOR_HOST")}/emulator/v1/projects/$PROJECT/databases/(default)/documents")
        clearEmulator("http://${System.getenv("FIREBASE_AUTH_EMULATOR_HOST")}/emulator/v1/projects/$PROJECT/accounts")
        app = emulatorApp()
        firestore = FirebaseFirestore.getInstance(app)
        store = FirestoreRemoteStore { firestore }
        // The Auth emulator takes an unsigned Google ID token: the same claims a real Google sign-in gives.
        val token = """{"sub":"google-${UUID.randomUUID()}","email":"$EMAIL","email_verified":true}"""
        val user = onBackground { FirebaseAuth.getInstance(app).signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await().user!! }
        account = CloudAccount(user.uid, user.email)
    }

    @After
    fun tearDown() {
        databases.forEach { it.close() }
        if (::app.isInitialized) FirebaseAuth.getInstance(app).signOut()
    }

    private fun clearEmulator(url: String) {
        val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.requestMethod = "DELETE"
        check(connection.responseCode == 200) { "clearing $url: ${connection.responseCode}" }
        connection.disconnect()
    }

    /**
     * Runs [block] off the main thread while the test thread keeps Robolectric's main looper going: Firebase
     * posts some of its work to the main thread.
     */
    private fun <T> onBackground(block: suspend () -> T): T {
        val result = CoroutineScope(Dispatchers.IO).async { block() }
        val deadline = System.currentTimeMillis() + 60_000
        while (!result.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
            check(System.currentTimeMillis() < deadline) { "timed out" }
        }
        return runBlocking { result.await() }
    }

    private fun id() = UUID.randomUUID().toString()

    private fun job(syncId: String = id(), updatedAt: Long, name: String = "Kawiarnia") =
        RemoteJob(syncId, updatedAt, false, name, 3_300, 1, null, "PLN", emptyList())

    private fun shift(jobSyncId: String, updatedAt: Long, syncId: String = id(), day: Long = 20_400) =
        RemoteShift(syncId, updatedAt, false, jobSyncId, day, 480, 960, 30, 3_300, null, null, null)

    @Test
    fun theMarkerIsCreatedOnceAndDeletedWithTheData() = onBackground {
        assertNull(store.readMeta(account.uid))
        val first = store.ensureMeta(account.uid, CloudMeta("epoch-1"))
        assertEquals(CloudMeta("epoch-1"), first)
        assertEquals(first, store.ensureMeta(account.uid, CloudMeta("epoch-2")))
        assertEquals(first, store.readMeta(account.uid))

        val jobId = id()
        store.push(account.uid, listOf(job(jobId, 1_000), shift(jobId, 1_000), RemoteBonus(id(), 1_000, false, jobId, 20_400, 5_000, null)))
        assertTrue(store.hasRecords(account.uid))
        store.deleteAll(account.uid)
        assertNull(store.readMeta(account.uid))
        assertTrue(!store.hasRecords(account.uid))
    }

    @Test
    fun aRecordIsOnlyReplacedByALaterVersion() = onBackground {
        val jobId = id()
        assertEquals(listOf(PushResult.Written), store.push(account.uid, listOf(job(jobId, 1_000))))
        // The same version, and an older one: the cloud keeps its own and says which.
        val same = store.push(account.uid, listOf(job(jobId, 1_000)))
        assertEquals(PushResult.Kept(job(jobId, 1_000)), same.single())
        val older = store.push(account.uid, listOf(job(jobId, 900, name = "Old")))
        assertEquals(PushResult.Kept(job(jobId, 1_000)), older.single())
        assertEquals(listOf(PushResult.Written), store.push(account.uid, listOf(job(jobId, 1_100, name = "New"))))
        assertEquals(listOf(job(jobId, 1_100, name = "New")), store.jobs(account.uid, listOf(jobId, id())))
    }

    @Test
    fun changesAreFoundByServerTimePageByPage() = onBackground {
        val jobId = id()
        // More than a page (500) and more than one transaction (250).
        val many = (0 until 620).map { shift(jobId, updatedAt = 1_000L + it, day = 20_000L + it) }
        store.push(account.uid, listOf(job(jobId, 1_000)) + many)
        val all = store.changesSince(account.uid, null)
        assertEquals(1, all.jobs.size)
        assertEquals(many.toSet(), all.shifts.toSet())
        val since = all.newestServerTime!!

        val later = shift(jobId, 5_000)
        store.push(account.uid, listOf(later))
        val changes = store.changesSince(account.uid, since + 1)
        assertEquals(listOf(later), changes.shifts)
        assertTrue(changes.jobs.isEmpty())
        assertTrue(changes.newestServerTime!! > since)
    }

    @Test
    fun theRulesRefuseAnotherAccount() = onBackground {
        val auth = FirebaseAuth.getInstance(app)
        val stranger = """{"sub":"stranger-${id()}","email":"someone@example.com","email_verified":true}"""
        auth.signInWithCredential(GoogleAuthProvider.getCredential(stranger, null)).await()
        val error = runCatching { store.readMeta(auth.currentUser!!.uid) }.exceptionOrNull()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, (error as FirebaseFirestoreException).code)
        assertEquals(CloudProblem.ACCESS_DENIED, error.cloudProblem())
    }

    /** Two phones with their own databases sync through the emulator: what one does, the other gets. */
    @Test
    fun twoPhonesSyncThroughTheCloud() = onBackground {
        class Phone(var now: Long) {
            val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShiftDatabase::class.java)
                .build().also { databases += it }
            val clock = WallClock { now }
            val details = JobDetailsSource(db.paySettingsDao())
            val jobs = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details, clock)
            val shifts = RoomShiftRepository(db, db.shiftDao(), details, clock)
            val engine = SyncEngine(db, store, InMemorySyncStateStore(), { }, clock)

            suspend fun signIn(): SyncResult = engine.completeSignIn(account, (engine.prepareSignIn(account) as SignInPlan.Ready).choice)

            suspend fun shownShifts() = db.shiftDao().getAll().map { it.syncId to it.note }.toSet()
        }
        val start = System.currentTimeMillis()
        val a = Phone(start)
        val jobId = a.jobs.upsert(Job(name = "Kawiarnia", hourlyRateGrosze = 3_300, color = 1, currency = "PLN"))
        val shiftId = a.shifts.upsert(
            Shift(jobId = jobId, date = LocalDate.of(2026, 11, 2), start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3_300, note = "rano"),
        )
        assertEquals(SignInPlan.Ready(SignInChoice.UPLOAD), a.engine.prepareSignIn(account))
        assertEquals(SyncResult.Synced(downloaded = 0, uploaded = 2, skipped = 0), a.signIn())

        val b = Phone(start + 1_000)
        assertEquals(SyncResult.Synced(downloaded = 2, uploaded = 0, skipped = 0), b.signIn())
        assertEquals(a.shownShifts(), b.shownShifts())

        // B edits the note; A gets it.
        val onB = b.db.shiftDao().getAll().single()
        b.now += 1_000
        b.shifts.upsert(Shift(id = onB.id, jobId = onB.jobId, date = LocalDate.ofEpochDay(onB.dateEpochDay), start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3_300, note = "zamiana"))
        assertEquals(1, (b.engine.sync() as SyncResult.Synced).uploaded)
        a.now += 3_000
        assertEquals(1, (a.engine.sync() as SyncResult.Synced).downloaded)
        assertEquals(setOf(a.db.shiftDao().getAny(shiftId)!!.syncId to "zamiana"), a.shownShifts())

        // A deletes the job (and so its shift); B gets both deletions.
        a.now += 1_000
        a.jobs.delete(a.jobs.getJob(jobId)!!)
        assertEquals(2, (a.engine.sync() as SyncResult.Synced).uploaded)
        b.now += 5_000
        b.engine.sync()
        assertTrue(b.db.jobDao().getAll().isEmpty())
        assertTrue(b.db.shiftDao().getAll().isEmpty())
    }

    private companion object {
        const val PROJECT = "demo-shift-tracker"
        const val EMAIL = "owner@example.com"
        const val EMULATOR_HOST = "127.0.0.1"
        const val APP_NAME = "emulator"

        /** One Firebase app for all the tests (Firebase keeps state across them), pointed at the emulators once. */
        fun emulatorApp(): FirebaseApp {
            val context = ApplicationProvider.getApplicationContext<Context>()
            FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }?.let { return it }
            val options = FirebaseOptions.Builder()
                .setProjectId(PROJECT)
                .setApplicationId("1:1:android:1")
                .setApiKey("fake-api-key")
                .build()
            val app = FirebaseApp.initializeApp(context, options, APP_NAME)
            FirebaseAuth.getInstance(app).useEmulator(EMULATOR_HOST, 9099)
            FirebaseFirestore.getInstance(app).apply {
                useEmulator(EMULATOR_HOST, 8080)
                firestoreSettings = firestoreSettings { setLocalCacheSettings(memoryCacheSettings {}) }
            }
            return app
        }
    }
}

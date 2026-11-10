package com.vkurylo.shifttracker.ui.settings

import android.app.Application
import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.data.sync.CloudAccount
import com.vkurylo.shifttracker.data.sync.FakeCloudAuth
import com.vkurylo.shifttracker.data.sync.FakeRemoteStore
import com.vkurylo.shifttracker.data.sync.FileSafetyBackup
import com.vkurylo.shifttracker.data.sync.RemoteJob
import com.vkurylo.shifttracker.data.backup.JsonBackupRepository
import com.vkurylo.shifttracker.data.sync.SignInChoice
import com.vkurylo.shifttracker.data.sync.SyncController
import com.vkurylo.shifttracker.data.sync.testSyncController
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.SnackbarController
import com.vkurylo.shifttracker.ui.theme.ShiftTrackerTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/**
 * Settings → Account & sync with sync: off until the switch is turned on, the merge question (nothing syncs while it
 * is open), and the status after a sync and after an upload refused because of the clock.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class SettingsSyncTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val ana = CloudAccount("uid-ana", "ana@example.com")
    private val cloud = FakeRemoteStore()
    private val databases = mutableListOf<ShiftDatabase>()
    private val snackbars = SnackbarHostState()
    private val shownMessages = mutableListOf<String>()
    private var now = 1_762_000_000_000L
    private val clock = WallClock { now }
    private var serverNow: Long? = null

    @After
    fun tearDown() {
        databases.forEach { it.close() }
        java.io.File(context.filesDir, FileSafetyBackup.DIR).deleteRecursively()
        // Everything the Settings screen set off ran off the main thread.
        org.junit.Assert.assertEquals("cloud calls on the main thread", 0, cloud.callsOnMainThread)
    }

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    /** Waits for [condition], keeping the main thread going (the ViewModel's work resumes there). */
    private fun waitFor(condition: () -> Boolean) = composeRule.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun database() = Room.inMemoryDatabaseBuilder(context, ShiftDatabase::class.java).allowMainThreadQueries().build().also { databases += it }

    /** A phone's data: a job with two shifts. */
    private fun someData(db: ShiftDatabase, jobName: String) = runBlocking {
        val details = JobDetailsSource(db.paySettingsDao())
        val job = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details, clock)
            .upsert(Job(name = jobName, hourlyRateGrosze = 3300, color = 1, currency = "PLN"))
        val shifts = RoomShiftRepository(db, db.shiftDao(), details, clock)
        for (day in 1..2) {
            shifts.upsert(Shift(jobId = job, date = LocalDate.of(2026, 11, day), start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3300))
        }
        job
    }

    private lateinit var viewModel: SettingsViewModel

    private fun showSettings(db: ShiftDatabase): SyncController {
        val auth = FakeCloudAuth(signedInAs = ana)
        val controller = testSyncController(db, cloud, auth, serverClock = { serverNow }, clock = clock)
        val viewModel = settingsViewModel(context, db, auth, cloud, controller).also { viewModel = it }
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                snapshotFlow { snackbars.currentSnackbarData?.visuals?.message }.filterNotNull().collect { shownMessages += it }
            }
            ShiftTrackerTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalSnackbarController provides SnackbarController(snackbars, scope)) {
                    SettingsScreen(onManageJobs = {}, onImportIcs = {}, viewModel = viewModel)
                }
            }
        }
        return controller
    }

    /** Turns sync on as the switch does, off the main thread (which keeps running meanwhile, as in the app). */
    private fun linkInBackground(controller: SyncController, choice: SignInChoice) {
        val linked = java.util.concurrent.CompletableFuture<Unit>()
        Thread { runCatching { runBlocking { controller.link(choice) } }.fold({ linked.complete(Unit) }, linked::completeExceptionally) }.start()
        waitFor { linked.isDone }
        linked.get()
    }

    private fun scrollTo(text: String) = composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = true))

    private fun tap(text: String) {
        scrollTo(text)
        composeRule.onNodeWithText(text).performClick()
    }

    private fun waitForText(text: String, substring: Boolean = false) = composeRule.waitUntil(10_000) {
        composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun gone(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()

    @Test
    fun syncIsOffUntilTheSwitchIsTurnedOnThenTheRowShowsWhatMoved() {
        val db = database()
        someData(db, "Kawiarnia")
        showSettings(db)

        // Signed in: only the access check; no sync, no Sync now.
        scrollTo(str(R.string.sync_switch))
        waitForText(str(R.string.cloud_access_ok))
        assertTrue(gone(str(R.string.sync_now)))
        assertTrue(cloud.records(ana.uid).isEmpty())

        tap(str(R.string.sync_switch))
        waitFor { str(R.string.sync_turned_on) in shownMessages }
        scrollTo(str(R.string.sync_now))
        // 3 records sent: 3 cloud writes.
        waitForText(str(R.string.sync_done, "", 0, 3, 3).substringAfter(" · "), substring = true)
        assertTrue(cloud.records(ana.uid).size == 3)
    }

    @Test
    fun theMergeQuestionHoldsSyncUntilAnswered() {
        // Another phone already put its data in the account.
        val other = database()
        someData(other, "Biuro")
        runBlocking { testSyncController(other, cloud, FakeCloudAuth(signedInAs = ana)).let { it.link((it.prepare() as com.vkurylo.shifttracker.data.sync.SignInPlan.Ready).choice) } }
        val db = database()
        someData(db, "Kawiarnia")
        val controller = showSettings(db)

        tap(str(R.string.sync_switch))
        waitForText(str(R.string.sync_merge_title))
        assertNull("nothing syncs while the question is open", runBlocking { controller.syncNow() })

        composeRule.onNodeWithText(str(R.string.sync_merge)).performClick()
        waitFor { str(R.string.sync_turned_on) in shownMessages }
        assertTrue(gone(str(R.string.sync_merge_title)))
        val jobs = runBlocking { db.jobDao().getAll().map { it.name }.toSet() }
        assertTrue(jobs.toString(), jobs == setOf("Kawiarnia", "Biuro"))
    }

    @Test
    fun deletingMyCloudDataAsksFirstThenDeletesItAndTurnsSyncOff() {
        val db = database()
        someData(db, "Kawiarnia")
        val controller = showSettings(db)
        linkInBackground(controller, SignInChoice.UPLOAD)
        assertTrue(cloud.records(ana.uid).size == 3)

        // Cancel: nothing happens.
        tap(str(R.string.cloud_delete))
        waitForText(str(R.string.cloud_delete_title))
        composeRule.onNodeWithText(str(R.string.cancel)).performClick()
        composeRule.waitForIdle()
        assertTrue(gone(str(R.string.cloud_delete_title)))
        assertTrue(cloud.records(ana.uid).size == 3)

        tap(str(R.string.cloud_delete))
        waitForText(str(R.string.cloud_delete_title))
        composeRule.onNodeWithText(str(R.string.cloud_delete_confirm)).performClick()

        waitFor { str(R.string.cloud_delete_done, 3) in shownMessages }
        assertTrue(cloud.records(ana.uid).isEmpty())
        // Sync is off (no "Sync now"), and the phone still has its data.
        composeRule.waitUntil(10_000) { gone(str(R.string.sync_now)) }
        assertTrue(runBlocking { db.jobDao().getAll().size == 1 && db.shiftDao().getAll().size == 2 })
    }

    /** A backup file with [jobName]'s data, as Export writes it. */
    private fun backupFile(jobName: String): android.net.Uri {
        val source = database()
        someData(source, jobName)
        val file = java.io.File(context.cacheDir, "backup-$jobName.json")
        file.writeText(runBlocking { JsonBackupRepository(source).exportJson() })
        return android.net.Uri.fromFile(file)
    }

    @Test
    fun theImportQuestionSaysWhenItReplacesDataOnTheOtherPhonesToo() {
        val db = database()
        someData(db, "Kawiarnia")
        val controller = showSettings(db)
        val file = backupFile("Biuro")

        // Never synced: the import only replaces this phone's data.
        viewModel.requestImport(file)
        waitForText(str(R.string.import_backup_message))
        composeRule.onNodeWithText(str(R.string.cancel)).performClick()

        // Sync on: all the devices.
        linkInBackground(controller, SignInChoice.UPLOAD)
        viewModel.requestImport(file)
        waitForText(str(R.string.import_backup_title_everywhere))
        composeRule.onNodeWithText(str(R.string.import_backup_message_synced)).assertExists()
        composeRule.onNodeWithText(str(R.string.cancel)).performClick()

        // Sync off but still linked: the other devices, once sync is back on.
        tap(str(R.string.sync_switch))
        composeRule.waitUntil(10_000) { gone(str(R.string.sync_now)) }
        viewModel.requestImport(file)
        waitForText(str(R.string.import_backup_message_linked))
        composeRule.onNodeWithText(str(R.string.import_backup_title)).assertExists()
    }

    @Test
    fun anImportWithSyncOnSavesASafetyBackupFirstAndReplacesTheDataInTheCloudToo() {
        val db = database()
        someData(db, "Kawiarnia")
        val controller = showSettings(db)
        linkInBackground(controller, SignInChoice.UPLOAD)

        viewModel.requestImport(backupFile("Biuro"))
        waitForText(str(R.string.import_backup_title_everywhere))
        composeRule.onNodeWithText(str(R.string.replace_data_everywhere)).performClick()
        waitFor { str(R.string.import_backup_done, 1, 2, 0) in shownMessages }

        // The data that was replaced is in a safety backup.
        val saved = java.io.File(context.filesDir, FileSafetyBackup.DIR).listFiles().orEmpty().single()
        assertTrue(saved.name, saved.name.endsWith("-before-import.json"))
        assertTrue(saved.readText().contains("\"Kawiarnia\""))

        // After the next sync the cloud has the file's data, and the replaced job is deleted there too.
        tap(str(R.string.sync_now))
        // 6 sent: Kawiarnia and its 2 shifts as deleted, Biuro and its 2 shifts (3 writes before, 9 today).
        scrollTo(str(R.string.sync_switch))
        waitForText(str(R.string.sync_done, "", 0, 6, 9).substringAfter(" · "), substring = true)
        val jobs = cloud.records(ana.uid).filterIsInstance<RemoteJob>().associate { it.name to it.deleted }
        assertTrue(jobs.toString(), jobs == mapOf("Kawiarnia" to true, "Biuro" to false))
    }

    @Test
    fun anUploadRefusedBecauseOfTheClockSaysToCheckTheDateAndTime() {
        val db = database()
        val job = someData(db, "Kawiarnia")
        val controller = showSettings(db)
        linkInBackground(controller, SignInChoice.UPLOAD)

        // The phone's clock runs 30 minutes ahead; the cloud refuses the next change.
        cloud.refuseWrites = true
        serverNow = now - 30 * 60_000
        runBlocking {
            RoomShiftRepository(db, db.shiftDao(), JobDetailsSource(db.paySettingsDao()), clock)
                .upsert(Shift(jobId = job, date = LocalDate.of(2026, 11, 3), start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3300))
        }
        tap(str(R.string.sync_now))

        val expected = str(R.string.sync_clock_ahead, "30")
        scrollTo(str(R.string.sync_switch))
        waitForText(expected)
        waitForText(str(R.string.sync_pending, 1))
    }
}

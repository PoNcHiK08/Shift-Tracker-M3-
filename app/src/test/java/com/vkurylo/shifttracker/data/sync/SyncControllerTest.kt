package com.vkurylo.shifttracker.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.backup.JsonBackupRepository
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomBonusRepository
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/**
 * When sync runs and what it reports: off until turned on, never during a question or an import, never set off
 * by a download, the daily write limit, a refused upload told apart by the clock, offline retried.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SyncControllerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cloud = FakeRemoteStore()
    private val ana = CloudAccount("uid-ana", "ana@example.com")
    private val phones = mutableListOf<Phone>()
    private val day = LocalDate.of(2026, 11, 2)

    @After
    fun tearDown() {
        phones.forEach { it.db.close() }
        assertEquals("cloud calls on the main thread", 0, cloud.callsOnMainThread)
    }

    private inner class Phone {
        val db: ShiftDatabase = Room.inMemoryDatabaseBuilder(context, ShiftDatabase::class.java).allowMainThreadQueries().build()
        var now = 1_762_000_000_000L
        val clock = WallClock { now }
        val auth = FakeCloudAuth(signedInAs = ana)
        val scheduler = FakeScheduler()
        var serverNow: Long? = null
        val lock = SyncLock()
        val prefs = testSyncPreferences()
        val controller = testSyncController(db, cloud, auth, prefs, scheduler, { serverNow }, clock, lock)
        private val details = JobDetailsSource(db.paySettingsDao())
        val jobs = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details, clock)
        val shifts = RoomShiftRepository(db, db.shiftDao(), details, clock)
        val bonuses = RoomBonusRepository(db, db.bonusDao(), details, clock)

        suspend fun job(name: String = "Kawiarnia") = jobs.upsert(Job(name = name, hourlyRateGrosze = 3300, color = 1, currency = "PLN"))

        suspend fun shift(jobId: Long, date: LocalDate = day) =
            shifts.upsert(Shift(jobId = jobId, date = date, start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3300))

        suspend fun bonus(jobId: Long, amount: Long = 5000) = bonuses.upsert(Bonus(jobId = jobId, date = day, amountGrosze = amount))

        /** Some data: a job, two shifts and a bonus (4 records). */
        suspend fun someData(): Long {
            val job = job()
            shift(job)
            shift(job, day.plusDays(1))
            bonus(job)
            return job
        }

        /** The Sync switch on; [answer] picks the choice when the plan is a question. */
        suspend fun turnOn(answer: (SignInPlan) -> SignInChoice = { (it as SignInPlan.Ready).choice }): SyncOutcome =
            controller.link(answer(controller.prepare()))

        suspend fun settings() = prefs.currentSettings()

        suspend fun waiting() = db.syncDao().dirty().count
    }

    private fun phone() = Phone().also { phones += it }

    private suspend fun eventually(what: String, condition: suspend () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(20)
    }.also { assertTrue(what, condition()) }

    @Test
    fun syncIsOffUntilTurnedOnEvenWhenSignedIn() = runBlocking {
        val a = phone()
        a.someData()

        assertNull(a.controller.syncNow())
        assertEquals(AutomaticRun.DONE, a.controller.runAutomatic())
        a.controller.start()
        a.controller.onForeground()
        a.job("Another") // a local change
        delay(SyncController.LOCAL_CHANGE_DELAY_MS + 500)

        assertEquals(emptyList<String>(), cloud.calls)
        assertEquals(0, a.scheduler.requests)
        assertFalse(a.controller.status.first().enabled)
    }

    @Test
    fun turningOnLinksAndUploadsThenLocalChangesSyncAfterAPause() = runBlocking {
        val a = phone()
        a.someData()

        val first = a.turnOn() // the account is empty: upload
        assertNull(first.problem)
        assertEquals(4, first.uploaded)
        assertTrue(a.settings().enabled)
        assertEquals(4, a.controller.status.first().writesToday)

        a.controller.start()
        a.shift(a.db.jobDao().getAll().single().id, day.plusDays(5))
        eventually("a sync is asked for after the change") { a.scheduler.requests == 1 }
        assertEquals(AutomaticRun.DONE, a.controller.runAutomatic())
        assertEquals(1, a.settings().last!!.uploaded)
        assertEquals(5, a.controller.status.first().writesToday)
        assertEquals(0, a.waiting())
    }

    @Test
    fun aDownloadNeverSetsOffAnUpload() = runBlocking {
        val a = phone()
        val job = a.someData()
        a.turnOn()
        val b = phone()
        assertEquals(SignInPlan.Ready(SignInChoice.DOWNLOAD), b.controller.prepare())
        b.controller.link(SignInChoice.DOWNLOAD)
        b.controller.start()
        delay(SyncController.LOCAL_CHANGE_DELAY_MS + 500)
        val asked = b.scheduler.requests

        a.now += 60_000
        a.shift(job, day.plusDays(9))
        a.controller.syncNow()
        b.now += 120_000
        val download = b.controller.syncNow()!!
        assertEquals(1, download.downloaded)
        assertEquals(0, download.uploaded)

        delay(SyncController.LOCAL_CHANGE_DELAY_MS + 500)
        assertEquals(asked, b.scheduler.requests) // nothing waiting on B, so no upload is asked for
        assertEquals(0, b.waiting())
        val writes = cloud.writes
        b.now += 120_000
        assertEquals(0, b.controller.syncNow()!!.uploaded)
        assertEquals(writes, cloud.writes)
    }

    @Test
    fun nothingSyncsWhileAQuestionIsOpen() = runBlocking {
        val a = phone()
        a.someData()
        a.turnOn()
        val b = phone()
        b.job("Biuro")

        val plan = b.controller.prepare()
        assertTrue(plan is SignInPlan.AskMerge)
        val calls = cloud.calls.size
        assertNull(b.controller.syncNow())
        assertEquals(AutomaticRun.DONE, b.controller.runAutomatic())
        b.controller.onForeground()
        delay(300)
        assertEquals(0, b.scheduler.requests)
        assertEquals(calls, cloud.calls.size)

        // Dismissed: still off.
        b.controller.cancelDecision()
        assertNull(b.controller.syncNow())

        // Answered: merged and on.
        val outcome = b.turnOn { SignInChoice.MERGE }
        assertNull(outcome.problem)
        assertTrue(b.settings().enabled)
        assertEquals(setOf("Kawiarnia", "Biuro"), b.db.jobDao().getAll().map { it.name }.toSet())

        // With sync on, a question opened again (the switch tapped twice) holds syncs too, until answered.
        assertEquals(SignInPlan.Ready(SignInChoice.CONTINUE), b.controller.prepare())
        assertNull(b.controller.syncNow())
        assertEquals(AutomaticRun.DONE, b.controller.runAutomatic())
        b.controller.cancelDecision()
        assertNull(b.controller.syncNow()!!.problem)
    }

    @Test
    fun aJsonImportAndASyncNeverOverlap() = runBlocking {
        val a = phone()
        val job = a.someData()
        a.turnOn()
        val backup = JsonBackupRepository(a.db, a.clock, a.lock)
        val file = backup.exportJson()
        a.now += 60_000
        a.shift(job, day.plusDays(3))

        // A sync stops in the middle of its upload...
        val uploading = CompletableDeferred<Unit>()
        val goOn = CompletableDeferred<Unit>()
        cloud.duringPush = {
            cloud.duringPush = null
            uploading.complete(Unit)
            goOn.await()
        }
        val sync = CoroutineScope(Dispatchers.Default).async { a.controller.syncNow() }
        uploading.await()
        // ...and an import started meanwhile waits for it.
        val import = CoroutineScope(Dispatchers.Default).async { backup.importJson(file) }
        delay(500)
        assertFalse("the import waits for the sync", import.isCompleted)

        goOn.complete(Unit)
        assertNull(sync.await()!!.problem)
        assertEquals(2, import.await().shifts) // finished, after the sync
        // The import's result goes up with the next sync, whole.
        a.now += 60_000
        assertTrue(a.waiting() > 0)
        assertNull(a.controller.syncNow()!!.problem)
        assertEquals(0, a.waiting())
    }

    @Test
    fun automaticSyncsStopAtTheDailyWriteLimitButSyncNowGoesOn() = runBlocking {
        val a = phone()
        val job = a.someData()
        a.turnOn()
        val today = a.settings().writesDay
        a.prefs.updateSettings { it.copy(writesDay = today, writesToday = SyncController.DAILY_AUTOMATIC_WRITES - 2) }
        a.now += 60_000
        repeat(5) { a.shift(job, day.plusDays(10L + it)) }

        assertEquals(AutomaticRun.DONE, a.controller.runAutomatic())
        val limited = a.settings().last!!
        assertEquals(SyncProblem.BUDGET, limited.problem)
        assertEquals(2, limited.uploaded)
        assertEquals(SyncController.DAILY_AUTOMATIC_WRITES, a.controller.status.first().writesToday)
        assertEquals(3, a.waiting())

        // At the limit, automatic syncs don't even ask the cloud.
        val calls = cloud.calls.size
        assertEquals(AutomaticRun.DONE, a.controller.runAutomatic())
        assertEquals(calls, cloud.calls.size)

        // "Sync now" isn't limited.
        assertEquals(3, a.controller.syncNow()!!.uploaded)
        assertEquals(0, a.waiting())

        // The next day automatic syncs write again.
        a.now += 24 * 3_600_000L
        a.shift(job, day.plusDays(20))
        assertEquals(AutomaticRun.DONE, a.controller.runAutomatic())
        assertEquals(1, a.settings().last!!.uploaded)
        assertEquals(1, a.controller.status.first().writesToday)
    }

    @Test
    fun aRefusedUploadSaysWhetherItIsTheClock() = runBlocking {
        val a = phone()
        val job = a.someData()
        a.turnOn()
        a.now += 60_000
        a.shift(job, day.plusDays(4))
        cloud.refuseWrites = true

        // The phone's clock 30 minutes ahead of the server's.
        a.serverNow = a.now - 30 * 60_000
        assertEquals(AutomaticRun.DONE, a.controller.runAutomatic()) // not retried: the user has to act
        assertEquals(SyncProblem.CLOCK, a.settings().last!!.problem)
        assertEquals("30", a.settings().last!!.detail)

        // The clock is right now, but a change was made while it ran 20 minutes ahead.
        a.serverNow = a.now
        a.now += 20 * 60_000
        a.shift(job, day.plusDays(5))
        a.now -= 20 * 60_000
        assertEquals(SyncProblem.CLOCK, a.controller.syncNow()!!.problem)
        assertEquals("20", a.settings().last!!.detail)

        // Refused with the clock right: the rules.
        a.db.shiftDao().getAll().forEach { if (it.updatedAt > a.now) a.db.shiftDao().upsert(it.copy(updatedAt = a.now)) }
        assertEquals(SyncProblem.WRITE_REFUSED, a.controller.syncNow()!!.problem)

        // No server time to compare with.
        a.serverNow = null
        assertEquals(SyncProblem.WRITE_REFUSED_CLOCK_UNKNOWN, a.controller.syncNow()!!.problem)

        // Fixed: it goes up.
        cloud.refuseWrites = false
        assertNull(a.controller.syncNow()!!.problem)
        assertEquals(0, a.waiting())
    }

    @Test
    fun offlineIsRetriedLater() = runBlocking {
        val a = phone()
        val job = a.someData()
        a.turnOn()
        a.now += 60_000
        a.shift(job, day.plusDays(6))
        cloud.reachable = false

        assertEquals(AutomaticRun.RETRY, a.controller.runAutomatic())
        assertEquals(SyncProblem.OFFLINE, a.settings().last!!.problem)
        assertEquals(1, a.waiting())

        cloud.reachable = true
        assertEquals(AutomaticRun.DONE, a.controller.runAutomatic())
        assertNull(a.settings().last!!.problem)
        assertEquals(0, a.waiting())
    }

    @Test
    fun deletedCloudDataStopsSyncUntilTheUserDecides() = runBlocking {
        val a = phone()
        a.someData()
        a.turnOn()
        withContext(Dispatchers.Default) { cloud.deleteAll(ana.uid) } // "Delete my cloud data" on another phone

        assertEquals(AutomaticRun.DONE, a.controller.runAutomatic())
        assertTrue(a.settings().cloudReset)
        assertEquals(SyncProblem.CLOUD_RESET, a.settings().last!!.problem)
        assertTrue(a.scheduler.cancels > 0)
        assertNull(a.controller.syncNow())
        assertTrue(cloud.records(ana.uid).isEmpty())

        // The user answers "Upload again".
        val outcome = a.turnOn { plan ->
            assertEquals(SignInPlan.CloudWasReset, plan)
            SignInChoice.UPLOAD
        }
        assertNull(outcome.problem)
        assertFalse(a.settings().cloudReset)
        assertEquals(4, cloud.records(ana.uid).size)
    }

    @Test
    fun deletingMyCloudDataTurnsSyncOffKeepsThePhonesDataAndTurningOnAgainUploadsItAfresh() = runBlocking {
        val a = phone()
        a.someData()
        a.turnOn()
        val b = phone()
        b.turnOn() // B had nothing: it took the account's data
        assertEquals(4, cloud.records(ana.uid).size)

        assertEquals(4, a.controller.deleteCloudData())

        assertTrue(cloud.records(ana.uid).isEmpty())
        assertNull(cloud.meta(ana.uid))
        assertFalse(a.settings().enabled)
        assertTrue(a.scheduler.cancels > 0)
        assertEquals("this phone keeps its data", listOf(1, 2, 1), listOf(a.db.jobDao().getAll().size, a.db.shiftDao().getAll().size, a.db.bonusDao().getAll().size))
        assertNull("nothing syncs until sync is turned on again", a.controller.syncNow())
        // The other phone stops instead of uploading into the emptied account, and asks.
        assertEquals(AutomaticRun.DONE, b.controller.runAutomatic())
        assertTrue(b.settings().cloudReset)
        assertTrue(cloud.records(ana.uid).isEmpty())

        // Turned on again: this phone is no longer linked and the account is empty, so all of it goes up.
        val outcome = a.turnOn { plan ->
            assertEquals(SignInPlan.Ready(SignInChoice.UPLOAD), plan)
            SignInChoice.UPLOAD
        }
        assertNull(outcome.problem)
        assertEquals(4, cloud.records(ana.uid).size)
    }

    @Test
    fun deletingWhileOfflineFailsKeepsSyncOffAndDeletingAgainFinishes() = runBlocking {
        val a = phone()
        a.someData()
        a.turnOn()

        cloud.reachable = false
        val failure = runCatching { a.controller.deleteCloudData() }.exceptionOrNull()
        assertTrue(failure.toString(), failure is java.io.IOException)
        assertFalse(a.settings().enabled)
        assertEquals(4, cloud.records(ana.uid).size)

        cloud.reachable = true
        assertEquals(4, a.controller.deleteCloudData())
        assertTrue(cloud.records(ana.uid).isEmpty())
    }

    @Test
    fun signingOutTurnsSyncOff() = runBlocking {
        val a = phone()
        a.someData()
        a.turnOn()

        a.controller.signOut()
        assertFalse(a.settings().enabled)
        assertNull(a.auth.currentAccount())
        assertTrue(a.scheduler.cancels > 0)

        // Signed in again: still off until turned on.
        a.auth.onSignIn = { ana }
        a.auth.signIn(context)
        assertNull(a.controller.syncNow())
        assertFalse(a.controller.status.first().enabled)
    }
}

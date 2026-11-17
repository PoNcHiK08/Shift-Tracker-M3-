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
import com.vkurylo.shifttracker.data.repository.RoomPayoutRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.domain.calc.MoneyParser
import com.vkurylo.shifttracker.domain.ics.IcsEvent
import com.vkurylo.shifttracker.domain.ics.ImportPlanner
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Payout
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.TextLimits
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

/**
 * The sync engine end to end against an in-memory cloud: phones with their own Room databases, edited
 * through the app's repositories, syncing with the same account.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SyncEngineTest {

    private val cloud = FakeRemoteStore()
    private val ana = CloudAccount(uid = "uid-ana", email = "ana@example.com")
    private val work = CloudAccount(uid = "uid-work", email = "work@example.com")
    private val phones = mutableListOf<Phone>()

    @After
    fun tearDown() = phones.forEach { it.db.close() }

    private val day = LocalDate.of(2026, 11, 2)
    private val november = YearMonth.of(2026, 11)

    /** One phone: its database, the app's repositories on it, its own clock and the sync engine. */
    private inner class Phone {
        val db: ShiftDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShiftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        var now = 1_000_000L
        val clock = WallClock { now }
        private val details = JobDetailsSource(db.paySettingsDao())
        val jobs = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details, clock)
        val shifts = RoomShiftRepository(db, db.shiftDao(), details, clock)
        val bonuses = RoomBonusRepository(db, db.bonusDao(), details, clock)
        val payouts = RoomPayoutRepository(db, db.payoutDao(), details, clock)
        val state = InMemorySyncStateStore()
        val safetyBackups = mutableListOf<String>()
        val engine = SyncEngine(db, cloud, state, { reason -> safetyBackups += "$reason ${JsonBackupRepository(db).exportJson()}" }, clock)

        /** Signs in; [answer] picks the choice when the plan is a question. */
        suspend fun signIn(account: CloudAccount = ana, answer: (SignInPlan) -> SignInChoice = { (it as SignInPlan.Ready).choice }): SyncResult =
            engine.completeSignIn(account, answer(engine.prepareSignIn(account)))

        suspend fun sync() = engine.sync()

        suspend fun job(name: String = "Kawiarnia", rate: Long = 3300, currency: String = "PLN") =
            jobs.upsert(Job(name = name, hourlyRateGrosze = rate, color = 1, currency = currency))

        suspend fun shift(jobId: Long, date: LocalDate = day, start: Int = 8, end: Int = 16, note: String? = null) = shifts.upsert(
            Shift(jobId = jobId, date = date, start = LocalTime.of(start, 0), end = LocalTime.of(end, 0), hourlyRateGrosze = 3300, note = note),
        )

        suspend fun bonus(jobId: Long, amount: Long = 5000) = bonuses.upsert(Bonus(jobId = jobId, date = day, amountGrosze = amount))

        /** A bonus for the whole [month]. */
        suspend fun monthBonus(jobId: Long, amount: Long = 50_000, month: YearMonth = november) =
            bonuses.upsert(Bonus(jobId = jobId, date = month.atDay(1), amountGrosze = amount, wholeMonth = true))

        suspend fun payout(jobId: Long, amount: Long = 450_000, month: YearMonth = november, note: String? = null) =
            payouts.upsert(Payout(jobId = jobId, month = month, amountMinor = amount, note = note))

        suspend fun shiftBySync(syncId: String) = db.shiftDao().getBySyncId(syncId)!!

        suspend fun dirtyCount() =
            db.syncDao().let { it.dirtyJobs().size + it.dirtyShifts().size + it.dirtyBonuses().size + it.dirtyPayouts().size }

        /** What the app shows, by sync id: equal views on two phones = the same data. */
        suspend fun view(): List<Any?> {
            val jobSync = db.jobDao().getAll().associate { it.id to it.syncId }
            return listOf(
                db.jobDao().getAll().map { listOf(it.syncId, it.name, it.hourlyRateGrosze, it.currency, it.color, it.secondRateGrosze, it.deleted) }
                    .sortedBy { it[0] as String },
                db.paySettingsDao().getAll().map { listOf(jobSync[it.jobId], it.fromMonth, it.payType, it.monthlyAmountMinor, it.deductionBp) }
                    .sortedBy { "${it[0]}${it[1]}" },
                db.shiftDao().getAll().map {
                    listOf(it.syncId, jobSync[it.jobId], it.dateEpochDay, it.startMinute, it.endMinute, it.unpaidBreakMinutes, it.hourlyRateGrosze, it.note, it.externalUid)
                }.sortedBy { it[0] as String },
                db.bonusDao().getAll().map { listOf(it.syncId, jobSync[it.jobId], it.dateEpochDay, it.amountGrosze, it.note, it.wholeMonth) }
                    .sortedBy { it[0] as String },
                db.payoutDao().getAll().map { listOf(it.syncId, jobSync[it.jobId], it.month, it.amountMinor, it.note, it.receivedEpochDay) }
                    .sortedBy { it[0] as String },
            )
        }
    }

    private fun phone() = Phone().also { phones += it }

    /** Two phones linked to [ana] with the same data: a job with two shifts and a bonus. */
    private suspend fun twoLinkedPhones(): Pair<Phone, Phone> {
        val a = phone()
        val job = a.job()
        a.shift(job, note = "rano")
        a.shift(job, date = day.plusDays(1), start = 14, end = 22)
        a.bonus(job)
        a.signIn()
        val b = phone()
        b.signIn()
        assertEquals(a.view(), b.view())
        return a to b
    }

    // --- Linking ----------------------------------------------------------------------------------------

    @Test
    fun firstSignInUploadsEverythingToAnEmptyAccount() = runBlocking {
        val a = phone()
        val job = a.job(rate = MoneyParser.maxMinor(2)) // the largest rate: money stays exact
        a.shift(job)
        a.shift(job, date = day.plusDays(1))
        a.bonus(job, amount = MoneyParser.maxMinor(2, MoneyParser.AMOUNT_WHOLE_DIGITS))

        assertEquals(SignInPlan.Ready(SignInChoice.UPLOAD), a.engine.prepareSignIn(ana))
        val result = a.engine.completeSignIn(ana, SignInChoice.UPLOAD)

        assertEquals(SyncResult.Synced(downloaded = 0, uploaded = 4, skipped = 0), result)
        assertEquals(0, a.dirtyCount())
        assertEquals(ana.uid, a.state.current.boundUid)
        val cloudJob = cloud.records(ana.uid).filterIsInstance<RemoteJob>().single()
        assertEquals(MoneyParser.maxMinor(2), cloudJob.hourlyRateGrosze)
        assertEquals(MoneyParser.maxMinor(2, MoneyParser.AMOUNT_WHOLE_DIGITS), cloud.records(ana.uid).filterIsInstance<RemoteBonus>().single().amountGrosze)
    }

    @Test
    fun aSecondPhoneDownloadsEverything() = runBlocking {
        val a = phone()
        val job = a.job()
        a.jobs.save(a.jobs.getJob(job)!!, com.vkurylo.shifttracker.domain.model.PaySettings(deductionBp = 1371))
        a.shift(job, note = "rano")
        a.signIn()

        val b = phone()
        assertEquals(SignInPlan.Ready(SignInChoice.DOWNLOAD), b.engine.prepareSignIn(ana))
        b.engine.completeSignIn(ana, SignInChoice.DOWNLOAD)

        assertEquals(a.view(), b.view())
        assertEquals(0, b.dirtyCount())
        assertEquals(1371, b.db.paySettingsDao().getAll().single().deductionBp)
    }

    @Test
    fun dataOnBothSidesIsMergedWithoutDuplicatingLookAlikes() = runBlocking {
        val a = phone()
        val kawiarnia = a.job("Kawiarnia")
        a.shift(kawiarnia) // also on phone B, with its own ids (e.g. the same backup restored there)
        a.shift(kawiarnia, date = day.plusDays(1))
        a.bonus(kawiarnia)
        a.signIn()

        val b = phone()
        val kawiarniaOnB = b.job(" kawiarnia")
        b.shift(kawiarniaOnB)
        b.shift(kawiarniaOnB, date = day.plusDays(5), note = "only on B")
        b.job("Biuro")

        val plan = b.engine.prepareSignIn(ana)
        assertEquals(SignInPlan.AskMerge(phone = Counts(2, 2, 0), cloud = Counts(1, 2, 1), lookAlikes = 2), plan)
        b.engine.completeSignIn(ana, SignInChoice.MERGE)
        a.sync()

        assertEquals(a.view(), b.view())
        assertEquals(2, a.db.jobDao().getAll().size)
        assertEquals("look-alike shift once, plus one from each phone", 3, a.db.shiftDao().getAll().size)
        assertEquals(1, a.db.bonusDao().getAll().size)
        assertTrue(b.safetyBackups.isEmpty())
    }

    @Test
    fun takingTheAccountsDataWritesASafetyBackupFirst() = runBlocking {
        val a = phone()
        a.shift(a.job("Kawiarnia"))
        a.signIn()
        val b = phone()
        b.shift(b.job("Tylko na B"))

        assertTrue(b.engine.prepareSignIn(ana) is SignInPlan.AskMerge)
        b.engine.completeSignIn(ana, SignInChoice.USE_CLOUD_ONLY)

        assertEquals(a.view(), b.view())
        assertEquals(1, b.safetyBackups.size)
        assertTrue("the backup holds B's own data", b.safetyBackups.single().contains("Tylko na B"))
        assertTrue(cloud.records(ana.uid).none { it is RemoteJob && it.name == "Tylko na B" })
    }

    @Test
    fun anotherAccountOnThisPhoneIsAskedAboutAndReplacingLeavesBothAccountsAsTheyWere() = runBlocking {
        val a = phone()
        a.shift(a.job("Ana's job"))
        a.signIn(ana)
        val c = phone()
        c.shift(c.job("Work job"))
        c.signIn(work)
        val anaBefore = cloud.records(ana.uid).toSet()
        val workBefore = cloud.records(work.uid).toSet()

        // On phone A, ana signs out (data and link stay) and work signs in.
        val plan = a.engine.prepareSignIn(work)
        assertEquals(SignInPlan.AskAccountSwitch(previousEmail = ana.email, phone = Counts(1, 1, 0), cloud = Counts(1, 1, 0)), plan)
        a.engine.completeSignIn(work, SignInChoice.USE_CLOUD_ONLY)

        assertEquals(c.view(), a.view())
        assertEquals(1, a.safetyBackups.size)
        assertEquals(anaBefore, cloud.records(ana.uid).toSet())
        assertEquals(workBefore, cloud.records(work.uid).toSet())
        assertEquals(work.uid, a.state.current.boundUid)
    }

    @Test
    fun anotherAccountCanTakeThisPhonesDataWithoutChangingTheFirstAccount() = runBlocking {
        val a = phone()
        a.shift(a.job("Ana's job"))
        a.signIn(ana)
        val c = phone()
        c.shift(c.job("Work job"))
        c.signIn(work)
        val anaBefore = cloud.records(ana.uid).toSet()

        a.engine.completeSignIn(work, SignInChoice.MERGE)
        c.sync()

        assertEquals(setOf("Ana's job", "Work job"), cloud.records(work.uid).filterIsInstance<RemoteJob>().map { it.name }.toSet())
        assertEquals(c.view(), a.view())
        assertEquals(anaBefore, cloud.records(ana.uid).toSet())
    }

    @Test
    fun signingOutKeepsTheDataAndChangesMadeMeanwhileSyncOnTheNextSignIn() = runBlocking {
        val (a, b) = twoLinkedPhones()
        // Signed out: the app works as before; nothing syncs.
        a.now += 10_000
        val added = a.shift(a.db.jobDao().getAll().single().id, date = day.plusDays(9), note = "added while signed out")

        assertEquals(SignInPlan.Ready(SignInChoice.CONTINUE), a.engine.prepareSignIn(ana))
        a.engine.completeSignIn(ana, SignInChoice.CONTINUE)
        b.sync()

        assertEquals(a.view(), b.view())
        assertEquals("added while signed out", b.shiftBySync(a.db.shiftDao().getAny(added)!!.syncId).note)
    }

    @Test
    fun deletingCloudDataUnlinksThisPhoneAndMakesOtherPhonesAskInsteadOfUploadingHalfTheData() = runBlocking {
        val (a, b) = twoLinkedPhones()
        val aBefore = a.view()

        assertEquals("records deleted", 1 + 2 + 1, a.engine.deleteCloudData(ana))

        assertTrue(cloud.records(ana.uid).isEmpty())
        assertNull(cloud.meta(ana.uid))
        assertNull(a.state.current.boundUid)
        assertEquals("this phone keeps its data", aBefore, a.view())

        // Phone B changes something and syncs: it must not quietly upload just that change.
        b.now += 10_000
        b.shift(b.db.jobDao().getAll().single().id, date = day.plusDays(3))
        assertEquals(SyncResult.CloudWasReset, b.sync())
        assertTrue(cloud.records(ana.uid).isEmpty())
        assertEquals(SignInPlan.CloudWasReset, b.engine.prepareSignIn(ana))

        // The user chooses to upload B's data again: all of it goes up.
        b.engine.unlink()
        assertEquals(SignInPlan.Ready(SignInChoice.UPLOAD), b.engine.prepareSignIn(ana))
        b.engine.completeSignIn(ana, SignInChoice.UPLOAD)
        assertEquals(1 + 3 + 1, cloud.records(ana.uid).size)
    }

    @Test
    fun deletingAnotherAccountsCloudDataLeavesThisPhonesLinkAlone() = runBlocking {
        val (a, b) = twoLinkedPhones()
        val other = phone()
        other.job("Biuro")
        other.signIn(work)

        // Signed in as "work" on phone A (still linked to ana) and deleting work's cloud data.
        assertEquals(1, a.engine.deleteCloudData(work))

        assertTrue(cloud.records(work.uid).isEmpty())
        assertEquals(ana.uid, a.state.current.boundUid)
        a.now += 10_000
        a.shift(a.db.jobDao().getAll().single().id, date = day.plusDays(5))
        assertTrue(a.sync() is SyncResult.Synced)
        b.sync()
        assertEquals(a.view(), b.view())
    }

    // --- Day-to-day sync ------------------------------------------------------------------------------

    @Test
    fun changesAndDeletionsReachTheOtherPhone() = runBlocking {
        val (a, b) = twoLinkedPhones()
        val jobId = a.db.jobDao().getAll().single().id
        a.now += 10_000
        val morning = a.shifts.getAllShifts().first()
        a.shifts.upsert(morning.copy(note = "zmieniona", unpaidBreakMinutes = 15))
        a.bonus(jobId, amount = 2500)
        a.sync()
        assertEquals(SyncResult.Synced(downloaded = 2, uploaded = 0, skipped = 0), b.sync())
        assertEquals(a.view(), b.view())

        b.now += 20_000
        b.shifts.delete(b.shifts.getAllShifts().last())
        b.sync()
        a.sync()
        assertEquals(a.view(), b.view())
        assertEquals(1, a.shifts.getAllShifts().size)
        assertTrue(cloud.records(ana.uid).filterIsInstance<RemoteShift>().any { it.deleted })

        // Deleting the job deletes its entries everywhere.
        a.now += 30_000
        a.jobs.delete(a.jobs.getJob(jobId)!!)
        a.sync()
        b.sync()
        assertEquals(a.view(), b.view())
        assertTrue(b.db.jobDao().getAll().isEmpty())
        assertTrue(b.shifts.getAllShifts().isEmpty())
    }

    @Test
    fun concurrentEditsOfOneRecordKeepTheLaterEditOnEveryPhone() = runBlocking {
        val (a, b) = twoLinkedPhones()
        val syncId = a.db.shiftDao().getAll().first().syncId

        // Both edit the same shift offline; B's edit is later. A syncs first.
        a.now = 2_000_000; a.shifts.upsert(a.shifts.getAllShifts().first().copy(note = "A"))
        b.now = 3_000_000; b.shifts.upsert(b.shifts.getAllShifts().first().copy(note = "B"))
        a.sync(); b.sync(); a.sync()
        assertEquals("B", a.shiftBySync(syncId).note)
        assertEquals(a.view(), b.view())

        // The other way round: A's edit is later, but B syncs first.
        b.now = 4_000_000; b.shifts.upsert(b.shifts.getAllShifts().first().copy(note = "B2"))
        a.now = 5_000_000; a.shifts.upsert(a.shifts.getAllShifts().first().copy(note = "A2"))
        b.sync(); a.sync(); b.sync()
        assertEquals("A2", b.shiftBySync(syncId).note)
        assertEquals(a.view(), b.view())
        assertEquals(0, a.dirtyCount() + b.dirtyCount())
    }

    @Test
    fun anEditOnAPhoneWhoseClockIsBehindStillBeatsTheVersionItEdited() = runBlocking {
        val (a, b) = twoLinkedPhones()
        val syncId = a.db.shiftDao().getAll().first().syncId
        b.now = 10_000_000
        b.shifts.upsert(b.shifts.getAllShifts().first().copy(note = "B"))
        b.sync()

        a.now = 5_000_000 // A's clock is far behind B's
        a.sync()
        a.shifts.upsert(a.shifts.getAllShifts().first().copy(note = "A edits B's version"))
        a.sync()
        b.sync()

        assertEquals(10_000_001L, a.shiftBySync(syncId).updatedAt)
        assertEquals("A edits B's version", b.shiftBySync(syncId).note)
    }

    @Test
    fun aTieGoesToTheCloudsVersionOnEveryPhone() = runBlocking {
        val (a, b) = twoLinkedPhones()
        val syncId = a.db.shiftDao().getAll().first().syncId
        a.now = 7_000_000; a.shifts.upsert(a.shifts.getAllShifts().first().copy(note = "A"))
        b.now = 7_000_000; b.shifts.upsert(b.shifts.getAllShifts().first().copy(note = "B"))
        a.sync(); b.sync(); a.sync()

        assertEquals("A", b.shiftBySync(syncId).note)
        assertEquals(a.view(), b.view())
    }

    /** User's rule: a shift added after its job was deleted on another phone brings the job back. */
    @Test
    fun aShiftAddedAfterItsJobWasDeletedElsewhereBringsTheJobBackWithOnlyThatShift() = runBlocking {
        val (a, b) = twoLinkedPhones()
        val jobSyncId = a.db.jobDao().getAll().single().syncId
        a.now = 2_000_000
        a.jobs.delete(a.jobs.getJob(a.db.jobDao().getAll().single().id)!!)
        a.sync()

        // B, offline, still has the job and adds a shift to it after A deleted it.
        b.now = 3_000_000
        val added = b.shift(b.db.jobDao().getAll().single().id, date = day.plusDays(7), note = "after the deletion")
        b.sync()
        a.sync()

        assertEquals(a.view(), b.view())
        assertEquals(listOf(jobSyncId), a.db.jobDao().getAll().map { it.syncId })
        assertEquals(listOf("after the deletion"), a.shifts.getAllShifts().map { it.note })
        assertTrue("the bonus deleted with the job stays deleted", a.db.bonusDao().getAll().isEmpty())
        assertEquals(b.db.shiftDao().getAny(added)!!.syncId, a.db.shiftDao().getAll().single().syncId)
    }

    @Test
    fun aShiftAddedBeforeItsJobWasDeletedElsewhereIsDeletedWithIt() = runBlocking {
        val (a, b) = twoLinkedPhones()
        b.now = 1_500_000
        b.shift(b.db.jobDao().getAll().single().id, date = day.plusDays(7), note = "before the deletion") // B offline
        a.now = 2_000_000
        a.jobs.delete(a.jobs.getJob(a.db.jobDao().getAll().single().id)!!)
        a.sync()
        b.sync()
        a.sync()

        assertEquals(a.view(), b.view())
        assertTrue(b.db.jobDao().getAll().isEmpty())
        assertTrue(b.shifts.getAllShifts().isEmpty())
        assertTrue(cloud.records(ana.uid).filterIsInstance<RemoteShift>().all { it.deleted })
    }

    /**
     * A third phone with nothing of its own to upload downloads both sides of that conflict at once (the
     * job's deletion from one phone, shifts for it from another): it applies the same rule on its own.
     */
    @Test
    fun aPhoneThatOnlyDownloadsAppliesTheJobRuleToo() = runBlocking {
        val (_, b) = twoLinkedPhones()
        val job = b.db.jobDao().getAll().single()
        val jobDeleted = job.toRemote(emptyList()).copy(updatedAt = 2_000_000, deleted = true)
        val newer = RemoteShift("20000000-0000-4000-8000-000000000001", 3_000_000, false, job.syncId, 20_500, 480, 960, 0, 3300, "after", null, null)
        val older = newer.copy(syncId = "20000000-0000-4000-8000-000000000002", updatedAt = 1_500_000, dateEpochDay = 20_501, note = "before")
        // Other phones' changes: the job deleted at 2_000_000 (with its old entries), one shift added later, one earlier.
        cloud.put(ana.uid, jobDeleted)
        cloud.records(ana.uid).filterIsInstance<RemoteShift>().forEach { cloud.put(ana.uid, it.copy(updatedAt = 2_000_000, deleted = true)) }
        cloud.records(ana.uid).filterIsInstance<RemoteBonus>().forEach { cloud.put(ana.uid, it.copy(updatedAt = 2_000_000, deleted = true)) }
        cloud.put(ana.uid, newer)
        cloud.put(ana.uid, older)

        b.sync()

        val revived = b.db.jobDao().getAll().single()
        assertEquals(job.syncId, revived.syncId)
        assertEquals("revived at the newer shift's time", 3_000_000L, revived.updatedAt)
        assertEquals(listOf("after"), b.shifts.getAllShifts().map { it.note })
        assertTrue(b.shiftBySync(older.syncId).deleted)
        // ...and tells the cloud, so every phone ends up the same.
        assertFalse((cloud.records(ana.uid).single { it.syncId == job.syncId } as RemoteJob).deleted)
        assertTrue((cloud.records(ana.uid).single { it.syncId == older.syncId } as RemoteShift).deleted)
    }

    @Test
    fun anEditMadeWhileItsUploadIsOnTheWayIsNotLost() = runBlocking {
        val (a, b) = twoLinkedPhones()
        val shift = a.shifts.getAllShifts().first()
        val syncId = a.db.shiftDao().getAny(shift.id)!!.syncId
        a.now += 10_000
        a.shifts.upsert(shift.copy(note = "first"))
        cloud.duringPush = {
            cloud.duringPush = null
            a.now += 1000
            a.shifts.upsert(a.shifts.getShift(shift.id)!!.copy(note = "second, during the upload"))
        }

        a.sync()
        b.sync()

        assertEquals("second, during the upload", (cloud.records(ana.uid).single { it.syncId == syncId } as RemoteShift).note)
        assertEquals("second, during the upload", b.shiftBySync(syncId).note)
        assertEquals(0, a.dirtyCount())
    }

    @Test
    fun aNewerVersionStoredDuringTheUploadIsTakenNotOverwritten() = runBlocking {
        val (a, b) = twoLinkedPhones()
        val syncId = a.db.shiftDao().getAll().first().syncId
        a.now = 2_000_000; a.shifts.upsert(a.shifts.getAllShifts().first().copy(note = "A"))
        b.now = 3_000_000; b.shifts.upsert(b.shifts.getAllShifts().first().copy(note = "B, later"))
        // B uploads its later edit between A's download and A's upload.
        cloud.duringPush = {
            cloud.duringPush = null
            b.sync()
        }

        a.sync()

        assertEquals("B, later", a.shiftBySync(syncId).note)
        assertEquals("B, later", (cloud.records(ana.uid).single { it.syncId == syncId } as RemoteShift).note)
        assertEquals(0, a.dirtyCount())
        assertEquals(a.view(), b.view())
    }

    @Test
    fun withoutANetworkSyncFailsAndKeepsEveryChangeForLater() = runBlocking {
        val (a, b) = twoLinkedPhones()
        a.now += 10_000
        a.shift(a.db.jobDao().getAll().single().id, date = day.plusDays(4), note = "offline")
        cloud.reachable = false

        assertThrows(IOException::class.java) { runBlocking { a.sync() } }
        assertEquals(1, a.dirtyCount())

        cloud.reachable = true
        a.sync()
        b.sync()
        assertEquals(a.view(), b.view())
    }

    @Test
    fun syncingAgainChangesNothing() = runBlocking {
        val (a, b) = twoLinkedPhones()
        assertEquals(SyncResult.Synced(0, 0, 0), a.sync())
        assertEquals(SyncResult.Synced(0, 0, 0), b.sync())
    }

    @Test
    fun theSameCalendarFileImportedOnTwoPhonesBeforeLinkingGivesOneShiftPerEvent() = runBlocking {
        val events = listOf(
            IcsEvent("e1@grafik", "Zmiana", null, LocalDateTime.of(2026, 11, 9, 8, 0), LocalDateTime.of(2026, 11, 9, 16, 0)),
            IcsEvent(null, "Zmiana", null, LocalDateTime.of(2026, 11, 10, 6, 0), LocalDateTime.of(2026, 11, 10, 14, 0)),
        )
        val a = phone()
        val sklepOnA = a.jobs.getJob(a.job("Sklep"))!!
        a.shifts.importFromCalendar(events.map { ImportPlanner.toShift(it, sklepOnA) })
        a.signIn()
        val b = phone()
        val sklepOnB = b.jobs.getJob(b.job("Sklep"))!!
        b.shifts.importFromCalendar(events.map { ImportPlanner.toShift(it, sklepOnB) })

        b.signIn { SignInChoice.MERGE }
        a.sync()

        assertEquals(a.view(), b.view())
        assertEquals(2, a.shifts.getAllShifts().size)
        assertEquals(1, b.db.jobDao().getAll().size)
    }

    @Test
    fun aBackupImportedOnOnePhoneReplacesTheDataOnTheOtherToo() = runBlocking {
        val (a, b) = twoLinkedPhones()
        val backup = JsonBackupRepository(b.db).exportJson()
        a.now += 10_000
        a.shift(a.db.jobDao().getAll().single().id, date = day.plusDays(20), note = "not in the backup")
        a.sync()
        b.sync()

        b.now += 20_000
        JsonBackupRepository(b.db, b.clock).importJson(backup)
        b.sync()
        a.sync()

        assertEquals(a.view(), b.view())
        assertTrue(a.shifts.getAllShifts().none { it.note == "not in the backup" })
        assertEquals(2, a.shifts.getAllShifts().size)
    }

    @Test
    fun aTextLongerThanTheCloudAllowsGoesUpCutAndHoldsUpNothing() = runBlocking {
        val (a, b) = twoLinkedPhones()
        val job = a.db.jobDao().getAll().single().id
        // Saved before the editors had a limit (or by an import): the cloud refuses more than 20 000.
        val long = "ї".repeat(TextLimits.MAX_LENGTH + 500)
        a.now += 10_000
        val legacy = a.shift(job, date = day.plusDays(7), note = long)
        a.shift(job, date = day.plusDays(8), note = "sent with it")

        assertEquals(2, (a.sync() as SyncResult.Synced).uploaded)
        b.sync()

        // This phone keeps its full text; the cloud and the other phone have the first 20 000 characters.
        val syncId = a.db.shiftDao().getAny(legacy)!!.syncId
        assertEquals(long, a.shiftBySync(syncId).note)
        assertEquals(TextLimits.cut(long), b.shiftBySync(syncId).note)
        assertEquals("sent with it", b.db.shiftDao().getAll().single { it.dateEpochDay == day.plusDays(8).toEpochDay() }.note)
        // Settled: the two versions count as the same, so nothing goes up again.
        val writes = cloud.writes
        assertEquals(0, (a.sync() as SyncResult.Synced).uploaded)
        assertEquals(0, (b.sync() as SyncResult.Synced).uploaded)
        assertEquals(writes, cloud.writes)
        assertEquals(0, a.dirtyCount())
    }

    @Test
    fun aShiftThatArrivesWithoutItsJobFetchesTheJob() = runBlocking {
        val (a, b) = twoLinkedPhones()
        a.now += 10_000
        val newJob = a.job("Nowa praca")
        a.sync()
        // B's last download is already past the job's upload (as if B had missed it).
        b.state.current = b.state.current.copy(cursor = cloud.serverTime + SyncEngine.PULL_OVERLAP_MS + 1)
        a.now += 10_000
        a.shift(newJob, date = day.plusDays(2), note = "for the new job")
        a.sync()

        b.sync()

        assertEquals(a.view(), b.view())
        assertEquals("Nowa praca", b.db.jobDao().getAll().single { it.name == "Nowa praca" }.name)
    }

    // --- An app update that changes what is synced ---------------------------------------------------

    /**
     * Phone B synced with an app version that didn't read payouts while A, already updated, sent one: B's cursor
     * went past it. Returns B, its cursor, and the payout B hasn't got.
     */
    private suspend fun phoneThatMissedAPayout(format: String?): Triple<Phone, Long, RemotePayout> {
        val (a, b) = twoLinkedPhones()
        val payout = RemotePayout(
            "30000000-0000-4000-8000-000000000001", a.now, false, a.db.jobDao().getAll().single().syncId, 202611, 450_000, null, null,
        )
        cloud.put(ana.uid, payout)
        val cursor = cloud.serverTime + SyncEngine.PULL_OVERLAP_MS + 1
        b.state.current = b.state.current.copy(cursor = cursor, format = format)
        return Triple(b, cursor, payout)
    }

    @Test
    fun aChangedSyncFormatReadsEverythingOnceAndRemembersTheFormat() = runBlocking {
        val (b, _, _) = phoneThatMissedAPayout(format = "jobs,shifts,bonuses/1")
        val pullsBefore = cloud.pulls.size

        b.sync()
        assertEquals(SYNC_FORMAT, b.state.current.format)
        assertEquals("the cursor is the newest server time read", cloud.serverTime, b.state.current.cursor)
        b.sync()

        // One full download, then from the cursor again.
        assertEquals(listOf(null, cloud.serverTime - SyncEngine.PULL_OVERLAP_MS), cloud.pulls.drop(pullsBefore))
    }

    @Test
    fun anUnchangedSyncFormatKeepsTheCursor() = runBlocking {
        val (b, cursor, _) = phoneThatMissedAPayout(format = SYNC_FORMAT)
        val pullsBefore = cloud.pulls.size

        b.sync()

        assertEquals(listOf<Long?>(cursor - SyncEngine.PULL_OVERLAP_MS), cloud.pulls.drop(pullsBefore))
        assertEquals(cursor, b.state.current.cursor)
        assertTrue("nothing behind the cursor is read", b.db.payoutDao().getAll().isEmpty())
    }

    @Test
    fun recordsMissedBeforeAnUpdateArriveAfterIt() = runBlocking {
        // A phone updated from a version without the sync format remembers none: the real case after this update.
        val (b, _, payout) = phoneThatMissedAPayout(format = null)

        b.sync()

        val stored = b.db.payoutDao().getAll().single()
        assertEquals(payout.syncId, stored.syncId)
        assertEquals(450_000L, stored.amountMinor)
        assertEquals(SYNC_FORMAT, b.state.current.format)
    }

    @Test
    fun recordsThisAppCantUseAreSkippedAndNeverWritten() = runBlocking {
        val (a, _) = twoLinkedPhones()
        val jobSyncId = a.db.jobDao().getAll().single().syncId
        val good = RemoteShift("10000000-0000-4000-8000-000000000001", 9_000_000, false, jobSyncId, 20_500, 480, 960, 0, 3300, "fine", null, null)
        cloud.put(ana.uid, good)
        cloud.put(ana.uid, good.copy(syncId = "10000000-0000-4000-8000-000000000002", schema = DOC_SCHEMA + 1)) // a newer app's layout
        cloud.put(ana.uid, good.copy(syncId = "10000000-0000-4000-8000-000000000003", unpaidBreakMinutes = 600)) // break longer than the shift
        cloud.put(ana.uid, RemoteJob("10000000-0000-4000-8000-000000000004", 9_000_000, false, " ", 3300, 1, null, "PLN", emptyList()))
        cloud.put(ana.uid, good.copy(syncId = "not-a-uuid"))

        val result = a.sync()

        assertEquals(SyncResult.Synced(downloaded = 1, uploaded = 0, skipped = 4), result)
        assertEquals(3, a.shifts.getAllShifts().size)
        assertFalse(a.shifts.getAllShifts().any { it.unpaidBreakMinutes == 600 })
    }

    // --- No ping-pong, and the write limit ------------------------------------------------------------

    @Test
    fun aDownloadedChangeIsNeverUploadedBack() = runBlocking {
        val (a, b) = twoLinkedPhones()
        a.now += 1_000
        // A changes a job, adds a shift and a bonus.
        val job = a.db.jobDao().getAll().single()
        a.jobs.upsert(Job(id = job.id, name = "Kawiarnia (centrum)", hourlyRateGrosze = job.hourlyRateGrosze, color = job.color, currency = job.currency))
        a.shift(job.id, date = day.plusDays(7), note = "new on A")
        a.bonus(job.id, amount = 1_234)
        assertEquals(3, (a.sync() as SyncResult.Synced).uploaded)
        val writesBefore = cloud.writes

        // B takes them in, stored as uploaded: nothing of them is waiting on B, so this sync offers the cloud
        // nothing back (not even versions it would keep).
        b.now += 5_000
        var calls = cloud.calls.size
        val download = b.sync() as SyncResult.Synced
        assertEquals(3, download.downloaded)
        assertEquals(0, download.uploaded)
        assertEquals(0, b.dirtyCount())
        assertFalse(cloud.calls.drop(calls).contains("push"))

        // Another sync on each phone: nothing is offered to the cloud, and it gets no write.
        calls = cloud.calls.size
        b.now += 5_000
        assertEquals(SyncResult.Synced(0, 0, 0), b.sync())
        a.now += 5_000
        assertEquals(SyncResult.Synced(0, 0, 0), a.sync())
        assertFalse(cloud.calls.drop(calls).contains("push"))
        assertEquals(writesBefore, cloud.writes)
        assertEquals(a.view(), b.view())
    }

    @Test
    fun uploadsStopAtTheWriteLimitAndTheRestWaitsForTheNextSync() = runBlocking {
        val a = phone()
        val job = a.job()
        a.shift(job)
        a.shift(job, date = day.plusDays(1))
        a.shift(job, date = day.plusDays(2))
        a.bonus(job)
        a.signIn(answer = { SignInChoice.UPLOAD }) // links; uploads all 5
        a.now += 1_000
        // Five new changes, a limit of 2.
        a.shift(job, date = day.plusDays(3))
        a.shift(job, date = day.plusDays(4))
        a.shift(job, date = day.plusDays(5))
        a.bonus(job, amount = 1_000)
        a.bonus(job, amount = 2_000)
        val counted = mutableListOf<Int>()

        val first = a.engine.sync(maxWrites = 2) { counted += it } as SyncResult.Synced
        assertEquals(2, first.uploaded)
        assertTrue(first.limited)
        assertEquals(3, a.dirtyCount())
        assertEquals(2, counted.sum())

        val rest = a.engine.sync(maxWrites = 100) { counted += it } as SyncResult.Synced
        assertEquals(3, rest.uploaded)
        assertFalse(rest.limited)
        assertEquals(0, a.dirtyCount())
        assertEquals(5, counted.sum())
    }

    // --- Month bonuses and received pay -----------------------------------------------------------------

    @Test
    fun aMonthBonusKeepsItsMarkOnTheOtherPhoneAndIsNotSentBack() = runBlocking {
        val a = phone()
        val job = a.job()
        a.monthBonus(job, amount = 50_000)
        a.bonus(job, amount = 5_000)
        assertEquals(SyncResult.Synced(downloaded = 0, uploaded = 3, skipped = 0), a.signIn())
        assertEquals(setOf(true, false), cloud.records(ana.uid).filterIsInstance<RemoteBonus>().map { it.wholeMonth }.toSet())

        val b = phone()
        val callsBeforeB = cloud.calls.size
        assertEquals(SyncResult.Synced(downloaded = 3, uploaded = 0, skipped = 0), b.signIn())
        assertFalse("what B downloaded is not offered back", cloud.calls.drop(callsBeforeB).contains("push"))
        assertEquals(a.view(), b.view())
        assertEquals(mapOf(true to november.atDay(1).toEpochDay(), false to day.toEpochDay()),
            b.db.bonusDao().getAll().associate { it.wholeMonth to it.dateEpochDay })
        assertEquals(0, b.dirtyCount())

        val writes = cloud.writes
        b.now += 5_000
        assertEquals(SyncResult.Synced(0, 0, 0), b.sync())
        a.now += 5_000
        assertEquals(SyncResult.Synced(0, 0, 0), a.sync())
        assertEquals("nothing is sent back", writes, cloud.writes)
    }

    @Test
    fun switchingABonusBetweenADayAndAMonthReachesTheOtherPhone() = runBlocking {
        val (a, b) = twoLinkedPhones()
        val bonus = a.bonuses.getBonus(a.db.bonusDao().getAll().single().id)!!

        a.now += 10_000
        a.bonuses.upsert(bonus.copy(wholeMonth = true))
        a.sync()
        b.sync()
        assertEquals(a.view(), b.view())
        assertTrue(b.db.bonusDao().getAll().single().wholeMonth)

        b.now += 20_000
        b.bonuses.upsert(b.bonuses.getBonus(b.db.bonusDao().getAll().single().id)!!.copy(wholeMonth = false, date = day.plusDays(3)))
        b.sync()
        a.sync()
        assertEquals(a.view(), b.view())
        assertFalse(a.db.bonusDao().getAll().single().wholeMonth)
        assertEquals(day.plusDays(3).toEpochDay(), a.db.bonusDao().getAll().single().dateEpochDay)
    }

    @Test
    fun payoutsTravelToTheOtherPhoneAndAreNeverSentBack() = runBlocking {
        val a = phone()
        val job = a.job()
        a.payout(job, amount = 400_000)
        a.payout(job, amount = 50_000, note = "reszta")
        assertEquals("the job and two payouts wait for upload", 3, a.db.syncDao().dirty().count)

        assertEquals(SyncResult.Synced(downloaded = 0, uploaded = 3, skipped = 0), a.signIn())
        assertEquals(0, a.db.syncDao().dirty().count)
        val cloudPayouts = cloud.records(ana.uid).filterIsInstance<RemotePayout>()
        assertEquals(setOf(400_000L, 50_000L), cloudPayouts.map { it.amountMinor }.toSet())
        assertTrue(cloudPayouts.all { it.month == 202611 && it.jobSyncId == a.db.jobDao().getAll().single().syncId })

        val b = phone()
        val callsBeforeB = cloud.calls.size
        assertEquals(SyncResult.Synced(downloaded = 3, uploaded = 0, skipped = 0), b.signIn())
        assertFalse("what B downloaded is not offered back", cloud.calls.drop(callsBeforeB).contains("push"))
        assertEquals(a.view(), b.view())
        assertEquals("stored as uploaded", 0, b.dirtyCount())
        assertEquals(0, b.db.syncDao().dirty().count)

        val writes = cloud.writes
        val calls = cloud.calls.size
        b.now += 5_000
        assertEquals(SyncResult.Synced(0, 0, 0), b.sync())
        a.now += 5_000
        assertEquals(SyncResult.Synced(0, 0, 0), a.sync())
        assertEquals("nothing is sent back", writes, cloud.writes)
        assertFalse(cloud.calls.drop(calls).contains("push"))
    }

    @Test
    fun aPayoutWithItsReceivedDateAndNoteArrivesUnchanged() = runBlocking {
        val a = phone()
        val job = a.job()
        a.payouts.upsert(Payout(jobId = job, month = november, amountMinor = 123_456, note = "Przelew", receivedOn = LocalDate.of(2026, 12, 2)))
        a.payouts.upsert(Payout(jobId = job, month = YearMonth.of(2026, 12), amountMinor = 1))
        a.signIn()
        val b = phone()
        b.signIn()

        assertEquals(a.view(), b.view())
        val onB = b.payouts.observePayouts(november, YearMonth.of(2026, 12)).first().map { it.payout }
        assertEquals(listOf(LocalDate.of(2026, 12, 2), null), onB.map { it.receivedOn })
        assertEquals(listOf("Przelew", null), onB.map { it.note })
    }

    @Test
    fun concurrentEditsOfOnePayoutKeepTheLaterAndPaymentsAddedOnTwoPhonesAreBothKept() = runBlocking {
        val (a, b) = twoLinkedPhones()
        val job = a.db.jobDao().getAll().single().id
        a.now += 1_000
        a.payout(job, amount = 400_000)
        a.sync()
        b.sync()
        val syncId = a.db.payoutDao().getAll().single().syncId

        // Both edit the same payout offline; B's edit is later. And each adds a payment of its own.
        a.now = 5_000_000
        a.payouts.upsert(a.payouts.getPayout(a.db.payoutDao().getAll().single().id)!!.copy(amountMinor = 410_000))
        a.payout(job, amount = 1_000, note = "A")
        b.now = 6_000_000
        b.payouts.upsert(b.payouts.getPayout(b.db.payoutDao().getAll().single().id)!!.copy(amountMinor = 420_000, note = "B edits"))
        b.payout(job, amount = 2_000, note = "B")
        a.sync(); b.sync(); a.sync()

        assertEquals(a.view(), b.view())
        assertEquals(420_000L, a.db.payoutDao().getBySyncId(syncId)!!.amountMinor)
        assertEquals("B edits", a.db.payoutDao().getBySyncId(syncId)!!.note)
        assertEquals(listOf(420_000L, 1_000L, 2_000L), a.db.payoutDao().getAll().map { it.amountMinor })
        assertEquals(0, a.dirtyCount() + b.dirtyCount())
    }

    @Test
    fun deletingAPayoutReachesTheOtherPhoneAndUndoBringsItBack() = runBlocking {
        val (a, b) = twoLinkedPhones()
        val job = a.db.jobDao().getAll().single().id
        a.now += 1_000
        a.payout(job)
        a.sync()
        b.sync()
        val payout = a.payouts.getPayout(a.db.payoutDao().getAll().single().id)!!

        a.now += 10_000
        a.payouts.delete(payout)
        a.sync()
        b.sync()
        assertTrue(b.db.payoutDao().getAll().isEmpty())
        assertTrue("a tombstone, not a missing document", cloud.records(ana.uid).filterIsInstance<RemotePayout>().single().deleted)
        assertEquals(a.view(), b.view())

        a.now += 10_000
        a.payouts.upsert(payout) // Undo
        a.sync()
        b.sync()
        assertEquals(listOf(payout.amountMinor), b.db.payoutDao().getAll().map { it.amountMinor })
        assertEquals(a.view(), b.view())
    }

    @Test
    fun deletingAJobDeletesItsPayoutsOnTheOtherPhoneToo() = runBlocking {
        val (a, b) = twoLinkedPhones()
        val job = a.db.jobDao().getAll().single().id
        a.now += 1_000
        a.payout(job)
        a.payout(job, amount = 1_000, month = YearMonth.of(2026, 10))
        a.sync()
        b.sync()
        assertEquals(2, b.db.payoutDao().getAll().size)

        a.now += 10_000
        a.jobs.delete(a.jobs.getJob(job)!!)
        a.sync()
        b.sync()

        assertEquals(a.view(), b.view())
        assertTrue(b.db.payoutDao().getAll().isEmpty())
        assertEquals(2, cloud.records(ana.uid).filterIsInstance<RemotePayout>().count { it.deleted })
    }

    @Test
    fun aPayoutAddedAfterItsJobWasDeletedElsewhereBringsTheJobBackWithOnlyThatPayout() = runBlocking {
        val (a, b) = twoLinkedPhones()
        val job = a.db.jobDao().getAll().single().id
        a.now += 1_000
        a.payout(job, amount = 1_000, note = "before the deletion")
        a.sync()
        b.sync()
        val jobSyncId = a.db.jobDao().getAll().single().syncId
        a.now = 20_000_000
        a.jobs.delete(a.jobs.getJob(job)!!)
        a.sync()

        // B, offline, still has the job and adds a payout to it after A deleted it.
        b.now = 30_000_000
        b.payout(b.db.jobDao().getAll().single().id, amount = 2_000, note = "after the deletion")
        b.sync()
        a.sync()

        assertEquals(a.view(), b.view())
        assertEquals(listOf(jobSyncId), a.db.jobDao().getAll().map { it.syncId })
        assertEquals(listOf("after the deletion"), a.db.payoutDao().getAll().map { it.note })
        assertTrue("the shifts and bonus deleted with the job stay deleted", a.shifts.getAllShifts().isEmpty() && a.db.bonusDao().getAll().isEmpty())
    }

    @Test
    fun aPayoutAddedBeforeItsJobWasDeletedElsewhereIsDeletedWithIt() = runBlocking {
        val (a, b) = twoLinkedPhones()
        b.now = 1_500_000
        b.payout(b.db.jobDao().getAll().single().id, note = "before the deletion") // B offline
        a.now = 2_000_000
        a.jobs.delete(a.jobs.getJob(a.db.jobDao().getAll().single().id)!!)
        a.sync()
        b.sync()
        a.sync()

        assertEquals(a.view(), b.view())
        assertTrue(b.db.jobDao().getAll().isEmpty())
        assertTrue(b.db.payoutDao().getAll().isEmpty())
        assertTrue(cloud.records(ana.uid).filterIsInstance<RemotePayout>().single().deleted)
    }

    @Test
    fun aPayoutThatArrivesWithoutItsJobFetchesTheJob() = runBlocking {
        val (a, b) = twoLinkedPhones()
        a.now += 10_000
        val newJob = a.job("Nowa praca")
        a.sync()
        // B's last download is already past the job's upload (as if B had missed it).
        b.state.current = b.state.current.copy(cursor = cloud.serverTime + SyncEngine.PULL_OVERLAP_MS + 1)
        a.now += 10_000
        a.payout(newJob, amount = 7_700)
        a.sync()

        b.sync()

        assertEquals(a.view(), b.view())
        assertEquals(listOf(7_700L), b.db.payoutDao().getAll().map { it.amountMinor })
        assertEquals("Nowa praca", b.db.jobDao().getAll().single { it.name == "Nowa praca" }.name)
    }

    @Test
    fun lookAlikePayoutsAndMonthBonusesAreLinkedOnFirstMergeButADayBonusIsNotAMonthBonus() = runBlocking {
        val a = phone()
        val kawiarnia = a.job("Kawiarnia")
        a.payout(kawiarnia, amount = 450_000)
        a.monthBonus(kawiarnia, amount = 50_000)
        a.signIn()

        val b = phone()
        val onB = b.job(" kawiarnia")
        b.payout(onB, amount = 450_000) // the same payment, with its own sync id
        b.payout(onB, amount = 10_000) // only on B
        b.monthBonus(onB, amount = 50_000) // the same month bonus
        // A bonus on the 1st with the same amount is another record: it isn't linked to the month bonus.
        b.bonuses.upsert(Bonus(jobId = onB, date = november.atDay(1), amountGrosze = 50_000))

        val plan = b.engine.prepareSignIn(ana) as SignInPlan.AskMerge
        assertEquals(Counts(jobs = 1, shifts = 0, bonuses = 2, payouts = 2), plan.phone)
        assertEquals(Counts(jobs = 1, shifts = 0, bonuses = 1, payouts = 1), plan.cloud)
        assertEquals("the job, the payout and the month bonus", 3, plan.lookAlikes)
        b.engine.completeSignIn(ana, SignInChoice.MERGE)
        a.sync()

        assertEquals(a.view(), b.view())
        assertEquals("the look-alike payout once, plus the one only on B", listOf(450_000L, 10_000L), a.db.payoutDao().getAll().map { it.amountMinor })
        assertEquals("the month bonus once, plus the bonus on the 1st", listOf(false, true), a.db.bonusDao().getAll().map { it.wholeMonth }.sorted())
    }

    @Test
    fun takingTheAccountsDataReplacesThisPhonesPayoutsToo() = runBlocking {
        val a = phone()
        a.payout(a.job("Kawiarnia"), amount = 450_000)
        a.signIn()
        val b = phone()
        b.payout(b.job("Tylko na B"), amount = 999)

        assertTrue(b.engine.prepareSignIn(ana) is SignInPlan.AskMerge)
        b.engine.completeSignIn(ana, SignInChoice.USE_CLOUD_ONLY)

        assertEquals(a.view(), b.view())
        assertEquals(listOf(450_000L), b.db.payoutDao().getAll().map { it.amountMinor })
        assertEquals(1, b.safetyBackups.size)
    }

    @Test
    fun uploadingEverythingToAnEmptyAccountIncludesPayoutsAndMonthBonuses() = runBlocking {
        val a = phone()
        val job = a.job()
        a.shift(job)
        a.monthBonus(job)
        a.payout(job)
        a.signIn()
        // Everything is uploaded once, then a second account gets it all again from the same phone.
        assertEquals(4, cloud.records(ana.uid).size)
        a.engine.unlink()

        assertEquals(SignInPlan.Ready(SignInChoice.UPLOAD), a.engine.prepareSignIn(work))
        assertEquals(SyncResult.Synced(downloaded = 0, uploaded = 4, skipped = 0), a.engine.completeSignIn(work, SignInChoice.UPLOAD))
        assertEquals(1, cloud.records(work.uid).filterIsInstance<RemotePayout>().size)
        assertTrue(cloud.records(work.uid).filterIsInstance<RemoteBonus>().single().wholeMonth)
    }

    @Test
    fun payoutsThisAppCantUseAreSkippedAndNeverWritten() = runBlocking {
        val (a, _) = twoLinkedPhones()
        val jobSyncId = a.db.jobDao().getAll().single().syncId
        var next = 0
        fun id() = "20000000-0000-4000-8000-%012d".format(++next)
        val good = RemotePayout(id(), 9_000_000, false, jobSyncId, 202611, 450_000, "fine", 20_420)
        cloud.put(ana.uid, good)
        val unusable = listOf(
            good.copy(month = 202613), // not a month
            good.copy(month = 0), // "from the beginning" is for pay settings, not for a payout
            good.copy(amountMinor = 0),
            good.copy(amountMinor = -5),
            good.copy(amountMinor = MoneyParser.maxMinor(3, MoneyParser.AMOUNT_WHOLE_DIGITS) + 1),
            good.copy(receivedEpochDay = 3_000_000_000L), // not a date
            good.copy(schema = DOC_SCHEMA + 1), // a newer app's layout
            good.copy(jobSyncId = "not-a-uuid"),
            good.copy(note = "x".repeat(TextLimits.MAX_LENGTH + 1)),
            good.copy(jobSyncId = "30000000-0000-4000-8000-000000000001"), // a job nobody has
        )
        unusable.forEach { cloud.put(ana.uid, it.copy(syncId = id())) }

        val result = a.sync()

        assertEquals(SyncResult.Synced(downloaded = 1, uploaded = 0, skipped = unusable.size), result)
        assertEquals(listOf(450_000L), a.db.payoutDao().getAll().map { it.amountMinor })
        assertEquals(0, a.dirtyCount())
    }
}

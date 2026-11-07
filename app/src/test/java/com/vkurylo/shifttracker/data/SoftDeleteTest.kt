package com.vkurylo.shifttracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.backup.JsonBackupRepository
import com.vkurylo.shifttracker.data.local.ParentChildRule
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.ShiftEntity
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomBonusRepository
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.model.PayType
import com.vkurylo.shifttracker.domain.model.Shift
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
import java.time.YearMonth
import java.util.UUID

/**
 * Deleting keeps tombstones (so deletions can sync later) that no read of the app shows; edits keep a
 * record's sync id and move its change time forward; Undo and [ParentChildRule] bring records back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SoftDeleteTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShiftDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private var now = 1_000_000L
    private val clock = WallClock { now }
    private val details = JobDetailsSource(db.paySettingsDao())
    private val jobs = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details, clock)
    private val shifts = RoomShiftRepository(db, db.shiftDao(), details, clock)
    private val bonuses = RoomBonusRepository(db, db.bonusDao(), details, clock)

    private val day = LocalDate.of(2026, 11, 2)
    private val month = YearMonth.from(day)

    @After
    fun tearDown() = db.close()

    private suspend fun job(name: String = "Burger King", currency: String = "PLN") =
        jobs.upsert(Job(name = name, hourlyRateGrosze = 3300, color = 1, currency = currency))

    private suspend fun shift(jobId: Long, date: LocalDate = day, startHour: Int = 8) = shifts.upsert(
        Shift(jobId = jobId, date = date, start = LocalTime.of(startHour, 0), end = LocalTime.of(startHour + 8, 0), hourlyRateGrosze = 3300),
    )

    private suspend fun bonus(jobId: Long) = bonuses.upsert(Bonus(jobId = jobId, date = day, amountGrosze = 5000))

    private fun isUuid(text: String) = runCatching { UUID.fromString(text).toString() == text }.getOrDefault(false)

    @Test
    fun newRecordsGetSyncMetadata() = runBlocking {
        val jobId = job()
        val shiftId = shift(jobId)
        val bonusId = bonus(jobId)

        val rows = listOf(
            db.jobDao().getAny(jobId)!!.let { Triple(it.syncId, it.updatedAt, it.deleted to it.dirty) },
            db.shiftDao().getAny(shiftId)!!.let { Triple(it.syncId, it.updatedAt, it.deleted to it.dirty) },
            db.bonusDao().getAny(bonusId)!!.let { Triple(it.syncId, it.updatedAt, it.deleted to it.dirty) },
        )
        rows.forEach { (syncId, updatedAt, flags) ->
            assertTrue(syncId, isUuid(syncId))
            assertEquals(now, updatedAt)
            assertEquals(false to true, flags) // live, waiting for upload
        }
        assertEquals(3, rows.map { it.first }.toSet().size)
    }

    @Test
    fun editKeepsSyncIdAndMovesChangeTimeForwardEvenWhenTheClockGoesBack() = runBlocking {
        val jobId = job()
        val shiftId = shift(jobId)
        val jobBefore = db.jobDao().getAny(jobId)!!
        val shiftBefore = db.shiftDao().getAny(shiftId)!!

        now -= 60_000 // this phone's clock is behind the time the rows were written
        jobs.upsert(jobs.getJob(jobId)!!.copy(name = "BK"))
        shifts.upsert(shifts.getShift(shiftId)!!.copy(note = "edited"))

        val jobAfter = db.jobDao().getAny(jobId)!!
        val shiftAfter = db.shiftDao().getAny(shiftId)!!
        assertEquals(jobBefore.syncId, jobAfter.syncId)
        assertEquals(shiftBefore.syncId, shiftAfter.syncId)
        assertEquals(jobBefore.updatedAt + 1, jobAfter.updatedAt)
        assertEquals(shiftBefore.updatedAt + 1, shiftAfter.updatedAt)
        assertEquals("BK", jobAfter.name)

        // Changing only the pay settings counts as a change of the job (they sync inside it).
        now += 120_000
        jobs.save(jobs.getJob(jobId)!!, PaySettings(from = month, payType = PayType.FIXED_MONTHLY, monthlyAmountMinor = 500000))
        assertEquals(now, db.jobDao().getAny(jobId)!!.updatedAt)
        assertEquals(jobBefore.syncId, db.jobDao().getAny(jobId)!!.syncId)
    }

    @Test
    fun deletedShiftIsATombstoneAndUndoBringsBackTheSameRecord() = runBlocking {
        val jobId = job()
        val shiftId = shift(jobId)
        val original = shifts.getShift(shiftId)!!
        val syncId = db.shiftDao().getAny(shiftId)!!.syncId

        now += 1000
        shifts.delete(original)

        assertNull(shifts.getShift(shiftId))
        assertTrue(shifts.observeMonth(month).first().isEmpty())
        val tombstone = db.shiftDao().getAny(shiftId)!!
        assertTrue(tombstone.deleted)
        assertTrue(tombstone.dirty)
        assertEquals(now, tombstone.updatedAt)

        // Undo saves the shift the editor still holds.
        now += 1000
        shifts.upsert(original)

        assertEquals(original, shifts.getShift(shiftId))
        val restored = db.shiftDao().getAny(shiftId)!!
        assertFalse(restored.deleted)
        assertEquals(syncId, restored.syncId)
        assertEquals(now, restored.updatedAt)
    }

    @Test
    fun deletingAJobDeletesItsShiftsAndBonusesAsTombstones() = runBlocking {
        val jobId = job()
        val otherJobId = job("Kawiarnia")
        val shiftIds = listOf(shift(jobId), shift(jobId, day.plusDays(1)))
        val bonusId = bonus(jobId)
        val otherShiftId = shift(otherJobId, day.plusDays(2))

        now += 5000
        jobs.delete(jobs.getJob(jobId)!!)

        val job = db.jobDao().getAny(jobId)!!
        assertTrue(job.deleted)
        assertEquals(now, job.updatedAt)
        (shiftIds.map { db.shiftDao().getAny(it)!! }).forEach {
            assertTrue(it.deleted)
            assertTrue(it.dirty)
            assertTrue("entry tombstones are no older than the job's", it.updatedAt >= job.updatedAt)
        }
        db.bonusDao().getAny(bonusId)!!.let {
            assertTrue(it.deleted)
            assertTrue(it.updatedAt >= job.updatedAt)
        }
        // The other job is untouched.
        assertEquals(listOf("Kawiarnia"), jobs.observeJobs().first().map { it.name })
        assertEquals(listOf(otherShiftId), shifts.observeMonth(month).first().map { it.shift.id })
        assertFalse(db.shiftDao().getAny(otherShiftId)!!.deleted)
    }

    /** Every read that feeds the app hides tombstones; a guard against a new query forgetting the filter. */
    @Test
    fun everyReadHidesDeletedRecords() = runBlocking {
        val liveJob = job("Live", currency = "PLN")
        val liveShift = shift(liveJob)
        val liveBonus = bonus(liveJob)
        val deletedShift = shift(liveJob, day.plusDays(3))
        val deletedBonus = bonus(liveJob)
        shifts.delete(shifts.getShift(deletedShift)!!)
        bonuses.delete(bonuses.getBonus(deletedBonus)!!)
        val deletedJob = job("Deleted", currency = "EUR")
        val shiftOfDeletedJob = shift(deletedJob, day.plusDays(4))
        val bonusOfDeletedJob = bonus(deletedJob)
        jobs.delete(jobs.getJob(deletedJob)!!)

        val from = day.minusDays(10).toEpochDay()
        val to = day.plusDays(10).toEpochDay()

        // Jobs
        assertEquals(listOf(liveJob), db.jobDao().observeAll().first().map { it.id })
        assertEquals(listOf(liveJob), db.jobDao().getAll().map { it.id })
        assertNull(db.jobDao().get(deletedJob))
        assertEquals(1, db.jobDao().shiftCount(liveJob))
        assertEquals(2, db.jobDao().entryCount(liveJob))
        assertEquals(0, db.jobDao().entryCount(deletedJob))
        assertEquals("PLN", db.jobDao().latestCurrency())
        assertEquals(listOf("Live"), jobs.observeJobs().first().map { it.name })
        assertNull(jobs.getJob(deletedJob))
        // Shifts
        assertEquals(listOf(liveShift), db.shiftDao().observeBetween(from, to).first().map { it.shift.id })
        assertEquals(listOf(liveShift), db.shiftDao().getAll().map { it.id })
        assertNull(db.shiftDao().get(deletedShift))
        assertNull(db.shiftDao().get(shiftOfDeletedJob))
        assertEquals(listOf(liveShift), shifts.getAllShifts().map { it.id }) // .ics duplicate check
        // Bonuses
        assertEquals(listOf(liveBonus), db.bonusDao().observeBetween(from, to).first().map { it.bonus.id })
        assertEquals(listOf(liveBonus), db.bonusDao().getAll().map { it.id })
        assertNull(db.bonusDao().get(deletedBonus))
        assertNull(db.bonusDao().get(bonusOfDeletedJob))
        // Each job's first/last entry (monthly salary span) counts live entries only.
        val activity = db.paySettingsDao().getActivity().associateBy { it.jobId }
        assertEquals(setOf(liveJob), activity.keys)
        assertEquals(day.toEpochDay() to day.toEpochDay(), activity.getValue(liveJob).let { it.firstDay to it.lastDay })
        assertEquals(db.paySettingsDao().getActivity(), db.paySettingsDao().observeActivity().first())
        // Export
        val export = Json.parseToJsonElement(JsonBackupRepository(db, clock).exportJson()).jsonObject
        assertEquals(1, export["jobs"]!!.jsonArray.size)
        assertEquals(1, export["shifts"]!!.jsonArray.size)
        assertEquals(1, export["bonuses"]!!.jsonArray.size)
    }

    @Test
    fun undoingAShiftAfterItsJobWasDeletedBringsBackTheJobWithOnlyThatShift() = runBlocking {
        val jobId = job()
        val undone = shifts.getShift(shift(jobId))!!
        val other = shift(jobId, day.plusDays(1))
        now += 1000
        shifts.delete(undone)
        now += 1000
        jobs.delete(jobs.getJob(jobId)!!)
        assertTrue(jobs.observeJobs().first().isEmpty())

        now += 1000
        shifts.upsert(undone) // the snackbar's Undo, after the job was deleted

        val job = db.jobDao().getAny(jobId)!!
        assertFalse(job.deleted)
        assertEquals("revived at the shift's change time", db.shiftDao().getAny(undone.id)!!.updatedAt, job.updatedAt)
        assertEquals(listOf("Burger King"), jobs.observeJobs().first().map { it.name })
        assertEquals(listOf(undone.id), shifts.observeMonth(month).first().map { it.shift.id })
        assertTrue("deleted together with the job, stays deleted", db.shiftDao().getAny(other)!!.deleted)
    }

    /**
     * Changes from another phone (step 3 of the sync work applies them the same way): a live shift that
     * arrives for a deleted job revives it when it is newer than the deletion, and is deleted with the job
     * when it is not.
     */
    @Test
    fun parentChildRuleAppliedToTheDatabase() = runBlocking {
        now = 10
        val revivedJob = job("Revived")
        val keptDeletedJob = job("Stays deleted")
        now = 100
        jobs.delete(jobs.getJob(revivedJob)!!)
        jobs.delete(jobs.getJob(keptDeletedJob)!!)
        val newer = ShiftEntity(jobId = revivedJob, dateEpochDay = day.toEpochDay(), startMinute = 480, endMinute = 960,
            unpaidBreakMinutes = 0, hourlyRateGrosze = 3300, note = "added offline after the deletion", externalUid = null, updatedAt = 150)
        val older = newer.copy(jobId = keptDeletedJob, note = "added offline before the deletion", syncId = UUID.randomUUID().toString(), updatedAt = 50)
        val newerId = db.shiftDao().upsert(newer)
        val olderId = db.shiftDao().upsert(older)

        ParentChildRule.enforce(db.jobDao())

        val revived = db.jobDao().getAny(revivedJob)!!
        assertFalse(revived.deleted)
        assertEquals("revived at the newer shift's time", 150L, revived.updatedAt)
        assertFalse(db.shiftDao().getAny(newerId)!!.deleted)
        assertEquals(listOf("Revived"), jobs.observeJobs().first().map { it.name })
        assertEquals(listOf("added offline after the deletion"), db.shiftDao().getAll().map { it.note })

        assertTrue(db.jobDao().getAny(keptDeletedJob)!!.deleted)
        val olderTombstone = db.shiftDao().getAny(olderId)!!
        assertTrue(olderTombstone.deleted)
        assertTrue(olderTombstone.dirty)
        assertEquals("deleted at the job's deletion time", 100L, olderTombstone.updatedAt)

        // Applying the rule again changes nothing.
        ParentChildRule.enforce(db.jobDao())
        assertEquals(revived, db.jobDao().getAny(revivedJob))
        assertEquals(olderTombstone, db.shiftDao().getAny(olderId))
    }
}

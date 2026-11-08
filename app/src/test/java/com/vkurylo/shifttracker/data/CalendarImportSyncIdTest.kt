package com.vkurylo.shifttracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.domain.ics.IcsEvent
import com.vkurylo.shifttracker.domain.ics.ImportPlanner
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Shifts imported from a calendar get sync ids derived from their event, so two phones importing the same
 * file before they sync end up with one shift per event, and importing an event again never duplicates it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CalendarImportSyncIdTest {

    /** One phone: its own database and repositories. */
    private inner class Phone {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShiftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        var now = 1_000L
        private val clock = WallClock { now }
        private val details = JobDetailsSource(db.paySettingsDao())
        val jobs = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details, clock)
        val shifts = RoomShiftRepository(db, db.shiftDao(), details, clock)

        suspend fun import(events: List<IcsEvent>): List<String> {
            val jobId = jobs.observeJobsOnce().firstOrNull()?.id ?: jobs.upsert(Job(name = "Sklep", hourlyRateGrosze = 3100, color = 1))
            val job = jobs.getJob(jobId)!!
            shifts.importFromCalendar(events.map { ImportPlanner.toShift(it, job) })
            return db.shiftDao().getAll().map { it.syncId }
        }
    }

    private val phones = mutableListOf<Phone>()
    private fun phone() = Phone().also { phones += it }

    @After
    fun tearDown() = phones.forEach { it.db.close() }

    private suspend fun RoomJobRepository.observeJobsOnce() = observeJobs().first()

    private fun event(uid: String?, day: Int, startHour: Int, endHour: Int, summary: String = "Zmiana") = IcsEvent(
        uid = uid,
        summary = summary,
        description = null,
        start = LocalDateTime.of(2026, 11, day, startHour, 0),
        end = LocalDateTime.of(2026, 11, day, endHour, 0),
    )

    private val schedule = listOf(
        event("a1@grafik", 9, 8, 16),
        event("a2@grafik", 10, 14, 22),
        event(null, 11, 6, 14),
    )

    @Test
    fun theSameEventsOnTwoPhonesAreTheSameRecords() = runBlocking {
        val a = phone().import(schedule)
        val b = phone().import(schedule)

        assertEquals(a, b)
        assertEquals(3, a.toSet().size)
    }

    @Test
    fun importingAnEventAgainUpdatesItsShiftInsteadOfAddingOne() = runBlocking {
        val phone = phone()
        phone.import(schedule)
        val before = phone.db.shiftDao().getAll().first()

        phone.now += 1000
        phone.import(listOf(event("a1@grafik", 9, 8, 18, summary = "Dłuższa zmiana"))) // same occurrence, ends later

        val shifts = phone.db.shiftDao().getAll()
        assertEquals(3, shifts.size)
        val after = shifts.first()
        assertEquals(before.id to before.syncId, after.id to after.syncId)
        assertEquals(18 * 60, after.endMinute)
        assertEquals("Dłuższa zmiana", after.note)
        assertEquals(phone.now, after.updatedAt)
    }

    @Test
    fun importingADeletedEventAgainBringsBackItsShift() = runBlocking {
        val phone = phone()
        phone.import(schedule)
        val deleted = phone.shifts.getAllShifts().first()
        phone.now += 1000
        phone.shifts.delete(deleted)
        assertEquals(2, phone.shifts.getAllShifts().size)

        phone.now += 1000
        phone.import(schedule.take(1))

        val row = phone.db.shiftDao().getAny(deleted.id)!!
        assertFalse(row.deleted)
        assertTrue(row.dirty)
        assertEquals(phone.now, row.updatedAt)
        assertEquals(3, phone.shifts.getAllShifts().size)
    }

    @Test
    fun occurrencesSharingAUidStayApart() = runBlocking {
        val ids = phone().import(
            listOf(
                event("weekly@grafik", 9, 8, 16),
                event("weekly@grafik", 16, 8, 16), // next week's occurrence
                event("weekly@grafik", 16, 18, 22), // a second one that day
            ),
        )
        assertEquals(3, ids.toSet().size)
    }

    @Test
    fun eventsWithoutUidAreIdentifiedByTheirSlotAndNeverMergeWithManualShifts() = runBlocking {
        val phone = phone()
        val jobId = phone.jobs.upsert(Job(name = "Sklep", hourlyRateGrosze = 3100, color = 1))
        // A shift entered by hand in the very slot the calendar has.
        phone.shifts.upsert(
            Shift(jobId = jobId, date = schedule[2].start.toLocalDate(), start = LocalTime.of(6, 0), end = LocalTime.of(14, 0), hourlyRateGrosze = 3100),
        )
        val manual = phone.db.shiftDao().getAll().single().syncId

        val withSlotEvent = phone.import(listOf(schedule[2]))
        assertEquals(2, withSlotEvent.size)
        assertTrue(manual in withSlotEvent)
        // The imported one has the same id another phone gives that event.
        assertEquals(phone().import(listOf(schedule[2])).single(), (withSlotEvent - manual).single())
        // A different slot is a different record.
        assertNotEquals(phone().import(listOf(event(null, 11, 6, 15))).single(), (withSlotEvent - manual).single())
    }

    @Test
    fun theDuplicateCheckStillSeesImportedShifts() = runBlocking {
        val phone = phone()
        phone.import(schedule)

        val plan = ImportPlanner.plan(schedule, phone.shifts.getAllShifts())

        assertEquals(listOf(true, true, true), plan.map { it.isDuplicate })
    }
}

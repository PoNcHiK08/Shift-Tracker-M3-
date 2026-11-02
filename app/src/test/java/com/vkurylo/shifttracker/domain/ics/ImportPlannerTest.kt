package com.vkurylo.shifttracker.domain.ics

import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ImportPlannerTest {

    private fun event(uid: String?, day: Int, startH: Int, endH: Int) = IcsEvent(
        uid = uid,
        summary = "Shift",
        description = null,
        start = LocalDateTime.of(2026, 11, day, startH, 0),
        end = LocalDateTime.of(2026, 11, day, endH, 0),
    )

    private val existing = listOf(
        Shift(id = 1, jobId = 1, date = LocalDate.of(2026, 11, 2), start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3000),
        Shift(id = 2, jobId = 1, date = LocalDate.of(2026, 11, 3), start = LocalTime.of(9, 0), end = LocalTime.of(17, 0), hourlyRateGrosze = 3000, externalUid = "abc"),
    )

    @Test
    fun `flags duplicates by time slot, uid and repeats within the file`() {
        val plan = ImportPlanner.plan(
            listOf(
                event(null, 2, 8, 16), // same slot as existing shift 1
                event("abc", 10, 8, 12), // same uid as existing shift 2
                event("new", 4, 8, 16), // new
                event("new2", 4, 8, 16), // same slot as the previous event in the file
            ),
            existing,
        )
        assertEquals(listOf(true, true, false, true), plan.map { it.isDuplicate })
    }

    @Test
    fun `converted shift takes the job rate and the event summary`() {
        val job = Job(id = 7, name = "Cafe", hourlyRateGrosze = 3150, color = 0)
        val shift = ImportPlanner.toShift(event("u1", 5, 22, 23).copy(end = LocalDateTime.of(2026, 11, 6, 6, 0)), job)
        assertEquals(7L, shift.jobId)
        assertEquals(LocalDate.of(2026, 11, 5), shift.date)
        assertEquals(LocalTime.of(22, 0), shift.start)
        assertEquals(LocalTime.of(6, 0), shift.end)
        assertEquals(3150L, shift.hourlyRateGrosze)
        assertEquals("Shift", shift.note)
        assertEquals("u1", shift.externalUid)
        assertEquals(8 * 60L, shift.paidMinutes)
    }
}

package com.vkurylo.shifttracker.ui.calendar

import com.vkurylo.shifttracker.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** A day without shifts says "Day off" up to and including today, and "No shifts yet" after it. */
class EmptyDayLabelTest {
    private val today = LocalDate.of(2026, 11, 23)

    @Test
    fun pastAndTodayAreDaysOff() {
        assertEquals(R.string.day_off, emptyDayLabel(today.minusDays(1), today))
        assertEquals(R.string.day_off, emptyDayLabel(LocalDate.of(2025, 1, 1), today))
        assertEquals(R.string.day_off, emptyDayLabel(today, today))
    }

    @Test
    fun futureDaysHaveNoShiftsYet() {
        assertEquals(R.string.day_no_shifts_yet, emptyDayLabel(today.plusDays(1), today))
        assertEquals(R.string.day_no_shifts_yet, emptyDayLabel(LocalDate.of(2027, 3, 1), today))
    }
}

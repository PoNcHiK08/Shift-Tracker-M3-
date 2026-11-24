package com.vkurylo.shifttracker.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * The calendar's day card title: the full weekday when it has the line to itself, the short one when the day's
 * amount sits beside it (so "Wednesday, 25 November" doesn't wrap onto two lines).
 */
class DayTitleFormatTest {
    private val day = LocalDate.of(2026, 11, 25)
    private val uk = Locale.forLanguageTag("uk")

    @Test
    fun aloneTheTitleHasTheFullWeekday() {
        assertEquals("Wednesday, 25 November", Formatters.dayTitle(day, Locale.US, besideAmount = false))
        assertEquals(Formatters.fullDate(day, uk), Formatters.dayTitle(day, uk, besideAmount = false))
    }

    @Test
    fun besideAnAmountTheWeekdayIsShort() {
        assertEquals("Wed, 25 November", Formatters.dayTitle(day, Locale.US, besideAmount = true))
        val ukrainian = Formatters.dayTitle(day, uk, besideAmount = true)
        assertEquals("Ср, 25 листопада", ukrainian)
    }
}

package com.vkurylo.shifttracker.domain.calc

import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.TimedRate
import com.vkurylo.shifttracker.ui.shift.EditShiftUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * A shift's length is the real time that passes between its start and end in the phone's time zone, so it is one
 * hour longer on the night the clocks go back and one hour shorter on the night they go forward.
 * Warsaw: 25 October 2026 03:00 → 02:00, 29 March 2026 02:00 → 03:00.
 */
class DstShiftTest {
    private val warsaw = ZoneId.of("Europe/Warsaw")
    private val clocksBack = LocalDate.of(2026, 10, 24) // the shift starts the evening before
    private val clocksForward = LocalDate.of(2026, 3, 28)
    private val ordinaryNight = LocalDate.of(2026, 10, 10)
    private val night = TimedRate(3_700, LocalTime.of(23, 0), LocalTime.of(6, 0))
    private lateinit var previousZone: TimeZone

    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Before
    fun pinZone() {
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(warsaw))
    }

    @After
    fun restoreZone() = TimeZone.setDefault(previousZone)

    @Test
    fun `the night the clocks go back a 22-06 shift lasts and is paid 9 hours`() {
        assertEquals(540L, ShiftCalculator.durationMinutes(clocksBack, t(22), t(6), warsaw))
        val pay = ShiftCalculator.pay(clocksBack, t(22), t(6), 0, 3_300, null, warsaw)
        assertEquals(540L, pay.durationMinutes)
        assertEquals(540L, pay.paidMinutes)
        assertEquals(9 * 3_300L, pay.earningsGrosze)
    }

    @Test
    fun `the night the clocks go forward a 22-06 shift lasts and is paid 7 hours`() {
        assertEquals(420L, ShiftCalculator.durationMinutes(clocksForward, t(22), t(6), warsaw))
        val pay = ShiftCalculator.pay(clocksForward, t(22), t(6), 30, 3_300, null, warsaw)
        assertEquals(390L, pay.paidMinutes)
        assertEquals(6 * 3_300L + 3_300 / 2, pay.earningsGrosze)
    }

    @Test
    fun `an ordinary night and a day shift on the change day are unchanged`() {
        assertEquals(480L, ShiftCalculator.durationMinutes(ordinaryNight, t(22), t(6), warsaw))
        // 25 October from 08:00: after the change, an ordinary 8 hours.
        assertEquals(480L, ShiftCalculator.durationMinutes(LocalDate.of(2026, 10, 25), t(8), t(16), warsaw))
        // Without daylight saving time real and clock time are the same.
        assertEquals(480L, ShiftCalculator.durationMinutes(clocksBack, t(22), t(6), ZoneId.of("UTC")))
    }

    @Test
    fun `the second-rate window counts real minutes too`() {
        // 23:00-06:00 the night the clocks go back is 8 real hours; 22:00-23:00 at the base rate.
        val back = ShiftCalculator.pay(clocksBack, t(22), t(6), 0, 3_300, night, warsaw)
        assertEquals(480L, back.secondRateMinutes)
        assertEquals(60L, back.baseRateMinutes)
        assertEquals(3_300L + 8 * 3_700L, back.earningsGrosze)

        val forward = ShiftCalculator.pay(clocksForward, t(22), t(6), 0, 3_300, night, warsaw)
        assertEquals(360L, forward.secondRateMinutes)
        assertEquals(60L, forward.baseRateMinutes)
        assertEquals(3_300L + 6 * 3_700L, forward.earningsGrosze)
    }

    @Test
    fun `a shift inside the hour that is skipped has no length and pays nothing`() {
        val pay = ShiftCalculator.pay(LocalDate.of(2026, 3, 29), t(2), t(3), 0, 3_300, night, warsaw)
        assertEquals(0L, pay.durationMinutes)
        assertEquals(0L, pay.paidMinutes)
        assertEquals(0L, pay.earningsGrosze)
    }

    @Test
    fun `a saved shift and the editor preview use the phone's time zone`() {
        val shift = Shift(jobId = 1, date = clocksBack, start = t(22), end = t(6), unpaidBreakMinutes = 30, hourlyRateGrosze = 3_300)
        assertEquals(510L, shift.paidMinutes)
        assertEquals(510L, shift.pay.paidMinutes)
        assertEquals(3_300L * 510 / 60, shift.earningsGrosze)

        val editor = EditShiftUiState(loading = false, date = clocksBack, start = t(22), end = t(6), breakMinutes = "30", rateGrosze = 3_300)
        assertEquals(540L, editor.durationMinutes)
        assertEquals(510L, editor.paidMinutes)
        assertEquals(shift.earningsGrosze, editor.earningsGrosze)
    }

    @Test
    fun `the break must be shorter than the real shift`() {
        val forward = EditShiftUiState(loading = false, date = clocksForward, start = t(22), end = t(6), breakMinutes = "450")
        assertTrue("7 h shift, 7.5 h break", forward.breakTooLong)
        val back = EditShiftUiState(loading = false, date = clocksBack, start = t(22), end = t(6), breakMinutes = "500")
        assertFalse("9 h shift, 8 h 20 min break", back.breakTooLong)
    }
}

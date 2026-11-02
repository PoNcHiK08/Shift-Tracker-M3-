package com.vkurylo.shifttracker.domain.calc

import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.TimedRate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Burger King example: 33 zł/h base, 37 zł/h between 23:00 and 06:00. */
class SecondRateTest {

    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)
    private val night = TimedRate(rateGrosze = 3700, from = t(23), to = t(6))

    private fun pay(start: LocalTime, end: LocalTime, breakMinutes: Int = 0) =
        ShiftCalculator.pay(start, end, breakMinutes, baseRateGrosze = 3300, secondRate = night)

    @Test
    fun `day shift outside the window uses only the base rate`() {
        val p = pay(t(8), t(16))
        assertEquals(0L, p.secondRateMinutes)
        assertEquals(8 * 3300L, p.earningsGrosze) // 264 zł
    }

    @Test
    fun `evening shift crossing into the night window`() {
        // 18–23 at 33 (5 h) + 23–02 at 37 (3 h) = 165 + 111 = 276 zł
        val p = pay(t(18), t(2))
        assertEquals(180L, p.secondRateMinutes)
        assertEquals(300L, p.baseRateMinutes)
        assertEquals(27600L, p.earningsGrosze)
    }

    @Test
    fun `early morning shift touching the end of the window`() {
        // 05–06 at 37 + 06–14 at 33 = 37 + 264 = 301 zł
        val p = pay(t(5), t(14))
        assertEquals(60L, p.secondRateMinutes)
        assertEquals(30100L, p.earningsGrosze)
    }

    @Test
    fun `full night shift inside the window`() {
        val p = pay(t(23), t(6))
        assertEquals(420L, p.secondRateMinutes)
        assertEquals(7 * 3700L, p.earningsGrosze)
    }

    @Test
    fun `break is split proportionally between rates`() {
        // 22–07: 2 h base + 7 h night = 9 h; minus 1 h break -> 8 paid h.
        // (2*33 + 7*37) * 8/9 = 325 * 8/9 = 288.888… -> 288.89 zł
        val p = pay(t(22), t(7), breakMinutes = 60)
        assertEquals(480L, p.paidMinutes)
        assertEquals(28889L, p.earningsGrosze)
    }

    @Test
    fun `24 hour shift counts the window once`() {
        assertEquals(420L, pay(t(7), t(7)).secondRateMinutes)
    }

    @Test
    fun `window that does not cross midnight`() {
        val evening = TimedRate(4000, from = t(18), to = t(22))
        assertEquals(120L, ShiftCalculator.minutesInWindow(t(16), t(20), evening))
        assertEquals(240L, ShiftCalculator.minutesInWindow(t(17), t(23), evening))
        assertEquals(0L, ShiftCalculator.minutesInWindow(t(8), t(16), evening))
    }

    @Test
    fun `no second rate gives the same result as the single-rate formula`() {
        val single = ShiftCalculator.pay(t(9, 25), t(17, 15), 30, 3001, null)
        assertEquals(ShiftCalculator.earningsGrosze(single.paidMinutes, 3001), single.earningsGrosze)
    }

    @Test
    fun `shift model uses its own second rate snapshot`() {
        val shift = Shift(
            jobId = 1,
            date = LocalDate.of(2026, 11, 7),
            start = t(18),
            end = t(2),
            hourlyRateGrosze = 3300,
            secondRate = night,
        )
        assertEquals(27600L, shift.earningsGrosze)
    }
}

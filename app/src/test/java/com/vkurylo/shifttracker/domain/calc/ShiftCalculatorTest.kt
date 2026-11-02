package com.vkurylo.shifttracker.domain.calc

import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ShiftCalculatorTest {

    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Test
    fun `day shift without break`() {
        assertEquals(8 * 60L, ShiftCalculator.paidMinutes(t(8), t(16), 0))
        assertFalse(ShiftCalculator.isOvernight(t(8), t(16)))
    }

    @Test
    fun `day shift with break subtracts the break`() {
        assertEquals(7 * 60L + 30, ShiftCalculator.paidMinutes(t(8), t(16), 30))
    }

    @Test
    fun `overnight shift crosses midnight`() {
        assertTrue(ShiftCalculator.isOvernight(t(22), t(6)))
        assertEquals(8 * 60L, ShiftCalculator.paidMinutes(t(22), t(6), 0))
    }

    @Test
    fun `overnight shift with break`() {
        assertEquals(7 * 60L + 15, ShiftCalculator.paidMinutes(t(22), t(6), 45))
    }

    @Test
    fun `shift ending exactly at midnight`() {
        assertEquals(6 * 60L, ShiftCalculator.paidMinutes(t(18), t(0), 0))
        assertTrue(ShiftCalculator.isOvernight(t(18), t(0)))
    }

    @Test
    fun `odd minutes are counted precisely`() {
        assertEquals(7 * 60L + 50, ShiftCalculator.paidMinutes(t(9, 25), t(17, 15), 0))
    }

    @Test
    fun `equal start and end means a full 24 hour shift`() {
        assertEquals(24 * 60L, ShiftCalculator.durationMinutes(t(7), t(7)))
    }

    @Test
    fun `break longer than the shift never gives negative hours`() {
        assertEquals(0L, ShiftCalculator.paidMinutes(t(8), t(9), 90))
    }

    @Test
    fun `negative break is ignored`() {
        assertEquals(60L, ShiftCalculator.paidMinutes(t(8), t(9), -15))
    }

    @Test
    fun `earnings are paid minutes times rate`() {
        // 7.5 h * 27.70 zł = 207.75 zł
        assertEquals(20775L, ShiftCalculator.earningsGrosze(450, 2770))
    }

    @Test
    fun `earnings round half up to the nearest grosz`() {
        // 10 min * 30.01 zł/h = 5.001666 zł -> 5.00 zł
        assertEquals(500L, ShiftCalculator.earningsGrosze(10, 3001))
        // 1 min * 30.30 zł/h = 0.505 zł -> 0.51 zł
        assertEquals(51L, ShiftCalculator.earningsGrosze(1, 3030))
    }

    @Test
    fun `shift model uses its own rate snapshot`() {
        val shift = Shift(jobId = 1, date = LocalDate.of(2026, 11, 2), start = t(22), end = t(6), unpaidBreakMinutes = 30, hourlyRateGrosze = 3050)
        assertEquals(450L, shift.paidMinutes)
        assertEquals(22875L, shift.earningsGrosze) // 7.5 h * 30.50
    }

    @Test
    fun `summary totals and per-job breakdown`() {
        val cafe = Job(id = 1, name = "Cafe", hourlyRateGrosze = 3000, color = 0)
        val shop = Job(id = 2, name = "Shop", hourlyRateGrosze = 3200, color = 0)
        val date = LocalDate.of(2026, 11, 3)
        val shifts = listOf(
            ShiftWithJob(Shift(id = 1, jobId = 1, date = date, start = t(8), end = t(16), hourlyRateGrosze = 3000), cafe),
            ShiftWithJob(Shift(id = 2, jobId = 1, date = date.plusDays(1), start = t(8), end = t(12), hourlyRateGrosze = 3000), cafe),
            // Rate snapshot differs from the job's current rate: the snapshot wins.
            ShiftWithJob(Shift(id = 3, jobId = 2, date = date, start = t(18), end = t(2), unpaidBreakMinutes = 30, hourlyRateGrosze = 2800), shop),
        )

        val summary = ShiftCalculator.summarize(shifts)

        assertEquals(3, summary.shiftCount)
        assertEquals((8 + 4) * 60L + 450, summary.totalPaidMinutes)
        assertEquals(36000L + 21000L, summary.totalEarningsGrosze)
        assertEquals(listOf("Cafe", "Shop"), summary.perJob.map { it.job.name })
        assertEquals(2, summary.perJob[0].shiftCount)
        assertEquals(36000L, summary.perJob[0].earningsGrosze)
        assertEquals(21000L, summary.perJob[1].earningsGrosze)
    }
}

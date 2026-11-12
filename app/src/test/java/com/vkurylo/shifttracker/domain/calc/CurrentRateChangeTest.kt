package com.vkurylo.shifttracker.domain.calc

import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.model.PayType
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.TimedRate
import com.vkurylo.shifttracker.domain.model.currentRates
import com.vkurylo.shifttracker.domain.model.rates
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** Which shifts "Apply to all shifts of this job" changes, and the money it moves, before anything is written. */
class CurrentRateChangeTest {
    private val night = TimedRate(4_500, LocalTime.of(22, 0), LocalTime.of(6, 0))

    // Now 35 zł/h with 45 zł/h at night; salaried from June 2026.
    private val cafe = Job(
        id = 1, name = "Cafe", hourlyRateGrosze = 3_500, color = 0, secondRate = night,
        payHistory = listOf(PaySettings(null), PaySettings(YearMonth.of(2026, 6), PayType.FIXED_MONTHLY, 300_000)),
    )

    private fun shift(id: Long, date: LocalDate, rate: Long = 3_000, second: TimedRate? = null, jobId: Long = 1, end: Int = 16) =
        Shift(id = id, jobId = jobId, date = date, start = LocalTime.of(8, 0), end = LocalTime.of(end, 0), hourlyRateGrosze = rate, secondRate = second)

    private val january = shift(1, LocalDate.of(2026, 1, 10))
    private val march = shift(2, LocalDate.of(2026, 3, 5))
    private val april = shift(3, LocalDate.of(2026, 4, 1), end = 23) // 15 h, one of them at night
    private val alreadyCurrent = shift(4, LocalDate.of(2026, 5, 1), rate = 3_500, second = night)
    private val salaried = shift(5, LocalDate.of(2026, 6, 10))
    private val otherJob = shift(6, LocalDate.of(2026, 4, 2), jobId = 2)
    private val shifts = listOf(january, march, april, alreadyCurrent, salaried, otherJob)

    @Test
    fun allShiftsOfTheJobThatDifferAndArePaidHourly() {
        val plan = CurrentRateChange.plan(cafe, shifts, RateScope.ALL, march.date, excludeShiftId = null)
        assertEquals(listOf(1L, 2L, 3L), plan.changes.map { it.old.id })
        assertEquals(3, plan.count)
        plan.changes.forEach { assertEquals(cafe.currentRates, it.new.rates) }
    }

    @Test
    fun fromTheShiftsDateOnAndNeverTheOpenShift() {
        val plan = CurrentRateChange.plan(cafe, shifts, RateScope.FROM_DATE, march.date, excludeShiftId = march.id)
        assertEquals(listOf(3L), plan.changes.map { it.old.id })
        val all = CurrentRateChange.plan(cafe, shifts, RateScope.ALL, march.date, excludeShiftId = march.id)
        assertEquals(listOf(1L, 3L), all.changes.map { it.old.id })
    }

    @Test
    fun theMoneyIsNewMinusOldPayWithTheSameCalculationAsTheShift() {
        val plan = CurrentRateChange.plan(cafe, shifts, RateScope.ALL, march.date, excludeShiftId = null)
        // 8 h: 240 → 280 zł (twice). 15 h with 1 h at night: 450 → 14 × 35 + 45 = 535 zł.
        assertEquals(4_000L, plan.changes[0].differenceMinor)
        assertEquals(53_500L - 45_000L, plan.changes[2].differenceMinor)
        assertEquals(4_000L + 4_000L + 8_500L, plan.differenceMinor)
        assertEquals(plan.changes.sumOf { it.new.earningsGrosze - it.old.earningsGrosze }, plan.differenceMinor)
    }

    @Test
    fun undoRatesAreTheOldOnes() {
        val plan = CurrentRateChange.plan(cafe, shifts, RateScope.ALL, march.date, excludeShiftId = null)
        assertEquals(mapOf(1L to january.rates, 2L to march.rates, 3L to april.rates), plan.oldRates)
        assertEquals(setOf(cafe.currentRates), plan.newRates.values.toSet())
    }

    @Test
    fun nothingToChangeIsAnEmptyPlan() {
        val plan = CurrentRateChange.plan(cafe, listOf(alreadyCurrent, otherJob, salaried), RateScope.ALL, march.date, excludeShiftId = null)
        assertEquals(0, plan.count)
        assertEquals(0L, plan.differenceMinor)
    }
}

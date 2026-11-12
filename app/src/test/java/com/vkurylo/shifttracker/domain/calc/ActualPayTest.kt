package com.vkurylo.shifttracker.domain.calc

import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.model.PayType
import com.vkurylo.shifttracker.domain.model.Payout
import com.vkurylo.shifttracker.domain.model.PayoutWithJob
import com.vkurylo.shifttracker.domain.model.ReceivedPart
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * Received pay counts as the real money of its job-month: with payouts, the job-month is their sum and replaces
 * the calculated shifts, bonuses and salary (never on top of them); without, it stays calculated.
 */
class ActualPayTest {
    private val october = YearMonth.of(2026, 10)
    private val november = YearMonth.of(2026, 11)
    private val day = november.atDay(3)
    private val year = (1..12).map { YearMonth.of(2026, it) }

    // 30 zł/h, 10 % deductions.
    private val cafe = Job(
        id = 1, name = "Cafe", hourlyRateGrosze = 3000, color = 0,
        payHistory = listOf(PaySettings(deductionBp = 1_000)), firstEntry = october.atDay(5), lastEntry = day,
    )

    // 3 000 € a month, 20 % deductions.
    private val office = Job(
        id = 2, name = "Office", hourlyRateGrosze = 0, color = 0, currency = "EUR",
        payHistory = listOf(PaySettings(null, PayType.FIXED_MONTHLY, 300_000, deductionBp = 2_000)),
        firstEntry = october.atDay(1), lastEntry = day,
    )
    private val shop = Job(id = 3, name = "Shop", hourlyRateGrosze = 2000, color = 0)
    private val jobs = listOf(cafe, office, shop)

    private fun shift(job: Job, date: LocalDate, id: Long) = ShiftWithJob(
        Shift(id = id, jobId = job.id, date = date, start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = job.hourlyRateGrosze),
        job,
    )

    private val novemberShifts = listOf(shift(cafe, day, 1), shift(cafe, day.plusDays(1), 2))
    private val shifts = novemberShifts + shift(cafe, october.atDay(10), 3)
    private val dayBonus = BonusWithJob(Bonus(id = 1, jobId = 1, date = day, amountGrosze = 5_000), cafe)
    private val monthBonus = BonusWithJob(Bonus(id = 2, jobId = 1, date = november.atDay(1), amountGrosze = 50_000, wholeMonth = true), cafe)
    private val bonuses = listOf(dayBonus, monthBonus)

    private fun payout(job: Job, month: YearMonth, amount: Long, id: Long = 0) =
        PayoutWithJob(Payout(id = id, jobId = job.id, month = month, amountMinor = amount), job)

    private fun novemberActual(payouts: List<PayoutWithJob>, mode: EarningsMode = EarningsMode.GROSS) =
        PayReconciler.actualSummary(novemberShifts, bonuses, jobs, listOf(november), mode, payouts)

    private fun novemberCalculated(mode: EarningsMode = EarningsMode.GROSS) =
        ShiftCalculator.summarize(novemberShifts, bonuses, jobs, listOf(november), mode)

    @Test
    fun theRuleTakesTheReceivedSumWhenThereArePayoutsElseTheCalculatedFigure() {
        val payouts = listOf(Payout(jobId = 1, month = november, amountMinor = 60_000), Payout(jobId = 1, month = november, amountMinor = 10_000))
        assertEquals(MonthPay(70_000, received = true), PayReconciler.actualMonthPay(123_456, payouts))
        assertEquals(MonthPay(123_456, received = false), PayReconciler.actualMonthPay(123_456, emptyList()))
        // An advance alone is the month's pay, even far below the calculated figure.
        assertEquals(MonthPay(1_000, received = true), PayReconciler.actualMonthPay(123_456, payouts.take(1).map { it.copy(amountMinor = 1_000) }))
    }

    @Test
    fun withoutPayoutsTheSummaryIsTheCalculatedOne() {
        assertEquals(novemberCalculated(), novemberActual(emptyList()))
        assertEquals(ReceivedPart.NONE, novemberActual(emptyList()).receivedPart)
    }

    @Test
    fun payoutsReplaceTheJobMonthsShiftsBonusesAndAreNeverAddedOnTop() {
        // Calculated cafe: 2 × 240 zł + 50 zł + 500 zł = 1 030 zł; 700 zł arrived in two payments.
        assertEquals(103_000L, novemberCalculated().totalEarnings["PLN"])
        val actual = novemberActual(listOf(payout(cafe, november, 60_000, 1), payout(cafe, november, 10_000, 2)))

        assertEquals(70_000L, actual.totalEarnings["PLN"])
        val cafeRow = actual.perJob.single { it.job.id == 1L }
        assertEquals(70_000L, cafeRow.earningsGrosze)
        assertEquals(ReceivedPart.ALL, cafeRow.receivedPart)
        // The bonuses are inside what arrived; none is still counted on top.
        assertEquals(0L, actual.countedBonuses["PLN"])
        assertEquals(0L, cafeRow.countedBonusMinor)
        assertEquals(55_000L, actual.replacedBonuses["PLN"])
    }

    @Test
    fun aPayoutReplacesTheFixedSalaryOfItsMonth() {
        val actual = novemberActual(listOf(payout(office, november, 250_000)))
        assertEquals(250_000L, actual.totalEarnings["EUR"])
        val officeRow = actual.perJob.single { it.job.id == 2L }
        assertEquals(0L, officeRow.countedSalaryMinor)
        assertEquals(0L, actual.countedSalaries["EUR"])
        assertEquals(ReceivedPart.ALL, officeRow.receivedPart)
    }

    @Test
    fun twoJobsInAMonthOnlyTheOneWithPayoutsIsReplaced() {
        val calculated = novemberCalculated()
        val actual = novemberActual(listOf(payout(cafe, november, 70_000)))

        assertEquals(70_000L, actual.totalEarnings["PLN"])
        assertEquals("the office's salary stays calculated", calculated.totalEarnings["EUR"], actual.totalEarnings["EUR"])
        assertEquals(ReceivedPart.NONE, actual.perJob.single { it.job.id == 2L }.receivedPart)
        assertEquals(ReceivedPart.PART, actual.receivedPart)
        assertTrue(actual.received.currencies == setOf("PLN"))
    }

    @Test
    fun theWholeMonthIsReceivedWhenEveryJobWithMoneyHasPayouts() {
        val actual = novemberActual(listOf(payout(cafe, november, 70_000), payout(office, november, 250_000)))
        assertEquals(ReceivedPart.ALL, actual.receivedPart)
    }

    @Test
    fun receivedIsTheSameInGrossAndNetWhileTheOtherJobFollowsTheMode() {
        val payouts = listOf(payout(cafe, november, 70_000))
        val gross = novemberActual(payouts, EarningsMode.GROSS)
        val net = novemberActual(payouts, EarningsMode.NET)
        assertEquals(70_000L, gross.totalEarnings["PLN"])
        assertEquals(70_000L, net.totalEarnings["PLN"])
        assertEquals(300_000L, gross.totalEarnings["EUR"])
        assertEquals(240_000L, net.totalEarnings["EUR"])
        // In Net the replaced figure is the net one: never a mix of modes.
        assertEquals(novemberCalculated(EarningsMode.NET).perJob.single { it.job.id == 1L }.earningsGrosze, net.replaced["PLN"])
    }

    @Test
    fun hoursShiftCountsAndTheAverageRateStayCalculated() {
        val calculated = novemberCalculated()
        val actual = novemberActual(listOf(payout(cafe, november, 1_000)))
        assertEquals(calculated.totalPaidMinutes, actual.totalPaidMinutes)
        assertEquals(calculated.shiftCount, actual.shiftCount)
        assertEquals(calculated.bonusCount, actual.bonusCount)
        assertEquals(calculated.shiftEarnings, actual.shiftEarnings)
        assertEquals(3_000L, actual.averageRate("PLN"))
        val cafeRow = actual.perJob.single { it.job.id == 1L }
        assertEquals(16 * 60L, cafeRow.paidMinutes)
        assertEquals(2, cafeRow.shiftCount)
    }

    @Test
    fun aJobWithPayoutsButNoEntriesStillCounts() {
        val actual = novemberActual(listOf(payout(shop, november, 10_000)))
        val shopRow = actual.perJob.single { it.job.id == 3L }
        assertEquals(10_000L, shopRow.earningsGrosze)
        assertEquals(0L, shopRow.paidMinutes)
        assertEquals(ReceivedPart.ALL, shopRow.receivedPart)
        assertEquals(103_000L + 10_000L, actual.totalEarnings["PLN"])
        assertFalse(PayReconciler.actualSummary(emptyList(), emptyList(), emptyList(), listOf(november), EarningsMode.GROSS, listOf(payout(shop, november, 10_000))).isEmpty)
    }

    @Test
    fun payoutsForOtherMonthsAndAWeekChangeNothing() {
        assertEquals(novemberCalculated(), novemberActual(listOf(payout(cafe, october, 70_000))))
        // A week covers no whole month: received pay never counts there.
        val week = PayReconciler.actualSummary(novemberShifts, listOf(dayBonus), jobs, emptyList(), EarningsMode.GROSS, listOf(payout(cafe, november, 70_000)))
        assertEquals(ShiftCalculator.summarize(novemberShifts, listOf(dayBonus), jobs, emptyList(), EarningsMode.GROSS), week)
    }

    @Test
    fun theYearTotalIsTheSumOfItsMonthsWithTheirReceivedPay() {
        val payouts = listOf(payout(cafe, november, 70_000), payout(office, october, 250_000))
        val actualYear = PayReconciler.actualSummary(shifts, bonuses, jobs, year, EarningsMode.GROSS, payouts)

        val months = year.map { month ->
            PayReconciler.actualSummary(
                shifts.filter { YearMonth.from(it.shift.date) == month }, bonuses.filter { it.bonus.month == month },
                jobs, listOf(month), EarningsMode.GROSS, payouts,
            )
        }
        assertEquals(months.sumOf { it.totalEarnings["PLN"] }, actualYear.totalEarnings["PLN"])
        assertEquals(months.sumOf { it.totalEarnings["EUR"] }, actualYear.totalEarnings["EUR"])
        // October cafe stays calculated (240 zł) next to November's 700 zł received; office: 2 500 € + 3 000 €.
        assertEquals(24_000L + 70_000L, actualYear.totalEarnings["PLN"])
        assertEquals(250_000L + 300_000L, actualYear.totalEarnings["EUR"])
        assertEquals(ReceivedPart.PART, actualYear.perJob.single { it.job.id == 1L }.receivedPart)
        assertEquals(1, actualYear.perJob.single { it.job.id == 1L }.receivedMonths)
        assertEquals(ReceivedPart.PART, actualYear.receivedPart)
    }
}

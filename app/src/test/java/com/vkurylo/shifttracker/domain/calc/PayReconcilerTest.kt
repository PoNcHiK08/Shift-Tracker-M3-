package com.vkurylo.shifttracker.domain.calc

import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.model.PayType
import com.vkurylo.shifttracker.domain.model.Payout
import com.vkurylo.shifttracker.domain.model.PayoutWithJob
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * Received pay is compared with the calculated NET pay of its month, per job. The totals count it through
 * [PayReconciler.actualSummary] (see ActualPayTest).
 */
class PayReconcilerTest {
    private val october = YearMonth.of(2026, 10)
    private val november = YearMonth.of(2026, 11)
    private val day = november.atDay(3)

    // 10 % deductions; a 3 000 € salary job with 20 %.
    private val cafe = Job(
        id = 1, name = "Cafe", hourlyRateGrosze = 3000, color = 0,
        payHistory = listOf(PaySettings(deductionBp = 1_000)), firstEntry = october.atDay(5), lastEntry = day,
    )
    private val office = Job(
        id = 2, name = "Office", hourlyRateGrosze = 0, color = 0, currency = "EUR",
        payHistory = listOf(PaySettings(null, PayType.FIXED_MONTHLY, 300_000, deductionBp = 2_000)), firstEntry = october.atDay(1), lastEntry = day,
    )
    private val jobs = listOf(cafe, office)

    private fun shift(job: Job, date: LocalDate, id: Long = 1) =
        ShiftWithJob(Shift(id = id, jobId = job.id, date = date, start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = job.hourlyRateGrosze), job)

    private val shifts = listOf(shift(cafe, day), shift(cafe, october.atDay(10), id = 2))
    private val dayBonus = BonusWithJob(Bonus(id = 1, jobId = 1, date = day.plusDays(1), amountGrosze = 5_000), cafe)
    private val monthBonus = BonusWithJob(Bonus(id = 2, jobId = 1, date = november.atDay(1), amountGrosze = 50_000, wholeMonth = true), cafe)

    private fun payout(job: Job, month: YearMonth, amount: Long, id: Long = 0, receivedOn: LocalDate? = null) =
        PayoutWithJob(Payout(id = id, jobId = job.id, month = month, amountMinor = amount, receivedOn = receivedOn), job)

    private fun forNovember(payouts: List<PayoutWithJob>) =
        PayReconciler.forMonth(november, jobs, shifts, listOf(dayBonus, monthBonus), payouts)

    @Test
    fun theCalculatedFigureIsTheNetPayOfTheMonthWithItsBonusesAndSalary() {
        val rows = forNovember(emptyList())

        val cafeRow = rows.single { it.job.id == 1L }
        // 8 h at 30 zł = 240 zł, a bonus of 50 zł and a month bonus of 500 zł, each less 10 %.
        assertEquals(21_600L + 4_500L + 45_000L, cafeRow.calculatedNetMinor)
        val officeRow = rows.single { it.job.id == 2L }
        assertEquals("a salary less 20 %", 240_000L, officeRow.calculatedNetMinor)
        // Like the summary's per-job list: the currency with the biggest total first, never one total across them.
        assertEquals(setOf("PLN", "EUR"), rows.map { it.currency }.toSet())
    }

    @Test
    fun theDifferenceIsReceivedMinusCalculatedAndSeveralPayoutsAreSummed() {
        val rows = forNovember(
            listOf(
                payout(cafe, november, 60_000, id = 1),
                payout(cafe, november, 10_000, id = 2, receivedOn = november.atEndOfMonth().plusDays(2)),
            ),
        )
        val row = rows.single { it.job.id == 1L }
        assertEquals(70_000L, row.receivedMinor)
        assertEquals(2, row.payouts.size)
        assertEquals("700,00 arrived against 711,00 calculated", -1_100L, row.differenceMinor)
    }

    @Test
    fun withNothingEnteredThereIsNothingToCompare() {
        val row = forNovember(emptyList()).single { it.job.id == 1L }
        assertEquals(0L, row.receivedMinor)
        assertNull(row.differenceMinor)
        assertEquals(emptyList<Payout>(), row.payouts)
    }

    @Test
    fun aPayoutBelongsToTheMonthItWasEarnedForNotTheMonthItArrivedIn() {
        // Earned for October, received on 2 November: it is October's, and November has none.
        val arrivedInNovember = payout(cafe, october, 20_000, receivedOn = november.atDay(2))
        assertEquals(emptyList<Payout>(), forNovember(listOf(arrivedInNovember)).single { it.job.id == 1L }.payouts)

        val octoberRow = PayReconciler.forMonth(october, jobs, shifts, listOf(dayBonus, monthBonus), listOf(arrivedInNovember)).single { it.job.id == 1L }
        assertEquals(20_000L, octoberRow.receivedMinor)
        assertEquals("only October's shift: 240 zł less 10 %", 21_600L, octoberRow.calculatedNetMinor)
        assertEquals(20_000L - 21_600L, octoberRow.differenceMinor)
    }

    @Test
    fun aMonthWithPayoutsButNoEntriesStillGetsARow() {
        val rows = PayReconciler.forMonth(YearMonth.of(2026, 12), jobs, shifts, listOf(dayBonus, monthBonus), listOf(payout(cafe, YearMonth.of(2026, 12), 1_000)))
        val row = rows.single { it.job.id == 1L }
        assertEquals(0L, row.calculatedNetMinor)
        assertEquals(1_000L, row.differenceMinor)
        // The salary job is active in December? Its last entry is in November: no salary, and no row.
        assertEquals(listOf(1L), rows.map { it.job.id })
    }

    @Test
    fun currenciesStayOnTheirOwnJobs() {
        val rows = forNovember(listOf(payout(cafe, november, 70_000), payout(office, november, 240_000)))
        assertEquals(mapOf("PLN" to -1_100L, "EUR" to 0L), rows.associate { it.currency to it.differenceMinor })
        assertEquals(mapOf("PLN" to 70_000L, "EUR" to 240_000L), rows.associate { it.currency to it.receivedMinor })
    }

    @Test
    fun theComparisonIsAlwaysAgainstNetWhateverModeTheScreensShow() {
        val payouts = listOf(payout(cafe, november, 71_100))
        // The calculation takes no display mode at all; the gross total next to it is a different number.
        val grossTotal = ShiftCalculator.summarize(
            shifts.filter { YearMonth.from(it.shift.date) == november }, listOf(dayBonus, monthBonus), jobs, listOf(november), EarningsMode.GROSS,
        ).perJob.single { it.job.id == 1L }.earningsGrosze
        val row = forNovember(payouts).single { it.job.id == 1L }
        assertEquals(24_000L + 5_000L + 50_000L, grossTotal)
        assertEquals(71_100L, row.calculatedNetMinor)
        assertEquals(0L, row.differenceMinor)
    }

    @Test
    fun receivedPayReplacesTheJobMonthsEarningsAndChangesNothingElse() {
        // Was "received pay never changes any earnings total"; received pay now counts as the real money of its
        // job-month: it replaces that job-month's calculated money (never adds to it) and leaves everything else.
        val bonuses = listOf(dayBonus, monthBonus)
        val shifts = shifts.filter { YearMonth.from(it.shift.date) == november }
        val calculated = ShiftCalculator.summarize(shifts, bonuses, jobs, listOf(november), EarningsMode.NET)
        val payouts = listOf(payout(cafe, november, 999_999))
        // The comparison itself still changes nothing.
        PayReconciler.forMonth(november, jobs, shifts, bonuses, payouts)
        PayReconciler.forYear(2026, jobs, shifts, bonuses, payouts)
        assertEquals(calculated, ShiftCalculator.summarize(shifts, bonuses, jobs, listOf(november), EarningsMode.NET))

        val actual = PayReconciler.actualSummary(shifts, bonuses, jobs, listOf(november), EarningsMode.NET, payouts)
        val cafeCalculated = calculated.perJob.single { it.job.id == 1L }.earningsGrosze
        assertEquals(calculated.totalEarnings["PLN"] - cafeCalculated + 999_999, actual.totalEarnings["PLN"])
        assertEquals(calculated.totalEarnings["EUR"], actual.totalEarnings["EUR"])
        assertEquals(calculated.totalPaidMinutes, actual.totalPaidMinutes)
        assertEquals(calculated.shiftCount, actual.shiftCount)
        assertEquals(calculated.averageRate("PLN"), actual.averageRate("PLN"))
        assertEquals(calculated.shiftEarnings, actual.shiftEarnings)
    }

    @Test
    fun theYearLineCoversOnlyTheMonthsThatHavePayouts() {
        val payouts = listOf(
            payout(cafe, october, 20_000),
            payout(cafe, november, 70_000),
            payout(cafe, november, 1_100),
            payout(office, YearMonth.of(2025, 12), 5_000), // another year
        )
        val lines = PayReconciler.forYear(2026, jobs, shifts, listOf(dayBonus, monthBonus), payouts)

        val line = lines.single()
        assertEquals(cafe, line.job)
        assertEquals(listOf(october, november), line.months.map { it.month })
        assertEquals(20_000L + 71_100L, line.receivedMinor)
        assertEquals("October 240 zł and November 711 zł, net", 21_600L + 71_100L, line.calculatedNetMinor)
        assertEquals(20_000L - 21_600L, line.differenceMinor)
    }

    @Test
    fun aYearWithoutPayoutsHasNoLines() {
        assertEquals(emptyList<Any>(), PayReconciler.forYear(2026, jobs, shifts, listOf(dayBonus, monthBonus), emptyList()))
    }
}

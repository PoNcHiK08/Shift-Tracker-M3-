package com.vkurylo.shifttracker.domain.calc

import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.domain.model.forWholeMonths
import com.vkurylo.shifttracker.domain.model.onDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** A bonus for the whole month counts like any bonus in money, and is the month's only: no day, no week. */
class MonthBonusCalculationTest {
    private val november = YearMonth.of(2026, 11)
    private val day = november.atDay(3)

    // 10 % deductions from November 2026 on; none before.
    private val cafe = Job(
        id = 1, name = "Cafe", hourlyRateGrosze = 3000, color = 0,
        payHistory = listOf(PaySettings(), PaySettings(from = november, deductionBp = 1_000)),
        firstEntry = day, lastEntry = day,
    )
    private val shift = ShiftWithJob(Shift(id = 1, jobId = 1, date = day, start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3000), cafe)
    private val dayBonus = BonusWithJob(Bonus(id = 1, jobId = 1, date = day, amountGrosze = 5_000), cafe)
    private val monthBonus = BonusWithJob(Bonus(id = 2, jobId = 1, date = november.atDay(1), amountGrosze = 50_000, wholeMonth = true), cafe)

    @Test
    fun aMonthBonusCountsInEarningsGrossAndNetByItsMonthsDeductions() {
        assertEquals(50_000L, ShiftCalculator.bonusAmount(monthBonus, EarningsMode.GROSS))
        assertEquals(45_000L, ShiftCalculator.bonusAmount(monthBonus, EarningsMode.NET))

        val gross = ShiftCalculator.summarize(listOf(shift), listOf(dayBonus, monthBonus), listOf(cafe), listOf(november), EarningsMode.GROSS)
        val net = ShiftCalculator.summarize(listOf(shift), listOf(dayBonus, monthBonus), listOf(cafe), listOf(november), EarningsMode.NET)
        assertEquals(55_000L, gross.bonuses["PLN"])
        assertEquals(24_000L + 55_000L, gross.totalEarnings["PLN"])
        assertEquals(4_500L + 45_000L, net.bonuses["PLN"])
        assertEquals(21_600L + 49_500L, net.totalEarnings["PLN"])
        assertEquals(2, gross.bonusCount)
        assertEquals(79_000L, gross.perJob.single().earningsGrosze)
    }

    @Test
    fun aMonthBonusInAMonthWithoutDeductionsIsNotReduced() {
        val october = YearMonth.of(2026, 10)
        val bonus = monthBonus.copy(bonus = monthBonus.bonus.copy(date = october.atDay(1)))
        assertEquals(50_000L, ShiftCalculator.bonusAmount(bonus, EarningsMode.NET))
    }

    @Test
    fun aMonthBonusAddsMoneyButNeitherHoursNorTheAverageRate() {
        val without = ShiftCalculator.summarize(listOf(shift), listOf(dayBonus), listOf(cafe), listOf(november))
        val with = ShiftCalculator.summarize(listOf(shift), listOf(dayBonus, monthBonus), listOf(cafe), listOf(november))

        assertEquals(without.totalPaidMinutes, with.totalPaidMinutes)
        assertEquals(without.shiftCount, with.shiftCount)
        assertEquals(without.shiftEarnings, with.shiftEarnings)
        assertEquals(without.averageRate("PLN"), with.averageRate("PLN"))
        assertEquals(without.totalEarnings["PLN"] + 50_000, with.totalEarnings["PLN"])
    }

    @Test
    fun aMonthWithOnlyAMonthBonusIsNotEmpty() {
        val summary = ShiftCalculator.summarize(emptyList(), listOf(monthBonus), listOf(cafe), listOf(november))
        assertEquals(50_000L, summary.totalEarnings["PLN"])
        assertEquals(false, summary.isEmpty)
        assertEquals(0L, summary.totalPaidMinutes)
    }

    @Test
    fun theDayHelpersSplitBonusesByKind() {
        val all = listOf(dayBonus, monthBonus)
        assertEquals(listOf(dayBonus), all.onDays())
        assertEquals(listOf(monthBonus), all.forWholeMonths())
        assertTrue(emptyList<BonusWithJob>().onDays().isEmpty())
    }

    @Test
    fun aDaysEarningsAreBuiltFromItsDayBonusesOnly() {
        val first = november.atDay(1)
        // What the day panel and the week breakdown use: the bonuses on days.
        val onTheFirst = listOf(dayBonus.copy(bonus = dayBonus.bonus.copy(date = first)), monthBonus)
        assertEquals(5_000L, ShiftCalculator.earnings(emptyList(), onTheFirst.onDays(), EarningsMode.GROSS)["PLN"])
        assertEquals(1, onTheFirst.filter { it.bonus.date == first }.onDays().size)
    }

    @Test
    fun aMonthBonusKeepsAFixedSalaryJobActiveOnTheFirstOfItsMonth() {
        // A bonus dated the 1st is job activity like any bonus (Job.firstEntry/lastEntry come from the dates).
        val office = Job(
            id = 3, name = "Office", hourlyRateGrosze = 0, color = 0,
            payHistory = listOf(PaySettings(null, com.vkurylo.shifttracker.domain.model.PayType.FIXED_MONTHLY, 400_000)),
            firstEntry = november.atDay(1), lastEntry = november.atDay(1),
        )
        assertEquals(400_000L, ShiftCalculator.salary(office, november))
        assertEquals(0L, ShiftCalculator.salary(office, november.plusMonths(1)))
    }

    @Test
    fun theMonthIsTheBonusesMonthWhateverDayItIsKeptOn() {
        assertEquals(november, monthBonus.bonus.month)
        assertEquals(LocalDate.of(2026, 11, 1), monthBonus.bonus.date)
    }
}

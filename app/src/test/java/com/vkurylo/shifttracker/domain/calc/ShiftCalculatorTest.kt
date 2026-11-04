package com.vkurylo.shifttracker.domain.calc

import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.model.PayType
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.domain.model.TimedRate
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
        assertEquals(36000L + 21000L, summary.totalEarnings["PLN"])
        assertEquals(listOf("Cafe", "Shop"), summary.perJob.map { it.job.name })
        assertEquals(2, summary.perJob[0].shiftCount)
        assertEquals(36000L, summary.perJob[0].earningsGrosze)
        assertEquals(21000L, summary.perJob[1].earningsGrosze)
    }

    @Test
    fun `bonuses add to earnings but not to hours or the average rate`() {
        val bk = Job(id = 1, name = "Burger King", hourlyRateGrosze = 3300, color = 0)
        val gift = Job(id = 2, name = "Only bonus", hourlyRateGrosze = 3000, color = 0)
        val date = LocalDate.of(2026, 8, 31)
        val shifts = listOf(
            ShiftWithJob(Shift(id = 1, jobId = 1, date = date, start = t(10), end = t(18), hourlyRateGrosze = 3300), bk),
        )
        val bonuses = listOf(
            BonusWithJob(Bonus(id = 1, jobId = 1, date = date, amountGrosze = 7500, note = "Premia"), bk),
            BonusWithJob(Bonus(id = 2, jobId = 2, date = date, amountGrosze = 1000), gift),
        )

        val summary = ShiftCalculator.summarize(shifts, bonuses)

        assertEquals(26400L, summary.shiftEarnings["PLN"]) // 8 h * 33
        assertEquals(8500L, summary.bonuses["PLN"])
        assertEquals(34900L, summary.totalEarnings["PLN"])
        assertEquals(480L, summary.totalPaidMinutes)
        assertEquals(3300L, summary.averageRate("PLN")) // bonuses don't inflate the hourly rate
        assertEquals(1, summary.shiftCount)
        assertEquals(2, summary.bonusCount)
        // A job with only a bonus in the period still shows up in the breakdown.
        assertEquals(listOf("Burger King", "Only bonus"), summary.perJob.map { it.job.name })
        assertEquals(33900L, summary.perJob[0].earningsGrosze)
        assertEquals(0, summary.perJob[1].shiftCount)
    }

    @Test
    fun `bonuses alone make a non-empty summary`() {
        val job = Job(id = 1, name = "BK", hourlyRateGrosze = 3300, color = 0)
        val summary = ShiftCalculator.summarize(
            emptyList(),
            listOf(BonusWithJob(Bonus(jobId = 1, date = LocalDate.of(2026, 8, 31), amountGrosze = 7500), job)),
        )
        assertEquals(false, summary.isEmpty)
        assertEquals(7500L, summary.totalEarnings["PLN"])
        assertEquals(0L, summary.averageRate("PLN"))
    }

    @Test
    fun `largest allowed rate over a full day is exact (no Long overflow)`() {
        val max = MoneyParser.MAX_GROSZE
        val base = ShiftCalculator.pay(t(0), t(0), 0, max, null)
        assertEquals(1440L, base.paidMinutes)
        assertEquals(24 * max, base.earningsGrosze)
        val night = TimedRate(max, t(22), t(6))
        assertEquals(24 * max, ShiftCalculator.pay(t(0), t(0), 0, max, night).earningsGrosze)
    }
    @Test
    fun `amounts in different currencies are never added together`() {
        val warsaw = Job(id = 1, name = "Warsaw", hourlyRateGrosze = 3000, color = 0, currency = "PLN")
        val berlin = Job(id = 2, name = "Berlin", hourlyRateGrosze = 1500, color = 0, currency = "EUR")
        val tokyo = Job(id = 3, name = "Tokyo", hourlyRateGrosze = 1200, color = 0, currency = "JPY")
        val date = LocalDate.of(2026, 11, 4)
        val shifts = listOf(
            ShiftWithJob(Shift(id = 1, jobId = 1, date = date, start = t(8), end = t(16), hourlyRateGrosze = 3000), warsaw),
            ShiftWithJob(Shift(id = 2, jobId = 2, date = date, start = t(8), end = t(12), hourlyRateGrosze = 1500), berlin),
            ShiftWithJob(Shift(id = 3, jobId = 3, date = date, start = t(9), end = t(10), hourlyRateGrosze = 1200), tokyo),
        )
        val bonuses = listOf(BonusWithJob(Bonus(jobId = 2, date = date, amountGrosze = 5000), berlin))

        val summary = ShiftCalculator.summarize(shifts, bonuses)

        assertEquals(mapOf("PLN" to 24000L, "EUR" to 11000L, "JPY" to 1200L), summary.totalEarnings.byCurrency)
        assertEquals(6000L, summary.shiftEarnings["EUR"])
        assertEquals(5000L, summary.bonuses["EUR"])
        assertEquals(0L, summary.bonuses["PLN"])
        // Hours are plain totals; the average rate is per currency, over that currency's hours.
        assertEquals(13 * 60L, summary.totalPaidMinutes)
        assertEquals(3000L, summary.averageRate("PLN"))
        assertEquals(1500L, summary.averageRate("EUR"))
        assertEquals(1200L, summary.averageRate("JPY"))
        // Largest first (by number, only to order the lines); jobs grouped by currency.
        assertEquals(listOf("PLN", "EUR", "JPY"), summary.currencies)
        assertEquals(listOf("Warsaw", "Berlin", "Tokyo"), summary.perJob.map { it.job.name })
        // A day's total.
        assertEquals(mapOf("PLN" to 24000L, "EUR" to 11000L, "JPY" to 1200L), ShiftCalculator.earnings(shifts, bonuses).byCurrency)
    }
    // --- Pay types --------------------------------------------------------------------------------

    private val nov = java.time.YearMonth.of(2026, 11)

    private fun salaried(history: List<PaySettings>, first: LocalDate?, last: LocalDate?) = Job(
        id = 5, name = "Office", hourlyRateGrosze = 4000, color = 0, currency = "EUR",
        payHistory = history, firstEntry = first, lastEntry = last,
    )

    @Test
    fun `a fixed monthly salary counts once per month and its shifts only log hours`() {
        val office = salaried(listOf(PaySettings(null, PayType.FIXED_MONTHLY, 300_000)), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 20))
        val shifts = listOf(
            ShiftWithJob(Shift(id = 1, jobId = 5, date = LocalDate.of(2026, 11, 2), start = t(9), end = t(17), hourlyRateGrosze = 4000), office),
            ShiftWithJob(Shift(id = 2, jobId = 5, date = LocalDate.of(2026, 11, 3), start = t(9), end = t(17), hourlyRateGrosze = 4000), office),
        )
        val bonuses = listOf(BonusWithJob(Bonus(jobId = 5, date = LocalDate.of(2026, 11, 3), amountGrosze = 10_000), office))

        val month = ShiftCalculator.summarize(shifts, bonuses, listOf(office), listOf(nov))
        assertEquals(300_000L, month.salaries["EUR"])
        assertEquals(0L, month.shiftEarnings["EUR"])
        assertEquals(310_000L, month.totalEarnings["EUR"]) // salary + bonus, no hourly pay
        assertEquals(16 * 60L, month.totalPaidMinutes)
        assertEquals(0L, month.averageRate("EUR")) // salaried time isn't hourly time
        assertEquals(2, month.fixedPayShiftCount)
        assertEquals(300_000L, month.perJob.single().salaryMinor)

        // A week has no whole month: hours only, no salary, no money from the shifts.
        val week = ShiftCalculator.summarize(shifts, emptyList(), listOf(office), emptyList())
        assertEquals(0L, week.totalEarnings["EUR"])
        assertEquals(16 * 60L, week.totalPaidMinutes)
        assertEquals(0L, ShiftCalculator.shiftEarnings(shifts[0]))
    }

    @Test
    fun `the salary counts from the first to the last month with entries, gaps included`() {
        val office = salaried(listOf(PaySettings(null, PayType.FIXED_MONTHLY, 300_000)), LocalDate.of(2026, 3, 10), LocalDate.of(2026, 6, 1))
        val year = (1..12).map { java.time.YearMonth.of(2026, it) }
        val summary = ShiftCalculator.summarize(emptyList(), emptyList(), listOf(office), year)
        // March, April (leave, no entries), May and June.
        assertEquals(4 * 300_000L, summary.salaries["EUR"])
        assertEquals(0L, ShiftCalculator.salary(office, java.time.YearMonth.of(2026, 2)))
        assertEquals(300_000L, ShiftCalculator.salary(office, java.time.YearMonth.of(2026, 4)))
        assertEquals(0L, ShiftCalculator.salary(office, java.time.YearMonth.of(2026, 7)))
        // A job that never had an entry earns no salary.
        assertEquals(0L, ShiftCalculator.salary(salaried(office.payHistory, null, null), nov))
    }

    @Test
    fun `changing pay from a month on leaves earlier months as they were`() {
        // Hourly until June, a 3 000 € salary from July, raised to 3 500 € from October.
        val history = listOf(
            PaySettings(java.time.YearMonth.of(2026, 7), PayType.FIXED_MONTHLY, 300_000),
            PaySettings(java.time.YearMonth.of(2026, 10), PayType.FIXED_MONTHLY, 350_000),
        )
        val office = salaried(history, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 12, 1))
        val june = ShiftWithJob(Shift(id = 1, jobId = 5, date = LocalDate.of(2026, 6, 8), start = t(9), end = t(17), hourlyRateGrosze = 4000), office)
        val july = ShiftWithJob(Shift(id = 2, jobId = 5, date = LocalDate.of(2026, 7, 8), start = t(9), end = t(17), hourlyRateGrosze = 4000), office)

        assertEquals(32_000L, ShiftCalculator.shiftEarnings(june)) // still hourly
        assertEquals(0L, ShiftCalculator.shiftEarnings(july))
        assertEquals(0L, ShiftCalculator.salary(office, java.time.YearMonth.of(2026, 6)))
        assertEquals(300_000L, ShiftCalculator.salary(office, java.time.YearMonth.of(2026, 9)))
        assertEquals(350_000L, ShiftCalculator.salary(office, java.time.YearMonth.of(2026, 10)))
        val year = ShiftCalculator.summarize(listOf(june, july), emptyList(), listOf(office), (1..12).map { java.time.YearMonth.of(2026, it) })
        assertEquals(32_000L + 3 * 300_000L + 3 * 350_000L, year.totalEarnings["EUR"])
    }

    @Test
    fun `whole months of a period`() {
        assertEquals(listOf(nov), ShiftCalculator.wholeMonths(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)))
        assertEquals(emptyList<java.time.YearMonth>(), ShiftCalculator.wholeMonths(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 8)))
        assertEquals(12, ShiftCalculator.wholeMonths(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)).size)
    }
    // --- Net -----------------------------------------------------------------------------------------

    @Test
    fun `net subtracts the deduction rounded half up in minor units`() {
        assertEquals(8_629L, ShiftCalculator.net(10_000, 1_371)) // 13.71 % of 100.00 = 13.71
        assertEquals(8_624L, ShiftCalculator.net(10_005, 1_380)) // 1 380.69 minor units -> 1 381 deducted
        assertEquals(1L, ShiftCalculator.net(3, 5_000)) // 1.5 -> 2 deducted (half up)
        assertEquals(0L, ShiftCalculator.net(1, 5_000)) // 0.5 -> 1 deducted
        assertEquals(3L, ShiftCalculator.net(3, 1_000)) // 0.3 -> 0 deducted
        assertEquals(0L, ShiftCalculator.net(24_100, 10_000)) // 100 %
        assertEquals(24_100L, ShiftCalculator.net(24_100, 0))
        // The largest amount at 100 % doesn't overflow.
        val largest = MoneyParser.maxMinor(3, MoneyParser.AMOUNT_WHOLE_DIGITS)
        assertEquals(0L, ShiftCalculator.net(largest, 10_000))
        assertEquals(largest - (largest * 1_371 + 5_000) / 10_000, ShiftCalculator.net(largest, 1_371))
    }

    @Test
    fun `net mode applies each month's deduction to shifts, bonuses and salaries`() {
        // 10 % from the start, 20 % from October: September stays at 10 %.
        val history = listOf(
            PaySettings(null, PayType.HOURLY, deductionBp = 1_000),
            PaySettings(java.time.YearMonth.of(2026, 10), PayType.HOURLY, deductionBp = 2_000),
        )
        val cafe = Job(id = 1, name = "Cafe", hourlyRateGrosze = 3000, color = 0, payHistory = history,
            firstEntry = LocalDate.of(2026, 9, 1), lastEntry = LocalDate.of(2026, 10, 31))
        val sep = ShiftWithJob(Shift(id = 1, jobId = 1, date = LocalDate.of(2026, 9, 7), start = t(8), end = t(16), hourlyRateGrosze = 3000), cafe)
        val oct = ShiftWithJob(Shift(id = 2, jobId = 1, date = LocalDate.of(2026, 10, 7), start = t(8), end = t(16), hourlyRateGrosze = 3000), cafe)
        val bonus = BonusWithJob(Bonus(jobId = 1, date = LocalDate.of(2026, 10, 9), amountGrosze = 1_005), cafe)

        assertEquals(24_000L, ShiftCalculator.shiftEarnings(sep))
        assertEquals(21_600L, ShiftCalculator.shiftEarnings(sep, EarningsMode.NET))
        assertEquals(19_200L, ShiftCalculator.shiftEarnings(oct, EarningsMode.NET))
        assertEquals(804L, ShiftCalculator.bonusAmount(bonus, EarningsMode.NET)) // 20 % of 10.05 = 2.01 deducted

        val gross = ShiftCalculator.summarize(listOf(sep, oct), listOf(bonus), listOf(cafe))
        val net = ShiftCalculator.summarize(listOf(sep, oct), listOf(bonus), listOf(cafe), mode = EarningsMode.NET)
        assertEquals(49_005L, gross.totalEarnings["PLN"])
        assertEquals(21_600L + 19_200L + 804L, net.totalEarnings["PLN"]) // the sum of what the entries show
        assertEquals(2_550L, net.averageRate("PLN")) // (216 + 192) zl over 16 h

        // A salary is netted too.
        val office = Job(id = 2, name = "Office", hourlyRateGrosze = 0, color = 0, currency = "EUR",
            payHistory = listOf(PaySettings(null, PayType.FIXED_MONTHLY, 300_000, deductionBp = 1_371)),
            firstEntry = LocalDate.of(2026, 11, 2), lastEntry = LocalDate.of(2026, 11, 2))
        assertEquals(258_870L, ShiftCalculator.salary(office, nov, EarningsMode.NET))
    }
}

package com.vkurylo.shifttracker.domain.calc

import com.vkurylo.shifttracker.domain.model.Amounts
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PayType
import com.vkurylo.shifttracker.domain.model.isActiveIn
import com.vkurylo.shifttracker.domain.model.payFor
import com.vkurylo.shifttracker.domain.model.paysHourly
import com.vkurylo.shifttracker.domain.model.JobSummary
import com.vkurylo.shifttracker.domain.model.MonthSummary
import com.vkurylo.shifttracker.domain.model.ShiftPay
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.domain.model.TimedRate
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.YearMonth

object ShiftCalculator {

    /** A shift whose end is not after its start finishes on the following day. */
    fun isOvernight(start: LocalTime, end: LocalTime): Boolean = !end.isAfter(start)

    /*
     * Time. A shift is stored as its start date plus a start and an end on the clock. Its length is the real
     * time that passes between the two in [zone] (the phone's time zone): one hour more on the night the clocks
     * go back, one hour less on the night they go forward. A time the clocks skip counts as the moment just after
     * the skipped hour; a time that happens twice (the hour the clocks go back) as its first occurrence.
     *
     * The overloads without a date read the clock face only, as if no clock change ever happened (the same as a
     * zone without daylight saving time). They are for checks that must not depend on the phone's zone (backup and
     * cloud records), not for what a shift lasts or earns.
     */

    /** Real minutes between start and end of a shift starting on [date], crossing midnight when needed. */
    fun durationMinutes(date: LocalDate, start: LocalTime, end: LocalTime, zone: ZoneId = ZoneId.systemDefault()): Long {
        val (from, to) = span(date, start, end, zone)
        return (to - from).coerceAtLeast(0)
    }

    /** Clock-face minutes between start and end, crossing midnight when needed (no clock change). */
    fun durationMinutes(start: LocalTime, end: LocalTime): Long = durationMinutes(CLOCK_FACE_DAY, start, end, ZoneOffset.UTC)

    /** Paid minutes = real duration minus the unpaid break, never negative. */
    fun paidMinutes(date: LocalDate, start: LocalTime, end: LocalTime, unpaidBreakMinutes: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
        (durationMinutes(date, start, end, zone) - unpaidBreakMinutes.coerceAtLeast(0)).coerceAtLeast(0)

    /** Clock-face paid minutes (no clock change). */
    fun paidMinutes(start: LocalTime, end: LocalTime, unpaidBreakMinutes: Int): Long =
        paidMinutes(CLOCK_FACE_DAY, start, end, unpaidBreakMinutes, ZoneOffset.UTC)

    /** Earnings in grosze at a single rate, rounded half-up to the nearest grosz. */
    fun earningsGrosze(paidMinutes: Long, hourlyRateGrosze: Long): Long {
        val numerator = paidMinutes * hourlyRateGrosze
        return (numerator + 30) / 60
    }

    /**
     * Real minutes of the shift starting on [date] (start..end, crossing midnight if needed) that fall inside the
     * daily window [window].from..[window].to (which may itself cross midnight), both on [zone]'s clocks.
     */
    fun minutesInWindow(date: LocalDate, start: LocalTime, end: LocalTime, window: TimedRate, zone: ZoneId = ZoneId.systemDefault()): Long {
        if (window.from == window.to) return 0
        val (shiftStart, shiftEnd) = span(date, start, end, zone)
        // A shift is at most 24 h long, so it can touch windows starting the day before, that day or the day after.
        var total = 0L
        for (day in -1L..1L) {
            val (windowStart, windowEnd) = span(date.plusDays(day), window.from, window.to, zone)
            val overlap = minOf(shiftEnd, windowEnd) - maxOf(shiftStart, windowStart)
            if (overlap > 0) total += overlap
        }
        return total
    }

    /** Clock-face minutes inside the window (no clock change). */
    fun minutesInWindow(start: LocalTime, end: LocalTime, window: TimedRate): Long =
        minutesInWindow(CLOCK_FACE_DAY, start, end, window, ZoneOffset.UTC)

    /**
     * Full pay calculation for a shift starting on [date]. Minutes inside the second-rate window are paid at that
     * rate, the rest at the base rate. The unpaid break is taken from both parts proportionally, since the app
     * doesn't know when exactly the break happened.
     */
    fun pay(
        date: LocalDate,
        start: LocalTime,
        end: LocalTime,
        unpaidBreakMinutes: Int,
        baseRateGrosze: Long,
        secondRate: TimedRate?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ShiftPay {
        val duration = durationMinutes(date, start, end, zone)
        // Only inside the hour the clocks skip (e.g. 02:00-03:00 that night): no time passes, nothing is earned.
        if (duration == 0L) return ShiftPay(0, 0, 0, 0)
        val paid = paidMinutes(date, start, end, unpaidBreakMinutes, zone)
        val secondMinutes = secondRate?.let { minutesInWindow(date, start, end, it, zone) }?.coerceAtMost(duration) ?: 0L
        val secondRateGrosze = secondRate?.rateGrosze ?: baseRateGrosze
        // earnings = (base part + second part) * paid / duration, in one exact integer division.
        val weighted = (duration - secondMinutes) * baseRateGrosze + secondMinutes * secondRateGrosze
        val numerator = weighted * paid
        val denominator = duration * 60
        val earnings = (numerator + denominator / 2) / denominator
        return ShiftPay(duration, paid, secondMinutes, earnings)
    }

    /** Clock-face pay (no clock change): for checks and comparisons that must not depend on the phone's zone. */
    fun pay(start: LocalTime, end: LocalTime, unpaidBreakMinutes: Int, baseRateGrosze: Long, secondRate: TimedRate?): ShiftPay =
        pay(CLOCK_FACE_DAY, start, end, unpaidBreakMinutes, baseRateGrosze, secondRate, ZoneOffset.UTC)

    /** Start and end of a shift (or a rate window) starting on [date], in epoch minutes. */
    private fun span(date: LocalDate, start: LocalTime, end: LocalTime, zone: ZoneId): Pair<Long, Long> {
        val endDate = if (isOvernight(start, end)) date.plusDays(1) else date
        return epochMinute(date, start, zone) to epochMinute(endDate, end, zone)
    }

    /** [ZonedDateTime.of] moves a skipped time past the gap and takes the earlier offset of a repeated one. */
    private fun epochMinute(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        Math.floorDiv(ZonedDateTime.of(date, time, zone).toEpochSecond(), 60L)

    /** Any day: in UTC every day is 24 h, so the clock face and real time agree. */
    private val CLOCK_FACE_DAY: LocalDate = LocalDate.of(2000, 1, 3)

    /**
     * What a shift earns: its hourly pay, or 0 when its job is paid a fixed monthly salary that month
     * (the shift still counts for hours).
     */
    fun shiftEarnings(item: ShiftWithJob, mode: EarningsMode = EarningsMode.GROSS): Long {
        val gross = if (item.job.paysHourly(item.shift.date)) item.shift.earningsGrosze else 0
        return inMode(gross, item.job, YearMonth.from(item.shift.date), mode)
    }

    /** A bonus, gross or after its job's deductions in the bonus's month. */
    fun bonusAmount(item: BonusWithJob, mode: EarningsMode = EarningsMode.GROSS): Long =
        inMode(item.bonus.amountGrosze, item.job, YearMonth.from(item.bonus.date), mode)

    /**
     * [gross] minus [deductionBp] basis points of it, the deduction rounded half-up in minor units:
     * 10 000 at 13.71 % → 8 629. Each shift, bonus and salary is netted on its own, so totals are the sum
     * of what the entries show. No overflow: amounts are at most ~1e13 (see [MoneyParser]), × 10 000.
     */
    fun net(gross: Long, deductionBp: Int): Long {
        if (deductionBp <= 0 || gross <= 0) return gross
        val deduction = (gross * deductionBp + 5_000) / 10_000
        return gross - deduction
    }

    private fun inMode(gross: Long, job: Job, month: YearMonth, mode: EarningsMode): Long =
        if (mode == EarningsMode.NET) net(gross, job.payFor(month).deductionBp) else gross

    /**
     * The monthly salary [job] earns in [month]: its amount when that month's settings are
     * [PayType.FIXED_MONTHLY] and the month lies between the job's first and last entry; 0 otherwise.
     */
    fun salary(job: Job, month: YearMonth, mode: EarningsMode = EarningsMode.GROSS): Long {
        val pay = job.payFor(month)
        val gross = if (pay.payType == PayType.FIXED_MONTHLY && job.isActiveIn(month)) pay.monthlyAmountMinor else 0
        return if (mode == EarningsMode.NET) net(gross, pay.deductionBp) else gross
    }

    /**
     * Totals plus a per-job breakdown (shifts, bonuses and monthly salaries). Money is totalled per
     * currency; jobs are sorted by currency (the biggest total first), then by earnings (highest first).
     *
     * [salaryMonths] are the whole months the period covers (none for a week): each job of [jobs] paid a
     * fixed salary in such a month adds it once, even in a month without entries of its own (leave).
     */
    fun summarize(
        shifts: List<ShiftWithJob>,
        bonuses: List<BonusWithJob> = emptyList(),
        jobs: List<Job> = emptyList(),
        salaryMonths: List<YearMonth> = emptyList(),
        mode: EarningsMode = EarningsMode.GROSS,
    ): MonthSummary {
        val salaries = jobs.associate { job -> job.id to salaryMonths.sumOf { salary(job, it, mode) } }.filterValues { it > 0 }
        if (shifts.isEmpty() && bonuses.isEmpty() && salaries.isEmpty()) return MonthSummary.EMPTY
        val shiftsByJob = shifts.groupBy { it.job.id }
        val bonusesByJob = bonuses.groupBy { it.job.id }
        val jobsById = jobs.associateBy { it.id }
        val unsorted = (shiftsByJob.keys + bonusesByJob.keys + salaries.keys).map { jobId ->
            val jobShifts = shiftsByJob[jobId].orEmpty()
            val jobBonuses = bonusesByJob[jobId].orEmpty()
            val hourly = jobShifts.filter { it.job.paysHourly(it.shift.date) }
            JobSummary(
                job = jobsById[jobId] ?: jobShifts.firstOrNull()?.job ?: jobBonuses.first().job,
                paidMinutes = jobShifts.sumOf { it.shift.paidMinutes },
                shiftEarningsGrosze = hourly.sumOf { shiftEarnings(it, mode) },
                bonusGrosze = jobBonuses.sumOf { bonusAmount(it, mode) },
                shiftCount = jobShifts.size,
                salaryMinor = salaries[jobId] ?: 0,
                hourlyPaidMinutes = hourly.sumOf { it.shift.paidMinutes },
            )
        }
        val shiftEarnings = Amounts.sum(unsorted, { it.currency }, { it.shiftEarningsGrosze })
        val bonusTotals = Amounts.sum(unsorted, { it.currency }, { it.bonusGrosze })
        val salaryTotals = Amounts.sum(unsorted.filter { it.salaryMinor > 0 }, { it.currency }, { it.salaryMinor })
        val currencyOrder = (shiftEarnings + bonusTotals + salaryTotals).sortedByValue().map { it.first }
        val perJob = unsorted.sortedWith(
            compareBy<JobSummary> { currencyOrder.indexOf(it.currency) }
                .thenByDescending { it.earningsGrosze }
                .thenBy { it.job.name },
        )
        return MonthSummary(
            totalPaidMinutes = perJob.sumOf { it.paidMinutes },
            shiftEarnings = shiftEarnings,
            bonuses = bonusTotals,
            shiftCount = shifts.size,
            bonusCount = bonuses.size,
            perJob = perJob,
            salaries = salaryTotals,
            fixedPayShiftCount = shifts.count { !it.job.paysHourly(it.shift.date) },
        )
    }

    /** Earnings of a set of shifts and bonuses (e.g. one day), per currency; no monthly salaries. */
    fun earnings(shifts: List<ShiftWithJob>, bonuses: List<BonusWithJob>, mode: EarningsMode = EarningsMode.GROSS): Amounts =
        Amounts.sum(shifts, { it.job.currency }, { shiftEarnings(it, mode) }) +
            Amounts.sum(bonuses, { it.job.currency }, { bonusAmount(it, mode) })

    /** The whole months between [from] and [to] (inclusive): where monthly salaries are counted. */
    fun wholeMonths(from: LocalDate, to: LocalDate): List<YearMonth> {
        val months = mutableListOf<YearMonth>()
        var month = YearMonth.from(from)
        while (!month.isAfter(YearMonth.from(to))) {
            if (!month.atDay(1).isBefore(from) && !month.atEndOfMonth().isAfter(to)) months += month
            month = month.plusMonths(1)
        }
        return months
    }
}

package com.vkurylo.shifttracker.domain.calc

import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.JobSummary
import com.vkurylo.shifttracker.domain.model.Amounts
import com.vkurylo.shifttracker.domain.model.MonthSummary
import com.vkurylo.shifttracker.domain.model.Payout
import com.vkurylo.shifttracker.domain.model.PayoutWithJob
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import java.time.YearMonth

/**
 * One job's salary that actually arrived for one month, set against what the app calculates for it.
 *
 * The comparison shown under "Received pay". The totals count received pay through [PayReconciler.actualSummary]
 * instead (it replaces the calculated money, never adds to it). The calculated figure here is always the NET pay
 * (after the job's deductions), whatever the display mode is: net is what lands in the bank. All amounts are
 * minor units of the job's currency.
 */
data class PayReconciliation(
    val job: Job,
    val month: YearMonth,
    /** Shifts, bonuses (also those for the whole month) and the fixed salary of the month, after deductions. */
    val calculatedNetMinor: Long,
    /** What arrived for this month, in the order entered; there can be several. */
    val payouts: List<Payout>,
) {
    val currency: String get() = job.currency

    val receivedMinor: Long get() = payouts.sumOf { it.amountMinor }

    /** Received minus calculated (negative: less arrived than calculated); null while nothing is entered. */
    val differenceMinor: Long? get() = if (payouts.isEmpty()) null else receivedMinor - calculatedNetMinor
}

/** One job's received pay over the months of a year that have any, against the calculated net of those months. */
data class JobYearReconciliation(val job: Job, val months: List<PayReconciliation>) {
    val currency: String get() = job.currency
    val receivedMinor: Long get() = months.sumOf { it.receivedMinor }
    val calculatedNetMinor: Long get() = months.sumOf { it.calculatedNetMinor }
    val differenceMinor: Long get() = receivedMinor - calculatedNetMinor
}

/** One job's money for one month: the received sum when anything arrived for it, else the calculated figure. */
data class MonthPay(val amountMinor: Long, val received: Boolean)

object PayReconciler {

    /**
     * The rule for one job-month: with at least one payout, its actual pay is the sum of the payouts (several are
     * added up; an advance alone counts as the month's pay) and replaces [calculatedMinor] entirely. Without
     * payouts it is [calculatedMinor]. The payouts must all belong to that job and month.
     */
    fun actualMonthPay(calculatedMinor: Long, payouts: List<Payout>): MonthPay =
        if (payouts.isEmpty()) MonthPay(calculatedMinor, received = false)
        else MonthPay(payouts.sumOf { it.amountMinor }, received = true)

    /**
     * Totals for a period in which received pay counts as the real money: [ShiftCalculator.summarize] of the
     * period, where every job-month of [months] (the period's whole months; none for a week) with payouts counts
     * their sum instead of its calculated shifts, bonuses (day and whole-month) and fixed salary. Hours, shift
     * counts, shift earnings and the average rate stay calculated. A job with payouts but no entries in the
     * period still gets a row. Payouts for months outside [months] are ignored. Same in Gross and Net mode.
     */
    fun actualSummary(
        shifts: List<ShiftWithJob>,
        bonuses: List<BonusWithJob>,
        jobs: List<Job>,
        months: List<YearMonth>,
        mode: EarningsMode,
        payouts: List<PayoutWithJob>,
    ): MonthSummary {
        val calculated = ShiftCalculator.summarize(shifts, bonuses, jobs, months, mode)
        val relevant = payouts.filter { it.payout.month in months }
        if (relevant.isEmpty()) return calculated

        val jobsById = (relevant.map { it.job } + jobs).associateBy { it.id }
        val swaps = relevant.groupBy { it.job.id to it.payout.month }.map { (key, rows) ->
            val (jobId, month) = key
            val job = jobsById.getValue(jobId)
            val part = ShiftCalculator.summarize(
                shifts = shifts.filter { it.job.id == jobId && YearMonth.from(it.shift.date) == month },
                bonuses = bonuses.filter { it.job.id == jobId && it.bonus.month == month },
                jobs = listOf(job),
                salaryMonths = listOf(month),
                mode = mode,
            ).perJob.firstOrNull()
            val pay = actualMonthPay(part?.calculatedEarningsMinor ?: 0, rows.map { it.payout })
            JobSummary(
                job = job, paidMinutes = 0, shiftEarningsGrosze = 0, bonusGrosze = 0, shiftCount = 0,
                receivedMinor = pay.amountMinor,
                replacedMinor = part?.calculatedEarningsMinor ?: 0,
                replacedBonusMinor = part?.bonusGrosze ?: 0,
                replacedSalaryMinor = part?.salaryMinor ?: 0,
                receivedMonths = 1,
            )
        }.groupBy { it.job.id }

        val byJob = calculated.perJob.associateBy { it.job.id }
        val unsorted = (byJob.keys + swaps.keys).map { jobId ->
            val base = byJob[jobId] ?: JobSummary(jobsById.getValue(jobId), 0, 0, 0, 0)
            val jobSwaps = swaps[jobId].orEmpty()
            base.copy(
                receivedMinor = jobSwaps.sumOf { it.receivedMinor },
                replacedMinor = jobSwaps.sumOf { it.replacedMinor },
                replacedBonusMinor = jobSwaps.sumOf { it.replacedBonusMinor },
                replacedSalaryMinor = jobSwaps.sumOf { it.replacedSalaryMinor },
                receivedMonths = jobSwaps.size,
            )
        }
        val swapped = unsorted.filter { it.receivedMonths > 0 }
        val summary = calculated.copy(
            received = Amounts.sum(swapped, { it.currency }, { it.receivedMinor }),
            replaced = Amounts.sum(swapped, { it.currency }, { it.replacedMinor }),
            replacedBonuses = Amounts.sum(swapped, { it.currency }, { it.replacedBonusMinor }),
            replacedSalaries = Amounts.sum(swapped, { it.currency }, { it.replacedSalaryMinor }),
        )
        val currencyOrder = summary.totalEarnings.sortedByValue().map { it.first }
        return summary.copy(
            perJob = unsorted.sortedWith(
                compareBy<JobSummary> { currencyOrder.indexOf(it.currency) }
                    .thenByDescending { it.earningsGrosze }
                    .thenBy { it.job.name },
            ),
        )
    }

    /**
     * Per job of [month]: the calculated net pay and the payouts for the month. A job appears when it has any
     * entry (shift, bonus, salary) in the month or any payout for it. Payouts are matched by the month they were
     * earned for; the day they arrived plays no part. Entries and payouts of other months are ignored.
     */
    fun forMonth(
        month: YearMonth,
        jobs: List<Job>,
        shifts: List<ShiftWithJob>,
        bonuses: List<BonusWithJob>,
        payouts: List<PayoutWithJob>,
    ): List<PayReconciliation> {
        val summary = ShiftCalculator.summarize(
            shifts = shifts.filter { YearMonth.from(it.shift.date) == month },
            bonuses = bonuses.filter { YearMonth.from(it.bonus.date) == month },
            jobs = jobs,
            salaryMonths = listOf(month),
            mode = EarningsMode.NET,
        )
        val calculated = summary.perJob.associate { it.job.id to it.earningsGrosze }
        val received = payouts.filter { it.payout.month == month }.groupBy { it.job.id }
        val knownJobs = (payouts.map { it.job } + jobs + summary.perJob.map { it.job }).associateBy { it.id }
        val calculatedOrder = summary.perJob.map { it.job.id }
        val payoutOnly = received.keys.filter { it !in calculated }.sortedBy { knownJobs.getValue(it).name.lowercase() }
        return (calculatedOrder + payoutOnly).map { id ->
            PayReconciliation(
                job = knownJobs.getValue(id),
                month = month,
                calculatedNetMinor = calculated[id] ?: 0,
                payouts = received[id].orEmpty().map { it.payout },
            )
        }
    }

    /**
     * Per job with payouts in [year]: the months that have payouts, each against its calculated net pay. Months
     * of the job without payouts are not part of it (nothing to compare there).
     */
    fun forYear(
        year: Int,
        jobs: List<Job>,
        shifts: List<ShiftWithJob>,
        bonuses: List<BonusWithJob>,
        payouts: List<PayoutWithJob>,
    ): List<JobYearReconciliation> {
        val ofYear = payouts.filter { it.payout.month.year == year }
        val rows = ofYear.map { it.payout.month }.distinct().sorted().flatMap { month ->
            forMonth(month, jobs, shifts, bonuses, ofYear).filter { it.payouts.isNotEmpty() }
        }
        return rows.groupBy { it.job.id }.values
            .map { JobYearReconciliation(it.first().job, it) }
            .sortedWith(compareBy({ it.currency }, { it.job.name.lowercase() }))
    }
}

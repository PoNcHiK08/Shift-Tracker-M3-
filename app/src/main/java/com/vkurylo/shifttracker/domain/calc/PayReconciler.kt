package com.vkurylo.shifttracker.domain.calc

import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Payout
import com.vkurylo.shifttracker.domain.model.PayoutWithJob
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import java.time.YearMonth

/**
 * One job's salary that actually arrived for one month, set against what the app calculates for it.
 *
 * Reconciliation only: nothing here feeds an earnings total (that would count the money twice). The calculated
 * figure is always the NET pay (after the job's deductions), whatever the display mode is: net is what lands in
 * the bank. All amounts are minor units of the job's currency.
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

object PayReconciler {

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

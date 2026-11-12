package com.vkurylo.shifttracker.domain.model

/**
 * Totals for any period (week, month, year). Earnings = pay for shifts + bonuses, kept per currency
 * ([Amounts]): money in different currencies is never added up. Hours and counts are plain totals.
 *
 * Received pay: when a job has payouts for a whole month of the period, that job-month's calculated money
 * ([replaced], of which [replacedBonuses] and [replacedSalaries]) is swapped for what arrived ([received]); see
 * `PayReconciler.actualSummary`. Hours, counts, [shiftEarnings] and the average rate stay calculated.
 */
data class MonthSummary(
    val totalPaidMinutes: Long,
    val shiftEarnings: Amounts,
    val bonuses: Amounts,
    val shiftCount: Int,
    val bonusCount: Int,
    val perJob: List<JobSummary>,
    /** Fixed monthly salaries of the whole months in the period (none for a week). */
    val salaries: Amounts = Amounts.EMPTY,
    /** Shifts of jobs paid a fixed salary at the time: they count for hours, not for money. */
    val fixedPayShiftCount: Int = 0,
    /** Payouts of the job-months that count what arrived instead of the calculated pay. */
    val received: Amounts = Amounts.EMPTY,
    /** The calculated money (shifts, bonuses, salaries) of those job-months, which [received] replaces. */
    val replaced: Amounts = Amounts.EMPTY,
    /** The bonuses part of [replaced]. */
    val replacedBonuses: Amounts = Amounts.EMPTY,
    /** The fixed salary part of [replaced]. */
    val replacedSalaries: Amounts = Amounts.EMPTY,
) {
    /** What the app calculates for the period, before any received pay replaces part of it. */
    val calculatedEarnings: Amounts get() = shiftEarnings + bonuses + salaries

    /** The period's money: calculated, except job-months with received pay, which count what arrived. */
    val totalEarnings: Amounts get() = calculatedEarnings - replaced + received

    /** Bonuses still counted as calculated (those of job-months with received pay are inside [received]). */
    val countedBonuses: Amounts get() = bonuses - replacedBonuses

    /** Fixed salaries still counted as calculated. */
    val countedSalaries: Amounts get() = salaries - replacedSalaries

    /** Whether [totalEarnings] holds received pay: none of it, part of it, or all of it. */
    val receivedPart: ReceivedPart
        get() = when {
            received.isEmpty -> ReceivedPart.NONE
            (calculatedEarnings - replaced).byCurrency.values.all { it == 0L } -> ReceivedPart.ALL
            else -> ReceivedPart.PART
        }

    /** Currencies with any entry in the period, largest total first. */
    val currencies: List<String> get() = totalEarnings.sortedByValue().map { it.first }

    /**
     * Average hourly pay in [currency] for hourly-paid time at jobs paid in it; bonuses and salaries
     * are excluded so they don't inflate the rate.
     */
    fun averageRate(currency: String): Long {
        val minutes = perJob.filter { it.currency == currency }.sumOf { it.hourlyPaidMinutes }
        return if (minutes > 0) shiftEarnings[currency] * 60 / minutes else 0
    }

    val isEmpty: Boolean get() = shiftCount == 0 && bonusCount == 0 && salaries.isEmpty && received.isEmpty

    companion object {
        val EMPTY = MonthSummary(0, Amounts.EMPTY, Amounts.EMPTY, 0, 0, emptyList())
    }
}

/** One job's part of a period; a job has a single currency, so these are plain minor units. */
data class JobSummary(
    val job: Job,
    val paidMinutes: Long,
    val shiftEarningsGrosze: Long,
    val bonusGrosze: Long,
    val shiftCount: Int,
    /** Monthly salary counted in the period. */
    val salaryMinor: Long = 0,
    /** Worked time paid by the hour (excludes shifts covered by a monthly salary). */
    val hourlyPaidMinutes: Long = paidMinutes,
    /** Payouts of this job's months with received pay (they replace [replacedMinor]). */
    val receivedMinor: Long = 0,
    /** Calculated money of the months with received pay. */
    val replacedMinor: Long = 0,
    val replacedBonusMinor: Long = 0,
    val replacedSalaryMinor: Long = 0,
    /** Months of the period for which this job counts received pay. */
    val receivedMonths: Int = 0,
) {
    val currency: String get() = job.currency
    val calculatedEarningsMinor: Long get() = shiftEarningsGrosze + bonusGrosze + salaryMinor

    /** The job's money in the period: calculated, with months that have received pay counting what arrived. */
    val earningsGrosze: Long get() = calculatedEarningsMinor - replacedMinor + receivedMinor

    /** Bonuses / salary still counted as calculated. */
    val countedBonusMinor: Long get() = bonusGrosze - replacedBonusMinor
    val countedSalaryMinor: Long get() = salaryMinor - replacedSalaryMinor

    val receivedPart: ReceivedPart
        get() = when {
            receivedMonths == 0 -> ReceivedPart.NONE
            calculatedEarningsMinor - replacedMinor == 0L -> ReceivedPart.ALL
            else -> ReceivedPart.PART
        }
}

/** How much of a figure is received pay rather than calculated. */
enum class ReceivedPart {
    /** Calculated only. */
    NONE,

    /** Partly received, partly still calculated. */
    PART,

    /** Received only. */
    ALL,
}

package com.vkurylo.shifttracker.domain.model

/**
 * Totals for any period (week, month, year). Earnings = pay for shifts + bonuses, kept per currency
 * ([Amounts]): money in different currencies is never added up. Hours and counts are plain totals.
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
) {
    val totalEarnings: Amounts get() = shiftEarnings + bonuses + salaries

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

    val isEmpty: Boolean get() = shiftCount == 0 && bonusCount == 0 && salaries.isEmpty

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
) {
    val currency: String get() = job.currency
    val earningsGrosze: Long get() = shiftEarningsGrosze + bonusGrosze + salaryMinor
}

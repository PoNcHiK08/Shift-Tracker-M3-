package com.vkurylo.shifttracker.domain.model

/** Totals for any period (week, month, year). Earnings = pay for shifts + bonuses. */
data class MonthSummary(
    val totalPaidMinutes: Long,
    val shiftEarningsGrosze: Long,
    val bonusGrosze: Long,
    val shiftCount: Int,
    val bonusCount: Int,
    val perJob: List<JobSummary>,
) {
    val totalEarningsGrosze: Long get() = shiftEarningsGrosze + bonusGrosze

    /** Average hourly pay for worked time; bonuses are excluded so they don't inflate the rate. */
    val averageRateGrosze: Long get() = if (totalPaidMinutes > 0) shiftEarningsGrosze * 60 / totalPaidMinutes else 0

    val isEmpty: Boolean get() = shiftCount == 0 && bonusCount == 0

    companion object {
        val EMPTY = MonthSummary(0, 0, 0, 0, 0, emptyList())
    }
}

data class JobSummary(
    val job: Job,
    val paidMinutes: Long,
    val shiftEarningsGrosze: Long,
    val bonusGrosze: Long,
    val shiftCount: Int,
) {
    val earningsGrosze: Long get() = shiftEarningsGrosze + bonusGrosze
}

package com.vkurylo.shifttracker.domain.model

data class MonthSummary(
    val totalPaidMinutes: Long,
    val totalEarningsGrosze: Long,
    val shiftCount: Int,
    val perJob: List<JobSummary>,
) {
    companion object {
        val EMPTY = MonthSummary(0, 0, 0, emptyList())
    }
}

data class JobSummary(
    val job: Job,
    val paidMinutes: Long,
    val earningsGrosze: Long,
    val shiftCount: Int,
)

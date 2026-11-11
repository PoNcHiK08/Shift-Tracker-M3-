package com.vkurylo.shifttracker.domain.model

import java.time.LocalDate
import java.time.YearMonth

/**
 * The salary that actually arrived for a job, for the month it was EARNED for (pay is often received the
 * next month). Amounts are minor units of the job's currency.
 *
 * A job's payouts for a month are its real pay for that month: the totals count their sum instead of (never on
 * top of) the calculated pay of that job-month (see `PayReconciler.actualSummary`), and the Received pay section
 * compares them with the calculated (net) pay. A job can have several payouts for the same month (they are summed).
 */
data class Payout(
    val id: Long = 0,
    val jobId: Long,
    /** The month the pay was earned for. */
    val month: YearMonth,
    val amountMinor: Long,
    val note: String? = null,
    /** The day it arrived, when the user entered it. */
    val receivedOn: LocalDate? = null,
) {
    companion object {
        /** The years the cloud rules accept for a payout's month (firestore.rules, isMonthKey). */
        const val MIN_YEAR = 1000
        const val MAX_YEAR = 9999

        /** yyyymm, like the pay settings history (2026-11 is 202611). */
        fun keyOf(month: YearMonth): Int = month.year * 100 + month.monthValue

        /** The month of a yyyymm key, or null when it isn't a real month in [MIN_YEAR]..[MAX_YEAR]. */
        fun monthFromKey(key: Int): YearMonth? {
            val year = key / 100
            val month = key % 100
            return if (key > 0 && year in MIN_YEAR..MAX_YEAR && month in 1..12) YearMonth.of(year, month) else null
        }
    }
}

data class PayoutWithJob(
    val payout: Payout,
    val job: Job,
)

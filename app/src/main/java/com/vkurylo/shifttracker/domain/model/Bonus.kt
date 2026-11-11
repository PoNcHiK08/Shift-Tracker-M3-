package com.vkurylo.shifttracker.domain.model

import java.time.LocalDate
import java.time.YearMonth

/**
 * A one-off extra payment (bonus, tips, premium…) for a job on a given day, or, with [wholeMonth], for a
 * whole month: then [date] is only the month it belongs to (stored as the 1st) and the bonus has no day.
 * A whole-month bonus counts in that month's earnings and payout, but is not shown on a day and is not
 * part of a week.
 */
data class Bonus(
    val id: Long = 0,
    val jobId: Long,
    val date: LocalDate,
    val amountGrosze: Long,
    val note: String? = null,
    val wholeMonth: Boolean = false,
) {
    /** The month the bonus counts in. */
    val month: YearMonth get() = YearMonth.from(date)
}

data class BonusWithJob(
    val bonus: Bonus,
    val job: Job,
)

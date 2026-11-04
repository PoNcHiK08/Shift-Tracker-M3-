package com.vkurylo.shifttracker.domain.model

import java.time.LocalDate
import java.time.YearMonth

/** How a job pays. New types (e.g. a fixed amount per shift) can be added here. */
enum class PayType {
    /** Shifts earn their saved hourly rate(s), as the app always did. */
    HOURLY,

    /** A fixed monthly salary; shifts only log hours. */
    FIXED_MONTHLY,
}

/**
 * A job's pay settings from month [from] on (null: from the very beginning), until the next entry of
 * its history. Changing pay settings adds or replaces the entry of one month, so earlier months keep
 * what they had: past earnings are never rewritten (hourly rates are snapshotted on each shift).
 */
data class PaySettings(
    val from: YearMonth? = null,
    val payType: PayType = PayType.HOURLY,
    /** The salary of a [PayType.FIXED_MONTHLY] month, in minor units of the job's currency. */
    val monthlyAmountMinor: Long = 0,
) {
    companion object {
        /** A job with no history: hourly, as every job before pay types. */
        val DEFAULT = PaySettings()
    }
}

/** The settings in force in [month]: the latest history entry starting on or before it. */
fun Job.payFor(month: YearMonth): PaySettings =
    payHistory.lastOrNull { it.from == null || !it.from.isAfter(month) } ?: PaySettings.DEFAULT

fun Job.payFor(date: LocalDate): PaySettings = payFor(YearMonth.from(date))

/** Whether shifts of this job on [date] earn their hourly rate (false: covered by a monthly salary). */
fun Job.paysHourly(date: LocalDate): Boolean = payFor(date).payType == PayType.HOURLY

/**
 * The months a fixed salary is counted in: every month from the job's first to its last month with any
 * shift or bonus (gaps such as a month of leave count; nothing after the last entry, nothing before
 * the first).
 */
fun Job.isActiveIn(month: YearMonth): Boolean {
    val first = firstEntry ?: return false
    val last = lastEntry ?: return false
    return !month.isBefore(YearMonth.from(first)) && !month.isAfter(YearMonth.from(last))
}

package com.vkurylo.shifttracker.domain.model

import java.time.LocalTime

/**
 * A place of work. Money is kept as Long minor units of the job's [currency] (grosze for PLN, cents
 * for EUR; the `*Grosze` names predate other currencies) to avoid floating point errors. Its shifts
 * and bonuses are in the same currency.
 *
 * [hourlyRateGrosze] is the base rate. [secondRate] optionally pays a different rate inside a
 * daily time window, e.g. 37 zł/h from 23:00 to 06:00 while the base rate is 33 zł/h.
 */
data class Job(
    val id: Long = 0,
    val name: String,
    val hourlyRateGrosze: Long,
    /** ARGB color from [JobPalette]. */
    val color: Int,
    val secondRate: TimedRate? = null,
    /** ISO 4217 code, see [Currencies]. Fixed once the job has shifts or bonuses. */
    val currency: String = Currencies.DEFAULT,
    /** Pay type and salary by month, oldest first; empty means hourly throughout. See [payFor]. */
    val payHistory: List<PaySettings> = emptyList(),
    /** Dates of the job's first and last shift or bonus (null without any): bounds a monthly salary. */
    val firstEntry: java.time.LocalDate? = null,
    val lastEntry: java.time.LocalDate? = null,
)

/**
 * A rate that applies every day between [from] and [to]. If [to] is not after [from]
 * the window crosses midnight (e.g. 23:00–06:00).
 */
data class TimedRate(
    val rateGrosze: Long,
    val from: LocalTime,
    val to: LocalTime,
) {
    companion object {
        val DEFAULT_NIGHT_FROM: LocalTime = LocalTime.of(23, 0)
        val DEFAULT_NIGHT_TO: LocalTime = LocalTime.of(6, 0)
    }
}

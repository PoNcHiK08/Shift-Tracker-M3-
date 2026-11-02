package com.vkurylo.shifttracker.domain.model

import java.time.LocalTime

/**
 * A place of work. Money is kept in grosze (1/100 PLN) to avoid floating point errors.
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

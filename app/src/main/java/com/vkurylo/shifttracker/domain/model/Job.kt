package com.vkurylo.shifttracker.domain.model

/**
 * A place of work. Money is kept in grosze (1/100 PLN) to avoid floating point errors.
 */
data class Job(
    val id: Long = 0,
    val name: String,
    val hourlyRateGrosze: Long,
    /** ARGB color from [JobPalette]. */
    val color: Int,
)

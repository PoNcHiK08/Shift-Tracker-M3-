package com.vkurylo.shifttracker.domain.model

import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import java.time.LocalDate
import java.time.LocalTime

/**
 * One worked shift. [date] is the day the shift starts; if [end] is not after [start]
 * the shift ends on the next day (overnight shift).
 *
 * [hourlyRateGrosze] is a snapshot of the job's rate when the shift was saved, so later
 * rate changes don't rewrite past earnings.
 */
data class Shift(
    val id: Long = 0,
    val jobId: Long,
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    val unpaidBreakMinutes: Int = 0,
    val hourlyRateGrosze: Long,
    val note: String? = null,
    /** UID of the calendar event this shift was imported from, if any. */
    val externalUid: String? = null,
) {
    val isOvernight: Boolean get() = ShiftCalculator.isOvernight(start, end)
    val paidMinutes: Long get() = ShiftCalculator.paidMinutes(start, end, unpaidBreakMinutes)
    val earningsGrosze: Long get() = ShiftCalculator.earningsGrosze(paidMinutes, hourlyRateGrosze)
}

/** A shift together with the job it belongs to, ready for display. */
data class ShiftWithJob(
    val shift: Shift,
    val job: Job,
)

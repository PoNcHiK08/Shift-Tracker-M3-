package com.vkurylo.shifttracker.domain.model

import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import java.time.LocalDate
import java.time.LocalTime

/**
 * One worked shift. [date] is the day the shift starts; if [end] is not after [start]
 * the shift ends on the next day (overnight shift).
 *
 * [hourlyRateGrosze] and [secondRate] are a snapshot of the job's rates when the shift was saved,
 * so later rate changes don't rewrite past earnings.
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
    val secondRate: TimedRate? = null,
) {
    val isOvernight: Boolean get() = ShiftCalculator.isOvernight(start, end)
    val pay: ShiftPay get() = ShiftCalculator.pay(start, end, unpaidBreakMinutes, hourlyRateGrosze, secondRate)
    val paidMinutes: Long get() = ShiftCalculator.paidMinutes(start, end, unpaidBreakMinutes)
    val earningsGrosze: Long get() = pay.earningsGrosze
}

/** How a shift's time and money split between the base rate and the timed second rate. */
data class ShiftPay(
    val durationMinutes: Long,
    val paidMinutes: Long,
    /** Worked minutes (before the break) inside the second-rate window. */
    val secondRateMinutes: Long,
    val earningsGrosze: Long,
) {
    val baseRateMinutes: Long get() = durationMinutes - secondRateMinutes
}

/** A shift together with the job it belongs to, ready for display. */
data class ShiftWithJob(
    val shift: Shift,
    val job: Job,
)

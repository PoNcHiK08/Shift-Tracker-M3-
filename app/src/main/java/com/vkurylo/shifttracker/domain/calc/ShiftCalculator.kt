package com.vkurylo.shifttracker.domain.calc

import com.vkurylo.shifttracker.domain.model.JobSummary
import com.vkurylo.shifttracker.domain.model.MonthSummary
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import java.time.LocalTime

object ShiftCalculator {

    private const val MINUTES_PER_DAY = 24 * 60

    /** A shift whose end is not after its start finishes on the following day. */
    fun isOvernight(start: LocalTime, end: LocalTime): Boolean = !end.isAfter(start)

    /** Total minutes between start and end, crossing midnight when needed. */
    fun durationMinutes(start: LocalTime, end: LocalTime): Long {
        val startMin = start.hour * 60 + start.minute
        val endMin = end.hour * 60 + end.minute
        val diff = endMin - startMin
        return (if (diff <= 0) diff + MINUTES_PER_DAY else diff).toLong()
    }

    /** Paid minutes = duration minus the unpaid break, never negative. */
    fun paidMinutes(start: LocalTime, end: LocalTime, unpaidBreakMinutes: Int): Long =
        (durationMinutes(start, end) - unpaidBreakMinutes.coerceAtLeast(0)).coerceAtLeast(0)

    /** Earnings in grosze, rounded half-up to the nearest grosz. */
    fun earningsGrosze(paidMinutes: Long, hourlyRateGrosze: Long): Long {
        val numerator = paidMinutes * hourlyRateGrosze
        return (numerator + 30) / 60
    }

    /** Totals plus a per-job breakdown, sorted by earnings (highest first). */
    fun summarize(shifts: List<ShiftWithJob>): MonthSummary {
        if (shifts.isEmpty()) return MonthSummary.EMPTY
        val perJob = shifts
            .groupBy { it.job.id }
            .map { (_, items) ->
                JobSummary(
                    job = items.first().job,
                    paidMinutes = items.sumOf { it.shift.paidMinutes },
                    earningsGrosze = items.sumOf { it.shift.earningsGrosze },
                    shiftCount = items.size,
                )
            }
            .sortedWith(compareByDescending<JobSummary> { it.earningsGrosze }.thenBy { it.job.name })
        return MonthSummary(
            totalPaidMinutes = perJob.sumOf { it.paidMinutes },
            totalEarningsGrosze = perJob.sumOf { it.earningsGrosze },
            shiftCount = shifts.size,
            perJob = perJob,
        )
    }
}

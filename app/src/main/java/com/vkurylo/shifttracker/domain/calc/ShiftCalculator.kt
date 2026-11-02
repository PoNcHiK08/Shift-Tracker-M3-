package com.vkurylo.shifttracker.domain.calc

import com.vkurylo.shifttracker.domain.model.JobSummary
import com.vkurylo.shifttracker.domain.model.MonthSummary
import com.vkurylo.shifttracker.domain.model.ShiftPay
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.domain.model.TimedRate
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

    /** Earnings in grosze at a single rate, rounded half-up to the nearest grosz. */
    fun earningsGrosze(paidMinutes: Long, hourlyRateGrosze: Long): Long {
        val numerator = paidMinutes * hourlyRateGrosze
        return (numerator + 30) / 60
    }

    /**
     * Minutes of the shift (start..end, crossing midnight if needed) that fall inside the
     * daily window [window].from..[window].to (which may itself cross midnight).
     */
    fun minutesInWindow(start: LocalTime, end: LocalTime, window: TimedRate): Long {
        val shiftStart = start.toMinuteOfDay()
        val shiftEnd = shiftStart + durationMinutes(start, end)
        val from = window.from.toMinuteOfDay()
        val to = window.to.toMinuteOfDay()
        if (from == to) return 0
        val windowLength = if (to > from) to - from else to + MINUTES_PER_DAY - from
        // A shift is at most 24 h long, so it can touch windows starting yesterday, today or tomorrow.
        var total = 0L
        for (day in -1..1) {
            val windowStart = day * MINUTES_PER_DAY + from
            val windowEnd = windowStart + windowLength
            val overlap = minOf(shiftEnd, windowEnd.toLong()) - maxOf(shiftStart.toLong(), windowStart.toLong())
            if (overlap > 0) total += overlap
        }
        return total
    }

    /**
     * Full pay calculation. Minutes inside the second-rate window are paid at that rate, the rest
     * at the base rate. The unpaid break is taken from both parts proportionally, since the app
     * doesn't know when exactly the break happened.
     */
    fun pay(
        start: LocalTime,
        end: LocalTime,
        unpaidBreakMinutes: Int,
        baseRateGrosze: Long,
        secondRate: TimedRate?,
    ): ShiftPay {
        val duration = durationMinutes(start, end)
        val paid = paidMinutes(start, end, unpaidBreakMinutes)
        val secondMinutes = secondRate?.let { minutesInWindow(start, end, it) } ?: 0L
        val secondRateGrosze = secondRate?.rateGrosze ?: baseRateGrosze
        // earnings = (base part + second part) * paid / duration, in one exact integer division.
        val weighted = (duration - secondMinutes) * baseRateGrosze + secondMinutes * secondRateGrosze
        val numerator = weighted * paid
        val denominator = duration * 60
        val earnings = (numerator + denominator / 2) / denominator
        return ShiftPay(duration, paid, secondMinutes, earnings)
    }

    private fun LocalTime.toMinuteOfDay(): Int = hour * 60 + minute

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

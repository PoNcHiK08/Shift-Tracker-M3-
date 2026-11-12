package com.vkurylo.shifttracker.domain.calc

import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ShiftRates
import com.vkurylo.shifttracker.domain.model.currentRates
import com.vkurylo.shifttracker.domain.model.paysHourly
import com.vkurylo.shifttracker.domain.model.rates
import com.vkurylo.shifttracker.domain.model.withRates
import java.time.LocalDate

/** Which shifts of the job "Apply to all shifts" covers. */
enum class RateScope {
    /** Every shift of the job. */
    ALL,

    /** Shifts on the given date and later. */
    FROM_DATE,
}

/**
 * Putting a job's current rates on many of its shifts at once: what would change, worked out before anything is
 * written, so the count and the money shown are exactly what is applied.
 */
data class RatePlan(val job: Job, val changes: List<Change>) {
    /** One shift: as stored, and with the job's current rates. */
    data class Change(val old: Shift, val new: Shift) {
        val differenceMinor: Long get() = new.earningsGrosze - old.earningsGrosze
    }

    val count: Int get() = changes.size

    /** New minus old pay of the shifts that change, before deductions, in the job's currency. */
    val differenceMinor: Long get() = changes.sumOf { it.differenceMinor }

    /** Shift id → the rates to write. */
    val newRates: Map<Long, ShiftRates> get() = changes.associate { it.new.id to it.new.rates }

    /** Shift id → the rates they had: what Undo writes back. */
    val oldRates: Map<Long, ShiftRates> get() = changes.associate { it.old.id to it.old.rates }
}

object CurrentRateChange {

    /**
     * The shifts of [job] among [shifts] that would get its current rates: those whose rates differ from them
     * (shifts already on them are left alone and not counted), paid by the hour on their date (a salaried month
     * has no per-shift pay, as for the single-shift "Use current rate"), from [fromDate] on for
     * [RateScope.FROM_DATE], and never [excludeShiftId] (the shift open in the editor, which takes the rates on
     * screen and keeps them when it is saved).
     */
    fun plan(job: Job, shifts: List<Shift>, scope: RateScope, fromDate: LocalDate, excludeShiftId: Long?): RatePlan {
        val current = job.currentRates
        val changes = shifts
            .asSequence()
            .filter { it.jobId == job.id && it.id != excludeShiftId }
            .filter { scope == RateScope.ALL || !it.date.isBefore(fromDate) }
            .filter { job.paysHourly(it.date) && it.rates != current }
            .map { RatePlan.Change(it, it.withRates(current)) }
            .toList()
        return RatePlan(job, changes)
    }
}

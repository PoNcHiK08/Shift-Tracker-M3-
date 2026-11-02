package com.vkurylo.shifttracker.domain.ics

import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift

/** One row of the import preview. */
data class ImportCandidate(
    val event: IcsEvent,
    /** True when a shift with the same UID, or the same date and times, already exists. */
    val isDuplicate: Boolean,
)

object ImportPlanner {

    fun plan(events: List<IcsEvent>, existing: List<Shift>): List<ImportCandidate> {
        val existingUids = existing.mapNotNullTo(HashSet()) { it.externalUid }
        val existingSlots = existing.mapTo(HashSet()) { Triple(it.date, it.start, it.end) }
        val seenInFile = HashSet<Triple<*, *, *>>()
        return events.map { event ->
            val slot = Triple(event.start.toLocalDate(), event.start.toLocalTime().withSecondAndNano(), event.end.toLocalTime().withSecondAndNano())
            val duplicate = (event.uid != null && event.uid in existingUids) ||
                slot in existingSlots ||
                !seenInFile.add(slot)
            ImportCandidate(event, duplicate)
        }
    }

    fun toShift(event: IcsEvent, job: Job, unpaidBreakMinutes: Int = 0): Shift = Shift(
        jobId = job.id,
        date = event.start.toLocalDate(),
        start = event.start.toLocalTime().withSecondAndNano(),
        end = event.end.toLocalTime().withSecondAndNano(),
        unpaidBreakMinutes = unpaidBreakMinutes,
        hourlyRateGrosze = job.hourlyRateGrosze,
        note = event.summary,
        externalUid = event.uid,
        secondRate = job.secondRate,
    )

    private fun java.time.LocalTime.withSecondAndNano() = withSecond(0).withNano(0)
}

package com.vkurylo.shifttracker.data.local

/**
 * What happens when a job is deleted (a tombstone) but some of its shifts, bonuses or payouts are not.
 *
 * Deleting a job deletes its entries in the same transaction, so on one phone this state doesn't last.
 * It comes up when changes meet: phone A deletes a job while phone B, offline, adds a shift to it; or an
 * Undo brings back a shift whose job was deleted in the meantime. This is the only place that decides
 * it, for local writes and for changes that arrive from the cloud alike:
 *
 * - **A live entry changed after the job was deleted** (its `updated_at` is later than the job's
 *   tombstone) **brings the job back.** A change made after the deletion shows the job is still in use,
 *   and nothing that was just entered disappears silently. The job comes back with only those newer
 *   entries; entries deleted together with it stay deleted. The revived job's `updated_at` is the newest
 *   of those entries' (later than its tombstone).
 * - **Live entries that are not newer than the deletion are deleted with the job**, whether or not a
 *   newer entry brings the job back: they existed before the deletion and would have gone with the job
 *   had the deleting phone known them. Their tombstones get `max(entry's updated_at + 1, job's deletion
 *   time)`.
 *
 * Every value depends only on the records, so phones applying the rule independently reach the same result.
 */
object ParentChildRule {

    sealed interface Resolution {
        /** The job is live, or has no live entries: nothing to do. */
        data object None : Resolution

        /**
         * Undelete the job with this `updatedAt`; its live entries not newer than [jobDeletedAt] are deleted,
         * each at `max(its updated_at + 1, jobDeletedAt)`.
         */
        data class ReviveJob(val updatedAt: Long, val jobDeletedAt: Long) : Resolution

        /** The job stays deleted; its live entries are deleted, each at `max(its updated_at + 1, jobDeletedAt)`. */
        data class DeleteEntries(val jobDeletedAt: Long) : Resolution
    }

    fun resolve(jobDeleted: Boolean, jobUpdatedAt: Long, liveEntryUpdatedAts: List<Long>): Resolution {
        if (!jobDeleted || liveEntryUpdatedAts.isEmpty()) return Resolution.None
        val newest = liveEntryUpdatedAts.max()
        return if (newest > jobUpdatedAt) Resolution.ReviveJob(newest, jobUpdatedAt) else Resolution.DeleteEntries(jobUpdatedAt)
    }

    /** Applies [resolve] to every deleted job that has live entries. Call it inside the write's transaction. */
    suspend fun enforce(dao: JobDao) {
        for (jobId in dao.deletedJobsWithLiveEntries()) {
            val job = dao.getAny(jobId) ?: continue
            when (val resolution = resolve(job.deleted, job.updatedAt, dao.liveEntryUpdatedAts(jobId))) {
                Resolution.None -> Unit
                is Resolution.ReviveJob -> {
                    dao.deleteLiveShiftsNotNewerThan(jobId, resolution.jobDeletedAt)
                    dao.deleteLiveBonusesNotNewerThan(jobId, resolution.jobDeletedAt)
                    dao.deleteLivePayoutsNotNewerThan(jobId, resolution.jobDeletedAt)
                    dao.markRevived(jobId, resolution.updatedAt)
                }
                is Resolution.DeleteEntries -> {
                    dao.deleteLiveShiftsNotNewerThan(jobId, resolution.jobDeletedAt)
                    dao.deleteLiveBonusesNotNewerThan(jobId, resolution.jobDeletedAt)
                    dao.deleteLivePayoutsNotNewerThan(jobId, resolution.jobDeletedAt)
                }
            }
        }
    }
}

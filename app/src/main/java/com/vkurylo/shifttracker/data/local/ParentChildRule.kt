package com.vkurylo.shifttracker.data.local

/**
 * What happens when a job is deleted (a tombstone) but some of its shifts or bonuses are not.
 *
 * Deleting a job deletes its entries in the same transaction, so on one phone this state doesn't last.
 * It comes up when changes meet: phone A deletes a job while phone B, offline, adds a shift to it; or an
 * Undo brings back a shift whose job was deleted in the meantime. This is the only place that decides
 * it, for local writes and for changes that arrive from the cloud alike:
 *
 * - **A live entry changed after the job was deleted** (its `updated_at` is later than the job's
 *   tombstone) **brings the job back.** A change made after the deletion shows the job is still in use,
 *   and nothing that was just entered disappears silently. Only the job comes back, with the entries that
 *   are live; entries that were deleted together with the job stay deleted. The revived job's
 *   `updated_at` is the newest of those entries' (later than its tombstone), the same value on every
 *   phone that applies the rule, so they agree on the result.
 * - **Live entries that are not newer than the deletion are deleted with the job**, as if they had
 *   existed when it was deleted. Their tombstones get `max(entry's updated_at + 1, job's deletion time)`,
 *   again the same on every phone.
 */
object ParentChildRule {

    sealed interface Resolution {
        /** The job is live, or has no live entries: nothing to do. */
        data object None : Resolution

        /** Undelete the job with this `updated_at`. */
        data class ReviveJob(val updatedAt: Long) : Resolution

        /** Delete the job's live entries, each at `max(its updated_at + 1, jobDeletedAt)`. */
        data class DeleteEntries(val jobDeletedAt: Long) : Resolution
    }

    fun resolve(jobDeleted: Boolean, jobUpdatedAt: Long, liveEntryUpdatedAts: List<Long>): Resolution {
        if (!jobDeleted || liveEntryUpdatedAts.isEmpty()) return Resolution.None
        val newest = liveEntryUpdatedAts.max()
        return if (newest > jobUpdatedAt) Resolution.ReviveJob(newest) else Resolution.DeleteEntries(jobUpdatedAt)
    }

    /** Applies [resolve] to every deleted job that has live entries. Call it inside the write's transaction. */
    suspend fun enforce(dao: JobDao) {
        for (jobId in dao.deletedJobsWithLiveEntries()) {
            val job = dao.getAny(jobId) ?: continue
            when (val resolution = resolve(job.deleted, job.updatedAt, dao.liveEntryUpdatedAts(jobId))) {
                Resolution.None -> Unit
                is Resolution.ReviveJob -> dao.markRevived(jobId, resolution.updatedAt)
                is Resolution.DeleteEntries -> {
                    dao.deleteLiveShiftsOf(jobId, resolution.jobDeletedAt)
                    dao.deleteLiveBonusesOf(jobId, resolution.jobDeletedAt)
                }
            }
        }
    }
}

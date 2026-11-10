package com.vkurylo.shifttracker.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query

/** A shift or bonus row with its job's sync id, which the cloud uses to refer to the job. */
data class ShiftWithJobSyncId(
    @Embedded val shift: ShiftEntity,
    @ColumnInfo(name = "job_sync_id") val jobSyncId: String,
)

data class BonusWithJobSyncId(
    @Embedded val bonus: BonusEntity,
    @ColumnInfo(name = "job_sync_id") val jobSyncId: String,
)

/** Changes on this phone not uploaded yet: how many, and the latest change time among them. */
data class DirtySummary(
    @ColumnInfo(name = "count") val count: Int,
    @ColumnInfo(name = "newest") val newest: Long?,
)

/** Reads and writes of the sync layer. Unlike the app's DAOs, these see tombstones too. */
@Dao
interface SyncDao {
    @Query("SELECT * FROM jobs WHERE dirty = 1 ORDER BY id")
    suspend fun dirtyJobs(): List<JobEntity>

    /**
     * Changes not uploaded yet, as they change: a new edit, an edit of a row still waiting (its newest time moves)
     * or an upload (fewer rows). Applying the cloud's versions doesn't change it: they are stored as uploaded.
     */
    @Query(
        "SELECT COUNT(*) AS count, MAX(updated_at) AS newest FROM (" +
            "SELECT updated_at FROM jobs WHERE dirty = 1 UNION ALL " +
            "SELECT updated_at FROM shifts WHERE dirty = 1 UNION ALL " +
            "SELECT updated_at FROM bonuses WHERE dirty = 1)",
    )
    fun observeDirty(): kotlinx.coroutines.flow.Flow<DirtySummary>

    @Query(
        "SELECT COUNT(*) AS count, MAX(updated_at) AS newest FROM (" +
            "SELECT updated_at FROM jobs WHERE dirty = 1 UNION ALL " +
            "SELECT updated_at FROM shifts WHERE dirty = 1 UNION ALL " +
            "SELECT updated_at FROM bonuses WHERE dirty = 1)",
    )
    suspend fun dirty(): DirtySummary

    @Query("SELECT s.*, j.sync_id AS job_sync_id FROM shifts s INNER JOIN jobs j ON j.id = s.job_id WHERE s.dirty = 1 ORDER BY s.id")
    suspend fun dirtyShifts(): List<ShiftWithJobSyncId>

    @Query("SELECT b.*, j.sync_id AS job_sync_id FROM bonuses b INNER JOIN jobs j ON j.id = b.job_id WHERE b.dirty = 1 ORDER BY b.id")
    suspend fun dirtyBonuses(): List<BonusWithJobSyncId>

    /*
     * "Uploaded": only if the row is still the version that was uploaded. A change made while the upload
     * was on its way has a later updated_at, so the row stays dirty and goes up next time.
     */
    @Query("UPDATE jobs SET dirty = 0 WHERE sync_id = :syncId AND updated_at = :updatedAt")
    suspend fun markJobUploaded(syncId: String, updatedAt: Long): Int

    @Query("UPDATE shifts SET dirty = 0 WHERE sync_id = :syncId AND updated_at = :updatedAt")
    suspend fun markShiftUploaded(syncId: String, updatedAt: Long): Int

    @Query("UPDATE bonuses SET dirty = 0 WHERE sync_id = :syncId AND updated_at = :updatedAt")
    suspend fun markBonusUploaded(syncId: String, updatedAt: Long): Int

    /* To be uploaded again (this phone's version is newer than the cloud's but wasn't marked). */
    @Query("UPDATE jobs SET dirty = 1 WHERE sync_id = :syncId")
    suspend fun markJobDirty(syncId: String)

    @Query("UPDATE shifts SET dirty = 1 WHERE sync_id = :syncId")
    suspend fun markShiftDirty(syncId: String)

    @Query("UPDATE bonuses SET dirty = 1 WHERE sync_id = :syncId")
    suspend fun markBonusDirty(syncId: String)

    /** Everything is to be uploaded (to an account that doesn't have this phone's data yet). */
    @Query("UPDATE jobs SET dirty = 1")
    suspend fun markAllJobsDirty()

    @Query("UPDATE shifts SET dirty = 1")
    suspend fun markAllShiftsDirty()

    @Query("UPDATE bonuses SET dirty = 1")
    suspend fun markAllBonusesDirty()

    @Query("SELECT shift.*, job.sync_id AS job_sync_id FROM shifts shift INNER JOIN jobs job ON job.id = shift.job_id WHERE shift.deleted = 0 AND job.deleted = 0")
    suspend fun liveShifts(): List<ShiftWithJobSyncId>

    @Query("SELECT bonus.*, job.sync_id AS job_sync_id FROM bonuses bonus INNER JOIN jobs job ON job.id = bonus.job_id WHERE bonus.deleted = 0 AND job.deleted = 0")
    suspend fun liveBonuses(): List<BonusWithJobSyncId>

    @Query("SELECT COUNT(*) FROM jobs WHERE sync_id = :syncId")
    suspend fun jobSyncIdCount(syncId: String): Int

    @Query("SELECT COUNT(*) FROM shifts WHERE sync_id = :syncId")
    suspend fun shiftSyncIdCount(syncId: String): Int

    @Query("SELECT COUNT(*) FROM bonuses WHERE sync_id = :syncId")
    suspend fun bonusSyncIdCount(syncId: String): Int

    /* Gives a local record the sync id of the cloud record it turned out to be (first sign-in merge). */
    @Query("UPDATE jobs SET sync_id = :syncId WHERE id = :id")
    suspend fun setJobSyncId(id: Long, syncId: String)

    @Query("UPDATE shifts SET sync_id = :syncId WHERE id = :id")
    suspend fun setShiftSyncId(id: Long, syncId: String)

    @Query("UPDATE bonuses SET sync_id = :syncId WHERE id = :id")
    suspend fun setBonusSyncId(id: Long, syncId: String)

    /* Removes every row, tombstones included: only when this phone takes the account's data instead of its own. */
    @Query("DELETE FROM bonuses")
    suspend fun deleteAllBonuses()

    @Query("DELETE FROM shifts")
    suspend fun deleteAllShifts()

    @Query("DELETE FROM job_pay_settings")
    suspend fun deleteAllPaySettings()

    @Query("DELETE FROM jobs")
    suspend fun deleteAllJobs()
}

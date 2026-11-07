package com.vkurylo.shifttracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Deleted jobs, shifts and bonuses stay in their tables as tombstones (`deleted = 1`, see SyncMetadata.kt).
 * Every read that feeds the app (screens, widget, export, .ics duplicate check, counts) filters them out;
 * only the `getAny` lookups, used to keep a row's sync metadata when it is written again, return them.
 * Shift and bonus lists also hide entries of a deleted job (ParentChildRule keeps such pairs from lasting).
 */

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE deleted = 0 ORDER BY id")
    suspend fun getAll(): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE id = :id AND deleted = 0")
    suspend fun get(id: Long): JobEntity?

    /** The row even when it is a tombstone. */
    @Query("SELECT * FROM jobs WHERE id = :id")
    suspend fun getAny(id: Long): JobEntity?

    @Upsert
    suspend fun upsert(job: JobEntity): Long

    @Insert
    suspend fun insertAll(jobs: List<JobEntity>)

    /** Removes every row, tombstones included. Only for the JSON import, which replaces all data. */
    @Query("DELETE FROM jobs")
    suspend fun deleteAll()

    @Query("UPDATE jobs SET deleted = 1, dirty = 1, updated_at = :at WHERE id = :id")
    suspend fun markDeleted(id: Long, at: Long)

    @Query("UPDATE jobs SET deleted = 0, dirty = 1, updated_at = :at WHERE id = :id")
    suspend fun markRevived(id: Long, at: Long)

    /** Deletes the job's live shifts, each at `max(its updated_at + 1, at)`. */
    @Query("UPDATE shifts SET deleted = 1, dirty = 1, updated_at = MAX(updated_at + 1, :at) WHERE job_id = :jobId AND deleted = 0")
    suspend fun deleteLiveShiftsOf(jobId: Long, at: Long)

    /** Deletes the job's live bonuses, each at `max(its updated_at + 1, at)`. */
    @Query("UPDATE bonuses SET deleted = 1, dirty = 1, updated_at = MAX(updated_at + 1, :at) WHERE job_id = :jobId AND deleted = 0")
    suspend fun deleteLiveBonusesOf(jobId: Long, at: Long)

    /** Deleted jobs that still have a live shift or bonus (see [ParentChildRule]). */
    @Query(
        """
        SELECT id FROM jobs j WHERE deleted = 1 AND (
            EXISTS (SELECT 1 FROM shifts s WHERE s.job_id = j.id AND s.deleted = 0) OR
            EXISTS (SELECT 1 FROM bonuses b WHERE b.job_id = j.id AND b.deleted = 0)
        )
        """,
    )
    suspend fun deletedJobsWithLiveEntries(): List<Long>

    @Query(
        """
        SELECT updated_at FROM shifts WHERE job_id = :jobId AND deleted = 0
        UNION ALL
        SELECT updated_at FROM bonuses WHERE job_id = :jobId AND deleted = 0
        """,
    )
    suspend fun liveEntryUpdatedAts(jobId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM shifts WHERE job_id = :jobId AND deleted = 0")
    suspend fun shiftCount(jobId: Long): Int

    @Query(
        "SELECT (SELECT COUNT(*) FROM shifts WHERE job_id = :jobId AND deleted = 0) + " +
            "(SELECT COUNT(*) FROM bonuses WHERE job_id = :jobId AND deleted = 0)",
    )
    suspend fun entryCount(jobId: Long): Int

    /** The currency of the most recently created job (highest id), for a new job's default. */
    @Query("SELECT currency FROM jobs WHERE deleted = 0 ORDER BY id DESC LIMIT 1")
    suspend fun latestCurrency(): String?
}

@Dao
interface ShiftDao {
    @Query(
        """
        SELECT s.*, j.id AS j_id, j.name AS j_name,
               j.hourly_rate_grosze AS j_hourly_rate_grosze, j.color AS j_color,
               j.second_rate_grosze AS j_second_rate_grosze,
               j.second_rate_from_minute AS j_second_rate_from_minute,
               j.second_rate_to_minute AS j_second_rate_to_minute,
               j.currency AS j_currency,
               j.sync_id AS j_sync_id, j.updated_at AS j_updated_at, j.deleted AS j_deleted, j.dirty AS j_dirty
        FROM shifts s INNER JOIN jobs j ON j.id = s.job_id
        WHERE s.date_epoch_day BETWEEN :fromEpochDay AND :toEpochDay AND s.deleted = 0 AND j.deleted = 0
        ORDER BY s.date_epoch_day, s.start_minute
        """,
    )
    fun observeBetween(fromEpochDay: Long, toEpochDay: Long): Flow<List<ShiftWithJobRow>>

    @Query("SELECT * FROM shifts WHERE id = :id AND deleted = 0")
    suspend fun get(id: Long): ShiftEntity?

    /** The row even when it is a tombstone. */
    @Query("SELECT * FROM shifts WHERE id = :id")
    suspend fun getAny(id: Long): ShiftEntity?

    @Query(
        """
        SELECT s.* FROM shifts s INNER JOIN jobs j ON j.id = s.job_id
        WHERE s.deleted = 0 AND j.deleted = 0
        ORDER BY s.date_epoch_day, s.start_minute
        """,
    )
    suspend fun getAll(): List<ShiftEntity>

    @Upsert
    suspend fun upsert(shift: ShiftEntity): Long

    @Insert
    suspend fun insertAll(shifts: List<ShiftEntity>)

    /** Deletes the shift (a tombstone) at `max(its updated_at + 1, now)`. */
    @Query("UPDATE shifts SET deleted = 1, dirty = 1, updated_at = MAX(updated_at + 1, :now) WHERE id = :id AND deleted = 0")
    suspend fun markDeleted(id: Long, now: Long)

    /** Removes every row, tombstones included. Only for the JSON import, which replaces all data. */
    @Query("DELETE FROM shifts")
    suspend fun deleteAll()
}

@Dao
interface BonusDao {
    @Query(
        """
        SELECT b.*, j.id AS j_id, j.name AS j_name,
               j.hourly_rate_grosze AS j_hourly_rate_grosze, j.color AS j_color,
               j.second_rate_grosze AS j_second_rate_grosze,
               j.second_rate_from_minute AS j_second_rate_from_minute,
               j.second_rate_to_minute AS j_second_rate_to_minute,
               j.currency AS j_currency,
               j.sync_id AS j_sync_id, j.updated_at AS j_updated_at, j.deleted AS j_deleted, j.dirty AS j_dirty
        FROM bonuses b INNER JOIN jobs j ON j.id = b.job_id
        WHERE b.date_epoch_day BETWEEN :fromEpochDay AND :toEpochDay AND b.deleted = 0 AND j.deleted = 0
        ORDER BY b.date_epoch_day, b.id
        """,
    )
    fun observeBetween(fromEpochDay: Long, toEpochDay: Long): Flow<List<BonusWithJobRow>>

    @Query("SELECT * FROM bonuses WHERE id = :id AND deleted = 0")
    suspend fun get(id: Long): BonusEntity?

    /** The row even when it is a tombstone. */
    @Query("SELECT * FROM bonuses WHERE id = :id")
    suspend fun getAny(id: Long): BonusEntity?

    @Query(
        """
        SELECT b.* FROM bonuses b INNER JOIN jobs j ON j.id = b.job_id
        WHERE b.deleted = 0 AND j.deleted = 0
        ORDER BY b.date_epoch_day, b.id
        """,
    )
    suspend fun getAll(): List<BonusEntity>

    @Upsert
    suspend fun upsert(bonus: BonusEntity): Long

    @Insert
    suspend fun insertAll(bonuses: List<BonusEntity>)

    /** Deletes the bonus (a tombstone) at `max(its updated_at + 1, now)`. */
    @Query("UPDATE bonuses SET deleted = 1, dirty = 1, updated_at = MAX(updated_at + 1, :now) WHERE id = :id AND deleted = 0")
    suspend fun markDeleted(id: Long, now: Long)

    /** Removes every row, tombstones included. Only for the JSON import, which replaces all data. */
    @Query("DELETE FROM bonuses")
    suspend fun deleteAll()
}

@Dao
interface PaySettingsDao {
    @Query("SELECT * FROM job_pay_settings ORDER BY job_id, from_month")
    fun observeAll(): Flow<List<JobPaySettingsEntity>>

    @Query("SELECT * FROM job_pay_settings ORDER BY job_id, from_month")
    suspend fun getAll(): List<JobPaySettingsEntity>

    @Query("SELECT * FROM job_pay_settings WHERE job_id = :jobId ORDER BY from_month")
    suspend fun forJob(jobId: Long): List<JobPaySettingsEntity>

    @Query("DELETE FROM job_pay_settings WHERE job_id = :jobId")
    suspend fun deleteForJob(jobId: Long)

    @Query("DELETE FROM job_pay_settings WHERE job_id = :jobId AND from_month = :fromMonth")
    suspend fun deleteEntry(jobId: Long, fromMonth: Int)

    @Insert
    suspend fun insert(entry: JobPaySettingsEntity)

    @Insert
    suspend fun insertAll(entries: List<JobPaySettingsEntity>)

    @Query("DELETE FROM job_pay_settings")
    suspend fun deleteAll()

    /** Each job's first and last day with a (not deleted) shift or bonus. */
    @Query(
        """
        SELECT job_id, MIN(day) AS first_day, MAX(day) AS last_day FROM (
            SELECT job_id, date_epoch_day AS day FROM shifts WHERE deleted = 0
            UNION ALL
            SELECT job_id, date_epoch_day AS day FROM bonuses WHERE deleted = 0
        ) GROUP BY job_id
        """,
    )
    fun observeActivity(): Flow<List<JobActivityRow>>

    @Query(
        """
        SELECT job_id, MIN(day) AS first_day, MAX(day) AS last_day FROM (
            SELECT job_id, date_epoch_day AS day FROM shifts WHERE deleted = 0
            UNION ALL
            SELECT job_id, date_epoch_day AS day FROM bonuses WHERE deleted = 0
        ) GROUP BY job_id
        """,
    )
    suspend fun getActivity(): List<JobActivityRow>
}

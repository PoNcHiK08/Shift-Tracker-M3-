package com.vkurylo.shifttracker.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs ORDER BY id")
    suspend fun getAll(): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE id = :id")
    suspend fun get(id: Long): JobEntity?

    @Upsert
    suspend fun upsert(job: JobEntity): Long

    @Insert
    suspend fun insertAll(jobs: List<JobEntity>)

    @Delete
    suspend fun delete(job: JobEntity)

    @Query("DELETE FROM jobs")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM shifts WHERE job_id = :jobId")
    suspend fun shiftCount(jobId: Long): Int

    @Query("SELECT (SELECT COUNT(*) FROM shifts WHERE job_id = :jobId) + (SELECT COUNT(*) FROM bonuses WHERE job_id = :jobId)")
    suspend fun entryCount(jobId: Long): Int

    /** The currency of the most recently created job (highest id), for a new job's default. */
    @Query("SELECT currency FROM jobs ORDER BY id DESC LIMIT 1")
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
               j.currency AS j_currency
        FROM shifts s INNER JOIN jobs j ON j.id = s.job_id
        WHERE s.date_epoch_day BETWEEN :fromEpochDay AND :toEpochDay
        ORDER BY s.date_epoch_day, s.start_minute
        """,
    )
    fun observeBetween(fromEpochDay: Long, toEpochDay: Long): Flow<List<ShiftWithJobRow>>

    @Query("SELECT * FROM shifts WHERE id = :id")
    suspend fun get(id: Long): ShiftEntity?

    @Query("SELECT * FROM shifts ORDER BY date_epoch_day, start_minute")
    suspend fun getAll(): List<ShiftEntity>

    @Upsert
    suspend fun upsert(shift: ShiftEntity): Long

    @Insert
    suspend fun insertAll(shifts: List<ShiftEntity>)

    @Delete
    suspend fun delete(shift: ShiftEntity)

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
               j.currency AS j_currency
        FROM bonuses b INNER JOIN jobs j ON j.id = b.job_id
        WHERE b.date_epoch_day BETWEEN :fromEpochDay AND :toEpochDay
        ORDER BY b.date_epoch_day, b.id
        """,
    )
    fun observeBetween(fromEpochDay: Long, toEpochDay: Long): Flow<List<BonusWithJobRow>>

    @Query("SELECT * FROM bonuses WHERE id = :id")
    suspend fun get(id: Long): BonusEntity?

    @Query("SELECT * FROM bonuses ORDER BY date_epoch_day, id")
    suspend fun getAll(): List<BonusEntity>

    @Upsert
    suspend fun upsert(bonus: BonusEntity): Long

    @Insert
    suspend fun insertAll(bonuses: List<BonusEntity>)

    @Delete
    suspend fun delete(bonus: BonusEntity)

    @Query("DELETE FROM bonuses")
    suspend fun deleteAll()
}

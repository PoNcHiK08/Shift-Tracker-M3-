package com.vkurylo.shifttracker.data.repository

import com.vkurylo.shifttracker.data.local.BonusDao
import com.vkurylo.shifttracker.data.local.JobDao
import com.vkurylo.shifttracker.data.local.ShiftDao
import com.vkurylo.shifttracker.data.local.toDomain
import com.vkurylo.shifttracker.data.local.toEntity
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.domain.repository.BonusRepository
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.ShiftRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomJobRepository @Inject constructor(
    private val dao: JobDao,
) : JobRepository {
    override fun observeJobs(): Flow<List<Job>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    override suspend fun getJob(id: Long): Job? = dao.get(id)?.toDomain()
    override suspend fun upsert(job: Job): Long {
        val rowId = dao.upsert(job.toEntity())
        // @Upsert returns -1 when it updated an existing row.
        return if (rowId == -1L) job.id else rowId
    }
    override suspend fun delete(job: Job) = dao.delete(job.toEntity())
    override suspend fun shiftCount(jobId: Long): Int = dao.shiftCount(jobId)
}

@Singleton
class RoomShiftRepository @Inject constructor(
    private val dao: ShiftDao,
) : ShiftRepository {
    override fun observeShifts(from: LocalDate, to: LocalDate): Flow<List<ShiftWithJob>> =
        dao.observeBetween(from.toEpochDay(), to.toEpochDay()).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getShift(id: Long): Shift? = dao.get(id)?.toDomain()
    override suspend fun getAllShifts(): List<Shift> = dao.getAll().map { it.toDomain() }
    override suspend fun upsert(shift: Shift): Long {
        val rowId = dao.upsert(shift.toEntity())
        return if (rowId == -1L) shift.id else rowId
    }
    override suspend fun insertAll(shifts: List<Shift>) = dao.insertAll(shifts.map { it.toEntity() })
    override suspend fun delete(shift: Shift) = dao.delete(shift.toEntity())
}

@Singleton
class RoomBonusRepository @Inject constructor(
    private val dao: BonusDao,
) : BonusRepository {
    override fun observeBonuses(from: LocalDate, to: LocalDate): Flow<List<BonusWithJob>> =
        dao.observeBetween(from.toEpochDay(), to.toEpochDay()).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getBonus(id: Long): Bonus? = dao.get(id)?.toDomain()
    override suspend fun upsert(bonus: Bonus): Long {
        val rowId = dao.upsert(bonus.toEntity())
        return if (rowId == -1L) bonus.id else rowId
    }
    override suspend fun delete(bonus: Bonus) = dao.delete(bonus.toEntity())
}

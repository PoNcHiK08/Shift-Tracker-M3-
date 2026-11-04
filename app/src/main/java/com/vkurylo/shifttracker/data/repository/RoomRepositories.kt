package com.vkurylo.shifttracker.data.repository

import androidx.room.withTransaction
import com.vkurylo.shifttracker.data.local.BonusDao
import com.vkurylo.shifttracker.data.local.JobActivityRow
import com.vkurylo.shifttracker.data.local.JobPaySettingsEntity
import com.vkurylo.shifttracker.data.local.PaySettingsDao
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.toMonthKey
import com.vkurylo.shifttracker.data.local.JobDao
import com.vkurylo.shifttracker.data.local.ShiftDao
import com.vkurylo.shifttracker.data.local.toDomain
import com.vkurylo.shifttracker.data.local.toEntity
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.domain.repository.BonusRepository
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.ShiftRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything a [Job] carries beyond its own row: its pay settings history and the span of its entries
 * (for a monthly salary). Joined in by every repository that hands out jobs.
 */
@Singleton
class JobDetailsSource @Inject constructor(private val dao: PaySettingsDao) {
    class Details(val history: Map<Long, List<PaySettings>>, val activity: Map<Long, JobActivityRow>)

    val details: Flow<Details> = combine(dao.observeAll(), dao.observeActivity()) { history, activity -> details(history, activity) }

    suspend fun get(): Details = details(dao.getAll(), dao.getActivity())

    private fun details(history: List<JobPaySettingsEntity>, activity: List<JobActivityRow>) = Details(
        history = history.groupBy({ it.jobId }, { it.toDomain() }),
        activity = activity.associateBy { it.jobId },
    )
}

private fun Job.withDetails(details: JobDetailsSource.Details): Job = copy(
    payHistory = details.history[id].orEmpty(),
    firstEntry = details.activity[id]?.let { LocalDate.ofEpochDay(it.firstDay) },
    lastEntry = details.activity[id]?.let { LocalDate.ofEpochDay(it.lastDay) },
)

@Singleton
class RoomJobRepository @Inject constructor(
    private val db: ShiftDatabase,
    private val dao: JobDao,
    private val payDao: PaySettingsDao,
    private val details: JobDetailsSource,
) : JobRepository {
    override fun observeJobs(): Flow<List<Job>> =
        combine(dao.observeAll(), details.details) { list, d -> list.map { it.toDomain().withDetails(d) } }
    override suspend fun getJob(id: Long): Job? = dao.get(id)?.toDomain()?.withDetails(details.get())
    override suspend fun upsert(job: Job): Long {
        val rowId = dao.upsert(job.toEntity())
        // @Upsert returns -1 when it updated an existing row.
        return if (rowId == -1L) job.id else rowId
    }
    override suspend fun save(job: Job, pay: PaySettings): Long = db.withTransaction {
        val id = upsert(job)
        if (pay.from == null) payDao.deleteForJob(id) else payDao.deleteEntry(id, pay.from.toMonthKey())
        payDao.insert(pay.toEntity(id))
        id
    }
    override suspend fun delete(job: Job) = dao.delete(job.toEntity())
    override suspend fun shiftCount(jobId: Long): Int = dao.shiftCount(jobId)
    override suspend fun entryCount(jobId: Long): Int = dao.entryCount(jobId)
    override suspend fun latestCurrency(): String? = dao.latestCurrency()
}

@Singleton
class RoomShiftRepository @Inject constructor(
    private val dao: ShiftDao,
    private val details: JobDetailsSource,
) : ShiftRepository {
    override fun observeShifts(from: LocalDate, to: LocalDate): Flow<List<ShiftWithJob>> =
        combine(dao.observeBetween(from.toEpochDay(), to.toEpochDay()), details.details) { rows, d ->
            rows.map { row -> row.toDomain().let { it.copy(job = it.job.withDetails(d)) } }
        }

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
    private val details: JobDetailsSource,
) : BonusRepository {
    override fun observeBonuses(from: LocalDate, to: LocalDate): Flow<List<BonusWithJob>> =
        combine(dao.observeBetween(from.toEpochDay(), to.toEpochDay()), details.details) { rows, d ->
            rows.map { row -> row.toDomain().let { it.copy(job = it.job.withDetails(d)) } }
        }

    override suspend fun getBonus(id: Long): Bonus? = dao.get(id)?.toDomain()
    override suspend fun upsert(bonus: Bonus): Long {
        val rowId = dao.upsert(bonus.toEntity())
        return if (rowId == -1L) bonus.id else rowId
    }
    override suspend fun delete(bonus: Bonus) = dao.delete(bonus.toEntity())
}

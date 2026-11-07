package com.vkurylo.shifttracker.data.repository

import androidx.room.withTransaction
import com.vkurylo.shifttracker.data.local.BonusDao
import com.vkurylo.shifttracker.data.local.JobActivityRow
import com.vkurylo.shifttracker.data.local.JobPaySettingsEntity
import com.vkurylo.shifttracker.data.local.ParentChildRule
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.local.nextUpdatedAt
import com.vkurylo.shifttracker.data.local.stamped
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
    private val clock: WallClock = WallClock.System,
) : JobRepository {
    override fun observeJobs(): Flow<List<Job>> =
        combine(dao.observeAll(), details.details) { list, d -> list.map { it.toDomain().withDetails(d) } }
    override suspend fun getJob(id: Long): Job? = dao.get(id)?.toDomain()?.withDetails(details.get())

    /** Keeps the row's sync id, moves `updated_at` forward and marks it changed (undeleting it if needed). */
    override suspend fun upsert(job: Job): Long = db.withTransaction {
        val existing = if (job.id != 0L) dao.getAny(job.id) else null
        val rowId = dao.upsert(job.toEntity().stamped(existing, clock.nowMillis()))
        // @Upsert returns -1 when it updated an existing row.
        if (rowId == -1L) job.id else rowId
    }
    override suspend fun save(job: Job, pay: PaySettings): Long = db.withTransaction {
        val id = upsert(job)
        if (pay.from == null) payDao.deleteForJob(id) else payDao.deleteEntry(id, pay.from.toMonthKey())
        payDao.insert(pay.toEntity(id))
        id
    }
    /**
     * Deletes the job and its shifts and bonuses as tombstones, so the deletion can sync. The entries'
     * tombstones are no older than the job's, so an entry restored later counts as newer than the
     * deletion (see [ParentChildRule]).
     */
    override suspend fun delete(job: Job) = db.withTransaction {
        val existing = dao.getAny(job.id)
        if (existing == null || existing.deleted) return@withTransaction
        val at = nextUpdatedAt(existing.updatedAt, clock.nowMillis())
        dao.markDeleted(job.id, at)
        dao.deleteLiveShiftsOf(job.id, at)
        dao.deleteLiveBonusesOf(job.id, at)
    }
    override suspend fun shiftCount(jobId: Long): Int = dao.shiftCount(jobId)
    override suspend fun entryCount(jobId: Long): Int = dao.entryCount(jobId)
    override suspend fun latestCurrency(): String? = dao.latestCurrency()
}

@Singleton
class RoomShiftRepository @Inject constructor(
    private val db: ShiftDatabase,
    private val dao: ShiftDao,
    private val details: JobDetailsSource,
    private val clock: WallClock = WallClock.System,
) : ShiftRepository {
    override fun observeShifts(from: LocalDate, to: LocalDate): Flow<List<ShiftWithJob>> =
        combine(dao.observeBetween(from.toEpochDay(), to.toEpochDay()), details.details) { rows, d ->
            rows.map { row -> row.toDomain().let { it.copy(job = it.job.withDetails(d)) } }
        }

    override suspend fun getShift(id: Long): Shift? = dao.get(id)?.toDomain()
    override suspend fun getAllShifts(): List<Shift> = dao.getAll().map { it.toDomain() }
    /** Keeps the row's sync id, moves `updated_at` forward and marks it changed; Undo uses it to undelete. */
    override suspend fun upsert(shift: Shift): Long = db.withTransaction {
        val existing = if (shift.id != 0L) dao.getAny(shift.id) else null
        val rowId = dao.upsert(shift.toEntity().stamped(existing, clock.nowMillis()))
        ParentChildRule.enforce(db.jobDao())
        if (rowId == -1L) shift.id else rowId
    }
    override suspend fun insertAll(shifts: List<Shift>) = db.withTransaction {
        val now = clock.nowMillis()
        dao.insertAll(shifts.map { it.toEntity().stamped(null, now) })
        ParentChildRule.enforce(db.jobDao())
    }
    override suspend fun delete(shift: Shift) = dao.markDeleted(shift.id, clock.nowMillis())
}

@Singleton
class RoomBonusRepository @Inject constructor(
    private val db: ShiftDatabase,
    private val dao: BonusDao,
    private val details: JobDetailsSource,
    private val clock: WallClock = WallClock.System,
) : BonusRepository {
    override fun observeBonuses(from: LocalDate, to: LocalDate): Flow<List<BonusWithJob>> =
        combine(dao.observeBetween(from.toEpochDay(), to.toEpochDay()), details.details) { rows, d ->
            rows.map { row -> row.toDomain().let { it.copy(job = it.job.withDetails(d)) } }
        }

    override suspend fun getBonus(id: Long): Bonus? = dao.get(id)?.toDomain()
    /** Keeps the row's sync id, moves `updated_at` forward and marks it changed; Undo uses it to undelete. */
    override suspend fun upsert(bonus: Bonus): Long = db.withTransaction {
        val existing = if (bonus.id != 0L) dao.getAny(bonus.id) else null
        val rowId = dao.upsert(bonus.toEntity().stamped(existing, clock.nowMillis()))
        ParentChildRule.enforce(db.jobDao())
        if (rowId == -1L) bonus.id else rowId
    }
    override suspend fun delete(bonus: Bonus) = dao.markDeleted(bonus.id, clock.nowMillis())
}

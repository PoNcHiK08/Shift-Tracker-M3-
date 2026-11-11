package com.vkurylo.shifttracker.data.repository

import androidx.room.withTransaction
import com.vkurylo.shifttracker.data.local.BonusDao
import com.vkurylo.shifttracker.data.local.JobActivityRow
import com.vkurylo.shifttracker.data.local.JobPaySettingsEntity
import com.vkurylo.shifttracker.data.local.ParentChildRule
import com.vkurylo.shifttracker.data.local.PayoutDao
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.local.calendarSyncId
import com.vkurylo.shifttracker.data.local.nextUpdatedAt
import com.vkurylo.shifttracker.data.local.stamped
import com.vkurylo.shifttracker.data.local.PaySettingsDao
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.ShiftEntity
import com.vkurylo.shifttracker.data.local.toMonthKey
import com.vkurylo.shifttracker.data.local.JobDao
import com.vkurylo.shifttracker.data.local.ShiftDao
import com.vkurylo.shifttracker.data.local.toDomain
import com.vkurylo.shifttracker.data.local.toEntity
import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.model.Payout
import com.vkurylo.shifttracker.domain.model.PayoutWithJob
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.domain.repository.BonusRepository
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.PayoutRepository
import com.vkurylo.shifttracker.domain.repository.ShiftRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
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
     * Deletes the job and its shifts, bonuses and payouts as tombstones, so the deletion can sync. The
     * entries' tombstones are no older than the job's, so an entry restored later counts as newer than the
     * deletion (see [ParentChildRule]).
     */
    override suspend fun delete(job: Job) = db.withTransaction {
        val existing = dao.getAny(job.id)
        if (existing == null || existing.deleted) return@withTransaction
        val at = nextUpdatedAt(existing.updatedAt, clock.nowMillis())
        dao.markDeleted(job.id, at)
        dao.deleteLiveShiftsOf(job.id, at)
        dao.deleteLiveBonusesOf(job.id, at)
        dao.deleteLivePayoutsOf(job.id, at)
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
    override suspend fun importFromCalendar(shifts: List<Shift>) = db.withTransaction {
        val now = clock.nowMillis()
        for (shift in shifts) {
            val syncId = calendarSyncId(shift.externalUid, shift.date, shift.start, shift.end)
            val existing = dao.getBySyncId(syncId)
            val entity = if (existing != null && !existing.deleted) {
                // A duplicate the user ticked in the preview: the calendar only brings new times. The job,
                // rates, unpaid break and note are the user's own and stay.
                existing.withTimes(shift.start, shift.end)
            } else {
                // New, or deleted earlier: the event as the calendar has it.
                shift.toEntity().copy(id = existing?.id ?: 0, syncId = syncId)
            }
            dao.upsert(entity.stamped(existing, now))
        }
        ParentChildRule.enforce(db.jobDao())
    }

    /** New start/end; the break is kept, only cut down if it would no longer be shorter than the shift. */
    private fun ShiftEntity.withTimes(start: LocalTime, end: LocalTime): ShiftEntity {
        val duration = ShiftCalculator.durationMinutes(start, end).toInt()
        return copy(
            startMinute = start.hour * 60 + start.minute,
            endMinute = end.hour * 60 + end.minute,
            unpaidBreakMinutes = unpaidBreakMinutes.coerceAtMost(duration - 1),
        )
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

@Singleton
class RoomPayoutRepository @Inject constructor(
    private val db: ShiftDatabase,
    private val dao: PayoutDao,
    private val details: JobDetailsSource,
    private val clock: WallClock = WallClock.System,
) : PayoutRepository {
    override fun observePayouts(from: YearMonth, to: YearMonth): Flow<List<PayoutWithJob>> =
        combine(dao.observeBetween(from.toMonthKey(), to.toMonthKey()), details.details) { rows, d ->
            rows.mapNotNull { row -> row.toDomain()?.let { it.copy(job = it.job.withDetails(d)) } }
        }

    override suspend fun getPayout(id: Long): Payout? = dao.get(id)?.toDomain()

    /** Keeps the row's sync id, moves `updated_at` forward and marks it changed; Undo uses it to undelete. */
    override suspend fun upsert(payout: Payout): Long = db.withTransaction {
        val existing = if (payout.id != 0L) dao.getAny(payout.id) else null
        val rowId = dao.upsert(payout.toEntity().stamped(existing, clock.nowMillis()))
        ParentChildRule.enforce(db.jobDao())
        if (rowId == -1L) payout.id else rowId
    }

    override suspend fun delete(payout: Payout) = dao.markDeleted(payout.id, clock.nowMillis())
}

package com.vkurylo.shifttracker.data.backup

import androidx.room.withTransaction
import com.vkurylo.shifttracker.data.local.BonusEntity
import com.vkurylo.shifttracker.data.local.JobEntity
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.ShiftEntity
import com.vkurylo.shifttracker.domain.repository.BackupCounts
import com.vkurylo.shifttracker.domain.calc.MoneyParser
import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import com.vkurylo.shifttracker.domain.repository.BackupRepository
import com.vkurylo.shifttracker.domain.repository.InvalidBackupException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = CURRENT_VERSION,
    @SerialName("exported_at") val exportedAt: String,
    val jobs: List<BackupJob>,
    val shifts: List<BackupShift>,
    /** Added in backup version 3; older files simply have none. */
    val bonuses: List<BackupBonus> = emptyList(),
) {
    companion object {
        const val FORMAT = "shift-tracker-backup"

        /**
         * Every exporter so far wrote its version explicitly: 1 (jobs + shifts), 2 (+ second rates),
         * 3 (+ bonuses). All three decode into these classes (newer fields have defaults).
         */
        const val MIN_VERSION = 1
        const val CURRENT_VERSION = 3
    }
}

@Serializable
data class BackupJob(
    val id: Long,
    val name: String,
    @SerialName("hourly_rate_grosze") val hourlyRateGrosze: Long,
    val color: Int,
    @SerialName("second_rate") val secondRate: BackupTimedRate? = null,
)

/** Optional second rate between [from] and [to] (HH:mm). Added in backup version 2. */
@Serializable
data class BackupTimedRate(
    @SerialName("rate_grosze") val rateGrosze: Long,
    val from: String,
    val to: String,
)

@Serializable
data class BackupShift(
    @SerialName("job_id") val jobId: Long,
    /** ISO date, e.g. 2026-11-02. */
    val date: String,
    /** HH:mm */
    val start: String,
    val end: String,
    @SerialName("unpaid_break_minutes") val unpaidBreakMinutes: Int = 0,
    @SerialName("hourly_rate_grosze") val hourlyRateGrosze: Long,
    val note: String? = null,
    @SerialName("external_uid") val externalUid: String? = null,
    @SerialName("second_rate") val secondRate: BackupTimedRate? = null,
)

@Serializable
data class BackupBonus(
    @SerialName("job_id") val jobId: Long,
    /** ISO date, e.g. 2026-08-31. */
    val date: String,
    @SerialName("amount_grosze") val amountGrosze: Long,
    val note: String? = null,
)

@Singleton
class JsonBackupRepository @Inject constructor(
    private val db: ShiftDatabase,
) : BackupRepository {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun exportJson(): String {
        // One read transaction, so the three lists are a consistent snapshot.
        val (jobs, shifts, bonuses) = db.withTransaction {
            Triple(db.jobDao().getAll(), db.shiftDao().getAll(), db.bonusDao().getAll())
        }
        val file = BackupFile(
            exportedAt = java.time.OffsetDateTime.now().toString(),
            jobs = jobs.map {
                BackupJob(
                    id = it.id,
                    name = it.name,
                    hourlyRateGrosze = it.hourlyRateGrosze,
                    color = it.color,
                    secondRate = backupRate(it.secondRateGrosze, it.secondRateFromMinute, it.secondRateToMinute),
                )
            },
            shifts = shifts.map {
                BackupShift(
                    jobId = it.jobId,
                    date = java.time.LocalDate.ofEpochDay(it.dateEpochDay).toString(),
                    start = minuteToText(it.startMinute),
                    end = minuteToText(it.endMinute),
                    unpaidBreakMinutes = it.unpaidBreakMinutes,
                    hourlyRateGrosze = it.hourlyRateGrosze,
                    note = it.note,
                    externalUid = it.externalUid,
                    secondRate = backupRate(it.secondRateGrosze, it.secondRateFromMinute, it.secondRateToMinute),
                )
            },
            bonuses = bonuses.map {
                BackupBonus(
                    jobId = it.jobId,
                    date = java.time.LocalDate.ofEpochDay(it.dateEpochDay).toString(),
                    amountGrosze = it.amountGrosze,
                    note = it.note,
                )
            },
        )
        return json.encodeToString(BackupFile.serializer(), file)
    }

    override suspend fun importJson(json: String): BackupCounts {
        val file = try {
            this.json.decodeFromString(BackupFile.serializer(), json)
        } catch (e: IllegalArgumentException) { // includes kotlinx.serialization's SerializationException
            throw InvalidBackupException("Not a Shift Tracker backup", e)
        }
        if (file.format != BackupFile.FORMAT) throw InvalidBackupException("Unknown format ${file.format}")
        if (file.version > BackupFile.CURRENT_VERSION) {
            throw InvalidBackupException(
                "Backup version ${file.version} is newer than ${BackupFile.CURRENT_VERSION}",
                reason = InvalidBackupException.Reason.NEWER_VERSION,
            )
        }
        if (file.version < BackupFile.MIN_VERSION) throw InvalidBackupException("Unknown backup version ${file.version}")

        // Everything is validated and converted before the database is touched. The rules are the
        // ones the editors enforce, so any backup this app exported passes.
        val jobs = convert("jobs") { toJobs(file.jobs) }
        val jobIds = jobs.mapTo(HashSet()) { it.id }
        val shifts = convert("shifts") { file.shifts.map { toShift(it, jobIds) } }
        val bonuses = convert("bonuses") { file.bonuses.map { toBonus(it, jobIds) } }

        // If any write fails, the transaction rolls back and the old data stays.
        db.withTransaction {
            db.bonusDao().deleteAll()
            db.shiftDao().deleteAll()
            db.jobDao().deleteAll()
            db.jobDao().insertAll(jobs)
            db.shiftDao().insertAll(shifts)
            db.bonusDao().insertAll(bonuses)
        }
        return BackupCounts(jobs = jobs.size, shifts = shifts.size, bonuses = bonuses.size)
    }

    private inline fun <T> convert(what: String, block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw InvalidBackupException("Backup contains invalid $what: ${e.message}", e)
    } catch (e: java.time.DateTimeException) {
        throw InvalidBackupException("Backup contains invalid $what: ${e.message}", e)
    }

    private fun toJobs(jobs: List<BackupJob>): List<JobEntity> {
        val duplicates = jobs.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "duplicate job ids $duplicates" }
        return jobs.map {
            require(it.id > 0) { "job id ${it.id}" }
            require(it.name.isNotBlank()) { "job ${it.id} has no name" }
            requireAmount(it.hourlyRateGrosze, "rate of job ${it.id}")
            val second = it.secondRate?.let(::toWindow)
            JobEntity(
                id = it.id,
                name = it.name,
                hourlyRateGrosze = it.hourlyRateGrosze,
                color = it.color,
                secondRateGrosze = second?.rate,
                secondRateFromMinute = second?.from ?: DEFAULT_FROM,
                secondRateToMinute = second?.to ?: DEFAULT_TO,
            )
        }
    }

    private fun toShift(shift: BackupShift, jobIds: Set<Long>): ShiftEntity {
        require(shift.jobId in jobIds) { "shift refers to unknown job ${shift.jobId}" }
        requireAmount(shift.hourlyRateGrosze, "shift rate")
        val start = textToMinute(shift.start)
        val end = textToMinute(shift.end)
        val duration = ShiftCalculator.durationMinutes(minuteToTime(start), minuteToTime(end))
        // Same rule as the shift editor: the break must be shorter than the shift.
        require(shift.unpaidBreakMinutes in 0 until duration) { "break ${shift.unpaidBreakMinutes} min for a $duration min shift" }
        val second = shift.secondRate?.let(::toWindow)
        return ShiftEntity(
            jobId = shift.jobId,
            dateEpochDay = java.time.LocalDate.parse(shift.date).toEpochDay(),
            startMinute = start,
            endMinute = end,
            unpaidBreakMinutes = shift.unpaidBreakMinutes,
            hourlyRateGrosze = shift.hourlyRateGrosze,
            note = shift.note,
            externalUid = shift.externalUid,
            secondRateGrosze = second?.rate,
            secondRateFromMinute = second?.from ?: DEFAULT_FROM,
            secondRateToMinute = second?.to ?: DEFAULT_TO,
        )
    }

    private fun toBonus(bonus: BackupBonus, jobIds: Set<Long>): BonusEntity {
        require(bonus.jobId in jobIds) { "bonus refers to unknown job ${bonus.jobId}" }
        requireAmount(bonus.amountGrosze, "bonus")
        require(bonus.amountGrosze > 0) { "bonus of ${bonus.amountGrosze}" }
        return BonusEntity(
            jobId = bonus.jobId,
            dateEpochDay = java.time.LocalDate.parse(bonus.date).toEpochDay(),
            amountGrosze = bonus.amountGrosze,
            note = bonus.note,
        )
    }

    private class Window(val rate: Long, val from: Int, val to: Int)

    /** A second rate: amount in range and a non-empty window (the job editor rejects from == to). */
    private fun toWindow(rate: BackupTimedRate): Window {
        requireAmount(rate.rateGrosze, "second rate")
        val from = textToMinute(rate.from)
        val to = textToMinute(rate.to)
        require(from != to) { "second-rate window ${rate.from}–${rate.to} is empty" }
        return Window(rate.rateGrosze, from, to)
    }

    private fun requireAmount(grosze: Long, what: String) =
        require(grosze in 0..MoneyParser.MAX_GROSZE) { "$what out of range: $grosze" }

    private fun backupRate(grosze: Long?, fromMinute: Int, toMinute: Int): BackupTimedRate? =
        grosze?.let { BackupTimedRate(it, minuteToText(fromMinute), minuteToText(toMinute)) }

    private companion object {
        const val DEFAULT_FROM = 23 * 60
        const val DEFAULT_TO = 6 * 60
    }

    private fun minuteToText(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

    private fun textToMinute(text: String): Int {
        val time = java.time.LocalTime.parse(text)
        return time.hour * 60 + time.minute
    }

    private fun minuteToTime(minute: Int) = java.time.LocalTime.of(minute / 60, minute % 60)
}

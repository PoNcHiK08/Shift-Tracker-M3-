package com.vkurylo.shifttracker.data.backup

import androidx.room.withTransaction
import com.vkurylo.shifttracker.data.local.JobEntity
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.ShiftEntity
import com.vkurylo.shifttracker.domain.repository.BackupRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = 2,
    @SerialName("exported_at") val exportedAt: String,
    val jobs: List<BackupJob>,
    val shifts: List<BackupShift>,
) {
    companion object {
        const val FORMAT = "shift-tracker-backup"
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

class InvalidBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

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
        val jobs = db.jobDao().getAll()
        val shifts = db.shiftDao().getAll()
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
        )
        return json.encodeToString(BackupFile.serializer(), file)
    }

    override suspend fun importJson(json: String): Pair<Int, Int> {
        val file = try {
            this.json.decodeFromString(BackupFile.serializer(), json)
        } catch (e: Exception) {
            throw InvalidBackupException("Not a Shift Tracker backup", e)
        }
        if (file.format != BackupFile.FORMAT) throw InvalidBackupException("Unknown format ${file.format}")

        val jobIds = file.jobs.mapTo(HashSet()) { it.id }
        val shifts = try {
            file.shifts.map {
                require(it.jobId in jobIds) { "Shift refers to unknown job ${it.jobId}" }
                ShiftEntity(
                    jobId = it.jobId,
                    dateEpochDay = java.time.LocalDate.parse(it.date).toEpochDay(),
                    startMinute = textToMinute(it.start),
                    endMinute = textToMinute(it.end),
                    unpaidBreakMinutes = it.unpaidBreakMinutes.coerceAtLeast(0),
                    hourlyRateGrosze = it.hourlyRateGrosze,
                    note = it.note,
                    externalUid = it.externalUid,
                    secondRateGrosze = it.secondRate?.rateGrosze,
                    secondRateFromMinute = it.secondRate?.let { r -> textToMinute(r.from) } ?: DEFAULT_FROM,
                    secondRateToMinute = it.secondRate?.let { r -> textToMinute(r.to) } ?: DEFAULT_TO,
                )
            }
        } catch (e: Exception) {
            throw InvalidBackupException("Backup contains invalid shifts", e)
        }

        val jobs = try {
            file.jobs.map {
                JobEntity(
                    id = it.id,
                    name = it.name,
                    hourlyRateGrosze = it.hourlyRateGrosze,
                    color = it.color,
                    secondRateGrosze = it.secondRate?.rateGrosze,
                    secondRateFromMinute = it.secondRate?.let { r -> textToMinute(r.from) } ?: DEFAULT_FROM,
                    secondRateToMinute = it.secondRate?.let { r -> textToMinute(r.to) } ?: DEFAULT_TO,
                )
            }
        } catch (e: Exception) {
            throw InvalidBackupException("Backup contains invalid jobs", e)
        }

        db.withTransaction {
            db.shiftDao().deleteAll()
            db.jobDao().deleteAll()
            db.jobDao().insertAll(jobs)
            db.shiftDao().insertAll(shifts)
        }
        return file.jobs.size to shifts.size
    }

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
}

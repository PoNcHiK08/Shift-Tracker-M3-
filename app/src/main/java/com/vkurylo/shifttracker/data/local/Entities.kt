package com.vkurylo.shifttracker.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.domain.model.TimedRate
import java.time.LocalDate
import java.time.LocalTime

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "hourly_rate_grosze") val hourlyRateGrosze: Long,
    val color: Int,
    /** Optional second rate; null when the job has a single rate. Added in DB version 2. */
    @ColumnInfo(name = "second_rate_grosze") val secondRateGrosze: Long? = null,
    @ColumnInfo(name = "second_rate_from_minute", defaultValue = "1380") val secondRateFromMinute: Int = 1380,
    @ColumnInfo(name = "second_rate_to_minute", defaultValue = "360") val secondRateToMinute: Int = 360,
)

@Entity(
    tableName = "shifts",
    foreignKeys = [
        ForeignKey(
            entity = JobEntity::class,
            parentColumns = ["id"],
            childColumns = ["job_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("job_id"), Index("date_epoch_day"), Index("external_uid")],
)
data class ShiftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "job_id") val jobId: Long,
    @ColumnInfo(name = "date_epoch_day") val dateEpochDay: Long,
    /** Minutes since midnight. */
    @ColumnInfo(name = "start_minute") val startMinute: Int,
    @ColumnInfo(name = "end_minute") val endMinute: Int,
    @ColumnInfo(name = "unpaid_break_minutes") val unpaidBreakMinutes: Int,
    @ColumnInfo(name = "hourly_rate_grosze") val hourlyRateGrosze: Long,
    val note: String?,
    @ColumnInfo(name = "external_uid") val externalUid: String?,
    /** Snapshot of the job's second rate when the shift was saved. Added in DB version 2. */
    @ColumnInfo(name = "second_rate_grosze") val secondRateGrosze: Long? = null,
    @ColumnInfo(name = "second_rate_from_minute", defaultValue = "1380") val secondRateFromMinute: Int = 1380,
    @ColumnInfo(name = "second_rate_to_minute", defaultValue = "360") val secondRateToMinute: Int = 360,
)

data class ShiftWithJobRow(
    @Embedded val shift: ShiftEntity,
    @Embedded(prefix = "j_") val job: JobEntity,
)

internal fun minuteToTime(minute: Int): LocalTime = LocalTime.of(minute / 60 % 24, minute % 60)
internal fun LocalTime.toMinute(): Int = hour * 60 + minute

private fun timedRate(grosze: Long?, fromMinute: Int, toMinute: Int): TimedRate? =
    grosze?.let { TimedRate(it, minuteToTime(fromMinute), minuteToTime(toMinute)) }

fun JobEntity.toDomain() = Job(
    id = id,
    name = name,
    hourlyRateGrosze = hourlyRateGrosze,
    color = color,
    secondRate = timedRate(secondRateGrosze, secondRateFromMinute, secondRateToMinute),
)

fun Job.toEntity() = JobEntity(
    id = id,
    name = name,
    hourlyRateGrosze = hourlyRateGrosze,
    color = color,
    secondRateGrosze = secondRate?.rateGrosze,
    secondRateFromMinute = (secondRate?.from ?: TimedRate.DEFAULT_NIGHT_FROM).toMinute(),
    secondRateToMinute = (secondRate?.to ?: TimedRate.DEFAULT_NIGHT_TO).toMinute(),
)

fun ShiftEntity.toDomain() = Shift(
    id = id,
    jobId = jobId,
    date = LocalDate.ofEpochDay(dateEpochDay),
    start = minuteToTime(startMinute),
    end = minuteToTime(endMinute),
    unpaidBreakMinutes = unpaidBreakMinutes,
    hourlyRateGrosze = hourlyRateGrosze,
    note = note,
    externalUid = externalUid,
    secondRate = timedRate(secondRateGrosze, secondRateFromMinute, secondRateToMinute),
)

fun Shift.toEntity() = ShiftEntity(
    id = id,
    jobId = jobId,
    dateEpochDay = date.toEpochDay(),
    startMinute = start.toMinute(),
    endMinute = end.toMinute(),
    unpaidBreakMinutes = unpaidBreakMinutes,
    hourlyRateGrosze = hourlyRateGrosze,
    note = note?.takeIf { it.isNotBlank() },
    externalUid = externalUid,
    secondRateGrosze = secondRate?.rateGrosze,
    secondRateFromMinute = (secondRate?.from ?: TimedRate.DEFAULT_NIGHT_FROM).toMinute(),
    secondRateToMinute = (secondRate?.to ?: TimedRate.DEFAULT_NIGHT_TO).toMinute(),
)

fun ShiftWithJobRow.toDomain() = ShiftWithJob(shift = shift.toDomain(), job = job.toDomain())

/** A one-off extra payment. Added in DB version 3. */
@Entity(
    tableName = "bonuses",
    foreignKeys = [
        ForeignKey(
            entity = JobEntity::class,
            parentColumns = ["id"],
            childColumns = ["job_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("job_id"), Index("date_epoch_day")],
)
data class BonusEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "job_id") val jobId: Long,
    @ColumnInfo(name = "date_epoch_day") val dateEpochDay: Long,
    @ColumnInfo(name = "amount_grosze") val amountGrosze: Long,
    val note: String?,
)

data class BonusWithJobRow(
    @Embedded val bonus: BonusEntity,
    @Embedded(prefix = "j_") val job: JobEntity,
)

fun BonusEntity.toDomain() = Bonus(
    id = id,
    jobId = jobId,
    date = LocalDate.ofEpochDay(dateEpochDay),
    amountGrosze = amountGrosze,
    note = note,
)

fun Bonus.toEntity() = BonusEntity(
    id = id,
    jobId = jobId,
    dateEpochDay = date.toEpochDay(),
    amountGrosze = amountGrosze,
    note = note?.takeIf { it.isNotBlank() },
)

fun BonusWithJobRow.toDomain() = BonusWithJob(bonus = bonus.toDomain(), job = job.toDomain())

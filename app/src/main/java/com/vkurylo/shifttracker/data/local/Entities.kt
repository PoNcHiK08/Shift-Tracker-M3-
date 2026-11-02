package com.vkurylo.shifttracker.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import java.time.LocalDate
import java.time.LocalTime

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "hourly_rate_grosze") val hourlyRateGrosze: Long,
    val color: Int,
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
)

data class ShiftWithJobRow(
    @Embedded val shift: ShiftEntity,
    @Embedded(prefix = "j_") val job: JobEntity,
)

fun JobEntity.toDomain() = Job(id = id, name = name, hourlyRateGrosze = hourlyRateGrosze, color = color)
fun Job.toEntity() = JobEntity(id = id, name = name, hourlyRateGrosze = hourlyRateGrosze, color = color)

fun ShiftEntity.toDomain() = Shift(
    id = id,
    jobId = jobId,
    date = LocalDate.ofEpochDay(dateEpochDay),
    start = LocalTime.of(startMinute / 60, startMinute % 60),
    end = LocalTime.of(endMinute / 60, endMinute % 60),
    unpaidBreakMinutes = unpaidBreakMinutes,
    hourlyRateGrosze = hourlyRateGrosze,
    note = note,
    externalUid = externalUid,
)

fun Shift.toEntity() = ShiftEntity(
    id = id,
    jobId = jobId,
    dateEpochDay = date.toEpochDay(),
    startMinute = start.hour * 60 + start.minute,
    endMinute = end.hour * 60 + end.minute,
    unpaidBreakMinutes = unpaidBreakMinutes,
    hourlyRateGrosze = hourlyRateGrosze,
    note = note?.takeIf { it.isNotBlank() },
    externalUid = externalUid,
)

fun ShiftWithJobRow.toDomain() = ShiftWithJob(shift = shift.toDomain(), job = job.toDomain())

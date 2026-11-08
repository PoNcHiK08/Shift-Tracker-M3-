package com.vkurylo.shifttracker.data.local

import androidx.room.ColumnInfo
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/*
 * Sync bookkeeping of jobs, shifts and bonuses (DB version 7). Every such row carries:
 *
 * - `sync_id`: a UUID (random, or for a shift imported from a calendar derived from the event, see
 *   [calendarSyncId]), fixed for the life of the record and the same on every device. The local
 *   Long `id` stays the key for relations (job_id), navigation and the UI; only the sync layer uses
 *   `sync_id`.
 * - `updated_at`: wall-clock UTC millis of the row's last change, see [nextUpdatedAt].
 * - `deleted`: a tombstone. A deleted record keeps its row so the deletion can reach other devices;
 *   every read the app shows hides it.
 * - `dirty`: changed here since it was last uploaded. Every local write sets it; only the sync layer
 *   clears it.
 */

/** A row's local id, sync id and change time. */
data class SyncKey(
    val id: Long,
    @ColumnInfo(name = "sync_id") val syncId: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Wall clock for `updated_at`, replaceable in tests. */
fun interface WallClock {
    fun nowMillis(): Long

    companion object {
        val System = WallClock { java.lang.System.currentTimeMillis() }
    }
}

fun newSyncId(): String = UUID.randomUUID().toString()

/**
 * Sync id of a shift imported from a calendar (.ics) event. It is derived from the event instead of being
 * random, so the same event imported on two phones (before they have synced) becomes one record, not two;
 * importing it again, on any phone, is that same record (updated, or undeleted if it was deleted).
 *
 * - With a UID: the UID plus the occurrence's date and start time. Date and time tell apart occurrences
 *   that share a UID (the changed instances of a recurring event); a later end time is still the same
 *   occurrence.
 * - Without a UID: the date, start and end time, the same slot ImportPlanner treats as a duplicate.
 *
 * A name-based UUID (version 3), so it has the same form as every other sync id.
 */
fun calendarSyncId(externalUid: String?, date: LocalDate, start: LocalTime, end: LocalTime): String {
    val name = if (externalUid != null) "ics-uid:$externalUid|$date|$start" else "ics-slot:$date|$start|$end"
    return UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)).toString()
}

/**
 * `updated_at` for a change to a row whose current `updated_at` is [previous] (null: a new row).
 *
 * It never goes backwards: a change always counts as newer than the version it was made from, even if
 * this phone's clock is behind the one that wrote [previous]. SQL writes use the same rule as
 * `MAX(updated_at + 1, :now)`.
 */
fun nextUpdatedAt(previous: Long?, now: Long): Long = if (previous == null) now else maxOf(now, previous + 1)

internal fun JobEntity.stamped(existing: JobEntity?, now: Long) = copy(
    syncId = existing?.syncId ?: syncId,
    updatedAt = nextUpdatedAt(existing?.updatedAt, now),
    deleted = false,
    dirty = true,
)

internal fun ShiftEntity.stamped(existing: ShiftEntity?, now: Long) = copy(
    syncId = existing?.syncId ?: syncId,
    updatedAt = nextUpdatedAt(existing?.updatedAt, now),
    deleted = false,
    dirty = true,
)

internal fun BonusEntity.stamped(existing: BonusEntity?, now: Long) = copy(
    syncId = existing?.syncId ?: syncId,
    updatedAt = nextUpdatedAt(existing?.updatedAt, now),
    deleted = false,
    dirty = true,
)

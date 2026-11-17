package com.vkurylo.shifttracker.data.sync

/*
 * The records as the cloud stores them, under users/{uid}/jobs, /shifts, /bonuses and /payouts, one document per
 * record keyed by its sync id. Independent of the cloud SDK: the Firestore store maps these to and from
 * documents. Money is Long minor units (grosze for PLN), exactly as in Room; never a floating-point number.
 *
 * A deleted record stays as a document with `deleted = true` (a tombstone) and its last content, so the
 * deletion reaches every phone. A shift, bonus or payout refers to its job by the job's sync id.
 */

/** Layout version of the documents this app writes. Documents with a higher one are left alone. */
const val DOC_SCHEMA = 1

/**
 * What this app version reads from the cloud: the collections it syncs (one per record kind) and [DOC_SCHEMA].
 * A phone remembers it with its cursor ([SyncState.format]); when an update changes it, the next sync reads
 * everything once, so records the previous version skipped or didn't know (a new collection) are taken in.
 * Change it together with the collections (FirestoreDocuments.kt) or [DOC_SCHEMA]; a test keeps them in step.
 */
const val SYNC_FORMAT = "jobs,shifts,bonuses,payouts/$DOC_SCHEMA"

sealed interface RemoteRecord {
    val syncId: String

    /** Phone wall-clock millis of the last change; decides conflicts (see [LastWriteWins]). */
    val updatedAt: Long
    val deleted: Boolean

    /** [DOC_SCHEMA] of the app that wrote it. */
    val schema: Int
}

data class RemoteTimedRate(val rateGrosze: Long, val fromMinute: Int, val toMinute: Int)

/** One entry of a job's pay history; [fromMonth] is yyyymm, or 0 for "from the beginning". */
data class RemotePaySettings(val fromMonth: Int, val payType: String, val monthlyAmountMinor: Long, val deductionBp: Int)

data class RemoteJob(
    override val syncId: String,
    override val updatedAt: Long,
    override val deleted: Boolean,
    val name: String,
    val hourlyRateGrosze: Long,
    val color: Int,
    val secondRate: RemoteTimedRate?,
    val currency: String,
    /** The job's pay settings travel inside it, ordered by month. */
    val payHistory: List<RemotePaySettings>,
    override val schema: Int = DOC_SCHEMA,
) : RemoteRecord

data class RemoteShift(
    override val syncId: String,
    override val updatedAt: Long,
    override val deleted: Boolean,
    val jobSyncId: String,
    val dateEpochDay: Long,
    val startMinute: Int,
    val endMinute: Int,
    val unpaidBreakMinutes: Int,
    val hourlyRateGrosze: Long,
    val note: String?,
    val externalUid: String?,
    val secondRate: RemoteTimedRate?,
    override val schema: Int = DOC_SCHEMA,
) : RemoteRecord

data class RemoteBonus(
    override val syncId: String,
    override val updatedAt: Long,
    override val deleted: Boolean,
    val jobSyncId: String,
    val dateEpochDay: Long,
    val amountGrosze: Long,
    val note: String?,
    /**
     * True: belongs to the whole month of [dateEpochDay] (the 1st). Absent in a document written by an app version
     * that doesn't know month bonuses, which reads as false: a bonus on its day.
     */
    val wholeMonth: Boolean = false,
    override val schema: Int = DOC_SCHEMA,
) : RemoteRecord

/**
 * The salary that actually arrived for a job, for the month it was earned for ([month], yyyymm). The app counts it
 * as that job-month's pay when computing totals; nothing about it is stored or synced differently for that.
 */
data class RemotePayout(
    override val syncId: String,
    override val updatedAt: Long,
    override val deleted: Boolean,
    val jobSyncId: String,
    val month: Int,
    val amountMinor: Long,
    val note: String?,
    val receivedEpochDay: Long?,
    override val schema: Int = DOC_SCHEMA,
) : RemoteRecord

/**
 * The document users/{uid} itself. [epoch] is a random marker written when an account's cloud data is
 * first created; "Delete my cloud data" removes it, so a phone still linked to the account notices that the
 * data it synced with is gone (the epoch it remembers no longer exists) and asks instead of re-uploading.
 */
data class CloudMeta(val epoch: String)

/** Records changed in the cloud, and the newest server time among them (null when there were none). */
data class RemoteChanges(
    val jobs: List<RemoteJob> = emptyList(),
    val shifts: List<RemoteShift> = emptyList(),
    val bonuses: List<RemoteBonus> = emptyList(),
    val payouts: List<RemotePayout> = emptyList(),
    val newestServerTime: Long? = null,
)

/** What a push did with one record. */
sealed interface PushResult {
    /** The cloud had no version of it, or an older one: it now has this one. */
    data object Written : PushResult

    /** The cloud already had the same or a newer version, which it kept; here it is. */
    data class Kept(val cloudVersion: RemoteRecord) : PushResult
}

/**
 * The cloud refused to store records although it lets this account read (for Firestore: the security rules said
 * no to the write). Usually the phone's clock running ahead (the rules refuse change times far in the future),
 * or rules older than the app.
 */
class WriteRefusedException(cause: Throwable) : Exception(cause.message, cause)

/** The signed-in account. */
data class CloudAccount(val uid: String, val email: String?)

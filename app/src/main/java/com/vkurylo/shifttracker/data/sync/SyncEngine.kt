package com.vkurylo.shifttracker.data.sync

import androidx.room.withTransaction
import com.vkurylo.shifttracker.data.local.ParentChildRule
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.local.newSyncId
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Live records on one side. */
data class Counts(val jobs: Int, val shifts: Int, val bonuses: Int) {
    val isEmpty: Boolean get() = jobs == 0 && shifts == 0 && bonuses == 0
}

/** What signing in needs. */
sealed interface SignInPlan {
    /** Nothing to ask: complete the sign-in with [choice]. */
    data class Ready(val choice: SignInChoice) : SignInPlan

    /** This phone has never synced, and both it and the account have data: merge, or take the account's? */
    data class AskMerge(val phone: Counts, val cloud: Counts, val lookAlikes: Int) : SignInPlan

    /** This phone's data belongs to another account: replace it with this account's data, or add it? */
    data class AskAccountSwitch(val previousEmail: String?, val phone: Counts, val cloud: Counts) : SignInPlan

    /** The account's cloud data was deleted (from another phone) after this phone synced with it. */
    data object CloudWasReset : SignInPlan
}

enum class SignInChoice {
    /** Already linked to this account: just sync. */
    CONTINUE,

    /** The account has no data: upload this phone's. */
    UPLOAD,

    /** This phone has no data: download the account's. */
    DOWNLOAD,

    /** Keep both: link look-alikes, then merge by sync id (last write wins per record). */
    MERGE,

    /** The account's data replaces this phone's (a safety backup of this phone's data is written first). */
    USE_CLOUD_ONLY,
}

sealed interface SyncResult {
    /**
     * [uploaded] records were written to the cloud (one write each). [limited]: the upload stopped at the write
     * limit it was given, and some changes are still waiting.
     */
    data class Synced(val downloaded: Int, val uploaded: Int, val skipped: Int, val limited: Boolean = false) : SyncResult

    /** No account is linked to this phone's data. */
    data object NotLinked : SyncResult

    /** See [SignInPlan.CloudWasReset]. Nothing was uploaded; the user decides. */
    data object CloudWasReset : SyncResult
}

/**
 * Keeps Room (the only source the app reads) and the cloud copy of one account in step. Nothing in the UI
 * talks to the cloud; this runs in the background.
 *
 * A sync: check the account's cloud data is still the one this phone linked to, download what changed
 * since last time and apply it record by record ([LastWriteWins], then [ParentChildRule]), then upload
 * this phone's changed records, which the cloud stores only where it has nothing newer. The first link of
 * this phone's data to an account never overwrites either side silently: see [prepareSignIn].
 */
@Singleton
class SyncEngine @Inject constructor(
    private val db: ShiftDatabase,
    private val remote: RemoteStore,
    private val state: SyncStateStore,
    private val safetyBackup: SafetyBackup,
    private val clock: WallClock,
    lock: SyncLock = SyncLock(),
) {
    /* Shared with the JSON import: see SyncLock. */
    private val mutex = lock.mutex
    private val jobDao get() = db.jobDao()
    private val shiftDao get() = db.shiftDao()
    private val bonusDao get() = db.bonusDao()
    private val payDao get() = db.paySettingsDao()
    private val syncDao get() = db.syncDao()

    /**
     * Decides what signing in with [account] needs, without changing anything:
     * - linked to this account already: sync (or, if its cloud data was deleted since, ask);
     * - this phone has no data: download;
     * - linked to another account: ask (replace with this account's data, or add this phone's);
     * - never linked: upload when the account has no data, else ask (merge, or take the account's).
     */
    suspend fun prepareSignIn(account: CloudAccount): SignInPlan = mutex.withLock {
        val saved = state.get()
        if (saved.boundUid == account.uid) {
            val meta = remote.readMeta(account.uid)
            return if (meta != null && meta.epoch == saved.epoch) SignInPlan.Ready(SignInChoice.CONTINUE) else SignInPlan.CloudWasReset
        }
        val phone = phoneCounts()
        if (phone.isEmpty) return SignInPlan.Ready(SignInChoice.DOWNLOAD)
        val cloud = if (remote.hasRecords(account.uid)) remote.changesSince(account.uid, null) else RemoteChanges()
        val cloudCounts = Counts(cloud.jobs.count { !it.deleted }, cloud.shifts.count { !it.deleted }, cloud.bonuses.count { !it.deleted })
        return when {
            saved.boundUid != null -> SignInPlan.AskAccountSwitch(saved.boundEmail, phone, cloudCounts)
            cloudCounts.isEmpty -> SignInPlan.Ready(SignInChoice.UPLOAD)
            else -> SignInPlan.AskMerge(phone, cloudCounts, lookAlikes(cloud).size)
        }
    }

    /**
     * Carries out the user's (or [prepareSignIn]'s) [choice], links this phone's data to [account] and syncs
     * ([onWritten] as for [sync]).
     */
    suspend fun completeSignIn(account: CloudAccount, choice: SignInChoice, onWritten: suspend (Int) -> Unit = {}): SyncResult = mutex.withLock {
        when (choice) {
            SignInChoice.CONTINUE, SignInChoice.DOWNLOAD -> Unit
            SignInChoice.UPLOAD -> db.withTransaction { markAllDirty() }
            SignInChoice.MERGE -> {
                val cloud = remote.changesSince(account.uid, null)
                db.withTransaction {
                    linkLookAlikes(lookAlikes(cloud))
                    markAllDirty()
                }
            }
            SignInChoice.USE_CLOUD_ONLY -> {
                safetyBackup.save("before-taking-cloud-data")
                db.withTransaction { deleteEverything() }
            }
        }
        if (choice == SignInChoice.CONTINUE) {
            state.update { it.copy(boundEmail = account.email ?: it.boundEmail) }
        } else {
            val meta = remote.ensureMeta(account.uid, CloudMeta(epoch = newSyncId()))
            state.update { SyncState(boundUid = account.uid, boundEmail = account.email, epoch = meta.epoch, lastSyncedAt = it.lastSyncedAt) }
        }
        syncLocked(Int.MAX_VALUE, onWritten)
    }

    /**
     * Downloads, then uploads, what changed. Throws when the cloud can't be reached; nothing is lost then.
     *
     * At most [maxWrites] records are uploaded (the rest stay waiting, and the result says [SyncResult.Synced.limited]);
     * [onWritten] hears of every batch the cloud stored, as it happens, so writes are counted even if the sync
     * fails half-way.
     */
    suspend fun sync(maxWrites: Int = Int.MAX_VALUE, onWritten: suspend (Int) -> Unit = {}): SyncResult =
        mutex.withLock { syncLocked(maxWrites, onWritten) }

    /**
     * Deletes the account's records and its marker from the cloud, and unlinks this phone if it was linked to
     * that account (a phone linked to another account stays linked to it). This phone's data stays. Other
     * phones still linked to the account see [SyncResult.CloudWasReset] on their next sync. Returns the number
     * of records deleted.
     */
    suspend fun deleteCloudData(account: CloudAccount): Int = mutex.withLock {
        val deleted = remote.deleteAll(account.uid)
        state.update { if (it.boundUid == account.uid) SyncState(lastSyncedAt = it.lastSyncedAt) else it }
        deleted
    }

    /** Unlinks this phone's data from its account (it stays on the phone); the next sign-in starts afresh. */
    suspend fun unlink(): Unit = mutex.withLock {
        state.update { SyncState(lastSyncedAt = it.lastSyncedAt) }
    }

    // --- Sync -----------------------------------------------------------------------------------------

    private suspend fun syncLocked(maxWrites: Int, onWritten: suspend (Int) -> Unit): SyncResult {
        val saved = state.get()
        val uid = saved.boundUid ?: return SyncResult.NotLinked
        val meta = remote.readMeta(uid)
        if (meta == null || meta.epoch != saved.epoch) return SyncResult.CloudWasReset

        val changes = remote.changesSince(uid, saved.cursor?.let { it - PULL_OVERLAP_MS })
        val download = apply(uid, changes)
        changes.newestServerTime?.let { newest -> state.update { it.copy(cursor = maxOf(it.cursor ?: newest, newest)) } }

        var uploaded = 0
        var skipped = download.skipped
        var limited = false
        for (round in 1..MAX_UPLOAD_ROUNDS) {
            val upload = uploadOnce(uid, maxWrites - uploaded, onWritten)
            uploaded += upload.written
            skipped += upload.skipped
            if (upload.limited) {
                limited = true
                break
            }
            // Applying the cloud's versions (or ParentChildRule) can leave records to upload: another round.
            if (upload.sent == 0 || upload.written == 0) break
        }
        state.update { it.copy(lastSyncedAt = clock.nowMillis()) }
        return SyncResult.Synced(download.applied, uploaded, skipped, limited)
    }

    private class Download(val applied: Int, val skipped: Int)

    private suspend fun apply(uid: String, changes: RemoteChanges): Download {
        val jobs = changes.jobs.filter { it.isUsable() }
        val shifts = changes.shifts.filter { it.isUsable() }
        val bonuses = changes.bonuses.filter { it.isUsable() }
        var skipped = (changes.jobs.size - jobs.size) + (changes.shifts.size - shifts.size) + (changes.bonuses.size - bonuses.size)

        // A shift or bonus can arrive without its job (the job changed before the last download, but this
        // phone doesn't have it): fetch those jobs first.
        val inBatch = jobs.mapTo(HashSet()) { it.syncId }
        val missing = (shifts.map { it.jobSyncId } + bonuses.map { it.jobSyncId })
            .filterTo(HashSet()) { it !in inBatch && jobDao.getBySyncId(it) == null }
        val parents = if (missing.isEmpty()) emptyList() else remote.jobs(uid, missing).filter { it.isUsable() }

        var applied = 0
        db.withTransaction {
            for (record in parents + jobs + shifts + bonuses) {
                when (applyRecord(record)) {
                    Applied.CHANGED -> applied++
                    Applied.UNCHANGED -> Unit
                    Applied.NO_JOB -> skipped++
                }
            }
            ParentChildRule.enforce(jobDao)
        }
        return Download(applied, skipped)
    }

    private class Upload(val sent: Int, val written: Int, val skipped: Int, val limited: Boolean)

    private suspend fun uploadOnce(uid: String, writesLeft: Int, onWritten: suspend (Int) -> Unit): Upload {
        val dirty: List<RemoteRecord> = db.withTransaction {
            // Jobs first, so the cloud never has a shift or bonus without its job for long.
            syncDao.dirtyJobs().map { it.toRemote(payDao.forJob(it.id)) } +
                syncDao.dirtyShifts().map { it.shift.toRemote(it.jobSyncId) } +
                syncDao.dirtyBonuses().map { it.bonus.toRemote(it.jobSyncId) }
        }
        if (dirty.isEmpty()) return Upload(0, 0, 0, limited = false)
        // Each record sent can be one write: never send more than may still be written.
        val records = dirty.take(writesLeft.coerceAtLeast(0))
        if (records.isEmpty()) return Upload(0, 0, 0, limited = true)

        val results = records.chunked(UPLOAD_CHUNK).flatMap { chunk ->
            remote.push(uid, chunk).also { pushed -> onWritten(pushed.count { it == PushResult.Written }) }
        }

        var written = 0
        var skipped = 0
        db.withTransaction {
            for ((sent, result) in records.zip(results)) {
                when (result) {
                    PushResult.Written -> {
                        markUploaded(sent)
                        written++
                    }
                    // The cloud has the same or a newer version: take it (if it's one this app can use).
                    is PushResult.Kept ->
                        if (!result.cloudVersion.isUsable() || applyRecord(result.cloudVersion) == Applied.NO_JOB) skipped++
                }
            }
            ParentChildRule.enforce(jobDao)
        }
        return Upload(records.size, written, skipped, limited = records.size < dirty.size)
    }

    private enum class Applied { CHANGED, UNCHANGED, NO_JOB }

    /** Applies the cloud's version of a record by [LastWriteWins]. Call inside a transaction. */
    private suspend fun applyRecord(record: RemoteRecord): Applied = when (record) {
        is RemoteJob -> applyJob(record)
        is RemoteShift -> applyShift(record)
        is RemoteBonus -> applyBonus(record)
    }

    private suspend fun applyJob(cloud: RemoteJob): Applied {
        val local = jobDao.getBySyncId(cloud.syncId)
        if (local == null) {
            val id = jobDao.upsert(cloud.toEntity(id = 0))
            payDao.insertAll(cloud.payEntities(id))
            return Applied.CHANGED
        }
        val mine = local.toRemote(payDao.forJob(local.id))
        return when (LastWriteWins.winner(local.updatedAt, cloud.updatedAt, mine.sameContentAs(cloud))) {
            LastWriteWins.Winner.REMOTE -> {
                jobDao.upsert(cloud.toEntity(local.id))
                payDao.deleteForJob(local.id)
                payDao.insertAll(cloud.payEntities(local.id))
                Applied.CHANGED
            }
            LastWriteWins.Winner.LOCAL -> {
                if (!local.dirty) syncDao.markJobDirty(local.syncId)
                Applied.UNCHANGED
            }
            LastWriteWins.Winner.SAME -> {
                if (local.dirty) syncDao.markJobUploaded(local.syncId, local.updatedAt)
                Applied.UNCHANGED
            }
        }
    }

    private suspend fun applyShift(cloud: RemoteShift): Applied {
        val jobId = jobDao.getBySyncId(cloud.jobSyncId)?.id ?: return Applied.NO_JOB
        val local = shiftDao.getBySyncId(cloud.syncId)
        if (local == null) {
            shiftDao.upsert(cloud.toEntity(id = 0, jobId = jobId))
            return Applied.CHANGED
        }
        val mine = local.toRemote(jobDao.getAny(local.jobId)!!.syncId)
        return when (LastWriteWins.winner(local.updatedAt, cloud.updatedAt, mine.sameContentAs(cloud))) {
            LastWriteWins.Winner.REMOTE -> {
                shiftDao.upsert(cloud.toEntity(local.id, jobId))
                Applied.CHANGED
            }
            LastWriteWins.Winner.LOCAL -> {
                if (!local.dirty) syncDao.markShiftDirty(local.syncId)
                Applied.UNCHANGED
            }
            LastWriteWins.Winner.SAME -> {
                if (local.dirty) syncDao.markShiftUploaded(local.syncId, local.updatedAt)
                Applied.UNCHANGED
            }
        }
    }

    private suspend fun applyBonus(cloud: RemoteBonus): Applied {
        val jobId = jobDao.getBySyncId(cloud.jobSyncId)?.id ?: return Applied.NO_JOB
        val local = bonusDao.getBySyncId(cloud.syncId)
        if (local == null) {
            bonusDao.upsert(cloud.toEntity(id = 0, jobId = jobId))
            return Applied.CHANGED
        }
        val mine = local.toRemote(jobDao.getAny(local.jobId)!!.syncId)
        return when (LastWriteWins.winner(local.updatedAt, cloud.updatedAt, mine.sameContentAs(cloud))) {
            LastWriteWins.Winner.REMOTE -> {
                bonusDao.upsert(cloud.toEntity(local.id, jobId))
                Applied.CHANGED
            }
            LastWriteWins.Winner.LOCAL -> {
                if (!local.dirty) syncDao.markBonusDirty(local.syncId)
                Applied.UNCHANGED
            }
            LastWriteWins.Winner.SAME -> {
                if (local.dirty) syncDao.markBonusUploaded(local.syncId, local.updatedAt)
                Applied.UNCHANGED
            }
        }
    }

    /** Uploaded: unless the row changed again meanwhile (then it has a later updated_at and stays dirty). */
    private suspend fun markUploaded(record: RemoteRecord) {
        when (record) {
            is RemoteJob -> syncDao.markJobUploaded(record.syncId, record.updatedAt)
            is RemoteShift -> syncDao.markShiftUploaded(record.syncId, record.updatedAt)
            is RemoteBonus -> syncDao.markBonusUploaded(record.syncId, record.updatedAt)
        }
    }

    // --- Linking this phone's data to an account ------------------------------------------------------

    private suspend fun phoneCounts() = Counts(jobDao.getAll().size, shiftDao.getAll().size, bonusDao.getAll().size)

    private suspend fun lookAlikes(cloud: RemoteChanges): LookAlikes.Links {
        val jobs = jobDao.getAll().map { it.toRemote(payDao.forJob(it.id)) }
        val shifts = syncDao.liveShifts().map { it.shift.toRemote(it.jobSyncId) }
        val bonuses = syncDao.liveBonuses().map { it.bonus.toRemote(it.jobSyncId) }
        return LookAlikes.find(jobs, shifts, bonuses, cloud)
    }

    /** Gives each local look-alike its cloud twin's sync id (unless some local row already has that id). */
    private suspend fun linkLookAlikes(links: LookAlikes.Links) {
        for ((local, cloud) in links.jobs) {
            if (syncDao.jobSyncIdCount(cloud) == 0) jobDao.getBySyncId(local)?.let { syncDao.setJobSyncId(it.id, cloud) }
        }
        for ((local, cloud) in links.shifts) {
            if (syncDao.shiftSyncIdCount(cloud) == 0) shiftDao.getBySyncId(local)?.let { syncDao.setShiftSyncId(it.id, cloud) }
        }
        for ((local, cloud) in links.bonuses) {
            if (syncDao.bonusSyncIdCount(cloud) == 0) bonusDao.getBySyncId(local)?.let { syncDao.setBonusSyncId(it.id, cloud) }
        }
    }

    private suspend fun markAllDirty() {
        syncDao.markAllJobsDirty()
        syncDao.markAllShiftsDirty()
        syncDao.markAllBonusesDirty()
    }

    private suspend fun deleteEverything() {
        syncDao.deleteAllBonuses()
        syncDao.deleteAllShifts()
        syncDao.deleteAllPaySettings()
        syncDao.deleteAllJobs()
    }

    companion object {
        /**
         * Each download asks for changes from a little before the newest server time already taken in, in
         * case a write with an earlier server time became visible late. Records seen twice are the same
         * version and change nothing.
         */
        const val PULL_OVERLAP_MS = 60_000L

        /** Records per cloud write (one atomic check-and-write; Firestore allows 500 writes per transaction). */
        const val UPLOAD_CHUNK = 200

        const val MAX_UPLOAD_ROUNDS = 3
    }
}

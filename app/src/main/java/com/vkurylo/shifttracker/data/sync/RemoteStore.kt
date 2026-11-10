package com.vkurylo.shifttracker.data.sync

/**
 * The cloud as the sync engine sees it: one account's records under users/{uid}. Every call goes to the
 * server (no offline cache), so a failure means nothing was confirmed; it throws, and the caller retries.
 */
interface RemoteStore {
    suspend fun readMeta(uid: String): CloudMeta?

    /**
     * The account's [CloudMeta]; when it has none, stores [proposed] and returns it. Atomic, so two phones
     * linking at the same moment end up with the same one.
     */
    suspend fun ensureMeta(uid: String, proposed: CloudMeta): CloudMeta

    /** Whether the account has any record, deleted ones included. */
    suspend fun hasRecords(uid: String): Boolean

    /**
     * Records whose server time (set by the server when it stored them) is at or after [since], or all of
     * them when [since] is null. Server times only find what changed; they never decide a conflict.
     */
    suspend fun changesSince(uid: String, since: Long?): RemoteChanges

    /** The jobs with these sync ids (those that exist). */
    suspend fun jobs(uid: String, syncIds: Collection<String>): List<RemoteJob>

    /**
     * Stores each record unless the cloud already has a version of it with the same or a later
     * `updatedAt`, checking and writing atomically (a version stored meanwhile by another phone is never
     * overwritten by an older one). Results are in the order of [records]. Throws [WriteRefusedException] when
     * the cloud refuses the write itself.
     */
    suspend fun push(uid: String, records: List<RemoteRecord>): List<PushResult>

    /** Removes every record of the account and its [CloudMeta]. */
    suspend fun deleteAll(uid: String)
}

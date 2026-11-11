package com.vkurylo.shifttracker.data.sync

import java.io.IOException

/**
 * The cloud in memory, shared by the test's phones. Behaves like the Firestore store: the server stamps
 * every stored record with its own increasing time, and each push checks and writes atomically.
 */
class FakeRemoteStore : RemoteStore {

    private class Stored(val record: RemoteRecord, val serverTime: Long)

    private class Account {
        val records = LinkedHashMap<String, Stored>()
        var meta: CloudMeta? = null
    }

    private val accounts = HashMap<String, Account>()

    /** The server's clock: milliseconds, one second per write, far from the phones' clocks on purpose. */
    var serverTime = 50_000_000L
        private set

    /** False: every call fails as without a network. */
    var reachable = true

    /** Runs at the start of the next pushes (while an upload is on its way); set it back to null inside to run once. */
    var duringPush: (suspend () -> Unit)? = null

    /** True: pushes are refused as Firestore's rules refuse them (reads still work). */
    var refuseWrites = false

    /** Every call, by name, in order: to check what a sync did (or didn't) ask the cloud. */
    val calls = mutableListOf<String>()

    /** Calls made on the main thread (the app must make none: the UI keeps working while it syncs). */
    var callsOnMainThread = 0
        private set

    /** Records stored by pushes (each one write). */
    var writes = 0
        private set

    private fun account(uid: String) = accounts.getOrPut(uid) { Account() }
    private fun key(record: RemoteRecord) = "${record::class.simpleName}:${record.syncId}"
    private fun online(call: String = "") {
        calls += call
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) callsOnMainThread++
        if (!reachable) throw IOException("offline")
    }

    override suspend fun readMeta(uid: String): CloudMeta? {
        online("readMeta")
        return accounts[uid]?.meta
    }

    override suspend fun ensureMeta(uid: String, proposed: CloudMeta): CloudMeta {
        online()
        val account = account(uid)
        return account.meta ?: proposed.also { account.meta = it }
    }

    override suspend fun hasRecords(uid: String): Boolean {
        online()
        return accounts[uid]?.records?.isNotEmpty() == true
    }

    override suspend fun changesSince(uid: String, since: Long?): RemoteChanges {
        online()
        val stored = accounts[uid]?.records?.values.orEmpty().filter { since == null || it.serverTime >= since }
        val records = stored.map { it.record }
        return RemoteChanges(
            jobs = records.filterIsInstance<RemoteJob>(),
            shifts = records.filterIsInstance<RemoteShift>(),
            bonuses = records.filterIsInstance<RemoteBonus>(),
            payouts = records.filterIsInstance<RemotePayout>(),
            newestServerTime = stored.maxOfOrNull { it.serverTime },
        )
    }

    override suspend fun jobs(uid: String, syncIds: Collection<String>): List<RemoteJob> {
        online()
        return records(uid).filterIsInstance<RemoteJob>().filter { it.syncId in syncIds }
    }

    override suspend fun push(uid: String, records: List<RemoteRecord>): List<PushResult> {
        online("push")
        // firestore.rules refuses the whole write when one text is too long.
        if (refuseWrites || records.any { it.longestText() > 20_000 }) {
            throw WriteRefusedException(IllegalStateException("PERMISSION_DENIED: Missing or insufficient permissions."))
        }
        duringPush?.invoke()
        val account = account(uid)
        return records.map { record ->
            val existing = account.records[key(record)]
            if (existing == null || existing.record.updatedAt < record.updatedAt) {
                account.records[key(record)] = Stored(record, nextServerTime())
                writes++
                PushResult.Written
            } else {
                PushResult.Kept(existing.record)
            }
        }
    }

    override suspend fun deleteAll(uid: String): Int {
        online("deleteAll")
        return accounts.remove(uid)?.records?.size ?: 0
    }

    private fun RemoteRecord.longestText(): Int = when (this) {
        is RemoteJob -> name.length
        is RemoteShift -> maxOf(note?.length ?: 0, externalUid?.length ?: 0)
        is RemoteBonus -> note?.length ?: 0
        is RemotePayout -> note?.length ?: 0
    }

    // --- For tests ---

    fun records(uid: String): List<RemoteRecord> = accounts[uid]?.records?.values?.map { it.record }.orEmpty()

    fun meta(uid: String): CloudMeta? = accounts[uid]?.meta

    /** Stores [record] as another app (or app version) would, without any check. */
    fun put(uid: String, record: RemoteRecord) {
        account(uid).records[key(record)] = Stored(record, nextServerTime())
    }

    private fun nextServerTime(): Long {
        serverTime += 1000
        return serverTime
    }
}

class InMemorySyncStateStore : SyncStateStore {
    var current = SyncState()

    override suspend fun get() = current

    override suspend fun update(transform: (SyncState) -> SyncState) {
        current = transform(current)
    }
}

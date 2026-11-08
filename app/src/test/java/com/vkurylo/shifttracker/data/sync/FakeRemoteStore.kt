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

    private fun account(uid: String) = accounts.getOrPut(uid) { Account() }
    private fun key(record: RemoteRecord) = "${record::class.simpleName}:${record.syncId}"
    private fun online() {
        if (!reachable) throw IOException("offline")
    }

    override suspend fun readMeta(uid: String): CloudMeta? {
        online()
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
            newestServerTime = stored.maxOfOrNull { it.serverTime },
        )
    }

    override suspend fun jobs(uid: String, syncIds: Collection<String>): List<RemoteJob> {
        online()
        return records(uid).filterIsInstance<RemoteJob>().filter { it.syncId in syncIds }
    }

    override suspend fun push(uid: String, records: List<RemoteRecord>): List<PushResult> {
        online()
        duringPush?.invoke()
        val account = account(uid)
        return records.map { record ->
            val existing = account.records[key(record)]
            if (existing == null || existing.record.updatedAt < record.updatedAt) {
                account.records[key(record)] = Stored(record, nextServerTime())
                PushResult.Written
            } else {
                PushResult.Kept(existing.record)
            }
        }
    }

    override suspend fun deleteAll(uid: String) {
        online()
        accounts.remove(uid)
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

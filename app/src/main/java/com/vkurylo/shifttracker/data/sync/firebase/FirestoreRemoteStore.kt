package com.vkurylo.shifttracker.data.sync.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.vkurylo.shifttracker.data.sync.CloudMeta
import com.vkurylo.shifttracker.data.sync.PushResult
import com.vkurylo.shifttracker.data.sync.RemoteBonus
import com.vkurylo.shifttracker.data.sync.RemoteChanges
import com.vkurylo.shifttracker.data.sync.RemoteJob
import com.vkurylo.shifttracker.data.sync.RemotePayout
import com.vkurylo.shifttracker.data.sync.RemoteRecord
import com.vkurylo.shifttracker.data.sync.RemoteShift
import com.vkurylo.shifttracker.data.sync.RemoteStore
import com.vkurylo.shifttracker.data.sync.WriteRefusedException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RemoteStore] on Cloud Firestore (layout: FirestoreDocuments.kt; access and validation: firestore.rules).
 *
 * Everything goes to the server and nothing is queued for later: reads use [Source.SERVER], writes are
 * transactions, and both fail when the phone is offline. A write the caller wasn't told succeeded may be
 * repeated; it is the same version then, and changes nothing.
 */
@Singleton
class FirestoreRemoteStore internal constructor(private val firestore: () -> FirebaseFirestore) : RemoteStore {

    @Inject constructor(firebase: FirebaseServices) : this({ firebase.firestore })

    private val db get() = firestore()

    private fun user(uid: String) = db.collection(USERS).document(uid)

    private fun collection(uid: String, collection: CloudCollection) = user(uid).collection(collection.path)

    override suspend fun readMeta(uid: String): CloudMeta? = user(uid).get(Source.SERVER).await().toMeta()

    override suspend fun ensureMeta(uid: String, proposed: CloudMeta): CloudMeta {
        val ref = user(uid)
        return db.runTransaction { transaction ->
            transaction.get(ref).toMeta() ?: proposed.also {
                transaction.set(ref, mapOf(Fields.EPOCH to it.epoch, Fields.CREATED_AT to FieldValue.serverTimestamp()))
            }
        }.await()
    }

    override suspend fun hasRecords(uid: String): Boolean =
        CloudCollection.entries.any { !collection(uid, it).limit(1).get(Source.SERVER).await().isEmpty }

    override suspend fun changesSince(uid: String, since: Long?): RemoteChanges = coroutineScope {
        val changed = CloudCollection.entries
            .map { collection -> async { changedIn(uid, collection, since) } }
            .awaitAll()
            .flatten()
        val records = changed.map { it.first }
        RemoteChanges(
            jobs = records.filterIsInstance<RemoteJob>(),
            shifts = records.filterIsInstance<RemoteShift>(),
            bonuses = records.filterIsInstance<RemoteBonus>(),
            payouts = records.filterIsInstance<RemotePayout>(),
            newestServerTime = changed.mapNotNull { it.second }.maxOrNull(),
        )
    }

    /** Documents with a server time at or after [since] (all, when null), with that time; page by page. */
    private suspend fun changedIn(uid: String, collection: CloudCollection, since: Long?): List<Pair<RemoteRecord, Long?>> {
        var query: Query = collection(uid, collection).orderBy(Fields.SERVER_UPDATED_AT)
        if (since != null) query = query.whereGreaterThanOrEqualTo(Fields.SERVER_UPDATED_AT, timestamp(since))
        val found = mutableListOf<Pair<RemoteRecord, Long?>>()
        var last: DocumentSnapshot? = null
        do {
            val page = (last?.let { query.startAfter(it) } ?: query).limit(PAGE_SIZE.toLong()).get(Source.SERVER).await()
            page.documents.mapTo(found) { it.toRecord(collection) to it.serverTime() }
            last = page.documents.lastOrNull()
        } while (page.size() == PAGE_SIZE)
        return found
    }

    override suspend fun jobs(uid: String, syncIds: Collection<String>): List<RemoteJob> =
        current(uid, CloudCollection.JOBS, syncIds).filterIsInstance<RemoteJob>()

    /**
     * Reads the cloud's versions of [records], keeps those that are the same or newer, and writes the rest in one
     * transaction. firestore.rules lets a write replace a document only with a later `updatedAt`, so a version
     * another phone stored after the read can't be overwritten: the whole transaction is refused instead, and is
     * tried once more from a fresh read.
     */
    override suspend fun push(uid: String, records: List<RemoteRecord>): List<PushResult> =
        records.chunked(MAX_WRITES).flatMap { chunk ->
            try {
                pushOnce(uid, chunk)
            } catch (e: WriteDenied) {
                try {
                    pushOnce(uid, chunk)
                } catch (again: WriteDenied) {
                    // Refused twice, from a fresh read: not a version stored meanwhile, the rules refuse it.
                    throw WriteRefusedException(again.cause!!)
                }
            }
        }

    /** The rules refused the transaction's writes (the reads before it were allowed). */
    private class WriteDenied(cause: FirebaseFirestoreException) : Exception(cause)

    private suspend fun pushOnce(uid: String, records: List<RemoteRecord>): List<PushResult> {
        val cloud = coroutineScope {
            records.groupBy({ it.collection }, { it.syncId })
                .map { (collection, ids) -> async { current(uid, collection, ids) } }
                .awaitAll()
                .flatten()
                .associateBy { it.collection to it.syncId }
        }
        val results = records.map { record ->
            val existing = cloud[record.collection to record.syncId]
            if (existing != null && existing.updatedAt >= record.updatedAt) PushResult.Kept(existing) else PushResult.Written
        }
        val writes = records.filterIndexed { index, _ -> results[index] == PushResult.Written }
        if (writes.isNotEmpty()) {
            try {
                db.runTransaction { transaction ->
                    for (record in writes) {
                        val fields = record.toFields() + (Fields.SERVER_UPDATED_AT to FieldValue.serverTimestamp())
                        // Merge: fields a newer app version added to the document stay.
                        transaction.set(collection(uid, record.collection).document(record.syncId), fields, SetOptions.merge())
                    }
                    null
                }.await()
            } catch (e: FirebaseFirestoreException) {
                if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) throw WriteDenied(e)
                throw e
            }
        }
        return results
    }

    /** The documents with these ids that exist. */
    private suspend fun current(uid: String, collection: CloudCollection, ids: Collection<String>): List<RemoteRecord> =
        coroutineScope {
            ids.distinct().chunked(IN_QUERY_LIMIT).map { chunk ->
                async {
                    collection(uid, collection).whereIn(FieldPath.documentId(), chunk).get(Source.SERVER).await()
                        .documents.map { it.toRecord(collection) }
                }
            }.awaitAll().flatten()
        }

    override suspend fun deleteAll(uid: String): Int {
        // The marker goes first: a phone that syncs from now on finds it gone and stops (it asks the user)
        // instead of uploading into data that is being deleted. If this fails half-way, deleting again finishes.
        db.runTransaction { transaction ->
            transaction.delete(user(uid))
            null
        }.await()
        var deleted = 0
        for (collection in CloudCollection.entries) {
            while (true) {
                val page = collection(uid, collection).limit(MAX_WRITES.toLong()).get(Source.SERVER).await()
                if (page.isEmpty) break
                db.runTransaction { transaction ->
                    page.documents.forEach { transaction.delete(it.reference) }
                    null
                }.await()
                deleted += page.size()
            }
        }
        return deleted
    }

    private fun DocumentSnapshot.toMeta(): CloudMeta? = if (exists()) (get(Fields.EPOCH) as? String)?.let(::CloudMeta) else null

    private fun DocumentSnapshot.toRecord(collection: CloudCollection): RemoteRecord = readRecord(collection, id, data.orEmpty())

    private fun DocumentSnapshot.serverTime(): Long? =
        (get(Fields.SERVER_UPDATED_AT) as? Timestamp)?.let { it.seconds * 1_000 + it.nanoseconds / 1_000_000 }

    private fun timestamp(millis: Long) = Timestamp(Math.floorDiv(millis, 1_000L), (Math.floorMod(millis, 1_000L) * 1_000_000).toInt())

    private companion object {
        const val USERS = "users"

        const val PAGE_SIZE = 500

        /** Writes per transaction (Firestore allows 500). */
        const val MAX_WRITES = 250

        /** Values in one `in` query (Firestore allows 30). */
        const val IN_QUERY_LIMIT = 30
    }
}

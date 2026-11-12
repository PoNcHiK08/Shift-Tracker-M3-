package com.vkurylo.shifttracker.data.sync

/**
 * The conflict rule of sync: **last write wins, per record, by `updated_at`.** When this phone and the
 * cloud have different versions of the same record (same sync id), the one with the later `updated_at`
 * is kept everywhere and the other is dropped. Records don't merge field by field.
 *
 * - **Ties** (same `updated_at`, different content, which needs two phones writing in the same
 *   millisecond): the cloud's version wins, on every phone, so they all end up with the same one.
 * - **Deletions** are versions too: a tombstone newer than an edit deletes the record, an edit newer than
 *   a tombstone brings it back. Between a job and its entries, [com.vkurylo.shifttracker.data.local.ParentChildRule]
 *   decides what follows from that.
 *
 * **Which clock, and the clock-skew risk.** `updated_at` is the phone's own wall clock (UTC millis) at the
 * moment of the change, not a server timestamp. With server timestamps, an edit made offline at 10:00 and
 * uploaded at 12:00 would beat an edit made online at 11:00: whoever reconnects last would win. The price
 * is that phones must roughly agree on the time. If one phone's clock runs ahead, its edit of a record
 * wins over a concurrent edit of the same record made on another phone slightly later in real time.
 * This is bounded:
 * 1. A change is never older than the version it was made from
 *    ([com.vkurylo.shifttracker.data.local.nextUpdatedAt]), so editing what you see always wins,
 *    whatever the clocks say. Skew matters only for truly concurrent edits of the same record.
 * 2. The Firestore rules refuse an `updated_at` more than a few minutes ahead of the server's time, so a
 *    badly wrong clock can't make a record unbeatable; that phone's upload fails with an error instead.
 * 3. Phones normally set their clock from the network.
 *
 * The server's timestamp (`serverUpdatedAt`) is used only to find what changed since the last sync,
 * never to pick a winner.
 */
object LastWriteWins {

    enum class Winner {
        /** This phone's version is later: it stays and goes to the cloud. */
        LOCAL,

        /** The cloud's version is later (or a tie with different content): it replaces this phone's. */
        REMOTE,

        /** Both are the same version: nothing to change. */
        SAME,
    }

    fun winner(localUpdatedAt: Long, remoteUpdatedAt: Long, sameContent: Boolean): Winner = when {
        localUpdatedAt == remoteUpdatedAt && sameContent -> Winner.SAME
        localUpdatedAt > remoteUpdatedAt -> Winner.LOCAL
        else -> Winner.REMOTE
    }
}

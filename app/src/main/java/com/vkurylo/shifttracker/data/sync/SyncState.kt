package com.vkurylo.shifttracker.data.sync

/**
 * What this phone remembers about sync. It belongs to this phone only: it must not travel with an Android
 * backup of the database to another phone (the store keeps it out of backups), or that phone would think
 * it is already linked and up to date.
 */
data class SyncState(
    /** The account this phone's data is linked to; null: never linked, or unlinked. Kept on sign-out. */
    val boundUid: String? = null,
    /** That account's email, to name it when another account signs in. */
    val boundEmail: String? = null,
    /** The account's [CloudMeta.epoch] when it was linked. */
    val epoch: String? = null,
    /** Server time of the newest cloud change taken in; null: take everything. */
    val cursor: Long? = null,
    /** Wall-clock millis of the last complete sync. */
    val lastSyncedAt: Long? = null,
    /** The [SYNC_FORMAT] [cursor] was reached with; null: none yet (never synced, or an app version before it). */
    val format: String? = null,
)

interface SyncStateStore {
    suspend fun get(): SyncState

    suspend fun update(transform: (SyncState) -> SyncState)
}

/**
 * Writes a JSON export of everything this phone holds before its data is replaced (sync taking an account's
 * data, a JSON import). Writes nothing when the phone holds no data.
 */
fun interface SafetyBackup {
    suspend fun save(reason: String)

    /** Why a safety backup was written (part of its file name). */
    object Reason {
        const val BEFORE_TAKING_CLOUD_DATA = "before-taking-cloud-data"
        const val BEFORE_IMPORT = "before-import"
        const val BEFORE_RATE_CHANGE = "before-rate-change"
    }
}

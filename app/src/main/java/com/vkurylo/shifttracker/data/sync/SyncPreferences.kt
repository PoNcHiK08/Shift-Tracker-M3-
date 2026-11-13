package com.vkurylo.shifttracker.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Why the last sync attempt didn't complete (null: it did). */
enum class SyncProblem {
    /** No connection to the cloud; it is tried again when the phone is online. */
    OFFLINE,

    /** firestore.rules refused this account (the email in the rules, or the rules not published). */
    ACCESS_DENIED,

    /** The cloud refused the upload, and the phone's clock (or a change time) is ahead of the server's. */
    CLOCK,

    /** The cloud refused the upload, and the phone's clock couldn't be checked against the server's. */
    WRITE_REFUSED_CLOCK_UNKNOWN,

    /** The cloud refused the upload although the clock is right: rules older than the app. */
    WRITE_REFUSED,

    /** This account's cloud data was deleted (from another phone): the user decides what happens next. */
    CLOUD_RESET,

    /** Automatic sync stopped for today: the daily write limit was reached. */
    BUDGET,

    /** Anything else; [SyncOutcome.detail] says what. */
    OTHER,
}

/** The last sync attempt. */
data class SyncOutcome(
    val at: Long,
    val downloaded: Int = 0,
    /** Records written to the cloud (one write each). */
    val uploaded: Int = 0,
    val skipped: Int = 0,
    /** The upload stopped at the write limit; changes are still waiting. */
    val limited: Boolean = false,
    val problem: SyncProblem? = null,
    /** For [SyncProblem.CLOCK], minutes ahead; for [SyncProblem.OTHER], the error. */
    val detail: String? = null,
)

/** Sync's own settings on this phone (the engine's link and cursor are [SyncState]). */
data class SyncSettings(
    /** The user turned sync on (it is off until they do, also after signing in, and after signing out). */
    val enabled: Boolean = false,
    /** The account's cloud data was deleted after this phone synced with it: nothing runs until the user decides. */
    val cloudReset: Boolean = false,
    /** Local day ([java.time.LocalDate.toEpochDay]) that [writesToday] counts. */
    val writesDay: Long = 0,
    /** Cloud writes made by this phone on [writesDay]. */
    val writesToday: Int = 0,
    val last: SyncOutcome? = null,
)

/**
 * What sync remembers on this phone, in its own DataStore file (datastore/sync.preferences_pb). The backup rules
 * leave that file out: restored onto another phone, it would claim a link and a position in the cloud that the
 * restored database doesn't match. A restored phone links afresh instead (merging by sync id, so nothing doubles).
 */
@Singleton
class SyncPreferences internal constructor(private val store: DataStore<Preferences>) : SyncStateStore {

    @Inject constructor(@ApplicationContext context: Context) :
        this(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(FILE) })

    // --- SyncStateStore (the engine's) ---------------------------------------------------------------

    val state: Flow<SyncState> = store.data.map { it.toState() }

    override suspend fun get(): SyncState = state.first()

    override suspend fun update(transform: (SyncState) -> SyncState) {
        store.edit { prefs -> prefs.write(transform(prefs.toState())) }
    }

    private fun Preferences.toState() = SyncState(
        boundUid = this[BOUND_UID],
        boundEmail = this[BOUND_EMAIL],
        epoch = this[EPOCH],
        cursor = this[CURSOR],
        lastSyncedAt = this[LAST_SYNCED_AT],
        format = this[FORMAT],
    )

    private fun MutablePreferences.write(state: SyncState) {
        set(BOUND_UID, state.boundUid)
        set(BOUND_EMAIL, state.boundEmail)
        set(EPOCH, state.epoch)
        set(CURSOR, state.cursor)
        set(LAST_SYNCED_AT, state.lastSyncedAt)
        set(FORMAT, state.format)
    }

    // --- SyncSettings ----------------------------------------------------------------------------------

    val settings: Flow<SyncSettings> = store.data.map { it.toSettings() }

    suspend fun currentSettings(): SyncSettings = settings.first()

    suspend fun updateSettings(transform: (SyncSettings) -> SyncSettings) {
        store.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    private fun Preferences.toSettings() = SyncSettings(
        enabled = this[ENABLED] ?: false,
        cloudReset = this[CLOUD_RESET] ?: false,
        writesDay = this[WRITES_DAY] ?: 0,
        writesToday = this[WRITES_TODAY] ?: 0,
        last = this[LAST_AT]?.let { at ->
            SyncOutcome(
                at = at,
                downloaded = this[LAST_DOWNLOADED] ?: 0,
                uploaded = this[LAST_UPLOADED] ?: 0,
                skipped = this[LAST_SKIPPED] ?: 0,
                limited = this[LAST_LIMITED] ?: false,
                problem = this[LAST_PROBLEM]?.let { name -> SyncProblem.entries.firstOrNull { it.name == name } },
                detail = this[LAST_DETAIL],
            )
        },
    )

    private fun MutablePreferences.write(settings: SyncSettings) {
        this[ENABLED] = settings.enabled
        this[CLOUD_RESET] = settings.cloudReset
        this[WRITES_DAY] = settings.writesDay
        this[WRITES_TODAY] = settings.writesToday
        val last = settings.last
        set(LAST_AT, last?.at)
        set(LAST_DOWNLOADED, last?.downloaded)
        set(LAST_UPLOADED, last?.uploaded)
        set(LAST_SKIPPED, last?.skipped)
        set(LAST_LIMITED, last?.limited)
        set(LAST_PROBLEM, last?.problem?.name)
        set(LAST_DETAIL, last?.detail)
    }

    private fun <T> MutablePreferences.set(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    companion object {
        /** datastore/sync.preferences_pb: excluded in res/xml/backup_rules.xml and data_extraction_rules.xml. */
        const val FILE = "sync"

        private val BOUND_UID = stringPreferencesKey("bound_uid")
        private val BOUND_EMAIL = stringPreferencesKey("bound_email")
        private val EPOCH = stringPreferencesKey("epoch")
        private val CURSOR = longPreferencesKey("cursor")
        private val LAST_SYNCED_AT = longPreferencesKey("last_synced_at")
        private val FORMAT = stringPreferencesKey("format")

        private val ENABLED = booleanPreferencesKey("enabled")
        private val CLOUD_RESET = booleanPreferencesKey("cloud_reset")
        private val WRITES_DAY = longPreferencesKey("writes_day")
        private val WRITES_TODAY = intPreferencesKey("writes_today")
        private val LAST_AT = longPreferencesKey("last_at")
        private val LAST_DOWNLOADED = intPreferencesKey("last_downloaded")
        private val LAST_UPLOADED = intPreferencesKey("last_uploaded")
        private val LAST_SKIPPED = intPreferencesKey("last_skipped")
        private val LAST_LIMITED = booleanPreferencesKey("last_limited")
        private val LAST_PROBLEM = stringPreferencesKey("last_problem")
        private val LAST_DETAIL = stringPreferencesKey("last_detail")
    }
}

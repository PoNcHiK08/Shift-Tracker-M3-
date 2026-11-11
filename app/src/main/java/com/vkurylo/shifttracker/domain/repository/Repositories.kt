package com.vkurylo.shifttracker.domain.repository

import com.vkurylo.shifttracker.domain.model.AppLanguage
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.model.Payout
import com.vkurylo.shifttracker.domain.model.PayoutWithJob
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.domain.model.ThemeMode
import com.vkurylo.shifttracker.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.YearMonth

interface JobRepository {
    fun observeJobs(): Flow<List<Job>>
    suspend fun getJob(id: Long): Job?
    suspend fun upsert(job: Job): Long

    /**
     * Saves [job] and its [pay] settings in one transaction. With `pay.from == null` the settings apply
     * from the beginning and replace the whole history (a job without entries); otherwise they replace
     * only the entry of that month, so other months keep theirs.
     */
    suspend fun save(job: Job, pay: PaySettings): Long
    suspend fun delete(job: Job)
    suspend fun shiftCount(jobId: Long): Int

    /** Shifts + bonuses + received-pay payouts of the job; a job with entries keeps its currency. */
    suspend fun entryCount(jobId: Long): Int

    /** Currency of the most recently created job, or null when there is none. */
    suspend fun latestCurrency(): String?
}

interface ShiftRepository {
    /** All shifts (with their job) whose start date is within [from]..[to], ordered by start. */
    fun observeShifts(from: LocalDate, to: LocalDate): Flow<List<ShiftWithJob>>
    fun observeMonth(month: YearMonth): Flow<List<ShiftWithJob>> =
        observeShifts(month.atDay(1), month.atEndOfMonth())
    suspend fun getShift(id: Long): Shift?
    suspend fun getAllShifts(): List<Shift>
    suspend fun upsert(shift: Shift): Long

    /**
     * Saves shifts imported from a calendar (.ics) file. Each one is identified by its calendar event
     * rather than at random, so the same event imported on two phones is one shift, and importing an
     * event again never adds a second one: a shift that exists only gets the event's start and end time
     * (its job, rates, unpaid break and note are the user's and stay), and a deleted one comes back as the
     * calendar has it.
     */
    suspend fun importFromCalendar(shifts: List<Shift>)
    suspend fun delete(shift: Shift)
}

interface BonusRepository {
    /** Bonuses (with their job) dated within [from]..[to], ordered by date. */
    fun observeBonuses(from: LocalDate, to: LocalDate): Flow<List<BonusWithJob>>
    suspend fun getBonus(id: Long): Bonus?
    suspend fun upsert(bonus: Bonus): Long
    suspend fun delete(bonus: Bonus)
}

interface PayoutRepository {
    /** Payouts (with their job) earned for the months [from]..[to], ordered by month. */
    fun observePayouts(from: YearMonth, to: YearMonth): Flow<List<PayoutWithJob>>
    suspend fun getPayout(id: Long): Payout?
    suspend fun upsert(payout: Payout): Long
    suspend fun delete(payout: Payout)
}

interface PreferencesRepository {
    val preferences: Flow<UserPreferences>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setEarningsMode(mode: EarningsMode)
}

/** Per-app UI language (stored by the platform / AppCompat, not by us). */
interface LanguageRepository {
    fun current(): AppLanguage
    fun set(language: AppLanguage)
    /** Applies Ukrainian on the very first launch, as it is the app's default language. */
    suspend fun applyDefaultOnFirstLaunch()

    /**
     * The app language for places outside activities (the home-screen widget). On Android 13+ the
     * platform's per-app locale (also changed from system Settings); before that, the tag this app
     * stored when the language was set, as AppCompat only restores its locale when an activity starts.
     * Null: follow the system.
     */
    suspend fun appLocale(): java.util.Locale?

    /** Emits whenever the app language is set from the app (a trigger for the widget). */
    val changes: kotlinx.coroutines.flow.Flow<String?>
}

/** JSON export/import of all jobs, shifts and bonuses. */
interface BackupRepository {
    suspend fun exportJson(): String

    /**
     * Replaces all data with the backup. The whole file is validated first; if anything is wrong
     * (or writing fails half-way) nothing in the database changes. [beforeReplace] runs after the file
     * passed and right before the data is replaced, with sync held off (to save a safety backup of the
     * data about to go); if it throws, nothing changes.
     *
     * @throws InvalidBackupException when the file is not a usable backup.
     */
    suspend fun importJson(json: String, beforeReplace: suspend () -> Unit = {}): BackupCounts
}

/** The backup was rejected; the database is unchanged. */
class InvalidBackupException(
    message: String,
    cause: Throwable? = null,
    val reason: Reason = Reason.INVALID,
) : Exception(message, cause) {
    enum class Reason {
        /** Not a Shift Tracker backup, or it contains values the app can't accept. */
        INVALID,

        /** Written by a newer version of the app, in a format this version doesn't know. */
        NEWER_VERSION,
    }
}

data class BackupCounts(val jobs: Int, val shifts: Int, val bonuses: Int)

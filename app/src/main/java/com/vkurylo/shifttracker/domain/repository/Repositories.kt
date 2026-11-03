package com.vkurylo.shifttracker.domain.repository

import com.vkurylo.shifttracker.domain.model.AppLanguage
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.Job
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
    suspend fun delete(job: Job)
    suspend fun shiftCount(jobId: Long): Int
}

interface ShiftRepository {
    /** All shifts (with their job) whose start date is within [from]..[to], ordered by start. */
    fun observeShifts(from: LocalDate, to: LocalDate): Flow<List<ShiftWithJob>>
    fun observeMonth(month: YearMonth): Flow<List<ShiftWithJob>> =
        observeShifts(month.atDay(1), month.atEndOfMonth())
    suspend fun getShift(id: Long): Shift?
    suspend fun getAllShifts(): List<Shift>
    suspend fun upsert(shift: Shift): Long
    suspend fun insertAll(shifts: List<Shift>)
    suspend fun delete(shift: Shift)
}

interface BonusRepository {
    /** Bonuses (with their job) dated within [from]..[to], ordered by date. */
    fun observeBonuses(from: LocalDate, to: LocalDate): Flow<List<BonusWithJob>>
    suspend fun getBonus(id: Long): Bonus?
    suspend fun upsert(bonus: Bonus): Long
    suspend fun delete(bonus: Bonus)
}

interface PreferencesRepository {
    val preferences: Flow<UserPreferences>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
}

/** Per-app UI language (stored by the platform / AppCompat, not by us). */
interface LanguageRepository {
    fun current(): AppLanguage
    fun set(language: AppLanguage)
    /** Applies Ukrainian on the very first launch, as it is the app's default language. */
    suspend fun applyDefaultOnFirstLaunch()
}

/** JSON export/import of all jobs, shifts and bonuses. */
interface BackupRepository {
    suspend fun exportJson(): String

    /**
     * Replaces all data with the backup. The whole file is validated first; if anything is wrong
     * (or writing fails half-way) nothing in the database changes.
     *
     * @throws InvalidBackupException when the file is not a usable backup.
     */
    suspend fun importJson(json: String): BackupCounts
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

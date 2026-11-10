package com.vkurylo.shifttracker.ui.settings

import android.content.Context
import com.vkurylo.shifttracker.data.DocumentIo
import com.vkurylo.shifttracker.data.backup.JsonBackupRepository
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.sync.RemoteStore
import com.vkurylo.shifttracker.data.sync.SyncController
import com.vkurylo.shifttracker.data.sync.testSyncController
import com.vkurylo.shifttracker.data.sync.firebase.CloudAuth
import com.vkurylo.shifttracker.domain.model.AppLanguage
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.ThemeMode
import com.vkurylo.shifttracker.domain.model.UserPreferences
import com.vkurylo.shifttracker.domain.repository.LanguageRepository
import com.vkurylo.shifttracker.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.util.Locale

/**
 * A [SettingsViewModel] on an in-memory database, with [cloudAuth] and [remoteStore] as given, and a real
 * [SyncController] on them (its scheduler a fake).
 */
internal fun settingsViewModel(
    context: Context,
    db: ShiftDatabase,
    cloudAuth: CloudAuth,
    remoteStore: RemoteStore,
    syncController: SyncController = testSyncController(db, remoteStore, cloudAuth),
): SettingsViewModel {
    val preferences = object : PreferencesRepository {
        override val preferences: Flow<UserPreferences> = MutableStateFlow(UserPreferences(ThemeMode.SYSTEM, false, EarningsMode.GROSS))
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override suspend fun setDynamicColor(enabled: Boolean) = Unit
        override suspend fun setEarningsMode(mode: EarningsMode) = Unit
    }
    val language = object : LanguageRepository {
        override fun current() = AppLanguage.UKRAINIAN
        override fun set(language: AppLanguage) = Unit
        override suspend fun applyDefaultOnFirstLaunch() = Unit
        override suspend fun appLocale(): Locale = Locale.forLanguageTag("uk")
        override val changes: Flow<String?> = flowOf("uk")
    }
    val jobs = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), JobDetailsSource(db.paySettingsDao()))
    return SettingsViewModel(preferences, language, JsonBackupRepository(db), DocumentIo(context), cloudAuth, remoteStore, syncController, jobs)
}

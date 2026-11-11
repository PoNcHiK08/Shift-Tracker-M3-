package com.vkurylo.shifttracker.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.vkurylo.shifttracker.data.backup.JsonBackupRepository
import com.vkurylo.shifttracker.data.local.BonusDao
import com.vkurylo.shifttracker.data.local.PaySettingsDao
import com.vkurylo.shifttracker.data.local.PayoutDao
import com.vkurylo.shifttracker.data.local.JobDao
import com.vkurylo.shifttracker.data.local.ShiftDao
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.repository.AppCompatLanguageRepository
import com.vkurylo.shifttracker.data.repository.DataStorePreferencesRepository
import com.vkurylo.shifttracker.data.repository.RoomBonusRepository
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomPayoutRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.data.sync.FileSafetyBackup
import com.vkurylo.shifttracker.data.sync.FirestoreHostClock
import com.vkurylo.shifttracker.data.sync.RemoteStore
import com.vkurylo.shifttracker.data.sync.SafetyBackup
import com.vkurylo.shifttracker.data.sync.ServerClock
import com.vkurylo.shifttracker.data.sync.SyncPreferences
import com.vkurylo.shifttracker.data.sync.SyncScheduler
import com.vkurylo.shifttracker.data.sync.SyncStateStore
import com.vkurylo.shifttracker.data.sync.WorkManagerSyncScheduler
import com.vkurylo.shifttracker.data.sync.firebase.CloudAuth
import com.vkurylo.shifttracker.data.sync.firebase.FirebaseCloudAuth
import com.vkurylo.shifttracker.data.sync.firebase.FirestoreRemoteStore
import com.vkurylo.shifttracker.domain.repository.BackupRepository
import com.vkurylo.shifttracker.domain.repository.BonusRepository
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.LanguageRepository
import com.vkurylo.shifttracker.domain.repository.PayoutRepository
import com.vkurylo.shifttracker.domain.repository.PreferencesRepository
import com.vkurylo.shifttracker.domain.repository.ShiftRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ShiftDatabase =
        Room.databaseBuilder(context, ShiftDatabase::class.java, ShiftDatabase.NAME)
            .addMigrations(ShiftDatabase.MIGRATION_6_7)
            .build()

    @Provides
    fun provideWallClock(): WallClock = WallClock.System

    @Provides
    fun provideJobDao(db: ShiftDatabase): JobDao = db.jobDao()

    @Provides
    fun provideShiftDao(db: ShiftDatabase): ShiftDao = db.shiftDao()

    @Provides
    fun provideBonusDao(db: ShiftDatabase): BonusDao = db.bonusDao()

    @Provides
    fun providePayoutDao(db: ShiftDatabase): PayoutDao = db.payoutDao()

    @Provides
    fun providePaySettingsDao(db: ShiftDatabase): PaySettingsDao = db.paySettingsDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun bindJobRepository(impl: RoomJobRepository): JobRepository
    @Binds abstract fun bindShiftRepository(impl: RoomShiftRepository): ShiftRepository
    @Binds abstract fun bindBonusRepository(impl: RoomBonusRepository): BonusRepository
    @Binds abstract fun bindPayoutRepository(impl: RoomPayoutRepository): PayoutRepository
    @Binds abstract fun bindPreferencesRepository(impl: DataStorePreferencesRepository): PreferencesRepository
    @Binds abstract fun bindLanguageRepository(impl: AppCompatLanguageRepository): LanguageRepository
    @Binds abstract fun bindBackupRepository(impl: JsonBackupRepository): BackupRepository
    @Binds abstract fun bindRemoteStore(impl: FirestoreRemoteStore): RemoteStore
    @Binds abstract fun bindCloudAuth(impl: FirebaseCloudAuth): CloudAuth
    @Binds abstract fun bindSyncStateStore(impl: SyncPreferences): SyncStateStore
    @Binds abstract fun bindSafetyBackup(impl: FileSafetyBackup): SafetyBackup
    @Binds abstract fun bindServerClock(impl: FirestoreHostClock): ServerClock
    @Binds abstract fun bindSyncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler
}

@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {
    /** Outlives screens; used for work that must finish after navigation (e.g. undo). */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
}

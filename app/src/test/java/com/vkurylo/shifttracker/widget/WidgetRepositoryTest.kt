package com.vkurylo.shifttracker.widget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomBonusRepository
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.domain.model.AppLanguage
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ThemeMode
import com.vkurylo.shifttracker.domain.model.UserPreferences
import com.vkurylo.shifttracker.domain.repository.LanguageRepository
import com.vkurylo.shifttracker.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * The widget reads the app's own repositories: a write anywhere (here straight into Room, as an
 * import would) produces a new widget state, and the app language and "hide amounts" are applied.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WidgetRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, ShiftDatabase::class.java).allowMainThreadQueries().build()
    private val details = JobDetailsSource(db.paySettingsDao())
    private val prefs = MutableStateFlow(UserPreferences(ThemeMode.SYSTEM, true, EarningsMode.GROSS, hideWidgetAmounts = false))
    private val now = LocalDateTime.of(2026, 11, 4, 9, 0)

    private val language = object : LanguageRepository {
        override fun current() = AppLanguage.UKRAINIAN
        override fun set(language: AppLanguage) = Unit
        override suspend fun applyDefaultOnFirstLaunch() = Unit
        override suspend fun appLocale(): Locale = Locale.forLanguageTag("uk")
        override val changes: Flow<String?> = flowOf("uk")
    }
    private val preferences = object : PreferencesRepository {
        override val preferences: Flow<UserPreferences> = prefs
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override suspend fun setDynamicColor(enabled: Boolean) = Unit
        override suspend fun setEarningsMode(mode: EarningsMode) = Unit
        override suspend fun setHideWidgetAmounts(hide: Boolean) { prefs.value = prefs.value.copy(hideWidgetAmounts = hide) }
    }
    private val shifts = RoomShiftRepository(db.shiftDao(), details)
    private val repository = WidgetRepository(
        context,
        shifts,
        RoomBonusRepository(db.bonusDao(), details),
        RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details),
        preferences,
        language,
    )

    @After
    fun tearDown() = db.close()

    @Test
    fun aWriteProducesANewStateInTheAppLanguage() = runBlocking {
        val empty = repository.models { now }.first().state
        assertEquals("Додайте першу роботу", empty.emptyMessage)

        val job = db.jobDao().upsert(com.vkurylo.shifttracker.data.local.JobEntity(name = "Kawiarnia", hourlyRateGrosze = 3000, color = 1))
        shifts.upsert(Shift(jobId = job, date = now.toLocalDate(), start = LocalTime.of(10, 0), end = LocalTime.of(18, 0), hourlyRateGrosze = 3000))

        val state = repository.models { now }.first { it.state.nextShift != null }.state
        assertEquals("Kawiarnia", state.nextShift!!.jobName)
        assertEquals("Сьогодні", state.nextShift!!.whenLabel)
        assertEquals("240,00 zł", state.amountLines.single())
        assertEquals("8 год", state.hours)

        preferences.setHideWidgetAmounts(true)
        assertEquals(listOf("•••"), repository.models { now }.first().state.amountLines)
    }
}

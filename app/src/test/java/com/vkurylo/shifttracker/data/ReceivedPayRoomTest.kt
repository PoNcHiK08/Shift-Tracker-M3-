package com.vkurylo.shifttracker.data

import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomBonusRepository
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomPayoutRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Payout
import com.vkurylo.shifttracker.domain.model.ReceivedPart
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ThemeMode
import com.vkurylo.shifttracker.domain.model.UserPreferences
import com.vkurylo.shifttracker.domain.repository.PreferencesRepository
import com.vkurylo.shifttracker.ui.calendar.CalendarViewModel
import com.vkurylo.shifttracker.ui.summary.SummaryPeriod
import com.vkurylo.shifttracker.ui.summary.SummaryViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalTime
import java.time.YearMonth

/**
 * With the real database: a payout counts in the month's totals while it lives, and once deleted (a tombstone kept
 * for sync) every figure is the calculated one again. No stored data changes for this: it is computed on read.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ReceivedPayRoomTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShiftDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private var now = 1_000_000L
    private val clock = WallClock { now }
    private val details = JobDetailsSource(db.paySettingsDao())
    private val jobs = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details, clock)
    private val shifts = RoomShiftRepository(db, db.shiftDao(), details, clock)
    private val bonuses = RoomBonusRepository(db, db.bonusDao(), details, clock)
    private val payouts = RoomPayoutRepository(db, db.payoutDao(), details, clock)
    private val prefs = object : PreferencesRepository {
        override val preferences: Flow<UserPreferences> = MutableStateFlow(UserPreferences(ThemeMode.SYSTEM, true, EarningsMode.GROSS))
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override suspend fun setDynamicColor(enabled: Boolean) = Unit
        override suspend fun setEarningsMode(mode: EarningsMode) = Unit
    }
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val month = YearMonth.now()

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun <T> StateFlow<T>.awaitValue(condition: (T) -> Boolean): T {
        scope.launch { collect {} }
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition(value) && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("timed out at $value", condition(value))
        return value
    }

    @Test
    fun deletingThePayoutBringsTheCalculatedFigureBack() {
        val (jobId, payoutId) = runBlocking {
            val jobId = jobs.upsert(Job(name = "Cafe", hourlyRateGrosze = 3000, color = 0))
            shifts.upsert(Shift(jobId = jobId, date = month.atDay(3), start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3000))
            bonuses.upsert(Bonus(jobId = jobId, date = month.atDay(1), amountGrosze = 50_000, wholeMonth = true))
            jobId to payouts.upsert(Payout(jobId = jobId, month = month, amountMinor = 70_000))
        }
        val calendar = CalendarViewModel(SavedStateHandle(), shifts, bonuses, jobs, prefs, payouts)
        val summary = SummaryViewModel(
            SavedStateHandle(mapOf("period" to SummaryPeriod.MONTH.name, "anchor" to month.atDay(3).toEpochDay())),
            shifts, bonuses, jobs, prefs, payouts,
        )

        // 240 zł + 500 zł calculated; 700 zł arrived and counts instead.
        val received = calendar.uiState.awaitValue { !it.loading && it.monthSummary.totalEarnings["PLN"] == 70_000L }
        assertEquals(ReceivedPart.ALL, received.monthSummary.receivedPart)
        summary.uiState.awaitValue { !it.loading && it.summary.totalEarnings["PLN"] == 70_000L }

        now += 1_000
        runBlocking { payouts.delete(payouts.getPayout(payoutId)!!) }

        val back = calendar.uiState.awaitValue { it.monthSummary.totalEarnings["PLN"] == 74_000L }
        assertEquals(ReceivedPart.NONE, back.monthSummary.receivedPart)
        val month = summary.uiState.awaitValue { it.summary.totalEarnings["PLN"] == 74_000L }
        assertEquals(ReceivedPart.NONE, month.summary.perJob.single { it.job.id == jobId }.receivedPart)
        assertEquals(480L, month.summary.totalPaidMinutes)
        assertEquals("the deleted payout stays as a tombstone", true, runBlocking { db.payoutDao().getAny(payoutId)?.deleted })
    }
}

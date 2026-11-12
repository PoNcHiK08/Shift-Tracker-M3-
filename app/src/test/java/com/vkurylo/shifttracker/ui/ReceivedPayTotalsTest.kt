package com.vkurylo.shifttracker.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.model.PayType
import com.vkurylo.shifttracker.domain.model.Payout
import com.vkurylo.shifttracker.domain.model.ReceivedPart
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.domain.model.ThemeMode
import com.vkurylo.shifttracker.domain.model.UserPreferences
import com.vkurylo.shifttracker.domain.repository.BonusRepository
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.PreferencesRepository
import com.vkurylo.shifttracker.domain.repository.ShiftRepository
import com.vkurylo.shifttracker.ui.calendar.CalendarViewModel
import com.vkurylo.shifttracker.ui.summary.SummaryPeriod
import com.vkurylo.shifttracker.ui.summary.SummaryViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * The calendar's month tile and Summary month / year count a job-month's received pay instead of its calculated
 * pay, with the marker; a week, the day panel, hours and the average rate stay calculated.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ReceivedPayTotalsTest {
    private val month = YearMonth.now()
    private val day = month.atDay(3)

    // 30 zł/h with 10 % deductions; a 3 000 € salary with 20 %.
    private val cafe = Job(
        id = 1, name = "Cafe", hourlyRateGrosze = 3000, color = 0,
        payHistory = listOf(PaySettings(deductionBp = 1_000)), firstEntry = month.atDay(1), lastEntry = day,
    )
    private val office = Job(
        id = 2, name = "Office", hourlyRateGrosze = 0, color = 0, currency = "EUR",
        payHistory = listOf(PaySettings(null, PayType.FIXED_MONTHLY, 300_000, deductionBp = 2_000)), firstEntry = day, lastEntry = day,
    )
    private val shifts = listOf(
        ShiftWithJob(Shift(id = 1, jobId = 1, date = day, start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3000), cafe),
    )
    private val bonuses = listOf(
        BonusWithJob(Bonus(id = 1, jobId = 1, date = day, amountGrosze = 5_000), cafe),
        BonusWithJob(Bonus(id = 2, jobId = 1, date = month.atDay(1), amountGrosze = 50_000, wholeMonth = true), cafe),
    )

    // Calculated cafe: 240 zł + 50 zł + 500 zł = 790 zł gross. 700 zł arrived, in two payments.
    private val payouts = FakePayoutRepository(
        listOf(cafe, office),
        listOf(Payout(id = 1, jobId = 1, month = month, amountMinor = 60_000), Payout(id = 2, jobId = 1, month = month, amountMinor = 10_000)),
    )

    private val prefs = MutableStateFlow(UserPreferences(ThemeMode.SYSTEM, true, EarningsMode.GROSS))
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val shiftRepository = object : ShiftRepository {
        override fun observeShifts(from: LocalDate, to: LocalDate): Flow<List<ShiftWithJob>> =
            flowOf(shifts.filter { !it.shift.date.isBefore(from) && !it.shift.date.isAfter(to) })
        override suspend fun getShift(id: Long): Shift? = null
        override suspend fun getAllShifts(): List<Shift> = emptyList()
        override suspend fun upsert(shift: Shift): Long = 0
        override suspend fun importFromCalendar(shifts: List<Shift>) = Unit
        override suspend fun delete(shift: Shift) = Unit
    }
    private val bonusRepository = object : BonusRepository {
        override fun observeBonuses(from: LocalDate, to: LocalDate): Flow<List<BonusWithJob>> =
            flowOf(bonuses.filter { !it.bonus.date.isBefore(from) && !it.bonus.date.isAfter(to) })
        override suspend fun getBonus(id: Long): Bonus? = null
        override suspend fun upsert(bonus: Bonus): Long = 0
        override suspend fun delete(bonus: Bonus) = Unit
    }
    private val jobRepository = object : JobRepository {
        override fun observeJobs(): Flow<List<Job>> = flowOf(listOf(cafe, office))
        override suspend fun getJob(id: Long): Job? = null
        override suspend fun upsert(job: Job): Long = 0
        override suspend fun save(job: Job, pay: PaySettings): Long = 0
        override suspend fun delete(job: Job) = Unit
        override suspend fun shiftCount(jobId: Long): Int = 0
        override suspend fun entryCount(jobId: Long): Int = 0
        override suspend fun latestCurrency(): String? = null
    }
    private val preferencesRepository = object : PreferencesRepository {
        override val preferences: Flow<UserPreferences> = prefs
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override suspend fun setDynamicColor(enabled: Boolean) = Unit
        override suspend fun setEarningsMode(mode: EarningsMode) { prefs.value = prefs.value.copy(earningsMode = mode) }
    }

    private fun <T> StateFlow<T>.awaitValue(condition: (T) -> Boolean): T {
        scope.launch { collect {} } // keeps the WhileSubscribed flow running
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition(value) && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("timed out at $value", condition(value))
        return value
    }

    private fun calendar() = CalendarViewModel(SavedStateHandle(), shiftRepository, bonusRepository, jobRepository, preferencesRepository, payouts)

    private fun summary(period: SummaryPeriod, anchor: LocalDate = day) = SummaryViewModel(
        SavedStateHandle(mapOf("period" to period.name, "anchor" to anchor.toEpochDay())),
        shiftRepository, bonusRepository, jobRepository, preferencesRepository, payouts,
    )

    @Test
    fun theCalendarMonthTileCountsWhatArrivedWithTheMarkerAndTheDayPanelStaysCalculated() {
        val state = calendar().uiState.awaitValue { !it.loading }
        assertEquals(70_000L, state.monthSummary.totalEarnings["PLN"])
        assertEquals("the office's salary stays calculated", 300_000L, state.monthSummary.totalEarnings["EUR"])
        assertEquals(ReceivedPart.PART, state.monthSummary.receivedPart)
        assertEquals("hours stay calculated", 480L, state.monthSummary.totalPaidMinutes)
        // The day panel and dots: the day's own shift and bonus, untouched.
        assertEquals(1, state.shiftsByDate[day]?.size)
        assertEquals(listOf(5_000L), state.bonusesByDate[day]?.map { it.bonus.amountGrosze })
    }

    @Test
    fun receivedIsTheSameFigureInNet() {
        prefs.value = prefs.value.copy(earningsMode = EarningsMode.NET)
        val state = calendar().uiState.awaitValue { !it.loading && it.monthSummary.totalEarnings["EUR"] == 240_000L }
        assertEquals(70_000L, state.monthSummary.totalEarnings["PLN"])
        val summary = summary(SummaryPeriod.MONTH).uiState.awaitValue { !it.loading && it.summary.totalEarnings["EUR"] == 240_000L }
        assertEquals(70_000L, summary.summary.totalEarnings["PLN"])
    }

    @Test
    fun theSummaryMonthHeroAndByJobCountWhatArrived() {
        val state = summary(SummaryPeriod.MONTH).uiState.awaitValue { !it.loading }
        assertEquals(70_000L, state.summary.totalEarnings["PLN"])
        assertEquals(ReceivedPart.PART, state.summary.receivedPart)
        assertEquals("no bonus is counted on top", 0L, state.summary.countedBonuses["PLN"])
        val cafeRow = state.summary.perJob.single { it.job.id == 1L }
        assertEquals(70_000L, cafeRow.earningsGrosze)
        assertEquals(ReceivedPart.ALL, cafeRow.receivedPart)
        assertEquals(480L, cafeRow.paidMinutes)
        assertEquals(ReceivedPart.NONE, state.summary.perJob.single { it.job.id == 2L }.receivedPart)
        // Hours, shifts and the rate stay calculated.
        assertEquals(480L, state.summary.totalPaidMinutes)
        assertEquals(1, state.summary.shiftCount)
        assertEquals(3_000L, state.summary.averageRate("PLN"))
        // The comparison stays: calculated net 711 zł against 700 zł received.
        assertEquals(-1_100L, state.receivedPay.single { it.job.id == 1L }.differenceMinor)
    }

    @Test
    fun theYearRowAndTotalCountWhatArrived() {
        val state = summary(SummaryPeriod.YEAR).uiState.awaitValue { !it.loading }
        val row = state.breakdown[month.monthValue - 1]
        assertEquals(70_000L, row.earnings["PLN"])
        assertEquals(300_000L, row.earnings["EUR"])
        assertEquals(ReceivedPart.PART, row.receivedPart)
        assertEquals(480L, row.paidMinutes)
        assertEquals(70_000L, state.summary.totalEarnings["PLN"])
        assertEquals(state.breakdown.sumOf { it.earnings["PLN"] }, state.summary.totalEarnings["PLN"])
        assertTrue(state.breakdown.filterIndexed { i, _ -> i != month.monthValue - 1 }.all { it.receivedPart == ReceivedPart.NONE })
    }

    @Test
    fun aWeekStaysCalculatedWithPayoutsPresent() {
        val state = summary(SummaryPeriod.WEEK).uiState.awaitValue { !it.loading }
        // 240 zł + the day bonus of 50 zł: no received pay (and no month bonus) in a week.
        assertEquals(29_000L, state.summary.totalEarnings["PLN"])
        assertEquals(ReceivedPart.NONE, state.summary.receivedPart)
        assertEquals(29_000L, state.breakdown.single { it.date == day }.earnings["PLN"])
        assertTrue(state.breakdown.all { it.receivedPart == ReceivedPart.NONE })
    }

    @Test
    fun deletingThePayoutsBringsEveryFigureBackToTheCalculatedOne() {
        val calendar = calendar()
        val summary = summary(SummaryPeriod.MONTH)
        calendar.uiState.awaitValue { !it.loading && it.monthSummary.totalEarnings["PLN"] == 70_000L }
        runBlocking { payouts.all.forEach { payouts.delete(it) } }

        val back = calendar.uiState.awaitValue { it.monthSummary.totalEarnings["PLN"] == 79_000L }
        assertEquals(ReceivedPart.NONE, back.monthSummary.receivedPart)
        val month = summary.uiState.awaitValue { !it.loading && it.summary.totalEarnings["PLN"] == 79_000L }
        assertEquals(55_000L, month.summary.countedBonuses["PLN"])
        assertEquals(ReceivedPart.NONE, month.summary.perJob.single { it.job.id == 1L }.receivedPart)
    }
}

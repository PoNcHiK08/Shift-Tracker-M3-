package com.vkurylo.shifttracker.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * A bonus for the whole month is in its month's and year's totals (gross and net), but it has no day:
 * not in a week's totals or days, and not in the calendar grid or the day panel.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MonthBonusTotalsTest {
    private val october = YearMonth.of(2026, 10)
    private val november = YearMonth.of(2026, 11)

    // 10 % deductions. The week 26 Oct - 1 Nov 2026 contains the 1st of November, where November's bonus is kept.
    private val cafe = Job(
        id = 1, name = "Cafe", hourlyRateGrosze = 3000, color = 0,
        payHistory = listOf(PaySettings(deductionBp = 1_000)), firstEntry = october.atDay(1), lastEntry = november.atDay(1),
    )
    private val shifts = listOf(
        ShiftWithJob(Shift(id = 1, jobId = 1, date = LocalDate.of(2026, 10, 28), start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3000), cafe),
    )
    private val dayBonus = BonusWithJob(Bonus(id = 1, jobId = 1, date = LocalDate.of(2026, 10, 29), amountGrosze = 5_000), cafe)
    private val octoberBonus = BonusWithJob(Bonus(id = 2, jobId = 1, date = october.atDay(1), amountGrosze = 30_000, wholeMonth = true), cafe)
    private val novemberBonus = BonusWithJob(Bonus(id = 3, jobId = 1, date = november.atDay(1), amountGrosze = 50_000, wholeMonth = true), cafe)
    private val bonuses = listOf(octoberBonus, dayBonus, novemberBonus)

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
        override fun observeJobs(): Flow<List<Job>> = flowOf(listOf(cafe))
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

    private fun summary(period: SummaryPeriod, anchor: LocalDate): SummaryViewModel {
        val handle = SavedStateHandle(mapOf("period" to period.name, "anchor" to anchor.toEpochDay()))
        return SummaryViewModel(handle, shiftRepository, bonusRepository, jobRepository, preferencesRepository, FakePayoutRepository(listOf(cafe)))
    }

    @Test
    fun aWeekLeavesOutTheMonthBonusEvenWhenTheWeekContainsTheDateItIsKeptOn() {
        val vm = summary(SummaryPeriod.WEEK, LocalDate.of(2026, 10, 28))
        val state = vm.uiState.awaitValue { !it.loading && it.range.period == SummaryPeriod.WEEK }

        assertEquals(LocalDate.of(2026, 11, 1), state.range.to)
        // 8 h at 30 zł and a bonus of 50 zł: nothing of the 500 zł bonus for November (kept on 1 November).
        assertEquals(24_000L + 5_000L, state.summary.totalEarnings["PLN"])
        assertEquals(1, state.summary.bonusCount)
        assertEquals(5_000L, state.summary.bonuses["PLN"])
        val sunday = state.breakdown.last()
        assertEquals(LocalDate.of(2026, 11, 1), sunday.date)
        assertEquals(0, sunday.bonusCount)
        assertTrue(sunday.earnings.isEmpty)
        assertTrue(sunday.isEmpty)
        assertEquals(5_000L, state.breakdown.single { it.date == LocalDate.of(2026, 10, 29) }.earnings["PLN"])
    }

    @Test
    fun aWeekStaysWithoutItNetToo() {
        prefs.value = prefs.value.copy(earningsMode = EarningsMode.NET)
        val state = summary(SummaryPeriod.WEEK, LocalDate.of(2026, 10, 28)).uiState.awaitValue { !it.loading && it.summary.totalEarnings["PLN"] == 21_600L + 4_500L }
        assertEquals(1, state.summary.bonusCount)
    }

    @Test
    fun theMonthCountsItsOwnMonthBonusGrossAndNet() {
        val vm = summary(SummaryPeriod.MONTH, november.atDay(10))
        val gross = vm.uiState.awaitValue { !it.loading && it.range.period == SummaryPeriod.MONTH }
        assertEquals(50_000L, gross.summary.totalEarnings["PLN"])
        assertEquals(1, gross.summary.bonusCount)
        assertEquals("a bonus adds no hours and no shifts", 0L, gross.summary.totalPaidMinutes)
        assertEquals(50_000L, gross.summary.perJob.single().bonusGrosze)

        prefs.value = prefs.value.copy(earningsMode = EarningsMode.NET)
        val net = vm.uiState.awaitValue { it.summary.totalEarnings["PLN"] == 45_000L }
        assertEquals(45_000L, net.summary.bonuses["PLN"])
    }

    @Test
    fun theOctoberMonthHasOctobersBonusesButNotNovembers() {
        val state = summary(SummaryPeriod.MONTH, october.atDay(10)).uiState.awaitValue { !it.loading && it.range.from.month.value == 10 }
        // 8 h at 30 zł, 50 zł and the 300 zł month bonus; the 500 zł of November (kept on 1 November) is not in it.
        assertEquals(24_000L + 5_000L + 30_000L, state.summary.totalEarnings["PLN"])
        assertEquals(2, state.summary.bonusCount)
    }

    @Test
    fun theYearCountsEveryMonthBonusInItsOwnMonthRow() {
        val state = summary(SummaryPeriod.YEAR, LocalDate.of(2026, 6, 1)).uiState.awaitValue { !it.loading && it.range.period == SummaryPeriod.YEAR }

        assertEquals(24_000L + 5_000L + 30_000L + 50_000L, state.summary.totalEarnings["PLN"])
        assertEquals(3, state.summary.bonusCount)
        assertEquals(24_000L + 5_000L + 30_000L, state.breakdown[9].earnings["PLN"]) // October
        assertEquals(50_000L, state.breakdown[10].earnings["PLN"]) // November
        assertEquals(1, state.breakdown[10].bonusCount)
    }

    @Test
    fun theCalendarGridAndDayPanelHaveNoMonthBonusButTheMonthTotalHasIt() {
        val vm = CalendarViewModel(SavedStateHandle(), shiftRepository, bonusRepository, jobRepository, preferencesRepository)
        vm.showMonth(november)
        val state = vm.uiState.awaitValue { !it.loading && it.month == november && it.monthSummary.totalEarnings["PLN"] > 0 }

        assertNull("no dot or panel entry on the 1st", state.bonusesByDate[november.atDay(1)])
        assertFalse(state.bonusesByDate.values.flatten().any { it.bonus.wholeMonth })
        assertEquals("the bonus of 29 October is a day bonus of the neighbouring month", listOf(dayBonus), state.bonusesByDate[LocalDate.of(2026, 10, 29)])
        assertEquals(50_000L, state.monthSummary.totalEarnings["PLN"])
        assertEquals(1, state.monthSummary.bonusCount)
    }
}

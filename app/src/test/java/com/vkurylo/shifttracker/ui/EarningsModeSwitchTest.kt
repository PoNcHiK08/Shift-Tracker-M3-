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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * The global Gross / Net switch changes every earnings figure: the calendar's month total and the
 * summary (total, per job, breakdown), with each job's own deduction; currencies stay apart.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class EarningsModeSwitchTest {
    private val month = YearMonth.now()
    private val day = month.atDay(3)

    // A PLN café with 10 % deductions and a EUR office on a 3 000 € salary with 20 %.
    private val cafe = Job(
        id = 1, name = "Cafe", hourlyRateGrosze = 3000, color = 0,
        payHistory = listOf(PaySettings(deductionBp = 1_000)), firstEntry = day, lastEntry = day,
    )
    private val office = Job(
        id = 2, name = "Office", hourlyRateGrosze = 0, color = 0, currency = "EUR",
        payHistory = listOf(PaySettings(null, PayType.FIXED_MONTHLY, 300_000, deductionBp = 2_000)), firstEntry = day, lastEntry = day,
    )
    private val shifts = listOf(
        ShiftWithJob(Shift(id = 1, jobId = 1, date = day, start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3000), cafe),
        ShiftWithJob(Shift(id = 2, jobId = 2, date = day, start = LocalTime.of(9, 0), end = LocalTime.of(17, 0), hourlyRateGrosze = 0), office),
    )
    private val bonuses = listOf(BonusWithJob(Bonus(id = 1, jobId = 1, date = day, amountGrosze = 5_000), cafe))

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

    @Test
    fun calendarMonthTotalFollowsTheSwitch() {
        val vm = CalendarViewModel(SavedStateHandle(), shiftRepository, bonusRepository, jobRepository, preferencesRepository, FakePayoutRepository(listOf(cafe, office)))
        val gross = vm.uiState.awaitValue { !it.loading }.monthSummary
        assertEquals(mapOf("PLN" to 29_000L, "EUR" to 300_000L), gross.totalEarnings.byCurrency.filterValues { it > 0 })

        prefs.value = prefs.value.copy(earningsMode = EarningsMode.NET)
        val net = vm.uiState.awaitValue { it.monthSummary.totalEarnings["PLN"] != 29_000L }.monthSummary
        // 240 zl + 50 zl less 10 % each; 3 000 EUR less 20 %. Never one total across currencies.
        assertEquals(21_600L + 4_500L, net.totalEarnings["PLN"])
        assertEquals(240_000L, net.totalEarnings["EUR"])
    }

    @Test
    fun summaryFollowsTheSwitch() {
        val vm = SummaryViewModel(SavedStateHandle(), shiftRepository, bonusRepository, jobRepository, preferencesRepository, FakePayoutRepository(listOf(cafe, office)))
        vm.setPeriod(SummaryPeriod.YEAR)
        val gross = vm.uiState.awaitValue { !it.loading && it.range.period == SummaryPeriod.YEAR }
        val monthEntry = gross.breakdown[month.monthValue - 1]
        assertEquals(29_000L, monthEntry.earnings["PLN"])
        assertEquals(300_000L, monthEntry.earnings["EUR"])

        prefs.value = prefs.value.copy(earningsMode = EarningsMode.NET)
        val net = vm.uiState.awaitValue { it.summary.totalEarnings["PLN"] == 26_100L }
        assertEquals(240_000L, net.summary.totalEarnings["EUR"])
        assertEquals(26_100L, net.breakdown[month.monthValue - 1].earnings["PLN"])
        assertEquals(26_100L, net.summary.perJob.first { it.job.id == 1L }.earningsGrosze)
    }
}

package com.vkurylo.shifttracker.ui.summary

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.R
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
import com.vkurylo.shifttracker.ui.FakePayoutRepository
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.SnackbarController
import com.vkurylo.shifttracker.ui.theme.ShiftTrackerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** Summary → Month lists the bonuses for the whole month in a section of their own; week and year don't. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h2400dp")
class SummaryMonthBonusesTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val november = YearMonth.of(2026, 11)
    private val cafe = Job(
        id = 1, name = "Kawiarnia", hourlyRateGrosze = 3000, color = 1,
        payHistory = listOf(PaySettings()), firstEntry = november.atDay(3), lastEntry = november.atDay(3),
    )
    private val shifts = listOf(
        ShiftWithJob(Shift(id = 1, jobId = 1, date = november.atDay(3), start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3000), cafe),
    )
    private val monthBonus = BonusWithJob(Bonus(id = 7, jobId = 1, date = november.atDay(1), amountGrosze = 50_000, note = "Premia kwartalna", wholeMonth = true), cafe)
    private val dayBonus = BonusWithJob(Bonus(id = 8, jobId = 1, date = november.atDay(4), amountGrosze = 5_000, note = "napiwek"), cafe)

    private var bonuses = listOf(monthBonus, dayBonus)

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
        override val preferences: Flow<UserPreferences> = MutableStateFlow(UserPreferences(ThemeMode.SYSTEM, true, EarningsMode.GROSS))
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override suspend fun setDynamicColor(enabled: Boolean) = Unit
        override suspend fun setEarningsMode(mode: EarningsMode) = Unit
    }

    private val edited = mutableListOf<Long>()
    private val added = mutableListOf<YearMonth>()

    private fun show(period: SummaryPeriod = SummaryPeriod.MONTH): SummaryViewModel {
        val handle = SavedStateHandle(mapOf("period" to period.name, "anchor" to november.atDay(10).toEpochDay()))
        val payouts = FakePayoutRepository(listOf(cafe))
        val vm = SummaryViewModel(handle, shiftRepository, bonusRepository, jobRepository, preferencesRepository, payouts)
        val editor = PayoutEditorViewModel(payouts, CoroutineScope(Dispatchers.Unconfined))
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalSnackbarController provides SnackbarController(SnackbarHostState(), scope)) {
                ShiftTrackerTheme(darkTheme = false, dynamicColor = false) {
                    SummaryScreen(onEditBonus = { edited += it }, onAddMonthBonus = { added += it }, viewModel = vm, payoutEditor = editor)
                }
            }
        }
        composeRule.waitUntil(5_000) { !vm.uiState.value.loading }
        return vm
    }

    private fun text(res: Int) = context.getString(res)

    @Test
    fun theMonthListsItsMonthBonusesInTheirOwnSection() {
        show()

        composeRule.onNodeWithText(text(R.string.month_bonuses_title)).assertIsDisplayed()
        composeRule.onNodeWithText("Premia kwartalna").assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.bonus_for_whole_month)).assertIsDisplayed()
        // A bonus on a day is part of the month's totals, but not listed here.
        composeRule.onNodeWithText("napiwek").assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.month_bonuses_none)).assertDoesNotExist()
    }

    @Test
    fun tappingOneOpensItAndAddStartsAMonthBonusForTheMonthOnScreen() {
        show()

        composeRule.onNodeWithText("Premia kwartalna").performClick()
        assertEquals(listOf(7L), edited)
        composeRule.onNodeWithTag(SUMMARY_ADD_MONTH_BONUS_TAG).performClick()
        assertEquals(listOf(november), added)
    }

    @Test
    fun aMonthWithoutOneSaysSoAndStillOffersToAddOne() {
        bonuses = listOf(dayBonus)
        show()

        composeRule.onNodeWithText(text(R.string.month_bonuses_none)).assertIsDisplayed()
        composeRule.onNodeWithTag(SUMMARY_ADD_MONTH_BONUS_TAG).assertIsDisplayed()
    }

    @Test
    fun theWeekHasNoSuchSection() {
        val vm = show(SummaryPeriod.WEEK)
        composeRule.onNodeWithText(text(R.string.month_bonuses_title)).assertDoesNotExist()
        composeRule.onNode(hasTestTag(SUMMARY_ADD_MONTH_BONUS_TAG)).assertDoesNotExist()
        assertEquals(SummaryPeriod.WEEK, vm.uiState.value.range.period)
        assertEquals(emptyList<BonusWithJob>(), vm.uiState.value.monthBonuses)
    }

    @Test
    fun theYearHasNoSuchSectionEither() {
        val vm = show(SummaryPeriod.YEAR)
        composeRule.onNodeWithText(text(R.string.month_bonuses_title)).assertDoesNotExist()
        composeRule.onNode(hasTestTag(SUMMARY_ADD_MONTH_BONUS_TAG)).assertDoesNotExist()
        assertEquals(SummaryPeriod.YEAR, vm.uiState.value.range.period)
        assertEquals(emptyList<BonusWithJob>(), vm.uiState.value.monthBonuses)
    }
}

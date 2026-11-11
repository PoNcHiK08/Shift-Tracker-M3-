package com.vkurylo.shifttracker.ui.bonus

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.repository.BonusRepository
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.MONTH_STEPPER_VALUE_TAG
import com.vkurylo.shifttracker.ui.components.SnackbarController
import com.vkurylo.shifttracker.ui.theme.ShiftTrackerTheme
import com.vkurylo.shifttracker.ui.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** The bonus editor's switch: "On a specific day" (as before) or "For the whole month" (pick a month, no date). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class MonthBonusEditorTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val job = Job(id = 1, name = "Kawiarnia", hourlyRateGrosze = 3050, color = 1)
    private val november = YearMonth.of(2026, 11)
    private val saved = mutableListOf<Bonus>()
    private val deleted = mutableListOf<Bonus>()
    private var stored: Bonus? = null

    private val bonuses = object : BonusRepository {
        override fun observeBonuses(from: LocalDate, to: LocalDate): Flow<List<BonusWithJob>> = flowOf(emptyList())
        override suspend fun getBonus(id: Long): Bonus? = stored?.takeIf { it.id == id }
        override suspend fun upsert(bonus: Bonus): Long = bonus.id.also { saved += bonus }
        override suspend fun delete(bonus: Bonus) { deleted += bonus }
    }
    private val jobs = object : JobRepository {
        override fun observeJobs(): Flow<List<Job>> = flowOf(listOf(job))
        override suspend fun getJob(id: Long): Job? = job
        override suspend fun upsert(job: Job): Long = job.id
        override suspend fun delete(job: Job) = Unit
        override suspend fun shiftCount(jobId: Long): Int = 0
        override suspend fun entryCount(jobId: Long): Int = 0
        override suspend fun latestCurrency(): String? = null
        override suspend fun save(job: Job, pay: PaySettings): Long = job.id
    }

    private fun editor(route: Map<String, Any?>): EditBonusViewModel =
        EditBonusViewModel(SavedStateHandle(route), bonuses, jobs, CoroutineScope(Dispatchers.Unconfined))

    private fun show(vm: EditBonusViewModel) {
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalSnackbarController provides SnackbarController(SnackbarHostState(), scope)) {
                ShiftTrackerTheme(darkTheme = false, dynamicColor = false) {
                    EditBonusScreen(onClose = {}, onManageJobs = {}, viewModel = vm)
                }
            }
        }
        composeRule.waitUntil(5_000) { !vm.uiState.value.loading }
    }

    private fun text(res: Int) = context.getString(res)

    @Test
    fun aNewBonusIsOnADayBySwitchedToTheWholeMonthShowsAMonthInsteadOfADate() {
        val vm = editor(mapOf("dateEpochDay" to LocalDate.of(2026, 11, 17).toEpochDay()))
        show(vm)

        // Default: on a specific day, with the date field.
        composeRule.onNodeWithText(text(R.string.bonus_kind_day)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.bonus_kind_month)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.date)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.bonus_hint)).assertExists() // below the fold on a small screen
        composeRule.onNodeWithTag(MONTH_STEPPER_VALUE_TAG).assertDoesNotExist()
        assertFalse(vm.uiState.value.wholeMonth)

        composeRule.onNodeWithText(text(R.string.bonus_kind_month)).performClick()

        assertTrue(vm.uiState.value.wholeMonth)
        composeRule.onNodeWithTag(MONTH_STEPPER_VALUE_TAG).assertTextEquals(Formatters.monthYear(november, Locale.getDefault()))
        composeRule.onNodeWithText(text(R.string.date)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.bonus_hint)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.bonus_hint_month)).assertExists()

        // And back: the day is still the one that was picked.
        composeRule.onNodeWithText(text(R.string.bonus_kind_day)).performClick()
        composeRule.onNodeWithText(text(R.string.date)).assertIsDisplayed()
        assertEquals(LocalDate.of(2026, 11, 17), vm.uiState.value.date)
    }

    @Test
    fun theMonthIsSteppedWithTheArrows() {
        val vm = editor(mapOf("dateEpochDay" to november.atDay(1).toEpochDay(), "wholeMonth" to true))
        show(vm)

        composeRule.onNodeWithTag(MONTH_STEPPER_VALUE_TAG).assertTextEquals(Formatters.monthYear(november, Locale.getDefault()))
        composeRule.onNodeWithContentDescription(text(R.string.next_month)).performClick()
        composeRule.onNodeWithTag(MONTH_STEPPER_VALUE_TAG).assertTextEquals(Formatters.monthYear(november.plusMonths(1), Locale.getDefault()))
        composeRule.onNodeWithContentDescription(text(R.string.previous_month)).performClick()
        composeRule.onNodeWithContentDescription(text(R.string.previous_month)).performClick()
        composeRule.onNodeWithTag(MONTH_STEPPER_VALUE_TAG).assertTextEquals(Formatters.monthYear(november.minusMonths(1), Locale.getDefault()))
        assertEquals(LocalDate.of(2026, 10, 1), vm.uiState.value.date)
    }

    @Test
    fun savingAMonthBonusStoresTheFlagAndTheFirstOfTheMonth() = runBlocking {
        val vm = editor(mapOf("dateEpochDay" to LocalDate.of(2026, 11, 17).toEpochDay(), "wholeMonth" to true))
        show(vm)
        vm.setAmount("500")
        vm.setNote("Premia kwartalna")

        vm.save()
        composeRule.waitUntil(5_000) { saved.isNotEmpty() }

        val bonus = saved.single()
        assertTrue(bonus.wholeMonth)
        assertEquals("kept on the 1st of its month", LocalDate.of(2026, 11, 1), bonus.date)
        assertEquals(50_000L, bonus.amountGrosze)
        assertEquals(november, bonus.month)
    }

    @Test
    fun savingADayBonusStillStoresADayBonus() {
        val vm = editor(mapOf("dateEpochDay" to LocalDate.of(2026, 11, 17).toEpochDay()))
        show(vm)
        vm.setAmount("75")
        vm.save()
        composeRule.waitUntil(5_000) { saved.isNotEmpty() }

        assertFalse(saved.single().wholeMonth)
        assertEquals(LocalDate.of(2026, 11, 17), saved.single().date)
    }

    @Test
    fun anExistingMonthBonusOpensInMonthModeAndKeepsItWhenSaved() {
        stored = Bonus(id = 5, jobId = 1, date = november.atDay(1), amountGrosze = 50_000, note = "Premia", wholeMonth = true)
        val vm = editor(mapOf("bonusId" to 5L))
        show(vm)

        composeRule.onNodeWithTag(MONTH_STEPPER_VALUE_TAG).assertTextEquals(Formatters.monthYear(november, Locale.getDefault()))
        assertTrue(vm.uiState.value.wholeMonth)
        assertEquals("500", vm.uiState.value.amount.substringBefore(',').substringBefore('.'))

        vm.setAmount("600")
        vm.save()
        composeRule.waitUntil(5_000) { saved.isNotEmpty() }
        assertEquals(Bonus(id = 5, jobId = 1, date = november.atDay(1), amountGrosze = 60_000, note = "Premia", wholeMonth = true), saved.single())
    }

    @Test
    fun deletingAMonthBonusCanBeUndoneWithTheFlagIntact() {
        stored = Bonus(id = 5, jobId = 1, date = november.atDay(1), amountGrosze = 50_000, wholeMonth = true)
        val vm = editor(mapOf("bonusId" to 5L))
        show(vm)

        vm.requestDelete()
        vm.delete()
        composeRule.waitUntil(5_000) { deleted.isNotEmpty() }
        assertTrue(deleted.single().wholeMonth)

        vm.undoDelete(deleted.single()) // the snackbar's Undo
        composeRule.waitUntil(5_000) { saved.isNotEmpty() }
        assertEquals(deleted.single(), saved.single())
        assertTrue(saved.single().wholeMonth)
    }
}

package com.vkurylo.shifttracker.ui.bonus

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.repository.BonusRepository
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.SnackbarController
import com.vkurylo.shifttracker.ui.theme.ShiftTrackerTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Regression: an editor's Delete was enabled before the entry had loaded. A tap in that moment set
 * the "confirm delete" flag on a state the loader then replaced, so the tap silently did nothing
 * (it also made the smoke test flaky). Delete must stay disabled until the entry is loaded.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class EditorDeleteWhileLoadingTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val job = Job(id = 1, name = "Kawiarnia", hourlyRateGrosze = 3050, color = 1)
    private val bonus = Bonus(id = 5, jobId = 1, date = LocalDate.of(2026, 11, 3), amountGrosze = 7500)

    @Test
    fun deleteIsDisabledUntilTheBonusHasLoaded() {
        val loaded = CompletableDeferred<Unit>()
        val bonuses = object : BonusRepository {
            override fun observeBonuses(from: LocalDate, to: LocalDate): Flow<List<BonusWithJob>> = flowOf(emptyList())
            override suspend fun getBonus(id: Long): Bonus? = bonus.also { loaded.await() }
            override suspend fun upsert(bonus: Bonus): Long = bonus.id
            override suspend fun delete(bonus: Bonus) = Unit
        }
        val jobs = object : JobRepository {
            override fun observeJobs(): Flow<List<Job>> = flowOf(listOf(job))
            override suspend fun getJob(id: Long): Job? = job
            override suspend fun upsert(job: Job): Long = job.id
            override suspend fun delete(job: Job) = Unit
            override suspend fun shiftCount(jobId: Long): Int = 0
        }
        val vm = EditBonusViewModel(SavedStateHandle(mapOf("bonusId" to 5L)), bonuses, jobs, CoroutineScope(Dispatchers.Unconfined))
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalSnackbarController provides SnackbarController(SnackbarHostState(), scope)) {
                ShiftTrackerTheme(darkTheme = false, dynamicColor = false) {
                    EditBonusScreen(onClose = {}, onManageJobs = {}, viewModel = vm)
                }
            }
        }
        val delete = composeRule.onNodeWithContentDescription(context.getString(R.string.delete_bonus))

        // Still loading: Delete is visible but can't be pressed.
        delete.assertIsNotEnabled()

        loaded.complete(Unit)
        composeRule.waitUntil(5_000) { !vm.uiState.value.loading }
        delete.assertIsEnabled().performClick()
        composeRule.waitUntilAtLeastOneExists(hasText(context.getString(R.string.delete_bonus_title)), 5_000)
    }
}

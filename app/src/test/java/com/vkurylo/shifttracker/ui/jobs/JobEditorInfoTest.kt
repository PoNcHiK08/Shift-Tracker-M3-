package com.vkurylo.shifttracker.ui.jobs

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.ui.theme.ShiftTrackerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/**
 * The job editor's ⓘ buttons open their explanations, including the one in the locked (disabled) currency field:
 * a disabled field must not swallow the tap on its trailing icon.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w411dp-h2400dp")
class JobEditorInfoTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, ShiftDatabase::class.java).allowMainThreadQueries().build()
    private val details = JobDetailsSource(db.paySettingsDao())
    private val jobs = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details)
    private val shifts = RoomShiftRepository(db, db.shiftDao(), details)
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun settle(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("timed out", condition())
    }

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun theLockedCurrencyAndTheDeductionsExplainThemselves() {
        val id = runBlocking {
            val job = jobs.upsert(Job(name = "Cafe", hourlyRateGrosze = 3_000, color = 0))
            shifts.upsert(Shift(jobId = job, date = LocalDate.of(2026, 3, 5), start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3_000))
            job
        }
        val vm = JobsViewModel(jobs)
        scope.launch { vm.uiState.collect {} }
        composeRule.setContent { ShiftTrackerTheme(darkTheme = false, dynamicColor = false) { JobsScreen(onBack = {}, viewModel = vm) } }
        settle { vm.uiState.value.jobs.isNotEmpty() }
        composeRule.runOnUiThread { vm.startEdit(vm.uiState.value.jobs.single { it.id == id }) }
        settle { vm.uiState.value.editing != null && vm.uiState.value.currencyLocked }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(str(R.string.currency_locked)).assertDoesNotExist()
        composeRule.onNode(hasContentDescription(str(R.string.more_info, str(R.string.currency))) and hasClickAction()).performClick()
        composeRule.onNodeWithText(str(R.string.currency_locked)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.ok)).performClick()

        composeRule.onNode(hasContentDescription(str(R.string.more_info, str(R.string.deduction_percent))) and hasClickAction())
            .performScrollTo().performClick()
        composeRule.onNodeWithText(str(R.string.deduction_hint)).assertIsDisplayed()
    }
}

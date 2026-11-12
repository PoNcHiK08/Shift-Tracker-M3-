package com.vkurylo.shifttracker.ui.shift

import android.app.Application
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRatesRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.data.sync.SafetyBackup
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.SnackbarController
import com.vkurylo.shifttracker.ui.summary.signedMoney
import com.vkurylo.shifttracker.ui.theme.ShiftTrackerTheme
import com.vkurylo.shifttracker.ui.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.robolectric.Shadows.shadowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/** Edit shift → "Apply to all shifts of this job…": the button, the dialog's preview and scopes, Apply and Undo. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h2400dp")
class ApplyRateScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val locale = Locale.getDefault()
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShiftDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private var now = 1_000_000L
    private val clock = WallClock { now }
    private val details = JobDetailsSource(db.paySettingsDao())
    private val jobs = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details, clock)
    private val shifts = RoomShiftRepository(db, db.shiftDao(), details, clock)
    private val rates = RoomShiftRatesRepository(db, db.shiftDao(), clock)
    private val appScope = CoroutineScope(Dispatchers.Unconfined)
    private val backups = mutableListOf<String>()
    private val snackbarHost = SnackbarHostState()

    private var cafeId = 0L
    private var january = 0L
    private var march = 0L
    private var april = 0L
    private var current = 0L

    @After
    fun tearDown() {
        appScope.cancel()
        db.close()
    }

    private fun setUp() = runBlocking {
        cafeId = jobs.upsert(Job(name = "Cafe", hourlyRateGrosze = 3_000, color = 0))
        fun shift(date: LocalDate, rate: Long = 3_000) =
            Shift(jobId = cafeId, date = date, start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = rate)
        january = shifts.upsert(shift(LocalDate.of(2026, 1, 10)))
        march = shifts.upsert(shift(LocalDate.of(2026, 3, 5)))
        april = shifts.upsert(shift(LocalDate.of(2026, 4, 1)))
        current = shifts.upsert(shift(LocalDate.of(2026, 5, 1), rate = 3_500))
        jobs.upsert(jobs.getJob(cafeId)!!.copy(hourlyRateGrosze = 3_500))
        now += 10_000
    }

    private fun show(shiftId: Long) {
        val editor = EditShiftViewModel(SavedStateHandle(mapOf("shiftId" to shiftId)), shifts, jobs, appScope)
        val applyRate = ApplyRateViewModel(rates, SafetyBackup { backups += it }, appScope)
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalSnackbarController provides SnackbarController(snackbarHost, scope)) {
                ShiftTrackerTheme(darkTheme = false, dynamicColor = false) {
                    EditShiftScreen(onClose = {}, onManageJobs = {}, viewModel = editor, applyRate = applyRate)
                }
            }
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (editor.uiState.value.loading && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertFalse("the editor didn't load", editor.uiState.value.loading)
        composeRule.waitForIdle()
    }

    private fun text(res: Int, vararg args: Any) = context.getString(res, *args)
    private fun summary(count: Int, minor: Long) =
        context.resources.getQuantityString(R.plurals.apply_rate_summary, count, count, signedMoney(minor, "PLN", locale))
    private fun rate(id: Long) = runBlocking { db.shiftDao().getAny(id)!!.hourlyRateGrosze }

    @Test
    fun theButtonIsThereOnlyWhenTheShiftsRateIsOutdated() {
        setUp()
        show(current)
        composeRule.onNodeWithTag(APPLY_RATE_ALL_BUTTON).assertDoesNotExist()
    }

    @Test
    fun aNewShiftHasNoButton() {
        setUp()
        show(0)
        composeRule.onNodeWithTag(APPLY_RATE_ALL_BUTTON).assertDoesNotExist()
    }

    @Test
    fun theDialogShowsTheCountTheMoneyAndTheWarningAndTheScopeChangesThem() {
        setUp()
        show(march)
        composeRule.onNodeWithTag(APPLY_RATE_ALL_BUTTON).performScrollTo().performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(text(R.string.apply_rate_title))).fetchSemanticsNodes().isNotEmpty() }

        // March is open: January and April change, 8 h × 5 zł more each.
        composeRule.onNodeWithTag(APPLY_RATE_SUMMARY).assertTextEquals(summary(2, 8_000))
        composeRule.onNodeWithText(text(R.string.apply_rate_warning)).assertExists()
        composeRule.onNodeWithTag(APPLY_RATE_SCOPE + "FROM_DATE").performClick()
        composeRule.onNodeWithTag(APPLY_RATE_SUMMARY).assertTextEquals(summary(1, 4_000))
        composeRule.onNodeWithText(text(R.string.apply_rate_scope_from, Formatters.shortDate(LocalDate.of(2026, 3, 5), locale))).assertExists()
        composeRule.onNodeWithTag(APPLY_RATE_CONFIRM).assertIsEnabled()
    }

    @Test
    fun applyChangesTheOtherShiftsShowsUndoAndUndoPutsThemBack() {
        setUp()
        show(march)
        composeRule.onNodeWithTag(APPLY_RATE_ALL_BUTTON).performScrollTo().performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(text(R.string.apply_rate_title))).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(APPLY_RATE_CONFIRM).performClick()

        composeRule.waitUntil(5_000) { snackbarHost.currentSnackbarData != null }
        val message = context.resources.getQuantityString(R.plurals.apply_rate_done, 2, 2)
        assertEquals(message, snackbarHost.currentSnackbarData!!.visuals.message)
        assertEquals(listOf(SafetyBackup.Reason.BEFORE_RATE_CHANGE), backups)
        assertEquals(3_500L, rate(january))
        assertEquals(3_500L, rate(april))
        assertEquals("the open shift waits for Save", 3_000L, rate(march))
        composeRule.onNodeWithTag(APPLY_RATE_DIALOG).assertDoesNotExist()
        // The editor shows the current rate for this shift (the hint is gone).
        composeRule.onNodeWithTag(APPLY_RATE_ALL_BUTTON).assertDoesNotExist()

        snackbarHost.currentSnackbarData!!.performAction()
        composeRule.waitUntil(5_000) { rate(january) == 3_000L && rate(april) == 3_000L }
    }

    @Test
    fun withNothingToChangeApplyIsOff() {
        setUp()
        runBlocking {
            for (id in listOf(january, april)) shifts.upsert(shifts.getShift(id)!!.copy(hourlyRateGrosze = 3_500))
        }
        show(march)
        composeRule.onNodeWithTag(APPLY_RATE_ALL_BUTTON).performScrollTo().performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(text(R.string.apply_rate_title))).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(APPLY_RATE_SUMMARY).assertTextEquals(text(R.string.apply_rate_none))
        composeRule.onNodeWithTag(APPLY_RATE_CONFIRM).assertIsNotEnabled()
    }
}

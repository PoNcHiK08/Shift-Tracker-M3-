package com.vkurylo.shifttracker.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomBonusRepository
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRatesRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.data.sync.SafetyBackup
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Payout
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.ui.bonus.EditBonusScreen
import com.vkurylo.shifttracker.ui.bonus.EditBonusViewModel
import com.vkurylo.shifttracker.ui.components.DISCARD_CONFIRM
import com.vkurylo.shifttracker.ui.components.DISCARD_DIALOG
import com.vkurylo.shifttracker.ui.components.DISCARD_KEEP
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.SnackbarController
import com.vkurylo.shifttracker.ui.jobs.JOB_NAME_TAG
import com.vkurylo.shifttracker.ui.jobs.JobsScreen
import com.vkurylo.shifttracker.ui.jobs.JobsViewModel
import com.vkurylo.shifttracker.ui.shift.ApplyRateViewModel
import com.vkurylo.shifttracker.ui.shift.EditShiftScreen
import com.vkurylo.shifttracker.ui.shift.EditShiftViewModel
import com.vkurylo.shifttracker.ui.summary.PayoutEditorViewModel
import com.vkurylo.shifttracker.ui.summary.RECEIVED_PAY_AMOUNT_TAG
import com.vkurylo.shifttracker.ui.summary.RECEIVED_PAY_CANCEL_TAG
import com.vkurylo.shifttracker.ui.summary.RECEIVED_PAY_SHEET_TAG
import com.vkurylo.shifttracker.ui.summary.ReceivedPaySheet
import com.vkurylo.shifttracker.ui.theme.ShiftTrackerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * The shift, bonus, received-payment and job editors ask "Discard changes?" before Close, system back or dismissing
 * the sheet throws away unsaved changes; without changes they close straight away, and nothing is ever saved by it.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h2400dp")
class DiscardChangesTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, ShiftDatabase::class.java).allowMainThreadQueries().build()
    private var now = 1_000_000L
    private val clock = WallClock { now++ }
    private val details = JobDetailsSource(db.paySettingsDao())
    private val jobs = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details, clock)
    private val shifts = RoomShiftRepository(db, db.shiftDao(), details, clock)
    private val bonuses = RoomBonusRepository(db, db.bonusDao(), details, clock)
    private val appScope = CoroutineScope(Dispatchers.Unconfined)
    private val snackbarHost = SnackbarHostState()
    private var closed = 0

    private var cafeId = 0L
    private var shiftId = 0L
    private var bonusId = 0L

    @After
    fun tearDown() {
        appScope.cancel()
        db.close()
    }

    private fun setUp() = runBlocking {
        cafeId = jobs.upsert(Job(name = "Cafe", hourlyRateGrosze = 3_000, color = 0))
        shiftId = shifts.upsert(Shift(jobId = cafeId, date = LocalDate.of(2026, 3, 5), start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3_000, note = "n"))
        bonusId = bonuses.upsert(Bonus(jobId = cafeId, date = LocalDate.of(2026, 3, 5), amountGrosze = 5_000, note = "tips"))
    }

    private fun settle(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("timed out", condition())
    }

    /** Like [settle], while letting Compose draw frames too (a sheet's hide animation). */
    private fun settleUi(condition: () -> Boolean) =
        composeRule.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }

    private fun shiftEditor(id: Long) =
        EditShiftViewModel(SavedStateHandle(mapOf("shiftId" to id)), shifts, jobs, appScope).also { vm ->
            settle { !vm.uiState.value.loading && vm.uiState.value.jobs.isNotEmpty() }
        }

    private fun bonusEditor(id: Long) =
        EditBonusViewModel(SavedStateHandle(mapOf("bonusId" to id)), bonuses, jobs, appScope).also { vm ->
            settle { !vm.uiState.value.loading && vm.uiState.value.jobs.isNotEmpty() }
        }

    private fun content(body: @androidx.compose.runtime.Composable () -> Unit) = composeRule.setContent {
        val scope = rememberCoroutineScope()
        CompositionLocalProvider(LocalSnackbarController provides SnackbarController(snackbarHost, scope)) {
            ShiftTrackerTheme(darkTheme = false, dynamicColor = false) { body() }
        }
    }

    /** System back on the activity (full-screen editors). */
    private fun back() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    /** System back as the phone sends it: to the focused window, which is a bottom sheet's own window while it is open. */
    private fun backKey() {
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            val sheet = org.robolectric.shadows.ShadowDialog.getShownDialogs().last { it.isShowing }
            (sheet as androidx.activity.ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    private fun closeButton() = composeRule.onNodeWithContentDescription(context.getString(R.string.close))
    private fun dialogShown() = composeRule.onAllNodesWithTagExists(DISCARD_DIALOG)
    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagExists(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    // --- What counts as a change -------------------------------------------------------------------------

    @Test
    fun theShiftEditorHasChangesOnlyAfterAnEditAndNotAfterUndoingIt() {
        setUp()
        val vm = shiftEditor(shiftId)
        assertFalse(vm.uiState.value.hasChanges)
        vm.setNote("other")
        assertTrue(vm.uiState.value.hasChanges)
        vm.setNote("n")
        assertFalse("back to what is saved", vm.uiState.value.hasChanges)
        vm.setStart(LocalTime.of(9, 0))
        assertTrue(vm.uiState.value.hasChanges)

        val fresh = shiftEditor(0)
        assertFalse("a new shift as it opens", fresh.uiState.value.hasChanges)
        fresh.setBreak("30")
        assertTrue(fresh.uiState.value.hasChanges)
    }

    @Test
    fun theBonusEditorHasChangesOnlyAfterAnEdit() {
        setUp()
        val vm = bonusEditor(bonusId)
        assertFalse(vm.uiState.value.hasChanges)
        vm.setAmount("60")
        assertTrue(vm.uiState.value.hasChanges)
        vm.setAmount("50")
        assertFalse(vm.uiState.value.hasChanges)
        vm.setWholeMonth(true)
        assertTrue(vm.uiState.value.hasChanges)

        val fresh = bonusEditor(0)
        assertFalse(fresh.uiState.value.hasChanges)
        fresh.setNote("x")
        assertTrue(fresh.uiState.value.hasChanges)
    }

    @Test
    fun thePaymentFormAsksBeforeItsCloseButtonDropsChanges() {
        val job = Job(id = 1, name = "Cafe", hourlyRateGrosze = 3_000, color = 0)
        val payout = Payout(id = 7, jobId = 1, month = YearMonth.of(2026, 3), amountMinor = 100_000)
        val vm = PayoutEditorViewModel(FakePayoutRepository(listOf(job), listOf(payout)), appScope)
        vm.startEdit(payout, job)
        assertFalse(vm.draft.value!!.hasChanges)
        vm.requestClose()
        assertNull("no changes: closes", vm.draft.value)

        vm.startEdit(payout, job)
        vm.setNote("advance")
        assertTrue(vm.draft.value!!.hasChanges)
        vm.requestClose()
        assertTrue(vm.draft.value!!.confirmDiscard)
        vm.keepEditing()
        assertFalse(vm.draft.value!!.confirmDiscard)
        assertEquals("advance", vm.draft.value!!.note)
        vm.requestClose()
        vm.cancel()
        assertNull(vm.draft.value)

        vm.startNew(job, YearMonth.of(2026, 4))
        assertFalse(vm.draft.value!!.hasChanges)
        vm.setAmount("10")
        assertTrue(vm.draft.value!!.hasChanges)
    }

    // --- The screens -------------------------------------------------------------------------------------

    @Test
    fun theShiftEditorClosesAtOnceWithoutChangesAndAsksWithThem() {
        setUp()
        val vm = shiftEditor(shiftId)
        val applyRate = ApplyRateViewModel(RoomShiftRatesRepository(db, db.shiftDao(), clock), SafetyBackup { }, appScope)
        content { EditShiftScreen(onClose = { closed++ }, onManageJobs = {}, viewModel = vm, applyRate = applyRate) }

        closeButton().performClick()
        assertEquals("unchanged: Close just closes", 1, closed)
        assertFalse(dialogShown())

        composeRule.runOnUiThread { vm.setNote("unsaved") }
        composeRule.waitForIdle()
        closeButton().performClick()
        assertTrue(dialogShown())
        assertEquals(1, closed)
        composeRule.onNodeWithTag(DISCARD_KEEP).performClick()
        assertFalse(dialogShown())
        assertEquals("unsaved", vm.uiState.value.note)

        back()
        assertTrue("system back asks too", dialogShown())
        assertEquals(1, closed)
        composeRule.onNodeWithTag(DISCARD_CONFIRM).performClick()
        assertEquals(2, closed)
        assertEquals("nothing was saved", "n", runBlocking { shifts.getShift(shiftId)!!.note })
    }

    @Test
    fun theBonusEditorAsksOnBackWithChanges() {
        setUp()
        val vm = bonusEditor(bonusId)
        content { EditBonusScreen(onClose = { closed++ }, onManageJobs = {}, viewModel = vm) }
        composeRule.runOnUiThread { vm.setAmount("70") }
        composeRule.waitForIdle()
        back()
        assertTrue(dialogShown())
        composeRule.onNodeWithTag(DISCARD_CONFIRM).performClick()
        assertEquals(1, closed)
        assertEquals(5_000L, runBlocking { bonuses.getBonus(bonusId)!!.amountGrosze })
    }

    @Test
    fun theReceivedPaySheetAsksBeforeDroppingAnEditedPayment() {
        val job = Job(id = 1, name = "Cafe", hourlyRateGrosze = 3_000, color = 0)
        val repository = FakePayoutRepository(listOf(job))
        val vm = PayoutEditorViewModel(repository, appScope)
        val month = YearMonth.of(2026, 3)
        content { ReceivedPaySheet(job = job, month = month, row = null, editor = vm, onDismiss = { closed++ }) }
        composeRule.runOnUiThread { vm.startNew(job, month) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(RECEIVED_PAY_AMOUNT_TAG).performTextInput("1200")

        // The form's Close goes back to the list, after asking.
        composeRule.onNodeWithTag(RECEIVED_PAY_CANCEL_TAG).performClick()
        assertTrue(dialogShown())
        composeRule.onNodeWithTag(DISCARD_KEEP).performClick()
        assertNotNull(vm.draft.value)

        // Back would close the whole sheet: it asks too, and Keep editing leaves everything open.
        backKey()
        assertTrue(dialogShown())
        composeRule.onNodeWithTag(DISCARD_KEEP).performClick()
        assertEquals(0, closed)
        assertEquals("1200", vm.draft.value?.amount)
        composeRule.onNodeWithTag(RECEIVED_PAY_SHEET_TAG).assertExists()

        backKey()
        composeRule.onNodeWithTag(DISCARD_CONFIRM).performClick()
        composeRule.waitUntil(5_000) { closed == 1 }
        assertNull(vm.draft.value)
        assertTrue("nothing was saved", repository.upserts.isEmpty())
    }

    @Test
    fun theJobSheetAsksBeforeDroppingChangesAndNotWithout() {
        setUp()
        val vm = JobsViewModel(jobs)
        val watching = CoroutineScope(Dispatchers.Unconfined).launch { vm.uiState.collect {} }
        try {
            content { JobsScreen(onBack = {}, viewModel = vm) }
            settleUi { vm.uiState.value.jobs.isNotEmpty() }
            val cafe = vm.uiState.value.jobs.single()

            composeRule.runOnUiThread { vm.startEdit(cafe) }
            settleUi { vm.uiState.value.editing != null }
            composeRule.waitForIdle()
            backKey()
            settleUi { vm.uiState.value.editing == null }
            assertFalse("unchanged: no question", dialogShown())

            composeRule.runOnUiThread { vm.startEdit(cafe) }
            settleUi { vm.uiState.value.editing != null }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(JOB_NAME_TAG).performTextInput(" Bar")
            backKey()
            assertTrue(dialogShown())
            composeRule.onNodeWithTag(DISCARD_KEEP).performClick()
            assertNotNull("Keep editing leaves the sheet open", vm.uiState.value.editing)
            composeRule.onNodeWithTag(JOB_NAME_TAG).assert(androidx.compose.ui.test.hasText("Bar", substring = true))

            backKey()
            composeRule.onNodeWithTag(DISCARD_CONFIRM).performClick()
            settleUi { vm.uiState.value.editing == null }
            assertEquals("Cafe", runBlocking { jobs.getJob(cafeId)!!.name })
        } finally {
            watching.cancel()
        }
    }
}

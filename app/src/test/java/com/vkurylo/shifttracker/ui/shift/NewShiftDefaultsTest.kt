package com.vkurylo.shifttracker.ui.shift

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRatesRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.data.sync.SafetyBackup
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.TimedRate
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.SnackbarController
import com.vkurylo.shifttracker.ui.theme.ShiftTrackerTheme
import com.vkurylo.shifttracker.ui.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * A new shift starts at the times of its job's latest shift (08:00–16:00 when the job has none), and follows the
 * job picked until the user sets a time; the editor shows hours like the rest of the app ("7.5 h", not "7:30").
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w411dp-h2400dp")
class NewShiftDefaultsTest {
    @get:Rule val composeRule = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShiftDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private var now = 1_000_000L
    private val clock = WallClock { now++ }
    private val details = JobDetailsSource(db.paySettingsDao())
    private val jobs = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details, clock)
    private val shifts = RoomShiftRepository(db, db.shiftDao(), details, clock)
    private val appScope = CoroutineScope(Dispatchers.Unconfined)
    private var nightsId = 0L
    private var daysId = 0L
    private var freshId = 0L

    @After
    fun tearDown() {
        appScope.cancel()
        db.close()
    }

    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    private fun setUp() = runBlocking {
        nightsId = jobs.upsert(Job(name = "A nights", hourlyRateGrosze = 3_300, color = 0))
        daysId = jobs.upsert(Job(name = "B days", hourlyRateGrosze = 3_000, color = 1))
        freshId = jobs.upsert(Job(name = "C fresh", hourlyRateGrosze = 3_000, color = 2))
        fun shift(job: Long, day: Int, start: LocalTime, end: LocalTime) =
            Shift(jobId = job, date = LocalDate.of(2026, 3, day), start = start, end = end, hourlyRateGrosze = 3_000)
        shifts.upsert(shift(nightsId, 1, t(18), t(2)))
        shifts.upsert(shift(nightsId, 5, t(22), t(6)))
        // Its latest shift by date, but deleted: not used.
        shifts.upsert(shift(nightsId, 9, t(5), t(9))).also { shifts.delete(shifts.getShift(it)!!) }
        shifts.upsert(shift(daysId, 3, t(9, 30), t(17, 45)))
        // Entered later, for an earlier day: the latest day counts, not the latest entry.
        shifts.upsert(shift(daysId, 2, t(7), t(15)))
    }

    private fun settle(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("timed out: ${editor?.uiState?.value}", condition())
    }

    private var editor: EditShiftViewModel? = null

    private fun newShift(): EditShiftViewModel =
        EditShiftViewModel(SavedStateHandle(mapOf("shiftId" to 0L)), shifts, jobs, appScope).also { vm ->
            editor = vm
            settle { !vm.uiState.value.loading && vm.uiState.value.jobs.size == 3 }
        }

    private fun times(vm: EditShiftViewModel) = vm.uiState.value.start to vm.uiState.value.end

    @Test
    fun aNewShiftTakesItsJobsLatestTimes() {
        setUp()
        val vm = newShift()
        assertEquals(nightsId, vm.uiState.value.jobId)
        settle { times(vm) == (t(22) to t(6)) }
        assertFalse("times filled in for the user are not a change", vm.uiState.value.hasChanges)
    }

    @Test
    fun pickingAnotherJobTakesThatJobsTimesAndAJobWithoutShiftsTheUsualOnes() {
        setUp()
        val vm = newShift()
        settle { times(vm) == (t(22) to t(6)) }
        vm.selectJob(vm.uiState.value.jobs.first { it.id == daysId })
        settle { times(vm) == (t(9, 30) to t(17, 45)) }
        vm.selectJob(vm.uiState.value.jobs.first { it.id == freshId })
        settle { times(vm) == (t(8) to t(16)) }
    }

    @Test
    fun timesTheUserSetAreKeptWhenTheJobChanges() {
        setUp()
        val vm = newShift()
        settle { times(vm) == (t(22) to t(6)) }
        vm.setStart(t(20))
        vm.selectJob(vm.uiState.value.jobs.first { it.id == daysId })
        repeat(30) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertEquals(t(20) to t(6), times(vm))
    }

    @Test
    fun withoutAnyShiftsANewShiftIsEightToFour() {
        runBlocking { jobs.upsert(Job(name = "Only", hourlyRateGrosze = 3_000, color = 0)) }
        val vm = EditShiftViewModel(SavedStateHandle(mapOf("shiftId" to 0L)), shifts, jobs, appScope)
        settle { !vm.uiState.value.loading && vm.uiState.value.jobs.isNotEmpty() }
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertEquals(t(8) to t(16), times(vm))
    }

    @Test
    fun anExistingShiftKeepsItsOwnTimes() {
        setUp()
        val id = runBlocking { shifts.upsert(Shift(jobId = nightsId, date = LocalDate.of(2026, 2, 1), start = t(12), end = t(13), hourlyRateGrosze = 3_000)) }
        val vm = EditShiftViewModel(SavedStateHandle(mapOf("shiftId" to id)), shifts, jobs, appScope)
        settle { !vm.uiState.value.loading }
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertEquals(t(12) to t(13), times(vm))
    }

    @Test
    fun theEditorShowsHoursLikeTheRestOfTheApp() {
        runBlocking {
            val job = jobs.upsert(Job(name = "Cafe", hourlyRateGrosze = 3_000, color = 0))
            val evening = TimedRate(4_000, t(14), t(22))
            shifts.upsert(Shift(jobId = job, date = LocalDate.of(2026, 3, 5), start = t(8), end = t(16), unpaidBreakMinutes = 30, hourlyRateGrosze = 3_000, secondRate = evening))
        }
        val id = runBlocking { shifts.getAllShifts().single().id }
        val vm = EditShiftViewModel(SavedStateHandle(mapOf("shiftId" to id)), shifts, jobs, appScope)
        val applyRate = ApplyRateViewModel(RoomShiftRatesRepository(db, db.shiftDao(), clock), SafetyBackup { }, appScope)
        val snackbarHost = SnackbarHostState()
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalSnackbarController provides SnackbarController(snackbarHost, scope)) {
                ShiftTrackerTheme(darkTheme = false, dynamicColor = false) {
                    EditShiftScreen(onClose = {}, onManageJobs = {}, viewModel = vm, applyRate = applyRate)
                }
            }
        }
        settle { !vm.uiState.value.loading }
        composeRule.waitForIdle()
        val paid = Formatters.decimalHours(450, Locale.US)
        assertEquals("7.5", paid)
        composeRule.onNodeWithText("$paid h").assertExists()
        // The rate lines: 6 h worked at the base rate, 2 h at the evening rate (before the break).
        composeRule.onNodeWithText("· 6 h", substring = true).assertExists()
        composeRule.onNodeWithText("· 2 h", substring = true).assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("7:30", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("6:00 h", substring = true).fetchSemanticsNodes().size)
    }
}

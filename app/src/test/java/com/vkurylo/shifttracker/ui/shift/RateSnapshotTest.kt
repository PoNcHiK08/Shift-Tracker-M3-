package com.vkurylo.shifttracker.ui.shift

import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.TimedRate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * B4: a shift keeps the rates saved with it. Changing the job's rate, opening the shift, or picking another job
 * and coming back never puts the job's current rate on it; only "Use current rate" and "Apply to all shifts of
 * this job…" (ApplyRateTest) do.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RateSnapshotTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShiftDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private var now = 1_000_000L
    private val clock = WallClock { now++ }
    private val details = JobDetailsSource(db.paySettingsDao())
    private val jobs = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details, clock)
    private val shifts = RoomShiftRepository(db, db.shiftDao(), details, clock)
    private val appScope = CoroutineScope(Dispatchers.Unconfined)

    private val oldNight = TimedRate(3_500, LocalTime.of(22, 0), LocalTime.of(6, 0))
    private val newNight = TimedRate(4_500, LocalTime.of(23, 0), LocalTime.of(6, 0))
    private val date = LocalDate.of(2026, 3, 5)
    private var cafeId = 0L
    private var shopId = 0L
    private var shiftId = 0L

    @After
    fun tearDown() {
        appScope.cancel()
        if (db.isOpen) db.close()
    }

    private fun setUp() = runBlocking {
        cafeId = jobs.upsert(Job(name = "Cafe", hourlyRateGrosze = 3_000, color = 0, secondRate = oldNight))
        shopId = jobs.upsert(Job(name = "Shop", hourlyRateGrosze = 2_800, color = 1))
        shiftId = shifts.upsert(
            Shift(jobId = cafeId, date = date, start = LocalTime.of(18, 0), end = LocalTime.of(2, 0), hourlyRateGrosze = 3_000, secondRate = oldNight),
        )
    }

    /** The job's rates go up the way the job editor saves them (with pay settings from this month). */
    private fun raiseCafeRate() = runBlocking {
        val cafe = jobs.getJob(cafeId)!!
        jobs.save(cafe.copy(hourlyRateGrosze = 4_000, secondRate = newNight), PaySettings(from = YearMonth.of(2026, 11)))
    }

    private fun stored(): Shift = runBlocking { shifts.getShift(shiftId)!! }

    private fun idle(until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!until() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("timed out", until())
    }

    private fun editor(): EditShiftViewModel =
        EditShiftViewModel(SavedStateHandle(mapOf("shiftId" to shiftId)), shifts, jobs, appScope).also { vm ->
            idle { !vm.uiState.value.loading && vm.uiState.value.jobs.size == 2 }
        }

    private fun saveAndWait(vm: EditShiftViewModel, until: (Shift) -> Boolean) {
        vm.save()
        idle { until(stored()) }
    }

    @Test
    fun changingTheJobsRateLeavesItsShiftsAndTheirEarningsAsTheyWere() = runBlocking {
        setUp()
        val before = stored()
        val earningsBefore = shifts.observeShifts(date, date).first().single().let { ShiftCalculator.shiftEarnings(it) }

        raiseCafeRate()

        assertEquals(before, stored())
        val after = shifts.observeShifts(date, date).first().single()
        assertEquals(4_000L, after.job.hourlyRateGrosze)
        assertEquals(earningsBefore, ShiftCalculator.shiftEarnings(after))
    }

    @Test
    fun openingAndSavingTheShiftKeepsItsStoredRates() {
        setUp()
        raiseCafeRate()
        val vm = editor()
        assertTrue(vm.uiState.value.rateOutdated)
        vm.setNote("edited")
        saveAndWait(vm) { it.note == "edited" }
        assertEquals(3_000L, stored().hourlyRateGrosze)
        assertEquals(oldNight, stored().secondRate)
    }

    @Test
    fun theRateChangingWhileTheEditorIsOpenDoesNotReachTheShift() {
        setUp()
        val vm = editor()
        raiseCafeRate()
        idle { vm.uiState.value.selectedJob?.hourlyRateGrosze == 4_000L }
        assertEquals(3_000L, vm.uiState.value.rateGrosze)
        assertEquals(oldNight, vm.uiState.value.secondRate)
        assertTrue(vm.uiState.value.rateOutdated)
    }

    @Test
    fun pickingAnotherJobAndComingBackBringsBackTheShiftsOwnRates() {
        setUp()
        raiseCafeRate()
        val vm = editor()
        val state = vm.uiState.value
        vm.selectJob(state.jobs.first { it.id == shopId })
        assertEquals("another job: its current rate", 2_800L, vm.uiState.value.rateGrosze)
        vm.selectJob(state.jobs.first { it.id == cafeId })

        assertEquals(3_000L, vm.uiState.value.rateGrosze)
        assertEquals(oldNight, vm.uiState.value.secondRate)
        assertTrue("the editor still offers the current rate", vm.uiState.value.rateOutdated)
        vm.setNote("back")
        saveAndWait(vm) { it.note == "back" }
        assertEquals(3_000L, stored().hourlyRateGrosze)
        assertEquals(oldNight, stored().secondRate)
    }

    @Test
    fun useCurrentRateIsTheExplicitWayToTakeTheNewRate() {
        setUp()
        raiseCafeRate()
        val vm = editor()
        vm.useCurrentRate()
        assertFalse(vm.uiState.value.rateOutdated)
        saveAndWait(vm) { it.hourlyRateGrosze == 4_000L }
        assertEquals(newNight, stored().secondRate)
    }
}

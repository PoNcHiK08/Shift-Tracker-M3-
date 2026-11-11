package com.vkurylo.shifttracker.ui.shift

import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.ShiftEntity
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.local.toDomain
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRatesRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.data.sync.SafetyBackup
import com.vkurylo.shifttracker.domain.calc.RateScope
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ShiftRates
import com.vkurylo.shifttracker.domain.model.TimedRate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/**
 * "Apply to all shifts of this job" on a real database: only this job's live shifts that differ change, each
 * stamped to sync, after a safety backup; Undo puts the old rates back; the shift open in the editor is left to
 * the editor.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ApplyRateTest {
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

    /** Safety backups asked for, with how many shifts had the new rate at that moment (only the one already on it). */
    private val backups = mutableListOf<Pair<String, Int>>()
    private var backupFails = false
    private val safetyBackup = SafetyBackup { reason ->
        if (backupFails) throw java.io.IOException("disk full")
        backups += reason to db.shiftDao().getAll().count { it.hourlyRateGrosze == NEW_RATE }
    }

    private val night = TimedRate(4_500, LocalTime.of(22, 0), LocalTime.of(6, 0))
    private lateinit var cafe: Job
    private var january = 0L
    private var march = 0L
    private var april = 0L
    private var alreadyCurrent = 0L
    private var tombstone = 0L
    private var otherJobShift = 0L
    private val marchDate = LocalDate.of(2026, 3, 5)

    @After
    fun tearDown() {
        appScope.cancel()
        if (db.isOpen) db.close()
    }

    private fun setUp() = runBlocking {
        val cafeId = jobs.upsert(Job(name = "Cafe", hourlyRateGrosze = 3_000, color = 0))
        val otherJobId = jobs.upsert(Job(name = "Shop", hourlyRateGrosze = 3_000, color = 1))
        fun shift(jobId: Long, date: LocalDate, rate: Long = 3_000, second: TimedRate? = null) =
            Shift(jobId = jobId, date = date, start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = rate, secondRate = second, note = "n$date")
        january = shifts.upsert(shift(cafeId, LocalDate.of(2026, 1, 10)))
        march = shifts.upsert(shift(cafeId, marchDate))
        april = shifts.upsert(shift(cafeId, LocalDate.of(2026, 4, 1)))
        alreadyCurrent = shifts.upsert(shift(cafeId, LocalDate.of(2026, 5, 1), rate = NEW_RATE, second = night))
        tombstone = shifts.upsert(shift(cafeId, LocalDate.of(2026, 4, 20)))
        shifts.delete(shifts.getShift(tombstone)!!)
        otherJobShift = shifts.upsert(shift(otherJobId, LocalDate.of(2026, 4, 2)))
        // The job's rate goes up; nothing is waiting to sync any more.
        jobs.upsert(jobs.getJob(cafeId)!!.copy(hourlyRateGrosze = NEW_RATE, secondRate = night))
        cafe = jobs.getJob(cafeId)!!
        db.openHelper.writableDatabase.execSQL("UPDATE shifts SET dirty = 0")
        db.openHelper.writableDatabase.execSQL("UPDATE jobs SET dirty = 0")
        now += 10_000
    }

    private fun rows(): Map<Long, ShiftEntity> = runBlocking {
        listOf(january, march, april, alreadyCurrent, tombstone, otherJobShift).associateWith { db.shiftDao().getAny(it)!! }
    }

    private fun idle(until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!until() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("timed out", until())
    }

    private fun viewModel() = ApplyRateViewModel(rates, safetyBackup, appScope)

    private fun opened(vm: ApplyRateViewModel, openShift: Long? = march): ApplyRateDialog {
        vm.open(cafe, openShift, marchDate)
        idle { vm.dialog.value != null }
        return vm.dialog.value!!
    }

    private fun confirmed(vm: ApplyRateViewModel): ApplyRateEvent = runBlocking {
        vm.confirm()
        withTimeout(10_000) { vm.events.first() }
    }

    /** The row without its rates and sync stamps: what must not change when only the rates do. */
    private fun ShiftEntity.withoutRates() =
        copy(hourlyRateGrosze = 0, secondRateGrosze = null, secondRateFromMinute = 0, secondRateToMinute = 0, updatedAt = 0, dirty = false)

    @Test
    fun onlyThisJobsLiveShiftsThatDifferChangeAndTheyAreStampedToSync() {
        setUp()
        val before = rows()
        val vm = viewModel()
        val dialog = opened(vm, openShift = null)
        assertEquals("January, March and April; not the current one, the tombstone or the other job", 3, dialog.all.count)

        val event = confirmed(vm) as ApplyRateEvent.Applied
        assertEquals(3, event.count)

        val after = rows()
        for (id in listOf(january, march, april)) {
            val row = after.getValue(id)
            assertEquals(NEW_RATE, row.hourlyRateGrosze)
            assertEquals(night.rateGrosze, row.secondRateGrosze)
            assertTrue("changed rows sync", row.dirty)
            assertTrue("a newer updated_at", row.updatedAt > before.getValue(id).updatedAt)
            assertEquals("nothing else about the shift changes", before.getValue(id).withoutRates(), row.withoutRates())
        }
        for (id in listOf(alreadyCurrent, tombstone, otherJobShift)) {
            assertEquals("untouched", before.getValue(id), after.getValue(id))
        }
    }

    @Test
    fun theDialogsCountAndMoneyAreWhatIsApplied() {
        setUp()
        val vm = viewModel()
        val dialog = opened(vm)
        // March is open in the editor: January and April for all, April only from March on.
        assertEquals(2, dialog.all.count)
        assertEquals(1, dialog.fromDate.count)
        vm.setScope(RateScope.FROM_DATE)
        val shown = vm.dialog.value!!.plan
        assertEquals(dialog.fromDate, shown)
        val before = rows()

        val event = confirmed(vm) as ApplyRateEvent.Applied
        val after = rows()
        val changed = after.filter { (id, row) -> row != before.getValue(id) }.keys
        assertEquals(setOf(april), changed)
        assertEquals(shown.count, event.count)
        val moneyApplied = changed.sumOf { after.getValue(it).toDomain().earningsGrosze - before.getValue(it).toDomain().earningsGrosze }
        assertEquals(shown.differenceMinor, moneyApplied)
        // An 8 h day shift: 240 zł → 280 zł.
        assertEquals(4_000L, moneyApplied)
    }

    @Test
    fun allShiftsScopeAppliesWhatItShows() {
        setUp()
        val vm = viewModel()
        val shown = opened(vm).plan
        val before = rows()
        val event = confirmed(vm) as ApplyRateEvent.Applied
        val after = rows()
        val changed = after.filter { (id, row) -> row != before.getValue(id) }.keys
        assertEquals(setOf(january, april), changed)
        assertEquals(shown.count, event.count)
        assertEquals(shown.differenceMinor, changed.sumOf { after.getValue(it).toDomain().earningsGrosze - before.getValue(it).toDomain().earningsGrosze })
    }

    @Test
    fun aSafetyBackupIsTakenBeforeAnythingChanges() {
        setUp()
        val vm = viewModel()
        opened(vm)
        confirmed(vm)
        assertEquals(listOf(SafetyBackup.Reason.BEFORE_RATE_CHANGE to 1), backups)
    }

    @Test
    fun withoutASafetyBackupNothingChanges() {
        setUp()
        backupFails = true
        val before = rows()
        val vm = viewModel()
        opened(vm)
        assertEquals(ApplyRateEvent.Failed, confirmed(vm))
        assertEquals(before, rows())
    }

    @Test
    fun undoPutsTheOldRatesBack() {
        setUp()
        val before = rows()
        val vm = viewModel()
        opened(vm, openShift = null)
        val event = confirmed(vm) as ApplyRateEvent.Applied
        val applied = rows()
        now += 10_000
        var failed = false
        vm.undo(event) { failed = true }
        idle { rows().getValue(april).hourlyRateGrosze == 3_000L }

        val undone = rows()
        assertFalse(failed)
        for (id in listOf(january, march, april)) {
            assertEquals(before.getValue(id).hourlyRateGrosze, undone.getValue(id).hourlyRateGrosze)
            assertEquals(before.getValue(id).secondRateGrosze, undone.getValue(id).secondRateGrosze)
            assertEquals(before.getValue(id).withoutRates(), undone.getValue(id).withoutRates())
            assertTrue(undone.getValue(id).dirty)
            assertTrue(undone.getValue(id).updatedAt > applied.getValue(id).updatedAt)
        }
        for (id in listOf(alreadyCurrent, tombstone, otherJobShift)) assertEquals(before.getValue(id), undone.getValue(id))
    }

    @Test
    fun aFailingUndoReportsInsteadOfCrashing() {
        setUp()
        val vm = viewModel()
        opened(vm, openShift = null)
        val event = confirmed(vm) as ApplyRateEvent.Applied
        db.close()
        var failed = false
        vm.undo(event) { failed = true }
        idle { failed }
    }

    @Test
    fun theOpenEditorKeepsItsUnsavedStateAndItsSaveDoesNotUndoTheOthers() {
        setUp()
        val editor = EditShiftViewModel(SavedStateHandle(mapOf("shiftId" to march)), shifts, jobs, appScope)
        idle { !editor.uiState.value.loading }
        editor.setNote("unsaved note")
        assertTrue(editor.uiState.value.rateOutdated)

        val vm = viewModel()
        opened(vm, openShift = march)
        editor.useCurrentRate() // what the screen does on Apply
        confirmed(vm)

        val stored = rows().getValue(march)
        assertEquals("the open shift is the editor's to save", 3_000L, stored.hourlyRateGrosze)
        assertEquals("n$marchDate", stored.note)
        assertEquals(NEW_RATE, editor.uiState.value.rateGrosze)
        assertEquals("unsaved note", editor.uiState.value.note)

        editor.save()
        idle { rows().getValue(march).note == "unsaved note" }
        val saved = rows()
        assertEquals(NEW_RATE, saved.getValue(march).hourlyRateGrosze)
        assertEquals(NEW_RATE, saved.getValue(january).hourlyRateGrosze)
        assertEquals(NEW_RATE, saved.getValue(april).hourlyRateGrosze)
    }

    @Test
    fun theRatesRepositoryNeverWritesAnotherJobsShiftOrATombstone() = runBlocking {
        setUp()
        val before = rows()
        val written = rates.setRates(cafe.id, mapOf(otherJobShift to ShiftRates(1, null), tombstone to ShiftRates(1, null)))
        assertEquals(0, written)
        assertEquals(before, rows())
        assertTrue(rates.liveShiftsOf(cafe.id).none { it.id == tombstone })
        assertTrue(rates.liveShiftsOf(cafe.id).all { it.jobId == cafe.id })
    }

    private companion object {
        const val NEW_RATE = 3_500L
    }
}

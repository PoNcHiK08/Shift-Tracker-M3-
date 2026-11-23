package com.vkurylo.shifttracker.ui.shift

import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/**
 * The editor opened for a shift that is gone (deleted, also by sync, after the tap that opened it) says so and
 * closes; it never turns into a new-shift form whose Save would store a copy.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class EditorMissingShiftTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShiftDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val details = JobDetailsSource(db.paySettingsDao())
    private val jobs = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details)
    private val shifts = RoomShiftRepository(db, db.shiftDao(), details)
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun aDeletedShiftClosesTheEditorAndSaveStoresNothing() {
        val id = runBlocking {
            val job = jobs.upsert(Job(name = "Cafe", hourlyRateGrosze = 3_000, color = 0))
            shifts.upsert(Shift(jobId = job, date = LocalDate.of(2026, 3, 5), start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3_000))
                .also { shifts.delete(shifts.getShift(it)!!) }
        }
        val vm = EditShiftViewModel(SavedStateHandle(mapOf("shiftId" to id)), shifts, jobs, scope)
        val events = mutableListOf<EditShiftEvent>()
        scope.launch { vm.events.collect { events += it } }
        val deadline = System.currentTimeMillis() + 10_000
        while (events.isEmpty() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertEquals(listOf<EditShiftEvent>(EditShiftEvent.Missing), events)
        assertTrue("not a new-shift form", vm.uiState.value.loading)
        vm.save()
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        fun count(sql: String) = db.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getInt(0) }
        assertEquals("only the tombstone", 1, count("SELECT COUNT(*) FROM shifts"))
        assertEquals(0, count("SELECT COUNT(*) FROM shifts WHERE deleted = 0"))
    }
}

package com.vkurylo.shifttracker.ui

import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomBonusRepository
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomPayoutRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.ui.bonus.EditBonusViewModel
import com.vkurylo.shifttracker.ui.jobs.JobsViewModel
import com.vkurylo.shifttracker.ui.shift.EditShiftViewModel
import com.vkurylo.shifttracker.ui.summary.PayoutEditorViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth

/**
 * Regression: Save pressed twice before the first save finished (a busy database, a slow phone) stored a new
 * record twice: both calls had id 0, so both inserted. Each editor must store a new record exactly once.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DoubleSaveTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShiftDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val details = JobDetailsSource(db.paySettingsDao())
    private val jobs = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details)
    private val shifts = RoomShiftRepository(db, db.shiftDao(), details)
    private val bonuses = RoomBonusRepository(db, db.bonusDao(), details)
    private val payouts = RoomPayoutRepository(db, db.payoutDao(), details)
    private val appScope = CoroutineScope(Dispatchers.Unconfined)
    private val day = LocalDate.of(2026, 11, 2)

    @After
    fun tearDown() = db.close()

    /** Lets the view models' coroutines (on the main looper) run until [condition] holds. */
    private fun settle(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("timed out", condition())
    }

    /** Once the first row is there, gives a second save time to land too, then returns the row count. */
    private fun rowsAfterSaving(count: () -> Int): Int {
        settle { count() >= 1 }
        val until = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return count()
    }

    private fun job(): Job = runBlocking {
        jobs.getJob(jobs.upsert(Job(name = "Kawiarnia", hourlyRateGrosze = 3300, color = 1)))!!
    }

    @Test
    fun aNewShiftSavedTwiceInARowIsStoredOnce() {
        job()
        val editor = EditShiftViewModel(SavedStateHandle(mapOf("shiftId" to 0L, "dateEpochDay" to day.toEpochDay())), shifts, jobs, appScope)
        settle { !editor.uiState.value.loading && editor.uiState.value.jobId != null }

        editor.save()
        editor.save()

        assertEquals(1, rowsAfterSaving { runBlocking { db.shiftDao().getAll().size } })
    }

    @Test
    fun aNewBonusSavedTwiceInARowIsStoredOnce() {
        job()
        val editor = EditBonusViewModel(SavedStateHandle(mapOf("bonusId" to 0L, "dateEpochDay" to day.toEpochDay())), bonuses, jobs, appScope)
        settle { !editor.uiState.value.loading && editor.uiState.value.jobId != null }
        editor.setAmount("75")

        editor.save()
        editor.save()

        assertEquals(1, rowsAfterSaving { runBlocking { db.bonusDao().getAll().size } })
    }

    @Test
    fun aNewPayoutSavedTwiceInARowIsStoredOnce() {
        val job = job()
        val editor = PayoutEditorViewModel(payouts, appScope)
        editor.startNew(job, YearMonth.from(day))
        editor.setAmount("4500")

        editor.save()
        editor.save()

        assertEquals(1, rowsAfterSaving { runBlocking { db.payoutDao().getAll().size } })
    }

    @Test
    fun aNewJobSavedTwiceInARowIsStoredOnce() {
        val jobsScreen = JobsViewModel(jobs)
        val job = Job(name = "Sklep", hourlyRateGrosze = 3100, color = 2)

        jobsScreen.save(job, PaySettings())
        jobsScreen.save(job, PaySettings())

        assertEquals(1, rowsAfterSaving { runBlocking { db.jobDao().getAll().size } })
    }
}

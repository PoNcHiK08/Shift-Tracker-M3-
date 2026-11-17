package com.vkurylo.shifttracker.ui.jobs

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.model.PayType
import com.vkurylo.shifttracker.domain.model.payFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.YearMonth

/**
 * New pay settings "from a month" replace those saved for the months after it, but only once the user has agreed:
 * the job editor asks first, listing those months, and Cancel changes nothing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PayChangeConfirmationTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShiftDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val jobs = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), JobDetailsSource(db.paySettingsDao()))
    private val viewModel = JobsViewModel(jobs)
    private val watching = CoroutineScope(Dispatchers.Unconfined).launch { viewModel.uiState.collect {} }

    private val jul = YearMonth.of(2026, 7)
    private val oct = YearMonth.of(2026, 10)
    private val nov = YearMonth.of(2026, 11)
    private val dec = YearMonth.of(2026, 12)

    @After
    fun tearDown() {
        watching.cancel()
        db.close()
    }

    /** Lets the view model's coroutines (on the main looper) run until [condition] holds. */
    private fun settle(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("timed out", condition())
    }

    /** Lets anything the view model started run to its end. */
    private fun idle() {
        val until = System.currentTimeMillis() + 300
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    /** A job paid by the hour with 10 % from July, 13.71 % from November and a fixed salary from December. */
    private fun jobWithHistory(): Job = runBlocking {
        val id = jobs.save(Job(name = "Office", hourlyRateGrosze = 4000, color = 0), PaySettings())
        val job = jobs.getJob(id)!!
        jobs.save(job, PaySettings(jul, deductionBp = 1000))
        jobs.save(job, PaySettings(nov, deductionBp = 1371))
        jobs.save(job, PaySettings(dec, PayType.FIXED_MONTHLY, 500_000, 1371))
        jobs.getJob(id)!!
    }

    private fun history(job: Job) = runBlocking { jobs.getJob(job.id)!!.payHistory }

    @Test
    fun laterMonthsAreReplacedOnlyAfterConfirmation() {
        val job = jobWithHistory()
        val before = history(job)
        val fromOctober = PaySettings(oct, deductionBp = 1200)

        viewModel.save(job, fromOctober)
        settle { viewModel.uiState.value.pendingPayReplace != null }
        val pending = viewModel.uiState.value.pendingPayReplace!!
        assertEquals(oct, pending.month)
        assertEquals(listOf(nov, dec), pending.laterMonths)
        assertEquals("nothing is written while the question is open", before, history(job))

        viewModel.cancelPayReplace()
        idle()
        assertNull(viewModel.uiState.value.pendingPayReplace)
        assertEquals("Cancel changes nothing", before, history(job))

        viewModel.save(job, fromOctober)
        settle { viewModel.uiState.value.pendingPayReplace != null }
        viewModel.confirmPayReplace()
        settle { history(job) != before }

        val saved = runBlocking { jobs.getJob(job.id)!! }
        assertEquals(listOf(null, jul, oct), saved.payHistory.map { it.from })
        assertEquals("earlier months keep theirs", 1000, saved.payFor(YearMonth.of(2026, 9)).deductionBp)
        assertEquals(1200, saved.payFor(YearMonth.of(2027, 1)).deductionBp)
        assertEquals(PayType.HOURLY, saved.payFor(YearMonth.of(2027, 1)).payType)
        assertNull(viewModel.uiState.value.pendingPayReplace)
    }

    @Test
    fun aNameOrColourChangeAsksNothingAndKeepsTheHistory() {
        val job = jobWithHistory()
        val before = history(job)

        // January: the form holds the settings in force (December's salary), "from January", which has no entry.
        viewModel.save(job.copy(name = "Biuro", color = 5), PaySettings(YearMonth.of(2027, 1), PayType.FIXED_MONTHLY, 500_000, 1371))
        settle { runBlocking { jobs.getJob(job.id)!!.name } == "Biuro" }
        idle()

        assertNull(viewModel.uiState.value.pendingPayReplace)
        assertEquals(before, history(job))
        assertEquals(5, runBlocking { jobs.getJob(job.id)!!.color })
    }
}

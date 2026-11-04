package com.vkurylo.shifttracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.model.PayType
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.payFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** Pay settings history: saving never rewrites other months, and jobs carry their history and activity. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PayHistoryRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShiftDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val details = JobDetailsSource(db.paySettingsDao())
    private val jobs = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details)
    private val shifts = RoomShiftRepository(db.shiftDao(), details)

    @After
    fun tearDown() = db.close()

    private val jul = YearMonth.of(2026, 7)
    private val oct = YearMonth.of(2026, 10)

    @Test
    fun changesFromAMonthKeepEarlierMonths() = runBlocking {
        val id = jobs.save(Job(name = "Office", hourlyRateGrosze = 4000, color = 0, currency = "EUR"), PaySettings())
        val job = jobs.getJob(id)!!
        jobs.save(job, PaySettings(jul, PayType.FIXED_MONTHLY, 300_000))
        jobs.save(job, PaySettings(oct, PayType.FIXED_MONTHLY, 350_000))
        // A second change in the same month replaces that month's entry only.
        jobs.save(job, PaySettings(oct, PayType.FIXED_MONTHLY, 360_000))

        val saved = jobs.getJob(id)!!
        assertEquals(PayType.HOURLY, saved.payFor(YearMonth.of(2026, 6)).payType)
        assertEquals(300_000L, saved.payFor(YearMonth.of(2026, 9)).monthlyAmountMinor)
        assertEquals(360_000L, saved.payFor(YearMonth.of(2026, 12)).monthlyAmountMinor)
        assertEquals(3, saved.payHistory.size)

        // "From the beginning" (a job without entries) replaces the whole history.
        jobs.save(saved, PaySettings(null, PayType.FIXED_MONTHLY, 100_000))
        assertEquals(listOf(PaySettings(null, PayType.FIXED_MONTHLY, 100_000)), jobs.getJob(id)!!.payHistory)
    }

    @Test
    fun observedShiftsCarryTheJobsHistoryAndActivity() = runBlocking {
        val id = jobs.save(Job(name = "Office", hourlyRateGrosze = 4000, color = 0), PaySettings(null, PayType.FIXED_MONTHLY, 500_000))
        shifts.upsert(Shift(jobId = id, date = LocalDate.of(2026, 9, 3), start = LocalTime.of(9, 0), end = LocalTime.of(17, 0), hourlyRateGrosze = 4000))
        shifts.upsert(Shift(jobId = id, date = LocalDate.of(2026, 11, 3), start = LocalTime.of(9, 0), end = LocalTime.of(17, 0), hourlyRateGrosze = 4000))

        val job = jobs.observeJobs().first().single()
        assertEquals(LocalDate.of(2026, 9, 3), job.firstEntry)
        assertEquals(LocalDate.of(2026, 11, 3), job.lastEntry)
        val shift = shifts.observeShifts(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).first().single()
        assertEquals(job.payHistory, shift.job.payHistory)
        assertEquals(0L, ShiftCalculator.shiftEarnings(shift))
        // October has no entry but lies between September and November: its salary counts.
        assertEquals(500_000L, ShiftCalculator.salary(job, YearMonth.of(2026, 10)))
        assertEquals(0L, ShiftCalculator.salary(job, YearMonth.of(2026, 12)))
    }
}

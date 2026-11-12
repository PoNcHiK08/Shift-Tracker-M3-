package com.vkurylo.shifttracker.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRatesRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.domain.calc.CurrentRateChange
import com.vkurylo.shifttracker.domain.calc.RateScope
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/**
 * A few hundred shifts changed at once ("Apply to all shifts of this job") are ordinary changes to sync: they go
 * up in chunks, within the automatic daily write limit, and a smaller limit leaves the rest for the next sync.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ManyRateChangesSyncTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShiftDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private var now = 1_000_000L
    private val clock = WallClock { now }
    private val details = JobDetailsSource(db.paySettingsDao())
    private val jobs = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details, clock)
    private val shifts = RoomShiftRepository(db, db.shiftDao(), details, clock)
    private val rates = RoomShiftRatesRepository(db, db.shiftDao(), clock)
    private val cloud = FakeRemoteStore()
    private val engine = SyncEngine(db, cloud, InMemorySyncStateStore(), { }, clock)
    private val account = CloudAccount(uid = "uid-test", email = "test@example.com")

    @After
    fun tearDown() = db.close()

    private fun dirtyShifts() = runBlocking { db.syncDao().dirtyShifts().size }

    private fun threeHundredChangedShifts(): Long = runBlocking {
        val jobId = jobs.upsert(Job(name = "Cafe", hourlyRateGrosze = 3_000, color = 0))
        val start = LocalDate.of(2025, 1, 1)
        repeat(300) { shifts.upsert(Shift(jobId = jobId, date = start.plusDays(it.toLong()), start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3_000)) }
        engine.completeSignIn(account, (engine.prepareSignIn(account) as SignInPlan.Ready).choice)
        assertEquals(0, dirtyShifts())
        now += 1_000
        jobs.upsert(jobs.getJob(jobId)!!.copy(hourlyRateGrosze = 3_500))
        val job = jobs.getJob(jobId)!!
        val plan = CurrentRateChange.plan(job, rates.liveShiftsOf(jobId), RateScope.ALL, start, null)
        assertEquals(300, rates.setRates(jobId, plan.newRates))
        jobId
    }

    @Test
    fun threeHundredChangedShiftsGoUpWithinTheDailyAutomaticLimit() = runBlocking {
        threeHundredChangedShifts()
        assertEquals(300, dirtyShifts())
        var counted = 0
        val result = engine.sync(maxWrites = SyncController.DAILY_AUTOMATIC_WRITES) { counted += it } as SyncResult.Synced
        assertFalse(result.limited)
        assertEquals(0, dirtyShifts())
        assertTrue("the shifts and the job", counted >= 300)
        assertTrue(cloud.records(account.uid).filterIsInstance<RemoteShift>().all { it.hourlyRateGrosze == 3_500L })
    }

    @Test
    fun aSmallerLimitLeavesTheRestForTheNextSync() = runBlocking {
        threeHundredChangedShifts()
        val first = engine.sync(maxWrites = 250) as SyncResult.Synced
        assertTrue(first.limited)
        assertTrue(dirtyShifts() in 1..299)
        val rest = engine.sync(maxWrites = SyncController.DAILY_AUTOMATIC_WRITES) as SyncResult.Synced
        assertFalse(rest.limited)
        assertEquals(0, dirtyShifts())
    }
}

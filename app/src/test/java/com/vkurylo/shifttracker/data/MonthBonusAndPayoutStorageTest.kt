package com.vkurylo.shifttracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.local.PayoutEntity
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomBonusRepository
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomPayoutRepository
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Payout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Whole-month bonuses (a flag on a bonus, kept on the 1st of its month) and received-pay payouts (their own
 * table): how they are stored, read, edited and deleted.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MonthBonusAndPayoutStorageTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShiftDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private var now = 1_000_000L
    private val clock = WallClock { now }
    private val details = JobDetailsSource(db.paySettingsDao())
    private val jobs = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details, clock)
    private val bonuses = RoomBonusRepository(db, db.bonusDao(), details, clock)
    private val payouts = RoomPayoutRepository(db, db.payoutDao(), details, clock)

    private val november = YearMonth.of(2026, 11)

    @After
    fun tearDown() = db.close()

    private suspend fun job(name: String = "Burger King", currency: String = "PLN") =
        jobs.upsert(Job(name = name, hourlyRateGrosze = 3300, color = 1, currency = currency))

    private suspend fun payout(
        jobId: Long,
        month: YearMonth = november,
        amountMinor: Long = 450_000,
        note: String? = null,
        receivedOn: LocalDate? = null,
    ) = payouts.upsert(Payout(jobId = jobId, month = month, amountMinor = amountMinor, note = note, receivedOn = receivedOn))

    // --- Whole-month bonuses ---------------------------------------------------------------------------

    @Test
    fun monthBonusIsKeptOnTheFirstOfItsMonthWithItsFlag() = runBlocking {
        val jobId = job()
        val id = bonuses.upsert(Bonus(jobId = jobId, date = LocalDate.of(2026, 11, 17), amountGrosze = 50_000, note = "Premia kwartalna", wholeMonth = true))

        val stored = db.bonusDao().getAny(id)!!
        assertTrue(stored.wholeMonth)
        assertEquals(LocalDate.of(2026, 11, 1).toEpochDay(), stored.dateEpochDay)
        val bonus = bonuses.getBonus(id)!!
        assertEquals(LocalDate.of(2026, 11, 1), bonus.date)
        assertEquals(november, bonus.month)
        assertTrue(bonus.wholeMonth)
        assertEquals("Premia kwartalna", bonus.note)

        // It is found by the month's range, like any bonus of that month.
        assertEquals(listOf(id), bonuses.observeBonuses(november.atDay(1), november.atEndOfMonth()).first().map { it.bonus.id })
        assertTrue(bonuses.observeBonuses(YearMonth.of(2026, 10).atDay(1), YearMonth.of(2026, 10).atEndOfMonth()).first().isEmpty())
    }

    @Test
    fun aBonusOnADayStaysOnItsDay() = runBlocking {
        val jobId = job()
        val id = bonuses.upsert(Bonus(jobId = jobId, date = LocalDate.of(2026, 11, 17), amountGrosze = 5_000))

        val stored = db.bonusDao().getAny(id)!!
        assertFalse(stored.wholeMonth)
        assertEquals(LocalDate.of(2026, 11, 17).toEpochDay(), stored.dateEpochDay)
        assertFalse(bonuses.getBonus(id)!!.wholeMonth)
    }

    @Test
    fun editingAMonthBonusKeepsItsFlagAndSyncId() = runBlocking {
        val jobId = job()
        val id = bonuses.upsert(Bonus(jobId = jobId, date = november.atDay(1), amountGrosze = 50_000, wholeMonth = true))
        val before = db.bonusDao().getAny(id)!!

        now += 1000
        bonuses.upsert(bonuses.getBonus(id)!!.copy(amountGrosze = 60_000))

        val after = db.bonusDao().getAny(id)!!
        assertTrue(after.wholeMonth)
        assertEquals(60_000L, after.amountGrosze)
        assertEquals(before.syncId, after.syncId)
        assertEquals(now, after.updatedAt)
        assertTrue(after.dirty)
    }

    @Test
    fun aBonusCanBeSwitchedBetweenADayAndAMonth() = runBlocking {
        val jobId = job()
        val id = bonuses.upsert(Bonus(jobId = jobId, date = LocalDate.of(2026, 11, 17), amountGrosze = 5_000))

        now += 1000
        bonuses.upsert(bonuses.getBonus(id)!!.copy(wholeMonth = true))
        assertTrue(bonuses.getBonus(id)!!.wholeMonth)
        assertEquals(LocalDate.of(2026, 11, 1), bonuses.getBonus(id)!!.date)

        now += 1000
        bonuses.upsert(bonuses.getBonus(id)!!.copy(wholeMonth = false, date = LocalDate.of(2026, 11, 20)))
        assertFalse(bonuses.getBonus(id)!!.wholeMonth)
        assertEquals(LocalDate.of(2026, 11, 20), bonuses.getBonus(id)!!.date)
    }

    @Test
    fun aMonthBonusCountsAsJobActivityOnTheFirstOfItsMonth() = runBlocking {
        val jobId = job()
        bonuses.upsert(Bonus(jobId = jobId, date = LocalDate.of(2026, 11, 25), amountGrosze = 50_000, wholeMonth = true))

        val activity = db.paySettingsDao().getActivity().single()
        assertEquals(LocalDate.of(2026, 11, 1).toEpochDay(), activity.firstDay)
        assertEquals(LocalDate.of(2026, 11, 1).toEpochDay(), activity.lastDay)
    }

    @Test
    fun deletingAMonthBonusIsATombstoneAndUndoBringsBackTheSameRecord() = runBlocking {
        val jobId = job()
        val id = bonuses.upsert(Bonus(jobId = jobId, date = november.atDay(1), amountGrosze = 50_000, wholeMonth = true))
        val original = bonuses.getBonus(id)!!
        val syncId = db.bonusDao().getAny(id)!!.syncId

        now += 1000
        bonuses.delete(original)
        assertNull(bonuses.getBonus(id))
        assertTrue(db.bonusDao().getAny(id)!!.deleted)

        now += 1000
        bonuses.upsert(original)
        val restored = db.bonusDao().getAny(id)!!
        assertFalse(restored.deleted)
        assertTrue(restored.wholeMonth)
        assertEquals(syncId, restored.syncId)
    }

    // --- Payouts ---------------------------------------------------------------------------------------

    @Test
    fun aPayoutIsStoredWithItsMonthAmountNoteAndReceivedDate() = runBlocking {
        val jobId = job()
        val id = payout(jobId, november, 450_000, "Przelew za listopad", LocalDate.of(2026, 12, 2))

        val stored = db.payoutDao().getAny(id)!!
        assertEquals(202611, stored.month)
        assertEquals(450_000L, stored.amountMinor)
        assertEquals(LocalDate.of(2026, 12, 2).toEpochDay(), stored.receivedEpochDay)
        assertTrue(UUID.fromString(stored.syncId).toString() == stored.syncId)
        assertEquals(now, stored.updatedAt)
        assertEquals(false to true, stored.deleted to stored.dirty) // live, waiting for upload

        assertEquals(
            Payout(id = id, jobId = jobId, month = november, amountMinor = 450_000, note = "Przelew za listopad", receivedOn = LocalDate.of(2026, 12, 2)),
            payouts.getPayout(id),
        )
    }

    @Test
    fun aPayoutWithoutNoteOrReceivedDateStoresNulls() = runBlocking {
        val jobId = job()
        val id = payouts.upsert(Payout(jobId = jobId, month = november, amountMinor = 100, note = "   "))

        val stored = db.payoutDao().getAny(id)!!
        assertNull(stored.note)
        assertNull(stored.receivedEpochDay)
        assertNull(payouts.getPayout(id)!!.receivedOn)
    }

    @Test
    fun severalPayoutsForTheSameJobAndMonthAreSeparateRecords() = runBlocking {
        val jobId = job()
        val first = payout(jobId, amountMinor = 400_000)
        val second = payout(jobId, amountMinor = 50_000, note = "reszta")

        assertNotEquals(db.payoutDao().getAny(first)!!.syncId, db.payoutDao().getAny(second)!!.syncId)
        val listed = payouts.observePayouts(november, november).first()
        assertEquals(listOf(first, second), listed.map { it.payout.id })
        assertEquals(450_000L, listed.sumOf { it.payout.amountMinor })
    }

    @Test
    fun payoutsAreFoundByTheMonthTheyWereEarnedForNotWhenTheyArrived() = runBlocking {
        val jobId = job()
        val october = payout(jobId, YearMonth.of(2026, 10), receivedOn = LocalDate.of(2026, 11, 2))
        val december2025 = payout(jobId, YearMonth.of(2025, 12))
        val january = payout(jobId, YearMonth.of(2026, 1))

        assertTrue(payouts.observePayouts(november, november).first().isEmpty())
        assertEquals(listOf(october), payouts.observePayouts(YearMonth.of(2026, 10), YearMonth.of(2026, 10)).first().map { it.payout.id })
        // A range across a year boundary: yyyymm keys 202512..202601.
        assertEquals(
            listOf(december2025, january),
            payouts.observePayouts(YearMonth.of(2025, 12), YearMonth.of(2026, 1)).first().map { it.payout.id },
        )
    }

    @Test
    fun payoutsComeWithTheirJob() = runBlocking {
        val burger = job("Burger King")
        val office = job("Biuro", currency = "EUR")
        payout(burger)
        payout(office, amountMinor = 3_000_00)

        val listed = payouts.observePayouts(november, november).first()
        assertEquals(listOf("Burger King" to "PLN", "Biuro" to "EUR"), listed.map { it.job.name to it.job.currency })
    }

    @Test
    fun aPayoutIsNotJobActivityButLocksTheCurrency() = runBlocking {
        val jobId = job()
        assertEquals(0, jobs.entryCount(jobId))

        val id = payout(jobId)

        // Pay calculation: a payout is not an entry of the job's fixed-salary span.
        assertTrue(db.paySettingsDao().getActivity().isEmpty())
        // Currency: the job now has an entry, so its currency can't change under the amounts.
        assertEquals(1, jobs.entryCount(jobId))

        payouts.delete(payouts.getPayout(id)!!)
        assertEquals(0, jobs.entryCount(jobId))
    }

    @Test
    fun editingAPayoutKeepsItsSyncIdAndMovesTheChangeTimeForwardEvenWhenTheClockGoesBack() = runBlocking {
        val jobId = job()
        val id = payout(jobId)
        val before = db.payoutDao().getAny(id)!!

        now -= 60_000
        payouts.upsert(payouts.getPayout(id)!!.copy(amountMinor = 460_000, note = "z dopłatą"))

        val after = db.payoutDao().getAny(id)!!
        assertEquals(before.syncId, after.syncId)
        assertEquals(before.updatedAt + 1, after.updatedAt)
        assertEquals(460_000L, after.amountMinor)
        assertTrue(after.dirty)
    }

    @Test
    fun deletedPayoutIsATombstoneAndUndoBringsBackTheSameRecord() = runBlocking {
        val jobId = job()
        val id = payout(jobId, receivedOn = LocalDate.of(2026, 12, 2))
        val original = payouts.getPayout(id)!!
        val syncId = db.payoutDao().getAny(id)!!.syncId

        now += 1000
        payouts.delete(original)

        assertNull(payouts.getPayout(id))
        assertTrue(payouts.observePayouts(november, november).first().isEmpty())
        val tombstone = db.payoutDao().getAny(id)!!
        assertTrue(tombstone.deleted)
        assertTrue(tombstone.dirty)
        assertEquals(now, tombstone.updatedAt)

        now += 1000
        payouts.upsert(original) // the snackbar's Undo

        assertEquals(original, payouts.getPayout(id))
        val restored = db.payoutDao().getAny(id)!!
        assertFalse(restored.deleted)
        assertEquals(syncId, restored.syncId)
        assertEquals(now, restored.updatedAt)
    }

    @Test
    fun aRowWithAnImpossibleMonthIsSkippedInsteadOfCrashingAScreen() = runBlocking {
        val jobId = job()
        val good = payout(jobId)
        db.payoutDao().insertAll(listOf(PayoutEntity(jobId = jobId, month = 202613, amountMinor = 1, note = null, receivedEpochDay = null)))
        val bad = db.payoutDao().getAll().first { it.month == 202613 }.id

        assertEquals(listOf(good), payouts.observePayouts(YearMonth.of(2026, 1), YearMonth.of(2027, 12)).first().map { it.payout.id })
        assertNull(payouts.getPayout(bad))
    }

    @Test
    fun monthKeys() {
        assertEquals(202611, Payout.keyOf(november))
        assertEquals(november, Payout.monthFromKey(202611))
        assertEquals(YearMonth.of(1000, 1), Payout.monthFromKey(100001))
        assertEquals(YearMonth.of(9999, 12), Payout.monthFromKey(999912))
        for (bad in listOf(0, -202611, 202600, 202613, 99912, 1_000_001, 20261)) {
            assertNull(bad.toString(), Payout.monthFromKey(bad))
        }
    }
}

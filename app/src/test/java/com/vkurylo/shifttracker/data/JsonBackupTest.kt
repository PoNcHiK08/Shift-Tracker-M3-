package com.vkurylo.shifttracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.backup.InvalidBackupException
import com.vkurylo.shifttracker.data.backup.JsonBackupRepository
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.toDomain
import com.vkurylo.shifttracker.data.local.toEntity
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.TimedRate
import com.vkurylo.shifttracker.domain.repository.BackupCounts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class JsonBackupTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbs = mutableListOf<ShiftDatabase>()

    private fun newDb() = Room.inMemoryDatabaseBuilder(context, ShiftDatabase::class.java)
        .allowMainThreadQueries().build().also { dbs += it }

    @After
    fun tearDown() = dbs.forEach { it.close() }

    @Test
    fun exportThenImportRestoresEverything() = runBlocking {
        val source = newDb()
        val night = TimedRate(3700, LocalTime.of(23, 0), LocalTime.of(6, 0))
        source.jobDao().upsert(Job(id = 1, name = "Burger King", hourlyRateGrosze = 3300, color = 42, secondRate = night).toEntity())
        source.shiftDao().upsert(
            Shift(jobId = 1, date = LocalDate.of(2026, 8, 30), start = LocalTime.of(20, 0), end = LocalTime.of(1, 0),
                hourlyRateGrosze = 3300, secondRate = night, note = "late").toEntity(),
        )
        source.bonusDao().upsert(Bonus(jobId = 1, date = LocalDate.of(2026, 8, 31), amountGrosze = 7500, note = "Premia").toEntity())

        val json = JsonBackupRepository(source).exportJson()

        val target = newDb()
        val counts = JsonBackupRepository(target).importJson(json)

        assertEquals(BackupCounts(jobs = 1, shifts = 1, bonuses = 1), counts)
        assertEquals(night, target.jobDao().getAll().single().toDomain().secondRate)
        val shift = target.shiftDao().getAll().single().toDomain()
        assertEquals(17300L, shift.earningsGrosze) // 3 h * 33 + 2 h * 37
        val bonus = target.bonusDao().getAll().single().toDomain()
        assertEquals(7500L, bonus.amountGrosze)
        assertEquals("Premia", bonus.note)
        assertEquals(LocalDate.of(2026, 8, 31), bonus.date)
    }

    @Test
    fun importsOlderBackupWithoutBonusesOrSecondRates() = runBlocking {
        val v1 = """
            {"format":"shift-tracker-backup","version":1,"exported_at":"2026-11-01T10:00:00Z",
             "jobs":[{"id":5,"name":"Cafe","hourly_rate_grosze":3000,"color":1}],
             "shifts":[{"job_id":5,"date":"2026-10-01","start":"08:00","end":"16:00","hourly_rate_grosze":3000}]}
        """.trimIndent()
        val db = newDb()
        val counts = JsonBackupRepository(db).importJson(v1)
        assertEquals(BackupCounts(1, 1, 0), counts)
        assertEquals(24000L, db.shiftDao().getAll().single().toDomain().earningsGrosze)
    }

    @Test
    fun rejectsBonusForUnknownJob() {
        val bad = """
            {"format":"shift-tracker-backup","version":3,"exported_at":"x","jobs":[],"shifts":[],
             "bonuses":[{"job_id":9,"date":"2026-08-31","amount_grosze":7500}]}
        """.trimIndent()
        assertThrows(InvalidBackupException::class.java) {
            runBlocking { JsonBackupRepository(newDb()).importJson(bad) }
        }
    }
}

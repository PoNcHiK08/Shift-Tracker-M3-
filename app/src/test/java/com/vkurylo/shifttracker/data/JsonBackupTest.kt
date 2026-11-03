package com.vkurylo.shifttracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.backup.JsonBackupRepository
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.toDomain
import com.vkurylo.shifttracker.data.local.toEntity
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.TimedRate
import com.vkurylo.shifttracker.domain.calc.MoneyParser
import com.vkurylo.shifttracker.domain.repository.BackupCounts
import com.vkurylo.shifttracker.domain.repository.InvalidBackupException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    // --- Versions -------------------------------------------------------------------------------

    @Test
    fun importsVersion2WithSecondRates() = runBlocking {
        val v2 = """
            {"format":"shift-tracker-backup","version":2,"exported_at":"2026-11-01T10:00:00Z",
             "jobs":[{"id":3,"name":"Burger King","hourly_rate_grosze":3300,"color":1,
                      "second_rate":{"rate_grosze":3700,"from":"23:00","to":"06:00"}}],
             "shifts":[{"job_id":3,"date":"2026-08-30","start":"20:00","end":"01:00","hourly_rate_grosze":3300,
                        "second_rate":{"rate_grosze":3700,"from":"23:00","to":"06:00"}}]}
        """.trimIndent()
        val db = newDb()
        assertEquals(BackupCounts(1, 1, 0), JsonBackupRepository(db).importJson(v2))
        assertEquals(17300L, db.shiftDao().getAll().single().toDomain().earningsGrosze)
    }

    @Test
    fun rejectsNewerVersionWithItsOwnReason() = runBlocking {
        val db = seededDb()
        val before = snapshot(db)
        val e = rejected(db, backup(version = 4))
        assertEquals(InvalidBackupException.Reason.NEWER_VERSION, e.reason)
        assertEquals(before, snapshot(db))
    }

    @Test
    fun rejectsUnknownOldVersion() = runBlocking {
        assertEquals(InvalidBackupException.Reason.INVALID, rejected(newDb(), backup(version = 0)).reason)
    }

    // --- Values ---------------------------------------------------------------------------------

    @Test
    fun rejectsInvalidValuesAndKeepsTheDatabase() = runBlocking {
        val tooMuch = MoneyParser.MAX_GROSZE + 1
        val cases = mapOf(
            "duplicate job ids" to backup(jobs = job(1) + "," + job(1)),
            "job id 0" to backup(jobs = job(0), shifts = "", bonuses = ""),
            "blank job name" to backup(jobs = job(1, name = " ")),
            "negative rate" to backup(jobs = job(1, rate = -1)),
            "rate above max" to backup(jobs = job(1, rate = tooMuch)),
            "empty second-rate window" to backup(jobs = job(1, second = """{"rate_grosze":3700,"from":"23:00","to":"23:00"}""")),
            "second rate above max" to backup(jobs = job(1, second = """{"rate_grosze":$tooMuch,"from":"23:00","to":"06:00"}""")),
            "shift rate above max" to backup(shifts = shift(rate = tooMuch)),
            "negative break" to backup(shifts = shift(breakMinutes = -5)),
            "break as long as the shift" to backup(shifts = shift(start = "08:00", end = "16:00", breakMinutes = 480)),
            "bad time" to backup(shifts = shift(start = "25:00")),
            "bad date" to backup(shifts = shift(date = "2026-02-30")),
            "shift for unknown job" to backup(shifts = shift(jobId = 9)),
            "zero bonus" to backup(bonuses = bonus(amount = 0)),
            "bonus above max" to backup(bonuses = bonus(amount = tooMuch)),
        )
        val db = seededDb()
        val before = snapshot(db)
        for ((name, json) in cases) {
            val e = rejected(db, json, name)
            assertEquals(name, InvalidBackupException.Reason.INVALID, e.reason)
            assertEquals("database changed after: $name", before, snapshot(db))
        }
    }

    @Test
    fun acceptsBoundaryValues() = runBlocking {
        val json = backup(
            jobs = job(1, rate = MoneyParser.MAX_GROSZE) + "," + job(2, rate = 0),
            shifts = shift(start = "08:00", end = "16:00", breakMinutes = 479) + "," + shift(start = "00:00", end = "00:00", breakMinutes = 1439),
            bonuses = bonus(amount = MoneyParser.MAX_GROSZE),
        )
        assertEquals(BackupCounts(2, 2, 1), JsonBackupRepository(newDb()).importJson(json))
    }

    // --- Atomicity ------------------------------------------------------------------------------

    /** A write failing half-way (after the old data was deleted) must roll everything back. */
    @Test
    fun failedWriteRollsBackToTheOldData() = runBlocking {
        val db = seededDb()
        val before = snapshot(db)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_bonus_insert BEFORE INSERT ON bonuses BEGIN SELECT RAISE(ABORT, 'simulated failure'); END",
        )
        try {
            JsonBackupRepository(db).importJson(backup())
            fail("the simulated write failure was not reported")
        } catch (e: InvalidBackupException) {
            fail("valid backup was rejected: ${e.message}")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("simulated failure"))
        }
        assertEquals(before, snapshot(db))
        assertNotEquals(0, before.first.size)
    }

    // --- Helpers --------------------------------------------------------------------------------

    private suspend fun seededDb(): ShiftDatabase = newDb().also { db ->
        db.jobDao().upsert(Job(id = 7, name = "Kawiarnia", hourlyRateGrosze = 3050, color = 3).toEntity())
        db.shiftDao().upsert(
            Shift(jobId = 7, date = LocalDate.of(2026, 10, 1), start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3050).toEntity(),
        )
        db.bonusDao().upsert(Bonus(jobId = 7, date = LocalDate.of(2026, 10, 2), amountGrosze = 5000).toEntity())
    }

    private suspend fun snapshot(db: ShiftDatabase) =
        Triple(db.jobDao().getAll(), db.shiftDao().getAll(), db.bonusDao().getAll())

    private suspend fun rejected(db: ShiftDatabase, json: String, case: String = json): InvalidBackupException = try {
        JsonBackupRepository(db).importJson(json)
        throw AssertionError("backup was accepted: $case")
    } catch (e: InvalidBackupException) {
        e
    }

    private fun backup(
        version: Int = 3,
        jobs: String = job(1),
        shifts: String = shift(),
        bonuses: String = bonus(),
    ) = """{"format":"shift-tracker-backup","version":$version,"exported_at":"x","jobs":[$jobs],"shifts":[$shifts],"bonuses":[$bonuses]}"""

    private fun job(id: Long, name: String = "Job $id", rate: Long = 3300, second: String? = null) =
        """{"id":$id,"name":"$name","hourly_rate_grosze":$rate,"color":1${second?.let { ",\"second_rate\":$it" } ?: ""}}"""

    private fun shift(
        jobId: Long = 1,
        date: String = "2026-10-05",
        start: String = "08:00",
        end: String = "16:00",
        breakMinutes: Int = 30,
        rate: Long = 3300,
    ) = """{"job_id":$jobId,"date":"$date","start":"$start","end":"$end","unpaid_break_minutes":$breakMinutes,"hourly_rate_grosze":$rate}"""

    private fun bonus(jobId: Long = 1, amount: Long = 7500) =
        """{"job_id":$jobId,"date":"2026-10-06","amount_grosze":$amount}"""
}

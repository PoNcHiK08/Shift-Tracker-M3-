package com.vkurylo.shifttracker.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.local.Migration6To7
import com.vkurylo.shifttracker.data.local.PayoutEntity
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.toDomain
import com.vkurylo.shifttracker.domain.model.TimedRate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalTime
import java.util.UUID

/**
 * Builds a database exactly as an older version of the app created it (from the exported schema),
 * fills it with data, then opens it with the current Room database: the migrations must keep every
 * row and Room's schema validation must pass.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DatabaseMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /** Creates the database exactly as [version] of the app did, from its exported schema. */
    private fun createDatabase(version: Int): SQLiteDatabase {
        val schemaFile = File("schemas/com.vkurylo.shifttracker.data.local.ShiftDatabase/$version.json")
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject["database"]!!.jsonObject
        val dbFile = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        schema["entities"]!!.jsonArray.forEach { entity ->
            val obj = entity.jsonObject
            val table = obj["tableName"]!!.jsonPrimitive.content
            db.execSQL(obj["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            obj["indices"]?.jsonArray?.forEach { index ->
                db.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
        schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
        return db
    }

    private fun openCurrent(migration6To7: Migration = ShiftDatabase.MIGRATION_6_7) =
        Room.databaseBuilder(context, ShiftDatabase::class.java, dbName)
            .addMigrations(migration6To7)
            .allowMainThreadQueries()
            .build()

    private fun createVersion1Database() {
        val db = createDatabase(1)
        db.execSQL("INSERT INTO jobs (id, name, hourly_rate_grosze, color) VALUES (1, 'Burger King', 3300, -1)")
        db.execSQL(
            "INSERT INTO shifts (id, job_id, date_epoch_day, start_minute, end_minute, unpaid_break_minutes, " +
                "hourly_rate_grosze, note, external_uid) VALUES (1, 1, 20400, 480, 960, 30, 3300, 'old shift', NULL)",
        )
        db.close()
    }

    @Test
    fun migrateFrom1ToLatestKeepsAllData() = runBlocking {
        createVersion1Database()

        val db = openCurrent()
        try {
            val jobs = db.jobDao().getAll().map { it.toDomain() }
            val shifts = db.shiftDao().getAll().map { it.toDomain() }

            assertEquals(1, jobs.size)
            assertEquals("Burger King", jobs.single().name)
            assertNull(jobs.single().secondRate)

            assertEquals(1, shifts.size)
            val shift = shifts.single()
            assertEquals(LocalTime.of(8, 0), shift.start)
            assertEquals(LocalTime.of(16, 0), shift.end)
            assertEquals("old shift", shift.note)
            assertNull(shift.secondRate)
            assertEquals(3300L * 15 / 2, shift.earningsGrosze) // 7.5 h * 33 zł

            // v3 adds an empty bonuses table.
            assertEquals(0, db.bonusDao().getAll().size)

            // Defaults written by the migration for the new window columns.
            val raw = db.jobDao().getAll().single()
            assertEquals(23 * 60, raw.secondRateFromMinute)
            assertEquals(6 * 60, raw.secondRateToMinute)

            // v4: jobs made before currencies are PLN.
            assertEquals("PLN", jobs.single().currency)
            // v5: no pay history, which means hourly as before.
            assertEquals(0, db.paySettingsDao().getAll().size)
            // v6: the deduction column exists, 0 by default (no deductions, gross = net).
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO job_pay_settings (job_id, from_month, pay_type, monthly_amount_minor) VALUES (1, 0, 'HOURLY', 0)",
            )
            assertEquals(0, db.paySettingsDao().getAll().single().deductionBp)
            // v7: sync metadata on every row.
            assertTrue(isUuid(raw.syncId))
            assertTrue(isUuid(db.shiftDao().getAll().single().syncId))
            // v8: no payouts yet.
            assertEquals(0, db.payoutDao().getAll().size)
        } finally {
            db.close()
        }
    }

    /**
     * v6 -> v7 adds sync metadata only: every row, id, job link, pay setting and amount stays; each synced
     * row gets its own UUID, the migration time as its change time, not deleted and not yet uploaded.
     */
    @Test
    fun migrateFrom6To7KeepsDataAndAddsSyncMetadata(): Unit = runBlocking {
        createDatabase(6).apply {
            execSQL("INSERT INTO jobs (id, name, hourly_rate_grosze, color, second_rate_grosze, second_rate_from_minute, second_rate_to_minute, currency) VALUES (1, 'Burger King', 3300, -1, 3700, 1380, 360, 'PLN')")
            execSQL("INSERT INTO jobs (id, name, hourly_rate_grosze, color, currency) VALUES (5, 'Biuro', 0, -2, 'EUR')")
            execSQL("INSERT INTO job_pay_settings (job_id, from_month, pay_type, monthly_amount_minor, deduction_bp) VALUES (5, 0, 'FIXED_MONTHLY', 450000, 1371)")
            execSQL("INSERT INTO shifts (id, job_id, date_epoch_day, start_minute, end_minute, unpaid_break_minutes, hourly_rate_grosze, note, external_uid, second_rate_grosze, second_rate_from_minute, second_rate_to_minute) VALUES (10, 1, 20400, 1200, 60, 30, 3300, 'late', 'uid-1@cal', 3700, 1380, 360)")
            execSQL("INSERT INTO shifts (id, job_id, date_epoch_day, start_minute, end_minute, unpaid_break_minutes, hourly_rate_grosze, note, external_uid) VALUES (11, 1, 20401, 480, 960, 0, 3300, NULL, NULL)")
            execSQL("INSERT INTO shifts (id, job_id, date_epoch_day, start_minute, end_minute, unpaid_break_minutes, hourly_rate_grosze, note, external_uid) VALUES (12, 5, 20402, 540, 1020, 60, 0, NULL, NULL)")
            execSQL("INSERT INTO bonuses (id, job_id, date_epoch_day, amount_grosze, note) VALUES (3, 1, 20405, 7500, 'Premia')")
            close()
        }
        val migratedAt = 1_790_000_000_000L

        val db = openCurrent(Migration6To7 { migratedAt })
        try {
            val jobs = db.jobDao().getAll()
            val shifts = db.shiftDao().getAll()
            val bonuses = db.bonusDao().getAll()

            // Same rows, same ids, same links.
            assertEquals(listOf(1L, 5L), jobs.map { it.id })
            assertEquals(listOf(10L to 1L, 11L to 1L, 12L to 5L), shifts.map { it.id to it.jobId })
            assertEquals(listOf(3L to 1L), bonuses.map { it.id to it.jobId })

            // Same values.
            val burgerKing = jobs.first().toDomain()
            assertEquals("Burger King", burgerKing.name)
            assertEquals(TimedRate(3700, LocalTime.of(23, 0), LocalTime.of(6, 0)), burgerKing.secondRate)
            assertEquals("EUR", jobs.last().currency)
            val late = shifts.first().toDomain()
            assertEquals("late", late.note)
            assertEquals("uid-1@cal", late.externalUid)
            assertEquals(LocalTime.of(20, 0), late.start)
            assertEquals(LocalTime.of(1, 0), late.end)
            // 20:00-01:00 with 30 min break: 3 h at 33 zł + 2 h at 37 zł = 173 zł, the break (1/10) taken proportionally.
            assertEquals(17300L - 1730L, late.earningsGrosze)
            assertEquals(7500L, bonuses.single().amountGrosze)
            val pay = db.paySettingsDao().getAll().single()
            assertEquals(5L to 1371, pay.jobId to pay.deductionBp)
            assertEquals(450000L, pay.monthlyAmountMinor)

            // Sync metadata: unique UUIDs, the migration time, live, waiting for the first upload.
            val metadata = jobs.map { SyncRow(it.syncId, it.updatedAt, it.deleted, it.dirty) } +
                shifts.map { SyncRow(it.syncId, it.updatedAt, it.deleted, it.dirty) } +
                bonuses.map { SyncRow(it.syncId, it.updatedAt, it.deleted, it.dirty) }
            assertEquals(6, metadata.size)
            metadata.forEach { row ->
                assertTrue(row.syncId, isUuid(row.syncId))
                assertEquals(SyncRow(row.syncId, migratedAt, deleted = false, dirty = true), row)
            }
            assertEquals(6, metadata.map { it.syncId }.toSet().size)

            // The unique index exists: a second row with the same sync id is refused.
            val duplicate = shifts.last().copy(id = 0, syncId = shifts.first().syncId)
            assertThrows(SQLiteConstraintException::class.java) { runBlocking { db.shiftDao().insertAll(listOf(duplicate)) } }
        } finally {
            db.close()
        }
    }

    /**
     * v7 -> v8 adds `bonuses.whole_month` and the empty `payouts` table, and nothing else: every row keeps
     * every value, including its sync id, change time, tombstone and "waiting for upload" mark (so the update
     * makes nothing look changed and nothing upload again), and every existing bonus stays a bonus on its day.
     */
    @Test
    fun migrateFrom7To8KeepsEveryRowUntouchedAndAddsMonthBonusFlagAndPayouts(): Unit = runBlocking {
        val uploadedAt = 1_790_000_000_000L
        val jobKb = "11111111-1111-4111-8111-111111111111"
        val jobOld = "22222222-2222-4222-8222-222222222222"
        val jobEur = "33333333-3333-4333-8333-333333333333"
        val tables = listOf("jobs", "shifts", "bonuses", "job_pay_settings")
        val before = createDatabase(7).run {
            // Uploaded, deleted-and-uploaded, and waiting for upload: all three states must survive.
            execSQL("INSERT INTO jobs (id, name, hourly_rate_grosze, color, second_rate_grosze, second_rate_from_minute, second_rate_to_minute, currency, sync_id, updated_at, deleted, dirty) VALUES (1, 'Burger King', 3300, -1, 3700, 1380, 360, 'PLN', '$jobKb', $uploadedAt, 0, 0)")
            execSQL("INSERT INTO jobs (id, name, hourly_rate_grosze, color, currency, sync_id, updated_at, deleted, dirty) VALUES (2, 'Stara praca', 2500, -3, 'PLN', '$jobOld', ${uploadedAt + 10}, 1, 0)")
            execSQL("INSERT INTO jobs (id, name, hourly_rate_grosze, color, currency, sync_id, updated_at, deleted, dirty) VALUES (5, 'Biuro', 0, -2, 'EUR', '$jobEur', ${uploadedAt + 20}, 0, 1)")
            execSQL("INSERT INTO job_pay_settings (job_id, from_month, pay_type, monthly_amount_minor, deduction_bp) VALUES (5, 0, 'FIXED_MONTHLY', 450000, 1371)")
            execSQL("INSERT INTO shifts (id, job_id, date_epoch_day, start_minute, end_minute, unpaid_break_minutes, hourly_rate_grosze, note, external_uid, sync_id, updated_at, deleted, dirty) VALUES (10, 1, 20400, 480, 960, 30, 3300, 'uploaded', NULL, '44444444-4444-4444-8444-444444444444', $uploadedAt, 0, 0)")
            execSQL("INSERT INTO shifts (id, job_id, date_epoch_day, start_minute, end_minute, unpaid_break_minutes, hourly_rate_grosze, note, external_uid, sync_id, updated_at, deleted, dirty) VALUES (11, 2, 20401, 480, 960, 0, 2500, NULL, 'uid@cal', '55555555-5555-4555-8555-555555555555', ${uploadedAt + 10}, 1, 0)")
            execSQL("INSERT INTO bonuses (id, job_id, date_epoch_day, amount_grosze, note, sync_id, updated_at, deleted, dirty) VALUES (3, 1, 20405, 7500, 'Premia', '66666666-6666-4666-8666-666666666666', ${uploadedAt + 1}, 0, 0)")
            execSQL("INSERT INTO bonuses (id, job_id, date_epoch_day, amount_grosze, note, sync_id, updated_at, deleted, dirty) VALUES (4, 5, 20410, 1000, NULL, '77777777-7777-4777-8777-777777777777', ${uploadedAt + 21}, 0, 1)")
            execSQL("INSERT INTO bonuses (id, job_id, date_epoch_day, amount_grosze, note, sync_id, updated_at, deleted, dirty) VALUES (6, 2, 20411, 300, 'gone', '88888888-8888-4888-8888-888888888888', ${uploadedAt + 10}, 1, 1)")
            val rows = tables.associateWith { rawQuery("SELECT * FROM $it ORDER BY id", null).rows() }
            close()
            rows
        }

        val db = openCurrent()
        try {
            val sqlite = db.openHelper.writableDatabase
            // Nothing moved or changed; the only new value is whole_month = 0 on every bonus.
            for (table in tables) {
                val expected = before.getValue(table).map { if (table == "bonuses") it + ("whole_month" to "0") else it }
                assertEquals(table, expected, sqlite.query("SELECT * FROM $table ORDER BY id").rows())
            }
            assertEquals(3, before.getValue("bonuses").size)

            // And through the app's own reads: bonuses on their days, not month bonuses.
            assertEquals(listOf(3L, 4L), db.bonusDao().getAll().map { it.id })
            assertEquals(listOf(false, false), db.bonusDao().getAll().map { it.wholeMonth })
            assertEquals(listOf(false, false), db.bonusDao().getAll().map { it.toDomain().wholeMonth })

            // The payouts table exists, is empty, accepts a payout, keeps sync ids unique and follows its job.
            assertEquals(0, db.payoutDao().getAll().size)
            val payout = PayoutEntity(jobId = 1, month = 202611, amountMinor = 450000, note = null, receivedEpochDay = null)
            db.payoutDao().insertAll(listOf(payout))
            assertEquals(1, db.payoutDao().getAll().size)
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { db.payoutDao().insertAll(listOf(payout.copy(amountMinor = 1))) }
            }
            sqlite.execSQL("DELETE FROM jobs WHERE id = 1")
            assertEquals("a payout goes with its job", 0, sqlite.query("SELECT COUNT(*) FROM payouts").rows().single().getValue("COUNT(*)")!!.toInt())
        } finally {
            db.close()
        }
    }

    /** Every column of every row as text (a missing value stays null), in query order. */
    private fun Cursor.rows(): List<Map<String, String?>> = use {
        buildList {
            while (moveToNext()) {
                add(columnNames.indices.associate { columnNames[it] to if (isNull(it)) null else getString(it) })
            }
        }
    }

    private data class SyncRow(val syncId: String, val updatedAt: Long, val deleted: Boolean, val dirty: Boolean)

    private fun isUuid(text: String) = runCatching { UUID.fromString(text).toString() == text }.getOrDefault(false)
}

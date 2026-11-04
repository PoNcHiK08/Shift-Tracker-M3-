package com.vkurylo.shifttracker.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.toDomain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalTime

/**
 * Builds a database exactly as version 1 of the app created it (from the exported schema),
 * fills it with data, then opens it with the current Room database: the auto-migration must
 * keep every row and Room's schema validation must pass.
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

    private fun createVersion1Database() {
        val schemaFile = File("schemas/com.vkurylo.shifttracker.data.local.ShiftDatabase/1.json")
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

        db.execSQL("INSERT INTO jobs (id, name, hourly_rate_grosze, color) VALUES (1, 'Burger King', 3300, -1)")
        db.execSQL(
            "INSERT INTO shifts (id, job_id, date_epoch_day, start_minute, end_minute, unpaid_break_minutes, " +
                "hourly_rate_grosze, note, external_uid) VALUES (1, 1, 20400, 480, 960, 30, 3300, 'old shift', NULL)",
        )
        db.version = 1
        db.close()
    }

    @Test
    fun migrateFrom1ToLatestKeepsAllData() = runBlocking {
        createVersion1Database()

        val db = Room.databaseBuilder(context, ShiftDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
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
        } finally {
            db.close()
        }
    }
}

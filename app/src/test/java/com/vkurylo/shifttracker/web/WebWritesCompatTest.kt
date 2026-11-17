package com.vkurylo.shifttracker.web

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomBonusRepository
import com.vkurylo.shifttracker.data.repository.RoomPayoutRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.data.sync.CloudAccount
import com.vkurylo.shifttracker.data.sync.FakeRemoteStore
import com.vkurylo.shifttracker.data.sync.InMemorySyncStateStore
import com.vkurylo.shifttracker.data.sync.RemoteBonus
import com.vkurylo.shifttracker.data.sync.RemotePayout
import com.vkurylo.shifttracker.data.sync.RemoteRecord
import com.vkurylo.shifttracker.data.sync.RemoteShift
import com.vkurylo.shifttracker.data.sync.SignInChoice
import com.vkurylo.shifttracker.data.sync.SyncEngine
import com.vkurylo.shifttracker.data.sync.firebase.CloudCollection
import com.vkurylo.shifttracker.data.sync.firebase.readRecord
import com.vkurylo.shifttracker.data.sync.firebase.toFields
import com.vkurylo.shifttracker.data.sync.isUsable
import com.vkurylo.shifttracker.data.sync.toRemote
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.Payout
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.TimedRate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * The web version's writes (stage B) against the app's own code, both ways.
 *
 * Web -> app: web/test/fixtures/writes/web-writes.json holds documents the web's builders made (a shift created,
 * edited, deleted and restored; a night shift with a second rate; an imported shift edited; bonuses on a day and for
 * the whole month; a bonus in the previous app version's shape edited; payouts). A phone downloads the jobs, then
 * takes each write as it comes from the cloud through the real SyncEngine. After each one the phone's row, mapped
 * back by the app's own upload code (toRemote, toFields), must be exactly the web's document, the row must not be
 * dirty, and the next sync must write nothing (no ping-pong).
 *
 * App -> web: web/test/fixtures/writes/app-writes.json holds what the app uploads for records saved on a phone
 * (repositories, then SyncEngine's push into the fake cloud). The web reads it in web/test/unit/app-writes.test.ts.
 * By default this checks the committed file is still what the app's mapping writes for those records; with
 * UPDATE_WEB_FIXTURES=1 it saves them on a phone again and rewrites the file (ids and times are new each time).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WebWritesCompatTest {

    private val folder = File("../web/test/fixtures/writes")
    private val databases = mutableListOf<ShiftDatabase>()
    private val account = CloudAccount("uid-web-writes", "owner@example.com")

    @After
    fun tearDown() = databases.forEach { it.close() }

    private fun phone(): ShiftDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShiftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { databases += it }

    // --- Web -> app ------------------------------------------------------------------------------------------

    @Test
    fun webWritesComeOutOnThePhoneExactlyAndAreNotSentBack() = runBlocking {
        val fixture = parse(File(folder, "web-writes.json").readText()) as Map<*, *>
        val db = phone()
        val cloud = FakeRemoteStore()
        // The phone's clock is later than every write: nothing it does on its own could look newer.
        val engine = SyncEngine(db, cloud, InMemorySyncStateStore(), {}, WallClock { 1_799_000_000_000L })

        (fixture["jobs"] as Map<*, *>).forEach { (id, fields) -> cloud.put(account.uid, record("jobs", id, fields)) }
        (fixture["appDocuments"] as Map<*, *>).forEach { (key, fields) ->
            val (collection, id) = (key as String).split("/")
            cloud.put(account.uid, record(collection, id, fields))
        }
        engine.completeSignIn(account, SignInChoice.DOWNLOAD)
        assertEquals("the phone sent nothing after downloading", 0, cloud.writes)

        val steps = fixture["steps"] as List<*>
        assertEquals(13, steps.size)
        for (step in steps) {
            step as Map<*, *>
            val name = step["name"] as String
            val collection = step["collection"] as String
            val id = step["id"] as String
            @Suppress("UNCHECKED_CAST")
            val fields = step["fields"] as Map<String, Any?>
            val record = record(collection, id, fields)
            assertTrue("$name: the app can use the web's document", record.isUsable())

            cloud.put(account.uid, record)
            val writesBefore = cloud.writes
            engine.sync()
            assertEquals("$name: the phone sends nothing back", writesBefore, cloud.writes)

            assertEquals("$name: the phone's row, as the app would upload it, is the web's document", fields, onPhone(db, collection, id))
            assertFalse("$name: the row isn't waiting to be uploaded", dirty(db, collection, id))

            engine.sync()
            assertEquals("$name: and a later sync sends nothing either", writesBefore, cloud.writes)
        }

        // What the steps leave, as the app shows it.
        val shift = db.shiftDao().getBySyncId("a1000000-0000-4000-8000-000000000001")!!
        assertFalse("undo brought the shift back", shift.deleted)
        assertEquals(1080, shift.endMinute)
        assertTrue("a deleted bonus is a tombstone on the phone", db.bonusDao().getBySyncId("b2000000-0000-4000-8000-000000000001")!!.deleted)
        assertTrue(db.bonusDao().getBySyncId("b2000000-0000-4000-8000-000000000002")!!.wholeMonth)
        assertFalse(db.bonusDao().getBySyncId("b2000000-0000-4000-8000-000000000003")!!.wholeMonth)
        assertEquals("ics-20261102-1", db.shiftDao().getBySyncId("a1000000-0000-4000-8000-000000000003")!!.externalUid)
        val night = db.shiftDao().getBySyncId("a1000000-0000-4000-8000-000000000002")!!
        assertEquals(listOf(1320L, 360L, 3700L), listOf(night.startMinute.toLong(), night.endMinute.toLong(), night.secondRateGrosze))
    }

    private suspend fun onPhone(db: ShiftDatabase, collection: String, id: String): Map<String, Any?> {
        suspend fun jobSyncId(jobId: Long) = db.jobDao().getAll().first { it.id == jobId }.syncId
        val remote: RemoteRecord = when (collection) {
            "shifts" -> db.shiftDao().getBySyncId(id)!!.let { it.toRemote(jobSyncId(it.jobId)) }
            "bonuses" -> db.bonusDao().getBySyncId(id)!!.let { it.toRemote(jobSyncId(it.jobId)) }
            "payouts" -> db.payoutDao().getBySyncId(id)!!.let { it.toRemote(jobSyncId(it.jobId)) }
            else -> error(collection)
        }
        return remote.toFields()
    }

    private suspend fun dirty(db: ShiftDatabase, collection: String, id: String): Boolean = when (collection) {
        "shifts" -> db.shiftDao().getBySyncId(id)!!.dirty
        "bonuses" -> db.bonusDao().getBySyncId(id)!!.dirty
        "payouts" -> db.payoutDao().getBySyncId(id)!!.dirty
        else -> error(collection)
    }

    // --- App -> web ------------------------------------------------------------------------------------------

    @Test
    fun appWritesFileIsWhatTheAppUploads() = runBlocking {
        val file = File(folder, "app-writes.json")
        if (System.getenv("UPDATE_WEB_FIXTURES") == "1") file.writeText(render(json(appWrites())))
        assertTrue("$file is missing: run with UPDATE_WEB_FIXTURES=1", file.exists())
        val committed = parse(file.readText()) as Map<*, *>
        val documents = committed["documents"] as Map<*, *>
        var count = 0
        for ((collection, docs) in documents) {
            for ((id, fields) in docs as Map<*, *>) {
                val record = record(collection as String, id, fields)
                assertTrue("$collection/$id: usable", record.isUsable())
                assertEquals("$collection/$id: exactly what the app's mapping writes for this record", fields, record.toFields())
                count++
            }
        }
        assertEquals("shifts, bonuses (both kinds) and payouts, live and deleted", 8, count)
    }

    /** Records saved on a phone through the app's repositories, as its sync engine uploads them. */
    private suspend fun appWrites(): Map<String, Any?> {
        val fixture = parse(File(folder, "web-writes.json").readText()) as Map<*, *>
        val db = phone()
        val cloud = FakeRemoteStore()
        val clock = WallClock { System.currentTimeMillis() }
        val engine = SyncEngine(db, cloud, InMemorySyncStateStore(), {}, clock)
        (fixture["jobs"] as Map<*, *>).forEach { (id, fields) -> cloud.put(account.uid, record("jobs", id, fields)) }
        engine.completeSignIn(account, SignInChoice.DOWNLOAD)
        val jobs = db.jobDao().getAll().associateBy { it.syncId }
        val cafe = jobs.getValue("c49f2c0c-b280-4492-8476-1ad7e501e5da").id
        val night = jobs.getValue("fe49c731-2814-47c8-8c10-5f9ad3994e8e").id
        val eur = jobs.getValue("0b6f0c0e-7a5d-4c43-9f0d-2d5ad1e0b7a1").id

        val details = JobDetailsSource(db.paySettingsDao())
        val shifts = RoomShiftRepository(db, db.shiftDao(), details, clock = clock)
        val bonuses = RoomBonusRepository(db, db.bonusDao(), details, clock = clock)
        val payouts = RoomPayoutRepository(db, db.payoutDao(), details, clock = clock)

        shifts.upsert(Shift(jobId = cafe, date = LocalDate.of(2026, 11, 20), start = LocalTime.of(9, 0), end = LocalTime.of(17, 0), unpaidBreakMinutes = 30, hourlyRateGrosze = 3150, note = "Z telefonu"))
        shifts.upsert(
            Shift(
                jobId = night, date = LocalDate.of(2026, 11, 21), start = LocalTime.of(22, 0), end = LocalTime.of(6, 0), unpaidBreakMinutes = 30,
                hourlyRateGrosze = 3300, secondRate = TimedRate(3700, LocalTime.of(23, 0), LocalTime.of(6, 0)),
            ),
        )
        val deletedShift = shifts.upsert(Shift(jobId = cafe, date = LocalDate.of(2026, 11, 22), start = LocalTime.of(10, 0), end = LocalTime.of(14, 0), hourlyRateGrosze = 3150))
        shifts.delete(shifts.getShift(deletedShift)!!)
        bonuses.upsert(Bonus(jobId = cafe, date = LocalDate.of(2026, 11, 20), amountGrosze = 4200, note = "Napiwki"))
        bonuses.upsert(Bonus(jobId = night, date = LocalDate.of(2026, 11, 1), amountGrosze = 30000, wholeMonth = true))
        payouts.upsert(Payout(jobId = night, month = YearMonth.of(2026, 11), amountMinor = 190000, note = "Przelew", receivedOn = LocalDate.of(2026, 12, 10)))
        payouts.upsert(Payout(jobId = eur, month = YearMonth.of(2026, 10), amountMinor = 12345))
        val deletedPayout = payouts.upsert(Payout(jobId = eur, month = YearMonth.of(2026, 9), amountMinor = 500))
        payouts.delete(payouts.getPayout(deletedPayout)!!)

        engine.sync()
        val uploaded = cloud.records(account.uid)
        val documents = linkedMapOf<String, Map<String, Map<String, Any?>>>()
        for (collection in listOf(CloudCollection.SHIFTS, CloudCollection.BONUSES, CloudCollection.PAYOUTS)) {
            val records = uploaded.filter {
                when (collection) {
                    CloudCollection.SHIFTS -> it is RemoteShift
                    CloudCollection.BONUSES -> it is RemoteBonus
                    else -> it is RemotePayout
                }
            }
            documents[collection.path] = records.associateTo(LinkedHashMap()) { it.syncId to it.toFields() }
        }
        assertNotNull(documents["shifts"])
        return linkedMapOf(
            "note" to "Written by app/src/test/.../web/WebWritesCompatTest.kt (UPDATE_WEB_FIXTURES=1): records saved on a phone, as the app uploads them. Read by web/test/unit/app-writes.test.ts.",
            "documents" to documents,
        )
    }

    // --- JSON ------------------------------------------------------------------------------------------------

    private fun record(collection: Any?, id: Any?, fields: Any?): RemoteRecord {
        @Suppress("UNCHECKED_CAST")
        return readRecord(CloudCollection.entries.first { it.path == collection }, id as String, fields as Map<String, Any?>)
    }

    private fun parse(text: String): Any? = plain(Json.parseToJsonElement(text))

    /** JSON as Firestore hands documents to the app: whole numbers as Long. */
    private fun plain(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> element.entries.associateTo(LinkedHashMap()) { (k, v) -> k to plain(v) }
        is JsonArray -> element.map(::plain)
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            else -> element.doubleOrNull
        }
    }

    private fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associateTo(LinkedHashMap()) { (k, v) -> k as String to json(v) })
        is List<*> -> JsonArray(value.map(::json))
        else -> error("Not JSON: $value")
    }

    private fun render(element: JsonElement): String = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), element) + "\n"
}

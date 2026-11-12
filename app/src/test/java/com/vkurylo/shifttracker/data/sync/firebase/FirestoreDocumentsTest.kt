package com.vkurylo.shifttracker.data.sync.firebase

import com.vkurylo.shifttracker.data.sync.DOC_SCHEMA
import com.vkurylo.shifttracker.data.sync.RemoteBonus
import com.vkurylo.shifttracker.data.sync.RemoteJob
import com.vkurylo.shifttracker.data.sync.RemotePaySettings
import com.vkurylo.shifttracker.data.sync.RemotePayout
import com.vkurylo.shifttracker.data.sync.RemoteShift
import com.vkurylo.shifttracker.data.sync.RemoteTimedRate
import com.vkurylo.shifttracker.data.sync.isUsable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FirestoreDocumentsTest {

    private val job = RemoteJob(
        syncId = "11111111-1111-4111-8111-111111111111",
        updatedAt = 1_762_600_000_000,
        deleted = false,
        name = "Kawiarnia",
        hourlyRateGrosze = 3_300,
        color = 0xFF2196F3.toInt(), // negative as an Int: stored as a whole number all the same
        secondRate = RemoteTimedRate(rateGrosze = 4_000, fromMinute = 22 * 60, toMinute = 6 * 60),
        currency = "PLN",
        payHistory = listOf(RemotePaySettings(0, "HOURLY", 0, 0), RemotePaySettings(202_611, "FIXED_MONTHLY", 650_000, 2_271)),
    )
    private val shift = RemoteShift(
        syncId = "22222222-2222-4222-8222-222222222222",
        updatedAt = 1_762_600_000_001,
        deleted = true,
        jobSyncId = job.syncId,
        dateEpochDay = 20_400,
        startMinute = 22 * 60,
        endMinute = 6 * 60,
        unpaidBreakMinutes = 30,
        hourlyRateGrosze = 3_300,
        note = null,
        externalUid = "abc@calendar",
        secondRate = null,
    )
    private val bonus = RemoteBonus("33333333-3333-4333-8333-333333333333", 5, false, job.syncId, 20_400, 5_000, "Premia")
    private val monthBonus = RemoteBonus("66666666-6666-4666-8666-666666666666", 7, false, job.syncId, 20_393, 50_000, null, wholeMonth = true)
    private val payout = RemotePayout("55555555-5555-4555-8555-555555555555", 6, false, job.syncId, 202_611, 450_000, "Przelew", 20_420)

    @Test
    fun `records come back from their fields unchanged`() {
        assertEquals(job, readRecord(CloudCollection.JOBS, job.syncId, job.toFields()))
        assertEquals(shift, readRecord(CloudCollection.SHIFTS, shift.syncId, shift.toFields()))
        assertEquals(bonus, readRecord(CloudCollection.BONUSES, bonus.syncId, bonus.toFields()))
        assertEquals(bonus.copy(note = null), readRecord(CloudCollection.BONUSES, bonus.syncId, bonus.copy(note = null).toFields()))
        assertEquals(monthBonus, readRecord(CloudCollection.BONUSES, monthBonus.syncId, monthBonus.toFields()))
        assertEquals(payout, readRecord(CloudCollection.PAYOUTS, payout.syncId, payout.toFields()))
        assertEquals(
            payout.copy(note = null, receivedEpochDay = null),
            readRecord(CloudCollection.PAYOUTS, payout.syncId, payout.copy(note = null, receivedEpochDay = null).toFields()),
        )
        assertEquals(payout.copy(deleted = true), readRecord(CloudCollection.PAYOUTS, payout.syncId, payout.copy(deleted = true).toFields()))
    }

    @Test
    fun `a bonus is written with its wholeMonth mark and read with or without it`() {
        assertEquals(false, bonus.toFields()["wholeMonth"])
        assertEquals(true, monthBonus.toFields()["wholeMonth"])
        // A document written by the app version before month bonuses has no such field: a bonus on its day.
        val before = bonus.toFields() - "wholeMonth"
        assertEquals(bonus, readRecord(CloudCollection.BONUSES, bonus.syncId, before))
        assertTrue(readRecord(CloudCollection.BONUSES, bonus.syncId, before).isUsable())
        // Present, it must be a boolean.
        for (bad in listOf<Any?>("true", 1L, 0L, null)) {
            val record = readRecord(CloudCollection.BONUSES, bonus.syncId, monthBonus.toFields() + ("wholeMonth" to bad))
            assertEquals(bad.toString(), UNREADABLE_SCHEMA, record.schema)
        }
    }

    @Test
    fun `a payout needs every field, with its type`() {
        val fields = payout.toFields()
        for ((what, broken) in mapOf(
            "no amount" to fields - "amountMinor",
            "no month" to fields - "month",
            "no job" to fields - "jobId",
            "no note key" to fields - "note",
            "no received-date key" to fields - "receivedEpochDay",
            "a fractional amount" to fields + ("amountMinor" to 450_000.5),
            "a month as text" to fields + ("month" to "2026-11"),
            "a received date as text" to fields + ("receivedEpochDay" to "2026-12-02"),
            "a note that is not text" to fields + ("note" to 5L),
        )) {
            val record = readRecord(CloudCollection.PAYOUTS, payout.syncId, broken)
            assertEquals(what, UNREADABLE_SCHEMA, record.schema)
            assertFalse(what, record.isUsable())
        }
    }

    @Test
    fun `a payout's values are checked before it is used`() {
        assertTrue(payout.isUsable())
        for ((what, bad) in mapOf(
            "month 13" to payout.copy(month = 202_613),
            "month 0" to payout.copy(month = 0),
            "a year before 1000" to payout.copy(month = 99_912),
            "no amount" to payout.copy(amountMinor = 0),
            "a negative amount" to payout.copy(amountMinor = -1),
            "an amount over the limit" to payout.copy(amountMinor = Long.MAX_VALUE),
            "a received date that is no date" to payout.copy(receivedEpochDay = Long.MAX_VALUE),
            "a job id that is not a UUID" to payout.copy(jobSyncId = "kawiarnia"),
            "a newer layout" to payout.copy(schema = DOC_SCHEMA + 1),
        )) {
            assertFalse(what, bad.isUsable())
        }
    }

    @Test
    fun `every number is a whole number, money in minor units`() {
        val fields = job.toFields() + shift.toFields() + bonus.toFields() + monthBonus.toFields() + payout.toFields()
        fun numbers(value: Any?): List<Any> = when (value) {
            is Map<*, *> -> value.values.flatMap(::numbers)
            is List<*> -> value.flatMap(::numbers)
            is Number -> listOf(value)
            else -> emptyList()
        }
        val all = numbers(fields)
        assertTrue(all.isNotEmpty())
        assertTrue(all.all { it is Long })
        assertEquals(3_300L, job.toFields()["hourlyRateGrosze"])
        assertEquals(650_000L, ((job.toFields()["payHistory"] as List<*>)[1] as Map<*, *>)["monthlyAmountMinor"])
    }

    @Test
    fun `a document this app can't read is skipped, and never overwritten by an older version`() {
        val missing = job.toFields() - "currency"
        val fraction = shift.toFields() + ("hourlyRateGrosze" to 33.5)
        val text = bonus.toFields() + ("amountGrosze" to "5000")
        val noTime = bonus.toFields() - "updatedAt"
        val badHistory = job.toFields() + ("payHistory" to listOf("HOURLY"))

        for ((collection, fields) in listOf(
            CloudCollection.JOBS to missing,
            CloudCollection.SHIFTS to fraction,
            CloudCollection.BONUSES to text,
            CloudCollection.JOBS to badHistory,
        )) {
            val record = readRecord(collection, "44444444-4444-4444-8444-444444444444", fields)
            assertEquals(UNREADABLE_SCHEMA, record.schema)
            assertFalse(record.isUsable())
            assertEquals(fields["updatedAt"], record.updatedAt)
        }
        // Without a readable time, it counts as newer than anything this phone could upload.
        assertEquals(Long.MAX_VALUE, readRecord(CloudCollection.BONUSES, bonus.syncId, noTime).updatedAt)
    }

    @Test
    fun `a newer app's layout is read but not used`() {
        val newer = shift.toFields() + mapOf("v" to (DOC_SCHEMA + 1).toLong(), "tips" to 1_200L)
        val record = readRecord(CloudCollection.SHIFTS, shift.syncId, newer)
        assertEquals(DOC_SCHEMA + 1, record.schema)
        assertFalse(record.isUsable())
    }

    @Test
    fun `the fields written are exactly the ones firestore rules accept`() {
        // Unit tests run in the app module; the rules are in firebase/ at the root of the repository.
        val rules = File("../firebase/firestore.rules").readText()
        /** Every exact field set the function accepts (a record may have more than one accepted shape). */
        fun shapes(function: String): List<Set<String>> {
            val start = rules.indexOf("function $function(d) {")
            check(start >= 0) { "no function $function in the rules" }
            val next = rules.indexOf("\n    function ", start + 1)
            val body = rules.substring(start, if (next < 0) rules.length else next)
            return Regex("""hasExactly\(d, \[(.*?)]\)""", RegexOption.DOT_MATCHES_ALL).findAll(body).map { match ->
                Regex("'([A-Za-z]+)'").findAll(match.groupValues[1]).map { it.groupValues[1] }.toSet()
            }.toList()
        }
        fun allowed(function: String): Set<String> = shapes(function).single()
        // The store adds serverUpdatedAt to every write.
        assertEquals(allowed("isJob"), job.toFields().keys + "serverUpdatedAt")
        assertEquals(allowed("isShift"), shift.toFields().keys + "serverUpdatedAt")
        // A bonus has two accepted shapes: the one the previous app version writes, and the current one.
        val bonusShapes = shapes("isBonus")
        assertEquals(2, bonusShapes.size)
        val (previous, current) = bonusShapes.sortedBy { it.size }
        assertEquals("the previous version's shape is the current one without wholeMonth", current - "wholeMonth", previous)
        assertEquals("this version writes the current shape, for both kinds of bonus", current, bonus.toFields().keys + "serverUpdatedAt")
        assertEquals(current, monthBonus.toFields().keys + "serverUpdatedAt")
        assertEquals(allowed("isPayout"), payout.toFields().keys + "serverUpdatedAt")
        assertTrue(rules.contains("hasExactly(value, ['rateGrosze', 'fromMinute', 'toMinute'])"))
        assertEquals(setOf("rateGrosze", "fromMinute", "toMinute"), (job.toFields()["secondRate"] as Map<*, *>).keys)
    }
}

package com.vkurylo.shifttracker.data.sync.firebase

import com.vkurylo.shifttracker.data.sync.DOC_SCHEMA
import com.vkurylo.shifttracker.data.sync.RemoteBonus
import com.vkurylo.shifttracker.data.sync.RemoteJob
import com.vkurylo.shifttracker.data.sync.RemotePaySettings
import com.vkurylo.shifttracker.data.sync.RemotePayout
import com.vkurylo.shifttracker.data.sync.RemoteRecord
import com.vkurylo.shifttracker.data.sync.RemoteShift
import com.vkurylo.shifttracker.data.sync.RemoteTimedRate

/*
 * Cloud records <-> Firestore document fields. Plain maps, no Firebase types, so it is unit-tested on the JVM;
 * FirestoreRemoteStore adds the server timestamp. firestore.rules checks the same field names and types: change
 * both together.
 *
 *   users/{uid}                 { epoch, createdAt }
 *   users/{uid}/jobs/{syncId}    { v, updatedAt, serverUpdatedAt, deleted, name, hourlyRateGrosze, color,
 *                                  secondRate, currency, payHistory }
 *   users/{uid}/shifts/{syncId}  { v, updatedAt, serverUpdatedAt, deleted, jobId, dateEpochDay, startMinute,
 *                                  endMinute, unpaidBreakMinutes, hourlyRateGrosze, note, externalUid, secondRate }
 *   users/{uid}/bonuses/{syncId} { v, updatedAt, serverUpdatedAt, deleted, jobId, dateEpochDay, amountGrosze, note,
 *                                  wholeMonth }
 *   users/{uid}/payouts/{syncId} { v, updatedAt, serverUpdatedAt, deleted, jobId, month, amountMinor, note,
 *                                  receivedEpochDay }
 *
 * `wholeMonth` is optional when reading (a bonus written by an app version without month bonuses has none: false),
 * and always written. Everything else is required. firestore.rules accepts a bonus with or without it.
 *
 * Every number is a whole number (Firestore integer): money in minor units (grosze), times in UTC millis, minutes
 * of the day, days since 1970-01-01, months as yyyymm. `jobId` is the job's sync id (its document id). `serverUpdatedAt` is set by
 * the server on every write and only used to find changes; `updatedAt` (the phone's clock) decides conflicts.
 */

internal object Fields {
    const val SCHEMA = "v"
    const val UPDATED_AT = "updatedAt"
    const val SERVER_UPDATED_AT = "serverUpdatedAt"
    const val DELETED = "deleted"

    const val NAME = "name"
    const val HOURLY_RATE = "hourlyRateGrosze"
    const val COLOR = "color"
    const val SECOND_RATE = "secondRate"
    const val CURRENCY = "currency"
    const val PAY_HISTORY = "payHistory"

    const val JOB_ID = "jobId"
    const val DATE = "dateEpochDay"
    const val START = "startMinute"
    const val END = "endMinute"
    const val BREAK = "unpaidBreakMinutes"
    const val NOTE = "note"
    const val EXTERNAL_UID = "externalUid"
    const val AMOUNT = "amountGrosze"
    const val WHOLE_MONTH = "wholeMonth"

    const val MONTH = "month"
    const val AMOUNT_MINOR = "amountMinor"
    const val RECEIVED = "receivedEpochDay"

    const val RATE = "rateGrosze"
    const val FROM_MINUTE = "fromMinute"
    const val TO_MINUTE = "toMinute"

    const val FROM_MONTH = "fromMonth"
    const val PAY_TYPE = "payType"
    const val MONTHLY_AMOUNT = "monthlyAmountMinor"
    const val DEDUCTION = "deductionBp"

    const val EPOCH = "epoch"
    const val CREATED_AT = "createdAt"
}

internal enum class CloudCollection(val path: String) { JOBS("jobs"), SHIFTS("shifts"), BONUSES("bonuses"), PAYOUTS("payouts") }

internal val RemoteRecord.collection: CloudCollection
    get() = when (this) {
        is RemoteJob -> CloudCollection.JOBS
        is RemoteShift -> CloudCollection.SHIFTS
        is RemoteBonus -> CloudCollection.BONUSES
        is RemotePayout -> CloudCollection.PAYOUTS
    }

/**
 * Schema given to a document this app can't read (a field missing or of the wrong type). Higher than any
 * [DOC_SCHEMA], so the sync engine treats it like a document from a newer app: skipped, never applied.
 */
internal const val UNREADABLE_SCHEMA = Int.MAX_VALUE

/** The document's fields, without `serverUpdatedAt` (the store adds the server's timestamp). */
internal fun RemoteRecord.toFields(): Map<String, Any?> {
    val common = mapOf(Fields.SCHEMA to schema.toLong(), Fields.UPDATED_AT to updatedAt, Fields.DELETED to deleted)
    return common + when (this) {
        is RemoteJob -> mapOf(
            Fields.NAME to name,
            Fields.HOURLY_RATE to hourlyRateGrosze,
            Fields.COLOR to color.toLong(),
            Fields.SECOND_RATE to secondRate?.toFields(),
            Fields.CURRENCY to currency,
            Fields.PAY_HISTORY to payHistory.map {
                mapOf(
                    Fields.FROM_MONTH to it.fromMonth.toLong(),
                    Fields.PAY_TYPE to it.payType,
                    Fields.MONTHLY_AMOUNT to it.monthlyAmountMinor,
                    Fields.DEDUCTION to it.deductionBp.toLong(),
                )
            },
        )
        is RemoteShift -> mapOf(
            Fields.JOB_ID to jobSyncId,
            Fields.DATE to dateEpochDay,
            Fields.START to startMinute.toLong(),
            Fields.END to endMinute.toLong(),
            Fields.BREAK to unpaidBreakMinutes.toLong(),
            Fields.HOURLY_RATE to hourlyRateGrosze,
            Fields.NOTE to note,
            Fields.EXTERNAL_UID to externalUid,
            Fields.SECOND_RATE to secondRate?.toFields(),
        )
        is RemoteBonus -> mapOf(
            Fields.JOB_ID to jobSyncId,
            Fields.DATE to dateEpochDay,
            Fields.AMOUNT to amountGrosze,
            Fields.NOTE to note,
            Fields.WHOLE_MONTH to wholeMonth,
        )
        is RemotePayout -> mapOf(
            Fields.JOB_ID to jobSyncId,
            Fields.MONTH to month.toLong(),
            Fields.AMOUNT_MINOR to amountMinor,
            Fields.NOTE to note,
            Fields.RECEIVED to receivedEpochDay,
        )
    }
}

private fun RemoteTimedRate.toFields() =
    mapOf(Fields.RATE to rateGrosze, Fields.FROM_MINUTE to fromMinute.toLong(), Fields.TO_MINUTE to toMinute.toLong())

/**
 * Reads a document of [collection]. Never throws: a document it can't read (a field missing or of the wrong
 * type) comes back as a record with [UNREADABLE_SCHEMA], which the engine skips. Its `updatedAt` is the
 * document's when that one is readable, else [Long.MAX_VALUE], so this app never overwrites what it can't read.
 * Whether the values make sense (ranges, currency, ...) is the engine's check (isUsable), not this one's.
 */
internal fun readRecord(collection: CloudCollection, id: String, data: Map<String, Any?>): RemoteRecord =
    try {
        val reader = FieldReader(data)
        when (collection) {
            CloudCollection.JOBS -> reader.job(id)
            CloudCollection.SHIFTS -> reader.shift(id)
            CloudCollection.BONUSES -> reader.bonus(id)
            CloudCollection.PAYOUTS -> reader.payout(id)
        }
    } catch (e: UnreadableField) {
        unreadable(collection, id, (data[Fields.UPDATED_AT] as? Long) ?: Long.MAX_VALUE)
    }

private fun unreadable(collection: CloudCollection, id: String, updatedAt: Long): RemoteRecord = when (collection) {
    CloudCollection.JOBS -> RemoteJob(id, updatedAt, false, "", 0, 0, null, "", emptyList(), UNREADABLE_SCHEMA)
    CloudCollection.SHIFTS -> RemoteShift(id, updatedAt, false, "", 0, 0, 0, 0, 0, null, null, null, UNREADABLE_SCHEMA)
    CloudCollection.BONUSES -> RemoteBonus(id, updatedAt, false, "", 0, 0, null, schema = UNREADABLE_SCHEMA)
    CloudCollection.PAYOUTS -> RemotePayout(id, updatedAt, false, "", 0, 0, null, null, UNREADABLE_SCHEMA)
}

private class UnreadableField(name: String) : Exception(name)

private class FieldReader(private val data: Map<String, Any?>) {

    fun job(id: String) = RemoteJob(
        syncId = id,
        updatedAt = long(Fields.UPDATED_AT),
        deleted = bool(Fields.DELETED),
        name = string(Fields.NAME),
        hourlyRateGrosze = long(Fields.HOURLY_RATE),
        color = int(Fields.COLOR),
        secondRate = timedRate(),
        currency = string(Fields.CURRENCY),
        payHistory = (data[Fields.PAY_HISTORY] as? List<*> ?: throw UnreadableField(Fields.PAY_HISTORY)).map { entry ->
            val pay = FieldReader(entry.asMap(Fields.PAY_HISTORY))
            RemotePaySettings(
                fromMonth = pay.int(Fields.FROM_MONTH),
                payType = pay.string(Fields.PAY_TYPE),
                monthlyAmountMinor = pay.long(Fields.MONTHLY_AMOUNT),
                deductionBp = pay.int(Fields.DEDUCTION),
            )
        },
        schema = schema(),
    )

    fun shift(id: String) = RemoteShift(
        syncId = id,
        updatedAt = long(Fields.UPDATED_AT),
        deleted = bool(Fields.DELETED),
        jobSyncId = string(Fields.JOB_ID),
        dateEpochDay = long(Fields.DATE),
        startMinute = int(Fields.START),
        endMinute = int(Fields.END),
        unpaidBreakMinutes = int(Fields.BREAK),
        hourlyRateGrosze = long(Fields.HOURLY_RATE),
        note = optionalString(Fields.NOTE),
        externalUid = optionalString(Fields.EXTERNAL_UID),
        secondRate = timedRate(),
        schema = schema(),
    )

    fun bonus(id: String) = RemoteBonus(
        syncId = id,
        updatedAt = long(Fields.UPDATED_AT),
        deleted = bool(Fields.DELETED),
        jobSyncId = string(Fields.JOB_ID),
        dateEpochDay = long(Fields.DATE),
        amountGrosze = long(Fields.AMOUNT),
        note = optionalString(Fields.NOTE),
        wholeMonth = boolOrFalse(Fields.WHOLE_MONTH),
        schema = schema(),
    )

    fun payout(id: String) = RemotePayout(
        syncId = id,
        updatedAt = long(Fields.UPDATED_AT),
        deleted = bool(Fields.DELETED),
        jobSyncId = string(Fields.JOB_ID),
        month = int(Fields.MONTH),
        amountMinor = long(Fields.AMOUNT_MINOR),
        note = optionalString(Fields.NOTE),
        receivedEpochDay = optionalLong(Fields.RECEIVED),
        schema = schema(),
    )

    private fun schema(): Int = int(Fields.SCHEMA)

    private fun timedRate(): RemoteTimedRate? {
        val rate = data[Fields.SECOND_RATE] ?: return null
        val fields = FieldReader(rate.asMap(Fields.SECOND_RATE))
        return RemoteTimedRate(fields.long(Fields.RATE), fields.int(Fields.FROM_MINUTE), fields.int(Fields.TO_MINUTE))
    }

    // Firestore returns whole numbers as Long; anything else (a fraction, text) is not ours.
    fun long(name: String): Long = when (val value = data[name]) {
        is Long -> value
        is Int -> value.toLong()
        else -> throw UnreadableField(name)
    }

    fun int(name: String): Int {
        val value = long(name)
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) throw UnreadableField(name)
        return value.toInt()
    }

    fun bool(name: String): Boolean = data[name] as? Boolean ?: throw UnreadableField(name)

    /** Missing: false (written by an app version that doesn't know the field). Present: must be a boolean. */
    fun boolOrFalse(name: String): Boolean = if (data.containsKey(name)) bool(name) else false

    /** Present and null, or a whole number. A missing key is unreadable: this app always writes it. */
    fun optionalLong(name: String): Long? {
        if (!data.containsKey(name)) throw UnreadableField(name)
        return if (data[name] == null) null else long(name)
    }

    fun string(name: String): String = data[name] as? String ?: throw UnreadableField(name)

    /** Present and null, or a string. A missing key is unreadable: this app always writes it. */
    fun optionalString(name: String): String? {
        if (!data.containsKey(name)) throw UnreadableField(name)
        return data[name]?.let { it as? String ?: throw UnreadableField(name) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(name: String): Map<String, Any?> =
        if (this is Map<*, *> && keys.all { it is String }) this as Map<String, Any?> else throw UnreadableField(name)
}

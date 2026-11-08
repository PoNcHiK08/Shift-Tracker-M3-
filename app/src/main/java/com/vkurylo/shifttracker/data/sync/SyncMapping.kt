package com.vkurylo.shifttracker.data.sync

import com.vkurylo.shifttracker.data.local.BonusEntity
import com.vkurylo.shifttracker.data.local.JobEntity
import com.vkurylo.shifttracker.data.local.JobPaySettingsEntity
import com.vkurylo.shifttracker.data.local.ShiftEntity
import com.vkurylo.shifttracker.domain.calc.MoneyParser
import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import com.vkurylo.shifttracker.domain.model.Currencies
import java.time.LocalTime

/* Room rows <-> cloud records. Amounts are copied as they are (Long minor units). */

private const val DEFAULT_FROM = 23 * 60
private const val DEFAULT_TO = 6 * 60

private fun timedRate(grosze: Long?, from: Int, to: Int) = grosze?.let { RemoteTimedRate(it, from, to) }

fun JobEntity.toRemote(payHistory: List<JobPaySettingsEntity>) = RemoteJob(
    syncId = syncId,
    updatedAt = updatedAt,
    deleted = deleted,
    name = name,
    hourlyRateGrosze = hourlyRateGrosze,
    color = color,
    secondRate = timedRate(secondRateGrosze, secondRateFromMinute, secondRateToMinute),
    currency = currency,
    payHistory = payHistory.sortedBy { it.fromMonth }.map {
        RemotePaySettings(it.fromMonth, it.payType, it.monthlyAmountMinor, it.deductionBp)
    },
)

fun ShiftEntity.toRemote(jobSyncId: String) = RemoteShift(
    syncId = syncId,
    updatedAt = updatedAt,
    deleted = deleted,
    jobSyncId = jobSyncId,
    dateEpochDay = dateEpochDay,
    startMinute = startMinute,
    endMinute = endMinute,
    unpaidBreakMinutes = unpaidBreakMinutes,
    hourlyRateGrosze = hourlyRateGrosze,
    note = note,
    externalUid = externalUid,
    secondRate = timedRate(secondRateGrosze, secondRateFromMinute, secondRateToMinute),
)

fun BonusEntity.toRemote(jobSyncId: String) = RemoteBonus(
    syncId = syncId,
    updatedAt = updatedAt,
    deleted = deleted,
    jobSyncId = jobSyncId,
    dateEpochDay = dateEpochDay,
    amountGrosze = amountGrosze,
    note = note,
)

/** The cloud's version as a row (already uploaded, so not dirty), keeping the local [id]. */
fun RemoteJob.toEntity(id: Long) = JobEntity(
    id = id,
    name = name,
    hourlyRateGrosze = hourlyRateGrosze,
    color = color,
    secondRateGrosze = secondRate?.rateGrosze,
    secondRateFromMinute = secondRate?.fromMinute ?: DEFAULT_FROM,
    secondRateToMinute = secondRate?.toMinute ?: DEFAULT_TO,
    currency = currency,
    syncId = syncId,
    updatedAt = updatedAt,
    deleted = deleted,
    dirty = false,
)

fun RemoteJob.payEntities(jobId: Long) = payHistory.map {
    JobPaySettingsEntity(jobId = jobId, fromMonth = it.fromMonth, payType = it.payType, monthlyAmountMinor = it.monthlyAmountMinor, deductionBp = it.deductionBp)
}

fun RemoteShift.toEntity(id: Long, jobId: Long) = ShiftEntity(
    id = id,
    jobId = jobId,
    dateEpochDay = dateEpochDay,
    startMinute = startMinute,
    endMinute = endMinute,
    unpaidBreakMinutes = unpaidBreakMinutes,
    hourlyRateGrosze = hourlyRateGrosze,
    note = note,
    externalUid = externalUid,
    secondRateGrosze = secondRate?.rateGrosze,
    secondRateFromMinute = secondRate?.fromMinute ?: DEFAULT_FROM,
    secondRateToMinute = secondRate?.toMinute ?: DEFAULT_TO,
    syncId = syncId,
    updatedAt = updatedAt,
    deleted = deleted,
    dirty = false,
)

fun RemoteBonus.toEntity(id: Long, jobId: Long) = BonusEntity(
    id = id,
    jobId = jobId,
    dateEpochDay = dateEpochDay,
    amountGrosze = amountGrosze,
    note = note,
    syncId = syncId,
    updatedAt = updatedAt,
    deleted = deleted,
    dirty = false,
)

/** Same version: same content and `updatedAt`, whatever the [RemoteRecord.schema]. */
fun RemoteRecord.sameContentAs(other: RemoteRecord): Boolean = when {
    this is RemoteJob && other is RemoteJob -> copy(schema = 0) == other.copy(schema = 0)
    this is RemoteShift && other is RemoteShift -> copy(schema = 0) == other.copy(schema = 0)
    this is RemoteBonus && other is RemoteBonus -> copy(schema = 0) == other.copy(schema = 0)
    else -> false
}

/*
 * A record from the cloud is applied only if it is one this app can show and calculate with: the same
 * limits the editors and the backup import use. Anything else (a newer app's layout, a damaged document)
 * is skipped and counted, never written into the database.
 */

/**
 * Names and notes have no length limit in the editors; this one only keeps out absurd values. firestore.rules
 * allows the same length, so whatever one phone may upload, the others can apply.
 */
private const val MAX_TEXT = 20_000
private const val DAY_MINUTES = 24 * 60

/** 0001-01-01 .. 9999-12-31, so LocalDate.ofEpochDay never throws. */
private val EPOCH_DAYS = -719_162L..2_932_896L

private fun minute(value: Int) = value in 0 until DAY_MINUTES

private fun RemoteTimedRate?.isUsable(digits: Int) =
    this == null || (rateGrosze in 0..MoneyParser.maxMinor(digits) && minute(fromMinute) && minute(toMinute) && fromMinute != toMinute)

private fun isUuid(text: String) = runCatching { java.util.UUID.fromString(text).toString() == text }.getOrDefault(false)

fun RemoteRecord.isUsable(): Boolean = schema <= DOC_SCHEMA && isUuid(syncId) && updatedAt >= 0 && when (this) {
    is RemoteJob -> isUsableJob()
    is RemoteShift -> isUsableShift()
    is RemoteBonus -> isUsableBonus()
}

private fun RemoteJob.isUsableJob(): Boolean {
    if (name.isBlank() || name.length > MAX_TEXT || !Currencies.isValid(currency)) return false
    val digits = Currencies.fractionDigits(currency)
    return hourlyRateGrosze in 0..MoneyParser.maxMinor(digits) &&
        secondRate.isUsable(digits) &&
        payHistory.map { it.fromMonth }.toSet().size == payHistory.size &&
        payHistory.all {
            (it.fromMonth == 0 || (it.fromMonth / 100 in 1..9999 && it.fromMonth % 100 in 1..12)) &&
                it.monthlyAmountMinor in 0..MoneyParser.maxMinor(digits, MoneyParser.AMOUNT_WHOLE_DIGITS) &&
                it.deductionBp in 0..10_000
        }
}

// Amounts of shifts and bonuses are checked against the currency with the most decimals (3) here: the
// job they belong to, and so their currency, may arrive in another batch.
private const val MAX_FRACTION_DIGITS = 3

private fun RemoteShift.isUsableShift(): Boolean {
    if (!isUuid(jobSyncId) || dateEpochDay !in EPOCH_DAYS || !minute(startMinute) || !minute(endMinute)) return false
    val duration = ShiftCalculator.durationMinutes(LocalTime.of(startMinute / 60, startMinute % 60), LocalTime.of(endMinute / 60, endMinute % 60))
    return unpaidBreakMinutes in 0 until duration &&
        hourlyRateGrosze in 0..MoneyParser.maxMinor(MAX_FRACTION_DIGITS) &&
        secondRate.isUsable(MAX_FRACTION_DIGITS) &&
        (note?.length ?: 0) <= MAX_TEXT && (externalUid?.length ?: 0) <= MAX_TEXT
}

private fun RemoteBonus.isUsableBonus(): Boolean =
    isUuid(jobSyncId) && dateEpochDay in EPOCH_DAYS &&
        amountGrosze in 1..MoneyParser.maxMinor(MAX_FRACTION_DIGITS, MoneyParser.AMOUNT_WHOLE_DIGITS) &&
        (note?.length ?: 0) <= MAX_TEXT

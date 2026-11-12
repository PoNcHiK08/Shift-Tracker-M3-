package com.vkurylo.shifttracker.data.backup

import androidx.room.withTransaction
import com.vkurylo.shifttracker.data.sync.SyncLock
import kotlinx.coroutines.sync.withLock
import com.vkurylo.shifttracker.data.local.BonusEntity
import com.vkurylo.shifttracker.data.local.JobEntity
import com.vkurylo.shifttracker.data.local.JobPaySettingsEntity
import com.vkurylo.shifttracker.data.local.ParentChildRule
import com.vkurylo.shifttracker.data.local.PayoutEntity
import com.vkurylo.shifttracker.data.local.monthKeyToYearMonth
import com.vkurylo.shifttracker.data.local.toEntity
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.ShiftEntity
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.local.newSyncId
import com.vkurylo.shifttracker.data.local.nextUpdatedAt
import com.vkurylo.shifttracker.data.local.stamped
import com.vkurylo.shifttracker.domain.repository.BackupCounts
import com.vkurylo.shifttracker.domain.calc.MoneyParser
import com.vkurylo.shifttracker.domain.model.Currencies
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.model.PayType
import com.vkurylo.shifttracker.domain.model.Payout
import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import com.vkurylo.shifttracker.domain.repository.BackupRepository
import com.vkurylo.shifttracker.domain.repository.InvalidBackupException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = CURRENT_VERSION,
    @SerialName("exported_at") val exportedAt: String,
    val jobs: List<BackupJob>,
    val shifts: List<BackupShift>,
    /** Added in backup version 3; older files simply have none. */
    val bonuses: List<BackupBonus> = emptyList(),
    /** Added in backup version 6; older files simply have none. */
    val payouts: List<BackupPayout> = emptyList(),
) {
    companion object {
        const val FORMAT = "shift-tracker-backup"

        /**
         * Every exporter so far wrote its version explicitly: 1 (jobs + shifts), 2 (+ second rates),
         * 3 (+ bonuses), 4 (+ a currency and a pay settings history with deductions per job), 5 (+ a sync id
         * per job, shift and bonus), 6 (+ whole-month bonuses and the received-pay payouts). All of them decode
         * into these classes (newer fields have defaults: an older file's jobs are PLN and hourly, its bonuses are
         * on their day, it has no payouts, and its records get new sync ids when imported).
         */
        const val MIN_VERSION = 1
        const val CURRENT_VERSION = 6
    }
}

@Serializable
data class BackupJob(
    val id: Long,
    val name: String,
    @SerialName("hourly_rate_grosze") val hourlyRateGrosze: Long,
    val color: Int,
    @SerialName("second_rate") val secondRate: BackupTimedRate? = null,
    /**
     * ISO 4217 code; the job's, its shifts' and bonuses' amounts are minor units of it (the
     * `*_grosze` names are kept from version 1). Added in backup version 4.
     */
    val currency: String = "PLN",
    /** Pay settings by month, oldest first; empty = hourly throughout. Added in backup version 4. */
    @SerialName("pay_history") val payHistory: List<BackupPaySettings> = emptyList(),
    /** The record's id on every device (a UUID). Added in backup version 5; older files have none. */
    @SerialName("sync_id") val syncId: String? = null,
)

/** One entry of a job's pay history: from month [from] ("2026-11"; null = from the beginning). */
@Serializable
data class BackupPaySettings(
    val from: String? = null,
    @SerialName("pay_type") val payType: String = "HOURLY",
    @SerialName("monthly_amount_minor") val monthlyAmountMinor: Long = 0,
    /** Deductions in percent as text, e.g. "13.71" (0–100, at most 2 decimals). */
    @SerialName("deduction_percent") val deductionPercent: String = "0",
)

/** Optional second rate between [from] and [to] (HH:mm). Added in backup version 2. */
@Serializable
data class BackupTimedRate(
    @SerialName("rate_grosze") val rateGrosze: Long,
    val from: String,
    val to: String,
)

@Serializable
data class BackupShift(
    @SerialName("job_id") val jobId: Long,
    /** ISO date, e.g. 2026-11-02. */
    val date: String,
    /** HH:mm */
    val start: String,
    val end: String,
    @SerialName("unpaid_break_minutes") val unpaidBreakMinutes: Int = 0,
    @SerialName("hourly_rate_grosze") val hourlyRateGrosze: Long,
    val note: String? = null,
    @SerialName("external_uid") val externalUid: String? = null,
    @SerialName("second_rate") val secondRate: BackupTimedRate? = null,
    /** The record's id on every device (a UUID). Added in backup version 5; older files have none. */
    @SerialName("sync_id") val syncId: String? = null,
)

@Serializable
data class BackupBonus(
    @SerialName("job_id") val jobId: Long,
    /** ISO date, e.g. 2026-08-31. */
    val date: String,
    @SerialName("amount_grosze") val amountGrosze: Long,
    val note: String? = null,
    /** The record's id on every device (a UUID). Added in backup version 5; older files have none. */
    @SerialName("sync_id") val syncId: String? = null,
    /**
     * True: the bonus is for the whole month of [date] (kept on its 1st), not for that day.
     * Added in backup version 6; older files' bonuses are all on their day.
     */
    @SerialName("whole_month") val wholeMonth: Boolean = false,
)

/**
 * The salary that actually arrived for a job, for the month it was earned for ([month], "2026-11"). A job can
 * have several for a month. Amounts are minor units of the job's currency. Added in backup version 6.
 */
@Serializable
data class BackupPayout(
    @SerialName("job_id") val jobId: Long,
    val month: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val note: String? = null,
    /** ISO date it arrived, when the user entered it. */
    @SerialName("received_on") val receivedOn: String? = null,
    @SerialName("sync_id") val syncId: String? = null,
)

@Singleton
class JsonBackupRepository @Inject constructor(
    private val db: ShiftDatabase,
    private val clock: WallClock = WallClock.System,
    private val syncLock: SyncLock = SyncLock(),
) : BackupRepository {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun exportJson(): String {
        // One read transaction, so the lists are a consistent snapshot.
        val snapshot = db.withTransaction {
            Snapshot(db.jobDao().getAll(), db.shiftDao().getAll(), db.bonusDao().getAll(), db.paySettingsDao().getAll(), db.payoutDao().getAll())
        }
        val (jobs, shifts, bonuses) = snapshot
        val history = snapshot.payHistory.groupBy { it.jobId }
        val file = BackupFile(
            exportedAt = java.time.OffsetDateTime.now().toString(),
            jobs = jobs.map {
                BackupJob(
                    id = it.id,
                    name = it.name,
                    hourlyRateGrosze = it.hourlyRateGrosze,
                    color = it.color,
                    secondRate = backupRate(it.secondRateGrosze, it.secondRateFromMinute, it.secondRateToMinute),
                    currency = it.currency,
                    payHistory = history[it.id].orEmpty().map { entry ->
                        BackupPaySettings(
                            from = monthKeyToYearMonth(entry.fromMonth)?.toString(),
                            payType = entry.payType,
                            monthlyAmountMinor = entry.monthlyAmountMinor,
                            deductionPercent = java.math.BigDecimal.valueOf(entry.deductionBp.toLong(), 2).stripTrailingZeros().toPlainString(),
                        )
                    },
                    syncId = it.syncId,
                )
            },
            shifts = shifts.map {
                BackupShift(
                    jobId = it.jobId,
                    date = java.time.LocalDate.ofEpochDay(it.dateEpochDay).toString(),
                    start = minuteToText(it.startMinute),
                    end = minuteToText(it.endMinute),
                    unpaidBreakMinutes = it.unpaidBreakMinutes,
                    hourlyRateGrosze = it.hourlyRateGrosze,
                    note = it.note,
                    externalUid = it.externalUid,
                    secondRate = backupRate(it.secondRateGrosze, it.secondRateFromMinute, it.secondRateToMinute),
                    syncId = it.syncId,
                )
            },
            bonuses = bonuses.map {
                BackupBonus(
                    jobId = it.jobId,
                    date = java.time.LocalDate.ofEpochDay(it.dateEpochDay).toString(),
                    amountGrosze = it.amountGrosze,
                    note = it.note,
                    syncId = it.syncId,
                    wholeMonth = it.wholeMonth,
                )
            },
            payouts = snapshot.payouts.mapNotNull { payout ->
                // A row whose month isn't a real month can't be written (the app never creates one).
                Payout.monthFromKey(payout.month)?.let { month ->
                    BackupPayout(
                        jobId = payout.jobId,
                        month = month.toString(),
                        amountMinor = payout.amountMinor,
                        note = payout.note,
                        receivedOn = payout.receivedEpochDay?.let { java.time.LocalDate.ofEpochDay(it).toString() },
                        syncId = payout.syncId,
                    )
                }
            },
        )
        return json.encodeToString(BackupFile.serializer(), file)
    }

    /** What a backup file holds, without importing it; null if it isn't a Shift Tracker backup. */
    fun countsOf(json: String): BackupCounts? =
        runCatching { this.json.decodeFromString(BackupFile.serializer(), json) }.getOrNull()
            ?.takeIf { it.format == BackupFile.FORMAT }
            ?.let { BackupCounts(jobs = it.jobs.size, shifts = it.shifts.size, bonuses = it.bonuses.size, payouts = it.payouts.size) }

    /** Whether this phone holds any job (shifts and bonuses always belong to one). */
    suspend fun hasData(): Boolean = db.jobDao().getAll().isNotEmpty()

    override suspend fun importJson(json: String, beforeReplace: suspend () -> Unit): BackupCounts {
        val file = try {
            this.json.decodeFromString(BackupFile.serializer(), json)
        } catch (e: IllegalArgumentException) { // includes kotlinx.serialization's SerializationException
            throw InvalidBackupException("Not a Shift Tracker backup", e)
        }
        if (file.format != BackupFile.FORMAT) throw InvalidBackupException("Unknown format ${file.format}")
        if (file.version > BackupFile.CURRENT_VERSION) {
            throw InvalidBackupException(
                "Backup version ${file.version} is newer than ${BackupFile.CURRENT_VERSION}",
                reason = InvalidBackupException.Reason.NEWER_VERSION,
            )
        }
        if (file.version < BackupFile.MIN_VERSION) throw InvalidBackupException("Unknown backup version ${file.version}")

        // Everything is validated and converted before the database is touched. The rules are the
        // ones the editors enforce, so any backup this app exported passes.
        val jobs = convert("jobs") { toJobs(file.jobs) }
        val payHistory = convert("pay settings") { file.jobs.flatMap(::toPayHistory) }
        // Job id -> decimals of its currency: shift rates and bonuses are checked against them.
        val digitsByJob = jobs.associate { it.id to Currencies.fractionDigits(it.currency) }
        val shifts = convert("shifts") { file.shifts.map { toShift(it, digitsByJob) } }
        val bonuses = convert("bonuses") { file.bonuses.map { toBonus(it, digitsByJob) } }
        val payouts = convert("received pay") { file.payouts.map { toPayout(it, digitsByJob) } }

        convert("sync ids") {
            requireUniqueSyncIds("job", jobs.map { it.syncId })
            requireUniqueSyncIds("shift", shifts.map { it.syncId })
            requireUniqueSyncIds("bonus", bonuses.map { it.syncId })
            requireUniqueSyncIds("payout", payouts.map { it.syncId })
        }

        // If any write fails, the transaction rolls back and the old data stays. A sync never runs in the middle
        // of it (SyncLock): it waits, then uploads the result.
        syncLock.mutex.withLock {
            beforeReplace()
            db.withTransaction { replaceAll(jobs, payHistory, shifts, bonuses, payouts, clock.nowMillis()) }
        }
        return BackupCounts(jobs = jobs.size, shifts = shifts.size, bonuses = bonuses.size, payouts = payouts.size)
    }

    /**
     * Makes the database hold exactly the backup's records, in a way that syncs: every live record the
     * file doesn't have becomes a tombstone (so the import replaces data on the other devices too, not
     * only here), and every record of the file is written as changed now. A record whose sync id (backup
     * version 5) is already in the database, live or deleted, is that same record: it is updated (and
     * undeleted) in place. Anything else is a new row; the file's job ids are only references inside the
     * file and are mapped to the rows' ids here.
     */
    private suspend fun replaceAll(
        jobs: List<JobEntity>,
        payHistory: List<JobPaySettingsEntity>,
        shifts: List<ShiftEntity>,
        bonuses: List<BonusEntity>,
        payouts: List<PayoutEntity>,
        now: Long,
    ) {
        val jobDao = db.jobDao()
        val shiftDao = db.shiftDao()
        val bonusDao = db.bonusDao()
        val payoutDao = db.payoutDao()
        val payDao = db.paySettingsDao()
        val keepPayouts = payouts.mapTo(HashSet()) { it.syncId }
        val keepBonuses = bonuses.mapTo(HashSet()) { it.syncId }
        val keepShifts = shifts.mapTo(HashSet()) { it.syncId }
        val keepJobs = jobs.mapTo(HashSet()) { it.syncId }
        payoutDao.liveKeys().filter { it.syncId !in keepPayouts }.forEach { payoutDao.markDeleted(it.id, now) }
        bonusDao.liveKeys().filter { it.syncId !in keepBonuses }.forEach { bonusDao.markDeleted(it.id, now) }
        shiftDao.liveKeys().filter { it.syncId !in keepShifts }.forEach { shiftDao.markDeleted(it.id, now) }
        jobDao.liveKeys().filter { it.syncId !in keepJobs }.forEach { jobDao.markDeleted(it.id, nextUpdatedAt(it.updatedAt, now)) }

        val rowIdByFileId = HashMap<Long, Long>()
        for (job in jobs) {
            val existing = jobDao.getBySyncId(job.syncId)
            val rowId = jobDao.upsert(job.copy(id = existing?.id ?: 0).stamped(existing, now))
            rowIdByFileId[job.id] = if (rowId == -1L) existing!!.id else rowId
        }
        rowIdByFileId.values.forEach { payDao.deleteForJob(it) }
        payDao.insertAll(payHistory.map { it.copy(id = 0, jobId = rowIdByFileId.getValue(it.jobId)) })
        for (shift in shifts) {
            val existing = shiftDao.getBySyncId(shift.syncId)
            shiftDao.upsert(shift.copy(id = existing?.id ?: 0, jobId = rowIdByFileId.getValue(shift.jobId)).stamped(existing, now))
        }
        for (bonus in bonuses) {
            val existing = bonusDao.getBySyncId(bonus.syncId)
            bonusDao.upsert(bonus.copy(id = existing?.id ?: 0, jobId = rowIdByFileId.getValue(bonus.jobId)).stamped(existing, now))
        }
        for (payout in payouts) {
            val existing = payoutDao.getBySyncId(payout.syncId)
            payoutDao.upsert(payout.copy(id = existing?.id ?: 0, jobId = rowIdByFileId.getValue(payout.jobId)).stamped(existing, now))
        }
        ParentChildRule.enforce(jobDao)
    }

    private fun requireUniqueSyncIds(what: String, ids: List<String>) {
        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "two of the ${what}s have the same sync id $duplicates" }
    }

    /**
     * A version 5 sync id: a UUID, kept in the canonical lowercase form the app writes (ids are
     * case-sensitive in the cloud, so a hand-edited uppercase one must not become a different record).
     * Absent (older versions): a new id.
     */
    private fun syncIdOf(syncId: String?): String {
        if (syncId == null) return newSyncId()
        val canonical = runCatching { java.util.UUID.fromString(syncId).toString() }.getOrNull()
        require(canonical != null && canonical.equals(syncId, ignoreCase = true)) { "sync id '$syncId' is not a UUID" }
        return canonical
    }

    private inline fun <T> convert(what: String, block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw InvalidBackupException("Backup contains invalid $what: ${e.message}", e)
    } catch (e: java.time.DateTimeException) {
        throw InvalidBackupException("Backup contains invalid $what: ${e.message}", e)
    }

    private fun toJobs(jobs: List<BackupJob>): List<JobEntity> {
        val duplicates = jobs.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "duplicate job ids $duplicates" }
        return jobs.map {
            require(it.id > 0) { "job id ${it.id}" }
            require(it.name.isNotBlank()) { "job ${it.id} has no name" }
            require(Currencies.isValid(it.currency)) { "job ${it.id} has an unknown currency '${it.currency}'" }
            val digits = Currencies.fractionDigits(it.currency)
            requireRate(it.hourlyRateGrosze, digits, "rate of job ${it.id}")
            val second = it.secondRate?.let { rate -> toWindow(rate, digits) }
            JobEntity(
                id = it.id,
                name = it.name,
                hourlyRateGrosze = it.hourlyRateGrosze,
                color = it.color,
                secondRateGrosze = second?.rate,
                secondRateFromMinute = second?.from ?: DEFAULT_FROM,
                secondRateToMinute = second?.to ?: DEFAULT_TO,
                currency = it.currency,
                syncId = syncIdOf(it.syncId),
            )
        }
    }

    /** A job's pay history: known types, months unique, salaries within the editor's limits. */
    private fun toPayHistory(job: BackupJob): List<JobPaySettingsEntity> {
        val digits = Currencies.fractionDigits(job.currency)
        val entries = job.payHistory.map { entry ->
            val type = PayType.entries.firstOrNull { it.name == entry.payType }
            requireNotNull(type) { "job ${job.id} has an unknown pay type '${entry.payType}'" }
            val month = entry.from?.let { java.time.YearMonth.parse(it) }
            require(entry.monthlyAmountMinor in 0..MoneyParser.maxMinor(digits, MoneyParser.AMOUNT_WHOLE_DIGITS)) {
                "monthly amount of job ${job.id} out of range: ${entry.monthlyAmountMinor}"
            }
            require(entry.deductionPercent.matches(Regex("""\d{1,3}(\.\d{1,2})?"""))) {
                "deduction of job ${job.id} is not a percentage with up to 2 decimals: '${entry.deductionPercent}'"
            }
            val bp = java.math.BigDecimal(entry.deductionPercent).movePointRight(2).toInt()
            require(bp in 0..10_000) { "deduction of job ${job.id} over 100 %: ${entry.deductionPercent}" }
            PaySettings(month, type, entry.monthlyAmountMinor, bp).toEntity(job.id)
        }
        val duplicates = entries.groupingBy { it.fromMonth }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "job ${job.id} has two pay settings for the same month" }
        return entries
    }

    private fun toShift(shift: BackupShift, digitsByJob: Map<Long, Int>): ShiftEntity {
        val digits = requireNotNull(digitsByJob[shift.jobId]) { "shift refers to unknown job ${shift.jobId}" }
        requireRate(shift.hourlyRateGrosze, digits, "shift rate")
        val start = textToMinute(shift.start)
        val end = textToMinute(shift.end)
        val duration = ShiftCalculator.durationMinutes(minuteToTime(start), minuteToTime(end))
        // Same rule as the shift editor: the break must be shorter than the shift.
        require(shift.unpaidBreakMinutes in 0 until duration) { "break ${shift.unpaidBreakMinutes} min for a $duration min shift" }
        val second = shift.secondRate?.let { toWindow(it, digits) }
        return ShiftEntity(
            jobId = shift.jobId,
            dateEpochDay = java.time.LocalDate.parse(shift.date).toEpochDay(),
            startMinute = start,
            endMinute = end,
            unpaidBreakMinutes = shift.unpaidBreakMinutes,
            hourlyRateGrosze = shift.hourlyRateGrosze,
            note = shift.note,
            externalUid = shift.externalUid,
            secondRateGrosze = second?.rate,
            secondRateFromMinute = second?.from ?: DEFAULT_FROM,
            secondRateToMinute = second?.to ?: DEFAULT_TO,
            syncId = syncIdOf(shift.syncId),
        )
    }

    private fun toBonus(bonus: BackupBonus, digitsByJob: Map<Long, Int>): BonusEntity {
        val digits = requireNotNull(digitsByJob[bonus.jobId]) { "bonus refers to unknown job ${bonus.jobId}" }
        require(bonus.amountGrosze <= MoneyParser.maxMinor(digits, MoneyParser.AMOUNT_WHOLE_DIGITS)) { "bonus out of range: ${bonus.amountGrosze}" }
        require(bonus.amountGrosze > 0) { "bonus of ${bonus.amountGrosze}" }
        val date = java.time.LocalDate.parse(bonus.date)
        return BonusEntity(
            jobId = bonus.jobId,
            // A bonus for the whole month has no day of its own: it is kept on the 1st.
            dateEpochDay = (if (bonus.wholeMonth) date.withDayOfMonth(1) else date).toEpochDay(),
            amountGrosze = bonus.amountGrosze,
            note = bonus.note,
            wholeMonth = bonus.wholeMonth,
            syncId = syncIdOf(bonus.syncId),
        )
    }

    /** Received pay: for a job of the file, a real month, an amount like a bonus's, and a real date if any. */
    private fun toPayout(payout: BackupPayout, digitsByJob: Map<Long, Int>): PayoutEntity {
        val digits = requireNotNull(digitsByJob[payout.jobId]) { "received pay refers to unknown job ${payout.jobId}" }
        val month = java.time.YearMonth.parse(payout.month)
        val key = Payout.keyOf(month)
        require(Payout.monthFromKey(key) == month) { "received pay month ${payout.month} out of range" }
        require(payout.amountMinor <= MoneyParser.maxMinor(digits, MoneyParser.AMOUNT_WHOLE_DIGITS)) { "received pay out of range: ${payout.amountMinor}" }
        require(payout.amountMinor > 0) { "received pay of ${payout.amountMinor}" }
        val receivedOn = payout.receivedOn?.let { java.time.LocalDate.parse(it) }
        require(receivedOn == null || receivedOn.year in 1..9999) { "received date ${payout.receivedOn} out of range" }
        return PayoutEntity(
            jobId = payout.jobId,
            month = key,
            amountMinor = payout.amountMinor,
            note = payout.note,
            receivedEpochDay = receivedOn?.toEpochDay(),
            syncId = syncIdOf(payout.syncId),
        )
    }

    private class Window(val rate: Long, val from: Int, val to: Int)

    private data class Snapshot(
        val jobs: List<JobEntity>,
        val shifts: List<ShiftEntity>,
        val bonuses: List<BonusEntity>,
        val payHistory: List<JobPaySettingsEntity>,
        val payouts: List<PayoutEntity>,
    )

    /** A second rate: amount in range and a non-empty window (the job editor rejects from == to). */
    private fun toWindow(rate: BackupTimedRate, digits: Int): Window {
        requireRate(rate.rateGrosze, digits, "second rate")
        val from = textToMinute(rate.from)
        val to = textToMinute(rate.to)
        require(from != to) { "second-rate window ${rate.from}–${rate.to} is empty" }
        return Window(rate.rateGrosze, from, to)
    }

    /** A rate in minor units of a currency with [digits] decimals, within what the editors accept. */
    private fun requireRate(minor: Long, digits: Int, what: String) =
        require(minor in 0..MoneyParser.maxMinor(digits)) { "$what out of range: $minor" }

    private fun backupRate(grosze: Long?, fromMinute: Int, toMinute: Int): BackupTimedRate? =
        grosze?.let { BackupTimedRate(it, minuteToText(fromMinute), minuteToText(toMinute)) }

    private companion object {
        const val DEFAULT_FROM = 23 * 60
        const val DEFAULT_TO = 6 * 60
    }

    private fun minuteToText(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

    private fun textToMinute(text: String): Int {
        val time = java.time.LocalTime.parse(text)
        return time.hour * 60 + time.minute
    }

    private fun minuteToTime(minute: Int) = java.time.LocalTime.of(minute / 60, minute % 60)
}

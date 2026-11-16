package com.vkurylo.shifttracker.web

import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.data.local.ShiftDatabase
import com.vkurylo.shifttracker.data.local.WallClock
import com.vkurylo.shifttracker.data.repository.JobDetailsSource
import com.vkurylo.shifttracker.data.repository.RoomBonusRepository
import com.vkurylo.shifttracker.data.repository.RoomJobRepository
import com.vkurylo.shifttracker.data.repository.RoomPayoutRepository
import com.vkurylo.shifttracker.data.repository.RoomShiftRepository
import com.vkurylo.shifttracker.data.sync.CloudAccount
import com.vkurylo.shifttracker.data.sync.FakeRemoteStore
import com.vkurylo.shifttracker.data.sync.InMemorySyncStateStore
import com.vkurylo.shifttracker.data.sync.RemoteBonus
import com.vkurylo.shifttracker.data.sync.RemoteJob
import com.vkurylo.shifttracker.data.sync.RemotePaySettings
import com.vkurylo.shifttracker.data.sync.RemotePayout
import com.vkurylo.shifttracker.data.sync.RemoteRecord
import com.vkurylo.shifttracker.data.sync.RemoteShift
import com.vkurylo.shifttracker.data.sync.RemoteTimedRate
import com.vkurylo.shifttracker.data.sync.SignInChoice
import com.vkurylo.shifttracker.data.sync.SyncEngine
import com.vkurylo.shifttracker.data.sync.firebase.CloudCollection
import com.vkurylo.shifttracker.data.sync.firebase.readRecord
import com.vkurylo.shifttracker.data.sync.firebase.toFields
import com.vkurylo.shifttracker.domain.calc.JobYearReconciliation
import com.vkurylo.shifttracker.domain.calc.MoneyParser
import com.vkurylo.shifttracker.domain.calc.PayReconciliation
import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import com.vkurylo.shifttracker.domain.model.Amounts
import com.vkurylo.shifttracker.domain.model.Currencies
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.JobSummary
import com.vkurylo.shifttracker.domain.model.MonthSummary
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.model.ThemeMode
import com.vkurylo.shifttracker.domain.model.TimedRate
import com.vkurylo.shifttracker.domain.model.UserPreferences
import com.vkurylo.shifttracker.domain.model.paysHourly
import com.vkurylo.shifttracker.domain.repository.PreferencesRepository
import com.vkurylo.shifttracker.ui.calendar.CalendarViewModel
import com.vkurylo.shifttracker.ui.summary.BreakdownEntry
import com.vkurylo.shifttracker.ui.summary.SummaryPeriod
import com.vkurylo.shifttracker.ui.summary.SummaryUiState
import com.vkurylo.shifttracker.ui.summary.SummaryViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.util.Random

/**
 * Golden fixtures for the web version (web/): the app's own figures, so the web's TypeScript port of the pay logic can
 * be checked against them number by number (web/test/unit/parity.test.ts).
 *
 * - calc.json: ShiftCalculator on a broad set of shifts (midnight, breaks, timed second rates, large rates) and
 *   the net rule on amounts and deductions.
 * - datasets/<name>.json: a user's cloud documents (the Firestore shape of FirestoreDocuments.kt) and what the app
 *   shows for them. Each dataset goes the way a phone takes it in: documents -> readRecord -> the real SyncEngine
 *   (download into Room: unusable records skipped, ParentChildRule) -> the real CalendarViewModel and
 *   SummaryViewModel, gross and net. "demo" is the web's demo data (web/src/demo/demo-documents.json).
 *
 * By default this test only checks that the committed fixtures are what the app computes now, and fails when they
 * are out of date. To write them again (after changing the pay logic, or the demo data):
 *
 *     UPDATE_WEB_FIXTURES=1 ./gradlew testDebugUnitTest --tests "com.vkurylo.shifttracker.web.WebFixturesExport"
 *
 * (Windows PowerShell: `$env:UPDATE_WEB_FIXTURES=1; .\gradlew.bat testDebugUnitTest --tests "..."`), then commit them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WebFixturesExport {

    private val fixtures = File("../web/test/fixtures")
    private val update = System.getenv("UPDATE_WEB_FIXTURES") == "1"
    private val databases = mutableListOf<ShiftDatabase>()

    @After
    fun tearDown() = databases.forEach { it.close() }

    // --- The fixtures ---------------------------------------------------------------------------------

    @Test
    fun calculations() = check("calc.json", calculationFixture())

    @Test
    fun demoData() {
        val documents = File("../web/src/demo/demo-documents.json").readText()
        check("datasets/demo.json", datasetFixture("demo", parseDocuments(documents)))
    }

    @Test
    fun edgeCases() = check("datasets/edge.json", datasetFixture("edge", EdgeData.documents()))

    @Test
    fun randomYear() = check("datasets/random.json", datasetFixture("random", RandomData(seed = 20_261_113L).documents()))

    private fun check(name: String, content: String) {
        val file = File(fixtures, name)
        if (update) {
            file.parentFile!!.mkdirs()
            file.writeText(content)
            return
        }
        assertTrue("$file is missing: run with UPDATE_WEB_FIXTURES=1 (see the class comment)", file.exists())
        assertEquals(
            "$file is out of date: run with UPDATE_WEB_FIXTURES=1 (see the class comment) and commit it",
            content,
            file.readText().replace("\r\n", "\n"),
        )
    }

    // --- calc.json --------------------------------------------------------------------------------------

    private fun calculationFixture(): String {
        val random = Random(7L)
        val minutes = listOf(0, 1, 59, 359, 360, 361, 420, 479, 720, 1079, 1080, 1320, 1379, 1380, 1381, 1439)
        val rates = listOf(0L, 1L, 2L, 59L, 3150L, 3333L, 999_999_999L, MoneyParser.maxMinor(3))
        val windows = listOf(
            null,
            TimedRate(3700, LocalTime.of(23, 0), LocalTime.of(6, 0)),
            TimedRate(2000, LocalTime.of(6, 0), LocalTime.of(22, 0)),
            TimedRate(1, LocalTime.of(0, 0), LocalTime.of(23, 59)),
            TimedRate(4100, LocalTime.of(22, 0), LocalTime.of(22, 1)),
            TimedRate(5000, LocalTime.of(12, 0), LocalTime.of(12, 0)),
            TimedRate(MoneyParser.maxMinor(3), LocalTime.of(0, 0), LocalTime.of(0, 1)),
        )
        val cases = mutableListOf<JsonElement>()
        fun case(start: Int, end: Int, breakMinutes: Int, rate: Long, second: TimedRate?) {
            val pay = ShiftCalculator.pay(time(start), time(end), breakMinutes, rate, second)
            cases += obj(
                "start" to start, "end" to end, "break" to breakMinutes, "rate" to money(rate),
                "second" to (second?.let { obj("rate" to money(it.rateGrosze), "from" to minuteOf(it.from), "to" to minuteOf(it.to)) } ?: JsonNull),
                "durationMinutes" to pay.durationMinutes, "paidMinutes" to pay.paidMinutes,
                "secondRateMinutes" to pay.secondRateMinutes, "earnings" to money(pay.earningsGrosze),
                "overnight" to ShiftCalculator.isOvernight(time(start), time(end)),
            )
        }
        // Every start and end of the grid, with and without the night window.
        for (start in minutes) for (end in minutes) {
            case(start, end, 0, 3150, null)
            case(start, end, 30.coerceAtMost(ShiftCalculator.durationMinutes(time(start), time(end)).toInt() - 1), 3333, windows[1])
        }
        // Every window and rate on a few shifts, breaks up to the whole shift (and beyond, which the calculator caps).
        for (window in windows) for (rate in rates) {
            case(22 * 60, 6 * 60, 0, rate, window?.copy(rateGrosze = rate / 2 + 1))
            case(0, 0, 0, rate, window)
            case(7 * 60 + 13, 15 * 60 + 47, 37, rate, window)
            case(23 * 60 + 59, 0, 0, rate, window)
        }
        for (breakMinutes in listOf(0, 1, 29, 30, 479, 480, 481, 2000, -5)) case(8 * 60, 16 * 60, breakMinutes, 3150, windows[1])
        // Random shifts: any minute, any break, any rate in range, half of them with a random window.
        repeat(1200) {
            val start = random.nextInt(1440)
            val end = random.nextInt(1440)
            val duration = ShiftCalculator.durationMinutes(time(start), time(end)).toInt()
            val breakMinutes = if (random.nextBoolean()) 0 else random.nextInt(duration)
            val rate = randomMoney(random, MoneyParser.maxMinor(if (random.nextInt(4) == 0) 3 else 2))
            val window = if (random.nextBoolean()) null else {
                TimedRate(randomMoney(random, MoneyParser.maxMinor(2)), time(random.nextInt(1440)), time(random.nextInt(1440)))
            }
            case(start, end, breakMinutes, rate, window)
        }

        val net = mutableListOf<JsonElement>()
        val grossValues = listOf(-5L, 0L, 1L, 2L, 49L, 50L, 99L, 4_999L, 5_000L, 9_999L, 10_000L, 12_345L, 1_234_567L) +
            listOf(MoneyParser.maxMinor(2, MoneyParser.AMOUNT_WHOLE_DIGITS), MoneyParser.maxMinor(3, MoneyParser.AMOUNT_WHOLE_DIGITS)) +
            List(200) { randomMoney(random, MoneyParser.maxMinor(3, MoneyParser.AMOUNT_WHOLE_DIGITS)) }
        val bps = listOf(-1, 0, 1, 1371, 2271, 4999, 5000, 5001, 9999, 10_000)
        for (gross in grossValues) for (bp in bps) {
            net += obj("gross" to money(gross), "bp" to bp, "net" to money(ShiftCalculator.net(gross, bp)))
        }
        // The currencies a job may have (Currencies.isValid) and their decimals, as the app checks and formats them.
        val currencies = java.util.Currency.getAvailableCurrencies().map { it.currencyCode }.sorted()
            .filter(Currencies::isValid)
            .map { obj("code" to it, "digits" to Currencies.fractionDigits(it)) }
        return render(obj("shifts" to JsonArray(cases), "net" to JsonArray(net), "currencies" to JsonArray(currencies)))
    }

    private fun randomMoney(random: Random, max: Long): Long = when (random.nextInt(5)) {
        0 -> random.nextInt(100).toLong()
        1 -> max - random.nextInt(100)
        else -> Math.floorMod(random.nextLong(), max + 1)
    }

    // --- datasets/<name>.json ---------------------------------------------------------------------------

    /** What the app shows for [documents]: jobs, every month of the calendar, and the Summary's weeks, months and years. */
    private fun datasetFixture(name: String, documents: Map<String, Map<String, Map<String, Any?>>>): String {
        val phone = downloadToAPhone(documents)
        val ids = Ids(phone)
        val shifts = runBlocking { phone.shiftDao().getAll() }
        val bonuses = runBlocking { phone.bonusDao().getAll() }
        val payouts = runBlocking { phone.payoutDao().getAll() }
        val days = (shifts.map { it.dateEpochDay } + bonuses.map { it.dateEpochDay }).map(LocalDate::ofEpochDay) +
            payouts.map { YearMonth.of(it.month / 100, it.month % 100).atDay(1) }
        val first = YearMonth.from(days.minOrNull() ?: LocalDate.of(2026, 11, 1)).minusMonths(1)
        val last = YearMonth.from(days.maxOrNull() ?: LocalDate.of(2026, 11, 1)).plusMonths(1)
        val months = generateSequence(first) { it.plusMonths(1) }.takeWhile { !it.isAfter(last) }.toList()
        val weeks = generateSequence(first.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(last.atEndOfMonth()) }.toList()
        val years = months.map { it.year }.distinct()

        val repositories = Repositories(phone)
        val jobs = runBlocking { repositories.jobs.observeJobs().first() }
        val expected = obj(
            "jobs" to JsonArray(jobs.map { job(it, ids) }),
            "modes" to obj(
                *EarningsMode.entries.map { mode ->
                    mode.name to obj(
                        "calendar" to JsonArray(months.map { calendar(repositories, mode, it, ids) }),
                        "summary" to JsonArray(
                            weeks.map { summary(repositories, mode, SummaryPeriod.WEEK, it, ids) } +
                                months.map { summary(repositories, mode, SummaryPeriod.MONTH, it.atDay(1), ids) } +
                                years.map { summary(repositories, mode, SummaryPeriod.YEAR, LocalDate.of(it, 1, 1), ids) },
                        ),
                    )
                }.toTypedArray(),
            ),
        )
        return render(obj("name" to name, "documents" to json(documents), "expected" to expected))
    }

    /** A new phone signs in with an account that has [documents] in the cloud and downloads them (SyncEngine). */
    private fun downloadToAPhone(documents: Map<String, Map<String, Map<String, Any?>>>): ShiftDatabase {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShiftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { databases += it }
        val cloud = FakeRemoteStore()
        val account = CloudAccount("uid-fixtures", "fixtures@example.com")
        for (collection in CloudCollection.entries) {
            documents[collection.path].orEmpty().forEach { (id, fields) -> cloud.put(account.uid, readRecord(collection, id, fields)) }
        }
        val engine = SyncEngine(db, cloud, InMemorySyncStateStore(), {}, WallClock { 4_000_000_000_000L })
        runBlocking { engine.completeSignIn(account, SignInChoice.DOWNLOAD) }
        return db
    }

    /** Local row ids -> the sync ids (document ids) the web knows records by. */
    private class Ids(db: ShiftDatabase) {
        val jobs = runBlocking { db.jobDao().getAll() }.associate { it.id to it.syncId }
        val shifts = runBlocking { db.shiftDao().getAll() }.associate { it.id to it.syncId }
        val bonuses = runBlocking { db.bonusDao().getAll() }.associate { it.id to it.syncId }
        val payouts = runBlocking { db.payoutDao().getAll() }.associate { it.id to it.syncId }
    }

    private class Repositories(db: ShiftDatabase) {
        private val details = JobDetailsSource(db.paySettingsDao())
        val jobs = RoomJobRepository(db, db.jobDao(), db.paySettingsDao(), details)
        val shifts = RoomShiftRepository(db, db.shiftDao(), details)
        val bonuses = RoomBonusRepository(db, db.bonusDao(), details)
        val payouts = RoomPayoutRepository(db, db.payoutDao(), details)

        fun preferences(mode: EarningsMode) = object : PreferencesRepository {
            override val preferences: Flow<UserPreferences> = flowOf(UserPreferences(earningsMode = mode))
            override suspend fun setThemeMode(mode: ThemeMode) = Unit
            override suspend fun setDynamicColor(enabled: Boolean) = Unit
            override suspend fun setEarningsMode(mode: EarningsMode) = Unit
        }
    }

    /** Lets the view model's coroutines (on the main looper) run until [condition] holds. */
    private fun <T> settle(read: () -> T, condition: (T) -> Boolean): T {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val value = read()
            if (condition(value)) return value
            Thread.sleep(2)
        }
        throw AssertionError("timed out")
    }

    /** The calendar of [month] as the app shows it: the month's tiles, its month bonuses, and every day's panel. */
    private fun calendar(repositories: Repositories, mode: EarningsMode, month: YearMonth, ids: Ids): JsonElement {
        val viewModel = CalendarViewModel(
            SavedStateHandle(mapOf("selectedDate" to month.atDay(1).toEpochDay())),
            repositories.shifts, repositories.bonuses, repositories.jobs, repositories.preferences(mode), repositories.payouts,
        )
        viewModel.showMonth(month)
        val watching = CoroutineScope(Dispatchers.Unconfined).launch { viewModel.uiState.collect {} }
        try {
            val state = settle({ viewModel.uiState.value }) { !it.loading && it.month == month }
            val dates = (state.shiftsByDate.keys + state.bonusesByDate.keys).filter { YearMonth.from(it) == month }.sorted()
            return obj(
                "month" to month.toString(),
                "summary" to summary(state.monthSummary, ids),
                "monthBonuses" to JsonArray(state.monthBonuses.map { bonusLine(it, mode, ids) }),
                "days" to JsonArray(
                    dates.map { date ->
                        val shifts = state.shiftsByDate[date].orEmpty()
                        val bonuses = state.bonusesByDate[date].orEmpty()
                        obj(
                            "date" to date.toString(),
                            "paidMinutes" to shifts.sumOf { it.shift.paidMinutes },
                            "total" to amounts(ShiftCalculator.earnings(shifts, bonuses, mode)),
                            "shifts" to JsonArray(
                                shifts.map {
                                    obj(
                                        "id" to ids.shifts.getValue(it.shift.id),
                                        "job" to ids.jobs.getValue(it.job.id),
                                        "paysHourly" to it.job.paysHourly(it.shift.date),
                                        "paidMinutes" to it.shift.paidMinutes,
                                        "earnings" to money(ShiftCalculator.shiftEarnings(it, mode)),
                                    )
                                },
                            ),
                            "bonuses" to JsonArray(bonuses.map { bonusLine(it, mode, ids) }),
                        )
                    },
                ),
            )
        } finally {
            watching.cancel()
        }
    }

    private fun bonusLine(item: com.vkurylo.shifttracker.domain.model.BonusWithJob, mode: EarningsMode, ids: Ids) = obj(
        "id" to ids.bonuses.getValue(item.bonus.id),
        "job" to ids.jobs.getValue(item.job.id),
        "amount" to money(ShiftCalculator.bonusAmount(item, mode)),
    )

    /** Summary -> [period] containing [anchor]: totals, by job, breakdown, month bonuses and received pay. */
    private fun summary(repositories: Repositories, mode: EarningsMode, period: SummaryPeriod, anchor: LocalDate, ids: Ids): JsonElement {
        val viewModel = SummaryViewModel(
            SavedStateHandle(mapOf("period" to period.name, "anchor" to anchor.toEpochDay())),
            repositories.shifts, repositories.bonuses, repositories.jobs, repositories.preferences(mode), repositories.payouts,
        )
        val watching = CoroutineScope(Dispatchers.Unconfined).launch { viewModel.uiState.collect {} }
        try {
            val state: SummaryUiState = settle({ viewModel.uiState.value }) { !it.loading && it.range.period == period }
            return obj(
                "period" to period.name,
                "from" to state.range.from.toString(),
                "to" to state.range.to.toString(),
                "summary" to summary(state.summary, ids),
                "breakdown" to JsonArray(state.breakdown.map(::breakdown)),
                "monthBonuses" to JsonArray(state.monthBonuses.map { bonusLine(it, mode, ids) }),
                "receivedPay" to JsonArray(state.receivedPay.map { reconciliation(it, ids) }),
                "receivedPayYear" to JsonArray(state.receivedPayYear.map { yearReconciliation(it, ids) }),
            )
        } finally {
            watching.cancel()
        }
    }

    private fun summary(summary: MonthSummary, ids: Ids): JsonElement = obj(
        "totalPaidMinutes" to summary.totalPaidMinutes,
        "shiftCount" to summary.shiftCount,
        "bonusCount" to summary.bonusCount,
        "fixedPayShiftCount" to summary.fixedPayShiftCount,
        "isEmpty" to summary.isEmpty,
        "receivedPart" to summary.receivedPart.name,
        "shiftEarnings" to amounts(summary.shiftEarnings),
        "bonuses" to amounts(summary.bonuses),
        "salaries" to amounts(summary.salaries),
        "received" to amounts(summary.received),
        "replaced" to amounts(summary.replaced),
        "replacedBonuses" to amounts(summary.replacedBonuses),
        "replacedSalaries" to amounts(summary.replacedSalaries),
        "calculatedEarnings" to amounts(summary.calculatedEarnings),
        "totalEarnings" to amounts(summary.totalEarnings),
        "countedBonuses" to amounts(summary.countedBonuses),
        "countedSalaries" to amounts(summary.countedSalaries),
        "currencies" to JsonArray(summary.currencies.map(::JsonPrimitive)),
        "averageRates" to JsonArray(
            summary.perJob.map { it.currency }.distinct().map { obj("currency" to it, "rate" to money(summary.averageRate(it))) },
        ),
        "perJob" to JsonArray(summary.perJob.map { jobSummary(it, ids) }),
    )

    private fun jobSummary(it: JobSummary, ids: Ids): JsonElement = obj(
        "job" to ids.jobs.getValue(it.job.id),
        "currency" to it.currency,
        "paidMinutes" to it.paidMinutes,
        "hourlyPaidMinutes" to it.hourlyPaidMinutes,
        "shiftCount" to it.shiftCount,
        "shiftEarnings" to money(it.shiftEarningsGrosze),
        "bonus" to money(it.bonusGrosze),
        "salary" to money(it.salaryMinor),
        "received" to money(it.receivedMinor),
        "replaced" to money(it.replacedMinor),
        "replacedBonus" to money(it.replacedBonusMinor),
        "replacedSalary" to money(it.replacedSalaryMinor),
        "receivedMonths" to it.receivedMonths,
        "calculated" to money(it.calculatedEarningsMinor),
        "earnings" to money(it.earningsGrosze),
        "countedBonus" to money(it.countedBonusMinor),
        "countedSalary" to money(it.countedSalaryMinor),
        "receivedPart" to it.receivedPart.name,
    )

    private fun breakdown(entry: BreakdownEntry): JsonElement = obj(
        "date" to entry.date.toString(),
        "paidMinutes" to entry.paidMinutes,
        "earnings" to amounts(entry.earnings),
        "shiftCount" to entry.shiftCount,
        "bonusCount" to entry.bonusCount,
        "hasSalary" to entry.hasSalary,
        "receivedPart" to entry.receivedPart.name,
        "isEmpty" to entry.isEmpty,
    )

    private fun reconciliation(row: PayReconciliation, ids: Ids): JsonElement = obj(
        "job" to ids.jobs.getValue(row.job.id),
        "month" to row.month.toString(),
        "calculatedNet" to money(row.calculatedNetMinor),
        "payouts" to JsonArray(row.payouts.map { JsonPrimitive(ids.payouts.getValue(it.id)) }),
        "received" to money(row.receivedMinor),
        "difference" to (row.differenceMinor?.let(::money) ?: JsonNull),
    )

    private fun yearReconciliation(line: JobYearReconciliation, ids: Ids): JsonElement = obj(
        "job" to ids.jobs.getValue(line.job.id),
        "months" to JsonArray(line.months.map { reconciliation(it, ids) }),
        "calculatedNet" to money(line.calculatedNetMinor),
        "received" to money(line.receivedMinor),
        "difference" to money(line.differenceMinor),
    )

    /** A job of the Jobs screen (live jobs, as listed), with what the web needs to check its own reading. */
    private fun job(job: Job, ids: Ids): JsonElement = obj(
        "id" to ids.jobs.getValue(job.id),
        "name" to job.name,
        "currency" to job.currency,
        "hourlyRate" to money(job.hourlyRateGrosze),
        "color" to job.color,
        "secondRate" to (job.secondRate?.let { obj("rate" to money(it.rateGrosze), "from" to minuteOf(it.from), "to" to minuteOf(it.to)) } ?: JsonNull),
        "payHistory" to JsonArray(job.payHistory.map(::paySettings)),
        "firstEntry" to (job.firstEntry?.toString()?.let(::JsonPrimitive) ?: JsonNull),
        "lastEntry" to (job.lastEntry?.toString()?.let(::JsonPrimitive) ?: JsonNull),
    )

    private fun paySettings(pay: PaySettings): JsonElement = obj(
        "from" to (pay.from?.toString()?.let(::JsonPrimitive) ?: JsonNull),
        "payType" to pay.payType.name,
        "monthlyAmount" to money(pay.monthlyAmountMinor),
        "deductionBp" to pay.deductionBp,
    )

    // --- JSON ------------------------------------------------------------------------------------------

    /** Money as a string: minor units may be beyond what a JavaScript number holds exactly. */
    private fun money(value: Long): JsonElement = JsonPrimitive(value.toString())

    /** Amounts in their order (the app shows the largest first, ties in this order). */
    private fun amounts(amounts: Amounts): JsonElement =
        JsonArray(amounts.byCurrency.map { (currency, value) -> JsonArray(listOf(JsonPrimitive(currency), money(value))) })

    private fun time(minute: Int): LocalTime = LocalTime.of(minute / 60, minute % 60)
    private fun minuteOf(time: LocalTime): Int = time.hour * 60 + time.minute

    private fun obj(vararg entries: Pair<String, Any?>): JsonObject = JsonObject(linkedMapOf(*entries.map { (k, v) -> k to json(v) }.toTypedArray()))

    private fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associateTo(LinkedHashMap()) { (k, v) -> k as String to json(v) })
        is List<*> -> JsonArray(value.map(::json))
        else -> error("Not JSON: $value")
    }

    private fun render(element: JsonElement): String = Json.encodeToString(JsonElement.serializer(), element) + "\n"

    private fun parseDocuments(text: String): Map<String, Map<String, Map<String, Any?>>> {
        @Suppress("UNCHECKED_CAST")
        return plain(Json.parseToJsonElement(text)) as Map<String, Map<String, Map<String, Any?>>>
    }

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

    // --- Datasets built here ------------------------------------------------------------------------------

    /** Hand-picked cases: deleted and unreadable documents, deleted jobs, currencies, limits, DST nights, year end. */
    private object EdgeData {
        private var next = 0
        private fun id(): String = "e0000000-0000-4000-8000-%012d".format(++next)

        fun documents(): Map<String, Map<String, Map<String, Any?>>> {
            val docs = linkedMapOf<String, LinkedHashMap<String, Map<String, Any?>>>()
            CloudCollection.entries.forEach { docs[it.path] = LinkedHashMap() }
            fun put(record: RemoteRecord, edit: (MutableMap<String, Any?>) -> Unit = {}) {
                val collection = when (record) {
                    is RemoteJob -> "jobs"
                    is RemoteShift -> "shifts"
                    is RemoteBonus -> "bonuses"
                    is RemotePayout -> "payouts"
                }
                docs.getValue(collection)[record.syncId] = record.toFields().toMutableMap().also(edit)
            }
            var at = 1_790_000_000_000L
            fun t() = ++at
            fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()
            val night = RemoteTimedRate(3700, 23 * 60, 6 * 60)
            fun shift(job: String, date: Long, start: Int, end: Int, rate: Long, breakMinutes: Int = 0, second: RemoteTimedRate? = null, deleted: Boolean = false, updatedAt: Long = t()) =
                RemoteShift(id(), updatedAt, deleted, job, date, start, end, breakMinutes, rate, null, null, second)
            fun bonus(job: String, date: Long, amount: Long, wholeMonth: Boolean = false, deleted: Boolean = false, updatedAt: Long = t()) =
                RemoteBonus(id(), updatedAt, deleted, job, date, amount, null, wholeMonth)
            fun payout(job: String, month: Int, amount: Long, deleted: Boolean = false) = RemotePayout(id(), t(), deleted, job, month, amount, null, null)
            fun hourly(bp: Int = 0, from: Int = 0) = RemotePaySettings(from, "HOURLY", 0, bp)

            // A night job in PLN with 13.71 % from October and a fixed salary from February 2027.
            val night1 = id()
            put(RemoteJob(night1, t(), false, "Magazyn", 3300, -16738333, night, "PLN", listOf(hourly(), hourly(1371, 202610), RemotePaySettings(202702, "FIXED_MONTHLY", 480_000, 1371))))
            // The nights the clocks change in Europe/Warsaw, and a night over the end of the year.
            put(shift(night1, day(2026, 10, 24), 22 * 60, 6 * 60, 3300, 30, night))
            put(shift(night1, day(2027, 3, 27), 22 * 60, 6 * 60, 3300, 30, night))
            put(shift(night1, day(2026, 12, 31), 22 * 60, 6 * 60, 3300, 0, night))
            put(shift(night1, day(2027, 1, 1), 0, 8 * 60, 3300, 15, night))
            put(shift(night1, day(2026, 10, 31), 23 * 60 + 30, 7 * 60 + 15, 3500, 45, night))
            put(shift(night1, day(2026, 11, 30), 18 * 60, 18 * 60, 3300, 60, night)) // 24 h
            put(shift(night1, day(2027, 2, 10), 9 * 60, 17 * 60, 3300)) // salaried month: hours only
            put(shift(night1, day(2027, 4, 5), 9 * 60, 17 * 60, 3300))
            // Month bonuses (and one written by an app version without wholeMonth: a day bonus).
            put(bonus(night1, day(2026, 10, 1), 30_000, wholeMonth = true))
            put(bonus(night1, day(2026, 12, 1), 25_000, wholeMonth = true))
            put(bonus(night1, day(2026, 12, 15), 4_000)) { it.remove("wholeMonth") }
            put(bonus(night1, day(2027, 3, 1), 20_000, wholeMonth = true))
            // Payouts: two for one month, one for a month without entries, one deleted.
            put(payout(night1, 202610, 150_000))
            put(payout(night1, 202610, 82_345))
            put(payout(night1, 202701, 99_000))
            put(payout(night1, 202606, 10_000))
            put(payout(night1, 202611, 1, deleted = true))

            // Currencies with 0 and 3 decimals, and the largest rates and amounts the app accepts.
            val yen = id()
            put(RemoteJob(yen, t(), false, "東京", 1_200, -7617718, null, "JPY", emptyList()))
            put(shift(yen, day(2026, 11, 3), 10 * 60, 18 * 60 + 7, 1_200, 37))
            put(shift(yen, day(2026, 11, 4), 10 * 60, 10 * 60 + 1, MoneyParser.maxMinor(0)))
            put(bonus(yen, day(2026, 11, 4), MoneyParser.maxMinor(0, MoneyParser.AMOUNT_WHOLE_DIGITS)))
            val dinar = id()
            put(RemoteJob(dinar, t(), false, "Kuwait", MoneyParser.maxMinor(3), -14273992, RemoteTimedRate(MoneyParser.maxMinor(3), 0, 1), "KWD", listOf(hourly(10_000))))
            put(shift(dinar, day(2026, 11, 5), 0, 0, MoneyParser.maxMinor(3), 0, RemoteTimedRate(MoneyParser.maxMinor(3), 0, 1)))
            put(shift(dinar, day(2026, 11, 6), 7 * 60 + 1, 7 * 60, MoneyParser.maxMinor(3), 1438))
            put(bonus(dinar, day(2026, 11, 1), MoneyParser.maxMinor(3, MoneyParser.AMOUNT_WHOLE_DIGITS), wholeMonth = true))
            put(payout(dinar, 202611, MoneyParser.maxMinor(3, MoneyParser.AMOUNT_WHOLE_DIGITS)))

            // A salaried job with a gap month (counted: between its first and last entry) and nothing before or after.
            val office = id()
            put(RemoteJob(office, t(), false, "biuro", 3000, -4056997, null, "PLN", listOf(RemotePaySettings(0, "FIXED_MONTHLY", 520_000, 2271))))
            put(shift(office, day(2026, 9, 2), 9 * 60, 17 * 60, 3000, 60))
            put(shift(office, day(2026, 11, 2), 9 * 60, 17 * 60, 3000, 60))
            // A pay type a newer app version might write: read as hourly.
            val future = id()
            put(RemoteJob(future, t(), false, "Biuro", 2000, -43230, null, "PLN", listOf(RemotePaySettings(0, "PER_SHIFT", 0, 0))))
            put(shift(future, day(2026, 11, 2), 8 * 60, 12 * 60, 2000))

            // A job deleted on one phone while another, later, added a shift: the job comes back with that shift only.
            val revived = id()
            put(RemoteJob(revived, 1_800_000_000_000L, true, "Sklep", 3100, -1499549, null, "PLN", emptyList()))
            put(shift(revived, day(2026, 11, 10), 8 * 60, 16 * 60, 3100, updatedAt = 1_799_000_000_000L)) // older: deleted with it
            put(shift(revived, day(2026, 11, 11), 8 * 60, 16 * 60, 3100, updatedAt = 1_800_000_000_500L)) // newer: brings the job back
            put(bonus(revived, day(2026, 11, 10), 5_000, updatedAt = 1_799_000_000_000L))
            // A job deleted with everything older than the deletion: none of it shows.
            val gone = id()
            put(RemoteJob(gone, 1_800_000_000_000L, true, "Stara", 2500, -6543440, null, "PLN", emptyList()))
            put(shift(gone, day(2026, 11, 12), 8 * 60, 16 * 60, 2500, updatedAt = 1_799_000_000_000L))
            put(payout(gone, 202611, 50_000))

            // Deleted records, and records the app can't use: never shown.
            put(shift(night1, day(2026, 11, 20), 8 * 60, 16 * 60, 3300, deleted = true))
            put(bonus(night1, day(2026, 11, 20), 7_000, deleted = true))
            put(shift(night1, day(2026, 11, 21), 8 * 60, 16 * 60, 3300)) { it.remove("note") } // a field missing
            put(shift(night1, day(2026, 11, 22), 8 * 60, 16 * 60, 3300)) { it["hourlyRateGrosze"] = "3300" } // wrong type
            put(shift(night1, day(2026, 11, 23), 8 * 60, 16 * 60, 3300)) { it["v"] = 2L } // a newer layout
            put(shift(night1, day(2026, 11, 24), 8 * 60, 9 * 60, 3300, breakMinutes = 60)) // break as long as the shift
            put(shift(night1, day(2026, 11, 25), 8 * 60, 16 * 60, -1)) // negative rate
            put(shift(id(), day(2026, 11, 26), 8 * 60, 16 * 60, 3300)) // its job doesn't exist
            put(bonus(night1, day(2026, 11, 27), 0)) // zero bonus
            put(payout(night1, 202613, 1_000)) // no such month
            put(RemoteJob(id(), t(), false, " ", 3300, 0, null, "PLN", emptyList())) // blank name
            put(RemoteJob(id(), t(), false, "Bad currency", 3300, 0, null, "XYZ", emptyList()))
            return docs
        }
    }

    /** A year of made-up but plausible data, from July 2026 to June 2027, with every kind of record. */
    private class RandomData(seed: Long) {
        private val random = Random(seed)
        private var next = 0
        private fun id(): String = "a0000000-0000-4000-8000-%012d".format(++next)
        private var at = 1_782_000_000_000L
        private fun t() = ++at

        fun documents(): Map<String, Map<String, Map<String, Any?>>> {
            val docs = linkedMapOf<String, LinkedHashMap<String, Map<String, Any?>>>()
            CloudCollection.entries.forEach { docs[it.path] = LinkedHashMap() }
            fun put(collection: String, record: RemoteRecord) {
                docs.getValue(collection)[record.syncId] = record.toFields()
            }
            val night = RemoteTimedRate(3700, 23 * 60, 6 * 60)
            val day = RemoteTimedRate(2000, 6 * 60, 22 * 60)
            class J(val id: String, val rate: Long, val second: RemoteTimedRate?, val currency: String, val weight: Int)
            val jobs = listOf(
                J(id(), 3150, night, "PLN", 5).also {
                    put("jobs", RemoteJob(it.id, t(), false, "Kawiarnia", it.rate, -12345, night, "PLN", listOf(
                        RemotePaySettings(0, "HOURLY", 0, 0), RemotePaySettings(202610, "HOURLY", 0, 1371),
                        RemotePaySettings(202703, "FIXED_MONTHLY", 480_000, 1371), RemotePaySettings(202705, "HOURLY", 0, 0),
                    )))
                },
                J(id(), 3000, null, "PLN", 3).also {
                    put("jobs", RemoteJob(it.id, t(), false, "Biuro", it.rate, -54321, null, "PLN", listOf(RemotePaySettings(0, "FIXED_MONTHLY", 520_000, 2271))))
                },
                J(id(), 1800, day, "EUR", 2).also {
                    put("jobs", RemoteJob(it.id, t(), false, "Переклади", it.rate, -999, day, "EUR", emptyList()))
                },
                J(id(), 1_250, null, "JPY", 1).also {
                    put("jobs", RemoteJob(it.id, t(), false, "Tokyo", it.rate, -1, null, "JPY", listOf(RemotePaySettings(0, "HOURLY", 0, 500))))
                },
            )
            val deletedJob = id()
            put("jobs", RemoteJob(deletedJob, 1_782_500_000_000L, true, "Usunięta", 2000, -7, null, "PLN", emptyList()))

            val taken = HashSet<Pair<Long, Int>>()
            var date = LocalDate.of(2026, 7, 1)
            while (!date.isAfter(LocalDate.of(2027, 6, 30))) {
                for (job in jobs) {
                    if (random.nextInt(10) >= job.weight) continue
                    var start = when (random.nextInt(4)) {
                        0 -> random.nextInt(1440)
                        1 -> 22 * 60
                        else -> (6 + random.nextInt(10)) * 60 + listOf(0, 15, 30, 45)[random.nextInt(4)]
                    }
                    while (!taken.add(date.toEpochDay() to start)) start = (start + 1) % 1440
                    val length = if (random.nextInt(40) == 0) 1440 else 60 + random.nextInt(14 * 60)
                    val end = (start + length) % 1440
                    val duration = if (end == start) 1440 else Math.floorMod(end - start, 1440)
                    val breakMinutes = when (random.nextInt(3)) {
                        0 -> 0
                        1 -> 30.coerceAtMost(duration - 1)
                        else -> random.nextInt(duration)
                    }
                    // Most shifts keep the job's rates; some were saved with older ones.
                    val rate = if (random.nextInt(6) == 0) job.rate - 50 + random.nextInt(100) else job.rate
                    val second = if (job.second != null && random.nextInt(5) == 0) null else job.second
                    put("shifts", RemoteShift(id(), t(), random.nextInt(25) == 0, job.id, date.toEpochDay(), start, end, breakMinutes, rate, null, null, second))
                }
                if (random.nextInt(9) == 0) {
                    val job = jobs[random.nextInt(jobs.size)]
                    put("bonuses", RemoteBonus(id(), t(), random.nextInt(10) == 0, job.id, date.toEpochDay(), 1 + random.nextInt(20_000).toLong(), null, false))
                }
                if (date.dayOfMonth == 1) {
                    val month = date.year * 100 + date.monthValue
                    for (job in jobs) {
                        if (random.nextInt(4) == 0) {
                            put("bonuses", RemoteBonus(id(), t(), random.nextInt(8) == 0, job.id, date.toEpochDay(), 5_000 + random.nextInt(50_000).toLong(), null, true))
                        }
                        repeat(listOf(0, 0, 1, 1, 2, 3)[random.nextInt(6)]) {
                            put("payouts", RemotePayout(id(), t(), random.nextInt(10) == 0, job.id, month, 10_000 + random.nextInt(400_000).toLong(), null, null))
                        }
                    }
                    if (random.nextInt(3) == 0) {
                        put("shifts", RemoteShift(id(), 1_782_400_000_000L, true, deletedJob, date.toEpochDay(), 8 * 60, 16 * 60, 0, 2000, null, null, null))
                    }
                }
                date = date.plusDays(1)
            }
            return docs
        }
    }
}

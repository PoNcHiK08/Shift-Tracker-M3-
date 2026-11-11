package com.vkurylo.shifttracker.ui.summary

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.model.PayType
import com.vkurylo.shifttracker.domain.model.Payout
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.domain.model.ThemeMode
import com.vkurylo.shifttracker.domain.model.UserPreferences
import com.vkurylo.shifttracker.domain.repository.BonusRepository
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.PreferencesRepository
import com.vkurylo.shifttracker.domain.repository.ShiftRepository
import com.vkurylo.shifttracker.ui.FakePayoutRepository
import com.vkurylo.shifttracker.ui.components.LocalSnackbarController
import com.vkurylo.shifttracker.ui.components.SnackbarController
import com.vkurylo.shifttracker.ui.theme.ShiftTrackerTheme
import com.vkurylo.shifttracker.ui.util.Formatters
import com.vkurylo.shifttracker.ui.util.RECEIVED_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.Locale

/**
 * Summary → Month → Received pay: what arrived next to the calculated NET pay, per job, with the difference; the
 * payments can be added, edited and deleted. The totals count what arrived instead of the calculated pay.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h2400dp")
class ReceivedPayScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val november = YearMonth.of(2026, 11)
    private val locale = Locale.getDefault()

    // A café with 10 % deductions; a 3 000 € salary job with 20 %.
    private val cafe = Job(
        id = 1, name = "Kawiarnia", hourlyRateGrosze = 3000, color = 1,
        payHistory = listOf(PaySettings(deductionBp = 1_000)), firstEntry = november.atDay(1), lastEntry = november.atDay(3),
    )
    private val office = Job(
        id = 2, name = "Biuro", hourlyRateGrosze = 0, color = 2, currency = "EUR",
        payHistory = listOf(PaySettings(null, PayType.FIXED_MONTHLY, 300_000, deductionBp = 2_000)),
        firstEntry = november.atDay(3), lastEntry = november.atDay(3),
    )
    private val shifts = listOf(
        ShiftWithJob(Shift(id = 1, jobId = 1, date = november.atDay(3), start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3000), cafe),
        ShiftWithJob(Shift(id = 2, jobId = 2, date = november.atDay(3), start = LocalTime.of(9, 0), end = LocalTime.of(17, 0), hourlyRateGrosze = 0), office),
    )
    private val monthBonus = BonusWithJob(Bonus(id = 7, jobId = 1, date = november.atDay(1), amountGrosze = 50_000, wholeMonth = true), cafe)

    // 8 h at 30 zł = 240 zł and the 500 zł month bonus, each less 10 %: 216 + 450 = 666 zł. The office: 3 000 € less 20 %.
    private val cafeNet = 21_600L + 45_000L
    private val officeNet = 240_000L

    private val firstPayment = Payout(id = 1, jobId = 1, month = november, amountMinor = 60_000, note = "Przelew", receivedOn = LocalDate.of(2026, 12, 2))

    private var mode = EarningsMode.GROSS
    private val prefs = MutableStateFlow(UserPreferences(ThemeMode.SYSTEM, true, EarningsMode.GROSS))

    private val shiftRepository = object : ShiftRepository {
        override fun observeShifts(from: LocalDate, to: LocalDate): Flow<List<ShiftWithJob>> =
            flowOf(shifts.filter { !it.shift.date.isBefore(from) && !it.shift.date.isAfter(to) })
        override suspend fun getShift(id: Long): Shift? = null
        override suspend fun getAllShifts(): List<Shift> = emptyList()
        override suspend fun upsert(shift: Shift): Long = 0
        override suspend fun importFromCalendar(shifts: List<Shift>) = Unit
        override suspend fun delete(shift: Shift) = Unit
    }
    private val bonusRepository = object : BonusRepository {
        override fun observeBonuses(from: LocalDate, to: LocalDate): Flow<List<BonusWithJob>> =
            flowOf(listOf(monthBonus).filter { !it.bonus.date.isBefore(from) && !it.bonus.date.isAfter(to) })
        override suspend fun getBonus(id: Long): Bonus? = null
        override suspend fun upsert(bonus: Bonus): Long = 0
        override suspend fun delete(bonus: Bonus) = Unit
    }
    private val jobRepository = object : JobRepository {
        override fun observeJobs(): Flow<List<Job>> = flowOf(listOf(cafe, office))
        override suspend fun getJob(id: Long): Job? = null
        override suspend fun upsert(job: Job): Long = 0
        override suspend fun save(job: Job, pay: PaySettings): Long = 0
        override suspend fun delete(job: Job) = Unit
        override suspend fun shiftCount(jobId: Long): Int = 0
        override suspend fun entryCount(jobId: Long): Int = 0
        override suspend fun latestCurrency(): String? = null
    }
    private val preferencesRepository = object : PreferencesRepository {
        override val preferences: Flow<UserPreferences> = prefs
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override suspend fun setDynamicColor(enabled: Boolean) = Unit
        override suspend fun setEarningsMode(mode: EarningsMode) { prefs.value = prefs.value.copy(earningsMode = mode) }
    }

    private fun money(minor: Long, currency: String = "PLN") = Formatters.money(minor, currency, locale)

    /** The row of [jobId] shows every one of [texts] (searched in the unmerged tree: the row is one clickable node). */
    private fun assertRowShows(jobId: Long, vararg texts: String) {
        for (text in texts) {
            val found = composeRule.onAllNodes(hasText(text, substring = true) and hasAnyAncestor(hasTestTag(RECEIVED_PAY_ROW_TAG + jobId)), useUnmergedTree = true)
            assertTrue("'$text' is not in the row of job $jobId", found.fetchSemanticsNodes().isNotEmpty())
        }
    }
    private fun text(res: Int) = context.getString(res)

    private fun show(
        period: SummaryPeriod = SummaryPeriod.MONTH,
        month: YearMonth = november,
        payments: List<Payout> = listOf(firstPayment),
    ): Pair<SummaryViewModel, FakePayoutRepository> {
        val payouts = FakePayoutRepository(listOf(cafe, office), payments)
        val handle = SavedStateHandle(mapOf("period" to period.name, "anchor" to month.atDay(10).toEpochDay()))
        val vm = SummaryViewModel(handle, shiftRepository, bonusRepository, jobRepository, preferencesRepository, payouts)
        val editor = PayoutEditorViewModel(payouts, CoroutineScope(Dispatchers.Unconfined))
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalSnackbarController provides SnackbarController(SnackbarHostState(), scope)) {
                ShiftTrackerTheme(darkTheme = false, dynamicColor = false) {
                    SummaryScreen(viewModel = vm, payoutEditor = editor)
                }
            }
        }
        composeRule.waitUntil(5_000) { !vm.uiState.value.loading }
        return vm to payouts
    }

    @Test
    fun theMonthShowsWhatArrivedNextToTheNetCalculationPerJob() {
        show()

        composeRule.onNodeWithText(text(R.string.received_pay_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.received_pay_caption)).assertIsDisplayed()

        // Calculated net (after the 10 % deductions), what arrived and the difference.
        assertRowShows(cafe.id, "Kawiarnia", money(cafeNet), money(60_000) + " · 1 payment", signed(60_000 - cafeNet))

        // Nothing entered for the office: its calculated net is there and "not entered", with no difference.
        assertRowShows(office.id, money(officeNet, "EUR"), text(R.string.received_pay_not_entered))
        composeRule.onNode(hasText(text(R.string.received_pay_difference)) and hasAnyAncestor(hasTestTag(RECEIVED_PAY_ROW_TAG + office.id)), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    private fun signed(minor: Long) = (if (minor > 0) "+" else "") + money(minor)

    @Test
    fun theComparisonIsAgainstNetEvenWhenTheScreensShowGross() {
        val (vm, _) = show()
        assertEquals(EarningsMode.GROSS, prefs.value.earningsMode)
        // Gross is what the calculation shows (the total counts the 600 zł received instead); the received-pay row
        // still compares with the net figure.
        val cafeRow = vm.uiState.value.summary.perJob.single { it.job.id == 1L }
        assertEquals(24_000L + 50_000L, cafeRow.calculatedEarningsMinor)
        assertEquals(60_000L, cafeRow.earningsGrosze)
        assertEquals(cafeNet, vm.uiState.value.receivedPay.single { it.job.id == 1L }.calculatedNetMinor)
        assertRowShows(cafe.id, money(cafeNet))
    }

    @Test
    fun receivedPayReplacesTheCalculatedTotalOfItsJobMonthAndNothingElse() {
        // Was "received pay never changes any total": received pay now counts as the real money of its job-month,
        // instead of (never on top of) the calculated figure; hours, shifts and the rate stay calculated.
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        fun viewModel(payments: List<Payout>) = SummaryViewModel(
            SavedStateHandle(mapOf("period" to "MONTH", "anchor" to november.atDay(10).toEpochDay())),
            shiftRepository, bonusRepository, jobRepository, preferencesRepository, FakePayoutRepository(listOf(cafe, office), payments),
        ).also { vm -> scope.launch { vm.uiState.collect { } } }

        val with = viewModel(listOf(firstPayment, firstPayment.copy(id = 2, amountMinor = 99_999_999)))
        val without = viewModel(emptyList())
        composeRule.waitUntil(5_000) { !with.uiState.value.loading && !without.uiState.value.loading }

        val calculated = without.uiState.value.summary
        val actual = with.uiState.value.summary
        assertEquals(mapOf("PLN" to (24_000L + 50_000L), "EUR" to 300_000L), calculated.totalEarnings.byCurrency)
        assertEquals(mapOf("PLN" to (60_000L + 99_999_999L), "EUR" to 300_000L), actual.totalEarnings.byCurrency)
        assertEquals(calculated.totalPaidMinutes, actual.totalPaidMinutes)
        assertEquals(calculated.shiftCount, actual.shiftCount)
        assertEquals(calculated.bonusCount, actual.bonusCount)
        assertEquals(calculated.averageRate("PLN"), actual.averageRate("PLN"))
        assertEquals(calculated.perJob.single { it.job.id == 2L }, actual.perJob.single { it.job.id == 2L })
        // The comparison figures differ as before.
        assertTrue(with.uiState.value.receivedPay.any { it.receivedMinor > 0 })
        assertTrue(without.uiState.value.receivedPay.all { it.receivedMinor == 0L })
        scope.cancel()
    }

    @Test
    fun aPaymentIsAddedFromTheJobsSheetForThatMonth() {
        val (_, payouts) = show()

        composeRule.onNodeWithTag(RECEIVED_PAY_ROW_TAG + office.id).performClick()
        composeRule.onNodeWithTag(RECEIVED_PAY_SHEET_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.received_pay_sheet_title).replace("%1\$s", "Biuro")).assertIsDisplayed()
        composeRule.onNodeWithTag(RECEIVED_PAY_ADD_TAG).performClick()

        composeRule.onNodeWithTag(RECEIVED_PAY_AMOUNT_TAG).performTextInput("2400,50")
        // The job's currency is the payment's.
        composeRule.onNodeWithText("€").assertIsDisplayed()
        composeRule.onNodeWithTag(RECEIVED_PAY_SAVE_TAG).performClick()

        composeRule.waitUntil(5_000) { payouts.upserts.isNotEmpty() }
        assertEquals(Payout(id = 0, jobId = 2, month = november, amountMinor = 240_050, note = null, receivedOn = null), payouts.upserts.single())

        // Back on the job's list, with the new payment counted and the difference shown.
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag(RECEIVED_PAY_ADD_TAG)).fetchSemanticsNodes().isNotEmpty() }
        // (Both the sheet and the row behind it show the figures.)
        assertTrue(composeRule.onAllNodesWithText(money(240_050, "EUR") + " · 1 payment", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("+" + money(50, "EUR"), substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun anInvalidAmountKeepsTheFormOpenWithAnError() {
        val (_, payouts) = show()
        composeRule.onNodeWithTag(RECEIVED_PAY_ROW_TAG + office.id).performClick()
        composeRule.onNodeWithTag(RECEIVED_PAY_ADD_TAG).performClick()

        composeRule.onNodeWithTag(RECEIVED_PAY_AMOUNT_TAG).performTextInput("0")
        composeRule.onNodeWithTag(RECEIVED_PAY_SAVE_TAG).performClick()

        composeRule.onNodeWithText(text(R.string.error_amount_invalid)).assertIsDisplayed()
        assertTrue(payouts.upserts.isEmpty())
    }

    @Test
    fun aPaymentCanBeChangedAndMovedToAnotherMonth() {
        val (_, payouts) = show()

        composeRule.onNodeWithTag(RECEIVED_PAY_ROW_TAG + cafe.id).performClick()
        composeRule.onNodeWithTag(RECEIVED_PAY_PAYMENT_TAG + firstPayment.id).assertIsDisplayed()
        composeRule.onNodeWithTag(RECEIVED_PAY_PAYMENT_TAG + firstPayment.id).performClick()

        composeRule.onNodeWithTag(RECEIVED_PAY_AMOUNT_TAG).performTextClearance()
        composeRule.onNodeWithTag(RECEIVED_PAY_AMOUNT_TAG).performTextInput("650,50")
        composeRule.onNodeWithContentDescription(text(R.string.next_month)).performClick()
        composeRule.onNodeWithTag(RECEIVED_PAY_SAVE_TAG).performClick()

        composeRule.waitUntil(5_000) { payouts.upserts.isNotEmpty() }
        assertEquals(
            Payout(id = 1, jobId = 1, month = november.plusMonths(1), amountMinor = 65_050, note = "Przelew", receivedOn = LocalDate.of(2026, 12, 2)),
            payouts.upserts.single(),
        )
        assertEquals("one payment, moved - not a second one", 1, payouts.all.size)
    }

    @Test
    fun aPaymentCanBeDeletedFromItsForm() {
        val (_, payouts) = show()
        composeRule.onNodeWithTag(RECEIVED_PAY_ROW_TAG + cafe.id).performClick()
        composeRule.onNodeWithTag(RECEIVED_PAY_PAYMENT_TAG + firstPayment.id).performClick()

        composeRule.onNodeWithTag(RECEIVED_PAY_DELETE_TAG).performClick()

        composeRule.waitUntil(5_000) { payouts.deletes.isNotEmpty() }
        assertEquals(firstPayment, payouts.deletes.single())
        assertTrue(payouts.all.isEmpty())
    }

    @Test
    fun aMonthWithReceivedPayButNoEntriesIsNotEmpty() {
        val december = YearMonth.of(2026, 12)
        val (vm, _) = show(month = december, payments = listOf(Payout(id = 3, jobId = 1, month = december, amountMinor = 1_000)))

        composeRule.onNodeWithText(text(R.string.received_pay_title)).assertIsDisplayed()
        assertRowShows(cafe.id, money(1_000))
        composeRule.onNodeWithText(text(R.string.summary_empty_title)).assertDoesNotExist()
        // What arrived is the month's money now: 10 zł, received.
        assertTrue(!vm.uiState.value.summary.isEmpty)
        assertEquals(1_000L, vm.uiState.value.summary.totalEarnings["PLN"])
    }

    @Test
    fun theYearShowsOneLinePerJobWithReceivedPay() {
        val (vm, _) = show(period = SummaryPeriod.YEAR)

        composeRule.onNodeWithText(text(R.string.received_pay_title)).assertIsDisplayed()
        val line = vm.uiState.value.receivedPayYear.single()
        assertEquals(cafe, line.job)
        assertRowShows(cafe.id, money(60_000), money(cafeNet), signed(60_000 - cafeNet))
        // The jobs without payments have no line.
        composeRule.onNode(hasTestTag(RECEIVED_PAY_ROW_TAG + office.id)).assertDoesNotExist()
    }

    @Test
    fun theWeekHasNoReceivedPay() {
        val (vm, _) = show(period = SummaryPeriod.WEEK)
        composeRule.onNodeWithText(text(R.string.received_pay_title)).assertDoesNotExist()
        assertEquals(emptyList<Any>(), vm.uiState.value.receivedPay)
        assertEquals(emptyList<Any>(), vm.uiState.value.receivedPayYear)
    }

    /** How many "received" markers show [label]. */
    private fun markers(label: String) =
        composeRule.onAllNodes(hasText(label) and hasAnyAncestor(hasTestTag(RECEIVED_TAG)), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun allMarkers() = composeRule.onAllNodes(hasTestTag(RECEIVED_TAG), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun theMonthTotalAndTheJobWithPaymentsCarryTheReceivedMarker() {
        show()
        // The hero: the café's 600 zł received next to the office's calculated salary.
        assertEquals(1, markers(text(R.string.received_tag_part)))
        // By job: the café's whole figure is what arrived; the office has no marker.
        assertEquals(1, markers(text(R.string.received_tag_all)))
        assertEquals(2, allMarkers())
        // The hero and the café's row both show the 600 zł received.
        assertEquals(2, composeRule.onAllNodesWithText(money(60_000)).fetchSemanticsNodes().size)
        // The 500 zł month bonus is inside what arrived: no "including bonuses" on top of it.
        val including = context.getString(R.string.including_bonuses, "").trim()
        composeRule.onAllNodes(hasText(including, substring = true)).fetchSemanticsNodes().let { assertEquals(0, it.size) }
    }

    @Test
    fun withoutPaymentsThereIsNoMarker() {
        show(payments = emptyList())
        assertEquals(0, allMarkers())
    }

    @Test
    fun theYearMarksTheMonthWithPaymentsAndItsTotal() {
        show(period = SummaryPeriod.YEAR)
        val part = text(R.string.received_tag_part)
        // November's row says it includes received pay (its row is one node for accessibility).
        val label = Formatters.monthName(november, locale)
        val rows = composeRule.onAllNodes(hasContentDescription(label, substring = true) and hasContentDescription(part, substring = true)).fetchSemanticsNodes()
        assertEquals(1, rows.size)
        // The year total, too.
        assertTrue(markers(part) >= 1)
    }

    @Test
    fun theWeekShowsNoMarker() {
        show(period = SummaryPeriod.WEEK)
        assertEquals(0, allMarkers())
    }
}

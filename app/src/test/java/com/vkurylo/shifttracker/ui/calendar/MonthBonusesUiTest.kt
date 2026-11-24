package com.vkurylo.shifttracker.ui.calendar

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.ui.theme.ShiftTrackerTheme
import com.vkurylo.shifttracker.ui.util.EarningsDisplay
import com.vkurylo.shifttracker.ui.util.Formatters
import com.vkurylo.shifttracker.ui.util.LocalEarningsDisplay
import org.junit.Assert.assertEquals
import com.vkurylo.shifttracker.domain.model.MonthSummary
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.YearMonth
import java.util.Locale

/** The bonus line of "Earned this month" and the list it opens: every bonus of the month, per currency, one line always. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class MonthBonusesUiTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val november = YearMonth.of(2026, 11)
    private val cafe = Job(id = 1, name = "Kawiarnia", hourlyRateGrosze = 3000, color = 1, payHistory = listOf(PaySettings(deductionBp = 1_000)))
    private val office = Job(id = 2, name = "Biuro", hourlyRateGrosze = 0, color = 2, currency = "EUR")
    private val items = listOf(
        BonusWithJob(Bonus(id = 7, jobId = 1, date = november.atDay(1), amountGrosze = 50_000, note = "Premia kwartalna", wholeMonth = true), cafe),
        BonusWithJob(Bonus(id = 8, jobId = 2, date = november.atDay(1), amountGrosze = 10_000, wholeMonth = true), office),
    )

    private fun money(minor: Long, currency: String) = Formatters.money(minor, currency, Locale.getDefault())

    private fun show(mode: EarningsMode = EarningsMode.GROSS, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalEarningsDisplay provides EarningsDisplay(mode = mode)) {
                ShiftTrackerTheme(darkTheme = false, dynamicColor = false) { content() }
            }
        }
    }

    private val dayBonus = BonusWithJob(Bonus(id = 9, jobId = 1, date = november.atDay(14), amountGrosze = 20_000, note = "Napiwki"), cafe)
    private val strip = MonthSummary.EMPTY

    private fun line(items: List<BonusWithJob>, mode: EarningsMode = EarningsMode.GROSS) =
        show(mode) { MonthStrip(strip, defaultCurrency = "PLN", bonuses = items, onBonusesClick = {}) }

    @Test
    fun oneBonusShowsItsAmountAndSeveralTheirCountPerCurrency() {
        line(items.take(1))
        composeRule.onNodeWithText(context.getString(R.string.bonuses_line_one, "+${money(50_000, "PLN")}")).assertIsDisplayed()
    }

    @Test
    fun severalBonusesSayHowManyWithOneTotalPerCurrency() {
        line(items + dayBonus)
        // Never one total across currencies.
        val amounts = "+${money(70_000, "PLN")} · ${money(10_000, "EUR")}"
        val text = context.resources.getQuantityString(R.plurals.bonuses_line_many, 3, amounts, 3)
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    @Test
    fun aMonthWithoutBonusesStillHasTheLine() {
        line(emptyList())
        composeRule.onNodeWithText(context.getString(R.string.bonuses_line_none)).assertIsDisplayed()
    }

    @Test
    fun inNetModeTheLineShowsTheAmountAfterTheJobsDeductions() {
        line(items.take(1), EarningsMode.NET)
        composeRule.onNodeWithText(context.getString(R.string.bonuses_line_one, "+${money(45_000, "PLN")}")).assertIsDisplayed()
    }

    @Test
    fun tappingTheEarningsTileOpensTheBonusesEvenWithoutAny() {
        var opened = 0
        show { MonthStrip(strip, defaultCurrency = "PLN", bonuses = emptyList(), onBonusesClick = { opened++ }) }
        composeRule.onNodeWithTag(MONTH_TILE_EARNINGS).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theStripIsEquallyTallWithNoBonusesAndWithAVeryLongAmount() {
        var bonuses by mutableStateOf(emptyList<BonusWithJob>())
        show { MonthStrip(strip, defaultCurrency = "PLN", bonuses = bonuses, onBonusesClick = {}) }
        composeRule.waitForIdle()
        val empty = composeRule.onNodeWithTag(MONTH_TILE_EARNINGS).getUnclippedBoundsInRoot()
        val line = composeRule.onNodeWithTag(MONTH_BONUSES_LINE_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        // A huge amount in two currencies, three bonuses: the line shrinks its text, never wraps.
        val huge = Job(id = 3, name = "Hotel", hourlyRateGrosze = 0, color = 3, currency = "UAH")
        bonuses = items + dayBonus + BonusWithJob(Bonus(id = 10, jobId = 3, date = november.atDay(20), amountGrosze = 99_999_999_999), huge)
        composeRule.waitForIdle()
        val full = composeRule.onNodeWithTag(MONTH_TILE_EARNINGS).getUnclippedBoundsInRoot()
        val fullLine = composeRule.onNodeWithTag(MONTH_BONUSES_LINE_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(empty.bottom - empty.top, full.bottom - full.top)
        assertEquals(line.bottom - line.top, fullLine.bottom - fullLine.top)
        assertTrue("the line stays inside the tile", fullLine.right <= full.right)
    }

    @Test
    fun theMonthsBonusesAreTheWholeMonthOnesThenTheDayOnesOfThatMonthOnly() {
        val october = BonusWithJob(Bonus(id = 11, jobId = 1, date = november.minusMonths(1).atDay(30), amountGrosze = 1), cafe)
        val early = dayBonus.copy(bonus = dayBonus.bonus.copy(id = 12, date = november.atDay(2)))
        val byDate = listOf(dayBonus, early, october).groupBy { it.bonus.date }
        assertEquals(listOf(7L, 8L, 12L, 9L), bonusesOfMonth(november, items, byDate).map { it.bonus.id })
    }

    @Test
    fun theListShowsEveryMonthBonusAndLetsYouEditOrAddOne() {
        val edited = mutableListOf<Long>()
        var added = 0
        show { MonthBonusesSheet(november, items + dayBonus, onDismiss = {}, onEdit = { edited += it }, onAdd = { added++ }) }

        composeRule.onNodeWithText(context.getString(R.string.bonuses_sheet_title, Formatters.monthYear(november, Locale.getDefault()))).assertIsDisplayed()
        // Two of Kawiarnia's: one for the whole month, one for a day.
        assertEquals(2, composeRule.onAllNodesWithText(context.getString(R.string.bonus_for_job, "Kawiarnia")).fetchSemanticsNodes().size)
        composeRule.onNodeWithText(context.getString(R.string.bonus_for_whole_month) + " · Premia kwartalna").assertIsDisplayed()
        // A bonus for a day says which day.
        composeRule.onNodeWithText(Formatters.shortDate(november.atDay(14), Locale.getDefault()) + " · Napiwki").assertIsDisplayed()
        composeRule.onNodeWithText("+${money(50_000, "PLN")}").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.bonus_for_job, "Biuro")).assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(R.string.bonus_for_job, "Biuro")).performClick()
        assertEquals(listOf(8L), edited)
        composeRule.onNodeWithTag(MONTH_BONUSES_ADD_TAG).performClick()
        assertEquals(1, added)
    }

    @Test
    fun anEmptyListSaysSoAndStillOffersToAddOne() {
        var added = 0
        show { MonthBonusesSheet(november, emptyList(), onDismiss = {}, onEdit = {}, onAdd = { added++ }) }
        composeRule.onNodeWithText(context.getString(R.string.bonuses_sheet_none)).assertIsDisplayed()
        composeRule.onNodeWithTag(MONTH_BONUSES_ADD_TAG).performClick()
        assertEquals(1, added)
    }
}

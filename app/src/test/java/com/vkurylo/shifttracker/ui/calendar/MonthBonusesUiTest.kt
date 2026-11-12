package com.vkurylo.shifttracker.ui.calendar

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.YearMonth
import java.util.Locale

/** The calendar's "Month bonuses" row and the list it opens: what the bonuses for the whole month add up to. */
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

    @Test
    fun theRowSaysHowManyAndHowMuchPerCurrencyAndOpensTheList() {
        var opened = 0
        show { MonthBonusesRow(items, onClick = { opened++ }) }

        composeRule.onNodeWithText(context.getString(R.string.month_bonuses_row, 2)).assertIsDisplayed()
        // One total per currency, never one across them.
        composeRule.onNodeWithText("+${money(50_000, "PLN")} · ${money(10_000, "EUR")}", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(MONTH_BONUSES_ROW_TAG).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun inNetModeTheRowShowsTheAmountAfterTheJobsDeductions() {
        show(EarningsMode.NET) { MonthBonusesRow(items.take(1), onClick = {}) }
        composeRule.onNodeWithText("+${money(45_000, "PLN")}", substring = true).assertIsDisplayed()
    }

    @Test
    fun theListShowsEveryMonthBonusAndLetsYouEditOrAddOne() {
        val edited = mutableListOf<Long>()
        var added = 0
        show { MonthBonusesSheet(november, items, onDismiss = {}, onEdit = { edited += it }, onAdd = { added++ }) }

        composeRule.onNodeWithText(context.getString(R.string.month_bonuses_sheet_title, Formatters.monthYear(november, Locale.getDefault()))).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.bonus_for_job, "Kawiarnia")).assertIsDisplayed()
        composeRule.onNodeWithText("Premia kwartalna").assertIsDisplayed()
        composeRule.onNodeWithText("+${money(50_000, "PLN")}").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.bonus_for_job, "Biuro")).assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(R.string.bonus_for_job, "Biuro")).performClick()
        assertEquals(listOf(8L), edited)
        composeRule.onNodeWithTag(MONTH_BONUSES_ADD_TAG).performClick()
        assertEquals(1, added)
    }
}

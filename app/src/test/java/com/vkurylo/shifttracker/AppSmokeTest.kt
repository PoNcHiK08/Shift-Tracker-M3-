package com.vkurylo.shifttracker

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import com.vkurylo.shifttracker.ui.util.Formatters
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * End-to-end smoke test through the real app: create a job, log a shift, browse every
 * screen and delete the shift again. Catches crashes that unit tests can't see.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppSmokeTest {

    private val hiltRule = HiltAndroidRule(this)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hiltRule).around(composeRule)

    private fun str(id: Int) = composeRule.activity.getString(id)

    /** Waits until a clickable node with this text (or content description) exists, then clicks it. */
    private fun clickOn(label: String) {
        val matcher = (hasText(label) or hasContentDescription(label)) and hasClickAction()
        composeRule.waitUntilExactlyOneExists(matcher, timeoutMillis = 10_000)
        composeRule.onNode(matcher).performClick()
        composeRule.waitForIdle()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntilAtLeastOneExists(hasText(text, substring = true), timeoutMillis = 10_000)
        composeRule.waitForIdle() // let screen transitions finish
    }

    @Test
    fun fullFlow() {
        // Empty calendar asks for a job first.
        waitForText(str(R.string.no_jobs_title))
        composeRule.onAllNodesWithText(str(R.string.add_job)).onFirst().performScrollTo().performClick()

        // Jobs screen -> create a job via the FAB.
        waitForText(str(R.string.jobs_title))
        composeRule.onNode(hasContentDescription(str(R.string.add_job)) and hasClickAction()).performClick()
        waitForText(str(R.string.new_job))
        composeRule.onNode(hasSetTextAction() and hasText(str(R.string.job_name))).performTextInput("Kawiarnia")
        composeRule.onNode(hasSetTextAction() and hasText(str(R.string.hourly_rate))).performTextInput("30,50")
        // Second (night) rate: 37 zł/h 23:00–06:00 — doesn't touch the default 08–16 shift below.
        composeRule.onNodeWithText(str(R.string.second_rate_toggle)).performClick()
        waitForText(str(R.string.second_rate))
        composeRule.onNode(hasSetTextAction() and hasText(str(R.string.second_rate))).performTextInput("37")
        composeRule.onNodeWithText(str(R.string.save)).performScrollTo().performClick()
        waitForText("Kawiarnia")
        waitForText("23:00–06:00")
        clickOn(str(R.string.back))

        // Calendar -> add a shift (08:00–16:00 default).
        composeRule.waitUntilAtLeastOneExists(hasContentDescription(str(R.string.add_shift)), 10_000)
        composeRule.onNode(hasContentDescription(str(R.string.add_shift)) and hasClickAction()).performClick()
        waitForText(str(R.string.new_shift))
        composeRule.onNodeWithText(str(R.string.save)).performClick()

        // The shift shows up in the month list: 8 h * 30.50 = 244.00 zł.
        waitForText("244")
        composeRule.onAllNodesWithText("Kawiarnia").onFirst().assertExists()

        // Add a 75 zł bonus for today through the day's bottom sheet.
        val locale = composeRule.activity.resources.configuration.locales[0]
        val todayLabel = Formatters.fullDate(LocalDate.now(), locale)
        val todayCell = hasContentDescription(todayLabel, substring = true) and hasClickAction()
        composeRule.waitUntilAtLeastOneExists(todayCell, 10_000)
        composeRule.onNode(todayCell).performClick()
        clickOn(str(R.string.bonus))
        waitForText(str(R.string.new_bonus))
        composeRule.onNode(hasSetTextAction() and hasText(str(R.string.bonus_amount))).performTextInput("75")
        composeRule.onNode(hasSetTextAction() and hasText(str(R.string.bonus_note))).performTextInput("Premia")
        clickOn(str(R.string.save))
        waitForText("+75")
        // Month card: 244 + 75 = 319 zł.
        waitForText("319")

        // Summary and settings render; the bonus counts towards earnings.
        clickOn(str(R.string.nav_summary))
        waitForText(str(R.string.earnings_month))
        waitForText("319")
        waitForText(composeRule.activity.getString(R.string.including_bonuses, "").trim())
        clickOn(str(R.string.period_year))
        waitForText(str(R.string.earnings_year))
        waitForText(str(R.string.by_month))
        clickOn(str(R.string.period_week))
        waitForText(str(R.string.earnings_week))
        clickOn(str(R.string.previous_period))
        clickOn(str(R.string.current_period))
        clickOn(str(R.string.period_month))
        waitForText(str(R.string.earnings_month))
        clickOn(str(R.string.nav_settings))
        waitForText(str(R.string.jobs_and_rates))
        clickOn(str(R.string.theme_dark))
        clickOn(str(R.string.nav_calendar))

        // Open the shift and delete it.
        waitForText("Kawiarnia")
        composeRule.onAllNodesWithText("Kawiarnia").onFirst().performScrollTo().performClick()
        waitForText(str(R.string.edit_shift))
        clickOn(str(R.string.delete_shift))
        waitForText(str(R.string.delete_shift_title))
        composeRule.onNodeWithText(str(R.string.delete)).performClick()

        // Then the bonus.
        waitForText("+75")
        composeRule.onNode(hasText("+75", substring = true)).performScrollTo().performClick()
        waitForText(str(R.string.edit_bonus))
        clickOn(str(R.string.delete_bonus))
        waitForText(str(R.string.delete_bonus_title))
        composeRule.onNodeWithText(str(R.string.delete)).performClick()
        waitForText(str(R.string.no_shifts_title))
    }
}

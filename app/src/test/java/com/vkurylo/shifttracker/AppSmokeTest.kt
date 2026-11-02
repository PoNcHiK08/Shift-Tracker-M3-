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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

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
        composeRule.onNodeWithText(str(R.string.save)).performClick()
        waitForText("Kawiarnia")
        composeRule.onNodeWithContentDescription(str(R.string.back)).performClick()

        // Calendar -> add a shift (08:00–16:00 default).
        composeRule.waitUntilAtLeastOneExists(hasContentDescription(str(R.string.add_shift)), 10_000)
        composeRule.onNode(hasContentDescription(str(R.string.add_shift)) and hasClickAction()).performClick()
        waitForText(str(R.string.new_shift))
        composeRule.onNodeWithText(str(R.string.save)).performClick()

        // The shift shows up in the month list: 8 h * 30.50 = 244.00 zł.
        waitForText("244")
        composeRule.onAllNodesWithText("Kawiarnia").onFirst().assertExists()

        // Summary and settings render.
        composeRule.onNodeWithText(str(R.string.nav_summary)).performClick()
        waitForText(str(R.string.total_earnings))
        waitForText("244")
        composeRule.onNodeWithText(str(R.string.nav_settings)).performClick()
        waitForText(str(R.string.jobs_and_rates))
        composeRule.onNodeWithText(str(R.string.theme_dark)).performClick()
        composeRule.onNodeWithText(str(R.string.nav_calendar)).performClick()

        // Open the shift and delete it.
        waitForText("Kawiarnia")
        composeRule.onAllNodesWithText("Kawiarnia").onFirst().performScrollTo().performClick()
        waitForText(str(R.string.edit_shift))
        composeRule.onNodeWithContentDescription(str(R.string.delete_shift)).performClick()
        waitForText(str(R.string.delete_shift_title))
        composeRule.onNodeWithText(str(R.string.delete)).performClick()
        waitForText(str(R.string.no_shifts_title))
    }
}

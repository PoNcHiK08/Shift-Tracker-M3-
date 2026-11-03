package com.vkurylo.shifttracker

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.ui.util.Formatters
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.YearMonth
import javax.inject.Inject

/**
 * The calendar keeps the month the user paged to: after opening and closing an editor, and after
 * the activity is recreated (rotation, theme/language change, process restore).
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, sdk = [35])
class CalendarMonthRestoreTest {
    private val hiltRule = HiltAndroidRule(this)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hiltRule).around(composeRule)

    @Inject lateinit var jobs: JobRepository

    @Before
    fun setUp() = hiltRule.inject()

    private fun str(id: Int) = composeRule.activity.getString(id)

    private fun waitForText(text: String) {
        composeRule.waitUntilAtLeastOneExists(hasText(text), timeoutMillis = 10_000)
        composeRule.waitForIdle()
    }

    private fun waitGone(text: String) {
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }
        composeRule.waitForIdle()
    }

    private fun click(label: String, scroll: Boolean = false) {
        val matcher = (hasText(label) or hasContentDescription(label)) and hasClickAction()
        composeRule.waitUntilExactlyOneExists(matcher, timeoutMillis = 10_000)
        val node = composeRule.onNode(matcher)
        if (scroll) node.performScrollTo()
        node.performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun visibleMonthSurvivesEditorAndRecreation() {
        runBlocking { jobs.upsert(Job(name = "Kawiarnia", hourlyRateGrosze = 3050, color = 0xFF43A047.toInt())) }
        val locale = composeRule.activity.resources.configuration.locales[0]
        val nextMonth = Formatters.monthYear(YearMonth.now().plusMonths(1), locale)
        val thisMonth = Formatters.monthYear(YearMonth.now(), locale)

        waitForText(str(R.string.add_shift))
        click(str(R.string.next_month))
        waitForText(nextMonth)
        waitGone(thisMonth)

        // Editor round trip (the card/button grows into the editor and back).
        click(str(R.string.add_shift), scroll = true)
        waitForText(str(R.string.new_shift))
        click(str(R.string.close))
        waitGone(str(R.string.new_shift))
        waitForText(nextMonth)

        // Recreated activity: let everything settle (initial focus, transitions), then the calendar must
        // still be on the month the user paged to.
        composeRule.activityRule.scenario.recreate()
        waitForText(nextMonth)
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText(nextMonth).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText(thisMonth).fetchSemanticsNodes().isEmpty())
    }
}

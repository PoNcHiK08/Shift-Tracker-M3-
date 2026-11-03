package com.vkurylo.shifttracker

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.repository.BonusRepository
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.ShiftRepository
import com.vkurylo.shifttracker.ui.util.Formatters
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

/**
 * The M3 Expressive press feedback animates shapes on bouncy springs: segmented list items and day
 * cards round their corners, calendar days morph into a squircle, button groups and the period
 * arrows morph. A spring that overshoots must never produce an invalid shape (a negative corner
 * radius crashed the app before), so like a phone this test draws every frame of each press and
 * release, with real rendering.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, sdk = [35], qualifiers = "w384dp-h832dp-night-xxhdpi")
class PressMorphDrawTest {
    private val hiltRule = HiltAndroidRule(this)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hiltRule).around(composeRule)

    @Inject lateinit var jobs: JobRepository
    @Inject lateinit var shifts: ShiftRepository
    @Inject lateinit var bonuses: BonusRepository

    @Before
    fun setUp() = hiltRule.inject()

    private fun str(id: Int) = composeRule.activity.getString(id)

    private fun drawEveryFrame(millis: Long) {
        var t = 0L
        while (t < millis) {
            composeRule.mainClock.advanceTimeBy(16)
            composeRule.onRoot().captureToImage()
            t += 16
        }
    }

    /** Hold a finger on the element, then lift it off without clicking (cancel), drawing every frame. */
    private fun pressAndCancel(matcher: SemanticsMatcher) {
        composeRule.onNode(matcher).performTouchInput { down(center) }
        drawEveryFrame(400)
        composeRule.onNode(matcher).performTouchInput { cancel() }
        drawEveryFrame(800)
    }

    private fun clickAndDraw(matcher: SemanticsMatcher) {
        composeRule.onNode(matcher).performClick()
        drawEveryFrame(800)
    }

    @Test
    fun pressFeedbackDrawsEveryFrame() {
        val today = LocalDate.now()
        runBlocking {
            val id = jobs.upsert(Job(name = "Burger King", hourlyRateGrosze = 3300, color = 0xFFFB8C00.toInt()))
            shifts.upsert(Shift(jobId = id, date = today, start = LocalTime.of(18, 30), end = LocalTime.of(1, 30), hourlyRateGrosze = 3300))
            bonuses.upsert(Bonus(jobId = id, date = today, amountGrosze = 7500, note = "Premia"))
        }
        composeRule.waitUntilAtLeastOneExists(hasText("Premia"), 10_000)
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false

        // Calendar: both segments of today's group (shift on top, bonus below) and another day's cell.
        pressAndCancel(hasText("Burger King") and hasClickAction())
        pressAndCancel(hasText("Premia", substring = true) and hasClickAction())
        val locale = composeRule.activity.resources.configuration.locales[0]
        val otherDay = if (today.dayOfMonth == 1) today.plusDays(1) else today.minusDays(1)
        pressAndCancel(hasContentDescription(Formatters.fullDate(otherDay, locale), substring = true) and hasClickAction())

        // Summary: period group, the arrows (label slides), and a segmented row.
        clickAndDraw(hasContentDescription(str(R.string.nav_summary)) and hasClickAction())
        clickAndDraw(hasText(str(R.string.period_week)) and hasClickAction())
        clickAndDraw(hasContentDescription(str(R.string.previous_period)) and hasClickAction())
        clickAndDraw(hasContentDescription(str(R.string.next_period)) and hasClickAction())
        clickAndDraw(hasText(str(R.string.period_year)) and hasClickAction())

        // Settings: a segmented row and the theme button group.
        clickAndDraw(hasContentDescription(str(R.string.nav_settings)) and hasClickAction())
        pressAndCancel(hasText(str(R.string.jobs_and_rates)) and hasClickAction())
        clickAndDraw(hasText(str(R.string.theme_light)) and hasClickAction())
        clickAndDraw(hasText(str(R.string.theme_dark)) and hasClickAction())

        composeRule.mainClock.autoAdvance = true
    }
}

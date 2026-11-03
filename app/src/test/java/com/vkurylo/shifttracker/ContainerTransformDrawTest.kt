package com.vkurylo.shifttracker

import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.ShiftRepository
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
 * Regression for the crash "Corner size in Px can't be negative" (Galaxy S23+, Android 17): the
 * editor's corner radius is animated by a slightly under-damped spring and overshot below zero
 * at the end of the card -> editor container transform. A phone draws every frame, so this test
 * also draws every frame (real rendering) while the editor opens and closes.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, sdk = [35], qualifiers = "w393dp-h851dp-night-xxhdpi")
class ContainerTransformDrawTest {
    private val hiltRule = HiltAndroidRule(this)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hiltRule).around(composeRule)

    @Inject lateinit var jobs: JobRepository
    @Inject lateinit var shifts: ShiftRepository

    @Before
    fun setUp() = hiltRule.inject()

    private fun str(id: Int) = composeRule.activity.getString(id)

    /** Advance the clock one 60 Hz frame at a time and draw each frame, like a phone does. */
    private fun drawEveryFrame(millis: Long) {
        var t = 0L
        while (t < millis) {
            composeRule.mainClock.advanceTimeBy(16)
            composeRule.onRoot().captureToImage()
            t += 16
        }
    }

    @Test
    fun cardToEditorAndBackDrawsEveryFrame() {
        runBlocking {
            val id = jobs.upsert(Job(name = "Burger King", hourlyRateGrosze = 3300, color = 0xFFFB8C00.toInt()))
            shifts.upsert(
                Shift(jobId = id, date = LocalDate.now(), start = LocalTime.of(18, 30), end = LocalTime.of(1, 30), hourlyRateGrosze = 3300),
            )
        }
        composeRule.waitUntilAtLeastOneExists(hasText("Burger King"), 10_000)
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false

        // Card -> editor.
        composeRule.onNode(hasText("Burger King") and hasClickAction()).performClick()
        drawEveryFrame(1_500)
        // Editor -> card.
        composeRule.onNode(hasContentDescription(str(R.string.close)) and hasClickAction()).performClick()
        drawEveryFrame(1_500)
        // "Add shift" button -> editor -> back.
        composeRule.onNode(hasText(str(R.string.add_shift)) and hasClickAction()).performClick()
        drawEveryFrame(1_500)
        composeRule.onNode(hasContentDescription(str(R.string.close)) and hasClickAction()).performClick()
        drawEveryFrame(1_500)
        // "Bonus" button -> bonus editor -> back (same container transform).
        composeRule.onNode(hasText(str(R.string.bonus)) and hasClickAction()).performClick()
        drawEveryFrame(1_500)
        composeRule.onNode(hasContentDescription(str(R.string.close)) and hasClickAction()).performClick()
        drawEveryFrame(1_500)

        composeRule.mainClock.autoAdvance = true
    }
}

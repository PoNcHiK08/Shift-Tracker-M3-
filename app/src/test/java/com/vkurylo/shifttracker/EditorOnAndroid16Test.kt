package com.vkurylo.shifttracker

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.TimedRate
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.ShiftRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, sdk = [36], qualifiers = "w393dp-h851dp-night-xxhdpi")
/**
 * Regression for "opening a shift closes the app" (reported on a Galaxy S23+, Android 16, dark theme):
 * open the editor from a shift card and from "Add shift" on an Android 16 runtime with the phone's
 * screen size, and check the editor stays open after the container transform has finished.
 */
class EditorOnAndroid16Test {
    private val hiltRule = HiltAndroidRule(this)
    private val composeRule = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(hiltRule).around(composeRule)
    @Inject lateinit var jobs: JobRepository
    @Inject lateinit var shifts: ShiftRepository
    @Before fun setUp() = hiltRule.inject()
    private fun str(id: Int) = composeRule.activity.getString(id)

    @Test
    fun openEditorFromCardAndButton() {
        val today = LocalDate.now()
        val night = TimedRate(3700, LocalTime.of(23, 0), LocalTime.of(6, 0))
        runBlocking {
            val id = jobs.upsert(Job(name = "Burger King", hourlyRateGrosze = 3300, color = 0xFFFB8C00.toInt(), secondRate = night))
            shifts.upsert(Shift(jobId = id, date = today, start = LocalTime.of(18, 30), end = LocalTime.of(1, 30), hourlyRateGrosze = 3300, secondRate = night))
        }
        composeRule.waitUntilAtLeastOneExists(hasText("Burger King"), 10_000)
        composeRule.waitForIdle()
        composeRule.onNode(hasText("Burger King") and hasClickAction()).performClick()
        composeRule.waitUntilAtLeastOneExists(hasText(str(R.string.edit_shift)), 10_000)
        composeRule.mainClock.advanceTimeBy(3000)
        composeRule.waitForIdle()
        assertFalse(composeRule.activity.isFinishing)
        composeRule.onNode(hasText(str(R.string.edit_shift))).assertExists()
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        composeRule.onNode(hasText(str(R.string.add_shift)) and hasClickAction()).performClick()
        composeRule.waitUntilAtLeastOneExists(hasText(str(R.string.new_shift)), 10_000)
        composeRule.mainClock.advanceTimeBy(3000)
        composeRule.waitForIdle()
        assertFalse(composeRule.activity.isFinishing)
        composeRule.onNode(hasText(str(R.string.new_shift))).assertExists()
    }
}

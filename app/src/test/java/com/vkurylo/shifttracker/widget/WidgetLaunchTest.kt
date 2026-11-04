package com.vkurylo.shifttracker.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.CrashReporter
import com.vkurylo.shifttracker.MainActivity
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.ShiftRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

/**
 * Widget taps open the right screen on a cold and a warm start; Back from an editor opened this way
 * lands on the calendar (and isn't an "unexpected finish"); a relaunch from Recents or a recreation
 * doesn't replay the tap; an open editor never gets a second one stacked on it.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
class WidgetLaunchTest {
    private val hiltRule = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hiltRule).around(composeRule)

    @Inject lateinit var jobs: JobRepository
    @Inject lateinit var shifts: ShiftRepository

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var controller: ActivityController<MainActivity>? = null
    private val activity get() = controller!!.get()
    private var shiftId = 0L

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            val job = jobs.upsert(Job(name = "Kawiarnia", hourlyRateGrosze = 3050, color = 0xFF43A047.toInt()))
            shiftId = shifts.upsert(
                Shift(jobId = job, date = LocalDate.now(), start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3050, note = "from the widget"),
            )
        }
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
    }

    private fun str(id: Int): String = activity.getString(id)

    /** A cold start from the widget: the activity is created with the widget's intent. */
    private fun launch(request: LaunchRequest?) {
        controller = Robolectric.buildActivity(MainActivity::class.java, WidgetLaunch.intent(context, request)).setup()
        composeRule.waitForIdle()
    }

    private fun waitFor(text: String) = composeRule.waitUntilAtLeastOneExists(hasText(text), 10_000)

    private fun count(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun back() {
        activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
    }

    /** A warm start: the activity is open (singleTop) and gets the widget's intent. */
    private fun newIntent(intent: Intent) {
        controller!!.newIntent(intent)
        composeRule.waitForIdle()
    }

    private fun newIntent(request: LaunchRequest) = newIntent(WidgetLaunch.intent(context, request))

    private fun assertOnCalendar() {
        waitFor(str(R.string.add_shift))
        assertEquals("home", CrashReporter.currentScreen)
    }

    @Test
    fun newShiftOpensTheEditorOnTodayAndBackLandsOnTheCalendar() {
        launch(LaunchRequest.NewShift)
        waitFor(str(R.string.new_shift))
        back()
        composeRule.waitUntil(10_000) { count(str(R.string.new_shift)) == 0 }
        assertOnCalendar()
    }

    @Test
    fun theNextShiftCardOpensThatShift() {
        launch(LaunchRequest.EditShift(shiftId))
        waitFor(str(R.string.edit_shift))
        waitFor("from the widget")
        back()
        composeRule.waitUntil(10_000) { count(str(R.string.edit_shift)) == 0 }
        assertOnCalendar()
    }

    @Test
    fun earningsOpenTheSummaryTabAndAddFirstJobOpensJobs() {
        launch(LaunchRequest.Summary)
        waitFor(str(R.string.summary_title))
        // Warm start (singleTop): the next tap arrives through onNewIntent.
        newIntent(LaunchRequest.Jobs)
        waitFor(str(R.string.jobs_title))
        back()
        waitFor(str(R.string.summary_title))
        assertEquals("home", CrashReporter.currentScreen)
    }

    @Test
    fun aRelaunchFromRecentsDoesNotReplayTheTap() {
        // Recents relaunches a finished task with its original intent plus FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY.
        // Handed over both ways: as the intent of a new activity and to a running one.
        val replay = WidgetLaunch.intent(context, LaunchRequest.NewShift).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(WidgetLaunch.parse(replay))
        controller = Robolectric.buildActivity(MainActivity::class.java, replay).setup()
        composeRule.waitForIdle()
        assertOnCalendar()
        assertEquals(0, count(str(R.string.new_shift)))
        newIntent(replay)
        assertEquals(0, count(str(R.string.new_shift)))
        assertOnCalendar()
    }

    @Test
    fun aConsumedTapIsClearedAndNotReplayedOnRecreation() {
        launch(LaunchRequest.NewShift)
        waitFor(str(R.string.new_shift))
        assertEquals(Intent.ACTION_MAIN, activity.intent.action)
        controller!!.recreate()
        composeRule.waitForIdle()
        waitFor(str(R.string.new_shift))
        // Still one editor: Back goes straight to the calendar.
        back()
        composeRule.waitUntil(10_000) { count(str(R.string.new_shift)) == 0 }
        assertOnCalendar()
    }

    @Test
    fun anOpenEditorIsNotStackedOn() {
        launch(LaunchRequest.EditShift(shiftId))
        waitFor(str(R.string.edit_shift))
        newIntent(LaunchRequest.NewShift)
        newIntent(LaunchRequest.Summary)
        // The editor is still there, alone: no "New shift" on top, and one Back reaches the calendar.
        assertEquals(0, count(str(R.string.new_shift)))
        waitFor(str(R.string.edit_shift))
        composeRule.onNode(hasContentDescription(str(R.string.close)) and hasClickAction()).assertExists()
        back()
        composeRule.waitUntil(10_000) { count(str(R.string.edit_shift)) == 0 }
        assertOnCalendar()
    }
}

package com.vkurylo.shifttracker.ui.navigation

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.ui.theme.ShiftTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The floating bar has one indicator pill that slides (never fades) to the selected tab, whatever selected it, with
 * a slight spring that never carries it off the icon; the first position is taken without animation.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
class FloatingNavBarTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val labels = HomeTabs.map { context.getString(it.labelRes) }
    private var selected by mutableIntStateOf(0)
    private val taps = mutableListOf<HomeTab>()

    private fun show(initial: Int) {
        selected = initial
        composeRule.setContent {
            ShiftTrackerTheme(darkTheme = false, dynamicColor = false) {
                HomeNavigationBar(tabs = HomeTabs, selectedIndex = selected, onSelect = { taps += it; selected = HomeTabs.indexOf(it) })
            }
        }
    }

    private fun bounds(tag: String): Rect = composeRule.onNode(hasTestTag(tag)).fetchSemanticsNode().boundsInRoot
    private fun indicators() = composeRule.onAllNodes(hasTestTag(HOME_BAR_INDICATOR_TAG)).fetchSemanticsNodes()
    private fun tab(index: Int) = composeRule.onNode(hasText(labels[index]) and hasClickAction())
    private fun tabCenter(index: Int): Float = tab(index).fetchSemanticsNode().boundsInRoot.center.x
    private fun pillCenter(): Float = bounds(HOME_BAR_INDICATOR_TAG).center.x
    private fun dp(value: Float) = value * composeRule.density.density

    private fun assertPillOn(index: Int) =
        assertEquals("pill on ${labels[index]}", tabCenter(index), pillCenter(), dp(0.5f))

    @Test
    fun theFirstPositionIsTakenWithoutSliding() {
        composeRule.mainClock.autoAdvance = false
        show(initial = 2)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        assertPillOn(2)
    }

    @Test
    fun aTapSlidesTheOnePillToTheTabAndSelectsIt() {
        show(initial = 0)
        composeRule.waitForIdle()
        assertPillOn(0)
        tab(1).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(HomeTabs[1]), taps)
        assertPillOn(1)
        assertEquals(true, tab(1).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        assertEquals(false, tab(0).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
    }

    @Test
    fun aChangeFromElsewhereSlidesItThereFrameByFrameWithoutFadingOrLeavingTheIcon() {
        show(initial = 0)
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        // Not a tap: e.g. a back gesture or the widget changed the tab.
        selected = 2
        val start = tabCenter(0)
        val target = tabCenter(2)
        // The pill may pass the target by less than the room it leaves around the 24 dp icon.
        val room = dp((64f - 24f) / 2)
        var between = 0
        var maxPast = 0f
        var settledAt = -1
        for (frame in 1..60) {
            composeRule.mainClock.advanceTimeByFrame()
            assertEquals("always exactly one pill (no per-tab indicators fading)", 1, indicators().size)
            val x = pillCenter()
            if (x > start + 1 && x < target - 1) between++
            maxPast = maxOf(maxPast, x - target)
            if (settledAt < 0 && abs(x - target) < dp(0.5f)) settledAt = frame
        }
        assertTrue("it slides through the tabs in between ($between frames)", between >= 5)
        assertTrue("past the target by ${maxPast / composeRule.density.density} dp", maxPast < room)
        assertTrue("it reaches the tab in about 300 ms (frame $settledAt)", settledAt in 10..30)
        assertPillOn(2)
    }

    @Test
    fun theBarIsFullyRoundedAndItsLabelsAlwaysShow() {
        show(initial = 1)
        composeRule.waitForIdle()
        labels.forEach { composeRule.onNode(hasText(it)).assertExists() }
        val bar = bounds(HOME_BAR_TAG)
        assertTrue("about 72 dp tall: ${bar.height / composeRule.density.density}", abs(bar.height - dp(72f)) < dp(1f))
        assertEquals(1, indicators().size)
        assertEquals(dp(64f), bounds(HOME_BAR_INDICATOR_TAG).width, dp(0.5f))
        assertEquals(dp(32f), bounds(HOME_BAR_INDICATOR_TAG).height, dp(0.5f))
    }
}

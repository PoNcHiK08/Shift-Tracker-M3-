package com.vkurylo.shifttracker

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.ui.navigation.HOME_BAR_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * In the real app: the floating bar keeps 16 dp from the sides and 8 dp above the system navigation bar, a tab's list
 * scrolls its last item clear of it, and scrolling a tab down hides the bar while up, the list's end or a tab switch
 * brings it back.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
class HomeFloatingBarLayoutTest {
    private val hiltRule = HiltAndroidRule(this)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hiltRule).around(composeRule)

    private fun dp(value: Float) = value * composeRule.density.density

    @Test
    fun theBarFloatsWithItsMarginsAndTheLastSettingIsNotHiddenBehindIt() {
        composeRule.waitUntilAtLeastOneExists(hasTestTag(HOME_BAR_TAG), 10_000)
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val bar = composeRule.onNode(hasTestTag(HOME_BAR_TAG)).fetchSemanticsNode().boundsInRoot
        val navInset = with(composeRule.density) {
            androidx.core.view.ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom?.toFloat() ?: 0f
        }
        assertEquals("left margin", dp(16f), bar.left - root.left, dp(1f))
        assertEquals("right margin", dp(16f), root.right - bar.right, dp(1f))
        assertEquals("gap above the system navigation bar", dp(8f), root.bottom - navInset - bar.bottom, dp(1f))

        val settings = activityString(R.string.nav_settings)
        composeRule.onNode(hasText(settings) and hasClickAction()).performClick()
        val version = activityString(R.string.version, BuildConfig.VERSION_NAME)
        composeRule.waitUntilAtLeastOneExists(hasScrollAction(), 10_000)
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(version))
        composeRule.waitForIdle()
        val last = composeRule.onNode(hasText(version), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val barNow = composeRule.onNode(hasTestTag(HOME_BAR_TAG)).fetchSemanticsNode().boundsInRoot
        assertTrue("the version line (bottom ${last.bottom}) ends above the bar (top ${barNow.top})", last.bottom <= barNow.top)
    }

    private fun activityString(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun barBounds() = composeRule.onNode(hasTestTag(HOME_BAR_TAG)).fetchSemanticsNode().boundsInRoot
    private fun rootBounds() = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
    private fun barShown() = barBounds().bottom < rootBounds().bottom
    private fun barGone() = barBounds().top >= rootBounds().bottom

    /** A slow drag of [distanceDp] on the tab's list: up (content scrolls down) when positive. */
    private fun drag(distanceDp: Float) {
        composeRule.onNode(hasScrollAction()).performTouchInput {
            val from = center.y + dp(distanceDp) / 2
            swipe(Offset(center.x, from), Offset(center.x, from - dp(distanceDp)), durationMillis = 600)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun scrollingDownHidesTheBarAndUpTheEndOrAnotherTabBringItBack() {
        composeRule.waitUntilAtLeastOneExists(hasTestTag(HOME_BAR_TAG), 10_000)
        composeRule.onNode(hasText(activityString(R.string.nav_settings)) and hasClickAction()).performClick()
        composeRule.waitUntilAtLeastOneExists(hasScrollAction(), 10_000)
        composeRule.waitForIdle()
        assertTrue(barShown())

        drag(10f)
        assertTrue("a tiny movement leaves it alone", barShown())
        drag(200f)
        assertTrue("scrolled down: it slid off the screen", barGone())
        drag(-200f)
        assertTrue("scrolled up: back", barShown())

        drag(200f)
        assertTrue(barGone())
        // Keep going to the end of the list: there it comes back.
        repeat(12) { drag(300f) }
        assertTrue("at the end of the list", barShown())

        // Hidden again near the end would need scrolling up first; hide it from the top instead.
        repeat(12) { drag(-300f) }
        drag(200f)
        assertTrue(barGone())
        // Back to the calendar tab (a back gesture): the bar is back for the new tab.
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertTrue("another tab", barShown())
    }
}

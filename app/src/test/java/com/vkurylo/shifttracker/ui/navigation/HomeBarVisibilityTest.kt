package com.vkurylo.shifttracker.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the floating bar hides and shows: only after the content moved a threshold one way (no flicker on small or
 * jittery movements), back at an end, and on demand (a tab switch).
 */
class HomeBarVisibilityTest {
    private val bar = HomeBarVisibility(thresholdPx = 60f)

    private fun scroll(vararg moves: Float) = moves.forEach { bar.onScrolled(it, 0f) }

    @Test
    fun scrollingDownPastTheThresholdHidesItAndUpShowsIt() {
        scroll(-20f, -20f)
        assertTrue("40 px is not enough", bar.visible)
        scroll(-25f)
        assertFalse(bar.visible)
        scroll(30f)
        assertFalse("30 px up is not enough", bar.visible)
        scroll(31f)
        assertTrue(bar.visible)
    }

    @Test
    fun jitterBackAndForthNeverTogglesIt() {
        repeat(50) { scroll(-50f, 45f) }
        assertTrue(bar.visible)
        scroll(-61f)
        assertFalse(bar.visible)
        repeat(50) { scroll(50f, -45f) }
        assertFalse(bar.visible)
    }

    @Test
    fun reachingTheEndOfTheListShowsIt() {
        scroll(-100f)
        assertFalse(bar.visible)
        // The list moved its last 10 px and couldn't take the remaining 15 px.
        bar.onScrolled(-10f, -15f)
        assertTrue(bar.visible)
    }

    @Test
    fun aTabSwitchShowsIt() {
        scroll(-100f)
        assertFalse(bar.visible)
        bar.show()
        assertTrue(bar.visible)
        // And a new scroll starts counting from zero.
        scroll(-59f)
        assertTrue(bar.visible)
    }
}

package com.vkurylo.shifttracker.ui.calendar

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.domain.model.MonthSummary
import com.vkurylo.shifttracker.ui.theme.ShiftTrackerTheme
import com.vkurylo.shifttracker.ui.util.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * The month tiles: the big earnings tile and the column of hours + shifts must line up at the top
 * and the bottom (in 1.0.11 the column was 12 dp taller), the two small tiles must be equal, and
 * nothing may be cut off with a large font, where all of them grow together instead.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measuring, so wrapping at large font sizes is real
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w384dp-h832dp-night-xxhdpi")
class MonthTilesLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    private val summary = MonthSummary(
        totalPaidMinutes = 43 * 60,
        shiftEarningsGrosze = 140_200,
        bonusGrosze = 7_500,
        shiftCount = 6,
        bonusCount = 1,
        perJob = emptyList(),
    )
    private val total = Formatters.money(summary.totalEarningsGrosze, Locale.US)
    private val bonusLine = "incl. bonuses " + Formatters.money(summary.bonusGrosze, Locale.US)

    private fun show(fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ShiftTrackerTheme(darkTheme = true, dynamicColor = false) {
                    // The calendar's width on a 384 dp phone (12 dp padding on each side).
                    Box(Modifier.width(384.dp).padding(horizontal = 12.dp)) {
                        MonthTiles(summary)
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun bounds(tag: String): DpRect = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun assertLinedUp(): Triple<DpRect, DpRect, DpRect> {
        val earnings = bounds(MONTH_TILE_EARNINGS)
        val hours = bounds(MONTH_TILE_HOURS)
        val shifts = bounds(MONTH_TILE_SHIFTS)
        assertEquals("tops differ", earnings.top.value, hours.top.value, 0.5f)
        assertEquals("bottoms differ", earnings.bottom.value, shifts.bottom.value, 0.5f)
        assertEquals("small tiles differ", (hours.bottom - hours.top).value, (shifts.bottom - shifts.top).value, 0.5f)
        return Triple(earnings, hours, shifts)
    }

    /** [text] (a counter exposes its whole value as one node) lies fully inside [tile], not cut off. */
    private fun assertInside(text: String, tile: DpRect) {
        val node = composeRule.onNodeWithText(text).getUnclippedBoundsInRoot()
        assertTrue("'$text' $node is cut off by its tile $tile", node.top >= tile.top && node.bottom <= tile.bottom)
    }

    private fun assertEverythingFits(earnings: DpRect, hours: DpRect, shifts: DpRect) {
        assertInside("43", hours)
        assertInside("6", shifts)
        assertInside(total, earnings)
        assertInside(bonusLine, earnings)
    }

    @Test
    fun tilesLineUpAndAreCompact() {
        show(fontScale = 1f)
        val (earnings, hours, shifts) = assertLinedUp()
        val height = (earnings.bottom - earnings.top).value
        // 1.0.11: a 148 dp big tile next to a 160 dp column.
        assertTrue("tiles are $height dp high", height in 136f..150f)
        assertEverythingFits(earnings, hours, shifts)
    }

    @Test
    fun withAHugeFontTheyGrowTogetherAndNothingIsCut() {
        show(fontScale = 2f)
        val (earnings, hours, shifts) = assertLinedUp()
        assertTrue("didn't grow with the font", (earnings.bottom - earnings.top).value > 160f)
        assertEverythingFits(earnings, hours, shifts)
    }
}

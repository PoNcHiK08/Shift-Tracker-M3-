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
import com.vkurylo.shifttracker.domain.model.Amounts
import com.vkurylo.shifttracker.domain.model.MonthSummary
import com.vkurylo.shifttracker.ui.util.RECEIVED_TAG
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
 * The month strip at the top of the calendar: earnings, hours and shifts side by side must line up
 * at the top and the bottom, stay compact (so the calendar and the day below keep their room), and
 * nothing may be cut off with a large font, where all of them grow together instead.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measuring, so wrapping at large font sizes is real
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w384dp-h832dp-night-xxhdpi")
class MonthStripLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    private val summary = MonthSummary(
        totalPaidMinutes = 43 * 60,
        shiftEarnings = Amounts.of("PLN", 140_200),
        bonuses = Amounts.of("PLN", 7_500),
        shiftCount = 6,
        bonusCount = 1,
        perJob = emptyList(),
    )
    private val total = Formatters.money(summary.totalEarnings["PLN"], "PLN", Locale.US)

    private fun show(fontScale: Float, summary: MonthSummary = this.summary) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ShiftTrackerTheme(darkTheme = true, dynamicColor = false) {
                    // The calendar's width on a 384 dp phone (12 dp padding on each side).
                    Box(Modifier.width(384.dp).padding(horizontal = 12.dp)) {
                        MonthStrip(summary, defaultCurrency = "PLN")
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
        listOf(hours, shifts).forEach {
            assertEquals("tops differ", earnings.top.value, it.top.value, 0.5f)
            assertEquals("bottoms differ", earnings.bottom.value, it.bottom.value, 0.5f)
        }
        assertTrue("not left to right", earnings.right <= hours.left && hours.right <= shifts.left)
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
    }

    @Test
    fun stripLinesUpAndIsCompact() {
        show(fontScale = 1f)
        val (earnings, hours, shifts) = assertLinedUp()
        val height = (earnings.bottom - earnings.top).value
        // The old tiles below the day were 144 dp high; this sits above the calendar.
        assertTrue("strip is $height dp high", height in 40f..56f)
        assertEverythingFits(earnings, hours, shifts)
    }

    @Test
    fun withAHugeFontItGrowsTogetherAndNothingIsCut() {
        show(fontScale = 2f)
        val (earnings, hours, shifts) = assertLinedUp()
        assertTrue("didn't grow with the font", (earnings.bottom - earnings.top).value > 64f)
        assertEverythingFits(earnings, hours, shifts)
    }

    @Test
    fun aMonthWithReceivedPayShowsTheMarkerInsideTheEarningsTileAndStillLinesUp() {
        // 1 477 zł calculated for the month, 1 200 zł received for it instead.
        val received = summary.copy(received = Amounts.of("PLN", 120_000), replaced = Amounts.of("PLN", 147_700))
        show(fontScale = 1f, summary = received)
        val (earnings, _, _) = assertLinedUp()
        val tag = composeRule.onNodeWithTag(RECEIVED_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the marker $tag is cut off by $earnings", tag.top >= earnings.top && tag.bottom <= earnings.bottom)
        composeRule.onNodeWithText("Received", useUnmergedTree = true).assertExists()
        assertInside(Formatters.money(120_000, "PLN", Locale.US), earnings)
    }

    @Test
    fun aCalculatedMonthHasNoMarker() {
        show(fontScale = 1f)
        composeRule.onNodeWithTag(RECEIVED_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    // --- The "Received" tag beside the amount ---------------------------------------------------------------

    private fun receivedSummary(receivedMinor: Long) =
        summary.copy(received = Amounts.of("PLN", receivedMinor), replaced = Amounts.of("PLN", 147_700))

    private fun tagBounds() = composeRule.onNodeWithTag(RECEIVED_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
    private fun amountBounds() = composeRule.onNodeWithTag(MONTH_TILE_AMOUNT, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun assertWithin(inner: DpRect, outer: DpRect, what: String) {
        assertTrue("$what $inner is cut off by $outer", inner.left >= outer.left && inner.right <= outer.right && inner.top >= outer.top && inner.bottom <= outer.bottom)
    }

    private fun assertNoOverlap(a: DpRect, b: DpRect) {
        val overlaps = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
        assertTrue("$a and $b overlap", !overlaps)
    }

    @Test
    fun theTagSitsBesideTheAmountOnItsRow() {
        show(fontScale = 1f, summary = receivedSummary(120_000))
        val (earnings, _, _) = assertLinedUp()
        val tag = tagBounds()
        val amount = amountBounds()
        assertTrue("the tag $tag is not right of the amount $amount", tag.left >= amount.right)
        val middle = (tag.top + tag.bottom) / 2
        assertTrue("the tag $tag is not on the amount's row $amount", middle >= amount.top && middle <= amount.bottom)
        assertWithin(tag, earnings, "the tag")
        assertWithin(amount, earnings, "the amount")
    }

    @Test
    fun aLongAmountWithAHugeFontPutsTheTagUnderTheAmountWithoutCuttingAnything() {
        show(fontScale = 2f, summary = receivedSummary(1_234_567))
        val (earnings, _, _) = assertLinedUp()
        val tag = tagBounds()
        val amount = amountBounds()
        assertTrue("the tag $tag didn't move under the amount $amount", tag.top >= amount.bottom - 0.5.dp)
        assertNoOverlap(tag, amount)
        assertWithin(tag, earnings, "the tag")
        assertInside(Formatters.money(1_234_567, "PLN", Locale.US), earnings)
    }

    @Test
    fun aLongAmountAtANormalFontKeepsEverythingInside() {
        show(fontScale = 1f, summary = receivedSummary(1_234_567))
        val (earnings, _, _) = assertLinedUp()
        val tag = tagBounds()
        assertNoOverlap(tag, amountBounds())
        assertWithin(tag, earnings, "the tag")
        assertInside(Formatters.money(1_234_567, "PLN", Locale.US), earnings)
    }

    @Test
    @Config(qualifiers = "uk-w384dp-h832dp-night-xxhdpi")
    fun theUkrainianTagFitsBesideOrUnderTheAmount() {
        show(fontScale = 1.3f, summary = receivedSummary(1_234_567))
        val (earnings, _, _) = assertLinedUp()
        composeRule.onNodeWithText("Отримано", useUnmergedTree = true).assertExists()
        val tag = tagBounds()
        assertNoOverlap(tag, amountBounds())
        assertWithin(tag, earnings, "the tag")
        assertWithin(amountBounds(), earnings, "the amount")
    }

    @Test
    fun aLongCalculatedAmountHasNoTag() {
        show(fontScale = 2f, summary = summary.copy(shiftEarnings = Amounts.of("PLN", 1_234_567)))
        composeRule.onNodeWithTag(RECEIVED_TAG, useUnmergedTree = true).assertDoesNotExist()
        assertLinedUp()
    }
}

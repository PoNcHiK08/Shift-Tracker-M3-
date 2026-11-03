package com.vkurylo.shifttracker

import android.graphics.Bitmap
import androidx.activity.BackEventCompat
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.ShiftRepository
import com.vkurylo.shifttracker.ui.navigation.EDITOR_CONTAINER_TAG
import com.vkurylo.shifttracker.ui.navigation.PREDICTIVE_BACK_MS
import com.vkurylo.shifttracker.ui.navigation.PredictiveBackGesture
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import kotlin.math.abs

/**
 * Predictive back driven through the real dispatcher (dispatchOnBackStarted / Progressed, then
 * onBackPressed or dispatchOnBackCancelled), one 16 ms frame at a time with real rendering.
 *
 * What this can prove: commit and cancel reach the right destination, nothing throws, the closing
 * screen keeps moving after release (no stall before it disappears) and is gone in time, the drift
 * follows the swipe edge, the editor follows the finger instead of collapsing at once, and normal
 * transitions afterwards are not affected. What it can't: how the gesture feels on a phone.
 *
 * Geometry is read from the semantics bounds of a stable node on the closing screen; these include
 * the graphics-layer scale/translation. Checks use small tolerances and windows of a few frames, not
 * exact per-frame monotonicity.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, sdk = [35], qualifiers = "en-rUS-w384dp-h832dp-night-xxhdpi")
class PredictiveBackGestureTest {
    private val hiltRule = HiltAndroidRule(this)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hiltRule).around(composeRule)

    @Inject lateinit var jobs: JobRepository
    @Inject lateinit var shifts: ShiftRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            val id = jobs.upsert(Job(name = "Kawiarnia", hourlyRateGrosze = 3050, color = 0xFF43A047.toInt()))
            shifts.upsert(Shift(jobId = id, date = LocalDate.now(), start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3050))
        }
        composeRule.waitUntilAtLeastOneExists(hasText("Kawiarnia"), 10_000)
        composeRule.waitForIdle()
    }

    private fun str(id: Int) = composeRule.activity.getString(id)
    private val dispatcher get() = composeRule.activity.onBackPressedDispatcher

    private fun click(label: String) {
        composeRule.onNode((hasText(label) or hasContentDescription(label)) and hasClickAction()).performClick()
        composeRule.waitForIdle()
    }

    private fun bounds(text: String): Rect? =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    private fun tabSelected(label: String): Boolean {
        val node = composeRule.onNode(hasContentDescription(label) and hasClickAction()).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On ||
            node.config.getOrNull(SemanticsProperties.Selected) == true
    }

    private fun event(p: Float, edge: Int): BackEventCompat {
        val w = composeRule.activity.window.decorView.width.toFloat()
        val x = if (edge == BackEventCompat.EDGE_LEFT) p * w * 0.6f else w - p * w * 0.6f
        return BackEventCompat(x, 1200f, p, edge)
    }

    private class Frame(val bounds: Rect?, val pixels: IntArray)

    /** One frame: advance the clock, draw it (as a phone would), keep a small copy and the tracked node's bounds. */
    private fun frame(tracked: String): Frame {
        composeRule.mainClock.advanceTimeBy(FRAME_MS)
        val image = composeRule.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val small = Bitmap.createScaledBitmap(image, image.width / 8, image.height / 8, true)
        val pixels = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
        // Bitmaps live in native memory; free them now rather than waiting for the GC.
        image.recycle()
        small.recycle()
        return Frame(bounds(tracked), pixels)
    }

    /** Mean absolute RGB difference of two frames, 0..255. */
    private fun diff(a: IntArray, b: IntArray): Double {
        var sum = 0L
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            sum += abs((x shr 16 and 0xFF) - (y shr 16 and 0xFF)) + abs((x shr 8 and 0xFF) - (y shr 8 and 0xFF)) + abs((x and 0xFF) - (y and 0xFF))
        }
        return sum / (a.size * 3.0)
    }

    private class Run(val drag: List<Rect?>, val after: List<Frame>)

    /** Drags to [progress] in 12 frames, holds 3, then commits or cancels and records [framesAfter]. */
    private fun gesture(
        tracked: String,
        edge: Int,
        progress: Float,
        commit: Boolean,
        framesAfter: Int = 40,
        duringDrag: () -> Unit = {},
    ): Run {
        composeRule.mainClock.autoAdvance = false
        val drag = mutableListOf<Rect?>()
        composeRule.runOnUiThread { dispatcher.dispatchOnBackStarted(event(0f, edge)) }
        drag += frame(tracked).bounds
        for (i in 1..12) {
            composeRule.runOnUiThread { dispatcher.dispatchOnBackProgressed(event(progress * i / 12, edge)) }
            drag += frame(tracked).bounds
        }
        repeat(3) { drag += frame(tracked).bounds }
        duringDrag()
        composeRule.runOnUiThread { if (commit) dispatcher.onBackPressed() else dispatcher.dispatchOnBackCancelled() }
        val after = List(framesAfter) { frame(tracked) }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        return Run(drag, after)
    }

    /**
     * After release (and NavHost's own [latency] frames) the closing screen must never sit still while
     * it can still be seen: every frame that is visibly different from the end state must also differ
     * from the frame [window] later (motion or fade). It must be gone within [maxFrames]. While it's
     * clearly visible, its tracked node must also keep shrinking (geometry, with a 1 px tolerance).
     */
    private fun assertContinuousExit(after: List<Frame>, maxFrames: Int, latency: Int = 3, window: Int = 3) {
        val gone = after.indexOfFirst { it.bounds == null }
        assertTrue("closing screen still there after ${after.size} frames", gone >= 0)
        assertTrue("closing screen took $gone frames to leave (max $maxFrames)", gone <= maxFrames)
        val end = after.last().pixels
        for (i in latency until gone) {
            val visibility = diff(after[i].pixels, end)
            if (visibility < VISIBLE) continue // already faded out
            val change = diff(after[i].pixels, after[i + window].pixels)
            assertTrue("visible screen stalled after release at frame $i (visible=$visibility, change=$change)", change >= MOVING)
            if (visibility >= CLEARLY_VISIBLE && i + window < gone) {
                val shrink = after[i].bounds!!.width - after[i + window].bounds!!.width
                assertTrue("visible screen stopped shrinking at frame $i (by $shrink px)", shrink >= 1f)
            }
        }
    }

    @Test
    fun tabGestureCommitContinuesAfterReleaseAndCompletes() {
        click(str(R.string.nav_settings))
        val tracked = str(R.string.jobs_and_rates)
        val full = bounds(tracked)!!
        val run = gesture(tracked, BackEventCompat.EDGE_RIGHT, 0.3f, commit = true) {
            // The pill moves only when the gesture is committed.
            assertTrue(tabSelected(str(R.string.nav_settings)))
        }
        val dragged = run.drag.last()!!
        assertTrue("didn't shrink while dragging", dragged.width < full.width * 0.95f)
        assertTrue("didn't drift towards the finger (right edge -> left)", dragged.center.x < full.center.x - 4)
        val frames = ((1 - 0.3f) * PREDICTIVE_BACK_MS / FRAME_MS).toInt()
        assertContinuousExit(run.after, maxFrames = frames + 6)
        assertTrue(tabSelected(str(R.string.nav_calendar)))
        assertNull(PredictiveBackGesture.closingEntryId)
    }

    @Test
    fun tabGestureCancelRestoresAndNormalTabSwitchStillWorks() {
        click(str(R.string.nav_settings))
        val tracked = str(R.string.jobs_and_rates)
        val full = bounds(tracked)!!
        val run = gesture(tracked, BackEventCompat.EDGE_RIGHT, 0.3f, commit = false)
        val restored = run.after.last().bounds!!
        assertEquals(full.width, restored.width, 1f)
        assertEquals(full.center.x, restored.center.x, 1f)
        assertTrue(tabSelected(str(R.string.nav_settings)))
        assertNull("gesture mark left behind after cancel", PredictiveBackGesture.closingEntryId)

        // An ordinary tab switch afterwards works and leaves no gesture state.
        click(str(R.string.nav_calendar))
        assertTrue(tabSelected(str(R.string.nav_calendar)))
        assertNull(bounds(tracked))
        assertNull(PredictiveBackGesture.closingEntryId)
    }

    @Test
    fun leftEdgeGestureDriftsRightAndClosesJobs() {
        click(str(R.string.nav_settings))
        click(str(R.string.jobs_and_rates))
        val tracked = "Kawiarnia"
        val full = bounds(tracked)!!
        val run = gesture(tracked, BackEventCompat.EDGE_LEFT, 0.3f, commit = true)
        val dragged = run.drag.last()!!
        assertTrue("didn't drift towards the finger (left edge -> right)", dragged.center.x > full.center.x + 4)
        val frames = ((1 - 0.3f) * PREDICTIVE_BACK_MS / FRAME_MS).toInt()
        assertContinuousExit(run.after, maxFrames = frames + 6)
        composeRule.waitUntilAtLeastOneExists(hasText(str(R.string.jobs_and_rates)), 5_000)
        assertNull(PredictiveBackGesture.closingEntryId)
    }

    /**
     * Editor -> card while the finger drives it: the container shrinks in step with the gesture (and
     * holds still when the finger does - a spring or any time-driven animation would keep moving),
     * then carries on into its card after release. Afterwards the normal container transform works.
     */
    @Test
    fun editorFollowsTheFingerThenFliesIntoItsCard() {
        click("Kawiarnia")
        val full = container()!!.height
        val heights = mutableListOf<Float?>()
        fun step() {
            composeRule.mainClock.advanceTimeBy(FRAME_MS)
            composeRule.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap().recycle()
            heights += container()?.height
        }
        fun dragTo(p: Float) {
            composeRule.runOnUiThread { dispatcher.dispatchOnBackProgressed(event(p, BackEventCompat.EDGE_RIGHT)) }
            step()
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { dispatcher.dispatchOnBackStarted(event(0f, BackEventCompat.EDGE_RIGHT)) }
        step()
        for (i in 1..6) dragTo(0.15f * i / 6)
        val atHalf = heights.size - 1
        repeat(8) { step() } // finger held still
        val afterHold = heights.size - 1
        for (i in 1..6) dragTo(0.15f + 0.15f * i / 6)
        repeat(3) { step() }
        val atEnd = heights.size - 1
        composeRule.runOnUiThread { dispatcher.onBackPressed() }
        val released = heights.size
        repeat(40) { step() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        val half = full - heights[atHalf]!!
        val end = full - heights[atEnd]!!
        assertTrue("editor didn't shrink while dragging: ${heights.take(atEnd + 1)}", end > full * 0.1f)
        assertEquals("editor moved while the finger was still", heights[atHalf]!!, heights[afterHold]!!, 3f)
        assertTrue("shrink not in step with the finger: half=$half end=$end", half / end in 0.35f..0.65f)
        // The way covered at progress 0.3 is about 0.3 of the way to the card. A spring, sampled on the
        // seeked timeline, would be most of the way there (measured: ~0.8) before the finger has moved.
        val card = heights.filterNotNull().min()
        val covered = (full - heights[atEnd]!!) / (full - card)
        assertTrue("editor ran ahead of the finger: covered $covered of the way at progress 0.3", covered in 0.15f..0.5f)
        // After release: keeps shrinking towards the card until it arrives (< 20 % of the screen).
        val after = heights.subList(released, heights.size)
        val arrived = after.indexOfFirst { it == null || it < full * 0.2f }
        assertTrue("editor never reached its card: $after", arrived in 1..((0.7f * PREDICTIVE_BACK_MS / FRAME_MS).toInt() + 6))
        val moving = after.subList(3, arrived).map { it!! }
        for (i in 0 until moving.size - 2) {
            assertTrue("editor stalled after release: ${moving.drop(i).take(3)}", moving[i] - moving[i + 2] >= 1f)
        }
        composeRule.waitUntil(5_000) { container() == null }
        assertNull(PredictiveBackGesture.closingEntryId)

        // The normal container transform afterwards: the card grows into the editor and back.
        click("Kawiarnia")
        composeRule.waitUntil(5_000) { container() != null }
        assertEquals(full, container()!!.height, 1f)
        click(str(R.string.close))
        composeRule.waitUntil(5_000) { container() == null }
    }

    private fun container(): Rect? =
        composeRule.onAllNodes(hasTestTag(EDITOR_CONTAINER_TAG)).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    private companion object {
        const val FRAME_MS = 16L

        /** Mean RGB difference (0..255) from the end state above which the closing screen still shows. */
        const val VISIBLE = 1.0
        const val CLEARLY_VISIBLE = 6.0

        /** Minimum mean RGB change over a window for a visible screen to count as moving/fading. */
        const val MOVING = 0.3
    }
}

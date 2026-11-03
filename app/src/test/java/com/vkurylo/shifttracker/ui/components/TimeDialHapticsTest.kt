package com.vkurylo.shifttracker.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerSelectionMode
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.ui.theme.ShiftTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w384dp-h832dp-xxhdpi")
class TimeDialHapticsTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private class CountingHaptics : PickerHaptics {
        var ticks = 0
        var confirms = 0
        override fun tick() { ticks++ }
        override fun confirm() { confirms++ }
    }

    private val dial = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)

    @Test
    fun opensSilentlyTicksOnUserChangesAndKeepsOneCollector() {
        val haptics = CountingHaptics()
        var title by mutableStateOf("Start")
        var confirmed: LocalTime? = null
        timeTickCollectorStarts.set(0)
        composeRule.setContent {
            CompositionLocalProvider(LocalPickerHaptics provides haptics) {
                ShiftTrackerTheme(darkTheme = false, dynamicColor = false) {
                    TimeDialog(title, LocalTime.of(8, 0), onConfirm = { confirmed = it }, onDismiss = {})
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals("opening the dialog must not tick", 0, haptics.ticks)
        assertEquals(1, timeTickCollectorStarts.get())

        // Recompositions with new parameters must not restart the collector (stable state identity).
        repeat(5) {
            title = "Start $it"
            composeRule.waitForIdle()
        }
        assertEquals("collector restarted on recomposition", 1, timeTickCollectorStarts.get())

        // A tap on "10" selects it: one tick.
        composeRule.onNodeWithContentDescription("10 hours").performClick()
        composeRule.waitForIdle()
        assertEquals(1, haptics.ticks)

        composeRule.onNodeWithText(context.getString(R.string.ok)).performClick()
        composeRule.waitForIdle()
        assertEquals(1, haptics.confirms)
        assertEquals(LocalTime.of(10, 0), confirmed)
        assertEquals(1, timeTickCollectorStarts.get())
    }

    @Test
    fun programmaticChangesDoNotTick() {
        val haptics = CountingHaptics()
        lateinit var state: TimePickerState
        composeRule.setContent {
            ShiftTrackerTheme(darkTheme = false, dynamicColor = false) {
                state = rememberTimePickerState(initialHour = 8, initialMinute = 0, is24Hour = true)
                val touch = remember { DialTouch() }
                TimeTickHaptics(state, haptics, touch)
                Box(Modifier.trackDialTouch(touch)) { TimePicker(state = state) }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { state.hour = 15 }
        composeRule.waitForIdle()
        composeRule.runOnIdle { state.minute = 30 }
        composeRule.waitForIdle()
        assertEquals("programmatic changes must not tick", 0, haptics.ticks)

        composeRule.onNodeWithContentDescription("10 hours").performClick()
        composeRule.waitForIdle()
        assertEquals(1, haptics.ticks)
    }

    /**
     * Measures, rather than asserts a feel: a fast drag around the minute dial. The tick rate goes to
     * the test output so throttling (minTickIntervalMs) can be decided after trying it on a phone.
     */
    @Test
    fun fastMinuteDragTicksAtMostOncePerFrame() {
        val haptics = CountingHaptics()
        lateinit var state: TimePickerState
        composeRule.setContent {
            ShiftTrackerTheme(darkTheme = false, dynamicColor = false) {
                state = rememberTimePickerState(initialHour = 8, initialMinute = 0, is24Hour = true)
                val touch = remember { DialTouch() }
                TimeTickHaptics(state, haptics, touch)
                Box(Modifier.trackDialTouch(touch)) { TimePicker(state = state) }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { state.selection = TimePickerSelectionMode.Minute }
        composeRule.waitForIdle()
        val start = state.minute

        val frameMs = 8L // 120 Hz, like the S23+
        val steps = 45 // three quarters of a turn in 45 frames = 0.36 s
        composeRule.mainClock.autoAdvance = false
        val node = composeRule.onNode(dial)
        fun point(width: Int, height: Int, step: Int): Offset {
            val angle = Math.toRadians(step * 270.0 / steps)
            val r = width * 0.38f
            return Offset(width / 2f + r * sin(angle).toFloat(), height / 2f - r * cos(angle).toFloat())
        }
        node.performTouchInput { down(point(width, height, 0)) }
        for (i in 1..steps) {
            composeRule.mainClock.advanceTimeBy(frameMs)
            node.performTouchInput { moveTo(point(width, height, i)) }
        }
        composeRule.mainClock.advanceTimeBy(frameMs)
        node.performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(200)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        val moved = (state.minute - start + 60) % 60
        val seconds = (steps + 1) * frameMs / 1000.0
        println("DIAL-HAPTICS: ${haptics.ticks} ticks for $moved minutes over ${steps + 1} frames (${"%.2f".format(seconds)} s) = ${"%.0f".format(haptics.ticks / seconds)} ticks/s")
        assertNotEquals("the drag didn't move the hand", 0, moved)
        assertTrue("no ticks during the drag", haptics.ticks > 0)
        assertTrue("more than one tick per frame", haptics.ticks <= steps + 1)
    }
}
